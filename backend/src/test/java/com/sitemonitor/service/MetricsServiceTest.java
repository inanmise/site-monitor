package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class MetricsServiceTest {

    private MetricsService service;

    @BeforeEach
    void setUp() {
        service = new MetricsService();
    }

    @Test
    @DisplayName("getHistory: initially empty")
    void getHistory_initiallyEmpty() {
        assertThat(service.getHistory()).isEmpty();
    }

    @Test
    @DisplayName("sample adds one entry to history")
    void sample_addsOneEntryToHistory() {
        service.sample();
        assertThat(service.getHistory()).hasSize(1);
    }

    @Test
    @DisplayName("sample entry has required keys")
    void sample_entryHasRequiredKeys() {
        service.sample();
        Map<String, Object> entry = service.getHistory().get(0);
        assertThat(entry).containsKeys("ts", "heap_used_mb", "heap_max_mb", "threads", "heap_pct");
    }

    @Test
    @DisplayName("heap_used_mb is non-negative")
    void sample_heapUsedMbIsNonNegative() {
        service.sample();
        long heapUsed = (Long) service.getHistory().get(0).get("heap_used_mb");
        assertThat(heapUsed).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("thread count is positive")
    void sample_threadCountIsPositive() {
        service.sample();
        int threads = (Integer) service.getHistory().get(0).get("threads");
        assertThat(threads).isGreaterThan(0);
    }

    @Test
    @DisplayName("multiple samples return all entries")
    void getHistory_multipleSamples_returnsAll() {
        service.sample();
        service.sample();
        service.sample();
        assertThat(service.getHistory()).hasSize(3);
    }

    @Test
    @DisplayName("sample trims history to 1440 entries")
    @SuppressWarnings("unchecked")
    void sample_trimTo1440Entries() {
        Deque<Map<String, Object>> deque = (Deque<Map<String, Object>>)
                ReflectionTestUtils.getField(service, "history");
        // Fill to exactly 1440 entries
        for (int i = 0; i < 1440; i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("ts", "2026-01-01T00:00:00");
            p.put("heap_used_mb", 0L);
            deque.addLast(p);
        }
        assertThat(deque).hasSize(1440);

        // One more sample should keep size at 1440 (oldest evicted)
        service.sample();

        assertThat(service.getHistory()).hasSize(1440);
    }

    // -- GC delta tabani (D19) -------------------------------------------------

    @Test
    @DisplayName("Ilk ornek TABAN kurar: gc_delta_ms 0 - JVM omru boyu birikmis GC DEGIL")
    void sample_firstSample_gcDeltaIsZero() {
        // lastGcMs 0'dan basladigi icin ilk nokta "son orneklemeden bu yana" yerine surecin
        // acilisindan beri biriken toplam GC suresini gosteriyordu: grafigin ilk noktasi her
        // acilista sahte bir sicrama ciziyordu.
        MetricsService fresh = new MetricsService();
        fresh.sample();

        Object delta = fresh.getHistory().get(0).get("gc_delta_ms");
        org.assertj.core.api.Assertions.assertThat(((Number) delta).longValue()).isZero();
    }

    @Test
    @DisplayName("Sonraki ornekler gercek delta uretir (negatif olmaz)")
    void sample_subsequentSamples_nonNegativeDelta() {
        MetricsService fresh = new MetricsService();
        fresh.sample();
        fresh.sample();

        Object delta = fresh.getHistory().get(1).get("gc_delta_ms");
        org.assertj.core.api.Assertions.assertThat(((Number) delta).longValue()).isGreaterThanOrEqualTo(0L);
    }
}
