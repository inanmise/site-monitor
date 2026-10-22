package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.DomainExpiryReminder;
import com.sitemonitor.repository.DomainExpiryReminderRepository;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Alan adı hatırlatmasının kısmi tekil indeksi GERÇEK PostgreSQL'de (2026-10-23, çok pod R2a · D2 — MP-45) —
 * {@code mvn -Ppostgres-it test}: tekil + kısmi tanım; aynı (izleme, bitiş, eşik) kesim sonrası ikinci kez eklenemez, kesim
 * öncesi geçmiş satırlar serbest; 8 eşzamanlı sahiplenmeden TEK satır; çağıranın işlemi içindeki ihlal o işlemi BOZMAZ
 * (ayrı işlem — 25P02 yok).
 */
@PostgresIntegration
class DomainExpiryReminderPostgresTest {

    private static final String INDEX = "ux_der_mon_exp_thr";

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static void awaitDeferredIndexes() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);
    }

    private static DomainExpiryReminder row(long monitorId, String expiry, int threshold, String sentAt) {
        DomainExpiryReminder r = new DomainExpiryReminder();
        r.setMonitorId(monitorId);
        r.setExpiryDate(expiry);
        r.setThresholdDays(threshold);
        r.setDaysRemaining(5);
        r.setStatus(DomainExpiryReminderService.STATUS_SENDING);
        r.setSentAt(sentAt);
        return r;
    }

    @Test
    @DisplayName("PostgreSQL: indeks tekil + kısmi; kesim sonrası aynı anahtar eklenemez, kesim öncesi geçmiş serbest")
    void indexShapeAndScope() throws Exception {
        awaitDeferredIndexes();
        String def = jdbc().queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, INDEX);
        assertThat(def).startsWith("CREATE UNIQUE INDEX").contains("monitor_id, expiry_date, threshold_days").contains("2026-10-23");
        DomainExpiryReminderRepository repo = PostgresIt.app().bean(DomainExpiryReminderRepository.class);
        long mid = 900_000L + (System.nanoTime() % 10_000);
        try {
            repo.save(row(mid, "2027-01-01T00:00:00Z", 7, "2026-10-23T10:00:00"));
            assertThatThrownBy(() -> repo.save(row(mid, "2027-01-01T00:00:00Z", 7, "2026-10-24T10:00:00")))
                    .isInstanceOf(DataIntegrityViolationException.class);
            repo.save(row(mid, "2027-01-01T00:00:00Z", 14, "2026-10-23T10:00:00"));   // başka eşik
            repo.save(row(mid, "2028-01-01T00:00:00Z", 7, "2026-10-23T10:00:00"));    // yenilendi: yeni bitiş
            repo.save(row(mid, "2026-11-01T00:00:00Z", 3, "2026-10-20T10:00:00"));    // kesim öncesi geçmiş …
            repo.save(row(mid, "2026-11-01T00:00:00Z", 3, "2026-10-21T10:00:00"));    // … eski çift indekse girmez
        } finally {
            jdbc().update("DELETE FROM domain_expiry_reminders WHERE monitor_id = ?", mid);
        }
    }

    @Test
    @DisplayName("PostgreSQL: 8 pod aynı eşiği aynı anda sahiplenir → TEK satır; işlem içindeki ihlal çağıranın işlemini bozmaz")
    void concurrentClaims_singleRow_callerTransactionSurvives() throws Exception {
        awaitDeferredIndexes();
        DomainExpiryReminderService svc = PostgresIt.app().bean(DomainExpiryReminderService.class);
        long mid = 910_000L + (System.nanoTime() % 10_000);
        int pods = 8;
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        try {
            CyclicBarrier start = new CyclicBarrier(pods);
            List<Future<Boolean>> fs = new ArrayList<>();
            for (int p = 0; p < pods; p++) {
                fs.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return svc.insertClaim(row(mid, "2027-02-01T00:00:00Z", 7, "2026-10-23T11:00:00"));
                }));
            }
            int won = 0;
            for (Future<Boolean> f : fs) if (f.get(30, TimeUnit.SECONDS)) won++;
            assertThat(won).isEqualTo(1);

            TransactionTemplate tt = new TransactionTemplate(PostgresIt.app().bean(PlatformTransactionManager.class));
            Integer afterViolation = tt.execute(st -> {
                boolean again = svc.insertClaim(row(mid, "2027-02-01T00:00:00Z", 7, "2026-10-23T11:00:01"));
                assertThat(again).isFalse();
                return jdbc().queryForObject("SELECT COUNT(*) FROM domain_expiry_reminders WHERE monitor_id = ?", Integer.class, mid);
            });
            assertThat(afterViolation).as("çağıranın işlemi ihlalden sonra da sorgu koşabiliyor (25P02 yok)").isEqualTo(1);
        } finally {
            pool.shutdownNow();
            jdbc().update("DELETE FROM domain_expiry_reminders WHERE monitor_id = ?", mid);
        }
    }
}
