package com.certmonitor.service;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationService {

    // Outbound mail is driven by the DB-backed SMTP settings (admin Settings page).
    // When nothing is saved yet, SmtpSettingsService falls back to env spring.mail.*
    // so behaviour is unchanged until the admin saves on the screen.
    private final SmtpSettingsService smtpSettings;
    private final SmtpMailService smtpMailService;

    /**
     * Tek thread'lik scheduler — SMTP 421 retry'ları için. Caller thread
     * (sweep executor ya da HTTP request) 90 saniye block etmesin.
     */
    private final ScheduledExecutorService mailRetryExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mail-retry");
                t.setDaemon(true);
                return t;
            });

    @PreDestroy
    void shutdownRetryExecutor() {
        mailRetryExecutor.shutdown();
        try {
            // Graceful shutdown: bekleyen 421-retry görevlerine kısa süre tanı
            if (!mailRetryExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                mailRetryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mailRetryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Current SMTP settings (DB-backed; SmtpSettingsService falls back to env) ──
    private boolean isEnabled() {
        return Boolean.TRUE.equals(smtpSettings.getOrDefaults().getEnabled());
    }
    private String currentFrom() {
        String f = smtpSettings.getOrDefaults().getFromAddress();
        return (f != null && !f.isBlank()) ? f : "noreply@certmonitor";
    }
    private String currentFromName() {
        return smtpSettings.getOrDefaults().getFromName();
    }
    private long retry() {
        Integer r = smtpSettings.getOrDefaults().getRetryDelayMs();
        return r != null ? r.longValue() : 90000L;
    }
    private org.springframework.mail.javamail.JavaMailSenderImpl currentSender() {
        return smtpMailService.currentSender();
    }
    private void applyFrom(MimeMessageHelper helper) throws Exception {
        String name = currentFromName();
        if (name != null && !name.isBlank()) helper.setFrom(currentFrom(), name);
        else helper.setFrom(currentFrom());
    }

    public String getEmailFrom() { return currentFrom(); }

    public String sendAlert(String to, String subject, String message) {
        return sendAlert(to, subject, message, null, null, null, null, null);
    }

    public String sendAlert(String to, String subject, String message,
                            String domain, String level, String alertType,
                            Integer daysRemaining, Map<String, Object> certContext) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — TO={} | KONU={}", to, subject);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            applyFrom(helper);
            helper.setSubject(subject);
            String html = buildAlertEmailHtml(subject, message, domain, level, alertType, daysRemaining, certContext);
            helper.setText(html, true);
            return doSend(to, msg, 1);
        } catch (Exception e) {
            log.error("✗ E-posta hazırlanamadı: TO={} | HATA={}", to, e.getMessage());
            return "FAILED: " + e.getMessage();
        }
    }

    public String sendAlert(String[] toAddresses, String subject, String message,
                            String domain, String level, String alertType,
                            Integer daysRemaining, Map<String, Object> certContext) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — TO={} | KONU={}", Arrays.toString(toAddresses), subject);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(toAddresses);
            applyFrom(helper);
            helper.setSubject(subject);
            helper.setText(buildAlertEmailHtml(subject, message, domain, level,
                    alertType, daysRemaining, certContext), true);
            return doSend(Arrays.toString(toAddresses), msg, 1);
        } catch (Exception e) {
            log.error("✗ E-posta hazırlanamadı: TO={} | HATA={}", Arrays.toString(toAddresses), e.getMessage());
            return "FAILED: " + e.getMessage();
        }
    }

    public String sendResolutionAlert(String[] toAddresses, String subject,
                                      String domain, String alertType, String alertLevel,
                                      Integer daysRemaining, String resolvedBy, String resolvedAt,
                                      String createdAt, Map<String, Object> certContext) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — çözüm bildirimi: TO={}", Arrays.toString(toAddresses));
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(toAddresses);
            applyFrom(helper);
            helper.setSubject(subject);
            helper.setText(buildResolutionEmailHtml(domain, alertType, alertLevel,
                    daysRemaining, resolvedBy, resolvedAt, createdAt, certContext), true);
            return doSend(Arrays.toString(toAddresses), msg, 1);
        } catch (Exception e) {
            log.error("✗ Çözüm e-postası hazırlanamadı: TO={} | HATA={}", Arrays.toString(toAddresses), e.getMessage());
            return "FAILED: " + e.getMessage();
        }
    }

    private String doSend(String to, MimeMessage msg, int attempt) {
        try {
            currentSender().send(msg);
            if (attempt == 1) {
                log.info("✓ E-posta gönderildi: TO={}", to);
            } else {
                log.info("✓ E-posta gönderildi (retry #{}): TO={}", attempt - 1, to);
            }
            return "SENT";
        } catch (Exception e) {
            String err = e.getMessage() != null ? e.getMessage() : "";
            // 421 = transient rate-limit from SMTP gateway — birden çok kez, artan
            // bekleme (exponential backoff) ile async retry. Check both getMessage()
            // and toString() because MailSendException may wrap the inner cause.
            String errFull = err + " " + e.toString();
            if (errFull.contains("421") && attempt < MAX_SEND_ATTEMPTS) {
                // backoff: retryDelay × 2^(attempt-1) → base, 2×, 4× …
                long delay = retry() * (1L << (attempt - 1));
                log.warn("⏳ SMTP 421 rate limit (deneme {}/{}) — {}ms sonra async retry: TO={}",
                        attempt, MAX_SEND_ATTEMPTS, delay, to);
                // Caller'ı bloke etme; retry'ı ayrı thread'de tetikle.
                mailRetryExecutor.schedule(
                    () -> {
                        try { doSend(to, msg, attempt + 1); }
                        catch (Exception ex) {
                            log.error("✗ Async retry başarısız: TO={} | HATA={}", to, ex.getMessage());
                        }
                    },
                    delay, TimeUnit.MILLISECONDS);
                // İlk denemenin sonucu caller'a döner (sonraki retry'lar async, sonucu yutulur).
                return attempt == 1 ? "QUEUED_RETRY: " + err : "QUEUED_RETRY";
            }
            if (errFull.contains("421")) {
                log.error("✗ E-posta {} denemede de 421 rate limit ile gönderilemedi: TO={}", MAX_SEND_ATTEMPTS, to);
            }
            log.error("✗ E-posta gönderilemedi: TO={} | HATA={}", to, err);
            return "FAILED: " + err;
        }
    }

    /** 421 rate-limit için toplam deneme sayısı (1 ilk + 3 retry); her retry artan beklemeli. */
    private static final int MAX_SEND_ATTEMPTS = 4;

    /** Rich resolution email with full context (manual or auto resolve). */
    public String sendResolutionAlert(String to, String subject,
                                      String domain, String alertType, String alertLevel,
                                      Integer daysRemaining, String resolvedBy, String resolvedAt,
                                      String createdAt, Map<String, Object> certContext) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — çözüm bildirimi: TO={}", to);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            applyFrom(helper);
            helper.setSubject(subject);
            helper.setText(buildResolutionEmailHtml(domain, alertType, alertLevel,
                    daysRemaining, resolvedBy, resolvedAt, createdAt, certContext), true);
            return doSend(to, msg, 1);
        } catch (Exception e) {
            log.error("✗ Çözüm e-postası hazırlanamadı: TO={} | HATA={}", to, e.getMessage());
            return "FAILED: " + e.getMessage();
        }
    }

    // ── Password reset — admin auto-reset flow ──────────────────────────────

    /**
     * Sends a one-time temporary password to a user whose account was
     * auto-reset by an admin. The plaintext temp password is ONLY ever
     * present in this email body — it is never logged.
     */
    public String sendPasswordResetEmail(String toAddress, String username,
                                          String displayName, String tempPassword) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — şifre sıfırlama: TO={}", toAddress);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(toAddress);
            applyFrom(helper);
            helper.setSubject("[CertMonitor] Şifreniz sıfırlandı — lütfen güncelleyin");
            helper.setText(buildPasswordResetHtml(username, displayName, tempPassword), true);
            return doSend(toAddress, msg, 1);
        } catch (Exception e) {
            log.error("✗ Şifre sıfırlama e-postası hazırlanamadı: TO={} | HATA={}", toAddress, e.getMessage());
            return "FAILED: " + e.getMessage();
        }
    }

    private String buildPasswordResetHtml(String username, String displayName, String tempPwd) {
        String name = (displayName != null && !displayName.isBlank()) ? displayName : username;
        return """
            <div style="font-family:Arial,sans-serif;max-width:560px;margin:0 auto;padding:20px;color:#1e293b">
              <h2 style="color:#4f46e5;margin-top:0">Şifreniz sıfırlandı</h2>
              <p>Sayın <strong>%s</strong>,</p>
              <p>CertMonitor hesabınızın şifresi bir yönetici tarafından sıfırlandı.</p>
              <table style="border-collapse:collapse;margin:14px 0;font-size:.95em">
                <tr>
                  <td style="padding:4px 12px 4px 0;color:#64748b">Kullanıcı adı:</td>
                  <td style="padding:4px 0;font-family:ui-monospace,Consolas,monospace;font-weight:600">%s</td>
                </tr>
                <tr>
                  <td style="padding:4px 12px 4px 0;color:#64748b;vertical-align:top">Geçici şifre:</td>
                  <td style="padding:4px 0;font-family:ui-monospace,Consolas,monospace;font-weight:700;letter-spacing:.04em;font-size:1.1em">%s</td>
                </tr>
              </table>
              <p><strong>Bu şifre 24 saat geçerlidir.</strong> Bu süre içinde giriş yapmazsanız geçici şifreniz devre dışı kalır ve yeni bir sıfırlama talep etmeniz gerekir.</p>
              <p>İlk girişinizde sistem sizden kalıcı bir şifre belirlemenizi isteyecektir.</p>
              <p style="font-size:.9em;color:#64748b">Bu işlemi siz başlatmadıysanız lütfen sistem yöneticinizle iletişime geçin.</p>
            </div>
            """.formatted(name, username, tempPwd);
    }

    // ── System admin — network outage notifications ──────────────────────────

    public String sendSystemAdminNetworkAlert(String to, String detectedAt,
                                              int networkErrors, int total,
                                              double errorRate, double threshold) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — admin network alert: TO={}", to);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            applyFrom(helper);
            helper.setSubject("[CertMonitor] ⚠ Ağ Erişim Sorunu Tespit Edildi");
            helper.setText(buildAdminNetworkAlertHtml(detectedAt, networkErrors, total,
                    errorRate, threshold), true);
            return doSend(to, msg, 1);
        } catch (Exception e) {
            log.error("✗ Admin network alert hazırlanamadı: TO={} | HATA={}", to, e.getMessage());
            return "FAILED: " + e.getMessage();
        }
    }

    public String sendSystemAdminNetworkResolved(String to, String detectedAt, String resolvedAt,
                                                 long durationMs, int networkErrors, int total,
                                                 double errorRate) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — admin network resolved: TO={}", to);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            applyFrom(helper);
            helper.setSubject("[CertMonitor] ✅ Ağ Erişim Sorunu Çözüldü");
            helper.setText(buildAdminNetworkResolvedHtml(detectedAt, resolvedAt, durationMs,
                    networkErrors, total, errorRate), true);
            return doSend(to, msg, 1);
        } catch (Exception e) {
            log.error("✗ Admin network resolved hazırlanamadı: TO={} | HATA={}", to, e.getMessage());
            return "FAILED: " + e.getMessage();
        }
    }

    private String buildAdminNetworkAlertHtml(String detectedAt, int networkErrors, int total,
                                              double errorRate, double threshold) {
        String ratePct = String.format("%.0f%%", errorRate * 100);
        String threshPct = String.format("%.0f%%", threshold * 100);
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body style='font-family:Segoe UI,Arial,sans-serif;color:#1f2937;'>" +
                "<div style='max-width:640px;margin:0 auto;padding:24px;background:#fff;'>" +
                "<h2 style='color:#b91c1c;margin:0 0 12px;'>⚠ CertMonitor — Ağ Erişim Sorunu Tespit Edildi</h2>" +
                "<p style='font-size:.95em;line-height:1.55;'>CertMonitor host'unun bir veya daha fazla sertifika kontrolünü tamamlayamadığı tespit edildi. " +
                "Tarama turunda <strong>" + networkErrors + " / " + total + "</strong> domain ağ-class hatasıyla düştü " +
                "(oran: <strong>" + ratePct + "</strong>, eşik: " + threshPct + "). " +
                "Bu, host'un outbound bağlantısında bir problem olabileceğini gösteriyor.</p>" +
                "<table style='width:100%;border-collapse:collapse;margin:16px 0;font-size:.92em;'>" +
                "<tr><th style='text-align:left;padding:6px 10px;background:#f3f4f6;border:1px solid #e5e7eb;'>Tespit Zamanı</th>" +
                "<td style='padding:6px 10px;border:1px solid #e5e7eb;'>" + formatIso(detectedAt) + "</td></tr>" +
                "<tr><th style='text-align:left;padding:6px 10px;background:#f3f4f6;border:1px solid #e5e7eb;'>Etkilenen Domain</th>" +
                "<td style='padding:6px 10px;border:1px solid #e5e7eb;'>" + networkErrors + " / " + total + "</td></tr>" +
                "<tr><th style='text-align:left;padding:6px 10px;background:#f3f4f6;border:1px solid #e5e7eb;'>Hata Oranı</th>" +
                "<td style='padding:6px 10px;border:1px solid #e5e7eb;'>" + ratePct + "</td></tr>" +
                "<tr><th style='text-align:left;padding:6px 10px;background:#f3f4f6;border:1px solid #e5e7eb;'>Eşik</th>" +
                "<td style='padding:6px 10px;border:1px solid #e5e7eb;'>" + threshPct + "</td></tr>" +
                "</table>" +
                "<h3 style='color:#374151;font-size:1em;margin:20px 0 8px;'>Sistemin Aksiyonu</h3>" +
                "<ul style='font-size:.9em;line-height:1.6;'>" +
                "<li>Yeni alarm üretimi <strong>geçici olarak duraklatıldı</strong></li>" +
                "<li>Auto-resolve işlemi <strong>askıya alındı</strong> (sahte resolved e-posta yağmuru engellenir)</li>" +
                "<li>Dashboard'da operatörlere uyarı banner'ı gösterildi</li>" +
                "</ul>" +
                "<h3 style='color:#374151;font-size:1em;margin:20px 0 8px;'>Önerilen Kontroller</h3>" +
                "<ul style='font-size:.9em;line-height:1.6;'>" +
                "<li>Host'un internet bağlantısı (modem/router)</li>" +
                "<li>Outbound proxy ayarları</li>" +
                "<li>Kurumsal firewall/NAT politikaları</li>" +
                "<li>DNS sunucu erişilebilirliği</li>" +
                "</ul>" +
                "<p style='font-size:.88em;color:#6b7280;margin-top:24px;'>Ağ erişimi normale döner dönmez ayrıca bir <strong>\"Çözüldü\"</strong> e-postası alacaksınız.</p>" +
                "<hr style='border:none;border-top:1px solid #e5e7eb;margin:24px 0 12px;' />" +
                "<p style='font-size:.78em;color:#9ca3af;'>CertMonitor — System Admin Notification</p>" +
                "</div></body></html>";
    }

    private String buildAdminNetworkResolvedHtml(String detectedAt, String resolvedAt, long durationMs,
                                                 int networkErrors, int total, double errorRate) {
        long durationMin = durationMs / 60000;
        long durationSec = (durationMs / 1000) % 60;
        String durationStr = durationMin + " dk " + durationSec + " sn";
        String ratePct = String.format("%.0f%%", errorRate * 100);
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body style='font-family:Segoe UI,Arial,sans-serif;color:#1f2937;'>" +
                "<div style='max-width:640px;margin:0 auto;padding:24px;background:#fff;'>" +
                "<h2 style='color:#15803d;margin:0 0 12px;'>✅ CertMonitor — Ağ Erişim Sorunu Çözüldü</h2>" +
                "<p style='font-size:.95em;line-height:1.55;'>CertMonitor host'unun outbound bağlantı sorunu çözüldü. " +
                "Sertifika kontrolleri normal işleyişe döndü.</p>" +
                "<table style='width:100%;border-collapse:collapse;margin:16px 0;font-size:.92em;'>" +
                "<tr><th style='text-align:left;padding:6px 10px;background:#f3f4f6;border:1px solid #e5e7eb;'>Tespit Zamanı</th>" +
                "<td style='padding:6px 10px;border:1px solid #e5e7eb;'>" + formatIso(detectedAt) + "</td></tr>" +
                "<tr><th style='text-align:left;padding:6px 10px;background:#f3f4f6;border:1px solid #e5e7eb;'>Çözüm Zamanı</th>" +
                "<td style='padding:6px 10px;border:1px solid #e5e7eb;'>" + formatIso(resolvedAt) + "</td></tr>" +
                "<tr><th style='text-align:left;padding:6px 10px;background:#f3f4f6;border:1px solid #e5e7eb;'>Toplam Süre</th>" +
                "<td style='padding:6px 10px;border:1px solid #e5e7eb;'>" + durationStr + "</td></tr>" +
                "<tr><th style='text-align:left;padding:6px 10px;background:#f3f4f6;border:1px solid #e5e7eb;'>Tespit Anında Etkilenen</th>" +
                "<td style='padding:6px 10px;border:1px solid #e5e7eb;'>" + networkErrors + " / " + total + " (" + ratePct + ")</td></tr>" +
                "</table>" +
                "<h3 style='color:#374151;font-size:1em;margin:20px 0 8px;'>Sistemin Aksiyonu</h3>" +
                "<ul style='font-size:.9em;line-height:1.6;'>" +
                "<li>Yeni alarm üretimi <strong>yeniden aktif</strong></li>" +
                "<li>Auto-resolve işlemi <strong>yeniden aktif</strong></li>" +
                "<li>Dashboard uyarı banner'ı kaldırıldı</li>" +
                "</ul>" +
                "<p style='font-size:.88em;color:#15803d;background:#f0fdf4;border:1px solid #bbf7d0;padding:10px 14px;border-radius:6px;'>" +
                "<em>Not: Outage süresince üretilebilecek sahte alarmlar bastırıldığı için ekibinize ÇÖZÜLDÜ e-posta yağmuru gönderilmedi.</em></p>" +
                "<hr style='border:none;border-top:1px solid #e5e7eb;margin:24px 0 12px;' />" +
                "<p style='font-size:.78em;color:#9ca3af;'>CertMonitor — System Admin Notification</p>" +
                "</div></body></html>";
    }

    // ── Public HTML accessors (used to store sent HTML in notification log) ──

    private static final java.util.Set<String> MONITORING_OUTAGE_TYPES =
            java.util.Set.of("ACCESSIBILITY", "PORT_DOWN", "DNS_FAILURE");

    public String buildAlertEmailHtml(String subject, String message,
                                       String domain, String level, String alertType,
                                       Integer daysRemaining, Map<String, Object> certContext) {
        if (alertType != null && MONITORING_OUTAGE_TYPES.contains(alertType)) {
            return buildRichMonitoringOutageAlertHtml(message, domain, alertType, certContext);
        }
        if ("DNS_CHANGED".equals(alertType)) {
            return buildRichDnsChangedAlertHtml(message, domain, certContext);
        }
        return (domain != null)
                ? buildRichAlertHtml(subject, message, domain, level, alertType, daysRemaining, certContext)
                : buildSimpleAlertHtml(subject, message);
    }

    public String buildResolutionEmailHtml(String domain, String alertType, String alertLevel,
                                            Integer daysRemaining, String resolvedBy,
                                            String resolvedAt, String createdAt,
                                            Map<String, Object> certContext) {
        if ((alertType != null && MONITORING_OUTAGE_TYPES.contains(alertType))
                || "DNS_CHANGED".equals(alertType)) {
            return buildRichMonitoringResolvedHtml(domain, alertType, resolvedBy, resolvedAt, createdAt);
        }
        return buildRichResolvedHtml(domain, alertType, alertLevel,
                daysRemaining, resolvedBy, resolvedAt, createdAt, certContext);
    }

    // ── HTML builders ────────────────────────────────────────────────────────

    private String buildRichAlertHtml(String subject, String message,
                                       String domain, String level, String alertType,
                                       Integer daysRemaining, Map<String, Object> certContext) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

        String accentColor = switch (level != null ? level : "") {
            case "CRITICAL" -> "#dc2626";
            case "HIGH"     -> "#ea580c";
            default         -> "#d97706";
        };
        String levelTr = switch (level != null ? level : "") {
            case "CRITICAL" -> "KRİTİK";
            case "HIGH"     -> "YÜKSEK";
            default         -> "UYARI";
        };
        String typeTr = switch (alertType != null ? alertType : "") {
            case "REVOKED"      -> "İptal Edildi";
            case "MISMATCH"     -> "Dağıtım Eksik";
            case "CHAIN_BROKEN" -> "Zincir Sorunu";
            default             -> "Son Kullanma Tarihi";
        };
        String typeIcon = switch (alertType != null ? alertType : "") {
            case "REVOKED"      -> "🚫";
            case "MISMATCH"     -> "⚡";
            case "CHAIN_BROKEN" -> "🔗";
            default             -> "⏰";
        };

        String notAfter    = ctxStr(certContext, "not_after");
        String notBefore   = ctxStr(certContext, "not_before");
        String issuerCn    = ctxStr(certContext, "issuer_cn");
        String issuerOrg   = ctxStr(certContext, "issuer");
        String certSubject = ctxStr(certContext, "subject");
        String checkedAt   = ctxStr(certContext, "checked_at");
        String fingerprint = ctxStr(certContext, "fingerprint");

        String issuerDisplay    = !issuerCn.isEmpty() ? issuerCn : (!issuerOrg.isEmpty() ? issuerOrg : "—");
        String validFromDisplay = formatIso(notBefore);
        String checkedAtDisplay = formatIso(checkedAt);

        // ── Expiry hero ──
        String expiryHero = "";
        if ("EXPIRY".equals(alertType) && daysRemaining != null) {
            String dc = daysRemaining <= 7 ? "#dc2626" : daysRemaining <= 15 ? "#ea580c" : "#d97706";
            String urgencyMsg = daysRemaining <= 7  ? "⚠ Acil önlem alınmalı!"
                              : daysRemaining <= 15 ? "En kısa sürede yenilenmeli"
                              : "Yenileme planlanmalı";

            expiryHero = "<table width='100%' cellpadding='0' cellspacing='0' border='0'"
                + " style='margin:20px 0;border-radius:14px;overflow:hidden;border:2px solid " + dc + "22'>"
                + "<tr>"
                + "<td class='em-hero-l' align='center' valign='middle' width='38%'"
                + " style='background:" + dc + ";padding:22px 14px'>"
                + "<div style='color:#fff;font-size:64px;font-weight:900;line-height:1;letter-spacing:-2px'>" + daysRemaining + "</div>"
                + "<div style='color:#fff;font-size:14px;font-weight:800;margin-top:4px;letter-spacing:.06em'>GÜN KALDI</div>"
                + "<div style='color:rgba(255,255,255,.85);font-size:12px;margin-top:8px;padding:0 6px'>" + urgencyMsg + "</div>"
                + "</td>"
                + "<td class='em-hero-r' valign='middle' style='background:" + dc + "0d;padding:20px 22px'>"
                + "<div style='font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8;text-transform:uppercase;margin-bottom:10px'>Son Kullanma Tarihi</div>"
                + "<div style='font-size:24px;font-weight:900;color:" + dc + ";letter-spacing:-.5px'>" + formatIso(notAfter) + "</div>"
                + "<div style='font-size:13px;color:#475569;margin-top:6px;line-height:1.6'>" + formatIsoFull(notAfter) + "</div>"
                + "<div style='margin-top:12px;padding:6px 12px;background:" + dc + ";color:#fff;"
                + "border-radius:6px;font-size:12px;font-weight:800;display:inline-block'>"
                + "📅 " + daysRemaining + " gün sonra sona eriyor</div>"
                + "</td>"
                + "</tr></table>";
        } else if (!"EXPIRY".equals(alertType)) {
            expiryHero = "<div style='text-align:center;margin:20px 0'>"
                + "<div style='display:inline-block;background:" + accentColor + ";color:#fff;"
                + "border-radius:12px;padding:14px 32px;font-size:17px;font-weight:800;letter-spacing:.02em'>"
                + typeIcon + " " + typeTr.toUpperCase() + " TESPİT EDİLDİ"
                + "</div></div>";
        }

        // ── Two-column info section ──
        String certRows = tableRow2col("🌐 Alan Adı",              escHtml(domain))
            + tableRow2col("📋 Sertifika Sahibi",    !certSubject.isEmpty() ? escHtml(certSubject) : escHtml(domain))
            + tableRow2col("🏢 Veren Kurum (CA)",    escHtml(issuerDisplay))
            + tableRow2col("📅 Geçerlilik Başlangıcı", validFromDisplay)
            + (!fingerprint.isEmpty()
                ? tableRow2colMono("🔑 Parmak İzi",
                    fingerprint.length() > 24 ? fingerprint.substring(0, 24) + "…" : fingerprint)
                : "");

        String statusRows = statusRow2col("Sertifika", certStatusLabel(level, alertType))
            + statusRow2col("İptal",   revocationLabel(certContext))
            + statusRow2col("Zincir",  chainLabel(certContext))
            + statusRow2col("Dağıtım", deployLabel(certContext));

        // Wrapper table: col-l and col-r stack to 100% on mobile via media query
        String twoColSection =
            "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px'><tr>"
            + "<td class='em-col-l' valign='top' style='width:55%;padding-right:8px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td style='background:#1e293b;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>SERTİFİKA BİLGİLERİ</td></tr>"
            + certRows + "</table></td>"
            + "<td class='em-col-r' valign='top' style='width:45%'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td style='background:#334155;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>DURUM ÖZETİ</td></tr>"
            + statusRows + "</table></td>"
            + "</tr></table>";

        // ── Mobile CSS ──
        String css = "<style>"
            + "body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + "img{border:0;height:auto;line-height:100%;outline:none;text-decoration:none}"
            + "@media only screen and (max-width:620px){"
            // Outer wrapper: remove desktop padding/margin
            + ".em-wrap{padding:0!important}"
            + ".em-card{border-radius:0!important;width:100%!important}"
            // Top bar domain font smaller on very narrow screens
            + ".em-domain{font-size:16px!important;word-break:break-all!important}"
            // Body padding tighter on mobile
            + ".em-body{padding:14px!important}"
            // Hero: stack left/right cells vertically
            + ".em-hero-l,.em-hero-r{display:block!important;width:100%!important}"
            + ".em-hero-l{border-radius:12px 12px 0 0!important}"
            + ".em-hero-r{border-radius:0 0 12px 12px!important;padding:16px!important}"
            // Info columns: stack vertically
            + ".em-col-l{display:block!important;width:100%!important;padding-right:0!important;padding-bottom:10px!important}"
            + ".em-col-r{display:block!important;width:100%!important}"
            // Footer: hide long check-time text, keep notification time
            + ".em-footer-check{display:none!important}"
            + "}"
            + "</style>";

        return "<!DOCTYPE html><html lang='tr'>"
            + "<head>"
            + "<meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + css
            + "</head>"
            + "<body style='margin:0;padding:0;background:#f1f5f9;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            // Outer centering table (fluid)
            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='background:#f1f5f9;padding:24px 10px'>"
            + "<tr><td align='center'>"

            // Card container — max 640px, collapses to full width on mobile
            + "<table class='em-card' width='640' cellpadding='0' cellspacing='0' border='0'"
            + " style='max-width:640px;width:100%;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.15)'>"
            + "<tr><td style='padding:0'>"

            // ── Top bar ──
            + "<div style='background:" + accentColor + ";padding:22px 24px'>"
            + "<div style='color:rgba(255,255,255,.65);font-size:11px;font-weight:700;letter-spacing:.12em'>CertMonitor — Sertifika İzleme</div>"
            + "<div class='em-domain' style='color:#fff;font-size:22px;font-weight:900;"
            + "margin-top:10px;word-break:break-all;line-height:1.25'>🌐 " + escHtml(domain) + "</div>"
            + "<div style='color:rgba(255,255,255,.88);font-size:15px;font-weight:700;"
            + "margin-top:8px;letter-spacing:.02em'>" + levelTr + " &nbsp;·&nbsp; " + typeTr + "</div>"
            + "</div>"

            // ── Body ──
            + "<div class='em-body' style='background:#fff;padding:22px 24px'>"
            + expiryHero
            + twoColSection

            // Alert detail
            + "<div style='background:#fffbeb;border-left:4px solid " + accentColor + ";"
            + "border-radius:0 8px 8px 0;padding:14px 18px;color:#1c1917;"
            + "font-size:14px;line-height:1.7;margin-bottom:20px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + accentColor + ";margin-bottom:6px'>ALARM DETAYI</div>"
            + escHtml(message)
            + "</div>"

            // Footer
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + (!checkedAtDisplay.equals("—")
                ? "<span class='em-footer-check'>" + "Son kontrol: " + checkedAtDisplay + " &nbsp;&middot;&nbsp;</span>" : "")
            + " Bildirim: " + generatedAt
            + "</td></tr></table>"

            + "</div>"  // em-body
            + "</td></tr></table>"  // em-card
            + "</td></tr></table>"  // em-wrap
            + "</body></html>";
    }

    private String tableRow2col(String label, String value) {
        return "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>" + label + "</td>"
            + "<td style='padding:9px 13px;font-size:14px;font-weight:600;color:#1e293b;word-break:break-all'>" + value + "</td>"
            + "</tr>";
    }

    private String tableRow2colMono(String label, String value) {
        return "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>" + label + "</td>"
            + "<td style='padding:9px 13px;font-size:11px;font-family:monospace;color:#475569;word-break:break-all'>" + value + "</td>"
            + "</tr>";
    }

    private String statusRow2col(String label, String value) {
        boolean ok      = value.startsWith("✓");
        boolean neutral = value.startsWith("~") || value.startsWith("—");
        String bg         = ok || neutral ? "" : "background:#fff7ed";
        String valueColor = ok ? "#15803d" : neutral ? "#6b7280" : "#b45309";
        return "<tr style='border-top:1px solid #e2e8f0;" + bg + "'>"
            + "<td style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>" + label + "</td>"
            + "<td style='padding:9px 13px;font-size:13px;font-weight:700;color:" + valueColor + "'>" + value + "</td>"
            + "</tr>";
    }

    private String formatIsoFull(String iso) {
        if (iso == null || iso.isBlank()) return "—";
        try {
            // UTC ISO'yu Europe/Istanbul'a çevir → kullanıcıya her zaman yerel saat
            LocalDateTime dt = LocalDateTime.parse(iso, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
                    .atZone(ZoneOffset.UTC).withZoneSameInstant(IST).toLocalDateTime();
            String[] months = {"Ocak","Şubat","Mart","Nisan","Mayıs","Haziran",
                               "Temmuz","Ağustos","Eylül","Ekim","Kasım","Aralık"};
            String[] days = {"Pazartesi","Salı","Çarşamba","Perşembe","Cuma","Cumartesi","Pazar"};
            String month   = months[dt.getMonthValue() - 1];
            String dayName = days[dt.getDayOfWeek().getValue() - 1];
            return dt.getDayOfMonth() + " " + month + " " + dt.getYear() + ", " + dayName
                 + " — " + String.format("%02d:%02d", dt.getHour(), dt.getMinute());
        } catch (Exception e) {
            return formatIso(iso);
        }
    }

    private String statusRow(String label, String value) {
        boolean ok      = value.startsWith("✓");
        boolean neutral = value.startsWith("~") || value.startsWith("—");
        String bg         = ok || neutral ? "" : "background:#fff7ed";
        String valueColor = ok ? "#15803d" : neutral ? "#6b7280" : "#b45309";
        return "<tr style='border-top:1px solid #e2e8f0;" + bg + "'>"
            + "<td style='padding:9px 14px;font-size:13px;color:#64748b;width:48%'>" + label + "</td>"
            + "<td style='padding:9px 14px;font-size:13px;font-weight:700;color:" + valueColor + "'>" + value + "</td>"
            + "</tr>";
    }

    private String infoRow(String label, String value, boolean muted) {
        String vc = muted ? "#94a3b8" : "#1e293b";
        return "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td style='padding:9px 14px;font-size:12px;color:#64748b;width:44%;white-space:nowrap'>" + label + "</td>"
            + "<td style='padding:9px 14px;font-size:13px;font-weight:600;color:" + vc + ";word-break:break-all'>" + value + "</td>"
            + "</tr>";
    }

    private String infoRowHighlight(String label, String value, String color) {
        return "<tr style='border-top:1px solid #e2e8f0;background:#fff7ed'>"
            + "<td style='padding:9px 14px;font-size:12px;color:#64748b;width:44%;white-space:nowrap'>" + label + "</td>"
            + "<td style='padding:9px 14px;font-size:14px;font-weight:800;color:" + color + "'>" + value + "</td>"
            + "</tr>";
    }

    private String infoRowMono(String label, String value) {
        return "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td style='padding:9px 14px;font-size:12px;color:#64748b;width:44%;white-space:nowrap'>" + label + "</td>"
            + "<td style='padding:9px 14px;font-size:11px;font-family:monospace;color:#475569;word-break:break-all'>" + value + "</td>"
            + "</tr>";
    }

    private String certStatusLabel(String level, String alertType) {
        if ("EXPIRY".equals(alertType)) {
            return switch (level != null ? level : "") {
                case "CRITICAL" -> "✗ Kritik — çok yakın";
                case "HIGH"     -> "⚠ Yüksek risk";
                default         -> "⚠ Uyarı — yaklaşıyor";
            };
        }
        return "✗ Sorunlu";
    }

    private String revocationLabel(Map<String, Object> ctx) {
        if (ctx == null) return "— Kontrol yapılmadı";
        String v = String.valueOf(ctx.getOrDefault("revocation_status", "UNKNOWN"));
        return switch (v) {
            case "VALID"        -> "✓ İptal edilmedi";
            case "REVOKED"      -> "✗ İPTAL EDİLDİ";
            case "UNDETERMINED" -> "~ Kontrol edilemedi (OCSP/CRL yok)";
            case "UNKNOWN"      -> "~ Kontrol edilemedi";
            default             -> "~ " + v;
        };
    }

    private String chainLabel(Map<String, Object> ctx) {
        if (ctx == null) return "— Kontrol yapılmadı";
        String v = String.valueOf(ctx.getOrDefault("chain_status", "UNKNOWN"));
        return switch (v) {
            case "VALID"   -> "✓ Zincir sağlıklı";
            case "BROKEN"  -> "✗ Zincir kırık";
            case "REVOKED" -> "✗ Zincirde iptal var";
            case "UNKNOWN" -> "~ Kontrol edilemedi";
            default        -> "~ " + v;
        };
    }

    private String deployLabel(Map<String, Object> ctx) {
        if (ctx == null) return "— Kontrol yapılmadı";
        String v = String.valueOf(ctx.getOrDefault("deployment_status", "UNKNOWN"));
        return switch (v) {
            case "OK"         -> "✓ Tamamlandı";
            case "INCOMPLETE" -> "⚠ Eksik dağıtım";
            case "UNKNOWN"    -> "~ Bilinmiyor";
            default           -> "~ " + v;
        };
    }

    private String ctxStr(Map<String, Object> ctx, String key) {
        if (ctx == null) return "";
        Object v = ctx.get(key);
        return v != null ? String.valueOf(v) : "";
    }

    /** UTC ISO ("yyyy-MM-dd'T'HH:mm:ss") → Europe/Istanbul "dd.MM.yyyy HH:mm" (yerel saat).
     *  Tüm e-posta tarihleri buradan geçer; proje genelinde kullanıcıya her zaman local gösterilir. */
    private String formatIso(String iso) {
        if (iso == null || iso.isBlank()) return "—";
        try {
            LocalDateTime utc = LocalDateTime.parse(iso, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(IST)
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        } catch (Exception e) {
            try {
                // Beklenmeyen biçim — saat dilimi çevirmeden en azından okunur biçime getir
                return iso.substring(8, 10) + "." + iso.substring(5, 7) + "." + iso.substring(0, 4)
                     + " " + iso.substring(11, 16);
            } catch (Exception e2) {
                return iso;
            }
        }
    }

    /** Proje saat dilimi (UTC+3, DST yok). Stored ISO string'leri UTC kabul edilir. */
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");

    /** UTC ISO ("yyyy-MM-dd'T'HH:mm:ss") → Europe/Istanbul "dd.MM.yyyy HH:mm".
     *  null/boş → null (footer'da ilgili satır gizlensin). */
    private String formatIstanbul(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            LocalDateTime utc = LocalDateTime.parse(iso, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            return utc.atZone(ZoneOffset.UTC).withZoneSameInstant(IST)
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        } catch (Exception e) {
            return formatIso(iso);
        }
    }

    private String buildSimpleAlertHtml(String subject, String message) {
        return buildSimpleAlertHtml(subject, message, null, null);
    }

    /** Outlook (Word/VML) + diğer istemciler (HTML) için "bulletproof" CTA butonu.
     *  mso/non-mso koşullu yorumlarıyla her istemci yalnız kendi sürümünü görür. */
    private String ctaButton(String url, String label, String accent) {
        if (url == null || url.isBlank()) return "";
        String safe = escHtml(url);
        return "<!--[if mso]>"
            + "<v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:w=\"urn:schemas-microsoft-com:office:word\""
            + " href=\"" + safe + "\" style=\"height:48px;v-text-anchor:middle;width:360px;\""
            + " arcsize=\"16%\" strokecolor=\"" + accent + "\" fillcolor=\"" + accent + "\">"
            + "<w:anchorlock/>"
            + "<center style=\"color:#ffffff;font-family:'Segoe UI',Tahoma,Arial,sans-serif;font-size:15px;font-weight:bold;\">"
            + label + "</center>"
            + "</v:roundrect>"
            + "<![endif]-->"
            + "<!--[if !mso]><!-->"
            + "<table role='presentation' border='0' cellspacing='0' cellpadding='0' style='display:inline-block'><tr>"
            + "<td align='center' bgcolor='" + accent + "' style='background:" + accent + ";border-radius:8px;"
            + "padding:13px 26px;color:#ffffff'>"
            + "<a href='" + safe + "' target='_blank' style='color:#ffffff;text-decoration:none;"
            + "font-size:15px;font-weight:800;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<span style='color:#ffffff'>" + label + "</span></a>"
            + "</td></tr></table>"
            + "<!--<![endif]-->";
    }

    private String buildSimpleAlertHtml(String subject, String message, String ctaUrl, String ctaLabel) {
        String now = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String color = subject.contains("KRİTİK") || subject.contains("CRITICAL") ? "#dc2626"
                : subject.contains("YÜKSEK") || subject.contains("HIGH") ? "#ea580c" : "#d97706";
        String css = "<style>"
            + "@media only screen and (max-width:600px){"
            + ".em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-body{padding:16px!important}"
            + "}"
            + "</style>";
        String cta = (ctaUrl != null && !ctaUrl.isBlank())
            ? "<div style='text-align:center;margin-top:20px'>" + ctaButton(ctaUrl, ctaLabel, "#15803d") + "</div>"
            : "";
        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml'"
            + " xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" + css + "</head>"
            + "<body style='margin:0;padding:0;background:#f3f4f6;font-family:\"Segoe UI\",Arial,sans-serif'>"
            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0' style='background:#f3f4f6;padding:24px 10px'>"
            + "<tr><td align='center'>"
            + "<table class='em-card' width='600' cellpadding='0' cellspacing='0' border='0'"
            + " style='max-width:600px;width:100%;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.12)'>"
            + "<tr><td style='background:" + color + ";padding:20px 24px;color:#fff'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.1em;opacity:.8'>CertMonitor — Sertifika İzleme</div>"
            + "<div style='font-size:18px;font-weight:800;margin-top:4px;word-break:break-word'>" + escHtml(subject) + "</div>"
            + "</td></tr>"
            + "<tr><td class='em-body' style='padding:24px'>"
            + "<div style='background:#fffbeb;border-left:4px solid " + color + ";"
            + "border-radius:0 8px 8px 0;padding:14px 16px;color:#1c1917;font-size:14px;line-height:1.7'>"
            + escHtml(message) + "</div>"
            + cta
            + "<div style='text-align:center;color:#94a3b8;font-size:11px;margin-top:20px;"
            + "padding-top:16px;border-top:1px solid #f1f5f9'>CertMonitor &nbsp;·&nbsp; " + now + "</div>"
            + "</td></tr></table>"
            + "</td></tr></table>"
            + "</body></html>";
    }

    private String buildRichResolvedHtml(String domain, String alertType, String alertLevel,
                                          Integer daysRemaining, String resolvedBy, String resolvedAt,
                                          String createdAt, Map<String, Object> certContext) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String green = "#16a34a";

        String levelTr = switch (alertLevel != null ? alertLevel : "") {
            case "CRITICAL" -> "KRİTİK";
            case "HIGH"     -> "YÜKSEK";
            default         -> "UYARI";
        };
        String levelColor = switch (alertLevel != null ? alertLevel : "") {
            case "CRITICAL" -> "#dc2626";
            case "HIGH"     -> "#ea580c";
            default         -> "#d97706";
        };
        String typeTr = switch (alertType != null ? alertType : "") {
            case "REVOKED"      -> "İptal";
            case "MISMATCH"     -> "Dağıtım Eksik";
            case "CHAIN_BROKEN" -> "Zincir Sorunu";
            default             -> "Son Kullanma";
        };

        String notAfter      = ctxStr(certContext, "not_after");
        String issuerCn      = ctxStr(certContext, "issuer_cn");
        String issuerOrg     = ctxStr(certContext, "issuer");
        String issuerDisplay = !issuerCn.isEmpty() ? issuerCn : (!issuerOrg.isEmpty() ? issuerOrg : "—");
        String by            = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "admin";

        // ── Resolution info (left column) ──
        String resolverRows = tableRow2col("👤 Çözen Kişi",          escHtml(by))
            + tableRow2col("🕐 Çözülme Tarihi",  fmtOrDash(formatIstanbul(resolvedAt)))
            + tableRow2col("📅 Alarm Oluşturma", fmtOrDash(formatIstanbul(createdAt)))
            + (daysRemaining != null
                ? tableRow2col("📊 Alarm Anındaki Kalan Gün", daysRemaining + " gün") : "");

        // ── Alert detail (right column) ──
        String alertRows = tableRow2col("⚠ Alarm Tipi",      typeTr)
            + "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>🔴 Alarm Seviyesi</td>"
            + "<td style='padding:9px 13px;font-size:14px;font-weight:700;color:" + levelColor + "'>" + levelTr + "</td>"
            + "</tr>"
            + tableRow2col("🌐 Alan Adı",         escHtml(domain))
            + (!notAfter.isEmpty()
                ? tableRow2col("📅 Sertifika Bitiş", formatIso(notAfter)) : "")
            + (!issuerDisplay.equals("—")
                ? tableRow2col("🏢 Veren Kurum",     escHtml(issuerDisplay)) : "");

        String twoColSection =
            "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:4px'><tr>"
            + "<td class='em-col-l' valign='top' style='width:50%;padding-right:8px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='border:1px solid #bbf7d0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#15803d' style='background-color:#15803d;padding:9px 14px;font-size:11px;font-weight:700;"
            + "letter-spacing:.1em;color:#dcfce7'>&#10003; ÇÖZÜM BİLGİSİ</td></tr>"
            + resolverRows + "</table></td>"
            + "<td class='em-col-r' valign='top' style='width:50%'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#334155' style='background-color:#334155;padding:9px 14px;font-size:11px;font-weight:700;"
            + "letter-spacing:.1em;color:#cbd5e1'>ALARM DETAYI</td></tr>"
            + alertRows + "</table></td>"
            + "</tr></table>";

        String dark = "#0f172a";

        // Tüm renkli zeminler td+bgcolor ile (Outlook/Word motoru div background ve
        // div padding'i yok sayar); fontlar -apple-system (Mac) + Segoe UI; MSO font
        // fallback + color-scheme (dark-mode renk bozulması engellenir).
        String css = "<style>"
            + "body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + "@media only screen and (max-width:620px){"
            + ".em-card{width:100%!important;border-radius:0!important}"
            + ".em-pad{padding-left:18px!important;padding-right:18px!important}"
            + ".em-domain{font-size:18px!important;word-break:break-all!important}"
            + ".em-col-l{display:block!important;width:100%!important;padding-right:0!important;padding-bottom:10px!important}"
            + ".em-col-r{display:block!important;width:100%!important}"
            + "}"
            + "</style>";

        return "<!DOCTYPE html><html lang='tr'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + "<meta name='color-scheme' content='light only'>"
            + "<meta name='supported-color-schemes' content='light'>"
            + "<!--[if mso]><style>*{font-family:Arial,Helvetica,sans-serif !important}</style><![endif]-->"
            + css + "</head>"
            + "<body style='margin:0;padding:0;background-color:#eef2f6;"
            + "font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,\"Helvetica Neue\",Arial,sans-serif'>"

            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#eef2f6'"
            + " style='background-color:#eef2f6;mso-table-lspace:0;mso-table-rspace:0'>"
            + "<tr><td align='center' style='padding:28px 12px'>"

            + "<table role='presentation' class='em-card' width='600' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'"
            + " style='width:600px;max-width:600px;background-color:#ffffff;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 10px 30px rgba(15,23,42,.12)'>"

            // ── Header (executive dark) ──
            + "<tr><td class='em-pad' bgcolor='" + dark + "' style='background-color:" + dark + ";padding:26px 30px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.16em;color:#7c8aa0'>"
            + "CERTMONITOR &nbsp;&#183;&nbsp; SERTİFİKA İZLEME</div>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='margin:14px 0 2px'><tr>"
            + "<td bgcolor='" + green + "' style='background-color:" + green + ";border-radius:6px;padding:6px 13px;"
            + "font-size:12px;font-weight:800;letter-spacing:.09em;color:#ffffff'>&#10003;&nbsp; ÇÖZÜLDÜ</td>"
            + "</tr></table>"
            + "<div class='em-domain' style='color:#ffffff;font-size:23px;font-weight:800;"
            + "margin-top:12px;word-break:break-all;line-height:1.25'>" + escHtml(domain) + "</div>"
            + "<div style='color:#aab4c5;font-size:14px;font-weight:600;margin-top:6px'>"
            + "Sorun Giderildi &nbsp;&#183;&nbsp; " + typeTr + " Alarmı</div>"
            + "</td></tr>"

            // ── Success banner (yatay, her clientta tutarlı) ──
            + "<tr><td class='em-pad' bgcolor='#ffffff' style='background-color:#ffffff;padding:24px 30px 6px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ecfdf5'"
            + " style='background-color:#ecfdf5;border-radius:12px'><tr>"
            + "<td width='80' align='center' valign='middle' style='padding:18px 0 18px 16px'>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td width='48' height='48' align='center' valign='middle' bgcolor='" + green + "'"
            + " style='background-color:" + green + ";border-radius:24px;color:#ffffff;font-size:27px;"
            + "font-weight:700;line-height:48px;text-align:center'>&#10003;</td>"
            + "</tr></table></td>"
            + "<td valign='middle' style='padding:18px 18px'>"
            + "<div style='font-size:18px;font-weight:800;color:#15803d;letter-spacing:-.2px'>"
            + "Sorun Başarıyla Giderildi</div>"
            + "<div style='font-size:13px;color:#3f7a5a;margin-top:3px;line-height:1.5'>"
            + "Bu alarm kapatıldı; sertifika izleme kesintisiz sürüyor.</div>"
            + "</td></tr></table></td></tr>"

            // ── İki kolon detay ──
            + "<tr><td class='em-pad' bgcolor='#ffffff' style='background-color:#ffffff;padding:18px 30px 4px'>"
            + twoColSection + "</td></tr>"

            // ── Özet kutusu (sol aksan renkli hücreyle — Outlook-safe) ──
            + "<tr><td class='em-pad' bgcolor='#ffffff' style='background-color:#ffffff;padding:0 30px 24px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='border-radius:10px;overflow:hidden'><tr>"
            + "<td width='5' bgcolor='" + green + "' style='background-color:" + green + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:14px 18px;color:#14532d;"
            + "font-size:14px;line-height:1.7'>"
            + "<div style='font-size:11px;font-weight:800;letter-spacing:.09em;color:" + green + ";margin-bottom:6px'>ÖZET</div>"
            + "<strong>" + escHtml(domain) + "</strong> için açık olan sertifika alarmı "
            + "<strong>" + escHtml(by) + "</strong> tarafından <strong>çözüldü</strong> olarak işaretlendi. "
            + "Uyarı tipi: <strong>" + typeTr + "</strong> &nbsp;|&nbsp; önceki seviye: "
            + "<strong style='color:" + levelColor + "'>" + levelTr + "</strong>."
            + "</td></tr></table></td></tr>"

            // ── Footer (executive dark) ──
            + "<tr><td class='em-pad' bgcolor='" + dark + "' style='background-color:" + dark + ";padding:15px 30px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='font-size:11px;color:#7c8aa0;font-weight:700;letter-spacing:.06em'>CERTMONITOR</td>"
            + "<td align='right' style='font-size:11px;color:#7c8aa0'>Bildirim: " + generatedAt + "</td>"
            + "</tr></table></td></tr>"

            + "</table>"             // em-card
            + "</td></tr></table>"   // dış sarmalayıcı
            + "</body></html>";
    }

    // ── Accessibility (site down) mails ──────────────────────────────────────

    /**
     * İzleme kesintisi alarm maili — ACCESSIBILITY / PORT_DOWN / DNS_FAILURE
     * için ortak şablon; etiketler tipe göre çözülür. ctx,
     * MonitoringOutageService'in ürettiği kesinti bağlamıdır (port/protocol/
     * record_type, first_failure_at, last_error, confirm_attempts,
     * confirm_delay_ms, confirm_attempt_count). "Tekrar Bildir" yolu eksik
     * context geçirebileceğinden TÜM okumalar null-toleranslıdır.
     */
    @SuppressWarnings("unchecked")
    private String buildRichMonitoringOutageAlertHtml(String message, String domain,
                                                      String alertType, Map<String, Object> ctx) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String red = "#dc2626";

        String kicker = switch (alertType) {
            case "PORT_DOWN"   -> "CertMonitor — Port İzleme";
            case "DNS_FAILURE" -> "CertMonitor — DNS İzleme";
            default            -> "CertMonitor — Erişilebilirlik İzleme";
        };
        String heroTitle = switch (alertType) {
            case "PORT_DOWN"   -> "PORT ERİŞİLEMEZ";
            case "DNS_FAILURE" -> "DNS ÇÖZÜLEMİYOR";
            default            -> "SİTE ERİŞİLEMEZ";
        };
        String typeTrLabel = switch (alertType) {
            case "PORT_DOWN"   -> "Port Kesintisi";
            case "DNS_FAILURE" -> "DNS Çözümleme Hatası";
            default            -> "Erişim Kesintisi";
        };
        String accessLabel = switch (alertType) {
            case "PORT_DOWN"   -> "Port";
            case "DNS_FAILURE" -> "DNS Çözümleme";
            default            -> "Erişim";
        };

        String port           = ctxStr(ctx, "port");
        String protocol       = ctxStr(ctx, "protocol");
        String recordType     = ctxStr(ctx, "record_type");
        String firstFailureAt = ctxStr(ctx, "first_failure_at");
        String lastError      = ctxStr(ctx, "last_error");
        String attemptCount   = ctxStr(ctx, "confirm_attempt_count");
        String delayMs        = ctxStr(ctx, "confirm_delay_ms");
        String endpoint = "DNS_FAILURE".equals(alertType)
                ? escHtml(domain) + (!recordType.isEmpty() ? " · " + escHtml(recordType) + " kaydı" : "")
                : escHtml(domain) + (!port.isEmpty() ? ":" + port : "");
        String attemptsLabel  = !attemptCount.isEmpty() ? attemptCount : "Ardışık";
        String delayLabel     = !delayMs.isEmpty()
                ? (Long.parseLong(delayMs) / 1000) + " sn arayla " : "";

        List<Map<String, Object>> attempts = (ctx != null && ctx.get("confirm_attempts") instanceof List<?> l)
                ? (List<Map<String, Object>>) l : List.of();

        // ── Hero — kesinti bildirimi ──
        String hero = "<table width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='margin:20px 0;border-radius:14px;overflow:hidden;border:2px solid " + red + "22'>"
            + "<tr>"
            + "<td class='em-hero-l' align='center' valign='middle' width='38%'"
            + " style='background:" + red + ";padding:22px 14px'>"
            + "<div style='color:#fff;font-size:52px;line-height:1'>🔴</div>"
            + "<div style='color:#fff;font-size:15px;font-weight:800;margin-top:8px;letter-spacing:.06em'>" + heroTitle + "</div>"
            + "<div style='color:rgba(255,255,255,.85);font-size:12px;margin-top:8px;padding:0 6px'>⚠ Acil müdahale gerekli</div>"
            + "</td>"
            + "<td class='em-hero-r' valign='middle' style='background:" + red + "0d;padding:20px 22px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8;text-transform:uppercase;margin-bottom:10px'>İlk Hata Zamanı</div>"
            + "<div style='font-size:24px;font-weight:900;color:" + red + ";letter-spacing:-.5px'>" + formatIso(firstFailureAt) + "</div>"
            + "<div style='font-size:13px;color:#475569;margin-top:6px;line-height:1.6'>" + formatIsoFull(firstFailureAt) + "</div>"
            + "<div style='margin-top:12px;padding:6px 12px;background:" + red + ";color:#fff;"
            + "border-radius:6px;font-size:12px;font-weight:800;display:inline-block'>"
            + "🔁 " + attemptsLabel + " doğrulama denemesi " + delayLabel + "— tümü başarısız</div>"
            + "</td>"
            + "</tr></table>";

        // ── Sol kolon: kesinti bilgileri ──
        StringBuilder outageRows = new StringBuilder();
        outageRows.append(tableRow2col("🌐 Uç Nokta", endpoint));
        if ("PORT_DOWN".equals(alertType) && !protocol.isEmpty()) {
            outageRows.append(tableRow2col("🔌 Protokol", escHtml(protocol)));
        }
        if (!firstFailureAt.isEmpty()) {
            outageRows.append(tableRow2col("🕐 İlk Hata", formatIso(firstFailureAt)));
        }
        if (!lastError.isEmpty()) {
            String shortErr = lastError.length() > 90 ? lastError.substring(0, 90) + "…" : lastError;
            outageRows.append(tableRow2col("⚠ Son Hata", escHtml(shortErr)));
        }
        for (Map<String, Object> a : attempts) {
            String at  = String.valueOf(a.getOrDefault("checked_at", ""));
            String err = String.valueOf(a.getOrDefault("error", ""));
            String time = formatIso(at); // her zaman yerel saat (Europe/Istanbul)
            String detail = !err.isEmpty() && !"null".equals(err)
                    ? time + " — " + escHtml(err.length() > 60 ? err.substring(0, 60) + "…" : err)
                    : time + " — yanıt yok";
            outageRows.append(tableRow2col("🔁 Deneme " + a.getOrDefault("attempt", "?"), detail));
        }

        // ── Sağ kolon: durum özeti ──
        String statusRows = statusRow2col(accessLabel,  "✗ ERİŞİLEMİYOR")
            + statusRow2col("Doğrulama",  "✗ " + (attempts.isEmpty() ? "Başarısız" : attempts.size() + "/" + attempts.size() + " deneme başarısız"))
            + statusRow2col("Seviye",     "✗ KRİTİK")
            + statusRow2col("İzleme",     "✓ Devam ediyor");

        String twoColSection =
            "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px'><tr>"
            + "<td class='em-col-l' valign='top' style='width:55%;padding-right:8px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td style='background:#1e293b;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>KESİNTİ BİLGİLERİ</td></tr>"
            + outageRows + "</table></td>"
            + "<td class='em-col-r' valign='top' style='width:45%'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td style='background:#334155;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>DURUM ÖZETİ</td></tr>"
            + statusRows + "</table></td>"
            + "</tr></table>";

        String css = "<style>"
            + "body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + "@media only screen and (max-width:620px){"
            + ".em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-domain{font-size:16px!important;word-break:break-all!important}"
            + ".em-body{padding:14px!important}"
            + ".em-hero-l,.em-hero-r{display:block!important;width:100%!important}"
            + ".em-hero-l{border-radius:12px 12px 0 0!important}"
            + ".em-hero-r{border-radius:0 0 12px 12px!important;padding:16px!important}"
            + ".em-col-l{display:block!important;width:100%!important;padding-right:0!important;padding-bottom:10px!important}"
            + ".em-col-r{display:block!important;width:100%!important}"
            + "}"
            + "</style>";

        return "<!DOCTYPE html><html lang='tr'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + css + "</head>"
            + "<body style='margin:0;padding:0;background:#f1f5f9;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='background:#f1f5f9;padding:24px 10px'><tr><td align='center'>"

            + "<table class='em-card' width='640' cellpadding='0' cellspacing='0' border='0'"
            + " style='max-width:640px;width:100%;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.15)'><tr><td style='padding:0'>"

            // ── Top bar ──
            + "<div style='background:" + red + ";padding:22px 24px'>"
            + "<div style='color:rgba(255,255,255,.65);font-size:11px;font-weight:700;letter-spacing:.12em'>" + kicker + "</div>"
            + "<div class='em-domain' style='color:#fff;font-size:22px;font-weight:900;"
            + "margin-top:10px;word-break:break-all;line-height:1.25'>🌐 " + endpoint + "</div>"
            + "<div style='color:rgba(255,255,255,.88);font-size:15px;font-weight:700;"
            + "margin-top:8px;letter-spacing:.02em'>KRİTİK &nbsp;·&nbsp; " + typeTrLabel + "</div>"
            + "</div>"

            // ── Body ──
            + "<div class='em-body' style='background:#fff;padding:22px 24px'>"
            + hero
            + twoColSection

            // Alarm detayı
            + "<div style='background:#fef2f2;border-left:4px solid " + red + ";"
            + "border-radius:0 8px 8px 0;padding:14px 18px;color:#1c1917;"
            + "font-size:14px;line-height:1.7;margin-bottom:14px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + red + ";margin-bottom:6px'>ALARM DETAYI</div>"
            + escHtml(message)
            + "</div>"

            // Otomatik kapanış notu
            + "<div style='font-size:12px;color:#64748b;line-height:1.6;margin-bottom:20px'>"
            + "ℹ Sorun düzeldiğinde bu alarm otomatik kapatılır ve çözüm e-postası gönderilir. "
            + "Alarmı CertMonitor &rarr; Uyarılar &rarr; Alarm Geçmişi ekranından onaylayabilir veya kapatabilirsiniz."
            + "</div>"

            // Footer
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + "Bildirim: " + generatedAt
            + "</td></tr></table>"

            + "</div>"
            + "</td></tr></table>"
            + "</td></tr></table>"
            + "</body></html>";
    }

    /** İzleme çözüm maili — süre createdAt→resolvedAt'ten hesaplanır; etiketler tipe göre. */
    private String buildRichMonitoringResolvedHtml(String domain, String alertType, String resolvedBy,
                                                   String resolvedAt, String createdAt) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String green = "#16a34a";
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (otomatik)";
        String duration = formatOutageDuration(createdAt, resolvedAt);
        boolean dnsChanged = "DNS_CHANGED".equals(alertType);

        String kicker = switch (alertType != null ? alertType : "") {
            case "PORT_DOWN"                 -> "CertMonitor — Port İzleme";
            case "DNS_FAILURE", "DNS_CHANGED" -> "CertMonitor — DNS İzleme";
            default                          -> "CertMonitor — Erişilebilirlik İzleme";
        };
        String heroLine = switch (alertType != null ? alertType : "") {
            case "PORT_DOWN"   -> "Port Yeniden Açıldı";
            case "DNS_FAILURE" -> "DNS Çözümleme Düzeldi";
            case "DNS_CHANGED" -> "DNS Değişikliği Alarmı Kapatıldı";
            default            -> "Erişim Yeniden Sağlandı";
        };
        String typeTrLabel = switch (alertType != null ? alertType : "") {
            case "PORT_DOWN"   -> "Port Kesintisi";
            case "DNS_FAILURE" -> "DNS Çözümleme Hatası";
            case "DNS_CHANGED" -> "DNS Değişikliği";
            default            -> "Erişim Kesintisi";
        };
        String levelTrLabel = dnsChanged ? "YÜKSEK" : "KRİTİK";
        String levelColor   = dnsChanged ? "#9333ea" : "#dc2626";
        String durationLabel = dnsChanged ? "⏱ Alarm Süresi" : "⏱ Toplam Kesinti";

        String resolverRows = tableRow2col("👤 Çözen",            escHtml(by))
            + tableRow2col("🕐 Çözülme Zamanı",     fmtOrDash(formatIstanbul(resolvedAt)))
            + tableRow2col("📅 Alarm Başlangıcı",   fmtOrDash(formatIstanbul(createdAt)));

        String outageRows = tableRow2col("🌐 Alan Adı",   escHtml(domain))
            + tableRow2col("⚠ Alarm Tipi",  typeTrLabel)
            + "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>🔴 Seviye</td>"
            + "<td style='padding:9px 13px;font-size:14px;font-weight:700;color:" + levelColor + "'>" + levelTrLabel + "</td>"
            + "</tr>"
            + "<tr style='border-top:1px solid #e2e8f0;background:#f0fdf4'>"
            + "<td style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>" + durationLabel + "</td>"
            + "<td style='padding:9px 13px;font-size:14px;font-weight:800;color:" + green + "'>" + duration + "</td>"
            + "</tr>";

        String twoColSection =
            "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px'><tr>"
            + "<td class='em-col-l' valign='top' style='width:50%;padding-right:8px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='border:1px solid #bbf7d0;border-radius:10px;overflow:hidden'>"
            + "<tr><td style='background:#15803d;padding:9px 14px;font-size:11px;font-weight:700;"
            + "letter-spacing:.1em;color:#dcfce7'>ÇÖZÜM BİLGİSİ</td></tr>"
            + resolverRows + "</table></td>"
            + "<td class='em-col-r' valign='top' style='width:50%'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td style='background:#334155;padding:9px 14px;font-size:11px;font-weight:700;"
            + "letter-spacing:.1em;color:#94a3b8'>KESİNTİ DETAYI</td></tr>"
            + outageRows + "</table></td>"
            + "</tr></table>";

        String css = "<style>"
            + "body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + "@media only screen and (max-width:620px){"
            + ".em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-domain{font-size:16px!important;word-break:break-all!important}"
            + ".em-body{padding:14px!important}"
            + ".em-col-l{display:block!important;width:100%!important;padding-right:0!important;padding-bottom:10px!important}"
            + ".em-col-r{display:block!important;width:100%!important}"
            + "}"
            + "</style>";

        return "<!DOCTYPE html><html lang='tr'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + css + "</head>"
            + "<body style='margin:0;padding:0;background:#f1f5f9;"
            + "font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='background:#f1f5f9;padding:24px 10px'><tr><td align='center'>"

            + "<table class='em-card' width='640' cellpadding='0' cellspacing='0' border='0'"
            + " style='max-width:640px;width:100%;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.15)'><tr><td style='padding:0'>"

            // Top bar
            + "<div style='background:" + green + ";padding:22px 24px'>"
            + "<div style='color:rgba(255,255,255,.65);font-size:11px;font-weight:700;"
            + "letter-spacing:.12em'>" + kicker + "</div>"
            + "<div class='em-domain' style='color:#fff;font-size:22px;font-weight:900;"
            + "margin-top:10px;word-break:break-all;line-height:1.25'>✅ " + escHtml(domain) + "</div>"
            + "<div style='color:rgba(255,255,255,.88);font-size:15px;font-weight:700;"
            + "margin-top:8px;letter-spacing:.02em'>" + heroLine + " &nbsp;·&nbsp; " + typeTrLabel + "</div>"
            + "</div>"

            // Body
            + "<div class='em-body' style='background:#fff;padding:22px 24px'>"

            // Hero checkmark
            + "<div style='text-align:center;margin:16px 0 24px'>"
            + "<div style='display:inline-block;background:#dcfce7;border-radius:50%;width:80px;"
            + "height:80px;line-height:80px;font-size:42px;border:3px solid " + green + "'>✓</div>"
            + "<div style='margin-top:14px;font-size:20px;font-weight:800;color:#15803d;"
            + "letter-spacing:-.3px'>" + heroLine + "</div>"
            + "<div style='margin-top:6px;font-size:13px;color:#64748b'>"
            + "Alarm kapatıldı. İzleme devam etmektedir.</div>"
            + "</div>"

            + twoColSection

            // Info box
            + "<div style='background:#f0fdf4;border-left:4px solid " + green + ";"
            + "border-radius:0 8px 8px 0;padding:14px 18px;color:#14532d;"
            + "font-size:14px;line-height:1.7;margin-bottom:20px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;"
            + "color:" + green + ";margin-bottom:6px'>BİLGİ</div>"
            + "<strong>" + escHtml(domain) + "</strong> için açık olan <strong>" + typeTrLabel
            + "</strong> alarmı kapatıldı. "
            + (dnsChanged ? "Alarm süresi: " : "Toplam kesinti süresi: ")
            + "<strong>" + duration + "</strong>."
            + "</div>"

            // Footer
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + "CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;"
            + "font-size:11px;color:#94a3b8'>Bildirim: " + generatedAt + "</td>"
            + "</tr></table>"

            + "</div>"
            + "</td></tr></table>"
            + "</td></tr></table>"
            + "</body></html>";
    }

    /**
     * DNS kayıt değişikliği alarm maili (YÜKSEK, mor) — ESKİ | YENİ değerler
     * iki kolon halinde. Teyit denemeleri bölümü yoktur (değişiklik başarılı
     * sorgudan pozitif gözlemdir); alarm otomatik kapanmaz. ctx okumaları
     * null-toleranslıdır ("Tekrar Bildir" yolu eksik context geçirebilir).
     */
    private String buildRichDnsChangedAlertHtml(String message, String domain, Map<String, Object> ctx) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String purple = "#9333ea";

        String recordType = ctxStr(ctx, "record_type");
        String changedAt  = ctxStr(ctx, "changed_at");
        List<String> oldValues = ctxList(ctx, "old_values");
        List<String> newValues = ctxList(ctx, "new_values");

        return buildDnsChangedHtmlInternal(message, domain, recordType, changedAt, oldValues, newValues, purple, generatedAt);
    }

    @SuppressWarnings("unchecked")
    private List<String> ctxList(Map<String, Object> ctx, String key) {
        if (ctx == null) return List.of();
        Object v = ctx.get(key);
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }

    private String buildDnsChangedHtmlInternal(String message, String domain, String recordType,
                                               String changedAt, List<String> oldValues,
                                               List<String> newValues, String purple, String generatedAt) {
        StringBuilder oldRows = new StringBuilder();
        if (oldValues.isEmpty()) {
            oldRows.append(tableRow2colMono("•", "—"));
        } else {
            for (String v : oldValues) oldRows.append(tableRow2colMono("•", escHtml(v)));
        }
        StringBuilder newRows = new StringBuilder();
        if (newValues.isEmpty()) {
            newRows.append(tableRow2colMono("•", "—"));
        } else {
            for (String v : newValues) newRows.append(tableRow2colMono("•", escHtml(v)));
        }

        String twoColSection =
            "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px'><tr>"
            + "<td class='em-col-l' valign='top' style='width:50%;padding-right:8px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td style='background:#475569;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>ESKİ DEĞERLER</td></tr>"
            + oldRows + "</table></td>"
            + "<td class='em-col-r' valign='top' style='width:50%'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid " + purple + "44;border-radius:10px;overflow:hidden'>"
            + "<tr><td style='background:" + purple + ";padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#f3e8ff'>YENİ DEĞERLER</td></tr>"
            + newRows + "</table></td>"
            + "</tr></table>";

        String css = "<style>"
            + "body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + "@media only screen and (max-width:620px){"
            + ".em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-domain{font-size:16px!important;word-break:break-all!important}"
            + ".em-body{padding:14px!important}"
            + ".em-col-l{display:block!important;width:100%!important;padding-right:0!important;padding-bottom:10px!important}"
            + ".em-col-r{display:block!important;width:100%!important}"
            + "}"
            + "</style>";

        return "<!DOCTYPE html><html lang='tr'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + css + "</head>"
            + "<body style='margin:0;padding:0;background:#f1f5f9;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='background:#f1f5f9;padding:24px 10px'><tr><td align='center'>"

            + "<table class='em-card' width='640' cellpadding='0' cellspacing='0' border='0'"
            + " style='max-width:640px;width:100%;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.15)'><tr><td style='padding:0'>"

            // ── Top bar ──
            + "<div style='background:" + purple + ";padding:22px 24px'>"
            + "<div style='color:rgba(255,255,255,.65);font-size:11px;font-weight:700;letter-spacing:.12em'>CertMonitor — DNS İzleme</div>"
            + "<div class='em-domain' style='color:#fff;font-size:22px;font-weight:900;"
            + "margin-top:10px;word-break:break-all;line-height:1.25'>🔀 " + escHtml(domain)
            + (!recordType.isEmpty() ? " <span style='font-size:15px;font-weight:700'>· " + escHtml(recordType) + " kaydı</span>" : "")
            + "</div>"
            + "<div style='color:rgba(255,255,255,.88);font-size:15px;font-weight:700;"
            + "margin-top:8px;letter-spacing:.02em'>YÜKSEK &nbsp;·&nbsp; DNS Değişikliği</div>"
            + "</div>"

            // ── Body ──
            + "<div class='em-body' style='background:#fff;padding:22px 24px'>"

            // Hero
            + "<div style='text-align:center;margin:16px 0 22px'>"
            + "<div style='display:inline-block;background:" + purple + ";color:#fff;"
            + "border-radius:12px;padding:14px 32px;font-size:17px;font-weight:800;letter-spacing:.02em'>"
            + "🔀 DNS KAYDI DEĞİŞTİ"
            + "</div>"
            + (!changedAt.isEmpty()
                ? "<div style='margin-top:10px;font-size:13px;color:#64748b'>Tespit zamanı: "
                  + formatIsoFull(changedAt) + "</div>" : "")
            + "</div>"

            + twoColSection

            // Alarm detayı
            + "<div style='background:#faf5ff;border-left:4px solid " + purple + ";"
            + "border-radius:0 8px 8px 0;padding:14px 18px;color:#1c1917;"
            + "font-size:14px;line-height:1.7;margin-bottom:14px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + purple + ";margin-bottom:6px'>ALARM DETAYI</div>"
            + escHtml(message)
            + "</div>"

            // Manuel kapanış notu
            + "<div style='font-size:12px;color:#64748b;line-height:1.6;margin-bottom:20px'>"
            + "ℹ Bu alarm otomatik kapanmaz. Değişiklik planlı ise CertMonitor &rarr; Uyarılar &rarr; "
            + "Alarm Geçmişi ekranından alarmı onaylayın ve kapatın. Beklenmedik bir değişiklikse "
            + "(olası domain hijack / hatalı migrasyon) derhal ağ ekibiyle iletişime geçin."
            + "</div>"

            // Footer
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + "Bildirim: " + generatedAt
            + "</td></tr></table>"

            + "</div>"
            + "</td></tr></table>"
            + "</td></tr></table>"
            + "</body></html>";
    }

    // ── Haftalık rapor mailleri ──────────────────────────────────────────────

    /** Onay mailinde inline (CID) gömülecek görsel. */
    public record InlineImage(String cid, byte[] data, String contentType) {}

    private static final ObjectMapper WR_JSON = new ObjectMapper();
    private static final List<org.commonmark.Extension> MD_EXTENSIONS = List.of(
            TablesExtension.create(),
            org.commonmark.ext.task.list.items.TaskListItemsExtension.create());
    private static final Parser MD_PARSER = Parser.builder().extensions(MD_EXTENSIONS).build();
    private static final HtmlRenderer MD_RENDERER = HtmlRenderer.builder()
            .extensions(MD_EXTENSIONS).escapeHtml(true).build();

    /**
     * Genel amaçlı HTML mail — CC ve inline CID görsel desteğiyle.
     * ÖNEMLİ: setText(html, true) addInline'dan ÖNCE çağrılmalıdır
     * (MimeMessageHelper related multipart sıralaması).
     */
    /** Gönderen adres — haftalık rapor gönderim geçmişi kayıtları için. */
    /* package */ String fromAddress() {
        return currentFrom();
    }

    public String sendHtml(String[] to, String[] cc, String subject, String html,
                           List<InlineImage> inline) {
        if (!isEnabled()) {
            log.info("⚠ Email devre dışı — TO={} CC={} | KONU={}",
                    Arrays.toString(to), Arrays.toString(cc != null ? cc : new String[0]), subject);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = currentSender().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    msg, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");
            helper.setTo(to);
            if (cc != null && cc.length > 0) helper.setCc(cc);
            applyFrom(helper);
            helper.setSubject(subject);
            helper.setText(html, true);
            if (inline != null) {
                for (InlineImage img : inline) {
                    helper.addInline(img.cid(), new ByteArrayResource(img.data()), img.contentType());
                }
            }
            return doSend(Arrays.toString(to), msg, 1);
        } catch (Exception e) {
            log.error("✗ HTML e-posta hazırlanamadı: TO={} | HATA={}", Arrays.toString(to), e.getMessage());
            return "FAILED: " + e.getMessage();
        }
    }

    /** Markdown → HTML (GFM tabloları destekli, raw HTML escape'li).
     *  forEmail=true: /api/weekly-reports/images/{id} → cid:img{id}. */
    private String mdToHtml(String md, boolean forEmail, Map<Long, Integer> imageWidths, int maxWidth) {
        if (md == null || md.isBlank()) return "";
        String src = forEmail
                ? md.replaceAll("\\]\\(/api/weekly-reports/images/(\\d+)\\)", "](cid:img$1)")
                : md;
        String html = MD_RENDERER.render(MD_PARSER.parse(src));
        html = inlineImageStyles(taskCheckboxesToSymbols(html), forEmail, imageWidths, maxWidth);
        // Outlook <head><style>'ı yok sayar; markdown bloklarının .wr-md stillerini
        // (liste girintisi, paragraf, tablo) maile inline et → Outlook = önizleme.
        return forEmail ? inlineBlockStyles(html) : html;
    }

    /** Markdown çıktısındaki blok elemanlara .wr-md ile birebir aynı inline stilleri
     *  ekler (yalnız mail yolu). Yalnız markdown HTML'ine uygulanır — chip/section
     *  tabloları ayrı üretildiğinden etkilenmez. GFM tablo align attr'ı korunur. */
    private String inlineBlockStyles(String html) {
        if (html == null || html.isEmpty()) return html;
        return html
            .replace("<ul>", "<ul style=\"margin:6px 0;padding-left:22px;font-size:14px;color:#1e293b\">")
            .replace("<ol>", "<ol style=\"margin:6px 0;padding-left:22px;font-size:14px;color:#1e293b\">")
            .replace("<p>",  "<p style=\"margin:6px 0;font-size:14px;line-height:1.6;color:#1e293b\">")
            .replace("<table>", "<table style=\"border-collapse:collapse;width:100%;margin:8px 0\">")
            .replaceAll("<th(\\s|>)", "<th style=\"border:1px solid #e2e8f0;padding:6px 10px;font-size:13px;text-align:left;background:#f8fafc;font-weight:700\"$1")
            .replaceAll("<td(\\s|>)", "<td style=\"border:1px solid #e2e8f0;padding:6px 10px;font-size:13px;text-align:left\"$1");
    }

    /** Görev listesi checkbox'larını sembole çevirir — mail istemcileri form
     *  input'larını desteklemez (Outlook tamamen düşürür); ☑/☐ her yerde görünür. */
    private static final Pattern TASK_CHECKBOX = Pattern.compile("<input[^>]*type=\"checkbox\"[^>]*>");

    private String taskCheckboxesToSymbols(String html) {
        Matcher m = TASK_CHECKBOX.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, m.group().contains("checked") ? "☑ " : "☐ ");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Outlook masaüstü (Word motoru) head'deki style bloğunu yok sayar — görsel
     *  taşmasını ancak inline stil + açık width attribute engeller. Görsel
     *  genişliği bulunduğu bölümün maxWidth'i ile sınırlanır (konuma göre
     *  dinamik); width attr → Outlook, width:100%/max-width → modern, display:block
     *  → Gmail alt boşluğu. */
    /* package */ static final int MAIL_IMG_MAX_WIDTH = 720; // madde 1-3: kart 850 − iç boşluklar
    /* package */ static final int MAIL_IMG_MAX_WIDTH_CHANNEL = 660; // madde 4 kanal alt-kartı (ekstra padding)
    private static final Pattern CID_IMG = Pattern.compile("<img src=\"cid:img(\\d+)\"");
    private static final Pattern API_IMG = Pattern.compile("<img src=\"(/api/weekly-reports/images/\\d+)\"");

    private String inlineImageStyles(String html, boolean forEmail, Map<Long, Integer> imageWidths, int maxWidth) {
        if (!forEmail) {
            return API_IMG.matcher(html).replaceAll(
                    "<img style=\"max-width:100%;height:auto;border-radius:8px;margin:6px 0\" src=\"$1\"");
        }
        Matcher m = CID_IMG.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Integer natural = imageWidths != null ? imageWidths.get(Long.parseLong(m.group(1))) : null;
            // Görsel doğal genişliğini AŞMASIN ama bölüm sınırını da geçmesin (taşma yok)
            int w = Math.min(natural != null ? natural : maxWidth, maxWidth);
            m.appendReplacement(sb, "<img width=\"" + w + "\" style=\"display:block;width:100%;"
                    + "max-width:" + w + "px;height:auto;border-radius:8px;margin:6px 0\""
                    + " src=\"cid:img" + m.group(1) + "\"");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Haftalık rapor onay maili / önizleme HTML'i. contentJson şeması için
     * bkz. WeeklyReportService.DEFAULT_TEMPLATE_JSON. forEmail=false UI
     * önizlemesi içindir (görsel URL'leri /api olarak kalır).
     */
    /** PO onay-bekleyen mailinde rapor içeriğinin üstüne/altına eklenen onay CTA bloğu
     *  (yeşil banner + bulletproof "Onayla" butonu). url boşsa boş döner. */
    private String approveCtaBlock(String approveUrl) {
        if (approveUrl == null || approveUrl.isBlank()) return "";
        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 18px'>"
            + "<tr><td align='center' bgcolor='#ecfdf5' style='background:#ecfdf5;border:1px solid #a7f3d0;"
            + "border-radius:10px;padding:16px'>"
            + "<div style='font-size:14px;font-weight:700;color:#065f46;margin-bottom:10px'>"
            + "Bu rapor onayınızı bekliyor</div>"
            + ctaButton(approveUrl, "✅ Raporu onaylamak için tıklayınız &rarr;", "#15803d")
            + "</td></tr></table>";
    }

    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail) {
        return buildWeeklyReportHtml(teamName, weekLabel, managerName, contentJson, forEmail, null);
    }

    /** imageWidths: görsel id → gösterim genişliği px (Outlook width attr için);
     *  null/eksik girişlerde 560 fallback. */
    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail,
                                        Map<Long, Integer> imageWidths) {
        return buildWeeklyReportHtml(teamName, weekLabel, managerName, contentJson, forEmail,
                imageWidths, null, null, null);
    }

    /** Footer'a onay bilgisi ekler: approverName + approvedAtIso + sentAtIso (UTC ISO,
     *  Europe/Istanbul'a çevrilir). null olanlar gizlenir (örn. DRAFT önizleme). */
    @SuppressWarnings("unchecked")
    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail,
                                        Map<Long, Integer> imageWidths,
                                        String approverName, String approvedAtIso, String sentAtIso) {
        return buildWeeklyReportHtml(teamName, weekLabel, managerName, contentJson, forEmail,
                imageWidths, approverName, approvedAtIso, sentAtIso, null);
    }

    /** {@code approveCtaUrl} doluysa (PO onay-bekleyen maili): rapor içeriğinin üstüne ve
     *  altına "Raporu onayla" CTA bloğu eklenir; PO raporu görüp maildeki linkten onaylar. */
    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail,
                                        Map<Long, Integer> imageWidths,
                                        String approverName, String approvedAtIso, String sentAtIso,
                                        String approveCtaUrl) {
        String generatedAt = ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        // Footer sağ sütun — onay bilgisi (varsa) + oluşturma zamanı; hepsi Europe/Istanbul
        StringBuilder footerRight = new StringBuilder();
        if (approverName != null && !approverName.isBlank())
            footerRight.append("Onaylayan: ").append(escHtml(approverName)).append("<br>");
        String approvedIst = formatIstanbul(approvedAtIso);
        if (approvedIst != null) footerRight.append("Onay: ").append(approvedIst).append("<br>");
        String sentIst = formatIstanbul(sentAtIso);
        if (sentIst != null) footerRight.append("Gönderim: ").append(sentIst).append("<br>");
        footerRight.append("Oluşturuldu: ").append(generatedAt);
        // Executive lacivert palet — arkaplanlar Outlook (Word motoru) için
        // table/td + bgcolor ATTRIBUTE ile verilir; style yalnız yedektir.
        String accent = "#1f3864";
        String outerBg = "#f4f6f8";

        Map<String, Object> c;
        try {
            c = WR_JSON.readValue(contentJson != null ? contentJson : "{}", Map.class);
        } catch (Exception e) {
            c = Map.of();
        }
        Map<String, Object> i1 = asMap(c.get("item1"));
        Map<String, Object> i2 = asMap(c.get("item2"));
        Map<String, Object> i3 = asMap(c.get("item3"));
        Map<String, Object> i4 = asMap(c.get("item4"));

        // ── Madde 1 — sayı chip'leri + durum + takip linki ──
        String item1Body =
            numChipRow(
                numChip("Toplam", i1.get("total"), "#334155"),
                numChip("Acil",   i1.get("urgent"), "#dc2626"),
                numChip("Yüksek", i1.get("high"),   "#ea580c"),
                numChip("Orta",   i1.get("medium"), "#d97706"),
                numChip("Düşük",  i1.get("low"),    "#16a34a"))
            + metaLine("Durum", str(i1.get("status_text")))
            + linkLine("Proaktif İyileştirme kayıtlarına erişmek için tıklayınız", str(i1.get("tracking_url")))
            + mdToHtml(str(i1.get("notes_md")), forEmail, imageWidths, MAIL_IMG_MAX_WIDTH);

        // ── Madde 2 ──
        // Üç sayı da 0 ise otomatik vurgulu "kayıt yok" notu (manuel yazıma gerek kalmaz)
        boolean noItem2Records = intVal(i2.get("open_incidents")) == 0
                && intVal(i2.get("problem_records")) == 0
                && intVal(i2.get("postmortems")) == 0;
        String item2AutoNote = noItem2Records
            ? "<p style='margin:8px 0;padding:8px 12px;border-left:4px solid #16a34a;background:#e7f6ec;"
              + "font-size:13px;font-weight:700;color:#14532d'>✔ Bu hafta aşım yaşanan olay, problem veya "
              + "açık postmortem kaydı bulunmamaktadır.</p>"
            : "";
        String item2Body =
            numChipRow(
                numChip("Açık Olay",   i2.get("open_incidents"),  "#dc2626"),
                numChip("Problem",     i2.get("problem_records"), "#ea580c"),
                numChip("Postmortem",  i2.get("postmortems"),     "#7c3aed"))
            + item2AutoNote
            + linkLine("Açık olay kayıtları için tıklayınız", str(i2.get("incidents_url")))
            + linkLine("Problem kayıtları için tıklayınız", str(i2.get("problems_url")))
            + linkLine("Postmortem kayıtları için tıklayınız", str(i2.get("postmortems_url")))
            + linkLine("İlgili kayıtlar için tıklayınız", str(i2.get("tracking_url"))) // eski raporlardaki genel link
            + mdToHtml(str(i2.get("notes_md")), forEmail, imageWidths, MAIL_IMG_MAX_WIDTH);

        // ── Madde 3 ──
        String item3Body = mdToHtml(str(i3.get("notes_md")), forEmail, imageWidths, MAIL_IMG_MAX_WIDTH);

        // ── Madde 4 — kanal alt-kartları ──
        StringBuilder item4Body = new StringBuilder();
        Object channelsObj = i4.get("channels");
        if (channelsObj instanceof List<?> channels) {
            for (Object chObj : channels) {
                Map<String, Object> ch = asMap(chObj);
                item4Body.append("<div style='border:1px solid #e2e8f0;border-radius:10px;margin-bottom:10px;overflow:hidden'>")
                    .append("<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>")
                    .append("<td bgcolor='#eef1f5' style='background:#eef1f5;padding:8px 14px;")
                    .append("font-size:13px;font-weight:800;color:#334155'>")
                    .append(escHtml(str(ch.get("name")))).append("</td></tr></table>")
                    .append("<div style='padding:10px 14px'>")
                    .append(mdToHtml(str(ch.get("notes_md")), forEmail, imageWidths, MAIL_IMG_MAX_WIDTH_CHANNEL))
                    .append("</div></div>");
            }
        }

        String css = "<style>"
            + "body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + ".wr-md table{border-collapse:collapse;width:100%;margin:8px 0}"
            + ".wr-md th,.wr-md td{border:1px solid #e2e8f0;padding:6px 10px;font-size:13px;text-align:left}"
            + ".wr-md th{background:#f8fafc;font-weight:700}"
            + ".wr-md img{max-width:100%;height:auto;border-radius:8px;margin:6px 0}"
            + ".wr-md p{margin:6px 0;font-size:14px;line-height:1.6;color:#1e293b}"
            + ".wr-md ul,.wr-md ol{margin:6px 0;padding-left:22px;font-size:14px;color:#1e293b}"
            + "@media only screen and (max-width:870px){"
            + ".em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-body{padding:14px!important}"
            + "}"
            + "</style>";

        return "<!DOCTYPE html><html lang='tr'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + css + "</head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='" + outerBg + "' style='background:" + outerBg + ";padding:24px 10px'>"
            + "<tr><td align='center' bgcolor='" + outerBg + "'>"

            + "<table class='em-card' width='850' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#ffffff' style='max-width:850px;width:100%;background:#ffffff;"
            + "border:1px solid #d7dde5;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.10)'><tr><td bgcolor='#ffffff' style='padding:0'>"

            // ── Üst bar — div shading Outlook'ta güvenilmez; td + bgcolor attr ──
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";padding:22px 24px'>"
            + "<div style='color:#aebed8;font-size:11px;font-weight:700;letter-spacing:.12em'>CertMonitor — Haftalık Rapor</div>"
            + "<div style='color:#ffffff;font-size:22px;font-weight:900;margin-top:10px;line-height:1.25'>📋 "
            + escHtml(teamName) + "</div>"
            + "<div style='color:#dbe3ef;font-size:15px;font-weight:700;margin-top:8px'>"
            + escHtml(weekLabel) + "</div>"
            + "</td></tr></table>"

            // ── Gövde ──
            + "<div class='em-body' style='background:#fff;padding:22px 24px'>"

            // Hitap + giriş
            + "<p style='font-size:15px;color:#0f172a;margin:0 0 6px'><strong>Sayın "
            + escHtml(managerName != null && !managerName.isBlank() ? managerName : "Yönetici") + ",</strong></p>"
            + "<p style='font-size:14px;color:#334155;line-height:1.7;margin:0 0 18px'>"
            + escHtml(teamName) + " ekibi olarak <strong>" + escHtml(weekLabel)
            + "</strong> haftası raporumuzu aşağıda paylaşıyoruz.</p>"

            + approveCtaBlock(approveCtaUrl)

            + reportSection("1. Proaktif Servis İyileştirme Kayıtları", item1Body, accent)
            + reportSection("2. Aşım Yaşanan Olay / Problem ve Açık Postmortem Kayıtları", item2Body, accent)
            + reportSection("3. Haftalık Katılım Sağlanan Çalışmalar", item3Body, accent)
            + reportSection("4. Domain Bazlı Kritik İşlerin Durumu", item4Body.toString(), accent)

            + approveCtaBlock(approveCtaUrl)

            // Footer
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor — Haftalık Rapor</td>"
            + "<td align='right' valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8;line-height:1.7'>"
            + footerRight + "</td></tr></table>"

            + "</div>"
            + "</td></tr></table>"
            + "</td></tr></table>"
            + "</body></html>";
    }

    /** PO'ya onay bekleyen rapor bilgilendirmesi. */
    public String buildWeeklyReportSubmittedHtml(String teamName, String weekLabel, String submittedBy,
                                                 String approveUrl) {
        return buildSimpleAlertHtml(
                "[CertMonitor] " + teamName + " — " + weekLabel + " raporu onayınızı bekliyor",
                teamName + " ekibinin " + weekLabel + " haftalık raporu "
                + (submittedBy != null ? submittedBy : "ekip üyesi")
                + " tarafından onayınıza sunuldu. Aşağıdaki butonla (giriş yapmadan) doğrudan "
                + "onaylayabilir ya da CertMonitor → Raporlar → Haftalık Raporlar ekranından "
                + "inceleyip düzeltme talebiyle iade edebilirsiniz.",
                approveUrl, "✅ Raporu onaylamak için tıklayınız →");
    }

    /** Takıma iade/düzeltme talebi bildirimi. */
    public String buildWeeklyReportRejectedHtml(String teamName, String weekLabel,
                                                String note, String rejectedBy) {
        return buildSimpleAlertHtml(
                "[CertMonitor] " + teamName + " — " + weekLabel + " raporu iade edildi",
                weekLabel + " haftalık raporunuz "
                + (rejectedBy != null ? rejectedBy : "PO")
                + " tarafından düzeltme talebiyle iade edildi.\n\nDüzeltme notu: "
                + (note != null ? note : "—")
                + "\n\nRaporu güncelleyip tekrar onaya gönderebilirsiniz.");
    }

    /** Cuma hatırlatma maili — executive lacivert şablon. Henüz raporunu girmemiş
     *  SY takımlarına, bugün 15:00 son giriş hatırlatması + "nasıl girilir" kısa kılavuz
     *  + doğrudan Haftalık Raporlar'a giden CTA link. Mail her zaman TR. */
    public String buildWeeklyReportReminderHtml(String teamName, String weekLabel, String reportUrl) {
        String accent = "#1f3864";
        String outerBg = "#f4f6f8";
        String generatedAt = ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

        // CTA buton — "bulletproof": Outlook (Word) için VML v:roundrect (yuvarlak köşe +
        // sabit genişlik), diğer istemciler için HTML <td>+<a> (beyaz metin <span> ile sabit).
        // mso/non-mso koşullu yorumlarıyla her istemci yalnız kendi sürümünü görür.
        String btnLabel = "📝 Haftalık raporu girmek için tıklayınız &rarr;";
        String cta = (reportUrl != null && !reportUrl.isBlank())
            ? "<div style='margin:4px 0 20px'>"
              + "<!--[if mso]>"
              + "<v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:w=\"urn:schemas-microsoft-com:office:word\""
              + " href=\"" + escHtml(reportUrl) + "\" style=\"height:48px;v-text-anchor:middle;width:380px;\""
              + " arcsize=\"16%\" strokecolor=\"" + accent + "\" fillcolor=\"" + accent + "\">"
              + "<w:anchorlock/>"
              + "<center style=\"color:#ffffff;font-family:'Segoe UI',Tahoma,Arial,sans-serif;font-size:15px;font-weight:bold;\">"
              + btnLabel + "</center>"
              + "</v:roundrect>"
              + "<![endif]-->"
              + "<!--[if !mso]><!-->"
              + "<table role='presentation' border='0' cellspacing='0' cellpadding='0'><tr>"
              + "<td align='center' bgcolor='" + accent + "' style='background:" + accent + ";border-radius:8px;"
              + "padding:13px 26px;color:#ffffff'>"
              + "<a href='" + escHtml(reportUrl) + "' target='_blank' style='color:#ffffff;text-decoration:none;"
              + "font-size:15px;font-weight:800;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
              + "<span style='color:#ffffff'>" + btnLabel + "</span></a>"
              + "</td></tr></table>"
              + "<!--<![endif]-->"
              + "</div>"
            : "";

        // "Nasıl girilir?" — sabit (güvenilir) HTML; <strong> kaçırılmaz
        String stepsBody =
            "<ol style='margin:0;padding-left:20px;font-size:14px;line-height:1.8;color:#1e293b'>"
            + "<li>Sol menüden <strong>Raporlar → Haftalık Raporlar</strong>'a gidin.</li>"
            + "<li><strong>Yeni Hafta Raporu</strong> ile yıl/hafta seçip <strong>Oluştur</strong>'a tıklayın.</li>"
            + "<li>Dört maddeyi doldurun: Proaktif İyileştirmeler · Olay/Problem/Postmortem · Katılımlar · Domain bazlı kritik işler.</li>"
            + "<li><strong>Kaydet</strong>; hazır olunca <strong>Onaya Gönder</strong>.</li>"
            + "<li>PO onayından sonra rapor müdüre otomatik iletilir.</li>"
            + "</ol>";

        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml'"
            + " xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + "<style>@media only screen and (max-width:870px){"
            + ".em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-body{padding:14px!important}}</style></head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='" + outerBg + "' style='background:" + outerBg + ";padding:24px 10px'>"
            + "<tr><td align='center' bgcolor='" + outerBg + "'>"

            + "<table class='em-card' width='850' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#ffffff' style='max-width:850px;width:100%;background:#ffffff;"
            + "border:1px solid #d7dde5;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.10)'><tr><td bgcolor='#ffffff' style='padding:0'>"

            // ── Üst bar ──
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";padding:22px 24px'>"
            + "<div style='color:#aebed8;font-size:11px;font-weight:700;letter-spacing:.12em'>CERTMONITOR — HAFTALIK RAPOR HATIRLATMASI</div>"
            + "<div style='color:#ffffff;font-size:22px;font-weight:900;margin-top:10px;line-height:1.25'>⏰ "
            + escHtml(teamName) + "</div>"
            + "<div style='color:#dbe3ef;font-size:15px;font-weight:700;margin-top:8px'>"
            + escHtml(weekLabel) + "</div>"
            + "</td></tr></table>"

            // ── Gövde ──
            + "<div class='em-body' style='background:#fff;padding:22px 24px'>"
            + "<p style='font-size:15px;color:#0f172a;margin:0 0 6px'><strong>Sayın " + escHtml(teamName) + " ekibi,</strong></p>"
            + "<p style='font-size:14px;color:#334155;line-height:1.7;margin:0 0 14px'>"
            + "Bu haftanın (<strong>" + escHtml(weekLabel) + "</strong>) haftalık raporu sistemde henüz görünmüyor. "
            + "Mesai başlangıcıyla birlikte raporunuzu hatırlatmak isteriz.</p>"

            // Son giriş uyarısı (vurgulu)
            + "<p style='margin:0 0 4px;padding:10px 14px;border-left:4px solid #dc2626;background:#fef2f2;"
            + "font-size:14px;font-weight:700;color:#991b1b'>⏰ Son giriş <strong>bugün saat 15:00</strong> — "
            + "lütfen bu haftanın raporunu Cert Monitor üzerinden zamanında giriniz.</p>"

            + cta

            + reportSection("Haftalık Rapor Nasıl Girilir?", stepsBody, accent)

            // Footer
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor — Otomatik Hatırlatma</td>"
            + "<td align='right' valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8;line-height:1.7'>"
            + "Oluşturuldu: " + generatedAt + "</td></tr></table>"

            + "</div>"
            + "</td></tr></table>"
            + "</td></tr></table>"
            + "</body></html>";
    }

    private String reportSection(String title, String bodyHtml, String accent) {
        return "<div style='margin-bottom:20px'>"
            // Başlık şeridi: div shading yerine td + bgcolor (Outlook uyumu)
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";color:#ffffff;"
            + "border-radius:8px 8px 0 0;padding:9px 14px;font-size:13px;font-weight:800;"
            + "letter-spacing:.02em'>" + title + "</td></tr></table>"
            // Gövde kabı TABLO+TD: Word <div> padding'i yok sayar, <td> padding'ini onurlandırır
            // → iç boşluk (başlık ile içerik arası dahil) Outlook'ta da render olur.
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse'><tr>"
            + "<td class='wr-md' style='border:1px solid #e2e8f0;border-top:none;border-radius:0 0 8px 8px;"
            + "padding:14px'>"
            + (bodyHtml == null || bodyHtml.isBlank()
                ? "<p style='color:#94a3b8;font-size:13px;margin:0'>—</p>" : bodyHtml)
            + "</td></tr></table></div>";
    }

    /**
     * Olay & Hata bildirimi — executive Outlook/Mac-safe şablon (table + bgcolor attr,
     * inline stil; div shading'e güvenmez). Önem'e göre renklenen başlık, künye tablosu,
     * RCA / iş etkisi / çözüm bölümleri ve olayı açma CTA'sı. {@code inc} = controller dto
     * (snake_case alanlar). Mevcut buildWeeklyReportHtml deseniyle birebir uyumlu.
     */
    public String buildIncidentNotificationHtml(Map<String, Object> inc, String managerName,
                                                String kind, String ctaUrl) {
        boolean resolved = "RESOLVED".equals(kind);
        boolean isNew = "NEW".equals(kind);
        String sev = str(inc.get("severity"));
        // Çözüldüde önem rengi yerine YEŞİL (iyi haber); aksi halde önem rengi.
        String accent = resolved ? "#15803d" : switch (sev == null ? "" : sev) {
            case "CRITICAL" -> "#b91c1c";
            case "HIGH"     -> "#c2410c";
            case "MEDIUM"   -> "#b45309";
            case "LOW"      -> "#15803d";
            default          -> "#1f3864";
        };
        String outerBg = "#f4f6f8";
        String title = str(inc.get("title"));
        String teamName = str(inc.get("team_name"));
        String generatedAt = ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String eyebrow = resolved ? "CertMonitor — Olay Çözüldü ✓"
                       : isNew    ? "CertMonitor — Yeni Olay Bildirimi"
                                  : "CertMonitor — Olay Güncellendi";

        StringBuilder facts = new StringBuilder()
            .append(kvRow("Önem", sevBadgeText(sev)))
            .append(kvRow("Durum", statusText(str(inc.get("status")))))
            .append(kvRow("Takım", teamName))
            .append(kvRow("Kanal", str(inc.get("channel"))))
            .append(kvRow("Servis / Domain", str(inc.get("service"))))
            .append(kvRow("Kategori", str(inc.get("category"))))
            .append(kvRow("Oluş Zamanı", fmtOrDash(formatIstanbul(str(inc.get("occurred_at"))))))
            .append(kvRow("Tespit Zamanı", fmtOrDash(formatIstanbul(str(inc.get("detected_at"))))))
            .append(kvRow("Çözülme Zamanı", fmtOrDash(formatIstanbul(str(inc.get("resolved_at"))))));
        if (inc.get("duration_minutes") != null) facts.append(kvRow("Süre", inc.get("duration_minutes") + " dk"));
        if (Boolean.TRUE.equals(inc.get("sla_breached"))) facts.append(kvRow("SLA", "İHLAL EDİLDİ"));
        if (inc.get("error_budget_burn_pct") != null) facts.append(kvRow("Error Budget Tüketimi", inc.get("error_budget_burn_pct") + "%"));

        String factsTable = "<table width='100%' cellpadding='0' cellspacing='0' border='0' "
            + "style='border-collapse:collapse'>" + facts + "</table>";

        String cta = (ctaUrl != null && !ctaUrl.isBlank())
            ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:2px 0 18px'>"
              + "<tr><td align='center'>" + ctaButton(ctaUrl, "Olay kaydını açmak için tıklayınız &rarr;", accent)
              + "</td></tr></table>"
            : "";

        return "<!DOCTYPE html><html lang='tr'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + "<style>body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + ".inc-md p{margin:0;font-size:14px;line-height:1.7;color:#1e293b}"
            + "@media only screen and (max-width:870px){.em-wrap{padding:0!important}"
            + ".em-card{border-radius:0!important;width:100%!important}.em-body{padding:14px!important}}</style></head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='" + outerBg
            + "' style='background:" + outerBg + ";padding:24px 10px'><tr><td align='center' bgcolor='" + outerBg + "'>"
            + "<table class='em-card' width='850' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff' "
            + "style='max-width:850px;width:100%;background:#ffffff;border:1px solid #d7dde5;border-radius:14px;"
            + "overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.10)'><tr><td bgcolor='#ffffff' style='padding:0'>"
            // ── Başlık (önem rengi) ──
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";padding:22px 24px'>"
            + "<div style='color:#ffffff;opacity:.78;font-size:11px;font-weight:700;letter-spacing:.12em'>"
            + escHtml(eyebrow) + "</div>"
            + "<div style='color:#ffffff;font-size:21px;font-weight:900;margin-top:10px;line-height:1.3'>"
            + escHtml(title) + "</div>"
            + "<div style='color:#ffffff;opacity:.92;font-size:14px;font-weight:700;margin-top:8px'>"
            + escHtml(sevBadgeText(sev)) + " &middot; " + escHtml(teamName) + "</div>"
            + "</td></tr></table>"
            // ── Gövde ──
            + "<div class='em-body' style='background:#fff;padding:22px 24px'>"
            + "<p style='font-size:15px;color:#0f172a;margin:0 0 6px'><strong>Sayın "
            + escHtml(managerName != null && !managerName.isBlank() ? managerName : "Yetkili") + ",</strong></p>"
            + "<p style='font-size:14px;color:#334155;line-height:1.7;margin:0 0 18px'>"
            + (resolved ? "Ekibinize ait bir olay/hata kaydı <strong>çözüldü</strong>. Çözüm özeti aşağıdadır."
                     : isNew ? "Ekibinize ait yeni bir olay/hata kaydı oluşturuldu. Yönetici özeti aşağıdadır."
                     : "Ekibinize ait bir olay/hata kaydı güncellendi. Güncel yönetici özeti aşağıdadır.")
            + "</p>"
            + (resolved ? resolvedBanner(inc) : "")
            + cta
            + reportSection("Olay Künyesi", factsTable, accent)
            + reportSection("Kök Neden (RCA)", textBlock(str(inc.get("rca_summary"))), accent)
            + reportSection("İş Etkisi", textBlock(str(inc.get("business_impact"))), accent)
            + reportSection("Çözüm / Müdahale Adımları", textBlock(str(inc.get("resolution_steps"))), accent)
            + cta
            // ── Footer ──
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + "CertMonitor — Olay & Hata Bildirimi</td>"
            + "<td align='right' valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;"
            + "color:#94a3b8'>Oluşturuldu: " + generatedAt + "</td></tr></table>"
            + "</div></td></tr></table></td></tr></table></body></html>";
    }

    /** Çözüldü banner'ı — yeşil başarı kutusu + çözülme zamanı/süre. */
    private String resolvedBanner(Map<String, Object> inc) {
        String resolvedAt = fmtOrDash(formatIstanbul(str(inc.get("resolved_at"))));
        String dur = inc.get("duration_minutes") != null ? inc.get("duration_minutes") + " dk" : "—";
        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 16px'>"
            + "<tr><td bgcolor='#ecfdf5' style='background:#ecfdf5;border:1px solid #a7f3d0;border-radius:10px;padding:14px 16px'>"
            + "<div style='font-size:15px;font-weight:800;color:#065f46'>&#10003; Bu olay çözüldü</div>"
            + "<div style='font-size:13px;color:#047857;margin-top:6px'>Çözülme: <strong>" + escHtml(resolvedAt)
            + "</strong> &middot; Süre: <strong>" + escHtml(dur) + "</strong></div>"
            + "</td></tr></table>";
    }

    /** Künye satırı (label/value) — Outlook-safe td+bgcolor. */
    private String kvRow(String label, String value) {
        String v = (value == null || value.isBlank()) ? "—" : escHtml(value);
        return "<tr>"
            + "<td bgcolor='#f8fafc' style='background:#f8fafc;border:1px solid #e2e8f0;padding:8px 12px;"
            + "font-size:13px;font-weight:700;color:#475569;width:38%;vertical-align:top'>" + escHtml(label) + "</td>"
            + "<td style='border:1px solid #e2e8f0;padding:8px 12px;font-size:13px;color:#0f172a;"
            + "vertical-align:top;word-break:break-word'>" + v + "</td></tr>";
    }

    /** Markdown metin → e-posta-güvenli paragraf: görsel sözdizimini at, escape + satır sonu→&lt;br&gt;. */
    private String textBlock(String md) {
        if (md == null || md.isBlank()) return "";
        String s = md.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "").trim();
        if (s.isEmpty()) return "";
        return "<p style='margin:0;font-size:14px;line-height:1.7;color:#1e293b'>"
            + escHtml(s).replace("\n", "<br>") + "</p>";
    }

    private static String fmtOrDash(String s) { return (s == null || s.isBlank()) ? "—" : s; }

    private static String sevBadgeText(String sev) {
        if (sev == null) return "—";
        return switch (sev) {
            case "CRITICAL" -> "KRİTİK"; case "HIGH" -> "YÜKSEK";
            case "MEDIUM"   -> "ORTA";   case "LOW"  -> "DÜŞÜK";
            default -> sev;
        };
    }

    private static String statusText(String st) {
        if (st == null) return "—";
        return switch (st) {
            case "OPEN" -> "Açık"; case "INVESTIGATING" -> "İnceleniyor";
            case "MITIGATED" -> "Hafifletildi"; case "RESOLVED" -> "Çözüldü";
            default -> st;
        };
    }

    /** Sayı rozeti — tek tablo HÜCRESİ. E-posta-güvenli: inline-block/margin/
     *  border-radius/8-haneli-hex YOK (Outlook/Apple Mail bunları bozar). Düz
     *  açık zemin (bgcolor attribute) + tam renk kenarlık. numChipRow ile sarılır. */
    private String numChip(String label, Object value, String color) {
        String v = value != null ? String.valueOf(value) : "0";
        String bg = tint(color, 0.12);
        return "<td bgcolor='" + bg + "' style='background:" + bg + ";border:1px solid " + color
            + ";padding:8px 13px;font-size:14px;font-weight:700;color:" + color
            + ";white-space:nowrap;text-align:center'>" + escHtml(label) + ": " + escHtml(v) + "</td>";
    }

    /** Rozet hücrelerini tek satırlık tabloya sarar. Yerleşim INLINE: Outlook (Word)
     *  &lt;head&gt;&lt;style&gt; sınıf kurallarını (.wr-md table) yok sayar, önizleme
     *  (iframe/tarayıcı) onurlandırır. Aynı görünüm için width:100% + border-collapse
     *  + eşit kolon genişlikleri satır-içinde verilir. */
    private String numChipRow(String... cells) {
        int n = cells.length;
        String w = (n > 0 ? Math.round(100.0 / n) : 100) + "%";
        StringBuilder tds = new StringBuilder();
        for (String c : cells) {
            // Her hücreye eşit yüzde: Word otomatik dağıtım yerine eşit kolon kullansın
            tds.append(c.replaceFirst("<td ", "<td width='" + w + "' "));
        }
        // Üst boşluk artık bölüm gövdesi <td> padding'inden gelir (Outlook+önizleme aynı);
        // chip tablosunun üst marjı 0 — yoksa önizlemede çift boşluk olurdu (Word marjı yok sayar).
        return "<table role='presentation' border='0' cellspacing='0' cellpadding='0' width='100%'"
            + " style='width:100%;border-collapse:collapse;margin:0 0 10px'><tr>"
            + tds + "</tr></table>";
    }

    /** Hex rengi beyazla harmanlar (ratio=renk payı) → düz açık ton. 8-haneli
     *  alfa hex yerine her istemcide çalışan gerçek katı renk. */
    static String tint(String hex, double ratio) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        r = (int) Math.round(r * ratio + 255 * (1 - ratio));
        g = (int) Math.round(g * ratio + 255 * (1 - ratio));
        b = (int) Math.round(b * ratio + 255 * (1 - ratio));
        return String.format("#%02x%02x%02x", r, g, b);
    }

    private String metaLine(String label, String value) {
        if (value == null || value.isBlank()) return "";
        return "<p style='font-size:13px;color:#334155;margin:4px 0'><strong>" + label + ":</strong> "
            + escHtml(value) + "</p>";
    }

    /** Takip linki satırı — URL açık yazılmaz; tıklanabilir metin etikettir
     *  (kullanıcı isteği: mailde çıplak URL paylaşılmasın). */
    private String linkLine(String label, String url) {
        if (url == null || url.isBlank()) return "";
        return "<p style='font-size:13px;margin:4px 0'>🔗 <a href='" + escHtml(url)
            + "' style='color:#1f3864;font-weight:700'>" + escHtml(label) + "</a></p>";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String str(Object o) {
        return o != null ? String.valueOf(o) : "";
    }

    /** JSON sayı/metin değerini int'e çevirir (null/parse edilemez → 0). */
    private static int intVal(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return 0;
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return 0; }
    }

    /** Kesinti süresi (createdAt→resolvedAt) TR formatında: "2 saat 14 dakika". */
    private String formatOutageDuration(String createdAt, String resolvedAt) {
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
            LocalDateTime a = LocalDateTime.parse(createdAt, f);
            LocalDateTime b = LocalDateTime.parse(resolvedAt, f);
            long mins = java.time.Duration.between(a, b).toMinutes();
            if (mins < 1) return "1 dakikadan az";
            long days = mins / 1440, hours = (mins % 1440) / 60, rem = mins % 60;
            StringBuilder sb = new StringBuilder();
            if (days > 0)  sb.append(days).append(" gün ");
            if (hours > 0) sb.append(hours).append(" saat ");
            if (rem > 0)   sb.append(rem).append(" dakika");
            return sb.toString().trim();
        } catch (Exception e) {
            return "—";
        }
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
