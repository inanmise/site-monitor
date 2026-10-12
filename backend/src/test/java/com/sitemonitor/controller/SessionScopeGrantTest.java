package com.sitemonitor.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Takım erişim paylaşımı (2026-10-12) — kapsam yardımcıları: okuma (izleme okuma yardımcıları + görüş kümesi), çalıştırma,
 * düzenleme (izleme / envanter yazma), silme; yönetim kapıları (canView, canManage, viewTeamIds, manageTeamIds) AYNEN.
 * 7/24 operatör genişlemesi bozulmaz.
 */
class SessionScopeGrantTest {

    private static MockHttpSession user(long team) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", team);
        s.setAttribute("viewTeamIds", List.of(team));
        s.setAttribute("memberTeamIds", List.of(team));
        s.setAttribute("manageTeamIds", List.of());
        return s;
    }

    private static void grants(MockHttpSession s, List<Long> view, List<Long> exec, List<Long> edit, List<Long> del) {
        s.setAttribute(SessionScope.ATTR_GRANT_VIEW, view);
        s.setAttribute(SessionScope.ATTR_GRANT_EXECUTE, exec);
        s.setAttribute(SessionScope.ATTR_GRANT_EDIT, edit);
        s.setAttribute(SessionScope.ATTR_GRANT_DELETE, del);
    }

    @Test
    @DisplayName("paylaşım yokken davranış AYNI (kendi takımı)")
    void noGrantsUnchanged() {
        MockHttpSession s = user(1L);
        assertThat(SessionScope.canViewMonitoring(s, 2L)).isFalse();
        assertThat(SessionScope.monitoringViewTeamIds(s)).containsExactly(1L);
        assertThat(SessionScope.viewTeamIdsWithGrants(s)).containsExactly(1L);
        assertThat(SessionScope.canExecuteTeam(s, 1L)).isTrue();
        assertThat(SessionScope.canExecuteTeam(s, 2L)).isFalse();
        assertThat(SessionScope.canDeleteTeamResource(s, 1L)).isFalse();   // üye silemez (yönetici değil)
    }

    @Test
    @DisplayName("VIEW: okur; çalıştıramaz / düzenleyemez / silemez; canView / viewTeamIds değişmez")
    void viewOnly() {
        MockHttpSession s = user(1L);
        grants(s, List.of(2L), List.of(), List.of(), List.of());
        assertThat(SessionScope.canViewMonitoring(s, 2L)).isTrue();
        assertThat(SessionScope.canViewWithGrants(s, 2L)).isTrue();
        assertThat(SessionScope.monitoringViewTeamIds(s)).containsExactly(1L, 2L);
        assertThat(SessionScope.viewTeamIdsWithGrants(s)).containsExactly(1L, 2L);
        assertThat(SessionScope.canView(s, 2L)).isFalse();
        assertThat(SessionScope.viewTeamIds(s)).containsExactly(1L);
        assertThat(SessionScope.canExecuteTeam(s, 2L)).isFalse();
        assertThat(SessionScope.canOperateTeam(s, 2L)).isFalse();
        assertThat(SessionScope.canWriteInventory(s, 2L)).isFalse();
        assertThat(SessionScope.inventoryWriteTest(s).test(2L)).isFalse();
        assertThat(SessionScope.canDeleteTeamResource(s, 2L)).isFalse();
    }

    @Test
    @DisplayName("EXECUTE: çalıştırır; düzenleyemez")
    void execute() {
        MockHttpSession s = user(1L);
        grants(s, List.of(2L), List.of(2L), List.of(), List.of());
        assertThat(SessionScope.canExecuteTeam(s, 2L)).isTrue();
        assertThat(SessionScope.grantExecuteTeamIds(s)).containsExactly(2L);
        assertThat(SessionScope.canOperateTeam(s, 2L)).isFalse();
        assertThat(SessionScope.canWriteInventory(s, 2L)).isFalse();
    }

    @Test
    @DisplayName("EDIT: izleme / envanter yazar (+ çalıştırır); silemez; yönetemez")
    void edit() {
        MockHttpSession s = user(1L);
        grants(s, List.of(2L), List.of(2L), List.of(2L), List.of());
        assertThat(SessionScope.canOperateTeam(s, 2L)).isTrue();
        assertThat(SessionScope.canExecuteTeam(s, 2L)).isTrue();
        assertThat(SessionScope.canWriteInventory(s, 2L)).isTrue();
        assertThat(SessionScope.inventoryWriteTest(s).test(2L)).isTrue();
        assertThat(SessionScope.canDeleteTeamResource(s, 2L)).isFalse();
        assertThat(SessionScope.canManage(s, 2L)).isFalse();
    }

    @Test
    @DisplayName("DELETE: kaydı siler; canManage (yönetim) yine KAPALI")
    void delete() {
        MockHttpSession s = user(1L);
        grants(s, List.of(2L), List.of(2L), List.of(2L), List.of(2L));
        assertThat(SessionScope.canDeleteTeamResource(s, 2L)).isTrue();
        assertThat(SessionScope.canManage(s, 2L)).isFalse();
        assertThat(SessionScope.manageTeamIds(s)).isEmpty();
    }

    @Test
    @DisplayName("7/24 operatörü: izleme okuma tümü (null) — paylaşım bunu daraltmaz; görüş kümesi + paylaşım ayrı yardımcıda")
    void nocOperatorUnaffected() {
        MockHttpSession s = user(1L);
        s.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        grants(s, List.of(2L), List.of(), List.of(), List.of());
        assertThat(SessionScope.monitoringViewTeamIds(s)).isNull();
        assertThat(SessionScope.canViewMonitoring(s, 9L)).isTrue();
        assertThat(SessionScope.viewTeamIdsWithGrants(s)).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("Kendi kapsamı (takım değişimi): EDIT paylaşımı canOperateTeam'i açar, canOperateTeamOwn'u AÇMAZ")
    void ownScopeIgnoresGrants() {
        MockHttpSession s = user(1L);
        grants(s, List.of(2L), List.of(2L), List.of(2L), List.of(2L));
        assertThat(SessionScope.canOperateTeam(s, 2L)).isTrue();
        assertThat(SessionScope.canOperateTeamOwn(s, 2L)).isFalse();
        assertThat(SessionScope.canOperateTeamOwn(s, 1L)).isTrue();
        assertThat(SessionScope.canWriteInventory(s, 2L)).isTrue();
        assertThat(SessionScope.canWriteInventoryOwn(s, 2L)).isFalse();
        assertThat(SessionScope.grantedEdit(s, 2L)).isTrue();
        assertThat(SessionScope.grantedEdit(s, 1L)).isFalse();
    }

    @Test
    @DisplayName("Kişi listeleri (nöbet / 7/24 üyeleri): paylaşım açmaz; 7/24 operatörü okur")
    void peopleReadsIgnoreGrants() {
        MockHttpSession s = user(1L);
        grants(s, List.of(2L), List.of(), List.of(), List.of());
        assertThat(SessionScope.canViewMonitoring(s, 2L)).isTrue();
        assertThat(SessionScope.canViewMonitoringOwn(s, 2L)).isFalse();
        assertThat(SessionScope.canViewMonitoringOwn(s, 1L)).isTrue();
        s.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        assertThat(SessionScope.canViewMonitoringOwn(s, 2L)).isTrue();
    }

    @Test
    @DisplayName("viewableOnlyByGrant: yalnız paylaşımla görülen takımda true; kendi takımında / paylaşımsız false")
    void viewableOnlyByGrant() {
        MockHttpSession s = user(1L);
        assertThat(SessionScope.viewableOnlyByGrant(s, 2L)).isFalse();
        grants(s, List.of(1L, 2L), List.of(), List.of(), List.of());
        assertThat(SessionScope.viewableOnlyByGrant(s, 2L)).isTrue();
        assertThat(SessionScope.viewableOnlyByGrant(s, 1L)).isFalse();   // zaten kendi takımı
        assertThat(SessionScope.viewableOnlyByGrant(s, null)).isFalse();
    }

    @Test
    @DisplayName("viewTeamIdsWithGrants: paylaşımlı listede contains(null) güvenli (List.copyOf NPE'si yok)")
    void grantScopeContainsNullSafe() {
        MockHttpSession s = user(1L);
        grants(s, List.of(2L), List.of(), List.of(), List.of());
        assertThat(SessionScope.viewTeamIdsWithGrants(s).contains(null)).isFalse();
    }

    @Test
    @DisplayName("global görüntüleyici: null kapsam korunur")
    void globalViewerUnchanged() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("systemRole", "AUDIT");
        grants(s, List.of(2L), List.of(), List.of(), List.of());
        assertThat(SessionScope.viewTeamIdsWithGrants(s)).isNull();
        assertThat(SessionScope.monitoringViewTeamIds(s)).isNull();
    }
}
