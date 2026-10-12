package com.sitemonitor.controller;

import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link ActivityController} — TAKIM izolasyonu (IDOR engeli) güvenlik testleri. Kapsam SESSION'dan
 * türetilir; istemciden gelen teamId parametresi yok sayılır. Başka takımın kaydına detay ile erişim 404.
 */
@WebMvcTest(ActivityController.class)
class ActivityControllerTest {

    @Autowired MockMvc mvc;

    // Web slice ortak bean'leri (AuthInterceptor) + controller bağımlılığı.
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    // AuthInterceptor (@Component) her dilimde kuruluyor; sessiz reauth'a denetim kaydı
    // yazdığından AuditService'e de ihtiyaç duyar.
    @MockitoBean com.sitemonitor.service.AuditService auditService;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean ActivityLogRepository repo;

    private static MockHttpSession scopedUser(Long... teams) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(teams)));
        return s;
    }

    private static MockHttpSession globalViewer() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "AUDIT");   // viewTeamIds yok → global görücü
        return s;
    }

    private static ActivityLog row(Long id, Long teamId) {
        ActivityLog a = new ActivityLog();
        a.setId(id); a.setTeamId(teamId); a.setMonitorType("HTTP"); a.setAction("SCHEDULED_CHECK");
        a.setActivityTime("2026-07-28T10:00:00");
        return a;
    }

    @SuppressWarnings("unchecked")
    private void stubFind() {
        when(repo.findFiltered(anyBoolean(), anyList(), anyBoolean(), anyList(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
    }

    @Test
    @DisplayName("list: kapsam SESSION'dan türetilir; istemci teamId'si yok sayılır (scoped=true, scope=[kullanıcının takımı])")
    @SuppressWarnings("unchecked")
    void list_scopedToSessionTeams_ignoresClientTeamId() throws Exception {
        stubFind();

        // Kullanıcı team=1; URL'de kötü niyetli ?teamId=999 → ETKİSİZ (controller böyle bir param okumaz).
        mvc.perform(get("/api/activity?teamId=999").session(scopedUser(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<List<Long>> scope = ArgumentCaptor.forClass(List.class);
        verify(repo).findFiltered(eq(true), scope.capture(), anyBoolean(), anyList(), any(), any(), any(), any(), any(), any());
        assertThat(scope.getValue()).containsExactly(1L);   // yalnız kendi takımı
    }

    @Test
    @DisplayName("list: global görücü (AUDIT) → scoped=false (tüm takımlar)")
    void list_globalViewer_unscoped() throws Exception {
        stubFind();

        mvc.perform(get("/api/activity").session(globalViewer())).andExpect(status().isOk());

        verify(repo).findFiltered(eq(false), anyList(), anyBoolean(), anyList(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("list: kapsamsız kullanıcı (viewTeamIds boş) → hiç kayıt, repo çağrılmaz")
    void list_noTeams_returnsEmptyWithoutQuery() throws Exception {
        mvc.perform(get("/api/activity").session(scopedUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        verify(repo, never()).findFiltered(anyBoolean(), anyList(), anyBoolean(), anyList(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("detail: BAŞKA takımın kaydına erişim → 404 (varlık sızıntısı yok)")
    void detail_crossTeam_notFound() throws Exception {
        when(repo.findById(5L)).thenReturn(Optional.of(row(5L, 2L)));   // kayıt team=2

        mvc.perform(get("/api/activity/5").session(scopedUser(1L)))     // kullanıcı team=1
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("detail: KENDİ takımının kaydı → 200")
    void detail_ownTeam_ok() throws Exception {
        when(repo.findById(7L)).thenReturn(Optional.of(row(7L, 1L)));   // kayıt team=1

        mvc.perform(get("/api/activity/7").session(scopedUser(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(7));
    }

    @Test
    @DisplayName("2026-10-11 summary: tür sayıları (tür süzgeci hariç, durum dahil) + saatlik seri (sıfır dolgulu, TIMEOUT hata)")
    void summary_typeCountsAndHourlySeries() throws Exception {
        when(repo.countByStatusFiltered(anyBoolean(), anyList(), anyBoolean(), anyList(), any(), any(), any(), any()))
                .thenReturn(java.util.Collections.singletonList(new Object[]{"SUCCESS", 3L}));
        when(repo.countByTypeFiltered(anyBoolean(), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new Object[]{"HTTP", 5L}, new Object[]{"CERT", 2L}));
        when(repo.countByHourAndStatus(anyBoolean(), anyList(), anyBoolean(), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(new Object[]{"2026-10-11T08", "SUCCESS", 4L}, new Object[]{"2026-10-11T08", "TIMEOUT", 1L},
                        new Object[]{"2026-10-11T09", "ERROR", 2L}));
        mvc.perform(get("/api/activity/summary?type=HTTP&status=error&from=2026-10-11T00:00:00&to=2026-10-11T23:59:59")
                        .session(scopedUser(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.type_counts.HTTP").value(5))
                .andExpect(jsonPath("$.type_counts.CERT").value(2))
                .andExpect(jsonPath("$.series.bucket").value("hour"))
                .andExpect(jsonPath("$.series.points.length()").value(24))
                .andExpect(jsonPath("$.series.points[8].t").value("2026-10-11T08:00:00Z"))
                .andExpect(jsonPath("$.series.points[8].success").value(4))
                .andExpect(jsonPath("$.series.points[8].error").value(1))
                .andExpect(jsonPath("$.series.points[9].error").value(2))
                .andExpect(jsonPath("$.series.points[0].success").value(0));
        // tür sayımına durum (büyük harf) geçer, tür süzgeci geçmez
        verify(repo).countByTypeFiltered(eq(true), anyList(), isNull(), eq("ERROR"), eq("2026-10-11T00:00:00"),
                eq("2026-10-11T23:59:59"), isNull());
    }

    @Test
    @DisplayName("2026-10-11 series: 48 saatten uzun pencere İstanbul gününe toplanır (UTC 22:00 = ertesi gün)")
    void series_dailyIstanbulBuckets() {
        java.time.Instant from = java.time.Instant.parse("2026-10-04T21:00:00Z");   // İstanbul 5 Ekim 00:00
        java.time.Instant to = java.time.Instant.parse("2026-10-11T20:59:59Z");     // İstanbul 11 Ekim 23:59
        Map<String, Object> s = ActivityController.series(List.of(
                new Object[]{"2026-10-10T22", "WARNING", 3L},     // İstanbul 11 Ekim 01:00
                new Object[]{"2026-10-10T20", "SUCCESS", 2L},     // İstanbul 10 Ekim 23:00
                new Object[]{"bozuk", "SUCCESS", 9L}), from, to);
        assertThat(s.get("bucket")).isEqualTo("day");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pts = (List<Map<String, Object>>) s.get("points");
        assertThat(pts).hasSize(7);
        assertThat(pts.get(0).get("t")).isEqualTo("2026-10-05");
        Map<String, Object> d11 = pts.stream().filter(p -> "2026-10-11".equals(p.get("t"))).findFirst().orElseThrow();
        Map<String, Object> d10 = pts.stream().filter(p -> "2026-10-10".equals(p.get("t"))).findFirst().orElseThrow();
        assertThat(d11.get("warning")).isEqualTo(3L);
        assertThat(d10.get("success")).isEqualTo(2L);
    }

    @Test
    @DisplayName("2026-10-11 summary: aralıksız istek son 30 güne sınırlanır (seri sorgusu from ile kesilir)")
    void summary_seriesDefaultWindow() throws Exception {
        mvc.perform(get("/api/activity/summary").session(globalViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.bucket").value("day"))
                .andExpect(jsonPath("$.type_counts").isMap());
        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        verify(repo).countByHourAndStatus(eq(false), anyList(), eq(false), anyList(), isNull(), from.capture(), isNull(), isNull());
        java.time.Instant f = java.time.LocalDateTime.parse(from.getValue()).toInstant(java.time.ZoneOffset.UTC);
        assertThat(f).isBetween(java.time.Instant.now().minus(java.time.Duration.ofDays(30)).minusSeconds(60),
                java.time.Instant.now().minus(java.time.Duration.ofDays(30)).plusSeconds(60));
    }

    @Test
    @DisplayName("detail: bilinmeyen id → 404")
    void detail_unknown_notFound() throws Exception {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/activity/999").session(globalViewer()))
                .andExpect(status().isNotFound());
    }
}
