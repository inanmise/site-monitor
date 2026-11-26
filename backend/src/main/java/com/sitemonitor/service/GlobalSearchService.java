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

    /**
     * Arama vuruşu. 2026-09-20 (kullanıcı bildirimi): sonuçta takım adı, grup, etiketler ve tier de taşınır — palet
     * yalnız ad gösteriyordu, "hangi takımın / hangi grubun" sorusu cevapsızdı. Etiket ve grup adı ARAMAYA da girer.
     */
    public record Hit(String kind, String id, String label, String sub, Long teamId, String tab, Map<String, Object> params,
                      String teamName, String groupName, String tags, Integer tier) {
        public Hit(String kind, String id, String label, String sub, Long teamId, String tab, Map<String, Object> params) {
            this(kind, id, label, sub, teamId, tab, params, null, null, null, null);
        }
    }

    /**
     * İzleme türü → (tablo, hedef sütunu, nav sekmesi, canlı-satır süzgeci). {@code liveFilter}: yumuşak silinen
     * satırı dışarıda bırakan ek koşul — yalnız DNS/Port'ta {@code deleted_at} var (2026-09-27; diğer türler kalıcı
     * silinir). Silinmiş izleme palet sonucunda görünmez; duraklatılmış görünür (diğer türlerle aynı).
     * İKİNCİ okuyucu: {@link AlertKeyOwnershipService} (alarm anahtarı → sahip takım; bakım/teyit kapıları) —
     * yeni tür eklerken ikisi de buradan beslenir.
     */
    record Kind(String table, String targetCol, String tab, String liveFilter) {
        Kind(String table, String targetCol, String tab) { this(table, targetCol, tab, null); }
    }
    static final Map<String, Kind> MONITOR_KINDS = new LinkedHashMap<>();
    static {
        MONITOR_KINDS.put("http",      new Kind("http_monitors",      "url",    "http"));
        MONITOR_KINDS.put("ping",      new Kind("ping_monitors",      "host",   "ping"));
        MONITOR_KINDS.put("port",      new Kind("port_monitors",      "host",   "port", "deleted_at IS NULL"));
        MONITOR_KINDS.put("dns",       new Kind("dns_monitors",       "domain", "dns",  "deleted_at IS NULL"));
        MONITOR_KINDS.put("keyword",   new Kind("keyword_monitors",   "url",    "keyword"));
        MONITOR_KINDS.put("page",      new Kind("page_monitors",      "url",    "page"));
        MONITOR_KINDS.put("pagespeed", new Kind("pagespeed_monitors", "url",    "pagespeed"));
        MONITOR_KINDS.put("scripted",  new Kind("scripted_monitors",  "name",   "scripted"));
        MONITOR_KINDS.put("dbquery",   new Kind("dbquery_monitors",   "name",   "dbquery"));   // veritabanı sorgu izlemesi (2026-10-31)
        MONITOR_KINDS.put("domain",    new Kind("domain_monitors",    "domain", "domain"));
    }
    static final int MAX_CERTS = 20, MAX_PER_MONITOR = 6, MAX_TEAMS = 5, MIN_QUERY = 2;

    private final JdbcTemplate jdbc;

    /**
     * Görülebilir takım kümesi (2026-10-22, DATA-6): kapsam LIMIT'ten ÖNCE, SQL'de uygulanır. Eskiden her kaynak ilk
     * {@code LIMIT} satırı (ada göre) okuyup kapsamı Java'da süzüyordu — başka takımların adça önce gelen kayıtları kullanıcının
     * kendi eşleşmelerini sayfa dışına itiyordu ("api", "prod" gibi sık terimlerde "sonuç yok"). Küme, predicate'in her takım
     * kimliğine (ve takımsız kayda) sorulmasıyla kurulur; hepsi görülebiliyorsa ({@code all}) SQL süzgeci yoktur.
     */
    record Scope(boolean all, boolean teamless, List<Long> ids) {
        /** {@code alias.col IN (…)} [{@code OR alias.col IS NULL}] — boş küme ve takımsız görünmezse "hiçbiri" (1 = 0). */
        String clause(String col, String orCol, List<Object> args) {
            if (all) return "";
            List<String> parts = new ArrayList<>();
            if (!ids.isEmpty()) {
                String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
                parts.add(col + " IN (" + marks + ")");
                args.addAll(ids);
                if (orCol != null) { parts.add(orCol + " IN (" + marks + ")"); args.addAll(ids); }
            }
            if (teamless) parts.add(col + " IS NULL");
            return parts.isEmpty() ? " AND 1 = 0" : " AND (" + String.join(" OR ", parts) + ")";
        }
    }

    static Scope scopeOf(java.util.Collection<Long> teamIds, Predicate<Long> canViewTeam, boolean teamsKnown) {
        boolean teamless = canViewTeam.test(null);
        List<Long> ids = new ArrayList<>();
        boolean all = teamsKnown && teamless;
        for (Long id : teamIds) {
            if (id == null) continue;
            if (canViewTeam.test(id)) ids.add(id); else all = false;
        }
        // Takım listesi okunamadıysa SQL süzgeci kurulamaz → eski yol (Java süzgeci) — "all" ama satırlar yine predicate'ten geçer.
        if (!teamsKnown) all = true;
        return new Scope(all, teamless, ids);
    }

    public List<Hit> search(String q, Predicate<Long> canViewTeam) {
        return search(q, canViewTeam, null);
    }

    /** Olay kaydı aramasında en çok sonuç (2026-11-26, "Bul ve Raporla"). */
    static final int MAX_INCIDENTS = 6;

    /**
     * Olay kaydı arama kapsamı (2026-11-26): {@code teams} null = tüm takımların kayıtları (global görüntüleyici / 7/24
     * operatörü), dolu = kaydın takımı ya da giren takımı bu kümede — {@code IncidentController.canReadIncident} ile aynı
     * kural, LIMIT'ten ÖNCE SQL'de. Boş küme = hiçbiri. Çağıran {@code incidents.view} iznini ayrıca denetler; izin yoksa
     * bu nesne verilmez (olay araması yapılmaz).
     */
    public record IncidentScope(List<Long> teams) { }

    /**
     * {@link #search(String, Predicate)} + olay kayıtları ({@code incidents} null = olay araması yok). Olay vuruşu yalnız kısa
     * alanlara bakar (başlık, alan, etiket, üç kod) + kimlik ({@code #123} / {@code 123}); markdown metinleri aranmaz (palet
     * her tuşta sorar). Vuruş {@code incident-history} sekmesini {@code incident=<id>} ile açar.
     */
    public List<Hit> search(String q, Predicate<Long> canViewTeam, IncidentScope incidents) {
        List<Hit> out = new ArrayList<>(searchBase(q, canViewTeam));
        if (incidents != null) {
            String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
            if (needle.length() >= MIN_QUERY || idOf(needle) != null) out.addAll(searchIncidents(needle, incidents));
        }
        return out;
    }

    /** {@code #123} / {@code 123} → kimlik (en çok 18 hane); başka metin → null. */
    static Long idOf(String needle) {
        if (needle == null) return null;
        String s = needle.startsWith("#") ? needle.substring(1).trim() : needle;
        if (s.isEmpty() || s.length() > 18) return null;
        for (int k = 0; k < s.length(); k++) if (s.charAt(k) < '0' || s.charAt(k) > '9') return null;
        return Long.valueOf(s);
    }

    private List<Hit> searchIncidents(String needle, IncidentScope incidents) {
        List<Hit> out = new ArrayList<>();
        List<Long> teams = incidents.teams();
        if (teams != null && teams.isEmpty()) return out;
        try {
            Long id = idOf(needle);
            String like = "%" + needle.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            List<Object> args = new ArrayList<>(List.of(like, like, like, like, like, like));
            String idSql = "";
            if (id != null) { idSql = " OR id = ?"; args.add(id); }
            String scopeSql = "";
            if (teams != null) {
                String marks = String.join(",", java.util.Collections.nCopies(teams.size(), "?"));
                scopeSql = " AND (team_id IN (" + marks + ") OR created_by_team_id IN (" + marks + "))";
                args.addAll(teams);
                args.addAll(teams);
            }
            jdbc.query("SELECT id, title, severity, status, service, team_id, team_name FROM incident_records WHERE ("
                    + "LOWER(title) LIKE ? ESCAPE '!' OR LOWER(COALESCE(service,'')) LIKE ? ESCAPE '!' "
                    + "OR LOWER(COALESCE(tags,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(error_code,'')) LIKE ? ESCAPE '!' "
                    + "OR LOWER(COALESCE(function_code,'')) LIKE ? ESCAPE '!' OR LOWER(COALESCE(channel_code,'')) LIKE ? ESCAPE '!'"
                    + idSql + ")" + scopeSql + " ORDER BY occurred_at DESC, id DESC LIMIT " + MAX_INCIDENTS, rs -> {
                long rid = rs.getLong("id");
                long tid = rs.getLong("team_id"); Long teamId = rs.wasNull() ? null : tid;
                String title = rs.getString("title");
                String service = rs.getString("service");
                String sub = "#" + rid + (service != null && !service.isBlank() ? " · " + service : "");
                out.add(new Hit("incident", String.valueOf(rid), title, sub, teamId, "incident-history",
                        Map.of("incident", String.valueOf(rid)), rs.getString("team_name"), null, null, null));
            }, args.toArray());
        } catch (Exception e) { log.debug("palet: olay kaydı araması düştü: {}", e.toString()); }
        return out;
    }

    private List<Hit> searchBase(String q, Predicate<Long> canViewTeam) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (needle.length() < MIN_QUERY) return List.of();
        String like = "%" + needle.replace("%", "\\%").replace("_", "\\_") + "%";
        List<Hit> out = new ArrayList<>();
        Map<Long, String> teamNames = new java.util.HashMap<>();
        boolean teamsKnown = true;
        try { jdbc.query("SELECT id, name FROM teams", rs -> { teamNames.put(rs.getLong("id"), rs.getString("name")); }); }
        catch (Exception e) { teamsKnown = false; log.debug("palet: takım adları okunamadı: {}", e.toString()); }
        Scope scope = scopeOf(teamNames.keySet(), canViewTeam, teamsKnown);

        // Sertifika envanteri — SY ya da UG takımı görülebilen kayıt (2026-10-22, DATA-6: UG eskiden hiç aranmıyordu)
        try {
            List<Object> args = new ArrayList<>(List.of(like, like, like, like, like));
            String scopeSql = scope.clause("team_id", "ug_team_id", args);
            jdbc.query("SELECT domain, team_id, ug_team_id, owner, description, group_name, tags, tier FROM certificate_inventory WHERE active = TRUE AND ("
                    + "LOWER(domain) LIKE ? OR LOWER(COALESCE(owner,'')) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ? "
                    + "OR LOWER(COALESCE(group_name,'')) LIKE ? OR LOWER(COALESCE(tags,'')) LIKE ?)" + scopeSql + " "
                    + "ORDER BY domain LIMIT " + (MAX_CERTS * 3), rs -> {
                long tid = rs.getLong("team_id"); Long teamId = rs.wasNull() ? null : tid;
                long uid = rs.getLong("ug_team_id"); Long ugId = rs.wasNull() ? null : uid;
                if (!canViewTeam.test(teamId) && !(ugId != null && canViewTeam.test(ugId))) return;
                if (out.stream().filter(h -> "certificate".equals(h.kind())).count() >= MAX_CERTS) return;
                String domain = rs.getString("domain");
                String sub = firstNonBlank(rs.getString("owner"), rs.getString("description"));
                int tierRaw = rs.getInt("tier"); Integer tier = rs.wasNull() ? null : tierRaw;
                out.add(new Hit("certificate", domain, domain, sub, teamId, "dashboard", Map.of("domain", domain),
                        teamId != null ? teamNames.get(teamId) : null, rs.getString("group_name"), rs.getString("tags"), tier));
            }, args.toArray());
        } catch (Exception e) { log.debug("palet: envanter araması düştü: {}", e.toString()); }

        // İzlemeler (9 tür)
        for (Map.Entry<String, Kind> e : MONITOR_KINDS.entrySet()) {
            Kind k = e.getValue();
            String kind = e.getKey();
            String cols = (k.targetCol().equals("name") ? "id, name, name AS target, team_id" : "id, name, " + k.targetCol() + " AS target, team_id")
                    + ", group_name, tags";
            // Etiket ve grup adı da aranır (2026-09-20).
            String where = (k.targetCol().equals("name") ? "LOWER(name) LIKE ?" : "LOWER(name) LIKE ? OR LOWER(COALESCE(" + k.targetCol() + ",'')) LIKE ?")
                    + " OR LOWER(COALESCE(group_name,'')) LIKE ? OR LOWER(COALESCE(tags,'')) LIKE ?";
            // Ek koşul VEYA zincirinin TAMAMINI sarmalı — parantezsiz "… OR tags LIKE ? AND deleted_at IS NULL"
            // yalnız son terimi süzerdi.
            if (k.liveFilter() != null) where = "(" + where + ") AND " + k.liveFilter();
            try {
                List<Object> args = new ArrayList<>(k.targetCol().equals("name") ? List.of(like, like, like) : List.of(like, like, like, like));
                String scopeSql = scope.clause("team_id", null, args);
                jdbc.query("SELECT " + cols + " FROM " + k.table() + " WHERE (" + where + ")" + scopeSql
                        + " ORDER BY name LIMIT " + (MAX_PER_MONITOR * 3), rs -> {
                    long tid = rs.getLong("team_id"); Long teamId = rs.wasNull() ? null : tid;
                    if (!canViewTeam.test(teamId)) return;
                    if (out.stream().filter(h -> kind.equals(h.kind())).count() >= MAX_PER_MONITOR) return;
                    String id = String.valueOf(rs.getLong("id"));
                    String name = rs.getString("name"), target = rs.getString("target");
                    out.add(new Hit(kind, id, name, target != null && !target.equals(name) ? target : null, teamId, k.tab(), Map.of("monitor", id),
                            teamId != null ? teamNames.get(teamId) : null, rs.getString("group_name"), rs.getString("tags"), null));
                }, args.toArray());
            } catch (Exception ex) { log.debug("palet: {} araması düştü: {}", kind, ex.toString()); }
        }

        // Takımlar — kapsam SQL'de; vuruşun parametresi takım ADI (2026-10-22, UIM-3 / K7): pano süzgeci takım adlarıyla
        // karşılaştırır, kimlikle ("42") gelen süzgeç boş pano ve "Takım: 42" çipi gösteriyordu.
        try {
            List<Object> args = new ArrayList<>(List.of(like));
            String scopeSql = scope.all() ? "" : (scope.ids().isEmpty() ? " AND 1 = 0"
                    : " AND id IN (" + String.join(",", java.util.Collections.nCopies(scope.ids().size(), "?")) + ")");
            if (!scope.all()) args.addAll(scope.ids());
            jdbc.query("SELECT id, name FROM teams WHERE LOWER(name) LIKE ?" + scopeSql + " ORDER BY name LIMIT " + (MAX_TEAMS * 3), rs -> {
                long id = rs.getLong("id");
                if (!canViewTeam.test(id)) return;
                if (out.stream().filter(h -> "team".equals(h.kind())).count() >= MAX_TEAMS) return;
                String name = rs.getString("name");
                out.add(new Hit("team", String.valueOf(id), name, null, id, "dashboard",
                        Map.of("team", name != null ? name : String.valueOf(id))));
            }, args.toArray());
        } catch (Exception e) { log.debug("palet: takım araması düştü: {}", e.toString()); }

        return out;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }
}
