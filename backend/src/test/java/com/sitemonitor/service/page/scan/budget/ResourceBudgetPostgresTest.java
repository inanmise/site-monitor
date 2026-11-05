package com.sitemonitor.service.page.scan.budget;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bütçe + eğilim GERÇEK PostgreSQL'de (2026-11, F1 / F2): açılış yaması {@code page_monitors.resource_budget_json} kolonunu
 * kurar; TEXT kolonda koşullu yazım ({@code = ?}) ve eş zamanlı birleştirme; BIGINT / INTEGER / BOOLEAN kolonlarının okunması;
 * {@code LIMIT ?}. Algoritma 3 özet kolonları ({@code js_bytes} …) bu veritabanında olsa da olmasa da okuma çökmez — testin
 * beklentileri iki durumda da tutan değerlerdir.
 */
@PostgresIntegration
class ResourceBudgetPostgresTest {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Test
    @DisplayName("bütçe kolonu yamayla kurulur; koşullu yazım + birleştirme; işaretleme ve eğilim PostgreSQL tipleriyle")
    void budgetAndTrendOnPostgres() {
        JdbcTemplate jdbc = PostgresIt.app().jdbc();
        ResourceBudgetService budgets = PostgresIt.app().bean(ResourceBudgetService.class);
        ResourceTrendService trends = PostgresIt.app().bean(ResourceTrendService.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'page_monitors' "
                + "AND column_name = 'resource_budget_json'", Integer.class)).isEqualTo(1);

        jdbc.update("DELETE FROM page_monitors WHERE name = 'pg-budget-it'");
        jdbc.update("INSERT INTO page_monitors (name, url, active) VALUES ('pg-budget-it', 'https://pg-budget.example.test/', TRUE)");
        long mon = jdbc.queryForObject("SELECT MAX(id) FROM page_monitors WHERE name = 'pg-budget-it'", Long.class);
        try {
            // 1) Koşullu yazım: NULL → değer; eski okumayla gelen ikinci değişiklik güncel değere uygulanır
            assertThat(budgets.save(mon, null, Map.of("max_page_kb", 500)).outcome()).isEqualTo(ResourceBudgetService.Outcome.SAVED);
            String seen = jdbc.queryForObject("SELECT resource_budget_json FROM page_monitors WHERE id = ?", String.class, mon);
            assertThat(budgets.save(mon, seen, Map.of("max_requests", 40)).outcome()).isEqualTo(ResourceBudgetService.Outcome.SAVED);
            assertThat(budgets.save(mon, seen, Map.of("min_grade", 70)).outcome()).isEqualTo(ResourceBudgetService.Outcome.SAVED);
            String stored = jdbc.queryForObject("SELECT resource_budget_json FROM page_monitors WHERE id = ?", String.class, mon);
            assertThat(ResourceBudget.parse(stored).toMap()).containsEntry("max_page_kb", 500).containsEntry("max_requests", 40)
                    .containsEntry("min_grade", 70);

            // 2) Özet satırları (gerçek tipler) + işaretleme
            String at = ISO.format(Instant.now().minusSeconds(3600));
            for (long bytes : new long[]{100_000L, 900_000L}) {
                jdbc.update("INSERT INTO page_resource_scans (monitor_id, url, status, started_at, finished_at, capped, grade, "
                                + "load_ms, page_bytes, request_count, image_bytes, items_kept, items_json, algorithm_version) "
                                + "VALUES (?, 'https://pg-budget.example.test/', 'DONE', ?, ?, FALSE, 80, 1500, ?, 30, 10000, TRUE, ?, 2)",
                        mon, at, at, bytes, "[{\"url\":\"https://pg-budget.example.test/a.js\",\"category\":\"JS\",\"bytes\":"
                                + bytes + ",\"page_load\":true,\"verdict\":\"ok\"}]");
            }
            List<Long> ids = jdbc.queryForList("SELECT id FROM page_resource_scans WHERE monitor_id = ? ORDER BY id", Long.class, mon);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Long id : ids) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", id);
                rows.add(r);
            }
            ResourceBudget b = ResourceBudget.parse(stored);
            assertThat(budgets.annotate(mon, b, rows)).isTrue();
            assertThat(rows.get(0)).containsEntry("budget_status", "ok");
            assertThat(rows.get(1)).containsEntry("budget_status", "exceeded").containsEntry("budget_exceeded", 1);
            assertThat(budgets.evaluateOne(mon, b, ids.get(1))).containsEntry("status", "exceeded");

            // 3) Eğilim: noktalar + büyüme (iki analiz, aynı algoritma sürümü)
            Map<String, Object> t = trends.trend(mon, 30, b);
            assertThat((List<?>) t.get("points")).hasSize(2);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> growth = (List<Map<String, Object>>) t.get("growth");
            assertThat(growth).hasSize(1);
            assertThat(growth.get(0)).containsEntry("first_bytes", 100_000L).containsEntry("last_bytes", 900_000L);
        } finally {
            jdbc.update("DELETE FROM page_resource_scans WHERE monitor_id = ?", mon);
            jdbc.update("DELETE FROM page_monitors WHERE id = ?", mon);
        }
    }
}
