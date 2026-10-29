package com.sitemonitor.service.certsweep;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parçalı sertifika taraması deposu GERÇEK PostgreSQL'de (2026-10-29, çok pod Sürüm 3-4 paket A, tasarım §9.2 A) —
 * {@code mvn -Ppostgres-it test} (yalnız CI): açılış yaması iki tabloyu, üç dizini ve parça tablosunun otomatik süpürme
 * eşiklerini kurdu; üç işçi {@code WITH … FOR UPDATE OF k SKIP LOCKED … UPDATE … RETURNING} ile 500 parçaya aynı anda uzanır — her
 * parça tam bir kez alınır; koşu RUNNING + koordinatör nabzı kapısı; tamamlama belirteç CAS'ı + {@code EXISTS (koşu RUNNING)};
 * bırakma / iptal / öksüz kapatma / budama / durum okuması; sahiplenme aday sorgusu {@code idx_csc_state} ile koşar; salt okunur
 * işlem içinden bean çağrıları işlemi bozmaz (NOT_SUPPORTED); taşınabilir CAS yolu da PostgreSQL'de doğrudur. Satırlar
 * {@code itr34-} önekli koşu kimlikleriyle yazılır ve her testten sonra silinir (paylaşılan veritabanı; uygulamanın anahtarı
 * kapalı — tablolara yazmaz).
 */
@PostgresIntegration
class CertSweepPostgresTest {

    private static final String PREFIX = "itr34-" + Long.toHexString(ThreadLocalRandom.current().nextLong(1L << 32)) + "-";
    private static final long LEASE = 60_000L;

    private static JdbcTemplate jdbc() {
        return PostgresIt.app().jdbc();
    }

    private final CertSweepTestClock clock = new CertSweepTestClock(System.currentTimeMillis());

    private JdbcCertSweepStore store() {
        JdbcCertSweepStore s = new JdbcCertSweepStore(jdbc(), clock);
        s.markTablesReady(true);
        return s;
    }

    private static String id(String name) {
        return PREFIX + name;
    }

    @AfterEach
    void cleanUp() {
        jdbc().update("DELETE FROM cert_sweep_chunks WHERE run_id LIKE ?", PREFIX + "%");
        jdbc().update("DELETE FROM cert_sweep_runs WHERE run_id LIKE ?", PREFIX + "%");
    }

