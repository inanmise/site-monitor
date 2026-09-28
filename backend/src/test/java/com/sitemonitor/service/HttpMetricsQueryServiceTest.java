package com.sitemonitor.service;

import com.sitemonitor.model.HttpMetricMinute;
import com.sitemonitor.repository.HttpMetricMinuteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(HttpMetricsQueryService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class HttpMetricsQueryServiceTest {

    @Autowired HttpMetricsQueryService service;
    @Autowired HttpMetricMinuteRepository repo;

    // ── Histogram yardımcıları (saf) ────────────────────────────────────────────

    private static String histAt(long ms, long count) {
        long[] h = new long[HttpMetricsService.HIST_LEN];
        h[HttpMetricsService.histIndex(ms)] = count;
        return join(h);
    }

    private static String join(long[] h) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length; i++) { if (i > 0) sb.append(','); sb.append(h[i]); }
        return sb.toString();
    }

    @Test
    @DisplayName("percentile: tek kovada p50<=p95<=p99, kovanın [alt,üst] sınırı içinde")
    void percentile_singleBucket() {
        long[] h = new long[HttpMetricsService.HIST_LEN];
        h[HttpMetricsService.histIndex(75)] = 100;   // hepsi 75ms kovasında (alt 50, üst 75)
        long p50 = HttpMetricsQueryService.percentile(h, 0.50, 75);
        long p95 = HttpMetricsQueryService.percentile(h, 0.95, 75);
        long p99 = HttpMetricsQueryService.percentile(h, 0.99, 75);
        assertThat(p50).isLessThanOrEqualTo(p95);
        assertThat(p95).isLessThanOrEqualTo(p99);
        assertThat(p95).isBetween(50L, 75L);
    }

    @Test
    @DisplayName("percentile: çok-kovalı dağılımda p95 üst kuyruğa düşer")
    void percentile_multiBucket() {
        long[] h = new long[HttpMetricsService.HIST_LEN];
        h[HttpMetricsService.histIndex(10)]   = 90;   // %90 hızlı (~10ms)
        h[HttpMetricsService.histIndex(1000)] = 10;   // %10 yavaş (~1000ms, alt 750 üst 1000)
        long p50 = HttpMetricsQueryService.percentile(h, 0.50, 1000);
        long p95 = HttpMetricsQueryService.percentile(h, 0.95, 1000);
        assertThat(p50).isLessThanOrEqualTo(20L);      // ortanca hızlı kovada
        assertThat(p95).isBetween(750L, 1000L);        // p95 yavaş kuyrukta
    }

    @Test
    @DisplayName("percentile: boş histogram → 0")
    void percentile_empty() {
        assertThat(HttpMetricsQueryService.percentile(new long[HttpMetricsService.HIST_LEN], 0.95, 0)).isZero();
    }

    @Test
    @DisplayName("bucketKey: UTC → Europe/Istanbul yerel; gece-yarısı bir sonraki güne kayar")
    void bucketKey_istanbulShift() {
        // 22:30 UTC = 01:30 IST (ertesi gün)
        assertThat(HttpMetricsQueryService.bucketKey("2026-06-18T22:30:00", "minute")).isEqualTo("2026-06-19T01:30:00");
        assertThat(HttpMetricsQueryService.bucketKey("2026-06-18T22:30:00", "hour")).isEqualTo("2026-06-19T01:00:00");
    }

    @Test
    @DisplayName("resolveGranularity: >24 saat → hour, değilse minute (24s dahil dakika)")
    void resolveGranularity_auto() {
        assertThat(HttpMetricsQueryService.resolveGranularity(null, "2026-06-18T00:00:00", "2026-06-18T06:00:00")).isEqualTo("minute"); // 6s
        assertThat(HttpMetricsQueryService.resolveGranularity(null, "2026-06-18T00:00:00", "2026-06-19T00:00:00")).isEqualTo("minute"); // 24s → dakika
        assertThat(HttpMetricsQueryService.resolveGranularity(null, "2026-06-18T00:00:00", "2026-06-20T00:00:00")).isEqualTo("hour");   // 48s → saat
        assertThat(HttpMetricsQueryService.resolveGranularity("hour", "2026-06-18T00:00:00", "2026-06-18T01:00:00")).isEqualTo("hour");
    }

    // ── series / endpoints (DB) ─────────────────────────────────────────────────

    private HttpMetricMinute row(String bucketUtc, String endpoint, long count, long errors, long sumMs, long maxMs, long histMs) {
        HttpMetricMinute m = new HttpMetricMinute();
        m.setBucketMinute(bucketUtc); m.setEndpoint(endpoint);
        m.setReqCount(count); m.setErrorCount(errors); m.setSumMs(sumMs); m.setMaxMs(maxMs); m.setMinMs(1);
        m.setHist(histAt(histMs, count));
        return m;
    }

    @Test
    @DisplayName("series: minute granularite + IST kovalar + özet (count/avg/p95)")
    @SuppressWarnings("unchecked")
    void series_minute() {
        // aynı IST saatinde iki dakika + bir endpoint
        repo.save(row("2026-06-18T10:00:00", "GET /api/x", 10, 1, 400, 80, 40));   // IST 13:00
        repo.save(row("2026-06-18T10:01:00", "GET /api/x", 20, 0, 600, 90, 30));   // IST 13:01
        repo.save(row("2026-06-18T10:00:00", "GET /api/y", 5, 0, 100, 20, 20));    // başka endpoint (filtrelenir)

        Map<String, Object> res = service.series("2026-06-18T09:55:00", "2026-06-18T10:10:00", "GET /api/x", "minute");
        var data = (List<Map<String, Object>>) res.get("data");
        assertThat(data).hasSize(16);   // IST 12:55..13:10 = 16 dakika kovası (boşlar dahil)
        var b1300 = data.stream().filter(p -> "2026-06-18T13:00:00".equals(p.get("ts"))).findFirst().orElseThrow();
        var b1301 = data.stream().filter(p -> "2026-06-18T13:01:00".equals(p.get("ts"))).findFirst().orElseThrow();
        assertThat(((Number) b1300.get("count")).longValue()).isEqualTo(10L);
        assertThat(((Number) b1301.get("count")).longValue()).isEqualTo(20L);
        // boş kova (13:05): count=0, süreler null → grafik boşlukta çizgiyi birleştirmez
        var empty = data.stream().filter(p -> "2026-06-18T13:05:00".equals(p.get("ts"))).findFirst().orElseThrow();
        assertThat(((Number) empty.get("count")).longValue()).isZero();
        assertThat(empty.get("avg_ms")).isNull();
        assertThat(empty.get("p95_ms")).isNull();

        var summary = (Map<String, Object>) res.get("summary");
        assertThat(((Number) summary.get("total")).longValue()).isEqualTo(30L);   // yalnız x
        assertThat(((Number) summary.get("errors")).longValue()).isEqualTo(1L);
        assertThat(((Number) summary.get("avg_ms")).longValue()).isEqualTo(1000L / 30L);
        assertThat(((Number) summary.get("p95_ms")).longValue()).isGreaterThan(0L);
        assertThat(res.get("granularity")).isEqualTo("minute");
    }

    @Test
    @DisplayName("series: endpoint boş → tüm endpoint'ler birlikte; hour granularite tek kovada toplar")
    @SuppressWarnings("unchecked")
    void series_allEndpoints_hour() {
        repo.save(row("2026-06-18T10:00:00", "GET /api/x", 10, 0, 400, 80, 40));
        repo.save(row("2026-06-18T10:30:00", "GET /api/y", 20, 2, 600, 90, 30));   // aynı IST saati (13:xx)

        Map<String, Object> res = service.series("2026-06-18T09:00:00", "2026-06-18T11:00:00", null, "hour");
        var data = (List<Map<String, Object>>) res.get("data");
        assertThat(data).hasSize(3);                                  // IST 12:00,13:00,14:00 (boşlar dahil)
        var b13 = data.stream().filter(p -> "2026-06-18T13:00:00".equals(p.get("ts"))).findFirst().orElseThrow();
        assertThat(((Number) b13.get("count")).longValue()).isEqualTo(30L);  // x + y
        assertThat(((Number) b13.get("errors")).longValue()).isEqualTo(2L);
        // boş saat (12:00) → count=0
        var empty12 = data.stream().filter(p -> "2026-06-18T12:00:00".equals(p.get("ts"))).findFirst().orElseThrow();
        assertThat(((Number) empty12.get("count")).longValue()).isZero();
    }

    @Test
    @DisplayName("endpoints: aralıkta endpoint başına toplam (count'a göre azalan)")
    @SuppressWarnings("unchecked")
    void endpoints_list() {
        repo.save(row("2026-06-18T10:00:00", "GET /api/x", 30, 3, 900, 80, 40));
        repo.save(row("2026-06-18T10:01:00", "GET /api/y", 5, 0, 100, 20, 20));

        List<Map<String, Object>> eps = service.endpoints("2026-06-18T00:00:00", "2026-06-18T23:59:59");
        assertThat(eps).hasSize(2);
        assertThat(eps.get(0).get("endpoint")).isEqualTo("GET /api/x");   // en çok istek başta
        assertThat(((Number) eps.get(0).get("count")).longValue()).isEqualTo(30L);
        assertThat(((Number) eps.get(0).get("avg_ms")).longValue()).isEqualTo(30L);
        assertThat(((Number) eps.get(0).get("error_rate_pct")).doubleValue()).isEqualTo(10.0);
        // 2026-09-28 ekleri: yöntem / yol ayrı, son görülme (UTC, en büyük kova)
        assertThat(eps.get(0)).containsEntry("method", "GET").containsEntry("path", "/api/x")
                .containsEntry("last_seen", "2026-06-18T10:00:00");
    }

    // ── İstek Gezgini (2026-09-28): overview / durum kodları / en yavaş-en çok hata ──────────────

    private HttpMetricMinute row(String bucketUtc, String endpoint, long count, long errors, long sumMs, long maxMs,
                                 long histMs, String codes) {
        HttpMetricMinute m = row(bucketUtc, endpoint, count, errors, sumMs, maxMs, histMs);
        m.setStatusCodes(codes);
        return m;
    }

    private static Map<String, Object> find(List<Map<String, Object>> list, String key, Object value) {
        return list.stream().filter(p -> value.equals(p.get(key))).findFirst().orElseThrow();
    }

    private static long n(Object o) { return ((Number) o).longValue(); }

    @Test
    @DisplayName("overview: uç tablosu SÜZGEÇTEN BAĞIMSIZ (hepsi); seri/özet/durum kodları yalnız süzgece uyanlar")
    @SuppressWarnings("unchecked")
    void overview_endpointFilter_tableUnfiltered_seriesFiltered() {
        repo.save(row("2026-06-18T10:00:00", "GET /api/x", 10, 2, 400, 80, 40, "200:8,404:1,503:1"));
        repo.save(row("2026-06-18T10:01:00", "GET /api/x", 20, 0, 600, 90, 30, "200:19,304:1"));
        repo.save(row("2026-06-18T10:00:00", "POST /api/y", 5, 5, 100, 20, 20, "500:5"));

        Map<String, Object> res = service.overview("2026-06-18T09:55:00", "2026-06-18T10:10:00", "GET /api/x", null, "minute");

        var eps = (List<Map<String, Object>>) res.get("endpoints");
        assertThat(eps).extracting(e -> e.get("endpoint")).containsExactly("GET /api/x", "POST /api/y");
        var x = find(eps, "endpoint", "GET /api/x");
        assertThat(n(x.get("count"))).isEqualTo(30L);
        assertThat(n(x.get("status_2xx"))).isEqualTo(27L);
        assertThat(n(x.get("status_3xx"))).isEqualTo(1L);
        assertThat(n(x.get("status_4xx"))).isEqualTo(1L);
        assertThat(n(x.get("status_5xx"))).isEqualTo(1L);
        assertThat(x.get("last_seen")).isEqualTo("2026-06-18T10:01:00");
        assertThat(n(x.get("p95_ms"))).isBetween(20L, 50L);          // histogram 30/40 ms kovalarında
        var y = find(eps, "endpoint", "POST /api/y");
        assertThat(n(y.get("status_5xx"))).isEqualTo(5L);
        assertThat(((Number) y.get("error_rate_pct")).doubleValue()).isEqualTo(100.0);

        var summary = (Map<String, Object>) res.get("summary");
        assertThat(n(summary.get("total"))).isEqualTo(30L);          // yalnız x
        assertThat(n(summary.get("status_5xx"))).isEqualTo(1L);
        assertThat(n(summary.get("unclassified"))).isZero();
        assertThat(((Number) summary.get("req_per_min")).doubleValue()).isEqualTo(2.0);   // 30 istek / 15 dk

        var codes = (List<Map<String, Object>>) res.get("status_codes");
        assertThat(codes).extracting(c -> c.get("code")).containsExactly(200, 304, 404, 503);
        assertThat(n(codes.get(0).get("count"))).isEqualTo(27L);

        var data = (List<Map<String, Object>>) res.get("data");
        var b1300 = find(data, "ts", "2026-06-18T13:00:00");            // IST
        assertThat(n(b1300.get("count"))).isEqualTo(10L);                // y (aynı dakika) süzüldü
        assertThat(n(b1300.get("status_4xx"))).isEqualTo(1L);
        // t = kova başlangıcının epoch'u — tarayıcı dilimi ne olursa olsun aynı an (13:00 IST = 10:00 UTC)
        assertThat(n(b1300.get("t"))).isEqualTo(java.time.Instant.parse("2026-06-18T10:00:00Z").toEpochMilli());
        var empty = find(data, "ts", "2026-06-18T13:05:00");
        assertThat(n(empty.get("count"))).isZero();
        assertThat(empty.get("p95_ms")).isNull();
        assertThat(n(empty.get("status_2xx"))).isZero();
        assertThat(res).containsEntry("clamped", false).containsEntry("granularity", "minute");
        // TEL BİÇİMİ: tüm anahtarlar snake_case
        assertThat(res.keySet()).allMatch(k -> k.equals(k.toLowerCase()) && !k.contains("-"));
        assertThat(summary.keySet()).allMatch(k -> k.equals(k.toLowerCase()));
        assertThat(x.keySet()).allMatch(k -> k.equals(k.toLowerCase()));
    }

    @Test
    @DisplayName("overview: yöntem süzgeci; eski (durum kolonu NULL) satırlar 'sınıfsız' sayılır")
    @SuppressWarnings("unchecked")
    void overview_methodFilter_and_unclassifiedLegacyRows() {
        repo.save(row("2026-06-18T10:00:00", "GET /api/x", 10, 1, 400, 80, 40, null));   // eski satır
        repo.save(row("2026-06-18T10:00:00", "POST /api/y", 4, 0, 100, 20, 20, "201:4"));

        Map<String, Object> res = service.overview("2026-06-18T09:00:00", "2026-06-18T11:00:00", null, "post,BOGUS", null);
        var summary = (Map<String, Object>) res.get("summary");
        assertThat(n(summary.get("total"))).isEqualTo(4L);               // yalnız POST
        assertThat(n(summary.get("status_2xx"))).isEqualTo(4L);

        Map<String, Object> all = service.overview("2026-06-18T09:00:00", "2026-06-18T11:00:00", null, null, null);
        var allSummary = (Map<String, Object>) all.get("summary");
        assertThat(n(allSummary.get("unclassified"))).isEqualTo(10L);
        assertThat(n(allSummary.get("total"))).isEqualTo(14L);
    }

    @Test
    @DisplayName("overview: eski ham satırlar GÜVENLİ ada katlanır — sorgu dizesi/kimlik yanıtta hiç görünmez")
    @SuppressWarnings("unchecked")
    void overview_neverLeaksRawPathParts() {
        repo.save(row("2026-06-18T10:00:00", "GET /api/users/1234?token=abc", 3, 0, 30, 10, 10, "200:3"));
        repo.save(row("2026-06-18T10:01:00", "GET /api/users/5678", 2, 0, 20, 10, 10, "200:2"));

        Map<String, Object> res = service.overview("2026-06-18T09:00:00", "2026-06-18T11:00:00", null, null, null);
        var eps = (List<Map<String, Object>>) res.get("endpoints");
        assertThat(eps).extracting(e -> e.get("endpoint")).containsExactly("GET /api/users/{id}");
        assertThat(n(eps.get(0).get("count"))).isEqualTo(5L);
        // (Yalnız uç tablosu taranır: seri noktalarının epoch sayıları rastlantıyla "1234" içerebilir.)
        assertThat(eps.toString()).doesNotContain("token=abc").doesNotContain("1234").doesNotContain("5678");
    }

    @Test
    @DisplayName("overview: 31 günü aşan aralık kırpılır (clamped); from > to ve bozuk tarih 400 (IllegalArgument)")
    void overview_rangeValidation() {
        Map<String, Object> res = service.overview("2026-01-01T00:00:00", "2026-06-18T00:00:00", null, null, null);
        assertThat(res).containsEntry("clamped", true).containsEntry("from", "2026-05-18T00:00:00")
                .containsEntry("granularity", "hour");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.overview("2026-06-18T10:00:00", "2026-06-18T09:00:00", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.overview("dün", "2026-06-18T09:00:00", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("series: tek uç — nokta başına t + durum sınıfları, özete status_codes (geriye uyumlu ekler)")
    @SuppressWarnings("unchecked")
    void series_singleEndpoint_statusCodes() {
        repo.save(row("2026-06-18T10:00:00", "GET /api/x", 10, 3, 400, 80, 40, "200:7,500:3"));
        repo.save(row("2026-06-18T10:00:00", "GET /api/z", 99, 0, 400, 80, 40, "200:99"));

        Map<String, Object> res = service.series("2026-06-18T09:59:00", "2026-06-18T10:01:00", "GET /api/x", "minute");
        var codes = (List<Map<String, Object>>) res.get("status_codes");
        assertThat(codes).extracting(c -> c.get("code")).containsExactly(200, 500);
        var summary = (Map<String, Object>) res.get("summary");
        assertThat(n(summary.get("status_5xx"))).isEqualTo(3L);
        assertThat(n(summary.get("total"))).isEqualTo(10L);
        var data = (List<Map<String, Object>>) res.get("data");
        assertThat(data).allSatisfy(p -> assertThat(p).containsKeys("t", "status_2xx", "status_5xx", "unclassified"));
    }

    // Kayan pencere "şimdi"ye bağlı → sabit saat (Clock) + satırlar O SAATTEN türetilir; takvim ilerlese de test aynı.
    private static final java.time.Instant NOW = java.time.Instant.parse("2026-06-18T12:00:30Z");
    private static String minutesAgo(long m) {
        return NOW.truncatedTo(java.time.temporal.ChronoUnit.MINUTES).minus(m, java.time.temporal.ChronoUnit.MINUTES)
                .toString().substring(0, 19);
    }

    @Test
    @DisplayName("topEndpoints: son 24 saat; en yavaş p95'e göre (en az 5 istek), en çok hata; 24 saatten eski satır sayılmaz")
    @SuppressWarnings("unchecked")
    void topEndpoints_windowAndRanking() {
        service.setClock(java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        repo.save(row(minutesAgo(10), "GET /api/slow", 6, 0, 6 * 2000, 2400, 2400, "200:6"));
        repo.save(row(minutesAgo(10), "GET /api/one-off", 1, 0, 9000, 9000, 9000, "200:1"));   // tek istek: en yavaş sayılmaz
        repo.save(row(minutesAgo(20), "GET /api/fast", 50, 0, 500, 20, 10, "200:50"));
        repo.save(row(minutesAgo(30), "POST /api/fail", 10, 6, 100, 20, 10, "200:4,500:6"));
        repo.save(row(minutesAgo(40), "GET /api/some-4xx", 10, 2, 100, 20, 10, "200:8,404:2"));
        repo.save(row(minutesAgo(25 * 60), "GET /api/ancient", 500, 500, 500, 90_000, 20_000, "500:500"));   // pencere dışı

        Map<String, Object> top = service.topEndpoints();
        var slowest = (List<Map<String, Object>>) top.get("slowest");
        var errors = (List<Map<String, Object>>) top.get("errors");
        assertThat(slowest).extracting(e -> e.get("endpoint")).first().isEqualTo("GET /api/slow");
        assertThat(slowest).extracting(e -> e.get("endpoint")).doesNotContain("GET /api/one-off", "GET /api/ancient");
        assertThat(slowest).hasSizeLessThanOrEqualTo(3);
        assertThat(errors).extracting(e -> e.get("endpoint")).containsExactly("POST /api/fail", "GET /api/some-4xx");
        assertThat(top).containsEntry("window_hours", 24).containsEntry("endpoint_count", 5);
    }

    @Test
    @DisplayName("topEndpoints: 60 sn önbellek — süre dolmadan yeni satır görünmez, saat ilerleyince yeniden hesaplanır")
    @SuppressWarnings("unchecked")
    void topEndpoints_cachedForSixtySeconds() {
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        java.time.Clock moving = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return now.get(); }
        };
        service.setClock(moving);
        repo.save(row(minutesAgo(5), "POST /api/fail", 10, 5, 100, 20, 10, "500:5,200:5"));
        assertThat((List<Map<String, Object>>) service.topEndpoints().get("errors")).hasSize(1);

        repo.save(row(minutesAgo(4), "GET /api/new-fail", 10, 9, 100, 20, 10, "500:9,200:1"));
        now.set(NOW.plusSeconds(30));
        assertThat((List<Map<String, Object>>) service.topEndpoints().get("errors")).hasSize(1);   // önbellekten

        now.set(NOW.plusSeconds(61));
        assertThat((List<Map<String, Object>>) service.topEndpoints().get("errors")).hasSize(2);   // yeniden hesap
    }

    // ── 2026-09-28c: dakika kovası tavanı (B4) + series aralık kuralı (ek-2) ────────────────────

    @Test
    @DisplayName("resolveGranularity: açık 'minute' en çok 48 saatte; üstünde 'hour' (auto kuralı değişmedi)")
    void resolveGranularity_minuteCappedAt48h() {
        assertThat(HttpMetricsQueryService.resolveGranularity("minute", "2026-06-18T00:00:00", "2026-06-20T00:00:00")).isEqualTo("minute"); // tam 48 sa
        assertThat(HttpMetricsQueryService.resolveGranularity("minute", "2026-06-18T00:00:00", "2026-06-20T00:01:00")).isEqualTo("hour");   // 48 sa + 1 dk
        assertThat(HttpMetricsQueryService.resolveGranularity("MINUTE", "2026-05-18T00:00:00", "2026-06-18T00:00:00")).isEqualTo("hour");   // 31 gün
        assertThat(HttpMetricsQueryService.resolveGranularity(null, "2026-06-18T00:00:00", "2026-06-19T00:59:00")).isEqualTo("minute");     // auto: 24 sa 59 dk → dakika (eskisi gibi)
        assertThat(HttpMetricsQueryService.minuteCoerced("minute", "hour")).isTrue();
        assertThat(HttpMetricsQueryService.minuteCoerced(null, "hour")).isFalse();
    }

    @Test
    @DisplayName("overview + series: 31 gün + granularity=minute → yanıt 'hour' ve granularity_clamped=true (dakika toplayıcısı belleğe alınmaz)")
    @SuppressWarnings("unchecked")
    void minuteGranularity_longRange_isServedHourly() {
        repo.save(row("2026-06-17T10:00:00", "GET /api/x", 10, 0, 400, 80, 40));
        Map<String, Object> ov = service.overview("2026-05-18T00:00:00", "2026-06-18T00:00:00", null, null, "minute");
        assertThat(ov).containsEntry("granularity", "hour").containsEntry("granularity_clamped", true);
        assertThat((List<?>) ov.get("data")).hasSizeLessThan(800);   // 31 × 24 = 744 saat kovası (44.640 değil)
        Map<String, Object> se = service.series("2026-05-18T00:00:00", "2026-06-18T00:00:00", "GET /api/x", "minute");
        assertThat(se).containsEntry("granularity", "hour").containsEntry("granularity_clamped", true);
        Map<String, Object> shortRange = service.series("2026-06-17T09:00:00", "2026-06-17T11:00:00", null, "minute");
        assertThat(shortRange).containsEntry("granularity", "minute").containsEntry("granularity_clamped", false);
    }

    @Test
    @DisplayName("series: overview ile aynı aralık kuralı — 31 güne kırpılır (clamped + gerçek from/to), from > to 400")
    void series_rangeClampedLikeOverview() {
        repo.save(row("2026-06-17T10:00:00", "GET /api/x", 10, 0, 400, 80, 40));
        repo.save(row("2026-03-01T10:00:00", "GET /api/x", 99, 0, 400, 80, 40));   // 31 günün dışında → sayılmaz
        Map<String, Object> res = service.series("2026-03-01T00:00:00", "2026-06-18T00:00:00", "GET /api/x", null);
        assertThat(res).containsEntry("clamped", true).containsEntry("from", "2026-05-18T00:00:00")
                .containsEntry("to", "2026-06-18T00:00:00").containsEntry("capped", false);
        assertThat(n(((Map<String, Object>) res.get("summary")).get("total"))).isEqualTo(10L);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.series("2026-06-18T10:00:00", "2026-06-18T09:00:00", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
