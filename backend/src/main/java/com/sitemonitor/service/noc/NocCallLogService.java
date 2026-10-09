package com.sitemonitor.service.noc;

import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.NocCallLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NocCallLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.MonitorTypeCatalog;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 7/24 ARAMA KAYDI (2026-09-27; sözleşme {@code .migration/noc/CONTRACT.md} "Arama kaydı") — NOC bir uyarı için
 * sahibi takımdan nöbetçiyi aradığında uyarının üzerinden girdiği kayıtlar.
 *
 * <h2>Yetki</h2>
 * <ul>
 *   <li><b>Yazma</b> — matris izni {@code noc_calls.write/edit} (TAM ad). Global yönetici HER ZAMAN geçer.
 *       <b>Kapsamlı müdür</b> (rol ADMIN ama {@code viewTeamIds} dolu) matrisin ADMIN satırından bu izni ALMAZ:
 *       ADMIN satırı kısılamaz ve kapsamlı müdürü 7/24 operatörü yapsaydı bütün takımların uyarılarını görürdü
 *       (projenin yaşanmış "müdür global sanıldı" tuzağı). Kendi takımlarının kayıtlarını okur.</li>
 *   <li><b>Görünürlük</b> — izin sahibi (7/24 operatörü) TÜM takımların uyarılarını görür: yalnız uyarı listesi,
 *       uyarı detayı (+ teslimat günlüğü) ve bu uçlar için ({@link #seesAllAlerts}); başka hiçbir yüzeyin kapsamı
 *       genişlemez. Global görücü (admin/AUDIT) zaten görür.</li>
 *   <li><b>Okuma</b> — uyarıyı görebilen herkes: izin sahibi, global görücü, uyarının takımını (damgalı takım ya da
 *       envanter SY/UG) görüş kapsamında taşıyan ve {@code alerts.read} izni olan.</li>
 *   <li><b>Silme</b> — yalnız kaydı giren, {@link #DELETE_WINDOW} içinde (izni hâlâ varken); ya da global yönetici.</li>
 * </ul>
 * Telefon HİÇBİR yanıtta yok — seçicide yalnız {@code has_phone}; serbest ad alanına numara yazılamaz.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NocCallLogService {

    /** İzin matrisi anahtarı — TAM ad ({@code release_history.read} deseni). */
    public static final String PERMISSION = "noc_calls.write";
    public static final String PERMISSION_ACTION = "edit";

    public static final List<String> OUTCOMES =
            List.of("REACHED", "NO_ANSWER", "VOICEMAIL", "BUSY", "WRONG_NUMBER", "ESCALATED");
    public static final List<String> CHANNELS = List.of("PHONE", "SMS", "TEAMS", "EMAIL");
    public static final String DEFAULT_CHANNEL = "PHONE";

    public static final int NOTE_MAX = 1000;
    public static final int NAME_MAX = 200;
    /** Serbest ad alanında bu kadar ya da daha çok rakam = telefon numarası (yazılmaz). */
    static final int PHONE_DIGITS = 7;

    /** Kaydı girenin kendi kaydını silebildiği süre. */
    public static final Duration DELETE_WINDOW = Duration.ofMinutes(15);
    /**
     * Aramanın uyarı AÇILIŞINDAN önce olabileceği pay: izleme alarmı doğrulama denemelerinden sonra açar (sorun ilk
     * görüldükten birkaç dakika sonra) ve saatler birebir aynı değildir. Daha eskisi başka bir olayın araması demektir.
     */
    public static final Duration BEFORE_OPEN_TOLERANCE = Duration.ofMinutes(10);
    /** İstemci saatinin sunucudan ileride olabileceği pay ("Şimdi" düğmesi); aşan an GELECEK sayılır. */
    public static final Duration FUTURE_SKEW = Duration.ofMinutes(2);

    static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final NocCallLogRepository repo;
    private final AlertEventRepository alertRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final TeamRepository teamRepo;
    private final NocCallListService callLists;
    private final PermissionService permissionService;
    private final ActivityLogService activityLog;

    /** "Şimdi" — testte sabitlenir (çıplak Instant.now() yerine). */
    Supplier<Instant> clock = Instant::now;

    // ── Yetki ────────────────────────────────────────────────────────────────

    /** 7/24 operatörü mü (arama kaydı girebilir mi)? Global yönetici evet, kapsamlı müdür hayır, diğerleri matris. */
    public boolean canWrite(HttpSession s) {
        if (s == null) return false;
        if (SessionScope.isGlobalAdmin(s)) return true;
        // 7/24 izleme ekibi takımının üyesi (2026-10-04): rolü ne olursa olsun operatördür — kapsamlı müdür de olsa.
        // Takımı Ayarlar'da GLOBAL yönetici işaretler; "müdür global sanıldı" tuzağı burada yok, çünkü yalnız OKUMA
        // kapsamı + arama kaydı + not genişler (yazma kapsamı aynen kalır).
        if (SessionScope.isNocOperator(s)) return true;
        if (SessionScope.isScopedAdmin(s)) return false;
        return permissionService.allows(s, PERMISSION, PERMISSION_ACTION);
    }

    /** Uyarı listesi/detayı için TÜM takımları görür mü — global görücü ya da 7/24 operatörü. */
    public boolean seesAllAlerts(HttpSession s) {
        return SessionScope.isGlobalViewer(s) || canWrite(s);
    }

    /** Bu uyarının arama kayıtlarını okuyabilir mi. */
    public boolean canRead(HttpSession s, AlertEvent ev) {
        if (s == null || ev == null) return false;
        if (canWrite(s)) return true;
        if (!permissionService.allows(s, "alerts.read", "view")) return false;
        if (SessionScope.isGlobalViewer(s)) return true;
        List<Long> view = SessionScope.viewTeamIds(s);
        if (view == null) return false;
        for (Long t : alertTeamIds(ev)) if (view.contains(t)) return true;
        return false;
    }

    // ── Uyarı + takım ────────────────────────────────────────────────────────

    public AlertEvent loadAlert(Long alertId) {
        return alertRepo.findById(alertId).orElseThrow(() -> new NoSuchElementException(
                Msg.t("Uyarı bulunamadı: ", "Alert not found: ") + alertId));
    }

    /** Uyarının ait olabileceği takımlar — OKUMA kuralı (AdminController.alertReadTeamIds / liste sorgusuyla aynı). */
    Set<Long> alertTeamIds(AlertEvent ev) {
        Set<Long> ids = new HashSet<>();
        if (ev.getTeamId() != null) ids.add(ev.getTeamId());
        if (ev.getDomain() != null) {
            inventoryRepo.findByDomain(ev.getDomain()).ifPresent(inv -> {
                if (inv.getTeamId() != null) ids.add(inv.getTeamId());
                if (inv.getUgTeamId() != null) ids.add(inv.getUgTeamId());
            });
        }
        return ids;
    }

    /**
     * Uyarının SAHİBİ takım: damgalı teamId; yoksa envanter SY, o da yoksa UG (IncidentsController ile aynı sıra). Bağımsız
     * izleme uyarısında envanter OKUNMAZ (2026-10-09, {@link com.sitemonitor.service.AlertOwnership}; bildirim yönlendirmesiyle
     * aynı yüklem): damgasız bağımsız uyarının sahibi yoktur — arama kartı host'un envanterindeki başka takımı göstermez.
     */
    public Long owningTeam(AlertEvent ev) {
        if (ev.getTeamId() != null) return ev.getTeamId();
        if (ev.getDomain() == null || !com.sitemonitor.service.AlertOwnership.routesLikeInventory(ev)) return null;
        return inventoryRepo.findByDomain(ev.getDomain())
                .map(inv -> inv.getTeamId() != null ? inv.getTeamId() : inv.getUgTeamId())
                .orElse(null);
    }

    // ── Okuma ────────────────────────────────────────────────────────────────

    /** Uyarının kayıtları, en yeni önce. Her satırda {@code can_delete} + (kendi kaydında) {@code delete_until}. */
    public List<Map<String, Object>> list(AlertEvent ev, HttpSession s) {
        String me = username(s);
        boolean admin = SessionScope.isGlobalAdmin(s);
        boolean write = canWrite(s);
        Instant now = clock.get();
        List<Map<String, Object>> out = new ArrayList<>();
        for (NocCallLog c : repo.findByAlertIdOrderByContactedAtDescIdDesc(ev.getId())) out.add(dto(c, me, admin, write, now));
        return out;
    }

    Map<String, Object> dto(NocCallLog c, String me, boolean admin, boolean write, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("contacted_user_id", c.getContactedUserId());
        m.put("contacted_name", c.getContactedName());
        m.put("contacted_at", c.getContactedAt());
        m.put("channel", c.getChannel());
        m.put("outcome", c.getOutcome());
        m.put("note", c.getNote());
        m.put("created_by_name", c.getCreatedByName());
        m.put("created_at", c.getCreatedAt());
        boolean own = isOwner(c, me);
        Instant until = deleteUntil(c);
        m.put("can_delete", admin || (write && own && until != null && now.isBefore(until)));
        // Süre yalnız KENDİ kaydında anlamlı (arayüz düğmeyi süre dolunca kendiliğinden kaldırır); yönetici süresiz.
        m.put("delete_until", !admin && own && until != null ? ISO.format(until) : null);
        return m;
    }

    private static boolean isOwner(NocCallLog c, String me) {
        return me != null && c.getCreatedBy() != null && me.equalsIgnoreCase(c.getCreatedBy());
    }

    private static Instant deleteUntil(NocCallLog c) {
        Instant created = parseStored(c.getCreatedAt());
        return created == null ? null : created.plus(DELETE_WINDOW);
    }

    /**
     * Uyarı listesi özeti: her satıra {@code noc_call_count} ve {@code noc_last_call}
     * ({@code {contacted_name, outcome, contacted_at}}) — sayfa başına TEK sorgu. Özet sorgusu düşerse liste yine
     * döner (alanlar boş kalır; arayüz göstergeyi çizmez).
     */
    public void decorate(List<AlertEvent> alerts) {
        if (alerts == null || alerts.isEmpty()) return;
        Set<Long> ids = new LinkedHashSet<>();
        for (AlertEvent a : alerts) if (a != null && a.getId() != null) ids.add(a.getId());
        if (ids.isEmpty()) return;
        Map<Long, Object[]> byAlert = new HashMap<>();
        try {
            for (Object[] row : repo.summarizeByAlertIds(ids)) {
                if (row == null || !(row[0] instanceof Number n)) continue;
                byAlert.putIfAbsent(n.longValue(), row);
            }
        } catch (Exception e) {
            log.debug("7/24 arama özeti alınamadı: {}", e.toString());
            return;
        }
        for (AlertEvent a : alerts) {
            if (a == null || a.getId() == null) continue;
            Object[] r = byAlert.get(a.getId());
            if (r == null) {
                a.setNocCallCount(0L);
                a.setNocLastCall(null);
                continue;
            }
            a.setNocCallCount(r[4] instanceof Number n ? n.longValue() : 1L);
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("contacted_name", r[1]);
            last.put("outcome", r[2]);
            last.put("contacted_at", r[3]);
            a.setNocLastCall(last);
        }
    }

    // ── Kişi seçicisi ────────────────────────────────────────────────────────

    /**
     * Arama seçicisi: takımın arama listesi (SIRALI, yalnız hâlâ aktif üye olanlar) → Takım Müdürü → diğer aktif
     * üyeler. Bir kişi bir kez görünür (listedeki müdür {@code is_manager=true} ile işaretlenir). Telefon YOK.
     * Takımı çözülemeyen uyarıda boş — arayüz serbest ad girişine düşer.
     */
    public List<Map<String, Object>> contacts(AlertEvent ev) {
        Long teamId = owningTeam(ev);
        if (teamId == null) return List.of();
        Team team = teamRepo.findById(teamId).orElse(null);
        if (team == null) return List.of();
        AppUser manager = callLists.resolveManager(team).orElse(null);
        Long managerId = manager != null ? manager.getId() : null;

        List<Map<String, Object>> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Map<String, Object> p : callLists.callListDto(teamId)) {
            if (!Boolean.TRUE.equals(p.get("is_member")) || !(p.get("user_id") instanceof Number n)) continue;
            Long uid = n.longValue();
            if (!seen.add(uid)) continue;
            out.add(contact(uid, str(p.get("display_name")), str(p.get("title")),
                    Boolean.TRUE.equals(p.get("has_phone")), "CALL_LIST", uid.equals(managerId)));
        }
        if (manager != null && seen.add(manager.getId())) out.add(contact(manager, "MANAGER", true));
        for (AppUser u : callLists.activeMembers(teamId)) {
            if (u.getId() != null && seen.add(u.getId())) out.add(contact(u, "MEMBER", u.getId().equals(managerId)));
        }
        return out;
    }

    private static Map<String, Object> contact(AppUser u, String source, boolean isManager) {
        return contact(u.getId(), NocCallListService.displayName(u), u.getTitle(),
                u.getPhone() != null && !u.getPhone().isBlank(), source, isManager);
    }

    private static Map<String, Object> contact(Long id, String name, String title, boolean hasPhone, String source,
                                               boolean isManager) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", id);
        m.put("display_name", name);
        m.put("title", title);
        m.put("has_phone", hasPhone);
        m.put("source", source);
        m.put("is_manager", isManager);
        return m;
    }

    // ── Yazma ────────────────────────────────────────────────────────────────

    /** Doğrulanmış istek gövdesi. */
    record Input(Long contactedUserId, String contactedName, Instant contactedAt, String channel, String outcome,
                 String note) {}

    /** Yeni kayıt — gövde {@code { contactedUserId?, contactedName?, contactedAt?, channel?, outcome, note? }}. */
    public Map<String, Object> create(AlertEvent ev, Map<String, Object> body, HttpSession s) {
        if (!canWrite(s)) throw new SecurityException(Msg.t(
                "Arama kaydı girme yetkiniz yok (noc_calls.write)", "You do not have permission to log calls (noc_calls.write)"));
        Instant now = clock.get();
        Input in = parse(body, ev.getCreatedAt(), now);
        Long teamId = owningTeam(ev);

        String name = in.contactedName();
        if (in.contactedUserId() != null) {
            Map<String, Object> picked = null;
            for (Map<String, Object> c : contacts(ev)) {
                if (Objects.equals(c.get("user_id"), in.contactedUserId())) { picked = c; break; }
            }
            if (picked == null) throw new IllegalArgumentException(Msg.t(
                    "Seçilen kişi bu uyarının takımında değil — listeden seçin ya da adını yazın",
                    "The selected person is not in this alert's team — pick from the list or type their name"));
            String dn = str(picked.get("display_name"));
            name = dn == null || dn.isBlank() ? String.valueOf(in.contactedUserId()) : dn;
        }

        NocCallLog c = new NocCallLog();
        c.setAlertId(ev.getId());
        c.setTeamId(teamId);
        c.setContactedUserId(in.contactedUserId());
        c.setContactedName(truncate(name, NAME_MAX));
        c.setContactedAt(ISO.format(in.contactedAt()));
        c.setChannel(in.channel());
        c.setOutcome(in.outcome());
        c.setNote(in.note());
        c.setCreatedBy(truncate(username(s), 100));
        c.setCreatedByName(truncate(displayName(s), NAME_MAX));
        c.setCreatedAt(ISO.format(now));
        NocCallLog saved = repo.save(c);
        activity(ev, teamId, "NOC_CALL_LOGGED", s, saved);
        return dto(saved, username(s), SessionScope.isGlobalAdmin(s), true, now);
    }

    /** Silme: kaydı giren {@link #DELETE_WINDOW} içinde (izni hâlâ varken) ya da global yönetici. Dönüş: silinen satır. */
    public NocCallLog delete(AlertEvent ev, Long callId, HttpSession s) {
        NocCallLog c = repo.findById(callId)
                .filter(x -> Objects.equals(x.getAlertId(), ev.getId()))
                .orElseThrow(() -> new NoSuchElementException(Msg.t("Arama kaydı bulunamadı: ", "Call log entry not found: ") + callId));
        if (!SessionScope.isGlobalAdmin(s)) {
            if (!canWrite(s) || !isOwner(c, username(s))) throw new SecurityException(Msg.t(
                    "Arama kaydını yalnız giren kişi silebilir", "Only the person who logged this call can delete it"));
            Instant until = deleteUntil(c);
            if (until == null || !clock.get().isBefore(until)) throw new SecurityException(Msg.t(
                    "Silme süresi doldu — kayıt girildikten sonra 15 dakika içinde silinebilir",
                    "The deletion window has closed — an entry can only be deleted within 15 minutes of logging it"));
        }
        repo.delete(c);
        activity(ev, c.getTeamId(), "NOC_CALL_DELETED", s, c);
        return c;
    }

    /**
     * Gövdeyi doğrular (saf — testte doğrudan çağrılır). Sonuç zorunlu; kişi (kullanıcı ya da ad) zorunlu; an gelecek
     * olamaz ve uyarının açılışından {@link #BEFORE_OPEN_TOLERANCE}'tan daha önce olamaz; not ≤ {@link #NOTE_MAX};
     * kontrol karakterleri ayıklanır.
     */
    static Input parse(Map<String, Object> body, String alertCreatedAt, Instant now) {
        Map<String, Object> b = body == null ? Map.of() : body;

        String outcome = upper(b.get("outcome"));
        if (outcome == null) throw bad("Sonuç zorunlu (ulaşıldı, yanıt yok…)", "Outcome is required (reached, no answer…)");
        if (!OUTCOMES.contains(outcome)) throw bad("Geçersiz sonuç: " + outcome, "Invalid outcome: " + outcome);

        String channel = upper(b.get("channel"));
        if (channel == null) channel = DEFAULT_CHANNEL;
        else if (!CHANNELS.contains(channel)) throw bad("Geçersiz kanal: " + channel, "Invalid channel: " + channel);

        Long userId = null;
        Object rawUser = b.get("contactedUserId");
        if (rawUser != null && !(rawUser instanceof String rs && rs.isBlank())) {
            userId = rawUser instanceof Number n ? Long.valueOf(n.longValue()) : parseLong(rawUser.toString());
            if (userId == null) throw bad("Geçersiz kişi kimliği", "Invalid person id");
        }

        String name = cleanLine(str(b.get("contactedName")));
        if (userId == null) {
            if (name == null) throw bad("Aranan kişi zorunlu — listeden seçin ya da adını yazın",
                    "Who was called is required — pick from the list or type their name");
            if (name.length() > NAME_MAX) throw bad("Ad en fazla " + NAME_MAX + " karakter olabilir",
                    "The name can be at most " + NAME_MAX + " characters");
            if (looksLikePhone(name)) throw bad("Ad alanına telefon numarası yazılmaz — yalnız kişinin adını yazın",
                    "Don't put a phone number in the name field — just the person's name");
        }

        Instant at = now;
        String rawAt = str(b.get("contactedAt"));
        if (rawAt != null && !rawAt.isBlank()) {
            at = parseClient(rawAt.trim());
            if (at == null) throw bad("Geçersiz arama zamanı: " + rawAt, "Invalid call time: " + rawAt);
        }
        at = at.truncatedTo(ChronoUnit.SECONDS);
        if (at.isAfter(now.plus(FUTURE_SKEW))) throw bad("Arama zamanı gelecekte olamaz", "The call time can't be in the future");
        if (at.isAfter(now)) at = now.truncatedTo(ChronoUnit.SECONDS);   // saat kayması payı → "şimdi"
        Instant opened = parseStored(alertCreatedAt);
        if (opened != null && at.isBefore(opened.minus(BEFORE_OPEN_TOLERANCE))) throw bad(
                "Arama zamanı uyarının açılışından önce olamaz", "The call time can't be before the alert was raised");

        String note = cleanNote(str(b.get("note")));
        if (note != null && note.length() > NOTE_MAX) throw bad("Not en fazla " + NOTE_MAX + " karakter olabilir",
                "The note can be at most " + NOTE_MAX + " characters");

        return new Input(userId, userId == null ? name : null, at, channel, outcome, note);
    }

    // ── Metin temizliği ──────────────────────────────────────────────────────

    /** Yön denetim karakterleri (Trojan-source / görünüm sahteciliği) — kontrol karakteri gibi ayıklanır. */
    private static boolean isBidiControl(int cp) {
        return (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069) || cp == 0x200E || cp == 0x200F;
    }

    /** Tek satır: TÜM kontrol karakterleri boşluğa, ardışık boşluk teke; boşsa null. */
    static String cleanLine(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (isBidiControl(cp)) return;
            sb.appendCodePoint(Character.isISOControl(cp) || Character.isWhitespace(cp) ? ' ' : cp);
        });
        String out = sb.toString().replaceAll(" {2,}", " ").trim();
        return out.isEmpty() ? null : out;
    }

    /** Çok satırlı not: satır sonları {@code \n}'e normalize, sekme boşluğa, diğer kontrol karakterleri atılır. */
    static String cleanNote(String s) {
        if (s == null) return null;
        String norm = s.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(norm.length());
        norm.codePoints().forEach(cp -> {
            if (cp == '\n') { sb.append('\n'); return; }
            if (cp == '\t') { sb.append(' '); return; }
            if (Character.isISOControl(cp) || isBidiControl(cp)) return;
            sb.appendCodePoint(cp);
        });
        String out = sb.toString().strip();
        return out.isEmpty() ? null : out;
    }

    static boolean looksLikePhone(String s) {
        if (s == null) return false;
        int digits = 0;
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) digits++;
        return digits >= PHONE_DIGITS;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    /** İstemci anı: ofsetli/Z'li ISO ya da ofsetsiz (UTC kabul edilir — proje biçimi). */
    static Instant parseClient(String v) {
        try { return OffsetDateTime.parse(v).toInstant(); } catch (Exception ignored) { /* sıradaki biçim */ }
        try { return Instant.parse(v); } catch (Exception ignored) { /* sıradaki biçim */ }
        try { return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC); } catch (Exception ignored) { /* geçersiz */ }
        return null;
    }

    /** Saklanan proje damgası (UTC, eksiz ya da Z'li); çözülemezse null. */
    static Instant parseStored(String v) {
        if (v == null || v.isBlank()) return null;
        return parseClient(v.trim());
    }

    private void activity(AlertEvent ev, Long teamId, String action, HttpSession s, NocCallLog c) {
        try {
            String who = c.getContactedName() == null ? "—" : c.getContactedName();
            String summary = ("NOC_CALL_DELETED".equals(action) ? "7/24 arama kaydı silindi: " : "7/24 arama: ")
                    + who + " — " + outcomeTr(c.getOutcome()) + " (" + channelTr(c.getChannel()) + ")";
            activityLog.recordLifecycle(activityType(ev.getAlertType()), null, ev.getDomain(), ev.getDomain(), teamId,
                    action, username(s), summary);
        } catch (Exception e) {
            log.debug("7/24 arama etkinlik kaydı atlandı: {}", e.toString());
        }
    }

    /** Etkinlik akışı türü: uyarı tipinin izleme türü (MonitorTypeCatalog); bilinmeyen tipte ham tip. */
    static String activityType(String alertType) {
        String t = MonitorTypeCatalog.typeOfAlert(alertType);
        if (t != null) return t.toUpperCase(Locale.ROOT);
        String raw = alertType == null ? "CERT" : alertType.toUpperCase(Locale.ROOT);
        return raw.length() > 20 ? raw.substring(0, 20) : raw;
    }

    static String outcomeTr(String o) {
        if (o == null) return "—";
        return switch (o) {
            case "REACHED" -> "ulaşıldı";
            case "NO_ANSWER" -> "yanıt yok";
            case "VOICEMAIL" -> "sesli mesaj bırakıldı";
            case "BUSY" -> "hat meşgul";
            case "WRONG_NUMBER" -> "yanlış numara";
            case "ESCALATED" -> "başkasına yönlendirildi";
            default -> o;
        };
    }

    static String channelTr(String c) {
        if (c == null) return "telefon";
        return switch (c) {
            case "PHONE" -> "telefon";
            case "SMS" -> "SMS";
            case "TEAMS" -> "Teams";
            case "EMAIL" -> "e-posta";
            default -> c;
        };
    }

    private static IllegalArgumentException bad(String tr, String en) {
        return new IllegalArgumentException(Msg.t(tr, en));
    }

    private static String upper(Object o) {
        String s = str(o);
        if (s == null || s.isBlank()) return null;
        return s.trim().toUpperCase(Locale.ROOT);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return null; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    static String username(HttpSession s) {
        Object u = s != null ? s.getAttribute("username") : null;
        return u != null ? u.toString() : null;
    }

    static String displayName(HttpSession s) {
        Object dn = s != null ? s.getAttribute("displayName") : null;
        if (dn != null && !dn.toString().isBlank()) return dn.toString();
        return username(s);
    }
}
