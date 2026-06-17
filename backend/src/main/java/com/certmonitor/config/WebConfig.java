package com.certmonitor.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
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

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.isBlank()
                ? new String[0]
                : allowedOrigins.split(",");

        if (origins.length > 0) {
            registry.addMapping("/api/**")
                    .allowedOrigins(origins)
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .allowedHeaders("Content-Type", "X-Requested-With")
                    .allowCredentials(true)
                    .maxAge(corsMaxAge);
        }
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
                // No caching for API responses
                if (req.getRequestURI().startsWith("/api/")) {
                    res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
                    res.setHeader("Pragma", "no-cache");
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
        executor.initialize();
        return executor;
    }
}
