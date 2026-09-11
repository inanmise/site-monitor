package com.sitemonitor.service;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.DeploymentHistoryService.Kind;
import com.sitemonitor.service.DeploymentHistoryService.TransitionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Sürüm geçişi bildirimi (E3, opt-in) — {@link DeploymentHistoryService#recordStartup} açılışta
 * UPGRADE/ROLLBACK/CHANGED türetince {@link TransitionEvent} yayımlar; bu dinleyici yalnız
 * <b>UPGRADE ve ROLLBACK</b> için ve yalnız {@code site.monitor.deploy.notify.enabled=true} iken
 * sistem yöneticisine ({@code site.monitor.system-admin.email}, CANLI okunur) e-posta gönderir.
 *
 * <p>Best-effort: dinleyiciden HİÇBİR istisna çıkmaz (WARN) — açılış yolu bir mail yüzünden düşmez.
 * Alıcı yoksa sessizce atlanır (log). Her gönderim {@code notification_logs}'a yazılır
 * (trigger {@code DEPLOYMENT_UPGRADE}/{@code DEPLOYMENT_ROLLBACK}) — Bildirim Geçmişi ekranında görünsün.
 *
 * <p><b>Push paritesi — bilinçli olarak YOK.</b> {@link UserPushService} kişi-bazlı ALARM kanalıdır:
 * alıcılar {@code UserPushRecipientResolver.resolve(teamId, alertLevel)} ile takım + unvan-grubu +
 * seviye kapılarından çözülür ve her teslimat bir {@code alert_event} satırına bağlanır. Bir sürüm
 * geçişinin takımı, seviyesi ya da alarm olayı yoktur; "sistem yöneticisi" e-posta adresi de bir
 * push kullanıcı adına eşlenmez. Kanalın sözleşmesini (PushMessageContractTest: her şablon alarm
 * bağlamı yer tutucularıyla dolar) eğip bükmeden sistem olayı taşınamıyor; taşınırsa ayrı bir
 * "sistem olayları" alıcı kapsamı tasarlanmalı. O yüzden burada yalnız e-posta var.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentNotifyService {

    static final String ENABLED_KEY = "site.monitor.deploy.notify.enabled";
    static final String RECIPIENT_KEY = "site.monitor.system-admin.email";
    static final int MAX_HIGHLIGHTS = 5;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final AppSettingsService appSettings;
    private final EmailNotificationService emailService;
    private final ReleaseIndexService releaseIndex;
    private final NotificationLogRepository notificationLogRepo;

    @EventListener(TransitionEvent.class)
    public void onTransition(TransitionEvent ev) {
        try {
            String status = handle(ev);
            if (status != null) log.info("Deployment notice {} {}→{}: {}", ev.environment(), ev.fromVersion(), ev.toVersion(), status);
        } catch (Exception e) {
            log.warn("Deployment notice failed (ignored): {}", e.toString());
        }
    }

    /** Gönderim durumu ({@code SENT}/{@code SKIPPED_*}/{@code FAILED: …}) ya da hiç ele alınmadıysa null. */
    String handle(TransitionEvent ev) {
        if (ev == null || ev.kind() == null) return null;
        if (ev.kind() != Kind.UPGRADE && ev.kind() != Kind.ROLLBACK) return null;   // CHANGED/RESTART bildirilmez
        if (!appSettings.getBoolean(ENABLED_KEY, false)) return null;
        String[] to = recipients(appSettings.getString(RECIPIENT_KEY, ""));
        if (to.length == 0) {
            log.info("Deployment notice skipped — {} boş", RECIPIENT_KEY);
            return "SKIPPED_NO_RECIPIENT";
        }
        EmailNotificationService.DeploymentNotice notice = build(ev);
        String status = emailService.sendDeploymentNotice(to, notice);
        writeNotificationLog(to, ev, status);
        return status;
    }

    EmailNotificationService.DeploymentNotice build(TransitionEvent ev) {
        String commit = ev.row() == null ? null : ev.row().getGitCommit();
        String commitShort = commit == null ? null : commit.substring(0, Math.min(8, commit.length()));
        String startedAt = ev.row() == null ? null : ev.row().getStartedAt();
        List<String> highlights = new ArrayList<>();
        boolean breaking = false;
        Optional<ReleaseIndexService.Release> rel = releaseIndex.find(ev.toVersion());
        if (rel.isPresent()) {
            breaking = rel.get().breaking();
            rel.get().changes().stream()
                    .sorted(Comparator.comparingInt(c -> "feat".equals(c.type()) ? 0 : "fix".equals(c.type()) ? 1 : 2))
                    .limit(MAX_HIGHLIGHTS)
                    .forEach(c -> highlights.add(c.type() + (c.scope() != null && !c.scope().isBlank() ? "(" + c.scope() + ")" : "")
                            + ": " + c.subject()));
        }
        return new EmailNotificationService.DeploymentNotice(ev.kind().name(), ev.environment(),
                blankToUnknown(ev.fromVersion()), blankToUnknown(ev.toVersion()), commitShort, startedAt, highlights, breaking);
    }

    /** Virgül/noktalı virgül ayrılmış liste → kırpılmış, boşları atılmış dizi. */
    static String[] recipients(String raw) {
        if (raw == null || raw.isBlank()) return new String[0];
        List<String> out = new ArrayList<>();
        for (String s : raw.split("[,;]")) if (!s.isBlank()) out.add(s.trim());
        return out.toArray(new String[0]);
    }

    private static String blankToUnknown(String v) { return v == null || v.isBlank() ? "?" : v; }

    private void writeNotificationLog(String[] to, TransitionEvent ev, String status) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(ev.row() == null || ev.row().getId() == null ? 0L : ev.row().getId());   // deployment_history id
            n.setSentAt(ISO.format(Instant.now()));
            n.setRecipientEmail(String.join(",", to));
            n.setRecipientRole("SYSTEM_ADMIN");
            n.setSubject("[Site Monitor] " + ev.kind() + ": " + ev.environment() + " " + ev.fromVersion() + " → " + ev.toVersion());
            n.setEmailStatus(status);
            n.setTrigger("DEPLOYMENT_" + ev.kind().name());
            n.setEmailFrom(emailService.senderAddress());
            notificationLogRepo.save(n);
        } catch (Exception e) {
            log.warn("Deployment notice notification_log yazılamadı: {}", e.getMessage());
        }
    }
}
