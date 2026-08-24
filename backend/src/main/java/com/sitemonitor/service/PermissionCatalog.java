package com.sitemonitor.service;

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
        r("inventory.purge",    "certificates", EXECUTE, Set.of(EXECUTE)),   // kalıcı (geri-alınamaz) silme — dedike + sensitive
        r("inventory.transfer", "certificates", "execute", Set.of(EXECUTE)),
        r("notes.read",         "certificates", VIEW),
        r("notes.crud",         "certificates", EDIT),

        // ── İletişim / Eskalasyon ─────────────────────────────────────────
        r("contacts.list",      "communication", VIEW),
        r("contacts.crud",      "communication", EDIT),

        // ── Yönetim (Takım & Kullanıcı) ───────────────────────────────────
        r("teams.list",         "management", VIEW),
        r("teams.update",       "management", EDIT),
        // Yalnız takımın haftalık e-posta anahtarları (Cuma hatırlatması + Pazartesi erişilebilirlik).
        // teams.update'ten AYRI: sıradan üye kendi takımının bu iki anahtarını çevirebilsin ama
        // ad/e-posta/aktiflik alanlarına dokunamasın diye. Uç ayrıca üyelik doğrular.
        r("teams.weekly_notifications", "management", EDIT),
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
        r("system_health.read",          "monitoring", VIEW),
        r("system_health.actions",       "monitoring", EXECUTE, Set.of(EXECUTE)),
        r("system_health.terminate",     "monitoring", EXECUTE, Set.of(EXECUTE)),   // kullanıcı oturumu sonlandırma (kick)
        r("system_health.scheduler_lock","monitoring", EXECUTE, Set.of(EXECUTE)),   // dağıtık-kilit force-release
        r("monitoring.read",       "monitoring", VIEW),
        r("monitoring.crud",       "monitoring", EDIT),
        r("monitoring.trigger",    "monitoring", EXECUTE),
        // Senaryo İzleme (k6) — keyfi kod çalıştırma → hassas; yalnız ADMIN + TEAM_ADMIN (PO). EDIT=CRUD, EXECUTE=çalıştır/test.
        new Resource("monitoring.scripted", "monitoring", List.of(EDIT, EXECUTE), Set.of(EDIT, EXECUTE)),
        r("domain.registration.view", "monitoring", VIEW),   // Domain Kaydı sekmesi (registrar/IANA/DNSSEC/IP/EPP)
        r("monitoring.group", "monitoring", VIEW),           // İzleme Grupları — görüntüleme (takım-scope)
        r("monitoring.group", "monitoring", EDIT),           // İzleme Grupları — yeniden adlandırma (takım-scope)
        // Şablon kütüphanesi. İKİ AYRI SATIR olması ZORUNLU, tek çok-eylemli Resource DEĞİL:
        // auditDefaults() `r.actions.contains(VIEW)` sonucunu putAll ile kaynağın TÜM eylemlerine
        // uyguluyor (bkz. :238-241, :246-249). Tek satırda List.of(VIEW, EDIT) yazsaydık salt-okunur
        // AUDIT rolü sessizce şablon DÜZENLEME yetkisi kazanırdı. Ayrı satırlarda VIEW=true,
        // EDIT=false doğru şekilde hesaplanır — monitoring.group'un yukarıdaki deseni de bu yüzden.
        r("monitoring.scripted_templates", "monitoring", VIEW),
        // EDIT hassas: şablon, başkalarının çalıştıracağı KOD'dur. (sensitive = matris onay
        // istemi; varsayılan değeri belirlemez — K2 gereği USER'a AÇIK gelir.)
        r("monitoring.scripted_templates", "monitoring", EDIT, Set.of(EDIT)),

        // ── Loglar & Raporlar ─────────────────────────────────────────────
        r("audit_log.read",     "logs", VIEW),
        r("weak_algo.read",     "logs", VIEW),

        // ── Haftalık Raporlar ─────────────────────────────────────────────
        r("weekly_reports.read",    "reports", VIEW),
        r("weekly_reports.crud",    "reports", EDIT),
        r("weekly_reports.approve", "reports", EXECUTE, Set.of(EXECUTE)),

        // ── Olay & Hata Geçmişi (SRE incident ledger) — kendi grubu (reports'tan ayrı) ──
        r("incidents.view",   "incidents", VIEW),
        r("incidents.manage", "incidents", EDIT),
        r("incidents.delete", "incidents", EXECUTE, Set.of(EXECUTE)),

        // ── Bakım Pencereleri (Maintenance Windows) ──
        r("maintenance.view",   "maintenance", VIEW),
        r("maintenance.manage", "maintenance", EDIT),
        r("maintenance.delete", "maintenance", EXECUTE, Set.of(EXECUTE)),

        // ── Sorun Bildirimleri (admin triyaj; kendi grubu) ──
        // NOT: anahtar tarihsel olarak "login-reports" — ekran 2026-08'de TÜM kaynakları
        // (LOGIN | CLIENT_ERROR | USER_REPORT) kapsayan "Sorun Bildirimleri"ne genelleştirildi.
        // Anahtar bilinçli korunuyor: rename mevcut grant'leri kaybettirir (bootstrap seed'i yeniden koşmaz).
        r("issues.login-reports", "issues", VIEW),   // listeleme/görüntüleme
        r("issues.login-reports", "issues", EDIT),   // durum değiştirme (İşleme Al / Çözümlendi / Yeniden Aç)
        // KALICI (geri-alınamaz) silme — DEDİKE + sensitive, inventory.purge emsali.
        // Durumu değiştirmek ile kaydı YOK ETMEK farklı yetkilerdir: ikincisi güvenlik
        // bildirimlerini de silebilir, o yüzden "raporları yönetsin ama kanıt silemesin"
        // ayrımı mümkün kalmalı. Varsayılan: yalnız ADMIN (diğer rollerin listesine EKLENMEZ).
        r("issues.login-reports.purge", "issues", EXECUTE, Set.of(EXECUTE)),

        // ── Yönetim Araçları (kritik) ─────────────────────────────────────
        r("diagnostics.run",        "tools", EXECUTE, Set.of(EXECUTE)),
        r("diagnostics.history",    "tools", VIEW),
        r("sql_playground.execute", "tools", EXECUTE, Set.of(EXECUTE)),
        r("guide_links.crud",       "tools", EDIT),

        // ── Manuel Tarama ─────────────────────────────────────────────────
        r("scheduler.run",          "monitoring", EXECUTE, Set.of(EXECUTE)),

        // ── Sistem Ayarları (varsayılan yalnız ADMIN; literal bootstrap 'admin' HER ZAMAN erişir) ──
        r("settings.smtp",     "settings", EDIT, Set.of(EDIT)),
        r("settings.ldap",     "settings", EDIT, Set.of(EDIT)),
        r("settings.general",  "settings", EDIT),
        r("settings.branding", "settings", EDIT),
        r("settings.database", "settings", VIEW),
        // Veri Saklama: süre değiştirmek ve elle temizlik geri alınamaz veri kaybı üretebilir → yalnız ADMIN.
        r("settings.retention", "settings", EDIT, Set.of(EDIT)),
        r("settings.secrets",  "settings", EXECUTE, Set.of(EXECUTE))
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
            "teams.list", "teams.update", "teams.weekly_notifications",
            "users.list", "users.crud", "users.actions",
            "alerts.read", "alerts.actions",
            "system_health.read",
            // audit_log.read: denetim kaydı sistem-geneli (tüm takımlar/kullanıcılar) → yalnız
            // global admin/AUDIT erişebilir (requireAuditAccess); TEAM_ADMIN'e verilmez.
            "weak_algo.read",
            // monitoring.crud/trigger: kendi takımı için keyword/ping izleme oluştur/düzenle/çalıştır
            // monitoring.scripted: PO/TEAM_ADMIN kendi takımı için k6 senaryosu yazar/çalıştırır (USER'a AÇILMAZ)
            "monitoring.read", "monitoring.crud", "monitoring.trigger", "monitoring.scripted", "domain.registration.view", "monitoring.group",
            "monitoring.scripted_templates",   // şablon kütüphanesi (USER'a da açık — K2)
            // Haftalık raporlar: takım yöneticisi okur/düzenler ve onaylayabilir;
            // tanılama geçmişini görür (canlı tarama admin-only kalır)
            "weekly_reports.read", "weekly_reports.crud", "weekly_reports.approve",
            "incidents.view", "incidents.manage", "incidents.delete",
            "maintenance.view", "maintenance.manage", "maintenance.delete",
            "diagnostics.history"
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
            // Kendi takımının haftalık e-posta anahtarlarını çevirebilir (uç üyelik doğrular);
            // teams.update VERİLMEZ — ad/e-posta/aktiflik yönetici alanı olarak kalır.
            "teams.weekly_notifications",
            "users.list",
            "thresholds.read",
            "alerts.read", "alerts.actions",
            "system_health.read",
            // monitoring.crud/trigger: USER kendi takımı için keyword/ping izleme oluşturur/düzenler/çalıştırır
            // (silme canManage ile TEAM_ADMIN/ADMIN'de; Port/DNS yazma requireAdmin ile admin-only kalır)
            "monitoring.read", "monitoring.crud", "monitoring.trigger", "domain.registration.view", "monitoring.group",
            // Şablon kütüphanesi: USER kendi TAKIMINA şablon yazar (K2).
            // Genel şablonu düzenlemek uçta requireAdmin ile ayrıca korunur.
            "monitoring.scripted_templates",
            // monitoring.scripted (edit + execute): USER kendi TAKIMININ sentetik monitörünü
            // yazar/düzenler ve elle koşturur. 2026-08-24'e kadar USER'a KAPALIYDI ve kullanıcı
            // bunu bir kusur olarak bildirdi: şablon yazabiliyor ama o şablondan monitör
            // kuramıyordu — yarım bir yetki.
            //
            // Riski KABUL EDİLDİ ve sınırları şöyle: k6 keyfi koddur ve pod üzerinde çalışır, ama
            // (a) takım izolasyonu AYRI ve zaten yerinde — `canOperateTeam` başka takımın
            // monitörüne SecurityException atar, (b) SİLME hâlâ TEAM_ADMIN/ADMIN'de
            // (`canDeleteRow` ayrıca isTeamAdmin ister), (c) script tarayıcısı (scanScriptOrError)
            // ve k6 zaman/kaynak tavanları herkese aynı şekilde uygulanır.
            // Her iki eylem de "sensitive" işaretli kalır: matriste kapatmak tek tık.
            "monitoring.scripted",
            // audit_log.read: sistem-geneli denetim → yalnız admin/AUDIT (requireAuditAccess)
            "weak_algo.read",
            // Haftalık raporlar: USER kendi takımının raporunu yazar/düzenler
            // (onay yetkisi yok — PO onayı servis tarafında orgRole ile ayrı)
            "weekly_reports.read", "weekly_reports.crud",
            // Olay geçmişi: USER kendi takımı için olay girer/düzenler (incidents.manage);
            // silme yetkisi (incidents.delete) yalnız TEAM_ADMIN/ADMIN'de
            "incidents.view", "incidents.manage",
            "maintenance.view"
        );
        for (Resource r : ALL) putAll(map, r, allowed.contains(r.key));
        for (Resource r : INTERNAL) putAll(map, r, false);
        return map;
    }

    private static Map<String, Map<String, Boolean>> auditDefaults() {
        var map = new java.util.LinkedHashMap<String, Map<String, Boolean>>();
        // System-wide read-only auditor. Sistem ayarları (SMTP/LDAP/secret/DB) AUDIT'e
        // otomatik AÇILMAZ — kimlik bilgisi/altyapı sırrı sızmasın (yalnız ADMIN veya grant).
        for (Resource r : ALL) {
            boolean isRead = r.actions.contains(VIEW) && !"settings".equals(r.group);
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
