package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.metrics.BusinessMetricsRegistry;
import com.sitemonitor.service.metrics.MetricsExportToken;
import com.sitemonitor.service.metrics.MonitoringMetricNames;
import com.sitemonitor.service.metrics.MonitoringMetricsExporter;
import io.micrometer.core.instrument.Gauge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * İş ölçümleri ucu {@code /metrics/sitemonitor} (2026-10-18, bağımsız inceleme): yalnız taşıyıcı belirteçle; yanlış / eksik
 * belirteç 401 gövdesiz + {@code WWW-Authenticate}, IP başına 10 başarısız denemeden sonra 429, güvenlik olayı IP başına 10
 * dakikada bir; belirteç yoksa 404 ve denetim yok; aktarıcı bayatsa iş değerleri gizli; yanıt {@code no-store}.
 */
@WebMvcTest(MetricsExportController.class)
@Import({ MetricsExportToken.class, BusinessMetricsRegistry.class })
@TestPropertySource(properties = MetricsExportToken.PROPERTY + "=" + MetricsExportControllerTest.TOKEN)
class MetricsExportControllerTest {

    static final String TOKEN = "test-token-0123456789abcdef";

    @Autowired MockMvc mvc;
    @Autowired BusinessMetricsRegistry business;
    @Autowired MetricsExportController controller;

    @MockitoBean MonitoringMetricsExporter exporter;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    final AtomicReference<Double> value = new AtomicReference<>(2.0);

    @BeforeEach
    void setUp() {
        if (business.meters().find(MonitoringMetricNames.MONITOR_STATE).gauge() == null) {
            Gauge.builder(MonitoringMetricNames.MONITOR_STATE, value, AtomicReference::get).tag("monitor", "Ödeme").register(business.meters());
            Gauge.builder(MonitoringMetricNames.EXPORTER_ENABLED, () -> 1).register(business.meters());
        }
        when(exporter.visibleNames()).thenReturn(null);
    }

    static RequestPostProcessor ip(String addr) {
        return r -> { r.setRemoteAddr(addr); return r; };
    }

    @Test
    @DisplayName("doğru belirteç: 200, Prometheus metin biçimi, no-store, iş ölçümleri; /api dışında (oturum gerekmez)")
    void validTokenScrapes() throws Exception {
        MvcResult r = mvc.perform(get(MetricsExportController.PATH).header("Authorization", "Bearer " + TOKEN).with(ip("10.1.0.1")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn();
        assertThat(r.getResponse().getContentType()).startsWith("text/plain").contains("version=0.0.4");
        String body = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("sitemonitor_monitor_state{monitor=\"Ödeme\"} 2.0").contains("sitemonitor_exporter_enabled 1.0");
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("aktarıcı bayat: yalnız tazelik ölçümleri (iş değerleri ve source_up gizli)")
    void staleExporterHidesBusinessValues() throws Exception {
        when(exporter.visibleNames()).thenReturn(MonitoringMetricNames.STALE_VISIBLE);
        String body = mvc.perform(get(MetricsExportController.PATH).header("Authorization", "Bearer " + TOKEN).with(ip("10.1.0.2")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("sitemonitor_exporter_enabled").doesNotContain("sitemonitor_monitor_state");
    }

    @Test
    @DisplayName("eksik / yanlış belirteç: 401 gövdesiz + WWW-Authenticate; güvenlik olayı IP başına pencerede BİR kez")
    void missingOrWrongToken() throws Exception {
        mvc.perform(get(MetricsExportController.PATH).with(ip("10.2.0.1")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
        mvc.perform(get(MetricsExportController.PATH).header("Authorization", "Bearer wrong-token-0123456789").with(ip("10.2.0.1")))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mvc.perform(get(MetricsExportController.PATH).header("Authorization", "Basic " + TOKEN).with(ip("10.2.0.1")))
                .andExpect(status().isUnauthorized());
        verify(auditService, times(1)).recordSecurityEvent(eq("METRICS_EXPORT_ACCESS_DENIED"), any(), isNull(), eq("METRICS"),
                eq(MetricsExportController.PATH), eq("missing"));
        // başka IP ayrı sayılır
        mvc.perform(get(MetricsExportController.PATH).header("Authorization", "Bearer nope-nope-nope-nope-nope").with(ip("10.2.0.2")))
                .andExpect(status().isUnauthorized());
        verify(auditService, times(1)).recordSecurityEvent(eq("METRICS_EXPORT_ACCESS_DENIED"), any(), isNull(), eq("METRICS"),
                eq(MetricsExportController.PATH), eq("invalid"));
    }

    @Test
    @DisplayName("hız sınırı: IP başına dakikada 10 başarısız denemeden sonra 429 + Retry-After — doğru belirteç bile denenmez")
    void rateLimited() throws Exception {
        long[] clock = { 1_000_000_000_000L };
        controller.setClockMs(() -> clock[0]);
        try {
            for (int i = 0; i < 10; i++) {
                mvc.perform(get(MetricsExportController.PATH).header("Authorization", "Bearer bad").with(ip("10.3.0.1")))
                        .andExpect(status().isUnauthorized());
            }
            mvc.perform(get(MetricsExportController.PATH).header("Authorization", "Bearer " + TOKEN).with(ip("10.3.0.1")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Retry-After", "60"))
                    .andExpect(content().string(""));
            mvc.perform(get(MetricsExportController.PATH).header("Authorization", "Bearer " + TOKEN).with(ip("10.3.0.9")))
                    .andExpect(status().isOk());
            clock[0] += 61_000L;   // sonraki dakika: sayaç sıfırlanır
            mvc.perform(get(MetricsExportController.PATH).header("Authorization", "Bearer " + TOKEN).with(ip("10.3.0.1")))
                    .andExpect(status().isOk());
        } finally {
            controller.setClockMs(System::currentTimeMillis);
        }
    }

    @Test
    @DisplayName("belirteç tanımlı değil: 404 gövdesiz, denetim yok, kazıma yok")
    void noTokenIsNotFound() {
        BusinessMetricsRegistry reg = mock(BusinessMetricsRegistry.class);
        AuditService audit = mock(AuditService.class);
        MetricsExportController c = new MetricsExportController(MetricsExportToken.of(""), reg, exporter, audit);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", MetricsExportController.PATH);
        req.addHeader("Authorization", "Bearer " + TOKEN);
        var res = c.scrape(req);
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody()).isNull();
        assertThat(res.getHeaders().getCacheControl()).isEqualTo("no-store");
        verifyNoInteractions(reg, audit);
        verify(exporter, never()).visibleNames();
    }
}
