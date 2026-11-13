package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.dbgrowth.DbGrowthService;
import com.sitemonitor.service.dbgrowth.DbHealthAnalysisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Veritabanı büyümesi uçları: görünüm izni, kapasite (yalnız global yönetici + doğrulama + denetim), tahmin, analiz sınırları. */
@WebMvcTest(DbGrowthController.class)
class DbGrowthControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean DbGrowthService growth;
    @MockitoBean DbHealthAnalysisService analysis;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean AppSettingsService settingsService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private static MockHttpSession session(String user, String role, boolean global) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", user);
        s.setAttribute("systemRole", role);
        if (!global) s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(5L)));
        return s;
    }

    @Test
    @DisplayName("görünüm: system_health.read gerekir; global yönetici kapasite / uygula bayrakları alır, kapsamlı müdür almaz")
    void overview() throws Exception {
        when(growth.overview(anyInt(), anyBoolean())).thenReturn(Map.of("supported", true));
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("settings.retention"), eq("edit"))).thenReturn(true);
        mvc.perform(get("/api/admin/system/db-growth?range=30").session(session("admin", "ADMIN", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.supported").value(true))
                .andExpect(jsonPath("$.data.can_edit_capacity").value(true))
                .andExpect(jsonPath("$.data.can_apply").value(true))
                .andExpect(jsonPath("$.data.can_open_retention").value(true));
        verify(growth).overview(30, false);
        mvc.perform(get("/api/admin/system/db-growth").session(session("mudur", "ADMIN", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_edit_capacity").value(false))
                .andExpect(jsonPath("$.data.can_apply").value(false));
        doThrow(new SecurityException("no")).when(permissionService).require(any(jakarta.servlet.http.HttpSession.class),
                eq("system_health.read"), eq("view"));
        mvc.perform(get("/api/admin/system/db-growth").session(session("u", "USER", false))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("kapasite: kapsamlı müdür 403; geçersiz değer 400 alan hatası; değişince AppSettingsService.save + denetim; aynı değer yazılmaz")
    void capacity() throws Exception {
        mvc.perform(put("/api/admin/system/db-growth/capacity").session(session("mudur", "ADMIN", false))
                .contentType(MediaType.APPLICATION_JSON).content("{\"capacity_gb\":100}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/system/db-growth/capacity").session(session("admin", "ADMIN", true))
                .contentType(MediaType.APPLICATION_JSON).content("{\"capacity_gb\":-5}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("capacity_gb"));
        mvc.perform(put("/api/admin/system/db-growth/capacity").session(session("admin", "ADMIN", true))
                .contentType(MediaType.APPLICATION_JSON).content("{\"capacity_gb\":\"abc\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("capacity_gb"));
        when(growth.capacityGb()).thenReturn(0);
        mvc.perform(put("/api/admin/system/db-growth/capacity").session(session("admin", "ADMIN", true))
                .contentType(MediaType.APPLICATION_JSON).content("{\"capacity_gb\":250}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.capacity_gb").value(250));
        verify(settingsService).save(eq(Map.of("values", Map.of(DbGrowthService.CAPACITY_KEY, "250"))), eq("admin"));
        verify(auditService).recordAction(eq("DB_CAPACITY_SETTINGS_SAVE"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("SETTINGS"), eq(DbGrowthService.CAPACITY_KEY), anyString(), anyString());
        verify(growth).invalidate();
        when(growth.capacityGb()).thenReturn(250);
        mvc.perform(put("/api/admin/system/db-growth/capacity").session(session("admin", "ADMIN", true))
                .contentType(MediaType.APPLICATION_JSON).content("{\"capacity_gb\":250}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.changed").value(false));
    }

    @Test
    @DisplayName("tahmin: yalnız global yönetici; dakikada 6'dan fazlası 429 + Retry-After")
    void whatIf() throws Exception {
        when(growth.whatIf(anyString(), anyInt())).thenReturn(Map.of("rows", 10));
        mvc.perform(get("/api/admin/system/db-growth/what-if?policy=series-port&days=90").session(session("mudur", "ADMIN", false)))
                .andExpect(status().isForbidden());
        MockHttpSession admin = session("admin2", "ADMIN", true);
        for (int i = 0; i < 6; i++) {
            mvc.perform(get("/api/admin/system/db-growth/what-if?policy=series-port&days=90").session(admin))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.rows").value(10));
        }
        mvc.perform(get("/api/admin/system/db-growth/what-if?policy=series-port&days=90").session(admin))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("analiz: system_health.actions/execute; sürerken 409 DB_ANALYSIS_BUSY; dakikada 3'ten fazlası 429; başarıda denetim")
    void analysisRun() throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("status", "OK");
        r.put("duration_ms", 12);
        r.put("findings", List.of(Map.of("code", "DEAD_TUPLES")));
        when(analysis.run(anyString())).thenReturn(Optional.of(r));
        MockHttpSession s = session("ops", "ADMIN", true);
        mvc.perform(post("/api/admin/system/db-growth/analysis").session(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OK"));
        verify(auditService).recordAction(eq("DB_HEALTH_ANALYSIS_RUN"), any(jakarta.servlet.http.HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class),
                eq("SYSTEM"), eq("db-growth"), anyString());
        when(analysis.run(anyString())).thenReturn(Optional.empty());
        mvc.perform(post("/api/admin/system/db-growth/analysis").session(s)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DB_ANALYSIS_BUSY"));
        mvc.perform(post("/api/admin/system/db-growth/analysis").session(s)).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/system/db-growth/analysis").session(s)).andExpect(status().isTooManyRequests());
        doThrow(new SecurityException("no")).when(permissionService).require(any(jakarta.servlet.http.HttpSession.class),
                eq("system_health.actions"), eq("execute"));
        mvc.perform(post("/api/admin/system/db-growth/analysis").session(session("u", "USER", false))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("son analiz: okuma izniyle; hiç yoksa null")
    void latest() throws Exception {
        when(analysis.latest()).thenReturn(null);
        mvc.perform(get("/api/admin/system/db-growth/analysis/latest").session(session("u", "USER", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.running").value(false));
        verify(analysis, never()).run(anyString());
    }
}
