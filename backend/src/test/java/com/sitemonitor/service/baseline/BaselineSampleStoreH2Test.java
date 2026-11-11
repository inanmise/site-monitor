package com.sitemonitor.service.baseline;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.baseline.BaselineMath.History;
import com.sitemonitor.service.baseline.BaselineMath.Verdict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dinamik taban çizgisi örnek deposu H2'de ({@link BaselineSampleStore} + {@link BaselineSchemaPatch}): şema yaması (depo
 * HAZIR olur), ekle / en yeni N oku (sıra + ihlal bayrağı + complete), {@code beforeMs} dışlaması, izleme sil, buda, tablo
 * yokken boş, ve uçtan uca {@link BaselineMath} ile karar.
 */
class BaselineSampleStoreH2Test {

    private static final long T0 = 1_800_000_000_000L;
    private static final String HTTP = BaselineTypes.HTTP;

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    BaselineSampleStore store;

    @BeforeEach
    void schema() {
        store = new BaselineSampleStore(mp.jdbc());
        new BaselineSchemaPatch(mp.jdbc(), store).apply();
    }

    @AfterEach
    void close() {
        mp.close();
    }

    @Test
    void sema_yamasi_depoyu_hazir_yapar() {
        assertThat(store.ready()).isTrue();
    }

    @Test
    void ekle_ve_en_yeni_oku_sira_ihlal_ve_complete() {
        store.append(HTTP, 1, T0 + 1000, 100, false);
        store.append(HTTP, 1, T0 + 2000, 110, false);
        store.append(HTTP, 1, T0 + 3000, 500, true);    // ihlal
        store.append(HTTP, 2, T0 + 3000, 999, false);   // başka izleme — karışmamalı

        History h = store.readRecent(HTTP, 1, T0 + 10_000, 50);
        assertThat(h.samples()).hasSize(3);
        assertThat(h.complete()).isTrue();               // 3 < 50 → sınıra değilmedi
        // yeniden eskiye: 500(ihlal), 110, 100
        assertThat(h.samples().get(0).value()).isEqualTo(500.0);
        assertThat(h.samples().get(0).breach()).isTrue();
        assertThat(h.samples().get(1).value()).isEqualTo(110.0);
        assertThat(h.samples().get(2).value()).isEqualTo(100.0);
        assertThat(store.count(HTTP, 1)).isEqualTo(3);
        assertThat(store.count(HTTP, 2)).isEqualTo(1);
    }

    @Test
    void beforeMs_mevcut_ve_sonraki_disar() {
        for (int i = 1; i <= 5; i++) store.append(HTTP, 1, T0 + i * 1000, 100 + i, false);
        // beforeMs = T0+3000 → yalnız 1,2 (< 3000) girer; 3,4,5 dışarı
        History h = store.readRecent(HTTP, 1, T0 + 3000, 50);
        assertThat(h.samples()).hasSize(2);
        assertThat(h.samples().get(0).value()).isEqualTo(102.0);   // T0+2000
    }

    @Test
    void limit_sinira_deginca_complete_false() {
        for (int i = 1; i <= 10; i++) store.append(HTTP, 1, T0 + i * 1000, 100, false);
        History h = store.readRecent(HTTP, 1, T0 + 100_000, 5);
        assertThat(h.samples()).hasSize(5);
        assertThat(h.complete()).isFalse();   // sınıra değdi → daha fazlası olabilir
    }

    @Test
    void izleme_sil() {
        store.append(HTTP, 1, T0 + 1000, 100, false);
        store.append(HTTP, 1, T0 + 2000, 100, false);
        assertThat(store.deleteForMonitor(HTTP, 1)).isEqualTo(2);
        assertThat(store.count(HTTP, 1)).isZero();
    }

    @Test
    void buda_izleme_basina_en_yeniyi_tutar() {
        for (int i = 1; i <= 20; i++) store.append(HTTP, 1, T0 + i * 1000, 100 + i, false);
        for (int i = 1; i <= 20; i++) store.append(HTTP, 2, T0 + i * 1000, 200 + i, false);
        store.prune(5);   // izleme başına en yeni 5
        assertThat(store.count(HTTP, 1)).isEqualTo(5);
        assertThat(store.count(HTTP, 2)).isEqualTo(5);
        History h = store.readRecent(HTTP, 1, T0 + 100_000, 50);
        assertThat(h.samples().get(0).value()).isEqualTo(120.0);   // en yeni korundu (T0+20000 → 100+20)
        assertThat(h.samples()).hasSize(5);
    }

    @Test
    void desteklenmeyen_tur_yazmaz_okumaz() {
        store.append("domain", 1, T0 + 1000, 100, false);
        assertThat(store.count("domain", 1)).isZero();
        assertThat(store.readRecent("domain", 1, T0 + 10_000, 50).samples()).isEmpty();
    }

    @Test
    void tablo_yoksa_bos_doner_ekleme_sessiz() {
        JdbcTemplate empty = new JdbcTemplate(mp.jdbc().getDataSource());
        empty.execute("DROP TABLE IF EXISTS " + BaselineSampleStore.TABLE);
        BaselineSampleStore s2 = new BaselineSampleStore(empty);   // markReady çağrılmadı
        s2.append(HTTP, 1, T0, 100, false);                        // patlamaz
        assertThat(s2.readRecent(HTTP, 1, T0 + 1, 50).samples()).isEmpty();
        assertThat(s2.ready()).isFalse();
    }

    @Test
    void uctan_uca_math_ile_karar() {
        // 12 örnek ~100 → ortalama 100, etkin σ taban 2; değer 500 → kritik (yukarı)
        for (int i = 1; i <= 12; i++) store.append(HTTP, 1, T0 + i * 1000, 100, false);
        History h = store.readRecent(HTTP, 1, T0 + 100_000, BaselineSampleStore.recentNeed(BaselineParams.defaults()));
        Verdict v = BaselineMath.evaluate(500, h, BaselineParams.defaults());
        assertThat(v.level()).isEqualTo(BaselineMath.LEVEL_CRITICAL);
        assertThat(v.breached()).isTrue();
        assertThat(v.mean()).isEqualTo(100.0);
    }
}
