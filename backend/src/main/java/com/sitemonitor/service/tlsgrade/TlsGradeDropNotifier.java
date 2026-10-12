package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TlsGradeChange;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TlsGradeChangeRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.TlsGradeDropMail;
import com.sitemonitor.service.SecretMask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TLS yapılandırma notu DÜŞÜŞÜ → takıma bildirim (2026-10-11, kullanıcı isteği: "TLS notu düştüğünde yalnız uygulama
 * içinde görünüyor").
 *
 * <ul>
 *   <li><b>Kaynak:</b> {@code tls_grade_changes} satırları {@code notify_state=PENDING} doğar (yalnız DROP; bilgi değişimi
 *       ve yükseliş bildirilmez; eski satırlar null — geriye dönük gönderim yok).</li>
 *   <li><b>Bir kez:</b> satırlar koşullu UPDATE ile talep edilir (PENDING → SENDING) — iki pod / iki tur aynı düşüşü
 *       iki kez bildirmez; sonuç SENT ya da "SKIPPED: …" yazılır.</li>
 *   <li><b>Alıcı:</b> kaydın sorumlu (SY) ve UG takımları, her takım YALNIZ kendi satırlarını görür — takım posta kutusu /
 *       varsayılan bildirim grubu ({@link EscalationService#teamEmailsForMonitor}) + takım üyelerine push. Takımsız satır
 *       bildirilmez ("SKIPPED: takım yok").</li>
 *   <li><b>Eşik:</b> {@code site.monitor.tls-grade.drop-notify-min-grade} — yeni not bu nottan YÜKSEKSE bildirilmez
 *       (varsayılan A: A+ dışındaki her düşüş). {@code site.monitor.tls-grade.drop-notify=false} → hepsi "SKIPPED: bildirim
 *       kapalı" (sonradan açılınca eski düşüşler yağmur gibi gelmez).</li>
 *   <li><b>Sistem bakımı</b> susturması açıksa "SKIPPED: sistem bakımı".</li>
 *   <li><b>İz:</b> her takım gönderimi {@code notification_logs} (tetik {@value #TRIGGER}, alarm kimliği 0) — gönderilmeyen
 *       karar da gerekçesiyle yazılır. Hata turu düşürmez.</li>
 * </ul>
 * Zamanlanmış TLS işi ({@link TlsProfileJobService}) her turda çağırır (lider + {@code scheduler_lock}); elle yeniden
 * tarama da düşüş yazabilir, bildirimi yine bu tur gönderir.
 */
@Slf4j
@Service
public class TlsGradeDropNotifier {

    public static final String TRIGGER = "TLS_GRADE_DROP";
    public static final String KEY_ENABLED = "site.monitor.tls-grade.drop-notify";
    public static final String KEY_MIN_GRADE = "site.monitor.tls-grade.drop-notify-min-grade";
    static final int BATCH = 500;
    static final String SKIP_OFF = "SKIPPED: bildirim kapalı";
    static final String SKIP_BELOW = "SKIPPED: eşik üstü not";
    static final String SKIP_MAINT = "SKIPPED: sistem bakımı";
    static final String SKIP_NO_TEAM = "SKIPPED: takım yok";
    static final String SKIP_NO_RECIPIENT = "SKIPPED: alıcı yok";
    /** Alıcı vardı ama hiçbir kanal teslim edemedi (SMTP hatası, push kuyruğa girmedi) — 2026-10-12'ye kadar "alıcı yok" yazılırdı. */
    static final String NOTIFY_FAILED = "FAILED: gönderilemedi";
    /** notification_logs.recipient_email / email_status uzunluğu (VARCHAR(255)). */
    private static final int LOG_FIELD_MAX = 255;

    /** Takıma gönderimin sonucu. */
    enum Delivery { DELIVERED, FAILED, NONE }

    /**
     * Ekran kodu ("Son düşüşler" satırı): sent | pending | off | below | maintenance | no_team | no_recipient; özellikten
     * önceki satır (durum yok) → null. Türkçe durum metni yalnız günlükte kalır, istemci bu kodu çevirir.
     */
    public static String uiCode(String notifyState) {
        if (notifyState == null || notifyState.isBlank()) return null;
        return switch (notifyState) {
            case TlsGradeChange.NOTIFY_SENT -> "sent";
            case TlsGradeChange.NOTIFY_PENDING, TlsGradeChange.NOTIFY_SENDING -> "pending";
            case SKIP_OFF -> "off";
            case SKIP_BELOW -> "below";
            case SKIP_MAINT -> "maintenance";
            case SKIP_NO_TEAM -> "no_team";
            case SKIP_NO_RECIPIENT -> "no_recipient";
            case NOTIFY_FAILED -> "failed";
            default -> null;
        };
    }

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final TlsGradeChangeRepository changeRepo;
    private final AppSettingsService appSettings;
    private final EscalationService escalation;
    private final EmailNotificationService email;
    private final NotificationLogRepository notificationLogRepo;
    private final TeamRepository teamRepo;

    @Autowired(required = false) private UserPushService push;
    @Autowired(required = false) private SystemMaintenanceService systemMaintenance;

    public TlsGradeDropNotifier(TlsGradeChangeRepository changeRepo, AppSettingsService appSettings, EscalationService escalation,
                                EmailNotificationService email, NotificationLogRepository notificationLogRepo, TeamRepository teamRepo) {
        this.changeRepo = changeRepo;
        this.appSettings = appSettings;
        this.escalation = escalation;
        this.email = email;
        this.notificationLogRepo = notificationLogRepo;
        this.teamRepo = teamRepo;
    }

    void setPush(UserPushService p) { this.push = p; }
    void setSystemMaintenance(SystemMaintenanceService s) { this.systemMaintenance = s; }

    /** Tur özeti (log + test). */
    public record Result(int claimed, int sent, int skipped, int teams) { }

    /** Bekleyen düşüşleri bildirir. Asla fırlatmaz. */
    public Result notifyPending() {
        try {
            return run();
        } catch (Exception e) {
            log.warn("TLS notu düşüş bildirimi turu tamamlanamadı (sonraki tur yeniden dener): {}", e.toString());
            return new Result(0, 0, 0, 0);
        }
    }

    private Result run() {
        List<TlsGradeChange> pending = changeRepo.findByNotifyStateOrderByIdAsc(TlsGradeChange.NOTIFY_PENDING, PageRequest.of(0, BATCH));
        if (pending.isEmpty()) return new Result(0, 0, 0, 0);
        List<Long> ids = pending.stream().map(TlsGradeChange::getId).toList();
        int claimed = changeRepo.transitionNotifyState(ids, TlsGradeChange.NOTIFY_PENDING, TlsGradeChange.NOTIFY_SENDING);
        if (claimed <= 0) return new Result(0, 0, 0, 0);
        // Başka tur bir kısmını aldıysa yalnız bizim talep ettiklerimiz: yeniden okumak yerine durum filtresi
        List<TlsGradeChange> mine = new ArrayList<>();
        for (TlsGradeChange c : changeRepo.findAllById(ids)) if (TlsGradeChange.NOTIFY_SENDING.equals(c.getNotifyState())) mine.add(c);
        String now = ISO.format(Instant.now());

        if (!appSettings.getBoolean(KEY_ENABLED, true)) {
            finish(mine, SKIP_OFF, now);
            return new Result(mine.size(), 0, mine.size(), 0);
        }
        if (systemMaintenance != null && safeMuted()) {
            finish(mine, SKIP_MAINT, now);
            log.info("TLS notu düşüş bildirimi: {} düşüş sistem bakımı susturmasıyla gönderilmedi", mine.size());
            return new Result(mine.size(), 0, mine.size(), 0);
        }
        String minGrade = normalizeMinGrade(appSettings.getString(KEY_MIN_GRADE, "A"));
        List<TlsGradeChange> eligible = new ArrayList<>();
        List<TlsGradeChange> below = new ArrayList<>();
        List<TlsGradeChange> teamless = new ArrayList<>();
        for (TlsGradeChange c : mine) {
            if (TlsGradeRules.rank(c.getToGrade()) > TlsGradeRules.rank(minGrade)) below.add(c);
            else if (c.getTeamId() == null && c.getUgTeamId() == null) teamless.add(c);
            else eligible.add(c);
        }
        finish(below, SKIP_BELOW, now);
        finish(teamless, SKIP_NO_TEAM, now);

        // Takım başına satırlar (SY + UG; aynı takım iki kez gelirse tek satır)
        Map<Long, List<TlsGradeChange>> byTeam = new LinkedHashMap<>();
        for (TlsGradeChange c : eligible) {
            Set<Long> owners = new LinkedHashSet<>();
            if (c.getTeamId() != null) owners.add(c.getTeamId());
            if (c.getUgTeamId() != null) owners.add(c.getUgTeamId());
            for (Long t : owners) byTeam.computeIfAbsent(t, k -> new ArrayList<>()).add(c);
        }
        Set<Long> delivered = new LinkedHashSet<>();
        Set<Long> failed = new LinkedHashSet<>();
        String appUrl = appSettings.getString("site.monitor.app.base-url", "");
        for (Map.Entry<Long, List<TlsGradeChange>> e : byTeam.entrySet()) {
            Delivery d = sendToTeam(e.getKey(), e.getValue(), appUrl);
            for (TlsGradeChange c : e.getValue()) {
                if (d == Delivery.DELIVERED) delivered.add(c.getId());
                else if (d == Delivery.FAILED) failed.add(c.getId());
            }
        }
        // En az bir sahip takıma ulaştıysa SENT; hiçbirine ulaşamadı ama denendiyse FAILED; hiç alıcı yoksa "alıcı yok".
        List<TlsGradeChange> sent = eligible.stream().filter(c -> delivered.contains(c.getId())).toList();
        List<TlsGradeChange> notDelivered = eligible.stream()
                .filter(c -> !delivered.contains(c.getId()) && failed.contains(c.getId())).toList();
        List<TlsGradeChange> noRecipient = eligible.stream()
                .filter(c -> !delivered.contains(c.getId()) && !failed.contains(c.getId())).toList();
        finish(sent, TlsGradeChange.NOTIFY_SENT, now);
        finish(notDelivered, NOTIFY_FAILED, now);
        finish(noRecipient, SKIP_NO_RECIPIENT, now);
        log.info("TLS notu düşüş bildirimi: {} düşüş · {} takıma gönderildi · {} gönderilemedi · {} atlandı", mine.size(), byTeam.size(),
                notDelivered.size(), below.size() + teamless.size() + noRecipient.size());
        return new Result(mine.size(), sent.size(), mine.size() - sent.size(), byTeam.size());
    }

    /**
     * Takıma tek özet: e-posta (alıcı varsa) + üyelere push. En az bir kanal ulaştıysa (e-posta SENT / kuyrukta, ya da push
     * kuyruğa girdi) DELIVERED; bir kanal denendi ama hiçbiri ulaşmadıysa FAILED; hiç alıcı yoksa NONE.
     */
    private Delivery sendToTeam(Long teamId, List<TlsGradeChange> rows, String appUrl) {
        String teamName = teamRepo.findById(teamId).map(Team::getName).orElse("Takım #" + teamId);
        List<TlsGradeDropMail.Row> mailRows = rows.stream()
                .map(c -> new TlsGradeDropMail.Row(c.getDomain(), c.getFromGrade(), c.getToGrade(), c.getReasons(), c.getChangedAt()))
                .toList();
        boolean delivered = false;
        boolean attempted = false;
        List<String> recipients;
        try { recipients = escalation.teamEmailsForMonitor(teamId, null); } catch (Exception ex) { recipients = List.of(); }
        String subject = TlsGradeDropMail.subject(teamName, mailRows);
        String status;
        if (recipients.isEmpty()) {
            status = SKIP_NO_RECIPIENT;
        } else {
            MailDoc.Mail mail = TlsGradeDropMail.build(teamName, mailRows, appUrl);
            status = email.sendHtml(recipients.toArray(new String[0]), null, subject, mail.html(), null);
            // QUEUED_RETRY (SMTP 421 vb.) teslim kuyruğunda — gönderilmiş sayılır; SKIPPED… (kanal kapalı / pasif) deneme değildir
            delivered = status != null && (status.startsWith("SENT") || status.startsWith("QUEUED"));
            attempted = status == null || !status.startsWith("SKIPPED");
            log.info("TLS notu düşüş e-postası: takım={} sertifika={} alıcı={} durum={}", teamName, rows.size(),
                    SecretMask.maskEmails(recipients), status);
        }
        int queued = 0;
        if (push != null) {
            try {
                String worst = TlsGradeDropMail.worstGrade(mailRows);
                String level = TlsGradeRules.rank(worst) <= TlsGradeRules.rank("F") ? "CRITICAL"
                        : TlsGradeRules.rank(worst) <= TlsGradeRules.rank("C") ? "HIGH" : "WARNING";
                TlsGradeChange first = rows.get(0);
                String msg = rows.size() == 1
                        ? first.getDomain() + " TLS yapılandırma notu " + first.getFromGrade() + " → " + first.getToGrade() + " düştü"
                        : rows.size() + " sertifikanın TLS yapılandırma notu düştü (en düşük " + worst + ")";
                String msgEn = rows.size() == 1
                        ? "TLS configuration grade of " + first.getDomain() + " dropped " + first.getFromGrade() + " → " + first.getToGrade()
                        : "The TLS configuration grade dropped on " + rows.size() + " certificates (lowest " + worst + ")";
                String dedupe = "tls-drop:" + teamId + ":" + rows.stream().map(c -> String.valueOf(c.getId())).reduce((a, b) -> a + "," + b).orElse("");
                if (dedupe.length() > 180) dedupe = dedupe.substring(0, 180);
                Map<String, Object> pr = push.enqueueTeamNoticeLocalized(teamId, TRIGGER, level, "CERT",
                        rows.size() == 1 ? first.getDomain() : teamName, new UserPushService.LocalizedText(msg, msgEn), dedupe, Set.of());
                queued = pr.get("queued") instanceof Number n ? n.intValue() : 0;
                if (queued > 0) delivered = true;
            } catch (Exception ex) {
                log.warn("TLS notu düşüş push'u kuyruğa alınamadı (takım {}): {}", teamId, ex.toString());
            }
        }
        saveLog(teamName, recipients, subject, status + (queued > 0 ? " · push " + queued : ""));
        return delivered ? Delivery.DELIVERED : attempted ? Delivery.FAILED : Delivery.NONE;
    }

    private void saveLog(String teamName, List<String> recipients, String subject, String status) {
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);   // alarm kaynaklı değil (kolon NOT NULL) — haftalık rapor deseni
            n.setSentAt(ISO.format(Instant.now()));
            n.setRecipientName(teamName);
            n.setRecipientEmail(clipTo(String.join(", ", recipients), LOG_FIELD_MAX));
            n.setRecipientRole("COMBINED");
            n.setSubject(subject);
            n.setEmailStatus(clipTo(status, LOG_FIELD_MAX));
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(TRIGGER);
            n.setEmailFrom(email.getEmailFrom());
            notificationLogRepo.save(n);
        } catch (Exception e) {
            log.warn("TLS notu düşüş bildirimi günlük kaydı yazılamadı: {}", e.getMessage());
        }
    }

    /** Sütun uzunluğuna kırp (uzun alıcı listesi / hata metni INSERT'i düşürmesin — tek iz kaybolurdu). */
    static String clipTo(String v, int max) {
        if (v == null || v.length() <= max) return v;
        return v.substring(0, Math.max(0, max - 1)) + "…";
    }

    private void finish(List<TlsGradeChange> rows, String state, String now) {
        if (rows == null || rows.isEmpty()) return;
        List<Long> ids = rows.stream().map(TlsGradeChange::getId).toList();
        for (int i = 0; i < ids.size(); i += BATCH) {
            changeRepo.finishNotify(ids.subList(i, Math.min(ids.size(), i + BATCH)), clip(state), now);
        }
    }

    private boolean safeMuted() {
        try { return systemMaintenance.notificationsMuted(); } catch (Exception e) { return false; }
    }

    /** Geçerli not (A+…F) ya da varsayılan A. */
    static String normalizeMinGrade(String raw) {
        String g = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        return TlsGradeRules.isGrade(g) ? g : "A";
    }

    private static String clip(String s) {
        return s == null ? null : s.length() <= 60 ? s : s.substring(0, 60);
    }
}
