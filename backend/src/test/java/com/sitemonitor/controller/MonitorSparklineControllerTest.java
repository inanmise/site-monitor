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
    @DisplayName("takım kullanıcısı yalnız görebildiği takımların monitörlerini alır; saat tavanı 168; yanıt id anahtarlı")
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
                .andExpect(jsonPath("$.hours").value(168))
                .andExpect(jsonPath("$.data.1.n").value(3))
                .andExpect(jsonPath("$.data.2").doesNotExist());

        @SuppressWarnings("unchecked") ArgumentCaptor<Set<Long>> ids = ArgumentCaptor.forClass(Set.class);
        verify(sparklineService).sparklines(eq("http"), eq(9999), ids.capture());
        assertThat(ids.getValue()).containsExactly(1L);   // takım 9 ve takımsız monitör dışarıda
    }
}
