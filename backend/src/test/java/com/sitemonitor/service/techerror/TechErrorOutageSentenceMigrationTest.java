package com.sitemonitor.service.techerror;

import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorTeamTemplate;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportEventRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.repository.TechErrorTeamTemplateRepository;
import com.sitemonitor.repository.TechErrorTemplateVersionRepository;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.techerror.TechErrorTemplate.Calc;
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
 * Kesinti cümlesi göçü (2026-10-18; kullanıcı kararı: "Dönemde kesinti oldu mu?" sorusu özete cümle olarak girsin, Verileri
 * bölümü PDF / e-postada tamamen kalksın): bu sürüm öncesi varsayılan özet cümleleri (TR / EN) birebir aynıysa kesinti
 * cümlesi kesinti kaynaklı adet cümlesinin önüne eklenir — kanal şablonları ve takım şablon kütüphanesi; ekibin değiştirdiği
 * cümle listesine dokunulmaz; tekrar uygulanmaz; iş adet göçünü bekler.
 */
class TechErrorOutageSentenceMigrationTest {

    /** Bu sürüm öncesi varsayılan: bugünkü varsayılanın kesinti cümlesi çıkarılmış hâli (kehanet {@code OLD_LINES_*} ayrıca sabitlenir). */
    static Template oldDefault(String lang) {
        Template d = TechErrorTemplate.defaults(lang);
        String line = "en".equals(lang) ? TechErrorTemplate.OUTAGE_LINE_EN : TechErrorTemplate.OUTAGE_LINE_TR;
        List<Section> secs = new ArrayList<>();
        for (Section s : d.sections()) {
            if ("summary".equals(s.kind())) {
                List<String> lines = new ArrayList<>(s.lines());
                assertThat(lines.remove(line)).as(lang + " varsayılanda kesinti cümlesi var").isTrue();
                secs.add(s.withLines(List.copyOf(lines)));
            } else {
                secs.add(s);
            }
        }
        return TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(d.lang(), d.title(), List.copyOf(secs))));
    }

    static Template mapFields(Template t, java.util.function.UnaryOperator<Field> fn) {
        List<Section> secs = new ArrayList<>();
        for (Section s : t.sections()) {
            if (!"metrics".equals(s.kind())) {
                secs.add(s);
                continue;
            }
            List<Field> fs = new ArrayList<>();
            for (Field f : s.fields()) {
                Field g = fn.apply(f);
                if (g != null) fs.add(g);
            }
            secs.add(s.withFields(List.copyOf(fs)));
        }
        return new Template(t.lang(), t.title(), List.copyOf(secs));
    }

    @Test
    @DisplayName("kehanet: göçün eski cümle listesi = bugünkü varsayılanın kesinti cümlesiz hâli (TR / EN, birebir); cümle kesinti kaynaklı adet cümlesinin hemen önünde, iki dalı da dolu")
    void oldLinesPinned() {
        assertThat(oldDefault("tr").sections().get(0).lines()).isEqualTo(TechErrorOutageSentenceMigration.OLD_LINES_TR);
        assertThat(oldDefault("en").sections().get(0).lines()).isEqualTo(TechErrorOutageSentenceMigration.OLD_LINES_EN);
        for (String lang : List.of("tr", "en")) {
            List<String> lines = TechErrorTemplate.defaults(lang).sections().get(0).lines();
            int at = TechErrorOutageSentenceMigration.OUTAGE_ERRORS_LINE;
            assertThat(lines.get(at)).as(lang).isEqualTo("en".equals(lang) ? TechErrorTemplate.OUTAGE_LINE_EN : TechErrorTemplate.OUTAGE_LINE_TR);
            assertThat(lines.get(at + 1)).as(lang).contains("{f:outage-errors}");
            var ref = TechErrorSummary.refs(lines.get(at)).get(0);
            assertThat(ref.id()).isEqualTo("had-outage");
            assertThat(TechErrorRichText.blank(ref.yes())).isFalse();
            assertThat(TechErrorRichText.blank(ref.no())).isFalse();
        }
    }

    @Test
    @DisplayName("eski varsayılan (TR / EN) bugünkü varsayılanın BAYT BAYT aynısına döner; tekrar uygulanmaz; bugünkü varsayılanda yapılacak bir şey yok")
    void oldDefaultBecomesToday() {
        for (String lang : List.of("tr", "en")) {
            Optional<Template> m = TechErrorOutageSentenceMigration.migrate(oldDefault(lang));
            assertThat(m).as(lang).isPresent();
            assertThat(TechErrorTemplate.toJson(m.get())).as(lang).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults(lang)));
            assertThat(TechErrorOutageSentenceMigration.migrate(m.get())).as(lang + " ikinci kez").isEmpty();
            assertThat(TechErrorOutageSentenceMigration.migrate(TechErrorTemplate.defaults(lang))).as(lang + " bugünkü").isEmpty();
        }
    }

    @Test
    @DisplayName("dokunulmaz: ekibin değiştirdiği / eklediği cümle, kesinti sorusu yok, türü değişmiş ya da hesaplanan kesinti sorusu, özetsiz şablon")
    void changedTemplatesAreLeftAlone() {
        Template old = oldDefault("tr");
        // Cümle değişti
        List<String> edited = new ArrayList<>(old.sections().get(0).lines());
        edited.set(0, edited.get(0).replace("gerçekleşmiştir", "olmuştur"));
        Template e1 = new Template(old.lang(), old.title(), replaceFirst(old, old.sections().get(0).withLines(List.copyOf(edited))));
        assertThat(TechErrorOutageSentenceMigration.migrate(e1)).isEmpty();
        // Cümle eklendi
        List<String> added = new ArrayList<>(old.sections().get(0).lines());
        added.add("Ek cümle.");
        assertThat(TechErrorOutageSentenceMigration.migrate(new Template(old.lang(), old.title(),
                replaceFirst(old, old.sections().get(0).withLines(List.copyOf(added)))))).isEmpty();
        // Kesinti sorusu silindi / türü değişti / hesaplanıyor
        assertThat(TechErrorOutageSentenceMigration.migrate(mapFields(old, f -> "had-outage".equals(f.id()) ? null : f))).isEmpty();
        assertThat(TechErrorOutageSentenceMigration.migrate(mapFields(old,
                f -> "had-outage".equals(f.id()) ? new Field("had-outage", f.label(), "number", null) : f))).isEmpty();
        assertThat(TechErrorOutageSentenceMigration.migrate(mapFields(old,
                f -> "had-outage".equals(f.id()) ? f.withCalc(Calc.increase("total-tx")) : f))).isEmpty();
        // Özet yok
        assertThat(TechErrorOutageSentenceMigration.migrate(new Template(old.lang(), old.title(),
                old.sections().stream().filter(s -> !"summary".equals(s.kind())).toList()))).isEmpty();
        assertThat(TechErrorOutageSentenceMigration.migrate(null)).isEmpty();
    }

    private static List<Section> replaceFirst(Template t, Section first) {
        List<Section> secs = new ArrayList<>(t.sections());
        secs.set(0, first);
        return List.copyOf(secs);
    }

    @Test
    @DisplayName("iş: zincir — anomali soruları göçünün nişanı yokken (adet nişanı olsa da) koşmaz; varsa kendi kilidi altında koşar")
    void waitsForAnomalyMigration() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenAnswer(inv -> 0);
        SchedulerService scheduler = mock(SchedulerService.class);
        @SuppressWarnings("unchecked") ObjectProvider<SchedulerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scheduler);
        TechErrorOutageSentenceMigration job = new TechErrorOutageSentenceMigration(mock(TechErrorChannelRepository.class),
                mock(TechErrorReportService.class), mock(TechErrorTeamTemplateRepository.class), jdbc, provider);
        job.run();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(Supplier.class));
        // Adet göçü bitti ama anomali soruları göçü henüz değil: beklenir (çatal değil zincir)
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> TechErrorCountsInFiguresMigration.KEY.equals(inv.getArgument(2)) ? 1 : 0);
        job.run();
        verify(scheduler, never()).callWithSchedulerLock(anyString(), any(Supplier.class));
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenAnswer(inv -> TechErrorCountsInFiguresMigration.KEY.equals(inv.getArgument(2))
                        || TechErrorAnomalyFieldsMigration.KEY.equals(inv.getArgument(2)) ? 1 : 0);
        when(scheduler.callWithSchedulerLock(anyString(), any(Supplier.class))).thenReturn(Optional.of(0));
        job.run();
        verify(scheduler).callWithSchedulerLock(eq(TechErrorOutageSentenceMigration.LOCK), any(Supplier.class));
    }

    /** H2'de gerçek SQL: kanallar + kütüphane, nişan, sürüm geçmişi, açılmış rapor, silinmiş takımın kaydı. */
    @Nested
    @DataJpaTest
    @Import({TechErrorReportService.class, TechErrorHistoryService.class, TechErrorTeamTemplateService.class,
            TechErrorOutageSentenceMigration.class})
    @TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    class Job {
        @Autowired TechErrorReportService service;
        @Autowired TechErrorOutageSentenceMigration migration;
        @Autowired TechErrorTeamTemplateService library;
        @Autowired TechErrorTeamTemplateRepository libraryRepo;
        @Autowired TechErrorChannelRepository channels;
        @Autowired TechErrorReportRepository reports;
        @Autowired TechErrorReportEventRepository events;
        @Autowired TechErrorTemplateVersionRepository versions;
        @Autowired TeamRepository teams;
        @Autowired JdbcTemplate jdbc;

        Long teamA;
        Long teamB;

        @BeforeEach
        void clean() {
            libraryRepo.deleteAll();
            events.deleteAll();
            versions.deleteAll();
            reports.deleteAll();
            channels.deleteAll();
            teams.deleteAll();
            teamA = team("Dijital Kanallar");
            teamB = team("Kart Sistemleri");
            jdbc.execute(com.sitemonitor.service.StandaloneMonitorDeletionBackfill.MARKERS_DDL);
            jdbc.update("DELETE FROM schema_patch_markers WHERE patch_key IN (?, ?)", TechErrorOutageSentenceMigration.KEY,
                    TechErrorCountsInFiguresMigration.KEY);
            service.useClock(Clock.fixed(Instant.parse("2026-10-18T09:00:00Z"), ZoneOffset.UTC));
        }

        private Long team(String name) {
            Team t = new Team();
            t.setName(name);
            t.setActive(true);
            return teams.save(t).getId();
        }

        @Test
        @DisplayName("iş: eski varsayılanlı kanallar (TR / EN) ve kütüphane kayıtları güncellenir (sürüm +1, 'SiteMonitor'), değiştirilmiş / bugünkü şablon dokunulmaz; açılmış rapor kopyasını korur; silinmiş takımın yetim kaydı anomali göçündeki gibi göçer (yetim kuralı siler); nişan sonrası koşmaz")
        void migratesOnce() {
            TechErrorChannel a = service.createChannel(teamA, "Mobil", null, oldDefault("tr"), 7L);
            TechErrorChannel b = service.createChannel(teamA, "POS", null, oldDefault("en"), 7L);
            TechErrorChannel fresh = service.createChannel(teamA, "ATM", null, TechErrorTemplate.defaults("tr"), 7L);
            Template old = oldDefault("tr");
            List<String> edited = new ArrayList<>(old.sections().get(0).lines());
            edited.add("Ekibin cümlesi.");
            Template custom = TechErrorTemplate.normalize(TechErrorTemplate.toMap(new Template(old.lang(), old.title(),
                    replaceFirst(old, old.sections().get(0).withLines(List.copyOf(edited))))));
            TechErrorChannel mine = service.createChannel(teamA, "Kurumsal", null, custom, 7L);
            TechErrorReport opened = service.createReport(a, 2026, 9, 7L);
            String openedTemplate = opened.getTemplateJson();
            TechErrorTeamTemplate libOld = library.create(teamA, "Eski varsayılan", oldDefault("en"), true, 7L);
            TechErrorTeamTemplate libMine = library.create(teamA, "Kendi şablonumuz", custom, false, 7L);
            TechErrorTeamTemplate libGone = library.create(teamB, "Silinen takımın", oldDefault("tr"), false, 7L);
            teams.deleteById(teamB);                                    // takım silindi, kayıt yetim kaldı
            assertThat(migration.marker(TechErrorOutageSentenceMigration.KEY)).isFalse();

            assertThat(migration.migrateLocked()).isEqualTo(4);         // 2 kanal + 2 kütüphane kaydı (biri yetim)
            TechErrorChannel afterA = channels.findById(a.getId()).orElseThrow();
            assertThat(afterA.getTemplateVersion()).isEqualTo(2);
            assertThat(afterA.getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
            assertThat(channels.findById(b.getId()).orElseThrow().getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("en")));
            assertThat(channels.findById(fresh.getId()).orElseThrow().getTemplateVersion()).isEqualTo(1);
            assertThat(channels.findById(mine.getId()).orElseThrow().getTemplateVersion()).isEqualTo(1);
            assertThat(versions.findAll()).anySatisfy(v -> {
                assertThat(v.getChannelId()).isEqualTo(a.getId());
                assertThat(v.getVersion()).isEqualTo(2);
                assertThat(v.getSavedByName()).isEqualTo(TechErrorOutageSentenceMigration.ACTOR);
            });
            TechErrorTeamTemplate libAfter = libraryRepo.findById(libOld.getId()).orElseThrow();
            assertThat(libAfter.getTemplateJson()).isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("en")));
            assertThat(libAfter.getVersion()).isGreaterThan(libOld.getVersion());
            assertThat(libAfter.getUpdatedByName()).isEqualTo(TechErrorOutageSentenceMigration.ACTOR);
            assertThat(libAfter.isDefaultEntry()).isTrue();             // yalnız şablon değişti
            assertThat(libraryRepo.findById(libMine.getId()).orElseThrow().getVersion()).isEqualTo(libMine.getVersion());
            // Tek yol (anomali göçüyle aynı: findMigrationRows + koşullu migrateTemplate): takım bakılmaz, yetim kayıt da göçer
            assertThat(libraryRepo.findById(libGone.getId()).orElseThrow().getTemplateJson())
                    .isEqualTo(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
            // Açılmış rapor kopyasını korur; "şablonun son hâlini uygula" farkı görür, değer kaybı yok
            TechErrorReport r = reports.findById(opened.getId()).orElseThrow();
            assertThat(r.getTemplateJson()).isEqualTo(openedTemplate);
            assertThat(service.refreshPreview(r, afterA)).containsEntry("differs", true).containsEntry("lost_values", 0);
            // Nişan yazıldı: ikinci koşu hiçbir şey yapmaz
            assertThat(migration.done()).isTrue();
            assertThat(migration.migrateLocked()).isZero();
            assertThat(channels.findById(a.getId()).orElseThrow().getTemplateVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("kütüphane göçü anomali göçüyle aynı yol: okunan sürümle koşullu (eski sürümle yazım 0 satır, ekibin kaydı ezilmez), okunamayan kayıt dokunulmaz, ikinci koşu değiştirmez")
        void libraryUpdateIsConditional() {
            TechErrorTeamTemplate e = library.create(teamA, "Eski", oldDefault("tr"), false, 7L);
            String json = TechErrorTemplate.toJson(TechErrorOutageSentenceMigration.migrate(oldDefault("tr")).orElseThrow());
            assertThat(libraryRepo.migrateTemplate(e.getId(), e.getVersion() + 5, json, Instant.now(), "SiteMonitor")).isZero();
            assertThat(libraryRepo.findById(e.getId()).orElseThrow().getVersion()).isEqualTo(e.getVersion());
            TechErrorTeamTemplate broken = library.create(teamA, "Bozuk", oldDefault("tr"), false, 7L);
            jdbc.update("UPDATE tech_error_team_templates SET template_json = ? WHERE id = ?", "{bozuk", broken.getId());
            assertThat(migration.migrateLibrary()).isEqualTo(1);
            TechErrorTeamTemplate after = libraryRepo.findById(e.getId()).orElseThrow();
            assertThat(after.getTemplateJson()).isEqualTo(json);
            assertThat(after.getVersion()).isEqualTo(e.getVersion() + 1);
            assertThat(after.getUpdatedByName()).isEqualTo(TechErrorOutageSentenceMigration.ACTOR);
            assertThat(libraryRepo.findById(broken.getId()).orElseThrow().getTemplateJson()).isEqualTo("{bozuk");
            assertThat(migration.migrateLibrary()).isZero();
            assertThat(libraryRepo.findById(e.getId()).orElseThrow().getVersion()).isEqualTo(after.getVersion());
        }
    }
}
