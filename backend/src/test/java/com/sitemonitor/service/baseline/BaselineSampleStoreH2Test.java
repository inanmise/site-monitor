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

    // ── 2026-11-13 hata taraması ────────────────────────────────────────────────────────────────────────────────────

    @Test
    void mevsimsel_bugunun_son_yarim_saati_ayni_saat_sayilmaz() {
        long DAY = BaselineSampleStore.DAY_MS, tol = BaselineSampleStore.SEASON_TOLERANCE_MIN_MS;
        long now = T0 + 10 * DAY;
        long nowPos = Math.floorMod(now, DAY);
        store.append(HTTP, 1, now - 5 * 60_000L, 555, false);    // 5 dk önce (bugün) → GİRMEZ (#3)
        store.append(HTTP, 1, now - 25 * 60_000L, 556, false);   // 25 dk önce (bugün) → GİRMEZ
        store.append(HTTP, 1, now - DAY, 100, false);            // tam 24 sa önce → girer
        store.append(HTTP, 1, now - DAY + 25 * 60_000L, 101, false);   // dün, şimdiden 25 dk sonrası (±30 dk içinde) → girer
        store.append(HTTP, 1, now - DAY - 25 * 60_000L, 102, false);   // dün, 25 dk öncesi → girer
        History h = store.readSeasonal(HTTP, 1, now, 50, DAY, nowPos, tol);
        assertThat(h.samples()).extracting(BaselineMath.Sample::value).containsExactly(101.0, 100.0, 102.0);
    }

    @Test
    void mevsimsel_haftalik_bu_haftanin_ornekleri_sayilmaz() {
        long WEEK = BaselineSampleStore.WEEK_MS, tol = BaselineSampleStore.SEASON_TOLERANCE_MIN_MS;
        long now = T0 + 10 * WEEK;
        store.append(HTTP, 1, now - 10 * 60_000L, 900, false);   // bu hafta (10 dk önce) → girmez
        store.append(HTTP, 1, now - WEEK, 100, false);           // geçen hafta aynı an → girer
        History h = store.readSeasonal(HTTP, 1, now, 50, WEEK, Math.floorMod(now, WEEK), tol);
        assertThat(h.samples()).extracting(BaselineMath.Sample::value).containsExactly(100.0);
    }

    @Test
    void budama_dilimlerle_ve_sinir_satiri_dahil() {
        JdbcTemplate j = mp.jdbc();
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 1_200; i++) rows.add(new Object[] { HTTP, 1L, T0 + i, 100L, false, T0 });
        j.batchUpdate("INSERT INTO " + BaselineSampleStore.TABLE
                + " (monitor_type, monitor_id, checked_ms, value_ms, breach, created_ms) VALUES (?, ?, ?, ?, ?, ?)", rows);
        store.append(HTTP, 2, T0, 100, false);   // sınırın altında — dokunulmaz
        BaselineSampleStore.PruneResult r = store.prune(150, null, 500);   // küçük dilim: 500 + 500 + 50 (H2'de hızlı)
        assertThat(r.deleted()).isEqualTo(1_050);
        assertThat(r.monitors()).isEqualTo(1);
        assertThat(r.partial()).isFalse();
        assertThat(store.count(HTTP, 1)).isEqualTo(150);
        assertThat(store.count(HTTP, 2)).isEqualTo(1);
        // en yeniler kaldı
        History h = store.readRecent(HTTP, 1, Long.MAX_VALUE, 1);
        assertThat(h.samples()).hasSize(1);
        assertThat(j.queryForObject("SELECT MIN(checked_ms) FROM " + BaselineSampleStore.TABLE + " WHERE monitor_id = 1",
                Long.class)).isEqualTo(T0 + 1_051);
    }

    /** {@code n} satırı {@code stepMs} aralıkla {@code newest}'ten geriye ekler (toplu). */
    private void fill(long id, long newest, long stepMs, int n) {
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(new Object[] { HTTP, id, newest - i * stepMs, 100L + (i % 7), false, newest });
        mp.jdbc().batchUpdate("INSERT INTO " + BaselineSampleStore.TABLE
                + " (monitor_type, monitor_id, checked_ms, value_ms, breach, created_ms) VALUES (?, ?, ?, ?, ?, ?)", rows);
    }

    private History seasonal(long id, long now, long period, int limit) {
        return store.readSeasonal(HTTP, id, now, limit, period, Math.floorMod(now, period), BaselineSampleStore.SEASON_TOLERANCE_MIN_MS);
    }

    @Test
    void budama_haftalik_mevsimi_korur_mevsimsiz_satir_siniri_aynen() {
        // Son inceleme (YÜKSEK): 5 dk aralıkta 1500 satır ≈ 5,2 gün — yalnız satır sayısıyla budama geçen haftanın aynı saatini
        // siliyor, haftalık taban kalıcı olarak değerlendirilemiyordu. Mevsime duyarlı budama geçen haftayı korur.
        long now = T0 + 100 * BaselineSampleStore.WEEK_MS;
        long W = BaselineSampleStore.WEEK_MS;
        fill(1, now - 60_000L, 300_000L, 2_304);    // 8 gün, 5 dk — haftalık (pencere 10)
        fill(2, now - 60_000L, 300_000L, 1_700);    // mevsimsiz — yalnız satır sınırı
        BaselineSampleStore.SeasonNeeds needs = (t, id) -> id == 1 ? new BaselineSampleStore.SeasonNeed(W, 10) : null;
        assertThat(seasonal(1, now, W, 10).samples()).as("budamadan önce").hasSize(10);

        store.prune(BaselineSampleStore.KEEP_PER_MONITOR, needs, now, null, 500);

        assertThat(store.count(HTTP, 2)).as("mevsimsiz: eski satır sınırı").isEqualTo(BaselineSampleStore.KEEP_PER_MONITOR);
        assertThat(seasonal(1, now, W, 10).samples()).as("haftalık: geçen haftanın aynı saati budamadan sonra da okunur").hasSize(10);
    }

    @Test
    void budama_gunluk_mevsimde_yeterli_gun_kalir() {
        // 10 dk aralık: aynı saate günde ~6 örnek → pencere 30 için ~7 gün gerekir; satır sınırı (100) ~17 saattir.
        long now = T0 + 50 * BaselineSampleStore.DAY_MS;
        long D = BaselineSampleStore.DAY_MS;
        fill(3, now - 60_000L, 600_000L, 1_440);    // 10 gün
        BaselineSampleStore.SeasonNeeds needs = (t, id) -> new BaselineSampleStore.SeasonNeed(D, 30);
        BaselineSampleStore.PruneResult r = store.prune(100, needs, now, null, 500);
        assertThat(r.deleted()).as("ihtiyaçtan eskiler yine silinir (satır hacmi sınırlı)").isPositive();
        assertThat(store.count(HTTP, 3)).isBetween(1_000, 1_100);
        History h = seasonal(3, now, D, 30);
        assertThat(h.samples()).as("günlük pencere budamadan sonra dolu").hasSize(30);
    }

    @Test
    void mevsimsel_koruma_suresi_saf_kural() {
        long D = BaselineSampleStore.DAY_MS, tol = BaselineSampleStore.SEASON_TOLERANCE_MIN_MS;
        long pad = tol + BaselineSampleStore.SEASON_KEEP_MARGIN_MS;
        // 30 sn aralık (2880 satır/gün → pencere başına 120) → dünün aynı saati yeter: 1 periyot
        assertThat(BaselineSampleStore.seasonalKeepFromMs(new BaselineSampleStore.SeasonNeed(D, 30), T0, 2_880))
                .isEqualTo(T0 - D - pad);
        // 10 dk (144/gün → 6) → ceil(37,5 / 6) = 7 gün
        assertThat(BaselineSampleStore.seasonalKeepFromMs(new BaselineSampleStore.SeasonNeed(D, 30), T0, 144))
                .isEqualTo(T0 - 7 * D - pad);
        // örnek yok / çok seyrek → üst sınır pencere + 1 periyot
        assertThat(BaselineSampleStore.seasonalKeepFromMs(new BaselineSampleStore.SeasonNeed(D, 30), T0, 0))
                .isEqualTo(T0 - 31 * D - pad);
        assertThat(BaselineSampleStore.seasonalKeepFromMs(new BaselineSampleStore.SeasonNeed(D, 30), T0, 1))
                .isEqualTo(T0 - 31 * D - pad);
    }
}
