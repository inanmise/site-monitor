package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorReportEvent;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportEventRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.SchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Raporun şablon sürümü GERİ DOLDURMASI (2026-10-18, D19): sütundan önce açılmış raporların sürümü kendi geçmişinden
 * (CREATE / TEMPLATE_REFRESH olayı, en yenisi) bulunur, olayı olmayan "bilinmiyor" kalır; yazım koşulludur; nişan yalnız
 * tam taramadan sonra; iş kilit altında ve nişan varken koşmaz.
 */
class TechErrorReportTemplateVersionBackfillTest {

    @Test
    @DisplayName("ayrıntıdan sürüm: pozitif tam sayı; yoksa / bozuksa / ondalıksa / sıfırsa null (tahmin yok)")
    void versionOf() {
        assertThat(TechErrorReportTemplateVersionBackfill.versionOf("{\"template_version\":3}")).isEqualTo(3);
        assertThat(TechErrorReportTemplateVersionBackfill.versionOf("{\"template_version\":3,\"removed_values\":1}")).isEqualTo(3);
        assertThat(TechErrorReportTemplateVersionBackfill.versionOf("{\"removed_values\":1}")).isNull();
        assertThat(TechErrorReportTemplateVersionBackfill.versionOf("{\"template_version\":0}")).isNull();
        assertThat(TechErrorReportTemplateVersionBackfill.versionOf("{\"template_version\":2.5}")).isNull();
        assertThat(TechErrorReportTemplateVersionBackfill.versionOf("{\"template_version\":\"3\"}")).isNull();
        assertThat(TechErrorReportTemplateVersionBackfill.versionOf("{bozuk")).isNull();
        assertThat(TechErrorReportTemplateVersionBackfill.versionOf(null)).isNull();
    }

