package com.certmonitor.config;

import com.certmonitor.service.HttpMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link HttpMetricsInterceptor} — Sistem Sağlığı HTTP panelini besleyen istek ölçüm noktası.
 * Endpoint kardinalitesini sınırlamak için ham URI yerine route şablonu (method + pattern) kaydeder;
 * öz-metrik uçlarını atlar. Gerçek MockHttpServletRequest/Response ile izole (Spring context yok).
 */
class HttpMetricsInterceptorTest {

    private HttpMetricsService metrics;
    private HttpMetricsInterceptor interceptor;

    @BeforeEach
    void setUp() {
        metrics = mock(HttpMetricsService.class);
        interceptor = new HttpMetricsInterceptor(metrics);
    }

    @Test
    @DisplayName("preHandle: başlangıç zamanını attribute'a koyar + true döner")
    void preHandle_setsStartAttributeAndReturnsTrue() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/incidents/5");

        boolean proceed = interceptor.preHandle(req, new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
        assertThat(req.getAttribute("_t")).isInstanceOf(Long.class);
    }

    @Test
    @DisplayName("afterCompletion: route şablonu (method + pattern) + status ile kaydeder (ham URI değil)")
    void afterCompletion_withPattern_recordsRouteTemplate() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/incidents/5");
        req.setAttribute("_t", System.currentTimeMillis() - 20);
        req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/incidents/{id}");
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);

        interceptor.afterCompletion(req, res, new Object(), null);

        verify(metrics).record(eq("GET /api/incidents/{id}"), eq(200), anyLong());
    }

    @Test
    @DisplayName("afterCompletion: eşleşen şablon yoksa ham URI'ye düşer")
    void afterCompletion_noPattern_fallsBackToRawUri() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/unmatched");
        req.setAttribute("_t", System.currentTimeMillis());
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(404);

        interceptor.afterCompletion(req, res, new Object(), null);

        verify(metrics).record(eq("POST /api/unmatched"), eq(404), anyLong());
    }

    @Test
    @DisplayName("afterCompletion: start attribute yoksa kayıt YOK (no-op)")
    void afterCompletion_noStartAttribute_noRecord() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");

        interceptor.afterCompletion(req, new MockHttpServletResponse(), new Object(), null);

        verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("afterCompletion: /metrics ve /http-metrics öz-uçları kaydedilmez (özyinelemeli gürültü önlenir)")
    void afterCompletion_selfMetricsEndpoints_skipped() {
        for (String uri : new String[]{"/actuator/metrics", "/api/http-metrics/summary"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
            req.setAttribute("_t", System.currentTimeMillis());
            interceptor.afterCompletion(req, new MockHttpServletResponse(), new Object(), null);
        }
        verifyNoInteractions(metrics);
    }
}
