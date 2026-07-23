package com.certmonitor.controller;

import com.certmonitor.service.HttpMetricsService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.StormService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Login hero istatistikleri — public erişim + gerçek kaynaklardan değerler. */
@WebMvcTest(PublicStatsController.class)
@org.springframework.test.context.TestPropertySource(properties = "cert.monitor.public-stats.cache-ms=0")
class PublicStatsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean StormService stormService;
    @MockitoBean JdbcTemplate jdbcTemplate;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @Test
    @DisplayName("GET /api/public-stats OTURUMSUZ → 200; izlenen hedef + erişilebilirlik gerçek kaynaklardan")
    void publicStats_noAuth_realValues() throws Exception {
        when(stormService.totalActiveMonitors()).thenReturn(512L);
        when(jdbcTemplate.queryForMap(anyString(), anyString()))
                .thenReturn(Map.of("ups", 9987L, "total", 10000L));

        mvc.perform(get("/api/public-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.monitored_targets").value(512))
                .andExpect(jsonPath("$.data.availability_pct").value(99.9));
    }

    @Test
    @DisplayName("uptime verisi yoksa availability_pct null döner (UI '—' gösterir)")
    void publicStats_noUptimeData_nullPct() throws Exception {
        when(stormService.totalActiveMonitors()).thenReturn(3L);
        when(jdbcTemplate.queryForMap(anyString(), anyString()))
                .thenReturn(Map.of("ups", 0L, "total", 0L));

        mvc.perform(get("/api/public-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monitored_targets").value(3))
                .andExpect(jsonPath("$.data.availability_pct").isEmpty());
    }
}
