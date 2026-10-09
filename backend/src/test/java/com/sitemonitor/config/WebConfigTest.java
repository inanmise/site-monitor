package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * certCheckExecutor boyutları enjekte edilen değerlerden gelir; taşma çağıranda koşar ve SAYILIR
 * (Görev Kuyruğu kartı). Ayrıca satır-içi varsayılanlar application.properties ile aynı olmalı —
 * 2026-09-10'a kadar kuyruk burada 100, properties'te 1000'di (properties yüklenmeyen bağlamda
 * sessizce küçük kuyruk).
 */
class WebConfigTest {

    @Test
    @DisplayName("certCheckExecutor enjekte edilen core/max/kuyruk boyutlarını taşır")
    void executor_usesInjectedSizes() {
        WebConfig cfg = new WebConfig();
        ReflectionTestUtils.setField(cfg, "executorCoreSize", 3);
        ReflectionTestUtils.setField(cfg, "executorMaxSize", 7);
        ReflectionTestUtils.setField(cfg, "executorQueueCapacity", 5000);
        ThreadPoolTaskExecutor ex = cfg.certCheckExecutor();
        try {
            assertThat(ex.getCorePoolSize()).isEqualTo(3);
            assertThat(ex.getMaxPoolSize()).isEqualTo(7);
            assertThat(ex.getQueueCapacity()).isEqualTo(5000);
        } finally {
            ex.shutdown();
        }
    }

