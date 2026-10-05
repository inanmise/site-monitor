package com.sitemonitor.service.page.diagnose;

import com.sitemonitor.service.http.diagnose.HttpDiagFindings;
import com.sitemonitor.service.keyword.KeywordBodyAnalyzer;
import com.sitemonitor.service.keyword.diagnose.KeywordDiagFindings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-10-05): Sayfa Bütünlüğü ve Sayfa Hızı uçtan uca tanılamasının bulgu kodlarının her biri arayüzde TR + EN metne
 * sahip olmalı — arayüz kodları anahtara DİNAMİK çevirir ({@code pgdx.finding.<KOD>.title|body},
 * {@code psdx.finding.<KOD>.title|body}); frontend'in used-keys kapısı onları göremez (eksik anahtar ekranda genel "bilinmeyen
 * bulgu" metnine düşerdi). Varyant metinleri ({@code params.reason}) ve yol farkı varyantı da aranır. Kodlar HTTP / keyword
 * kataloglarıyla ÇAKIŞMAZ: arayüz ad alanını koddan seçer. {@code KeywordDiagFindingsI18nGateTest} deseni.
 */
class PageDiagFindingsI18nGateTest {

    private static final Path I18N_DIR = Path.of("../frontend/src/i18n");

    private static String dictionary(String file, String marker) throws IOException {
        String src = Files.readString(I18N_DIR.resolve(file), StandardCharsets.UTF_8);
        int start = src.indexOf(marker);
        assertThat(start).as(file + " içinde '" + marker + "' sözlük başlangıcı").isGreaterThanOrEqualTo(0);
        assertThat(src.indexOf(marker, start + 1)).as(file + " tek sözlük içermeli").isEqualTo(-1);
        return src.substring(start);
    }

    private static List<String> missing(List<String> keys) throws IOException {
        String tr = dictionary("tr.js", "export const TR = {");
        String en = dictionary("en.js", "export const EN = {");
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            String key = "'" + k + "'";
            if (!tr.contains(key)) out.add("TR " + key);
            if (!en.contains(key)) out.add("EN " + key);
        }
        return out;
    }

    private static List<String> keys(String prefix, List<String> codes, List<String> suffixes) {
        List<String> out = new ArrayList<>();
        for (String c : codes) for (String s : suffixes) out.add(prefix + c + s);
        return out;
    }

    @Test
    @DisplayName("katalogların kod adları birbirleriyle ve HTTP / keyword kataloglarıyla çakışmaz")
    void namespacesDisjoint() {
        Set<String> other = new HashSet<>(HttpDiagFindings.CODES);
        other.addAll(KeywordDiagFindings.CODES);
        other.addAll(KeywordBodyAnalyzer.HINT_CODES);
        for (String c : PageDiagFindings.CODES) assertThat(other).as("pgdx kodu başka katalogda: " + c).doesNotContain(c);
        for (String c : PageSpeedDiagFindings.CODES) {
            assertThat(other).as("psdx kodu başka katalogda: " + c).doesNotContain(c);
            assertThat(PageDiagFindings.CODES).as("psdx kodu pgdx'te: " + c).doesNotContain(c);
        }
        assertThat(PageDiagFindings.CODES).hasSize(12).doesNotHaveDuplicates();
        assertThat(PageSpeedDiagFindings.CODES).hasSize(17).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("SOZLESME: her Sayfa Bütünlüğü bulgusunun TR + EN başlık/gövde metni var (+ reason varyantları)")
    void pageFindings() throws IOException {
        List<String> k = keys("pgdx.finding.", PageDiagFindings.CODES, List.of(".title", ".body"));
        // params.reason varyantları
        k.add("pgdx.finding.PAGE_DOWN.body.unfinished");
        k.add("pgdx.finding.THIRD_PARTY_ONLY.body.alarm");
        k.add("pgdx.finding.THIRD_PARTY_ONLY.body.quiet");
        for (String r : List.of("resources", "time", "single_page")) {
            k.add("pgdx.finding.CRAWL_LIMIT.title." + r);
            k.add("pgdx.finding.CRAWL_LIMIT.body." + r);
        }
        k.add("httpdx.finding.PATH_DIFFERS.title.page");
        k.add("httpdx.finding.PATH_DIFFERS.body.page");
        assertThat(missing(k)).as("eksik i18n anahtarları").isEmpty();
    }

    @Test
    @DisplayName("SOZLESME: her Sayfa Hızı bulgusunun TR + EN başlık/gövde metni var (+ metrik / sınır varyantları)")
    void pageSpeedFindings() throws IOException {
        List<String> k = keys("psdx.finding.", PageSpeedDiagFindings.CODES, List.of(".title", ".body"));
        for (String metric : PageSpeedDiagFindings.METRICS) {
            k.add("psdx.finding.THRESHOLD_BREACH.title." + metric);
            k.add("psdx.finding.THRESHOLD_BREACH.body." + metric);
        }
        for (String r : List.of("resources", "bytes", "time")) k.add("psdx.finding.MEASUREMENT_PARTIAL.body." + r);
        k.add("psdx.finding.PAGESPEED_DOWN.body.unfinished");
        k.add("psdx.finding.SLOW_SERVER.body.threshold");
        k.add("psdx.finding.SLOW_CONNECT.body.proxy_tunnel");
        k.add("httpdx.finding.PATH_DIFFERS.title.pagespeed");
        k.add("httpdx.finding.PATH_DIFFERS.body.pagespeed");
        assertThat(missing(k)).as("eksik i18n anahtarları").isEmpty();
    }
}
