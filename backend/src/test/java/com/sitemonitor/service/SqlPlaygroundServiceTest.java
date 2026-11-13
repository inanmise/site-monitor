package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.SqlQueryHistory;
import com.sitemonitor.repository.SqlQueryHistoryRepository;
import com.sitemonitor.service.sqlplayground.SensitiveColumnException;
import com.sitemonitor.service.sqlplayground.SqlParams;
import com.sitemonitor.service.sqlplayground.SqlRunRegistry;
import com.sitemonitor.service.sqlplayground.SqlSensitiveColumns;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SQL Playground servisi. Çalıştırma testleri GERÇEK bir H2 veritabanında koşar (2026-11-13: çalıştırıcı artık JDBC
 * ifadesini kendisi kurar — durdurma, parametre bağlama, kökene göre maskeleme için); şema keşfi testleri sahte
 * JdbcTemplate ile.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqlPlaygroundServiceTest {

    @Mock JdbcTemplate jdbc;
    @Mock SqlQueryHistoryRepository historyRepo;

    private SqlPlaygroundService service;
    private SqlPlaygroundService h2;
    private JdbcTemplate h2Jdbc;

    @BeforeEach
    void setUp() {
        service = new SqlPlaygroundService(jdbc, historyRepo);
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sqlpg" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        h2Jdbc = new JdbcTemplate(ds);
        h2Jdbc.execute("CREATE TABLE teams (id BIGINT PRIMARY KEY, name VARCHAR(100), insertion_date VARCHAR(30), "
                + "updated_at VARCHAR(30), created_at VARCHAR(30), into_date VARCHAR(30))");
        h2Jdbc.execute("INSERT INTO teams VALUES (1, 'alpha', 'x', 'y', 'z', 'w'), (2, 'beta', 'x', 'y', 'z', 'w')");
        h2Jdbc.execute("CREATE TABLE app_users (id BIGINT PRIMARY KEY, username VARCHAR(100), password_hash VARCHAR(100), "
                + "active_session_id VARCHAR(100), team_id BIGINT, active BOOLEAN)");
        h2Jdbc.execute("INSERT INTO app_users VALUES (1, 'alice', '$2a$10$secret', 'sess-1', 1, true), "
                + "(2, 'bob', NULL, NULL, 2, false)");
        h2Jdbc.execute("CREATE TABLE app_settings (id BIGINT PRIMARY KEY, setting_key VARCHAR(100), setting_value VARCHAR(400))");
        h2Jdbc.execute("INSERT INTO app_settings VALUES (1, 'plain.key', 'hello'), (2, 'secret.key', 'enc:v1:abcdef')");
        h2Jdbc.execute("CREATE TABLE audit_log (id BIGINT PRIMARY KEY, my_pg_sleep_log VARCHAR(10))");
        h2Jdbc.execute("CREATE TABLE a (id BIGINT)");
        h2Jdbc.execute("CREATE TABLE b (id BIGINT)");
        h2Jdbc.execute("CREATE TABLE c (id BIGINT)");
        h2Jdbc.execute("CREATE TABLE sql_running_queries (run_id VARCHAR(64) PRIMARY KEY, owner_username VARCHAR(100), "
                + "instance_id VARCHAR(100), pod VARCHAR(100), backend_pid INTEGER, app_name VARCHAR(64), kind VARCHAR(16), "
                + "sql_fingerprint VARCHAR(32), started_ms BIGINT, started_at VARCHAR(30), cancel_requested BOOLEAN, heartbeat_ms BIGINT)");
        h2 = new SqlPlaygroundService(h2Jdbc, historyRepo);
    }

    private static SqlPlaygroundService.QueryRequest q(String sql) {
        return new SqlPlaygroundService.QueryRequest(sql, Map.of(), null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.get("rows");
    }

    // ── execute (H2) ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SELECT returns rows + duration + records history (+ run_id, masked_columns, cancelled)")
    void execute_select_returnsRows() {
        Map<String, Object> result = h2.execute("SELECT id, name FROM teams ORDER BY id", "n34567");

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("rowCount")).isEqualTo(2);
        assertThat(rows(result).get(0).get("name")).isEqualTo("alpha");
        assertThat((Long) result.get("durationMs")).isGreaterThanOrEqualTo(0L);
        assertThat(result.get("run_id")).isNotNull();
        assertThat(result.get("masked_columns")).isEqualTo(List.of());
        assertThat(result.get("cancelled")).isEqualTo(false);
        ArgumentCaptor<SqlQueryHistory> saved = ArgumentCaptor.forClass(SqlQueryHistory.class);
        verify(historyRepo).save(saved.capture());
        assertThat(saved.getValue().getSuccess()).isTrue();
        assertThat(saved.getValue().getCancelled()).isFalse();
    }

    @Test
    @DisplayName("UPDATE rejected — write keywords forbidden")
    void execute_update_rejected() {
        assertThatThrownBy(() -> service.execute("UPDATE teams SET name='x' WHERE id=1", "admin"))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(historyRepo);
    }

    @Test
    @DisplayName("DELETE rejected")
    void execute_delete_rejected() {
        assertThatThrownBy(() -> service.execute("DELETE FROM teams", "admin"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DROP rejected")
    void execute_drop_rejected() {
        assertThatThrownBy(() -> service.execute("DROP TABLE teams", "admin"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TRUNCATE rejected")
    void execute_truncate_rejected() {
        assertThatThrownBy(() -> service.execute("TRUNCATE teams", "admin"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Multi-statement rejected")
    void execute_multistatement_rejected() {
        assertThatThrownBy(() -> service.execute("SELECT 1; DELETE FROM teams", "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("statement");
    }

    @Test
    @DisplayName("WITH CTE pure SELECT allowed — executed SQL is the wrapped CTE")
    void execute_withCte_allowed() {
        Map<String, Object> r = h2.execute("WITH t AS (SELECT 1 AS x) SELECT * FROM t", "admin");
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat((String) r.get("executedSql")).contains("WITH t AS").contains("SELECT * FROM t").endsWith("LIMIT 1000");
    }

    @Test
    @DisplayName("WITH CTE containing DML rejected")
    void execute_withCteDml_rejected() {
        assertThatThrownBy(() -> service.execute(
            "WITH t AS (DELETE FROM teams RETURNING *) SELECT * FROM t", "admin"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Missing LIMIT auto-appends LIMIT 1000")
    void execute_autoLimitAppended() {
        Map<String, Object> r = h2.execute("SELECT * FROM teams", "admin");
        assertThat((String) r.get("executedSql")).containsIgnoringCase("limit 1000");
    }

    @Test
    @DisplayName("LIMIT > 1000 capped to 1000")
    void execute_limitExceeds_capped() {
        String executed = (String) h2.execute("SELECT * FROM teams LIMIT 5000", "admin").get("executedSql");
        assertThat(executed).containsIgnoringCase("LIMIT 1000").doesNotContain("5000");
    }

    @Test
    @DisplayName("History recorded on SQL failure too")
    void execute_recordsHistory_onFailure() {
        Map<String, Object> result = h2.execute("SELECT 1/0", "admin");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).isNotBlank();
        verify(historyRepo).save(any(SqlQueryHistory.class));
    }

    @Test
    @DisplayName("Hata metni EN ÖZGÜL neden (veritabanı mesajı) — Spring sarmalayıcı metni değil")
    void execute_error_usesMostSpecificCause() {
        Map<String, Object> result = h2.execute("SELECT nope FROM teams", "admin");
        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).containsIgnoringCase("nope").doesNotContain("StatementCallback")
                .doesNotContain("ConnectionCallback");
        ArgumentCaptor<SqlQueryHistory> saved = ArgumentCaptor.forClass(SqlQueryHistory.class);
        verify(historyRepo).save(saved.capture());
        assertThat(saved.getValue().getErrorMessage()).containsIgnoringCase("nope");
        // Statik yardımcı: Spring 6 sarmalayıcısı yerine PostgreSQL'in kendi iletisi
        java.sql.SQLException pg = new java.sql.SQLException("ERROR: column \"nope\" does not exist" + System.lineSeparator() + "  Position: 8");
        assertThat(SqlPlaygroundService.errorMessage(new org.springframework.jdbc.BadSqlGrammarException(
                "StatementCallback", "SELECT nope FROM teams", pg))).contains("does not exist").contains("Position: 8");
    }

    @Test
    @DisplayName("Empty SQL rejected")
    void execute_empty_rejected() {
        assertThatThrownBy(() -> service.execute("   ", "admin"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("boş");
    }

    @Test
    @DisplayName("Line comments stripped before validation")
    void execute_lineCommentsStripped() {
        Map<String, Object> r = h2.execute("-- only a comment line\nSELECT 1", "admin");
        // Yorum satiri ATILIR, SELECT KALIR — yorumla birlikte sorgu da silinseydi bos/bozuk bir ifade calisirdi.
        assertThat((String) r.get("executedSql")).contains("SELECT 1").doesNotContain("only a comment line");
        assertThat(r.get("ok")).isEqualTo(true);
    }

    @Test
    @DisplayName("Dize içindeki -- ve /* korunur (eski düzenli ifade onları da siliyordu)")
    void execute_commentMarkersInsideStringsKept() {
        Map<String, Object> r = h2.execute("SELECT 'a--b' AS x, '/*c*/' AS y /* gerçek yorum */", "admin");
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(rows(r).get(0)).containsEntry("x", "a--b").containsEntry("y", "/*c*/");
        assertThat((String) r.get("executedSql")).doesNotContain("gerçek yorum");
    }

    @Test
    @DisplayName("Kapanmayan dize / görünmez denetim karakteri açık 400 ile reddedilir; aşırı uzun SQL reddedilir")
    void execute_unparsableRejected() {
        assertThatThrownBy(() -> service.execute("SELECT 'abc", "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("satır 1");
        assertThatThrownBy(() -> service.execute("SELECT 1​", "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.execute("SELECT " + "1".repeat(SqlPlaygroundService.MAX_SQL_CHARS), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(historyRepo);
    }

    @Test
    @DisplayName("Trailing semicolon allowed (single statement)")
    void execute_trailingSemi_allowed() {
        Map<String, Object> r = h2.execute("SELECT 1;", "admin");
        // Sondaki `;` atilir — birakilsaydi sarmalanan ifade sozdizimi hatasi verirdi.
        assertThat((String) r.get("executedSql")).contains("SELECT 1").doesNotContain("1;");
        assertThat(r.get("ok")).isEqualTo(true);
    }

    @Test
    @DisplayName("Yasak kelimeyi İÇEREN sütun adları reddedilmez (kelime sınırı)")
    void execute_columnNamesContainingKeywords_allowed() {
        Map<String, Object> r = h2.execute("SELECT insertion_date, updated_at, created_at FROM teams", "admin");
        // Sutun adlari BOZULMADAN gecmeli: sanitizasyon "insert" alt-dizisini kirpsaydi sorgu sessizce baska bir sey calistirirdi.
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat((String) r.get("executedSql"))
                .contains("insertion_date").contains("updated_at").contains("created_at");
    }

    // ── Hassas kolon maskeleme / reddi (A1) ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A1: hassas kolon kökene göre maskelenir (takma adla da), null null kalır; masked_columns dolar")
    void execute_masksSensitiveColumns() {
        // Takma adın (password_hash AS h) kökene göre maskelenmesi PostgreSQL sürücüsünün taban kolon bilgisiyle olur —
        // H2 türetilmiş tablo kolonunun kökenini taşımaz; o durum SqlPlaygroundPostgresTest'te.
        Map<String, Object> r = h2.execute("SELECT username, password_hash, active_session_id FROM app_users ORDER BY id", "admin");
        assertThat(r.get("ok")).isEqualTo(true);
        List<Map<String, Object>> rs = rows(r);
        assertThat(rs.get(0).get("username")).isEqualTo("alice");
        assertThat(rs.get(0).get("password_hash")).isEqualTo(SqlSensitiveColumns.MASK);
        assertThat(rs.get(0).get("active_session_id")).isEqualTo(SqlSensitiveColumns.MASK);
        assertThat(rs.get(1).get("password_hash")).isNull();
        @SuppressWarnings("unchecked") List<String> masked = (List<String>) r.get("masked_columns");
        assertThat(masked).contains("password_hash", "active_session_id").doesNotContain("username");
        Map<String, Object> star = h2.execute("SELECT * FROM app_users u ORDER BY u.id", "admin");
        assertThat(rows(star).get(0).get("password_hash")).isEqualTo(SqlSensitiveColumns.MASK);
    }

    @Test
    @DisplayName("A1: şifreli değer biçimi (enc:v1:) hangi kolonda olursa maskelenir")
    void execute_masksEncryptedValues() {
        Map<String, Object> r = h2.execute("SELECT setting_key, setting_value FROM app_settings ORDER BY id", "admin");
        assertThat(rows(r).get(0).get("setting_value")).isEqualTo("hello");
        assertThat(rows(r).get(1).get("setting_value")).isEqualTo(SqlSensitiveColumns.MASK);
        assertThat(r.get("masked_columns")).isEqualTo(List.of("setting_value"));
    }

    @Test
    @DisplayName("A1: ifade içinde hassas kolon → SQL_SENSITIVE_COLUMN, sorgu ÇALIŞMAZ, geçmişe başarısız satır")
    void execute_sensitiveExpressionRejected() {
        assertThatThrownBy(() -> h2.execute("SELECT username FROM app_users WHERE password_hash LIKE '$2a%'", "admin"))
                .isInstanceOfSatisfying(SensitiveColumnException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SQL_SENSITIVE_COLUMN");
                    assertThat(e.getColumn()).isEqualTo("password_hash");
                    assertThat(e.getMessage()).contains("password_hash");
                });
        ArgumentCaptor<SqlQueryHistory> saved = ArgumentCaptor.forClass(SqlQueryHistory.class);
        verify(historyRepo).save(saved.capture());
        assertThat(saved.getValue().getSuccess()).isFalse();
        assertThat(saved.getValue().getErrorMessage()).contains("password_hash");
    }

    // ── Parametreler (B4) ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("B4: :ad türüyle bağlanır (metin birleştirme yok); eksik → SQL_PARAM_MISSING; tür hatası → SQL_PARAM_INVALID")
    void execute_params() {
        Map<String, SqlParams.Param> p = Map.of("id", new SqlParams.Param("2", "number"),
                "n", new SqlParams.Param("x' OR '1'='1", "text"));
        Map<String, Object> r = h2.execute(new SqlPlaygroundService.QueryRequest(
                "SELECT name FROM teams WHERE id = :id OR name = :n", p, null), "admin");
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(rows(r)).extracting(m -> m.get("name")).containsExactly("beta");
        assertThat(r.get("params")).isEqualTo(2);
        assertThat((String) r.get("executedSql")).contains(":id").contains(":n");

        assertThatThrownBy(() -> h2.execute(new SqlPlaygroundService.QueryRequest("SELECT :x", Map.of(), null), "admin"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getCode()).isEqualTo("SQL_PARAM_MISSING"));
        assertThatThrownBy(() -> h2.execute(new SqlPlaygroundService.QueryRequest("SELECT :x",
                Map.of("x", new SqlParams.Param("abc", "number")), null), "admin"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getCode()).isEqualTo("SQL_PARAM_INVALID"));
        ArgumentCaptor<SqlQueryHistory> saved = ArgumentCaptor.forClass(SqlQueryHistory.class);
        verify(historyRepo).save(saved.capture());
        assertThat(saved.getValue().getSqlText()).contains(":id").doesNotContain("OR '1'='1");
    }

    // ── Durdurma (A2) ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A2: çalışan sorguyu sahibi durdurur → ok:false, cancelled:true, geçmişte cancelled; başkası durduramaz")
    void execute_cancel() throws Exception {
        SqlRunRegistry reg = new SqlRunRegistry(h2Jdbc, "inst-x", "pod-x");
        h2.setRunRegistry(reg);
        String runId = "run-cancel-0001";
        CompletableFuture<Map<String, Object>> f = CompletableFuture.supplyAsync(() -> h2.execute(new SqlPlaygroundService.QueryRequest(
                "SELECT MAX(RAND()) AS m FROM SYSTEM_RANGE(1, 200000) a, SYSTEM_RANGE(1, 200000) b", Map.of(), runId), "alice"));
        long until = System.currentTimeMillis() + 10_000;
        SqlRunRegistry.CancelResult res;
        do {
            Thread.sleep(100);
            assertThat(h2.cancel(runId, "mallory")).isEqualTo(new SqlRunRegistry.CancelResult(false, "not_found"));
            res = h2.cancel(runId, "alice");
        } while (!res.cancelled() && !f.isDone() && System.currentTimeMillis() < until);
        if (!res.cancelled()) {
            Object outcome;
            try { outcome = f.get(1, TimeUnit.SECONDS); } catch (Exception ex) { outcome = ex; }
            fail("durdurulamadı; sonuç=" + outcome);
        }
        Map<String, Object> r = f.get(20, TimeUnit.SECONDS);
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat(r.get("cancelled")).isEqualTo(true);
        assertThat(r.get("run_id")).isEqualTo(runId);
        ArgumentCaptor<SqlQueryHistory> saved = ArgumentCaptor.forClass(SqlQueryHistory.class);
        verify(historyRepo).save(saved.capture());
        assertThat(saved.getValue().getCancelled()).isTrue();
        assertThat(h2.cancel(runId, "alice")).isEqualTo(new SqlRunRegistry.CancelResult(false, "finished"));
    }

    // ── Plan / dışa aktarım (H2) ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C1: PostgreSQL değilse 409 SQL_EXPLAIN_UNSUPPORTED; hassas ifade yine reddedilir")
    void explain_unsupportedOnH2() {
        assertThatThrownBy(() -> h2.explain(q("SELECT 1"), false, "admin"))
                .isInstanceOfSatisfying(com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SQL_EXPLAIN_UNSUPPORTED"));
        assertThatThrownBy(() -> h2.explain(q("SELECT md5(password_hash) FROM app_users"), true, "admin"))
                .isInstanceOf(SensitiveColumnException.class);
    }

    @Test
    @DisplayName("C3: dışa aktarım satırları tek tek verir, tavanı aşınca keser (truncated), maskeyi uygular")
    void export_streamsAndTruncates() {
        List<Object[]> got = new ArrayList<>();
        String[][] labels = new String[1][];
        SqlPlaygroundService.RunOutcome o = h2.export(q("SELECT username, password_hash FROM app_users ORDER BY id"), 1, "admin",
                new SqlPlaygroundService.RowSink() {
                    @Override public void columns(String[] l) { labels[0] = l; }
                    @Override public boolean row(Object[] v) { got.add(v); return true; }
                });
        assertThat(o.ok()).isTrue();
        assertThat(o.rows()).isEqualTo(1);
        assertThat(o.truncated()).isTrue();
        assertThat(labels[0]).containsExactly("username", "password_hash");
        assertThat(got).hasSize(1);
        assertThat(got.get(0)[1]).isEqualTo(SqlSensitiveColumns.MASK);
        assertThat(o.maskedColumns()).containsExactly("password_hash");
        SqlPlaygroundService.RunOutcome all = h2.export(q("SELECT id FROM teams"), 10, "admin",
                new SqlPlaygroundService.RowSink() {
                    @Override public void columns(String[] l) { }
                    @Override public boolean row(Object[] v) { return true; }
                });
        assertThat(all.rows()).isEqualTo(2);
        assertThat(all.truncated()).isFalse();
    }

    @Test
    @DisplayName("İnceleme DÜŞÜK 6: hassas kolon reddi plan ve dışa aktarımda da geçmişe başarısız satır yazar")
    void explainAndExportRejectionLeavesHistory() {
        assertThatThrownBy(() -> h2.explain(q("SELECT md5(password_hash) FROM app_users"), false, "admin"))
                .isInstanceOf(SensitiveColumnException.class);
        assertThatThrownBy(() -> h2.export(q("SELECT username FROM app_users ORDER BY password_hash"), 10, "admin",
                new SqlPlaygroundService.RowSink() {
                    @Override public void columns(String[] l) { }
                    @Override public boolean row(Object[] v) { return true; }
                })).isInstanceOf(SensitiveColumnException.class);
        ArgumentCaptor<SqlQueryHistory> saved = ArgumentCaptor.forClass(SqlQueryHistory.class);
        verify(historyRepo, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(h -> {
            assertThat(h.getSuccess()).isFalse();
            assertThat(h.getErrorMessage()).contains("password_hash");
        });
    }

    @Test
    @DisplayName("İnceleme DÜŞÜK 6: parametre DEĞERİ geçmişe yazılan hata metninde maskelenir (yanıtta kalabilir)")
    void paramValueMaskedInStoredError() {
        Map<String, Object> r = h2.execute(new SqlPlaygroundService.QueryRequest("SELECT CAST(:x AS INT) AS v",
                Map.of("x", new SqlParams.Param("gizli-deger-77", "text")), null), "admin");
        assertThat(r.get("ok")).isEqualTo(false);
        ArgumentCaptor<SqlQueryHistory> saved = ArgumentCaptor.forClass(SqlQueryHistory.class);
        verify(historyRepo).save(saved.capture());
        assertThat(saved.getValue().getErrorMessage()).isNotBlank().doesNotContain("gizli-deger-77");
        assertThat(saved.getValue().getSqlText()).contains(":x").doesNotContain("gizli-deger-77");
        assertThat(SqlPlaygroundService.maskParamValues("invalid input syntax for type integer: \"ab\"",
                Map.of("x", new SqlParams.Param("ab", "text"))))
                .isEqualTo("invalid input syntax for type integer: \"***\"");
        assertThat(SqlPlaygroundService.maskParamValues("satır 1 konum 12: 1", Map.of("x", new SqlParams.Param("1", "number"))))
                .isEqualTo("satır 1 konum 12: 1");   // kısa çıplak değer metni bozmaz
        assertThat(SqlPlaygroundService.maskParamValues(null, Map.of())).isNull();
    }

    @Test
    @DisplayName("İnceleme C: date → yyyy-MM-dd, zamansız timestamp → yerel ISO (kaymasız), timestamptz → UTC …Z")
    void dateAndTimestampFormats() {
        Map<String, Object> r = h2.execute("SELECT DATE '2026-11-13' AS d, TIMESTAMP '2026-11-13 10:05:00' AS ts, "
                + "TIMESTAMP '2026-11-13 10:05:00.123' AS tsm, TIMESTAMP '2026-11-13 10:05:00.123456' AS tsu, "
                + "TIMESTAMP WITH TIME ZONE '2026-11-13 10:05:00+03:00' AS tz, CAST(NULL AS DATE) AS dn", "admin");
        assertThat(r.get("ok")).as("hata: %s", r.get("error")).isEqualTo(true);
        Map<String, Object> row = rows(r).get(0);
        assertThat(row.get("d")).isEqualTo("2026-11-13");
        assertThat(row.get("ts")).isEqualTo("2026-11-13T10:05:00");
        assertThat(row.get("tsm")).isEqualTo("2026-11-13T10:05:00.123");
        assertThat(row.get("tsu")).isEqualTo("2026-11-13T10:05:00.123456");
        assertThat(row.get("tz")).isEqualTo("2026-11-13T07:05:00.000Z");
        assertThat(row.get("dn")).isNull();
    }

    @Test
    @DisplayName("İnceleme ORTA 2: dışa aktarım okuma döngüsündeyken Durdur → döngü bir sonraki satırda kesilir, sonuç durduruldu")
    void exportCancelledInReadLoop() throws Exception {
        SqlRunRegistry reg = new SqlRunRegistry(h2Jdbc, "inst-x", "pod-x");
        h2.setRunRegistry(reg);
        String runId = "run-export-loop-1";
        int[] seen = {0};
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        CompletableFuture<SqlPlaygroundService.RunOutcome> f = CompletableFuture.supplyAsync(() -> h2.export(
                new SqlPlaygroundService.QueryRequest("SELECT \"X\" FROM SYSTEM_RANGE(1, 20000)", Map.of(), runId), 50_000, "alice",
                new SqlPlaygroundService.RowSink() {
                    @Override public void columns(String[] l) { }
                    @Override public boolean row(Object[] v) {
                        if (++seen[0] == 10) started.countDown();
                        try { Thread.sleep(2); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return true;
                    }
                }));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(h2.cancel(runId, "alice").cancelled()).isTrue();
        SqlPlaygroundService.RunOutcome o = f.get(20, TimeUnit.SECONDS);
        assertThat(o.ok()).isFalse();
        assertThat(o.cancelled()).isTrue();
        assertThat(seen[0]).isLessThan(20_000);
    }

    // Aynı adlı kolonların tekilleştirilmesi: H2 türetilmiş tabloda (_capped sarmalayıcısı) aynı adlı kolonu reddeder —
    // sorgu düzeyindeki testler SqlPlaygroundPostgresTest'te, ad kuralı SqlResultColumnsTest'te.

    @Test
    @DisplayName("Benzersiz kolonlu sorgu değişmez: columns = sıralı adlar, renamed_columns boş")
    void uniqueColumnsUnchanged() {
        Map<String, Object> r = h2.execute("SELECT id, name FROM teams ORDER BY id", "admin");
        assertThat(r.get("columns")).isEqualTo(List.of("id", "name"));
        assertThat(r.get("renamed_columns")).isEqualTo(List.of());
        assertThat(rows(r).get(0)).containsOnlyKeys("id", "name");
    }

    // ── Tehlikeli fonksiyon engeli (SELECT-only kuralının YAKALAYAMADIĞI yüzey) ──

    /**
     * Bu sorguların hepsi geçerli SELECT'tir ve DML/DDL anahtar kelimesi İÇERMEZ — yani
     * SELECT-only kuralını ve FORBIDDEN listesini sorunsuz geçerler. Onları durduran tek şey
     * FORBIDDEN_FUNCTIONS listesidir ve o liste hiçbir testle korunmuyordu.
     */
    @ParameterizedTest(name = "yasak fonksiyon reddedilir: {0}")
    @ValueSource(strings = {
            "SELECT pg_read_file('/etc/passwd')",                       // sunucu dosyası okuma
            "SELECT pg_read_binary_file('/etc/shadow')",
            "SELECT pg_ls_dir('/')",                                    // dizin listeleme
            "SELECT pg_stat_file('/etc/hostname')",
            "SELECT lo_import('/etc/passwd')",                          // large-object
            "SELECT lo_export(1, '/tmp/x')",
            "SELECT dblink_connect('host=10.0.0.1 user=x')",            // iç-ağ bağlantısı
            "SELECT * FROM dblink('dbname=x', 'SELECT 1') AS t(a int)",
            "SELECT pg_sleep(30)",                                      // kaynak tüketimi
            "SELECT pg_terminate_backend(123)",                         // backend kontrolü
            "SELECT pg_cancel_backend(123)",
            "SELECT pg_reload_conf()",
            "SELECT set_config('x', 'y', false)",
            // danışma kilitleri (inceleme ORTA 3): oturum kilidi havuza dönen bağlantıda kalır
            "SELECT pg_advisory_lock(424242)",
            "SELECT pg_try_advisory_lock(1)",
            "SELECT pg_advisory_xact_lock(1, 2)",
            "SELECT pg_advisory_lock_shared(1)",
            "SELECT pg_advisory_unlock_all()",
    })
    void execute_forbiddenFunctions_rejected(String sql) {
        assertThatThrownBy(() -> service.execute(sql, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        // Reddedilen sorgu veritabanına HİÇ gitmez.
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest(name = "büyük/küçük harf ve boşluk kaçamağı işe yaramaz: {0}")
    @ValueSource(strings = {
            "SELECT PG_READ_FILE('/etc/passwd')",
            "select Pg_Sleep(10)",
            "SELECT   pg_ls_dir  ( '/' )",
    })
    void execute_forbiddenFunctions_caseAndSpacingEvasionFails(String sql) {
        assertThatThrownBy(() -> service.execute(sql, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Kelime sınırı: adında yasak fonksiyon GEÇEN sütun reddedilmez")
    void execute_columnNamedLikeForbiddenFunction_allowed() {
        // Engel listesi kelime sınırıyla eşleşir; "my_pg_sleep_log" gibi meşru bir ad
        // reddedilirse kullanıcı sebebini anlayamaz.
        Map<String, Object> r = h2.execute("SELECT my_pg_sleep_log FROM audit_log", "admin");

        assertThat(r.get("ok")).isEqualTo(true);
    }

    @Test
    @DisplayName("listTables queries information_schema")
    void listTables_queries() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("table_name", "teams")));
        List<Map<String, Object>> result = service.listTables();
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("listColumns rejects invalid identifier")
    void listColumns_invalidName_rejected() {
        assertThatThrownBy(() -> service.listColumns("teams; DROP TABLE x"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("listColumns accepts valid identifier")
    void listColumns_validName_returns() {
        when(jdbc.queryForList(anyString(), eq("teams")))
            .thenReturn(List.of(Map.of("column_name", "id")));
        List<Map<String, Object>> result = service.listColumns("teams");
        assertThat(result).hasSize(1);
    }

    // ── Denetim 5. tur, bulgu 9 + 33 ──────────────────────────────────────────

    @Test
    @DisplayName("Bulgu 9: SELECT ... INTO reddedilir — SELECT ile baslayan bir DDL+DML'dir")
    void execute_selectInto_rejected() {
        assertThatThrownBy(() -> service.execute("SELECT * INTO yeni_tablo FROM teams", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).queryForList(anyString());
    }

    @Test
    @DisplayName("Bulgu 9: 'into' kelimesi sutun adinin PARCASI ise engellenmez (yanlis pozitif yok)")
    void execute_columnContainingInto_allowed() {
        Map<String, Object> r = h2.execute("SELECT into_date FROM teams", "admin");
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat((String) r.get("executedSql")).contains("into_date").endsWith("LIMIT 1000");
    }

    @Test
    @DisplayName("Bulgu 33: IC LIMIT tavanin ALTINDA olsa da DIS tavan eklenir")
    void execute_innerLimitBelowCap_stillWrapped() {
        String executed = (String) h2.execute("SELECT * FROM a, b WHERE a.id IN (SELECT id FROM c LIMIT 5)", "admin")
                .get("executedSql");
        assertThat(executed)
                .as("dis tavan yoksa kartezyen carpim milyonlarca satiri bellege alir: %s", executed)
                .startsWith("SELECT * FROM (")
                .endsWith("LIMIT 1000");
    }

    // ── Kod incelemesi 2026-09-09: paylaşılan JdbcTemplate'e dokunulmaz ───────

    @Test
    @DisplayName("execute PAYLAŞILAN JdbcTemplate'in zaman aşımını DEĞİŞTİRMEZ (retention/scheduler 30 sn tavana düşmesin)")
    void execute_doesNotMutateSharedJdbcTemplate() {
        when(jdbc.queryForList(contains("SELECT"))).thenReturn(List.of());
        service.execute("SELECT 1", "n1");
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).setQueryTimeout(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("Çalıştırma otomatik commit KAPALI salt-okunur işlemde koşar ve her zaman geri alınır (H2 bağlantısı)")
    void execute_runsInRolledBackTransaction() throws Exception {
        javax.sql.DataSource ds = h2Jdbc.getDataSource();
        // Gözlem: çalıştırma sonrası havuz bağlantısının otomatik commit'i eski hâline döner (sızıntı yok)
        Map<String, Object> r = h2.execute("SELECT 1", "admin");
        assertThat(r.get("ok")).isEqualTo(true);
        try (java.sql.Connection c = ds.getConnection()) {
            assertThat(c.getAutoCommit()).isTrue();
        }
    }

    @Test
    @DisplayName("DataSource varsa oyun alanı kendi ayrı, 30 sn zaman aşımlı JdbcTemplate örneğini kullanır (tek sefer kurulur)")
    void playgroundTemplate_isSeparateTimedInstance() {
        javax.sql.DataSource ds = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        when(jdbc.getDataSource()).thenReturn(ds);

        JdbcTemplate t1 = service.playgroundTemplate();
        JdbcTemplate t2 = service.playgroundTemplate();

        assertThat(t1).isNotSameAs(jdbc).isSameAs(t2);
        assertThat(t1.getQueryTimeout()).isEqualTo(30);
        // DataSource artık SALT-OKUNUR sarmalayıcı (2026-09-23); hedefi yine paylaşılan havuz.
        assertThat(t1.getDataSource()).isNotSameAs(ds)
                .isInstanceOf(org.springframework.jdbc.datasource.DelegatingDataSource.class);
        assertThat(((org.springframework.jdbc.datasource.DelegatingDataSource) t1.getDataSource())
                .getTargetDataSource()).isSameAs(ds);
    }

    @Test
    @DisplayName("Oyun alanı bağlantısı JDBC seviyesinde SALT-OKUNUR açılır — kara-liste tek savunma değil")
    void playgroundConnection_isOpenedReadOnly() throws Exception {
        // Metin kontrolleri (SELECT/WITH ile başlama, into/merge, yasak fonksiyonlar) ilk kapı;
        // kelime sınırlarını atlatan bir yazma yolu için ikinci kapı DB'nin kendisi olmalı.
        javax.sql.DataSource ds = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        java.sql.Connection con = org.mockito.Mockito.mock(java.sql.Connection.class);
        when(ds.getConnection()).thenReturn(con);
        when(jdbc.getDataSource()).thenReturn(ds);

        service.playgroundTemplate().getDataSource().getConnection();

        verify(con).setReadOnly(true);
    }

    @Test
    @DisplayName("Hassas kolon şeması canlı information_schema'dan desene uyan kolonları da alır")
    void sensitiveSchema_includesLivePatternColumns() {
        h2Jdbc.execute("CREATE TABLE vendor_tokens (id BIGINT, refresh_token VARCHAR(50))");
        assertThat(h2.sensitiveSchema().columnsOf("vendor_tokens")).contains("refresh_token");
        assertThat(h2.sensitiveSchema().columnsOf("app_users")).contains("password_hash");
    }

    // ── tableDetails zenginleştirme (2026-09-20) ─────────────────────────────


    @Test
    @DisplayName("tableDetails: yapısal kısıt (kolonlar, hedef tablo, ON DELETE), kolon rozetleri (pk/fk/indeksli), referenced_by, stats; katalog hatası ekranı düşürmez")
    void tableDetails_enriched() {
        java.util.function.Function<String, Map<String, Object>> col = n -> { Map<String, Object> m = new java.util.LinkedHashMap<>(); m.put("column_name", n); m.put("data_type", "bigint"); m.put("udt_name", "int8"); m.put("is_nullable", "NO"); return m; };
        when(jdbc.queryForList(contains("information_schema.columns"), eq("alerts"))).thenReturn(new java.util.ArrayList<>(List.of(col.apply("id"), col.apply("team_id"), col.apply("note"))));
        when(jdbc.queryForList(contains("pg_get_constraintdef"), eq("alerts"))).thenReturn(List.of(
                Map.of("name", "alerts_pkey", "contype", "p", "definition", "PRIMARY KEY (id)", "columns", "id", "ref_columns", "", "on_delete", "a", "on_update", "a"),
                mapOf("name", "alerts_team_fk", "contype", "f", "definition", "FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE", "columns", "team_id", "ref_table", "teams", "ref_columns", "id", "on_delete", "c", "on_update", "a")));
        when(jdbc.queryForList(contains("fr.relname = ?"), eq("alerts"))).thenReturn(List.of(
                Map.of("name", "notes_alert_fk", "from_table", "alert_notes", "from_columns", "alert_id", "on_delete", "n")));
        when(jdbc.queryForList(contains("FROM pg_indexes"), eq("alerts"))).thenReturn(new java.util.ArrayList<>(List.of(
                mapOf("name", "alerts_pkey", "definition", "CREATE UNIQUE INDEX alerts_pkey ON alerts (id)", "is_unique", true),
                mapOf("name", "ix_alerts_note", "definition", "CREATE INDEX ix_alerts_note ON alerts (note)", "is_unique", false))));
        when(jdbc.queryForList(contains("pg_stat_user_indexes"), eq("alerts"))).thenReturn(List.of(
                mapOf("name", "ix_alerts_note", "idx_scan", 42L, "idx_tup_read", 100L, "size", "16 kB", "size_bytes", 16384L, "columns", "note", "is_primary", false)));
        when(jdbc.queryForList(contains("pg_stat_user_tables WHERE"), eq("alerts"))).thenReturn(List.of(
                mapOf("seq_scan", 3L, "seq_tup_read", 30L, "idx_scan", 42L, "idx_tup_fetch", 40L, "n_dead_tup", 7L, "n_mod_since_analyze", 1L,
                      "last_vacuum", null, "last_autovacuum", null, "last_analyze", null, "last_autoanalyze", null)));
        when(jdbc.queryForList(contains("pg_stats WHERE"), eq("alerts"))).thenThrow(new RuntimeException("no privilege"));   // safeQuery yutar

        Map<String, Object> d = service.tableDetails("alerts");

        @SuppressWarnings("unchecked") List<Map<String, Object>> cons = (List<Map<String, Object>>) d.get("constraints");
        assertThat(cons).hasSize(2);
        assertThat(cons.get(1).get("type")).isEqualTo("FOREIGN KEY");
        assertThat(cons.get(1).get("columns")).isEqualTo(List.of("team_id"));
        assertThat(cons.get(1).get("ref_table")).isEqualTo("teams");
        assertThat(cons.get(1).get("on_delete")).isEqualTo("CASCADE");
        @SuppressWarnings("unchecked") List<Map<String, Object>> cols = (List<Map<String, Object>>) d.get("columns");
        assertThat(cols.get(0).get("is_pk")).isEqualTo(true);
        assertThat(cols.get(1).get("is_fk")).isEqualTo(true);
        assertThat(cols.get(2).get("is_indexed")).isEqualTo(true);
        assertThat(cols.get(1).get("is_indexed")).isEqualTo(false);
        @SuppressWarnings("unchecked") List<Map<String, Object>> ref = (List<Map<String, Object>>) d.get("referenced_by");
        assertThat(ref).hasSize(1);
        assertThat(ref.get(0).get("from_columns")).isEqualTo(List.of("alert_id"));
        assertThat(ref.get(0).get("on_delete")).isEqualTo("SET NULL");
        @SuppressWarnings("unchecked") List<Map<String, Object>> ixs = (List<Map<String, Object>>) d.get("indexes");
        assertThat(ixs.get(1).get("scans")).isEqualTo(42L);
        assertThat(ixs.get(1).get("size")).isEqualTo("16 kB");
        assertThat(((Map<?, ?>) d.get("stats")).get("dead_rows")).isEqualTo(7L);
        assertThat(d).containsKeys("comment", "inferred_relations");
    }

    @Test
    void fkAction_codes() {
        assertThat(SqlPlaygroundService.fkAction("c")).isEqualTo("CASCADE");
        assertThat(SqlPlaygroundService.fkAction("n")).isEqualTo("SET NULL");
        assertThat(SqlPlaygroundService.fkAction("a")).isEqualTo("NO ACTION");
        assertThat(SqlPlaygroundService.fkAction(null)).isNull();
    }

    // ── 3. tur (2026-11-13) ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("3. tur ORTA 3: yalnız EN DIŞ LIMIT tavana iner — alt sorgu / CTE LIMIT'i ve dize içindeki 'limit' aynen")
    void enforceLimitOnlyOuterLimit() {
        assertThat(SqlPlaygroundService.enforceLimit("SELECT count(*) FROM (SELECT g FROM t LIMIT 5000) s", 1000))
                .isEqualTo("SELECT * FROM (SELECT count(*) FROM (SELECT g FROM t LIMIT 5000) s) AS _capped LIMIT 1000");
        assertThat(SqlPlaygroundService.enforceLimit("SELECT 'limit 5000' AS note", 1000))
                .isEqualTo("SELECT * FROM (SELECT 'limit 5000' AS note) AS _capped LIMIT 1000");
        assertThat(SqlPlaygroundService.enforceLimit("WITH c AS (SELECT * FROM t LIMIT 5000) SELECT * FROM c", 1000))
                .contains("LIMIT 5000) SELECT");
        assertThat(SqlPlaygroundService.enforceLimit("SELECT * FROM teams LIMIT 5000;", 1000))
                .isEqualTo("SELECT * FROM (SELECT * FROM teams LIMIT 1000) AS _capped LIMIT 1000");
        assertThat(SqlPlaygroundService.enforceLimit("SELECT * FROM teams LIMIT 500 OFFSET 2", 1000))
                .isEqualTo("SELECT * FROM (SELECT * FROM teams LIMIT 500 OFFSET 2) AS _capped LIMIT 1000");
        assertThat(SqlPlaygroundService.enforceLimit("SELECT * FROM t LIMIT 100000", 50_001)).contains("LIMIT 50001)");
        assertThat(SqlPlaygroundService.enforceLimit("SELECT * FROM t LIMIT 0x7fffffff", 1000)).contains("LIMIT 0x7fffffff)");
        // H2'de gerçek sonuç: eskiden iç LIMIT 1000'e iniyor, sayım sessizce 1000 dönüyordu
        Map<String, Object> r = h2.execute("SELECT count(*) AS c FROM (SELECT \"X\" FROM SYSTEM_RANGE(1, 5000) LIMIT 5000) s", "admin");
        assertThat(r.get("ok")).as("hata: %s", r.get("error")).isEqualTo(true);
        assertThat(((Number) rows(r).get(0).get("c")).intValue()).isEqualTo(5000);
        assertThat(rows(h2.execute("SELECT 'limit 5000' AS note", "admin")).get(0).get("note")).isEqualTo("limit 5000");
    }

    @Test
    @DisplayName("3. tur DÜŞÜK 5: ekran sorgusu bellek bütçesi — uzun hücre kesilir (truncated_cells), toplam aşılınca okuma durur")
    void screenMemoryBudget() {
        Map<String, Object> small = h2.execute("SELECT id FROM teams", "admin");
        assertThat(small.get("truncated")).isEqualTo(false);
        assertThat(small.get("truncated_reason")).isNull();
        assertThat(small.get("truncated_cells")).isEqualTo(0);

        Map<String, Object> one = h2.execute("SELECT REPEAT('x', 250000) AS big, 'y' AS small", "admin");
        assertThat(one.get("ok")).as("hata: %s", one.get("error")).isEqualTo(true);
        String big = (String) rows(one).get(0).get("big");
        assertThat(big).hasSize(SqlPlaygroundService.CELL_MAX_CHARS + 1).endsWith(SqlPlaygroundService.CELL_TRUNCATED_MARK);
        assertThat(rows(one).get(0).get("small")).isEqualTo("y");
        assertThat(one.get("truncated_cells")).isEqualTo(1);
        assertThat(one.get("truncated")).isEqualTo(false);

        Map<String, Object> many = h2.execute("SELECT REPEAT('z', 90000) AS v FROM SYSTEM_RANGE(1, 200)", "admin");
        assertThat(many.get("ok")).as("hata: %s", many.get("error")).isEqualTo(true);
        assertThat(many.get("truncated")).isEqualTo(true);
        assertThat(many.get("truncated_reason")).isEqualTo("bytes");
        int n = rows(many).size();
        assertThat(n).isGreaterThan(0).isLessThan(200);
        assertThat((long) n * 90_000).isLessThanOrEqualTo(SqlPlaygroundService.RESULT_MAX_CHARS);
        assertThat(many.get("rowCount")).isEqualTo(n);

        Map<String, Object> capped = h2.execute("SELECT \"X\" FROM SYSTEM_RANGE(1, 1500)", "admin");
        assertThat(rows(capped)).hasSize(1000);
        assertThat(capped.get("truncated")).isEqualTo(true);
        assertThat(capped.get("truncated_reason")).isEqualTo("rows");

        // Kesme maskeyi açmaz: hassas kolon maskesi ve şifreli değer kuralı hücre okunurken uygulanır
        Map<String, Object> m = h2.execute("SELECT password_hash FROM app_users WHERE id = 1", "admin");
        assertThat(rows(m).get(0).get("password_hash")).isEqualTo(SqlSensitiveColumns.MASK);
    }

    @Test
    @DisplayName("Hücre sınırı: vekil çift bölünmez; ikili veri [binary N bytes]; kısa değer aynı nesne")
    void fitCellRules() {
        String s = "a".repeat(9) + "😀" + "b";   // 9 + vekil çift + 1
        assertThat(SqlPlaygroundService.fitCell(s, 10)).isEqualTo("a".repeat(9) + SqlPlaygroundService.CELL_TRUNCATED_MARK);
        assertThat(SqlPlaygroundService.fitCell(new byte[300], 100)).isEqualTo("[binary 300 bytes]");
        String shortV = "kısa";
        assertThat(SqlPlaygroundService.fitCell(shortV, 10)).isSameAs(shortV);
        assertThat(SqlPlaygroundService.fitCell(List.of("x".repeat(50), "y".repeat(50)), 40)).asString()
                .endsWith(SqlPlaygroundService.CELL_TRUNCATED_MARK);
    }

    @Test
    @DisplayName("3. tur DÜŞÜK 4: dizi hücresi JSON listesi (500 yok); şifreli değer içeren dizi maskelenir; time metin")
    void driverObjectsAreJsonSafe() throws Exception {
        Map<String, Object> r = h2.execute("SELECT ARRAY[1, 2] AS a, ARRAY['x', NULL] AS b, TIME '12:30:05' AS t", "admin");
        assertThat(r.get("ok")).as("hata: %s", r.get("error")).isEqualTo(true);
        Map<String, Object> row = rows(r).get(0);
        assertThat(row.get("a")).isEqualTo(List.of(1, 2));
        assertThat(row.get("b")).isEqualTo(java.util.Arrays.asList("x", null));
        assertThat(row.get("t")).isEqualTo("12:30:05");
        // Yanıt gövdesi JSON'a yazılabilir (eskiden sürücü dizi nesnesi Jackson'da 500 veriyordu)
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(r);
        assertThat(json).contains("\"a\":[1,2]");

        Map<String, Object> enc = h2.execute("SELECT ARRAY[setting_value] AS v FROM app_settings WHERE id = 2", "admin");
        assertThat(rows(enc).get(0).get("v")).isEqualTo(SqlSensitiveColumns.MASK);
        assertThat(enc.get("masked_columns")).isEqualTo(List.of("v"));
    }

    @Test
    @DisplayName("3. tur YÜKSEK 1: takma ad / alan biçiminde çağrı / konum atlatmaları servis düzeyinde çalıştırılmadan reddedilir")
    void bypassesRejectedBeforeRunning() {
        for (String sql : List.of(
                "SELECT length(to_jsonb(u)->>'password_hash') AS l FROM app_users * u",
                "SELECT length(to_jsonb(rows)->>'password_hash') AS l FROM app_users rows",
                "SELECT u.to_jsonb FROM app_users u",
                "SELECT username FROM (SELECT * FROM app_users ORDER BY (6) LIMIT 100) d",
                "SELECT password_hash, username FROM app_users ORDER BY 0x1")) {
            assertThatThrownBy(() -> h2.execute(sql, "admin")).as(sql).isInstanceOf(SensitiveColumnException.class);
        }
    }

    /** null değer taşıyabilen sıralı harita (Map.of null kabul etmez). */
    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
