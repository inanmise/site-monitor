package com.certmonitor.util;

import com.certmonitor.service.SqlPlaygroundService;
import com.certmonitor.repository.SqlQueryHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that executes every curated sample query from {@link SqlSamples}
 * against the live JPA schema (H2 in PostgreSQL compatibility mode) — catches
 * column rename / typo bugs immediately. Queries that use PostgreSQL-only
 * functions (pg_size_pretty, quote_ident, etc.) are still parsed and routed; we
 * tolerate "function not found" errors for those rows.
 */
@DataJpaTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class SqlSamplesIntegrationTest {

    /**
     * Functions H2 in PG-compat mode does not implement. Queries containing any
     * of these are expected to fail with a "function not found" error and are
     * not counted as schema bugs.
     */
    private static final Pattern PG_ONLY_FN = Pattern.compile(
        "pg_size_pretty|pg_total_relation_size|quote_ident|pg_relation_size|pg_stat_user_tables",
        Pattern.CASE_INSENSITIVE);

    @Autowired DataSource dataSource;
    @Autowired SqlQueryHistoryRepository historyRepo;

    private SqlPlaygroundService service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        service = new SqlPlaygroundService(jdbc, historyRepo);
    }

    @Test
    @DisplayName("Every curated sample passes read-only validation (SELECT/WITH only)")
    void allSamples_passReadOnlyValidation() {
        List<String> failures = new ArrayList<>();
        for (Map<String, String> sample : SqlSamples.list()) {
            String label = sample.get("label");
            String sql = sample.get("sql");
            try {
                // execute() runs validateReadOnly inside; we only care it does not throw
                // before attempting JDBC — so we wrap and inspect the exception type.
                service.execute(sql, "test");
            } catch (IllegalArgumentException e) {
                // Read-only validation rejected the sample → real bug
                failures.add(label + " :: " + e.getMessage());
            } catch (Exception ignored) {
                // Other exceptions (JDBC, function-not-found) are evaluated separately
            }
        }
        assertThat(failures)
            .as("samples that fail read-only validation")
            .isEmpty();
    }

    @Test
    @DisplayName("Every sample executes against the JPA schema without column-resolution errors")
    void allSamples_runWithoutColumnErrors() {
        List<String> realBugs = new ArrayList<>();
        for (Map<String, String> sample : SqlSamples.list()) {
            String label = sample.get("label");
            String sql = sample.get("sql");
            boolean usesPgOnly = PG_ONLY_FN.matcher(sql).find();

            try {
                Map<String, Object> result = service.execute(sql, "test");
                if (Boolean.TRUE.equals(result.get("ok"))) {
                    // Sample ran cleanly — column refs and syntax all valid in H2/PG mode.
                    continue;
                }
                String error = (String) result.get("error");
                if (error == null) continue;
                String msg = error.toLowerCase();
                if (usesPgOnly && isFunctionError(msg)) {
                    // expected — H2 lacks the PG-specific function
                    continue;
                }
                if (isColumnOrTableError(msg)) {
                    realBugs.add(label + " :: " + error);
                }
                // any other error is tolerated (function/syntax quirks in H2)
            } catch (DataAccessException dae) {
                // Caught here only if service did not wrap; treat as potential bug
                realBugs.add(label + " :: " + dae.getMessage());
            }
        }
        assertThat(realBugs)
            .as("sample queries that reference missing columns/tables")
            .isEmpty();
    }

    private boolean isColumnOrTableError(String msg) {
        return msg.contains("column ") && (msg.contains("not found") || msg.contains("does not exist"))
            || msg.contains("table ") && (msg.contains("not found") || msg.contains("does not exist"))
            || msg.contains("object not found")
            || msg.contains("not found in any table");
    }

    private boolean isFunctionError(String msg) {
        return msg.contains("function ") && (msg.contains("not found") || msg.contains("does not exist"))
            || msg.contains("unknown function");
    }
}
