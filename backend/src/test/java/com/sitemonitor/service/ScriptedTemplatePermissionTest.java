package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code monitoring.scripted_templates} izin anahtarının katalog sözleşmesi.
 *
 * <p><b>Bu testin asıl varlık sebebi bir tuzak:</b> {@code auditDefaults()} bir kaynağın
 * {@code actions.contains(VIEW)} sonucunu {@code putAll} ile o kaynağın TÜM eylemlerine
 * uyguluyor. Anahtar tek bir çok-eylemli {@code Resource(VIEW, EDIT)} olarak tanımlansaydı,
 * salt-okunur AUDIT rolü sessizce şablon DÜZENLEME yetkisi kazanırdı — kimsenin fark etmeyeceği
 * bir yetki yükseltmesi. İki ayrı tek-eylemli satır bunu engeller ve test onu pinler.
 */
class ScriptedTemplatePermissionTest {

    private static final String KEY = "monitoring.scripted_templates";

    private static Map<String, Boolean> actionsFor(String role) {
        return PermissionCatalog.defaultsFor(role).get(KEY);
    }

    @Test
    @DisplayName("Anahtar katalogda VAR ve VIEW+EDIT eylemlerini taşır (Permission Matrix'te görünür)")
    void keyExistsInCatalog() {
        // CLAUDE.md kuralı: koda giren her resource_key aynı değişiklikte kataloğa girer,
        // yoksa matriste görünmez ve grant bootstrap edilmez.
        assertThat(PermissionCatalog.ALL)
                .anyMatch(r -> KEY.equals(r.key) && r.actions.contains("view"))
                .anyMatch(r -> KEY.equals(r.key) && r.actions.contains("edit"));
    }

    @Test
    @DisplayName("TUZAK: AUDIT görüntüler ama DÜZENLEYEMEZ (tek çok-eylemli Resource olsaydı edit de açılırdı)")
    void auditGetsViewButNotEdit() {
        Map<String, Boolean> audit = actionsFor("AUDIT");
        assertThat(audit).isNotNull();
        assertThat(audit.get("view")).isTrue();
        assertThat(audit.get("edit")).isFalse();
    }

    @Test
    @DisplayName("K2: USER varsayılan olarak şablon yazabilir — monitoring.scripted'den AYRI ve daha açık")
    void userCanAuthorTemplatesByDefault() {
        Map<String, Boolean> user = actionsFor("USER");
        assertThat(user.get("view")).isTrue();
        assertThat(user.get("edit")).isTrue();

        // Ayrım kasıtlı: şablon YAZMAK kod ÇALIŞTIRMAK değildir. O şablondan monitör kurmak
        // hâlâ monitoring.scripted ister ve o USER'a KAPALI kalır.
        assertThat(PermissionCatalog.defaultsFor("USER").get("monitoring.scripted").get("edit")).isFalse();
    }

    @Test
    @DisplayName("TEAM_ADMIN ve ADMIN tam yetkili")
    void teamAdminAndAdminHaveFullAccess() {
        for (String role : new String[]{ "TEAM_ADMIN", "ADMIN" }) {
            Map<String, Boolean> m = actionsFor(role);
            assertThat(m.get("view")).as("%s view", role).isTrue();
            assertThat(m.get("edit")).as("%s edit", role).isTrue();
        }
    }

    @Test
    @DisplayName("EDIT hassas işaretli — matriste onay ister (varsayılan değeri belirlemez)")
    void editIsMarkedSensitive() {
        assertThat(PermissionCatalog.ALL)
                .filteredOn(r -> KEY.equals(r.key) && r.actions.contains("edit"))
                .allMatch(r -> r.sensitive.contains("edit"));
    }
}
