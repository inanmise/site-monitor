package com.sitemonitor.service.certsweep;

import com.sitemonitor.config.ShutdownDeadline;
import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.AppSettingsChangedEvent;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.checks.AnchoredDbClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L-1 (2026-10-29, çok pod Sürüm 3-4 paket A, tasarım §9.1 {@code CertChunkWorkerOffTest}): anahtar açılıştan beri KAPALIYKEN
 * depo, şema yaması (açılışta — izin verilen tek fark), anahtar ({@link CertChunking}), işçi ve gerçek veritabanı-çapalı saat
 * kurulur; işçi tembel başlatıcısını ve durum kaynağını kaydeder, hazır olayı / ayar olayları / kapanış / yok etme görülür —
 * veritabanına HİÇ bağlanılmaz (iki tablo dahil hiçbir deyim, saat çapası yok), hiçbir {@code cert-chunk-*} iş parçacığı doğmaz
 * ve {@code @PreDestroy} ortak kapanış süresine ({@code ShutdownDeadline.waitMs}) HİÇ dokunmaz. Karşı-sınama: anahtar açılınca
 * aynı düzenek iş parçacığını başlatır ve tabloyu sorgular (test sahte-yeşil değildir).
 */
class CertChunkWorkerOffTest {

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    final AtomicInteger connections = new AtomicInteger();
    final AtomicBoolean enabled = new AtomicBoolean(false);

    @AfterEach
    void close() throws Exception {
        ShutdownDeadline.activate(null);
        long deadline = System.currentTimeMillis() + 7_000;
        while (CertChunkWorkerTest.anyCertChunkThread() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        mp.close();
    }

    private record Beans(CertChunking cc, CertChunkWorker worker, AnchoredDbClock clock, JdbcCertSweepStore store) {}

    private Beans wire() {
        JdbcTemplate counting = new JdbcTemplate(CertSweepSqlH2Test.countingConnections(mp.dataSource(), connections));
        AnchoredDbClock clock = new AnchoredDbClock(counting);
        JdbcCertSweepStore store = new JdbcCertSweepStore(counting, clock);
        new CertSweepSchemaPatch(mp.jdbc(), store).apply();   // açılış yaması: izin verilen tek fark — sayılmadan önce
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> CertChunking.KEY_ENABLED.equals(i.getArgument(0))
                ? enabled.get() : i.getArgument(1));
        CertChunking cc = new CertChunking(settings, CertChunking.Tuning.DEFAULTS);
        CertChunkWorkerTest.FakeRunner runner = new CertChunkWorkerTest.FakeRunner();
        CertChunkWorker worker = new CertChunkWorker(store, cc, () -> runner, () -> true, () -> true, "pod1-00000001",
                System::nanoTime);
        worker.register();
        return new Beans(cc, worker, clock, store);
    }

    @Test
    @DisplayName("Anahtar kapalı: hazır + ayar olayları + kapanış + yok etme — sıfır bağlantı, sıfır cert-chunk iş parçacığı, ortak süreye dokunulmaz")
    void offMeansNoSqlNoThreadsNoDeadline() throws Exception {
        Beans b = wire();
        connections.set(0);
        b.cc().onReady(null);
        b.cc().onSettingsChanged(new AppSettingsChangedEvent(Set.of(CertChunking.KEY_ENABLED), "save"));
        b.cc().onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));
        b.worker().poke();
        Thread.sleep(300);
        assertThat(b.cc().activated()).isFalse();
        assertThat(CertChunkWorkerTest.anyCertChunkThread()).as("hiç iş parçacığı yok").isFalse();
        CertSweepStatus.Worker st = b.cc().workerStatus();
        assertThat(st).as("durum kaynağı kayıtlı (bellek)").isNotNull();
        assertThat(st.started()).isFalse();
        assertThat(st.claimed()).isZero();

        ShutdownDeadline d = new ShutdownDeadline();
        ShutdownDeadline.activate(d);
        b.worker().onContextClosed(null);
        b.cc().onContextClosed(null);
        b.worker().destroy();
        Thread.sleep(100);
        long probe = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
        assertThat(d.remainingMs(ShutdownDeadline.DESTROY_BUDGET_MS, probe))
                .as("ortak yok etme süresi HİÇ başlatılmadı (başlasaydı ~1 sn kalırdı)").isEqualTo(ShutdownDeadline.DESTROY_BUDGET_MS);
        assertThat(connections).as("anahtar kapalı — hiçbir SQL deyimi (iki tablo dahil)").hasValue(0);
        assertThat(CertChunkWorkerTest.anyCertChunkThread()).isFalse();
        assertThat(b.clock().anchors()).as("DB saati hiç çapalanmadı").isZero();
        assertThat(rows("cert_sweep_runs")).isZero();
        assertThat(rows("cert_sweep_chunks")).isZero();
    }

    @Test
    @DisplayName("Karşı-sınama: anahtar açılınca başlatıcı koşar, yoklama iş parçacığı doğar, tablo sorgulanır; kapanış durdurur")
    void onStartsThreadAndQueries() throws Exception {
        Beans b = wire();
        b.cc().onReady(null);
        assertThat(b.cc().activated()).isFalse();
        connections.set(0);
        enabled.set(true);
        b.cc().onSettingsChanged(new AppSettingsChangedEvent(Set.of(CertChunking.KEY_ENABLED), "save"));
        assertThat(b.cc().activated()).isTrue();
        long deadline = System.currentTimeMillis() + 5_000;
        while (connections.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(connections.get()).as("sahiplenme sorgusu").isPositive();
        assertThat(Thread.getAllStackTraces().keySet()).extracting(Thread::getName).contains(CertChunkWorker.POLL_THREAD);
        assertThat(b.cc().workerStatus().started()).isTrue();
        b.worker().onContextClosed(null);
        ShutdownDeadline d = new ShutdownDeadline();
        d.remainingMs(0, System.nanoTime() - TimeUnit.SECONDS.toNanos(30));   // ortak süre bitmiş
        ShutdownDeadline.activate(d);
        b.worker().destroy();
        deadline = System.currentTimeMillis() + 7_000;
        while (CertChunkWorkerTest.anyCertChunkThread() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(CertChunkWorkerTest.anyCertChunkThread()).isFalse();
    }

    private int rows(String table) {
        Integer n = mp.jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
