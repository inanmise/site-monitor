package com.sitemonitor.service.activity;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.activity.ActivityFilter.Params;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.sitemonitor.service.activity.ActivityRows.row;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aktivite zenginleştirmesinin (2026-11-05, D + E + F) sorguları GERÇEK PostgreSQL'de: liste / özet (ifadeyle GROUP BY,
 * {@code LIKE … ESCAPE}, bağlı LIMIT / OFFSET), dışa aktarımın sınırlı sayımı ve anahtar kümeli okuması, zaman çizelgesi,
 * kök neden olguları ({@code CASE} içinde bağlı parametreler) ve EXPLAIN ile iki yeni dizinin süzgece hizmet ettiği
 * ({@code idx_act_team_status_time}, kısmi {@code idx_act_reason_time}).
 *
 * <p>Zenginleştirmenin kolonları (failure_reason …) yazıcı kısmının varlığa eklediği kolonlardır; burada
 * {@code ADD COLUMN IF NOT EXISTS} — varlık onları taşıdığında etkisiz. Dizinler katalogdaki DDL'le (IF NOT EXISTS) kurulur.
 */
@PostgresIntegration
class ActivityFilterPostgresTest {

    private static final String TAG = "act2-it-" + UUID.randomUUID().toString().substring(0, 8);
    private static final long TEAM = 978_100_000L + (long) (Math.random() * 100_000);

    private static JdbcTemplate jdbc() {
        return PostgresIt.app().jdbc();
    }

