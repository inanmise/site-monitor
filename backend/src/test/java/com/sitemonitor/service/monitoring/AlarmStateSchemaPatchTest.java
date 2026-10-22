package com.sitemonitor.service.monitoring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod R1-B (2026-10-21) şema yaması — İDEMPOTENT (H2, PostgreSQL kipi): eksik {@code change_state} kolonları ve
 * {@code monitor_eval_inbox} tablosu + dizini kurulur; ikinci koşu hiçbir şey eklemez; tablo yoksa düşmez.
 */
class AlarmStateSchemaPatchTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:alarmstatepatch" + SEQ.incrementAndGet()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
    }

    @AfterEach
    void tearDown() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    @DisplayName("Eksik kolonlar + gelen kutusu kurulur; ikinci koşu 0 ekler")
    void appliesOnceIdempotent() {
        jdbc.execute("CREATE TABLE dns_records (id BIGINT PRIMARY KEY, monitor_id BIGINT, checked_at VARCHAR(40))");
        jdbc.execute("CREATE TABLE domain_checks (id BIGINT PRIMARY KEY, monitor_id BIGINT, checked_at VARCHAR(40))");
        AlarmStateSchemaPatch patch = new AlarmStateSchemaPatch(jdbc);

        assertThat(patch.apply()).isEqualTo(2);
        assertThat(patch.columnExists("dns_records", ChangeStates.COLUMN)).isTrue();
        assertThat(patch.columnExists("domain_checks", ChangeStates.COLUMN)).isTrue();
        assertThat(patch.tableExists(ManualEvalInbox.TABLE)).isTrue();
        Integer idx = jdbc.queryForObject("SELECT count(*) FROM information_schema.indexes WHERE lower(index_name) = 'idx_mei_claim'",
                Integer.class);
        assertThat(idx).isPositive();

        assertThat(patch.apply()).as("ikinci koşu").isZero();
        jdbc.update("INSERT INTO dns_records (id, monitor_id, checked_at, change_state) VALUES (1, 5, 'x', ?)", ChangeStates.PENDING);
        assertThat(jdbc.queryForObject("SELECT change_state FROM dns_records WHERE id = 1", String.class))
                .isEqualTo(ChangeStates.PENDING);
    }

    @Test
    @DisplayName("Değişiklik tabloları yoksa düşmez (yalnız gelen kutusu kurulur)")
    void missingTablesTolerated() {
        AlarmStateSchemaPatch patch = new AlarmStateSchemaPatch(jdbc);
        assertThat(patch.apply()).isZero();
        assertThat(patch.tableExists(ManualEvalInbox.TABLE)).isTrue();
    }
}
