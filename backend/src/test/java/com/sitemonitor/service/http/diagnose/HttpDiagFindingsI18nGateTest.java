package com.sitemonitor.service.http.diagnose;

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
 * KAPI (2026-10-02): HTTP uçtan uca tanılamasının {@link HttpDiagFindings#CODES} kataloğundaki HER kodun arayüzde TR + EN
 * metni olmalı — {@code httpdx.finding.<KOD>.title} ve {@code httpdx.finding.<KOD>.body} ({@code frontend/src/i18n/tr.js}
 * ve {@code en.js}). Arayüz kodu anahtara DİNAMİK çevirir ({@code t('httpdx.finding.' + code + '.title', params)}); bu
 * yüzden frontend'in used-keys kapısı onları göremez — eksik anahtar ekranda ham {@code httpdx.finding.X.title} olarak
 * görünürdü. {@code AlertNoiseSuggestionI18nGateTest} deseni: her sözlük KENDİ dosyasında, tanım satırından itibaren.
 */
class HttpDiagFindingsI18nGateTest {

    private static final Path I18N_DIR = Path.of("../frontend/src/i18n");

    private static String dictionary(String file, String marker) throws IOException {
        String src = Files.readString(I18N_DIR.resolve(file), StandardCharsets.UTF_8);
        int start = src.indexOf(marker);
        assertThat(start).as(file + " içinde '" + marker + "' sözlük başlangıcı").isGreaterThanOrEqualTo(0);
        assertThat(src.indexOf(marker, start + 1)).as(file + " tek sözlük içermeli").isEqualTo(-1);
        return src.substring(start);
    }

    @Test
    @DisplayName("katalog sözleşmedeki 22 kodu taşır, tekrar yok")
    void catalogMatchesContract() {
        assertThat(HttpDiagFindings.CODES).hasSize(22).doesNotHaveDuplicates()
                .contains("OK", "RESPONSE_TIMEOUT", "PATH_DIFFERS", "BOTH_PATHS_FAIL", "CLIENT_MISMATCH", "SSRF_BLOCKED");
    }

    @Test
    @DisplayName("SOZLESME: her bulgu kodunun TR ve EN title/body anahtarı var")
    void everyFindingCode_hasTrAndEnKeys() throws IOException {
        String tr = dictionary("tr.js", "export const TR = {");
        String en = dictionary("en.js", "export const EN = {");
        List<String> missing = new ArrayList<>();
        for (String code : HttpDiagFindings.CODES) {
            for (String suffix : List.of(".title", ".body")) {
                String key = "'httpdx.finding." + code + suffix + "'";
                if (!tr.contains(key)) missing.add("TR " + key);
                if (!en.contains(key)) missing.add("EN " + key);
            }
        }
        assertThat(missing).as("eksik i18n anahtarları").isEmpty();
    }
}
