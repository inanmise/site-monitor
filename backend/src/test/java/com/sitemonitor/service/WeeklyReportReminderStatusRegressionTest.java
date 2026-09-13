package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeeklyReportImageRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Regression: ISSUE-002 — hatırlatma durumu sayaçları BUGÜNÜN haftasına bakıyordu; son giriş günü geçince
// (Cmt/Paz) "2 takıma gidecek" gibi yanlış sayı gösteriyordu, oysa sonraki koşu gelecek haftada.
// Found by /qa on 2026-09-13
// Report: .gstack/qa-reports/qa-report-localhost-2026-09-13.md
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyReportReminderStatusRegressionTest {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");

    @Mock WeeklyReportRepository reportRepo;
    @Mock WeeklyReportImageRepository imageRepo;
    @Mock com.sitemonitor.repository.WeeklyReportMailRepository mailRepo;
    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock EmailNotificationService emailService;
    @Mock AppSettingsService appSettings;
    @Mock PermissionService permissionService;
    @Mock WeeklyReportKpiService kpiService;
    @Mock MonitoringWeeklyStatsService monitoringStatsService;

    private WeeklyReportService service;

    @BeforeEach
    void setUp() {
        service = new WeeklyReportService(reportRepo, imageRepo, mailRepo, teamRepo, userRepo,
                contactRepo, emailService, new ObjectMapper(), appSettings, permissionService, kpiService, monitoringStatsService,
                org.mockito.Mockito.mock(com.sitemonitor.repository.DomainMonitorRepository.class),
                org.mockito.Mockito.mock(com.sitemonitor.repository.DomainCheckRepository.class));
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));   // varsayılan: FRI 15:00
        Team a = new Team(); a.setId(2L); a.setName("Takım A"); a.setEmail("a@example.com"); a.setWeeklyReminderEnabled(true);
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(a));
    }

    private static WeeklyReport approved() { WeeklyReport r = new WeeklyReport(); r.setId(1L); r.setTeamId(2L); r.setStatus("APPROVED"); return r; }

    @Test
    @DisplayName("Cumartesi: bu haftanın raporu girilmiş olsa da sayaçlar GELECEK haftaya (sonraki Cuma) bakar → 1 takıma gidecek, 0 girmiş")
    void saturday_countsNextWeek() {
        // 2026-09-12 Cumartesi 10:00 IST — son giriş (Cuma 15:00) geçti; sonraki koşu 18 Eylül Cuma (ISO 2026-W38)
        ZonedDateTime saturday = ZonedDateTime.of(2026, 9, 12, 10, 0, 0, 0, IST);
        assertThat(saturday.getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(2L, 2026, 37)).thenReturn(Optional.of(approved()));   // bu hafta girildi
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(2L, 2026, 38)).thenReturn(Optional.empty());          // gelecek hafta yok

        Map<String, Object> st = service.reminderStatus(true, saturday);

        assertThat(st).containsEntry("run_week", "2026-W38")
                .containsEntry("will_send", 1).containsEntry("already_done", 0).containsEntry("opt_in_teams", 1);
        assertThat(String.valueOf(st.get("next_run_at"))).startsWith("2026-09-18T06:00");   // 09:00 IST = 06:00 UTC
        verify(reportRepo).findByTeamIdAndReportYearAndWeekNo(2L, 2026, 38);
        verify(reportRepo, org.mockito.Mockito.never()).findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), org.mockito.ArgumentMatchers.eq(37));
    }

    @Test
    @DisplayName("Çarşamba: sonraki koşu bu haftanın Cuması → sayaçlar bu haftaya bakar (girilmiş rapor sayılır)")
    void wednesday_countsThisWeek() {
        ZonedDateTime wednesday = ZonedDateTime.of(2026, 9, 9, 10, 0, 0, 0, IST);   // ISO 2026-W37
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(2L, 2026, 37)).thenReturn(Optional.of(approved()));

        Map<String, Object> st = service.reminderStatus(true, wednesday);

        assertThat(st).containsEntry("run_week", "2026-W37").containsEntry("will_send", 0).containsEntry("already_done", 1);
        assertThat(String.valueOf(st.get("next_run_at"))).startsWith("2026-09-11T06:00");
    }

    @Test
    @DisplayName("Cuma 09:30 (koşu saati geçti, son giriş henüz değil): sonraki koşu GELECEK Cuma → gelecek hafta sayılır")
    void fridayAfterRun_countsNextWeek() {
        ZonedDateTime friday = ZonedDateTime.of(2026, 9, 11, 9, 30, 0, 0, IST);
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());

        Map<String, Object> st = service.reminderStatus(true, friday);

        assertThat(st).containsEntry("run_week", "2026-W38");
        assertThat(String.valueOf(st.get("next_run_at"))).startsWith("2026-09-18T06:00");
    }
}
