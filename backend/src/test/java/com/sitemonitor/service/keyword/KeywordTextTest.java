package com.sitemonitor.service.keyword;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anahtar kelime kontrolünün metin katmanı (kw sözleşmesi §4.2–4.3, B4): karakter kümesi önceliği (BOM &gt; Content-Type &gt;
 * meta &gt; UTF-8), WHATWG etiket eşlemesi, desteklenmeyen küme, eşleşme kapsamı / normalleştirme ve değişim için satırlı
 * görünür metin.
 */
class KeywordTextTest {

    private static final Charset W1254 = Charset.forName("windows-1254");

    @Test
    @DisplayName("windows-1254 sayfa: Content-Type bildirimiyle doğru okunur (eskiden UTF-8 okunup 'Giriş' bulunmuyordu)")
    void headerCharset() {
        byte[] b = "<p>Giriş başarılı — İletişim</p>".getBytes(W1254);
        KeywordText.Decoded d = KeywordText.decode(b, "text/html; charset=windows-1254");
        assertThat(d.text()).contains("Giriş").contains("İletişim");
        assertThat(d.charset()).isEqualTo("windows-1254");
        assertThat(d.source()).isEqualTo(KeywordText.SRC_HEADER);
        assertThat(new String(b, StandardCharsets.UTF_8)).doesNotContain("Giriş");   // eski davranışın kanıtı
    }

    @Test
    @DisplayName("WHATWG eşlemesi: iso-8859-9 / latin5 / l5 / cp1254 → windows-1254; iso-8859-1 / us-ascii → windows-1252")
    void whatwgLabels() {
        for (String label : new String[] { "iso-8859-9", "ISO-8859-9", "latin5", "l5", "iso-ir-148", "csisolatin5", "cp1254",
                "x-cp1254", "windows-1254" }) {
            assertThat(KeywordText.resolve(label, false).name()).as(label).isEqualTo("windows-1254");
        }
        for (String label : new String[] { "iso-8859-1", "latin1", "l1", "us-ascii", "ascii", "cp1252", "windows-1252" }) {
            assertThat(KeywordText.resolve(label, false).name()).as(label).isEqualTo("windows-1252");
        }
        assertThat(KeywordText.resolve("utf8", false)).isEqualTo(StandardCharsets.UTF_8);
        assertThat(KeywordText.resolve("bozuk:küme", false)).isNull();
        assertThat(KeywordText.resolve("x".repeat(60), false)).isNull();
        // meta'da UTF-16 ilanı UTF-8 sayılır (WHATWG ön taraması); başlıkta değil
        assertThat(KeywordText.resolve("utf-16", true)).isEqualTo(StandardCharsets.UTF_8);
        assertThat(KeywordText.resolve("utf-16le", false)).isEqualTo(StandardCharsets.UTF_16LE);
    }

    @Test
    @DisplayName("<meta charset> ve <meta http-equiv content=...charset=...> ilk 4 KB'ta okunur; başlık meta'yı ezer")
    void metaCharset() {
        byte[] b1 = "<html><head><meta charset=\"iso-8859-9\"></head><body>Şifre sıfırlama</body></html>".getBytes(W1254);
        KeywordText.Decoded d1 = KeywordText.decode(b1, "text/html");
        assertThat(d1.text()).contains("Şifre sıfırlama");
        assertThat(d1.source()).isEqualTo(KeywordText.SRC_META);

        byte[] b2 = ("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1254\"></head>"
                + "<body>Hoş geldiniz</body></html>").getBytes(W1254);
        assertThat(KeywordText.decode(b2, null).text()).contains("Hoş geldiniz");

        // başlık UTF-8 diyor, meta windows-1254 → başlık kazanır (WHATWG)
        byte[] b3 = "<meta charset=windows-1254><p>Çalışma</p>".getBytes(StandardCharsets.UTF_8);
        KeywordText.Decoded d3 = KeywordText.decode(b3, "text/html; charset=utf-8");
        assertThat(d3.text()).contains("Çalışma");
        assertThat(d3.source()).isEqualTo(KeywordText.SRC_HEADER);

        // 4 KB'tan sonraki meta bildirimi okunmaz → UTF-8 varsayılan
        byte[] b4 = (" ".repeat(5000) + "<meta charset=windows-1254>").getBytes(StandardCharsets.US_ASCII);
        assertThat(KeywordText.decode(b4, null).source()).isEqualTo(KeywordText.SRC_DEFAULT);
    }

