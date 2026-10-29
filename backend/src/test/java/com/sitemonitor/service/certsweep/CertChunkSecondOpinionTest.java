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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.sitemonitor.service.certsweep.CertSweepFakes.rows;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-4 paket B — aykırı pod / ikinci görüş (tasarım R3-4 §2.6, §9.2 B): kural doğruluk tablosu, aykırı pod'un ağ
 * hataları BAŞKA pod'dan (kaçınılan işçi) yeniden ölçülür, koordinatörün kendisi aykırıysa ve kimse almazsa eski sonuçlar
 * kalır, koordinatör aykırı değilse ve kimse almazsa kendisi ölçer, gerçek kısmi kesinti hatalarını korur, aykırı yoksa hiçbir
 * şey değişmez (L-3 birebir).
 */
class CertChunkSecondOpinionTest {

    static final String ME = "pod-a-0000000a";
    static final String B = "pod-b-0000000b";
    static final String C = "pod-c-0000000c";

    final AtomicLong clock = new AtomicLong(5_000_000L);
    final Store store = new Store(clock::get);
    volatile boolean cAlive = true;
    Runnable onTick = () -> { };

    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(CertRunCoordinator.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    CertRunCoordinator coordinator(String me, Runner local) {
        return new CertRunCoordinator(CertSweepFakes.chunking(true, CertSweepFakes.tuning()), store, local, () -> true, null,
                () -> me, clock::get, ms -> {
                    clock.addAndGet(ms);
                    onTick.run();
                });
    }

    static CertRunCoordinator.Request req(String runId) {
        return new CertRunCoordinator.Request(runId, "hourly", true, () -> true, () -> false, 900_000L, () -> 3, () -> 0.5);
    }

    /** Bozuk çıkışlı pod: her alan adı ağ hatası. */
    static Runner broken(String pod) {
        return new Runner(pod, CertSweepFakes::netErr);
    }

    /** Sağlıklı pod; {@code down} kümesindeki alan adları HER yerden düşük (gerçek kısmi kesinti). */
    static Runner healthy(String pod, Set<String> down) {
        return new Runner(pod, d -> down.contains(d) ? CertSweepFakes.netErr(d) : CertSweepFakes.ok(d));
    }

    static long networkErrors(List<Map<String, Object>> results) {
        return Runner.count(results);
    }

    /** B ve C sırayla birer parça alır; C yalnız {@link #cAlive} iken. */
    void tickBoth(Worker wb, Worker wc) {
        onTick = () -> {
            if (store.calls.contains("addChunks") && !keepCAfterDispatch) cAlive = false;
            wb.tick();
            if (cAlive) wc.tick();
        };
    }

    boolean keepCAfterDispatch = true;

    @Test
    @DisplayName("kural doğruluk tablosu: oran ≥ eşik ∧ ≥ min-errors ∧ diğerleri ≤ eşik/2 ∧ diğerlerinin ≥ 2 × min-errors sonucu")
    void outlierTruthTable() {
        // nw, ne_w, otherN, otherNe, minErrors, threshold
        assertThat(CertRunCoordinator.outlier(20, 20, 20, 0, 3, 0.5)).isTrue();
        assertThat(CertRunCoordinator.outlier(20, 10, 20, 5, 3, 0.5)).as("diğerleri tam eşik/2").isTrue();
        assertThat(CertRunCoordinator.outlier(20, 10, 20, 6, 3, 0.5)).as("diğerleri eşik/2 üstü").isFalse();
        assertThat(CertRunCoordinator.outlier(20, 9, 20, 0, 3, 0.5)).as("pod oranı eşik altı").isFalse();
        assertThat(CertRunCoordinator.outlier(4, 2, 20, 0, 3, 0.5)).as("min-errors altı").isFalse();
        assertThat(CertRunCoordinator.outlier(20, 20, 5, 0, 3, 0.5)).as("diğerlerinin sonucu < 2 × min-errors").isFalse();
        assertThat(CertRunCoordinator.outlier(20, 20, 0, 0, 3, 0.5)).as("tek işçi").isFalse();
        assertThat(CertRunCoordinator.outlier(0, 0, 20, 0, 3, 0.5)).isFalse();
        assertThat(CertRunCoordinator.outlier(20, 0, 20, 0, 0, 0.0)).as("hatasız pod asla aykırı değil").isFalse();
    }

    @Test
    @DisplayName("B aykırı → ağ hataları kaçınılan işçi = B ile yeniden dağıtılır, C ölçer, sonuç C'ninki; sayaç + WARN")
    void outlierRemeasuredOnAnotherPod() {
        Runner local = healthy(ME, Set.of());
        Worker wb = new Worker(store, broken(B), B);
        Worker wc = new Worker(store, healthy(C, Set.of()), C);
        tickBoth(wb, wc);
        CertRunCoordinator c = coordinator(ME, local);
        CertRunCoordinator.Gathered g = c.gather(rows(40, "o"), req("run-so"));

        assertThat(g.aborted()).isFalse();
        assertThat(networkErrors(g.results())).isZero();
        assertThat(local.checked).isEmpty();
        List<Store.Chunk> so = store.chunksOf("run-so").stream().filter(k -> k.avoid != null).toList();
        assertThat(so).hasSize(4).allMatch(k -> B.equals(k.avoid) && C.equals(k.worker));
        assertThat(c.status().secondOpinions()).isEqualTo(20);
        assertThat(store.run("run-so").secondOpinions).isEqualTo(20);
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Sertifika taraması: pod-b pod'unun parçalarında ağ hatası oranı %100 (diğerleri %0) — 20 "
                        + "alan adı başka pod'dan yeniden ölçüldü; çıkış eşliğini (P1) doğrulayın");
        // L-3: karar listesi klasik sırada
        for (int i = 0; i < 40; i++) assertThat(g.results().get(i).get("domain")).isEqualTo(String.format("o%03d.example.com", i));
    }

