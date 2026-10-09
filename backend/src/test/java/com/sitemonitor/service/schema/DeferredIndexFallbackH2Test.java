package com.sitemonitor.service.schema;

import com.sitemonitor.service.schema.DeferredIndexBuilder.Spec;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ertelenmiş indeksler (2026-10-08) GERÇEK şemaya karşı (H2, entity'lerden {@code ddl-auto}): taşınabilir yedekler
 * açılış yaması olarak gerçekten kurulur ve kolon sırası doğrudur; ikinci koşu idempotenttir; PG'ye özgü olanlar dâhil
 * her indeksin adı geçen kolonları entity tablolarında VARDIR (yanlış yazılmış kolon PostgreSQL'de arka planda sessizce
 * başarısız olurdu). PostgreSQL semantiği (INCLUDE, kısmi, fonksiyonel, CONCURRENTLY) {@code SchemaPatchPostgresTest}'te.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:deferredidx;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=DAY,VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeferredIndexFallbackH2Test {

    @Autowired JdbcTemplate jdbc;

    /** Rollup tabloları entity'siz, ham DDL ile kurulur — SchedulerService.applySchemaPatches ile aynı tanım. */
    @BeforeEach
    void rollupTables() {
        for (String[] t : List.of(new String[]{"monitor_check_daily", "day VARCHAR(10)"},
                new String[]{"monitor_check_hourly", "hour_bucket VARCHAR(13)"})) {
            String bucket = t[1].substring(0, t[1].indexOf(' '));
            jdbc.execute("CREATE TABLE IF NOT EXISTS " + t[0] + "(monitor_type VARCHAR(16) NOT NULL, monitor_key VARCHAR(255) NOT NULL, "
                    + t[1] + " NOT NULL, total_checks BIGINT DEFAULT 0, up_checks BIGINT DEFAULT 0, avg_response_ms INTEGER, "
                    + "max_response_ms INTEGER, PRIMARY KEY (monitor_type, monitor_key, " + bucket + "))");
        }
    }

    private List<String> indexColumns(String index) {
        return jdbc.queryForList("SELECT lower(column_name) FROM information_schema.index_columns "
                + "WHERE lower(index_name) = ? ORDER BY ordinal_position", String.class, index);
    }

    private boolean columnExists(String table, String column) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                + "WHERE lower(table_name) = ? AND lower(column_name) = ?", Integer.class, table, column);
        return n != null && n > 0;
    }

    /** DDL'deki "ON tablo(…)" sonrası kolon adları (lower(…), INCLUDE, WHERE dâhil; anahtar sözcükler hariç). */
    private static List<String> referencedColumns(Spec s) {
        String tail = s.postgresDdl().substring(s.postgresDdl().indexOf(" ON " + s.table() + "(") + (" ON " + s.table() + "(").length());
        Set<String> keywords = Set.of("lower", "upper", "include", "where", "true", "false");   // işlev adları kolon değil
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[a-z_]+").matcher(tail.toLowerCase(Locale.ROOT));
        while (m.find()) if (!keywords.contains(m.group())) out.add(m.group());
        return out;
    }

    private static List<String> keyColumns(String ddl) {
        String inner = ddl.substring(ddl.indexOf('(') + 1, ddl.indexOf(')'));
        List<String> out = new ArrayList<>();
        for (String c : inner.split(",")) out.add(c.trim().toLowerCase(Locale.ROOT));
        return out;
    }

    @Test
    @DisplayName("H2: taşınabilir yedekler açılış yaması olarak kurulur (kolon sırası doğru), ikinci koşu idempotent")
    void fallbacks_areCreated_withExactColumns_andIdempotent() {
        SchemaPatchRunner runner = new SchemaPatchRunner(jdbc);
        DeferredIndexBuilder builder = new DeferredIndexBuilder(jdbc);
        builder.applyFallbacks(runner::patch);
        builder.applyFallbacks(runner::patch);   // ikinci açılış

        int portable = 0;
        for (Spec s : DeferredIndexBuilder.CATALOG) {
            if (s.fallbackDdl() == null) {
                assertThat(indexColumns(s.name())).as("%s PG'ye özgü — H2'de kurulmamalı", s.name()).isEmpty();
                continue;
            }
            portable++;
            assertThat(indexColumns(s.name())).as("%s kolonları", s.name()).containsExactlyElementsOf(keyColumns(s.fallbackDdl()));
        }
        assertThat(portable).isEqualTo(10);
        assertThat(builder.startInBackground()).as("H2'de arka plan kurulumu yok").isNull();
    }

    @Test
    @DisplayName("Her indeksin (PG'ye özgüler dâhil) adı geçen kolonları gerçek tabloda var")
    void everyReferencedColumn_existsInRealSchema() {
        for (Spec s : DeferredIndexBuilder.CATALOG) {
            List<String> cols = referencedColumns(s);
            assertThat(cols).as("%s kolon listesi", s.name()).isNotEmpty();
            for (String c : cols) {
                assertThat(columnExists(s.table(), c)).as("%s: %s.%s kolonu yok", s.name(), s.table(), c).isTrue();
            }
        }
    }
}
