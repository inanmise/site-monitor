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

    /**
     * Teslim sonucu: en az bir kanal ulaştı / denenen kanalların hiçbiri ulaşamadı / denenecek kanal yok / sistem bakımı
     * susturdu ({@code MUTED}, 2026-10-12: susturma bitince süren sorun bir kez bildirilir — {@link WatchdogTransition}).
     */
    public enum Outcome { DELIVERED, FAILED, NO_CHANNEL, MUTED }

    /** Kanal ayrıntısı (yönetici ekranı ve test sonucu). */
    public record Report(Outcome outcome, String email, String webhook, String push, int recipients) { }

    private final AppSettingsService settings;
    private final EmailNotificationService email;
    private final NotificationLogRepository logs;
    private WebhookService webhooks;
    private UserPushService push;
    private com.sitemonitor.repository.AppUserRepository users;
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
    @Autowired(required = false) public void setUsers(com.sitemonitor.repository.AppUserRepository v) { this.users = v; }
    @Autowired(required = false) @Lazy public void setMaintenance(SystemMaintenanceService v) { this.maintenance = v; }

    public Report send(List<WatchdogMail.Item> items, boolean test) {
        String subject = WatchdogMail.subject(items, test);
        String trigger = test ? TRIGGER_TEST : TRIGGER;
        List<String> to = WatchdogSettings.recipients(settings, systemAdminEmail);
        if (!test && muted()) {
            Report r = new Report(Outcome.MUTED, "SKIPPED: sistem bakımı", "SKIPPED: sistem bakımı", "SKIPPED: sistem bakımı", to.size());
            saveLog(subject, to, r, trigger);
            return r;
        }
        boolean anyAttempt = false, anyOk = false;

        String emailStatus;
        if (to.isEmpty()) {
            emailStatus = "SKIPPED: alıcı yok";
        } else {
            try {
                MailDoc.Mail mail = WatchdogMail.build(items, test, settings.getString("site.monitor.app.base-url", ""),
                        WatchdogSettings.confirm(settings));
                String st = email.sendHtml(to.toArray(String[]::new), null, subject, mail.html(), null);
                emailStatus = st == null ? "FAILED: yanıt yok" : st;
                // E-posta kanalı kapalı / alıcıların hepsi pasif (SKIPPED…) bir teslim DENEMESİ değildir (2026-10-12): FAILED sayılsa
                // açık sorun 15 dk'da bir sonsuza dek yeniden "gönderilmeye" çalışılırdı.
                if (!emailStatus.startsWith("SKIPPED")) anyAttempt = true;
                if (st != null && (st.startsWith("SENT") || st.startsWith("QUEUED"))) anyOk = true;
            } catch (Exception e) {
                anyAttempt = true;
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
            int ok = 0, passive = 0, tried = 0;
            for (String u : users) {
                // Pasif hesap sistem push'u da almaz (2026-10-12; e-posta yolu pasif adresi zaten düşürüyordu). Hesabı
                // olmayan tanımlayıcıya bugünkü gibi gönderilir. Metin alıcının push diline göre (TR / EN).
                com.sitemonitor.model.AppUser acc = lookup(u);
                if (acc != null && Boolean.FALSE.equals(acc.getActive())) { passive++; continue; }
                boolean en = acc != null && com.sitemonitor.service.PushI18n.isEn(acc.getPushLang());
                tried++;
                try {
                    if (push.sendDirect(u, en ? WatchdogMail.subjectEn(items, test) : subject, plain(items, en)).ok()) ok++;
                } catch (Exception e) {
                    log.warn("Kendi kendini izleme push'u gönderilemedi ({}): {}", u, e.toString());
                }
            }
            if (tried > 0) anyAttempt = true;
            String skipped = passive > 0 ? " · " + passive + " pasif kullanıcı atlandı" : "";
            pushStatus = tried == 0 ? "SKIPPED: pasif kullanıcı"
                    : (ok == tried ? "SENT" : ok > 0 ? "PARTIAL " + ok + "/" + tried : "FAILED") + skipped;
            if (ok > 0) anyOk = true;
        }

        Outcome outcome = anyOk ? Outcome.DELIVERED : anyAttempt ? Outcome.FAILED : Outcome.NO_CHANNEL;
        Report r = new Report(outcome, emailStatus, webhookStatus, pushStatus, to.size());
        saveLog(subject, to, r, trigger);
        return r;
    }

    /** Sistem bakımı bildirimleri susturuyor mu (hata → hayır; turun başında bir kez okunur). */
    boolean muted() {
        try {
            return maintenance != null && maintenance.notificationsMuted();
        } catch (Exception e) {
            return false;
        }
    }

    /** Webhook / push gövdesi — satır başına "Kontrol: durum — ayrıntı". */
    static String plain(List<WatchdogMail.Item> items) {
        return plain(items, false);
    }

    static String plain(List<WatchdogMail.Item> items, boolean en) {
        List<String> lines = new ArrayList<>();
        for (WatchdogMail.Item i : items) {
            String d = i.detail(en);
            lines.add(i.label(en) + ": " + kindText(i.kind(), en) + (d == null || d.isBlank() ? "" : " — " + d));
        }
        return String.join("\n", lines);
    }

    private static String kindText(String kind, boolean en) {
        return switch (kind == null ? "" : kind) {
            case "ALERT" -> en ? "problem" : "sorun";
            case "REMIND" -> en ? "ongoing" : "sürüyor";
            case "RECOVER" -> en ? "recovered" : "düzeldi";
            default -> "test";
        };
    }

    private com.sitemonitor.model.AppUser lookup(String username) {
        if (users == null || username == null) return null;
        try {
            return users.findByUsername(username.trim()).orElse(null);
        } catch (Exception e) {
            return null;
        }
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
            n.setRecipientEmail(String.join(", ", to));     // varlık listeyi kolona sığdırır ("… +N")
            n.setRecipientRole("SYSTEM_ADMIN");
            n.setSubject(subject);
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
