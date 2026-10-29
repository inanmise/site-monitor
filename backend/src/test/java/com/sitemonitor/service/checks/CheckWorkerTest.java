package com.sitemonitor.service.checks;

import com.sitemonitor.config.ShutdownDeadline;
import com.sitemonitor.service.AppSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CheckWorker} (2026-10-29, çok pod Sürüm 3-3 paket A, tasarım §9.2 A): yuva sınırı, liderin payı + çalma (U-2),
 * yalnız lider alabildiği türde tam yuva, k6 izin kapısı, yetenek süzgeci, uyarlanan yoklama, işçi zaman aşımı (yuva dolu kalır,
 * geç gövde atılır), gövde istisnası (yalnız sınıf adı), gövde / işçi / anahtar kapalı → sahiplenme yok (koşanların sonucu yine
 * yazılır), geç tamamlama, yazım hatasında yeniden deneme, kapanışta başlamamış işin bırakılması ve kesilen işin sonucunun
 * yazılmaması, gerçek iş parçacıklarıyla uçtan uca tur.
 */
class CheckWorkerTest {

    private static final long T0 = 1_800_000_000_000L;

    final TestDbClock clock = new TestDbClock(T0);
    final AtomicLong nano = new AtomicLong(10_000_000_000L);
    final AtomicBoolean enabled = new AtomicBoolean(true);
    final AtomicBoolean leader = new AtomicBoolean();
    final AtomicInteger permits = new AtomicInteger(0);
    final FakeStore store = new FakeStore();
    final FakeRegistry registry = new FakeRegistry();
    volatile BiFunction<String, Long, Map<String, Object>> body = (t, id) -> ok(id);
    final AtomicBoolean runnerPresent = new AtomicBoolean(true);
    final List<CheckWorker> workers = new ArrayList<>();
    final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        ShutdownDeadline.activate(null);
        for (CheckWorker w : workers) {
            w.onContextClosed(null);
            exhaustDeadline();
            w.destroy();
        }
        ShutdownDeadline.activate(null);
        for (ExecutorService e : executors) e.shutdownNow();
        long deadline = System.currentTimeMillis() + 5_000;
        while (CheckWorkerRegistryTest.anyCheckWorkerThread() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertThat(CheckWorkerRegistryTest.anyCheckWorkerThread()).as("test iş parçacığı bırakmaz").isFalse();
    }

    private static void exhaustDeadline() {
        ShutdownDeadline d = new ShutdownDeadline();
        d.remainingMs(0, System.nanoTime() - TimeUnit.SECONDS.toNanos(30));
        ShutdownDeadline.activate(d);
    }

    static Map<String, Object> ok(long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("id", id);
        return m;
    }

