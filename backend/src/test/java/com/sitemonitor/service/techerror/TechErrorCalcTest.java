package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.techerror.TechErrorCalc.Result;
import com.sitemonitor.service.techerror.TechErrorCalc.Status;
import com.sitemonitor.service.techerror.TechErrorTemplate.Calc;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
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
 * Otomatik hesaplanan oranlar (2026-10-16, kullanıcı isteği: "kullanıcı adetleri girer, yüzdeleri sistem hesaplar").
 * {@link #TABLE} istemcinin aynası ({@code techErrorModel.calcResult}) için de TEK doğruluk tablosu: JS testi bu
 * dosyadan okur — iki taraf ayrışamaz.
 */
class TechErrorCalcTest {

    /**
     * { formül, şablondaki başvuru alanları (kimlik=tür), bu ayın değerleri, geçen ay ("-" = rapor yok), durum, değer }.
     * Formül: {@code ratio:a/b} = a ÷ b × 100, {@code ratio:a-c/b} = (a − c) ÷ b × 100, {@code change:a}, {@code increase:a}.
     */
    static final String[][] TABLE = {
            { "ratio:a/b", "a=number,b=number", "a=5,b=200", "-", "OK", "2.5" },
            { "ratio:a-c/b", "a=number,b=number,c=number", "a=50,b=1000,c=10", "-", "OK", "4" },
            { "ratio:a/b", "a=number,b=number", "a=1,b=3", "-", "OK", "33.33" },
            { "ratio:a/b", "a=number,b=number", "a=2.675,b=100", "-", "OK", "2.68" },
            { "ratio:a/b", "a=number,b=number", "a=8529,b=1234567", "-", "OK", "0.69" },
            // inceleme 2026-10-16: 59 / 4000 × 100 = 1,4749999999999999 (kısa gösterim) → 1,47 — ekran da 1,47 (1,48 değil)
            { "ratio:a/b", "a=number,b=number", "a=59,b=4000", "-", "OK", "1.47" },
            // kullanıcı kararı 2026-10-16: 0,01'in altı 2 anlamlı hane (en çok 6 ondalık) — sahte %0 yok
            { "ratio:a/b", "a=number,b=number", "a=28,b=1000000", "-", "OK", "0.0028" },
            { "ratio:a/b", "a=number,b=number", "a=405,b=10000000", "-", "OK", "0.0041" },
            { "ratio:a/b", "a=number,b=number", "a=1,b=1000000000", "-", "OK", "0" },
            { "ratio:a/b", "a=number,b=number", "a=0,b=100", "-", "OK", "0" },
            { "ratio:a/b", "a=number,b=number", "a=5250,b=1250000", "-", "OK", "0.42" },
            { "ratio:a/b", "a=number,b=number", "a=5", "-", "MISSING", "" },
            { "ratio:a-c/b", "a=number,b=number,c=number", "a=50,b=1000", "-", "MISSING", "" },
            { "ratio:a/b", "a=number,b=number", "a=5,b=0", "-", "ZERO", "" },
            { "ratio:a-c/b", "a=number,b=number,c=number", "a=5,b=100,c=10", "-", "NEGATIVE", "" },
            { "ratio:a/b", "a=percent,b=number", "a=5,b=100", "-", "BAD_REF", "" },
            { "ratio:a/b", "b=number", "b=100", "-", "BAD_REF", "" },
            { "ratio:a/b", "a=number,b=number", "a=1000000000000000,b=1", "-", "RANGE", "" },
            { "change:a", "a=number", "a=110", "a=100", "OK", "10" },
            { "change:a", "a=number", "a=90", "a=100", "OK", "10" },
            { "change:a", "a=number", "a=1250000", "a=1000000", "OK", "25" },
            { "change:a", "a=number", "a=110", "-", "MANUAL", "" },
            { "change:a", "a=number", "a=110", "", "MANUAL", "" },
            { "change:a", "a=number", "", "a=100", "MISSING", "" },
            { "change:a", "a=number", "a=5", "a=0", "ZERO", "" },
            { "change:a", "a=percent", "a=5", "a=4", "BAD_REF", "" },
            { "increase:a", "a=number", "a=110", "a=100", "OK", "true" },
            { "increase:a", "a=number", "a=90", "a=100", "OK", "false" },
            { "increase:a", "a=number", "a=100", "a=100", "EQUAL", "" },
            { "increase:a", "a=number", "a=100", "-", "MANUAL", "" },
            { "increase:a", "a=number", "", "a=100", "MISSING", "" },
            { "increase:a", "a=number", "a=5", "a=0", "OK", "true" },
    };

    static Calc calcOf(String spec) {
        String[] p = spec.split(":", 2);
        if (!"ratio".equals(p[0])) return new Calc(p[0], null, null, null, p[1]);
        String[] nd = p[1].split("/", 2);
        String[] num = nd[0].split("-", 2);
        return Calc.ratio(num[0], num.length > 1 ? num[1] : null, nd[1]);
    }

    static Map<String, Object> numbers(String spec) {
        if ("-".equals(spec)) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        if (spec.isEmpty()) return m;
        for (String kv : spec.split(",")) {
            String[] p = kv.split("=", 2);
            m.put(p[0], Double.parseDouble(p[1]));
        }
        return m;
    }

    @Test
    @DisplayName("doğruluk tablosu: oran, düşülen sayı, bölen 0, eksik girdi, geçersiz başvuru, değişim ve artış (geçen ay yoksa elle)")
    void truthTable() {
        assertThat(TABLE.length).isGreaterThanOrEqualTo(20);
        for (String[] row : TABLE) {
            Calc c = calcOf(row[0]);
            Map<String, Field> byId = new HashMap<>();
            for (String kv : row[1].split(",")) {
                String[] p = kv.split("=", 2);
                byId.put(p[0], new Field(p[0], p[0], p[1], null));
            }
            Field f = new Field("x", "x", "increase".equals(c.kind()) ? "yesno" : "percent", null, null, c);
            byId.put("x", f);
            Result r = TechErrorCalc.result(f, byId, numbers(row[2]), numbers(row[3]));
            assertThat(r.status()).as(String.join(" | ", row)).isEqualTo(Status.valueOf(row[4]));
            if (row[5].isEmpty()) {
                assertThat(r.value()).as(String.join(" | ", row)).isNull();
            } else if ("true".equals(row[5]) || "false".equals(row[5])) {
                assertThat(r.value()).as(String.join(" | ", row)).isEqualTo(Boolean.parseBoolean(row[5]));
            } else {
                assertThat(TechErrorValues.compact((Double) r.value()).doubleValue()).as(String.join(" | ", row))
                        .isEqualTo(Double.parseDouble(row[5]));
            }
        }
    }

    static Template tpl() {
        return TechErrorTemplate.defaults("tr");
    }

    @Test
    @DisplayName("uygulama (varsayılan şablon): oranlar adetlerden; istemcinin gönderdiği oran yok sayılır; eksik girdide oran boş")
    void applyOnDefaults() {
        Template t = tpl();
        Map<String, Object> f = new HashMap<>();
        f.put("total-tx", 1250000);
        f.put("error-count", 5250);
        f.put("unmapped-count", 120);
        f.put("quota-count", 80);
        f.put("had-outage", true);
        f.put("outage-errors", 250);
        f.put("error-rate", 9.9);                                    // istemci yanlış gönderse de sunucu hesaplar
        f.put("sla-rate", 99.5);                                     // elle girilen yüzde aynen kalır
        Values v = TechErrorCalc.apply(t, TechErrorValues.normalize(Map.of("fields", f), t), Map.of("total-tx", 1000000L));
        assertThat(v.fields()).containsEntry("error-rate", 0.42)
                .containsEntry("error-rate-excl", 0.4)
                .containsEntry("unmapped-rate", 2.29)
                .containsEntry("quota-rate", 1.52)
                .containsEntry("outage-share", 4.76)
                .containsEntry("tx-increase", true)
                .containsEntry("tx-increase-pct", 25L)
                .containsEntry("sla-rate", 99.5);
        // Teknik hata adedi silinince ona bağlı oranlar BOŞ (eski hesap kalmaz)
        f.remove("error-count");
        Values v2 = TechErrorCalc.apply(t, TechErrorValues.normalize(Map.of("fields", f), t), Map.of("total-tx", 1000000L));
        assertThat(v2.fields()).doesNotContainKeys("error-rate", "error-rate-excl", "unmapped-rate", "quota-rate", "outage-share")
                .containsEntry("tx-increase-pct", 25L);
    }

    @Test
    @DisplayName("uygulama: geçen ayın raporu yoksa değişim ve artış ELLE (girilen değer kalır); 'kesinti yok' cevabında hariç oran muaf")
    void applyManualAndExempt() {
        Template t = tpl();
        Map<String, Object> f = new HashMap<>();
        f.put("total-tx", 1000);
        f.put("error-count", 10);
        f.put("tx-increase", false);
        f.put("tx-increase-pct", 3.5);
        f.put("had-outage", false);
        f.put("outage-errors", 4);                                   // muaf: saklanmaz
        Values v = TechErrorCalc.apply(t, TechErrorValues.normalize(Map.of("fields", f), t), null);
        assertThat(v.fields()).containsEntry("tx-increase", false).containsEntry("tx-increase-pct", 3.5)
                .containsEntry("error-rate", 1L)
                .doesNotContainKeys("outage-errors", "error-rate-excl", "outage-share");
        // Geçen ay rapor var ama toplam işlem girilmemiş → yine elle
        Values v2 = TechErrorCalc.apply(t, TechErrorValues.normalize(Map.of("fields", f), t), Map.of("error-count", 5L));
        assertThat(v2.fields()).containsEntry("tx-increase-pct", 3.5);
        // Değişim yoksa (aynı adet) artış boş, değişim %0
        Values v3 = TechErrorCalc.apply(t, TechErrorValues.normalize(Map.of("fields", f), t), Map.of("total-tx", 1000L));
        assertThat(v3.fields()).doesNotContainKey("tx-increase").containsEntry("tx-increase-pct", 0L);
    }

    @Test
    @DisplayName("uygulama: formülü kullanılamayan alan (başvurduğu alan şablondan silinmiş) elle girilir — değer korunur")
    void applyBadRefKeepsManual() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(Map.of("id", "m", "kind", "metrics", "title", "G", "fields", List.of(
                Map.of("id", "total", "label", "Toplam", "type", "number"),
                Map.of("id", "rate", "label", "Oran", "type", "percent", "calc",
                        Map.of("kind", "ratio", "num", "silinmis", "den", "total"))))));
        Template t = TechErrorTemplate.normalize(raw);       // başvuru kayıtta reddedilmez
        assertThat(t.allFields().get(1).calc().num()).isEqualTo("silinmis");
        Values v = TechErrorCalc.apply(t, TechErrorValues.normalize(Map.of("fields", Map.of("total", 10, "rate", 7.5)), t), null);
        assertThat(v.fields()).containsEntry("rate", 7.5);
    }

    static Map<String, Object> oneField(Map<String, Object> field) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        List<Object> fields = new ArrayList<>();
        fields.add(Map.of("id", "a", "label", "A", "type", "number"));
        fields.add(Map.of("id", "b", "label", "B", "type", "number"));
        fields.add(field);
        raw.put("sections", List.of(Map.of("id", "m", "kind", "metrics", "title", "G", "fields", fields)));
        return raw;
    }

    @Test
    @DisplayName("şablon: hesap biçimce denetlenir (tür, alan türü, zorunlu alan, kendine başvuru, kimlik); saklıda geçersiz hesap düşer")
    void templateValidation() {
        Map<String, Object> ok = oneField(Map.of("id", "r", "label", "R", "type", "percent",
                "calc", Map.of("kind", "ratio", "num", "a", "den", "b")));
        Template t = TechErrorTemplate.normalize(ok);
        assertThat(t.allFields().get(2).calc()).isEqualTo(Calc.ratio("a", null, "b"));
        // Gidiş-dönüş: JSON'da yalnız dolu anahtarlar
        assertThat(TechErrorTemplate.toJson(t)).contains("\"calc\":{\"kind\":\"ratio\",\"num\":\"a\",\"den\":\"b\"}");
        assertThat(TechErrorTemplate.fromStored(TechErrorTemplate.toJson(t)).allFields().get(2).calc()).isEqualTo(Calc.ratio("a", null, "b"));
        // Boş tür = elle
        assertThat(TechErrorTemplate.normalize(oneField(Map.of("id", "r", "label", "R", "type", "percent",
                "calc", Map.of("kind", "")))).allFields().get(2).calc()).isNull();

        List<Map<String, Object>> bad = List.of(
                Map.of("id", "r", "label", "R", "type", "number", "calc", Map.of("kind", "ratio", "num", "a", "den", "b")),
                Map.of("id", "r", "label", "R", "type", "percent", "calc", Map.of("kind", "increase", "of", "a")),
                Map.of("id", "r", "label", "R", "type", "yesno", "calc", Map.of("kind", "change", "of", "a")),
                Map.of("id", "r", "label", "R", "type", "percent", "calc", Map.of("kind", "ratio", "num", "a")),
                Map.of("id", "r", "label", "R", "type", "percent", "calc", Map.of("kind", "ratio", "num", "r", "den", "b")),
                Map.of("id", "r", "label", "R", "type", "percent", "calc", Map.of("kind", "ratio", "num", "A B", "den", "b")),
                Map.of("id", "r", "label", "R", "type", "percent", "calc", Map.of("kind", "sum", "num", "a", "den", "b")),
                Map.of("id", "r", "label", "R", "type", "percent", "calc", "ratio"));
        for (Map<String, Object> field : bad) {
            assertThatThrownBy(() -> TechErrorTemplate.normalize(oneField(field))).as(field.toString())
                    .isInstanceOf(FieldValidationException.class);
            // Saklı şablon hoşgörülü: alan kalır, hesap düşer (alan elle girilir)
            Template stored = TechErrorTemplate.fromStored(TechErrorTemplate.JSON.writeValueAsString(oneField(field)));
            assertThat(stored.allFields()).extracting(Field::id).contains("r");
            assertThat(stored.allFields().stream().filter(x -> "r".equals(x.id())).findFirst().orElseThrow().calc()).isNull();
        }
    }

    @Test
    @DisplayName("inceleme 2026-10-16: otomatik hesaplanan evet/hayır sorusu 'Hayır ise sorma' hedefi olamaz (katı 400, saklıda bağ düşer)")
    void computedQuestionIsNotASkipTarget() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(Map.of("id", "m", "kind", "metrics", "title", "G", "fields", List.of(
                Map.of("id", "a", "label", "A", "type", "number"),
                Map.of("id", "q", "label", "Artış?", "type", "yesno", "calc", Map.of("kind", "increase", "of", "a")),
                Map.of("id", "d", "label", "Bağlı", "type", "number", "skip_if_no", "q")))));
        assertThatThrownBy(() -> TechErrorTemplate.normalize(raw)).isInstanceOf(FieldValidationException.class);
        Template stored = TechErrorTemplate.fromStored(TechErrorTemplate.JSON.writeValueAsString(raw));
        assertThat(stored.allFields().get(2).skipIfNo()).isNull();
        assertThat(stored.allFields().get(1).calc()).isNotNull();
    }

    @Test
    @DisplayName("varsayılan şablon: her hesaplanan alanın başvuruları şablonda hesaplanmayan sayı alanı (formül kullanılabilir)")
    void defaultsAreUsable() {
        for (String lang : List.of("tr", "en")) {
            Template t = TechErrorTemplate.defaults(lang);
            Map<String, Field> byId = new HashMap<>();
            for (Field f : t.allFields()) byId.put(f.id(), f);
            List<Field> calcs = t.allFields().stream().filter(f -> f.calc() != null).toList();
            assertThat(calcs).extracting(Field::id).containsExactly("error-rate", "error-rate-excl", "unmapped-rate", "quota-rate",
                    "outage-share", "tx-increase", "tx-increase-pct");
            for (Field f : calcs) {
                assertThat(f.calc().refs()).as(lang + " " + f.id()).allMatch(r -> TechErrorCalc.input(byId.get(r)));
            }
            // Özet cümleleri yeni oranlara bağlı
            String lines = String.join("\n", t.sections().get(0).lines());
            assertThat(lines).contains("{f:unmapped-rate}").contains("{f:quota-rate}");
        }
    }

    @Test
    @DisplayName("2026-10-16: küçük yüzde 2 anlamlı haneyle gösterilir (en çok 6 ondalık) — sahte %0 yok; istemci formatPercent aynı")
    void smallPercentDisplay() {
        assertThat(TechErrorFormat.percent(0.0028, false)).isEqualTo("%0,0028");
        assertThat(TechErrorFormat.percent(0.00004, true)).isEqualTo("0.00004%");
        assertThat(TechErrorFormat.percent(0.0000025, false)).isEqualTo("%0,000003");
        assertThat(TechErrorFormat.percent(0.42, false)).isEqualTo("%0,42");
        assertThat(TechErrorFormat.percent(0, false)).isEqualTo("%0");
        assertThat(TechErrorCalc.roundRate(59 / 4000.0 * 100)).isEqualTo(1.47);
        assertThat(TechErrorCalc.roundRate(0.00405)).isEqualTo(0.0041);
        assertThat(TechErrorCalc.roundRate(1e-7)).isZero();
    }
}
