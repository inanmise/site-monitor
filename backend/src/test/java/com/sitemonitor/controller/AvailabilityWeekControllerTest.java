package com.sitemonitor.controller;

import com.sitemonitor.service.AvailabilityWeekService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kişisel Panom "7 günlük erişilebilirlik" ucu (2026-11-14): kapı {@code monitoring.read/view} (yoksa 403, servis çağrılmaz),
 * kapsam izleme OKUMA kapsamı (görüş takımları + erişim paylaşımı + 7/24 operatörü; takımsız satır yalnız tümünü görene),
 * okuma hatası 503 (asla sıfır / %100).
 */
@WebMvcTest(AvailabilityWeekController.class)
class AvailabilityWeekControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean AvailabilityWeekService availabilityWeekService;
    @MockitoBean com.sitemonitor.service.PermissionService permissionService;
    @MockitoBean com.sitemonitor.service.AuditService auditService;

    private static MockHttpSession session(String role, List<Long> viewTeams) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        if (viewTeams != null) s.setAttribute("viewTeamIds", viewTeams);
        return s;
    }

    private static Map<String, Object> week() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", 7); m.put("target_pct", 99.9); m.put("monitors", 12L); m.put("availability_pct", 99.95);
        m.put("meets_target", true); m.put("below_target", 0L); m.put("worst", List.of());
        return m;
    }

    @SuppressWarnings("unchecked")
    private Predicate<Long> scopeOf(MockHttpSession s, int call) throws Exception {
        mvc.perform(get("/api/monitoring/availability-week").session(s)).andExpect(status().isOk());
        ArgumentCaptor<Predicate<Long>> cap = ArgumentCaptor.forClass(Predicate.class);
        verify(availabilityWeekService, times(call)).build(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("monitoring.read yok → 403, servis çağrılmaz")
    void noPermission_403() throws Exception {
        doThrow(new SecurityException("yetki yok")).when(permissionService).require(any(HttpSession.class), eq("monitoring.read"), eq("view"));
        mvc.perform(get("/api/monitoring/availability-week").session(session("USER", List.of(5L))))
                .andExpect(status().isForbidden());
        verify(availabilityWeekService, never()).build(any());
    }

    @Test
    @DisplayName("yanıt: success + veri (ölçüm, hedef, hüküm)")
    void okBody() throws Exception {
        when(availabilityWeekService.build(any())).thenReturn(week());
        mvc.perform(get("/api/monitoring/availability-week").session(session("ADMIN", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.days").value(7))
                .andExpect(jsonPath("$.data.target_pct").value(99.9))
                .andExpect(jsonPath("$.data.availability_pct").value(99.95))
                .andExpect(jsonPath("$.data.meets_target").value(true));
        verify(permissionService).require(any(HttpSession.class), eq("monitoring.read"), eq("view"));
    }

    @Test
    @DisplayName("kapsam: global görüntüleyici hepsi (takımsız dahil); USER / kapsamlı müdür yalnız takımları; paylaşım ekler; 7/24 operatörü hepsi")
    void scopePerRole() throws Exception {
        when(availabilityWeekService.build(any())).thenReturn(week());

        Predicate<Long> global = scopeOf(session("AUDIT", null), 1);
        assertThat(global.test(9L)).isTrue();
        assertThat(global.test(null)).isTrue();

        Predicate<Long> user = scopeOf(session("USER", List.of(5L)), 2);
        assertThat(user.test(5L)).isTrue();
        assertThat(user.test(9L)).isFalse();
        assertThat(user.test(null)).isFalse();   // takımsız / envanter türevi satır kapsamlı kullanıcıya sayılmaz

        Predicate<Long> scopedAdmin = scopeOf(session("ADMIN", List.of(5L, 6L)), 3);   // müdür global DEĞİLDİR
        assertThat(scopedAdmin.test(6L)).isTrue();
        assertThat(scopedAdmin.test(9L)).isFalse();

        MockHttpSession grantedSession = session("USER", List.of(5L));
        grantedSession.setAttribute(SessionScope.ATTR_GRANT_VIEW, List.of(9L));
        Predicate<Long> granted = scopeOf(grantedSession, 4);
        assertThat(granted.test(9L)).isTrue();
        assertThat(granted.test(7L)).isFalse();

        MockHttpSession operatorSession = session("USER", List.of(3L));
        operatorSession.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        Predicate<Long> operator = scopeOf(operatorSession, 5);
        assertThat(operator.test(9L)).isTrue();
        assertThat(operator.test(null)).isTrue();
    }

    @Test
    @DisplayName("okuma hatası → 503 DB_UNAVAILABLE, veri yok")
    void readFailureIs503() throws Exception {
        when(availabilityWeekService.build(any()))
                .thenThrow(new AvailabilityWeekService.UnavailableException(new IllegalStateException("db")));
        mvc.perform(get("/api/monitoring/availability-week").session(session("ADMIN", null)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("DB_UNAVAILABLE"))
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
