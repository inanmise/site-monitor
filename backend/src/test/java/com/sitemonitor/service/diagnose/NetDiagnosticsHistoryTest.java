package com.sitemonitor.service.diagnose;

import com.sitemonitor.model.DiagnosticRun;
import com.sitemonitor.service.DiagnosticHistoryService;
import com.sitemonitor.service.DomainNames;
import com.sitemonitor.service.diagnose.NetDiagnosticsHistory.Kind;
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
 * Ping / Port / DNS tanılama geçmişi (2026-10-05): kayıt {@code <tür>-monitor:<id>} + {@code PING_DIAG|PORT_DIAG|DNS_DIAG}
 * ile yazılır; banner önizlemesi saklanmaz; bir türün ucu başka türün / başka izlemenin kaydını açamaz; anahtar hiçbir
 * alan adı sorgusunun üretebileceği bir değer değildir (yönetim tanılama geçmişine karışmaz).
 */
class NetDiagnosticsHistoryTest {

    private final ObjectMapper json = new ObjectMapper();

    private static Map<String, Object> portData() {
        Map<String, Object> banner = new LinkedHashMap<>();
        banner.put("received_bytes", 20);
        banner.put("text_preview", "220 gizli banner metni");
        banner.put("hex_preview", "32 32 30");
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("key", "banner");
        step.put("status", "ok");
        step.put("detail", banner);
        List<Object> steps = new ArrayList<>();
        steps.add(step);
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("key", "alternate");
        path.put("steps", new ArrayList<>(List.of(deepStep())));
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("code", "PORT_OK");
        verdict.put("status", "ok");
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("host", "mail.example.test");
        target.put("port", 25);
        target.put("protocol", "BANNER");
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("own", "direct");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("run_id", null);
        d.put("kind", "port");
        d.put("started_at", "2026-10-05T09:00:00Z");
        d.put("duration_ms", 210L);
        d.put("verdict", verdict);
        d.put("target", target);
        d.put("route", route);
        d.put("findings", new ArrayList<>(List.of(Map.of("code", "PORT_OK"))));
        d.put("steps", steps);
        d.put("paths", new ArrayList<>(List.of(path)));
        return d;
    }

    private static Map<String, Object> deepStep() {
        Map<String, Object> banner = new LinkedHashMap<>();
        banner.put("text_preview", "öteki yolun banner metni");
        banner.put("hex_preview", "aa bb");
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("key", "banner");
        step.put("detail", banner);
        return step;
    }

