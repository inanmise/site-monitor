package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorChangeDiff.Change;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Değişiklik geçmişinin fark hesabı (2026-10-14) — saf işlev; varsayılan TR / EN şablonla. */
class TechErrorChangeDiffTest {

    static final Template TR = TechErrorTemplate.defaults("tr");
    static final Template EN = TechErrorTemplate.defaults("en");
    /** Varsayılanda tablo yok (SLA tablosu ekran görüntüsü, 2026-10-15) — tablo farkları eski SLA tablosuyla sınanır. */
    static final Template TRT = TechErrorExecutiveTest.TRT;

    static Values values(Map<String, Object> raw, Template t) {
        return TechErrorValues.normalize(raw, t);
    }

    static Change find(TechErrorChangeDiff.Result r, String labelPart) {
        return r.changes().stream().filter(c -> c.label().contains(labelPart)).findFirst().orElseThrow(
                () -> new AssertionError("değişiklik yok: " + labelPart + " in " + r.changes()));
    }

    @Test
    @DisplayName("alan: boştan değere 'set', değerden değere 'change', değerden boşa 'clear'; değerler PDF biçiminde (TR)")
    void fields() {
        Values b = values(Map.of("fields", Map.of("error-rate", 0.42, "total-tx", 1250000)), TR);
        Values a = values(Map.of("fields", Map.of("error-rate", 0.5, "sla-above", false)), TR);
        TechErrorChangeDiff.Result r = TechErrorChangeDiff.diff(TR, b, a, "Mobil", 2026, 9);
        Change rate = find(r, "Teknik hata oranı");
        assertThat(rate.op()).isEqualTo("change");
        assertThat(rate.before()).isEqualTo("%0,42");
        assertThat(rate.after()).isEqualTo("%0,5");
        assertThat(rate.section()).isEqualTo("Mobil Kanalı — Eylül 2026 Oranları (otomatik)");   // yer tutucular çözülmüş
        Change total = find(r, "Toplam işlem adedi");
        assertThat(total.op()).isEqualTo("clear");
        assertThat(total.before()).isEqualTo("1.250.000");
        assertThat(total.after()).isNull();
        Change sla = find(r, "SLA hedefin üstünde mi?");
        assertThat(sla.op()).isEqualTo("set");
        assertThat(sla.after()).isEqualTo("Hayır");
        assertThat(r.changes()).hasSize(3);
    }

    @Test
    @DisplayName("2026-10-17 (4b): raporun o aya özel hedefi ayrı satır — '<alan> — bu ayın hedefi', anahtar g:<alan>, yüzde biçimi; şablon hedefine dönüş 'clear'; aynı hedef değişiklik değil; EN etiketi")
    void targets() {
        Values b = values(Map.of("fields", Map.of("sla-rate", 99.9)), TR);
        Values a = values(Map.of("fields", Map.of("sla-rate", 99.9), "targets", Map.of("sla-rate", 99.5)), TR);
        TechErrorChangeDiff.Result r = TechErrorChangeDiff.diff(TR, b, a, "Mobil", 2026, 9);
        Change g = find(r, "bu ayın hedefi");
        assertThat(g.label()).isEqualTo("Kritik fonksiyonların SLA oranı — bu ayın hedefi");
        assertThat(g.section()).isEqualTo("Mobil Kanalı — Eylül 2026 Verileri");
        assertThat(g.type()).isEqualTo("percent");
        assertThat(g.op()).isEqualTo("set");
        assertThat(g.before()).isNull();
        assertThat(g.after()).isEqualTo("%99,5");
        assertThat(g.key()).isEqualTo("g:sla-rate");
        assertThat(r.changes()).hasSize(1);
        assertThat(find(TechErrorChangeDiff.diff(TR, a, b, "Mobil", 2026, 9), "bu ayın hedefi").op()).isEqualTo("clear");
        Values a2 = values(Map.of("fields", Map.of("sla-rate", 99.9), "targets", Map.of("sla-rate", 99.5)), TR);
        assertThat(TechErrorChangeDiff.diff(TR, a, a2, "Mobil", 2026, 9).changes()).isEmpty();
        Values a3 = values(Map.of("fields", Map.of("sla-rate", 99.9), "targets", Map.of("sla-rate", 99.7)), TR);
        Change ch = find(TechErrorChangeDiff.diff(TR, a, a3, "Mobil", 2026, 9), "bu ayın hedefi");
        assertThat(ch.op()).isEqualTo("change");
        assertThat(ch.before()).isEqualTo("%99,5");
        assertThat(ch.after()).isEqualTo("%99,7");
        Values eb = values(Map.of(), EN);
        Values ea = values(Map.of("targets", Map.of("sla-rate", 99.5)), EN);
        Change eg = find(TechErrorChangeDiff.diff(EN, eb, ea, "Mobile", 2026, 9), "this month's target");
        assertThat(eg.label()).isEqualTo("SLA rate of critical functions — this month's target");
        assertThat(eg.after()).isEqualTo("99.5%");
    }

