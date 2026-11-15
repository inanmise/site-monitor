package com.sitemonitor.service.baseline;

import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dinamik taban örnek deposunun açılış yaması (kapı incelemesi 2026-11-15 — sınıfın kendi testi yoktu): tabloyu ve dizini kurar,
 * depoyu HAZIR bildirir, ikinci koşu hiçbir şey değiştirmez (her açılışta "1 değişiklik" yazmaz), iki pod yarışı kaybedilse de
 * hata vermez, veritabanı hatası açılışı durdurmaz ve depo hazır sayılmaz.
 */
class BaselineSchemaPatchTest {

    final MultiPodTestSupport mp = MultiPodTestSupport.create();

    @AfterEach
    void close() {
        mp.close();
    }

    private boolean tableExists(JdbcTemplate jdbc) {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM " + BaselineSampleStore.TABLE + " WHERE 1 = 0", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("Tablo + dizin kurulur, depo hazır; ikinci koşu 0 değişiklik; veri yolunun kolonları yerinde")
    void createsOnce_marksReady_secondRunIsNoop() {
        BaselineSampleStore store = new BaselineSampleStore(mp.jdbc());
        BaselineSchemaPatch p = new BaselineSchemaPatch(mp.jdbc(), store);
        assertThat(tableExists(mp.jdbc())).isFalse();
        assertThat(store.ready()).isFalse();

        assertThat(p.apply()).isGreaterThanOrEqualTo(1);
        assertThat(tableExists(mp.jdbc())).isTrue();
        assertThat(store.ready()).isTrue();
        Integer idx = mp.jdbc().queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES"
                + " WHERE LOWER(INDEX_NAME) = 'idx_mbs_monitor'", Integer.class);
        assertThat(idx).as("idx_mbs_monitor kuruldu").isPositive();

        assertThat(p.apply()).as("güncel şemada ikinci koşu hiçbir şey değiştirmez").isZero();
        mp.jdbc().update("INSERT INTO " + BaselineSampleStore.TABLE
                + " (monitor_type, monitor_id, checked_ms, value_ms, breach, created_ms) VALUES ('http', 1, 10, 120, false, 10)");
        assertThat(mp.jdbc().queryForObject("SELECT value_ms FROM " + BaselineSampleStore.TABLE + " WHERE monitor_id = 1",
                Long.class)).isEqualTo(120L);
        p.onReady();   // var olan tabloda istisna yok
        assertThat(store.ready()).isTrue();
    }

    @Test
    @DisplayName("İki pod aynı anda açılır: CREATE yarışı kaybedilse de tablo varsa hata yok, depo hazır")
    void createRaceLost_isTolerated() {
        JdbcTemplate racing = new JdbcTemplate(mp.dataSource()) {
            @Override
            public void execute(String sql) {
                super.execute(sql);   // öteki pod kurdu …
                if (sql.startsWith("CREATE TABLE")) {
                    throw new org.springframework.dao.DuplicateKeyException("pg_type_typname_nsp_index");   // … bu pod kaybetti
                }
            }
        };
        BaselineSampleStore store = new BaselineSampleStore(mp.jdbc());
        BaselineSchemaPatch p = new BaselineSchemaPatch(racing, store);
        assertThat(p.apply()).isZero();
        assertThat(tableExists(mp.jdbc())).isTrue();
        assertThat(store.ready()).isTrue();
    }

    @Test
    @DisplayName("Veritabanı hatası açılışı durdurmaz (WARN) ve depo hazır SAYILMAZ (taban çizgisi tabloya dokunmaz)")
    void failureIsSwallowed_storeNotReady() {
        JdbcTemplate broken = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:baseline-yok;IFEXISTS=TRUE"));
        BaselineSampleStore store = new BaselineSampleStore(broken);
        new BaselineSchemaPatch(broken, store).onReady();
        assertThat(store.ready()).isFalse();
    }
}
