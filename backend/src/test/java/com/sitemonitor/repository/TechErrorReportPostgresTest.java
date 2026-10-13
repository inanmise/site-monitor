package com.sitemonitor.repository;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorReportImage;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kanal teknik hata raporu tabloları (2026-10-13) GERÇEK PostgreSQL'de: görsel kolonu BYTEA (H2'deki 255 bayt sınırı
 * yok), milisaniyeli gönderim talebi eşitliği (timestamptz), koşullu talep / sonuç, grup sayımları (bigint), yuva silme
 * ve kanal × ay tekilliği.
 */
@PostgresIntegration
class TechErrorReportPostgresTest {

    @Test
    @DisplayName("BYTEA, talep/sonuç, sayımlar, yuva silme ve tekillik PostgreSQL'de doğru")
    void queries() {
        TechErrorChannelRepository channels = PostgresIt.app().bean(TechErrorChannelRepository.class);
        TechErrorReportRepository reports = PostgresIt.app().bean(TechErrorReportRepository.class);
        TechErrorReportImageRepository images = PostgresIt.app().bean(TechErrorReportImageRepository.class);
        String tag = "ter-it-" + UUID.randomUUID().toString().substring(0, 8);
        long team = 765_430L + (long) (Math.random() * 1000);

        TechErrorChannel ch = new TechErrorChannel();
        ch.setTeamId(team);
        ch.setName(tag + " İnternet Şubesi");
        ch.setNameKey((tag + " internet şubesi").toLowerCase());
        ch.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        ch = channels.saveAndFlush(ch);
        try {
            TechErrorReport r = new TechErrorReport();
            r.setChannelId(ch.getId());
            r.setTeamId(team);
            r.setReportYear(2026);
            r.setReportMonth(9);
            r.setTemplateJson(ch.getTemplateJson());
            r.setValuesJson(TechErrorValues.toJson(TechErrorValues.Values.empty()));
            r = reports.saveAndFlush(r);
            Long version = r.getVersion();

            byte[] big = new byte[300_000];
            for (int i = 0; i < big.length; i++) big[i] = (byte) i;
            TechErrorReportImage img = new TechErrorReportImage();
            img.setReportId(r.getId());
            img.setTeamId(team);
            img.setSlotId("monthly-tx");
            img.setContentType("image/png");
            img.setWidth(800);
            img.setHeight(400);
            img.setSizeBytes(big.length);
            img.setData(big);
            img = images.saveAndFlush(img);
            assertThat(images.findById(img.getId()).orElseThrow().getData()).isEqualTo(big);
            String type = PostgresIt.app().jdbc().queryForObject(
                    "SELECT data_type FROM information_schema.columns WHERE table_name = 'tech_error_report_images' AND column_name = 'data'",
                    String.class);
            assertThat(type).isEqualTo("bytea");

            Object[] counts = images.countsByReport(List.of(r.getId())).get(0);
            assertThat(((Number) counts[1]).longValue()).isEqualTo(1L);
            assertThat(((Number) counts[2]).longValue()).isEqualTo(300_000L);
            assertThat(reports.countByChannel(List.of(ch.getId())).get(0)[1]).isInstanceOf(Number.class);

            Instant t0 = Instant.parse("2026-10-13T09:00:00.123Z");
            assertThat(reports.claimSend(r.getId(), t0, t0.minusSeconds(600))).isEqualTo(1);
            assertThat(reports.claimSend(r.getId(), t0.plusSeconds(1), t0.minusSeconds(599))).isZero();
            assertThat(reports.finishSent(r.getId(), t0, "SENT", t0, 7L, "a@example.com", 0L)).isEqualTo(1);
            assertThat(reports.touchContent(r.getId(), t0.plusSeconds(60))).isEqualTo(1);
            TechErrorReport after = reports.findById(r.getId()).orElseThrow();
            assertThat(after.getVersion()).isEqualTo(version);
            assertThat(after.getSendCount()).isEqualTo(1);
            assertThat(after.getContentChangedAt()).isAfter(after.getLastSentAt());

            assertThat(images.deleteSlotsNotIn(r.getId(), List.of("tech-errors"))).isEqualTo(1);
            assertThat(images.findByReportIdOrderByIdAsc(r.getId())).isEmpty();

            TechErrorReport dup = new TechErrorReport();
            dup.setChannelId(ch.getId());
            dup.setTeamId(team);
            dup.setReportYear(2026);
            dup.setReportMonth(9);
            dup.setTemplateJson("{}");
            dup.setValuesJson("{}");
            assertThatThrownBy(() -> reports.saveAndFlush(dup)).isInstanceOf(DataIntegrityViolationException.class);
            assertThat(reports.findByChannelIdInAndReportYearAndReportMonth(List.of(ch.getId()), 2026, 9))
                    .extracting(TechErrorReportRepository.Row::getId).hasSize(1);
        } finally {
            for (Long id : reports.idsForChannel(ch.getId())) images.deleteAllForReport(id);
            reports.deleteAllForChannel(ch.getId());
            channels.deleteById(ch.getId());
        }
    }

