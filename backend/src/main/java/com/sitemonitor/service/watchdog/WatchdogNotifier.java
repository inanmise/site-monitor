package com.sitemonitor.service.watchdog;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.WebhookService;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.WatchdogMail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Kendi kendini izleme bildirim kanalları (2026-10-11). Bir turun tüm geçişleri TEK bildirimde:
 * <ul>
 *   <li><b>E-posta:</b> {@code watchdog.recipients}, boşsa sistem yöneticisi adresi.</li>
 *   <li><b>Webhook:</b> {@code watchdog.webhook-url} (Teams / Slack).</li>
 *   <li><b>Push:</b> {@code watchdog.push-users} — DOĞRUDAN ağ geçidine ({@code sendDirect}); outbox'a girmez, çünkü
 *       izlenen sorunlardan biri tam da outbox'ın tıkanmasıdır.</li>
 * </ul>
 * Sistem bakımı "bildirimler sussun" açıkken hiçbir kanal denenmez. Her bildirim (gönderilen, gönderilemeyen, atlanan)
 * {@code notification_logs}'a tetik {@value #TRIGGER} ile yazılır — sessiz yol yok.
 */
@Slf4j
@Component
public class WatchdogNotifier {

    public static final String TRIGGER = "WATCHDOG";
    public static final String TRIGGER_TEST = "WATCHDOG_TEST";

    /** Teslim sonucu: en az bir kanal ulaştı / denenen kanalların hiçbiri ulaşamadı / denenecek kanal yok ya da susturuldu. */
    public enum Outcome { DELIVERED, FAILED, NO_CHANNEL }

    /** Kanal ayrıntısı (yönetici ekranı ve test sonucu). */
    public record Report(Outcome outcome, String email, String webhook, String push, int recipients) { }

    private final AppSettingsService settings;
    private final EmailNotificationService email;
    private final NotificationLogRepository logs;
    private WebhookService webhooks;
    private UserPushService push;
    private SystemMaintenanceService maintenance;

    @Value("${site.monitor.system-admin.email:}")
    private String systemAdminEmail = "";

    public WatchdogNotifier(AppSettingsService settings, EmailNotificationService email, NotificationLogRepository logs) {
        this.settings = settings;
        this.email = email;
        this.logs = logs;
    }

    @Autowired(required = false) @Lazy public void setWebhooks(WebhookService v) { this.webhooks = v; }
    @Autowired(required = false) @Lazy public void setPush(UserPushService v) { this.push = v; }
    @Autowired(required = false) @Lazy public void setMaintenance(SystemMaintenanceService v) { this.maintenance = v; }

