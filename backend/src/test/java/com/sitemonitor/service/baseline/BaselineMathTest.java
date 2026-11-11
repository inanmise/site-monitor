package com.sitemonitor.service.baseline;

import com.sitemonitor.service.baseline.BaselineMath.History;
import com.sitemonitor.service.baseline.BaselineMath.Sample;
import com.sitemonitor.service.baseline.BaselineMath.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Dinamik taban çizgisi saf matematiği ({@link BaselineMath}) — dbquery anomali algoritmasının genelleştirilmiş birebir
 * yeniden yazımı. Veritabanısız doğruluk tablosu: kuruluyor / yetersiz / sıfırlama, yön, σ tabanı, σ = 0 sınırsız sapma,
 * Y1 ihlal-dışlaması + kontrollü uyum.
 */
class BaselineMathTest {

    private static final BaselineParams UP = BaselineParams.defaults();   // yukarı, k 2,5/3,5, pencere 30, min 10

    private static History hist(boolean complete, boolean reset, double... values) {
        List<Sample> s = new ArrayList<>();
        for (double v : values) s.add(new Sample(v, false));   // ihlal yok
        return new History(s, complete, reset);
    }

    private static History histBreach(boolean complete, Sample... samples) {
        return new History(List.of(samples), complete, false);
    }

    private static double[] rep(double v, int n) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    // ── Kuruluyor / yetersiz / sıfırlama ─────────────────────────────────────

    @Test
    void az_ornek_taban_kuruluyor_karar_yok() {
        Verdict v = BaselineMath.evaluate(500, hist(true, false, rep(100, 4)), UP);   // 4 < min 10
        assertThat(v.level()).isEqualTo(BaselineMath.LEVEL_NONE);
        assertThat(v.note()).isEqualTo(BaselineMath.NOTE_BUILDING_BASELINE);
        assertThat(v.evaluated()).isFalse();
        assertThat(v.breached()).isFalse();
        assertThat(v.samples()).isEqualTo(4);
    }

