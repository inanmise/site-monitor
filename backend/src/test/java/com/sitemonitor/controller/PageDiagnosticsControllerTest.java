package com.sitemonitor.controller;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.page.diagnose.PageDiagnosticsHistory;
import com.sitemonitor.service.page.diagnose.PageDiagnosticsHistory.Kind;
import com.sitemonitor.service.page.diagnose.PageDiagnosticsService;
import com.sitemonitor.service.page.diagnose.PageSpeedDiagnosticsService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sayfa Bütünlüğü + Sayfa Hızı uçtan uca tanılama uçları (2026-10-05) — keyword/HTTP tanılama uçlarının aynası: yetki
 * (diagnostics.run + takımı işletebilme), 404, 400 (bozuk URL), 429 (kullanıcı VE izleme başına dakikada 6; aynı anda 4),
 * 200 zarfı + run_id + denetim {@code PAGE_DIAGNOSTICS_RUN} / {@code PAGESPEED_DIAGNOSTICS_RUN} (URL sorgusu yazılmaz),
 * geçmiş uçlarının kapsamı ve tür yalıtımı, oturumsuz 401, izleme deposuna YAZMA yok.
 */
@WebMvcTest(PageDiagnosticsController.class)
class PageDiagnosticsControllerTest {

    @Autowired MockMvc mvc;
    @Autowired PageDiagnosticsController controller;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean PageMonitorRepository pageMonitorRepo;
    @MockitoBean PageSpeedMonitorRepository pageSpeedMonitorRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean PageDiagnosticsService pageDiagnostics;
    @MockitoBean PageSpeedDiagnosticsService pageSpeedDiagnostics;
    @MockitoBean PageDiagnosticsHistory history;
    @MockitoBean AuditService auditService;

