package com.sitemonitor.service.sqlplayground;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Çalışan sorgu kaydı ve durdurma — iki "pod" aynı H2 tablosunu paylaşır (A2). PostgreSQL'deki {@code pg_cancel_backend}
 * yolu {@code SqlPlaygroundPostgresTest}'te; burada aynı pod durdurması, sahiplik, bitmiş çalıştırma, başka pod işareti.
 */
class SqlRunRegistryTest {

    private JdbcTemplate jdbc;
    private SqlRunRegistry podA;
    private SqlRunRegistry podB;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:srq" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sql_running_queries (run_id VARCHAR(64) PRIMARY KEY, owner_username VARCHAR(100) NOT NULL, "
                + "instance_id VARCHAR(100), pod VARCHAR(100), backend_pid INTEGER, app_name VARCHAR(64), kind VARCHAR(16), "
                + "sql_fingerprint VARCHAR(32), started_ms BIGINT, started_at VARCHAR(30), cancel_requested BOOLEAN, heartbeat_ms BIGINT)");
        podA = new SqlRunRegistry(jdbc, "inst-a", "pod-a");
        podB = new SqlRunRegistry(jdbc, "inst-b", "pod-b");
    }

    private long rows() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM sql_running_queries", Long.class);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("Aynı pod: sahibi durdurur → Statement.cancel(); başkasının kimliği 'bulunamadı'")
    void samePodCancel() throws Exception {
        SqlRunRegistry.Run r = podA.start("run-0000001", "alice", "execute", "fp");
        Statement st = mock(Statement.class);
        podA.attach(r, st, null);
        assertThat(rows()).isEqualTo(1);

        assertThat(podA.cancel("run-0000001", "bob")).isEqualTo(new SqlRunRegistry.CancelResult(false, "not_found"));
        assertThat(podA.cancel("run-0000001", "alice")).isEqualTo(new SqlRunRegistry.CancelResult(true, null));
        verify(st).cancel();
        assertThat(podA.cancelRequested(r)).isTrue();

        podA.finish(r);
        assertThat(rows()).isZero();
        assertThat(podA.cancel("run-0000001", "alice")).isEqualTo(new SqlRunRegistry.CancelResult(false, "finished"));
        assertThat(podA.cancel("run-0000001", "bob")).isEqualTo(new SqlRunRegistry.CancelResult(false, "not_found"));
    }

    @Test
    @DisplayName("İfade bağlanmadan önce gelen durdurma: bağlanırken CancelledException (sorgu hiç başlamaz)")
    void cancelBeforeAttach() {
        SqlRunRegistry.Run r = podA.start("run-0000002", "alice", "execute", "fp");
        assertThat(podA.cancel("run-0000002", "alice").cancelled()).isTrue();
        assertThatThrownBy(() -> podA.attach(r, mock(Statement.class), null))
                .isInstanceOf(SqlRunRegistry.CancelledException.class);
        podA.finish(r);
    }

    @Test
    @DisplayName("Başka pod: kaydı bulur, işaret yazar; H2'de pg_cancel_backend yok → other_pod_unreachable; sahibi pod işareti görür")
    void otherPod() {
        SqlRunRegistry.Run r = podA.start("run-0000003", "alice", "execute", "fp");
        podA.attach(r, mock(Statement.class), 1234);
        assertThat(podB.cancel("run-0000003", "bob")).isEqualTo(new SqlRunRegistry.CancelResult(false, "not_found"));
        assertThat(podB.cancel("run-0000003", "alice")).isEqualTo(new SqlRunRegistry.CancelResult(false, "other_pod_unreachable"));
        assertThat(podA.cancelRequested(r)).isTrue();
        podA.finish(r);
        assertThat(podB.cancel("run-0000003", "alice")).isEqualTo(new SqlRunRegistry.CancelResult(false, "not_found"));
    }

    @Test
    @DisplayName("Kimlik doğrulama: geçersiz → 400 run_id; aynı kimlik çalışırken → 409; kimliksiz → üretilir")
    void ids() {
        assertThatThrownBy(() -> podA.start("bad id!", "a", "execute", "fp")).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> podA.start("short", "a", "execute", "fp")).isInstanceOf(FieldValidationException.class);
        SqlRunRegistry.Run r = podA.start("run-0000004", "a", "execute", "fp");
        assertThatThrownBy(() -> podA.start("run-0000004", "a", "execute", "fp"))
                .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("SQL_RUN_ID_IN_USE"));
        podA.finish(r);
        SqlRunRegistry.Run gen = podA.start(null, "a", "execute", "fp");
        assertThat(SqlRunRegistry.validRunId(gen.runId())).isTrue();
        podA.finish(gen);
        assertThat(podA.cancel("x", "a")).isEqualTo(new SqlRunRegistry.CancelResult(false, "not_found"));
    }

    @Test
    @DisplayName("Bayat kayıt (> 3 dk) bir sonraki başlangıçta silinir; etiket kısa ve kimliğe bağlı")
    void staleSweep() {
        jdbc.update("INSERT INTO sql_running_queries (run_id, owner_username, instance_id, started_ms) VALUES (?,?,?,?)",
                "old-run-0001", "x", "dead", System.currentTimeMillis() - SqlRunRegistry.STALE_MS - 1000);
        SqlRunRegistry.Run r = podA.start("run-0000005", "a", "execute", "fp");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sql_running_queries WHERE run_id = 'old-run-0001'", Long.class)).isZero();
        podA.finish(r);
        assertThat(SqlRunRegistry.appNameOf("run-0000005")).startsWith("sm-sqlpg-").hasSize(25)
                .isEqualTo(SqlRunRegistry.appNameOf("run-0000005")).isNotEqualTo(SqlRunRegistry.appNameOf("run-0000006"));
    }

    @Test
    @DisplayName("Süre bekçisi (inceleme ORTA 2): süre dolunca ifade iptal edilir ve çalıştırma zaman aşımı işaretlenir")
    void watchdogExpiresRun() throws Exception {
        SqlRunRegistry.Run r = podA.start("run-0000010", "alice", "export", "fp");
        Statement st = mock(Statement.class);
        podA.attach(r, st, null, 1);
        long until = System.currentTimeMillis() + 5_000;
        while (!r.timedOut() && System.currentTimeMillis() < until) Thread.sleep(50);
        assertThat(r.timedOut()).isTrue();
        verify(st).cancel();
        assertThatThrownBy(() -> podA.checkContinue(r, Long.MAX_VALUE)).isInstanceOf(SqlRunRegistry.TimedOutException.class);
        podA.finish(r);
    }

    @Test
    @DisplayName("Süre bekçisi çözülen (bitmiş) çalıştırmaya iptal göndermez")
    void watchdogNeverFiresAfterDetach() throws Exception {
        SqlRunRegistry.Run r = podA.start("run-0000011", "alice", "export", "fp");
        Statement st = mock(Statement.class);
        podA.attach(r, st, null, 1);
        podA.detach(r);
        Thread.sleep(2_300);
        org.mockito.Mockito.verifyNoInteractions(st);
        assertThat(r.timedOut()).isFalse();
        podA.finish(r);
    }

    @Test
    @DisplayName("Okuma döngüsü denetimi: süre aşımı, aynı pod durdurması ve başka pod'un tablo işareti satırlar arasında yakalanır")
    void checkContinue() {
        long[] now = {1_000_000L};
        podA.setClock(() -> now[0]);
        SqlRunRegistry.Run r = podA.start("run-0000012", "alice", "export", "fp");
        podA.attach(r, mock(Statement.class), 4242);
        podA.checkContinue(r, now[0] + 10_000);   // geçer
        now[0] += 10_001;
        assertThatThrownBy(() -> podA.checkContinue(r, 1_010_000L)).isInstanceOf(SqlRunRegistry.TimedOutException.class);
        podA.finish(r);

        now[0] = 2_000_000L;
        SqlRunRegistry.Run r2 = podA.start("run-0000013", "alice", "export", "fp");
        podA.attach(r2, mock(Statement.class), 4243);
        // Başka pod işareti yazar (H2: pg_cancel_backend yok → other_pod_unreachable), sahibi pod döngüde görür
        assertThat(podB.cancel("run-0000013", "alice").reason()).isEqualTo(SqlRunRegistry.REASON_UNREACHABLE);
        assertThatThrownBy(() -> podA.checkContinue(r2, Long.MAX_VALUE)).isInstanceOf(SqlRunRegistry.CancelledException.class);
        assertThat(r2.cancelRequested()).isTrue();
        podA.finish(r2);

        SqlRunRegistry.Run r3 = podA.start("run-0000014", "alice", "export", "fp");
        podA.attach(r3, mock(Statement.class), null);
        podA.cancel("run-0000014", "alice");
        assertThatThrownBy(() -> podA.checkContinue(r3, Long.MAX_VALUE)).isInstanceOf(SqlRunRegistry.CancelledException.class);
        podA.finish(r3);
    }

    @Test
    @DisplayName("Bayat temizliği son tazelemeye bakar: eski başlangıç + taze heartbeat silinmez")
    void staleSweepHonoursHeartbeat() {
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO sql_running_queries (run_id, owner_username, instance_id, started_ms, heartbeat_ms) VALUES (?,?,?,?,?)",
                "long-run-0001", "x", "other", now - SqlRunRegistry.STALE_MS - 60_000, now - 1_000);
        jdbc.update("INSERT INTO sql_running_queries (run_id, owner_username, instance_id, started_ms, heartbeat_ms) VALUES (?,?,?,?,?)",
                "dead-run-0001", "x", "other", now - SqlRunRegistry.STALE_MS - 60_000, now - SqlRunRegistry.STALE_MS - 1_000);
        SqlRunRegistry.Run r = podA.start("run-0000015", "a", "execute", "fp");
        assertThat(jdbc.queryForList("SELECT run_id FROM sql_running_queries ORDER BY run_id", String.class))
                .contains("long-run-0001").doesNotContain("dead-run-0001");
        podA.finish(r);
    }

    @Test
    @DisplayName("Tablo yoksa yalnız aynı pod durdurması çalışır (çalıştırma bozulmaz)")
    void tableMissing() {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:srqnone" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        SqlRunRegistry reg = new SqlRunRegistry(new JdbcTemplate(ds), "i", "p");
        SqlRunRegistry.Run r = reg.start("run-0000007", "a", "execute", "fp");
        reg.attach(r, mock(Statement.class), null);
        assertThat(reg.cancel("run-0000007", "a").cancelled()).isTrue();
        reg.finish(r);
        assertThat(reg.cancel("run-0000008", "a")).isEqualTo(new SqlRunRegistry.CancelResult(false, "not_found"));
    }
}
