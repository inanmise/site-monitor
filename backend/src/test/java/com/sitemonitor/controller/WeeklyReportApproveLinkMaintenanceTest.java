package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.WeeklyReportService;
import com.sitemonitor.util.SystemMaintenanceSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sistem Bakım Modu — haftalık rapor e-posta onay bağlantıları (2026-10-02): bakım AKTİFKEN (oturumsuz, PUBLIC) bağlantı
 * rapor durumunu DEĞİŞTİRMEZ, bakım sayfası döner ve deneme {@code WEEKLY_REPORT_LINK_DENIED} olarak denetlenir; bakım
 * bitince aynı bağlantı yeniden çalışır.
 */
@WebMvcTest(WeeklyReportController.class)
class WeeklyReportApproveLinkMaintenanceTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean WeeklyReportService service;
    @MockitoBean com.sitemonitor.service.WeeklyReportReminderService reminderService;
    @MockitoBean AuditService auditService;
    @MockitoBean com.sitemonitor.service.WeeklyReportKpiService kpiService;
    @MockitoBean com.sitemonitor.service.MonitoringWeeklyStatsService monitoringStatsService;
    @MockitoBean com.sitemonitor.service.PermissionService permissionService;
    @MockitoBean com.sitemonitor.service.AppSettingsService appSettings;
    @MockitoBean com.sitemonitor.repository.IncidentRecordRepository incidentRepo;
    @MockitoBean com.sitemonitor.repository.TeamRepository teamRepo;
    @MockitoBean com.sitemonitor.service.WeeklyReportTeamInfoService teamInfoService;
    @MockitoBean SystemMaintenanceService maintenance;

    @BeforeEach
    void setUp() {
        when(maintenance.signalBody()).thenReturn(SystemMaintenanceSignal.body(
                "02.10.2026 22:00 – 23:00 (İstanbul saati) arasında planlı bakım yapılmaktadır.", Map.of("state", "active")));
    }

    @Test
    @DisplayName("bakımda GET onay sayfası → bakım mesajı, form YOK, denetim; token durumu sorulmaz")
    void page_duringMaintenance() throws Exception {
        when(maintenance.isActive()).thenReturn(true);
        mvc.perform(get("/api/weekly-reports/approve-link").param("token", "T1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("planlı bakım")))
                .andExpect(content().string(not(containsString("Raporu Onayla"))));
        verify(service, never()).approvalTokenStatus(any());
        verify(auditService).recordSecurityEvent(eq("WEEKLY_REPORT_LINK_DENIED"), any(), any(), eq("WEEKLY_REPORT"),
                eq("email-token"), eq("system_maintenance (link opened)"));
    }

    @Test
    @DisplayName("bakımda POST onayla / iade et → rapor DEĞİŞMEZ (servis çağrılmaz)")
    void confirmAndReject_duringMaintenance() throws Exception {
        when(maintenance.isActive()).thenReturn(true);
        mvc.perform(post("/api/weekly-reports/approve-link/confirm").param("token", "T1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("planlı bakım")));
        mvc.perform(post("/api/weekly-reports/approve-link/reject").param("token", "T1").param("reason", "x"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("planlı bakım")));
        verify(service, never()).approveViaToken(any());
        verify(service, never()).rejectViaToken(any(), any());
    }

    @Test
    @DisplayName("bakım bitince aynı bağlantı yeniden çalışır")
    void afterMaintenance_works() throws Exception {
        when(maintenance.isActive()).thenReturn(false);
        when(service.approvalTokenStatus("T1")).thenReturn(Map.of("valid", true, "team_name", "TakimA", "week_label", "2026-W24"));
        mvc.perform(get("/api/weekly-reports/approve-link").param("token", "T1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Raporu Onayla")));
    }
}
