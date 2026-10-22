package com.sitemonitor.repository;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.AlertEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DATA-7 (2026-10-22) GERÇEK PostgreSQL'de: aynı saniyede açılan 1 200 alarm 500'lük sayfalarla okunduğunda her alarm TAM BİR
 * kez gelir. Eşitlik bozucusuz {@code ORDER BY created_at} (ya da hiç ORDER BY olmaması — haftalık kesinti raporunun eski
 * hâli) PostgreSQL'de top-N sıralamasında sayfalar arası eşitleri farklı sıralar; satır iki sayfada birden görünür ya da hiç
 * görünmez. H2 eklenme sırasını koruduğu için bunu göremez.
 */
@PostgresIntegration
class ReadPathPagingPostgresTest {

    private static final int N = 1200;
    private static final int PAGE = 500;

    @Test
    @DisplayName("haftalık kesinti raporu sırası (createdAt, id) ve olaylar sayfası sırası: 1 200 eş-zamanlı alarm, 500'lük sayfalar → kimlik kümesi tam")
    void sameSecondAlarmsPageExactlyOnce() {
        JdbcTemplate jdbc = PostgresIt.app().jdbc();
        AlertEventRepository repo = PostgresIt.app().bean(AlertEventRepository.class);
        long team = 970_000L + (long) (Math.random() * 10_000);
        String ts = "2031-03-04T05:06:07";
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            rows.add(new Object[]{ "page-it-" + team + "-" + i + ".example.com", "WARNING", "HTTP_DOWN", ts, false, false, team });
        }
        try {
            jdbc.batchUpdate("INSERT INTO alert_events (domain, alert_level, alert_type, created_at, resolved, acknowledged, team_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
            Set<Long> all = new HashSet<>(jdbc.queryForList("SELECT id FROM alert_events WHERE team_id = ?", Long.class, team));
            assertThat(all).hasSize(N);

            Set<Long> weekly = new HashSet<>();
            int weeklyRows = 0;
            for (int p = 0; p * PAGE < N; p++) {
                Page<AlertEvent> page = repo.findFiltered(null, ts, ts, null, null, null, null, true, List.of(team),
                        PageRequest.of(p, PAGE, com.sitemonitor.service.report.WeeklyOutageReportService.STABLE_ORDER));
                for (AlertEvent e : page.getContent()) { weekly.add(e.getId()); weeklyRows++; }
            }
            assertThat(weeklyRows).isEqualTo(N);
            assertThat(weekly).isEqualTo(all);

            Set<Long> incidents = new HashSet<>();
            int incidentRows = 0;
            for (int p = 0; p * PAGE < N; p++) {
                Page<AlertEvent> page = repo.findIncidents(null, ts, ts, null, null, true, false, List.of(team),
                        PageRequest.of(p, PAGE, com.sitemonitor.controller.IncidentsController.sortFor("started", "desc")));
                for (AlertEvent e : page.getContent()) { incidents.add(e.getId()); incidentRows++; }
            }
            assertThat(incidentRows).isEqualTo(N);
            assertThat(incidents).isEqualTo(all);
        } finally {
            jdbc.update("DELETE FROM alert_events WHERE team_id = ?", team);
        }
    }
}
