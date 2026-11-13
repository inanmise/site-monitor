package com.sitemonitor.service.sqlplayground;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** KAPI: veri sözlüğündeki her tablo / kolon şemada gerçekten var (yanlış ad bırakılmaz); hassas kolon "maskelenir" der. */
class SqlDataDictionaryTest {

    @Test
    @DisplayName("Sözlükteki her tablo ve kolon varlık şemasında var")
    void everyNameExists() {
        Map<String, Set<String>> schema = SchemaSources.entityColumns();
        List<String> wrong = new ArrayList<>();
        for (SqlDataDictionary.Tbl t : SqlDataDictionary.TABLES) {
            Set<String> cols = schema.get(t.name());
            if (cols == null) { wrong.add(t.name()); continue; }
            for (SqlDataDictionary.Col c : t.cols()) if (!cols.contains(c.name())) wrong.add(t.name() + "." + c.name());
        }
        assertThat(wrong).as("Sözlükte şemada olmayan adlar: %s", wrong).isEmpty();
        assertThat(SqlDataDictionary.TABLES.size()).isGreaterThanOrEqualTo(25);
    }

    @Test
    @DisplayName("render: tablolar + kolon açıklamaları; hassas kolon açıklaması maskelendiğini söyler")
    @SuppressWarnings("unchecked")
    void render() {
        Map<String, Object> out = SqlDataDictionary.render();
        Map<String, Object> tables = (Map<String, Object>) out.get("tables");
        Map<String, Object> users = (Map<String, Object>) tables.get("app_users");
        assertThat((String) users.get("description")).isNotBlank();
        Map<String, String> cols = (Map<String, String>) users.get("columns");
        assertThat(cols.get("password_hash")).contains("maskelenir");
        assertThat(cols.get("username")).doesNotContain("maskelenir");
    }
}
