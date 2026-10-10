package com.sitemonitor.service.quality;

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
 * KAPI (2026-10-10): veri kalitesi kataloğunun ({@link DataQualityRule}) her kodu arayüzde TR + EN metne sahip olmalı —
 * arayüz kodları anahtara DİNAMİK çevirir ({@code dq.rule.<KOD>.title|why|fix}), bu yüzden frontend'in used-keys kapısı
 * onları göremez (eksik anahtar ekranda ham {@code dq.rule.X.why} olarak görünürdü). Bant, katman nedeni, sahipsizlik
 * nedeni, kurum notu ve 7/24 nedeni etiketleri de aynı kuraldadır. Arayüz kopyası ({@code dataQualityCodes.js}) backend
 * listesiyle SIRA DAHİL birebir aynı olmalı (düzeltme listesi bu sırayla gruplanır). {@code CheckFailureI18nGateTest}
 * deseni.
 */
class DataQualityRuleI18nGateTest {

    private static final Path I18N_DIR = Path.of("../frontend/src/i18n");
    private static final Path CODES_JS = Path.of("../frontend/src/components/dataquality/dataQualityCodes.js");

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
    @DisplayName("SOZLESME: her kuralın TR + EN başlık / neden önemli / nasıl düzeltilir metni var")
    void everyRuleHasTexts() throws IOException {
        assertThat(missing("dq.rule.", DataQualityRule.CODES, List.of(".title", ".why", ".fix")))
                .as("eksik i18n anahtarları").isEmpty();
    }

    @Test
    @DisplayName("SOZLESME: bant, önem, kapsam, katman nedeni, sahipsizlik nedeni, kurum notu ve 7/24 nedeni etiketleri")
    void labels() throws IOException {
        List<String> bands = Arrays.stream(DataQualityScore.Band.values()).map(Enum::name).toList();
        List<String> severities = Arrays.stream(DataQualityRule.Severity.values()).map(Enum::name).toList();
        List<String> scopes = Arrays.stream(DataQualityRule.Scope.values()).map(Enum::name).toList();
        List<String> out = new ArrayList<>();
        out.addAll(missing("dq.band.", bands, List.of("")));
        out.addAll(missing("dq.severity.", severities, List.of("")));
        out.addAll(missing("dq.scope.", scopes, List.of("")));
        out.addAll(missing("dq.tier.", TierHeuristics.REASONS, List.of("")));
        out.addAll(missing("dq.owner.", DataQualityEvaluator.OWNER_REASONS, List.of("")));
        out.addAll(missing("dq.note.", DataQualityEvaluator.NOTE_CODES, List.of("")));
        out.addAll(missing("dq.nocReason.", List.of("NO_ACTIVE_GROUP", "TYPE_DISABLED", "UNKNOWN"), List.of("")));
        assertThat(out).as("eksik etiketler").isEmpty();
    }

    @Test
    @DisplayName("SOZLESME: arayüz kod listesi (dataQualityCodes.js) backend kataloğuyla birebir aynı sırada")
    void frontendCodeListMatches() throws IOException {
        String js = Files.readString(CODES_JS, StandardCharsets.UTF_8);
        int start = js.indexOf("export const DATA_QUALITY_RULES");
        assertThat(start).as("DATA_QUALITY_RULES dizisi").isGreaterThanOrEqualTo(0);
        String body = js.substring(js.indexOf('[', start), js.indexOf(']', start) + 1);
        Matcher m = Pattern.compile("'([A-Z0-9_]+)'").matcher(body);
        List<String> front = new ArrayList<>();
        while (m.find()) front.add(m.group(1));
        assertThat(front).as("arayüz kod listesi").containsExactlyElementsOf(DataQualityRule.CODES);
    }
}
