package com.sitemonitor.controller;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.page.scan.PageResourceScanService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kaynak analizi uçları: çalıştırma = "Şimdi kontrol et" kuralı (monitoring.trigger + takımda çalıştırma), okuma = izlemeyi
 * görebilen; göremediği izleme 404; 202 + denetim (URL sorgusuz), 409 çalışan tarama, 429 kişi sınırı / pod dolu, 400 bozuk URL.
 */
@WebMvcTest(PageResourceScanController.class)
class PageResourceScanControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean PageMonitorRepository pageMonitorRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean PageResourceScanService scans;
    @MockitoBean AuditService auditService;

    static Map<String, Object> scan(long id, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("status", status);
        m.put("via", "direct");
        return m;
    }

    @BeforeEach
    void defaults() {
        when(permissionService.allows(any(HttpSession.class), eq("monitoring.trigger"), eq("execute"))).thenReturn(true);
        when(pageMonitorRepo.findById(61L)).thenReturn(Optional.of(page(61L, 5L, "https://shop.example.test/?token=GIZLI99")));
        when(pageMonitorRepo.findById(63L)).thenReturn(Optional.of(page(63L, 5L, "not a url")));
        when(pageMonitorRepo.findById(404L)).thenReturn(Optional.empty());
        when(scans.start(any(PageMonitor.class), anyString(), any()))
                .thenReturn(new PageResourceScanService.Start(PageResourceScanService.Outcome.STARTED, scan(901, "RUNNING"), 0));
        Map<String, Object> list = new LinkedHashMap<>();
        list.put("items", List.of(scan(900, "DONE")));
        list.put("running", null);
        list.put("limits", Map.of("detail_runs", 20));
        when(scans.list(eq(61L), anyInt())).thenReturn(list);
    }

    static PageMonitor page(Long id, Long teamId, String url) {
        PageMonitor m = new PageMonitor();
        m.setId(id);
        m.setName("Sayfa " + id);
        m.setUrl(url);
        m.setTeamId(teamId);
        return m;
    }

    static MockHttpSession session(String role, Long teamId, Long userId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "user" + userId);
        s.setAttribute("displayName", "Kullanıcı " + userId);
        s.setAttribute("userId", userId);
        s.setAttribute("systemRole", role);
        if (teamId != null) {
            s.setAttribute("teamId", teamId);
            s.setAttribute("viewTeamIds", List.of(teamId));
            s.setAttribute("memberTeamIds", List.of(teamId));
        }
        return s;
    }

    static MockHttpSession admin() { return session("ADMIN", null, 1L); }

    @Test
    @DisplayName("202: tarama başlar, çalıştıranın adı servise gider, denetim PAGE_RESOURCE_SCAN_RUN (URL sorgusu yazılmaz)")
    void startOk() throws Exception {
        mvc.perform(post("/api/monitoring/page/61/resource-scans").session(session("USER", 5L, 2L)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(901))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
        verify(scans).start(any(PageMonitor.class), eq("u:2"), eq("Kullanıcı 2"));
        verify(auditService).recordAction(eq("PAGE_RESOURCE_SCAN_RUN"), any(HttpSession.class), eq("PAGE_MONITOR"), eq("61"),
                argThat((String d) -> d.contains("scan_id") && d.contains("shop.example.test") && !d.contains("GIZLI99")),
                isNull());
    }

    @Test
    @DisplayName("409: izlemenin çalışan taraması — kod + o taramanın özeti; denetim yazılmaz")
    void startRunning() throws Exception {
        when(scans.start(any(PageMonitor.class), anyString(), any()))
                .thenReturn(new PageResourceScanService.Start(PageResourceScanService.Outcome.RUNNING, scan(777, "RUNNING"), 0));
        mvc.perform(post("/api/monitoring/page/61/resource-scans").session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESOURCE_SCAN_RUNNING"))
                .andExpect(jsonPath("$.data.id").value(777));
        verify(auditService, never()).recordAction(eq("PAGE_RESOURCE_SCAN_RUN"), any(HttpSession.class), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("429: kişi sınırı RATE_LIMITED + Retry-After; pod dolu RESOURCE_SCAN_BUSY")
    void startLimited() throws Exception {
        when(scans.start(any(PageMonitor.class), anyString(), any()))
                .thenReturn(new PageResourceScanService.Start(PageResourceScanService.Outcome.RATE, null, 42));
        mvc.perform(post("/api/monitoring/page/61/resource-scans").session(admin()))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retry_after_seconds").value(42));
        when(scans.start(any(PageMonitor.class), anyString(), any()))
                .thenReturn(new PageResourceScanService.Start(PageResourceScanService.Outcome.BUSY, null, 15));
        mvc.perform(post("/api/monitoring/page/61/resource-scans").session(admin()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RESOURCE_SCAN_BUSY"));
    }

    @Test
    @DisplayName("başka takımın izlemesi 404 (başlatma, geçmiş, tek tarama); izin yoksa 403; bozuk URL 400 — servis çağrılmaz")
    void gates() throws Exception {
        MockHttpSession other = session("USER", 9L, 3L);
        mvc.perform(post("/api/monitoring/page/61/resource-scans").session(other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/monitoring/page/61/resource-scans").session(other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/monitoring/page/61/resource-scans/900").session(other)).andExpect(status().isNotFound());
        mvc.perform(post("/api/monitoring/page/404/resource-scans").session(admin())).andExpect(status().isNotFound());
        mvc.perform(post("/api/monitoring/page/63/resource-scans").session(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        doThrow(new SecurityException("yok")).when(permissionService)
                .require(any(HttpSession.class), eq("monitoring.trigger"), eq("execute"));
        mvc.perform(post("/api/monitoring/page/61/resource-scans").session(admin())).andExpect(status().isForbidden());
        verify(scans, never()).start(any(PageMonitor.class), anyString(), any());
        verify(scans, never()).list(anyLong(), anyInt());
        verify(scans, never()).one(anyLong(), anyLong());
    }

    @Test
    @DisplayName("geçmiş: can_run = izin + takımda çalıştırma; AUDIT görür ama çalıştıramaz")
    void listCanRun() throws Exception {
        mvc.perform(get("/api/monitoring/page/61/resource-scans").session(session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(900))
                .andExpect(jsonPath("$.data.limits.detail_runs").value(20))
                .andExpect(jsonPath("$.data.can_run").value(true));
        mvc.perform(get("/api/monitoring/page/61/resource-scans").session(session("AUDIT", null, 4L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_run").value(false));
        when(permissionService.allows(any(HttpSession.class), eq("monitoring.trigger"), eq("execute"))).thenReturn(false);
        mvc.perform(get("/api/monitoring/page/61/resource-scans").session(admin()))
                .andExpect(jsonPath("$.data.can_run").value(false));
    }

    @Test
    @DisplayName("tek tarama: 200 veri; bulunamazsa 404 RESOURCE_SCAN_NOT_FOUND")
    void one() throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scan", scan(900, "DONE"));
        data.put("analysis", Map.of("scores", List.of()));
        data.put("items", List.of());
        data.put("items_kept", true);
        when(scans.one(900L, 61L)).thenReturn(data);
        when(scans.one(999L, 61L)).thenReturn(null);   // Mockito Map için boş harita döndürürdü
        mvc.perform(get("/api/monitoring/page/61/resource-scans/900").session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scan.status").value("DONE"))
                .andExpect(jsonPath("$.data.items_kept").value(true));
        mvc.perform(get("/api/monitoring/page/61/resource-scans/999").session(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_SCAN_NOT_FOUND"));
    }
}
