package com.sitemonitor.service.keyword;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anahtar kelime eşleşmesinin TEK harf katlama kuralı (kw sözleşmesi §4.1, B3) — doğruluk tablosu. Ön yüz eşi
 * {@code frontend/src/utils/keywordFold.js} bu tabloyu okur ({@code javaStringTable(src, 'TABLE')}): satır biçimi
 * {@code { metin, iğne, harfDuyarlı, adet, ilkKonum }} — beş dizge hücre. Türkçe harfler doğrudan yazılır.
 */
class KeywordFoldTest {

    static final String[][] TABLE = {
            { "IŞIK", "ışık", "false", "1", "0" },
            { "iletişim", "İletişim", "false", "1", "0" },
            { "İstanbul", "ISTANBUL", "false", "1", "0" },
            { "ılık ılık", "ILIK", "false", "2", "0" },
            { "Hoş geldiniz", "HOŞ GELDİNİZ", "false", "1", "0" },
            { "Hoş geldiniz", "HOŞ GELDİNİZ", "true", "0", "-1" },
            { "aaaa", "aa", "false", "2", "0" },
            { "x İ y", "i", "false", "1", "2" },
            { "şeker", "seker", "false", "0", "-1" },
            { "ÇAĞRI çağrı", "çağrı", "false", "2", "0" },
            { "", "a", "false", "0", "-1" },
            { "abc", "", "false", "0", "-1" },
            { "Giriş Yap", "GİRİŞ", "false", "1", "0" },
            { "TIKLAYIN", "tıklayın", "false", "1", "0" },
            { "TIKLAYIN", "tiklayin", "false", "1", "0" },
            { "Sepete Ekle", "sepete ekle", "true", "0", "-1" },
            { "a İletişim b İLETİŞİM", "iletişim", "false", "2", "2" },
    };

    @Test
    @DisplayName("doğruluk tablosu: adet + ilk konum (Türkçe İ/I/ı/i katlaması, aksan katlaması YOK, örtüşmesiz)")
    void table() {
        for (String[] row : TABLE) {
            KeywordFold.Count c = KeywordFold.count(row[0], row[1], Boolean.parseBoolean(row[2]), KeywordFold.MAX_COUNT);
            assertThat(c.count()).as("adet: %s / %s / cs=%s", row[0], row[1], row[2]).isEqualTo(Integer.parseInt(row[3]));
            assertThat(c.firstIndex()).as("ilk konum: %s / %s / cs=%s", row[0], row[1], row[2]).isEqualTo(Integer.parseInt(row[4]));
        }
    }

    @Test
    @DisplayName("fold: İ/ı/I/i → i, diğer harfler basit küçültme; uzunluk HER ZAMAN korunur; null → null")
    void foldKeepsLength() {
        assertThat(KeywordFold.fold("İıIi")).isEqualTo("iiii");
        assertThat(KeywordFold.fold("ŞĞÜÖÇ")).isEqualTo("şğüöç");
        assertThat(KeywordFold.fold((String) null)).isNull();
        for (String s : new String[] { "İSTANBUL İLETİŞİM", "ıııİİİ", "a😀b", "Straße", "ǅ" }) {
            assertThat(KeywordFold.fold(s)).as(s).hasSize(s.length());
        }
    }

    @Test
    @DisplayName("sayım üst sınırı: max'tan fazla eşleşme sayılmaz; max < 1 → (0, -1)")
    void countCap() {
        String hay = "a".repeat(100);
        assertThat(KeywordFold.count(hay, "a", false, 10).count()).isEqualTo(10);
        assertThat(KeywordFold.count(hay, "a", false, 0)).isEqualTo(new KeywordFold.Count(0, -1));
        assertThat(KeywordFold.count(null, "a", false, 5)).isEqualTo(new KeywordFold.Count(0, -1));
        assertThat(KeywordFold.count("a", null, false, 5)).isEqualTo(new KeywordFold.Count(0, -1));
    }
}
