package com.sitemonitor.service.certsweep;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paket A'nın GERÇEK Spring bağlamındaki kablolaması (2026-10-29, çok pod Sürüm 3-4; {@code test} profili, bağlam önbelleği
 * {@code ShutdownLoggerTest} / {@code DistributedChecksOffThreadsTest} ile paylaşılır): depo, şema yaması ve işçi bean'leri
 * döngüsüz kurulur; açılış yaması iki boş tabloyu kurup depoya "hazır" bildirmiştir; anahtar varsayılan KAPALI → işçi
 * başlamadı, hiçbir {@code cert-chunk-*} iş parçacığı yok, tablolar boş; işçinin durum kaynağı anahtara kayıtlı (bellek).
 */
@SpringBootTest
@ActiveProfiles("test")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CertSweepSpringWiringTest {

    @Autowired
    CertChunking certChunking;
    @Autowired
    CertSweepStore store;
    @Autowired
    CertChunkWorker worker;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Spring: tablolar açılışta kuruldu ve boş, depo hazır; anahtar kapalı → işçi başlamadı, iş parçacığı yok")
    void wiredAndIdleWhileOff() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (CertChunkWorkerTest.anyCertChunkThread() && System.nanoTime() < until) Thread.sleep(20);   // başka testin açığı
        assertThat(certChunking.enabled()).as("varsayılan kapalı").isFalse();
        assertThat(certChunking.activated()).isFalse();
        assertThat(store.tablesReady()).as("açılış yaması bildirdi").isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cert_sweep_runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cert_sweep_chunks", Integer.class)).isZero();
        assertThat(certChunking.workerStatus()).as("işçi durum kaynağı kayıtlı").isNotNull();
        assertThat(certChunking.workerStatus().started()).isFalse();
        assertThat(worker.status().started()).isFalse();
        assertThat(CertChunkWorkerTest.anyCertChunkThread()).isFalse();
    }
}
