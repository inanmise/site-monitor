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
 * iş parçacıklarıyla her parçayı tam bir kez ölçer. İnceleme düzeltmeleri (FX-WORKER, 2026-10-29): lider kendi koşusunu ve
 * ikinci görüşü beklemeden tam bütçeyle alır, işi kimse almıyorsa (≥ 30 sn bekleyen parça) tam bütçeye geçer; kendi parçasını
 * yeniden alan işçi bütçe sızdırmaz; anahtar açık tur sırasında kapanınca 2 × kira boyunca bırakılan parçaları alır; uyarı
 * hız sınırı ileti türü başına.
 */
class CertChunkWorkerTest {

    static final long T0 = CertSweepSqlH2Test.T0;
    static final long LEASE = 60_000L;
    /**
     * Kural testlerinin ayarı: 40 alan adı bütçesi (eski varsayılan 2 × çekirdek), en çok 4 parça — açık bütçe ayarında lider payı
     * sınırsız (40 × %50 = 20). Yeni varsayılan (6 × çekirdek, lider ≤ çekirdek; C2 B-1) {@code defaultBudgetSixTimesCore} ile.
     */
    static final CertChunking.Tuning DEFAULT = new CertChunking.Tuning(true, 20, 40, 4, 40, 60_000L, 10_000L, 50, 250L, 5_000L);

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
    @DisplayName("Bütçe: 6 × çekirdek (çekirdek 7 → 42) → aynı anda 2 parça; çekirdek canlı büyür; parça sınırı (3) bütçeden önce; uçuşta parça yoksa bütçeden büyük tek parça alınır")
    void budgetAndMaxChunks() throws Exception {
        store.createRun(CertSweepSqlH2Test.spec("run-b", 200, 20));
        FakeRunner r = runner();
        r.core = 7;    // varsayılan bütçe kuralı 6 × çekirdek = 42 alan adı
        r.block();
        CertChunkWorker w = worker(tuning(4, 0, 50, true), r);
        w.startForTest(null);
        assertThat(w.claimRound()).isEqualTo(2);
        assertThat(w.status().budget()).isEqualTo(42);
        assertThat(w.status().inflightDomains()).isEqualTo(40);
        assertThat(w.claimRound()).as("bütçe dolu — denenmez").isEqualTo(-1);
        r.core = 14;   // görev havuzu canlı büyütüldü → bütçe 84
        assertThat(w.claimRound()).isEqualTo(2);
        assertThat(w.status().budget()).isEqualTo(84);
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
        assertThat(leader.claimRound()).as("yarı bütçe dolu: yalnız muaf (kendi koşusu / ikinci görüş) parçalar denenir — yok")
                .isZero();
        assertThat(leader.heldCount()).isEqualTo(1);
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
        w.tick();   // boş sahiplenme
        nanos.addAndGet(DEFAULT.offGraceMs() * 1_000_000L + 1_000_000_000L);   // kapanış sonrası süre (2 × kira) doldu
        w.tick();   // artık boşta
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
    @DisplayName("Entegrasyon: işçi gerçekten başlayınca koordinatöre kendi dürtmesini verir (yeni koşu 5 sn beklemeden alınır)")
    void startHandsPokeToCoordinator() {
        CertRunCoordinator coordinator = mock(CertRunCoordinator.class);
        CertChunkWorker w = worker(DEFAULT, runner());
        org.springframework.test.util.ReflectionTestUtils.setField(w, "coordinator",
                new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("c", coordinator))
                        .getBeanProvider(CertRunCoordinator.class));
        org.mockito.Mockito.verifyNoInteractions(coordinator);
        w.start();
        org.mockito.ArgumentCaptor<Runnable> poke = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        org.mockito.Mockito.verify(coordinator).setLocalWorkerPoke(poke.capture());
        poke.getValue().run();   // dürtme çağrılabilir (bellek; iş parçacığını uyandırır)

        // pod'da işçi kapalı → başlamaz, dürtme de verilmez
        CertRunCoordinator other = mock(CertRunCoordinator.class);
        CertChunkWorker off = worker(tuning(4, 0, 50, false), runner());
        org.springframework.test.util.ReflectionTestUtils.setField(off, "coordinator",
                new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("c", other))
                        .getBeanProvider(CertRunCoordinator.class));
        off.start();
        org.mockito.Mockito.verifyNoInteractions(other);
    }

    @Test
    @DisplayName("C2 B-1: varsayılan bütçe 6 × çekirdek (120) — takipçi aynı anda 6 parça; lider + başka canlı pod varken en çok çekirdek (20)")
    void defaultBudgetSixTimesCore() throws Exception {
        store.createRun(CertSweepSqlH2Test.spec("run-dflt", 400, 20));
        FakeRunner r = runner();
        r.block();
        CertChunkWorker w = worker(CertChunking.Tuning.DEFAULTS, r);
        w.startForTest(null);
        assertThat(w.claimRound()).isEqualTo(6);
        assertThat(w.status().budget()).isEqualTo(120);
        assertThat(w.status().inflightDomains()).isEqualTo(120);
        assertThat(CertChunking.Tuning.DEFAULTS.leaderBudget(20)).isEqualTo(20);
        r.open();
        await(() -> w.heldCount() == 0, 5_000);
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

    // ── FX-WORKER (2026-10-29): inceleme bulguları SW-M1 / CR-L1 / CR-H-1 b / SW-L1 / SW-L2 / SW-L5 ─────────────────

    @Test
    @DisplayName("CR-L1: tarama lideri KENDİ koordine ettiği koşunun parçalarını beklemeden ve TAM bütçeyle alır; başka işçi için bekletme sürer")
    void leaderClaimsOwnRunAtOnceWithFullBudget() {
        store.createRun(CertSweepSqlH2Test.spec("run-own", 200, 20));   // koordinatör "coord-00000001"
        FakeRunner r = runner();
        r.block();
        CertChunkWorker leader = worker(DEFAULT, store, r, () -> true, () -> true, "coord-00000001");
        leader.startForTest(null);
        assertThat(leader.claimRound()).as("0 sn'lik parçalar ama KENDİ koşusu — tam bütçe (40) = iki parça").isEqualTo(2);
        assertThat(leader.status().leaderYielding()).isTrue();
        assertThat(leader.status().budget()).as("kendi koşusunun parçaları tam bütçeyle").isEqualTo(40);
        assertThat(leader.status().inflightDomains()).isEqualTo(40);

        FakeRunner r2 = runner();
        r2.block();
        CertChunkWorker otherLeader = worker(DEFAULT, store, r2, () -> true, () -> true, "pod-l-00000002");
        otherLeader.startForTest(null);
        assertThat(otherLeader.claimRound()).as("başka pod'un koşusu: lider bekletmesi (10 sn) sürer").isZero();
        r.open();
        r2.open();
    }

    @Test
    @DisplayName("SW-M1: işi kimse almıyorsa (son aldığı parça ≥ 30 sn beklemiş) çekilen lider tam bütçeye geçer; 12 sn'lik parçada yarı bütçe kalır; kanıt her alınan parçayla güncellenir")
    void leaderAloneStopsYielding() throws Exception {
        store.createRun(CertSweepSqlH2Test.spec("run-y", 120, 20));   // 6 parça, başka koordinatör
        FakeRunner r = runner();
        r.block();
        CertChunkWorker leader = worker(DEFAULT, store, r, () -> true, () -> true, "pod-l-00000001");
        leader.startForTest(null);
        clock.advance(12_000);
        store.heartbeat("run-y");
        assertThat(leader.claimRound()).as("12 sn beklemiş parça — yarı bütçe (20) = bir parça").isEqualTo(1);
        assertThat(leader.status().budget()).isEqualTo(20);
        r.open();
        await(() -> leader.heldCount() == 0, 5_000);

        clock.advance(28_000);   // kalan parçalar 40 sn'dir bekliyor — kimse almıyor
        store.heartbeat("run-y");
        r.block();
        assertThat(leader.claimRound()).as("40 sn beklemiş parça → tam bütçe (40) = iki parça").isEqualTo(2);
        assertThat(leader.status().budget()).isEqualTo(40);
        assertThat(leader.status().leaderYielding()).as("bekletme sürer").isTrue();
        r.open();
        await(() -> leader.heldCount() == 0, 5_000);
        for (int i = 0; i < 5 && count("run_id = 'run-y' AND state = 'PENDING'") > 0; i++) {
            leader.claimRound();
            await(() -> leader.heldCount() == 0, 5_000);
        }
        assertThat(count("run_id = 'run-y' AND state = 'DONE'")).isEqualTo(6);
        assertThat(leader.claimRound()).as("bekletmeli sahiplenme boş").isZero();

        store.createRun(CertSweepSqlH2Test.spec("run-y2", 120, 20));
        clock.advance(12_000);
        store.heartbeat("run-y2");
        r.block();
        assertThat(leader.claimRound()).as("yeni turun 12 sn'lik parçası — kanıt güncellendi, yarı bütçe").isEqualTo(1);
        assertThat(leader.status().budget()).isEqualTo(20);
        r.open();
    }

    // ── FX2-WORKER (2026-10-29): L-2 — rahatlama başka işçilerin hareketsizliğine dayanır, çekirdeği aşmaz ──────────

    /** Varsayılan bütçe KURALI (6 × çekirdek = 120, lider payı 20, rahatlama 40), en çok 4 parça, en az 1 alan adı. */
    static final CertChunking.Tuning DEFAULT_RULE = new CertChunking.Tuning(true, 20, 1, 4, 0, 60_000L, 10_000L, 50, 250L, 5_000L);

    @Test
    @DisplayName("L-2 (inceleme sondası): başka işçi kuyruğu işlerken (canlı kirayla iki parça tutuyor) 35 sn'lik turda çekilen lider "
            + "yarı payda kalır — bir parça / 20; o işçi ölünce (kiraları doldu) rahatlama = 40, iki parça (tam bütçe 120 değil)")
    void leaderKeepsShareWhileAnotherWorkerWorks() throws Exception {
        store.createRun(CertSweepSqlH2Test.spec("run-w", 120, 20));   // 6 parça, koordinatör "coord-00000001"
        String follower = "pod-c-0000000c";
        assertThat(store.claim(follower, LEASE, Long.MAX_VALUE)).isPresent();
        assertThat(store.claim(follower, LEASE, Long.MAX_VALUE)).isPresent();
        clock.advance(35_000);   // tur 35 sn'lik — tüm parçalar birlikte yazıldı, çalınan parça da 35 sn'lik
        store.heartbeat("run-w");
        FakeRunner r = runner();
        r.block();
        CertChunkWorker leader = worker(DEFAULT_RULE, store, r, () -> true, () -> true, "pod-l-00000001");
        leader.startForTest(null);
        assertThat(leader.claimRound()).as("takipçi çalışıyor — yarı pay (20) = bir parça").isEqualTo(1);
        assertThat(leader.status().budget()).isEqualTo(20);
        assertThat(leader.status().leaderYielding()).isTrue();
        r.open();
        await(() -> leader.heldCount() == 0, 5_000);

        clock.advance(30_000);   // 65 sn: takipçinin kiraları doldu (pod öldü, yenilemiyor) — işi kimse almıyor
        store.heartbeat("run-w");
        r.block();
        assertThat(leader.claimRound()).as("rahatlama 40 = iki parça (alt gruplu 20 görev = çekirdek); tam bütçe 120 dört parça "
                + "alırdı").isEqualTo(2);
        assertThat(leader.status().budget()).isEqualTo(40);
        assertThat(leader.status().leaderYielding()).as("bekletme sürer").isTrue();
        assertThat(count("run_id = 'run-w' AND claimed_by = 'pod-l-00000001' AND state = 'CLAIMED' AND attempts = 2"))
                .as("ölen takipçinin kirası dolan iki parçası").isEqualTo(2);
        r.open();
    }

    @Test
    @DisplayName("L-2: takipçi 30 sn'dir parça almıyor → rahatlama (40 = iki parça); son sahiplenmesi 20 sn önceyse 35 sn'lik "
            + "parçada bile yarı pay")
    void leaderReliefOnlyAfterOthersSilentFor30s() throws Exception {
        store.createRun(CertSweepSqlH2Test.spec("run-q", 160, 20));   // 8 parça
        String follower = "pod-c-0000000c";
        ClaimedChunk a = store.claim(follower, LEASE, Long.MAX_VALUE).orElseThrow();
        clock.advance(5_000);
        store.heartbeat("run-q");
        assertThat(store.complete(CertSweepSqlH2Test.completion(a, follower))).isTrue();
        clock.advance(10_000);   // 15 sn: takipçi son parçasını alıp bitirir, sonra susar (işçisi durdu)
        store.heartbeat("run-q");
        ClaimedChunk b = store.claim(follower, LEASE, Long.MAX_VALUE).orElseThrow();
        clock.advance(5_000);
        store.heartbeat("run-q");
        assertThat(store.complete(CertSweepSqlH2Test.completion(b, follower))).isTrue();

        clock.advance(15_000);   // 35 sn: parça 35 sn bekledi ama takipçi yalnız 20 sn'dir sessiz
        store.heartbeat("run-q");
        FakeRunner r = runner();
        r.block();
        CertChunkWorker leader = worker(DEFAULT_RULE, store, r, () -> true, () -> true, "pod-l-00000001");
        leader.startForTest(null);
        assertThat(leader.claimRound()).as("takipçi 20 sn önce parça aldı — yarı pay").isEqualTo(1);
        assertThat(leader.status().budget()).isEqualTo(20);
        r.open();
        await(() -> leader.heldCount() == 0, 5_000);

        clock.advance(15_000);   // 50 sn: takipçi 35 sn'dir sessiz
        store.heartbeat("run-q");
        r.block();
        assertThat(leader.claimRound()).as("işi kimse almıyor — rahatlama 40 = iki parça").isEqualTo(2);
        assertThat(leader.status().budget()).isEqualTo(40);
        r.open();
        await(() -> leader.heldCount() == 0, 5_000);
        r.block();
        assertThat(leader.claimRound()).as("lider tek başına: rahatlama sürer (kendi parçaları sayılmaz)").isEqualTo(2);
        assertThat(leader.status().budget()).isEqualTo(40);
        r.open();
    }

    @Test
    @DisplayName("CR-H-1 b: ikinci görüş parçalarını çekilen lider beklemeden ve TAM bütçeyle alır; aynı turun 0 sn'lik ilk görüş parçası beklemede kalır")
    void leaderClaimsSecondOpinionsAtOnceWithFullBudget() {
        store.createRun(CertSweepSqlH2Test.spec("run-so", 20, 20));   // tek ilk görüş parçası (başka koordinatör)
        List<CertChunkSpec> opinions = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            List<CertChunkSpec.Entry> e = new ArrayList<>();
            for (int i = 0; i < 10; i++) e.add(CertSweepSqlH2Test.entry(k * 10 + i));
            opinions.add(new CertChunkSpec(1 + k, e, "pod-bad-0000000b"));
        }
        assertThat(store.addChunks("run-so", opinions)).isEqualTo(3);
        FakeRunner r = runner();
        r.block();
        CertChunkWorker leader = worker(DEFAULT, store, r, () -> true, () -> true, "pod-l-00000001");
        leader.startForTest(null);
        assertThat(leader.claimRound()).as("üç ikinci görüş parçası (30 alan adı) beklemeden, tam bütçeyle").isEqualTo(3);
        assertThat(count("run_id = 'run-so' AND avoid_worker IS NOT NULL AND state = 'CLAIMED' AND claimed_by = 'pod-l-00000001'"))
                .isEqualTo(3);
        assertThat(count("run_id = 'run-so' AND avoid_worker IS NULL AND state = 'PENDING'"))
                .as("0 sn'lik ilk görüş parçası liderin bekletmesine takılır").isEqualTo(1);
        assertThat(leader.status().budget()).isEqualTo(40);
        r.open();
    }

    @Test
    @DisplayName("SW-L1: kendi kirası dolan parçasını yeniden alan işçi: eski koşucu atılır, uçuştaki alan adı sızmaz, tek tamamlama")
    void sameOwnerReclaimDoesNotLeakBudget() throws Exception {
        AtomicBoolean renewFails = new AtomicBoolean(false);
        CertSweepStore flaky = new Delegating(store) {
            @Override
            public int renew(String owner, Collection<Long> ids, long leaseMs) {
                if (renewFails.get()) throw new org.springframework.dao.QueryTimeoutException("yenileme takıldı");
                return super.renew(owner, ids, leaseMs);
            }
        };
        store.createRun(CertSweepSqlH2Test.spec("run-sl", 20, 20));   // TEK parça
        FakeRunner r = runner();
        r.block();
        CertChunkWorker w = worker(DEFAULT, flaky, r, () -> false, () -> false, "pod-a-00000001");
        w.startForTest(null);
        assertThat(w.claimRound()).isEqualTo(1);
        assertThat(w.status().inflightDomains()).isEqualTo(20);

        renewFails.set(true);
        clock.advance(LEASE + 1_000);
        nanos.addAndGet((LEASE + 1_000) * 1_000_000L);
        store.heartbeat("run-sl");   // koordinatör yaşıyor
        w.tick();                    // yenileme düşer, sahiplenme turu aynı (kirası dolmuş) parçayı yeniden alır
        assertThat(mp.jdbc().queryForObject("SELECT attempts FROM cert_sweep_chunks WHERE run_id = 'run-sl'", Integer.class))
                .isEqualTo(2);
        assertThat(w.heldCount()).isEqualTo(1);
        assertThat(w.status().inflightDomains()).as("aynı parça iki kez sayılmaz").isEqualTo(20);

        r.open();
        await(() -> w.heldCount() == 0, 5_000);
        await(() -> w.status().completed() + w.status().discarded() == 2, 5_000);
        assertThat(w.status().inflightDomains()).as("hiçbir şey tutulmuyorken uçuşta alan adı 0").isZero();
        assertThat(w.status().completed()).as("yeni belirteçli koşucu tamamlar").isEqualTo(1);
        assertThat(w.status().discarded()).as("eski koşucunun sonucu atılır").isEqualTo(1);
        assertThat(w.status().late()).isZero();
        assertThat(count("run_id = 'run-sl' AND state = 'DONE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("SW-L2: anahtar açık koşu sırasında kapatıldı, başka pod parçalarını bıraktı → boştaki işçi 2 × kira boyunca alır; sonra sorgusuz; dürtme bir kez dener")
    void switchedOffKeepsClaimingWithinGrace() throws Exception {
        store.createRun(CertSweepSqlH2Test.spec("run-g", 40, 20));   // iki parça
        FakeRunner rb = runner();
        rb.block();
        JdbcCertSweepStore sb = new JdbcCertSweepStore(mp.jdbc(), clock, null);
        sb.markTablesReady(true);
        AtomicLong nb = new AtomicLong(5_000_000_000L);
        CertChunkWorker b = new CertChunkWorker(sb, chunking(DEFAULT), () -> rb, () -> false, () -> false, "pod-b-00000002",
                nb::get);
        workers.add(b);
        b.startForTest(null);
        assertThat(b.claimRound()).isEqualTo(2);

        FakeRunner ra = runner();
        CertChunkWorker a = worker(DEFAULT, ra);
        a.startForTest(new Direct());
        a.tick();   // sahiplenecek bir şey yok → son sahiplenme boş
        assertThat(a.heldCount()).isZero();

        enabled.set(false);         // yönetici anahtarı açık koşu sırasında kapattı
        b.onContextClosed(null);    // pod b kapanıyor → iki parçayı bırakır (deneme 0)
        await(() -> count("run_id = 'run-g' AND state = 'PENDING'") == 2, 5_000);
        for (int i = 0; i < 5 && count("run_id = 'run-g' AND state = 'DONE'") < 2; i++) {
            nanos.addAndGet(6_000_000_000L);
            clock.advance(6_000);
            store.heartbeat("run-g");
            a.tick();
        }
        assertThat(count("run_id = 'run-g' AND state = 'DONE' AND worker = 'pod-a-00000001'"))
                .as("bırakılan parçalar kapanış sonrası süre içinde alındı").isEqualTo(2);
        assertThat(ra.measured).hasSize(40);

        // süre doldu: yalnız bellek okuması — yeni bir koşunun parçası alınmaz
        nanos.addAndGet(DEFAULT.offGraceMs() * 1_000_000L + 1_000_000_000L);
        store.createRun(CertSweepSqlH2Test.spec("run-g2", 20, 20));
        connections.set(0);
        for (int i = 0; i < 3; i++) {
            nanos.addAndGet(6_000_000_000L);
            assertThat(a.tick()).isEqualTo(DEFAULT.idlePollMs());
        }
        assertThat(connections).as("kapalı + süre doldu + boşta: sorgu yok").hasValue(0);
        assertThat(count("run_id = 'run-g2' AND state = 'PENDING'")).isEqualTo(1);

        a.poke();   // dürtme: bir kez dener
        a.tick();
        assertThat(count("run_id = 'run-g2' AND state = 'DONE'")).as("dürtme sahiplenmeyi beklemeden dener").isEqualTo(1);
    }

    @Test
    @DisplayName("SW-L5: uyarı hız sınırı ileti türü başına — sahiplenme uyarısının hemen ardından 'sonuç yazılamadı' yine WARN")
    void warnRateLimitIsPerKind() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CertChunkWorker.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        ch.qos.logback.classic.Level before = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            AtomicBoolean claimFails = new AtomicBoolean(true);
            AtomicBoolean completeFails = new AtomicBoolean(false);
            CertSweepStore flaky = new Delegating(store) {
                @Override
                public Optional<ClaimedChunk> claim(String owner, long leaseMs, long createdBeforeMs) {
                    if (claimFails.get()) throw new org.springframework.dao.QueryTimeoutException("sahiplenme düştü");
                    return super.claim(owner, leaseMs, createdBeforeMs);
                }

                @Override
                public boolean complete(ChunkCompletion c) {
                    if (completeFails.get()) throw new org.springframework.dao.TransientDataAccessResourceException("blip");
                    return super.complete(c);
                }
            };
            store.createRun(CertSweepSqlH2Test.spec("run-w", 20, 20));
            CertChunkWorker w = worker(tuning(1, 0, 50, true), flaky, runner(), () -> false, () -> false, "pod-w-00000001");
            w.startForTest(new Direct());
            assertThat(w.claimRound()).isZero();
            claimFails.set(false);
            completeFails.set(true);
            assertThat(w.claimRound()).isEqualTo(1);
            claimFails.set(true);
            assertThat(w.claimRound()).isZero();   // aynı türün ikinci uyarısı aynı dakikada → DEBUG
            List<String> warns = logs.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(warns).hasSize(2);
            assertThat(warns.get(0)).contains("sahiplenilemedi");
            assertThat(warns.get(1)).as("tamamlama uyarısı sahiplenme uyarısının gölgesinde kalmaz").contains("sonucu yazılamadı");
            assertThat(logs.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.DEBUG
                    && e.getFormattedMessage().contains("sahiplenilemedi")).count()).isEqualTo(1);
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(before);
        }
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
