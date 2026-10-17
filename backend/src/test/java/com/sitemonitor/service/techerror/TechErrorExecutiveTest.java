package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.techerror.TechErrorRichText.Block;
import com.sitemonitor.service.techerror.TechErrorRichText.Run;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Yönetici raporu yapısı (2026-10-14): yönetici özeti cümle kalıpları, zengin metin (madde / alt madde / kalın),
 * grup satırlı tablo bölümü, değerlerin şablon değişiminde taşınması.
 */
class TechErrorExecutiveTest {

    private static final Template TR = TechErrorTemplate.defaults("tr");

    /**
     * Varsayılan şablonda tablo yok (SLA tablosu 2026-10-15'ten beri ekran görüntüsü); tablo bölümü türü duruyor —
     * tablo testleri eski SLA tablosunu şablona ekler.
     */
    static final Template TRT = TechErrorTemplate.normalize(withSlaTable());

    private static Section section(Template t, String kind) {
        return t.sections().stream().filter(s -> kind.equals(s.kind())).findFirst().orElseThrow();
    }

    // ── Yönetici özeti ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("özet: girilen rakamlarla dolar, rakamlar kalın, cümleler tek paragrafta akar; değeri olmayan cümle düşer")
    void summaryFillsFromValues() {
        Map<String, Object> f = new HashMap<>();
        f.put("error-rate", 0.42);
        f.put("total-tx", 1250000);
        f.put("tx-increase", true);
        f.put("tx-increase-pct", 3.5);
        f.put("error-count", 52500);
        f.put("unmapped-count", 120);
        f.put("quota-count", 80);
        f.put("unmapped-rate", 0.228571);
        f.put("quota-rate", 0.152381);
        String text = TechErrorSummary.auto(section(TR, "summary"), TR, f, "Mobil", 2026, 9);
        assertThat(text).startsWith("Mobil Kanalı Eylül 2026 verilerine göre teknik hata oranı **%0,42** olarak gerçekleşmiştir.");
        assertThat(text).contains("toplam işlem adedi **1.250.000** olarak gerçekleşmiştir. "
                + "Bir önceki aya göre işlem adedinde **%3,5** artış görülmüştür.");
        // Değişim bilinmese de (ilk ay) toplam işlem adedi özette kalır — ayrı cümle
        Map<String, Object> first = new HashMap<>(f);
        first.remove("tx-increase-pct");
        assertThat(TechErrorSummary.auto(section(TR, "summary"), TR, first, "Mobil", 2026, 9))
                .contains("toplam işlem adedi **1.250.000** olarak gerçekleşmiştir.").doesNotContain("artış");
        assertThat(text).contains("Ay içindeki teknik hata adedi **52.500** olarak gerçekleşmiştir. "
                + "Map yapılmamış hata adedi **120** olup teknik hataların **%0,2286** kadarını oluşturmaktadır. "
                + "Kota hatası adedi **80** olup teknik hataların **%0,1524** kadarını oluşturmaktadır.");
        // kesinti oranı ve SLA girilmedi → o cümleler yok, yarım cümle yok
        assertThat(text).doesNotContain("Kesintiler hariç").doesNotContain("SLA").doesNotContain("{f:");
        assertThat(text).doesNotContain("\n");                                   // tek paragraf
        // Trend cümlesi özette yok ("Grafikler hakkında" bölümünü tekrar ediyor, grafik yoksa da basılıyordu)
        assertThat(text).doesNotContain("Yıllık trend").endsWith("Kota hatası adedi **80** olup teknik hataların **%0,1524** kadarını oluşturmaktadır.");
        // Kota boş kalsa da hata adedi ve map yapılmamış adet özette kalır (eskiden tek cümleydi, hepsi düşüyordu)
        f.remove("quota-count");
        assertThat(TechErrorSummary.auto(section(TR, "summary"), TR, f, "Mobil", 2026, 9))
                .contains("teknik hata adedi **52.500**").contains("Map yapılmamış hata adedi **120**").doesNotContain("Kota");
    }

