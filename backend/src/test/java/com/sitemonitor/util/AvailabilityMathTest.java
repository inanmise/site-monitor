package com.sitemonitor.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Hata varken ASLA %100" kuralı (prod kapısı 2026-09-25, O-5) — tek yardımcı + kardeş yüzey kapısı.
 */
class AvailabilityMathTest {

    @ParameterizedTest(name = "{0} kontrol, {1} başarılı, {2} ondalık → {3}")
    @CsvSource({
            // hata yok → tam 100
            "10080, 10080, 1, 100.0",
            "43200, 43200, 2, 100.0",
            // haftalık 1 dk aralık, TEK hata: %99,990 → round1 %100,0 DEĞİL, 99,9
            "10080, 10079, 1, 99.9",
            // kart trendi 24 sa / 10 sn aralık, tek hata: 99,988 → 99,9 (1 ondalık)
            "8640, 8639, 1, 99.9",
            // 30 gün / 1 dk, tek hata: 99,998 → 99,99 (2 ondalık)
            "43200, 43199, 2, 99.99",
            // durum sayfası 7 gün ~600k kontrol, 100 hata: 99,983 → 99,9 (eskiden %100,0)
            "600000, 599900, 1, 99.9",
            // sıradan değerler aynen yuvarlanır
            "200, 150, 1, 75.0",
            "3, 2, 2, 66.67",
            "3, 1, 1, 33.3",
            "10, 0, 1, 0.0"
    })
    void pct_table(long total, long ok, int decimals, double expected) {
        assertThat(AvailabilityMath.pct(total, ok, decimals)).isEqualTo(expected);
    }

    @Test
    @DisplayName("veri yok → null (\"%100\" değil)")
    void noData_isNull() {
        assertThat(AvailabilityMath.pct(0, 0, 1)).isNull();
    }

    /** Erişilebilirlik gösteren yüzeyler — yuvarlamayı YALNIZ AvailabilityMath üzerinden yapmalı. */
    private static final List<String> AVAILABILITY_SURFACES = List.of(
            "com/sitemonitor/service/MonitoringWeeklyStatsService.java",
            "com/sitemonitor/controller/PublicStatsController.java",
            "com/sitemonitor/service/MonitorSparklineService.java",
            "com/sitemonitor/controller/MonitoringController.java",
            "com/sitemonitor/service/CertificateCardExtrasService.java",
            "com/sitemonitor/service/WeeklyAvailabilityReportService.java");

    /** Çıplak yüzde yuvarlaması: round1(100.0 * …) ya da Math.round(… 1000.0 / 10000.0 …) (kaçışsız sınıflar). */
    private static final Pattern RAW_PCT = Pattern.compile(
            "round1[(]100([.]0)?[ ]*[*]|Math[.]round[(][^;]*(1000[.]0|10000[.]0)");

    @Test
    @DisplayName("KAPI: erişilebilirlik yüzeylerinde çıplak yüzde yuvarlaması kalmadı (R6 örneği kapatmıştı, sınıfı değil)")
    void availabilitySurfacesUseTheHelper() throws IOException {
        Path root = Files.isDirectory(Path.of("src/main/java")) ? Path.of("src/main/java") : Path.of("backend/src/main/java");
        List<String> offenders = new ArrayList<>();
        for (String rel : AVAILABILITY_SURFACES) {
            Path f = root.resolve(rel);
            assertThat(f).as("yüzey dosyası taşındı mı? " + rel).exists();
            int no = 0;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                no++;
                if (RAW_PCT.matcher(line).find()) offenders.add(rel + ":" + no + ": " + line.trim());
            }
        }
        assertThat(offenders)
                .as("Hata varken %100'e yuvarlanabilir — AvailabilityMath.pct(total, ok, ondalık) kullanın")
                .isEmpty();
    }
}
