package com.sitemonitor.config;

import com.sitemonitor.controller.AuthController;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.service.AdAccountStatusService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H paketi B4 (2026-11-26): beni-hatırla çereziyle oturum KURULURKEN AD hesap durumu sorulur. Devre dışı / yok → belirteçler
 * silinir, çerez düşer, 401 {@code AD_ACCOUNT_DISABLED}; ulaşılamadı + 24 saatte doğrulama yok → 401 {@code AD_UNVERIFIED},
 * belirteç KALIR; izinliyse oturum bugünkü gibi kurulur. Servis yoksa (dilim testi) davranış birebir bugünkü.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthInterceptorAdAccountTest {

    @Mock RememberMeService rememberMeService;
    @Mock UserService userService;
    @Mock AuthController authController;
    @Mock AuditService auditService;
    @Mock AdAccountStatusService adStatus;

    AuthInterceptor interceptor;
    AppUser bob;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(rememberMeService, userService, authController, auditService);
        interceptor.setAdAccountStatus(adStatus);
        bob = new AppUser();
        bob.setId(9L);
        bob.setUsername("BOB");
        bob.setSystemRole("USER");
        bob.setAuthSource("LDAP");
        bob.setActive(true);
        when(rememberMeService.validate(eq("tok"), any())).thenReturn(Optional.of("BOB"));
        when(userService.findByUsername("BOB")).thenReturn(Optional.of(bob));
        when(userService.checkLockout("BOB")).thenReturn(new UserService.LockoutStatus(false, 0));
    }

    private static MockHttpServletRequest cookieReq() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/me");
        r.setCookies(new Cookie(RememberMeService.COOKIE_NAME, "tok"));
        return r;
    }

    @Test
    @DisplayName("AD hesabı devre dışı / yok → oturum KURULMAZ, belirteçler silinir, çerez düşer, 401 AD_ACCOUNT_DISABLED + BLOCKED denetim")
    void disabled_refusedAndTokensDeleted() throws Exception {
        when(adStatus.checkForLogin(bob)).thenReturn(new AdAccountStatusService.Decision(false,
                AdAccountStatusService.CODE_DISABLED, AdAccountStatusService.Status.DISABLED));
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(cookieReq(), res, new Object())).isFalse();

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"code\":\"AD_ACCOUNT_DISABLED\"");
        verify(authController, never()).populateSession(any(), any());
        verify(userService, never()).recordSuccessfulLogin(any(), any(), any(), any());
        verify(rememberMeService).invalidateAllForUser("BOB");
        Cookie c = res.getCookie(RememberMeService.COOKIE_NAME);
        assertThat(c).isNotNull();
        assertThat(c.getMaxAge()).isZero();
        verify(auditService).recordAdAccountRefused(eq("BOB"), eq(9L), any(), eq("USER"), any(), any(),
                eq("REMEMBER_ME"), eq("AD_ACCOUNT_DISABLED"));
        verify(userService, never()).applyProgressiveLockout(anyString());
    }

    @Test
    @DisplayName("AD'ye ulaşılamadı + 24 saatte doğrulama yok → 401 AD_UNVERIFIED (giriş formu); belirteç ve çerez KALIR")
    void unverified_refusedTokenKept() throws Exception {
        when(adStatus.checkForLogin(bob)).thenReturn(new AdAccountStatusService.Decision(false,
                AdAccountStatusService.CODE_UNVERIFIED, AdAccountStatusService.Status.UNKNOWN));
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(cookieReq(), res, new Object())).isFalse();

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"code\":\"AD_UNVERIFIED\"");
        verify(authController, never()).populateSession(any(), any());
        verify(rememberMeService, never()).invalidateAllForUser(anyString());
        assertThat(res.getCookie(RememberMeService.COOKIE_NAME)).isNull();
        verify(auditService).recordAdAccountRefused(eq("BOB"), eq(9L), any(), eq("USER"), any(), any(),
                eq("REMEMBER_ME"), eq("AD_UNVERIFIED"));
    }

    @Test
    @DisplayName("AD izin verdi → oturum bugünkü gibi kurulur")
    void allowed_sessionRestored() throws Exception {
        when(adStatus.checkForLogin(bob)).thenReturn(new AdAccountStatusService.Decision(true, null,
                AdAccountStatusService.Status.ACTIVE));
        assertThat(interceptor.preHandle(cookieReq(), new MockHttpServletResponse(), new Object())).isTrue();
        verify(authController).populateSession(any(), eq(bob));
        verify(auditService, never()).recordAdAccountRefused(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("pasif hesap AD'ye SORULMADAN eski yoldan reddedilir; oturumu olan istekte AD'ye hiç gidilmez")
    void askedOnlyWhenRestoringSession() throws Exception {
        bob.setActive(false);
        interceptor.preHandle(cookieReq(), new MockHttpServletResponse(), new Object());
        verify(adStatus, never()).checkForLogin(any());

        org.springframework.mock.web.MockHttpSession s = new org.springframework.mock.web.MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "BOB");
        s.setAttribute("userId", 9L);
        s.setAttribute("systemRole", "USER");
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/certificates");
        r.setSession(s);
        interceptor.preHandle(r, new MockHttpServletResponse(), new Object());
        verify(adStatus, never()).checkForLogin(any());
    }

    @Test
    @DisplayName("servis yoksa (dilim / eski bağlam) davranış birebir bugünkü: çerezle oturum kurulur")
    void noService_unchanged() throws Exception {
        AuthInterceptor plain = new AuthInterceptor(rememberMeService, userService, authController, auditService);
        assertThat(plain.preHandle(cookieReq(), new MockHttpServletResponse(), new Object())).isTrue();
        verify(authController).populateSession(any(), eq(bob));
    }
}
