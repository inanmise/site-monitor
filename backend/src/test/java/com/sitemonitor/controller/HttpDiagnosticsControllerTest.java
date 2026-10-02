package com.sitemonitor.controller;

import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.http.diagnose.HttpDiagnosticsHistory;
import com.sitemonitor.service.http.diagnose.HttpDiagnosticsService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP uçtan uca tanılama uçları (2026-10-02): yetki (diagnostics.run + takımı işletebilme), 404, 429 (kullanıcı VE
 * izleme başına dakikada 6), 400 (bozuk URL), 200 zarfı + run_id + denetim, geçmiş uçlarının kapsamı.
 */
@WebMvcTest(HttpDiagnosticsController.class)
class HttpDiagnosticsControllerTest {

    @Autowired MockMvc mvc;

    // AuthInterceptor bağımlılıkları — @WebMvcTest zorunlu seti
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean HttpMonitorRepository httpMonitorRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean HttpDiagnosticsService diagnosticsService;
    @MockitoBean HttpDiagnosticsHistory diagnosticsHistory;
    @MockitoBean AuditService auditService;

    @BeforeEach
    void defaults() {
        when(permissionService.allows(any(HttpSession.class), eq("diagnostics.run"), eq("execute"))).thenReturn(true);
        when(httpMonitorRepo.findById(36L)).thenReturn(Optional.of(monitor(36L, 5L, "http://site.example/")));
        when(httpMonitorRepo.findById(37L)).thenReturn(Optional.of(monitor(37L, 5L, "http://site.example/b")));
        when(httpMonitorRepo.findById(38L)).thenReturn(Optional.of(monitor(38L, 5L, "not a url")));
        when(httpMonitorRepo.findById(404L)).thenReturn(Optional.empty());
        when(diagnosticsService.diagnose(any(HttpMonitor.class), anyBoolean())).thenAnswer(i -> result());
        when(diagnosticsHistory.save(any(), any(), any(), any(), any(), any())).thenReturn(77L);
    }

    private static HttpMonitor monitor(Long id, Long teamId, String url) {
        HttpMonitor m = new HttpMonitor();
        m.setId(id);
        m.setName("Mon " + id);
        m.setUrl(url);
        m.setTeamId(teamId);
        return m;
    }

    private static Map<String, Object> result() {
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("status", "fail");
        verdict.put("code", "PATH_DIFFERS");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", null);
        data.put("verdict", verdict);
        data.put("paths", List.of());
        return data;
    }

