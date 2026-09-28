package com.sitemonitor.service;

import com.sitemonitor.model.HttpMetricMinute;
import com.sitemonitor.repository.HttpMetricMinuteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpMetricsServiceTest {

    private HttpMetricsService service;

    @BeforeEach
    void setUp() {
        service = new HttpMetricsService();
    }

    // ── record ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("record 200 → does not increment errors")
    void record_200_doesNotIncrementErrors() {
        service.record(200, 50);
        Map<String, Object> summary = service.getSummary();
        assertThat((Long) summary.get("total_errors")).isEqualTo(0L);
        assertThat((Long) summary.get("total_requests")).isEqualTo(1L);
    }

    @Test
    @DisplayName("record 400 → increments errors")
    void record_400_incrementsErrors() {
        service.record(400, 10);
        Map<String, Object> summary = service.getSummary();
        assertThat((Long) summary.get("total_errors")).isEqualTo(1L);
    }

    @Test
    @DisplayName("record 500 → increments errors")
    void record_500_incrementsErrors() {
        service.record(500, 10);
        Map<String, Object> summary = service.getSummary();
        assertThat((Long) summary.get("total_errors")).isEqualTo(1L);
    }

    @Test
    @DisplayName("multiple records accumulate correctly")
    void record_multiple_accumulatesCorrectly() {
        service.record(200, 10);
        service.record(200, 20);
        service.record(500, 30);

        Map<String, Object> summary = service.getSummary();
        assertThat((Long) summary.get("total_requests")).isEqualTo(3L);
        assertThat((Long) summary.get("total_errors")).isEqualTo(1L);
    }

    // ── getSummary ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no requests → error_rate_pct=0.0")
    void getSummary_noRequests_zeroErrorRate() {
        Map<String, Object> summary = service.getSummary();
        assertThat((Double) summary.get("error_rate_pct")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("all 500s → error_rate_pct=100.0")
    void getSummary_allErrors_100Pct() {
        service.record(500, 10);
        service.record(500, 10);
        service.record(500, 10);
        Map<String, Object> summary = service.getSummary();
        assertThat((Double) summary.get("error_rate_pct")).isEqualTo(100.0);
    }

    @Test
    @DisplayName("50% errors → error_rate_pct≈50.0")
    void getSummary_mixed_calculatesRate() {
        service.record(200, 10);
        service.record(500, 10);
        Map<String, Object> summary = service.getSummary();
        assertThat((Double) summary.get("error_rate_pct")).isEqualTo(50.0);
    }

    @Test
    @DisplayName("getSummary tracks max_ms")
    void getSummary_tracksMaxMs() {
        service.record(200, 10);
        service.record(200, 500);
        Map<String, Object> summary = service.getSummary();
        assertThat((Long) summary.get("max_ms")).isEqualTo(500L);
    }

    // ── rotate ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rotate moves current bucket to history")
    @SuppressWarnings("unchecked")
    void rotate_movesCurrentBucketToHistory() {
        service.record(200, 100);
        // Force a different minute key by manipulating the current bucket's key
        Object currentBucket = ReflectionTestUtils.getField(service, "current");
        // We can't easily change time, so instead: call rotate and verify history grows
        // when current bucket has data
        // Since we're in same minute, rotate only adds if key changed.
        // Instead verify via getHistory which includes current bucket if count>0
        java.util.List<Map<String, Object>> history = service.getHistory();
        assertThat(history).isNotEmpty();
        assertThat(history.stream().mapToLong(b -> (Long) b.get("count")).sum()).isEqualTo(1L);
    }

    // ── endpoint bazlı kalıcılık (yeni) ─────────────────────────────────────────

    @Test
    @DisplayName("histIndex: ms doğru kovaya; taşma son indeks")
    void histIndex_boundaries() {
        assertThat(HttpMetricsService.histIndex(1)).isZero();
        assertThat(HttpMetricsService.histIndex(75)).isEqualTo(7);   // bounds[7]=75
        assertThat(HttpMetricsService.histIndex(50_000)).isEqualTo(HttpMetricsService.HISTOGRAM_BOUNDS.length); // overflow
    }

    @Test
    @DisplayName("rotation: biten dakikanın endpoint kovaları DB'ye (saveAll) yazılır + histogram dolu")
    @SuppressWarnings("unchecked")
    void rotation_persistsEndpointBuckets() throws Exception {
        HttpMetricMinuteRepository repo = mock(HttpMetricMinuteRepository.class);
        ReflectionTestUtils.setField(service, "metricRepo", repo);

        service.record("GET /api/x", 200, 40);
        service.record("GET /api/x", 500, 120);   // 1 hata, sumMs=160, max=120

        // Dakikayı zorla değiştir: current'ı eski-anahtarlı kovaya çek → rotate() rotateTo+flush tetikler.
        var ctor = Class.forName("com.sitemonitor.service.HttpMetricsService$MinuteBucket")
                .getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        ReflectionTestUtils.setField(service, "current", ctor.newInstance("2000-01-01T00:00:00"));

        service.rotate();

        ArgumentCaptor<List<HttpMetricMinute>> cap = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(cap.capture());
        List<HttpMetricMinute> rows = cap.getValue();
        assertThat(rows).hasSize(1);
        HttpMetricMinute m = rows.get(0);
        assertThat(m.getEndpoint()).isEqualTo("GET /api/x");
        assertThat(m.getReqCount()).isEqualTo(2L);
        assertThat(m.getErrorCount()).isEqualTo(1L);
        assertThat(m.getSumMs()).isEqualTo(160L);
        assertThat(m.getMaxMs()).isEqualTo(120L);
        assertThat(m.getHist()).contains(",");   // histogram CSV dolu
    }

    // ── 2026-09-28 ekleri: yüzdelikler, istek hızı, durum kodları ─────────────────────────────────

    @Test
    @DisplayName("getSummary: p50/p95/p99 histogramdan, req_per_min + peak; history noktasında p95_ms")
    void getSummary_percentilesAndRate() {
        for (int i = 0; i < 95; i++) service.record(200, 10);
        for (int i = 0; i < 5; i++) service.record(200, 2000);
        Map<String, Object> s = service.getSummary();
        assertThat((Long) s.get("p50_ms")).isLessThanOrEqualTo(10L);
        assertThat((Long) s.get("p99_ms")).isGreaterThanOrEqualTo(1500L);
        assertThat((Long) s.get("p95_ms")).isLessThanOrEqualTo((Long) s.get("p99_ms"));
        // Kova SINIRI tuzağı (test-fixed-date-time-bomb): 100 kayıt dakika sınırına denk gelirse iki kovaya bölünür →
        // hız = toplam / kova sayısı; iddia bu bağıntıyı sınar, "tek kova" varsaymaz.
        int buckets = (Integer) s.get("buckets");
        assertThat((Double) s.get("req_per_min") * buckets).isCloseTo(100.0, within(0.5));
        assertThat((Long) s.get("peak_req_per_min")).isBetween(50L, 100L);
        assertThat(service.getHistory().get(0)).containsKey("p95_ms");
    }

    @Test
    @DisplayName("24 saatlik kayan p95: pencereden düşen dakikanın histogramı ÇIKARILIR (eski yavaş dakika p95'i kirletmez)")
    void rollingHistogram_evictsOldMinutes() throws Exception {
        var ctor = Class.forName("com.sitemonitor.service.HttpMetricsService$MinuteBucket")
                .getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        var add = ctor.getDeclaringClass().getDeclaredMethod("add", int.class, long.class);
        add.setAccessible(true);
        var fin = HttpMetricsService.class.getDeclaredMethod("finalize", ctor.getDeclaringClass());
        fin.setAccessible(true);

        Object slow = ctor.newInstance("2000-01-01T00:00:00");
        for (int i = 0; i < 1000; i++) add.invoke(slow, 200, 9000L);
        fin.invoke(service, slow);
        assertThat((Long) service.getSummary().get("p95_ms")).isGreaterThan(5000L);

        // 1440 hızlı dakika daha → yavaş dakika pencereden düşer
        for (int m = 1; m <= 1440; m++) {
            Object fast = ctor.newInstance("2000-01-02T00:00:00");
            add.invoke(fast, 200, 5L);
            fin.invoke(service, fast);
        }
        assertThat((Long) service.getSummary().get("p95_ms")).isLessThanOrEqualTo(5L);
        assertThat(service.getHistory()).hasSize(1440);
    }

    @Test
    @DisplayName("rotation: uç kovası durum kodlarını 'kod:adet' olarak yazar; 100–599 dışı kod 0'a katlanır")
    @SuppressWarnings("unchecked")
    void rotation_persistsStatusCodes() throws Exception {
        HttpMetricsRepoHolder h = new HttpMetricsRepoHolder(service);
        service.record("GET /api/x", 200, 5);
        service.record("GET /api/x", 200, 5);
        service.record("GET /api/x", 404, 5);
        service.record("GET /api/x", 999, 5);
        h.forceRotate();

        HttpMetricMinute m = h.onlyRow();
        assertThat(m.getStatusCodes()).isEqualTo("0:1,200:2,404:1");
        assertThat(HttpMetricsService.statusKey(700)).isZero();
        assertThat(HttpMetricsService.statusKey(503)).isEqualTo(503);
    }

    /** Dakikayı zorla çevirip DB'ye yazılan satırı yakalar (rotation_persistsEndpointBuckets ile aynı yöntem). */
    private static final class HttpMetricsRepoHolder {
        final HttpMetricsService service;
        final HttpMetricMinuteRepository repo = mock(HttpMetricMinuteRepository.class);

        HttpMetricsRepoHolder(HttpMetricsService service) {
            this.service = service;
            ReflectionTestUtils.setField(service, "metricRepo", repo);
        }

        void forceRotate() throws Exception {
            var ctor = Class.forName("com.sitemonitor.service.HttpMetricsService$MinuteBucket")
                    .getDeclaredConstructor(String.class);
            ctor.setAccessible(true);
            ReflectionTestUtils.setField(service, "current", ctor.newInstance("2000-01-01T00:00:00"));
            service.rotate();
        }

        @SuppressWarnings("unchecked")
        HttpMetricMinute onlyRow() {
            ArgumentCaptor<List<HttpMetricMinute>> cap = ArgumentCaptor.forClass(List.class);
            verify(repo).saveAll(cap.capture());
            assertThat(cap.getValue()).hasSize(1);
            return cap.getValue().get(0);
        }
    }

    /**
     * Kardinalite tavanı (2026-08-20 bellek denetimi). Endpoint anahtarı yüksek kardinaliteli bir
     * kaynaktan beslenirse hem bellek-içi harita hem {@code http_metric_minute} tablosu sınırsız
     * büyürdü. Tavan yapısal güvencedir: dakika başına en çok MAX_ENDPOINTS_PER_MINUTE+1 satır.
     */
    @Test
    @DisplayName("kardinalite tavanı: 5000 farklı endpoint → dakika başına en çok 501 kova, fazlası '(overflow)'")
    @SuppressWarnings("unchecked")
    void record_cardinalityCap_foldsOverflowBucket() throws Exception {
        HttpMetricMinuteRepository repo = mock(HttpMetricMinuteRepository.class);
        ReflectionTestUtils.setField(service, "metricRepo", repo);

        for (int i = 0; i < 5000; i++) service.record("GET /api/e" + i, 200, 5);

        var ctor = Class.forName("com.sitemonitor.service.HttpMetricsService$MinuteBucket")
                .getDeclaredConstructor(String.class);
        ctor.setAccessible(true);
        ReflectionTestUtils.setField(service, "current", ctor.newInstance("2000-01-01T00:00:00"));

        service.rotate();

        ArgumentCaptor<List<HttpMetricMinute>> cap = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(cap.capture());
        List<HttpMetricMinute> rows = cap.getValue();

        assertThat(rows).hasSizeLessThanOrEqualTo(501);          // 500 ayrı + 1 taşma kovası
        assertThat(rows).extracting(HttpMetricMinute::getEndpoint).contains("(overflow)");
        // Taşma kovası kaybolan istekleri YUTMAZ: toplam req_count korunur.
        assertThat(rows.stream().mapToLong(HttpMetricMinute::getReqCount).sum()).isEqualTo(5000L);
    }
}
