package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sayfa Hızı alarm / çözüm postası (2026-11-14, Sayfa Hızı zenginleştirme 1. sürüm, madde 1): eşik → ölçülen tablosu (her ihlal),
 * süre kırılımı, kısmi ölçüm notu ve en ağır kaynaklar. Yeni bağlam anahtarları ({@code ps_thresholds}, {@code ps_phases},
 * {@code ps_partial}) yoksa posta bugünküyle BİREBİR aynı kalır — eski alarmın yeniden uyarısı / çözümü değişmez.
 */
class PageSpeedAlertMailTest {

    private final EmailSamples s = new EmailSamples();
    private static final String URL = "https://www.example.com/";

    private static Map<String, Object> oldCtx() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("monitor_name", "Ana sayfa");
        c.put("url", URL);
        c.put("response_ms", 6200);
        c.put("total_bytes", 2_000_000L);
        c.put("breached_metrics", List.of("LOAD"));
        c.put("checked_at", "2026-11-14T07:00:00");
        return c;
    }

    private static Map<String, Object> newCtx() {
        Map<String, Object> c = oldCtx();
        c.put("ps_thresholds", "LOAD\t3000\t6200\t1\t0\nMINSIZE\t50\t12\t1\t0\nJS\t800\t1450\t1\t1\nTHIRD\t500\t\t0\t0");
        c.put("ps_phases", "dns\t12\nconnect\t28\ntls\t46\nserver\t310\ndownload\t180");
        c.put("ps_partial", true);
        c.put("heavy_rows", "JS\thttps://cdn.example.com/main.js\t820\nIMG\thttps://www.example.com/hero.jpg\t610");
        c.put("heavy_total", 40);
        return c;
    }

    private String html(Map<String, Object> ctx) {
        return s.svc.buildAlertEmailHtml("[Site Monitor] YÜKSEK · Ana sayfa", "YÜKSEK: eşik aşıldı", URL, "HIGH",
                "PAGESPEED_SLOW", null, ctx);
    }

    private String text(Map<String, Object> ctx) {
        return s.svc.buildAlertEmailText("[Site Monitor] YÜKSEK · Ana sayfa", "YÜKSEK: eşik aşıldı", URL, "HIGH",
                "PAGESPEED_SLOW", null, ctx);
    }

    @Test
    @DisplayName("Yeni anahtar yoksa posta bugünküyle birebir: heavy_rows tek başına (eski bağlam) hiçbir şey eklemez")
    void withoutNewKeysOutputIsUnchanged() {
        Map<String, Object> withHeavy = oldCtx();
        withHeavy.put("heavy_rows", "JS\thttps://cdn.example.com/main.js\t820");
        withHeavy.put("heavy_total", 3);
        assertThat(html(withHeavy)).isEqualTo(html(oldCtx()));
        assertThat(text(withHeavy)).isEqualTo(text(oldCtx()));
        assertThat(html(oldCtx())).doesNotContain("Eşikler").doesNotContain("En Ağır Kaynaklar");

        String resHtml = s.svc.buildResolutionEmailHtml(URL, "PAGESPEED_SLOW", "HIGH", null, "Sistem (otomatik)",
                "2026-11-14T08:00:00", "2026-11-14T07:00:00", withHeavy, "Takım A", null);
        String resHtmlOld = s.svc.buildResolutionEmailHtml(URL, "PAGESPEED_SLOW", "HIGH", null, "Sistem (otomatik)",
                "2026-11-14T08:00:00", "2026-11-14T07:00:00", oldCtx(), "Takım A", null);
        assertThat(resHtml).isEqualTo(resHtmlOld).doesNotContain("Çözülen Alarm");
    }

    @Test
    @DisplayName("Eşik tablosu her ihlali listeler; alt sınır ≥, asgari boyut ≥ eşik; ölçülemeyen gri; HTML = düz metin")
    void alertMailCarriesThresholdTablePhasesAndHeavy() {
        String h = html(newCtx());
        String t = text(newCtx());
        for (String part : List.of("Eşikler", "Yükleme süresi", "6200 ms", "≤ 3000 ms", "Asgari sayfa boyutu", "≥ 50 KB",
                "Beklenenden küçük", "JavaScript", "≥ 1450 KB", "Üçüncü taraf", "Ölçülemedi",
                EmailTemplateBuilder.PS_PARTIAL_TEXT, "DNS 12 ms", "Sunucu 310 ms", "HTML indirme 180 ms",
                "En Ağır Kaynaklar", "https://cdn.example.com/main.js", "820", "38 kaynak daha")) {
            assertThat(h).as("HTML: %s", part).contains(part.replace("≥", "≥"));
            assertThat(t).as("Metin: %s", part).contains(part);
        }
    }

    @Test
    @DisplayName("İnceleme 2026-11-17: ALT SINIR satırı eşiği aşmadıysa 'Eşik içinde' DENMEZ (kararsız, gri); asgari boyutta "
            + "eşiğin üstündeki alt sınır kanıtlı geçer; tam ölçüm 'Eşik içinde'")
    void lowerBoundRowIsNotWithin() {
        Map<String, Object> c = oldCtx();
        c.put("ps_thresholds", "SIZE\t3000\t1200\t0\t1\nIMG\t800\t420\t0\t1\nMINSIZE\t50\t80\t0\t1\nTTFB\t800\t200\t0\t0"
                + "\nGROWSIZE\t20|1000\t1100\t0\t1");
        c.put("ps_partial", true);
        String h = html(c);
        String t = text(c);
        assertThat(t).contains("Sayfa boyutu: ≥ 1200 KB (eşik ≤ 3000 KB) — Karar verilemedi (kısmi ölçüm — değer alt sınır)");
        assertThat(t).contains("Görseller: ≥ 420 KB (eşik ≤ 800 KB) — Karar verilemedi");
        assertThat(t).contains("Asgari sayfa boyutu: ≥ 80 KB (eşik ≥ 50 KB) — Eşik içinde");
        assertThat(t).contains("İlk bayt (TTFB): 200 ms (eşik ≤ 800 ms) — Eşik içinde");
        assertThat(t).contains("Sayfa ağırlığı büyümesi:").contains("— Karar verilemedi");
        assertThat(h).contains("Karar verilemedi (kısmi ölçüm — değer alt sınır)");
        // Kararsız satır yeşil (başarı rengi) boyanmaz; yalnız kanıtlı geçen iki satır yeşil
        var undecided = new EmailTemplateBuilder.PsRow("SIZE", "3000", "1200", false, true);
        assertThat(EmailTemplateBuilder.psUndecided(undecided)).isTrue();
        assertThat(EmailTemplateBuilder.psUndecided(new EmailTemplateBuilder.PsRow("SIZE", "3000", "4200", true, true))).isFalse();
        assertThat(EmailTemplateBuilder.psUndecided(new EmailTemplateBuilder.PsRow("MINSIZE", "50", "12", false, true))).isTrue();
        assertThat(EmailTemplateBuilder.psUndecided(new EmailTemplateBuilder.PsRow("MINSIZE", "50", "80", false, true))).isFalse();
        assertThat(EmailTemplateBuilder.psUndecided(new EmailTemplateBuilder.PsRow("SIZE", "3000", "1200", false, false))).isFalse();
        assertThat(java.util.regex.Pattern.compile("#16a34a[^>]*>Karar verilemedi").matcher(h).find()).isFalse();
        assertThat(java.util.regex.Pattern.compile("#71717a[^>]*>Karar verilemedi").matcher(h).find()).isTrue();
        // Masaüstü tablosu + telefon istif kopyası: kanıtlı geçen 2 satır × 2 yeşil hücre
        long greenCells = java.util.regex.Pattern.compile("#16a34a[^>]*>Eşik içinde").matcher(h).results().count();
        assertThat(greenCells).isEqualTo(4);
    }

    @Test
    @DisplayName("Çözüm postası alarm anının eşik tablosunu, süre kırılımını ve en ağır kaynakları taşır (HTML = metin)")
    void resolutionMailCarriesSnapshot() {
        String h = s.svc.buildResolutionEmailHtml(URL, "PAGESPEED_SLOW", "HIGH", null, "Sistem (otomatik)",
                "2026-11-14T08:00:00", "2026-11-14T07:00:00", newCtx(), "Takım A", null);
        String t = s.svc.buildResolutionEmailText(URL, "PAGESPEED_SLOW", "Sistem (otomatik)", "2026-11-14T08:00:00",
                "2026-11-14T07:00:00", newCtx());
        for (String part : List.of("Çözülen Alarm", "Sayfa Hızı Eşik Aşımı", "Eşikler (alarm anı)", "6200 ms",
                "Süre Kırılımı (alarm anı)", "DNS 12 ms", "En Ağır Kaynaklar (alarm anı)", "https://cdn.example.com/main.js")) {
            assertThat(h).as("HTML: %s", part).contains(part);
            assertThat(t).as("Metin: %s", part).contains(part);
        }
    }

    @Test
    @DisplayName("Anahtarlar alarm anlık görüntüsüne girer (yeniden uyarı / elle gönderim / çözüm aynı kanıtı taşır)")
    void snapshotKeysAreKept() {
        assertThat(EscalationService.RESOLVED_CONTEXT_KEYS)
                .contains("ps_thresholds", "ps_phases", "ps_partial", "heavy_rows", "heavy_total");
    }

    @Test
    @DisplayName("Başka türün postasına (HTTP yavaş) sayfa hızı bölümü girmez")
    void otherTypesUntouched() {
        Map<String, Object> c = newCtx();
        String h = s.svc.buildAlertEmailHtml("x", "y", URL, "HIGH", "HTTP_SLOW", null, c);
        assertThat(h).doesNotContain("Eşikler").doesNotContain("En Ağır Kaynaklar");
    }
}
