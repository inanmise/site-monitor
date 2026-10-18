package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Calc;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorTiles.Tile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kısaltılmış sayı gösterimi ve sıfır işareti (2026-10-18, kullanıcı istekleri: "4.4G gibi bir giriş raporda da aynı
 * şekilde gözüksün" ve "0 adet gibi olursa 0 yanına yeşil yuvarlak tik"). Tablolar istemcideki
 * {@code techErrorNumberDisplay.test.js} tarafından bu KAYNAKTAN okunur — Java ve JS aynı sonucu üretmeli. Şablon iki tarafta
 * elle aynıdır ({@link #template()}).
 */
class TechErrorNumberDisplayTest {

    /** Satır: değer, üs, dil, beklenen ({@link TechErrorFormat#abbreviated}). */
    static final String[][] ABBREV_TABLE = {
            { "4400000000", "9", "tr", "4,4G" },
            { "4400000000", "9", "en", "4.4G" },
            { "7420000000", "9", "tr", "7,42G" },
            { "130100000", "6", "tr", "130,1M" },
            { "681600", "3", "tr", "681,6k" },
            { "681600", "3", "en", "681.6k" },
            { "1250000000000", "9", "tr", "1250G" },                  // harften önce gruplama YOK (inceleme 2026-10-18:
            { "1250000000000", "9", "en", "1250G" },                  // "1.250G" düzenleyicide 1,25 milyar okunurdu)
            { "4400000000", "6", "tr", "4400M" },
            { "4400000000", "6", "en", "4400M" },
            { "0", "3", "tr", "0" },                                   // sıfır kısaltılmaz (inceleme 2026-10-18: tikin
            { "0", "9", "en", "0" },                                   // yanında "0G" basılıyordu)
            { "0", "12", "tr", "0" },
            { "1234567.891", "3", "tr", "1234,567891k" },              // tam 6 ondalık sığar
            { "2.5", "3", "tr", "0,0025k" },
            { "999999999999999", "3", "en", "999999999999.999k" },      // kayan nokta bölmesi yok: basamaklar aynen
            { "1000000000000000", "12", "tr", "1000T" },
            { "4123456789", "6", "tr", "4123,456789M" },
            { "4123456789", "9", "tr", "4.123.456.789" },              // 6 ondalığa sığmaz: yuvarlamak değeri değiştirirdi → tam sayı
            { "4123456789", "9", "en", "4,123,456,789" },
            { "1234.5678", "3", "tr", "1.234,5678" },                  // 1,2345678k → tam sayı
            { "1500000", "12", "tr", "1.500.000" },                    // 0,0000015T → tam sayı
            { "2500000", "7", "tr", "2.500.000" },                     // geçersiz üs → tam sayı
    };

    /** Satır: değer (≥ 0), dil, beklenen ({@link TechErrorFormat#compactQlik} — kısaltmalı değerin farkı). */
    static final String[][] QLIK_TABLE = {
            { "50000000", "tr", "50M" },
            { "1234567", "tr", "1,23M" },
            { "1234567", "en", "1.23M" },
            { "999", "tr", "999" },
            { "999.996", "tr", "1k" },                 // yuvarlanınca 1000 → bir üst birim
            { "999999", "en", "1M" },
            { "12.5", "tr", "12,5" },
            { "0.005", "tr", "0,01" },
            { "1250000000", "en", "1.25G" },
            { "250000", "tr", "250k" },
            { "2000000000000000", "tr", "2000T" },                    // harften önce gruplama yok
    };

    /**
     * Sıfır işareti doğruluk tablosu ({@link TechErrorFormat#zeroOk}). Satır: alan türü, "daha iyi" yönü ("-" = yok), hesap
     * ("-" = yok, "ratio"), değer ("n:0" sayı, "s:0" metin, "b:false" evet/hayır, null = boş), beklenen.
     */
    static final String[][] ZERO_TABLE = {
            { "number", "-", "-", "n:0", "true" },
            { "number", "lower", "-", "n:0", "true" },
            { "number", "none", "-", "n:0", "true" },
            { "number", "higher", "-", "n:0", "false" },             // "başarılı işlem 0" iyi değildir
            { "number", "-", "-", "n:0.0", "true" },
            { "number", "-", "-", "n:5", "false" },
            { "number", "-", "-", null, "false" },                     // bilinmeyen 0 değildir
            { "number", "-", "-", "s:0", "false" },
            { "number", "-", "ratio", "n:0", "false" },              // otomatik hesaplanan alan hiç
            { "percent", "-", "-", "n:0", "false" },
            { "percent", "lower", "-", "n:0", "false" },
            { "yesno", "-", "-", "b:false", "false" },
            { "text", "-", "-", "s:0", "false" },
    };

    /**
     * Yalnız oran tabanı işaretlenmez ({@link TechErrorFormat#zeroOk(Template, Field, Object)}, inceleme 2026-10-18 kullanıcı
     * kararı). Şablon: satır = alan kimliği, tür, hesap ("-" yok; "ratio:pay:çıkarılan:payda", "change:alan", "increase:alan";
     * boş parça "-"). JS testinde aynı şablon BU tablodan kurulur.
     */
    static final String[][] RATE_BASE_FIELDS = {
            { "total", "number", "-" },
            { "count", "number", "-" },
            { "unmapped", "number", "-" },
            { "outage", "number", "-" },
            { "solo", "number", "-" },
            { "tracked", "number", "-" },
            { "base2", "number", "-" },
            { "rate", "percent", "ratio:count:-:total" },
            { "excl", "percent", "ratio:count:outage:total" },
            { "share", "percent", "ratio:unmapped:-:count" },
            { "other", "percent", "ratio:solo:-:base2" },
            { "growth", "percent", "change:total" },
            { "up", "yesno", "increase:tracked" },
    };

    /** Satır: alan kimliği, değer 0 iken işaret beklenir mi. */
    static final String[][] RATE_BASE_TABLE = {
            { "total", "false" },      // yalnız payda (+ değişimi izlenir): "toplam işlem 0" veri yok demektir
            { "count", "true" },       // bir oranın payı (başka oranın paydası olsa da sayılan sorun)
            { "unmapped", "true" },    // pay
            { "outage", "true" },      // çıkarılan
            { "solo", "true" },        // pay
            { "tracked", "true" },     // yalnız "increase"in of'u — taban değil
            { "base2", "false" },      // yalnız payda
    };

    /** Anomali adedi ({@link TechErrorFormat#zeroCount}). Satır: değer, beklenen. */
    static final String[][] COUNT_TABLE = {
            { "n:0", "true" }, { "n:0.0", "true" }, { "n:3", "false" }, { null, "false" }, { "s:0", "false" }, { "b:false", "false" },
    };

    /**
     * Özet cümlesi + kısaltma ({@link TechErrorSummary#line}). Satır: ad, kalıp, bu ayın değerleri ("alan=değer;…"), geçen ayın
     * değerleri ("-" = rapor yok), kısaltma üsleri ("alan=üs;…"), beklenen cümle. Kanal "Mobil", Eylül 2026, Türkçe.
     */
    static final String[][] UNIT_SUMMARY_TABLE = {
            { "kısaltmalı adet kısaltmasıyla", "{kanal} işlem {f:total}.", "total=4400000000", "-", "total=9", "Mobil işlem **4,4G**." },
            { "kısaltmasız adet tam (bayt bayt eski)", "{kanal} işlem {f:total}.", "total=4400000000", "-", "", "Mobil işlem **4.400.000.000**." },
            { "kısaltmalı adetin farkı kısa, yüzde kısmı aynı", "Fark {d:total}.", "total=4400000000", "total=4350000000", "total=9",
              "Fark **+50M (+%1,15)**." },
            { "kısaltmasız adetin farkı tam", "Fark {d:total}.", "total=4400000000", "total=4350000000", "",
              "Fark **+50.000.000 (+%1,15)**." },
            { "değişim yok düz", "Fark {d:total}.", "total=4400000000", "total=4400000000", "total=9", "Fark değişim yok." },
            { "yüzde alanında üs yok sayılır", "Oran {f:rate}.", "rate=0.42", "-", "rate=9", "Oran **%0,42**." },
            { "adet + düşüş farkı kısa", "{f:count} adet, {d:count}.", "count=1250000", "count=2000000", "count=6",
              "**1,25M** adet, **−750k (−%37,5)**." },
            { "kısaltmayla yazılan sıfır harfsiz (0G değil)", "{f:count} adet, {d:count}.", "count=0", "count=0", "count=9",
              "**0** adet, değişim yok." },
    };

    /**
     * Kutucuk + kısaltma + sıfır işareti ({@link TechErrorTiles#build}). Satır: ad, bu ay, geçen ay ("-" = yok), üsler,
     * ardından beklenen kutucuklar "kimlik|değer|fark|sıfır" ("-" = fark yok).
     */
    static final String[][] UNIT_TILES_TABLE = {
            { "kısaltmalı değer + kısa fark; sıfır adet işaretli; yüksek-iyi sıfır işaretsiz; yüzde işaretsiz",
              "total=4400000000;count=0;denied=0;rate=0.42", "total=4350000000;count=5", "total=9",
              "total|4,4G|+50M (+%1,15)|false",
              "count|0|−5 (−%100)|true",
              "denied|0|-|false",
              "rate|%0,42|-|false" },
            { "boş değer bilinmiyor (işaret yok); kısaltmayla yazılan sıfır harfsiz (0k değil) ve işaretli",
              "count=0", "-", "count=3",
              "total|—|-|false",
              "count|0|-|true",
              "denied|—|-|false",
              "rate|—|-|false" },
    };

    /** Özet (kutucuklar) + alanlar: adet (yönsüz), düşük-iyi adet, yüksek-iyi adet, yüzde. JS testinde aynısı. */
    static Template template() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(
                Map.of("id", "exec", "kind", "summary", "title", "Yönetici Özeti", "lines", List.of("{kanal}: {f:total}."),
                        "tiles", List.of("total", "count", "denied", "rate")),
                Map.of("id", "m", "kind", "metrics", "title", "Veriler", "fields", List.of(
                        Map.of("id", "total", "label", "Toplam işlem adedi", "type", "number"),
                        Map.of("id", "count", "label", "Teknik hata adedi", "type", "number", "better", "lower"),
                        Map.of("id", "denied", "label", "Başarılı işlem adedi", "type", "number", "better", "higher"),
                        Map.of("id", "rate", "label", "Teknik hata oranı", "type", "percent")))));
        return TechErrorTemplate.normalize(raw);
    }

    /** {@link #RATE_BASE_FIELDS} şablonu (tek metrik bölümü). */
    static Template rateBaseTemplate() {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (String[] row : RATE_BASE_FIELDS) {
            Map<String, Object> f = new LinkedHashMap<>(Map.of("id", row[0], "label", row[0], "type", row[1]));
            if (!"-".equals(row[2])) {
                String[] c = row[2].split(":");
                Map<String, Object> calc = new LinkedHashMap<>();
                calc.put("kind", c[0]);
                if ("ratio".equals(c[0])) {
                    calc.put("num", c[1]);
                    if (!"-".equals(c[2])) calc.put("minus", c[2]);
                    calc.put("den", c[3]);
                } else {
                    calc.put("of", c[1]);
                }
                f.put("calc", calc);
            }
            fields.add(f);
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(Map.of("id", "m", "kind", "metrics", "title", "Veriler", "fields", fields)));
        return TechErrorTemplate.normalize(raw);
    }

    static Object cellValue(String spec) {
        if (spec == null) return null;
        String raw = spec.substring(2);
        return switch (spec.charAt(0)) {
            case 'n' -> raw.contains(".") ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw);
            case 'b' -> (Object) Boolean.parseBoolean(raw);
            default -> raw;
        };
    }

    static Map<String, Object> values(String spec) {
        if ("-".equals(spec)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        if (spec.isEmpty()) return out;
        for (String part : spec.split(";")) {
            int eq = part.indexOf('=');
            String raw = part.substring(eq + 1);
            out.put(part.substring(0, eq), raw.contains(".") ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw));
        }
        return out;
    }

    static Map<String, Integer> units(String spec) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (spec.isEmpty()) return out;
        for (String part : spec.split(";")) {
            int eq = part.indexOf('=');
            out.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1)));
        }
        return out;
    }

    @Test
    @DisplayName("kısaltmalı gösterim: yazılan rakam aynen geri gelir, en çok 6 ondalık, gruplama, harf k / M / G / T, geçersiz üs tam")
    void abbreviatedTruthTable() {
        for (String[] row : ABBREV_TABLE) {
            double v = Double.parseDouble(row[0]);
            assertThat(TechErrorFormat.abbreviated(v, Integer.parseInt(row[1]), "en".equals(row[2])))
                    .as(String.join(" ", row)).isEqualTo(row[3]);
        }
    }

    @Test
    @DisplayName("kısa fark: büyüklüğe göre birim, en çok 2 ondalık, 1000'e yuvarlanan bir üst birime geçer")
    void compactQlikTruthTable() {
        for (String[] row : QLIK_TABLE) {
            assertThat(TechErrorFormat.compactQlik(Double.parseDouble(row[0]), "en".equals(row[1]))).as(String.join(" ", row)).isEqualTo(row[2]);
        }
    }

    @Test
    @DisplayName("sıfır işareti: yalnız sayı alanı, tam 0, yüksek-iyi değil, hesapsız; boş / metin / yüzde hiç")
    void zeroTruthTable() {
        for (String[] row : ZERO_TABLE) {
            String better = "-".equals(row[1]) ? null : row[1];
            Calc calc = "-".equals(row[2]) ? null : Calc.ratio("a", null, "b");
            Field f = new Field("x", "X", row[0], null, null, calc, null, better);
            assertThat(TechErrorFormat.zeroOk(f, cellValue(row[3]))).as(String.join(" ", java.util.Arrays.asList(row)))
                    .isEqualTo(Boolean.parseBoolean(row[4]));
        }
        for (String[] row : COUNT_TABLE) {
            assertThat(TechErrorFormat.zeroCount(cellValue(row[0]))).as(String.valueOf(row[0])).isEqualTo(Boolean.parseBoolean(row[1]));
        }
        assertThat(TechErrorFormat.zeroOk(null, 0L)).isFalse();
    }

    @Test
    @DisplayName("inceleme 2026-10-18: yalnız oran tabanı olan sayı alanı (payda, hiçbir oranın payı / çıkarılanı değil) sıfırda işaretlenmez")
    void rateBaseOnlyIsNotTicked() {
        Template t = rateBaseTemplate();
        Map<String, Field> byId = new HashMap<>();
        for (Field f : t.allFields()) byId.put(f.id(), f);
        assertThat(byId.get("rate").calc()).as("şablon hesabı okundu").isNotNull();
        for (String[] row : RATE_BASE_TABLE) {
            assertThat(TechErrorFormat.zeroOk(t, byId.get(row[0]), 0L)).as(row[0]).isEqualTo(Boolean.parseBoolean(row[1]));
            assertThat(TechErrorFormat.zeroOk(t, byId.get(row[0]), 5L)).as(row[0] + " = 5").isFalse();
        }
        // Şablonsuz çağrı alan kuralına düşer
        assertThat(TechErrorFormat.zeroOk(null, byId.get("total"), 0L)).isTrue();
        // Varsayılan şablon (TR + EN): toplam işlem işaretlenmez; hata / eşleme / kota / kesinti adedi işaretlenir
        for (String lang : List.of("tr", "en")) {
            Template d = TechErrorTemplate.defaults(lang);
            Map<String, Field> dd = new HashMap<>();
            for (Field f : d.allFields()) dd.put(f.id(), f);
            assertThat(TechErrorFormat.zeroOk(d, dd.get("total-tx"), 0L)).as(lang + " total-tx").isFalse();
            for (String id : List.of("error-count", "unmapped-count", "quota-count", "outage-errors")) {
                assertThat(TechErrorFormat.zeroOk(d, dd.get(id), 0L)).as(lang + " " + id).isTrue();
            }
        }
    }

    @Test
    @DisplayName("inceleme 2026-10-18: kısaltılabilir mi — kaydırılan değer en çok 6 ondalıkla tam; geçersiz üs / sonsuz hayır")
    void abbreviableRule() {
        assertThat(TechErrorFormat.abbreviable(4_400_000_000d, 9)).isTrue();
        assertThat(TechErrorFormat.abbreviable(4_123_456_789d, 9)).isFalse();
        assertThat(TechErrorFormat.abbreviable(4_123_456_789d, 6)).isTrue();
        assertThat(TechErrorFormat.abbreviable(0, 12)).isFalse();               // sıfırın büyüklüğü yok: her yerde "0"
        assertThat(TechErrorFormat.abbreviable(-0.0, 9)).isFalse();
        assertThat(TechErrorFormat.abbreviable(0.5, 3)).isTrue();               // sıfır olmayan küçük değer kısaltılır
        assertThat(TechErrorFormat.abbreviable(1_250_000_000_000d, 3)).isTrue();
        assertThat(TechErrorFormat.abbreviable(1.5, 6)).isFalse();
        assertThat(TechErrorFormat.abbreviable(4_400_000_000d, 7)).isFalse();
        assertThat(TechErrorFormat.abbreviable(Double.NaN, 3)).isFalse();
        assertThat(TechErrorFormat.ABBREV_MAX_DECIMALS).isEqualTo(6);
    }

    @Test
    @DisplayName("özet cümlesi: {f:} kısaltmasıyla, {d:} kısa fark; kısaltmasız değer bayt bayt eskisi gibi")
    void summaryWithUnits() {
        Template t = template();
        Map<String, Field> byId = new HashMap<>();
        for (Field f : t.allFields()) byId.put(f.id(), f);
        for (String[] row : UNIT_SUMMARY_TABLE) {
            String got = TechErrorSummary.line(row[1], byId, values(row[2]), values(row[3]), Map.of(), units(row[4]), "tr", "Mobil", 2026, 9);
            assertThat(got).as(row[0]).isEqualTo(row[5]);
            if (row[4].isEmpty()) {
                // Üssüz yol eski imzayla aynı
                assertThat(TechErrorSummary.line(row[1], byId, values(row[2]), values(row[3]), Map.of(), "tr", "Mobil", 2026, 9))
                        .as(row[0] + " (eski imza)").isEqualTo(got);
            }
        }
    }

    @Test
    @DisplayName("kutucuk: kısaltmalı değer ve kısa fark, sıfır adet işareti (yüksek-iyi ve yüzde hariç, boş değer hariç)")
    void tilesWithUnitsAndZero() {
        Template t = template();
        for (String[] row : UNIT_TILES_TABLE) {
            List<Tile> tiles = TechErrorTiles.build(t, t.sections().get(0), values(row[1]), Map.of(), values(row[2]), "Mobil", 2026, 9,
                    units(row[3]));
            List<String> shown = new ArrayList<>();
            for (Tile x : tiles) shown.add(String.join("|", x.id(), x.value(), x.delta() == null ? "-" : x.delta(), String.valueOf(x.zero())));
            List<String> expected = new ArrayList<>();
            for (int i = 4; i < row.length; i++) expected.add(row[i]);
            assertThat(shown).as(row[0]).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("değer biçimi: sayı alanı / tablo sayı sütunu / anomali adedi üssü kullanır; yüzde ve metin sütunu kullanmaz; null üs eski çıktı")
    void valueCellCountWithUnits() {
        Field number = new Field("n", "N", "number", null);
        Field pct = new Field("p", "P", "percent", null);
        assertThat(TechErrorFormat.value(number, 4_400_000_000L, "tr", 9)).isEqualTo("4,4G");
        assertThat(TechErrorFormat.value(number, 4_400_000_000L, "tr", null)).isEqualTo(TechErrorFormat.value(number, 4_400_000_000L, "tr"));
        assertThat(TechErrorFormat.value(pct, 0.42, "tr", 9)).isEqualTo("%0,42");
        assertThat(TechErrorFormat.value(number, null, "tr", 9)).isEqualTo(TechErrorFormat.EMPTY);
        var numCol = new TechErrorTemplate.Column("c", "C", "number");
        var pctCol = new TechErrorTemplate.Column("p", "P", "percent");
        var txtCol = new TechErrorTemplate.Column("t", "T", "text");
        assertThat(TechErrorFormat.cell(numCol, 1_250_000L, "en", 6)).isEqualTo("1.25M");
        assertThat(TechErrorFormat.cell(pctCol, 99.5, "en", 6)).isEqualTo("99.5%");
        assertThat(TechErrorFormat.cell(txtCol, "12k", "en", 3)).isEqualTo("12k");
        assertThat(TechErrorFormat.count(681_600L, "tr", 3)).isEqualTo("681,6k");
        assertThat(TechErrorFormat.count(681_600L, "tr", null)).isEqualTo("681.600");
        assertThat(TechErrorFormat.count(null, "tr", 3)).isEqualTo(TechErrorFormat.EMPTY);
        // Fark: üssüz çağrı eski imzayla aynı
        assertThat(TechErrorFormat.delta(number, 100L, 150L, "tr", null)).isEqualTo(TechErrorFormat.delta(number, 100L, 150L, "tr"));
        assertThat(TechErrorFormat.delta(pct, 0.5, 0.42, "tr", 9)).isEqualTo(TechErrorFormat.delta(pct, 0.5, 0.42, "tr"));
    }

    @Test
    @DisplayName("grafik etiketi: iki dilde de Qlik harfleri k / M / G / T")
    void compactUsesQlikLetters() {
        assertThat(TechErrorFormat.compact(12_345, "number", false)).isEqualTo("12,3k");
        assertThat(TechErrorFormat.compact(12_345, "number", true)).isEqualTo("12.3k");
        assertThat(TechErrorFormat.compact(2_400_000_000d, "number", true)).isEqualTo("2.4G");
        assertThat(TechErrorFormat.compact(3_200_000_000_000d, "number", false)).isEqualTo("3,2T");
        assertThat(TechErrorFormat.compact(999_999_500_000d, "number", false)).isEqualTo("1T");
    }
}
