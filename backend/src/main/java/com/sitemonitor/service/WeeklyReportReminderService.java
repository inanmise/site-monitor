package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyReportReminderService {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    /** Raporu "girilmiş" sayan durumlar — bu takımlar rahatsız edilmez. */
    private static final Set<String> DONE_STATUSES = Set.of("PENDING_APPROVAL", "APPROVED");

    private final TeamRepository teamRepo;
    private final WeeklyReportRepository reportRepo;
    private final EmailNotificationService emailService;
    private final AppSettingsService appSettings;

    @Value("${site.monitor.weekly-report.reminder-enabled:true}")
    private boolean enabled;

    @Value("${site.monitor.app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    /** "bugün saat 15:00" / "Cuma saat 15:00" — mail vurgusu; son giriş günü bugünse "bugün". */
    static String deadlineText(WeeklyReportDeadline d, LocalDate today) {
        String when = today.getDayOfWeek() == d.day() ? "bugün" : d.dayNameTr();
        return when + " saat " + d.timeText();
    }

    /** Gönderim özeti — loglama/test için. */
    public record ReminderResult(int candidates, int sent, int skippedNoEmail, int skippedDone, int skippedDisabled) {}

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
        int sent = 0, skippedNoEmail = 0, skippedDone = 0, skippedDisabled = 0;

        for (Team team : syTeams) {
            // Takım başına opt-in: anahtar kapalıysa (veya hiç açılmamışsa) bu takım rahatsız edilmez.
            // Filtre repo sorgusuna DEĞİL buraya konur — findByActiveTrueOrderByNameAsc başka üç akış
            // tarafından da kullanılıyor (CertificateService dahil).
            // Modül o takımda kapalıysa (2026-09-16) hatırlatma da gitmez — özellik hiç yokmuş gibi davranır.
            if (!Boolean.TRUE.equals(team.getWeeklyReportsEnabled())) { skippedDisabled++; continue; }
            if (!Boolean.TRUE.equals(team.getWeeklyReminderEnabled())) {
                skippedDisabled++;
                continue;
            }

            Optional<WeeklyReport> existing =
                    reportRepo.findByTeamIdAndReportYearAndWeekNo(team.getId(), year, week);
            if (existing.isPresent() && DONE_STATUSES.contains(existing.get().getStatus())) {
                skippedDone++;
                continue; // zaten onaya gönderilmiş/onaylanmış → rahatsız etme
            }

            String teamEmail = team.getEmail() != null ? team.getEmail().trim() : "";
            if (teamEmail.isBlank()) {
                skippedNoEmail++;
                log.warn("Haftalık rapor hatırlatması atlandı (takım e-postası yok): team={} ({})",
                        team.getName(), team.getId());
                continue;
            }

            String subject = "[Site Monitor] " + team.getName() + " — Haftalık rapor hatırlatması (" + weekLabel + ")";
            // Logo şablonun başlık çubuğundan gelir; CID ekini sendHtml hunisi otomatik iliştirir.
            String html = emailService.buildWeeklyReportReminderHtml(team.getName(), weekLabel, url,
                    deadlineText(deadline, today));
            String status = emailService.sendHtml(new String[]{teamEmail}, null, subject, html, null);
            sent++;
            log.info("Haftalık rapor hatırlatması: team={} week={} to={} status={}",
                    team.getName(), weekLabel, teamEmail, status);
        }

        ReminderResult result = new ReminderResult(syTeams.size(), sent, skippedNoEmail, skippedDone, skippedDisabled);
        log.info("Haftalık rapor cuma hatırlatması tamamlandı: {} aday SY takımı → gönderilen={}, "
                + "atlanan(e-posta yok)={}, atlanan(zaten girilmiş)={}, atlanan(hatırlatma kapalı)={}",
                result.candidates(), result.sent(), result.skippedNoEmail(), result.skippedDone(),
                result.skippedDisabled());
        return result;
    }

    /** OpenShift route (APP_BASE_URL) + doğrudan Haftalık Raporlar sekmesi deep-link'i. */
    private String buildReportUrl() {
        String url = appSettings.getString("site.monitor.app.base-url", appBaseUrl);
        String base = url != null ? url.replaceAll("/+$", "") : "";
        return base + "/?tab=weeklyreports";
    }
}
