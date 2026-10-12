package com.sitemonitor.repository;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.ActivityLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aktivite özeti (2026-10-11) GERÇEK PostgreSQL'de: saat × durum yerel sorgusu ({@code SUBSTRING … GROUP BY 1, 2},
 * boolean ve liste parametreleri, CAST'li null denetimleri) ve tür sayımı; takım kapsamı ve tür süzgeci uygulanır.
 */
@PostgresIntegration
class ActivityQueriesPostgresTest {

    private static ActivityLog row(String time, String type, String status, Long teamId, String target) {
        ActivityLog a = new ActivityLog();
        a.setActivityTime(time);
        a.setMonitorType(type);
        a.setResultStatus(status);
        a.setTeamId(teamId);
        a.setTarget(target);
        a.setMonitorName("m-" + target);
        a.setAction("SCHEDULED_CHECK");
        return a;
    }

    @Test
    @DisplayName("saat × durum ve tür sayımı: kapsam + tür süzgeci + arama; GROUP BY 1, 2 PostgreSQL'de çalışır")
    void hourlyAndTypeCounts() {
        ActivityLogRepository repo = PostgresIt.app().bean(ActivityLogRepository.class);
        String tag = "act-it-" + UUID.randomUUID().toString().substring(0, 8);
        long team = 987_650L + (long) (Math.random() * 1000);
        repo.saveAll(List.of(
                row("2031-01-05T08:10:00", "HTTP", "SUCCESS", team, tag + "-a"),
                row("2031-01-05T08:40:00", "HTTP", "ERROR", team, tag + "-a"),
                row("2031-01-05T09:05:00", "CERT", "WARNING", team, tag + "-b"),
                row("2031-01-05T09:06:00", "HTTP", "SUCCESS", team + 1, tag + "-c")));   // başka takım

        String q = "%" + tag + "%";
        List<Object[]> hours = repo.countByHourAndStatus(true, List.of(team), false, List.of(""), null,
                "2031-01-05T00:00:00", null, q);
        Map<String, Long> byKey = new HashMap<>();
        for (Object[] r : hours) byKey.put(r[0] + "|" + r[1], ((Number) r[2]).longValue());
        assertThat(byKey).containsEntry("2031-01-05T08|SUCCESS", 1L).containsEntry("2031-01-05T08|ERROR", 1L)
                .containsEntry("2031-01-05T09|WARNING", 1L).hasSize(3);

        List<Object[]> httpOnly = repo.countByHourAndStatus(false, List.of(-1L), true, List.of("HTTP"), null,
                "2031-01-05T00:00:00", "2031-01-05T23:59:59", q);
        long total = httpOnly.stream().mapToLong(r -> ((Number) r[2]).longValue()).sum();
        assertThat(total).as("kapsamsız + yalnız HTTP").isEqualTo(3L);

        Map<String, Long> types = new HashMap<>();
        for (Object[] r : repo.countByTypeFiltered(true, List.of(team), null, null, "2031-01-01T00:00:00", null, q)) {
            types.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        }
        assertThat(types).containsEntry("HTTP", 2L).containsEntry("CERT", 1L).hasSize(2);
        Map<String, Long> errorsOnly = new HashMap<>();
        for (Object[] r : repo.countByTypeFiltered(true, List.of(team), null, "ERROR", null, null, q)) {
            errorsOnly.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        }
        assertThat(errorsOnly).containsExactlyEntriesOf(Map.of("HTTP", 1L));
    }
}
