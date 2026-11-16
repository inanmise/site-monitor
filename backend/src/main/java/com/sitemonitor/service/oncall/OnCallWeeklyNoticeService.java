package com.sitemonitor.service.oncall;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.OnCallOverride;
import com.sitemonitor.model.OnCallSchedule;
import com.sitemonitor.model.OnCallWeeklyNotice;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.OnCallOverrideRepository;
import com.sitemonitor.repository.OnCallScheduleRepository;
import com.sitemonitor.repository.OnCallWeeklyNoticeRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.OnCallWeeklyNoticeMail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Haftalık nöbet bildirimi (2026-11-15, kullanıcı isteği: "takımın nöbetçi bilgisini her hafta pazartesi günü 08:30'da push
 * ve mail üzerinden ekibe bildiren bir geliştirme yapalım; nöbet bilgisi girilmemiş ve bildirim gelmesini istemeyenlere
 * gitmesin; her takım kendi bilgilendirmelerini kendisi takip etsin, ayarlamalarını kendisi yapsın").
 *
 * <ul>
 *   <li><b>Kim açar:</b> takım kendisi, nöbet çizelgesi penceresinden (varsayılan KAPALI). Gün + saat (varsayılan Pazartesi
 *       08:30, İstanbul) ve kanallar (e-posta = takımın alarm adresi — varsayılan bildirim grubu, yoksa takım e-postası;
 *       push = takım üyeleri, takım bildirimi kuralları + kişisel kapatma / erteleme / seviye / pasif) takımın ayarıdır.</li>
 *   <li><b>Tur:</b> {@value #TICK_MS_DEFAULT} ms'de bir; hiçbir takım açmadıysa tek {@code exists} sorgusu, kilit yok. Açan
 *       varsa {@code scheduler_lock} {@value #LOCK_NAME} altında (yenilenen kilit; kapanışta yeni iş başlamaz).</li>
 *   <li><b>Takım × hafta EN ÇOK BİR kez:</b> gönderimden ÖNCE {@code oncall_weekly_notices} satırı eklenir (benzersiz takım ×
 *       hafta — ikinci pod / tur kaybeder), sonuçla kapanır. Yarıda kalan (pod öldü) satır YENİDEN GÖNDERİLMEZ: UNKNOWN + iz.
 *       Aynı gün içinde toplam {@value #MAX_ATTEMPTS} denemeye kadar yeniden denenir: hiçbir kanalın ulaşmadığı FAILED ve
 *       e-postası iletilemeyen PARTIAL (yalnız e-posta). Sistem bakımı susturması sahiplenmeden ÖNCE bakılır: gönderim
 *       pencere içinde bakımın bitmesini bekler; pencere bakımda kapanırsa tek MAINTENANCE satırı. Gövde yan iş
 *       koşucusunda koşar (paylaşılan zamanlayıcı havuzu tutulmaz).</li>
 *   <li><b>Telafi:</b> dilim geçtiyse o İstanbul günü içinde gönderilir (09:00'da açılan pod 08:30'u yine gönderir); gün de
 *       geçmişse "zamanı kaçırıldı" izi yazılır (ayar dilimden önce yapıldıysa).</li>
 *   <li><b>Gönderilmeyen karar da iz bırakır:</b> nöbet bilgisi yok (çizelge kapalı / üye yok / hafta boyunca kimse çözülmüyor),
 *       sistem bakımı susturması, kanal kapalı, adres yok, pasif-yalnız adres — her karar bir {@code notification_logs}
 *       satırı (tetik {@value #TRIGGER}, alarm kimliği 0).</li>
 * </ul>
 */
@Slf4j
@Service
public class OnCallWeeklyNoticeService {

    public static final String TRIGGER = "ONCALL_WEEKLY";
    public static final String LOCK_NAME = "oncall-weekly-notice";
    /** Push günlüğünde görünen bildirim türü (frontend {@code PUSH_TYPE_ALIASES.oncall}). */
    public static final String PUSH_TYPE = "ONCALL";
    static final long TICK_MS_DEFAULT = 60_000;
    /** Bundan eski SENDING satırı yarıda kalmıştır (gönderim saniyeler sürer). */
    static final Duration STALE_SENDING = Duration.ofMinutes(15);
    /** FAILED yeniden denemesi arasında en az bu kadar beklenir. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 3;

    static final String SKIP_NO_ONCALL = "SKIPPED: nöbet bilgisi yok";
    static final String SKIP_MAINTENANCE = SystemMaintenanceService.STATUS_SKIPPED;
    static final String SKIP_CHANNELS_OFF = "SKIPPED: kanallar kapalı (takım ayarı)";
    static final String SKIP_NO_TEAM = "SKIPPED: takım yok";
    static final String SKIP_TEAM_INACTIVE = "SKIPPED: takım pasif";
    static final String SKIP_MISSED = "SKIPPED: zamanı kaçırıldı — gönderilmedi";
    static final String SKIP_NO_ADDRESS = "SKIPPED: takımın e-posta adresi yok";
    /** E-posta kanalı takım ayarıyla kapalı — {@link OnCallService#mailOutcome} bu öneki "off" sayar. */
    public static final String MAIL_OFF = "SKIPPED: e-posta kanalı kapalı (takım ayarı)";
    static final String FAILED_RECIPIENTS = "FAILED: alıcılar okunamadı";
    /** Push sınıfı (satırda {@code push_status}; ekran "kapalı / N kişi / iletilemedi / alıcı yok" der). */
    static final String PUSH_DELIVERED = "delivered";
    static final String PUSH_SKIPPED = "skipped";
    static final String PUSH_FAILED = "failed";
    static final String PUSH_OFF = "off";
    /** Bakımda bekleyen takım × hafta işaretleri (pod belleği) — tavan aşılırsa temizlenir. */
    static final int MUTED_MARKS_MAX = 10_000;
    static final String FAILED_UNKNOWN = "FAILED: sonuç bilinmiyor — gönderen pod durdu; yeniden gönderilmedi";

    // Neden kodları (ekran çevirir; kalıcı)
    static final String R_NO_ONCALL = "NO_ONCALL";
    static final String R_MAINTENANCE = "MAINTENANCE";
    static final String R_CHANNELS_OFF = "CHANNELS_OFF";
    static final String R_NO_TEAM = "NO_TEAM";
    static final String R_TEAM_INACTIVE = "TEAM_INACTIVE";
    static final String R_MISSED = "MISSED";
    static final String R_NO_RECIPIENT = "NO_RECIPIENT";
    static final String R_ERROR = "ERROR";

    private static final int LOG_FIELD_MAX = 255;

    private final OnCallScheduleRepository schedules;
    private final OnCallOverrideRepository overrides;
    private final AppUserRepository users;
    private final OnCallWeeklyNoticeRepository notices;
    private final TeamRepository teams;
    private final NotificationLogRepository notificationLogs;

    @Autowired(required = false) private SchedulerService scheduler;
    @Autowired(required = false) private EscalationService escalation;
    @Autowired(required = false) private EmailNotificationService email;
    @Autowired(required = false) private UserPushService push;
    @Autowired(required = false) private SystemMaintenanceService systemMaintenance;
    @Autowired(required = false) private AppSettingsService appSettings;

    /** Test kancası. */
    Clock clock = Clock.systemUTC();
    /** Tur aralığı = yan iş koşucusundaki sabit gecikme (bitiş + aralık). */
    @Value("${site.monitor.oncall.weekly-notice.tick-ms:60000}")
    long tickMs = TICK_MS_DEFAULT;
    /** Son durum yazımı başarısızsa yeniden deneme beklemeleri (ms; test 0'a çeker) — toplam ≤ 1 sn. */
    long[] finishBackoffMs = {200, 600};
    /**
     * Pencere içinde sistem bakımı yüzünden BEKLEYEN takım × hafta (pod belleği). Pencere bakım sürerken kapanırsa tek
     * SKIPPED / MAINTENANCE satırı yazılır (kaçırıldı değil). Başka pod turu alırsa işaret yoktur; o zaman "şu an susturulmuş
     * mu" sorusu karar verir.
     */
    private final Set<String> mutedWeeks = ConcurrentHashMap.newKeySet();
    /** Kapanış başladı: yeni takım işlenmez (Spring kapanışta {@code @Scheduled} tıklamayı sürdürür). */
    private volatile boolean closing;

    public OnCallWeeklyNoticeService(OnCallScheduleRepository schedules, OnCallOverrideRepository overrides, AppUserRepository users,
                                     OnCallWeeklyNoticeRepository notices, TeamRepository teams,
                                     NotificationLogRepository notificationLogs) {
        this.schedules = schedules;
        this.overrides = overrides;
        this.users = users;
        this.notices = notices;
        this.teams = teams;
        this.notificationLogs = notificationLogs;
    }

    void setScheduler(SchedulerService s) { this.scheduler = s; }
    void setEscalation(EscalationService e) { this.escalation = e; }
    void setEmail(EmailNotificationService e) { this.email = e; }
    void setPush(UserPushService p) { this.push = p; }
    void setSystemMaintenance(SystemMaintenanceService s) { this.systemMaintenance = s; }
    void setAppSettings(AppSettingsService a) { this.appSettings = a; }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        closing = true;
    }

    /** Tur sonucu (log + test). */
    public record TickResult(int sent, int skipped, int failed, int missed, int unknown) {
        static final TickResult EMPTY = new TickResult(0, 0, 0, 0, 0);
    }

    /**
     * Zamanlanmış tık. Ucuz kapı: hiçbir takım açmadıysa VE yarıda kalmış gönderim yoksa iki {@code exists} sorgusu, kilit
     * yok. Gövde (kilit + tur) pod'un YAN İŞ koşucusunda koşar ({@code SideJob.fixedDelay}: bu pod'daki son turun
     * bitişinden en erken bir aralık sonra) — e-posta gönderimi takım takım sürer, paylaşılan 8 iş parçacıklı
     * {@code @Scheduled} havuzunu tutmaz (inceleme 2026-11-15). Koşucu yoksa / üstlenmezse (sahte zamanlayıcı) satır içi.
     */
    @Scheduled(fixedDelayString = "${site.monitor.oncall.weekly-notice.tick-ms:60000}",
               initialDelayString = "${site.monitor.oncall.weekly-notice.initial-delay-ms:75000}")
    public void scheduledTick() {
        if (closing) return;
        if (!anyWork(Instant.now(clock))) return;
        SchedulerService s = scheduler;
        if (s == null) {
            tickAndLog();
            return;
        }
        Runnable body = () -> {
            if (!closing) s.runWithSchedulerLock(LOCK_NAME, this::tickAndLog);
        };
        if (!s.offloadSideJob(com.sitemonitor.service.schedule.SideJob.fixedDelay(LOCK_NAME, tickMs, body))) body.run();
    }

    private void tickAndLog() {
        TickResult r = tick(Instant.now(clock));
        if (r.sent() + r.skipped() + r.failed() + r.missed() + r.unknown() > 0) {
            log.info("Haftalık nöbet bildirimi turu: {} gönderildi, {} atlandı, {} başarısız, {} kaçırıldı, {} yarıda kalmış",
                    r.sent(), r.skipped(), r.failed(), r.missed(), r.unknown());
        }
    }

    /** Tur gerekli mi: bildirimi açık takım VAR ya da yarıda kalmış (eşikten eski) SENDING satırı var. */
    boolean anyWork(Instant now) {
        return anyEnabled() || staleSendingExists(now);
    }

    boolean staleSendingExists(Instant now) {
        try {
            return notices.existsByStatusAndClaimedAtLessThan(OnCallWeeklyNotice.SENDING,
                    OnCallWeeklyNoticeSlots.iso(now.minus(STALE_SENDING)));
        } catch (RuntimeException e) {
            log.debug("Haftalık nöbet bildirimi: yarıda kalan satır kapısı okunamadı (tablo henüz yok?): {}", e.toString());
            return false;
        }
    }

    boolean anyEnabled() {
        try {
            return schedules.existsByWeeklyNoticeEnabledTrue();
        } catch (RuntimeException e) {
            log.debug("Haftalık nöbet bildirimi ön kapısı okunamadı (kolon henüz yok?): {}", e.toString());
            return false;
        }
    }

    /**
     * Tek tur (kilit çağıranda). Önce yarıda kalmış sahiplenmeler UNKNOWN'a çekilir; sonra bildirimi açık her takım için
     * önceki ve bu haftanın dilimi değerlendirilir. Bir takımın hatası ötekileri durdurmaz. Asla fırlatmaz.
     */
    public TickResult tick(Instant now) {
        int[] c = new int[5];   // gönderildi, atlandı, başarısız, kaçırıldı, yarıda kalmış
        try {
            c[4] = sweepStale(now);
            List<OnCallSchedule> list = schedules.findByWeeklyNoticeEnabledTrue();
            if (list.isEmpty()) return new TickResult(0, 0, 0, 0, c[4]);
            Map<String, OnCallWeeklyNotice> rows = new HashMap<>();
            for (OnCallWeeklyNotice n : notices.findByWeekKeyIn(OnCallWeeklyNoticeSlots.candidateWeekKeys(now))) {
                // Kuyruktaki (421) e-postanın sonucu geldiyse satır önce uzlaştırılır: iletilemediyse yeniden deneme kuralı
                // (pencere içinde, deneme hakkı varsa) bu turda uygulanır
                rows.put(n.getTeamId() + "|" + n.getWeekKey(), settleQueuedMail(notices, notificationLogs, n));
            }
            for (OnCallSchedule s : list) {
                if (closing || stopping()) break;
                try {
                    handleTeam(s, rows, now, c);
                } catch (RuntimeException e) {
                    log.warn("Haftalık nöbet bildirimi: takım {} değerlendirilemedi: {}", s.getTeamId(), e.toString());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Haftalık nöbet bildirimi turu tamamlanamadı (sonraki tur yeniden dener): {}", e.toString());
        }
        return new TickResult(c[0], c[1], c[2], c[3], c[4]);
    }

    private boolean stopping() {
        try {
            return scheduler != null && scheduler.sideJobsStopping();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void handleTeam(OnCallSchedule s, Map<String, OnCallWeeklyNotice> rows, Instant now, int[] c) {
        OnCallWeeklyNoticeSlots.Settings st = OnCallWeeklyNoticeSlots.Settings.of(s);
        if (!st.enabled()) return;
        for (Instant slot : OnCallWeeklyNoticeSlots.candidates(now, st.day(), st.time())) {
            if (slot.isAfter(now)) continue;
            String key = OnCallWeeklyNoticeSlots.weekKey(slot);
            String mark = s.getTeamId() + "|" + key;
            OnCallWeeklyNotice row = rows.get(mark);
            boolean inWindow = now.isBefore(OnCallWeeklyNoticeSlots.catchUpEnd(slot));
            if (inWindow) {
                if (row != null && !retryable(row, now)) continue;
                // Sistem bakımı susturması sahiplenmeden ÖNCE: satır yazılmaz, pencere içinde bakım bitince gönderilir
                // (08:00–09:00 bakımı 08:30 dilimini yakmaz). Pencere bakım sürerken kapanırsa tek MAINTENANCE satırı.
                if (muted()) {
                    noteMuted(mark);
                    continue;
                }
                Prev prev = row == null ? null : new Prev(row.getStatus(), row.getAttempts(), row.getFinishedAt());
                OnCallWeeklyNotice claimed = row == null ? claimNew(s.getTeamId(), key, slot, now) : reclaim(row, now);
                if (claimed != null) {
                    String status = send(s, st, claimed, slot, prev);
                    if (status == null) noteMuted(mark);        // gönderimden hemen önce bakım başladı: bırakıldı
                    else {
                        mutedWeeks.remove(mark);
                        count(status, c);
                    }
                }
            } else if (row == null) {
                boolean mutedDuring = mutedWeeks.remove(mark);
                boolean configured = configuredBefore(s, slot);
                if (mutedDuring || (configured && muted())) {
                    if (recordFinal(s.getTeamId(), key, slot, now, R_MAINTENANCE, SKIP_MAINTENANCE)) c[1]++;
                } else if (configured) {
                    if (recordMissed(s.getTeamId(), key, slot, now)) c[3]++;
                }
            } else {
                mutedWeeks.remove(mark);
            }
        }
    }

    /** Sahiplenmeden önceki satır durumu (yeniden denemeyi bırakırken geri yazılır). */
    record Prev(String status, Integer attempts, String finishedAt) { }

    private void noteMuted(String mark) {
        if (mutedWeeks.size() >= MUTED_MARKS_MAX) mutedWeeks.clear();
        mutedWeeks.add(mark);
    }

    private static void count(String status, int[] c) {
        if (OnCallWeeklyNotice.SENT.equals(status) || OnCallWeeklyNotice.PARTIAL.equals(status)) c[0]++;
        else if (OnCallWeeklyNotice.FAILED.equals(status)) c[2]++;
        else c[1]++;
    }

    /** Ayar (açma / gün / saat) dilimden ÖNCE yapıldıysa dilim gerçekten kaçırılmıştır; bilinmiyorsa iz yazılmaz. */
    static boolean configuredBefore(OnCallSchedule s, Instant slot) {
        Instant since = OnCallWeeklyNoticeSlots.parseIso(s.getWeeklyNoticeSince());
        return since != null && !since.isAfter(slot);
    }

    // ── Sahiplenme ───────────────────────────────────────────────────────────────────────────

    /** Yeni satır = sahiplenme. Benzersizlik ihlali (başka pod / tur aldı) → null. */
    OnCallWeeklyNotice claimNew(Long teamId, String key, Instant slot, Instant now) {
        OnCallWeeklyNotice n = new OnCallWeeklyNotice();
        n.setTeamId(teamId);
        n.setWeekKey(key);
        n.setSlotAt(OnCallWeeklyNoticeSlots.iso(slot));
        n.setStatus(OnCallWeeklyNotice.SENDING);
        n.setAttempts(1);
        n.setClaimedAt(OnCallWeeklyNoticeSlots.iso(now));
        n.setClaimedBy(SchedulerService.instanceId());
        n.setCreatedAt(OnCallWeeklyNoticeSlots.iso(now));
        try {
            return notices.saveAndFlush(n);
        } catch (DataIntegrityViolationException e) {
            log.debug("Haftalık nöbet bildirimi: takım {} hafta {} başka bir turda — atlandı", teamId, key);
            return null;
        }
    }

    /**
     * Satır (telafi penceresi içinde) yeniden denenecek mi — aralık hariç: deneme sınırı altında ve FAILED (hiçbir kanal
     * ulaşmadı) ya da PARTIAL olup e-postası İLETİLEMEDİ (push ulaştı; yeniden yalnız e-posta gider). PARTIAL'ın e-postası
     * atlandıysa (adres yok / pasif) ya da push'u ulaşmadıysa kesindir. Ekran "sıradaki gönderim" ve "yeniden denenecek mi"
     * için de bunu okur ({@link OnCallService}).
     */
    public static boolean willRetry(OnCallWeeklyNotice row) {
        if (row == null) return false;
        int attempts = row.getAttempts() == null ? 1 : row.getAttempts();
        if (attempts >= MAX_ATTEMPTS) return false;
        if (OnCallWeeklyNotice.FAILED.equals(row.getStatus())) return true;
        return OnCallWeeklyNotice.PARTIAL.equals(row.getStatus()) && "failed".equals(OnCallService.mailOutcome(row.getMailStatus()));
    }

    /** {@link #willRetry} ve son denemeden {@link #RETRY_AFTER} geçmiş. */
    static boolean retryable(OnCallWeeklyNotice row, Instant now) {
        if (!willRetry(row)) return false;
        Instant last = OnCallWeeklyNoticeSlots.parseIso(row.getFinishedAt() != null ? row.getFinishedAt() : row.getClaimedAt());
        return last == null || !now.isBefore(last.plus(RETRY_AFTER));
    }

    /**
     * Kuyruktaki (SMTP 421, {@code QUEUED_RETRY}) e-postanın GERÇEK sonucunu satıra yazar (2026-11-17, inceleme): posta
     * servisi yeniden denemenin sonucunu gönderimin {@code notification_logs} satırına yazar ({@code mail_log_id}); satır o
     * sonuç gelene dek "gönderildi (e-posta kuyrukta)" görünür, eskiden SONSUZA DEK öyle kalıyordu — e-posta sonunda
     * iletilemese de hafta "bitti" sayılıyordu. Günlük satırı artık kuyrukta değilse: SENT → e-posta iletildi; SKIPPED →
     * atlandı; diğeri (FAILED, yetim / sonuç alınamadı dahil) → iletilemedi ve satırın son durumu kanal sonuçlarından yeniden
     * hesaplanır (push ulaştıysa PARTIAL — pencere içinde yalnız e-posta yeniden denenir; ulaşmadıysa FAILED). Yeniden deneme
     * mevcut kurala tabidir (en çok {@value #MAX_ATTEMPTS} deneme, telafi penceresi içinde). Koşullu UPDATE (okunan durum +
     * posta durumu); okunamazsa / kimlik yoksa satır olduğu gibi döner. Asla fırlatmaz.
     */
    public static OnCallWeeklyNotice settleQueuedMail(OnCallWeeklyNoticeRepository notices, NotificationLogRepository logs,
                                                      OnCallWeeklyNotice row) {
        if (row == null || notices == null || logs == null || row.getMailLogId() == null) return row;
        if (!"queued".equals(OnCallService.mailOutcome(row.getMailStatus()))) return row;
        if (OnCallWeeklyNotice.SENDING.equals(row.getStatus())) return row;
        try {
            String logged = logs.findEmailStatusById(row.getMailLogId()).orElse(null);
            if (logged == null || logged.isBlank() || logged.startsWith("QUEUED")) return row;   // sonuç henüz yok
            Outcome mail = logged.startsWith("SENT") ? Outcome.DELIVERED
                    : logged.startsWith("SKIPPED") ? Outcome.SKIPPED : Outcome.FAILED;
            String status = finalStatus(mail, pushOutcomeOf(row.getPushStatus()));
            String reason = OnCallWeeklyNotice.SKIPPED.equals(status) ? R_NO_RECIPIENT : row.getReason();
            String mailStatus = clip(mail == Outcome.DELIVERED ? "SENT" : logged, LOG_FIELD_MAX);
            int n = notices.settleQueuedMail(row.getId(), row.getStatus(), row.getMailStatus(), status, reason, mailStatus);
            if (n != 1) return row;
            log.info("Haftalık nöbet bildirimi: takım {} hafta {} kuyruktaki e-postanın sonucu geldi ({}) — durum {} → {}",
                    row.getTeamId(), row.getWeekKey(), mail, row.getStatus(), status);
            row.setStatus(status);
            row.setReason(reason);
            row.setMailStatus(mailStatus);
            return row;
        } catch (RuntimeException e) {
            log.debug("Haftalık nöbet bildirimi: kuyruktaki e-postanın sonucu okunamadı (satır {}): {}", row.getId(), e.toString());
            return row;
        }
    }

    /** Satırdaki push sınıfı → kanal sonucu (null / bilinmeyen = ulaşmadı, hata değil). */
    static Outcome pushOutcomeOf(String pushStatus) {
        if (PUSH_DELIVERED.equals(pushStatus)) return Outcome.DELIVERED;
        if (PUSH_OFF.equals(pushStatus)) return Outcome.OFF;
        if (PUSH_FAILED.equals(pushStatus)) return Outcome.FAILED;
        return Outcome.SKIPPED;
    }

    private OnCallWeeklyNotice reclaim(OnCallWeeklyNotice row, Instant now) {
        String stamp = OnCallWeeklyNoticeSlots.iso(now);
        int attempts = row.getAttempts() == null ? 1 : row.getAttempts();
        int n = notices.reclaim(row.getId(), row.getStatus(), attempts, stamp, SchedulerService.instanceId());
        if (n != 1) return null;
        row.setStatus(OnCallWeeklyNotice.SENDING);
        row.setClaimedAt(stamp);
        row.setAttempts(attempts + 1);
        return row;
    }

    /** Kaçırılan dilim: doğrudan SKIPPED / MISSED satırı (benzersiz) + iz. */
    boolean recordMissed(Long teamId, String key, Instant slot, Instant now) {
        return recordFinal(teamId, key, slot, now, R_MISSED,
                SKIP_MISSED + " (" + OnCallWeeklyNoticeMail.dayTime(slot, false) + ")");
    }

    /**
     * Pencere gönderimsiz kapandı: doğrudan SKIPPED satırı (benzersiz — ikinci pod / tur kaybeder) + TEK iz; ekleyen pod
     * izi yazar. Kaçırıldı (uygulama çalışmıyordu) ya da sistem bakımı pencere boyunca sürdü.
     */
    boolean recordFinal(Long teamId, String key, Instant slot, Instant now, String reason, String trace) {
        OnCallWeeklyNotice n = new OnCallWeeklyNotice();
        n.setTeamId(teamId);
        n.setWeekKey(key);
        n.setSlotAt(OnCallWeeklyNoticeSlots.iso(slot));
        n.setStatus(OnCallWeeklyNotice.SKIPPED);
        n.setReason(reason);
        n.setAttempts(1);
        n.setClaimedAt(OnCallWeeklyNoticeSlots.iso(now));
        n.setClaimedBy(SchedulerService.instanceId());
        n.setFinishedAt(OnCallWeeklyNoticeSlots.iso(now));
        n.setCreatedAt(OnCallWeeklyNoticeSlots.iso(now));
        try {
            notices.saveAndFlush(n);
        } catch (DataIntegrityViolationException e) {
            return false;
        }
        String team = teamName(teamId);
        log.warn("Haftalık nöbet bildirimi: takım {} için {} dilimi gönderilmeden kapandı ({}) — gönderilmedi",
                team, OnCallWeeklyNoticeMail.dayTime(slot, false), reason);
        saveLog(team, List.of(), OnCallWeeklyNoticeMail.subject(team, slot, false), trace, null);
        return true;
    }

    /** Yarıda kalmış SENDING satırları → UNKNOWN (yeniden gönderilmez); koşullu UPDATE'i kazanan pod iz yazar. */
    int sweepStale(Instant now) {
        int n = 0;
        try {
            String cutoff = OnCallWeeklyNoticeSlots.iso(now.minus(STALE_SENDING));
            for (OnCallWeeklyNotice r : notices.findTop200ByStatusAndClaimedAtLessThanOrderByIdAsc(OnCallWeeklyNotice.SENDING, cutoff)) {
                if (notices.markUnknown(r.getId(), r.getClaimedAt(), OnCallWeeklyNoticeSlots.iso(now)) != 1) continue;
                n++;
                String team = teamName(r.getTeamId());
                Instant slot = OnCallWeeklyNoticeSlots.parseIso(r.getSlotAt());
                String subject = OnCallWeeklyNoticeMail.subject(team, slot, false);
                log.warn("Haftalık nöbet bildirimi: takım {} hafta {} gönderimi yarıda kalmış (damga {}) — yeniden gönderilmedi",
                        team, r.getWeekKey(), r.getClaimedAt());
                // Gönderim izini yazıp satırı kapatamadan duran pod: sonuç günlükte zaten var, ikinci iz yazılmaz
                if (!tracedSince(subject, r.getClaimedAt())) saveLog(team, List.of(), subject, FAILED_UNKNOWN, null);
            }
        } catch (RuntimeException e) {
            log.debug("Haftalık nöbet bildirimi: yarıda kalan satırlar okunamadı: {}", e.toString());
        }
        return n;
    }

    /** Bu sahiplenmeden sonra bu takım × haftanın izi yazıldı mı (okunamazsa false — iz yazılır, kayıp değil). */
    private boolean tracedSince(String subject, String claimedAt) {
        if (claimedAt == null) return false;
        try {
            return notificationLogs.existsByTriggerAndSubjectAndSentAtGreaterThanEqual(TRIGGER, subject, claimedAt);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── Gönderim ─────────────────────────────────────────────────────────────────────────────

    /** Yeni sahiplenme için (testler). */
    String send(OnCallSchedule s, OnCallWeeklyNoticeSlots.Settings st, OnCallWeeklyNotice row, Instant slot) {
        return send(s, st, row, slot, null);
    }

    /**
     * Sahiplenilmiş satırı gönderir ve kapatır; satırın son durumunu döner. {@code prev} = yeniden denemede satırın önceki
     * durumu (PARTIAL → yalnız e-posta yeniden gider). Sistem bakımı tam bu anda başladıysa sahiplenme BIRAKILIR ve null
     * döner (pencere içinde yeniden denenir). Asla fırlatmaz.
     */
    String send(OnCallSchedule s, OnCallWeeklyNoticeSlots.Settings st, OnCallWeeklyNotice row, Instant slot, Prev prev) {
        Long teamId = s.getTeamId();
        String teamName = "Takım #" + teamId;
        String subject = OnCallWeeklyNoticeMail.subject(teamName, slot, false);
        if (muted()) {
            release(row, prev);
            return null;
        }
        boolean mailOnly = prev != null && OnCallWeeklyNotice.PARTIAL.equals(prev.status());
        try {
            Team team = teams.findById(teamId).orElse(null);
            if (team == null) return finishSkip(row, teamName, subject, SKIP_NO_TEAM, R_NO_TEAM);
            teamName = team.getName() == null || team.getName().isBlank() ? teamName : team.getName();
            subject = OnCallWeeklyNoticeMail.subject(teamName, slot, false);
            if (Boolean.FALSE.equals(team.getActive())) return finishSkip(row, teamName, subject, SKIP_TEAM_INACTIVE, R_TEAM_INACTIVE);
            OnCallWeeklyNoticeMail.Info info = weekInfo(s, teamName, slot);
            if (info == null) return finishSkip(row, teamName, subject, SKIP_NO_ONCALL, R_NO_ONCALL);
            if (!st.mail() && !st.push()) return finishSkip(row, teamName, subject, SKIP_CHANNELS_OFF, R_CHANNELS_OFF);

            // E-posta: takımın alarm adresi (varsayılan bildirim grubu, yoksa takım e-postası)
            Outcome mail;
            String mailStatus;
            List<String> to = List.of();
            String html = null;
            if (!st.mail()) {
                mail = Outcome.OFF;
                mailStatus = MAIL_OFF;
            } else {
                List<String> recipients = null;
                try {
                    recipients = escalation == null ? List.of() : escalation.teamEmailsForMonitor(teamId, null);
                } catch (RuntimeException e) {
                    log.warn("Haftalık nöbet bildirimi: takım {} alıcıları okunamadı: {}", teamName, e.toString());
                }
                if (recipients == null) {
                    mail = Outcome.FAILED;
                    mailStatus = FAILED_RECIPIENTS;
                } else if (recipients.isEmpty() || email == null) {
                    mail = Outcome.SKIPPED;
                    mailStatus = SKIP_NO_ADDRESS;
                } else {
                    to = recipients;
                    MailDoc.Mail m = OnCallWeeklyNoticeMail.build(info, false);
                    html = m.html();
                    String status;
                    try {
                        status = email.sendHtml(to.toArray(new String[0]), null, subject, m.html(), m.text(), null, false, null);
                    } catch (RuntimeException e) {
                        status = "FAILED: " + e.getClass().getSimpleName();
                        log.warn("Haftalık nöbet bildirimi e-postası gönderilemedi (takım {}): {}", teamName, e.toString());
                    }
                    mailStatus = status == null ? "FAILED: sonuç yok" : status;
                    mail = mailStatus.startsWith("SENT") || mailStatus.startsWith("QUEUED") ? Outcome.DELIVERED
                            : mailStatus.startsWith("SKIPPED") ? Outcome.SKIPPED : Outcome.FAILED;
                    log.info("Haftalık nöbet bildirimi e-postası: takım={} alıcı={} durum={}", teamName, SecretMask.maskEmails(to), mailStatus);
                }
            }

            // Push: takım üyeleri (takım bildirimi kuralları; kişisel kapatma / erteleme / seviye / pasif çözümlenir)
            Outcome pushOut;
            int queued = 0;
            String pushNote;
            if (mailOnly) {
                // Önceki denemede push ulaştı; yeniden yalnız e-posta (push anahtarı zaten tekilleştirir, yine de gönderilmez)
                pushOut = Outcome.DELIVERED;
                queued = row.getPushCount() == null ? 0 : row.getPushCount();
                pushNote = " · push: önceki denemede " + queued + " kişi (yeniden gönderilmedi)";
            } else if (!st.push()) {
                pushOut = Outcome.OFF;
                pushNote = " · push: kapalı (takım ayarı)";
            } else if (push == null) {
                pushOut = Outcome.SKIPPED;
                pushNote = " · push: yok";
            } else {
                try {
                    Map<String, Object> pr = push.enqueueTeamNoticeLocalized(teamId, TRIGGER, "WARNING", PUSH_TYPE, teamName,
                            new UserPushService.LocalizedText(OnCallWeeklyNoticeMail.pushText(info, false),
                                    OnCallWeeklyNoticeMail.pushText(info, true)),
                            "oncall-week:" + teamId + ":" + row.getWeekKey(), Set.of());
                    queued = pr.get("queued") instanceof Number q ? q.intValue() : 0;
                    pushOut = queued > 0 ? Outcome.DELIVERED : Outcome.SKIPPED;
                    pushNote = queued > 0 ? " · push " + queued
                            : " · push: " + (pr.get("reason") == null ? "alıcı yok" : String.valueOf(pr.get("reason")));
                } catch (RuntimeException e) {
                    pushOut = Outcome.FAILED;
                    pushNote = " · push: FAILED (" + e.getClass().getSimpleName() + ")";
                    log.warn("Haftalık nöbet bildirimi push'u kuyruğa alınamadı (takım {}): {}", teamName, e.toString());
                }
            }

            String status = finalStatus(mail, pushOut);
            String reason = OnCallWeeklyNotice.SKIPPED.equals(status) ? R_NO_RECIPIENT : null;
            Long logId = saveLog(teamName, to, subject, mailStatus + pushNote, html);
            finish(row, status, reason, mailStatus, pushClass(pushOut), queued, null, logId);
            return status;
        } catch (RuntimeException e) {
            log.warn("Haftalık nöbet bildirimi hazırlanamadı (takım {}): {}", teamName, e.toString());
            saveLog(teamName, List.of(), subject, "FAILED: haftalık nöbet bildirimi hazırlanamadı (" + e.getClass().getSimpleName() + ")", null);
            finish(row, OnCallWeeklyNotice.FAILED, R_ERROR, null, null, 0, e.getClass().getSimpleName(), null);
            return OnCallWeeklyNotice.FAILED;
        }
    }

    static String pushClass(Outcome o) {
        return switch (o) {
            case DELIVERED -> PUSH_DELIVERED;
            case FAILED -> PUSH_FAILED;
            case OFF -> PUSH_OFF;
            default -> PUSH_SKIPPED;
        };
    }

    /** Sahiplenmeyi bırakır: yeni satır silinir, yeniden deneme önceki durumuna döner (koşullu; kaybedilirse süpürme kapatır). */
    private void release(OnCallWeeklyNotice row, Prev prev) {
        try {
            if (prev == null || prev.status() == null) notices.deleteClaim(row.getId(), row.getClaimedAt());
            else notices.revertClaim(row.getId(), row.getClaimedAt(), prev.status(), prev.attempts(), prev.finishedAt());
            log.info("Haftalık nöbet bildirimi: takım {} hafta {} — sistem bakımı başladı, gönderim bakım bitince yeniden denenecek",
                    row.getTeamId(), row.getWeekKey());
        } catch (RuntimeException e) {
            log.warn("Haftalık nöbet bildirimi sahiplenmesi bırakılamadı (takım {} hafta {}): {}", row.getTeamId(),
                    row.getWeekKey(), e.toString());
        }
    }

    /** Kanal sonucu. OFF = takım bu kanalı kapattı. */
    enum Outcome { DELIVERED, SKIPPED, FAILED, OFF }

    /**
     * Satırın son durumu: açık her kanal ulaştıysa SENT; en az biri ulaştı, açık bir kanal ulaşmadıysa PARTIAL; hiçbiri
     * ulaşmadı ve en az biri hata verdiyse FAILED (yeniden denenir); hiçbiri ulaşmadı ve hata yoksa SKIPPED (alıcı yok / pasif).
     */
    static String finalStatus(Outcome mail, Outcome push) {
        boolean delivered = mail == Outcome.DELIVERED || push == Outcome.DELIVERED;
        boolean failed = mail == Outcome.FAILED || push == Outcome.FAILED;
        boolean missing = (mail != Outcome.OFF && mail != Outcome.DELIVERED) || (push != Outcome.OFF && push != Outcome.DELIVERED);
        if (delivered) return missing ? OnCallWeeklyNotice.PARTIAL : OnCallWeeklyNotice.SENT;
        return failed ? OnCallWeeklyNotice.FAILED : OnCallWeeklyNotice.SKIPPED;
    }

    private String finishSkip(OnCallWeeklyNotice row, String teamName, String subject, String status, String reason) {
        saveLog(teamName, List.of(), subject, status, null);
        finish(row, OnCallWeeklyNotice.SKIPPED, reason, null, null, 0, null, null);
        return OnCallWeeklyNotice.SKIPPED;
    }

    /**
     * Son durumu yazar; geçici veritabanı hatasında {@link #finishBackoffMs} aralarla yeniden dener (toplam ≤ 1 sn). Hâlâ
     * yazılamazsa satır SENDING kalır; yarıda kalan satır süpürmesi UNKNOWN'a çeker (yeniden gönderilmez, iz zaten yazıldığı
     * için ikinci iz de yazılmaz).
     */
    private void finish(OnCallWeeklyNotice row, String status, String reason, String mailStatus, String pushStatus, int pushCount,
                        String errorClass, Long mailLogId) {
        String at = OnCallWeeklyNoticeSlots.iso(Instant.now(clock));
        long[] backoff = finishBackoffMs == null ? new long[0] : finishBackoffMs;
        for (int attempt = 0; ; attempt++) {
            try {
                int n = notices.finish(row.getId(), row.getClaimedAt(), status, reason, clip(mailStatus, LOG_FIELD_MAX), pushStatus,
                        pushCount, clip(errorClass, 120), at, mailLogId);
                if (n == 0) {
                    log.warn("Haftalık nöbet bildirimi: takım {} hafta {} sahiplenmesi bu arada değişti — son durum ({}) yazılmadı",
                            row.getTeamId(), row.getWeekKey(), status);
                }
                return;
            } catch (RuntimeException e) {
                if (attempt >= backoff.length || !pause(backoff[attempt])) {
                    log.warn("Haftalık nöbet bildirimi son durumu yazılamadı (takım {} hafta {} → {}, {} deneme): {}", row.getTeamId(),
                            row.getWeekKey(), status, attempt + 1, e.toString());
                    return;
                }
            }
        }
    }

    private static boolean pause(long ms) {
        if (ms <= 0) return true;
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean muted() {
        try {
            return systemMaintenance != null && systemMaintenance.notificationsMuted();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── İçerik ───────────────────────────────────────────────────────────────────────────────

    /**
     * Dilimden sonraki dilime kadar nöbet zaman çizelgesi ({@link OnCallRotation#timeline} — aynı öncelik kuralı). Nöbet
     * bilgisi YOKSA null: çizelge kapalı / üye yok / hafta boyunca var olan bir hesaba çözülen parça yok.
     */
    OnCallWeeklyNoticeMail.Info weekInfo(OnCallSchedule s, String teamName, Instant slot) {
        OnCallRotation.Config cfg = OnCallService.config(s);
        if (!cfg.enabled() || cfg.members().isEmpty()) return null;
        Instant to = slot.plus(OnCallWeeklyNoticeSlots.WEEK);
        List<OnCallRotation.Override> ovs = new ArrayList<>();
        for (OnCallOverride o : overrides.findByTeamIdAndEndsAtGreaterThanOrderByStartsAtAscIdAsc(s.getTeamId(),
                OnCallWeeklyNoticeSlots.iso(slot))) {
            ovs.add(OnCallService.toOverride(o));
        }
        OnCallRotation.Timeline tl = OnCallRotation.timelineCounted(cfg, ovs, slot, to, OnCallRotation.MAX_TIMELINE);
        List<OnCallRotation.Slot> line = tl.slots();
        Set<Long> ids = new LinkedHashSet<>();
        for (OnCallRotation.Slot sl : line) if (sl.userId() != null) ids.add(sl.userId());
        if (ids.isEmpty()) return null;
        Map<Long, AppUser> byId = new HashMap<>();
        for (AppUser u : users.findAllById(ids)) byId.put(u.getId(), u);
        if (byId.isEmpty()) return null;
        List<OnCallWeeklyNoticeMail.Segment> segs = new ArrayList<>();
        for (OnCallRotation.Slot sl : line) {
            OnCallWeeklyNoticeMail.Person p = null;
            if (sl.userId() != null) {
                AppUser u = byId.get(sl.userId());
                p = u == null ? new OnCallWeeklyNoticeMail.Person(null, null, null, false, true)
                        : new OnCallWeeklyNoticeMail.Person(
                                u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName(),
                                u.getPhone(), u.getEmail(), !Boolean.FALSE.equals(u.getActive()), false);
            }
            segs.add(new OnCallWeeklyNoticeMail.Segment(sl.start(), sl.end(), p, sl.override()));
        }
        return new OnCallWeeklyNoticeMail.Info(teamName, slot, to, segs, baseUrl(), tl.omitted());
    }

    private String baseUrl() {
        try {
            return appSettings == null ? "" : appSettings.getString("site.monitor.app.base-url", "");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private String teamName(Long teamId) {
        try {
            return teams.findById(teamId).map(Team::getName).filter(n -> !n.isBlank()).orElse("Takım #" + teamId);
        } catch (RuntimeException e) {
            return "Takım #" + teamId;
        }
    }

    /** Günlük satırını yazar; kimliğini döner (yazılamazsa null — kuyruktaki e-posta o zaman uzlaştırılamaz). */
    private Long saveLog(String teamName, List<String> recipients, String subject, String status, String html) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);
            n.setSentAt(OnCallWeeklyNoticeSlots.iso(Instant.now(clock)));
            n.setRecipientName(teamName);
            n.setRecipientEmail(String.join(", ", recipients == null ? List.of() : recipients));
            n.setRecipientRole("TEAM");
            n.setSubject(subject);
            n.setMessage(html);
            n.setEmailStatus(status);
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(TRIGGER);
            if (email != null) n.setEmailFrom(email.getEmailFrom());
            NotificationLog saved = notificationLogs.save(n);
            return saved == null ? null : saved.getId();
        } catch (RuntimeException e) {
            log.warn("Haftalık nöbet bildirimi günlük kaydı yazılamadı: {}", e.toString());
            return null;
        }
    }

    private static String clip(String v, int max) {
        if (v == null || v.length() <= max) return v;
        return v.substring(0, Math.max(0, max - 1)) + "…";
    }
}
