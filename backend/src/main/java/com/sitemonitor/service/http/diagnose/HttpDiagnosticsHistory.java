package com.sitemonitor.service.http.diagnose;

import com.sitemonitor.model.DiagnosticRun;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.service.DiagnosticHistoryService;
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
 * HTTP uçtan uca tanılamasının GEÇMİŞİ (2026-10-02) — mevcut {@code diagnostic_runs} tablosu, şema değişikliği YOK.
 *
 * <p><b>Mevcut geçmişe karışmaz.</b> Kayıt anahtarı gerçek bir alan adı değil {@code http-monitor:<id>}; Yönetim →
 * Tanılama geçmişi alan adına TAM eşleşmeyle okur ({@code findTop100ByDomainOrderByIdDesc}) ve bu anahtarı hiçbir
 * alan adı sorgusu üretemez ({@code DomainNames.validate} çıktısı {@code :} taşımaz — port kırpılır, biçim yalnız
 * harf/rakam/tire/nokta) — o ekranların gösterdiği DEĞİŞMEDİ. Tür {@value HttpDiagnosticsService#RUN_TYPE}.
 *
 * <p><b>Gövde önizlemesi KAYDEDİLMEZ</b> (ürün kararı): kayıttan önce her hop'un {@code response.body.preview} alanı
 * silinir ({@code preview_stored=false}). Liste özeti {@code summary} sütununda küçük bir JSON olarak tutulur — liste
 * her satırın tam sonucunu ayrıştırmasın.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpDiagnosticsHistory {

    public static final String KEY_PREFIX = "http-monitor:";
    /** Liste uzunluğu (sözleşme: son 20). */
    public static final int LIST_LIMIT = 20;

    private final DiagnosticHistoryService history;
    private final ObjectMapper objectMapper;

    public static String key(Long monitorId) { return KEY_PREFIX + monitorId; }

    /**
     * Sonucu (önizlemesiz kopya) geçmişe yazar.
     *
     * @return kayıt kimliği; yazılamazsa {@code null} (tanılama yanıtı yine döner)
     */
    public Long save(HttpMonitor m, Map<String, Object> data, String actor, Long actorId, Long actorTeamId, String sourceIp) {
        Map<String, Object> stored = withoutPreviews(data);
        boolean success = "ok".equals(pathField(data, 0, "outcome"));
        String summaryJson;
        try {
            summaryJson = objectMapper.writeValueAsString(summary(data));
        } catch (Exception e) {
            summaryJson = null;
        }
        return history.recordRun(key(m.getId()), portOf(m.getUrl()), HttpDiagnosticsService.RUN_TYPE,
                actor, actorId, actorTeamId, sourceIp, success, summaryJson, stored);
    }

    /** Bu izlemenin son {@value #LIST_LIMIT} çalıştırmasının özeti (yeni → eski). */
    public List<Map<String, Object>> list(Long monitorId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DiagnosticRun d : history.recent(key(monitorId), HttpDiagnosticsService.RUN_TYPE)) {
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
        if (d == null || !key(monitorId).equals(d.getDomain()) || !HttpDiagnosticsService.RUN_TYPE.equals(d.getRunType())) {
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

    /** Liste satırı özeti (sözleşme alanları). */
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
        s.put("duration_ms", data.get("duration_ms"));
        return s;
    }

    private static Object pathField(Map<String, Object> data, int index, String field) {
        if (data.get("paths") instanceof List<?> paths && paths.size() > index && paths.get(index) instanceof Map<?, ?> p) {
            return p.get(field);
        }
        return null;
    }

    /** Derin kopya + her hop'ta {@code response.body.preview = null}, {@code preview_stored = false}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutPreviews(Map<String, Object> data) {
        Map<String, Object> copy = (Map<String, Object>) deepCopy(data);
        if (copy.get("paths") instanceof List<?> paths) {
            for (Object p : paths) {
                if (!(p instanceof Map<?, ?> path) || !(path.get("hops") instanceof List<?> hops)) continue;
                for (Object h : hops) {
                    if (h instanceof Map<?, ?> hop && hop.get("response") instanceof Map<?, ?> resp
                            && resp.get("body") instanceof Map<?, ?> body) {
                        Map<String, Object> b = (Map<String, Object>) body;
                        b.put("preview", null);
                        b.put("preview_stored", false);
                    }
                }
            }
        }
        return copy;
    }

    private static Object deepCopy(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) m.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            return m;
        }
        if (node instanceof List<?> list) {
            List<Object> l = new ArrayList<>(list.size());
            for (Object o : list) l.add(deepCopy(o));
            return l;
        }
        return node;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Object o = objectMapper.readValue(json, Map.class);
            return o instanceof Map<?, ?> ? (Map<String, Object>) o : new LinkedHashMap<>();
        } catch (Exception e) {
            log.debug("HTTP tanılama geçmişi ayrıştırılamadı: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private static Integer portOf(String url) {
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
