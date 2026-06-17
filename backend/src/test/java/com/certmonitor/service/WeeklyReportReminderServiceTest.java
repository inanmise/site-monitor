package com.certmonitor.service;

import com.certmonitor.model.Team;
import com.certmonitor.model.WeeklyReport;
import com.certmonitor.repository.TeamRepository;
import com.certmonitor.repository.WeeklyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyReportReminderServiceTest {

    @Mock TeamRepository teamRepo;
    @Mock WeeklyReportRepository reportRepo;
    @Mock EmailNotificationService emailService;
    @Mock AppSettingsService appSettings;

    private WeeklyReportReminderService service;

    @BeforeEach
    void setUp() {
        service = new WeeklyReportReminderService(teamRepo, reportRepo, emailService, appSettings);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://cm.example.com/");
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(emailService.buildWeeklyReportReminderHtml(any(), any(), any())).thenReturn("<html/>");
        when(emailService.sendHtml(any(), any(), anyString(), anyString(), any())).thenReturn("SENT");
    }

    private static Team team(Long id, String name, String email) {
        Team t = new Team();
        t.setId(id); t.setName(name); t.setEmail(email); t.setActive(true);
        return t;
    }

    private static WeeklyReport reportWithStatus(String status) {
        WeeklyReport r = new WeeklyReport();
        r.setStatus(status);
        return r;
    }

    @Test
    @DisplayName("sendFridayReminders: yalnız onaya-göndermemiş + e-postası olan SY takımlarına gönderir")
    void sendsOnlyToTeamsThatHaveNotSubmitted() {
        Team a = team(1L, "AlphaSY",   "alpha@x.com");   // rapor yok       → gönder
        Team b = team(2L, "BetaSY",    "beta@x.com");    // DRAFT           → gönder
        Team c = team(3L, "GammaSY",   "gamma@x.com");   // APPROVED        → atla (girilmiş)
        Team d = team(4L, "DeltaSY",   "");              // rapor yok, mail yok → atla
        Team e = team(5L, "EpsilonSY", "eps@x.com");     // PENDING_APPROVAL → atla (girilmiş)
        when(teamRepo.findByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(a, b, c, d, e));

        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(1L), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(2L), anyInt(), anyInt())).thenReturn(Optional.of(reportWithStatus("DRAFT")));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(3L), anyInt(), anyInt())).thenReturn(Optional.of(reportWithStatus("APPROVED")));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(4L), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(eq(5L), anyInt(), anyInt())).thenReturn(Optional.of(reportWithStatus("PENDING_APPROVAL")));

        WeeklyReportReminderService.ReminderResult res = service.sendFridayReminders();

        assertThat(res.candidates()).isEqualTo(5);
        assertThat(res.sent()).isEqualTo(2);
        assertThat(res.skippedNoEmail()).isEqualTo(1);
        assertThat(res.skippedDone()).isEqualTo(2);

        // İki gönderim; alıcılar alpha + beta
        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService, times(2)).sendHtml(toCap.capture(), isNull(), anyString(), anyString(), isNull());
        assertThat(toCap.getAllValues().stream().map(arr -> arr[0]))
                .containsExactlyInAnyOrder("alpha@x.com", "beta@x.com");
    }

    @Test
    @DisplayName("sendFridayReminders: mail linki ?tab=weeklyreports deep-link içerir (çift / temizlenir)")
    void buildsDeepLinkUrl() {
        Team a = team(1L, "AlphaSY", "alpha@x.com");
        when(teamRepo.findByActiveTrueOrderByNameAsc()).thenReturn(java.util.List.of(a));
        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());

        service.sendFridayReminders();

        ArgumentCaptor<String> urlCap = ArgumentCaptor.forClass(String.class);
        verify(emailService).buildWeeklyReportReminderHtml(eq("AlphaSY"), anyString(), urlCap.capture());
        assertThat(urlCap.getValue()).isEqualTo("https://cm.example.com/?tab=weeklyreports");
    }

    @Test
    @DisplayName("sendFridayReminders: devre dışıyken hiç gönderim yapmaz")
    void disabledSkipsEntirely() {
        ReflectionTestUtils.setField(service, "enabled", false);

        WeeklyReportReminderService.ReminderResult res = service.sendFridayReminders();

        assertThat(res.sent()).isZero();
        verifyNoInteractions(teamRepo, reportRepo);
        verify(emailService, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }
}