    @Test
    @DisplayName("koordinatörün kendisi aykırı ve kimse almıyor → 30 sn sonra eski sonuçlar kalır (kendi bozuk çıkışıyla ölçmez)")
    void coordinatorIsOutlierAndNobodyClaims() {
        Runner local = broken(B);
        Worker wb = new Worker(store, broken(B), B);
        Worker wc = new Worker(store, healthy(C, Set.of()), C);
        keepCAfterDispatch = false;
        tickBoth(wb, wc);
        CertRunCoordinator c = coordinator(B, local);
        long start = clock.get();
        CertRunCoordinator.Gathered g = c.gather(rows(40, "p"), req("run-self"));

        assertThat(g.aborted()).isFalse();
        assertThat(networkErrors(g.results())).isEqualTo(20);
        assertThat(local.checked).as("koordinatör = aykırı pod: yeniden ölçmez").isEmpty();
        assertThat(clock.get() - start).isGreaterThanOrEqualTo(CertChunking.SECOND_OPINION_WAIT_MS);
        assertThat(store.chunksOf("run-self").stream().filter(k -> k.avoid != null))
                .allMatch(k -> CertSweepStore.CHUNK_CANCELLED.equals(k.state));
    }

    @Test
    @DisplayName("koordinatör aykırı değil ve kimse almıyor → 30 sn sonra kendisi ölçer, sonuçlar onunki")
    void nobodyClaimsCoordinatorMeasures() {
        Runner local = healthy(ME, Set.of());
        Worker wb = new Worker(store, broken(B), B);
        Worker wc = new Worker(store, healthy(C, Set.of()), C);
        keepCAfterDispatch = false;
        tickBoth(wb, wc);
        CertRunCoordinator c = coordinator(ME, local);
        CertRunCoordinator.Gathered g = c.gather(rows(40, "q"), req("run-local"));

        assertThat(networkErrors(g.results())).isZero();
        assertThat(local.checked).hasSize(20);
        assertThat(c.status().takeovers()).isEqualTo(4);
    }

    @Test
    @DisplayName("gerçek kısmi kesinti: her yerden düşen alan adları ikinci görüşte de düşer → hataları korunur (yalnız bedel: yeniden ölçüm)")
    void genuinePartialOutageKeepsFailures() {
        Set<String> down = Set.of("g000.example.com", "g001.example.com", "g002.example.com", "g010.example.com",
                "g011.example.com", "g012.example.com");
        Runner local = healthy(ME, down);
        Worker wb = new Worker(store, broken(B), B);
        Worker wc = new Worker(store, healthy(C, down), C);
        tickBoth(wb, wc);
        CertRunCoordinator c = coordinator(ME, local);
        CertRunCoordinator.Gathered g = c.gather(rows(40, "g"), req("run-genuine"));

        assertThat(networkErrors(g.results())).as("yalnız gerçekten düşük olanlar").isEqualTo(down.size());
        assertThat(c.status().secondOpinions()).isEqualTo(20);
        assertThat(g.results()).filteredOn(m -> "error".equals(m.get("status")))
                .extracting(m -> (String) m.get("domain")).containsExactlyInAnyOrderElementsOf(down);
        assertThat(store.calls.stream().filter("addChunks"::equals)).as("tek tur ikinci görüş").hasSize(1);
    }

    @Test
    @DisplayName("aykırı yok (hatalar pod'lara dengeli) → ikinci görüş yok, depo addChunks çağrılmaz, sonuçlar aynen")
    void noOutlierNoChange() {
        Set<String> down = Set.of("n000.example.com", "n007.example.com", "n013.example.com", "n021.example.com");
        Runner local = healthy(ME, down);
        Worker wb = new Worker(store, healthy(B, down), B);
        Worker wc = new Worker(store, healthy(C, down), C);
        tickBoth(wb, wc);
        CertRunCoordinator c = coordinator(ME, local);
        CertRunCoordinator.Gathered g = c.gather(rows(40, "n"), req("run-none"));

        assertThat(store.calls).doesNotContain("addChunks");
        assertThat(networkErrors(g.results())).isEqualTo(4);
        assertThat(c.status().secondOpinions()).isZero();
        assertThat(store.run("run-none").secondOpinions).isZero();
    }
}
