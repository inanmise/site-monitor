package com.sitemonitor.service.page.scan.site;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Çok sayfalı analizin saf kuralları (prs3 G): sayfa seçimi ve {@code analysis.site} bölümü. */
class SitePagesTest {

    static final String MAIN = "https://shop.example.test/";

    @Test
    @DisplayName("seçim: aynı şema + host + port, uzantısız / .html / .htm, DOM sırası, tekil (sondaki / dahil), ana sayfa hariç, en çok max")
    void selectRules() {
        List<String> links = List.of(
                "https://shop.example.test/about",
                "https://shop.example.test/about/",               // aynı sayfa (sondaki /)
                "https://shop.example.test/contact.html",
                "https://shop.example.test/files/r.pdf",          // belge uzantısı → değil
                "https://shop.example.test/app.php",              // sunucu sayfası (D-4) → evet
                "https://shop.example.test/",                     // ana sayfanın kendisi
                "https://shop.example.test",                      // ana sayfa (boş yol)
                "http://shop.example.test/insecure",              // başka şema
                "https://shop.example.test:8443/other-port",      // başka port
                "https://cdn.example.test/page",                  // başka host
                "https://SHOP.example.test/Upper",                // host büyük harf → aynı host
                "https://shop.example.test/old.htm",
                "https://shop.example.test/search?q=1",
                "mailto:x@example.test",
                "https://shop.example.test/skip/me");
        List<String> got = SitePages.select(links, MAIN, null, u -> u.contains("/skip/"), 20);
        assertThat(got).containsExactly(
                "https://shop.example.test/about",
                "https://shop.example.test/contact.html",
                "https://shop.example.test/app.php",
                "https://SHOP.example.test/Upper",
                "https://shop.example.test/old.htm",
                "https://shop.example.test/search?q=1");
        assertThat(SitePages.select(links, MAIN, null, null, 2))
                .containsExactly("https://shop.example.test/about", "https://shop.example.test/contact.html");
        assertThat(SitePages.select(links, MAIN, null, null, 0)).isEmpty();
        assertThat(SitePages.select(null, MAIN, null, null, 5)).isEmpty();
    }

    @Test
    @DisplayName("D-4: sunucu sayfaları (.aspx .asp .php .jsp .jspx .cfm .shtml .xhtml .do .action) alınır; belge / görsel / betik / stil alınmaz")
    void selectServerPages() {
        List<String> links = List.of(
                "https://shop.example.test/Hakkimizda.aspx",
                "https://shop.example.test/eski.asp",
                "https://shop.example.test/urun.php?id=3",
                "https://shop.example.test/app/liste.jsp",
                "https://shop.example.test/app/sayfa.jspx",
                "https://shop.example.test/takvim.cfm",
                "https://shop.example.test/inc/menu.shtml",
                "https://shop.example.test/faces/giris.xhtml",
                "https://shop.example.test/islem.do",
                "https://shop.example.test/kayit.action",
                "https://shop.example.test/RAPOR.ASPX",
                "https://shop.example.test/f/a.pdf",
                "https://shop.example.test/f/b.docx",
                "https://shop.example.test/i/c.png",
                "https://shop.example.test/j/d.js",
                "https://shop.example.test/s/e.css",
                "https://shop.example.test/x.zip");
        assertThat(SitePages.select(links, MAIN, null, null, 20)).containsExactly(
                "https://shop.example.test/Hakkimizda.aspx",
                "https://shop.example.test/eski.asp",
                "https://shop.example.test/urun.php?id=3",
                "https://shop.example.test/app/liste.jsp",
                "https://shop.example.test/app/sayfa.jspx",
                "https://shop.example.test/takvim.cfm",
                "https://shop.example.test/inc/menu.shtml",
                "https://shop.example.test/faces/giris.xhtml",
                "https://shop.example.test/islem.do",
                "https://shop.example.test/kayit.action",
                "https://shop.example.test/RAPOR.ASPX");
    }

    @Test
    @DisplayName("seçim: host / şema yönlendirme SONRASI adresten; ikisi de ana sayfa sayılır")
    void selectUsesFinalUrl() {
        List<String> links = List.of("https://www.example.test/home", "https://www.example.test/a", "http://example.test/b");
        assertThat(SitePages.select(links, "http://example.test/", "https://www.example.test/home", null, 10))
                .containsExactly("https://www.example.test/a");
    }

