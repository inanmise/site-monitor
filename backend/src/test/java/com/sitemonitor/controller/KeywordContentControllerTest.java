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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    @MockitoBean com.sitemonitor.service.SchedulerService schedulerService;
    @MockitoBean com.sitemonitor.service.KeywordCheckerService keywordChecker;

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

    /** Varsayılan izlemenin (41) GÜNCEL temel özeti — istek parmak izi dahil (hata avı 2026-11-06 H4#3). */
    static String currentSpec() {
        KeywordMonitor m = monitor(41L, 5L);
        m.setChangeDetect(true);
        m.setChangeMask("DIGITS,DATES");
        m.setChangeSelector("#icerik");
        return KeywordChangeDetector.specHashFor(m, null);
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
        String spec = currentSpec();
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
    @DisplayName("Performans 2026-11-06: değişim algılama KAPALIYKEN son değişim / son satır sorgulanmaz, anahtarlar null")
    void changeViewDisabled_readsNoResultRows() throws Exception {
        KeywordMonitor m = monitor(41L, 5L);
        m.setChangeDetect(false);
        when(keywordMonitorRepo.findById(41L)).thenReturn(Optional.of(m));
        mvc.perform(get("/api/monitoring/keyword/41/change").session(session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.latest").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.skipped").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.can_edit").value(true));
        org.mockito.Mockito.verify(contentQueries, org.mockito.Mockito.never()).latestChangeRow(anyLong());
        org.mockito.Mockito.verify(contentQueries, org.mockito.Mockito.never()).latestRow(anyLong());
        // null (hiç ayarlanmamış) da kapalı sayılır
        m.setChangeDetect(null);
        mvc.perform(get("/api/monitoring/keyword/41/change").session(session("USER", 5L, 2L)))
                .andExpect(jsonPath("$.data.enabled").value(false));
        org.mockito.Mockito.verify(contentQueries, org.mockito.Mockito.never()).latestChangeRow(anyLong());
    }

    @Test
    @DisplayName("kw son inceleme O-5: temel METNİ yalnız düzenleyebilene — okuyucu (AUDIT / düzenleme izni yok) text null + "
            + "text_hidden; satır sayısı ve fark özeti okuyucuya kalır; son kontrolde değişim ölçülmediyse skipped notu")
    void baselineTextOnlyForEditors() throws Exception {
        String spec = currentSpec();
        when(baselines.find(41L)).thenReturn(Optional.of(baseline(spec)));
        KeywordResult ch = new KeywordResult();
        ch.setCheckedAt("2026-11-05T09:00:00");
        ch.setContentChanged(true);
        ch.setChangeSummaryJson("{\"lines_before\":2,\"lines_after\":2,\"removed\":[\"Fiyat #\"],\"added\":[],"
                + "\"removed_total\":1,\"added_total\":0,\"truncated\":false}");
        when(contentQueries.latestChangeRow(41L)).thenReturn(List.of(ch));
        KeywordResult last = new KeywordResult();
        last.setCheckedAt("2026-11-05T10:00:00");
        last.setChangeNote("HTTP_ERROR");
        when(contentQueries.latestRow(41L)).thenReturn(List.of(last));
        mvc.perform(get("/api/monitoring/keyword/41/change").session(session("AUDIT", null, 4L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_edit").value(false))
                .andExpect(jsonPath("$.data.baseline.text").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.baseline.text_hidden").value(true))
                .andExpect(jsonPath("$.data.baseline.line_count").value(2))
                .andExpect(jsonPath("$.data.latest.summary.removed[0]").value("Fiyat #"))
                .andExpect(jsonPath("$.data.skipped.note").value("HTTP_ERROR"))
                .andExpect(jsonPath("$.data.skipped.checked_at").value("2026-11-05T10:00:00"));
        // düzenleme izni olmayan takım üyesi de okuyucu
        when(permissionService.allows(any(HttpSession.class), eq("monitoring.crud"), eq("edit"))).thenReturn(false);
        mvc.perform(get("/api/monitoring/keyword/41/change").session(session("USER", 5L, 2L)))
                .andExpect(jsonPath("$.data.baseline.text_hidden").value(true));
        // düzenleyebilen metni görür, text_hidden yok
        when(permissionService.allows(any(HttpSession.class), eq("monitoring.crud"), eq("edit"))).thenReturn(true);
        when(contentQueries.latestRow(41L)).thenReturn(List.of());
        mvc.perform(get("/api/monitoring/keyword/41/change").session(session("USER", 5L, 2L)))
                .andExpect(jsonPath("$.data.baseline.text").value("Fiyat #\nGüncel: @"))
                .andExpect(jsonPath("$.data.baseline.text_hidden").doesNotExist())
                .andExpect(jsonPath("$.data.skipped").doesNotExist());
    }

    @Test
    @DisplayName("POST change/baseline: seçici öğe bulmadı → 409 KEYWORD_BASELINE_FETCH_FAILED + change_skipped=SELECTOR_NOT_FOUND, "
            + "temel alınmaz")
    void takeBaselineSelectorNotFound() throws Exception {
        when(schedulerService.keywordCheckSpec(any(KeywordMonitor.class), eq(false))).thenAnswer(a -> liveSpec(a.getArgument(0)));
        when(keywordChecker.check(any(com.sitemonitor.service.keyword.KeywordCheckSpec.class)))
                .thenReturn(new java.util.HashMap<>(Map.of("ok", true, "change_skipped", "SELECTOR_NOT_FOUND")));
        mvc.perform(post("/api/monitoring/keyword/41/change/baseline").session(session("USER", 5L, 2L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEYWORD_BASELINE_FETCH_FAILED"))
                .andExpect(jsonPath("$.change_skipped").value("SELECTOR_NOT_FOUND"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("seçici")));
        verify(baselines, never()).takeManual(anyLong(), any(), anyLong(), any(), any(), any());
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
        // kw son inceleme ŞÜPHE: çözen = GÖRÜNEN ad (diğer elle kapanışlar gibi) + kapanan alarm için ALERT_RESOLVE denetimi
        verify(escalationService).resolve(7001L, "Kullanıcı 2", "İçerik temeli sıfırlandı");
        verify(escalationService, never()).resolve(eq(7002L), any(), any());
        verify(auditService).recordAction(eq("ALERT_RESOLVE"), any(HttpSession.class), eq("ALERT_EVENT"), eq("7001"),
                argThat((String d) -> d != null && d.contains("keyword_baseline") && d.contains("İçerik temeli sıfırlandı")), isNull());
        verify(auditService, never()).recordAction(eq("ALERT_RESOLVE"), any(HttpSession.class), eq("ALERT_EVENT"), eq("7002"),
                any(), any());
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

    // ── Aşama 2: canlı ölçüm uçları ──────────────────────────────────────────────────────────

    private com.sitemonitor.service.keyword.KeywordCheckSpec liveSpec(KeywordMonitor m) {
        return com.sitemonitor.service.keyword.KeywordCheckSpec.forMonitor(m, null, null, false).build();
    }

    @Test
    @DisplayName("POST change/baseline: canlı ölçümle MANUAL temel + açık değişim alarmı 'Yeni içerik temeli alındı' notuyla kapanır; "
            + "denetim baseline_set; değişim kapalı → 409 KEYWORD_CHANGE_DISABLED; sayfa okunamadı → 409 KEYWORD_BASELINE_FETCH_FAILED")
    void takeBaselineLive() throws Exception {
        KeywordMonitor m = keywordMonitorRepo.findById(41L).orElseThrow();
        when(schedulerService.keywordCheckSpec(any(KeywordMonitor.class), eq(false))).thenAnswer(a -> liveSpec(a.getArgument(0)));
        var snap = KeywordChangeDetector.snapshot(List.of("Fiyat 12 TL"), java.util.EnumSet.noneOf(
                com.sitemonitor.service.keyword.content.ContentMasker.Mask.class), List.of());
        when(keywordChecker.check(any(com.sitemonitor.service.keyword.KeywordCheckSpec.class))).thenAnswer(a -> {
            com.sitemonitor.service.keyword.KeywordCheckSpec sp = a.getArgument(0);
            assertThat(sp.changeDetect()).isTrue();
            assertThat(sp.baseline()).isNull();
            sp.changeCapture().put(snap, 900L, "h1");
            return new java.util.HashMap<>(Map.of("ok", true, "baseline_missing", true));
        });
        KeywordChangeBaseline saved = baseline("h1");
        saved.setSource("MANUAL");
        when(baselines.takeManual(eq(41L), eq(snap), eq(900L), eq("h1"), eq("user2"), eq("Kullanıcı 2"))).thenReturn(saved);
        AlertEvent open = new AlertEvent();
        open.setId(7001L);
        open.setContextJson("{\"monitor_id\":41}");
        when(alertEventRepo.findOpenAlerts(URL, EscalationService.TYPE_KEYWORD_CHANGED)).thenReturn(List.of(open));
        mvc.perform(post("/api/monitoring/keyword/41/change/baseline").session(session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.baseline.source").value("MANUAL"))
                .andExpect(jsonPath("$.data.closed_alarm").value(true));
        verify(escalationService).resolve(7001L, "Kullanıcı 2", "Yeni içerik temeli alındı");
        verify(auditService).recordAction(eq("ALERT_RESOLVE"), any(HttpSession.class), eq("ALERT_EVENT"), eq("7001"),
                argThat((String d) -> d != null && d.contains("Yeni içerik temeli alındı")), isNull());
        verify(auditService).recordAction(eq("MONITOR_UPDATE"), any(HttpSession.class), eq("KEYWORD_MONITOR"), eq("41"),
                argThat((String d) -> d != null && d.contains("baseline_set")), isNull());

        // sayfa okunamadı → anlık görüntü yok
        when(keywordChecker.check(any(com.sitemonitor.service.keyword.KeywordCheckSpec.class)))
                .thenReturn(new java.util.HashMap<>(Map.of("ok", false, "failure_reason", "TIMEOUT_READ", "error", "x")));
        mvc.perform(post("/api/monitoring/keyword/41/change/baseline").session(session("USER", 5L, 2L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEYWORD_BASELINE_FETCH_FAILED"))
                .andExpect(jsonPath("$.failure_reason").value("TIMEOUT_READ"));

        m.setChangeDetect(null);
        mvc.perform(post("/api/monitoring/keyword/41/change/baseline").session(session("USER", 5L, 2L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEYWORD_CHANGE_DISABLED"));
        // başka takım → 404, okuyabilen ama düzenleyemeyen (AUDIT) → 403
        mvc.perform(post("/api/monitoring/keyword/41/change/baseline").session(session("USER", 9L, 3L)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST matches: aynı ölçümün içerik görünümünden kural başına ≤ 5 bağlam (maskeli); satır / alarm / temel YOK; "
            + "denetim MONITOR_DIAGNOSE kind=matches")
    void liveMatches() throws Exception {
        when(permissionService.allows(any(HttpSession.class), eq("monitoring.trigger"), eq("execute"))).thenReturn(true);
        KeywordMonitor m = keywordMonitorRepo.findById(41L).orElseThrow();
        m.setRulesJson("[{\"id\":\"r1\",\"kind\":\"TEXT\",\"pattern\":\"fiyat\",\"operator\":\"GTE\",\"count\":1},"
                + "{\"id\":\"r2\",\"kind\":\"REGEX\",\"pattern\":\"\\\\d+ TL\",\"operator\":\"GTE\",\"count\":1}]");
        when(schedulerService.keywordCheckSpec(any(KeywordMonitor.class), eq(false))).thenAnswer(a -> liveSpec(a.getArgument(0)));
        String html = "<p>Fiyat 12 TL</p><p>FİYAT 13 TL</p><p>password=Gizli123</p>";
        when(keywordChecker.checkDetailed(any(com.sitemonitor.service.keyword.KeywordCheckSpec.class))).thenAnswer(a -> {
            com.sitemonitor.service.keyword.KeywordCheckSpec sp = a.getArgument(0);
            var v = com.sitemonitor.service.KeywordCheckerService.evaluateRules(sp, html, "text/html", html.length(), false, null, null);
            return new com.sitemonitor.service.KeywordCheckerService.Detailed(
                    new java.util.HashMap<>(Map.of("http_status", 200, "final_url", URL, "ok", true)), v);
        });
        mvc.perform(post("/api/monitoring/keyword/41/matches").session(session("USER", 5L, 2L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.http_status").value(200))
                .andExpect(jsonPath("$.data.rules.length()").value(2))
                .andExpect(jsonPath("$.data.rules[0].kind").value("TEXT"))
                .andExpect(jsonPath("$.data.rules[0].count").value(2))
                .andExpect(jsonPath("$.data.rules[0].contexts.length()").value(2))
                .andExpect(jsonPath("$.data.rules[0].contexts[1].match").value("FİYAT"))
                .andExpect(jsonPath("$.data.rules[1].contexts[0].match").value("12 TL"))
                .andExpect(jsonPath("$.data.rules[1].contexts[0].source").value("RAW"))
                .andExpect(jsonPath("$.data.truncated").value(false));
        verify(auditService).recordAction(eq("MONITOR_DIAGNOSE"), any(HttpSession.class), eq("KEYWORD_MONITOR"), eq("41"),
                argThat((String d) -> d != null && d.contains("matches")), isNull());
        verify(baselines, never()).takeManual(any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("Eşleşme ucu yük sınırı: kullanıcı başına dakikada 6 (pod belleği), 7. istek bekleme süresiyle reddedilir")
    void matchesRateLimit() {
        KeywordContentController c = new KeywordContentController(keywordMonitorRepo, contentQueries, baselines, alertEventRepo,
                escalationService, permissionService, auditService, schedulerService, keywordChecker);
        c.setClock(java.time.Clock.fixed(java.time.Instant.parse("2026-11-05T09:00:00Z"), java.time.ZoneOffset.UTC));
        for (int i = 0; i < KeywordContentController.MATCHES_PER_MINUTE; i++) assertThat(c.matchesRate("ayse")).isNull();
        assertThat(c.matchesRate("ayse")).isBetween(1L, 60L);
        assertThat(c.matchesRate("mehmet")).isNull();
    }
}
