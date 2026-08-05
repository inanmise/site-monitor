package com.sitemonitor.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Caching'i ayrı bir @Configuration üzerinden etkinleştirir + per-cache TTL tanımlar.
 *
 * Neden ana sınıfta değil: Spring Boot 4'te @WebMvcTest slice'ı cache
 * otokonfigürasyonunu dahil etmez. @EnableCaching ana uygulama sınıfında olursa
 * web-slice testleri de onu işleyip CacheManager arar ve context yüklenemez.
 * Bu @Configuration web slice tarafından yüklenmediğinden (yalnız tam uygulama
 * bağlamı yükler) caching prod'da aynen çalışır, slice testleri temiz kalır.
 *
 * Bu CacheManager bean'i, otokonfigürasyondaki tek-spec Caffeine yerine per-cache
 * TTL sağlar (ConditionalOnMissingBean → auto-config geri çekilir; application.properties'teki
 * spring.cache.caffeine.spec artık kullanılmaz).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Cert dashboard cache'leri: dashboard 5 dk'da (300 sn) yenileniyor → TTL'i buna hizala.
     * Eski 60 sn ≪ 300 sn poll aralığı → cache HER yenilemede soğuk (thrash): 100 kullanıcı aynı
     * anda expired key'e binip metodu 100× çalıştırıyordu. 300 sn'de cache yenilemeler arası sıcak
     * kalır. Diğer cache'ler (permission/settings/… — tazelik önemli) varsayılan 60 sn'de kalır.
     */
    private static final List<String> LONG_TTL_CACHES =
            List.of("cert-latest", "cert-stats", "cert-warnings", "renewal-advice", "teamNames",
                    // failure-domains: 7 günlük notification_logs taraması, dashboard'da 5 dk'da bir
                    // 100 kullanıcı çağırıyor → 300 sn TTL ile tur başına 1 tarama (bkz. ExtendedHealthService).
                    "failure-domains",
                    // monitoring-weekly-stats: istek başına ~15 gruplu/scan sorgu (domain_checks GROUP-BY
                    // dahil) → 300 sn TTL; geçmiş haftalar statik (bkz. MonitoringWeeklyStatsService, F6).
                    "monitoring-weekly-stats");

    private static final Duration LONG_TTL    = Duration.ofSeconds(300);
    private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);
    private static final long      MAX_SIZE   = 1000;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager mgr = new CaffeineCacheManager();
        // Varsayılan spec: isimli-özel olmayan tüm cache'ler 60 sn / 1000 (eski davranış).
        mgr.setCaffeine(Caffeine.newBuilder().recordStats().maximumSize(MAX_SIZE).expireAfterWrite(DEFAULT_TTL));
        // Cert dashboard cache'leri: uzun TTL (yenileme aralığıyla hizalı).
        for (String name : LONG_TTL_CACHES) {
            mgr.registerCustomCache(name,
                    Caffeine.newBuilder().recordStats().maximumSize(MAX_SIZE).expireAfterWrite(LONG_TTL).build());
        }
        return mgr;
    }
}
