package com.sitemonitor.service;

import com.sitemonitor.model.SqlQueryHistory;
import com.sitemonitor.repository.SqlQueryHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sistem Sağlığı "Veritabanı" bölümü için executive DB analitiği.
 *
 * <p>Kaynaklar:
 * <ul>
 *   <li><b>sql_query_history</b> (SQL Playground geçmişi): top kullanıcılar, sorgu zaman serisi,
 *       hatalı sorgular, başarı oranı, süreler. Yalnız admin'in manuel SELECT/WITH sorgularını kapsar.</li>
 *   <li><b>pg_stat_statements</b> (varsa): DB-GENELİ top/yavaş SQL (uygulamanın tüm sorguları). Eklenti
 *       yoksa sql_query_history'ye düşülür.</li>
 *   <li><b>pg_stat_user_tables</b>: en çok kullanılan tablolar (okuma/yazma) + tablo boyutları.</li>
 *   <li><b>pg_stat_activity / pg_database_size</b>: aktif bağlantı, DB boyutu, yanıt süresi.</li>
 * </ul>
 * Zaman kümeleme Europe/Istanbul; executed_at UTC ISO string'tir (parse edilir).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DbAnalyticsService {

    private final JdbcTemplate jdbcTemplate;
    private final SqlQueryHistoryRepository historyRepo;

    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final int TOP_N = 10;
    private static final int FAILED_N = 25;
    private static final int RECENT_N = 200;      // "Sorgu" kartı drill-down: son ham sorgu satırları
    private static final int SQL_PREVIEW = 240;   // SQL metni gösterim kısaltması
    private static final long DAY = 86_400L;

    private enum Gran { DAY, HOUR }

    /** Açılışta pg_stat_statements'i (varsa) best-effort etkinleştir. shared_preload_libraries'de
     *  değilse CREATE EXTENSION hata verir → loglanır, sql_query_history fallback'i kullanılır. */
    @EventListener(ApplicationReadyEvent.class)
    public void tryEnablePgStatStatements() {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
            log.info("pg_stat_statements hazır — DB-geneli SQL istatistikleri etkin");
        } catch (Exception e) {
            log.info("pg_stat_statements etkin değil (shared_preload_libraries gerekir); "
                    + "sql_query_history fallback kullanılacak: {}", e.getMessage());
        }
    }

    /** Tek payload — frontend tek çağrı yapar; days = pencere (1/7/30).
     *  60 sn cache (pencereye göre): pg_stat çağrıları + sql_query_history taraması her açılışta değil. */
    @org.springframework.cache.annotation.Cacheable("db-analytics-overview")
    public Map<String, Object> getOverview(int days) {
        int win = days <= 1 ? 1 : days >= 30 ? 30 : 7;
        String since = ISO.format(Instant.now().minusSeconds((long) win * DAY));
        List<SqlQueryHistory> rows = historyRepo.findByExecutedAtGreaterThanEqualOrderByExecutedAtAsc(since);
        boolean pgss = pgStatStatementsAvailable();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary",      buildSummary(rows, win, pgss));
        out.put("top_users",    buildTopUsers(rows));
        out.put("top_sql",      pgss ? topSqlFromPgss() : topSqlFromHistory(rows));
        out.put("slowest_sql",  pgss ? slowestFromPgss() : slowestFromHistory(rows));
        out.put("failed",       buildFailed(rows));
        out.put("recent_queries", recentQueries(rows));
        out.put("top_tables",   topTables());
        out.put("table_sizes",  tableSizes());
        out.put("series",       buildSeries(rows, win == 1 ? Gran.HOUR : Gran.DAY, win));
        out.put("connections",  connections());
        return out;
    }

    // ── Summary ──────────────────────────────────────────────────────────────────
    private Map<String, Object> buildSummary(List<SqlQueryHistory> rows, int win, boolean pgss) {
        long total = rows.size(), failed = 0, sumMs = 0, maxMs = 0, durN = 0;
        for (SqlQueryHistory r : rows) {
            if (!Boolean.TRUE.equals(r.getSuccess())) failed++;
            if (r.getDurationMs() != null) { sumMs += r.getDurationMs(); durN++; if (r.getDurationMs() > maxMs) maxMs = r.getDurationMs(); }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queries", total);
        m.put("failed", failed);
        m.put("success_rate", total > 0 ? Math.round((total - failed) * 1000.0 / total) / 10.0 : 100.0);
        m.put("avg_ms", durN > 0 ? Math.round((double) sumMs / durN) : 0);
        m.put("max_ms", maxMs);
        m.put("active_connections", scalarLong("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"));
        m.put("db_size", scalarStr("SELECT pg_size_pretty(pg_database_size(current_database()))"));
        m.put("table_count", scalarLong("SELECT count(*) FROM pg_stat_user_tables"));
        m.put("pgss", pgss);
        m.put("days", win);
        return m;
    }

    // ── Top kullanıcılar (sql_query_history) ──────────────────────────────────────
    private List<Map<String, Object>> buildTopUsers(List<SqlQueryHistory> rows) {
        Map<String, long[]> agg = new LinkedHashMap<>();   // user → [queries, sumMs, durN, failed]
        Map<String, String> last = new LinkedHashMap<>();
        for (SqlQueryHistory r : rows) {
            String u = r.getExecutedBy() == null ? "—" : r.getExecutedBy();
            long[] a = agg.computeIfAbsent(u, k -> new long[4]);
            a[0]++;
            if (r.getDurationMs() != null) { a[1] += r.getDurationMs(); a[2]++; }
            if (!Boolean.TRUE.equals(r.getSuccess())) a[3]++;
            last.merge(u, r.getExecutedAt(), (c, n) -> n != null && n.compareTo(c) > 0 ? n : c);
        }
        return agg.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
                .limit(TOP_N)
                .map(e -> {
                    long[] a = e.getValue();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username", e.getKey());
                    m.put("queries", a[0]);
                    m.put("avg_ms", a[2] > 0 ? Math.round((double) a[1] / a[2]) : 0);
                    m.put("failed", a[3]);
                    m.put("last", last.get(e.getKey()));
                    return m;
                }).toList();
    }

    // ── Top / yavaş SQL — pg_stat_statements (DB-geneli) ──────────────────────────
    private List<Map<String, Object>> topSqlFromPgss() {
        return pgssQuery("ORDER BY calls DESC");
    }
    private List<Map<String, Object>> slowestFromPgss() {
        return pgssQuery("ORDER BY mean_exec_time DESC");
    }
    private List<Map<String, Object>> pgssQuery(String orderBy) {
        try {
            String sql = "SELECT query, calls, "
                    + "round(mean_exec_time::numeric, 1) AS avg_ms, "
                    + "round(max_exec_time::numeric, 1) AS max_ms, "
                    + "round(total_exec_time::numeric, 0) AS total_ms, rows "
                    + "FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) "
                    + orderBy + " LIMIT " + TOP_N;
            List<Map<String, Object>> res = jdbcTemplate.queryForList(sql);
            for (Map<String, Object> r : res) r.put("sql", preview((String) r.remove("query")));
            return res;
        } catch (Exception e) {
            log.warn("pg_stat_statements query failed ({}): {}", orderBy, e.getMessage());
            return List.of();
        }
    }

    // ── Top / yavaş SQL — fallback (sql_query_history) ────────────────────────────
    private List<Map<String, Object>> topSqlFromHistory(List<SqlQueryHistory> rows) {
        Map<String, long[]> agg = new LinkedHashMap<>();   // sql → [calls, sumMs, durN, maxMs]
        Map<String, String> last = new LinkedHashMap<>();
        for (SqlQueryHistory r : rows) {
            String key = norm(r.getSqlText());
            long[] a = agg.computeIfAbsent(key, k -> new long[4]);
            a[0]++;
            if (r.getDurationMs() != null) { a[1] += r.getDurationMs(); a[2]++; if (r.getDurationMs() > a[3]) a[3] = r.getDurationMs(); }
            last.merge(key, r.getExecutedAt(), (c, n) -> n != null && n.compareTo(c) > 0 ? n : c);
        }
        return agg.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue()[0], x.getValue()[0]))
                .limit(TOP_N)
                .map(e -> {
                    long[] a = e.getValue();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sql", preview(e.getKey()));
                    m.put("calls", a[0]);
                    m.put("avg_ms", a[2] > 0 ? Math.round((double) a[1] / a[2]) : 0);
                    m.put("max_ms", a[3]);
                    m.put("last", last.get(e.getKey()));
                    return m;
                }).toList();
    }
    private List<Map<String, Object>> slowestFromHistory(List<SqlQueryHistory> rows) {
        return rows.stream()
                .filter(r -> r.getDurationMs() != null)
                .sorted((x, y) -> Long.compare(y.getDurationMs(), x.getDurationMs()))
                .limit(TOP_N)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sql", preview(r.getSqlText()));
                    m.put("duration_ms", r.getDurationMs());
                    m.put("username", r.getExecutedBy());
                    m.put("time", r.getExecutedAt());
                    m.put("rows", r.getRowCount());
                    return m;
                }).toList();
    }

    // ── Hatalı sorgular (sql_query_history) ───────────────────────────────────────
    private List<Map<String, Object>> buildFailed(List<SqlQueryHistory> rows) {
        return rows.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getSuccess()))
                .sorted((x, y) -> nullSafe(y.getExecutedAt()).compareTo(nullSafe(x.getExecutedAt())))
                .limit(FAILED_N)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("time", r.getExecutedAt());
                    m.put("username", r.getExecutedBy());
                    m.put("sql", preview(r.getSqlText()));
                    m.put("error", r.getErrorMessage());
                    return m;
                }).toList();
    }

    // ── Son sorgular (sql_query_history ham satırlar) — "Sorgu" kartı detayı ──────
    private List<Map<String, Object>> recentQueries(List<SqlQueryHistory> rows) {
        return rows.stream()
                .sorted((x, y) -> nullSafe(y.getExecutedAt()).compareTo(nullSafe(x.getExecutedAt())))
                .limit(RECENT_N)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("time", r.getExecutedAt());
                    m.put("username", r.getExecutedBy());
                    m.put("sql", preview(r.getSqlText()));
                    m.put("duration_ms", r.getDurationMs());
                    m.put("success", r.getSuccess());
                    m.put("rows", r.getRowCount());
                    return m;
                }).toList();
    }

    // ── En çok kullanılan tablolar (pg_stat_user_tables) ──────────────────────────
    private List<Map<String, Object>> topTables() {
        try {
            String sql = """
                SELECT relname AS table_name,
                       coalesce(seq_scan,0) + coalesce(idx_scan,0) AS reads,
                       coalesce(n_tup_ins,0) + coalesce(n_tup_upd,0) + coalesce(n_tup_del,0) AS writes,
                       n_live_tup AS row_count
                FROM pg_stat_user_tables
                ORDER BY (coalesce(seq_scan,0) + coalesce(idx_scan,0)) DESC
                LIMIT %d
                """.formatted(TOP_N);
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.warn("topTables failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Tablo boyutları (pg_stat_user_tables) ─────────────────────────────────────
    private List<Map<String, Object>> tableSizes() {
        try {
            String sql = """
                SELECT relname AS table_name,
                       n_live_tup AS row_count,
                       pg_size_pretty(pg_relation_size(relid))       AS table_size,
                       pg_size_pretty(pg_total_relation_size(relid)) AS total_size,
                       pg_relation_size(relid)                       AS table_size_bytes,
                       pg_total_relation_size(relid)                 AS total_size_bytes
                FROM pg_stat_user_tables
                ORDER BY pg_total_relation_size(relid) DESC
                """;
            return jdbcTemplate.queryForList(sql);
        } catch (Exception e) {
            log.warn("tableSizes failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Sorgu zaman serisi (Istanbul kovaları) ────────────────────────────────────
    private List<Map<String, Object>> buildSeries(List<SqlQueryHistory> rows, Gran g, int win) {
        LinkedHashMap<String, long[]> buckets = new LinkedHashMap<>();   // ts → [count, sumMs, durN, failed]
        for (ZonedDateTime z : bucketStarts(g, win)) buckets.put(tsOf(z), new long[4]);
        for (SqlQueryHistory r : rows) {
            Instant t = parse(r.getExecutedAt());
            if (t == null) continue;
            long[] c = buckets.get(tsOf(bucketStart(t.atZone(ZONE), g)));
            if (c == null) continue;
            c[0]++;
            if (r.getDurationMs() != null) { c[1] += r.getDurationMs(); c[2]++; }
            if (!Boolean.TRUE.equals(r.getSuccess())) c[3]++;
        }
        List<Map<String, Object>> out = new ArrayList<>(buckets.size());
        for (var e : buckets.entrySet()) {
            long[] c = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", e.getKey());
            m.put("count", c[0]);
            m.put("avg_ms", c[2] > 0 ? Math.round((double) c[1] / c[2]) : 0);
            m.put("failed", c[3]);
            out.add(m);
        }
        return out;
    }

    // ── Bağlantılar / DB ──────────────────────────────────────────────────────────
    private Map<String, Object> connections() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", scalarLong("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"));
        m.put("max", scalarLong("SELECT setting::bigint FROM pg_settings WHERE name = 'max_connections'"));
        m.put("db_size", scalarStr("SELECT pg_size_pretty(pg_database_size(current_database()))"));
        m.put("response_ms", measureResponseMs());
        return m;
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────
    private boolean pgStatStatementsAvailable() {
        try {
            Long n = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_extension WHERE extname = 'pg_stat_statements'", Long.class);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private long measureResponseMs() {
        try {
            long t0 = System.currentTimeMillis();
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return System.currentTimeMillis() - t0;
        } catch (Exception e) {
            return -1;
        }
    }

    private List<ZonedDateTime> bucketStarts(Gran g, int win) {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        List<ZonedDateTime> list = new ArrayList<>();
        if (g == Gran.HOUR) {
            ZonedDateTime base = now.withMinute(0).withSecond(0).withNano(0);
            for (int i = 23; i >= 0; i--) list.add(base.minusHours(i));
        } else {
            ZonedDateTime base = now.toLocalDate().atStartOfDay(ZONE);
            for (int i = win - 1; i >= 0; i--) list.add(base.minusDays(i));
        }
        return list;
    }
    private ZonedDateTime bucketStart(ZonedDateTime z, Gran g) {
        return g == Gran.HOUR ? z.withMinute(0).withSecond(0).withNano(0) : z.toLocalDate().atStartOfDay(ZONE);
    }
    private String tsOf(ZonedDateTime z) { return ISO.format(z.toInstant()); }

    private Long scalarLong(String sql) {
        try { return jdbcTemplate.queryForObject(sql, Long.class); }
        catch (Exception e) { return null; }
    }
    private String scalarStr(String sql) {
        try { return jdbcTemplate.queryForObject(sql, String.class); }
        catch (Exception e) { return null; }
    }
    private String preview(String sql) {
        if (sql == null) return "";
        String s = sql.replaceAll("\\s+", " ").trim();
        return s.length() > SQL_PREVIEW ? s.substring(0, SQL_PREVIEW) + "…" : s;
    }
    private String norm(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
    }
    private String nullSafe(String s) { return s != null ? s : ""; }
    private Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Instant.parse(iso.endsWith("Z") ? iso : iso + "Z"); }
        catch (Exception e) { return null; }
    }
}
