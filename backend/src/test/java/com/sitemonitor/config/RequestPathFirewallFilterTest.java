package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BK1 (bug regresyon 2026-09-27) — istek yolu güvenlik duvarı. Ham yolda matris parametresi, kodlu ayraç,
 * çift eğik çizgi, nokta bölütü ya da kontrol karakteri taşıyan istek işleyiciye ULAŞMADAN 400 alır;
 * normal yollar (API, statik varlık, /health, SPA kabuğu) ve SORGU dizesindeki aynı karakterler etkilenmez.
 */
class RequestPathFirewallFilterTest {

    /** Filtreden geçti mi — zincir çağrıldı ve 400 yazılmadı. */
    private static boolean passes(String method, String rawPath, String query) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, rawPath);
        if (query != null) req.setQueryString(query);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new RequestPathFirewallFilter().doFilter(req, res, chain);
        boolean reached = chain.getRequest() != null;
        if (!reached) {
            assertThat(res.getStatus()).as(rawPath).isEqualTo(400);
            assertThat(res.getContentAsString()).contains("\"success\":false");
        }
        return reached && res.getStatus() != 400;
    }

    @Test
    @DisplayName("KAPI: matris parametresi / kodlu ayraç / çift eğik çizgi / nokta bölütü / kontrol karakteri → 400, zincire ULAŞMAZ")
    void unsafePaths_rejected() throws Exception {
        String[] bad = {
                "/api;x/certificates",          // BK1'in canlı örneği: auth + CSRF atlatması
                "/api/certificates;x",
                "/api/certificates;jsessionid=ABC",
                "/api%3bx/certificates", "/api%3Bx/certificates",
                "/api/%2e%2e/admin/users", "/api/%2E%2E/admin/users", "/api/%2e/certificates",
                "/api%2fcertificates", "/api%2Fcertificates",
                "/api%5ccertificates", "/api\\certificates",
                "//api/certificates", "/api//certificates",
                "/api/./certificates", "/api/../api/certificates", "/api/certificates/..", "/api/certificates/.",
                "/api/certificates%00", "/api/certificates%0a", "/api/certificates%0D", "/api/certificates%7f",
                "/api/certificates\u0000", "/api/cert\nificates",
                "/api/certificates%", "/api/certificates%zz", "/api/certificates%4",
        };
        for (String p : bad) {
            assertThat(passes("GET", p, null)).as("reddedilmeliydi: " + p).isFalse();
            assertThat(passes("POST", p, null)).as("POST da reddedilmeliydi: " + p).isFalse();
            assertThat(RequestPathFirewallFilter.rejectReason(p)).as(p).isNotNull();
        }
    }

    @Test
    @DisplayName("Normal yollar geçer: API (alan adı yol değişkeni dâhil), statik varlık, /health, SPA kabuğu")
    void normalPaths_allowed() throws Exception {
        String[] ok = {
                "/", "/index.html", "/favicon.ico", "/assets/index-abc123.js", "/assets/logo.svg",
                "/health", "/health/readiness",
                "/api/certificates", "/api/me", "/api/login", "/api/public-stats",
                "/api/certificates/www.example.com/health",              // noktalı alan adı — kodlanmaz
                "/api/certificates/www.example.com%3A8443/health",       // encodeURIComponent(':')
                "/api/history/example.com.", "/api/admin/notes/example.com/12/revisions",
                "/api/admin/sql/tables/app_user/columns",
                "/api/x/%C3%A7al%C4%B1%C5%9Fma",                        // UTF-8 kodlu Türkçe bölüt
        };
        for (String p : ok) {
            assertThat(passes("GET", p, null)).as("geçmeliydi: " + p).isTrue();
            assertThat(RequestPathFirewallFilter.rejectReason(p)).as(p).isNull();
        }
    }

    @Test
    @DisplayName("Kural yalnız YOLA bakar: sorgu dizesindeki ; / %2e / // / .. serbest")
    void queryString_notInspected() throws Exception {
        assertThat(passes("GET", "/api/activity", "q=a;b&from=%2e%2e&u=http://x//y/../z")).isTrue();
    }

    @Test
    @DisplayName("lookupPath: matris içeriği kırpılır, yüzde kodu çözülür, // tekilleşir (yönlendiricinin gördüğü yol)")
    void lookupPath_normalises() {
        assertThat(RequestPathFirewallFilter.lookupPath(new MockHttpServletRequest("GET", "/api;x/certificates")))
                .isEqualTo("/api/certificates");
        assertThat(RequestPathFirewallFilter.lookupPath(new MockHttpServletRequest("GET", "/%61pi/certificates")))
                .isEqualTo("/api/certificates");
        assertThat(RequestPathFirewallFilter.lookupPath(new MockHttpServletRequest("GET", "//api/certificates")))
                .isEqualTo("/api/certificates");
        assertThat(RequestPathFirewallFilter.lookupPath(new MockHttpServletRequest("GET", "/api/login;jsessionid=1")))
                .isEqualTo("/api/login");
    }
}
