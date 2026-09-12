package com.sitemonitor.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Predicate;

/**
 * Komut paleti araması (2026-09-12, zenginleştirme #1): tek kutudan alan / izleme adı / takım.
 * Sonuç {@code {kind, id, label, sub, team_id, tab, params}} — arayüz {@code navigateTo(tab, params)} ile gider.
 *
 * <p>Takım kapsaması ÇAĞIRANDA ({@link java.util.function.Predicate} teamId → görünür mü): servis kendi
 * kapsamını uydurmaz, controller {@code SessionScope.canView} verir. LIKE aramaları küçük harf; JDBC
 * parametreli (enjeksiyon yok). Her kaynak ayrı LIMIT alır — 20 alan + 8×6 izleme + 5 takım tavanı.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GlobalSearchService {

    public record Hit(String kind, String id, String label, String sub, Long teamId, String tab, Map<String, Object> params) {}

    /** İzleme türü → (tablo, hedef sütunu, nav sekmesi). */
    record Kind(String table, String targetCol, String tab) {}
    static final Map<String, Kind> MONITOR_KINDS = new LinkedHashMap<>();
    static {
        MONITOR_KINDS.put("http",      new Kind("http_monitors",      "url",    "http"));
        MONITOR_KINDS.put("ping",      new Kind("ping_monitors",      "host",   "ping"));
        MONITOR_KINDS.put("port",      new Kind("port_monitors",      "host",   "port"));
        MONITOR_KINDS.put("dns",       new Kind("dns_monitors",       "domain", "dns"));
        MONITOR_KINDS.put("keyword",   new Kind("keyword_monitors",   "url",    "keyword"));
        MONITOR_KINDS.put("page",      new Kind("page_monitors",      "url",    "page"));
        MONITOR_KINDS.put("pagespeed", new Kind("pagespeed_monitors", "url",    "pagespeed"));
        MONITOR_KINDS.put("scripted",  new Kind("scripted_monitors",  "name",   "scripted"));
        MONITOR_KINDS.put("domain",    new Kind("domain_monitors",    "domain", "domain"));
    }
    static final int MAX_CERTS = 20, MAX_PER_MONITOR = 6, MAX_TEAMS = 5, MIN_QUERY = 2;

    private final JdbcTemplate jdbc;

    public List<Hit> search(String q, Predicate<Long> canViewTeam) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < MIN_QUERY) return List.of();
        String like = "%" + needle.replace("%", "\\%").replace("_", "\\_") + "%";
        List<Hit> out = new ArrayList<>();

        // Sertifika envanteri
        try {
            jdbc.query("SELECT domain, team_id, owner, description FROM certificate_inventory WHERE active = TRUE AND ("
                    + "LOWER(domain) LIKE ? OR LOWER(COALESCE(owner,'')) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ?) "
                    + "ORDER BY domain LIMIT " + (MAX_CERTS * 3), rs -> {
                long tid = rs.getLong("team_id"); Long teamId = rs.wasNull() ? null : tid;
                if (!canViewTeam.test(teamId)) return;
                if (out.stream().filter(h -> "certificate".equals(h.kind())).count() >= MAX_CERTS) return;
                String domain = rs.getString("domain");
                String sub = firstNonBlank(rs.getString("owner"), rs.getString("description"));
                out.add(new Hit("certificate", domain, domain, sub, teamId, "dashboard", Map.of("domain", domain)));
            }, like, like, like);
        } catch (Exception e) { log.debug("palet: envanter araması düştü: {}", e.toString()); }

        // İzlemeler (9 tür)
        for (Map.Entry<String, Kind> e : MONITOR_KINDS.entrySet()) {
            Kind k = e.getValue();
            String kind = e.getKey();
            String cols = k.targetCol().equals("name") ? "id, name, name AS target, team_id" : "id, name, " + k.targetCol() + " AS target, team_id";
            String where = k.targetCol().equals("name") ? "LOWER(name) LIKE ?" : "LOWER(name) LIKE ? OR LOWER(COALESCE(" + k.targetCol() + ",'')) LIKE ?";
            try {
                List<Object> args = k.targetCol().equals("name") ? List.of(like) : List.of(like, like);
                jdbc.query("SELECT " + cols + " FROM " + k.table() + " WHERE " + where + " ORDER BY name LIMIT " + (MAX_PER_MONITOR * 3), rs -> {
                    long tid = rs.getLong("team_id"); Long teamId = rs.wasNull() ? null : tid;
                    if (!canViewTeam.test(teamId)) return;
                    if (out.stream().filter(h -> kind.equals(h.kind())).count() >= MAX_PER_MONITOR) return;
                    String id = String.valueOf(rs.getLong("id"));
                    String name = rs.getString("name"), target = rs.getString("target");
                    out.add(new Hit(kind, id, name, target != null && !target.equals(name) ? target : null, teamId, k.tab(), Map.of("monitor", id)));
                }, args.toArray());
            } catch (Exception ex) { log.debug("palet: {} araması düştü: {}", kind, ex.toString()); }
        }

        // Takımlar
        try {
            jdbc.query("SELECT id, name FROM teams WHERE LOWER(name) LIKE ? ORDER BY name LIMIT " + (MAX_TEAMS * 3), rs -> {
                long id = rs.getLong("id");
                if (!canViewTeam.test(id)) return;
                if (out.stream().filter(h -> "team".equals(h.kind())).count() >= MAX_TEAMS) return;
                out.add(new Hit("team", String.valueOf(id), rs.getString("name"), null, id, "dashboard", Map.of("team", String.valueOf(id))));
            }, like);
        } catch (Exception e) { log.debug("palet: takım araması düştü: {}", e.toString()); }

        return out;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
