package com.sitemonitor.config;

import com.sitemonitor.controller.AuthController;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kodla giriş / Giriş Yöntemleri uçları (2026-10-02) oturumsuz çağrılır: giriş sayfası yöntemleri okur, kodu ister ve
 * doğrular — oturum ancak doğrulamadan SONRA kurulur. Yönetim ucu ({@code /api/admin/login-methods}) korumalı kalır.
 * Kod uçlarının istek/yanıt gövdeleri TRACE'te bile loglanmaz (kod {@code code} alanında — genel bir ad).
 */
@ExtendWith(MockitoExtension.class)
class LoginOtpPublicPathsTest {

    @Mock RememberMeService rememberMeService;
    @Mock UserService userService;
    @Mock AuthController authController;
    @Mock AuditService auditService;

    @InjectMocks AuthInterceptor interceptor;

    @Test
    @DisplayName("PUBLIC: /api/public/login-methods, /api/login/otp/request, /api/login/otp/verify oturumsuz geçer")
    void otpPaths_arePublic() throws Exception {
        for (String p : new String[] { "/api/public/login-methods", "/api/login/otp/request", "/api/login/otp/verify" }) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", p);
            assertThat(interceptor.preHandle(req, new MockHttpServletResponse(), new Object())).as(p).isTrue();
        }
    }

    @Test
    @DisplayName("yönetim ucu ve benzer adlı yollar KORUMALI: /api/admin/login-methods, /api/login/otp/x, /api/login/otp → 401")
    void adminAndLookalikes_protected() throws Exception {
        for (String p : new String[] { "/api/admin/login-methods", "/api/login/otp/other", "/api/login/otp", "/api/public/login-methods/x" }) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            assertThat(interceptor.preHandle(new MockHttpServletRequest("GET", p), res, new Object())).as(p).isFalse();
            assertThat(res.getStatus()).as(p).isEqualTo(401);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("PUBLIC listesindeki kod uçları tam bu üçü; hiçbiri 'status' içermez (Durum sayfası kapısı)")
    void publicSet_containsExactlyOtpTrio() throws Exception {
        Field f = AuthInterceptor.class.getDeclaredField("PUBLIC");
        f.setAccessible(true);
        Set<String> pub = (Set<String>) f.get(null);
        assertThat(pub).contains("/api/public/login-methods", "/api/login/otp/request", "/api/login/otp/verify")
                .noneMatch(p -> p.contains("status"))
                .noneMatch(p -> p.startsWith("/api/admin/"));
    }

    @Test
    @DisplayName("istek loglama: /api/login/otp/* gövdeleri (yol hileleriyle de) HİÇ loglanmaz; /api/login değişmedi")
    void requestLogging_omitsOtpBodies() {
        for (String uri : new String[] { "/api/login/otp/verify", "/api/login/otp/request", "/api/login//OTP/verify",
                "/api/login/otp/verify;jsessionid=1", "/api/login/%6Ftp/verify" }) {
            assertThat(RequestLoggingFilter.bodyNeverLogged(new MockHttpServletRequest("POST", uri))).as(uri).isTrue();
        }
        assertThat(RequestLoggingFilter.bodyNeverLogged(new MockHttpServletRequest("POST", "/api/login"))).isFalse();
    }
}
