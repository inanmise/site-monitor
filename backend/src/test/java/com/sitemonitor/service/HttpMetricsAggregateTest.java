package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HttpMetricsAggregate} — İstek Gezgini'nin tek geçişli toplayıcısı ve uç adı güvenliği (2026-09-28).
 *
 * <p>Güvenlik sözleşmesi: arayüze ve CSV'ye çıkan uç adı YÖNTEM + YOL ŞABLONUDUR. Kaydedici zaten rota şablonu
 * yazıyor; buradaki testler derinlemesine savunmayı pinler — eski/yanlış beslenmiş bir satır sorgu dizesi, kimlik,
 * belirteç ya da e-posta taşısa bile ham hâliyle görünmez.
 */
class HttpMetricsAggregateTest {

    @ParameterizedTest(name = "{0} → {1}")
    @DisplayName("safeEndpoint: sorgu dizesi / kimlik / UUID / belirteç / e-posta maskelenir, şablon ve özel kovalar korunur")
    @CsvSource(delimiter = '|', value = {
            "GET /api/incidents/{id}|GET /api/incidents/{id}",
            "GET /api/x?token=abc123&page=2|GET /api/x",
            "GET /api/x#frag|GET /api/x",
            "GET /api/incidents/48213|GET /api/incidents/{id}",
            "DELETE /api/users/3f2b9c1e-8d4a-4b6e-9f10-2a3b4c5d6e7f/sessions|DELETE /api/users/{id}/sessions",
            "POST /api/auth/reset/Zm9vYmFyMTIzNDU2Nzg5MGFiY2RlZg|POST /api/auth/reset/{token}",
            "GET /api/people/ad.soyad@example.com|GET /api/people/{value}",
            "GET /api/assets/**|GET /api/assets/**",
            "POST (unmatched)|POST (unmatched)",
            "(overflow)|(overflow)",
            "(other)|(other)",
            "GET /api/very-long-but-harmless-static-segment-name|GET /api/very-long-but-harmless-static-segment-name",
    })
    void safeEndpoint_masks(String raw, String expected) {
        assertThat(HttpMetricsAggregate.safeEndpoint(raw)).isEqualTo(expected);
    }

    @Test
    @DisplayName("safeEndpoint: boş → (other); denetim karakterleri silinir; 200 karakter tavanı")
    void safeEndpoint_edgeCases() {
        assertThat(HttpMetricsAggregate.safeEndpoint(null)).isEqualTo("(other)");
        assertThat(HttpMetricsAggregate.safeEndpoint("   ")).isEqualTo("(other)");
        assertThat(HttpMetricsAggregate.safeEndpoint("GET /api/a" + Character.toString(10) + "b"))
                .isEqualTo("GET /api/ab");
        String longPath = "GET /api/" + "segment/".repeat(60);
        assertThat(HttpMetricsAggregate.safeEndpoint(longPath)).hasSize(HttpMetricsAggregate.MAX_LEN);
    }

    @Test
    @DisplayName("methodOf / pathOf: yöntemli ad bölünür, özel kova yöntemsiz")
    void methodAndPath() {
        assertThat(HttpMetricsAggregate.methodOf("PATCH /api/x/{id}")).isEqualTo("PATCH");
        assertThat(HttpMetricsAggregate.pathOf("PATCH /api/x/{id}")).isEqualTo("/api/x/{id}");
        assertThat(HttpMetricsAggregate.methodOf("(overflow)")).isEmpty();
        assertThat(HttpMetricsAggregate.pathOf("(overflow)")).isEqualTo("(overflow)");
        assertThat(HttpMetricsAggregate.methodOf("POST (unmatched)")).isEqualTo("POST");
    }

    @Test
    @DisplayName("parseCodes: bozuk parça atlanır, sıfır/negatif adet yok sayılır")
    void parseCodes_tolerant() {
        List<long[]> out = HttpMetricsAggregate.parseCodes("200:10,garbage,404:x,500:2,301:0,:5");
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).containsExactly(200, 10);
        assertThat(out.get(1)).containsExactly(500, 2);
        assertThat(HttpMetricsAggregate.parseCodes(null)).isEmpty();
    }

    @Test
    @DisplayName("classIndex: 2xx/3xx/4xx/5xx ve diğer (1xx, 0)")
    void classIndex_buckets() {
        assertThat(HttpMetricsAggregate.classIndex(204)).isZero();
        assertThat(HttpMetricsAggregate.classIndex(304)).isEqualTo(1);
        assertThat(HttpMetricsAggregate.classIndex(429)).isEqualTo(2);
        assertThat(HttpMetricsAggregate.classIndex(503)).isEqualTo(3);
        assertThat(HttpMetricsAggregate.classIndex(101)).isEqualTo(4);
        assertThat(HttpMetricsAggregate.classIndex(0)).isEqualTo(4);
    }

    private static Object[] raw(String bucket, String ep, long count, long errors, long sumMs, long maxMs, String codes) {
        long[] h = new long[HttpMetricsService.HIST_LEN];
        h[HttpMetricsService.histIndex(maxMs)] = count;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length; i++) { if (i > 0) sb.append(','); sb.append(h[i]); }
        return new Object[]{bucket, ep, count, errors, sumMs, maxMs, 1L, sb.toString(), codes};
    }

    @Test
    @DisplayName("Agg: sınıf sayıları kodlardan, durum kolonu OLMAYAN (eski) satır 'sınıfsız', son görülme en büyük kova")
    void agg_classesUnclassifiedAndLastSeen() {
        HttpMetricsAggregate.Agg a = new HttpMetricsAggregate.Agg(true);
        a.add(HttpMetricsAggregate.Row.of(raw("2026-06-18T10:01:00", "GET /api/x", 10, 2, 300, 40, "200:7,304:1,404:1,503:1")));
        a.add(HttpMetricsAggregate.Row.of(raw("2026-06-18T10:03:00", "GET /api/x", 5, 0, 100, 30, null)));
        a.add(HttpMetricsAggregate.Row.of(raw("2026-06-18T10:02:00", "GET /api/x", 3, 0, 60, 20, "200:3")));

        assertThat(a.count).isEqualTo(18L);
        assertThat(a.classes).containsExactly(10L, 1L, 1L, 1L, 0L);
        assertThat(a.unclassified).isEqualTo(5L);
        assertThat(a.lastSeen).isEqualTo("2026-06-18T10:03:00");

        Map<String, Object> ep = a.toEndpoint("GET /api/x");
        assertThat(ep).containsKeys("endpoint", "method", "path", "count", "errors", "error_rate_pct", "avg_ms",
                "max_ms", "p50_ms", "p95_ms", "p99_ms", "status_2xx", "status_3xx", "status_4xx", "status_5xx",
                "status_other", "unclassified", "last_seen");
        assertThat(ep.get("method")).isEqualTo("GET");
        assertThat(ep.get("path")).isEqualTo("/api/x");

        List<Map<String, Object>> codes = a.codeList(10);
        assertThat(codes.get(0)).containsEntry("code", 200).containsEntry("count", 10L);
        assertThat(codes).extracting(m -> m.get("code")).containsExactly(200, 304, 404, 503);
    }
}
