package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VERİ TEKRARI YOK (2026-10-18, kullanıcı isteği: "kullanıcının elle girdiği verileri tekrar etmeden Yönetici Özeti kısmında
 * gösterelim; aynı veriyi birden fazla kez göstermemeye çalışalım"). {@link TechErrorSummary#shownValues}: değeri yönetici
 * özetinde gösterilen alanlar — özetin KUTUCUKLARINDA çizilen ya da TUTULAN bir cümlede {@code {f:}} ile yazılan; elle
 * düzenlenmiş özette yalnız kutucuklar. PDF ve e-posta bu kümedeki sayı / yüzde / evet-hayır alanlarını gösterge
 * bölümlerinde tekrar basmaz ({@link TechErrorSummary#shownInSummary}).
 */
class TechErrorSummaryShownTest {

    private static Section summary(String id, List<String> lines, List<String> tiles) {
        return new Section(id, "summary", "Özet " + id, List.of(), List.of(), null, null, null, null, null, lines, List.of(), List.of(),
                null, tiles);
    }

    private static Section metrics(String id, String host, List<Field> fields) {
        return new Section(id, "metrics", "Bölüm " + id, fields, List.of(), null, null, null, null, null, List.of(), List.of(), List.of(),
                host, List.of());
    }

    /** İki özet (sa, sb) + ayrı gösterge bölümü (m) + sa'ya gömülü bölüm (h). */
    private static Template template(List<String> aLines, List<String> aTiles, List<String> bLines, List<String> bTiles) {
        List<Section> s = new ArrayList<>();
        s.add(summary("sa", aLines, aTiles));
        if (bLines != null) s.add(summary("sb", bLines, bTiles));
        s.add(metrics("m", null, List.of(
                new Field("n1", "Adet bir", "number", null),
                new Field("n2", "Adet iki", "number", null),
                new Field("p1", "Oran", "percent", null).withTarget(99.0, "higher"),
                new Field("y1", "Var mı?", "yesno", null),
                new Field("t1", "Kısa metin", "text", null),
                new Field("ta1", "Uzun açıklama", "textarea", null),
                new Field("gate", "Kesinti oldu mu?", "yesno", null),
                new Field("ex", "Kesinti adedi", "number", null, "gate"))));
        s.add(metrics("h", "sa", List.of(
                new Field("h1", "Gömülü adet", "number", null),
                new Field("hn", "Gömülü açıklama", "textarea", null))));
        return new Template("tr", "{kanal}", List.copyOf(s));
    }

    private static Values values(Map<String, Object> fields, Map<String, String> texts) {
        return new Values(fields, Map.of(), texts, Map.of(), Map.of(), Map.of());
    }

    private static Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /**
     * Doğruluk tablosu satırı: ad, sa cümleleri / kutucukları, sb (null = ikinci özet yok), değerler, elle metinler, geçen ay,
     * beklenen küme.
     */
    record Row(String name, List<String> aLines, List<String> aTiles, List<String> bLines, List<String> bTiles,
               Map<String, Object> fields, Map<String, String> texts, Map<String, Object> previous, Set<String> expected) { }

    static final List<Row> TABLE = List.of(
            new Row("yalnız kutucuk", List.of(), List.of("n1"), null, null, f("n1", 5), Map.of(), null, Set.of("n1")),
            new Row("kutucuk, değer boş — '—' çizilir, sayılır", List.of(), List.of("n2"), null, null, f(), Map.of(), null, Set.of("n2")),
            new Row("{f:} tutulan cümlede", List.of("A {f:n1} oldu."), List.of(), null, null, f("n1", 5), Map.of(), null, Set.of("n1")),
            new Row("{f:} düşen cümlede (diğer değer boş) — ikisi de sayılmaz", List.of("A {f:n1}, B {f:n2}."), List.of(), null, null,
                    f("n1", 5), Map.of(), null, Set.of()),
            new Row("{f:} düşen cümlede (muaf alan) — sayılmaz", List.of("A {f:n1}, kesinti {f:ex}."), List.of(), null, null,
                    f("n1", 5, "gate", false, "ex", 3), Map.of(), null, Set.of()),
            new Row("muaf alanın kutucuğu çizilmez — sayılmaz", List.of(), List.of("ex"), null, null, f("gate", false, "ex", 3), Map.of(),
                    null, Set.of()),
            new Row("{d:} yalnız — değeri yazmaz, sayılmaz", List.of("Fark {d:n1}."), List.of(), null, null, f("n1", 5), Map.of(),
                    f("n1", 4), Set.of()),
            new Row("{t:} / {dt:} yalnız — sayılmaz", List.of("Hedef {t:p1}, fark {dt:p1}."), List.of(), null, null, f("p1", 99.5),
                    Map.of(), null, Set.of()),
            new Row("{f:} + {d:} aynı cümlede, geçen ay yok — cümle düşer, sayılmaz", List.of("A {f:n1} ({d:n1})."), List.of(), null, null,
                    f("n1", 5), Map.of(), null, Set.of()),
            new Row("{f:} + {d:} aynı cümlede, geçen ay var — {f:} sayılır", List.of("A {f:n1} ({d:n1})."), List.of(), null, null,
                    f("n1", 5), Map.of(), f("n1", 4), Set.of("n1")),
            new Row("evet/hayır seçimi {f:y1?Var|Yok} — sayılır", List.of("Durum: {f:y1?Var|Yok}."), List.of(), null, null, f("y1", true),
                    Map.of(), null, Set.of("y1")),
            new Row("madde kalıbı boş gövdeye çözüldü — düşer", List.of("- {f:y1?Var|}"), List.of(), null, null, f("y1", false), Map.of(),
                    null, Set.of()),
            new Row("elle metin — yalnız kutucuklar", List.of("A {f:n1} oldu."), List.of("n2"), null, null, f("n1", 5, "n2", 7),
                    Map.of("sa", "Elle yazılmış özet."), null, Set.of("n2")),
            new Row("boş elle metin = otomatik", List.of("A {f:n1} oldu."), List.of(), null, null, f("n1", 5), Map.of("sa", "  "), null,
                    Set.of("n1")),
            new Row("iki özet — birleşim", List.of("A {f:n1} oldu."), List.of(), List.of("B {f:n2} oldu."), List.of("p1"),
                    f("n1", 5, "n2", 7, "p1", 99.5), Map.of(), null, Set.of("n1", "n2", "p1")),
            new Row("iki özet, biri elle — elle olanın yalnız kutucukları", List.of("A {f:n1} oldu."), List.of("y1"),
                    List.of("B {f:n2} oldu."), List.of(), f("n1", 5, "n2", 7, "y1", true), Map.of("sa", "Elle."), null, Set.of("n2")),
            new Row("gömülü bölümün alanı {f:} ile — sayılır", List.of("Gömülü {f:h1} adet."), List.of(), null, null, f("h1", 2), Map.of(),
                    null, Set.of("h1")),
            new Row("kutucuk sayı / yüzde değil — çizilmez, sayılmaz", List.of(), List.of("t1", "y1"), null, null, f("t1", "x", "y1", true),
                    Map.of(), null, Set.of()),
            new Row("kısa metin {f:t1} — kümede (gösterge bölümünden yine düşmez)", List.of("Kaynak {f:t1}."), List.of(), null, null,
                    f("t1", "abc"), Map.of(), null, Set.of("t1")),
            new Row("uzun açıklamaya başvuran cümle düşer", List.of("Not {f:ta1}."), List.of(), null, null, f("ta1", "abc"), Map.of(), null,
                    Set.of()),
            new Row("şablonda olmayan alan — cümle düşer", List.of("Yok {f:nope}, {f:n1}."), List.of("nope"), null, null, f("n1", 5),
                    Map.of(), null, Set.of()));

    @Test
    @DisplayName("doğruluk tablosu: kutucuk ya da TUTULAN cümlede {f:} → gösterildi; düşen cümle, {d:} / {t:} / {dt:}, elle metnin cümleleri → gösterilmedi; iki özet birleşir")
    void truthTable() {
        for (Row r : TABLE) {
            Template t = template(r.aLines(), r.aTiles(), r.bLines(), r.bTiles());
            Values v = values(r.fields(), r.texts());
            assertThat(TechErrorSummary.shownValues(t, v, r.previous(), "Mobil", 2026, 9)).as(r.name()).isEqualTo(r.expected());
        }
    }

    @Test
    @DisplayName("gösterge bölümünden yalnız sayı / yüzde / evet-hayır düşer: kısa metin ve uzun açıklama kümede olsa da basılır")
    void onlyNumbersPercentsAndYesNoAreOmitted() {
        Template t = template(List.of(), List.of(), null, null);
        Set<String> all = Set.of("n1", "p1", "y1", "t1", "ta1");
        Map<String, Field> byId = new HashMap<>();
        for (Field x : t.allFields()) byId.put(x.id(), x);
        assertThat(TechErrorSummary.shownInSummary(byId.get("n1"), all)).isTrue();
        assertThat(TechErrorSummary.shownInSummary(byId.get("p1"), all)).isTrue();
        assertThat(TechErrorSummary.shownInSummary(byId.get("y1"), all)).isTrue();
        assertThat(TechErrorSummary.shownInSummary(byId.get("t1"), all)).isFalse();
        assertThat(TechErrorSummary.shownInSummary(byId.get("ta1"), all)).isFalse();
        assertThat(TechErrorSummary.shownInSummary(byId.get("n2"), all)).isFalse();
        assertThat(TechErrorSummary.shownInSummary(byId.get("n1"), Set.of())).isFalse();
        assertThat(TechErrorSummary.shownInSummary(byId.get("n1"), null)).isFalse();
    }

    @Test
    @DisplayName("özet bölümü olmayan şablon / boş değerler: küme boş (çıktı bugünküyle aynı)")
    void noSummaryMeansNothingShown() {
        Template t = template(List.of(), List.of(), null, null);
        Template noSummary = new Template("tr", "{kanal}", t.sections().stream().filter(s -> !"summary".equals(s.kind())).toList());
        assertThat(TechErrorSummary.shownValues(noSummary, values(f("n1", 5), Map.of()), null, "Mobil", 2026, 9)).isEmpty();
        assertThat(TechErrorSummary.shownValues(t, values(f("n1", 5), Map.of()), null, "Mobil", 2026, 9)).isEmpty();
        assertThat(TechErrorSummary.shownValues(null, Values.empty(), null, "Mobil", 2026, 9)).isEmpty();
    }

    @Test
    @DisplayName("söylenmemiş değer kuralıyla AYNI 1. geçiş: gömülü bölümün {f:} ile söylenen alanı satır olarak basılmaz, söylenmeyeni basılır (özetin kendi blokları değişmedi)")
    void sameToldPassAsUntoldFacts() {
        Template t = template(List.of("Gömülü {f:h1} adet."), List.of(), null, null);
        Values told = values(f("h1", 2), Map.of());
        List<TechErrorSummary.Block> b = TechErrorSummary.blocks(t.sections().get(0), t, told, "Mobil", 2026, 9);
        assertThat(b).extracting(TechErrorSummary.Block::text).containsExactly("Gömülü **2** adet.");
        assertThat(TechErrorSummary.shownValues(t, told, null, "Mobil", 2026, 9)).containsExactly("h1");
        // Cümle düşerse (değer yok) söylenmemiş değer satırı da yok; küme boş
        Template t2 = template(List.of("Gömülü {f:h1} adet, {f:n2}."), List.of(), null, null);
        List<TechErrorSummary.Block> b2 = TechErrorSummary.blocks(t2.sections().get(0), t2, told, "Mobil", 2026, 9);
        assertThat(b2).extracting(TechErrorSummary.Block::text).containsExactly("Gömülü adet: **2**");
        assertThat(TechErrorSummary.shownValues(t2, told, null, "Mobil", 2026, 9)).isEmpty();
    }

    @Test
    @DisplayName("varsayılan TR + EN şablon, tam dolu rapor: Verileri'nin bütün adetleri ve Oranları'nın bütün oranları özette — kesinti sorusu (hiçbir cümle yazmıyor) ve notlar kalır")
    void defaultTemplateFullyFilled() {
        for (String lang : List.of("tr", "en")) {
            Template t = TechErrorTemplate.defaults(lang);
            Map<String, Object> fields = new HashMap<>(Map.of("total-tx", 200_000_000_000L, "error-count", 4_400_000_000L,
                    "unmapped-count", 123_456, "quota-count", 7_890, "had-outage", true, "outage-errors", 400_000_000,
                    "sla-rate", 99.97, "tx-increase", true, "tx-increase-pct", 5.26));
            fields.put("unmapped-note", "Not");
            Values v = TechErrorCalc.apply(t, TechErrorValues.normalize(Map.of("fields", fields), t), Map.of("error-count", 4_000_000_000L,
                    "error-rate", 2.1));
            Set<String> shown = TechErrorSummary.shownValues(t, v, Map.of("error-count", 4_000_000_000L, "error-rate", 2.1), "Mobil", 2026, 9);
            assertThat(shown).as(lang).containsExactlyInAnyOrder("error-rate", "error-rate-excl", "total-tx", "tx-increase-pct", "tx-increase",
                    "error-count", "unmapped-count", "unmapped-rate", "quota-count", "quota-rate", "outage-errors", "outage-share", "sla-rate",
                    "sla-above");
            for (Section s : t.sections()) {
                if (!"metrics".equals(s.kind())) continue;
                List<String> left = new ArrayList<>();
                for (Field x : s.fields()) if (!TechErrorSummary.shownInSummary(x, shown)) left.add(x.id());
                if ("rates".equals(s.id())) assertThat(left).as(lang + " Oranları").isEmpty();
                if ("summary".equals(s.id())) assertThat(left).as(lang + " Verileri").containsExactly("unmapped-note", "quota-note", "had-outage");
            }
        }
    }
}