    @Test
    @DisplayName("Havuz + kuyruk dolunca görev çağıranda koşar ve CALLER_RUNS sayacı artar (CallerRuns semantiği korunur)")
    void overflow_runsOnCallerAndCounts() throws Exception {
        WebConfig cfg = new WebConfig();
        ReflectionTestUtils.setField(cfg, "executorCoreSize", 1);
        ReflectionTestUtils.setField(cfg, "executorMaxSize", 1);
        ReflectionTestUtils.setField(cfg, "executorQueueCapacity", 1);
        ThreadPoolTaskExecutor ex = cfg.certCheckExecutor();
        long before = WebConfig.CALLER_RUNS.get();
        CountDownLatch release = new CountDownLatch(1);
        try {
            ex.execute(() -> { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignore) { } });   // havuzu doldur
            ex.execute(() -> { });                                                                                        // kuyruğu doldur
            Thread caller = Thread.currentThread();
            Thread[] ranOn = new Thread[1];
            ex.execute(() -> ranOn[0] = Thread.currentThread());                                                          // taşma → çağıranda
            assertThat(ranOn[0]).isSameAs(caller);
            assertThat(WebConfig.CALLER_RUNS.get()).isEqualTo(before + 1);
        } finally {
            release.countDown();
            ex.shutdown();
        }
    }

    @Test
    @DisplayName("Satır-içi @Value varsayılanları application.properties ile birebir (core/max/kuyruk)")
    void inlineDefaults_matchProperties() throws Exception {
        String java = Files.readString(Path.of("src/main/java/com/sitemonitor/config/WebConfig.java"));
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        for (String key : new String[]{"core-size", "max-size", "queue-capacity"}) {
            String inJava = group(java, "site\\.monitor\\.executor\\." + key + ":(\\d+)");
            String env = "EXECUTOR_" + key.toUpperCase().replace('-', '_').replace("SIZE", "SIZE");
            String inProps = group(props, "site\\.monitor\\.executor\\." + key + "=\\$\\{" + env + ":(\\d+)\\}");
            assertThat(inJava).as("WebConfig " + key).isEqualTo(inProps);
        }
    }

    /**
     * 2026-09-10: /api/branding ve /api/public-stats "public, max-age=60" idi; NetScaler bunu kendi
     * TTL'siyle sakladı, test ortamında dağıtımdan sonra giriş sayfası dakikalarca eski sürümü
     * gösterdi. İki uç da artık diğer /api yanıtları gibi no-store; /assets/** immutable kalır,
     * SPA kabuğu no-store kalır. Biri "public" ile geri gelirse bu test kırmızı.
     */
    @Test
    @DisplayName("Cache-Control: hiçbir /api yanıtı (branding/public-stats dâhil) paylaşımlı önbelleğe saklanamaz")
    void publicEndpoints_areNoStore() throws Exception {
        WebConfig cfg = new WebConfig();
        var filter = cfg.securityHeadersFilter();
        for (String uri : new String[]{"/api/branding", "/api/public-stats", "/api/me", "/api/admin/system",
                "/api/system/version", "/api/system/releases", "/api/admin/deployments"}) {
            var req = new org.springframework.mock.web.MockHttpServletRequest("GET", uri);
            var res = new org.springframework.mock.web.MockHttpServletResponse();
            filter.doFilter(req, res, new org.springframework.mock.web.MockFilterChain());
            String cc = res.getHeader("Cache-Control");
            assertThat(cc).as(uri).contains("no-store").doesNotContain("public").doesNotContain("max-age");
        }
        var asset = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("GET", "/assets/index-abc123.js"),
                asset, new org.springframework.mock.web.MockFilterChain());
        assertThat(asset.getHeader("Cache-Control")).contains("immutable");
        var shell = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("GET", "/"),
                shell, new org.springframework.mock.web.MockFilterChain());
        assertThat(shell.getHeader("Cache-Control")).contains("no-store");
    }

    /**
     * 2026-10-09 (performans): marka görselleri, yazı tipleri, simgeler ve teknik doküman PDF'leri yeniden doğrulanarak
     * önbelleğe alınır ("no-cache" → 304); SPA kabuğu ve index.html no-store KALIR; "public"/"max-age" yazılmaz.
     */
    @Test
    @DisplayName("Cache-Control: marka/yazı tipi/simge/PDF no-cache (304 ile yeniden doğrulama); kabuk no-store")
    void staticBrandFontsPdf_revalidate() throws Exception {
        var filter = new WebConfig().securityHeadersFilter();
        for (String uri : new String[]{"/brand/logo-ok-64.png", "/fonts/Roboto-Regular.ttf", "/favicon.ico", "/favicon.svg",
                "/favicon-32.png", "/apple-touch-icon.png", "/icon-192.png", "/site.webmanifest", "/whitepaper.tr.pdf", "/whitepaper.en.pdf"}) {
            var res = new org.springframework.mock.web.MockHttpServletResponse();
            filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("GET", uri), res, new org.springframework.mock.web.MockFilterChain());
            assertThat(res.getHeader("Cache-Control")).as(uri).isEqualTo("no-cache");
        }
        for (String uri : new String[]{"/", "/index.html", "/foo", "/brandx/a.png", "/x/whitepaper.tr.pdf", "/other.pdf"}) {
            var res = new org.springframework.mock.web.MockHttpServletResponse();
            filter.doFilter(new org.springframework.mock.web.MockHttpServletRequest("GET", uri), res, new org.springframework.mock.web.MockFilterChain());
            assertThat(res.getHeader("Cache-Control")).as(uri).contains("no-store");
        }
    }

    /** 2026-10-09: Boot 4 / Tomcat 11 .js'yi text/javascript sunar — sıkıştırma listesi bunu içermezse JS sıkıştırılmaz. */
    @Test
    @DisplayName("sıkıştırma: text/javascript, CSS, JSON, SVG ve webmanifest listede")
    void compressionCoversJavascript() throws Exception {
        java.util.Properties props = new java.util.Properties();
        try (var in = getClass().getResourceAsStream("/application.properties")) {
            props.load(in);
        }
        // test kaynaklarında aynı ad varsa ana dosyayı doğrudan oku
        java.nio.file.Path main = java.nio.file.Path.of("src/main/resources/application.properties");
        if (java.nio.file.Files.exists(main)) {
            props = new java.util.Properties();
            try (var in = java.nio.file.Files.newInputStream(main)) { props.load(in); }
        }
        assertThat(props.getProperty("server.compression.enabled")).isEqualTo("true");
        assertThat(java.util.List.of(props.getProperty("server.compression.mime-types").split(",")))
                .contains("text/javascript", "application/javascript", "text/css", "application/json", "image/svg+xml", "application/manifest+json");
    }

    /**
     * CSP (2026-10-01, onaylı öneri 5): betik yalnız aynı kaynaktan; satır içi betik ve olay işleyicisi izni YOK.
     * Stil tarafındaki 'unsafe-inline' bilerek kalır (shadcn Chart &lt;style&gt; enjekte eder, mail önizlemeleri).
     */
    @Test
    @DisplayName("CSP: script-src 'self' — 'unsafe-inline' ve 'unsafe-eval' yok; çerçeveleme kapalı")
    void csp_scriptSrc_hasNoUnsafeInline() throws Exception {
        var res = new org.springframework.mock.web.MockHttpServletResponse();
        new WebConfig().securityHeadersFilter().doFilter(
                new org.springframework.mock.web.MockHttpServletRequest("GET", "/"), res,
                new org.springframework.mock.web.MockFilterChain());
        String csp = res.getHeader("Content-Security-Policy");
        String scriptSrc = group(csp, "script-src ([^;]*);");
        assertThat(scriptSrc.trim()).isEqualTo("'self'");
        assertThat(csp).doesNotContain("unsafe-eval").contains("frame-ancestors 'none'");
    }

    private static String group(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertThat(m.find()).as("desen bulunmalı: " + regex).isTrue();
        return m.group(1);
    }
}
