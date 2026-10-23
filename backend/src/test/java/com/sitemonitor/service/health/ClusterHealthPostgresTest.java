package com.sitemonitor.service.health;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.SystemHeartbeat;
import com.sitemonitor.repository.SystemHeartbeatRepository;
import com.sitemonitor.service.SchedulerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sistem Sağlığı küme durumu GERÇEK PostgreSQL'de (2026-10-23, çok pod R2b, MP-34 / MP-35 / N4) —
 * {@code mvn -Ppostgres-it test}: açılış yaması tabloları ve heartbeat kolonlarını kurdu; koşullu UPDATE + yinelenen anahtarda
 * INSERT yolu (otomatik kayıt, işlem dışı) PostgreSQL'de düşmeden "daha yeni değil" der; pod özeti JPQL'i ({@code CASE} +
 * {@code GROUP BY}) çalışır; {@code getSystemHealth} (salt okunur işlem) küme tarama durumunu okur.
 */
@PostgresIntegration
class ClusterHealthPostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    @Test
    @DisplayName("PostgreSQL: yama tabloları ve heartbeat kolonlarını kurdu")
    void schemaInPlace() {
        for (String t : List.of("scan_runs", "cluster_observations")) {
            Integer n = jdbc().queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, t);
            assertThat(n).as(t).isEqualTo(1);
        }
        for (String c : List.of("instance_id", "shutdown_marker")) {
            Integer n = jdbc().queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'system_heartbeat' "
                    + "AND column_name = ?", Integer.class, c);
            assertThat(n).as(c).isEqualTo(1);
        }
        assertThat(new ClusterHealthSchemaPatch(jdbc()).apply()).as("ikinci koşu değişiklik yapmaz").isZero();
    }

    @Test
    @DisplayName("PostgreSQL: gözlem — eski yayın yinelenen anahtarda NOT_NEWER (işlem bozulmaz), yeni yayın yazılır")
    void observationOnPostgres() {
        ClusterHealthStore store = PostgresIt.app().bean(ClusterHealthStore.class);
        String name = "it-" + UUID.randomUUID().toString().substring(0, 20);
        assertThat(store.publish(name, "pod1-00000001", 1_000L, "{}", "t")).isEqualTo(ClusterHealthStore.Publish.WRITTEN);
        assertThat(store.publish(name, "pod2-00000002", 999L, "{}", "t")).isEqualTo(ClusterHealthStore.Publish.NOT_NEWER);
        assertThat(store.publish(name, "pod2-00000002", 2_000L, "{}", "t")).isEqualTo(ClusterHealthStore.Publish.WRITTEN);
        assertThat(store.read(name).orElseThrow().instanceId()).isEqualTo("pod2-00000002");
        jdbc().update("DELETE FROM cluster_observations WHERE name = ?", name);
    }

    @Test
    @DisplayName("PostgreSQL: tarama satırı + getSystemHealth (salt okunur işlem) küme durumunu okur")
    void scanRunAndSystemHealth() {
        ClusterHealthStore store = PostgresIt.app().bean(ClusterHealthStore.class);
        String at = LocalDateTime.now(ZoneOffset.UTC).withNano(0).toString();
        assertThat(store.recordScanRun("full", "itrun", "pod1-00000001", at, 10L, 3, 0, 0, null)).isTrue();
        Map<String, Object> h = PostgresIt.app().bean(SchedulerService.class).getSystemHealth();
        @SuppressWarnings("unchecked") Map<String, Object> scan = (Map<String, Object>) h.get("scan");
        assertThat(scan).containsEntry("source", "cluster");
        assertThat((String) scan.get("last_run")).isGreaterThanOrEqualTo(at);
        assertThat(h).containsKey("served_by");
        jdbc().update("DELETE FROM scan_runs WHERE kind = 'full' AND run_id = 'itrun'");
    }

    @Test
    @DisplayName("PostgreSQL: heartbeat pod özeti ({@code CASE} + {@code GROUP BY}) ve izdüşüm sorgusu")
    void heartbeatQueriesOnPostgres() {
        SystemHeartbeatRepository repo = PostgresIt.app().bean(SystemHeartbeatRepository.class);
        String id = "itpod-" + UUID.randomUUID().toString().substring(0, 8).replace('-', 'a') + "-0000abcd";
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        SystemHeartbeat a = new SystemHeartbeat();
        a.setRecordedAt(now.minusMinutes(2));
        a.setInstanceId(id);
        SystemHeartbeat m = new SystemHeartbeat();
        m.setRecordedAt(now.minusMinutes(1));
        m.setInstanceId(id);
        m.setShutdownMarker(Boolean.TRUE);
        repo.saveAll(List.of(a, m));
        try {
            Object[] row = repo.podSummariesSince(now.minusHours(1)).stream().filter(r -> id.equals(r[0])).findFirst().orElseThrow();
            assertThat(((Number) row[3]).longValue()).as("işaret sayısı").isEqualTo(1L);
            assertThat(repo.findBeatsAfter(now.minusHours(1)).stream().filter(r -> id.equals(r[1])).count())
                    .as("işaret izdüşüme girmez").isEqualTo(1);
        } finally {
            jdbc().update("DELETE FROM system_heartbeat WHERE instance_id = ?", id);
        }
    }
}
