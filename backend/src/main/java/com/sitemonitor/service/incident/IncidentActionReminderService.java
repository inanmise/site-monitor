package com.sitemonitor.service.incident;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.IncidentActionItem;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.IncidentActionItemRepository;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.mail.IncidentActionReminderMail;
import com.sitemonitor.service.mail.MailDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Olay aksiyonu hatırlatması (2026-10-11) — her gün 09:00 (İstanbul), son tarihi YARIN, BUGÜN ya da GEÇMİŞ olan açık
 * aksiyonların sahiplerine sahip başına TEK e-posta. Takvim: son tarihten bir gün önce, son tarih günü, gecikmenin ilk
 * günü, sonra haftada bir. Her aksiyon gönderimden ÖNCE koşullu UPDATE ile sahiplenilir ({@code reminded_on}): iki pod
 * aynı aksiyonu iki kez hatırlatmaz (en çok bir kez). Kararlar {@code notification_logs}'a yazılır (tetik
 * {@value #TRIGGER}): gönderildi / başarısız / e-posta yok / pasif kullanıcı / sistem bakımı (bakımda sahiplenme
 * yapılmaz → ertesi gün yeniden denenir).
 */
@Slf4j
@Service
public class IncidentActionReminderService {

    public static final String TRIGGER = "INCIDENT_ACTION";
    static final String LOCK = "incident-action-reminder";
    static final int REPEAT_DAYS = 7;

    private final IncidentActionItemRepository actions;
    private final IncidentRecordRepository incidents;
    private final AppUserRepository users;
    private final EmailNotificationService email;
    private final NotificationLogRepository logs;
    private final AppSettingsService settings;

    @Autowired(required = false) @Lazy private SchedulerService scheduler;
    @Autowired(required = false) @Lazy private SystemMaintenanceService maintenance;

    @Value("${site.monitor.incident.action-reminder-enabled:true}")
    private boolean enabled = true;

    public IncidentActionReminderService(IncidentActionItemRepository actions, IncidentRecordRepository incidents,
                                         AppUserRepository users, EmailNotificationService email,
                                         NotificationLogRepository logs, AppSettingsService settings) {
        this.actions = actions;
        this.incidents = incidents;
        this.users = users;
        this.email = email;
        this.logs = logs;
        this.settings = settings;
    }

    void setEnabled(boolean v) { this.enabled = v; }
    void setMaintenance(SystemMaintenanceService m) { this.maintenance = m; }

    public record Result(int owners, int sent, int skipped, int items) { }

    @Scheduled(cron = "${site.monitor.incident.action-reminder-cron:0 0 9 * * *}", zone = "Europe/Istanbul")
    public void scheduled() {
        if (!enabled) return;
        try {
            if (scheduler == null) run(LocalDate.now(IncidentPostmortemService.IST));
            else scheduler.callWithSchedulerLock(LOCK, () -> run(LocalDate.now(IncidentPostmortemService.IST)));
        } catch (Exception e) {
            log.error("Olay aksiyonu hatırlatması başarısız: {}", e.getMessage(), e);
        }
    }

    /**
     * Hangi hatırlatma? SOON (yarın), TODAY, OVERDUE — null = bugün hatırlatılmaz. {@code remindedOn}: son hatırlatma günü.
     * Gecikmede: son hatırlatma son tarihte ya da önceyse (henüz gecikme hatırlatması gitmedi) ya da en az
     * {@value #REPEAT_DAYS} gün önceyse.
     */
    static String whenToRemind(String due, String remindedOn, LocalDate today) {
        if (due == null) return null;
        LocalDate d;
        try { d = LocalDate.parse(due); } catch (Exception e) { return null; }
        String t = today.toString();
        if (d.equals(today.plusDays(1))) return remindedOn == null || remindedOn.compareTo(t) < 0 ? "SOON" : null;
        if (d.equals(today)) return remindedOn == null || remindedOn.compareTo(t) < 0 ? "TODAY" : null;
        if (d.isBefore(today)) {
            if (remindedOn == null || remindedOn.compareTo(due) <= 0) return "OVERDUE";
            try {
                return ChronoUnit.DAYS.between(LocalDate.parse(remindedOn), today) >= REPEAT_DAYS ? "OVERDUE" : null;
            } catch (Exception e) {
                return "OVERDUE";
            }
        }
        return null;
    }

