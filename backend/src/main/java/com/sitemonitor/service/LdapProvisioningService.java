package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserTeamSource;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Maps the AD attributes of a freshly-authenticated user into our AppUser/Team
 * model (Faz 3a). Called from the login flow after a successful LDAP bind.
 *
 * <p>Rol kuralları (2026-08, güncel): ürün sahibi ({@code company} içinde "PRODUCT OWNER")
 * ve müdür (kendisine bağlı çalışan olan) → systemRole=TEAM_ADMIN; kalan herkes → USER.
 * LDAP HİÇ KİMSEYE sistem-geneli ADMIN vermez: hem PO hem müdür kendi takım(lar)ının
 * yöneticisidir. ADMIN yalnız yerel bootstrap hesabında ve admin panelinden elle verilerek
 * bulunur. orgRole ayrıca AD'den türetilir (PO > D6 MANAGER > D7 BOLUM_BASKANI > TECH).
 * İlişkiler (managerId, mudurluk, team) her girişte tazelenir; role_locked/orgRoleLocked
 * işaretli kullanıcılara dokunulmaz, elle yükseltilmiş ADMIN/AUDIT düşürülmez.
 *
 * <p><b>2026-09-26 (prod hatası: üye olmayan kullanıcı takımda görünüyor, müdür verisi karışık).</b>
 * <ul>
 *   <li>Her türetilmiş üyeliğin KAYNAĞI yazılır ({@link UserTeamSource}: AD grubu / company).</li>
 *   <li>AD hiç takım vermediğinde eskiden TÜM eski üyelikler sonsuza dek kalıyordu. Artık (ayar
 *       {@value #PRUNE_KEY}, varsayılan açık) yalnız AD'den türetilmiş olanlar budanır; elle eklenen ve
 *       kaynağı bilinmeyen üyelik girişte silinmez — yönetici "AD ile karşılaştır"da görüp karar verir.</li>
 *   <li>{@code manager_id} artık HER ZAMAN {@code manager_sicil} ile tutarlı: yeni sicil çözülemezse
 *       eski müdüre asılı kalmıyor (sicil yeni müdürü, id eski müdürü gösteriyordu); AD'de müdür yoksa
 *       ikisi de temizlenir.</li>
 *   <li>Müdür sicili sırası ayarlanabilir ({@value #MANAGER_ATTRS_KEY}); sicil bağı {@link ManagerLookup}.</li>
 *   <li>Bir astın girişiyle bir kez oluşturulan müdür kaydı bir daha hiç tazelenmiyordu (kendisi
 *       girmedikçe): artık {@value #MANAGER_REFRESH_KEY} saatten eskiyse astın girişinde AD'den tazelenir.</li>
 *   <li>LDAP kaynaklı üyelik/müdür değişikliği denetime yazılır ({@code USER_LDAP_SYNC}).</li>
 *   <li>Takım grubu yalnız {@code OU=ScrumGroups} RDN'i TAM eşleşince sayılır (eskiden DN'de alt dize:
 *       "OU=ScrumGroupsArchive" gibi benzer adlı OU'daki eski grup da takım sayılırdı).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LdapProvisioningService {

    /** Müdür sicilinin okunacağı AD nitelikleri, öncelik sırasıyla (CSV). */
    public static final String MANAGER_ATTRS_KEY = "site.monitor.ldap.manager-attributes";
    public static final String DEFAULT_MANAGER_ATTRS = "extensionAttribute4,manager";
    /** AD artık desteklemediğinde AD'den TÜRETİLMİŞ üyelik girişte budansın mı. */
    public static final String PRUNE_KEY = "site.monitor.ldap.prune-unsupported-teams";
    /** Mevcut müdür kaydı bu kadar saatten eskiyse astın girişinde AD'den tazelenir (0 = kapalı). */
    public static final String MANAGER_REFRESH_KEY = "site.monitor.ldap.manager-refresh-hours";

    /** Takım grubunun bulunduğu OU (RDN tam eşleşme). */
    static final String TEAM_GROUP_OU = "ScrumGroups";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private final LdapDirectoryService directory;
    private final EscalationContactRepository contactRepo;
    private final AppSettingsService appSettings;
    private final TeamMembershipSourceService teamSources;
    private final AuditService auditService;

    /** Hangi yoldan çağrıldı — budama kuralı ve denetim kaydı buna göre. */
    public enum Mode { LOGIN, MANAGER, ADMIN_RESYNC }

    /** AD'den türetilmiş tek takım üyeliği (henüz DB'ye yazılmamış). {@code groupDn} yalnız grup kaynağında. */
    public record DerivedTeam(String name, String source, String detail, String groupDn) {}

    /** AD grubunun takım SAYILMAMA sebebi (tanılama ekranı için). */
    public record IgnoredGroup(String dn, String reason) {}

    /** Bir provizyon/yeniden eşitleme turunun önce/sonra özeti (denetim + önizleme). */
    public record SyncResult(AppUser user, boolean created, List<Long> teamsBefore, List<Long> teamsAfter,
                             String managerSicilBefore, Long managerIdBefore) {}

    /** Provision/refresh the authenticated user from AD; resolves team + manager. */
    @Transactional
    public AppUser provisionFromAd(String username, String dn, Map<String, Object> attrs) {
        return upsert(username, attrs, true, Mode.LOGIN, null).user();
    }

    /**
     * Yöneticinin "AD'den yeniden eşitle" eylemi: giriş gibi uygular, ek olarak takım kilidi YOKSA
     * üyelik kümesini AD'nin türettiğiyle BİREBİR eşitler (AD hiç takım vermiyorsa kaynağı ne olursa
     * olsun kilitsiz üyelikler kalkar — yönetici önizlemeyi görüp onaylamıştır). Kilitli (elle
     * düzenlenmiş) üyeliklere dokunulmaz. Denetimi çağıran (controller) oturumla yazar.
     */
    @Transactional
    public SyncResult resyncFromAd(String username, Map<String, Object> attrs) {
        return upsert(username, attrs, true, Mode.ADMIN_RESYNC, null);
    }

    /**
     * Core mapping. {@code resolveManager=false} when provisioning a manager record
     * recursively (avoids walking the management chain indefinitely).
     */
    private SyncResult upsert(String username, Map<String, Object> attrs, boolean resolveManager, Mode mode, String via) {
        String uname = com.sitemonitor.service.UserService.normalizeUsername(username);  // hep BÜYÜK harf
        String now = now();
        AppUser u = userRepo.findByUsername(uname).orElseGet(AppUser::new);   // case-insensitive → eski satırı bulur
        boolean isNew = (u.getId() == null);
        List<Long> teamsBefore = isNew ? List.of() : ownTeams(u);
        String mgrSicilBefore = u.getManagerSicil();
        Long mgrIdBefore = u.getManagerId();
        if (isNew) {
            u.setUsername(uname);
            u.setPasswordHash(null);     // AD users keep no app password
            u.setAuthSource("LDAP");
            u.setActive(true);
            u.setCreatedAt(now);
        }

        // ── Profile fields ──
        u.setEmail(str(attrs, "mail"));
        u.setEmployeeId(str(attrs, "cn"));                 // Sicil No
        u.setFirstName(str(attrs, "givenName"));
        u.setLastName(str(attrs, "sn"));
        u.setDisplayName(orElse(str(attrs, "displayName"), uname));
        u.setTitle(str(attrs, "title"));
        u.setPhone(str(attrs, "mobile"));
        u.setDepartment(str(attrs, "department"));
        u.setCompanyLevel(str(attrs, "description"));
        u.setPhotoBase64(stripBase64Prefix(str(attrs, "thumbnailPhoto")));

        // ── Müdürlük (extensionAttribute5 = "ID;Name") ──
        applyMudurluk(u, str(attrs, "extensionAttribute5"));

        boolean isPo = containsCi(str(attrs, "company"), "PRODUCT OWNER");
        applyOrgRole(u, isPo);                             // PO > D6->MANAGER > D7->BOLUM_BASKANI > TECH (kilitliyse dokunmaz)

        u.setUpdatedAt(now);
        u = userRepo.save(u);                              // ensure id before role/scope checks

        // ── Role: PO veya müdür → TEAM_ADMIN; else USER ──
        // A user is a müdür if provisioned via the recursive manager path (resolveManager=false)
        // OR if someone in the DB reports to them.
        // 2026-08 kararı: LDAP artık HİÇ KİMSEYE sistem-geneli ADMIN vermez. Ürün sahibi de müdür de
        // kendi takım(lar)ının yöneticisidir → TEAM_ADMIN (takım kapsamlı). ADMIN yalnız yerel
        // bootstrap hesabı ve admin panelinden elle verilerek kalır. Elle verilmiş ADMIN/AUDIT
        // applyRole tarafından korunur — bu değişiklik kimsenin mevcut yetkisini geri almaz.
        boolean isManager = !resolveManager || userRepo.existsByManagerId(u.getId());
        applyRole(u, (isPo || isManager) ? "TEAM_ADMIN" : "USER");

        // ── Takım(lar): YALNIZ bu kişinin KENDİ nitelikleri — memberOf (OU=ScrumGroups) → boşsa company ──
        // Özyinelemeli müdür yolunda da attrs müdürün KENDİ AD kaydıdır; astın takımı müdüre geçmez.
        applyTeams(u, deriveTeams(attrs), isPo, mode);

        // ── Manager (extensionAttribute4 / manager → CN=sicil) ──
        // 2026-09-10: müdür KAYDI özyinelemeli yoldan (resolveManager=false) gelince de kendi müdür
        // sicili yazılır ve DB'de zaten varsa bağlanır — yalnız AD'ye gidip bir üst kademeyi
        // PROVİZYON etmez (sonsuz zincir yok).
        resolveManagerLink(u, attrs, resolveManager);
        if (resolveManager) {
            // Takım↔müdür ilişkisi kurulduysa, müdürü otomatik MANAGER eskalasyon
            // kontağı yap (min seviye HIGH). Varsayılan KAPALI — yalnız
            // site.monitor.escalation.auto-add-managers açıksa (ensure içinde canlı okunur).
            ensureManagerEscalationContact(u.getTeamId(), u.getManagerId());
        }

        u.setUpdatedAt(now);
        AppUser saved = userRepo.save(u);
        SyncResult result = new SyncResult(saved, isNew, teamsBefore, ownTeams(saved), mgrSicilBefore, mgrIdBefore);
        if (mode != Mode.ADMIN_RESYNC) auditLdapChange(result, mode, via);
        return result;
    }

    /**
     * AD'den türetilen rolü uygular. Rol admin tarafından KİLİTLENMİŞSE (role_locked) hiç dokunma —
     * manuel atanan rol (örn. USER→TEAM_ADMIN) her girişte ezilmez. Kilitsiz kullanıcılarda eski
     * davranış korunur: elle yükseltilmiş ADMIN/AUDIT düşürülmez.
     *
     * <p>{@code desired} artık yalnız TEAM_ADMIN veya USER olabilir — LDAP hiç kimseye ADMIN vermez.
     */
    private void applyRole(AppUser u, String desired) {
        if (Boolean.TRUE.equals(u.getRoleLocked())) return;   // admin manuel kilitledi → LDAP dokunmaz
        String cur = u.getSystemRole();
        if (!"ADMIN".equals(cur) && !"AUDIT".equals(cur)) u.setSystemRole(desired);
    }

    /**
     * AD'den organizasyonel rolü türetir: PO > seviye D6 → MANAGER > seviye D7 → BOLUM_BASKANI > TECH.
     * Admin manuel değiştirmişse (orgRoleLocked) HİÇ dokunma — manuel org rol her girişte ezilmez.
     * companyLevel = AD `description` (ör. "D6"/"D7"); word-boundary + case-insensitive eşleşme.
     */
    private void applyOrgRole(AppUser u, boolean isPo) {
        if (Boolean.TRUE.equals(u.getOrgRoleLocked())) return;   // admin manuel kilitledi → LDAP dokunmaz
        String desired = isPo ? "PO"
                : levelIs(u.getCompanyLevel(), "D6") ? "MANAGER"
                : levelIs(u.getCompanyLevel(), "D7") ? "BOLUM_BASKANI"
                : "TECH";
        u.setOrgRole(desired);
    }

    /** companyLevel içinde {@code code} (ör. "D6") tam kelime olarak (case-insensitive) geçiyor mu. */
    private static boolean levelIs(String companyLevel, String code) {
        return companyLevel != null && java.util.regex.Pattern
                .compile("\\b" + code + "\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(companyLevel).find();
    }

    /**
     * AD niteliklerinden takım üyeliklerini TÜRETİR (saf — DB'ye dokunmaz; tanılama ekranı da kullanır).
     * Bir kullanıcı birden çok ScrumGroup'ta olabilir; takım, CN'i "Onaycı" ile bitMEYEN gruplardır
     * (onaycı grupları takım değil). TÜM uygun gruplar üyelik olur (sıra korunur). Hiç grup yoksa
     * {@code company} yedeği. Takım adı eşleşmesi BİREBİR (harf katlama yok → "İ/I" ile yanlış takıma
     * düşme olmaz).
     */
    public static List<DerivedTeam> deriveTeams(Map<String, Object> attrs) {
        List<DerivedTeam> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String dn : memberOfList(attrs)) {
            if (!isTeamGroupDn(dn)) continue;
            String cn = cnOf(dn);
            if (cn == null || cn.isBlank() || isApproverCn(cn)) continue;
            if (seen.add(cn)) out.add(new DerivedTeam(cn, UserTeamSource.LDAP_GROUP, cn, dn));
        }
        if (out.isEmpty()) {
            String company = str(attrs, "company");
            for (String name : companyTeamNames(company)) {
                String detail = "company=" + company;
                if (detail.length() > 300) detail = detail.substring(0, 300);   // iz sütunu 300 — karşılaştırma tutarlı kalsın
                if (seen.add(name)) out.add(new DerivedTeam(name, UserTeamSource.LDAP_COMPANY, detail, null));
            }
        }
        return out;
    }

    /** Takım sayılmayan gruplar ve sebepleri — "neden bu takımda değil / neden şu grup sayılmadı" tanılaması. */
    public static List<IgnoredGroup> ignoredGroups(Map<String, Object> attrs) {
        List<IgnoredGroup> out = new ArrayList<>();
        for (String dn : memberOfList(attrs)) {
            if (!isTeamGroupDn(dn)) { out.add(new IgnoredGroup(dn, "NOT_TEAM_OU")); continue; }
            String cn = cnOf(dn);
            if (cn == null || cn.isBlank()) out.add(new IgnoredGroup(dn, "NO_CN"));
            else if (isApproverCn(cn)) out.add(new IgnoredGroup(dn, "APPROVER_GROUP"));
        }
        return out;
    }

    /**
     * Türetilen takımları uygular. Kurallar:
     * <ul>
     *   <li>Takım KİLİTLİ (elle düzenlenmiş) → hiç dokunma (2026-09-18 sözleşmesi).</li>
     *   <li>AD en az bir takım verdi → üyelik = o küme (eski davranış; birincil = ilk çözülen).</li>
     *   <li>AD hiç takım vermedi: {@link Mode#ADMIN_RESYNC} → üyelik boşalır (yönetici onayladı);
     *       giriş/müdür yolunda {@value #PRUNE_KEY} açıksa YALNIZ AD'den türetilmiş (iz = LDAP_*) üyelikler
     *       düşer, elle/kaynaksız olanlar kalır; ayar kapalıysa eski davranış (hepsi kalır).</li>
     * </ul>
     */
    private void applyTeams(AppUser u, List<DerivedTeam> derived, boolean isPo, Mode mode) {
        // Admin takımları MANUEL düzenlediyse (team_locked) AD üyeliği hiç uygulanmaz — role_locked ile
        // aynı sözleşme (2026-09-18). Kilit kalkınca bir sonraki girişte AD yeniden yazar.
        if (Boolean.TRUE.equals(u.getTeamLocked())) return;

        LinkedHashMap<Long, DerivedTeam> resolved = new LinkedHashMap<>();
        for (DerivedTeam d : derived) {
            Team team = findOrCreateTeam(d.name(), d.groupDn());
            resolved.putIfAbsent(team.getId(), d);
            if (isPo) assignLeaderIfVacant(team, u);
        }

        List<Long> current = ownTeams(u);
        LinkedHashSet<Long> next;
        if (!resolved.isEmpty()) {
            next = new LinkedHashSet<>(resolved.keySet());
        } else if (mode == Mode.ADMIN_RESYNC) {
            next = new LinkedHashSet<>();
        } else if (appSettings.getBoolean(PRUNE_KEY, true)) {
            Map<Long, UserTeamSource> src = teamSources.sourcesOf(u.getId());
            next = new LinkedHashSet<>();
            for (Long t : current) {
                UserTeamSource s = src.get(t);
                if (s == null || !UserTeamSource.LDAP_SOURCES.contains(s.getSource())) next.add(t);
            }
        } else {
            next = new LinkedHashSet<>(current);
        }

        boolean changed = !next.equals(new LinkedHashSet<>(current));
        // Küme DEĞİŞMEDİYSE koleksiyon yeniden yazılmaz: eskiden her girişte app_user_teams silinip yeniden
        // ekleniyordu (tek pod, eşzamanlı girişlerde gereksiz yazım + aynı satıra yarış).
        if (changed) u.setTeamIds(next);
        if (!resolved.isEmpty()) {
            u.setTeamId(next.iterator().next());           // birincil = ilk çözülen
        } else if (changed && (u.getTeamId() == null || !next.contains(u.getTeamId()))) {
            u.setTeamId(next.isEmpty() ? null : next.iterator().next());
        }

        // Kaynak izi: AD'den gelenler LDAP_*; artık üye olunmayanların izi silinir. Aynı iz zaten
        // kayıtlıysa yeniden yazılmaz (her girişte gereksiz UPDATE olmasın).
        Map<Long, UserTeamSource> known = resolved.isEmpty() ? Map.of() : teamSources.sourcesOf(u.getId());
        for (Map.Entry<Long, DerivedTeam> e : resolved.entrySet()) {
            UserTeamSource k = known.get(e.getKey());
            if (k != null && Objects.equals(k.getSource(), e.getValue().source())
                    && Objects.equals(k.getDetail(), e.getValue().detail())) continue;
            teamSources.record(u.getId(), e.getKey(), e.getValue().source(), e.getValue().detail(), "LDAP");
        }
        List<Long> removed = current.stream().filter(t -> !next.contains(t)).toList();
        teamSources.forget(u.getId(), removed);
    }

    /** Takımı adıyla bul, yoksa oluştur. {@code dnForMail} verilirse grubun AD mail'i takım e-postası olur. */
    private Team findOrCreateTeam(String teamName, String dnForMail) {
        Team team = teamRepo.findByName(teamName).orElseGet(() -> {
            Team t = new Team();
            t.setName(teamName);
            t.setEmail(dnForMail != null ? directory.groupMail(dnForMail).orElse(null) : null);
            t.setActive(true);
            t.setCreatedAt(now());
            t.setUpdatedAt(now());
            return teamRepo.save(t);
        });
        // E-postası boşsa ve bir grup DN'imiz varsa sonradan doldur.
        if (dnForMail != null && (team.getEmail() == null || team.getEmail().isBlank())) {
            directory.groupMail(dnForMail).ifPresent(m -> { team.setEmail(m); teamRepo.save(team); });
        }
        return team;
    }

    private void assignLeaderIfVacant(Team team, AppUser po) {
        if (team.getLeaderId() == null) {
            team.setLeaderId(po.getId());
            team.setUpdatedAt(now());
            teamRepo.save(team);
        }
    }

    /**
     * AD {@code company} attribute'undan takım adlarını çıkarır (grup üyeliği boşken fallback).
     * Format: {@code "<ROL>-<TAKIM1>[,<TAKIM2>...]"}. Takım adları iç tire içerir
     * (ör. "SY-MevduatMuhasebeSigorta"), bu yüzden yalnız İLK tireden böl (rol önekini at), kalanı
     * virgülle ayır. Örnekler:
     * <pre>
     *   "PRODUCT OWNER-SY-MevduatMuhasebeSigorta,SY-Takım A Mobil Servis"
     *        → [SY-MevduatMuhasebeSigorta, SY-Takım A Mobil Servis]
     *   "YAZILIM UZMANI-SY-MevduatMuhasebeSigorta" → [SY-MevduatMuhasebeSigorta]
     * </pre>
     */
    static List<String> companyTeamNames(String company) {
        if (company == null) return List.of();
        int dash = company.indexOf('-');
        if (dash < 0 || dash + 1 >= company.length()) return List.of();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String part : company.substring(dash + 1).split(",")) {
            String name = part.trim();
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
        return names;
    }

    /** Geriye uyum: varsayılan nitelik sırasıyla ({@value #DEFAULT_MANAGER_ATTRS}). */
    static String managerSicilOf(Map<String, Object> attrs) {
        return managerSicilOf(attrs, List.of("extensionAttribute4", "manager"));
    }

    /**
     * Müdür sicilini AD niteliklerinden türetir; nitelik sırası ayardan ({@value #MANAGER_ATTRS_KEY}).
     * Her aday için: DN ise CN'i, düz sicil (yalnız rakam/harf, virgülsüz) ise kendisi. İLK ÇÖZÜLEN kazanır —
     * eskiden {@code cnOf(orElse(ea4, manager))} idi: ea4 DOLU ama DN olmayan bir değer taşıyorsa
     * ({@code CN=} yok) cnOf null dönüyor ve dolu {@code manager} DN'ine hiç düşülmüyordu → sicil boş.
     */
    static String managerSicilOf(Map<String, Object> attrs, List<String> order) {
        for (String key : order) {
            String s = sicilFromAttr(attrs, key);
            if (s != null) return s;
        }
        return null;
    }

    /** Tanılama: her nitelikten çözülen sicil (sıra korunur, çözülemeyen null). İki nitelik farklı kişiyi
     *  gösteriyorsa "hangisi doğru" kararı bu tablodan verilir. */
    public static Map<String, String> managerCandidates(Map<String, Object> attrs, List<String> order) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : order) out.put(key, sicilFromAttr(attrs, key));
        return out;
    }

    private static String sicilFromAttr(Map<String, Object> attrs, String key) {
        String raw = str(attrs, key);
        if (raw == null) return null;
        String cn = cnOf(raw);
        if (cn != null && !cn.isBlank()) return cn;
        String plain = raw.trim();
        if (!plain.contains(",") && !plain.contains("=") && plain.matches("[A-Za-z0-9_.-]{1,50}")) return plain;
        return null;
    }

    /** Ayardaki müdür nitelik sırası; geçersiz ad (filtre enjeksiyonu / yazım hatası) atlanır, boşsa varsayılan. */
    public List<String> managerAttributeOrder() {
        List<String> raw = appSettings.getCsv(MANAGER_ATTRS_KEY, DEFAULT_MANAGER_ATTRS);
        List<String> out = new ArrayList<>();
        for (String a : raw) if (a != null && a.matches("[A-Za-z][A-Za-z0-9-]{0,63}") && !out.contains(a)) out.add(a);
        return out.isEmpty() ? List.of("extensionAttribute4", "manager") : out;
    }

    /**
     * Müdür bağını kurar. {@code provisionMissing} (giriş yolu): müdür DB'de yoksa AD'den (özyinelemesiz)
     * provizyon eder, varsa ve bayatsa tazeler. {@code false} (özyinelemeli müdür yolu): yalnız sicili yazar
     * ve DB'de zaten varsa bağlar.
     *
     * <p>{@code manager_id} HER ZAMAN {@code manager_sicil} ile tutarlıdır: sicil çözülemezse {@code null}.
     * Eskiden çözülemeyen yeni sicilde eski {@code manager_id} yerinde kalıyordu — "Müdür (sicil)" yeni
     * müdürü, Takım Müdürü sütunu ve müdür kapsamı ESKİ müdürü gösteriyordu. AD'de müdür niteliği yoksa
     * ikisi de temizlenir (diğer profil alanları gibi AD yetkilidir).
     */
    private void resolveManagerLink(AppUser u, Map<String, Object> attrs, boolean provisionMissing) {
        String sicil = managerSicilOf(attrs, managerAttributeOrder());
        if (sicil == null || sicil.isBlank()) {
            u.setManagerSicil(null);
            u.setManagerId(null);
            return;
        }
        u.setManagerSicil(sicil);
        Optional<AppUser> mgr = ManagerLookup.resolve(userRepo, sicil, u.getId());
        if (provisionMissing) {
            if (mgr.isEmpty()) {
                mgr = provisionManager(sicil, u.getUsername());
            } else if (isStale(mgr.get())) {
                refreshManager(mgr.get(), sicil, u.getUsername());
            }
        }
        u.setManagerId(mgr.map(AppUser::getId).filter(id -> !id.equals(u.getId())).orElse(null));
    }

    /** Müdür DB'de yok → AD'de {@code cn=<sicil>} TEK kayıt varsa minimal provizyon (özyinelemesiz). */
    private Optional<AppUser> provisionManager(String sicil, String viaUsername) {
        Optional<Map<String, Object>> mgrAttrs = directory.findOne("cn", sicil);
        if (mgrAttrs.isEmpty()) return Optional.empty();
        String mgrUsername = str(mgrAttrs.get(), "sAMAccountName");
        if (mgrUsername == null || mgrUsername.isBlank()) return Optional.empty();
        return Optional.of(upsert(mgrUsername, mgrAttrs.get(), false, Mode.MANAGER, viaUsername).user());
    }

    /** Bayat müdür kaydını AD'den tazeler — yalnız AD'deki {@code cn=<sicil>} kaydı AYNI hesapsa. */
    private void refreshManager(AppUser existing, String sicil, String viaUsername) {
        try {
            Optional<Map<String, Object>> a = directory.findOne("cn", sicil);
            if (a.isEmpty()) return;
            String sam = str(a.get(), "sAMAccountName");
            if (sam == null || !UserService.normalizeUsername(sam).equals(UserService.normalizeUsername(existing.getUsername()))) {
                log.warn("Müdür tazeleme atlandı: sicil {} AD'de başka hesaba ({}) çıkıyor, DB'deki {}", sicil, sam, existing.getUsername());
                return;
            }
            upsert(sam, a.get(), false, Mode.MANAGER, viaUsername);
        } catch (RuntimeException e) {
            log.warn("Müdür kaydı tazelenemedi (sicil={}): {}", sicil, e.getMessage());
        }
    }

    /** LDAP kaynaklı mevcut müdür kaydı {@value #MANAGER_REFRESH_KEY} saatten eski mi (0 = hiç tazeleme). */
    private boolean isStale(AppUser m) {
        if (m == null || !"LDAP".equalsIgnoreCase(m.getAuthSource())) return false;
        int hours = appSettings.getInt(MANAGER_REFRESH_KEY, 24);
        if (hours <= 0) return false;
        String threshold = ISO.format(Instant.now().minus(Duration.ofHours(hours)));
        return m.getUpdatedAt() == null || m.getUpdatedAt().compareTo(threshold) < 0;
    }

    /** LDAP kaynaklı üyelik/müdür DEĞİŞİKLİĞİNİ (ya da yeni kaydı) denetime yazar; değişiklik yoksa yazmaz. */
    private void auditLdapChange(SyncResult r, Mode mode, String via) {
        try {
            AppUser u = r.user();
            List<Long> added = r.teamsAfter().stream().filter(t -> !r.teamsBefore().contains(t)).toList();
            List<Long> removed = r.teamsBefore().stream().filter(t -> !r.teamsAfter().contains(t)).toList();
            boolean mgrChanged = !Objects.equals(r.managerSicilBefore(), u.getManagerSicil())
                    || !Objects.equals(r.managerIdBefore(), u.getManagerId());
            if (!r.created() && added.isEmpty() && removed.isEmpty() && !mgrChanged) return;
            String detail = AuditDetail.of(
                    "username", u.getUsername(),
                    "via", mode == Mode.MANAGER ? "MANAGER_OF:" + via : mode.name(),
                    "created", r.created(),
                    "team_locked", Boolean.TRUE.equals(u.getTeamLocked()),
                    "teams_before", String.valueOf(r.teamsBefore()),
                    "teams_after", String.valueOf(r.teamsAfter()),
                    "added", String.valueOf(added),
                    "removed", String.valueOf(removed),
                    "manager_sicil_before", r.managerSicilBefore(),
                    "manager_sicil_after", u.getManagerSicil(),
                    "manager_id_before", r.managerIdBefore(),
                    "manager_id_after", u.getManagerId());
            auditService.recordSystemEvent("USER_LDAP_SYNC", "USER", String.valueOf(u.getId()), detail);
        } catch (RuntimeException e) {
            log.warn("USER_LDAP_SYNC denetimi yazılamadı: {}", e.getMessage());
        }
    }

    /**
     * Takımın müdürünü otomatik olarak MANAGER eskalasyon kontağı yapar (min seviye HIGH).
     * VARSAYILAN KAPALI (site.monitor.escalation.auto-add-managers=false): D7+ müdürler
     * eskalasyona OTOMATİK eklenmez; Admin/Takım PO'su isterse manuel ekler. Admin ayarı
     * açarsa eski davranış döner. Aynı takım+MANAGER+e-posta kontağı zaten varsa
     * (aktif/pasif) tekrar eklenmez (idempotent). Mevcut kayıtlar ayar kapansa da silinmez.
     */
    private void ensureManagerEscalationContact(Long teamId, Long managerId) {
        if (!appSettings.getBoolean("site.monitor.escalation.auto-add-managers", false)) return;
        if (teamId == null || managerId == null) return;
        AppUser mgr = userRepo.findById(managerId).orElse(null);
        if (mgr == null || mgr.getEmail() == null || mgr.getEmail().isBlank()) return;
        String email = mgr.getEmail().trim();
        boolean exists = contactRepo.findByTeamIdOrderByRoleAsc(teamId).stream()
                .anyMatch(c -> "MANAGER".equals(c.getRole()) && email.equalsIgnoreCase(c.getEmail()));
        if (exists) return;
        EscalationContact c = new EscalationContact();
        c.setTeamId(teamId);
        c.setRole("MANAGER");
        c.setUserId(mgr.getId());
        c.setName((mgr.getDisplayName() != null && !mgr.getDisplayName().isBlank())
                ? mgr.getDisplayName() : mgr.getUsername());
        c.setEmail(email);
        c.setMinAlertLevel("HIGH");
        c.setActive(true);
        c.setCreatedAt(now());
        contactRepo.save(c);
        log.info("Takım müdürü otomatik MANAGER eskalasyon kontağı eklendi: team={} manager='{}' <{}>",
                teamId, c.getName(), email);
    }

    private void applyMudurluk(AppUser u, String ext5) {
        if (ext5 == null || !ext5.contains(";")) return;
        String[] parts = ext5.split(";", 2);
        try {
            u.setMudurlukId(Long.parseLong(parts[0].trim()));
        } catch (NumberFormatException ignored) {
            u.setMudurlukId(null);
        }
        if (parts.length > 1) u.setMudurlukName(parts[1].trim());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Kullanıcının üye olduğu tüm takımlar (ek üyelikler + birincil), sıralı + tekil. */
    static List<Long> ownTeams(AppUser u) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (u.getTeamIds() != null) for (Long t : u.getTeamIds()) if (t != null) ids.add(t);
        if (u.getTeamId() != null) ids.add(u.getTeamId());
        return new ArrayList<>(ids);
    }

    /**
     * Grup DN'i takım OU'sunda mı — RDN bileşeni {@code OU=ScrumGroups} ile TAM eşleşmeli (harf duyarsız,
     * '=' çevresindeki boşluk tolere). Eskiden DN'de alt dize aranıyordu: "OU=ScrumGroupsArchive" /
     * "OU=OldScrumGroups" altındaki eski gruplar da takım sayılırdı.
     */
    static boolean isTeamGroupDn(String dn) {
        if (dn == null) return false;
        for (String rdn : splitDn(dn)) {
            int eq = rdn.indexOf('=');
            if (eq < 0) continue;
            if (rdn.substring(0, eq).trim().equalsIgnoreCase("OU")
                    && rdn.substring(eq + 1).trim().equalsIgnoreCase(TEAM_GROUP_OU)) return true;
        }
        return false;
    }

    /** DN'i kaçışsız virgüllerden böler ({@code \,} ayırıcı sayılmaz). */
    private static List<String> splitDn(String dn) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean esc = false;
        for (int i = 0; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (esc) { cur.append(c); esc = false; continue; }
            if (c == '\\') { cur.append(c); esc = true; continue; }
            if (c == ',') { parts.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(c);
        }
        parts.add(cur.toString());
        return parts;
    }

    /** Extracts the CN value from a DN, e.g. "CN=99999,OU=..." → "99999". */
    static String cnOf(String dn) {
        if (dn == null) return null;
        int i = indexOfIgnoreCase(dn, "CN=");
        if (i < 0) return null;
        int start = i + 3;
        int end = dn.indexOf(',', start);
        String cn = (end < 0) ? dn.substring(start) : dn.substring(start, end);
        return cn.trim();
    }

    /** True when a group CN denotes an approver ("Onaycı") group rather than a team,
     *  e.g. "Takim B_Onayci". Such groups are skipped for team naming. */
    static boolean isApproverCn(String cn) {
        if (cn == null) return false;
        String c = cn.trim().toLowerCase(java.util.Locale.ROOT);
        return c.endsWith("onayci") || c.endsWith("onaycı");
    }

    @SuppressWarnings("unchecked")
    static List<String> memberOfList(Map<String, Object> attrs) {
        Object v = caseInsensitiveGet(attrs, "memberOf");
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (v instanceof String one && !one.isBlank()) return List.of(one);
        return List.of();
    }

    static String stripBase64Prefix(String v) {
        if (v == null) return null;
        return v.startsWith("base64:") ? v.substring("base64:".length()) : v;
    }

    /** Case-insensitive single-value attribute read (first element if multi-valued). */
    static String str(Map<String, Object> attrs, String key) {
        Object v = caseInsensitiveGet(attrs, key);
        if (v == null) return null;
        String val = (v instanceof List<?> list)
                ? (list.isEmpty() ? null : String.valueOf(list.get(0)))
                : String.valueOf(v);
        if (val == null || val.isBlank() || "null".equals(val)) return null;
        return val;
    }

    private static Object caseInsensitiveGet(Map<String, Object> attrs, String key) {
        if (attrs == null || key == null) return null;
        Object direct = attrs.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, Object> e : attrs.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static boolean containsCi(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Harf duyarsız alt dize konumu — ÖZGÜN dizideki indeksi döner. Eskiden {@code toLowerCase()}'in
     * indeksi kullanılıyordu: varsayılan yerelde "İ" iki karaktere açıldığında (i + birleşik nokta)
     * indeks kayıyor ve CN yanlış kesiliyordu.
     */
    private static int indexOfIgnoreCase(String s, String sub) {
        int n = sub.length();
        for (int i = 0; i + n <= s.length(); i++) {
            if (s.regionMatches(true, i, sub, 0, n)) return i;
        }
        return -1;
    }

    private static String orElse(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String now() {
        return ISO.format(Instant.now());
    }
}
