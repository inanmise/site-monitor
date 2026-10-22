package com.sitemonitor.service.cache;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.lang.Nullable;

/**
 * Önbellek veri yoluna bağlı Caffeine yöneticisi (2026-10-22, çok pod R2-A, MP-30). Her Spring önbelleğinin
 * {@code evict} / {@code evictIfPresent} / {@code clear} / {@code invalidate} çağrısı yerel boşaltmanın YANINDA
 * {@link CacheBus#bump(String...)} yapar — böylece {@code AdminController}'ın 8 {@code @CacheEvict}'i,
 * {@code InventoryInsightController}, {@code CertificateService.evictAllCaches}, {@code ThresholdPreviewService} ve elle
 * {@code getCache(..).clear()} yapan her yer çağrı yerinde değişiklik olmadan öteki pod'lara da ulaşır.
 *
 * <p>Öteki pod'un yayını geldiğinde veri yolu {@link #localEvictor(String)} ile YALNIZ yerel önbelleği boşaltır (yeniden
 * yayın yok). Bu pod'da henüz oluşmamış önbellek için iş yoktur (içinde eski veri olamaz).
 *
 * <p>Önbellekler {@link CaffeineCache} alt sınıfıdır: {@code SystemMetricSampler} ve Micrometer önbellek ölçerleri
 * ({@code instanceof CaffeineCache}) aynen çalışır. Veri yolu tekil fasulyeler kurulduktan sonra bağlanır
 * ({@link #afterSingletonsInstantiated()}); açılış öncesi boşaltmalar yayın yapmaz (yayılacak eski veri yok). Veri yolu
 * fasulyesi yoksa (dilim testleri) yönetici düz {@link CaffeineCacheManager} gibidir.
 */
public class BusAwareCacheManager extends CaffeineCacheManager implements SmartInitializingSingleton {

    private final @Nullable ObjectProvider<CacheBus> busProvider;
    private volatile CacheBus bus;

    public BusAwareCacheManager(@Nullable ObjectProvider<CacheBus> busProvider) {
        this.busProvider = busProvider;
    }

    /** Test / el kurulumu: veri yolunu doğrudan bağlar (çözücü kaydı dahil). */
    public void bind(@Nullable CacheBus b) {
        this.bus = b;
        if (b != null) b.addResolver(this::localEvictor);
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (bus != null || busProvider == null) return;
        try {
            bind(busProvider.getIfAvailable());
        } catch (RuntimeException ignored) {
            // veri yolu kurulamadı: önbellekler yerel (bugünkü davranış)
        }
    }

    @Override
    protected Cache adaptCaffeineCache(String name, com.github.benmanes.caffeine.cache.Cache<Object, Object> cache) {
        return new BusAwareCaffeineCache(name, cache, isAllowNullValues(), this);
    }

    /** Öteki pod'un yayını: bu pod'da oluşmuş önbelleği YEREL boşaltır (yeniden yayın yok); oluşmamışsa iş yok. */
    @Nullable
    Runnable localEvictor(String name) {
        if (name == null || !getCacheNames().contains(name)) return null;
        return () -> {
            Cache c = getCache(name);
            if (c instanceof BusAwareCaffeineCache bc) bc.clearLocal();
            else if (c != null) c.clear();
        };
    }

    void bump(String name) {
        CacheBus b = bus;
        if (b != null) b.bump(name);
    }

    /** Boşaltmayı veri yoluna da bildiren Caffeine önbelleği. */
    static final class BusAwareCaffeineCache extends CaffeineCache {

        private final BusAwareCacheManager owner;

        BusAwareCaffeineCache(String name, com.github.benmanes.caffeine.cache.Cache<Object, Object> cache, boolean allowNullValues,
                              BusAwareCacheManager owner) {
            super(name, cache, allowNullValues);
            this.owner = owner;
        }

        @Override
        public void evict(Object key) {
            super.evict(key);
            owner.bump(getName());
        }

        @Override
        public boolean evictIfPresent(Object key) {
            boolean present = super.evictIfPresent(key);
            owner.bump(getName());   // bu pod'da yoksa da öteki pod'larda olabilir
            return present;
        }

        @Override
        public void clear() {
            super.clear();
            owner.bump(getName());
        }

        @Override
        public boolean invalidate() {
            boolean any = super.invalidate();
            owner.bump(getName());
            return any;
        }

        /** Yalnız bu pod (öteki pod'un yayını uygulanırken). */
        void clearLocal() {
            super.clear();
        }
    }
}
