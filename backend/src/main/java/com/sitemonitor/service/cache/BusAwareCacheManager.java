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

    /**
     * Boşaltmayı veri yoluna da bildiren Caffeine önbelleği.
     *
     * <p><b>Kuşak damgalı değerler (çok pod R2-A inceleme C-1 / D-1).</b> Her değer, yüklemesinin BAŞLADIĞI kuşakla saklanır
     * ({@link Stamped}); her boşaltma (yerel ya da öteki pod'un yayını) kuşağı artırır. Okunan değerin damgası güncel kuşaktan
     * eskiyse o değer bir ISKALAMADIR: kaldırılır ve BİR kez yeniden yüklenir. Böylece öteki pod'un yayını uygulandıktan sonra
     * gelen bir GET, boşaltmadan ÖNCE başlamış bir {@code sync = true} yüklemeye katılsa bile eski listeyi almaz (eskiden yalnız
     * saklanması önleniyordu). Öteki pod'un yayını ({@link #clearLocal()}) yalnız kuşağı artırır — doğruluk için yeter — ve
     * fiziksel temizliği kısa ömürlü bir arka plan işine bırakır: yeniden hesaplanan bir girdi Caffeine'in kutu kilidini
     * tutarken veri yolu iş parçacığı beklemez. Yerel {@code clear()} (istek iş parçacığındaki {@code @CacheEvict}) eskisi
     * gibi hemen temizler.
     */
    static final class BusAwareCaffeineCache extends CaffeineCache {

        /** Değer + yüklemesinin başladığı kuşak. */
        record Stamped(long generation, Object value) { }

        private final BusAwareCacheManager owner;
        private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

        BusAwareCaffeineCache(String name, com.github.benmanes.caffeine.cache.Cache<Object, Object> cache, boolean allowNullValues,
                              BusAwareCacheManager owner) {
            super(name, cache, allowNullValues);
            this.owner = owner;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Object key, java.util.concurrent.Callable<T> valueLoader) {
            com.github.benmanes.caffeine.cache.Cache<Object, Object> nc = getNativeCache();
            for (int attempt = 0; ; attempt++) {
                Object raw = nc.get(key, k -> {
                    long started = generation.get();
                    Object v;
                    try {
                        v = valueLoader.call();
                    } catch (Exception ex) {
                        throw new ValueRetrievalException(key, valueLoader, ex);
                    }
                    return new Stamped(started, toStoreValue(v));
                });
                if (!(raw instanceof Stamped st)) return (T) fromStoreValue(raw);
                if (st.generation() == generation.get()) return (T) fromStoreValue(st.value());
                nc.asMap().remove(key, raw);   // boşaltmadan önce başlamış yükleme: ıskalama
                if (attempt >= 1) return (T) fromStoreValue(st.value());   // en çok bir yeniden yükleme (döngü yok)
            }
        }

        @Override
        protected Object lookup(Object key) {
            com.github.benmanes.caffeine.cache.Cache<Object, Object> nc = getNativeCache();
            Object raw = nc.getIfPresent(key);
            if (!(raw instanceof Stamped st)) return raw;
            if (st.generation() == generation.get()) return st.value();
            nc.asMap().remove(key, raw);
            return null;
        }

        @Override
        public void put(Object key, @Nullable Object value) {
            getNativeCache().put(key, new Stamped(generation.get(), toStoreValue(value)));
        }

        @Override
        public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
            Object existing = getNativeCache().asMap().putIfAbsent(key, new Stamped(generation.get(), toStoreValue(value)));
            if (existing == null) return null;
            return toValueWrapper(existing instanceof Stamped st ? st.value() : existing);
        }

        @Override
        public void evict(Object key) {
            generation.incrementAndGet();
            super.evict(key);
            owner.bump(getName());
        }

        @Override
        public boolean evictIfPresent(Object key) {
            generation.incrementAndGet();
            boolean present = super.evictIfPresent(key);
            owner.bump(getName());   // bu pod'da yoksa da öteki pod'larda olabilir
            return present;
        }

        @Override
        public void clear() {
            generation.incrementAndGet();
            super.clear();
            owner.bump(getName());
        }

        @Override
        public boolean invalidate() {
            generation.incrementAndGet();
            boolean any = super.invalidate();
            owner.bump(getName());
            return any;
        }

        /**
         * Yalnız bu pod (öteki pod'un yayını uygulanırken; veri yolu iş parçacığında): kuşak artar — eski damgalı her değer artık
         * ıskalamadır — ve fiziksel temizlik arka plana bırakılır; bu çağrı Caffeine kilidini BEKLEMEZ.
         */
        void clearLocal() {
            long cutoff = generation.incrementAndGet();
            com.github.benmanes.caffeine.cache.Cache<Object, Object> nc = getNativeCache();
            try {
                // Yalnız boşaltmadan ÖNCE damgalanmış girdiler silinir — arada yüklenmiş taze değer kalır
                java.util.concurrent.ForkJoinPool.commonPool().execute(() -> {
                    for (java.util.Map.Entry<Object, Object> e : nc.asMap().entrySet()) {
                        Object v = e.getValue();
                        if (!(v instanceof Stamped st) || st.generation() < cutoff) nc.asMap().remove(e.getKey(), v);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // havuz yok: eski girdiler okununca / TTL ile düşer (doğruluk kuşaktan)
            }
        }

        /** Test kancası: güncel kuşak. */
        long generation() {
            return generation.get();
        }
    }
}
