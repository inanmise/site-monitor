package com.sitemonitor.service;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.LoginAnomalyIncidentRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Güvenlik uyarı e-postalarının ASENKRON gönderim aracı — ayrı bean olması ZORUNLU (Spring @Async
 * proxy'si yalnız farklı bean'den çağrılınca devreye girer). Scheduler thread'i mail sunucusuna
 * bloke olmaz. Her gönderim notification_logs'a + audit'e yazılır; hata → sonraki taramada tekrar
 * (lastAlertAt yalnız BAŞARIDA damgalanır) + ardışık hatalarda belirgin log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityMailDispatcher {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final EmailNotificationService emailService;
    private final LoginAnomalyIncidentRepository incidentRepo;
    private final NotificationLogRepository notificationLogRepo;
    private final AuditService auditService;

    /** Ardışık gönderim hatası sayacı (in-memory; restart'ta sıfırlanır — kabul edilebilir). */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    int consecutiveFailures() { return consecutiveFailures.get(); }

    @Async("loginIssueMailExecutor")
    public void dispatchAlert(String[] recipients, FailedLoginAnomalyService.AnomalyReport report,
                              String trigger, Long incidentId) {
        String subject = "[Site Monitör] Anomali: " + report.total() + " başarısız login / "
                + report.windowMinutes() + "dk — " + (report.hits() == null ? 0 : report.hits().size()) + " kural";
        String status;
        try {
            status = emailService.sendSystemAdminLoginAnomalyAlert(recipients, report, trigger);
        } catch (Exception e) {
            status = "FAILED: " + e.getMessage();
        }
        writeNotificationLog(recipients, subject, "LOGIN_ANOMALY_" + trigger, status, incidentId);
        safeAudit("LOGIN_ANOMALY_ALERT", incidentId,
                "{\"trigger\":\"" + trigger + "\",\"total\":" + report.total()
                        + ",\"rules\":\"" + report.rulesSignature() + "\",\"status\":\"" + status + "\"}");

        if (isDelivered(status)) {
            consecutiveFailures.set(0);
            if (incidentId != null) {
                incidentRepo.findById(incidentId).ifPresent(inc -> {
                    inc.setLastAlertAt(ISO.format(Instant.now()));   // cooldown yalnız BAŞARIDA başlar
                    incidentRepo.save(inc);
                });
            }
        } else {
            int c = consecutiveFailures.incrementAndGet();
            if (c >= 3)
                log.error("‼ Login anomaly uyarı maili {} kez ÜST ÜSTE gönderilemedi (son durum={}) — SMTP kontrol edin", c, status);
            else
                log.warn("Login anomaly uyarı maili gönderilemedi (durum={}) — sonraki taramada tekrar denenecek", status);
        }
    }

    @Async("loginIssueMailExecutor")
    public void dispatchResolved(String[] recipients, String openedAt, String resolvedAt,
                                 long peakTotal, Long incidentId) {
        String status;
        try {
            status = emailService.sendSystemAdminLoginAnomalyResolved(recipients, openedAt, resolvedAt, peakTotal);
        } catch (Exception e) {
            status = "FAILED: " + e.getMessage();
        }
        writeNotificationLog(recipients, "[Site Monitör] Login anomalisi normale döndü",
                "LOGIN_ANOMALY_RESOLUTION", status, incidentId);
        safeAudit("LOGIN_ANOMALY_RESOLVED", incidentId,
                "{\"peakTotal\":" + peakTotal + ",\"status\":\"" + status + "\"}");
    }

    private static boolean isDelivered(String status) {
        return status != null && (status.equals("SENT") || status.startsWith("QUEUED")
                || status.equals("SKIPPED_DISABLED") || status.equals("SKIPPED_NO_RECIPIENT"));
    }

    private void writeNotificationLog(String[] recipients, String subject, String trigger, String status, Long incidentId) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(incidentId == null ? 0L : incidentId);   // login_anomaly_incident id (trigger ile ayrışır)
            n.setSentAt(ISO.format(Instant.now()));
            n.setRecipientEmail(recipients == null ? "" : String.join(",", recipients));
            n.setRecipientRole("SYSTEM_ADMIN");
            n.setSubject(subject);
            n.setEmailStatus(status);
            n.setTrigger(trigger);
            n.setEmailFrom(emailService.senderAddress());
            notificationLogRepo.save(n);
        } catch (Exception e) {
            log.warn("Login anomaly notification_log yazılamadı: {}", e.getMessage());
        }
    }

    private void safeAudit(String eventType, Long incidentId, String detail) {
        try {
            auditService.recordSystemEvent(eventType, "LOGIN_ANOMALY",
                    incidentId == null ? null : String.valueOf(incidentId), detail);
        } catch (Exception e) {
            log.warn("Login anomaly audit yazılamadı: {}", e.getMessage());
        }
    }
}
