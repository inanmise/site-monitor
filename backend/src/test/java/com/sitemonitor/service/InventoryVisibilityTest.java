package com.sitemonitor.service;

import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.model.CertificateInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Org geneli envanter görünürlüğü (2026-09-26) — kapının kendisi ({@link InventoryVisibility}) ve yazma
 * kuralının tek kaynağı ({@link SessionScope#canWriteInventory} / {@link SessionScope#inventoryWriteTest}).
 */
class InventoryVisibilityTest {

    private AppSettingsService appSettings;
    private PermissionService permissions;
    private InventoryVisibility visibility;

    @BeforeEach
    void setUp() {
        appSettings = mock(AppSettingsService.class);
        permissions = mock(PermissionService.class);
        visibility = new InventoryVisibility(appSettings, permissions);
        when(appSettings.getBoolean(InventoryVisibility.SETTING_KEY, true)).thenReturn(true);
        when(permissions.allows(any(jakarta.servlet.http.HttpSession.class), eq("inventory.list"), eq("view"))).thenReturn(true);
    }

    private static MockHttpSession session(String role, List<Long> view, List<Long> manage, List<Long> member) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("systemRole", role);
        if (view != null) s.setAttribute("viewTeamIds", new ArrayList<>(view));
        if (manage != null) s.setAttribute("manageTeamIds", new ArrayList<>(manage));
        s.setAttribute("memberTeamIds", new ArrayList<>(member));
        return s;
    }

    private static CertificateInventory inv(Long team, Long ugTeam, String deletedAt) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain("x.example.com"); i.setTeamId(team); i.setUgTeamId(ugTeam); i.setDeletedAt(deletedAt);
        return i;
    }

    @Test
    @DisplayName("ayar anahtarı + varsayılan AÇIK ile canlı okunur (her çağrıda; önbellek yok)")
    void enabled_readsSettingLiveWithDefaultTrue() {
        assertThat(visibility.enabled()).isTrue();
        when(appSettings.getBoolean(InventoryVisibility.SETTING_KEY, true)).thenReturn(false);
        assertThat(visibility.enabled()).isFalse();
        verify(appSettings, org.mockito.Mockito.times(2)).getBoolean("site.monitor.inventory.visible-to-all", true);
    }

    @Test
    @DisplayName("scope: yalnız 'all' (büyük/küçük harf, boşluk toleranslı) + ayar açık + inventory.list izni org geneli olur")
    void wantsAll_rules() {
        MockHttpSession user = session("USER", List.of(5L), List.of(), List.of(5L));
        assertThat(visibility.wantsAll(user, "all")).isTrue();
        assertThat(visibility.wantsAll(user, " ALL ")).isTrue();
        assertThat(visibility.wantsAll(user, "mine")).isFalse();
        assertThat(visibility.wantsAll(user, "")).isFalse();
        assertThat(visibility.wantsAll(user, null)).isFalse();
        assertThat(visibility.wantsAll(user, "everything")).isFalse();
        assertThat(visibility.effectiveScope(user, "all")).isEqualTo("all");
        assertThat(visibility.effectiveScope(user, "bogus")).isEqualTo("mine");

        when(permissions.allows(any(jakarta.servlet.http.HttpSession.class), eq("inventory.list"), eq("view"))).thenReturn(false);
        assertThat(visibility.wantsAll(user, "all")).as("izin yoksa genişleme yok").isFalse();

        when(permissions.allows(any(jakarta.servlet.http.HttpSession.class), eq("inventory.list"), eq("view"))).thenReturn(true);
        when(appSettings.getBoolean(InventoryVisibility.SETTING_KEY, true)).thenReturn(false);
        assertThat(visibility.wantsAll(user, "all")).as("ayar kapalıyken genişleme yok").isFalse();
        assertThat(visibility.wantsAll(null, "all")).isFalse();
    }

    @Test
    @DisplayName("tek kayıt: kendi SY/UG takımı her zaman; başka takım yalnız ayar açık + silinmemişse")
    void canRead_ownUgForeignDeleted() {
        MockHttpSession user = session("USER", List.of(5L), List.of(), List.of(5L));
        assertThat(visibility.canRead(user, inv(5L, null, null))).isTrue();
        assertThat(visibility.canRead(user, inv(9L, 5L, null))).as("UG takımı").isTrue();
        assertThat(visibility.canRead(user, inv(9L, null, null))).as("başka takım, ayar açık").isTrue();
        assertThat(visibility.canRead(user, inv(9L, null, "2026-09-01T00:00:00"))).as("silinmiş yabancı").isFalse();
        assertThat(visibility.readableOrgWide(user, inv(5L, null, "2026-09-01T00:00:00"))).isFalse();
        assertThat(visibility.canRead(user, null)).isFalse();

        when(appSettings.getBoolean(InventoryVisibility.SETTING_KEY, true)).thenReturn(false);
        assertThat(visibility.canRead(user, inv(9L, null, null))).as("ayar kapalı").isFalse();
        assertThat(visibility.canRead(user, inv(5L, null, null))).as("kendi kaydı ayardan bağımsız").isTrue();
    }

    @Test
    @DisplayName("yazma kuralı: USER üyesi olduğu takım; TEAM_ADMIN / kapsamlı müdür yönetim kapsamı; global admin hepsi; AUDIT hiçbiri")
    void writeRule_perRole() {
        MockHttpSession user = session("USER", List.of(5L), List.of(), List.of(5L));
        MockHttpSession teamAdmin = session("TEAM_ADMIN", List.of(5L, 7L), List.of(5L, 7L), List.of(5L));
        MockHttpSession scopedAdmin = session("ADMIN", List.of(5L, 6L), List.of(5L, 6L), List.of(5L));
        MockHttpSession globalAdmin = session("ADMIN", null, null, List.of());
        MockHttpSession audit = session("AUDIT", null, List.of(), List.of());

        assertThat(SessionScope.canWriteInventory(user, 5L)).isTrue();
        assertThat(SessionScope.canWriteInventory(user, 9L)).isFalse();
        assertThat(SessionScope.canWriteInventory(teamAdmin, 7L)).isTrue();
        assertThat(SessionScope.canWriteInventory(teamAdmin, 9L)).isFalse();
        assertThat(SessionScope.canWriteInventory(scopedAdmin, 6L)).as("ast takımı").isTrue();
        assertThat(SessionScope.canWriteInventory(scopedAdmin, 9L)).as("kapsamlı müdür GLOBAL DEĞİL").isFalse();
        assertThat(SessionScope.canWriteInventory(globalAdmin, 9L)).isTrue();
        assertThat(SessionScope.canWriteInventory(globalAdmin, null)).isTrue();
        assertThat(SessionScope.canWriteInventory(audit, 5L)).isFalse();
        assertThat(SessionScope.canWriteInventory(user, null)).as("takımsız kayıt yalnız global admin").isFalse();
    }

    @Test
    @DisplayName("istek başına küme (inventoryWriteTest) tekil kuralla BİREBİR aynı karar verir")
    void writeTest_matchesSingleRule() {
        List<MockHttpSession> sessions = List.of(
                session("USER", List.of(5L), List.of(), List.of(5L, 8L)),
                session("TEAM_ADMIN", List.of(5L, 7L), List.of(5L, 7L), List.of(5L)),
                session("ADMIN", List.of(5L, 6L), List.of(5L, 6L), List.of(5L)),
                session("ADMIN", null, null, List.of()),
                session("AUDIT", null, List.of(), List.of()));
        java.util.ArrayList<Long> teams = new java.util.ArrayList<>(List.of(1L, 5L, 6L, 7L, 8L, 9L));
        teams.add(null);
        for (MockHttpSession s : sessions) {
            Predicate<Long> once = SessionScope.inventoryWriteTest(s);
            for (Long t : teams) {
                assertThat(once.test(t)).as(s.getAttribute("systemRole") + " / takım " + t)
                        .isEqualTo(SessionScope.canWriteInventory(s, t));
            }
        }
    }

    @Test
    @DisplayName("ayar katalogda ve GLOBAL_ONLY: takım kapsamlı müdür kurum geneli görünürlüğü değiştiremez")
    void setting_isCatalogued_andGlobalOnly() {
        assertThat(AppSettingsCatalog.byKey(InventoryVisibility.SETTING_KEY)).isNotNull();
        assertThat(AppSettingsCatalog.byKey(InventoryVisibility.SETTING_KEY).type()).isEqualTo(AppSettingsCatalog.Type.BOOL);
        assertThat(AppSettingsCatalog.isGlobalOnly(InventoryVisibility.SETTING_KEY)).isTrue();
    }
}
