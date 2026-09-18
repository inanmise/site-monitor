package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionCatalogTest {

    @Test
    @DisplayName("defaultsFor: ADMIN tüm kaynaklara (ALL + INTERNAL) true verir")
    void adminGetsEverything() {
        Map<String, Map<String, Boolean>> admin = PermissionCatalog.defaultsFor("ADMIN");
        // ALL + INTERNAL içindeki her resource haritada olmalı ve tüm action'ları true
        for (PermissionCatalog.Resource r : PermissionCatalog.ALL) {
            assertThat(admin).containsKey(r.key);
            r.actions.forEach(a -> assertThat(admin.get(r.key).get(a)).as(r.key + "/" + a).isTrue());
        }
        for (PermissionCatalog.Resource r : PermissionCatalog.INTERNAL) {
            assertThat(admin).containsKey(r.key);
        }
    }

    @Test
    @DisplayName("defaultsFor: USER read-only + alerts.actions; CRUD ve internal kapalı")
    void userIsReadOnlyPlusAlertActions() {
        Map<String, Map<String, Boolean>> user = PermissionCatalog.defaultsFor("USER");
        assertThat(user.get("inventory.list").get("view")).isTrue();
        assertThat(user.get("inventory.crud").get("edit")).isTrue();    // 2026-09-18: "Domain Ekle" her seviyede (uç üyelik doğrular)
        assertThat(user.get("alerts.actions").get("execute")).isTrue();
        // Internal kaynaklar USER'da kapalı
        assertThat(user.get("permissions.manage").get("edit")).isFalse();
        assertThat(user.get("system.global_admin").get("execute")).isFalse();
    }

    @Test
    @DisplayName("defaultsFor: AUDIT yalnız VIEW action'larını alır, EDIT/EXECUTE kapalı")
    void auditIsSystemWideReadOnly() {
        Map<String, Map<String, Boolean>> audit = PermissionCatalog.defaultsFor("AUDIT");
        assertThat(audit.get("inventory.list").get("view")).isTrue();   // view → açık
        assertThat(audit.get("inventory.crud").get("edit")).isFalse();  // edit → kapalı
        assertThat(audit.get("monitoring.trigger").get("execute")).isFalse();
    }

    @Test
    @DisplayName("AUDIT SÜPÜRME: hiçbir kaynakta edit/execute YOK — salt-okunur rolün tanımı")
    void auditHasNoWriteActionAnywhere() {
        // Yukarıdaki test ÜÇ anahtarı noktasal kontrol ediyor; `notification.groups` tam da bu yüzden
        // gözden kaçtı. auditDefaults() `actions.contains(VIEW)` sonucunu kaynağın TÜM eylemlerine
        // uyguluyor, yani tek satırda List.of(VIEW, EDIT) yazılan HER kaynak AUDIT'e sessizce yazma
        // yetkisi verir. Kural noktasal değil SÜPÜRME olarak pinlenir — dördüncü tekrarı keser.
        Map<String, Map<String, Boolean>> audit = PermissionCatalog.defaultsFor("AUDIT");
        for (PermissionCatalog.Resource r : PermissionCatalog.ALL) {
            Map<String, Boolean> actions = audit.get(r.key);
            if (actions == null) continue;
            assertThat(actions.get(PermissionCatalog.EDIT))
                    .as("AUDIT salt-okunurdur ama %s/edit AÇIK", r.key)
                    .isNotEqualTo(true);
            assertThat(actions.get(PermissionCatalog.EXECUTE))
                    .as("AUDIT salt-okunurdur ama %s/execute AÇIK", r.key)
                    .isNotEqualTo(true);
        }
    }

    @Test
    @DisplayName("notification.groups: AUDIT görür ama DÜZENLEYEMEZ; USER/TEAM_ADMIN düzenleyebilir")
    void notificationGroupsSplitPreservesTeamAccess() {
        // Bölme yalnız AUDIT'i etkilemeli — takımın kendi nöbetçi listesini yönetmesi (K2) korunur.
        Map<String, Map<String, Boolean>> audit = PermissionCatalog.defaultsFor("AUDIT");
        assertThat(audit.get("notification.groups").get("view")).isTrue();
        assertThat(audit.get("notification.groups").get("edit")).isFalse();

        for (String role : new String[]{"USER", "TEAM_ADMIN", "ADMIN"}) {
            Map<String, Map<String, Boolean>> m = PermissionCatalog.defaultsFor(role);
            assertThat(m.get("notification.groups").get("view")).as(role + "/view").isTrue();
            assertThat(m.get("notification.groups").get("edit")).as(role + "/edit").isTrue();
        }
    }

    @Test
    @DisplayName("defaultsFor: bilinmeyen rol → boş map (fail-safe), null rol → NPE değil boş davranış")
    void unknownRoleEmpty() {
        assertThat(PermissionCatalog.defaultsFor("WHATEVER")).isEmpty();
        // null switch → NullPointerException olmasın diye guard yok; gerçek çağıranlar
        // sabit rol dizisini kullanır. Yine de bilinmeyen rolün boş dönmesi kritik.
    }

    @Test
    @DisplayName("Yeni modüller: weekly_reports + diagnostics matriste ve rol varsayılanları doğru")
    void newModulesDefaults() {
        // Katalogda mevcutlar
        assertThat(PermissionCatalog.ALL.stream().map(r -> r.key))
                .contains("weekly_reports.read", "weekly_reports.crud", "weekly_reports.approve",
                          "diagnostics.run", "diagnostics.history");

        Map<String, Map<String, Boolean>> admin = PermissionCatalog.defaultsFor("ADMIN");
        Map<String, Map<String, Boolean>> teamAdmin = PermissionCatalog.defaultsFor("TEAM_ADMIN");
        Map<String, Map<String, Boolean>> user = PermissionCatalog.defaultsFor("USER");
        Map<String, Map<String, Boolean>> audit = PermissionCatalog.defaultsFor("AUDIT");

        // ADMIN: hepsi açık
        assertThat(admin.get("weekly_reports.approve").get("execute")).isTrue();
        assertThat(admin.get("diagnostics.run").get("execute")).isTrue();

        // TEAM_ADMIN: rapor read/crud/approve açık; diagnostics geçmiş açık, canlı tarama kapalı
        assertThat(teamAdmin.get("weekly_reports.crud").get("edit")).isTrue();
        assertThat(teamAdmin.get("weekly_reports.approve").get("execute")).isTrue();
        assertThat(teamAdmin.get("diagnostics.history").get("view")).isTrue();
        assertThat(teamAdmin.get("diagnostics.run").get("execute")).isTrue();    // 2026-09-11: kendi takımının alanları (uç kapsamlar)

        // USER: rapor read/crud açık, approve KAPALI; diagnostics kapalı
        assertThat(user.get("weekly_reports.read").get("view")).isTrue();
        assertThat(user.get("weekly_reports.crud").get("edit")).isTrue();
        assertThat(user.get("weekly_reports.approve").get("execute")).isFalse();
        assertThat(user.get("diagnostics.run").get("execute")).isTrue();         // 2026-09-11: kendi takımının alanları (uç kapsamlar)
        assertThat(user.get("diagnostics.history").get("view")).isFalse();

        // AUDIT: yalnız VIEW açık (read + history); crud/approve/run kapalı
        assertThat(audit.get("weekly_reports.read").get("view")).isTrue();
        assertThat(audit.get("weekly_reports.crud").get("edit")).isFalse();
        assertThat(audit.get("diagnostics.history").get("view")).isTrue();
        assertThat(audit.get("diagnostics.run").get("execute")).isFalse();
    }

    @Test
    @DisplayName("Tutarlılık: ALL'daki her resource, 4 rol default'unun en az birinde tanımlı")
    void everyResourceCoveredByDefaults() {
        // ALL'a yeni bir resource eklenip rol default'larına eklenmezse, non-ADMIN
        // için sessizce false olur — ADMIN her şeyi kapsadığından bu test onu yakalar.
        Map<String, Map<String, Boolean>> admin = PermissionCatalog.defaultsFor("ADMIN");
        for (PermissionCatalog.Resource r : PermissionCatalog.ALL) {
            assertThat(admin).as("ADMIN default '%s' resource'unu içermeli", r.key).containsKey(r.key);
        }
    }

    @Test
    @DisplayName("USER kendi takiminin sentetik monitorunu yazar/kosturur; AUDIT yazamaz")
    void userCanManageOwnTeamScriptedMonitors() {
        // 2026-08-24 politika degisikligi: USER sablon yazabiliyor ama o sablondan monitor
        // KURAMIYORDU — yarim bir yetkiydi ve kullanici bunu kusur olarak bildirdi.
        // Risk kabul edildi ve sinirlari ayri katmanlarda duruyor: takim izolasyonu
        // canOperateTeam'de, SILME hala TEAM_ADMIN/ADMIN'de, script tarayicisi herkese ayni.
        Map<String, Map<String, Boolean>> user = PermissionCatalog.defaultsFor("USER");
        assertThat(user.get("monitoring.scripted").get("edit")).isTrue();
        assertThat(user.get("monitoring.scripted").get("execute")).isTrue();

        // Salt-okunur AUDIT rolu bundan ETKILENMEZ.
        Map<String, Map<String, Boolean>> audit = PermissionCatalog.defaultsFor("AUDIT");
        assertThat(audit.get("monitoring.scripted").get("edit")).isFalse();
        assertThat(audit.get("monitoring.scripted").get("execute")).isFalse();
    }
}
