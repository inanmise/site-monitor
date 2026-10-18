package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorTeamTemplate;
import com.sitemonitor.model.TechErrorTemplateVersion;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportEventRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.repository.TechErrorTeamTemplateRepository;
import com.sitemonitor.repository.TechErrorTemplateVersionRepository;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
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
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Takım ŞABLON KÜTÜPHANESİ (2026-10-18, D17) ve raporun ŞABLON SÜRÜMÜ (D19) — H2'de GERÇEK SQL. {@code NOT_SUPPORTED}:
 * uygulamadaki gibi işlemsiz çağıran; takım satırı kilidi, koşullu varsayılan düşürme ve {@code @Modifying} sorguları
 * kendi işlemlerinde çalışmalı.
 */
@DataJpaTest
@Import({TechErrorTeamTemplateService.class, TechErrorReportService.class, TechErrorHistoryService.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TechErrorTeamTemplateServiceTest {

    @Autowired TechErrorTeamTemplateService library;
    @Autowired TechErrorTeamTemplateRepository repo;
    @Autowired TechErrorReportService service;
    @Autowired TechErrorChannelRepository channels;
    @Autowired TechErrorReportRepository reports;
    @Autowired TechErrorReportEventRepository events;
    @Autowired TechErrorTemplateVersionRepository versions;
    @Autowired TeamRepository teams;

    private Long teamA;
    private Long teamB;

    @BeforeEach
    void clean() {
        repo.deleteAll();
        events.deleteAll();
        versions.deleteAll();
        reports.deleteAll();
        channels.deleteAll();
        teams.deleteAll();
        teamA = team("Dijital Kanallar");
        teamB = team("Kart Sistemleri");
        service.useClock(Clock.fixed(Instant.parse("2026-10-13T09:00:00Z"), ZoneOffset.UTC));
    }

    private Long team(String name) {
        Team t = new Team();
        t.setName(name);
        t.setActive(true);
        return teams.save(t).getId();
    }

    private static Template tr() {
        return TechErrorTemplate.defaults("tr");
    }

    private static Template titled(String title) {
        Template d = tr();
        return new Template(d.lang(), title, d.sections());
    }

    private TechErrorTeamTemplate entry(Long team, String name, boolean makeDefault) {
        return library.create(team, name, tr(), makeDefault, 7L);
    }

    private List<String> defaults(Long team) {
        List<String> out = new ArrayList<>();
        for (var m : library.list(team)) if (Boolean.TRUE.equals(m.getDefaultEntry())) out.add(m.getName());
        return out;
    }

    @Test
    @DisplayName("ekleme: sayımlar ve dil sütunda; liste önce varsayılan, sonra ad (Türkçe); başka takımın kaydı 404")
    void createAndList() {
        TechErrorTeamTemplate b = entry(teamA, "Zeytin şablonu", false);
        entry(teamA, "İnternet şablonu", true);
        entry(teamA, "ana şablon", false);
        assertThat(b.getSectionCount()).isEqualTo(tr().sections().size());
        assertThat(b.getFieldCount()).isEqualTo(tr().allFields().size());
        assertThat(b.getSlotCount()).isEqualTo(tr().allSlots().size());
        assertThat(b.getTemplateLang()).isEqualTo("tr");
        assertThat(library.list(teamA)).extracting(TechErrorTeamTemplateRepository.Meta::getName)
                .containsExactly("İnternet şablonu", "ana şablon", "Zeytin şablonu");
        assertThat(library.list(teamB)).isEmpty();
        assertThatThrownBy(() -> library.get(teamB, b.getId())).isInstanceOf(NoSuchElementException.class);
        assertThat(library.get(teamA, b.getId()).getName()).isEqualTo("Zeytin şablonu");
        assertThat(TechErrorTeamTemplateService.readableTemplate(library.get(teamA, b.getId())).title()).isEqualTo(tr().title());
    }

    @Test
    @DisplayName("ad: takım içinde tekil (Türkçe İ/i, boşluk), başka takımda serbest; boş ve 100 karakterden uzun ad alan hatası")
    void names() {
        entry(teamA, "İnternet Şubesi", false);
        assertThatThrownBy(() -> entry(teamA, "  internet   şubesi "))
                .isInstanceOf(CodedConflictException.class).extracting(e -> ((CodedConflictException) e).getCode())
                .isEqualTo("TEMPLATE_NAME_EXISTS");
        assertThat(entry(teamB, "İnternet Şubesi", false).getId()).isNotNull();
        assertThatThrownBy(() -> entry(teamA, "  "))
                .isInstanceOf(FieldValidationException.class).extracting(e -> ((FieldValidationException) e).getFields().keySet())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.COLLECTION).containsExactly("name");
        assertThatThrownBy(() -> entry(teamA, "x".repeat(TechErrorTeamTemplateService.MAX_NAME + 1)))
                .isInstanceOf(FieldValidationException.class);
        assertThat(entry(teamA, "y".repeat(TechErrorTeamTemplateService.MAX_NAME)).getName()).hasSize(100);
    }

    private TechErrorTeamTemplate entry(Long team, String name) {
        return entry(team, name, false);
    }

    @Test
    @DisplayName("takım başına en çok 20 kayıt: 21. → 409 TEMPLATE_LIBRARY_LIMIT; başka takım etkilenmez; silince yer açılır")
    void limit() {
        TechErrorTeamTemplate first = null;
        for (int i = 1; i <= TechErrorTeamTemplateService.MAX_PER_TEAM; i++) {
            TechErrorTeamTemplate e = entry(teamA, "Şablon " + i);
            if (first == null) first = e;
        }
        assertThatThrownBy(() -> entry(teamA, "Fazla"))
                .isInstanceOf(CodedConflictException.class).extracting(e -> ((CodedConflictException) e).getCode())
                .isEqualTo("TEMPLATE_LIBRARY_LIMIT");
        assertThat(entry(teamB, "Fazla").getId()).isNotNull();
        library.delete(teamA, first.getId());
        assertThat(entry(teamA, "Fazla").getId()).isNotNull();
        assertThat(repo.countByTeamId(teamA)).isEqualTo(20);
    }

    @Test
    @DisplayName("takım varsayılanı en çok BİR: yeni varsayılan öncekini düşürür (sürümü artar), kaldırınca hiç kalmaz; başka takımın varsayılanı değişmez")
    void oneDefaultPerTeam() {
        TechErrorTeamTemplate a = entry(teamA, "A", true);
        TechErrorTeamTemplate otherTeam = entry(teamB, "B takımı", true);
        TechErrorTeamTemplate b = entry(teamA, "B", true);
        assertThat(defaults(teamA)).containsExactly("B");
        TechErrorTeamTemplate aNow = repo.findById(a.getId()).orElseThrow();
        assertThat(aNow.getVersion()).isGreaterThan(a.getVersion());           // düşürülen satır değişti → eski sürümle yazım 409
        TechErrorTeamTemplate c = entry(teamA, "C", false);
        var res = library.update(teamA, c.getId(), c.getVersion(), false, null, null, true, 8L);
        assertThat(res.changed()).containsExactly("default");
        assertThat(defaults(teamA)).containsExactly("C");
        assertThat(library.teamDefault(teamA).orElseThrow().getName()).isEqualTo("C");
        var off = library.update(teamA, c.getId(), res.entry().getVersion(), false, null, null, false, 8L);
        assertThat(off.changed()).containsExactly("default");
        assertThat(defaults(teamA)).isEmpty();
        assertThat(library.teamDefault(teamA)).isEmpty();
        assertThat(defaults(teamB)).containsExactly("B takımı");
        assertThat(repo.findById(otherTeam.getId()).orElseThrow().getVersion()).isEqualTo(otherTeam.getVersion());
        assertThatThrownBy(() -> library.update(teamA, a.getId(), a.getVersion(), true, "Yeni ad", null, null, 8L))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(b.getId()).isNotNull();
    }

    @Test
    @DisplayName("eşzamanlı 'varsayılan yap' (iki iş parçacığı, farklı kayıtlar): takım satırı kilidiyle sırayla çalışır — sonunda TEK varsayılan")
    void concurrentSetDefault() throws Exception {
        entry(teamA, "X", true);
        TechErrorTeamTemplate y = entry(teamA, "Y", false);
        TechErrorTeamTemplate z = entry(teamA, "Z", false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> runs = new ArrayList<>();
            for (TechErrorTeamTemplate e : List.of(y, z)) {
                runs.add(pool.submit(() -> {
                    go.await();
                    try {
                        return library.update(teamA, e.getId(), e.getVersion(), false, null, null, true, 9L);
                    } catch (RuntimeException ex) {
                        return ex;
                    }
                }));
            }
            go.countDown();
            int ok = 0;
            for (Future<Object> f : runs) {
                Object r = f.get(30, TimeUnit.SECONDS);
                if (r instanceof TechErrorTeamTemplateService.UpdateResult) ok++;
                else assertThat(r).isInstanceOf(RuntimeException.class);     // kaybeden 409 / kilit zaman aşımı alabilir — ikisi de yazmaz
            }
            assertThat(ok).isGreaterThanOrEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(defaults(teamA)).hasSize(1).isSubsetOf("Y", "Z");
    }

    @Test
    @DisplayName("güncelleme: yalnız gönderilen alanlar; değişiklik yoksa hiçbir şey yazılmaz (sürüm aynı); eski sürüm 409; sürümsüz 400; ad çakışması 409")
    void updateRules() {
        TechErrorTeamTemplate e = entry(teamA, "Ana", false);
        entry(teamA, "Diğer", false);
        var same = library.update(teamA, e.getId(), e.getVersion(), true, " Ana ", tr(), false, 8L);
        assertThat(same.isChanged()).isFalse();
        assertThat(repo.findById(e.getId()).orElseThrow().getVersion()).isEqualTo(e.getVersion());
        var renamed = library.update(teamA, e.getId(), e.getVersion(), true, "Ana şablon", null, null, 8L);
        assertThat(renamed.changed()).containsExactly("name");
        assertThat(renamed.nameBefore()).isEqualTo("Ana");                      // kilit altında okunan eski ad (denetim)
        assertThat(renamed.entry().getName()).isEqualTo("Ana şablon");
        TechErrorTeamTemplate after = repo.findById(e.getId()).orElseThrow();
        assertThat(after.getName()).isEqualTo("Ana şablon");
        assertThat(after.getTemplateJson()).isEqualTo(e.getTemplateJson());
        var tpl = library.update(teamA, e.getId(), after.getVersion(), false, null, titled("{kanal} başlığı {ay} {yil}"), null, 8L);
        assertThat(tpl.changed()).containsExactly("template");
        assertThat(TechErrorTeamTemplateService.readableTemplate(repo.findById(e.getId()).orElseThrow()).title())
                .isEqualTo("{kanal} başlığı {ay} {yil}");
        assertThatThrownBy(() -> library.update(teamA, e.getId(), e.getVersion(), true, "Eski sürüm", null, null, 8L))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> library.update(teamA, e.getId(), null, true, "x", null, null, 8L))
                .isInstanceOf(FieldValidationException.class);
        Long v = repo.findById(e.getId()).orElseThrow().getVersion();
        assertThatThrownBy(() -> library.update(teamA, e.getId(), v, true, "diğer", null, null, 8L))
                .isInstanceOf(CodedConflictException.class).extracting(x -> ((CodedConflictException) x).getCode())
                .isEqualTo("TEMPLATE_NAME_EXISTS");
        assertThatThrownBy(() -> library.update(teamB, e.getId(), v, true, "x", null, null, 8L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("silme kalıcı; başka takım 404; takım yoksa (kilit satırı yok) 404")
    void deleteAndMissingTeam() {
        TechErrorTeamTemplate e = entry(teamA, "Silinecek", true);
        assertThatThrownBy(() -> library.delete(teamB, e.getId())).isInstanceOf(NoSuchElementException.class);
        assertThat(library.delete(teamA, e.getId()).getName()).isEqualTo("Silinecek");
        assertThat(repo.findById(e.getId())).isEmpty();
        assertThat(library.teamDefault(teamA)).isEmpty();
        assertThatThrownBy(() -> library.create(987654L, "Yok", tr(), false, 7L)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("şablon: yazımda KATI (istemci şablonu normalize ile), okumada hoşgörülü; kanala uygulanırken hoşgörülü okuma + katı yeniden doğrulama; okunamayan JSON 400 (varsayılana sessizce düşmez)")
    void strictWriteLenientRead() {
        Map<String, Object> bad = new LinkedHashMap<>(TechErrorTemplate.toMap(tr()));
        bad.put("sections", List.of(Map.of("id", "a b", "kind", "metrics", "title", "x", "fields", List.of())));
        assertThatThrownBy(() -> TechErrorTemplate.normalize(bad)).isInstanceOf(FieldValidationException.class)
                .extracting(x -> ((FieldValidationException) x).getFields().keySet())
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.COLLECTION).containsExactly("template");
        TechErrorTeamTemplate e = entry(teamA, "Eski kayıt", false);
        // Saklı JSON'da KATI kuralın reddettiği bir bağ ("Hayır ise sorma" bir sayı alanını gösteriyor — evet/hayır değil):
        // hoşgörülü okuma bağı düşürür, katı yeniden doğrulama temiz şablonu kabul eder
        Map<String, Object> stored = TechErrorTemplate.toMap(tr());
        stored.put("title", "Kayıtlı {kanal} {ay} {yil}");
        String numberField = null;
        Map<String, Object> linked = null;
        for (Object so : (List<?>) stored.get("sections")) {
            @SuppressWarnings("unchecked") Map<String, Object> s = (Map<String, Object>) so;
            if (!"metrics".equals(s.get("kind"))) continue;
            for (Object fo : (List<?>) s.get("fields")) {
                @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) fo;
                if (numberField == null && "number".equals(f.get("type")) && f.get("calc") == null) numberField = (String) f.get("id");
                else if (linked == null && "number".equals(f.get("type")) && f.get("calc") == null) linked = f;
            }
        }
        assertThat(numberField).isNotNull();
        assertThat(linked).isNotNull();
        linked.put("skip_if_no", numberField);
        assertThatThrownBy(() -> TechErrorTemplate.normalize(stored)).isInstanceOf(FieldValidationException.class);
        e = repo.findById(e.getId()).orElseThrow();
        e.setTemplateJson(new tools.jackson.databind.ObjectMapper().writeValueAsString(stored));
        repo.saveAndFlush(e);
        Template valid = TechErrorTeamTemplateService.validatedTemplate(repo.findById(e.getId()).orElseThrow());
        assertThat(valid.title()).isEqualTo("Kayıtlı {kanal} {ay} {yil}");
        String linkedId = (String) linked.get("id");
        assertThat(valid.allFields()).filteredOn(f -> f.id().equals(linkedId)).singleElement()
                .satisfies(f -> assertThat(f.skipIfNo()).isNull());
        TechErrorTeamTemplate broken = repo.findById(e.getId()).orElseThrow();
        broken.setTemplateJson("{bozuk");
        repo.saveAndFlush(broken);
        TechErrorTeamTemplate reread = repo.findById(e.getId()).orElseThrow();
        // Okunamayan kayıt sistem şablonuna DÜŞMEZ (2026-10-18 inceleme): hem kanala kopyalama hem tek kayıt okuması 409
        // TEMPLATE_UNREADABLE; kanal yolunda alan library_template_id (seçicinin altında)
        assertThatThrownBy(() -> TechErrorTeamTemplateService.validatedTemplate(reread)).isInstanceOf(CodedConflictException.class)
                .satisfies(x -> {
                    CodedConflictException c = (CodedConflictException) x;
                    assertThat(c.getCode()).isEqualTo("TEMPLATE_UNREADABLE");
                    assertThat(c.getExtra()).containsEntry("field", "library_template_id");
                    assertThat(c.getMessage()).contains("Eski kayıt");
                });
        assertThatThrownBy(() -> TechErrorTeamTemplateService.readableTemplate(reread)).isInstanceOf(CodedConflictException.class)
                .extracting(x -> ((CodedConflictException) x).getCode()).isEqualTo("TEMPLATE_UNREADABLE");
        assertThat(TechErrorTemplate.fromStored(reread.getTemplateJson()).title()).isEqualTo(tr().title());   // eski davranış: varsayılana düşerdi
        for (String json : List.of("", "[]", "\"metin\"", "{\"lang\":\"tr\",\"title\":\"x\"}",
                "{\"lang\":\"tr\",\"title\":\"x\",\"sections\":[]}",
                "{\"lang\":\"tr\",\"title\":\"x\",\"sections\":[{\"id\":\"a\",\"kind\":\"tanımsız\",\"title\":\"x\"}]}")) {
            assertThat(TechErrorTemplate.readStored(json)).as(json).isEmpty();
        }
        assertThat(TechErrorTemplate.readStored(TechErrorTemplate.toJson(tr()))).hasValueSatisfying(t -> assertThat(t.title()).isEqualTo(tr().title()));
    }

    @Test
    @DisplayName("kütüphaneden kanal: şablon kopyalanır, kanalın ilk sürümü kaynağı yazar (library + ad); kopya / varsayılan da işaretli, kopyada ad yok")
    void channelFromLibraryRecordsSource() {
        TechErrorTeamTemplate e = library.create(teamA, "Mobil kanallar şablonu", titled("Kütüphane {kanal} {ay} {yil}"), true, 7L);
        Template t = TechErrorTeamTemplateService.validatedTemplate(e);
        TechErrorChannel ch = service.createChannel(teamA, "Mobil", null, t, 7L,
                TechErrorReportService.TemplateSource.library(e.getName()));
        assertThat(TechErrorTemplate.fromStored(ch.getTemplateJson()).title()).isEqualTo("Kütüphane {kanal} {ay} {yil}");
        TechErrorTemplateVersion v1 = versions.findByChannelIdAndVersion(ch.getId(), 1).orElseThrow();
        assertThat(v1.getSourceKind()).isEqualTo("library");
        assertThat(v1.getSourceName()).isEqualTo("Mobil kanallar şablonu");
        assertThat(versions.findByChannelIdOrderByVersionDesc(ch.getId(), org.springframework.data.domain.PageRequest.of(0, 5)))
                .singleElement().satisfies(m -> {
                    assertThat(m.getSourceKind()).isEqualTo("library");
                    assertThat(m.getSourceName()).isEqualTo("Mobil kanallar şablonu");
                });
        TechErrorChannel copy = service.createChannel(teamA, "POS", null, t, 7L, TechErrorReportService.TemplateSource.copy());
        TechErrorTemplateVersion cv = versions.findByChannelIdAndVersion(copy.getId(), 1).orElseThrow();
        assertThat(cv.getSourceKind()).isEqualTo("copy");
        assertThat(cv.getSourceName()).isNull();
        TechErrorChannel plain = service.createChannel(teamA, "ATM", null, tr(), 7L);
        assertThat(versions.findByChannelIdAndVersion(plain.getId(), 1).orElseThrow().getSourceKind()).isNull();
        // Kütüphane kaydı sonradan değişse de kanal kendi kopyasını korur
        library.update(teamA, e.getId(), e.getVersion(), false, null, tr(), null, 7L);
        assertThat(TechErrorTemplate.fromStored(channels.findById(ch.getId()).orElseThrow().getTemplateJson()).title())
                .isEqualTo("Kütüphane {kanal} {ay} {yil}");
    }

    @Test
    @DisplayName("D19: rapor açılışta kanalın şablon sürümünü, 'son hâli uygula'da güncel sürümü taşır; sürüm başına sayı tek gruplu sorgu (bilinmeyen null ayrı); dönem listesi en yeni önce")
    void reportTemplateVersion() {
        TechErrorChannel ch = service.createChannel(teamA, "Mobil", null, tr(), 7L);
        TechErrorReport aug = service.createReport(ch, 2026, 8, 7L);
        assertThat(aug.getTemplateVersion()).isEqualTo(1);
        int v2 = service.saveTemplate(ch.getId(), 1, titled("v2 {kanal} {ay} {yil}"), 7L);
        TechErrorChannel ch2 = channels.findById(ch.getId()).orElseThrow();
        TechErrorReport sep = service.createReport(ch2, 2026, 9, 7L);
        assertThat(sep.getTemplateVersion()).isEqualTo(v2);
        TechErrorReport jul = service.createReport(ch2, 2026, 7, 7L);
        // Sürümü bilinmeyen eski rapor (sütundan önce)
        TechErrorReport old = service.createReport(ch2, 2026, 6, 7L);
        old = reports.findById(old.getId()).orElseThrow();
        old.setTemplateVersion(null);
        reports.saveAndFlush(old);
        assertThat(service.templateVersionCounts(ch.getId())).containsEntry(1, 1L).containsEntry(2, 2L).containsEntry(null, 1L);
        service.refreshTemplate(aug.getId(), aug.getVersion(), 7L);
        assertThat(reports.findById(aug.getId()).orElseThrow().getTemplateVersion()).isEqualTo(2);
        assertThat(service.templateVersionCounts(ch.getId())).doesNotContainKey(1).containsEntry(2, 3L).containsEntry(null, 1L);
        assertThat(service.reportsWithTemplateVersion(ch.getId(), 2)).extracting(TechErrorReportRepository.Row::getReportMonth)
                .containsExactly(9, 8, 7);
        assertThat(service.reportsWithTemplateVersion(ch.getId(), 2)).extracting(TechErrorReportRepository.Row::getTemplateVersion)
                .containsOnly(2);
        // Değer kaydı sürüm sütununa dokunmaz (DynamicUpdate): geri doldurmanın yazdığı değer korunur
        reports.fillTemplateVersion(old.getId(), 1);
        TechErrorReport o = reports.findById(old.getId()).orElseThrow();
        service.saveValues(o.getId(), o.getVersion(), Map.of("fields", Map.of("total-tx", 10)), 7L);
        assertThat(reports.findById(old.getId()).orElseThrow().getTemplateVersion()).isEqualTo(1);
        assertThat(jul.getId()).isNotNull();
    }
}
