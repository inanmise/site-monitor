package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.techerror.TechErrorTemplate.Calc;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Slot;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mevcut kanal şablonlarının otomatik oranlara göçü (2026-10-16, kullanıcı kararı: "otomatik güncellensin"). Saf dönüşüm
 * + H2'de gerçek SQL ile tek seferlik iş (nişan, sürüm geçmişi, açılmış rapora dokunmama).
 */
class TechErrorCalcMigrationTest {

    /** 2026-10-16 öncesi varsayılan şablonun gösterge / özet kısmı (oranlar elle girilir). */
    static Map<String, Object> oldDefault(String lang) {
        boolean en = "en".equals(lang);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", lang);
        raw.put("title", en ? "{channel} Channel Technical Error Messages - {month} {year}" : "{kanal} Kanalı Teknik Hata Mesajları - {ay} {yil}");
        List<Object> sections = new ArrayList<>();
        sections.add(Map.of("id", "exec-summary", "kind", "summary", "title", en ? "Executive Summary" : "Yönetici Özeti", "lines", en
                ? List.of("Based on {month} {year} figures, the {channel} channel's technical error rate was {f:error-rate}.",
                        "The number of unmapped errors was {f:unmapped-count}.", "The number of quota errors was {f:quota-count}.",
                        "{f:outage-errors} technical errors were caused by outages, {f:outage-share} of all errors.")
                : List.of("{kanal} Kanalı {ay} {yil} verilerine göre teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.",
                        "Map yapılmamış hata adedi {f:unmapped-count} olarak kaydedilmiştir.",
                        "Kota hatası adedi {f:quota-count} olarak kaydedilmiştir.",
                        "Teknik hataların {f:outage-errors} adedi kesinti kaynaklı olup toplam hataların {f:outage-share} kadarını oluşturmaktadır.")));
        List<Object> fields = new ArrayList<>();
        fields.add(field("error-rate", en ? "Technical error rate" : "Teknik hata oranı", "percent",
                en ? "Based on this month's figures" : "İlgili ay/yıl verilerine göre", null));
        fields.add(field("had-outage", en ? "Was there an outage in the period?" : "Dönemde kesinti oldu mu?", "yesno", null, null));
        fields.add(field("error-rate-excl", en ? "Technical error rate excluding outage days" : "Kesinti günleri hariç teknik hata oranı",
                "percent", en ? "With errors from outage days left out" : "Kesintilerin olduğu günlerde alınan hatalar çıkarıldığında",
                "had-outage"));
        fields.add(field("total-tx", en ? "Total transactions" : "Toplam işlem adedi", "number", null, null));
        fields.add(field("tx-increase", en ? "Increase compared with the previous month?" : "Önceki aya göre artış var mı?", "yesno", null, null));
        fields.add(field("tx-increase-pct", en ? "Change compared with the previous month" : "Önceki aya göre değişim oranı", "percent",
                en ? "Enter the size of the increase or decrease without a sign (e.g. 3.5); the question above sets the direction"
                        : "Artış ya da azalış oranını işaretsiz yazın (ör. 3,5); yönü yukarıdaki soru belirler", null));
        fields.add(field("sla-rate", en ? "SLA rate of critical functions" : "Kritik fonksiyonların SLA oranı", "percent", null, null));
        fields.add(field("error-count", en ? "Technical errors in the month" : "Ay içindeki teknik hata adedi", "number", null, null));
        fields.add(field("outage-errors", en ? "Technical errors caused by outages" : "Kesinti kaynaklı teknik hata adedi", "number", null,
                "had-outage"));
        fields.add(field("outage-share", en ? "Share of outage-caused errors in all errors" : "Kesinti kaynaklı hataların toplam hataya oranı",
                "percent", null, "had-outage"));
        sections.add(Map.of("id", "summary", "kind", "metrics", "title", "{kanal}", "fields", fields));
        sections.add(Map.of("id", "unmapped", "kind", "metrics", "title", "Map", "fields", List.of(
                field("unmapped-count", "Map yapılmamış hata adedi", "number", null, null),
                field("unmapped-note", "Açıklama", "textarea", null, null))));
        sections.add(Map.of("id", "quota", "kind", "metrics", "title", "Kota", "fields", List.of(
                field("quota-count", "Kota hatası adedi", "number", null, null),
                field("quota-note", "Açıklama", "textarea", null, null))));
        raw.put("sections", sections);
        return raw;
    }

