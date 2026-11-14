package com.sitemonitor.controller;

import com.sitemonitor.service.AlertTrendService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kişisel Panom "Alarm eğilimi" ucu (2026-11-14): kapı ({@code alerts.read} yoksa {@code visible:false}, 403 değil), kapsam
 * ({@code /api/me/open-alerts} ile aynı: global görüntüleyici / 7-24 operatörü tümü, diğerleri görüş takımları + paylaşımlar),
 * geçersiz dönem 400, okuma hatası 503 (asla sıfır).
 */
@WebMvcTest(AlertTrendController.class)
class AlertTrendControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean AlertTrendService alertTrendService;
    @MockitoBean com.sitemonitor.service.PermissionService permissionService;
    @MockitoBean com.sitemonitor.service.AuditService auditService;
    @MockitoBean com.sitemonitor.service.noc.NocCallLogService nocCallLog;

    private static MockHttpSession session(String role, List<Long> viewTeams) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        if (viewTeams != null) s.setAttribute("viewTeamIds", viewTeams);
        return s;
    }

    private void allow() {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("alerts.read"), eq("view"))).thenReturn(true);
    }

    private static Map<String, Object> trend(String range) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("range", range);
        m.put("bucket", "7d".equals(range) ? "day" : "hour");
        m.put("buckets", List.of(Map.of("t", "2026-11-13T20:00:00", "opened", 2L, "closed", 1L)));
        m.put("totals", Map.of("opened", 2L, "closed", 1L, "critical", 0L, "closed_silent", 0L));
        return m;
    }

    @Test
    @DisplayName("alerts.read yok → visible:false (403 DEĞİL), servis hiç çağrılmaz")
    void noPermission_visibleFalse() throws Exception {
        mvc.perform(get("/api/me/alert-trend").session(session("USER", List.of(5L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.visible").value(false))
                .andExpect(jsonPath("$.data.buckets").doesNotExist());
        verify(alertTrendService, never()).build(anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("geçersiz dönem → 400 VALIDATION_FAILED + field=range; varsayılan dönem 24h")
    void invalidRange() throws Exception {
        allow();
        mvc.perform(get("/api/me/alert-trend").param("range", "30d").session(session("ADMIN", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("range"));
        verify(alertTrendService, never()).build(anyString(), anyBoolean(), any());

        when(alertTrendService.build(eq("24h"), eq(true), isNull())).thenReturn(trend("24h"));
        mvc.perform(get("/api/me/alert-trend").session(session("ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visible").value(true))
                .andExpect(jsonPath("$.data.range").value("24h"))
                .andExpect(jsonPath("$.data.totals.opened").value(2));
    }

    @Test
    @DisplayName("kapsam: global yönetici / AUDIT tümü; kapsamlı müdür ve USER görüş takımları + paylaşımlar; 7-24 operatörü tümü")
    void scopePerRole() throws Exception {
        allow();
        when(alertTrendService.build(anyString(), anyBoolean(), any())).thenReturn(trend("7d"));

        mvc.perform(get("/api/me/alert-trend").param("range", "7d").session(session("AUDIT", null))).andExpect(status().isOk());
        verify(alertTrendService).build("7d", true, null);

        // Kapsamlı müdür (ADMIN + görüş takımı) global DEĞİLDİR
        mvc.perform(get("/api/me/alert-trend").param("range", "7d").session(session("ADMIN", List.of(5L, 6L)))).andExpect(status().isOk());
        verify(alertTrendService).build("7d", false, List.of(5L, 6L));

        // USER + erişim paylaşımı (takım 9'u paylaşımla okur)
        MockHttpSession granted = session("USER", List.of(5L));
        granted.setAttribute(SessionScope.ATTR_GRANT_VIEW, List.of(9L));
        mvc.perform(get("/api/me/alert-trend").param("range", "7d").session(granted)).andExpect(status().isOk());
        verify(alertTrendService).build("7d", false, List.of(5L, 9L));

        // 7/24 operatörü: tüm alarmlar (AdminController.seesAllAlerts ile aynı kapı)
        MockHttpSession operator = session("USER", List.of(3L));
        when(nocCallLog.seesAllAlerts(operator)).thenReturn(true);
        mvc.perform(get("/api/me/alert-trend").param("range", "7d").session(operator)).andExpect(status().isOk());
        verify(alertTrendService).build("7d", true, List.of(3L));
    }

    @Test
    @DisplayName("okuma hatası → 503 DB_UNAVAILABLE, veri yok (asla sıfır kovalar)")
    void readFailureIs503() throws Exception {
        allow();
        when(alertTrendService.build(anyString(), anyBoolean(), any()))
                .thenThrow(new AlertTrendService.UnavailableException(new IllegalStateException("db")));
        mvc.perform(get("/api/me/alert-trend").session(session("ADMIN", null)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"))
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
