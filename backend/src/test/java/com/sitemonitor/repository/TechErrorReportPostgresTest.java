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
            assertThat(reports.releaseClaim(r.getId(), t1, t0)).isEqualTo(1);
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

    @Test
    @DisplayName("Faz B: aynı sürüme eşzamanlı şablon kaydı → biri kazanır, kalanlar 409, geçmişte tek yeni sürüm; geçmiş "
            + "sayfası ve budama PostgreSQL'de doğru; kanal silme geçmişi de götürür")
    void historyAndTemplateVersions() throws Exception {
        var service = PostgresIt.app().bean(com.sitemonitor.service.techerror.TechErrorReportService.class);
        var history = PostgresIt.app().bean(com.sitemonitor.service.techerror.TechErrorHistoryService.class);
        var versions = PostgresIt.app().bean(TechErrorTemplateVersionRepository.class);
        var events = PostgresIt.app().bean(TechErrorReportEventRepository.class);
        long team = 765_430L + (long) (Math.random() * 1000);
        String tag = "ter-it-" + UUID.randomUUID().toString().substring(0, 8);
        TechErrorChannel ch = service.createChannel(team, tag + " ATM", null, TechErrorTemplate.defaults("tr"), null);
        try {
            int n = 5;
            var pool = java.util.concurrent.Executors.newFixedThreadPool(n);
            var start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<String>> out = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                out.add(pool.submit(() -> {
                    start.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    try {
                        service.saveTemplate(ch.getId(), 1, TechErrorTemplate.defaults("en"), null);
                        return "OK";
                    } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                        return "409";
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
            assertThat(results).filteredOn(r -> !"OK".equals(r)).allMatch("409"::equals);
            assertThat(history.templateVersions(ch.getId())).extracting(v -> v.getVersion()).containsExactly(2, 1);

            TechErrorReport r = service.createReport(ch, 2025, 5, null);
            Instant at = Instant.now();
            int total = com.sitemonitor.service.techerror.TechErrorHistoryService.MAX_EVENTS_PER_REPORT
                    + com.sitemonitor.service.techerror.TechErrorHistoryService.PRUNE_SLACK + 1;
            for (int i = 1; i < total; i++) history.record(r, "SEND", null, null, java.util.Map.of("state", "SENT"), at);
            assertThat(events.countByReportId(r.getId()))
                    .isEqualTo(com.sitemonitor.service.techerror.TechErrorHistoryService.MAX_EVENTS_PER_REPORT);
            var p1 = history.reportHistory(r.getId(), null, 30);
            var p2 = history.reportHistory(r.getId(), p1.events().get(29).getId(), 30);
            assertThat(p1.hasMore()).isTrue();
            assertThat(p2.events().get(0).getId()).isLessThan(p1.events().get(29).getId());
            assertThat(p1.recordedSince()).isNotNull();

            service.deleteChannel(ch.getId());
            assertThat(versions.countByChannelId(ch.getId())).isZero();
            assertThat(events.countByReportId(r.getId())).isZero();
        } finally {
            versions.deleteAllForChannel(ch.getId());
            PostgresIt.app().bean(TechErrorReportRepository.class).deleteAllForChannel(ch.getId());
            PostgresIt.app().bean(TechErrorChannelRepository.class).deleteById(ch.getId());
        }
    }

    @Test
    @DisplayName("değer dışa aktarımı (E23) PostgreSQL'de: dönem aritmetiği (yıl × 12 + ay, yıl sınırı), boolean süzgeç, EXISTS ile"
            + " önceki ay yüklemesi (taslak da), sayım = dışa aktarılan; servis canlı değişim oranını önceki aydan hesaplar")
    void exportQueries() {
        TechErrorChannelRepository channels = PostgresIt.app().bean(TechErrorChannelRepository.class);
        TechErrorReportRepository reports = PostgresIt.app().bean(TechErrorReportRepository.class);
        TechErrorReportExportRepository export = PostgresIt.app().bean(TechErrorReportExportRepository.class);
        String tag = "ter-exp-" + UUID.randomUUID().toString().substring(0, 8);
        long team = 765_430L + (long) (Math.random() * 1000);
        TechErrorChannel ch = new TechErrorChannel();
        ch.setTeamId(team);
        ch.setName(tag + " Mobil");
        ch.setNameKey((tag + " mobil").toLowerCase());
        ch.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        ch = channels.saveAndFlush(ch);
        try {
            Long[] ids = new Long[4];
            int[][] periods = {{2025, 12}, {2026, 1}, {2026, 8}, {2026, 9}};
            int[] totals = {400, 500, 1000, 1200};
            for (int i = 0; i < 4; i++) {
                TechErrorReport r = new TechErrorReport();
                r.setChannelId(ch.getId());
                r.setTeamId(team);
                r.setReportYear(periods[i][0]);
                r.setReportMonth(periods[i][1]);
                r.setTemplateJson(ch.getTemplateJson());
                r.setValuesJson("{\"fields\":{\"total-tx\":" + totals[i] + "}}");
                ids[i] = reports.saveAndFlush(r).getId();
            }
            Instant at = Instant.parse("2026-10-02T08:00:00.250Z");
            for (int i : new int[] {1, 3}) {
                assertThat(reports.claimSend(ids[i], at, at.minusSeconds(600))).isEqualTo(1);
                assertThat(reports.finishSent(ids[i], at, "SENT", at, 7L, "takim@example.com", 0L)).isEqualTo(1);
            }
            int from = 2026 * 12 + 1, to = 2026 * 12 + 12;
            assertThat(export.countForExport(List.of(ch.getId()), from, to, true)).isEqualTo(2L);
            assertThat(export.countForExport(List.of(ch.getId()), from, to, false)).isEqualTo(3L);
            var rows = export.findForExport(List.of(ch.getId()), from - 1, from, to, true, org.springframework.data.domain.Limit.of(10));
            assertThat(rows).extracting(TechErrorReportExportRepository.ExportRow::getId).containsExactly(ids[0], ids[1], ids[2], ids[3]);
            var sep = export.findForExport(List.of(ch.getId()), 2026 * 12 + 8, 2026 * 12 + 9, 2026 * 12 + 9, true,
                    org.springframework.data.domain.Limit.of(10));
            assertThat(sep).extracting(TechErrorReportExportRepository.ExportRow::getId).containsExactly(ids[2], ids[3]);

            var svc = PostgresIt.app().bean(com.sitemonitor.service.techerror.TechErrorExportService.class);
            var res = svc.export(List.of(ch), java.util.Map.of(team, "Takım"),
                    new com.sitemonitor.service.techerror.TechErrorExportService.Filter(2026, null, true), Instant.parse("2026-10-18T09:00:00Z"));
            assertThat(res.reports()).extracting(m -> m.reportId()).containsExactly(ids[1], ids[3]);
            int fid = com.sitemonitor.service.techerror.TechErrorExportService.COLUMNS.indexOf("field_id");
            int rid = com.sitemonitor.service.techerror.TechErrorExportService.COLUMNS.indexOf("report_id");
            int val = com.sitemonitor.service.techerror.TechErrorExportService.COLUMNS.indexOf("value");
            int comp = com.sitemonitor.service.techerror.TechErrorExportService.COLUMNS.indexOf("computed");
            // Varsayılan şablonun değişim oranı (2026-10-16): Eylül, Ağustos TASLAĞINA göre |1200 − 1000| / 1000 = %20
            var sepPct = res.rows().stream().filter(r -> ids[3].equals(r.get(rid)) && "tx-increase-pct".equals(r.get(fid)))
                    .findFirst().orElseThrow();
            assertThat(sepPct.get(val)).isEqualTo(new java.math.BigDecimal("20"));
            assertThat(sepPct.get(comp)).isEqualTo(true);
        } finally {
            reports.deleteAllForChannel(ch.getId());
            channels.deleteById(ch.getId());
        }
    }

    @Test
    @DisplayName("D19 şablon sürümü PostgreSQL'de: anahtar kümesi sayfası (kimlik + sürüm, null dahil), koşullu doldurma / bayat sürüm düzeltme, sürüm başına gruplu sayım (null ayrı)")
    void templateVersionQueries() {
        var service = PostgresIt.app().bean(com.sitemonitor.service.techerror.TechErrorReportService.class);
        TechErrorReportRepository reports = PostgresIt.app().bean(TechErrorReportRepository.class);
        long team = 765_430L + (long) (Math.random() * 1000);
        String tag = "ter-it-" + UUID.randomUUID().toString().substring(0, 8);
        TechErrorChannel ch = service.createChannel(team, tag + " Şablon sürümü", null, TechErrorTemplate.defaults("tr"), null);
        try {
            TechErrorReport a = service.createReport(ch, 2024, 1, null);
            TechErrorReport b = service.createReport(ch, 2024, 2, null);
            assertThat(reports.fillTemplateVersion(a.getId(), 3)).isZero();             // dolu (1) → boşa yazım yok
            PostgresIt.app().jdbc().update("UPDATE tech_error_reports SET template_version = NULL WHERE id = ?", b.getId());
            List<Object[]> page = reports.idsAndTemplateVersions(a.getId() - 1, org.springframework.data.domain.PageRequest.of(0, 2));
            assertThat(page).hasSize(2);
            assertThat(((Number) page.get(0)[0]).longValue()).isEqualTo(a.getId());
            assertThat(((Number) page.get(0)[1]).intValue()).isEqualTo(1);
            assertThat(page.get(1)[1]).isNull();
            assertThat(reports.fillTemplateVersion(b.getId(), 2)).isEqualTo(1);
            assertThat(reports.replaceTemplateVersion(a.getId(), 9, 2)).isZero();       // okunan değer değil → yazmaz
            assertThat(reports.replaceTemplateVersion(a.getId(), 1, 2)).isEqualTo(1);
            TechErrorReport c = service.createReport(ch, 2024, 3, null);
            PostgresIt.app().jdbc().update("UPDATE tech_error_reports SET template_version = NULL WHERE id = ?", c.getId());
            java.util.Map<Integer, Long> counts = service.templateVersionCounts(ch.getId());
            assertThat(counts).containsEntry(2, 2L).containsEntry(null, 1L);
        } finally {
            reports.deleteAllForChannel(ch.getId());
            PostgresIt.app().bean(TechErrorTemplateVersionRepository.class).deleteAllForChannel(ch.getId());
            PostgresIt.app().bean(TechErrorChannelRepository.class).deleteById(ch.getId());
        }
    }

    @Test
    @DisplayName("kanal analizi, pasif kanallar (inceleme 2026-10-18) PostgreSQL'de: takım IN listesiyle tek gruplu sorgu — pasif kanal"
            + " başına istenen dönemdeki en yeni dönem (yıl × 12 + ay, yıl sınırı); aktif kanal ve pencere dışı satır vermez")
    void analyticsNewestPeriodOfInactiveChannels() {
        TechErrorChannelRepository channels = PostgresIt.app().bean(TechErrorChannelRepository.class);
        TechErrorReportRepository reports = PostgresIt.app().bean(TechErrorReportRepository.class);
        String tag = "ter-an-" + UUID.randomUUID().toString().substring(0, 8);
        long team = 765_430L + (long) (Math.random() * 1000);
        TechErrorChannel active = new TechErrorChannel();
        active.setTeamId(team);
        active.setName(tag + " Mobil");
        active.setNameKey((tag + " mobil").toLowerCase());
        active.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        active = channels.saveAndFlush(active);
        TechErrorChannel passive = new TechErrorChannel();
        passive.setTeamId(team);
        passive.setName(tag + " Arşiv");
        passive.setNameKey((tag + " arşiv").toLowerCase());
        passive.setActive(false);
        passive.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        passive = channels.saveAndFlush(passive);
        try {
            int[][] periods = {{2025, 11}, {2025, 12}, {2026, 10}};
            for (int[] p : periods) {
                TechErrorReport r = new TechErrorReport();
                r.setChannelId(passive.getId());
                r.setTeamId(team);
                r.setReportYear(p[0]);
                r.setReportMonth(p[1]);
                r.setTemplateJson(passive.getTemplateJson());
                r.setValuesJson("{\"fields\":{\"total-tx\":1}}");
                reports.saveAndFlush(r);
            }
            TechErrorReport own = new TechErrorReport();
            own.setChannelId(active.getId());
            own.setTeamId(team);
            own.setReportYear(2026);
            own.setReportMonth(9);
            own.setTemplateJson(active.getTemplateJson());
            own.setValuesJson("{\"fields\":{\"total-tx\":2}}");
            reports.saveAndFlush(own);
            int from = 2025 * 12 + 10, to = 2026 * 12 + 9;
            List<Object[]> rows = reports.newestPeriodOfInactiveChannels(List.of(team), from, to);
            assertThat(rows).hasSize(1);
            assertThat(((Number) rows.get(0)[0]).longValue()).isEqualTo(passive.getId());
            assertThat(((Number) rows.get(0)[1]).intValue()).isEqualTo(2025 * 12 + 12);
            assertThat(reports.newestPeriodOfInactiveChannels(List.of(team), 2026 * 12 + 1, to)).isEmpty();
        } finally {
            reports.deleteAllForChannel(passive.getId());
            reports.deleteAllForChannel(active.getId());
            channels.deleteById(passive.getId());
            channels.deleteById(active.getId());
        }
    }
}
