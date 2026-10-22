package com.sitemonitor.config;

import com.sitemonitor.service.AppSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WebConfig} çekirdek fabrikalarının dalları (2026-10-22, çok pod R1 — JaCoCo sınıf tabanı 0.98): kapanışta reddedilen
 * kontrol işi çalıştırılmaz ve sayılmaz, bildirim havuzu dolunca iş çağıranda koşar, giriş postası havuzu kapanışta bekler,
 * CORS kaynağı ayardan / {@code @Value}'dan okur ve joker kökeni eler, kesiciler yalnız {@code /api/**}'de.
 */
class WebConfigBeansTest {

    @Test
    @DisplayName("certCheckExecutor kapandıktan sonra reddedilen görev ÇALIŞTIRILMAZ ve çağıranda koşma sayılmaz (WARN)")
    void certCheckExecutor_rejectedAfterShutdown_isDroppedNotCounted() {
        WebConfig cfg = new WebConfig();
        ReflectionTestUtils.setField(cfg, "executorCoreSize", 1);
        ReflectionTestUtils.setField(cfg, "executorMaxSize", 1);
        ReflectionTestUtils.setField(cfg, "executorQueueCapacity", 1);
        ThreadPoolTaskExecutor ex = cfg.certCheckExecutor();
        ex.shutdown();
        long before = WebConfig.CALLER_RUNS.get();
        AtomicInteger ran = new AtomicInteger();
        ThreadPoolExecutor tpe = ex.getThreadPoolExecutor();
        tpe.getRejectedExecutionHandler().rejectedExecution(ran::incrementAndGet, tpe);
        assertThat(ran.get()).isZero();
        assertThat(WebConfig.CALLER_RUNS.get()).isEqualTo(before);
    }

