package com.sitemonitor.service;

import com.sitemonitor.service.R34CertRig.Node;
import com.sitemonitor.service.R34CertRig.Opts;
import com.sitemonitor.service.certsweep.CertChunkRunner;
import com.sitemonitor.service.certsweep.CertChunkWorker;
import com.sitemonitor.service.certsweep.CertChunking;
import com.sitemonitor.service.certsweep.CertRunCoordinator;
import com.sitemonitor.service.certsweep.CertSweepStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-4 paket C2 — L-1 İŞ PARÇACIKLARI (tasarım R3-4 §9.1 {@code CertChunksOffThreadsTest}; 3-3'ün
 * {@code DistributedChecksOffThreadsTest} deseni): anahtar açılıştan beri KAPALIYKEN hiçbir {@code cert-chunk-*} (yoklama,
 * koşucular, bırakma) iş parçacığı doğmaz, koordinatör hiçbir şeye dokunmaz.
 *
 * <ul>
 *   <li>GERÇEK Spring bağlamı ({@code test} profili, {@code ShutdownLoggerTest} ile aynı yapılandırma — bağlam önbelleği
 *       paylaşılır): bean'ler kurulu, {@code SchedulerService} koordinatöre bağlı, hazır olayı geçti; koordinatörün
 *       {@code gather}'ı 50 alan adıyla çağrılır → {@code null} (klasik), sayaç / tablo / iş parçacığı değişmez.</li>
 *   <li>Elle kurulmuş üç pod (üretimdeki tembel başlatıcı kayıtları): tam / saatlik / bayat turlar, ayar olayları, kapanış.</li>
 *   <li>Denetim karşıtı: anahtar açılınca aynı düzenekte işçi gerçek yoklama iş parçacığıyla başlar; kapanış durdurur.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CertChunksOffThreadsTest {

    @Autowired
    CertChunking certChunking;
    @Autowired
    CertChunkWorker certChunkWorker;
    @Autowired
    CertRunCoordinator certRunCoordinator;
    @Autowired
    CertSweepStore certSweepStore;
    @Autowired
    CertChunkRunner certChunkRunner;
    @Autowired
    SchedulerService schedulerService;
    @Autowired
    JdbcTemplate jdbc;

    static Set<String> certChunkThreads() {
        Set<String> out = new TreeSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith(CertChunkWorker.RUNNER_PREFIX)) out.add(t.getName());
        }
        return out;
    }

    static void awaitNoCertChunkThreads() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!certChunkThreads().isEmpty() && System.nanoTime() < until) Thread.sleep(20);
    }

    @Test
    @DisplayName("Spring bağlamı: anahtar varsayılan KAPALI → etkinleşme yok, işçi başlamadı, SchedulerService koordinatöre bağlı; "
            + "gather(50 alan adı) → null (klasik), sayaç / tablo / iş parçacığı değişmez")
    void springContext_offSinceBoot_noThreads_gatherIsClassic() throws InterruptedException {
        awaitNoCertChunkThreads();   // başka testin anahtar AÇIK karşı sınaması bitmiş olsun
        assertThat(certChunking.enabled()).as("varsayılan kapalı").isFalse();
        assertThat(certChunking.activated()).isFalse();
        assertThat(certChunkWorker.status().started()).isFalse();
        assertThat(ReflectionTestUtils.getField(schedulerService, "certRunCoordinator"))
                .as("SchedulerService'in parçalı tur alanı bağlı (Spring)").isSameAs(certRunCoordinator);
        assertThat(certChunkRunner).isInstanceOf(SchedulerCertChunks.class);
        assertThat(certSweepStore.tablesReady()).as("açılış yaması (izinli fark)").isTrue();

        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) rows.add(CertSweepGoldenHarness.row(R34CertRig.domain(i), 443, false, "", null, null));
        var before = certRunCoordinator.status();
        CertRunCoordinator.Gathered g = certRunCoordinator.gather(rows, new CertRunCoordinator.Request("abcdef012345",
                "full", true, () -> true, () -> false, 900_000L, () -> 3, () -> 0.5));
        assertThat(g).as("anahtar kapalı: klasik toplama").isNull();
        assertThat(certRunCoordinator.status()).as("kapalıyken sayaç bile değişmez").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cert_sweep_runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cert_sweep_chunks", Integer.class)).isZero();
        assertThat(certChunkThreads()).as("hiçbir cert-chunk iş parçacığı").isEmpty();
    }

    @Test
    @DisplayName("Elle kurulmuş üç pod, anahtar KAPALI: tam / saatlik / bayat turlar + ayar olayları + kapanış — sıfır cert-chunk iş "
            + "parçacığı, sıfır koşu satırı, işçiler başlamadı")
    void handBuilt_offSinceBoot_noThreads() throws InterruptedException {
        awaitNoCertChunkThreads();
        try (R34CertRig rig = new R34CertRig()) {
            rig.switchOn.set(false);
            rig.inventory(60);
            Node a = rig.node(1, new Opts().lazy());
            Node b = rig.node(2, new Opts().lazy());
            Node c = rig.node(3, new Opts().lazy().leader());
            rig.runFull(a);
            rig.mp.advance(3_600_000L);
            rig.runHourly(b);
            rig.mp.advance(600_000L);
            rig.runStale(c);
            rig.settingsChanged();
            rig.steps(30_000L);
            for (Node n : List.of(a, b, c)) {
                assertThat(n.chunking.activated()).isFalse();
                assertThat(n.worker.status().started()).isFalse();
                assertThat(n.coordinator.status().chunkedRuns()).isZero();
                assertThat(n.coordinator.status().classicFallback()).as("kapalıyken sayaç da yok").isZero();
            }
            assertThat(certChunkThreads()).as("anahtar kapalı: hiçbir cert-chunk iş parçacığı").isEmpty();
            for (Node n : List.of(a, b, c)) {
                n.ss().onContextClosed(null);
                n.worker.onContextClosed(null);
                n.chunking.onContextClosed(null);
                n.worker.destroy();
            }
            assertThat(certChunkThreads()).isEmpty();
            assertThat(rig.runRows()).isEmpty();
            assertThat(rig.chunkRows()).isZero();
            assertThat(a.saves() + b.saves() + c.saves()).as("klasik turlar kaydetti (test kör değil)").isEqualTo(180);
        }
    }

    @Test
    @DisplayName("Denetim karşıtı: aynı düzenekte anahtar açılınca başlatıcı koşar — cert-chunk-poll doğar; kapanış durdurur")
    void handBuilt_switchTurnedOn_startsThreads() throws InterruptedException {
        awaitNoCertChunkThreads();
        try (R34CertRig rig = new R34CertRig()) {
            rig.switchOn.set(false);
            rig.inventory(60);
            Node a = rig.node(1, new Opts().lazy());
            assertThat(certChunkThreads()).isEmpty();
            rig.switchOn.set(true);
            rig.settingsChanged();
            assertThat(a.chunking.activated()).isTrue();
            assertThat(a.worker.status().started()).isTrue();
            assertThat(certChunkThreads()).contains(CertChunkWorker.POLL_THREAD);
            a.worker.onContextClosed(null);
            a.worker.destroy();
            awaitNoCertChunkThreads();
            assertThat(certChunkThreads()).as("kapanış iş parçacıklarını durdurdu").isEmpty();
        }
    }
}
