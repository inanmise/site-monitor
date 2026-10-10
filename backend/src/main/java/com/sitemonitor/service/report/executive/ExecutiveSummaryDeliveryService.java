package com.sitemonitor.service.report.executive;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.ExecutiveReportRow;
import com.sitemonitor.model.ExecutiveSummaryReport;
import com.sitemonitor.model.ExecutiveSummaryTeamReport;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.ExecutiveSummaryReportRepository;
import com.sitemonitor.repository.ExecutiveSummaryTeamReportRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.InactiveRecipientGuard;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.mail.ExecutiveSummaryMail;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.report.ExecutiveSummaryPdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * AYLIK YÖNETİCİ ÖZETİ GÖNDERİMİ (2026-10-10).
 *
 * <h2>Zamanlama ve "tam bir kez"</h2>
 * <ul>
 *   <li>Varsayılan KAPALI (opt-in, {@link ExecutiveSummarySettings#ENABLED_KEY}). Zamanlanmış tetik
 *       ({@code ExecutiveSummaryScheduling}, canlı cron, varsayılan ayın 1'i 09:00 TR) {@code scheduler_lock}
 *       "executive-summary" altında {@link #runScheduled} çağırır; kapsadığı ay = ÖNCEKİ takvim ayı.</li>
 *   <li>Ay başına TEK kayıt ({@code executive_summary_reports}, UNIQUE yıl×ay): ilk talep INSERT, sonraki talepler koşullu
 *       UPDATE ({@code reclaim}). İki pod aynı anda gelirse yalnız biri talep eder; SENT/PARTIAL bir ay zamanlanmış yoldan
 *       ASLA ikinci kez gitmez.</li>
 *   <li>Saatlik telafi ({@link #catchUp}): bu ayın planlı anından sonraki {@value #CATCH_UP_HOURS} saat içinde, önceki ay
 *       gönderilmemişse (kayıt yok — pod kapalıydı) ya da yeniden denenebilir durumdaysa (FAILED / NO_RECIPIENT /
 *       SKIPPED_MAIL_OFF / SKIPPED_DISABLED) ve deneme sayısı {@value #MAX_ATTEMPTS}'ün altındaysa gönderir. Yarıda kalan
 *       SENDING kaydı otomatik YENİDEN gönderilmez (bazı dilimler gitmiş olabilir) — yönetici "Şimdi gönder" ile karar
 *       verir.</li>
 * </ul>
 *
 * <h2>Takım özetleri (2026-10-10, kullanıcı isteği: "takım bazlı yönetici ayarlaması")</h2>
 * Aynı tetik, kurum özetinden SONRA, gönderimi açık her AKTİF takım için o takımın özetini o takımın alıcılarına gönderir
 * ({@link ExecutiveSummaryTeamService}). Kurum özetinin açık/kapalı olması takım özetlerini etkilemez. Her takım × ay
 * {@code executive_summary_team_reports}'ta aynı "tam bir kez" kapısından geçer; bir takımın hatası diğerlerini durdurmaz.
 * Takım sayısı kadar döngü (sınırlı); kurum geneli ham veri koşu başına bir kez okunur
 * ({@link ExecutiveSummaryService#newRunShared()}). Kapalı takım için kayıt yazılmaz (gürültü olmasın).
 *
 * <h2>Alıcılar</h2>
 * Kurum: açık adres listesi + (ayar açıksa) aktif GLOBAL yöneticilerin adresleri; takım: takım ayarı. Küçük harf tekil.
 * Pasif kullanıcıya ait adres düşer ({@code InactiveRecipientGuard}; huni de ayrıca süzer). Posta GİZLİ alıcılarla (BCC),
 * {@value #BCC_CHUNK}'lük dilimlerle gider; ek: PDF.
 *
 * <h2>İz ("gönderilmeyen bildirim nedenini söyler")</h2>
 * Her dilim {@code notification_logs}'a (tetik {@code EXECUTIVE_SUMMARY}) yazılır; alıcı yoksa {@code SKIPPED: alıcı yok}
 * satırı; özet kapalıyken zamanlanmış koşu ayın kaydına {@code SKIPPED_DISABLED} bırakır. Test gönderimi
 * ({@code EXECUTIVE_SUMMARY_TEST}) yalnız isteyen yöneticinin KENDİ adresine gider ve ay kaydına dokunmaz.
 * Sistem bakımı susturması diğer raporlarda olduğu gibi bu postaya UYGULANMAZ (alarm bildirimi değildir).
 */
@Slf4j
@Service
public class ExecutiveSummaryDeliveryService {

    public static final int BCC_CHUNK = 100;
    public static final int MAX_ATTEMPTS = 3;
    public static final int CATCH_UP_HOURS = 72;
    public static final String TRIGGER = "EXECUTIVE_SUMMARY";
    public static final String TRIGGER_TEST = "EXECUTIVE_SUMMARY_TEST";
    /** Test postası sınırı: yönetici başına {@value #TEST_LIMIT} / {@value #TEST_WINDOW_MS} ms. */
    public static final int TEST_LIMIT = 3;
    public static final long TEST_WINDOW_MS = 10 * 60_000L;

    static final Set<String> RETRYABLE = Set.of(ExecutiveSummaryReport.FAILED, ExecutiveSummaryReport.NO_RECIPIENT,
            ExecutiveSummaryReport.SKIPPED_MAIL_OFF, ExecutiveSummaryReport.SKIPPED_DISABLED);
    private static final DateTimeFormatter UTC_ISO = ExecutiveSummaryContext.UTC_ISO;

    private final ExecutiveSummaryService summaryService;
    private final ExecutiveSummarySettings settings;
    private final ExecutiveSummaryReportRepository reportRepo;
    private final EmailNotificationService emailService;
    private final NotificationLogRepository notificationLogRepo;
    private final AppUserRepository userRepo;
    private final UserService userService;
    private final AppSettingsService appSettings;

    @Autowired(required = false)
    private InactiveRecipientGuard inactiveGuard;

    /** Takım özetleri (birim testlerinde yoksa takım yolu kapalıdır; kurum yolu birebir aynı çalışır). */
    @Autowired(required = false)
    private ExecutiveSummaryTeamService teamService;

    @Autowired(required = false)
    private ExecutiveSummaryTeamReportRepository teamReportRepo;

    private final Map<String, Deque<Long>> testTimes = new ConcurrentHashMap<>();

    public ExecutiveSummaryDeliveryService(ExecutiveSummaryService summaryService, ExecutiveSummarySettings settings,
                                           ExecutiveSummaryReportRepository reportRepo, EmailNotificationService emailService,
                                           NotificationLogRepository notificationLogRepo, AppUserRepository userRepo,
                                           @org.springframework.context.annotation.Lazy UserService userService,
                                           AppSettingsService appSettings) {
        this.summaryService = summaryService;
        this.settings = settings;
        this.reportRepo = reportRepo;
        this.emailService = emailService;
        this.notificationLogRepo = notificationLogRepo;
        this.userRepo = userRepo;
        this.userService = userService;
        this.appSettings = appSettings;
    }

    void setInactiveGuard(InactiveRecipientGuard g) { this.inactiveGuard = g; }

    void setTeamParts(ExecutiveSummaryTeamService teamService, ExecutiveSummaryTeamReportRepository teamReportRepo) {
        this.teamService = teamService;
        this.teamReportRepo = teamReportRepo;
    }

    /** Takım yolu kurulu mu (Spring bağlamında her zaman; bean'siz birim testinde hayır). */
    public boolean teamsAvailable() { return teamService != null && teamReportRepo != null; }

    /** Gönderim sonucu. {@code status} kayıt durumu ya da atlama kodu (ALREADY_SENT, IN_PROGRESS, DISABLED, NOT_DUE …). */
    public record Result(String status, String month, int recipients, int chunks, String detail) { }

    /** Çözülmüş alıcılar: adresler + kaynak sayıları + pasif olduğu için düşenler. */
    public record Recipients(List<String> emails, int explicitCount, int adminCount, int droppedInactive) {
        public boolean isEmpty() { return emails.isEmpty(); }
    }

    /** Gönderimin alıcı kümesi (kurum ya da takım): adresler + pasif olduğu için düşen sayısı. */
    record Audience(List<String> emails, int droppedInactive) {
        boolean isEmpty() { return emails.isEmpty(); }
    }

    // ── Kayıt yuvaları (kurum geneli / takım) ───────────────────────────────────────────────────────────────────────

    /** Ay kaydının deposu — kurum geneli ve takım kayıtları aynı talep / bitirme mantığını paylaşır. */
    interface Slot {
        /** Günlük ve iz etiketi ("kurum geneli" / "takım #5 Ödeme"). */
        String label();
        /** {@code notification_logs.recipient_name}. */
        String traceName();
        ExecutiveReportRow find(YearMonth m);
        ExecutiveReportRow newRow(YearMonth m);
        ExecutiveReportRow saveAndFlush(ExecutiveReportRow r);
        void save(ExecutiveReportRow r);
        int reclaim(Long id, String expectedStatus, Integer expectedAttempts, Integer nextAttempts, String claimedAt,
                    String triggerKind, String actor);
    }

    private final Slot orgSlot = new Slot() {
        @Override public String label() { return "kurum geneli"; }
        @Override public String traceName() { return "Yönetici Özeti"; }
        @Override public ExecutiveReportRow find(YearMonth m) {
            return reportRepo.findByReportYearAndReportMonth(m.getYear(), m.getMonthValue()).orElse(null);
        }
        @Override public ExecutiveReportRow newRow(YearMonth m) {
            ExecutiveSummaryReport r = new ExecutiveSummaryReport();
            r.setReportYear(m.getYear());
            r.setReportMonth(m.getMonthValue());
            return r;
        }
        @Override public ExecutiveReportRow saveAndFlush(ExecutiveReportRow r) {
            return reportRepo.saveAndFlush((ExecutiveSummaryReport) r);
        }
        @Override public void save(ExecutiveReportRow r) { reportRepo.save((ExecutiveSummaryReport) r); }
        @Override public int reclaim(Long id, String st, Integer exp, Integer next, String at, String trig, String actor) {
            return reportRepo.reclaim(id, st, exp, next, at, trig, actor);
        }
    };

    private Slot teamSlot(Long teamId, String teamName) {
        String name = teamName == null || teamName.isBlank() ? "#" + teamId : teamName;
        return new Slot() {
            @Override public String label() { return "takım #" + teamId + " " + name; }
            @Override public String traceName() { return clip("Yönetici Özeti · " + name, 200); }
            @Override public ExecutiveReportRow find(YearMonth m) {
                return teamReportRepo.findByTeamIdAndReportYearAndReportMonth(teamId, m.getYear(), m.getMonthValue())
                        .orElse(null);
            }
            @Override public ExecutiveReportRow newRow(YearMonth m) {
                ExecutiveSummaryTeamReport r = new ExecutiveSummaryTeamReport();
                r.setTeamId(teamId);
                r.setReportYear(m.getYear());
                r.setReportMonth(m.getMonthValue());
                return r;
            }
            @Override public ExecutiveReportRow saveAndFlush(ExecutiveReportRow r) {
                return teamReportRepo.saveAndFlush((ExecutiveSummaryTeamReport) r);
            }
            @Override public void save(ExecutiveReportRow r) { teamReportRepo.save((ExecutiveSummaryTeamReport) r); }
            @Override public int reclaim(Long id, String st, Integer exp, Integer next, String at, String trig, String actor) {
                return teamReportRepo.reclaim(id, st, exp, next, at, trig, actor);
            }
        };
    }

    // ── Alıcılar (kurum) ────────────────────────────────────────────────────────────────────────────────────────────

    public Recipients recipients() {
        Map<String, String> out = new LinkedHashMap<>();
        int explicit = 0, admins = 0, dropped = 0;
        for (String e : settings.explicitRecipients()) {
            if (isInactiveOnly(e)) { dropped++; continue; }
            if (out.putIfAbsent(e.toLowerCase(Locale.ROOT), e) == null) explicit++;
        }
        if (settings.includeGlobalAdmins()) {
            try {
                for (AppUser u : userRepo.findByActiveTrueOrderByUsernameAsc()) {
                    if (!Boolean.TRUE.equals(u.getActive()) || !isGlobalAdmin(u)) continue;
                    String e = u.getEmail() == null ? null : u.getEmail().trim();
                    if (e == null || e.isEmpty() || !e.contains("@")) continue;
                    if (out.putIfAbsent(e.toLowerCase(Locale.ROOT), e) == null) admins++;
                }
            } catch (Exception ex) {
                log.warn("Yönetici özeti: global yöneticiler okunamadı: {}", ex.toString());
            }
        }
        return new Recipients(List.copyOf(out.values()), explicit, admins, dropped);
    }

    private boolean isInactiveOnly(String email) {
        try {
            return inactiveGuard != null && inactiveGuard.isInactiveOnlyEmail(email);
        } catch (Exception e) {
            return false;
        }
    }

    /** Global yönetici: ADMIN + takım kapsamı yok (yerel ADMIN her zaman; LDAP ADMIN kapsamı varsa müdürdür). */
    boolean isGlobalAdmin(AppUser u) {
        if (u == null || !"ADMIN".equals(u.getSystemRole())) return false;
        if (!"LDAP".equalsIgnoreCase(u.getAuthSource())) return true;
        try {
            return userService.computeViewTeamIds(u) == null;
        } catch (Exception e) {
            return false;    // belirsizse kurum geneli rapor GİTMEZ (sızıntıdan iyidir)
        }
    }

    // ── Zamanlanmış / telafi / elle ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Zamanlanmış tetik: önceki ayın özeti. Kurum özeti kapalıysa ayın kaydına SKIPPED_DISABLED bırakır (yalnız ilk kez);
     * ardından gönderimi açık takımların özetleri. Dönen: kurum sonucunun aynısı.
     */
    public Result runScheduled() {
        YearMonth month = summaryService.defaultMonth();
        Result org;
        if (!settings.enabled()) {
            recordDisabled(month);
            log.info("Aylık yönetici özeti kapalı ({}=false) — {} için gönderim yapılmadı", ExecutiveSummarySettings.ENABLED_KEY, month);
            org = new Result("DISABLED", month.toString(), 0, 0, "Yönetici özeti kapalı");
        } else {
            org = deliver(month, "SCHEDULED", "system", false);
        }
        runTeams(month, "SCHEDULED", false);
        pruneTeamReports();
        return org;
    }

    /** Saatlik telafi (sınıf belgesi). Kurum ve takım yolları birbirinden bağımsızdır. */
    public Result catchUp() {
        boolean orgOn = settings.enabled();
        boolean teamsOn = teamsAvailable() && teamService.anyEnabled();
        if (!orgOn && !teamsOn) return new Result("DISABLED", null, 0, 0, null);
        Instant now = summaryService.now();
        YearMonth cur = summaryService.currentMonth();
        Instant planned = plannedFireThisMonth(cur, settings.cron());
        YearMonth month = cur.minusMonths(1);
        if (planned == null || planned.isAfter(now)) return new Result("NOT_DUE", month.toString(), 0, 0, null);
        if (Duration.between(planned, now).toHours() >= CATCH_UP_HOURS) {
            return new Result("WINDOW_CLOSED", month.toString(), 0, 0, null);
        }
        Result org;
        if (!orgOn) {
            org = new Result("DISABLED", month.toString(), 0, 0, null);
        } else {
            ExecutiveSummaryReport row = reportRepo.findByReportYearAndReportMonth(month.getYear(), month.getMonthValue()).orElse(null);
            if (row != null && (!RETRYABLE.contains(row.getStatus()) || attempts(row) >= MAX_ATTEMPTS)) {
                org = new Result("NOTHING_TO_DO", month.toString(), 0, 0, row.getStatus());
            } else {
                org = deliver(month, "CATCH_UP", "system", false);
            }
        }
        if (teamsOn) runTeams(month, "CATCH_UP", true);
        return org;
    }

    /** Elle gönderim (global yönetici, "Şimdi gönder"): kayıt durumundan bağımsız gönderir; yalnız süren gönderim engeller. */
    public Result sendNow(YearMonth month, String actor) {
        return deliver(month, "MANUAL", actor == null ? "admin" : actor, true);
    }

    /** Takımın elle gönderimi (global yönetici ya da takımın müdürü — kapı denetleyicide). */
    public Result sendTeamNow(Long teamId, YearMonth month, String actor) {
        if (!teamsAvailable()) return new Result("DISABLED", month.toString(), 0, 0, null);
        Map<Long, ExecutiveSummaryTeamService.Recipients> rc = teamService.recipientsFor(List.of(teamId));
        ExecutiveSummaryTeamService.Recipients r = rc.get(teamId);
        if (r == null) return new Result("TEAM_INACTIVE", month.toString(), 0, 0, null);
        return deliverTeam(teamId, teamService.teamName(teamId), month, "MANUAL", actor == null ? "admin" : actor, true,
                r, summaryService.newRunShared());
    }

    /**
     * Gönderimi açık takımların özetleri. {@code catchUpOnly}: yalnız kaydı olmayan ya da yeniden denenebilir durumdaki
     * takımlar (telafi). Takım başına hata günlüğe yazılır, sıradaki takım sürer.
     */
    void runTeams(YearMonth month, String trigger, boolean catchUpOnly) {
        if (!teamsAvailable()) return;
        List<Long> ids = teamService.enabledTeamIds();
        if (ids.isEmpty()) return;
        Map<Long, ExecutiveSummaryTeamService.Recipients> recipients = teamService.recipientsFor(ids);
        Map<String, Object> shared = summaryService.newRunShared();
        Map<Long, String> names = summaryService.teamNames();
        int sent = 0, skipped = 0, failed = 0;
        for (Long id : ids) {
            ExecutiveSummaryTeamService.Recipients rc = recipients.get(id);
            if (rc == null) { skipped++; continue; }            // silinmiş ya da pasif takım
            try {
                if (catchUpOnly) {
                    ExecutiveSummaryTeamReport row = teamReportRepo.findByTeamIdAndReportYearAndReportMonth(id,
                            month.getYear(), month.getMonthValue()).orElse(null);
                    if (row != null && (!RETRYABLE.contains(row.getStatus()) || attempts(row) >= MAX_ATTEMPTS)) {
                        skipped++;
                        continue;
                    }
                }
                Result r = deliverTeam(id, names.get(id), month, trigger, "system", false, rc, shared);
                if (ExecutiveReportRow.SENT.equals(r.status()) || ExecutiveReportRow.PARTIAL.equals(r.status())) sent++;
                else skipped++;
            } catch (Exception e) {
                failed++;
                log.warn("Takım yönetici özeti gönderilemedi (takım {} / {}): {}", id, month, e.toString(), e);
            }
        }
        log.info("Takım yönetici özetleri {} ({}): {} takım — {} gönderildi, {} atlandı, {} hata", month, trigger,
                ids.size(), sent, skipped, failed);
    }

    /** Seçilebilir pencereden (24 ay) eski takım kayıtlarını siler — tablo takım × 25 aydan fazla büyümez. */
    void pruneTeamReports() {
        if (teamReportRepo == null) return;
        try {
            YearMonth cutoff = summaryService.currentMonth().minusMonths(ExecutiveSummaryService.MAX_MONTHS_BACK);
            int n = teamReportRepo.deleteOlderThan(cutoff.getYear() * 12 + cutoff.getMonthValue());
            if (n > 0) log.info("Takım yönetici özeti: {} eski kayıt silindi (< {})", n, cutoff);
        } catch (Exception e) {
            log.warn("Takım yönetici özeti eski kayıtları silinemedi: {}", e.toString());
        }
    }

    /** Bu ayın planlı ilk tetiği (cron ayın başından itibaren). Geçersiz cron → varsayılan. */
    static Instant plannedFireThisMonth(YearMonth cur, String cron) {
        ZonedDateTime start = cur.atDay(1).atStartOfDay(ExecutiveSummaryContext.IST).minusSeconds(1);
        ZonedDateTime next;
        try {
            next = CronExpression.parse(cron).next(start);
        } catch (Exception e) {
            next = CronExpression.parse(ExecutiveSummarySettings.DEFAULT_CRON).next(start);
        }
        if (next == null || !YearMonth.from(next).equals(cur)) return null;
        return next.toInstant();
    }

    private static int attempts(ExecutiveReportRow r) {
        return r.getAttempts() == null ? 0 : r.getAttempts();
    }

    /** Kurum ayı kaydı TALEP edilir (tam bir kez), sonra özet + PDF + BCC dilimleri. */
    Result deliver(YearMonth month, String trigger, String actor, boolean force) {
        return deliverTo(orgSlot, month, trigger, actor, force, () -> summaryService.compute(month), () -> {
            Recipients rc = recipients();
            return new Audience(rc.emails(), rc.droppedInactive());
        }, ExecutiveSummarySettings.RECIPIENTS_KEY + " ve global yönetici seçeneği");
    }

    /** Takım ayı kaydı TALEP edilir, sonra takım özeti + PDF + BCC dilimleri (takımın alıcılarına). */
    Result deliverTeam(Long teamId, String teamName, YearMonth month, String trigger, String actor, boolean force,
                       ExecutiveSummaryTeamService.Recipients rc, Map<String, Object> shared) {
        return deliverTo(teamSlot(teamId, teamName), month, trigger, actor, force,
                () -> summaryService.compute(month, teamId, shared),
                () -> new Audience(rc.emails(), rc.droppedInactive()), "takım alıcı ayarı");
    }

    private Result deliverTo(Slot slot, YearMonth month, String trigger, String actor, boolean force,
                             Supplier<ExecutiveSummary> computer, Supplier<Audience> audience, String recipientsHint) {
        ExecutiveReportRow row = claim(slot, month, trigger, actor, force);
        if (row == null) {
            ExecutiveReportRow cur = slot.find(month);
            // SENDING / yarış → IN_PROGRESS; gönderilmiş → ALREADY_SENT; deneme tavanı dolmuş ya da başka pod yeniden talep
            // etmiş (koşullu UPDATE 0 satır) → NOT_CLAIMED.
            String cs = cur == null ? null : cur.getStatus();
            String st = cs == null || ExecutiveReportRow.SENDING.equals(cs) ? "IN_PROGRESS"
                    : ExecutiveReportRow.SENT.equals(cs) || ExecutiveReportRow.PARTIAL.equals(cs) ? "ALREADY_SENT"
                    : "NOT_CLAIMED";
            log.info("Aylık yönetici özeti {} ({}) — {} ({}), gönderim yapılmadı", month, slot.label(), st,
                    cur == null ? "-" : cur.getStatus());
            return new Result(st, month.toString(), 0, 0, cur == null ? null : cur.getStatus());
        }
        ExecutiveSummary summary;
        try {
            summary = computer.get();
        } catch (Exception e) {
            log.error("Aylık yönetici özeti {} ({}) hesaplanamadı: {}", month, slot.label(), e.toString(), e);
            finish(slot, row, ExecutiveReportRow.FAILED, 0, 0, "Özet hesaplanamadı: " + e.getClass().getSimpleName(), null, null);
            return new Result(ExecutiveReportRow.FAILED, month.toString(), 0, 0, "summary");
        }
        Audience rc = audience.get();
        String subject = ExecutiveSummaryMail.subject(summary);
        if (rc.isEmpty()) {
            String why = "SKIPPED: alıcı yok" + (rc.droppedInactive() > 0 ? " (" + rc.droppedInactive() + " pasif kullanıcı adresi düştü)" : "");
            trace(slot, "—", subject, null, why, TRIGGER);
            finish(slot, row, ExecutiveReportRow.NO_RECIPIENT, 0, 0, why, subject, summary);
            log.warn("Aylık yönetici özeti {} ({}): alıcı yok ({}) — gönderilmedi", month, slot.label(), recipientsHint);
            return new Result(ExecutiveReportRow.NO_RECIPIENT, month.toString(), 0, 0, why);
        }
        byte[] pdf = ExecutiveSummaryPdfWriter.render(summary);
        String fileName = ExecutiveSummaryPdfWriter.fileName(summary);
        MailDoc.Mail mail = ExecutiveSummaryMail.build(summary, baseUrl(), ExecFormat.stamp(summaryService.now()),
                pdf.length > 0 ? fileName : null);
        List<EmailNotificationService.MailAttachment> attachments = pdf.length > 0
                ? List.of(new EmailNotificationService.MailAttachment(fileName, pdf, "application/pdf")) : List.of();

        Map<String, Integer> statuses = new LinkedHashMap<>();
        List<String> to = rc.emails();
        int chunks = 0;
        for (int i = 0; i < to.size(); i += BCC_CHUNK) {
            List<String> chunk = to.subList(i, Math.min(to.size(), i + BCC_CHUNK));
            chunks++;
            String st;
            try {
                st = emailService.sendHtmlBccWithAttachments(chunk.toArray(new String[0]), subject, mail.html(),
                        mail.text(), attachments);
            } catch (Exception e) {
                st = "FAILED: " + e.getClass().getSimpleName();
            }
            trace(slot, "BCC×" + chunk.size() + ": " + String.join(", ", chunk), subject, chunks == 1 ? mail.html() : null, st, TRIGGER);
            statuses.merge(statusKey(st), chunk.size(), Integer::sum);
        }
        String detail = detail(statuses) + (pdf.length > 0 ? "" : " · PDF eki üretilemedi");
        String finalStatus = finalStatus(statuses);
        finish(slot, row, finalStatus, to.size(), chunks, detail, subject, summary);
        log.info("Aylık yönetici özeti {} ({} / {}): {} alıcı, {} dilim → {}", month, slot.label(), trigger, to.size(), chunks, detail);
        return new Result(finalStatus, month.toString(), to.size(), chunks, detail);
    }

    static String statusKey(String st) {
        if (st == null || st.startsWith("FAILED")) return "FAILED";
        if (st.startsWith("QUEUED_RETRY")) return "QUEUED_RETRY";
        if (st.startsWith("SKIPPED_DISABLED")) return "SKIPPED_DISABLED";
        if (st.startsWith("SKIPPED")) return "SKIPPED";
        return "SENT";
    }

    /** Dilim sonuçlarından kayıt durumu: hepsi gitti → SENT; hiçbiri → FAILED / SKIPPED_MAIL_OFF; karışık → PARTIAL. */
    static String finalStatus(Map<String, Integer> statuses) {
        int ok = statuses.getOrDefault("SENT", 0) + statuses.getOrDefault("QUEUED_RETRY", 0);
        int total = statuses.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return ExecutiveReportRow.FAILED;
        if (ok == total) return ExecutiveReportRow.SENT;
        if (ok > 0) return ExecutiveReportRow.PARTIAL;
        if (statuses.getOrDefault("SKIPPED_DISABLED", 0) == total) return ExecutiveReportRow.SKIPPED_MAIL_OFF;
        return ExecutiveReportRow.FAILED;
    }

    private static String detail(Map<String, Integer> statuses) {
        StringBuilder sb = new StringBuilder();
        statuses.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(k).append(" ×").append(v));
        return sb.toString();
    }

    /** Kurum ayı kaydını talep eder (eski imza — testler). */
    ExecutiveSummaryReport claim(YearMonth month, String trigger, String actor, boolean force) {
        return (ExecutiveSummaryReport) claim(orgSlot, month, trigger, actor, force);
    }

    /**
     * Ay kaydını talep eder: yoksa INSERT (UNIQUE — yarışı kaybeden düşer), varsa koşullu UPDATE. Zamanlanmış yol yalnız
     * yeniden denenebilir durumları alır; elle yol SENDING dışında her durumu alır. Talep edilemezse null.
     */
    ExecutiveReportRow claim(Slot slot, YearMonth month, String trigger, String actor, boolean force) {
        String now = UTC_ISO.format(summaryService.now());
        ExecutiveReportRow row = slot.find(month);
        if (row == null) {
            ExecutiveReportRow r = slot.newRow(month);
            r.setStatus(ExecutiveReportRow.SENDING);
            r.setAttempts(1);
            r.setTriggerKind(trigger);
            r.setActor(actor);
            r.setClaimedAt(now);
            r.setCreatedAt(now);
            try {
                return slot.saveAndFlush(r);
            } catch (DataIntegrityViolationException e) {
                log.info("Aylık yönetici özeti {} ({}): kayıt başka pod tarafından talep edildi", month, slot.label());
                return null;
            }
        }
        String status = row.getStatus();
        if (ExecutiveReportRow.SENDING.equals(status)) return null;
        if (!force && (!RETRYABLE.contains(status) || attempts(row) >= MAX_ATTEMPTS)) return null;
        int prevAttempts = attempts(row);
        if (row.getAttempts() == null) {
            // Eski/boş deneme sayısı: koşullu UPDATE'in eşitliği null'da çalışmaz → önce sıfırla.
            row.setAttempts(0);
            slot.saveAndFlush(row);
        }
        int n = slot.reclaim(row.getId(), status, prevAttempts, prevAttempts + 1, now, trigger, actor);
        if (n != 1) return null;
        row.setStatus(ExecutiveReportRow.SENDING);
        row.setAttempts(prevAttempts + 1);
        row.setClaimedAt(now);
        row.setTriggerKind(trigger);
        row.setActor(actor);
        return row;
    }

    private void finish(Slot slot, ExecutiveReportRow row, String status, int recipients, int chunks, String detail,
                        String subject, ExecutiveSummary summary) {
        try {
            row.setStatus(status);
            row.setRecipientCount(recipients);
            row.setChunkCount(chunks);
            row.setDetail(clip(detail, 500));
            row.setSubject(clip(subject, 300));
            if (summary != null) {
                row.setSummaryStatus(summary.status());
                row.setSummaryJson(ExecutiveSummaryService.toJson(summary));
            }
            row.setSentAt(UTC_ISO.format(summaryService.now()));
            slot.save(row);
        } catch (Exception e) {
            log.warn("Aylık yönetici özeti kaydı yazılamadı ({}): {}", slot.label(), e.toString());
        }
    }

    /** Kapalıyken zamanlanmış koşu: kurum ayının kaydı yoksa SKIPPED_DISABLED (iz). */
    void recordDisabled(YearMonth month) {
        try {
            if (reportRepo.findByReportYearAndReportMonth(month.getYear(), month.getMonthValue()).isPresent()) return;
            String now = UTC_ISO.format(summaryService.now());
            ExecutiveSummaryReport r = new ExecutiveSummaryReport();
            r.setReportYear(month.getYear());
            r.setReportMonth(month.getMonthValue());
            r.setStatus(ExecutiveSummaryReport.SKIPPED_DISABLED);
            r.setAttempts(0);
            r.setTriggerKind("SCHEDULED");
            r.setActor("system");
            r.setDetail("Yönetici özeti kapalı (varsayılan) — zamanlanmış gönderim yapılmadı");
            r.setCreatedAt(now);
            r.setSentAt(now);
            reportRepo.saveAndFlush(r);
        } catch (DataIntegrityViolationException e) {
            log.debug("Aylık yönetici özeti {}: kapalı kaydı başka podda yazıldı", month);
        } catch (Exception e) {
            log.warn("Aylık yönetici özeti kapalı kaydı yazılamadı: {}", e.toString());
        }
    }

    // ── Test gönderimi ──────────────────────────────────────────────────────────────────────────────────────────────

    /** Test sonucu: durum + hedef adres (maskesiz değil — yalnız isteyenin kendi adresi). */
    public record TestResult(boolean ok, String status, String email) { }

    /** Yönetici başına kayan pencere sınırı — sınır aşıldıysa false. */
    public boolean allowTest(String username) {
        long now = System.currentTimeMillis();
        Deque<Long> q = testTimes.computeIfAbsent(username == null ? "?" : username, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() >= TEST_WINDOW_MS) q.pollFirst();
            if (q.size() >= TEST_LIMIT) return false;
            q.addLast(now);
            return true;
        }
    }

    /** İsteyen kullanıcının e-posta adresi (yoksa null). */
    public String emailOf(String username) {
        if (username == null) return null;
        try {
            return userRepo.findByUsername(username).map(AppUser::getEmail)
                    .map(String::trim).filter(e -> !e.isEmpty() && e.contains("@")).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Kurum test postası: yalnız {@code email}'e, ay kaydı YAZILMAZ; iz {@code EXECUTIVE_SUMMARY_TEST}. */
    public TestResult sendTest(YearMonth month, String email) {
        return sendTestOf(orgSlot, summaryService.compute(month), month, email);
    }

    /** Takım test postası: takım özeti, yalnız isteyenin {@code email}'ine; ay kaydı YAZILMAZ. */
    public TestResult sendTeamTest(Long teamId, YearMonth month, String email) {
        String name = teamsAvailable() ? teamService.teamName(teamId) : null;
        return sendTestOf(teamSlot(teamId, name), summaryService.compute(month, teamId, null), month, email);
    }

    private TestResult sendTestOf(Slot slot, ExecutiveSummary summary, YearMonth month, String email) {
        byte[] pdf = ExecutiveSummaryPdfWriter.render(summary);
        String fileName = ExecutiveSummaryPdfWriter.fileName(summary);
        MailDoc.Mail mail = ExecutiveSummaryMail.build(summary, baseUrl(), ExecFormat.stamp(summaryService.now()),
                pdf.length > 0 ? fileName : null);
        String subject = "[TEST] " + ExecutiveSummaryMail.subject(summary);
        List<EmailNotificationService.MailAttachment> attachments = pdf.length > 0
                ? List.of(new EmailNotificationService.MailAttachment(fileName, pdf, "application/pdf")) : List.of();
        String st;
        try {
            st = emailService.sendHtmlWithAttachments(new String[]{ email }, null, subject, mail.html(), null, attachments);
        } catch (Exception e) {
            st = "FAILED: " + e.getClass().getSimpleName();
        }
        trace(slot, email, subject, mail.html(), st, TRIGGER_TEST);
        String key = statusKey(st);
        return new TestResult("SENT".equals(key) || "QUEUED_RETRY".equals(key), st, email);
    }

    // ── Geçmiş ──────────────────────────────────────────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> history(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExecutiveSummaryReport r : reportRepo.findAllByOrderByReportYearDescReportMonthDesc(
                PageRequest.of(0, Math.max(1, Math.min(limit, 36))))) {
            out.add(ExecutiveSummaryTeamService.historyRow(r));
        }
        return out;
    }

    /** Ayar ekranı: alıcı önizlemesi (sayılar + adresler — yalnız global yöneticiye döner) + sonraki çalışmalar. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>(settings.toMap());
        Recipients rc = recipients();
        m.put("recipient_count", rc.emails().size());
        m.put("recipient_explicit", rc.explicitCount());
        m.put("recipient_admins", rc.adminCount());
        m.put("recipient_dropped_inactive", rc.droppedInactive());
        m.put("recipient_preview", rc.emails().subList(0, Math.min(20, rc.emails().size())));
        m.put("next_runs", nextRuns(settings.cron(), 3));
        m.put("bcc_chunk", BCC_CHUNK);
        m.put("history", history(12));
        return m;
    }

    /** Cron'un sıradaki N çalışması (UTC ISO). */
    public List<String> nextRuns(String cron, int n) {
        List<String> out = new ArrayList<>();
        try {
            CronExpression ce = CronExpression.parse(cron);
            ZonedDateTime cursor = summaryService.now().atZone(ExecutiveSummaryContext.IST);
            for (int i = 0; i < n; i++) {
                cursor = ce.next(cursor);
                if (cursor == null) break;
                out.add(UTC_ISO.format(cursor.toInstant()));
            }
        } catch (Exception ignored) { /* geçersiz cron → boş */ }
        return out;
    }

    /** Takımların ortak zamanlaması (takım ayar ekranı — kurum cron'u ile aynı). */
    public List<String> nextRuns() {
        return nextRuns(settings.cron(), 3);
    }

    // ── İz ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    private void trace(Slot slot, String recipients, String subject, String html, String status, String trigger) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);                       // rapor postasının alarmı yok (diğer raporlarla aynı nöbetçi)
            n.setRecipientEmail(clip(recipients, 250));
            n.setRecipientName(slot.traceName());
            n.setRecipientRole("REPORT");
            n.setSubject(subject);
            n.setMessage(html);
            n.setEmailStatus(status);
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(trigger);
            n.setEmailFrom(emailService.fromAddress());
            n.setSentAt(UTC_ISO.format(summaryService.now()));
            notificationLogRepo.save(n);
        } catch (Exception e) {
            log.warn("Yönetici özeti gönderim izi yazılamadı: {}", e.toString());
        }
    }

    private String baseUrl() {
        try {
            String url = appSettings.getString("site.monitor.app.base-url", "");
            return url == null || url.isBlank() ? null : url.trim().replaceAll("/+$", "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
