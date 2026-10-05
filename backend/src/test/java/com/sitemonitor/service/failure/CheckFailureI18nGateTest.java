package com.sitemonitor.service.failure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-10-05): kontrol hata teşhisi kataloğunun ({@link CheckFailureReason}) her kodu arayüzde TR + EN metne sahip
 * olmalı — arayüz kodları anahtara DİNAMİK çevirir ({@code chkfail.<KOD>.short|why|effect|fix}), bu yüzden frontend'in
 * used-keys kapısı onları göremez (eksik anahtar ekranda ham {@code chkfail.X.why} olarak görünürdü). Evre etiketleri
 * ({@code chkfail.phase.<EVRE>}) de aynı kuraldadır. Arayüz kopyası ({@code components/checks/checkFailureCodes.js})
 * backend listesiyle birebir aynı olmalı. {@code KeywordDiagFindingsI18nGateTest} deseni.
 */
class CheckFailureI18nGateTest {

    private static final Path I18N_DIR = Path.of("../frontend/src/i18n");
    private static final Path CODES_JS = Path.of("../frontend/src/components/checks/checkFailureCodes.js");

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
    @DisplayName("SOZLESME: her neden kodunun TR + EN kısa / neden / etki / çözüm metni var")
    void everyReasonHasTexts() throws IOException {
        assertThat(missing("chkfail.", CheckFailureReason.CODES, List.of(".short", ".why", ".effect", ".fix")))
                .as("eksik i18n anahtarları").isEmpty();
    }

    @Test
    @DisplayName("SOZLESME: her evrenin TR + EN etiketi var")
    void everyPhaseHasLabel() throws IOException {
        List<String> phases = Arrays.stream(CheckFailureReason.Phase.values()).map(p -> p.name()).toList();
        assertThat(missing("chkfail.phase.", phases, List.of(""))).as("eksik evre etiketleri").isEmpty();
    }

    @Test
    @DisplayName("SOZLESME: arayüz kod listesi (checkFailureCodes.js) backend kataloğuyla birebir aynı sırada")
    void frontendCodeListMatches() throws IOException {
        String js = Files.readString(CODES_JS, StandardCharsets.UTF_8);
        int start = js.indexOf("export const CHECK_FAILURE_CODES");
        assertThat(start).as("CHECK_FAILURE_CODES dizisi").isGreaterThanOrEqualTo(0);
        String body = js.substring(js.indexOf('[', start), js.indexOf(']', start) + 1);
        Matcher m = Pattern.compile("'([A-Z0-9_]+)'").matcher(body);
        List<String> front = new ArrayList<>();
        while (m.find()) front.add(m.group(1));
        assertThat(front).as("arayüz kod listesi").containsExactlyElementsOf(CheckFailureReason.CODES);
    }
}