    @Test
    @DisplayName("seçim: bozuk / çok uzun / boş adres atlanır, hariç tutma yüklemi fırlatırsa dahil edilir")
    void selectRobust() {
        List<String> links = new ArrayList<>();
        links.add(null);
        links.add("");
        links.add("https://shop.example.test/" + "a".repeat(5000));
        links.add("https://shop.example.test/ok");
        links.add("ht!tp://bad url");
        assertThat(SitePages.select(links, MAIN, null, u -> { throw new IllegalStateException("x"); }, 10))
                .containsExactly("https://shop.example.test/ok");
    }

    static SitePages.Page done(String url, int grade, long html, Map<String, Long> res) {
        Map<String, SitePages.Resource> m = new LinkedHashMap<>();
        res.forEach((u, b) -> m.put(u, new SitePages.Resource(u, u.endsWith(".css") ? "CSS" : "IMAGE", b)));
        return new SitePages.Page(url, SitePages.DONE, null, grade, "B", 1200L, 50_000L, res.size() + 1, 80, 70, 60,
                List.of("LARGE_IMAGES"), html, m, false);
    }

    @Test
    @DisplayName("bölüm: ortak = ≥ 2 tamamlanan sayfada; tekil bayt = HTML + yalnız o sayfanınkiler; toplamlar; bitmeyen sayfa null")
    void sectionSharedAndUnique() {
        String css = "https://shop.example.test/s.css";
        String bg = "https://shop.example.test/bg.png";
        String hero = "https://shop.example.test/hero.png";
        String about = "https://shop.example.test/about.png";
        List<SitePages.Page> pages = List.of(
                done(MAIN, 81, 1000, Map.of(css, 300L, bg, 5000L, hero, 20_000L)),
                done("https://shop.example.test/about", 70, 800, Map.of(css, 300L, bg, 5000L, about, 7000L)),
                done("https://shop.example.test/c", 60, 600, Map.of(css, 300L)),
                SitePages.Page.failed("https://shop.example.test/broken", "PAGE_HTTP:500"),
                SitePages.Page.skipped("https://shop.example.test/late", "NOT_SCANNED:TIME"));
        Map<String, Object> s = SitePages.section(pages);

        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) s.get("pages");
        assertThat(rows).hasSize(5);
        assertThat(rows.get(0).get("unique_bytes")).as("HTML + hero").isEqualTo(21_000L);
        assertThat(rows.get(0).get("shared_count")).isEqualTo(2);
        assertThat(rows.get(1).get("unique_bytes")).as("HTML + about.png").isEqualTo(7_800L);
        assertThat(rows.get(2).get("unique_bytes")).as("yalnız HTML").isEqualTo(600L);
        assertThat(rows.get(2).get("shared_count")).isEqualTo(1);
        assertThat(rows.get(3).get("status")).isEqualTo("failed");
        assertThat(rows.get(3).get("error")).isEqualTo("PAGE_HTTP:500");
        assertThat(rows.get(3).get("unique_bytes")).isNull();
        assertThat(rows.get(3).get("grade")).isNull();
        assertThat(rows.get(4).get("status")).isEqualTo("skipped");
        assertThat(rows.get(4).get("capped")).isEqualTo(true);
        @SuppressWarnings("unchecked") Map<String, Object> scores = (Map<String, Object>) rows.get(1).get("scores");
        assertThat(scores).containsEntry("security", 80).containsEntry("seo", 70).containsEntry("accessibility", 60);
        assertThat(rows.get(0).get("top_recs")).isEqualTo(List.of("LARGE_IMAGES"));
        assertThat(rows.get(0).get("requests")).isEqualTo(4);

        @SuppressWarnings("unchecked") List<Map<String, Object>> shared = (List<Map<String, Object>>) s.get("shared");
        assertThat(shared).extracting(m -> m.get("url")).containsExactly(css, bg);
        assertThat(shared.get(0).get("pages")).isEqualTo(3);
        assertThat(shared.get(1).get("pages")).isEqualTo(2);
        assertThat(shared.get(1).get("category")).isEqualTo("IMAGE");
        assertThat(shared.get(1).get("bytes")).isEqualTo(5000L);

