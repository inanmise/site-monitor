package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.NotificationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Cuma haftalık rapor hatırlatması — o anki ISO haftası için raporunu henüz
 * onaya göndermemiş (rapor yok / DRAFT / REJECTED) AKTİF SY takımlarına executive
 * bir hatırlatma maili gönderir. Tetikleme + HA lock {@link SchedulerService}'tedir;
 * bu servis saf seçim + mail oluşturma/gönderme yapar (test edilebilir).
 *
 * <p><b>Takım başına yalıtım + iz (çok pod R2a, MP-42, 2026-10-22):</b> bir takımın hatası (şablon, posta servisi) diğer
 * takımları DURDURMAZ — eskiden döngü ilk istisnada kesiliyor, kalan takımlar o hafta hatırlatma almıyor ve hiçbir iz
 * kalmıyordu. Modülü açık her takımın kararı bir {@code notification_logs} satırı bırakır (tetik {@value #TRIGGER}, alarm
 * kimliği 0): gönderildi / başarısız / {@value #ST_NO_EMAIL} / {@value #ST_DONE} / {@value #ST_OFF}. Modülü kapalı takım
 * için satır yazılmaz (özellik o takımda yokmuş gibi).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyReportReminderService {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    /** Raporu "girilmiş" sayan durumlar — bu takımlar rahatsız edilmez. */
    private static final Set<String> DONE_STATUSES = Set.of("PENDING_APPROVAL", "APPROVED");

    /** {@code notification_logs.trigger} (R2a, MP-42). */
    public static final String TRIGGER = "WEEKLY_REPORT_REMINDER";
    static final String ST_NO_EMAIL = "SKIPPED: takım e-postası yok";
    static final String ST_DONE = "SKIPPED: rapor zaten onaya gönderilmiş";
    static final String ST_OFF = "SKIPPED: takımın hatırlatması kapalı";

    private final TeamRepository teamRepo;
    private final WeeklyReportRepository reportRepo;
    private final EmailNotificationService emailService;
    private final AppSettingsService appSettings;

    /** Karar izi (R2a, MP-42); yoksa (dilim testleri) yalnız uygulama günlüğü. */
    @Autowired(required = false)
    private NotificationLogRepository logRepo;

    void setLogRepo(NotificationLogRepository r) { this.logRepo = r; }

    @Value("${site.monitor.weekly-report.reminder-enabled:true}")
    private boolean enabled;

    @Value("${site.monitor.app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    /** "bugün saat 15:00" / "Cuma saat 15:00" — mail vurgusu; son giriş günü bugünse "bugün". */
    static String deadlineText(WeeklyReportDeadline d, LocalDate today) {
        String when = today.getDayOfWeek() == d.day() ? "bugün" : d.dayNameTr();
        return when + " saat " + d.timeText();
    }

    /** Gönderim özeti — loglama/test için. {@code failed}: takımın gönderimi hata verdi (R2a, MP-42; diğer takımlar sürdü). */
    public record ReminderResult(int candidates, int sent, int skippedNoEmail, int skippedDone, int skippedDisabled, int failed) {
        public ReminderResult(int candidates, int sent, int skippedNoEmail, int skippedDone, int skippedDisabled) {
            this(candidates, sent, skippedNoEmail, skippedDone, skippedDisabled, 0);
        }
    }

    public ReminderResult sendFridayReminders() { return sendFridayReminders(false); }

    /**
     * @param scheduledRun true = zamanlayıcıdan (her gün 09:00): yalnız SON GİRİŞ GÜNÜNDE gönderir;
     *                     false = elle tetik (/reminders/trigger): gün kontrolü yok.
     */
    public ReminderResult sendFridayReminders(boolean scheduledRun) {
        if (!enabled) {
            log.info("Haftalık rapor hatırlatması devre dışı (reminder-enabled=false) — atlandı");
            return new ReminderResult(0, 0, 0, 0, 0);
        }

        LocalDate today = LocalDate.now(IST);
        WeeklyReportDeadline deadline = WeeklyReportDeadline.resolve(appSettings);
        if (scheduledRun && today.getDayOfWeek() != deadline.day()) {
            log.debug("Haftalık rapor hatırlatması: bugün {} son giriş günü değil ({}) — atlandı",
                    today.getDayOfWeek(), deadline.day());
            return new ReminderResult(0, 0, 0, 0, 0);
        }
        int year = today.get(WeekFields.ISO.weekBasedYear());
        int week = today.get(WeekFields.ISO.weekOfWeekBasedYear());
        String weekLabel = WeeklyReportService.computeWeekLabel(year, week);
        String url = buildReportUrl();

        List<Team> syTeams = teamRepo.findByActiveTrueOrderByNameAsc();
        int sent = 0, skippedNoEmail = 0, skippedDone = 0, skippedDisabled = 0, failed = 0;

        for (Team team : syTeams) {
            // Takım başına opt-in: anahtar kapalıysa (veya hiç açılmamışsa) bu takım rahatsız edilmez.
            // Filtre repo sorgusuna DEĞİL buraya konur — findByActiveTrueOrderByNameAsc başka üç akış
            // tarafından da kullanılıyor (CertificateService dahil).
            // Modül o takımda kapalıysa (2026-09-16) hatırlatma da gitmez — özellik hiç yokmuş gibi davranır.
            if (!Boolean.TRUE.equals(team.getWeeklyReportsEnabled())) { skippedDisabled++; continue; }
            String subject = "[Site Monitor] " + team.getName() + " — Haftalık rapor hatırlatması (" + weekLabel + ")";
            String teamEmail = team.getEmail() != null ? team.getEmail().trim() : "";
            // Takım başına yalıtım (R2a, MP-42): bir takımın hatası sıradaki takımları durdurmaz, kararı iz bırakır
            try {
                if (!Boolean.TRUE.equals(team.getWeeklyReminderEnabled())) {
                    skippedDisabled++;
                    trace(team, teamEmail, subject, ST_OFF, null);
                    continue;
                }

                Optional<WeeklyReport> existing =
                        reportRepo.findByTeamIdAndReportYearAndWeekNo(team.getId(), year, week);
                if (existing.isPresent() && DONE_STATUSES.contains(existing.get().getStatus())) {
                    skippedDone++;
                    trace(team, teamEmail, subject, ST_DONE, null);
                    continue; // zaten onaya gönderilmiş/onaylanmış → rahatsız etme
                }

                if (teamEmail.isBlank()) {
                    skippedNoEmail++;
                    trace(team, teamEmail, subject, ST_NO_EMAIL, null);
                    log.warn("Haftalık rapor hatırlatması atlandı (takım e-postası yok): team={} ({})",
                            team.getName(), team.getId());
                    continue;
                }

                // Logo şablonun başlık çubuğundan gelir; CID ekini sendHtml hunisi otomatik iliştirir.
                String html = emailService.buildWeeklyReportReminderHtml(team.getName(), weekLabel, url,
                        deadlineText(deadline, today));
                String status = emailService.sendHtml(new String[]{teamEmail}, null, subject, html, null);
                String st = status == null ? "FAILED: sonuç yok" : status;
                trace(team, teamEmail, subject, st, html);
                if (st.startsWith("SENT") || st.startsWith("QUEUED")) sent++;
                else if (st.startsWith("SKIPPED")) skippedDisabled++;
                else failed++;
                log.info("Haftalık rapor hatırlatması: team={} week={} to={} status={}",
                        team.getName(), weekLabel, SecretMask.maskEmails(teamEmail), status);
            } catch (RuntimeException e) {
                failed++;
                log.warn("Haftalık rapor hatırlatması takım {} ({}) için gönderilemedi — diğer takımlar sürüyor: {}",
                        team.getName(), team.getId(), e.toString());
                trace(team, teamEmail, subject, "FAILED: " + e.getClass().getSimpleName(), null);
            }
        }

        ReminderResult result = new ReminderResult(syTeams.size(), sent, skippedNoEmail, skippedDone, skippedDisabled, failed);
        log.info("Haftalık rapor cuma hatırlatması tamamlandı: {} aday SY takımı → gönderilen={}, "
                + "atlanan(e-posta yok)={}, atlanan(zaten girilmiş)={}, atlanan(hatırlatma kapalı)={}, hata={}",
                result.candidates(), result.sent(), result.skippedNoEmail(), result.skippedDone(),
                result.skippedDisabled(), result.failed());
        return result;
    }

    /**
     * Takımın kararının izi (R2a, MP-42): bir {@code notification_logs} satırı. Gönderilen postada gövde {@code message}'a
     * yazılır (SMTP günlüğünden yeniden gönderilebilsin; 421 yeniden denemesi sonucu konu + alıcıyla bu satıra yazar). Hata
     * yutulur — iz yazılamaması hatırlatmayı değiştirmez.
     */
    private void trace(Team team, String to, String subject, String status, String html) {
        if (logRepo == null) return;
        try {
            NotificationLog n = new NotificationLog();
            n.setAlertEventId(0L);                       // alarm kaynaklı değil (kolon NOT NULL)
            n.setSentAt(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now()));
            n.setRecipientName(team.getName());
            n.setRecipientEmail(to == null || to.isBlank() ? "-" : to);
            n.setRecipientRole("TEAM");
            n.setSubject(subject);
            n.setMessage(html);
            n.setEmailStatus(status);
            n.setWebhookStatus("SKIPPED");
            n.setTrigger(TRIGGER);
            n.setEmailFrom(emailService.getEmailFrom());
            logRepo.save(n);
        } catch (RuntimeException e) {
            log.warn("Haftalık rapor hatırlatması izi yazılamadı (takım {}): {}", team.getId(), e.toString());
        }
    }

    /** OpenShift route (APP_BASE_URL) + doğrudan Haftalık Raporlar sekmesi deep-link'i. */
    private String buildReportUrl() {
        String url = appSettings.getString("site.monitor.app.base-url", appBaseUrl);
        String base = url != null ? url.replaceAll("/+$", "") : "";
        return base + "/?tab=weeklyreports";
    }
}
