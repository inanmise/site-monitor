package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.repository.SqlQueryHistoryRepository;
import com.sitemonitor.service.sqlplayground.SensitiveColumnException;
import com.sitemonitor.service.sqlplayground.SqlParams;
import com.sitemonitor.service.sqlplayground.SqlRunRegistry;
import com.sitemonitor.service.sqlplayground.SqlSensitiveColumns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * SQL Playground'un PostgreSQL'e özgü yolları GERÇEK motorda (2026-11-13) — {@code mvn -Ppostgres-it test}:
 * pgjdbc taban kolon bilgisiyle kökene göre maskeleme (takma ad, {@code _capped} sarmalayıcı alt sorgusu, JOIN), salt-okunur
 * işlem (veritabanı yazmayı reddeder), çalıştırma etiketi, EXPLAIN JSON özeti, başka pod'dan {@code pg_cancel_backend}
 * ile durdurma, imleçli dışa aktarım ve {@code ?} operatörlü parametreli sorgu.
 */
@PostgresIntegration
class SqlPlaygroundPostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static SqlPlaygroundService service(SqlRunRegistry reg) {
        SqlPlaygroundService s = new SqlPlaygroundService(jdbc(), PostgresIt.app().bean(SqlQueryHistoryRepository.class));
        if (reg != null) s.setRunRegistry(reg);
        return s;
    }

    private static SqlPlaygroundService.QueryRequest q(String sql) {
        return new SqlPlaygroundService.QueryRequest(sql, Map.of(), null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.get("rows");
    }

    @Test
    @DisplayName("A1: takma ad + _capped alt sorgusu + JOIN üzerinden köken izlenir — takma adlı password_hash maskeli")
    void aliasIsMaskedByOrigin() {
        String user = "it-sqlpg-" + System.nanoTime();
        jdbc().update("INSERT INTO remember_me_tokens (token, username, expires_at) VALUES (?, ?, 0)",
                "tok-" + System.nanoTime(), user);
        try {
            Map<String, Object> r = service(null).execute(
                    "SELECT r.username, r.token AS z, t.name AS team_name FROM remember_me_tokens r "
                            + "LEFT JOIN teams t ON t.name = r.username WHERE r.username = '" + user + "'", "postgres-it");
            assertThat(r.get("ok")).as("hata: %s", r.get("error")).isEqualTo(true);
            assertThat(rows(r)).hasSize(1);
            assertThat(rows(r).get(0).get("username")).isEqualTo(user);
            assertThat(rows(r).get(0).get("z")).isEqualTo(SqlSensitiveColumns.MASK);
            @SuppressWarnings("unchecked") List<String> masked = (List<String>) r.get("masked_columns");
            assertThat(masked).containsExactly("z");
        } finally {
            jdbc().update("DELETE FROM remember_me_tokens WHERE username = ?", user);
        }
    }

    @Test
    @DisplayName("A1: PostgreSQL rol parola özeti (pg_authid.rolpassword) takma adla da maskelenir — okuma yetkisi varsa")
    void roleCatalogPasswordMasked() {
        Map<String, Object> r = service(null).execute("SELECT rolname, rolpassword AS p FROM pg_catalog.pg_authid", "postgres-it");
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.TRUE.equals(r.get("ok")),
                "test kullanıcısı pg_authid okuyamıyor (süper kullanıcı değil): " + r.get("error"));
        for (Map<String, Object> row : rows(r)) {
            if (row.get("p") != null) assertThat(row.get("p")).isEqualTo(SqlSensitiveColumns.MASK);
        }
        @SuppressWarnings("unchecked") List<String> masked = (List<String>) r.get("masked_columns");
        assertThat(masked).containsExactly("p");
        assertThatThrownBy(() -> service(null).execute("SELECT md5(rolpassword) FROM pg_authid", "postgres-it"))
                .isInstanceOf(SensitiveColumnException.class);
    }

    @Test
    @DisplayName("Aynı adlı kolonlar tekilleştirilir: iki değer de taşınır, columns + renamed_columns; dışa aktarım başlığı aynı")
    void duplicateColumnNamesKeepBothValues() {
        Map<String, Object> r = service(null).execute("SELECT a.n AS id, b.n * 10 AS id, 'x' AS name FROM (VALUES (1), (2)) a(n) "
                + "JOIN (VALUES (1), (2)) b(n) ON a.n = b.n ORDER BY 1", "postgres-it");
        assertThat(r.get("ok")).as("hata: %s", r.get("error")).isEqualTo(true);
        assertThat(r.get("columns")).isEqualTo(List.of("id", "id_2", "name"));
        assertThat(r.get("renamed_columns")).isEqualTo(List.of(Map.of("original", "id", "name", "id_2")));
        assertThat(((Number) rows(r).get(0).get("id")).intValue()).isEqualTo(1);
        assertThat(((Number) rows(r).get(0).get("id_2")).intValue()).isEqualTo(10);
        assertThat(((Number) rows(r).get(1).get("id_2")).intValue()).isEqualTo(20);

        String[][] labels = new String[1][];
        SqlPlaygroundService.RunOutcome o = service(null).export(q("SELECT 1 AS id, 2 AS id"), 10, "postgres-it",
                new SqlPlaygroundService.RowSink() {
                    @Override public void columns(String[] l) { labels[0] = l; }
                    @Override public boolean row(Object[] v) { return true; }
                });
        assertThat(o.ok()).as("hata: %s", o.error()).isTrue();
        assertThat(labels[0]).containsExactly("id", "id_2");
    }

    @Test
    @DisplayName("Tekilleştirilmiş adla da maske: aynı hassas kolon iki kez seçilince ikisi de maskeli, masked_columns tekil adlarla")
    void duplicateSensitiveColumnsMasked() {
        String user = "it-sqlpg-dup-" + System.nanoTime();
        jdbc().update("INSERT INTO remember_me_tokens (token, username, expires_at) VALUES (?, ?, 0)", "tok-d-" + System.nanoTime(), user);
        try {
            Map<String, Object> r = service(null).execute("SELECT r.token, r.token, r.username FROM remember_me_tokens r "
                    + "WHERE r.username = '" + user + "'", "postgres-it");
            assertThat(r.get("ok")).as("hata: %s", r.get("error")).isEqualTo(true);
            assertThat(r.get("columns")).isEqualTo(List.of("token", "token_2", "username"));
            assertThat(rows(r).get(0).get("token")).isEqualTo(SqlSensitiveColumns.MASK);
            assertThat(rows(r).get(0).get("token_2")).isEqualTo(SqlSensitiveColumns.MASK);
            assertThat(r.get("masked_columns")).isEqualTo(List.of("token", "token_2"));
        } finally {
            jdbc().update("DELETE FROM remember_me_tokens WHERE username = ?", user);
        }
    }

    @Test
    @DisplayName("A1: ifade içinde hassas kolon PostgreSQL'de de çalıştırılmadan reddedilir")
    void sensitiveExpressionRejected() {
        assertThatThrownBy(() -> service(null).execute(
                "SELECT username FROM app_users WHERE password_hash LIKE '$2%'", "postgres-it"))
                .isInstanceOf(SensitiveColumnException.class);
        assertThatThrownBy(() -> service(null).execute("SELECT to_jsonb(u) FROM app_users u", "postgres-it"))
                .isInstanceOf(SensitiveColumnException.class);
        // Satır karşılaştırma kâhini (inceleme YÜKSEK 1): hiçbir hassas kolon adı geçmeden bütün satır değer olur
        assertThatThrownBy(() -> service(null).execute(
                "SELECT 1 WHERE ROW(1, 'admin') < (SELECT * FROM app_users WHERE id = 1)", "postgres-it"))
                .isInstanceOf(SensitiveColumnException.class);
        assertThatThrownBy(() -> service(null).execute(
                "SELECT 1 WHERE ROW('a', 'b') < (TABLE spring_session)", "postgres-it"))
                .isInstanceOf(SensitiveColumnException.class);
    }

    @Test
    @DisplayName("Zaman biçimleri: date → yyyy-MM-dd, timestamp → yerel ISO (kaymasız), timestamptz → UTC …Z")
    void timeFormats() {
        Map<String, Object> r = service(null).execute("SELECT DATE '2026-11-13' AS d, TIMESTAMP '2026-11-13 10:05:00' AS ts, "
                + "TIMESTAMP '2026-11-13 10:05:00.123456' AS tsu, TIMESTAMPTZ '2026-11-13 10:05:00+03' AS tz", "postgres-it");
        assertThat(r.get("ok")).as("hata: %s", r.get("error")).isEqualTo(true);
        Map<String, Object> row = rows(r).get(0);
        assertThat(row.get("d")).isEqualTo("2026-11-13");
        assertThat(row.get("ts")).isEqualTo("2026-11-13T10:05:00");
        assertThat(row.get("tsu")).isEqualTo("2026-11-13T10:05:00.123456");
        assertThat(row.get("tz")).isEqualTo("2026-11-13T07:05:00.000Z");
    }

    @Test
    @DisplayName("İnceleme DÜŞÜK 4: veritabanında standard_conforming_strings kapalı olsa da işlem boyunca açık (havuz dışı dışa aktarım dahil)")
    void standardConformingStringsForced() {
        String db = jdbc().queryForObject("SELECT current_database()", String.class);
        try {
            jdbc().execute("ALTER DATABASE \"" + db.replace("\"", "\"\"") + "\" SET standard_conforming_strings = off");
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.abort("veritabanı ayarı değiştirilemiyor: " + e.getMessage());
        }
        try {
            List<Object[]> got = new ArrayList<>();
            SqlPlaygroundService.RunOutcome o = service(null).export(
                    q("SELECT current_setting('standard_conforming_strings') AS s"), 10, "postgres-it",
                    new SqlPlaygroundService.RowSink() {
                        @Override public void columns(String[] l) { }
                        @Override public boolean row(Object[] v) { got.add(v); return true; }
                    });
            assertThat(o.ok()).as("hata: %s", o.error()).isTrue();
            assertThat(got.get(0)[0]).isEqualTo("on");
        } finally {
            jdbc().execute("ALTER DATABASE \"" + db.replace("\"", "\"\"") + "\" RESET standard_conforming_strings");
        }
    }

    @Test
    @DisplayName("İnceleme ORTA 2 / canlı A: dışa aktarım İKİNCİ getirmede bloklanmışken Durdur → sorgu kesilir, durduruldu, pg_stat_activity'den çıkar")
    void exportCancelWhileBlockedInFetch() throws Exception {
        SqlRunRegistry reg = new SqlRunRegistry(jdbc(), "it-pod-x", "pod-x");
        SqlPlaygroundService a = service(reg);
        String runId = "it-exp-cancel-" + System.nanoTime();
        java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch firstBatch = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<SqlPlaygroundService.RunOutcome> f = CompletableFuture.supplyAsync(() -> a.export(
                new SqlPlaygroundService.QueryRequest(
                        "SELECT g FROM (SELECT generate_series(1, 400000000) AS g) s WHERE g <= 600 OR md5(g::text) = 'x'",
                        Map.of(), runId),
                50_000, "alice", new SqlPlaygroundService.RowSink() {
                    @Override public void columns(String[] l) { }
                    @Override public boolean row(Object[] v) {
                        if (seen.incrementAndGet() == SqlPlaygroundService.EXPORT_FETCH_SIZE) firstBatch.countDown();
                        return true;
                    }
                }));
        assertThat(firstBatch.await(60, TimeUnit.SECONDS)).as("ilk getirme gelmedi").isTrue();
        Thread.sleep(500);   // ikinci getirme (yavaş tarama) başlasın
        long t0 = System.currentTimeMillis();
        assertThat(a.cancel(runId, "alice").cancelled()).isTrue();
        SqlPlaygroundService.RunOutcome o = f.get(30, TimeUnit.SECONDS);
        assertThat(o.ok()).isFalse();
        assertThat(o.cancelled()).as("sonuç: %s", o.error()).isTrue();
        assertThat(System.currentTimeMillis() - t0).isLessThan(15_000);
        assertThat(seen.get()).isLessThan(50_000);
        long until = System.currentTimeMillis() + 5_000;
        long active;
        do {
            active = jdbc().queryForObject("SELECT COUNT(*) FROM pg_stat_activity WHERE application_name = ? AND state = 'active'",
                    Long.class, SqlRunRegistry.appNameOf(runId));
            if (active > 0) Thread.sleep(100);
        } while (active > 0 && System.currentTimeMillis() < until);
        assertThat(active).isZero();
    }

    @Test
    @DisplayName("İnceleme ORTA 2: dışa aktarım okuma döngüsündeyken (satırlar arası) Durdur → bir sonraki satırda kesilir")
    void exportCancelInReadLoop() throws Exception {
        SqlRunRegistry reg = new SqlRunRegistry(jdbc(), "it-pod-y", "pod-y");
        SqlPlaygroundService a = service(reg);
        String runId = "it-exp-loop-" + System.nanoTime();
        java.util.concurrent.atomic.AtomicInteger seen = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<SqlPlaygroundService.RunOutcome> f = CompletableFuture.supplyAsync(() -> a.export(
                new SqlPlaygroundService.QueryRequest("SELECT g FROM generate_series(1, 40000) g", Map.of(), runId),
                50_000, "alice", new SqlPlaygroundService.RowSink() {
                    @Override public void columns(String[] l) { }
                    @Override public boolean row(Object[] v) {
                        if (seen.incrementAndGet() == 100) started.countDown();
                        try { Thread.sleep(1); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return true;
                    }
                }));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(a.cancel(runId, "alice").cancelled()).isTrue();
        SqlPlaygroundService.RunOutcome o = f.get(30, TimeUnit.SECONDS);
        assertThat(o.cancelled()).as("sonuç: %s", o.error()).isTrue();
        assertThat(seen.get()).isLessThan(40_000);
    }

    @Test
    @DisplayName("Salt-okunur işlem + çalıştırma etiketi: transaction_read_only on, application_name sm-sqlpg-…; yazma reddedilir")
    void readOnlyTransactionAndTag() {
        Map<String, Object> r = service(null).execute(
                "SELECT current_setting('transaction_read_only') AS ro, current_setting('application_name') AS app", "postgres-it");
        assertThat(r.get("ok")).as("hata: %s", r.get("error")).isEqualTo(true);
        assertThat(rows(r).get(0).get("ro")).isEqualTo("on");
        assertThat((String) rows(r).get(0).get("app")).startsWith("sm-sqlpg-");

        // Metin kapılarını geçen bir yazma (sıra artırımı) veritabanında reddedilir — eskiden otomatik commit açık
        // olduğundan pgjdbc'nin readOnly bayrağı hiç uygulanmıyordu.
        List<String> seqs = jdbc().queryForList(
                "SELECT sequence_schema || '.' || sequence_name FROM information_schema.sequences LIMIT 1", String.class);
        if (!seqs.isEmpty()) {
            Map<String, Object> w = service(null).execute("SELECT nextval('" + seqs.get(0) + "') AS n", "postgres-it");
            assertThat(w.get("ok")).isEqualTo(false);
            assertThat(((String) w.get("error")).toLowerCase()).contains("read-only");
        }
        // Havuz bağlantısına etiket / salt-okunurluk sızmaz (SET LOCAL işlemle biter).
        assertThat(jdbc().queryForObject("SELECT current_setting('transaction_read_only')", String.class)).isEqualTo("off");
    }

    @Test
    @DisplayName("C1: EXPLAIN JSON planı + özet (maliyet, düğüm, ANALYZE süresi)")
    void explainSummary() {
        Map<String, Object> p = service(null).explain(q("SELECT t.name, count(*) FROM teams t GROUP BY t.name"), false, "postgres-it");
        assertThat(p.get("ok")).as("hata: %s", p.get("error")).isEqualTo(true);
        assertThat(p.get("plan")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) p.get("summary");
        assertThat(s.get("total_cost")).isNotNull();
        assertThat(((Number) s.get("nodes")).intValue()).isGreaterThan(0);

        Map<String, Object> a = service(null).explain(q("SELECT count(*) FROM teams"), true, "postgres-it");
        assertThat(a.get("ok")).as("hata: %s", a.get("error")).isEqualTo(true);
        @SuppressWarnings("unchecked") Map<String, Object> as = (Map<String, Object>) a.get("summary");
        assertThat(as.get("actual_ms")).isNotNull();
    }

    @Test
    @DisplayName("C3: dışa aktarım sürücü imleciyle okur, tavanı aşınca keser")
    void exportCursor() {
        List<Object[]> got = new ArrayList<>();
        SqlPlaygroundService.RunOutcome o = service(null).export(q("SELECT g FROM generate_series(1, 5000) g"), 1000, "postgres-it",
                new SqlPlaygroundService.RowSink() {
                    @Override public void columns(String[] l) { }
                    @Override public boolean row(Object[] v) { got.add(v); return true; }
                });
        assertThat(o.ok()).as("hata: %s", o.error()).isTrue();
        assertThat(o.rows()).isEqualTo(1000);
        assertThat(o.truncated()).isTrue();
        assertThat(got).hasSize(1000);
    }

    @Test
    @DisplayName("B4: parametreler türüyle bağlanır; jsonb ? operatörü parametreyle birlikte çalışır (?? kaçışı)")
    void paramsWithQuestionMarkOperator() {
        Map<String, Object> r = service(null).execute(new SqlPlaygroundService.QueryRequest(
                "SELECT '{\"a\":1}'::jsonb ? 'a' AS has, :n::int + 1 AS v, :d AS d",
                Map.of("n", new SqlParams.Param("41", "number"), "d", new SqlParams.Param("2026-11-13", "date")), null),
                "postgres-it");
        assertThat(r.get("ok")).as("hata: %s", r.get("error")).isEqualTo(true);
        assertThat(rows(r).get(0).get("has")).isEqualTo(true);
        assertThat(((Number) rows(r).get(0).get("v")).intValue()).isEqualTo(42);
        assertThat(String.valueOf(rows(r).get(0).get("d"))).isEqualTo("2026-11-13");
    }

    @Test
    @DisplayName("A2: başka pod durdurur — kayıt satırı + pg_stat_activity etiketi eşleşince pg_cancel_backend")
    void crossPodCancel() throws Exception {
        SqlRunRegistry podA = new SqlRunRegistry(jdbc(), "it-pod-a", "pod-a");
        SqlRunRegistry podB = new SqlRunRegistry(jdbc(), "it-pod-b", "pod-b");
        SqlPlaygroundService a = service(podA);
        String runId = "it-cancel-" + System.nanoTime();
        CompletableFuture<Map<String, Object>> f = CompletableFuture.supplyAsync(() -> a.execute(new SqlPlaygroundService.QueryRequest(
                "SELECT max(random()) AS m FROM generate_series(1, 30000) x, generate_series(1, 30000) y", Map.of(), runId), "alice"));
        long until = System.currentTimeMillis() + 15_000;
        SqlRunRegistry.CancelResult res;
        do {
            Thread.sleep(200);
            assertThat(podB.cancel(runId, "mallory").cancelled()).isFalse();
            res = podB.cancel(runId, "alice");
        } while (!res.cancelled() && !f.isDone() && System.currentTimeMillis() < until);
        if (!res.cancelled()) {
            Object outcome;
            try { outcome = f.get(1, TimeUnit.SECONDS); } catch (Exception ex) { outcome = ex; }
            fail("başka pod durduramadı (" + res + "); sonuç=" + outcome);
        }
        Map<String, Object> r = f.get(30, TimeUnit.SECONDS);
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat(r.get("cancelled")).isEqualTo(true);
        assertThat(jdbc().queryForObject("SELECT COUNT(*) FROM sql_running_queries WHERE run_id = ?", Long.class, runId)).isZero();
    }
}
