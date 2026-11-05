package com.sitemonitor.service.keyword;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anahtar kelime izleme zenginleştirmesi kolonları (kw sözleşmesi §3.1–3.2) — eksik kolonları ekler, ikinci koşu hiçbir şey
 * yapmaz, tablo yoksa atlar; kolonlar NULLABLE (eski satırlar NULL = bugünkü davranış).
 */
class KeywordSchemaPatchTest {

    MultiPodTestSupport mp;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    @Test
    @DisplayName("eski şema: 13 izleme + 2 sonuç kolonu eklenir, ikinci koşu no-op; eski satır NULL; tablo yoksa atlar")
    void addsColumnsIdempotently() {
        KeywordSchemaPatch patch = new KeywordSchemaPatch(mp.jdbc());
        assertThat(patch.apply()).as("tablolar yok").isZero();

        mp.jdbc().execute("CREATE TABLE keyword_monitors (id BIGINT PRIMARY KEY, name VARCHAR(255), url VARCHAR(255), "
                + "keyword VARCHAR(255), case_sensitive BOOLEAN)");
        mp.jdbc().execute("CREATE TABLE keyword_results (id BIGINT PRIMARY KEY, monitor_id BIGINT, via VARCHAR(10))");
        mp.jdbc().update("INSERT INTO keyword_monitors VALUES (1, 'a', 'https://x.example/', 'k', false)");
        mp.jdbc().update("INSERT INTO keyword_results VALUES (1, 1, 'direct')");

        assertThat(patch.apply()).isEqualTo(KeywordSchemaPatch.COLUMNS.size()).isEqualTo(15);
        for (KeywordSchemaPatch.Col c : KeywordSchemaPatch.COLUMNS) {
            assertThat(patch.columnExists(c.table(), c.column())).as(c.table() + "." + c.column()).isTrue();
        }
        assertThat(patch.apply()).as("ikinci koşu").isZero();
        assertThat(mp.jdbc().queryForObject("SELECT expected_status FROM keyword_monitors WHERE id = 1", String.class)).isNull();
        assertThat(mp.jdbc().queryForObject("SELECT follow_redirects FROM keyword_monitors WHERE id = 1", Boolean.class)).isNull();
        assertThat(mp.jdbc().queryForObject("SELECT decoded_charset FROM keyword_results WHERE id = 1", String.class)).isNull();
        // uzun değer sığar (VARCHAR(2000) son URL, TEXT gövde)
        mp.jdbc().update("UPDATE keyword_monitors SET expected_final_url = ?, request_body = ? WHERE id = 1",
                "https://x.example/" + "a".repeat(1900), "b".repeat(70_000));
    }
}
