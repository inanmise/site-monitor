package com.sitemonitor.service;

import com.sitemonitor.model.LoginIssueMailLog;
import com.sitemonitor.repository.LoginIssueMailLogRepository;
import com.sitemonitor.service.EmailNotificationService.InlineImage;
import com.sitemonitor.service.EmailNotificationService.LoginIssueMailResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Login "sorun bildir" maillerinin ASYNC gönderimi + kalıcı geçmişi. Üç mail türü:
 * REPORT_ADMIN (sistem yöneticisine bildirim), REPORTER_ACK (bildirene onay), RESOLVED (çözüldü).
 *
 * <p>Neden ayrı servis: (1) gönderim {@code loginIssueMailExecutor}'da async — public request thread'ini
 * bloklamaz, cert-check havuzunu çalmaz; (2) her gönderim {@link LoginIssueMailLog} olarak yazılır →
 * admin ekranında "kime/ne zaman/hangi tür/durum" geçmişi. Mail best-effort; DB kaydı (rapor) birincil.
 *
 * <p>Mute'a rağmen gönderim: {@code site.monitor.login-issues.force-email} (varsayılan AÇIK) true ise
 * mailler SMTP mute'unu ({@code SmtpSettings.enabled=false}) aşar (operasyonel bildirim). SMTP config
 * eksikse gönderim SKIP değil FAILED olur — bu da geçmişte görünür.
 *
 * <p>@Async proxy'nin devreye girmesi için bu metodlar DIŞ bean'den (controller) çağrılır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginIssueMailService {

    public static final String REPORT_ADMIN = "REPORT_ADMIN";
    public static final String REPORTER_ACK = "REPORTER_ACK";
    public static final String RESOLVED = "RESOLVED";
    /** Uygulama içi çökme (ErrorBoundary) otomatik bildirimi — admin'e. */
    public static final String CLIENT_ERROR_ADMIN = "CLIENT_ERROR_ADMIN";
    /** Kullanıcı-tetiklemeli sorun bildirimi — admin'e. */
    public static final String USER_REPORT_ADMIN = "USER_REPORT_ADMIN";
    /** Günlük özet — admin'e (daily-digest açıkken). */
    public static final String DIGEST = "DIGEST";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final EmailNotificationService emailService;
    private final LoginIssueMailLogRepository mailLogRepo;
    private final AppSettingsService appSettings;

    private boolean forceEmail() {
        return appSettings.getBoolean("site.monitor.login-issues.force-email", true);
    }

    /** Sistem yöneticisine bildirim (public "sorun bildir" akışı). {@code reporterEmail} → mailde "E-posta" satırı. */
    @Async("loginIssueMailExecutor")
    public void dispatchReport(Long reportId, String refCode, String adminTo, String reporterEmail, String username, String errorText,
                               String message, List<InlineImage> images, String clientIp, String userAgent, String reportedAt) {
        boolean force = forceEmail();
        LoginIssueMailResult res = send(() -> emailService.sendLoginIssueReport(
                adminTo, refCode, username, reporterEmail, errorText, message, images, clientIp, userAgent, reportedAt, force));
        log.info("Login sorun bildirimi {} admin maili → {} ({})", refCode, adminTo, res.status());
        saveLog(reportId, refCode, REPORT_ADMIN, adminTo, null, res, force);
    }

    /** Uygulama içi çökme (ErrorBoundary) otomatik bildirimi — sistem yöneticisine. Ack maili yoktur
     *  (bildirimi kullanıcı değil uygulama gönderir); kayıt aynı sorun-bildirimi ekranına düşer. */
    @Async("loginIssueMailExecutor")
    public void dispatchClientError(Long reportId, String refCode, String adminTo, String username,
                                    String errorText, String message, String clientIp, String userAgent, String reportedAt) {
        boolean force = forceEmail();
        LoginIssueMailResult res = send(() -> emailService.sendClientErrorReport(
                adminTo, refCode, username, errorText, message, clientIp, userAgent, reportedAt, force));
        log.info("Uygulama hatası bildirimi {} admin maili → {} ({})", refCode, adminTo, res.status());
        saveLog(reportId, refCode, CLIENT_ERROR_ADMIN, adminTo, null, res, force);
    }

    /** Kullanıcı-tetiklemeli sorun bildirimi (USER_REPORT) — sistem yöneticisine. */
    @Async("loginIssueMailExecutor")
    public void dispatchUserReport(Long reportId, String refCode, String adminTo, String username, String reporterEmail,
                                   String category, String message, String errorText, String linkedReference,
                                   String tabKey, String appVersion, List<InlineImage> images,
                                   String clientIp, String userAgent, String reportedAt) {
        boolean force = forceEmail();
        LoginIssueMailResult res = send(() -> emailService.sendUserIssueReport(
                adminTo, refCode, username, reporterEmail, category, message, errorText, linkedReference,
                tabKey, appVersion, images, clientIp, userAgent, reportedAt, force));
        log.info("Sorun bildirimi {} admin maili → {} ({})", refCode, adminTo, res.status());
        saveLog(reportId, refCode, USER_REPORT_ADMIN, adminTo, null, res, force);
    }

    /** Günlük özet — digest cron'undan çağrılır (SchedulerService); tekil rapor kaydına bağlı değildir. */
    @Async("loginIssueMailExecutor")
    public void dispatchDigest(String adminTo, List<java.util.Map<String, String>> items, String periodLabel) {
        boolean force = forceEmail();
        LoginIssueMailResult res = send(() -> emailService.sendIssueDigest(adminTo, items, periodLabel, force));
        log.info("Sorun bildirimleri günlük özeti → {} ({} kayıt, {})", adminTo, items.size(), res.status());
        // reportId=0 sentinel: digest tek kayda bağlı değil; kolon NOT NULL (soft-FK, kısıt yok).
        saveLog(0L, "DIGEST", DIGEST, adminTo, null, res, force);
    }

    /** Bildirene "alındı" onayı. */
    @Async("loginIssueMailExecutor")
    public void dispatchAck(Long reportId, String refCode, String reporterTo, String username, String errorText,
                            String message, List<InlineImage> images, String reportedAt) {
        boolean force = forceEmail();
        LoginIssueMailResult res = send(() -> emailService.sendLoginIssueAck(
                reporterTo, refCode, username, errorText, message, images, reportedAt, force));
        saveLog(reportId, refCode, REPORTER_ACK, reporterTo, null, res, force);
    }

    /** "Çözüldü" bildirimi — bildiren (To) + sistem yöneticisi (CC). Zenginleştirilmiş içerik:
     *  bildirim zamanı + orijinal sorun (hata + açıklama) + ekran görüntüleri + çözüm notu. */
    @Async("loginIssueMailExecutor")
    public void dispatchResolved(Long reportId, String refCode, String reporterEmail, String adminEmail,
                                 String username, String errorText, String message, String reportedAt,
                                 String resolutionNote, String resolvedAt, List<InlineImage> images) {
        boolean force = forceEmail();
        LoginIssueMailResult res = send(() -> emailService.sendLoginIssueResolved(
                reporterEmail, adminEmail, refCode, username, errorText, message, reportedAt,
                resolutionNote, resolvedAt, images, force));
        // Alıcı düzenini gönderim mantığıyla aynen logla (bildiren yoksa admin To olur).
        boolean hasReporter = reporterEmail != null && !reporterEmail.isBlank();
        boolean hasAdmin = adminEmail != null && !adminEmail.isBlank();
        String to = hasReporter ? reporterEmail : (hasAdmin ? adminEmail : null);
        String cc = (hasReporter && hasAdmin) ? adminEmail : null;
        log.info("Login issue {} çözüldü maili → to={} cc={} ({})", refCode, to, cc, res.status());
        saveLog(reportId, refCode, RESOLVED, to, cc, res, force);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private interface Send { LoginIssueMailResult run(); }

    private LoginIssueMailResult send(Send s) {
        try {
            return s.run();
        } catch (Exception e) {
            return new LoginIssueMailResult("FAILED: " + e.getMessage(), emailService.senderAddress(), null, null);
        }
    }

    private void saveLog(Long reportId, String refCode, String mailType, String to, String cc,
                         LoginIssueMailResult res, boolean forced) {
        try {
            String status = res != null ? res.status() : null;
            LoginIssueMailLog m = new LoginIssueMailLog();
            m.setReportId(reportId);
            m.setRefCode(refCode);
            m.setMailType(mailType);
            m.setRecipientTo(trimTo(to, 255));
            m.setCc(cc);
            boolean failed = status != null && status.startsWith("FAILED");
            m.setStatus(trimTo(status, 100));
            m.setErrorMessage(failed ? status : null);
            m.setEmailFrom(res != null ? trimTo(res.from(), 255) : null);
            m.setSubject(res != null ? res.subject() : null);
            m.setBodyHtml(res != null ? res.bodyHtml() : null);
            m.setForced(forced);
            m.setSentAt(ISO.format(Instant.now()));
            mailLogRepo.save(m);
        } catch (Exception e) {
            log.warn("Login issue {} mail log yazılamadı: {}", refCode, e.getMessage());
        }
    }

    private static String trimTo(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
