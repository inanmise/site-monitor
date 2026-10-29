package com.sitemonitor.service.certsweep;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.checks.CheckJobRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Parça işçisi (2026-10-29, çok pod Sürüm 3-4 paket A, tasarım §9.2 A) — gerçek H2 deposu, sahte ölçme gövdesi
 * ({@link CertChunkRunner}) ve elle ilerletilen saatlerle: sahiplen → ölç → tamamla; bütçe (2 × çekirdek), parça sınırı,
 * bütçeden büyük tek parça; tarama liderinin payı (yalnız 10 sn'den eski parçalar, yarı bütçe; başka canlı pod yoksa tam
 * bütçe; pay 0 → hiç); kira yenilemesi 3 dakikalık parçayı korur; kapanış tutulan parçaları BEKLEMEDEN bırakır ve sonuçları
 * atar; kapanan ölçüm gövdesi → atılır; geç tamamlama sayılır; ölçüm istisnası → her alan adı {@code failed}; gövde yokken
 * sahiplenme yok; pod'da işçi kapalı → başlamaz; anahtar sonradan kapanınca açık koşuyu bitirir sonra sorgusuz boşta bekler;
 * tamamlama veritabanı hatasında yeniden denenir; dürtme beklemeden sahiplendirir; durum yalnız bellek; iki pod gerçek
 * iş parçacıklarıyla her parçayı tam bir kez ölçer.
 */
class CertChunkWorkerTest {

    static final long T0 = CertSweepSqlH2Test.T0;
    static final long LEASE = 60_000L;
    static final CertChunking.Tuning DEFAULT = CertChunking.Tuning.DEFAULTS;

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    final CertSweepTestClock clock = new CertSweepTestClock(T0);
    final AtomicLong nanos = new AtomicLong(5_000_000_000L);
    final AtomicBoolean enabled = new AtomicBoolean(true);
    final AtomicInteger connections = new AtomicInteger();
    final List<CertChunkWorker> workers = new CopyOnWriteArrayList<>();
    final List<FakeRunner> runners = new CopyOnWriteArrayList<>();
    JdbcCertSweepStore store;