    @Test
    @DisplayName("BOM her bildirimi ezer (UTF-8 / UTF-16LE / UTF-16BE) ve metinden ayıklanır")
    void bom() {
        byte[] utf8 = concat(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF }, "İletişim".getBytes(StandardCharsets.UTF_8));
        KeywordText.Decoded d = KeywordText.decode(utf8, "text/html; charset=windows-1254");
        assertThat(d.text()).isEqualTo("İletişim");
        assertThat(d.source()).isEqualTo(KeywordText.SRC_BOM);

        byte[] le = concat(new byte[] { (byte) 0xFF, (byte) 0xFE }, "Şube".getBytes(StandardCharsets.UTF_16LE));
        assertThat(KeywordText.decode(le, null).text()).isEqualTo("Şube");
        byte[] be = concat(new byte[] { (byte) 0xFE, (byte) 0xFF }, "Şube".getBytes(StandardCharsets.UTF_16BE));
        assertThat(KeywordText.decode(be, null).charset()).isEqualTo("UTF-16BE");
    }

    @Test
    @DisplayName("desteklenmeyen küme → UTF-8 (fallback); bildirim yokken geçersiz UTF-8 yine UTF-8 (sezgisel tahmin YOK)")
    void fallbacks() {
        byte[] b = "<p>abc</p>".getBytes(StandardCharsets.UTF_8);
        KeywordText.Decoded d = KeywordText.decode(b, "text/html; charset=x-bilinmeyen-kume");
        assertThat(d.charset()).isEqualTo("UTF-8");
        assertThat(d.source()).isEqualTo(KeywordText.SRC_FALLBACK);

        byte[] w = "Giriş".getBytes(W1254);
        KeywordText.Decoded d2 = KeywordText.decode(w, "text/html");
        assertThat(d2.charset()).isEqualTo("UTF-8");
        assertThat(d2.source()).isEqualTo(KeywordText.SRC_DEFAULT);
        assertThat(d2.text()).contains("�");
        // alternatif okuma (CHARSET ipucu): UTF-8 + geçersiz bayt → windows-1254; UTF-8 dışı kullanıldıysa → UTF-8
        assertThat(KeywordText.alternate("UTF-8", d2.text()).name()).isEqualTo("windows-1254");
        assertThat(KeywordText.alternate("windows-1254", "x")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(KeywordText.alternate("UTF-8", "temiz metin")).isNull();
        assertThat(KeywordText.decode(null, null).text()).isEmpty();
    }

    @Test
    @DisplayName("görünüm: RAW ham metin (normalize açıkken varlık + boşluk), VISIBLE görünür metin; iğne aynı normalleştirmeden geçer")
    void viewAndNeedle() {
        String html = "<p>Hoş <b>geldiniz</b>&nbsp;&nbsp;G&uuml;venli</p><script>var x='gizli';</script>";
        assertThat(KeywordText.view(html, null, false)).isEqualTo(html);
        assertThat(KeywordText.view(html, "RAW", true)).contains("Güvenli").doesNotContain("&uuml;");
        String visible = KeywordText.view(html, "VISIBLE", false);
        assertThat(visible).isEqualTo("Hoş geldiniz Güvenli").doesNotContain("gizli");
        assertThat(KeywordText.needle("Hoş   geldiniz", "VISIBLE", false)).isEqualTo("Hoş geldiniz");
        assertThat(KeywordText.needle("G&uuml;venli", "RAW", true)).isEqualTo("Güvenli");
        assertThat(KeywordText.needle("a  b", "RAW", false)).isEqualTo("a  b");
        assertThat(KeywordText.scope("visible")).isEqualTo("VISIBLE");
        assertThat(KeywordText.scope("zzz")).isEqualTo("RAW");
        assertThat(KeywordText.scope(null)).isEqualTo("RAW");
    }

    @Test
    @DisplayName("visibleLines: blok etiketleri satır, satır içi boşluk tek, boş satır ve betik düşer (değişim parmak izi)")
    void visibleLines() {
        String html = "<html><head><title>Fiyatlar</title><style>p{}</style></head><body>"
                + "<div>Ürün A   <b>100 TL</b></div>\n\n<div>   </div><p>Ürün B&nbsp;200 TL</p><script>x()</script>"
                + "<ul><li>Bir</li><li>İki</li></ul>son</body></html>";
        assertThat(KeywordBodyAnalyzer.visibleLines(html, 2_000_000, 2_000_000))
                .isEqualTo("Fiyatlar\nÜrün A 100 TL\nÜrün B 200 TL\nBir\nİki\nson");
        assertThat(KeywordBodyAnalyzer.visibleLines("", 10, 10)).isEmpty();
        assertThat(KeywordBodyAnalyzer.visibleLines("<p>" + "a".repeat(500) + "</p>", 2_000_000, 50)).hasSizeLessThanOrEqualTo(50);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
