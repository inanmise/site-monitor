package com.sitemonitor.service.page.diagnose;

import com.sitemonitor.model.DiagnosticRun;
import com.sitemonitor.service.DiagnosticHistoryService;
import com.sitemonitor.service.diagnose.NetDiagnosticsHistory;
import com.sitemonitor.service.page.diagnose.PageDiagnosticsHistory.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sayfa Bütünlüğü / Sayfa Hızı tanılama geçmişi (2026-10-05): hop gövde önizlemesi KAYDEDİLMEZ (canlı yanıt değişmez);
 * kayıt {@code page-monitor:<id>} + {@code PAGE_DIAG} / {@code pagespeed-monitor:<id>} + {@code PAGESPEED_DIAG} ile yazılır —
 * alan adı geçmişine karışmaz; başka izlemenin ya da TÜRÜN kaydı açılamaz; yönetim tekil uç bu türleri izleme tanılaması sayar.
 */
class PageDiagnosticsHistoryTest {

    private final ObjectMapper json = new ObjectMapper();

    private static Map<String, Object> sampleData(String verdictStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bytes", 10);
        body.put("text", true);
        body.put("preview", "<html>gövde önizlemesi gizli</html>");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 200);
        response.put("body", body);
        Map<String, Object> hop = new LinkedHashMap<>();
        hop.put("index", 0);
        hop.put("response", response);
        List<Object> hops = new ArrayList<>();
        hops.add(hop);
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("key", "monitor");
        path.put("route", "proxy");
        path.put("outcome", "fail");
        path.put("http_status", 200);
        path.put("hops", hops);
        Map<String, Object> alt = new LinkedHashMap<>();
        alt.put("key", "alternate");
        alt.put("route", "direct");
        alt.put("outcome", "ok");
        alt.put("http_status", 200);
        alt.put("hops", new ArrayList<>());
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("status", verdictStatus);
        verdict.put("code", "RESOURCES_BROKEN");
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("issues", List.of(Map.of("url", "https://shop.example.test/a.png", "kind", "BROKEN")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", null);
        data.put("kind", "page");
        data.put("started_at", "2026-10-05T09:00:00Z");
        data.put("duration_ms", 812L);
        data.put("verdict", verdict);
        data.put("findings", List.of(Map.of("code", "RESOURCES_BROKEN"), Map.of("code", "SAME_HOST_BROKEN")));
        List<Object> paths = new ArrayList<>();
        paths.add(path);
        paths.add(alt);
        data.put("paths", paths);
        data.put("page", page);
        return data;
    }

    @Test
    @DisplayName("kayıt: anahtar + tür + port; gövde önizlemesi silinir, canlı yanıt değişmez; özet yolları taşır")
    @SuppressWarnings("unchecked")
    void save_stripsPreviews() throws Exception {
        DiagnosticHistoryService svc = mock(DiagnosticHistoryService.class);
        when(svc.recordRun(anyString(), any(), anyString(), any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(9L);
        PageDiagnosticsHistory h = new PageDiagnosticsHistory(svc, json);
        Map<String, Object> data = sampleData("fail");
        Long id = h.save(Kind.PAGE, 61L, "https://shop.example.test:8443/x", data, "operator1", 3L, 5L, "203.0.113.7");
        assertThat(id).isEqualTo(9L);
        ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(svc).recordRun(eq("page-monitor:61"), eq(8443), eq("PAGE_DIAG"), eq("operator1"), eq(3L), eq(5L),
                eq("203.0.113.7"), eq(false), summary.capture(), stored.capture());
        String storedJson = json.writeValueAsString(stored.getValue());
        assertThat(storedJson).doesNotContain("gövde önizlemesi gizli").contains("\"preview_stored\":false")
                .contains("shop.example.test/a.png");
        // canlı yanıt (pencere) önizlemeyi hâlâ taşır
        Map<String, Object> hop = ((List<Map<String, Object>>) ((Map<String, Object>) ((List<Object>) data.get("paths")).get(0)).get("hops")).get(0);
        assertThat(((Map<String, Object>) ((Map<String, Object>) hop.get("response")).get("body")).get("preview")).isNotNull();
        Map<String, Object> s = json.readValue(summary.getValue(), Map.class);
        assertThat(s).containsEntry("verdict_code", "RESOURCES_BROKEN").containsEntry("monitor_route", "proxy")
                .containsEntry("alternate_route", "direct").containsEntry("findings", 2);
    }

    @Test
    @DisplayName("Sayfa Hızı kaydı kendi anahtarı + türüyle; başarı = hüküm ok")
    void save_pageSpeed() {
        DiagnosticHistoryService svc = mock(DiagnosticHistoryService.class);
        PageDiagnosticsHistory h = new PageDiagnosticsHistory(svc, json);
        h.save(Kind.PAGESPEED, 71L, "https://shop.example.test/", sampleData("ok"), "op", null, null, null);
        verify(svc).recordRun(eq("pagespeed-monitor:71"), eq(443), eq("PAGESPEED_DIAG"), eq("op"), any(), any(), any(),
                eq(true), any(), any());
    }

    private static DiagnosticRun run(Long id, String domain, String type) {
        DiagnosticRun d = new DiagnosticRun();
        d.setId(id);
        d.setDomain(domain);
        d.setRunType(type);
        d.setExecutedBy("operator1");
        d.setResultJson("{\"verdict\":{\"code\":\"PAGE_OK\"},\"run_id\":null}");
        d.setSummary("{\"verdict_code\":\"PAGE_OK\",\"verdict_status\":\"ok\",\"started_at\":\"2026-10-05T09:00:00Z\"}");
        return d;
    }

    @Test
    @DisplayName("okuma: yalnız AYNI izleme + AYNI tür; başka izleme / tür / alan adı kaydı → null")
    void get_isolation() {
        DiagnosticHistoryService svc = mock(DiagnosticHistoryService.class);
        when(svc.get(1L)).thenReturn(run(1L, "page-monitor:61", "PAGE_DIAG"));
        when(svc.get(2L)).thenReturn(run(2L, "pagespeed-monitor:61", "PAGESPEED_DIAG"));
        when(svc.get(3L)).thenReturn(run(3L, "page-monitor:62", "PAGE_DIAG"));
        when(svc.get(4L)).thenReturn(run(4L, "page-monitor:61", "HTTP_DIAG"));
        when(svc.get(5L)).thenReturn(run(5L, "example.com", "CONNECTION"));
        when(svc.get(6L)).thenThrow(new NoSuchElementException("yok"));
        PageDiagnosticsHistory h = new PageDiagnosticsHistory(svc, json);
        Map<String, Object> ok = h.get(Kind.PAGE, 61L, 1L);
        assertThat(ok).containsEntry("run_id", 1L).containsEntry("executed_by", "operator1");
        assertThat(h.get(Kind.PAGE, 61L, 2L)).as("Sayfa Hızı kaydı Sayfa Bütünlüğü ucundan açılmaz").isNull();
        assertThat(h.get(Kind.PAGESPEED, 61L, 1L)).as("Sayfa Bütünlüğü kaydı Sayfa Hızı ucundan açılmaz").isNull();
        assertThat(h.get(Kind.PAGESPEED, 61L, 2L)).isNotNull();
        assertThat(h.get(Kind.PAGE, 61L, 3L)).as("başka izleme").isNull();
        assertThat(h.get(Kind.PAGE, 61L, 4L)).as("başka tür").isNull();
        assertThat(h.get(Kind.PAGE, 61L, 5L)).as("alan adı geçmişi").isNull();
        assertThat(h.get(Kind.PAGE, 61L, 6L)).isNull();
    }

    @Test
    @DisplayName("liste: kendi anahtarı + türüyle sorgular, özet satırları")
    void list() {
        DiagnosticHistoryService svc = mock(DiagnosticHistoryService.class);
        when(svc.recent("pagespeed-monitor:71", "PAGESPEED_DIAG")).thenReturn(List.of(run(7L, "pagespeed-monitor:71", "PAGESPEED_DIAG")));
        PageDiagnosticsHistory h = new PageDiagnosticsHistory(svc, json);
        List<Map<String, Object>> rows = h.list(Kind.PAGESPEED, 71L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("id", 7L).containsEntry("verdict_code", "PAGE_OK").containsEntry("verdict_status", "ok");
    }

    @Test
    @DisplayName("yönetim (sertifika/alan adı) tanılama geçmişi bu türleri izleme tanılaması sayar")
    void monitorRunTypes() {
        assertThat(NetDiagnosticsHistory.isMonitorRun(run(1L, "page-monitor:61", "PAGE_DIAG"))).isTrue();
        assertThat(NetDiagnosticsHistory.isMonitorRun(run(2L, "pagespeed-monitor:61", "PAGESPEED_DIAG"))).isTrue();
    }
}
