package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorIncidentText.Converted;
import com.sitemonitor.service.techerror.TechErrorIncidentText.ImageRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Olay kaydı markdown'ı → teknik hata raporunun zengin metin alt kümesi (2026-11-20). Çıktı {@link TechErrorRichText}'in
 * okuduğu biçimde olmalı; girdi 64 KB'ta kesilir, derin iç içe girdi yığını taşırmaz.
 */
class TechErrorIncidentTextTest {

    private static String conv(String md) {
        return TechErrorIncidentText.convert(md).text();
    }

    @Test
    @DisplayName("boş / null girdi boş metin")
    void blank() {
        assertThat(conv(null)).isEmpty();
        assertThat(conv("")).isEmpty();
        assertThat(conv("  \n\t ")).isEmpty();
        assertThat(TechErrorIncidentText.convert(null).inputClipped()).isFalse();
    }

    @Test
    @DisplayName("başlık kalın satır; kalın korunur, italik metni kalır, satır içi kod düz; paragraflar arası tek boş satır")
    void inlineAndHeadings() {
        String out = conv("# Başlık *bir*\n\nMetin **kalın** ve *italik* `kod`.\nAynı paragraf.\n\n\n\nİkinci");
        assertThat(out).isEqualTo("**Başlık bir**\n\nMetin **kalın** ve italik kod. Aynı paragraf.\n\nİkinci");
        List<TechErrorRichText.Block> b = TechErrorRichText.parse(out);
        assertThat(b.get(0).runs()).containsExactly(new TechErrorRichText.Run("Başlık bir", true));
        assertThat(b.get(2).runs()).anySatisfy(r -> assertThat(r).isEqualTo(new TechErrorRichText.Run("kalın", true)));
    }

    @Test
    @DisplayName("madde ve numaralı liste; iç içe en çok 3 düzey (daha derini 3. düzeyde); görev maddesi işaretli")
    void lists() {
        String md = """
                - bir
                  - iki
                    - üç
                      - dört
                - [x] bitti
                - [ ] kaldı

                3. üçüncü
                4. dördüncü
                """;
        String out = conv(md);
        assertThat(out).isEqualTo("""
                - bir
                  - iki
                    - üç
                    - dört
                - ☑ bitti
                - ☐ kaldı

                3. üçüncü
                4. dördüncü""");
        List<TechErrorRichText.Block> b = TechErrorRichText.parse(out);
        assertThat(b.get(0).item()).isTrue();
        assertThat(b.get(1).level()).isEqualTo(1);
        assertThat(b.get(2).level()).isEqualTo(2);
        assertThat(b.get(3).level()).isEqualTo(2);
        assertThat(b.get(b.size() - 1).marker()).isEqualTo("4.");
    }

    @Test
    @DisplayName("bağlantı 'metin (adres)'; metin adresle aynıysa yalnız adres")
    void links() {
        assertThat(conv("[Panel](https://x.example/p) ve <https://y.example>"))
                .isEqualTo("Panel (https://x.example/p) ve https://y.example");
    }

    @Test
    @DisplayName("görsel kaldırılır; hemen üstündeki aynı adlı kalın ad satırı da gider, farklı adlıysa kalır")
    void images() {
        String md = "Önce\n\n**Ekran 1**\n\n![Ekran 1](/api/incidents/images/12)\n\n**Başka**\n\n![Ekran 2](/api/incidents/images/13)\n\nSonra";
        assertThat(conv(md)).isEqualTo("Önce\n\n**Başka**\n\nSonra");
        assertThat(conv("**Ad** ![Ad](/api/incidents/images/5)\n\nx")).isEqualTo("x");
        assertThat(conv("Metin ![a](/x.png) devam")).doesNotContain("![", "/x.png").startsWith("Metin").endsWith("devam");
    }

    @Test
    @DisplayName("tablo satırları 'a | b'; kod bloğu düz satırlar; ham HTML etiketsiz metin + varlıklar")
    void tablesCodeHtml() {
        String md = """
                | Ad | Değer |
                |----|-------|
                | a  | 1     |

                ```
                SELECT 1;
                  girintili
                ```

                <div>Merhaba &amp; <b>dünya</b></div>
                """;
        assertThat(conv(md)).isEqualTo("Ad | Değer\na | 1\n\nSELECT 1;\ngirintili\n\nMerhaba & dünya");
    }

