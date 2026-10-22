package com.sitemonitor.service.cache;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.atomic.AtomicInteger;

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
        CacheBus b = new CacheBus(mp.jdbc(), txm);
        b.clock = mp.clockSupplier();
        b.createdAtMs = mp.now();
        buses.add(b);
        return b;
    }

    long gen(String name) {
        List<Long> l = mp.jdbc().queryForList("SELECT generation FROM cache_bus WHERE name = ?", Long.class, name);
        return l.isEmpty() ? -1 : l.get(0);
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
    @DisplayName("Başarısız yoklama bir sonraki turda yeniden dener — arada kaçan yayın kaybolmaz; başarısız yayın kuyrukta kalır")
    void failedPoll_retries_failedBump_requeued() {
        CacheBus a = pod(), b = pod();
        AtomicInteger onB = new AtomicInteger();
        b.register("weak-algo", onB::incrementAndGet);
        a.pollNow();
        b.pollNow();

        mp.jdbc().execute("ALTER TABLE cache_bus RENAME TO cache_bus_off");
        a.bump("weak-algo");
        a.flushPending();
        assertThat(a.hasPending()).as("tablo yok → yayın kuyrukta kalır").isTrue();
        assertThat(b.pollNow()).as("yoklama başarısız, istisna yok").isFalse();

        mp.jdbc().execute("ALTER TABLE cache_bus_off RENAME TO cache_bus");
        a.flushPending();
        assertThat(a.hasPending()).isFalse();
        assertThat(b.pollNow()).isTrue();
        assertThat(onB.get()).isEqualTo(1);
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
    @DisplayName("Yazdığını oku: X-Cache-Min-Gen kuşağı uygulanmamış pod TEK yoklamayla boşaltır (iş parçacığı yok / var)")
    void minGen_triggersSynchronousApply() throws Exception {
        CacheBus a = pod(), b = pod();
        AtomicInteger onB = new AtomicInteger();
        b.register("cert-latest", onB::incrementAndGet);
        a.pollNow();
        b.pollNow();

        a.bump("cert-latest");
        a.flushPending();
        long g = a.appliedGeneration();
        assertThat(b.appliedGeneration()).isLessThan(g);
        assertThat(b.awaitGeneration(g)).as("iş parçacığı yok → satır içi yoklama").isTrue();
        assertThat(onB.get()).isEqualTo(1);

        // İş parçacığı var ama dönemsel yoklama uzak: istek ZORUNLU yoklamayı uyandırır
        b.pollMs = 60_000;
        b.start();
        Thread.sleep(150);   // açılış turu (taban zaten var)
        a.bump("cert-latest");
        a.flushPending();
        long g2 = a.appliedGeneration();
        long t0 = System.nanoTime();
        assertThat(b.awaitGeneration(g2)).isTrue();
        assertThat((System.nanoTime() - t0) / 1_000_000).as("en çok min-gen-wait-ms").isLessThan(CacheBus.DEFAULT_MIN_GEN_WAIT_MS);
        assertThat(onB.get()).isEqualTo(2);

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
    @DisplayName("İstek iş parçacığı: aynı istekteki yayınlar TEK güncelleme; kalan ad istek bitince arka planda")
    void requestBatch_coalescesAndCompletes() {
        CacheBus a = pod();
        a.pollNow();
        ServletRequestAttributes attrs = new ServletRequestAttributes(new MockHttpServletRequest("POST", "/api/x"),
                new MockHttpServletResponse());
        RequestContextHolder.setRequestAttributes(attrs);
        a.bump("cert-latest");
        a.bump("cert-stats", "card-extras");
        assertThat(gen(CacheBus.GLOBAL)).as("yanıt yazılmadan yayın yok").isEqualTo(-1);
        long g = a.flushRequest();
        assertThat(g).isEqualTo(1);
        assertThat(gen("cert-latest")).isEqualTo(1);
        assertThat(gen("card-extras")).isEqualTo(1);
        assertThat(a.flushRequest()).as("tekrar: yeni yayın yok, kuşak aynı").isEqualTo(1);

        a.bump("today-monitors");
        attrs.requestCompleted();
        RequestContextHolder.resetRequestAttributes();
        assertThat(a.hasPending()).as("gövdesiz biten istek → arka plan kuyruğu").isTrue();
        a.flushPending();
        assertThat(gen("today-monitors")).isEqualTo(1);
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
    @DisplayName("Kapanış: bekleyen yayın TEK denemeyle gider; sonrasında bekleme yapılmaz")
    void stop_flushesPending_thenNoWaits() {
        CacheBus a = pod();
        a.pollNow();
        a.bump("maintenance-windows");
        a.onContextClosed(null);
        assertThat(gen("maintenance-windows")).isEqualTo(1);
        long t0 = System.nanoTime();
        assertThat(a.awaitGeneration(999)).isFalse();
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(50);

        // Kapanış dinleyicileri sürerken (ör. bildirim havuzu boşaltması) biten işin boşaltması: yok etmede gönderilir
        a.bump("maintenance-windows");
        assertThat(a.hasPending()).isTrue();
        a.destroy();
        assertThat(gen("maintenance-windows")).isEqualTo(2);
    }
}
