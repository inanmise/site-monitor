package com.sitemonitor.service.evalstate;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Kalıcı değerlendirme durumu açılış yaması (2026-10-23, çok pod Sürüm 3-1, A1): dört tabloyu + dizini kurar, ikinci koşu
 * dokunmaz, iki pod'un {@code CREATE} yarışı yutulur, veritabanı hatası açılışı durdurmaz.
 */
class EvalStateSchemaPatchTest {

    final MultiPodTestSupport mp = MultiPodTestSupport.create();

    @AfterEach
    void close() {
        mp.close();
    }

    @Test
    @DisplayName("Tablolar yoksa kurulur (H2'de 4 değişiklik, doluluk ayarı yalnız PG); ikinci koşu no-op; dizin var")
    void createsOnceThenNoop() {
        EvalStateSchemaPatch p = new EvalStateSchemaPatch(mp.jdbc());
        for (String t : EvalStateSql.TABLES) assertThat(p.tableReadable(t)).as(t).isFalse();
        assertThat(p.apply()).isEqualTo(4);
        for (String t : EvalStateSql.TABLES) assertThat(p.tableReadable(t)).as(t).isTrue();
        assertThat(p.apply()).as("ikinci koşu").isZero();
        Integer idx = mp.jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.indexes "
                + "WHERE lower(index_name) = 'idx_mls_type_domain'", Integer.class);
        assertThat(idx).isPositive();
        p.onReady();   // var olan tablolarda istisna yok
        assertThat(new EvalStateSql(mp.jdbc()).tablesExist()).isTrue();
    }

    @Test
    @DisplayName("Kurulan tablolar yazımın beklediği her sütunu taşır (dört tabloya bir satır yazılıp okunur)")
    void tablesCarryEveryWrittenColumn() {
        new EvalStateSchemaPatch(mp.jdbc()).apply();
        EvalStateSql sql = new EvalStateSql(mp.jdbc());
        long g = sql.acquireGeneration("pod1", 1_000L);
        sql.writeRecovery(List.of(new EvalStateSql.RecoveryRow("HTTP_DOWN", "x", 1, 2L, 3L, 4L, 1)), "pod1", g, 1L);
        sql.writeLastStatus(List.of(new EvalStateSql.LastStatusRow("HTTP_DOWN", "m:1", "x", "d", 1L, 2L, false, false,
                false, "OTHER", "M", 1L, 1L, 2)), "pod1", g, 1L);
        sql.writeTypeState(List.of(new EvalStateSql.TypeRow("HTTP_DOWN", false, null, 1L, 2L, 3)), "pod1", g, 1L);
        sql.writeChains(List.of(new EvalStateSql.ChainRow(EvalStateKeys.keyHash("k"), "k", 1, "HTTP_DOWN", "x", null,
                "http", null, 0, 3, 0L, "2026-10-23T00:00:00", null, null, null, true, "RUNNING", 1L, 4)), "pod1", g, 1L);
        assertThat(sql.readEvalState(g)).hasSize(1);
        assertThat(sql.readLastStatus(g)).hasSize(1);
        assertThat(sql.readTypeState(g)).hasSize(1);
        assertThat(sql.readConfirmations(g)).hasSize(1);
    }

    @Test
    @DisplayName("İki pod aynı anda açılır: CREATE yarışı kaybedilse de tablo varsa hata yok, değişiklik sayılmaz")
    void createRaceLost_isTolerated() {
        AtomicInteger lost = new AtomicInteger();
        JdbcTemplate racing = new JdbcTemplate(mp.dataSource()) {
            @Override
            public void execute(String sql) {
                super.execute(sql);   // öteki pod kurdu …
                if (sql.startsWith("CREATE TABLE")) {
                    lost.incrementAndGet();
                    throw new DuplicateKeyException("pg_type_typname_nsp_index");   // … bu pod yarışı kaybetti
                }
            }
        };
        EvalStateSchemaPatch p = new EvalStateSchemaPatch(racing);
        assertThatCode(p::apply).doesNotThrowAnyException();
        assertThat(lost).hasValue(4);
        for (String t : EvalStateSql.TABLES) assertThat(p.tableReadable(t)).as(t).isTrue();
        assertThat(p.apply()).isZero();
    }

    @Test
    @DisplayName("FX #7: iki pod aynı anda açılır — CREATE INDEX yarışı kaybedilse de dizin varsa WARN yok (DEBUG)")
    void indexRaceLost_noWarnWhenIndexExists() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EvalStateSchemaPatch.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            AtomicInteger lost = new AtomicInteger();
            JdbcTemplate racing = new JdbcTemplate(mp.dataSource()) {
                @Override
                public void execute(String sql) {
                    super.execute(sql);   // öteki pod kurdu …
                    if (sql.startsWith("CREATE INDEX")) {
                        lost.incrementAndGet();
                        throw new DuplicateKeyException("pg_class_relname_nsp_index");   // … bu pod yarışı kaybetti
                    }
                }
            };
            EvalStateSchemaPatch p = new EvalStateSchemaPatch(racing);
            assertThatCode(p::apply).doesNotThrowAnyException();
            assertThat(lost).hasValue(1);
            assertThat(p.indexPresent(new com.sitemonitor.service.schema.SchemaDdlGuard(racing))).isTrue();
            assertThat(logs.list).noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN);

            // Dizin gerçekten yoksa (kurulamadı) WARN yazılır — sessiz yutulmaz.
            mp.jdbc().execute("DROP INDEX idx_mls_type_domain");
            JdbcTemplate failing = new JdbcTemplate(mp.dataSource()) {
                @Override
                public void execute(String sql) {
                    if (sql.startsWith("CREATE INDEX")) throw new DuplicateKeyException("başka hata");
                    super.execute(sql);
                }
            };
            new EvalStateSchemaPatch(failing).apply();
            assertThat(logs.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                    && e.getFormattedMessage().contains("idx_mls_type_domain"));
        } finally {
            logger.detachAppender(logs);
        }
    }

    @Test
    @DisplayName("Veritabanı hatası açılışı durdurmaz (WARN)")
    void failureIsSwallowed() {
        JdbcTemplate broken = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:evalstate-yok;IFEXISTS=TRUE"));
        assertThatCode(() -> new EvalStateSchemaPatch(broken).onReady()).doesNotThrowAnyException();
    }
}
