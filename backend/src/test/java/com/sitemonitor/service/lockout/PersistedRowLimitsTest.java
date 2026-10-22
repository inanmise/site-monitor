package com.sitemonitor.service.lockout;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kalıcı satırları sayan oran sınırları (2026-10-23, çok pod Sürüm 2a — MP-32 / MP-33) — GERÇEK şema (ddl-auto, H2
 * PostgreSQL kipi). Sayım yan etkisi olan eylemin yazdığı satırdan: hangi pod yazdıysa yazsın aynı sayı.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:persistedrowlimits;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class PersistedRowLimitsTest {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final long NOW = Instant.parse("2026-10-23T10:00:00Z").toEpochMilli();

    @Autowired DataSource dataSource;
    JdbcTemplate jdbc;
    PersistedRowLimits limits;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM login_issue_reports");
        limits = new PersistedRowLimits(jdbc);
    }

    /** NOT_SUPPORTED satırları işler — önbellekli @DataJpaTest bağlamını paylaşan sonraki testler temiz başlasın. */
    @AfterEach
    void cleanUpCommittedRows() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM login_issue_reports");
    }

    private void reveal(Long actorId, String actor, long atMs) {
        jdbc.update("INSERT INTO audit_log (event_type, event_time, actor, actor_id, outcome) VALUES (?, ?, ?, ?, 'SUCCESS')",
                PersistedRowLimits.EVENT_SECRET_REVEAL, ISO.format(Instant.ofEpochMilli(atMs)), actor, actorId);
    }

    private void report(String source, String ip, long atMs) {
        jdbc.update("INSERT INTO login_issue_reports (reported_at, message, status, image_count, source, ip_address) "
                + "VALUES (?, 'm', 'OPEN', 0, ?, ?)", ISO.format(Instant.ofEpochMilli(atMs)), source, ip);
    }

    @Test
    @DisplayName("Gizli değer gösterimi: kullanıcı kimliğiyle son dakika / son saat ayrı sayılır, en eski anlar döner; başka kullanıcı ve başka olay sayılmaz")
    void secretReveals_countsPerActor() {
        reveal(7L, "ALICE", NOW - 3_600_000L - 1_000L);   // saatin dışında
        reveal(7L, "ALICE", NOW - 3_000_000L);            // saat içi
        reveal(7L, "ALICE", NOW - 59_000L);               // dakika içi
        reveal(7L, "ALICE", NOW - 1_000L);
        reveal(8L, "BOB", NOW - 1_000L);                   // başka kullanıcı
        jdbc.update("INSERT INTO audit_log (event_type, event_time, actor, actor_id) VALUES ('LOGIN', ?, 'ALICE', 7)",
                ISO.format(Instant.ofEpochMilli(NOW - 1_000L)));

        PersistedRowLimits.Window w = limits.secretReveals(7L, "ALICE", NOW, 60_000L, 3_600_000L);
        assertThat(w.hour()).isEqualTo(3);
        assertThat(w.minute()).isEqualTo(2);
        assertThat(w.hourOldestMs()).isEqualTo(NOW - 3_000_000L);
        assertThat(w.minuteOldestMs()).isEqualTo(NOW - 59_000L);
    }

    @Test
    @DisplayName("Kimliksiz oturum: kullanıcı adıyla sayılır; hiç kimlik yoksa null (yalnız pod sınırı)")
    void secretReveals_byActorName() {
        reveal(null, "LEGACY", NOW - 5_000L);
        assertThat(limits.secretReveals(null, "LEGACY", NOW, 60_000L, 3_600_000L).minute()).isEqualTo(1);
        assertThat(limits.secretReveals(null, null, NOW, 60_000L, 3_600_000L)).isNull();
        PersistedRowLimits.Window none = limits.secretReveals(99L, "X", NOW, 60_000L, 3_600_000L);
        assertThat(none.hour()).isZero();
        assertThat(none.hourOldestMs()).isNull();
    }

    @Test
    @DisplayName("Sorun bildirimi: kaynak + IP + son bir saat; kayıtla AYNI kırpma (50 karakter); başka kaynak / IP / eski satır sayılmaz")
    void issueReports_countsPerSourceAndIp() {
        report("LOGIN", "10.1.2.3", NOW - 10_000L);
        report("LOGIN", "10.1.2.3", NOW - 3_000_000L);
        report("LOGIN", "10.1.2.3", NOW - 3_700_000L);       // saatin dışında
        report("CLIENT_ERROR", "10.1.2.3", NOW - 10_000L);   // başka kaynak
        report("LOGIN", "10.9.9.9", NOW - 10_000L);          // başka IP
        String longIp = "f".repeat(80);
        report("LOGIN", longIp.substring(0, 50), NOW - 10_000L);

        PersistedRowLimits.Reports login = limits.issueReports("LOGIN", "10.1.2.3", NOW - 3_600_000L);
        assertThat(login.count()).isEqualTo(2);
        assertThat(login.oldestMs()).as("ilk yerin boşalma anı = en eski + pencere").isEqualTo(NOW - 3_000_000L);
        assertThat(limits.issueReports("CLIENT_ERROR", " 10.1.2.3 ", NOW - 3_600_000L).count()).isEqualTo(1);
        assertThat(limits.issueReports("LOGIN", longIp, NOW - 3_600_000L).count()).isEqualTo(1);
        PersistedRowLimits.Reports none = limits.issueReports("LOGIN", "10.0.0.250", NOW - 3_600_000L);
        assertThat(none.count()).isZero();
        assertThat(none.oldestMs()).isNull();
        assertThat(limits.issueReports("LOGIN", null, NOW - 3_600_000L)).isNull();
    }

    @Test
    @DisplayName("Okuma hatası → null (çağıran pod sınırına güvenir; asla istisna)")
    void readFailure_null() {
        jdbc.execute("ALTER TABLE login_issue_reports RENAME TO login_issue_reports_x");
        try {
            assertThat(limits.issueReports("LOGIN", "10.1.2.3", NOW)).isNull();
        } finally {
            jdbc.execute("ALTER TABLE login_issue_reports_x RENAME TO login_issue_reports");
        }
        jdbc.execute("ALTER TABLE audit_log RENAME TO audit_log_x");
        try {
            assertThat(limits.secretReveals(7L, "A", NOW, 60_000L, 3_600_000L)).isNull();
        } finally {
            jdbc.execute("ALTER TABLE audit_log_x RENAME TO audit_log");
        }
    }

    @Test
    @DisplayName("parse: ISO saniye → epoch ms; boş / bozuk → null")
    void parse() {
        assertThat(PersistedRowLimits.parse("2026-10-23T10:00:00")).isEqualTo(NOW);
        assertThat(PersistedRowLimits.parse(null)).isNull();
        assertThat(PersistedRowLimits.parse("bozuk")).isNull();
    }
}
