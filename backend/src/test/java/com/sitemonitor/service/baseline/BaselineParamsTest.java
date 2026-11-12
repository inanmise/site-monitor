package com.sitemonitor.service.baseline;

import com.sitemonitor.service.dbquery.DbQueryRules;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BaselineParams} — dbquery ({@link DbQueryRules}) sabitleriyle BİREBİR aynı (sürüklenme kapısı) ve bozuk / sınır
 * dışı değerleri öntanımlıya kırpan {@code normalized}.
 */
class BaselineParamsTest {

    @Test
    void sabitler_dbquery_ile_birebir() {
        assertThat(BaselineParams.K_MIN).isEqualTo(DbQueryRules.K_MIN);
        assertThat(BaselineParams.K_MAX).isEqualTo(DbQueryRules.K_MAX);
        assertThat(BaselineParams.K_WARNING_DEFAULT).isEqualTo(DbQueryRules.K_WARNING_DEFAULT);
        assertThat(BaselineParams.K_CRITICAL_DEFAULT).isEqualTo(DbQueryRules.K_CRITICAL_DEFAULT);
        assertThat(BaselineParams.WINDOW_MIN).isEqualTo(DbQueryRules.WINDOW_MIN);
        assertThat(BaselineParams.WINDOW_MAX).isEqualTo(DbQueryRules.WINDOW_MAX);
        assertThat(BaselineParams.WINDOW_DEFAULT).isEqualTo(DbQueryRules.WINDOW_DEFAULT);
        assertThat(BaselineParams.MIN_SAMPLES_MIN).isEqualTo(DbQueryRules.MIN_SAMPLES_MIN);
        assertThat(BaselineParams.MIN_SAMPLES_DEFAULT).isEqualTo(DbQueryRules.MIN_SAMPLES_DEFAULT);
        assertThat(BaselineParams.DIRECTION_UP).isEqualTo(DbQueryRules.DIRECTION_UP);
        assertThat(BaselineParams.SEASON_WEEKLY).isEqualTo(DbQueryRules.SEASON_WEEKLY);
    }

    @Test
    void ontanimli_yavaslama_yonu_yukari() {
        BaselineParams d = BaselineParams.defaults();
        assertThat(d.direction()).isEqualTo(BaselineParams.DIRECTION_UP);
        assertThat(d.kWarning()).isEqualTo(2.5);
        assertThat(d.kCritical()).isEqualTo(3.5);
        assertThat(d.window()).isEqualTo(30);
        assertThat(d.minSamples()).isEqualTo(10);
        assertThat(d.season()).isEqualTo(BaselineParams.SEASON_NONE);
        assertThat(d.seasonal()).isFalse();
    }

    @Test
    void normalized_sinir_disini_kirpar() {
        BaselineParams bad = new BaselineParams("bogus", 0.1, 50, 5, 0, "bogus").normalized();
        assertThat(bad.direction()).isEqualTo(BaselineParams.DIRECTION_UP);
        assertThat(bad.season()).isEqualTo(BaselineParams.SEASON_NONE);
        assertThat(bad.kWarning()).isEqualTo(1.0);     // K_MIN
        assertThat(bad.kCritical()).isEqualTo(10.0);   // K_MAX
        assertThat(bad.window()).isEqualTo(10);        // WINDOW_MIN
        assertThat(bad.minSamples()).isBetween(5, 10); // MIN_SAMPLES_MIN … window
    }

    @Test
    void normalized_kritik_uyaridan_kucukse_yukseltir() {
        BaselineParams p = new BaselineParams(BaselineParams.DIRECTION_UP, 4.0, 2.0, 30, 10, BaselineParams.SEASON_NONE).normalized();
        assertThat(p.kCritical()).isGreaterThanOrEqualTo(p.kWarning());
    }

    @Test
    void normalized_min_ornek_pencereyi_asamaz() {
        BaselineParams p = new BaselineParams(BaselineParams.DIRECTION_UP, 2.5, 3.5, 12, 50, BaselineParams.SEASON_NONE).normalized();
        assertThat(p.minSamples()).isLessThanOrEqualTo(p.window());
    }

    @Test
    void toAnomaly_normalize_edilmis_kopru() {
        DbQueryRules.Anomaly a = new BaselineParams(BaselineParams.DIRECTION_UP, 2.5, 3.5, 30, 10, BaselineParams.SEASON_NONE).toAnomaly();
        assertThat(a.direction()).isEqualTo(DbQueryRules.DIRECTION_UP);
        assertThat(a.window()).isEqualTo(30);
        assertThat(a.minSamples()).isEqualTo(10);
    }
}
