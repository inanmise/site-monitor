package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code http_metric_minute.status_codes} için AÇIK, idempotent şema yaması (2026-09-28c, B3).
 *
 * <p>Kolon dolu tabloya SONRADAN eklendi; ddl-auto'ya güvenilmez (proje kuralı — "ddl-auto sessiz kısıt tuzağı").
 * ALTER düşerse {@code HttpMetricsService.flushPending} her dakikanın yazımını yutuyor ve İstek Gezgini kalıcı boş
 * kalıyordu. Test iki şeyi pinler: (1) yama satırı {@code applySchemaPatches}'te VAR (kaynak taraması — satır silinirse
 * kırmızı); (2) tam o DDL, kolonsuz eski tabloda (PostgreSQL kipinde H2) çalışır, kolon NULL'lanabilir gelir ve eski
 * satırlar/yeni yazımlar sorunsuzdur. İdempotentlik {@code patch()}'in {@code columnExists} kontrolündedir.
 */
class HttpMetricStatusCodesPatchTest {

    private static final Path SCHEDULER = Path.of("src/main/java/com/sitemonitor/service/SchedulerService.java");

    private static String patchDdl() throws Exception {
        Matcher m = Pattern.compile("patch[(]\"(ALTER TABLE http_metric_minute ADD COLUMN status_codes [^\"]*)\"[)]")
                .matcher(Files.readString(SCHEDULER));
        assertThat(m.find()).as("applySchemaPatches içinde status_codes yaması").isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("yama satırı var ve kolonsuz eski tabloda çalışır; kolon NULL'lanabilir, eski satır korunur")
    void patchAddsNullableColumnToLegacyTable() throws Exception {
        String ddl = patchDdl();
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:hmm_status_patch;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
             Statement st = c.createStatement()) {
            // 2026-09-28 öncesi şekil: status_codes yok, içinde satır var.
            st.execute("CREATE TABLE http_metric_minute (id BIGINT PRIMARY KEY, bucket_minute VARCHAR(19), endpoint VARCHAR(255), req_count BIGINT)");
            st.execute("INSERT INTO http_metric_minute VALUES (1, '2026-09-27T10:00:00', 'GET /api/x', 5)");

            st.execute(ddl);

            try (ResultSet rs = st.executeQuery("SELECT is_nullable, data_type FROM information_schema.columns "
                    + "WHERE lower(table_name) = 'http_metric_minute' AND lower(column_name) = 'status_codes'")) {
                assertThat(rs.next()).as("kolon oluştu").isTrue();
                assertThat(rs.getString(1)).isEqualToIgnoringCase("YES");
            }
            try (ResultSet rs = st.executeQuery("SELECT status_codes FROM http_metric_minute WHERE id = 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isNull();   // eski satır: NULL = "sınıfsız" (sorgu tarafı öyle sayar)
            }
            st.execute("INSERT INTO http_metric_minute VALUES (2, '2026-09-28T10:00:00', 'GET /api/x', 3, '200:3')");
        }
    }
}
