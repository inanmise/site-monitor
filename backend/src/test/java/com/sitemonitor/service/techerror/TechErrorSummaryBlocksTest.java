package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorSummary.Block;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yönetici özetinin BLOKLARI (2026-10-17): özet kartına gömülü bölümlerin açıklamaları ilgili cümlenin altında,
 * söylenmemiş değerleri kendi satırında. {@link #BLOCKS_TABLE} istemcideki {@code techErrorSummaryBlocks.test.js}
 * tarafından bu KAYNAKTAN okunur — Java ve JS aynı çıktıyı vermeli. Şablon iki tarafta elle aynıdır ({@link #template()};
 * JS testi aynı kimlik / ad / tür / cümlelerle kurar).
 */
class TechErrorSummaryBlocksTest {

    /** Özet + ayrı oran bölümü + özete gömülü üç bölüm (adet + açıklama; adet + evet/hayır + açıklama + kısa metin + bağlı not; serbest). */
    static Template template() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(
                Map.of("id", "exec", "kind", "summary", "title", "Yönetici Özeti", "lines", List.of(
                        "{kanal} Kanalı {ay} {yil} teknik hata oranı {f:rate} olarak gerçekleşmiştir.",
                        "Map yapılmamış hata adedi {f:un-count} olup teknik hataların {f:un-rate} kadarını oluşturmaktadır.",
                        "- Kota hatası adedi {f:q-count} olarak kaydedilmiştir.",
                        "Kapanış cümlesi.",
                        "Kaynak {f:q-short} olarak belirlenmiştir.")),
                Map.of("id", "base", "kind", "metrics", "title", "Oranlar", "fields", List.of(
                        Map.of("id", "rate", "label", "Teknik hata oranı", "type", "percent"),
                        Map.of("id", "un-rate", "label", "Map yapılmamış hataların oranı", "type", "percent"))),
                Map.of("id", "un", "kind", "metrics", "title", "Map Yapılmamış Hatalar", "host", "exec", "fields", List.of(
                        Map.of("id", "un-count", "label", "Map yapılmamış hata adedi", "type", "number"),
                        Map.of("id", "un-note", "label", "Map yapılmamış hatalar hakkında açıklama", "type", "textarea"))),
                Map.of("id", "q", "kind", "metrics", "title", "Kota Hataları", "host", "exec", "fields", List.of(
                        Map.of("id", "q-count", "label", "Kota hatası adedi", "type", "number"),
                        Map.of("id", "q-flag", "label", "Kota aşımı oldu mu?", "type", "yesno"),
                        Map.of("id", "q-note", "label", "Kota hataları hakkında açıklama", "type", "textarea"),
                        Map.of("id", "q-short", "label", "Kota kaynağı", "type", "text"),
                        Map.of("id", "ex-note", "label", "Kesinti notu", "type", "textarea", "skip_if_no", "q-flag"))),
                Map.of("id", "free", "kind", "metrics", "title", "Serbest", "host", "exec", "fields", List.of(
                        Map.of("id", "fr-count", "label", "{kanal} serbest adedi", "type", "number"),
                        Map.of("id", "fr-note", "label", "Serbest açıklama", "type", "textarea"),
                        Map.of("id", "fr-short", "label", "Serbest kaynak", "type", "text")))));
        return TechErrorTemplate.normalize(raw);
    }

    /**
     * Blok doğruluk tablosu — JS testi AYNI tabloyu bu kaynaktan okur. Satır: ad; değerler ("alan=değer;…", sayı ve evet/hayır
     * alan türüne göre okunur, metinde "\n" satır sonu; boş = değer yok); elle metin (null = yok); ardından beklenen bloklar
     * sırayla: "T:<metin>" ya da "N:<bölüm>:<alan>:<alan adı = notun başlığı>:<metin>". Kanal "Mobil", dönem Eylül 2026.
     */
    static final String[][] BLOCKS_TABLE = {
            { "tam dolu: açıklama cümlenin altında, söylenmemiş evet/hayır kendi bölümünün ardında, cümlesi olan kısa metin yalnız cümlede, başvurulmayan bölüm sonda (adet kalın, kısa metin düz ve yıldızı tek)",
              "rate=0.42;un-rate=2.38;un-count=1250;un-note=Yeni **kod**\n- E-1042;q-count=80;q-flag=true;q-note=Kota notu;q-short=Batch;fr-count=5;fr-note=Serbest;fr-short=A **B** C", null,
              "T:Mobil Kanalı Eylül 2026 teknik hata oranı **%0,42** olarak gerçekleşmiştir. Map yapılmamış hata adedi **1.250** olup teknik hataların **%2,38** kadarını oluşturmaktadır.",
              "N:un:un-note:Map yapılmamış hatalar hakkında açıklama:Yeni **kod**\n- E-1042",
              "T:- Kota hatası adedi **80** olarak kaydedilmiştir.",
              "T:Kota aşımı oldu mu? Evet",
              "N:q:q-note:Kota hataları hakkında açıklama:Kota notu",
              "T:Kapanış cümlesi. Kaynak Batch olarak belirlenmiştir.",
              "T:Mobil serbest adedi: **5**\nSerbest kaynak: A *B* C",
              "N:free:fr-note:Serbest açıklama:Serbest" },
            { "bütün adetler girilmiş, not yok: yerleşim bir şey eklemiyorsa paragraf bölünmez — tek blok, otomatik metnin aynısı",
              "rate=0.42;un-rate=2.38;un-count=1250;q-count=80", null,
              "T:Mobil Kanalı Eylül 2026 teknik hata oranı **%0,42** olarak gerçekleşmiştir. Map yapılmamış hata adedi **1.250** olup teknik hataların **%2,38** kadarını oluşturmaktadır.\n- Kota hatası adedi **80** olarak kaydedilmiştir.\n\nKapanış cümlesi." },
            { "cümlesi düşen adet (oran yok) sonda değer satırı olur; sonraki düz cümle aynı paragrafta akar",
              "rate=0.42;un-count=1250;un-note=Not", null,
              "T:Mobil Kanalı Eylül 2026 teknik hata oranı **%0,42** olarak gerçekleşmiştir. Kapanış cümlesi.",
              "T:Map yapılmamış hata adedi: **1.250**",
              "N:un:un-note:Map yapılmamış hatalar hakkında açıklama:Not" },
            { "elle metin: yer tutucu çözülür, notlar sonda, söylenmemiş değer eklenmez, Hayır cevabına bağlı not muaf",
              "un-count=1250;un-note=Not;q-flag=false;ex-note=gizli", "Elle **özet** — {kanal}",
              "T:Elle **özet** — Mobil",
              "N:un:un-note:Map yapılmamış hatalar hakkında açıklama:Not" },
            { "madde cümlesi paragrafa tek satır sonuyla bağlanır ve bölüm o cümlenin ardına yerleşir; Hayır cevabı değer satırı, bağlı not muaf",
              "rate=0.42;q-count=80;q-flag=false;ex-note=gizli;q-note=Kota notu", null,
              "T:Mobil Kanalı Eylül 2026 teknik hata oranı **%0,42** olarak gerçekleşmiştir.\n- Kota hatası adedi **80** olarak kaydedilmiştir.",
              "T:Kota aşımı oldu mu? Hayır",
              "N:q:q-note:Kota hataları hakkında açıklama:Kota notu",
              "T:Kapanış cümlesi." },
            { "hiç değer yok: yalnız rakamsız cümle", "", null, "T:Kapanış cümlesi." },
            { "yalnız açıklama: cümle önce, not sonda", "un-note=Sadece not", null,
              "T:Kapanış cümlesi.", "N:un:un-note:Map yapılmamış hatalar hakkında açıklama:Sadece not" },
            { "boşluktan ibaret açıklama not değildir", "un-note=  \n ", null, "T:Kapanış cümlesi." },
            { "boş elle metin otomatik sayılır", "un-note=Not", "  ",
              "T:Kapanış cümlesi.", "N:un:un-note:Map yapılmamış hatalar hakkında açıklama:Not" },
            { "yalnız evet/hayır: değer satırı, kalın değil", "q-flag=true", null, "T:Kapanış cümlesi.", "T:Kota aşımı oldu mu? Evet" },
            { "kısa metne cümle başvuruyorsa yalnız cümlede yer alır (ne not ne değer satırı); bir şey eklemeyen yerleşim paragrafı bölmez",
              "q-short=Batch", null, "T:Kapanış cümlesi. Kaynak Batch olarak belirlenmiştir." },
            { "kısa metnin cümlesi yoksa değer satırı (düz, yıldız dizisi tek yıldız)",
              "fr-short=A **B** C", null, "T:Kapanış cümlesi.", "T:Serbest kaynak: A *B* C" },
            { "yerleşen bölüm bir şey eklemiyorsa madde ve sonraki cümle aynı blokta kalır; yerleşmeyenler şablon sırasıyla sonda; yer tutuculu alan adı çözülür",
              "un-count=3;q-count=4;fr-count=5", null,
              "T:- Kota hatası adedi **4** olarak kaydedilmiştir.\n\nKapanış cümlesi.",
              "T:Map yapılmamış hata adedi: **3**",
              "T:Mobil serbest adedi: **5**" },
    };

    /** "alan=değer;…" → değer haritası (tür alandan: sayı → Long / Double, evet-hayır → Boolean, metin olduğu gibi). */
    static Map<String, Object> values(Template t, String spec) {
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

    static Block expected(String s) {
        if (s.startsWith("T:")) return Block.text(s.substring(2));
        String[] p = s.substring(2).split(":", 4);
        return Block.note(p[0], p[1], p[2], p[3]);
    }

    @Test
    @DisplayName("blok doğruluk tablosu: açıklama ilgili cümlenin altında, söylenmemiş değer kendi satırında, elle metinde yalnız notlar")
    void blocksTruthTable() {
        Template t = template();
        for (String[] row : BLOCKS_TABLE) {
            Map<String, String> texts = row[2] == null ? Map.of() : Map.of("exec", row[2]);
            List<Block> expected = new ArrayList<>();
            for (int i = 3; i < row.length; i++) expected.add(expected(row[i]));
            List<Block> got = TechErrorSummary.blocks(t.sections().get(0), t, values(t, row[1]), texts, "Mobil", 2026, 9);
            assertThat(got).as(row[0]).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("gömülü bölüm yoksa tek metin bloğu = otomatik metin (davranış değişmez); saklı değerlerle de aynı")
    @SuppressWarnings("unchecked")
    void noEmbeddedEqualsAuto() {
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(template()));
        List<Object> secs = new ArrayList<>();
        for (Object o : (List<?>) raw.get("sections")) {
            Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) o);
            m.remove("host");
            secs.add(m);
        }
        raw.put("sections", secs);
        Template t = TechErrorTemplate.normalize(raw);
        assertThat(TechErrorTemplate.embeddedSections(t, "exec")).isEmpty();
        for (String[] row : BLOCKS_TABLE) {
            if (row[2] != null) continue;
            Map<String, Object> v = values(t, row[1]);
            String auto = TechErrorSummary.auto(t.sections().get(0), t, v, "Mobil", 2026, 9);
            List<Block> blocks = TechErrorSummary.blocks(t.sections().get(0), t, v, Map.of(), "Mobil", 2026, 9);
            assertThat(blocks).as(row[0]).containsExactly(Block.text(auto));
        }
        Template h = template();
        TechErrorValues.Values stored = TechErrorValues.normalize(Map.of("fields", Map.of("rate", 0.42, "un-count", 7)), h);
        assertThat(TechErrorSummary.blocks(h.sections().get(0), h, stored, "Mobil", 2026, 9))
                .isEqualTo(TechErrorSummary.blocks(h.sections().get(0), h, stored.fields(), stored.texts(), "Mobil", 2026, 9));
    }

    @Test
    @DisplayName("metin sınırı yalnız metne: kesim noktasından sonra başlayan metin blokları düşer, kalan son blok … ile biter, notlar hiç düşmez")
    void capKeepsNotes() {
        int max = TechErrorTemplate.MAX_RESOLVED_TEXT;
        Block note = Block.note("un", "un-note", "Açıklama", "Not");
        List<Block> cut = TechErrorSummary.capText(List.of(Block.text("a".repeat(max + 500)), note, Block.text("b")));
        assertThat(cut).hasSize(2);
        assertThat(cut.get(0).text()).hasSize(max).endsWith("…");
        assertThat(cut.get(1)).isEqualTo(note);
        // Kesim ikinci bloğun içinde: ilk blok tam, ikinci kesim noktasına kadar (ayraç 2 sayılır)
        List<Block> second = TechErrorSummary.capText(List.of(Block.text("abcde"), note, Block.text("x".repeat(max))));
        assertThat(second).hasSize(3);
        assertThat(second.get(0).text()).isEqualTo("abcde");
        assertThat(second.get(2).text()).hasSize(max - 1 - 7 + 1).endsWith("…");
        // Sınır altı: aynı liste
        List<Block> same = List.of(Block.text("kısa"), note);
        assertThat(TechErrorSummary.capText(same)).isSameAs(same);
    }

    // ── Söylenen kümesi = shownValues kuralı (bütünleştirme incelemesi 2026-10-18) ─────────────────────────────────

    /**
     * Seçimli başvuru şablonu: özete gömülü evet/hayır + adet; ilk cümle seçilen dalı BOŞ olabilen bir seçim taşır. JS testi
     * aynı şablonu elle kurar.
     */
    static Template choiceTemplate() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(
                Map.of("id", "exec", "kind", "summary", "title", "Yönetici Özeti", "lines", List.of(
                        "Dönem değerlendirmesi tamamlandı.{f:q-flag? Kota aşıldı.|}",
                        "Kota adedi {f:q-count} olarak kaydedilmiştir.")),
                Map.of("id", "q", "kind", "metrics", "title", "Kota", "host", "exec", "fields", List.of(
                        Map.of("id", "q-flag", "label", "Kota aşımı oldu mu?", "type", "yesno"),
                        Map.of("id", "q-count", "label", "Kota hatası adedi", "type", "number")))));
        return TechErrorTemplate.normalize(raw);
    }

    /**
     * Seçimli başvuru doğruluk tablosu (BLOCKS_TABLE ile aynı biçim; JS testi bu kaynaktan okur): {@code {f:x?evet|hayır}}
     * yalnız SEÇİLEN dal boş değilse x'i söyler — "…{f:q-flag? Kota aşıldı.|}" Hayır'da kota hakkında bir şey yazmaz, değer
     * satırı basılır (shownValues ile aynı kural).
     */
    static final String[][] CHOICE_TABLE = {
            { "Evet: seçilen dal yazar, değer satırı yok; bir şey eklemeyen yerleşim paragrafı bölmez",
              "q-flag=true;q-count=5", null,
              "T:Dönem değerlendirmesi tamamlandı. Kota aşıldı. Kota adedi **5** olarak kaydedilmiştir." },
            { "Hayır: seçilen dal boş — kota cevabı değer satırı olur, sonraki cümle yeni paragraf",
              "q-flag=false;q-count=5", null,
              "T:Dönem değerlendirmesi tamamlandı.",
              "T:Kota aşımı oldu mu? Hayır",
              "T:Kota adedi **5** olarak kaydedilmiştir." },
            { "Hayır ve adet yok: adet cümlesi düşer, kota cevabı yine basılır",
              "q-flag=false", null,
              "T:Dönem değerlendirmesi tamamlandı.",
              "T:Kota aşımı oldu mu? Hayır" },
    };

    @Test
    @DisplayName("bütünleştirme incelemesi 2026-10-18: seçilen dalı BOŞ seçimli başvuru alanı söylemez — gömülü değer satır olarak basılır (shownValues ile aynı kural)")
    void choiceTruthTable() {
        Template t = choiceTemplate();
        for (String[] row : CHOICE_TABLE) {
            List<Block> expected = new ArrayList<>();
            for (int i = 3; i < row.length; i++) expected.add(expected(row[i]));
            List<Block> got = TechErrorSummary.blocks(t.sections().get(0), t, values(t, row[1]), Map.of(), "Mobil", 2026, 9);
            assertThat(got).as(row[0]).isEqualTo(expected);
        }
    }

    /** 450 karakterlik dolgu cümlesi (k = sıra). */
    static String filler(int k) {
        String head = "Dolgu " + k + " ";
        return head + "x".repeat(449 - head.length()) + ".";
    }

    /**
     * Metin sınırı şablonu: 26 dolgu cümlesi (birleşik 11.725 karakter), 27. cümle sınırı GEÇER ve sonunda gömülü {@code e2}'yi
     * yazar, 28. cümle sınırın ötesinde (üretilmez) gömülü {@code e1}'i yazar. Şablon doğrudan kurulur (sınıra ulaşmak 20
     * cümle × 500 karakter sınırını aşar; çözülen değerler ulaştırabilir). JS testi aynı şablonu kurar.
     */
    static Template capChoiceTemplate() {
        List<String> lines = new ArrayList<>();
        for (int k = 0; k < 26; k++) lines.add(filler(k));
        String tail = " değeri {f:e2}.";
        String straddle = "Gömülü " + "y".repeat(450 - "Gömülü ".length() - tail.length()) + tail;
        lines.add(straddle);
        lines.add("Sınırın ötesinde {f:e1}.");
        TechErrorTemplate.Section exec = new TechErrorTemplate.Section("exec", "summary", "Yönetici Özeti", List.of(), List.of(), null, null,
                null, null, null, List.copyOf(lines), List.of(), List.of(), null, List.of());
        TechErrorTemplate.Section e = new TechErrorTemplate.Section("e", "metrics", "Gömülü", List.of(
                new Field("e1", "Gömülü adet", "number", null),
                new Field("e2", "Gömülü iki", "number", null),
                new Field("e-note", "Gömülü açıklama", "textarea", null)), List.of(), null, null, null, null, null, List.of(), List.of(),
                List.of(), "exec", List.of());
        return new Template("tr", "{kanal}", List.of(exec, e));
    }

    @Test
    @DisplayName("bütünleştirme incelemesi 2026-10-18: cümlesi metin sınırında KESİLEN ya da sınırın ötesinde kalan gömülü değer kaybolmaz — değer satırı tamamıyla özet metninden sonra (yarım \"…\" yok); not yerinde")
    void cutSentenceValuesStillPrint() {
        Template t = capChoiceTemplate();
        Map<String, Object> v = new LinkedHashMap<>(Map.of("e1", 7L, "e2", 8L, "e-note", "Not"));
        List<Block> got = TechErrorSummary.blocks(t.sections().get(0), t, v, Map.of(), "Mobil", 2026, 9);
        assertThat(got).hasSize(3);
        assertThat(got.get(0).note()).isFalse();
        assertThat(got.get(0).text()).startsWith("Dolgu 0 ").endsWith("…").doesNotContain("**8**");
        assertThat(got.get(0).text().length()).isLessThanOrEqualTo(TechErrorTemplate.MAX_RESOLVED_TEXT);
        assertThat(got.get(1)).isEqualTo(Block.note("e", "e-note", "Gömülü açıklama", "Not"));
        assertThat(got.get(2)).isEqualTo(Block.text("Gömülü adet: **7**\nGömülü iki: **8**"));
        // Kesilen cümlenin değeri "gösterildi" sayılmaz (shownValues ile aynı)
        TechErrorValues.Values stored = new TechErrorValues.Values(v, Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        assertThat(TechErrorSummary.shownValues(t, stored, null, "Mobil", 2026, 9)).doesNotContain("e1", "e2");
    }
}