    @Test
    @DisplayName("EN şablon: sayı / yüzde / evet-hayır İngilizce biçimde")
    void english() {
        Values b = values(Map.of("fields", Map.of("total-tx", 1000)), EN);
        Values a = values(Map.of("fields", Map.of("total-tx", 1250000.5, "sla-above", true)), EN);
        TechErrorChangeDiff.Result r = TechErrorChangeDiff.diff(EN, b, a, "ATM", 2026, 9);
        assertThat(find(r, "Total transactions").after()).isEqualTo("1,250,000.5");
        assertThat(find(r, "Above the SLA target?").after()).isEqualTo("Yes");
    }

    @Test
    @DisplayName("aynı değer değişiklik sayılmaz (5 ile 5.0 aynı); değişiklik yoksa sonuç boş")
    void sameValue() {
        assertThat(TechErrorChangeDiff.same(5L, 5.0)).isTrue();
        assertThat(TechErrorChangeDiff.same(null, null)).isTrue();
        assertThat(TechErrorChangeDiff.same(null, 0)).isFalse();
        Values v = values(Map.of("fields", Map.of("error-rate", 0.42)), TR);
        assertThat(TechErrorChangeDiff.diff(TR, v, v, "K", 2026, 9).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("uzun metin: işaretleme düşer, satırlar birleşir, 300 karakterde '…'; tam uzunluklar ayrıca yazılır")
    void longText() {
        String longNote = "- **Kritik** satır\n" + "x".repeat(400);
        Values b = values(Map.of("fields", Map.of("unmapped-note", "kısa")), TR);
        Values a = values(Map.of("fields", Map.of("unmapped-note", longNote)), TR);
        Change c = find(TechErrorChangeDiff.diff(TR, b, a, "K", 2026, 9), "hakkında açıklama");
        assertThat(c.type()).isEqualTo("textarea");
        assertThat(c.after()).startsWith("- Kritik satır x").endsWith("…").doesNotContain("**").doesNotContain("\n");
        assertThat(c.after().length()).isEqualTo(TechErrorChangeDiff.PREVIEW + 1);
        // Uzunluk yalnız önizleme KESİLDİYSE ve okunan metnin uzunluğu (işaretleme sayılmaz — inceleme 2026-10-14)
        assertThat(c.afterLength()).isGreaterThan(TechErrorChangeDiff.PREVIEW).isLessThan(longNote.length());
        assertThat(c.beforeLength()).isNull();
        assertThat(TechErrorChangeDiff.cutLength("**kısa** ama kalın")).isNull();
    }

    @Test
    @DisplayName("yönetici özeti: ilk elle yazım 'manual', otomatiğe dönüş 'auto', elle metin değişimi 'change'")
    void summary() {
        String sid = TR.sections().stream().filter(s -> "summary".equals(s.kind())).findFirst().orElseThrow().id();
        Values none = values(Map.of(), TR);
        Values manual = values(Map.of("texts", Map.of(sid, "Elle **özet**")), TR);
        Values manual2 = values(Map.of("texts", Map.of(sid, "Başka özet")), TR);
        assertThat(TechErrorChangeDiff.diff(TR, none, manual, "K", 2026, 9).changes().get(0))
                .extracting(Change::op, Change::type, Change::after).containsExactly("manual", "summary", "Elle özet");
        assertThat(TechErrorChangeDiff.diff(TR, manual, none, "K", 2026, 9).changes().get(0).op()).isEqualTo("auto");
        assertThat(TechErrorChangeDiff.diff(TR, manual, manual2, "K", 2026, 9).changes().get(0).op()).isEqualTo("change");
    }

    @Test
    @DisplayName("metin bölümü: saklı metin yoksa varsayılan metin görünür; bilerek boşaltmak 'clear'")
    void textSection() {
        String sid = TR.sections().stream().filter(s -> "text".equals(s.kind())).findFirst().orElseThrow().id();
        Values def = values(Map.of(), TR);
        Values cleared = values(Map.of("texts", Map.of(sid, "")), TR);
        Change c = TechErrorChangeDiff.diff(TR, def, cleared, "K", 2026, 9).changes().get(0);
        assertThat(c.op()).isEqualTo("clear");
        assertThat(c.before()).startsWith("Yıllık trend");
        assertThat(c.after()).isNull();
    }

    @Test
    @DisplayName("konular: eklenen, silinen ve değişen (konu, açıklama, müşteri sayısı) ayrı satırlar; başlık konudan")
    void issues() {
        Map<String, Object> i1 = new LinkedHashMap<>(Map.of("id", "a", "topic", "Kart Limiti", "title_auto", true,
                "description", "İlk açıklama", "customers", 1200));
        Map<String, Object> i2 = new LinkedHashMap<>(Map.of("id", "b", "topic", "Silinecek", "title_auto", true));
        Map<String, Object> i1b = new LinkedHashMap<>(i1);
        i1b.put("customers", 1500);
        i1b.put("description", "Güncel açıklama");
        Map<String, Object> i3 = new LinkedHashMap<>(Map.of("id", "c", "topic", "Yeni Konu", "title_auto", true));
        Values b = values(Map.of("issues", Map.of("issues", List.of(i1, i2))), TR);
        Values a = values(Map.of("issues", Map.of("issues", List.of(i1b, i3))), TR);
        TechErrorChangeDiff.Result r = TechErrorChangeDiff.diff(TR, b, a, "K", 2026, 9);
        assertThat(find(r, "Silinecek").op()).isEqualTo("remove");
        assertThat(find(r, "Yeni Konu").op()).isEqualTo("add");
        Change cust = find(r, "Etkilenen müşteri sayısı");
        assertThat(cust.label()).startsWith("Kart Limiti: Hata Artışı · ");
        assertThat(cust).extracting(Change::before, Change::after).containsExactly("1.200", "1.500");
        assertThat(find(r, "Ne oldu?").after()).isEqualTo("Güncel açıklama");
    }

    @Test
    @DisplayName("konu sırası değişti (PDF'teki sıra): tek 'reorder' satırı — boş olay yazılmaz (inceleme 2026-10-14)")
    void issuesReorder() {
        Map<String, Object> i1 = Map.of("id", "a", "topic", "Birinci", "title_auto", true);
        Map<String, Object> i2 = Map.of("id", "b", "topic", "İkinci", "title_auto", true);
        Values b = values(Map.of("issues", Map.of("issues", List.of(i1, i2))), TR);
        Values a = values(Map.of("issues", Map.of("issues", List.of(i2, i1))), TR);
        TechErrorChangeDiff.Result r = TechErrorChangeDiff.diff(TR, b, a, "K", 2026, 9);
        assertThat(r.changes()).singleElement().satisfies(c -> {
            assertThat(c.op()).isEqualTo("reorder");
            assertThat(c.type()).isEqualTo("issue");
        });
        // yalnız ekleme / silme sırayı "değişti" saymaz
        Values added = values(Map.of("issues", Map.of("issues", List.of(i1, i2, Map.of("id", "c", "topic", "Üçüncü", "title_auto", true)))), TR);
        assertThat(TechErrorChangeDiff.diff(TR, b, added, "K", 2026, 9).changes()).extracting(Change::op).containsExactly("add");
    }

    @Test
    @DisplayName("metin bölümünün varsayılanı yer tutucuları ÇÖZÜLMÜŞ hâliyle karşılaştırılır (düzenleyici ve PDF gibi)")
    void textSectionDefaultIsResolved() {
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TR));
        List<Object> secs = new ArrayList<>((List<?>) raw.get("sections"));
        secs.add(Map.of("id", "kanal-notu", "kind", "text", "title", "Not", "text", "{kanal} Kanalı {ay} {yil} notu"));
        raw.put("sections", secs);
        Template t = TechErrorTemplate.normalize(raw);
        Values def = values(Map.of(), t);
        // görünen varsayılanı aynen yazmak değişiklik değildir
        Values same = values(Map.of("texts", Map.of("kanal-notu", "Mobil Kanalı Eylül 2026 notu")), t);
        assertThat(TechErrorChangeDiff.diff(t, def, same, "Mobil", 2026, 9).changes()).isEmpty();
        Values edited = values(Map.of("texts", Map.of("kanal-notu", "Yeni not")), t);
        Change c = TechErrorChangeDiff.diff(t, def, edited, "Mobil", 2026, 9).changes().get(0);
        assertThat(c.before()).isEqualTo("Mobil Kanalı Eylül 2026 notu");
    }

