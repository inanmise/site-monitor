package com.sitemonitor.service.tlsgrade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-10-10): TLS notu neden kataloğunun ({@link TlsGradeRules.Reason}) her kodu arayüzde TR + EN metne sahip
 * olmalı — arayüz kodları anahtara DİNAMİK çevirir ({@code tlsg.reason.<KOD>.title|why|fix}), frontend'in used-keys
 * kapısı onları göremez. Durum gerekçeleri ({@code tlsg.state.<KOD>}) ve notlar ({@code tlsg.grade.<NOT>}) de aynı
 * kuralda. Arayüz kopyası ({@code components/tlsgrade/tlsGradeCodes.js}: kod sırası + tavanlar) backend kataloğuyla birebir
 * aynı olmalı — rozet "hangi neden notu belirledi" sorusunu bu tavanlarla cevaplar. {@code CheckFailureI18nGateTest} deseni.
 */
class TlsGradeI18nGateTest {

    private static final Path I18N_DIR = Path.of("../frontend/src/i18n");
    private static final Path CODES_JS = Path.of("../frontend/src/components/tlsgrade/tlsGradeCodes.js");

    private static String dictionary(String file, String marker) throws IOException {
        String src = Files.readString(I18N_DIR.resolve(file), StandardCharsets.UTF_8);
        int start = src.indexOf(marker);
        assertThat(start).as(file + " içinde '" + marker + "' sözlük başlangıcı").isGreaterThanOrEqualTo(0);
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
    @DisplayName("SOZLESME: her neden kodunun TR + EN başlık / neden / çözüm metni var")
    void everyReasonHasTexts() throws IOException {
        assertThat(missing("tlsg.reason.", TlsGradeRules.REASON_CODES, List.of(".title", ".why", ".fix")))
                .as("eksik i18n anahtarları").isEmpty();
    }

    @Test
    @DisplayName("SOZLESME: her durum gerekçesinin ve her notun TR + EN etiketi var")
    void everyStateAndGradeHasLabel() throws IOException {
        assertThat(missing("tlsg.state.", TlsGradeRules.STATE_CODES, List.of(""))).isEmpty();
        List<String> gradeKeys = TlsGradeRules.GRADES.stream().map(g -> g.replace("+", "plus")).toList();
        assertThat(missing("tlsg.grade.", gradeKeys, List.of(""))).isEmpty();
    }

    @Test
    @DisplayName("SOZLESME: arayüz kopyası (tlsGradeCodes.js) kod sırası ve tavanlarıyla backend kataloğunun aynısı")
    void frontendCatalogMatches() throws IOException {
        String js = Files.readString(CODES_JS, StandardCharsets.UTF_8);
        int start = js.indexOf("export const TLS_GRADE_REASONS");
        assertThat(start).as("TLS_GRADE_REASONS dizisi").isGreaterThanOrEqualTo(0);
        int end = js.indexOf("\n]", start);
        assertThat(end).as("TLS_GRADE_REASONS dizisi satır başında ']' ile kapanmalı").isGreaterThan(start);
        String body = js.substring(start, end);
        Matcher m = Pattern.compile("\\[\\s*'([A-Z0-9_]+)'\\s*,\\s*(null|'([A-F+]{1,2})')\\s*\\]").matcher(body);
        Map<String, String> front = new LinkedHashMap<>();
        while (m.find()) front.put(m.group(1), m.group(3));
        Map<String, String> back = new LinkedHashMap<>();
        for (TlsGradeRules.Reason r : TlsGradeRules.Reason.values()) back.put(r.name(), r.cap());
        assertThat(new ArrayList<>(front.keySet())).as("kod sırası").containsExactlyElementsOf(back.keySet());
        assertThat(front).as("tavanlar").isEqualTo(back);
    }
}