    @Test
    @DisplayName("iş: nişan varsa (ya da okunamıyorsa) kilit hiç istenmez; yoksa kendi kilidi altında koşar")
    void runRespectsMarker() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        SchedulerService scheduler = mock(SchedulerService.class);
        @SuppressWarnings("unchecked") ObjectProvider<SchedulerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scheduler);
        TechErrorReportTemplateVersionBackfill job = new TechErrorReportTemplateVersionBackfill(
                mock(TechErrorReportRepository.class), mock(TechErrorReportEventRepository.class), jdbc, provider);
        job.run();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(Supplier.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenThrow(new RuntimeException("db yok"));
        job.run();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(Supplier.class));
        org.mockito.Mockito.reset(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(scheduler.callWithSchedulerLock(anyString(), any(Supplier.class))).thenReturn(Optional.of(0));
        job.run();
        verify(scheduler).callWithSchedulerLock(eq(TechErrorReportTemplateVersionBackfill.LOCK), any(Supplier.class));
    }

    /** H2'de gerçek SQL: olaylardan doldurma, koşullu yazım, nişan, süre bütçesi. */
    @Nested
    @DataJpaTest
    @Import({TechErrorReportService.class, TechErrorHistoryService.class, TechErrorReportTemplateVersionBackfill.class})
    @TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    class Job {
        @Autowired TechErrorReportService service;
        @Autowired TechErrorReportTemplateVersionBackfill backfill;
        @Autowired TechErrorChannelRepository channels;
        @Autowired TechErrorReportRepository reports;
        @Autowired TechErrorReportEventRepository events;
        @Autowired com.sitemonitor.repository.TechErrorTemplateVersionRepository versions;
        @Autowired JdbcTemplate jdbc;

        @BeforeEach
        void clean() {
            events.deleteAll();
            versions.deleteAll();
            reports.deleteAll();
            channels.deleteAll();
            jdbc.execute(com.sitemonitor.service.StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key = ?", TechErrorReportTemplateVersionBackfill.KEY);
            service.useClock(Clock.fixed(Instant.parse("2026-10-13T09:00:00Z"), ZoneOffset.UTC));
            backfill.clock = System::currentTimeMillis;
        }

        /** Sütundan önceki rapor gibi: sürümü boşaltılır (geçmiş olayı durur). */
        private TechErrorReport legacy(TechErrorChannel ch, int month) {
            TechErrorReport r = service.createReport(ch, 2026, month, 7L);
            TechErrorReport fresh = reports.findById(r.getId()).orElseThrow();
            fresh.setTemplateVersion(null);
            return reports.saveAndFlush(fresh);
        }

        private Integer versionOf(TechErrorReport r) {
            return reports.findById(r.getId()).orElseThrow().getTemplateVersion();
        }

        @Test
        @DisplayName("açılış olayından sürüm; sonraki 'son hâli uygula' olayı kazanır; olayı olmayan rapor null kalır; dolu rapor değişmez; nişan yazılır, ikinci koşu bir şey yapmaz")
        void fillsFromHistory() {
            TechErrorChannel ch = service.createChannel(1L, "Mobil", null, TechErrorTemplate.defaults("tr"), 7L);
            TechErrorReport fromCreate = legacy(ch, 8);                               // CREATE olayı: template_version 1
            int v2 = service.saveTemplate(ch.getId(), 1, TechErrorTemplate.defaults("en"), 7L);
            TechErrorChannel ch2 = channels.findById(ch.getId()).orElseThrow();
            TechErrorReport refreshed = legacy(ch, 7);                                // CREATE v1 …
            TechErrorReport rr = reports.findById(refreshed.getId()).orElseThrow();
            service.refreshTemplate(rr.getId(), rr.getVersion(), 7L);                 // … sonra REFRESH v2
            TechErrorReport rr2 = reports.findById(refreshed.getId()).orElseThrow();
            rr2.setTemplateVersion(null);
            reports.saveAndFlush(rr2);
            TechErrorReport noHistory = legacy(ch2, 6);
            events.deleteAll(events.findByReportIdOrderByIdDesc(noHistory.getId(), org.springframework.data.domain.PageRequest.of(0, 50)));
            TechErrorReport filled = service.createReport(ch2, 2026, 5, 7L);          // yeni yol: zaten v2
            // Bozuk ayrıntılı en yeni olay: daha eski olaya düşülmez (bilinmiyor)
            TechErrorReport brokenLatest = legacy(ch, 4);
            TechErrorReportEvent bad = new TechErrorReportEvent();
            bad.setReportId(brokenLatest.getId());
            bad.setChannelId(ch.getId());
            bad.setTeamId(1L);
            bad.setKind("TEMPLATE_REFRESH");
            bad.setEventAt(Instant.parse("2026-10-13T10:00:00Z"));
            bad.setDetailJson("{bozuk");
            events.save(bad);

            assertThat(backfill.done()).isFalse();
            assertThat(backfill.backfillLocked()).isEqualTo(2);
            assertThat(versionOf(fromCreate)).isEqualTo(1);
            assertThat(versionOf(refreshed)).isEqualTo(v2);
            assertThat(versionOf(noHistory)).isNull();
            assertThat(versionOf(brokenLatest)).isNull();
            assertThat(versionOf(filled)).isEqualTo(v2);
            assertThat(backfill.done()).isTrue();
            assertThat(backfill.backfillLocked()).isZero();
            assertThat(service.templateVersionCounts(ch.getId())).containsEntry(1, 1L).containsEntry(2, 2L).containsEntry(null, 2L);
        }

        @Test
        @DisplayName("koşullu yazım: arada sürüm yazıldıysa (son hâli uygula / yeni pod) geri doldurma ezmez")
        void conditionalWrite() {
            TechErrorChannel ch = service.createChannel(1L, "Mobil", null, TechErrorTemplate.defaults("tr"), 7L);
            TechErrorReport r = legacy(ch, 8);
            assertThat(reports.fillTemplateVersion(r.getId(), 5)).isEqualTo(1);
            assertThat(reports.fillTemplateVersion(r.getId(), 1)).isZero();
            assertThat(versionOf(r)).isEqualTo(5);
            assertThat(backfill.backfillLocked()).isZero();
            assertThat(versionOf(r)).isEqualTo(5);
        }

        @Test
        @DisplayName("süre bütçesi dolarsa nişan yazılmaz (kalan satırlar sonraki koşuda); bir sonraki koşu tamamlar")
        void budgetStopsWithoutMarker() {
            TechErrorChannel ch = service.createChannel(1L, "Mobil", null, TechErrorTemplate.defaults("tr"), 7L);
            TechErrorReport r = legacy(ch, 8);
            long[] t = { 0 };
            backfill.clock = () -> { t[0] += TechErrorReportTemplateVersionBackfill.BUDGET_MS + 1; return t[0]; };
            assertThat(backfill.backfillLocked()).isEqualTo(1);
            assertThat(backfill.done()).isFalse();
            backfill.clock = System::currentTimeMillis;
            assertThat(backfill.backfillLocked()).isZero();
            assertThat(backfill.done()).isTrue();
            assertThat(versionOf(r)).isEqualTo(1);
            assertThat(List.of(r.getId())).isNotEmpty();
        }
    }
}
