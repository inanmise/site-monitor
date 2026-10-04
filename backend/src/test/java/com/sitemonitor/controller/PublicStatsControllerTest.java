package com.sitemonitor.controller;

import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.PresenceService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.StormService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Giriş sayfası istatistikleri (PUBLIC) — oturumsuz erişim, kullanım şeridi alanları (2026-10-04), rakam başına
 * hata yalıtımı, ayar kapalıyken yalnız eski iki rakam, yanıtta ad/ayrıntı olmaması ve no-store başlığı.
 */
@WebMvcTest(PublicStatsController.class)
@org.springframework.test.context.TestPropertySource(properties = "site.monitor.public-stats.cache-ms=0")
class PublicStatsControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean JdbcTemplate jdbcTemplate;
    @MockitoBean MonitoringOverviewService overviewService;
    @MockitoBean AlertEventRepository alertEventRepo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean AppUserRepository userRepo;
    @MockitoBean AuditLogRepository auditLogRepo;
    @MockitoBean PresenceService presenceService;
    @MockitoBean AppSettingsService appSettings;
    // Fırtına paydası artık bu uçta KULLANILMAZ (giriş sayfası 621 ↔ pano 688 ayrışması) — mock, çağrılmadığı doğrulansın diye.
    @MockitoBean StormService stormService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;
    // AuthInterceptor (@Component) her dilimde kuruluyor; sessiz reauth'a denetim kaydı
    // yazdığından AuditService'e de ihtiyaç duyar.
    @MockitoBean com.sitemonitor.service.AuditService auditService;

    private static Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total", 720L); s.put("active", 702L); s.put("healthy", 688L);
        s.put("down", 9L); s.put("stale", 3L); s.put("unknown", 2L); s.put("paused", 18L);
        s.put("checks_window", 123456L); s.put("failed_window", 321L);
        s.put("window_hours", 24); s.put("generated_at", "2026-10-04T08:00:00");
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(String body) {
        return (Map<String, Object>) new tools.jackson.databind.ObjectMapper().readValue(body, Map.class).get("data");
    }

    @BeforeEach
    void defaults() {
        when(appSettings.getBoolean(eq(PublicStatsController.USAGE_KEY), anyBoolean())).thenReturn(true);
        when(overviewService.orgSummary()).thenReturn(summary());
        when(jdbcTemplate.queryForMap(org.mockito.ArgumentMatchers.contains("uptime_checks"), anyString()))
                .thenReturn(Map.of("ups", 9987L, "total", 10000L));
        // 2026-10-05: sertifika taramaları (24 sa) ve sertifika izlemesi (aktif envanter × son durum)
        when(jdbcTemplate.queryForMap(org.mockito.ArgumentMatchers.contains("certificate_checks"), anyString()))
                .thenReturn(Map.of("total", 3456L, "failed", 12L));
        when(jdbcTemplate.queryForMap(org.mockito.ArgumentMatchers.contains("certificate_inventory")))
                .thenReturn(Map.of("total", 312L, "ok", 298L, "expiring", 9L, "expired", 2L));
        when(alertEventRepo.countCreatedSince(anyString())).thenReturn(41L);
        when(teamRepo.countByActiveTrue()).thenReturn(12L);
        when(userRepo.countByActiveTrue()).thenReturn(243L);
        when(auditLogRepo.countDistinctLoginActorsSince(anyString())).thenReturn(57L);
        // Varlık özeti takım ADLARI taşır — public uca yalnız toplam geçmeli.
        when(presenceService.online()).thenReturn(Map.of("total", 14L, "no_team", 1L,
                "teams", List.of(Map.of("team_id", 3L, "team_name", "Takim A", "count", 13L))));
    }

    @Test
    @DisplayName("OTURUMSUZ → 200; kullanım şeridi alanları gerçek kaynaklardan; monitored_targets = pano 'aktif'; fırtına paydası çağrılmaz")
    void publicStats_noAuth_usageFields() throws Exception {
        mvc.perform(get("/api/public-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.healthy_monitors").value(688))
                .andExpect(jsonPath("$.data.active_monitors").value(702))
                .andExpect(jsonPath("$.data.total_monitors").value(720))
                .andExpect(jsonPath("$.data.monitored_targets").value(702))
                // koşum = izleme koşumu (123456) + sertifika taraması (3456); başarısız = 321 + 12
                .andExpect(jsonPath("$.data.checks_24h").value(126912))
                .andExpect(jsonPath("$.data.monitor_checks_24h").value(123456))
                .andExpect(jsonPath("$.data.cert_checks_24h").value(3456))
                .andExpect(jsonPath("$.data.cert_failed_checks_24h").value(12))
                .andExpect(jsonPath("$.data.certificates").value(312))
                .andExpect(jsonPath("$.data.certificates_ok").value(298))
                .andExpect(jsonPath("$.data.certificates_expiring_30d").value(9))
                .andExpect(jsonPath("$.data.certificates_expired").value(2))
                .andExpect(jsonPath("$.data.failed_checks_24h").value(333))
                .andExpect(jsonPath("$.data.alerts_24h").value(41))
                .andExpect(jsonPath("$.data.teams").value(12))
                .andExpect(jsonPath("$.data.active_users").value(243))
                .andExpect(jsonPath("$.data.online_users").value(14))
                .andExpect(jsonPath("$.data.logins_24h").value(57))
                .andExpect(jsonPath("$.data.availability_pct").value(99.9));
        verify(stormService, never()).totalActiveMonitors();
    }

    @Test
    @DisplayName("Yanıt YALNIZ toplam sayılar: anahtar kümesi sabit; takım adı, alan adı, zaman damgası, kırılım yok")
    void publicStats_onlyAggregateCounts() throws Exception {
        String body = mvc.perform(get("/api/public-stats")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> data = dataOf(body);
        assertThat(data.keySet()).containsExactlyInAnyOrder("monitored_targets", "availability_pct", "healthy_monitors",
                "active_monitors", "total_monitors", "checks_24h", "failed_checks_24h", "alerts_24h", "teams",
                "active_users", "online_users", "logins_24h", "monitor_checks_24h", "cert_checks_24h", "cert_failed_checks_24h",
                "certificates", "certificates_ok", "certificates_expiring_30d", "certificates_expired");
        java.util.Set<String> expected = new java.util.LinkedHashSet<>(PublicStatsController.LEGACY_FIELDS);
        expected.addAll(PublicStatsController.USAGE_FIELDS);
        assertThat(data.keySet()).as("yanıt = eski alanlar + USAGE_FIELDS (arayüz USAGE_KEYS ile aynı liste)")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(data.values()).allSatisfy(v -> assertThat(v).isInstanceOf(Number.class));
        assertThat(body).doesNotContain("Takim A").doesNotContain("team_name").doesNotContain("generated_at")
                .doesNotContain("no_team");
    }

    @Test
    @DisplayName("Rakam başına yalıtım: bir kaynak hata verirse YALNIZ o rakam null; diğerleri gelir")
    void publicStats_failurePerFigure() throws Exception {
        when(alertEventRepo.countCreatedSince(anyString())).thenThrow(new RuntimeException("db down"));
        when(presenceService.online()).thenThrow(new RuntimeException("boom"));
        when(jdbcTemplate.queryForMap(anyString(), anyString())).thenThrow(new RuntimeException("no table"));
        when(userRepo.countByActiveTrue()).thenThrow(new RuntimeException("users"));

        mvc.perform(get("/api/public-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alerts_24h").isEmpty())
                .andExpect(jsonPath("$.data.online_users").isEmpty())
                .andExpect(jsonPath("$.data.active_users").isEmpty())
                .andExpect(jsonPath("$.data.availability_pct").isEmpty())
                .andExpect(jsonPath("$.data.healthy_monitors").value(688))
                .andExpect(jsonPath("$.data.teams").value(12))
                .andExpect(jsonPath("$.data.logins_24h").value(57));
    }

    @Test
    @DisplayName("İzleme özeti hata verirse izleme rakamları (ve eski monitored_targets) null; takım/çevrimiçi/alarm gelir")
    void publicStats_overviewFails_monitorFiguresNull() throws Exception {
        when(overviewService.orgSummary()).thenThrow(new RuntimeException("overview"));

        mvc.perform(get("/api/public-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.healthy_monitors").isEmpty())
                .andExpect(jsonPath("$.data.active_monitors").isEmpty())
                .andExpect(jsonPath("$.data.checks_24h").isEmpty())
                .andExpect(jsonPath("$.data.monitored_targets").isEmpty())
                .andExpect(jsonPath("$.data.alerts_24h").value(41))
                .andExpect(jsonPath("$.data.online_users").value(14));
    }

    @Test
    @DisplayName("Ayar KAPALI → yalnız eski iki rakam (monitored_targets + availability_pct); kullanım kaynakları sorgulanmaz")
    void publicStats_usageDisabled_legacyOnly() throws Exception {
        when(appSettings.getBoolean(eq(PublicStatsController.USAGE_KEY), anyBoolean())).thenReturn(false);

        String body = mvc.perform(get("/api/public-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monitored_targets").value(702))
                .andExpect(jsonPath("$.data.availability_pct").value(99.9))
                .andExpect(jsonPath("$.data.healthy_monitors").doesNotExist())
                .andExpect(jsonPath("$.data.online_users").doesNotExist())
                .andExpect(jsonPath("$.data.teams").doesNotExist())
                .andExpect(jsonPath("$.data.active_users").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> data = dataOf(body);
        assertThat(data.keySet()).containsExactly("monitored_targets", "availability_pct");
        verify(alertEventRepo, never()).countCreatedSince(anyString());
        verify(presenceService, never()).online();
        verify(auditLogRepo, never()).countDistinctLoginActorsSince(anyString());
        verify(teamRepo, never()).countByActiveTrue();
        verify(userRepo, never()).countByActiveTrue();
    }

    @Test
    @DisplayName("uptime verisi yoksa availability_pct null döner (UI '—' gösterir)")
    void publicStats_noUptimeData_nullPct() throws Exception {
        when(jdbcTemplate.queryForMap(anyString(), anyString())).thenReturn(Map.of("ups", 0L, "total", 0L));

        mvc.perform(get("/api/public-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.monitored_targets").value(702))
                .andExpect(jsonPath("$.data.availability_pct").isEmpty());
    }

    @Test
    @DisplayName("HTTP yanıtı no-store (paylaşımlı önbellek YOK — yük hafifletmesi sunucu belleğinde)")
    void publicStats_noStoreHeader() throws Exception {
        mvc.perform(get("/api/public-stats"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("public"))));
    }
}
