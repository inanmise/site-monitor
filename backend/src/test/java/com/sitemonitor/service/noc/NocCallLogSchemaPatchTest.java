package com.sitemonitor.service.noc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code noc_call_log} şema yaması İDEMPOTENT (H2): düşürülen kolon ve indeks geri gelir, ikinci koşu hiçbir şey
 * eklemez; bütün veri kolonları NULLABLE (ddl-auto'ya NOT NULL/kısıt bırakılmaz — proje tuzağı).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:noccalllogpatch;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@Import(NocCallLogSchemaPatch.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NocCallLogSchemaPatchTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired NocCallLogSchemaPatch patch;

    private int indexCount(String name) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM information_schema.indexes WHERE lower(index_name)=?",
                Integer.class, name);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("düşen kolon + indeks geri gelir; ikinci koşu 0; veri kolonları nullable")
    void idempotent() {
        assertThat(patch.tableExists()).isTrue();
        jdbc.execute("DROP INDEX IF EXISTS idx_noc_call_log_alert");
        jdbc.execute("ALTER TABLE noc_call_log DROP COLUMN note");
        assertThat(patch.columnExists("note")).isFalse();
        assertThat(indexCount("idx_noc_call_log_alert")).isZero();

        assertThat(patch.apply()).isEqualTo(1);
        assertThat(patch.columnExists("note")).isTrue();
        assertThat(indexCount("idx_noc_call_log_alert")).isPositive();
        assertThat(indexCount("idx_noc_call_log_team")).isPositive();
        assertThat(patch.apply()).isZero();

        for (String col : NocCallLogSchemaPatch.COLUMNS.keySet()) {
            String nullable = jdbc.queryForObject(
                    "SELECT is_nullable FROM information_schema.columns WHERE lower(table_name)='noc_call_log' AND lower(column_name)=?",
                    String.class, col);
            assertThat(nullable).as(col).isEqualToIgnoringCase("YES");
        }
    }
}
