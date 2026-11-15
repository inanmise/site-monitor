package com.sitemonitor.service.page.scan.site;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Tüm site tarama kuyruğu (2026-11-15): aday kuralları, sıra, tavanlar. */
class SiteCrawlTest {

    static final String BASE = "https://a.example/";

    @Test
    @DisplayName("aday kuralı: aynı köken, sayfa uzantısı, hariç tutma, sondaki / kopyası, ana sayfa ve atlananlar yok")
    void acceptRules() {
        SiteCrawl c = new SiteCrawl(BASE, BASE + "home", u -> u.contains("/skip/"), Set.of(BASE + "broken"));
        int added = c.offer(List.of(BASE + "a", BASE + "a/", BASE + "b.html", BASE + "c.pdf", BASE + "img.png",
                "https://other.example/x", "http://a.example/a2", BASE + "skip/x", BASE, BASE + "home", BASE + "broken",
                BASE + "d#frag", BASE + "d", BASE + "p.php?id=1"), SiteCrawl.SOURCE_LINK);
        List<String> order = new ArrayList<>();
        SiteCrawl.Entry e;
        while ((e = c.poll()) != null) order.add(e.url());
        assertThat(order).containsExactly(BASE + "a", BASE + "b.html", BASE + "d", BASE + "p.php?id=1");
        assertThat(added).isEqualTo(4);
        assertThat(c.fromLinks()).isEqualTo(4);
        assertThat(c.fromSitemap()).isZero();
    }

    @Test
    @DisplayName("sıra: genişlik öncelikli; pushFront kuyruğun başına; markKnown tekrarı engeller; kaynak sayaçları")
    void orderAndSources() {
        SiteCrawl c = new SiteCrawl(BASE, null, null, null);
        c.offer(List.of(BASE + "1", BASE + "2"), SiteCrawl.SOURCE_LINK);
        c.offer(List.of(BASE + "2", BASE + "3"), SiteCrawl.SOURCE_SITEMAP);
        SiteCrawl.Entry first = c.poll();
        assertThat(first.url()).isEqualTo(BASE + "1");
        c.pushFront(first);
        assertThat(c.remaining(10)).extracting(SiteCrawl.Entry::url).containsExactly(BASE + "1", BASE + "2", BASE + "3");
        assertThat(c.remaining(10)).extracting(SiteCrawl.Entry::source).containsExactly("link", "link", "sitemap");
        c.markKnown(BASE + "4");
        assertThat(c.offer(List.of(BASE + "4"), SiteCrawl.SOURCE_LINK)).isZero();
        assertThat(c.fromSitemap()).isEqualTo(1);
        assertThat(c.queued()).isEqualTo(3);
        assertThat(c.remaining(2)).hasSize(2);
    }

    @Test
    @DisplayName("tavanlar: tek çağrıda en çok MAX_LINKS_PER_PAGE değerlendirilir; bilinen tavanı aşan adres sayılır, kuyruğa girmez")
    void caps() {
        SiteCrawl c = new SiteCrawl(BASE, null, null, null);
        List<String> big = new ArrayList<>();
        for (int i = 0; i < SiteCrawl.MAX_LINKS_PER_PAGE + 500; i++) big.add(BASE + "p" + i);
        assertThat(c.offer(big, SiteCrawl.SOURCE_LINK)).isEqualTo(SiteCrawl.MAX_LINKS_PER_PAGE);
        int round = 0;
        while (c.queued() + c.dropped() < SiteCrawl.MAX_KNOWN + 100 && round < 20) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < SiteCrawl.MAX_LINKS_PER_PAGE; i++) next.add(BASE + "r" + round + "-" + i);
            c.offer(next, SiteCrawl.SOURCE_LINK);
            round++;
        }
        assertThat(c.queued()).isLessThanOrEqualTo(SiteCrawl.MAX_KNOWN);
        assertThat(c.dropped()).isPositive();
    }

    @Test
    @DisplayName("taşma sayacı tekil: aynı taşan adres birçok sayfada geçse de bir kez sayılır; full() tavanı söyler")
    void droppedIsUnique() {
        SiteCrawl c = new SiteCrawl(BASE, null, null, null);
        int round = 0;
        while (!c.full()) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < SiteCrawl.MAX_LINKS_PER_PAGE; i++) next.add(BASE + "f" + round + "-" + i);
            c.offer(next, SiteCrawl.SOURCE_LINK);
            round++;
            assertThat(round).as("tavan sınırlı turda dolar").isLessThan(100);
        }
        int before = c.dropped();   // tavanı dolduran son turun taşanları (ana sayfa da bilinen sayılır)
        List<String> extra = List.of(BASE + "x1", BASE + "x2", BASE + "x3");
        for (int i = 0; i < 5; i++) assertThat(c.offer(extra, SiteCrawl.SOURCE_LINK)).isZero();
        assertThat(c.dropped()).isEqualTo(before + 3);
        assertThat(c.droppedAtLeast()).isFalse();
        assertThat(SiteCrawl.fnv64("a")).isNotEqualTo(SiteCrawl.fnv64("b"));
    }

    @Test
    @DisplayName("geçersiz köken / boş girdi: hiçbir şey eklenmez, istisna yok")
    void invalidInputs() {
        SiteCrawl c = new SiteCrawl("not a url", null, null, null);
        assertThat(c.offer(List.of(BASE + "a"), SiteCrawl.SOURCE_LINK)).isZero();
        SiteCrawl ok = new SiteCrawl(BASE, null, null, null);
        assertThat(ok.offer(null, SiteCrawl.SOURCE_LINK)).isZero();
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add("");
        withNull.add(BASE + "x".repeat(SitePages.MAX_URL_CHARS + 10));
        assertThat(ok.offer(withNull, SiteCrawl.SOURCE_LINK)).isZero();
        assertThat(ok.poll()).isNull();
        ok.pushFront(null);
        assertThat(ok.queued()).isZero();
    }
}