    @Test
    @DisplayName("kayıt: banner önizlemeleri SİLİNİR (yollar dâhil), canlı yanıt değişmez; anahtar + tür + port + başarı")
    @SuppressWarnings("unchecked")
    void save_stripsBannerPreviews() throws Exception {
        DiagnosticHistoryService history = mock(DiagnosticHistoryService.class);
        when(history.recordRun(anyString(), any(), anyString(), any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(5L);
        Map<String, Object> data = portData();
        Long id = new NetDiagnosticsHistory(history, json).save(Kind.PORT, 31L, 25, data, "alice", 1L, 2L, "10.0.0.9");
        assertThat(id).isEqualTo(5L);
        ArgumentCaptor<Object> stored = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(history).recordRun(eq("port-monitor:31"), eq(25), eq("PORT_DIAG"), eq("alice"), eq(1L), eq(2L), eq("10.0.0.9"),
                eq(true), summary.capture(), stored.capture());
        String storedJson = json.writeValueAsString(stored.getValue());
        assertThat(storedJson).doesNotContain("gizli banner metni").doesNotContain("öteki yolun banner metni")
                .doesNotContain("32 32 30").contains("\"preview_stored\":false");
        assertThat(summary.getValue()).contains("PORT_OK").contains("mail.example.test:25 (BANNER)").contains("\"route\":\"direct\"");
        Map<String, Object> liveStep = (Map<String, Object>) ((List<Object>) data.get("steps")).get(0);
        assertThat(((Map<String, Object>) liveStep.get("detail")).get("text_preview")).isEqualTo("220 gizli banner metni");
    }

    @Test
    @DisplayName("liste: yalnız kendi anahtarı + türü okunur; özet alanları")
    void list_readsOwnKeyAndType() throws Exception {
        DiagnosticHistoryService history = mock(DiagnosticHistoryService.class);
        DiagnosticRun a = new DiagnosticRun();
        a.setId(9L);
        a.setExecutedBy("alice");
        a.setSummary(json.writeValueAsString(NetDiagnosticsHistory.summary(portData())));
        when(history.recent("ping-monitor:31", "PING_DIAG")).thenReturn(List.of(a));
        List<Map<String, Object>> rows = new NetDiagnosticsHistory(history, json).list(Kind.PING, 31L);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("id", 9L).containsEntry("verdict_code", "PORT_OK").containsEntry("route", "direct")
                .containsEntry("findings", 1).containsEntry("traceroute", false);
    }

    @Test
    @DisplayName("tek kayıt: başka izlemenin / başka türün (aynı numara) / olmayan kaydın → null (404)")
    void get_scopedToMonitorAndType() throws Exception {
        DiagnosticHistoryService history = mock(DiagnosticHistoryService.class);
        DiagnosticRun own = run(1L, "dns-monitor:31", "DNS_DIAG");
        own.setResultJson(json.writeValueAsString(Map.of("kind", "dns", "run_id", 1)));
        when(history.get(1L)).thenReturn(own);
        when(history.get(2L)).thenReturn(run(2L, "dns-monitor:99", "DNS_DIAG"));
        when(history.get(3L)).thenReturn(run(3L, "dns-monitor:31", "PING_DIAG"));
        when(history.get(4L)).thenReturn(run(4L, "ping-monitor:31", "PING_DIAG"));
        when(history.get(5L)).thenThrow(new NoSuchElementException("yok"));
        NetDiagnosticsHistory h = new NetDiagnosticsHistory(history, json);
        assertThat(h.get(Kind.DNS, 31L, 1L)).containsEntry("run_id", 1L).containsEntry("kind", "dns").containsEntry("executed_by", "bob");
        assertThat(h.get(Kind.DNS, 31L, 2L)).isNull();
        assertThat(h.get(Kind.DNS, 31L, 3L)).isNull();
        assertThat(h.get(Kind.DNS, 31L, 4L)).isNull();   // ping kaydı DNS ucundan açılmaz
        assertThat(h.get(Kind.PING, 31L, 1L)).isNull();  // DNS kaydı ping ucundan açılmaz
        assertThat(h.get(Kind.DNS, 31L, 5L)).isNull();
    }

    private static DiagnosticRun run(Long id, String domain, String type) {
        DiagnosticRun d = new DiagnosticRun();
        d.setId(id);
        d.setDomain(domain);
        d.setRunType(type);
        d.setExecutedBy("bob");
        return d;
    }

    @Test
    @DisplayName("ayrışma: anahtarlar alan adı olarak geçerli değil; izleme run_type'ları yönetim geçmişinden ayıklanır")
    void keysNeverValidDomains() {
        for (Kind k : Kind.values()) {
            String key = k.key(41L);
            assertThat(key).contains(":");
            Throwable t = catchThrowable(() -> DomainNames.validate(key));
            String validated = t == null ? DomainNames.validate(key) : null;
            assertThat(validated == null || !validated.contains(":")).isTrue();
            assertThat(NetDiagnosticsHistory.isMonitorRun(run(1L, key, k.runType))).isTrue();
        }
        assertThat(NetDiagnosticsHistory.isMonitorRun(run(1L, "example.test", "CONNECTION"))).isFalse();
        assertThat(NetDiagnosticsHistory.isMonitorRun(run(1L, "http-monitor:4", "HTTP_DIAG"))).isTrue();
        assertThat(NetDiagnosticsHistory.isMonitorRun(run(1L, "keyword-monitor:4", "KEYWORD_DIAG"))).isTrue();
        // run_type kolonu 20 karakter — tür adları sığmalı
        for (Kind k : Kind.values()) assertThat(k.runType.length()).isLessThanOrEqualTo(20);
    }
}
