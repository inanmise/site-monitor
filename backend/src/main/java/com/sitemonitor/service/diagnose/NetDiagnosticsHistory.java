package com.sitemonitor.service.diagnose;

import com.sitemonitor.model.DiagnosticRun;
import com.sitemonitor.service.DiagnosticHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Ping / Port / DNS uçtan uca tanılamasının GEÇMİŞİ (2026-10-05) — mevcut {@code diagnostic_runs} tablosu, şema değişikliği
 * YOK. HTTP/keyword tanılama geçmişinin aynası.
 *
 * <p><b>Mevcut geçmişe karışmaz.</b> Anahtar {@code ping-monitor:<id>} / {@code port-monitor:<id>} /
 * {@code dns-monitor:<id>}: Yönetim → Tanılama geçmişi alan adına TAM eşleşmeyle okur ve bu anahtarları hiçbir alan adı
 * sorgusu üretemez ({@code :} taşır). Tür ({@code PING_DIAG|PORT_DIAG|DNS_DIAG}) de okunur — bir türün ucu başka türün
 * kaydını (aynı numaralı izleme olsa bile) AÇAMAZ.
 *
 * <p><b>Önizleme kaydedilmez</b> (HTTP gövde önizlemesi kararıyla aynı): banner adımının metin + onaltılık önizlemesi
 * geçmişe yazılmadan silinir ({@code preview_stored=false}); döküm yalnız 80 karakterlik banner kesitini taşır (kontrol
 * kaydının da sakladığı uzunluk).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NetDiagnosticsHistory {

    public static final int LIST_LIMIT = 20;

    /** Tanılama türleri: anahtar öneki + {@code run_type}. */
    public enum Kind {
        PING("ping-monitor:", PingDiagnosticsService.RUN_TYPE),
        PORT("port-monitor:", PortDiagnosticsService.RUN_TYPE),
        DNS("dns-monitor:", DnsDiagnosticsService.RUN_TYPE);

        public final String prefix;
        public final String runType;

        Kind(String prefix, String runType) {
            this.prefix = prefix;
            this.runType = runType;
        }

        public String key(Long monitorId) { return prefix + monitorId; }
    }

    /** İzleme tanılamalarının TÜM run_type'ları — yönetim (sertifika/alan adı) tanılama geçmişi bunları göstermez.
     *  (Sayfa Bütünlüğü / Sayfa Hızı, 2026-10-05: {@code PAGE_DIAG} / {@code PAGESPEED_DIAG}.) */
    public static final Set<String> MONITOR_RUN_TYPES = Set.of(
            "HTTP_DIAG", "KEYWORD_DIAG", PingDiagnosticsService.RUN_TYPE, PortDiagnosticsService.RUN_TYPE,
            DnsDiagnosticsService.RUN_TYPE,
            com.sitemonitor.service.page.diagnose.PageDiagnosticsService.RUN_TYPE,
            com.sitemonitor.service.page.diagnose.PageSpeedDiagnosticsService.RUN_TYPE);

    public static boolean isMonitorRun(DiagnosticRun d) {
        return d != null && d.getRunType() != null && MONITOR_RUN_TYPES.contains(d.getRunType());
    }

    private final DiagnosticHistoryService history;
    private final ObjectMapper objectMapper;

    /** Sonucu (önizlemesiz kopya) geçmişe yazar. @return kayıt kimliği; yazılamazsa {@code null}. */
    public Long save(Kind kind, Long monitorId, Integer port, Map<String, Object> data, String actor, Long actorId,
                     Long actorTeamId, String sourceIp) {
        Map<String, Object> stored = withoutPreviews(data);
        Object verdict = data.get("verdict");
        boolean success = verdict instanceof Map<?, ?> v && "ok".equals(v.get("status"));
        String summaryJson;
        try {
            summaryJson = objectMapper.writeValueAsString(summary(data));
        } catch (Exception e) {
            summaryJson = null;
        }
        return history.recordRun(kind.key(monitorId), port, kind.runType, actor, actorId, actorTeamId, sourceIp, success,
                summaryJson, stored);
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
            row.put("route", s.get("route"));
            row.put("target", s.get("target"));
            row.put("findings", s.get("findings"));
            row.put("traceroute", s.get("traceroute"));
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
        s.put("route", data.get("route") instanceof Map<?, ?> r ? r.get("own") : null);
        Object target = data.get("target");
        if (target instanceof Map<?, ?> t) {
            StringBuilder sb = new StringBuilder(String.valueOf(t.get("host")));
            if (t.get("port") != null) sb.append(':').append(t.get("port"));
            if (t.get("record_type") != null) sb.append(' ').append(t.get("record_type"));
            if (t.get("protocol") != null && t.get("port") != null) sb.append(" (").append(t.get("protocol")).append(')');
            s.put("target", sb.toString());
        }
        s.put("findings", data.get("findings") instanceof List<?> l ? l.size() : 0);
        s.put("traceroute", data.get("options") instanceof Map<?, ?> o && Boolean.TRUE.equals(o.get("traceroute")));
        s.put("duration_ms", data.get("duration_ms"));
        return s;
    }

    /** Derin kopya: banner önizlemeleri silinir (yol adımları dâhil). Canlı yanıt değişmez. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withoutPreviews(Map<String, Object> data) {
        Map<String, Object> copy = (Map<String, Object>) deepCopy(data);
        stripSteps(copy.get("steps"));
        if (copy.get("paths") instanceof List<?> paths) {
            for (Object p : paths) if (p instanceof Map<?, ?> pm) stripSteps(pm.get("steps"));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static void stripSteps(Object steps) {
        if (!(steps instanceof List<?> list)) return;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> step) || !"banner".equals(step.get("key"))) continue;
            if (step.get("detail") instanceof Map<?, ?> d) {
                Map<String, Object> dm = (Map<String, Object>) d;
                if (dm.containsKey("text_preview")) dm.put("text_preview", null);
                if (dm.containsKey("hex_preview")) dm.put("hex_preview", null);
                dm.put("preview_stored", false);
            }
        }
    }

    private static Object deepCopy(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> c = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) c.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
            return c;
        }
        if (o instanceof List<?> l) {
            List<Object> c = new ArrayList<>(l.size());
            for (Object x : l) c.add(deepCopy(x));
            return c;
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            Object o = objectMapper.readValue(json, Map.class);
            return o instanceof Map<?, ?> ? (Map<String, Object>) o : new LinkedHashMap<>();
        } catch (Exception e) {
            log.debug("Ağ tanılama geçmişi ayrıştırılamadı: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }
}
