package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.SystemMaintenanceSuppression;
import com.sitemonitor.model.SystemMaintenanceWindow;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.SystemMaintenanceSuppressionRepository;
import com.sitemonitor.repository.SystemMaintenanceWindowRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.util.Msg;
import com.sitemonitor.util.SystemMaintenanceSignal;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * SİSTEM BAKIM MODU (2026-10-02, kullanıcı kararı) — global yönetici Ayarlar → Platform → "Sistem Bakımı" sayfasından
 * SiteMonitor'ün KENDİSİNİ bakıma alır. İzleme hedeflerinin bakım pencereleri ({@link MaintenanceService}) AYRI bir
 * özelliktir; adlar karışmasın (kod adı {@code system-maintenance}).
 *
 * <p><b>Durum ZAMANDAN türetilir</b> ({@link #phaseOf}): PLANNED → (ANNOUNCED, duyuru penceresi) → WARNING (uyarı şeridi)
 * → ACTIVE → ENDED; iptal edilen satır CANCELLED. Ayrı bir "başlat" işi yoktur — saat gelince tüm pod'lar aynı veriden
 * aynı kararı verir. Pod başına kısa önbellek ({@code site.monitor.system-maintenance.cache-ms}, varsayılan 5 sn, en çok
 * 5 sn): giriş kapısı, oturum yoklaması ve bildirim hattı HER çağrıda DB'ye gitmez; yöneticinin kendi pod'undaki yazımı
 * önbelleği anında boşaltır, öteki pod'lar en geç 5 sn'de görür. DB okunamazsa son görüntü (yoksa "bakım yok") —
 * kapı açık kalır (fail-open: veritabanı arızası herkesi dışarıda bırakmaz).
 *
 * <p><b>Kapılar:</b> {@link #isActive()} (giriş + {@code AuthInterceptor} oturum kesimi), {@link #notificationsMuted()}
 * (alarm bildirim hattı — yalnız "Bildirimler bakım boyunca sussun" açıksa ve bakım AKTİFKEN), {@link #clientBlock()}
 * (oturum yoklaması / {@code /api/me} ek bloğu), {@link #publicStatus()} (giriş sayfası — kimlik/sayaç YOK).
 *
 * <p><b>Yönetim:</b> planla / hemen bakıma al / düzenle / uzat / hemen bitir / iptal — her adım denetim kaydı
 * ({@code SYSTEM_MAINTENANCE_*}); çakışan pencere ve geçmiş zamanda başlangıç alan yanında (400 + {@code field}) reddedilir.
 * Yan işler (duyuru/düzeltme e-postası, başlangıç/bitiş denetimi, bitişte bildirim telafisi) {@link SystemMaintenanceJobService}'te,
 * {@code scheduler_lock} altında tek pod'da.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMaintenanceService {

    public static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    /** Saklama biçimi (UTC, ekli bölge yok) — alarm/denetim tablolarıyla aynı; sözlük sırası = zaman sırası. */
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Form alanı biçimi — İstanbul yerel tarih+saat. */
    static final DateTimeFormatter LOCAL_IN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    public static final List<Integer> WARN_OPTIONS = List.of(5, 10, 15, 30);
    public static final List<Integer> ANNOUNCE_OPTIONS = List.of(0, 1, 6, 24, 48);
    public static final List<Integer> COUNTDOWN_OPTIONS = List.of(0, 1, 2, 5, 10, 15, 30);
    public static final List<Integer> DURATION_OPTIONS = List.of(15, 30, 60, 90, 120, 240);
    public static final List<Integer> EXTEND_OPTIONS = List.of(15, 30, 60);
    /** "Hemen bakıma al" geri sayımı 0 olsa da başlangıç en az bu kadar ileridir — içeridekiler kısa pencereyi görür. */
    public static final int IMMEDIATE_MIN_SECONDS = 10;
    static final int DEFAULT_WARN_MINUTES = 10;
    static final int DEFAULT_ANNOUNCE_HOURS = 24;
    static final int MIN_DURATION_MINUTES = 5;
    static final int MAX_DURATION_HOURS = 72;
    static final int MAX_LEAD_DAYS = 90;
    static final int MESSAGE_MAX = 1000;
    static final int CONTACT_MAX = 300;

    public static final String RESOURCE = "SYSTEM_MAINTENANCE";
    /** Bildirim günlüğü tetiği / durumu — "never silent": susturulan her bildirim iz bırakır. */
    public static final String TRIGGER = "SYSTEM_MAINTENANCE";
    public static final String STATUS_SKIPPED = "SKIPPED: sistem bakımı";
    public static final String PUSH_SKIPPED = "SKIPPED_SYSTEM_MAINTENANCE";
    /** Açılış sayılan tetikler — bunlar susturulduysa bakım bitince telafi edilir. */
    static final Set<String> OPENING_TRIGGERS = Set.of("INITIAL", "ESCALATION");

    /** Türetilmiş durum. */
    public enum Phase { PLANNED, ANNOUNCED, WARNING, ACTIVE, ENDED, CANCELLED;
        public String wire() { return name().toLowerCase(java.util.Locale.ROOT); }
        public boolean beforeStart() { return this == PLANNED || this == ANNOUNCED || this == WARNING; }
    }

    private final SystemMaintenanceWindowRepository repo;
    private final SystemMaintenanceSuppressionRepository suppressionRepo;
    private final AuditService auditService;
    private final AppUserRepository userRepo;
    private final UserService userService;
    private final TeamRepository teamRepo;

    @Value("${site.monitor.system-maintenance.cache-ms:5000}")
    long cacheMs = 5000;

    /**
     * "Bakım tamamlandı" bildirim süresi (2026-10-02, kullanıcı isteği: "planlı bakım sonlandığında kullanıcılara bir uyarı
     * daha gönderilsin") — bakım BİTTİKTEN sonra bu kadar dakika oturum yoklaması, giriş sayfası ve Durum Sayfası EK
     * {@code state: "ended"} döner (0 = kapalı; iptal edilen bakım hiçbir zaman).
     */
    @Value("${site.monitor.system-maintenance.ended-notice-minutes:60}")
    long endedNoticeMinutes = 60;

    /** Test kancası (paket-özel). */
    Clock clock = Clock.systemUTC();

    private record Snapshot(List<SystemMaintenanceWindow> windows, long atMs) { }

    private volatile Snapshot snapshot;

    // ── Zaman yardımcıları ───────────────────────────────────────────────────────────────────────────

    Instant now() { return Instant.now(clock); }

    static String iso(Instant at) { return at == null ? null : ISO.format(at); }

    static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            String s = iso.endsWith("Z") ? iso.substring(0, iso.length() - 1) : iso;
            if (s.length() > 19) s = s.substring(0, 19);
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** API çıktısı: UTC + 'Z' (istemci saat dilimi tahmin etmesin). */
    static String wire(String iso) { return iso == null ? null : iso + "Z"; }

    /** İstanbul yerel "yyyy-MM-ddTHH:mm" (form değeri). */
    static String local(String iso) {
        Instant at = parse(iso);
        return at == null ? null : LOCAL_IN.format(at.atZone(ZONE));
    }

    /** Sunucu saati (UTC + 'Z') — istemci geri sayımları buna göre hesaplar. */
    public String serverNow() { return iso(now()) + "Z"; }

    // ── Durum türetme ────────────────────────────────────────────────────────────────────────────────

    /** Pencerenin {@code now} anındaki durumu — saf fonksiyon. */
    public static Phase phaseOf(SystemMaintenanceWindow w, Instant now) {
        if (w == null) return null;
        if (w.getCancelledAt() != null) return Phase.CANCELLED;
        Instant start = parse(w.getStartAt()), end = parse(w.getEndAt());
        if (start == null || end == null) return Phase.CANCELLED;   // bozuk satır hiçbir kapıyı tetiklemez
        if (!now.isBefore(end)) return Phase.ENDED;
        if (!now.isBefore(start)) return Phase.ACTIVE;
        int warn = w.getWarnMinutes() == null ? DEFAULT_WARN_MINUTES : Math.max(0, w.getWarnMinutes());
        if (!now.isBefore(start.minus(Duration.ofMinutes(warn)))) return Phase.WARNING;
        int ann = w.getAnnounceHours() == null ? 0 : Math.max(0, w.getAnnounceHours());
        if (ann > 0 && !now.isBefore(start.minus(Duration.ofHours(ann)))) return Phase.ANNOUNCED;
        return Phase.PLANNED;
    }

    // ── Okuma tarafı (pod önbelleği) ────────────────────────────────────────────────────────────────

    /**
     * İptal edilmemiş pencereler: yükleme anında bitmemiş olanlar + son {@code endedNoticeMinutes} dk içinde bitmiş olanlar
     * ("bakım tamamlandı" notu için; 0 = yalnız bitmemişler, eski davranış) — en çok {@code cacheMs} bayat. Bitmiş pencere
     * hiçbir kapıyı tetiklemez: {@link #current()} / {@link #activeWindow()} yalnız AKTİF ya da başlamamış pencereye bakar.
     */
    List<SystemMaintenanceWindow> live() {
        Snapshot s = snapshot;
        long nowMs = clock.millis();
        if (s != null && nowMs - s.atMs() < cacheMs) return s.windows();
        List<SystemMaintenanceWindow> list;
        try {
            Instant since = Instant.ofEpochMilli(nowMs).minus(Duration.ofMinutes(Math.max(0, endedNoticeMinutes)));
            list = repo.findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(iso(since));
            if (list == null) list = List.of();
        } catch (Exception e) {
            // Fail-open: DB okunamıyorsa son görüntü (yoksa "bakım yok") — veritabanı arızası herkesi dışarıda bırakmaz.
            log.debug("Sistem bakımı durumu okunamadı (son görüntü kullanılıyor): {}", e.getMessage());
            list = s != null ? s.windows() : List.of();
        }
        snapshot = new Snapshot(List.copyOf(list), nowMs);
        return snapshot.windows();
    }

    /** Bu pod'un önbelleğini boşaltır (yönetici yazımından sonra). */
    public void invalidate() { snapshot = null; }

    /** Kullanıcıya görünen en yakın pencere: AKTİF olan, yoksa başlamamış en erken pencere. */
    public Optional<SystemMaintenanceWindow> current() {
        Instant now = now();
        SystemMaintenanceWindow first = null;
        for (SystemMaintenanceWindow w : live()) {
            Phase p = phaseOf(w, now);
            if (p == Phase.ACTIVE) return Optional.of(w);
            if (first == null && p != null && p.beforeStart()) first = w;
        }
        return Optional.ofNullable(first);
    }

    /** Şu an AKTİF bakım penceresi. */
    public Optional<SystemMaintenanceWindow> activeWindow() {
        Instant now = now();
        for (SystemMaintenanceWindow w : live()) if (phaseOf(w, now) == Phase.ACTIVE) return Optional.of(w);
        return Optional.empty();
    }

    /** Bakım AKTİF mi — giriş kapısı ve oturum kesimi. Ek sorgu yok (önbellek). */
    public boolean isActive() { return activeWindow().isPresent(); }

    /**
     * Son {@code endedNoticeMinutes} dk içinde (gerçek bitişe göre) BİTMİŞ en son pencere — "bakım tamamlandı" notu
     * (2026-10-02, kullanıcı isteği). İptal edilen pencere bu listeye hiç girmez; ek sorgu yok (pod önbelleği).
     */
    public Optional<SystemMaintenanceWindow> recentlyEnded() {
        if (endedNoticeMinutes <= 0) return Optional.empty();
        Instant now = now();
        Instant since = now.minus(Duration.ofMinutes(endedNoticeMinutes));
        SystemMaintenanceWindow best = null;
        Instant bestEnd = null;
        for (SystemMaintenanceWindow w : live()) {
            if (phaseOf(w, now) != Phase.ENDED) continue;
            Instant end = parse(w.getEndAt());
            if (end == null || end.isBefore(since)) continue;
            if (bestEnd == null || end.isAfter(bestEnd)) {
                best = w;
                bestEnd = end;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Kullanıcıya şu an gösterilen (duyuru / uyarı / aktif) bir pencere var mı — varsa "tamamlandı" notu çizilmez. */
    private boolean anyShown(Instant now) {
        for (SystemMaintenanceWindow w : live()) {
            Phase p = phaseOf(w, now);
            if (p == Phase.ANNOUNCED || p == Phase.WARNING || p == Phase.ACTIVE) return true;
        }
        return false;
    }

    /** "Tamamlandı" penceresi — yalnız gösterilen başka bir bakım yoksa (duyuru/uyarı/aktif her zaman öncelikli). */
    private Optional<SystemMaintenanceWindow> endedNotice() {
        if (endedNoticeMinutes <= 0 || anyShown(now())) return Optional.empty();
        return recentlyEnded();
    }

    /** Alarm bildirimleri susturulsun mu: bakım AKTİF ve "Bildirimler bakım boyunca sussun" açık. */
    public boolean notificationsMuted() {
        return activeWindow().map(w -> Boolean.TRUE.equals(w.getMuteNotifications())).orElse(false);
    }

    /**
     * Bildirim telafisi bekleniyor mu — zamanlı eskalasyon adımları ve sessiz saat özeti bunu bekler: aksi hâlde bakımda
     * açılan alarmın gecikmeli kişisi, takımın ertelenmiş INITIAL'ından ÖNCE aranabilirdi. Bakım bitip telafi işi koşana
     * dek (≤ iş aralığı) true.
     */
    public boolean notificationsHeld() {
        if (notificationsMuted()) return true;
        try {
            for (SystemMaintenanceWindow w : repo.findPendingJobs()) {
                if (Boolean.TRUE.equals(w.getMuteNotifications()) && w.getCancelledAt() == null
                        && w.getCatchUpAt() == null && phaseOf(w, now()) == Phase.ENDED) return true;
            }
        } catch (Exception e) {
            log.debug("Sistem bakımı telafi durumu okunamadı: {}", e.getMessage());
        }
        return false;
    }

    // ── Kullanıcıya giden bloklar ───────────────────────────────────────────────────────────────────

    /**
     * Oturum yoklaması ve {@code /api/me} ek bloğu — EK alan, mevcut alanlara dokunmaz. Duyuru penceresinin dışındaki
     * planlı bakım kullanıcıya gösterilmez ({@code state: none}). Bakım bittikten sonra {@code endedNoticeMinutes} dk
     * {@code state: "ended"} (2026-10-02, kullanıcı isteği: kapatılabilir "bakım tamamlandı" şeridi; kapatma anahtarı
     * pencere + sürüm) — duyuru/uyarı/aktif bir pencere her zaman önce gelir.
     */
    public Map<String, Object> clientBlock() {
        Optional<SystemMaintenanceWindow> c = current();
        Phase p = c.map(w -> phaseOf(w, now())).orElse(null);
        if (p == Phase.ANNOUNCED || p == Phase.WARNING || p == Phase.ACTIVE) {
            SystemMaintenanceWindow w = c.get();
            Map<String, Object> m = windowFields(w, p);
            m.put("id", w.getId());
            m.put("revision", w.getRevision() == null ? 1 : w.getRevision());
            m.put("immediate", Boolean.TRUE.equals(w.getImmediate()));
            return m;
        }
        Optional<SystemMaintenanceWindow> ended = endedNotice();
        if (ended.isEmpty()) return Map.of("state", "none");
        SystemMaintenanceWindow w = ended.get();
        Map<String, Object> m = endedFields(w);
        m.put("id", w.getId());
        m.put("revision", w.getRevision() == null ? 1 : w.getRevision());
        return m;
    }

    /**
     * Giriş sayfası (oturumsuz) — yalnız pencere saatleri, durum, TR/EN mesaj ve iletişim. Kimlik, kişi, IP, sayaç YOK.
     * Duyuru penceresi kapalı (0) olsa bile uyarı aşamasından itibaren görünür (o an zaten içeridekilere duyuruluyor).
     * Bakım bittikten sonra {@code endedNoticeMinutes} dk {@code state: "ended"} ("Planlı bakım tamamlandı; giriş
     * yapabilirsiniz") — kimlik/sürüm burada da YOK.
     */
    public Map<String, Object> publicStatus() {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<SystemMaintenanceWindow> c = current();
        Phase p = c.map(w -> phaseOf(w, now())).orElse(null);
        if (p == Phase.ANNOUNCED || p == Phase.WARNING || p == Phase.ACTIVE) {
            out.putAll(windowFields(c.get(), p));
        } else {
            Optional<SystemMaintenanceWindow> ended = endedNotice();
            if (ended.isPresent()) out.putAll(endedFields(ended.get()));
            else out.put("state", "none");
        }
        out.put("server_now", serverNow());
        return out;
    }

    /** 401/403 gövdesi — pencere bilgisiyle. */
    public Map<String, Object> signalBody() {
        SystemMaintenanceWindow w = activeWindow().orElse(null);
        Map<String, Object> window = w == null ? Map.of("state", "active") : windowFields(w, Phase.ACTIVE);
        return SystemMaintenanceSignal.body(signalMessage(w), window);
    }

    private static Map<String, Object> windowFields(SystemMaintenanceWindow w, Phase p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", p.wire());
        m.put("start_at", wire(w.getStartAt()));
        m.put("end_at", wire(w.getEndAt()));
        m.put("start_local", local(w.getStartAt()));
        m.put("end_local", local(w.getEndAt()));
        m.put("warn_minutes", w.getWarnMinutes() == null ? DEFAULT_WARN_MINUTES : w.getWarnMinutes());
        m.put("announce_hours", w.getAnnounceHours() == null ? 0 : w.getAnnounceHours());
        m.put("message_tr", blankToNull(w.getMessageTr()));
        m.put("message_en", blankToNull(w.getMessageEn()));
        m.put("contact", blankToNull(w.getContact()));
        return m;
    }

    /**
     * "Bakım tamamlandı" alanları — {@code end_at} GERÇEK bitiştir ("Hemen bitir" / "Uzat" sonrası), {@code planned_end_at}
     * planlanan bitiş (erken/uzatılmış bitişi istemci isterse gösterir). Uyarı/duyuru süreleri burada anlamsız, yazılmaz.
     */
    private static Map<String, Object> endedFields(SystemMaintenanceWindow w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", Phase.ENDED.wire());
        m.put("start_at", wire(w.getStartAt()));
        m.put("end_at", wire(w.getEndAt()));
        m.put("planned_end_at", wire(w.getPlannedEndAt()));
        m.put("start_local", local(w.getStartAt()));
        m.put("end_local", local(w.getEndAt()));
        m.put("message_tr", blankToNull(w.getMessageTr()));
        m.put("message_en", blankToNull(w.getMessageEn()));
        m.put("contact", blankToNull(w.getContact()));
        return m;
    }

    /** "Bakım bitince de e-posta gönder" — null (yama öncesi satır) = açık (varsayılan). */
    public static boolean emailOnEnd(SystemMaintenanceWindow w) {
        return w != null && !Boolean.FALSE.equals(w.getEmailOnEnd());
    }

    /** "02.10.2026 22:00 – 23:00" (aynı gün) / "02.10.2026 22:00 – 03.10.2026 01:00" — İstanbul saati. */
    public static String windowText(String startIso, String endIso) {
        Instant s = parse(startIso), e = parse(endIso);
        if (s == null || e == null) return "";
        ZonedDateTime zs = s.atZone(ZONE), ze = e.atZone(ZONE);
        String tail = zs.toLocalDate().equals(ze.toLocalDate()) ? HM.format(ze) : DAY.format(ze) + " " + HM.format(ze);
        return DAY.format(zs) + " " + HM.format(zs) + " – " + tail;
    }

    /** Arayüz dilinde kullanıcı mesajı (Msg.t). */
    static String signalMessage(SystemMaintenanceWindow w) {
        if (w == null) {
            return Msg.t("Sistem bakımda; şu an yalnız global yöneticiler giriş yapabilir.",
                    "The system is under maintenance; only global administrators can sign in right now.");
        }
        String win = windowText(w.getStartAt(), w.getEndAt());
        return Msg.t(win + " (İstanbul saati) arasında planlı bakım yapılmaktadır. Bakım süresince yalnız global yöneticiler giriş yapabilir.",
                "Planned maintenance is in progress " + win + " (Istanbul time). Only global administrators can sign in during maintenance.");
    }

    // ── Kapı sayaçları ve bildirim susturma ─────────────────────────────────────────────────────────

    /** Global yönetici mi (yerel/bootstrap ADMIN; AD kaynaklı müdür değil) — oturumdaki kapsamla aynı kural. */
    public boolean isGlobalAdminAccount(AppUser u) {
        return u != null && "ADMIN".equals(u.getSystemRole()) && userService.computeViewTeamIds(u) == null;
    }

    /** Bakımda reddedilen girişi aktif pencereye sayar. Hata yayılmaz. */
    public void recordBlockedLogin() {
        activeWindow().ifPresent(w -> {
            try { repo.incrementLoginsBlocked(w.getId()); } catch (Exception e) { log.debug("Engellenen giriş sayılamadı: {}", e.getMessage()); }
        });
    }

    /** Bakım başlangıcında kesilen oturumu aktif pencereye sayar. Hata yayılmaz. */
    public void recordSessionEnded() {
        activeWindow().ifPresent(w -> {
            try { repo.incrementSessionsEnded(w.getId()); } catch (Exception e) { log.debug("Kesilen oturum sayılamadı: {}", e.getMessage()); }
        });
    }

    /**
     * Susturulan bildirimin sayacı + telafi kaydı (bakım × alarm başına TEK satır; açılış bayrağı bir kez yükselir). Olaysız
     * tekil bildirimde (alarm kimliği yok) yalnız sayaç artar. Hata bildirim hattına ASLA yayılmaz.
     */
    public void noteSuppressed(Long alertEventId, String trigger) {
        Optional<SystemMaintenanceWindow> aw = activeWindow();
        if (aw.isEmpty()) return;
        Long windowId = aw.get().getId();
        try { repo.incrementNotificationsSuppressed(windowId); } catch (Exception e) { log.debug("Susturma sayılamadı: {}", e.getMessage()); }
        if (alertEventId == null) return;
        boolean opening = trigger != null && OPENING_TRIGGERS.contains(trigger);
        String at = iso(now());
        try {
            if (suppressionRepo.touch(windowId, alertEventId, at, opening) > 0) return;
            SystemMaintenanceSuppression s = new SystemMaintenanceSuppression();
            s.setWindowId(windowId);
            s.setAlertEventId(alertEventId);
            s.setFirstTrigger(trigger);
            s.setOpening(opening);
            s.setSuppressedCount(1);
            s.setFirstAt(at);
            s.setLastAt(at);
            suppressionRepo.save(s);
        } catch (DataIntegrityViolationException race) {
            // Aynı alarm iki pod'da aynı anda susturuldu — satır var, sayacı artır.
            try { suppressionRepo.touch(windowId, alertEventId, at, opening); } catch (Exception ignore) { /* sayaç best-effort */ }
        } catch (Exception e) {
            log.warn("Sistem bakımı susturma kaydı yazılamadı (olay {}): {}", alertEventId, e.getMessage());
        }
    }

    // ── Yönetim: okuma ─────────────────────────────────────────────────────────────────────────────

    /** Ayarlar sayfasının tek okuma ucu: sunucu saati, en yakın pencere, yaklaşanlar, etki, seçenekler, alıcılar. */
    public Map<String, Object> overview() {
        Instant now = now();
        List<SystemMaintenanceWindow> open = repo.findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(iso(now));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("server_now", serverNow());
        SystemMaintenanceWindow cur = null;
        for (SystemMaintenanceWindow w : open) if (phaseOf(w, now) == Phase.ACTIVE) { cur = w; break; }
        if (cur == null && !open.isEmpty()) cur = open.get(0);
        out.put("current", cur == null ? null : toDto(cur, now));
        List<Map<String, Object>> upcoming = new ArrayList<>();
        for (SystemMaintenanceWindow w : open) upcoming.add(toDto(w, now));
        out.put("windows", upcoming);
        out.put("impact", impact());
        Map<String, Object> opts = new LinkedHashMap<>();
        opts.put("warn_minutes", WARN_OPTIONS);
        opts.put("announce_hours", ANNOUNCE_OPTIONS);
        opts.put("countdown_minutes", COUNTDOWN_OPTIONS);
        opts.put("duration_minutes", DURATION_OPTIONS);
        opts.put("extend_minutes", EXTEND_OPTIONS);
        opts.put("default_warn_minutes", DEFAULT_WARN_MINUTES);
        opts.put("default_announce_hours", DEFAULT_ANNOUNCE_HOURS);
        opts.put("max_duration_hours", MAX_DURATION_HOURS);
        out.put("options", opts);
        out.put("recipients", recipients());
        return out;
    }

    /** Etki özeti: şu an canlı oturumu olan kullanıcılar ve bunların kaçı bakım başlayınca çıkış yapacak. */
    public Map<String, Object> impact() {
        int live = 0, affected = 0;
        try {
            for (AppUser u : userRepo.findAllWithActiveSession()) {
                if (!Boolean.TRUE.equals(u.getActive()) || !userService.hasLiveSession(u)) continue;
                live++;
                if (!isGlobalAdminAccount(u)) affected++;
            }
        } catch (Exception e) {
            log.debug("Etki özeti hesaplanamadı: {}", e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("live_sessions", live);
        m.put("affected_sessions", affected);
        m.put("admin_sessions", live - affected);
        return m;
    }

    /** E-posta duyurusu alıcı seçenekleri: aktif + e-postalı kullanıcı sayısı ve takımlar (adresli/adressiz). */
    Map<String, Object> recipients() {
        Map<String, Object> m = new LinkedHashMap<>();
        int users = 0;
        try {
            for (AppUser u : userRepo.findByActiveTrueOrderByUsernameAsc()) {
                if (u.getEmail() != null && !u.getEmail().isBlank()) users++;
            }
        } catch (Exception e) {
            log.debug("Alıcı sayısı okunamadı: {}", e.getMessage());
        }
        m.put("active_users_with_email", users);
        List<Map<String, Object>> teams = new ArrayList<>();
        try {
            for (Team t : teamRepo.findAll()) {
                if (t == null || Boolean.FALSE.equals(t.getActive())) continue;
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("id", t.getId());
                tm.put("name", t.getName());
                tm.put("email", blankToNull(t.getEmail()));
                teams.add(tm);
            }
        } catch (Exception e) {
            log.debug("Takım listesi okunamadı: {}", e.getMessage());
        }
        teams.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
        m.put("teams", teams);
        return m;
    }

    /** Bakım geçmişi — en yeni önce, sayfalı. */
    public Map<String, Object> history(int page, int size) {
        int p = Math.max(1, page), s = Math.max(1, Math.min(size, 200));
        Page<SystemMaintenanceWindow> pg = repo.findAllByOrderByStartAtDescIdDesc(PageRequest.of(p - 1, s));
        Instant now = now();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SystemMaintenanceWindow w : pg.getContent()) rows.add(toDto(w, now));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows);
        out.put("total", pg.getTotalElements());
        out.put("page", p);
        out.put("size", s);
        return out;
    }

    /** Tek pencere ayrıntısı (+ telafi dökümü). */
    public Map<String, Object> detail(Long id) {
        SystemMaintenanceWindow w = load(id);
        Map<String, Object> dto = toDto(w, now());
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        int alerts = 0;
        try {
            for (SystemMaintenanceSuppression s : suppressionRepo.findByWindowId(w.getId())) {
                alerts++;
                String o = s.getOutcome() == null ? "PENDING" : s.getOutcome();
                outcomes.merge(o, 1, Integer::sum);
            }
        } catch (Exception e) {
            log.debug("Susturma dökümü okunamadı: {}", e.getMessage());
        }
        dto.put("suppressed_alerts", alerts);
        dto.put("suppression_outcomes", outcomes);
        return dto;
    }

    /** {@link #toDto(SystemMaintenanceWindow, Instant)} — sunucu saatiyle. */
    public Map<String, Object> toDto(SystemMaintenanceWindow w) { return toDto(w, now()); }

    /** Yönetim ekranı DTO'su (planlanan/gerçek zamanlar, kim, sayaçlar, e-posta durumu). */
    public Map<String, Object> toDto(SystemMaintenanceWindow w, Instant now) {
        Phase p = phaseOf(w, now);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.getId());
        m.put("phase", p == null ? null : p.wire());
        m.put("start_at", wire(w.getStartAt()));
        m.put("end_at", wire(w.getEndAt()));
        m.put("start_local", local(w.getStartAt()));
        m.put("end_local", local(w.getEndAt()));
        m.put("planned_start_at", wire(w.getPlannedStartAt()));
        m.put("planned_end_at", wire(w.getPlannedEndAt()));
        boolean started = p == Phase.ACTIVE || p == Phase.ENDED;
        m.put("actual_start_at", started ? wire(w.getStartAt()) : null);
        m.put("actual_end_at", p == Phase.ENDED ? wire(w.getEndAt()) : null);
        m.put("warn_minutes", w.getWarnMinutes());
        m.put("announce_hours", w.getAnnounceHours());
        m.put("mute_notifications", Boolean.TRUE.equals(w.getMuteNotifications()));
        m.put("immediate", Boolean.TRUE.equals(w.getImmediate()));
        m.put("message_tr", w.getMessageTr());
        m.put("message_en", w.getMessageEn());
        m.put("contact", w.getContact());
        m.put("email_all_users", Boolean.TRUE.equals(w.getEmailAllUsers()));
        m.put("email_team_ids", parseIds(w.getEmailTeamIds()));
        m.put("email_corrections", Boolean.TRUE.equals(w.getEmailCorrections()));
        m.put("email_on_end", emailOnEnd(w));
        m.put("revision", w.getRevision() == null ? 1 : w.getRevision());
        m.put("created_at", wire(w.getCreatedAt()));
        m.put("created_by", w.getCreatedBy());
        m.put("updated_at", wire(w.getUpdatedAt()));
        m.put("updated_by", w.getUpdatedBy());
        m.put("started_by", w.getStartedBy());
        m.put("ended_by", w.getEndedBy());
        m.put("extended_count", w.getExtendedCount() == null ? 0 : w.getExtendedCount());
        m.put("extended_by", w.getExtendedBy());
        m.put("cancelled_at", wire(w.getCancelledAt()));
        m.put("cancelled_by", w.getCancelledBy());
        m.put("announce_mail_at", wire(w.getAnnounceMailAt()));
        m.put("announce_mail_status", w.getAnnounceMailStatus());
        m.put("announce_mail_count", w.getAnnounceMailCount() == null ? 0 : w.getAnnounceMailCount());
        m.put("correction_mail_count", w.getCorrectionMailCount() == null ? 0 : w.getCorrectionMailCount());
        m.put("end_mail_at", wire(w.getEndMailAt()));
        m.put("end_mail_status", w.getEndMailStatus());
        m.put("end_mail_count", w.getEndMailCount() == null ? 0 : w.getEndMailCount());
        m.put("sessions_ended", w.getSessionsEnded() == null ? 0 : w.getSessionsEnded());
        m.put("logins_blocked", w.getLoginsBlocked() == null ? 0 : w.getLoginsBlocked());
        m.put("notifications_suppressed", w.getNotificationsSuppressed() == null ? 0 : w.getNotificationsSuppressed());
        m.put("caught_up_count", w.getCaughtUpCount() == null ? 0 : w.getCaughtUpCount());
        m.put("catch_up_at", wire(w.getCatchUpAt()));
        return m;
    }

    // ── Yönetim: yazma ─────────────────────────────────────────────────────────────────────────────

    /** Alan-bazlı doğrulama hatası (400 + {@code field}) — arayüz mesajı alanın altında gösterir. */
    public static class FieldException extends IllegalArgumentException {
        private final String field;
        public FieldException(String field, String message) { super(message); this.field = field; }
        public String field() { return field; }
    }

    /** Planlı bakım (başlangıç + bitiş, İstanbul). */
    public SystemMaintenanceWindow schedule(Map<String, Object> body, HttpSession session) {
        Instant now = now();
        Instant start = readTime(body, "start", true);
        Instant end = readTime(body, "end", true);
        validateWindow(start, end, now, null, true);
        SystemMaintenanceWindow w = new SystemMaintenanceWindow();
        applySettings(w, body, false);
        w.setStartAt(iso(start));
        w.setEndAt(iso(end));
        w.setPlannedStartAt(w.getStartAt());
        w.setPlannedEndAt(w.getEndAt());
        w.setImmediate(false);
        w.setRevision(1);
        stampCreated(w, session, now);
        SystemMaintenanceWindow saved = repo.save(w);
        invalidate();
        auditService.recordAction("SYSTEM_MAINTENANCE_SCHEDULED", session, RESOURCE, String.valueOf(saved.getId()),
                AuditDetail.of("start", wire(saved.getStartAt()), "end", wire(saved.getEndAt()),
                        "warn_minutes", saved.getWarnMinutes(), "announce_hours", saved.getAnnounceHours(),
                        "mute_notifications", Boolean.TRUE.equals(saved.getMuteNotifications()),
                        "email_all_users", Boolean.TRUE.equals(saved.getEmailAllUsers()),
                        "email_team_ids", parseIds(saved.getEmailTeamIds()), "email_on_end", emailOnEnd(saved)), null);
        log.info("Sistem bakımı planlandı: #{} {} → {} (uyarı {} dk, duyuru {} sa, bildirim susturma {}) — {}",
                saved.getId(), saved.getStartAt(), saved.getEndAt(), saved.getWarnMinutes(), saved.getAnnounceHours(),
                saved.getMuteNotifications(), actor(session));
        return saved;
    }

    /** "Hemen bakıma al": geri sayım (0 = 10 sn'lik pencere) + süre ya da bitiş saati. */
    public SystemMaintenanceWindow startNow(Map<String, Object> body, HttpSession session) {
        Instant now = now();
        int countdown = intOf(body, "countdown_minutes", 0);
        if (!COUNTDOWN_OPTIONS.contains(countdown)) {
            throw new FieldException("countdown_minutes", Msg.t("Geçersiz geri sayım süresi.", "Invalid countdown."));
        }
        Instant start = now.plusSeconds(Math.max(IMMEDIATE_MIN_SECONDS, countdown * 60L));
        Instant end = readTime(body, "end", false);
        if (end == null) {
            int duration = intOf(body, "duration_minutes", 60);
            if (duration < MIN_DURATION_MINUTES || duration > MAX_DURATION_HOURS * 60) {
                throw new FieldException("duration_minutes", Msg.t("Geçersiz bakım süresi.", "Invalid maintenance duration."));
            }
            end = start.plus(Duration.ofMinutes(duration));
        }
        validateWindow(start, end, now, null, false);
        SystemMaintenanceWindow w = new SystemMaintenanceWindow();
        applySettings(w, body, true);
        // Uyarı şeridi HEMEN görünsün: uyarı süresi geri sayımın kendisidir (en az 1 dk); duyuru yok.
        w.setWarnMinutes(Math.max(1, countdown));
        w.setAnnounceHours(0);
        w.setStartAt(iso(start));
        w.setEndAt(iso(end));
        w.setPlannedStartAt(w.getStartAt());
        w.setPlannedEndAt(w.getEndAt());
        w.setImmediate(true);
        w.setRevision(1);
        stampCreated(w, session, now);
        w.setStartedBy(actor(session));
        SystemMaintenanceWindow saved = repo.save(w);
        invalidate();
        auditService.recordAction("SYSTEM_MAINTENANCE_START_NOW", session, RESOURCE, String.valueOf(saved.getId()),
                AuditDetail.of("countdown_minutes", countdown, "start", wire(saved.getStartAt()), "end", wire(saved.getEndAt()),
                        "mute_notifications", Boolean.TRUE.equals(saved.getMuteNotifications()),
                        "affected_sessions", impact().get("affected_sessions"),
                        "email_all_users", Boolean.TRUE.equals(saved.getEmailAllUsers()),
                        "email_team_ids", parseIds(saved.getEmailTeamIds()), "email_on_end", emailOnEnd(saved)), null);
        log.warn("Sistem HEMEN bakıma alınıyor: #{} başlangıç {} (geri sayım {} dk) bitiş {} — {}",
                saved.getId(), saved.getStartAt(), countdown, saved.getEndAt(), actor(session));
        return saved;
    }

    /** Başlamadan önce düzenleme — saat değişirse sürüm artar (düzeltme e-postası). */
    public SystemMaintenanceWindow update(Long id, Map<String, Object> body, HttpSession session) {
        Instant now = now();
        SystemMaintenanceWindow w = load(id);
        Phase p = phaseOf(w, now);
        if (p == null || !p.beforeStart()) {
            throw new IllegalStateException(Msg.t("Başlamış, bitmiş ya da iptal edilmiş bakım düzenlenemez.",
                    "A maintenance that has started, ended or been cancelled cannot be edited."));
        }
        Instant start = readTime(body, "start", false);
        Instant end = readTime(body, "end", false);
        if (start == null) start = parse(w.getStartAt());
        if (end == null) end = parse(w.getEndAt());
        validateWindow(start, end, now, w.getId(), true);
        Map<String, Object> before = toDto(w, now);
        boolean timeChanged = !iso(start).equals(w.getStartAt()) || !iso(end).equals(w.getEndAt());
        applySettings(w, body, false);
        w.setStartAt(iso(start));
        w.setEndAt(iso(end));
        w.setPlannedStartAt(w.getStartAt());
        w.setPlannedEndAt(w.getEndAt());
        if (timeChanged) w.setRevision((w.getRevision() == null ? 1 : w.getRevision()) + 1);
        w.setUpdatedAt(iso(now));
        w.setUpdatedBy(actor(session));
        SystemMaintenanceWindow saved = repo.save(w);
        invalidate();
        auditService.recordAction("SYSTEM_MAINTENANCE_UPDATED", session, RESOURCE, String.valueOf(saved.getId()),
                AuditDetail.of("start_before", before.get("start_at"), "end_before", before.get("end_at"),
                        "start", wire(saved.getStartAt()), "end", wire(saved.getEndAt()), "time_changed", timeChanged,
                        "warn_minutes", saved.getWarnMinutes(), "announce_hours", saved.getAnnounceHours(),
                        "mute_notifications", Boolean.TRUE.equals(saved.getMuteNotifications()),
                        "email_on_end", emailOnEnd(saved)), null);
        log.info("Sistem bakımı düzenlendi: #{} {} → {} (saat değişti: {}) — {}", saved.getId(), saved.getStartAt(),
                saved.getEndAt(), timeChanged, actor(session));
        return saved;
    }

    /** Uzat: +dk ya da yeni bitiş saati — yalnız aktif (ya da uyarı aşamasındaki) bakım; bitiş yalnız İLERİ alınır. */
    public SystemMaintenanceWindow extend(Long id, Map<String, Object> body, HttpSession session) {
        Instant now = now();
        SystemMaintenanceWindow w = load(id);
        Phase p = phaseOf(w, now);
        if (p != Phase.ACTIVE && p != Phase.WARNING) {
            throw new IllegalStateException(Msg.t("Yalnız süren (ya da başlamak üzere olan) bakım uzatılabilir.",
                    "Only a running (or about to start) maintenance can be extended."));
        }
        Instant oldEnd = parse(w.getEndAt());
        Instant newEnd = readTime(body, "end", false);
        if (newEnd == null) {
            int minutes = intOf(body, "minutes", 0);
            if (!EXTEND_OPTIONS.contains(minutes)) {
                throw new FieldException("minutes", Msg.t("Uzatma süresi 15, 30 ya da 60 dakika olmalı.",
                        "The extension must be 15, 30 or 60 minutes."));
            }
            newEnd = oldEnd.plus(Duration.ofMinutes(minutes));
        }
        if (!newEnd.isAfter(oldEnd) || !newEnd.isAfter(now.plusSeconds(60))) {
            throw new FieldException("end", Msg.t("Yeni bitiş, mevcut bitişten ve şimdiden en az 1 dakika sonra olmalı.",
                    "The new end must be after the current end and at least 1 minute from now."));
        }
        Instant start = parse(w.getStartAt());
        if (Duration.between(start, newEnd).compareTo(Duration.ofHours(MAX_DURATION_HOURS)) > 0) {
            throw new FieldException("end", Msg.t("Bakım en çok " + MAX_DURATION_HOURS + " saat sürebilir.",
                    "A maintenance can last at most " + MAX_DURATION_HOURS + " hours."));
        }
        ensureNoOverlap(start, newEnd, w.getId());
        w.setEndAt(iso(newEnd));
        w.setRevision((w.getRevision() == null ? 1 : w.getRevision()) + 1);
        w.setExtendedCount((w.getExtendedCount() == null ? 0 : w.getExtendedCount()) + 1);
        w.setExtendedBy(actor(session));
        w.setUpdatedAt(iso(now));
        w.setUpdatedBy(actor(session));
        SystemMaintenanceWindow saved = repo.save(w);
        invalidate();
        auditService.recordAction("SYSTEM_MAINTENANCE_EXTENDED", session, RESOURCE, String.valueOf(saved.getId()),
                AuditDetail.of("end_before", wire(iso(oldEnd)), "end", wire(saved.getEndAt()),
                        "added_minutes", Duration.between(oldEnd, newEnd).toMinutes()), null);
        log.info("Sistem bakımı uzatıldı: #{} bitiş {} → {} — {}", saved.getId(), iso(oldEnd), saved.getEndAt(), actor(session));
        return saved;
    }

    /** Hemen bitir: bitiş = şimdi; girişler aynı anda açılır (önbellek bu pod'da boşalır, öteki pod'lar ≤ 5 sn). */
    public SystemMaintenanceWindow endNow(Long id, HttpSession session) {
        Instant now = now();
        SystemMaintenanceWindow w = load(id);
        Phase p = phaseOf(w, now);
        if (p != Phase.ACTIVE) {
            throw new IllegalStateException(p != null && p.beforeStart()
                    ? Msg.t("Bakım henüz başlamadı — iptal edin.", "The maintenance has not started yet — cancel it instead.")
                    : Msg.t("Bakım zaten bitmiş ya da iptal edilmiş.", "The maintenance has already ended or been cancelled."));
        }
        String plannedEnd = w.getEndAt();
        w.setEndAt(iso(now));
        w.setEndedBy(actor(session));
        w.setUpdatedAt(iso(now));
        w.setUpdatedBy(actor(session));
        SystemMaintenanceWindow saved = repo.save(w);
        invalidate();
        auditService.recordAction("SYSTEM_MAINTENANCE_END_NOW", session, RESOURCE, String.valueOf(saved.getId()),
                AuditDetail.of("end_planned", wire(plannedEnd), "end", wire(saved.getEndAt())), null);
        log.warn("Sistem bakımı HEMEN bitirildi: #{} (plan {}) — {}", saved.getId(), plannedEnd, actor(session));
        return saved;
    }

    /** İptal: yalnız başlamadan önce (planlı ya da geri sayımdaki "hemen" bakım). */
    public SystemMaintenanceWindow cancel(Long id, HttpSession session) {
        Instant now = now();
        SystemMaintenanceWindow w = load(id);
        Phase p = phaseOf(w, now);
        if (p == null || !p.beforeStart()) {
            throw new IllegalStateException(p == Phase.ACTIVE
                    ? Msg.t("Süren bakım iptal edilemez — \"Hemen bitir\"i kullanın.", "A running maintenance cannot be cancelled — use \"End now\".")
                    : Msg.t("Bakım zaten bitmiş ya da iptal edilmiş.", "The maintenance has already ended or been cancelled."));
        }
        w.setCancelledAt(iso(now));
        w.setCancelledBy(actor(session));
        w.setRevision((w.getRevision() == null ? 1 : w.getRevision()) + 1);
        w.setUpdatedAt(iso(now));
        w.setUpdatedBy(actor(session));
        SystemMaintenanceWindow saved = repo.save(w);
        invalidate();
        auditService.recordAction("SYSTEM_MAINTENANCE_CANCELLED", session, RESOURCE, String.valueOf(saved.getId()),
                AuditDetail.of("start", wire(saved.getStartAt()), "end", wire(saved.getEndAt()), "phase_before", p.wire()), null);
        log.info("Sistem bakımı iptal edildi: #{} ({}) — {}", saved.getId(), p.wire(), actor(session));
        return saved;
    }

    // ── Doğrulama / okuma yardımcıları ──────────────────────────────────────────────────────────────

    private SystemMaintenanceWindow load(Long id) {
        if (id == null) throw new NoSuchElementException(Msg.t("Bakım kaydı bulunamadı.", "Maintenance not found."));
        return repo.findById(id).orElseThrow(() -> new NoSuchElementException(Msg.t("Bakım kaydı bulunamadı.", "Maintenance not found.")));
    }

    /** {@code <prefix>_local} (İstanbul "yyyy-MM-ddTHH:mm") ya da {@code <prefix>_at} (ISO anı, 'Z'li). */
    static Instant readTime(Map<String, Object> body, String prefix, boolean required) {
        Object local = body == null ? null : body.get(prefix + "_local");
        Object at = body == null ? null : body.get(prefix + "_at");
        String field = prefix + "_local";
        try {
            if (local != null && !local.toString().isBlank()) {
                String s = local.toString().trim();
                if (s.length() > 16) s = s.substring(0, 16);
                return LocalDateTime.parse(s, LOCAL_IN).atZone(ZONE).toInstant();
            }
            if (at != null && !at.toString().isBlank()) {
                Instant parsed = Instant.parse(at.toString().trim().endsWith("Z") ? at.toString().trim() : at.toString().trim() + "Z");
                return parsed;
            }
        } catch (DateTimeParseException e) {
            throw new FieldException(field, Msg.t("Geçersiz tarih/saat.", "Invalid date/time."));
        }
        if (required) {
            throw new FieldException(field, "start".equals(prefix)
                    ? Msg.t("Başlangıç tarihi ve saati zorunlu.", "Start date and time are required.")
                    : Msg.t("Bitiş tarihi ve saati zorunlu.", "End date and time are required."));
        }
        return null;
    }

    /** Sıra, süre, geçmiş zaman ve çakışma kuralları. {@code planned}: başlangıç en az 1 dk ileride olmalı. */
    private void validateWindow(Instant start, Instant end, Instant now, Long selfId, boolean planned) {
        if (planned && start.isBefore(now.plusSeconds(60))) {
            throw new FieldException("start_local", Msg.t("Başlangıç geçmişte olamaz (en az 1 dakika sonrası).",
                    "The start cannot be in the past (at least 1 minute from now)."));
        }
        if (start.isAfter(now.plus(Duration.ofDays(MAX_LEAD_DAYS)))) {
            throw new FieldException("start_local", Msg.t("Başlangıç en çok " + MAX_LEAD_DAYS + " gün sonrası olabilir.",
                    "The start can be at most " + MAX_LEAD_DAYS + " days ahead."));
        }
        if (!end.isAfter(start)) {
            throw new FieldException("end_local", Msg.t("Bitiş, başlangıçtan sonra olmalı.", "The end must be after the start."));
        }
        if (Duration.between(start, end).toMinutes() < MIN_DURATION_MINUTES) {
            throw new FieldException("end_local", Msg.t("Bakım en az " + MIN_DURATION_MINUTES + " dakika sürmeli.",
                    "A maintenance must last at least " + MIN_DURATION_MINUTES + " minutes."));
        }
        if (Duration.between(start, end).compareTo(Duration.ofHours(MAX_DURATION_HOURS)) > 0) {
            throw new FieldException("end_local", Msg.t("Bakım en çok " + MAX_DURATION_HOURS + " saat sürebilir.",
                    "A maintenance can last at most " + MAX_DURATION_HOURS + " hours."));
        }
        ensureNoOverlap(start, end, selfId);
    }

    /** İptal edilmemiş, bitmemiş başka bir pencereyle [start, end) kesişimi yasak. */
    private void ensureNoOverlap(Instant start, Instant end, Long selfId) {
        for (SystemMaintenanceWindow o : repo.findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(iso(now()))) {
            if (selfId != null && selfId.equals(o.getId())) continue;
            Instant os = parse(o.getStartAt()), oe = parse(o.getEndAt());
            if (os == null || oe == null) continue;
            if (start.isBefore(oe) && os.isBefore(end)) {
                throw new FieldException("start_local", Msg.t(
                        "Bu aralık #" + o.getId() + " numaralı bakımla çakışıyor (" + windowText(o.getStartAt(), o.getEndAt()) + ").",
                        "This window overlaps maintenance #" + o.getId() + " (" + windowText(o.getStartAt(), o.getEndAt()) + ")."));
            }
        }
    }

    /** Uyarı/duyuru/susturma/mesaj/e-posta ayarları (gövdede verilmeyen alan mevcut değerini korur). */
    private void applySettings(SystemMaintenanceWindow w, Map<String, Object> body, boolean immediate) {
        Map<String, Object> b = body == null ? Map.of() : body;
        if (!immediate) {
            int warn = intOf(b, "warn_minutes", w.getWarnMinutes() == null ? DEFAULT_WARN_MINUTES : w.getWarnMinutes());
            if (!WARN_OPTIONS.contains(warn)) {
                throw new FieldException("warn_minutes", Msg.t("Uyarı süresi 5, 10, 15 ya da 30 dakika olmalı.",
                        "The warning time must be 5, 10, 15 or 30 minutes."));
            }
            int ann = intOf(b, "announce_hours", w.getAnnounceHours() == null ? DEFAULT_ANNOUNCE_HOURS : w.getAnnounceHours());
            if (!ANNOUNCE_OPTIONS.contains(ann)) {
                throw new FieldException("announce_hours", Msg.t("Duyuru süresi kapalı, 1, 6, 24 ya da 48 saat olmalı.",
                        "The announcement time must be off, 1, 6, 24 or 48 hours."));
            }
            w.setWarnMinutes(warn);
            w.setAnnounceHours(ann);
        }
        if (b.containsKey("mute_notifications") || w.getMuteNotifications() == null) {
            w.setMuteNotifications(Boolean.TRUE.equals(bool(b.get("mute_notifications"))));
        }
        if (b.containsKey("message_tr") || w.getMessageTr() == null) w.setMessageTr(text(b.get("message_tr"), MESSAGE_MAX, "message_tr"));
        if (b.containsKey("message_en") || w.getMessageEn() == null) w.setMessageEn(text(b.get("message_en"), MESSAGE_MAX, "message_en"));
        if (b.containsKey("contact") || w.getContact() == null) w.setContact(text(b.get("contact"), CONTACT_MAX, "contact"));
        if (b.containsKey("email_all_users") || w.getEmailAllUsers() == null) {
            w.setEmailAllUsers(Boolean.TRUE.equals(bool(b.get("email_all_users"))));
        }
        if (b.containsKey("email_team_ids") || w.getEmailTeamIds() == null) {
            List<Long> ids = idsOf(b.get("email_team_ids"));
            for (Long tid : ids) {
                if (!teamRepo.existsById(tid)) {
                    throw new FieldException("email_team_ids", Msg.t("Bilinmeyen takım: #" + tid, "Unknown team: #" + tid));
                }
            }
            w.setEmailTeamIds(ids.isEmpty() ? null : String.join(",", ids.stream().map(String::valueOf).toList()));
        }
        if (b.containsKey("email_corrections") || w.getEmailCorrections() == null) {
            Object v = b.get("email_corrections");
            w.setEmailCorrections(v == null ? Boolean.TRUE : Boolean.TRUE.equals(bool(v)));
        }
        // "Bakım bitince de e-posta gönder" (2026-10-02, kullanıcı isteği) — varsayılan AÇIK; gövdede yoksa mevcut değer korunur.
        if (b.containsKey("email_on_end") || w.getEmailOnEnd() == null) {
            Object v = b.get("email_on_end");
            w.setEmailOnEnd(v == null ? Boolean.TRUE : Boolean.TRUE.equals(bool(v)));
        }
    }

    private static String text(Object v, int max, String field) {
        if (v == null) return null;
        String s = v.toString().strip();
        if (s.isEmpty()) return null;
        if (s.length() > max) {
            throw new FieldException(field, Msg.t("En çok " + max + " karakter.", "At most " + max + " characters."));
        }
        return s;
    }

    private static Boolean bool(Object v) {
        if (v instanceof Boolean b) return b;
        return v != null && "true".equalsIgnoreCase(v.toString().trim());
    }

    static int intOf(Map<String, Object> body, String key, int def) {
        Object v = body == null ? null : body.get(key);
        if (v == null || v.toString().isBlank()) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new FieldException(key, Msg.t("Geçersiz sayı.", "Invalid number."));
        }
    }

    static List<Long> idsOf(Object v) {
        Collection<?> raw = v instanceof Collection<?> c ? c
                : v == null || v.toString().isBlank() ? List.of() : Arrays.asList(v.toString().split(","));
        Set<Long> out = new LinkedHashSet<>();
        for (Object o : raw) {
            if (o == null) continue;
            try { out.add(o instanceof Number n ? n.longValue() : Long.parseLong(o.toString().trim())); }
            catch (NumberFormatException e) { throw new FieldException("email_team_ids", Msg.t("Geçersiz takım kimliği.", "Invalid team id.")); }
        }
        return new ArrayList<>(out);
    }

    static List<Long> parseIds(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<Long> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            try { out.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignore) { /* bozuk parça atlanır */ }
        }
        return out;
    }

    private void stampCreated(SystemMaintenanceWindow w, HttpSession session, Instant now) {
        w.setCreatedAt(iso(now));
        w.setCreatedBy(actor(session));
        w.setCreatedById(userId(session));
        w.setSessionsEnded(0);
        w.setLoginsBlocked(0);
        w.setNotificationsSuppressed(0);
        w.setJobsDone(false);
    }

    static String actor(HttpSession session) {
        Object v = session == null ? null : session.getAttribute("username");
        return v == null ? "system" : v.toString();
    }

    private static Long userId(HttpSession session) {
        Object v = session == null ? null : session.getAttribute("userId");
        if (v instanceof Number n) return n.longValue();
        try { return v == null ? null : Long.valueOf(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
