package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nöbet rutinleri GERÇEK PostgreSQL'de (2026-11-26) — H2'nin göremediği davranışlar: {@code ON CONFLICT DO NOTHING}
 * sahiplenme yarışı (iki oturum → bir kazanan), gün satırı yarışı (tek satır), {@code FOR UPDATE} gün kilidinin ikinci
 * oturumu bekletmesi, gruplu istatistik / gün sorgularının dizin kullanması (asla tablo taraması).
 */
@PostgresIntegration
class OnCallRoutinePostgresTest {

    private static final long WAIT_MS = 15_000;
    private final Long team = 900_000_000L + ThreadLocalRandom.current().nextInt(1_000_000);

    private static JdbcTemplate jdbc() {
        return PostgresIt.app().jdbc();
    }

    private static OnCallRoutineStore store() {
        return PostgresIt.app().bean(OnCallRoutineStore.class);
    }

    @AfterEach
    void cleanup() {
        for (String t : OnCallRoutineTestRig.TABLES) jdbc().update("DELETE FROM " + t + " WHERE team_id = ?", team);
    }

    private static <T> List<T> race(int n, java.util.concurrent.Callable<T> body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier barrier = new CyclicBarrier(n);
        try {
            List<Future<T>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return body.call();
                }));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : fs) out.add(f.get(30, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("sahiplenme yarışı: 6 oturum aynı bildirimi ister → tam bir kazanan, tek satır")
    void noticeClaimRace() throws Exception {
        String at = OnCallRoutineStore.ts(Instant.now());
        List<Long> ids = race(6, () -> store().claim(team, OnCallRoutineCodes.NOTICE_COMPLETE, "2026-11-26:1", OnCallRoutineCodes.N_SENDING, null, null,
                at, "pod"));
        assertThat(ids.stream().filter(java.util.Objects::nonNull).count()).isEqualTo(1);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM oncall_routine_notices WHERE team_id = ?", Integer.class, team))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("gün satırı yarışı: 6 oturum aynı günü açar → tek satır, hata yok")
    void ensureDayRace() throws Exception {
        race(6, () -> {
            store().ensureDay(team, "2026-11-26", "2026-11-23", "2026-11-23", null, null, "NONE", Instant.now());
            return true;
        });
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM oncall_routine_days WHERE team_id = ?", Integer.class, team))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("gün kilidi: FOR UPDATE tutan oturum bitene dek ikinci kilit bekler, sonra güncel satırı görür")
    void dayLockWaits() throws Exception {
        store().ensureDay(team, "2026-11-26", "2026-11-23", "2026-11-23", null, null, "NONE", Instant.now());
        try (Connection a = PostgresIt.app().newSession()) {
            a.setAutoCommit(false);
            try (PreparedStatement ps = a.prepareStatement(
                    "SELECT id FROM oncall_routine_days WHERE team_id = ? AND routine_day = '2026-11-26' FOR UPDATE")) {
                ps.setLong(1, team);
                ps.executeQuery().close();
            }
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<Integer> other = pool.submit(() -> PostgresIt.app().bean(org.springframework.transaction.support
                        .TransactionTemplate.class).execute(st -> store().lockDay(team, "2026-11-26").version()));
                assertThat(waitsOnLock("oncall_routine_days")).as("ikinci oturum kilit bekliyor").isTrue();
                assertThat(other.isDone()).isFalse();
                try (PreparedStatement up = a.prepareStatement(
                        "UPDATE oncall_routine_days SET version = version + 1 WHERE team_id = ? AND routine_day = '2026-11-26'")) {
                    up.setLong(1, team);
                    up.executeUpdate();
                }
                a.commit();
                assertThat(other.get(WAIT_MS, TimeUnit.MILLISECONDS)).as("kilit sonrası güncel sürüm").isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("istatistik / gün / dönem sorguları dizin kullanır (enable_seqscan kapalıyken bile tablo taraması yok)")
    void queriesUseIndexes() throws Exception {
        // Gerçekçi hacim: boş tabloda planlayıcı hangi dizini seçerse seçsin ucuzdur (team_id öncüllü benzersiz dizin + süzgeç
        // da "tarama değil" sayılırdı). 20 görev × 2 yıl → 30 günlük aralık için (team_id, due_day) dizini açıkça ucuz olmalı.
        jdbc().update("INSERT INTO oncall_routine_items (team_id, task_id, section_id, period_kind, period_key, due_day, title, "
                + "section_title, required, frequency, state, note_version, source, version, created_at, updated_at) "
                + "SELECT ?, 't' || t, 's1', 'DAY', to_char(d, 'YYYY-MM-DD'), to_char(d, 'YYYY-MM-DD'), 'Görev', 'Bölüm', TRUE, "
                + "'DAILY', 'DONE', 0, 'USER', 0, '2026-01-01T00:00:00', '2026-01-01T00:00:00' "
                + "FROM generate_series(1, 20) t, generate_series(DATE '2025-01-01', DATE '2026-12-31', INTERVAL '1 day') d", team);
        // Sorunlar: çoğu çözülmüş, %1'i açık; bildirimler: çoğu SENT, birkaçı SENDING (sahiplenme dizinleri seçilmeli)
        jdbc().update("INSERT INTO oncall_routine_problems (team_id, routine_day, severity, status, note, version, created_at) "
                + "SELECT ?, to_char(DATE '2025-01-01' + (g % 700), 'YYYY-MM-DD'), 'LOW', "
                + "CASE WHEN g % 100 = 0 THEN 'OPEN' ELSE 'RESOLVED' END, 'Not', 0, '2026-01-01T00:00:00' "
                + "FROM generate_series(1, 4000) g", team);
        jdbc().update("INSERT INTO oncall_routine_notices (team_id, kind, ref_key, status, attempts, claimed_at, created_at) "
                + "SELECT ?, 'COMPLETE', 'r' || g, CASE WHEN g % 200 = 0 THEN 'SENDING' ELSE 'SENT' END, 1, "
                + "to_char(TIMESTAMP '2025-01-01 09:00:00' + (g || ' hours')::interval, 'YYYY-MM-DD\"T\"HH24:MI:SS'), "
                + "'2025-01-01T00:00:00' FROM generate_series(1, 4000) g", team);
        jdbc().execute("ANALYZE oncall_routine_items");
        jdbc().execute("ANALYZE oncall_routine_problems");
        jdbc().execute("ANALYZE oncall_routine_notices");
        try (Connection c = PostgresIt.app().newSession(); Statement st = c.createStatement()) {
            st.execute("SET enable_seqscan = off");
            String due = plan(st, "SELECT task_id, state, COUNT(*) FROM oncall_routine_items WHERE team_id = " + team
                    + " AND due_day >= '2026-11-01' AND due_day <= '2026-11-30' GROUP BY task_id, state");
            assertThat(due).doesNotContain("Seq Scan on oncall_routine_items").contains("idx_ori_due");
            String period = plan(st, "SELECT id FROM oncall_routine_items WHERE team_id = " + team
                    + " AND period_kind = 'DAY' AND period_key = '2026-11-26'");
            assertThat(period).doesNotContain("Seq Scan on oncall_routine_items").containsAnyOf("idx_ori_period", "uk_ori_task_period");
            String day = plan(st, "SELECT id FROM oncall_routine_days WHERE team_id = " + team + " AND routine_day = '2026-11-26'");
            assertThat(day).doesNotContain("Seq Scan").contains("uk_ord_team_day");
            String claim = plan(st, "SELECT id FROM oncall_routine_notices WHERE team_id = " + team
                    + " AND kind = 'COMPLETE' AND ref_key = 'x'");
            assertThat(claim).doesNotContain("Seq Scan").contains("uk_orn_team_kind_ref");
            // gün aralığı (hafta / takvim / devir / istatistik) ve sorunlar
            String days = plan(st, "SELECT id FROM oncall_routine_days WHERE team_id = " + team
                    + " AND routine_day >= '2026-11-01' AND routine_day <= '2026-11-30' ORDER BY routine_day");
            assertThat(days).doesNotContain("Seq Scan").contains("uk_ord_team_day");
            String probs = plan(st, "SELECT id FROM oncall_routine_problems WHERE team_id = " + team
                    + " AND routine_day >= '2026-11-01' AND routine_day <= '2026-11-30' ORDER BY id DESC LIMIT 51");
            assertThat(probs).doesNotContain("Seq Scan on oncall_routine_problems");
            String open = plan(st, "SELECT COUNT(*) FROM oncall_routine_problems WHERE team_id = " + team + " AND status = 'OPEN'");
            assertThat(open).doesNotContain("Seq Scan").contains("idx_orp_team_status");
            // iş turunun ucuz kapısı ve bildirilmemiş tamamlamalar (tamamlanma zamanı indeksli)
            String pending = plan(st, "SELECT id FROM oncall_routine_days WHERE completed_at >= '2026-11-25T00:00:00' "
                    + "AND completion_seq > notified_seq ORDER BY completed_at LIMIT 200");
            assertThat(pending).doesNotContain("Seq Scan").contains("idx_ord_completed");
            String stale = plan(st, "SELECT id FROM oncall_routine_notices WHERE status = 'SENDING' "
                    + "AND claimed_at < '2026-11-26T00:00:00' ORDER BY id LIMIT 200");
            assertThat(stale).doesNotContain("Seq Scan").contains("idx_orn_status_claimed");
        }
    }

    private static String plan(Statement st, String sql) throws Exception {
        StringBuilder b = new StringBuilder();
        try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) b.append(rs.getString(1)).append('\n');
        }
        return b.toString();
    }

    private static boolean waitsOnLock(String fragment) throws InterruptedException {
        long until = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < until) {
            Integer n = jdbc().queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() "
                            + "AND wait_event_type = 'Lock' AND query ILIKE ?", Integer.class, "%" + fragment + "%");
            if (n != null && n > 0) return true;
            Thread.sleep(50);
        }
        return false;
    }
}