    @Test
    @DisplayName("PostgreSQL: yama tabloları, üç dizini ve otomatik süpürme eşiklerini kurdu; ikinci koşu değişiklik yapmaz; tür PostgreSQL")
    void schemaInPlace() {
        for (String t : CertSweepSql.TABLES) {
            Integer n = jdbc().queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, t);
            assertThat(n).as(t).isEqualTo(1);
        }
        for (String idx : List.of("idx_csr_state", "ux_csc_run_chunk", "idx_csc_state")) {
            assertThat(jdbc().queryForObject("SELECT to_regclass(?::text) IS NOT NULL", Boolean.class, idx)).as(idx).isTrue();
        }
        assertThat(jdbc().queryForObject("SELECT i.indisunique FROM pg_index i WHERE i.indexrelid = to_regclass('ux_csc_run_chunk')",
                Boolean.class)).isTrue();
        String opts = jdbc().queryForObject("SELECT array_to_string(reloptions, ',') FROM pg_class WHERE oid = to_regclass(?::text)",
                String.class, CertSweepSql.CHUNKS);
        assertThat(opts).contains("autovacuum_vacuum_scale_factor=0").contains("autovacuum_vacuum_threshold=500");
        JdbcCertSweepStore s = store();
        assertThat(new CertSweepSchemaPatch(jdbc(), s).apply()).as("ikinci koşu").isZero();
        assertThat(s.tablesReady()).isTrue();
        assertThat(s.postgres()).isTrue();
        assertThat(PostgresIt.app().bean(CertSweepStore.class).tablesReady()).as("uygulamanın deposu açılışta 'hazır'").isTrue();
    }

    @Test
    @DisplayName("PostgreSQL: üç işçi × 500 parça SKIP LOCKED ile yarışır — her parça tam bir kez sahiplenilir, deneme 1")
    void threeWorkersClaimEachChunkOnce() throws Exception {
        JdbcCertSweepStore s = store();
        String run = id("race");
        s.createRun(CertSweepSqlH2Test.spec(run, 10_000, 20));
        List<Callable<List<Long>>> workers = new ArrayList<>();
        for (int w = 0; w < 3; w++) {
            JdbcCertSweepStore ws = store();
            String owner = PREFIX + "w" + w;
            workers.add(() -> {
                List<Long> got = new ArrayList<>();
                for (int i = 0; i < 1_000; i++) {
                    Optional<ClaimedChunk> c = ws.claim(owner, LEASE, Long.MAX_VALUE);
                    if (c.isEmpty()) break;
                    if (!c.get().runId().startsWith(PREFIX)) continue;   // paylaşılan veritabanında başka koşu yok — savunma
                    assertThat(c.get().entries()).hasSize(20);
                    got.add(c.get().id());
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
        assertThat(total).isEqualTo(500);
        assertThat(all).hasSize(500);
        assertThat(res).as("iş ≥ 2 işçiye dağıldı").filteredOn(r -> !r.isEmpty()).hasSizeGreaterThanOrEqualTo(2);
        Integer claimed = jdbc().queryForObject("SELECT COUNT(*) FROM cert_sweep_chunks WHERE run_id = ? AND state = 'CLAIMED' "
                + "AND attempts = 1", Integer.class, run);
        assertThat(claimed).isEqualTo(500);
    }

    @Test
    @DisplayName("PostgreSQL: koşu RUNNING + nabız kapısı; oluşturma eşiği; kaçınılan işçi; kira dolumu → deneme 2 → kayıp")
    void claimGates() {
        JdbcCertSweepStore s = store();
        String run = id("gates");
        s.createRun(CertSweepSqlH2Test.spec(run, 20, 20));
        assertThat(s.claim(PREFIX + "w", LEASE, clock.nowMs())).as("oluşturma eşiği").isEmpty();
        clock.advance(60_001);
        assertThat(s.claim(PREFIX + "w", LEASE, Long.MAX_VALUE)).as("nabız eski").isEmpty();
        assertThat(s.heartbeat(run)).isTrue();
        ClaimedChunk a = s.claim(PREFIX + "w1", LEASE, Long.MAX_VALUE).orElseThrow();
        assertThat(a.attempts()).isEqualTo(1);
        assertThat(a.leaseUntilMs()).isEqualTo(clock.nowMs() + LEASE);
        assertThat(a.coordinator()).as("RETURNING koşunun koordinatörünü verir").isEqualTo("coord-00000001");
        assertThat(a.pendingMsAtClaim()).as("bekleme = sahiplenme − oluşturma").isEqualTo(60_001);
        clock.advance(LEASE + 1);
        s.heartbeat(run);
        ClaimedChunk b = s.claim(PREFIX + "w2", LEASE, Long.MAX_VALUE).orElseThrow();
        assertThat(b.id()).isEqualTo(a.id());
        assertThat(b.attempts()).isEqualTo(2);
        clock.advance(LEASE + 1);
        s.heartbeat(run);
        assertThat(s.claim(PREFIX + "w3", LEASE, Long.MAX_VALUE)).as("deneme tavanı").isEmpty();

        String run2 = id("avoid");
        s.createRun(CertSweepSqlH2Test.spec(run2, 5, 5));
        s.addChunks(run2, List.of(new CertChunkSpec(1, List.of(CertSweepSqlH2Test.entry(0)), PREFIX + "bad")));
        assertThat(s.claim(PREFIX + "bad", LEASE, Long.MAX_VALUE).orElseThrow().chunkNo()).isZero();
        assertThat(s.claim(PREFIX + "bad", LEASE, Long.MAX_VALUE)).as("ikinci görüş parçası kaçınılan işçiye verilmez").isEmpty();
        assertThat(s.claim(PREFIX + "good", LEASE, Long.MAX_VALUE).orElseThrow().chunkNo()).isEqualTo(1);
        assertThat(s.markDeciding(run2, "{}", 0, 0, 0)).isTrue();
    }

    @Test
    @DisplayName("PostgreSQL (FX-WORKER SW-M1 / CR-L1 / CR-H-1 b): bekletme eşiği yalnız başka koordinatörün ilk görüş parçalarına; kendi koşusu "
            + "ve ikinci görüş muaf; MIN = yalnız muaflar; RETURNING koordinatör / oluşturma / kaçınılan işçi döner — iki yolda aynı")
    void claimGateLeaderExemptionsOnPostgres() {
        for (Boolean dialect : new Boolean[] {null, Boolean.FALSE}) {   // tek ifade (CTE … RETURNING) ve taşınabilir CAS
            JdbcCertSweepStore s = dialect == null ? store() : new JdbcCertSweepStore(jdbc(), clock, dialect);
            s.markTablesReady(true);
            String tag = dialect == null ? "pg" : "portable";
            String coord = PREFIX + "coord-" + tag;
            String run = id("lead-" + tag);
            CertRunSpec base = CertSweepSqlH2Test.spec(run, 20, 20);
            s.createRun(new CertRunSpec(base.runId(), base.kind(), coord, base.evictCaches(), base.domainsTotal(), base.chunkSize(),
                    base.chunks()));
            long now = clock.nowMs();
            assertThat(s.claim(PREFIX + "w-" + tag, LEASE, now)).as(tag + ": başka koordinatörün 0 sn'lik parçası").isEmpty();
            assertThat(s.claim(PREFIX + "w-" + tag, LEASE, Long.MIN_VALUE)).as(tag + ": MIN = yalnız muaflar").isEmpty();
            ClaimedChunk own = s.claim(coord, LEASE, Long.MIN_VALUE).orElseThrow();
            assertThat(own.runId()).as(tag + ": kendi koşusu muaf").isEqualTo(run);
            assertThat(own.coordinator()).isEqualTo(coord);
            assertThat(own.createdMs()).isEqualTo(now);
            assertThat(own.avoidWorker()).isNull();

            s.addChunks(run, List.of(new CertChunkSpec(1, List.of(CertSweepSqlH2Test.entry(0)), PREFIX + "bad-" + tag)));
            ClaimedChunk so = s.claim(PREFIX + "w-" + tag, LEASE, Long.MIN_VALUE).orElseThrow();
            assertThat(so.chunkNo()).as(tag + ": ikinci görüş muaf").isEqualTo(1);
            assertThat(so.avoidWorker()).isEqualTo(PREFIX + "bad-" + tag);
            assertThat(so.coordinator()).isEqualTo(coord);
            assertThat(s.markDeciding(run, "{}", 0, 0, 0)).isTrue();
        }
    }

    @Test
    @DisplayName("PostgreSQL: tamamlama CAS'ı + EXISTS (koşu RUNNING), bırakma, iptal, karar / kapanış, öksüz kapatma, budama, durum")
    void lifecycleOnPostgres() {
        JdbcCertSweepStore s = store();
        String run = id("life");
        s.createRun(CertSweepSqlH2Test.spec(run, 60, 20));
        ClaimedChunk a = s.claim(PREFIX + "w1", LEASE, Long.MAX_VALUE).orElseThrow();
        ClaimedChunk b = s.claim(PREFIX + "w1", LEASE, Long.MAX_VALUE).orElseThrow();
        ClaimedChunk c = s.claim(PREFIX + "w2", LEASE, Long.MAX_VALUE).orElseThrow();
        assertThat(s.renew(PREFIX + "w1", List.of(a.id(), b.id(), c.id()), LEASE)).isEqualTo(2);
        ChunkCompletion done = CertSweepSqlH2Test.completion(a, PREFIX + "w1");
        assertThat(s.complete(new ChunkCompletion(a.id(), "yanlis", PREFIX + "w1", done.resultsJson(), 20, 0, 0, 0))).isFalse();
        assertThat(s.complete(done)).isTrue();
        assertThat(s.release(PREFIX + "w1", List.of(a.id(), b.id()))).as("yalnız CLAIMED b").isEqualTo(1);
        assertThat(s.cancel(run, c.id())).isTrue();
        assertThat(s.complete(CertSweepSqlH2Test.completion(c, PREFIX + "w2"))).isFalse();
        Map<Long, String> res = s.readResults(run, List.of(a.id(), b.id(), c.id()));
        assertThat(res).containsOnlyKeys(a.id());
        assertThat(ChunkCompletion.decodeResults(res.get(a.id()))).hasSize(20);

        CertSweepStatus st = s.status(5);
        assertThat(st.openRun()).isNotNull();
        assertThat(st.chunks(CertSweepStore.CHUNK_PENDING)).isGreaterThanOrEqualTo(1);

        ClaimedChunk again = s.claim(PREFIX + "w3", LEASE, Long.MAX_VALUE).orElseThrow();
        assertThat(again.id()).isEqualTo(b.id());
        assertThat(s.markDeciding(run, "{}", 1, 0, 0)).isTrue();
        assertThat(s.complete(CertSweepSqlH2Test.completion(again, PREFIX + "w3"))).as("koşu RUNNING değil").isFalse();
        assertThat(s.heartbeat(run)).isFalse();

        String orphan = id("orphan");
        s.createRun(CertSweepSqlH2Test.spec(orphan, 20, 20));
        List<CertSweepStatus.Run> settled = s.settleOrphans();
        assertThat(settled).extracting(CertSweepStatus.Run::runId).contains(run, orphan);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM cert_sweep_chunks WHERE run_id LIKE ?", Integer.class, PREFIX + "%"))
                .isZero();
        assertThat(s.finish(run, CertSweepStore.RUN_DONE, CertSweepStore.OUTCOME_DECIDED, false)).as("zaten öksüz kapatıldı").isFalse();
        clock.advance(CertChunking.RUN_RETENTION_MS + 1);
        assertThat(s.pruneRuns(clock.nowMs() - CertChunking.RUN_RETENTION_MS, CertChunking.RUN_PRUNE_LIMIT))
                .isGreaterThanOrEqualTo(2);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM cert_sweep_runs WHERE run_id LIKE ?", Integer.class, PREFIX + "%"))
                .isZero();
    }

    @Test
    @DisplayName("PostgreSQL: sahiplenme aday sorgusu idx_csc_state ile koşar (FOR UPDATE OF k SKIP LOCKED)")
    void claimUsesStateIndex() {
        JdbcCertSweepStore s = store();
        String run = id("plan");
        s.createRun(CertSweepSqlH2Test.spec(run, 10_000, 20));
        jdbc().execute("ANALYZE cert_sweep_chunks");
        String plan = jdbc().execute((ConnectionCallback<String>) conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("SET enable_seqscan = off");
                try (ResultSet rs = st.executeQuery("EXPLAIN SELECT k.id FROM cert_sweep_chunks k WHERE (k.state = 'PENDING' OR "
                        + "(k.state = 'CLAIMED' AND k.lease_until_ms < 1)) AND k.attempts < 2 AND k.created_ms < 9 "
                        + "ORDER BY k.id LIMIT 1 FOR UPDATE OF k SKIP LOCKED")) {
                    StringBuilder sb = new StringBuilder();
                    while (rs.next()) sb.append(rs.getString(1)).append('\n');
                    return sb.toString();
                } finally {
                    st.execute("RESET enable_seqscan");
                }
            }
        });
        assertThat(plan).as(plan).contains("Index").doesNotContain("Seq Scan on cert_sweep_chunks");
    }

    @Test
    @DisplayName("PostgreSQL: salt okunur işlem içinden depo bean'i kurar / sahiplenir / okur — dış işlem bozulmaz (NOT_SUPPORTED)")
    void readOnlyTransactionNotPoisoned() {
        CertSweepStore bean = PostgresIt.app().bean(CertSweepStore.class);
        PlatformTransactionManager txm = PostgresIt.app().bean(PlatformTransactionManager.class);
        TransactionTemplate tt = new TransactionTemplate(txm);
        tt.setReadOnly(true);
        String run = id("ro");
        Integer after = tt.execute(status -> {
            bean.createRun(CertSweepSqlH2Test.spec(run, 20, 20));
            assertThat(bean.claim(PREFIX + "w", LEASE, Long.MAX_VALUE)).isPresent();
            assertThat(bean.status(5).lastRuns()).isNotEmpty();
            return jdbc().queryForObject("SELECT 1", Integer.class);
        });
        assertThat(after).as("dış işlem sağlam").isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM cert_sweep_chunks WHERE run_id = ? AND state = 'CLAIMED'",
                Integer.class, run)).isEqualTo(1);
    }

    @Test
    @DisplayName("PostgreSQL: taşınabilir CAS yolu (tür bilinmiyorken) de doğru — sahiplenme, tamamlama")
    void portablePathIsValidOnPostgres() {
        JdbcCertSweepStore portable = new JdbcCertSweepStore(jdbc(), clock, Boolean.FALSE);
        portable.markTablesReady(true);
        String run = id("portable");
        portable.createRun(CertSweepSqlH2Test.spec(run, 40, 20));
        ClaimedChunk a = portable.claim(PREFIX + "w", LEASE, Long.MAX_VALUE).orElseThrow();
        ClaimedChunk b = portable.claim(PREFIX + "w", LEASE, Long.MAX_VALUE).orElseThrow();
        assertThat(List.of(a.chunkNo(), b.chunkNo())).containsExactly(0, 1);
        assertThat(portable.complete(CertSweepSqlH2Test.completion(a, PREFIX + "w"))).isTrue();
        assertThat(portable.claim(PREFIX + "w", LEASE, Long.MAX_VALUE)).isEmpty();
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
