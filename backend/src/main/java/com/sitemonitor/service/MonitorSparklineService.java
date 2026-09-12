package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * İzleme kartı mini trendi (2026-09-12, zenginleştirme #4 + #14): tür başına TEK toplu sorguyla her
 * monitörün son N saatlik saatlik kovaları (adet / hata / ort. süre) ve son 5 kontrolü (ok / süre / saat).
 *
 * <p>Kart başına ayrı istek YOK (50 kart = 50 istek olurdu); sayfa tür için bir kez çeker, görünürken
 * tazeler. Sorgu NATİF ve kovalı: ham satır taşımaz (dakikalık kontrolde 24 saat × 100 monitör = 144k
 * satır olurdu) — {@code substr(checked_at,1,13)} saat kovası Postgres/H2/SQLite'ta aynı çalışır;
 * son-5 için {@code ROW_NUMBER() OVER (PARTITION BY …)} (Postgres, H2 ≥ 1.4.198).
 *
 * <p>Takım kapsaması ÇAĞIRANDA: bu servis monitör id → takım id haritasını döner, controller
 * {@code SessionScope.canView} ile süzer (IDOR).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MonitorSparklineService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    public static final int MAX_HOURS = 24 * 7;
    static final int LAST_N = 5;

    /** Tür → (kontrol tablosu, monitör tablosu, süre sütunu, hata ifadesi). Sütunlar Hibernate snake_case. */
    record Kind(String table, String monitorTable, String msCol, String failExpr) {}

    static final Map<String, Kind> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("http",      new Kind("http_checks",      "http_monitors",      "response_ms", "CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END"));
        KINDS.put("ping",      new Kind("ping_checks",      "ping_monitors",      "rtt_ms",      "CASE WHEN error IS NOT NULL OR packet_loss >= 100 THEN 1 ELSE 0 END"));
        KINDS.put("port",      new Kind("port_checks",      "port_monitors",      "response_ms", "CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END"));
        KINDS.put("dns",       new Kind("dns_records",      "dns_monitors",       "response_ms", "CASE WHEN changed = TRUE THEN 1 ELSE 0 END"));
        KINDS.put("keyword",   new Kind("keyword_results",  "keyword_monitors",   "response_ms", "CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END"));
        KINDS.put("page",      new Kind("page_checks",      "page_monitors",      "response_ms", "CASE WHEN error IS NOT NULL THEN 1 ELSE 0 END"));
        KINDS.put("pagespeed", new Kind("pagespeed_checks", "pagespeed_monitors", "response_ms", "CASE WHEN error_message IS NOT NULL OR (breached_metrics IS NOT NULL AND breached_metrics <> '') THEN 1 ELSE 0 END"));
        KINDS.put("scripted",  new Kind("scripted_checks",  "scripted_monitors",  "duration_ms", "CASE WHEN error IS NOT NULL OR exit_code <> 0 OR checks_failed > 0 THEN 1 ELSE 0 END"));
    }

    private final JdbcTemplate jdbc;

    public static boolean supports(String type) { return type != null && KINDS.containsKey(type); }

    /** Monitör id → takım id (null = takımsız/envanter türevi). Çağıran görünürlük süzgecini uygular. */
    public Map<Long, Long> monitorTeams(String type) {
        Kind k = KINDS.get(type);
        Map<Long, Long> out = new HashMap<>();
        jdbc.query("SELECT id, team_id FROM " + k.monitorTable(), rs -> {
            long id = rs.getLong("id");
            long tid = rs.getLong("team_id");
            out.put(id, rs.wasNull() ? null : tid);
        });
        return out;
    }

    /**
     * @param ids görünür monitör id'leri (boş → boş harita)
     * @return id → { n, fail, up_pct, buckets:[{t,n,fail,ms}], last:[{at,ok,ms}] }
     */
    public Map<Long, Map<String, Object>> sparklines(String type, int hours, Set<Long> ids) {
        Kind k = KINDS.get(type);
        int h = Math.max(1, Math.min(MAX_HOURS, hours));
        String from = ISO.format(Instant.now().minus(h, ChronoUnit.HOURS));
        Map<Long, Map<String, Object>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        for (Long id : ids) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", 0); m.put("fail", 0); m.put("up_pct", null);
            m.put("buckets", new ArrayList<Map<String, Object>>());
            m.put("last", new ArrayList<Map<String, Object>>());
            out.put(id, m);
        }

        // Saatlik kovalar — tek sorgu, tüm monitörler; id süzgeci bellekte (IN listesi 1000+ olabilir).
        String bucketSql = "SELECT t.monitor_id, t.bucket, COUNT(*) AS n, SUM(t.fail) AS fail, AVG(t.ms) AS ms FROM ("
                + "  SELECT monitor_id, substr(checked_at,1,13) AS bucket, " + k.msCol() + " AS ms, " + k.failExpr() + " AS fail"
                + "    FROM " + k.table() + " WHERE checked_at >= ?"
                + ") t GROUP BY t.monitor_id, t.bucket ORDER BY t.monitor_id, t.bucket";
        jdbc.query(bucketSql, rs -> {
            long id = rs.getLong(1);
            Map<String, Object> m = out.get(id);
            if (m == null) return;
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("t", rs.getString(2));
            int n = rs.getInt(3), fail = rs.getInt(4);
            b.put("n", n); b.put("fail", fail);
            double ms = rs.getDouble(5);
            b.put("ms", rs.wasNull() ? null : Math.round(ms));
            @SuppressWarnings("unchecked") List<Map<String, Object>> buckets = (List<Map<String, Object>>) m.get("buckets");
            buckets.add(b);
            m.put("n", (Integer) m.get("n") + n);
            m.put("fail", (Integer) m.get("fail") + fail);
        }, from);

        // Son 5 kontrol — pencere fonksiyonu; hata verirse (eski motor) yalnız kovalarla dönülür.
        String lastSql = "SELECT monitor_id, checked_at, ms, fail FROM ("
                + "  SELECT monitor_id, checked_at, " + k.msCol() + " AS ms, " + k.failExpr() + " AS fail,"
                + "         ROW_NUMBER() OVER (PARTITION BY monitor_id ORDER BY checked_at DESC) AS rn"
                + "    FROM " + k.table() + " WHERE checked_at >= ?"
                + ") t WHERE t.rn <= " + LAST_N + " ORDER BY monitor_id, checked_at ASC";
        try {
            jdbc.query(lastSql, rs -> {
                long id = rs.getLong(1);
                Map<String, Object> m = out.get(id);
                if (m == null) return;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("at", rs.getString(2));
                long ms = rs.getLong(3);
                c.put("ms", rs.wasNull() ? null : ms);
                c.put("ok", rs.getInt(4) == 0);
                @SuppressWarnings("unchecked") List<Map<String, Object>> last = (List<Map<String, Object>>) m.get("last");
                last.add(c);
            }, from);
        } catch (Exception e) {
            log.debug("Sparkline son-5 sorgusu desteklenmedi ({}): {}", type, e.toString());
        }

        for (Map<String, Object> m : out.values()) {
            int n = (Integer) m.get("n"), fail = (Integer) m.get("fail");
            m.put("up_pct", n == 0 ? null : Math.round(1000.0 * (n - fail) / n) / 10.0);
        }
        return out;
    }
}
