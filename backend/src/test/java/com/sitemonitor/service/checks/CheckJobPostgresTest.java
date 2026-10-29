package com.sitemonitor.service.checks;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dağıtık kontrol kuyruğu GERÇEK PostgreSQL'de (2026-10-29, çok pod Sürüm 3-3 paket A, tasarım §9.2 A) —
 * {@code mvn -Ppostgres-it test}: açılış yaması iki tabloyu, {@code idx_mcj_state}'i, kısmi tekil {@code ux_mcj_open}'ı ve
 * otomatik süpürme eşiklerini kurdu; çok satırlı {@code ON CONFLICT DO NOTHING} gönderme açık işi tekilleştirir; üç işçi
 * {@code FOR UPDATE SKIP LOCKED} ile 2000 işe aynı anda uzanır — her iş tam bir kez alınır; kira-bağlı {@code DELETE …
 * RETURNING} kira sahibi olmayana hiçbir şey vermez; sahiplenme aday sorgusu dizinle koşar. Satırlar rastgele bir izleme kimliği
 * aralığında yazılır ve her testten sonra silinir (paylaşılan veritabanı; uygulamanın anahtarı kapalı — kuyruğa yazmaz).
 */
@PostgresIntegration
class CheckJobPostgresTest {

    private static final long BASE = 9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);

    private static JdbcTemplate jdbc() {
        return PostgresIt.app().jdbc();
    }

    private final TestDbClock clock = new TestDbClock(System.currentTimeMillis());
    private JdbcCheckJobStore store;

    @BeforeEach
    void setUp() {
        store = new JdbcCheckJobStore(jdbc(), clock);
    }

    @AfterEach
    void cleanUp() {
        jdbc().update("DELETE FROM monitor_check_jobs WHERE monitor_id >= ? AND monitor_id < ?", BASE, BASE + 1_000_000);
        jdbc().update("DELETE FROM monitor_check_workers WHERE instance_id LIKE 'itr33-%'");
    }

    @Test
    @DisplayName("PostgreSQL: yama tabloları, dizinleri ve otomatik süpürme eşiklerini kurdu; ikinci koşu değişiklik yapmaz")
    void schemaInPlace() {
        for (String t : CheckJobSql.TABLES) {
            Integer n = jdbc().queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, t);
            assertThat(n).as(t).isEqualTo(1);
        }
        assertThat(jdbc().queryForObject("SELECT to_regclass('idx_mcj_state') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc().queryForObject("SELECT to_regclass('ux_mcj_open') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc().queryForObject("SELECT i.indisunique AND i.indpred IS NOT NULL FROM pg_index i "
                + "WHERE i.indexrelid = to_regclass('ux_mcj_open')", Boolean.class)).as("kısmi tekil").isTrue();
        String opts = jdbc().queryForObject("SELECT array_to_string(reloptions, ',') FROM pg_class WHERE oid = to_regclass(?::text)",
                String.class, CheckJobSql.JOBS);
        assertThat(opts).contains("autovacuum_vacuum_scale_factor=0").contains("autovacuum_vacuum_threshold=2000");
        assertThat(new CheckJobSchemaPatch(jdbc()).apply()).as("ikinci koşu").isZero();
        assertThat(store.postgres()).isTrue();
        assertThat(store.openIndexPresent()).isTrue();
    }

    @Test
    @DisplayName("PostgreSQL: çok satırlı gönderme açık işi tekilleştirir; tekil dizin ikinci açık satırı reddeder; DONE yenisini engellemez")
    void dispatchOnConflict() {
        List<Long> ids = new ArrayList<>();
        for (long i = 0; i < 450; i++) ids.add(BASE + i);
        assertThat(store.dispatch(CheckJobTypes.PORT, ids, 150_000, "itr33-l")).isEqualTo(450);
        assertThat(store.dispatch(CheckJobTypes.PORT, ids, 150_000, "itr33-l")).isZero();
        assertThatThrownBy(() -> jdbc().update("INSERT INTO monitor_check_jobs (job_type, monitor_id, state, lease_ms, created_ms, "
                + "dispatcher, attempts) VALUES ('port', ?, 'PENDING', 1, 1, 'x', 0)", BASE))
                .isInstanceOf(DuplicateKeyException.class);
        List<CheckJobRow> c = store.claim(Set.of(CheckJobTypes.PORT), 1, "itr33-w");
        store.complete(List.of(new CheckJobStore.Completion(c.get(0).id(), c.get(0).claimToken(), "{}", false, "itr33-w")));
        assertThat(store.dispatch(CheckJobTypes.PORT, List.of(c.get(0).monitorId()), 150_000, "itr33-l")).isEqualTo(1);
    }

    @Test
    @DisplayName("PostgreSQL: üç işçi × 2000 iş SKIP LOCKED ile yarışır — her iş tam bir kez sahiplenilir")
    void threeWorkersClaimEachJobOnce() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (long i = 0; i < 2_000; i++) ids.add(BASE + 10_000 + i);
        assertThat(store.dispatch(CheckJobTypes.HTTP, ids, 150_000, "itr33-l")).isEqualTo(2_000);
        List<Callable<List<Long>>> workers = new ArrayList<>();
        for (int w = 0; w < 3; w++) {
            JdbcCheckJobStore s = new JdbcCheckJobStore(jdbc(), clock);
            String owner = "itr33-w" + w;
            workers.add(() -> {
                List<Long> got = new ArrayList<>();
                for (int round = 0; round < 1_000; round++) {
                    List<CheckJobRow> rows = s.claim(Set.of(CheckJobTypes.HTTP), 25, owner);
                    if (rows.isEmpty()) break;
                    for (CheckJobRow r : rows) got.add(r.id());
                }
                return got;
            });
        }
        List<List<Long>> res = runTogether(workers);
        Set<Long> all = new HashSet<>();
        int total = 0;
        for (List<Long> r : res) {
            total += r.size();
            all.addAll(r);
        }
        assertThat(total).isEqualTo(2_000);
        assertThat(all).hasSize(2_000);
        assertThat(res).as("iş ≥ 2 işçiye dağıldı").filteredOn(r -> !r.isEmpty()).hasSizeGreaterThanOrEqualTo(2);
        Integer claimed = jdbc().queryForObject("SELECT COUNT(*) FROM monitor_check_jobs WHERE state = 'CLAIMED' AND attempts = 1 "
                + "AND monitor_id >= ? AND monitor_id < ?", Integer.class, BASE + 10_000, BASE + 12_000);
        assertThat(claimed).isEqualTo(2_000);
    }

    @Test
    @DisplayName("PostgreSQL: kira-bağlı alma — kira sahibi olmayan hiçbir şey almaz; sahibi id sırasında, tam bir kez alır")
    void takeIsLeaseBound() {
        store.dispatch(CheckJobTypes.PING, List.of(BASE + 20_001, BASE + 20_002), 150_000, "itr33-l");
        List<CheckJobRow> got = store.claim(Set.of(CheckJobTypes.PING), 2, "itr33-w");
        assertThat(got).hasSize(2);
        store.complete(List.of(new CheckJobStore.Completion(got.get(1).id(), got.get(1).claimToken(), "{\"up\":true}", false, "itr33-w"),
                new CheckJobStore.Completion(got.get(0).id(), got.get(0).claimToken(), "{\"up\":false}", false, "itr33-w")));
        assertThat(store.take(10, "itr33-not-the-lease-holder")).isEmpty();

        String holder = jdbc().query("SELECT claimed_by FROM " + ClusterScheduleService.TABLE + " WHERE monitor_key = ?",
                (ResultSet rs) -> rs.next() ? rs.getString(1) : null, CheckJobSql.LEASE_KEY);
        boolean inserted = false;
        if (holder == null) {
            holder = "itr33-leader";
            jdbc().update("INSERT INTO " + ClusterScheduleService.TABLE + " (monitor_key, next_due_at, claimed_by) VALUES (?, ?, ?)",
                    CheckJobSql.LEASE_KEY, System.currentTimeMillis() + 60_000, holder);
            inserted = true;
        }
        try {
            List<CheckResultRow> taken = store.take(10, holder);
            assertThat(taken).extracting(CheckResultRow::monitorId).containsExactly(BASE + 20_001, BASE + 20_002);
            assertThat(taken.get(0).result()).containsEntry("up", false);
            assertThat(store.take(10, holder)).as("tam bir kez").isEmpty();
        } finally {
            if (inserted) {
                jdbc().update("DELETE FROM " + ClusterScheduleService.TABLE + " WHERE monitor_key = ? AND claimed_by = ?",
                        CheckJobSql.LEASE_KEY, holder);
            }
        }
    }

    @Test
    @DisplayName("PostgreSQL: taşınabilir yol da (dizin doğrulanmadan önce / tür okunamadı) doğru — açık işi yoksa ekle + ON CONFLICT, CAS sahiplenme")
    void portablePathIsValidOnPostgres() {
        long m = BASE + 40_001;
        int[] first = jdbc().batchUpdate(CheckJobSql.SQL_DISPATCH_ONE_PG,
                List.<Object[]>of(new Object[] {"keyword", m, 150_000L, clock.nowMs(), "itr33-l", "keyword", m}));
        int[] again = jdbc().batchUpdate(CheckJobSql.SQL_DISPATCH_ONE_PG,
                List.<Object[]>of(new Object[] {"keyword", m, 150_000L, clock.nowMs(), "itr33-l", "keyword", m}));
        assertThat(first).containsExactly(1);
        assertThat(again).as("açık iş var — eklenmez, hata yok").containsExactly(0);
        JdbcCheckJobStore portable = new JdbcCheckJobStore(jdbc(), clock, Boolean.FALSE, System::nanoTime);
        assertThat(portable.dispatch(CheckJobTypes.KEYWORD, List.of(m, m + 1), 150_000, "itr33-l")).isEqualTo(1);
        List<CheckJobRow> got = portable.claim(Set.of(CheckJobTypes.KEYWORD), 5, "itr33-w", clock.nowMs() + 1);
        assertThat(got).extracting(CheckJobRow::monitorId).containsExactly(m, m + 1);
        assertThat(portable.complete(List.of(new CheckJobStore.Completion(got.get(0).id(), got.get(0).claimToken(), "{}", false,
                "itr33-w")))).containsExactly(true);
        assertThat(portable.release("itr33-w", List.of(got.get(1).id()))).isEqualTo(1);
        CheckJobStore.QueueStats st = portable.stats();
        assertThat(st.count(CheckJobStore.STATE_DONE, CheckJobTypes.KEYWORD)).isGreaterThanOrEqualTo(1);
        assertThat(portable.reap(5)).isEmpty();
    }

    @Test
    @DisplayName("PostgreSQL: kalp atışı upsert'i (ON CONFLICT DO UPDATE) ve sahiplenme aday sorgusu dizinle koşar")
    void heartbeatUpsertAndIndexedClaim() {
        DistributedChecks on = CheckWorkerRegistryTest.distributed(true, DistributedChecks.Tuning.DEFAULTS);
        CheckWorkerRegistryImpl reg = new CheckWorkerRegistryImpl(jdbc(), clock, on, "itr33-pg-00000001", () -> false,
                () -> true, () -> true, () -> true, () -> true, () -> true,
                () -> new CheckWorkerRegistryImpl.LocalStats(16, 1, 2L, 0L, null), () -> "it", System::nanoTime);
        reg.tick();
        clock.advance(10_000);
        reg.tick();
        Long hb = jdbc().queryForObject("SELECT heartbeat_ms FROM monitor_check_workers WHERE instance_id = 'itr33-pg-00000001'",
                Long.class);
        assertThat(hb).isEqualTo(clock.nowMs());
        assertThat(reg.workers()).anyMatch(w -> w.instanceId().equals("itr33-pg-00000001") && w.claimLagP95Ms() == null);

        List<Long> ids = new ArrayList<>();
        for (long i = 0; i < 2_000; i++) ids.add(BASE + 30_000 + i);
        store.dispatch(CheckJobTypes.DNS, ids, 150_000, "itr33-l");
        jdbc().execute("ANALYZE monitor_check_jobs");
        String plan = jdbc().execute((ConnectionCallback<String>) conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("SET enable_seqscan = off");
                try (ResultSet rs = st.executeQuery("EXPLAIN SELECT id FROM monitor_check_jobs WHERE job_type IN ('dns') "
                        + "AND (state = 'PENDING' OR (state = 'CLAIMED' AND lease_until_ms < 1)) AND attempts < 2 "
                        + "ORDER BY id LIMIT 25 FOR UPDATE SKIP LOCKED")) {
                    StringBuilder sb = new StringBuilder();
                    while (rs.next()) sb.append(rs.getString(1)).append('\n');
                    return sb.toString();
                } finally {
                    st.execute("RESET enable_seqscan");
                }
            }
        });
        assertThat(plan).as(plan).contains("Index").doesNotContain("Seq Scan on monitor_check_jobs");
    }

    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CyclicBarrier barrier = new CyclicBarrier(tasks.size());
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return t.call();
                }));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) out.add(f.get(60, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
