package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.R34CertRig.Node;
import com.sitemonitor.service.R34CertRig.Opts;
import com.sitemonitor.service.certsweep.CertChunking;
import com.sitemonitor.service.certsweep.CertSweepStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-4 paket C2 — KAPASİTE (tasarım R3-4 §9.4, §1.3, §3.3).
 *
 * <p>Gerçek iş parçacıkları: pod başına gerçek {@code certCheckExecutor} (20 iş parçacığı), gerçek parça işçisi (yoklama +
 * {@code cert-chunk-N} koşucuları), gerçek koordinatör, ortak H2, duvar saati. Zaman 1:10 ölçekli: sağlıklı kontrol lognormal
 * (medyan 1,2 sn → 120 ms, σ 0,5), ağ sınıfı hata 13 sn → 1,3 sn ({@code 6 sn × 2 deneme + 1 sn}); koordinatör beklemesi 500 ms →
 * 50 ms, steal-after 10 sn → 1 sn, işçi yoklaması 250 ms → 50 ms (taban), boş yoklama 5 sn → 250 ms (taban). 3x envanter (1.200
 * alan adı), 3 pod (biri tarama lideri, yarı bütçe). Gecikmeler alan adı dizinine bağlı belirlenimci tohumlardan — model ve ölçüm
 * AYNI gecikmeleri kullanır.
 *
 * <p>Ölçülen: toplama süresi (tur başı → ilk kayıt), son parça → toplama (koordinatör beklemesi), her alan adı tam bir kez
 * kaydedildi / hiçbiri kaybolmadı, her pod iş yaptı; aynı envanterin TEK pod klasik turu (20 iş parçacığı) ile kıyas.
 *
 * <p><b>Model.</b> {@link #chunkedModel}: işçi bütçesi kuralının (tasarım §3.4 — {@code budget = 2 × çekirdek} alan adı, parça
 * BİTENE dek tutulur) kuyruk benzetimi; {@link #classicModel}: 1.200 kontrol 20 iş parçacığında. Tasarım tablosu (§1.3) iş
 * parçacığı-sürekli formülle ({@code Σt / iş parçacığı + max t}) 3x / 3 pod için ≈ 50 sn öngörür; bütçe kuralı bir parçayı en
 * yavaş kontrolü (13 sn) bitene dek tutar — parçaların ~%46'sında bir hata vardır — bu yüzden eski varsayılanlarla (2 × çekirdek)
 * ≈ 113 sn (eşdeğer) çıkıyordu (delta-R3-4-C2 "Bugs found" B-1). Düzeltme: varsayılan bütçe 6 × çekirdek, liderin başka pod'un
 * turuna ayırdığı pay en çok max(parça, çekirdek) → 3x / 3 pod ≈ 75 sn (klasik ≈ 145 sn); {@code max-inflight-domains=120} ile ≈ 62 sn.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class CertChunksCapacityTest {

    static final int N = 1_200;
    static final long SCALE = 10;
    static final double MEDIAN_MS = 1_200.0 / SCALE;
    static final double SIGMA = 0.5;
    static final double FAIL_MS = 13_000.0 / SCALE;
    static final int THREADS = 20;
    static final long STEAL_AFTER_MS = 10_000L / SCALE;

    /** Ölçekli pod ayarları; {@code maxInflight} 0 = varsayılan (2 × çekirdek = 40). */
    static CertChunking.Tuning tuning(int maxInflight) {
        return new CertChunking.Tuning(true, 20, 40, 16, maxInflight, 60_000L, STEAL_AFTER_MS, 50, 50L, 250L);
    }

    R34CertRig rig;

    @AfterEach
    void close() throws InterruptedException {
        if (rig != null) rig.close();
        long until = System.currentTimeMillis() + 10_000L;
        while (anyThread("cert-chunk-", "r34-cert-check-") && System.currentTimeMillis() < until) Thread.sleep(20);
    }

    static boolean anyThread(String... prefixes) {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!t.isAlive()) continue;
            for (String p : prefixes) if (t.getName().startsWith(p)) return true;
        }
        return false;
    }

    // ── Belirlenimci gecikmeler (model + ölçüm) ──────────────────────────────────────────────────────────────────────

    static boolean failing(int i, double share) {
        long h = (i * 0x9E3779B97F4A7C15L) >>> 33;
        return h % 1000 < (long) Math.round(share * 1000);
    }

    /** Ölçekli gecikme (ms). */
    static double latencyMs(int i, double failShare) {
        if (failing(i, failShare)) return FAIL_MS;
        Random r = new Random(1_000L + i);
        return MEDIAN_MS * Math.exp(SIGMA * r.nextGaussian());
    }

    static String issuer(int i) {
        return "CN=CA-" + (i * 7 % 13);
    }

    // ── Modeller ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Klasik tur: tüm kontroller 0 anında tek havuza (FIFO, {@code threads} iş parçacığı) — toplama süresi (ms). */
    static double classicModel(int n, double failShare, int threads) {
        PriorityQueue<Double> heap = new PriorityQueue<>();
        for (int k = 0; k < threads; k++) heap.add(0.0);
        double max = 0;
        for (int i = 0; i < n; i++) {
            double f = heap.poll() + latencyMs(i, failShare);
            heap.add(f);
            max = Math.max(max, f);
        }
        return max;
    }

    /**
     * Parçalı tur: yayın kuruluşu sırasıyla {@code chunk}'lık parçalar; pod {@code p} aynı anda en çok {@code slots[p]} parça tutar
     * (bütçe / parça boyu; bir parça EN YAVAŞ kontrolü bitince bırakılır), {@code readyAt[p]}'den önce almaz (lider steal-after),
     * parçanın kontrolleri pod'un {@code threads} iş parçacığında FIFO koşar. Sahiplenme anlıktır (biten parça işçiyi dürter).
     */
    static double chunkedModel(int n, double failShare, int[] slots, double[] readyAt, int threads, int chunk) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        order.sort(Comparator.comparing(CertChunksCapacityTest::issuer).thenComparingInt(i -> i));
        int pods = slots.length;
        List<PriorityQueue<Double>> threadFree = new ArrayList<>();
        List<PriorityQueue<Double>> slotFree = new ArrayList<>();
        for (int p = 0; p < pods; p++) {
            PriorityQueue<Double> h = new PriorityQueue<>();
            for (int k = 0; k < threads; k++) h.add(0.0);
            threadFree.add(h);
            PriorityQueue<Double> s = new PriorityQueue<>();
            for (int k = 0; k < slots[p]; k++) s.add(readyAt[p]);
            slotFree.add(s);
        }
        double max = 0;
        for (int next = 0; next < n; next += chunk) {
            int best = -1;
            double at = Double.MAX_VALUE;
            for (int p = 0; p < pods; p++) {
                Double t = slotFree.get(p).peek();
                if (t != null && t < at) {
                    at = t;
                    best = p;
                }
            }
            double t = slotFree.get(best).poll();
            double done = t;
            for (int k = next; k < Math.min(n, next + chunk); k++) {
                double s = Math.max(t, threadFree.get(best).poll());
                double f = s + latencyMs(order.get(k), failShare);
                threadFree.get(best).add(f);
                done = Math.max(done, f);
            }
            slotFree.get(best).add(done);
            max = Math.max(max, done);
        }
        return max;
    }

    /** Bütçeden pod başına parça yuvası (lider yarı bütçe): {@code [lider, takipçi, takipçi]}. */
    static int[] slots(int maxInflight) {
        // C2 B-1 düzeltmesi: varsayılan bütçe 6 × çekirdek; liderin payı varsayılanda en çok max(parça, çekirdek) (açık ayar aynen)
        int budget = maxInflight > 0 ? maxInflight : CertChunking.Tuning.DEFAULT_BUDGET_PER_CORE * THREADS;
        int follower = Math.max(1, budget / 20);
        int leaderBudget = budget * 50 / 100;
        if (maxInflight <= 0) leaderBudget = Math.min(leaderBudget, Math.max(20, THREADS));
        int leader = Math.max(1, leaderBudget / 20);
        return new int[] {leader, follower, follower};
    }

    static double chunkedModel(double failShare, int maxInflight) {
        return chunkedModel(N, failShare, slots(maxInflight), new double[] {STEAL_AFTER_MS, 0, 0}, THREADS, 20);
    }

    @Test
    @DisplayName("Model: tasarım tablosu (§1.3) — klasik 3x ≈ 1,8 dk, parçalı 3 pod ≈ 50 sn (iş parçacığı-sürekli formül); bütçe "
            + "kuyruk modeli: varsayılan bütçe (40) klasikten hızlı ama tasarım hedefini kaçırır, 120 tutturur")
    void model_designTableAndBudgetModel() {
        // §1.3 formülü: M ≈ (N_h · t_h + N_f · t_f) / iş parçacığı + max t_f (t_h 1,2 sn, t_f 13 sn)
        double nf = N * 0.03;
        double nh = N - nf;
        double classic = (nh * 1.2 + nf * 13.0) / 20 + 13.0;
        double chunked3 = (nh * 1.2 + nf * 13.0) / 50 + 13.0;   // 20 + 20 + 10 (lider yarı)
        assertThat(classic).as("klasik 3x ≈ 1,8 dk (tasarım §1.3)").isBetween(100.0, 115.0);
        assertThat(chunked3).as("parçalı 3 pod ≈ 50 sn (tasarım §1.3 / §3.3)").isBetween(45.0, 55.0);

        double classicQ = classicModel(N, 0.03, THREADS) * SCALE / 1000.0;
        double chunkedDefault = chunkedModel(0.03, 0) * SCALE / 1000.0;
        double chunked120 = chunkedModel(0.03, 120) * SCALE / 1000.0;
        double bad30Default = chunkedModel(0.30, 0) * SCALE / 1000.0;
        double bad30With120 = chunkedModel(0.30, 120) * SCALE / 1000.0;
        assertThat(chunkedDefault).as("parçalı (varsayılan bütçe) klasikten hızlı").isLessThan(classicQ);
        assertThat(chunkedDefault).as("B-1 düzeltmesi: varsayılan bütçe (6 × çekirdek, lider korunur) klasiğin yarısının altında")
                .isLessThan(classicQ * 0.6);
        assertThat(chunked120).as("max-inflight-domains=120: 3x hedefi (< 70 sn)").isLessThan(70.0);
        assertThat(bad30With120).as("kötü saat (%30) + 120: < 2,5 dk").isLessThan(150.0);
        System.out.printf(Locale.ROOT, "[r34-capacity-model] classic=%.1fs chunkedDefault=%.1fs chunked120=%.1fs "
                + "bad30Default=%.1fs bad30With120=%.1fs%n", classicQ, chunkedDefault, chunked120, bad30Default, bad30With120);
    }

    // ── Ölçüm ────────────────────────────────────────────────────────────────────────────────────────────────────────

    record Measured(double gatherEqS, double collectLagMs, Map<Integer, Integer> checksByPod, int saves) {}

    /** Bir tam tur: {@code chunked} ise 3 pod (1 lider, koordinatör pod 2), değilse tek 20.170.0 pod'u. */
    Measured measure(double failShare, boolean chunked, int maxInflight) {
        rig = new R34CertRig(MultiPodTestSupport.create(), true);
        rig.tuning = tuning(maxInflight);
        rig.inventory(N);
        for (int i = 0; i < N; i++) rig.issuers.put(R34CertRig.domain(i), issuer(i));
        rig.oracle = (pod, i, d) -> failing(i, failShare)
                ? CertSweepGoldenHarness.networkError(d, "Connection timeout after 6s")
                : R34CertRig.healthyResult(i, d);
        rig.latency = i -> Math.round(latencyMs(i, failShare));
        rig.switchOn.set(chunked);
        Node coordinator;
        if (chunked) {
            rig.node(1, new Opts().leader().realThreads());
            coordinator = rig.node(2, new Opts().realThreads());
            rig.node(3, new Opts().realThreads());
        } else {
            coordinator = rig.node(1, new Opts().legacy().realThreads());
        }
        long t0 = System.nanoTime();
        rig.runFull(coordinator);
        long firstSave = rig.firstCallNanos(coordinator, "saveResult");
        assertThat(firstSave).as("tur kaydetti").isPositive();
        double gatherEqS = (firstSave - t0) / 1e6 * SCALE / 1000.0;
        double lag = -1;
        if (chunked) {
            Map<String, Object> run = rig.onlyRun();
            assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
            long lastDone = rig.completions.stream().filter(R34CertRig.Completion::accepted)
                    .mapToLong(R34CertRig.Completion::atMs).max().orElseThrow();
            long firstSaveWall = System.currentTimeMillis() - (System.nanoTime() - firstSave) / 1_000_000L;
            lag = firstSaveWall - lastDone;
        }
        Map<String, Integer> saves = rig.saveCounts();
        assertThat(saves).as("hiçbir alan adı kaybolmadı").hasSize(N);
        assertThat(saves.values()).as("her alan adı tam bir kez kaydedildi").allMatch(k -> k == 1);
        assertThat(coordinator.saves()).isEqualTo(N);
        Measured m = new Measured(gatherEqS, lag, rig.checksByPod(), (int) coordinator.saves());
        rig.close();
        rig = null;
        return m;
    }

    @Test
    @DisplayName("3x envanter, 3 pod, %3 ağ hatası, varsayılan bütçe: çift / kayıp yok, her pod iş yapar, son parça → toplama "
            + "≤ 1 sn, toplama klasik tek pod turundan HIZLI ve kuyruk modeliyle uyumlu")
    void capacity_3x_3pods_defaultBudget() {
        Measured classic = measure(0.03, false, 0);
        Measured chunked = measure(0.03, true, 0);
        double model = chunkedModel(0.03, 0) * SCALE / 1000.0;
        System.out.printf(Locale.ROOT, "[r34-capacity] 3%% default: classic=%.1fs chunked=%.1fs (model %.1fs) collectLag=%.0fms "
                + "byPod=%s%n", classic.gatherEqS(), chunked.gatherEqS(), model, chunked.collectLagMs(), chunked.checksByPod());
        assertThat(chunked.checksByPod()).as("her pod (lider dahil) iş yaptı").containsKeys(1, 2, 3);
        assertThat(chunked.checksByPod().values()).allMatch(k -> k > 0);
        assertThat(chunked.checksByPod().values().stream().mapToInt(Integer::intValue).sum()).as("yeniden ölçüm yok")
                .isEqualTo(N);
        assertThat(chunked.collectLagMs()).as("son parça → koordinatör topladı (gerçek ms)").isBetween(0.0, 1_000.0);
        assertThat(chunked.gatherEqS()).as("parçalı toplama klasikten hızlı").isLessThan(classic.gatherEqS());
        assertThat(chunked.gatherEqS()).as("ölçüm kuyruk modeliyle uyumlu").isBetween(model * 0.7, model * 1.4);
    }

    @Test
    @DisplayName("3x envanter, 3 pod, max-inflight-domains=120: tasarım hedefi (< 70 sn eşdeğer) ve kötü saat (%30, < 2,5 dk) — "
            + "çift / kayıp yok")
    void capacity_3x_3pods_budget120_meetsDesignTarget() {
        Measured normal = measure(0.03, true, 120);
        Measured bad = measure(0.30, true, 120);
        System.out.printf(Locale.ROOT, "[r34-capacity] budget120: 3%%=%.1fs (model %.1fs) 30%%=%.1fs (model %.1fs)%n",
                normal.gatherEqS(), chunkedModel(0.03, 120) * SCALE / 1000.0, bad.gatherEqS(),
                chunkedModel(0.30, 120) * SCALE / 1000.0);
        assertThat(normal.checksByPod()).containsKeys(1, 2, 3);
        assertThat(normal.gatherEqS()).as("3x normal saat < 70 sn eşdeğer").isLessThan(70.0 * 1.15);
        assertThat(bad.gatherEqS()).as("3x kötü saat (%30) < 2,5 dk eşdeğer").isLessThan(150.0 * 1.15);
    }

    @Test
    @DisplayName("Varsayılanlarla (C2 B-1 düzeltmesi): 3x / 3 pod toplama < 85 sn eşdeğer (lider korunur), kötü saat (%30) < 2,5 dk")
    void capacity_3x_3pods_designTarget_withDefaults() {
        Measured normal = measure(0.03, true, 0);
        Measured bad = measure(0.30, true, 0);
        // Tasarımın ≈ 50 sn tahmini liderin tam katılımına dayanıyordu; kullanıcı kararıyla lider başkasının turuna yalnız bir parça
        // (çekirdek kadar) ayırır → 3x / 3 pod ≈ 75 sn (klasik ≈ 145 sn). Sınır: 85 sn; kötü saat 2,5 dk.
        System.out.printf(Locale.ROOT, "[r34-capacity] defaults: 3%%=%.1fs 30%%=%.1fs%n", normal.gatherEqS(), bad.gatherEqS());
        assertThat(normal.gatherEqS()).isLessThan(85.0);
        assertThat(bad.gatherEqS()).isLessThan(150.0 * 1.15);
    }
}
