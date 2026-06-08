package com.certmonitor.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sabit liste — sistemdeki yetki birimlerinin (resource_key) hangi action'lar
 * için anlamlı olduğunu tanımlar. Permission Matrix UI'ı bu katalog üzerinden
 * render edilir; bootstrap seed bu listeyi temel alır.
 *
 * Her satır: { key, group, actions, sensitive } — sensitive: confirmation gerektiren action'lar.
 */
public final class PermissionCatalog {

    public static final String VIEW    = "view";
    public static final String EDIT    = "edit";
    public static final String EXECUTE = "execute";

    public static final List<Resource> ALL = List.of(
        // ── Sertifika Yönetimi ────────────────────────────────────────────
        r("inventory.list",     "certificates", VIEW),
        r("inventory.crud",     "certificates", EDIT),
        r("inventory.transfer", "certificates", "execute", Set.of(EXECUTE)),
        r("notes.read",         "certificates", VIEW),
        r("notes.crud",         "certificates", EDIT),

        // ── İletişim / Eskalasyon ─────────────────────────────────────────
        r("contacts.list",      "communication", VIEW),
        r("contacts.crud",      "communication", EDIT),

        // ── Yönetim (Takım & Kullanıcı) ───────────────────────────────────
        r("teams.list",         "management", VIEW),
        r("teams.update",       "management", EDIT),
        r("teams.lifecycle",    "management", EXECUTE, Set.of(EXECUTE)),
        r("users.list",         "management", VIEW),
        r("users.crud",         "management", EDIT),
        r("users.actions",      "management", EXECUTE),
        r("thresholds.read",    "management", VIEW),
        r("thresholds.edit",    "management", EDIT, Set.of(EDIT)),

        // ── Alarmlar ──────────────────────────────────────────────────────
        r("alerts.read",        "alerts", VIEW),
        r("alerts.actions",     "alerts", EXECUTE),

        // ── İzleme ────────────────────────────────────────────────────────
        r("system_health.read",    "monitoring", VIEW),
        r("system_health.actions", "monitoring", EXECUTE, Set.of(EXECUTE)),
        r("monitoring.read",       "monitoring", VIEW),
        r("monitoring.crud",       "monitoring", EDIT),
        r("monitoring.trigger",    "monitoring", EXECUTE),

        // ── Loglar & Raporlar ─────────────────────────────────────────────
        r("audit_log.read",     "logs", VIEW),
        r("weak_algo.read",     "logs", VIEW),

        // ── Yönetim Araçları (kritik) ─────────────────────────────────────
        r("sql_playground.execute", "tools", EXECUTE, Set.of(EXECUTE)),
        r("guide_links.crud",       "tools", EDIT)
    );

    /**
     * Permission matrix UI'ında gizlenen, idari iç-kullanım resource'ları.
     * Backend yine bu key'leri permission check için kullanır.
     */
    public static final List<Resource> INTERNAL = List.of(
        r("permissions.manage",   "system", EDIT, Set.of(EDIT, EXECUTE)),
        r("system.global_admin",  "system", EXECUTE),
        r("system.team_admin",    "system", EXECUTE)
    );

    public static class Resource {
        public String key;
        public String group;
        public List<String> actions;
        public Set<String> sensitive;
        public Resource(String key, String group, List<String> actions, Set<String> sensitive) {
            this.key = key; this.group = group; this.actions = actions; this.sensitive = sensitive;
        }
        public String getKey() { return key; }
        public String getGroup() { return group; }
        public List<String> getActions() { return actions; }
        public Set<String> getSensitive() { return sensitive; }
    }

    private static Resource r(String key, String group, String action) {
        return new Resource(key, group, List.of(action), Set.of());
    }
    private static Resource r(String key, String group, String action, Set<String> sensitive) {
        return new Resource(key, group, List.of(action), sensitive);
    }

    /**
     * Default seed values — bootstrap'ta ilk başlangıçta uygulanır.
     * Mevcut hard-coded davranışın aynısı:
     *   ADMIN → her şeyi yapar
     *   TEAM_ADMIN → kendi takım scope'unda CRUD + bazı action'lar (önceki PR davranışı)
     *   USER → read-only + alert actions
     *   AUDIT → sistem geneli read-only
     */
    public static Map<String, Map<String, Boolean>> defaultsFor(String role) {
        return switch (role) {
            case "ADMIN" -> adminDefaults();
            case "TEAM_ADMIN" -> teamAdminDefaults();
            case "USER" -> userDefaults();
            case "AUDIT" -> auditDefaults();
            default -> Map.of();
        };
    }

    private static Map<String, Map<String, Boolean>> adminDefaults() {
        var map = new java.util.LinkedHashMap<String, Map<String, Boolean>>();
        for (Resource r : ALL) putAll(map, r, true);
        for (Resource r : INTERNAL) putAll(map, r, true);
        return map;
    }

    private static Map<String, Map<String, Boolean>> teamAdminDefaults() {
        var map = new java.util.LinkedHashMap<String, Map<String, Boolean>>();
        // Allow on team scope: inventory.list/crud, contacts.list/crud, notes.read/crud,
        // teams.list/update, users.list/crud/actions, alerts.read/actions,
        // system_health.read, audit_log.read, weak_algo.read, monitoring.read
        Set<String> allowed = Set.of(
            "inventory.list", "inventory.crud",
            "contacts.list", "contacts.crud",
            "notes.read", "notes.crud",
            "teams.list", "teams.update",
            "users.list", "users.crud", "users.actions",
            "alerts.read", "alerts.actions",
            "system_health.read",
            "audit_log.read", "weak_algo.read",
            "monitoring.read"
        );
        for (Resource r : ALL) putAll(map, r, allowed.contains(r.key));
        // Internal: team_admin sentinel for legacy helper
        putAll(map, INTERNAL.stream().filter(x -> x.key.equals("system.team_admin")).findFirst().orElseThrow(), true);
        putAll(map, INTERNAL.stream().filter(x -> x.key.equals("system.global_admin")).findFirst().orElseThrow(), false);
        putAll(map, INTERNAL.stream().filter(x -> x.key.equals("permissions.manage")).findFirst().orElseThrow(), false);
        return map;
    }

    private static Map<String, Map<String, Boolean>> userDefaults() {
        var map = new java.util.LinkedHashMap<String, Map<String, Boolean>>();
        // Read-only across the board, plus alerts.actions (USER manages own team alerts)
        Set<String> allowed = Set.of(
            "inventory.list",
            "contacts.list",
            "notes.read",
            "teams.list",
            "users.list",
            "thresholds.read",
            "alerts.read", "alerts.actions",
            "system_health.read",
            "monitoring.read",
            "audit_log.read",
            "weak_algo.read"
        );
        for (Resource r : ALL) putAll(map, r, allowed.contains(r.key));
        for (Resource r : INTERNAL) putAll(map, r, false);
        return map;
    }

    private static Map<String, Map<String, Boolean>> auditDefaults() {
        var map = new java.util.LinkedHashMap<String, Map<String, Boolean>>();
        // System-wide read-only auditor.
        for (Resource r : ALL) {
            boolean isRead = r.actions.contains(VIEW);
            putAll(map, r, isRead);
        }
        for (Resource r : INTERNAL) putAll(map, r, false);
        return map;
    }

    private static void putAll(Map<String, Map<String, Boolean>> map, Resource r, boolean value) {
        var inner = map.computeIfAbsent(r.key, k -> new java.util.LinkedHashMap<>());
        for (String a : r.actions) inner.put(a, value);
    }

    private PermissionCatalog() {}
}
