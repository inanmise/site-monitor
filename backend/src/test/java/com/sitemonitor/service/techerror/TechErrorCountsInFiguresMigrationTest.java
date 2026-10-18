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
 * Adet göçü (2026-10-17, 7. sürüm): özet kartına gömülü 4a map / kota bölümleri çözülür, adet + açıklama "Verileri"
 * bölümüne teknik hata adedinin ardına taşınır; ekibin değiştirdiği / ayırdığı bölüme dokunulmaz; tekrar uygulanmaz;
 * iş SLA / kutucuk göçünü bekler.
 */
class TechErrorCountsInFiguresMigrationTest {

    static Section section(Template t, String id) {
        return t.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    static List<String> ids(Section s) {
        return s.fields().stream().map(Field::id).toList();
    }

    static Template normalized(Template d, List<Section> secs) {
        return TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(d.lang(), d.title(), List.copyOf(secs))));
    }

    static Template replace(Template t, String id, UnaryOperator<Section> fn) {
        List<Section> secs = new ArrayList<>();
        for (Section s : t.sections()) secs.add(s.id().equals(id) ? fn.apply(s) : s);
        return normalized(t, secs);
    }

    @Test
    @DisplayName("4a düzeni (TR / EN) bugünkü varsayılanın aynısına döner: gömülü bölümler gider, adet + açıklama Verileri'nde teknik hata adedinin ardında (önce map, sonra kota); tekrar uygulanmaz; bugünkü varsayılanda yapılacak bir şey yok")
    void hostedBecomesToday() {
        for (String lang : List.of("tr", "en")) {
            Template old = TechErrorTestTemplates.hostedDefaults(lang);
            assertThat(section(old, "unmapped").host()).isEqualTo("exec-summary");
            assertThat(ids(section(old, "summary"))).doesNotContain("unmapped-count", "quota-note");
            Template m = TechErrorCountsInFiguresMigration.migrate(old).orElseThrow();
            // Anomali soruları (2026-10-18) bu göçten sonra gelir: sonuç onların öncesindeki varsayılan, ardından bugünkü
            assertThat(TechErrorTemplate.toJson(m)).as(lang).isEqualTo(TechErrorTemplate.toJson(TechErrorTestTemplates.beforeAnomaly(lang)));
            assertThat(TechErrorTemplate.toJson(TechErrorAnomalyFieldsMigration.migrate(m).orElseThrow())).as(lang)
                    .isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults(lang)));
            TechErrorTemplate.normalize(TechErrorTemplate.toMap(m));                     // katı doğrulamadan geçer
            assertThat(section(m, "unmapped")).isNull();
            assertThat(section(m, "quota")).isNull();
            assertThat(ids(section(m, "summary"))).containsSequence("error-count", "unmapped-count", "unmapped-note", "quota-count",
                    "quota-note", "had-outage");
            assertThat(TechErrorCountsInFiguresMigration.migrate(m)).isEmpty();
            // Alan kimlikleri değişmedi: kutucuklar, oranlar ve özet cümleleri aynı alanlara bakar
            assertThat(section(m, "exec-summary").tiles()).isEqualTo(section(old, "exec-summary").tiles());
            assertThat(section(m, "rates")).isEqualTo(section(old, "rates"));
            assertThat(m.allFields()).extracting(Field::id).containsExactlyInAnyOrderElementsOf(old.allFields().stream().map(Field::id).toList());
        }
        assertThat(TechErrorCountsInFiguresMigration.migrate(TechErrorTemplate.defaults("tr"))).isEmpty();
        assertThat(TechErrorCountsInFiguresMigration.migrate(TechErrorTemplate.defaults("en"))).isEmpty();
    }

    @Test
    @DisplayName("ekibin değiştirdiği bölüme dokunulmaz: başka başlık, ek alan, ipucu, hedef, farklı adet adı; ekibin ayırdığı (host yok) bölüm de kalır — yalnız öbürü taşınır")
    void changedOrSeparatedSectionsStay() {
        Template base = TechErrorTestTemplates.hostedDefaults("tr");
        // Başlık değişmiş kota + dokunulmamış map: yalnız map taşınır, kota gömülü kalır
        Template t1 = replace(base, "quota", s -> new Section(s.id(), s.kind(), "Kota (bizim)", s.fields(), s.slots(), null, null, null,
                null, null, List.of(), List.of(), List.of(), s.host()));
        Template m1 = TechErrorCountsInFiguresMigration.migrate(t1).orElseThrow();
        assertThat(section(m1, "unmapped")).isNull();
        assertThat(section(m1, "quota").host()).isEqualTo("exec-summary");
        assertThat(ids(section(m1, "summary"))).containsSequence("error-count", "unmapped-count", "unmapped-note", "had-outage")
                .doesNotContain("quota-count");
        // Ek alan
        Template t2 = replace(base, "unmapped", s -> s.withFields(List.of(s.fields().get(0), s.fields().get(1),
                new Field("unmapped-new", "Yeni hata kodu var mı?", "yesno", null))));
        assertThat(section(TechErrorCountsInFiguresMigration.migrate(t2).orElseThrow(), "unmapped")).isNotNull();
        // İpucu / hedef / farklı adet adı
        Template t3 = replace(base, "unmapped", s -> s.withFields(List.of(s.fields().get(0).withHint("Elle sayılır"), s.fields().get(1))));
        assertThat(section(TechErrorCountsInFiguresMigration.migrate(t3).orElseThrow(), "unmapped")).isNotNull();
        Template t4 = replace(base, "unmapped", s -> s.withFields(List.of(
                new Field("unmapped-count", "Eşlenmemiş hata adedi", "number", null), s.fields().get(1))));
        assertThat(section(TechErrorCountsInFiguresMigration.migrate(t4).orElseThrow(), "unmapped")).isNotNull();
        // Ekip "Ayrı bölüm olarak" ayırmış (host yok): kalır
        Template t5 = replace(base, "unmapped", s -> s.withHost(null));
        Template m5 = TechErrorCountsInFiguresMigration.migrate(t5).orElseThrow();
        assertThat(section(m5, "unmapped").host()).isNull();
        assertThat(ids(section(m5, "unmapped"))).containsExactly("unmapped-count", "unmapped-note");
        assertThat(section(m5, "quota")).isNull();
        // Her ikisi de değişmiş: hiçbir şey yapılmaz
        Template t6 = replace(replace(base, "unmapped", s -> s.withHost(null)), "quota", s -> s.withHost(null));
        assertThat(TechErrorCountsInFiguresMigration.migrate(t6)).isEmpty();
    }

    @Test
    @DisplayName("hedef bölüm: 'summary' kimlikli AYRI gösterge bölümü yoksa (ya da ekip Verileri'ni özete gömmüşse) dokunulmaz; teknik hata adedi yoksa sona eklenir; 30 alan sınırı aşılırsa o bölüm kalır; ekibin kendi özetine gömdüğü bölüm de çözülür")
    void targetSectionRules() {
        Template base = TechErrorTestTemplates.hostedDefaults("tr");
        // Ekip Verileri bölümünü de özete gömmüş: hedef gömülü → hiçbir şey taşınmaz (notlar yine özetin altına düşerdi)
        Template hostedTarget = replace(base, "summary", s -> s.withHost("exec-summary"));
        assertThat(section(hostedTarget, "summary").host()).isEqualTo("exec-summary");
        assertThat(TechErrorCountsInFiguresMigration.migrate(hostedTarget)).isEmpty();
        // Verileri bölümünün kimliği değişmiş (ekip yeniden kurmuş)
        Template t1 = replace(base, "summary", s -> new Section("figures", s.kind(), s.title(), s.fields(), s.slots(), null, null, null,
                null, null, List.of(), List.of(), List.of(), null));
        assertThat(TechErrorCountsInFiguresMigration.migrate(t1)).isEmpty();
        // Teknik hata adedi Verileri'nde yok → sona
        Template t2 = replace(base, "summary", s -> s.withFields(s.fields().stream().filter(f -> !"error-count".equals(f.id())).toList()));
        Template m2 = TechErrorCountsInFiguresMigration.migrate(t2).orElseThrow();
        assertThat(ids(section(m2, "summary"))).endsWith("sla-above", "unmapped-count", "unmapped-note", "quota-count", "quota-note");
        // 30 alan sınırı: Verileri 27 alanla dolu → map (2 alan) sığar (29), kota sığmaz (31) → kota gömülü kalır
        Template t3 = replace(base, "summary", s -> {
            List<Field> fs = new ArrayList<>(s.fields());
            for (int i = fs.size(); i < TechErrorTemplate.MAX_FIELDS - 3; i++) fs.add(new Field("extra-" + i, "Ek " + i, "number", null));
            return s.withFields(List.copyOf(fs));
        });
        assertThat(section(t3, "summary").fields()).hasSize(TechErrorTemplate.MAX_FIELDS - 3);
        Template m3 = TechErrorCountsInFiguresMigration.migrate(t3).orElseThrow();
        assertThat(section(m3, "summary").fields()).hasSize(TechErrorTemplate.MAX_FIELDS - 1);
        assertThat(section(m3, "unmapped")).isNull();
        assertThat(section(m3, "quota").host()).isEqualTo("exec-summary");
        TechErrorTemplate.normalize(TechErrorTemplate.toMap(m3));
        // Ekibin kendi özet bölümüne gömdüğü (host farklı) 4a bölümü de çözülür — şekil aynı
        List<Section> secs = new ArrayList<>(base.sections());
        secs.add(1, new Section("ozet-2", "summary", "İkinci Özet", List.of(), List.of(), null, null, null, null, null,
                List.of("Kota {f:quota-count}."), List.of(), List.of()));
        Template t4 = replace(normalized(base, secs), "quota", s -> s.withHost("ozet-2"));
        assertThat(section(t4, "quota").host()).isEqualTo("ozet-2");
        Template m4 = TechErrorCountsInFiguresMigration.migrate(t4).orElseThrow();
        assertThat(section(m4, "quota")).isNull();
        assertThat(ids(section(m4, "summary"))).containsSequence("error-count", "unmapped-count", "unmapped-note", "quota-count", "quota-note");
    }

    @Test
    @DisplayName("iş: SLA / kutucuk göçünün nişanı yokken koşmaz; varsa kilit altında koşar")
    void waitsForSlaTilesMigration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenAnswer(inv -> 0);
        SchedulerService scheduler = mock(SchedulerService.class);
        @SuppressWarnings("unchecked") ObjectProvider<SchedulerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scheduler);
        TechErrorCountsInFiguresMigration job = new TechErrorCountsInFiguresMigration(mock(TechErrorChannelRepository.class),
                mock(TechErrorReportService.class), jdbc, provider);
        job.run();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(Supplier.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> TechErrorSlaTilesMigration.KEY.equals(inv.getArgument(2)) ? 1 : 0);
        when(scheduler.callWithSchedulerLock(anyString(), any(Supplier.class))).thenReturn(Optional.of(0));
        job.run();
        verify(scheduler).callWithSchedulerLock(eq(TechErrorCountsInFiguresMigration.LOCK), any(Supplier.class));
    }

    /** H2'de gerçek SQL: nişan, sürüm geçmişi, açılmış rapora dokunulmaması, taslağa uygulamada değer korunması. */
    @Nested
    @DataJpaTest
    @Import({TechErrorReportService.class, TechErrorHistoryService.class, TechErrorCountsInFiguresMigration.class})
    @TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    class Job {
        @Autowired TechErrorReportService service;
        @Autowired TechErrorCountsInFiguresMigration migration;
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
            jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key IN (?, ?)", TechErrorCountsInFiguresMigration.KEY,
                    TechErrorSlaTilesMigration.KEY);
            service.useClock(Clock.fixed(Instant.parse("2026-10-17T09:00:00Z"), ZoneOffset.UTC));
        }

        @Test
        @DisplayName("iş: 4a düzenli kanallar (TR / EN) güncellenir (sürüm +1, geçmişte 'SiteMonitor'), bugünkü varsayılan dokunulmaz; açılmış rapor aynen kalır; nişan sonrası koşmaz")
        void migratesOnce() {
            TechErrorChannel a = service.createChannel(1L, "Mobil", null, TechErrorTestTemplates.hostedDefaults("tr"), 7L);
            TechErrorChannel b = service.createChannel(1L, "POS", null, TechErrorTestTemplates.hostedDefaults("en"), 7L);
            TechErrorChannel fresh = service.createChannel(1L, "ATM", null, TechErrorTemplate.defaults("tr"), 7L);
            TechErrorReport opened = service.createReport(a, 2026, 9, 7L);
            String openedTemplate = opened.getTemplateJson();
            assertThat(migration.marker(TechErrorCountsInFiguresMigration.KEY)).isFalse();

            assertThat(migration.migrateLocked()).isEqualTo(2);
            TechErrorChannel afterA = channels.findById(a.getId()).orElseThrow();
            assertThat(afterA.getTemplateVersion()).isEqualTo(2);
            assertThat(afterA.getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(TechErrorTestTemplates.beforeAnomaly("tr")));
            assertThat(channels.findById(b.getId()).orElseThrow().getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(TechErrorTestTemplates.beforeAnomaly("en")));
            assertThat(channels.findById(fresh.getId()).orElseThrow().getTemplateVersion()).isEqualTo(1);
            assertThat(versions.findAll()).anySatisfy(v -> {
                assertThat(v.getChannelId()).isEqualTo(a.getId());
                assertThat(v.getVersion()).isEqualTo(2);
                assertThat(v.getSavedByName()).isEqualTo(TechErrorCountsInFiguresMigration.ACTOR);
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
        @DisplayName("taslağa şablonun son hâli uygulanınca girilmiş adet ve açıklama korunur (aynı kimlik + tür, bölüm değişti) — kayıp 0, oran hesaplanır, alanlar Verileri'nde")
        void refreshKeepsValues() {
            TechErrorChannel ch = service.createChannel(1L, "Mobil", null, TechErrorTestTemplates.hostedDefaults("tr"), 7L);
            TechErrorReport sep = service.createReport(ch, 2026, 9, 7L);
            TechErrorReport s = service.saveValues(sep.getId(), sep.getVersion(),
                    Map.of("fields", Map.of("total-tx", 1000, "error-count", 10, "unmapped-count", 2, "unmapped-note", "Yeni kodlar",
                            "quota-count", 1, "quota-note", "Kota notu")), 7L);
            migration.migrateLocked();
            Map<String, Object> preview = service.refreshPreview(reports.findById(sep.getId()).orElseThrow(),
                    channels.findById(ch.getId()).orElseThrow());
            assertThat(preview).containsEntry("differs", true).containsEntry("lost_values", 0);
            service.refreshTemplate(sep.getId(), s.getVersion(), 7L);
            TechErrorReport after = reports.findById(sep.getId()).orElseThrow();
            Template t = TechErrorTemplate.fromStored(after.getTemplateJson());
            assertThat(section(t, "unmapped")).isNull();
            assertThat(ids(section(t, "summary"))).containsSequence("error-count", "unmapped-count", "unmapped-note", "quota-count", "quota-note");
            Map<String, Object> fields = TechErrorValues.fromStored(after.getValuesJson(), t).fields();
            assertThat(fields).containsEntry("unmapped-count", 2L).containsEntry("unmapped-note", "Yeni kodlar")
                    .containsEntry("quota-count", 1L).containsEntry("quota-note", "Kota notu")
                    .containsEntry("unmapped-rate", 20L).containsEntry("quota-rate", 10L);
        }
    }
}
