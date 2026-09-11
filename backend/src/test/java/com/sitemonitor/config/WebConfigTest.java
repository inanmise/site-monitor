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

    private static String group(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertThat(m.find()).as("desen bulunmalı: " + regex).isTrue();
        return m.group(1);
    }
}
