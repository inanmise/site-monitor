package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Tema kataloğu (2026-10-05): liste, şemalar, doğrulama kuralları ve hoşgörülü etkin politika. */
class ThemeCatalogTest {

    @Test
    @DisplayName("sekiz tema, katalog sırasıyla; Açık/Koyu temel, altı ek tema doğru şemada")
    void catalog_idsAndSchemes() {
        assertThat(ThemeCatalog.IDS).containsExactly(
                "light", "dark", "blueprint", "parchment", "alloy", "obsidian", "slag", "crucible");
        assertThat(ThemeCatalog.schemeOf("light")).isEqualTo("light");
        assertThat(ThemeCatalog.schemeOf("parchment")).isEqualTo("light");
        assertThat(ThemeCatalog.schemeOf("alloy")).isEqualTo("light");
        for (String id : List.of("dark", "blueprint", "obsidian", "slag", "crucible")) {
            assertThat(ThemeCatalog.schemeOf(id)).as(id).isEqualTo("dark");
        }
        assertThat(ThemeCatalog.schemeOf("sepia")).isNull();
        assertThat(ThemeCatalog.DEFAULT_OPTIONS).first().isEqualTo("system");
        assertThat(ThemeCatalog.DEFAULT_OPTIONS).hasSize(9);
        assertThat(ThemeCatalog.DEFAULT_ENABLED_CSV).isEqualTo(String.join(",", ThemeCatalog.IDS));
    }

    @Test
    @DisplayName("geçerli politikalar: sekizi + system; yalnız ek temalar + açık varsayılan; Açık+Koyu + system")
    void validate_ok() {
        assertThat(ThemeCatalog.validate(ThemeCatalog.IDS, "system")).isNull();
        assertThat(ThemeCatalog.validate(List.of("crucible", "parchment"), "parchment")).isNull();
        assertThat(ThemeCatalog.validate(List.of("light", "dark"), "system")).isNull();
        assertThat(ThemeCatalog.validate(List.of("light", "dark"), "")).as("boş varsayılan = system").isNull();
        assertThat(ThemeCatalog.validate(List.of("slag"), "slag")).isNull();
    }

    @Test
    @DisplayName("bilinmeyen kimlik → enabled alanı")
    void validate_unknownId() {
        ThemeCatalog.Problem p = ThemeCatalog.validate(List.of("light", "sepia"), "light");
        assertThat(p).isNotNull();
        assertThat(p.field()).isEqualTo("enabled");
        assertThat(p.en()).contains("sepia");
    }

    @Test
    @DisplayName("boş liste → enabled alanı (en az bir tema)")
    void validate_emptyList() {
        assertThat(ThemeCatalog.validate(List.of(), "system").field()).isEqualTo("enabled");
        assertThat(ThemeCatalog.validate(null, "light").field()).isEqualTo("enabled");
    }

    @Test
    @DisplayName("varsayılan listede kapalı → default alanı; bilinmeyen varsayılan → default alanı")
    void validate_defaultNotEnabled() {
        ThemeCatalog.Problem p = ThemeCatalog.validate(List.of("light", "dark"), "crucible");
        assertThat(p.field()).isEqualTo("default");
        assertThat(ThemeCatalog.validate(List.of("light", "dark"), "sepia").field()).isEqualTo("default");
    }

    @Test
    @DisplayName("system varsayılanı Açık ve Koyu'nun ikisini de ister → default alanı")
    void validate_systemNeedsLightAndDark() {
        assertThat(ThemeCatalog.validate(List.of("light", "blueprint"), "system").field()).isEqualTo("default");
        assertThat(ThemeCatalog.validate(List.of("dark", "parchment"), "system").field()).isEqualTo("default");
    }

    @Test
    @DisplayName("etkin politika hoşgörülü: bilinmeyen atılır, sıra katalog sırası, geçersiz varsayılan güvenli değere düşer")
    void effective_isTolerant() {
        Map<String, Object> m = ThemeCatalog.effective("crucible, sepia ,light,dark", "system");
        assertThat(m.get("enabled")).isEqualTo(List.of("light", "dark", "crucible"));
        assertThat(m.get("default")).isEqualTo("system");

        // boş / tamamen geçersiz liste → sekizi de açık
        assertThat(ThemeCatalog.effective("", "system").get("enabled")).isEqualTo(ThemeCatalog.IDS);
        assertThat(ThemeCatalog.effective("sepia", "system").get("enabled")).isEqualTo(ThemeCatalog.IDS);

        // kapalı varsayılan → system (Açık+Koyu açık); Açık+Koyu yoksa listedeki ilk tema
        assertThat(ThemeCatalog.effective("light,dark", "crucible").get("default")).isEqualTo("system");
        assertThat(ThemeCatalog.effective("slag,parchment", "system").get("default")).isEqualTo("parchment");
        assertThat(ThemeCatalog.effective("slag,parchment", "slag").get("default")).isEqualTo("slag");
        assertThat(ThemeCatalog.effective(null, null).get("default")).isEqualTo("system");
    }

    @Test
    @DisplayName("parseCsv: kırpar, boşları ve tekrarları atar, sırayı korur")
    void parseCsv() {
        assertThat(ThemeCatalog.parseCsv(" dark, ,light,dark ")).containsExactly("dark", "light");
        assertThat(ThemeCatalog.parseCsv(null)).isEmpty();
        assertThat(ThemeCatalog.canonical(List.of("crucible", "light"))).containsExactly("light", "crucible");
    }
}
