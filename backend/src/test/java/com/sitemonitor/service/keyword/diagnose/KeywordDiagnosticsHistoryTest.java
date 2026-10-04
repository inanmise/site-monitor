package com.sitemonitor.service.keyword.diagnose;

import com.sitemonitor.model.DiagnosticRun;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.service.DiagnosticHistoryService;
import com.sitemonitor.service.DomainNames;
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
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Keyword tanılama geçmişi (2026-10-04): gövde önizlemesi, görünür metin önizlemesi ve eşleşme bağlamları KAYDEDİLMEZ;
 * kayıt {@code keyword-monitor:<id>} + {@code KEYWORD_DIAG} ile yazılır — alan adı geçmişine ve HTTP tanılama geçmişine
 * karışmaz; başka izlemenin/türün kaydı açılamaz.
 */
class KeywordDiagnosticsHistoryTest {

    private final ObjectMapper json = new ObjectMapper();

    private static Map<String, Object> sampleData() {
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
        path.put("route", "direct");
        path.put("outcome", "fail");
        path.put("http_status", 200);
        path.put("hops", hops);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("before", "bağlam metni öncesi");
        ctx.put("match", "Kampanya");
        ctx.put("after", "sonrası");
        Map<String, Object> kw = new LinkedHashMap<>();
        kw.put("occurrences", 0);
        kw.put("hints", List.of("LOGIN_PAGE"));
        kw.put("contexts", new ArrayList<>(List.of(ctx)));
        kw.put("visible_text_preview", "görünür metin önizlemesi");
        kw.put("preview_stored", true);
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("status", "fail");
        verdict.put("code", "KEYWORD_NOT_FOUND");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", null);
        data.put("kind", "keyword");
        data.put("started_at", "2026-10-04T09:00:00Z");
        data.put("duration_ms", 812L);
        data.put("verdict", verdict);
        List<Object> paths = new ArrayList<>();
        paths.add(path);
        data.put("paths", paths);
        data.put("keyword", kw);
        return data;
    }

    private static KeywordMonitor monitor() {
        KeywordMonitor m = new KeywordMonitor();
        m.setId(41L);
        m.setUrl("https://site.example.com/?t={timestamp}");
        return m;
    }

    @Test
    @DisplayName("kayıt: önizlemeler + bağlamlar SİLİNİR (yanıttaki kopya dokunulmaz), anahtar keyword-monitor:<id>, tür KEYWORD_DIAG")
    @SuppressWarnings("unchecked")
    void save_stripsPreviews() throws Exception {
        DiagnosticHistoryService history = mock(DiagnosticHistoryService.class);
        when(history.recordRun(anyString(), any(), anyString(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(7L);
        Map<String, Object> data = sampleData();
        Long id = new KeywordDiagnosticsHistory(history, json).save(monitor(), data, "alice", 1L, 2L, "10.0.0.9");
        assertThat(id).isEqualTo(7L);
        ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(history).recordRun(eq("keyword-monitor:41"), eq(443), eq("KEYWORD_DIAG"), eq("alice"), eq(1L), eq(2L),
                eq("10.0.0.9"), eq(false), summary.capture(), stored.capture());
        String storedJson = json.writeValueAsString(stored.getValue());
        assertThat(storedJson).doesNotContain("gövde önizlemesi gizli").doesNotContain("görünür metin önizlemesi")
                .doesNotContain("bağlam metni öncesi").contains("LOGIN_PAGE").contains("\"preview_stored\":false");
        assertThat(summary.getValue()).contains("KEYWORD_NOT_FOUND").contains("\"occurrences\":0");
        // Canlı yanıt (çağırana dönen) DEĞİŞMEDİ
        assertThat(((Map<String, Object>) data.get("keyword")).get("visible_text_preview")).isEqualTo("görünür metin önizlemesi");
    }

    @Test
    @DisplayName("liste: yalnız keyword-monitor:<id> + KEYWORD_DIAG okunur; özet alanları")
    void list_readsOwnKeyAndType() throws Exception {
        DiagnosticHistoryService history = mock(DiagnosticHistoryService.class);
        DiagnosticRun a = new DiagnosticRun();
        a.setId(5L);
        a.setExecutedBy("alice");
        a.setSummary(json.writeValueAsString(KeywordDiagnosticsHistory.summary(sampleData())));
        when(history.recent("keyword-monitor:41", "KEYWORD_DIAG")).thenReturn(List.of(a));
        List<Map<String, Object>> rows = new KeywordDiagnosticsHistory(history, json).list(41L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("id", 5L).containsEntry("verdict_code", "KEYWORD_NOT_FOUND")
                .containsEntry("occurrences", 0).containsEntry("monitor_route", "direct");
    }

    @Test
    @DisplayName("tek kayıt: başka izlemenin, HTTP_DIAG türünün ya da olmayan kaydın → null (404)")
    void get_scopedToMonitorAndType() throws Exception {
        DiagnosticHistoryService history = mock(DiagnosticHistoryService.class);
        DiagnosticRun own = new DiagnosticRun();
        own.setId(9L);
        own.setDomain("keyword-monitor:41");
        own.setRunType("KEYWORD_DIAG");
        own.setExecutedBy("alice");
        own.setResultJson(json.writeValueAsString(KeywordDiagnosticsHistory.withoutPreviews(sampleData())));
        DiagnosticRun other = new DiagnosticRun();
        other.setId(10L);
        other.setDomain("keyword-monitor:99");
        other.setRunType("KEYWORD_DIAG");
        DiagnosticRun httpRun = new DiagnosticRun();
        httpRun.setId(11L);
        httpRun.setDomain("keyword-monitor:41");
        httpRun.setRunType("HTTP_DIAG");
        when(history.get(9L)).thenReturn(own);
        when(history.get(10L)).thenReturn(other);
        when(history.get(11L)).thenReturn(httpRun);
        when(history.get(12L)).thenThrow(new NoSuchElementException("yok"));
        KeywordDiagnosticsHistory h = new KeywordDiagnosticsHistory(history, json);
        assertThat(h.get(41L, 9L)).containsEntry("run_id", 9L).containsEntry("executed_by", "alice").containsKey("keyword");
        assertThat(json.writeValueAsString(h.get(41L, 9L))).doesNotContain("gövde önizlemesi gizli");
        assertThat(h.get(41L, 10L)).isNull();
        assertThat(h.get(41L, 11L)).isNull();
        assertThat(h.get(41L, 12L)).isNull();
    }

    @Test
    @DisplayName("ayrışma: 'keyword-monitor:<id>' anahtarını hiçbir alan adı sorgusu üretemez (yönetim tanılama geçmişine karışmaz)")
    void keyNeverAValidDomain() {
        Throwable t = catchThrowable(() -> DomainNames.validate(KeywordDiagnosticsHistory.key(41L)));
        String validated = t == null ? DomainNames.validate(KeywordDiagnosticsHistory.key(41L)) : null;
        assertThat(validated == null || !validated.contains(":")).isTrue();
        assertThat(KeywordDiagnosticsHistory.key(41L)).isEqualTo("keyword-monitor:41").contains(":");
    }
}
