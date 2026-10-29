package com.sitemonitor.service.checks;

import com.sitemonitor.config.ShutdownDeadline;
import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.it.MultiPodTestSupport.Pod;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.AppSettingsChangedEvent;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.evalstate.EvalLoadGate;
import com.sitemonitor.service.evalstate.EvalStateSink;
import com.sitemonitor.service.evalstate.HandoverResult;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-3 paket C — dağıtık kontrol sonuç boşaltıcısı ({@link CheckResultDrainer}; tasarım R3-3 §4.3 / §4.4 / §5.6).
 *
 * <p>GERÇEK {@code SchedulerService} pod'ları (ortak H2 + ortak saat, {@link MultiPodTestSupport}) ve pod başına GERÇEK
 * {@link MonitoringOutageService} (değerlendirme kapısı = 3-2 {@link EvalLoadGate}); iş kuyruğu (paket A) ve uygulayıcı
 * (paket B) sahte — kuyruk alma işlemini GERÇEK kira satırına bağlar (kira-bağlı {@code DELETE} gibi). Liderlik olayları
 * üretimdeki sırayla dağıtılır: önce MOS (3-2 kapısını kurar), sonra boşaltıcı.
 */
class CheckResultDrainerTest {

    static final DistributedChecks.Tuning FAST = new DistributedChecks.Tuning(true, 16, 1_000L, 5_000L, 200L, 50, 5_000L,
            600_000L);

