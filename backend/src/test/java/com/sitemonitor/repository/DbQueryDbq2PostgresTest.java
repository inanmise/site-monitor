package com.sitemonitor.repository;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * dbq2 (2026-11-05) yerli sorguları GERÇEK PostgreSQL'de — {@code mvn -Ppostgres-it test}: bağlantı sağlığının son durum
 * LATERAL'ı, 24 sa toplamı, son hata kodu; kart değer eğiliminin türetilmiş tablo kovası (yeniden bağlanan parametre / GROUP BY
 * ifadesi tuzağı yok); alarm postasının son zamanlanmış çalıştırmaları; grafik değişiklik işaretleri. Satırlar rastgele adlı
 * izlemelere yazılır ve silinir.
 */
@PostgresIntegration
class DbQueryDbq2PostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static DbQueryCheck check(long monitorId, String at, String status, boolean ok, Double value, Boolean manual,
                                      Integer ms, String reason) {
        DbQueryCheck c = new DbQueryCheck();
        c.setMonitorId(monitorId);
        c.setCheckedAt(at);
        c.setOk(ok);
        c.setStatus(status);
        c.setMetricValue(value);
        c.setManual(manual);
        c.setDurationMs(ms);
        c.setFailureReason(reason);
        return c;
    }

    @Test
    @DisplayName("PostgreSQL: son durum LATERAL, 24 sa toplamı, eşit zamanlı hata kodu, saatlik değer kovası, son zamanlanmış çalıştırmalar, değişiklik işaretleri")
    void dbq2QueriesOnPostgres() {
        DbQueryMonitorRepository monitors = PostgresIt.app().bean(DbQueryMonitorRepository.class);
        DbQueryCheckRepository checks = PostgresIt.app().bean(DbQueryCheckRepository.class);
        MonitorChangeLogRepository changes = PostgresIt.app().bean(MonitorChangeLogRepository.class);
        DbQueryMonitor m = new DbQueryMonitor();
        m.setName("it-dbq2-" + UUID.randomUUID());
        m.setTeamId(1L);
        m.setActive(true);
        m.setSqlText("SELECT 1");
        long id = monitors.save(m).getId();
        try {
            checks.save(check(id, "2026-11-05T08:10:00", "OK", true, 10.0, null, 100, null));
            checks.save(check(id, "2026-11-05T08:40:00", "BREACH", true, 20.0, false, 300, null));
            checks.save(check(id, "2026-11-05T08:45:00", "OK", true, 999.0, true, 50, null));
            checks.save(check(id, "2026-11-05T09:05:00", "FAIL", false, null, false, 200, "DB_TIMEOUT"));

            List<Object[]> last = checks.latestStatusFor(List.of(id));
            assertThat(last).hasSize(1);
            assertThat(last.get(0)[1]).isEqualTo("FAIL");

            Object[] agg = checks.connectionRunStats(List.of(id), "2026-11-05T00:00:00",
                    com.sitemonitor.service.dbquery.DbConnectionProfileService.CONNECTION_CODES).get(0);
            assertThat(((Number) agg[0]).longValue()).isEqualTo(4);
            assertThat(((Number) agg[1]).longValue()).isEqualTo(1);
            assertThat(((Number) agg[2]).doubleValue()).isEqualTo(162.5);
            assertThat(((Number) agg[3]).longValue()).isEqualTo(0);
            assertThat(checks.failureReasonsAt(List.of(id), "2026-11-05T09:05:00")).containsExactly("DB_TIMEOUT");

            Map<String, Double> byKey = new HashMap<>();
            for (Object[] r : checks.valueTrendBuckets(List.of(id), "2026-11-05T00:00:00"))
                byKey.put(r[1].toString(), ((Number) r[2]).doubleValue());
            assertThat(byKey).containsOnly(Map.entry("2026-11-05T08", 15.0));

            List<Object[]> recent = checks.recentScheduledSummary(id, 10);
            assertThat(recent).hasSize(3);
            assertThat(recent.get(0)[1]).isEqualTo("FAIL");
            assertThat(recent.get(2)[1]).isEqualTo("OK");

            com.sitemonitor.model.MonitorChangeLog row = new com.sitemonitor.model.MonitorChangeLog();
            row.setResourceKind("DBQUERY");
            row.setResourceId(id);
            row.setSeq(1);
            row.setEventType("UPDATE");
            row.setCreatedAt("2026-11-04T12:00:00");
            row.setChanges("{\"sqlText\":{}}");
            changes.save(row);
            List<Object[]> marks = changes.changeMarks("DBQUERY", id, "2026-11-01T00:00:00", "2026-11-05T00:00:00", 200);
            assertThat(marks).hasSize(1);
            assertThat(marks.get(0)[1]).isEqualTo("UPDATE");
        } finally {
            jdbc().update("DELETE FROM dbquery_checks WHERE monitor_id = ?", id);
            jdbc().update("DELETE FROM monitor_change_log WHERE resource_kind = 'DBQUERY' AND resource_id = ?", id);
            jdbc().update("DELETE FROM dbquery_monitors WHERE id = ?", id);
        }
    }
}
