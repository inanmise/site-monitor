package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Teknik hata raporu değerleri (2026-10-13): tür kuralları, şablona göre süzme, sınırlar, biçim. */
class TechErrorValuesTest {

    @Test
    @DisplayName("kesinti yok ('Hayır'): kesinti alanlarının değeri saklanmaz; 'Evet' ya da cevapsızken saklanır")
    void outageFieldsExemptWhenNoOutage() {
        var t = TechErrorTemplate.defaults("tr");
        Map<String, Object> f = new java.util.HashMap<>(Map.of("error-rate", 0.4, "error-rate-excl", 0.3, "outage-errors", 12,
                "outage-share", 5.5, "error-count", 300));
        f.put("had-outage", false);
        var no = TechErrorValues.normalize(Map.of("fields", f), t);
        assertThat(no.fields()).containsEntry("had-outage", false).containsKeys("error-rate", "error-count")
                .doesNotContainKeys("error-rate-excl", "outage-errors", "outage-share");
        f.put("had-outage", true);
        assertThat(TechErrorValues.normalize(Map.of("fields", f), t).fields())
                .containsKeys("error-rate-excl", "outage-errors", "outage-share");
        f.remove("had-outage");
        assertThat(TechErrorValues.normalize(Map.of("fields", f), t).fields())
                .containsKeys("error-rate-excl", "outage-errors", "outage-share");
        // "Hayır" iken gizli alandaki GEÇERSİZ değer kaydı reddettirmez (katı doğrulanmaz, düşer)
        Map<String, Object> bad = new java.util.HashMap<>(Map.of("had-outage", false, "outage-share", 5_000_000_000_000_000.0,
                "outage-errors", "çok"));
        assertThat(TechErrorValues.normalize(Map.of("fields", bad), t).fields()).containsOnlyKeys("had-outage");
        bad.put("had-outage", true);
        assertThatThrownBy(() -> TechErrorValues.normalize(Map.of("fields", bad), t))
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class);
    }

    @Test
    @DisplayName("metin bölümü çözülmüş varsayılan kadar (12 000) uzun olabilir; üstü reddedilir")
    void textSectionAllowsResolvedLength() {
        var t = TechErrorTemplate.defaults("tr");
        String sid = t.sections().stream().filter(s -> "text".equals(s.kind())).findFirst().orElseThrow().id();
        var ok = TechErrorValues.normalize(java.util.Map.of("texts", java.util.Map.of(sid, "x".repeat(11_000))), t);
        org.assertj.core.api.Assertions.assertThat(ok.texts().get(sid).length()).isEqualTo(11_000);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TechErrorValues.normalize(
                java.util.Map.of("texts", java.util.Map.of(sid, "x".repeat(TechErrorTemplate.MAX_RESOLVED_TEXT + 1))), t))
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class);
    }

    private final Template t = TechErrorTemplate.defaults("tr");

    @Test
    @DisplayName("tür kuralları: sayı ≥ 0, yüzde negatif olabilir, evet-hayır yalnız boolean, sayısal metin kabul")
    void typeRules() {
        Map<String, Object> f = new HashMap<>();
        f.put("error-rate", 0.42);
        f.put("tx-increase-pct", -3.5);
        f.put("total-tx", "1250000");
        f.put("tx-increase", false);
        f.put("unmapped-note", "Satır 1\r\nSatır 2");
        f.put("bilinmeyen", 12);           // şablonda yok → düşer
        f.put("quota-count", "");         // boş → saklanmaz
        Values v = TechErrorValues.normalize(Map.of("fields", f), t);
        assertThat(v.fields()).containsEntry("error-rate", 0.42).containsEntry("tx-increase-pct", -3.5)
                .containsEntry("total-tx", 1250000L).containsEntry("tx-increase", false)
                .containsEntry("unmapped-note", "Satır 1\nSatır 2")
                .doesNotContainKeys("bilinmeyen", "quota-count");

        assertInvalid(Map.of("fields", Map.of("total-tx", -1)), "values.total-tx");
        assertInvalid(Map.of("fields", Map.of("error-rate", "yüzde")), "values.error-rate");
        assertInvalid(Map.of("fields", Map.of("sla-above", "evet")), "values.sla-above");
        assertInvalid(Map.of("fields", Map.of("total-tx", 1e16)), "values.total-tx");
    }

    @Test
    @DisplayName("anomali başlıkları: en çok 30, kimlik tekilleşir, sayaçlar ≥ 0, başlık kalıbı konuyu izler")
    void issues() {
        List<Object> list = new ArrayList<>();
        list.add(Map.of("id", "a", "topic", "Kart limiti", "title", "", "title_auto", true, "description", "Ne zaman: 12 Eylül",
                "customers", 1200, "errors", "3400"));
        list.add(Map.of("id", "a", "topic", "", "title", "Elle başlık", "title_auto", false));
        Values v = TechErrorValues.normalize(Map.of("issues", Map.of("issues", list)), t);
        var out = v.issues().get("issues");
        assertThat(out).hasSize(2);
        assertThat(out.get(0).id()).isNotEqualTo(out.get(1).id());
        assertThat(out.get(0).errors()).isEqualTo(3400L);
        assertThat(out.get(0).displayTitle("{konu} Konusu ile yaşanan problem")).isEqualTo("Kart limiti Konusu ile yaşanan problem");
        assertThat(out.get(1).displayTitle("{konu} Konusu ile yaşanan problem")).isEqualTo("Elle başlık");

        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i < 31; i++) tooMany.add(Map.of("topic", "k" + i));
        assertInvalid(Map.of("issues", Map.of("issues", tooMany)), "issues.issues");
        assertInvalid(Map.of("issues", Map.of("issues", List.of(Map.of("customers", -5)))), "issues.issues.1");
    }

    @Test
    @DisplayName("metin bölümü: boş metin de saklanır (varsayılanı bilerek silmek), 12 000 karakter sınırı (çözülmüş varsayılan)")
    void texts() {
        Values v = TechErrorValues.normalize(Map.of("texts", Map.of("trend-note", "")), t);
        assertThat(v.texts()).containsEntry("trend-note", "");
        assertInvalid(Map.of("texts", Map.of("trend-note", "x".repeat(TechErrorTemplate.MAX_RESOLVED_TEXT + 1))), "text.trend-note");
    }

    @Test
    @DisplayName("saklı değer hoşgörülü: şablondan çıkan alan düşer, bozuk JSON boş değer verir")
    void lenientStored() {
        String json = TechErrorValues.toJson(TechErrorValues.normalize(Map.of("fields", Map.of("error-rate", 1.5, "quota-note", "Not")), t));
        Map<String, Object> raw = TechErrorTemplate.toMap(t);
        @SuppressWarnings("unchecked") List<Object> secs = new ArrayList<>((List<Object>) raw.get("sections"));
        secs.removeIf(o -> "quota".equals(((Map<?, ?>) o).get("id")));   // kota bölümü çıkarıldı
        Map<String, Object> smaller = new HashMap<>(raw);
        smaller.put("sections", secs);
        Values v = TechErrorValues.fromStored(json, TechErrorTemplate.normalize(smaller));
        assertThat(v.fields()).containsOnlyKeys("error-rate");
        assertThat(TechErrorValues.fromStored("{bozuk", t).fields()).isEmpty();
    }

    @Test
    @DisplayName("biçim: TR %0,42 ve 1.250.000; EN 0.42% ve 1,250,000; Evet/Hayır; boş —")
    void format() {
        var rate = t.allFields().stream().filter(f -> f.id().equals("error-rate")).findFirst().orElseThrow();
        var tx = t.allFields().stream().filter(f -> f.id().equals("total-tx")).findFirst().orElseThrow();
        var yes = t.allFields().stream().filter(f -> f.id().equals("sla-above")).findFirst().orElseThrow();
        assertThat(TechErrorFormat.value(rate, 0.42, "tr")).isEqualTo("%0,42");
        assertThat(TechErrorFormat.value(rate, 0.42, "en")).isEqualTo("0.42%");
        assertThat(TechErrorFormat.value(tx, 1250000L, "tr")).isEqualTo("1.250.000");
        assertThat(TechErrorFormat.value(tx, 1250000L, "en")).isEqualTo("1,250,000");
        assertThat(TechErrorFormat.value(yes, true, "tr")).isEqualTo("Evet");
        assertThat(TechErrorFormat.value(yes, false, "en")).isEqualTo("No");
        assertThat(TechErrorFormat.value(tx, null, "tr")).isEqualTo("—");
        assertThat(TechErrorFormat.value(rate, 12.123456, "tr")).isEqualTo("%12,1235");
    }

    private void assertInvalid(Map<String, Object> raw, String field) {
        assertThatThrownBy(() -> TechErrorValues.normalize(raw, t))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey(field));
    }
}
