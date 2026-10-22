package com.sitemonitor.service.cache;

import com.sitemonitor.config.CacheConfig;
import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring önbellekleri veri yoluna bağlı (2026-10-22, çok pod R2-A, MP-30): {@code @CacheEvict} / {@code clear()} pod A'da →
 * pod B'nin aynı adlı önbelleği bir yoklamada boşalır; B'nin boşaltması yeniden yayın yapmaz; boşaltma dinleyicisi
 * ({@code CertificateService} ısıtıcı kancası) adları alır. Önbellekler {@link CaffeineCache} kalır (ölçerler).
 */
class BusAwareCacheManagerTest {

    MultiPodTestSupport mp;
    DataSourceTransactionManager txm;
    final List<CacheBus> buses = new ArrayList<>();
    final List<AnnotationConfigApplicationContext> contexts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        mp.jdbc().execute(CacheBus.DDL);
        txm = new DataSourceTransactionManager(mp.dataSource());
    }

    @AfterEach
    void tearDown() {
        contexts.forEach(AnnotationConfigApplicationContext::close);
        for (CacheBus b : buses) b.stop(false);
        mp.close();
    }

    CacheBus bus() {
        CacheBus b = new CacheBus(mp.jdbc(), txm);
        buses.add(b);
        b.pollNow();
        return b;
    }

    static BusAwareCacheManager manager(CacheBus bus) {
        BusAwareCacheManager m = new BusAwareCacheManager(null);
        m.bind(bus);
        return m;
    }

    long gen(String name) {
        List<Long> l = mp.jdbc().queryForList("SELECT generation FROM cache_bus WHERE name = ?", Long.class, name);
        return l.isEmpty() ? 0 : l.get(0);
    }

    @Test
    @DisplayName("clear / evict pod A'da → pod B'nin önbelleği boşalır; B yeniden yayın yapmaz; A kendi önbelleğini ikinci kez boşaltmaz")
    void clearOnA_emptiesB() {
        CacheBus busA = bus(), busB = bus();
        BusAwareCacheManager a = manager(busA), b = manager(busB);
        List<Set<String>> remoteOnB = new CopyOnWriteArrayList<>();
        busB.onRemoteEvict(remoteOnB::add);
        Cache ca = a.getCache("card-extras"), cb = b.getCache("card-extras");
        ca.put("all", "A");
        cb.put("all", "B-eski");

        ca.clear();
        assertThat(ca.get("all")).isNull();
        assertThat(cb.get("all")).as("yoklamadan önce B eski").isNotNull();
        busA.flushPending();
        busB.pollNow();
        assertThat(cb.get("all")).as("B boşaldı").isNull();
        assertThat(remoteOnB).containsExactly(Set.of("card-extras"));
        assertThat(gen("card-extras")).as("B'nin boşaltması yeniden yayın değil").isEqualTo(1);

        ca.put("all", "A-yeni");
        busA.pollNow();
        assertThat(ca.get("all")).as("A kendi yayınını yeniden uygulamaz (ısıtılmış değer kalır)").isNotNull();

        cb.put("k", "v");
        ca.evict("k");             // tek anahtar: öteki pod'da tüm önbellek boşalır (anahtar yolda taşınmaz)
        busA.flushPending();
        busB.pollNow();
        assertThat(cb.get("k")).isNull();
        ca.evictIfPresent("yok");   // bu pod'da yok — öteki pod'larda olabilir: yine yayın
        ca.invalidate();
        busA.flushPending();
        assertThat(gen("card-extras")).as("clear + evict + (evictIfPresent ∪ invalidate tek birleştirilmiş yayın)").isEqualTo(3);
    }

    @Test
    @DisplayName("Bu pod'da hiç oluşmamış önbellek: iş yok (çözücü null); önbellekler CaffeineCache (SystemMetricSampler ölçer)")
    void unknownCache_noWork_instanceOfCaffeine() {
        CacheBus busA = bus(), busB = bus();
        BusAwareCacheManager a = manager(busA), b = manager(busB);
        a.getCache("audit-stats").clear();
        busA.flushPending();
        busB.pollNow();
        assertThat(b.getCacheNames()).as("B'de önbellek oluşturulmadı").doesNotContain("audit-stats");
        assertThat(b.localEvictor("audit-stats")).isNull();
        assertThat(a.getCache("cert-latest")).isInstanceOf(CaffeineCache.class);
    }

    @Test
    @DisplayName("Yarış: sync yükleme sürerken öteki pod'un yayını önbelleği boşaltırsa yüklenen (eski) değer saklanmaz")
    void syncLoadRacingRemoteEvict_isNotStored() throws Exception {
        BusAwareCacheManager m = manager(bus());
        Cache c = m.getCache("cert-latest");
        java.util.concurrent.CountDownLatch loading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch evicted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<String> f = ex.submit(() -> c.get("all", () -> {
                loading.countDown();
                assertThat(evicted.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return "eski";
            }));
            assertThat(loading.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            m.localEvictor("cert-latest").run();   // öteki pod'un yayını (yerel boşaltma)
            evicted.countDown();
            assertThat(f.get(5, java.util.concurrent.TimeUnit.SECONDS)).as("çağıran yine sonucu alır").isEqualTo("eski");
        } finally {
            ex.shutdownNow();
        }
        assertThat(c.get("all")).as("yarışan yükleme saklanmadı").isNull();
        assertThat(c.get("all", () -> "taze")).isEqualTo("taze");
        assertThat(c.get("all", () -> "yine")).as("yarışsız yükleme saklandı").isEqualTo("taze");
    }

    @Test
    @DisplayName("Veri yolu yokken yönetici düz Caffeine gibi (dilim testleri)")
    void noBus_plainCaffeine() {
        BusAwareCacheManager m = new BusAwareCacheManager(null);
        m.afterSingletonsInstantiated();
        Cache c = m.getCache("cert-stats");
        c.put("x", 1);
        c.clear();
        assertThat(c.get("x")).isNull();
        assertThat(gen(CacheBus.GLOBAL)).isZero();
    }

    // ── Gerçek Spring vekili: iki "pod" bağlamı, aynı veritabanı ─────────────────────────────────────────────────────

    /** İki bağlamın paylaştığı veritabanı + sayaçlı hizmet. */
    static class Svc {
        final AtomicInteger computes = new AtomicInteger();

        @Cacheable(value = "cert-latest", sync = true)
        public String latest() {
            return "v" + computes.incrementAndGet();
        }

        @CacheEvict(value = {"cert-latest", "card-extras"}, allEntries = true)
        public void write() { }
    }

    @Configuration
    @Import(CacheConfig.class)
    static class PodConfig {
        static DataSource shared;

        @Bean JdbcTemplate jdbcTemplate() { return new JdbcTemplate(shared); }
        @Bean PlatformTransactionManager transactionManager() { return new DataSourceTransactionManager(shared); }
        @Bean CacheBus cacheBus(JdbcTemplate jdbc, ObjectProvider<PlatformTransactionManager> tx) { return new CacheBus(jdbc, tx); }
        @Bean Svc svc() { return new Svc(); }
    }

    AnnotationConfigApplicationContext podContext() {
        PodConfig.shared = mp.dataSource();
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(PodConfig.class);
        contexts.add(ctx);
        ctx.getBean(CacheBus.class).pollNow();
        return ctx;
    }

    @Test
    @DisplayName("@CacheEvict pod A'da (gerçek vekil, CacheConfig) → pod B'nin @Cacheable sonucu bir yoklamada yeniden hesaplanır")
    void cacheEvictAnnotation_propagates() {
        AnnotationConfigApplicationContext a = podContext(), b = podContext();
        assertThat(a.getBean(CacheManager.class)).isInstanceOf(BusAwareCacheManager.class);
        Svc sa = a.getBean(Svc.class), sb = b.getBean(Svc.class);
        assertThat(sb.latest()).isEqualTo("v1");
        assertThat(sb.latest()).isEqualTo("v1");

        sa.write();
        a.getBean(CacheBus.class).flushPending();
        assertThat(sb.latest()).as("yoklamadan önce B önbellekten").isEqualTo("v1");
        b.getBean(CacheBus.class).pollNow();
        assertThat(sb.latest()).as("B yeniden hesapladı").isEqualTo("v2");
        assertThat(gen("cert-latest")).isEqualTo(1);
        assertThat(gen(CacheBus.GLOBAL)).as("iki önbellek TEK yayın (birleştirme)").isEqualTo(1);
    }
}
