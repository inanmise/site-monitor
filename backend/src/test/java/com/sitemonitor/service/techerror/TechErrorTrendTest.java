package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Otomatik trend (2026-10-15, Faz C): şablon kuralları, son 12 ayın verisi, çubuk etiketinin kısa biçimi. */
class TechErrorTrendTest {

    static final Template TR = TechErrorTestTemplates.withTrend("tr");

    private static TechErrorReport report(long id, int year, int month, Template t, Map<String, Object> fields) {
        TechErrorReport r = new TechErrorReport();
        r.setId(id);
        r.setReportYear(year);
        r.setReportMonth(month);
        r.setTemplateJson(TechErrorTemplate.toJson(t));
        r.setValuesJson(TechErrorValues.toJson(TechErrorValues.normalize(Map.of("fields", fields), t)));
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withTrend(Template base, Object series) {
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(base));
        List<Object> secs = new ArrayList<>((List<?>) raw.get("sections"));
        Map<String, Object> tr = new LinkedHashMap<>();
        tr.put("id", "tr2");
        tr.put("kind", "trend");
        tr.put("title", "Trend");
        tr.put("series", series);
        secs.add(tr);
        raw.put("sections", secs);
        return raw;
    }

    @Test
    @DisplayName("trend bölümü (ekibin eklediği): beş alan, hepsi sayı / yüzde; gidiş-dönüş korunur. Varsayılanda trend YOK (2026-10-16)")
    void defaultHasTrend() {
        // Kullanıcı kararı 2026-10-16: grafikleri görsel olarak kendileri sağlıyor — varsayılan şablonda trend bölümü yok
        assertThat(TechErrorTemplate.defaults("tr").sections()).noneMatch(x -> "trend".equals(x.kind()));
        assertThat(TechErrorTemplate.defaults("en").sections()).noneMatch(x -> "trend".equals(x.kind()));
        Section s = TR.sections().stream().filter(x -> "trend".equals(x.kind())).findFirst().orElseThrow();
        assertThat(s.title()).isEqualTo("Otomatik Trendler (Son 12 Ay)");
        assertThat(s.lines()).containsExactly("error-rate", "total-tx", "error-count", "unmapped-count", "quota-count");
        int trendAt = TR.sections().indexOf(s);
        assertThat(TR.sections().get(trendAt + 1).id()).isEqualTo("charts");
        assertThat(TechErrorTrend.referencedFields(TR)).containsExactly("error-rate", "total-tx", "error-count",
                "unmapped-count", "quota-count");
        assertThat(TechErrorTemplate.toMap(TR).toString()).contains("series=[error-rate, total-tx");
        Template again = TechErrorTemplate.normalize(TechErrorTemplate.toMap(TR));
        assertThat(again).isEqualTo(TR);
        Section en = TechErrorTestTemplates.withTrend("en").sections().stream().filter(x -> "trend".equals(x.kind())).findFirst().orElseThrow();
        assertThat(en.title()).isEqualTo("Automatic Trends (Last 12 Months)");
        assertThat(en.lines()).isEqualTo(s.lines());
    }