    @Test
    @DisplayName("notifyExecutor: havuz + kuyruk doluyken bildirim görevi KAYBOLMAZ — çağıran iş parçacığında koşar")
    void notifyExecutor_fullRunsOnCaller() throws Exception {
        ThreadPoolTaskExecutor ex = new WebConfig().notifyExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            ThreadPoolExecutor tpe = ex.getThreadPoolExecutor();
            Thread caller = Thread.currentThread();
            Thread[] ranOn = new Thread[1];
            tpe.getRejectedExecutionHandler().rejectedExecution(() -> ranOn[0] = Thread.currentThread(), tpe);
            assertThat(ranOn[0]).as("kapanmamış havuzda red = çağıranda koş").isSameAs(caller);
            assertThat(ex).isInstanceOf(WebConfig.DeadlineAwareTaskExecutor.class);
        } finally {
            release.countDown();
            ex.shutdown();
        }
    }

    @Test
    @DisplayName("loginIssueMailExecutor: kapanışta kuyruğu bekler (ortak süreye bağlı), dolunca çağıranda koşar")
    void loginIssueMailExecutor_settings() throws Exception {
        ThreadPoolTaskExecutor ex = new WebConfig().loginIssueMailExecutor();
        try {
            assertThat(ex).isInstanceOf(WebConfig.DeadlineAwareTaskExecutor.class);
            assertThat(ex.getCorePoolSize()).isEqualTo(2);
            assertThat(ex.getMaxPoolSize()).isEqualTo(4);
            assertThat(ex.getQueueCapacity()).isEqualTo(100);
            assertThat(ex.getThreadNamePrefix()).isEqualTo("login-mail-");
            assertThat(ex.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
            CountDownLatch done = new CountDownLatch(1);
            ex.execute(done::countDown);
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            ex.shutdown();
        }
    }

    @Test
    @DisplayName("mdcTaskExecutorCustomizer Boot havuzuna MDC aktarıcısını takar")
    void mdcCustomizerSetsDecorator() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        new WebConfig().mdcTaskExecutorCustomizer().customize(ex);
        assertThat(ReflectionTestUtils.getField(ex, "taskDecorator")).isInstanceOf(MdcTaskDecorator.class);
    }

    @SuppressWarnings("unchecked")
    private static WebConfig corsConfig(AppSettingsService settings, String fallback) {
        WebConfig cfg = new WebConfig();
        ObjectProvider<AppSettingsService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(settings);
        ReflectionTestUtils.setField(cfg, "appSettingsProvider", provider);
        ReflectionTestUtils.setField(cfg, "allowedOrigins", fallback);
        ReflectionTestUtils.setField(cfg, "corsMaxAge", 600L);
        return cfg;
    }

    private static CorsConfiguration cors(WebConfig cfg, String uri) {
        CorsFilter f = cfg.corsFilter();
        CorsConfigurationSource src = (CorsConfigurationSource) ReflectionTestUtils.getField(f, "configSource");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        if (uri == null) req.setRequestURI(null);
        return src.getCorsConfiguration(req);
    }

    @Test
    @DisplayName("CORS: yalnız /api/**; ayar servisi yoksa @Value listesi (boşluk / boş öğe atılır); '*' elenir, hepsi elenirse CORS kapalı")
    void corsFromValueFallback() {
        WebConfig cfg = corsConfig(null, " http://a.example , ,http://b.example,*");
        assertThat(cors(cfg, "/index.html")).isNull();
        assertThat(cors(cfg, null)).isNull();
        CorsConfiguration c = cors(cfg, "/api/x");
        assertThat(c).isNotNull();
        assertThat(c.getAllowedOrigins()).containsExactly("http://a.example", "http://b.example");
        assertThat(c.getAllowCredentials()).isTrue();
        assertThat(c.getMaxAge()).isEqualTo(600L);
        assertThat(c.getAllowedMethods()).contains("GET", "POST", "PUT", "DELETE", "OPTIONS");
        // Çok pod (2026-10-22, R2-A): yazdığını-oku kuşağı çapraz kökende de gönderilip okunabilir
        assertThat(c.getAllowedHeaders()).contains("X-Lang", "X-Cache-Min-Gen");
        assertThat(c.getExposedHeaders()).containsExactly("X-Cache-Gen");

        assertThat(cors(corsConfig(null, "*"), "/api/x")).as("yalnız joker → kapalı").isNull();
        assertThat(cors(corsConfig(null, null), "/api/x")).as("hiç köken yok").isNull();
    }

    @Test
    @DisplayName("CORS: ayar servisi varsa kökenler CANLI ondan okunur")
    void corsFromSettings() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getCsv(anyString(), anyString())).thenReturn(List.of("https://ui.example", "", "*"));
        CorsConfiguration c = cors(corsConfig(s, "http://unused.example"), "/api/me");
        assertThat(c).isNotNull();
        assertThat(c.getAllowedOrigins()).containsExactly("https://ui.example");
    }

    @Test
    @DisplayName("kesiciler /api/**'e bağlanır; köken denetimi filtresi kurulur; yeniden doğrulanabilir statik: null değil")
    void interceptorsAndFilters() {
        WebConfig cfg = corsConfig(null, "http://a.example");
        ReflectionTestUtils.setField(cfg, "authInterceptor", mock(com.sitemonitor.config.AuthInterceptor.class));
        ReflectionTestUtils.setField(cfg, "httpMetricsInterceptor", mock(com.sitemonitor.config.HttpMetricsInterceptor.class));
        InterceptorRegistry registry = new InterceptorRegistry();
        cfg.addInterceptors(registry);
        @SuppressWarnings("unchecked")
        List<Object> regs = (List<Object>) ReflectionTestUtils.getField(registry, "registrations");
        assertThat(regs).hasSize(2);
        assertThat(cfg.originCheckFilter()).isNotNull();
        assertThat(cfg.loginHelpBodyLimitFilter()).isNotNull();
        assertThat(WebConfig.isRevalidatableStatic(null)).isFalse();
    }
}
