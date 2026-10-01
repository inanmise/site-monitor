package com.sitemonitor.controller;

import com.sitemonitor.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fırtına gözlem uçları (2026-09-30): {@code alerts.read/view} kapısı, görüş kapsamı (global görüntüleyici hepsini,
 * kapsamlı kullanıcı yalnız görüş takımlarını), 404 ayrıntı; ayar uçlarına dokunmaz.
 */
@WebMvcTest(StormStatusController.class)
class StormStatusControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean StormStatusService statusService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        doThrow(new SecurityException("no perm")).when(permissionService)
                .require(argThat((jakarta.servlet.http.HttpSession s) -> s != null && !"ok".equals(s.getAttribute("perm"))),
                         eq("alerts.read"), eq("view"));
        when(statusService.status(any(), any(), anyBoolean(), anyBoolean())).thenReturn(Map.of("teams", List.of(), "totals", Map.of("teams", 0)));
        when(statusService.history(any(), anyBoolean(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(Map.of("items", List.of(), "total", 0L));
        when(statusService.analytics(any(), anyBoolean(), any(), anyInt())).thenReturn(Map.of("total", 0));
    }

    private MockHttpSession session(String role, List<Long> viewTeamIds, boolean perm) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        if (viewTeamIds != null) s.setAttribute("viewTeamIds", viewTeamIds);
        if (perm) s.setAttribute("perm", "ok");
        return s;
    }

    @Test
    @DisplayName("oturumsuz → 401; izinsiz → 403")
    void gates() throws Exception {
        mvc.perform(get("/api/monitoring/storm/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/monitoring/storm/status").session(session("USER", List.of(14L), false))).andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/storm/history").session(session("USER", List.of(14L), false))).andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/storm/analytics").session(session("USER", List.of(14L), false))).andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/storm/7").session(session("USER", List.of(14L), false))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("kapsamlı kullanıcı: yüklem yalnız görüş takımlarında true, seesAll=false; global görüntüleyici: seesAll=true")
    @SuppressWarnings("unchecked")
    void scopePredicate() throws Exception {
        mvc.perform(get("/api/monitoring/storm/status").session(session("USER", List.of(14L), true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        org.mockito.ArgumentCaptor<java.util.function.Predicate<Long>> cap = org.mockito.ArgumentCaptor.forClass(java.util.function.Predicate.class);
        verify(statusService).status(eq("14"), cap.capture(), eq(false), eq(false));
        org.assertj.core.api.Assertions.assertThat(cap.getValue().test(14L)).isTrue();
        org.assertj.core.api.Assertions.assertThat(cap.getValue().test(15L)).isFalse();

        mvc.perform(get("/api/monitoring/storm/status?fresh=true").session(session("ADMIN", null, true))).andExpect(status().isOk());
        verify(statusService).status(eq("ALL"), any(), eq(true), eq(true));

        mvc.perform(get("/api/monitoring/storm/history?teamId=14&from=2026-09-01&to=2026-09-30&resolvedOnly=true&page=2&size=10")
                        .session(session("ADMIN", null, true)))
                .andExpect(status().isOk());
        verify(statusService).history(any(), eq(true), isNull(), eq(14L), eq("2026-09-01"), eq("2026-09-30"), eq(true), eq(2), eq(10));

        mvc.perform(get("/api/monitoring/storm/analytics?days=90").session(session("AUDIT", null, true))).andExpect(status().isOk());
        verify(statusService).analytics(any(), eq(true), isNull(), eq(90));
    }

    @Test
    @DisplayName("ayrıntı: kapsam dışı / yok → 404, var → 200")
    void detail() throws Exception {
        when(statusService.detail(eq(7L), any(), anyBoolean())).thenReturn(null);
        mvc.perform(get("/api/monitoring/storm/7").session(session("USER", List.of(14L), true))).andExpect(status().isNotFound());
        when(statusService.detail(eq(8L), any(), anyBoolean())).thenReturn(Map.of("id", 8L, "members", List.of()));
        mvc.perform(get("/api/monitoring/storm/8").session(session("USER", List.of(14L), true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(8));
    }
}
