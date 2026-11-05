package com.sitemonitor.controller;

import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.service.activity.ActivityExportService;
import com.sitemonitor.service.activity.ActivityFilter;
import com.sitemonitor.service.activity.ActivityQueryService;
import com.sitemonitor.service.activity.ActivityRelatedService;
import com.sitemonitor.service.activity.ActivityTimelineService;
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
    // be2 (2026-11-05, aktivite zenginleştirmesi D + E + F)
    @MockitoBean ActivityQueryService queries;
    @MockitoBean ActivityTimelineService timelines;
    @MockitoBean ActivityExportService exports;
    @MockitoBean ActivityRelatedService relatedSvc;
    @MockitoBean com.sitemonitor.repository.TeamRepository teamRepo;

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

    private void stubFind() {
        when(queries.list(any(), anyInt(), anyInt())).thenReturn(new ActivityQueryService.PageResult(List.of(), 0));
    }

    private ActivityFilter capturedListFilter() {
        ArgumentCaptor<ActivityFilter> f = ArgumentCaptor.forClass(ActivityFilter.class);
        verify(queries).list(f.capture(), anyInt(), anyInt());
        return f.getValue();
    }

    @Test
    @DisplayName("list: kapsam SESSION'dan türetilir; istemci teamId'si yalnız DARALTIR (kapsamla kesişir, genişletmez)")
    void list_scopedToSessionTeams_teamIdOnlyNarrows() throws Exception {
        stubFind();

        // Kullanıcı team=1; URL'de ?teamId=999 → kapsam [1] KALIR, 999 ek süzgeç (kesişim boş — başka takım görünmez).
        mvc.perform(get("/api/activity?teamId=999").session(scopedUser(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ActivityFilter f = capturedListFilter();
        assertThat(f.scoped()).isTrue();
        assertThat(f.scope()).containsExactly(1L);   // yalnız kendi takımı
        assertThat(f.teamId()).isEqualTo(999L);
        assertThat(f.where().where()).contains("team_id IN (?)").contains("team_id = ?");
    }

    @Test
    @DisplayName("list: global görücü (AUDIT) → scoped=false (tüm takımlar)")
    void list_globalViewer_unscoped() throws Exception {
        stubFind();

        mvc.perform(get("/api/activity").session(globalViewer())).andExpect(status().isOk());

        assertThat(capturedListFilter().scoped()).isFalse();
    }

    @Test
    @DisplayName("list: kapsamsız kullanıcı (viewTeamIds boş) → hiç kayıt, sorgu yok")
    void list_noTeams_returnsEmptyWithoutQuery() throws Exception {
        mvc.perform(get("/api/activity").session(scopedUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        verify(queries, never()).list(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("2026-11-05 list: yeni süzgeçler (durum CSV, izleme, kişi, grup, neden) süzgece geçer; satırlar listeyle aynı biçim")
    void list_newFilters() throws Exception {
        when(queries.list(any(), anyInt(), anyInt())).thenReturn(new ActivityQueryService.PageResult(List.of(row(9L, 1L)), 1));
        mvc.perform(get("/api/activity?status=ERROR,TIMEOUT&monitor=HTTP:42&actor=Ahmet&group=check,alarm&reason=DNS_NXDOMAIN,class:NETWORK&page=0&size=10")
                        .session(scopedUser(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data[0].id").value(9))
                .andExpect(jsonPath("$.size").value(10));
        ActivityFilter f = capturedListFilter();
        assertThat(f.statuses()).containsExactly("ERROR", "TIMEOUT");
        assertThat(f.monitorType()).isEqualTo("HTTP");
        assertThat(f.monitorId()).isEqualTo(42L);
        assertThat(f.actor()).isEqualTo("ahmet");
        assertThat(f.groups()).containsExactly("check", "alarm");
        assertThat(f.reasons()).containsExactly("DNS_NXDOMAIN");
        assertThat(f.reasonClasses()).containsExactly("NETWORK");
    }

    @Test
    @DisplayName("Sözleşme D1: liste satırları team_name taşır — sayfa başına TEK takım okuması; takımsız satırda null")
    void list_teamNamesOneReadPerPage() throws Exception {
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(1L);
        team.setName("Kart Ödeme Ekibi");
        when(teamRepo.findAllById(any())).thenReturn(List.of(team));
        when(queries.list(any(), anyInt(), anyInt())).thenReturn(new ActivityQueryService.PageResult(
                List.of(row(9L, 1L), row(10L, 1L), row(11L, null)), 3));
        mvc.perform(get("/api/activity").session(globalViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].team_name").value("Kart Ödeme Ekibi"))
                .andExpect(jsonPath("$.data[1].team_name").value("Kart Ödeme Ekibi"))
                .andExpect(jsonPath("$.data[2].team_name").doesNotExist());
        verify(teamRepo, times(1)).findAllById(any());
    }

    @Test
    @DisplayName("2026-11-05 list: ters aralık → 400 VALIDATION_FAILED alan from; tanınmayan grup → alan group; sorgu yok")
    void list_validation400() throws Exception {
        mvc.perform(get("/api/activity?from=2026-11-05T12:00:00&to=2026-11-05T10:00:00Z").session(scopedUser(1L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("from"));
        mvc.perform(get("/api/activity/summary?group=foo").session(scopedUser(1L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("group"));
        verifyNoInteractions(queries);
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
    @DisplayName("2026-10-11 summary: tür sayıları (tür süzgeci hariç, durum dahil) + saatlik seri (sıfır dolgulu, TIMEOUT hata); 2026-11-05 grup sayıları + timeout")
    void summary_typeCountsAndHourlySeries() throws Exception {
        Map<String, Long> groups = new java.util.LinkedHashMap<>();
        groups.put("check", 5L);
        groups.put("alarm", 1L);
        groups.put("notification", 0L);
        groups.put("change", 0L);
        groups.put("other", 0L);
        when(queries.summary(any(), any())).thenReturn(new ActivityQueryService.SummaryData(
                Map.of("SUCCESS", 3L, "TIMEOUT", 1L), "2026-10-11T09:30:00",
                new java.util.TreeMap<>(Map.of("HTTP", 5L, "CERT", 2L)), groups,
                List.of(new Object[]{"2026-10-11T08", "SUCCESS", 4L}, new Object[]{"2026-10-11T08", "TIMEOUT", 1L},
                        new Object[]{"2026-10-11T09", "ERROR", 2L})));
        mvc.perform(get("/api/activity/summary?type=HTTP&status=error&from=2026-10-11T00:00:00&to=2026-10-11T23:59:59")
                        .session(scopedUser(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.error").value(1))
                .andExpect(jsonPath("$.timeout").value(1))
                .andExpect(jsonPath("$.last_activity").value("2026-10-11T09:30:00"))
                .andExpect(jsonPath("$.type_counts.HTTP").value(5))
                .andExpect(jsonPath("$.type_counts.CERT").value(2))
                .andExpect(jsonPath("$.group_counts.check").value(5))
                .andExpect(jsonPath("$.group_counts.alarm").value(1))
                .andExpect(jsonPath("$.series.bucket").value("hour"))
                .andExpect(jsonPath("$.series.points.length()").value(24))
                .andExpect(jsonPath("$.series.points[8].t").value("2026-10-11T08:00:00Z"))
                .andExpect(jsonPath("$.series.points[8].success").value(4))
                .andExpect(jsonPath("$.series.points[8].error").value(1))
                .andExpect(jsonPath("$.series.points[9].error").value(2))
                .andExpect(jsonPath("$.series.points[0].success").value(0));
        // süzgece durum (büyük harf), tür ve aralık geçer; seri başlangıcı = from
        ArgumentCaptor<ActivityFilter> f = ArgumentCaptor.forClass(ActivityFilter.class);
        verify(queries).summary(f.capture(), eq("2026-10-11T00:00:00"));
        assertThat(f.getValue().statuses()).containsExactly("ERROR");
        assertThat(f.getValue().types()).containsExactly("HTTP");
        assertThat(f.getValue().to()).isEqualTo("2026-10-11T23:59:59");
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
        when(queries.summary(any(), any())).thenReturn(new ActivityQueryService.SummaryData(Map.of(), null,
                new java.util.TreeMap<>(), new java.util.LinkedHashMap<>(), List.of()));
        mvc.perform(get("/api/activity/summary").session(globalViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.bucket").value("day"))
                .andExpect(jsonPath("$.type_counts").isMap())
                .andExpect(jsonPath("$.group_counts").isMap());
        ArgumentCaptor<String> from = ArgumentCaptor.forClass(String.class);
        verify(queries).summary(any(), from.capture());
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

    // ── be2 uçları (2026-11-05): zaman çizelgesi, kök neden, dışa aktarım — kapı ve devretme ──

    @Test
    @DisplayName("timeline: servis oturumun kapsamı ve canViewMonitoring yüklemiyle çağrılır; görülemeyen → 404")
    void timeline_delegatesWithSessionGate() throws Exception {
        when(timelines.timeline(any(), any(), any(), anyBoolean(), anyList(), any(), any())).thenAnswer(inv -> {
            java.util.function.Predicate<Long> canView = inv.getArgument(5);
            if (!canView.test(1L) || canView.test(2L)) throw new java.util.NoSuchElementException("İzleme bulunamadı.");
            return Map.of("checks", 3);
        });
        mvc.perform(get("/api/activity/timeline?monitor=HTTP:5&from=2026-11-01").session(scopedUser(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.checks").value(3));
        verify(timelines).timeline(eq("HTTP:5"), eq("2026-11-01"), isNull(), eq(true), eq(List.of(1L)), any(), any());
        mvc.perform(get("/api/activity/timeline?monitor=HTTP:5").session(scopedUser(2L)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("related: gövde + öğeler listeyle aynı satır biçiminde (toDto)")
    void related_mapsItems() throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("window_s", 120);
        body.put("suspects", List.of(Map.of("code", "SINGLE", "params", Map.of())));
        when(relatedSvc.related(eq(7L), isNull(), anyBoolean(), anyList(), any()))
                .thenReturn(new ActivityRelatedService.Result(body, List.of(row(8L, 1L))));
        mvc.perform(get("/api/activity/7/related").session(scopedUser(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.window_s").value(120))
                .andExpect(jsonPath("$.data.suspects[0].code").value("SINGLE"))
                .andExpect(jsonPath("$.data.items[0].id").value(8))
                .andExpect(jsonPath("$.data.items[0].monitor_type").value("HTTP"));
    }

    @Test
    @DisplayName("export: listeyle aynı süzgeç + kapsam; kapsamsız oturum servise 'boş kapsam' olarak geçer")
    void export_delegatesSameFilter() throws Exception {
        when(exports.export(any(), any(), anyBoolean(), any(), any(), any())).thenReturn(null);
        mvc.perform(get("/api/activity/export?format=json&status=ERROR,TIMEOUT").session(scopedUser(1L)))
                .andExpect(status().isOk());
        ArgumentCaptor<ActivityFilter> f = ArgumentCaptor.forClass(ActivityFilter.class);
        verify(exports).export(f.capture(), eq("json"), eq(false), any(), any(), any());
        assertThat(f.getValue().scope()).containsExactly(1L);
        assertThat(f.getValue().statuses()).containsExactly("ERROR", "TIMEOUT");
        mvc.perform(get("/api/activity/export").session(scopedUser())).andExpect(status().isOk());
        verify(exports).export(any(), isNull(), eq(true), any(), any(), any());
    }
}
