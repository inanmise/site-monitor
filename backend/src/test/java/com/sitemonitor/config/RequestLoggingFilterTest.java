package com.sitemonitor.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RequestLoggingFilter TRACE davranışı. Kritik regresyon: TRACE açıkken yanıt gövdesi loglanırken
 * gerçek yanıt BOZULMAMALI (eski ContentCachingResponseWrapper + copyBodyToResponse, server.compression
 * ile çakışıp gövdeyi bozuyor → beyaz ekrandı). Tee-stream gövdeyi gerçek çıkışa olduğu gibi geçirir.
 */
class RequestLoggingFilterTest {

    private RequestLoggingFilter filter;
    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level original;

    @BeforeEach
    void setUp() {
        filter = new RequestLoggingFilter();
        logbackLogger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
        original = logbackLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
        logbackLogger.setLevel(original);
    }

    @Test
    @DisplayName("TRACE: büyük yanıt gövdesi TAM ve bozulmadan teslim edilir; log truncate edilir")
    void trace_largeBody_deliveredIntact_andLogTruncated() throws Exception {
        logbackLogger.setLevel(Level.TRACE);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/certificates");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        byte[] big = new byte[20_000];
        java.util.Arrays.fill(big, (byte) 'A'); // 20 KB — capture cap (8 KB) ve log truncate (2000) üstü

        FilterChain chain = (request, response) -> {
            ((HttpServletResponse) response).setStatus(200);
            response.getOutputStream().write(big);
        };

        filter.doFilter(req, resp, chain);

        // Gerçek yanıt BOZULMADAN tam gelmeli (tee delegate'e her baytı geçirir)
        assertThat(resp.getContentAsByteArray()).hasSize(20_000).containsOnly((byte) 'A');
        assertThat(resp.getStatus()).isEqualTo(200);

        // respBody loglanır ama truncate edilir (büyük gövde belleği/log'u şişirmez)
        String logged = lastResponseLogLine();
        assertThat(logged).contains("respBody=").contains("[truncated]");
    }

    @Test
    @DisplayName("TRACE: getWriter() yolu da tee'lenir — metin gerçek yanıta tam yazılır")
    void trace_writerPath_deliveredIntact() throws Exception {
        logbackLogger.setLevel(Level.TRACE);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/stats");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String payload = "{\"ok\":true,\"items\":[1,2,3]}";
        FilterChain chain = (request, response) -> {
            ((HttpServletResponse) response).setStatus(200);
            response.getWriter().write(payload);
        };

        filter.doFilter(req, resp, chain);

        assertThat(resp.getContentAsString()).isEqualTo(payload);
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("TRACE: yanıt gövdesi gerçek yanıtta maskelenmez — redaction yalnız log'a aittir")
    void trace_bodyNotMutated_onlyLogRedacted() throws Exception {
        logbackLogger.setLevel(Level.TRACE);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        String body = "{\"token\":\"super-secret-value\"}";
        FilterChain chain = (request, response) -> {
            ((HttpServletResponse) response).setStatus(200);
            response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilter(req, resp, chain);

        // Teslim edilen gövde DEĞİŞMEZ
        assertThat(resp.getContentAsString()).isEqualTo(body);
        // Log'da maskelenir
        assertThat(lastResponseLogLine()).contains("respBody=").doesNotContain("super-secret-value");
    }

    @Test
    @DisplayName("TRACE kapalı (INFO): filtre baypas — yanıt sarmalanmaz")
    void nonTrace_bypassesWrapping() throws Exception {
        logbackLogger.setLevel(Level.INFO);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        HttpServletResponse[] seen = new HttpServletResponse[1];
        FilterChain chain = (request, response) -> {
            seen[0] = (HttpServletResponse) response;
            ((HttpServletResponse) response).setStatus(200);
            response.getOutputStream().write("x".getBytes(StandardCharsets.UTF_8));
        };

        filter.doFilter(req, resp, chain);

        // Sarmalama yok: handler GERÇEK response örneğini alır
        assertThat(seen[0]).isSameAs(resp);
        assertThat(resp.getContentAsString()).isEqualTo("x");
        assertThat(appender.list).isEmpty(); // TRACE kapalı → hiç log yok
    }

    @Test
    @DisplayName("TRACE: statik/JS yolu shouldSkip ile baypas — sarmalanmaz")
    void trace_staticAsset_skipped() throws Exception {
        logbackLogger.setLevel(Level.TRACE);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/assets/index-abc.js");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        HttpServletResponse[] seen = new HttpServletResponse[1];
        FilterChain chain = (request, response) -> {
            seen[0] = (HttpServletResponse) response;
            ((HttpServletResponse) response).setStatus(200);
        };

        filter.doFilter(req, resp, chain);

        assertThat(seen[0]).isSameAs(resp);                 // skip → wrap yok
        assertThat(seen[0]).isNotInstanceOf(HttpServletResponseWrapper.class);
    }

    /** En son "<<<" (yanıt) TRACE satırının formatlanmış mesajı. */
    private String lastResponseLogLine() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("<<<"))
                .reduce((a, b) -> b)
                .orElse("");
    }
}
