package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.watchdog.WatchdogNotifier;
import com.sitemonitor.service.watchdog.WatchdogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Kendi kendini izleme uçları (2026-10-11): oturumsuz dış uç (200 / 503, no-store), yönetici yetkisi, test sınırı. */
@WebMvcTest(WatchdogController.class)
class WatchdogControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean WatchdogService watchdog;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private static MockHttpSession session(String user, String role, boolean global) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", user);
        s.setAttribute("systemRole", role);
        if (!global) s.setAttribute("viewTeamIds", new java.util.ArrayList<>(java.util.List.of(5L)));
        return s;
    }

    @Test
    @DisplayName("dış uç oturumsuz: UP → 200, DOWN → 503; no-store")
    void publicEndpoint() throws Exception {
        when(watchdog.publicStatus(any())).thenReturn(Map.of("status", "UP", "component", "watchdog"));
        mvc.perform(get("/api/public/health/watchdog"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.status").value("UP"));
        when(watchdog.publicStatus(any())).thenReturn(Map.of("status", "DOWN"));
        mvc.perform(get("/api/public/health/watchdog")).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("yönetici durumu izin ister; izin yoksa 403")
    void adminStatus() throws Exception {
        when(watchdog.adminStatus(any())).thenReturn(Map.of("status", "UP"));
        mvc.perform(get("/api/admin/system/watchdog").session(session("admin", "ADMIN", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("UP"));
        doThrow(new SecurityException("no")).when(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("system_health.read"), eq("view"));
        mvc.perform(get("/api/admin/system/watchdog").session(session("u", "USER", false))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("test bildirimi: kapsamlı müdür 403; global yönetici 200 + denetim; dakikada 3'ten fazlası 429")
    void testNotification() throws Exception {
        mvc.perform(post("/api/admin/system/watchdog/test").session(session("mudur", "ADMIN", false))).andExpect(status().isForbidden());
        verify(watchdog, never()).sendTest();
        when(watchdog.sendTest()).thenReturn(new WatchdogNotifier.Report(WatchdogNotifier.Outcome.DELIVERED, "SENT", "SKIPPED", "SKIPPED", 2));
        MockHttpSession admin = session("admin", "ADMIN", true);
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/admin/system/watchdog/test").session(admin))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("DELIVERED"));
        }
        mvc.perform(post("/api/admin/system/watchdog/test").session(admin))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verify(auditService, org.mockito.Mockito.times(3)).recordAction(eq("WATCHDOG_TEST"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("şimdi değerlendir: eylem izni + denetim; tur özeti döner (kümede zamanlanmış turla aynı kilit)")
    void evaluate() throws Exception {
        when(watchdog.enabled()).thenReturn(true);
        when(watchdog.evaluateManually(any())).thenReturn(java.util.Optional.of(new WatchdogService.Round(6, 1, 0, 0, "DELIVERED", false)));
        when(watchdog.adminStatus(any())).thenReturn(Map.of("status", "DEGRADED"));
        mvc.perform(post("/api/admin/system/watchdog/evaluate").session(session("admin", "ADMIN", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.round.alerts").value(1))
                .andExpect(jsonPath("$.data.status").value("DEGRADED"));
        verify(auditService).recordAction(eq("WATCHDOG_EVALUATE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("SYSTEM"), eq("watchdog"), anyString());
        verify(watchdog, never()).evaluate(any());
    }

    @Test
    @DisplayName("2026-10-12: kapalıyken değerlendirme yok (409 WATCHDOG_DISABLED); başka pod'da tur sürüyorsa 409 WATCHDOG_BUSY")
    void evaluateDisabledOrBusy() throws Exception {
        MockHttpSession admin = session("admin", "ADMIN", true);
        when(watchdog.enabled()).thenReturn(false);
        mvc.perform(post("/api/admin/system/watchdog/evaluate").session(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WATCHDOG_DISABLED"));
        verify(watchdog, never()).evaluateManually(any());
        when(watchdog.enabled()).thenReturn(true);
        when(watchdog.evaluateManually(any())).thenReturn(java.util.Optional.empty());
        mvc.perform(post("/api/admin/system/watchdog/evaluate").session(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WATCHDOG_BUSY"));
        verify(auditService, never()).recordAction(eq("WATCHDOG_EVALUATE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
    }
}