    static Map<String, Object> field(String id, String label, String type, String hint, String skip) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("type", type);
        if (hint != null) m.put("hint", hint);
        if (skip != null) m.put("skip_if_no", skip);
        return m;
    }

    static Field f(Template t, String id) {
        return t.allFields().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
    }

    static Section section(Template t, String id) {
        return t.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("eski varsayılan (TR): oranlar hesaplanır, yeni oranlar eklenir, girilenler üstte; eski ipucu / ad / cümle yenilenir; tekrar uygulanmaz")
    void oldDefaultTr() {
        Template t = TechErrorTemplate.normalize(oldDefault("tr"));
        Template m = TechErrorCalcMigration.migrate(t).orElseThrow();
        assertThat(f(m, "error-rate").calc()).isEqualTo(Calc.ratio("error-count", null, "total-tx"));
        assertThat(f(m, "error-rate-excl").calc()).isEqualTo(Calc.ratio("error-count", "outage-errors", "total-tx"));
        assertThat(f(m, "outage-share").calc()).isEqualTo(Calc.ratio("outage-errors", null, "error-count"));
        assertThat(f(m, "tx-increase-pct").calc()).isEqualTo(Calc.change("total-tx"));
        assertThat(f(m, "tx-increase").calc()).isEqualTo(Calc.increase("total-tx"));
        assertThat(f(m, "unmapped-rate").calc()).isEqualTo(Calc.ratio("unmapped-count", null, "error-count"));
        assertThat(f(m, "quota-rate").calc()).isEqualTo(Calc.ratio("quota-count", null, "error-count"));
        assertThat(f(m, "sla-rate").calc()).isNull();                                    // SLA elle kalır
        // Eski varsayılan ipucu / ad yenilendi; muafiyet bağı korundu
        assertThat(f(m, "error-rate").hint()).isNull();
        assertThat(f(m, "tx-increase-pct").hint()).isEqualTo(TechErrorTemplate.DEFAULT_HINT_CHANGE_TR);
        assertThat(f(m, "tx-increase").hint()).isEqualTo(TechErrorTemplate.DEFAULT_HINT_INCREASE_TR);
        assertThat(f(m, "outage-share").label()).isEqualTo("Kesinti kaynaklı hataların teknik hatalara oranı");
        assertThat(f(m, "error-rate-excl").skipIfNo()).isEqualTo("had-outage");
        // Girilenler üstte, hesaplananlar altta; yeni oranlar teknik hata oranının bölümünde
        assertThat(section(m, "summary").fields()).extracting(Field::id).containsExactly("had-outage", "total-tx", "sla-rate",
                "error-count", "outage-errors", "error-rate", "error-rate-excl", "tx-increase", "tx-increase-pct", "outage-share",
                "unmapped-rate", "quota-rate");
        assertThat(section(m, "unmapped").fields()).extracting(Field::id).containsExactly("unmapped-count", "unmapped-note");
        // Özet: yalnız eski varsayılan cümleler yeni oranlarla
        assertThat(section(m, "exec-summary").lines()).containsExactly(
                "{kanal} Kanalı {ay} {yil} verilerine göre teknik hata oranı {f:error-rate} olarak gerçekleşmiştir.",
                "Map yapılmamış hata adedi {f:unmapped-count} olup teknik hataların {f:unmapped-rate} kadarını oluşturmaktadır.",
                "Kota hatası adedi {f:quota-count} olup teknik hataların {f:quota-rate} kadarını oluşturmaktadır.",
                "Teknik hataların {f:outage-errors} adedi kesinti kaynaklı olup teknik hataların {f:outage-share} kadarını oluşturmaktadır.");
        // Göçmüş şablon katı doğrulamadan geçer; ikinci uygulama değişiklik üretmez
        TechErrorTemplate.normalize(TechErrorTemplate.toMap(m));
        assertThat(TechErrorCalcMigration.migrate(m)).isEmpty();
    }

    @Test
    @DisplayName("varsayılan otomatik trend bölümü kaldırılır (kullanıcı kararı 2026-10-16); ekibin kendi eklediği trend bölümü kalır")
    void defaultTrendRemoved() {
        Map<String, Object> raw = oldDefault("tr");
        @SuppressWarnings("unchecked") List<Object> secs = new ArrayList<>((List<Object>) raw.get("sections"));
        secs.add(Map.of("id", "auto-trend", "kind", "trend", "title", "Otomatik Trendler (Son 12 Ay)",
                "series", List.of("error-rate", "total-tx")));
        secs.add(Map.of("id", "trend-ekip", "kind", "trend", "title", "Bizim trendimiz", "series", List.of("total-tx")));
        raw.put("sections", secs);
        Template m = TechErrorCalcMigration.migrate(TechErrorTemplate.normalize(raw)).orElseThrow();
        assertThat(m.sections()).extracting(Section::id).doesNotContain("auto-trend").contains("trend-ekip");
        // Yalnız trend bölümü kaldırılması gereken (oranları zaten hesaplanan) şablon da göçer
        Map<String, Object> onlyTrend = new LinkedHashMap<>(TechErrorTemplate.toMap(TechErrorTestTemplates.withTrend("tr")));
        Template m2 = TechErrorCalcMigration.migrate(TechErrorTemplate.normalize(onlyTrend)).orElseThrow();
        assertThat(TechErrorTemplate.toJson(m2)).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
    }

    @Test
    @DisplayName("inceleme 2026-10-16: başka alanın 'Hayır ise sorma' sorusu olan artış alanı hesaplanır hâle getirilmez; bölümde tek yer varsa yalnız ilk yeni oran eklenir")
    void skipTargetAndPartialRoom() {
        Map<String, Object> raw = oldDefault("tr");
        @SuppressWarnings("unchecked") List<Object> secs = new ArrayList<>((List<Object>) raw.get("sections"));
        @SuppressWarnings("unchecked") Map<String, Object> summary = new LinkedHashMap<>((Map<String, Object>) secs.get(1));
        @SuppressWarnings("unchecked") List<Object> fields = new ArrayList<>((List<Object>) summary.get("fields"));
        fields.add(field("artis-notu", "Artışın nedeni", "text", null, "tx-increase"));
        while (fields.size() < TechErrorTemplate.MAX_FIELDS - 1) fields.add(field("ek-" + fields.size(), "Ek " + fields.size(), "text", null, null));
        summary.put("fields", fields);
        secs.set(1, summary);
        raw.put("sections", secs);
        Template m = TechErrorCalcMigration.migrate(TechErrorTemplate.normalize(raw)).orElseThrow();
        assertThat(f(m, "tx-increase").calc()).isNull();
        assertThat(f(m, "artis-notu").skipIfNo()).isEqualTo("tx-increase");
        assertThat(f(m, "tx-increase-pct").calc()).isNotNull();                         // değişim oranı yine hesaplanır
        assertThat(f(m, "unmapped-rate")).isNotNull();
        assertThat(f(m, "quota-rate")).isNull();
        assertThat(section(m, "summary").fields()).hasSize(TechErrorTemplate.MAX_FIELDS);
        // Eklenmeyen orana bağlı özet cümlesi değişmez; eklenene bağlı olan değişir
        assertThat(section(m, "exec-summary").lines()).contains("Kota hatası adedi {f:quota-count} olarak kaydedilmiştir.")
                .contains("Map yapılmamış hata adedi {f:unmapped-count} olup teknik hataların {f:unmapped-rate} kadarını oluşturmaktadır.");
    }

    @Test
    @DisplayName("EN şablon: İngilizce ipucu, ad ve özet cümleleri")
    void oldDefaultEn() {
        Template m = TechErrorCalcMigration.migrate(TechErrorTemplate.normalize(oldDefault("en"))).orElseThrow();
        assertThat(f(m, "tx-increase-pct").hint()).isEqualTo(TechErrorTemplate.DEFAULT_HINT_CHANGE_EN);
        assertThat(f(m, "outage-share").label()).isEqualTo("Share of outage-caused errors in technical errors");
        assertThat(f(m, "unmapped-rate").label()).isEqualTo("Share of unmapped errors in technical errors");
        assertThat(section(m, "exec-summary").lines()).contains(
                "There were {f:unmapped-count} unmapped errors, {f:unmapped-rate} of the technical errors.");
    }

    @Test
    @DisplayName("ekibin yazdığı ipucu, ad ve cümle korunur; girdisi olmayan oran dönüştürülmez; türü farklı alan dokunulmaz")
    void teamCustomisationsKept() {
        Map<String, Object> raw = oldDefault("tr");
        @SuppressWarnings("unchecked") List<Object> secs = new ArrayList<>((List<Object>) raw.get("sections"));
        @SuppressWarnings("unchecked") Map<String, Object> summary = new LinkedHashMap<>((Map<String, Object>) secs.get(1));
        @SuppressWarnings("unchecked") List<Object> fields = new ArrayList<>((List<Object>) summary.get("fields"));
        fields.set(0, field("error-rate", "Hata oranı (bizim tanımımız)", "percent", "Ekibin kendi açıklaması", null));
        fields.removeIf(x -> "total-tx".equals(((Map<?, ?>) x).get("id")));             // toplam işlem kaldırılmış
        fields.replaceAll(x -> "outage-share".equals(((Map<?, ?>) x).get("id"))
                ? field("outage-share", "Kesinti payı", "number", null, "had-outage") : x);   // türü sayıya çevrilmiş
        summary.put("fields", fields);
        secs.set(1, summary);
        secs.set(0, Map.of("id", "exec-summary", "kind", "summary", "title", "Özet",
                "lines", List.of("Map yapılmamış hata adedimiz {f:unmapped-count}.")));
        raw.put("sections", secs);
        Template m = TechErrorCalcMigration.migrate(TechErrorTemplate.normalize(raw)).orElseThrow();
        assertThat(f(m, "error-rate").calc()).isNull();                                   // toplam işlem yok → elle kalır
        assertThat(f(m, "error-rate").label()).isEqualTo("Hata oranı (bizim tanımımız)");
        assertThat(f(m, "error-rate").hint()).isEqualTo("Ekibin kendi açıklaması");
        assertThat(f(m, "tx-increase-pct").calc()).isNull();
        assertThat(f(m, "outage-share").calc()).isNull();
        assertThat(f(m, "outage-share").label()).isEqualTo("Kesinti payı");
        assertThat(f(m, "unmapped-rate")).isNotNull();                                    // girdileri var → eklenir
        assertThat(section(m, "exec-summary").lines()).containsExactly("Map yapılmamış hata adedimiz {f:unmapped-count}.");
    }

    @Test
    @DisplayName("yeni varsayılan şablonda yapılacak bir şey yok; bölüm dolu (30 alan) ise yeni oran eklenmez, dönüşüm yine yapılır")
    void nothingToDoAndFullSection() {
        assertThat(TechErrorCalcMigration.migrate(TechErrorTemplate.defaults("tr"))).isEmpty();
        assertThat(TechErrorCalcMigration.migrate(TechErrorTemplate.defaults("en"))).isEmpty();

        Map<String, Object> raw = oldDefault("tr");
        @SuppressWarnings("unchecked") List<Object> secs = new ArrayList<>((List<Object>) raw.get("sections"));
        @SuppressWarnings("unchecked") Map<String, Object> summary = new LinkedHashMap<>((Map<String, Object>) secs.get(1));
        @SuppressWarnings("unchecked") List<Object> fields = new ArrayList<>((List<Object>) summary.get("fields"));
        for (int i = fields.size(); i < TechErrorTemplate.MAX_FIELDS; i++) fields.add(field("extra-" + i, "Ek " + i, "text", null, null));
        summary.put("fields", fields);
        secs.set(1, summary);
        raw.put("sections", secs);
        Template m = TechErrorCalcMigration.migrate(TechErrorTemplate.normalize(raw)).orElseThrow();
        assertThat(section(m, "summary").fields()).hasSize(TechErrorTemplate.MAX_FIELDS);
        assertThat(f(m, "unmapped-rate")).isNull();
        assertThat(f(m, "error-rate").calc()).isNotNull();
        // Eklenemeyen orana bağlı cümle değişmez
        assertThat(section(m, "exec-summary").lines()).contains("Map yapılmamış hata adedi {f:unmapped-count} olarak kaydedilmiştir.");
    }

    /** Bugünkü varsayılan, ama grafik bölümleri (SLA ekran görüntüsü ve "Grafikler hakkında"dan sonra) eski varsayılanınki. */
    static Template withOldCharts(String lang, List<Section> old) {
        Template d = TechErrorTemplate.defaults(lang);
        List<Section> secs = new ArrayList<>();
        for (Section s : d.sections()) if (!s.id().startsWith("charts")) secs.add(s);
        secs.addAll(old);
        return TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(d.lang(), d.title(), List.copyOf(secs))));
    }

    @Test
    @DisplayName("grafikler (kullanıcı kararı 2026-10-16): eski varsayılanın dört bölümü (TR / EN) yeni iki bölümle değişir — SLA kalır, sonuç bugünkü varsayılanın aynısı; tekrar uygulanmaz")
    void oldChartsV2Replaced() {
        for (String lang : List.of("tr", "en")) {
            Template old = withOldCharts(lang, TechErrorCalcMigration.oldDefaultCharts(2, "en".equals(lang)));
            assertThat(old.allSlots()).hasSize(2 + 22);
            Template m = TechErrorCalcMigration.migrate(old).orElseThrow();
            assertThat(TechErrorTemplate.toJson(m)).as(lang).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults(lang)));
            assertThat(m.sections()).extracting(Section::id).contains("sla-shots", "charts", "charts-daily")
                    .doesNotContain("charts-monthly", "charts-distribution");
            TechErrorTemplate.normalize(TechErrorTemplate.toMap(m));
            assertThat(TechErrorCalcMigration.migrate(m)).isEmpty();
        }
    }

    @Test
    @DisplayName("grafikler: ilk sürümün tek 'Grafikler' bölümü de değişir; yeni bölümler eskisinin yerine (sonraki bölümler yerinde kalır)")
    void oldChartsV1Replaced() {
        for (String lang : List.of("tr", "en")) {
            Template d = TechErrorTemplate.defaults(lang);
            List<Section> secs = new ArrayList<>();
            for (Section s : d.sections()) if (!s.id().startsWith("charts")) secs.add(s);
            int slaAt = -1;
            for (int i = 0; i < secs.size(); i++) if ("sla-shots".equals(secs.get(i).id())) slaAt = i;
            secs.add(slaAt, TechErrorCalcMigration.oldDefaultCharts(1, "en".equals(lang)).get(0));   // SLA bölümünden önce
            Template old = TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(d.lang(), d.title(), List.copyOf(secs))));
            Template m = TechErrorCalcMigration.migrate(old).orElseThrow();
            assertThat(m.sections()).extracting(Section::id).containsSubsequence("rates", "charts", "charts-daily", "sla-shots", "trend-note");
            assertThat(section(m, "charts").slots()).isEqualTo(section(TechErrorTemplate.defaults(lang), "charts").slots());
            assertThat(section(m, "charts-daily").slots()).isEqualTo(section(TechErrorTemplate.defaults(lang), "charts-daily").slots());
            assertThat(TechErrorCalcMigration.migrate(m)).isEmpty();
        }
    }

    @Test
    @DisplayName("grafikler: ekibin değiştirdiği bölümlere (tek yuva adı, genişliği, bölüm başlığı) dokunulmaz; bölümlerin sırası önemsiz")
    void changedChartsKept() {
        List<Section> v2 = TechErrorCalcMigration.oldDefaultCharts(2, false);
        // tek yuvanın adı değişmiş
        List<Section> renamed = new ArrayList<>(v2);
        Section monthly = renamed.get(1);
        List<Slot> slots = new ArrayList<>(monthly.slots());
        slots.set(0, new Slot(slots.get(0).id(), "Bizim aylık işlem grafiğimiz", slots.get(0).width()));
        renamed.set(1, new Section(monthly.id(), monthly.kind(), monthly.title(), List.of(), List.copyOf(slots), null, null, null, null, null));
        assertThat(TechErrorCalcMigration.migrate(withOldCharts("tr", renamed))).isEmpty();
        // genişlik değişmiş
        List<Section> widened = new ArrayList<>(v2);
        Section daily = widened.get(3);
        List<Slot> ds = new ArrayList<>(daily.slots());
        ds.set(0, new Slot(ds.get(0).id(), ds.get(0).label(), "full"));
        widened.set(3, new Section(daily.id(), daily.kind(), daily.title(), List.of(), List.copyOf(ds), null, null, null, null, null));
        assertThat(TechErrorCalcMigration.migrate(withOldCharts("tr", widened))).isEmpty();
        // bölüm başlığı değişmiş
        List<Section> retitled = new ArrayList<>(v2);
        Section dist = retitled.get(2);
        retitled.set(2, new Section(dist.id(), dist.kind(), "Birim dağılımı", List.of(), dist.slots(), null, null, null, null, null));
        assertThat(TechErrorCalcMigration.migrate(withOldCharts("tr", retitled))).isEmpty();
        // bir bölüm silinmiş
        assertThat(TechErrorCalcMigration.migrate(withOldCharts("tr", v2.subList(0, 3)))).isEmpty();
        // sıra değişmiş ama içerik aynı → değişir; yeni bölümler ilk eskinin yerine
        List<Section> reordered = new ArrayList<>(v2);
        java.util.Collections.reverse(reordered);
        Template m = TechErrorCalcMigration.migrate(withOldCharts("tr", reordered)).orElseThrow();
        assertThat(TechErrorTemplate.toJson(m)).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
    }

    @Test
    @DisplayName("grafikler: yeni yuva kimliği başka bölümde varsa ya da yuva sınırı aşılacaksa kanala dokunulmaz")
    void chartsCollisionOrLimitKept() {
        Template base = withOldCharts("tr", TechErrorCalcMigration.oldDefaultCharts(1, false));
        // Ekibin kendi bölümünde yeni varsayılanın bir yuva kimliği var
        List<Section> secs = new ArrayList<>(base.sections());
        secs.add(new Section("ekip-gorselleri", "images", "Ekibin görselleri", List.of(),
                List.of(new Slot("month-summary", "Bizim özetimiz", "full")), null, null, null, null, null));
        Template collide = TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(base.lang(), base.title(), List.copyOf(secs))));
        assertThat(TechErrorCalcMigration.migrate(collide)).isEmpty();
        // 40 yuva: eski tek bölüm (14) yerine 15 yuva sığmaz
        List<Section> full = new ArrayList<>(base.sections());
        int room = TechErrorTemplate.MAX_SLOTS - base.allSlots().size();
        List<Slot> extra = new ArrayList<>();
        for (int i = 0; i < room; i++) extra.add(new Slot("ek-" + i, "Ek " + i, "full"));
        full.add(new Section("ek", "images", "Ek görseller", List.of(), List.copyOf(extra), null, null, null, null, null));
        Template atLimit = TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(base.lang(), base.title(), List.copyOf(full))));
        assertThat(atLimit.allSlots()).hasSize(TechErrorTemplate.MAX_SLOTS);
        assertThat(TechErrorCalcMigration.migrate(atLimit)).isEmpty();
    }

    /** H2'de gerçek SQL: nişan, sürüm geçmişi, açılmış rapora dokunulmaması. */
    @Nested
    @DataJpaTest
    @Import({TechErrorReportService.class, TechErrorHistoryService.class, TechErrorCalcMigration.class})
    @TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    class Job {
        @Autowired TechErrorReportService service;
        @Autowired TechErrorCalcMigration migration;
        @Autowired TechErrorChannelRepository channels;
        @Autowired TechErrorReportRepository reports;
        @Autowired com.sitemonitor.repository.TechErrorReportEventRepository events;
        @Autowired com.sitemonitor.repository.TechErrorTemplateVersionRepository versions;
        @Autowired JdbcTemplate jdbc;

        @BeforeEach
        void clean() {
            events.deleteAll();
            versions.deleteAll();
            reports.deleteAll();
            channels.deleteAll();
            jdbc.execute(com.sitemonitor.service.StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", TechErrorCalcMigration.KEY);
            service.useClock(Clock.fixed(Instant.parse("2026-10-16T09:00:00Z"), ZoneOffset.UTC));
        }

        @Test
        @DisplayName("iş: eski şablonlu kanal güncellenir (sürüm +1, geçmişte 'SiteMonitor'), yenisi dokunulmaz; açılmış rapor aynen kalır; nişan sonrası koşmaz")
        void migratesOnce() {
            TechErrorChannel old = service.createChannel(1L, "Mobil", null, TechErrorTemplate.normalize(oldDefault("tr")), 7L);
            TechErrorChannel fresh = service.createChannel(1L, "POS", null, TechErrorTemplate.defaults("tr"), 7L);
            TechErrorChannel oldCharts = service.createChannel(1L, "İnternet", null,
                    withOldCharts("tr", TechErrorCalcMigration.oldDefaultCharts(2, false)), 7L);
            TechErrorReport opened = service.createReport(old, 2026, 9, 7L);
            String openedTemplate = opened.getTemplateJson();

            assertThat(migration.migrateLocked()).isEqualTo(2);
            // Eski varsayılan grafik bölümleri yeni iki bölüme döndü (sürüm +1)
            TechErrorChannel charted = channels.findById(oldCharts.getId()).orElseThrow();
            assertThat(charted.getTemplateVersion()).isEqualTo(2);
            assertThat(charted.getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
            TechErrorChannel after = channels.findById(old.getId()).orElseThrow();
            assertThat(after.getTemplateVersion()).isEqualTo(2);
            assertThat(f(TechErrorTemplate.fromStored(after.getTemplateJson()), "error-rate").calc()).isNotNull();
            assertThat(channels.findById(fresh.getId()).orElseThrow().getTemplateVersion()).isEqualTo(1);
            assertThat(versions.findAll()).anySatisfy(v -> {
                assertThat(v.getChannelId()).isEqualTo(old.getId());
                assertThat(v.getVersion()).isEqualTo(2);
                assertThat(v.getSavedByName()).isEqualTo(TechErrorCalcMigration.ACTOR);
            });
            // Açılmış rapor kopyasını korur; "şablonun son hâli farklı" der
            TechErrorReport r = reports.findById(opened.getId()).orElseThrow();
            assertThat(r.getTemplateJson()).isEqualTo(openedTemplate);
            assertThat(service.refreshPreview(r, after)).containsEntry("differs", true);
            // Nişan yazıldı: ikinci koşu hiçbir şey yapmaz
            assertThat(migration.done()).isTrue();
            assertThat(migration.migrateLocked()).isZero();
            assertThat(channels.findById(old.getId()).orElseThrow().getTemplateVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("taslağa şablonun son hâli uygulanınca oranlar adetlerden hesaplanır (geçen ayın raporuyla değişim dahil)")
        void refreshComputes() {
            TechErrorChannel ch = service.createChannel(1L, "Mobil", null, TechErrorTemplate.normalize(oldDefault("tr")), 7L);
            TechErrorReport aug = service.createReport(ch, 2026, 8, 7L);
            service.saveValues(aug.getId(), aug.getVersion(), Map.of("fields", Map.of("total-tx", 1000)), 7L);
            TechErrorReport sep = service.createReport(ch, 2026, 9, 7L);
            TechErrorReport s = service.saveValues(sep.getId(), sep.getVersion(),
                    Map.of("fields", Map.of("total-tx", 1100, "error-count", 11, "error-rate", 5.5)), 7L);
            assertThat(s.getValuesJson()).contains("\"error-rate\":5.5");                // eski şablon: elle
            migration.migrateLocked();
            // Önizleme: kayıp yalnız şablon farkından (yeni hesaplanan oranlar kaybı gizlemez); elle yazılan oranın yerine
            // hesaplanan değer ayrıca sayılır (inceleme 2026-10-16)
            Map<String, Object> preview = service.refreshPreview(reports.findById(sep.getId()).orElseThrow(),
                    channels.findById(ch.getId()).orElseThrow());
            assertThat(preview).containsEntry("lost_values", 0).containsEntry("recalculated", 1);
            service.refreshTemplate(sep.getId(), s.getVersion(), 7L);
            Map<String, Object> fields = TechErrorValues.fromStored(reports.findById(sep.getId()).orElseThrow().getValuesJson(),
                    TechErrorTemplate.fromStored(reports.findById(sep.getId()).orElseThrow().getTemplateJson())).fields();
            assertThat(fields).containsEntry("error-rate", 1L).containsEntry("tx-increase", true).containsEntry("tx-increase-pct", 10L);
            assertThat(Optional.ofNullable(fields.get("unmapped-rate"))).isEmpty();      // map yapılmamış adet girilmedi
        }
    }
}
