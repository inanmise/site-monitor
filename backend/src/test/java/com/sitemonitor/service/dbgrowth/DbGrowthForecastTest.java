package com.sitemonitor.service.dbgrowth;

import com.sitemonitor.service.dbgrowth.DbGrowthForecast.Fit;
import com.sitemonitor.service.dbgrowth.DbGrowthForecast.Point;
import com.sitemonitor.service.dbgrowth.DbGrowthForecast.Projection;
import com.sitemonitor.service.dbgrowth.DbGrowthForecast.TableInput;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Saf tahmin: Theil–Sen, bant, doygunluk, veritabanı toplamı, dolma günü, histogram. */
class DbGrowthForecastTest {

    private static List<Point> line(int n, double start, double perDay) {
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new Point(1000 + i, start + perDay * i));
        return out;
    }

    @Test
    @DisplayName("Theil–Sen: düzgün doğruda eğim tam, bant dar; 7 noktadan az → null")
    void fitLine() {
        Fit f = DbGrowthForecast.fit(line(10, 1_000, 50));
        assertThat(f).isNotNull();
        assertThat(f.slope()).isEqualTo(50.0, within(1e-9));
        assertThat(f.low()).isEqualTo(50.0, within(1e-9));
        assertThat(f.high()).isEqualTo(50.0, within(1e-9));
        assertThat(f.points()).isEqualTo(10);
        assertThat(DbGrowthForecast.fit(line(6, 1_000, 50))).isNull();
        assertThat(DbGrowthForecast.fit(List.of())).isNull();
        assertThat(DbGrowthForecast.fit(null)).isNull();
    }

    @Test
    @DisplayName("Theil–Sen tek sıçramaya dayanıklı (toplu içe aktarma eğimi kaydırmaz); bant genişler")
    void fitRobustToSpike() {
        List<Point> pts = line(15, 1_000, 10);
        pts.set(7, new Point(1007, 1_000_000));          // tek uç değer
        Fit f = DbGrowthForecast.fit(pts);
        assertThat(f.slope()).isBetween(9.0, 11.0);
        assertThat(f.high()).isGreaterThanOrEqualTo(f.slope());
        assertThat(f.low()).isLessThanOrEqualTo(f.slope());
    }

    @Test
    @DisplayName("pencere: en yeniden 30 günden eski noktalar dışarıda; aynı gün ikinci nokta yok sayılır")
    void fitWindow() {
        List<Point> pts = new ArrayList<>(line(10, 0, 1_000));     // gün 1000..1009 — eğim 1000
        for (int i = 0; i < 10; i++) pts.add(new Point(1100 + i, 5_000 + 5 * i));   // 90 gün sonra eğim 5
        pts.add(new Point(1109, 9_999_999));                        // aynı gün ikinci nokta
        Fit f = DbGrowthForecast.fit(pts);
        assertThat(f.points()).isEqualTo(10);
        assertThat(f.slope()).isEqualTo(5.0, within(1e-9));
    }

    @Test
    @DisplayName("tablo tahmini: doymamış tabloda dengelenmeyle sınırlanır; eğim yoksa soğuk başlangıç hızı ±%50; hiçbiri yoksa sabit")
    void projectTable() {
        Fit f = new Fit(100, 80, 120, 10);
        Projection p = DbGrowthForecast.project(new TableInput("t", 1_000, f, null, null), 30);
        assertThat(p.value()).isEqualTo(4_000.0);
        assertThat(p.low()).isEqualTo(3_400.0);
        assertThat(p.high()).isEqualTo(4_600.0);
        Projection capped = DbGrowthForecast.project(new TableInput("t", 1_000, f, null, 2_500.0), 30);
        assertThat(capped.value()).isEqualTo(2_500.0);
        assertThat(capped.high()).isEqualTo(2_500.0);
        Projection cold = DbGrowthForecast.project(new TableInput("t", 1_000, null, 10.0, null), 10);
        assertThat(cold.value()).isEqualTo(1_100.0);
        assertThat(cold.low()).isEqualTo(1_050.0);
        assertThat(cold.high()).isEqualTo(1_150.0);
        Projection flat = DbGrowthForecast.project(new TableInput("t", 1_000, null, null, null), 90);
        assertThat(flat.value()).isEqualTo(1_000.0);
        Projection shrink = DbGrowthForecast.project(new TableInput("t", 100, new Fit(-10, -20, 0, 7), null, null), 30);
        assertThat(shrink.value()).isZero();   // 0'ın altına inmez
    }

    @Test
    @DisplayName("veritabanı = tablo dışı sabit pay + tablo tahminleri; tablo yoksa kendi eğimiyle doğrusal; ikisi de yoksa null")
    void projectDatabase() {
        List<TableInput> tables = List.of(new TableInput("a", 600, new Fit(10, 10, 10, 7), null, null),
                new TableInput("b", 300, new Fit(0, 0, 0, 7), null, null));
        Projection p = DbGrowthForecast.projectDatabase(1_000, tables, null, 10);
        assertThat(p.value()).isEqualTo(1_100.0);    // 100 sabit + 700 + 300
        Projection lin = DbGrowthForecast.projectDatabase(1_000, List.of(), new Fit(5, 4, 6, 7), 10);
        assertThat(lin.value()).isEqualTo(1_050.0);
        assertThat(DbGrowthForecast.projectDatabase(1_000, List.of(), null, 10)).isNull();
    }

    @Test
    @DisplayName("dolma günü: kapasite yok → null; zaten dolu → 0; doğrusal büyümede ilk ulaşılan gün; 10 yılda dolmuyorsa null")
    void daysUntilFull() {
        List<TableInput> tables = List.of(new TableInput("a", 900, new Fit(10, 10, 10, 7), null, null));
        assertThat(DbGrowthForecast.daysUntilFull(0, 1_000, tables, null)).isNull();
        assertThat(DbGrowthForecast.daysUntilFull(900, 1_000, tables, null)).isZero();
        assertThat(DbGrowthForecast.daysUntilFull(1_500, 1_000, tables, null)).isEqualTo(50);
        List<TableInput> flat = List.of(new TableInput("a", 900, new Fit(0, 0, 0, 7), null, null));
        assertThat(DbGrowthForecast.daysUntilFull(1_500, 1_000, flat, null)).isNull();
        // dengelenme kapasitenin altında → hiç dolmaz
        List<TableInput> capped = List.of(new TableInput("a", 900, new Fit(10, 10, 10, 7), null, 1_200.0));
        assertThat(DbGrowthForecast.daysUntilFull(1_500, 1_000, capped, null)).isNull();
    }

    @ParameterizedTest(name = "en eski {0} gün, saklama {1} → doymuş={2}, dengelenme={3}")
    @CsvSource({
            "10, 30, false, 3000",
            "29.5, 30, true, ",
            "40, 30, true, ",
            "2, 30, false, ",
            ", 30, false, ",
    })
    void saturationAndEquilibrium(Double oldest, int retention, boolean saturated, Double eq) {
        assertThat(DbGrowthForecast.saturated(oldest, retention)).isEqualTo(saturated);
        Double got = DbGrowthForecast.equilibrium(1_000, oldest, retention);
        if (eq == null) assertThat(got).isNull();
        else assertThat(got).isEqualTo(eq, within(1e-6));
    }

    @Test
    @DisplayName("histogram: kesimden önce kalan oran, kova içinde zaman ara değeri; uçlar 0 / 1; okunamayan → null")
    void histogram() {
        List<String> b = List.of("2026-01-01T00:00:00", "2026-01-11T00:00:00", "2026-01-21T00:00:00", "2026-01-31T00:00:00");
        assertThat(DbGrowthForecast.histogramFractionBefore(b, Instant.parse("2025-12-01T00:00:00Z"))).isZero();
        assertThat(DbGrowthForecast.histogramFractionBefore(b, Instant.parse("2026-03-01T00:00:00Z"))).isEqualTo(1.0);
        assertThat(DbGrowthForecast.histogramFractionBefore(b, Instant.parse("2026-01-11T00:00:00Z"))).isEqualTo(1.0 / 3, within(1e-9));
        assertThat(DbGrowthForecast.histogramFractionBefore(b, Instant.parse("2026-01-16T00:00:00Z"))).isEqualTo(0.5, within(1e-9));
        assertThat(DbGrowthForecast.histogramFractionBefore(List.of("x", "y"), Instant.now())).isNull();
        assertThat(DbGrowthForecast.histogramFractionBefore(List.of("2026-01-01"), Instant.now())).isNull();
        // gün / saat kovası / boşluklu zaman damgası biçimleri
        assertThat(DbGrowthForecast.epochSeconds("2026-01-01")).isEqualTo(Instant.parse("2026-01-01T00:00:00Z").getEpochSecond());
        assertThat(DbGrowthForecast.epochSeconds("2026-01-01T05")).isEqualTo(Instant.parse("2026-01-01T05:00:00Z").getEpochSecond());
        assertThat(DbGrowthForecast.epochSeconds("2026-01-01 05:06:07.123")).isEqualTo(Instant.parse("2026-01-01T05:06:07Z").getEpochSecond());
    }

    @Test
    @DisplayName("histogram_bounds metni: tırnaklı öğeler virgül içerebilir; sınır sayısı tavanlı")
    void parseArray() {
        assertThat(DbGrowthForecast.parseArray("{\"2026-01-01 00:00:00\",\"a,b\",c}", 100))
                .containsExactly("2026-01-01 00:00:00", "a,b", "c");
        assertThat(DbGrowthForecast.parseArray("{a,b,c,d}", 2)).containsExactly("a", "b");
        assertThat(DbGrowthForecast.parseArray(null, 5)).isEmpty();
    }
}
