package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-4 — {@link SchedulerCertChunks} (inceleme SW-L3, FX-COORD 2026-10-29): tarama lideri bir parçanın satırlarını
 * en çok {@code max(1, çekirdek / 2)}'lik alt gruplarla ölçer (aynı klasik toplama, sıra korunur) — liderin bir parçası
 * {@code certCheckExecutor}'ın bütün çekirdeğini tutmaz. Takipçi, turun koordinatörü (kendi iş parçacığı) ve okunamayan çekirdek
 * bölmez; kapanışta kalan alt gruplar gönderilmez (liste kısa); istisna aynen fırlar.
 */
class SchedulerCertChunksTest {

    final List<Integer> batches = Collections.synchronizedList(new ArrayList<>());

    static List<Map<String, Object>> rows(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("domain", String.format("d%03d.example.com", i));
            r.put("port", 443);
            out.add(r);
        }
        return out;
    }

    /** Sahte zamanlayıcı: {@code gatherCertResults} her çağrının boyutunu kaydeder, her satıra bir sonuç döner. */
    SchedulerService scheduler(boolean leader, boolean coordinating, int core) {
        SchedulerService s = mock(SchedulerService.class);
        when(s.sweepLeaderHint()).thenReturn(leader);
        when(s.isCoordinatingCertRun()).thenReturn(coordinating);
        when(s.certCheckCoreSize()).thenReturn(core);
        when(s.gatherCertResults(anyList(), anyString())).thenAnswer(i -> {
            List<Map<String, Object>> rows = i.getArgument(0);
            batches.add(rows.size());
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("domain", r.get("domain"));
                m.put("run_id", i.getArgument(1));
                out.add(m);
            }
            return out;
        });
        return s;
    }

    static List<Object> domains(List<Map<String, Object>> results) {
        return results.stream().map(m -> m.get("domain")).toList();
    }

    @Test
    @DisplayName("lider: 20 satırlık parça çekirdek 20 → 10 + 10 (yarım çekirdek); sıra ve sonuçlar klasik toplamayla aynı")
    void leaderMeasuresInHalfCoreSubBatches() {
        SchedulerCertChunks r = new SchedulerCertChunks(scheduler(true, false, 20));
        List<Map<String, Object>> in = rows(20);
        List<Map<String, Object>> out = r.checkDomains(in, "run-1");
        assertThat(batches).containsExactly(10, 10);
        assertThat(domains(out)).isEqualTo(domains(in));
        assertThat(out).allSatisfy(m -> assertThat(m).containsEntry("run_id", "run-1"));

        batches.clear();
        new SchedulerCertChunks(scheduler(true, false, 7)).checkDomains(rows(20), "run-2");
        assertThat(batches).as("çekirdek 7 → alt grup 3").containsExactly(3, 3, 3, 3, 3, 3, 2);
        batches.clear();
        new SchedulerCertChunks(scheduler(true, false, 1)).checkDomains(rows(3), "run-3");
        assertThat(batches).as("çekirdek 1 → en az 1").containsExactly(1, 1, 1);
    }

    @Test
    @DisplayName("takipçi / turun koordinatörü / çekirdek okunamadı / parça alt gruptan küçük → tek klasik toplama (bölünmez)")
    void noSubBatchingOutsideLeaderChunks() {
        new SchedulerCertChunks(scheduler(false, false, 20)).checkDomains(rows(20), "r");
        assertThat(batches).as("takipçi").containsExactly(20);
        batches.clear();
        new SchedulerCertChunks(scheduler(true, true, 20)).checkDomains(rows(20), "r");
        assertThat(batches).as("koordinatör kendi devraldığı alan adlarını bütün çekirdekle ölçer").containsExactly(20);
        batches.clear();
        new SchedulerCertChunks(scheduler(true, false, 0)).checkDomains(rows(20), "r");
        assertThat(batches).as("çekirdek okunamadı").containsExactly(20);
        batches.clear();
        new SchedulerCertChunks(scheduler(true, false, 20)).checkDomains(rows(8), "r");
        assertThat(batches).as("alt gruptan küçük").containsExactly(8);
        batches.clear();
        SchedulerService throwing = scheduler(true, false, 20);
        when(throwing.isCoordinatingCertRun()).thenThrow(new IllegalStateException("okunamadı"));
        new SchedulerCertChunks(throwing).checkDomains(rows(20), "r");
        assertThat(batches).as("okuma hatası → bölme yok").containsExactly(20);
        assertThat(SchedulerCertChunks.leaderSubBatch(scheduler(true, false, 20))).isEqualTo(10);
    }

    @Test
    @DisplayName("FX2-WORKER L-3: lider, işçi iş parçacığı, BU pod'un koşan turunun parçası → tek klasik toplama (alt grup yok); "
            + "başka pod'un turunun parçası (rahatlamayla alınan dahil) yine 10 + 10")
    void ownRunningRunChunkIsOneGather() {
        SchedulerService s = scheduler(true, false, 20);
        when(s.coordinatesCertRun("run-own")).thenReturn(true);
        SchedulerCertChunks r = new SchedulerCertChunks(s);
        List<Map<String, Object>> in = rows(20);
        List<Map<String, Object>> out = r.checkDomains(in, "run-own");
        assertThat(batches).as("kendi turu: klasik turun lider pod'daki davranışı — bütün çekirdek").containsExactly(20);
        assertThat(domains(out)).isEqualTo(domains(in));
        batches.clear();
        r.checkDomains(rows(20), "run-other");
        assertThat(batches).as("başka pod'un turu bölünür").containsExactly(10, 10);
        assertThat(SchedulerCertChunks.leaderSubBatch(s, "run-own")).isZero();
        assertThat(SchedulerCertChunks.leaderSubBatch(s, "run-other")).isEqualTo(10);
        assertThat(SchedulerCertChunks.leaderSubBatch(s, null)).as("kimliksiz: eski kural").isEqualTo(10);
    }

    @Test
    @DisplayName("FX2-WORKER L-3 (gerçek zamanlayıcı belleği): lider pod turu koordine ederken BAŞKA iş parçacığındaki işçisi kendi "
            + "turunun parçalarını parça başına TEK toplamayla ölçer (çekirdek 4 → alt grup 2 olurdu); başka turun kimliği ve tur "
            + "bittikten sonra aynı kimlik bölünür")
    void ownRunMemoryOnRealScheduler() throws Exception {
        CertSweepGoldenHarness h = new CertSweepGoldenHarness(CertSweepGoldenHarness.scenario("full"));   // 12 alan adı
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor core4 =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();   // yalnız çekirdek sayısı okunur
        core4.setCorePoolSize(4);
        org.springframework.test.util.ReflectionTestUtils.setField(h.scheduler(), "certCheckExecutor", core4);
        // Sığ kopya: running / currentRunId / denetleyici aynı nesneler — casus, gerçek zamanlayıcının belleğini okur
        SchedulerService spy = org.mockito.Mockito.spy(h.scheduler());
        doAnswer(i -> {
            batches.add(((List<?>) i.getArgument(0)).size());
            return i.callRealMethod();
        }).when(spy).gatherCertResults(anyList(), anyString());
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(3_000_000L);
        com.sitemonitor.service.certsweep.CertSweepFakes.Store store = new com.sitemonitor.service.certsweep.CertSweepFakes.Store(clock::get);
        com.sitemonitor.service.certsweep.CertSweepFakes.Worker worker =
                new com.sitemonitor.service.certsweep.CertSweepFakes.Worker(store, new SchedulerCertChunks(spy), "pod-a-worker");
        List<Integer> elsewhere = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.ExecutorService workerThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            com.sitemonitor.service.certsweep.CertRunCoordinator c = com.sitemonitor.service.certsweep.CertSweepFakes.coordinator(
                    com.sitemonitor.service.certsweep.CertSweepFakes.chunking(true,
                            com.sitemonitor.service.certsweep.CertSweepFakes.tuning()),   // parça 5
                    store, new SchedulerCertChunks(h.scheduler()), () -> true, d -> Map.of(), "pod-a-coord", clock::get, ms -> {
                        clock.addAndGet(ms);
                        try {
                            workerThread.submit(() -> {   // işçi: koordinatörden AYRI iş parçacığı (işaretsiz)
                                elsewhere.add(SchedulerCertChunks.leaderSubBatch(spy, "run-elsewhere"));
                                worker.drain();
                            }).get(30, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
            org.springframework.test.util.ReflectionTestUtils.setField(h.scheduler(), "certRunCoordinator", c);
            h.run();
        } finally {
            workerThread.shutdownNow();
        }
        String runId = String.valueOf(((java.util.concurrent.atomic.AtomicReference<?>) org.springframework.test.util
                .ReflectionTestUtils.getField(h.scheduler(), "lastRunId")).get());
        assertThat(store.run(runId)).as("parçalı koşuldu").isNotNull();
        assertThat(worker.completed).as("işçi parçaları ölçtü").isPositive();
        assertThat(batches).as("her parça TEK toplama: 5 / 5 / 2 — bölünseydi 2 + 2 + 1 …").hasSize(worker.completed)
                .allMatch(n -> n <= 5).anyMatch(n -> n > 2);
        assertThat(elsewhere).as("aynı anda başka turun parçası bölünürdü (lider, çekirdek 4)").isNotEmpty().allMatch(n -> n == 2);
        assertThat(SchedulerCertChunks.leaderSubBatch(spy, runId)).as("tur bitti — kendi turu sayılmaz").isEqualTo(2);
        assertThat(spy.coordinatesCertRun(runId)).isFalse();
    }

    @Test
    @DisplayName("kapanış: alt grup kısa dönerse ya da alt gruplar arasında kapanış başlarsa kalanlar gönderilmez, liste KISA döner")
    void closingStopsBetweenSubBatches() {
        SchedulerService s = scheduler(true, false, 20);
        doAnswer(i -> {
            List<Map<String, Object>> rows = i.getArgument(0);
            batches.add(rows.size());
            List<Map<String, Object>> out = new ArrayList<>();
            for (int k = 0; k < rows.size() - 4; k++) out.add(new LinkedHashMap<>(rows.get(k)));   // kapanış: kısa
            return out;
        }).when(s).gatherCertResults(anyList(), anyString());
        List<Map<String, Object>> out = new SchedulerCertChunks(s).checkDomains(rows(20), "r");
        assertThat(batches).containsExactly(10);
        assertThat(out).hasSize(6);

        batches.clear();
        SchedulerService closing = scheduler(true, false, 20);
        when(closing.certRunClosing()).thenReturn(true);
        List<Map<String, Object>> out2 = new SchedulerCertChunks(closing).checkDomains(rows(20), "r");
        assertThat(batches).as("ilk alt gruptan sonra kapanış görüldü").containsExactly(10);
        assertThat(out2).hasSize(10);
    }

    @Test
    @DisplayName("bir kontrolün istisnası klasikteki gibi AYNEN fırlar; boş satır listesi zamanlayıcıya gitmez")
    void exceptionPropagatesAndEmptyRowsSkip() {
        SchedulerService s = scheduler(true, false, 20);
        IllegalStateException boom = new IllegalStateException("denetleyici çöktü");
        doThrow(boom).when(s).gatherCertResults(anyList(), anyString());
        assertThatThrownBy(() -> new SchedulerCertChunks(s).checkDomains(rows(20), "r")).isSameAs(boom);

        SchedulerService idle = scheduler(true, false, 20);
        assertThat(new SchedulerCertChunks(idle).checkDomains(List.of(), "r")).isEmpty();
        verify(idle, never()).gatherCertResults(anyList(), anyString());
    }
}
