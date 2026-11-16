package com.sitemonitor.service.page.scan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.service.page.scan.site.SiteCrawl;
import com.sitemonitor.service.page.scan.site.SitePages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tüm site taramasının denetim noktası (inceleme 2026-11-17): ARTIMLI kurulur — her satır eklendiğinde bir kez serileştirilir,
 * {@code build} yalnız hazır parçaları + kuyruğu birleştirir. Çıktı eski (her noktada her şeyi yeniden kuran) yöntemle AYNI
 * belgedir (kahin aşağıda, eski kodun birebir kopyası).
 */
class PageResourceScanCheckpointTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "https://a.example/";

    static SitePages.Page done(String url, int grade) {
        Map<String, SitePages.Resource> res = new LinkedHashMap<>();
        res.put(BASE + "s.css", new SitePages.Resource(BASE + "s.css", "CSS", 1200));
        res.put(BASE + "p" + grade + ".png", new SitePages.Resource(BASE + "p" + grade + ".png", "IMAGE", 40_000));
        return new SitePages.Page(url, SitePages.DONE, null, grade, "B", 1500L, 90_000L, 3, 80, 70, 60,
                List.of("LARGE_IMAGES", "NO_CACHE"), 9_000, res, false);
    }

    /** Kahin: inceleme öncesi checkpointJson (her noktada tüm satırları yeniden kurar). */
    static String oldCheckpoint(long scanId, List<SitePages.Page> pages, List<Map<String, Object>> prior, SiteCrawl crawl,
                                List<String> doneUrls) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        m.put("crawl", PageResourceScanService.CRAWL_FULL);
        List<Map<String, Object>> rows = new ArrayList<>(prior.size() + pages.size());
        rows.addAll(prior);
        for (int i = 1; i < pages.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>(SitePages.row(pages.get(i)));
            row.put("from_scan", scanId);
            rows.add(row);
        }
        m.put("rows", rows);
        m.put("done_urls", doneUrls);
        List<Map<String, Object>> q = new ArrayList<>();
        for (SiteCrawl.Entry e : crawl.remaining(5_000)) {
            Map<String, Object> qe = new LinkedHashMap<>();
            qe.put("u", e.url());
            qe.put("s", e.source());
            q.add(qe);
        }
        m.put("queue", q);
        m.put("queue_total", crawl.queued());
        return JSON.writeValueAsString(m);
    }

    static Map<String, Object> parse(String json) throws Exception {
        return JSON.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    @Test
    @DisplayName("artımlı nokta eski yöntemle aynı belgeyi üretir; satırlar yalnız bir kez serileştirilir")
    void incrementalEqualsOldAndSerializesOnce() throws Exception {
        long scanId = 42;
        Map<String, Object> priorRow = new LinkedHashMap<>(SitePages.row(done(BASE + "eski", 77)));
        priorRow.put("from_scan", 7L);
        List<Map<String, Object>> prior = List.of(priorRow);
        SiteCrawl crawl = new SiteCrawl(BASE, BASE, null, null);
        crawl.offer(List.of(BASE + "q1", BASE + "q2", BASE + "q3"), SiteCrawl.SOURCE_LINK);

        PageResourceScanService.Checkpoint ck = new PageResourceScanService.Checkpoint(scanId);
        ck.addRow(priorRow);
        ck.addDone(BASE + "eski");
        List<SitePages.Page> pages = new ArrayList<>();
        pages.add(done(BASE, 90));   // ana sayfa: noktaya girmez
        List<String> doneUrls = new ArrayList<>(List.of(BASE + "eski"));
        for (int i = 0; i < 40; i++) {
            SitePages.Page pg = i % 7 == 3 ? SitePages.Page.failed(BASE + "f" + i, "PAGE_HTTP:404") : done(BASE + "d" + i, 50 + i);
            pages.add(pg);
            ck.addPage(pg);
            ck.addDone(pg.url());
            doneUrls.add(pg.url());
            if (i % 10 == 9) {
                // Her "nokta" anında yeni yöntem eski kahinle aynı belge
                assertThat(parse(ck.build(crawl))).isEqualTo(parse(oldCheckpoint(scanId, pages, prior, crawl, doneUrls)));
            }
        }
        for (int i = 0; i < 5; i++) ck.build(crawl);
        assertThat(ck.serializedRows).as("her satır bir kez (1 devralınan + 40 sayfa) — noktalar yeniden serileştirmez")
                .isEqualTo(41);
        Map<String, Object> last = parse(ck.build(crawl));
        assertThat((List<?>) last.get("rows")).hasSize(41);
        assertThat(last.get("queue_total")).isEqualTo(3);
    }

    @Test
    @DisplayName("taranmış sayılan kuyruk kaydı noktaya yazılmaz; taranmış adres tavanı uygulanır")
    void queueSkipsDoneAndDoneCap() throws Exception {
        SiteCrawl crawl = new SiteCrawl(BASE, BASE, null, null);
        crawl.offer(List.of(BASE + "a", BASE + "b"), SiteCrawl.SOURCE_SITEMAP);
        crawl.markDone(BASE + "a/");
        PageResourceScanService.Checkpoint ck = new PageResourceScanService.Checkpoint(1);
        for (int i = 0; i < PageResourceScanService.CHECKPOINT_DONE_MAX + 50; i++) ck.addDone(BASE + "x" + i);
        Map<String, Object> m = parse(ck.build(crawl));
        assertThat((List<?>) m.get("queue")).hasSize(1);
        assertThat(m.get("queue_total")).isEqualTo(1);
        assertThat((List<?>) m.get("done_urls")).hasSize(PageResourceScanService.CHECKPOINT_DONE_MAX);
    }
}
