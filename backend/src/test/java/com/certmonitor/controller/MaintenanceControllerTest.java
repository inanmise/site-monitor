package com.certmonitor.controller;

import com.certmonitor.model.MaintenanceWindow;
import com.certmonitor.repository.MaintenanceWindowRepository;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.MaintenanceService;
import com.certmonitor.service.PermissionService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MaintenanceController.class)
class MaintenanceControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean MaintenanceWindowRepository repo;
    @MockitoBean MaintenanceService maintenanceService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.certmonitor.service.HttpMetricsService httpMetricsService;

    private MockHttpSession session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private static MaintenanceWindow win() {
        MaintenanceWindow w = new MaintenanceWindow();
        w.setId(1L); w.setName("DB bakımı"); w.setStartAt("2026-01-01T10:00:00");
        w.setDurationMinutes(60); w.setRecurrence("NONE"); w.setTimezone("Europe/Istanbul"); w.setActive(true);
        return w;
    }

    @Test
    @DisplayName("GET /maintenance: pencereleri DTO olarak döner (status/next_occurrence dahil)")
    void list() throws Exception {
        when(repo.findAllByOrderByStartAtDesc()).thenReturn(List.of(win()));
        when(maintenanceService.computeStatus(any(), any())).thenReturn("upcoming");
        when(maintenanceService.targetCount(any())).thenReturn(3);
        mvc.perform(get("/api/monitoring/maintenance").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("DB bakımı"))
                .andExpect(jsonPath("$.data[0].status").value("upcoming"))
                .andExpect(jsonPath("$.data[0].target_count").value(3));
    }

    @Test
    @DisplayName("POST /maintenance: geçerli gövde kaydeder + cache refresh")
    void create() throws Exception {
        when(repo.save(any(MaintenanceWindow.class))).thenAnswer(inv -> { MaintenanceWindow w = inv.getArgument(0); w.setId(9L); return w; });
        mvc.perform(post("/api/monitoring/maintenance").session(session())
                        .contentType("application/json")
                        .content("{\"name\":\"Bakım\",\"startAt\":\"2026-05-01T02:00:00\",\"durationMinutes\":120,\"recurrence\":\"DAILY\",\"allMonitors\":true}"))
                .andExpect(status().isOk());
        verify(repo).save(any(MaintenanceWindow.class));
        verify(maintenanceService).refresh();
    }

    @Test
    @DisplayName("POST /maintenance: isim yoksa 400")
    void create_missingName() throws Exception {
        mvc.perform(post("/api/monitoring/maintenance").session(session())
                        .contentType("application/json").content("{\"startAt\":\"2026-05-01T02:00:00\"}"))
                .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("POST /maintenance: ayrıştırılamayan startAt → 400 (sessiz-inert pencereyi önler) (M9)")
    void create_malformedStart_400() throws Exception {
        mvc.perform(post("/api/monitoring/maintenance").session(session())
                        .contentType("application/json")
                        .content("{\"name\":\"X\",\"startAt\":\"not-a-date\",\"durationMinutes\":60,\"recurrence\":\"NONE\"}"))
                .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("POST /maintenance: WEEKLY ama gün seçilmemiş → 400 (hiç tetiklenmeyen pencereyi önler) (M9)")
    void create_weeklyNoDays_400() throws Exception {
        mvc.perform(post("/api/monitoring/maintenance").session(session())
                        .contentType("application/json")
                        .content("{\"name\":\"X\",\"startAt\":\"2026-01-01T10:00:00\",\"durationMinutes\":60,\"recurrence\":\"WEEKLY\",\"daysOfWeek\":\"\"}"))
                .andExpect(status().isBadRequest());
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("POST /maintenance/quick: ad-hoc pencere oluşturur")
    void quick() throws Exception {
        when(repo.save(any(MaintenanceWindow.class))).thenAnswer(inv -> { MaintenanceWindow w = inv.getArgument(0); w.setId(3L); return w; });
        mvc.perform(post("/api/monitoring/maintenance/quick").session(session())
                        .contentType("application/json").content("{\"minutes\":30,\"allMonitors\":true}"))
                .andExpect(status().isOk());
        verify(repo).save(any(MaintenanceWindow.class));
    }

    @Test
    @DisplayName("DELETE /maintenance/{id}: siler + refresh")
    void delete_ok() throws Exception {
        when(repo.findById(1L)).thenReturn(Optional.of(win()));
        mvc.perform(delete("/api/monitoring/maintenance/1").session(session()))
                .andExpect(status().isOk());
        verify(repo).deleteById(1L);
        verify(maintenanceService).refresh();
    }

    @Test
    @DisplayName("GET /maintenance/active: aktif hedef bilgisini döner")
    void active() throws Exception {
        when(maintenanceService.activeInfo()).thenReturn(java.util.Map.of("all", false, "targets", List.of()));
        mvc.perform(get("/api/monitoring/maintenance/active").session(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.all").value(false));
    }
}