    @Test
    @DisplayName("önizleme vekil çiftini ortadan bölmez")
    void previewKeepsSurrogatePairs() {
        String text = "a".repeat(TechErrorChangeDiff.PREVIEW - 1) + "😀" + "b".repeat(50);
        String p = TechErrorChangeDiff.preview(text);
        assertThat(p).endsWith("…");
        assertThat(Character.isHighSurrogate(p.charAt(p.length() - 2))).isFalse();
    }

    @Test
    @DisplayName("tablo hücresi: etiket 'Satır — Sütun' (yer tutucu çözülmüş), değer sütun türüyle biçimli")
    void tableCells() {
        String sid = TRT.sections().stream().filter(s -> "table".equals(s.kind())).findFirst().orElseThrow().id();
        Values b = values(Map.of(), TRT);
        Values a = values(Map.of("tables", Map.of(sid, Map.of("total", Map.of("availability", 99.5, "functions", 40)))), TRT);
        TechErrorChangeDiff.Result r = TechErrorChangeDiff.diff(TRT, b, a, "Mobil", 2026, 9);
        Change av = find(r, "Erişilebilirlik");
        assertThat(av.label()).isEqualTo("Mobil Kanalı Toplam — Erişilebilirlik (kesintisiz)");
        assertThat(av).extracting(Change::op, Change::after).containsExactly("set", "%99,5");
        assertThat(find(r, "Fonksiyon adedi").after()).isEqualTo("40");
    }

