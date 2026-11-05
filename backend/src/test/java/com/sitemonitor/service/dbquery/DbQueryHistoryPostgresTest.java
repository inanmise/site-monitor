package com.sitemonitor.service.dbquery;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Akıllı kural geçmiş okuması GERÇEK PostgreSQL'de (dbq2 D): varlıktan üretilen {@code dbquery_checks}, yakın geçmiş ve
 * mevsimsel aralıklar TEK ifadede ({@code SELECT * FROM (… LIMIT ?) r UNION ALL SELECT * FROM (… LIMIT ?) s}), boolean
 * süzgeçler, çapa sınırı (aynı saniyede kimlik), en çok hedef sayısında (günlük 200 + haftalık 200) parametreli sorgu.
 */
@PostgresIntegration
class DbQueryHistoryPostgresTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static long insert(JdbcTemplate jdbc, long mon, Instant at, boolean ok, Boolean manual, Boolean recheck) {
        jdbc.update("INSERT INTO dbquery_checks (monitor_id, checked_at, ok, status, manual, recheck, sql_hash, "
                + "rule_results_json) VALUES (?, ?, ?, 'OK', ?, ?, 'h', '[]')", mon, ISO.format(at), ok, manual, recheck);
        return jdbc.queryForObject("SELECT MAX(id) FROM dbquery_checks WHERE monitor_id = ?", Long.class, mon);
    }

    @Test
    @DisplayName("yakın geçmiş + mevsimsel aralıklar tek ifadede; elle / teyit / hatalı hariç; çapa; 400 aralıklı plan")
    void readOnPostgres() {
        JdbcTemplate jdbc = PostgresIt.app().jdbc();
        long mon = 9_880_001L;
        jdbc.update("DELETE FROM dbquery_checks WHERE monitor_id BETWEEN ? AND ?", mon, mon + 10);
        Instant ref = Instant.parse("2026-11-05T10:00:00Z");
        long day = 86_400L;
        long d1 = insert(jdbc, mon, ref.minusSeconds(day - 30), true, false, false);
        insert(jdbc, mon, ref.minusSeconds(day + 3_600), true, false, false);
        long d2 = insert(jdbc, mon, ref.minusSeconds(2 * day), true, null, null);
        insert(jdbc, mon, ref.minusSeconds(2 * day + 10), true, true, false);    // elle
        insert(jdbc, mon, ref.minusSeconds(2 * day + 20), true, false, true);    // teyit
        insert(jdbc, mon, ref.minusSeconds(2 * day + 40), false, false, false);  // hatalı
        insert(jdbc, mon + 1, ref.minusSeconds(day), true, false, false);        // başka izleme
        long r1 = insert(jdbc, mon, ref.minusSeconds(60), true, false, false);
        long r1b = insert(jdbc, mon, ref.minusSeconds(60), true, false, false);  // aynı saniye

        DbQueryRules.Rule daily = DbQuerySmartRulesTest.rule("kind", "ANOMALY", "column", "adet", "season", "daily",
                "window", 10);
        DbQueryRules.Rule recent = DbQuerySmartRulesTest.rule("id", "n", "kind", "ANOMALY", "column", "adet", "window", 10);
        assertThat(DbQueryHistory.read(jdbc, mon, null, null, DbQueryHistory.plan(List.of(daily), ref, 300)))
                .extracting(DbQueryHistory.Row::id).containsExactly(d1, d2);
        List<DbQueryHistory.Row> both = DbQueryHistory.read(jdbc, mon, null, null,
                DbQueryHistory.plan(List.of(daily, recent), ref, 300));
        assertThat(both).extracting(DbQueryHistory.Row::id).doesNotHaveDuplicates()
                .startsWith(r1b, r1, d1).contains(d2).hasSize(5);
        // çapa r1b → aynı saniyedeki küçük kimlik (r1) ve öncesi
        assertThat(DbQueryHistory.read(jdbc, mon, ISO.format(ref.minusSeconds(60)), r1b,
                DbQueryHistory.plan(List.of(recent), ref, 300))).extracting(DbQueryHistory.Row::id).startsWith(r1, d1);

        // En büyük plan: günlük 200 + haftalık 200 hedef (tekil 372 aralık) + yakın 200 — parametreli tek ifade çalışır
        DbQueryRules.Rule d200 = DbQuerySmartRulesTest.rule("id", "d", "kind", "ANOMALY", "column", "adet", "season", "daily",
                "window", 200);
        DbQueryRules.Rule w200 = DbQuerySmartRulesTest.rule("id", "w", "kind", "ANOMALY", "column", "adet", "season",
                "weekly", "window", 200);
        DbQueryRules.Rule n200 = DbQuerySmartRulesTest.rule("id", "x", "kind", "ANOMALY", "column", "adet", "window", 200);
        DbQueryHistory.Plan big = DbQueryHistory.plan(List.of(d200, w200, n200), ref, 300);
        assertThat(big.ranges()).hasSize(200 + 172);   // haftalığın 28 günü (7…196) günlükle ortak
        assertThat(DbQueryHistory.read(jdbc, mon, null, null, big)).extracting(DbQueryHistory.Row::id)
                .contains(r1, r1b, d1, d2).doesNotHaveDuplicates();
        Map<String, Object> plan = jdbc.queryForMap("SELECT COUNT(*) AS n FROM dbquery_checks WHERE monitor_id = ?", mon);
        assertThat(((Number) plan.get("n")).intValue()).isEqualTo(8);
        jdbc.update("DELETE FROM dbquery_checks WHERE monitor_id BETWEEN ? AND ?", mon, mon + 10);
    }
}
