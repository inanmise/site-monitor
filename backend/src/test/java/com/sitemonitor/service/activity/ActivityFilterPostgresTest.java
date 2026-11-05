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
        return jdbc().execute((ConnectionCallback<String>) c -> {
            try (Statement st = c.createStatement()) {
                st.execute("SET enable_seqscan = off");
            }
            StringBuilder plan = new StringBuilder();
            try (PreparedStatement ps = c.prepareStatement("EXPLAIN SELECT COUNT(*) FROM activity_log " + w.where())) {
                Object[] a = w.array();
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
    }
}
