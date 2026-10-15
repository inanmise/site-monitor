package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.techerror.TechErrorTemplate.Slot;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Kanal teknik hata raporu şablonu (2026-10-13): varsayılan düzen, katı / hoşgörülü doğrulama, yer tutucular. */
class TechErrorTemplateTest {

    @Test
    @DisplayName("varsayılan şablon: özet → göstergeler → anomali → map yok → kota → SLA (ekran görüntüsü) → not → otomatik trend → 12 aylık özetler → ay bazlı → birim dağılımı → gün bazlı")
    void defaultLayoutMatchesTheRequest() {
        Template t = TechErrorTemplate.defaults("tr");
        assertThat(t.title()).isEqualTo("{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}");
        assertThat(t.sections()).extracting(s -> s.kind())
                .containsExactly("summary", "metrics", "issues", "metrics", "metrics", "images", "text", "trend", "images", "images", "images", "images");
        assertThat(t.sections().get(0).title()).isEqualTo("Yönetici Özeti");
        assertThat(t.sections().get(3).title()).isEqualTo("Map Yapılmamış Hata Adedi");
        assertThat(t.sections().get(4).title()).isEqualTo("Kota Hatası Adedi");
        // Kullanıcı kararı 2026-10-15: SLA tablosu elle girilmez — raporlama aracının tablosunun ekran görüntüsü, tam genişlik
        // (iki kolon); tek görüntü ya da iki ayrı görüntü
        var sla = t.sections().get(5);
        assertThat(sla.title()).isEqualTo("SLA Erişilebilirlik Tablosu");
        assertThat(sla.columns()).isEmpty();
        assertThat(sla.slots()).extracting(Slot::width).containsExactly("full", "full");
        assertThat(t.sections()).noneMatch(s -> "table".equals(s.kind()));

        // Grafik akışı (kullanıcı kararı 2026-10-15): 12 aylık özetler → ay bazlı (son 12 ay) → raporun ayının birim
        // dağılımı → gün bazlı; her bölüm kendi başlığıyla
        assertThat(t.sections().get(7).title()).isEqualTo("Otomatik Trendler (Son 12 Ay)");      // girilen rakamlardan, Faz C
        assertThat(t.sections().subList(8, 12)).extracting(s -> s.title()).containsExactly("12 Aylık Özetler",
                "Ay Bazlı Hata ve İşlem Adetleri (Son 12 Ay)", "{ay} {yil} Birim Bazlı Hata Dağılımı",
                "Gün Bazlı Hata ve İşlem Adetleri ({ay} {yil})");
        List<Slot> summary = t.sections().get(8).slots(), monthly = t.sections().get(9).slots(),
                dist = t.sections().get(10).slots(), daily = t.sections().get(11).slots();
        assertThat(summary).extracting(Slot::label, Slot::width).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Yıllık Operasyon ve Hata Dağılım Özeti (Son 12 Ay)", "half"),
                org.assertj.core.groups.Tuple.tuple("Yıllık Teknik Hata Oranı Trendi (Son 12 Ay)", "half"),
                org.assertj.core.groups.Tuple.tuple("Son 12 Ay Operasyon ve Hata Özet Tablosu", "full"));
        // Her kırılım iki tabanda: toplam işlem, toplam hata, teknik, business, maplenmemiş, kota
        for (String k : List.of("Toplam İşlem", "Toplam Hata", "Teknik Hata", "Business Hata", "Maplenmemiş Hata", "Kota Hata")) {
            assertThat(monthly).extracting(Slot::label).anyMatch(l -> l.startsWith("Ay Bazlı " + k));
            assertThat(daily).extracting(Slot::label).anyMatch(l -> l.startsWith("Gün Bazlı " + k));
        }
        assertThat(monthly).extracting(Slot::label).allMatch(l -> l.startsWith("Ay Bazlı ") && l.endsWith("(Son 12 Ay)"));
        assertThat(daily).extracting(Slot::label).allMatch(l -> l.startsWith("Gün Bazlı ") && l.endsWith("({ay} {yil})"));
        assertThat(dist).extracting(Slot::label).allMatch(l -> l.startsWith("{ay} {yil} ") && l.endsWith("(Birim Bazlı)"));
        // Yarımlar çift çift (PDF'te yan yana), tamlar bölüm sonunda
        for (List<Slot> group : List.of(summary, monthly, dist, daily)) {
            long halves = group.stream().filter(x -> "half".equals(x.width())).count();
            assertThat(halves % 2).isZero();
            assertThat(group.subList(0, (int) halves)).extracting(Slot::width).containsOnly("half");
        }
        List<Slot> slots = t.allSlots().subList(2, t.allSlots().size());
        assertThat(slots).hasSize(22);
        // Eski "(Aylık)" / "(Günlük)" / "Grafiği" adları kalmadı
        assertThat(slots).extracting(Slot::label).noneMatch(l -> l.contains("(Aylık)") || l.contains("(Günlük)") || l.contains("Grafiği"));
        // EN: aynı yapı
        Template en = TechErrorTemplate.defaults("en");
        assertThat(en.sections().get(9).slots()).extracting(Slot::label).allMatch(l -> l.startsWith("Monthly ") && l.endsWith("(Last 12 Months)"));
        assertThat(en.sections().get(11).slots()).extracting(Slot::label).allMatch(l -> l.startsWith("Daily ") && l.endsWith("({month} {year})"));
        assertThat(en.sections().get(8).slots().get(0).label())
                .isEqualTo("Annual Operations and Error Distribution Summary (Last 12 Months)");
        assertThat(t.allFields()).extracting(f -> f.id()).contains("error-rate", "error-rate-excl", "total-tx",
                "tx-increase", "tx-increase-pct", "sla-rate", "sla-above", "error-count", "outage-errors", "outage-share",
                "unmapped-count", "quota-count");
    }

    @Test
    @DisplayName("TR ve EN varsayılanlar aynı kimlikleri taşır; katı doğrulamadan aynen geçer (gidiş-dönüş)")
    void defaultsRoundTrip() {
        for (String lang : List.of("tr", "en")) {
            Template t = TechErrorTemplate.defaults(lang);
            Template again = TechErrorTemplate.normalize(TechErrorTemplate.toMap(t));
            assertThat(TechErrorTemplate.toJson(again)).isEqualTo(TechErrorTemplate.toJson(t));
            assertThat(TechErrorTemplate.fromStored(TechErrorTemplate.toJson(t))).isEqualTo(t);
        }
        assertThat(TechErrorTemplate.defaults("en").allFields()).extracting(f -> f.id())
                .isEqualTo(TechErrorTemplate.defaults("tr").allFields().stream().map(f -> f.id()).toList());
        assertThat(TechErrorTemplate.defaults("en").allSlots()).extracting(Slot::id)
                .isEqualTo(TechErrorTemplate.defaults("tr").allSlots().stream().map(Slot::id).toList());
    }

    @Test
    @DisplayName("yer tutucular: kanal / ay / yıl (TR ve EN adları); ay adı şablon dilinde")
    void placeholders() {
        assertThat(TechErrorTemplate.resolve("{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}", "Mobil", 2026, 9, "tr"))
                .isEqualTo("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026");
        assertThat(TechErrorTemplate.resolve("{channel} - {month} {year}", "POS", 2025, 12, "en")).isEqualTo("POS - December 2025");
        assertThat(TechErrorTemplate.resolve("{kanal} {month}", "ATM", 2026, 1, "tr")).isEqualTo("ATM Ocak");
        assertThat(TechErrorTemplate.monthName(13, "tr")).isEqualTo("13");
        assertThat(TechErrorTemplate.resolveIssueTitle("{konu} Konusu ile yaşanan problem", "Kart limiti"))
                .isEqualTo("Kart limiti Konusu ile yaşanan problem");
        assertThat(TechErrorTemplate.resolveIssueTitle("{konu} Konusu ile yaşanan problem", " ")).isEqualTo("Konusu ile yaşanan problem");
    }

    @Test
    @DisplayName("katı doğrulama: boş başlık, tanınmayan tür, tekrarlı kimlik, boş grafik adı, 20+ bölüm, 40+ yuva reddedilir")
    void strictRejects() {
        Map<String, Object> ok = TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr"));

        Map<String, Object> noTitle = new LinkedHashMap<>(ok);
        noTitle.put("title", "  ");
        assertInvalid(noTitle);

        Map<String, Object> badKind = copyWithSection(ok, Map.of("id", "x", "kind", "chart", "title", "X"));
        assertInvalid(badKind);

        Map<String, Object> dup = copyWithSection(ok, Map.of("id", "summary", "kind", "text", "title", "Tekrar"));
        assertInvalid(dup);

        Map<String, Object> emptySlot = copyWithSection(ok, Map.of("id", "more", "kind", "images", "title", "Ek",
                "slots", List.of(Map.of("id", "e1", "label", "", "width", "full"))));
        assertInvalid(emptySlot);

        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 21; i++) many.add(Map.of("id", "t" + i, "kind", "text", "title", "T" + i, "text", ""));
        Map<String, Object> tooMany = new LinkedHashMap<>(ok);
        tooMany.put("sections", many);
        assertInvalid(tooMany);

        List<Object> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) slots.add(Map.of("id", "z" + i, "label", "Z" + i, "width", "half"));
        Map<String, Object> tooManySlots = copyWithSection(ok, Map.of("id", "more", "kind", "images", "title", "Ek", "slots", slots));
        assertInvalid(tooManySlots);   // 14 + 27 = 41 > 40

        Map<String, Object> badLang = new LinkedHashMap<>(ok);
        badLang.put("lang", "de");
        assertInvalid(badLang);
    }

    @Test
    @DisplayName("katı doğrulama: eksik kimlik üretilir, genişlik varsayılanı yarım, denetim karakterleri temizlenir")
    void strictNormalizes() {
        Map<String, Object> raw = Map.of("lang", "tr", "title", "Başlık\t{kanal}", "sections", List.of(
                Map.of("kind", "metrics", "title", "Özet", "fields", List.of(
                        Map.of("label", "Oran\n", "type", "percent"), Map.of("label", "Adet", "type", "number"))),
                Map.of("kind", "images", "title", "Grafikler", "slots", List.of(Map.of("label", "G1")))));
        Template t = TechErrorTemplate.normalize(raw);
        assertThat(t.title()).isEqualTo("Başlık {kanal}");
        assertThat(t.sections().get(0).id()).isEqualTo("s-1");
        assertThat(t.allFields()).extracting(f -> f.id()).containsExactly("f-1", "f-2");
        assertThat(t.allFields().get(0).label()).isEqualTo("Oran");
        assertThat(t.allSlots().get(0).width()).isEqualTo("half");
    }

    @Test
    @DisplayName("saklı şablon hoşgörülü okunur: bozuk JSON → varsayılan, geçersiz bölüm atlanır, uzun ad kırpılır")
    void lenientStored() {
        assertThat(TechErrorTemplate.fromStored("{bozuk")).isEqualTo(TechErrorTemplate.defaults("tr"));
        assertThat(TechErrorTemplate.fromStored(null).sections()).isNotEmpty();
        String json = "{\"lang\":\"tr\",\"title\":\"" + "A".repeat(300) + "\",\"sections\":["
                + "{\"id\":\"a\",\"kind\":\"nope\",\"title\":\"x\"},"
                + "{\"id\":\"b\",\"kind\":\"text\",\"title\":\"Metin\",\"text\":\"merhaba\"}]}";
        Template t = TechErrorTemplate.fromStored(json);
        assertThat(t.title()).hasSize(TechErrorTemplate.MAX_TITLE);
        assertThat(t.sections()).extracting(s -> s.id()).containsExactly("b");
    }

    @Test
    @DisplayName("kesinti sorusu: varsayılanda 'Dönemde kesinti oldu mu?' üç kesinti alanını yönetir (TR + EN), JSON'da korunur")
    void defaultOutageQuestionControlsOutageFields() {
        for (String lang : List.of("tr", "en")) {
            Template t = TechErrorTemplate.defaults(lang);
            Map<String, TechErrorTemplate.Field> byId = new LinkedHashMap<>();
            for (var f : t.allFields()) byId.put(f.id(), f);
            assertThat(byId.get("had-outage").type()).isEqualTo("yesno");
            assertThat(byId.get("had-outage").skipIfNo()).isNull();
            for (String id : List.of("error-rate-excl", "outage-errors", "outage-share")) {
                assertThat(byId.get(id).skipIfNo()).as(lang + " " + id).isEqualTo("had-outage");
            }
            assertThat(byId.get("error-rate").skipIfNo()).isNull();
            Template back = TechErrorTemplate.fromStored(TechErrorTemplate.toJson(t));
            assertThat(back).isEqualTo(t);
            assertThat(TechErrorTemplate.normalize(TechErrorTemplate.toMap(t))).isEqualTo(t);
        }
        var excl = TechErrorTemplate.defaults("tr").allFields().stream().filter(f -> f.id().equals("error-rate-excl")).findFirst().orElseThrow();
        assertThat(TechErrorTemplate.exempt(excl, Map.of("had-outage", false))).isTrue();
        assertThat(TechErrorTemplate.exempt(excl, Map.of("had-outage", true))).isFalse();
        assertThat(TechErrorTemplate.exempt(excl, Map.of())).isFalse();          // cevaplanmadı → istenir
    }

    @Test
    @DisplayName("'Hayır ise sorma' bağı: yalnız evet/hayır sorusuna, kendisine değil, zincirsiz; katı reddeder, saklı düşürür")
    void skipIfNoReferenceRules() {
        Map<String, Object> raw = TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr"));
        for (String bad : List.of("total-tx", "error-rate-excl", "yok-boyle-alan")) {
            Map<String, Object> copy = withSkip(raw, "error-rate", bad);
            assertInvalid(copy);
            // saklı (hoşgörülü) okumada bağ düşer, alan kalır
            Template lenient = TechErrorTemplate.fromStored(TechErrorTemplate.JSON.writeValueAsString(copy));
            assertThat(lenient.allFields().stream().filter(f -> f.id().equals("error-rate")).findFirst().orElseThrow().skipIfNo()).isNull();
        }
        assertInvalid(withSkip(raw, "had-outage", "had-outage"));               // kendisine
        assertInvalid(withSkip(raw, "had-outage", "sla-above"));                // zincir: bağlı alanların sorusu kendisi bağlı
        // geçerli bağ: evet/hayır sorusuna, zincirsiz
        Template ok = TechErrorTemplate.normalize(withSkip(raw, "sla-rate", "sla-above"));
        assertThat(ok.allFields().stream().filter(f -> f.id().equals("sla-rate")).findFirst().orElseThrow().skipIfNo())
                .isEqualTo("sla-above");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withSkip(Map<String, Object> raw, String fieldId, String target) {
        Map<String, Object> copy = new LinkedHashMap<>(raw);
        List<Map<String, Object>> secs = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) raw.get("sections")) {
            Map<String, Object> sc = new LinkedHashMap<>(s);
            if (s.get("fields") instanceof List<?> fl) {
                List<Map<String, Object>> fs = new ArrayList<>();
                for (Object o : fl) {
                    Map<String, Object> fm = new LinkedHashMap<>((Map<String, Object>) o);
                    if (fieldId.equals(fm.get("id"))) fm.put("skip_if_no", target);
                    fs.add(fm);
                }
                sc.put("fields", fs);
            }
            secs.add(sc);
        }
        copy.put("sections", secs);
        return copy;
    }

    private static void assertInvalid(Map<String, Object> raw) {
        assertThatThrownBy(() -> TechErrorTemplate.normalize(raw))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("template"));
    }

    private static Map<String, Object> copyWithSection(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        List<Object> secs = new ArrayList<>((List<?>) base.get("sections"));
        secs.add(extra);
        out.put("sections", secs);
        return out;
    }
}
