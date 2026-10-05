package com.sitemonitor.service.diagnose;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-10-05): ping / port / DNS uçtan uca tanılamasının her bulgu kodu ve her adım anahtarı arayüzde TR + EN metne
 * sahip olmalı — arayüz kodları anahtara DİNAMİK çevirir ({@code ndx.finding.<KOD>.title|body}, {@code ndx.step.<anahtar>}),
 * bu yüzden frontend'in used-keys kapısı onları göremez (eksik anahtar ekranda ham anahtar olarak görünürdü).
 * {@code HttpDiagFindingsI18nGateTest} / {@code KeywordDiagFindingsI18nGateTest} deseni.
 */
class NetDiagFindingsI18nGateTest {

    private static final Path I18N_DIR = Path.of("../frontend/src/i18n");

    private static String dictionary(String file, String marker) throws IOException {
        String src = Files.readString(I18N_DIR.resolve(file), StandardCharsets.UTF_8);
        int start = src.indexOf(marker);
        assertThat(start).as(file + " içinde '" + marker + "' sözlük başlangıcı").isGreaterThanOrEqualTo(0);
        assertThat(src.indexOf(marker, start + 1)).as(file + " tek sözlük içermeli").isEqualTo(-1);
        return src.substring(start);
    }

    private static List<String> missing(String prefix, List<String> codes, List<String> suffixes) throws IOException {
        String tr = dictionary("tr.js", "export const TR = {");
        String en = dictionary("en.js", "export const EN = {");
        List<String> out = new ArrayList<>();
        for (String code : codes) {
            for (String suffix : suffixes) {
                String key = "'" + prefix + code + suffix + "'";
                if (!tr.contains(key)) out.add("TR " + key);
                if (!en.contains(key)) out.add("EN " + key);
            }
        }
        return out;
    }

    @Test
    @DisplayName("SOZLESME: her ağ tanılama bulgusunun TR + EN başlık/gövde metni var")
    void findings() throws IOException {
        assertThat(missing("ndx.finding.", NetDiagFindings.CODES, List.of(".title", ".body")))
                .as("eksik i18n anahtarları").isEmpty();
    }

    @Test
    @DisplayName("SOZLESME: her adım anahtarının TR + EN adı var")
    void steps() throws IOException {
        assertThat(missing("ndx.step.", NetDiagFindings.STEP_KEYS, List.of("")))
                .as("eksik i18n anahtarları").isEmpty();
    }
}
