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
 *   <li><b>pg_stat_user_tables</b>: tablo boyutları (tablo / indeks / toplam), okuma/yazma, ölü satır,
 *       sıralı ↔ indeks taraması, son vacuum/analyze — TEK sorgu; "en çok kullanılan" listesi bundan türetilir.</li>
 *   <li><b>pg_stat_activity / pg_database_size</b>: bağlantı sayısı + durum dağılımı (active / idle / idle in
 *       transaction …), uzun süren sorgu/işlem ve kilit bekleme SAYILARI, DB boyutu, yanıt süresi. Başka
 *       oturumların SQL metni / istemci adresi / rol adı DÖNDÜRÜLMEZ (yalnız sayı ve süre).</li>
 *   <li><b>pg_stat_database</b>: önbellek isabeti, commit/rollback, deadlock, geçici dosyalar (tek satır).</li>
 * </ul>
 * Zaman kümeleme Europe/Istanbul; executed_at UTC ISO string'tir (parse edilir).
 *
 * <p><b>Zarif düşüş (2026-09-28):</b> her pg_* sorgusu ayrı korunur; okunamayan kaynak {@code null} döner
 * (ör. {@code db_stats: null}, {@code connections.states: null}) — arayüz bunu "bilinmiyor" diye AÇIKÇA yazar,
 * "sorun yok" diye değil. H2 (test) ve yetkisiz rol aynı yola düşer. Sorgu sayısı tablo/satır sayısından
 * BAĞIMSIZDIR (N+1 yok; bkz. DbAnalyticsServiceTest).
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
    /** getOverview'un belleğe alacağı azami sorgu-geçmişi satırı (bkz. getOverview yorumu). */
    private static final int MAX_HISTORY_ROWS = 50_000;
    /** "Uzun süren sorgu" eşiği (sn) — payload'da {@code connections.long_threshold_s} olarak da döner. */
    static final int LONG_QUERY_S = 60;
    /** pg_* zaman damgaları uygulamanın UTC ISO biçiminde (executed_at ile aynı: Z'siz). */
    private static final String UTC_ISO = "'YYYY-MM-DD\"T\"HH24:MI:SS'";

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
        // Tavanlı çekim (2026-08-20 bellek denetimi): pencere zaman filtreliydi ama LIMIT'siz,
        // oysa sql-history retention'ı 365 gün — 30 günlük pencere tabloyu sınırlamıyordu.
        // En YENİ satırlar korunur; aşağıdaki toplayıcılar ASC beklediği için ters çevrilir.
        List<SqlQueryHistory> rows = new java.util.ArrayList<>(
                historyRepo.findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(
                        since, org.springframework.data.domain.Limit.of(MAX_HISTORY_ROWS)));
        boolean truncated = rows.size() >= MAX_HISTORY_ROWS;
        java.util.Collections.reverse(rows);
        boolean pgss = pgStatStatementsAvailable();

        // Paylaşılan pg_* okumaları TEK kez (eskiden bağlantı sayısı ve DB boyutu iki kez soruluyordu).
        List<Map<String, Object>> sizes = tableSizes();            // null = okunamadı
        Map<String, Object> activity = activity();                 // null = okunamadı
        Long active = activity == null ? null : (Long) activity.get("total");
        String dbSize = scalarStr("SELECT pg_size_pretty(pg_database_size(current_database()))");

        Map<String, Object> out = new LinkedHashMap<>();
        // Kırpma GÖRÜNÜR olmalı: sessizce kesilen bir analitik ekranı "sistemde 50.000 sorgu var"
        // gibi yanlış bir tabloyu doğruymuş gibi gösterir.
        out.put("truncated", truncated);
        out.put("row_limit", MAX_HISTORY_ROWS);
        // Verinin HESAPLANDIĞI an (60 sn önbellek): arayüz "son güncelleme"yi istemci saatinden değil buradan yazar.
        out.put("generated_at", ISO.format(Instant.now()));
        out.put("summary",      buildSummary(rows, win, pgss, active, dbSize, sizes));
        out.put("top_users",    buildTopUsers(rows));
        out.put("top_sql",      pgss ? topSqlFromPgss() : topSqlFromHistory(rows));
        out.put("slowest_sql",  pgss ? slowestFromPgss() : slowestFromHistory(rows));
        out.put("failed",       buildFailed(rows));
        out.put("recent_queries", recentQueries(rows));
        out.put("top_tables",   topTables(sizes));
        out.put("table_sizes",  sizes == null ? List.of() : sizes);
        out.put("series",       buildSeries(rows, win == 1 ? Gran.HOUR : Gran.DAY, win));
        out.put("connections",  connections(active, dbSize, activity));
        out.put("db_stats",     dbStats());
        return out;
    }

    // ── SQL metni maskesi (görüntüleyiciye göre, önbellekten SONRA) ─────────────────
    /**
     * SQL metni, hata iletisi ve çalıştıran kullanıcı adı — yalnız global admin + AUDIT görür (2026-09-28c, B2).
     *
     * <p>Neden: {@code sql_query_history} SQL Oyun Alanı'nın geçmişidir; o ekranın kendi geçmiş ucu
     * ({@code SqlPlaygroundController.history}) yalnız global admin'e ve yalnız KENDİ satırlarına açıktır, denetim
     * kaydına bile gövde yerine 200 karakterlik alıntı yazılır ("gövde kişisel veri içerebilir"). Bu uç ise
     * {@code system_health.read} ile her kademeye açık (2026-09-19 ürün kararı) → sabit değerli SQL
     * ({@code … WHERE email = '…'}), hata iletisindeki değerler ve yöneticinin kullanıcı adı USER'a gidiyordu.
     * pg_stat_statements'in DB-geneli (normalize) sorgu metinleri de AYNI kurala tabi.
     *
     * <p>Kural: bu üç alan HER derinlikte düşer (anahtar hiç yok); sayılar / süreler / zaman kalır; hata iletisi
     * yerine yalnız SINIFI ({@code error_kind}) ve varsa {@code sql_state} döner (arayüzün iletiden türettiği rozetin
     * sunucu karşılığı — dbModel.errorKind / sqlState ile aynı kurallar). Sonuç 60 sn PAYLAŞILAN önbellekte olduğu
     * için yerinde değiştirilmez: kopya üretilir ({@code maskEmployeeIds} deseni).
     */
    public static final java.util.Set<String> SQL_TEXT_FIELDS = java.util.Set.of("sql", "error", "username");
    /** Yanıt bayrağı: true → SQL metni / hata / kullanıcı adı bu görüntüleyici için düşürüldü. */
    public static final String SQL_MASK_FLAG = "sql_masked";

    public static Map<String, Object> maskSqlText(Map<String, Object> overview, boolean visible) {
        if (overview == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        if (visible) out.putAll(overview);
        else overview.forEach((k, v) -> out.put(k, stripSqlText(v)));
        out.put(SQL_MASK_FLAG, !visible);
        return out;
    }

    private static Object stripSqlText(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> c = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                String key = String.valueOf(k);
                if (!SQL_TEXT_FIELDS.contains(key)) c.put(key, stripSqlText(v));
            });
            Object err = m.get("error");
            if (err != null && !String.valueOf(err).isBlank()) {
                c.put("error_kind", errorKind(String.valueOf(err)));
                String state = sqlState(String.valueOf(err));
                if (state != null) c.put("sql_state", state);
            }
            return c;
        }
        if (node instanceof List<?> l) {
            List<Object> c = new ArrayList<>(l.size());
            for (Object v : l) c.add(stripSqlText(v));
            return c;
        }
        return node;
    }

    /** Hata iletisinin sınıfı — arayüzdeki {@code dbModel.errorKind} ile aynı sıra ve kurallar. */
    static String errorKind(String msg) {
        String m = msg == null ? "" : msg.toLowerCase(java.util.Locale.ROOT);
        if (m.isBlank()) return null;
        if (m.contains("timeout") || m.contains("canceling statement") || m.contains("zaman aşımı")) return "timeout";
        if (m.contains("read-only transaction") || m.contains("salt okunur")) return "readonly";
        if (m.contains("permission denied") || m.contains("not allowed") || m.contains("forbidden")
                || m.contains("yetki") || m.contains("izin verilmiyor") || m.contains("yasak")) return "denied";
        if (m.contains("syntax error") || m.contains("sözdizimi")) return "syntax";
        if (m.contains("does not exist") || m.contains("bulunamad") || m.contains("unknown column")
                || m.contains("undefined")) return "missing";
        return "other";
    }

    private static final java.util.regex.Pattern SQL_STATE =
            java.util.regex.Pattern.compile("SQL\\s*STATE\\W{0,3}([0-9A-Z]{5})\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** "SQLSTATE: 57014" / "SQL state [42P01]" → kod; yoksa null (arayüzdeki {@code dbModel.sqlState} ile aynı). */
    static String sqlState(String msg) {
        if (msg == null) return null;
        java.util.regex.Matcher m = SQL_STATE.matcher(msg);
        return m.find() ? m.group(1).toUpperCase(java.util.Locale.ROOT) : null;
    }

    // ── Summary ──────────────────────────────────────────────────────────────────
    private Map<String, Object> buildSummary(List<SqlQueryHistory> rows, int win, boolean pgss,
                                             Long active, String dbSize, List<Map<String, Object>> sizes) {
        long total = rows.size(), failed = 0, sumMs = 0, maxMs = 0, durN = 0;
        long[] durations = new long[rows.size()];
        java.util.Set<String> users = new java.util.HashSet<>();
        for (SqlQueryHistory r : rows) {
            if (!Boolean.TRUE.equals(r.getSuccess())) failed++;
            if (r.getDurationMs() != null) {
                long ms = r.getDurationMs();
                sumMs += ms; durations[(int) durN++] = ms;
                if (ms > maxMs) maxMs = ms;
            }
            users.add(r.getExecutedBy() == null ? "—" : r.getExecutedBy());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queries", total);
        m.put("failed", failed);
        m.put("success_rate", total > 0 ? Math.round((total - failed) * 1000.0 / total) / 10.0 : 100.0);
        m.put("avg_ms", durN > 0 ? Math.round((double) sumMs / durN) : 0);
        m.put("max_ms", maxMs);
        m.put("p95_ms", percentile(durations, (int) durN, 95));
        m.put("user_count", (long) users.size());
        m.put("active_connections", active);
        m.put("db_size", dbSize);
        m.put("table_count", sizes == null ? null : (long) sizes.size());
        m.put("pgss", pgss);
        m.put("days", win);
        return m;
    }

    /** En-yakın-sıra yüzdeliği (ilk {@code n} değer); veri yoksa {@code null} ("0 ms" bir ölçüm değildir). */
    static Long percentile(long[] values, int n, int pct) {
        if (n <= 0) return null;
        long[] v = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(v);
        int idx = (int) Math.ceil(pct / 100.0 * n) - 1;
        return v[Math.max(0, Math.min(n - 1, idx))];
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
            // share_pct: bu ifadenin DB'deki TOPLAM çalışma süresindeki payı (pencere işlevi LIMIT'ten önce, tüm
            // ifadeler üzerinden hesaplanır); hit_pct: paylaşılan tampondan okunan blok oranı. İkisi de aynı
            // görünüm taramasında — ek sorgu yok.
            String sql = "SELECT query, calls, "
                    + "round(mean_exec_time::numeric, 1) AS avg_ms, "
                    + "round(max_exec_time::numeric, 1) AS max_ms, "
                    + "round(total_exec_time::numeric, 0) AS total_ms, rows, "
                    + "round((100.0 * total_exec_time / nullif(sum(total_exec_time) OVER (), 0))::numeric, 1) AS share_pct, "
                    + "round((100.0 * shared_blks_hit / nullif(shared_blks_hit + shared_blks_read, 0))::numeric, 1) AS hit_pct "
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

    // ── En çok kullanılan tablolar — tablo satırlarından TÜRETİLİR (ayrı sorgu yok) ─────
    /** Eski sözleşme korunur: {table_name, reads, writes, row_count}, okumaya göre azalan, ilk {@link #TOP_N}. */
    static List<Map<String, Object>> topTables(List<Map<String, Object>> sizes) {
        if (sizes == null) return List.of();
        return sizes.stream()
                .sorted((x, y) -> Long.compare(asLong(y.get("reads")), asLong(x.get("reads"))))
                .limit(TOP_N)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("table_name", r.get("table_name"));
                    m.put("reads", asLong(r.get("reads")));
                    m.put("writes", asLong(r.get("writes")));
                    m.put("row_count", r.get("row_count"));
                    return m;
                }).toList();
    }

    // ── Tablolar (pg_stat_user_tables) — boyut + kullanım + bakım, TEK sorgu ────────
    /**
     * Tablo başına: boyutlar (tablo / indeks / toplam, okunur + bayt), canlı/ölü satır, sıralı ↔ indeks taraması,
     * okuma/yazma, son (auto)vacuum/(auto)analyze (UTC ISO). {@code dead_pct} ve {@code idx_scan_pct} Java'da
     * hesaplanır (sıfıra bölme → null = bilinmiyor). Okunamazsa {@code null}.
     */
    private List<Map<String, Object>> tableSizes() {
        try {
            // Boyut işlevleri (dosya sistemi stat'ı) tablo başına TEK kez: MATERIALIZED CTE (PG12+) — okunur biçim ve
            // sıralama aynı sayılardan. Yerel ölçüm (99 tablo): ~0,3 sn; sonuç 60 sn önbellekte.
            String sql = """
                WITH t AS MATERIALIZED (
                    SELECT s.*, pg_relation_size(s.relid) AS tb, pg_total_relation_size(s.relid) AS tot,
                           pg_indexes_size(s.relid) AS ib
                    FROM pg_stat_user_tables s)
                SELECT schemaname AS schema_name,
                       relname AS table_name,
                       n_live_tup AS row_count,
                       n_dead_tup AS dead_rows,
                       pg_size_pretty(tb)  AS table_size,
                       pg_size_pretty(tot) AS total_size,
                       pg_size_pretty(ib)  AS index_size,
                       tb  AS table_size_bytes,
                       tot AS total_size_bytes,
                       ib  AS index_size_bytes,
                       coalesce(seq_scan, 0) AS seq_scan,
                       coalesce(idx_scan, 0) AS idx_scan,
                       coalesce(seq_scan, 0) + coalesce(idx_scan, 0) AS reads,
                       coalesce(n_tup_ins, 0) + coalesce(n_tup_upd, 0) + coalesce(n_tup_del, 0) AS writes,
                       n_mod_since_analyze AS mod_since_analyze,
                       to_char(greatest(last_vacuum, last_autovacuum) AT TIME ZONE 'UTC', %1$s)   AS last_vacuum,
                       to_char(greatest(last_analyze, last_autoanalyze) AT TIME ZONE 'UTC', %1$s) AS last_analyze
                FROM t
                ORDER BY tot DESC
                """.formatted(UTC_ISO);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> r : jdbcTemplate.queryForList(sql)) {
                Map<String, Object> m = new LinkedHashMap<>(r);
                long live = asLong(r.get("row_count")), dead = asLong(r.get("dead_rows"));
                long seq = asLong(r.get("seq_scan")), idx = asLong(r.get("idx_scan"));
                m.put("dead_pct", pct(dead, live + dead));
                m.put("idx_scan_pct", pct(idx, seq + idx));
                rows.add(m);
            }
            return rows;
        } catch (Exception e) {
            log.warn("tableSizes failed: {}", e.getMessage());
            return null;
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
    private Map<String, Object> connections(Long active, String dbSize, Map<String, Object> activity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", active);
        m.put("max", scalarLong("SELECT setting::bigint FROM pg_settings WHERE name = 'max_connections'"));
        m.put("db_size", dbSize);
        m.put("response_ms", measureResponseMs());
        // Durum dağılımı + uzun süren iş sinyalleri. Okunamadıysa null → arayüz "bilinmiyor" yazar.
        m.put("states", activity == null ? null : activity.get("states"));
        m.put("idle_in_tx", activity == null ? null : activity.get("idle_in_tx"));
        m.put("long_queries", activity == null ? null : activity.get("long_queries"));
        m.put("lock_waits", activity == null ? null : activity.get("lock_waits"));
        m.put("longest_query_s", activity == null ? null : activity.get("longest_query_s"));
        m.put("longest_xact_s", activity == null ? null : activity.get("longest_xact_s"));
        m.put("long_threshold_s", LONG_QUERY_S);
        return m;
    }

    /**
     * pg_stat_activity'yi (bu DB) duruma göre TEK sorguda toplar: sayı, en uzun işlem/sorgu yaşı, uzun süren sorgu ve
     * kilit bekleme sayısı. Başka rolün oturumunda PostgreSQL {@code state}'i gizler (pg_read_all_stats yoksa) → o
     * satırlar "unknown" kovasına düşer; "idle" sayılmaz. SQL metni / istemci adresi / rol adı OKUNMAZ.
     */
    private Map<String, Object> activity() {
        try {
            String sql = """
                SELECT coalesce(state, 'unknown') AS state,
                       count(*) AS n,
                       max(extract(epoch FROM (now() - xact_start))) AS max_xact_s,
                       max(CASE WHEN state = 'active' THEN extract(epoch FROM (now() - query_start)) END) AS max_query_s,
                       sum(CASE WHEN state = 'active' AND query_start < now() - interval '%d seconds' THEN 1 ELSE 0 END) AS long_queries,
                       sum(CASE WHEN wait_event_type = 'Lock' THEN 1 ELSE 0 END) AS lock_waits
                FROM pg_stat_activity
                WHERE datname = current_database()
                GROUP BY 1
                ORDER BY 2 DESC
                """.formatted(LONG_QUERY_S);
            List<Map<String, Object>> states = new ArrayList<>();
            long total = 0, idleTx = 0, longQ = 0, locks = 0, maxXact = 0, maxQuery = 0;
            for (Map<String, Object> r : jdbcTemplate.queryForList(sql)) {
                String state = String.valueOf(r.get("state"));
                long n = asLong(r.get("n"));
                total += n;
                if (state.startsWith("idle in transaction")) idleTx += n;
                longQ += asLong(r.get("long_queries"));
                locks += asLong(r.get("lock_waits"));
                maxXact = Math.max(maxXact, asLong(r.get("max_xact_s")));
                maxQuery = Math.max(maxQuery, asLong(r.get("max_query_s")));
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("state", state);
                s.put("count", n);
                states.add(s);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", total);
            m.put("states", states);
            m.put("idle_in_tx", idleTx);
            m.put("long_queries", longQ);
            m.put("lock_waits", locks);
            m.put("longest_query_s", maxQuery);
            m.put("longest_xact_s", maxXact);
            return m;
        } catch (Exception e) {
            log.warn("pg_stat_activity okunamadı: {}", e.getMessage());
            return null;
        }
    }

    /**
     * pg_stat_database (bu DB, tek satır): önbellek isabeti, commit/rollback, deadlock, geçici dosyalar. Sayaçlar
     * {@code stats_reset}'ten beri BİRİKİMLİDİR. Okunamazsa {@code null} — "sorun yok" değil, "bilinmiyor".
     */
    private Map<String, Object> dbStats() {
        try {
            String sql = """
                SELECT blks_hit, blks_read, xact_commit, xact_rollback, deadlocks, temp_files, temp_bytes,
                       pg_size_pretty(temp_bytes) AS temp_size,
                       to_char(stats_reset AT TIME ZONE 'UTC', %s) AS stats_reset
                FROM pg_stat_database WHERE datname = current_database()
                """.formatted(UTC_ISO);
            List<Map<String, Object>> res = jdbcTemplate.queryForList(sql);
            if (res.isEmpty()) return null;
            Map<String, Object> r = res.get(0);
            long hit = asLong(r.get("blks_hit")), read = asLong(r.get("blks_read"));
            long commit = asLong(r.get("xact_commit")), rollback = asLong(r.get("xact_rollback"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cache_hit_pct", pct(hit, hit + read));
            m.put("blks_hit", hit);
            m.put("blks_read", read);
            m.put("xact_commit", commit);
            m.put("xact_rollback", rollback);
            m.put("rollback_pct", pct(rollback, commit + rollback));
            m.put("deadlocks", asLong(r.get("deadlocks")));
            m.put("temp_files", asLong(r.get("temp_files")));
            m.put("temp_bytes", asLong(r.get("temp_bytes")));
            m.put("temp_size", r.get("temp_size"));
            m.put("stats_reset", r.get("stats_reset"));
            return m;
        } catch (Exception e) {
            log.warn("pg_stat_database okunamadı: {}", e.getMessage());
            return null;
        }
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
    /** JDBC sayısı (Long / BigDecimal / Double …) → long; null/sayı-dışı → 0. */
    static long asLong(Object o) {
        return o instanceof Number n ? Math.round(n.doubleValue()) : 0L;
    }
    /** part / whole yüzdesi (1 ondalık); payda 0 → null (oran tanımsız = bilinmiyor, "%0" değil). */
    static Double pct(long part, long whole) {
        return whole > 0 ? Math.round(part * 1000.0 / whole) / 10.0 : null;
    }
    private Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return Instant.parse(iso.endsWith("Z") ? iso : iso + "Z"); }
        catch (Exception e) { return null; }
    }
}