        @SuppressWarnings("unchecked") Map<String, Object> totals = (Map<String, Object>) s.get("totals");
        assertThat(totals).containsEntry("pages", 5).containsEntry("scanned", 3).containsEntry("failed", 1)
                .containsEntry("skipped", 1).containsEntry("shared", 2);
        assertThat(totals.get("avg_grade")).as("(81 + 70 + 60) / 3 = 70,3").isEqualTo(70);
        assertThat(totals.get("total_unique_bytes")).as("3 HTML + her nesne bir kez")
                .isEqualTo(1000L + 800 + 600 + 300 + 5000 + 20_000 + 7000);
        assertThat(SitePages.scanned(pages)).isEqualTo(3);
        assertThat(SitePages.anyCapped(pages)).isTrue();
        assertThat(s.get("incomplete")).as("O-3: taranmayan sayfa site eksikliğidir").isEqualTo(true);

        // Açılamayan (failed) sayfa eksiklik değildir — sonuçtur
        Map<String, Object> complete = SitePages.section(List.of(pages.get(0), pages.get(1), pages.get(3)));
        assertThat(complete.get("incomplete")).isEqualTo(false);
    }

    @Test
    @DisplayName("bölüm: hiç tamamlanan sayfa yoksa ortalama ve toplam null (bilinmiyor ≠ 0)")
    void sectionNothingDone() {
        Map<String, Object> s = SitePages.section(List.of(SitePages.Page.failed(MAIN, "PAGE_BLOCKED")));
        @SuppressWarnings("unchecked") Map<String, Object> totals = (Map<String, Object>) s.get("totals");
        assertThat(totals.get("avg_grade")).isNull();
        assertThat(totals.get("total_unique_bytes")).isNull();
        assertThat(totals.get("scanned")).isEqualTo(0);
        assertThat(SitePages.anyCapped(List.of(SitePages.Page.failed(MAIN, "PAGE_BLOCKED")))).isFalse();
    }

    @Test
    @DisplayName("bölüm: ortak liste en çok 50 (çok sayfada olan önce, eşitlikte büyük bayt); adres sorgusundaki gizli değer maskelenir")
    void sharedCapOrderAndMask() {
        Map<String, Long> res = new LinkedHashMap<>();
        for (int i = 0; i < 60; i++) res.put("https://shop.example.test/i" + i + ".png", 100L + i);
        res.put("https://shop.example.test/x.js?token=GIZLI42", 10_000L);
        List<SitePages.Page> pages = List.of(done(MAIN, 90, 10, res), done("https://shop.example.test/b", 90, 10, res));
        Map<String, Object> s = SitePages.section(pages);
        @SuppressWarnings("unchecked") List<Map<String, Object>> shared = (List<Map<String, Object>>) s.get("shared");
        assertThat(shared).hasSize(SitePages.MAX_SHARED);
        assertThat(shared.get(0).get("bytes")).isEqualTo(10_000L);
        assertThat((String) shared.get(0).get("url")).startsWith("https://shop.example.test/x.js?token=");
        assertThat(shared.get(1).get("bytes")).isEqualTo(159L);
        @SuppressWarnings("unchecked") Map<String, Object> totals = (Map<String, Object>) s.get("totals");
        assertThat(totals.get("shared")).isEqualTo(61);
        String all = s.toString();
        assertThat(all).doesNotContain("GIZLI42");
    }

    @Test
    @DisplayName("öne çıkan öneriler: ilk 5 kod, bozuk satırlar atlanır")
    void topRecs() {
        List<Object> recs = new ArrayList<>();
        recs.add(Map.of("code", "A"));
        recs.add("bozuk");
        recs.add(Map.of("severity", "high"));
        for (String c : List.of("B", "C", "D", "E", "F")) recs.add(Map.of("code", c));
        assertThat(SitePages.topRecs(recs)).containsExactly("A", "B", "C", "D", "E");
        assertThat(SitePages.topRecs(null)).isEmpty();
        assertThat(SitePages.topRecs("x")).isEmpty();
    }
}
