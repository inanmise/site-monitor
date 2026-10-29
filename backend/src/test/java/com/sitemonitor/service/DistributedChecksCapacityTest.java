package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.service.R33ClusterRig.Node;
import com.sitemonitor.service.R33ClusterRig.Opts;
import com.sitemonitor.service.checks.CheckJobRow;
import com.sitemonitor.service.checks.CheckJobStore;
import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.checks.CheckResultRow;
import com.sitemonitor.service.checks.DistributedChecks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-3 — KAPASİTE / GECİKME (tasarım R3-3 §9.4, §2.3, paket D2).
 *
 * <p>Gerçek iş parçacıkları (her pod'un işçi yoklaması + 16 yuva, işçi kaydı, sonuç boşaltıcısı, MOS'un teyit / kurtarma
 * yürütücüleri), ortak H2, duvar saati. Zaman 1:5 ölçekli: 2.100 izleme 12 sn aralıkla (= 60 sn gerçek → 175 / sn ölçekli =
 * 35 / sn gerçek, 3x hedefi), denetleyici gecikmesi lognormal (medyan 30 ms = 150 ms gerçek, σ 0,5) ve %2 "zaman aşımı" (2 sn =
 * 10 sn gerçek, DOWN — teyit zinciri liderde), gönderim tıkı 1 sn (5 sn), işçi yoklaması 200 ms (1 sn), çalma eşiği 1 sn (5 sn),
 * 3 pod × 16 yuva, lider payı %50. İzleme fazları aralığa dağıtılır (üretimde izlemeler farklı anlarda oluşur).
 *
 * <p>Ölçülen: vade → kontrol başlangıcı gecikmesi (p95 &lt; 30 sn gerçek eşdeğeri), değerlendirici birikimi (DONE → alındı,
 * p95 &lt; 1 sn), çift / kayıp yok (gönderilen = koşturulan = değerlendirilen, hiçbir iş iki kez değerlendirilmez, hiçbir dönem
 * açık işe katılmadı, hiçbiri biçilmedi). Ayrıca {@link CheckLatencyModel}: M/G/c (Erlang C) modeli yuva beklemesinin ihmal
 * edilebilir olduğunu, gecikme bütçesinin gönderim tıkı + yoklamadan oluştuğunu ve sahiplenme kadansının (yuva / poll-ms) pod
 * başına tavanı belirlediğini belgeler.
 */
@Timeout(value = 150, unit = TimeUnit.SECONDS)
class DistributedChecksCapacityTest {

    static final int MONITORS = 2_100;
    /** Zaman ölçeği 1:5 — işçinin sabit 200 ms yoklama adımı ({@code CheckWorker.FLUSH_MS}) ölçekli yoklamaya (1 sn / 5) eşit
     *  olsun; 1:10'da sabit adım sahiplenme hızını gerçeğin yarısına düşürür ve ölçüm kötümser olur. */
    static final long SCALE = 5;
    static final int INTERVAL_S = 12;               // 60 sn gerçek
    static final long DISPATCH_EVERY_MS = 1_000;    // 5 sn gerçek
    static final long POLL_MS = 200;                // 1 sn gerçek
    static final long DRAIN_MS = 400;               // 2 sn gerçek
    static final long STEAL_AFTER_MS = 1_000;       // 5 sn gerçek
    static final long CLASSIC_TICK_MS = 6_000;      // 30 sn gerçek
    static final long WARMUP_MS = 12_000;
    static final long MEASURE_MS = 36_000;
    static final double TIMEOUT_SHARE = 0.02;
    static final long TIMEOUT_SLEEP_MS = 2_000;     // 10 sn gerçek
    static final double MEDIAN_MS = 30.0;           // 150 ms gerçek
    static final double SIGMA = 0.5;
    static final int SLOTS = 16;
    static final int PODS = 3;

    /** Saf M/G/c kuyruk modeli — tasarım §2.3 bütçesi. */
    static final class CheckLatencyModel {
        /** Lognormal + zaman aşımı karışımının ortalama hizmet süresi (ms). */
        static double meanServiceMs() {
            double lognormalMean = MEDIAN_MS * Math.exp(SIGMA * SIGMA / 2);
            return (1 - TIMEOUT_SHARE) * lognormalMean + TIMEOUT_SHARE * TIMEOUT_SLEEP_MS;
        }