    public Result run(LocalDate today) {
        String t = today.toString();
        List<IncidentActionItem> candidates = actions.reminderCandidates(today.plusDays(1).toString());
        Map<Long, List<IncidentActionItem>> byOwner = new LinkedHashMap<>();
        Map<Long, String> whenOf = new HashMap<>();
        for (IncidentActionItem a : candidates) {
            String w = whenToRemind(a.getDueDate(), a.getRemindedOn(), today);
            if (w == null) continue;
            whenOf.put(a.getId(), w);
            byOwner.computeIfAbsent(a.getOwnerUserId(), k -> new ArrayList<>()).add(a);
        }
        if (byOwner.isEmpty()) return new Result(0, 0, 0, 0);

        boolean muted = muted();
        Map<Long, String> titles = new HashMap<>();
        Set<Long> incidentIds = new HashSet<>();
        byOwner.values().forEach(l -> l.forEach(a -> incidentIds.add(a.getIncidentId())));
        for (IncidentRecord r : incidents.findAllById(incidentIds)) titles.put(r.getId(), r.getTitle());
        Map<Long, AppUser> owners = new HashMap<>();
        for (AppUser u : users.findAllById(byOwner.keySet())) owners.put(u.getId(), u);

        int sent = 0, skipped = 0, items = 0;
        String base = settings.getString("site.monitor.app.base-url", "");
        for (Map.Entry<Long, List<IncidentActionItem>> en : byOwner.entrySet()) {
            AppUser owner = owners.get(en.getKey());
            String name = owner == null ? en.getValue().get(0).getOwnerName() : IncidentPostmortemService.displayName(owner);
            String to = owner == null || owner.getEmail() == null ? "" : owner.getEmail().trim();
            if (muted) {
                saveLog(name, to, "[Site Monitor] Olay aksiyonları", "SKIPPED: sistem bakımı");
                skipped++;
                continue;
            }
            // Sahiplen (bu pod) — yalnız sahiplenilen aksiyonlar e-postaya girer
            List<IncidentActionReminderMail.Item> mine = new ArrayList<>();
            for (IncidentActionItem a : en.getValue()) {
                int claimed = a.getRemindedOn() == null ? actions.claimFirstReminder(a.getId(), t)
                        : actions.claimNextReminder(a.getId(), a.getRemindedOn(), t);
                if (claimed != 1) continue;
                String w = whenOf.get(a.getId());
                long late = "OVERDUE".equals(w) ? ChronoUnit.DAYS.between(LocalDate.parse(a.getDueDate()), today) : 0;
                mine.add(new IncidentActionReminderMail.Item(a.getTitle(), titles.getOrDefault(a.getIncidentId(), "#" + a.getIncidentId()),
                        a.getDueDate(), w, late, a.getPriority()));
            }
            if (mine.isEmpty()) continue;
            items += mine.size();
            String subject = IncidentActionReminderMail.subject(mine);
            if (owner == null || !Boolean.TRUE.equals(owner.getActive())) {
                saveLog(name, to, subject, owner == null ? "SKIPPED: kullanıcı yok" : "SKIPPED: pasif kullanıcı");
                skipped++;
                continue;
            }
            if (to.isBlank()) {
                saveLog(name, to, subject, "SKIPPED: e-posta adresi yok");
                skipped++;
                continue;
            }
            String status;
            try {
                MailDoc.Mail mail = IncidentActionReminderMail.build(name, mine, base);
                String st = email.sendHtml(new String[]{to}, null, subject, mail.html(), mail.text(), null, false, null);
                status = st == null ? "FAILED: yanıt yok" : st;
                if (st != null && (st.startsWith("SENT") || st.startsWith("QUEUED"))) sent++;
            } catch (Exception e) {
                status = "FAILED: " + clip(e.getMessage(), 180);
                log.warn("Olay aksiyonu hatırlatması gönderilemedi (sahip {}): {}", en.getKey(), e.toString());
            }
            saveLog(name, to, subject, status);
        }
        log.info("Olay aksiyonu hatırlatması: {} sahip, {} aksiyon, {} gönderildi, {} atlandı", byOwner.size(), items, sent, skipped);
        return new Result(byOwner.size(), sent, skipped, items);
    }

    private boolean muted() {
        try {
            return maintenance != null && maintenance.notificationsMuted();
        } catch (Exception e) {
            return false;
        }
    }

    private void saveLog(String name, String to, String subject, String status) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);   // alarm kaynaklı değil (kolon NOT NULL)
            n.setSentAt(IncidentPostmortemService.ISO.format(Instant.now()));
            n.setRecipientName(clip(name == null ? "" : name, 255));
            n.setRecipientEmail(to);                        // varlık kolona sığdırır (2026-10-12; 1000 kolondan uzundu)
            n.setRecipientRole("ACTION_OWNER");
            n.setSubject(clip(subject, 500));
            n.setEmailStatus(clip(status, 250));
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(TRIGGER);
            n.setEmailFrom(email.getEmailFrom());
            logs.save(n);
        } catch (Exception e) {
            log.warn("Olay aksiyonu hatırlatma günlüğü yazılamadı: {}", e.getMessage());
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
