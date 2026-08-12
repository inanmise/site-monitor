package com.sitemonitor.config;

import com.sitemonitor.controller.AuthController;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AuthInterceptor güvenlik regresyonları (A1 + A2):
 *  - A1: remember-me çerezi hesap kilidini (geçici/kalıcı) veya disable'ı geçersiz kılmamalı.
 *  - A2: mustChangePassword kapısı çerezle geri-yüklenen oturumda da uygulanmalı.
 */
@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock RememberMeService rememberMeService;
    @Mock UserService userService;
    @Mock AuthController authController;
    @Mock AuditService auditService;

    @InjectMocks AuthInterceptor interceptor;

    private static MockHttpServletRequest reqWithCookie(String path) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI(path);
        req.setCookies(new Cookie(RememberMeService.COOKIE_NAME, "tok"));
        return req;
    }

    private static AppUser activeUser() {
        AppUser u = new AppUser();
        u.setUsername("alice");
        u.setActive(true);
        return u;
    }

    @Test
    @DisplayName("A1: kalıcı kilitli kullanıcı geçerli remember-me çereziyle bile 401 alır")
    void rememberMe_lockedUser_rejected() throws Exception {
        MockHttpServletRequest req = reqWithCookie("/api/certificates");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(rememberMeService.validate("tok")).thenReturn(Optional.of("alice"));
        when(userService.findByUsername("alice")).thenReturn(Optional.of(activeUser()));
        when(userService.checkLockout("alice")).thenReturn(new UserService.LockoutStatus(true, 0));

        boolean allowed = interceptor.preHandle(req, res, new Object());

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        verify(authController, never()).populateSession(any(), any());
    }

    @Test
    @DisplayName("A1: geçici kilitli kullanıcı remember-me çereziyle 401 alır")
    void rememberMe_temporarilyLockedUser_rejected() throws Exception {
        MockHttpServletRequest req = reqWithCookie("/api/certificates");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(rememberMeService.validate("tok")).thenReturn(Optional.of("alice"));
        when(userService.findByUsername("alice")).thenReturn(Optional.of(activeUser()));
        when(userService.checkLockout("alice")).thenReturn(new UserService.LockoutStatus(false, 120));

        boolean allowed = interceptor.preHandle(req, res, new Object());

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        verify(authController, never()).populateSession(any(), any());
    }

    @Test
    @DisplayName("A2: mustChangePassword olan kullanıcı çerezle geri-yüklense de whitelist dışı yola 403 alır")
    void rememberMe_mustChangePassword_blocksNonWhitelistedPath() throws Exception {
        MockHttpServletRequest req = reqWithCookie("/api/certificates");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(rememberMeService.validate("tok")).thenReturn(Optional.of("alice"));
        when(userService.findByUsername("alice")).thenReturn(Optional.of(activeUser()));
        when(userService.checkLockout("alice")).thenReturn(new UserService.LockoutStatus(false, 0));
        // Gerçek populateSession davranışını taklit et: mustChangePassword session'a yazılır.
        doAnswer(inv -> {
            HttpSession s = inv.getArgument(0);
            s.setAttribute("mustChangePassword", true);
            return null;
        }).when(authController).populateSession(any(), any());

        boolean allowed = interceptor.preHandle(req, res, new Object());

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("A2: mustChangePassword olsa bile whitelist'teki yol (change-password) çerezle erişilebilir")
    void rememberMe_mustChangePassword_allowsWhitelistedPath() throws Exception {
        MockHttpServletRequest req = reqWithCookie("/api/me/change-password");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(rememberMeService.validate("tok")).thenReturn(Optional.of("alice"));
        when(userService.findByUsername("alice")).thenReturn(Optional.of(activeUser()));
        when(userService.checkLockout("alice")).thenReturn(new UserService.LockoutStatus(false, 0));
        doAnswer(inv -> {
            HttpSession s = inv.getArgument(0);
            s.setAttribute("mustChangePassword", true);
            return null;
        }).when(authController).populateSession(any(), any());

        boolean allowed = interceptor.preHandle(req, res, new Object());

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("Aktif, kilitsiz kullanıcı remember-me çereziyle oturumu geri-yüklenir (true)")
    void rememberMe_activeUser_allowed() throws Exception {
        MockHttpServletRequest req = reqWithCookie("/api/certificates");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(rememberMeService.validate("tok")).thenReturn(Optional.of("alice"));
        when(userService.findByUsername("alice")).thenReturn(Optional.of(activeUser()));
        when(userService.checkLockout("alice")).thenReturn(new UserService.LockoutStatus(false, 0));

        boolean allowed = interceptor.preHandle(req, res, new Object());

        assertThat(allowed).isTrue();
        verify(authController).populateSession(any(), any());
    }

    // ── Sessiz reauth = GİRİŞTİR: damga + denetim kaydı ────────────────────────

    @Test
    @DisplayName("Çerezle sessiz dönüş giriş damgası basar ve REMEMBER_ME olarak işaretlenir")
    void rememberMe_stampsSuccessfulLogin() throws Exception {
        MockHttpServletRequest req = reqWithCookie("/api/certificates");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(rememberMeService.validate("tok")).thenReturn(Optional.of("alice"));
        when(userService.findByUsername("alice")).thenReturn(Optional.of(activeUser()));
        when(userService.checkLockout("alice")).thenReturn(new UserService.LockoutStatus(false, 0));
        when(auditService.resolveIp(any())).thenReturn("10.0.0.1");

        interceptor.preHandle(req, res, new Object());

        // CANONICAL username (entity'den) — çerezdeki case DB'dekinden farklı olabilir.
        verify(userService).recordSuccessfulLogin(eq("alice"), any(), eq("10.0.0.1"),
                eq(UserService.LoginMethod.REMEMBER_ME));
    }

    @Test
    @DisplayName("Sessiz dönüşe denetim kaydı da yazılır (bu yol bugüne kadar hiç LOGIN yazmıyordu)")
    void rememberMe_writesAuditLogin() throws Exception {
        MockHttpServletRequest req = reqWithCookie("/api/certificates");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(rememberMeService.validate("tok")).thenReturn(Optional.of("alice"));
        when(userService.findByUsername("alice")).thenReturn(Optional.of(activeUser()));
        when(userService.checkLockout("alice")).thenReturn(new UserService.LockoutStatus(false, 0));

        interceptor.preHandle(req, res, new Object());

        verify(auditService).recordLogin(eq("alice"), any(), any(), any(), any(), any(), any(),
                eq(true), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Kilitli kullanıcıda ne damga ne denetim kaydı yazılır (A1 kapısı damgayı da kapsar)")
    void rememberMe_lockedUser_noStampNoAudit() throws Exception {
        MockHttpServletRequest req = reqWithCookie("/api/certificates");
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(rememberMeService.validate("tok")).thenReturn(Optional.of("alice"));
        when(userService.findByUsername("alice")).thenReturn(Optional.of(activeUser()));
        when(userService.checkLockout("alice")).thenReturn(new UserService.LockoutStatus(true, 0));

        interceptor.preHandle(req, res, new Object());

        verify(userService, never()).recordSuccessfulLogin(any(), any(), any(), any());
        verify(auditService, never()).recordLogin(any(), any(), any(), any(), any(), any(), any(),
                anyBoolean(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("A2 (regresyon): mevcut oturumda mustChangePassword whitelist dışı yola 403")
    void session_mustChangePassword_blocksNonWhitelisted() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/api/certificates");
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("mustChangePassword", true);
        req.setSession(s);
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req, res, new Object());

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
    }

    // ── Tek aktif oturum / süpersede → otomatik logout (500 değil temiz 401) ─────

    @Test
    @DisplayName("Süpersede oturum → 401 + invalidate + remember-me cookie temizlenir (500 değil)")
    void session_superseded_returns401() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRequestURI("/api/certificates");
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "alice");
        req.setSession(s);
        MockHttpServletResponse res = new MockHttpServletResponse();
        when(userService.isSessionSuperseded(any(), any())).thenReturn(true);

        boolean allowed = interceptor.preHandle(req, res, new Object());

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("Session superseded");
        assertThat(s.isInvalid()).isTrue();
        Cookie c = res.getCookie(RememberMeService.COOKIE_NAME);
        assertThat(c).isNotNull();
        assertThat(c.getMaxAge()).isZero();   // remember-me cookie silindi
    }

    @Test
    @DisplayName("Yarış: oturum paralel istekçe kapatılmış (getAttribute IllegalStateException) → 500 değil 401")
    void session_concurrentlyInvalidated_returns401_notError() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession s = mock(HttpSession.class);
        when(req.getRequestURI()).thenReturn("/api/me");
        when(req.getSession(false)).thenReturn(s);
        when(s.getAttribute("authenticated"))
                .thenThrow(new IllegalStateException("getAttribute: Session already invalidated"));
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req, res, new Object());

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("Session superseded");
    }
}
