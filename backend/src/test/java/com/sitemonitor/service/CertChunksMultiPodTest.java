package com.sitemonitor.service;

import com.sitemonitor.service.R34CertRig.Claim;
import com.sitemonitor.service.R34CertRig.Completion;
import com.sitemonitor.service.R34CertRig.Node;
import com.sitemonitor.service.R34CertRig.Opts;
import com.sitemonitor.service.certsweep.CertChunking;
import com.sitemonitor.service.certsweep.CertSweepStatus;
import com.sitemonitor.service.certsweep.CertSweepStore;
import com.sitemonitor.service.health.ClusterHealthStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Çok pod Sürüm 3-4 paket C2 — PARÇALI SERTİFİKA TARAMASI çok pod senaryoları (tasarım R3-4 §9.3; L-2 / L-3 / L-4 / L-5).
 *
 * <p>{@link R34CertRig}: tek H2, ortak saat, pod başına gerçek {@code SchedulerService} (gerçek {@code cert-check} kilidi ve tur
 * gövdesi), gerçek depo / işçi / koordinatör / ölçücü, gerçek toplu kesinti servisi ve tarama durumu deposu; denetleyici kâhini
 * pod'dan bağımsız. Her senaryonun kararı (kayıt haritaları sırasıyla, önbellek, {@code onRun}, alarm listeleri,
 * {@code recordScanRun}) aynı envanterin TEK pod klasik turuyla (20.170.0 benzeri pod, taze veritabanı) karşılaştırılır.
 *
 * <p>Her senaryonun koruduğu mekanizma tek satırlık bir üretim kodu bozulmasıyla sınandı (delta-R3-4-C2 "Mutations").
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class CertChunksMultiPodTest {

    R34CertRig rig;

    @AfterEach
    void close() {
        if (rig != null) rig.close();
    }

    static final BiConsumer<R34CertRig, Node> FULL = R34CertRig::runFull;

    static void assertSameDecisions(List<String> classic, List<String> chunked) {
        assertThat(classic).as("klasik döküm boş değil (test kör değil)").isNotEmpty();
        assertThat(R34CertRig.firstDifference(classic, chunked)).as("karar dökümü (kayıt, önbellek, onRun, alarm, scan_runs) "
                + "tek pod klasik turuyla AYNI").isNull();
    }

    static void assertEachSavedOnce(R34CertRig rig, int n) {
        Map<String, Integer> saves = rig.saveCounts();
        assertThat(saves).as("her alan adı kaydedildi").hasSize(n);
        assertThat(saves.values()).as("her alan adı TAM BİR kez kaydedildi (L-4)").allMatch(k -> k == 1);
    }

    static void assertOnlyCoordinatorDecides(R34CertRig rig, Node coordinator) {
        for (Node n : rig.nodes) {
            if (n == coordinator) continue;
            assertThat(n.calls).as("%s hiçbir karar vermedi (kayıt / önbellek / kesinti / alarm / scan_runs — L-2)", n).isEmpty();
        }
    }

    static Set<String> workerPods(Map<String, Object> run) {
        Set<String> out = new TreeSet<>();
        for (CertSweepStatus.WorkerTally t : CertSweepStatus.decodeWorkers((String) run.get("workers_json"))) out.add(t.worker());
        return out;
    }

    static int intOf(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // s1 — olağan parçalı tur
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("s1: 1.200 alan adı × 3 pod — her alan adı tam bir kez ölçülür ve kaydedilir, karar yalnız koordinatörde bir kez, "
            + "iş ≥ 2 pod'a dağılır; karar dökümü tek pod klasik turuyla AYNI")
    void s1_normalRun_1200Domains3Pods_equalsClassic() {
        rig = new R34CertRig().inventory(1_200);
        Node a = rig.node(1, new Opts().leader());
        Node b = rig.node(2);
        Node c = rig.node(3);
        rig.runFull(b);

        Map<String, Object> run = rig.onlyRun();
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        assertThat(run.get("outcome")).isEqualTo(CertSweepStore.OUTCOME_DECIDED);
        assertThat(run.get("coordinator")).isEqualTo(b.owner());
        assertThat(intOf(run.get("domains_total"))).isEqualTo(1_200);
        assertThat(intOf(run.get("chunks_total"))).isEqualTo(60);
        assertThat(intOf(run.get("takeovers"))).isZero();
        assertThat(rig.chunkRows()).as("bitince parçalar silindi").isZero();

        assertEachSavedOnce(rig, 1_200);
        assertThat(b.saves()).isEqualTo(1_200);
        assertOnlyCoordinatorDecides(rig, b);
        assertThat(b.callsOf("evictAllCaches")).isEqualTo(1);
        assertThat(b.callsOf("onRun")).isEqualTo(1);
        assertThat(b.callsOf("processResults")).isEqualTo(1);
        assertThat(b.callsOf("recordScanRun")).isEqualTo(1);
        assertThat(rig.checkArgs()).as("her alan adı TAM BİR kez, klasik bağımsız değişkenlerle ölçüldü (L-3)")
                .isEqualTo(rig.expectedCheckArgs());

        Map<Integer, Integer> byPod = rig.checksByPod();
        assertThat(byPod.keySet()).as("iş ≥ 2 pod'a dağıldı: %s", byPod).hasSizeGreaterThanOrEqualTo(2);
        assertThat(byPod.get(2)).isPositive();
        assertThat(byPod.get(3)).isPositive();
        assertThat(workerPods(run)).as("workers_json ölçen pod'ları adlar").contains(b.owner(), c.owner())
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(rig.claims).as("tarama lideri başka pod varken 10 sn dolmadan parça almaz")
                .noneMatch(k -> k.pod() == 1 && k.atMs() < intOfLong(run.get("created_ms")) + 10_000L);

        Map<String, Object> scan = rig.scanRun(ClusterHealthStore.KIND_FULL);
        assertThat(scan).isNotNull();
        assertThat(intOf(scan.get("total"))).isEqualTo(1_200);
        assertThat(String.valueOf(scan.get("instance_id"))).isEqualTo(b.owner());

        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(1_200), FULL), rig.decisions(b));
        assertThat(a.calls).isEmpty();
    }

    /** "Kural gereği klasik" tur sayacı (tek pod, {@code min-domains} altı …) — FX-COORD (OB-L6) gerçek düşüşten ayrı sayar. */
    static void assertClassicByRule(CertSweepStatus.Coordinator st, long expected) {
        assertThat(st.classicByRule()).as("kural gereği klasik (FX-COORD OB-L6)").isEqualTo(expected);
        assertThat(st.classicFallback()).as("gerçek klasiğe düşüş yok (FX-COORD OB-L6)").isZero();
    }

    static long intOfLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // s2 — işçi ölümü
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("s2a: işçi parçanın ortasında ölür (ölçtü, yazamadı) → kira dolunca başka pod yeniden sahiplenir (deneme 2); çift "
            + "kayıt yok, devralma yok, karar klasikle aynı")
    void s2a_workerDiesMidChunk_reclaimedAfterLease_noDuplicateSave() {
        rig = new R34CertRig().inventory(100);
        Node a = rig.node(1);
        Node b = rig.node(2);
        Node c = rig.node(3);
        c.onMeasure = c::kill;   // pod 3'ün ilk ölçümü onu öldürür: parça ölçülür ama tamamlama yazılmaz
        rig.runFull(a);

        Map<String, Object> run = rig.onlyRun();
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        assertThat(intOf(run.get("takeovers"))).as("koordinatör devralmadı").isZero();
        assertThat(intOf(run.get("reclaims"))).as("bir parça yeniden sahiplenildi").isEqualTo(1);

        List<Claim> dead = rig.claims.stream().filter(k -> k.pod() == 3).toList();
        assertThat(dead).hasSize(1);
        Claim lost = dead.get(0);
        List<Claim> again = rig.claims.stream().filter(k -> k.chunkId() == lost.chunkId() && k.pod() != 3).toList();
        assertThat(again).as("ölen pod'un parçası canlı bir pod'a geçti").hasSize(1);
        assertThat(again.get(0).attempts()).isEqualTo(2);
        assertThat(again.get(0).atMs()).as("kira (60 sn) dolmadan geçmedi").isGreaterThanOrEqualTo(lost.atMs() + 60_000L);

        assertEachSavedOnce(rig, 100);
        assertOnlyCoordinatorDecides(rig, a);
        long measuredTwice = rig.checks.stream().map(R34CertRig.Check::domain).distinct().count();
        assertThat(rig.checks).as("ölen pod'un ölçümü + yeniden ölçüm (yan etkisiz)").hasSize(120);
        assertThat(measuredTwice).isEqualTo(100);
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(100), FULL), rig.decisions(a));
        assertThat(b.calls).isEmpty();
    }

    @Test
    @DisplayName("s2b: aynı parçayı İKİ işçi de kaybeder (deneme 2) → koordinatör iptal edip kendisi ölçer; ~2 kira içinde biter, "
            + "çift kayıt yok, karar klasikle aynı")
    void s2b_secondLoss_coordinatorTakesOver() {
        rig = new R34CertRig().inventory(40);   // 2 parça (en az 40 alan adı)
        Node a = rig.node(1, new Opts().workerOff());   // koordinatörün kendi işçisi kapalı
        Node b = rig.node(2);
        Node c = rig.node(3);
        c.paused = true;
        b.onMeasure = b::kill;
        rig.stepHooks.add(now -> {
            if (!c.paused || !b.dead) return;
            long firstLoss = rig.claims.stream().filter(k -> k.pod() == 2).mapToLong(Claim::atMs).min().orElse(Long.MAX_VALUE);
            if (now >= firstLoss + 61_000L) {
                c.onMeasure = c::kill;
                c.paused = false;
            }
        });
        long t0 = rig.clock.getAsLong();
        rig.runFull(a);
        long took = rig.clock.getAsLong() - t0;

        Map<String, Object> run = rig.onlyRun();
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        assertThat(intOf(run.get("takeovers"))).as("iki parça da devralındı").isEqualTo(2);
        assertThat(intOf(run.get("reclaims"))).isEqualTo(2);
        assertThat(rig.claims.stream().filter(k -> k.pod() == 2)).hasSize(2).allMatch(k -> k.attempts() == 1);
        assertThat(rig.claims.stream().filter(k -> k.pod() == 3)).hasSize(2).allMatch(k -> k.attempts() == 2);
        assertThat(rig.checksByPod().get(1)).as("koordinatör 40 alan adını kendisi ölçtü").isEqualTo(40);
        assertThat(took).as("iki kira + adımlar — ilerlemesiz tavan (900 sn) değil").isLessThan(200_000L);

        assertEachSavedOnce(rig, 40);
        assertOnlyCoordinatorDecides(rig, a);
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(40), FULL), rig.decisions(a));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // s3 — koordinatör ölümü
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("s3: koordinatör toplamanın ortasında ölür → HİÇBİR şey kaydedilmez; nabzı 60 sn eskiyince işçiler parçalarını "
            + "almaz; kilit dolunca sonraki koordinatör koşuyu ORPHANED kapatır, parçalarını siler ve alan adlarını denetler")
    void s3_coordinatorDies_nothingSaved_nextRunSettlesOrphan() throws Exception {
        rig = new R34CertRig().inventory(300);   // 15 parça
        Node a = rig.node(1);
        Node b = rig.node(2);
        Node c = rig.node(3);
        AtomicInteger sleeps = new AtomicInteger();
        a.freezeWhen = () -> sleeps.incrementAndGet() >= 2;   // 1. adımda parçalar ölçüldü, 2. beklemede ölür
        Thread t = rig.runAsync("r34-dead-coordinator", () -> rig.runFull(a));
        try {
            assertThat(a.frozenSignal.await(60, TimeUnit.SECONDS)).as("koordinatör dondu").isTrue();
            Map<String, Object> dead = rig.onlyRun();
            String deadRun = (String) dead.get("run_id");
            long created = intOfLong(dead.get("created_ms"));
            assertThat(dead.get("state")).isEqualTo(CertSweepStore.RUN_RUNNING);

            // Ölümden sonra işçiler bekletilir; nabız 60 sn'yi geçince bekleyen parçalara artık dokunulmaz
            b.paused = true;
            c.paused = true;
            rig.steps(61_000L);
            int claimsBefore = rig.claims.size();
            long pending = rig.chunks(deadRun).stream().filter(m -> "PENDING".equals(m.get("state"))).count();
            assertThat(pending).as("ölü koordinatörün bekleyen parçaları var").isPositive();
            b.paused = false;
            c.paused = false;
            rig.steps(15_000L);
            assertThat(rig.claims.size()).as("nabzı eski koşunun parçası sahiplenilmez").isEqualTo(claimsBefore);
            assertThat(rig.chunks(deadRun).stream().filter(m -> "PENDING".equals(m.get("state"))).count()).isEqualTo(pending);

            // cert-check kilidi (3 dk, yenilenmiyor) dolar; sonraki tam tur pod 2'de
            long until = created + 3 * 60_000L + 1_000L;
            if (rig.clock.getAsLong() < until) rig.steps(until - rig.clock.getAsLong());
            rig.runFull(b);

            assertThat(a.calls).as("ölen koordinatör hiçbir şey kaydetmedi / karar vermedi").isEmpty();
            Map<String, Object> orphan = rig.runRow(deadRun);
            assertThat(orphan.get("state")).isEqualTo(CertSweepStore.RUN_ABANDONED);
            assertThat(orphan.get("outcome")).isEqualTo(CertSweepStore.OUTCOME_ORPHANED);
            List<Map<String, Object>> all = rig.runRows();
            assertThat(all).hasSize(2);
            Map<String, Object> next = all.get(1);
            assertThat(next.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
            assertThat(next.get("coordinator")).isEqualTo(b.owner());
            assertThat(rig.chunkRows()).as("öksüzün ve yeni koşunun parçaları silindi").isZero();
            assertEachSavedOnce(rig, 300);
            assertOnlyCoordinatorDecides(rig, b);
            assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(300), FULL), rig.decisions(b));
        } finally {
            a.ss().stopStartupCheck();   // çözülen ölü koordinatör kapanışı görür ve kayıtsız çıkar
            a.thaw.countDown();
            t.join(30_000L);
        }
        assertThat(t.isAlive()).isFalse();
        assertThat(a.calls).as("çözüldükten sonra da kaydetmedi").isEmpty();
        assertThat(rig.runRows().get(0).get("outcome")).isEqualTo(CertSweepStore.OUTCOME_ORPHANED);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // s4 — kilit kaybı
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("s4: cert-check kilidi turun ortasında zorla bırakılır → koordinatör sonraki adımda bırakır (ABANDONED / LOCK_LOST), "
            + "hiçbir kayıt / karar yok; yavaş işçinin geç tamamlaması reddedilir")
    void s4_lockForceReleasedMidRun_nothingSaved_lateCompletionRejected() {
        rig = new R34CertRig().inventory(100);
        Node a = rig.node(1);
        Node b = rig.node(2);
        Node c = rig.node(3);
        c.holdRuns = true;   // pod 3'ün parçası yavaş: ölçümü sürüyor
        AtomicLong releasedAt = new AtomicLong(-1);
        rig.stepHooks.add(now -> {
            if (releasedAt.get() < 0 && rig.steps.get() == 2) {
                rig.mp.jdbc().update("DELETE FROM scheduler_lock WHERE name = 'cert-check'");   // yönetici zorla bıraktı
                b.pod.locks().renewAll();   // koordinatörün yenileyicisi 0 satır → tutuş KAYBEDİLDİ
                releasedAt.set(now);
                rig.maxSteps = rig.steps.get() + 10;   // koordinatör kaybı birkaç adımda görmeli
            }
        });
        rig.runFull(b);

        Map<String, Object> run = rig.onlyRun();
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_ABANDONED);
        assertThat(run.get("outcome")).isEqualTo(CertSweepStore.OUTCOME_LOCK_LOST);
        assertThat(intOfLong(run.get("finished_ms")) - releasedAt.get())
                .as("koordinatör kilit kaybını sonraki beklemede gördü (tüm parçaları beklemedi)").isLessThanOrEqualTo(R34CertRig.STEP_MS);
        assertThat(b.calls).as("kilit kaybı: kayıt, önbellek, kesinti, alarm, scan_runs YOK").isEmpty();
        assertThat(rig.scanRuns(ClusterHealthStore.KIND_FULL)).isZero();
        assertThat(rig.chunkRows()).as("bırakılan koşunun parçaları silindi").isZero();
        assertOnlyCoordinatorDecides(rig, b);

        rig.maxSteps = Long.MAX_VALUE;
        c.holdRuns = false;
        rig.step();   // pod 3'ün yavaş parçası şimdi biter
        assertThat(c.workerStatus().late()).as("geç tamamlama reddedildi").isEqualTo(1);
        assertThat(rig.completions.stream().filter(k -> k.pod() == 3)).hasSize(1).noneMatch(Completion::accepted);
        assertThat(rig.saveCounts()).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // s5 — toplu ağ kesintisi tüm tur üstünden; aykırı pod ikinci görüşü
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    static final String DOWN_ISSUER = "CN=Zz-Down CA";

    /** {@code down(i)} alan adları HER pod'dan ağ hatası (gerçek kesinti); yayın kuruluşu aynı → parçalarda toplanır. */
    static Consumer<R34CertRig> outageSetup(int n, java.util.function.IntPredicate down) {
        return r -> {
            r.inventory(n);
            for (int i = 0; i < n; i++) if (down.test(i)) r.issuers.put(R34CertRig.domain(i), DOWN_ISSUER);
            r.oracle = (pod, i, d) -> down.test(i)
                    ? CertSweepGoldenHarness.networkError(d, "Connection refused/unreachable: Connection refused")
                    : R34CertRig.healthyResult(i, d);
        };
    }

    @Test
    @DisplayName("s5a: kümenin tamamında %60 ağ hatası (bazı parçalar %100, bazıları %0) → kesinti TÜM tur üstünden bir kez "
            + "kararlaştırılır (onRun 72 / 120, şüpheli, alarm işlenmez); başka pod'daki sonraki tur ikinci posta atmaz; düzelince tek "
            + "çözüm postası — klasikle aynı")
    void s5a_wholeClusterOutage_decidedOnceOverTheWholeRun() {
        Consumer<R34CertRig> setup = outageSetup(120, i -> i % 5 < 3);
        rig = new R34CertRig();
        setup.accept(rig);
        Node a = rig.node(1);
        Node b = rig.node(2);
        Node c = rig.node(3);
        rig.runFull(b);

        assertThat(rig.onlyRun().get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        Map<String, Object> onRun = b.calls.stream().filter(m -> "onRun".equals(m.get("call"))).findFirst().orElseThrow();
        assertThat(intOfLong(onRun.get("networkErrors"))).isEqualTo(72);
        assertThat(intOf(onRun.get("total"))).isEqualTo(120);
        assertThat(onRun.get("suspected")).isEqualTo(true);
        assertThat(b.callsOf("onRun")).isEqualTo(1);
        assertThat(b.callsOf("processResults")).as("şüpheli tur: yeni alarm işlenmez").isZero();
        assertThat(b.callsOf("resolveVerifiedStaleCertAlerts")).isEqualTo(1);
        assertThat(rig.ongoingOutages()).isEqualTo(1);
        assertThat(rig.completions.stream().filter(Completion::accepted))
                .as("parça oranları farklı (%100 ve %0) — karar parça başına değil")
                .anyMatch(k -> k.networkErrors() == k.checked()).anyMatch(k -> k.networkErrors() == 0);
        assertOnlyCoordinatorDecides(rig, b);
        assertSameDecisions(R34CertRig.classicDecisions(setup, FULL), rig.decisions(b));
        verify(rig.email, times(1)).sendSystemAdminNetworkAlert(eq(R34CertRig.ADMIN), any(), anyInt(), anyInt(), anyDouble(),
                anyDouble());

        rig.runFull(c);   // kesinti sürer, sonraki tur başka pod'da
        assertThat(rig.runRows()).hasSize(2);
        assertThat(rig.ongoingOutages()).as("tek açık kesinti satırı").isEqualTo(1);
        verify(rig.email, times(1)).sendSystemAdminNetworkAlert(anyString(), any(), anyInt(), anyInt(), anyDouble(), anyDouble());

        rig.oracle = (pod, i, d) -> R34CertRig.healthyResult(i, d);
        rig.runFull(a);   // düzeldi, üçüncü pod
        assertThat(rig.ongoingOutages()).isZero();
        verify(rig.email, times(1)).sendSystemAdminNetworkResolved(anyString(), any(), any(), anyLong(), anyInt(), anyInt(),
                anyDouble());
        assertThat(a.callsOf("processResults")).isEqualTo(1);
    }

    @Test
    @DisplayName("s5b: %40 ağ hatası, bazı parçalar %100 → tur eşiğin altında (şüphesiz): alarmlar tüm listeyle bir kez işlenir, "
            + "kesinti satırı yok — klasikle aynı")
    void s5b_partialOutageBelowThreshold_noOutage() {
        Consumer<R34CertRig> setup = outageSetup(120, i -> i % 5 < 2);
        rig = new R34CertRig();
        setup.accept(rig);
        rig.node(1);
        Node b = rig.node(2);
        rig.node(3);
        rig.runFull(b);

        Map<String, Object> onRun = b.calls.stream().filter(m -> "onRun".equals(m.get("call"))).findFirst().orElseThrow();
        assertThat(intOfLong(onRun.get("networkErrors"))).isEqualTo(48);
        assertThat(onRun.get("suspected")).isEqualTo(false);
        assertThat(rig.completions.stream().filter(Completion::accepted)).anyMatch(k -> k.networkErrors() == k.checked());
        assertThat(rig.ongoingOutages()).isZero();
        assertThat(b.callsOf("processResults")).isEqualTo(1);
        assertOnlyCoordinatorDecides(rig, b);
        assertSameDecisions(R34CertRig.classicDecisions(setup, FULL), rig.decisions(b));
    }

    @Test
    @DisplayName("s5c: tek pod'un çıkışı bozuk (parçalarının hepsi ağ hatası, diğerleri temiz) → o pod'un hataları BAŞKA pod'dan "
            + "yeniden ölçülür (kaçınılan işçi), sahte kesinti / sahte alarm yok — sağlıklı klasik turla aynı")
    void s5c_badEgressPod_secondOpinion_noFalseOutage() {
        Consumer<R34CertRig> setup = r -> {
            r.inventory(120);
            r.oracle = (pod, i, d) -> R34CertRig.healthyResult(i, d);
        };
        rig = new R34CertRig();
        setup.accept(rig);
        Node a = rig.node(1);
        rig.node(2);
        Node c = rig.node(3);
        rig.brokenEgress.add(3);
        rig.runFull(a);

        Map<String, Object> run = rig.onlyRun();
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        int pod3Domains = rig.claims.stream().filter(k -> k.pod() == 3).mapToInt(Claim::domains).sum();
        assertThat(pod3Domains).as("bozuk pod parça aldı").isPositive();
        assertThat(intOf(run.get("second_opinions"))).as("bozuk pod'un ağ hataları yeniden ölçüldü").isEqualTo(pod3Domains);
        assertThat(rig.claims.stream().filter(k -> k.chunkNo() >= intOf(run.get("chunks_total"))))
                .as("ikinci görüş parçaları bozuk pod'a gitmedi").isNotEmpty().noneMatch(k -> k.pod() == 3);
        Map<String, Object> onRun = a.calls.stream().filter(m -> "onRun".equals(m.get("call"))).findFirst().orElseThrow();
        assertThat(intOfLong(onRun.get("networkErrors"))).as("sahte ağ hatası kalmadı").isZero();
        assertThat(rig.ongoingOutages()).isZero();
        assertThat(c.calls).isEmpty();
        assertEachSavedOnce(rig, 120);
        assertSameDecisions(R34CertRig.classicDecisions(setup, FULL), rig.decisions(a));
    }

    @Test
    @DisplayName("s5d: GERÇEK kısmi kesinti tek pod'un parçalarına düşer (o pod aykırı görünür) → ikinci görüş başka pod'dan ölçer, "
            + "hatalar KALIR (gerçek kesinti gizlenmez) — klasikle aynı")
    void s5d_genuinePartialOutageOnOneWorker_secondOpinionKeepsFailures() {
        Consumer<R34CertRig> setup = outageSetup(120, i -> i >= 80);   // son iki parça (yayın kuruluşu en sonda)
        rig = new R34CertRig();
        setup.accept(rig);
        Node a = rig.node(1);
        rig.node(2);
        rig.node(3);
        rig.runFull(a);

        Map<String, Object> run = rig.onlyRun();
        assertThat(intOf(run.get("second_opinions"))).as("aykırı pod'un ağ hataları yeniden ölçüldü").isEqualTo(40);
        Map<String, Object> onRun = a.calls.stream().filter(m -> "onRun".equals(m.get("call"))).findFirst().orElseThrow();
        assertThat(intOfLong(onRun.get("networkErrors"))).as("gerçek hatalar kaldı").isEqualTo(40);
        assertThat(onRun.get("suspected")).isEqualTo(false);
        assertThat(a.callsOf("processResults")).isEqualTo(1);
        assertEachSavedOnce(rig, 120);
        assertOnlyCoordinatorDecides(rig, a);
        assertSameDecisions(R34CertRig.classicDecisions(setup, FULL), rig.decisions(a));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // s6 — bayat süpürme ↔ saatlik tur
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("s6a: saatlik parçalı tur cert-check'i tutarken başka pod'un bayat süpürmesi kilidi alamaz → atlanır; kümede "
            + "tek koşu, tek kayıt")
    void s6a_staleSweepWhileHourlyRunHoldsTheLock_skipped() {
        rig = new R34CertRig().inventory(100);
        Node a = rig.node(1);
        Node b = rig.node(2);
        rig.node(3);
        AtomicBoolean tried = new AtomicBoolean();
        rig.stepHooks.add(now -> {
            if (tried.compareAndSet(false, true)) rig.runStale(b);
        });
        rig.runHourly(a);

        assertThat(tried).isTrue();
        Map<String, Object> run = rig.onlyRun();
        assertThat(run.get("kind")).isEqualTo(ClusterHealthStore.KIND_HOURLY);
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        assertThat(b.calls).as("bayat süpürme koşmadı").isEmpty();
        assertThat((java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(b.ss(), "running")).isFalse();
        assertThat(rig.scanRuns(ClusterHealthStore.KIND_STALE)).isZero();
        assertThat(rig.scanRuns(ClusterHealthStore.KIND_HOURLY)).isEqualTo(1);
        assertEachSavedOnce(rig, 100);
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(100), R34CertRig::runHourly), rig.decisions(a));
    }

    @Test
    @DisplayName("s6b: anahtar açık ve başka pod'lar canlıyken min-domains'in (40) altındaki bayat tur KLASİK koşar — koşu satırı "
            + "yok, karar klasikle aynı")
    void s6b_smallStaleRunBelowMinDomains_isClassic() {
        rig = new R34CertRig().inventory(10);
        rig.node(1);
        Node b = rig.node(2);
        rig.node(3);
        rig.runStale(b);

        assertThat(rig.runRows()).isEmpty();
        assertThat(rig.claims).isEmpty();
        assertThat(rig.checksByPod()).containsOnlyKeys(2);
        assertClassicByRule(b.coordinator.status(), 1);
        assertEachSavedOnce(rig, 10);
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(10), R34CertRig::runStale), rig.decisions(b));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // s7 — anahtar turun ortasında kapatılır
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("s7: anahtar turun ortasında KAPATILIR → açık koşu devralmasız biter (işçiler parça almayı sürdürür), sonra "
            + "işçiler sorgusuz bekler (FX-WORKER sonrası: 2 × kira penceresi dolunca); sonraki tur klasik")
    void s7_switchTurnedOffMidRun_runCompletes_nextRunClassic() {
        rig = new R34CertRig().inventory(200);   // 10 parça
        Node a = rig.node(1);
        Node b = rig.node(2);
        rig.node(3);
        rig.stepHooks.add(now -> {
            if (rig.steps.get() == 1) rig.switchOn.set(false);
        });
        long t0 = rig.clock.getAsLong();
        rig.runFull(b);
        long took = rig.clock.getAsLong() - t0;

        Map<String, Object> run = rig.onlyRun();
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        assertThat(intOf(run.get("takeovers"))).isZero();
        assertThat(took).as("kapatma koşuyu durdurmadı (devralma / ilerlemesiz tavan yok)").isLessThan(30_000L);
        assertEachSavedOnce(rig, 200);
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(200), FULL), rig.decisions(b));

        // son boş sahiplenme; FX-WORKER (SW-L2) sonrası işçi anahtar kapandıktan sonra 2 × kira boyunca boşta aralığıyla
        // sahiplenmeyi sürdürür — pencere dolunca (öncesinde de sonrasında da) sorgu yok
        rig.steps(2 * rig.tuning.leaseMs() + 10_000L);
        int claimsBefore = rig.nodes.stream().filter(n -> n.store != null).mapToInt(n -> n.store.claimCalls.get()).sum();
        rig.steps(60_000L);
        assertThat(rig.nodes.stream().filter(n -> n.store != null).mapToInt(n -> n.store.claimCalls.get()).sum())
                .as("anahtar kapalı, elde parça yok: işçiler sahiplenme sorgusu atmaz").isEqualTo(claimsBefore);

        rig.runFull(a);
        assertThat(rig.runRows()).as("sonraki tur klasik — yeni koşu satırı yok").hasSize(1);
        assertThat(a.saves()).isEqualTo(200);
        assertThat(a.coordinator.status().chunkedRuns()).isZero();
    }

    @Test
    @DisplayName("s7b: anahtar turun ortasında kapatılır, işçiler boş sahiplenmeyle boşta kalmışken bir pod SIGTERM ile parçalarını "
            + "bırakır → boştaki işçiler bırakılanları bir boşta yoklamada alır (devralma / 900 sn ilerlemesiz tavan yok); pencere "
            + "(2 × kira) dolunca sorgusuz")
    void s7b_switchOffMidRun_releasedChunksPickedUpWithinGrace() {
        rig = new R34CertRig().inventory(200);   // 10 parça
        rig.node(1);
        Node b = rig.node(2);
        Node c = rig.node(3);
        c.holdRuns = true;   // pod 3'ün iki parçası ölçümde asılı kalır
        AtomicLong releasedAt = new AtomicLong(-1);
        List<Long> released = new ArrayList<>();
        rig.stepHooks.add(now -> {
            if (rig.steps.get() == 1) rig.switchOn.set(false);
            // 2. adımda a ile b kalan dört parçayı alıp bitirir, 3. adımda boş sahiplenir (boşta); 8. adımda (4 sn) pod 3 kapanır
            if (rig.steps.get() == 8 && releasedAt.get() < 0) {
                released.addAll(rig.claims.stream().filter(k -> k.pod() == 3).map(Claim::chunkId).toList());
                c.worker.onContextClosed(null);
                c.chunking.onContextClosed(null);
                awaitState(released, "PENDING");
                c.kill();
                releasedAt.set(now);
            }
        });
        rig.runFull(b);

        assertThat(releasedAt.get()).as("pod 3 turun ortasında kapandı").isPositive();
        assertThat(released).hasSize(2);
        Map<String, Object> run = rig.onlyRun();
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        assertThat(intOf(run.get("takeovers"))).as("bırakılanları işçiler aldı — koordinatör devralmadı").isZero();
        assertThat(intOfLong(run.get("finished_ms")) - releasedAt.get())
                .as("anahtar kapalıyken boştaki işçi bir boşta yoklama içinde aldı (900 sn tavanı beklenmedi)")
                .isLessThanOrEqualTo(rig.tuning.idlePollMs() + 4 * R34CertRig.STEP_MS);
        assertThat(rig.claims.stream().filter(k -> released.contains(k.chunkId()) && k.pod() != 3).map(Claim::chunkId).toList())
                .as("bırakılan her parça başka pod'da bir kez sahiplenildi").containsExactlyInAnyOrderElementsOf(released);
        assertEachSavedOnce(rig, 200);
        assertOnlyCoordinatorDecides(rig, b);
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(200), FULL), rig.decisions(b));

        rig.steps(2 * rig.tuning.leaseMs() + 10_000L);   // pencere: son ON görülüşü / son dolu sahiplenme + 2 × kira
        int before = rig.nodes.stream().filter(n -> n.store != null && !n.dead).mapToInt(n -> n.store.claimCalls.get()).sum();
        rig.steps(60_000L);
        assertThat(rig.nodes.stream().filter(n -> n.store != null && !n.dead).mapToInt(n -> n.store.claimCalls.get()).sum())
                .as("pencere doldu: anahtar kapalı, elde parça yok — sahiplenme sorgusu yok").isEqualTo(before);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // s8 — karışık sürümler (20.170.0 benzeri pod: 3-4 parçası yok)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("s8a: 20.170.0 koordinatör (3-4 parçası yok) + anahtarı açık 3-4 işçileri → klasik tur, koşu satırı yok, işçiler "
            + "boş tabloyu yoklar")
    void s8a_legacyCoordinator_classicRun_newWorkersIdle() {
        rig = new R34CertRig().inventory(100);
        Node a = rig.node(1, new Opts().legacy());
        rig.node(2);
        rig.node(3);
        rig.runFull(a);
        rig.steps(10_000L);

        assertThat(rig.runRows()).isEmpty();
        assertThat(rig.claims).isEmpty();
        assertThat(rig.checksByPod()).containsOnlyKeys(1);
        assertEachSavedOnce(rig, 100);
        assertOnlyCoordinatorDecides(rig, a);
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(100), FULL), rig.decisions(a));
    }

    @Test
    @DisplayName("s8b: 3-4 koordinatör + yalnız 20.170.0 diğerleri → parçalı, her parçayı kendi işçisi ölçer; klasikle aynı")
    void s8b_newCoordinatorOnlyLegacyOthers_chunkedAlone() {
        rig = new R34CertRig().inventory(100);
        Node a = rig.node(1);
        rig.node(2, new Opts().legacy());
        rig.node(3, new Opts().legacy());
        rig.runFull(a);

        Map<String, Object> run = rig.onlyRun();
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        assertThat(workerPods(run)).containsExactly(a.owner());
        assertThat(rig.checksByPod()).containsOnlyKeys(1);
        assertEachSavedOnce(rig, 100);
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(100), FULL), rig.decisions(a));
    }

    @Test
    @DisplayName("s8c: 3-4 koordinatör TARAMA LİDERİ + yalnız 20.170.0 diğerleri → kendi turunun parçalarını HEMEN ve tam bütçeyle "
            + "alır (steal-after / yarı bütçe yalnız BAŞKA pod'un turunda) — yavaşlamaz, doğru")
    void s8c_newCoordinatorIsLeader_onlyLegacyOthers_ownRunAtOnceFullBudget() {
        rig = new R34CertRig().inventory(100);
        Node a = rig.node(1, new Opts().leader());
        rig.node(2, new Opts().legacy());
        rig.node(3, new Opts().legacy());
        long t0 = rig.clock.getAsLong();
        rig.runFull(a);
        long took = rig.clock.getAsLong() - t0;

        Map<String, Object> run = rig.onlyRun();
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        long created = intOfLong(run.get("created_ms"));
        assertThat(rig.claims).isNotEmpty().allMatch(k -> k.pod() == 1);
        assertThat(rig.claims.stream().mapToLong(Claim::atMs).min().orElseThrow())
                .as("kendi turu: steal-after (10 sn) beklenmez").isLessThan(created + rig.tuning.stealAfterMs());
        Map<Long, Integer> perStep = new TreeMap<>();
        for (Claim k : rig.claims) perStep.merge(k.atMs(), 1, Integer::sum);
        assertThat(perStep.values()).as("kendi turu: tam bütçe — adım başına iki parça").contains(2);
        assertThat(intOf(run.get("takeovers"))).isZero();
        assertThat(took).as("lider kendi turunda steal-after kadar yavaşlamaz").isLessThan(rig.tuning.stealAfterMs());
        assertEachSavedOnce(rig, 100);
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(100), FULL), rig.decisions(a));
    }

    @Test
    @DisplayName("s8d: 3-4 koordinatörün işçisi kapalı + yalnız 20.170.0 diğerleri → 60 sn içinde hiçbir parça sahiplenilmez, "
            + "koordinatör hepsini kendisi ölçer (900 sn beklemez); klasikle aynı")
    void s8d_newCoordinatorWorkerOff_onlyLegacyOthers_noWorkerNet() {
        rig = new R34CertRig().inventory(100);
        Node a = rig.node(1, new Opts().workerOff());
        rig.node(2, new Opts().legacy());
        rig.node(3, new Opts().legacy());
        long t0 = rig.clock.getAsLong();
        rig.runFull(a);
        long took = rig.clock.getAsLong() - t0;

        Map<String, Object> run = rig.onlyRun();
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        assertThat(intOf(run.get("takeovers"))).isEqualTo(intOf(run.get("chunks_total")));
        assertThat(took).isBetween(CertChunking.COORDINATOR_ALIVE_MS, CertChunking.COORDINATOR_ALIVE_MS + 10_000L);
        assertThat(rig.claims).isEmpty();
        assertEachSavedOnce(rig, 100);
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(100), FULL), rig.decisions(a));
    }

    @Test
    @DisplayName("s8e: geri dönüş — 3-4 koordinatör ölür, 20.170.0 pod klasik turla alan adlarını kurtarır (öksüz satır RUNNING "
            + "kalır, okunmaz), sonraki 3-4 koordinatör öksüzü ORPHANED kapatır; hiçbir alan adı bir turda iki kez kaydedilmez")
    void s8e_rollback_newCoordinatorDies_legacyRecovers_nextNewCoordinatorSettles() throws Exception {
        rig = new R34CertRig().inventory(100);
        Node a = rig.node(1);
        Node b = rig.node(2, new Opts().legacy());
        Node c = rig.node(3);
        AtomicInteger sleeps = new AtomicInteger();
        a.freezeWhen = () -> sleeps.incrementAndGet() >= 1;
        Thread t = rig.runAsync("r34-dead-coordinator-e", () -> rig.runFull(a));
        try {
            assertThat(a.frozenSignal.await(60, TimeUnit.SECONDS)).isTrue();
            String deadRun = (String) rig.onlyRun().get("run_id");
            long created = intOfLong(rig.onlyRun().get("created_ms"));
            rig.steps(created + 3 * 60_000L + 1_000L - rig.clock.getAsLong());

            rig.runFull(b);   // 20.170.0: klasik
            assertThat(b.saves()).isEqualTo(100);
            assertThat(new TreeSet<>(b.savedDomains())).hasSize(100);
            assertThat(rig.runRow(deadRun).get("state")).as("eski kod tabloları okumaz").isEqualTo(CertSweepStore.RUN_RUNNING);

            rig.runFull(c);   // 3-4: öksüzü kapatır
            assertThat(rig.runRow(deadRun).get("outcome")).isEqualTo(CertSweepStore.OUTCOME_ORPHANED);
            assertThat(rig.runRows()).hasSize(2);
            assertThat(rig.runRows().get(1).get("state")).isEqualTo(CertSweepStore.RUN_DONE);
            assertThat(c.saves()).isEqualTo(100);
            assertThat(new TreeSet<>(c.savedDomains())).as("yeni koşuda her alan adı bir kez").hasSize(100);
            assertThat(a.calls).isEmpty();
            assertThat(rig.chunkRows()).isZero();
        } finally {
            a.ss().stopStartupCheck();
            a.thaw.countDown();
            t.join(30_000L);
        }
        assertThat(t.isAlive()).isFalse();
        assertThat(a.calls).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // s9 — kapanış
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("s9a: işçiye SIGTERM (ContextClosedEvent) → tuttuğu parçalar beklemeden bırakılır (deneme − 1), başka pod HEMEN "
            + "alır (kira beklenmez); çift kayıt yok")
    void s9a_workerSigterm_releasesHeldChunks_reclaimedAtOnce() {
        rig = new R34CertRig().inventory(100);
        Node a = rig.node(1);
        rig.node(2);
        Node c = rig.node(3);
        c.holdRuns = true;
        AtomicBoolean closed = new AtomicBoolean();
        rig.stepHooks.add(now -> {
            if (!closed.compareAndSet(false, true)) return;
            List<Long> held = rig.claims.stream().filter(k -> k.pod() == 3).map(Claim::chunkId).toList();
            c.worker.onContextClosed(null);
            c.chunking.onContextClosed(null);
            awaitState(held, "PENDING");
            c.kill();
        });
        long t0 = rig.clock.getAsLong();
        rig.runFull(a);
        long took = rig.clock.getAsLong() - t0;

        Map<String, Object> run = rig.onlyRun();
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        assertThat(intOf(run.get("reclaims"))).as("bırakılan parça deneme − 1 ile geri döndü").isZero();
        assertThat(intOf(run.get("takeovers"))).isZero();
        assertThat(took).as("kira (60 sn) beklenmedi").isLessThan(10_000L);
        assertThat(c.workerStatus().released()).isEqualTo(1);
        List<Claim> pod3 = rig.claims.stream().filter(k -> k.pod() == 3).toList();
        assertThat(pod3).hasSize(1);
        assertThat(rig.claims.stream().filter(k -> k.chunkId() == pod3.get(0).chunkId() && k.pod() != 3))
                .hasSize(1).allMatch(k -> k.attempts() == 1);
        assertEachSavedOnce(rig, 100);
        assertOnlyCoordinatorDecides(rig, a);
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(100), FULL), rig.decisions(a));
    }

    private void awaitState(List<Long> ids, String state) {
        long until = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < until) {
            boolean all = true;
            for (Long id : ids) {
                String s = rig.mp.jdbc().queryForObject("SELECT state FROM cert_sweep_chunks WHERE id = ?", String.class, id);
                if (!state.equals(s)) all = false;
            }
            if (all) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("parçalar " + state + " olmadı: " + ids);
    }

    @Test
    @DisplayName("s9b: koordinatöre kapanış turun ortasında → sonraki beklemede bırakır (ABANDONED / SHUTDOWN, parçalar sonraki "
            + "koordinatöre kalır), hiçbir kayıt yok; uçuştaki parçanın tamamlaması reddedilir; sonraki tur artıkları siler")
    void s9b_coordinatorShutdownMidRun_abandoned_lateCompletionRejected() {
        rig = new R34CertRig().inventory(100);
        Node a = rig.node(1);
        Node b = rig.node(2);
        Node c = rig.node(3);
        c.holdRuns = true;
        AtomicLong closedAt = new AtomicLong(-1);
        rig.stepHooks.add(now -> {
            if (closedAt.get() < 0 && rig.steps.get() == 2) {
                b.ss().onContextClosed(null);
                b.worker.onContextClosed(null);
                b.chunking.onContextClosed(null);
                closedAt.set(now);
                rig.maxSteps = rig.steps.get() + 10;   // koordinatör kapanışı birkaç adımda görmeli
            }
        });
        rig.runFull(b);

        Map<String, Object> run = rig.onlyRun();
        String runId = (String) run.get("run_id");
        assertThat(run.get("state")).isEqualTo(CertSweepStore.RUN_ABANDONED);
        assertThat(run.get("outcome")).isEqualTo(CertSweepStore.OUTCOME_SHUTDOWN);
        assertThat(intOfLong(run.get("finished_ms")) - closedAt.get()).isLessThanOrEqualTo(R34CertRig.STEP_MS);
        assertThat(b.calls).as("kapanış: hiçbir kayıt / karar").isEmpty();
        assertThat(rig.chunks(runId)).as("kapanışta parçalar silinmez (sonraki koordinatör siler)").isNotEmpty();

        rig.maxSteps = Long.MAX_VALUE;
        c.holdRuns = false;
        rig.step();
        assertThat(c.workerStatus().late()).as("bırakılmış koşunun tamamlaması reddedildi").isEqualTo(1);
        assertThat(rig.completions.stream().filter(k -> k.pod() == 3)).hasSize(1).noneMatch(Completion::accepted);
        assertThat(rig.chunks(runId).stream().filter(m -> "DONE".equals(m.get("state")))
                .filter(m -> String.valueOf(m.get("worker")).equals(c.owner()))).isEmpty();
        int claimsBefore = rig.claims.size();
        rig.steps(10_000L);
        assertThat(rig.claims.size()).as("bırakılmış koşunun parçası sahiplenilmez").isEqualTo(claimsBefore);

        b.kill();
        rig.runFull(a);
        assertThat(rig.runRows()).hasSize(2);
        assertThat(rig.runRows().get(1).get("state")).isEqualTo(CertSweepStore.RUN_DONE);
        assertThat(rig.chunkRows()).as("artık parçalar silindi").isZero();
        assertEachSavedOnce(rig, 100);
        assertThat(a.saves()).isEqualTo(100);
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(100), FULL), rig.decisions(a));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // s10 — lider payı; s11 — tek pod
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("s10: tarama lideri başka canlı pod varken yalnız 10 sn bekleyen parçaları ve yarı bütçeyle (tek parça) alır; "
            + "lider olmayan aynı pod hemen ve tam bütçeyle (iki parça) alır")
    void s10_leaderWaitsStealAfterAndUsesHalfBudget() {
        rig = new R34CertRig().inventory(80);   // 4 parça
        Node a = rig.node(1, new Opts().leader());
        Node b = rig.node(2, new Opts().workerOff());
        Node c = rig.node(3);
        c.paused = true;   // canlı ama meşgul (parça almıyor)
        rig.runFull(b);

        Map<String, Object> run = rig.onlyRun();
        long created = intOfLong(run.get("created_ms"));
        assertThat(rig.claims).isNotEmpty().allMatch(k -> k.pod() == 1)
                .allMatch(k -> k.atMs() >= created + CertChunking.Tuning.DEFAULTS.stealAfterMs());
        Map<Long, Integer> perStep = new TreeMap<>();
        for (Claim k : rig.claims) perStep.merge(k.atMs(), 1, Integer::sum);
        assertThat(perStep.values()).allMatch(n -> n == 1);
        assertEachSavedOnce(rig, 80);

        // aynı pod lider değilken: hemen ve adım başına iki parça
        a.leader.set(false);
        rig.claims.clear();
        rig.runFull(b);
        Map<String, Object> second = rig.runRows().get(1);
        long created2 = intOfLong(second.get("created_ms"));
        Map<Long, Integer> perStep2 = new TreeMap<>();
        for (Claim k : rig.claims) perStep2.merge(k.atMs(), 1, Integer::sum);
        assertThat(rig.claims.stream().mapToLong(Claim::atMs).min().orElseThrow())
                .as("lider değil: steal-after beklenmez (en çok bir boş yoklama aralığı)")
                .isLessThan(created2 + CertChunking.Tuning.DEFAULTS.idlePollMs() + R34CertRig.STEP_MS);
        assertThat(perStep2.values()).as("tam bütçe: adım başına iki parça").contains(2);
    }

    @Test
    @DisplayName("s11: tek pod (başka canlı pod yok), anahtar AÇIK → klasik tur: koşu satırı yok, karar anahtar kapalı klasikle aynı")
    void s11_singlePodSwitchOn_isClassic() {
        rig = new R34CertRig().inventory(100);
        Node a = rig.node(1);
        a.otherLive.set(false);
        rig.runFull(a);

        assertThat(rig.runRows()).isEmpty();
        assertThat(rig.claims).isEmpty();
        assertClassicByRule(a.coordinator.status(), 1);
        assertThat(a.coordinator.status().chunkedRuns()).isZero();
        assertSameDecisions(R34CertRig.classicDecisions(r -> r.inventory(100), FULL), rig.decisions(a));
        verify(rig.email, never()).sendSystemAdminNetworkAlert(anyString(), any(), anyInt(), anyInt(), anyDouble(), anyDouble());
    }
}
