package com.sitemonitor.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionScope (Faz 3b takım-kapsamı yardımcıları) için birim testleri.
 * Yetki/scope mantığının kritikliği nedeniyle doğrudan ve fail-closed davranışı doğrulanır.
 */
class SessionScopeTest {

    private static MockHttpSession session(String role, List<Long> view, List<Long> manage) {
        MockHttpSession s = new MockHttpSession();
        if (role != null) s.setAttribute("systemRole", role);
        if (view != null) s.setAttribute("viewTeamIds", new java.util.ArrayList<>(view));
        if (manage != null) s.setAttribute("manageTeamIds", new java.util.ArrayList<>(manage));
        return s;
    }

    @Test
    @DisplayName("null session → fail-closed (hepsi false/null)")
    void nullSession_failClosed() {
        assertThat(SessionScope.viewTeamIds(null)).isNull();
        assertThat(SessionScope.manageTeamIds(null)).isNull();
        assertThat(SessionScope.isGlobalAdmin(null)).isFalse();
        assertThat(SessionScope.isGlobalViewer(null)).isFalse();
        assertThat(SessionScope.canManage(null, 1L)).isFalse();
    }

    @Test
    @DisplayName("global admin: viewTeamIds null + ADMIN → isGlobalAdmin/Viewer true, her takımı yönetir")
    void globalAdmin() {
        MockHttpSession s = session("ADMIN", null, null);
        assertThat(SessionScope.viewTeamIds(s)).isNull();
        assertThat(SessionScope.isGlobalAdmin(s)).isTrue();
        assertThat(SessionScope.isGlobalViewer(s)).isTrue();
        assertThat(SessionScope.canManage(s, 1L)).isTrue();
        assertThat(SessionScope.canManage(s, 999L)).isTrue();
    }

    @Test
    @DisplayName("scoped müdür: ADMIN + viewTeamIds dolu → global DEĞİL, yönetim boş → canManage false")
    void scopedManager_readOnly() {
        MockHttpSession s = session("ADMIN", List.of(1L, 2L), List.of());
        assertThat(SessionScope.isGlobalAdmin(s)).isFalse();   // viewTeamIds null değil
        assertThat(SessionScope.isGlobalViewer(s)).isFalse();
        assertThat(SessionScope.viewTeamIds(s)).containsExactly(1L, 2L);
        assertThat(SessionScope.canManage(s, 1L)).isFalse();   // manage boş → salt görüntüleme
    }

    @Test
    @DisplayName("AUDIT: viewTeamIds null → global viewer ama global admin değil")
    void audit_globalViewerNotAdmin() {
        MockHttpSession s = session("AUDIT", null, null);
        assertThat(SessionScope.isGlobalViewer(s)).isTrue();
        assertThat(SessionScope.isGlobalAdmin(s)).isFalse();
        assertThat(SessionScope.canManage(s, 1L)).isFalse();
    }

    @Test
    @DisplayName("USER: viewTeamIds null OLSA BİLE rol kontrolü nedeniyle global viewer değil (defence-in-depth)")
    void user_neverGlobal() {
        MockHttpSession s = session("USER", null, null);
        assertThat(SessionScope.isGlobalAdmin(s)).isFalse();
        assertThat(SessionScope.isGlobalViewer(s)).isFalse();
        assertThat(SessionScope.canManage(s, 1L)).isFalse();
    }

    @Test
    @DisplayName("PO/TEAM_ADMIN: manageTeamIds yalnız kapsamındaki takımları yönetir")
    void teamAdmin_scopedManage() {
        MockHttpSession s = session("TEAM_ADMIN", List.of(4L, 5L), List.of(4L, 5L));
        assertThat(SessionScope.isGlobalAdmin(s)).isFalse();
        assertThat(SessionScope.canManage(s, 4L)).isTrue();
        assertThat(SessionScope.canManage(s, 5L)).isTrue();
        assertThat(SessionScope.canManage(s, 9L)).isFalse();
        assertThat(SessionScope.canManage(s, null)).isFalse();
    }

