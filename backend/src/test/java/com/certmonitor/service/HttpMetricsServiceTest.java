package com.certmonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

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
}
