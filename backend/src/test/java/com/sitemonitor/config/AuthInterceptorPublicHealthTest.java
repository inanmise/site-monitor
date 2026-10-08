package com.sitemonitor.config;

import com.sitemonitor.controller.AuthController;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Veritabanı sağlık ucu (2026-10-08) dış izleyici için OTURUMSUZ açık; açık liste yalnız TAM yolu kapsar — komşu bir
 * {@code /api/public/health/…} yolu ya da sonuna eklenen parça oturumsuz geçemez.
 */
class AuthInterceptorPublicHealthTest {

    private final AuthInterceptor interceptor = new AuthInterceptor(mock(RememberMeService.class), mock(UserService.class),
            mock(AuthController.class), mock(AuditService.class));

    private boolean allowed(String path) throws Exception {
        return interceptor.preHandle(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), new Object());
    }

    @Test
    @DisplayName("/api/public/health/db oturumsuz geçer; komşu yollar 401")
    void onlyExactPathIsPublic() throws Exception {
        assertThat(allowed("/api/public/health/db")).isTrue();
        assertThat(allowed("/api/public/health/db/details")).isFalse();
        assertThat(allowed("/api/public/health")).isFalse();
        assertThat(allowed("/api/admin/system")).isFalse();
    }
}
