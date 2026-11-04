package com.sitemonitor.service.page.scan.budget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "En çok büyüyen nesneler" (F2): yalnız sayfa yükü, indirilmiş, boyutu bilinen, kesilmemiş, başarılı nesneler sayılır; en az iki
 * analizde görülmüş, EN YENİ analizde hâlâ yüklenen ve büyümüş nesneler bayt artışına göre sıralanır (≤ 20).
 */
class ResourceGrowthTest {

    static String item(String url, String category, long bytes, String extra) {
        return "{\"url\":\"" + url + "\",\"category\":\"" + category + "\",\"bytes\":" + bytes
                + ",\"page_load\":true,\"verdict\":\"ok\",\"status\":200,\"headers\":{\"content_type\":\"x\"},"
                + "\"findings\":[\"LARGE_IMAGES\"]" + (extra == null ? "" : "," + extra) + "}";
    }

    static String items(String... parts) {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (String p : parts) j.add(p);
        return j.toString();
    }

    @Test
    @DisplayName("ayrıştırma: sayılmayan nesneler elenir; iç içe başlık / bulgu nesneleri okunmaz")
    void parseFilters() {
        String json = items(
                item("https://x/a.js", "JS", 1000, null),
                item("https://x/link", "LINK", 9000, "\"page_load\":false"),
                item("https://x/skip.mp4", "MEDIA", 9000, "\"verdict\":\"skipped\""),
                item("https://x/broken.png", "IMAGE", 9000, "\"verdict\":\"broken\""),
                item("https://x/unk.css", "CSS", 9000, "\"size_source\":\"unknown\""),
                item("https://x/cut.png", "IMAGE", 9000, "\"truncated\":true"),
                // algoritma 1: karar yok → HTTP durum kuralı
                "{\"url\":\"https://x/old.css\",\"category\":\"CSS\",\"bytes\":300,\"page_load\":true,\"status\":200}",
                "{\"url\":\"https://x/old404.css\",\"category\":\"CSS\",\"bytes\":300,\"page_load\":true,\"status\":404}",
                // aynı adres iki kez → büyük bayt
                item("https://x/a.js", "JS", 1500, null),
                // bozuk alan türleri: url nesne, bytes metin → sayılmaz (iç nesne alanları dış nesneye karışmaz)
                "{\"url\":{\"url\":\"https://x/inner\"},\"bytes\":10,\"page_load\":true,\"status\":200}",
                "{\"url\":\"https://x/str\",\"bytes\":\"10\",\"page_load\":true,\"status\":200}");
        ResourceGrowth.ScanObjects s = ResourceGrowth.parse(7, json);
        assertThat(s.scanId()).isEqualTo(7);
        assertThat(s.objects()).containsOnlyKeys("https://x/a.js", "https://x/old.css");
        assertThat(s.objects().get("https://x/a.js").bytes()).isEqualTo(1500);
        assertThat(s.objects().get("https://x/a.js").category()).isEqualTo("JS");
    }

    @Test
    @DisplayName("bozuk / boş ayrıntı → boş küme, asla fırlatmaz")
    void parseBroken() {
        assertThat(ResourceGrowth.parse(1, null).objects()).isEmpty();
        assertThat(ResourceGrowth.parse(1, "").objects()).isEmpty();
        assertThat(ResourceGrowth.parse(1, "{\"not\":\"array\"}").objects()).isEmpty();
        assertThat(ResourceGrowth.parse(1, "[{\"url\":\"https://x/a\",\"bytes\":1,\"page_load\":tru").objects()).isEmpty();
        assertThat(ResourceGrowth.parse(1, "[1,2,3]").objects()).isEmpty();
    }

    @Test
    @DisplayName("büyüme: ≥ 2 analizde görülen, en yenide hâlâ yüklenen ve büyüyen nesneler, bayt artışına göre")
    void computeGrowth() {
        List<ResourceGrowth.ScanObjects> scans = List.of(
                ResourceGrowth.parse(1, items(
                        item("https://x/app.js", "JS", 100_000, null),
                        item("https://x/hero.png", "IMAGE", 200_000, null),
                        item("https://x/gone.css", "CSS", 1_000, null),
                        item("https://x/shrink.css", "CSS", 50_000, null),
                        item("https://x/zero.svg", "IMAGE", 0, null))),
                ResourceGrowth.parse(2, items(
                        item("https://x/app.js", "JS", 150_000, null),
                        item("https://x/gone.css", "CSS", 9_000_000, null))),
                ResourceGrowth.parse(3, items(
                        item("https://x/app.js", "JS", 180_000, null),
                        item("https://x/hero.png", "IMAGE", 900_000, null),
                        item("https://x/shrink.css", "CSS", 40_000, null),
                        item("https://x/zero.svg", "IMAGE", 2_000, null),
                        item("https://x/new.js", "JS", 999_999, null))));
        List<Map<String, Object>> g = ResourceGrowth.compute(scans);
        assertThat(g).extracting(m -> m.get("url")).containsExactly(
                "https://x/hero.png", "https://x/app.js", "https://x/zero.svg");
        Map<String, Object> hero = g.get(0);
        assertThat(hero).containsEntry("category", "IMAGE").containsEntry("first_bytes", 200_000L)
                .containsEntry("last_bytes", 900_000L).containsEntry("delta_bytes", 700_000L).containsEntry("delta_pct", 350.0)
                .containsEntry("scans", 2).containsEntry("first_scan_id", 1L).containsEntry("last_scan_id", 3L);
        assertThat(g.get(1)).containsEntry("scans", 3).containsEntry("delta_pct", 80.0);
        // ilk boyut 0 → yüzde yok (sonsuz değil)
        assertThat(g.get(2)).containsEntry("delta_pct", null);
    }

    @Test
    @DisplayName("tek analiz ya da hiç → boş; sonuç en çok 20, eşitlikte adres sırası")
    void bounds() {
        assertThat(ResourceGrowth.compute(List.of())).isEmpty();
        assertThat(ResourceGrowth.compute(null)).isEmpty();
        List<String> first = new ArrayList<>();
        List<String> last = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String url = String.format("https://x/f%02d.js", i);
            first.add(item(url, "JS", 1_000, null));
            last.add(item(url, "JS", 2_000, null));
        }
        List<Map<String, Object>> g = ResourceGrowth.compute(List.of(
                ResourceGrowth.parse(1, items(first.toArray(String[]::new))),
                ResourceGrowth.parse(2, items(last.toArray(String[]::new)))));
        assertThat(g).hasSize(ResourceGrowth.MAX_RESULTS);
        assertThat(g.get(0).get("url")).isEqualTo("https://x/f00.js");
        assertThat(g.get(19).get("url")).isEqualTo("https://x/f19.js");
    }

    @Test
    @DisplayName("yüzde: bir ondalık, yarı yukarı")
    void pct() {
        assertThat(ResourceGrowth.pct(3, 4)).isEqualTo(33.3);
        assertThat(ResourceGrowth.pct(8, 9)).isEqualTo(12.5);
        assertThat(ResourceGrowth.pct(0, 5)).isNull();
    }
}
