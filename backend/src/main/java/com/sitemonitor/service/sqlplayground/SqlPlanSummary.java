package com.sitemonitor.service.sqlplayground;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL {@code EXPLAIN (FORMAT JSON)} çıktısının özeti (2026-11-13, C1) — saf, sınırlı.
 *
 * <p>Girdi sürücünün döndürdüğü JSON'un ayrıştırılmış hâli ({@code List} → {@code Map}). Çıktı:
 * {@code total_cost} (kök düğüm), {@code plan_rows}, {@code actual_ms} (yalnız ANALYZE: "Execution Time"), {@code nodes}
 * (düğüm sayısı), {@code seq_scans} [{table, rows}] (ANALYZE'de gerçek satır × döngü, değilse tahmin) ve
 * {@code top_nodes} [{node_type, relation?, cost, actual_ms?, share}] — düğümün KENDİ payı (alt düğümler düşülmüş),
 * pahalıdan ucuza ilk {@value #TOP_N}; {@code share} 0–1 (kökün toplamına oran, 3 basamak).
 *
 * <p>Gezinme yinelemeli (yığın), en çok {@value #MAX_NODES} düğüm ve {@value #MAX_DEPTH} derinlik — kötü niyetli /
 * devasa plan sınırsız döngü ya da yığın taşması yapamaz.
 */
public final class SqlPlanSummary {

    private SqlPlanSummary() {}

    static final int TOP_N = 5;
    static final int MAX_NODES = 10_000;
    static final int MAX_DEPTH = 200;

    private record Node(Map<String, Object> plan, int depth) {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> summarize(Object parsed, boolean analyzed) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> top = null;
        if (parsed instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m) top = (Map<String, Object>) m;
        else if (parsed instanceof Map<?, ?> m) top = (Map<String, Object>) m;
        Map<String, Object> root = top != null && top.get("Plan") instanceof Map<?, ?> p ? (Map<String, Object>) p : null;
        if (root == null) {
            out.put("total_cost", null);
            out.put("plan_rows", null);
            out.put("actual_ms", null);
            out.put("nodes", 0);
            out.put("seq_scans", List.of());
            out.put("top_nodes", List.of());
            return out;
        }
        double rootCost = num(root.get("Total Cost"));
        double rootMs = analyzed ? num(root.get("Actual Total Time")) * Math.max(1, num(root.get("Actual Loops"))) : 0;

        List<Map<String, Object>> seq = new ArrayList<>();
        List<double[]> scored = new ArrayList<>();
        List<Map<String, Object>> scoredNodes = new ArrayList<>();
        int count = 0;
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(new Node(root, 0));
        while (!stack.isEmpty() && count < MAX_NODES) {
            Node nd = stack.pop();
            Map<String, Object> p = nd.plan();
            count++;
            List<Map<String, Object>> children = new ArrayList<>();
            if (p.get("Plans") instanceof List<?> kids) {
                for (Object k : kids) if (k instanceof Map<?, ?> km) children.add((Map<String, Object>) km);
            }
            double cost = num(p.get("Total Cost"));
            double ms = analyzed ? num(p.get("Actual Total Time")) * Math.max(1, num(p.get("Actual Loops"))) : 0;
            double childCost = 0, childMs = 0;
            for (Map<String, Object> c : children) {
                childCost += num(c.get("Total Cost"));
                if (analyzed) childMs += num(c.get("Actual Total Time")) * Math.max(1, num(c.get("Actual Loops")));
                if (nd.depth() + 1 <= MAX_DEPTH) stack.push(new Node(c, nd.depth() + 1));
            }
            double selfCost = Math.max(0, cost - childCost);
            double selfMs = Math.max(0, ms - childMs);
            String type = str(p.get("Node Type"));
            String relation = str(p.get("Relation Name"));
            if ("Seq Scan".equals(type) && relation != null) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("table", relation);
                s.put("rows", analyzed
                        ? Math.round(num(p.get("Actual Rows")) * Math.max(1, num(p.get("Actual Loops"))))
                        : Math.round(num(p.get("Plan Rows"))));
                seq.add(s);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node_type", type);
            if (relation != null) m.put("relation", relation);
            m.put("cost", round(selfCost, 2));
            if (analyzed) m.put("actual_ms", round(selfMs, 3));
            double share = analyzed ? (rootMs > 0 ? selfMs / rootMs : 0) : (rootCost > 0 ? selfCost / rootCost : 0);
            m.put("share", round(Math.min(1, share), 3));
            scoredNodes.add(m);
            scored.add(new double[]{analyzed ? selfMs : selfCost, scoredNodes.size() - 1});
        }
        scored.sort(Comparator.comparingDouble((double[] a) -> a[0]).reversed());
        List<Map<String, Object>> topNodes = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP_N, scored.size()); i++) topNodes.add(scoredNodes.get((int) scored.get(i)[1]));

        out.put("total_cost", round(rootCost, 2));
        out.put("plan_rows", Math.round(num(root.get("Plan Rows"))));
        out.put("actual_ms", analyzed && top.get("Execution Time") != null ? round(num(top.get("Execution Time")), 3) : null);
        out.put("nodes", count);
        out.put("seq_scans", seq);
        out.put("top_nodes", topNodes);
        return out;
    }

    private static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return 0;
        try { return Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException e) { return 0; }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
