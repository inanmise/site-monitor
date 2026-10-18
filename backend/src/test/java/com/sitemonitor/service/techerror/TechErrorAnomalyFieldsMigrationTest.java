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

    /** 2026-10-17 düzeni, anomali bölümünde soruların hepsi KAPALI olarak yazılmış (ekip açıp hepsini kapattı). */
    static Template allSwitchesOff(String lang) {
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorTemplate.toMap(TechErrorTestTemplates.beforeAnomaly(lang)));
        List<Map<String, Object>> secs = new ArrayList<>();
        for (Object o : (List<?>) raw.get("sections")) {
            @SuppressWarnings("unchecked") Map<String, Object> sec = new LinkedHashMap<>((Map<String, Object>) o);
            if ("issues".equals(sec.get("kind"))) {
                sec.put("ask_time", false);
                sec.put("ask_outage", false);
                sec.put("ask_impact", false);
                sec.put("allow_images", false);
                sec.put("ratio_of", "");
            }
            secs.add(sec);
        }
        raw.put("sections", secs);
        return TechErrorTemplate.normalize(raw);
    }

    @Test
    @DisplayName("inceleme 2026-10-18: ekibin bütün soruları KAPATTIĞI bölüm 'hiç ayarlanmamış' bölümden ayrılır — anahtarlar false yazılır"
            + " (JSON'da kalır, okununca da ayarlanmış), eski düzende görünür ve göç onu yeniden AÇMAZ; hiç anahtarı olmayan bölüm aynen göçer")
    void allSwitchesOffStaysOff() {
        for (String lang : List.of("tr", "en")) {
            Template off = allSwitchesOff(lang);
            Section s = issues(off);
            assertThat(s.options()).as(lang).isNotNull();
            assertThat(s.opts().legacy()).isTrue();
            String json = TechErrorTemplate.toJson(off);
            assertThat(json).contains("\"ask_time\":false").contains("\"allow_images\":false").doesNotContain("time_label");
            Template back = TechErrorTemplate.fromStored(json);
            assertThat(issues(back).options()).isNotNull();
            assertThat(TechErrorTemplate.toJson(back)).isEqualTo(json);
            assertThat(TechErrorAnomalyFieldsMigration.migrate(off)).as(lang).isEmpty();
            assertThat(TechErrorAnomalyFieldsMigration.migrate(back)).isEmpty();
            // Hiç ayarlanmamış (anahtarsız) eski bölüm: JSON'da anomali anahtarı yok, göç açar
            Template old = TechErrorTestTemplates.beforeAnomaly(lang);
            assertThat(TechErrorTemplate.toJson(old)).doesNotContain("ask_time").doesNotContain("allow_images");
            assertThat(TechErrorAnomalyFieldsMigration.migrate(old)).isPresent();
        }
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
        @Autowired com.sitemonitor.repository.TechErrorTeamTemplateRepository library;
        @Autowired JdbcTemplate jdbc;

        @BeforeEach
        void clean() {
            library.deleteAll();
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

        private com.sitemonitor.model.TechErrorTeamTemplate entry(long team, String name, String json) {
            com.sitemonitor.model.TechErrorTeamTemplate e = new com.sitemonitor.model.TechErrorTeamTemplate();
            e.setTeamId(team);
            e.setName(name);
            e.setNameKey(name.toLowerCase(java.util.Locale.ROOT));
            e.setTemplateJson(json);
            e.setTemplateLang("tr");
            e.setSectionCount(8);
            e.setFieldCount(1);
            e.setSlotCount(1);
            e.setCreatedAt(Instant.parse("2026-10-17T09:00:00Z"));
            e.setUpdatedAt(Instant.parse("2026-10-17T09:00:00Z"));
            e.setUpdatedByName("Ayşe");
            return library.saveAndFlush(e);
        }

        @Test
        @DisplayName("inceleme 2026-10-18: takım ŞABLON KÜTÜPHANESİ kayıtları da göçer (yeni kanal penceresi takım varsayılanını seçer) —"
                + " 2026-10-17 düzeni bugünkü varsayılan olur, sürüm +1, 'son güncelleyen' SiteMonitor; ekibin değiştirdiği, hepsini kapattığı"
                + " ve okunamayan kayıt aynen kalır; tekrar koşmaz")
        void migratesLibraryEntries() {
            var oldTr = entry(1L, "Eski TR", TechErrorTemplate.toJson(TechErrorTestTemplates.beforeAnomaly("tr")));
            var oldEn = entry(1L, "Eski EN", TechErrorTemplate.toJson(TechErrorTestTemplates.beforeAnomaly("en")));
            String customJson = TechErrorTemplate.toJson(replaceIssues(TechErrorTestTemplates.beforeAnomaly("tr"), s -> new Section(s.id(),
                    s.kind(), s.title(), List.of(), List.of(), "{konu} sorunu", s.descriptionLabel(), s.customersLabel(), s.errorsLabel(), null)));
            var custom = entry(2L, "Kendi", customJson);
            String offJson = TechErrorTemplate.toJson(allSwitchesOff("tr"));
            var off = entry(2L, "Kapalı", offJson);
            var broken = entry(3L, "Bozuk", "{bozuk");
            long before = oldTr.getVersion();

            assertThat(migration.migrateLocked()).isEqualTo(2);
            var a = library.findById(oldTr.getId()).orElseThrow();
            assertThat(a.getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
            assertThat(a.getVersion()).isEqualTo(before + 1);
            assertThat(a.getUpdatedByName()).isEqualTo(TechErrorAnomalyFieldsMigration.ACTOR);
            assertThat(library.findById(oldEn.getId()).orElseThrow().getTemplateJson())
                    .isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("en")));
            var c = library.findById(custom.getId()).orElseThrow();
            assertThat(c.getTemplateJson()).isEqualTo(customJson);
            assertThat(c.getVersion()).isEqualTo(custom.getVersion());
            assertThat(c.getUpdatedByName()).isEqualTo("Ayşe");
            assertThat(library.findById(off.getId()).orElseThrow().getTemplateJson()).isEqualTo(offJson);
            assertThat(library.findById(broken.getId()).orElseThrow().getTemplateJson()).isEqualTo("{bozuk");
            assertThat(migration.migrateLocked()).isZero();                       // nişan yazıldı
            // Koşullu yazım: okunan sürüm değiştiyse yazmaz (arada ekip kaydetti)
            assertThat(library.migrateTemplate(a.getId(), a.getVersion() - 1, "{}", Instant.now(), "x")).isZero();
            assertThat(library.findById(a.getId()).orElseThrow().getTemplateJson()).isNotEqualTo("{}");
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
