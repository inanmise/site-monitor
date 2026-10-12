package com.sitemonitor.config;

import com.sitemonitor.controller.AuthController;
import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.access.TeamAccessGrantService;
import com.sitemonitor.service.noc.NocOperatorService;
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
import org.springframework.mock.web.MockHttpSession;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Takım erişim paylaşımı (2026-10-12) — {@code AuthInterceptor} her GEÇERLİ istekte paylaşımı oturuma yazar: verilen
 * paylaşım yeniden giriş BEKLEMEDEN gelir, kaldırılınca gider. Süpersede oturumda koşmaz; eşitleme hatası isteği düşürmez;
 * 7/24 eşitlemesinin hatası paylaşım eşitlemesini ENGELLEMEZ.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthInterceptorTeamGrantTest {

    @Mock RememberMeService rememberMeService;
    @Mock UserService userService;
    @Mock AuthController authController;
    @Mock AuditService auditService;
    @Mock NocOperatorService nocOperators;
    @Mock TeamAccessGrantService teamGrants;

    AuthInterceptor interceptor;
    final List<Long> granted = new ArrayList<>();

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(rememberMeService, userService, authController, auditService);
        interceptor.setNocOperators(nocOperators);
        interceptor.setTeamGrants(teamGrants);
        when(userService.isSessionSuperseded(anyString(), anyString())).thenReturn(false);
        doAnswer(i -> {
            MockHttpSession s = i.getArgument(0);
            if (granted.contains(s.getAttribute("userId"))) {
                s.setAttribute(SessionScope.ATTR_GRANT_VIEW, new ArrayList<>(List.of(20L)));
            } else {
                s.removeAttribute(SessionScope.ATTR_GRANT_VIEW);
            }
            return null;
        }).when(teamGrants).sync(any());
    }

    private static MockHttpSession session(long userId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", true);
        s.setAttribute("username", "U" + userId);
        s.setAttribute("userId", userId);
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", List.of(3L));
        return s;
    }

    private boolean call(MockHttpSession s) throws Exception {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/monitoring/ping");
        r.setSession(s);
        return interceptor.preHandle(r, new MockHttpServletResponse(), new Object());
    }

    @Test
    @DisplayName("paylaşım bir SONRAKİ istekte gelir, kaldırılınca gider (yeniden giriş yok)")
    void grantFollowsWithoutRelogin() throws Exception {
        MockHttpSession s = session(5L);
        assertThat(call(s)).isTrue();
        assertThat(SessionScope.canViewMonitoring(s, 20L)).isFalse();
        granted.add(5L);
        assertThat(call(s)).isTrue();
        assertThat(SessionScope.canViewMonitoring(s, 20L)).isTrue();
        granted.clear();
        assertThat(call(s)).isTrue();
        assertThat(SessionScope.canViewMonitoring(s, 20L)).isFalse();
    }

    @Test
    @DisplayName("süpersede oturumda eşitleme koşmaz; paylaşım hatası isteği düşürmez; 7/24 hatası paylaşımı engellemez")
    void supersededAndFailures() throws Exception {
        MockHttpSession s = session(6L);
        when(userService.isSessionSuperseded("U6", s.getId())).thenReturn(true);
        assertThat(call(s)).isFalse();
        verify(teamGrants, never()).sync(any());

        MockHttpSession noc = session(7L);
        granted.add(7L);
        doThrow(new RuntimeException("noc down")).when(nocOperators).sync(any());
        assertThat(call(noc)).isTrue();
        assertThat(SessionScope.canViewMonitoring(noc, 20L)).isTrue();

        MockHttpSession broken = session(8L);
        doThrow(new RuntimeException("grants down")).when(teamGrants).sync(any());
        assertThat(call(broken)).isTrue();
    }
}