    @Test
    @DisplayName("şablon kuralları: boş / 8+ alan / okunamayan kimlik katıda 400; olmayan ya da sayı olmayan alan kabul (kilitlenmesin), çizilmez")
    void templateRules() {
        assertInvalid(withTrend(TR, List.of()));
        List<String> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) nine.add("f" + i);
        assertInvalid(withTrend(TR, nine));
        assertInvalid(withTrend(TR, List.of("Kötü Kimlik!")));
        // Şablonda olmayan ya da sayı / yüzde olmayan alan REDDEDİLMEZ (alan silmek / türünü değiştirmek kilitlenmesin):
        // grafik çizilmez — referencedFields onları saymaz; tekrar tekilleşir
        Template ok = TechErrorTemplate.normalize(withTrend(TR, List.of("error-rate", "silinmis-alan", "unmapped-note", "error-rate")));
        Section s = ok.sections().get(ok.sections().size() - 1);
        assertThat(s.lines()).containsExactly("error-rate", "silinmis-alan", "unmapped-note");
        Template only = TechErrorTemplate.normalize(withTrend(TR, List.of("had-outage")));
        assertThat(only.sections().get(only.sections().size() - 1).lines()).containsExactly("had-outage");
        assertThat(TechErrorTrend.referencedFields(only)).doesNotContain("had-outage");
        // hoşgörülü okuma: okunamayan kimlik düşer, kalanı korunur
        Template lenient = TechErrorTemplate.fromStored(TechErrorTemplate.JSON.writeValueAsString(withTrend(TR, List.of("Kötü!", "total-tx"))));
        assertThat(lenient.sections().get(lenient.sections().size() - 1).lines()).containsExactly("total-tx");
    }

    @Test
    @DisplayName("veri: raporun ayıyla biten 12 ay (yıl dönümü dahil); boş ay null; kendi ayı güncel değerlerden; tür değişmiş alan karışmaz")
    void build() {
        Values cur = TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", 0.42, "total-tx", 1000)), TR);
        // geçen yıl Aralık (pencere içi), Mayıs 2025 (pencere dışı), Ağustos 2026 (önceki ay)
        TechErrorReport dec = report(2, 2025, 12, TR, Map.of("error-rate", 0.5, "total-tx", 900));
        TechErrorReport old = report(3, 2025, 5, TR, Map.of("error-rate", 9.9));
        TechErrorReport aug = report(4, 2026, 8, TR, Map.of("error-rate", 0.3));
        // Temmuz 2026: aynı kimlik ama türü değişmiş (yüzde → metin) bir şablon — değer alınmaz
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TR));
        String retyped = TechErrorTemplate.toJson(TR).replace("\"id\":\"error-rate\",\"label\":\"Teknik hata oranı\",\"type\":\"percent\"",
                "\"id\":\"error-rate\",\"label\":\"Teknik hata oranı\",\"type\":\"text\"");
        assertThat(retyped).isNotEqualTo(TechErrorTemplate.toJson(TR));
        TechErrorReport jul = new TechErrorReport();
        jul.setId(5L);
        jul.setReportYear(2026);
        jul.setReportMonth(7);
        jul.setTemplateJson(retyped);
        jul.setValuesJson("{\"fields\":{\"error-rate\":\"yüksek\",\"total-tx\":800}}");
        TechErrorTrend t = TechErrorTrend.build(TR, cur, 2026, 9, List.of(dec, old, aug, jul));
        assertThat(t.months()).hasSize(12).first().isEqualTo(YearMonth.of(2025, 10));
        assertThat(t.months().get(11)).isEqualTo(YearMonth.of(2026, 9));
        List<Double> rate = t.series("error-rate");
        assertThat(rate.get(2)).isEqualTo(0.5);                      // Aralık 2025
        assertThat(rate.get(9)).isNull();                            // Temmuz: türü değişmiş
        assertThat(rate.get(10)).isEqualTo(0.3);                     // Ağustos
        assertThat(rate.get(11)).isEqualTo(0.42);                    // kendi ayı
        assertThat(rate.get(0)).isNull();                            // raporu olmayan ay boş, sıfır değil
        assertThat(t.points("error-rate")).isEqualTo(3);
        assertThat(t.series("total-tx").get(9)).isEqualTo(800.0);    // Temmuz'un sayı alanı aynı türde
        assertThat(t.series("total-tx")).doesNotContain(9.9);
        // trend bölümü olmayan şablon: veri yok, sorgu gerekmez
        Map<String, Object> noTrend = new LinkedHashMap<>(TechErrorTemplate.toMap(TR));
        @SuppressWarnings("unchecked") List<Map<String, Object>> secs = new ArrayList<>((List<Map<String, Object>>) noTrend.get("sections"));
        secs.removeIf(x -> "trend".equals(x.get("kind")));
        noTrend.put("sections", secs);
        Template plain = TechErrorTemplate.normalize(noTrend);
        assertThat(TechErrorTrend.referencedFields(plain)).isEmpty();
        assertThat(TechErrorTrend.build(plain, cur, 2026, 9, List.of(dec)).isEmpty()).isTrue();
        assertThat(raw).isNotEmpty();
    }

    @Test
    @DisplayName("muaf alan (\"Hayır\" ise sorma) trendde boş; ocak penceresi bir önceki yılın şubatından başlar")
    void exemptAndWindow() {
        Values cur = TechErrorValues.normalize(Map.of("fields", Map.of("had-outage", false, "error-rate", 1)), TR);
        TechErrorTrend t = TechErrorTrend.build(TechErrorTemplate.normalize(withTrend(TR, List.of("error-rate-excl", "error-rate"))),
                cur, 2027, 1, List.of());
        assertThat(t.series("error-rate-excl").get(11)).isNull();
        assertThat(TechErrorTrend.window(2027, 1)).first().isEqualTo(YearMonth.of(2026, 2));
    }

    @Test
    @DisplayName("çubuk etiketi: yüzde kısa; sayı iki dilde de Qlik k / M / G / T (2026-10-18); 100 altı tek ondalık; 1000'e yuvarlanan üst birime geçer")
    void compactLabels() {
        assertThat(TechErrorFormat.compact(0.42, "percent", false)).isEqualTo("%0,42");
        assertThat(TechErrorFormat.compact(0.42, "percent", true)).isEqualTo("0.42%");
        // Yüzde: en çok iki ondalık, 0,1'in altında iki anlamlı basamak (en çok altı) — küçük oran "%0" olmaz
        assertThat(TechErrorFormat.compact(0.4235, "percent", false)).isEqualTo("%0,42");
        assertThat(TechErrorFormat.compact(0.0423, "percent", false)).isEqualTo("%0,042");
        assertThat(TechErrorFormat.compact(0.00423, "percent", false)).isEqualTo("%0,0042");
        assertThat(TechErrorFormat.compact(0.00004, "percent", false)).isEqualTo("%0,00004");
        assertThat(TechErrorFormat.compact(0.000123, "percent", true)).isEqualTo("0.00012%");
        assertThat(TechErrorFormat.compact(0.0995, "percent", false)).isEqualTo("%0,1");
        assertThat(TechErrorFormat.compact(0.000042, "percent", false)).isEqualTo("%0,000042");
        assertThat(TechErrorFormat.compact(-0.000042, "percent", false)).isEqualTo("%-0,000042");
        assertThat(TechErrorFormat.compact(0.00995, "percent", false)).isEqualTo("%0,01");
        assertThat(TechErrorFormat.compact(0.000995, "percent", false)).isEqualTo("%0,001");
        assertThat(TechErrorFormat.compact(99.995, "percent", false)).isEqualTo("%100");
        assertThat(TechErrorFormat.compact(-1.255, "percent", true)).isEqualTo("-1.26%");
        assertThat(TechErrorFormat.compact(0, "percent", false)).isEqualTo("%0");
        // Birim sınırı: 999,5 ve üstü yuvarlanınca 1000 olur → bir üst birim (inceleme 2026-10-15)
        assertThat(TechErrorFormat.compact(999_950, "number", false)).isEqualTo("1M");
        assertThat(TechErrorFormat.compact(999_950, "number", true)).isEqualTo("1M");
        assertThat(TechErrorFormat.compact(999_499, "number", false)).isEqualTo("999k");
        assertThat(TechErrorFormat.compact(999_999_500d, "number", false)).isEqualTo("1G");
        assertThat(TechErrorFormat.compact(1_000, "number", false)).isEqualTo("1k");
        assertThat(TechErrorFormat.compact(99_950, "number", false)).isEqualTo("100k");
        assertThat(TechErrorFormat.compact(987, "number", false)).isEqualTo("987");
        assertThat(TechErrorFormat.compact(12_345, "number", false)).isEqualTo("12,3k");
        assertThat(TechErrorFormat.compact(125_430_112, "number", false)).isEqualTo("125M");
        assertThat(TechErrorFormat.compact(1_250_000, "number", true)).isEqualTo("1.3M");
        assertThat(TechErrorFormat.compact(2_400_000_000d, "number", false)).isEqualTo("2,4G");
        assertThat(TechErrorFormat.compact(-12_500, "number", true)).isEqualTo("-12.5k");
        assertThat(TechErrorFormat.compact(3_200_000_000_000d, "number", false)).isEqualTo("3,2T");
        assertThat(TechErrorFormat.compact(999_999_500_000d, "number", true)).isEqualTo("1T");
    }

    private static void assertInvalid(Map<String, Object> raw) {
        assertThatThrownBy(() -> TechErrorTemplate.normalize(raw)).isInstanceOf(FieldValidationException.class);
    }
}
