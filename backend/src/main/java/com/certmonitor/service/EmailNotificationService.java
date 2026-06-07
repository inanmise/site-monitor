package com.certmonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationService {

    private final JavaMailSender mailSender;

    @Value("${cert.monitor.email.enabled:false}")
    private boolean enabled;

    @Value("${cert.monitor.email.from:noreply@certmonitor}")
    private String emailFrom;

    // How long to wait before retrying a transient 421 rate-limit rejection (default 90 s)
    @Value("${mail.send.retry-delay-ms:90000}")
    private long retryDelayMs;

    public String getEmailFrom() { return emailFrom; }

    public String sendAlert(String to, String subject, String message) {
        return sendAlert(to, subject, message, null, null, null, null, null);
    }

    public String sendAlert(String to, String subject, String message,
                            String domain, String level, String alertType,
                            Integer daysRemaining, Map<String, Object> certContext) {
        if (!enabled) {
            log.info("⚠ Email devre dışı — TO={} | KONU={}", to, subject);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            helper.setFrom(emailFrom);
            helper.setSubject(subject);
            String html = (domain != null)
                    ? buildRichAlertHtml(subject, message, domain, level, alertType, daysRemaining, certContext)
                    : buildSimpleAlertHtml(subject, message);
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
        if (!enabled) {
            log.info("⚠ Email devre dışı — TO={} | KONU={}", Arrays.toString(toAddresses), subject);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(toAddresses);
            helper.setFrom(emailFrom);
            helper.setSubject(subject);
            helper.setText(buildRichAlertHtml(subject, message, domain, level,
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
        if (!enabled) {
            log.info("⚠ Email devre dışı — çözüm bildirimi: TO={}", Arrays.toString(toAddresses));
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(toAddresses);
            helper.setFrom(emailFrom);
            helper.setSubject(subject);
            helper.setText(buildRichResolvedHtml(domain, alertType, alertLevel,
                    daysRemaining, resolvedBy, resolvedAt, createdAt, certContext), true);
            return doSend(Arrays.toString(toAddresses), msg, 1);
        } catch (Exception e) {
            log.error("✗ Çözüm e-postası hazırlanamadı: TO={} | HATA={}", Arrays.toString(toAddresses), e.getMessage());
            return "FAILED: " + e.getMessage();
        }
    }

    private String doSend(String to, MimeMessage msg, int attempt) {
        try {
            mailSender.send(msg);
            log.info("✓ E-posta gönderildi: TO={}", to);
            return "SENT";
        } catch (Exception e) {
            String err = e.getMessage() != null ? e.getMessage() : "";
            // 421 = transient rate-limit from SMTP gateway — wait and retry once
            // Check both getMessage() and toString() because MailSendException may wrap the inner cause
            String errFull = err + " " + e.toString();
            if (attempt == 1 && errFull.contains("421")) {
                log.warn("⏳ SMTP 421 rate limit — {}ms sonra tekrar deneniyor: TO={}", retryDelayMs, to);
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return "FAILED (interrupted): " + err;
                }
                return doSend(to, msg, 2);
            }
            log.error("✗ E-posta gönderilemedi: TO={} | HATA={}", to, err);
            return "FAILED: " + err;
        }
    }

    /** Rich resolution email with full context (manual or auto resolve). */
    public String sendResolutionAlert(String to, String subject,
                                      String domain, String alertType, String alertLevel,
                                      Integer daysRemaining, String resolvedBy, String resolvedAt,
                                      String createdAt, Map<String, Object> certContext) {
        if (!enabled) {
            log.info("⚠ Email devre dışı — çözüm bildirimi: TO={}", to);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            helper.setFrom(emailFrom);
            helper.setSubject(subject);
            helper.setText(buildRichResolvedHtml(domain, alertType, alertLevel,
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
        if (!enabled) {
            log.info("⚠ Email devre dışı — şifre sıfırlama: TO={}", toAddress);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(toAddress);
            helper.setFrom(emailFrom);
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
        if (!enabled) {
            log.info("⚠ Email devre dışı — admin network alert: TO={}", to);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            helper.setFrom(emailFrom);
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
        if (!enabled) {
            log.info("⚠ Email devre dışı — admin network resolved: TO={}", to);
            return "SKIPPED_DISABLED";
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setTo(to);
            helper.setFrom(emailFrom);
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
                "<td style='padding:6px 10px;border:1px solid #e5e7eb;'>" + detectedAt + "</td></tr>" +
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
                "<td style='padding:6px 10px;border:1px solid #e5e7eb;'>" + detectedAt + "</td></tr>" +
                "<tr><th style='text-align:left;padding:6px 10px;background:#f3f4f6;border:1px solid #e5e7eb;'>Çözüm Zamanı</th>" +
                "<td style='padding:6px 10px;border:1px solid #e5e7eb;'>" + resolvedAt + "</td></tr>" +
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

    public String buildAlertEmailHtml(String subject, String message,
                                       String domain, String level, String alertType,
                                       Integer daysRemaining, Map<String, Object> certContext) {
        return (domain != null)
                ? buildRichAlertHtml(subject, message, domain, level, alertType, daysRemaining, certContext)
                : buildSimpleAlertHtml(subject, message);
    }

    public String buildResolutionEmailHtml(String domain, String alertType, String alertLevel,
                                            Integer daysRemaining, String resolvedBy,
                                            String resolvedAt, String createdAt,
                                            Map<String, Object> certContext) {
        return buildRichResolvedHtml(domain, alertType, alertLevel,
                daysRemaining, resolvedBy, resolvedAt, createdAt, certContext);
    }

    // ── HTML builders ────────────────────────────────────────────────────────

    private String buildRichAlertHtml(String subject, String message,
                                       String domain, String level, String alertType,
                                       Integer daysRemaining, Map<String, Object> certContext) {
        String generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

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
            LocalDateTime dt = LocalDateTime.parse(iso, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
            String[] months = {"Ocak","Şubat","Mart","Nisan","Mayıs","Haziran",
                               "Temmuz","Ağustos","Eylül","Ekim","Kasım","Aralık"};
            String[] days = {"Pazartesi","Salı","Çarşamba","Perşembe","Cuma","Cumartesi","Pazar"};
            String month   = months[dt.getMonthValue() - 1];
            String dayName = days[dt.getDayOfWeek().getValue() - 1];
            return dt.getDayOfMonth() + " " + month + " " + dt.getYear() + ", " + dayName
                 + " — " + String.format("%02d:%02d", dt.getHour(), dt.getMinute()) + " UTC";
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

    private String formatIso(String iso) {
        if (iso == null || iso.isBlank()) return "—";
        try {
            // yyyy-MM-dd'T'HH:mm:ss → dd.MM.yyyy HH:mm
            return iso.substring(8, 10) + "." + iso.substring(5, 7) + "." + iso.substring(0, 4)
                 + " " + iso.substring(11, 16);
        } catch (Exception e) {
            return iso;
        }
    }

    private String buildSimpleAlertHtml(String subject, String message) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
        String color = subject.contains("KRİTİK") || subject.contains("CRITICAL") ? "#dc2626"
                : subject.contains("YÜKSEK") || subject.contains("HIGH") ? "#ea580c" : "#d97706";
        String css = "<style>"
            + "@media only screen and (max-width:600px){"
            + ".em-wrap{padding:0!important}.em-card{border-radius:0!important;width:100%!important}"
            + ".em-body{padding:16px!important}"
            + "}"
            + "</style>";
        return "<!DOCTYPE html><html lang='tr'>"
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
            + "<div style='text-align:center;color:#94a3b8;font-size:11px;margin-top:20px;"
            + "padding-top:16px;border-top:1px solid #f1f5f9'>CertMonitor &nbsp;·&nbsp; " + now + "</div>"
            + "</td></tr></table>"
            + "</td></tr></table>"
            + "</body></html>";
    }

    private String buildRichResolvedHtml(String domain, String alertType, String alertLevel,
                                          Integer daysRemaining, String resolvedBy, String resolvedAt,
                                          String createdAt, Map<String, Object> certContext) {
        String generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
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
            + tableRow2col("🕐 Çözülme Tarihi",  formatIso(resolvedAt))
            + tableRow2col("📅 Alarm Oluşturma", formatIso(createdAt))
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
            + "letter-spacing:.1em;color:#94a3b8'>ALARM DETAYI</td></tr>"
            + alertRows + "</table></td>"
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
            + "letter-spacing:.12em'>CertMonitor — Sertifika İzleme</div>"
            + "<div class='em-domain' style='color:#fff;font-size:22px;font-weight:900;"
            + "margin-top:10px;word-break:break-all;line-height:1.25'>✅ " + escHtml(domain) + "</div>"
            + "<div style='color:rgba(255,255,255,.88);font-size:15px;font-weight:700;"
            + "margin-top:8px;letter-spacing:.02em'>Sorun Giderildi &nbsp;·&nbsp; " + typeTr + "</div>"
            + "</div>"

            // Body
            + "<div class='em-body' style='background:#fff;padding:22px 24px'>"

            // Hero checkmark
            + "<div style='text-align:center;margin:16px 0 24px'>"
            + "<div style='display:inline-block;background:#dcfce7;border-radius:50%;width:80px;"
            + "height:80px;line-height:80px;font-size:42px;border:3px solid " + green + "'>✓</div>"
            + "<div style='margin-top:14px;font-size:20px;font-weight:800;color:#15803d;"
            + "letter-spacing:-.3px'>Sorun Başarıyla Giderildi</div>"
            + "<div style='margin-top:6px;font-size:13px;color:#64748b'>"
            + "Bu alarm artık kapalıdır. Sertifika izleme devam etmektedir.</div>"
            + "</div>"

            + twoColSection

            // Info box
            + "<div style='background:#f0fdf4;border-left:4px solid " + green + ";"
            + "border-radius:0 8px 8px 0;padding:14px 18px;color:#14532d;"
            + "font-size:14px;line-height:1.7;margin-bottom:20px'>"
            + "<div style='font-size:11px;font-weight:700;letter-spacing:.08em;"
            + "color:" + green + ";margin-bottom:6px'>BİLGİ</div>"
            + "<strong>" + escHtml(domain) + "</strong> için açık olan sertifika alarmı "
            + "<strong>" + escHtml(by) + "</strong> tarafından <strong>çözüldü</strong> olarak işaretlendi. "
            + "Uyarı tipi: <strong>" + typeTr + "</strong> &nbsp;|&nbsp; Önceki alarm seviyesi: "
            + "<strong style='color:" + levelColor + "'>" + levelTr + "</strong>."
            + "</div>"

            // Footer
            + "<table width='100%' cellpadding='0' cellspacing='0'><tr>"
            + "<td style='border-top:1px solid #f1f5f9;padding-top:12px;font-size:11px;color:#94a3b8'>"
            + "CertMonitor</td>"
            + "<td align='right' style='border-top:1px solid #f1f5f9;padding-top:12px;"
            + "font-size:11px;color:#94a3b8'>Bildirim: " + generatedAt + "</td>"
            + "</tr></table>"

            + "</div>"               // em-body
            + "</td></tr></table>"   // em-card
            + "</td></tr></table>"   // em-wrap
            + "</body></html>";
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