    private DistributedChecks dc(DistributedChecks.Tuning tuning) {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean(eq(DistributedChecks.KEY_ENABLED), anyBoolean())).thenAnswer(inv -> enabled.get());
        when(s.getString(eq(DistributedChecks.KEY_TYPES), anyString())).thenReturn(CheckJobTypes.DEFAULT_CSV);
        return new DistributedChecks(s, tuning);
    }

    private CheckWorker worker(DistributedChecks.Tuning tuning) {
        CheckJobRunner runner = (type, id) -> body.apply(type, id);
        CheckWorker w = new CheckWorker(store, registry, dc(tuning), () -> runnerPresent.get() ? runner : null, leader::get,
                () -> false, permits::get, clock, "pod1-00000001", nano::get);
        workers.add(w);
        return w;
    }

    private CheckWorker started(DistributedChecks.Tuning tuning) {
        CheckWorker w = worker(tuning);
        w.startForTest(null);
        return w;
    }

    private static DistributedChecks.Tuning slots(int n) {
        return new DistributedChecks.Tuning(true, n, 1_000, 5_000, 2_000, 50, 5_000, 600_000);
    }

    private void advance(long ms) {
        nano.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms));
        clock.advance(ms);
    }

    private static void await(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertThat(cond.getAsBoolean()).as("koşul 5 sn içinde sağlanmadı").isTrue();
    }

    @Test
    @DisplayName("Yuva sınırı: en çok boş yuva kadar sahiplenir; yuvalar doluyken deneme yok; boşalınca sonuçlar yazılır, yenileri alınır")
    void claimsAtMostFreeSlots() throws Exception {
        store.add(CheckJobTypes.PORT, 10);
        CountDownLatch gate = new CountDownLatch(1);
        body = (t, id) -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok(id);
        };
        CheckWorker w = started(slots(4));
        w.tick();
        assertThat(store.claims).singleElement().satisfies(c -> assertThat(c.limit()).isEqualTo(4));
        await(() -> w.running() == 4);
        advance(1_000);
        w.tick();
        assertThat(store.claims).as("boş yuva yok — sahiplenme denemesi yok").hasSize(1);
        gate.countDown();
        await(() -> w.running() == 0 && w.pendingCompletions() == 4);
        advance(1_000);
        w.tick();
        assertThat(store.completed).hasSize(4);
        assertThat(store.claims).hasSize(2);
        assertThat(store.claims.get(1).limit()).isEqualTo(4);
        assertThat(w.status().completed()).isEqualTo(4);
        assertThat(w.status().claimed()).isEqualTo(8);
    }

    @Test
    @DisplayName("Lider (U-2): başka canlı işçi varken payı kadar (16 × %50 = 8), sonra kalan yuvalarla 5 sn'den eski işleri çalar")
    void leaderShareThenSteal() {
        leader.set(true);
        registry.caps = Set.of(CheckJobTypes.PORT, CheckJobTypes.HTTP);
        registry.live.put(CheckJobTypes.PORT, 2);
        registry.live.put(CheckJobTypes.HTTP, 1);
        CheckWorker w = started(DistributedChecks.Tuning.DEFAULTS);
        w.tick();
        assertThat(store.claims).hasSize(2);
        assertThat(store.claims.get(0).types()).containsExactlyInAnyOrder(CheckJobTypes.PORT, CheckJobTypes.HTTP);
        assertThat(store.claims.get(0).limit()).isEqualTo(8);
        assertThat(store.claims.get(0).createdBefore()).isEqualTo(CheckJobStore.ANY_AGE);
        assertThat(store.claims.get(1).limit()).isEqualTo(16);
        assertThat(store.claims.get(1).createdBefore()).as("çal: steal-after-ms'ten eski").isEqualTo(T0 - 5_000);
    }

    @Test
    @DisplayName("Lider: türü başka kimse alamıyorsa (0 ya da okunamadı) o tür için tüm yuvalar; payı koşanlar düşer")
    void leaderSoloTypesUseFullSlots() {
        leader.set(true);
        registry.caps = Set.of(CheckJobTypes.PORT, CheckJobTypes.PING);
        registry.live.put(CheckJobTypes.PORT, 1);
        registry.live.put(CheckJobTypes.PING, CheckWorkerRegistry.UNKNOWN);
        store.add(CheckJobTypes.PING, 3);
        CheckWorker w = started(DistributedChecks.Tuning.DEFAULTS);
        body = (t, id) -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok(id);
        };
        w.tick();
        assertThat(store.claims.get(0).types()).containsExactly(CheckJobTypes.PING);
        assertThat(store.claims.get(0).limit()).isEqualTo(16);
        assertThat(store.claims.get(1).types()).containsExactly(CheckJobTypes.PORT);
        assertThat(store.claims.get(1).limit()).as("pay 8 − koşan 3").isEqualTo(5);
        assertThat(store.claims.get(2).limit()).as("çal: 16 − 3").isEqualTo(13);
    }

    @Test
    @DisplayName("Lider değil ya da yalnız lider: tek sahiplenme, tüm yuvalar")
    void followerUsesFullSlots() {
        registry.caps = Set.of(CheckJobTypes.PORT, CheckJobTypes.DNS);
        registry.live.put(CheckJobTypes.PORT, 3);
        CheckWorker w = started(DistributedChecks.Tuning.DEFAULTS);
        w.tick();
        assertThat(store.claims).singleElement().satisfies(c -> {
            assertThat(c.limit()).isEqualTo(16);
            assertThat(c.types()).containsExactlyInAnyOrder(CheckJobTypes.PORT, CheckJobTypes.DNS);
        });
        leader.set(true);
        registry.live.clear();
        advance(10_000);
        w.tick();
        assertThat(store.claims).hasSize(2);
        assertThat(store.claims.get(1).limit()).as("yalnız lider").isEqualTo(16);
    }

    @Test
    @DisplayName("k6: senaryo işleri ayrı ve min(boş yuva, boş izin) kadar; izin yoksa hiç; yetenek yoksa türler arasında yok")
    void scriptedPermitGate() {
        registry.caps = Set.of(CheckJobTypes.PORT, CheckJobTypes.SCRIPTED);
        store.add(CheckJobTypes.SCRIPTED, 5);
        permits.set(1);
        CheckWorker w = started(DistributedChecks.Tuning.DEFAULTS);
        body = (t, id) -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok(id);
        };
        w.tick();
        assertThat(store.claims).hasSize(2);
        assertThat(store.claims.get(0).types()).containsExactly(CheckJobTypes.PORT);
        assertThat(store.claims.get(1).types()).containsExactly(CheckJobTypes.SCRIPTED);
        assertThat(store.claims.get(1).limit()).isEqualTo(1);
        permits.set(0);
        advance(1_000);
        w.tick();
        assertThat(store.claims).as("izin yok → senaryo sahiplenmesi yok").hasSize(3);
        assertThat(store.claims.get(2).types()).containsExactly(CheckJobTypes.PORT);
        registry.caps = Set.of(CheckJobTypes.PORT);
        permits.set(5);
        advance(10_000);
        w.tick();
        assertThat(store.claims.get(store.claims.size() - 1).types()).containsExactly(CheckJobTypes.PORT);
    }

    @Test
    @DisplayName("Uyarlanan yoklama: boşta 2 → 4 → 5 sn (tavan), iş bulununca 1 sn")
    void adaptivePoll() {
        registry.caps = Set.of(CheckJobTypes.PORT);
        CheckWorker w = started(DistributedChecks.Tuning.DEFAULTS);
        w.tick();
        assertThat(store.claims).hasSize(1);
        assertThat(w.pollDelayMs()).isEqualTo(2_000);
        advance(1_999);
        w.tick();
        assertThat(store.claims).hasSize(1);
        advance(1);
        w.tick();
        assertThat(store.claims).hasSize(2);
        assertThat(w.pollDelayMs()).isEqualTo(4_000);
        advance(4_000);
        w.tick();
        assertThat(w.pollDelayMs()).isEqualTo(5_000);
        advance(5_000);
        w.tick();
        assertThat(w.pollDelayMs()).isEqualTo(5_000);
        store.add(CheckJobTypes.PORT, 1);
        advance(5_000);
        w.tick();
        assertThat(store.claims).hasSize(5);
        assertThat(w.pollDelayMs()).as("iş bulundu").isEqualTo(1_000);
        advance(1_000);
        w.tick();
        assertThat(store.claims).hasSize(6);
    }

    @Test
    @DisplayName("İşçi zaman aşımı: kira − 10 sn'de worker_timeout yazılır ve gövde kesilir; kesmeyi yok sayan gövde yuvayı tutar, geç sonucu atılır")
    void workerTimeout() throws Exception {
        // Yeniden taban (inceleme QW-L5): zaman aşımında gövde artık KESİLİR — bu test kesmeyi YOK SAYAN gövdeyi sınar (yuva
        // gövde dönene dek dolu kalır); kesmeye uyan gövdenin yuvası boşalır: hungBodyIsInterruptedAndSlotReturned.
        store.add(CheckJobTypes.HTTP, 1);
        AtomicBoolean release = new AtomicBoolean();
        AtomicInteger interruptsSeen = new AtomicInteger();
        body = (t, id) -> {
            while (!release.get()) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ignored) {
                    interruptsSeen.incrementAndGet();   // kesmeyi yok sayan (kötü davranan) gövde
                }
            }
            return ok(id);
        };
        CheckWorker w = started(DistributedChecks.Tuning.DEFAULTS);
        w.tick();
        await(() -> w.running() == 1);
        advance(CheckJobTypes.workerTimeoutMs(CheckJobTypes.HTTP) - 1);
        w.tick();
        assertThat(w.status().timeouts()).isZero();
        assertThat(w.interrupts()).isZero();
        advance(2);
        w.tick();
        advance(CheckWorker.FLUSH_MS);
        w.tick();
        assertThat(store.completed).singleElement().satisfies(c -> assertThat(c.resultJson()).isEqualTo("{\"worker_timeout\":true}"));
        assertThat(w.status().timeouts()).isEqualTo(1);
        assertThat(w.interrupts()).as("zaman aşımı gövdeyi keser").isEqualTo(1);
        await(() -> interruptsSeen.get() == 1);
        assertThat(w.running()).as("kesmeyi yok sayan gövde yuvayı tutar").isEqualTo(1);
        release.set(true);
        await(() -> w.running() == 0);
        advance(CheckWorker.FLUSH_MS);
        w.tick();
        assertThat(store.completed).as("geç gövdenin sonucu yazılmaz").hasSize(1);
        assertThat(w.pendingCompletions()).isZero();
    }

    @Test
    @DisplayName("Takılan gövde zaman aşımında KESİLİR, yuva gövde dönünce boşalır; gövde kendini 'bırakıldı' görür; kesme sonraki işe sızmaz")
    void hungBodyIsInterruptedAndSlotReturned() throws Exception {
        store.add(CheckJobTypes.HTTP, 2);
        CountDownLatch never = new CountDownLatch(1);
        AtomicBoolean firstSawAbandoned = new AtomicBoolean();
        AtomicBoolean secondInterruptedOrAbandoned = new AtomicBoolean(true);
        AtomicBoolean outsideAbandoned = new AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        body = (t, id) -> {
            if (calls.incrementAndGet() == 1) {
                try {
                    never.await();
                } catch (InterruptedException e) {
                    firstSawAbandoned.set(CheckWorker.currentJobAbandoned());
                    Thread.currentThread().interrupt();   // gövde bayrağı geri koyar — sonraki işe sızmamalı
                }
                return ok(id);
            }
            secondInterruptedOrAbandoned.set(Thread.currentThread().isInterrupted() || CheckWorker.currentJobAbandoned());
            return ok(id);
        };
        ExecutorService one = Executors.newFixedThreadPool(1, r -> {
            Thread th = new Thread(r, CheckWorker.POOL_PREFIX + "test");
            th.setDaemon(true);
            return th;
        });
        executors.add(one);
        CheckWorker w = worker(slots(1));
        w.startForTest(one);
        w.tick();
        await(() -> w.running() == 1);
        long firstId = store.firstId();

        advance(CheckJobTypes.workerTimeoutMs(CheckJobTypes.HTTP) + 1);
        w.tick();
        await(() -> w.running() == 0);
        assertThat(w.interrupts()).isEqualTo(1);
        assertThat(firstSawAbandoned).as("gövde kendini bırakılmış görür (geçmiş satırını yazmaz)").isTrue();

        advance(1_000);
        w.tick();   // boşalan yuvayla ikinci iş (zaman aşımı turunda henüz alınmadıysa)
        await(() -> calls.get() == 2 && w.running() == 0);
        advance(CheckWorker.FLUSH_MS);
        w.tick();
        assertThat(secondInterruptedOrAbandoned).as("kesme / bırakılma aynı iş parçacığındaki sonraki işe sızmaz").isFalse();
        assertThat(store.completed).filteredOn(c -> c.id() == firstId).singleElement()
                .satisfies(c -> assertThat(c.resultJson()).isEqualTo("{\"worker_timeout\":true}"));
        assertThat(store.completed).filteredOn(c -> c.id() == firstId + 1).singleElement()
                .satisfies(c -> assertThat(c.resultJson()).contains("\"ok\":true"));
        Thread probe = new Thread(() -> outsideAbandoned.set(CheckWorker.currentJobAbandoned()));
        probe.start();
        probe.join(5_000);
        assertThat(outsideAbandoned).as("işçi dışındaki iş parçacığında her zaman false").isFalse();
    }

    @Test
    @DisplayName("Birikim tavanı (inceleme QW-L10): sonuçlar yazılamazken tavanda yeni iş SAHİPLENİLMEZ; yazılınca sürer — hiçbir tamamlama düşmez")
    void backlogStopsClaimingUntilDrained() throws Exception {
        store.add(CheckJobTypes.PORT, 12);
        CheckWorker w = started(slots(4));
        w.backlogLimits(3, Long.MAX_VALUE);
        store.failComplete = true;
        w.tick();
        assertThat(store.claims).hasSize(1);
        await(() -> w.running() == 0 && w.pendingCompletions() == 4);
        assertThat(w.backlogFull()).isTrue();
        for (int i = 0; i < 5; i++) {
            advance(5_000);
            w.tick();
        }
        assertThat(store.claims).as("birikim dolu — sahiplenme yok").hasSize(1);
        assertThat(w.backlogPauses()).isPositive();
        assertThat(w.status().completeErrors()).isPositive();
        assertThat(w.pendingCompletions()).as("hiçbir tamamlama düşmedi").isEqualTo(4);
        assertThat(w.status().late()).isZero();
        assertThat(w.pendingChars()).isPositive();

        store.failComplete = false;
        advance(5_000);
        w.tick();
        assertThat(store.completed).hasSize(4);
        assertThat(w.pendingChars()).isZero();
        assertThat(store.claims).as("birikim yazıldı — sahiplenme sürer").hasSize(2);
        assertThat(w.backlogDropped()).isZero();
    }

    @Test
    @DisplayName("Birikim tavanı karakterle de: büyük sonuçlar karakter tavanına ulaşınca sahiplenme durur")
    void backlogCharCapStopsClaiming() throws Exception {
        store.add(CheckJobTypes.PAGE, 6);
        body = (t, id) -> {
            Map<String, Object> m = ok(id);
            m.put("x", "a".repeat(2_000));
            return m;
        };
        CheckWorker w = started(slots(2));
        w.backlogLimits(1_000, 3_000);
        store.failComplete = true;
        w.tick();
        await(() -> w.running() == 0 && w.pendingCompletions() == 2);
        assertThat(w.pendingChars()).isGreaterThanOrEqualTo(4_000);
        advance(5_000);
        w.tick();
        assertThat(store.claims).as("karakter tavanı").hasSize(1);
        store.failComplete = false;
        advance(5_000);
        w.tick();
        assertThat(store.claims).hasSize(2);
        assertThat(store.completed).hasSize(2);
    }

    @Test
    @DisplayName("Gövde istisnası → {failed: SınıfAdı} (ileti yok); null sonuç → NullResult")
    void runnerFailureIsClassNameOnly() throws Exception {
        store.add(CheckJobTypes.PORT, 2);
        AtomicInteger n = new AtomicInteger();
        body = (t, id) -> {
            if (n.incrementAndGet() == 1) throw new IllegalArgumentException("gizli bilgi https://x?token=1");
            return null;
        };
        CheckWorker w = started(DistributedChecks.Tuning.DEFAULTS);
        w.tick();
        await(() -> w.pendingCompletions() == 2);
        advance(CheckWorker.FLUSH_MS);
        w.tick();
        assertThat(store.completed).extracting(CheckJobStore.Completion::resultJson)
                .containsExactlyInAnyOrder("{\"failed\":\"IllegalArgumentException\"}", "{\"failed\":\"NullResult\"}");
        assertThat(String.join("", store.completed.stream().map(CheckJobStore.Completion::resultJson).toList()))
                .doesNotContain("gizli").doesNotContain("token");
        assertThat(w.status().failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Kapılar: gövde (paket B) yok / işçi kapalı / anahtar kapalı → sahiplenme yok; koşanın sonucu anahtar kapanınca da yazılır")
    void gatesStopClaimsButNotCompletions() throws Exception {
        runnerPresent.set(false);
        CheckWorker w = started(DistributedChecks.Tuning.DEFAULTS);
        w.tick();
        assertThat(store.claims).as("gövde yok").isEmpty();
        runnerPresent.set(true);

        CheckWorker disabled = worker(new DistributedChecks.Tuning(false, 16, 1000, 5000, 2000, 50, 5000, 600000));
        disabled.start();
        assertThat(disabled.status().started()).as("işçi kapalı pod başlamaz").isFalse();
        assertThat(disabled.claimAllowed()).isFalse();

        store.add(CheckJobTypes.PORT, 1);
        CountDownLatch gate = new CountDownLatch(1);
        body = (t, id) -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok(id);
        };
        w.tick();
        assertThat(store.claims).hasSize(1);
        await(() -> w.running() == 1);
        enabled.set(false);
        store.add(CheckJobTypes.PORT, 3);
        gate.countDown();
        await(() -> w.pendingCompletions() == 1);
        advance(10_000);
        w.tick();
        assertThat(store.completed).as("koşan işin sonucu yazılır").hasSize(1);
        assertThat(store.claims).as("anahtar kapalı — yeni sahiplenme yok").hasSize(1);
    }

    @Test
    @DisplayName("Geç tamamlama (belirteç uyuşmadı) late sayılır; yazım hatası satırları kaybetmez, sonraki adımda yazılır")
    void lateAndRetriedCompletions() throws Exception {
        store.add(CheckJobTypes.PORT, 2);
        CheckWorker w = started(DistributedChecks.Tuning.DEFAULTS);
        w.tick();
        await(() -> w.pendingCompletions() == 2);
        store.failNextComplete.set(true);
        store.lostIds.add(store.firstId());
        advance(CheckWorker.FLUSH_MS);
        w.tick();
        assertThat(w.status().completeErrors()).isEqualTo(1);
        assertThat(w.pendingCompletions()).as("sıraya geri kondu").isEqualTo(2);
        advance(CheckWorker.FLUSH_MS);
        w.tick();
        assertThat(w.pendingCompletions()).isZero();
        assertThat(w.status().late()).isEqualTo(1);
        assertThat(w.status().completed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Kapanış: yeni sahiplenme yok; başlamamış iş yardımcı iş parçacığında bırakılır ve hiç başlamaz; kesilenin sonucu yazılmaz")
    void shutdownReleasesNotStartedAndCutsRunning() throws Exception {
        store.add(CheckJobTypes.PORT, 2);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger bodies = new AtomicInteger();
        body = (t, id) -> {
            bodies.incrementAndGet();
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("interrupted", true);
                return m;
            }
            return ok(id);
        };
        ExecutorService one = Executors.newFixedThreadPool(1, r -> {
            Thread th = new Thread(r, CheckWorker.POOL_PREFIX + "test");
            th.setDaemon(true);
            return th;
        });
        executors.add(one);
        CheckWorker w = worker(slots(2));
        w.startForTest(one);
        w.tick();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(w.running()).isEqualTo(2);

        w.onContextClosed(null);
        await(() -> store.released.size() == 1);
        assertThat(store.released.get(0)).as("ikinci iş başlamamıştı").isEqualTo(store.firstId() + 1);
        assertThat(w.running()).isEqualTo(1);
        advance(10_000);
        w.tick();
        assertThat(store.claims).as("kapanıştan sonra sahiplenme yok").hasSize(1);

        exhaustDeadline();
        w.destroy();
        await(() -> w.running() == 0);
        Thread.sleep(50);
        assertThat(bodies).as("bırakılan iş hiç koşmadı").hasValue(1);
        assertThat(w.status().droppedAtShutdown()).isEqualTo(1);
        advance(CheckWorker.FLUSH_MS);
        w.tick();
        assertThat(store.completed).as("kesilen işin sonucu yazılmaz (iş CLAIMED kalır)").isEmpty();
    }

    @Test
    @DisplayName("Uçtan uca: gerçek yoklama iş parçacığı ve yuvalar işleri koşturur, sonuçları yazar; kapanış iş parçacıklarını durdurur")
    void realThreadsEndToEnd() throws Exception {
        store.add(CheckJobTypes.PORT, 5);
        store.add(CheckJobTypes.DNS, 3);
        CheckWorker real = new CheckWorker(store, registry, dc(DistributedChecks.Tuning.DEFAULTS),
                () -> (type, id) -> ok(id), () -> false, () -> false, () -> 0, clock, "pod1-00000001", System::nanoTime);
        workers.add(real);
        real.start();
        assertThat(CheckWorkerRegistryTest.threadNames()).contains(CheckWorker.POLL_THREAD);
        await(() -> store.completed.size() == 8);
        assertThat(CheckWorkerRegistryTest.threadNames()).anyMatch(n -> n.matches("check-worker-\\d+"));
        assertThat(real.status().completed()).isEqualTo(8);
        assertThat(real.claimLagP95()).isNotNull();
        real.onContextClosed(null);
        real.destroy();
        assertThat(CheckWorkerRegistryTest.threadNames()).doesNotContain(CheckWorker.POLL_THREAD);
    }

    @Test
    @DisplayName("Sahiplenme gecikmesi p95: son pencere üzerinden, yoksa null")
    void claimLagP95() throws Exception {
        CheckWorker w = started(slots(64));
        assertThat(w.claimLagP95()).isNull();
        store.lagStep = 10;
        store.add(CheckJobTypes.PORT, 20);
        w.tick();
        await(() -> w.running() == 0);
        // gecikmeler 0, 10, …, 190 → p95 = ceil(0.95 × 20) − 1 = 18. sıradaki = 180
        assertThat(w.claimLagP95()).isEqualTo(180L);
        assertThat(w.localStats().claimLagP95Ms()).isEqualTo(180L);
        assertThat(w.localStats().slots()).isEqualTo(64);
    }

    // ── Sahteler ────────────────────────────────────────────────────────────────────────────────────────────────────

    record ClaimCall(Set<String> types, int limit, long createdBefore) {}

    final class FakeStore implements CheckJobStore {
        final List<long[]> jobs = new CopyOnWriteArrayList<>();   // {id, typeIndex, monitorId, state 0/1/2}
        final List<ClaimCall> claims = new CopyOnWriteArrayList<>();
        final List<Completion> completed = new CopyOnWriteArrayList<>();
        final List<Long> released = new CopyOnWriteArrayList<>();
        final Set<Long> lostIds = ConcurrentHashMap.newKeySet();
        final AtomicBoolean failNextComplete = new AtomicBoolean();
        volatile boolean failComplete;
        final AtomicLong seq = new AtomicLong(100);
        volatile long lagStep;

        void add(String type, int n) {
            for (int i = 0; i < n; i++) {
                long id = seq.incrementAndGet();
                jobs.add(new long[] {id, CheckJobTypes.ALL.indexOf(type), id * 10, 0});
            }
        }

        long firstId() {
            return jobs.get(0)[0];
        }

        @Override
        public int dispatch(String type, Collection<Long> monitorIds, long leaseMs, String dispatcher) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized List<CheckJobRow> claim(Collection<String> types, int limit, String owner, long createdBeforeMs) {
            claims.add(new ClaimCall(Set.copyOf(types), limit, createdBeforeMs));
            List<CheckJobRow> out = new ArrayList<>();
            int k = 0;
            for (long[] j : jobs) {
                if (out.size() >= limit) break;
                String type = CheckJobTypes.ALL.get((int) j[1]);
                if (j[3] != 0 || !types.contains(type)) continue;
                j[3] = 1;
                long now = clock.nowMs();
                long created = now - lagStep * k++;
                out.add(new CheckJobRow(j[0], type, j[2], created, 1, CheckJobTypes.leaseMs(type), "t" + j[0], now,
                        now + CheckJobTypes.leaseMs(type)));
            }
            return out;
        }

        @Override
        public boolean[] complete(List<Completion> list) {
            if (failNextComplete.getAndSet(false) || failComplete) {
                throw new org.springframework.dao.DataAccessResourceFailureException("db");
            }
            boolean[] out = new boolean[list.size()];
            for (int i = 0; i < list.size(); i++) {
                out[i] = !lostIds.contains(list.get(i).id());
                if (out[i]) completed.add(list.get(i));
            }
            return out;
        }

        @Override
        public int release(String owner, Collection<Long> ids) {
            released.addAll(ids);
            return ids.size();
        }

        @Override
        public List<CheckResultRow> take(int limit, String leaseOwner) {
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

    static final class FakeRegistry implements CheckWorkerRegistry {
        volatile Set<String> caps = Set.copyOf(CheckJobTypes.ALL);
        final Map<String, Integer> live = new ConcurrentHashMap<>();

        @Override
        public int liveOtherWorkers(String type) {
            return live.getOrDefault(type, 0);
        }

        @Override
        public Set<String> localCapabilities() {
            return CheckJobTypes.canonical(caps);
        }

        @Override
        public List<WorkerView> workers() {
            return List.of();
        }
    }
}
