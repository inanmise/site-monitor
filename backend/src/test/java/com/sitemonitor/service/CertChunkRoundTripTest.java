package com.sitemonitor.service;

import com.sitemonitor.service.certsweep.CertChunkRunner;
import com.sitemonitor.service.certsweep.CertChunking;
import com.sitemonitor.service.certsweep.CertRunCoordinator;
import com.sitemonitor.service.certsweep.CertSweepFakes;
import com.sitemonitor.service.certsweep.CertSweepStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Çok pod Sürüm 3-4 paket B — PARÇALI TUR = KLASİK TUR (tasarım R3-4 §9.1 / §9.2 B; L-1, L-2, L-3). P0'ın dokuz altın kaydı
 * ({@code r34-golden/*.json}, DEĞİŞMEMİŞ 20.170.0 kodundan) üç biçimde koşturulur:
 * <ol>
 *   <li><b>Koordinatör var, anahtar kapalı:</b> döküm (gönderme sırası dahil) altın kayıtla BİREBİR; depo, ölçücü, canlılık,
 *       yayın kuruluşu okuması hiç çağrılmaz (L-1).</li>
 *   <li><b>Anahtar açık, başka canlı pod yok:</b> tur klasik — döküm birebir (tek pod ≡ kapalı).</li>
 *   <li><b>Parçalı:</b> sahte depo + işçi (başka pod) + GERÇEK ölçücü ({@link SchedulerCertChunks} → {@code gatherCertResults})
 *       + yayın kuruluşu yakınlığıyla karışık parça sırası → sonuçlar JSON'a kodlanıp çözülür, koordinatör klasik sırada kurar,
 *       {@code runCheckForDomains}'in DEĞİŞMEMİŞ geri kalanı koşar. {@code check_order} dışındaki döküm (kayıt haritaları
 *       sırasıyla, önbellek, {@code onRun}, alarm listeleri, {@code recordScanRun}, kilit, durum, günlükler) altın kayıtla aynı.
 *       Bilinçli farklar: kilit kaybı / kapanış senaryolarında günlükteki "kaç sonuç" sayısı (parçalı tur o anda topladığını
 *       yazar) ve kontrol-istisnası senaryosunda {@code checks} (işçinin ölçümü + koordinatörün yeniden ölçümü).</li>
 * </ol>
 */
class CertChunkRoundTripTest {

    static final String COORDINATOR = "pod-a-0000000a";
    static final String WORKER = "pod-b-0000000b";

    static Stream<String> scenarioNames() {
        return CertSweepGoldenHarness.scenarios().stream().map(CertSweepGoldenHarness.Scenario::name);
    }

    static String golden(String name) throws Exception {
        return Files.readString(CertSweepGoldenHarness.GOLDEN.resolve(name + ".json"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    /** Altın kaydın (tam döküm) seçilen bölümleri — yeniden ayrıştırmadan: aynı harness'la klasik koşu. */
    static String classicSections(String name, String... sections) throws Exception {
        CertSweepGoldenHarness h = new CertSweepGoldenHarness(CertSweepGoldenHarness.scenario(name));
        h.run();
        assertThat(h.transcript()).as("klasik koşu altın kayda eşit (karşılaştırma tabanı)").isEqualTo(golden(name));
        return h.transcript(sections);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 1) Koordinatör var, anahtar KAPALI → birebir (L-1)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioNames")
    @DisplayName("koordinatör bağlı + anahtar kapalı → tam döküm altın kayıtla birebir; depo / ölçücü / canlılık hiç çağrılmaz")
    void offWithCoordinatorIsByteIdentical(String name) throws Exception {
        CertSweepGoldenHarness h = new CertSweepGoldenHarness(CertSweepGoldenHarness.scenario(name));
        CertSweepStore store = mock(CertSweepStore.class);
        CertChunkRunner runner = mock(CertChunkRunner.class);
        AtomicInteger liveness = new AtomicInteger();
        AtomicInteger issuers = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        CertRunCoordinator c = CertSweepFakes.coordinator(CertSweepFakes.chunking(false, CertSweepFakes.tuning()), store,
                runner, () -> {
                    liveness.incrementAndGet();
                    return true;
                }, d -> {
                    issuers.incrementAndGet();
                    return Map.of();
                }, COORDINATOR, System::currentTimeMillis, ms -> sleeps.incrementAndGet());
        ReflectionTestUtils.setField(h.scheduler(), "certRunCoordinator", c);
        h.run();
        assertThat(h.transcript()).isEqualTo(golden(name));
        verifyNoInteractions(store, runner);
        assertThat(liveness).hasValue(0);
        assertThat(issuers).hasValue(0);
        assertThat(sleeps).hasValue(0);
        assertThat(c.status().classicFallback()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioNames")
    @DisplayName("anahtar açık ama başka canlı pod yok → klasik tur, tam döküm birebir (tek pod ≡ kapalı); depo yazılmaz")
    void onSinglePodIsClassic(String name) throws Exception {
        CertSweepGoldenHarness h = new CertSweepGoldenHarness(CertSweepGoldenHarness.scenario(name));
        AtomicLong clock = new AtomicLong(1_000_000L);
        CertSweepFakes.Store store = new CertSweepFakes.Store(clock::get);
        CertRunCoordinator c = CertSweepFakes.coordinator(CertSweepFakes.chunking(true, CertSweepFakes.tuning()), store,
                new SchedulerCertChunks(h.scheduler()), () -> false, d -> Map.of(), COORDINATOR, clock::get,
                ms -> clock.addAndGet(ms));
        ReflectionTestUtils.setField(h.scheduler(), "certRunCoordinator", c);
        h.run();
        assertThat(h.transcript()).isEqualTo(golden(name));
        // OB-L1: anahtar açıkken her tur başında öksüzler kapatılır (klasik turda da) — koşu yazılmaz, karar dökümü aynı
        assertThat(store.calls).containsExactly("settleOrphans");
        assertThat(store.runs).isEmpty();
        assertThat(c.status().classicByRule()).isEqualTo(1);
        assertThat(c.status().classicFallback()).isZero();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // 3) PARÇALI tur ≡ klasik karar dökümü (L-2 / L-3)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Parçalı koşu düzeneği: harness'ın zamanlayıcısı + gerçek ölçücü + sahte depo + başka pod'un işçisi. */
    static final class Chunked {
        final CertSweepGoldenHarness h;
        final AtomicLong clock = new AtomicLong(2_000_000L);
        final CertSweepFakes.Store store = new CertSweepFakes.Store(clock::get);
        final CertSweepFakes.Worker worker;
        final CertRunCoordinator coordinator;

        Chunked(String name) throws Exception {
            h = new CertSweepGoldenHarness(CertSweepGoldenHarness.scenario(name));
            SchedulerCertChunks runner = new SchedulerCertChunks(h.scheduler());
            worker = new CertSweepFakes.Worker(store, runner, WORKER);
            // Yayın kuruluşu klasik sıranın TERSİ → parça içeriği / sırası klasik sıradan farklı (yeniden kurma kanıtı)
            List<Map<String, Object>> due = CertSweepGoldenHarness.scenario(name).rows();
            Map<String, String> byDomain = new HashMap<>();
            for (int i = 0; i < due.size(); i++) {
                byDomain.put((String) due.get(i).get("domain"), String.format("CN=CA-%03d", due.size() - 1 - i));
            }
            Function<List<String>, Map<String, String>> issuers = names -> {
                Map<String, String> m = new HashMap<>();
                for (String n : names) if (byDomain.containsKey(n)) m.put(n, byDomain.get(n));
                return m;
            };
            coordinator = CertSweepFakes.coordinator(CertSweepFakes.chunking(true, CertSweepFakes.tuning()), store, runner,
                    () -> true, issuers, COORDINATOR, clock::get, ms -> {
                        clock.addAndGet(ms);
                        worker.drain();
                    });
            ReflectionTestUtils.setField(h.scheduler(), "certRunCoordinator", coordinator);
        }

        String runId() {
            return String.valueOf(((java.util.concurrent.atomic.AtomicReference<?>) ReflectionTestUtils
                    .getField(h.scheduler(), "lastRunId")).get());
        }
    }

    static final Set<String> COUNT_LOG_SCENARIOS = Set.of("lock-lost", "shutdown-during-gather");
    /** "… (runId=<run>, 4 sonuç)" / "… (runId=<run>, 2 / 5 sonuç)" — parçalı turda bırakma anındaki sayı. */
    static final Pattern RESULT_COUNT = Pattern.compile("runId=<run>, \\d+( / \\d+)? sonuç");

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarioNames")
    @DisplayName("parçalı tur: kayıt / önbellek / onRun / alarm / scan_runs / kilit / durum / günlük dökümü klasikle AYNI")
    void chunkedEqualsClassic(String name) throws Exception {
        Chunked k = new Chunked(name);
        k.h.run();
        String runId = k.runId();
        assertThat(k.store.run(runId)).as("parçalı koşuldu").isNotNull();

        // parça sırası klasik sıradan farklı (yakınlık) — yine de karar sırası klasik
        List<String> chunkOrder = new ArrayList<>();
        k.store.lastSpec.chunks().forEach(c -> c.entries().forEach(e -> chunkOrder.add(e.domain())));
        List<String> classicOrder = CertSweepGoldenHarness.scenario(name).rows().stream().map(r -> (String) r.get("domain")).toList();
        assertThat(chunkOrder).containsExactlyInAnyOrderElementsOf(classicOrder);
        if (classicOrder.size() > 1) assertThat(chunkOrder).isNotEqualTo(classicOrder);

        assertThat(k.h.transcript("scenario", "calls", "state"))
                .isEqualTo(classicSections(name, "scenario", "calls", "state"));
        String logs = k.h.transcript("logs");
        String classicLogs = classicSections(name, "logs");
        if (COUNT_LOG_SCENARIOS.contains(name)) {
            assertThat(RESULT_COUNT.matcher(logs).replaceAll("runId=<run>, N sonuç"))
                    .isEqualTo(RESULT_COUNT.matcher(classicLogs).replaceAll("runId=<run>, N sonuç"));
        } else {
            assertThat(logs).isEqualTo(classicLogs);
        }
        if ("check-throws".equals(name)) {
            // işçinin ölçümü fırlattı → parça "failed" tamamlandı → koordinatör yeniden ölçtü, o da fırlattı (klasik FAILED)
            assertThat(k.h.transcript("checks")).contains("t1.example.com", "t2.example.com", "t3.example.com", "t4.example.com");
        } else {
            assertThat(k.h.transcript("checks")).as("her alan adı tam bir kez ölçüldü").isEqualTo(classicSections(name, "checks"));
        }
    }

    @Test
    @DisplayName("parçalı koşu satırının son durumu tur sonucunu izler (DONE / FAILED / ABANDONED) ve bitince parçalar silinir")
    void runRowFinalStates() throws Exception {
        Map<String, String> expected = Map.of(
                "hourly", "DONE:DECIDED",
                "full", "DONE:DECIDED",
                "suspected-outage", "DONE:DECIDED",
                "save-throws", "FAILED:FAILED",
                "check-throws", "FAILED:FAILED",
                "lock-lost", "ABANDONED:LOCK_LOST",
                "shutdown-during-gather", "ABANDONED:SHUTDOWN");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            Chunked k = new Chunked(e.getKey());
            k.h.run();
            CertSweepFakes.Store.Run run = k.store.run(k.runId());
            assertThat(run.state + ":" + run.outcome).as(e.getKey()).isEqualTo(e.getValue());
            if (!"shutdown-during-gather".equals(e.getKey())) {
                assertThat(k.store.chunksOf(k.runId())).as(e.getKey() + ": parçalar silindi").isEmpty();
            }
        }
    }

    @Test
    @DisplayName("koşu başka koordinatörce kapatıldı (nabız 0 satır) ama kilit hâlâ 'tutuluyor' görünüyor → çağıran HİÇBİR şey işlemez")
    void heartbeatLossStopsTheCallerBeforeSaving() throws Exception {
        Chunked k = new Chunked("hourly");
        // İşçi hiç çalışmaz; koşu satırı dışarıdan kapatılır (öksüz sayıldı), saat nabız aralığını geçer
        CertRunCoordinator c = CertSweepFakes.coordinator(CertSweepFakes.chunking(true, CertSweepFakes.tuning()), k.store,
                new SchedulerCertChunks(k.h.scheduler()), () -> true, d -> Map.of(), COORDINATOR, k.clock::get, ms -> {
                    k.clock.addAndGet(ms);
                    k.store.runs.values().forEach(r -> r.state = CertSweepStore.RUN_ABANDONED);
                });
        ReflectionTestUtils.setField(k.h.scheduler(), "certRunCoordinator", c);
        k.h.run();
        String calls = k.h.transcript("calls");
        assertThat(calls).doesNotContain("\"call\": \"saveResult\"").doesNotContain("\"call\": \"onRun\"")
                .doesNotContain("\"call\": \"evictAllCaches\"").doesNotContain("\"call\": \"recordScanRun\"")
                .doesNotContain("\"call\": \"processResults\"").contains("\"call\": \"lock.release\"");
        assertThat(k.h.transcript("logs")).contains("cert-check kilidi kaybedildi — sonuçlar işlenmedi");
        assertThat(k.store.finishes).containsExactly(k.runId() + ":ABANDONED:LOCK_LOST:false");
    }

    @Test
    @DisplayName("parçalı tur: kontroller İŞÇİDE koşar (koordinatör ölçmez), koşu satırı DECIDING'ten geçer, workers_json işçiyi adlar")
    void workerMeasuresCoordinatorDecides() throws Exception {
        Chunked k = new Chunked("full");
        k.h.run();
        CertSweepFakes.Store.Run run = k.store.run(k.runId());
        assertThat(run.workersJson).contains(WORKER).doesNotContain(COORDINATOR);
        assertThat(k.worker.completed).isEqualTo(k.store.lastSpec.chunksTotal());
        assertThat(k.coordinator.status().takeovers()).isZero();
        assertThat(k.coordinator.status().chunkedRuns()).isEqualTo(1);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // İnceleme düzeltmeleri (FX-COORD, 2026-10-29)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Koordinatör aykırı düzeneği (H-1): 40 alan adı (8 parça), denetleyici gerçeği SAĞLIKLI. Koordinatör A'nın çıkışı bozuk —
     * kendi işçisi 8 parçanın 6'sını alır (30 ağ hatası); B (gerçek ölçücü → harness denetleyicisi) kalan 2'yi. A aykırı → 30 alan
     * adı kaçınılan işçi = A ile 6 ikinci görüş parçasına dağıtılır; B {@code soLimit} tanesini ölçer, sonrakini tutarken ölür.
     */
    static final class Outlier {
        final CertSweepGoldenHarness h;
        final AtomicLong clock = new AtomicLong(3_000_000L);
        final CertSweepFakes.Store store = new CertSweepFakes.Store(clock::get);
        final CertRunCoordinator coordinator;
        int aClaims;
        int soDone;
        boolean bDead;

        Outlier(int soLimit) throws Exception {
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, CertSweepGoldenHarness.Outcome> outcomes = new java.util.LinkedHashMap<>();
            for (int i = 0; i < 40; i++) {
                String d = String.format("o%03d.example.com", i);
                rows.add(CertSweepGoldenHarness.row(d, 443, false, "", null, null));
                outcomes.put(d, CertSweepGoldenHarness.Outcome.ok(CertSweepGoldenHarness.valid(d, 90, false, 100L + i)));
            }
            h = new CertSweepGoldenHarness(new CertSweepGoldenHarness.Scenario("outlier", "hourly", true, true, rows, outcomes,
                    false, true, null));
            CertSweepFakes.Runner brokenA = new CertSweepFakes.Runner(COORDINATOR, CertSweepFakes::netErr);
            CertSweepFakes.Worker wa = new CertSweepFakes.Worker(store, brokenA, COORDINATOR);
            CertSweepFakes.Worker wb = new CertSweepFakes.Worker(store, new SchedulerCertChunks(h.scheduler()), WORKER);
            coordinator = CertSweepFakes.coordinator(CertSweepFakes.chunking(true, CertSweepFakes.tuning()), store, brokenA,
                    () -> true, d -> Map.of(), COORDINATOR, clock::get, ms -> {
                        clock.addAndGet(ms);
                        if (aClaims < 6) {
                            if (wa.tick()) aClaims++;
                            return;
                        }
                        if (bDead) return;
                        var k = store.claim(WORKER, 60_000L, Long.MAX_VALUE);
                        if (k.isEmpty()) return;
                        boolean so = store.chunksOf(k.get().runId()).stream()
                                .anyMatch(ch -> ch.id == k.get().id() && ch.avoid != null);
                        if (so && soDone >= soLimit) {
                            bDead = true;                                    // ikinci görüş parçasını tutarken ölür
                            return;
                        }
                        wb.run(k.get());
                        if (so) soDone++;
                    });
            ReflectionTestUtils.setField(h.scheduler(), "certRunCoordinator", coordinator);
        }

        String onRun() {
            String calls = h.transcript("calls");
            int at = calls.indexOf("\"call\": \"onRun\"");
            assertThat(at).as("onRun çağrıldı").isNotNegative();
            return calls.substring(at, calls.indexOf('}', at));
        }
    }

    @Test
    @DisplayName("H-1: koordinatör aykırı + ikinci görüş KISMİ (yardımcı 3 / 6 parçadan sonra öldü) → özgün sonuçların HEPSİ, onRun 'şüpheli' = özgün karar (30 / 40)")
    void coordinatorOutlierPartialSecondOpinion_onRunAsOriginal() throws Exception {
        Outlier o = new Outlier(3);
        o.h.run();
        assertThat(o.bDead).isTrue();
        assertThat(o.soDone).isEqualTo(3);
        // kısmi değişim 15 / 40 (%37,5) → şüphesiz → sahte alarmlar olurdu; hepsi-ya-da-hiçbiri: 30 / 40 şüpheli
        assertThat(o.onRun()).contains("\"networkErrors\": 30").contains("\"total\": 40").contains("\"suspected\": true");
        assertThat(o.h.transcript("calls")).as("şüpheli turda yeni alarm yok — yalnız kapanış uzlaştırması")
                .doesNotContain("\"call\": \"processResults\"").contains("\"call\": \"resolveVerifiedStaleCertAlerts\"");
    }

    @Test
    @DisplayName("H-1: koordinatör aykırı + ikinci görüş TAM → bütün yeniden ölçümler kullanılır, onRun düzeltilmiş oranı görür (0 / 40)")
    void coordinatorOutlierFullSecondOpinion_onRunCorrected() throws Exception {
        Outlier o = new Outlier(Integer.MAX_VALUE);
        o.h.run();
        assertThat(o.soDone).isEqualTo(6);
        assertThat(o.onRun()).contains("\"networkErrors\": 0").contains("\"suspected\": false");
    }

    @Test
    @DisplayName("SW-L3: koordinatörün yerel ölçümü KENDİ iş parçacığında işaretli (lider alt grup kuralı ona uygulanmaz); tur bitince işaret kalkar")
    void coordinatorThreadIsMarkedDuringGather() throws Exception {
        CertSweepGoldenHarness h = new CertSweepGoldenHarness(CertSweepGoldenHarness.scenario("full"));
        SchedulerCertChunks real = new SchedulerCertChunks(h.scheduler());
        List<Boolean> marks = new ArrayList<>();
        CertChunkRunner recording = new CertChunkRunner() {
            @Override
            public List<Map<String, Object>> checkDomains(List<Map<String, Object>> rows, String runId) {
                marks.add(h.scheduler().isCoordinatingCertRun());
                return real.checkDomains(rows, runId);
            }

            @Override
            public boolean closing() {
                return real.closing();
            }

            @Override
            public long networkErrorCount(List<Map<String, Object>> results) {
                return real.networkErrorCount(results);
            }

            @Override
            public int executorCoreSize() {
                return real.executorCoreSize();
            }
        };
        AtomicLong clock = new AtomicLong(4_000_000L);
        CertSweepFakes.Store store = new CertSweepFakes.Store(clock::get);
        // işçi yok → 60 sn sonra koordinatör hepsini kendisi ölçer
        CertRunCoordinator c = CertSweepFakes.coordinator(CertSweepFakes.chunking(true, CertSweepFakes.tuning()), store,
                recording, () -> true, d -> Map.of(), COORDINATOR, clock::get, ms -> clock.addAndGet(ms));
        ReflectionTestUtils.setField(h.scheduler(), "certRunCoordinator", c);
        assertThat(h.scheduler().isCoordinatingCertRun()).isFalse();
        h.run();
        assertThat(marks).isNotEmpty().allMatch(Boolean.TRUE::equals);
        assertThat(h.scheduler().isCoordinatingCertRun()).as("işaret tur bitince kaldırıldı").isFalse();
        assertThat(h.transcript("calls")).contains("\"call\": \"onRun\"");
    }
}
