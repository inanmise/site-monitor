package com.sitemonitor.config;

import com.sitemonitor.service.AppSettingsService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 225 satırlık config, testi yoktu. Üç davranışı sessizce ve ağır bozulabilir:
 *
 *  1) CORS FAIL-SAFE (CWE-942): allowCredentials=true iken "*" origin BİRLİKTE olamaz. Eleme
 *     mantığı bozulursa kimlik bilgisiyle her origin'e açılan CORS oluşur — hiçbir test görmezdi.
 *     Origin listesi CANLI ayardan okunduğu için çalışma anında yanlış değer girilebilir.
 *  2) SPA CACHE POLİTİKASI: index.html no-store olmazsa deploy sonrası eski cache'li kabuk artık
 *     var olmayan bundle'ı çağırır → React mount olamaz → BEYAZ EKRAN. Kodda regresyon olarak
 *     anlatılmış ama kilitleyen test yoktu.
 *  3) /api/login-help gövde limiti: kimliksiz uç; limit kalkarsa OOM yolu açılır.
 */
class WebConfigTest {

    /** appSettings sağlayıcısı olmayan (yalnız @Value fallback'i kullanan) WebConfig. */
    private WebConfig configWith(String allowedOrigins, AppSettingsService live) {
        WebConfig cfg = new WebConfig();
        ReflectionTestUtils.setField(cfg, "allowedOrigins", allowedOrigins);
        ReflectionTestUtils.setField(cfg, "corsMaxAge", 3600L);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<AppSettingsService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(live);
        ReflectionTestUtils.setField(cfg, "appSettingsProvider", provider);
        return cfg;
    }

    private CorsConfiguration corsFor(WebConfig cfg, String uri) {
        CorsFilter filter = cfg.corsFilter();
        var source = (org.springframework.web.cors.CorsConfigurationSource)
                ReflectionTestUtils.getField(filter, "configSource");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        return source.getCorsConfiguration(req);
    }

    // ── CORS ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CWE-942: '*' origin ELENİR — credentials'lı istek tüm origin'lere AÇILMAZ")
    void cors_wildcardStripped() {
        WebConfig cfg = configWith("*", null);

        assertThat(corsFor(cfg, "/api/certificates")).isNull();   // tek origin '*' idi → CORS kapalı
    }

    @Test
    @DisplayName("'*' listede DİĞER origin'lerle birlikteyse yalnız o elenir, gerçekler kalır")
    void cors_wildcardStrippedFromList() {
        WebConfig cfg = configWith("https://a.akbank.com, *, https://b.akbank.com", null);

        CorsConfiguration c = corsFor(cfg, "/api/certificates");

        assertThat(c).isNotNull();
        assertThat(c.getAllowedOrigins()).containsExactly("https://a.akbank.com", "https://b.akbank.com");
        assertThat(c.getAllowCredentials()).isTrue();
    }

    @Test
    @DisplayName("CANLI ayar @Value varsayılanını EZER (çalışma anında origin değiştirilebilir)")
    void cors_liveSettingsOverrideDefaults() {
        AppSettingsService live = mock(AppSettingsService.class);
        when(live.getCsv(anyString(), anyString())).thenReturn(List.of("https://canli.akbank.com"));
        WebConfig cfg = configWith("https://eski.akbank.com", live);

        CorsConfiguration c = corsFor(cfg, "/api/certificates");

        assertThat(c.getAllowedOrigins()).containsExactly("https://canli.akbank.com");
    }

    @Test
    @DisplayName("CORS yalnız /api/** için üretilir (statik varlıklar kapsam dışı)")
    void cors_onlyForApiPaths() {
        WebConfig cfg = configWith("https://a.akbank.com", null);

        assertThat(corsFor(cfg, "/assets/index-abc.js")).isNull();
        assertThat(corsFor(cfg, "/")).isNull();
        assertThat(corsFor(cfg, "/api/x")).isNotNull();
    }

    // ── Güvenlik başlıkları + SPA cache politikası ───────────────────────────────

    private MockHttpServletResponse runSecurityFilter(String uri) throws Exception {
        OncePerRequestFilter filter = new WebConfig().securityHeadersFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return res;
    }

    @ParameterizedTest
    @CsvSource({
        "/,                    no-store",
        "/index.html,          no-store",
        "/favicon.ico,         no-store",
        "/api/certificates,    no-store",
    })
    @DisplayName("SPA kabuğu ve API ASLA cache'lenmez (bayat index.html = deploy sonrası beyaz ekran)")
    void cachePolicy_noStorePaths(String uri, String expectedFragment) throws Exception {
        assertThat(runSecurityFilter(uri).getHeader("Cache-Control")).contains(expectedFragment);
    }

    @Test
    @DisplayName("/assets/** içerik-hash'li olduğu için UZUN cache'lenir (immutable)")
    void cachePolicy_assetsImmutable() throws Exception {
        String cc = runSecurityFilter("/assets/index-abc123.js").getHeader("Cache-Control");
        assertThat(cc).contains("max-age=31536000").contains("immutable");
    }

    @Test
    @DisplayName("Public login uçları 60 sn cache'lenir (branding + hero istatistikleri)")
    void cachePolicy_publicEndpoints() throws Exception {
        assertThat(runSecurityFilter("/api/branding").getHeader("Cache-Control")).isEqualTo("public, max-age=60");
        assertThat(runSecurityFilter("/api/public-stats").getHeader("Cache-Control")).isEqualTo("public, max-age=60");
    }

    @Test
    @DisplayName("Güvenlik başlıkları her yanıtta: CSP, HSTS, frame-ancestors 'none', nosniff")
    void securityHeaders_present() throws Exception {
        MockHttpServletResponse res = runSecurityFilter("/");

        assertThat(res.getHeader("Content-Security-Policy"))
                .contains("default-src 'self'")
                .contains("frame-ancestors 'none'")
                .contains("frame-src 'self'");          // mail önizleme iframe'i çalışsın
        assertThat(res.getHeader("Strict-Transport-Security")).contains("max-age=31536000");
        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    // ── /api/login-help gövde limiti ────────────────────────────────────────────

    /**
     * Gövde 9 MB'lık gerçek byte[] ile taklit EDİLMEZ (testte 9 MB heap israfı); filtre yalnız
     * {@code getContentLengthLong()} okuduğu için o tek metot ezilir. shouldNotFilter servletPath'e
     * baktığından o da açıkça set ediliyor — MockHttpServletRequest'te varsayılanı boş string.
     */
    private int runBodyLimitFilter(String uri, long contentLength) throws Exception {
        OncePerRequestFilter filter = new WebConfig().loginHelpBodyLimitFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri) {
            @Override public long getContentLengthLong() { return contentLength; }
        };
        req.setRequestURI(uri);
        req.setServletPath(uri);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    @DisplayName("Kimliksiz /api/login-help: limit üstü gövde 413 ile reddedilir")
    void loginHelp_overLimit_rejected() throws Exception {
        assertThat(runBodyLimitFilter("/api/login-help", 9L * 1024 * 1024)).isEqualTo(413);
    }

    @Test
    @DisplayName("Limit altı gövde ve BAŞKA uçlar zincirde devam eder")
    void loginHelp_underLimit_andOtherPaths_pass() throws Exception {
        assertThat(runBodyLimitFilter("/api/login-help", 1024)).isEqualTo(200);
        assertThat(runBodyLimitFilter("/api/certificates", 9L * 1024 * 1024)).isEqualTo(200);
    }
}