    @Test
    @DisplayName("özet: 'kesinti yok' cevabıyla muaf rakamın cümlesi düşer; evet/hayır seçimi cevaba göre metin verir")
    void summaryDropsExemptAndChoosesYesNo() {
        Map<String, Object> f = new HashMap<>();
        f.put("had-outage", false);
        f.put("error-rate-excl", 0.3);              // muaf (Hayır) → yazılmaz
        f.put("outage-errors", 12);
        f.put("outage-share", 5.0);
        f.put("sla-rate", 99.2);
        f.put("sla-above", false);
        String no = TechErrorSummary.auto(section(TR, "summary"), TR, f, "ATM", 2026, 9);
        assertThat(no).doesNotContain("Kesintiler hariç").doesNotContain("kesinti kaynaklı");
        // 2026-10-17 (4b): SLA cümlesi hedefi ({t:}) ve hedefe uzaklığı ({dt:}, puan) söyler
        assertThat(no).contains("kritik fonksiyonların SLA oranı **%99,2** (hedef **%99,95**, fark **−0,75 puan**) ile hedefin altında kalmıştır.");
        // Önceki aya göre fark cümlesi ({d:}) geçen ayın raporu olmadan düşer
        assertThat(no).doesNotContain("Önceki aya göre teknik hata adedi");
        f.put("had-outage", true);
        f.put("sla-above", true);
        String yes = TechErrorSummary.auto(section(TR, "summary"), TR, f, "ATM", 2026, 9);
        assertThat(yes).contains("Kesintiler hariç teknik hata oranı **%0,3**").contains("**12** adedi kesinti kaynaklı")
                .contains("hedef başarıyla tutturulmuştur");
        // EN şablon: EN biçim ve seçenek
        Template en = TechErrorTemplate.defaults("en");
        String e = TechErrorSummary.auto(section(en, "summary"), en, Map.of("sla-rate", 99.2, "sla-above", true), "ATM", 2026, 9);
        assertThat(e).contains("SLA rate was **99.2%** (target **99.95%**, difference **−0.75 pts**), so the target was met.");
    }

