package com.sitemonitor.controller;

import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.keyword.diagnose.KeywordDiagnosticsHistory;
import com.sitemonitor.service.keyword.diagnose.KeywordDiagnosticsService;
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
 * Keyword uçtan uca tanılama uçları (2026-10-04) — HTTP tanılama uçlarının aynası: yetki (diagnostics.run + takımı
 * işletebilme), 404, 400 (bozuk URL), 429 (kullanıcı VE izleme başına dakikada 6), 200 zarfı + run_id + denetim
 * {@code KEYWORD_DIAGNOSTICS_RUN} (anahtar kelime denetime yazılmaz), geçmiş uçlarının kapsamı, oturumsuz 401.
 */
@WebMvcTest(KeywordDiagnosticsController.class)
class KeywordDiagnosticsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean KeywordMonitorRepository keywordMonitorRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean KeywordDiagnosticsService diagnosticsService;
    @MockitoBean KeywordDiagnosticsHistory diagnosticsHistory;
    @MockitoBean AuditService auditService;

    @BeforeEach
    void defaults() {
        when(permissionService.allows(any(HttpSession.class), eq("diagnostics.run"), eq("execute"))).thenReturn(true);
        when(keywordMonitorRepo.findById(41L)).thenReturn(Optional.of(monitor(41L, 5L, "https://site.example.com/")));
        when(keywordMonitorRepo.findById(42L)).thenReturn(Optional.of(monitor(42L, 5L, "https://site.example.com/b")));
        when(keywordMonitorRepo.findById(43L)).thenReturn(Optional.of(monitor(43L, 5L, "not a url")));
        when(keywordMonitorRepo.findById(404L)).thenReturn(Optional.empty());
        when(diagnosticsService.diagnose(any(KeywordMonitor.class), anyBoolean())).thenAnswer(i -> result());
        when(diagnosticsHistory.save(any(), any(), any(), any(), any(), any())).thenReturn(88L);
    }

    private static KeywordMonitor monitor(Long id, Long teamId, String url) {
        KeywordMonitor m = new KeywordMonitor();
        m.setId(id);
        m.setName("Kelime " + id);
        m.setUrl(url);
        m.setKeyword("GizliKampanyaMetni");
        m.setTeamId(teamId);
        return m;
    }

    private static Map<String, Object> result() {
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("status", "fail");
        verdict.put("code", "KEYWORD_NOT_FOUND");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", null);
        data.put("kind", "keyword");
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
    @DisplayName("200: sonuç + run_id, compare=true varsayılan, denetim KEYWORD_DIAGNOSTICS_RUN (anahtar kelime YAZILMAZ)")
    void diagnose_ok() throws Exception {
        mvc.perform(post("/api/monitoring/keyword/41/diagnose").session(admin(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.run_id").value(88))
                .andExpect(jsonPath("$.data.verdict.code").value("KEYWORD_NOT_FOUND"));
        verify(diagnosticsService).diagnose(any(KeywordMonitor.class), eq(true));
        verify(auditService).recordAction(eq("KEYWORD_DIAGNOSTICS_RUN"), any(HttpSession.class), eq("KEYWORD_MONITOR"),
                eq("41"), org.mockito.ArgumentMatchers.argThat((String d) -> d != null && d.contains("KEYWORD_NOT_FOUND")
                        && !d.contains("GizliKampanyaMetni")), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("compare=false gövdesi servise geçer; takım üyesi kendi takımında çalıştırır")
    void diagnose_compareFalse_teamMember() throws Exception {
        mvc.perform(post("/api/monitoring/keyword/41/diagnose").session(session("USER", 5L, 3L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"compare\":false}"))
                .andExpect(status().isOk());
        verify(diagnosticsService).diagnose(any(KeywordMonitor.class), eq(false));
    }

    @Test
    @DisplayName("403: diagnostics.run izni yok / başka takımın izlemesi → servis çağrılmaz")
    void diagnose_forbidden() throws Exception {
        mvc.perform(post("/api/monitoring/keyword/41/diagnose").session(session("USER", 2L, 5L)))
                .andExpect(status().isForbidden());
        when(permissionService.allows(any(HttpSession.class), eq("diagnostics.run"), eq("execute"))).thenReturn(false);
        mvc.perform(post("/api/monitoring/keyword/41/diagnose").session(admin(4L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", containsString("diagnostics.run")));
        verify(diagnosticsService, never()).diagnose(any(), anyBoolean());
    }

    @Test
    @DisplayName("404 olmayan izleme; 400 bozuk URL (İngilizce arayüzde İngilizce ileti)")
    void diagnose_notFound_badUrl() throws Exception {
        mvc.perform(post("/api/monitoring/keyword/404/diagnose").session(admin(6L)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/monitoring/keyword/43/diagnose").session(admin(7L)).header("X-Lang", "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("invalid")));
        verify(diagnosticsService, never()).diagnose(any(), anyBoolean());
    }

    @Test
    @DisplayName("429: aynı izlemede dakikada 7. istek (farklı kullanıcılar)")
    void diagnose_rateLimitedPerMonitor() throws Exception {
        for (long u = 100; u < 106; u++) {
            mvc.perform(post("/api/monitoring/keyword/42/diagnose").session(admin(u))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/monitoring/keyword/42/diagnose").session(admin(106L)).header("X-Lang", "en"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", containsString("per minute")));
    }

    @Test
    @DisplayName("429: aynı kullanıcı dakikada 7. istek (farklı izlemeler); reddedilen istek izlemenin penceresini tüketmez")
    void diagnose_rateLimitedPerUser() throws Exception {
        for (long id = 200; id < 206; id++) {
            when(keywordMonitorRepo.findById(id)).thenReturn(Optional.of(monitor(id, 5L, "https://site.example.com/" + id)));
            mvc.perform(post("/api/monitoring/keyword/" + id + "/diagnose").session(admin(900L))).andExpect(status().isOk());
        }
        when(keywordMonitorRepo.findById(206L)).thenReturn(Optional.of(monitor(206L, 5L, "https://site.example.com/206")));
        mvc.perform(post("/api/monitoring/keyword/206/diagnose").session(admin(900L)))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/monitoring/keyword/206/diagnose").session(admin(901L))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("geçmiş listesi + kaydı: aynı kapı; başka izlemenin kaydı 404; kapsam dışı 403")
    void history() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 5);
        row.put("verdict_code", "KEYWORD_OK");
        when(diagnosticsHistory.list(41L)).thenReturn(List.of(row));
        mvc.perform(get("/api/monitoring/keyword/41/diagnose/history").session(admin(8L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].verdict_code").value("KEYWORD_OK"));
        mvc.perform(get("/api/monitoring/keyword/41/diagnose/history").session(session("USER", 2L, 9L)))
                .andExpect(status().isForbidden());
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("run_id", 9);
        when(diagnosticsHistory.get(41L, 9L)).thenReturn(run);
        when(diagnosticsHistory.get(eq(41L), eq(10L))).thenReturn(null);
        mvc.perform(get("/api/monitoring/keyword/41/diagnose/history/9").session(admin(10L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.run_id").value(9));
        mvc.perform(get("/api/monitoring/keyword/41/diagnose/history/10").session(admin(10L)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/monitoring/keyword/41/diagnose/history/9").session(session("USER", 2L, 11L)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("oturumsuz istek 401 (AuthInterceptor) — uç PUBLIC değil")
    void requiresSession() throws Exception {
        mvc.perform(post("/api/monitoring/keyword/41/diagnose")).andExpect(status().isUnauthorized());
        verify(diagnosticsService, never()).diagnose(any(), anyBoolean());
        verify(diagnosticsHistory, never()).list(anyLong());
        verify(auditService, never()).recordAction(eq("KEYWORD_DIAGNOSTICS_RUN"), any(HttpSession.class), anyString(),
                anyString(), anyString(), any());
    }
}
