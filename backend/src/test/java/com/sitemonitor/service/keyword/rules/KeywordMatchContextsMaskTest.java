package com.sitemonitor.service.keyword.rules;

import com.sitemonitor.service.keyword.KeywordSecretWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * kw son inceleme D-9 (2026-11-05): eşleşme bağlamında maske önce / eşleşme / sonra parçalarının BİRLEŞİMİNE uygulanır, sonra
 * bölünür — parça sınırında bölünen sır ya da {@code "password":"…"} kalıbı açık kalmaz; pencere kenarındaki sır tam kapsanır.
 */
class KeywordMatchContextsMaskTest {

    private static String all(Map<String, Object> c) {
        return c.get("before") + "|" + c.get("match") + "|" + c.get("after");
    }

    @Test
    @DisplayName("Sır eşleşmenin içinde başlayıp sonrasına taşıyor → parça parça maske kaçırırdı; birleşik maske yakalar")
    void secretAcrossMatchBoundary() {
        String secret = "abcSECRETVALUE77";
        String text = "önce metin " + secret + " sonra metin";
        int s = text.indexOf("abc");
        Map<String, Object> c = KeywordMatchContexts.context(text, s, s + 3, "RAW", List.of(secret));
        assertThat(all(c)).doesNotContain("SECRETVALUE77").doesNotContain(secret);
        assertThat((String) c.get("before")).contains("önce metin").contains("sonra metin");
        assertThat(c.get("match")).isEqualTo("");
    }

    @Test
    @DisplayName("\"password\" anahtarı öncede, değeri eşleşmede → eşleşme tek başına maskelenmezdi; birleşik maske yakalar")
    void sensitiveFieldAcrossBoundary() {
        String text = "{\"user\":\"ali\",\"password\":\"hunter22xyz\",\"x\":1}";
        int s = text.indexOf("hunter22xyz");
        Map<String, Object> c = KeywordMatchContexts.context(text, s, s + "hunter22xyz".length(), "RAW", List.of());
        assertThat(all(c)).doesNotContain("hunter22xyz");
    }

    @Test
    @DisplayName("Sınırı aşmayan sır: parçalar korunur (eşleşme vurgusu kaybolmaz), sır yine maskeli")
    void piecesKeptWhenNoCrossing() {
        String secret = "tok3nVALUE999";
        String text = "Fiyat 1250 TL — anahtar " + secret + " bitti";
        int s = text.indexOf("Fiyat");
        Map<String, Object> c = KeywordMatchContexts.context(text, s, s + 5, "RAW", List.of(secret));
        assertThat(c.get("match")).isEqualTo("Fiyat");
        assertThat((String) c.get("after")).doesNotContain(secret).contains("anahtar");
    }

    @Test
    @DisplayName("Pencere kenarındaki sır tam kapsanır (expand) — yarım sır açık kalmaz")
    void windowExpandsToWholeSecret() {
        String secret = "ZQ7xLONGsecretVALUE";
        String text = "Fiyat" + "b".repeat(57) + secret + " son";   // ±60 penceresi sırrın ilk 3 karakterini keser
        Map<String, Object> c = KeywordMatchContexts.context(text, 0, 5, "RAW", List.of(secret));
        assertThat(all(c)).doesNotContain("ZQ7").doesNotContain(secret);
        int[] w = KeywordSecretWindow.expand(text, 0, 65, List.of(secret));
        assertThat(w[1]).isEqualTo(text.indexOf(secret) + secret.length());
        assertThat(KeywordSecretWindow.expand(text, 0, 65, List.of())).containsExactly(0, 65);
    }
}
