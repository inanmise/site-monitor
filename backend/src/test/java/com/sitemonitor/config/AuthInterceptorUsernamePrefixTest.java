package com.sitemonitor.config;

import com.sitemonitor.controller.AuthController;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.loginpolicy.UsernamePrefixPolicy;
import com.sitemonitor.service.loginpolicy.UsernamePrefixRule;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kullanıcı adı ön eki — {@code AuthInterceptor} kapısı (2026-10-21). Sıra: pasif → ÖN EK → bakım → süpersede. Kurala
 * uymayan canlı oturum MEZAR TAŞI ile kesilir (invalidate değil — öteki sekmeler ve uçuştaki istekler aynı sinyali alır),
 * remember-me token'ları silinir, tek-oturum kaydı temizlenir, {@code SESSION_ENDED_USERNAME_PREFIX} yazılır. Global
 * yönetici OTOMATİK muaf değildir; bootstrap yönetici ve muaf liste muaftır. Kural kapalıyken davranış birebir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthInterceptorUsernamePrefixTest {

    @Mock RememberMeService rememberMeService;
    @Mock UserService userService;
    @Mock AuthController authController;
    @Mock AuditService auditService;
    @Mock UsernamePrefixPolicy policy;
    @Mock SystemMaintenanceService maintenance;

    final AtomicReference<UsernamePrefixRule> rule = new AtomicReference<>();
    AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        rule.set(UsernamePrefixRule.of(true, "N", "SERVIS1", "admin"));
        when(policy.allows(any())).thenAnswer(inv -> rule.get().allows(inv.getArgument(0)));
        when(policy.deniedBody()).thenAnswer(inv -> UsernamePrefixRule.deniedBody(rule.get().prefixes()));
        interceptor = new AuthInterceptor(rememberMeService, userService, authController, auditService);
        interceptor.setUsernamePrefix(policy);
    }

    private static MockHttpSession session(String username, String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", username);
        s.setAttribute("userId", 5L);
        s.setAttribute("teamId", 3L);
        s.setAttribute("systemRole", role);
        return s;
    }

    private static MockHttpServletRequest req(String path, MockHttpSession s) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", path);
        if (s != null) r.setSession(s);
        return r;
    }

    @Test
    @DisplayName("MP oturumu → 401 USERNAME_PREFIX_NOT_ALLOWED, mezar taşı (invalidate DEĞİL), token'lar silinir, çerez düşer, kayıt temizlenir, denetim")
    void mpSessionCut() throws Exception {
        MockHttpSession s = session("MP123", "USER");
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(req("/api/certificates", s), res, new Object())).isFalse();

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"code\":\"USERNAME_PREFIX_NOT_ALLOWED\"")
                .contains("\"allowed_prefixes\":[\"N\"]").doesNotContain("Session superseded");
        assertThat(s.isInvalid()).as("mezar taşı — invalidate değil").isFalse();
        assertThat(s.getAttribute("authenticated")).isNull();
        assertThat(s.getAttribute("username")).isNull();
        assertThat(s.getAttribute(AuthInterceptor.ATTR_USERNAME_PREFIX)).isEqualTo(true);
        assertThat(s.getAttribute(AuthInterceptor.ATTR_USERNAME_PREFIX_USER)).isEqualTo("MP123");
        verify(rememberMeService).invalidateAllForUser("MP123");
        verify(userService).clearActiveSession(eq("MP123"), anyString());
        Cookie c = res.getCookie(RememberMeService.COOKIE_NAME);
        assertThat(c).isNotNull();
        assertThat(c.getMaxAge()).isZero();
        verify(auditService).recordAction(eq("SESSION_ENDED_USERNAME_PREFIX"), eq("MP123"), eq(5L), eq(3L), eq("USER"),
                eq("USER"), eq("MP123"), anyString(), any(), any(), any());
        verify(userService, never()).isSessionSuperseded(any(), any());
    }

    @Test
    @DisplayName("öteki sekme / eşzamanlı istek AYNI sinyali alır (mezar taşı); denetim bir kez yazılır")
    void otherTabsSeeSameSignal() throws Exception {
        MockHttpSession s = session("MP123", "USER");
        interceptor.preHandle(req("/api/me", s), new MockHttpServletResponse(), new Object());
        MockHttpServletResponse second = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req("/api/session/ping", s), second, new Object())).isFalse();
        assertThat(second.getStatus()).isEqualTo(401);
        assertThat(second.getContentAsString()).contains("USERNAME_PREFIX_NOT_ALLOWED");
        verify(auditService, times(1)).recordAction(eq("SESSION_ENDED_USERNAME_PREFIX"), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("mezar taşı her pod'da AYNI sinyal: kuralı (bayat önbellekle) KAPALI gören pod da 401 USERNAME_PREFIX_NOT_ALLOWED döner, oturumu kapatmaz")
    void tombstoneAlwaysSignalsWhateverTheLocalRule() throws Exception {
        MockHttpSession s = session("MP123", "USER");
        interceptor.preHandle(req("/api/me", s), new MockHttpServletResponse(), new Object());
        // öteki pod: ayar önbelleği henüz eski → kural kapalı
        rule.set(UsernamePrefixRule.disabled("admin"));
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(req("/api/me", s), res, new Object())).isFalse();
            assertThat(res.getStatus()).isEqualTo(401);
            assertThat(res.getContentAsString()).contains("USERNAME_PREFIX_NOT_ALLOWED");
            assertThat(s.isInvalid()).isFalse();
            assertThat(s.getAttribute("authenticated")).isNull();
        }
    }

    @Test
    @DisplayName("N oturumu, muaf liste ve bootstrap yönetici geçer; GLOBAL yönetici OTOMATİK muaf değil")
    void allowedAndExempt() throws Exception {
        for (String name : List.of("N123", "SERVIS1", "admin")) {
            MockHttpSession s = session(name, "USER");
            assertThat(interceptor.preHandle(req("/api/me", s), new MockHttpServletResponse(), new Object())).as(name).isTrue();
        }
        MockHttpSession admin = session("MPADMIN", "ADMIN");   // viewTeamIds yok → global yönetici
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req("/api/me", admin), res, new Object())).isFalse();
        assertThat(res.getContentAsString()).contains("USERNAME_PREFIX_NOT_ALLOWED");
    }

    @Test
    @DisplayName("SIRA: pasif ÖNCE (pasif MP hesabı ACCOUNT_INACTIVE alır); ön ek bakımdan ÖNCE")
    void orderPassiveThenPrefixThenMaintenance() throws Exception {
        interceptor.setSystemMaintenance(maintenance);
        when(maintenance.isActive()).thenReturn(true);
        when(userService.isAccountInactive("MP1")).thenReturn(true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        interceptor.preHandle(req("/api/me", session("MP1", "USER")), res, new Object());
        assertThat(res.getContentAsString()).contains("ACCOUNT_INACTIVE").doesNotContain("USERNAME_PREFIX");

        MockHttpServletResponse res2 = new MockHttpServletResponse();
        interceptor.preHandle(req("/api/me", session("MP2", "USER")), res2, new Object());
        assertThat(res2.getContentAsString()).contains("USERNAME_PREFIX_NOT_ALLOWED").doesNotContain("MAINTENANCE");
        verify(maintenance, never()).recordSessionEnded();
        InOrder o = inOrder(userService, policy);
        o.verify(userService).isAccountInactive("MP2");
        o.verify(policy).allows("MP2");
    }

    @Test
    @DisplayName("remember-me: MP hesabının geçerli çerezi → oturum KURULMAZ, token'lar silinir, 401 + denetim (yol REMEMBER_ME)")
    void rememberMeRefused() throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/me");
        r.setCookies(new Cookie(RememberMeService.COOKIE_NAME, "tok"));
        AppUser u = new AppUser();
        u.setId(9L);
        u.setUsername("MP9");
        u.setSystemRole("USER");
        u.setActive(true);
        when(rememberMeService.validate(eq("tok"), any())).thenReturn(Optional.of("MP9"));
        when(userService.findByUsername("MP9")).thenReturn(Optional.of(u));
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(r, res, new Object())).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("USERNAME_PREFIX_NOT_ALLOWED");
        verify(authController, never()).populateSession(any(), any());
        verify(rememberMeService).invalidateAllForUser("MP9");
        verify(userService, never()).recordSuccessfulLogin(any(), any(), any(), any());
        verify(auditService).recordUsernamePrefixLogin(eq("MP9"), eq(9L), any(), eq("USER"), any(), any(), eq("REMEMBER_ME"));
    }

    @Test
    @DisplayName("remember-me: N hesabının çerezi bugünkü gibi oturum kurar")
    void rememberMeAllowed() throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/me");
        r.setCookies(new Cookie(RememberMeService.COOKIE_NAME, "tok"));
        AppUser u = new AppUser();
        u.setUsername("N9");
        u.setSystemRole("USER");
        u.setActive(true);
        when(rememberMeService.validate(eq("tok"), any())).thenReturn(Optional.of("N9"));
        when(userService.findByUsername("N9")).thenReturn(Optional.of(u));
        when(userService.checkLockout("N9")).thenReturn(new UserService.LockoutStatus(false, 0));
        assertThat(interceptor.preHandle(r, new MockHttpServletResponse(), new Object())).isTrue();
        verify(authController).populateSession(any(), eq(u));
    }

    @Test
    @DisplayName("kural KAPALI ya da politika yok → davranış birebir (MP oturumu geçer, hiçbir şey yazılmaz)")
    void ruleOffOrNoPolicyUnchanged() throws Exception {
        rule.set(UsernamePrefixRule.disabled("admin"));
        MockHttpSession s = session("MP123", "USER");
        assertThat(interceptor.preHandle(req("/api/me", s), new MockHttpServletResponse(), new Object())).isTrue();
        AuthInterceptor plain = new AuthInterceptor(rememberMeService, userService, authController, auditService);
        assertThat(plain.preHandle(req("/api/me", session("MP123", "USER")), new MockHttpServletResponse(), new Object())).isTrue();
        verify(rememberMeService, never()).invalidateAllForUser(anyString());
    }

    @Test
    @DisplayName("politika okunamazsa (istisna) kapı AÇIK kalır — fail-open")
    void failOpen() throws Exception {
        when(policy.allows(any())).thenThrow(new RuntimeException("settings"));
        assertThat(interceptor.preHandle(req("/api/me", session("MP123", "USER")), new MockHttpServletResponse(),
                new Object())).isTrue();
    }
}
