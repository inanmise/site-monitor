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
 *       Yalnız hiçbir kanalın ulaşmadığı FAILED aynı gün içinde {@value #MAX_ATTEMPTS} denemeye kadar yeniden denenir.</li>
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

    @Scheduled(fixedDelayString = "${site.monitor.oncall.weekly-notice.tick-ms:60000}",
               initialDelayString = "${site.monitor.oncall.weekly-notice.initial-delay-ms:75000}")
    public void scheduledTick() {
        if (closing) return;
        // Hiçbir takım açmadıysa (bugünkü kurulum): tek boolean sorgu, kilit yok
        if (!anyEnabled()) return;
        if (scheduler == null) {
            tick(Instant.now(clock));
            return;
        }
        scheduler.runWithSchedulerLock(LOCK_NAME, () -> {
            TickResult r = tick(Instant.now(clock));
            if (r.sent() + r.skipped() + r.failed() + r.missed() + r.unknown() > 0) {
                log.info("Haftalık nöbet bildirimi turu: {} gönderildi, {} atlandı, {} başarısız, {} kaçırıldı, {} yarıda kalmış",
                        r.sent(), r.skipped(), r.failed(), r.missed(), r.unknown());
            }
        });
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
                rows.put(n.getTeamId() + "|" + n.getWeekKey(), n);
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
            OnCallWeeklyNotice row = rows.get(s.getTeamId() + "|" + key);
            boolean inWindow = now.isBefore(OnCallWeeklyNoticeSlots.catchUpEnd(slot));
            if (inWindow) {
                OnCallWeeklyNotice claimed = row == null ? claimNew(s.getTeamId(), key, slot, now)
                        : retryable(row, now) ? reclaim(row, now) : null;
                if (claimed != null) {
                    String status = send(s, st, claimed, slot);
                    count(status, c);
                }
            } else if (row == null && configuredBefore(s, slot)) {
                if (recordMissed(s.getTeamId(), key, slot, now)) c[3]++;
            }
        }
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

    /** FAILED satırı yeniden denenebilir mi: deneme sınırı altında ve son denemeden {@link #RETRY_AFTER} geçmiş. */
    static boolean retryable(OnCallWeeklyNotice row, Instant now) {
        if (!OnCallWeeklyNotice.FAILED.equals(row.getStatus())) return false;
        int attempts = row.getAttempts() == null ? 1 : row.getAttempts();
        if (attempts >= MAX_ATTEMPTS) return false;
        Instant last = OnCallWeeklyNoticeSlots.parseIso(row.getFinishedAt() != null ? row.getFinishedAt() : row.getClaimedAt());
        return last == null || !now.isBefore(last.plus(RETRY_AFTER));
    }

    private OnCallWeeklyNotice reclaim(OnCallWeeklyNotice row, Instant now) {
        String stamp = OnCallWeeklyNoticeSlots.iso(now);
        int attempts = row.getAttempts() == null ? 1 : row.getAttempts();
        int n = notices.reclaimFailed(row.getId(), attempts, stamp, SchedulerService.instanceId());
        if (n != 1) return null;
        row.setStatus(OnCallWeeklyNotice.SENDING);
        row.setClaimedAt(stamp);
        row.setAttempts(attempts + 1);
        return row;
    }

    /** Kaçırılan dilim: doğrudan SKIPPED satırı (benzersiz) + iz; ekleyen pod izi yazar. */
    boolean recordMissed(Long teamId, String key, Instant slot, Instant now) {
        OnCallWeeklyNotice n = new OnCallWeeklyNotice();
        n.setTeamId(teamId);
        n.setWeekKey(key);
        n.setSlotAt(OnCallWeeklyNoticeSlots.iso(slot));
        n.setStatus(OnCallWeeklyNotice.SKIPPED);
        n.setReason(R_MISSED);
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
        log.warn("Haftalık nöbet bildirimi: takım {} için {} dilimi kaçırıldı (uygulama o gün çalışmıyordu) — gönderilmedi",
                team, OnCallWeeklyNoticeMail.dayTime(slot, false));
        saveLog(team, List.of(), OnCallWeeklyNoticeMail.subject(team, slot, false), SKIP_MISSED + " ("
                + OnCallWeeklyNoticeMail.dayTime(slot, false) + ")", null);
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
                log.warn("Haftalık nöbet bildirimi: takım {} hafta {} gönderimi yarıda kalmış (damga {}) — yeniden gönderilmedi",
                        team, r.getWeekKey(), r.getClaimedAt());
                saveLog(team, List.of(), OnCallWeeklyNoticeMail.subject(team, slot, false), FAILED_UNKNOWN, null);
            }
        } catch (RuntimeException e) {
            log.debug("Haftalık nöbet bildirimi: yarıda kalan satırlar okunamadı: {}", e.toString());
        }
        return n;
    }

    // ── Gönderim ─────────────────────────────────────────────────────────────────────────────

    /** Sahiplenilmiş satırı gönderir ve kapatır; satırın son durumunu döner. Asla fırlatmaz. */
    String send(OnCallSchedule s, OnCallWeeklyNoticeSlots.Settings st, OnCallWeeklyNotice row, Instant slot) {
        Long teamId = s.getTeamId();
        String teamName = "Takım #" + teamId;
        String subject = OnCallWeeklyNoticeMail.subject(teamName, slot, false);
        try {
            Team team = teams.findById(teamId).orElse(null);
            if (team == null) return finishSkip(row, teamName, subject, SKIP_NO_TEAM, R_NO_TEAM);
            teamName = team.getName() == null || team.getName().isBlank() ? teamName : team.getName();
            subject = OnCallWeeklyNoticeMail.subject(teamName, slot, false);
            if (Boolean.FALSE.equals(team.getActive())) return finishSkip(row, teamName, subject, SKIP_TEAM_INACTIVE, R_TEAM_INACTIVE);
            if (muted()) return finishSkip(row, teamName, subject, SKIP_MAINTENANCE, R_MAINTENANCE);
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
            if (!st.push()) {
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
            saveLog(teamName, to, subject, mailStatus + pushNote, html);
            finish(row, status, reason, mailStatus, queued, null);
            return status;
        } catch (RuntimeException e) {
            log.warn("Haftalık nöbet bildirimi hazırlanamadı (takım {}): {}", teamName, e.toString());
            saveLog(teamName, List.of(), subject, "FAILED: haftalık nöbet bildirimi hazırlanamadı (" + e.getClass().getSimpleName() + ")", null);
            finish(row, OnCallWeeklyNotice.FAILED, R_ERROR, null, 0, e.getClass().getSimpleName());
            return OnCallWeeklyNotice.FAILED;
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
        finish(row, OnCallWeeklyNotice.SKIPPED, reason, null, 0, null);
        return OnCallWeeklyNotice.SKIPPED;
    }

    private void finish(OnCallWeeklyNotice row, String status, String reason, String mailStatus, int pushCount, String errorClass) {
        try {
            int n = notices.finish(row.getId(), row.getClaimedAt(), status, reason, clip(mailStatus, LOG_FIELD_MAX), pushCount,
                    clip(errorClass, 120), OnCallWeeklyNoticeSlots.iso(Instant.now(clock)));
            if (n == 0) {
                log.warn("Haftalık nöbet bildirimi: takım {} hafta {} sahiplenmesi bu arada değişti — son durum ({}) yazılmadı",
                        row.getTeamId(), row.getWeekKey(), status);
            }
        } catch (RuntimeException e) {
            // Satır SENDING kalır; yarıda kalan satır süpürmesi UNKNOWN'a çeker (yeniden gönderilmez)
            log.warn("Haftalık nöbet bildirimi son durumu yazılamadı (takım {} hafta {} → {}): {}", row.getTeamId(), row.getWeekKey(),
                    status, e.toString());
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
        List<OnCallRotation.Slot> line = OnCallRotation.timeline(cfg, ovs, slot, to, OnCallRotation.MAX_TIMELINE);
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
        return new OnCallWeeklyNoticeMail.Info(teamName, slot, to, segs, baseUrl());
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

    private void saveLog(String teamName, List<String> recipients, String subject, String status, String html) {
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
            notificationLogs.save(n);
        } catch (RuntimeException e) {
            log.warn("Haftalık nöbet bildirimi günlük kaydı yazılamadı: {}", e.toString());
        }
    }

    private static String clip(String v, int max) {
        if (v == null || v.length() <= max) return v;
        return v.substring(0, Math.max(0, max - 1)) + "…";
    }
}
