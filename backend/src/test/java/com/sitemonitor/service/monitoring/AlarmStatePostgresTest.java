package com.sitemonitor.service.monitoring;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod R1-B (2026-10-21) — GERÇEK PostgreSQL'de ({@code mvn -Ppostgres-it test}): {@code change_state} kolonları ve
 * {@code monitor_eval_inbox} açılışta kurulmuş; gelen kutusunun tek ifadeli sahiplenmesi ({@code FOR UPDATE SKIP LOCKED
 * … RETURNING}) iki eşzamanlı pod'a satırları AYRIK dağıtır.
 */
@PostgresIntegration
class AlarmStatePostgresTest {

    private static JdbcTemplate jdbc() {
        return PostgresIt.app().jdbc();
    }

    private static boolean column(String table, String col) {
        Integer n = jdbc().queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() "
                + "AND table_name = ? AND column_name = ?", Integer.class, table, col);
        return n != null && n > 0;
    }

    @Test
    @DisplayName("PostgreSQL: change_state kolonları, gelen kutusu tablosu ve sahiplenme dizini açılışta var")
    void schemaInPlace() {
        assertThat(column("dns_records", ChangeStates.COLUMN)).isTrue();
        assertThat(column("domain_checks", ChangeStates.COLUMN)).isTrue();
        for (String c : List.of("monitor_type", "monitor_id", "manual", "result_json", "checked_ms", "source_pod",
                "claimed_by", "claimed_until_ms", "created_ms")) {
            assertThat(column(ManualEvalInbox.TABLE, c)).as(c).isTrue();
        }
        assertThat(jdbc().queryForObject("SELECT to_regclass('idx_mei_claim') IS NOT NULL", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("PostgreSQL: iki pod aynı anda sahiplenir (SKIP LOCKED + RETURNING) → her satır tek pod'a, take tam bir kez")
    void concurrentClaims_disjoint() throws Exception {
        long base = 9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
        // Paylaşılan uygulama bağlamı tek pod'luk küme lideridir ve kendi boşaltma işi 5 sn'de bir koşar: test satırları
        // "park" sahibiyle bir gün sonrasına dek sahiplenilmiş eklenir (o iş duvar saatiyle bakar, alamaz); test pod'larının
        // saati iki gün ileride — onlar için park kirası dolmuştur.
        long wall = System.currentTimeMillis();
        long park = wall + 86_400_000L;
        for (int i = 0; i < 40; i++) {
            jdbc().update("INSERT INTO monitor_eval_inbox (monitor_type, monitor_id, manual, result_json, checked_ms, source_pod, "
                            + "claimed_by, claimed_until_ms, created_ms) VALUES (?, ?, TRUE, ?, ?, 'it-src', 'it-park', ?, ?)",
                    ManualEvalInbox.TYPE_HTTP, base + i, ManualEvalInbox.toJson(Map.of("ok", true, "i", i)), wall, park, wall);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier start = new CyclicBarrier(2);
            List<Future<Set<Long>>> results = List.of(
                    pool.submit(() -> drain(start, future(wall), "it-pod-a", base)),
                    pool.submit(() -> drain(start, future(wall), "it-pod-b", base)));
            Set<Long> all = new HashSet<>();
            int total = 0;
            for (Future<Set<Long>> f : results) {
                Set<Long> got = f.get(60, TimeUnit.SECONDS);
                total += got.size();
                all.addAll(got);
            }
            assertThat(total).as("çift işlenen satır yok").isEqualTo(all.size());
            assertThat(all).hasSize(40);
        } finally {
            pool.shutdownNow();
            jdbc().update("DELETE FROM monitor_eval_inbox WHERE monitor_id BETWEEN ? AND ?", base, base + 100);
        }
    }

    private static ManualEvalInbox future(long wall) {
        ManualEvalInbox i = new ManualEvalInbox(jdbc());
        i.setClock(() -> wall + 2 * 86_400_000L);
        return i;
    }

    private static Set<Long> drain(CyclicBarrier start, ManualEvalInbox inbox, String owner, long base) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        Set<Long> mine = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            List<ManualEvalInbox.Row> rows = inbox.claim(owner, 5);
            if (rows.isEmpty()) break;
            for (ManualEvalInbox.Row r : rows) {
                if (r.monitorId() < base || r.monitorId() > base + 100) continue;   // başka testin satırı değil
                assertThat(r.claimedBy()).isEqualTo(owner);
                assertThat(ManualEvalInbox.parse(r.resultJson())).containsEntry("ok", true);
                if (inbox.take(r)) mine.add(r.id());
            }
        }
        return mine;
    }
}
