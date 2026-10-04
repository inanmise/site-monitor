package com.sitemonitor.config;

import com.sitemonitor.controller.AuthController;
import com.sitemonitor.controller.SessionScope;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
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
 * 7/24 izleme ekibi operatör bayrağı (2026-10-04) — {@code AuthInterceptor} her GEÇERLİ istekte oturumu önbellekle eşitler:
 * operatörlük yeniden giriş BEKLEMEDEN gelir ve gider. Kesilen (pasif / süpersede) oturumda eşitleme koşmaz; servis yoksa
 * (dilim testi) davranış birebir; eşitleme hatası isteği düşürmez.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthInterceptorNocOperatorTest {

    @Mock RememberMeService rememberMeService;
    @Mock UserService userService;
    @Mock AuthController authController;
    @Mock AuditService auditService;
    @Mock NocOperatorService nocOperators;

    AuthInterceptor interceptor;
    /** Önbelleğin "şu an" operatör saydığı kullanıcılar. */
    final List<Long> operators = new ArrayList<>();

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(rememberMeService, userService, authController, auditService);
        interceptor.setNocOperators(nocOperators);
        when(userService.isSessionSuperseded(anyString(), anyString())).thenReturn(false);
        doAnswer(i -> {
            MockHttpSession s = i.getArgument(0);
            Object uid = s.getAttribute("userId");
            if (operators.contains(uid)) {
                s.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
                s.setAttribute(SessionScope.ATTR_NOC_TEAM_IDS, new ArrayList<>(List.of(10L)));
            } else {
                s.removeAttribute(SessionScope.ATTR_NOC_OPERATOR);
                s.removeAttribute(SessionScope.ATTR_NOC_TEAM_IDS);
            }
            return null;
        }).when(nocOperators).sync(any());
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
    @DisplayName("takıma eklenen kullanıcı bir SONRAKİ istekte operatör olur; takım listeden çıkınca bayrak kalkar (yeniden giriş yok)")
    void flagFollowsCacheWithoutRelogin() throws Exception {
        MockHttpSession s = session(5L);
        assertThat(call(s)).isTrue();
        assertThat(SessionScope.isNocOperator(s)).isFalse();

        operators.add(5L);
        assertThat(call(s)).isTrue();
        assertThat(SessionScope.isNocOperator(s)).isTrue();
        assertThat(SessionScope.seesAllMonitoring(s)).isTrue();

        operators.clear();
        assertThat(call(s)).isTrue();
        assertThat(SessionScope.isNocOperator(s)).isFalse();
        assertThat(SessionScope.canViewMonitoring(s, 99L)).isFalse();
    }

    @Test
    @DisplayName("kesilen oturumda (süpersede) eşitleme KOŞMAZ; eşitleme hatası isteği düşürmez; servis yoksa davranış aynı")
    void supersededAndFailures() throws Exception {
        MockHttpSession s = session(6L);
        when(userService.isSessionSuperseded("U6", s.getId())).thenReturn(true);
        assertThat(call(s)).isFalse();
        verify(nocOperators, never()).sync(any());

        MockHttpSession ok = session(7L);
        doThrow(new RuntimeException("cache down")).when(nocOperators).sync(any());
        assertThat(call(ok)).isTrue();

        AuthInterceptor plain = new AuthInterceptor(rememberMeService, userService, authController, auditService);
        MockHttpSession p = session(8L);
        MockHttpServletRequest r = new MockHttpServletRequest("GET", "/api/monitoring/ping");
        r.setSession(p);
        assertThat(plain.preHandle(r, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(SessionScope.isNocOperator(p)).isFalse();
    }
}
