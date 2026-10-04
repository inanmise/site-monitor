package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kişi-webhook alıcı çözümü: alarm olayından (takım + seviye) sicil listesi üretir.
 *
 * <p>Zincir: aktif takım ÜYELERİ ({@code AppUser.teamIds ∋ event.teamId}) → rol-grubu süzgeci
 * (ORG ROLÜ ile kesin eşleşme: Yönetici = MANAGER/BOLUM_BASKANI/CLEVEL, Uzman = TECH, PO = PO)
 * → şiddet kuralı (Yönetici grubu yalnız HIGH/CRITICAL — mevcut müdür-eskalasyon sözleşmesinin
 * kanal içi eşleniği) → E1 opt-out.
 *
 * <p><b>Unvan (AD title) KULLANILMAZ (ürün kararı 2026-09-11).</b> Aynı rolde onlarca farklı unvan
 * var ("Yazılım Geliştirici", "Kıdemli Uzman", "Takım Lideri"…); desen listesi hiçbir zaman tam
 * olmuyor ve eşleşmeyen kişi sessizce dışarıda kalıyordu. Org rolü ({@code AppUser.orgRole}) zaten
 * AD kademesinden türer (PO bayrağı / D6 → MANAGER / D7 → BOLUM_BASKANI / diğer → TECH) ve yönetici
 * kullanıcı ekranından elle sabitleyebilir — tek ve denetlenebilir kaynak odur. Kayıtlı eski
 * yapılandırma ({@code source:"title"}) okunurken grup anahtarına göre org-rol kümesine çevrilir. E-posta/bildirim-grupları bu çözüme KARIŞMAZ: kanal bağımsızlığı
 * yalnız gönderimde değil ALICI SEÇİMİNDE de geçerli.
 *
 * <p>Grup yapılandırması {@code site.monitor.userpush.role-groups} JSON'undan CANLI okunur —
 * kurum unvanları değişince ayar yetişir, dağıtım gerekmez. Tüm gruplar varsayılan KAPALI:
 * hiçbir grup açılmadıysa kimseye gitmez (global anahtar gibi bilinçli sessiz başlangıç).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPushRecipientResolver {

    /** Varsayılan grup seti — ayar boşken de tanımlı olsun (hepsi KAPALI). */
    static final String DEFAULT_GROUPS_JSON = """
            {"uzman":{"enabled":false,"source":"orgRole","patterns":["TECH"],"minLevel":"WARNING"},
             "po":{"enabled":false,"source":"orgRole","patterns":["PO"],"minLevel":"WARNING"},
             "yonetici":{"enabled":false,"source":"orgRole","patterns":["MANAGER"],"minLevel":"HIGH"},
             "bolum_baskani":{"enabled":false,"source":"orgRole","patterns":["BOLUM_BASKANI"],"minLevel":"CRITICAL"},
             "clevel":{"enabled":false,"source":"orgRole","patterns":["CLEVEL"],"minLevel":"CRITICAL"}}""";

    /**
     * Sabit kademeler (ürün kararı 2026-09-11): kartlar sistemdeki org rolü listesinin birebir karşılığıdır
     * (Kullanıcı ekranındaki "Organizasyonel Rol" seçenekleri) — her kart TEK org rolü: Uzman = TECH,
     * PO = PO, Yönetici = MANAGER, Bölüm Başkanı = BOLUM_BASKANI, C-Level = CLEVEL. Yönetici yalnız aç/kapa
     * + asgari seviye seçer. Kayıtlı yapılandırma ne derse desin bilinen anahtarın rolü budur (eski unvan
     * desenleri ve ara sürümün çoklu-rol kümeleri de buna çevrilir); kayıtta olmayan kademe kapalı eklenir.
     * "Rol yok (üye)" = orgRole boş → hiçbir kart; "Kim alır?"da NO_ORG_ROLE.
     */
    static final Map<String, List<String>> TIER_ROLES = new LinkedHashMap<>();
    static {
        TIER_ROLES.put("uzman",         List.of("TECH"));
        TIER_ROLES.put("po",            List.of("PO"));
        TIER_ROLES.put("yonetici",      List.of("MANAGER"));
        TIER_ROLES.put("bolum_baskani", List.of("BOLUM_BASKANI"));
        TIER_ROLES.put("clevel",        List.of("CLEVEL"));
    }
    private static final Map<String, String> TIER_DEFAULT_MIN_LEVEL = Map.of(
            "uzman", "WARNING", "po", "WARNING", "yonetici", "HIGH", "bolum_baskani", "CRITICAL", "clevel", "CRITICAL");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppUserRepository userRepo;
    private final AppSettingsService appSettings;

    /** Kişisel push sessiz saati karar kodu (2026-10-01, onaylı öneri 15). */
    public static final String SKIPPED_USER_QUIET_HOURS = "SKIPPED_USER_QUIET_HOURS";

    /** Test kancası (paket-özel) — kişisel sessiz saat penceresi bu saatle değerlendirilir. */
    java.time.Clock clock = java.time.Clock.systemUTC();

    /**
     * Kişinin KENDİ push sessiz saati şu an bu seviyeyi bastırıyor mu? Global push sessiz saatinin kişi eşi: pencere
     * içinde asgari seviyenin altındaki push bu kişiye gitmez (satır {@code SKIPPED_USER_QUIET_HOURS}); KRİTİK asla
     * bastırılmaz. Alan boşsa (varsayılan) hiçbir şey değişmez — ek sorgu da yok (kullanıcı satırı zaten elde).
     */
    static boolean personalQuietBlocks(AppUser u, String level, java.time.Instant now) {
        if (u == null || u.getPushQuietStart() == null || u.getPushQuietEnd() == null) return false;
        QuietHours q = QuietHours.parse(u.getPushQuietStart(), u.getPushQuietEnd(), u.getPushQuietDays(),
                u.getPushQuietMinLevel());
        return q != null && q.defers(level) && q.activeAt(now);
    }

    // ── Kişisel bildirim tercihleri (2026-10-04, onaylı öneri 4) ───────────────────────────────────────────

    /** Kişi bu izleme ailesini push'ta istemiyor ({@code push_families} izin listesinde yok). */
    public static final String SKIPPED_USER_TYPE = "SKIPPED_USER_TYPE";
    /** Alarm seviyesi kişinin en düşük push seviyesinin ({@code push_min_level}) altında. */
    public static final String SKIPPED_USER_LEVEL = "SKIPPED_USER_LEVEL";
    /** Kişi push'u erteledi (susturdu); kritikler yine gelsin seçiliyse KRİTİK hariç. */
    public static final String SKIPPED_USER_SNOOZE = "SKIPPED_USER_SNOOZE";
    /** Eskalasyon adımı push'u: kişiye bağlı kullanıcı pasif (yalnız pasif eşleşme var). */
    public static final String SKIPPED_USER_INACTIVE = "SKIPPED_USER_INACTIVE";
    /** Eskalasyon adımı push'u: kişinin bağlı/e-postası eşleşen aktif kullanıcısı yok. */
    public static final String SKIPPED_NO_USER_MATCH = "SKIPPED_NO_USER_MATCH";
    /** Eskalasyon adımı push'u: kişinin e-postası birden çok aktif kullanıcıya eşleşiyor (kime gideceği belirsiz). */
    public static final String SKIPPED_AMBIGUOUS_USER = "SKIPPED_AMBIGUOUS_USER";

    /** Susturma şu an etkin mi (bitiş UTC damgası gelecekte). Bozuk damga = susturma YOK (bildirim engellenmesin). */
    public static boolean snoozeActive(AppUser u, java.time.Instant now) {
        if (u == null || u.getPushSnoozeUntil() == null || u.getPushSnoozeUntil().isBlank()) return false;
        java.time.Instant until = PushText.parseStoredUtc(u.getPushSnoozeUntil());
        return until != null && now != null && now.isBefore(until);
    }

    /** İzin listesi (küçük harf); null = hepsi. Boş/bozuk liste = hepsi (bozuk ayar bildirimi susturmasın). */
    static java.util.Set<String> allowedFamilies(AppUser u) {
        String raw = u == null ? null : u.getPushFamilies();
        if (raw == null || raw.isBlank()) return null;
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String p : raw.split(",")) {
            String f = p.trim().toLowerCase(Locale.ROOT);
            if (!f.isEmpty()) out.add(f);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Kişinin bildirim tercihleri bu push'u süzüyor mu? {@code null} = geçti. Sıra: aile → seviye → susturma (kalıcı
     * tercih önce, geçici susturma sonra — "neden gelmedi" sorusunun cevabı en kalıcı neden olur). Tercih yoksa (hepsi
     * null) her zaman null: davranış bugünküyle aynı.
     *
     * @param families olayın izleme aile(ler)i; null/boş = bilinmiyor → aile süzgeci UYGULANMAZ
     */
    public static String preferenceSkip(AppUser u, String level, java.util.Collection<String> families, java.time.Instant now) {
        if (u == null) return null;
        java.util.Set<String> allowed = allowedFamilies(u);
        if (allowed != null && families != null && !families.isEmpty()) {
            boolean any = false;
            for (String f : families) if (f != null && allowed.contains(f.toLowerCase(Locale.ROOT))) { any = true; break; }
            if (!any) return SKIPPED_USER_TYPE;
        }
        String min = u.getPushMinLevel();
        if (min != null && !min.isBlank() && levelValue(level) < levelValue(min.trim().toUpperCase(Locale.ROOT)))
            return SKIPPED_USER_LEVEL;
        if (snoozeActive(u, now)) {
            boolean criticalPasses = !Boolean.FALSE.equals(u.getPushSnoozeCritical())
                    && "CRITICAL".equalsIgnoreCase(level == null ? "" : level.trim());
            if (!criticalPasses) return SKIPPED_USER_SNOOZE;
        }
        return null;
    }

    /** Kişinin push dili ({@code tr}/{@code en}). */
    static String langOf(AppUser u) {
        return PushI18n.norm(u == null ? null : u.getPushLang());
    }

    /**
     * Çözüm sonucu: gönderilecekler + nedenleriyle atlananlar (görünmez sessizlik yok). {@code lang}: push dili;
     * {@code allowedFamilies}: kişinin aile izin listesi (null = hepsi) — aile süzgeci olayın ailesi bilinince
     * {@link #withFamilies} ile uygulanır (çözüm aileyi bilmeyen eski çağıranlar için aynı kalır).
     */
    public record Recipient(String username, String displayName, String skipReason, String lang,
                            java.util.Set<String> allowedFamilies) {
        /** Eski üç alanlı biçim — dil Türkçe, aile süzgeci yok (bugünkü davranış). */
        public Recipient(String username, String displayName, String skipReason) {
            this(username, displayName, skipReason, PushI18n.TR, null);
        }
        public Recipient(String username, String displayName, String skipReason, String lang) {
            this(username, displayName, skipReason, lang, null);
        }
        /** Dil her zaman normalize ({@code tr}/{@code en}). */
        public Recipient {
            lang = PushI18n.norm(lang);
        }
        Recipient withSkip(String reason) {
            return new Recipient(username, displayName, reason, lang, allowedFamilies);
        }
    }

    /** Aile süzgecinin EZEBİLECEĞİ (daha geçici) nedenler: aile kalıcı tercih olduğu için önce gelir. */
    private static final java.util.Set<String> FAMILY_OVERRIDABLE =
            java.util.Set.of(SKIPPED_USER_LEVEL, SKIPPED_USER_SNOOZE, SKIPPED_USER_QUIET_HOURS);

    /**
     * Olayın izleme aile(ler)i bilinince kişisel aile süzgecini uygular: izin listesi dolu ve olayın hiçbir ailesi listede
     * yoksa neden {@link #SKIPPED_USER_TYPE} olur (seviye/susturma/sessiz saat nedenini ezer; opt-out ve kişi-dışı
     * nedenleri ezmez). {@code families} boş/null = aile bilinmiyor → liste olduğu gibi döner.
     */
    public static List<Recipient> withFamilies(List<Recipient> recipients, java.util.Collection<String> families) {
        if (recipients == null || recipients.isEmpty() || families == null || families.isEmpty()) return recipients;
        List<Recipient> out = new ArrayList<>(recipients.size());
        for (Recipient r : recipients) {
            java.util.Set<String> allowed = r.allowedFamilies();
            if (allowed != null && (r.skipReason() == null || FAMILY_OVERRIDABLE.contains(r.skipReason()))
                    && families.stream().noneMatch(f -> f != null && allowed.contains(f.toLowerCase(Locale.ROOT)))) {
                out.add(r.withSkip(SKIPPED_USER_TYPE));
            } else {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * ÇÖZÜM (RESOLVE) alıcıları: açılışta gerçekten push ALAN kullanıcılar. Seviye eşiği, grup
     * kuralı, sessiz saat ve takım çözümlemesi burada UYGULANMAZ — karar açılışta verildi; "düştü"
     * mesajını alan herkes "düzeldi"yi de almalı (kullanıcı kararı 2026-09-10). Yalnız iki güncel
     * gerçek sorgulanır: hesap hâlâ aktif mi ve kişi o aradan opt-out yapmış mı.
     */
    public List<Recipient> resolvePrior(List<String> usernames) {
        Map<String, Recipient> out = new LinkedHashMap<>();
        for (String raw : usernames == null ? List.<String>of() : usernames) {
            String username = raw == null ? "" : raw.trim();
            if (username.isEmpty() || "-".equals(username) || out.containsKey(username)) continue;
            AppUser u = userRepo.findByUsername(username).orElse(null);
            if (u == null || !Boolean.TRUE.equals(u.getActive())) continue;   // ayrılmış/pasif hesaba çözüm gitmez
            String display = u.getDisplayName() != null ? u.getDisplayName() : username;
            // Kişisel tercihler (seviye/aile/susturma) ÇÖZÜMÜ tutmaz (2026-10-04): "düştü"yü alan "düzeldi"yi de almalı —
            // sessiz saatten muaf olmasının nedeniyle aynı (telefondaki alarm kapanmalı).
            out.put(username, Boolean.TRUE.equals(u.getPushOptOut())
                    ? new Recipient(username, display, "SKIPPED_USER_OPT_OUT", langOf(u))
                    : new Recipient(username, display, null, langOf(u)));
        }
        return new ArrayList<>(out.values());
    }

    /** Takım + seviye + olayın izleme aileleri → alıcılar ({@link #resolve(Long, String)} + {@link #withFamilies}). */
    public List<Recipient> resolve(Long teamId, String alertLevel, java.util.Collection<String> families) {
        return withFamilies(resolve(teamId, alertLevel), families);
    }

    /**
     * Takım + seviye → alıcılar. Kişisel tercihler (2026-10-04: seviye, susturma) opt-out'tan SONRA, kişisel sessiz saatten
     * ÖNCE değerlendirilir; süzülen kişi listede nedeniyle kalır (teslimat günlüğüne satırı yazılır). Aile süzgeci olayın
     * ailesini bilen çağıranda ({@link #withFamilies}) uygulanır — kişinin izin listesi alıcıda taşınır.
     */
    public List<Recipient> resolve(Long teamId, String alertLevel) {
        if (teamId == null) return List.of();
        Map<String, GroupRule> groups = groupRules();
        if (groups.values().stream().noneMatch(g -> g.enabled)) return List.of();

        int level = levelValue(alertLevel);
        java.time.Instant now = java.time.Instant.now(clock);
        // Tekilleştirme: çoklu takım üyeliğinde aynı kişi bir kez (LinkedHashMap sıra korur).
        Map<String, Recipient> out = new LinkedHashMap<>();
        for (AppUser u : userRepo.findByMembershipTeamId(teamId)) {
            if (!Boolean.TRUE.equals(u.getActive())) continue;
            GroupRule match = matchGroup(groups, u);
            if (match == null || !match.enabled) continue;                       // grubu yok/kapalı → aday değil
            if (level < levelValue(match.minLevel)) continue;                    // şiddet kuralı (Yönetici=HIGH+)
            String username = u.getUsername() == null ? "" : u.getUsername().trim();
            String display = u.getDisplayName() != null ? u.getDisplayName() : username;
            String lang = langOf(u);
            java.util.Set<String> allowed = allowedFamilies(u);
            String pref;
            if (username.isEmpty()) {
                out.putIfAbsent("(bos)#" + u.getId(), new Recipient("-", display, "SKIPPED_NO_ID"));
            } else if (Boolean.TRUE.equals(u.getPushOptOut())) {
                out.putIfAbsent(username, new Recipient(username, display, "SKIPPED_USER_OPT_OUT", lang, allowed));
            } else if ((pref = preferenceSkip(u, alertLevel, null, now)) != null) {
                // Kişisel tercih (2026-10-04): seviye / susturma — satır nedeniyle kalır (sessiz kayıp yok).
                out.putIfAbsent(username, new Recipient(username, display, pref, lang, allowed));
            } else if (personalQuietBlocks(u, alertLevel, now)) {
                // Kişisel sessiz saat (2026-10-01): kişi penceresinde, seviye asgari seviyesinin altında — satır kalır.
                out.putIfAbsent(username, new Recipient(username, display, SKIPPED_USER_QUIET_HOURS, lang, allowed));
            } else {
                out.put(username, new Recipient(username, display, null, lang, allowed));       // opt-out satırını ezebilir mi?
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * "Kim alır, kim almaz ve NEDEN?" — {@link #resolve} ile AYNI kuralları uygular ama elenenleri de
     * gerekçesiyle döndürür (2026-09-11, kullanıcı: aynı takımdaki bir üyeye push gitmiyor, nedeni görünmüyor).
     * resolve() elenen üyeyi hiç yazmaz (teslimat günlüğü yalnız adayları taşır) — bu yüzden ayrı yüzey.
     */
    public record Explanation(String username, String displayName, String title, String orgRole, boolean active,
                              String group, Boolean groupEnabled, String minLevel, boolean optOut, String decision) {}

    public List<Explanation> explain(Long teamId, String alertLevel) {
        List<Explanation> out = new ArrayList<>();
        if (teamId == null) return out;
        Map<String, GroupRule> groups = groupRules();
        boolean anyEnabled = groups.values().stream().anyMatch(g -> g.enabled);
        int level = levelValue(alertLevel);
        java.time.Instant now = java.time.Instant.now(clock);
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();   // id null olabilir → ad+id anahtarı
        for (AppUser u : userRepo.findByMembershipTeamId(teamId)) {
            String username = u.getUsername() == null ? "" : u.getUsername().trim();
            String display = u.getDisplayName() != null ? u.getDisplayName() : username;
            boolean active = Boolean.TRUE.equals(u.getActive());
            boolean optOut = Boolean.TRUE.equals(u.getPushOptOut());
            GroupRule match = matchGroup(groups, u);
            String decision;
            if (!active) decision = "INACTIVE";
            else if (!anyEnabled) decision = "ALL_GROUPS_OFF";
            else if (u.getOrgRole() == null || u.getOrgRole().isBlank()) decision = "NO_ORG_ROLE";   // veri eksik: kullanıcı ekranından atanır
            else if (match == null) decision = "NO_GROUP";
            else if (!match.enabled) decision = "GROUP_DISABLED";
            else if (level < levelValue(match.minLevel)) decision = "BELOW_MIN_LEVEL";
            else if (username.isEmpty()) decision = "SKIPPED_NO_ID";
            else if (optOut) decision = "SKIPPED_USER_OPT_OUT";
            else if (preferenceSkip(u, alertLevel, null, now) != null) decision = preferenceSkip(u, alertLevel, null, now);   // aile bilinmez (senaryo)
            else if (personalQuietBlocks(u, alertLevel, now)) decision = SKIPPED_USER_QUIET_HOURS;   // resolve() ile aynı sıra
            else decision = "RECIPIENT";
            seen.add(seenKey(u));
            out.add(new Explanation(username.isEmpty() ? "-" : username, display, u.getTitle(), u.getOrgRole(), active,
                    match == null ? null : match.key, match == null ? null : match.enabled,
                    match == null ? null : match.minLevel, optOut, decision));
        }
        // BİRİNCİL takımı bu takım olan ama app_user_teams'te SATIRI OLMAYAN kullanıcı: alıcı çözümü
        // üyelik tablosundan yürüdüğü için böyle biri sessizce hiç aday olmaz. Bu bir VERİ kusurudur
        // (çoklu-takım göçünün geri doldurması ıskalamış); ekranda ayrı kararla görünür.
        for (AppUser u : userRepo.findByTeamIdOrderByUsernameAsc(teamId)) {
            if (seen.contains(seenKey(u))) continue;
            String username = u.getUsername() == null ? "" : u.getUsername().trim();
            out.add(new Explanation(username.isEmpty() ? "-" : username,
                    u.getDisplayName() != null ? u.getDisplayName() : username,
                    u.getTitle(), u.getOrgRole(), Boolean.TRUE.equals(u.getActive()),
                    null, null, null, Boolean.TRUE.equals(u.getPushOptOut()), "MISSING_MEMBERSHIP"));
        }
        return out;
    }

    /**
     * Eskalasyon kişisi → TEK aktif uygulama kullanıcısı (2026-10-04, onaylı öneri 6). {@code skipReason} doluysa push
     * gitmez ve neden satıra yazılır: bağlı kullanıcı / e-posta eşleşmesi yok ({@link #SKIPPED_NO_USER_MATCH}), e-posta
     * birden çok AKTİF kullanıcıya eşleşiyor ({@link #SKIPPED_AMBIGUOUS_USER}), yalnız pasif eşleşme var
     * ({@link #SKIPPED_USER_INACTIVE}). Eşleşme varsa kişisel kurallar ({@link #resolve} ile aynı sıra: opt-out → tercih →
     * kişisel sessiz saat) {@code recipient.skipReason}'a yazılır.
     *
     * <p>Öncelik: kişideki açık bağ ({@code escalation_contacts.user_id}); yoksa büyük/küçük harf duyarsız TEKİL e-posta.
     * Rol grubu / takım üyeliği SORULMAZ — kişi eskalasyon listesinde olduğu için adımın alıcısıdır (e-postanın aynası).
     */
    public record ContactMatch(Recipient recipient, String skipReason) {}

    public ContactMatch resolveContact(com.sitemonitor.model.EscalationContact c, String alertLevel,
                                       java.util.Collection<String> families) {
        if (c == null) return new ContactMatch(null, SKIPPED_NO_USER_MATCH);
        AppUser u = null;
        if (c.getUserId() != null) {
            u = userRepo.findById(c.getUserId()).orElse(null);
            if (u != null && !Boolean.TRUE.equals(u.getActive())) return new ContactMatch(null, SKIPPED_USER_INACTIVE);
        }
        if (u == null) {
            String email = c.getEmail() == null ? "" : c.getEmail().trim().toLowerCase(Locale.ROOT);
            if (email.isEmpty()) return new ContactMatch(null, SKIPPED_NO_USER_MATCH);
            List<AppUser> all = userRepo.findAllByEmailLower(email);
            List<AppUser> active = all.stream().filter(x -> Boolean.TRUE.equals(x.getActive())).toList();
            if (active.size() > 1) return new ContactMatch(null, SKIPPED_AMBIGUOUS_USER);
            if (active.isEmpty()) return new ContactMatch(null, all.isEmpty() ? SKIPPED_NO_USER_MATCH : SKIPPED_USER_INACTIVE);
            u = active.get(0);
        }
        String username = u.getUsername() == null ? "" : u.getUsername().trim();
        if (username.isEmpty()) return new ContactMatch(null, "SKIPPED_NO_ID");
        String display = u.getDisplayName() != null ? u.getDisplayName() : username;
        String lang = langOf(u);
        java.time.Instant now = java.time.Instant.now(clock);
        String skip;
        if (Boolean.TRUE.equals(u.getPushOptOut())) skip = "SKIPPED_USER_OPT_OUT";
        else if ((skip = preferenceSkip(u, alertLevel, families, now)) != null) { /* tercih */ }
        else if (personalQuietBlocks(u, alertLevel, now)) skip = SKIPPED_USER_QUIET_HOURS;
        return new ContactMatch(new Recipient(username, display, skip, lang), null);
    }

    /** Tekilleştirme anahtarı: id (varsa) + kullanıcı adı — henüz kalıcılaşmamış kayıtta id null olabilir. */
    private static String seenKey(AppUser u) {
        String name = u.getUsername() == null ? "" : u.getUsername().trim().toUpperCase(Locale.ROOT);
        return (u.getId() == null ? "?" : u.getId().toString()) + "#" + name;
    }

    /** Kullanıcının ORG ROLÜYLE eşleştiği İLK açık grup; açık grup eşleşmezse kapalı eşleşme; hiç yoksa null.
     *  Unvan (title) burada OKUNMAZ — bkz. sınıf Javadoc'u. */
    private GroupRule matchGroup(Map<String, GroupRule> groups, AppUser u) {
        GroupRule fallback = null;
        for (GroupRule g : groups.values()) {
            boolean hit = matchesAny(u.getOrgRole(), g.patterns, true);
            if (!hit) continue;
            if (g.enabled) return g;
            if (fallback == null) fallback = g;
        }
        return fallback;
    }

    /** Basit joker eşleme: {@code *X*} içeren desenler; Türkçe harfe duyarlı lower ile. */
    static boolean matchesAny(String value, List<String> patterns, boolean exact) {
        if (value == null || value.isBlank() || patterns == null) return false;
        String v = value.toLowerCase(new Locale("tr", "TR")).trim();
        for (String p : patterns) {
            if (p == null || p.isBlank()) continue;
            String pat = p.toLowerCase(new Locale("tr", "TR")).trim();
            if (exact) { if (v.equals(pat)) return true; continue; }
            String core = pat.replace("*", "");
            boolean starts = pat.startsWith("*"), ends = pat.endsWith("*");
            if (starts && ends) { if (v.contains(core)) return true; }
            else if (ends)      { if (v.startsWith(core)) return true; }
            else if (starts)    { if (v.endsWith(core)) return true; }
            else                { if (v.equals(core)) return true; }
        }
        return false;
    }

    record GroupRule(String key, boolean enabled, String source, List<String> patterns, String minLevel) {}

    Map<String, GroupRule> groupRules() {
        String json = appSettings.getString("site.monitor.userpush.role-groups", DEFAULT_GROUPS_JSON);
        Map<String, GroupRule> out = new LinkedHashMap<>();
        try {
            JsonNode root = MAPPER.readTree(json == null || json.isBlank() ? DEFAULT_GROUPS_JSON : json);
            root.properties().forEach(e -> {
                JsonNode n = e.getValue();
                List<String> raw = new ArrayList<>();
                if (n.path("patterns").isArray()) n.path("patterns").forEach(x -> raw.add(x.asText()));
                // Bilinen kademenin rolü SABİT (TIER_ROLES); kayıttaki desenler (eski unvan desenleri ya da ara
                // sürümün çoklu-rol kümesi) yok sayılır. Bilinmeyen anahtar (özel grup): desenler org-rol kodu sayılır.
                List<String> pats = TIER_ROLES.containsKey(e.getKey()) ? TIER_ROLES.get(e.getKey()) : raw;
                out.put(e.getKey(), new GroupRule(e.getKey(),
                        n.path("enabled").asBoolean(false),
                        "orgRole",
                        pats,
                        n.path("minLevel").asText(TIER_DEFAULT_MIN_LEVEL.getOrDefault(e.getKey(), "WARNING"))));
            });
            // Kayıtta olmayan bilinen kademe (ör. eski kayıtta "bolum_baskani" yok) varsayılanıyla, KAPALI eklenir.
            TIER_ROLES.forEach((key, roles) -> out.putIfAbsent(key,
                    new GroupRule(key, false, "orgRole", roles, TIER_DEFAULT_MIN_LEVEL.get(key))));
        } catch (Exception ex) {
            log.warn("userpush role-groups ayrıştırılamadı — tüm gruplar kapalı sayılıyor: {}", ex.toString());
        }
        return out;
    }

    static int levelValue(String level) {
        return switch (level == null ? "" : level) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            default -> 1;
        };
    }
}
