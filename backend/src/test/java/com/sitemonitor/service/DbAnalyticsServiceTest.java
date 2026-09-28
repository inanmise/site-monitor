package com.sitemonitor.service;

import com.sitemonitor.model.SqlQueryHistory;
import com.sitemonitor.repository.SqlQueryHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DbAnalyticsService — pg_stat_statements YOK (fallback) yolunda sql_query_history toplulaştırması.
 * Harici pg_* sorguları mock JdbcTemplate ile null/boş döner; history-tabanlı paneller hesaplanır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DbAnalyticsServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock SqlQueryHistoryRepository historyRepo;

    private DbAnalyticsService service;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new DbAnalyticsService(jdbcTemplate, historyRepo);
    }

    private SqlQueryHistory q(String user, String sql, Long durMs, boolean success, Instant when) {
        SqlQueryHistory h = new SqlQueryHistory();
        h.setExecutedBy(user);
        h.setSqlText(sql);
        h.setDurationMs(durMs);
        h.setSuccess(success);
        h.setExecutedAt(ISO.format(when));
        if (!success) h.setErrorMessage("boom");
        return h;
    }

    @Test
    @DisplayName("getOverview (fallback): summary + top_users + top_sql + failed + series")
    @SuppressWarnings("unchecked")
    void getOverview_fallbackAggregates() {
        Instant now = Instant.now();
        List<SqlQueryHistory> rows = List.of(
                q("alice", "SELECT 1", 10L, true, now),
                q("alice", "SELECT 1", 20L, true, now),
                q("bob",   "SELECT 2", 5L,  false, now));
        when(historyRepo.findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(any(), any())).thenReturn(rows);
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Long.class))).thenReturn(0L); // pgss off

        Map<String, Object> d = service.getOverview(7);
        Map<String, Object> sum = (Map<String, Object>) d.get("summary");

        assertThat(((Number) sum.get("queries")).longValue()).isEqualTo(3);
        assertThat(((Number) sum.get("failed")).longValue()).isEqualTo(1);
        assertThat((Boolean) sum.get("pgss")).isFalse();
        assertThat(((Number) sum.get("avg_ms")).longValue()).isEqualTo(12); // (10+20+5)/3 = 11.67→12
        assertThat((List<?>) d.get("top_users")).hasSize(2);                 // alice, bob
        assertThat((List<?>) d.get("top_sql")).hasSize(2);                   // "SELECT 1", "SELECT 2"
        assertThat((List<?>) d.get("failed")).hasSize(1);                    // bob
        assertThat((List<?>) d.get("recent_queries")).hasSize(3);            // 3 ham satır (SQL metinli)
        // 7 günlük seri → 7 günlük kova; gün sayısı en az 7
        assertThat((List<?>) d.get("series")).hasSizeGreaterThanOrEqualTo(7);
    }

    @Test
    @DisplayName("getOverview: gün penceresi 1/7/30 dışına taşmaz")
    @SuppressWarnings("unchecked")
    void getOverview_windowClamped() {
        when(historyRepo.findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(any(), any())).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Long.class))).thenReturn(0L);

        Map<String, Object> sum = (Map<String, Object>) service.getOverview(999).get("summary");
        assertThat(((Number) sum.get("days")).intValue()).isEqualTo(30);     // 999 → 30
        Map<String, Object> sum1 = (Map<String, Object>) service.getOverview(1).get("summary");
        assertThat(((Number) sum1.get("days")).intValue()).isEqualTo(1);
    }

    /**
     * Satır tavanı (2026-08-20 bellek denetimi). getOverview zaman-pencereli ama LIMIT'siz
     * çekiyordu; sql-history retention'ı 365 gün olduğundan 30 günlük pencere tabloyu
     * sınırlamıyordu ve tüm satırlar (+ 9 toplayıcının türevleri + @Cacheable payload'ı)
     * aynı anda bellekte oluyordu.
     */
    @Test
    @DisplayName("getOverview: repo'ya TAVANLI ve en-yeni-önce sorar; tavana değince payload truncated taşır")
    @SuppressWarnings("unchecked")
    void getOverview_capsHistoryRows() {
        Instant now = Instant.now();
        // Tavan kadar satır dön → truncated=true beklenir.
        List<SqlQueryHistory> many = new java.util.ArrayList<>();
        for (int i = 0; i < 50_000; i++) many.add(q("u" + (i % 3), "SELECT " + (i % 5), 1L, true, now));
        when(historyRepo.findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(any(), any())).thenReturn(many);
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Long.class))).thenReturn(0L);

        Map<String, Object> d = service.getOverview(30);

        ArgumentCaptor<org.springframework.data.domain.Limit> limitCap =
                ArgumentCaptor.forClass(org.springframework.data.domain.Limit.class);
        verify(historyRepo).findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(any(), limitCap.capture());
        assertThat(limitCap.getValue().max()).isEqualTo(50_000);
        assertThat((Boolean) d.get("truncated")).isTrue();
        assertThat(((Number) d.get("row_limit")).intValue()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("getOverview: tavanın altında kalan veri truncated=false döner")
    void getOverview_belowCap_notTruncated() {
        when(historyRepo.findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(any(), any()))
                .thenReturn(List.of(q("alice", "SELECT 1", 10L, true, Instant.now())));
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Long.class))).thenReturn(0L);

        assertThat((Boolean) service.getOverview(7).get("truncated")).isFalse();
    }

    // ── 2026-09-28 zenginleştirme: p95, durum dağılımı, pg_stat_database, tablo sağlığı ─────────────────────

    /** Değiştirilebilir satır (servis pgss satırından `query`yi söküp `sql` yazıyor). */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** Gerçek PostgreSQL'in yokluk/yetki hatası: Spring bunu BadSqlGrammarException olarak fırlatır. */
    private static org.springframework.jdbc.BadSqlGrammarException missing(String relation) {
        return new org.springframework.jdbc.BadSqlGrammarException("StatementCallback", "SELECT …",
                new java.sql.SQLException("ERROR: relation \"" + relation + "\" does not exist", "42P01"));
    }

    private static List<Map<String, Object>> activityRows() {
        return List.of(
                row("state", "idle", "n", 10L, "max_xact_s", null, "max_query_s", null, "long_queries", 0L, "lock_waits", 0L),
                row("state", "active", "n", 3L, "max_xact_s", new java.math.BigDecimal("75.4"),
                        "max_query_s", new java.math.BigDecimal("75.4"), "long_queries", 1L, "lock_waits", 0L),
                row("state", "idle in transaction", "n", 2L, "max_xact_s", 400.2, "max_query_s", null,
                        "long_queries", 0L, "lock_waits", 1L),
                row("state", "unknown", "n", 1L, "max_xact_s", null, "max_query_s", null, "long_queries", 0L, "lock_waits", 0L));
    }

    private static List<Map<String, Object>> tableRows(int n) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        out.add(row("schema_name", "public", "table_name", "audit_log", "row_count", 900L, "dead_rows", 100L,
                "table_size", "8 MB", "total_size", "12 MB", "index_size", "4 MB",
                "table_size_bytes", 8_388_608L, "total_size_bytes", 12_582_912L, "index_size_bytes", 4_194_304L,
                "seq_scan", 30L, "idx_scan", 70L, "reads", 100L, "writes", 5L, "mod_since_analyze", 12L,
                "last_vacuum", "2026-09-01T02:00:00", "last_analyze", null));
        for (int i = 1; i < n; i++) {
            out.add(row("schema_name", "public", "table_name", "t" + i, "row_count", 0L, "dead_rows", 0L,
                    "seq_scan", 0L, "idx_scan", 0L, "reads", (long) i, "writes", 0L));
        }
        return out;
    }

    @Test
    @DisplayName("zengin payload: p95 + kullanıcı sayısı + durum dağılımı + pg_stat_database + tablo sağlığı (snake_case)")
    @SuppressWarnings("unchecked")
    void getOverview_enrichedFields() {
        Instant now = Instant.now();
        List<SqlQueryHistory> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 20; i++) rows.add(q(i % 2 == 0 ? "kisi.a" : "kisi.b", "SELECT " + i, i * 10L, true, now.minusSeconds(i)));
        when(historyRepo.findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(any(), any())).thenReturn(rows);
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.queryForList(contains("pg_stat_activity"))).thenReturn(activityRows());
        when(jdbcTemplate.queryForList(contains("pg_stat_user_tables"))).thenReturn(tableRows(2));
        when(jdbcTemplate.queryForList(contains("pg_stat_database"))).thenReturn(List.of(row(
                "blks_hit", 990L, "blks_read", 10L, "xact_commit", 95L, "xact_rollback", 5L, "deadlocks", 2L,
                "temp_files", 3L, "temp_bytes", 1024L, "temp_size", "1024 bytes", "stats_reset", "2026-09-01T00:00:00")));

        Map<String, Object> d = service.getOverview(7);
        Map<String, Object> sum = (Map<String, Object>) d.get("summary");
        assertThat(sum.get("p95_ms")).isEqualTo(190L);                  // 10..200 → en-yakın-sıra 19. değer
        assertThat(sum.get("user_count")).isEqualTo(2L);
        assertThat(sum.get("active_connections")).isEqualTo(16L);         // durum toplamı (ayrı count(*) yok)
        assertThat(sum.get("table_count")).isEqualTo(2L);
        assertThat((String) d.get("generated_at")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");

        Map<String, Object> conn = (Map<String, Object>) d.get("connections");
        assertThat(conn.get("active")).isEqualTo(16L);
        assertThat((List<Map<String, Object>>) conn.get("states")).extracting(s -> s.get("state"))
                .containsExactly("idle", "active", "idle in transaction", "unknown");
        assertThat(conn.get("idle_in_tx")).isEqualTo(2L);
        assertThat(conn.get("long_queries")).isEqualTo(1L);
        assertThat(conn.get("lock_waits")).isEqualTo(1L);
        assertThat(conn.get("longest_query_s")).isEqualTo(75L);
        assertThat(conn.get("longest_xact_s")).isEqualTo(400L);
        assertThat(conn.get("long_threshold_s")).isEqualTo(DbAnalyticsService.LONG_QUERY_S);

        Map<String, Object> st = (Map<String, Object>) d.get("db_stats");
        assertThat(st.get("cache_hit_pct")).isEqualTo(99.0);
        assertThat(st.get("rollback_pct")).isEqualTo(5.0);
        assertThat(st.get("deadlocks")).isEqualTo(2L);
        assertThat(st.get("stats_reset")).isEqualTo("2026-09-01T00:00:00");

        List<Map<String, Object>> sizes = (List<Map<String, Object>>) d.get("table_sizes");
        assertThat(sizes.get(0).get("dead_pct")).isEqualTo(10.0);          // 100 / (900 + 100)
        assertThat(sizes.get(0).get("idx_scan_pct")).isEqualTo(70.0);      // 70 / (30 + 70)
        assertThat(sizes.get(1).get("dead_pct")).isNull();                 // boş tablo: oran tanımsız, "%0" DEĞİL
        assertThat(sizes.get(1).get("idx_scan_pct")).isNull();
        // "En çok kullanılan" ayrı sorgu değil, aynı satırlardan okumaya göre türetilir (eski sözleşme alanları)
        List<Map<String, Object>> top = (List<Map<String, Object>>) d.get("top_tables");
        assertThat(top.get(0)).containsEntry("table_name", "audit_log").containsEntry("reads", 100L).containsKeys("writes", "row_count");

        assertSnakeCaseKeys(d, "$");
    }

    /** Tel biçimi: payload'daki HER anahtar snake_case (global Jackson ayarı Map anahtarlarını çevirmez). */
    private static void assertSnakeCaseKeys(Object node, String path) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                assertThat(String.valueOf(e.getKey())).as(path).matches("[a-z0-9_]+");
                assertSnakeCaseKeys(e.getValue(), path + "." + e.getKey());
            }
        } else if (node instanceof List<?> l) {
            for (Object o : l) assertSnakeCaseKeys(o, path + "[]");
        }
    }

    @Test
    @DisplayName("pg_* okunamazsa (yetki/eklenti yok) değerler null = BİLİNMİYOR — sıfır ya da 'sorun yok' değil")
    @SuppressWarnings("unchecked")
    void getOverview_pgUnavailable_isUnknownNotZero() {
        when(historyRepo.findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(any(), any())).thenReturn(List.of());
        when(jdbcTemplate.queryForList(org.mockito.ArgumentMatchers.anyString())).thenThrow(missing("pg_stat_activity"));
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(), eq(Long.class))).thenThrow(missing("pg_extension"));
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(), eq(String.class))).thenThrow(missing("pg_database"));
        when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(), eq(Integer.class))).thenThrow(missing("dual"));

        Map<String, Object> d = service.getOverview(7);
        Map<String, Object> sum = (Map<String, Object>) d.get("summary");
        Map<String, Object> conn = (Map<String, Object>) d.get("connections");
        assertThat(d.get("db_stats")).isNull();
        assertThat(d).containsKey("db_stats");                            // anahtar VAR, değeri null (eski sunucu ≠ okunamadı)
        assertThat(conn.get("states")).isNull();
        assertThat(conn.get("active")).isNull();
        assertThat(conn.get("idle_in_tx")).isNull();
        assertThat(conn.get("long_queries")).isNull();
        assertThat(conn.get("response_ms")).isEqualTo(-1L);
        assertThat(sum.get("active_connections")).isNull();
        assertThat(sum.get("table_count")).isNull();
        assertThat(sum.get("p95_ms")).isNull();                           // ölçüm yok → null ("0 ms" değil)
        assertThat((Boolean) sum.get("pgss")).isFalse();
        assertThat((List<?>) d.get("table_sizes")).isEmpty();
        assertThat((List<?>) d.get("top_tables")).isEmpty();
    }

    /** Tek pod: istatistik uç noktası tablo/satır sayısıyla ORANTILI sorgu atmamalı (N+1 yok). */
    private int jdbcCallsFor(int tables, int historyRows) {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        SqlQueryHistoryRepository repo = org.mockito.Mockito.mock(SqlQueryHistoryRepository.class);
        Instant now = Instant.now();
        List<SqlQueryHistory> rows = new java.util.ArrayList<>();
        for (int i = 0; i < historyRows; i++) rows.add(q("u" + (i % 7), "SELECT " + (i % 13), 5L, i % 9 != 0, now.minusSeconds(i)));
        when(repo.findByExecutedAtGreaterThanEqualOrderByExecutedAtDesc(any(), any())).thenReturn(rows);
        when(jdbc.queryForObject(contains("pg_extension"), eq(Long.class))).thenReturn(1L);      // pgss AÇIK (en pahalı yol)
        when(jdbc.queryForList(contains("pg_stat_statements"))).thenAnswer(a -> List.of(row("query", "SELECT $1", "calls", 3L)));
        when(jdbc.queryForList(contains("pg_stat_user_tables"))).thenReturn(tableRows(tables));
        when(jdbc.queryForList(contains("pg_stat_activity"))).thenReturn(activityRows());
        new DbAnalyticsService(jdbc, repo).getOverview(30);
        return org.mockito.Mockito.mockingDetails(jdbc).getInvocations().size();
    }

    @Test
    @DisplayName("sorgu sayısı sabit: 2 tablo/3 satır ile 150 tablo/5000 satır AYNI sayıda JDBC çağrısı (≤ 9)")
    void getOverview_queryCountIndependentOfSize() {
        int small = jdbcCallsFor(2, 3);
        int big = jdbcCallsFor(150, 5_000);
        assertThat(big).isEqualTo(small);
        assertThat(small).isLessThanOrEqualTo(9);
    }

    @Test
    @DisplayName("percentile: en-yakın-sıra, veri yoksa null")
    void percentile_nearestRank() {
        assertThat(DbAnalyticsService.percentile(new long[]{5, 1, 3}, 3, 95)).isEqualTo(5L);
        assertThat(DbAnalyticsService.percentile(new long[]{7}, 1, 95)).isEqualTo(7L);
        assertThat(DbAnalyticsService.percentile(new long[0], 0, 95)).isNull();
    }
}
