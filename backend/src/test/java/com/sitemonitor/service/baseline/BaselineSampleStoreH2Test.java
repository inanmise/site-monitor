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
    void mevsimsel_gunluk_ayni_saat_slotu() {
        long DAY = BaselineSampleStore.DAY_MS, tol = BaselineSampleStore.SEASON_TOLERANCE_MIN_MS;
        long nowPos = Math.floorMod(T0, DAY);   // 08:00 konumu
        for (int d = 0; d < 5; d++) {
            store.append(HTTP, 1, T0 + d * DAY, 100 + d, false);                 // her gün 08:00
            store.append(HTTP, 1, T0 + d * DAY + 20 * 60_000, 150 + d, false);   // 08:20 (±30 dk içinde)
        }
        for (int d = 0; d < 5; d++) store.append(HTTP, 1, T0 + d * DAY + 6 * 3_600_000L, 900, false);   // 14:00 (başka slot)
        store.append(HTTP, 1, T0 + 40 * 60_000, 800, false);   // 08:40 (±30 dk dışı)

        History h = store.readSeasonal(HTTP, 1, T0 + 10 * DAY, 50, DAY, nowPos, tol);
        assertThat(h.samples()).hasSize(10);                        // yalnız 08:00 + 08:20 × 5 gün
        assertThat(h.complete()).isFalse();                         // mevsimsel okuma muhafazakâr
        assertThat(h.samples()).allMatch(s -> s.value() < 200);     // 14:00 (900) / 08:40 (800) yok
    }

    @Test
    void mevsimsel_haftalik_ayni_gun_ve_saat() {
        long WEEK = BaselineSampleStore.WEEK_MS, DAY = BaselineSampleStore.DAY_MS, tol = BaselineSampleStore.SEASON_TOLERANCE_MIN_MS;
        long nowPos = Math.floorMod(T0, WEEK);
        for (int w = 0; w < 4; w++) store.append(HTTP, 1, T0 + w * WEEK, 100 + w, false);        // aynı hafta-günü-saati
        for (int w = 0; w < 4; w++) store.append(HTTP, 1, T0 + w * WEEK + DAY, 900, false);       // ertesi gün (haftalıkta eşleşmez)
        History h = store.readSeasonal(HTTP, 1, T0 + 10 * WEEK, 50, WEEK, nowPos, tol);
        assertThat(h.samples()).hasSize(4);
        assertThat(h.samples()).allMatch(s -> s.value() < 200);
    }

    @Test
    void mevsimsel_gun_sarmasi_dairesel() {
        long DAY = BaselineSampleStore.DAY_MS, tol = BaselineSampleStore.SEASON_TOLERANCE_MIN_MS;
        long midnight = T0 - Math.floorMod(T0, DAY);       // o günün 00:00'ı
        long nowPos = Math.floorMod(midnight + 10 * 60_000, DAY);   // 00:10
        store.append(HTTP, 1, midnight - 10 * 60_000, 111, false);  // dün 23:50 → dairesel 20 dk → eşleşir
        store.append(HTTP, 1, midnight + 5 * 60_000, 222, false);   // bugün 00:05 → 5 dk → eşleşir
        store.append(HTTP, 1, midnight + 60 * 60_000, 999, false);  // bugün 01:00 → 50 dk → eşleşmez
        History h = store.readSeasonal(HTTP, 1, midnight + 2 * DAY, 50, DAY, nowPos, tol);
        assertThat(h.samples()).hasSize(2);
        assertThat(h.samples()).allMatch(s -> s.value() < 900);
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
