package com.sitemonitor.controller;

import com.sitemonitor.service.MonitorSparklineService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Kart mini trendi ucu (2026-09-12): tür doğrulama + takım görünürlüğü (IDOR) + saat tavanı. */
@WebMvcTest(MonitorSparklineController.class)
class MonitorSparklineControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean MonitorSparklineService sparklineService;
    @MockitoBean com.sitemonitor.service.PermissionService permissionService;
    @MockitoBean com.sitemonitor.service.AuditService auditService;
    @MockitoBean com.sitemonitor.service.AppSettingsService appSettings;

    private MockHttpSession teamUser(Long... viewTeams) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", viewTeams[0]);
        s.setAttribute("viewTeamIds", List.of(viewTeams));
        return s;
    }

    @Test
    @DisplayName("bilinmeyen tür → 400")
    void unknownType() throws Exception {
        mvc.perform(get("/api/monitoring/sparklines").param("type", "domain").session(teamUser(5L)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("takım kullanıcısı yalnız görebildiği takımların monitörlerini alır; saat tavanı 24 (kapsamlı, O-7); yanıt id anahtarlı")
    void scopesToVisibleTeams() throws Exception {
        Map<Long, Long> teams = new HashMap<>();
        teams.put(1L, 5L); teams.put(2L, 9L); teams.put(3L, null);
        when(sparklineService.monitorTeams("http")).thenReturn(teams);
        Map<Long, Map<String, Object>> out = new HashMap<>();
        out.put(1L, Map.of("n", 3, "fail", 0, "up_pct", 100.0));
        when(sparklineService.sparklines(eq("http"), anyInt(), eq(Set.of(1L)))).thenReturn(out);

        mvc.perform(get("/api/monitoring/sparklines").param("type", "http").param("hours", "9999").session(teamUser(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.hours").value(24))   // O-7: kapsamlı çağıran 24 saatle sınırlı (global 168)
                .andExpect(jsonPath("$.data.1.n").value(3))
                .andExpect(jsonPath("$.data.2").doesNotExist());

        @SuppressWarnings("unchecked") ArgumentCaptor<Set<Long>> ids = ArgumentCaptor.forClass(Set.class);
        verify(sparklineService).sparklines(eq("http"), eq(24), ids.capture());   // tavan controller'da uygulanır (O-7)
        assertThat(ids.getValue()).containsExactly(1L);   // takım 9 ve takımsız monitör dışarıda
    }
    @Test
    @DisplayName("sla: hedef canlı ayardan (varsayılan 99.9), gün tavanı 30 (kapsamlı, O-7), kapsam aynı")
    void sla() throws Exception {
        Map<Long, Long> teams = new HashMap<>(); teams.put(1L, 5L); teams.put(2L, 9L);
        when(sparklineService.monitorTeams("ping")).thenReturn(teams);
        when(appSettings.getDouble(eq("site.monitor.sla.target-pct"), org.mockito.ArgumentMatchers.anyDouble())).thenReturn(99.5);
        Map<Long, Map<String, Object>> out = new HashMap<>();
        out.put(1L, Map.of("n", 100, "fail", 1, "up_pct", 99.0, "bad_hours", 1));
        when(sparklineService.availability(eq("ping"), anyInt(), eq(Set.of(1L)))).thenReturn(out);
        mvc.perform(get("/api/monitoring/sla").param("type", "ping").param("days", "365").session(teamUser(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target_pct").value(99.5))
                .andExpect(jsonPath("$.days").value(30))   // O-7: kapsamlı çağıran 30 günle sınırlı (global 90)
                .andExpect(jsonPath("$.data.1.up_pct").value(99.0))
                .andExpect(jsonPath("$.data.2").doesNotExist());
    }

    // ── Maliyet sınırı (prod kapısı 2026-09-25, O-7) ───────────────────────────────────────────

    private static MockHttpSession globalAdmin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");   // viewTeamIds yok → global görücü
        return s;
    }

    @Test
    @DisplayName("O-7: global görücü tüm filoyu ORTAK önbellekli yoldan alır (kapsamlı yol + monitör taraması çağrılmaz); tavan 168/90")
    void globalViewer_usesSharedCachedPath() throws Exception {
        Map<Long, Map<String, Object>> all = new HashMap<>();
        all.put(1L, Map.of("n", 3)); all.put(2L, Map.of("n", 4));
        when(sparklineService.sparklinesAll(eq("http"), anyInt())).thenReturn(all);
        when(sparklineService.availabilityAll(eq("http"), anyInt())).thenReturn(all);

        mvc.perform(get("/api/monitoring/sparklines").param("type", "http").param("hours", "9999").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hours").value(168))
                .andExpect(jsonPath("$.data.2.n").value(4));
        mvc.perform(get("/api/monitoring/sla").param("type", "http").param("days", "365").session(globalAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(90));

        verify(sparklineService).sparklinesAll("http", 168);
        verify(sparklineService).availabilityAll("http", 90);
        verify(sparklineService, org.mockito.Mockito.never()).sparklines(org.mockito.ArgumentMatchers.anyString(), anyInt(), org.mockito.ArgumentMatchers.anySet());
        verify(sparklineService, org.mockito.Mockito.never()).monitorTeams(org.mockito.ArgumentMatchers.anyString());
    }
}