    public Report send(List<WatchdogMail.Item> items, boolean test) {
        String subject = WatchdogMail.subject(items, test);
        String trigger = test ? TRIGGER_TEST : TRIGGER;
        List<String> to = WatchdogSettings.recipients(settings, systemAdminEmail);
        if (!test && muted()) {
            Report r = new Report(Outcome.NO_CHANNEL, "SKIPPED: sistem bakımı", "SKIPPED: sistem bakımı", "SKIPPED: sistem bakımı", to.size());
            saveLog(subject, to, r, trigger);
            return r;
        }
        boolean anyAttempt = false, anyOk = false;

        String emailStatus;
        if (to.isEmpty()) {
            emailStatus = "SKIPPED: alıcı yok";
        } else {
            anyAttempt = true;
            try {
                MailDoc.Mail mail = WatchdogMail.build(items, test, settings.getString("site.monitor.app.base-url", ""));
                String st = email.sendHtml(to.toArray(String[]::new), null, subject, mail.html(), null);
                emailStatus = st == null ? "FAILED: yanıt yok" : st;
                if (st != null && (st.startsWith("SENT") || st.startsWith("QUEUED"))) anyOk = true;
            } catch (Exception e) {
                emailStatus = "FAILED: " + clip(e.getMessage(), 180);
                log.warn("Kendi kendini izleme e-postası gönderilemedi: {}", e.toString());
            }
        }

        String webhookStatus;
        String url = WatchdogSettings.webhookUrl(settings);
        if (url.isBlank() || webhooks == null) {
            webhookStatus = "SKIPPED";
        } else {
            anyAttempt = true;
            try {
                webhooks.send(WatchdogSettings.webhookType(settings), url, subject, plain(items), level(items, test));
                webhookStatus = "SENT";
                anyOk = true;
            } catch (Exception e) {
                webhookStatus = "FAILED: " + clip(e.getMessage(), 180);
                log.warn("Kendi kendini izleme webhook'u gönderilemedi ({}): {}", WebhookService.maskUrl(url), e.toString());
            }
        }

        String pushStatus;
        List<String> users = WatchdogSettings.pushUsers(settings);
        if (users.isEmpty() || push == null) {
            pushStatus = "SKIPPED";
        } else if (!push.gatewayConfigured()) {
            pushStatus = "SKIPPED: push ağ geçidi yapılandırılmamış";
        } else {
            anyAttempt = true;
            int ok = 0;
            for (String u : users) {
                try {
                    if (push.sendDirect(u, subject, plain(items)).ok()) ok++;
                } catch (Exception e) {
                    log.warn("Kendi kendini izleme push'u gönderilemedi ({}): {}", u, e.toString());
                }
            }
            pushStatus = ok == users.size() ? "SENT" : ok > 0 ? "PARTIAL " + ok + "/" + users.size() : "FAILED";
            if (ok > 0) anyOk = true;
        }

        Outcome outcome = anyOk ? Outcome.DELIVERED : anyAttempt ? Outcome.FAILED : Outcome.NO_CHANNEL;
        Report r = new Report(outcome, emailStatus, webhookStatus, pushStatus, to.size());
        saveLog(subject, to, r, trigger);
        return r;
    }

    private boolean muted() {
        try {
            return maintenance != null && maintenance.notificationsMuted();
        } catch (Exception e) {
            return false;
        }
    }

    /** Webhook / push gövdesi — satır başına "Kontrol: durum — ayrıntı". */
    static String plain(List<WatchdogMail.Item> items) {
        List<String> lines = new ArrayList<>();
        for (WatchdogMail.Item i : items) {
            lines.add(i.label() + ": " + kindText(i.kind()) + (i.detail() == null || i.detail().isBlank() ? "" : " — " + i.detail()));
        }
        return String.join("\n", lines);
    }

    private static String kindText(String kind) {
        return switch (kind == null ? "" : kind) {
            case "ALERT" -> "sorun";
            case "REMIND" -> "sürüyor";
            case "RECOVER" -> "düzeldi";
            default -> "test";
        };
    }

    private static String level(List<WatchdogMail.Item> items, boolean test) {
        if (test) return "INFO";
        boolean open = items.stream().anyMatch(i -> !"RECOVER".equals(i.kind()));
        if (!open) return "OK";
        return items.stream().anyMatch(i -> "CRITICAL".equals(i.severity()) && !"RECOVER".equals(i.kind())) ? "CRITICAL" : "HIGH";
    }

    private void saveLog(String subject, List<String> to, Report r, String trigger) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);   // alarm kaynaklı değil (kolon NOT NULL)
            n.setSentAt(WatchdogTransition.ISO.format(Instant.now()));
            n.setRecipientName("Kendi kendini izleme");
            n.setRecipientEmail(clip(String.join(", ", to), 1000));
            n.setRecipientRole("SYSTEM_ADMIN");
            n.setSubject(clip(subject, 500));
            n.setEmailStatus(clip(r.email() + ("SKIPPED".equals(r.push()) ? "" : " · push " + r.push()), 250));
            n.setWebhookStatus(clip(r.webhook(), 250));
            n.setTrigger(trigger);
            n.setEmailFrom(email.getEmailFrom());
            logs.save(n);
        } catch (Exception e) {
            log.warn("Kendi kendini izleme bildirim günlüğü yazılamadı: {}", e.getMessage());
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
