package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.it.SwallowedSqlErrors;
import com.sitemonitor.service.crypto.CryptoInventoryService;
import com.sitemonitor.service.quality.DataQualityService;
import com.sitemonitor.service.report.executive.ExecutiveSummary;
import com.sitemonitor.service.report.executive.ExecutiveSummaryService;
import com.sitemonitor.service.report.executive.SectionResult;
import com.sitemonitor.service.tlsgrade.TlsGradeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-10-10 özellikleri GERÇEK PostgreSQL'de (veri kalitesi, kripto envanteri, TLS notu, aylık yönetici özeti). Bu
 * servisler sorgu hatasını ekran çökmesin diye yutar (boş sonuç / NO_DATA / ERROR bölümü) — H2'de yeşil, PostgreSQL'de
 * sessizce boş bir sorgu burada görünür olur ({@link SwallowedSqlErrors}).
 */
@PostgresIntegration
class NewReportsPostgresTest {

    private static <T> T bean(Class<T> type) { return PostgresIt.app().bean(type); }

    @Test
    @DisplayName("Veri kalitesi: değerlendirme + günlük görüntü (ON CONFLICT ikinci yazımda no-op) + ay sonu okuması — yutulan SQL hatası yok")
    void dataQuality_queriesAndSnapshot_onPostgres() {
        DataQualityService svc = bean(DataQualityService.class);
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            assertThat(svc.evaluation(true)).isNotNull();
            Instant now = Instant.now();
            svc.writeDailySnapshot(now);
            svc.writeDailySnapshot(now);                                     // aynı gün ikinci yazım: çakışma yok
            assertThat(svc.snapshotWritten(now)).isTrue();
            String day = LocalDate.ofInstant(now, ZoneId.of("Europe/Istanbul")).toString();
            Integer orgRows = PostgresIt.app().jdbc().queryForObject(
                    "SELECT count(*) FROM data_quality_daily WHERE snap_day = ? AND team_key = 0", Integer.class, day);
            assertThat(orgRows).as("gün başına TEK kurum satırı").isEqualTo(1);
            assertThat(svc.monthEnds(LocalDate.now(ZoneId.of("Europe/Istanbul")).withDayOfMonth(1))).isNotNull();
            assertThat(svc.digest()).isNotNull();
            assertThat(sql.errors()).as("veri kalitesinin yuttuğu SQL hataları").isEmpty();
        }
    }

    @Test
    @DisplayName("Kripto envanteri: tam görünüm + özet — yutulan SQL hatası yok")
    void cryptoInventory_onPostgres() {
        CryptoInventoryService svc = bean(CryptoInventoryService.class);
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            Map<String, Object> full = svc.build(null, true);
            assertThat(full).isNotNull();
            assertThat(svc.summary(null, 10)).isNotNull();
            assertThat(sql.errors()).as("kripto envanterinin yuttuğu SQL hataları").isEmpty();
        }
    }

    @Test
    @DisplayName("TLS notu: kapsam + düşüş penceresi + son düşüşler sorguları — yutulan SQL hatası yok")
    void tlsGrade_queries_onPostgres() {
        TlsGradeService svc = bean(TlsGradeService.class);
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            assertThat(svc.coverageForDomains(List.of("it-tls.example.test"))).isNotNull();
            String to = Instant.now().toString();
            String from = Instant.now().minus(30, ChronoUnit.DAYS).toString();
            assertThat(svc.dropsBetween(from, to, 10)).isNotNull();
            assertThat(svc.drops(null, 30, 10, Map.of())).isNotNull();
            assertThat(sql.errors()).as("TLS notunun yuttuğu SQL hataları").isEmpty();
        }
    }

    @Test
    @DisplayName("Yönetici özeti: canlı ay hesaplaması yedi bölümü üretir, hiçbiri ERROR değil — yutulan SQL hatası yok")
    void executiveSummary_allSections_onPostgres() {
        ExecutiveSummaryService svc = bean(ExecutiveSummaryService.class);
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            ExecutiveSummary s = svc.get(svc.currentMonth(), true, true);
            assertThat(s.sections()).extracting(SectionResult::key)
                    .contains("availability", "noise", "expirations", "renewals", "tls-grade", "crypto-readiness", "data-quality");
            assertThat(s.sections()).as("hata veren bölüm").noneMatch(r -> SectionResult.ERROR.equals(r.status()));
            assertThat(sql.errors()).as("yönetici özetinin yuttuğu SQL hataları").isEmpty();
        }
    }
}
