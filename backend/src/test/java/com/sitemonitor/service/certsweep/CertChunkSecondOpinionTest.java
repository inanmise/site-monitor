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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        // L-4: sonuç koşu satırında — hepsi başka pod'da başarılı
        assertThat(store.run("run-so").secondOpinionOk).isEqualTo(20);
        assertThat(store.run("run-so").secondOpinionFailed).isZero();
        // CR-L5: uyarı ikinci görüşten SONRA, sonucuyla — yeniden ölçümlerin çoğu başka pod'dan başarılı → P1 önerisi
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Sertifika taraması: pod-b pod'unun parçalarında ağ hatası oranı %100 (diğerleri %0) — 20 "
                        + "alan adı başka pod'dan yeniden ölçüldü: 20 başarılı, 0 yine hatalı; çıkış eşliğini (P1) doğrulayın");
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
        assertThat(store.run("run-genuine").secondOpinionOk).as("L-4").isEqualTo(14);
        assertThat(store.run("run-genuine").secondOpinionFailed).isEqualTo(6);
        // B'nin çıkışı gerçekten bozuk: yeniden ölçümlerin çoğu (14 / 20) başka pod'dan başarılı → P1 önerili WARN
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Sertifika taraması: pod-b pod'unun parçalarında ağ hatası oranı %100 (diğerleri %0) — 20 "
                        + "alan adı başka pod'dan yeniden ölçüldü: 14 başarılı, 6 yine hatalı; çıkış eşliğini (P1) doğrulayın");
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
        assertThat(store.run("run-none").secondOpinionOk).as("L-4: ikinci görüş yok → 0 / 0").isZero();
        assertThat(store.run("run-none").secondOpinionFailed).isZero();
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // İnceleme düzeltmeleri (FX-COORD, 2026-10-29): H-1, M-1, L-5
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    static final String A = ME;          // koordinatör (aşağıdaki senaryolarda bozuk çıkışlı)

    /** Toplu kesinti kuralının "şüpheli" kararı (SchedulerService ile aynı: ≥ min-errors ∧ oran ≥ eşik). */
    static boolean suspected(List<Map<String, Object>> results) {
        long ne = networkErrors(results);
        return ne >= 3 && (double) ne / results.size() >= 0.5;
    }

    /**
     * İnceleme sondası H-1 (kalıcı): koordinatör A aykırı (bozuk çıkış; kendi işçisi 20 parçanın 12'sini aldı → 60 / 100 ağ
     * hatası, şüpheli kesinti — klasik A'da da öyle karar verirdi). Tek yardımcı tarama lideri B: yalnız ≥ 10 sn bekleyen
     * parçaları, 4 sn'de bir TEK parça alır. Eskiden 30 sn'de yardımcı hâlâ çalışırken ikinci görüş kesiliyordu (5 parça ölçülmüş,
     * 7'si iptal → 35 / 100, şüphesiz → 35 sahte alarm).
     */
    final class LeaderLikeHelper {
        final Worker wa = new Worker(store, broken(A), A);
        final Runner rb = healthy(B, Set.of());
        final Worker wb = new Worker(store, rb, B);
        int aClaims;
        long lastB = Long.MIN_VALUE;
        int bSecondOpinion;
        int dieAfterSecondOpinion = Integer.MAX_VALUE;
        boolean bDead;
        final Set<Long> claimedSo = new HashSet<>();

        void install() {
            onTick = () -> {
                long now = clock.get();
                if (aClaims < 12 && wa.tick()) aClaims++;
                if (bDead) return;
                if (lastB == Long.MIN_VALUE || now - lastB >= 4_000L) {
                    Optional<ClaimedChunk> k = store.claim(B, 60_000L, now - 10_000L);
                    if (k.isPresent()) {
                        boolean so = store.chunksOf(k.get().runId()).stream()
                                .anyMatch(ch -> ch.id == k.get().id() && ch.avoid != null);
                        if (so && bSecondOpinion >= dieAfterSecondOpinion) {
                            bDead = true;                                    // B bu ikinci görüş parçasını tutarken ölür
                            return;
                        }
                        if (so) {
                            bSecondOpinion++;
                            claimedSo.add(k.get().id());
                        }
                        wb.run(k.get());
                        lastB = now;
                    }
                }
            };
        }
    }

    @Test
    @DisplayName("H-1: koordinatör aykırı, yardımcı lider gibi yavaş → 30 sn'de sahiplenilmiş ikinci görüş KESİLMEZ, hepsi ölçülür; oran TAMAMEN düzelir")
    void coordinatorOutlier_leaderLikeHelper_fullyCorrected() {
        Runner localA = broken(A);
        LeaderLikeHelper h = new LeaderLikeHelper();
        h.install();
        CertRunCoordinator c = coordinator(A, localA);
        CertRunCoordinator.Gathered g = c.gather(rows(100, "x"), req("run-x"));

        assertThat(g.aborted()).isFalse();
        List<Store.Chunk> so = store.chunksOf("run-x").stream().filter(k -> k.avoid != null).toList();
        assertThat(so).hasSize(12).allMatch(k -> CertSweepStore.CHUNK_DONE.equals(k.state) && B.equals(k.worker));
        assertThat(so).noneMatch(k -> CertSweepStore.CHUNK_CANCELLED.equals(k.state));
        assertThat(h.bSecondOpinion).isEqualTo(12);
        assertThat(networkErrors(g.results())).as("ikinci görüşün hepsi kullanıldı").isZero();
        assertThat(suspected(g.results())).isFalse();
        assertThat(localA.checked).as("koordinatör aykırı: kendisi yeniden ölçmez").isEmpty();
        assertThat(c.status().secondOpinions()).isEqualTo(60);
    }

    @Test
    @DisplayName("H-1 / M-1: koordinatör aykırı, yardımcı 3 ikinci görüş parçası ölçüp 4.'yü tutarken ölür → kısmi değişim (15 düzeltme → 45 / 100) 'şüpheli' kararını DEĞİŞTİRİRDİ → özgün sonuçların HEPSİ kalır")
    void coordinatorOutlier_helperDiesMidway_keepsAllOriginals() {
        // Yeniden temel (FX2-COORD M-1): eskiden 2 parça (10 düzeltme → 50 / 100) idi; M-1 kuralında 50 / 100 kararı DEĞİŞTİRMEZ
        // (≥ %50 hâlâ şüpheli) ve kısmi değişim uygulanır (ayrı test) — "karar değişirdi → hepsi kalır" yolu için 3 parça
        Runner localA = broken(A);
        LeaderLikeHelper h = new LeaderLikeHelper();
        h.dieAfterSecondOpinion = 3;
        h.install();
        CertRunCoordinator c = coordinator(A, localA);
        long start = clock.get();
        CertRunCoordinator.Gathered g = c.gather(rows(100, "y"), req("run-y"));

        assertThat(g.aborted()).isFalse();
        assertThat(h.bDead).isTrue();
        assertThat(h.bSecondOpinion).isEqualTo(3);
        // kısmi değişim 45 / 100 → şüphesiz olurdu (özgün: 60 / 100 şüpheli) → hepsi kalır, karar özgünle aynı
        assertThat(networkErrors(g.results())).isEqualTo(60);
        assertThat(suspected(g.results())).as("onRun 'şüpheli' kararı özgünle aynı").isTrue();
        assertThat(localA.checked).isEmpty();
        assertThat(clock.get() - start).as("ilerleme ağına (900 sn) kalmadan bırakıldı").isLessThan(300_000L);
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).as("doğrulanmamış şüphe için P1 uyarısı yok").isEmpty();
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(l -> l.startsWith("İkinci görüş eksik kaldı (60 alan adından 15 tanesi başka pod'dan yeniden ölçüldü) — "
                        + "kısmi değişim toplu kesinti kararını değiştirirdi (evet → hayır)"))
                .anyMatch(l -> l.startsWith("İkinci görüş tamamlanamadı: pod-a pod'unun 60 alan adından yeniden ölçülen 15"));
        // L-4: hiçbir yeniden ölçüm kullanılmadı → 0 / 0 (kart "doğrulanamadı" uyarısı)
        assertThat(store.run("run-y").secondOpinionOk).isZero();
        assertThat(store.run("run-y").secondOpinionFailed).isZero();
    }

    @Test
    @DisplayName("M-1: bir pod'un çözücüsü bozuk (çözülemeyen ad, karar oranına girmez) → o pod aykırı sayılır, alan adları başka pod'dan yeniden ölçülür")
    void unresolvableOnOnePodIsRemeasured() {
        Runner local = healthy(ME, Set.of());
        Worker wb = new Worker(store, new Runner(B, CertSweepFakes::unresolvable), B);
        Worker wc = new Worker(store, healthy(C, Set.of()), C);
        tickBoth(wb, wc);
        CertRunCoordinator c = coordinator(ME, local);
        CertRunCoordinator.Gathered g = c.gather(rows(40, "u"), req("run-dns"));

        assertThat(g.aborted()).isFalse();
        assertThat(g.results()).as("sahte 'Domain resolution failed' yok").noneMatch(m -> "error".equals(m.get("status")));
        assertThat(store.chunksOf("run-dns").stream().filter(k -> k.avoid != null).toList())
                .hasSize(4).allMatch(k -> B.equals(k.avoid) && C.equals(k.worker));
        assertThat(c.status().secondOpinions()).isEqualTo(20);
        assertThat(local.checked).isEmpty();
    }

    @Test
    @DisplayName("L-5: gerçek kısmi kesinti (bir yayın kuruluşu kesimi tek pod'un parçalarında) → yeniden ölçüm de düşer, P1 uyarısı YOK (INFO sonucu)")
    void genuineSegmentOutageNoEgressAdvice() {
        // B'nin parçaları 0, 2, 4, 6 (sıra: B, C); her B parçasının ilk 3 alan adı her yerden düşük
        Set<String> down = new HashSet<>();
        for (int chunk : new int[]{0, 2, 4, 6}) for (int k = 0; k < 3; k++) down.add(String.format("s%03d.example.com", chunk * 5 + k));
        Runner local = healthy(ME, down);
        Worker wb = new Worker(store, healthy(B, down), B);
        Worker wc = new Worker(store, healthy(C, down), C);
        tickBoth(wb, wc);
        CertRunCoordinator c = coordinator(ME, local);
        CertRunCoordinator.Gathered g = c.gather(rows(40, "s"), req("run-seg"));

        assertThat(g.aborted()).isFalse();
        assertThat(c.status().secondOpinions()).as("B aykırı (12 / 20 ≥ %50, C %0)").isEqualTo(12);
        assertThat(networkErrors(g.results())).as("gerçek hatalar korunur").isEqualTo(12);
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).isEmpty();
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage)).contains(
                "İkinci görüş: pod-b pod'unun 12 alan adı başka pod'dan yeniden ölçüldü: 0 başarılı, 12 yine hatalı — hatalar "
                        + "başka pod'dan da görülüyor (gerçek kesinti olası), sonuçlar geçerli (runId=run-seg)");
        // L-4: sonuç koşu satırında — Küme kartı P1 önermez (hatalar doğrulandı)
        assertThat(store.run("run-seg").secondOpinionOk).isZero();
        assertThat(store.run("run-seg").secondOpinionFailed).isEqualTo(12);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // İkinci düzeltme turu (FX2-COORD, 2026-10-29): M-1, L-1, L-5
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    static CertRunCoordinator.Request req(String runId, double threshold) {
        return new CertRunCoordinator.Request(runId, "hourly", true, () -> true, () -> false, 900_000L, () -> 3, () -> threshold);
    }

    /**
     * İnceleme sondası M-1 (kalıcı): koordinatör A aykırı (bozuk çıkış) ama turun yalnız 35 / 100 alan adını ölçtü (B ve C
     * temiz) — tüm özgünlerle tur ŞÜPHESİZ (%35). İkinci görüş: C ilk aşamadan sonra gitti, B 7 kendi ikinci görüş parçasından
     * 4'ünü ölçüp (20 alan adı, hepsi sağlıklı) 5.'yi tutarken ölür. Kısmi değişim (35 → 15) kararı DEĞİŞTİRMEZ → 20 yeni ölçüm
     * geçerli; eskiden hepsi-ya-da-hiçbiri 35 sahte "erişilemiyor" alarmı bırakıyordu.
     */
    @Test
    @DisplayName("M-1: kendi ikinci görüşü eksik ama kısmi değişim 'şüpheli' kararını değiştirmiyor (şüphesiz kalıyor) → tamamlanan 20 yeniden ölçüm geçerli: 35 → 15")
    void selfSecondOpinionIncomplete_sameDecision_partialApplied() {
        Runner localA = broken(A);
        Worker wa = new Worker(store, broken(A), A);
        Worker wb = new Worker(store, healthy(B, Set.of()), B);
        Worker wc = new Worker(store, healthy(C, Set.of()), C);
        int[] aClaims = {0};
        int[] soDone = {0};
        boolean[] bDead = {false};
        onTick = () -> {
            if (aClaims[0] < 7 && wa.tick()) {
                aClaims[0]++;
                return;
            }
            if (!store.calls.contains("addChunks")) {
                wb.tick();
                wc.tick();
                return;
            }
            if (bDead[0]) return;
            Optional<ClaimedChunk> k = store.claim(B, 60_000L, Long.MAX_VALUE);
            if (k.isEmpty()) return;
            if (soDone[0] >= 4) {
                bDead[0] = true;                                         // 5. ikinci görüş parçasını tutarken ölür
                return;
            }
            wb.run(k.get());
            soDone[0]++;
        };
        CertRunCoordinator c = coordinator(A, localA);
        CertRunCoordinator.Gathered g = c.gather(rows(100, "t"), req("run-t"));

        assertThat(g.aborted()).isFalse();
        assertThat(bDead[0]).isTrue();
        assertThat(soDone[0]).isEqualTo(4);
        assertThat(networkErrors(g.results())).as("20 iyi yeniden ölçüm uygulandı").isEqualTo(15);
        assertThat(suspected(g.results())).as("özgünle aynı karar: şüphesiz").isFalse();
        assertThat(localA.checked).as("koordinatör aykırı: kendisi yeniden ölçmez").isEmpty();
        assertThat(store.run("run-t").secondOpinions).isEqualTo(35);
        assertThat(store.run("run-t").secondOpinionOk).as("L-4").isEqualTo(20);
        assertThat(store.run("run-t").secondOpinionFailed).isZero();
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(l -> l.startsWith("İkinci görüş kısmen uygulandı: 35 alan adından 20 tanesi başka pod'dan yeniden ölçüldü"));
        for (int i = 0; i < 100; i++) assertThat(g.results().get(i).get("domain")).isEqualTo(String.format("t%03d.example.com", i));
    }

    @Test
    @DisplayName("M-1: koordinatör aykırı, yardımcı 2 ikinci görüş parçası ölçüp 3.'yü tutarken ölür → kısmi değişim (60 → 50 / 100) 'şüpheli' kararını değiştirmiyor → 10 düzeltme geçerli, karar özgünle aynı")
    void coordinatorOutlier_helperDiesMidway_sameDecision_partialApplied() {
        Runner localA = broken(A);
        LeaderLikeHelper h = new LeaderLikeHelper();
        h.dieAfterSecondOpinion = 2;
        h.install();
        CertRunCoordinator c = coordinator(A, localA);
        CertRunCoordinator.Gathered g = c.gather(rows(100, "z"), req("run-z"));

        assertThat(g.aborted()).isFalse();
        assertThat(h.bDead).isTrue();
        assertThat(networkErrors(g.results())).isEqualTo(50);
        assertThat(suspected(g.results())).as("50 / 100 hâlâ şüpheli — özgünle aynı karar").isTrue();
        assertThat(localA.checked).isEmpty();
        assertThat(store.run("run-z").secondOpinionOk).isEqualTo(10);
        assertThat(store.run("run-z").secondOpinionFailed).isZero();
    }

    /**
     * M-1 yukarı yönlü: koordinatör A'nın çözücüsü bozuk (çözülemeyen ad — karar oranına GİRMEZ, CR-M-1 yine de yeniden dağıtır).
     * Tüm özgünlerle tur şüphesiz (0 ağ hatası). B yeniden dağıtılanların 20'sini ölçer — ağ hatası döner — ve ölür. Kısmi
     * değişim (20 / 100, eşik %20) turu toplu kesinti EŞİĞİNİN ÜSTÜNE taşırdı → özgün sonuçların hepsi kalır.
     */
    @Test
    @DisplayName("M-1: kısmi değişim turu eşiğin ÜSTÜNE taşırdı (yeniden dağıtılan çözülemeyen adlar ağ hatasına döner) → özgün sonuçların hepsi kalır")
    void selfSecondOpinionIncomplete_upwardFlip_keepsAllOriginals() {
        Runner localA = new Runner(A, CertSweepFakes::unresolvable);
        Worker wa = new Worker(store, new Runner(A, CertSweepFakes::unresolvable), A);
        Worker wb = new Worker(store, new Runner(B, d -> store.calls.contains("addChunks") ? CertSweepFakes.netErr(d)
                : CertSweepFakes.ok(d)), B);
        Worker wc = new Worker(store, healthy(C, Set.of()), C);
        int[] aClaims = {0};
        int[] soDone = {0};
        boolean[] bDead = {false};
        onTick = () -> {
            if (aClaims[0] < 8 && wa.tick()) {
                aClaims[0]++;
                return;
            }
            if (!store.calls.contains("addChunks")) {
                wb.tick();
                wc.tick();
                return;
            }
            if (bDead[0]) return;
            Optional<ClaimedChunk> k = store.claim(B, 60_000L, Long.MAX_VALUE);
            if (k.isEmpty()) return;
            if (soDone[0] >= 4) {
                bDead[0] = true;
                return;
            }
            wb.run(k.get());
            soDone[0]++;
        };
        CertRunCoordinator c = coordinator(A, localA);
        CertRunCoordinator.Gathered g = c.gather(rows(100, "f"), req("run-f", 0.2));

        assertThat(g.aborted()).isFalse();
        assertThat(bDead[0]).isTrue();
        assertThat(soDone[0]).isEqualTo(4);
        assertThat(networkErrors(g.results())).as("ağ hatasına dönen 20 yeniden ölçüm uygulanmadı").isZero();
        assertThat(g.results()).filteredOn(m -> Boolean.TRUE.equals(m.get(CertRunCoordinator.UNRESOLVABLE_HOST))).hasSize(40);
        assertThat(store.run("run-f").secondOpinionOk).isZero();
        assertThat(store.run("run-f").secondOpinionFailed).isZero();
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .anyMatch(l -> l.contains("kısmi değişim toplu kesinti kararını değiştirirdi (hayır → evet)"));
    }

    /**
     * İnceleme sondası L-1 (kalıcı): koordinatör A aykırı; TEK yardımcı B bir kendi ikinci görüş parçasını bitirir, sonrakini
     * alır ve kapanırken BIRAKIR (PENDING, deneme − 1; ölçek küçültme / düğüm boşaltma) ve gider. Kalan parçalar beklemede:
     * kayıp değil, kira yok, sahiplenme görüldü → eskiden ilerleme ağına (900 sn, {@code cert-check} tutulurken) kadar
     * bekleniyordu. Şimdi: hiçbir kendi ikinci görüş parçasının durumu 30 sn değişmedi ve biri beklemede → bırakılır (M-1).
     */
    @Test
    @DisplayName("L-1: tek yardımcı kendi ikinci görüş parçasını kapanırken bırakıp gider → 30 sn durgunluktan sonra bırakılır (900 sn beklenmez); kısmi değişim kararı değiştirmiyor → uygulanır")
    void selfSecondOpinion_soleHelperReleasesGracefully_givenUpQuickly() {
        Runner localA = broken(A);
        Worker wa = new Worker(store, broken(A), A);
        Worker wb = new Worker(store, healthy(B, Set.of()), B);
        int[] aClaims = {0};
        int[] soDone = {0};
        boolean[] bGone = {false};
        long[] soPhaseStart = {Long.MIN_VALUE};
        onTick = () -> {
            if (aClaims[0] < 12 && wa.tick()) {
                aClaims[0]++;
                return;
            }
            if (bGone[0]) return;
            Optional<ClaimedChunk> k = store.claim(B, 60_000L, Long.MAX_VALUE);
            if (k.isEmpty()) return;
            boolean so = store.chunksOf(k.get().runId()).stream().anyMatch(ch -> ch.id == k.get().id() && ch.avoid != null);
            if (so && soPhaseStart[0] == Long.MIN_VALUE) soPhaseStart[0] = clock.get();
            if (so && soDone[0] >= 1) {
                store.release(B, List.of(k.get().id()));                 // tek yardımcının düzgün kapanışı
                bGone[0] = true;
                return;
            }
            wb.run(k.get());
            if (so) soDone[0]++;
        };
        CertRunCoordinator c = coordinator(A, localA);
        CertRunCoordinator.Gathered g = c.gather(rows(100, "r"), req("run-r"));

        assertThat(g.aborted()).isFalse();
        assertThat(bGone[0]).isTrue();
        assertThat(soDone[0]).isEqualTo(1);
        assertThat(clock.get() - soPhaseStart[0]).as("ikinci görüş aşaması 900 sn değil, ~30 sn").isLessThan(120_000L);
        // 60 / 100 → kısmi 55 / 100: ikisi de şüpheli → 5 yeni ölçüm geçerli (M-1)
        assertThat(networkErrors(g.results())).isEqualTo(55);
        assertThat(suspected(g.results())).isTrue();
        assertThat(localA.checked).isEmpty();
        assertThat(store.chunksOf("run-r").stream().filter(k -> k.avoid != null && CertSweepStore.CHUNK_PENDING.equals(k.state)))
                .as("bırakılan bekleyen parçalar iptal edildi").isEmpty();
    }

    /**
     * L-1 sınırı: işlenen kuyruk ASLA bırakılmaz — tek yardımcı (kapasitesi bir parça) her kendi ikinci görüş parçasını 45 sn
     * ölçer, kirasını 15 sn'de bir yeniler; diğer parçalar o sürede beklemede. Kira yenilemesi parmak izini değiştirir → durgunluk
     * yok → 12 parçanın hepsi ölçülür, oran tamamen düzelir.
     */
    @Test
    @DisplayName("L-1 sınırı: yavaş tek yardımcı kirasını yenileyerek çalışırken bekleyen parçalar varken bile ikinci görüş bırakılmaz")
    void selfSecondOpinion_slowHelperRenewingLease_notGivenUp() {
        Runner localA = broken(A);
        Worker wa = new Worker(store, broken(A), A);
        Worker wb = new Worker(store, healthy(B, Set.of()), B);
        int[] aClaims = {0};
        int[] soDone = {0};
        ClaimedChunk[] held = {null};
        long[] heldSince = {0L};
        long[] lastRenew = {0L};
        onTick = () -> {
            long now = clock.get();
            if (aClaims[0] < 12 && wa.tick()) {
                aClaims[0]++;
                return;
            }
            if (!store.calls.contains("addChunks")) {
                wb.tick();
                return;
            }
            if (held[0] == null) {
                Optional<ClaimedChunk> k = store.claim(B, 60_000L, Long.MAX_VALUE);
                if (k.isEmpty()) return;
                held[0] = k.get();
                heldSince[0] = now;
                lastRenew[0] = now;
            } else if (now - heldSince[0] >= 45_000L) {
                wb.run(held[0]);
                soDone[0]++;
                held[0] = null;
            } else if (now - lastRenew[0] >= 15_000L) {
                store.renew(B, List.of(held[0].id()), 60_000L);
                lastRenew[0] = now;
            }
        };
        CertRunCoordinator c = coordinator(A, localA);
        CertRunCoordinator.Gathered g = c.gather(rows(100, "v"), req("run-v"));

        assertThat(g.aborted()).isFalse();
        assertThat(soDone[0]).isEqualTo(12);
        assertThat(networkErrors(g.results())).as("ikinci görüşün hepsi kullanıldı").isZero();
        assertThat(store.chunksOf("run-v").stream().filter(k -> k.avoid != null))
                .hasSize(12).allMatch(k -> CertSweepStore.CHUNK_DONE.equals(k.state));
        assertThat(store.run("run-v").secondOpinionOk).isEqualTo(60);
    }

    /**
     * L-5: B (koordinatör değil) aykırı; ikinci görüşü sağlıklı C ölçer ama bir parçada ölçücüsü fırlatır → işçi o parçanın her
     * öğesini {@code failed} işaretiyle tamamlar. Eskiden bu 5 alan adı B'nin bozuk özgünüyle kalıyordu (kısmi değişim); şimdi
     * sağlıklı koordinatörde yeniden ölçülür → oran tamamen düzelir.
     */
    @Test
    @DisplayName("L-5: başka pod'un ikinci görüş parçasının sonucu kullanılamaz (ölçücü fırlattı) → bozuk özgün kalmaz, koordinatör yeniden ölçer")
    void nonSelfSecondOpinionUnusableResult_remeasuredLocally() {
        Runner local = healthy(ME, Set.of());
        Worker wb = new Worker(store, broken(B), B);
        Runner rc = healthy(C, Set.of());
        Worker wc = new Worker(store, rc, C);
        onTick = () -> {
            if (store.calls.contains("addChunks") && rc.throwDomain == null) {
                rc.throwFor = () -> new IllegalStateException("ölçücü hatası (test)");
                rc.throwDomain = "l000.example.com";                    // B'nin ilk parçası → C'nin ilk ikinci görüş parçası
            }
            wb.tick();
            wc.tick();
        };
        CertRunCoordinator c = coordinator(ME, local);
        CertRunCoordinator.Gathered g = c.gather(rows(40, "l"), req("run-l5"));

        assertThat(g.aborted()).isFalse();
        assertThat(wc.failedChunks).as("C'nin bir ikinci görüş parçası fırlattı").isEqualTo(1);
        assertThat(networkErrors(g.results())).as("kullanılamayan ikinci görüş öğeleri koordinatörde yeniden ölçüldü").isZero();
        assertThat(local.checked).containsExactly("l000.example.com", "l001.example.com", "l002.example.com",
                "l003.example.com", "l004.example.com");
        assertThat(c.status().remeasured()).isEqualTo(5);
        assertThat(store.run("run-l5").secondOpinionOk).isEqualTo(20);
        assertThat(store.run("run-l5").secondOpinionFailed).isZero();
        for (int i = 0; i < 40; i++) assertThat(g.results().get(i).get("domain")).isEqualTo(String.format("l%03d.example.com", i));
    }
}
