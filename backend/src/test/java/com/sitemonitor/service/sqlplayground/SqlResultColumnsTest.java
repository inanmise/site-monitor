package com.sitemonitor.service.sqlplayground;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Sonuç kolon adı tekilleştirmesi (2026-11-13): aynı adlı kolonlar sessizce birbirinin üstüne yazılmasın. */
class SqlResultColumnsTest {

    @Test
    @DisplayName("Benzersiz adlar birebir aynı; yeniden adlandırma yok")
    void uniqueUnchanged() {
        List<Map<String, String>> r = new ArrayList<>();
        assertThat(SqlResultColumns.uniqueLabels(new String[]{"id", "name", "Email"}, r)).containsExactly("id", "name", "Email");
        assertThat(r).isEmpty();
    }

    @Test
    @DisplayName("İlk geçiş aynen, sonrakiler _2, _3; var olan adla ve büyük/küçük harfle çakışmaz")
    void duplicatesRenamed() {
        List<Map<String, String>> r = new ArrayList<>();
        assertThat(SqlResultColumns.uniqueLabels(new String[]{"id", "id", "id"}, r)).containsExactly("id", "id_2", "id_3");
        assertThat(r).containsExactly(Map.of("original", "id", "name", "id_2"), Map.of("original", "id", "name", "id_3"));

        r.clear();
        // "id_2" sonuçta zaten var → ikinci "ID" ona değil "ID_3"e gider; satır haritası büyük/küçük harf duyarsız
        assertThat(SqlResultColumns.uniqueLabels(new String[]{"id", "ID", "id_2"}, r)).containsExactly("id", "ID_3", "id_2");
        assertThat(r).containsExactly(Map.of("original", "ID", "name", "ID_3"));
    }

    @Test
    @DisplayName("Sınırlı: çok sayıda aynı ad da tekil adlarla biter")
    void manyDuplicates() {
        String[] raw = new String[500];
        java.util.Arrays.fill(raw, "x");
        List<Map<String, String>> r = new ArrayList<>();
        String[] out = SqlResultColumns.uniqueLabels(raw, r);
        assertThat(java.util.Set.of(out)).hasSize(500);
        assertThat(r).hasSize(499);
    }
}
