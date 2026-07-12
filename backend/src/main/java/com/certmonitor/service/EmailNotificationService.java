package com.certmonitor.service;

import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Value;
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

    // Mail'e özel logger — bağımsız açılır/kapanır: logging.level.com.certmonitor.mail=TRACE
    // (env LOGGING_LEVEL_COM_CERTMONITOR_MAIL=TRACE). Detaylı gönderim TRACE'leri buraya gider;
    // operasyonel INFO/ERROR (stack dahil) mevcut @Slf4j `log` üzerinde kalır → TRACE kapalıyken
    // bile hatanın tam stack'i her zaman görünür.
    private static final Logger MAIL_LOG = LoggerFactory.getLogger("com.certmonitor.mail");

    // Outbound mail is driven by the DB-backed SMTP settings (admin Settings page).
    // When nothing is saved yet, SmtpSettingsService falls back to env spring.mail.*
    // so behaviour is unchanged until the admin saves on the screen.
    private final SmtpSettingsService smtpSettings;
    private final SmtpMailService smtpMailService;
    /** Async 421-retry'ın terminal sonucunu, ilk denemede "QUEUED_RETRY" kaydedilen
     *  bildirim loguna geri-yazmak için (subject ile eşleştirilir). */
    private final com.certmonitor.repository.NotificationLogRepository notificationLogRepo;
    private final AppSettingsService appSettings;
    private final EmailTemplateBuilder templateBuilder;   // executive-premium alarm şablonu (tek merkez)

    /** Uygulama dış adresi — e-posta CTA deep-link'leri için. Spring @Value enjekte eder;
     *  birim testte (manuel new) initializer değeri kullanılır. */
    @Value("${cert.monitor.app.base-url:http://localhost:5173}")
    private String appBaseUrl = "http://localhost:5173";

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
            String text = buildAlertEmailText(subject, message, domain, level, alertType, daysRemaining, certContext);
            helper.setText(text, html);   // multipart/alternative (plain + HTML)
            return doSend(to, msg, 1);
        } catch (Exception e) {
            log.error("✗ E-posta hazırlanamadı: TO={} | HATA={}", to, e.getMessage(), e);
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
            helper.setText(
                    buildAlertEmailText(subject, message, domain, level, alertType, daysRemaining, certContext),
                    buildAlertEmailHtml(subject, message, domain, level, alertType, daysRemaining, certContext));
            return doSend(Arrays.toString(toAddresses), msg, 1);
        } catch (Exception e) {
            log.error("✗ E-posta hazırlanamadı: TO={} | HATA={}", Arrays.toString(toAddresses), e.getMessage(), e);
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
            helper.setText(
                    buildResolutionEmailText(domain, alertType, resolvedBy, resolvedAt),
                    buildResolutionEmailHtml(domain, alertType, alertLevel,
                            daysRemaining, resolvedBy, resolvedAt, createdAt, certContext));
            return doSend(Arrays.toString(toAddresses), msg, 1);
        } catch (Exception e) {
            log.error("✗ Çözüm e-postası hazırlanamadı: TO={} | HATA={}", Arrays.toString(toAddresses), e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    private String doSend(String to, MimeMessage msg, int attempt) {
        long t0 = System.currentTimeMillis();
        if (MAIL_LOG.isTraceEnabled()) {
            MAIL_LOG.trace("→ SMTP gönderim: TO={} | deneme={}/{} | {} | {}",
                    to, attempt, MAX_SEND_ATTEMPTS, describeMessage(msg), smtpContext());
        }
        try {
            currentSender().send(msg);
            long ms = System.currentTimeMillis() - t0;
            if (attempt == 1) {
                log.info("✓ E-posta gönderildi: TO={}", to);
            } else {
                log.info("✓ E-posta gönderildi (retry #{}): TO={}", attempt - 1, to);
            }
            if (MAIL_LOG.isTraceEnabled()) {
                MAIL_LOG.trace("✓ SMTP gönderim OK: TO={} | süre={}ms | messageId={} | boyut={}B",
                        to, ms, safeMessageId(msg), safeSize(msg));
            }
            // Bir async retry (attempt>1) sonunda başarılıysa: ilk denemede "QUEUED_RETRY"
            // kaydedilen log satırını SENT'e güncelle (aksi halde sahte "gönderilemedi" görünür).
            if (attempt > 1) writeBackRetryStatus(msg, "SENT");
            return "SENT";
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
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
                if (MAIL_LOG.isTraceEnabled()) {
                    // Son arg `e` (Throwable) → TRACE'te tam stack de basılır.
                    MAIL_LOG.trace("⏳ SMTP 421 ayrıntı: TO={} | süre={}ms | {} | kök sebep={}",
                            to, ms, smtpContext(), rootMessage(e), e);
                }
                // Caller'ı bloke etme; retry'ı ayrı thread'de tetikle.
                mailRetryExecutor.schedule(
                    () -> {
                        try { doSend(to, msg, attempt + 1); }
                        catch (Exception ex) {
                            log.error("✗ Async retry başarısız: TO={} | HATA={}", to, ex.getMessage(), ex);
                        }
                    },
                    delay, TimeUnit.MILLISECONDS);
                // İlk denemenin sonucu caller'a döner (sonraki retry'lar async, sonucu yutulur).
                return attempt == 1 ? "QUEUED_RETRY: " + err : "QUEUED_RETRY";
            }
            if (errFull.contains("421")) {
                log.error("✗ E-posta {} denemede de 421 rate limit ile gönderilemedi: TO={}", MAX_SEND_ATTEMPTS, to);
            }
            // Son arg `e` (Throwable) → SLF4J tam stack trace'i ERROR'a HER ZAMAN basar
            // (TRACE açmaya gerek yok). SMTP bağlamı + mesaj ayrıntısı ek olarak TRACE'te.
            log.error("✗ E-posta gönderilemedi: TO={} | süre={}ms | HATA={}", to, ms, err, e);
            if (MAIL_LOG.isTraceEnabled()) {
                MAIL_LOG.trace("✗ SMTP hata ayrıntı: TO={} | {} | {} | kök sebep={}",
                        to, describeMessage(msg), smtpContext(), rootMessage(e));
            }
            // Tüm async retry'lar (attempt>1) tükendi ve gönderilemedi: ilk denemede "QUEUED_RETRY"
            // kaydedilen log satırını gerçek FAILED durumuna güncelle (rozet bunu yakalar).
            if (attempt > 1) writeBackRetryStatus(msg, "FAILED: " + err);
            return "FAILED: " + err;
        }
    }

    /**
     * Async 421-retry'ın terminal sonucunu ("SENT" / "FAILED: ...") bildirim loguna geri-yazar:
     * ilk denemede EscalationService'in "QUEUED_RETRY..." olarak kaydettiği satırı subject ile
     * (en güncel) bulup günceller. Async retry sonuçları aksi halde yutulduğundan, log gerçeği
     * yansıtmaz ve "Alarm gönderilemedi" rozeti yanlış çalışır. Gönderimi ASLA kırmaz (try/catch).
     */
    private void writeBackRetryStatus(MimeMessage msg, String terminalStatus) {
        try {
            String subject = msg.getSubject();
            if (subject == null || subject.isBlank()) return;
            notificationLogRepo
                    .findTopBySubjectAndEmailStatusStartingWithOrderByIdDesc(subject, "QUEUED_RETRY")
                    .ifPresent(logRow -> {
                        logRow.setEmailStatus(terminalStatus);
                        notificationLogRepo.save(logRow);
                    });
        } catch (Exception e) {
            log.warn("Retry sonucu bildirim loguna yazılamadı: {}", e.getMessage());
        }
    }

    // ── Mail tanılama yardımcıları (yalnız log; mail GÖVDESİ ve SMTP PAROLASI asla loglanmaz) ──

    /** Mesaj meta verisi (alıcılar, konu, boyut) — gövde OKUNMAZ. Hata olsa bile gönderimi etkilemez. */
    private static String describeMessage(MimeMessage msg) {
        try {
            jakarta.mail.Address[] rcpts = msg.getAllRecipients();
            int size = msg.getSize();
            return "alıcılar=" + Arrays.toString(rcpts)
                    + " konu=" + msg.getSubject()
                    + " boyut=" + (size >= 0 ? size + "B" : "?");
        } catch (Exception ex) {
            return "msg=?(okunamadı: " + ex.getClass().getSimpleName() + ")";
        }
    }

    /** Etkin SMTP bağlamı — host/port/auth/TLS/timeout. PAROLA ASLA dahil edilmez. */
    private String smtpContext() {
        try {
            var s = smtpSettings.getOrDefaults();
            return "smtp=" + s.getHost() + ":" + s.getPort()
                    + " auth=" + (Boolean.TRUE.equals(s.getAuthEnabled()) ? "on" : "off")
                    + " user=" + (s.getUsername() != null ? s.getUsername() : "-")
                    + " starttls=" + Boolean.TRUE.equals(s.getStartTlsEnable())
                    + "/req=" + Boolean.TRUE.equals(s.getStartTlsRequired())
                    + " sslTrust=" + (s.getSslTrust() != null ? s.getSslTrust() : "-")
                    + " timeout(conn/read/write)=" + s.getConnectionTimeoutMs()
                    + "/" + s.getReadTimeoutMs() + "/" + s.getWriteTimeoutMs() + "ms";
        } catch (Exception ex) {
            return "smtp=?(okunamadı: " + ex.getClass().getSimpleName() + ")";
        }
    }

    /** Throwable zincirinin kök sebebine inip mesajını döndürür (mesaj boşsa sınıf adı). */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : cur.getClass().getSimpleName();
    }

    private static String safeMessageId(MimeMessage msg) {
        try { String id = msg.getMessageID(); return id != null ? id : "?"; }
        catch (Exception ex) { return "?"; }
    }

    private static String safeSize(MimeMessage msg) {
        try { int n = msg.getSize(); return n >= 0 ? String.valueOf(n) : "?"; }
        catch (Exception ex) { return "?"; }
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
            helper.setText(
                    buildResolutionEmailText(domain, alertType, resolvedBy, resolvedAt),
                    buildResolutionEmailHtml(domain, alertType, alertLevel,
                            daysRemaining, resolvedBy, resolvedAt, createdAt, certContext));
            return doSend(to, msg, 1);
        } catch (Exception e) {
            log.error("✗ Çözüm e-postası hazırlanamadı: TO={} | HATA={}", to, e.getMessage(), e);
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
            log.error("✗ Şifre sıfırlama e-postası hazırlanamadı: TO={} | HATA={}", toAddress, e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    /** Tier-3 (sistem/admin) e-postaları için sade Outlook-güvenli çerçeve: dış bgcolor tablo → ortalanmış
     *  sabit-genişlik beyaz kart → padding TD'de (Outlook div padding'ini ve max-width'i yok sayar).
     *  İç içerik (h2/p/tablo/ul) olduğu gibi bu td'ye yerleştirilir; rich builder'lardaki gibi hep-açık. */
    private String simpleFrameOpen(int maxWidth) {
        String css = "<style>@media only screen and (max-width:600px){.em-wrap{padding:0!important}"
            + ".em-card{border-radius:0!important;width:100%!important}.em-body{padding:18px!important}}</style>";
        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + LIGHT_SCHEME_META
            + "<!--[if mso]><style>table,td,div,p,a{font-family:'Segoe UI',Arial,sans-serif!important}</style><![endif]-->"
            + css + "</head>"
            + "<body style='margin:0;padding:0;background:#f1f5f9;font-family:\"Segoe UI\",Arial,sans-serif;color:#1f2937'>"
            + "<table role='presentation' class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#f1f5f9' style='background:#f1f5f9;mso-table-lspace:0pt;mso-table-rspace:0pt'>"
            + "<tr><td align='center' style='padding:24px 10px'>"
            + "<table role='presentation' class='em-card' width='" + maxWidth + "' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff' style='max-width:" + maxWidth + "px;width:100%;background:#ffffff;border:1px solid #e5e7eb;border-radius:12px;overflow:hidden'>"
            + "<tr><td class='em-body' bgcolor='#ffffff' style='padding:24px'>";
    }

    private String simpleFrameClose() {
        return "</td></tr></table></td></tr></table></body></html>";
    }

    private String buildPasswordResetHtml(String username, String displayName, String tempPwd) {
        String name = (displayName != null && !displayName.isBlank()) ? displayName : username;
        return simpleFrameOpen(560)
            + "<h2 style='color:#4f46e5;margin:0 0 12px;font-size:20px'>Şifreniz sıfırlandı</h2>"
            + "<p style='margin:0 0 10px'>Sayın <strong>" + escHtml(name) + "</strong>,</p>"
            + "<p style='margin:0 0 10px'>CertMonitor hesabınızın şifresi bir yönetici tarafından sıfırlandı.</p>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:14px 0;font-size:14px'>"
            + "<tr><td style='padding:4px 12px 4px 0;color:#64748b'>Kullanıcı adı:</td>"
            + "<td style='padding:4px 0;font-family:Consolas,\"Courier New\",monospace;font-weight:600'>" + escHtml(username) + "</td></tr>"
            + "<tr><td style='padding:4px 12px 4px 0;color:#64748b;vertical-align:top'>Geçici şifre:</td>"
            + "<td style='padding:4px 0;font-family:Consolas,\"Courier New\",monospace;font-weight:700;letter-spacing:.04em;font-size:16px'>" + escHtml(tempPwd) + "</td></tr>"
            + "</table>"
            + "<p style='margin:0 0 10px'><strong>Bu şifre 24 saat geçerlidir.</strong> Bu süre içinde giriş yapmazsanız geçici şifreniz devre dışı kalır ve yeni bir sıfırlama talep etmeniz gerekir.</p>"
            + "<p style='margin:0 0 10px'>İlk girişinizde sistem sizden kalıcı bir şifre belirlemenizi isteyecektir.</p>"
            + "<p style='font-size:13px;color:#64748b;margin:0'>Bu işlemi siz başlatmadıysanız lütfen sistem yöneticinizle iletişime geçin.</p>"
            + simpleFrameClose();
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
            log.error("✗ Admin network alert hazırlanamadı: TO={} | HATA={}", to, e.getMessage(), e);
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
            log.error("✗ Admin network resolved hazırlanamadı: TO={} | HATA={}", to, e.getMessage(), e);
            return "FAILED: " + e.getMessage();
        }
    }

    /** Admin bilgi tablosu satırı — etiket hücresi bgcolor'lı (Outlook-güvenli), değer hücresi düz. */
    private String adminRow(String label, String value) {
        return "<tr>"
            + "<td bgcolor='#f3f4f6' style='background-color:#f3f4f6;text-align:left;padding:7px 10px;border:1px solid #e5e7eb;font-weight:700;white-space:nowrap'>" + escHtml(label) + "</td>"
            + "<td style='padding:7px 10px;border:1px solid #e5e7eb'>" + value + "</td></tr>";
    }

    private String buildAdminNetworkAlertHtml(String detectedAt, int networkErrors, int total,
                                              double errorRate, double threshold) {
        String ratePct = String.format("%.0f%%", errorRate * 100);
        String threshPct = String.format("%.0f%%", threshold * 100);
        return simpleFrameOpen(640)
                + "<h2 style='color:#b91c1c;margin:0 0 12px;font-size:20px'>⚠ CertMonitor — Ağ Erişim Sorunu Tespit Edildi</h2>"
                + "<p style='font-size:14px;line-height:1.55;margin:0 0 12px'>CertMonitor host'unun bir veya daha fazla sertifika kontrolünü tamamlayamadığı tespit edildi. "
                + "Tarama turunda <strong>" + networkErrors + " / " + total + "</strong> domain ağ-class hatasıyla düştü "
                + "(oran: <strong>" + ratePct + "</strong>, eşik: " + threshPct + "). "
                + "Bu, host'un outbound bağlantısında bir problem olabileceğini gösteriyor.</p>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:16px 0;font-size:13px'>"
                + adminRow("Tespit Zamanı", formatIso(detectedAt))
                + adminRow("Etkilenen Domain", networkErrors + " / " + total)
                + adminRow("Hata Oranı", ratePct)
                + adminRow("Eşik", threshPct)
                + "</table>"
                + "<h3 style='color:#374151;font-size:15px;margin:20px 0 8px'>Sistemin Aksiyonu</h3>"
                + "<ul style='font-size:13px;line-height:1.6;margin:0 0 8px;padding-left:20px'>"
                + "<li>Yeni alarm üretimi <strong>geçici olarak duraklatıldı</strong></li>"
                + "<li>Auto-resolve işlemi <strong>askıya alındı</strong> (sahte resolved e-posta yağmuru engellenir)</li>"
                + "<li>Dashboard'da operatörlere uyarı banner'ı gösterildi</li>"
                + "</ul>"
                + "<h3 style='color:#374151;font-size:15px;margin:20px 0 8px'>Önerilen Kontroller</h3>"
                + "<ul style='font-size:13px;line-height:1.6;margin:0 0 8px;padding-left:20px'>"
                + "<li>Host'un internet bağlantısı (modem/router)</li>"
                + "<li>Outbound proxy ayarları</li>"
                + "<li>Kurumsal firewall/NAT politikaları</li>"
                + "<li>DNS sunucu erişilebilirliği</li>"
                + "</ul>"
                + "<p style='font-size:13px;color:#6b7280;margin:24px 0 0'>Ağ erişimi normale döner dönmez ayrıca bir <strong>\"Çözüldü\"</strong> e-postası alacaksınız.</p>"
                + "<hr style='border:none;border-top:1px solid #e5e7eb;margin:24px 0 12px' />"
                + "<p style='font-size:11px;color:#9ca3af;margin:0'>CertMonitor — System Admin Notification</p>"
                + simpleFrameClose();
    }

    private String buildAdminNetworkResolvedHtml(String detectedAt, String resolvedAt, long durationMs,
                                                 int networkErrors, int total, double errorRate) {
        long durationMin = durationMs / 60000;
        long durationSec = (durationMs / 1000) % 60;
        String durationStr = durationMin + " dk " + durationSec + " sn";
        String ratePct = String.format("%.0f%%", errorRate * 100);
        return simpleFrameOpen(640)
                + "<h2 style='color:#15803d;margin:0 0 12px;font-size:20px'>✅ CertMonitor — Ağ Erişim Sorunu Çözüldü</h2>"
                + "<p style='font-size:14px;line-height:1.55;margin:0 0 12px'>CertMonitor host'unun outbound bağlantı sorunu çözüldü. "
                + "Sertifika kontrolleri normal işleyişe döndü.</p>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:16px 0;font-size:13px'>"
                + adminRow("Tespit Zamanı", formatIso(detectedAt))
                + adminRow("Çözüm Zamanı", formatIso(resolvedAt))
                + adminRow("Toplam Süre", durationStr)
                + adminRow("Tespit Anında Etkilenen", networkErrors + " / " + total + " (" + ratePct + ")")
                + "</table>"
                + "<h3 style='color:#374151;font-size:15px;margin:20px 0 8px'>Sistemin Aksiyonu</h3>"
                + "<ul style='font-size:13px;line-height:1.6;margin:0 0 8px;padding-left:20px'>"
                + "<li>Yeni alarm üretimi <strong>yeniden aktif</strong></li>"
                + "<li>Auto-resolve işlemi <strong>yeniden aktif</strong></li>"
                + "<li>Dashboard uyarı banner'ı kaldırıldı</li>"
                + "</ul>"
                // Renkli not: <p background> Outlook'ta beyaza düşer → accent-şeritli tablo (td bgcolor)
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:16px 0 0;border-radius:8px;overflow:hidden'><tr>"
                + "<td width='4' bgcolor='#16a34a' style='background-color:#16a34a;width:4px;font-size:0;line-height:0'>&nbsp;</td>"
                + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:10px 14px;font-size:13px;color:#15803d'>"
                + "<em>Not: Outage süresince üretilebilecek sahte alarmlar bastırıldığı için ekibinize ÇÖZÜLDÜ e-posta yağmuru gönderilmedi.</em></td></tr></table>"
                + "<hr style='border:none;border-top:1px solid #e5e7eb;margin:24px 0 12px' />"
                + "<p style='font-size:11px;color:#9ca3af;margin:0'>CertMonitor — System Admin Notification</p>"
                + simpleFrameClose();
    }

    // ── Public HTML accessors (used to store sent HTML in notification log) ──

    private static final java.util.Set<String> MONITORING_OUTAGE_TYPES =
            java.util.Set.of("ACCESSIBILITY", "PORT_DOWN", "DNS_FAILURE");

    /** Süre-bitişi ailesi (alan adı + sertifika) executive-premium şablondan (EmailTemplateBuilder) geçer;
     *  izleme-kesintisi tipleri (uptime/port/dns/keyword/ping/dns-changed) tip-özel zengin şablonlarını korur. */
    public String buildAlertEmailHtml(String subject, String message,
                                       String domain, String level, String alertType,
                                       Integer daysRemaining, Map<String, Object> certContext) {
        if (alertType != null && MONITORING_OUTAGE_TYPES.contains(alertType)) {
            return buildRichMonitoringOutageAlertHtml(message, domain, alertType, certContext);
        }
        if ("KEYWORD".equals(alertType)) return buildRichKeywordAlertHtml(message, domain, level, certContext);
        if ("PING_DOWN".equals(alertType)) return buildRichPingAlertHtml(message, domain, level, certContext);
        if ("DNS_CHANGED".equals(alertType)) return buildRichDnsChangedAlertHtml(message, domain, certContext);
        if (domain == null) return buildSimpleAlertHtml(subject, message);
        // Kalan hepsi = süre-bitişi ailesi (DOMAINMON_*, DOMAIN_EXPIRY, sertifika EXPIRY/REVOKED/MISMATCH/CHAIN) → executive
        return templateBuilder.buildHtml(new EmailTemplateBuilder.AlertMail(
                alertType, level, domain, message, daysRemaining, certContext, teamNameOf(certContext)));
    }

    /** Alarm e-postasının plain-text (multipart) alternatifi. */
    public String buildAlertEmailText(String subject, String message,
                                      String domain, String level, String alertType,
                                      Integer daysRemaining, Map<String, Object> certContext) {
        return templateBuilder.buildText(new EmailTemplateBuilder.AlertMail(
                alertType, level, domain, message, daysRemaining, certContext, teamNameOf(certContext)));
    }

    public String buildResolutionEmailHtml(String domain, String alertType, String alertLevel,
                                            Integer daysRemaining, String resolvedBy,
                                            String resolvedAt, String createdAt,
                                            Map<String, Object> certContext) {
        if ("KEYWORD".equals(alertType)) return buildRichKeywordResolvedHtml(domain, certContext, resolvedBy, resolvedAt, createdAt);
        if ("PING_DOWN".equals(alertType)) return buildRichPingResolvedHtml(domain, certContext, resolvedBy, resolvedAt, createdAt);
        if ((alertType != null && MONITORING_OUTAGE_TYPES.contains(alertType)) || "DNS_CHANGED".equals(alertType)) {
            return buildRichMonitoringResolvedHtml(domain, alertType, resolvedBy, resolvedAt, createdAt);
        }
        // domain + sertifika → executive "çözüldü"
        return templateBuilder.buildResolvedHtml(domain, alertType, resolvedBy, resolvedAt);
    }

    /** Çözüm e-postasının plain-text (multipart) alternatifi. */
    public String buildResolutionEmailText(String domain, String alertType, String resolvedBy, String resolvedAt) {
        return templateBuilder.buildResolvedText(domain, alertType, resolvedBy, resolvedAt);
    }

    private static String teamNameOf(Map<String, Object> ctx) {
        if (ctx == null) return null;
        Object v = ctx.get("team_name");
        return v == null ? null : String.valueOf(v);
    }

    /** @deprecated Eski tip-özel şablonlar EmailTemplateBuilder ile değiştirildi; referans için tutuldu. */
    @Deprecated
    private String legacyResolution(String domain, String alertType, String alertLevel,
                                    Integer daysRemaining, String resolvedBy,
                                    String resolvedAt, String createdAt,
                                    Map<String, Object> certContext) {
        return buildRichResolvedHtml(domain, alertType, alertLevel,
                daysRemaining, resolvedBy, resolvedAt, createdAt, certContext);
    }

    /** Alan adı (registrar süre bitişi / veri yok / EPP durum / değişiklik) alarmları — sertifika alanları
     *  (issuer/subject/fingerprint) YOK; RDAP/WHOIS bağlamı (expiry/registrar/EPP kodları/sebep) ile. */
    static boolean isDomainAlertType(String alertType) {
        return alertType != null
                && (alertType.startsWith("DOMAINMON_") || "DOMAIN_EXPIRY".equals(alertType));
    }

    /** Alan adı alarm tipi → hero etiketi (Türkçe). */
    private static String domainTypeLabel(String alertType) {
        if (alertType == null) return "ALAN ADI UYARISI";
        return switch (alertType) {
            case "DOMAINMON_UNKNOWN" -> "ALAN ADI VERİ YOK";
            case "DOMAINMON_EXPIRY", "DOMAIN_EXPIRY" -> "ALAN ADI SÜRE BİTİŞİ";
            case "DOMAINMON_STATUS"  -> "ALAN ADI DURUM KODU UYARISI";
            case "DOMAINMON_CHANGED" -> "ALAN ADI DEĞİŞİKLİĞİ";
            default -> "ALAN ADI UYARISI";
        };
    }

    private static String domCtx(Map<String, Object> ctx, String key) {
        if (ctx == null) return null;
        Object v = ctx.get(key);
        return (v == null || String.valueOf(v).isBlank()) ? null : String.valueOf(v);
    }

    /** Alan adı alarmı e-postası — sertifika şablonu yerine RDAP/WHOIS bağlamına uygun içerik. */
    private String buildRichDomainAlertHtml(String message, String domain, String level,
                                            String alertType, Integer daysRemaining, Map<String, Object> ctx) {
        boolean critical = "CRITICAL".equalsIgnoreCase(level);
        String accent = critical ? "#b91c1c" : "#b45309";      // kırmızı (CRITICAL) / kehribar (WARNING)
        String label = domainTypeLabel(alertType);
        String days = daysRemaining != null ? String.valueOf(daysRemaining) : domCtx(ctx, "days");
        String expiry = domCtx(ctx, "expiry_date");
        String registrar = domCtx(ctx, "registrar");
        String statusCodes = domCtx(ctx, "status_codes");
        String changeDetail = domCtx(ctx, "change_detail");
        String lastError = domCtx(ctx, "last_error");

        StringBuilder rows = new StringBuilder();
        rows.append(adminRow("Alan Adı", "<strong>" + escHtml(domain) + "</strong>"));
        if (days != null)         rows.append(adminRow("Kalan Gün", escHtml(days)));
        if (expiry != null)       rows.append(adminRow("Bitiş Tarihi", escHtml(expiry)));
        if (registrar != null)    rows.append(adminRow("Registrar", escHtml(registrar)));
        if (statusCodes != null)  rows.append(adminRow("EPP Durum Kodları", escHtml(statusCodes)));
        if (changeDetail != null) rows.append(adminRow("Değişiklik", escHtml(changeDetail)));
        if (lastError != null)    rows.append(adminRow("Sebep", escHtml(lastError)));

        String body = message == null ? "" : escHtml(message).replace("\n", "<br>");

        return simpleFrameOpen(640)
                // Hero rozeti — bgcolor'lı TD (Outlook-güvenli), div-bg değil.
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:0 0 16px'>"
                + "<tr><td bgcolor='" + accent + "' style='background-color:" + accent + ";color:#ffffff;padding:14px 16px;border-radius:8px;font-size:16px;font-weight:700;text-align:center'>"
                + "🌐 " + label + "</td></tr></table>"
                + "<h2 style='color:#111827;margin:0 0 6px;font-size:20px'>" + escHtml(domain) + "</h2>"
                + "<p style='font-size:12px;color:#6b7280;margin:0 0 14px'>CertMonitor — Alan Adı İzleme</p>"
                + (body.isBlank() ? "" : "<p style='font-size:14px;line-height:1.55;margin:0 0 14px'>" + body + "</p>")
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:8px 0 4px;font-size:13px'>"
                + rows
                + "</table>"
                + "<hr style='border:none;border-top:1px solid #e5e7eb;margin:22px 0 12px' />"
                + "<p style='font-size:11px;color:#9ca3af;margin:0'>CertMonitor — Alan Adı (Domain) Süre Bitişi İzleme</p>"
                + simpleFrameClose();
    }

    /** Alan adı alarmı "Çözüldü" e-postası. */
    private String buildRichDomainResolvedHtml(String domain, String alertType, Integer daysRemaining,
                                               String resolvedBy, String resolvedAt, String createdAt,
                                               Map<String, Object> ctx) {
        String registrar = domCtx(ctx, "registrar");
        String expiry = domCtx(ctx, "expiry_date");
        String days = daysRemaining != null ? String.valueOf(daysRemaining) : domCtx(ctx, "days");

        StringBuilder rows = new StringBuilder();
        rows.append(adminRow("Alan Adı", "<strong>" + escHtml(domain) + "</strong>"));
        if (days != null)      rows.append(adminRow("Kalan Gün", escHtml(days)));
        if (expiry != null)    rows.append(adminRow("Bitiş Tarihi", escHtml(expiry)));
        if (registrar != null) rows.append(adminRow("Registrar", escHtml(registrar)));
        if (createdAt != null)  rows.append(adminRow("Başlangıç", formatIso(createdAt)));
        if (resolvedAt != null) rows.append(adminRow("Çözülme", formatIso(resolvedAt)));
        if (resolvedBy != null && !resolvedBy.isBlank()) rows.append(adminRow("Çözen", escHtml(resolvedBy)));

        return simpleFrameOpen(640)
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:0 0 16px'>"
                + "<tr><td bgcolor='#15803d' style='background-color:#15803d;color:#ffffff;padding:14px 16px;border-radius:8px;font-size:16px;font-weight:700;text-align:center'>"
                + "✅ ALAN ADI UYARISI ÇÖZÜLDÜ</td></tr></table>"
                + "<h2 style='color:#111827;margin:0 0 6px;font-size:20px'>" + escHtml(domain) + "</h2>"
                + "<p style='font-size:12px;color:#6b7280;margin:0 0 14px'>CertMonitor — Alan Adı İzleme</p>"
                + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-collapse:collapse;margin:8px 0 4px;font-size:13px'>"
                + rows
                + "</table>"
                + "<hr style='border:none;border-top:1px solid #e5e7eb;margin:22px 0 12px' />"
                + "<p style='font-size:11px;color:#9ca3af;margin:0'>CertMonitor — Alan Adı (Domain) Süre Bitişi İzleme</p>"
                + simpleFrameClose();
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
            default             -> "Sertifika Süre Bitişi";
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

            expiryHero = "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
                + " style='margin:20px 0;border-radius:14px;overflow:hidden;border:2px solid " + dc + "'>"
                + "<tr>"
                + "<td class='em-hero-l' align='center' valign='middle' width='38%'"
                + " bgcolor='" + dc + "' style='background-color:" + dc + ";padding:22px 14px'>"
                + "<div style='color:#fff;font-size:64px;font-weight:900;line-height:1;letter-spacing:-2px'>" + daysRemaining + "</div>"
                + "<div style='color:#fff;font-size:14px;font-weight:800;margin-top:4px;letter-spacing:.06em'>GÜN KALDI</div>"
                + "<div style='color:#ffffff;font-size:12px;margin-top:8px;padding:0 6px'>" + urgencyMsg + "</div>"
                + "</td>"
                + "<td class='em-hero-r' valign='middle' bgcolor='#f8fafc' style='background-color:#f8fafc;padding:20px 22px'>"
                + "<div style='font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8;text-transform:uppercase;margin-bottom:10px'>Sertifikanın Geçerlilik Bitiş Tarihi</div>"
                + "<div style='font-size:24px;font-weight:900;color:" + dc + ";letter-spacing:-.5px'>" + formatIso(notAfter) + "</div>"
                + "<div style='font-size:13px;color:#475569;margin-top:6px;line-height:1.6'>" + formatIsoFull(notAfter) + "</div>"
                + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='margin-top:12px'><tr>"
                + "<td bgcolor='" + dc + "' style='background-color:" + dc + ";border-radius:6px;padding:6px 12px;font-size:12px;font-weight:800;color:#fff'>"
                + "📅 " + daysRemaining + " gün sonra geçerliliği sona eriyor</td>"
                + "</tr></table>"
                + "</td>"
                + "</tr></table>";
        } else if (!"EXPIRY".equals(alertType)) {
            // Rozet: inline-block div Outlook'ta stilsiz düz metne çöker → ortalanmış iç-td pill (DNS hero deseni)
            expiryHero = "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:20px 0'><tr><td align='center'>"
                + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' align='center'><tr>"
                + "<td bgcolor='" + accentColor + "' style='background-color:" + accentColor + ";border-radius:12px;padding:14px 32px;font-size:17px;font-weight:800;letter-spacing:.02em;color:#fff'>"
                + typeIcon + " " + typeTr.toUpperCase() + " TESPİT EDİLDİ"
                + "</td></tr></table>"
                + "</td></tr></table>";
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
            + "<td class='em-col-l' valign='top' width='55%' style='width:55%;padding-right:8px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#1e293b' style='background-color:#1e293b;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>SERTİFİKA BİLGİLERİ</td></tr>"
            + certRows + "</table></td>"
            + "<td class='em-col-r' valign='top' width='45%' style='width:45%'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#334155' style='background-color:#334155;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>DURUM ÖZETİ</td></tr>"
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
            + "<meta name='color-scheme' content='light only'>"
            + "<meta name='supported-color-schemes' content='light'>"
            + "<!--[if mso]><style>*{font-family:Arial,Helvetica,sans-serif !important}</style><![endif]-->"
            + css
            + "</head>"
            + "<body style='margin:0;padding:0;background-color:#f1f5f9;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            // Dış ortalama tablosu (Outlook-safe: bgcolor + mso)
            + "<table role='presentation' class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#f1f5f9' style='background-color:#f1f5f9;mso-table-lspace:0;mso-table-rspace:0'>"
            + "<tr><td align='center' style='padding:24px 10px'>"

            // Kart — max 640px, mobilde tam genişlik
            + "<table role='presentation' class='em-card' width='640' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'"
            + " style='width:640px;max-width:640px;background-color:#ffffff;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.15)'>"

            // ── Top bar — bgcolor'lı <td> ──
            + "<tr><td bgcolor='" + accentColor + "' style='background-color:" + accentColor + ";padding:22px 24px'>"
            + "<div style='color:#ffffff;font-size:11px;font-weight:700;letter-spacing:.12em'>CertMonitor — Sertifika İzleme</div>"
            + "<div class='em-domain' style='color:#fff;font-size:22px;font-weight:900;"
            + "margin-top:10px;word-break:break-all;line-height:1.25'>🌐 " + escHtml(domain) + "</div>"
            + "<div style='color:#ffffff;font-size:15px;font-weight:700;"
            + "margin-top:8px;letter-spacing:.02em'>" + levelTr + " &nbsp;&#183;&nbsp; " + typeTr + "</div>"
            + "</td></tr>"

            // ── Body ──
            + "<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:22px 24px'>"
            + expiryHero
            + twoColSection

            // Alarm detayı (sol aksan-şeritli tablo — border-left div değil)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:10px;overflow:hidden;margin-bottom:20px'><tr>"
            + "<td width='5' bgcolor='" + accentColor + "' style='background-color:" + accentColor + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#fffbeb' style='background-color:#fffbeb;padding:14px 18px;color:#1c1917;font-size:14px;line-height:1.7'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + accentColor + ";margin-bottom:6px'>ALARM DETAYI</div>"
            + escHtml(message)
            + "</td></tr></table>"

            // Footer
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + (!checkedAtDisplay.equals("—")
                ? "<span class='em-footer-check'>" + "Son kontrol: " + checkedAtDisplay + " &nbsp;&middot;&nbsp;</span>" : "")
            + " Bildirim: " + generatedAt
            + "</td></tr></table>"

            + "</td></tr>"

            + "</table>"  // em-card
            + "</td></tr></table>"  // em-wrap
            + "</body></html>";
    }

    private String tableRow2col(String label, String value) {
        return "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td width='1%' style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>" + label + "</td>"
            + "<td style='padding:9px 13px;font-size:14px;font-weight:600;color:#1e293b;word-break:break-all'>" + value + "</td>"
            + "</tr>";
    }

    private String tableRow2colMono(String label, String value) {
        return "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td width='1%' style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>" + label + "</td>"
            + "<td style='padding:9px 13px;font-size:11px;font-family:monospace;color:#475569;word-break:break-all'>" + value + "</td>"
            + "</tr>";
    }

    private String statusRow2col(String label, String value) {
        boolean ok      = value.startsWith("✓");
        boolean neutral = value.startsWith("~") || value.startsWith("—");
        String bgAttr  = ok || neutral ? "" : " bgcolor='#fef2f2'";
        String bgStyle = ok || neutral ? "" : "background-color:#fef2f2;";
        String valueColor = ok ? "#15803d" : neutral ? "#6b7280" : "#dc2626";
        return "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td width='1%'" + bgAttr + " style='" + bgStyle + "padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>" + label + "</td>"
            + "<td" + bgAttr + " style='" + bgStyle + "padding:9px 13px;font-size:13px;font-weight:700;color:" + valueColor + "'>" + value + "</td>"
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
        String bg         = ok || neutral ? "" : "background:#fef2f2";
        String valueColor = ok ? "#15803d" : neutral ? "#6b7280" : "#dc2626";
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
        return "<tr style='border-top:1px solid #e2e8f0;background:#fef2f2'>"
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

    /** Apple Mail/iOS dark-mode oto-inversiyonunu kapatır (beyaz kartlar kararmaz → hep-açık WebKit preview'a uyum).
     *  Tüm builder head'lerinde viewport meta'sından sonra aynı iki satır kullanılır. */
    private static final String LIGHT_SCHEME_META =
            "<meta name='color-scheme' content='light only'><meta name='supported-color-schemes' content='light'>";

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

    /** Outlook (Word/VML v:roundrect — yuvarlak köşe + TAM-ALAN tıklanır) + diğer istemciler (HTML &lt;a&gt;) için
     *  çift "bulletproof" CTA. mso/non-mso koşullu yorumlarıyla her istemci yalnız kendi sürümünü görür.
     *  [if !mso] dalı eski çıktının bayt-bayt aynısı → Outlook-dışı (Apple Mail/Gmail/preview) SIFIR regresyon.
     *  href + w:anchorlock roundrect ELEMENTİNDE → eski "bazı Outlook'ta tıklanmıyordu" sorunu çözülür
     *  (aynı desen buildWeeklyReportReminderHtml'de kanıtlı çalışıyor). Genişlik VML'de sabit olmalı → vmlButtonWidth. */
    private String ctaButton(String url, String label, String accent) {
        if (url == null || url.isBlank()) return "";
        String safe = escHtml(url);
        int w = vmlButtonWidth(label);
        return "<!--[if mso]>"
            + "<table role='presentation' border='0' cellspacing='0' cellpadding='0' align='center' style='margin:0 auto'><tr><td align='center'>"
            + "<v:roundrect xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:w=\"urn:schemas-microsoft-com:office:word\""
            + " href=\"" + safe + "\" style=\"height:44px;v-text-anchor:middle;width:" + w + "px;\""
            + " arcsize=\"16%\" strokecolor=\"" + accent + "\" fillcolor=\"" + accent + "\">"
            + "<w:anchorlock/>"
            + "<center style=\"color:#ffffff;font-family:'Segoe UI',Tahoma,Arial,sans-serif;font-size:15px;font-weight:bold;\">"
            + label + "</center>"
            + "</v:roundrect>"
            + "</td></tr></table>"
            + "<![endif]-->"
            + "<!--[if !mso]><!-->"
            + "<table role='presentation' border='0' cellspacing='0' cellpadding='0' align='center' style='margin:0 auto'><tr>"
            + "<td align='center' bgcolor='" + accent + "' style='background:" + accent + ";border-radius:8px;mso-padding-alt:14px 32px'>"
            + "<a href='" + safe + "' target='_blank' style='display:inline-block;padding:14px 32px;"
            + "color:#ffffff;text-decoration:none;font-size:15px;font-weight:bold;border-radius:8px;"
            + "font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>" + label + "</a>"
            + "</td></tr></table>"
            + "<!--<![endif]-->";
    }

    /** VML v:roundrect auto-size yapamaz → görünür etiket uzunluğundan px genişlik türet
     *  (HTML entity'ler ve olası etiketler ~1 karakter sayılır). Fazla tahmin butonu genişletir, asla kırpmaz. */
    private static int vmlButtonWidth(String label) {
        String visible = label == null ? "" : label
                .replaceAll("&[a-zA-Z]+;|&#\\d+;", "x")   // &rarr; &nbsp; &#183; → tek karakter
                .replaceAll("<[^>]+>", "");                // olası inline etiket
        int w = visible.length() * 9 + 56;
        return Math.max(200, Math.min(600, w));
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
            + "<head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" + LIGHT_SCHEME_META + css + "</head>"
            + "<body style='margin:0;padding:0;background:#f3f4f6;font-family:\"Segoe UI\",Arial,sans-serif'>"
            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0' style='background:#f3f4f6;padding:24px 10px'>"
            + "<tr><td align='center'>"
            + "<table class='em-card' width='600' cellpadding='0' cellspacing='0' border='0'"
            + " style='max-width:600px;width:100%;background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,.12)'>"
            + "<tr><td bgcolor='" + color + "' style='background-color:" + color + ";padding:20px 24px;color:#fff'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.1em;color:#ffffff'>CertMonitor — Sertifika İzleme</div>"
            + "<div style='font-size:18px;font-weight:800;margin-top:4px;word-break:break-word'>" + escHtml(subject) + "</div>"
            + "</td></tr>"
            + "<tr><td class='em-body' style='padding:24px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:0 8px 8px 0;overflow:hidden'><tr>"
            + "<td width='4' bgcolor='" + color + "' style='background-color:" + color + ";width:4px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#fffbeb' style='background-color:#fffbeb;padding:14px 16px;color:#1c1917;font-size:14px;line-height:1.7'>"
            + escHtml(message) + "</td></tr></table>"
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
            default             -> "Sertifika Süre Bitişi";
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
            + "<td width='1%' style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>🔴 Alarm Seviyesi</td>"
            + "<td style='padding:9px 13px;font-size:14px;font-weight:700;color:" + levelColor + "'>" + levelTr + "</td>"
            + "</tr>"
            + tableRow2col("🌐 Alan Adı",         escHtml(domain))
            + (!notAfter.isEmpty()
                ? tableRow2col("📅 Sertifika Bitiş", formatIso(notAfter)) : "")
            + (!issuerDisplay.equals("—")
                ? tableRow2col("🏢 Veren Kurum",     escHtml(issuerDisplay)) : "");

        String twoColSection =
            "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:4px'><tr>"
            + "<td class='em-col-l' valign='top' width='50%' style='width:50%;padding-right:8px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='border:1px solid #bbf7d0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#15803d' style='background-color:#15803d;padding:9px 14px;font-size:11px;font-weight:700;"
            + "letter-spacing:.1em;color:#dcfce7'>&#10003; ÇÖZÜM BİLGİSİ</td></tr>"
            + resolverRows + "</table></td>"
            + "<td class='em-col-r' valign='top' width='50%' style='width:50%'>"
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

        // E-posta CTA: port/dns alarmında monitör detay deep-link'i (?tab=&monitor=<id>);
        // accessibility (uptime) monitör-id taşımaz → CTA boş kalır.
        String outageTab = "PORT_DOWN".equals(alertType) ? "port" : "DNS_FAILURE".equals(alertType) ? "dns" : null;
        String ctaHtml = (outageTab != null && !monitorCtaUrl(outageTab, ctx).isBlank())
                ? "<div style='text-align:center;margin-bottom:20px'>"
                  + ctaButton(monitorCtaUrl(outageTab, ctx), "Monitörü Aç &rarr;", "#1e293b") + "</div>"
                : "";

        // ── Hero — kesinti bildirimi ──
        String hero = "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='margin:20px 0;border-radius:14px;overflow:hidden;border:2px solid #fecaca'>"
            + "<tr>"
            + "<td class='em-hero-l' align='center' valign='middle' width='38%'"
            + " bgcolor='" + red + "' style='background-color:" + red + ";padding:22px 14px'>"
            + "<div style='color:#fff;font-size:52px;line-height:1'>🔴</div>"
            + "<div style='color:#fff;font-size:15px;font-weight:800;margin-top:8px;letter-spacing:.06em'>" + heroTitle + "</div>"
            + "<div style='color:#ffe4e6;font-size:12px;margin-top:8px;padding:0 6px'>⚠ Acil müdahale gerekli</div>"
            + "</td>"
            + "<td class='em-hero-r' valign='middle' bgcolor='#fef2f2' style='background-color:#fef2f2;padding:20px 22px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8;text-transform:uppercase;margin-bottom:10px'>İlk Hata Zamanı</div>"
            + "<div style='font-size:24px;font-weight:900;color:" + red + ";letter-spacing:-.5px'>" + formatIso(firstFailureAt) + "</div>"
            + "<div style='font-size:13px;color:#475569;margin-top:6px;line-height:1.6'>" + formatIsoFull(firstFailureAt) + "</div>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' style='margin-top:12px'><tr>"
            + "<td bgcolor='" + red + "' style='background-color:" + red + ";border-radius:6px;padding:6px 12px;font-size:12px;font-weight:800;color:#fff'>"
            + "🔁 " + attemptsLabel + " doğrulama denemesi " + delayLabel + "— tümü başarısız</td>"
            + "</tr></table>"
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
            + "<td class='em-col-l' valign='top' width='55%' style='width:55%;padding-right:8px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#1e293b' style='background-color:#1e293b;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>KESİNTİ BİLGİLERİ</td></tr>"
            + outageRows + "</table></td>"
            + "<td class='em-col-r' valign='top' width='45%' style='width:45%'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#334155' style='background-color:#334155;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>DURUM ÖZETİ</td></tr>"
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
            + "<meta name='color-scheme' content='light only'>"
            + "<meta name='supported-color-schemes' content='light'>"
            + "<!--[if mso]><style>*{font-family:Arial,Helvetica,sans-serif !important}</style><![endif]-->"
            + css + "</head>"
            + "<body style='margin:0;padding:0;background-color:#f1f5f9;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table role='presentation' class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#f1f5f9' style='background-color:#f1f5f9;mso-table-lspace:0;mso-table-rspace:0'>"
            + "<tr><td align='center' style='padding:24px 10px'>"

            + "<table role='presentation' class='em-card' width='640' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'"
            + " style='width:640px;max-width:640px;background-color:#ffffff;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.15)'>"

            // ── Top bar (kırmızı) — bgcolor'lı <td> ──
            + "<tr><td bgcolor='" + red + "' style='background-color:" + red + ";padding:22px 24px'>"
            + "<div style='color:#ffe4e6;font-size:11px;font-weight:700;letter-spacing:.12em'>" + kicker + "</div>"
            + "<div class='em-domain' style='color:#ffffff;font-size:22px;font-weight:900;"
            + "margin-top:10px;word-break:break-all;line-height:1.25'>🌐 " + endpoint + "</div>"
            + "<div style='color:#ffe4e6;font-size:15px;font-weight:700;"
            + "margin-top:8px;letter-spacing:.02em'>KRİTİK &nbsp;&#183;&nbsp; " + typeTrLabel + "</div>"
            + "</td></tr>"

            // ── Body ──
            + "<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:22px 24px'>"
            + hero
            + twoColSection

            // Alarm detayı (sol aksan-şeritli tablo — border-left div değil)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:10px;overflow:hidden;margin-bottom:14px'><tr>"
            + "<td width='5' bgcolor='" + red + "' style='background-color:" + red + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#fef2f2' style='background-color:#fef2f2;padding:14px 18px;color:#1c1917;font-size:14px;line-height:1.7'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + red + ";margin-bottom:6px'>ALARM DETAYI</div>"
            + escHtml(message)
            + "</td></tr></table>"

            // Otomatik kapanış notu
            + "<div style='font-size:12px;color:#64748b;line-height:1.6;margin-bottom:20px'>"
            + "ℹ Sorun düzeldiğinde bu alarm otomatik kapatılır ve çözüm e-postası gönderilir. "
            + "Alarmı CertMonitor &rarr; Uyarılar &rarr; Alarm Geçmişi ekranından onaylayabilir veya kapatabilirsiniz."
            + "</div>"

            + ctaHtml

            // Footer
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + "Bildirim: " + generatedAt
            + "</td></tr></table>"

            + "</td></tr>"

            + "</table>"             // em-card
            + "</td></tr></table>"   // dış sarmalayıcı
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
            + "<td width='1%' style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>🔴 Seviye</td>"
            + "<td style='padding:9px 13px;font-size:14px;font-weight:700;color:" + levelColor + "'>" + levelTrLabel + "</td>"
            + "</tr>"
            + "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td width='1%' bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>" + durationLabel + "</td>"
            + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:9px 13px;font-size:14px;font-weight:800;color:" + green + "'>" + duration + "</td>"
            + "</tr>";

        String twoColSection =
            "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px'><tr>"
            + "<td class='em-col-l' valign='top' width='50%' style='width:50%;padding-right:8px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='border:1px solid #bbf7d0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#15803d' style='background-color:#15803d;padding:9px 14px;font-size:11px;font-weight:700;"
            + "letter-spacing:.1em;color:#dcfce7'>ÇÖZÜM BİLGİSİ</td></tr>"
            + resolverRows + "</table></td>"
            + "<td class='em-col-r' valign='top' width='50%' style='width:50%'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#334155' style='background-color:#334155;padding:9px 14px;font-size:11px;font-weight:700;"
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
            + "<meta name='color-scheme' content='light only'>"
            + "<meta name='supported-color-schemes' content='light'>"
            + "<!--[if mso]><style>*{font-family:Arial,Helvetica,sans-serif !important}</style><![endif]-->"
            + css + "</head>"
            + "<body style='margin:0;padding:0;background-color:#f1f5f9;"
            + "font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table role='presentation' class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#f1f5f9' style='background-color:#f1f5f9;mso-table-lspace:0;mso-table-rspace:0'>"
            + "<tr><td align='center' style='padding:24px 10px'>"

            + "<table role='presentation' class='em-card' width='640' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'"
            + " style='width:640px;max-width:640px;background-color:#ffffff;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.15)'>"

            // ── Top bar (yeşil) — bgcolor'lı <td> (Outlook-safe) ──
            + "<tr><td bgcolor='" + green + "' style='background-color:" + green + ";padding:22px 24px'>"
            + "<div style='color:#dcfce7;font-size:11px;font-weight:700;letter-spacing:.12em'>" + kicker + "</div>"
            + "<div class='em-domain' style='color:#ffffff;font-size:22px;font-weight:900;"
            + "margin-top:10px;word-break:break-all;line-height:1.25'>&#9989; " + escHtml(domain) + "</div>"
            + "<div style='color:#eafff1;font-size:15px;font-weight:700;"
            + "margin-top:8px;letter-spacing:.02em'>" + heroLine + " &nbsp;&#183;&nbsp; " + typeTrLabel + "</div>"
            + "</td></tr>"

            // ── Hero ✓ (daire = nested <td bgcolor>, div+border-radius değil) ──
            + "<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:22px 24px 6px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr><td align='center'>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' align='center'><tr>"
            + "<td width='80' height='80' align='center' valign='middle' bgcolor='#dcfce7'"
            + " style='background-color:#dcfce7;border-radius:40px;border:3px solid " + green + ";"
            + "color:#15803d;font-size:42px;line-height:80px;text-align:center'>&#10003;</td>"
            + "</tr></table>"
            + "<div style='margin-top:14px;font-size:20px;font-weight:800;color:#15803d;letter-spacing:-.3px'>" + heroLine + "</div>"
            + "<div style='margin-top:6px;font-size:13px;color:#64748b'>Alarm kapatıldı. İzleme devam etmektedir.</div>"
            + "</td></tr></table></td></tr>"

            // ── İki kolon detay ──
            + "<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:18px 24px 4px'>" + twoColSection + "</td></tr>"

            // ── Bilgi kutusu (sol aksan-şeritli tablo — border-left div değil) ──
            + "<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:0 24px 22px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:10px;overflow:hidden'><tr>"
            + "<td width='5' bgcolor='" + green + "' style='background-color:" + green + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:14px 18px;color:#14532d;font-size:14px;line-height:1.7'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + green + ";margin-bottom:6px'>BİLGİ</div>"
            + "<strong>" + escHtml(domain) + "</strong> için açık olan <strong>" + typeTrLabel
            + "</strong> alarmı kapatıldı. "
            + (dnsChanged ? "Alarm süresi: " : "Toplam kesinti süresi: ")
            + "<strong>" + duration + "</strong>."
            + "</td></tr></table></td></tr>"

            // ── Footer ──
            + "<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:0 24px 18px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;"
            + "font-size:11px;color:#94a3b8'>Bildirim: " + generatedAt + "</td>"
            + "</tr></table></td></tr>"

            + "</table>"             // em-card
            + "</td></tr></table>"   // dış sarmalayıcı
            + "</body></html>";
    }

    // ── Alarm Fırtınası (Alert Storm) — çok monitör birden düştüğünde TEK toplu bildirim ──
    // Outlook-safe: td+bgcolor (div bg değil), solid hex, LIGHT_SCHEME_META, mso font fallback, ctaButton VML.

    /** CANLI base-url'den olay (incidents) ekranına deep-link — reminder/approve/incident ile AYNI kaynak. */
    private String stormCtaUrl() {
        String url = appSettings.getString("cert.monitor.app.base-url", appBaseUrl);
        String base = (url != null && !url.isBlank()) ? url.replaceAll("/+$", "") : "";
        return base.isEmpty() ? "" : base + "/?tab=incidents";
    }

    private String stormTargetRows(List<String> targets, int truncatedExtra, String zebra) {
        StringBuilder sb = new StringBuilder();
        for (String t : targets) {
            sb.append("<tr><td bgcolor='").append(zebra).append("' style='background-color:").append(zebra)
              .append(";padding:8px 14px;font-size:13px;color:#1c1917;border-top:1px solid #f1f5f9;word-break:break-all'>")
              .append("🔴 ").append(escHtml(t)).append("</td></tr>");
        }
        if (truncatedExtra > 0) {
            sb.append("<tr><td bgcolor='").append(zebra).append("' style='background-color:").append(zebra)
              .append(";padding:8px 14px;font-size:12px;color:#64748b;border-top:1px solid #f1f5f9;font-style:italic'>")
              .append("+ ").append(truncatedExtra).append(" monitör daha…</td></tr>");
        }
        return sb.toString();
    }

    /**
     * Toplu alarm fırtınası e-postası — {@code monitorCount} monitör birden erişilemez.
     * StormService promotion (INITIAL) + günlük toplu re-alert (DAILY_REALERT) bunu kullanır.
     */
    public String buildStormAlertHtml(int monitorCount, String scopeLabel, String rootCauseLabel,
                                      String startedAt, List<String> sampleTargets, int truncatedExtra) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String red = "#dc2626";
        String ctaUrl = stormCtaUrl();
        String cta = (!ctaUrl.isBlank())
            ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:4px 0 18px'><tr><td align='center'>"
              + ctaButton(ctaUrl, "Olayları Aç &rarr;", "#1e293b") + "</td></tr></table>"
            : "";

        String infoRows = tableRow2col("🌐 Kapsam",       escHtml(scopeLabel))
            + tableRow2col("🧭 Ortak Kök-Neden", escHtml(rootCauseLabel))
            + tableRow2col("🕐 Başlangıç",       fmtOrDash(formatIstanbul(startedAt)))
            + "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td width='1%' bgcolor='#fef2f2' style='background-color:#fef2f2;padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>📉 Etkilenen Monitör</td>"
            + "<td bgcolor='#fef2f2' style='background-color:#fef2f2;padding:9px 13px;font-size:16px;font-weight:800;color:" + red + "'>" + monitorCount + "</td>"
            + "</tr>";

        String listSection =
            "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#1e293b' style='background-color:#1e293b;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8'>ETKİLENEN MONİTÖRLER</td></tr>"
            + stormTargetRows(sampleTargets, truncatedExtra, "#ffffff")
            + "</table>";

        String css = "<style>"
            + "body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + "@media only screen and (max-width:620px){"
            + ".em-card{border-radius:0!important;width:100%!important}.em-body{padding:14px!important}"
            + "}"
            + "</style>";

        return "<!DOCTYPE html><html lang='tr'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<!--[if mso]><style>*{font-family:Arial,Helvetica,sans-serif !important}</style><![endif]-->"
            + css + "</head>"
            + "<body style='margin:0;padding:0;background-color:#f1f5f9;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#f1f5f9'"
            + " style='background-color:#f1f5f9'><tr><td align='center' style='padding:24px 10px'>"
            + "<table role='presentation' class='em-card' width='640' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'"
            + " style='width:640px;max-width:640px;background-color:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.15)'>"
            // Top bar
            + "<tr><td bgcolor='" + red + "' style='background-color:" + red + ";padding:22px 24px'>"
            + "<div style='color:#ffe4e6;font-size:11px;font-weight:700;letter-spacing:.12em'>CertMonitor — İzleme</div>"
            + "<div style='color:#ffffff;font-size:22px;font-weight:900;margin-top:10px;line-height:1.25'>🌩 ALARM FIRTINASI</div>"
            + "<div style='color:#ffe4e6;font-size:15px;font-weight:700;margin-top:8px'>KRİTİK &nbsp;&#183;&nbsp; " + monitorCount + " monitör birden erişilemez</div>"
            + "</td></tr>"
            // Body
            + "<tr><td class='em-body' bgcolor='#ffffff' style='background-color:#ffffff;padding:22px 24px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:10px;overflow:hidden;margin-bottom:16px'><tr>"
            + "<td width='5' bgcolor='" + red + "' style='background-color:" + red + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#fffbeb' style='background-color:#fffbeb;padding:14px 18px;color:#1c1917;font-size:14px;line-height:1.7'>"
            + "Kısa bir zaman penceresinde çok sayıda monitör birden erişilemez oldu — olası paylaşılan sunucu / ağ / veri merkezi kesintisi. "
            + "Bireysel alarmlar bu TEK toplu bildirimde gruplandı; sorunlar giderildikçe tek bir toplu \"çözüldü\" e-postası gönderilecektir."
            + "</td></tr></table>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#334155' style='background-color:#334155;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>FIRTINA ÖZETİ</td></tr>"
            + infoRows + "</table>"
            + listSection
            + cta
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Bildirim: " + generatedAt + "</td>"
            + "</tr></table>"
            + "</td></tr></table></td></tr></table></body></html>";
    }

    /** Toplu alarm fırtınası ÇÖZÜLDÜ e-postası — fırtına sona erdi, {@code recoveredCount} monitör kurtarıldı. */
    public String buildStormRecoveryHtml(int recoveredCount, int stillDownCount, String scopeLabel,
                                         String startedAt, String resolvedAt,
                                         List<String> sampleTargets, int truncatedExtra) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String green = "#16a34a";
        String duration = formatOutageDuration(startedAt, resolvedAt);
        String ctaUrl = stormCtaUrl();
        String cta = (!ctaUrl.isBlank())
            ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:4px 0 18px'><tr><td align='center'>"
              + ctaButton(ctaUrl, "Olayları Aç &rarr;", "#15803d") + "</td></tr></table>"
            : "";

        String infoRows = tableRow2col("🌐 Kapsam",         escHtml(scopeLabel))
            + tableRow2col("📅 Başlangıç",     fmtOrDash(formatIstanbul(startedAt)))
            + tableRow2col("🕐 Çözülme",       fmtOrDash(formatIstanbul(resolvedAt)))
            + "<tr style='border-top:1px solid #e2e8f0'>"
            + "<td width='1%' bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>⏱ Toplam Süre</td>"
            + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:9px 13px;font-size:14px;font-weight:800;color:" + green + "'>" + duration + "</td>"
            + "</tr>"
            + tableRow2col("✅ Kurtarılan",    recoveredCount + " monitör")
            + (stillDownCount > 0 ? tableRow2col("⚠ Hâlâ İzlemede", stillDownCount + " monitör (bireysel alarma döndü)") : "");

        String listSection =
            "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px;border:1px solid #bbf7d0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#15803d' style='background-color:#15803d;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#dcfce7'>&#10003; KURTARILAN MONİTÖRLER</td></tr>"
            + stormRecoveredRows(sampleTargets, truncatedExtra)
            + "</table>";

        String css = "<style>"
            + "body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + "@media only screen and (max-width:620px){"
            + ".em-card{border-radius:0!important;width:100%!important}.em-body{padding:14px!important}"
            + "}"
            + "</style>";

        return "<!DOCTYPE html><html lang='tr'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<!--[if mso]><style>*{font-family:Arial,Helvetica,sans-serif !important}</style><![endif]-->"
            + css + "</head>"
            + "<body style='margin:0;padding:0;background-color:#eef2f6;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#eef2f6'"
            + " style='background-color:#eef2f6'><tr><td align='center' style='padding:24px 10px'>"
            + "<table role='presentation' class='em-card' width='640' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'"
            + " style='width:640px;max-width:640px;background-color:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.15)'>"
            + "<tr><td bgcolor='" + green + "' style='background-color:" + green + ";padding:22px 24px'>"
            + "<div style='color:#dcfce7;font-size:11px;font-weight:700;letter-spacing:.12em'>CertMonitor — İzleme</div>"
            + "<div style='color:#ffffff;font-size:22px;font-weight:900;margin-top:10px;line-height:1.25'>✅ ALARM FIRTINASI SONA ERDİ</div>"
            + "<div style='color:#dcfce7;font-size:15px;font-weight:700;margin-top:8px'>" + recoveredCount + " monitör kurtarıldı</div>"
            + "</td></tr>"
            + "<tr><td class='em-body' bgcolor='#ffffff' style='background-color:#ffffff;padding:22px 24px'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#334155' style='background-color:#334155;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>FIRTINA ÖZETİ</td></tr>"
            + infoRows + "</table>"
            + listSection
            + cta
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Bildirim: " + generatedAt + "</td>"
            + "</tr></table>"
            + "</td></tr></table></td></tr></table></body></html>";
    }

    private String stormRecoveredRows(List<String> targets, int truncatedExtra) {
        StringBuilder sb = new StringBuilder();
        for (String t : targets) {
            sb.append("<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:8px 14px;font-size:13px;color:#1c1917;border-top:1px solid #f1f5f9;word-break:break-all'>")
              .append("&#10003; ").append(escHtml(t)).append("</td></tr>");
        }
        if (truncatedExtra > 0) {
            sb.append("<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:8px 14px;font-size:12px;color:#64748b;border-top:1px solid #f1f5f9;font-style:italic'>")
              .append("+ ").append(truncatedExtra).append(" monitör daha…</td></tr>");
        }
        return sb.toString();
    }

    // ── Keyword / Ping izleme — kendine ÖZGÜ executive alarm + çözüm şablonları ──
    // Cert/expiry şablonuyla hiçbir alan paylaşmaz; ortak yalnız kart/CSS iskeleti (aşağıdaki frame helper'ları).

    /** Ortak executive alarm kartı (keyword/ping) — kimlik (accent/ikon/başlık) ve içerik dışarıdan gelir. */
    /** Monitör detayına deep-link CTA URL'si (?tab=<tab>&monitor=<id>); base/monitor yoksa boş. */
    private String monitorCtaUrl(String tab, Map<String, Object> ctx) {
        Object mid = ctx != null ? ctx.get("monitor_id") : null;
        if (mid == null) return "";
        // CANLI okunur (Genel Ayarlar'dan değişebilir); @Value yalnız fallback — reminder/approve/incident ile AYNI kaynak.
        String url = appSettings.getString("cert.monitor.app.base-url", appBaseUrl);
        String base = (url != null && !url.isBlank()) ? url.replaceAll("/+$", "") : "";
        return base + "/?tab=" + tab + "&monitor=" + mid;
    }

    /** Header'da izlenen hedefi beyaz stille gösterir. Gerçek URL ise (http/https) tıklanabilir &lt;a&gt;;
     *  çıplak host (ping/port) ise şemasız kırık link yerine stillendirilmiş &lt;span&gt; (Outlook auto-link engeli korunur). */
    private String endpointLink(String value) {
        String esc = escHtml(value);
        boolean url = value != null && (value.startsWith("http://") || value.startsWith("https://"));
        return url
            ? "<a href='" + esc + "' target='_blank' style='color:#ffffff;text-decoration:none'>" + esc + "</a>"
            : "<span style='color:#ffffff'>" + esc + "</span>";
    }

    private String monitoringTypedAlert(String accent, String kicker, String emoji,
            String heroTitle, String heroSub, String endpoint, String typeBadge,
            String firstFailureAt, String attemptsLabel, String delayLabel,
            String leftRows, String rightRows, String extraBox, String message, String infoNote, String ctaUrl) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String cta = (ctaUrl != null && !ctaUrl.isBlank())
            ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:4px 0 16px'><tr><td align='center'>"
              + ctaButton(ctaUrl, "Monitörü Aç &rarr;", accent) + "</td></tr></table>"
            : "";

        String hero = "<table width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " style='margin:18px 0;border-radius:14px;overflow:hidden;border:1px solid #e2e8f0'><tr>"
            + "<td class='em-hero-l' align='center' valign='middle' width='38%' bgcolor='" + accent + "' style='background:" + accent + ";padding:22px 14px'>"
            + "<div style='color:#fff;font-size:46px;line-height:1'>" + emoji + "</div>"
            + "<div style='color:#fff;font-size:14px;font-weight:800;margin-top:8px;letter-spacing:.04em'>" + heroTitle + "</div>"
            + "<div style='color:#ffffff;font-size:12px;margin-top:8px;padding:0 6px'>" + heroSub + "</div>"
            + "</td>"
            + "<td class='em-hero-r' valign='middle' bgcolor='#f8fafc' style='background:#f8fafc;padding:20px 22px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.1em;color:#94a3b8;text-transform:uppercase;margin-bottom:10px'>İlk Hata Zamanı</div>"
            + "<div style='font-size:24px;font-weight:900;color:" + accent + ";letter-spacing:-.5px'>" + formatIso(firstFailureAt) + "</div>"
            + "<div style='font-size:13px;color:#475569;margin-top:6px;line-height:1.6'>" + formatIsoFull(firstFailureAt) + "</div>"
            + "<div style='margin-top:12px;font-size:12px;font-weight:700;color:#475569'>"
            + "🔁 " + attemptsLabel + " doğrulama denemesi " + delayLabel + "— tümü başarısız</div>"
            + "</td></tr></table>";

        // Tek-kolon (alt alta) — Outlook'ta yan-yana kolonlar kayıyordu; tam genişlik bölümler kaymaz.
        String twoCol = "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 12px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#1f3864' style='background:#1f3864;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>İZLEME BİLGİLERİ</td></tr>"
            + leftRows + "</table>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#1f3864' style='background:#1f3864;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>DOĞRULAMA ÖZETİ</td></tr>"
            + rightRows + "</table>";

        String css = "<style>body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + "@media only screen and (max-width:620px){.em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-domain{font-size:16px!important;word-break:break-all!important}.em-body{padding:14px!important}"
            + ".em-hero-l,.em-hero-r{display:block!important;width:100%!important}.em-hero-l{border-radius:12px 12px 0 0!important}"
            + ".em-hero-r{border-radius:0 0 12px 12px!important;padding:16px!important}"
            + ".em-col-l{display:block!important;width:100%!important;padding-right:0!important;padding-bottom:10px!important}"
            + ".em-col-r{display:block!important;width:100%!important}}</style>";

        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<meta http-equiv='X-UA-Compatible' content='IE=edge'>"
            + "<!--[if mso]><style>table,td,div,p,a{font-family:'Segoe UI',Arial,sans-serif!important}</style><![endif]-->" + css + "</head>"
            // Dış arka plan (bgcolor attr) + tablo lspace/rspace sıfır (Outlook tabloya boşluk eklemesin → kayma);
            // dış padding TD'de (Outlook tablo padding'ini yok sayar); kart bgcolor='#ffffff' → dış gri içeri sızmaz.
            + "<body bgcolor='#f1f5f9' style='margin:0;padding:0;background:#f1f5f9;-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table role='presentation' class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#f1f5f9' style='background:#f1f5f9;mso-table-lspace:0pt;mso-table-rspace:0pt'><tr><td align='center' style='padding:24px 10px'>"
            + "<table class='em-card' width='640' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff' style='max-width:640px;width:100%;background:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.15);mso-table-lspace:0pt;mso-table-rspace:0pt'><tr><td style='padding:0'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='" + accent + "' style='background-color:" + accent + "'><tr><td style='padding:22px 24px'>"
            + "<div style='color:#ffffff;font-size:11px;font-weight:700;letter-spacing:.12em'>" + kicker + "</div>"
            // URL explicit beyaz <a> içinde — Outlook çıplak URL'yi otomatik linkleyip mavi yapıyor (navy zeminde okunmaz).
            + "<div class='em-domain' style='color:#fff;font-size:22px;font-weight:900;margin-top:10px;word-break:break-all;line-height:1.25'>" + emoji + " " + endpointLink(endpoint) + "</div>"
            + "<div style='color:#ffffff;font-size:15px;font-weight:700;margin-top:8px;letter-spacing:.02em'>KRİTİK &nbsp;&#183;&nbsp; " + typeBadge + "</div>"
            + "</td></tr></table>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff' style='background-color:#ffffff'><tr><td class='em-body' style='padding:22px 24px'>"
            // Sorunu en üstte, sade ve net (executive) — kırmızı şeritli uyarı kutusu
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:2px 0 16px'><tr>"
            + "<td bgcolor='#fef2f2' style='background:#fef2f2;border-left:5px solid #dc2626;padding:15px 18px'>"
            + "<div style='font-size:12px;font-weight:800;letter-spacing:.07em;color:#dc2626;margin-bottom:6px'>⚠ SORUN TESPİT EDİLDİ</div>"
            + "<div style='font-size:15px;font-weight:600;color:#1c1917;line-height:1.6'>" + escHtml(message) + "</div>"
            + "</td></tr></table>"
            + hero + twoCol + extraBox + cta
            + "<div style='font-size:12px;color:#64748b;line-height:1.6;margin-bottom:20px'>" + infoNote + "</div>"
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Bildirim: " + generatedAt + "</td></tr></table>"
            + "</td></tr></table></td></tr></table></td></tr></table></body></html>";
    }

    /** Ortak attempt satırları — keyword/ping alarm sol kolonunda teyit denemeleri. */
    @SuppressWarnings("unchecked")
    private String attemptRows(Map<String, Object> ctx) {
        List<Map<String, Object>> attempts = (ctx != null && ctx.get("confirm_attempts") instanceof List<?> l)
                ? (List<Map<String, Object>>) l : List.of();
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> a : attempts) {
            String at = String.valueOf(a.getOrDefault("checked_at", ""));
            String err = String.valueOf(a.getOrDefault("error", ""));
            String detail = !err.isEmpty() && !"null".equals(err)
                    ? formatIso(at) + " — " + escHtml(err.length() > 60 ? err.substring(0, 60) + "…" : err)
                    : formatIso(at) + " — doğrulanamadı";
            sb.append(tableRow2col("🔁 Deneme " + a.getOrDefault("attempt", "?"), detail));
        }
        return sb.toString();
    }

    private static int attemptCount(Map<String, Object> ctx) {
        return (ctx != null && ctx.get("confirm_attempts") instanceof List<?> l) ? l.size() : 0;
    }

    /** Keyword izleme alarmı — kurumsal lacivert, adet/operatör koşulu alanları. */
    private String buildRichKeywordAlertHtml(String message, String url, String level, Map<String, Object> ctx) {
        String accent = "#1f3864";   // Akbank kurumsal lacivert
        String keyword   = ctxStr(ctx, "keyword");
        String operator  = ctxStr(ctx, "operator"); if (operator.isEmpty()) operator = "GTE";
        int threshold = ctx != null && ctx.get("match_count") instanceof Number mn ? mn.intValue() : 1;
        boolean absent = ("LTE".equals(operator) || "EQ".equals(operator) || "LT".equals(operator)) && threshold == 0;
        String condPhrase = KeywordCheckerService.opPhrase(operator, threshold);
        String occ = ctxStr(ctx, "occurrences");
        String httpStatus = ctxStr(ctx, "http_status");
        String responseMs = ctxStr(ctx, "response_ms");
        String snippet    = ctxStr(ctx, "snippet");
        String firstFailureAt = ctxStr(ctx, "first_failure_at");
        String lastError      = ctxStr(ctx, "last_error");
        String ac = ctxStr(ctx, "confirm_attempt_count");
        String delayMs = ctxStr(ctx, "confirm_delay_ms");
        String attemptsLabel = !ac.isEmpty() ? ac : "Ardışık";
        String delayLabel    = !delayMs.isEmpty() ? (Long.parseLong(delayMs) / 1000) + " sn arayla " : "";
        int n = attemptCount(ctx);

        StringBuilder left = new StringBuilder();
        left.append(tableRow2col("🌐 Adres", escHtml(url)));
        left.append(tableRow2col("🔎 Aranan kelime", escHtml(keyword)));
        left.append(tableRow2col("⚙ Beklenen koşul", condPhrase + " bulunmalı"));
        if (!occ.isEmpty()) left.append(tableRow2col("🔢 Bulunan adet", occ + " kez"));
        if (!httpStatus.isEmpty()) left.append(tableRow2col("📡 HTTP durumu", escHtml(httpStatus)));
        if (!responseMs.isEmpty()) left.append(tableRow2col("⏱ Yanıt süresi", escHtml(responseMs) + " ms"));
        if (!firstFailureAt.isEmpty()) left.append(tableRow2col("🕐 İlk hata", formatIso(firstFailureAt)));
        if (!lastError.isEmpty() && !"null".equalsIgnoreCase(lastError))
            left.append(tableRow2col("⚠ Son hata", escHtml(lastError.length() > 90 ? lastError.substring(0, 90) + "…" : lastError)));

        String wordStatus = absent ? "✗ İstenmeyen ifade var"
                : (!occ.isEmpty() ? "✗ " + occ + " kez · gerekli: " + condPhrase : "✗ Koşul sağlanmadı");
        String right = statusRow2col("Kelime durumu", wordStatus)
            + statusRow2col("Doğrulama", "✗ " + (n == 0 ? "Başarısız" : n + "/" + n + " başarısız"))
            + statusRow2col("Seviye", "✗ KRİTİK")
            + statusRow2col("İzleme", "✓ Devam ediyor");

        String extraBox = (absent && !snippet.isEmpty())
            ? "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:14px'><tr>"
              + "<td bgcolor='#f8fafc' style='background-color:#f8fafc;border-left:4px solid " + accent + ";padding:12px 16px'>"
              + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + accent + ";margin-bottom:6px'>EŞLEŞME BAĞLAMI</div>"
              + "<div style='font-size:13px;color:#1c1917;font-family:Consolas,monospace;word-break:break-word'>…" + escHtml(snippet) + "…</div></td></tr></table>"
            : "";

        return monitoringTypedAlert(accent, "CertMonitor — İçerik (Keyword) İzleme", "🔎",
                absent ? "İSTENMEYEN İFADE BULUNDU" : "KOŞUL SAĞLANMADI",
                "⚠ İçerik doğrulaması başarısız", escHtml(url), "İçerik Doğrulama",
                firstFailureAt, attemptsLabel, delayLabel, left.toString(), right, extraBox, message,
                "ℹ Koşul yeniden sağlandığında bu alarm otomatik kapatılır ve çözüm e-postası gönderilir.",
                monitorCtaUrl("keyword", ctx));
    }

    /** Ping (ICMP) izleme alarmı — kurumsal lacivert, erişilebilirlik alanları. */
    private String buildRichPingAlertHtml(String message, String host, String level, Map<String, Object> ctx) {
        String accent = "#1f3864";
        boolean na = "true".equalsIgnoreCase(ctxStr(ctx, "na"));
        String ipVersion  = ctxStr(ctx, "ip_version");
        String packetLoss = ctxStr(ctx, "packet_loss");
        String rttMs      = ctxStr(ctx, "rtt_ms");
        String firstFailureAt = ctxStr(ctx, "first_failure_at");
        String lastError      = ctxStr(ctx, "last_error");
        String ac = ctxStr(ctx, "confirm_attempt_count");
        String delayMs = ctxStr(ctx, "confirm_delay_ms");
        String attemptsLabel = !ac.isEmpty() ? ac : "Ardışık";
        String delayLabel    = !delayMs.isEmpty() ? (Long.parseLong(delayMs) / 1000) + " sn arayla " : "";
        String lossLabel     = !packetLoss.isEmpty() ? "%" + packetLoss : "%100";
        int n = attemptCount(ctx);

        StringBuilder left = new StringBuilder();
        left.append(tableRow2col("🖥️ Host", escHtml(host)));
        left.append(tableRow2col("🔢 IP sürümü", ipVersion.isEmpty() || "auto".equals(ipVersion) ? "Otomatik" : escHtml(ipVersion.toUpperCase())));
        left.append(tableRow2col("📉 Paket kaybı", lossLabel));
        if (!rttMs.isEmpty()) left.append(tableRow2col("⏱ RTT", escHtml(rttMs) + " ms"));
        if (!firstFailureAt.isEmpty()) left.append(tableRow2col("🕐 İlk hata", formatIso(firstFailureAt)));
        if (!lastError.isEmpty() && !"null".equalsIgnoreCase(lastError))
            left.append(tableRow2col("⚠ Son hata", escHtml(lastError.length() > 90 ? lastError.substring(0, 90) + "…" : lastError)));

        String right = statusRow2col("Ping durumu", na ? "✗ ICMP kullanılamıyor" : "✗ Yanıt yok")
            + statusRow2col("Paket kaybı", "✗ " + lossLabel)
            + statusRow2col("Doğrulama", "✗ " + (n == 0 ? "Başarısız" : n + "/" + n + " başarısız"))
            + statusRow2col("Seviye", "✗ KRİTİK")
            + statusRow2col("İzleme", "✓ Devam ediyor");

        return monitoringTypedAlert(accent, "CertMonitor — Ping (ICMP) İzleme", "🖥️",
                na ? "ICMP KULLANILAMIYOR" : "HOST YANIT VERMİYOR",
                "⚠ Erişilebilirlik kaybı", escHtml(host), "Erişilebilirlik (Ping)",
                firstFailureAt, attemptsLabel, delayLabel, left.toString(), right, "", message,
                "ℹ Host yeniden yanıt verdiğinde bu alarm otomatik kapatılır ve çözüm e-postası gönderilir.",
                monitorCtaUrl("ping", ctx));
    }

    /** Ortak executive çözüm (yeşil) kartı — keyword/ping kimliğiyle. */
    private String monitoringTypedResolved(String domain, String kicker, String heroLine,
            String typeTrLabel, String emoji, String detailRows, String ctaUrl,
            String resolvedBy, String resolvedAt, String createdAt) {
        String generatedAt = LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String green = "#16a34a";
        String by = resolvedBy != null && !resolvedBy.isBlank() ? resolvedBy : "Sistem (otomatik)";
        String duration = formatOutageDuration(createdAt, resolvedAt);
        String detailSection = (detailRows != null && !detailRows.isBlank())
            ? "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin-bottom:16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
              + "<tr><td bgcolor='#1f3864' style='background-color:#1f3864;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>ÇÖZÜLEN ALARM DETAYI</td></tr>"
              + detailRows + "</table>"
            : "";
        String cta = (ctaUrl != null && !ctaUrl.isBlank())
            ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 16px'><tr><td align='center'>"
              + ctaButton(ctaUrl, "Monitörü Aç &rarr;", "#1f3864") + "</td></tr></table>"
            : "";

        String resolverRows = tableRow2col("👤 Çözen", escHtml(by))
            + tableRow2col("🕐 Çözülme Zamanı", fmtOrDash(formatIstanbul(resolvedAt)))
            + tableRow2col("📅 Alarm Başlangıcı", fmtOrDash(formatIstanbul(createdAt)));
        String outageRows = tableRow2col("🌐 İzlenen", escHtml(domain))
            + tableRow2col("⚠ Alarm Tipi", typeTrLabel)
            + "<tr style='border-top:1px solid #e2e8f0'><td width='1%' style='padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>🔴 Seviye</td>"
            + "<td style='padding:9px 13px;font-size:14px;font-weight:700;color:#dc2626'>KRİTİK</td></tr>"
            // Vurgu satırı: <tr background> Outlook'ta beyaza düşer → her td'ye bgcolor (kardeş buildRichMonitoringResolvedHtml deseni)
            + "<tr style='border-top:1px solid #e2e8f0'><td width='1%' bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:9px 13px;font-size:12px;color:#64748b;white-space:nowrap'>⏱ Toplam Kesinti</td>"
            + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:9px 13px;font-size:14px;font-weight:800;color:" + green + "'>" + duration + "</td></tr>";

        // Tek-kolon (alt alta) — Outlook'ta yan-yana kolonlar kayıyordu; tam genişlik bölümler kaymaz.
        String twoCol = "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 12px;border:1px solid #bbf7d0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#15803d' style='background:#15803d;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#dcfce7'>ÇÖZÜM BİLGİSİ</td></tr>"
            + resolverRows + "</table>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 16px;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td colspan='2' bgcolor='#1f3864' style='background:#1f3864;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>KESİNTİ DETAYI</td></tr>"
            + outageRows + "</table>";

        String css = "<style>body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + "@media only screen and (max-width:620px){.em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-domain{font-size:16px!important;word-break:break-all!important}.em-body{padding:14px!important}"
            + ".em-col-l{display:block!important;width:100%!important;padding-right:0!important;padding-bottom:10px!important}"
            + ".em-col-r{display:block!important;width:100%!important}}</style>";

        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<meta http-equiv='X-UA-Compatible' content='IE=edge'>"
            + "<!--[if mso]><style>table,td,div,p,a{font-family:'Segoe UI',Arial,sans-serif!important}</style><![endif]-->" + css + "</head>"
            // Dış arka plan (bgcolor attr) + tablo lspace/rspace sıfır (Outlook tabloya boşluk eklemesin → kayma);
            // dış padding TD'de (Outlook tablo padding'ini yok sayar); kart bgcolor='#ffffff' → dış gri içeri sızmaz.
            + "<body bgcolor='#f1f5f9' style='margin:0;padding:0;background:#f1f5f9;-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table role='presentation' class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#f1f5f9' style='background:#f1f5f9;mso-table-lspace:0pt;mso-table-rspace:0pt'><tr><td align='center' style='padding:24px 10px'>"
            + "<table class='em-card' width='640' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff' style='max-width:640px;width:100%;background:#ffffff;border-radius:14px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.15);mso-table-lspace:0pt;mso-table-rspace:0pt'><tr><td style='padding:0'>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='" + green + "' style='background-color:" + green + "'><tr><td style='padding:22px 24px'>"
            + "<div style='color:#ffffff;font-size:11px;font-weight:700;letter-spacing:.12em'>" + kicker + "</div>"
            // URL explicit beyaz <a> içinde — Outlook çıplak URL'yi otomatik linkleyip mavi yapıyor (yeşil zeminde okunmaz).
            + "<div class='em-domain' style='color:#fff;font-size:22px;font-weight:900;margin-top:10px;word-break:break-all;line-height:1.25'>✅ " + endpointLink(domain) + "</div>"
            + "<div style='color:#ffffff;font-size:15px;font-weight:700;margin-top:8px;letter-spacing:.02em'>" + heroLine + " &nbsp;&#183;&nbsp; " + typeTrLabel + "</div>"
            + "</td></tr></table>"
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff' style='background-color:#ffffff'><tr><td class='em-body' style='padding:22px 24px'>"
            + "<div style='text-align:center;margin:14px 0 22px'>"
            + "<div style='font-size:54px;line-height:1;color:" + green + "'>✓</div>"
            + "<div style='margin-top:8px;font-size:20px;font-weight:800;color:#15803d'>" + heroLine + "</div>"
            + "<div style='margin-top:6px;font-size:13px;color:#64748b'>Alarm kapatıldı. İzleme devam etmektedir.</div></div>"
            + twoCol
            + detailSection
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:10px;overflow:hidden;margin-bottom:20px'><tr>"
            + "<td width='5' bgcolor='" + green + "' style='background-color:" + green + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#f0fdf4' style='background-color:#f0fdf4;padding:14px 18px;color:#14532d;font-size:14px;line-height:1.7'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + green + ";margin-bottom:6px'>BİLGİ</div>"
            + "<strong>" + escHtml(domain) + "</strong> için açık olan <strong>" + typeTrLabel + "</strong> alarmı kapatıldı. Toplam kesinti süresi: <strong>" + duration + "</strong>.</td></tr></table>"
            + cta
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Bildirim: " + generatedAt + "</td></tr></table>"
            + "</td></tr></table></td></tr></table></td></tr></table></body></html>";
    }

    private String buildRichKeywordResolvedHtml(String url, Map<String, Object> ctx, String resolvedBy, String resolvedAt, String createdAt) {
        StringBuilder d = new StringBuilder();
        if (ctx != null) {
            String kw = ctxStr(ctx, "keyword");
            String op = ctxStr(ctx, "operator"); if (op.isEmpty()) op = "GTE";
            int n = ctx.get("match_count") instanceof Number mn ? mn.intValue() : 1;
            String occ = ctxStr(ctx, "occurrences");
            if (!kw.isEmpty()) d.append(tableRow2col("🔎 Aranan kelime", escHtml(kw)));
            d.append(tableRow2col("⚙ Koşul", KeywordCheckerService.opPhrase(op, n) + " bulunmalı"));
            if (!occ.isEmpty()) d.append(tableRow2col("🔢 Alarm anı bulunan", occ + " kez"));
        }
        return monitoringTypedResolved(url, "CertMonitor — İçerik (Keyword) İzleme",
                "İçerik Doğrulaması Yeniden Başarılı", "İçerik Doğrulama", "🔎",
                d.toString(), monitorCtaUrl("keyword", ctx), resolvedBy, resolvedAt, createdAt);
    }

    private String buildRichPingResolvedHtml(String host, Map<String, Object> ctx, String resolvedBy, String resolvedAt, String createdAt) {
        StringBuilder d = new StringBuilder();
        if (ctx != null) {
            String ipv = ctxStr(ctx, "ip_version");
            d.append(tableRow2col("🖥️ Host", escHtml(host)));
            if (!ipv.isEmpty() && !"auto".equals(ipv)) d.append(tableRow2col("🔢 IP sürümü", escHtml(ipv.toUpperCase())));
        }
        return monitoringTypedResolved(host, "CertMonitor — Ping (ICMP) İzleme",
                "Host Yeniden Yanıt Veriyor", "Erişilebilirlik (Ping)", "🖥️",
                d.toString(), monitorCtaUrl("ping", ctx), resolvedBy, resolvedAt, createdAt);
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
            + "<td class='em-col-l' valign='top' width='50%' style='width:50%;padding-right:8px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='#475569' style='background-color:#475569;padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#cbd5e1'>ESKİ DEĞERLER</td></tr>"
            + oldRows + "</table></td>"
            + "<td class='em-col-r' valign='top' width='50%' style='width:50%'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid " + purple + ";border-radius:10px;overflow:hidden'>"
            + "<tr><td bgcolor='" + purple + "' style='background-color:" + purple + ";padding:9px 14px;font-size:11px;font-weight:700;letter-spacing:.1em;color:#f3e8ff'>YENİ DEĞERLER</td></tr>"
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
            + "<meta name='color-scheme' content='light only'>"
            + "<meta name='supported-color-schemes' content='light'>"
            + "<!--[if mso]><style>*{font-family:Arial,Helvetica,sans-serif !important}</style><![endif]-->"
            + css + "</head>"
            + "<body style='margin:0;padding:0;background-color:#f1f5f9;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table role='presentation' class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#f1f5f9' style='background-color:#f1f5f9;mso-table-lspace:0;mso-table-rspace:0'>"
            + "<tr><td align='center' style='padding:24px 10px'>"

            + "<table role='presentation' class='em-card' width='640' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'"
            + " style='width:640px;max-width:640px;background-color:#ffffff;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 8px 32px rgba(0,0,0,.15)'>"

            // ── Top bar (mor) — bgcolor'lı <td> ──
            + "<tr><td bgcolor='" + purple + "' style='background-color:" + purple + ";padding:22px 24px'>"
            + "<div style='color:#ffffff;font-size:11px;font-weight:700;letter-spacing:.12em'>CertMonitor — DNS İzleme</div>"
            + "<div class='em-domain' style='color:#fff;font-size:22px;font-weight:900;"
            + "margin-top:10px;word-break:break-all;line-height:1.25'>🔀 " + escHtml(domain)
            + (!recordType.isEmpty() ? " <span style='font-size:15px;font-weight:700'>· " + escHtml(recordType) + " kaydı</span>" : "")
            + "</div>"
            + "<div style='color:#ffffff;font-size:15px;font-weight:700;"
            + "margin-top:8px;letter-spacing:.02em'>YÜKSEK &nbsp;&#183;&nbsp; DNS Değişikliği</div>"
            + "</td></tr>"

            // ── Body ──
            + "<tr><td bgcolor='#ffffff' style='background-color:#ffffff;padding:22px 24px'>"

            // Hero (mor pill — bgcolor'lı <td>, inline-block div değil)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:16px 0 22px'><tr><td align='center'>"
            + "<table role='presentation' cellpadding='0' cellspacing='0' border='0' align='center'><tr>"
            + "<td bgcolor='" + purple + "' style='background-color:" + purple + ";border-radius:12px;padding:14px 32px;font-size:17px;font-weight:800;letter-spacing:.02em;color:#fff'>"
            + "🔀 DNS KAYDI DEĞİŞTİ</td>"
            + "</tr></table>"
            + (!changedAt.isEmpty()
                ? "<div style='margin-top:10px;font-size:13px;color:#64748b'>Tespit zamanı: " + formatIsoFull(changedAt) + "</div>" : "")
            + "</td></tr></table>"

            + twoColSection

            // Alarm detayı (sol aksan-şeritli tablo)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='border-radius:10px;overflow:hidden;margin-bottom:14px'><tr>"
            + "<td width='5' bgcolor='" + purple + "' style='background-color:" + purple + ";width:5px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#faf5ff' style='background-color:#faf5ff;padding:14px 18px;color:#1c1917;font-size:14px;line-height:1.7'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;color:" + purple + ";margin-bottom:6px'>ALARM DETAYI</div>"
            + escHtml(message)
            + "</td></tr></table>"

            // Manuel kapanış notu
            + "<div style='font-size:12px;color:#64748b;line-height:1.6;margin-bottom:20px'>"
            + "ℹ Bu alarm otomatik kapanmaz. Değişiklik planlı ise CertMonitor &rarr; Uyarılar &rarr; "
            + "Alarm Geçmişi ekranından alarmı onaylayın ve kapatın. Beklenmedik bir değişiklikse "
            + "(olası domain hijack / hatalı migrasyon) derhal ağ ekibiyle iletişime geçin."
            + "</div>"

            // Footer
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + "Bildirim: " + generatedAt
            + "</td></tr></table>"

            + "</td></tr>"

            + "</table>"
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
    // softbreak("<br />\n"): tek satır-sonu (\n) HTML'de <br/> olur — kullanıcının alt alta yazdığı
    // satırlar (log/stack trace/adım listesi) mailde de alt alta görünür (yoksa markdown boşluğa düzlerdi).
    private static final HtmlRenderer MD_RENDERER = HtmlRenderer.builder()
            .extensions(MD_EXTENSIONS).escapeHtml(true).softbreak("<br />\n").build();

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
            log.error("✗ HTML e-posta hazırlanamadı: TO={} | HATA={}", Arrays.toString(to), e.getMessage(), e);
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
            m.appendReplacement(sb, "<img width=\"" + w + "\" border=\"0\" alt=\"Rapor görseli\" style=\"display:block;width:100%;"
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

    /** approveCtaUrl'süz, KPI özetsiz uyumluluk overload'u. */
    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail,
                                        Map<Long, Integer> imageWidths,
                                        String approverName, String approvedAtIso, String sentAtIso,
                                        String approveCtaUrl) {
        return buildWeeklyReportHtml(teamName, weekLabel, managerName, contentJson, forEmail,
                imageWidths, approverName, approvedAtIso, sentAtIso, approveCtaUrl, null);
    }

    /** {@code approveCtaUrl} doluysa (PO onay-bekleyen maili): rapor içeriğinin üstüne ve altına "Raporu onayla"
     *  CTA bloğu eklenir. {@code kpiSummary} doluysa hero altında canlı KPI özet satırı (toplam/dolan/alarm/kritik/uptime)
     *  gösterilir — WeeklyReportKpiService'ten (read-only). */
    @SuppressWarnings("unchecked")
    public String buildWeeklyReportHtml(String teamName, String weekLabel, String managerName,
                                        String contentJson, boolean forEmail,
                                        Map<Long, Integer> imageWidths,
                                        String approverName, String approvedAtIso, String sentAtIso,
                                        String approveCtaUrl, Map<String, Object> kpiSummary) {
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
            // Renkli kutu: <p background> Outlook'ta beyaza düşer → accent-şeritli tablo (td bgcolor)
            ? "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:8px 0;border-radius:8px;overflow:hidden'><tr>"
              + "<td width='4' bgcolor='#16a34a' style='background-color:#16a34a;width:4px;font-size:0;line-height:0'>&nbsp;</td>"
              + "<td bgcolor='#e7f6ec' style='background-color:#e7f6ec;padding:8px 12px;font-size:13px;font-weight:700;color:#14532d'>"
              + "✔ Bu hafta aşım yaşanan olay, problem veya açık postmortem kaydı bulunmamaktadır.</td></tr></table>"
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

        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml' xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + css + "</head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='" + outerBg + "' style='background:" + outerBg + ";padding:24px 10px'>"
            + "<tr><td align='center' bgcolor='" + outerBg + "'>"

            // MSO ghost-table: Outlook'ta kartı 850px sabit + ortalı tutar (availability deseni)
            + "<!--[if mso]><table role='presentation' width='850' align='center' cellpadding='0' cellspacing='0' border='0'><tr><td><![endif]-->"
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
            // Gövde — div padding'i Outlook (Word) yok sayar → td padding'i (em-body class'ı td'de)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'><tr>"
            + "<td class='em-body' bgcolor='#ffffff' style='padding:22px 24px'>"

            // Hitap + giriş
            + "<p style='font-size:15px;color:#0f172a;margin:0 0 6px'><strong>Sayın "
            + escHtml(managerName != null && !managerName.isBlank() ? managerName : "Yönetici") + ",</strong></p>"
            + "<p style='font-size:14px;color:#334155;line-height:1.7;margin:0 0 18px'>"
            + escHtml(teamName) + " ekibi olarak <strong>" + escHtml(weekLabel)
            + "</strong> haftası raporumuzu aşağıda paylaşıyoruz.</p>"

            + weeklyOverviewSection(kpiSummary, accent)

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

            + "</td></tr></table>"   // em-body td + gövde tablosu
            + "</td></tr></table>"   // kart iç td + kart tablosu
            + "<!--[if mso]></td></tr></table><![endif]-->"
            + "</td></tr></table>"   // dış (wrap) td + tablo
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
            + LIGHT_SCHEME_META
            + "<style>@media only screen and (max-width:870px){"
            + ".em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-body{padding:14px!important}}</style></head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"

            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='" + outerBg + "' style='background:" + outerBg + ";padding:24px 10px'>"
            + "<tr><td align='center' bgcolor='" + outerBg + "'>"

            // MSO ghost-table: Outlook'ta kartı 850px sabit + ortalı tutar (availability deseni)
            + "<!--[if mso]><table role='presentation' width='850' align='center' cellpadding='0' cellspacing='0' border='0'><tr><td><![endif]-->"
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

            // ── Gövde ── (div padding'i Outlook yok sayar → td-tabanlı em-body)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'><tr>"
            + "<td class='em-body' bgcolor='#ffffff' style='padding:22px 24px'>"
            + "<p style='font-size:15px;color:#0f172a;margin:0 0 6px'><strong>Sayın " + escHtml(teamName) + " ekibi,</strong></p>"
            + "<p style='font-size:14px;color:#334155;line-height:1.7;margin:0 0 14px'>"
            + "Bu haftanın (<strong>" + escHtml(weekLabel) + "</strong>) haftalık raporu sistemde henüz görünmüyor. "
            + "Mesai başlangıcıyla birlikte raporunuzu hatırlatmak isteriz.</p>"

            // Son giriş uyarısı (vurgulu)
            // Renkli kutu: <p background> Outlook'ta beyaza düşer → accent-şeritli tablo (td bgcolor)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 4px;border-radius:8px;overflow:hidden'><tr>"
            + "<td width='4' bgcolor='#dc2626' style='background-color:#dc2626;width:4px;font-size:0;line-height:0'>&nbsp;</td>"
            + "<td bgcolor='#fef2f2' style='background-color:#fef2f2;padding:10px 14px;font-size:14px;font-weight:700;color:#991b1b'>"
            + "⏰ Son giriş <strong>bugün saat 15:00</strong> — lütfen bu haftanın raporunu Cert Monitor üzerinden zamanında giriniz.</td></tr></table>"

            + cta

            + reportSection("Haftalık Rapor Nasıl Girilir?", stepsBody, accent)

            // Footer
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor — Otomatik Hatırlatma</td>"
            + "<td align='right' valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8;line-height:1.7'>"
            + "Oluşturuldu: " + generatedAt + "</td></tr></table>"

            + "</td></tr></table>"   // em-body td + gövde tablosu
            + "</td></tr></table>"   // kart iç td + kart tablosu
            + "<!--[if mso]></td></tr></table><![endif]-->"
            + "</td></tr></table>"   // dış (wrap) td + tablo
            + "</body></html>";
    }

    // ── Haftalık Erişilebilirlik (availability) e-postası ───────────────────────

    /** E-posta görünüm DTO'ları — view katmanı kendi girdilerini sahiplenir (servis bağımlılığı
     *  email→report yönünde değil). availabilityPct/avgMs/p95Ms/certDays null = veri yok. */
    public record AvailabilityRow(String domain, Double availabilityPct,
                                  int outageCount, long downtimeMinutes, long longestOutageMinutes,
                                  Long avgMs, Long p95Ms, Integer certDaysRemaining) {}
    public record AvailabilitySummary(int domainCount, int withDataCount, Double avgAvailabilityPct,
                                      String bestDomain, Double bestPct, String worstDomain, Double worstPct,
                                      int downDomainCount, Integer nearestCertDays) {}

    /** Sertifika sahibi takıma haftalık erişilebilirlik özeti (executive). rows en kötü
     *  availability üstte sıralı gelir; down domainler ayrı vurgulanır. */
    public String buildWeeklyAvailabilityHtml(String teamName, String weekLabel,
                                              List<AvailabilityRow> rows, AvailabilitySummary s) {
        String accent = "#1f3864";
        String outerBg = "#f4f6f8";
        String generatedAt = ZonedDateTime.now(IST).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

        // KPI bandı (4 kart) — td+bgcolor (Outlook uyumlu)
        String avgTxt = pctText(s.avgAvailabilityPct());
        String kpi =
            "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:4px 0 18px;border-collapse:separate;border-spacing:8px 0'><tr>"
            + kpiCard("İZLENEN DOMAIN", String.valueOf(s.domainCount()), "#1e293b", "#f8fafc")
            + kpiCard("ORT. ERİŞİLEBİLİRLİK", avgTxt, pctColor(s.avgAvailabilityPct()), "#f8fafc")
            + kpiCard("KESİNTİ YAŞAYAN", String.valueOf(s.downDomainCount()),
                      s.downDomainCount() > 0 ? "#dc2626" : "#16a34a", "#f8fafc")
            + kpiCard("EN YAKIN SERTİFİKA", s.nearestCertDays() != null ? s.nearestCertDays() + " gün" : "—",
                      s.nearestCertDays() != null && s.nearestCertDays() <= 30 ? "#dc2626" : "#1e293b", "#f8fafc")
            + "</tr></table>";

        // Kesinti bölümü — varsa kırmızı liste, yoksa yeşil "kesinti yok" bandı (mail her durumda gider)
        StringBuilder down = new StringBuilder();
        for (AvailabilityRow r : rows) {
            if (r.outageCount() > 0) {
                down.append("<tr style='border-top:1px solid #fecaca'>")
                    .append("<td style='padding:7px 12px;font-size:13px;font-weight:700;color:#991b1b'>").append(escHtml(r.domain())).append("</td>")
                    .append("<td style='padding:7px 12px;font-size:13px;color:#b91c1c;white-space:nowrap'>").append(pctText(r.availabilityPct())).append("</td>")
                    .append("<td style='padding:7px 12px;font-size:13px;color:#b91c1c;white-space:nowrap'>")
                    .append(r.outageCount()).append(" kesinti · ").append(r.downtimeMinutes()).append(" dk</td>")
                    .append("</tr>");
            }
        }
        String downSection = s.downDomainCount() > 0
            ? "<div style='margin:0 0 18px'>"
              + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
              + "<td bgcolor='#dc2626' style='background:#dc2626;color:#fff;border-radius:8px 8px 0 0;padding:9px 14px;font-size:13px;font-weight:800'>"
              + "⚠ Bu hafta kesinti yaşayan domainler (" + s.downDomainCount() + ")</td></tr></table>"
              + "<table width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#fef2f2' style='background:#fef2f2;border:1px solid #fecaca;border-top:none;border-radius:0 0 8px 8px'>"
              + down + "</table></div>"
            : "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 18px'><tr>"
              + "<td bgcolor='#ecfdf5' style='background:#ecfdf5;border-left:4px solid #16a34a;border-radius:0 8px 8px 0;"
              + "padding:12px 16px;font-size:14px;font-weight:700;color:#15803d'>✓ Bu hafta hiçbir domain kesinti yaşamadı 🎉</td>"
              + "</tr></table>";

        // Domain tablosu (en kötü üstte — servis sıralar)
        StringBuilder body = new StringBuilder();
        body.append("<tr>")
            .append(thCell("Domain", "left")).append(thCell("Erişilebilirlik", "left"))
            .append(thCell("Kesinti", "left")).append(thCell("Yanıt (ort/p95)", "left"))
            .append(thCell("Sertifika", "left")).append("</tr>");
        for (AvailabilityRow r : rows) {
            String pc = pctColor(r.availabilityPct());
            String resp = (r.avgMs() != null)
                ? r.avgMs() + " / " + (r.p95Ms() != null ? r.p95Ms() : "—") + " ms" : "—";
            String certTxt = r.certDaysRemaining() != null ? r.certDaysRemaining() + " gün" : "—";
            String certColor = r.certDaysRemaining() != null && r.certDaysRemaining() <= 30 ? "#dc2626"
                             : r.certDaysRemaining() != null && r.certDaysRemaining() <= 60 ? "#d97706" : "#475569";
            body.append("<tr style='border-top:1px solid #e2e8f0'>")
                .append("<td style='padding:9px 13px;font-size:13px;font-weight:600;color:#1e293b;word-break:break-all'>").append(escHtml(r.domain())).append("</td>")
                .append("<td style='padding:9px 13px;font-size:14px;font-weight:800;color:").append(pc).append(";white-space:nowrap'>").append(pctText(r.availabilityPct())).append("</td>")
                .append("<td style='padding:9px 13px;font-size:13px;color:").append(r.outageCount() > 0 ? "#b91c1c" : "#64748b").append(";white-space:nowrap'>")
                .append(r.outageCount() > 0 ? r.outageCount() + " · " + r.downtimeMinutes() + " dk" : "—").append("</td>")
                .append("<td style='padding:9px 13px;font-size:13px;color:#475569;white-space:nowrap'>").append(resp).append("</td>")
                .append("<td style='padding:9px 13px;font-size:13px;font-weight:600;color:").append(certColor).append(";white-space:nowrap'>").append(certTxt).append("</td>")
                .append("</tr>");
        }
        String table =
            "<div style='margin:0 0 18px'>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";color:#fff;border-radius:8px 8px 0 0;padding:9px 14px;font-size:13px;font-weight:800'>Domain Erişilebilirlik Detayı</td></tr></table>"
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' style='border:1px solid #e2e8f0;border-top:none;border-radius:0 0 8px 8px;border-collapse:collapse'>"
            + body + "</table></div>";

        String bestWorst = (s.bestDomain() != null && s.worstDomain() != null && s.withDataCount() > 0)
            ? "<p style='font-size:13px;color:#475569;margin:0 0 14px;line-height:1.7'>"
              + "En yüksek: <strong style='color:#15803d'>" + escHtml(s.bestDomain()) + "</strong> (" + pctText(s.bestPct()) + ") · "
              + "En düşük: <strong style='color:" + pctColor(s.worstPct()) + "'>" + escHtml(s.worstDomain()) + "</strong> (" + pctText(s.worstPct()) + ")</p>"
            : "";

        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml'"
            + " xmlns:o='urn:schemas-microsoft-com:office:office'>"
            + "<head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + LIGHT_SCHEME_META
            + "<meta http-equiv='X-UA-Compatible' content='IE=edge'>"
            // Outlook (Word motoru) yazı tipi fallback'i — Segoe UI yoksa Arial
            + "<!--[if mso]><style>table,td,div,p{font-family:'Segoe UI',Arial,sans-serif!important}</style><![endif]-->"
            + "<style>@media only screen and (max-width:870px){"
            + ".em-pad{padding:16px 0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-body{padding:14px!important}}</style></head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%;font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            // Dış padding TABLO style'ında değil merkez TD'sinde — Outlook tablo padding'ini yok sayar
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='" + outerBg + "' style='background:" + outerBg + ";mso-table-lspace:0pt;mso-table-rspace:0pt'>"
            + "<tr><td align='center' class='em-pad' bgcolor='" + outerBg + "' style='padding:24px 10px'>"
            // MSO ghost-table: Outlook'ta kartı 850px sabit + ortalı tutar
            + "<!--[if mso]><table role='presentation' width='850' align='center' cellpadding='0' cellspacing='0' border='0'><tr><td><![endif]-->"
            + "<table class='em-card' width='850' cellpadding='0' cellspacing='0' border='0'"
            + " bgcolor='#ffffff' style='max-width:850px;width:100%;background:#ffffff;"
            + "border:1px solid #d7dde5;border-radius:14px;overflow:hidden;box-shadow:0 8px 32px rgba(0,0,0,.10)'>"
            + "<tr><td bgcolor='#ffffff' style='padding:0'>"
            // Üst bar
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0'><tr>"
            + "<td bgcolor='" + accent + "' style='background:" + accent + ";padding:22px 24px'>"
            + "<div style='color:#aebed8;font-size:11px;font-weight:700;letter-spacing:.12em'>CERTMONITOR — HAFTALIK ERİŞİLEBİLİRLİK</div>"
            + "<div style='color:#ffffff;font-size:22px;font-weight:900;margin-top:10px;line-height:1.25'>📊 " + escHtml(teamName) + "</div>"
            + "<div style='color:#dbe3ef;font-size:15px;font-weight:700;margin-top:8px'>" + escHtml(weekLabel) + "</div>"
            + "</td></tr></table>"
            // Gövde — div padding'i Outlook yok sayar → td padding'i (em-body class'ı td'de)
            + "<table width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'><tr>"
            + "<td class='em-body' bgcolor='#ffffff' style='padding:22px 24px'>"
            + "<p style='font-size:15px;color:#0f172a;margin:0 0 6px'><strong>Sayın " + escHtml(teamName) + " ekibi,</strong></p>"
            + "<p style='font-size:14px;color:#334155;line-height:1.7;margin:0 0 14px'>"
            + "Aşağıda sahip olduğunuz domainlerin geçen haftaya (<strong>" + escHtml(weekLabel) + "</strong>) ait erişilebilirlik özeti yer almaktadır.</p>"
            + kpi
            + downSection
            + bestWorst
            + table
            // Footer
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>CertMonitor — Otomatik Haftalık Rapor</td>"
            + "<td align='right' valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>Oluşturuldu: " + generatedAt + "</td></tr></table>"
            + "</td></tr></table>"   // em-body td + gövde tablosu
            + "</td></tr></table>"   // kart iç td + kart tablosu
            + "<!--[if mso]></td></tr></table><![endif]-->"
            + "</td></tr></table></body></html>";   // merkez td + dış tablo
    }

    private String kpiCard(String label, String value, String valueColor, String bg) {
        return "<td width='25%' bgcolor='" + bg + "' style='background:" + bg + ";border:1px solid #e2e8f0;"
            + "border-radius:10px;padding:12px 14px' valign='top'>"
            + "<div style='font-size:22px;font-weight:900;color:" + valueColor + ";line-height:1.1'>" + escHtml(value) + "</div>"
            + "<div style='font-size:10px;font-weight:700;letter-spacing:.08em;color:#94a3b8;margin-top:5px'>" + escHtml(label) + "</div>"
            + "</td>";
    }

    private String thCell(String label, String align) {
        return "<td bgcolor='#1e293b' align='" + align + "' style='background:#1e293b;padding:9px 13px;"
            + "font-size:11px;font-weight:700;letter-spacing:.06em;color:#cbd5e1;white-space:nowrap'>" + escHtml(label) + "</td>";
    }

    /** Availability %'sine göre renk: ≥99.9 yeşil, ≥99 amber, <99 kırmızı, null gri. */
    private static String pctColor(Double pct) {
        if (pct == null) return "#94a3b8";
        if (pct >= 99.9) return "#16a34a";
        if (pct >= 99.0) return "#d97706";
        return "#dc2626";
    }
    private static String pctText(Double pct) {
        if (pct == null) return "veri yok";
        return String.format(java.util.Locale.US, "%.2f%%", pct);
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
    /** 4-arg: mail gönderimi için (forEmail=true → görseller CID inline). */
    public String buildIncidentNotificationHtml(Map<String, Object> inc, String managerName,
                                                String kind, String ctaUrl) {
        return buildIncidentNotificationHtml(inc, managerName, kind, ctaUrl, true);
    }

    /** forEmail=false: UI önizlemesi (iframe) — markdown görselleri /api/incidents/images/{id} URL'siyle
     *  kalır (CID'e çevrilmez), iframe oturum çerezi ile yükler. */
    public String buildIncidentNotificationHtml(Map<String, Object> inc, String managerName,
                                                String kind, String ctaUrl, boolean forEmail) {
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
            .append(kvRow("Durum", statusText(str(inc.get("status"))), statusColor(str(inc.get("status")))))
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

        return "<!DOCTYPE html><html lang='tr' xmlns:v='urn:schemas-microsoft-com:vml'"
            + " xmlns:o='urn:schemas-microsoft-com:office:office'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'>"
            + LIGHT_SCHEME_META
            + "<style>body,table,td{-webkit-text-size-adjust:100%;-ms-text-size-adjust:100%}"
            + ".inc-md p{margin:0;font-size:14px;line-height:1.7;color:#1e293b}"
            + "@media only screen and (max-width:870px){.em-wrap{padding:0!important}"
            + ".em-card{border-radius:0!important;width:100%!important}.em-body{padding:14px!important}}</style></head>"
            + "<body bgcolor='" + outerBg + "' style='margin:0;padding:0;background:" + outerBg
            + ";font-family:\"Segoe UI\",Tahoma,Arial,sans-serif'>"
            + "<table class='em-wrap' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='" + outerBg
            + "' style='background:" + outerBg + ";padding:24px 10px'><tr><td align='center' bgcolor='" + outerBg + "'>"
            // MSO ghost-table: Outlook'ta kartı 850px sabit + ortalı tutar (availability deseni)
            + "<!--[if mso]><table role='presentation' width='850' align='center' cellpadding='0' cellspacing='0' border='0'><tr><td><![endif]-->"
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
            // ── Gövde ── (div padding'i Outlook yok sayar → td-tabanlı em-body)
            + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' bgcolor='#ffffff'><tr>"
            + "<td class='em-body' bgcolor='#ffffff' style='padding:22px 24px'>"
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
            + reportSection("Kök Neden (RCA)", textBlock(str(inc.get("rca_summary")), forEmail), accent)
            + incidentSectionOpt("Teknik Açıklama", str(inc.get("description")), accent, forEmail)
            + reportSection("İş Etkisi", textBlock(str(inc.get("business_impact")), forEmail), accent)
            + reportSection("Çözüm / Müdahale Adımları", textBlock(str(inc.get("resolution_steps")), forEmail), accent)
            + cta
            // ── Footer ──
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + "CertMonitor — Olay & Hata Bildirimi</td>"
            + "<td align='right' valign='top' style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;"
            + "color:#94a3b8'>Oluşturuldu: " + generatedAt + "</td></tr></table>"
            + "</td></tr></table></td></tr></table>"
            + "<!--[if mso]></td></tr></table><![endif]-->"
            + "</td></tr></table></body></html>";
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

    /** Künye satırı — renkli + kalın değer (örn. Durum: Çözüldü=yeşil). */
    private String kvRow(String label, String value, String valueColor) {
        String v = (value == null || value.isBlank()) ? "—" : escHtml(value);
        return "<tr>"
            + "<td bgcolor='#f8fafc' style='background:#f8fafc;border:1px solid #e2e8f0;padding:8px 12px;"
            + "font-size:13px;font-weight:700;color:#475569;width:38%;vertical-align:top'>" + escHtml(label) + "</td>"
            + "<td style='border:1px solid #e2e8f0;padding:8px 12px;font-size:13px;color:" + valueColor + ";"
            + "font-weight:700;vertical-align:top;word-break:break-word'>" + v + "</td></tr>";
    }

    /** Markdown metin → e-posta-güvenli paragraf: görsel sözdizimini at, escape + satır sonu→&lt;br&gt;. */
    private static final Pattern INC_CID_IMG = Pattern.compile("<img src=\"cid:incimg(\\d+)\"");
    private static final Pattern INC_API_IMG = Pattern.compile("<img src=\"(/api/incidents/images/\\d+)\"");

    /** Olay markdown alanı → HTML: TAM markdown (GFM tablo/liste/kalın/görev kutusu) + gömülü görseller.
     *  forEmail=true → /api/incidents/images/{id} CID inline (mail; InlineImage'ları IncidentNotificationService
     *  yükler). forEmail=false → /api URL korunur (iframe önizleme, oturum çerezi ile yüklenir).
     *  Outlook head&lt;style&gt;'ı yok saydığından blok stilleri inline edilir. escapeHtml=true → ham HTML güvenli. */
    private String textBlock(String md, boolean forEmail) {
        if (md == null || md.isBlank()) return "";
        String src = forEmail
                ? md.replaceAll("\\]\\(/api/incidents/images/(\\d+)\\)", "](cid:incimg$1)")
                : md;
        String html = taskCheckboxesToSymbols(MD_RENDERER.render(MD_PARSER.parse(src)));
        html = forEmail
                ? INC_CID_IMG.matcher(html).replaceAll(
                    "<img width=\"680\" border=\"0\" alt=\"Olay görseli\" style=\"display:block;width:100%;max-width:680px;height:auto;"
                    + "border-radius:8px;margin:8px 0;border:1px solid #e2e8f0\" src=\"cid:incimg$1\"")
                : INC_API_IMG.matcher(html).replaceAll(
                    "<img style=\"display:block;max-width:100%;height:auto;border-radius:8px;margin:8px 0;"
                    + "border:1px solid #e2e8f0\" src=\"$1\"");
        return inlineBlockStyles(html);
    }

    /** Boş değilse bölüm kutusu üretir (boş markdown alanında boş kutu render etmemek için). */
    private String incidentSectionOpt(String title, String md, String accent, boolean forEmail) {
        String body = textBlock(md, forEmail);
        return body.isBlank() ? "" : reportSection(title, body, accent);
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

    /** Durum rengi (künye "Durum" hücresi) — severity renk deseninin eşi. */
    private static String statusColor(String st) {
        if (st == null) return "#6b7280";
        return switch (st) {
            case "RESOLVED"      -> "#15803d";  // yeşil
            case "OPEN"          -> "#dc2626";  // kırmızı
            case "INVESTIGATING" -> "#ea580c";  // turuncu
            case "MITIGATED"     -> "#f59e0b";  // amber
            default              -> "#6b7280";  // gri
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

    /** Haftalık rapor e-postası hero KPI özeti — canlı cert/alarm/uptime (WeeklyReportKpiService.current).
     *  Anahtarlar: total_certs, expiring, alarms, critical, uptime_pct. null/boş → hiç gösterilmez. */
    private String weeklyKpiBlock(Map<String, Object> k) {
        if (k == null || k.isEmpty()) return "";
        Object up = k.get("uptime_pct");
        String uptime = up instanceof Number n ? String.format(java.util.Locale.US, "%.2f%%", n.doubleValue()) : "—";
        return "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 16px'>"
            + "<tr><td style='font-size:11px;font-weight:700;letter-spacing:.08em;color:#64748b;"
            + "padding-bottom:8px;text-transform:uppercase'>Haftalık Özet</td></tr>"
            + "<tr><td>"
            + numChipRow(
                numChip("Toplam Sertifika", k.get("total_certs"), "#334155"),
                numChip("Bu Hafta Dolan",   k.get("expiring"),    "#d97706"),
                numChip("Açılan Alarm",     k.get("alarms"),      "#dc2626"),
                numChip("Kritik ≤7",   k.get("critical"),    "#ea580c"),
                numChip("Uptime",           uptime,               "#16a34a"))
            + "</td></tr></table>";
    }

    /** Özet + KPI şeridini "Haftalık Özet ve Göstergeler" başlıklı rapor bölümüne sarar (ekrandaki akordeonla tutarlı).
     *  İçerik yoksa (eski/veri-yok) boş bölüm ÇİZMEZ. */
    private String weeklyOverviewSection(Map<String, Object> kpiSummary, String accent) {
        String body = weeklySummaryBlock(kpiSummary) + weeklyKpiBlock(kpiSummary) + weeklyMonitoringBlock(kpiSummary);
        return body.isBlank() ? "" : reportSection("Haftalık Özet ve Göstergeler", body, accent);
    }

    /** İzleme göstergeleri — tür başına tek satır (tür · izleme · erişim% · sorun), Outlook-safe.
     *  Anahtar: monitoring (List&lt;Map&gt;: type/active/success_pct/alarms). İzlemesi 0 olan tür satırı gizlenir; hiç yoksa boş. */
    @SuppressWarnings("unchecked")
    private String weeklyMonitoringBlock(Map<String, Object> k) {
        Object mon = k == null ? null : k.get("monitoring");
        if (!(mon instanceof List<?> list) || list.isEmpty()) return "";
        Map<String, String> labels = Map.of("cert", "Sertifika", "domain", "Alan Adı", "http", "HTTP/Website",
                "ping", "Ping", "port", "Port", "dns", "DNS", "keyword", "Keyword");
        StringBuilder rows = new StringBuilder();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) continue;
            int active = row.get("active") instanceof Number n ? n.intValue() : 0;
            if (active == 0) continue;
            String label = labels.getOrDefault(String.valueOf(row.get("type")), String.valueOf(row.get("type")));
            Object rate = row.get("success_pct");
            String rateStr = rate instanceof Number rn ? String.format(java.util.Locale.US, "%.1f%%", rn.doubleValue()) : "—";
            int alarms = row.get("alarms") instanceof Number an ? an.intValue() : 0;
            String alarmColor = alarms > 0 ? "#dc2626" : "#64748b";
            rows.append("<tr>")
              .append("<td style='padding:5px 8px;font-size:13px;color:#334155;border-bottom:1px solid #eef1f4'>").append(escHtml(label)).append("</td>")
              .append("<td align='right' style='padding:5px 8px;font-size:13px;color:#334155;border-bottom:1px solid #eef1f4'>").append(active).append("</td>")
              .append("<td align='right' style='padding:5px 8px;font-size:13px;font-weight:700;color:#334155;border-bottom:1px solid #eef1f4'>").append(rateStr).append("</td>")
              .append("<td align='right' style='padding:5px 8px;font-size:13px;font-weight:700;color:").append(alarmColor).append(";border-bottom:1px solid #eef1f4'>").append(alarms).append("</td>")
              .append("</tr>");
        }
        if (rows.length() == 0) return "";
        String head = "<div style='font-size:10px;font-weight:700;letter-spacing:.08em;color:#64748b;text-transform:uppercase;margin:12px 0 4px'>İzleme Göstergeleri</div>";
        String th = "<tr>"
              + "<td style='padding:5px 8px;font-size:11px;font-weight:700;color:#94a3b8;text-transform:uppercase;border-bottom:1px solid #e5e8ec'>Tür</td>"
              + "<td align='right' style='padding:5px 8px;font-size:11px;font-weight:700;color:#94a3b8;text-transform:uppercase;border-bottom:1px solid #e5e8ec'>İzleme</td>"
              + "<td align='right' style='padding:5px 8px;font-size:11px;font-weight:700;color:#94a3b8;text-transform:uppercase;border-bottom:1px solid #e5e8ec'>Erişim</td>"
              + "<td align='right' style='padding:5px 8px;font-size:11px;font-weight:700;color:#94a3b8;text-transform:uppercase;border-bottom:1px solid #e5e8ec'>Sorun</td>"
              + "</tr>";
        return head + "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 12px;border-collapse:collapse'>"
                + th + rows + "</table>";
    }

    /** Executive özet bloğu (e-posta) — sağlık skoru + yönetici paragrafı + iki kompakt tablo (aksiyon, 30 gün).
     *  Anahtarlar: score/score_band/manager_text/actions/lookahead. Skor yoksa (eski/veri-yok) → boş. Outlook-safe. */
    @SuppressWarnings("unchecked")
    private String weeklySummaryBlock(Map<String, Object> k) {
        if (k == null || k.get("score") == null) return "";
        int score = ((Number) k.get("score")).intValue();
        String band = String.valueOf(k.getOrDefault("score_band", "red"));
        String bandColor = "green".equals(band) ? "#16a34a" : "amber".equals(band) ? "#d97706" : "#dc2626";
        String para = escHtml(String.valueOf(k.getOrDefault("manager_text", "")));
        String head =
            "<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 14px'><tr>"
            + "<td valign='top' width='120' style='padding:0 16px 0 0;white-space:nowrap'>"
            + "<div style='font-size:34px;font-weight:800;color:" + bandColor + ";line-height:1'>" + score
            + "<span style='font-size:15px;color:#94a3b8'>/100</span></div>"
            + "<div style='font-size:10px;font-weight:700;letter-spacing:.08em;color:#94a3b8;text-transform:uppercase;margin-top:2px'>Haftalık Sağlık Skoru</div>"
            + "</td>"
            + "<td valign='top' style='font-size:14px;line-height:1.7;color:#334155'>" + para + "</td>"
            + "</tr></table>";
        // Önümüzdeki 30 Gün: kayıt yoksa bölüm hiç eklenmez (kullanıcı isteği) — Aksiyon tablosu ise boşken "Kayıt yok" gösterir.
        List<Map<String, Object>> lookahead = (List<Map<String, Object>>) k.get("lookahead");
        return head
            + summaryActionTable("Aksiyon Gerektirenler", (List<Map<String, Object>>) k.get("actions"))
            + (lookahead == null || lookahead.isEmpty() ? "" : summaryActionTable("Önümüzdeki 30 Gün", lookahead));
    }

    /** Özet aksiyon/14-gün tablosu — td/bgcolor tier şeridi, sağa hizalı gün (Outlook-safe). Boş → "Kayıt yok". */
    private String summaryActionTable(String title, List<Map<String, Object>> items) {
        String hdr = "<div style='font-size:10px;font-weight:700;letter-spacing:.08em;color:#64748b;"
                + "text-transform:uppercase;margin:8px 0 4px'>" + escHtml(title) + "</div>";
        if (items == null || items.isEmpty()) {
            return hdr + "<div style='font-size:13px;color:#94a3b8;margin-bottom:10px'>Kayıt yok</div>";
        }
        StringBuilder sb = new StringBuilder(hdr);
        sb.append("<table role='presentation' width='100%' cellpadding='0' cellspacing='0' border='0' style='margin:0 0 12px;border-collapse:collapse'>");
        for (Map<String, Object> a : items) {
            String stripe = tierColor(a.get("tier"));
            String type = "domain".equals(a.get("type")) ? "Domain" : "Sertifika";
            Object days = a.get("days_left");
            sb.append("<tr>")
              .append("<td width='4' bgcolor='").append(stripe).append("' style='background-color:").append(stripe)
              .append(";width:4px;font-size:0;line-height:0'>&nbsp;</td>")
              .append("<td style='padding:6px 10px;font-size:13px;color:#334155;border-bottom:1px solid #eef1f4'>")
              .append(escHtml(String.valueOf(a.get("name")))).append(" <span style='color:#94a3b8;font-size:11px'>").append(type).append("</span></td>")
              .append("<td align='right' style='padding:6px 10px;font-size:13px;font-weight:700;color:#334155;border-bottom:1px solid #eef1f4;white-space:nowrap'>")
              .append(days != null ? days + " gün" : "—").append("</td></tr>");
        }
        return sb.append("</table>").toString();
    }

    private static String tierColor(Object tier) {
        int t = tier instanceof Number n ? n.intValue() : 0;
        return switch (t) { case 1 -> "#dc2626"; case 2 -> "#d97706"; case 3 -> "#2563eb"; default -> "#94a3b8"; };
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
