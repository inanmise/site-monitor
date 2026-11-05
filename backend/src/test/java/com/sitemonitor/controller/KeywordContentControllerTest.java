package com.sitemonitor.controller;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.KeywordChangeBaseline;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.KeywordResult;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.KeywordContentQueryRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.keyword.content.KeywordChangeBaselineService;
import com.sitemonitor.service.keyword.content.KeywordChangeDetector;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Anahtar kelime içerik uçları (kw sözleşmesi §10.3): okuma = izlemeyi görebilen, temel sıfırlama = izlemeyi düzenleyebilen
 * (EDIT paylaşımı dahil); göremediği izleme 404; denetim MONITOR_UPDATE (action=baseline_clear); seri kovaları.
 */
@WebMvcTest(KeywordContentController.class)
class KeywordContentControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean KeywordMonitorRepository keywordMonitorRepo;
    @MockitoBean KeywordContentQueryRepository contentQueries;
    @MockitoBean KeywordChangeBaselineService baselines;
    @MockitoBean AlertEventRepository alertEventRepo;
    @MockitoBean EscalationService escalationService;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;

    static final String URL = "https://shop.example.test/urun?id=5";

    @BeforeEach
    void defaults() {
        when(permissionService.allows(any(HttpSession.class), eq("monitoring.crud"), eq("edit"))).thenReturn(true);
        KeywordMonitor m = monitor(41L, 5L);
        m.setChangeDetect(true);
        m.setChangeMask("DIGITS,DATES");
        m.setChangeSelector("#icerik");
        when(keywordMonitorRepo.findById(41L)).thenReturn(Optional.of(m));
        when(keywordMonitorRepo.findById(404L)).thenReturn(Optional.empty());
        when(alertEventRepo.findOpenAlerts(anyString(), anyString())).thenReturn(List.of());
        when(contentQueries.latestChangeRow(anyLong())).thenReturn(List.of());
    }

    static KeywordMonitor monitor(Long id, Long teamId) {
        KeywordMonitor m = new KeywordMonitor();
        m.setId(id);
        m.setName("Ürün sayfası " + id);
        m.setUrl(URL);
        m.setKeyword("Sepete ekle");
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

    static KeywordChangeBaseline baseline(String spec) {
        KeywordChangeBaseline b = new KeywordChangeBaseline();
        b.setMonitorId(41L);
        b.setFingerprint("f".repeat(64));
        b.setSpecHash(spec);
        b.setText("Fiyat #\nGüncel: @");
        b.setTextTruncated(false);
        b.setBytes(1234L);
        b.setTakenAt("2026-11-05T08:00:00");
        b.setTakenBy("system");
        b.setSource("AUTO");
        return b;
    }

    @Test
    @DisplayName("GET change: ayar + temel (spec uyuşuyorsa, metinle) + son değerlendirme + can_edit")
    void changeView() throws Exception {
        String spec = KeywordChangeDetector.specHash("DIGITS,DATES", "#icerik");
        when(baselines.find(41L)).thenReturn(Optional.of(baseline(spec)));
        KeywordResult r = new KeywordResult();
        r.setCheckedAt("2026-11-05T09:00:00");
        r.setContentChanged(true);
        r.setChangeSummaryJson("{\"lines_before\":2,\"lines_after\":2,\"removed\":[\"Fiyat #\"],\"added\":[\"Fiyat # TL\"],"
                + "\"removed_total\":1,\"added_total\":1,\"truncated\":false}");
        when(contentQueries.latestChangeRow(41L)).thenReturn(List.of(r));
        mvc.perform(get("/api/monitoring/keyword/41/change").session(session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.mask[0]").value("DIGITS"))
                .andExpect(jsonPath("$.data.mask[1]").value("DATES"))
                .andExpect(jsonPath("$.data.selector").value("#icerik"))
                .andExpect(jsonPath("$.data.baseline.source").value("AUTO"))
                .andExpect(jsonPath("$.data.baseline.line_count").value(2))
                .andExpect(jsonPath("$.data.baseline.text").value("Fiyat #\nGüncel: @"))
                .andExpect(jsonPath("$.data.baseline.bytes").value(1234))
                .andExpect(jsonPath("$.data.latest.changed").value(true))
                .andExpect(jsonPath("$.data.latest.summary.removed[0]").value("Fiyat #"))
                .andExpect(jsonPath("$.data.can_edit").value(true));
    }

    @Test
    @DisplayName("GET change: başka ayarla alınmış temel (spec farklı) gösterilmez; temel yok → null; son değerlendirme yok → null")
    void staleBaselineHidden() throws Exception {
        when(baselines.find(41L)).thenReturn(Optional.of(baseline("eski-spec-00000")));
        mvc.perform(get("/api/monitoring/keyword/41/change").session(session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseline").doesNotExist())
                .andExpect(jsonPath("$.data.latest").doesNotExist());
    }

    @Test
    @DisplayName("Kapılar: başka takım 404 (okuma + silme), bilinmeyen izleme 404, okuma izni yok 403, AUDIT okur ama silemez (403)")
    void gates() throws Exception {
        MockHttpSession other = session("USER", 9L, 3L);
        mvc.perform(get("/api/monitoring/keyword/41/change").session(other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/monitoring/keyword/41/content-series").session(other)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/monitoring/keyword/41/change/baseline").session(other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/monitoring/keyword/404/change").session(session("ADMIN", null, 1L))).andExpect(status().isNotFound());
        MockHttpSession audit = session("AUDIT", null, 4L);
        mvc.perform(get("/api/monitoring/keyword/41/change").session(audit)).andExpect(status().isOk());
        mvc.perform(delete("/api/monitoring/keyword/41/change/baseline").session(audit)).andExpect(status().isForbidden());
        verify(baselines, never()).delete(anyLong());
        doThrow(new SecurityException("yok")).when(permissionService)
                .require(any(HttpSession.class), eq("monitoring.read"), eq("view"));
        mvc.perform(get("/api/monitoring/keyword/41/change").session(session("ADMIN", null, 1L))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE baseline: temel silinir, BU izlemenin açık KEYWORD_CHANGED alarmı elle kapanır (not), denetim yazılır")
    void clearBaselineClosesOwnAlarm() throws Exception {
        when(baselines.delete(41L)).thenReturn(true);
        AlertEvent own = new AlertEvent();
        own.setId(7001L);
        own.setDomain(URL);
        own.setAlertType(EscalationService.TYPE_KEYWORD_CHANGED);
        own.setContextJson("{\"monitor_id\":41,\"team_id\":5}");
        AlertEvent others = new AlertEvent();
        others.setId(7002L);
        others.setDomain(URL);
        others.setAlertType(EscalationService.TYPE_KEYWORD_CHANGED);
        others.setContextJson("{\"monitor_id\":99,\"team_id\":5}");   // aynı URL'yi izleyen BAŞKA izleme açtı
        when(alertEventRepo.findOpenAlerts(URL, EscalationService.TYPE_KEYWORD_CHANGED)).thenReturn(List.of(own, others));
        mvc.perform(delete("/api/monitoring/keyword/41/change/baseline").session(session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.closed_alarm").value(true));
        verify(escalationService).resolve(7001L, "user2", "İçerik temeli sıfırlandı");
        verify(escalationService, never()).resolve(eq(7002L), any(), any());
        verify(auditService).recordAction(eq("MONITOR_UPDATE"), any(HttpSession.class), eq("KEYWORD_MONITOR"), eq("41"),
                argThat(d -> d != null && d.contains("baseline_clear") && !d.contains("id=5")), isNull());
    }

    @Test
    @DisplayName("DELETE baseline: temel yok + alarm yok → {deleted:false, closed_alarm:false}; alarm kapanış hatası işlemi bozmaz")
    void clearBaselineNothing() throws Exception {
        when(baselines.delete(41L)).thenReturn(false);
        mvc.perform(delete("/api/monitoring/keyword/41/change/baseline").session(session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(false))
                .andExpect(jsonPath("$.data.closed_alarm").value(false));
        when(alertEventRepo.findOpenAlerts(anyString(), anyString())).thenThrow(new RuntimeException("db"));
        mvc.perform(delete("/api/monitoring/keyword/41/change/baseline").session(session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closed_alarm").value(false));
    }

    @Test
    @DisplayName("content-series: kovalar (adet min/ort/maks, bayt ort., değişim, hata), tavan, tarih doğrulaması (400 from)")
    void contentSeries() throws Exception {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"2026-11-05T09:10:00", true, 3, 1000L, false});
        rows.add(new Object[]{"2026-11-05T09:20:00", false, 0, 3000L, true});
        rows.add(new Object[]{"2026-11-05T10:05:00", true, null, null, null});
        when(contentQueries.contentSeriesRaw(eq(41L), anyString(), anyString(), anyInt())).thenReturn(rows);
        mvc.perform(get("/api/monitoring/keyword/41/content-series?from=2026-11-01&to=2026-11-05").session(session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value("2026-11-01T00:00:00"))
                .andExpect(jsonPath("$.data.to").value("2026-11-05T23:59:59"))
                .andExpect(jsonPath("$.data.bucket_seconds").value(3600))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.capped").value(false))
                .andExpect(jsonPath("$.data.points[0].ts").value("2026-11-05T09:00:00"))
                .andExpect(jsonPath("$.data.points[0].checks").value(2))
                .andExpect(jsonPath("$.data.points[0].failed").value(1))
                .andExpect(jsonPath("$.data.points[0].occ_min").value(0))
                .andExpect(jsonPath("$.data.points[0].occ_max").value(3))
                .andExpect(jsonPath("$.data.points[0].occ_avg").value(1.5))
                .andExpect(jsonPath("$.data.points[0].bytes_avg").value(2000))
                .andExpect(jsonPath("$.data.points[0].changed").value(1))
                .andExpect(jsonPath("$.data.points[1].occ_min").doesNotExist())
                .andExpect(jsonPath("$.data.points[1].bytes_avg").doesNotExist());
        mvc.perform(get("/api/monitoring/keyword/41/content-series?from=dün").session(session("USER", 5L, 2L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("from"));
    }

    @Test
    @DisplayName("Seri saf kovalama: dakika / 10 dk / saat / gün kova uzunlukları; ham satır tavanı capped=true")
    void bucketsPure() {
        assertThat(KeywordContentController.bucketKeyLen("2026-11-05T00:00:00", "2026-11-05T05:00:00")).isEqualTo(16);
        assertThat(KeywordContentController.bucketKeyLen("2026-11-04T00:00:00", "2026-11-05T12:00:00")).isEqualTo(15);
        assertThat(KeywordContentController.bucketKeyLen("2026-10-10T00:00:00", "2026-11-05T00:00:00")).isEqualTo(13);
        assertThat(KeywordContentController.bucketKeyLen("2026-08-01T00:00:00", "2026-11-05T00:00:00")).isEqualTo(10);
        assertThat(KeywordContentController.bucketIso("2026-11-05T09:1", 15)).isEqualTo("2026-11-05T09:10:00");
        assertThat(KeywordContentController.bucketSeconds(10)).isEqualTo(86_400);
        Map<String, Object> s = KeywordContentController.buildSeries(List.of(), "2026-11-05T00:00:00", "2026-11-05T01:00:00", true);
        assertThat(s).containsEntry("capped", true).containsEntry("total", 0);
        assertThat(KeywordContentController.parseBound("2026-11-05T10:00", false, "from")).isEqualTo("2026-11-05T10:00:00");
        assertThat(KeywordContentController.parseBound("2026-11-05T10:00:00Z", true, "to")).isEqualTo("2026-11-05T10:00:00");
    }
}
