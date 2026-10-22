package com.sitemonitor.service.outage;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Kaynak başına tek açık ağ kesintisi olayı GERÇEK PostgreSQL'de (2026-10-21, çok pod — MP-01) —
 * {@code mvn -Ppostgres-it test}. Eski çiftler temizlenir, kısmi ifade indeksi {@code ux_noe_one_ongoing} kurulur, nişan
 * yazılır; sonra 8 pod aynı anda "açık kayıt yoksa ekle" der → TEK açık satır (ikinci ekleyen tipli
 * {@code DuplicateKeyException} alır, açılış postası atmaz). Paylaşılan bağlamdaki tabloya yazar; sonunda kendi satırlarını
 * kapatır.
 */
@PostgresIntegration
class NetworkOutageIndexPostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    @Test
    @DisplayName("PostgreSQL: çift açık olaylar temizlenir, ux_noe_one_ongoing kurulur, nişan yazılır; ikinci ONGOING eklenemez")
    void dedupeThenIndex_thenSecondOngoingRejected() {
        JdbcTemplate jdbc = jdbc();
        NetworkOutageOngoingDedupe job = PostgresIt.app().bean(NetworkOutageOngoingDedupe.class);
        jdbc.execute("DROP INDEX IF EXISTS " + NetworkOutageOngoingDedupe.INDEX);
        jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", NetworkOutageOngoingDedupe.KEY);
        jdbc.update("UPDATE network_outage_events SET status = 'RESOLVED' WHERE status = 'ONGOING'");
        long cert1 = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, null, "2026-10-20T01:00:00");
        long cert2 = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, "CERT", "2026-10-20T02:00:00");
        long acc1 = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, "IT_ACCESSIBILITY", "2026-10-20T01:00:00");
        long acc2 = NetworkOutageTestSchema.insertLegacyOngoing(jdbc, "IT_ACCESSIBILITY", "2026-10-20T02:00:00");

        assertThat(job.done()).isFalse();
        assertThat(job.runLocked()).isEqualTo(2);

        assertThat(job.indexExists()).isTrue();
        assertThat(job.done()).isTrue();
        String def = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class,
                NetworkOutageOngoingDedupe.INDEX);
        assertThat(def).startsWith("CREATE UNIQUE INDEX").contains("COALESCE").contains("WHERE");
        assertThat(jdbc.queryForList("SELECT id FROM network_outage_events WHERE status = 'ONGOING' AND id IN (?, ?, ?, ?) ORDER BY id",
                Long.class, cert1, cert2, acc1, acc2)).containsExactly(cert2, acc2);
        assertThatThrownBy(() -> NetworkOutageTestSchema.insertLegacyOngoing(jdbc, null, "2026-10-20T03:00:00"))
                .as("NULL kaynak da CERT sayılır — aynı anahtar").isInstanceOf(DuplicateKeyException.class);
        NetworkOutageTestSchema.insertLegacyOngoing(jdbc, "IT_PORT_DOWN", "2026-10-20T03:00:00");   // başka kaynak serbest

        jdbc.update("UPDATE network_outage_events SET status = 'RESOLVED' WHERE status = 'ONGOING'");
    }

    @Test
    @DisplayName("PostgreSQL: 8 pod aynı anda kesinti tespit eder → TEK açık satır, TEK açılış postası adayı")
    void concurrentDetection_singleOngoingRow() throws Exception {
        JdbcTemplate jdbc = jdbc();
        PostgresIt.app().bean(NetworkOutageOngoingDedupe.class).runLocked();   // indeks hazır olsun (idempotent)
        jdbc.update("UPDATE network_outage_events SET status = 'RESOLVED' WHERE status = 'ONGOING' AND (source IS NULL OR source = 'CERT')");
        int pods = 8;
        AppSettingsService settings = NetworkOutageTestSchema.defaultSettings();
        EmailNotificationService email = mock(EmailNotificationService.class);
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        try {
            CyclicBarrier start = new CyclicBarrier(pods);
            List<Future<Long>> fs = new ArrayList<>();
            for (int p = 0; p < pods; p++) {
                CertNetworkOutageService pod = new CertNetworkOutageService(jdbc, email, settings);
                fs.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return pod.insertOngoing("2026-10-21T06:00:00", 8, 10, 0.8, 0.5);
                }));
            }
            int inserted = 0;
            for (Future<Long> f : fs) if (f.get(30, TimeUnit.SECONDS) != null) inserted++;
            assertThat(inserted).as("açılış postasını yalnız EKLEYEN pod atar").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM network_outage_events WHERE status = 'ONGOING' "
                    + "AND (source IS NULL OR source = 'CERT')", Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            jdbc.update("UPDATE network_outage_events SET status = 'RESOLVED' WHERE status = 'ONGOING'");
        }
    }
}
