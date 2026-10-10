package com.sitemonitor.service.report.executive;

import com.sitemonitor.service.crypto.CryptoClassifier;
import com.sitemonitor.service.quality.DataQualityRule;
import com.sitemonitor.service.quality.DataQualityScore;
import com.sitemonitor.service.tlsgrade.TlsGradeRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-10-10): yönetici özetinin TLS notu, kripto hazırlığı ve veri kalitesi bölümleri o özelliklerin KENDİ kodlarını
 * yollar (neden, kategori, bant, kural). Posta ve PDF Türkçeyi {@link ExecFormat#CODE_LABELS_TR}'den, arayüz o özelliğin
 * i18n anahtarından ({@link ExecFormat#CODE_LABEL_I18N}) yazar. Bu test iki yönü bağlar: (1) her kod için Türkçe metin var
 * ve {@code tr.js}'tekiyle BİREBİR aynı (ekran ile PDF aynı sözcüğü kullanır), EN anahtarı da var; (2) haritada katalogda
 * olmayan (artık silinmiş) kod kalmaz. Yeni bir TLS neden kodu / veri kalitesi kuralı eklenince kırmızı olur.
 */
class ExecutiveSummaryCodeLabelsGateTest {

    private static final Path I18N_DIR = Path.of("../frontend/src/i18n");

    /** Biçim → katalogdaki kodlar (tek kaynaklardan). */
    static Map<String, List<String>> catalog() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("tls_reason", TlsGradeRules.REASON_CODES);
        m.put("crypto_category", Arrays.stream(CryptoClassifier.Category.values()).map(Enum::name).toList());
        m.put("pqc_band", CryptoReadinessSection.BANDS);
        m.put("dq_rule", DataQualityRule.CODES);
        m.put("dq_band", Arrays.stream(DataQualityScore.Band.values()).map(Enum::name).toList());
        return m;
    }

    /** Sözlükte anahtarın tek tırnaklı değeri (yoksa null). */
    static String value(String dict, String key) {
        Matcher m = Pattern.compile("'" + Pattern.quote(key) + "'\\s*:\\s*'((?:[^'\\\\]|\\\\.)*)'").matcher(dict);
        return m.find() ? m.group(1).replace("\\'", "'") : null;
    }

    @Test
    @DisplayName("her kodun Türkçesi tr.js ile birebir, EN anahtarı var; haritada fazladan kod yok")
    void labelsMatchDictionaries() throws IOException {
        String tr = Files.readString(I18N_DIR.resolve("tr.js"), StandardCharsets.UTF_8);
        String en = Files.readString(I18N_DIR.resolve("en.js"), StandardCharsets.UTF_8);
        assertThat(ExecFormat.CODE_LABEL_I18N.keySet()).containsExactlyInAnyOrderElementsOf(catalog().keySet());
        List<String> problems = new ArrayList<>();
        int expected = 0;
        for (Map.Entry<String, List<String>> e : catalog().entrySet()) {
            String template = ExecFormat.CODE_LABEL_I18N.get(e.getKey());
            for (String code : e.getValue()) {
                expected++;
                String key = template.replace("{0}", code);
                String javaTr = ExecFormat.CODE_LABELS_TR.get(e.getKey() + "." + code);
                String dictTr = value(tr, key);
                if (javaTr == null) problems.add("Java TR yok: " + e.getKey() + "." + code);
                if (dictTr == null) problems.add("tr.js yok: " + key);
                if (javaTr != null && dictTr != null && !javaTr.equals(dictTr)) {
                    problems.add("sapma " + key + ": Java '" + javaTr + "' ≠ tr.js '" + dictTr + "'");
                }
                if (value(en, key) == null) problems.add("en.js yok: " + key);
                if (dictTr != null && dictTr.matches(".*\\{\\d+}.*")) problems.add("yer tutucu (parametresiz çağrılır): " + key);
            }
        }
        assertThat(problems).isEmpty();
        assertThat(ExecFormat.CODE_LABELS_TR).as("haritada katalog dışı kod").hasSize(expected);
    }

    @Test
    @DisplayName("ExecFormat kod sütunlarını Türkçe yazar; bilinmeyen kod olduğu gibi, num ondalık virgülle")
    void formatsCodes() {
        assertThat(ExecFormat.value("TLS10_ENABLED", "tls_reason")).isEqualTo("TLS 1.0 açık");
        assertThat(ExecFormat.value("LEGACY", "crypto_category")).isEqualTo("2030 altı");
        assertThat(ExecFormat.value("P1", "pqc_band")).isEqualTo("P1 · şimdi");
        assertThat(ExecFormat.value("INV_NO_TEAM", "dq_rule")).isEqualTo("Sahipsiz envanter kaydı");
        assertThat(ExecFormat.value("POOR", "dq_band")).isEqualTo("Zayıf");
        assertThat(ExecFormat.value("YENI_KOD", "tls_reason")).isEqualTo("YENI_KOD");
        assertThat(ExecFormat.value(4.25, "num")).isEqualTo("4,25");
        assertThat(ExecFormat.value(-7.0, "pp")).isEqualTo("−7 puan");
    }
}
