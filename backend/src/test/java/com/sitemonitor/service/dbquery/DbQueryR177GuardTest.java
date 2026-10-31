package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r177 güvenlik denetimi: sunucu tarafında koca tabloyu tek değere çeviren XML fonksiyonları ve istatistik sıfırlama yasak
 * (H-4); sorgu biçimi (SELECT *, GROUP BY'sız toplama, dış kolon adları) uyarılar için çıkarılır (engellemez).
 */
class DbQueryR177GuardTest {

    @BeforeEach
    void clear() {
        DbQuerySqlGuard.clearCache();
    }

    private static DbQuerySqlGuard.Result pg(String sql) {
        return DbQuerySqlGuard.check(sql, DbType.POSTGRESQL);
    }

    @Test
    void serverSideDumpFunctionsAreRejected() {
        for (String sql : List.of(
                "SELECT table_to_xml('public.musteri', true, false, '')",
                "SELECT table_to_xmlschema('musteri', true, false, '')",
                "SELECT table_to_xml_and_xmlschema('musteri', true, false, '')",
                "SELECT pg_catalog.table_to_xml('musteri', true, false, '')",
                "SELECT query_to_xml('select * from musteri', true, false, '')",
                "SELECT cursor_to_xml('c', 10, true, false, '')",
                "SELECT schema_to_xml('public', true, false, '')",
                "SELECT database_to_xml(true, false, '')",
                "SELECT pg_stat_statements_reset()")) {
            DbQuerySqlGuard.Result g = pg(sql);
            assertThat(g.ok()).as(sql).isFalse();
            assertThat(g.violations()).as(sql).extracting(DbQuerySqlGuard.Violation::code)
                    .contains(DbQuerySqlGuard.SQL_FORBIDDEN_FUNCTION);
        }
        // aynı adla başlayan kolon / tablo adı yasak değil
        assertThat(pg("SELECT table_to_xml_count FROM rapor").ok()).isTrue();
    }

    @Test
    void shapeOfTheOuterSelect() {
        DbQuerySqlGuard.Shape star = pg("SELECT * FROM siparis").shape();
        assertThat(star.known()).isTrue();
        assertThat(star.selectStar()).isTrue();
        assertThat(star.singleRowAggregate()).isFalse();
        assertThat(pg("SELECT s.*, 1 AS x FROM siparis s").shape().selectStar()).isTrue();

        DbQuerySqlGuard.Shape count = pg("SELECT COUNT(*) AS adet FROM siparis WHERE durum = 'HATA'").shape();
        assertThat(count.singleRowAggregate()).isTrue();
        assertThat(count.selectStar()).isFalse();
        assertThat(count.columns()).containsExactly("adet");
        assertThat(pg("SELECT max(tutar), min(tutar) FROM siparis").shape().singleRowAggregate()).isTrue();
        // GROUP BY satır başına sonuç verir — tuzak değil
        assertThat(pg("SELECT durum, COUNT(*) FROM siparis GROUP BY durum").shape().singleRowAggregate()).isFalse();
        DbQuerySqlGuard.Shape cols = pg("SELECT id, \"Musteri_No\", tutar AS t FROM siparis").shape();
        assertThat(cols.columns()).containsExactly("id", "Musteri_No", "t");
        assertThat(cols.singleRowAggregate()).isFalse();
        // parantezli dış sorgu açılır; birleşim kümesi bilinir ama işaretsiz
        assertThat(pg("(SELECT COUNT(*) FROM t)").shape().singleRowAggregate()).isTrue();
        DbQuerySqlGuard.Shape union = pg("SELECT COUNT(*) FROM a UNION ALL SELECT COUNT(*) FROM b").shape();
        assertThat(union.known()).isTrue();
        assertThat(union.singleRowAggregate()).isFalse();
        // reddedilen / ayrıştırılamayan sorguda biçim bilinmez
        assertThat(pg("DELETE FROM t").shape().known()).isFalse();
    }
}