    MultiPodTestSupport mp;
    Settings settings;
    DistributedChecks dc;
    FakeStore store;
    final DbClock dbClock = new DbClock() {
        @Override
        public long nowMs() {
            return mp.now();
        }

        @Override
        public boolean stale() {
            return false;
        }
    };
    final List<CheckResultDrainer> drainers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        settings = new Settings();
        settings.enabled = true;
        dc = new DistributedChecks(settings.svc, FAST);
        store = new FakeStore(mp);
    }

    @AfterEach
    void tearDown() {
        for (CheckResultDrainer d : drainers) {
            d.destroy();
            Thread t = (Thread) ReflectionTestUtils.getField(d, "thread");
            if (t != null) await().atMost(Duration.ofSeconds(3)).until(() -> !t.isAlive());   // sonraki sınıflara sızmasın
        }
        mp.close();
    }

    // ── Düzenek ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Değerini testin değiştirdiği ayar servisi (yalnız bellek). */
    static final class Settings {
        volatile boolean enabled;
        final AppSettingsService svc = mock(AppSettingsService.class);

        Settings() {
            when(svc.getBoolean(anyString(), anyBoolean())).thenAnswer(i ->
                    DistributedChecks.KEY_ENABLED.equals(i.getArgument(0)) ? enabled : i.getArgument(1));
            when(svc.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        }
    }

    /** Ortak "veritabanı kuyruğu": DONE satırları; alma kira satırına bağlı, RETURNING sırası bilerek ters. */
    static final class FakeStore implements CheckJobStore {
        final MultiPodTestSupport mp;
        final TreeMap<Long, CheckResultRow> done = new TreeMap<>();
        final List<String> takeOwners = new CopyOnWriteArrayList<>();
        volatile Runnable onTake = () -> { };
        volatile RuntimeException failNext;
        /** FX-EVAL: her alma fırlatır (null = hayır). */
        volatile RuntimeException failAlways;
        final AtomicInteger failOnCall = new AtomicInteger(-1);

        FakeStore(MultiPodTestSupport mp) {
            this.mp = mp;
        }

        synchronized void add(CheckResultRow... rows) {
            for (CheckResultRow r : rows) done.put(r.id(), r);
        }

        synchronized int remaining() {
            return done.size();
        }

        int takes() {
            return takeOwners.size();
        }

        @Override
        public synchronized List<CheckResultRow> take(int limit, String leaseOwner) {
            takeOwners.add(leaseOwner);
            onTake.run();
            if (failOnCall.get() == takeOwners.size()) throw new DataAccessResourceFailureException("db gone");
            if (failAlways != null) throw failAlways;
            RuntimeException f = failNext;
            if (f != null) {
                failNext = null;
                throw f;
            }
            // Kira-bağlı DELETE (… AND EXISTS lease:sweep-leader claimed_by = ?): kirayı tutmayan hiçbir şey almaz.
            if (leaseOwner == null || !leaseOwner.equals(mp.leaseOwner("sweep-leader"))) return List.of();
            List<CheckResultRow> out = new ArrayList<>();
            Iterator<CheckResultRow> it = done.values().iterator();
            while (it.hasNext() && out.size() < limit) {
                out.add(it.next());
                it.remove();
            }
            Collections.reverse(out);   // RETURNING sırası garanti değil — boşaltıcı sıralar
            return out;
        }

        @Override
        public int dispatch(String type, Collection<Long> monitorIds, long leaseMs, String dispatcher) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CheckJobRow> claim(Collection<String> types, int limit, String owner, long createdBeforeMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean[] complete(List<Completion> completions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int release(String owner, Collection<Long> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CheckJobRow> reap(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public QueueStats stats() {
            throw new UnsupportedOperationException();
        }
    }

    /** Dalgaları kaydeden uygulayıcı (kanca dalga kaydedilmeden önce koşar; fırlatırsa dalga kaydedilmez). */
    static final class FakeApplier implements CheckResultApplier {
        final List<List<Long>> waves = new CopyOnWriteArrayList<>();
        volatile Consumer<List<CheckResultRow>> hook = w -> { };

        @Override
        public void apply(List<CheckResultRow> wave) {
            hook.accept(wave);
            waves.add(wave.stream().map(CheckResultRow::id).toList());
        }

        List<Long> appliedIds() {
            List<Long> out = new ArrayList<>();
            for (List<Long> w : waves) out.addAll(w);
            return out;
        }
    }

    record Rig(Pod pod, MonitoringOutageService mos, CheckResultDrainer drainer, FakeApplier applier) {
        EvalLoadGate gate() {
            return (EvalLoadGate) ReflectionTestUtils.getField(mos, "evalGate");
        }
    }

    MonitoringOutageService newMos() {
        MonitoringOutageService s = new MonitoringOutageService(mp.mock(AlertEventRepository.class),
                mp.mock(EscalationService.class), mp.jdbc(), mp.mock(DnsRecordRepository.class),
                mp.mock(DnsMonitorRepository.class), mp.mock(AppSettingsService.class),
                mp.mock(NetworkOutageEventRepository.class));
        ReflectionTestUtils.setField(s, "nowMs", mp.clockSupplier());
        return s;
    }

    /** n. pod + kendi MOS'u + boşaltıcı; liderlik olayları üretimdeki sırayla (MOS → boşaltıcı). */
    Rig rig(int n, boolean wireDrainerEvents) {
        MonitoringOutageService m = newMos();
        mp.use(MonitoringOutageService.class, m);
        Pod p = mp.pod(n);
        FakeApplier ap = new FakeApplier();
        CheckResultDrainer d = new CheckResultDrainer(dc, p::scheduler, () -> m, () -> store, () -> ap, p::schedule,
                () -> dbClock);
        d.setLeaderClock(mp.clockSupplier());
        drainers.add(d);
        p.events().listen(SweepLeadershipChangedEvent.class, m::onSweepLeadershipChanged);
        if (wireDrainerEvents) p.events().listen(SweepLeadershipChangedEvent.class, d::onSweepLeadershipChanged);
        return new Rig(p, m, d, ap);
    }

    Rig leader() {
        Rig a = rig(1, true);
        assertThat(a.pod().scheduler().isSweepLeaderNow()).as("A lider (GAINED)").isTrue();
        return a;
    }

    CheckResultRow row(long id, String type, long monitorId) {
        return row(id, type, monitorId, mp.now() - 1_000L);
    }

    CheckResultRow row(long id, String type, long monitorId, Long finishedMs) {
        long created = (finishedMs == null ? mp.now() : finishedMs) - 2_000L;
        return new CheckResultRow(id, type, monitorId, created, created + 100L, finishedMs, "pod-x", "{\"open\":true}",
                false);
    }

    static void markStarted(CheckResultDrainer d) {
        ReflectionTestUtils.setField(d, "started", true);
    }

    static SweepLeadershipChangedEvent event(String reason, long at) {
        return new SweepLeadershipChangedEvent(SweepLeadershipChangedEvent.GAINED.equals(reason), "x", at, reason);
    }

    // ── Dalgalar ve sıra ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("dalgalar: izleme başına dalga başına ≤ 1 sonuç; aynı izlemenin k. sonucu k. dalgada; sıra kimlik sırası")
    void wavesSplitPerMonitor() {
        List<CheckResultRow> rows = List.of(row(1, "port", 1), row(2, "port", 2), row(3, "port", 1), row(4, "http", 1),
                row(5, "port", 1), row(6, "http", 1));
        List<List<Long>> ids = CheckResultDrainer.waves(rows).stream()
                .map(w -> w.stream().map(CheckResultRow::id).toList()).toList();
        assertThat(ids).containsExactly(List.of(1L, 2L, 4L), List.of(3L, 6L), List.of(5L));
        assertThat(CheckResultDrainer.waves(List.of())).isEmpty();
    }

    @Test
    @DisplayName("alma sırası karışık gelse de kimlik sırasıyla dalga dalga uygulanır (aynı izleme bir dalgada bir kez)")
    void idOrderAndWavesThroughTheDrain() {
        Rig a = leader();
        store.add(row(5, "port", 1), row(1, "port", 1), row(3, "port", 1), row(2, "port", 2), row(4, "http", 1),
                row(6, "http", 1));
        assertThat(a.drainer().drainOnce()).isFalse();
        assertThat(a.applier().waves).containsExactly(List.of(1L, 2L, 4L), List.of(3L, 6L), List.of(5L));
        assertThat(store.remaining()).isZero();
        CheckResultDrainer.Status st = a.drainer().status();
        assertThat(st.taken()).isEqualTo(6);
        assertThat(st.waves()).isEqualTo(3);
        assertThat(st.applied()).isEqualTo(6);
        assertThat(st.outOfOrder()).isZero();
    }

    @Test
    @DisplayName("savunma süzgeci: son uygulanandan küçük kimlik atılır (sıra dışı sayılır); liderlik olayı süzgeci sıfırlar")
    void outOfOrderFilterResetOnLeadershipEvent() {
        Rig a = leader();
        store.add(row(10, "port", 1));
        a.drainer().drainOnce();
        store.add(row(7, "port", 1), row(12, "port", 1), row(8, "port", 2));
        a.drainer().drainOnce();
        assertThat(a.applier().appliedIds()).containsExactly(10L, 8L, 12L);
        assertThat(a.drainer().status().outOfOrder()).isEqualTo(1);

        // LOST + GAINED (aynı pod geri aldı): bellek sıfırlandı → süzgeç de sıfır; eski izlemenin yeni (küçük) kimliği uygulanır
        a.drainer().onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.LOST, mp.now()));
        a.drainer().onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.GAINED, mp.now()));
        store.add(row(9, "port", 1));
        a.drainer().drainOnce();
        assertThat(a.applier().appliedIds()).endsWith(9L);
        assertThat(a.drainer().status().outOfOrder()).isEqualTo(1);
    }

    @Test
    @DisplayName("bayat sonuç (bitişten > result-stale-ms) değerlendirilmeden atılır ve sayılır; bitiş yoksa oluşturmadan")
    void staleRowsAreDropped() {
        Rig a = leader();
        long stale = FAST.resultStaleMs();
        store.add(row(1, "port", 1, mp.now() - stale - 1), row(2, "port", 2, mp.now() - stale),
                row(3, "ping", 3, null));
        // bitişsiz satırın oluşturma anı (now − 2 sn) — taze; bitişsiz ve ESKİ oluşturma → bayat
        store.add(new CheckResultRow(4, "dns", 4, mp.now() - stale - 5_000L, null, null, "pod-x", null, false));
        a.drainer().drainOnce();
        assertThat(a.applier().appliedIds()).containsExactly(2L, 3L);
        assertThat(a.drainer().status().droppedStale()).isEqualTo(2);
    }

    // ── 3-2 yükleme kapısı ve GAINED ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("3-2 yükleme sürerken (evalLoadToken > 0) bilet alınmaz, satır alınmaz; yükleme bitince alır")
    void waitsForTheLoadGate() {
        Rig a = leader();
        store.add(row(1, "port", 1));
        a.gate().arm(7L, mp.now() + EvalLoadGate.LOAD_TIMEOUT_MS);
        assertThat(a.mos().evalLoadToken()).isEqualTo(7L);
        assertThat(a.drainer().drainOnce()).isFalse();
        assertThat(store.takes()).as("yükleme sürerken alma yok").isZero();
        assertThat(a.gate().inFlight()).as("bilet de alınmadı (araya sızma yok)").isZero();
        assertThat(a.drainer().status().loadWaits()).isEqualTo(1);

        a.mos().evalLoadSkipped(7L, null);
        assertThat(a.mos().evalLoadToken()).isZero();
        a.drainer().drainOnce();
        assertThat(a.applier().appliedIds()).containsExactly(1L);
    }

    @Test
    @DisplayName("lider ipucu açık ama bu liderliğin GAINED olayı henüz görülmediyse alınmaz (3-2 kapısı GAINED dinleyicisinde kurulur)")
    void waitsForTheGainedEvent() {
        Rig a = rig(1, false);   // boşaltıcı olayları kaçırdı
        assertThat(a.pod().scheduler().isSweepLeaderNow()).isTrue();
        assertThat(a.pod().scheduler().sweepLeaderHint()).isTrue();
        store.add(row(1, "port", 1));
        a.drainer().drainOnce();
        assertThat(store.takes()).isZero();

        a.drainer().onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.GAINED, mp.now()));
        a.drainer().drainOnce();
        assertThat(a.applier().appliedIds()).containsExactly(1L);
    }

    // ── Bilet ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bilet YALNIZ alma + uygulama boyunca açık: her partide tek bilet, partiler / adımlar arasında kapı durgun")
    void ticketCoversTakeAndApplyOnly() {
        Rig a = leader();
        for (long id = 1; id <= 150; id++) store.add(row(id, "port", id));
        List<Integer> inTake = new CopyOnWriteArrayList<>();
        List<Integer> inApply = new CopyOnWriteArrayList<>();
        List<Boolean> quietInTake = new CopyOnWriteArrayList<>();
        store.onTake = () -> {
            inTake.add(a.gate().inFlight());
            quietInTake.add(a.mos().awaitEvalQuiescent(0));
        };
        a.applier().hook = w -> inApply.add(a.gate().inFlight());
        a.drainer().drainOnce();
        assertThat(inTake).as("iki parti, her birinde tek bilet (ilki kapandı)").containsExactly(1, 1);
        assertThat(inApply).allMatch(n -> n == 1);
        assertThat(quietInTake).containsOnly(false);
        assertThat(a.gate().inFlight()).isZero();
        assertThat(a.mos().awaitEvalQuiescent(0)).as("adımlar arasında durgun").isTrue();
        assertThat(a.applier().appliedIds()).hasSize(150);
    }

    @Test
    @DisplayName("her parti kapıya YENİDEN girer: ilk partide devir (HANDOVER) başlarsa ikinci parti bekler — bilet partiler arasında tutulmaz")
    void eachBatchEntersTheGateAnew() throws Exception {
        Rig a = leader();
        for (long id = 1; id <= 150; id++) store.add(row(id, "port", id));
        AtomicBoolean first = new AtomicBoolean(true);
        List<Long> takeAtNanos = new CopyOnWriteArrayList<>();
        AtomicReference<Long> handoverAt = new AtomicReference<>();
        store.onTake = () -> takeAtNanos.add(System.nanoTime());
        a.applier().hook = w -> {
            if (first.compareAndSet(true, false)) {
                a.mos().beginEvalHandover();   // yeni girişler en çok HANDOVER_WAIT_MS bekler; yeniden giriş beklemez
                handoverAt.set(System.nanoTime());
                Thread end = new Thread(() -> {
                    try {
                        Thread.sleep(400L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    a.mos().endEvalHandover();
                });
                end.setDaemon(true);
                end.start();
            }
        };
        a.drainer().drainOnce();
        assertThat(takeAtNanos).hasSize(2);
        assertThat(TimeUnit.NANOSECONDS.toMillis(takeAtNanos.get(1) - handoverAt.get()))
                .as("ikinci parti yeni bilet aldı → devrin bitişini bekledi").isGreaterThanOrEqualTo(300L);
        assertThat(a.applier().appliedIds()).hasSize(150);
        assertThat(a.gate().inFlight()).isZero();
    }

    @Test
    @DisplayName("alma / uygulama hatası: bilet yine kapanır, sayılır, sonraki adım sürer (satırlar kuyrukta kalır)")
    void failuresCloseTheTicket() {
        Rig a = leader();
        store.add(row(1, "port", 1), row(2, "port", 2));
        store.failNext = new DataAccessResourceFailureException("db gone");
        a.drainer().drainOnce();
        assertThat(a.gate().inFlight()).isZero();
        assertThat(a.drainer().status().takeFailures()).isEqualTo(1);
        assertThat(a.drainer().status().lastFailure()).isEqualTo("DataAccessResourceFailureException");
        assertThat(store.remaining()).isEqualTo(2);

        AtomicBoolean thrown = new AtomicBoolean();
        a.applier().hook = w -> {
            if (thrown.compareAndSet(false, true)) throw new IllegalStateException("boom");
        };
        a.drainer().drainOnce();
        assertThat(a.gate().inFlight()).isZero();
        assertThat(a.drainer().status().applyFailures()).isEqualTo(1);
        store.add(row(3, "port", 1));
        a.drainer().drainOnce();
        assertThat(a.applier().appliedIds()).containsExactly(3L);
    }

    // ── Elle gelen kutusu kapısı ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("holdManual: GAINED sonrası birikim boşalana dek true (ilk geçişin kimliği filigran), alma < 100 → yetişti → false")
    void holdManualUntilCaughtUp() {
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        assertThat(d.holdManual()).as("iş parçacığı kurulmadı (anahtar bu açılışta açık görülmedi)").isFalse();
        markStarted(d);
        assertThat(d.holdManual()).as("GAINED görüldü, birikim boşaltılmadı").isTrue();

        for (long id = 1; id <= 150; id++) store.add(row(id, "port", id));
        store.failOnCall.set(2);   // ikinci parti düşer → henüz yetişmedi
        d.drainOnce();
        assertThat(d.status().watermarkId()).as("ilk geçişin en büyük kimliği").isEqualTo(100L);
        assertThat(d.status().caughtUp()).isFalse();
        assertThat(d.holdManual()).isTrue();

        d.drainOnce();   // kalan 50 → 100'den az → yetişti
        assertThat(store.remaining()).isZero();
        assertThat(d.status().caughtUp()).isTrue();
        assertThat(d.holdManual()).isFalse();
        assertThat(d.status().manualHolds()).isEqualTo(2);
    }

    @Test
    @DisplayName("holdManual tavanı: liderlik başlangıcından 20 sn sonra boşaltılamasa da false; anahtar kapalı → false")
    void holdManualCapAndSwitch() {
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        markStarted(d);
        assertThat(d.holdManual()).isTrue();
        mp.advance(CheckResultDrainer.HOLD_MANUAL_MAX_MS - 1);
        assertThat(d.holdManual()).isTrue();
        mp.advance(1);
        assertThat(d.holdManual()).as("20 sn tavanı").isFalse();

        // Yeni liderlik: tavan yeniden başlar; anahtar kapanınca bekletme yok
        d.onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.LOST, mp.now()));
        assertThat(d.holdManual()).as("lider değil").isFalse();
        settings.enabled = false;
        d.onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.GAINED, mp.now()));
        assertThat(d.holdManual()).as("anahtar kapalı").isFalse();
    }

    // ── Kira-bağlı alma ve liderlik değişimi ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("kira-bağlı alma: yalnız kira sahibi kendi sahip kimliğiyle alır; kira B'ye geçince A almaz, B alır")
    void leaseBoundTake() {
        Rig a = leader();
        Rig b = rig(2, true);
        assertThat(b.pod().scheduler().isSweepLeaderNow()).isFalse();
        store.add(row(1, "port", 1));
        b.drainer().drainOnce();
        assertThat(store.takes()).as("takipçi hiç almaz (lider ipucu false)").isZero();
        a.drainer().drainOnce();
        assertThat(a.applier().appliedIds()).containsExactly(1L);
        assertThat(store.takeOwners).containsOnly(MultiPodTestSupport.owner(1));

        mp.advance(181_000L);   // A'nın kirası doldu (TTL 180 sn)
        assertThat(b.pod().scheduler().isSweepLeaderNow()).as("B devraldı").isTrue();
        store.add(row(2, "port", 1));
        int before = store.takes();
        a.drainer().drainOnce();   // ipucu hâlâ true, ama bilet öncesi kira tazelenir → LOST → alma yok
        assertThat(store.takes()).as("kirayı kaybeden A almaz").isEqualTo(before);
        assertThat(a.pod().scheduler().sweepLeaderHint()).isFalse();
        b.drainer().drainOnce();
        assertThat(b.applier().appliedIds()).containsExactly(2L);
        assertThat(store.takeOwners.subList(before, store.takes())).containsOnly(MultiPodTestSupport.owner(2));

        // Yanlış (eski) sahip kimliğiyle alma veritabanında 0 satır döner — boşaltıcı yalnız kendi kira kimliğini verir
        assertThat(store.take(10, MultiPodTestSupport.owner(1))).isEmpty();
    }

    @Test
    @DisplayName("liderlik olayı durumu sıfırlar: LOST → yetişti / bekletme yok; GAINED → yeniden birikim boşaltılır")
    void leadershipEventResetsState() {
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        markStarted(d);
        d.drainOnce();   // boş kuyruk → yetişti
        assertThat(d.holdManual()).isFalse();
        d.onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.LOST, mp.now()));
        assertThat(d.status().caughtUp()).isTrue();
        assertThat(d.status().watermarkId()).isZero();
        d.onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.GAINED, mp.now()));
        assertThat(d.status().caughtUp()).isFalse();
        assertThat(d.holdManual()).isTrue();
    }

    // ── Kapalı anahtar (K-1), başlatma, kapanış ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("K-1: anahtar açılıştan beri KAPALI → iş parçacığı yok, alma yok, holdManual false, @PreDestroy ortak süreye dokunmaz")
    void switchOffSinceBoot() {
        settings.enabled = false;
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        d.registerStarter();
        dc.onReady(null);
        dc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));
        assertThat(d.started()).isFalse();
        assertThat(ReflectionTestUtils.getField(d, "thread")).as("iş parçacığı yok").isNull();
        store.add(row(1, "port", 1));
        assertThat(d.drainOnce()).isFalse();
        assertThat(store.takes()).isZero();
        assertThat(d.holdManual()).isFalse();

        ShutdownDeadline deadline = new ShutdownDeadline();
        ShutdownDeadline.activate(deadline);
        try {
            long t0 = System.nanoTime();
            d.destroy();
            // hiç başlamadıysa ortak süre bu çağrıyla başlar → tam bütçe (boşaltıcı ortak süreyi başlatmadı)
            assertThat(deadline.remainingMs(ShutdownDeadline.DESTROY_BUDGET_MS, t0 + TimeUnit.SECONDS.toNanos(5)))
                    .isEqualTo(ShutdownDeadline.DESTROY_BUDGET_MS);
        } finally {
            ShutdownDeadline.activate(null);
        }
    }

    @Test
    @DisplayName("anahtar sonradan açılınca iş parçacığı bir kez kurulur ve boşaltır; sonra kapanınca alma durur")
    void lateActivationStartsTheThreadOnce() {
        settings.enabled = false;
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        d.registerStarter();
        dc.onReady(null);
        assertThat(d.started()).isFalse();
        settings.enabled = true;
        dc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(DistributedChecks.KEY_ENABLED), "save"));
        assertThat(d.started()).isTrue();
        Thread t = (Thread) ReflectionTestUtils.getField(d, "thread");
        assertThat(t).isNotNull();
        assertThat(t.getName()).isEqualTo(CheckResultDrainer.THREAD_NAME);
        assertThat(t.isAlive()).isTrue();
        d.start();   // ikinci kez kurulmaz
        assertThat(ReflectionTestUtils.getField(d, "thread")).isSameAs(t);

        store.add(row(1, "port", 1));
        await().atMost(Duration.ofSeconds(5)).until(() -> a.applier().appliedIds().contains(1L));

        settings.enabled = false;   // döngü bellek okumasıyla döner
        int before = store.takes();
        store.add(row(2, "port", 2));
        sleepQuietly(600);
        assertThat(store.takes()).isEqualTo(before);
        assertThat(store.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("iş parçacığı asla ölmez: uygulayıcı Error fırlatsa da sürer; kapanış dinleyicisi beklemez, @PreDestroy ≤ 500 ms birleştirir")
    void threadSurvivesErrorsAndStopsOnShutdown() throws Exception {
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        AtomicBoolean thrown = new AtomicBoolean();
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        a.applier().hook = w -> {
            if (thrown.compareAndSet(false, true)) throw new LinkageError("broken");
            if (w.get(0).id() == 3L) {
                busy.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        d.start();
        Thread t = (Thread) ReflectionTestUtils.getField(d, "thread");
        assertThat(t).isNotNull();
        assertThat(t.isDaemon()).isTrue();
        assertThat(t.getName()).isEqualTo(CheckResultDrainer.THREAD_NAME);
        store.add(row(1, "port", 1));
        await().atMost(Duration.ofSeconds(5)).until(thrown::get);
        store.add(row(2, "port", 2));
        await().atMost(Duration.ofSeconds(5)).until(() -> a.applier().appliedIds().contains(2L));
        assertThat(t.isAlive()).isTrue();
        assertThat(d.status().lastFailure()).isEqualTo("LinkageError");

        store.add(row(3, "port", 3));   // iş parçacığı uygulama içinde meşgul kalır
        assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();
        long c0 = System.nanoTime();
        d.onContextClosed(null);   // yalnız bayrak — beklemez
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - c0)).isLessThan(100L);
        ShutdownDeadline deadline = new ShutdownDeadline();
        ShutdownDeadline.activate(deadline);
        try {
            long t0 = System.nanoTime();
            d.destroy();
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertThat(waited).as("en çok 500 ms birleştirir").isBetween(CheckResultDrainer.SHUTDOWN_JOIN_CAP_MS - 50,
                    CheckResultDrainer.SHUTDOWN_JOIN_CAP_MS + 1_000L);
            // bekleme ortak yok etme süresinden düştü (süre bu çağrıyla başladı)
            assertThat(deadline.remainingMs(ShutdownDeadline.DESTROY_BUDGET_MS, t0 + TimeUnit.SECONDS.toNanos(5)))
                    .isLessThanOrEqualTo(ShutdownDeadline.DESTROY_BUDGET_MS - 4_000L);
        } finally {
            ShutdownDeadline.activate(null);
            release.countDown();
        }
        await().atMost(Duration.ofSeconds(3)).until(() -> !t.isAlive());
        d.start();
        assertThat(ReflectionTestUtils.getField(d, "thread")).isSameAs(t);   // kapanıştan sonra yeniden kurulmaz
    }

    @Test
    @DisplayName("kapanış başlayınca (shutdownStarted / kapanış bayrağı) alma yok")
    void noTakeAfterShutdownStarted() {
        Rig a = leader();
        store.add(row(1, "port", 1));
        a.drainer().onContextClosed(null);
        a.drainer().drainOnce();
        assertThat(store.takes()).isZero();

        Rig b = rig(2, true);
        mp.advance(181_000L);
        assertThat(b.pod().scheduler().isSweepLeaderNow()).isTrue();
        b.pod().scheduler().onContextClosed(null);
        assertThat(b.pod().scheduler().sideJobsStopping()).isTrue();
        b.drainer().drainOnce();
        assertThat(store.takes()).isZero();
    }

    // ── 3-2 temiz devri ────────────────────────────────────────────────────────────────────────────────────────────

    /** Gerçek {@code SchedulerService.onContextClosed} devir sırası; kaydedicinin aldığı "durgun" bayrağını yakalar. */
    AtomicReference<Boolean> handoverSink(Rig r) {
        AtomicReference<Boolean> quiet = new AtomicReference<>();
        EvalStateSink sink = mock(EvalStateSink.class);
        when(sink.handoverReady()).thenReturn(true);
        when(sink.flushForHandover(anyLong(), anyBoolean())).thenAnswer(i -> {
            boolean q = i.getArgument(1);
            quiet.set(q);
            return q ? HandoverResult.WRITTEN : HandoverResult.NOT_QUIESCENT;   // EvalStateRecorder kuralı
        });
        ReflectionTestUtils.setField(r.pod().scheduler(), "evalStateSinkForHandover", sink);
        return quiet;
    }

    @Test
    @DisplayName("3-2 devri: 300 ms durulma anında süren parti → durgun değil (NOT_QUIESCENT); devirden sonra yeni alma yok")
    void handoverWhileABatchRuns_notQuiescent() throws Exception {
        Rig a = leader();
        AtomicReference<Boolean> quiet = handoverSink(a);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        a.applier().hook = w -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        store.add(row(1, "port", 1));
        Thread worker = new Thread(a.drainer()::drainOnce, "test-drain");
        worker.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        long t0 = System.nanoTime();
        a.pod().scheduler().onContextClosed(null);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0))
                .as("durulma ≤ 300 ms beklendi").isGreaterThanOrEqualTo(EvalStateSink.HANDOVER_QUIESCE_MS - 20);
        assertThat(quiet.get()).as("süren parti → işaretsiz devir (halef yalnız gözlemler)").isFalse();
        release.countDown();
        worker.join(5_000L);
        assertThat(worker.isAlive()).isFalse();
        int takes = store.takes();
        store.add(row(2, "port", 2));
        a.drainer().drainOnce();
        assertThat(store.takes()).as("devirden sonra alma yok").isEqualTo(takes);
    }

    @Test
    @DisplayName("3-2 devri: boşaltıcı adımlar arasında boşta → durgun (işaret yazılabilir); devirden sonra iş parçacığı almaz")
    void handoverWhileIdle_quiescent() {
        Rig a = leader();
        AtomicReference<Boolean> quiet = handoverSink(a);
        a.drainer().start();
        store.add(row(1, "port", 1));
        await().atMost(Duration.ofSeconds(5)).until(() -> a.applier().appliedIds().contains(1L));
        a.pod().scheduler().onContextClosed(null);
        assertThat(quiet.get()).as("partiler arasında bilet tutulmaz").isTrue();
        int takes = store.takes();
        store.add(row(2, "port", 2));
        sleepQuietly(600);
        assertThat(store.takes()).as("shutdownStarted sonrası alma yok").isEqualTo(takes);
    }

    // ── FX-EVAL (inceleme 2026-10-29) ──────────────────────────────────────────────────────────────────────────────

    /** Şimdiden SONRA gönderilmiş / sahiplenilmiş / bitmiş satır (taban ve klasik damga sınamaları). */
    CheckResultRow fresh(long id, String type, long monitorId) {
        long now = mp.now();
        return new CheckResultRow(id, type, monitorId, now + 1, now + 2, now + 3, "pod-x", "{\"open\":true}", false);
    }

    @Test
    @DisplayName("DH-M2: canDrain = kuruldu ∧ GAINED görüldü ∧ işbirlikçiler ∧ son başarılı alma (yoksa GAINED) 30 sn içinde")
    void canDrainFollowsTakeHealth() {
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        assertThat(d.canDrain()).as("iş parçacığı kurulmadı").isFalse();
        markStarted(d);
        assertThat(d.canDrain()).as("GAINED anından pencere").isTrue();
        mp.advance(CheckResultDrainer.TAKE_HEALTH_MIN_MS);
        assertThat(d.canDrain()).as("30 sn hiç alma yok").isFalse();
        d.drainOnce();   // boş alma = sağlıklı
        assertThat(d.canDrain()).isTrue();
        store.failAlways = new DataAccessResourceFailureException("db gone");
        mp.advance(CheckResultDrainer.TAKE_HEALTH_MIN_MS - 1);
        d.drainOnce();
        assertThat(d.canDrain()).as("son başarılı alma pencere içinde").isTrue();
        mp.advance(1);
        d.drainOnce();
        assertThat(d.canDrain()).as("alma 30 sn'dir düşüyor").isFalse();
        assertThat(d.status().canDrain()).isFalse();
        store.failAlways = null;
        d.drainOnce();
        assertThat(d.canDrain()).isTrue();
        d.onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.LOST, mp.now()));
        assertThat(d.canDrain()).as("lider değil (GAINED yok)").isFalse();
        d.onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.GAINED, mp.now()));
        assertThat(d.canDrain()).isTrue();
        d.onContextClosed(null);
        assertThat(d.canDrain()).as("kapanış").isFalse();
    }

    @Test
    @DisplayName("DH-M2 b: işbirlikçi eksik → boşaltamaz (canDrain / holdClassic false) ve bir kez WARN (eskiden sessizdi)")
    void missingCollaboratorWarnsOnceAndCannotDrain() {
        MonitoringOutageService m = newMos();
        mp.use(MonitoringOutageService.class, m);
        Pod p = mp.pod(1);
        CheckResultDrainer d = new CheckResultDrainer(dc, p::scheduler, () -> m, () -> store, () -> null, p::schedule,
                () -> dbClock);
        d.setLeaderClock(mp.clockSupplier());
        drainers.add(d);
        p.events().listen(SweepLeadershipChangedEvent.class, m::onSweepLeadershipChanged);
        p.events().listen(SweepLeadershipChangedEvent.class, d::onSweepLeadershipChanged);
        assertThat(p.scheduler().isSweepLeaderNow()).isTrue();
        markStarted(d);
        ch.qos.logback.classic.Logger lg =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CheckResultDrainer.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
                new ch.qos.logback.core.read.ListAppender<>();
        app.start();
        lg.addAppender(app);
        try {
            store.add(row(1, "port", 1));
            d.drainOnce();
            d.drainOnce();
            assertThat(store.takes()).isZero();
            assertThat(d.canDrain()).isFalse();
            assertThat(d.holdClassic()).as("boşaltamayan klasiği bekletmez").isFalse();
            assertThat(app.list.stream().filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(s -> s.startsWith("Dağıtık kontrol sonuç boşaltıcısı çalışamıyor")))
                    .containsExactly("Dağıtık kontrol sonuç boşaltıcısı çalışamıyor — eksik bileşen: CheckResultApplier "
                            + "(türler klasik taramada kalır)");
        } finally {
            lg.detachAppender(app);
        }
    }

    @Test
    @DisplayName("DH-M1 a: anahtar KAPALI → AÇIK: kapalıyken (ya da öncesinde) gönderilmiş işlerin sonuçları atılır, yeniden açıldıktan sonrakiler uygulanır")
    void switchOffThenOn_dropsResultsCreatedBeforeReEnable() {
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        store.add(row(1, "port", 1));
        d.drainOnce();
        assertThat(a.applier().appliedIds()).containsExactly(1L);

        settings.enabled = false;   // boşaltıcı adımı kapalı görür
        d.drainOnce();
        mp.advance(60_000L);
        store.add(row(2, "port", 1), row(3, "ping", 2));   // kapalıyken biten (kapanmadan önce gönderilmiş) işler
        settings.enabled = true;
        d.drainOnce();
        assertThat(a.applier().appliedIds()).as("klasik daha yeni gözlem yaptı").containsExactly(1L);
        assertThat(d.status().droppedObsolete()).isEqualTo(2);
        store.add(fresh(4, "port", 1));
        d.drainOnce();
        assertThat(a.applier().appliedIds()).containsExactly(1L, 4L);

        // kısa kapalı dönemi yalnız ayar olayı gördü (boşaltma adımı arada koşmadı)
        settings.enabled = false;
        d.onSettingsChanged(new AppSettingsChangedEvent(Set.of(DistributedChecks.KEY_ENABLED), "refresh"));
        settings.enabled = true;
        mp.advance(1_000L);
        store.add(new CheckResultRow(5, "port", 3, mp.now() - 500L, mp.now() - 400L, mp.now() - 100L, "pod-x",
                "{\"open\":true}", false));
        d.drainOnce();
        assertThat(a.applier().appliedIds()).containsExactly(1L, 4L);
        assertThat(d.status().droppedObsolete()).isEqualTo(3);
    }

    @Test
    @DisplayName("F2: anahtar kapanırken BEKLEYEN (PENDING) iş yeniden açıldıktan sonra sahiplenilip koşarsa sonucu UYGULANIR (o dönem kaybolmaz)")
    void pendingAtOffClaimedAfterReEnable_isApplied() {
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        d.drainOnce();
        long createdBeforeOff = mp.now() - 1_000L;
        settings.enabled = false;
        d.drainOnce();
        mp.advance(60_000L);
        settings.enabled = true;
        d.drainOnce();   // taban burada: şimdi
        mp.advance(2_000L);
        long now = mp.now();
        // kapanmadan önce gönderilmiş (PENDING), açıldıktan sonra sahiplenilip biten iş
        store.add(new CheckResultRow(7, "port", 1, createdBeforeOff, now - 1_500L, now - 500L, "pod-x", "{\"open\":true}", false));
        // kapanmadan önce sahiplenilmiş (koşmaya başlamış) iş — atılır
        store.add(new CheckResultRow(8, "port", 2, createdBeforeOff, createdBeforeOff + 100L, now - 400L, "pod-x",
                "{\"open\":true}", false));
        d.drainOnce();
        assertThat(a.applier().appliedIds()).containsExactly(7L);
        assertThat(d.status().droppedObsolete()).isEqualTo(1);
    }

    @Test
    @DisplayName("DH-M1 a: açılışta KAPALI, sonradan etkinleşen pod eski sonuçları atar; açılışta AÇIK pod (olağan devir) atmaz")
    void lateActivationFloorsOldResults() {
        settings.enabled = false;
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        d.onReady(null);   // açılış hazır: anahtar kapalı
        settings.enabled = true;
        store.add(row(1, "port", 1), fresh(2, "port", 2));
        d.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> a.applier().appliedIds().contains(2L));
        assertThat(a.applier().appliedIds()).containsExactly(2L);
        assertThat(d.status().droppedObsolete()).isEqualTo(1);

        // açılışta AÇIK: devralınan kuyruk (önceki liderin bitmiş işleri) değerlendirilir
        Rig b = rig(2, true);
        CheckResultDrainer e = b.drainer();
        e.onReady(null);
        mp.advance(181_000L);
        assertThat(b.pod().scheduler().isSweepLeaderNow()).isTrue();
        store.add(row(3, "port", 3));
        markStarted(e);
        e.drainOnce();
        assertThat(b.applier().appliedIds()).containsExactly(3L);
        assertThat(e.status().droppedObsolete()).isZero();
    }

    @Test
    @DisplayName("DH-M1 b: aynı izlemenin klasik zamanlanmış kontrolü işten SONRA başladıysa iş sonucu atılır; sonra sahiplenilen uygulanır; liderlik olayı damgaları siler")
    void classicCheckSupersedesOlderClaim() {
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        d.noteClassicCheck("port", 1L);   // kurulmadı → yazılmaz (K-1)
        markStarted(d);
        d.noteClassicCheck("port", 1L);   // klasik kontrol şimdi başladı
        store.add(row(1, "port", 1), row(2, "port", 2), fresh(3, "port", 1));
        d.drainOnce();
        assertThat(a.applier().appliedIds()).as("eski iş düştü, başka izleme ve sonraki iş uygulandı")
                .containsExactly(2L, 3L);
        assertThat(d.status().droppedSuperseded()).isEqualTo(1);

        d.onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.LOST, mp.now()));
        d.onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.GAINED, mp.now()));
        store.add(row(4, "port", 1));
        d.drainOnce();
        assertThat(a.applier().appliedIds()).endsWith(4L);

        settings.enabled = false;   // anahtar kapalı → damga yazılmaz
        d.noteClassicCheck("ping", 9L);
        settings.enabled = true;
        store.add(row(5, "ping", 9));
        d.drainOnce();
        assertThat(a.applier().appliedIds()).endsWith(5L);
        assertThat(d.status().droppedSuperseded()).isEqualTo(1);
    }

    @Test
    @DisplayName("DH-M1 c: holdClassic = GAINED sonrası birikim boşalana dek (tavan içinde); yetişince / kurulmadıysa / anahtar kapalıyken false")
    void holdClassicSemantics() {
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        assertThat(d.holdClassic()).as("kurulmadı (K-1)").isFalse();
        markStarted(d);
        assertThat(d.holdClassic()).isTrue();
        settings.enabled = false;
        assertThat(d.holdClassic()).isFalse();
        settings.enabled = true;
        d.drainOnce();   // boş kuyruk → yetişti
        assertThat(d.holdClassic()).isFalse();
        d.onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.GAINED, mp.now()));
        assertThat(d.holdClassic()).isTrue();
        mp.advance(CheckResultDrainer.HOLD_MANUAL_MAX_MS);
        assertThat(d.holdClassic()).as("tavan").isFalse();
    }

    @Test
    @DisplayName("DH-M1 c: yetişme penceresinde döngü 200 ms'de bir yoklar — yükleme biter bitmez alır (drain-ms 5 sn beklenmez)")
    void catchUpPollsRightAfterTheLoad() {
        dc = new DistributedChecks(settings.svc, new DistributedChecks.Tuning(true, 16, 1_000L, 5_000L, 5_000L, 50, 5_000L,
                600_000L));
        Rig a = leader();
        store.add(row(1, "port", 1));
        a.gate().arm(7L, mp.now() + EvalLoadGate.LOAD_TIMEOUT_MS);
        a.drainer().start();
        await().atMost(Duration.ofSeconds(3)).until(() -> a.drainer().status().loadWaits() >= 1);
        a.mos().evalLoadSkipped(7L, null);
        long t0 = System.nanoTime();
        await().atMost(Duration.ofMillis(1_500)).until(() -> a.applier().appliedIds().contains(1L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(1_500L);
    }

    @Test
    @DisplayName("DH-L1: satırlar alındıktan sonra kapanış başlarsa (çit kapalı) devir TEMİZ yazılmaz; sonraki dalga sayılır")
    void handoverDuringApply_rowsNotLostOrNotClean() throws Exception {
        Rig a = leader();
        ReflectionTestUtils.setField(a.pod().scheduler(), "checkResultDrainer", a.drainer());
        AtomicReference<Boolean> quiet = handoverSink(a);
        AtomicReference<Boolean> fenceAtApply = new AtomicReference<>();
        Thread[] closer = new Thread[1];
        a.applier().hook = w -> {
            if (closer[0] != null) return;
            closer[0] = new Thread(() -> a.pod().scheduler().onContextClosed(null), "fx-closer");
            closer[0].start();
            await().atMost(Duration.ofSeconds(2)).until(() -> a.pod().scheduler().sideJobsStopping());
            fenceAtApply.set(a.pod().scheduler().isSweepLeaderNow());
        };
        store.add(row(1, "port", 1), row(2, "ping", 2), row(3, "port", 1));   // dalgalar [1, 2], [3]
        a.drainer().drainOnce();
        closer[0].join(5_000L);
        assertThat(store.remaining()).as("satırlar kuyruktan silindi").isZero();
        assertThat(fenceAtApply.get()).as("çit kapalı").isFalse();
        assertThat(a.drainer().lostInHandover()).isTrue();
        assertThat(a.drainer().status().droppedHandover()).as("ikinci dalga değerlendirilmedi").isEqualTo(1);
        assertThat(a.applier().appliedIds()).containsExactly(1L, 2L);
        assertThat(quiet.get()).as("işaret temiz yazılmaz → halef yalnız gözlemler").isFalse();
    }

    @Test
    @DisplayName("DH-L1 (incelemenin yoklaması, tek dalga): kapanış SON dalganın uygulaması sırasında başlarsa da devir temiz yazılmaz")
    void handoverDuringTheOnlyWave_markerNotClean() throws Exception {
        Rig a = leader();
        ReflectionTestUtils.setField(a.pod().scheduler(), "checkResultDrainer", a.drainer());
        AtomicReference<Boolean> quiet = handoverSink(a);
        AtomicReference<Integer> remainingAtApply = new AtomicReference<>();
        Thread[] closer = new Thread[1];
        a.applier().hook = w -> {
            if (closer[0] != null) return;
            remainingAtApply.set(store.remaining());
            closer[0] = new Thread(() -> a.pod().scheduler().onContextClosed(null), "fx-closer-1");
            closer[0].start();
            await().atMost(Duration.ofSeconds(2)).until(() -> a.pod().scheduler().sideJobsStopping());
        };
        store.add(row(1, "port", 1), row(2, "ping", 2), row(3, "http", 3));   // tek dalga
        a.drainer().drainOnce();
        closer[0].join(5_000L);
        assertThat(remainingAtApply.get()).as("uygulama anında satırlar kuyrukta yok").isZero();
        assertThat(a.drainer().status().droppedHandover()).isZero();
        assertThat(a.drainer().lostInHandover()).isTrue();
        assertThat(quiet.get()).as("çit bu dalganın MOS çağrılarını düşürdü → işaret temiz değil").isFalse();
    }

    @Test
    @DisplayName("DH-L1: kapanıştan önce biten parti devri kirletmez (boşta → işaret temiz)")
    void handoverAfterACompletedBatch_markerClean() {
        Rig a = leader();
        ReflectionTestUtils.setField(a.pod().scheduler(), "checkResultDrainer", a.drainer());
        AtomicReference<Boolean> quiet = handoverSink(a);
        store.add(row(1, "port", 1));
        a.drainer().drainOnce();
        a.pod().scheduler().onContextClosed(null);
        assertThat(a.drainer().lostInHandover()).isFalse();
        assertThat(quiet.get()).isTrue();
    }

    @Test
    @DisplayName("DH-L2: bekletme tavanı yükleme kapısı açıldıktan sonraki ilk adımdan ölçülür (yavaş TAM yükleme sonrası elle kutu beklemeye devam eder)")
    void slowLoad_holdStartsWhenTheGateOpens() {
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        markStarted(d);
        store.add(row(1, "port", 1));
        a.gate().arm(7L, mp.now() + EvalLoadGate.LOAD_TIMEOUT_MS);
        mp.advance(19_000L);
        d.drainOnce();   // yükleme sürüyor
        assertThat(d.status().loadWaits()).isEqualTo(1);
        assertThat(d.holdManual()).isTrue();
        mp.advance(2_000L);   // 21 sn: yükleme zaman aşımıyla bitti, adım henüz koşmadı
        assertThat(d.holdManual()).as("yükleme görüldü — tavan yükleme süresi kadar uzar").isTrue();
        store.failOnCall.set(store.takes() + 1);   // kapıyı geçen ilk adımın alması düşer → henüz yetişmedi
        d.drainOnce();
        assertThat(d.status().caughtUp()).isFalse();
        mp.advance(CheckResultDrainer.HOLD_MANUAL_MAX_MS - 1);
        assertThat(d.holdManual()).as("kapıdan 20 sn dolmadı").isTrue();
        mp.advance(1);
        assertThat(d.holdManual()).as("kapıdan 20 sn").isFalse();
    }

    @Test
    @DisplayName("DH-L3: bir adımın iki partisi arasında liderlik olayı olursa adım durur (yeni dönem sessizce benimsenmez)")
    void leadershipEventBetweenBatchesStopsTheIteration() {
        Rig a = leader();
        CheckResultDrainer d = a.drainer();
        for (long id = 1; id <= 250; id++) store.add(row(id, "port", id));
        AtomicBoolean flipped = new AtomicBoolean();
        a.applier().hook = w -> {
            if (flipped.compareAndSet(false, true)) {
                d.onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.LOST, mp.now()));
                d.onSweepLeadershipChanged(event(SweepLeadershipChangedEvent.GAINED, mp.now()));
            }
        };
        assertThat(d.drainOnce()).isFalse();
        assertThat(store.takes()).as("ikinci parti alınmadı").isEqualTo(1);
        assertThat(a.applier().appliedIds()).hasSize(100);
        d.drainOnce();   // sonraki adım baştan sorar ve kalanları alır
        assertThat(a.applier().appliedIds()).hasSize(250);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
