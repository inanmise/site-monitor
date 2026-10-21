package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.service.schema.DeferredIndexBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

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

/**
 * Olaysız push satırlarının kısmi tekil indeksi GERÇEK PostgreSQL'de (2026-10-21, çok pod — MP-17) —
 * {@code mvn -Ppostgres-it test}. İndeks ertelenmiş kurulumla ({@code DeferredIndexBuilder.CATALOG}, CONCURRENTLY) gelir;
 * burada: tekil + kısmi tanım, olaysız aynı (anahtar, kişi, takım) ikinci kez eklenemez, TEST / taşma özeti / olaylı / kesim
 * öncesi satırlar kapsam dışı, servis yolu ({@code insertEventless}) yarışta "zaten var" der — 8 eşzamanlı yazımdan TEK satır.
 */
@PostgresIntegration
class PushEventlessIndexPostgresTest {

    private static final String INDEX = "ux_push_eventless_key_user";

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static void awaitDeferredIndexes() throws Exception {
        CompletableFuture<DeferredIndexBuilder.Result> run = DeferredIndexBuilder.lastRun();
        if (run != null) run.get(5, TimeUnit.MINUTES);
    }

    private static UserPushDelivery row(String key, String user, Long teamId, Long eventId, String trigger, String createdAt) {
        UserPushDelivery d = new UserPushDelivery();
        d.setAlertEventId(eventId);
        d.setTrigger(trigger);
        d.setDedupeKey(key);
        d.setUsername(user);
        d.setTeamId(teamId);
        d.setStatus("SKIPPED_USER_OPT_OUT");   // outbox'a girmez (bağlamın push işçisi satıra dokunmaz)
        d.setCreatedAt(createdAt);
        return d;
    }

    @Test
    @DisplayName("PostgreSQL: indeks tekil + kısmi; olaysız aynı anahtar/kişi/takım eklenemez, kapsam dışı satırlar serbest")
    void indexShapeAndScope() throws Exception {
        awaitDeferredIndexes();
        JdbcTemplate jdbc = jdbc();
        String def = jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, INDEX);
        assertThat(def).startsWith("CREATE UNIQUE INDEX").contains("alert_event_id IS NULL").contains("COALESCE");
        UserPushDeliveryRepository repo = PostgresIt.app().bean(UserPushDeliveryRepository.class);
        String key = "it-team:" + UUID.randomUUID().toString().substring(0, 8);
        String now = "2026-10-22T10:00:00";
        try {
            repo.save(row(key, "N00001", 5L, null, "WEEKLY_REPORT", now));
            assertThatThrownBy(() -> repo.save(row(key, "N00001", 5L, null, "WEEKLY_REPORT", now)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            repo.save(row(key, "N00001", 6L, null, "WEEKLY_REPORT", now));            // başka takım (fırtına karar satırı)
            repo.save(row(key, "N00002", 5L, null, "WEEKLY_REPORT", now));            // başka kişi
            repo.save(row("TEST:" + key, "N00001", null, null, "TEST", now));          // test: aynı istekte yinelenebilir
            repo.save(row("TEST:" + key, "N00001", null, null, "TEST", now));
            repo.save(row("OVERFLOW:" + key, "N00001", null, null, "OVERFLOW_SUMMARY", now));
            repo.save(row("OVERFLOW:" + key, "N00001", null, null, "OVERFLOW_SUMMARY", now));
            repo.save(row(key, "N00001", 5L, null, "WEEKLY_REPORT", "2026-10-20T10:00:00"));   // kesimden önceki geçmiş
        } finally {
            jdbc.update("DELETE FROM user_push_deliveries WHERE dedupe_key LIKE ?", "%" + key);
        }
    }

    @Test
    @DisplayName("PostgreSQL: 8 pod aynı olaysız bildirimi aynı anda yazar → insertEventless TEK satır ekler, diğerleri 'zaten var'")
    void concurrentInsert_singleRow() throws Exception {
        awaitDeferredIndexes();
        UserPushService svc = PostgresIt.app().bean(UserPushService.class);
        String key = "it-direct:" + UUID.randomUUID().toString().substring(0, 8);
        int pods = 8;
        ExecutorService pool = Executors.newFixedThreadPool(pods);
        try {
            CyclicBarrier start = new CyclicBarrier(pods);
            List<Future<Boolean>> fs = new ArrayList<>();
            for (int p = 0; p < pods; p++) {
                fs.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return svc.insertEventless(row(key, "N00009", 7L, null, "TECH_ERROR_SENT", "2026-10-22T10:00:00"));
                }));
            }
            int inserted = 0;
            for (Future<Boolean> f : fs) if (f.get(30, TimeUnit.SECONDS)) inserted++;
            assertThat(inserted).isEqualTo(1);
            assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM user_push_deliveries WHERE dedupe_key = ?", Integer.class, key))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
            jdbc().update("DELETE FROM user_push_deliveries WHERE dedupe_key = ?", key);
        }
    }
}
