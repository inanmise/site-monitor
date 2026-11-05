package com.sitemonitor.service.keyword;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anahtar kelime çoklu kural + içerik kolonları (kw sözleşmesi §3) — eksik kolonları ekler, ikinci koşu hiçbir şey yapmaz, tablo
 * yoksa atlar; kolonlar NULLABLE (eski satırlar NULL kalır = bugünkü davranış).
 */
class KeywordRuleSchemaPatchTest {

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
    @DisplayName("eski şema: 13 kolon eklenir, ikinci koşu no-op; eski satırlar NULL; tablo yoksa atlar")
    void addsColumnsIdempotently() {
        KeywordRuleSchemaPatch patch = new KeywordRuleSchemaPatch(mp.jdbc());
        assertThat(patch.apply()).as("tablolar yok").isZero();

        mp.jdbc().execute("CREATE TABLE keyword_monitors (id BIGINT PRIMARY KEY, name VARCHAR(100), url VARCHAR(500))");
        mp.jdbc().execute("CREATE TABLE keyword_results (id BIGINT PRIMARY KEY, monitor_id BIGINT, ok BOOLEAN)");
        mp.jdbc().update("INSERT INTO keyword_monitors VALUES (1, 'a', 'https://a.example.com/')");
        mp.jdbc().update("INSERT INTO keyword_results VALUES (1, 1, TRUE)");

        assertThat(patch.apply()).isEqualTo(KeywordRuleSchemaPatch.COLUMNS.size());
        for (KeywordRuleSchemaPatch.Col c : KeywordRuleSchemaPatch.COLUMNS) {
            assertThat(patch.columnExists(c.table(), c.column())).as(c.table() + "." + c.column()).isTrue();
        }
        assertThat(patch.apply()).isZero();
        assertThat(mp.jdbc().queryForObject("SELECT rules_json FROM keyword_monitors WHERE id = 1", String.class)).isNull();
        assertThat(mp.jdbc().queryForObject("SELECT change_detect FROM keyword_monitors WHERE id = 1", Boolean.class)).isNull();
        assertThat(mp.jdbc().queryForObject("SELECT content_changed FROM keyword_results WHERE id = 1", Boolean.class)).isNull();
        // kolon türleri: değer yazılabilir
        mp.jdbc().update("UPDATE keyword_monitors SET rule_logic = 'OR', min_bytes = 10, change_mask = 'DIGITS,DATES', "
                + "change_selector = '#a' WHERE id = 1");
        mp.jdbc().update("UPDATE keyword_results SET breach_level = 'CRITICAL', content_fingerprint = ?, change_summary = '{}', change_note = 'SELECTOR_NOT_FOUND' "
                + "WHERE id = 1", "f".repeat(64));
    }

    @Test
    @DisplayName("13 kolon: 7 keyword_monitors + 6 keyword_results (sözleşme §3.1 / §3.2; change_note — kw son inceleme O-3 / D-7)")
    void columnCatalogue() {
        assertThat(KeywordRuleSchemaPatch.COLUMNS).extracting(KeywordRuleSchemaPatch.Col::column).containsExactly(
                "rules_json", "rule_logic", "min_bytes", "max_bytes", "change_detect", "change_mask", "change_selector",
                "rule_results_json", "breach_level", "content_fingerprint", "content_changed", "change_summary", "change_note");
    }
}