    @Test
    @DisplayName("özet: elle düzenlenen metin kullanılır (yer tutucular çözülür); BOŞ elle metin otomatik sayılır, saklanmaz")
    void summaryOverride() {
        Section s = section(TR, "summary");
        Values v = TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", 0.42),
                "texts", Map.of(s.id(), "{kanal} için **elle** yazılmış özet.\n- madde")), TR);
        assertThat(TechErrorSummary.effective(s, TR, v, "POS", 2026, 9)).isEqualTo("POS için **elle** yazılmış özet.\n- madde");
        // İnceleme 2026-10-14: boş özetle "elle düzenle" özeti kalıcı olarak boş bırakıyordu → boş = otomatik, anahtar saklanmaz
        Values blank = TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", 0.42), "texts", Map.of(s.id(), " \n ")), TR);
        assertThat(blank.texts()).doesNotContainKey(s.id());
        assertThat(TechErrorSummary.effective(s, TR, blank, "POS", 2026, 9)).contains("**%0,42**");
        // Metin bölümünde boş metin ANLAMLIDIR (bölüm basılmaz) — o kural değişmedi
        Section text = TR.sections().stream().filter(x -> "text".equals(x.kind())).findFirst().orElseThrow();
        assertThat(TechErrorValues.normalize(Map.of("texts", Map.of(text.id(), "")), TR).texts()).containsEntry(text.id(), "");
        // Saklı (eski) boş özet de otomatiğe döner
        Values stored = TechErrorValues.fromStored("{\"fields\":{\"error-rate\":0.42},\"texts\":{\"" + s.id() + "\":\"\"}}", TR);
        assertThat(TechErrorSummary.effective(s, TR, stored, "POS", 2026, 9)).contains("**%0,42**");
        Values none = TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", 0.42)), TR);
        assertThat(TechErrorSummary.effective(s, TR, none, "POS", 2026, 9)).contains("**%0,42**");
    }

    @Test
    @DisplayName("özet: madde cümleleri ayrı satır, sonraki düz cümle yeni paragraf; metin değerindeki ** işaretlemeyi bozmaz")
    void summaryItemsAndNeutralText() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(
                Map.of("id", "m", "kind", "metrics", "title", "G", "fields", List.of(
                        Map.of("id", "a", "label", "A", "type", "number"),
                        Map.of("id", "note", "label", "Not", "type", "text"))),
                Map.of("id", "s", "kind", "summary", "title", "Özet", "lines", List.of(
                        "Giriş cümlesi.", "- A adedi {f:a}", "- Not: {f:note}", "Kapanış cümlesi."))));
        Template t = TechErrorTemplate.normalize(raw);
        String text = TechErrorSummary.auto(t.sections().get(1), t, Map.of("a", 5, "note", "a**b**c"), "K", 2026, 1);
        assertThat(text).isEqualTo("Giriş cümlesi.\n- A adedi **5**\n- Not: a*b*c\n\nKapanış cümlesi.");
        List<Block> blocks = TechErrorRichText.parse(text);
        assertThat(blocks).extracting(Block::kind).containsExactly("p", "li", "li", "gap", "p");
    }

    @Test
    @DisplayName("özet kalıbı doğrulaması: okunamayan başvuru, 20+ cümle, boş özet reddedilir; silinmiş alana bağlı cümle kaydı kilitlemez")
    void summaryTemplateRules() {
        // alan silmek özet yüzünden kilitlenmez: cümle kalır, raporda düşer (düzenleyici uyarır)
        Template dangling = TechErrorTemplate.normalize(withSummary("Oran {f:yok-boyle}."));
        assertThat(TechErrorSummary.auto(section(dangling, "summary"), dangling, Map.of(), "K", 2026, 1)).isEmpty();
        Template wrongType = TechErrorTemplate.normalize(withSummary("Oran {f:error-rate?iyi|kötü}."));
        assertThat(TechErrorSummary.auto(section(wrongType, "summary"), wrongType, Map.of("error-rate", 1), "K", 2026, 1)).isEmpty();
        assertInvalid(withSummary("Oran {f:error-rate."));
        assertInvalid(withSummary("Oran {f:Hatalı Kimlik}."));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) many.add("Cümle " + i);
        assertInvalid(withSummaryLines(many));
        assertInvalid(withSummaryLines(List.of()));
        assertInvalid(withSummary("x".repeat(501)));
        // geçerli: evet/hayır alanında seçim, sayı alanında değer
        Template ok = TechErrorTemplate.normalize(withSummary("{f:sla-above?Üstünde|Altında} · {f:error-rate}"));
        assertThat(section(ok, "summary").lines()).containsExactly("{f:sla-above?Üstünde|Altında} · {f:error-rate}");
        // saklı (hoşgörülü) okuma: olmayan alana bağlı cümle kalır, gösterimde düşer
        Template lenient = TechErrorTemplate.fromStored(TechErrorTemplate.JSON.writeValueAsString(withSummary("Oran {f:yok-boyle}.")));
        Section ls = section(lenient, "summary");
        assertThat(ls.lines()).containsExactly("Oran {f:yok-boyle}.");
        assertThat(TechErrorSummary.auto(ls, lenient, Map.of(), "K", 2026, 1)).isEmpty();
    }

    @Test
    @DisplayName("başvuru taraması doğrusal ve sınırlı: kapanmayan / geçersiz başvuru düz metin, malformed işaretler")
    void refScan() {
        assertThat(TechErrorSummary.refs("{f:a} ve {f:b?x|y} ve {f:c?yalnız}"))
                .extracting(TechErrorSummary.Ref::id, TechErrorSummary.Ref::yes, TechErrorSummary.Ref::no)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("a", null, null),
                        org.assertj.core.groups.Tuple.tuple("b", "x", "y"),
                        org.assertj.core.groups.Tuple.tuple("c", "yalnız", ""));
        assertThat(TechErrorSummary.malformed("{f:a} {f:")).isTrue();
        assertThat(TechErrorSummary.malformed("{f:A B}")).isTrue();
        assertThat(TechErrorSummary.malformed("{kanal} {f:a}")).isFalse();
        // İç içe süslü parantez okunamaz: kaydı engeller (ilk "}"de bitip bozuk basılırdı); seçim kesilmez
        assertThat(TechErrorSummary.malformed("{f:sla-above?{ay} ayında üstünde|altında}")).isTrue();
        String longYes = "ü".repeat(200);
        assertThat(TechErrorSummary.refs("{f:a?" + longYes + "|b}").get(0).yes()).isEqualTo(longYes);
        assertThat(TechErrorSummary.refs("{f:a}".repeat(150))).hasSize(TechErrorSummary.MAX_REFS);
    }

    // ── Zengin metin ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("zengin metin: madde, alt madde (2 boşluk = 1 düzey, en çok 3), numaralı madde, kalın, boş satır aralığı")
    void richTextParse() {
        String text = "Giriş **kalın** metin\n\n\n- Birinci\n  - Alt madde\n      - Çok derin\n* Yıldızlı\n• Noktalı\n12. Numaralı\n3) Parantezli\n"
                + "  devam satırı\n**yarım";
        List<Block> b = TechErrorRichText.parse(text);
        assertThat(b).extracting(Block::kind)
                .containsExactly("p", "gap", "li", "li", "li", "li", "li", "li", "li", "p", "p");
        assertThat(b.get(0).runs()).containsExactly(new Run("Giriş ", false), new Run("kalın", true), new Run(" metin", false));
        assertThat(b.get(2).level()).isZero();
        assertThat(b.get(3).level()).isEqualTo(1);
        assertThat(b.get(4).level()).isEqualTo(TechErrorRichText.MAX_LEVEL);
        assertThat(b.get(7).marker()).isEqualTo("12.");
        assertThat(b.get(8).marker()).isEqualTo("3.");
        assertThat(b.get(9).level()).isEqualTo(1);                       // girintili düz satır
        assertThat(b.get(10).runs()).containsExactly(new Run("**yarım", false));   // eşsiz ** düz metin
        assertThat(TechErrorRichText.plain("- **A**\n  - b\n\nson")).isEqualTo("- A\n  - b\n\nson");
        // "*kalın değil" ve "**başta kalın**" madde sayılmaz
        assertThat(TechErrorRichText.parse("**başta kalın** sonra").get(0).kind()).isEqualTo("p");
        assertThat(TechErrorRichText.parse("*yalnız").get(0).kind()).isEqualTo("p");
    }

    /**
     * Kalın işaret doğruluk tablosu — {@code techErrorExecutiveModel.test.js} AYNI tabloyu kullanır (Java ve JS aynı
     * çıktıyı vermeli). Biçim: girdi, ardından (metin, kalın mı) çiftleri.
     */
    static final Object[][] BOLD_TABLE = {
            { "a**b**c**d", "a", false, "b", true, "c**d", false },
            { "****", "****", false },
            { "Kart 4543 **** **** 1234", "Kart 4543 **** **** 1234", false },
            { "**%0,42** olarak", "%0,42", true, " olarak", false },
            { "** boşluk ** yok", "** boşluk ** yok", false },
            { "a ** b", "a ** b", false },
            { "**a **b**", "**a ", false, "b", true },
            { "x***y***z", "x***y***z", false },
            { "**ç**", "ç", true },
            { "**yarım", "**yarım", false },
            { "a**b", "a**b", false },
            { "Kod: ****1234 ve **önemli**", "Kod: ****1234 ve ", false, "önemli", true },
            { "ab**@x.com ve cd**@y.com", "ab**@x.com ve cd**@y.com", false },
            { "x (**%5**) y", "x (", false, "%5", true, ") y", false },
            { "oran **%0,42**.", "oran ", false, "%0,42", true, ".", false },
            { "a**(b)**c", "a**(b)**c", false },
    };

    @Test
    @DisplayName("kalın: TAM iki yıldız, açılıştan sonra / kapanıştan önce boşluk yok; maskeli kart numarası yıldızlarını kaybetmez")
    void boldTruthTable() {
        for (Object[] row : BOLD_TABLE) {
            List<Run> expected = new ArrayList<>();
            for (int i = 1; i < row.length; i += 2) expected.add(new Run((String) row[i], (Boolean) row[i + 1]));
            assertThat(TechErrorRichText.inline((String) row[0])).as((String) row[0]).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("özet: metin değerindeki yıldız dizisi işaretlemeyi bozmaz; uzun açıklama alanına başvuran cümle düşer; madde kararını kalıp verir")
    void summaryValueSafety() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(
                Map.of("id", "m", "kind", "metrics", "title", "G", "fields", List.of(
                        Map.of("id", "card", "label", "Kart", "type", "text"),
                        Map.of("id", "n", "label", "Adet", "type", "number"),
                        Map.of("id", "long", "label", "Not", "type", "textarea"))),
                Map.of("id", "s", "kind", "summary", "title", "Özet", "lines", List.of(
                        "Kart {f:card} ile {f:n} hata oldu.", "Not: {f:long}", "{f:card} ikinci cümle."))));
        Template t = TechErrorTemplate.normalize(raw);
        Section s = t.sections().get(1);
        Map<String, Object> f = Map.of("card", "4543 **** 1234", "n", 12, "long", "uzun\nnot");
        String text = TechErrorSummary.auto(s, t, f, "K", 2026, 1);
        // "****" tek yıldıza iner: sayı kalın kalır, cümlenin geri kalanı kalın olmaz
        assertThat(text).isEqualTo("Kart 4543 * 1234 ile **12** hata oldu. 4543 * 1234 ikinci cümle.");
        assertThat(TechErrorRichText.parse(text).get(0).runs())
                .containsExactly(new Run("Kart 4543 * 1234 ile ", false), new Run("12", true),
                        new Run(" hata oldu. 4543 * 1234 ikinci cümle.", false));
        // Değer "1. " ile başlasa da cümle madde sayılmaz (kalıp madde değil) — paragraf akışı bozulmaz
        String numbered = TechErrorSummary.auto(s, t, Map.of("card", "1. şube", "n", 3), "K", 2026, 1);
        assertThat(numbered).isEqualTo("Kart 1. şube ile **3** hata oldu. 1. şube ikinci cümle.");
        // Paragraf BAŞINDAKİ düz cümle değerle "1. " / "* " ile başlarsa işaretten sonraki boşluk bölünmez boşluk
        // olur: PDF / e-posta / önizleme onu madde olarak yeniden okumaz ("* Web" yıldızını kaybetmez)
        Template lead = TechErrorTemplate.normalize(withLines(raw, List.of("{f:card} kanalında {f:n} hata.", "- Madde {f:n}", "{f:card} son.")));
        String led = TechErrorSummary.auto(lead.sections().get(1), lead, Map.of("card", "1. Bölge", "n", 3), "K", 2026, 1);
        assertThat(led).isEqualTo("1.\u00A0Bölge kanalında **3** hata.\n- Madde **3**\n\n1.\u00A0Bölge son.");
        assertThat(TechErrorRichText.parse(led)).extracting(Block::kind).containsExactly("p", "li", "gap", "p");
        String star = TechErrorSummary.auto(lead.sections().get(1), lead, Map.of("card", "* Web", "n", 3), "K", 2026, 1);
        assertThat(TechErrorRichText.parse(star).get(0).plain()).startsWith("*\u00A0Web");
        // Madde kalıbı boş gövdeye çözülürse ("- {f:x?Var|}" → "-") cümle düşer
        Template empty = TechErrorTemplate.normalize(withLines(raw, List.of("Giriş.", "- {f:card}")));
        assertThat(TechErrorSummary.auto(empty.sections().get(1), empty, Map.of("card", ""), "K", 2026, 1)).isEqualTo("Giriş.");
    }

    /** {@code raw} şablonunun özet bölümünü verilen cümlelerle değiştirir. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withLines(Map<String, Object> raw, List<String> lines) {
        Map<String, Object> copy = new LinkedHashMap<>(raw);
        List<Object> sections = new ArrayList<>((List<Object>) raw.get("sections"));
        Map<String, Object> sum = new LinkedHashMap<>((Map<String, Object>) sections.get(1));
        sum.put("lines", lines);
        sections.set(1, sum);
        copy.put("sections", sections);
        return copy;
    }

    @Test
    @DisplayName("çok satırlı metin: satır başındaki sekme 2 boşluk (alt düzey), satır içindeki tek boşluk; ilk satırın girintisi korunur")
    void multilineTabsAndIndent() {
        assertThat(TechErrorTemplate.multiline("\n \n\t- alt\n\t\t- daha alt\nsatır\tiçi  \n")).isEqualTo("  - alt\n    - daha alt\nsatır içi");
        List<Block> b = TechErrorRichText.parse(TechErrorTemplate.multiline("\t- alt madde"));
        assertThat(b.get(0).level()).isEqualTo(1);
        assertThat(TechErrorTemplate.multiline("   \n\t\n ")).isEmpty();
        // Bölünmez boşluklu satır (Word / Outlook) paragraf aralığıdır; ASCII olmayan rakam numaralı madde değildir
        assertThat(TechErrorRichText.parse("a\n  \nb")).extracting(Block::kind).containsExactly("p", "gap", "p");
        assertThat(TechErrorRichText.parse("\u0661. madde").get(0).kind()).isEqualTo("p");
        // Satır içi sekme kaydedilen metindeki gibi tek boşluk: "1.\tMadde" / "-\tMadde" madde (önizleme ile aynı)
        assertThat(TechErrorRichText.parse("1.\tMadde\n-\tİkinci\n\t- alt")).extracting(Block::kind, Block::level)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("li", 0), org.assertj.core.groups.Tuple.tuple("li", 0),
                        org.assertj.core.groups.Tuple.tuple("li", 1));
    }

    @Test
    @DisplayName("zengin metin sınırlı: en çok MAX_BLOCKS satır okunur")
    void richTextIsBounded() {
        String text = "- x\n".repeat(TechErrorRichText.MAX_BLOCKS + 500);
        assertThat(TechErrorRichText.parse(text)).hasSize(TechErrorRichText.MAX_BLOCKS);
    }

    // ── Tablo ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("tablo değerleri: satır × sütun türüyle doğrulanır, boş hücre saklanmaz, alan anahtarı tables.<bölüm>.<satır>.<sütun>")
    void tableValues() {
        Section sla = section(TRT, "table");
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("total", Map.of("functions", 120, "availability", "99.5", "volume-share", -1.5));
        rows.put("sla-critical", Map.of("functions", "", "total-tx", 1000));
        rows.put("bilinmeyen-satir", Map.of("functions", 5));
        Values v = TechErrorValues.normalize(Map.of("tables", Map.of(sla.id(), rows)), TRT);
        assertThat(v.cell(sla.id(), "total", "functions")).isEqualTo(120L);
        assertThat(v.cell(sla.id(), "total", "availability")).isEqualTo(99.5);
        assertThat(v.cell(sla.id(), "total", "volume-share")).isEqualTo(-1.5);       // yüzde negatif olabilir
        assertThat(v.cell(sla.id(), "sla-critical", "functions")).isNull();
        assertThat(v.tables().get(sla.id())).doesNotContainKey("bilinmeyen-satir");
        assertThat(TechErrorValues.toJson(v)).contains("\"tables\"");
        // tablosuz değerlerin JSON'u eski biçimle aynı (tables anahtarı yok)
        assertThat(TechErrorValues.toJson(TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", 1)), TR)))
                .doesNotContain("tables");

        assertInvalidValue(Map.of("tables", Map.of(sla.id(), Map.of("total", Map.of("functions", -1)))),
                "tables." + sla.id() + ".total.functions", TRT);
        assertInvalidValue(Map.of("tables", Map.of(sla.id(), Map.of("total", Map.of("success-tx", "çok")))),
                "tables." + sla.id() + ".total.success-tx", TRT);
        // saklı okuma hoşgörülü: geçersiz hücre düşer
        Values lenient = TechErrorValues.fromStored("{\"tables\":{\"" + sla.id() + "\":{\"total\":{\"functions\":-5,\"total-tx\":7}}}}", TRT);
        assertThat(lenient.tables().get(sla.id()).get("total")).containsOnlyKeys("total-tx");
    }

    @Test
    @DisplayName("tablo şablon kuralları: sütun/satır zorunlu, 10+ sütun, 40+ satır, bilinmeyen sütun türü reddedilir; grup bayrağı korunur")
    void tableTemplateRules() {
        assertInvalid(withTable(List.of(), List.of(Map.of("label", "R"))));
        assertInvalid(withTable(List.of(Map.of("label", "C")), List.of()));
        assertInvalid(withTable(List.of(Map.of("label", "C", "type", "tarih")), List.of(Map.of("label", "R"))));
        assertInvalid(withTable(List.of(Map.of("label", "", "type", "number")), List.of(Map.of("label", "R"))));
        List<Object> cols = new ArrayList<>();
        for (int i = 0; i < 11; i++) cols.add(Map.of("label", "C" + i));
        assertInvalid(withTable(cols, List.of(Map.of("label", "R"))));
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 41; i++) rows.add(Map.of("label", "R" + i));
        assertInvalid(withTable(List.of(Map.of("label", "C")), rows));
        Template ok = TechErrorTemplate.normalize(withTable(
                List.of(Map.of("label", "Adet"), Map.of("id", "p", "label", "Oran", "type", "percent")),
                List.of(Map.of("label", "Toplam", "group", true), Map.of("label", "Alt"))));
        Section tb = ok.sections().stream().filter(s -> s.id().equals("tb")).findFirst().orElseThrow();
        assertThat(tb.columns()).extracting(c -> c.id() + ":" + c.type()).containsExactly("c-1:number", "p:percent");
        assertThat(tb.rows()).extracting(r -> r.id() + ":" + r.group()).containsExactly("r-1:true", "r-2:false");
        assertThat(TechErrorTemplate.fromStored(TechErrorTemplate.toJson(ok))).isEqualTo(ok);
    }

    @Test
    @DisplayName("'Hayır ise sorma' bağı çözülürken özet cümleleri ve tablo kaybolmaz (gidiş-dönüş)")
    void skipResolutionKeepsNewComponents() {
        Template t = TechErrorTemplate.normalize(TechErrorTemplate.toMap(TRT));
        assertThat(section(t, "summary").lines()).isEqualTo(section(TRT, "summary").lines()).isNotEmpty();
        assertThat(section(t, "table").columns()).isEqualTo(section(TRT, "table").columns()).isNotEmpty();
        assertThat(section(t, "table").rows()).isEqualTo(section(TRT, "table").rows()).isNotEmpty();
    }

    @Test
    @DisplayName("şablon değişince: özet metni ve tablo hücreleri taşınır; sütun türü değişen ya da silinen hücre düşer")
    void carryKeepsSummaryAndTable() {
        Section sla = section(TRT, "table");
        Section sum = section(TRT, "summary");
        Values before = TechErrorValues.normalize(Map.of(
                "texts", Map.of(sum.id(), "Elle özet"),
                "tables", Map.of(sla.id(), Map.of("total", Map.of("functions", 10, "availability", 99.1, "volume-share", 40)))), TRT);
        Map<String, Object> raw = TechErrorTemplate.toMap(TRT);
        @SuppressWarnings("unchecked") List<Map<String, Object>> secs = new ArrayList<>((List<Map<String, Object>>) raw.get("sections"));
        for (int i = 0; i < secs.size(); i++) {
            if (!"table".equals(secs.get(i).get("kind"))) continue;
            Map<String, Object> s = new LinkedHashMap<>(secs.get(i));
            List<Map<String, Object>> cols = new ArrayList<>();
            for (Object o : (List<?>) s.get("columns")) {
                @SuppressWarnings("unchecked") Map<String, Object> c = new LinkedHashMap<>((Map<String, Object>) o);
                if ("availability".equals(c.get("id"))) c.put("type", "text");       // tür değişti
                if (!"volume-share".equals(c.get("id"))) cols.add(c);              // silindi
            }
            s.put("columns", cols);
            secs.set(i, s);
        }
        raw.put("sections", secs);
        Template next = TechErrorTemplate.normalize(raw);
        Values after = TechErrorReportService.carry(before, TRT, next);
        assertThat(after.texts()).containsEntry(sum.id(), "Elle özet");
        assertThat(after.tables().get(sla.id()).get("total")).containsOnlyKeys("functions");
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> withSummary(String line) {
        return withSummaryLines(List.of(line));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withSummaryLines(List<String> lines) {
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TR));
        List<Map<String, Object>> secs = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) raw.get("sections")) {
            Map<String, Object> c = new LinkedHashMap<>(s);
            if ("summary".equals(s.get("kind"))) c.put("lines", lines);
            secs.add(c);
        }
        raw.put("sections", secs);
        return raw;
    }

    private static Map<String, Object> withTable(List<?> cols, List<?> rows) {
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TR));
        List<Object> secs = new ArrayList<>((List<?>) raw.get("sections"));
        Map<String, Object> tb = new LinkedHashMap<>();
        tb.put("id", "tb");
        tb.put("kind", "table");
        tb.put("title", "Tablo");
        tb.put("columns", cols);
        tb.put("rows", rows);
        secs.add(tb);
        raw.put("sections", secs);
        return raw;
    }

    private static void assertInvalid(Map<String, Object> raw) {
        assertThatThrownBy(() -> TechErrorTemplate.normalize(raw))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("template"));
    }

    /** Eski varsayılan SLA tablosu (6 sütun, grup satırlı) — kimlikler 20.134.0'daki gibi. */
    static Map<String, Object> withSlaTable() {
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TR));
        List<Object> secs = new ArrayList<>((List<?>) raw.get("sections"));
        Map<String, Object> tb = new LinkedHashMap<>();
        tb.put("id", "sla-table");
        tb.put("kind", "table");
        tb.put("title", "SLA Erişilebilirlik Tablosu");
        tb.put("columns", List.of(
                Map.of("id", "functions", "label", "Fonksiyon adedi", "type", "number"),
                Map.of("id", "success-tx", "label", "Başarılı işlem adedi", "type", "number"),
                Map.of("id", "total-tx", "label", "Toplam işlem adedi", "type", "number"),
                Map.of("id", "failed-tx", "label", "Teknik başarısız işlem adedi", "type", "number"),
                Map.of("id", "availability", "label", "Erişilebilirlik (kesintisiz)", "type", "percent"),
                Map.of("id", "volume-share", "label", "Toplam işlem hacmi payı", "type", "percent")));
        tb.put("rows", List.of(
                Map.of("id", "total", "label", "{kanal} Kanalı Toplam", "group", true),
                Map.of("id", "sla-critical", "label", "SLA dahil ve kritik"),
                Map.of("id", "sla-noncritical", "label", "SLA dahil ve kritik olmayan"),
                Map.of("id", "out-of-sla", "label", "SLA kapsamında olmayan")));
        secs.add(tb);
        raw.put("sections", secs);
        return raw;
    }

    private static void assertInvalidValue(Map<String, Object> raw, String key) {
        assertInvalidValue(raw, key, TR);
    }

    private static void assertInvalidValue(Map<String, Object> raw, String key, Template tpl) {
        assertThatThrownBy(() -> TechErrorValues.normalize(raw, tpl))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey(key));
    }
}
