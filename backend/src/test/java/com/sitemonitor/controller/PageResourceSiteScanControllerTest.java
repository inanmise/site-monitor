package com.sitemonitor.controller;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.page.scan.PageResourceScanService;
import com.sitemonitor.service.page.scan.budget.ResourceBudgetService;
import com.sitemonitor.service.page.scan.budget.ResourceTrendService;
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
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Çok sayfalı kaynak analizi başlatma gövdesi (prs3 G): {@code {scope:"site", max_pages:N}} → servise sayfa sayısı (ayarın
 * tavanına ve 2'ye kırpılmış), denetimde kapsam; gövdesiz / {@code scope:"page"} → bugünkü tek sayfa çağrısı; bozuk kapsam /
 * sayı → 400 {@code VALIDATION_FAILED} + alan, tarama başlamaz.
 */
@WebMvcTest(PageResourceScanController.class)
class PageResourceSiteScanControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean PageMonitorRepository pageMonitorRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean PageResourceScanService scans;
    @MockitoBean AuditService auditService;
    @MockitoBean ResourceBudgetService budgets;
    @MockitoBean ResourceTrendService trends;

    static Map<String, Object> scan(long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("status", "RUNNING");
        m.put("via", "direct");
        m.put("scope", "site");
        return m;
    }

    static MockHttpSession session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "user2");
        s.setAttribute("displayName", "Kullanıcı 2");
        s.setAttribute("userId", 2L);
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", 5L);
        s.setAttribute("viewTeamIds", java.util.List.of(5L));
        s.setAttribute("memberTeamIds", java.util.List.of(5L));
        return s;
    }

    @BeforeEach
    void defaults() {
        when(permissionService.allows(any(HttpSession.class), eq("monitoring.trigger"), eq("execute"))).thenReturn(true);
        PageMonitor m = new PageMonitor();
        m.setId(61L);
        m.setName("Sayfa 61");
        m.setUrl("https://shop.example.test/");
        m.setTeamId(5L);
        when(pageMonitorRepo.findById(61L)).thenReturn(Optional.of(m));
        when(scans.siteLimits()).thenReturn(new PageResourceScanService.SiteLimits(10, 900));
        PageResourceScanService.Start ok = new PageResourceScanService.Start(PageResourceScanService.Outcome.STARTED, scan(901), 0);
        when(scans.start(any(PageMonitor.class), anyString(), any())).thenReturn(ok);
        when(scans.start(any(PageMonitor.class), anyString(), any(), any())).thenReturn(ok);
    }

    @Test
    @DisplayName("scope=site, max_pages=4 → servise 4 sayfa; denetimde kapsam ve sayfa sayısı")
    void siteScopeStarts() throws Exception {
        mvc.perform(post("/api/monitoring/page/61/resource-scans").session(session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"site\",\"max_pages\":4}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.scope").value("site"));
        verify(scans).start(any(PageMonitor.class), eq("u:2"), eq("Kullanıcı 2"),
                eq(new PageResourceScanService.SiteOptions(4)));
        verify(auditService).recordAction(eq("PAGE_RESOURCE_SCAN_RUN"), any(HttpSession.class), eq("PAGE_MONITOR"), eq("61"),
                argThat((String d) -> d.contains("\"scope\":\"site\"") && d.contains("\"max_pages\":4")), isNull());
    }

    @Test
    @DisplayName("max_pages yok → ayarın değeri; ayarın üstü → ayarın tavanı; 1 → 2; rakam metni kabul")
    void maxPagesDefaultsAndClamp() throws Exception {
        String[] bodies = {"{\"scope\":\"SITE\"}", "{\"scope\":\"site\",\"max_pages\":50}", "{\"scope\":\"site\",\"max_pages\":1}",
                "{\"scope\":\"site\",\"max_pages\":\"3\"}"};
        for (String body : bodies) {
            mvc.perform(post("/api/monitoring/page/61/resource-scans").session(session())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isAccepted());
        }
        verify(scans, org.mockito.Mockito.times(2))
                .start(any(PageMonitor.class), anyString(), any(), eq(new PageResourceScanService.SiteOptions(10)));
        verify(scans).start(any(PageMonitor.class), anyString(), any(), eq(new PageResourceScanService.SiteOptions(2)));
        verify(scans).start(any(PageMonitor.class), anyString(), any(), eq(new PageResourceScanService.SiteOptions(3)));
    }

    @Test
    @DisplayName("gövdesiz ve scope=page → bugünkü tek sayfa çağrısı (çok sayfalı çağrı yok)")
    void pageScopeIsToday() throws Exception {
        mvc.perform(post("/api/monitoring/page/61/resource-scans").session(session())).andExpect(status().isAccepted());
        mvc.perform(post("/api/monitoring/page/61/resource-scans").session(session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"page\",\"max_pages\":5}"))
                .andExpect(status().isAccepted());
        verify(scans, org.mockito.Mockito.times(2)).start(any(PageMonitor.class), anyString(), any());
        verify(scans, never()).start(any(PageMonitor.class), anyString(), any(), any());
    }

    @Test
    @DisplayName("bozuk kapsam / sayfa sayısı → 400 VALIDATION_FAILED + alan; tarama başlamaz, denetim yok")
    void invalidBody() throws Exception {
        mvc.perform(post("/api/monitoring/page/61/resource-scans").session(session())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"crawl\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("scope"));
        for (String bad : new String[]{"2.5", "\"abc\"", "true", "\"-3\"", "\"1234567\""}) {
            mvc.perform(post("/api/monitoring/page/61/resource-scans").session(session())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"site\",\"max_pages\":" + bad + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value("max_pages"));
        }
        verify(scans, never()).start(any(PageMonitor.class), anyString(), any(), any());
        verify(scans, never()).start(any(PageMonitor.class), anyString(), any());
        verify(auditService, never()).recordAction(eq("PAGE_RESOURCE_SCAN_RUN"), any(HttpSession.class), anyString(),
                anyString(), any(), any());
    }

    @Test
    @DisplayName("tam sayı kuralı: tam değerli ondalık kabul, büyük sayı kırpılır, NaN / ondalık / eksi işaretli metin değil")
    void wholeNumber() {
        org.assertj.core.api.Assertions.assertThat(PageResourceScanController.wholeNumber(4.0)).isEqualTo(4);
        org.assertj.core.api.Assertions.assertThat(PageResourceScanController.wholeNumber(1e12)).isEqualTo(1_000_000);
        org.assertj.core.api.Assertions.assertThat(PageResourceScanController.wholeNumber(Double.NaN)).isNull();
        org.assertj.core.api.Assertions.assertThat(PageResourceScanController.wholeNumber(2.5)).isNull();
        org.assertj.core.api.Assertions.assertThat(PageResourceScanController.wholeNumber("-3")).isNull();
        org.assertj.core.api.Assertions.assertThat(PageResourceScanController.wholeNumber(" 7 ")).isEqualTo(7);
        org.assertj.core.api.Assertions.assertThat(PageResourceScanController.wholeNumber("٣")).isNull();
    }
}
