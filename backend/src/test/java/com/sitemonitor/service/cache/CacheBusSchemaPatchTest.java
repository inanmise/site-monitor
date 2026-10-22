package com.sitemonitor.service.cache;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code cache_bus} açılış yaması (2026-10-22, çok pod R2-A, MP-30): kurar, ikinci koşu dokunmaz, düşerse açılış durmaz. */
class CacheBusSchemaPatchTest {

    final MultiPodTestSupport mp = MultiPodTestSupport.create();

    @AfterEach
    void close() {
        mp.close();
    }

    @Test
    @DisplayName("Tablo yoksa kurulur; ikinci koşu no-op; kurulan tablo veri yolunun beklediği kolonları taşır")
    void createsOnce() {
        CacheBusSchemaPatch p = new CacheBusSchemaPatch(mp.jdbc());
        assertThat(p.tableExists()).isFalse();
        assertThat(p.apply()).isTrue();
        assertThat(p.apply()).isFalse();
        mp.jdbc().update("INSERT INTO cache_bus (name, generation, updated_at) VALUES ('x', 0, NULL)");
        assertThat(mp.jdbc().queryForObject("SELECT generation FROM cache_bus WHERE name = 'x'", Long.class)).isZero();
        p.onReady();   // var olan tabloda istisna yok
    }

    @Test
    @DisplayName("İki pod aynı anda açılır: CREATE yarışı kaybedilse de tablo varsa hata yok; veri yoluna 'hazır' bildirilir")
    void createRaceLost_isTolerated_andBusNotified() {
        org.springframework.jdbc.core.JdbcTemplate racing = new org.springframework.jdbc.core.JdbcTemplate(mp.dataSource()) {
            @Override
            public void execute(String sql) {
                super.execute(sql);   // öteki pod kurdu …
                throw new org.springframework.dao.DuplicateKeyException("pg_type_typname_nsp_index");   // … bu pod yarışı kaybetti
            }
        };
        CacheBus bus = org.mockito.Mockito.mock(CacheBus.class);
        org.springframework.beans.factory.support.StaticListableBeanFactory f = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        f.addBean("cacheBus", bus);
        CacheBusSchemaPatch p = new CacheBusSchemaPatch(racing, f.getBeanProvider(CacheBus.class));
        assertThat(p.apply()).isFalse();
        assertThat(p.tableExists()).isTrue();
        org.mockito.Mockito.verify(bus).tableReady();
    }

    @Test
    @DisplayName("Veritabanı hatası açılışı durdurmaz (WARN)")
    void failureIsSwallowed() {
        org.springframework.jdbc.core.JdbcTemplate broken = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:cachebus-yok;IFEXISTS=TRUE"));
        new CacheBusSchemaPatch(broken).onReady();
    }
}
