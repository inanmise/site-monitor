package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorSummary.Ref;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yönetici özetinin karşılaştırma başvuruları (2026-10-17, 4b; kullanıcı kararı "yer tutucu {d:alan}"): {@code {d:id}} önceki
 * aya göre fark (adette "+1.250 (+%3,2)", oranda "−0,05 puan"), {@code {t:id}} alanın etkin hedefi (raporun o aya özel hedefi,
 * yoksa şablonun), {@code {dt:id}} değerin hedefe uzaklığı (puan). {@link #DELTA_TABLE} istemcideki
 * {@code techErrorSummaryDelta.test.js} tarafından bu KAYNAKTAN okunur — Java ve JS aynı cümleyi üretmeli.
 */
class TechErrorSummaryDeltaTest {

    /**
     * Doğruluk tablosu — JS testi AYNI tabloyu okur. Satır: dil; kalıp (özetin tek cümlesi); bu ayın değerleri ("alan=değer;…");
     * geçen ayın değerleri ("-" = rapor yok); raporun o aya özel hedefleri; beklenen çıktı ("-" = cümle düşer, çıktı boş).
     * Şablon {@link #template(String, String)}: count sayı, rate yüzde (hedefsiz), sla yüzde (hedef %99,95, yüksek iyi),
     * note uzun açıklama, flag evet/hayır. Kanal "Mobil", dönem Eylül 2026.
     */
    static final String[][] DELTA_TABLE = {
            { "tr", "Hata adedi {d:count} değişti.", "count=5250", "count=6000", "", "Hata adedi **−750 (−%12,5)** değişti." },
            { "tr", "Hata adedi {d:count} değişti.", "count=80", "count=70", "", "Hata adedi **+10 (+%14,29)** değişti." },
            // adette değişim yok: düz metin (kalın rakam yok)
            { "tr", "Hata adedi {d:count} değişti.", "count=6000", "count=6000", "", "Hata adedi değişim yok değişti." },
            // önceki ay 0: yalnız adet farkı (yüzde tanımsız)
            { "tr", "Adet {d:count}.", "count=5", "count=0", "", "Adet **+5**." },
            { "tr", "Oran {d:rate}.", "rate=0.42", "rate=0.5", "", "Oran **−0,08 puan**." },
            { "tr", "Oran {d:rate}.", "rate=0.42", "rate=0.42", "", "Oran **0 puan**." },
            // geçen ayın raporu / değeri ya da bu ayın değeri yoksa cümle düşer (kullanıcı kararı)
            { "tr", "Oran {d:rate}.", "rate=0.42", "-", "", "-" },
            { "tr", "Oran {d:rate}.", "rate=0.42", "count=1", "", "-" },
            { "tr", "Oran {d:rate}.", "", "rate=0.42", "", "-" },
            // hedef ve hedefe uzaklık: şablon hedefi
            { "tr", "SLA {f:sla} (hedef {t:sla}, fark {dt:sla}).", "sla=99.2", "-", "", "SLA **%99,2** (hedef **%99,95**, fark **−0,75 puan**)." },
            { "tr", "SLA fark {dt:sla}.", "sla=99.95", "-", "", "SLA fark **0 puan**." },
            // hedef bu ayın değerini gerektirmez; uzaklık gerektirir
            { "tr", "SLA hedef {t:sla}.", "", "-", "", "SLA hedef **%99,95**." },
            { "tr", "SLA fark {dt:sla}.", "", "-", "", "-" },
            // raporun o aya özel hedefi şablonunkini ezer
            { "tr", "SLA hedef {t:sla}.", "sla=99.2", "-", "sla=99.5", "SLA hedef **%99,5**." },
            { "tr", "SLA fark {dt:sla}.", "sla=99.6", "-", "sla=99.5", "SLA fark **+0,1 puan**." },
            // hedefsiz alanda hedef / uzaklık, sayı dışı alanda fark → cümle düşer
            { "tr", "Oran hedef {t:rate}.", "rate=1", "-", "", "-" },
            { "tr", "Oran fark {dt:rate}.", "rate=1", "-", "", "-" },
            { "tr", "Not {d:note}.", "note=x", "note=y", "", "-" },
            { "tr", "Bayrak {d:flag}.", "flag=true", "flag=false", "", "-" },
            // İngilizce biçim: binlik virgül, yüzde sonda, "pts"
            { "en", "Errors changed by {d:count}.", "count=1250000", "count=1000000", "", "Errors changed by **+250,000 (+25%)**." },
            { "en", "SLA {f:sla} (target {t:sla}, difference {dt:sla}).", "sla=99.99", "-", "", "SLA **99.99%** (target **99.95%**, difference **+0.04 pts**)." },
            { "en", "Errors {d:count}.", "count=5", "count=5", "", "Errors no change." },
    };

    static Template template(String lang, String line) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", lang);
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(
                Map.of("id", "exec", "kind", "summary", "title", "Özet", "lines", List.of(line)),
                Map.of("id", "m", "kind", "metrics", "title", "Veriler", "fields", List.of(
                        Map.of("id", "count", "label", "Hata adedi", "type", "number"),
                        Map.of("id", "rate", "label", "Oran", "type", "percent"),
                        Map.of("id", "sla", "label", "SLA", "type", "percent", "target", 99.95, "better", "higher"),
                        Map.of("id", "note", "label", "Not", "type", "textarea"),
                        Map.of("id", "flag", "label", "Bayrak", "type", "yesno")))));
        return TechErrorTemplate.normalize(raw);
    }

    @Test
    @DisplayName("fark / hedef / hedefe uzaklık doğruluk tablosu (TR + EN): değer, puan, düşen cümleler, raporun hedefi")
    void truthTable() {
        assertThat(DELTA_TABLE.length).isGreaterThanOrEqualTo(20);
        for (String[] row : DELTA_TABLE) {
            Template t = template(row[0], row[1]);
            Map<String, Object> targets = TechErrorTilesTest.values(t, row[4]);
            String out = TechErrorSummary.auto(t.sections().get(0), t, TechErrorTilesTest.values(t, row[2]), "Mobil", 2026, 9,
                    TechErrorTilesTest.values(t, row[3]), targets == null ? Map.of() : targets);
            if ("-".equals(row[5])) assertThat(out).as(String.join(" | ", row)).isEmpty();
            else assertThat(out).as(String.join(" | ", row)).isEqualTo(row[5]);
        }
    }

    @Test
    @DisplayName("başvuru tarama: dört tür, yalnız {f:} seçim alır; kapanmamış / boş kimlikli başvuru bozuk, tanınmayan önek düz metin")
    void refKinds() {
        List<Ref> refs = TechErrorSummary.refs("a {d:count} b {t:sla} c {dt:sla} d {f:flag?e|h} e");
        assertThat(refs).extracting(Ref::kind).containsExactly("d", "t", "dt", "f");
        assertThat(refs).extracting(Ref::id).containsExactly("count", "sla", "sla", "flag");
        assertThat(refs.get(3).yes()).isEqualTo("e");
        assertThat(refs.get(0).yes()).isNull();
        assertThat(TechErrorSummary.malformed("{d:count")).isTrue();
        assertThat(TechErrorSummary.malformed("{dt:}")).isTrue();
        assertThat(TechErrorSummary.malformed("{d:Count}")).isTrue();
        assertThat(TechErrorSummary.malformed("{x:count} {d:count}")).isFalse();
        assertThat(TechErrorSummary.refs("{x:count}")).isEmpty();
        // Katı şablon kaydı yalnız BOZUK başvuruyu reddeder; silinmiş alana ya da uzun açıklamaya fark başvurusu kabul edilir
        // (alan silmek şablonu kilitlemesin) — çizimde cümle düşer
        assertThat(template("tr", "Not {d:note} ve {d:yok}.").sections().get(0).lines()).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> template("tr", "Kırık {dt:sla"))
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class);
    }

    @Test
    @DisplayName("inceleme 2026-10-17: yalnız {d:} ile anılan gömülü alan \"söylenmemiş\" sayılır — değeri kendi satırında yazılır")
    void deltaOnlyReferenceDoesNotTellTheValue() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(
                Map.of("id", "exec", "kind", "summary", "title", "Özet", "lines", List.of("Hata adedi {d:count} değişti.")),
                Map.of("id", "m", "kind", "metrics", "title", "Veriler", "host", "exec", "fields", List.of(
                        Map.of("id", "count", "label", "Hata adedi", "type", "number")))));
        Template t = TechErrorTemplate.normalize(raw);
        List<TechErrorSummary.Block> blocks = TechErrorSummary.blocks(t.sections().get(0), t, Map.of("count", 5250L), Map.of(),
                "Mobil", 2026, 9, Map.of("count", 6000L), Map.of());
        assertThat(blocks).extracting(TechErrorSummary.Block::text)
                .containsExactly("Hata adedi **−750 (−%12,5)** değişti.", "Hata adedi: **5.250**");
        // Değer başvurusu varsa söylenmiştir: ek satır yok
        Template told = template("tr", "Hata adedi {f:count} oldu, {d:count} değişti.");
        Template hosted = TechErrorTemplate.normalize(Map.of("lang", "tr", "title", "{kanal}", "sections", List.of(
                Map.of("id", "exec", "kind", "summary", "title", "Özet", "lines", List.of("Hata adedi {f:count} oldu, {d:count} değişti.")),
                Map.of("id", "m", "kind", "metrics", "title", "Veriler", "host", "exec", "fields", List.of(
                        Map.of("id", "count", "label", "Hata adedi", "type", "number"))))));
        assertThat(told.sections()).hasSize(2);
        assertThat(TechErrorSummary.blocks(hosted.sections().get(0), hosted, Map.of("count", 5250L), Map.of(), "Mobil", 2026, 9,
                Map.of("count", 6000L), Map.of())).hasSize(1);
    }

    @Test
    @DisplayName("etkin hedef ve yön: raporun hedefi > şablon; yüzde dışı alanda hedef yok; yön yazılmamışsa yüzde düşük-iyi, sayı yönsüz")
    void effectiveTargetAndBetter() {
        Template t = template("tr", "x");
        var byId = new java.util.HashMap<String, TechErrorTemplate.Field>();
        for (var f : t.allFields()) byId.put(f.id(), f);
        assertThat(TechErrorTemplate.effectiveTarget(byId.get("sla"), Map.of())).isEqualTo(99.95);
        assertThat(TechErrorTemplate.effectiveTarget(byId.get("sla"), Map.of("sla", 99.5))).isEqualTo(99.5);
        assertThat(TechErrorTemplate.effectiveTarget(byId.get("sla"), Map.of("sla", "x"))).isEqualTo(99.95);   // okunamayan hedef yok sayılır → şablonun
        // Rapor hedefi yalnız ŞABLONDA hedefi olan alanda geçer (saklama kuralıyla aynı; bayat yedek ekrana sızmaz)
        assertThat(TechErrorTemplate.effectiveTarget(byId.get("rate"), Map.of("rate", 1))).isNull();
        assertThat(TechErrorTemplate.effectiveTarget(byId.get("rate"), Map.of())).isNull();      // şablonda hedefi yok
        assertThat(TechErrorTemplate.effectiveTarget(byId.get("count"), Map.of())).isNull();
        assertThat(TechErrorTemplate.effectiveBetter(byId.get("sla"))).isEqualTo("higher");
        assertThat(TechErrorTemplate.effectiveBetter(byId.get("rate"))).isEqualTo("lower");
        assertThat(TechErrorTemplate.effectiveBetter(byId.get("count"))).isEqualTo("none");
    }
}
