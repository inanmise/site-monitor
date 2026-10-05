package com.sitemonitor.service.page.diagnose;

import com.sitemonitor.model.DiagnosticRun;
import com.sitemonitor.service.DiagnosticHistoryService;
import com.sitemonitor.service.http.diagnose.HttpDiagnosticsHistory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Sayfa Bütünlüğü ve Sayfa Hızı uçtan uca tanılamasının GEÇMİŞİ (2026-10-05) — mevcut {@code diagnostic_runs} tablosu,
 * şema değişikliği YOK. HTTP/keyword tanılama geçmişinin aynası.
 *
 * <p><b>Mevcut geçmişe karışmaz.</b> Anahtar {@code page-monitor:<id>} / {@code pagespeed-monitor:<id>}: Yönetim →
 * Tanılama geçmişi alan adına TAM eşleşmeyle okur ve bu anahtarları hiçbir alan adı sorgusu üretemez ({@code :} taşır);
 * tekil kayıt ucu izleme tanılamalarını kimlikle bile açmaz ({@code NetDiagnosticsHistory.MONITOR_RUN_TYPES}). Tür
 * ({@code PAGE_DIAG | PAGESPEED_DIAG}) de okunur — bir türün ucu başka türün kaydını (aynı numaralı izleme olsa bile)
 * AÇAMAZ.
 *
 * <p><b>Gövde önizlemesi KAYDEDİLMEZ</b> (HTTP kararıyla aynı): her hop'un {@code response.body.preview}'ı silinir;
 * kaynak listesi (URL'ler sorgu sırları maskeli), sayılar ve ölçümler kalır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageDiagnosticsHistory {

    public static final int LIST_LIMIT = 20;

    /** Tanılama türleri: anahtar öneki + {@code run_type}. */
    public enum Kind {
        PAGE("page-monitor:", PageDiagnosticsService.RUN_TYPE),
        PAGESPEED("pagespeed-monitor:", PageSpeedDiagnosticsService.RUN_TYPE);

        public final String prefix;
        public final String runType;

        Kind(String prefix, String runType) {
            this.prefix = prefix;
            this.runType = runType;
        }

        public String key(Long monitorId) { return prefix + monitorId; }
    }

    private final DiagnosticHistoryService history;
    private final ObjectMapper objectMapper;

    /** Sonucu (önizlemesiz kopya) geçmişe yazar. @return kayıt kimliği; yazılamazsa {@code null}. */
    public Long save(Kind kind, Long monitorId, String url, Map<String, Object> data, String actor, Long actorId,
                     Long actorTeamId, String sourceIp) {
        Map<String, Object> stored = HttpDiagnosticsHistory.withoutPreviews(data);
        Object verdict = data.get("verdict");
        boolean success = verdict instanceof Map<?, ?> v && "ok".equals(v.get("status"));
        String summaryJson;
        try {
            summaryJson = objectMapper.writeValueAsString(summary(data));
        } catch (Exception e) {
            summaryJson = null;
        }
        return history.recordRun(kind.key(monitorId), portOf(url), kind.runType, actor, actorId, actorTeamId, sourceIp,
                success, summaryJson, stored);
    }

    /** Bu izlemenin son {@value #LIST_LIMIT} çalıştırmasının özeti (yeni → eski). */
    public List<Map<String, Object>> list(Kind kind, Long monitorId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DiagnosticRun d : history.recent(kind.key(monitorId), kind.runType)) {
            Map<String, Object> s = parse(d.getSummary());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("started_at", s.get("started_at") != null ? s.get("started_at")
                    : (d.getExecutedAt() != null ? d.getExecutedAt() + "Z" : null));
            row.put("executed_by", d.getExecutedBy());
            row.put("verdict_code", s.get("verdict_code"));
            row.put("verdict_status", s.get("verdict_status"));
            row.put("monitor_route", s.get("monitor_route"));
            row.put("monitor_http_status", s.get("monitor_http_status"));
            row.put("alternate_route", s.get("alternate_route"));
            row.put("alternate_http_status", s.get("alternate_http_status"));
            row.put("findings", s.get("findings"));
            row.put("duration_ms", s.get("duration_ms"));
            out.add(row);
            if (out.size() >= LIST_LIMIT) break;
        }
        return out;
    }

    /** Saklanan tam sonuç; kayıt yoksa ya da BAŞKA bir izlemeye/türe aitse {@code null} (denetleyici 404 döner). */
    public Map<String, Object> get(Kind kind, Long monitorId, Long runId) {
        DiagnosticRun d;
        try {
            d = history.get(runId);
        } catch (NoSuchElementException e) {
            return null;
        }
        if (d == null || !kind.key(monitorId).equals(d.getDomain()) || !kind.runType.equals(d.getRunType())) return null;
        Map<String, Object> data = parse(d.getResultJson());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run_id", d.getId());
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (!"run_id".equals(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        out.put("executed_by", d.getExecutedBy());
        return out;
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────

    static Map<String, Object> summary(Map<String, Object> data) {
        Map<String, Object> s = new LinkedHashMap<>();
        Object verdict = data.get("verdict");
        s.put("started_at", data.get("started_at"));
        s.put("verdict_code", verdict instanceof Map<?, ?> v ? v.get("code") : null);
        s.put("verdict_status", verdict instanceof Map<?, ?> v ? v.get("status") : null);
        s.put("monitor_route", pathField(data, 0, "route"));
        s.put("monitor_http_status", pathField(data, 0, "http_status"));
        s.put("alternate_route", pathField(data, 1, "route"));
        s.put("alternate_http_status", pathField(data, 1, "http_status"));
        s.put("findings", data.get("findings") instanceof List<?> l ? l.size() : 0);
        s.put("duration_ms", data.get("duration_ms"));
        return s;
    }

    private static Object pathField(Map<String, Object> data, int index, String field) {
        if (data.get("paths") instanceof List<?> paths && paths.size() > index && paths.get(index) instanceof Map<?, ?> p) {
            return p.get(field);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Object o = objectMapper.readValue(json, Map.class);
            return o instanceof Map<?, ?> ? (Map<String, Object>) o : new LinkedHashMap<>();
        } catch (Exception e) {
            log.debug("Sayfa tanılama geçmişi ayrıştırılamadı: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    static Integer portOf(String url) {
        try {
            URI u = URI.create(url == null ? "" : url.trim());
            if (u.getPort() != -1) return u.getPort();
            if ("https".equalsIgnoreCase(u.getScheme())) return 443;
            if ("http".equalsIgnoreCase(u.getScheme())) return 80;
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
