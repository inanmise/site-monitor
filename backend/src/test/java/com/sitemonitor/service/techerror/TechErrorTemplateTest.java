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
    @DisplayName("varsayılan şablon: özet → veriler (girilen; map yok + kota adet ve açıklaması teknik hata adedinin ardında) → oranlar (otomatik) → anomali → SLA (ekran görüntüsü) → not → yıllık → günlük göstergeler")
    void defaultLayoutMatchesTheRequest() {
        Template t = TechErrorTemplate.defaults("tr");
        assertThat(t.title()).isEqualTo("{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}");
        assertThat(t.sections()).extracting(s -> s.kind())
                .containsExactly("summary", "metrics", "metrics", "issues", "images", "text", "images", "images");
        assertThat(t.sections().get(0).title()).isEqualTo("Yönetici Özeti");
        // Kullanıcı kararı 2026-10-17 (7. sürüm): map yapılmamış ve kota hataları (adet + açıklama) "Verileri" bölümünde,
        // teknik hata adedinin hemen ardında girilir; özet kartına gömülü bölüm YOK (gömme özelliği şablonda duruyor)
        assertThat(t.sections()).allMatch(s -> s.host() == null);
        assertThat(TechErrorTemplate.embeddedSections(t, "exec-summary")).isEmpty();
        assertThat(t.sections()).extracting(s -> s.id()).doesNotContain("unmapped", "quota");
        // Kullanıcı kararı 2026-10-16: üstte girilen adetler, altta otomatik oranlar
        assertThat(t.sections().get(1).title()).isEqualTo("{kanal} Kanalı — {ay} {yil} Verileri");
        assertThat(t.sections().get(1).fields()).extracting(f -> f.id()).containsExactly("total-tx", "error-count",
                "unmapped-count", "unmapped-note", "quota-count", "quota-note", "had-outage", "outage-errors", "sla-rate", "sla-above");
        assertThat(t.sections().get(1).fields()).extracting(f -> f.id(), f -> f.type(), f -> f.label()).contains(
                org.assertj.core.groups.Tuple.tuple("unmapped-count", "number", "Map yapılmamış hata adedi"),
                org.assertj.core.groups.Tuple.tuple("unmapped-note", "textarea", "Map yapılmamış hatalar hakkında açıklama"),
                org.assertj.core.groups.Tuple.tuple("quota-count", "number", "Kota hatası adedi"),
                org.assertj.core.groups.Tuple.tuple("quota-note", "textarea", "Kota hataları hakkında açıklama"));
        // 2026-10-17 (4b): "SLA hedefin üstünde mi?" hedefle otomatik (kullanıcı kararı: alanda hedef + otomatik hesap); diğerleri elle
        assertThat(t.sections().get(1).fields()).filteredOn(f -> !"sla-above".equals(f.id())).allMatch(f -> f.calc() == null);
        var slaRate = t.sections().get(1).fields().stream().filter(f -> "sla-rate".equals(f.id())).findFirst().orElseThrow();
        assertThat(slaRate.target()).isEqualTo(99.95);
        assertThat(slaRate.better()).isEqualTo("higher");
        var slaAbove = t.sections().get(1).fields().stream().filter(f -> "sla-above".equals(f.id())).findFirst().orElseThrow();
        assertThat(slaAbove.calc().kind()).isEqualTo("compare");
        assertThat(slaAbove.calc().of()).isEqualTo("sla-rate");
        // Renkli kutucuklar (kullanıcı kararı: 6 karışık) ve karşılaştırma cümleleri ({d:} fark, {t:} hedef, {dt:} hedefe uzaklık)
        assertThat(t.sections().get(0).tiles()).containsExactly("error-rate", "total-tx", "error-count", "unmapped-count", "quota-count", "sla-rate");
        assertThat(t.sections().get(0).lines()).anyMatch(l -> l.contains("{d:error-count}") && l.contains("{d:error-rate}"))
                .anyMatch(l -> l.contains("{t:sla-rate}") && l.contains("{dt:sla-rate}"));
        // Kullanıcı kararı 2026-10-18: kesinti cevabı özette cümle (iki dal da dolu), kesinti kaynaklı adet cümlesinin hemen önünde
        // — Verileri'nde kesinti sorusu tekrar basılmaz (bölüm PDF / e-postada kalkar)
        for (String lang : List.of("tr", "en")) {
            List<String> lines = TechErrorTemplate.defaults(lang).sections().get(0).lines();
            String outage = "en".equals(lang) ? TechErrorTemplate.OUTAGE_LINE_EN : TechErrorTemplate.OUTAGE_LINE_TR;
            int at = lines.indexOf(outage);
            assertThat(at).as(lang).isPositive();
            assertThat(lines.get(at + 1)).as(lang).contains("{f:outage-errors}");
            assertThat(lines.stream().filter(l -> l.contains("{f:had-outage")).count()).as(lang + " tek kesinti cümlesi").isEqualTo(1);
        }
        assertThat(TechErrorTemplate.OUTAGE_LINE_TR).isEqualTo("{f:had-outage?Dönemde kesinti yaşanmıştır.|Dönemde kesinti yaşanmamıştır.}");
        assertThat(TechErrorTemplate.OUTAGE_LINE_EN).isEqualTo("{f:had-outage?There was an outage in the period.|There was no outage in the period.}");
        assertThat(t.sections().get(2).title()).isEqualTo("{kanal} Kanalı — {ay} {yil} Oranları (otomatik)");
        assertThat(t.sections().get(2).fields()).allMatch(f -> f.calc() != null);
        // Kullanıcı kararı 2026-10-17: anomali adları
        var issues = t.sections().get(3);
        assertThat(issues.title()).isEqualTo("Anormal Artış Gösteren Hatalar");
        assertThat(issues.itemTitle()).isEqualTo("{konu}: Hata Artışı");
        assertThat(issues.descriptionLabel()).isEqualTo("Ne oldu? (zaman, yer, etki)");
        assertThat(issues.customersLabel()).isEqualTo("Etkilenen müşteri sayısı");
        assertThat(issues.errorsLabel()).isEqualTo("Bu konudan kaynaklı teknik hata adedi");
        // Kullanıcı kararı 2026-10-15: SLA tablosu elle girilmez — raporlama aracının tablosunun ekran görüntüsü, tam genişlik
        // (iki kolon); tek görüntü ya da iki ayrı görüntü
        var sla = t.sections().get(4);
        assertThat(sla.title()).isEqualTo("SLA Erişilebilirlik Tablosu");
        assertThat(sla.columns()).isEmpty();
        assertThat(sla.slots()).extracting(Slot::width).containsExactly("full", "full");
        assertThat(t.sections()).noneMatch(s -> "table".equals(s.kind()));

        // Otomatik trend bölümü varsayılanda YOK (kullanıcı kararı 2026-10-16: grafikleri görsel olarak kendileri sağlıyor)
        assertThat(t.sections()).noneMatch(s -> "trend".equals(s.kind()));
        // "Grafikler hakkında" altında YALNIZ iki görsel bölümü (kullanıcı kararı 2026-10-16): son 12 ayın yıllık göstergeleri
        // ve raporun ayının günlük göstergeleri — adlar, sıra ve genişlikler kullanıcının verdiği gibi
        assertThat(t.sections().get(5).title()).isEqualTo("Grafikler hakkında");
        assertThat(t.sections().subList(6, 8)).extracting(s -> s.title()).containsExactly(
                "12 Aylık Özetler - Yıllık Göstergeler", "{ay} {yil} Özetler - Günlük Göstergeler");
        assertThat(t.sections().get(6).slots()).extracting(Slot::label, Slot::width).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Yıllık Operasyon ve Hata Dağılım Özeti (Son 12 Ay)", "half"),
                org.assertj.core.groups.Tuple.tuple("Toplam Operasyon Adetleri (Son 12 Ay)", "half"),
                org.assertj.core.groups.Tuple.tuple("Teknik/Business Hata Oranı Trendi (Son 12 Ay)", "full"),
                org.assertj.core.groups.Tuple.tuple("Kota & Maplenmemiş Hata Adetleri (Son 12 Ay)", "full"),
                org.assertj.core.groups.Tuple.tuple("Teknik Hataların Tüm Operasyonlara Oranı (Son 12 Ay)", "full"),
                org.assertj.core.groups.Tuple.tuple("Bölüm ve Müdürlük Bazlı Toplam Operasyon ve Hata Özet Tablosu (Son 12 Ay)", "full"),
                org.assertj.core.groups.Tuple.tuple("Kanal Bazında Teknik Hata Adetleri (Son 12 Ay)", "half"),
                org.assertj.core.groups.Tuple.tuple("Kanal Bazında Business Hata Adetleri (Son 12 Ay)", "half"));
        assertThat(t.sections().get(7).slots()).extracting(Slot::label, Slot::width).containsExactly(
                org.assertj.core.groups.Tuple.tuple("{ay} {yil} Operasyon ve Hata Dağılım Özeti", "half"),
                org.assertj.core.groups.Tuple.tuple("{ay} {yil} Toplam Operasyon Adetleri (Günlük)", "half"),
                org.assertj.core.groups.Tuple.tuple("{ay} {yil} Müdürlük Bazında Teknik Hata Adetleri", "half"),
                org.assertj.core.groups.Tuple.tuple("{ay} {yil} Müdürlük Bazında Business Hata Adetleri", "half"),
                org.assertj.core.groups.Tuple.tuple("{ay} {yil} Teknik/Business Hata Oranı Trendi (Günlük)", "full"),
                org.assertj.core.groups.Tuple.tuple("{ay} {yil} Kota & Maplenmemiş Hata Adetleri (Günlük)", "full"),
                org.assertj.core.groups.Tuple.tuple("{ay} {yil} Teknik Hataların Tüm Operasyonlara Oranı (Günlük)", "full"));
        // Grafik yuvaları yalnız bu iki bölümde: SLA ekran görüntüleri (2) + 8 + 7
        assertThat(t.allSlots()).hasSize(17);
        // Yıllık bölümün adı zaman tabanını söyler; günlük bölümün adı raporun ayıyla başlar
        assertThat(t.sections().get(6).slots()).extracting(Slot::label).allMatch(l -> l.endsWith("(Son 12 Ay)"));
        assertThat(t.sections().get(7).slots()).extracting(Slot::label).allMatch(l -> l.startsWith("{ay} {yil} "));
        // EN: aynı yapı ve aynı genişlikler
        Template en = TechErrorTemplate.defaults("en");
        assertThat(en.sections().subList(6, 8)).extracting(s -> s.title()).containsExactly(
                "12-Month Summaries - Yearly Indicators", "{month} {year} Summaries - Daily Indicators");
        assertThat(en.sections().get(6).slots()).extracting(Slot::label).allMatch(l -> l.endsWith("(Last 12 Months)"));
        assertThat(en.sections().get(7).slots()).extracting(Slot::label).allMatch(l -> l.startsWith("{month} {year} "));
        assertThat(en.allSlots()).extracting(Slot::width).isEqualTo(t.allSlots().stream().map(Slot::width).toList());
        assertThat(en.sections().get(1).fields()).extracting(f -> f.id()).isEqualTo(t.sections().get(1).fields().stream().map(f -> f.id()).toList());
        assertThat(en.sections().get(6).slots().get(0).label())
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
        // 2026-10-17 varsayılan kalıp
        assertThat(TechErrorTemplate.resolveIssueTitle("{konu}: Hata Artışı", "Kart Limiti Sorgulama"))
                .isEqualTo("Kart Limiti Sorgulama: Hata Artışı");
        assertThat(TechErrorTemplate.resolveIssueTitle("{topic}: Error Increase", "Card limit")).isEqualTo("Card limit: Error Increase");
    }

    @Test
    @DisplayName("gösterim yeri (host, 2026-10-17): yalnız gösterge bölümünde ve aynı şablondaki bir yönetici özetine; katı reddeder, saklı düşürür; JSON'da korunur (4a düzeni — varsayılanda gömülü bölüm yok, özellik duruyor)")
    void hostStrictAndLenient() {
        Template d = TechErrorTestTemplates.hostedDefaults("tr");
        assertThat(TechErrorTemplate.hostOf(d, d.sections().get(1)).id()).isEqualTo("exec-summary");
        assertThat(TechErrorTemplate.hostOf(d, d.sections().get(3))).isNull();                 // Verileri: ayrı bölüm
        assertThat(TechErrorTemplate.hostOf(d, d.sections().get(0))).isNull();                 // özet bölümünün kendisi
        assertThat(TechErrorTemplate.embeddedSections(d, "summary")).isEmpty();                 // gösterge bölümü ev sahibi olamaz
        assertThat(TechErrorTemplate.embeddedSections(d, null)).isEmpty();
        Map<String, Object> raw = TechErrorTemplate.toMap(d);
        assertThat(sectionMap(raw, "unmapped")).containsEntry("host", "exec-summary");
        assertThat(sectionMap(raw, "summary")).doesNotContainKey("host");
        assertThat(sectionMap(raw, "exec-summary")).doesNotContainKey("host");
        // Katı: özet olmayan hedef, şablonda olmayan hedef, gösterge olmayan bölümde host, okunamayan kimlik → 400
        assertInvalid(withHost(raw, "unmapped", "summary"));
        assertInvalid(withHost(raw, "unmapped", "yok-boyle-bolum"));
        assertInvalid(withHost(raw, "trend-note", "exec-summary"));
        assertInvalid(withHost(raw, "unmapped", "Büyük Harf!"));
        // Boş host = ayrı bölüm (anahtar yazılmaz)
        Template separate = TechErrorTemplate.normalize(withHost(raw, "unmapped", "  "));
        assertThat(separate.sections().get(1).host()).isNull();
        assertThat(sectionMap(TechErrorTemplate.toMap(separate), "unmapped")).doesNotContainKey("host");
        assertThat(TechErrorTemplate.embeddedSections(separate, "exec-summary")).extracting(s -> s.id()).containsExactly("quota");
        // Saklı (hoşgörülü): geçersiz bağ düşer, bölüm ve alanları kalır (eski sürümün yazdığı ya da bozulmuş kayıt yine açılır)
        for (String[] bad : List.of(new String[]{"unmapped", "summary"}, new String[]{"unmapped", "yok-boyle-bolum"},
                new String[]{"trend-note", "exec-summary"}, new String[]{"unmapped", "Büyük Harf!"})) {
            Template lenient = TechErrorTemplate.fromStored(TechErrorTemplate.JSON.writeValueAsString(withHost(raw, bad[0], bad[1])));
            var s = lenient.sections().stream().filter(x -> x.id().equals(bad[0])).findFirst().orElseThrow();
            assertThat(s.host()).as(bad[0] + " → " + bad[1]).isNull();
            assertThat(lenient.allFields()).extracting(f -> f.id()).contains("unmapped-count", "unmapped-note", "quota-count");
        }
    }

    @Test
    @DisplayName("anomali etiketleri boş gelirse: istemci (katı) kaydı 2026-10-17 adlarını, saklı (hoşgörülü) okuma eski adları koyar")
    @SuppressWarnings("unchecked")
    void issueLabelFallbacks() {
        for (String lang : List.of("tr", "en")) {
            Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TechErrorTemplate.defaults(lang)));
            List<Map<String, Object>> secs = new ArrayList<>();
            for (Map<String, Object> s : (List<Map<String, Object>>) raw.get("sections")) {
                Map<String, Object> sc = new LinkedHashMap<>(s);
                if ("issues".equals(s.get("kind"))) {
                    for (String k : List.of("item_title", "description_label", "customers_label", "errors_label")) sc.remove(k);
                }
                secs.add(sc);
            }
            raw.put("sections", secs);
            var strict = TechErrorTemplate.normalize(raw).sections().stream().filter(s -> "issues".equals(s.kind())).findFirst().orElseThrow();
            var today = TechErrorTemplate.defaults(lang).sections().stream().filter(s -> "issues".equals(s.kind())).findFirst().orElseThrow();
            assertThat(strict).as(lang).isEqualTo(today);
            var lenient = TechErrorTemplate.fromStored(TechErrorTemplate.JSON.writeValueAsString(raw)).sections().stream()
                    .filter(s -> "issues".equals(s.kind())).findFirst().orElseThrow();
            assertThat(lenient.itemTitle()).isEqualTo("en".equals(lang) ? "{topic} issue" : "{konu} Konusu ile yaşanan problem");
            assertThat(lenient.descriptionLabel()).isEqualTo("en".equals(lang) ? "When, where and how did it happen?" : "Ne zaman, nerede, nasıl gerçekleşti?");
            assertThat(lenient.errorsLabel()).isEqualTo("en".equals(lang) ? "Technical errors caused by this issue" : "Bu sorundan kaynaklı teknik hata adedi");
            assertThat(lenient.customersLabel()).isEqualTo(today.customersLabel());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sectionMap(Map<String, Object> raw, String id) {
        for (Map<String, Object> s : (List<Map<String, Object>>) raw.get("sections")) if (id.equals(s.get("id"))) return s;
        throw new AssertionError("bölüm yok: " + id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withHost(Map<String, Object> raw, String sectionId, String host) {
        Map<String, Object> copy = new LinkedHashMap<>(raw);
        List<Map<String, Object>> secs = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) raw.get("sections")) {
            Map<String, Object> sc = new LinkedHashMap<>(s);
            if (sectionId.equals(s.get("id"))) sc.put("host", host);
            secs.add(sc);
        }
        copy.put("sections", secs);
        return copy;
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
        // 2026-10-17 (4b): "SLA hedefin üstünde mi?" hedef karşılaştırmasıyla HESAPLANIR → bağ hedefi olamaz (inceleme 2026-10-16)
        assertInvalid(withSkip(raw, "sla-rate", "sla-above"));
        assertInvalid(withSkip(raw, "had-outage", "sla-above"));
        // geçerli bağ: elle cevaplanan evet/hayır sorusuna, zincirsiz (aynı soruya birden çok alan bağlanabilir)
        Template ok = TechErrorTemplate.normalize(withSkip(raw, "sla-rate", "had-outage"));
        assertThat(ok.allFields().stream().filter(f -> f.id().equals("sla-rate")).findFirst().orElseThrow().skipIfNo())
                .isEqualTo("had-outage");
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

    @Test
    @DisplayName("2026-10-17 (4b): hedef yalnız yüzde alanında, %0–100 ve yönlü; 'daha iyi' yalnız sayı / yüzde; kutucuk ≤ 6, tekil, varlığı denetlenmez; karşılaştırma yalnız evet/hayır alanında — katı 400, saklıda düşer, toMap geri yazar")
    void targetBetterAndTilesRules() {
        Map<String, Object> raw = TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr"));
        Template round = TechErrorTemplate.normalize(raw);
        assertThat(field(round, "sla-rate").target()).isEqualTo(99.95);
        assertThat(field(round, "sla-rate").better()).isEqualTo("higher");
        assertThat(round.sections().get(0).tiles()).hasSize(6);
        // Hedef: sayı alanında / aralık dışı / yönsüz → katı 400; saklıda hedef düşer, alan kalır
        assertInvalid(withField(raw, "total-tx", "target", 50));
        assertInvalid(withField(raw, "sla-rate", "target", 101));
        assertInvalid(withField(raw, "sla-rate", "target", -1));
        assertInvalid(withField(raw, "sla-rate", "target", "yüksek"));
        assertInvalid(withField(raw, "sla-rate", "better", null));                 // hedef var, yön yok
        assertInvalid(withField(raw, "sla-rate", "better", "none"));               // hedef var, "yön yok" da yönsüz (inceleme)
        Template noneDir = stored(withField(raw, "sla-rate", "better", "none"));
        assertThat(field(noneDir, "sla-rate").target()).isNull();
        assertThat(field(noneDir, "sla-rate").better()).isEqualTo("none");
        assertThat(field(stored(withField(raw, "total-tx", "target", 50)), "total-tx").target()).isNull();
        assertThat(field(stored(withField(raw, "sla-rate", "target", 101)), "sla-rate").target()).isNull();
        Template noBetter = stored(withField(raw, "sla-rate", "better", null));
        assertThat(field(noBetter, "sla-rate").target()).isNull();
        assertThat(field(noBetter, "sla-rate").better()).isNull();
        // "Daha iyi": tanınmayan değer / evet-hayır alanında → katı 400, saklıda düşer; sayı alanında yönsüz hedefsiz olur
        assertInvalid(withField(raw, "sla-rate", "better", "up"));
        assertInvalid(withField(raw, "had-outage", "better", "higher"));
        assertThat(field(stored(withField(raw, "had-outage", "better", "higher")), "had-outage").better()).isNull();
        assertThat(field(TechErrorTemplate.normalize(withField(raw, "error-count", "better", "lower")), "error-count").better()).isEqualTo("lower");
        // Kutucuklar: 7 → katı 400, saklıda ilk 6; yinelenen tekilleşir; olmayan kimlik kalır (çizimde atlanır); bozuk kimlik 400
        List<Object> seven = List.of("a", "b", "c", "d", "e", "f", "g");
        assertInvalid(withTiles(raw, seven));
        assertThat(stored(withTiles(raw, seven)).sections().get(0).tiles()).containsExactly("a", "b", "c", "d", "e", "f");
        assertThat(TechErrorTemplate.normalize(withTiles(raw, List.of("error-rate", "error-rate", "ghost"))).sections().get(0).tiles())
                .containsExactly("error-rate", "ghost");
        assertInvalid(withTiles(raw, List.of("Büyük Harf")));
        assertInvalid(withTiles(raw, "error-rate"));
        // Boş liste yazılmaz: kutucuksuz şablonun belgesi 4b öncesiyle aynı
        @SuppressWarnings("unchecked") Map<String, Object> first = (Map<String, Object>) ((List<Object>) TechErrorTemplate.toMap(
                TechErrorTemplate.normalize(withTiles(raw, List.of()))).get("sections")).get(0);
        assertThat(first).doesNotContainKey("tiles");
        // Hedef karşılaştırması: yalnız evet/hayır alanında ve 'of' zorunlu; başvurunun türü / hedefi kayıtta denetlenmez (silme kilitlemez)
        assertInvalid(withField(raw, "sla-rate", "calc", Map.of("kind", "compare", "of", "error-rate")));
        assertInvalid(withField(raw, "sla-above", "calc", Map.of("kind", "compare")));
        assertInvalid(withField(raw, "sla-above", "calc", Map.of("kind", "compare", "of", "sla-above")));
        Template loose = TechErrorTemplate.normalize(withField(raw, "sla-above", "calc", Map.of("kind", "compare", "of", "total-tx")));
        assertThat(field(loose, "sla-above").calc().of()).isEqualTo("total-tx");
        assertThat(field(stored(withField(raw, "sla-rate", "calc", Map.of("kind", "compare", "of", "error-rate"))), "sla-rate").calc()).isNull();
        // EN varsayılan aynı yapıyı taşır
        Template en = TechErrorTemplate.defaults("en");
        assertThat(field(en, "sla-rate").target()).isEqualTo(99.95);
        assertThat(field(en, "sla-above").calc().kind()).isEqualTo("compare");
        assertThat(en.sections().get(0).tiles()).isEqualTo(round.sections().get(0).tiles());
    }

    private static TechErrorTemplate.Field field(Template t, String id) {
        return t.allFields().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    private static Template stored(Map<String, Object> raw) {
        return TechErrorTemplate.fromStored(TechErrorTemplate.JSON.writeValueAsString(raw));
    }

    /** Alan haritasında bir anahtarı değiştirir (null = anahtarı siler). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> withField(Map<String, Object> raw, String fieldId, String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(raw);
        List<Map<String, Object>> secs = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) raw.get("sections")) {
            Map<String, Object> sc = new LinkedHashMap<>(s);
            if (s.get("fields") instanceof List<?> fl) {
                List<Map<String, Object>> fs = new ArrayList<>();
                for (Object o : fl) {
                    Map<String, Object> fm = new LinkedHashMap<>((Map<String, Object>) o);
                    if (fieldId.equals(fm.get("id"))) {
                        if (value == null) fm.remove(key);
                        else fm.put(key, value);
                    }
                    fs.add(fm);
                }
                sc.put("fields", fs);
            }
            secs.add(sc);
        }
        copy.put("sections", secs);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withTiles(Map<String, Object> raw, Object tiles) {
        Map<String, Object> copy = new LinkedHashMap<>(raw);
        List<Map<String, Object>> secs = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) raw.get("sections")) {
            Map<String, Object> sc = new LinkedHashMap<>(s);
            if ("summary".equals(s.get("kind"))) sc.put("tiles", tiles);
            secs.add(sc);
        }
        copy.put("sections", secs);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withIssueKeys(Map<String, Object> raw, Map<String, Object> put, List<String> remove) {
        Map<String, Object> copy = new LinkedHashMap<>(raw);
        List<Map<String, Object>> secs = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) raw.get("sections")) {
            Map<String, Object> sc = new LinkedHashMap<>(s);
            if ("issues".equals(s.get("kind"))) {
                for (String k : remove) sc.remove(k);
                sc.putAll(put);
            }
            secs.add(sc);
        }
        copy.put("sections", secs);
        return copy;
    }

    private static TechErrorTemplate.Section issuesOf(Template t) {
        return t.sections().stream().filter(s -> "issues".equals(s.kind())).findFirst().orElseThrow();
    }

    static final List<String> ANOMALY_KEYS = List.of("ask_time", "time_label", "ask_outage", "outage_label", "outage_id_label",
            "ask_impact", "impact_label", "no_impact_display", "allow_images", "ratio_of");

    @Test
    @DisplayName("anomali soruları (2026-10-18): varsayılanda hepsi açık (TR / EN etiketler, pay 'error-count'); katı kayıt bozuk aç/kapa, tanınmayan gösterim ve okunamayan alan kimliğini reddeder, şablonda olmayan alanı kabul eder; boş etiket varsayılana döner; saklı okuma bozuğu düşürür")
    void anomalyOptionsStrictAndLenient() {
        for (String lang : List.of("tr", "en")) {
            boolean en = "en".equals(lang);
            var o = issuesOf(TechErrorTemplate.defaults(lang)).opts();
            assertThat(o.askTime()).isTrue();
            assertThat(o.askOutage()).isTrue();
            assertThat(o.askImpact()).isTrue();
            assertThat(o.allowImages()).isTrue();
            assertThat(o.noImpactDisplay()).isEqualTo("hide_count");
            assertThat(o.ratioOf()).isEqualTo("error-count");
            assertThat(o.outageLabel()).isEqualTo(en ? "Caused by an outage?" : "Kesinti kaynaklı mı?");
            assertThat(o.outageIdLabel()).isEqualTo(en ? "Outage ID" : "Kesinti ID");
            assertThat(o.impactLabel()).isEqualTo(en ? "Was there customer impact?" : "Müşteri etkisi var mı?");
            assertThat(o.legacy()).isFalse();
            assertThat(o.hideTopic()).isFalse();
        }
        Map<String, Object> ok = TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr"));
        // Gidiş-dönüş: JSON'da yalnız açık sorular (ve etiketleri) yazılır
        assertThat(TechErrorTemplate.toJson(TechErrorTemplate.normalize(ok))).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        assertThat(sectionMap(ok, "issues")).containsEntry("ask_time", true).containsEntry("ratio_of", "error-count")
                .containsEntry("no_impact_display", "hide_count");

        // Soruların hiçbiri yoksa eski düzen: seçenek yok, JSON'a anahtar yazılmaz (eski şablon bayt bayt aynı)
        Map<String, Object> none = withIssueKeys(ok, Map.of(), ANOMALY_KEYS);
        var legacy = issuesOf(TechErrorTemplate.normalize(none));
        assertThat(legacy.options()).isNull();
        assertThat(legacy.opts().legacy()).isTrue();
        assertThat(TechErrorTemplate.toMap(TechErrorTemplate.normalize(none)).toString()).doesNotContain("ask_time", "ratio_of", "allow_images");
        // Hepsi açıkça false: yine eski düzen — ama AYARLANMIŞ (inceleme 2026-10-18): seçenek boş değil, anahtarlar false yazılır,
        // göç "hiç ayarlanmamış" bölümle karıştırıp soruları yeniden açmaz
        Map<String, Object> allFalse = withIssueKeys(ok, Map.of("ask_time", false, "ask_outage", false, "ask_impact", false,
                "allow_images", false), List.of("ratio_of"));
        var off = issuesOf(TechErrorTemplate.normalize(allFalse));
        assertThat(off.options()).isNotNull();
        assertThat(off.opts().legacy()).isTrue();
        assertThat(sectionMap(TechErrorTemplate.toMap(TechErrorTemplate.normalize(allFalse)), "issues"))
                .containsEntry("ask_time", false).containsEntry("ask_outage", false).containsEntry("ask_impact", false)
                .containsEntry("allow_images", false).doesNotContainKeys("time_label", "outage_label", "impact_label", "ratio_of");

        // Boş etiket → bugünkü varsayılan; kapalı sorunun etiketi saklanmaz
        Map<String, Object> blank = withIssueKeys(ok, Map.of("outage_label", "", "impact_label", " ", "ask_time", false,
                "time_label", "Kendi etiketim"), List.of());
        var b = issuesOf(TechErrorTemplate.normalize(blank)).opts();
        assertThat(b.outageLabel()).isEqualTo("Kesinti kaynaklı mı?");
        assertThat(b.impactLabel()).isEqualTo("Müşteri etkisi var mı?");
        assertThat(b.askTime()).isFalse();
        assertThat(b.timeLabel()).isNull();
        // Kendi etiketi ve "konuyu rapora alma" kalır
        var own = issuesOf(TechErrorTemplate.normalize(withIssueKeys(ok, Map.of("outage_id_label", "Olay numarası",
                "no_impact_display", "hide_topic"), List.of()))).opts();
        assertThat(own.outageIdLabel()).isEqualTo("Olay numarası");
        assertThat(own.hideTopic()).isTrue();
        // Müşteri etkisi sorulmuyorsa gösterim yok sayılır
        assertThat(issuesOf(TechErrorTemplate.normalize(withIssueKeys(ok, Map.of("ask_impact", false, "no_impact_display", "hide_topic"),
                List.of()))).opts().noImpactDisplay()).isNull();
        // Pay alanı şablonda yok / sayı değil: kayıt REDDEDİLMEZ (düzenleyici uyarır — alan silmek şablonu kilitlemesin)
        assertThat(issuesOf(TechErrorTemplate.normalize(withIssueKeys(ok, Map.of("ratio_of", "olmayan-alan"), List.of()))).opts().ratioOf())
                .isEqualTo("olmayan-alan");
        assertThat(issuesOf(TechErrorTemplate.normalize(withIssueKeys(ok, Map.of("ratio_of", "error-rate"), List.of()))).opts().ratioOf())
                .isEqualTo("error-rate");
        // Boş pay alanı = pay sorulmaz
        assertThat(issuesOf(TechErrorTemplate.normalize(withIssueKeys(ok, Map.of("ratio_of", ""), List.of()))).opts().ratioOf()).isNull();

        // Katı: bozuk aç/kapa, tanınmayan gösterim, okunamayan kimlik, uzun etiket
        assertInvalid(withIssueKeys(ok, Map.of("ask_time", "evet"), List.of()));
        assertInvalid(withIssueKeys(ok, Map.of("allow_images", 1), List.of()));
        assertInvalid(withIssueKeys(ok, Map.of("no_impact_display", "gizle"), List.of()));
        assertInvalid(withIssueKeys(ok, Map.of("ratio_of", "Kötü Kimlik!"), List.of()));
        assertInvalid(withIssueKeys(ok, Map.of("outage_label", "x".repeat(TechErrorTemplate.MAX_ISSUE_LABEL + 1)), List.of()));

        // Hoşgörülü: aynı bozukluklar düşer, şablon açılır
        Map<String, Object> broken = withIssueKeys(ok, Map.of("ask_time", "evet", "no_impact_display", "gizle", "ratio_of", "Kötü!"), List.of());
        var lenient = issuesOf(TechErrorTemplate.fromStored(TechErrorTemplate.JSON.writeValueAsString(broken))).opts();
        assertThat(lenient.askTime()).isFalse();
        assertThat(lenient.noImpactDisplay()).isEqualTo("hide_count");
        assertThat(lenient.ratioOf()).isNull();
        assertThat(lenient.askOutage()).isTrue();

        // Konu görseli yuva kimliği
        assertThat(TechErrorTemplate.issueSlotId("issues", "i-ab12", 2)).isEqualTo("iss:issues:i-ab12:2");
        var is = TechErrorTemplate.parseIssueSlot("iss:issues:i-ab12:2");
        assertThat(is.sectionId()).isEqualTo("issues");
        assertThat(is.issueId()).isEqualTo("i-ab12");
        assertThat(is.n()).isEqualTo(2);
        for (String bad : List.of("iss:issues:i-1:3", "iss:issues:i-1:0", "iss:issues:i-1", "iss:Bad:i-1:1", "sla-shot-1",
                "iss:issues:i-1:1:x", "iss::i-1:1")) {
            assertThat(TechErrorTemplate.parseIssueSlot(bad)).as(bad).isNull();
        }
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
