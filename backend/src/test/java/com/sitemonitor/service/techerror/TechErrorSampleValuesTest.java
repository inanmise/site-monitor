package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Şablon önizlemesinin örnek değerleri (2026-10-17, R19): her alan türü değer alır, oranlar örnek adetlerden HESAPLANIR
 * (tutarlı), hiçbir alan muaf kalmaz, aynı şablon → aynı değerler, geçen ay farkları görünür.
 */
class TechErrorSampleValuesTest {

    private static Map<String, Object> field(String id, String type) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("id", id);
        f.put("label", id);
        f.put("type", type);
        return f;
    }

    /** Her alan / bölüm türünü içeren şablon: kısa metin, tablo (grup satırı + üç sütun türü), anomali, döngüsel oranlar. */
    private static Template custom() {
        Map<String, Object> ratioXY = field("ratio-xy", "percent");
        ratioXY.put("calc", Map.of("kind", "ratio", "num", "x", "den", "y"));
        Map<String, Object> ratioYX = field("ratio-yx", "percent");
        ratioYX.put("calc", Map.of("kind", "ratio", "num", "y", "den", "x"));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal} Önizleme");
        raw.put("sections", List.of(
                Map.of("id", "m", "kind", "metrics", "title", "Göstergeler", "fields", List.of(
                        field("x", "number"), field("y", "number"), ratioXY, ratioYX,
                        field("short", "text"), field("long", "textarea"), field("q", "yesno"), field("p", "percent"))),
                Map.of("id", "iss", "kind", "issues", "title", "Konular"),
                Map.of("id", "tb", "kind", "table", "title", "Tablo",
                        "columns", List.of(Map.of("id", "n", "label", "Adet", "type", "number"),
                                Map.of("id", "pc", "label", "Oran", "type", "percent"),
                                Map.of("id", "tx", "label", "Not", "type", "text")),
                        "rows", List.of(Map.of("id", "g", "label", "Grup", "group", true), Map.of("id", "r", "label", "Satır")))));
        return TechErrorTemplate.normalize(raw);
    }

    @Test
    @DisplayName("varsayılan şablon: her alan bir değer alır (girilen + hesaplanan), hiçbir alan muaf değil, kök (toplam işlem) ≥ 1 000 000")
    void everyFieldGetsAValue() {
        for (String lang : List.of("tr", "en")) {
            Template t = TechErrorTemplate.defaults(lang);
            TechErrorSampleValues.Sample s = TechErrorSampleValues.build(t, lang);
            Map<String, Object> f = s.values().fields();
            for (Field x : t.allFields()) {
                assertThat(f).as("%s: %s", lang, x.id()).containsKey(x.id());
                assertThat(TechErrorTemplate.exempt(x, f)).as("muaf olmamalı: " + x.id()).isFalse();
            }
            assertThat(((Number) f.get("total-tx")).longValue()).isBetween(1_000_000L, 1_499_000L);
            assertThat(f.get("had-outage")).isEqualTo(Boolean.TRUE);           // "Hayır cevabında sorma" bağlıları görünsün
            assertThat(f.get("unmapped-note").toString()).contains("**");      // iki maddelik zengin metin
            assertThat(s.values().issues().get("issues")).hasSize(1);
            assertThat(s.values().issues().get("issues").get(0).topic()).isEqualTo("en".equals(lang) ? "Sample topic" : "Örnek konu");
        }
    }

    @Test
    @DisplayName("oranlar örnek adetlerden TechErrorCalc ile hesaplanır ve tutarlıdır: pay ≤ payda / 4, düşülen ≤ pay / 2, oran 0–25; SLA hedefi tutturulmuş; değişim / artış geçen aya göre")
    void ratesAreConsistentWithCounts() {
        Template t = TechErrorTemplate.defaults("tr");
        TechErrorSampleValues.Sample s = TechErrorSampleValues.build(t, "tr");
        Map<String, Object> f = s.values().fields();
        Map<String, Field> byId = new HashMap<>();
        for (Field x : t.allFields()) byId.put(x.id(), x);
        for (Field x : t.allFields()) {
            if (x.calc() == null) continue;
            TechErrorCalc.Result r = TechErrorCalc.result(x, byId, f, s.previous());
            assertThat(r.status()).as(x.id()).isEqualTo(TechErrorCalc.Status.OK);
            if ("ratio".equals(x.calc().kind())) {
                double v = ((Number) f.get(x.id())).doubleValue();
                assertThat(v).as(x.id()).isEqualTo(((Number) r.value()).doubleValue()).isBetween(0.0, 25.0);
            }
        }
        long errors = ((Number) f.get("error-count")).longValue();
        assertThat(((Number) f.get("unmapped-count")).longValue()).isLessThanOrEqualTo(errors / 4);
        assertThat(((Number) f.get("outage-errors")).longValue()).isLessThanOrEqualTo(errors / 4);
        assertThat(((Number) f.get("error-count")).longValue()).isLessThanOrEqualTo(((Number) f.get("total-tx")).longValue() / 4);
        // Aynı paydaya bağlı adetler birbirinin aynısı değil
        assertThat(f.get("unmapped-count")).isNotEqualTo(f.get("quota-count"));
        assertThat(((Number) f.get("sla-rate")).doubleValue()).isGreaterThanOrEqualTo(99.95);
        assertThat(f.get("sla-above")).isEqualTo(Boolean.TRUE);
        // Geçen ay daha düşük: işlem adedi arttı, değişim oranı 2–8 %'nin karşılığı
        long prevTx = ((Number) s.previous().get("total-tx")).longValue();
        assertThat(prevTx).isLessThan(((Number) f.get("total-tx")).longValue());
        assertThat(f.get("tx-increase")).isEqualTo(Boolean.TRUE);
        assertThat(((Number) f.get("tx-increase-pct")).doubleValue()).isBetween(2.0, 9.0);
        assertThat(s.previous()).containsKey("error-rate");                 // geçen ayın oranı da hesaplı ({d:error-rate})
    }

    @Test
    @DisplayName("her tür: kısa metin, uzun açıklama, evet, yüzde; tablo hücreleri sütun türüne göre (grup satırı dahil); anomali konusu; döngüsel oranlar sonlu (≥ 1)")
    void customTemplateAllKinds() {
        Template t = custom();
        TechErrorSampleValues.Sample s = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> TechErrorSampleValues.build(t, "tr"));
        Map<String, Object> f = s.values().fields();
        assertThat(f.get("short")).isEqualTo("Örnek metin");
        assertThat(f.get("long").toString()).startsWith("- ");
        assertThat(f.get("q")).isEqualTo(Boolean.TRUE);
        assertThat(((Number) f.get("p")).doubleValue()).isBetween(0.1, 0.99);
        assertThat(((Number) f.get("x")).longValue()).isPositive();
        assertThat(((Number) f.get("y")).longValue()).isPositive();
        assertThat(f).containsKeys("ratio-xy", "ratio-yx");
        for (String row : List.of("g", "r")) {
            assertThat(s.values().cell("tb", row, "n")).isInstanceOf(Number.class);
            assertThat(((Number) s.values().cell("tb", row, "pc")).doubleValue()).isBetween(95.0, 100.0);
            assertThat(s.values().cell("tb", row, "tx")).isEqualTo("Örnek");
        }
        var issue = s.values().issues().get("iss").get(0);
        assertThat(issue.displayTitle("{konu}: Hata Artışı")).isEqualTo("Örnek konu: Hata Artışı");
        assertThat(issue.customers().longValue()).isEqualTo(1_250L);
        assertThat(issue.errors().longValue()).isEqualTo(3_400L);
        // EN örnek metinleri
        assertThat(TechErrorSampleValues.build(t, "en").values().fields().get("short")).isEqualTo("Sample text");
    }

    @Test
    @DisplayName("R19 inceleme: formülü sonuç vermeyen hesaplı alan da dolu — eksik başvurulu oran ve hedefsiz yüzdeyle karşılaştırma \"—\" basılmaz (yüzde örnek değer, evet / hayır \"Evet\"); geçen ay da dolu")
    void brokenFormulasStillGetSampleValues() {
        Map<String, Object> broken = field("broken-rate", "percent");
        broken.put("calc", Map.of("kind", "ratio", "num", "ghost", "den", "x"));        // "ghost" şablonda yok → BAD_REF
        Map<String, Object> cmp = field("cmp", "yesno");
        cmp.put("calc", Map.of("kind", "compare", "of", "p"));                          // p'nin hedefi yok → BAD_REF
        Map<String, Object> zero = field("zero-rate", "percent");
        zero.put("calc", Map.of("kind", "ratio", "num", "x", "den", "x-missing"));      // payda yok
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal} Önizleme");
        raw.put("sections", List.of(Map.of("id", "m", "kind", "metrics", "title", "Göstergeler", "fields", List.of(
                field("x", "number"), field("p", "percent"), broken, cmp, zero))));
        Template t = TechErrorTemplate.normalize(raw);
        Map<String, Field> byId = new HashMap<>();
        for (Field x : t.allFields()) byId.put(x.id(), x);
        TechErrorSampleValues.Sample s = TechErrorSampleValues.build(t, "tr");
        Map<String, Object> f = s.values().fields();
        // Hesap gerçekten sonuç vermiyor (düzeltme olmasaydı alan boş kalırdı)
        assertThat(TechErrorCalc.result(byId.get("broken-rate"), byId, f, s.previous()).status()).isNotEqualTo(TechErrorCalc.Status.OK);
        assertThat(TechErrorCalc.result(byId.get("cmp"), byId, f, s.previous()).status()).isNotEqualTo(TechErrorCalc.Status.OK);
        for (String id : List.of("broken-rate", "zero-rate")) {
            assertThat(f.get(id)).as(id).isInstanceOf(Double.class);
            assertThat(((Number) f.get(id)).doubleValue()).as(id).isBetween(0.1, 0.99);
            assertThat(s.previous()).as("geçen ay " + id).containsKey(id);
        }
        assertThat(f.get("cmp")).isEqualTo(Boolean.TRUE);
        assertThat(s.previous().get("cmp")).isEqualTo(Boolean.TRUE);
        // Varsayılan şablonun çalışan hesapları DEĞİŞMEZ (dolgu yalnız boş kalan alana)
        Template d = TechErrorTemplate.defaults("tr");
        Map<String, Field> dById = new HashMap<>();
        for (Field x : d.allFields()) dById.put(x.id(), x);
        TechErrorSampleValues.Sample ds = TechErrorSampleValues.build(d, "tr");
        for (Field x : d.allFields()) {
            if (x.calc() == null) continue;
            TechErrorCalc.Result r = TechErrorCalc.result(x, dById, ds.values().fields(), ds.previous());
            assertThat(r.status()).as(x.id()).isEqualTo(TechErrorCalc.Status.OK);
        }
    }

    @Test
    @DisplayName("deterministik: aynı şablon iki kez → bayt bayt aynı değerler ve geçen ay (PDF kararlı)")
    void deterministic() {
        Template t = TechErrorTemplate.defaults("tr");
        TechErrorSampleValues.Sample a = TechErrorSampleValues.build(t, "tr");
        TechErrorSampleValues.Sample b = TechErrorSampleValues.build(TechErrorTemplate.defaults("tr"), "tr");
        assertThat(TechErrorValues.toJson(a.values())).isEqualTo(TechErrorValues.toJson(b.values()));
        assertThat(a.previous()).isEqualTo(b.previous());
    }

    @Test
    @DisplayName("önizleme girdisi: sample işaretli, görsel ve trend boş, kimlik satırı yok (Hazırlayan = isteyen), dönem verilen ay")
    void previewInput() {
        Template t = TechErrorTemplate.defaults("tr");
        TechErrorPdfInput in = TechErrorSampleValues.previewInput(t, "Mobil", "Dijital", "Ayşe Yılmaz", 2026, 9,
                Instant.parse("2026-10-17T09:00:00Z"));
        assertThat(in.sample()).isTrue();
        assertThat(in.images()).isEmpty();
        assertThat(in.trend().months()).isEmpty();
        assertThat(in.provenance()).isNull();
        assertThat(in.preparedBy()).isEqualTo("Ayşe Yılmaz");
        assertThat(in.previousOrNull()).isNotNull().containsKey("total-tx");
        assertThat(in.title()).isEqualTo("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026");
        // withoutImages / withPrevious işareti taşır (servis Rendered.meta için withoutImages çağırır)
        assertThat(in.withoutImages().sample()).isTrue();
        assertThat(in.withPrevious(Map.of()).sample()).isTrue();
    }
}
