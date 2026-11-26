package com.sitemonitor.controller;

import com.sitemonitor.service.GlobalSearchService;
import com.sitemonitor.service.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Komut paletinin olay kaydı kapsamı (2026-11-26, "Bul ve Raporla"): {@code incidents.view} yoksa olay araması YOK; varsa
 * olay listesiyle AYNI kapsam — global görüntüleyici tümü (null), takım kullanıcısı görüş + paylaşım takımları.
 */
class GlobalSearchIncidentScopeTest {

    private static MockHttpSession user(Long... teams) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "sre1");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(teams)));
        return s;
    }

    private static GlobalSearchController controller(PermissionService perms) {
        GlobalSearchController c = new GlobalSearchController(mock(GlobalSearchService.class));
        ReflectionTestUtils.setField(c, "permissionService", perms);
        return c;
    }

    @Test
    @DisplayName("incidents.view yok → null (olay araması yapılmaz); izin servisi yok (dilim testi) → null")
    void noPermissionNoIncidents() {
        PermissionService perms = mock(PermissionService.class);
        assertThat(controller(perms).incidentScope(user(1L))).isNull();
        assertThat(controller(null).incidentScope(user(1L))).isNull();
    }

    @Test
    @DisplayName("takım kullanıcısı: görüş + paylaşım takımları; global yönetici: tüm takımlar (null)")
    void scopeFollowsIncidentList() {
        PermissionService perms = mock(PermissionService.class);
        when(perms.allows(any(jakarta.servlet.http.HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        MockHttpSession u = user(3L);
        u.setAttribute(SessionScope.ATTR_GRANT_VIEW, new ArrayList<>(List.of(9L)));
        assertThat(controller(perms).incidentScope(u).teams()).containsExactlyInAnyOrder(3L, 9L);
        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("authenticated", Boolean.TRUE);
        admin.setAttribute("username", "admin");
        admin.setAttribute("systemRole", "ADMIN");
        assertThat(controller(perms).incidentScope(admin).teams()).isNull();
    }
}
