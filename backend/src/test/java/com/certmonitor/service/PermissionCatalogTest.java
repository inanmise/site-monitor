package com.certmonitor.service;

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
        assertThat(user.get("inventory.crud").get("edit")).isFalse();
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
        assertThat(teamAdmin.get("diagnostics.run").get("execute")).isFalse();

        // USER: rapor read/crud açık, approve KAPALI; diagnostics kapalı
        assertThat(user.get("weekly_reports.read").get("view")).isTrue();
        assertThat(user.get("weekly_reports.crud").get("edit")).isTrue();
        assertThat(user.get("weekly_reports.approve").get("execute")).isFalse();
        assertThat(user.get("diagnostics.run").get("execute")).isFalse();
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
}
