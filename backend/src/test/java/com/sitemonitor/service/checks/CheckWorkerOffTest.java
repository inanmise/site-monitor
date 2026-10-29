package com.sitemonitor.service.checks;

import com.sitemonitor.config.ShutdownDeadline;
import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.AppSettingsChangedEvent;
import com.sitemonitor.service.AppSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * K-1 (2026-10-29, çok pod Sürüm 3-3 paket A): ana anahtar KAPALIYKEN işçi, işçi kaydı, kuyruk ve DB saati bean'leri kurulur,
 * tembel başlatıcıları kaydeder, hazır olayı / ayar olayı / kapanış / yok etme görür — veritabanına HİÇ bağlanmaz (iki tablo
 * dahil hiçbir deyim) ve hiçbir {@code check-worker*} iş parçacığı doğmaz. Karşı-sınama: anahtar açılınca aynı düzenek iş
 * parçacıklarını başlatır ve tabloya yazar (test sahte-yeşil değildir).
 */
class CheckWorkerOffTest {

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    final AtomicInteger connections = new AtomicInteger();
    final AtomicBoolean enabled = new AtomicBoolean(false);

    @AfterEach
    void close() throws Exception {
        ShutdownDeadline.activate(null);
        long deadline = System.currentTimeMillis() + 7_000;
        while (CheckWorkerRegistryTest.anyCheckWorkerThread() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        mp.close();
    }

    /** Bağlantı açılışlarını sayan veri kaynağı (her SQL deyimi bir bağlantı ister). */
    private DataSource counting(DataSource real) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) connections.incrementAndGet();
                    try {
                        return method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private record Beans(DistributedChecks dc, CheckWorkerRegistryImpl registry, CheckWorker worker, AnchoredDbClock clock) {}

    private Beans wire() {
        new CheckJobSchemaPatch(mp.jdbc()).apply();   // açılış yaması: izin verilen tek fark (boş tablolar) — sayılmadan önce
        JdbcTemplate jdbc = new JdbcTemplate(counting(mp.dataSource()));
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(eq(DistributedChecks.KEY_ENABLED), anyBoolean())).thenAnswer(inv -> enabled.get());
        when(settings.getString(eq(DistributedChecks.KEY_TYPES), anyString())).thenReturn(CheckJobTypes.DEFAULT_CSV);
        DistributedChecks dc = new DistributedChecks(settings, DistributedChecks.Tuning.DEFAULTS);
        AnchoredDbClock clock = new AnchoredDbClock(jdbc);
        JdbcCheckJobStore store = new JdbcCheckJobStore(jdbc, clock);
        CheckWorkerRegistryImpl registry = new CheckWorkerRegistryImpl(jdbc, clock, dc, "pod1-00000001", () -> true,
                () -> true, () -> true, () -> true, () -> true, () -> true,
                () -> CheckWorkerRegistryImpl.LocalStats.IDLE, () -> "v", System::nanoTime);
        CheckJobRunner runner = (t, id) -> Map.of("ok", true);
        CheckWorker worker = new CheckWorker(store, registry, dc, () -> runner, () -> true, () -> false, () -> 0, clock,
                "pod1-00000001", System::nanoTime);
        registry.register();
        worker.register();
        return new Beans(dc, registry, worker, clock);
    }

    @Test
    @DisplayName("Anahtar kapalı: hazır + ayar olayı + kapanış + yok etme — sıfır bağlantı, sıfır check-worker iş parçacığı")
    void offMeansNoSqlAndNoThreads() throws Exception {
        Beans b = wire();
        connections.set(0);
        b.dc().onReady(null);
        b.dc().onSettingsChanged(new AppSettingsChangedEvent(Set.of(DistributedChecks.KEY_ENABLED), "save"));
        b.dc().onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));
        Thread.sleep(300);
        assertThat(b.dc().activated()).isFalse();
        assertThat(CheckWorkerRegistryTest.anyCheckWorkerThread()).as("hiç iş parçacığı yok").isFalse();
        assertThat(b.registry().liveOtherWorkers(CheckJobTypes.PORT)).isEqualTo(CheckWorkerRegistry.UNKNOWN);
        assertThat(b.registry().workers()).isEmpty();
        assertThat(b.worker().status().started()).isFalse();
        b.worker().onContextClosed(null);
        b.registry().onContextClosed(null);
        b.worker().destroy();
        Thread.sleep(100);
        assertThat(connections).as("anahtar kapalı — hiçbir SQL deyimi (iki tablo dahil)").hasValue(0);
        assertThat(CheckWorkerRegistryTest.anyCheckWorkerThread()).isFalse();
        assertThat(b.clock().anchors()).as("DB saati hiç çapalanmadı").isZero();
        assertThat(rows("monitor_check_workers")).isZero();
        assertThat(rows("monitor_check_jobs")).isZero();
    }

    @Test
    @DisplayName("Karşı-sınama: anahtar açılınca başlatıcılar koşar, iş parçacıkları doğar, nabız satırı yazılır; kapanış durdurur")
    void onStartsThreadsAndWrites() throws Exception {
        Beans b = wire();
        b.dc().onReady(null);
        assertThat(b.dc().activated()).isFalse();
        enabled.set(true);
        b.dc().onSettingsChanged(new AppSettingsChangedEvent(Set.of(DistributedChecks.KEY_ENABLED), "save"));
        assertThat(b.dc().activated()).isTrue();
        long deadline = System.currentTimeMillis() + 5_000;
        while (rows("monitor_check_workers") == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(rows("monitor_check_workers")).isEqualTo(1);
        assertThat(CheckWorkerRegistryTest.threadNames()).contains(CheckWorker.POLL_THREAD, CheckWorkerRegistryImpl.THREAD);
        assertThat(connections.get()).isPositive();
        b.worker().onContextClosed(null);
        b.registry().onContextClosed(null);
        ShutdownDeadline d = new ShutdownDeadline();
        d.remainingMs(0, System.nanoTime() - TimeUnit.SECONDS.toNanos(30));
        ShutdownDeadline.activate(d);
        b.worker().destroy();
        deadline = System.currentTimeMillis() + 7_000;
        while (CheckWorkerRegistryTest.anyCheckWorkerThread() && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertThat(CheckWorkerRegistryTest.anyCheckWorkerThread()).isFalse();
    }

    private int rows(String table) {
        Integer n = mp.jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
