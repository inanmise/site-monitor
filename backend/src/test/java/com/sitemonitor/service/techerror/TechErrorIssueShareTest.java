package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anomali konusunun payı (2026-10-18). {@link #SHARE_TABLE} istemcideki {@code techErrorIssueShare.test.js} tarafından bu
 * KAYNAKTAN okunur — Java ve JS aynı payı vermeli. Şablon iki tarafta elle aynıdır ({@link #template(String)}).
 */
class TechErrorIssueShareTest {

    /**
     * Gösterge bölümü: "q" (evet/hayır), "total" (sayı, "q" Hayır ise muaf), "pct" (yüzde); anomali bölümü "iss",
     * {@code ratio_of} satırdan ("-" = pay sorulmuyor).
     */
    static Template template(String ratioOf) {
        Map<String, Object> issues = new LinkedHashMap<>();
        issues.put("id", "iss");
        issues.put("kind", "issues");
        issues.put("title", "Anomaliler");
        issues.put("ask_time", true);
        if (ratioOf != null) issues.put("ratio_of", ratioOf);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("lang", "tr");
        raw.put("title", "{kanal}");
        raw.put("sections", List.of(
                Map.of("id", "m", "kind", "metrics", "title", "Veriler", "fields", List.of(
                        Map.of("id", "q", "label", "Kesinti oldu mu?", "type", "yesno"),
                        Map.of("id", "total", "label", "Teknik hata adedi", "type", "number", "skip_if_no", "q"),
                        Map.of("id", "pct", "label", "Oran", "type", "percent"))),
                issues));
        return TechErrorTemplate.normalize(raw);
    }

    /**
     * Pay doğruluk tablosu — JS testi AYNI tabloyu bu kaynaktan okur. Satır: ad; ratio_of ("-" = yok); "q" cevabı ("-" =
     * cevapsız); toplam ("-" = boş); konunun hata adedi ("-" = boş); beklenen durum ("-" = pay sorulmuyor); beklenen değer
     * ("-" = yok); eksik ("-" = yok).
     */
    static final String[][] SHARE_TABLE = {
            { "pay hesaplanır", "total", "-", "1000", "34", "OK", "3.4", "-" },
            { "küçük pay 2 anlamlı hane (sahte %0 yok)", "total", "true", "1000000", "3", "OK", "0.0003", "-" },
            { "yarım yukarı ondalık gösterimden (1,4749999… → 1,47)", "total", "-", "4000", "59", "OK", "1.47", "-" },
            { "üçte bir", "total", "-", "3", "1", "OK", "33.33", "-" },
            { "konunun tamamı", "total", "-", "250", "250", "OK", "100", "-" },
            { "konu hatası 0 → %0 (gerçek sıfır)", "total", "-", "1000", "0", "OK", "0", "-" },
            { "konu adedi toplamdan büyük → pay yok", "total", "-", "100", "101", "OVER", "-", "-" },
            { "toplam 0", "total", "-", "0", "5", "ZERO", "-", "-" },
            { "toplam girilmedi", "total", "-", "-", "5", "MISSING", "-", "total" },
            { "konunun adedi girilmedi", "total", "-", "1000", "-", "MISSING", "-", "errors" },
            { "toplam muaf (soru Hayır)", "total", "false", "1000", "5", "EXEMPT", "-", "-" },
            { "pay alanı şablonda yok", "nope", "-", "1000", "5", "BAD_REF", "-", "-" },
            { "pay alanı yüzde (sayı değil)", "pct", "-", "1000", "5", "BAD_REF", "-", "-" },
            { "pay sorulmuyor", "-", "-", "1000", "5", "-", "-", "-" },
    };

    static Map<String, Object> fields(String q, String total) {
        Map<String, Object> f = new HashMap<>();
        if (!"-".equals(q)) f.put("q", Boolean.parseBoolean(q));
        if (!"-".equals(total)) f.put("total", Long.parseLong(total));
        // Muaf alanın değeri saklanmaz (okuma düşürür) — hesap girdisi de onu görmez
        if (Boolean.FALSE.equals(f.get("q"))) f.remove("total");
        return f;
    }

    @Test
    @DisplayName("SHARE_TABLE: pay = konunun hata adedi ÷ tüm teknik hatalar × 100 (oranlarla aynı yuvarlama); boş / 0 / aşan / muaf / uygunsuz alan → pay yok, nedeniyle")
    void table() {
        assertThat(SHARE_TABLE.length).isGreaterThanOrEqualTo(12);
        for (String[] row : SHARE_TABLE) {
            Template t = template("-".equals(row[1]) ? null : row[1]);
            Section s = t.section("iss");
            Map<String, Field> byId = new HashMap<>();
            for (Field f : t.allFields()) byId.put(f.id(), f);
            Map<String, Object> f = fields(row[2], row[3]);
            Issue i = new Issue("k", "Konu", "", true, "", null, "-".equals(row[4]) ? null : Long.parseLong(row[4]));
            TechErrorIssueShare.Result r = TechErrorIssueShare.of(s, i, byId, f);
            if ("-".equals(row[5])) {
                assertThat(r).as(row[0]).isNull();
                continue;
            }
            assertThat(r.status().name()).as(row[0]).isEqualTo(row[5]);
            if ("-".equals(row[6])) assertThat(r.value()).as(row[0]).isNull();
            else assertThat(r.value()).as(row[0]).isEqualTo(Double.parseDouble(row[6]));
            assertThat(r.missing()).as(row[0]).isEqualTo("-".equals(row[7]) ? null : row[7]);
        }
    }

    @Test
    @DisplayName("apply: pay konulara yazılır (hesaplar uygulanırken — TechErrorCalc.apply), sonuç yoksa boş; pay sorulmayan raporda AYNI nesne")
    void applyWritesShares() {
        Template t = template("total");
        Values v = new Values(Map.of("total", 2000L),
                Map.of("iss", List.of(new Issue("a", "A", "", true, "", null, 50L), new Issue("b", "B", "", true, "", null, null))),
                Map.of());
        Values out = TechErrorCalc.apply(t, v, null);
        assertThat(out.issues().get("iss").get(0).share()).isEqualTo(2.5);
        assertThat(out.issues().get("iss").get(1).share()).isNull();
        assertThat(TechErrorValues.toJson(out)).contains("\"share\":2.5");
        // Toplam silinince pay da kalkar (bayat değer kalmaz)
        Values gone = TechErrorIssueShare.apply(t, out.withFields(Map.of()));
        assertThat(gone.issues().get("iss").get(0).share()).isNull();
        // Pay sorulmuyor → aynı nesne
        Template noShare = template(null);
        assertThat(TechErrorIssueShare.apply(noShare, v)).isSameAs(v);
        // Varsayılan şablon: "Ay içindeki teknik hata adedi"ne göre
        Template d = TechErrorTemplate.defaults("tr");
        Values dv = TechErrorValues.normalize(Map.of("fields", Map.of("error-count", 5000, "total-tx", 100000),
                "issues", Map.of("issues", List.of(Map.of("id", "k", "topic", "T", "title_auto", true, "errors", 170)))), d);
        assertThat(TechErrorCalc.apply(d, dv, null).issues().get("issues").get(0).share()).isEqualTo(3.4);
    }

    @Test
    @DisplayName("kısaltma (2026-10-18): pay SAYIYLA hesaplanır ve kısaltma üsleri korunur — '1,25M' yazılan konu adedi 1.250.000'dir (üs yalnız gösterim)")
    void shareUsesNumbersAndKeepsUnits() {
        Template d = TechErrorTemplate.defaults("tr");
        Values dv = TechErrorValues.normalize(Map.of("fields", Map.of("error-count", 50_000_000),
                "issues", Map.of("issues", List.of(Map.of("id", "k", "topic", "T", "title_auto", true, "errors", 1_250_000))),
                "units", Map.of("error-count", 6, "issues.issues.k.errors", 6)), d);
        Values out = TechErrorCalc.apply(d, dv, null);
        assertThat(out.issues().get("issues").get(0).share()).isEqualTo(2.5);
        assertThat(out.units()).containsEntry("error-count", 6).containsEntry("issues.issues.k.errors", 6);
        assertThat(out.issueUnit("issues", "k", "errors")).isEqualTo(6);
        // Aynı sayılar kısaltmasız: aynı pay
        Values plain = TechErrorValues.normalize(Map.of("fields", Map.of("error-count", 50_000_000),
                "issues", Map.of("issues", List.of(Map.of("id", "k", "topic", "T", "title_auto", true, "errors", 1_250_000)))), d);
        assertThat(TechErrorCalc.apply(d, plain, null).issues().get("issues").get(0).share()).isEqualTo(2.5);
    }
}
