package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Çok pod R2a (MP-38) gerçek PostgreSQL'de: iki {@link AuditService} örneği ("iki pod", ayrı JVM kilitleri) işlem İÇİNDE
 * ve işlem DIŞINDA aynı anda denetim yazar → her satırın seq'i tekil, zincir sağlam (işlem içindeki yazım COMMIT sonrası
 * advisory lock altında). {@code ux_audit_seq} tekil + kısmi: kesimden sonraki aynı seq eklenemez, kesim öncesi serbest.
 * {@code mvn -Ppostgres-it test}.
 */
@PostgresIntegration
class AuditChainPostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static void awaitDeferredIndexes() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);
    }

    private static AuditService pod() {
        AuditService s = new AuditService(PostgresIt.app().bean(AuditLogRepository.class), mock(GeoIpService.class),
                mock(NewDeviceNotifier.class), mock(ClientIpResolver.class), mock(AuditGeoEnricher.class));
        s.setChainLockSupport(jdbc(), PostgresIt.app().bean(PlatformTransactionManager.class));
        return s;
    }

    @Test
    @DisplayName("PostgreSQL: ux_audit_seq tekil + kısmi; iki pod işlem içi / dışı aynı anda yazar → tekil seq, zincir sağlam")
    void uniqueSeqIndex_andTwoPodsInterleaving_chainValid() throws Exception {
        awaitDeferredIndexes();
        JdbcTemplate jdbc = jdbc();
        String def = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_audit_seq'", String.class);
        assertThat(def).startsWith("CREATE UNIQUE INDEX").contains("(seq)").contains("2026-10-23");

        // Dizin kapsamı: NEGATİF seq (zincirin ucunu — MAX(seq) — etkilemez; uygulamanın eşzamanlı yazımları bozulmaz).
        long probe = -1_000_000L - (UUID.randomUUID().getMostSignificantBits() & 0xFFFFF);
        String ins = "INSERT INTO audit_log (seq, event_type, event_time, actor, outcome) VALUES (?, 'IT_PROBE', ?, 'it', 'SUCCESS')";
        try {
            jdbc.update(ins, probe, "2026-10-23T00:00:01");
            assertThatThrownBy(() -> jdbc.update(ins, probe, "2026-10-24T00:00:01"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update(ins, probe - 1, "2026-10-20T00:00:01");
            jdbc.update(ins, probe - 1, "2026-10-21T00:00:01");   // kesim öncesi (eski olası çatallar) serbest
        } finally {
            jdbc.update("DELETE FROM audit_log WHERE event_type = 'IT_PROBE' AND seq < 0");
        }

        AuditService a = pod();
        AuditService b = pod();
        TransactionTemplate tt = new TransactionTemplate(PostgresIt.app().bean(PlatformTransactionManager.class));
        String tag = "it-chain-" + UUID.randomUUID().toString().substring(0, 8);
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            CyclicBarrier start = new CyclicBarrier(writers);
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                final int n = i;
                fs.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    AuditService svc = n % 2 == 0 ? a : b;
                    for (int k = 0; k < 5; k++) {
                        if ((n + k) % 2 == 0) {
                            tt.executeWithoutResult(st -> {
                                svc.recordSystemEvent("IT_CHAIN_TX", "SYSTEM", tag, "pod" + (n % 2));
                                try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            });
                        } else {
                            svc.recordSystemEvent("IT_CHAIN", "SYSTEM", tag, "pod" + (n % 2));
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE resource_id = ?", Integer.class, tag);
        Integer distinct = jdbc.queryForObject("SELECT COUNT(DISTINCT seq) FROM audit_log WHERE resource_id = ?", Integer.class, tag);
        assertThat(rows).isEqualTo(writers * 5);
        assertThat(distinct).as("seq'ler tekil").isEqualTo(rows);
        AuditService.ChainVerification v = a.verifyChain();
        assertThat(v.ok()).as("zincir sağlam: %s", v).isTrue();
    }
}
