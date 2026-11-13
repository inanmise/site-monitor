package com.sitemonitor.service.sqlplayground;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SqlPlanSummaryTest {

    private static final String PLAN = """
        [{"Plan": {"Node Type": "Hash Join", "Total Cost": 120.5, "Plan Rows": 40,
                   "Actual Total Time": 8.0, "Actual Loops": 1, "Actual Rows": 38,
          "Plans": [
            {"Node Type": "Seq Scan", "Relation Name": "alert_events", "Total Cost": 80.0, "Plan Rows": 1000,
             "Actual Total Time": 5.0, "Actual Loops": 1, "Actual Rows": 990},
            {"Node Type": "Hash", "Total Cost": 20.0, "Plan Rows": 10, "Actual Total Time": 1.0, "Actual Loops": 1,
             "Plans": [{"Node Type": "Index Scan", "Relation Name": "teams", "Total Cost": 19.0, "Plan Rows": 10,
                        "Actual Total Time": 0.9, "Actual Loops": 1, "Actual Rows": 9}]}
          ]},
          "Planning Time": 0.2, "Execution Time": 8.4}]
        """;

    @Test
    @DisplayName("Özet: kök maliyet, satır, düğüm sayısı, sıralı taramalar, en pahalı düğümler (kendi payı)")
    void summary() throws Exception {
        Object parsed = new ObjectMapper().readValue(PLAN, Object.class);
        Map<String, Object> s = SqlPlanSummary.summarize(parsed, false);
        assertThat(s.get("total_cost")).isEqualTo(120.5);
        assertThat(s.get("plan_rows")).isEqualTo(40L);
        assertThat(s.get("actual_ms")).isNull();
        assertThat(s.get("nodes")).isEqualTo(4);
        @SuppressWarnings("unchecked") List<Map<String, Object>> seq = (List<Map<String, Object>>) s.get("seq_scans");
        assertThat(seq).hasSize(1);
        assertThat(seq.get(0)).containsEntry("table", "alert_events").containsEntry("rows", 1000L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> top = (List<Map<String, Object>>) s.get("top_nodes");
        assertThat(top.get(0)).containsEntry("node_type", "Seq Scan").containsEntry("cost", 80.0);
        // Kök: 120.5 − (80 + 20) = 20.5 kendi payı
        assertThat(top).anySatisfy(n -> assertThat(n).containsEntry("node_type", "Hash Join").containsEntry("cost", 20.5));
    }

    @Test
    @DisplayName("ANALYZE: gerçek süre ve gerçek satır; pay süreye göre")
    void analyzed() throws Exception {
        Map<String, Object> s = SqlPlanSummary.summarize(new ObjectMapper().readValue(PLAN, Object.class), true);
        assertThat(s.get("actual_ms")).isEqualTo(8.4);
        @SuppressWarnings("unchecked") List<Map<String, Object>> seq = (List<Map<String, Object>>) s.get("seq_scans");
        assertThat(seq.get(0)).containsEntry("rows", 990L);
        @SuppressWarnings("unchecked") List<Map<String, Object>> top = (List<Map<String, Object>>) s.get("top_nodes");
        assertThat(top.get(0)).containsEntry("node_type", "Seq Scan").containsEntry("actual_ms", 5.0).containsEntry("share", 0.625);
    }

    @Test
    @DisplayName("Bozuk / boş girdi ve devasa plan sınırlı")
    void bounded() {
        assertThat(SqlPlanSummary.summarize(List.of(), false).get("nodes")).isEqualTo(0);
        assertThat(SqlPlanSummary.summarize("x", true).get("top_nodes")).isEqualTo(List.of());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Node Type", "Append");
        root.put("Total Cost", 1.0);
        List<Object> kids = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) kids.add(Map.of("Node Type", "Seq Scan", "Relation Name", "t", "Total Cost", 0.0));
        root.put("Plans", kids);
        Map<String, Object> s = SqlPlanSummary.summarize(List.of(Map.of("Plan", root)), false);
        assertThat((Integer) s.get("nodes")).isEqualTo(SqlPlanSummary.MAX_NODES);
    }
}
