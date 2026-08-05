package com.certmonitor.service;

import com.certmonitor.model.Team;
import com.certmonitor.model.WeeklyReport;
import com.certmonitor.repository.TeamRepository;
import com.certmonitor.repository.WeeklyReportRepository;
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

    @Value("${cert.monitor.weekly-report.reminder-enabled:true}")
    private boolean enabled;

    @Value("${cert.monitor.app.base-url:http://localhost:5173}")
    private String appBaseUrl;

    /** Gönderim özeti — loglama/test için. */
    public record ReminderResult(int candidates, int sent, int skippedNoEmail, int skippedDone) {}

    public ReminderResult sendFridayReminders() {
        if (!enabled) {
            log.info("Haftalık rapor hatırlatması devre dışı (reminder-enabled=false) — atlandı");
            return new ReminderResult(0, 0, 0, 0);
        }

        LocalDate today = LocalDate.now(IST);
        int year = today.get(WeekFields.ISO.weekBasedYear());
        int week = today.get(WeekFields.ISO.weekOfWeekBasedYear());
        String weekLabel = WeeklyReportService.computeWeekLabel(year, week);
        String url = buildReportUrl();

        List<Team> syTeams = teamRepo.findByActiveTrueOrderByNameAsc();
        int sent = 0, skippedNoEmail = 0, skippedDone = 0;

        for (Team team : syTeams) {
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

            String subject = "[Site Monitör] " + team.getName() + " — Haftalık rapor hatırlatması (" + weekLabel + ")";
            String html = emailService.buildWeeklyReportReminderHtml(team.getName(), weekLabel, url);
            String status = emailService.sendHtml(new String[]{teamEmail}, null, subject, html, null);
            sent++;
            log.info("Haftalık rapor hatırlatması: team={} week={} to={} status={}",
                    team.getName(), weekLabel, teamEmail, status);
        }

        ReminderResult result = new ReminderResult(syTeams.size(), sent, skippedNoEmail, skippedDone);
        log.info("Haftalık rapor cuma hatırlatması tamamlandı: {} aday SY takımı → gönderilen={}, "
                + "atlanan(e-posta yok)={}, atlanan(zaten girilmiş)={}",
                result.candidates(), result.sent(), result.skippedNoEmail(), result.skippedDone());
        return result;
    }

    /** OpenShift route (APP_BASE_URL) + doğrudan Haftalık Raporlar sekmesi deep-link'i. */
    private String buildReportUrl() {
        String url = appSettings.getString("cert.monitor.app.base-url", appBaseUrl);
        String base = url != null ? url.replaceAll("/+$", "") : "";
        return base + "/?tab=weeklyreports";
    }
}
