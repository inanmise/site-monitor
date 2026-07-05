package com.certmonitor.service;

import com.certmonitor.model.SmtpSettings;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * SMTP diagnostics driven by the current {@link SmtpSettings} (built on-the-fly
 * so the env-configured {@code JavaMailSender} used by real notifications is left
 * untouched). Phase 1 exposes:
 * <ul>
 *   <li>{@link #testConnection()} — opens/authenticates the SMTP transport without sending mail.</li>
 *   <li>{@link #sendTest(String)} — sends a real test message to a recipient.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmtpMailService {

    // Mail'e özel logger (EmailNotificationService ile ortak) — bağımsız TRACE toggle:
    // logging.level.com.certmonitor.mail=TRACE. Tanılama uçlarının detayları buraya gider.
    private static final Logger MAIL_LOG = LoggerFactory.getLogger("com.certmonitor.mail");

    private final SmtpSettingsService settingsService;

    /** Validates host/port/security/auth via JavaMail Transport.connect() — sends nothing. */
    public Map<String, Object> testConnection() {
        Map<String, Object> out = new LinkedHashMap<>();
        SmtpSettings s = settingsService.getOrDefaults();
        if (s.getHost() == null || s.getHost().isBlank()) {
            out.put("success", false);
            out.put("error", "SMTP host yapılandırılmamış");
            return out;
        }
        long start = System.currentTimeMillis();
        if (MAIL_LOG.isTraceEnabled()) MAIL_LOG.trace("→ SMTP testConnection: {}", smtpContextOf(s));
        try {
            buildSender(s).testConnection();
            long ms = System.currentTimeMillis() - start;
            out.put("success", true);
            out.put("message", "Bağlantı başarılı (" + s.getHost() + ":" + s.getPort() + ")");
            out.put("elapsed_ms", ms);
            if (MAIL_LOG.isTraceEnabled()) MAIL_LOG.trace("✓ SMTP testConnection OK: süre={}ms | {}", ms, smtpContextOf(s));
        } catch (Exception e) {
            out.put("success", false);
            out.put("error", rootMessage(e));
            // Son arg `e` (Throwable) → tam stack. Admin-tetikli tanılama olduğundan WARN.
            log.warn("✗ SMTP test bağlantısı başarısız: {} | {}", rootMessage(e), smtpContextOf(s), e);
        }
        return out;
    }

    /** Sends a one-off test email to {@code to} using the saved/effective SMTP settings. */
    public Map<String, Object> sendTest(String to) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (to == null || to.isBlank()) {
            out.put("success", false);
            out.put("error", "Alıcı adresi boş olamaz");
            return out;
        }
        SmtpSettings s = settingsService.getOrDefaults();
        if (s.getHost() == null || s.getHost().isBlank()) {
            out.put("success", false);
            out.put("error", "SMTP host yapılandırılmamış");
            return out;
        }
        String from = (s.getFromAddress() != null && !s.getFromAddress().isBlank())
                ? s.getFromAddress() : s.getUsername();
        if (from == null || from.isBlank()) {
            out.put("success", false);
            out.put("error", "From adresi tanımlı değil");
            return out;
        }
        long start = System.currentTimeMillis();
        if (MAIL_LOG.isTraceEnabled())
            MAIL_LOG.trace("→ SMTP sendTest: TO={} | from={} | {}", to.trim(), from, smtpContextOf(s));
        try {
            JavaMailSenderImpl sender = buildSender(s);
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setTo(to.trim());
            if (s.getFromName() != null && !s.getFromName().isBlank()) {
                helper.setFrom(from, s.getFromName());
            } else {
                helper.setFrom(from);
            }
            helper.setSubject("[CertMonitor] SMTP test e-postası");
            helper.setText(buildTestHtml(s), true);
            sender.send(msg);
            out.put("success", true);
            out.put("message", "Test e-postası gönderildi: " + to.trim());
            if (MAIL_LOG.isTraceEnabled())
                MAIL_LOG.trace("✓ SMTP sendTest OK: TO={} | süre={}ms", to.trim(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            out.put("success", false);
            out.put("error", rootMessage(e));
            // Son arg `e` (Throwable) → tam stack. Admin-tetikli tanılama olduğundan WARN.
            log.warn("✗ SMTP test e-postası başarısız: TO={} | {} | {}", to.trim(), rootMessage(e), smtpContextOf(s), e);
        }
        return out;
    }

    /**
     * JavaMailSenderImpl built from the current effective SMTP settings — used by the
     * real notification pipeline (EmailNotificationService) so all outbound mail honours
     * the admin Settings screen. Falls back to env config when nothing is saved.
     */
    public JavaMailSenderImpl currentSender() {
        return buildSender(settingsService.getOrDefaults());
    }

    // ── internals ────────────────────────────────────────────────────────────

    private JavaMailSenderImpl buildSender(SmtpSettings s) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(s.getHost());
        sender.setPort(s.getPort() != null ? s.getPort() : 587);
        sender.setDefaultEncoding("UTF-8");

        boolean auth = Boolean.TRUE.equals(s.getAuthEnabled());
        if (auth && s.getUsername() != null && !s.getUsername().isBlank()) {
            sender.setUsername(s.getUsername());
            String pw = settingsService.effectivePassword();
            sender.setPassword(pw != null ? pw : "");
        }

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.starttls.enable", String.valueOf(Boolean.TRUE.equals(s.getStartTlsEnable())));
        props.put("mail.smtp.starttls.required", String.valueOf(Boolean.TRUE.equals(s.getStartTlsRequired())));
        if (s.getSslTrust() != null && !s.getSslTrust().isBlank()) {
            props.put("mail.smtp.ssl.trust", s.getSslTrust());
        }
        props.put("mail.smtp.connectiontimeout", String.valueOf(orDefault(s.getConnectionTimeoutMs(), 10000)));
        props.put("mail.smtp.timeout", String.valueOf(orDefault(s.getReadTimeoutMs(), 15000)));
        props.put("mail.smtp.writetimeout", String.valueOf(orDefault(s.getWriteTimeoutMs(), 15000)));
        return sender;
    }

    private String buildTestHtml(SmtpSettings s) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        return "<div style=\"font-family:Segoe UI,Arial,sans-serif;max-width:520px;margin:0 auto;"
                + "padding:22px;color:#1e293b\">"
                + "<h2 style=\"color:#2563eb;margin-top:0\">CertMonitor — SMTP test</h2>"
                + "<p>Bu, CertMonitor Ayarlar ekranından gönderilen bir SMTP test e-postasıdır.</p>"
                + "<table style=\"border-collapse:collapse;font-size:.92em;margin:10px 0\">"
                + "<tr><td style=\"padding:3px 12px 3px 0;color:#64748b\">Sunucu:</td>"
                + "<td style=\"padding:3px 0;font-weight:600\">" + esc(s.getHost()) + ":" + s.getPort() + "</td></tr>"
                + "<tr><td style=\"padding:3px 12px 3px 0;color:#64748b\">STARTTLS:</td>"
                + "<td style=\"padding:3px 0\">" + Boolean.TRUE.equals(s.getStartTlsEnable()) + "</td></tr>"
                + "<tr><td style=\"padding:3px 12px 3px 0;color:#64748b\">Zaman:</td>"
                + "<td style=\"padding:3px 0\">" + now + "</td></tr>"
                + "</table>"
                + "<p style=\"font-size:.85em;color:#94a3b8\">Bu e-postayı aldıysanız SMTP ayarlarınız çalışıyor demektir.</p>"
                + "</div>";
    }

    private static int orDefault(Integer v, int def) {
        return v != null ? v : def;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Tek satır SMTP bağlamı (host/port/auth/TLS/timeout) — PAROLA ASLA dahil edilmez. */
    private static String smtpContextOf(SmtpSettings s) {
        boolean auth = Boolean.TRUE.equals(s.getAuthEnabled());
        return "smtp=" + s.getHost() + ":" + (s.getPort() != null ? s.getPort() : 587)
                + " auth=" + (auth ? "on" : "off")
                + " user=" + (s.getUsername() != null ? s.getUsername() : "-")
                + " starttls=" + Boolean.TRUE.equals(s.getStartTlsEnable())
                + "/req=" + Boolean.TRUE.equals(s.getStartTlsRequired())
                + " sslTrust=" + (s.getSslTrust() != null && !s.getSslTrust().isBlank() ? s.getSslTrust() : "-")
                + " timeout(conn/read/write)=" + orDefault(s.getConnectionTimeoutMs(), 10000)
                + "/" + orDefault(s.getReadTimeoutMs(), 15000)
                + "/" + orDefault(s.getWriteTimeoutMs(), 15000) + "ms";
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : cur.getClass().getSimpleName();
    }
}
