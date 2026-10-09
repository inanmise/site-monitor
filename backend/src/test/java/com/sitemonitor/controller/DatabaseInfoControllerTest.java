package com.sitemonitor.controller;

import com.sitemonitor.service.DatabaseHealthService;
import com.sitemonitor.service.DatabaseInfoService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DatabaseInfoController.class)
class DatabaseInfoControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean DatabaseInfoService service;
    @MockitoBean PermissionService permissionService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;
    // AuthInterceptor (@Component) her dilimde kuruluyor; sessiz reauth'a denetim kaydı
    // yazdığından AuditService'e de ihtiyaç duyar.
    @MockitoBean com.sitemonitor.service.AuditService auditService;
    // 2026-10-09: sayfa sağlık özetini aynı yanıtta okur (dış izleme ucuyla aynı sonuç).
    @MockitoBean DatabaseHealthService databaseHealth;

    @BeforeEach
    void setUp() {
        // Bootstrap admin ("admin") require'ı atlar; non-bootstrap için matris izni reddini simüle et → 403.
        org.mockito.Mockito.doThrow(new SecurityException("no perm")).when(permissionService)
                .require(org.mockito.ArgumentMatchers.any(jakarta.servlet.http.HttpSession.class),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("GET info oturumsuz → 401")
    void info_unauthenticated_401() throws Exception {
        mvc.perform(get("/api/admin/database/info"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET info admin olmayan → 403")
    void info_nonAdmin_403() throws Exception {
        mvc.perform(get("/api/admin/database/info").session(session("sre1")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET info bootstrap admin → 200 + data (db adı/kullanıcı/havuz)")
    void info_admin_200() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("database", "certmonitor");
        data.put("user", "certuser");
        data.put("version", "PostgreSQL 16.2");
        data.put("pool", Map.of("name", "SiteMonitorPool", "active", 1, "max_size", 10));
        when(service.getInfo()).thenReturn(data);

        mvc.perform(get("/api/admin/database/info").session(session("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.database").value("certmonitor"))
                .andExpect(jsonPath("$.data.user").value("certuser"))
                .andExpect(jsonPath("$.data.pool.max_size").value(10));
    }

    @Test
    @DisplayName("GET info → data.health dış izleme ucunun gövdesini taşır (durum, denetimler; gizli bilgi yok)")
    void info_includesHealthBlock() throws Exception {
        when(service.getInfo()).thenReturn(new LinkedHashMap<>(Map.of("database", "appdb")));
        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("connection", Map.of("status", "UP", "acquire_ms", 2));
        checks.put("query", Map.of("status", "DEGRADED", "latency_ms", 1500));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "DEGRADED");
        body.put("component", "database");
        body.put("checked_at", "2026-10-09T09:00:00Z");
        body.put("checks", checks);
        when(databaseHealth.current()).thenReturn(new DatabaseHealthService.Snapshot(body, "DEGRADED", 1L));

        mvc.perform(get("/api/admin/database/info").session(session("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.database").value("appdb"))
                .andExpect(jsonPath("$.data.health.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.health.checks.query.latency_ms").value(1500))
                .andExpect(jsonPath("$.data.health.checks.connection.acquire_ms").value(2));
    }

    @Test
    @DisplayName("GET info → sağlık özeti alınamazsa blok yok, sayfa yine 200 (istisna metni sızmaz)")
    void info_healthFailure_stillOk() throws Exception {
        when(service.getInfo()).thenReturn(new LinkedHashMap<>(Map.of("database", "appdb")));
        when(databaseHealth.current()).thenThrow(new IllegalStateException("gizli-ayrinti host=db.example.com"));

        mvc.perform(get("/api/admin/database/info").session(session("admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.database").value("appdb"))
                .andExpect(jsonPath("$.data.health").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("gizli-ayrinti"))));
    }

    private MockHttpSession session(String username) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", username);
        s.setAttribute("systemRole", "admin".equals(username) ? "ADMIN" : "USER");
        s.setAttribute("bootstrapAdmin", "admin".equals(username));   // settings gate bayrağı (literal username yerine)
        return s;
    }
}
