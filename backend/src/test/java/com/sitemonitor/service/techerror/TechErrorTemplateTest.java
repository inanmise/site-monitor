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
    @DisplayName("varsayılan şablon kullanıcının verdiği düzen: özet → anomali → map yok → kota → not → 2 kolonlu grafikler")
    void defaultLayoutMatchesTheRequest() {
        Template t = TechErrorTemplate.defaults("tr");
        assertThat(t.title()).isEqualTo("{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}");
        assertThat(t.sections()).extracting(s -> s.kind())
                .containsExactly("metrics", "issues", "metrics", "metrics", "text", "images");
        assertThat(t.sections().get(2).title()).isEqualTo("Map Yapılmamış Hata Adedi");
        assertThat(t.sections().get(3).title()).isEqualTo("Kota Hatası Adedi");
        List<Slot> slots = t.allSlots();
        // 1. sıra: iki yarım, 2. sıra: iki yarım, 3. ve 4. sıra tam, ardından ek tam genişlik grafikler
        assertThat(slots.subList(0, 4)).extracting(Slot::width).containsOnly("half");
        assertThat(slots.get(0).label()).isEqualTo("{kanal} Kanalı Ay Bazlı Toplam İşlem Adetleri");
        assertThat(slots.get(1).label()).isEqualTo("{kanal} Kanalı Teknik Hata Grafiği");
        assertThat(slots.get(4).label()).isEqualTo("{kanal} Kanalı Teknik Hata (Günlük)");
        assertThat(slots.get(5).label()).isEqualTo("{kanal} Kanalı Özet (Aylık)");
        assertThat(slots.subList(4, slots.size())).extracting(Slot::width).containsOnly("full");
        assertThat(slots).hasSize(14);
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
