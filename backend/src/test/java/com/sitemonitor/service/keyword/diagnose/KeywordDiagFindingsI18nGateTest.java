package com.sitemonitor.service.keyword.diagnose;

import com.sitemonitor.service.http.diagnose.HttpDiagFindings;
import com.sitemonitor.service.keyword.KeywordBodyAnalyzer;
import com.sitemonitor.service.keyword.KeywordFailureClassifier;
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
 * KAPI (2026-10-04): keyword hata teşhisinin ÜÇ kod kataloğunun her kodu arayüzde TR + EN metne sahip olmalı —
 * arayüz kodları anahtara DİNAMİK çevirir, bu yüzden frontend'in used-keys kapısı onları göremez (eksik anahtar ekranda
 * ham {@code kwfail.X.why} olarak görünürdü). {@code HttpDiagFindingsI18nGateTest} deseni.
 * <ul>
 *   <li>{@link KeywordFailureClassifier#CODES} → {@code kwfail.<KOD>.short|why|effect|fix} (kontrol geçmişi + kart)</li>
 *   <li>{@link KeywordBodyAnalyzer#HINT_CODES} → {@code kwhint.<KOD>.title|cause|effect|fix} (ipucu kartları + tanılama)</li>
 *   <li>{@link KeywordDiagFindings#CODES} → {@code kwdx.finding.<KOD>.title|body} (uçtan uca tanılama)</li>
 * </ul>
 */
class KeywordDiagFindingsI18nGateTest {

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
    @DisplayName("katalogların kod adları çakışmaz (arayüz ad alanını koddan seçer)")
    void namespacesDisjoint() {
        for (String c : KeywordDiagFindings.CODES) {
            assertThat(HttpDiagFindings.CODES).as("kwdx kodu httpdx'te olmamalı: " + c).doesNotContain(c);
            assertThat(KeywordBodyAnalyzer.HINT_CODES).doesNotContain(c);
        }
        for (String h : KeywordBodyAnalyzer.HINT_CODES) assertThat(HttpDiagFindings.CODES).doesNotContain(h);
        assertThat(KeywordDiagFindings.CODES).hasSize(10).doesNotHaveDuplicates();
        assertThat(KeywordBodyAnalyzer.HINT_CODES).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("SOZLESME: her başarısızlık nedeninin TR + EN kısa/neden/etki/çözüm metni var")
    void failureReasons() throws IOException {
        assertThat(missing("kwfail.", KeywordFailureClassifier.CODES, List.of(".short", ".why", ".effect", ".fix")))
                .as("eksik i18n anahtarları").isEmpty();
    }

    @Test
    @DisplayName("SOZLESME: her ipucunun TR + EN başlık/neden/etki/çözüm metni var")
    void hints() throws IOException {
        assertThat(missing("kwhint.", KeywordBodyAnalyzer.HINT_CODES, List.of(".title", ".cause", ".effect", ".fix")))
                .as("eksik i18n anahtarları").isEmpty();
    }

    @Test
    @DisplayName("SOZLESME: her keyword tanılama bulgusunun TR + EN başlık/gövde metni var")
    void diagFindings() throws IOException {
        assertThat(missing("kwdx.finding.", KeywordDiagFindings.CODES, List.of(".title", ".body")))
                .as("eksik i18n anahtarları").isEmpty();
    }
}
