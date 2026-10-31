package com.sitemonitor.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@link ScriptedCheckerService#stripComments} doğrusal tarayıcıya taşındı (2026-10-31, k6 inceleme A3): eski
 * {@code /\*[\s\S]*?\*\/} düzenli ifadesi kapanmamış bir blok yorumda betik boyunun KARESİ kadar adım atıyordu ve Java
 * düzenli ifadeleri iptal edilemez. Eski ifade burada ÖRNEK (oracle) olarak durur: dizge içinde yorum işareti olmayan
 * girdilerde iki çıktı boşluklar dışında aynıdır.
 */
class ScriptedStripCommentsTest {

    /** 20.177.0'daki uygulama — yalnız karşılaştırma için. */
    private static String oldStrip(String script) {
        return script
                .replaceAll("/\\*[\\s\\S]*?\\*/", " ")
                .replaceAll("(?m)(?<!:)//[^\n]*", " ");
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "import http from 'k6/http';\n// yorum\nexport default () => http.get('https://x.example.com/a');",
            "/* blok\n yorum */ const a = 1; /* ikinci */ const b = 2;",
            "const u = base + '://' + host; // açıklama",
            "http.get(`${base}/x`, { timeout: '10s' }); /** belge */",
            "const x = 1;",
            "",
            "a(); //\nb(); /**/ c();",
    })
    void sameAsOldRegexWithoutMarkersInStrings(String script) {
        assertThat(squash(ScriptedCheckerService.stripComments(script))).isEqualTo(squash(oldStrip(script)));
    }

    @Test
    void stringsKeepTheirSlashes_unlikeOldRegex() {
        String s = "const p = 'a//b'; const q = \"/*x*/\"; // son";
        String out = ScriptedCheckerService.stripComments(s);
        assertThat(out).contains("'a//b'").contains("\"/*x*/\"").doesNotContain("son");
    }

    @Test
    void escapedQuotesDoNotEndString() {
        String s = "const t = 'it\\'s // not a comment'; // gerçek yorum";
        String out = ScriptedCheckerService.stripComments(s);
        assertThat(out).contains("not a comment").doesNotContain("gerçek");
    }

    @Test
    void unclosedBlockComment_isLinear() {
        String s = "const a = 1; /*" + "x".repeat(512 * 1024);
        String out = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> ScriptedCheckerService.stripComments(s));
        assertThat(out.trim()).isEqualTo("const a = 1;");
        // çok sayıda açılış da doğrusal
        String many = "/*".repeat(200_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> ScriptedCheckerService.stripComments(many));
    }

    @Test
    void manyLineCommentsAndUnclosedString_areLinear() {
        String s = "//\n".repeat(200_000) + "'" + "a".repeat(300_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> ScriptedCheckerService.stripComments(s));
        assertThat(ScriptedCheckerService.stripComments(null)).isNull();
    }
}
