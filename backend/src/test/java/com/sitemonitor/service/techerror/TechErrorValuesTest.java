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
        assertInvalid(Map.of("issues", Map.of("issues", List.of(Map.of("customers", -5)))), "issues.issues.i-1.customers");
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
        // Kota açıklaması şablondan çıkarıldı (7. sürüm: alan "Verileri" bölümünde — bölüm değil alan silinir)
        for (Object o : secs) {
            @SuppressWarnings("unchecked") Map<String, Object> sec = (Map<String, Object>) o;
            if (sec.get("fields") instanceof List<?> fl) {
                List<Object> fields = new ArrayList<>(fl);
                fields.removeIf(f -> "quota-note".equals(((Map<?, ?>) f).get("id")));
                sec.put("fields", fields);
            }
        }
        Map<String, Object> smaller = new HashMap<>(raw);
        smaller.put("sections", secs);
        Values v = TechErrorValues.fromStored(json, TechErrorTemplate.normalize(smaller));
        assertThat(v.fields()).containsOnlyKeys("error-rate");
        assertThat(TechErrorValues.fromStored("{bozuk", t).fields()).isEmpty();
    }

    @Test
    @DisplayName("2026-10-17 (4b): raporun o aya özel hedefi yalnız şablonda hedefi olan yüzde alanında ve %0–100; hedefsiz alan sessiz düşer; sayı değilse / aralık dışıysa 400 (targets.<id>); boş yazılmaz; saklıda hoşgörülü")
    void targets() {
        var t = TechErrorTemplate.defaults("tr");
        var v = TechErrorValues.normalize(Map.of("fields", Map.of("sla-rate", 99.9),
                "targets", Map.of("sla-rate", 99.8, "error-rate", 1, "total-tx", 5, "yok", 3)), t);
        assertThat(v.targets()).containsOnlyKeys("sla-rate");
        assertThat(TechErrorValues.number(v.targets().get("sla-rate"))).isEqualTo(99.8);
        assertThat(TechErrorValues.toMap(v)).containsKey("targets");
        // Hedefsiz rapor: anahtar hiç yazılmaz (4b öncesi belgeyle aynı); boş metin = yok; sayısal metin kabul
        assertThat(TechErrorValues.toMap(TechErrorValues.normalize(Map.of("fields", Map.of("sla-rate", 99.9)), t))).doesNotContainKey("targets");
        assertThat(TechErrorValues.normalize(Map.of("targets", Map.of("sla-rate", " ")), t).targets()).isEmpty();
        assertThat(TechErrorValues.number(TechErrorValues.normalize(Map.of("targets", Map.of("sla-rate", "99.8")), t).targets().get("sla-rate")))
                .isEqualTo(99.8);
        // Şablon hedefine eşit değer de saklanır: o ay için sabitlenir (şablon sonradan değişse de geçmişte görünür)
        assertThat(TechErrorValues.normalize(Map.of("targets", Map.of("sla-rate", 99.95)), t).targets()).containsOnlyKeys("sla-rate");
        for (Object bad : List.of(101, -0.5, "yüksek")) {
            assertThatThrownBy(() -> TechErrorValues.normalize(Map.of("targets", Map.of("sla-rate", bad)), t))
                    .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class)
                    .satisfies(e -> assertThat(((com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException) e).getFields())
                            .containsKey("targets.sla-rate"));
        }
        // Saklı: bozuk hedef düşer, kayıt okunur; eski belge (anahtar yok) boş hedef
        var stored = TechErrorValues.fromStored("{\"fields\":{\"sla-rate\":99.9},\"targets\":{\"sla-rate\":\"x\",\"error-rate\":1}}", t);
        assertThat(TechErrorValues.number(stored.fields().get("sla-rate"))).isEqualTo(99.9);
        assertThat(stored.targets()).isEmpty();
        assertThat(TechErrorValues.fromStored("{\"fields\":{\"sla-rate\":99.9}}", t).targets()).isEmpty();
        assertThat(TechErrorValues.Values.empty().targets()).isEmpty();
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

    /** Sayı + yüzde + muaf olabilen sayı + anomali + tablo (kısaltma testleri, 2026-10-18). */
    static Template unitTemplate() {
        return TechErrorTemplate.normalize(Map.of("lang", "tr", "title", "{kanal}", "sections", List.of(
                Map.of("id", "m", "kind", "metrics", "title", "M", "fields", List.of(
                        Map.of("id", "total", "label", "Toplam", "type", "number"),
                        Map.of("id", "rate", "label", "Oran", "type", "percent"),
                        Map.of("id", "q", "label", "Kesinti?", "type", "yesno"),
                        Map.of("id", "outage", "label", "Kesinti adedi", "type", "number", "skip_if_no", "q"),
                        Map.of("id", "note", "label", "Not", "type", "text"))),
                Map.of("id", "iss", "kind", "issues", "title", "I"),
                Map.of("id", "tb", "kind", "table", "title", "T",
                        "columns", List.of(Map.of("id", "n", "label", "N", "type", "number"), Map.of("id", "p", "label", "P", "type", "percent")),
                        "rows", List.of(Map.of("id", "r1", "label", "R1"))))));
    }

    @Test
    @DisplayName("2026-10-18: kısaltma üsleri yalnız değeri olan SAYI yerinde (alan / anomali adedi / sayı sütunu) ve 3/6/9/12; geçersiz kayıt 400 değil sessizce düşer; muaf alanla düşer; kısaltmasız belge bayt bayt eski")
    void unitsAreValidatedSilently() {
        Template ut = unitTemplate();
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("total", 4_400_000_000L);
        fields.put("rate", 0.42);
        fields.put("q", false);
        fields.put("outage", 1000);
        fields.put("note", "12k");
        Map<String, Object> issue = new java.util.LinkedHashMap<>(Map.of("id", "i-a", "topic", "A", "customers", 1_250_000));
        Map<String, Object> units = new java.util.LinkedHashMap<>();
        units.put("total", 9);
        units.put("rate", 9);                                     // yüzde: düşer
        units.put("outage", 3);                                   // muaf alan: düşer
        units.put("note", 3);                                     // metin: düşer
        units.put("ghost", 6);                                    // şablonda yok: düşer
        units.put("issues.iss.i-a.customers", 6);
        units.put("issues.iss.i-a.errors", 6);                    // değer yok: düşer
        units.put("issues.iss.i-x.customers", 6);                 // konu yok: düşer
        units.put("tables.tb.r1.n", 3);
        units.put("tables.tb.r1.p", 3);                           // yüzde sütunu: düşer
        Map<String, Object> raw = Map.of("fields", fields, "issues", Map.of("iss", List.of(issue)),
                "tables", Map.of("tb", Map.of("r1", Map.of("n", 2500, "p", 1.5))), "units", units);
        Values v = TechErrorValues.normalize(raw, ut);
        assertThat(v.units()).containsExactly(Map.entry("total", 9), Map.entry("issues.iss.i-a.customers", 6), Map.entry("tables.tb.r1.n", 3));
        assertThat(v.unit("total")).isEqualTo(9);
        assertThat(v.issueUnit("iss", "i-a", "customers")).isEqualTo(6);
        assertThat(v.cellUnit("tb", "r1", "n")).isEqualTo(3);
        assertThat(TechErrorValues.toMap(v)).containsKey("units");
        // Geçersiz üsler (7, metin, ondalık, boolean, null, negatif) ve harita olmayan "units" 400 değil: düşer
        for (Object bad : java.util.Arrays.asList(7, "9", 9.5, true, null, -3, 0, 15)) {
            Map<String, Object> u = new java.util.HashMap<>();
            u.put("total", bad);
            assertThat(TechErrorValues.normalize(Map.of("fields", Map.of("total", 5), "units", u), ut).units()).as(String.valueOf(bad)).isEmpty();
        }
        assertThat(TechErrorValues.normalize(Map.of("fields", Map.of("total", 5), "units", List.of(9)), ut).units()).isEmpty();
        assertThat(TechErrorValues.normalize(Map.of("fields", Map.of("total", 5_000_000_000L), "units", Map.of("total", 9.0)), ut).units())
                .containsEntry("total", 9);
        // Kısaltmasız belge: anahtar hiç yazılmaz (önceki sürümle bayt bayt aynı JSON)
        Values plain = TechErrorValues.normalize(Map.of("fields", Map.of("total", 5)), ut);
        assertThat(TechErrorValues.toJson(plain)).isEqualTo("{\"fields\":{\"total\":5},\"issues\":{},\"texts\":{}}");
        // Saklıdan okuma: aynı süzgeç; bozuk birim kaydı kaydı okutmaz değil, yalnız düşer
        Values stored = TechErrorValues.fromStored(TechErrorValues.toJson(v), ut);
        assertThat(stored.units()).isEqualTo(v.units());
        assertThat(TechErrorValues.fromStored("{\"fields\":{\"total\":5},\"units\":{\"total\":\"G\"}}", ut).units()).isEmpty();
        assertThat(TechErrorValues.fromStored("{\"fields\":{\"total\":5},\"units\":{\"total\":\"G\"}}", ut).fields()).containsEntry("total", 5L);
        // Değeri silinen alanın üssü sahipsiz kalmaz (hesap sonrası alan haritası)
        assertThat(v.withFields(Map.of("rate", 0.42)).units()).doesNotContainKey("total").containsKeys("issues.iss.i-a.customers", "tables.tb.r1.n");
        assertThat(Values.empty().units()).isEmpty();
    }

    @Test
    @DisplayName("inceleme 2026-10-18: kaydırılınca 6 ondalığa sığmayan değerin üssü saklanmaz (gösterim yuvarlanmış sayı olurdu) — alan, anomali adedi, tablo hücresi")
    void unitsDroppedWhenAbbreviationWouldRound() {
        Template ut = unitTemplate();
        Map<String, Object> issue = new java.util.LinkedHashMap<>(Map.of("id", "i-a", "topic", "A", "customers", 1_234_567, "errors", 1_234_567));
        Map<String, Object> units = new java.util.LinkedHashMap<>();
        units.put("total", 9);                                    // 4,123456789G: 9 ondalık → düşer
        units.put("issues.iss.i-a.customers", 3);                 // 1234,567k: 3 ondalık → kalır
        units.put("issues.iss.i-a.errors", 12);                   // 0,000001234567T → düşer
        units.put("tables.tb.r1.n", 12);                          // 2500 / 10^12 → düşer
        Map<String, Object> raw = Map.of("fields", Map.of("total", 4_123_456_789L), "issues", Map.of("iss", List.of(issue)),
                "tables", Map.of("tb", Map.of("r1", Map.of("n", 2500))), "units", units);
        Values v = TechErrorValues.normalize(raw, ut);
        assertThat(v.units()).containsExactly(Map.entry("issues.iss.i-a.customers", 3));
        assertThat(v.fields()).containsEntry("total", 4_123_456_789L);                  // sayının kendisi aynen
        // Tam 6 ondalık sığar: 4123,456789M
        assertThat(TechErrorValues.normalize(Map.of("fields", Map.of("total", 4_123_456_789L), "units", Map.of("total", 6)), ut).units())
                .containsEntry("total", 6);
        // Saklıdan okuma (önceki derlemeyle yazılmış satır) aynı süzgeç
        assertThat(TechErrorValues.fromStored("{\"fields\":{\"total\":4123456789},\"units\":{\"total\":9}}", ut).units()).isEmpty();
    }

    @Test
    @DisplayName("kısaltmayla yazılan sıfırın (\"0G\", \"0k\") üssü katı ve saklı okumada sessizce düşer — rapor \"0\" basar")
    void unitsDroppedForZero() {
        Template ut = unitTemplate();
        Map<String, Object> issue = new java.util.LinkedHashMap<>(Map.of("id", "i-a", "topic", "A", "customers", 0, "errors", 2000));
        Map<String, Object> units = new java.util.LinkedHashMap<>();
        units.put("total", 9);                                    // 0G → düşer
        units.put("issues.iss.i-a.customers", 3);                 // 0k → düşer
        units.put("issues.iss.i-a.errors", 3);                    // 2k → kalır
        units.put("tables.tb.r1.n", 6);                           // 0M → düşer
        Map<String, Object> raw = Map.of("fields", Map.of("total", 0), "issues", Map.of("iss", List.of(issue)),
                "tables", Map.of("tb", Map.of("r1", Map.of("n", 0))), "units", units);
        Values v = TechErrorValues.normalize(raw, ut);
        assertThat(v.units()).containsExactly(Map.entry("issues.iss.i-a.errors", 3));
        assertThat(v.fields()).containsEntry("total", 0L);                               // sayının kendisi aynen
        assertThat(TechErrorValues.fromStored("{\"fields\":{\"total\":0},\"units\":{\"total\":9}}", ut).units()).isEmpty();
        assertThat(TechErrorValues.fromStored("{\"fields\":{\"total\":0.0},\"units\":{\"total\":12}}", ut).units()).isEmpty();
    }

    /** Varsayılan şablon (anomali soruları açık) + verilen konu → katı okuma; dönem Eylül 2026. */
    private static Values anomaly(Map<String, Object> issue) {
        return TechErrorValues.normalize(Map.of("issues", Map.of("issues", List.of(issue))), TechErrorTemplate.defaults("tr"),
                java.time.YearMonth.of(2026, 9));
    }

    private static void anomalyInvalid(Map<String, Object> issue, String key) {
        assertThatThrownBy(() -> anomaly(issue))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey(key));
    }

    private static Map<String, Object> topic(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", "k1");
        m.put("topic", "Kart Limiti");
        m.put("title_auto", true);
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("anomali cevapları (2026-10-18): tarih gerçek ve raporun ayında, saat aralığı ikisi birlikte ve bitiş sonra (ertesi gün ≤ 24 sa), kesinti Evet'te ID zorunlu (≤ 60), müşteri sayısı yalnız etki Evet'te; hatalar alanın altında (issues.<bölüm>.<konu>.<alan>)")
    void anomalyAnswers() {
        var v = anomaly(topic("date", "2026-09-12", "start", "14:00", "end", "15:30", "outage", true, "outage_id", "  INC-2026-0915 ",
                "impact", true, "customers", 320, "errors", 1250, "captions", Map.of("1", "Hata grafiği", "2", " ", "3", "fazla")));
        var i = v.issues().get("issues").get(0);
        assertThat(i.date()).isEqualTo("2026-09-12");
        assertThat(i.start()).isEqualTo("14:00");
        assertThat(i.end()).isEqualTo("15:30");
        assertThat(i.endNextDay()).isFalse();
        assertThat(i.outage()).isTrue();
        assertThat(i.outageId()).isEqualTo("INC-2026-0915");
        assertThat(i.impact()).isTrue();
        assertThat(i.customers()).isEqualTo(320L);
        assertThat(i.captions()).containsExactly(Map.entry("1", "Hata grafiği"));
        assertThat(i.share()).isNull();                                    // pay okuma değil hesap işidir (TechErrorIssueShare)

        // Gece yarısını geçen aralık: "ertesi gün" ile bitiş başlangıçtan önce / eşit; saniyeli saat kısalır
        var night = anomaly(topic("date", "2026-09-30", "start", "23:30:00", "end", "01:15", "end_next_day", true)).issues().get("issues").get(0);
        assertThat(night.start()).isEqualTo("23:30");
        assertThat(night.endNextDay()).isTrue();
        assertThat(anomaly(topic("date", "2026-09-30", "start", "10:00", "end", "10:00", "end_next_day", true)).issues().get("issues").get(0).end())
                .isEqualTo("10:00");                                       // tam 24 saat
        // Saat yoksa "ertesi gün" tutulmaz
        assertThat(anomaly(topic("date", "2026-09-30", "end_next_day", true)).issues().get("issues").get(0).endNextDay()).isFalse();

        anomalyInvalid(topic("date", "2026-10-01"), "issues.issues.k1.date");                       // ay dışı
        anomalyInvalid(topic("date", "2026-02-31"), "issues.issues.k1.date");                       // gerçek değil
        anomalyInvalid(topic("date", "12.09.2026"), "issues.issues.k1.date");                       // biçim
        anomalyInvalid(topic("date", "2026-09-12", "start", "25:00", "end", "26:00"), "issues.issues.k1.start");
        anomalyInvalid(topic("date", "2026-09-12", "start", "14:00"), "issues.issues.k1.end");      // yarım aralık
        anomalyInvalid(topic("date", "2026-09-12", "end", "14:00"), "issues.issues.k1.start");
        anomalyInvalid(topic("date", "2026-09-12", "start", "15:30", "end", "14:00"), "issues.issues.k1.end");   // bitiş önce
        anomalyInvalid(topic("date", "2026-09-12", "start", "14:00", "end", "14:00"), "issues.issues.k1.end");   // eşit
        anomalyInvalid(topic("date", "2026-09-12", "start", "10:00", "end", "11:00", "end_next_day", true), "issues.issues.k1.end"); // > 24 sa
        anomalyInvalid(topic("start", "14:00", "end", "15:00"), "issues.issues.k1.date");           // saatli ama tarihsiz
        anomalyInvalid(topic("outage", true), "issues.issues.k1.outage_id");                        // ID zorunlu
        anomalyInvalid(topic("outage", true, "outage_id", "   "), "issues.issues.k1.outage_id");
        anomalyInvalid(topic("outage", true, "outage_id", "X".repeat(61)), "issues.issues.k1.outage_id");
        anomalyInvalid(topic("outage", "evet"), "issues.issues.k1.outage");
        anomalyInvalid(topic("impact", 1), "issues.issues.k1.impact");
        anomalyInvalid(topic("impact", true, "customers", -1), "issues.issues.k1.customers");
        anomalyInvalid(topic("captions", Map.of("1", "x".repeat(201))), "issues.issues.k1.caption1");
        anomalyInvalid(topic("errors", "çok"), "issues.issues.k1.errors");

        // Kesinti Hayır → ID saklanmaz; etki Hayır → müşteri sayısı saklanmaz; cevapsız etki + müşteri sayısı → sayı söyler
        var no = anomaly(topic("outage", false, "outage_id", "INC-1", "impact", false, "customers", 50)).issues().get("issues").get(0);
        assertThat(no.outage()).isFalse();
        assertThat(no.outageId()).isNull();
        assertThat(no.impact()).isFalse();
        assertThat(no.customers()).isNull();
        var inferred = anomaly(topic("customers", 40)).issues().get("issues").get(0);
        assertThat(inferred.impact()).isTrue();
        assertThat(inferred.customers()).isEqualTo(40L);
        var zero = anomaly(topic("customers", 0)).issues().get("issues").get(0);
        assertThat(zero.impact()).isFalse();
        assertThat(zero.customers()).isNull();

        // Hoşgörülü (saklı) okuma: bozuk tarih / saat düşer, ay dışı tarih kalır (dönem bilinmez), yarım aralık tutulmaz
        String stored = "{\"fields\":{},\"issues\":{\"issues\":[{\"id\":\"k1\",\"topic\":\"T\",\"title\":\"\",\"title_auto\":true,"
                + "\"description\":\"\",\"customers\":null,\"errors\":null,\"date\":\"2026-13-40\",\"start\":\"14:00\","
                + "\"outage\":true,\"impact\":\"evet\"}]},\"texts\":{}}";
        var lenient = TechErrorValues.fromStored(stored, TechErrorTemplate.defaults("tr")).issues().get("issues").get(0);
        assertThat(lenient.date()).isNull();
        assertThat(lenient.start()).isNull();
        assertThat(lenient.outage()).isTrue();
        assertThat(lenient.outageId()).isNull();
        assertThat(lenient.impact()).isNull();
    }

    @Test
    @DisplayName("anomali cevapları: soru kapalıysa cevabı okunmaz (eski düzen bölümü yeni alanları düşürür, müşteri sayısını her zaman tutar); eski belge bayt bayt aynı kalır")
    void anomalyAnswersFollowTheTemplate() {
        Template legacy = TechErrorTestTemplates.beforeAnomaly("tr");
        Map<String, Object> raw = topic("date", "2026-09-12", "start", "14:00", "end", "15:00", "outage", true, "outage_id", "INC-1",
                "impact", false, "customers", 12, "errors", 5, "captions", Map.of("1", "Grafik"));
        var i = TechErrorValues.normalize(Map.of("issues", Map.of("issues", List.of(raw))), legacy).issues().get("issues").get(0);
        assertThat(i.date()).isNull();
        assertThat(i.start()).isNull();
        assertThat(i.outage()).isNull();
        assertThat(i.outageId()).isNull();
        assertThat(i.impact()).isNull();
        assertThat(i.customers()).isEqualTo(12L);                          // etki sorulmuyor: müşteri sayısı eskisi gibi
        assertThat(i.captions()).isEmpty();
        // Eski düzende kaydedilmiş belge: yeni anahtar yok, JSON aynı
        String old = "{\"fields\":{},\"issues\":{\"issues\":[{\"id\":\"k1\",\"topic\":\"Kart Limiti\",\"title\":\"\",\"title_auto\":true,"
                + "\"description\":\"\",\"customers\":12,\"errors\":5}]},\"texts\":{}}";
        assertThat(TechErrorValues.toJson(TechErrorValues.fromStored(old, legacy))).isEqualTo(old);
        assertThat(TechErrorValues.toJson(TechErrorValues.fromStored(old, TechErrorTemplate.defaults("tr"))))
                .contains("\"customers\":12").contains("\"impact\":true").doesNotContain("\"date\"").doesNotContain("\"captions\"");
        // Şablona uydurma (şablon yenileme): kapatılan sorunun cevabı düşer, kimlik aynı
        var answered = TechErrorValues.normalize(Map.of("issues", Map.of("issues", List.of(raw))), TechErrorTemplate.defaults("tr"),
                java.time.YearMonth.of(2026, 9)).issues().get("issues");
        var conformed = TechErrorValues.conform(answered, legacy.sections().stream().filter(s -> "issues".equals(s.kind())).findFirst().orElseThrow());
        assertThat(conformed.get(0).id()).isEqualTo("k1");
        assertThat(conformed.get(0).date()).isNull();
        assertThat(conformed.get(0).outageId()).isNull();
    }

    private void assertInvalid(Map<String, Object> raw, String field) {
        assertThatThrownBy(() -> TechErrorValues.normalize(raw, t))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey(field));
    }
}
