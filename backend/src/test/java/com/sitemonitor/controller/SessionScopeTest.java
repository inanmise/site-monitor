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
}
