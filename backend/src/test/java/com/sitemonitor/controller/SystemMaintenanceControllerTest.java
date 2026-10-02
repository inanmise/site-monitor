package com.sitemonitor.controller;

import com.sitemonitor.config.WebConfig;
import com.sitemonitor.model.SystemMaintenanceWindow;
import com.sitemonitor.service.SystemMaintenanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sistem Bakım Modu uçları (2026-10-02): yönetim uçlarının TAMAMI yalnız GLOBAL yönetici (kapsamlı müdür, AUDIT, USER
 * 403); doğrulama hatası 400 + {@code field}; public durum ucu yalnız beyaz-listeli alanları taşır ve {@code no-store}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemMaintenanceControllerTest {

    @Mock SystemMaintenanceService service;
    SystemMaintenanceController controller;

    @BeforeEach
    void setUp() {
        controller = new SystemMaintenanceController(service);
        SystemMaintenanceWindow w = new SystemMaintenanceWindow();
        w.setId(1L);
        when(service.schedule(any(), any())).thenReturn(w);
        when(service.startNow(any(), any())).thenReturn(w);
        when(service.update(anyLong(), any(), any())).thenReturn(w);
        when(service.extend(anyLong(), any(), any())).thenReturn(w);
        when(service.endNow(anyLong(), any())).thenReturn(w);
        when(service.cancel(anyLong(), any())).thenReturn(w);
        when(service.toDto(any(SystemMaintenanceWindow.class))).thenReturn(Map.of("id", 1L));
        when(service.overview()).thenReturn(Map.of("server_now", "x"));
        when(service.history(anyInt(), anyInt())).thenReturn(Map.of("items", List.of()));
    }

    static MockHttpSession session(String role, boolean global) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "u-" + role);
        s.setAttribute("systemRole", role);
        if (!global) s.setAttribute("viewTeamIds", List.of(1L));
        return s;
    }

    @Test
    @DisplayName("global yönetici: tüm uçlar 200")
    void globalAdmin_ok() {
        MockHttpSession s = session("ADMIN", true);
        assertThat(controller.overview(s).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.history(1, 10, s).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.schedule(Map.of(), s).getBody()).containsEntry("success", true);
        assertThat(controller.startNow(Map.of(), s).getBody()).containsKey("server_now");
        assertThat(controller.update(1L, Map.of(), s).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.extend(1L, Map.of("minutes", 15), s).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.endNow(1L, s).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.cancel(1L, s).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("kapsamlı müdür (ADMIN + viewTeamIds), AUDIT, TEAM_ADMIN, USER → 403 (SecurityException) — okuma dahil")
    void nonGlobal_forbidden() {
        for (MockHttpSession s : List.of(session("ADMIN", false), session("AUDIT", true), session("TEAM_ADMIN", false),
                session("USER", false))) {
            assertThatThrownBy(() -> controller.overview(s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> controller.history(1, 10, s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> controller.detail(1L, s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> controller.schedule(Map.of(), s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> controller.startNow(Map.of(), s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> controller.update(1L, Map.of(), s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> controller.extend(1L, Map.of(), s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> controller.endNow(1L, s)).isInstanceOf(SecurityException.class);
            assertThatThrownBy(() -> controller.cancel(1L, s)).isInstanceOf(SecurityException.class);
        }
        verify(service, never()).schedule(any(), any());
        verify(service, never()).startNow(any(), any());
        verify(service, never()).overview();
    }

    @Test
    @DisplayName("alan hatası → 400 + field")
    void fieldError_400() {
        ResponseEntity<Map<String, Object>> r = controller.fieldError(
                new SystemMaintenanceService.FieldException("start_local", "Başlangıç geçmişte olamaz"));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(r.getBody()).containsEntry("field", "start_local").containsEntry("success", false);
    }

    @Test
    @DisplayName("public durum ucu oturumsuz çalışır ve servis bloğunu aynen taşır")
    void publicStatus() {
        when(service.publicStatus()).thenReturn(Map.of("state", "announced", "start_at", "2026-10-02T19:00:00Z"));
        assertThat(controller.publicStatus().getBody()).containsEntry("success", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) controller.publicStatus().getBody().get("data");
        assertThat(data).containsEntry("state", "announced");
    }

    @Test
    @DisplayName("public durum ucu paylaşımlı önbelleğe girmez (no-store) — bakım bilgisi bayat kalmaz")
    void publicStatus_noStore() throws Exception {
        var filter = new WebConfig().securityHeadersFilter();
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/public/system-maintenance"), res, new MockFilterChain());
        assertThat(res.getHeader("Cache-Control")).contains("no-store").doesNotContain("public");
    }
}
