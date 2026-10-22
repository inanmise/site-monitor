package com.sitemonitor.service.cache;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Önbellek veri yolu — İKİ pod AYNI H2 veritabanında (2026-10-22, çok pod R2-A, MP-30). Her "pod" kendi {@link CacheBus}
 * örneğidir (kendi bellek içi kuşakları, kendi boşaltıcıları); yoklama iş parçacığı çoğu testte başlatılmaz — tur
 * {@code pollNow()} / {@code flushPending()} ile elle koşar (belirlenimli).
 */
class CacheBusH2Test {

    MultiPodTestSupport mp;
    DataSourceTransactionManager txm;
    final List<CacheBus> buses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        mp.jdbc().execute(CacheBus.DDL);
        txm = new DataSourceTransactionManager(mp.dataSource());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        for (CacheBus b : buses) b.stop(false);
        mp.close();
    }

    CacheBus pod() {
        return pod(mp.jdbc(), txm);
    }

    CacheBus pod(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager tm) {
        CacheBus b = new CacheBus(jdbc, tm);
        b.clock = mp.clockSupplier();
        b.createdAtMs = mp.now();
        buses.add(b);
        return b;
    }

    /** İş parçacığı çalışan pod (dönemsel yoklama uzak — yalnız zorunlu yoklama ve yayın). */
    CacheBus livePod() throws InterruptedException {
        CacheBus b = pod();
        b.pollMs = 60_000;
        b.start();
        waitFor(() -> b.appliedGeneration() >= 0, 3000);   // açılış turu (taban)
        return b;
    }

    long gen(String name) {
        List<Long> l = mp.jdbc().queryForList("SELECT generation FROM cache_bus WHERE name = ?", Long.class, name);
        return l.isEmpty() ? -1 : l.get(0);
    }

    static void waitFor(BooleanSupplier cond, long ms) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms);
        while (!cond.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertThat(cond.getAsBoolean()).as("koşul " + ms + " ms içinde sağlanmadı").isTrue();
    }

    @Test
    @DisplayName("A yayınlar → B'nin boşaltıcısı TEK kez koşar; B yeniden yayın yapmaz; A kendi önbelleğini ikinci kez boşaltmaz")
    void bumpOnA_evictsOnBOnce_noRebump() {
        CacheBus a = pod(), b = pod();
        AtomicInteger onA = new AtomicInteger(), onB = new AtomicInteger();
        a.register("oncall", onA::incrementAndGet);
        b.register("oncall", onB::incrementAndGet);
        assertThat(a.pollNow()).isTrue();
        assertThat(b.pollNow()).isTrue();

        a.bump("oncall");
        assertThat(a.hasPending()).as("işlem / istek yok → arka plan kuyruğu").isTrue();
        a.flushPending();
        assertThat(gen("oncall")).isEqualTo(1);
        assertThat(gen(CacheBus.GLOBAL)).isEqualTo(1);
        assertThat(a.appliedGeneration()).as("kendi yayını uygulanmış sayılır").isEqualTo(1);

        b.pollNow();
        assertThat(onB.get()).isEqualTo(1);
        b.pollNow();
        assertThat(onB.get()).as("aynı kuşak ikinci kez boşaltmaz").isEqualTo(1);
        assertThat(gen("oncall")).as("B yeniden yayın yapmadı").isEqualTo(1);
        assertThat(b.appliedGeneration()).isEqualTo(1);

        a.pollNow();
        assertThat(onA.get()).as("yazan pod kendi yayınını yeniden uygulamaz").isZero();
    }

    @Test
    @DisplayName("Geri alınan işlem yayın yapmaz; işlenen işlem işlendikten SONRA yayınlar")
    void rolledBack_noBump_committed_bumpsAfterCommit() {
        CacheBus a = pod(), b = pod();
        AtomicInteger onB = new AtomicInteger();
        b.register("team-grants", onB::incrementAndGet);
        a.pollNow();
        b.pollNow();
        TransactionTemplate t = new TransactionTemplate(txm);

        t.executeWithoutResult(st -> {
            a.bump("team-grants");
            assertThat(a.hasPending()).as("işlem sürerken yayın yok").isFalse();
            st.setRollbackOnly();
        });
        assertThat(a.hasPending()).as("geri alındı → yayın yok").isFalse();
        a.flushPending();
        b.pollNow();
        assertThat(onB.get()).isZero();

        t.executeWithoutResult(st -> {
            a.bump("team-grants");
            a.bump("team-grants", "oncall");   // aynı işlemde birden çok çağrı → tek eşleme
            assertThat(TransactionSynchronizationManager.getSynchronizations().stream()
                    .filter(s -> s instanceof CacheBus.AfterCompletion).count()).isEqualTo(1);
        });
        assertThat(a.hasPending()).isTrue();
        a.flushPending();
        assertThat(gen("team-grants")).isEqualTo(1);
        assertThat(gen(CacheBus.GLOBAL)).as("iki ad TEK genel artış").isEqualTo(1);
        b.pollNow();
        assertThat(onB.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Başka bir eşlemenin afterCommit'i içinden istenen yayın kaybolmaz (TechErrorReportService.analyticsChanged deseni)")
    void bumpFromAnotherAfterCommit_isPublished() {
        CacheBus a = pod();
        a.pollNow();
        new TransactionTemplate(txm).executeWithoutResult(st ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        a.bump(CacheBusNames.TECH_ERROR_ANALYTICS);
                    }
                }));
        assertThat(a.hasPending()).isTrue();
        a.flushPending();
        assertThat(gen(CacheBusNames.TECH_ERROR_ANALYTICS)).isEqualTo(1);
    }

    @Test
    @DisplayName("Tablo yok: kuyrukta kalır, yeniden bakma süresi dolmadan veritabanına GİDİLMEZ; tablo dönünce yayın + yoklama")
    void tableMissing_rechecksRarely_thenRecovers() {
        SlowDataSource ds = new SlowDataSource(mp.dataSource());
        CacheBus a = pod(new JdbcTemplate(ds), new DataSourceTransactionManager(ds)), b = pod();
        AtomicInteger onB = new AtomicInteger();
        b.register("weak-algo", onB::incrementAndGet);
        a.pollNow();
        b.pollNow();

        mp.jdbc().execute("ALTER TABLE cache_bus RENAME TO cache_bus_off");
        a.bump("weak-algo");
        a.flushPending();
        assertThat(a.hasPending()).as("tablo yok → yayın kuyrukta kalır").isTrue();
        assertThat(a.status()).containsEntry("table_missing", true);
        int before = ds.connections.get();
        a.flushPending();
        assertThat(a.pollNow()).isFalse();
        assertThat(ds.connections.get()).as("yeniden bakma süresi dolmadan bağlantı açılmaz").isEqualTo(before);
        assertThat(b.pollNow()).as("yoklama başarısız, istisna yok").isFalse();

        mp.jdbc().execute("ALTER TABLE cache_bus_off RENAME TO cache_bus");
        mp.advance(CacheBus.TABLE_RECHECK_MS);
        a.flushPending();
        assertThat(a.hasPending()).isFalse();
        b.tableReady();   // açılış yaması bildirimi: beklemeden yeniden dener
        assertThat(b.pollNow()).isTrue();
        assertThat(onB.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Boşaltıcı hata verirse ad uygulanmamış sayılır: genel kuşak ilerlemez, sonraki yoklama yeniden dener")
    void failingEvictor_isRetriedNextPoll() {
        CacheBus a = pod(), b = pod();
        AtomicInteger attempts = new AtomicInteger();
        b.register("noc-operators", () -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("geçici");
        });
        AtomicInteger listener = new AtomicInteger();
        b.onRemoteEvict(names -> listener.incrementAndGet());
        a.pollNow();
        b.pollNow();
        long appliedBefore = b.appliedGeneration();
        a.bump("noc-operators");
        a.flushPending();

        b.pollNow();
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(b.appliedGeneration()).as("hata → kuşak uygulanmadı").isEqualTo(appliedBefore);
        assertThat(listener.get()).as("başarısız ad dinleyiciye bildirilmez").isZero();
        b.pollNow();
        assertThat(attempts.get()).as("sonraki yoklama yeniden denedi").isEqualTo(2);
        assertThat(b.appliedGeneration()).isEqualTo(1);
        b.pollNow();
        assertThat(attempts.get()).as("başarıdan sonra yeniden çalışmaz").isEqualTo(2);
    }

    @Test
    @DisplayName("Elle silinen satır (!= kıyası) boşaltır; tablo hiç yoksa hiçbir çağrı istisna atmaz")
    void deletedRow_isDetected_missingTable_isSilent() {
        CacheBus a = pod(), b = pod();
        AtomicInteger onB = new AtomicInteger();
        b.register("quiet-hours", onB::incrementAndGet);
        a.bump("quiet-hours");
        a.flushPending();
        b.pollNow();   // taban (kuruluştan sonra yayınlanmış → boşaltır)
        int afterBaseline = onB.get();
        mp.jdbc().update("DELETE FROM cache_bus WHERE name = 'quiet-hours'");
        b.pollNow();
        assertThat(onB.get()).isEqualTo(afterBaseline + 1);

        mp.jdbc().execute("DROP TABLE cache_bus");
        CacheBus c = pod();
        assertThatCode(() -> {
            c.bump("x");
            c.flushPending();
            c.pollNow();
            c.awaitGeneration(5);
        }).doesNotThrowAnyException();
        assertThat(c.status()).containsEntry("table_missing", true);
    }

    @Test
    @DisplayName("Yazdığını oku: iş parçacığı yokken istek beklemez ve kendisi yoklamaz; iş parçacığı TEK zorunlu yoklamayla uygular")
    void minGen_triggersForcedPollOnWorker() throws Exception {
        CacheBus a = pod(), idle = pod();
        AtomicInteger onIdle = new AtomicInteger();
        idle.register("cert-latest", onIdle::incrementAndGet);
        a.pollNow();
        idle.pollNow();
        a.bump("cert-latest");
        a.flushPending();
        long g = a.appliedGeneration();
        long t0 = System.nanoTime();
        assertThat(idle.awaitGeneration(g)).as("iş parçacığı yok → istek iş parçacığı yoklamaz").isFalse();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(50);
        assertThat(onIdle.get()).isZero();

        CacheBus b = livePod();
        AtomicInteger onB = new AtomicInteger();
        b.register("cert-latest", onB::incrementAndGet);
        a.bump("cert-latest");
        a.flushPending();
        long g2 = a.appliedGeneration();
        t0 = System.nanoTime();
        assertThat(b.awaitGeneration(g2)).isTrue();
        assertThat((System.nanoTime() - t0) / 1_000_000).as("en çok min-gen-wait-ms").isLessThan(CacheBus.DEFAULT_MIN_GEN_WAIT_MS);
        assertThat(onB.get()).isEqualTo(1);

        // Kendi yayınının kuşağı: bekleme yok, yoklama yok
        long pollsBefore = (long) a.status().get("polls");
        assertThat(a.awaitGeneration(g2)).isTrue();
        assertThat((long) a.status().get("polls")).isEqualTo(pollsBefore);

        // Var olmayan (sahte büyük) kuşak: istek yine sunulur, en çok bekleme süresi kadar
        t0 = System.nanoTime();
        assertThat(b.awaitGeneration(g2 + 1000)).isFalse();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(CacheBus.DEFAULT_MIN_GEN_WAIT_MS + 400);
    }

    @Test
    @DisplayName("İstek iş parçacığı: aynı istekteki yayınlar TEK güncelleme (iş parçacığı yapar), yanıt kuşağı döner")
    void requestBatch_coalescesViaWorker() throws Exception {
        CacheBus a = livePod();
        ServletRequestAttributes attrs = new ServletRequestAttributes(new MockHttpServletRequest("POST", "/api/x"),
                new MockHttpServletResponse());
        RequestContextHolder.setRequestAttributes(attrs);
        a.bump("cert-latest");
        a.bump("cert-stats", "card-extras");
        Thread.sleep(150);
        assertThat(gen(CacheBus.GLOBAL)).as("yanıt yazılmadan yayın yok").isEqualTo(-1);
        long g = a.flushRequest();
        assertThat(g).isEqualTo(1);
        assertThat(gen("cert-latest")).isEqualTo(1);
        assertThat(gen("card-extras")).isEqualTo(1);
        assertThat(a.flushRequest()).as("tekrar: yeni yayın yok, kuşak aynı").isEqualTo(1);

        a.bump("today-monitors");
        attrs.requestCompleted();
        RequestContextHolder.resetRequestAttributes();
        waitFor(() -> gen("today-monitors") == 1, 3000);
        assertThat(a.openBatchCount()).as("tamamlanan grup açık listeden çıktı").isZero();
    }

    @Test
    @DisplayName("Yazma isteği takılmaz: veritabanı yavaşsa (havuz / kilit) yanıt en çok ~250 ms bekler, başlıksız gider; yayın sonra yapılır")
    void slowDatabase_requestDoesNotStall() throws Exception {
        SlowDataSource ds = new SlowDataSource(mp.dataSource());
        CacheBus a = pod(new JdbcTemplate(ds), new DataSourceTransactionManager(ds));
        a.pollMs = 60_000;
        a.start();
        waitFor(() -> a.appliedGeneration() >= 0, 3000);
        ds.delayMs = 800;
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/api/x"),
                new MockHttpServletResponse()));
        a.bump("cert-latest");
        long t0 = System.nanoTime();
        assertThat(a.flushRequest()).as("kuşak henüz bilinmiyor → başlık yok").isEqualTo(-1);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).as("istek iş parçacığı veritabanını beklemez").isLessThan(CacheBus.REQUEST_FLUSH_WAIT_MS + 200);
        assertThat(a.status().get("header_timeouts")).isEqualTo(1L);
        RequestContextHolder.resetRequestAttributes();
        waitFor(() -> gen("cert-latest") == 1, 20_000);
    }

    @Test
    @DisplayName("İstek tamamlandıktan sonra (süzgeç zincirin dışında) boşaltılan önbellek de yayınlanır — kapalı grup kuyruğa yönlendirir")
    void bumpAfterDispatcherCompleted_isQueued() {
        CacheBus a = pod();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        ServletRequestAttributes outer = new ServletRequestAttributes(req, new MockHttpServletResponse());   // RequestContextFilter
        ServletRequestAttributes inner = new ServletRequestAttributes(req, new MockHttpServletResponse());   // DispatcherServlet
        RequestContextHolder.setRequestAttributes(inner);
        a.bump("card-extras");
        inner.requestCompleted();
        assertThat(a.hasPending()).as("tamamlanan grubun adı kuyruğa").isTrue();
        a.flushPending();
        RequestContextHolder.setRequestAttributes(outer);   // süzgeç: grup özniteliği istekte duruyor ama KAPALI
        a.bump("card-extras");
        assertThat(a.hasPending()).as("kapalı gruba eklenmez → kuyruk").isTrue();
        a.flushPending();
        assertThat(gen("card-extras")).isEqualTo(2);
    }

    @Test
    @DisplayName("Uzun istek: 2 sn'den uzun bekleyen ad, istekte başka yayın olmasa da iş parçacığınca devralınır")
    void longRequest_agedBatchHandedOff() {
        CacheBus a = pod();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("GET", "/api/check/x"),
                new MockHttpServletResponse()));
        a.bump("inactive-recipients");
        assertThat(a.hasPending()).isFalse();
        a.handOffAgedBatches(System.nanoTime());
        assertThat(a.hasPending()).as("2 sn dolmadı").isFalse();
        a.handOffAgedBatches(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CacheBus.REQUEST_HANDOFF_MS + 100));
        assertThat(a.hasPending()).isTrue();
        a.flushPending();
        assertThat(gen("inactive-recipients")).isEqualTo(1);

        CacheBus.RequestBatch b = new CacheBus.RequestBatch(0);
        long h = TimeUnit.MILLISECONDS.toNanos(CacheBus.REQUEST_HANDOFF_MS);
        assertThat(b.add(Set.of("x"), 0, h)).isEqualTo(CacheBus.RequestBatch.Added.KEPT);
        assertThat(b.add(Set.of("y"), h + 1, h)).isEqualTo(CacheBus.RequestBatch.Added.HANDOFF);
        assertThat(b.close()).containsExactlyInAnyOrder("x", "y");
        assertThat(b.add(Set.of("z"), h + 2, h)).isEqualTo(CacheBus.RequestBatch.Added.CLOSED);
    }

    @Test
    @DisplayName("İki pod aynı adı eşzamanlı yayınlar: her yayın sayılır, kilitlenme yok; iki pod da en az bir kez boşaltır")
    void concurrentBumps_twoPods() {
        CacheBus a = pod(), b = pod();
        AtomicInteger onA = new AtomicInteger(), onB = new AtomicInteger();
        a.register("card-extras", onA::incrementAndGet);
        b.register("card-extras", onB::incrementAndGet);
        a.pollNow();
        b.pollNow();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (CacheBus bus : List.of(a, b)) {
            tasks.add(() -> {
                int ok = 0;
                for (int i = 0; i < 20; i++) if (bus.bumpNow(Set.of("card-extras", "cert-latest")) != null) ok++;
                return ok;
            });
        }
        List<Integer> ok = mp.race(tasks);
        assertThat(ok).containsExactly(20, 20);
        assertThat(gen("card-extras")).isEqualTo(40);
        assertThat(gen(CacheBus.GLOBAL)).isEqualTo(40);
        a.pollNow();
        b.pollNow();
        assertThat(onA.get()).as("öteki pod'un yayınları A'ya ulaştı").isGreaterThanOrEqualTo(1);
        assertThat(onB.get()).isGreaterThanOrEqualTo(1);
        assertThat(a.appliedGeneration()).isEqualTo(40);
        assertThat(b.appliedGeneration()).isEqualTo(40);
    }

    @Test
    @DisplayName("Yeni açılan pod: ilk yoklama taban alır; kuruluşundan kısa süre önce yayınlanan adlar boşaltılır, eskiler değil")
    void baseline_onlyRecentNamesEvicted() {
        CacheBus a = pod();
        a.bump("old-cache");
        a.flushPending();
        mp.advance(10 * 60_000);
        a.bump("new-cache");
        a.flushPending();

        CacheBus c = pod();   // createdAtMs = şimdi
        List<String> evicted = new CopyOnWriteArrayList<>();
        c.register("old-cache", () -> evicted.add("old-cache"));
        c.register("new-cache", () -> evicted.add("new-cache"));
        c.pollNow();
        assertThat(evicted).containsExactly("new-cache");
        assertThat(c.appliedGeneration()).isEqualTo(2);
    }

    @Test
    @DisplayName("Geçersiz adlar (boş, uzun, boşluklu) yok sayılır; genel satır adı çağırana kapalı")
    void invalidNames_ignored() {
        CacheBus a = pod();
        a.bump(null, "", "a b", "x".repeat(61), CacheBus.GLOBAL);
        assertThat(a.hasPending()).isFalse();
        assertThat(CacheBusNames.ALL).allMatch(CacheBus::valid).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Kapanış: ContextClosedEvent beklemez ve yoklatmaz; yok etmedeki son yayın sınırlı, son DB işlemi başarısızsa denenmez")
    void shutdown_boundedFinalFlush() throws Exception {
        CacheBus a = pod();
        a.pollNow();
        a.bump("maintenance-windows");
        long t0 = System.nanoTime();
        a.onContextClosed(null);
        assertThat(a.awaitGeneration(999)).isFalse();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(50);
        assertThat(a.hasPending()).as("kapanış dinleyicisi veritabanına gitmez").isTrue();
        a.destroy();
        assertThat(gen("maintenance-windows")).as("yok etmede tek deneme").isEqualTo(1);

        // Yavaş veritabanı: yok etme tavanla sınırlı
        SlowDataSource ds = new SlowDataSource(mp.dataSource());
        CacheBus slow = pod(new JdbcTemplate(ds), new DataSourceTransactionManager(ds));
        slow.pollNow();
        slow.bump("oncall");
        ds.delayMs = 5_000;
        t0 = System.nanoTime();
        slow.destroy();
        assertThat((System.nanoTime() - t0) / 1_000_000).as("SHUTDOWN_FLUSH_CAP_MS ile sınırlı")
                .isLessThan(CacheBus.SHUTDOWN_FLUSH_CAP_MS + 500);

        // Son veritabanı işlemi başarısız: yok etmede denenmez (beklemez)
        CacheBus broken = pod();
        broken.pollNow();
        mp.jdbc().execute("ALTER TABLE cache_bus RENAME TO cache_bus_off");
        broken.bump("quiet-hours");
        broken.flushPending();
        mp.jdbc().execute("ALTER TABLE cache_bus_off RENAME TO cache_bus");
        t0 = System.nanoTime();
        broken.destroy();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(50);
        assertThat(gen("quiet-hours")).isEqualTo(-1);
    }
}