    @Test
    @DisplayName("en çok 200 satır; fazlası 'more' olarak sayılır")
    void capped() {
        String sid = TRT.sections().stream().filter(s -> "table".equals(s.kind())).findFirst().orElseThrow().id();
        // 40 satır × 6 sütun = 240 hücre — yapay geniş tablo
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TRT));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = new ArrayList<>((List<Map<String, Object>>) raw.get("sections"));
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) rows.add(Map.of("id", "r" + i, "label", "Satır " + i));
        for (int i = 0; i < sections.size(); i++) {
            if (sid.equals(sections.get(i).get("id"))) {
                Map<String, Object> s = new HashMap<>(sections.get(i));
                s.put("rows", rows);
                sections.set(i, s);
            }
        }
        raw.put("sections", sections);
        Template wide = TechErrorTemplate.normalize(raw);
        Map<String, Object> cells = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String c : List.of("functions", "success-tx", "total-tx", "failed-tx", "availability", "volume-share")) row.put(c, i + 1);
            cells.put("r" + i, row);
        }
        TechErrorChangeDiff.Result r = TechErrorChangeDiff.diff(wide, values(Map.of(), wide),
                values(Map.of("tables", Map.of(sid, cells)), wide), "K", 2026, 9);
        assertThat(r.changes()).hasSize(TechErrorChangeDiff.MAX_CHANGES);
        assertThat(r.more()).isEqualTo(240 - TechErrorChangeDiff.MAX_CHANGES);
    }
}
