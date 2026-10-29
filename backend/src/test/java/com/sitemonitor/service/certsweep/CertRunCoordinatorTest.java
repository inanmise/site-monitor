package com.sitemonitor.service.certsweep;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.service.certsweep.CertSweepFakes.Runner;
import com.sitemonitor.service.certsweep.CertSweepFakes.Store;
import com.sitemonitor.service.certsweep.CertSweepFakes.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static com.sitemonitor.service.certsweep.CertSweepFakes.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Çok pod Sürüm 3-4 paket B — {@link CertRunCoordinator} birim testleri (tasarım R3-4 §9.2 B): kip kararı (kapalıyken tek
 * bellek okuması, {@code PodLiveness} en son), parça kurulumu ve 500 parça tavanı, klasik sırada yeniden kurma, kayıp parça
 * devralma, kullanılamaz uzak sonucun yeniden ölçümü, ilerleme / işçisiz / depo hatası kuralları, kilit kaybı / nabız 0 satır /
 * kapanış → ABANDONED ve hiçbir sonuç dönmez, öksüz koşu kapanışı, son durumlar, ölçücü istisnasının AYNEN iletilmesi ve
 * koordinatörün kendi hatasında klasiğe dönüş. Sahte saat: her bekleme sahte saati ilerletir ve betik adımını koşar
 * (deterministik, tek iş parçacığı).
 */
class CertRunCoordinatorTest {

    static final String ME = "pod-a-0000000a";
    static final String B = "pod-b-0000000b";
    static final String C = "pod-c-0000000c";

    final AtomicLong clock = new AtomicLong(1_000_000L);
    final Store store = new Store(clock::get);
    volatile boolean lock = true;
    volatile boolean stop = false;
    Runnable onTick = () -> { };
    final AtomicInteger ticks = new AtomicInteger();

    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(CertRunCoordinator.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
        logger.setLevel(null);
    }

    CertRunCoordinator coordinator(boolean enabled, Runner runner, boolean live, Function<List<String>, Map<String, String>> issuers) {
        return new CertRunCoordinator(CertSweepFakes.chunking(enabled, CertSweepFakes.tuning()), store, runner, () -> live,
                issuers, () -> ME, clock::get, ms -> {
                    clock.addAndGet(ms);
                    ticks.incrementAndGet();
                    onTick.run();
                });
    }

    CertRunCoordinator.Request req(String runId) {
        return new CertRunCoordinator.Request(runId, "hourly", true, () -> lock, () -> stop, 900_000L, () -> 3, () -> 0.5);
    }

    static Runner healthy(String pod) {
        return new Runner(pod, CertSweepFakes::ok);
    }

