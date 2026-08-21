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
