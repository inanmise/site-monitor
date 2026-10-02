package com.sitemonitor.config;

import com.sitemonitor.controller.AuthController;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.util.SystemMaintenanceSignal;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sistem Bakım Modu — {@code AuthInterceptor} kapısı (2026-10-02, kullanıcı kararı).
 * Sıra: pasif kontrolü → bakım kontrolü → süpersede. Bakım aktifken global yönetici olmayan oturum MEZAR TAŞI ile kesilir
 * (401 MAINTENANCE); global yönetici geçer; bakım yokken davranış birebir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthInterceptorMaintenanceTest {

    @Mock RememberMeService rememberMeService;
    @Mock UserService userService;
    @Mock AuthController authController;
    @Mock AuditService auditService;
    @Mock SystemMaintenanceService maintenance;

    AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(rememberMeService, userService, authController, auditService);
        interceptor.setSystemMaintenance(maintenance);
        when(maintenance.signalBody()).thenReturn(SystemMaintenanceSignal.body("Sistem bakımda",
                Map.of("state", "active", "end_at", "2026-10-02T11:00:00Z")));
    }

    private static MockHttpSession session(String username, String role, boolean global) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", username);
        s.setAttribute("userId", 5L);
        s.setAttribute("teamId", 3L);
        s.setAttribute("systemRole", role);
        if (!global) s.setAttribute("viewTeamIds", List.of(3L));
        return s;
    }

    private static MockHttpServletRequest req(String path, MockHttpSession s) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
        if (s != null) r.setSession(s);
        return r;
    }

    @Test
    @DisplayName("bakım AKTİF + kullanıcı oturumu → 401 MAINTENANCE, mezar taşı, token'lar silinir, çerez düşer, denetim + sayaç")
    void active_userSession_cut() throws Exception {
        when(maintenance.isActive()).thenReturn(true);
        MockHttpSession s = session("ALICE", "USER", false);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req("/api/session/ping", s), res, new Object());

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"code\":\"MAINTENANCE\"").contains("\"maintenance\"")
                .doesNotContain("Session superseded");
        assertThat(s.getAttribute("authenticated")).isNull();
        assertThat(s.getAttribute("username")).isNull();
        assertThat(s.getAttribute(AuthInterceptor.ATTR_MAINTENANCE)).isEqualTo(true);
        assertThat(s.getAttribute(AuthInterceptor.ATTR_MAINTENANCE_USER)).isEqualTo("ALICE");
        assertThat(s.isInvalid()).as("invalidate DEĞİL — mezar taşı").isFalse();
        verify(rememberMeService).invalidateAllForUser("ALICE");
        verify(userService).clearActiveSession(eq("ALICE"), anyString());
        Cookie c = res.getCookie(RememberMeService.COOKIE_NAME);
        assertThat(c).isNotNull();
        assertThat(c.getMaxAge()).isZero();
        verify(auditService).recordAction(eq("SESSION_ENDED_MAINTENANCE"), eq("ALICE"), eq(5L), eq(3L), eq("USER"),
                eq("USER"), eq("ALICE"), anyString(), any(), any(), any());
        verify(maintenance).recordSessionEnded();
        verify(userService, never()).isSessionSuperseded(any(), any());
    }

    @Test
    @DisplayName("kapsamlı müdür (ADMIN + viewTeamIds) de kesilir — yalnız GLOBAL yönetici içeride kalır")
    void active_scopedAdmin_cut() throws Exception {
        when(maintenance.isActive()).thenReturn(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req("/api/certificates", session("MUDUR", "ADMIN", false)), res, new Object())).isFalse();
        assertThat(res.getContentAsString()).contains("MAINTENANCE");
    }

    @Test
    @DisplayName("bakım AKTİF + global yönetici → geçer (süpersede kontrolü normal sürer)")
    void active_globalAdmin_passes() throws Exception {
        when(maintenance.isActive()).thenReturn(true);
        MockHttpSession s = session("admin", "ADMIN", true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req("/api/certificates", s), res, new Object())).isTrue();
        assertThat(s.getAttribute("authenticated")).isEqualTo(true);
        verify(userService).isSessionSuperseded("admin", s.getId());
        verify(auditService, never()).recordAction(eq("SESSION_ENDED_MAINTENANCE"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("SIRA: pasif kontrolü ÖNCE — pasif hesap bakımda da ACCOUNT_INACTIVE alır (MAINTENANCE değil)")
    void passiveCheckFirst() throws Exception {
        when(maintenance.isActive()).thenReturn(true);
        when(userService.isAccountInactive("ALICE")).thenReturn(true);
        MockHttpSession s = session("ALICE", "USER", false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req("/api/me", s), res, new Object())).isFalse();
        assertThat(res.getContentAsString()).contains("ACCOUNT_INACTIVE").doesNotContain("MAINTENANCE");
        assertThat(s.getAttribute(AuthInterceptor.ATTR_INACTIVE)).isEqualTo(true);
        verify(maintenance, never()).recordSessionEnded();
    }

    @Test
    @DisplayName("SIRA: bakım kontrolü süpersede kontrolünden ÖNCE (kesilen oturum 'oturum düştü' görmez)")
    void maintenanceBeforeSuperseded() throws Exception {
        when(maintenance.isActive()).thenReturn(true);
        when(userService.isSessionSuperseded(any(), any())).thenReturn(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        interceptor.preHandle(req("/api/me", session("ALICE", "USER", false)), res, new Object());
        assertThat(res.getContentAsString()).contains("MAINTENANCE").doesNotContain("Session superseded");
        InOrder o = inOrder(userService, maintenance);
        o.verify(userService).isAccountInactive("ALICE");
        o.verify(maintenance).isActive();
    }

    @Test
    @DisplayName("bakım YOK → davranış birebir (oturum geçer, kapı hiçbir şey yazmaz)")
    void noMaintenance_unchanged() throws Exception {
        when(maintenance.isActive()).thenReturn(false);
        MockHttpSession s = session("ALICE", "USER", false);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req("/api/me", s), res, new Object())).isTrue();
        assertThat(s.getAttribute("authenticated")).isEqualTo(true);
        verify(rememberMeService, never()).invalidateAllForUser(anyString());
        verify(maintenance, never()).recordSessionEnded();
    }

    @Test
    @DisplayName("bakım servisi hiç yoksa (dilim testi) → kapı çalışmaz")
    void noService_unchanged() throws Exception {
        AuthInterceptor plain = new AuthInterceptor(rememberMeService, userService, authController, auditService);
        MockHttpSession s = session("ALICE", "USER", false);
        assertThat(plain.preHandle(req("/api/me", s), new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    @DisplayName("mezar taşı: bakım sürüyorsa aynı MAINTENANCE sinyali; bittiyse oturum kapanır ve istek oturumsuz (401 Unauthorized)")
    void tombstone_followUp() throws Exception {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute(AuthInterceptor.ATTR_MAINTENANCE, true);
        s.setAttribute(AuthInterceptor.ATTR_MAINTENANCE_USER, "ALICE");
        when(maintenance.isActive()).thenReturn(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req("/api/me", s), res, new Object())).isFalse();
        assertThat(res.getContentAsString()).contains("MAINTENANCE");
        verify(auditService, never()).recordAction(eq("SESSION_ENDED_MAINTENANCE"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());

        when(maintenance.isActive()).thenReturn(false);
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req("/api/me", s), res2, new Object())).isFalse();
        assertThat(res2.getContentAsString()).contains("Unauthorized").doesNotContain("MAINTENANCE");
        assertThat(s.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("remember-me: bakımda global yönetici olmayan çerez → oturum KURULMAZ, token'lar silinir, MAINTENANCE + engellenen giriş")
    void rememberMe_blocked() throws Exception {
        when(maintenance.isActive()).thenReturn(true);
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/me");
        r.setCookies(new Cookie(RememberMeService.COOKIE_NAME, "tok"));
        AppUser u = new AppUser();
        u.setId(9L);
        u.setUsername("BOB");
        u.setSystemRole("USER");
        u.setActive(true);
        when(rememberMeService.validate(eq("tok"), any())).thenReturn(Optional.of("BOB"));
        when(userService.findByUsername("BOB")).thenReturn(Optional.of(u));
        when(maintenance.isGlobalAdminAccount(u)).thenReturn(false);
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(r, res, new Object())).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("MAINTENANCE");
        verify(authController, never()).populateSession(any(), any());
        verify(rememberMeService).invalidateAllForUser("BOB");
        verify(auditService).recordMaintenanceLogin(eq("BOB"), eq(9L), any(), eq("USER"), any(), any(), eq("REMEMBER_ME"));
        verify(maintenance).recordBlockedLogin();
    }

    @Test
    @DisplayName("remember-me: bakımda GLOBAL yönetici çerezi → oturum kurulur")
    void rememberMe_globalAdmin_restored() throws Exception {
        when(maintenance.isActive()).thenReturn(true);
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/me");
        r.setCookies(new Cookie(RememberMeService.COOKIE_NAME, "tok"));
        AppUser u = new AppUser();
        u.setUsername("admin");
        u.setSystemRole("ADMIN");
        u.setActive(true);
        when(rememberMeService.validate(eq("tok"), any())).thenReturn(Optional.of("admin"));
        when(userService.findByUsername("admin")).thenReturn(Optional.of(u));
        when(userService.checkLockout("admin")).thenReturn(new UserService.LockoutStatus(false, 0));
        when(maintenance.isGlobalAdminAccount(u)).thenReturn(true);
        assertThat(interceptor.preHandle(r, new MockHttpServletResponse(), new Object())).isTrue();
        verify(authController).populateSession(any(), eq(u));
    }

    @Test
    @DisplayName("public bakım durumu ucu oturumsuz geçer (PUBLIC)")
    void publicEndpoint_passes() throws Exception {
        when(maintenance.isActive()).thenReturn(true);
        assertThat(interceptor.preHandle(req("/api/public/system-maintenance", null), new MockHttpServletResponse(),
                new Object())).isTrue();
    }

    @Test
    @DisplayName("bakım durumu okunamazsa (istisna) kapı açık kalır — fail-open")
    void failOpen() throws Exception {
        when(maintenance.isActive()).thenThrow(new RuntimeException("db"));
        MockHttpSession s = session("ALICE", "USER", false);
        assertThat(interceptor.preHandle(req("/api/me", s), new MockHttpServletResponse(), new Object())).isTrue();
    }
}
