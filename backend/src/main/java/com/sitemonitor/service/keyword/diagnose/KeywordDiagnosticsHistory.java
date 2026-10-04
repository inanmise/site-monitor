package com.sitemonitor.service.keyword.diagnose;

import com.sitemonitor.model.DiagnosticRun;
import com.sitemonitor.model.KeywordMonitor;
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
 * Keyword uçtan uca tanılamasının GEÇMİŞİ (2026-10-04) — mevcut {@code diagnostic_runs} tablosu, şema değişikliği YOK.
 * HTTP tanılama geçmişinin ({@link HttpDiagnosticsHistory}) aynası.
 *
 * <p><b>Mevcut geçmişe karışmaz.</b> Kayıt anahtarı {@code keyword-monitor:<id>}; Yönetim → Tanılama geçmişi alan adına
 * TAM eşleşmeyle okur ve bu anahtarı hiçbir alan adı sorgusu üretemez ({@code :} taşır). Tür {@value
 * KeywordDiagnosticsService#RUN_TYPE}; liste/okuma türe de bakar — HTTP kaydı keyword ucundan, keyword kaydı HTTP
 * ucundan açılamaz.
 *
 * <p><b>Gövde önizlemesi KAYDEDİLMEZ</b> (ürün kararı, HTTP ile aynı): her hop'un {@code response.body.preview}'ı ve
 * anahtar kelime çözümlemesinin görünür metin önizlemesi + eşleşme bağlamları silinir ({@code preview_stored=false});
 * sayılar, ipuçları, meta veri kalır.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordDiagnosticsHistory {

    public static final String KEY_PREFIX = "keyword-monitor:";
    public static final int LIST_LIMIT = 20;

    private final DiagnosticHistoryService history;
    private final ObjectMapper objectMapper;

    public static String key(Long monitorId) { return KEY_PREFIX + monitorId; }

    /** Sonucu (önizlemesiz kopya) geçmişe yazar. @return kayıt kimliği; yazılamazsa {@code null}. */
    public Long save(KeywordMonitor m, Map<String, Object> data, String actor, Long actorId, Long actorTeamId, String sourceIp) {
        Map<String, Object> stored = withoutPreviews(data);
        boolean success = "ok".equals(pathField(data, 0, "outcome"));
        String summaryJson;
        try {
            summaryJson = objectMapper.writeValueAsString(summary(data));
        } catch (Exception e) {
            summaryJson = null;
        }
        return history.recordRun(key(m.getId()), portOf(m.getUrl()), KeywordDiagnosticsService.RUN_TYPE,
                actor, actorId, actorTeamId, sourceIp, success, summaryJson, stored);
    }

    /** Bu izlemenin son {@value #LIST_LIMIT} çalıştırmasının özeti (yeni → eski). */
    public List<Map<String, Object>> list(Long monitorId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DiagnosticRun d : history.recent(key(monitorId), KeywordDiagnosticsService.RUN_TYPE)) {
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
            row.put("occurrences", s.get("occurrences"));
            row.put("duration_ms", s.get("duration_ms"));
            out.add(row);
            if (out.size() >= LIST_LIMIT) break;
        }
        return out;
    }

    /** Saklanan tam sonuç; kayıt yoksa ya da BAŞKA bir izlemeye/türe aitse {@code null} (denetleyici 404 döner). */
    public Map<String, Object> get(Long monitorId, Long runId) {
        DiagnosticRun d;
        try {
            d = history.get(runId);
        } catch (NoSuchElementException e) {
            return null;
        }
        if (d == null || !key(monitorId).equals(d.getDomain()) || !KeywordDiagnosticsService.RUN_TYPE.equals(d.getRunType())) {
            return null;
        }
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
        s.put("occurrences", data.get("keyword") instanceof Map<?, ?> k ? k.get("occurrences") : null);
        s.put("duration_ms", data.get("duration_ms"));
        return s;
    }

    private static Object pathField(Map<String, Object> data, int index, String field) {
        if (data.get("paths") instanceof List<?> paths && paths.size() > index && paths.get(index) instanceof Map<?, ?> p) {
            return p.get(field);
        }
        return null;
    }

    /** Derin kopya: hop gövde önizlemeleri (HTTP kuralı) + görünür metin önizlemesi + eşleşme bağlamları silinir. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutPreviews(Map<String, Object> data) {
        Map<String, Object> copy = HttpDiagnosticsHistory.withoutPreviews(data);
        if (copy.get("keyword") instanceof Map<?, ?> kw) {
            Map<String, Object> k = (Map<String, Object>) kw;
            if (k.containsKey("visible_text_preview")) k.put("visible_text_preview", null);
            if (k.containsKey("contexts")) k.put("contexts", List.of());
            k.put("preview_stored", false);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Object o = objectMapper.readValue(json, Map.class);
            return o instanceof Map<?, ?> ? (Map<String, Object>) o : new LinkedHashMap<>();
        } catch (Exception e) {
            log.debug("Keyword tanılama geçmişi ayrıştırılamadı: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static Integer portOf(String url) {
        try {
            URI u = URI.create(url == null ? "" : url.trim().replace("{timestamp}", "0"));
            if (u.getPort() != -1) return u.getPort();
            if ("https".equalsIgnoreCase(u.getScheme())) return 443;
            if ("http".equalsIgnoreCase(u.getScheme())) return 80;
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