        /** Erlang C: c sunucu, a = λ·E[S] trafik yükü → bekleme olasılığı. */
        static double erlangC(int c, double a) {
            if (a >= c) return 1.0;
            double sum = 0;
            double term = 1;
            for (int k = 0; k < c; k++) {
                if (k > 0) term *= a / k;
                sum += term;
            }
            double last = term * a / c;   // a^c / c!
            double pc = last * c / (c - a);
            return pc / (sum + pc);
        }
    }

    @Test
    @DisplayName("Model: 3 pod × 16 yuva (lider %50) ve 3x yükte (35 / sn gerçek) yuva bekleme olasılığı < %1; gecikme bütçesi "
            + "gönderim tıkı (≤ 5 sn) + yoklama (≤ 1 sn) ≈ 6 sn gerçek")
    void queueingModel_budget() {
        double lambdaPerMs = MONITORS / (INTERVAL_S * 1000.0);
        double a = lambdaPerMs * CheckLatencyModel.meanServiceMs();
        int steadySlots = SLOTS / 2 + (PODS - 1) * SLOTS;   // lider yarı payla
        double pWait = CheckLatencyModel.erlangC(steadySlots, a);
        assertThat(a).as("ortalama meşgul yuva").isLessThan(steadySlots * 0.5);
        assertThat(pWait).as("Erlang C bekleme olasılığı").isLessThan(0.01);
        long budgetMs = (DISPATCH_EVERY_MS + POLL_MS) * SCALE;
        assertThat(budgetMs).as("tasarım §2.3: ≈ 6 sn gerçek").isLessThanOrEqualTo(6_000);
        // Sahiplenme kadansı sınırı (BULGU D2-2): bir pod yoklama başına en çok boş yuva kadar alır ve sonraki sahiplenme
        // poll-ms sonra → pod başına tavan = yuva / poll-ms (kontrol süresinden bağımsız). Gerçek varsayılanlar: (8 + 16 + 16) / 1 sn
        // = 40 / sn ≥ 35 / sn (3x hedefi, kullanım %87,5); bekleyen iş steal-after'ı aşınca lider tüm yuvalarıyla alır → 48 / sn.
        double steadyCapPerSec = steadySlots * 1000.0 / POLL_MS / SCALE;
        double stealCapPerSec = PODS * SLOTS * 1000.0 / POLL_MS / SCALE;
        double lambdaReal = lambdaPerMs * 1000.0 / SCALE;
        assertThat(lambdaReal).as("3x hedefi (gerçek / sn)").isCloseTo(35.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(steadyCapPerSec).as("yoklama sınırlı tavan (gerçek / sn)").isEqualTo(40.0).isGreaterThan(lambdaReal);
        assertThat(stealCapPerSec).isEqualTo(48.0);
    }

    /** Ölçüm sarmalayıcısı: gönderilen / eklenen sayılar, vade kuyruğu, alınan satırların birikim yaşı. */
    static final class MeasuringStore implements CheckJobStore {
        final CheckJobStore d;
        final Map<Long, Long> seed;
        final Map<Long, Integer> count;
        final Map<Long, ConcurrentLinkedQueue<Long>> dues;
        final AtomicLong requested;
        final AtomicLong inserted;
        final ConcurrentLinkedQueue<Long> backlog;

        MeasuringStore(CheckJobStore d, Map<Long, Long> seed, Map<Long, Integer> count, Map<Long, ConcurrentLinkedQueue<Long>> dues,
                       AtomicLong requested, AtomicLong inserted, ConcurrentLinkedQueue<Long> backlog) {
            this.d = d;
            this.seed = seed;
            this.count = count;
            this.dues = dues;
            this.requested = requested;
            this.inserted = inserted;
            this.backlog = backlog;
        }

        @Override
        public int dispatch(String type, Collection<Long> monitorIds, long leaseMs, String dispatcher) {
            for (Long id : monitorIds) {
                int k = count.merge(id, 1, Integer::sum) - 1;
                dues.computeIfAbsent(id, x -> new ConcurrentLinkedQueue<>()).add(seed.get(id) + k * INTERVAL_S * 1000L);
            }
            requested.addAndGet(monitorIds.size());
            int n = d.dispatch(type, monitorIds, leaseMs, dispatcher);
            inserted.addAndGet(n);
            return n;
        }

        @Override
        public List<CheckJobRow> claim(Collection<String> types, int limit, String owner, long createdBeforeMs) {
            return d.claim(types, limit, owner, createdBeforeMs);
        }

        @Override
        public boolean[] complete(List<Completion> completions) {
            return d.complete(completions);
        }

        @Override
        public int release(String owner, Collection<Long> ids) {
            return d.release(owner, ids);
        }

        @Override
        public List<CheckResultRow> take(int limit, String leaseOwner) {
            List<CheckResultRow> rows = d.take(limit, leaseOwner);
            long now = System.currentTimeMillis();
            for (CheckResultRow r : rows) if (r.finishedMs() != null) backlog.add(now - r.finishedMs());
            return rows;
        }

        @Override
        public List<CheckJobRow> reap(int limit) {
            return d.reap(limit);
        }

        @Override
        public QueueStats stats() {
            return d.stats();
        }
    }

    static long percentile(List<Long> values, double p) {
        List<Long> v = new ArrayList<>(values);
        Collections.sort(v);
        if (v.isEmpty()) return -1;
        int idx = (int) Math.ceil(p * v.size()) - 1;
        return v.get(Math.max(0, Math.min(v.size() - 1, idx)));
    }

    @Test
    @DisplayName("3 pod × 16 yuva, 2.100 izleme, ölçekli 3x yük: p95 gecikme < 30 sn gerçek eşdeğeri, değerlendirici birikimi "
            + "< 1 sn, çift / kayıp yok")
    void threePods_2100Monitors_p95DelayAndNoDuplicatesNoLoss() throws Exception {
        long setupAt = System.currentTimeMillis();
        try (MultiPodTestSupport mp = MultiPodTestSupport.create(setupAt)) {
            R33ClusterRig rig = new R33ClusterRig(mp, INTERVAL_S * 1000L, true);
            rig.types.set(CheckJobTypes.PORT);
            rig.tuning = new DistributedChecks.Tuning(true, SLOTS, POLL_MS, 1_000L, DRAIN_MS, 50, STEAL_AFTER_MS, 600_000L);
            for (long id = 1; id <= MONITORS; id++) {
                PortMonitor m = R33ClusterRig.port(id, INTERVAL_S);
                m.setConfirmIntervalSeconds(null);    // teyit adımları 1 ms arayla (liderin 4 teyit iş parçacığında)
                m.setRecoveryIntervalSeconds(null);
                rig.ports.add(m);
            }
            // Fazlar aralığa yayılır: vade satırları önceden yazılır (üretimde izlemeler farklı anlarda oluşur).
            long start = setupAt + 3_000L;
            Map<Long, Long> seed = new ConcurrentHashMap<>();
            List<Object[]> rows = new ArrayList<>();
            for (long id = 1; id <= MONITORS; id++) {
                long due = start + (id * INTERVAL_S * 1000L) / MONITORS;
                seed.put(id, due);
                rows.add(new Object[]{"port:" + id, due});
            }
            mp.jdbc().batchUpdate("INSERT INTO monitor_check_schedule(monitor_key, next_due_at) VALUES (?, ?)", rows);

            ThreadLocal<Boolean> timedOut = new ThreadLocal<>();
            rig.latency = (pod, type, id) -> {
                ThreadLocalRandom r = ThreadLocalRandom.current();
                boolean t = r.nextDouble() < TIMEOUT_SHARE;
                timedOut.set(t);
                return t ? TIMEOUT_SLEEP_MS : Math.round(Math.exp(Math.log(MEDIAN_MS) + SIGMA * r.nextGaussian()));
            };
            rig.oracle = (pod, type, id, now) -> !Boolean.TRUE.equals(timedOut.get());

            Map<Long, Integer> count = new ConcurrentHashMap<>();
            Map<Long, ConcurrentLinkedQueue<Long>> dues = new ConcurrentHashMap<>();
            AtomicLong requested = new AtomicLong();
            AtomicLong inserted = new AtomicLong();
            ConcurrentLinkedQueue<Long> backlog = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<long[]> delays = new ConcurrentLinkedQueue<>();   // [vade, gecikme]
            AtomicLong missingDue = new AtomicLong();
            rig.storeWrapper = s -> new MeasuringStore(s, seed, count, dues, requested, inserted, backlog);
            rig.onRunStart = (type, id) -> {
                ConcurrentLinkedQueue<Long> q = dues.get(id);
                Long due = q == null ? null : q.poll();
                if (due == null) {
                    missingDue.incrementAndGet();
                    return;
                }
                delays.add(new long[]{due, System.currentTimeMillis() - due});
            };

            List<Node> pods = new ArrayList<>();
            for (int n = 1; n <= PODS; n++) pods.add(rig.node(n, new Opts().realThreads()));
            for (Node n : pods) {
                // DA-M2 sonrası gönderim aralığı = max(izleme aralığı, klasik tık): ölçekli klasik tık (30 sn gerçek)
                ReflectionTestUtils.setField(n.ss(), "environment",
                        new MockEnvironment().withProperty("site.monitor.port.interval-ms", String.valueOf(CLASSIC_TICK_MS)));
            }
            Node leader = pods.get(0);
            try {
                assertThat(leader.ss().isSweepLeaderNow()).isTrue();
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (leader.registry.liveOtherWorkers(CheckJobTypes.PORT) < PODS - 1 && System.nanoTime() < until) {
                    pods.forEach(p -> p.registry.tick());
                    Thread.sleep(50);
                }
                assertThat(leader.registry.liveOtherWorkers(CheckJobTypes.PORT)).isEqualTo(PODS - 1);

                AtomicBoolean stop = new AtomicBoolean();
                AtomicLong dispatchTicks = new AtomicLong();
                Thread dispatcher = new Thread(() -> {
                    while (!stop.get()) {
                        try {
                            leader.ss().dispatchCheckJobs();
                            dispatchTicks.incrementAndGet();
                        } catch (Throwable ignored) {
                            // ölçüm döngüsü düşmesin
                        }
                        try {
                            Thread.sleep(DISPATCH_EVERY_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }, "r33-capacity-dispatch");
                dispatcher.setDaemon(true);
                dispatcher.start();
                long warmupEnd = start + WARMUP_MS;
                long measureEnd = warmupEnd + MEASURE_MS;
                while (System.currentTimeMillis() < measureEnd) Thread.sleep(200);
                stop.set(true);
                dispatcher.join(5_000);
                long drainUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (rig.evals.size() < inserted.get() && System.nanoTime() < drainUntil) Thread.sleep(100);

                List<Long> measured = new ArrayList<>();
                for (long[] d : delays) if (d[0] >= warmupEnd && d[0] < measureEnd) measured.add(d[1]);
                long p95 = percentile(measured, 0.95);
                long p99 = percentile(measured, 0.99);
                long max = percentile(measured, 1.0);
                List<Long> backlogs = new ArrayList<>(backlog);
                long backlogP95 = percentile(backlogs, 0.95);
                List<Long> jobIds = rig.evals.stream().map(R33ClusterRig.Eval::jobId).toList();
                Map<Integer, Long> perPod = new java.util.TreeMap<>();
                for (R33ClusterRig.Run r : rig.runs) perPod.merge(r.pod(), 1L, Long::sum);
                String report = String.format("[R3-3 kapasite] ölçülen %d kontrol (%.0f / sn ölçekli), gecikme p95 %d ms (gerçek ≈ %d sn), "
                                + "p99 %d ms, en çok %d ms; birikim p95 %d ms; gönderilen %d, eklenen %d, koşan %d, değerlendirilen %d; "
                                + "pod başına %s; gönderim tıkı %d",
                        measured.size(), measured.size() * 1000.0 / MEASURE_MS, p95, p95 * SCALE / 1000, p99, max, backlogP95,
                        requested.get(), inserted.get(), rig.runs.size(), rig.evals.size(), perPod, dispatchTicks.get());
                System.out.println(report);

                assertThat(measured.size()).as("ölçüm penceresinde ~350 / sn ölçekli kontrol: " + report)
                        .isGreaterThan((int) (MONITORS * (MEASURE_MS / (INTERVAL_S * 1000.0)) * 0.9));
                assertThat(p95 * SCALE).as("vade → başlangıç p95 < 30 sn gerçek eşdeğeri: " + report).isLessThan(30_000L);
                assertThat(backlogP95).as("değerlendirici birikimi p95 < 1 sn: " + report).isLessThan(1_000L);
                assertThat(requested.get()).as("hiçbir dönem açık işe katılmadı (gönderilen = eklenen)").isEqualTo(inserted.get());
                assertThat(missingDue.get()).as("her koşu bir gönderime denk (çift koşu yok)").isZero();
                assertThat((long) rig.runs.size()).as("koşan = eklenen (kayıp / çift yok): " + report).isEqualTo(inserted.get());
                assertThat((long) rig.evals.size()).as("değerlendirilen = eklenen: " + report).isEqualTo(inserted.get());
                assertThat(jobIds).as("hiçbir iş iki kez değerlendirilmedi").doesNotHaveDuplicates();
                assertThat(((Number) leader.ss().distributedCheckStatus().get("lost")).longValue()).as("biçilen yok").isZero();
                assertThat(perPod.keySet()).as("yük üç pod'a yayıldı").hasSize(PODS);
                assertThat(rig.jobRows()).as("kuyruk boşaldı").isZero();
            } finally {
                for (Node n : pods) rig.stop(n);
            }
        }
    }
}