    @Test
    void gecmis_dolu_ama_ornek_az_yetersiz() {
        // pencere 10, min 6: tek-tek ihlaller (kabul edilmez) örnek vermez → consumed≥pencere ama örnek 5 < 6
        BaselineParams p = new BaselineParams(BaselineParams.DIRECTION_UP, 2.5, 3.5, 10, 6, BaselineParams.SEASON_NONE);
        List<Sample> s = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            s.add(new Sample(9999, true));    // ihlal (tek, kabul edilmez → dışlanır)
            s.add(new Sample(100, false));
        }
        Verdict v = BaselineMath.evaluate(100, new History(s, true, false), p);
        assertThat(v.samples()).isEqualTo(5);
        assertThat(v.note()).isEqualTo(BaselineMath.NOTE_INSUFFICIENT_SAMPLES);
        assertThat(v.evaluated()).isFalse();
    }

    @Test
    void sifirlanan_gecmis_ornek_yok_reset_notu() {
        Verdict v = BaselineMath.evaluate(100, new History(List.of(), true, true), UP);
        assertThat(v.note()).isEqualTo(BaselineMath.NOTE_BASELINE_RESET);
        assertThat(v.samples()).isZero();
    }

    // ── Yön ve eşik (yukarı) ─────────────────────────────────────────────────

    @Test
    void yukari_yon_uyari_ve_kritik_esikleri() {
        // 12 örnek = 100 → ortalama 100, σ 0 → etkin σ = 100×0,02 = 2; uyarı 2,5σ=105, kritik 3,5σ=107
        History h = hist(true, false, rep(100, 12));
        assertThat(BaselineMath.evaluate(104, h, UP).level()).isEqualTo(BaselineMath.LEVEL_NONE);     // z=2 ≤ 2,5
        Verdict warn = BaselineMath.evaluate(106, h, UP);                                             // z=3
        assertThat(warn.level()).isEqualTo(BaselineMath.LEVEL_WARNING);
        assertThat(warn.breached()).isTrue();
        assertThat(warn.low()).isEqualTo(95.0);
        assertThat(warn.high()).isEqualTo(105.0);
        assertThat(warn.mean()).isEqualTo(100.0);
        assertThat(warn.sigma()).isEqualTo(2.0);
        assertThat(warn.deviation()).isEqualTo(3.0);
        assertThat(BaselineMath.evaluate(108, h, UP).level()).isEqualTo(BaselineMath.LEVEL_CRITICAL); // z=4 > 3,5
    }

    @Test
    void yukari_yon_hizli_deger_alarm_degil() {
        History h = hist(true, false, rep(100, 12));
        Verdict v = BaselineMath.evaluate(80, h, UP);   // çok hızlı — yukarı yönde önemsiz
        assertThat(v.level()).isEqualTo(BaselineMath.LEVEL_NONE);
        assertThat(v.deviation()).isEqualTo(-10.0);
        assertThat(v.breached()).isFalse();
    }

    @Test
    void asagi_yon_yavas_deger_alarm_degil_hizli_deger_alarm() {
        BaselineParams down = new BaselineParams(BaselineParams.DIRECTION_DOWN, 2.5, 3.5, 30, 10, BaselineParams.SEASON_NONE);
        History h = hist(true, false, rep(100, 12));
        assertThat(BaselineMath.evaluate(108, h, down).level()).isEqualTo(BaselineMath.LEVEL_NONE);     // yavaş — aşağı yönde önemsiz
        assertThat(BaselineMath.evaluate(94, h, down).level()).isEqualTo(BaselineMath.LEVEL_WARNING);   // hızlı — z=-3 → toward 3
        assertThat(BaselineMath.evaluate(92, h, down).level()).isEqualTo(BaselineMath.LEVEL_CRITICAL);  // z=-4 → toward 4
    }

    @Test
    void iki_yon_her_iki_ucta_alarm() {
        BaselineParams both = new BaselineParams(BaselineParams.DIRECTION_BOTH, 2.5, 3.5, 30, 10, BaselineParams.SEASON_NONE);
        History h = hist(true, false, rep(100, 12));
        assertThat(BaselineMath.evaluate(108, h, both).level()).isEqualTo(BaselineMath.LEVEL_CRITICAL);
        assertThat(BaselineMath.evaluate(92, h, both).level()).isEqualTo(BaselineMath.LEVEL_CRITICAL);
    }

    // ── σ tabanı ve σ = 0 ────────────────────────────────────────────────────

    @Test
    void etkin_sigma_tabani_formulu() {
        assertThat(BaselineMath.effectiveSigma(1000, 5)).isEqualTo(20.0);   // taban 1000×0,02=20 > 5
        assertThat(BaselineMath.effectiveSigma(1000, 50)).isEqualTo(50.0);  // gerçek σ 50 > taban 20
        assertThat(BaselineMath.effectiveSigma(0, 0)).isEqualTo(0.0);
    }

    @Test
    void sigma_sifir_ayni_deger_sinirsiz_sapma() {
        History h = hist(true, false, rep(0, 12));   // ortalama 0, etkin σ = 0
        Verdict same = BaselineMath.evaluate(0, h, UP);
        assertThat(same.level()).isEqualTo(BaselineMath.LEVEL_NONE);
        assertThat(same.deviation()).isEqualTo(0.0);
        Verdict up = BaselineMath.evaluate(1, h, UP);
        assertThat(up.level()).isEqualTo(BaselineMath.LEVEL_CRITICAL);
        assertThat(up.deviation()).isEqualTo(BaselineMath.UNBOUNDED_DEVIATION);
    }

    @Test
    void gercek_sigma_ile_bant() {
        // [90,110] × 6 → ortalama 100, örnek σ = sqrt(1200/11) ≈ 10,444659; etkin σ = max(10,44…, 2) = 10,444659
        double[] vals = new double[12];
        for (int i = 0; i < 12; i++) vals[i] = i % 2 == 0 ? 90 : 110;
        History h = hist(true, false, vals);
        Verdict v = BaselineMath.evaluate(130, h, UP);
        assertThat(v.mean()).isEqualTo(100.0);
        assertThat(v.sigma()).isCloseTo(10.444659, within(1e-5));
        assertThat(v.deviation()).isCloseTo(2.8723, within(1e-3));     // (130-100)/10,4447… (gösterim; karar eşiği değil)
        assertThat(v.level()).isEqualTo(BaselineMath.LEVEL_WARNING);   // 2,87 > 2,5 ve ≤ 3,5
        assertThat(BaselineMath.evaluate(124, h, UP).level()).isEqualTo(BaselineMath.LEVEL_NONE);     // z≈2,30
        assertThat(BaselineMath.evaluate(140, h, UP).level()).isEqualTo(BaselineMath.LEVEL_CRITICAL); // z≈3,83 > 3,5
    }

    // ── Y1: ihlal dışlaması + kontrollü uyum ─────────────────────────────────

    @Test
    void kalici_yavaslama_ihlal_dizisi_tabandan_dislanir() {
        // en yeni iki örnek ihlal 500; pencere 30 → dizi (2) < pencere, geçmişi kaplamıyor → DIŞLANIR
        List<Sample> s = new ArrayList<>();
        s.add(new Sample(500, true));
        s.add(new Sample(500, true));
        for (int i = 0; i < 12; i++) s.add(new Sample(100, false));
        Verdict v = BaselineMath.evaluate(500, new History(s, true, false), UP);
        assertThat(v.mean()).isEqualTo(100.0);               // 500'ler tabana girmedi
        assertThat(v.level()).isEqualTo(BaselineMath.LEVEL_CRITICAL);
        assertThat(v.breached()).isTrue();
    }

    @Test
    void kontrollu_uyum_pencere_boyu_ihlal_dizisi_yeni_normal() {
        // pencere 10: 10 ardışık ihlal 500 (dizi = pencere) → kabul → taban 500 → değer 500 artık olağan
        BaselineParams p = new BaselineParams(BaselineParams.DIRECTION_UP, 2.5, 3.5, 10, 5, BaselineParams.SEASON_NONE);
        Sample[] arr = new Sample[10];
        for (int i = 0; i < 10; i++) arr[i] = new Sample(500, true);
        Verdict v = BaselineMath.evaluate(500, histBreach(true, arr), p);
        assertThat(v.mean()).isEqualTo(500.0);
        assertThat(v.level()).isEqualTo(BaselineMath.LEVEL_NONE);   // uyum sağlandı → alarm kapanır
    }

    @Test
    void kontrollu_uyum_gecmisi_kaplayan_kisa_dizi_min_ornek() {
        // pencere 30 ama geçmiş yalnız 8 ihlal (tam) ve 8 ≥ min 5 → kabul → yeni düzey olağan
        BaselineParams p = new BaselineParams(BaselineParams.DIRECTION_UP, 2.5, 3.5, 30, 5, BaselineParams.SEASON_NONE);
        Sample[] arr = new Sample[8];
        for (int i = 0; i < 8; i++) arr[i] = new Sample(500, true);
        Verdict v = BaselineMath.evaluate(500, histBreach(true, arr), p);
        assertThat(v.mean()).isEqualTo(500.0);
        assertThat(v.level()).isEqualTo(BaselineMath.LEVEL_NONE);
    }

    @Test
    void kisa_ihlal_dizisi_gecmis_tam_degil_dislanir() {
        // 8 ihlal ama geçmiş TAM DEĞİL (okuma sınırına değildi) → kontrollü uyum tetiklenmez → dışlanır → örnek yok
        BaselineParams p = new BaselineParams(BaselineParams.DIRECTION_UP, 2.5, 3.5, 30, 5, BaselineParams.SEASON_NONE);
        Sample[] arr = new Sample[8];
        for (int i = 0; i < 8; i++) arr[i] = new Sample(500, true);
        Verdict v = BaselineMath.evaluate(500, histBreach(false, arr), p);
        assertThat(v.samples()).isZero();
        assertThat(v.evaluated()).isFalse();   // örnek yetersiz / kuruluyor
    }
}
