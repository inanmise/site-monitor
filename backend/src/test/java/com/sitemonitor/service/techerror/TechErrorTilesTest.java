package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorTiles.Tile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renkli kutucuk şeridi (2026-10-17, 4b). {@link #TILES_TABLE} istemcideki {@code techErrorTiles.test.js} tarafından bu
 * KAYNAKTAN okunur — Java ve JS aynı kutucukları üretmeli. Şablon iki tarafta elle aynıdır ({@link #template()}).
 */
class TechErrorTilesTest {

    /** Özet (6 kutucuk: oran, adet, düşük-iyi adet, hedefli SLA, uzun açıklama (çizilmez), muaf olabilen adet) + alanlar. */
    static Template template() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(
                Map.of("id", "exec", "kind", "summary", "title", "Yönetici Özeti", "lines", List.of("{kanal}: {f:rate}."),
                        "tiles", List.of("rate", "total", "count", "sla", "note", "q-count")),
                Map.of("id", "m", "kind", "metrics", "title", "Veriler", "fields", List.of(
                        Map.of("id", "rate", "label", "Teknik hata oranı", "type", "percent"),
                        Map.of("id", "total", "label", "Toplam işlem adedi", "type", "number"),
                        Map.of("id", "count", "label", "Teknik hata adedi", "type", "number", "better", "lower"),
                        Map.of("id", "sla", "label", "SLA oranı", "type", "percent", "target", 99.95, "better", "higher"),
                        Map.of("id", "note", "label", "Not", "type", "textarea"),
                        Map.of("id", "q-flag", "label", "Kota aşımı oldu mu?", "type", "yesno"),
                        Map.of("id", "q-count", "label", "Kota hatası adedi", "type", "number", "skip_if_no", "q-flag")))));
        return TechErrorTemplate.normalize(raw);
    }

    /**
     * Kutucuk doğruluk tablosu — JS testi AYNI tabloyu bu kaynaktan okur. Satır: ad; bu ayın değerleri ("alan=değer;…");
     * geçen ayın değerleri ("-" = rapor yok); raporun o aya özel hedefleri; ardından beklenen kutucuklar sırayla:
     * "kimlik|değer|fark|yön|ton|hedef|tutturuldu" ("-" = yok). Kanal "Mobil", dönem Eylül 2026.
     */
    static final String[][] TILES_TABLE = {
            { "tam dolu, geçen ay var: oran düştü → iyi (yüzde varsayılanı düşük-iyi); adet yönsüz → nötr; düşük-iyi adet düştü → iyi; SLA hedefin üstünde → iyi; uzun açıklama çizilmez",
              "rate=0.42;total=1250000;count=5250;sla=99.99;q-flag=true;q-count=80",
              "rate=0.5;total=1000000;count=6000;sla=99.9;q-count=70", "",
              "rate|%0,42|−0,08 puan|down|good|-|-",
              "total|1.250.000|+250.000 (+%25)|up|neutral|-|-",
              "count|5.250|−750 (−%12,5)|down|good|-|-",
              "sla|%99,99|+0,09 puan|up|good|%99,95|true",
              "q-count|80|+10 (+%14,29)|up|neutral|-|-" },
            { "geçen ay yok: yön bilinmez → nötr; hedefli SLA hedefin altında → kötü; boş değer bilinmiyor (gizlenmez); muaf alan çizilmez",
              "rate=0.42;total=100;sla=99.9;q-flag=false", "-", "",
              "rate|%0,42|-|-|neutral|-|-",
              "total|100|-|-|neutral|-|-",
              "count|—|-|-|unknown|-|-",
              "sla|%99,9|-|-|bad|%99,95|false" },
            { "raporun o aya özel hedefi; değişim yok → nötr; önceki ay 0 → yalnız adet farkı",
              "rate=0.42;total=5;sla=99.6", "rate=0.42;total=0", "sla=99.5",
              "rate|%0,42|0 puan|flat|neutral|-|-",
              "total|5|+5|up|neutral|-|-",
              "count|—|-|-|unknown|-|-",
              "sla|%99,6|-|-|good|%99,5|true",
              "q-count|—|-|-|unknown|-|-" },                                         // soru cevapsız: muaf değil, bilinmiyor
            { "hedefli alanın değeri boşsa bilinmiyor ama hedef yazılır; düşük-iyi adet yükselince kötü; hedefe eşit = tutturuldu",
              "count=7000;sla=99.95", "count=6000", "",
              "rate|—|-|-|unknown|-|-",
              "total|—|-|-|unknown|-|-",
              "count|7.000|+1.000 (+%16,67)|up|bad|-|-",
              "sla|%99,95|-|-|good|%99,95|true",
              "q-count|—|-|-|unknown|-|-" },
    };

    static Map<String, Object> values(Template t, String spec) {
        if ("-".equals(spec)) return null;
        Map<String, Field> byId = new HashMap<>();
        for (Field f : t.allFields()) byId.put(f.id(), f);
        Map<String, Object> out = new LinkedHashMap<>();
        if (spec.isEmpty()) return out;
        for (String part : spec.split(";")) {
            int eq = part.indexOf('=');
            String id = part.substring(0, eq);
            String raw = part.substring(eq + 1);
            Field f = byId.get(id);
            assertThat(f).as("tablo alanı " + id).isNotNull();
            Object v = switch (f.type()) {
                case "number", "percent" -> raw.contains(".") ? (Object) Double.parseDouble(raw) : (Object) Long.parseLong(raw);
                case "yesno" -> (Object) Boolean.parseBoolean(raw);
                default -> raw;
            };
            out.put(id, v);
        }
        return out;
    }

    static String show(Tile x) {
        return String.join("|", x.id(), x.value(), x.delta() == null ? "-" : x.delta(), x.dir() == null ? "-" : x.dir(), x.tone(),
                x.target() == null ? "-" : x.target(), x.met() == null ? "-" : String.valueOf(x.met()));
    }

    @Test
    @DisplayName("kutucuk doğruluk tablosu: değer, önceki aya fark + yön, hedef + yön rengi, bilinmeyen / muaf / çizilmeyen türler")
    void tilesTruthTable() {
        Template t = template();
        for (String[] row : TILES_TABLE) {
            Map<String, Object> targets = values(t, row[3]);
            List<Tile> tiles = TechErrorTiles.build(t, t.sections().get(0), values(t, row[1]), targets == null ? Map.of() : targets,
                    values(t, row[2]), "Mobil", 2026, 9);
            List<String> shown = new ArrayList<>();
            for (Tile x : tiles) shown.add(show(x));
            List<String> expected = new ArrayList<>();
            for (int i = 4; i < row.length; i++) expected.add(row[i]);
            assertThat(shown).as(row[0]).isEqualTo(expected);
            for (Tile x : tiles) assertThat(x.label()).as(row[0] + " " + x.id()).isNotBlank();
        }
    }

    @Test
    @DisplayName("inceleme 2026-10-17: yönsüz (none) alanın hedefi karşılaştırılmaz — kutucuk nötr, hedef yazılır, tutturuldu yok")
    void noneDirectionWithTargetIsNeutral() {
        // Katı kayıt hedef + "none" reddeder, saklı okuma hedefi düşürür — saf yol için alan elle kurulur
        Template base = TechErrorTemplate.normalize(Map.of("lang", "tr", "title", "{kanal}", "sections", List.of(
                Map.of("id", "exec", "kind", "summary", "title", "Özet", "lines", List.of("x"), "tiles", List.of("r")),
                Map.of("id", "m", "kind", "metrics", "title", "V", "fields", List.of(Map.of("id", "r", "label", "Oran", "type", "percent"))))));
        Field r = base.sections().get(1).fields().get(0).withTarget(1.0, "none");
        Template t = new Template(base.lang(), base.title(), List.of(base.sections().get(0), base.sections().get(1).withFields(List.of(r))));
        List<Tile> tiles = TechErrorTiles.build(t, t.sections().get(0), Map.of("r", 2.0), Map.of(), Map.of("r", 1.0), "K", 2026, 1);
        assertThat(tiles).hasSize(1);
        assertThat(tiles.get(0).tone()).isEqualTo("neutral");
        assertThat(tiles.get(0).met()).isNull();
        assertThat(tiles.get(0).target()).isEqualTo("%1");
        assertThat(tiles.get(0).delta()).isEqualTo("+1 puan");
    }

    @Test
    @DisplayName("şablonda olmayan kimlik, sayı / yüzde olmayan alan ve boş liste kutucuk üretmez; kutucuksuz özet boş liste")
    void skipsAndLimits() {
        Template t = template();
        Template ghost = TechErrorTemplate.normalize(Map.of("lang", "tr", "title", "{kanal}", "sections", List.of(
                Map.of("id", "exec", "kind", "summary", "title", "Özet", "lines", List.of("x"), "tiles", List.of("ghost", "flag", "rate")),
                Map.of("id", "m", "kind", "metrics", "title", "V", "fields", List.of(
                        Map.of("id", "rate", "label", "Oran", "type", "percent"),
                        Map.of("id", "flag", "label", "Evet mi?", "type", "yesno"))))));
        List<Tile> tiles = TechErrorTiles.build(ghost, ghost.sections().get(0), Map.of("rate", 1.5, "flag", true), Map.of(), null, "K", 2026, 1);
        assertThat(tiles).extracting(Tile::id).containsExactly("rate");
        assertThat(TechErrorTiles.build(t, t.sections().get(1), Map.of(), Map.of(), null, "K", 2026, 1)).isEmpty();   // gösterge bölümü
        // Etiketin yer tutucusu çözülür
        assertThat(TechErrorTiles.build(t, t.sections().get(0), Map.of(), Map.of(), null, "Mobil", 2026, 9).get(0).label()).isEqualTo("Teknik hata oranı");
    }
}
