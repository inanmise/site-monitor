package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.TechErrorReportMail;
import com.sitemonitor.service.report.TechErrorReportPdfWriter;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Teknik hata raporunu PDF eki olarak TAKIMIN e-posta adresine gönderir (2026-10-13, kullanıcı isteği: "raporun pdf
 * olarak raporu oluşturan ekibin mailine iletilmesini sağlamalıyız").
 *
 * <p>Akış: alıcı denetimi (takım adresi yoksa 409 {@code TEAM_EMAIL_MISSING}, hiçbir şey yazılmaz) → TALEP (koşullu
 * UPDATE, {@code SENDING}; aynı rapor başka sekmede / podda gönderiliyorsa 409 {@code SEND_IN_PROGRESS}) → PDF → posta →
 * SONUÇ (SENT / QUEUED / SKIPPED / FAILED). Talepten sonra ne olursa olsun satır son durumuna yazılır (try/finally);
 * pod gönderim ortasında ölürse {@code SENDING} 10 dakika sonra elle yeniden gönderilebilir. Her karar bir
 * {@code notification_logs} satırı bırakır (tetikleyici {@code TECH_ERROR_REPORT}). Zamanlanmış iş yok; sistem bakımı
 * sessize alması uygulanmaz (elle, kullanıcı isteğiyle gönderilen rapor — diğer raporlarla aynı).
 */
@Slf4j
@Service
public class TechErrorReportSender {

    public static final String TRIGGER = "TECH_ERROR_REPORT";
    static final Duration STALE_CLAIM = Duration.ofMinutes(10);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Pattern DELIVERABLE = Pattern.compile("[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]{2,}");

    private final TechErrorReportService service;
    private final TechErrorReportRepository reports;
    private final EmailNotificationService email;
    private final NotificationLogRepository logs;
    private final AppSettingsService appSettings;

    /** Pasif kullanıcı adres süzgeci (yoksa — dilim testleri — kayıt tüm adresleri listeler). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.sitemonitor.service.InactiveRecipientGuard inactiveGuard;

    /** E-postaya eklenecek PDF'in üst sınırı (kurumsal posta sınırı; aşarsa PDF indirilip elle gönderilir). */
    @Value("${site.monitor.tech-error-report.max-mail-mb:15}")
    int maxMailMb = 15;

    public TechErrorReportSender(TechErrorReportService service, TechErrorReportRepository reports,
                                 EmailNotificationService email, NotificationLogRepository logs, AppSettingsService appSettings) {
        this.service = service;
        this.reports = reports;
        this.email = email;
        this.logs = logs;
        this.appSettings = appSettings;
    }

    /** Gönderim sonucu: {@code state} SENT | QUEUED | SKIPPED | FAILED; {@code to} gerçek alıcılar. */
    public record Result(String state, String status, List<String> to, int pdfBytes, String subject) { }

    /** Takım adresinden gönderilebilir adresler (virgül / noktalı virgül ayraçlı; biçimsizler atılır, tekrarlar tekilleşir). */
    public static List<String> recipients(Team team) {
        if (team == null || team.getEmail() == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        String[] parts = team.getEmail().split("[,;]");
        for (int i = 0; i < parts.length && out.size() < 20; i++) {
            String e = parts[i].trim();
            if (e.isEmpty() || e.length() > 254 || !DELIVERABLE.matcher(e).matches()) continue;
            if (seen.add(e.toLowerCase(Locale.ROOT))) out.add(e);
        }
        return out;
    }

    public Result send(TechErrorReport report, TechErrorChannel channel, Team team, Long by) {
        List<String> to = recipients(team);
        if (to.isEmpty()) {
            throw new CodedConflictException("TEAM_EMAIL_MISSING", Msg.t(
                    "Takımın kayıtlı bir e-posta adresi yok. Yöneticiniz Admin → Takımlar'da adres ekledikten sonra tekrar gönderin; "
                            + "o zamana kadar PDF'i indirip elle iletebilirsiniz.",
                    "The team has no e-mail address on record. Send again after an administrator adds one under Admin → Teams; "
                            + "until then you can download the PDF and forward it yourself."));
        }
        Instant claimedAt = service.now();
        if (reports.claimSend(report.getId(), claimedAt, claimedAt.minus(STALE_CLAIM)) == 0) {
            if (!reports.existsById(report.getId())) throw TechErrorReportService.reportNotFound();
            throw new CodedConflictException("SEND_IN_PROGRESS", Msg.t(
                    "Bu rapor şu anda gönderiliyor (başka bir sekmede ya da başka biri tarafından). Bir dakika sonra durumu yenileyin.",
                    "This report is being sent right now (in another tab or by someone else). Refresh its status in a minute."));
        }
        String subject = null;
        String finalState = "FAILED";
        String finalStatus = "FAILED: gönderim tamamlanamadı";
        boolean released = false;
        int pdfBytes = 0;
        Long sentRev = null;
        List<String> active = to;
        try {
            // Talepten SONRA taze oku (inceleme 2026-10-13): çağıranın elindeki satır talepten önce okunmuştu; arada
            // kaydedilen değerler PDF'e girmez ama "gönderildi" görünürdü.
            TechErrorReport fresh = service.report(report.getId());
            // PDF bu okumanın içeriği: "gönderimden sonra değişti" bu sayaçla karşılaştırılır
            sentRev = fresh.getContentRev() == null ? 0L : fresh.getContentRev();
            TechErrorReportService.Rendered rendered;
            try {
                rendered = service.renderPdf(() -> service.pdfInput(fresh, channel, team.getName()));
            } catch (TechErrorReportService.PdfBusyException busy) {
                // Hiçbir şey denenmedi: talep bırakılır, FAILED yazılmaz; iz "atlandı" olarak kalır
                finalState = "SKIPPED";
                finalStatus = "SKIPPED: PDF sırası dolu, gönderilmedi";
                released = true;
                throw busy;
            }
            TechErrorPdfInput in = rendered.meta();
            byte[] pdf = rendered.pdf();
            subject = TechErrorReportMail.subject(in);
            pdfBytes = pdf.length;
            long max = Math.max(1, maxMailMb) * 1024L * 1024L;
            if (pdf.length > max) {
                finalStatus = "FAILED: PDF e-posta sınırını aşıyor (" + mb(pdf.length) + " MB > " + maxMailMb + " MB)";
                throw new CodedConflictException("PDF_TOO_LARGE", Msg.t(
                        "PDF " + mb(pdf.length) + " MB; e-posta sınırı " + maxMailMb + " MB. Büyük grafikleri küçültüp (JPEG) yeniden "
                                + "yükleyin ya da PDF'i indirip elle gönderin.",
                        "The PDF is " + mb(pdf.length) + " MB; the e-mail limit is " + maxMailMb + " MB. Make the large charts smaller "
                                + "(JPEG) and upload them again, or download the PDF and send it yourself."),
                        java.util.Map.of("max_mb", maxMailMb));
            }
            String fileName = TechErrorReportPdfWriter.fileName(channel.getName(), fresh.getReportYear(), fresh.getReportMonth(),
                    in.template().lang());
            MailDoc.Mail mail = TechErrorReportMail.build(in, baseUrl(), fileName);
            String status = email.sendHtml(to.toArray(new String[0]), null, subject, mail.html(), mail.text(), null, false,
                    List.of(new EmailNotificationService.MailAttachment(fileName, pdf, "application/pdf")));
            finalStatus = status == null ? "FAILED: sonuç yok" : status;
            finalState = classify(finalStatus);
            // Posta servisi yalnız pasif kullanıcılara ait adresleri düşürür — "kime gitti" kaydı da onları saymaz
            active = activeRecipients(to);
            return new Result(finalState, finalStatus, active, pdfBytes, subject);
        } catch (RuntimeException e) {
            if (!released && !(e instanceof CodedConflictException)) {
                log.warn("Teknik hata raporu gönderilemedi (rapor {}): {}", report.getId(), e.toString());
                if (finalStatus.startsWith("FAILED: gönderim tamamlanamadı")) {
                    finalStatus = "FAILED: " + clip(e.getClass().getSimpleName(), 120);
                }
            }
            if (!released) finalState = "FAILED";
            throw e;
        } finally {
            Instant at = service.now();
            // Günlük satırı ÖNCE: kuyruktaki gönderim bu satırla sonuçlandırılır (posta servisi SENT / FAILED yazar)
            Long logId = saveLog(team, active.isEmpty() ? to : active, subject != null ? subject : channel.getName(), finalStatus);
            try {
                int n;
                if (released) {
                    n = reports.releaseClaim(report.getId(), claimedAt);
                } else if ("SENT".equals(finalState)) {
                    n = reports.finishSent(report.getId(), claimedAt, clip(finalStatus, 255), at, by,
                            clip(String.join(", ", active), 500), sentRev);
                } else if ("QUEUED".equals(finalState)) {
                    // Posta servisi yeniden deniyor, teslim BİLİNMİYOR — "gönderildi" yazılmaz; okuma uçları günlük
                    // satırının sonucuyla sonuçlandırır (reconcileQueued)
                    n = reports.finishQueued(report.getId(), claimedAt, clip(finalStatus, 255), at, logId, by,
                            clip(String.join(", ", active), 500), sentRev);
                } else {
                    // SKIPPED / FAILED: gönderilmedi
                    n = reports.finishNotSent(report.getId(), claimedAt, finalState, clip(finalStatus, 255), at);
                }
                if (n == 0) log.warn("Teknik hata raporu gönderim sonucu yazılamadı (rapor {}): talep başka bir gönderime geçmiş", report.getId());
            } catch (RuntimeException ex) {
                log.warn("Teknik hata raporu gönderim sonucu yazılamadı (rapor {}): {}", report.getId(), ex.toString());
            }
        }
    }

    /** Yalnız pasif kullanıcılara ait adresler çıkarılmış alıcılar (koruma yoksa liste aynen). */
    List<String> activeRecipients(List<String> to) {
        if (inactiveGuard == null) return to;
        List<String> out = new ArrayList<>();
        for (String e : to) {
            try {
                if (!inactiveGuard.isInactiveOnlyEmail(e)) out.add(e);
            } catch (RuntimeException ex) {
                out.add(e);   // okunamazsa adres kalır (yalnız kayıt amaçlı)
            }
        }
        return out;
    }

    static String classify(String status) {
        if ("SENT".equals(status)) return "SENT";
        if (status.startsWith("QUEUED_RETRY")) return "QUEUED";
        if (status.startsWith("SKIPPED")) return "SKIPPED";
        return "FAILED";
    }

    /** Gönderim kararının günlük satırı; kimliği döner (yazılamazsa null — karar yine de sonlanır). */
    private Long saveLog(Team team, List<String> to, String subject, String status) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);   // alarm kaynaklı değil (kolon NOT NULL)
            n.setSentAt(ISO.format(Instant.now()));
            n.setRecipientName(clip(team.getName() == null ? "" : team.getName(), 255));
            n.setRecipientEmail(String.join(", ", to));     // varlık kolona sığdırır (NotificationLog.fitColumns)
            n.setRecipientRole("TEAM");
            n.setSubject(clip(subject, 500));
            n.setEmailStatus(clip(status, 250));
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(TRIGGER);
            n.setEmailFrom(email.fromAddress());
            NotificationLog saved = logs.save(n);
            return saved == null ? null : saved.getId();
        } catch (Exception e) {
            log.warn("Teknik hata raporu gönderim günlüğü yazılamadı: {}", e.getMessage());
            return null;
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

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
