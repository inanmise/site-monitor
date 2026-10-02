package com.sitemonitor.service.http.diagnose;

import com.sitemonitor.model.DiagnosticRun;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.service.DiagnosticHistoryService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tanılama geçmişi (2026-10-02): gövde önizlemesi KAYDEDİLMEZ, kayıt izleme anahtarıyla ({@code http-monitor:<id>})
 * yazılır — mevcut alan adı geçmişine karışmaz; liste özeti {@code summary} JSON'undan; başka izlemenin kaydı 404.
 */
class HttpDiagnosticsHistoryTest {

    private final ObjectMapper json = new ObjectMapper();

    private static Map<String, Object> sampleData() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bytes", 10);
        body.put("text", true);
        body.put("preview", "<html>gizli içerik</html>");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 401);
        response.put("body", body);
        Map<String, Object> hop = new LinkedHashMap<>();
        hop.put("index", 0);
        hop.put("response", response);
        List<Object> hops = new ArrayList<>();
        hops.add(hop);
        Map<String, Object> monitorPath = new LinkedHashMap<>();
        monitorPath.put("key", "monitor");
        monitorPath.put("route", "proxy");
        monitorPath.put("outcome", "fail");
        monitorPath.put("http_status", null);
        monitorPath.put("hops", new ArrayList<>());
        Map<String, Object> altPath = new LinkedHashMap<>();
        altPath.put("key", "alternate");
        altPath.put("route", "direct");
        altPath.put("outcome", "fail");
        altPath.put("http_status", 401);
        altPath.put("hops", hops);
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("status", "fail");
        verdict.put("code", "PATH_DIFFERS");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", null);
        data.put("started_at", "2026-10-02T10:49:53Z");
        data.put("duration_ms", 10432L);
        data.put("verdict", verdict);
        List<Object> paths = new ArrayList<>();
        paths.add(monitorPath);
        paths.add(altPath);
        data.put("paths", paths);
        return data;
    }

    private static HttpMonitor monitor() {
        HttpMonitor m = new HttpMonitor();
        m.setId(36L);
        m.setUrl("http://site.example:8080/");
        return m;
    }

    @Test
    @DisplayName("kayıt: önizleme SİLİNİR (yanıttaki kopya dokunulmaz), anahtar http-monitor:<id>, tür HTTP_DIAG, özet JSON")
    @SuppressWarnings("unchecked")
    void save_stripsPreviewAndUsesMonitorKey() throws Exception {
        DiagnosticHistoryService history = mock(DiagnosticHistoryService.class);
        when(history.recordRun(anyString(), any(), anyString(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(77L);
        HttpDiagnosticsHistory h = new HttpDiagnosticsHistory(history, json);
        Map<String, Object> data = sampleData();

        Long id = h.save(monitor(), data, "alice", 1L, 2L, "10.0.0.1");

        assertThat(id).isEqualTo(77L);
        ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(history).recordRun(eq("http-monitor:36"), eq(8080), eq("HTTP_DIAG"), eq("alice"), eq(1L), eq(2L),
                eq("10.0.0.1"), eq(false), summary.capture(), stored.capture());
        String storedJson = json.writeValueAsString(stored.getValue());
        assertThat(storedJson).doesNotContain("gizli içerik").contains("\"preview_stored\":false");
        // Yanıt kopyası (kullanıcıya dönen) önizlemeyi KORUR
        assertThat(json.writeValueAsString(data)).contains("gizli içerik");
        Map<String, Object> s = json.readValue(summary.getValue(), Map.class);
        assertThat(s).containsEntry("verdict_code", "PATH_DIFFERS").containsEntry("verdict_status", "fail")
                .containsEntry("monitor_route", "proxy").containsEntry("monitor_http_status", null)
                .containsEntry("alternate_route", "direct").containsEntry("alternate_http_status", 401)
                .containsEntry("started_at", "2026-10-02T10:49:53Z");
    }

    @Test
    @DisplayName("liste: sözleşme alanları özet JSON'dan; bozuk özet satırı düşürmez")
    void list_mapsSummary() throws Exception {
        DiagnosticHistoryService history = mock(DiagnosticHistoryService.class);
        DiagnosticRun a = new DiagnosticRun();
        a.setId(5L);
        a.setExecutedBy("alice");
        a.setExecutedAt("2026-10-02T10:50:03");
        a.setSummary(json.writeValueAsString(HttpDiagnosticsHistory.summary(sampleData())));
        DiagnosticRun b = new DiagnosticRun();
        b.setId(4L);
        b.setExecutedBy("bob");
        b.setExecutedAt("2026-10-01T08:00:00");
        b.setSummary("bozuk{");
        when(history.recent("http-monitor:36", "HTTP_DIAG")).thenReturn(List.of(a, b));
        List<Map<String, Object>> rows = new HttpDiagnosticsHistory(history, json).list(36L);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsOnlyKeys("id", "started_at", "executed_by", "verdict_code", "verdict_status",
                "monitor_route", "monitor_http_status", "alternate_route", "alternate_http_status", "duration_ms");
        assertThat(rows.get(0)).containsEntry("id", 5L).containsEntry("executed_by", "alice")
                .containsEntry("verdict_code", "PATH_DIFFERS").containsEntry("alternate_http_status", 401)
                .containsEntry("started_at", "2026-10-02T10:49:53Z");
        assertThat(rows.get(1)).containsEntry("id", 4L).containsEntry("verdict_code", null)
                .containsEntry("started_at", "2026-10-01T08:00:00Z");
    }

    @Test
    @DisplayName("tek kayıt: başka izlemenin / başka türün / olmayan kayıt → null (404)")
    void get_scopedToMonitorAndType() throws Exception {
        DiagnosticHistoryService history = mock(DiagnosticHistoryService.class);
        DiagnosticRun own = new DiagnosticRun();
        own.setId(9L);
        own.setDomain("http-monitor:36");
        own.setRunType("HTTP_DIAG");
        own.setExecutedBy("alice");
        own.setResultJson(json.writeValueAsString(HttpDiagnosticsHistory.withoutPreviews(sampleData())));
        DiagnosticRun other = new DiagnosticRun();
        other.setId(10L);
        other.setDomain("http-monitor:99");
        other.setRunType("HTTP_DIAG");
        DiagnosticRun certDiag = new DiagnosticRun();
        certDiag.setId(11L);
        certDiag.setDomain("http-monitor:36");
        certDiag.setRunType("CONNECTION");
        when(history.get(9L)).thenReturn(own);
        when(history.get(10L)).thenReturn(other);
        when(history.get(11L)).thenReturn(certDiag);
        when(history.get(12L)).thenThrow(new NoSuchElementException("yok"));
        HttpDiagnosticsHistory h = new HttpDiagnosticsHistory(history, json);

        Map<String, Object> r = h.get(36L, 9L);
        assertThat(r).containsEntry("run_id", 9L).containsEntry("executed_by", "alice").containsKey("paths");
        assertThat(json.writeValueAsString(r)).doesNotContain("gizli içerik");
        assertThat(h.get(36L, 10L)).isNull();
        assertThat(h.get(36L, 11L)).isNull();
        assertThat(h.get(36L, 12L)).isNull();
    }

    @Test
    @DisplayName("kayıt yazılamazsa run_id null — akış bozulmaz")
    void save_failureReturnsNull() {
        DiagnosticHistoryService history = mock(DiagnosticHistoryService.class);
        when(history.recordRun(anyString(), any(), anyString(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(null);
        assertThat(new HttpDiagnosticsHistory(history, json).save(monitor(), sampleData(), "a", null, null, null)).isNull();
        verify(history).recordRun(eq("http-monitor:36"), eq(8080), eq("HTTP_DIAG"), eq("a"), isNull(), isNull(), isNull(),
                eq(false), any(), any());
    }
}