    @Test
    @DisplayName("metindeki 2+ yıldız tek yıldıza iner (kalın sanılmaz); maddeye benzeyen düz satır madde olmaz")
    void neutralStarsAndItemLikeLines() {
        assertThat(conv("kart 4543 **** 1234")).isEqualTo("kart 4543 * 1234");
        assertThat(conv("`a**b`")).isEqualTo("a*b");
        String escaped = conv("1\\. madde değil\n\n\\- bu da değil");
        List<TechErrorRichText.Block> b = TechErrorRichText.parse(escaped);
        assertThat(b).noneMatch(TechErrorRichText.Block::item);
        String code = conv("```\n- kod satırı\n```");
        assertThat(TechErrorRichText.parse(code)).noneMatch(TechErrorRichText.Block::item);
    }

    @Test
    @DisplayName("64 KB üstü girdi ayrıştırmadan ÖNCE kesilir (inputClipped) ve hızlı biter")
    void inputBound() {
        String big = "a".repeat(80_000);
        Converted c = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> TechErrorIncidentText.convert(big));
        assertThat(c.inputClipped()).isTrue();
        assertThat(c.text()).hasSize(TechErrorIncidentText.MAX_INPUT_CHARS);
        Converted exact = TechErrorIncidentText.convert("b".repeat(TechErrorIncidentText.MAX_INPUT_CHARS));
        assertThat(exact.inputClipped()).isFalse();
        // Vekil çifti bölünmez
        String emoji = "a" + "😀".repeat(40_000);
        Converted e = TechErrorIncidentText.convert(emoji);
        assertThat(Character.isHighSurrogate(e.text().charAt(e.text().length() - 1))).isFalse();
    }

    @Test
    @DisplayName("derin iç içe girdi (alıntı, liste, köşeli parantez, yıldız, HTML) yığını taşırmaz ve hızlı biter")
    void deepNesting() {
        String[] hostile = {
                ">".repeat(60_000) + " x",
                "- ".repeat(30_000) + "x",
                "[".repeat(60_000) + "x" + "](".repeat(1),
                "*".repeat(60_000) + "x" + "*".repeat(60_000),
                "**a ".repeat(15_000),
                "<div>".repeat(10_000) + "&".repeat(30_000),
                "&".repeat(60_000),
                "<".repeat(60_000),
                "  ".repeat(20_000) + "- x",
                "| a ".repeat(15_000) + "|\n" + "|---".repeat(15_000) + "|\n",
                "![".repeat(30_000) + "x",
        };
        for (String h : hostile) {
            Converted c = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> TechErrorIncidentText.convert(h));
            assertThat(c.text().length()).isLessThanOrEqualTo(TechErrorIncidentText.MAX_INPUT_CHARS * 2 + 64);
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> TechErrorIncidentText.imageRefs(h + "/api/incidents/images/1"));
        }
    }

    @Test
    @DisplayName("olay görseli başvuruları: belge sırasıyla, tekrarsız, mutlak adres ve sorgu da olur; başka yol yok sayılır; en çok 50")
    void imageRefs() {
        String md = "![a](/api/incidents/images/12) ![b](https://site.example/api/incidents/images/7?x=1) "
                + "![c](/api/incidents/images/12) ![d](/api/other/images/3) ![e](/api/incidents/images/9x) "
                + "![f](/api/incidents/images/) [g](/api/incidents/images/8)";
        List<ImageRef> refs = TechErrorIncidentText.imageRefs(md);
        assertThat(refs).containsExactly(new ImageRef(12, "a"), new ImageRef(7, "b"));
        assertThat(TechErrorIncidentText.imageRefs(null)).isEmpty();
        assertThat(TechErrorIncidentText.imageRefs("metin")).isEmpty();

        StringBuilder many = new StringBuilder();
        for (int k = 1; k <= 80; k++) many.append("![x](/api/incidents/images/").append(k).append(")\n\n");
        assertThat(TechErrorIncidentText.imageRefs(many.toString())).hasSize(50);
    }

    @Test
    @DisplayName("görsel kimliği ayrıştırma: rakamlar, ? / # sonrası serbest, başka karakter yok, 18 haneden uzunu yok")
    void imageId() {
        assertThat(TechErrorIncidentText.imageId("/api/incidents/images/42")).isEqualTo(42L);
        assertThat(TechErrorIncidentText.imageId("/api/incidents/images/42?v=2")).isEqualTo(42L);
        assertThat(TechErrorIncidentText.imageId("/api/incidents/images/42#x")).isEqualTo(42L);
        assertThat(TechErrorIncidentText.imageId("/api/incidents/images/42/raw")).isNull();
        assertThat(TechErrorIncidentText.imageId("/api/incidents/images/abc")).isNull();
        assertThat(TechErrorIncidentText.imageId("/api/incidents/images/" + "9".repeat(25))).isNull();
        assertThat(TechErrorIncidentText.imageId(null)).isNull();
    }
}
