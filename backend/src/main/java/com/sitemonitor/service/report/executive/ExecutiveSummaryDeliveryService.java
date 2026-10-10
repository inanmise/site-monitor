package com.sitemonitor.service.report.executive;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.ExecutiveSummaryReport;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.ExecutiveSummaryReportRepository;
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
 * <h2>Alıcılar</h2>
 * Açık adres listesi + (ayar açıksa) aktif GLOBAL yöneticilerin adresleri; küçük harf tekil. Pasif kullanıcıya ait adres
 * düşer ({@code InactiveRecipientGuard}; huni de ayrıca süzer). Posta GİZLİ alıcılarla (BCC), {@value #BCC_CHUNK}'lük
 * dilimlerle gider; ek: PDF.
 *
 * <h2>İz ("gönderilmeyen bildirim nedenini söyler")</h2>
 * Her dilim {@code notification_logs}'a (tetik {@code EXECUTIVE_SUMMARY}) yazılır; alıcı yoksa {@code SKIPPED: alıcı yok}
 * satırı; özet kapalıyken zamanlanmış koşu ayın kaydına {@code SKIPPED_DISABLED} bırakır. Test gönderimi
 * ({@code EXECUTIVE_SUMMARY_TEST}) yalnız isteyen global yöneticinin KENDİ adresine gider ve ay kaydına dokunmaz.
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

    /** Gönderim sonucu. {@code status} kayıt durumu ya da atlama kodu (ALREADY_SENT, IN_PROGRESS, DISABLED, NOT_DUE …). */
    public record Result(String status, String month, int recipients, int chunks, String detail) { }

    /** Çözülmüş alıcılar: adresler + kaynak sayıları + pasif olduğu için düşenler. */
    public record Recipients(List<String> emails, int explicitCount, int adminCount, int droppedInactive) {
        public boolean isEmpty() { return emails.isEmpty(); }
    }

    // ── Alıcılar ────────────────────────────────────────────────────────────────────────────────────────────────────

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

    /** Zamanlanmış tetik: önceki ayın özeti. Kapalıysa ayın kaydına SKIPPED_DISABLED bırakır (yalnız ilk kez). */
    public Result runScheduled() {
        YearMonth month = summaryService.defaultMonth();
        if (!settings.enabled()) {
            recordDisabled(month);
            log.info("Aylık yönetici özeti kapalı ({}=false) — {} için gönderim yapılmadı", ExecutiveSummarySettings.ENABLED_KEY, month);
            return new Result("DISABLED", month.toString(), 0, 0, "Yönetici özeti kapalı");
        }
        return deliver(month, "SCHEDULED", "system", false);
    }

    /** Saatlik telafi (sınıf belgesi). */
    public Result catchUp() {
        if (!settings.enabled()) return new Result("DISABLED", null, 0, 0, null);
        Instant now = summaryService.now();
        YearMonth cur = summaryService.currentMonth();
        Instant planned = plannedFireThisMonth(cur, settings.cron());
        YearMonth month = cur.minusMonths(1);
        if (planned == null || planned.isAfter(now)) return new Result("NOT_DUE", month.toString(), 0, 0, null);
        if (Duration.between(planned, now).toHours() >= CATCH_UP_HOURS) {
            return new Result("WINDOW_CLOSED", month.toString(), 0, 0, null);
        }
        ExecutiveSummaryReport row = reportRepo.findByReportYearAndReportMonth(month.getYear(), month.getMonthValue()).orElse(null);
        if (row != null && (!RETRYABLE.contains(row.getStatus()) || attempts(row) >= MAX_ATTEMPTS)) {
            return new Result("NOTHING_TO_DO", month.toString(), 0, 0, row.getStatus());
        }
        return deliver(month, "CATCH_UP", "system", false);
    }

    /** Elle gönderim (global yönetici, "Şimdi gönder"): kayıt durumundan bağımsız gönderir; yalnız süren gönderim engeller. */
    public Result sendNow(YearMonth month, String actor) {
        return deliver(month, "MANUAL", actor == null ? "admin" : actor, true);
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

    private static int attempts(ExecutiveSummaryReport r) {
        return r.getAttempts() == null ? 0 : r.getAttempts();
    }

    /** Ay kaydı TALEP edilir (tam bir kez), sonra özet + PDF + BCC dilimleri. */
    Result deliver(YearMonth month, String trigger, String actor, boolean force) {
        ExecutiveSummaryReport row = claim(month, trigger, actor, force);
        if (row == null) {
            ExecutiveSummaryReport cur = reportRepo.findByReportYearAndReportMonth(month.getYear(), month.getMonthValue()).orElse(null);
            // SENDING / yarış → IN_PROGRESS; gönderilmiş → ALREADY_SENT; deneme tavanı dolmuş ya da başka pod yeniden talep
            // etmiş (koşullu UPDATE 0 satır) → NOT_CLAIMED.
            String cs = cur == null ? null : cur.getStatus();
            String st = cs == null || ExecutiveSummaryReport.SENDING.equals(cs) ? "IN_PROGRESS"
                    : ExecutiveSummaryReport.SENT.equals(cs) || ExecutiveSummaryReport.PARTIAL.equals(cs) ? "ALREADY_SENT"
                    : "NOT_CLAIMED";
            log.info("Aylık yönetici özeti {} — {} ({}), gönderim yapılmadı", month, st, cur == null ? "-" : cur.getStatus());
            return new Result(st, month.toString(), 0, 0, cur == null ? null : cur.getStatus());
        }
        ExecutiveSummary summary;
        try {
            summary = summaryService.compute(month);
        } catch (Exception e) {
            log.error("Aylık yönetici özeti {} hesaplanamadı: {}", month, e.toString(), e);
            finish(row, ExecutiveSummaryReport.FAILED, 0, 0, "Özet hesaplanamadı: " + e.getClass().getSimpleName(), null, null);
            return new Result(ExecutiveSummaryReport.FAILED, month.toString(), 0, 0, "summary");
        }
        Recipients rc = recipients();
        String subject = ExecutiveSummaryMail.subject(summary);
        if (rc.isEmpty()) {
            String why = "SKIPPED: alıcı yok" + (rc.droppedInactive() > 0 ? " (" + rc.droppedInactive() + " pasif kullanıcı adresi düştü)" : "");
            trace("—", subject, null, why, TRIGGER);
            finish(row, ExecutiveSummaryReport.NO_RECIPIENT, 0, 0, why, subject, summary);
            log.warn("Aylık yönetici özeti {}: alıcı yok ({} ve global yönetici seçeneği) — gönderilmedi", month,
                    ExecutiveSummarySettings.RECIPIENTS_KEY);
            return new Result(ExecutiveSummaryReport.NO_RECIPIENT, month.toString(), 0, 0, why);
        }
        byte[] pdf = ExecutiveSummaryPdfWriter.render(summary);
        String fileName = ExecutiveSummaryPdfWriter.fileName(month.toString());
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
            trace("BCC×" + chunk.size() + ": " + String.join(", ", chunk), subject, chunks == 1 ? mail.html() : null, st, TRIGGER);
            statuses.merge(statusKey(st), chunk.size(), Integer::sum);
        }
        String detail = detail(statuses) + (pdf.length > 0 ? "" : " · PDF eki üretilemedi");
        String finalStatus = finalStatus(statuses);
        finish(row, finalStatus, to.size(), chunks, detail, subject, summary);
        log.info("Aylık yönetici özeti {} ({}): {} alıcı, {} dilim → {}", month, trigger, to.size(), chunks, detail);
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
        if (total == 0) return ExecutiveSummaryReport.FAILED;
        if (ok == total) return ExecutiveSummaryReport.SENT;
        if (ok > 0) return ExecutiveSummaryReport.PARTIAL;
        if (statuses.getOrDefault("SKIPPED_DISABLED", 0) == total) return ExecutiveSummaryReport.SKIPPED_MAIL_OFF;
        return ExecutiveSummaryReport.FAILED;
    }

    private static String detail(Map<String, Integer> statuses) {
        StringBuilder sb = new StringBuilder();
        statuses.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(k).append(" ×").append(v));
        return sb.toString();
    }

    /**
     * Ay kaydını talep eder: yoksa INSERT (UNIQUE yıl×ay — yarışı kaybeden düşer), varsa koşullu UPDATE. Zamanlanmış yol
     * yalnız yeniden denenebilir durumları alır; elle yol SENDING dışında her durumu alır. Talep edilemezse null.
     */
    ExecutiveSummaryReport claim(YearMonth month, String trigger, String actor, boolean force) {
        String now = UTC_ISO.format(summaryService.now());
        ExecutiveSummaryReport row = reportRepo.findByReportYearAndReportMonth(month.getYear(), month.getMonthValue()).orElse(null);
        if (row == null) {
            ExecutiveSummaryReport r = new ExecutiveSummaryReport();
            r.setReportYear(month.getYear());
            r.setReportMonth(month.getMonthValue());
            r.setStatus(ExecutiveSummaryReport.SENDING);
            r.setAttempts(1);
            r.setTriggerKind(trigger);
            r.setActor(actor);
            r.setClaimedAt(now);
            r.setCreatedAt(now);
            try {
                return reportRepo.saveAndFlush(r);
            } catch (DataIntegrityViolationException e) {
                log.info("Aylık yönetici özeti {}: kayıt başka pod tarafından talep edildi", month);
                return null;
            }
        }
        String status = row.getStatus();
        if (ExecutiveSummaryReport.SENDING.equals(status)) return null;
        if (!force && (!RETRYABLE.contains(status) || attempts(row) >= MAX_ATTEMPTS)) return null;
        int prevAttempts = attempts(row);
        if (row.getAttempts() == null) {
            // Eski/boş deneme sayısı: koşullu UPDATE'in eşitliği null'da çalışmaz → önce sıfırla.
            row.setAttempts(0);
            reportRepo.saveAndFlush(row);
        }
        int n = reportRepo.reclaim(row.getId(), status, prevAttempts, prevAttempts + 1, now, trigger, actor);
        if (n != 1) return null;
        row.setStatus(ExecutiveSummaryReport.SENDING);
        row.setAttempts(prevAttempts + 1);
        row.setClaimedAt(now);
        row.setTriggerKind(trigger);
        row.setActor(actor);
        return row;
    }

    private void finish(ExecutiveSummaryReport row, String status, int recipients, int chunks, String detail,
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
            reportRepo.save(row);
        } catch (Exception e) {
            log.warn("Aylık yönetici özeti kaydı yazılamadı: {}", e.toString());
        }
    }

    /** Kapalıyken zamanlanmış koşu: ayın kaydı yoksa SKIPPED_DISABLED (iz). */
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

    /** Test postası: yalnız {@code email}'e, ay kaydı YAZILMAZ; iz {@code EXECUTIVE_SUMMARY_TEST}. */
    public TestResult sendTest(YearMonth month, String email) {
        ExecutiveSummary summary = summaryService.compute(month);
        byte[] pdf = ExecutiveSummaryPdfWriter.render(summary);
        String fileName = ExecutiveSummaryPdfWriter.fileName(month.toString());
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
        trace(email, subject, mail.html(), st, TRIGGER_TEST);
        String key = statusKey(st);
        return new TestResult("SENT".equals(key) || "QUEUED_RETRY".equals(key), st, email);
    }

    // ── Geçmiş ──────────────────────────────────────────────────────────────────────────────────────────────────────

    public List<Map<String, Object>> history(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExecutiveSummaryReport r : reportRepo.findAllByOrderByReportYearDescReportMonthDesc(
                PageRequest.of(0, Math.max(1, Math.min(limit, 36))))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", r.getReportYear() == null || r.getReportMonth() == null ? null
                    : YearMonth.of(r.getReportYear(), r.getReportMonth()).toString());
            m.put("status", r.getStatus());
            m.put("summary_status", r.getSummaryStatus());
            m.put("trigger", r.getTriggerKind());
            m.put("attempts", r.getAttempts());
            m.put("recipients", r.getRecipientCount());
            m.put("chunks", r.getChunkCount());
            m.put("detail", r.getDetail());
            m.put("sent_at", r.getSentAt());
            out.add(m);
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

    // ── İz ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    private void trace(String recipients, String subject, String html, String status, String trigger) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);                       // rapor postasının alarmı yok (diğer raporlarla aynı nöbetçi)
            n.setRecipientEmail(clip(recipients, 250));
            n.setRecipientName("Yönetici Özeti");
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