    @BeforeAll
    static void schema() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);       // arka plan kurulumuyla yarışma
        ActivityRows.extendSchema(jdbc());
        for (DeferredIndexBuilder.Spec s : DeferredIndexBuilder.CATALOG) {
            if (s.name().equals("idx_act_team_status_time") || s.name().equals("idx_act_reason_time")) jdbc().execute(s.postgresDdl());
        }
        // Seçici dağılım: takımın 3 000 başarılı satırı + 20 hatası; nedenli satırlar az (kısmi dizin küçük)
        List<Object[]> batch = new ArrayList<>();
        Instant t0 = Instant.parse("2031-02-01T00:00:00Z");
        for (int i = 0; i < 3_000; i++) {
            batch.add(new Object[]{ActivityFilter.iso(t0.plusSeconds(i * 60L)), "HTTP", 1L, TAG + "-ok", "SCHEDULED_CHECK", "SUCCESS", TEAM, null});
        }
        for (int i = 0; i < 20; i++) {
            batch.add(new Object[]{ActivityFilter.iso(t0.plusSeconds(i * 600L + 30)), "HTTP", 2L, TAG + "-err", "SCHEDULED_CHECK",
                    i % 2 == 0 ? "ERROR" : "TIMEOUT", TEAM, i % 2 == 0 ? "DNS_NXDOMAIN" : "READ_TIMEOUT"});
        }
        jdbc().batchUpdate("INSERT INTO activity_log (activity_time, monitor_type, monitor_id, target, action, result_status,"
                + " team_id, failure_reason, monitor_name, actor) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'm', 'scheduler')", batch);
        jdbc().execute("ANALYZE activity_log");
    }

    private static ActivityFilter filter(Params p, boolean scoped) {
        return ActivityFilter.parse(p, scoped, List.of(TEAM));
    }

    /** {@code SET enable_seqscan = off} ile aynı bağlantıda EXPLAIN — küçük tabloda da dizin seçimi görülür. */
    private static String explain(ActivityFilter.Sql w) {
        return explainSql("SELECT COUNT(*) FROM activity_log " + w.where(), w.array());
    }

    private static String explainSql(String sql, Object[] a) {
        return jdbc().execute((ConnectionCallback<String>) c -> {
            try (Statement st = c.createStatement()) {
                st.execute("SET enable_seqscan = off");
            }
            StringBuilder plan = new StringBuilder();
            try (PreparedStatement ps = c.prepareStatement("EXPLAIN " + sql)) {
                for (int i = 0; i < a.length; i++) ps.setObject(i + 1, a[i]);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) plan.append(rs.getString(1)).append('\n');
                }
            } finally {
                try (Statement st = c.createStatement()) {
                    st.execute("RESET enable_seqscan");
                }
            }
            return plan.toString();
        });
    }

    @Test
    @DisplayName("EXPLAIN: kapsamlı durum süzgeci takım + durum + zaman dizinini, neden süzgeci kısmi neden dizinini kullanır")
    void indexesServeFilters() {
        ActivityFilter status = filter(new Params(null, null, null, "ERROR,TIMEOUT", "2031-02-01T00:00:00", null, null, null,
                null, null, null), true);
        assertThat(explain(status.where())).contains("idx_act_team_status_time");
        ActivityFilter reason = filter(new Params(null, null, null, null, "2031-02-01T00:00:00", null, null, null, null,
                "DNS_NXDOMAIN", null), false);
        assertThat(explain(reason.where())).contains("idx_act_reason_time");
    }

    @Test
    @DisplayName("liste + özet + dışa aktarım PostgreSQL'de: süzgeç, ifadeli GROUP BY, ESCAPE'li grup koşulu, sınırlı sayım, anahtar kümesi")
    void queriesRunOnPostgres() throws Exception {
        ActivityQueryService queries = PostgresIt.app().bean(ActivityQueryService.class);
        ActivityFilter f = filter(new Params(null, null, null, "ERROR,TIMEOUT", null, null, null, null, "check", null, TAG), true);
        ActivityQueryService.PageResult page = queries.list(f, 0, 7);
        assertThat(page.total()).isEqualTo(20);
        assertThat(page.rows()).hasSize(7);
        assertThat(page.rows().get(0).getActivityTime()).isEqualTo("2031-02-01T03:10:30");
        ActivityQueryService.SummaryData d = queries.summary(filter(Params.legacy(null, null, null, "2031-02-01T00:00:00", null,
                TAG), true), "2031-02-01T00:00:00");
        assertThat(d.statusCounts()).containsEntry("SUCCESS", 3000L).containsEntry("ERROR", 10L).containsEntry("TIMEOUT", 10L);
        assertThat(d.groupCounts()).containsEntry("check", 3020L).containsEntry("alarm", 0L);
        assertThat(d.typeCounts()).containsEntry("HTTP", 3020L);
        long hours = d.hourRows().stream().mapToLong(r -> ((Number) r[2]).longValue()).sum();
        assertThat(hours).isEqualTo(3020L);

        ActivityExportService exports = PostgresIt.app().bean(ActivityExportService.class);
        ActivityFilter all = filter(Params.legacy(null, null, null, null, null, TAG), true);
        assertThat(exports.countCapped(all, 100)).isEqualTo(101);
        exports.chunk = 700;
        List<Long> ids = new ArrayList<>();
        assertThat(exports.stream(all, 5_000, Map.of(), r -> ids.add(r.id()))).isEqualTo(3020);
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("zaman çizelgesi ve kök neden olguları PostgreSQL'de (bağlı parametreli CASE, LIMIT)")
    void timelineAndRelatedOnPostgres() {
        long anchor = row("2031-03-01T10:00:00", "PING", 77L, "ERROR").team(TEAM).reason("ICMP_NO_REPLY").save(jdbc());
        row("2031-03-01T09:55:00", "PING", 77L, "SUCCESS").team(TEAM).save(jdbc());
        row("2031-03-01T10:05:00", "PING", 77L, "SUCCESS").team(TEAM).save(jdbc());
        ActivityRelatedService related = PostgresIt.app().bean(ActivityRelatedService.class);
        Map<String, Object> body = related.related(anchor, 60, true, List.of(TEAM), id -> true).body();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> facts = (List<Map<String, Object>>) body.get("facts");
        assertThat(facts).extracting(m -> m.get("code")).contains("LAST_SUCCESS", "RECOVERED_AFTER");

        ActivityTimelineService timelines = PostgresIt.app().bean(ActivityTimelineService.class);
        Map<String, Object> t = timelines.timeline("PING:77", "2031-03-01T09:00:00Z", "2031-03-01T11:00:00Z", true, List.of(TEAM),
                id -> true, Instant.parse("2031-03-01T11:00:00Z"));
        assertThat(t.get("checks")).isEqualTo(3);
        assertThat(t.get("outage_count")).isEqualTo(1);

        // Hata avı O1: kesinti kuralının SQL eşi (LEFT / COALESCE) PostgreSQL'de — HTTP WARNING ve senaryo FAIL başarısız,
        // NO_CHECKS (neden ya da eski özet) ve sorgu ihlali değil
        long http = row("2031-03-02T10:00:00", "HTTP", 78L, "WARNING").team(TEAM).reason("HTTP_STATUS").http(503).save(jdbc());
        row("2031-03-02T10:00:10", "HTTP", 79L, "WARNING").team(TEAM).reason("HTTP_STATUS").http(503).save(jdbc());
        row("2031-03-02T10:00:20", "SCRIPTED", 80L, "WARNING").team(TEAM).reason("SCRIPT_CHECK_FAILED").save(jdbc());
        row("2031-03-02T10:00:30", "SCRIPTED", 81L, "WARNING").team(TEAM).summary("NO_CHECKS · 0✓/0✗").save(jdbc());
        row("2031-03-02T10:00:40", "SCRIPTED", 82L, "WARNING").team(TEAM).reason("SCRIPT_NO_CHECKS").save(jdbc());
        row("2031-03-02T10:00:50", "DBQUERY", 83L, "WARNING").team(TEAM).save(jdbc());
        Map<String, Object> w = related.related(http, 120, true, List.of(TEAM), id -> true).body();
        assertThat(((Map<?, ?>) w.get("anchor")).get("failing")).isEqualTo(true);
        assertThat(w.get("total")).isEqualTo(2);
    }

    /** Düz (tam) sıralama düğümü: "Sort  (…" — "Incremental Sort" (dizinden gelen sıranın eşitlerini sıralar) sayılmaz. */
    private static final java.util.regex.Pattern FULL_SORT = java.util.regex.Pattern.compile("(?m)^\\s*(->\\s+)?Sort\\s+\\(");

    @Test
    @DisplayName("O3 EXPLAIN: dışa aktarım parçası sırayı dizinden alır (tam Sort yok) — takım imleci ve kapsamsız imleç; sayım sıralamaz")
    void exportChunksTakeOrderFromIndex() throws Exception {
        Instant now = Instant.parse("2031-03-10T00:00:00Z");
        ActivityFilter scoped = ActivityExportService.range(filter(Params.legacy(null, null, null, "2031-01-20T00:00:00", null, null),
                true), now).filter();
        ActivityFilter.Sql first = ActivityExportService.chunkSql(scoped, TEAM, null, 0, 100);
        String p1 = explainSql(first.where(), first.array());
        assertThat(p1).as(p1).contains("Index");
        assertThat(FULL_SORT.matcher(p1).find()).as("takım imleci tam sıralama yapmamalı:\n%s", p1).isFalse();
        ActivityFilter.Sql next = ActivityExportService.chunkSql(scoped, TEAM, "2031-02-02T00:00:00", Long.MAX_VALUE, 100);
        String p2 = explainSql(next.where(), next.array());
        assertThat(FULL_SORT.matcher(p2).find()).as("anahtar kümeli parça tam sıralama yapmamalı:\n%s", p2).isFalse();
        assertThat(p2).as(p2).contains("Index");

        ActivityFilter global = ActivityExportService.range(filter(Params.legacy(null, null, null, "2031-01-20T00:00:00", null, null),
                false), now).filter();
        ActivityFilter.Sql g = ActivityExportService.chunkSql(global, null, "2031-02-02T00:00:00", Long.MAX_VALUE, 100);
        String p3 = explainSql(g.where(), g.array());
        assertThat(FULL_SORT.matcher(p3).find()).as("kapsamsız imleç tam sıralama yapmamalı:\n%s", p3).isFalse();

        ActivityFilter.Sql w = scoped.where();
        Object[] countArgs = java.util.stream.Stream.concat(w.args().stream(), java.util.stream.Stream.of(50_001)).toArray();
        String p4 = explainSql(ActivityExportService.countSql(w.where()), countArgs);
        assertThat(p4).as("sınırlı sayım sıralamaz:\n%s", p4).doesNotContain("Sort");

        // birleştirme gerçek PostgreSQL'de: kapsamlı dışa aktarım tek sorgunun sırasıyla aynı
        ActivityExportService exports = PostgresIt.app().bean(ActivityExportService.class);
        exports.chunk = 333;
        ActivityFilter tagged = ActivityExportService.range(filter(Params.legacy(null, null, null, "2031-01-20T00:00:00", null, TAG),
                true), now).filter();
        List<Long> got = new ArrayList<>();
        exports.stream(tagged, 5_000, Map.of(), r -> got.add(r.id()));
        List<Long> expected = jdbc().queryForList("SELECT id FROM activity_log WHERE team_id = ? AND LOWER(target) LIKE ?"
                + " AND activity_time >= '2031-01-20T00:00:00' ORDER BY activity_time DESC, id DESC", Long.class, TEAM, "%" + TAG + "%");
        assertThat(got).containsExactlyElementsOf(expected);
    }
}
