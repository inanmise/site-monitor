package com.sitemonitor.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.*;

/** İstek kimliği (2026-10-08): gelen X-Request-Id / X-Correlation-Id'ye uyulur, güvensizse üretilir, iki başlıkta döner. */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void reset() {
        RequestContextHolder.resetRequestAttributes();
    }

    private MockHttpServletResponse run(MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    @DisplayName("başlık yoksa 32 karakterlik kimlik üretilir; X-Request-Id ve X-Correlation-Id aynı değer")
    void generated() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse res = run(req);
        String id = res.getHeader(CorrelationIdFilter.REQUEST_ID_HEADER);
        assertNotNull(id);
        assertEquals(32, id.length());
        assertEquals(id, res.getHeader(CorrelationIdFilter.HEADER));
        assertEquals(id, CorrelationIdFilter.get(req));
    }

    @Test
    @DisplayName("gelen X-Request-Id (proxy) önceliklidir; yoksa X-Correlation-Id")
    void honoursIncoming() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        req.addHeader("X-Request-Id", "ns-abc.123");
        req.addHeader("X-Correlation-Id", "corr-1");
        assertEquals("ns-abc.123", run(req).getHeader("X-Request-Id"));

        MockHttpServletRequest only = new MockHttpServletRequest("GET", "/api/x");
        only.addHeader("X-Correlation-Id", "corr-1");
        MockHttpServletResponse res = run(only);
        assertEquals("corr-1", res.getHeader("X-Request-Id"));
        assertEquals("corr-1", res.getHeader("X-Correlation-Id"));
    }

    @Test
    @DisplayName("güvensiz (boşluk/CRLF/uzun) gelen kimlik reddedilir → üretilir")
    void rejectsUnsafe() throws Exception {
        for (String bad : new String[]{"a b", "x\r\nSet-Cookie: y", "z".repeat(41), "<script>"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
            req.addHeader("X-Request-Id", bad);
            String id = run(req).getHeader("X-Request-Id");
            assertNotEquals(bad, id);
            assertTrue(id.matches("[0-9a-f]{32}"), id);
        }
    }

    @Test
    @DisplayName("current(): istek bağlamından okur, bağlam yoksa null")
    void current() {
        assertNull(CorrelationIdFilter.current());
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(CorrelationIdFilter.ATTR, "rid-9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        assertEquals("rid-9", CorrelationIdFilter.current());
    }
}