    @Test
    @DisplayName("aynı ay için eşzamanlı rapor açma: biri kazanır, diğerleri 409 REPORT_EXISTS (bozuk işlemde okuma → 500 yok)")
    void concurrentCreateIsConflictNot500() throws Exception {
        var service = PostgresIt.app().bean(com.sitemonitor.service.techerror.TechErrorReportService.class);
        TechErrorReportRepository reports = PostgresIt.app().bean(TechErrorReportRepository.class);
        long team = 765_430L + (long) (Math.random() * 1000);
        String tag = "ter-it-" + UUID.randomUUID().toString().substring(0, 8);
        TechErrorChannel ch = service.createChannel(team, tag + " POS", null, TechErrorTemplate.defaults("tr"), null);
        try {
            int n = 6;
            var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<String>> out = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                out.add(pool.submit(() -> {
                    start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        service.createReport(ch, 2025, 6, null);
                        return "OK";
                    } catch (com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException e) {
                        return e.getCode();
                    } catch (RuntimeException e) {
                        return "ERR:" + e.getClass().getSimpleName();
                    }
                }));
            }
            start.countDown();
            List<String> results = new java.util.ArrayList<>();
            for (var f : out) results.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            pool.shutdownNow();
            assertThat(results).filteredOn("OK"::equals).hasSize(1);
            assertThat(results).filteredOn(r -> !"OK".equals(r)).allMatch("REPORT_EXISTS"::equals);

            // Koşullu silme PostgreSQL'de: üye kipinde gönderilmiş rapor silinmez
            TechErrorReport r = reports.findByChannelIdAndReportYearAndReportMonth(ch.getId(), 2025, 6).orElseThrow();
            Instant t0 = Instant.now().minusSeconds(1200);
            reports.claimSend(r.getId(), t0, t0.minusSeconds(600));
            reports.finishSent(r.getId(), t0, "SENT", t0, null, "a@example.com", 0L);
            // Talep bırakma satırın KENDİ son sonucundan (CASE + LIKE PostgreSQL'de)
            Instant t1 = Instant.now().minusSeconds(60);
            assertThat(reports.claimSend(r.getId(), t1, t1.minusSeconds(600))).isEqualTo(1);
            assertThat(reports.releaseClaim(r.getId(), t1)).isEqualTo(1);
            assertThat(reports.findById(r.getId()).orElseThrow().getSendState()).isEqualTo("SENT");
            assertThat(reports.touchContent(r.getId(), Instant.now())).isEqualTo(1);
            assertThat(reports.findById(r.getId()).orElseThrow().getContentRev()).isGreaterThan(0L);
            assertThat(reports.countSendingForChannel(ch.getId(), Instant.now().minusSeconds(600))).isZero();
            assertThat(reports.deleteIfAllowed(r.getId(), true, Instant.now().minusSeconds(600))).isZero();
            assertThat(reports.deleteIfAllowed(r.getId(), false, Instant.now().minusSeconds(600))).isEqualTo(1);
        } finally {
            reports.deleteAllForChannel(ch.getId());
            PostgresIt.app().bean(TechErrorChannelRepository.class).deleteById(ch.getId());
        }
    }
}