    private static MockHttpSession session(String role, Long teamId, Long userId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "user" + userId);
        s.setAttribute("userId", userId);
        s.setAttribute("systemRole", role);
        if (teamId != null) {
            s.setAttribute("teamId", teamId);
            s.setAttribute("viewTeamIds", List.of(teamId));
            s.setAttribute("memberTeamIds", List.of(teamId));
        }
        return s;
    }

    private static MockHttpSession admin(Long userId) { return session("ADMIN", null, userId); }

    @Test
    @DisplayName("200: global admin → sonuç + run_id, servis compare=true (varsayılan), denetim HTTP_DIAGNOSTICS_RUN")
    void diagnose_ok() throws Exception {
        mvc.perform(post("/api/monitoring/http/36/diagnose").session(admin(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.run_id").value(77))
                .andExpect(jsonPath("$.data.verdict.code").value("PATH_DIFFERS"));
        verify(diagnosticsService).diagnose(any(HttpMonitor.class), eq(true));
        verify(auditService).recordAction(eq("HTTP_DIAGNOSTICS_RUN"), any(HttpSession.class), eq("HTTP_MONITOR"),
                eq("36"), org.mockito.ArgumentMatchers.contains("PATH_DIFFERS"), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("compare=false gövdesi servise geçer")
    void diagnose_compareFalse() throws Exception {
        mvc.perform(post("/api/monitoring/http/36/diagnose").session(admin(2L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"compare\":false}"))
                .andExpect(status().isOk());
        verify(diagnosticsService).diagnose(any(HttpMonitor.class), eq(false));
    }

    @Test
    @DisplayName("takım üyesi USER kendi takımının izlemesinde çalıştırabilir")
    void diagnose_teamMemberAllowed() throws Exception {
        mvc.perform(post("/api/monitoring/http/36/diagnose").session(session("USER", 5L, 3L)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("403: diagnostics.run izni yok → servis çağrılmaz")
    void diagnose_noPermission() throws Exception {
        when(permissionService.allows(any(HttpSession.class), eq("diagnostics.run"), eq("execute"))).thenReturn(false);
        mvc.perform(post("/api/monitoring/http/36/diagnose").session(admin(4L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", containsString("diagnostics.run")));
        verify(diagnosticsService, never()).diagnose(any(), anyBoolean());
    }

    @Test
    @DisplayName("403: başka takımın izlemesi (USER takım 2, izleme takım 5)")
    void diagnose_otherTeam() throws Exception {
        mvc.perform(post("/api/monitoring/http/36/diagnose").session(session("USER", 2L, 5L)))
                .andExpect(status().isForbidden());
        verify(diagnosticsService, never()).diagnose(any(), anyBoolean());
    }

    @Test
    @DisplayName("404: olmayan izleme")
    void diagnose_notFound() throws Exception {
        mvc.perform(post("/api/monitoring/http/404/diagnose").session(admin(6L)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("400: izlemenin URL'si bozuk (İngilizce arayüzde İngilizce ileti)")
    void diagnose_badUrl() throws Exception {
        mvc.perform(post("/api/monitoring/http/38/diagnose").session(admin(7L)).header("X-Lang", "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("invalid")));
    }

    @Test
    @DisplayName("429: aynı izlemede dakikada 7. istek (farklı kullanıcılar) — izleme penceresi")
    void diagnose_rateLimitedPerMonitor() throws Exception {
        for (long u = 100; u < 106; u++) {
            mvc.perform(post("/api/monitoring/http/37/diagnose").session(admin(u))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/monitoring/http/37/diagnose").session(admin(106L)).header("X-Lang", "en"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error", containsString("per minute")));
    }

    @Test
    @DisplayName("429: aynı kullanıcı dakikada 7. istek (farklı izlemeler) — kullanıcı penceresi; reddedilen istek pencereyi tüketmez")
    void diagnose_rateLimitedPerUser() throws Exception {
        for (long id = 200; id < 206; id++) {
            when(httpMonitorRepo.findById(id)).thenReturn(Optional.of(monitor(id, 5L, "http://site.example/" + id)));
            mvc.perform(post("/api/monitoring/http/" + id + "/diagnose").session(admin(900L))).andExpect(status().isOk());
        }
        when(httpMonitorRepo.findById(206L)).thenReturn(Optional.of(monitor(206L, 5L, "http://site.example/206")));
        mvc.perform(post("/api/monitoring/http/206/diagnose").session(admin(900L)))
                .andExpect(status().isTooManyRequests());
        // Reddedilen istek izleme 206'nın penceresini tüketmedi: başka kullanıcı hemen çalıştırabilir.
        mvc.perform(post("/api/monitoring/http/206/diagnose").session(admin(901L))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("geçmiş listesi: aynı kapı; satırlar yardımcıdan")
    void history_list() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 5);
        row.put("verdict_code", "OK");
        when(diagnosticsHistory.list(36L)).thenReturn(List.of(row));
        mvc.perform(get("/api/monitoring/http/36/diagnose/history").session(admin(8L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(5))
                .andExpect(jsonPath("$.data[0].verdict_code").value("OK"));
        mvc.perform(get("/api/monitoring/http/36/diagnose/history").session(session("USER", 2L, 9L)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/monitoring/http/404/diagnose/history").session(admin(8L)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("geçmiş kaydı: bulunursa 200, başka izlemeninse / yoksa 404, kapsam dışı 403")
    void history_run() throws Exception {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("run_id", 9);
        run.put("verdict", Map.of("code", "OK"));
        when(diagnosticsHistory.get(36L, 9L)).thenReturn(run);
        when(diagnosticsHistory.get(eq(36L), eq(10L))).thenReturn(null);
        mvc.perform(get("/api/monitoring/http/36/diagnose/history/9").session(admin(10L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run_id").value(9));
        mvc.perform(get("/api/monitoring/http/36/diagnose/history/10").session(admin(10L)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/monitoring/http/36/diagnose/history/9").session(session("USER", 2L, 11L)))
                .andExpect(status().isForbidden());
        verify(diagnosticsHistory, never()).get(anyLong(), eq(11L));
    }

    @Test
    @DisplayName("oturumsuz istek 401 (AuthInterceptor) — uç PUBLIC değil")
    void requiresSession() throws Exception {
        mvc.perform(post("/api/monitoring/http/36/diagnose")).andExpect(status().isUnauthorized());
        verify(diagnosticsService, never()).diagnose(any(), anyBoolean());
        verify(diagnosticsHistory, never()).list(anyLong());
        verify(auditService, never()).recordAction(eq("HTTP_DIAGNOSTICS_RUN"), any(HttpSession.class), anyString(),
                anyString(), anyString(), any());
    }
}
