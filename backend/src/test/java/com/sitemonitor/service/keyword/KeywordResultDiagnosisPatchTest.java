package com.sitemonitor.service.keyword;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code keyword_results} teşhis kolonları için AÇIK, idempotent şema yamaları (2026-10-04). Kolonlar DOLU tabloya
 * sonradan eklendi; ddl-auto'ya güvenilmez (proje kuralı). Test (1) her kolonun yama satırının {@code applySchemaPatches}'te
 * VAR olduğunu (kaynak taraması) ve (2) o DDL'lerin kolonsuz eski tabloda (PostgreSQL kipinde H2) çalıştığını, kolonların
 * NULL'lanabilir geldiğini, eski satırın NULL kaldığını (uydurma değer yok) ve yeni satırın yazılabildiğini pinler.
 * PostgreSQL'de varlık kontrolü {@code SchemaPatchPostgresTest.everyAddColumnPatch_existsWhereItsTableExists}'te.
 */
class KeywordResultDiagnosisPatchTest {

    private static final Path SCHEDULER = Path.of("src/main/java/com/sitemonitor/service/SchedulerService.java");
    private static final List<String> COLUMNS = List.of("failure_reason", "failure_detail", "final_url", "redirect_count",
            "content_type", "body_bytes", "body_truncated", "charset", "via", "hints", "excerpt");

    private static List<String> patches() throws Exception {
        String src = Files.readString(SCHEDULER);
        List<String> out = new ArrayList<>();
        for (String col : COLUMNS) {
            Matcher m = Pattern.compile("patch[(]\"(ALTER TABLE keyword_results ADD COLUMN " + col + " [^\"]*)\"[)]").matcher(src);
            assertThat(m.find()).as("applySchemaPatches içinde keyword_results." + col + " yaması").isTrue();
            assertThat(m.group(1)).as(col + " DEFAULT taşımamalı (eski satıra uydurma değer yazılmaz)").doesNotContainIgnoringCase("DEFAULT");
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    @DisplayName("11 yama satırı var; kolonsuz eski tabloda çalışır; kolonlar NULL'lanabilir, eski satır NULL, yeni satır yazılır")
    void patchesApplyToLegacyTable() throws Exception {
        List<String> ddl = patches();
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:kwr_diag_patch;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE keyword_results (id BIGINT PRIMARY KEY, monitor_id BIGINT NOT NULL, found BOOLEAN NOT NULL, "
                    + "ok BOOLEAN NOT NULL, error VARCHAR(255), checked_at VARCHAR(32))");
            st.execute("INSERT INTO keyword_results VALUES (1, 9, false, false, NULL, '2026-10-01T10:00:00')");
            for (String d : ddl) st.execute(d);
            for (String col : COLUMNS) {
                try (ResultSet rs = st.executeQuery("SELECT is_nullable FROM information_schema.columns "
                        + "WHERE lower(table_name) = 'keyword_results' AND lower(column_name) = '" + col + "'")) {
                    assertThat(rs.next()).as(col + " oluştu").isTrue();
                    assertThat(rs.getString(1)).as(col + " NULL'lanabilir").isEqualToIgnoringCase("YES");
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT failure_reason, hints, excerpt, body_truncated FROM keyword_results WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNull();
                assertThat(rs.getString(2)).isNull();
                assertThat(rs.getString(3)).isNull();
                assertThat(rs.getObject(4)).isNull();
            }
            st.execute("INSERT INTO keyword_results (id, monitor_id, found, ok, checked_at, failure_reason, failure_detail, final_url, "
                    + "redirect_count, content_type, body_bytes, body_truncated, charset, via, hints, excerpt) VALUES "
                    + "(2, 9, false, false, '2026-10-04T10:00:00', 'KEYWORD_NOT_FOUND', 'ayrıntı', 'https://site.example.com/', 1, "
                    + "'text/html', 1234, false, 'UTF-8', 'direct', '[\"LOGIN_PAGE\"]', 'alıntı')");
        }
    }
}
