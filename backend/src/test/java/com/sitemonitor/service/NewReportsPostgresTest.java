package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.it.SwallowedSqlErrors;
import com.sitemonitor.service.crypto.CryptoInventoryService;
import com.sitemonitor.service.quality.DataQualityService;
import com.sitemonitor.service.report.executive.ExecutiveSummary;
import com.sitemonitor.repository.ExecutiveSummaryTeamReportRepository;
import com.sitemonitor.repository.ExecutiveSummaryTeamSettingsRepository;
import com.sitemonitor.service.report.executive.ExecutiveSummaryService;
import com.sitemonitor.service.report.executive.ExecutiveSummaryTeamService;
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
    @DisplayName("Takım yönetici özeti: ayar sorguları, takım × ay talebi (UNIQUE + koşullu yeniden talep + budama) ve takım "
            + "kapsamlı hesap (koşu boyu paylaşım) PostgreSQL'de — yutulan SQL hatası yok")
    void executiveSummaryTeam_onPostgres() {
        com.sitemonitor.repository.TeamRepository teams = bean(com.sitemonitor.repository.TeamRepository.class);
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setName("it-exec-team-" + System.nanoTime());
        team.setActive(true);
        Long teamId = teams.save(team).getId();
        ExecutiveSummaryTeamService teamService = bean(ExecutiveSummaryTeamService.class);
        ExecutiveSummaryTeamSettingsRepository settingsRepo = bean(ExecutiveSummaryTeamSettingsRepository.class);
        ExecutiveSummaryTeamReportRepository reportRepo = bean(ExecutiveSummaryTeamReportRepository.class);
        ExecutiveSummaryService svc = bean(ExecutiveSummaryService.class);
        try (SwallowedSqlErrors sql = SwallowedSqlErrors.capture()) {
            assertThat(teamService.save(teamId, Map.of("enabled", true, "extra_emails", "it-team@example.com"), null, 0, "it"))
                    .contains("enabled", "extra_emails");
            assertThat(settingsRepo.existsByEnabledTrue()).isTrue();
            assertThat(teamService.enabledTeamIds()).contains(teamId);
            assertThat(teamService.recipientsFor(List.of(teamId)).get(teamId).emails()).containsExactly("it-team@example.com");
            assertThat(teamService.overview(List.of(teamId), svc.defaultMonth())).hasSize(1);
            assertThat(teamService.detail(teamId)).containsEntry("enabled", true);

            // takım × ay talebi: ilk INSERT, aynı anahtarla ikinci INSERT UNIQUE'te düşer, koşullu yeniden talep tek satır
            com.sitemonitor.model.ExecutiveSummaryTeamReport r = new com.sitemonitor.model.ExecutiveSummaryTeamReport();
            r.setTeamId(teamId);
            r.setReportYear(2020);
            r.setReportMonth(1);
            r.setStatus("FAILED");
            r.setAttempts(1);
            Long id = reportRepo.saveAndFlush(r).getId();
            com.sitemonitor.model.ExecutiveSummaryTeamReport dup = new com.sitemonitor.model.ExecutiveSummaryTeamReport();
            dup.setTeamId(teamId);
            dup.setReportYear(2020);
            dup.setReportMonth(1);
            dup.setStatus("SENDING");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> reportRepo.saveAndFlush(dup))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(reportRepo.reclaim(id, "FAILED", 1, 2, "2026-10-10T00:00:00", "MANUAL", "it")).isEqualTo(1);
            assertThat(reportRepo.reclaim(id, "FAILED", 1, 2, "2026-10-10T00:00:00", "MANUAL", "it")).isZero();
            assertThat(reportRepo.findByReportYearAndReportMonthAndTeamIdIn(2020, 1, List.of(teamId))).hasSize(1);
            assertThat(reportRepo.deleteOlderThan(2021 * 12 + 1)).isEqualTo(1);

            // takım kapsamlı hesap — kurum + takım aynı koşu haritasıyla
            Map<String, Object> shared = svc.newRunShared();
            ExecutiveSummary org = svc.compute(svc.currentMonth(), null, shared);
            ExecutiveSummary scoped = svc.compute(svc.currentMonth(), teamId, shared);
            assertThat(org.scope().isTeam()).isFalse();
            assertThat(scoped.scope().teamId()).isEqualTo(teamId);
            assertThat(scoped.sections()).as("takım kapsamında hata veren bölüm").noneMatch(x -> SectionResult.ERROR.equals(x.status()));
            assertThat(sql.errors()).as("takım yönetici özetinin yuttuğu SQL hataları").isEmpty();
        } finally {
            settingsRepo.deleteById(teamId);
            teams.deleteById(teamId);
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