    List<String> warns() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }

    static List<String> domainsOf(List<Map<String, Object>> results) {
        return results.stream().map(m -> (String) m.get("domain")).toList();
    }

    static List<String> domainsOfRows(List<Map<String, Object>> rows) {
        return rows.stream().map(m -> (String) m.get("domain")).toList();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Kip kararı (L-1)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("anahtar kapalı: null — depo, ölçücü, canlılık ve yayın kuruluşu okuması HİÇ çağrılmaz, sayaç da artmaz")
    void offIsOneMemoryRead() {
        CertSweepStore mockStore = mock(CertSweepStore.class);
        CertChunkRunner mockRunner = mock(CertChunkRunner.class);
        AtomicInteger liveness = new AtomicInteger();
        AtomicInteger issuers = new AtomicInteger();
        CertRunCoordinator c = new CertRunCoordinator(CertSweepFakes.chunking(false, CertSweepFakes.tuning()), mockStore,
                mockRunner, () -> {
                    liveness.incrementAndGet();
                    return true;
                }, d -> {
                    issuers.incrementAndGet();
                    return Map.of();
                }, () -> ME, clock::get, ms -> {
                    throw new AssertionError("uyumamalı");
                });
        assertThat(c.gather(rows(50, "x"), req("r-off"))).isNull();
        verifyNoInteractions(mockStore, mockRunner);
        assertThat(liveness).hasValue(0);
        assertThat(issuers).hasValue(0);
        assertThat(c.status().classicFallback()).isZero();
        assertThat(logs.list).isEmpty();

        // koordinatör hiç yoksa (null CertChunking) da null
        CertRunCoordinator none = new CertRunCoordinator(null, mockStore, mockRunner, () -> true, null, () -> ME,
                clock::get, ms -> { });
        assertThat(none.gather(rows(50, "x"), req("r-none"))).isNull();
        verifyNoInteractions(mockStore, mockRunner);
    }

    @Test
    @DisplayName("kip: depo yok / tablolar hazır değil / az alan adı / kapanış / başka canlı pod yok → klasik (sayılır); canlılık EN SON")
    void modeDecision() {
        Runner r = healthy(ME);
        AtomicInteger liveReads = new AtomicInteger();
        CertChunking cc = CertSweepFakes.chunking(true, new CertChunking.Tuning(true, 5, 10, 4, 0, 60_000L, 10_000L, 50, 250L, 5_000L));

        CertRunCoordinator noStore = new CertRunCoordinator(cc, null, r, () -> true, null, () -> ME, clock::get, ms -> { });
        assertThat(noStore.gather(rows(20, "a"), req("m1"))).isNull();
        CertRunCoordinator noRunner = new CertRunCoordinator(cc, store, null, () -> true, null, () -> ME, clock::get, ms -> { });
        assertThat(noRunner.gather(rows(20, "a"), req("m2"))).isNull();

        CertRunCoordinator c = new CertRunCoordinator(cc, store, r, () -> {
            liveReads.incrementAndGet();
            return false;
        }, null, () -> ME, clock::get, ms -> { });
        store.tablesReady = false;
        assertThat(c.gather(rows(20, "a"), req("m3"))).isNull();
        store.tablesReady = true;
        assertThat(c.gather(rows(9, "a"), req("m4"))).as("min-domains 10").isNull();
        stop = true;
        assertThat(c.gather(rows(20, "a"), req("m5"))).isNull();
        stop = false;
        assertThat(liveReads).as("canlılık yalnız diğer koşullar tutunca okunur").hasValue(0);
        assertThat(c.gather(rows(20, "a"), req("m6"))).as("başka canlı pod yok (ya da bilinmiyor)").isNull();
        assertThat(liveReads).hasValue(1);
        assertThat(c.status().classicByRule()).isEqualTo(4);
        assertThat(c.status().classicFallback()).as("kural gereği klasik — arıza değil (inceleme OB-L6)").isZero();
        assertThat(store.calls).as("klasik kararında yalnız öksüz kapatma (tablolar hazır, kapanmıyor — OB-L1); koşu yazılmaz")
                .containsExactly("settleOrphans", "settleOrphans", "settleOrphans");
        assertThat(r.checked).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Parça kurulumu, klasik sırada yeniden kurma
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("yayın kuruluşu yakınlığıyla bölünür, iki işçi ölçer, sonuç KLASİK sırada; DECIDING + workers_json, DONE kapanış")
    void classicOrderWithAffinityAndTwoWorkers() {
        Runner local = healthy(ME);
        Worker wb = new Worker(store, healthy(B), B);
        Worker wc = new Worker(store, healthy(C), C);
        onTick = () -> {
            wb.tick();
            wc.tick();
        };
        List<Map<String, Object>> due = rows(23, "d");
        // ters sıralı yayın kuruluşları → parça sırası klasik sıradan farklı
        Function<List<String>, Map<String, String>> issuers = names -> {
            Map<String, String> m = new HashMap<>();
            for (String n : names) m.put(n, "CN=CA-" + new StringBuilder(n).reverse());
            return m;
        };
        CertRunCoordinator c = coordinator(true, local, true, issuers);
        CertRunCoordinator.Gathered g = c.gather(due, req("run-order"));

        assertThat(g).isNotNull();
        assertThat(g.aborted()).isFalse();
        assertThat(domainsOf(g.results())).isEqualTo(domainsOfRows(due));
        assertThat(g.results()).allSatisfy(m -> assertThat(m).containsEntry("run_id", "run-order"));
        assertThat(local.checked).as("koordinatör kendisi ölçmedi").isEmpty();

        List<String> chunkOrder = new ArrayList<>();
        for (CertChunkSpec s : store.lastSpec.chunks()) for (CertChunkSpec.Entry e : s.entries()) chunkOrder.add(e.domain());
        assertThat(chunkOrder).as("parça sırası yayın kuruluşuna göre").isNotEqualTo(domainsOfRows(due))
                .containsExactlyInAnyOrderElementsOf(domainsOfRows(due));
        assertThat(store.lastSpec.chunksTotal()).isEqualTo(5);
        assertThat(store.lastSpec.kind()).isEqualTo("hourly");
        assertThat(store.lastSpec.coordinator()).isEqualTo(ME);

        Store.Run run = store.run("run-order");
        assertThat(run.state).isEqualTo(CertSweepStore.RUN_DECIDING);
        assertThat(run.workersJson).contains(B).contains(C);
        assertThat(CertSweepStatus.decodeWorkers(run.workersJson).stream().mapToInt(CertSweepStatus.WorkerTally::domains).sum())
                .isEqualTo(23);
        assertThat(run.takeovers).isZero();

        c.finish("run-order", true, false);
        assertThat(store.run("run-order").state).isEqualTo(CertSweepStore.RUN_DONE);
        assertThat(store.run("run-order").outcome).isEqualTo(CertSweepStore.OUTCOME_DECIDED);
        assertThat(store.chunksOf("run-order")).as("parçalar silinir").isEmpty();
        assertThat(c.status().chunkedRuns()).isEqualTo(1);
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(l -> l.startsWith("Sertifika taraması parçalı — runId=run-order, 23 alan adı, 5 parça (boyut 5)"))
                .anyMatch(l -> l.startsWith("Parçalı tarama toplandı — runId=run-order"));
    }

    @Test
    @DisplayName("koşu en çok 500 parça: 2 600 alan adı × boyut 5 → boyut 6, 434 parça; her sıra numarası tam bir kez")
    void chunkCapFiveHundred() {
        CertRunCoordinator c = coordinator(true, healthy(ME), true, null);
        CertRunSpec spec = c.buildSpec(rows(2600, "z"), req("cap"), ME, CertSweepFakes.tuning());
        assertThat(spec.chunkSize()).isEqualTo(6);
        assertThat(spec.chunksTotal()).isEqualTo(434).isLessThanOrEqualTo(CertChunking.MAX_CHUNKS_PER_RUN);
        Set<Integer> seen = new HashSet<>();
        for (CertChunkSpec s : spec.chunks()) for (CertChunkSpec.Entry e : s.entries()) assertThat(seen.add(e.index())).isTrue();
        assertThat(seen).hasSize(2600);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Devralma, yeniden ölçüm
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("kayıp parça (iki sahiplenen de öldü, deneme 2, kira doldu) → koordinatör iptal edip KENDİSİ ölçer; çift sonuç yok")
    void lostChunkTakenOver() {
        Runner local = healthy(ME);
        Worker live = new Worker(store, healthy(B), B);
        AtomicInteger step = new AtomicInteger();
        onTick = () -> {
            int s = step.incrementAndGet();
            if (s == 1) {
                store.claim("dead-1", 60_000L, Long.MAX_VALUE);                   // parça 0'ı alıp ölür
                live.drain();                                                      // diğerleri biter
            }
            if (s == 2) clock.addAndGet(61_000L);                                  // kira doldu (koordinatör nabız atar)
            if (s == 3) store.claim("dead-2", 60_000L, Long.MAX_VALUE);           // yeniden alınır (deneme 2), o da ölür
            if (s == 4) clock.addAndGet(61_000L);                                  // ikinci kira da doldu → KAYIP
        };
        List<Map<String, Object>> due = rows(12, "l");
        CertRunCoordinator c = coordinator(true, local, true, null);
        CertRunCoordinator.Gathered g = c.gather(due, req("run-lost"));

        assertThat(g.aborted()).isFalse();
        assertThat(domainsOf(g.results())).isEqualTo(domainsOfRows(due));
        List<String> chunk0 = store.lastSpec.chunks().get(0).entries().stream().map(CertChunkSpec.Entry::domain).toList();
        assertThat(local.checked).as("yalnız kayıp parçanın alan adları").containsExactlyInAnyOrderElementsOf(chunk0);
        assertThat(c.status().takeovers()).isEqualTo(1);
        assertThat(store.run("run-lost").takeovers).isEqualTo(1);
        assertThat(store.run("run-lost").reclaims).isEqualTo(1);
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(l -> l.equals("Parça 0 kayboldu (2 deneme) — koordinatör kendisi ölçüyor (runId=run-lost)"));
        assertThat(store.chunks.stream().filter(k -> k.no == 0).findFirst().orElseThrow().state)
                .isEqualTo(CertSweepStore.CHUNK_CANCELLED);
        // her alan adının tek sonucu: koordinatörün ölçtükleri koordinatöre, diğerleri B'ye
        assertThat(CertSweepStatus.decodeWorkers(store.run("run-lost").workersJson))
                .extracting(CertSweepStatus.WorkerTally::worker, CertSweepStatus.WorkerTally::domains)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(B, 7), org.assertj.core.groups.Tuple.tuple(ME, 5));
    }

    @Test
    @DisplayName("kullanılamaz uzak sonuç (failed / kırpılmış / eksik sıra) → koordinatör yalnız o alan adlarını yeniden ölçer")
    void unusableRemoteResultsRemeasured() {
        Runner local = healthy(ME);
        Runner rb = healthy(B);
        onTick = () -> {
            var claimed = store.claim(B, 60_000L, Long.MAX_VALUE);
            while (claimed.isPresent()) {
                ClaimedChunk k = claimed.get();
                List<ChunkCompletion.Result> res = new ArrayList<>();
                List<Map<String, Object>> measured = rb.checkDomains(k.rows(), k.runId());
                for (int i = 0; i < measured.size(); i++) {
                    int idx = k.entries().get(i).index();
                    if (idx == 1) res.add(new ChunkCompletion.Result(idx, Map.of("failed", "Boom"), false));
                    else if (idx == 2) continue;                                    // eksik sıra
                    else res.add(new ChunkCompletion.Result(idx, measured.get(i), false));
                }
                String json = ChunkCompletion.encodeResults(res);
                if (k.entries().stream().anyMatch(e -> e.index() == 3)) {
                    // sıra 3'ü kırpılmış olarak işaretle (t = true)
                    json = json.replace("{\"i\":3,\"t\":false", "{\"i\":3,\"t\":true");
                }
                store.complete(new ChunkCompletion(k.id(), k.claimToken(), B, json, k.domainCount(), 0, 0, 0));
                claimed = store.claim(B, 60_000L, Long.MAX_VALUE);
            }
        };
        List<Map<String, Object>> due = rows(10, "u");
        CertRunCoordinator c = coordinator(true, local, true, null);
        CertRunCoordinator.Gathered g = c.gather(due, req("run-unusable"));

        assertThat(g.aborted()).isFalse();
        assertThat(domainsOf(g.results())).isEqualTo(domainsOfRows(due));
        assertThat(g.results()).noneMatch(m -> m.containsKey("failed"));
        assertThat(local.checked).containsExactlyInAnyOrder("u001.example.com", "u002.example.com", "u003.example.com");
        assertThat(c.status().remeasured()).isEqualTo(3);
        assertThat(c.status().takeovers()).isZero();
    }

    @Test
    @DisplayName("ölçücü istisnası (uzak parça failed → yerel yeniden ölçüm de fırlatır) → AYNI istisna fırlar, koşu FAILED")
    void runnerExceptionPropagatesLikeClassic() {
        IllegalStateException boom = new IllegalStateException("denetleyici çöktü: e004");
        Runner local = healthy(ME);
        local.throwDomain = "e004.example.com";
        local.throwFor = () -> boom;
        Runner rb = healthy(B);
        rb.throwDomain = "e004.example.com";
        rb.throwFor = () -> new IllegalStateException("uzakta da");
        Worker wb = new Worker(store, rb, B);
        onTick = wb::drain;
        CertRunCoordinator c = coordinator(true, local, true, null);

        assertThatThrownBy(() -> c.gather(rows(10, "e"), req("run-throw"))).isSameAs(boom);
        assertThat(wb.failedChunks).isEqualTo(1);
        assertThat(store.run("run-throw").state).isEqualTo(CertSweepStore.RUN_FAILED);
        assertThat(store.run("run-throw").outcome).isEqualTo(CertSweepStore.OUTCOME_FAILED);
        assertThat(store.chunksOf("run-throw")).isEmpty();
        // finally kancası çağrılmaz (çağıranın chunkedRunId'si boş) — çağrılsa bile koşu zaten kapalı
        c.finish("run-throw", true, true);
        assertThat(store.run("run-throw").state).isEqualTo(CertSweepStore.RUN_FAILED);
    }

    @Test
    @DisplayName("koordinatörün KENDİ hatası turu düşürmez: koşu ABANDONED/FAILED, null döner → çağıran klasik toplar")
    void internalErrorFallsBackToClassic() {
        Runner local = healthy(ME);
        local.countThrows = new IllegalStateException("sayım bozuk");
        Worker wb = new Worker(store, healthy(B), B);
        onTick = wb::drain;
        CertRunCoordinator c = coordinator(true, local, true, null);
        assertThat(c.gather(rows(10, "i"), req("run-internal"))).isNull();
        assertThat(store.run("run-internal").state).isEqualTo(CertSweepStore.RUN_ABANDONED);
        assertThat(store.run("run-internal").outcome).isEqualTo(CertSweepStore.OUTCOME_FAILED);
        assertThat(c.status().classicFallback()).isEqualTo(1);
        assertThat(c.status().classicByRule()).isZero();
        assertThat(warns()).anyMatch(w -> w.startsWith("Parçalı tarama iç hatası — tur klasik toplamaya dönüyor (runId=run-internal)"));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Bırakma: kilit, nabız, kapanış
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("kilit kaybı → LOCK_LOST, sonuç DÖNMEZ; finally → ABANDONED/LOCK_LOST, parçalar silinir")
    void lockLostAbandons() {
        Worker wb = new Worker(store, healthy(B), B);
        onTick = () -> {
            wb.tick();
            lock = false;
        };
        CertRunCoordinator c = coordinator(true, healthy(ME), true, null);
        CertRunCoordinator.Gathered g = c.gather(rows(15, "k"), req("run-lock"));
        assertThat(g.aborted()).isTrue();
        assertThat(g.abortOutcome()).isEqualTo(CertSweepStore.OUTCOME_LOCK_LOST);
        assertThat(g.results()).isEmpty();
        c.finish("run-lock", false, false);
        assertThat(store.run("run-lock").state).isEqualTo(CertSweepStore.RUN_ABANDONED);
        assertThat(store.run("run-lock").outcome).isEqualTo(CertSweepStore.OUTCOME_LOCK_LOST);
        assertThat(store.finishes).containsExactly("run-lock:ABANDONED:LOCK_LOST:false");
        assertThat(store.chunksOf("run-lock")).isEmpty();
        assertThat(c.status().abandoned()).isEqualTo(1);
    }

    @Test
    @DisplayName("nabız 0 satır (koşu başka koordinatörce öksüz sayıldı) → LOCK_LOST")
    void heartbeatZeroRowsAbandons() {
        onTick = () -> {
            if (clock.get() > 1_005_000L) store.run("run-hb").state = CertSweepStore.RUN_ABANDONED;
        };
        CertRunCoordinator c = coordinator(true, healthy(ME), true, null);
        CertRunCoordinator.Gathered g = c.gather(rows(10, "h"), req("run-hb"));
        assertThat(g.abortOutcome()).isEqualTo(CertSweepStore.OUTCOME_LOCK_LOST);
        assertThat(store.calls).contains("heartbeat");
    }

    @Test
    @DisplayName("kapanış → SHUTDOWN; finally → ABANDONED/SHUTDOWN tek UPDATE (atShutdown, parçalar kalır)")
    void shutdownAbandonsAtShutdown() {
        Worker wb = new Worker(store, healthy(B), B);
        onTick = () -> {
            wb.tick();
            stop = true;
        };
        CertRunCoordinator c = coordinator(true, healthy(ME), true, null);
        CertRunCoordinator.Gathered g = c.gather(rows(15, "s"), req("run-stop"));
        assertThat(g.abortOutcome()).isEqualTo(CertSweepStore.OUTCOME_SHUTDOWN);
        assertThat(g.measured()).isLessThan(15);
        c.finish("run-stop", false, false);
        assertThat(store.finishes).containsExactly("run-stop:ABANDONED:SHUTDOWN:true");
        assertThat(store.chunksOf("run-stop")).as("kapanışta parça silinmez — sonraki koordinatör öksüz kapatır").isNotEmpty();
    }

    @Test
    @DisplayName("çağıranın erken dönüşü (kapanış / kilit, sonuç işlenmedi) → ABANDONED, sebep ölçücünün kapanış durumundan")
    void callerEarlyReturnFinishes() {
        Runner local = healthy(ME);
        Worker wb = new Worker(store, healthy(B), B);
        onTick = wb::drain;
        CertRunCoordinator c = coordinator(true, local, true, null);
        assertThat(c.gather(rows(10, "q"), req("run-q1")).aborted()).isFalse();
        c.finish("run-q1", false, false);
        assertThat(store.finishes).contains("run-q1:ABANDONED:LOCK_LOST:false");

        assertThat(c.gather(rows(10, "q"), req("run-q2")).aborted()).isFalse();
        local.closing = true;
        c.finish("run-q2", false, false);
        assertThat(store.finishes).contains("run-q2:ABANDONED:SHUTDOWN:true");
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Öksüz, son durumlar, kurulum hatası
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("açık eski koşu (ölmüş koordinatör) yeni koşu YAZILMADAN öksüz kapatılır, parçaları silinir, WARN")
    void orphanSettledBeforeCreate() {
        Worker wb = new Worker(store, healthy(B), B);
        CertRunCoordinator dead = coordinator(true, healthy(ME), true, null);
        // eski koşu: yazıldı, kimse bitirmedi (koordinatör öldü)
        CertRunSpec old = dead.buildSpec(rows(10, "o"), req("run-old"), "pod-x-0000000f", CertSweepFakes.tuning());
        store.createRun(old);
        store.calls.clear();

        onTick = wb::drain;
        CertRunCoordinator c = coordinator(true, healthy(ME), true, null);
        CertRunCoordinator.Gathered g = c.gather(rows(10, "n"), req("run-new"));
        assertThat(g.aborted()).isFalse();
        assertThat(store.run("run-old").state).isEqualTo(CertSweepStore.RUN_ABANDONED);
        assertThat(store.run("run-old").outcome).isEqualTo(CertSweepStore.OUTCOME_ORPHANED);
        assertThat(store.chunksOf("run-old")).isEmpty();
        assertThat(store.calls.indexOf("settleOrphans")).isLessThan(store.calls.indexOf("createRun"));
        assertThat(warns()).contains("Sahipsiz parçalı tarama kapatıldı (runId=run-old, koordinatör=pod-x) — sonuçları "
                + "kaydedilmemişti, bayat tarama telafi eder");
    }

    @Test
    @DisplayName("son durumlar: bitti → DONE/DECIDED, yakalandı → FAILED, bilinmeyen koşu kapanış ölçücüden")
    void finishStates() {
        Worker wb = new Worker(store, healthy(B), B);
        onTick = wb::drain;
        CertRunCoordinator c = coordinator(true, healthy(ME), true, null);
        c.gather(rows(10, "f"), req("run-f1"));
        c.finish("run-f1", true, true);
        assertThat(store.finishes).contains("run-f1:FAILED:FAILED:false");
        c.gather(rows(10, "f"), req("run-f2"));
        c.finish("run-f2", true, false);
        assertThat(store.finishes).contains("run-f2:DONE:DECIDED:false");
        c.finish(null, true, false);
        assertThat(store.finishes).hasSize(2);
    }

    @Test
    @DisplayName("koşu yazılamadı → null (klasik), WARN dakikada en çok bir; ikinci hata DEBUG")
    void creationFailureFallsBackToClassic() {
        store.failCreate = Store.dbDown();
        Runner local = healthy(ME);
        CertRunCoordinator c = coordinator(true, local, true, null);
        assertThat(c.gather(rows(10, "c"), req("run-c1"))).isNull();
        assertThat(c.gather(rows(10, "c"), req("run-c2"))).isNull();
        assertThat(warns()).containsExactly("Parçalı tarama başlatılamadı — tur klasik koşuyor: DataAccessResourceFailureException");
        clock.addAndGet(61_000L);
        assertThat(c.gather(rows(10, "c"), req("run-c3"))).isNull();
        assertThat(warns()).hasSize(2);
        assertThat(c.status().classicFallback()).isEqualTo(3);
        assertThat(c.status().classicByRule()).isZero();
        assertThat(local.checked).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Güvenlik ağları: depo hatası, ilerleme, işçisiz küme
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("depo 60 sn okunamaz → koordinatör toplanmamış her parçayı bellekteki listeden ölçer, sonuç STORE_FAILED (DONE)")
    void storeFailureGathersLocally() {
        Runner local = healthy(ME);
        Worker wb = new Worker(store, healthy(B), B);
        AtomicInteger step = new AtomicInteger();
        onTick = () -> {
            if (step.incrementAndGet() == 1) {
                wb.tick();                                                         // bir parça uzakta biter
            } else {
                store.failAll = Store.dbDown();                                    // sonra depo düşer
            }
        };
        List<Map<String, Object>> due = rows(15, "f");
        CertRunCoordinator c = coordinator(true, local, true, null);
        long start = clock.get();
        CertRunCoordinator.Gathered g = c.gather(due, req("run-store"));
        assertThat(g.aborted()).isFalse();
        assertThat(domainsOf(g.results())).isEqualTo(domainsOfRows(due));
        assertThat(local.checked).hasSize(10);
        assertThat(clock.get() - start).isBetween(CertChunking.STORE_FAILURE_LOCAL_MS, CertChunking.STORE_FAILURE_LOCAL_MS + 2_000L);
        assertThat(warns()).anyMatch(w -> w.startsWith("Parçalı tarama deposu 60 sn okunamadı"));
        store.failAll = null;
        c.finish("run-store", true, false);
        assertThat(store.finishes).contains("run-store:DONE:STORE_FAILED:false");
    }

    @Test
    @DisplayName("işçi parçayı tutar ama ilerleme yok (kira yenilenmiyor, dolmuyor) → tavan dolunca koordinatör iptal edip ölçer")
    void noProgressRule() {
        Runner local = healthy(ME);
        onTick = () -> store.claim(B, 600_000L, Long.MAX_VALUE);                   // al, ölçme, yenileme
        CertRunCoordinator c = coordinator(true, local, true, null);
        CertRunCoordinator.Request r = new CertRunCoordinator.Request("run-prog", "full", true, () -> lock, () -> stop,
                120_000L, () -> 3, () -> 0.5);
        long start = clock.get();
        CertRunCoordinator.Gathered g = c.gather(rows(10, "p"), r);
        assertThat(g.aborted()).isFalse();
        assertThat(local.checked).hasSize(10);
        assertThat(clock.get() - start).isBetween(120_000L, 125_000L);
        assertThat(warns()).anyMatch(w -> w.startsWith("Parçalı tarama 120 sn ilerlemesiz"));
    }

    @Test
    @DisplayName("hiçbir pod'da işçi yok: 60 sn içinde hiç sahiplenme → koordinatör hepsini ölçer (900 sn beklemez)")
    void noWorkerTakesAll() {
        Runner local = healthy(ME);
        CertRunCoordinator c = coordinator(true, local, true, null);
        long start = clock.get();
        List<Map<String, Object>> due = rows(12, "w");
        CertRunCoordinator.Gathered g = c.gather(due, req("run-nobody"));
        assertThat(domainsOf(g.results())).isEqualTo(domainsOfRows(due));
        assertThat(clock.get() - start).isBetween(CertRunCoordinator.NO_WORKER_MS, CertRunCoordinator.NO_WORKER_MS + 1_000L);
        assertThat(c.status().takeovers()).isEqualTo(3);
        assertThat(store.chunksOf("run-nobody")).allMatch(k -> CertSweepStore.CHUNK_CANCELLED.equals(k.state));
    }

    @Test
    @DisplayName("yerel işçi kancası koşu yazılınca bir kez dürtülür; kancanın hatası turu etkilemez")
    void localWorkerPoke() {
        Worker wb = new Worker(store, healthy(B), B);
        AtomicInteger pokes = new AtomicInteger();
        CertRunCoordinator c = coordinator(true, healthy(ME), true, null);
        c.setLocalWorkerPoke(() -> {
            pokes.incrementAndGet();
            wb.drain();
            throw new IllegalStateException("kanca bozuk");
        });
        CertRunCoordinator.Gathered g = c.gather(rows(10, "k"), req("run-poke"));
        assertThat(g.aborted()).isFalse();
        assertThat(pokes).hasValue(1);
        assertThat(ticks).as("işçi dürtüyle her şeyi bitirdi — hiç uyumadan toplandı").hasValue(0);
    }

    @Test
    @DisplayName("koordinatör sayaçları CertChunking'e kayıtlı (Sistem Sağlığı okur)")
    void statusRegistered() {
        CertChunking cc = CertSweepFakes.chunking(true, CertSweepFakes.tuning());
        Worker wb = new Worker(store, healthy(B), B);
        onTick = wb::drain;
        CertRunCoordinator c = new CertRunCoordinator(cc, store, healthy(ME), () -> true, null, () -> ME, clock::get, ms -> {
            clock.addAndGet(ms);
            onTick.run();
        });
        c.gather(rows(10, "t"), req("run-st"));
        assertThat(cc.coordinatorStatus()).isNotNull();
        assertThat(cc.coordinatorStatus().chunkedRuns()).isEqualTo(1);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // İnceleme düzeltmeleri (FX-COORD, 2026-10-29)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Koordinatör takılırsa testi sonsuz döngüde bırakmaz: sahte saat {@code limitMs}'i geçince durur. */
    void guard(long startMs, long limitMs) {
        Runnable prev = onTick;
        onTick = () -> {
            if (clock.get() - startMs > limitMs) throw new AssertionError("koordinatör takıldı (sahte saat " + limitMs + " ms aştı)");
            prev.run();
        };
    }

    /** Ölçücü sarmalayıcısı (sahte ölçücü {@code final}). */
    static CertChunkRunner wrap(Runner delegate, Runnable during) {
        return new CertChunkRunner() {
            @Override
            public List<Map<String, Object>> checkDomains(List<Map<String, Object>> rows, String runId) {
                during.run();
                return delegate.checkDomains(rows, runId);
            }

            @Override
            public boolean closing() {
                return delegate.closing();
            }

            @Override
            public long networkErrorCount(List<Map<String, Object>> results) {
                return delegate.networkErrorCount(results);
            }

            @Override
            public int executorCoreSize() {
                return delegate.executorCoreSize();
            }
        };
    }

    @Test
    @DisplayName("OB-L1: anahtar açık + tur KLASİK (tek pod / az alan adı) → açık eski koşular (RUNNING ve DECIDING, başka koordinatörün) yine öksüz kapatılır")
    void classicRunStillSettlesOrphans() {
        CertRunCoordinator dead = coordinator(true, healthy(ME), true, null);
        store.createRun(dead.buildSpec(rows(10, "o"), req("run-old"), "pod-x-0000000f", CertSweepFakes.tuning()));
        store.createRun(dead.buildSpec(rows(10, "p"), req("run-deciding"), "pod-y-0000000e", CertSweepFakes.tuning()));
        store.run("run-deciding").state = CertSweepStore.RUN_DECIDING;
        store.calls.clear();

        Runner local = healthy(ME);
        CertRunCoordinator single = coordinator(true, local, false, null);            // başka canlı pod yok → klasik
        assertThat(single.gather(rows(10, "n"), req("run-classic"))).isNull();
        assertThat(store.run("run-old").state + ":" + store.run("run-old").outcome).isEqualTo("ABANDONED:ORPHANED");
        assertThat(store.run("run-deciding").state + ":" + store.run("run-deciding").outcome).isEqualTo("ABANDONED:ORPHANED");
        assertThat(store.chunksOf("run-old")).isEmpty();
        assertThat(store.calls).as("yalnız öksüz kapatma — koşu yazılmaz, budanmaz").containsExactly("settleOrphans");
        assertThat(warns()).contains(
                "Sahipsiz parçalı tarama kapatıldı (runId=run-old, koordinatör=pod-x) — sonuçları kaydedilmemişti, bayat tarama telafi eder",
                "Sahipsiz parçalı tarama kapatıldı (runId=run-deciding, koordinatör=pod-y) — sonuçları kaydedilmemişti, bayat tarama telafi eder");
        assertThat(single.status().classicByRule()).isEqualTo(1);
        assertThat(single.status().classicFallback()).isZero();
        assertThat(local.checked).isEmpty();

        // az alan adı (min-domains altı) da kapatır; tablolar hazır değilken ya da kapanışta depoya dokunulmaz
        CertChunking cc = CertSweepFakes.chunking(true, new CertChunking.Tuning(true, 5, 50, 4, 0, 60_000L, 10_000L, 50, 250L, 5_000L));
        CertRunCoordinator few = new CertRunCoordinator(cc, store, local, () -> true, null, () -> ME, clock::get, ms -> { });
        store.createRun(dead.buildSpec(rows(10, "q"), req("run-old2"), "pod-x-0000000f", CertSweepFakes.tuning()));
        store.calls.clear();
        assertThat(few.gather(rows(10, "m"), req("run-few"))).isNull();
        assertThat(store.run("run-old2").state).isEqualTo(CertSweepStore.RUN_ABANDONED);
        store.calls.clear();
        store.tablesReady = false;
        assertThat(few.gather(rows(10, "m"), req("run-noready"))).isNull();
        store.tablesReady = true;
        stop = true;
        assertThat(few.gather(rows(10, "m"), req("run-stop"))).isNull();
        stop = false;
        assertThat(store.calls).as("tablolar hazır değil / kapanış → depo yok").isEmpty();

        // klasik turda öksüz kapatma hatası turu etkilemez (kural gereği klasik, WARN yok); parçalı olacak turda klasiğe düşer
        store.failSettle = Store.dbDown();
        int before = warns().size();
        assertThat(single.gather(rows(10, "n"), req("run-classic2"))).isNull();
        assertThat(single.status().classicByRule()).isEqualTo(2);
        assertThat(warns()).hasSize(before);
        CertRunCoordinator multi = coordinator(true, local, true, null);
        assertThat(multi.gather(rows(10, "n"), req("run-multi"))).isNull();
        assertThat(multi.status().classicFallback()).isEqualTo(1);
        assertThat(store.run("run-multi")).as("öksüzler kapatılamadan koşu yazılmaz").isNull();
        assertThat(warns()).contains("Parçalı tarama başlatılamadı — tur klasik koşuyor: DataAccessResourceFailureException");
    }

    @Test
    @DisplayName("CR-L2: biten parçaların sonuçları ≤ 20 parçalık okumalarla toplanır; sonucu 3 kez okunamayan parça (diğer okumalar başarılı) koordinatörde ölçülür")
    void doneChunkUnreadableIsTakenOver() {
        Runner local = healthy(ME);
        Worker wb = new Worker(store, healthy(B), B);
        AtomicInteger step = new AtomicInteger();
        onTick = () -> {
            if (step.incrementAndGet() == 1) {
                wb.drain();                                                        // 30 parçanın hepsi biter
                store.failReadFor.add(store.chunks.stream().filter(k -> k.no == 3).findFirst().orElseThrow().id);
            }
        };
        long start = clock.get();
        guard(start, 600_000L);
        List<Map<String, Object>> due = rows(150, "r");
        CertRunCoordinator c = coordinator(true, local, true, null);
        CertRunCoordinator.Gathered g = c.gather(due, req("run-read"));

        assertThat(g.aborted()).isFalse();
        assertThat(domainsOf(g.results())).isEqualTo(domainsOfRows(due));
        List<String> chunk3 = store.lastSpec.chunks().get(3).entries().stream().map(CertChunkSpec.Entry::domain).toList();
        assertThat(local.checked).as("yalnız okunamayan parçanın alan adları").containsExactlyInAnyOrderElementsOf(chunk3);
        assertThat(store.readBatches).as("okuma başına ≤ 20 parça").allMatch(k -> k <= CertRunCoordinator.RESULT_READ_BATCH)
                .contains(20);
        assertThat(clock.get() - start).as("depo-hatası ağı (60 sn) beklenmez").isLessThan(10_000L);
        assertThat(c.status().takeovers()).isEqualTo(1);
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(l -> l.equals("Parça 3 sonucu 3 kez okunamadı — koordinatör kendisi ölçüyor (runId=run-read)"));
    }

    @Test
    @DisplayName("CR-L2: kayıp parçanın iptali 3 kez yazılamaz (depo başka işlerde sağlam) → koordinatör yine de kendisi ölçer")
    void cancelFailingThreeTimesTakesOver() {
        Runner local = healthy(ME);
        Worker live = new Worker(store, healthy(B), B);
        AtomicInteger step = new AtomicInteger();
        onTick = () -> {
            int s = step.incrementAndGet();
            if (s == 1) {
                store.claim("dead-1", 60_000L, Long.MAX_VALUE);
                live.drain();
                store.failCancelFor.add(store.chunks.stream().filter(k -> k.no == 0).findFirst().orElseThrow().id);
            }
            if (s == 2) clock.addAndGet(61_000L);
            if (s == 3) store.claim("dead-2", 60_000L, Long.MAX_VALUE);
            if (s == 4) clock.addAndGet(61_000L);
        };
        guard(clock.get(), 1_200_000L);
        List<Map<String, Object>> due = rows(12, "x");
        CertRunCoordinator c = coordinator(true, local, true, null);
        CertRunCoordinator.Gathered g = c.gather(due, req("run-cancel"));

        assertThat(g.aborted()).isFalse();
        assertThat(domainsOf(g.results())).isEqualTo(domainsOfRows(due));
        List<String> chunk0 = store.lastSpec.chunks().get(0).entries().stream().map(CertChunkSpec.Entry::domain).toList();
        assertThat(local.checked).containsExactlyInAnyOrderElementsOf(chunk0);
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(l -> l.equals("Parça 0 iptal edilemedi (3 deneme) — koordinatör kendisi ölçüyor (runId=run-cancel)"));
    }

    @Test
    @DisplayName("CR-L3: tek denemeli parçanın kirası doldu, kimse yeniden almadı → lease-ms sonra koordinatör ölçer (900 sn beklemez)")
    void expiredSingleAttemptNotReclaimedIsTakenOver() {
        Runner local = healthy(ME);
        Worker live = new Worker(store, healthy(B), B);
        AtomicInteger step = new AtomicInteger();
        onTick = () -> {
            if (step.incrementAndGet() == 1) {
                store.claim("dead-1", 60_000L, Long.MAX_VALUE);                   // parça 0'ı alıp ölür (deneme 1)
                live.drain();                                                      // diğerleri biter; B bir daha almaz
            }
        };
        long start = clock.get();
        List<Map<String, Object>> due = rows(12, "e");
        CertRunCoordinator c = coordinator(true, local, true, null);
        CertRunCoordinator.Gathered g = c.gather(due, req("run-exp"));

        assertThat(g.aborted()).isFalse();
        assertThat(domainsOf(g.results())).isEqualTo(domainsOfRows(due));
        List<String> chunk0 = store.lastSpec.chunks().get(0).entries().stream().map(CertChunkSpec.Entry::domain).toList();
        assertThat(local.checked).containsExactlyInAnyOrderElementsOf(chunk0);
        assertThat(clock.get() - start).as("kira (60 sn) + lease-ms (60 sn) sonra, ilerleme ağından (900 sn) çok önce")
                .isBetween(120_000L, 125_000L);
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(l -> l.equals("Parça 0 kirası doldu, kimse yeniden almadı (1 deneme) — koordinatör kendisi ölçüyor (runId=run-exp)"));
    }

    @Test
    @DisplayName("CR-L4: yerel ölçüm 70 sn sürer → nabız ölçüm SIRASINDA atılır, başka pod bekleyen parçayı yine sahiplenir")
    void heartbeatKeptFreshDuringLocalMeasurement() {
        List<Runnable> beats = new ArrayList<>();
        AtomicInteger stops = new AtomicInteger();
        AtomicInteger beatsDuring = new AtomicInteger();
        Worker wb = new Worker(store, healthy(B), B);
        int[] claimedDuring = {0};
        Runner localRunner = healthy(ME);
        CertChunkRunner slow = wrap(localRunner, () -> {
            int hbBefore = (int) store.calls.stream().filter("heartbeat"::equals).count();
            for (int k = 0; k < 7; k++) {                                          // 70 sn süren yerel ölçüm
                clock.addAndGet(10_000L);
                new ArrayList<>(beats).forEach(Runnable::run);
            }
            beatsDuring.set((int) store.calls.stream().filter("heartbeat"::equals).count() - hbBefore);
            if (wb.tick()) claimedDuring[0]++;                                     // başka pod bu sırada parça 1'i alabiliyor mu?
        });
        AtomicInteger step = new AtomicInteger();
        onTick = () -> {
            int s = step.incrementAndGet();
            if (s == 1) store.claim("dead-1", 60_000L, Long.MAX_VALUE);
            if (s == 2) clock.addAndGet(61_000L);
            if (s == 3) store.claim("dead-2", 60_000L, Long.MAX_VALUE);
            if (s == 4) clock.addAndGet(61_000L);                                  // parça 0 KAYIP → yerel ölçüm (70 sn)
        };
        CertRunCoordinator c = new CertRunCoordinator(CertSweepFakes.chunking(true, CertSweepFakes.tuning()), store, slow,
                () -> true, null, () -> ME, clock::get, ms -> {
                    clock.addAndGet(ms);
                    onTick.run();
                });
        c.setBeatTimer((period, beat) -> {
            assertThat(period).isEqualTo(CertChunking.COORDINATOR_HEARTBEAT_MS);
            beats.add(beat);
            return () -> {
                beats.remove(beat);
                stops.incrementAndGet();
                return false;
            };
        });
        List<Map<String, Object>> due = rows(10, "y");
        CertRunCoordinator.Gathered g = c.gather(due, req("run-beat"));

        assertThat(g.aborted()).isFalse();
        assertThat(domainsOf(g.results())).isEqualTo(domainsOfRows(due));
        assertThat(beatsDuring).as("ölçüm sırasında 7 nabız").hasValue(7);
        assertThat(claimedDuring[0]).as("koşu sessiz görünmedi — işçi sahiplenmeyi sürdürdü").isEqualTo(1);
        assertThat(wb.completed).isEqualTo(1);
        assertThat(stops).as("nabız ölçüm bitince durdu").hasValue(1);
        assertThat(beats).isEmpty();
        List<String> chunk0 = store.lastSpec.chunks().get(0).entries().stream().map(CertChunkSpec.Entry::domain).toList();
        assertThat(localRunner.checked).containsExactlyInAnyOrderElementsOf(chunk0);
    }

    @Test
    @DisplayName("CR-L4: nabız ölçüm sırasında 0 satır bulursa (koşu başkasınca kapatıldı) → ölçüm bitince LOCK_LOST, sonuç dönmez")
    void heartbeatLostDuringLocalMeasurementAborts() {
        List<Runnable> beats = new ArrayList<>();
        CertChunkRunner slow = wrap(healthy(ME), () -> {
            clock.addAndGet(10_000L);
            store.run("run-beat-lost").state = CertSweepStore.RUN_ABANDONED;     // başka koordinatör öksüz saydı
            new ArrayList<>(beats).forEach(Runnable::run);
        });
        CertRunCoordinator c = new CertRunCoordinator(CertSweepFakes.chunking(true, CertSweepFakes.tuning()), store, slow,
                () -> true, null, () -> ME, clock::get, ms -> clock.addAndGet(ms));
        c.setBeatTimer((period, beat) -> {
            beats.add(beat);
            return () -> {
                beats.remove(beat);
                return false;
            };
        });
        CertRunCoordinator.Gathered g = c.gather(rows(10, "z"), req("run-beat-lost"));    // işçi yok → 60 sn sonra yerel
        assertThat(g.aborted()).isTrue();
        assertThat(g.abortOutcome()).isEqualTo(CertSweepStore.OUTCOME_LOCK_LOST);
        assertThat(g.results()).isEmpty();
    }

    @Test
    @DisplayName("CR-L4: üretim nabzı ayrı daemon iş parçacığında periyotla atar, durdurulunca iş parçacığı kalmaz")
    void productionBeatThreadStartsAndStops() throws Exception {
        AtomicInteger n = new AtomicInteger();
        CertRunCoordinator.Beat beat = CertRunCoordinator.THREAD_BEAT.start(20L, n::incrementAndGet);
        long deadline = System.currentTimeMillis() + 5_000L;
        while (n.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10L);
        assertThat(n.get()).isGreaterThanOrEqualTo(2);
        assertThat(Thread.getAllStackTraces().keySet()).extracting(Thread::getName).contains(CertRunCoordinator.HEARTBEAT_THREAD);
        assertThat(beat.stop()).isFalse();
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.isAlive()
                && CertRunCoordinator.HEARTBEAT_THREAD.equals(t.getName()));
        CertRunCoordinator.Beat throwing = CertRunCoordinator.THREAD_BEAT.start(5L, () -> {
            throw new IllegalStateException("nabız hatası");
        });
        Thread.sleep(40L);
        assertThat(throwing.stop()).as("nabzın hatası iş parçacığını öldürmez, durdurma temiz").isFalse();
    }

    @Test
    @DisplayName("SW-L2: anahtar tur sürerken KAPATILDI, bırakılan parçayı kimse almıyor → 30 sn sonra koordinatör ölçer; kirası yenilenen canlı parça alınmaz")
    void switchOffMidRunTakesOverStalledChunks() {
        AtomicBoolean on = new AtomicBoolean(true);
        Runner local = healthy(ME);
        Worker wb = new Worker(store, healthy(B), B);
        Worker wc = new Worker(store, healthy(C), C);
        AtomicInteger step = new AtomicInteger();
        ClaimedChunk[] held = new ClaimedChunk[1];
        onTick = () -> {
            int s = step.incrementAndGet();
            if (s == 1) {
                wb.tick();                                                         // parça 0 biter
                held[0] = store.claim(C, 60_000L, Long.MAX_VALUE).orElseThrow();   // parça 1: C uzun süre ölçer
                ClaimedChunk k = store.claim(B, 60_000L, Long.MAX_VALUE).orElseThrow();   // parça 2: B alır …
                store.release(B, List.of(k.id()));                                 // … ve kapanışta bırakır (deneme 0)
                on.set(false);                                                     // yönetici anahtarı kapatır
            } else if (s < 100) {
                store.renew(C, List.of(held[0].id()), 60_000L);                    // C canlı: kira yenilenir
            } else if (s == 100) {
                wc.run(held[0]);                                                   // C 50 sn sonra tamamlar
            }
        };
        long start = clock.get();
        guard(start, 900_000L);
        List<Map<String, Object>> due = rows(15, "w");
        CertRunCoordinator c = new CertRunCoordinator(CertSweepFakes.chunking(on::get, CertSweepFakes.tuning()), store, local,
                () -> true, null, () -> ME, clock::get, ms -> {
                    clock.addAndGet(ms);
                    onTick.run();
                });
        CertRunCoordinator.Gathered g = c.gather(due, req("run-off"));

        assertThat(g.aborted()).isFalse();
        assertThat(domainsOf(g.results())).isEqualTo(domainsOfRows(due));
        List<String> chunk2 = store.lastSpec.chunks().get(2).entries().stream().map(CertChunkSpec.Entry::domain).toList();
        assertThat(local.checked).as("yalnız bırakılmış parça; C'nin canlı parçası alınmadı").containsExactlyInAnyOrderElementsOf(chunk2);
        assertThat(clock.get() - start).as("900 sn ilerleme ağı beklenmedi").isLessThan(60_000L);
        assertThat(CertSweepStatus.decodeWorkers(store.run("run-off").workersJson))
                .extracting(CertSweepStatus.WorkerTally::worker).contains(B, C, ME);
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(l -> l.startsWith("Parçalı tarama anahtarı tur sürerken kapatıldı"));
    }

    @Test
    @DisplayName("sağlamlık: sonucun alan adı girdininkinden farklıysa kullanılmaz, koordinatör o alan adını yeniden ölçer")
    void resultForAnotherDomainIsRemeasured() {
        Runner local = healthy(ME);
        Runner rb = healthy(B);
        onTick = () -> {
            var claimed = store.claim(B, 60_000L, Long.MAX_VALUE);
            while (claimed.isPresent()) {
                ClaimedChunk k = claimed.get();
                List<Map<String, Object>> measured = rb.checkDomains(k.rows(), k.runId());
                List<ChunkCompletion.Result> res = new ArrayList<>();
                for (int i = 0; i < measured.size(); i++) {
                    int idx = k.entries().get(i).index();
                    Map<String, Object> m = measured.get(i);
                    if (idx == 1) m.put("domain", "baska.example.com");             // yanlış alan adına ait sonuç
                    res.add(new ChunkCompletion.Result(idx, m, false));
                }
                store.complete(new ChunkCompletion(k.id(), k.claimToken(), B, ChunkCompletion.encodeResults(res),
                        k.domainCount(), 0, 0, 0));
                claimed = store.claim(B, 60_000L, Long.MAX_VALUE);
            }
        };
        List<Map<String, Object>> due = rows(10, "v");
        CertRunCoordinator c = coordinator(true, local, true, null);
        CertRunCoordinator.Gathered g = c.gather(due, req("run-dom"));
        assertThat(g.aborted()).isFalse();
        assertThat(domainsOf(g.results())).isEqualTo(domainsOfRows(due));
        assertThat(local.checked).containsExactly("v001.example.com");
        assertThat(c.status().remeasured()).isEqualTo(1);
    }

    @Test
    @DisplayName("OB-L6: koordinatör sayaçları — alan adları classicByRule / classicFallback; eski 6'lı kurucu classicByRule = 0")
    void coordinatorStatusFields() {
        assertThat(java.util.Arrays.stream(CertSweepStatus.Coordinator.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).contains("classicByRule", "classicFallback");
        CertSweepStatus.Coordinator old = new CertSweepStatus.Coordinator(1, 2, 3, 4, 5, 6);
        assertThat(old.classicFallback()).isEqualTo(2);
        assertThat(old.classicByRule()).isZero();
        assertThat(new CertSweepStatus.Coordinator(1, 2, 3, 4, 5, 6, 7).classicByRule()).isEqualTo(7);
    }
}