    // ── Kod incelemesi 2026-09-09: ayar yüzeyleri kapsamlı müdüre kapalı ──────

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("requireNotScopedAdmin: global ADMIN ve diğer roller geçer; kapsamlı ADMIN (müdür) 403")
    void requireNotScopedAdmin_rejectsOnlyScopedAdmin() {
        org.springframework.mock.web.MockHttpSession global = new org.springframework.mock.web.MockHttpSession();
        global.setAttribute("systemRole", "ADMIN");
        SessionScope.requireNotScopedAdmin(global, "settings.smtp");   // istisna yok

        org.springframework.mock.web.MockHttpSession user = new org.springframework.mock.web.MockHttpSession();
        user.setAttribute("systemRole", "USER");
        user.setAttribute("viewTeamIds", new java.util.ArrayList<>(java.util.List.of(2L)));
        SessionScope.requireNotScopedAdmin(user, "settings.smtp");     // matris ayrıca sorar; bu kapı rolü engellemez

        org.springframework.mock.web.MockHttpSession scoped = new org.springframework.mock.web.MockHttpSession();
        scoped.setAttribute("systemRole", "ADMIN");
        scoped.setAttribute("viewTeamIds", new java.util.ArrayList<>(java.util.List.of(2L)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SessionScope.requireNotScopedAdmin(scoped, "settings.smtp"))
                .isInstanceOf(SecurityException.class);
    }
    @Test
    @DisplayName("7/24 operatörü (2026-10-04): izleme OKUMA yardımcıları genişler; canView/isGlobalViewer/viewTeamIds ve yazma kapıları DEĞİŞMEZ")
    void nocOperatorWidensOnlyMonitoringRead() {
        MockHttpSession op = session("USER", List.of(1L), List.of());
        op.setAttribute("memberTeamIds", new java.util.ArrayList<>(List.of(1L)));
        op.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        op.setAttribute(SessionScope.ATTR_NOC_TEAM_IDS, new java.util.ArrayList<>(List.of(1L)));

        assertThat(SessionScope.isNocOperator(op)).isTrue();
        assertThat(SessionScope.nocTeamIds(op)).containsExactly(1L);
        assertThat(SessionScope.seesAllMonitoring(op)).isTrue();
        assertThat(SessionScope.canViewMonitoring(op, 99L)).isTrue();
        assertThat(SessionScope.monitoringViewTeamIds(op)).isNull();
        // Yönetim alanlarının kullandığı yardımcılar DEĞİŞMEZ
        assertThat(SessionScope.canView(op, 99L)).isFalse();
        assertThat(SessionScope.isGlobalViewer(op)).isFalse();
        assertThat(SessionScope.viewTeamIds(op)).containsExactly(1L);
        assertThat(SessionScope.isGlobalAdmin(op)).isFalse();
        // Yazma kapıları operatörü TANIMAZ
        assertThat(SessionScope.canManage(op, 99L)).isFalse();
        assertThat(SessionScope.canOperateTeam(op, 99L)).isFalse();
        assertThat(SessionScope.canWriteInventory(op, 99L)).isFalse();
        assertThat(SessionScope.inventoryWriteTest(op).test(99L)).isFalse();
        assertThat(SessionScope.canOperateTeam(op, 1L)).isTrue();   // kendi takımı aynen

        // Kapsamlı müdür operatör olsa da global yönetici SAYILMAZ
        MockHttpSession mudur = session("ADMIN", List.of(1L), List.of(1L));
        mudur.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        assertThat(SessionScope.isGlobalAdmin(mudur)).isFalse();
        assertThat(SessionScope.isScopedAdmin(mudur)).isTrue();
        assertThat(SessionScope.canManage(mudur, 99L)).isFalse();
        assertThat(SessionScope.canViewMonitoring(mudur, 99L)).isTrue();

        // Bayrak yoksa / Boolean değilse: hiçbir genişleme yok
        MockHttpSession plain = session("USER", List.of(1L), List.of());
        assertThat(SessionScope.isNocOperator(plain)).isFalse();
        assertThat(SessionScope.nocTeamIds(plain)).isEmpty();
        assertThat(SessionScope.seesAllMonitoring(plain)).isFalse();
        assertThat(SessionScope.canViewMonitoring(plain, 99L)).isFalse();
        assertThat(SessionScope.canViewMonitoring(plain, 1L)).isTrue();
        assertThat(SessionScope.monitoringViewTeamIds(plain)).containsExactly(1L);
        plain.setAttribute(SessionScope.ATTR_NOC_OPERATOR, "true");
        assertThat(SessionScope.isNocOperator(plain)).isFalse();
        assertThat(SessionScope.isNocOperator(null)).isFalse();
        assertThat(SessionScope.seesAllMonitoring(null)).isFalse();
    }
}
