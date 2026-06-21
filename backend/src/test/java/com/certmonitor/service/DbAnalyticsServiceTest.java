package com.certmonitor.service;

import com.certmonitor.model.SqlQueryHistory;
import com.certmonitor.repository.SqlQueryHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * DbAnalyticsService — pg_stat_statements YOK (fallback) yolunda sql_query_history toplulaştırması.
 * Harici pg_* sorguları mock JdbcTemplate ile null/boş döner; history-tabanlı paneller hesaplanır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DbAnalyticsServiceTest {

    @Mock JdbcTemplate jdbcTemplate;
    @Mock SqlQueryHistoryRepository historyRepo;

    private DbAnalyticsService service;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new DbAnalyticsService(jdbcTemplate, historyRepo);
    }

    private SqlQueryHistory q(String user, String sql, Long durMs, boolean success, Instant when) {
        SqlQueryHistory h = new SqlQueryHistory();
        h.setExecutedBy(user);
        h.setSqlText(sql);
        h.setDurationMs(durMs);
        h.setSuccess(success);
        h.setExecutedAt(ISO.format(when));
        if (!success) h.setErrorMessage("boom");
        return h;
    }

    @Test
    @DisplayName("getOverview (fallback): summary + top_users + top_sql + failed + series")
    @SuppressWarnings("unchecked")
    void getOverview_fallbackAggregates() {
        Instant now = Instant.now();
        List<SqlQueryHistory> rows = List.of(
                q("alice", "SELECT 1", 10L, true, now),
                q("alice", "SELECT 1", 20L, true, now),
                q("bob",   "SELECT 2", 5L,  false, now));
        when(historyRepo.findByExecutedAtGreaterThanEqualOrderByExecutedAtAsc(any())).thenReturn(rows);
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Long.class))).thenReturn(0L); // pgss off

        Map<String, Object> d = service.getOverview(7);
        Map<String, Object> sum = (Map<String, Object>) d.get("summary");

        assertThat(((Number) sum.get("queries")).longValue()).isEqualTo(3);
        assertThat(((Number) sum.get("failed")).longValue()).isEqualTo(1);
        assertThat((Boolean) sum.get("pgss")).isFalse();
        assertThat(((Number) sum.get("avg_ms")).longValue()).isEqualTo(12); // (10+20+5)/3 = 11.67→12
        assertThat((List<?>) d.get("top_users")).hasSize(2);                 // alice, bob
        assertThat((List<?>) d.get("top_sql")).hasSize(2);                   // "SELECT 1", "SELECT 2"
        assertThat((List<?>) d.get("failed")).hasSize(1);                    // bob
        assertThat((List<?>) d.get("recent_queries")).hasSize(3);            // 3 ham satır (SQL metinli)
        // 7 günlük seri → 7 günlük kova; gün sayısı en az 7
        assertThat((List<?>) d.get("series")).hasSizeGreaterThanOrEqualTo(7);
    }

    @Test
    @DisplayName("getOverview: gün penceresi 1/7/30 dışına taşmaz")
    @SuppressWarnings("unchecked")
    void getOverview_windowClamped() {
        when(historyRepo.findByExecutedAtGreaterThanEqualOrderByExecutedAtAsc(any())).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("pg_extension"), eq(Long.class))).thenReturn(0L);

        Map<String, Object> sum = (Map<String, Object>) service.getOverview(999).get("summary");
        assertThat(((Number) sum.get("days")).intValue()).isEqualTo(30);     // 999 → 30
        Map<String, Object> sum1 = (Map<String, Object>) service.getOverview(1).get("summary");
        assertThat(((Number) sum1.get("days")).intValue()).isEqualTo(1);
    }
}
