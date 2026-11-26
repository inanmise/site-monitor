package com.sitemonitor.repository;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CertificateCheckRepository.firstSightingOf} (H paketi 2026-11-26, sertifika B2) GERÇEK PostgreSQL'de —
 * {@code mvn -Ppostgres-it test}. Sorgu kontrol hattında, sunulan ≠ beklenen iken çağrılır: en eski eşleşen satırı döner ve
 * hem özel (custom) hem genel (generic) planda {@code idx_cc_domain_ts} ile TEK alan adının satırlarını okur (tablo taraması
 * yok, bütün alan adlarını tarayan {@code idx_cc_checked_at} aralığı yok) — Performance rules'taki "single monitor" kalıbı
 * ({@code domain IN (:d, :d)} + {@code ORDER BY domain, checkedAt}). Gerçekçi istatistikte planlayıcı bit eşlemli okuma +
 * parmak izi süzgecinden SONRA birkaç satırlık sıralama seçebilir (sıralanan yalnız eşleşen satırlar) — kabul edilir.
 */
@PostgresIntegration
class CertFirstSightingPostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static void check(String domain, String fp, String at, String notAfter) {
        jdbc().update("INSERT INTO certificate_checks(domain, fingerprint, checked_at, not_after, status) VALUES (?,?,?,?,?)",
                domain, fp, at, notAfter, "valid");
    }

    @Test
    @DisplayName("firstSightingOf: bu uçta beklenen parmak izinin EN ESKİ satırı; başka alan adının satırı karışmaz; yoksa boş")
    void returnsEarliestRowOfThisDomain() {
        String d = "fs" + ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999) + ".example.com";
        check(d, "AAAA", "2011-07-03T10:00:00", "2012-07-01T00:00:00");
        check(d, "BBBB", "2011-07-02T10:00:00", "2012-08-01T00:00:00");
        check(d, "BBBB", "2011-07-05T10:00:00", "2012-08-01T00:00:00");
        check("other-" + d, "BBBB", "2011-06-01T10:00:00", "2012-08-01T00:00:00");
        CertificateCheckRepository repo = PostgresIt.app().bean(CertificateCheckRepository.class);
        String all = "2000-01-01T00:00:00";
        List<Object[]> rows = repo.firstSightingOf(d, List.of("BBBB", "bbbb"), all);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo("2011-07-02T10:00:00");
        assertThat(rows.get(0)[1]).isEqualTo("2012-08-01T00:00:00");
        assertThat(repo.firstSightingOf(d, List.of("CCCC"), all)).isEmpty();
        // Pencere alt sınırı: daha eski satır okunmaz
        List<Object[]> windowed = repo.firstSightingOf(d, List.of("BBBB"), "2011-07-04T00:00:00");
        assertThat(windowed).hasSize(1);
        assertThat(windowed.get(0)[0]).isEqualTo("2011-07-05T10:00:00");
    }

    @Test
    @DisplayName("EXPLAIN firstSightingOf (gerçekçi istatistik): özel ve GENEL planda idx_cc_domain_ts; Seq Scan ve tüm alan adlarını "
            + "tarayan idx_cc_checked_at yok")
    void planUsesDomainTimeIndexInCustomAndGenericPlans() {
        String sql = "SELECT c.checked_at, c.not_after FROM certificate_checks c WHERE c.domain IN ($1, $1) "
                + "AND c.checked_at >= $4 AND c.fingerprint IN ($2, $3) ORDER BY c.domain ASC, c.checked_at ASC LIMIT 1";
        for (String mode : List.of("force_custom_plan", "force_generic_plan")) {
            String plan = jdbc().execute((ConnectionCallback<String>) con -> {
                boolean auto = con.getAutoCommit();
                con.setAutoCommit(false);
                try (Statement st = con.createStatement()) {
                    // GERÇEKÇİ istatistik (bağımsız inceleme: sorgu artık checked_at alt sınırı taşır): boş tabloda planlayıcı
                    // eşit maliyette idx_cc_checked_at + Sort seçebiliyordu. 200 alan adı × 100 kontrol (son ~75 gün, saatlik
                    // tarama gibi) + ANALYZE — işlemle birlikte geri alınır (pg_statistic de işlemsel), diğer testlere sızmaz.
                    st.execute("INSERT INTO certificate_checks(domain, fingerprint, checked_at, not_after, status) "
                            + "SELECT 'plan' || (g % 200) || '.example.com', 'FP' || (g % 200), "
                            + "to_char(timestamp '2025-06-01' + (g / 200) * interval '18 hours', 'YYYY-MM-DD\"T\"HH24:MI:SS'), "
                            + "'2026-12-01T00:00:00', 'valid' FROM generate_series(1, 20000) g");
                    st.execute("ANALYZE certificate_checks");
                    st.execute("SET LOCAL enable_seqscan = off");
                    st.execute("SET LOCAL plan_cache_mode = " + mode);
                    st.execute("PREPARE fs_probe(text, text, text, text) AS " + sql);
                    StringBuilder out = new StringBuilder();
                    try (ResultSet rs = st.executeQuery(
                            "EXPLAIN EXECUTE fs_probe('x.example.com', 'AA', 'aa', '2025-01-01T00:00:00')")) {
                        while (rs.next()) out.append(rs.getString(1)).append('\n');
                    }
                    st.execute("DEALLOCATE fs_probe");
                    return out.toString();
                } finally {
                    con.rollback();
                    con.setAutoCommit(auto);
                }
            });
            assertThat(plan).as(mode + ":\n" + plan).contains("idx_cc_domain_ts").doesNotContain("Seq Scan")
                    .doesNotContain("idx_cc_checked_at");
        }
    }
}