    @BeforeEach
    void defaults() {
        when(permissionService.allows(any(HttpSession.class), eq("diagnostics.run"), eq("execute"))).thenReturn(true);
        when(pageMonitorRepo.findById(61L)).thenReturn(Optional.of(page(61L, 5L, "https://shop.example.test/?token=GIZLI99")));
        when(pageMonitorRepo.findById(62L)).thenReturn(Optional.of(page(62L, 5L, "https://shop.example.test/b")));
        when(pageMonitorRepo.findById(63L)).thenReturn(Optional.of(page(63L, 5L, "not a url")));
        when(pageMonitorRepo.findById(404L)).thenReturn(Optional.empty());
        when(pageSpeedMonitorRepo.findById(71L)).thenReturn(Optional.of(speed(71L, 5L, "https://shop.example.test/")));
        when(pageSpeedMonitorRepo.findById(404L)).thenReturn(Optional.empty());
        when(pageDiagnostics.diagnose(any(PageMonitor.class), anyBoolean())).thenAnswer(i -> result("page", "RESOURCES_BROKEN"));
        when(pageSpeedDiagnostics.diagnose(any(PageSpeedMonitor.class), anyBoolean())).thenAnswer(i -> result("pagespeed", "THRESHOLD_BREACH"));
        when(history.save(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(88L);
    }

    private static PageMonitor page(Long id, Long teamId, String url) {
        PageMonitor m = new PageMonitor();
        m.setId(id);
        m.setName("Sayfa " + id);
        m.setUrl(url);
        m.setTeamId(teamId);
        return m;
    }

    private static PageSpeedMonitor speed(Long id, Long teamId, String url) {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setId(id);
        m.setName("Hız " + id);
        m.setUrl(url);
        m.setTeamId(teamId);
        m.setCustomHeadersEnc("ENC:gizli-baslik");
        return m;
    }

    private static Map<String, Object> result(String kind, String code) {
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("status", "fail");
        verdict.put("code", code);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", null);
        data.put("kind", kind);
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
    @DisplayName("Sayfa Bütünlüğü 200: sonuç + run_id, compare=true varsayılan, denetim PAGE_DIAGNOSTICS_RUN (URL sorgusu YAZILMAZ)")
    void page_ok() throws Exception {
        mvc.perform(post("/api/monitoring/page/61/diagnose").session(admin(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.run_id").value(88))
                .andExpect(jsonPath("$.data.verdict.code").value("RESOURCES_BROKEN"));
        verify(pageDiagnostics).diagnose(any(PageMonitor.class), eq(true));
        verify(history).save(eq(Kind.PAGE), eq(61L), anyString(), any(), eq("user1"), eq(1L), any(), any());
        verify(auditService).recordAction(eq("PAGE_DIAGNOSTICS_RUN"), any(HttpSession.class), eq("PAGE_MONITOR"),
                eq("61"), org.mockito.ArgumentMatchers.argThat((String d) -> d != null && d.contains("RESOURCES_BROKEN")
                        && !d.contains("GIZLI99")), org.mockito.ArgumentMatchers.isNull());
        verify(pageSpeedDiagnostics, never()).diagnose(any(), anyBoolean());
    }

    @Test
    @DisplayName("Sayfa Hızı 200: compare=false servise geçer; denetim PAGESPEED_DIAGNOSTICS_RUN; şifreli başlık denetime yazılmaz")
    void pageSpeed_ok_compareFalse() throws Exception {
        mvc.perform(post("/api/monitoring/pagespeed/71/diagnose").session(session("USER", 5L, 3L))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"compare\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("pagespeed"));
        verify(pageSpeedDiagnostics).diagnose(any(PageSpeedMonitor.class), eq(false));
        verify(history).save(eq(Kind.PAGESPEED), eq(71L), anyString(), any(), any(), any(), any(), any());
        verify(auditService).recordAction(eq("PAGESPEED_DIAGNOSTICS_RUN"), any(HttpSession.class), eq("PAGESPEED_MONITOR"),
                eq("71"), org.mockito.ArgumentMatchers.argThat((String d) -> d != null && d.contains("THRESHOLD_BREACH")
                        && !d.contains("gizli-baslik")), org.mockito.ArgumentMatchers.isNull());
        verify(pageDiagnostics, never()).diagnose(any(), anyBoolean());
    }

    @Test
    @DisplayName("403: diagnostics.run izni yok / başka takımın izlemesi → servis çağrılmaz")
    void forbidden() throws Exception {
        mvc.perform(post("/api/monitoring/page/61/diagnose").session(session("USER", 2L, 5L)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/monitoring/pagespeed/71/diagnose").session(session("USER", 2L, 5L)))
                .andExpect(status().isForbidden());
        when(permissionService.allows(any(HttpSession.class), eq("diagnostics.run"), eq("execute"))).thenReturn(false);
        mvc.perform(post("/api/monitoring/page/61/diagnose").session(admin(4L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", containsString("diagnostics.run")));
        mvc.perform(get("/api/monitoring/pagespeed/71/diagnose/history").session(admin(4L)))
                .andExpect(status().isForbidden());
        verify(pageDiagnostics, never()).diagnose(any(), anyBoolean());
        verify(pageSpeedDiagnostics, never()).diagnose(any(), anyBoolean());
        verify(history, never()).list(any(), anyLong());
    }

    @Test
    @DisplayName("404 olmayan izleme (iki tür); 400 bozuk URL (İngilizce arayüzde İngilizce ileti)")
    void notFound_badUrl() throws Exception {
        mvc.perform(post("/api/monitoring/page/404/diagnose").session(admin(6L))).andExpect(status().isNotFound());
        mvc.perform(post("/api/monitoring/pagespeed/404/diagnose").session(admin(6L)).header("X-Lang", "en"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", containsString("Page speed monitor not found")));
        mvc.perform(post("/api/monitoring/page/63/diagnose").session(admin(7L)).header("X-Lang", "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("invalid")));
        verify(pageDiagnostics, never()).diagnose(any(), anyBoolean());
    }

    @Test
    @DisplayName("429: aynı izlemede dakikada 7. istek (farklı kullanıcılar)")
    void rateLimitedPerMonitor() throws Exception {
        for (long u = 100; u < 106; u++) {
            mvc.perform(post("/api/monitoring/page/62/diagnose").session(admin(u))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/monitoring/page/62/diagnose").session(admin(106L)).header("X-Lang", "en"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", containsString("per minute")));
        // izleme penceresi türe özgü: aynı numaralı Sayfa Hızı izlemesinin hakkı ayrı
        when(pageSpeedMonitorRepo.findById(62L)).thenReturn(Optional.of(speed(62L, 5L, "https://shop.example.test/")));
        mvc.perform(post("/api/monitoring/pagespeed/62/diagnose").session(admin(107L))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("429: aynı kullanıcı dakikada 7. istek (iki tür karışık); reddedilen istek izlemenin penceresini tüketmez")
    void rateLimitedPerUser() throws Exception {
        for (long id = 200; id < 206; id++) {
            when(pageMonitorRepo.findById(id)).thenReturn(Optional.of(page(id, 5L, "https://shop.example.test/" + id)));
            mvc.perform(post("/api/monitoring/page/" + id + "/diagnose").session(admin(900L))).andExpect(status().isOk());
        }
        mvc.perform(post("/api/monitoring/pagespeed/71/diagnose").session(admin(900L)))
                .andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/monitoring/pagespeed/71/diagnose").session(admin(901L))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("429: aynı anda en çok 4 tanılama — izin yoksa servis çağrılmaz, izin geri verilir")
    void inFlightLimit() throws Exception {
        Semaphore inFlight = (Semaphore) ReflectionTestUtils.getField(controller, "inFlight");
        int drained = inFlight.drainPermits();
        try {
            mvc.perform(post("/api/monitoring/page/61/diagnose").session(admin(30L)).header("X-Lang", "en"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.error", containsString("Too many diagnostics")));
            verify(pageDiagnostics, never()).diagnose(any(), anyBoolean());
        } finally {
            inFlight.release(drained);
        }
        mvc.perform(post("/api/monitoring/page/61/diagnose").session(admin(30L))).andExpect(status().isOk());
        assertPermits(inFlight);
    }

    private static void assertPermits(Semaphore s) {
        org.assertj.core.api.Assertions.assertThat(s.availablePermits()).isEqualTo(PageDiagnosticsController.MAX_CONCURRENT);
    }

    @Test
    @DisplayName("geçmiş listesi + kaydı: aynı kapı; türe göre ayrı (Kind); başka izlemenin kaydı 404; kapsam dışı 403")
    void history() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 5);
        row.put("verdict_code", "PAGE_OK");
        when(history.list(Kind.PAGE, 61L)).thenReturn(List.of(row));
        mvc.perform(get("/api/monitoring/page/61/diagnose/history").session(admin(8L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].verdict_code").value("PAGE_OK"));
        verify(history).list(Kind.PAGE, 61L);
        mvc.perform(get("/api/monitoring/page/61/diagnose/history").session(session("USER", 2L, 9L)))
                .andExpect(status().isForbidden());
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("run_id", 9);
        when(history.get(Kind.PAGESPEED, 71L, 9L)).thenReturn(run);
        when(history.get(Kind.PAGE, 61L, 9L)).thenReturn(null);   // Sayfa Hızı kaydı Sayfa Bütünlüğü ucundan açılmaz
        mvc.perform(get("/api/monitoring/pagespeed/71/diagnose/history/9").session(admin(10L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.run_id").value(9));
        mvc.perform(get("/api/monitoring/page/61/diagnose/history/9").session(admin(10L)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/monitoring/pagespeed/71/diagnose/history/9").session(session("USER", 2L, 11L)))
                .andExpect(status().isForbidden());
        verify(history).get(Kind.PAGESPEED, 71L, 9L);
        verify(history).get(Kind.PAGE, 61L, 9L);
    }

    @Test
    @DisplayName("YAN ETKİSİZ: tanılama izleme deposuna yalnız OKUR (findById), hiçbir şey yazmaz")
    void noRepositoryWrites() throws Exception {
        mvc.perform(post("/api/monitoring/page/61/diagnose").session(admin(40L))).andExpect(status().isOk());
        mvc.perform(post("/api/monitoring/pagespeed/71/diagnose").session(admin(40L))).andExpect(status().isOk());
        verify(pageMonitorRepo).findById(61L);
        verify(pageSpeedMonitorRepo).findById(71L);
        verifyNoMoreInteractions(pageMonitorRepo, pageSpeedMonitorRepo);
    }

    @Test
    @DisplayName("oturumsuz istek 401 (AuthInterceptor) — uç PUBLIC değil")
    void requiresSession() throws Exception {
        mvc.perform(post("/api/monitoring/page/61/diagnose")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/monitoring/pagespeed/71/diagnose")).andExpect(status().isUnauthorized());
        verify(pageDiagnostics, never()).diagnose(any(), anyBoolean());
        verify(pageSpeedDiagnostics, never()).diagnose(any(), anyBoolean());
        verify(auditService, never()).recordAction(anyString(), any(HttpSession.class), anyString(), anyString(), anyString(), any());
    }
}