    @BeforeEach
    void setUp() {
        store = new JdbcCertSweepStore(new JdbcTemplate(CertSweepSqlH2Test.countingConnections(mp.dataSource(), connections)),
                clock, null);
        new CertSweepSchemaPatch(mp.jdbc(), store).apply();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (FakeRunner r : runners) r.gate.countDown();
        for (CertChunkWorker w : workers) {
            w.onContextClosed(null);
            w.destroy();
        }
        long deadline = System.currentTimeMillis() + 7_000;
        while (anyCertChunkThread() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        mp.close();
    }

    // ── düzenek ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Sahte ölçme gövdesi: sıralı, kapanabilir, kapılı (gate), fırlatabilir; her 10'uncu alan adı ağ hatası. */
    static class FakeRunner implements CertChunkRunner {
        volatile boolean closing;
        volatile int core = 20;
        volatile RuntimeException fail;
        volatile long sleepMs;
        volatile CountDownLatch gate = new CountDownLatch(0);
        final List<String> measured = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger calls = new AtomicInteger();

        void block() {
            gate = new CountDownLatch(1);
        }

        void open() {
            gate.countDown();
        }

        @Override
        public List<Map<String, Object>> checkDomains(List<Map<String, Object>> rows, String runId) {
            calls.incrementAndGet();
            try {
                if (!gate.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("kapı açılmadı");
                if (sleepMs > 0) Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
            if (fail != null) throw fail;
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String d = (String) row.get("domain");
                measured.add(d);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("domain", d);
                boolean bad = d.startsWith("d") && Integer.parseInt(d.substring(1, d.indexOf('.'))) % 10 == 0;
                m.put("status", bad ? "error" : "valid");
                if (bad) m.put("error_class", "NETWORK");
                m.put("port", row.get("port"));
                m.put("elapsed_ms", 1_200L);
                m.put("run_id", runId);
                out.add(m);
                if (closing) break;   // kapanış: liste KISA döner
            }
            return out;
        }

        @Override
        public boolean closing() {
            return closing;
        }

        @Override
        public long networkErrorCount(List<Map<String, Object>> results) {
            return results.stream().filter(r -> "error".equals(r.get("status")) && "NETWORK".equals(r.get("error_class"))).count();
        }

        @Override
        public int executorCoreSize() {
            return core;
        }
    }

    /** Çağıran iş parçacığında koşan yürütücü (koşucu eşzamanlı — adımlar belirlenimci). */
    static final class Direct extends AbstractExecutorService {
        volatile boolean shut;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shut = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shut = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shut;
        }

        @Override
        public boolean isTerminated() {
            return shut;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    CertChunking chunking(CertChunking.Tuning t) {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> CertChunking.KEY_ENABLED.equals(i.getArgument(0))
                ? enabled.get() : i.getArgument(1));
        return new CertChunking(s, t);
    }

    FakeRunner runner() {
        FakeRunner r = new FakeRunner();
        runners.add(r);
        return r;
    }

    CertChunkWorker worker(CertChunking.Tuning t, CertSweepStore s, FakeRunner r, BooleanSupplier leader, BooleanSupplier other,
                           String owner) {
        CertChunkWorker w = new CertChunkWorker(s, chunking(t), () -> r, leader, other, owner, nanos::get);
        workers.add(w);
        return w;
    }

    CertChunkWorker worker(CertChunking.Tuning t, FakeRunner r) {
        return worker(t, store, r, () -> false, () -> false, "pod-a-00000001");
    }

    static CertChunking.Tuning tuning(int maxChunks, int maxInflight, int sharePct, boolean workerEnabled) {
        return new CertChunking.Tuning(workerEnabled, 20, 40, maxChunks, maxInflight, 60_000L, 10_000L, sharePct, 250L, 5_000L);
    }

    int count(String where) {
        Integer n = mp.jdbc().queryForObject("SELECT COUNT(*) FROM cert_sweep_chunks WHERE " + where, Integer.class);
        return n == null ? 0 : n;
    }

    static boolean anyCertChunkThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("cert-chunk-")) return true;
        }
        return false;
    }

    static void await(BooleanSupplier cond, long ms) throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertThat(cond.getAsBoolean()).as("koşul %d ms içinde sağlanmadı", ms).isTrue();
    }

    // ── testler ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sahiplen → klasik ölçüm → tamamla: üç parça DONE, sonuçlar parça sırasıyla çözülür, ağ hatası sayımı satırda")
    void claimsMeasuresAndCompletes() {
        store.createRun(CertSweepSqlH2Test.spec("run-1", 45, 20));
        FakeRunner r = runner();
        CertChunkWorker w = worker(DEFAULT, r);
        w.startForTest(new Direct());
        assertThat(w.tick()).as("parça alındı — kısa yoklama").isEqualTo(DEFAULT.pollMs());
        assertThat(count("run_id = 'run-1' AND state = 'DONE' AND worker = 'pod-a-00000001'")).isEqualTo(3);
        assertThat(r.calls).hasValue(3);
        assertThat(r.measured).hasSize(45);
        Map<String, Object> row0 = mp.jdbc().queryForMap("SELECT * FROM cert_sweep_chunks WHERE run_id = 'run-1' AND chunk_no = 0");
        assertThat(row0).containsEntry("checked", 20).containsEntry("network_errors", 2).containsEntry("errors", 2);
        List<ChunkCompletion.Result> res = ChunkCompletion.decodeResults((String) row0.get("results_json"));
        assertThat(res).extracting(ChunkCompletion.Result::index).containsExactlyElementsOf(range(0, 20));
        assertThat(res).allMatch(ChunkCompletion.Result::usable);
        assertThat(res.get(0).result()).containsEntry("run_id", "run-1").containsEntry("domain", "d0.example.com");
        CertSweepStatus.Worker st = w.status();
        assertThat(st.claimed()).isEqualTo(3);
        assertThat(st.completed()).isEqualTo(3);
        assertThat(st.domainsMeasured()).isEqualTo(45);
        assertThat(st.inflightChunks()).isZero();
        assertThat(st.lastClaimAtMs()).isEqualTo(T0);
        assertThat(st.budget()).isEqualTo(40);
        assertThat(w.tick()).as("aday kalmadı — uzun yoklama").isEqualTo(DEFAULT.idlePollMs());
    }

    static List<Integer> range(int from, int to) {
        List<Integer> out = new ArrayList<>();
        for (int i = from; i < to; i++) out.add(i);
        return out;
    }

    @Test
    @DisplayName("Bütçe: 2 × çekirdek (40) → aynı anda 2 parça; parça sınırı (3) bütçeden önce; uçuşta parça yoksa bütçeden büyük tek parça alınır")
    void budgetAndMaxChunks() throws Exception {
        store.createRun(CertSweepSqlH2Test.spec("run-b", 200, 20));
        FakeRunner r = runner();
        r.block();
        CertChunkWorker w = worker(DEFAULT, r);
        w.startForTest(null);
        assertThat(w.claimRound()).isEqualTo(2);
        assertThat(w.status().inflightDomains()).isEqualTo(40);
        assertThat(w.claimRound()).as("bütçe dolu — denenmez").isEqualTo(-1);
        r.core = 40;   // görev havuzu canlı büyütüldü → bütçe 80
        assertThat(w.claimRound()).isEqualTo(2);
        assertThat(w.status().budget()).isEqualTo(80);
        assertThat(w.claimRound()).as("parça sınırı (4)").isEqualTo(-1);
        assertThat(w.heldCount()).isEqualTo(4);
        r.open();
        await(() -> w.heldCount() == 0, 5_000);
        assertThat(count("run_id = 'run-b' AND state = 'DONE'")).isEqualTo(4);

        FakeRunner r2 = runner();
        r2.block();
        CertChunkWorker w2 = worker(tuning(3, 100, 50, true), store, r2, () -> false, () -> false, "pod-b-00000002");
        w2.startForTest(null);
        assertThat(w2.claimRound()).as("bütçe 100, parça sınırı 3").isEqualTo(3);

        FakeRunner r3 = runner();
        r3.block();
        CertChunkWorker w3 = worker(tuning(4, 10, 50, true), store, r3, () -> false, () -> false, "pod-c-00000003");
        w3.startForTest(null);
        assertThat(w3.claimRound()).as("bütçe 10 < parça 20: uçuşta parça yokken yine bir parça").isEqualTo(1);
        assertThat(w3.claimRound()).isEqualTo(-1);
        r2.open();
        r3.open();
    }

    @Test
    @DisplayName("Lider payı: başka canlı pod varken yalnız 10 sn'den eski parçalar + yarı bütçe; tek pod'da tam bütçe; pay 0 → hiç almaz")
    void leaderShare() throws Exception {
        store.createRun(CertSweepSqlH2Test.spec("run-l", 200, 20));
        FakeRunner r = runner();
        r.block();
        AtomicBoolean other = new AtomicBoolean(true);
        CertChunkWorker leader = worker(DEFAULT, store, r, () -> true, other::get, "pod-l-00000001");
        leader.startForTest(null);
        clock.advance(5_000);
        assertThat(leader.claimRound()).as("parçalar 5 sn'lik — lider almaz").isZero();
        assertThat(leader.status().leaderYielding()).isTrue();
        assertThat(leader.status().budget()).isEqualTo(20);
        clock.advance(6_000);
        store.heartbeat("run-l");
        assertThat(leader.claimRound()).as("10 sn'den eski — yarı bütçe (20) = bir parça").isEqualTo(1);
        assertThat(leader.claimRound()).isEqualTo(-1);
        other.set(false);
        assertThat(leader.claimRound()).as("başka canlı pod yok — tam bütçe").isEqualTo(1);
        assertThat(leader.status().leaderYielding()).isFalse();
        assertThat(leader.status().budget()).isEqualTo(40);

        FakeRunner r0 = runner();
        CertChunkWorker zero = worker(tuning(4, 0, 0, true), store, r0, () -> true, () -> true, "pod-z-00000009");
        zero.startForTest(new Direct());
        connections.set(0);
        assertThat(zero.claimRound()).as("pay %0 — lider hiç almaz").isEqualTo(-1);
        assertThat(connections).as("bütçe 0 → sorgu yok").hasValue(0);
        r.open();
    }

    @Test
    @DisplayName("Kira yenilemesi 15 sn'de bir: 3 dakikalık parça başka pod'a geçmez, sonucu yazılır")
    void leaseRenewalKeepsLongChunk() throws Exception {
        store.createRun(CertSweepSqlH2Test.spec("run-long", 20, 20));
        FakeRunner r = runner();
        r.block();
        CertChunkWorker w = worker(DEFAULT, r);
        w.startForTest(null);
        assertThat(w.claimRound()).isEqualTo(1);
        JdbcCertSweepStore other = new JdbcCertSweepStore(mp.jdbc(), clock, null);
        for (int i = 0; i < 12; i++) {
            clock.advance(15_000);
            nanos.addAndGet(15_000_000_000L);
            store.heartbeat("run-long");   // koordinatör yaşıyor
            w.tick();
            assertThat(other.claim("pod-x", LEASE, Long.MAX_VALUE)).as("%d. yenileme sonrası", i + 1).isEmpty();
        }
        Long lease = mp.jdbc().queryForObject("SELECT lease_until_ms FROM cert_sweep_chunks WHERE run_id = 'run-long'", Long.class);
        assertThat(lease).isEqualTo(clock.nowMs() + LEASE);
        r.open();
        await(() -> w.heldCount() == 0, 5_000);
        assertThat(w.status().completed()).isEqualTo(1);
        assertThat(w.status().late()).isZero();
    }

    @Test
    @DisplayName("Kapanış: dinleyici BEKLEMEZ, tutulan parçalar (uçuştakiler dahil) yardımcıda PENDING'e (deneme bir geri); geç sonuç atılır")
    void shutdownReleasesWithoutWaiting() throws Exception {
        store.createRun(CertSweepSqlH2Test.spec("run-s", 60, 20));
        FakeRunner r = runner();
        r.block();
        CertChunkWorker w = worker(DEFAULT, r);
        w.startForTest(null);
        assertThat(w.claimRound()).isEqualTo(2);
        long t0 = System.nanoTime();
        w.onContextClosed(null);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).as("kapanış dinleyicisi beklemez").isLessThan(200);
        await(() -> count("run_id = 'run-s' AND state = 'PENDING' AND attempts = 0 AND claimed_by IS NULL") == 3, 5_000);
        await(() -> w.status().released() == 2, 5_000);
        assertThat(w.claimRound()).as("kapanışta yeni sahiplenme yok").isEqualTo(-1);
        r.open();
        await(() -> w.heldCount() == 0, 5_000);
        assertThat(w.status().discarded()).isEqualTo(2);
        assertThat(w.status().completed()).isZero();
        assertThat(count("run_id = 'run-s' AND state = 'DONE'")).as("bırakılan parçanın sonucu yazılmaz").isZero();
        JdbcCertSweepStore other = new JdbcCertSweepStore(mp.jdbc(), clock, null);
        assertThat(other.claim("pod-b", LEASE, Long.MAX_VALUE)).as("başka pod hemen alır").isPresent();
    }

    @Test
    @DisplayName("Ölçme gövdesi kapanıyor: sahiplenme yok; ölçüm sırasında kapanırsa kısa liste ATILIR (tamamlanmaz)")
    void runnerClosing() {
        store.createRun(CertSweepSqlH2Test.spec("run-c", 40, 20));
        FakeRunner r = runner();
        r.closing = true;
        CertChunkWorker w = worker(DEFAULT, r);
        w.startForTest(new Direct());
        assertThat(w.claimAllowed()).isFalse();
        assertThat(w.tick()).isEqualTo(DEFAULT.idlePollMs());
        assertThat(count("state = 'CLAIMED'")).isZero();

        FakeRunner r2 = runner();
        CertChunkWorker w2 = worker(tuning(1, 0, 50, true), store, new FakeRunner() {
            @Override
            public List<Map<String, Object>> checkDomains(List<Map<String, Object>> rows, String runId) {
                r2.closing = true;   // ölçüm sürerken kapanış başladı
                return r2.checkDomains(rows, runId);
            }

            @Override
            public boolean closing() {
                return r2.closing;
            }
        }, () -> false, () -> false, "pod-d-00000004");
        w2.startForTest(new Direct());
        assertThat(w2.claimRound()).isEqualTo(1);
        assertThat(w2.status().discarded()).isEqualTo(1);
        assertThat(w2.status().completed()).isZero();
        assertThat(count("state = 'DONE'")).isZero();
        assertThat(count("state = 'CLAIMED'")).as("kira dolunca başka pod yeniden alır").isEqualTo(1);
    }

    @Test
    @DisplayName("Geç tamamlama: koordinatör ölçüm sürerken parçayı devraldı → 0 satır, 'late' sayılır, sonuç düşer")
    void lateCompletion() throws Exception {
        store.createRun(CertSweepSqlH2Test.spec("run-late", 20, 20));
        FakeRunner r = runner();
        r.block();
        CertChunkWorker w = worker(DEFAULT, r);
        w.startForTest(null);
        assertThat(w.claimRound()).isEqualTo(1);
        long id = store.chunkStates("run-late").get(0).id();
        assertThat(store.cancel("run-late", id)).isTrue();
        r.open();
        await(() -> w.heldCount() == 0, 5_000);
        assertThat(w.status().late()).isEqualTo(1);
        assertThat(w.status().completed()).isZero();
        assertThat(count("state = 'CANCELLED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("Ölçüm istisnası (klasikte turu düşürür): parça her alan adı 'failed' işaretli tamamlanır — koordinatör yeniden ölçer")
    void runnerExceptionCompletesWithFailedMarks() {
        store.createRun(CertSweepSqlH2Test.spec("run-x", 20, 20));
        FakeRunner r = runner();
        r.fail = new IllegalStateException("denetleyici patladı");
        CertChunkWorker w = worker(DEFAULT, r);
        w.startForTest(new Direct());
        assertThat(w.claimRound()).isEqualTo(1);
        String json = mp.jdbc().queryForObject("SELECT results_json FROM cert_sweep_chunks WHERE run_id = 'run-x'", String.class);
        List<ChunkCompletion.Result> res = ChunkCompletion.decodeResults(json);
        assertThat(res).hasSize(20).noneMatch(ChunkCompletion.Result::usable);
        assertThat(res.get(0).result()).containsEntry(CheckJobRunner.KEY_FAILED, "IllegalStateException");
        assertThat(w.failedChunks()).isEqualTo(1);
        assertThat(w.status().completed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Ölçme gövdesi (paket B) yok → sahiplenme yok, sorgu yok; pod'da işçi kapalı → başlamaz, iş parçacığı yok")
    void noRunnerAndWorkerDisabled() {
        store.createRun(CertSweepSqlH2Test.spec("run-n", 20, 20));
        CertChunkWorker w = new CertChunkWorker(store, chunking(DEFAULT), () -> null, () -> false, () -> false, "pod-n", nanos::get);
        workers.add(w);
        w.startForTest(new Direct());
        connections.set(0);
        w.tick();
        assertThat(connections).hasValue(0);
        assertThat(count("state = 'PENDING'")).isEqualTo(1);
        assertThat(w.status().budget()).isZero();

        CertChunkWorker off = worker(tuning(4, 0, 50, false), runner());
        off.start();
        assertThat(off.started()).isFalse();
        assertThat(anyCertChunkThread()).isFalse();
        assertThat(off.status().workerEnabled()).isFalse();
        assertThat(off.status().started()).isFalse();
    }

    @Test
    @DisplayName("Anahtar sonradan kapandı: açık koşuyu bitirir (sahiplenme boş dönene dek), sonra yalnız bellek okuyarak boşta; yeniden açılınca alır")
    void switchedOffLaterDrainsThenIdles() {
        store.createRun(CertSweepSqlH2Test.spec("run-off", 60, 20));
        FakeRunner r = runner();
        CertChunkWorker w = worker(DEFAULT, r);
        w.startForTest(new Direct());
        enabled.set(false);
        w.tick();
        assertThat(count("run_id = 'run-off' AND state = 'DONE'")).as("açık koşu bitirildi").isEqualTo(3);
        w.tick();   // boş sahiplenme → artık boşta
        store.createRun(CertSweepSqlH2Test.spec("run-off2", 20, 20));
        connections.set(0);
        for (int i = 0; i < 5; i++) {
            nanos.addAndGet(10_000_000_000L);
            assertThat(w.tick()).isEqualTo(DEFAULT.idlePollMs());
        }
        assertThat(connections).as("kapalı + boşta: hiçbir sorgu").hasValue(0);
        assertThat(count("run_id = 'run-off2' AND state = 'PENDING'")).isEqualTo(1);
        enabled.set(true);
        w.tick();
        assertThat(count("run_id = 'run-off2' AND state = 'DONE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("Tamamlama veritabanı hatası: yeniden denenir (3'e dek) ve yazılır; üçü de düşerse kira dolunca yeniden ölçülür")
    void completeRetried() {
        store.createRun(CertSweepSqlH2Test.spec("run-rt", 20, 20));
        AtomicInteger failures = new AtomicInteger(1);
        CertSweepStore flaky = new Delegating(store) {
            @Override
            public boolean complete(ChunkCompletion c) {
                if (failures.getAndDecrement() > 0) throw new org.springframework.dao.TransientDataAccessResourceException("blip");
                return super.complete(c);
            }
        };
        CertChunkWorker w = worker(tuning(1, 0, 50, true), flaky, runner(), () -> false, () -> false, "pod-r-00000001");
        w.startForTest(new Direct());
        assertThat(w.claimRound()).isEqualTo(1);
        assertThat(w.status().completed()).isEqualTo(1);
        assertThat(w.errors()[2]).isEqualTo(1);
        store.createRun(CertSweepSqlH2Test.spec("run-rt2", 20, 20));
        failures.set(10);
        assertThat(w.claimRound()).isEqualTo(1);
        assertThat(w.errors()[2]).isEqualTo(1 + CertChunkWorker.COMPLETE_ATTEMPTS);
        assertThat(w.status().completed()).isEqualTo(1);
        assertThat(count("state = 'CLAIMED'")).as("yazılamadı — CLAIMED kalır, kira dolunca yeniden ölçülür").isEqualTo(1);
    }

    @Test
    @DisplayName("Dürtme: boş sahiplenmeden sonra 5 sn beklemeden yeni koşunun parçası alınır; dürtmesiz alınmaz")
    void pokeClaimsAtOnce() {
        FakeRunner r = runner();
        CertChunkWorker w = worker(DEFAULT, r);
        w.startForTest(new Direct());
        assertThat(w.tick()).isEqualTo(DEFAULT.idlePollMs());
        store.createRun(CertSweepSqlH2Test.spec("run-p", 20, 20));
        nanos.addAndGet(1_000_000_000L);
        w.tick();
        assertThat(count("state = 'DONE'")).as("vakti gelmedi").isZero();
        w.poke();
        w.tick();
        assertThat(count("state = 'DONE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("Durum yalnız bellek (sorgu yok); başlamamış işçi saat bayatlığı bildirmez")
    void statusIsMemoryOnly() {
        CertChunkWorker w = worker(DEFAULT, runner());
        clock.stale = true;
        connections.set(0);
        CertSweepStatus.Worker st = w.status();
        assertThat(connections).hasValue(0);
        assertThat(st.started()).isFalse();
        assertThat(st.dbClockStale()).isFalse();
        assertThat(st.budget()).isEqualTo(40);
        w.startForTest(new Direct());
        assertThat(w.status().dbClockStale()).isTrue();
        assertThat(connections).hasValue(0);
    }

    @Test
    @DisplayName("İki pod gerçek iş parçacıklarıyla: 12 parça her biri tam bir kez ölçülür ve tamamlanır; iki işçi de iş yapar; kapanış temiz")
    void twoPodsRealThreads() throws Exception {
        store.createRun(CertSweepSqlH2Test.spec("run-2p", 240, 20));
        CertChunking.Tuning fast = new CertChunking.Tuning(true, 20, 40, 2, 0, 60_000L, 10_000L, 50, 50L, 250L);
        FakeRunner ra = runner();
        FakeRunner rb = runner();
        ra.sleepMs = 30;
        rb.sleepMs = 30;
        JdbcCertSweepStore sb = new JdbcCertSweepStore(mp.jdbc(), clock, null);
        sb.markTablesReady(true);
        CertChunkWorker a = new CertChunkWorker(store, chunking(fast), () -> ra, () -> false, () -> false, "pod-a-00000001",
                System::nanoTime);
        CertChunkWorker b = new CertChunkWorker(sb, chunking(fast), () -> rb, () -> false, () -> false, "pod-b-00000002",
                System::nanoTime);
        workers.add(a);
        workers.add(b);
        a.start();
        b.start();
        await(() -> count("run_id = 'run-2p' AND state = 'DONE'") == 12, 15_000);
        List<String> all = new ArrayList<>(ra.measured);
        all.addAll(rb.measured);
        assertThat(all).hasSize(240).doesNotHaveDuplicates();
        assertThat(a.status().completed() + b.status().completed()).isEqualTo(12);
        assertThat(a.status().late() + b.status().late()).isZero();
        assertThat(a.status().completed()).isPositive();
        assertThat(b.status().completed()).isPositive();
        assertThat(mp.jdbc().queryForList("SELECT DISTINCT worker FROM cert_sweep_chunks WHERE run_id = 'run-2p'", String.class))
                .containsExactlyInAnyOrder("pod-a-00000001", "pod-b-00000002");
        a.onContextClosed(null);
        b.onContextClosed(null);
        a.destroy();
        b.destroy();
        await(() -> !anyCertChunkThread(), 7_000);
    }

    /** Depoyu sarar (hata enjeksiyonu için). */
    static class Delegating implements CertSweepStore {
        final CertSweepStore d;

        Delegating(CertSweepStore d) {
            this.d = d;
        }

        @Override public long nowMs() { return d.nowMs(); }
        @Override public boolean clockStale() { return d.clockStale(); }
        @Override public boolean tablesReady() { return d.tablesReady(); }
        @Override public List<CertSweepStatus.Run> settleOrphans() { return d.settleOrphans(); }
        @Override public int pruneRuns(long olderThanMs, int limit) { return d.pruneRuns(olderThanMs, limit); }
        @Override public void createRun(CertRunSpec spec) { d.createRun(spec); }
        @Override public int addChunks(String runId, List<CertChunkSpec> chunks) { return d.addChunks(runId, chunks); }
        @Override public boolean heartbeat(String runId) { return d.heartbeat(runId); }
        @Override public List<ChunkState> chunkStates(String runId) { return d.chunkStates(runId); }
        @Override public Map<Long, String> readResults(String runId, Collection<Long> ids) { return d.readResults(runId, ids); }
        @Override public boolean cancel(String runId, long chunkId) { return d.cancel(runId, chunkId); }
        @Override public boolean markDeciding(String runId, String w, int t, int r, int s) { return d.markDeciding(runId, w, t, r, s); }
        @Override public boolean finish(String runId, String state, String outcome, boolean atShutdown) {
            return d.finish(runId, state, outcome, atShutdown);
        }
        @Override public Optional<ClaimedChunk> claim(String owner, long leaseMs, long createdBeforeMs) {
            return d.claim(owner, leaseMs, createdBeforeMs);
        }
        @Override public int renew(String owner, Collection<Long> ids, long leaseMs) { return d.renew(owner, ids, leaseMs); }
        @Override public boolean complete(ChunkCompletion c) { return d.complete(c); }
        @Override public int release(String owner, Collection<Long> ids) { return d.release(owner, ids); }
        @Override public CertSweepStatus status(int lastRuns) { return d.status(lastRuns); }
    }
}
