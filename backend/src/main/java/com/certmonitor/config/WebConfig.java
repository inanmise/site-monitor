package com.certmonitor.config;

import com.certmonitor.service.AppSettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // @Lazy: WebConfig aynı zamanda certCheckExecutor (AsyncTaskExecutor) tanımlar.
    // Spring Boot 4'te JPA EntityManagerFactoryBuilder, bootstrap executor'ı çözerken
    // bu @Configuration'ı örnekler; interceptor'lar eager enjekte edilirse
    // WebConfig → authInterceptor → rememberMeService → JPA repo → EMF dairesel
    // bağımlılığı oluşur (Spring 7 artık reddediyor). Lazy proxy ile döngü kırılır;
    // gerçek bean'ler addInterceptors() çağrılırken (context refresh sonrası) çözülür.
    @Autowired @Lazy
    private AuthInterceptor authInterceptor;

    @Autowired @Lazy
    private HttpMetricsInterceptor httpMetricsInterceptor;

    // CORS origin'leri istek anında AppSettingsService'ten (Genel Ayarlar) CANLI okunur.
    // ObjectProvider: bean yoksa (örn. @WebMvcTest slice'ı) hata vermez, @Value fallback'ine düşer;
    // ayrıca EMF bootstrap sırasındaki dairesel bağımlılığı önler (lazy resolve).
    @Autowired
    private ObjectProvider<AppSettingsService> appSettingsProvider;

    @Value("${cert.monitor.cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
    private String allowedOrigins;

    @Value("${cert.monitor.cors.max-age-seconds:3600}")
    private long corsMaxAge;

    @Value("${cert.monitor.executor.core-size:20}")
    private int executorCoreSize;

    @Value("${cert.monitor.executor.max-size:50}")
    private int executorMaxSize;

    @Value("${cert.monitor.executor.queue-capacity:100}")
    private int executorQueueCapacity;

    /**
     * CORS — statik addCorsMappings yerine istek-anında AppSettingsService'ten origin
     * okuyan CorsFilter. Genel Ayarlar'dan origin değişimi yeni isteklerde CANLI yansır
     * (yeniden başlatma gerekmez). /api/** ile sınırlı; preflight'ı (OPTIONS) filtre yanıtlar.
     */
    @Bean
    public CorsFilter corsFilter() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource() {
            @Override
            public CorsConfiguration getCorsConfiguration(HttpServletRequest request) {
                if (request.getRequestURI() == null || !request.getRequestURI().startsWith("/api/")) return null;
                AppSettingsService s = appSettingsProvider.getIfAvailable();
                List<String> origins = (s != null)
                        ? s.getCsv("cert.monitor.cors.allowed-origins", allowedOrigins)
                        : csv(allowedOrigins);
                if (origins.isEmpty()) return null;
                CorsConfiguration c = new CorsConfiguration();
                c.setAllowedOrigins(origins);
                c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                c.setAllowedHeaders(List.of("Content-Type", "X-Requested-With"));
                c.setAllowCredentials(true);
                c.setMaxAge(corsMaxAge);
                return c;
            }
        };
        return new CorsFilter(source);
    }

    /** CSV → trimlenmiş, boşsuz liste (AppSettingsService yokken @Value fallback'i için). */
    private static List<String> csv(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String p : raw.split(",")) {
                String t = p.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(httpMetricsInterceptor).addPathPatterns("/api/**");
    }

    @Bean
    public OncePerRequestFilter securityHeadersFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req,
                                            HttpServletResponse res,
                                            FilterChain chain) throws ServletException, IOException {
                res.setHeader("X-Content-Type-Options", "nosniff");
                res.setHeader("X-Frame-Options", "DENY");
                res.setHeader("X-XSS-Protection", "0"); // disabled — CSP is the modern defence
                res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
                res.setHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=()");
                // HSTS — 1 year, includes subdomains, preload-ready
                res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
                // CSP — React SPA + same-origin API
                res.setHeader("Content-Security-Policy",
                        "default-src 'self'; script-src 'self' 'unsafe-inline'; " +
                        "style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
                        "connect-src 'self'; font-src 'self'; frame-ancestors 'none'; " +
                        "base-uri 'self'; form-action 'self'");
                // ── Cache politikası (SPA) ──────────────────────────────────────
                // API yanıtları hiç cache'lenmez.
                // /assets/** Vite tarafından İÇERİK-HASH'li üretilir (örn. index-<hash>.js) →
                //   güvenle uzun süre cache'lenir (yeni build = yeni dosya adı = yeni URL).
                // Diğer her şey (index.html SPA kabuğu, "/", favicon) ASLA cache'lenmez; böylece
                //   her deploy'da taze index.html çekilir ve GÜNCEL asset hash'lerine işaret eder.
                //   Aksi halde eski cache'li index.html artık var olmayan eski bundle'ı (404)
                //   çağırır → React mount olamaz → BEYAZ EKRAN. (prod'da cache.period set değil,
                //   bu yüzden framework Cache-Control yazmaz; buradaki header otoritedir.)
                String uri = req.getRequestURI();
                if (uri.equals("/api/branding") || uri.equals("/api/public-stats")) {
                    // Public login-sayfası endpoint'leri (branding + hero istatistikleri) — 60 sn
                    // cache'lenebilir; canlı değişim en geç 1 dk'da yansır (ETag yok/gerekmiyor).
                    res.setHeader("Cache-Control", "public, max-age=60");
                } else if (uri.startsWith("/api/")) {
                    res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                    res.setHeader("Pragma", "no-cache");
                } else if (uri.startsWith("/assets/")) {
                    res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
                } else {
                    res.setHeader("Cache-Control", "no-store, must-revalidate");
                }
                chain.doFilter(req, res);
            }
        };
    }

    @Bean(name = "certCheckExecutor")
    public ThreadPoolTaskExecutor certCheckExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(executorCoreSize);
        executor.setMaxPoolSize(executorMaxSize);
        executor.setQueueCapacity(executorQueueCapacity);
        executor.setThreadNamePrefix("cert-check-");
        // Tek-pod CPU koruması: havuz+queue dolarsa görevi REDDETME (sweep'i kırma) — çağıran
        // (scheduler) thread'inde çalıştır. Böylece büyük ölçekte (1000 domain) tarama geri-basınçla
        // yavaşlar ama kullanıcı isteklerini aç bırakacak kadar thread açmaz + RejectedExecutionException
        // riski biter. Bu sayede max havuz güvenle küçültülebilir (values.yaml executorMaxSize).
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
