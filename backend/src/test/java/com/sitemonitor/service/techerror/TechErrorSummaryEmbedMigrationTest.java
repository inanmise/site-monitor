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
 * Özet göçü (2026-10-17): eski varsayılanla birebir aynı map / kota bölümleri özet kartına gömülür, anomali adları
 * nitelik nitelik yenilenir; ekibin değiştirdiği bölüme dokunulmaz; tekrar uygulanmaz; iş oran göçünü bekler.
 */
class TechErrorSummaryEmbedMigrationTest {

    static Section section(Template t, String id) {
        return t.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    static Field field(Template t, String id) {
        return t.allFields().stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
    }

    static Section issuesOld(boolean en) {
        return en
                ? new Section("issues", "issues", "Topics where technical errors rose abnormally", List.of(), List.of(),
                        "{topic} issue", "When, where and how did it happen?", "Affected customers",
                        "Technical errors caused by this issue", null)
                : new Section("issues", "issues", "Teknik hataların anomali olarak arttığı konular", List.of(), List.of(),
                        "{konu} Konusu ile yaşanan problem", "Ne zaman, nerede, nasıl gerçekleşti?", "Etkilenen müşteri sayısı",
                        "Bu sorundan kaynaklı teknik hata adedi", null);
    }

    static Section metrics(String id, String title, List<Field> fields) {
        return new Section(id, "metrics", title, fields, List.of(), null, null, null, null, null);
    }

    static Template normalized(Template d, List<Section> secs) {
        return TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(d.lang(), d.title(), List.copyOf(secs))));
    }

    /**
     * 20.138 varsayılanı (şekil A): adetler "Verileri" bölümünde (teknik hata adedinden sonra), map / kota bölümleri yalnız
     * "Açıklama" / "Notes"; anomali eski adlarla. 4a düzeninden (gömülü map / kota, Verileri'nde adet yok —
     * {@link TechErrorTestTemplates#hostedDefaults}) türetilir; bugünkü varsayılan (7. sürüm) adetleri Verileri'nde taşır.
     */
    static Template shapeA(String lang) {
        boolean en = "en".equals(lang);
        Template d = TechErrorTestTemplates.hostedDefaults(lang);
        List<Section> out = new ArrayList<>();
        for (Section s : d.sections()) {
            switch (s.id()) {
                case "unmapped", "quota" -> { }
                case "summary" -> {
                    List<Field> fs = new ArrayList<>();
                    for (Field f : s.fields()) {
                        fs.add(f);
                        if ("error-count".equals(f.id())) {
                            fs.add(new Field("unmapped-count", en ? "Unmapped errors" : "Map yapılmamış hata adedi", "number", null));
                            fs.add(new Field("quota-count", en ? "Quota errors" : "Kota hatası adedi", "number", null));
                        }
                    }
                    out.add(s.withFields(List.copyOf(fs)));
                }
                case "issues" -> {
                    out.add(issuesOld(en));
                    out.add(metrics("unmapped", en ? "Unmapped Errors" : "Map Yapılmamış Hatalar",
                            List.of(new Field("unmapped-note", en ? "Notes" : "Açıklama", "textarea", null))));
                    out.add(metrics("quota", en ? "Quota Errors" : "Kota Hataları",
                            List.of(new Field("quota-note", en ? "Notes" : "Açıklama", "textarea", null))));
                }
                default -> out.add(s);
            }
        }
        return normalized(d, out);
    }

    /** 20.138 öncesi (şekil B, oran göçünden sonra): map / kota bölümleri adet + "Açıklama", eski başlıklarla, anomali eski adlarla. */
    static Template shapeB(String lang) {
        boolean en = "en".equals(lang);
        Template d = TechErrorTestTemplates.hostedDefaults(lang);
        List<Section> out = new ArrayList<>();
        for (Section s : d.sections()) {
            switch (s.id()) {
                case "unmapped", "quota" -> { }
                case "issues" -> {
                    out.add(issuesOld(en));
                    out.add(metrics("unmapped", en ? "Unmapped Error Count" : "Map Yapılmamış Hata Adedi", List.of(
                            new Field("unmapped-count", en ? "Unmapped errors" : "Map yapılmamış hata adedi", "number", null),
                            new Field("unmapped-note", en ? "Notes" : "Açıklama", "textarea", null))));
                    out.add(metrics("quota", en ? "Quota Error Count" : "Kota Hatası Adedi", List.of(
                            new Field("quota-count", en ? "Quota errors" : "Kota hatası adedi", "number", null),
                            new Field("quota-note", en ? "Notes" : "Açıklama", "textarea", null))));
                }
                default -> out.add(s);
            }
        }
        return normalized(d, out);
    }

    static Template replace(Template t, String id, UnaryOperator<Section> fn) {
        List<Section> secs = new ArrayList<>();
        for (Section s : t.sections()) secs.add(s.id().equals(id) ? fn.apply(s) : s);
        return normalized(t, secs);
    }

    @Test
    @DisplayName("iki üretim şekli de (TR / EN) 4a düzeninin aynısına döner: adet bölüme, açıklama adı yeni, bölüm özetin ardında, anomali adları; tekrar uygulanmaz; 7. sürüm göçüyle zincir bugünkü varsayılanı verir")
    void bothShapesBecomeToday() {
        for (String lang : List.of("tr", "en")) {
            for (Template old : List.of(shapeA(lang), shapeB(lang))) {
                assertThat(old.sections()).allMatch(s -> s.host() == null);
                Template m = TechErrorSummaryEmbedMigration.migrate(old).orElseThrow();
                assertThat(TechErrorTemplate.toJson(m)).as(lang).isEqualTo(TechErrorTemplate.toJson(TechErrorTestTemplates.hostedDefaults(lang)));
                TechErrorTemplate.normalize(TechErrorTemplate.toMap(m));                 // katı doğrulamadan geçer
                assertThat(TechErrorSummaryEmbedMigration.migrate(m)).isEmpty();
                // Zincir (üretim sırası): gömme (4a) → adetler Verileri'ne (7. sürüm) = bugünkü varsayılan, bayt bayt
                Template chained = TechErrorCountsInFiguresMigration.migrate(m).orElseThrow();
                assertThat(TechErrorTemplate.toJson(chained)).as(lang + " zincir").isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults(lang)));
            }
        }
        assertThat(TechErrorSummaryEmbedMigration.migrate(TechErrorTemplate.defaults("tr"))).isEmpty();
        assertThat(TechErrorSummaryEmbedMigration.migrate(TechErrorTemplate.defaults("en"))).isEmpty();
    }

    @Test
    @DisplayName("ekibin değiştirdiği bölüme dokunulmaz: başka başlık, başka açıklama adı, ek alan, ipucu, hesaplanan adet; anomalide yalnız eski adlı nitelik değişir")
    void changedSectionsKept() {
        Template base = shapeB("tr");
        // Başlık değişmiş → bölüm aynen; anomali adları yine yenilenir (nitelik nitelik)
        Template retitled = replace(base, "unmapped", s -> s.withTitle("Bizim map bölümümüz"));
        Template m1 = TechErrorSummaryEmbedMigration.migrate(retitled).orElseThrow();
        assertThat(section(m1, "unmapped").host()).isNull();
        assertThat(section(m1, "unmapped").title()).isEqualTo("Bizim map bölümümüz");
        assertThat(section(m1, "quota").host()).isEqualTo("exec-summary");                       // kota eşleşti
        assertThat(section(m1, "issues").title()).isEqualTo("Anormal Artış Gösteren Hatalar");
        // Açıklama adı değişmiş
        Template renamedNote = replace(base, "quota", s -> s.withFields(List.of(s.fields().get(0),
                new Field("quota-note", "Kota notumuz", "textarea", null))));
        assertThat(section(TechErrorSummaryEmbedMigration.migrate(renamedNote).orElseThrow(), "quota").host()).isNull();
        // Ek alan
        Template extra = replace(base, "quota", s -> s.withFields(List.of(s.fields().get(0), s.fields().get(1),
                new Field("quota-owner", "Sorumlu", "text", null))));
        assertThat(section(TechErrorSummaryEmbedMigration.migrate(extra).orElseThrow(), "quota").host()).isNull();
        // Adette ipucu
        Template hinted = replace(base, "quota", s -> s.withFields(List.of(
                new Field("quota-count", "Kota hatası adedi", "number", "Qlik'ten alın"), s.fields().get(1))));
        assertThat(section(TechErrorSummaryEmbedMigration.migrate(hinted).orElseThrow(), "quota").host()).isNull();
        // Şekil A ama adet başka bölümde ekibin ipucuyla → o bölüm dokunulmaz, kota yine gömülür
        Template a = shapeA("tr");
        Template hintedCount = replace(a, "summary", s -> s.withFields(s.fields().stream().map(f -> "unmapped-count".equals(f.id())
                ? new Field(f.id(), f.label(), f.type(), "Qlik'ten alın") : f).toList()));
        Template mc = TechErrorSummaryEmbedMigration.migrate(hintedCount).orElseThrow();
        assertThat(section(mc, "unmapped").host()).isNull();
        assertThat(field(mc, "unmapped-count").hint()).isEqualTo("Qlik'ten alın");
        assertThat(section(mc, "summary").fields()).extracting(Field::id).contains("unmapped-count").doesNotContain("quota-count");
        assertThat(section(mc, "quota").host()).isEqualTo("exec-summary");
        // Anomalide ekibin kendi konu başlığı korunur, eski açıklama etiketi yine yenilenir
        Template issues = replace(base, "issues", s -> new Section(s.id(), s.kind(), s.title(), s.fields(), s.slots(), "{konu} sorunu",
                s.descriptionLabel(), s.customersLabel(), s.errorsLabel(), s.text(), s.lines(), s.columns(), s.rows(), s.host()));
        Section is = section(TechErrorSummaryEmbedMigration.migrate(issues).orElseThrow(), "issues");
        assertThat(is.itemTitle()).isEqualTo("{konu} sorunu");
        assertThat(is.descriptionLabel()).isEqualTo("Ne oldu? (zaman, yer, etki)");
        assertThat(is.errorsLabel()).isEqualTo("Bu konudan kaynaklı teknik hata adedi");
        assertThat(is.customersLabel()).isEqualTo("Etkilenen müşteri sayısı");
    }

    @Test
    @DisplayName("özet bölümü yoksa gömme yapılmaz (anomali adları yine yenilenir); özet adede başvurmuyorsa ilk özet ev sahibi; adet tek alanlı bölümdeyse dokunulmaz")
    void hostRules() {
        Template base = shapeB("tr");
        Template noSummary = normalized(base, base.sections().stream().filter(s -> !"summary".equals(s.kind())).toList());
        Template m = TechErrorSummaryEmbedMigration.migrate(noSummary).orElseThrow();
        assertThat(m.sections()).allMatch(s -> s.host() == null);
        assertThat(section(m, "issues").title()).isEqualTo("Anormal Artış Gösteren Hatalar");
        // Özet adede başvurmuyor → ilk özet ev sahibi, bölüm onun ardında
        Template silent = replace(base, "exec-summary", s -> s.withLines(List.of("Giriş cümlesi.")));
        Template ms = TechErrorSummaryEmbedMigration.migrate(silent).orElseThrow();
        assertThat(ms.sections()).extracting(Section::id).startsWith("exec-summary", "unmapped", "quota");
        assertThat(section(ms, "unmapped").host()).isEqualTo("exec-summary");
        // Şekil A, adet kendi başına bir bölümde → o bölüm boş kalırdı, dokunulmaz (kota yine gömülür)
        Template a = shapeA("tr");
        List<Section> secs = new ArrayList<>();
        for (Section s : a.sections()) {
            if ("summary".equals(s.id())) {
                secs.add(s.withFields(s.fields().stream().filter(f -> !"unmapped-count".equals(f.id())).toList()));
                secs.add(metrics("only-count", "Yalnız adet", List.of(new Field("unmapped-count", "Map yapılmamış hata adedi", "number", null))));
            } else {
                secs.add(s);
            }
        }
        Template ml = TechErrorSummaryEmbedMigration.migrate(normalized(a, secs)).orElseThrow();
        assertThat(section(ml, "unmapped").host()).isNull();
        assertThat(section(ml, "only-count").fields()).hasSize(1);
        assertThat(section(ml, "quota").host()).isEqualTo("exec-summary");
    }

    @Test
    @DisplayName("iş: oran göçünün nişanı yokken koşmaz; varsa kilit altında koşar")
    void waitsForCalcMigration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> 0);
        SchedulerService scheduler = mock(SchedulerService.class);
        @SuppressWarnings("unchecked") ObjectProvider<SchedulerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scheduler);
        TechErrorSummaryEmbedMigration job = new TechErrorSummaryEmbedMigration(mock(TechErrorChannelRepository.class),
                mock(TechErrorReportService.class), jdbc, provider);
        job.run();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(Supplier.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> TechErrorCalcMigration.KEY.equals(inv.getArgument(2)) ? 1 : 0);
        when(scheduler.callWithSchedulerLock(anyString(), any(Supplier.class))).thenReturn(Optional.of(0));
        job.run();
        verify(scheduler).callWithSchedulerLock(eq(TechErrorSummaryEmbedMigration.LOCK), any(Supplier.class));
    }

    /** H2'de gerçek SQL: nişan, sürüm geçmişi, açılmış rapora dokunulmaması, taslağa uygulamada değer korunması. */
    @Nested
    @DataJpaTest
    @Import({TechErrorReportService.class, TechErrorHistoryService.class, TechErrorSummaryEmbedMigration.class})
    @TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    class Job {
        @Autowired TechErrorReportService service;
        @Autowired TechErrorSummaryEmbedMigration migration;
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
            jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key IN (?, ?)", TechErrorSummaryEmbedMigration.KEY,
                    TechErrorCalcMigration.KEY);
            service.useClock(Clock.fixed(Instant.parse("2026-10-17T09:00:00Z"), ZoneOffset.UTC));
        }

        @Test
        @DisplayName("iş: eski şekilli kanallar 4a düzenine güncellenir (sürüm +1, geçmişte 'SiteMonitor'), bugünkü varsayılan dokunulmaz; açılmış rapor aynen kalır; nişan sonrası koşmaz")
        void migratesOnce() {
            TechErrorChannel a = service.createChannel(1L, "Mobil", null, shapeA("tr"), 7L);
            TechErrorChannel b = service.createChannel(1L, "POS", null, shapeB("en"), 7L);
            TechErrorChannel fresh = service.createChannel(1L, "ATM", null, TechErrorTemplate.defaults("tr"), 7L);
            TechErrorReport opened = service.createReport(a, 2026, 9, 7L);
            String openedTemplate = opened.getTemplateJson();
            assertThat(migration.marker(TechErrorSummaryEmbedMigration.KEY)).isFalse();

            assertThat(migration.migrateLocked()).isEqualTo(2);
            TechErrorChannel afterA = channels.findById(a.getId()).orElseThrow();
            assertThat(afterA.getTemplateVersion()).isEqualTo(2);
            assertThat(afterA.getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(TechErrorTestTemplates.hostedDefaults("tr")));
            TechErrorChannel afterB = channels.findById(b.getId()).orElseThrow();
            assertThat(afterB.getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(TechErrorTestTemplates.hostedDefaults("en")));
            assertThat(channels.findById(fresh.getId()).orElseThrow().getTemplateVersion()).isEqualTo(1);
            assertThat(versions.findAll()).anySatisfy(v -> {
                assertThat(v.getChannelId()).isEqualTo(a.getId());
                assertThat(v.getVersion()).isEqualTo(2);
                assertThat(v.getSavedByName()).isEqualTo(TechErrorSummaryEmbedMigration.ACTOR);
            });
            // Açılmış rapor kopyasını korur; "şablonun son hâli farklı" der
            TechErrorReport r = reports.findById(opened.getId()).orElseThrow();
            assertThat(r.getTemplateJson()).isEqualTo(openedTemplate);
            assertThat(service.refreshPreview(r, afterA)).containsEntry("differs", true);
            // Nişan yazıldı: ikinci koşu hiçbir şey yapmaz
            assertThat(migration.done()).isTrue();
            assertThat(migration.migrateLocked()).isZero();
            assertThat(channels.findById(a.getId()).orElseThrow().getTemplateVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("taslağa şablonun son hâli uygulanınca girilmiş adet ve açıklama korunur (aynı kimlik + tür) — kayıp 0, oran hesaplanır")
        void refreshKeepsValues() {
            TechErrorChannel ch = service.createChannel(1L, "Mobil", null, shapeA("tr"), 7L);
            TechErrorReport sep = service.createReport(ch, 2026, 9, 7L);
            TechErrorReport s = service.saveValues(sep.getId(), sep.getVersion(),
                    Map.of("fields", Map.of("total-tx", 1000, "error-count", 10, "unmapped-count", 2, "unmapped-note", "Yeni kodlar")), 7L);
            migration.migrateLocked();
            Map<String, Object> preview = service.refreshPreview(reports.findById(sep.getId()).orElseThrow(),
                    channels.findById(ch.getId()).orElseThrow());
            assertThat(preview).containsEntry("differs", true).containsEntry("lost_values", 0);
            service.refreshTemplate(sep.getId(), s.getVersion(), 7L);
            TechErrorReport after = reports.findById(sep.getId()).orElseThrow();
            Template t = TechErrorTemplate.fromStored(after.getTemplateJson());
            assertThat(section(t, "unmapped").host()).isEqualTo("exec-summary");
            Map<String, Object> fields = TechErrorValues.fromStored(after.getValuesJson(), t).fields();
            assertThat(fields).containsEntry("unmapped-count", 2L).containsEntry("unmapped-note", "Yeni kodlar")
                    .containsEntry("unmapped-rate", 20L);
        }
    }
}
