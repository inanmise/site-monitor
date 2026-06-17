package com.certmonitor.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Caching'i ayrı bir @Configuration üzerinden etkinleştirir.
 *
 * Neden ana sınıfta değil: Spring Boot 4'te @WebMvcTest slice'ı cache
 * otokonfigürasyonunu dahil etmez. @EnableCaching ana uygulama sınıfında olursa
 * web-slice testleri de onu işleyip CacheManager arar ve context yüklenemez.
 * Bu @Configuration web slice tarafından yüklenmediğinden (yalnız tam uygulama
 * bağlamı yükler) caching prod'da aynen çalışır, slice testleri temiz kalır.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
