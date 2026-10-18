package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Anomali soruları göçü (2026-10-18): 2026-10-17 varsayılan etiketli, sorusuz anomali bölümü bugünkü varsayılan sorularını alır
 * (sonuç bugünkü varsayılanla bayt bayt aynı); ekibin değiştirdiği bölüme dokunulmaz; tekrar uygulanmaz; iş adet göçünü bekler.
 */
class TechErrorAnomalyFieldsMigrationTest {

    static Section issues(Template t) {
        return t.sections().stream().filter(s -> "issues".equals(s.kind())).findFirst().orElseThrow();
    }

    static Template replaceIssues(Template t, UnaryOperator<Section> fn) {
        List<Section> secs = new ArrayList<>();
        for (Section s : t.sections()) secs.add("issues".equals(s.kind()) ? fn.apply(s) : s);
        return TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(t.lang(), t.title(), List.copyOf(secs))));
    }

    @Test
    @DisplayName("2026-10-17 düzeni (TR / EN) bugünkü varsayılanın aynısına döner (sorular açık, pay 'error-count'); tekrar uygulanmaz; bugünkü varsayılanda iş yok; bölüm başlığı değişmiş olsa da etiketler varsayılansa göçer")
    void defaultBecomesToday() {
        for (String lang : List.of("tr", "en")) {
            Template old = TechErrorTestTemplates.beforeAnomaly(lang);
            assertThat(issues(old).options()).isNull();
            Template m = TechErrorAnomalyFieldsMigration.migrate(old).orElseThrow();
            assertThat(TechErrorTemplate.toJson(m)).as(lang).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults(lang)));
            TechErrorTemplate.normalize(TechErrorTemplate.toMap(m));                    // katı doğrulamadan geçer
            assertThat(TechErrorAnomalyFieldsMigration.migrate(m)).isEmpty();
            assertThat(TechErrorAnomalyFieldsMigration.migrate(TechErrorTemplate.defaults(lang))).isEmpty();
            // Yalnız bölüm başlığı değişmiş: etiketler + kalıp varsayılan → göçer
            Template retitled = replaceIssues(old, s -> s.withTitle("Bizim anomalilerimiz"));
            Template mr = TechErrorAnomalyFieldsMigration.migrate(retitled).orElseThrow();
            assertThat(issues(mr).title()).isEqualTo("Bizim anomalilerimiz");
            assertThat(issues(mr).opts().askTime()).isTrue();
        }
    }

    @Test
    @DisplayName("ekibin değiştirdiği bölüme dokunulmaz: başka kalıp, başka açıklama / müşteri / hata etiketi, açılmış bir soru; 'Ay içindeki teknik hata adedi' sayı alanı yoksa pay sorulmaz (diğer sorular yine eklenir)")
    void changedSectionsStay() {
        Template base = TechErrorTestTemplates.beforeAnomaly("tr");
        Section s0 = issues(base);
        assertThat(TechErrorAnomalyFieldsMigration.migrate(replaceIssues(base, s -> new Section(s.id(), s.kind(), s.title(), List.of(),
                List.of(), "{konu} sorunu", s.descriptionLabel(), s.customersLabel(), s.errorsLabel(), null)))).isEmpty();
        assertThat(TechErrorAnomalyFieldsMigration.migrate(replaceIssues(base, s -> new Section(s.id(), s.kind(), s.title(), List.of(),
                List.of(), s.itemTitle(), "Açıklama", s.customersLabel(), s.errorsLabel(), null)))).isEmpty();
        assertThat(TechErrorAnomalyFieldsMigration.migrate(replaceIssues(base, s -> new Section(s.id(), s.kind(), s.title(), List.of(),
                List.of(), s.itemTitle(), s.descriptionLabel(), "Müşteri", s.errorsLabel(), null)))).isEmpty();
        assertThat(TechErrorAnomalyFieldsMigration.migrate(replaceIssues(base, s -> new Section(s.id(), s.kind(), s.title(), List.of(),
                List.of(), s.itemTitle(), s.descriptionLabel(), s.customersLabel(), "Hata", null)))).isEmpty();
        // Ekip yalnız görsellere izin vermiş: dokunulmaz (seçimi ekibin)
        Template own = replaceIssues(base, s -> s.withOptions(new TechErrorTemplate.IssueOptions(false, null, false, null, null, false,
                null, null, true, null)));
        assertThat(TechErrorAnomalyFieldsMigration.migrate(own)).isEmpty();
        // "Ay içindeki teknik hata adedi" yok → pay sorulmaz
        List<Section> noTotal = new ArrayList<>();
        for (Section s : base.sections()) {
            if ("metrics".equals(s.kind())) {
                List<Field> fs = s.fields().stream().filter(f -> !"error-count".equals(f.id())).toList();
                noTotal.add(s.withFields(fs));
            } else {
                noTotal.add(s);
            }
        }
        Template t2 = new Template(base.lang(), base.title(), List.copyOf(noTotal));
        var o = issues(TechErrorAnomalyFieldsMigration.migrate(t2).orElseThrow()).opts();
        assertThat(o.ratioOf()).isNull();
        assertThat(o.askOutage()).isTrue();
        assertThat(s0.opts().legacy()).isTrue();
    }

    @Test
    @DisplayName("iş: adet göçünün nişanı yokken koşmaz; varsa kilit altında koşar")
    void waitsForCountsMigration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenAnswer(inv -> 0);
        SchedulerService scheduler = mock(SchedulerService.class);
        @SuppressWarnings("unchecked") ObjectProvider<SchedulerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scheduler);
        TechErrorAnomalyFieldsMigration job = new TechErrorAnomalyFieldsMigration(mock(TechErrorChannelRepository.class),
                mock(TechErrorReportService.class), jdbc, provider);
        job.run();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(Supplier.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> TechErrorCountsInFiguresMigration.KEY.equals(inv.getArgument(2)) ? 1 : 0);
        when(scheduler.callWithSchedulerLock(anyString(), any(Supplier.class))).thenReturn(Optional.of(0));
        job.run();
        verify(scheduler).callWithSchedulerLock(eq(TechErrorAnomalyFieldsMigration.LOCK), any(Supplier.class));
    }

    @Test
    @DisplayName("iş: kilit hatası sayılır, 24 başarısız denemeden sonra pod bırakır (nişan yazılmaz)")
    void givesUpAfterMaxAttempts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> TechErrorCountsInFiguresMigration.KEY.equals(inv.getArgument(2)) ? 1 : 0);
        SchedulerService scheduler = mock(SchedulerService.class);
        @SuppressWarnings("unchecked") ObjectProvider<SchedulerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scheduler);
        when(scheduler.callWithSchedulerLock(anyString(), any(Supplier.class))).thenThrow(new IllegalStateException("db"));
        TechErrorAnomalyFieldsMigration job = new TechErrorAnomalyFieldsMigration(mock(TechErrorChannelRepository.class),
                mock(TechErrorReportService.class), jdbc, provider);
        for (int i = 0; i < TechErrorAnomalyFieldsMigration.MAX_ATTEMPTS + 5; i++) job.run();
        verify(scheduler, org.mockito.Mockito.times(TechErrorAnomalyFieldsMigration.MAX_ATTEMPTS))
                .callWithSchedulerLock(anyString(), any(Supplier.class));
    }

    /** H2'de gerçek SQL: nişan, sürüm geçmişi, açılmış rapora dokunulmaması, taslağa uygulamada konu değerlerinin korunması. */
    @Nested
    @DataJpaTest
    @Import({TechErrorReportService.class, TechErrorHistoryService.class, TechErrorAnomalyFieldsMigration.class})
    @TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    class Job {
        @Autowired TechErrorReportService service;
        @Autowired TechErrorAnomalyFieldsMigration migration;
        @Autowired TechErrorChannelRepository channels;
        @Autowired TechErrorReportRepository reports;
        @Autowired com.sitemonitor.repository.TechErrorReportEventRepository events;
        @Autowired com.sitemonitor.repository.TechErrorTemplateVersionRepository versions;
        @Autowired JdbcTemplate jdbc;

        @BeforeEach
        void clean() {
            events.deleteAll();
            versions.deleteAll();
            reports.deleteAll();
            channels.deleteAll();
            jdbc.execute(com.sitemonitor.service.StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key IN (?, ?)", TechErrorAnomalyFieldsMigration.KEY,
                    TechErrorCountsInFiguresMigration.KEY);
            service.useClock(Clock.fixed(Instant.parse("2026-10-18T09:00:00Z"), ZoneOffset.UTC));
        }

        @Test
        @DisplayName("iş: 2026-10-17 düzenli kanallar (TR / EN) güncellenir (sürüm +1, geçmişte 'SiteMonitor'), bugünkü varsayılan ve ekibin değiştirdiği dokunulmaz; açılmış rapor aynen kalır; nişan sonrası koşmaz")
        void migratesOnce() {
            TechErrorChannel a = service.createChannel(1L, "Mobil", null, TechErrorTestTemplates.beforeAnomaly("tr"), 7L);
            TechErrorChannel b = service.createChannel(1L, "POS", null, TechErrorTestTemplates.beforeAnomaly("en"), 7L);
            TechErrorChannel fresh = service.createChannel(1L, "ATM", null, TechErrorTemplate.defaults("tr"), 7L);
            Template custom = replaceIssues(TechErrorTestTemplates.beforeAnomaly("tr"), s -> new Section(s.id(), s.kind(), s.title(),
                    List.of(), List.of(), "{konu} sorunu", s.descriptionLabel(), s.customersLabel(), s.errorsLabel(), null));
            TechErrorChannel own = service.createChannel(1L, "Kurumsal", null, custom, 7L);
            TechErrorReport opened = service.createReport(a, 2026, 9, 7L);
            String openedTemplate = opened.getTemplateJson();
            assertThat(migration.marker(TechErrorAnomalyFieldsMigration.KEY)).isFalse();

            assertThat(migration.migrateLocked()).isEqualTo(2);
            TechErrorChannel afterA = channels.findById(a.getId()).orElseThrow();
            assertThat(afterA.getTemplateVersion()).isEqualTo(2);
            assertThat(afterA.getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
            assertThat(channels.findById(b.getId()).orElseThrow().getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("en")));
            assertThat(channels.findById(fresh.getId()).orElseThrow().getTemplateVersion()).isEqualTo(1);
            assertThat(channels.findById(own.getId()).orElseThrow().getTemplateVersion()).isEqualTo(1);
            assertThat(versions.findAll()).anySatisfy(v -> {
                assertThat(v.getChannelId()).isEqualTo(a.getId());
                assertThat(v.getVersion()).isEqualTo(2);
                assertThat(v.getSavedByName()).isEqualTo(TechErrorAnomalyFieldsMigration.ACTOR);
            });
            TechErrorReport r = reports.findById(opened.getId()).orElseThrow();
            assertThat(r.getTemplateJson()).isEqualTo(openedTemplate);
            assertThat(service.refreshPreview(r, afterA)).containsEntry("differs", true);
            assertThat(migration.done()).isTrue();
            assertThat(migration.migrateLocked()).isZero();
            assertThat(channels.findById(a.getId()).orElseThrow().getTemplateVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("taslağa şablonun son hâli uygulanınca eski konular kalır (kayıp 0): müşteri sayısı girilmiş konu 'müşteri etkisi var', 0 girilmiş 'yok'; pay hesaplanır")
        void refreshKeepsTopics() {
            TechErrorChannel ch = service.createChannel(1L, "Mobil", null, TechErrorTestTemplates.beforeAnomaly("tr"), 7L);
            TechErrorReport sep = service.createReport(ch, 2026, 9, 7L);
            Map<String, Object> k1 = new LinkedHashMap<>(Map.of("id", "k1", "topic", "Kart", "title_auto", true, "customers", 320, "errors", 100));
            Map<String, Object> k2 = new LinkedHashMap<>(Map.of("id", "k2", "topic", "Fatura", "title_auto", true, "customers", 0, "errors", 50));
            TechErrorReport s = service.saveValues(sep.getId(), sep.getVersion(),
                    Map.of("fields", Map.of("error-count", 1000), "issues", Map.of("issues", List.of(k1, k2))), 7L);
            assertThat(s.getValuesJson()).doesNotContain("\"impact\"").doesNotContain("\"share\"");     // eski düzen: soru yok
            migration.migrateLocked();
            Map<String, Object> preview = service.refreshPreview(reports.findById(sep.getId()).orElseThrow(),
                    channels.findById(ch.getId()).orElseThrow());
            assertThat(preview).containsEntry("differs", true).containsEntry("lost_values", 0);
            service.refreshTemplate(sep.getId(), s.getVersion(), 7L);
            TechErrorReport after = reports.findById(sep.getId()).orElseThrow();
            Template t = TechErrorTemplate.fromStored(after.getTemplateJson());
            var list = TechErrorValues.fromStored(after.getValuesJson(), t).issues().get("issues");
            assertThat(list).extracting(TechErrorValues.Issue::id).containsExactly("k1", "k2");
            assertThat(list.get(0).impact()).isTrue();
            assertThat(list.get(0).customers()).isEqualTo(320L);
            assertThat(list.get(1).impact()).isFalse();
            assertThat(list.get(1).customers()).isNull();
            assertThat(after.getValuesJson()).contains("\"share\":10").contains("\"share\":5");
        }
    }
}
