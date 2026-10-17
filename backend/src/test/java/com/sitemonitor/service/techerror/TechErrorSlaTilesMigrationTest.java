package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.techerror.TechErrorTemplate.Calc;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SLA hedefi + kutucuk göçü (2026-10-17, 4b): 4b öncesi varsayılan şablon bugünkü varsayılana döner; ekibin değiştirdiği
 * cümle / alan korunur; tekrar uygulanmaz; açılmış rapor değişmez.
 */
class TechErrorSlaTilesMigrationTest {

    static Section section(Template t, String id) {
        return t.sections().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    static Field field(Template t, String id) {
        return t.allFields().stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
    }

    static Template normalized(Template d, List<Section> secs) {
        return TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(d.lang(), d.title(), List.copyOf(secs))));
    }

    /** 4b öncesi varsayılan: SLA oranı hedefsiz, SLA sorusu elle, özet eski cümlelerle ve kutucuksuz. */
    static Template before4b(String lang) {
        boolean en = "en".equals(lang);
        Template d = TechErrorTemplate.defaults(lang);
        List<Section> out = new ArrayList<>();
        for (Section s : d.sections()) {
            if ("exec-summary".equals(s.id())) {
                out.add(s.withLines(en ? TechErrorSlaTilesMigration.OLD_LINES_EN : TechErrorSlaTilesMigration.OLD_LINES_TR).withTiles(List.of()));
            } else if ("summary".equals(s.id())) {
                List<Field> fs = new ArrayList<>();
                for (Field f : s.fields()) {
                    if ("sla-rate".equals(f.id())) fs.add(f.withTarget(null, null));
                    else if ("sla-above".equals(f.id())) fs.add(f.withCalc(null).withHint(null));
                    else fs.add(f);
                }
                out.add(s.withFields(List.copyOf(fs)));
            } else {
                out.add(s);
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
    @DisplayName("4b öncesi varsayılan (TR / EN) bugünkü varsayılanın aynısına döner: hedef + yön, hedef karşılaştırması, fark cümlesi, kutucuklar; tekrar uygulanmaz")
    void before4bBecomesToday() {
        for (String lang : List.of("tr", "en")) {
            Template old = before4b(lang);
            assertThat(field(old, "sla-rate").target()).isNull();
            assertThat(field(old, "sla-above").calc()).isNull();
            assertThat(section(old, "exec-summary").tiles()).isEmpty();
            Template m = TechErrorSlaTilesMigration.migrate(old).orElseThrow();
            assertThat(TechErrorTemplate.toJson(m)).as(lang).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults(lang)));
            TechErrorTemplate.normalize(TechErrorTemplate.toMap(m));
            assertThat(TechErrorSlaTilesMigration.migrate(m)).isEmpty();
        }
        assertThat(TechErrorSlaTilesMigration.migrate(TechErrorTemplate.defaults("tr"))).isEmpty();
    }

    @Test
    @DisplayName("inceleme 2026-10-17: SLA_LINE'ın yeni cümleleri bugünkü varsayılanın özetinde birebir var (freshDefaultLines buna dayanır); eski cümleler eski varsayılanda")
    void slaLineMapMatchesDefaults() {
        for (String lang : List.of("tr", "en")) {
            var first = TechErrorTemplate.defaults(lang).sections().get(0);
            assertThat(first.kind()).isEqualTo("summary");
            List<String> fresh = first.lines();
            List<String> old = "en".equals(lang) ? TechErrorSlaTilesMigration.OLD_LINES_EN : TechErrorSlaTilesMigration.OLD_LINES_TR;
            long hits = TechErrorSlaTilesMigration.SLA_LINE.entrySet().stream()
                    .filter(e -> fresh.contains(e.getValue())).peek(e -> assertThat(old).contains(e.getKey())).count();
            assertThat(hits).as(lang).isEqualTo(1);
            // Hedef kullanılamıyorsa: yeni liste, yalnız SLA cümlesi eski
            List<String> noSla = TechErrorSlaTilesMigration.freshDefaultLines(lang, false);
            assertThat(noSla).hasSize(fresh.size()).noneMatch(l -> l.contains("{t:sla-rate}"));
            assertThat(TechErrorSlaTilesMigration.freshDefaultLines(lang, true)).isEqualTo(fresh);
        }
    }

    @Test
    @DisplayName("ekibin cümleleri korunur (yalnız eski SLA cümlesi değişir); kendi hedefi / hesabı olan alan dokunulmaz; dolu kutucuk listesi değişmez")
    void teamChangesKept() {
        Template base = before4b("tr");
        // Ekip bir cümle eklemiş → liste eski varsayılan değil: fark cümlesi EKLENMEZ, yalnız SLA cümlesi yenilenir
        List<String> custom = new ArrayList<>(TechErrorSlaTilesMigration.OLD_LINES_TR);
        custom.add(1, "Ekibin kendi cümlesi.");
        Template withCustom = replace(base, "exec-summary", s -> s.withLines(List.copyOf(custom)));
        Template m = TechErrorSlaTilesMigration.migrate(withCustom).orElseThrow();
        List<String> lines = section(m, "exec-summary").lines();
        assertThat(lines).hasSize(custom.size()).contains("Ekibin kendi cümlesi.")
                .doesNotContain("Önceki aya göre teknik hata adedi {d:error-count}, teknik hata oranı {d:error-rate} değişmiştir.");
        assertThat(lines.get(lines.size() - 1)).contains("{t:sla-rate}").contains("{dt:sla-rate}");
        assertThat(section(m, "exec-summary").tiles()).isEqualTo(TechErrorTemplate.DEFAULT_TILES);
        // Ekip SLA oranına kendi hedefini koymuş → korunur; SLA sorusuna kendi hesabını koymuş → korunur
        Template own = replace(base, "summary", s -> s.withFields(s.fields().stream().map(f ->
                "sla-rate".equals(f.id()) ? f.withTarget(99.5, "higher")
                        : "sla-above".equals(f.id()) ? f.withCalc(Calc.increase("total-tx")) : f).toList()));
        Template mo = TechErrorSlaTilesMigration.migrate(own).orElseThrow();
        assertThat(field(mo, "sla-rate").target()).isEqualTo(99.5);
        assertThat(field(mo, "sla-above").calc()).isEqualTo(Calc.increase("total-tx"));
        // Kutucukları zaten seçmiş → değişmez
        Template tiled = replace(base, "exec-summary", s -> s.withTiles(List.of("total-tx")));
        assertThat(section(TechErrorSlaTilesMigration.migrate(tiled).orElseThrow(), "exec-summary").tiles()).containsExactly("total-tx");
        // SLA oranı şablonda yok → hedef / hesap yok, SLA cümlesi ESKİ kalır ama fark cümlesi yine eklenir (inceleme 2026-10-17);
        // kutucuklar var olan alanlardan
        Template noSla = replace(base, "summary", s -> s.withFields(s.fields().stream().filter(f -> !"sla-rate".equals(f.id())).toList()));
        Template mn = TechErrorSlaTilesMigration.migrate(noSla).orElseThrow();
        assertThat(field(mn, "sla-above").calc()).isNull();
        List<String> noSlaLines = section(mn, "exec-summary").lines();
        List<String> fresh = TechErrorTemplate.defaults("tr").sections().get(0).lines();
        assertThat(noSlaLines).hasSize(fresh.size())
                .contains("Önceki aya göre teknik hata adedi {d:error-count}, teknik hata oranı {d:error-rate} değişmiştir.")
                .noneMatch(l -> l.contains("{t:sla-rate}"));
        assertThat(noSlaLines.get(noSlaLines.size() - 1)).isEqualTo(TechErrorSlaTilesMigration.OLD_LINES_TR.get(TechErrorSlaTilesMigration.OLD_LINES_TR.size() - 1));
        assertThat(noSlaLines.subList(0, noSlaLines.size() - 1)).isEqualTo(fresh.subList(0, fresh.size() - 1));
        assertThat(section(mn, "exec-summary").tiles()).containsExactly("error-rate", "total-tx", "error-count", "unmapped-count", "quota-count");
    }

    @Test
    @DisplayName("SLA sorusu başka alanların 'Hayır ise sorma' sorusuysa hesaplanır hâle gelmez (bağ düşerdi)")
    void skipTargetQuestionNotComputed() {
        Template base = before4b("tr");
        Template linked = replace(base, "summary", s -> s.withFields(s.fields().stream().map(f ->
                "outage-errors".equals(f.id()) ? new Field(f.id(), f.label(), f.type(), f.hint(), "sla-above") : f).toList()));
        Template m = TechErrorSlaTilesMigration.migrate(linked).orElseThrow();
        assertThat(field(m, "sla-above").calc()).isNull();
        assertThat(field(m, "sla-rate").target()).isEqualTo(TechErrorTemplate.DEFAULT_SLA_TARGET);
    }

    /** H2'de gerçek SQL: sürüm, geçmiş adı, açılmış rapor, nişan. */
    @Nested
    @DataJpaTest
    @Import({TechErrorReportService.class, TechErrorHistoryService.class, TechErrorSlaTilesMigration.class})
    @TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    class Job {
        @Autowired TechErrorReportService service;
        @Autowired TechErrorSlaTilesMigration migration;
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
            jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key IN (?, ?)", TechErrorSlaTilesMigration.KEY,
                    TechErrorSummaryEmbedMigration.KEY);
            service.useClock(Clock.fixed(Instant.parse("2026-10-17T09:00:00Z"), ZoneOffset.UTC));
        }

        @Test
        @DisplayName("iş: eski kanal güncellenir (sürüm +1, 'SiteMonitor'), bugünkü varsayılan dokunulmaz, açılmış rapor aynen; nişan sonrası koşmaz")
        void migratesOnce() {
            TechErrorChannel old = service.createChannel(1L, "Mobil", null, before4b("tr"), 7L);
            TechErrorChannel fresh = service.createChannel(1L, "POS", null, TechErrorTemplate.defaults("en"), 7L);
            TechErrorReport opened = service.createReport(old, 2026, 9, 7L);
            String openedTemplate = opened.getTemplateJson();
            assertThat(migration.migrateLocked()).isEqualTo(1);
            TechErrorChannel after = channels.findById(old.getId()).orElseThrow();
            assertThat(after.getTemplateVersion()).isEqualTo(2);
            assertThat(after.getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
            assertThat(channels.findById(fresh.getId()).orElseThrow().getTemplateVersion()).isEqualTo(1);
            assertThat(versions.findAll()).anySatisfy(v -> {
                assertThat(v.getChannelId()).isEqualTo(old.getId());
                assertThat(v.getVersion()).isEqualTo(2);
                assertThat(v.getSavedByName()).isEqualTo(TechErrorSlaTilesMigration.ACTOR);
            });
            assertThat(reports.findById(opened.getId()).orElseThrow().getTemplateJson()).isEqualTo(openedTemplate);
            assertThat(migration.done()).isTrue();
            assertThat(migration.migrateLocked()).isZero();
        }

        @Test
        @DisplayName("taslağa son şablon uygulanınca elle girilmiş SLA cevabı hesaplananla değişir, SLA oranı ve hedefi korunur")
        void refreshComputesSla() {
            TechErrorChannel ch = service.createChannel(1L, "Mobil", null, before4b("tr"), 7L);
            TechErrorReport r = service.createReport(ch, 2026, 9, 7L);
            TechErrorReport s = service.saveValues(r.getId(), r.getVersion(),
                    Map.of("fields", Map.of("sla-rate", 99.99, "sla-above", false)), 7L);
            migration.migrateLocked();
            service.refreshTemplate(r.getId(), s.getVersion(), 7L);
            TechErrorReport after = reports.findById(r.getId()).orElseThrow();
            Template t = TechErrorTemplate.fromStored(after.getTemplateJson());
            Map<String, Object> fields = TechErrorValues.fromStored(after.getValuesJson(), t).fields();
            assertThat(fields).containsEntry("sla-rate", 99.99).containsEntry("sla-above", true);   // ≥ %99,95 → tutturuldu
        }
    }
}
