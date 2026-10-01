package com.sitemonitor.controller;

import com.sitemonitor.service.InboxService;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.OpenAlertsSummaryService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.TodayPanelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Dakikada bir yoklanan iki ucun sunucu-içi bellek kablolaması (2026-10-01, performans H1/M6):
 * <ul>
 *   <li>{@code GET /api/me/open-alerts}: {@code ?fresh=1} belleği atlar (alarm eylemi sonrası), düz yoklama bellekten;
 *       görünmeyen kullanıcı bellek yoluna hiç girmez; dönen paylaşılan harita DEĞİŞTİRİLMEZ ({@code visible} kopyaya).</li>
 *   <li>{@code GET /api/monitoring/overview}: bellek anahtarı canView yüklemini TAM belirler — global görüntüleyici
 *       {@code "ALL"}, kapsamlı kullanıcı sıralı görüş takımları.</li>
 * </ul>
 */
class PolledEndpointsMemoWiringTest {

    private static MockHttpSession session(String role, List<Long> viewTeamIds) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("systemRole", role);
        if (viewTeamIds != null) s.setAttribute("viewTeamIds", viewTeamIds);
        return s;
    }

    @Test
    @DisplayName("open-alerts: fresh=1 → cached(..., true); parametresiz → cached(..., false); paylaşılan sonuç değiştirilmez")
    void openAlerts_freshParam() {
        PermissionService perms = mock(PermissionService.class);
        OpenAlertsSummaryService summary = mock(OpenAlertsSummaryService.class);
        TodayPanelController c = new TodayPanelController(mock(TodayPanelService.class), mock(InboxService.class), perms, summary);
        MockHttpSession s = session("USER", List.of(14L, 3L));
        when(perms.allows(any(jakarta.servlet.http.HttpSession.class), eq("alerts.read"), eq("view"))).thenReturn(true);
        Map<String, Object> shared = new LinkedHashMap<>(Map.of("total", 2L, "sampled", false, "tabs", Map.of()));
        when(summary.cached(anyBoolean(), any(), anyBoolean())).thenReturn(shared);

        ResponseEntity<Map<String, Object>> r1 = c.openAlerts(s, null);
        verify(summary).cached(false, List.of(14L, 3L), false);
        c.openAlerts(s, "1");
        verify(summary).cached(false, List.of(14L, 3L), true);
        c.openAlerts(s, "true");
        verify(summary, times(2)).cached(false, List.of(14L, 3L), true);
        c.openAlerts(s, "0");
        verify(summary, times(2)).cached(false, List.of(14L, 3L), false);

        @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) r1.getBody().get("data");
        assertThat(data).containsEntry("visible", true).containsEntry("total", 2L);
        assertThat(shared).doesNotContainKey("visible");   // bellekteki paylaşılan harita kirlenmedi
    }

    @Test
    @DisplayName("open-alerts: alerts.read yoksa bellek yolu kullanılmaz, boş özet + visible=false")
    void openAlerts_notVisible() {
        PermissionService perms = mock(PermissionService.class);
        OpenAlertsSummaryService summary = mock(OpenAlertsSummaryService.class);
        TodayPanelController c = new TodayPanelController(mock(TodayPanelService.class), mock(InboxService.class), perms, summary);
        when(perms.allows(any(jakarta.servlet.http.HttpSession.class), anyString(), anyString())).thenReturn(false);
        when(summary.build(false, List.of())).thenReturn(new LinkedHashMap<>(Map.of("total", 0L)));
        @SuppressWarnings("unchecked") Map<String, Object> data =
                (Map<String, Object>) c.openAlerts(session("USER", List.of(1L)), "1").getBody().get("data");
        assertThat(data).containsEntry("visible", false);
        verify(summary, never()).cached(anyBoolean(), any(), anyBoolean());
    }

    @Test
    @DisplayName("overview: global görüntüleyici → anahtar ALL + seesAll=true; kapsamlı kullanıcı → sıralı takım anahtarı + seesAll=false")
    void overview_scopeKey() {
        MonitoringOverviewService svc = mock(MonitoringOverviewService.class);
        MonitoringOverviewController c = new MonitoringOverviewController(svc, mock(PermissionService.class));
        when(svc.build(anyString(), any(), anyBoolean(), anyInt())).thenReturn(Map.of());

        c.overview(24, session("ADMIN", null));
        verify(svc).build(eq("ALL"), any(), eq(true), eq(24));

        c.overview(48, session("USER", List.of(14L, 3L)));
        verify(svc).build(eq("T:3,14"), any(), eq(false), eq(48));

        // Kapsamlı müdür (ADMIN + viewTeamIds) global DEĞİL → takım anahtarı
        c.overview(24, session("ADMIN", List.of(7L)));
        verify(svc).build(eq("T:7"), any(), eq(false), eq(24));
        verify(svc, never()).build(any(java.util.function.Predicate.class), anyBoolean(), anyInt());
    }
}
