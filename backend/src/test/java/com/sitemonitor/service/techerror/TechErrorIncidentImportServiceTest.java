package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.IncidentImage;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorReportEvent;
import com.sitemonitor.model.TechErrorReportIncident;
import com.sitemonitor.repository.IncidentImageRepository;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportEventRepository;
import com.sitemonitor.repository.TechErrorReportImageRepository;
import com.sitemonitor.repository.TechErrorReportIncidentRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.repository.TechErrorTemplateVersionRepository;
import com.sitemonitor.service.IncidentService;
import com.sitemonitor.service.techerror.TechErrorIncidentImportService.Request;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;

import static com.sitemonitor.service.techerror.TechErrorIncidentLinks.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Olay kaydı → anomali konusu İÇE ALMA + CANLI bağ senkronu (2026-11-20) — H2'de GERÇEK SQL. {@code NOT_SUPPORTED}: uygulamadaki
 * gibi işlemsiz çağıran (servisler kendi işlemini açar; olay kaydı güncellemesi işlendikten SONRA senkron koşar).
 * "Şimdi" 2026-12-05 → raporun ayı Kasım 2026.
 */
@DataJpaTest
@Import({TechErrorReportService.class, TechErrorHistoryService.class, TechErrorExportService.class,
        TechErrorIncidentLinkHooks.class, TechErrorIncidentSyncService.class, TechErrorIncidentImportService.class,
        IncidentService.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TechErrorIncidentImportServiceTest {

    @Autowired TechErrorReportService service;
    @Autowired TechErrorIncidentImportService importer;
    @Autowired TechErrorIncidentSyncService sync;
    @Autowired IncidentService incidentService;
    @Autowired TechErrorChannelRepository channels;
    @Autowired TechErrorReportRepository reports;
    @Autowired TechErrorReportImageRepository images;
    @Autowired TechErrorReportIncidentRepository links;
    @Autowired TechErrorReportEventRepository events;
    @Autowired TechErrorTemplateVersionRepository versions;
    @Autowired IncidentRecordRepository incidents;
    @Autowired IncidentImageRepository incidentImages;
    @Autowired PlatformTransactionManager txManager;
    @Autowired EntityManager em;
    @Autowired TechErrorIncidentLinkHooks hooks;

    static final BiPredicate<Long, Long> ALL = (team, createdBy) -> true;
    static final BiPredicate<Long, Long> NONE = (team, createdBy) -> false;

    @BeforeEach
    void clean() {
        links.deleteAll();
        events.deleteAll();
        versions.deleteAll();
        images.deleteAll();
        reports.deleteAll();
        channels.deleteAll();
        incidentImages.deleteAll();
        incidents.deleteAll();
        service.useClock(Clock.fixed(Instant.parse("2026-12-05T09:00:00Z"), ZoneOffset.UTC));
        sync.useExecutor(Runnable::run);                           // olay senkronu çağıranın iş parçacığında (işlendikten sonra)
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private TechErrorChannel channel(long team, String name) {
        return service.createChannel(team, name, null, TechErrorTemplate.defaults("tr"), 7L);
    }

    private TechErrorReport report(long team) {
        return service.createReport(channel(team, "Mobil " + team), 2026, 11, 7L);
    }

    private IncidentRecord incident(long team, String title, String occurred, String resolved, Long errors, Boolean outage,
                                    String outageId, Integer customers, String rca) {
        IncidentRecord e = new IncidentRecord();
        e.setTitle(title);
        e.setOccurredAt(occurred);
        e.setResolvedAt(resolved);
        e.setSeverity("HIGH");
        e.setStatus("RESOLVED");
        e.setCategory("OTHER");
        e.setTeamId(team);
        e.setCreatedByTeamId(team);
        e.setTechErrorCount(errors);
        e.setOutageRelated(outage);
        e.setOutageId(outageId);
        e.setAffectedCustomers(customers);
        e.setRcaSummary(rca);
        e.setBusinessImpact("Ödemeler gecikti");
        e.setResolutionSteps("Disk genişletildi");
        e.setCreatedAt("2026-11-10T08:00:00");
        e.setUpdatedAt("2026-11-10T08:00:00");
        return incidents.save(e);
    }

    private IncidentRecord simple(long team, String title) {
        return incident(team, title, "2026-11-05T07:00:00", "2026-11-05T08:30:00", 1250L, true, "INC-1", 320, "Disk **doldu**");
    }

    private static Request req(String section, Long... ids) {
        return new Request(section, List.of(ids), null, false);
    }

    private TechErrorReport fresh(Long id) {
        return reports.findById(id).orElseThrow();
    }

    private static Template template(TechErrorReport r) {
        return TechErrorTemplate.fromStored(r.getTemplateJson());
    }

    private static Values values(TechErrorReport r) {
        return TechErrorValues.fromStored(r.getValuesJson(), template(r));
    }

    private Issue topic(Long reportId, String issueId) {
        return TechErrorIncidentWriter.find(values(fresh(reportId)), "issues", issueId);
    }

    private TechErrorReportIncident link(Long reportId) {
        List<TechErrorReportIncident> ls = links.findByReportIdOrderByIdAsc(reportId);
        assertThat(ls).hasSize(1);
        return ls.get(0);
    }

    private List<TechErrorReportEvent> history(Long reportId) {
        return events.findByReportIdOrderByIdDesc(reportId, PageRequest.of(0, 50));
    }

    private String importOne(TechErrorReport r, IncidentRecord e) {
        var out = importer.importIncidents(r.getId(), fresh(r.getId()).getVersion(), req("issues", e.getId()), 7L, "Ayşe Yılmaz", ALL);
        return (String) out.imported().get(0).get("issue_id");
    }

    /** Kullanıcının rapor ekranındaki kaydı: konunun bir alanını değiştirip değerleri kaydeder. */
    @SuppressWarnings("unchecked")
    private void userEdits(Long reportId, String issueId, String key, Object value) {
        TechErrorReport r = fresh(reportId);
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorValues.toMap(values(r)));
        Map<String, Object> issues = new LinkedHashMap<>((Map<String, Object>) raw.get("issues"));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object o : (List<Object>) issues.get("issues")) {
            Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) o);
            if (issueId.equals(m.get("id"))) m.put(key, value);
            list.add(m);
        }
        issues.put("issues", list);
        raw.put("issues", issues);
        service.saveValues(reportId, r.getVersion(), raw, 8L);
    }

    @SuppressWarnings("unchecked")
    private void userRemovesTopic(Long reportId, String issueId) {
        TechErrorReport r = fresh(reportId);
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorValues.toMap(values(r)));
        Map<String, Object> issues = new LinkedHashMap<>((Map<String, Object>) raw.get("issues"));
        List<Object> list = new ArrayList<>((List<Object>) issues.get("issues"));
        list.removeIf(o -> issueId.equals(((Map<String, Object>) o).get("id")));
        issues.put("issues", list);
        raw.put("issues", issues);
        service.saveValues(reportId, r.getVersion(), raw, 8L);
    }

    private void sql(String statement, Object... params) {
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            var q = em.createNativeQuery(statement);
            for (int k = 0; k < params.length; k++) q.setParameter(k + 1, params[k]);
            q.executeUpdate();
        });
    }

    private Map<String, Object> linkState(Long reportId, BiPredicate<Long, Long> canRead) {
        List<Map<String, Object>> dto = sync.linksDto(fresh(reportId), service.now(), canRead);
        assertThat(dto).hasSize(1);
        return dto.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> notesOf(Map<String, Object> m) {
        return (List<String>) m.get("notes");
    }

    private IncidentImage incidentImage(Long incidentId, String caption, String contentType, byte[] data, long size) {
        IncidentImage i = new IncidentImage();
        i.setIncidentId(incidentId);
        i.setCaption(caption);
        i.setContentType(contentType);
        i.setData(data);
        i.setSizeBytes(size);
        i.setCreatedAt("2026-11-10T08:00:00");
        return incidentImages.save(i);
    }

    // ── İçe alma ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("içe alma: bölüm sonuna yeni konu (eşlenmiş alanlar), sürüm + içerik sayacı artar, bağ satırı (7 iz, parçalar, olay"
            + " zamanı), tek INCIDENT_IMPORT geçmiş olayı; bağ durumu ok")
    void importWritesTopicLinkAndHistory() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme servisi yavaş");
        var out = importer.importIncidents(r.getId(), r.getVersion(), req("issues", a.getId()), 7L, "Ayşe Yılmaz", ALL);
        assertThat(out.issuesAdded()).isEqualTo(1);
        assertThat(out.imagesAdded()).isZero();
        String iid = (String) out.imported().get(0).get("issue_id");
        assertThat(iid).matches("i-[0-9a-z]{9,}");
        assertThat(out.imported().get(0)).containsEntry("incident_id", a.getId()).containsEntry("notes", List.of());

        TechErrorReport saved = fresh(r.getId());
        assertThat(saved.getVersion()).isGreaterThan(r.getVersion());
        assertThat(saved.getContentRev()).isGreaterThan(r.getContentRev() == null ? 0L : r.getContentRev());
        Issue i = topic(r.getId(), iid);
        assertThat(i.topic()).isEqualTo("Ödeme servisi yavaş");
        assertThat(i.title()).isEqualTo("Ödeme servisi yavaş: Hata Artışı");
        assertThat(i.titleAuto()).isTrue();
        assertThat(i.date()).isEqualTo("2026-11-05");
        assertThat(i.start()).isEqualTo("10:00");
        assertThat(i.end()).isEqualTo("11:30");
        assertThat(i.errors().longValue()).isEqualTo(1250L);
        assertThat(i.outage()).isTrue();
        assertThat(i.outageId()).isEqualTo("INC-1");
        assertThat(i.impact()).isTrue();
        assertThat(i.customers().longValue()).isEqualTo(320L);
        assertThat(i.description()).isEqualTo("**Kök neden**\nDisk **doldu**\n\n**İş etkisi**\nÖdemeler gecikti\n\n**Çözüm**\nDisk genişletildi");

        TechErrorReportIncident l = link(r.getId());
        assertThat(l.getIncidentId()).isEqualTo(a.getId());
        assertThat(l.getSectionId()).isEqualTo("issues");
        assertThat(l.getIssueId()).isEqualTo(iid);
        assertThat(l.getChannelId()).isEqualTo(r.getChannelId());
        assertThat(l.getPinned()).isNull();
        assertThat(l.getParts()).isEqualTo("rca,impact,resolution");
        assertThat(l.getIncidentUpdatedAt()).isEqualTo("2026-11-10T08:00:00");
        assertThat(l.getImportedByName()).isEqualTo("Ayşe Yılmaz");
        Map<String, String> fp = parseFp(l.getFieldsFp());
        assertThat(fp.keySet()).containsExactlyElementsOf(GROUPS);
        for (String g : GROUPS) assertThat(fp.get(g)).as(g).isEqualTo(fingerprint(g, i));

        TechErrorReportEvent ev = history(r.getId()).get(0);
        assertThat(ev.getKind()).isEqualTo(EV_IMPORT);
        assertThat(ev.getActorId()).isEqualTo(7L);
        assertThat(ev.getDetailJson()).contains("\"incident_ids\":[" + a.getId() + "]").contains("\"issues_added\":1")
                .contains("\"section_id\":\"issues\"");

        assertThat(linkState(r.getId(), ALL)).containsEntry("state", S_OK).containsEntry("title", "Ödeme servisi yavaş")
                .containsEntry("incident_id", a.getId()).containsEntry("pinned", List.of()).containsEntry("notes", List.of());
    }

    @Test
    @DisplayName("içe alma HEP YA DA HİÇ: başka takımın olayı / bulunamayan / görülemeyen / anomali olmayan bölüm / eski sürüm → hiçbir"
            + " şey yazılmaz; bulunamayan ve görülemeyen olay AYNI yanıtı alır")
    void importAllOrNothing() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "A");
        IncidentRecord other = simple(2, "Başka takım");

        assertThatThrownBy(() -> importer.importIncidents(r.getId(), r.getVersion(), req("issues", a.getId(), other.getId()), 7L, "x", ALL))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> {
                    assertThat(((FieldValidationException) e).getCode()).isEqualTo("INCIDENT_OTHER_TEAM");
                    assertThat(((FieldValidationException) e).getFields()).containsKey("incident_ids");
                });

        FieldValidationException missing = (FieldValidationException) org.junit.jupiter.api.Assertions.assertThrows(
                FieldValidationException.class,
                () -> importer.importIncidents(r.getId(), r.getVersion(), req("issues", a.getId(), 987654L), 7L, "x", ALL));
        FieldValidationException hidden = (FieldValidationException) org.junit.jupiter.api.Assertions.assertThrows(
                FieldValidationException.class,
                () -> importer.importIncidents(r.getId(), r.getVersion(), req("issues", a.getId()), 7L, "x", NONE));
        assertThat(missing.getCode()).isEqualTo("INCIDENT_NOT_FOUND").isEqualTo(hidden.getCode());
        assertThat(missing.getFields().keySet()).isEqualTo(hidden.getFields().keySet());
        assertThat(missing.getMessage().replace("987654", "#")).isEqualTo(hidden.getMessage().replace(String.valueOf(a.getId()), "#"));

        assertThatThrownBy(() -> importer.importIncidents(r.getId(), r.getVersion(), req("summary", a.getId()), 7L, "x", ALL))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("section_id"));
        assertThatThrownBy(() -> importer.importIncidents(r.getId(), r.getVersion(), req("yok", a.getId()), 7L, "x", ALL))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> importer.importIncidents(r.getId(), r.getVersion() + 1, req("issues", a.getId()), 7L, "x", ALL))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> importer.importIncidents(r.getId(), null, req("issues", a.getId()), 7L, "x", ALL))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("version"));

        TechErrorReport after = fresh(r.getId());
        assertThat(after.getVersion()).isEqualTo(r.getVersion());
        assertThat(values(after).issues()).isEmpty();
        assertThat(links.findByReportIdOrderByIdAsc(r.getId())).isEmpty();
        assertThat(history(r.getId())).extracting(TechErrorReportEvent::getKind).doesNotContain(EV_IMPORT);
    }

    @Test
    @DisplayName("aynı olay bir rapora bir kez: ikinci içe alma 409 INCIDENT_ALREADY_IMPORTED (+ incident_id); bölüm 30 konuda dolar")
    void alreadyImportedAndSectionFull() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "A");
        importOne(r, a);
        assertThatThrownBy(() -> importOne(r, a))
                .isInstanceOf(CodedConflictException.class)
                .satisfies(e -> {
                    assertThat(((CodedConflictException) e).getCode()).isEqualTo("INCIDENT_ALREADY_IMPORTED");
                    assertThat(((CodedConflictException) e).getExtra()).containsEntry("incident_id", a.getId());
                });
        // Bağ satırının iki benzersizliği: aynı rapor + aynı olay, aynı rapor + aynı konu
        TechErrorReportIncident dup = new TechErrorReportIncident();
        dup.setReportId(r.getId());
        dup.setChannelId(r.getChannelId());
        dup.setSectionId("issues");
        dup.setIssueId("başka");
        dup.setIncidentId(a.getId());
        assertThatThrownBy(() -> links.saveAndFlush(dup)).isInstanceOf(DataIntegrityViolationException.class);

        TechErrorReport r2 = service.createReport(channel(1, "Kart"), 2026, 11, 7L);
        List<Map<String, Object>> topics = new ArrayList<>();
        for (int k = 1; k <= 29; k++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "k" + k);
            m.put("topic", "Konu " + k);
            m.put("title_auto", true);
            topics.add(m);
        }
        service.saveValues(r2.getId(), r2.getVersion(), Map.of("issues", Map.of("issues", topics)), 7L);
        IncidentRecord b = simple(1, "B");
        IncidentRecord c = simple(1, "C");
        assertThatThrownBy(() -> importer.importIncidents(r2.getId(), fresh(r2.getId()).getVersion(),
                req("issues", b.getId(), c.getId()), 7L, "x", ALL))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getCode()).isEqualTo("SECTION_FULL"));
        importOne(r2, b);
        assertThat(values(fresh(r2.getId())).issues().get("issues")).hasSize(30);
    }

    @Test
    @DisplayName("önizleme YAZMAZ: alanlar, açıklama özeti, notlar, gizlenecek mi; içe almayla aynı doğrulama")
    void previewWritesNothing() {
        TechErrorReport r = report(1);
        IncidentRecord a = incident(1, "Gece kesintisi", "2026-10-31T21:30:00", null, 10L, true, null, 0, "Kök");
        List<Map<String, Object>> p = importer.preview(fresh(r.getId()), req("issues", a.getId()), ALL);
        assertThat(p).hasSize(1);
        @SuppressWarnings("unchecked") Map<String, Object> f = (Map<String, Object>) p.get(0).get("fields");
        assertThat(f).containsEntry("topic", "Gece kesintisi").containsEntry("date", "2026-11-01").containsEntry("start", null)
                .containsEntry("outage", null).containsEntry("impact", false).containsEntry("customers", null);
        assertThat(p.get(0)).containsEntry("incident_id", a.getId()).containsEntry("hidden", false)
                .containsEntry("notes", List.of(N_TIME_UNKNOWN, N_OUTAGE_ID_MISSING));
        assertThat((String) p.get(0).get("description_preview")).startsWith("**Kök neden**\nKök");
        TechErrorReport after = fresh(r.getId());
        assertThat(after.getVersion()).isEqualTo(r.getVersion());
        assertThat(links.findByReportIdOrderByIdAsc(r.getId())).isEmpty();

        // Eski düzen bölümü (soru yok, görsel yok): görsel istenirse "allowed: false"; tarih / kesinti / etki düşer
        TechErrorChannel legacy = service.createChannel(1L, "Eski", null, TechErrorTestTemplates.beforeAnomaly("tr"), 7L);
        TechErrorReport lr = service.createReport(legacy, 2026, 11, 7L);
        List<Map<String, Object>> lp = importer.preview(fresh(lr.getId()), new Request("issues", List.of(a.getId()), null, true), ALL);
        @SuppressWarnings("unchecked") Map<String, Object> img = (Map<String, Object>) lp.get(0).get("images");
        assertThat(img).containsEntry("copy", 0).containsEntry("allowed", false);
        @SuppressWarnings("unchecked") Map<String, Object> lf = (Map<String, Object>) lp.get(0).get("fields");
        assertThat(lf).containsEntry("date", null).containsEntry("outage", null).containsEntry("impact", null);
        assertThat(lp.get(0).get("notes")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("görseller: açıklama parçalarındaki sıra, en çok 2 kopya; başka olayın / biçimi desteklenmeyen / büyük / küçük görsel"
            + " nedeniyle atlanır; açıklama = olay görselinin adı, yoksa alt metin; rapor toplamı 40 MB")
    void imagesCopiedAndSkipped() throws Exception {
        TechErrorReport r = report(1);
        IncidentRecord other = simple(1, "Başka olay");
        IncidentRecord c = simple(1, "Görselli");
        byte[] ok = TechErrorImageValidatorTest.png(16, 16);
        IncidentImage valid1 = incidentImage(c.getId(), "Grafik 1", "image/png", ok, ok.length);
        IncidentImage valid2 = incidentImage(c.getId(), null, "image/png", ok, ok.length);
        IncidentImage gif = incidentImage(c.getId(), "Hareketli", "image/gif", new byte[]{ 'G', 'I', 'F' }, 3);
        IncidentImage huge = incidentImage(c.getId(), "Dev", "image/png", ok, 6L * 1024 * 1024);
        byte[] tiny = TechErrorImageValidatorTest.png(10, 10);
        IncidentImage small = incidentImage(c.getId(), "Küçük", "image/png", tiny, tiny.length);
        IncidentImage foreign = incidentImage(other.getId(), "Başkası", "image/png", ok, ok.length);
        c.setRcaSummary("Bakın:\n\n![x](/api/incidents/images/" + foreign.getId() + ")\n\n![g](/api/incidents/images/" + gif.getId()
                + ")\n\n![h](/api/incidents/images/" + huge.getId() + ")\n\n![s](/api/incidents/images/" + small.getId()
                + ")\n\n![Ekran 2](/api/incidents/images/" + valid2.getId() + ")\n\n![a](/api/incidents/images/" + valid1.getId() + ")");
        incidents.save(c);

        Request withImages = new Request("issues", List.of(c.getId()), null, true);
        List<Map<String, Object>> p = importer.preview(fresh(r.getId()), withImages, ALL);
        @SuppressWarnings("unchecked") Map<String, Object> pi = (Map<String, Object>) p.get(0).get("images");
        assertThat(pi.get("copy")).isEqualTo(2);                       // önizleme baytları okumaz: küçük görsel kopya sayılır
        assertThat(pi.get("skipped")).isEqualTo(List.of(
                Map.of("reason", "unreadable", "image_id", foreign.getId()),
                Map.of("reason", "format", "image_id", gif.getId()),
                Map.of("reason", "too_large", "image_id", huge.getId())));
        assertThat(p.get(0).get("notes")).isEqualTo(List.of(N_IMAGE_SKIPPED));

        var out = importer.importIncidents(r.getId(), r.getVersion(), withImages, 7L, "x", ALL);
        assertThat(out.imagesAdded()).isEqualTo(2);
        String iid = (String) out.imported().get(0).get("issue_id");
        assertThat(out.imported().get(0).get("notes")).isEqualTo(List.of(N_IMAGE_SKIPPED));
        assertThat(images.findByReportIdOrderByIdAsc(r.getId())).extracting(TechErrorReportImageRepository.Meta::getSlotId)
                .containsExactly(TechErrorTemplate.issueSlotId("issues", iid, 1), TechErrorTemplate.issueSlotId("issues", iid, 2));
        Issue i = topic(r.getId(), iid);
        assertThat(i.captions()).containsEntry("1", "Ekran 2").containsEntry("2", "Grafik 1");
        assertThat(i.description()).doesNotContain("/api/incidents/images/");
        assertThat(history(r.getId()).get(0).getDetailJson()).contains("\"images_added\":2");

        // Rapor toplamı: 40 MB dolu → "total"
        TechErrorReport r2 = service.createReport(channel(1, "Kart"), 2026, 11, 7L);
        var existing = service.upload(r2.getId(), "sla-shot-1", TechErrorImageValidatorTest.png(400, 200), "a.png", 7L);
        sql("UPDATE tech_error_report_images SET size_bytes = ?1 WHERE id = ?2",
                TechErrorReportService.MAX_REPORT_IMAGE_BYTES - 10, existing.getId());
        IncidentRecord d = simple(1, "Toplam");
        IncidentImage di = incidentImage(d.getId(), "D", "image/png", ok, ok.length);
        var o2 = importer.importIncidents(r2.getId(), fresh(r2.getId()).getVersion(),
                new Request("issues", List.of(d.getId()), null, true), 7L, "x", ALL);
        assertThat(o2.imagesAdded()).isZero();
        assertThat(o2.imported().get(0).get("notes")).isEqualTo(List.of(N_IMAGE_SKIPPED));
        assertThat(importer.plan(incidents.findById(d.getId()).orElseThrow(), null, TechErrorReportService.MAX_REPORT_IMAGE_BYTES - 10, true)
                .skipped()).extracting(TechErrorIncidentImportService.Skip::reason).containsExactly("total");
        assertThat(di.getId()).isNotNull();
    }

    @Test
    @DisplayName("kilit sırası: içe alma ve yenileme raporun satır kilidini İLK iş olarak alır — satırı tutan işlem varken bekler ve"
            + " bağ satırlarına dokunmaz")
    void writersLockReportRowFirst() throws Exception {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "A");
        String iid = importOne(r, a);
        IncidentRecord b = simple(1, "B");
        assertLocksReportRowBeforeLinks(r.getId(), () -> importer.importIncidents(r.getId(), fresh(r.getId()).getVersion(),
                req("issues", b.getId()), 7L, "x", ALL));
        assertThat(links.findByReportIdOrderByIdAsc(r.getId())).hasSize(2);
        assertLocksReportRowBeforeLinks(r.getId(), () -> sync.refresh(r.getId(), fresh(r.getId()).getVersion(), "issues", iid,
                null, 7L, ALL));
        assertLocksReportRowBeforeLinks(r.getId(), () -> sync.unlink(r.getId(), "issues", iid, 7L));
        assertThat(links.findByReportIdOrderByIdAsc(r.getId())).hasSize(1);

        // Olayla gelen senkron (AUTO) ve yetişme (CATCH_UP) de satırı İLK iş olarak alır (inceleme 2026-11-20)
        String ib = link(r.getId()).getIssueId();
        IncidentRecord eb = incidents.findById(b.getId()).orElseThrow();
        eb.setTechErrorCount(31L);
        incidents.save(eb);
        assertLocksReportRowBeforeLinks(r.getId(), () -> sync.onIncidentChanged(b.getId()));
        assertThat(topic(r.getId(), ib).errors().longValue()).isEqualTo(31L);
        eb = incidents.findById(b.getId()).orElseThrow();
        eb.setTechErrorCount(32L);
        eb.setUpdatedAt("2026-11-13T00:00:00");
        incidents.save(eb);
        assertLocksReportRowBeforeLinks(r.getId(), () -> sync.catchUp(r.getId()));
        assertThat(topic(r.getId(), ib).errors().longValue()).isEqualTo(32L);
    }

    private void assertLocksReportRowBeforeLinks(Long reportId, Runnable writer) throws Exception {
        var tx = new TransactionTemplate(txManager);
        var locked = new CountDownLatch(1);
        var probed = new AtomicInteger(-1);
        var probeMs = new AtomicLong(-1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var holder = pool.submit(() -> tx.execute(st -> {
                reports.lockRow(reportId);
                locked.countDown();
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                long t0 = System.nanoTime();
                probed.set(em.createNativeQuery("UPDATE tech_error_report_incidents SET synced_at = synced_at WHERE report_id = ?1")
                        .setParameter(1, reportId).executeUpdate());
                probeMs.set((System.nanoTime() - t0) / 1_000_000L);
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long t0 = System.nanoTime();
            writer.run();
            long waited = (System.nanoTime() - t0) / 1_000_000L;
            holder.get(10, TimeUnit.SECONDS);
            assertThat(waited).as("yazan rapor satırını bekledi").isGreaterThanOrEqualTo(300L);
            assertThat(probed.get()).as("beklerken bağ satırları serbestti").isPositive();
            assertThat(probeMs.get()).as("bağ satırı kilidi beklenmedi").isLessThan(300L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("eşzamanlı iki içe alma (aynı okunan sürüm): biri yazar, diğeri 409 — tek bağ")
    void concurrentImportsOneWins() throws Exception {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "A");
        long v = r.getVersion();
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (int k = 0; k < 2; k++) {
                fs.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        importer.importIncidents(r.getId(), v, req("issues", a.getId()), 7L, "x", ALL);
                        return "ok";
                    } catch (OptimisticLockingFailureException | CodedConflictException e) {
                        return "409";
                    }
                }));
            }
            start.countDown();
            List<String> got = new ArrayList<>();
            for (Future<String> f : fs) got.add(f.get(30, TimeUnit.SECONDS));
            assertThat(got).containsExactlyInAnyOrder("ok", "409");
        } finally {
            pool.shutdownNow();
        }
        assertThat(links.findByReportIdOrderByIdAsc(r.getId())).hasSize(1);
        assertThat(values(fresh(r.getId())).issues().get("issues")).hasSize(1);
    }

    // ── Bağın yaşamı ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bağ konuyla / raporla / kanalla gider; şablon yenileme bağı korur (uyarlamanın değiştirdiği iz yeniden tabanlanır,"
            + " sabitlenmez); bağı koparma içeriği ve sayacı değiştirmez")
    void linkLifecycle() {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorReport r = service.createReport(ch, 2026, 11, 7L);
        IncidentRecord a = simple(1, "A");
        IncidentRecord b = simple(1, "B");
        String ia = importOne(r, a);
        String ib = importOne(r, b);
        assertThat(links.findByReportIdOrderByIdAsc(r.getId())).hasSize(2);

        // Konu silindi → bağı gider
        userRemovesTopic(r.getId(), ia);
        assertThat(links.findByReportIdOrderByIdAsc(r.getId())).extracting(TechErrorReportIncident::getIssueId).containsExactly(ib);

        // Şablon yenileme: müşteri etkisi sorusu kapatıldı → etki düşer, iz yeniden tabanlanır, SABİTLENMEZ
        Map<String, Object> raw = TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr"));
        @SuppressWarnings("unchecked") List<Map<String, Object>> secs = new ArrayList<>((List<Map<String, Object>>) raw.get("sections"));
        secs.replaceAll(x -> {
            if (!"issues".equals(x.get("kind"))) return x;
            Map<String, Object> copy = new LinkedHashMap<>(x);
            copy.put("ask_impact", false);
            copy.remove("no_impact_display");
            copy.remove("impact_label");
            return copy;
        });
        raw.put("sections", secs);
        service.saveTemplate(ch.getId(), 1, TechErrorTemplate.normalize(raw), 7L);
        service.refreshTemplate(r.getId(), fresh(r.getId()).getVersion(), 7L);
        TechErrorReportIncident l = link(r.getId());
        assertThat(l.getPinned()).isNull();
        Issue now = topic(r.getId(), ib);
        assertThat(now.impact()).isNull();
        assertThat(now.customers().longValue()).isEqualTo(320L);
        assertThat(parseFp(l.getFieldsFp()).get(G_IMPACT)).isEqualTo(fingerprint(G_IMPACT, now));

        // Bağı koparma: konu kalır, sürüm ve içerik sayacı AYNI, INCIDENT_UNLINK
        TechErrorReport before = fresh(r.getId());
        assertThat(sync.unlink(r.getId(), "issues", ib, 7L)).isEqualTo(b.getId());
        TechErrorReport after = fresh(r.getId());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getContentRev()).isEqualTo(before.getContentRev());
        assertThat(topic(r.getId(), ib)).isNotNull();
        assertThat(links.findByReportIdOrderByIdAsc(r.getId())).isEmpty();
        TechErrorReportEvent ev = history(r.getId()).get(0);
        assertThat(ev.getKind()).isEqualTo(EV_UNLINK);
        assertThat(ev.getDetailJson()).contains("\"incident_id\":" + b.getId()).contains("\"topic\":\"B\"");
        assertThatThrownBy(() -> sync.unlink(r.getId(), "issues", ib, 7L))
                .isInstanceOf(CodedNotFoundException.class)
                .satisfies(e -> assertThat(((CodedNotFoundException) e).getCode()).isEqualTo("INCIDENT_LINK_NOT_FOUND"));

        // Rapor silinince bağları gider; kanal silinince raporlarının bağları gider
        String ic = importOne(r, simple(1, "C"));
        assertThat(ic).isNotNull();
        service.deleteReport(r.getId(), false);
        assertThat(links.findAll()).isEmpty();
        TechErrorReport r2 = service.createReport(ch, 2026, 10, 7L);
        TechErrorReport r3 = service.createReport(ch, 2026, 9, 7L);
        importer.importIncidents(r2.getId(), r2.getVersion(), req("issues", simple(1, "D").getId()), 7L, "x", ALL);
        importer.importIncidents(r3.getId(), r3.getVersion(), req("issues", simple(1, "E").getId()), 7L, "x", ALL);
        assertThat(links.findAll()).hasSize(2);
        service.deleteChannel(ch.getId());
        assertThat(links.findAll()).isEmpty();
    }

    // ── Senkron ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("olay güncellenince (işlendikten SONRA) bağlı rapor senkronlanır: sabitlenmemiş gruplar yazılır, sürüm + içerik sayacı"
            + " artar, INCIDENT_SYNC 'SiteMonitor' adıyla; kullanıcının değiştirdiği grup SABİTLENİR ve yazılmaz")
    void syncFollowsUnpinnedAndKeepsPinned() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme");
        String iid = importOne(r, a);

        userEdits(r.getId(), iid, "errors", 99);                       // kullanıcı hata adedini değiştirdi
        assertThat(link(r.getId()).getPinned()).isEqualTo(G_ERRORS);
        assertThat(linkState(r.getId(), ALL)).containsEntry("pinned", List.of(G_ERRORS));

        TechErrorReport before = fresh(r.getId());
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Ödeme servisi durdu");
        body.put("tech_error_count", 2000);
        body.put("affected_customers", 450);
        incidentService.update(a.getId(), body, "olayci");

        TechErrorReport after = fresh(r.getId());
        assertThat(after.getVersion()).isGreaterThan(before.getVersion());
        assertThat(after.getContentRev()).isGreaterThan(before.getContentRev());
        Issue i = topic(r.getId(), iid);
        assertThat(i.topic()).isEqualTo("Ödeme servisi durdu");
        assertThat(i.title()).isEqualTo("Ödeme servisi durdu: Hata Artışı");
        assertThat(i.customers().longValue()).isEqualTo(450L);
        assertThat(i.errors().longValue()).isEqualTo(99L);             // sabitlenmiş: olayın 2000'i yazılmadı
        TechErrorReportEvent ev = history(r.getId()).get(0);
        assertThat(ev.getKind()).isEqualTo(EV_SYNC);
        assertThat(ev.getActorId()).isNull();
        assertThat(ev.getActorName()).isEqualTo(TechErrorIncidentSyncService.ACTOR);
        assertThat(ev.getDetailJson()).contains("\"incident_id\":" + a.getId());
        TechErrorReportIncident l = link(r.getId());
        assertThat(l.getIncidentUpdatedAt()).isEqualTo(incidents.findById(a.getId()).orElseThrow().getUpdatedAt());
        assertThat(linkState(r.getId(), ALL)).containsEntry("state", S_OK).containsEntry("pinned", List.of(G_ERRORS));

        // Açık yenileme: sabitleme kaldırılır, olayın değeri yazılır; yapan kişi
        TechErrorReport cur = fresh(r.getId());
        sync.refresh(r.getId(), cur.getVersion(), "issues", iid, List.of(G_ERRORS), 9L, ALL);
        assertThat(topic(r.getId(), iid).errors().longValue()).isEqualTo(2000L);
        assertThat(link(r.getId()).getPinned()).isNull();
        TechErrorReportEvent rev = history(r.getId()).get(0);
        assertThat(rev.getKind()).isEqualTo(EV_SYNC);
        assertThat(rev.getActorId()).isEqualTo(9L);
        assertThat(rev.getDetailJson()).contains("\"refresh\":true").contains("\"unpinned\":[\"errors\"]");

        // Yenileme kuralları: eski sürüm 409, geçersiz unpin 400, olmayan bağ 404, görülemeyen olay 404 (silinmişle aynı)
        assertThatThrownBy(() -> sync.refresh(r.getId(), cur.getVersion(), "issues", iid, "all", 9L, ALL))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> sync.refresh(r.getId(), fresh(r.getId()).getVersion(), "issues", iid, List.of("nope"), 9L, ALL))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("unpin"));
        assertThatThrownBy(() -> sync.refresh(r.getId(), fresh(r.getId()).getVersion(), "issues", iid, "hepsi", 9L, ALL))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> sync.refresh(r.getId(), fresh(r.getId()).getVersion(), "issues", "yok", null, 9L, ALL))
                .isInstanceOf(CodedNotFoundException.class)
                .satisfies(e -> assertThat(((CodedNotFoundException) e).getCode()).isEqualTo("INCIDENT_LINK_NOT_FOUND"));
        CodedNotFoundException hidden = (CodedNotFoundException) org.junit.jupiter.api.Assertions.assertThrows(CodedNotFoundException.class,
                () -> sync.refresh(r.getId(), fresh(r.getId()).getVersion(), "issues", iid, "all", 9L, NONE));
        assertThat(hidden.getCode()).isEqualTo("INCIDENT_NOT_FOUND");
    }

    @Test
    @DisplayName("dondurulmuş rapor (gönderildi / onay bekliyor / onaylandı) kendiliğinden YAZILMAZ — durum frozen_stale; açık yenileme"
            + " yazar; eskimiş onay dondurmaz")
    void frozenReportNeverAutoWritten() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme");
        String iid = importOne(r, a);
        sql("UPDATE tech_error_reports SET last_sent_at = ?1, sent_rev = content_rev WHERE id = ?2",
                Instant.parse("2026-12-04T09:00:00Z"), r.getId());
        assertThat(TechErrorReportService.reportStatus(TechErrorIncidentLinks.row(fresh(r.getId())), service.now())).isEqualTo("sent");

        TechErrorReport before = fresh(r.getId());
        incidentService.update(a.getId(), new HashMap<>(Map.of("title", "Yeni başlık")), "olayci");
        assertThat(fresh(r.getId()).getVersion()).isEqualTo(before.getVersion());
        assertThat(topic(r.getId(), iid).topic()).isEqualTo("Ödeme");
        assertThat(linkState(r.getId(), ALL)).containsEntry("state", S_FROZEN_STALE);
        assertThat(sync.catchUp(r.getId())).isZero();
        assertThat(topic(r.getId(), iid).topic()).isEqualTo("Ödeme");

        sync.refresh(r.getId(), fresh(r.getId()).getVersion(), "issues", iid, null, 7L, ALL);
        assertThat(topic(r.getId(), iid).topic()).isEqualTo("Yeni başlık");
        assertThat(TechErrorReportService.reportStatus(TechErrorIncidentLinks.row(fresh(r.getId())), service.now())).isEqualTo("changed");
        assertThat(linkState(r.getId(), ALL)).containsEntry("state", S_OK);
    }

    @Test
    @DisplayName("dondurma kuralı: gönderildi / kuyrukta / gönderiliyor ve onay bekleyen / onaylanmış (eskimemiş) rapor donuk; taslak,"
            + " değişmiş gönderim ve eskimiş onay donuk değil")
    void frozenRule() {
        Instant now = Instant.parse("2026-12-05T09:00:00Z");
        TechErrorReport draft = new TechErrorReport();
        assertThat(TechErrorIncidentLinks.frozen(draft, now)).isFalse();
        TechErrorReport sent = new TechErrorReport();
        sent.setLastSentAt(now.minusSeconds(60));
        sent.setContentRev(3L);
        sent.setSentRev(3L);
        assertThat(TechErrorIncidentLinks.frozen(sent, now)).isTrue();
        sent.setContentRev(4L);
        assertThat(TechErrorIncidentLinks.frozen(sent, now)).isFalse();             // gönderimden sonra değişti
        TechErrorReport queued = new TechErrorReport();
        queued.setSendState("QUEUED");
        assertThat(TechErrorIncidentLinks.frozen(queued, now)).isTrue();
        TechErrorReport sending = new TechErrorReport();
        sending.setSendState("SENDING");
        sending.setSendClaimedAt(now.minusSeconds(30));
        assertThat(TechErrorIncidentLinks.frozen(sending, now)).isTrue();
        for (String st : List.of("PENDING", "APPROVED")) {
            TechErrorReport ap = new TechErrorReport();
            ap.setApprovalState(st);
            ap.setContentRev(5L);
            ap.setApprovalRev(5L);
            assertThat(TechErrorIncidentLinks.frozen(ap, now)).as(st).isTrue();
            // Onay akışı kaldırıldı (yönetici alıcısı yok): bekleyen / verilmiş onay dondurmaz (inceleme 2026-11-20)
            assertThat(TechErrorIncidentLinks.frozen(ap, () -> false, now)).as(st + " akış kaldırıldı").isFalse();
            assertThat(TechErrorIncidentLinks.frozen(ap, () -> true, now)).as(st + " akış var").isTrue();
            ap.setContentRev(6L);
            assertThat(TechErrorIncidentLinks.frozen(ap, now)).as(st + " eskimiş").isFalse();
        }
        // Gönderilmiş rapor akıştan bağımsız donuktur; akış sağlayıcısı onay durumu belirleyici değilse hiç sorulmaz
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        assertThat(TechErrorIncidentLinks.frozen(queued, () -> { asked.incrementAndGet(); return false; }, now)).isTrue();
        assertThat(TechErrorIncidentLinks.frozen(draft, () -> { asked.incrementAndGet(); return true; }, now)).isFalse();
        assertThat(asked.get()).isZero();
        TechErrorReport rejected = new TechErrorReport();
        rejected.setApprovalState("REJECTED");
        assertThat(TechErrorIncidentLinks.frozen(rejected, now)).isFalse();
    }

    @Test
    @DisplayName("silinen olay → durum deleted (başlık yok), yazılacak bir şey yok; görülemeyen olay → hidden; yetişme yalnız BAYAT bağı"
            + " yazar ve bayat bağ yoksa kilit almaz")
    void deletedHiddenAndCatchUp() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme");
        String iid = importOne(r, a);
        assertThat(linkState(r.getId(), NONE)).containsEntry("state", S_HIDDEN).containsEntry("title", null)
                .containsEntry("status", null).containsEntry("occurred_at", null);
        assertThat(sync.catchUp(r.getId())).isZero();                  // bayat değil

        // Olay olaysız değişti (ör. başka pod, kaybolan senkron): yetişme yazar
        IncidentRecord e = incidents.findById(a.getId()).orElseThrow();
        e.setTechErrorCount(77L);
        e.setUpdatedAt("2026-11-12T00:00:00");
        incidents.save(e);
        assertThat(linkState(r.getId(), ALL)).containsEntry("state", S_STALE);
        assertThat(sync.catchUp(r.getId())).isEqualTo(1);
        assertThat(topic(r.getId(), iid).errors().longValue()).isEqualTo(77L);
        assertThat(sync.catchUp(r.getId())).isZero();

        incidents.deleteById(a.getId());
        assertThat(linkState(r.getId(), ALL)).containsEntry("state", S_DELETED).containsEntry("title", null);
        assertThat(sync.catchUp(r.getId())).isZero();
        sync.onIncidentChanged(a.getId());
        assertThat(topic(r.getId(), iid).errors().longValue()).isEqualTo(77L);
        assertThatThrownBy(() -> sync.refresh(r.getId(), fresh(r.getId()).getVersion(), "issues", iid, null, 7L, ALL))
                .isInstanceOf(CodedNotFoundException.class)
                .satisfies(x -> assertThat(((CodedNotFoundException) x).getCode()).isEqualTo("INCIDENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("reddedilen değer eskisini korur + value_refused: tarihi kullanıcı sildi (sabit), olay çözülünce saat tarihsiz yazılamaz;"
            + " olay ayın dışına taşınınca konudaki tarih korunur")
    void refusedValuesKeepOld() {
        TechErrorReport r = report(1);
        IncidentRecord a = incident(1, "Açık olay", "2026-11-05T07:00:00", null, 5L, false, null, null, "Kök");
        String iid = importOne(r, a);
        assertThat(topic(r.getId(), iid).date()).isEqualTo("2026-11-05");
        assertThat(link(r.getId()).getSyncNotes()).isEqualTo(N_TIME_UNKNOWN);

        userEdits(r.getId(), iid, "date", null);                        // tarih sabitlendi (boş), saat zaten boştu
        assertThat(link(r.getId()).getPinned()).isEqualTo(G_DATE);
        TechErrorReport before = fresh(r.getId());
        incidentService.update(a.getId(), new HashMap<>(Map.of("resolved_at", "2026-11-05T09:00:00")), "olayci");
        Issue i = topic(r.getId(), iid);
        assertThat(i.date()).isNull();
        assertThat(i.start()).isNull();
        assertThat(fresh(r.getId()).getVersion()).isEqualTo(before.getVersion());   // yazılacak başka bir şey yoktu
        assertThat(linkState(r.getId(), ALL)).containsEntry("state", S_OK)
                .satisfies(m -> assertThat(notesOf(m)).contains(N_VALUE_REFUSED));

        // Ay dışına taşındı: tarih ve saat korunur, not düşer, diğer gruplar yazılır
        TechErrorReport r2 = service.createReport(channel(1, "Kart"), 2026, 11, 7L);
        IncidentRecord b = incident(1, "Taşınan", "2026-11-05T07:00:00", "2026-11-05T08:00:00", 5L, false, null, null, "Kök");
        String ib = importOne(r2, b);
        Map<String, Object> body = new HashMap<>();
        body.put("occurred_at", "2026-12-02T07:00:00");
        body.put("resolved_at", "2026-12-02T08:00:00");
        body.put("tech_error_count", 9);
        incidentService.update(b.getId(), body, "olayci");
        Issue j = topic(r2.getId(), ib);
        assertThat(j.date()).isEqualTo("2026-11-05");
        assertThat(j.start()).isEqualTo("10:00");
        assertThat(j.errors().longValue()).isEqualTo(9L);
        assertThat(linkState(r2.getId(), ALL)).satisfies(m -> assertThat(notesOf(m)).contains(N_VALUE_REFUSED,
                N_DATE_OUTSIDE_MONTH));
    }

    @Test
    @DisplayName("aynı saniyede ikinci düzenleme (updated_at değişmez) olayla gelen senkronda yine yazılır")
    void sameSecondSecondEditIsSynced() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme");
        String iid = importOne(r, a);
        IncidentRecord e = incidents.findById(a.getId()).orElseThrow();
        e.setTechErrorCount(500L);                                     // updated_at AYNI kaldı
        incidents.save(e);
        sync.onIncidentRecordChanged(new com.sitemonitor.service.IncidentRecordChangedEvent(a.getId()));
        assertThat(topic(r.getId(), iid).errors().longValue()).isEqualTo(500L);
    }

    @Test
    @DisplayName("aday listesi: raporun ayında başlamış takım olayları (en yeni önce), örtüşenler isteğe bağlı, Türkçe harf duyarsız arama,"
            + " okunamayan olay yok, içe alınmış işaretli; 200 karakterden uzun arama 400")
    void candidates() {
        TechErrorReport r = report(1);
        IncidentRecord nov1 = incident(1, "ÖDEME kesintisi", "2026-11-03T07:00:00", "2026-11-03T08:00:00", 1L, null, null, null, "x");
        IncidentRecord nov2 = incident(1, "Kart hatası", "2026-11-20T07:00:00", null, 1L, null, null, null, "x");
        IncidentRecord octOpen = incident(1, "Ekimden süren", "2026-10-20T07:00:00", null, 1L, null, null, null, "x");
        IncidentRecord octDone = incident(1, "Ekimde bitti", "2026-10-20T07:00:00", "2026-10-21T07:00:00", 1L, null, null, null, "x");
        IncidentRecord dec = incident(1, "Aralık", "2026-12-02T07:00:00", null, 1L, null, null, null, "x");
        IncidentRecord edge = incident(1, "Gece yarısı", "2026-10-31T21:30:00", null, 1L, null, null, null, "x");
        incident(2, "Başka takım", "2026-11-04T07:00:00", null, 1L, null, null, null, "x");
        importOne(r, nov1);

        Map<String, Object> page = importer.candidates(fresh(r.getId()), null, 0, 20, false, ALL);
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items).extracting(m -> m.get("id")).containsExactly(nov2.getId(), nov1.getId(), edge.getId());
        assertThat(items.get(1)).containsEntry("imported", true);
        assertThat(items.get(0)).containsEntry("imported", false).containsEntry("title", "Kart hatası");
        assertThat(page).containsEntry("total", 3).containsEntry("truncated", false)
                .containsEntry("period", Map.of("year", 2026, "month", 11));

        @SuppressWarnings("unchecked") List<Map<String, Object>> overlap = (List<Map<String, Object>>)
                importer.candidates(fresh(r.getId()), null, 0, 20, true, ALL).get("items");
        assertThat(overlap).extracting(m -> m.get("id")).contains(octOpen.getId()).doesNotContain(octDone.getId(), dec.getId());

        @SuppressWarnings("unchecked") List<Map<String, Object>> search = (List<Map<String, Object>>)
                importer.candidates(fresh(r.getId()), "ödeme", 0, 20, false, ALL).get("items");
        assertThat(search).extracting(m -> m.get("id")).containsExactly(nov1.getId());
        assertThat(importer.candidates(fresh(r.getId()), null, 0, 20, false, NONE)).containsEntry("total", 0);
        @SuppressWarnings("unchecked") List<Map<String, Object>> paged = (List<Map<String, Object>>)
                importer.candidates(fresh(r.getId()), null, 1, 2, false, ALL).get("items");
        assertThat(paged).extracting(m -> m.get("id")).containsExactly(edge.getId());
        assertThatThrownBy(() -> importer.candidates(fresh(r.getId()), "x".repeat(201), 0, 20, false, ALL))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("q"));
    }

    @Test
    @DisplayName("kullanıldığı raporlar: olayın bağlı olduğu, bakanın okuyabildiği raporlar (kanal, takım adı, dönem, konu, durum)")
    void usage() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme");
        importOne(r, a);
        List<Map<String, Object>> u = sync.usage(incidents.findById(a.getId()).orElseThrow(), team -> true, team -> "Dijital");
        assertThat(u).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("report_id", r.getId()).containsEntry("channel_name", "Mobil 1")
                    .containsEntry("team_name", "Dijital").containsEntry("year", 2026).containsEntry("month", 11)
                    .containsEntry("topic", "Ödeme").containsEntry("state", S_OK).containsEntry("status", "draft");
        });
        assertThat(sync.usage(incidents.findById(a.getId()).orElseThrow(), team -> false, team -> "x")).isEmpty();
    }

    @Test
    @DisplayName("açıklama parçaları bağa yazılır ve senkronda aynı parçalar kullanılır")
    void partsAreRemembered() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme");
        a.setDescription("Teknik ayrıntı");
        incidents.save(a);
        var out = importer.importIncidents(r.getId(), r.getVersion(),
                new Request("issues", List.of(a.getId()), List.of(P_TECHNICAL), false), 7L, "x", ALL);
        String iid = (String) out.imported().get(0).get("issue_id");
        assertThat(link(r.getId()).getParts()).isEqualTo(P_TECHNICAL);
        assertThat(topic(r.getId(), iid).description()).isEqualTo("**Teknik açıklama**\nTeknik ayrıntı");
        incidentService.update(a.getId(), new HashMap<>(Map.of("description", "Yeni teknik ayrıntı")), "olayci");
        assertThat(topic(r.getId(), iid).description()).isEqualTo("**Teknik açıklama**\nYeni teknik ayrıntı");
    }

    // ── İnceleme düzeltmeleri (2026-11-20) ───────────────────────────────────────────────────────────

    private void assertNotWritten(Long reportId, TechErrorReport before, String issueId, String topicName) {
        TechErrorReport now = fresh(reportId);
        assertThat(now.getVersion()).isEqualTo(before.getVersion());
        assertThat(now.getContentRev()).isEqualTo(before.getContentRev());
        assertThat(topic(reportId, issueId).topic()).isEqualTo(topicName);
    }

    private org.hibernate.stat.Statistics statistics() {
        org.hibernate.stat.Statistics s = em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        s.clear();
        return s;
    }

    private TechErrorReportIncident linkRow(TechErrorReport r, String sectionId, String issueId, long incidentId) {
        TechErrorReportIncident l = new TechErrorReportIncident();
        l.setReportId(r.getId());
        l.setChannelId(r.getChannelId());
        l.setSectionId(sectionId);
        l.setIssueId(issueId);
        l.setIncidentId(incidentId);
        return l;
    }

    @Test
    @DisplayName("olay raporun takımından çıkınca (toplu transfer, team_id güncellemesi, raporun takım taşıması) bağ HİÇ yazılmaz: AUTO /"
            + " yetişme yazmaz, yenileme 404 INCIDENT_NOT_FOUND, durum hidden (başlık yok); kullanıldığı raporlarda da hidden")
    void otherTeamIncidentIsNeverWritten() {
        // 1) Toplu transfer, ardından B takımının düzenlemesi
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme");
        String ia = importOne(r, a);
        incidentService.transfer(List.of(a.getId()), 2L, "B takımı", "olayci");
        TechErrorReport before = fresh(r.getId());
        Map<String, Object> body = new HashMap<>();
        body.put("title", "B takımının başlığı");
        body.put("tech_error_count", 999);
        incidentService.update(a.getId(), body, "b-olayci");
        assertNotWritten(r.getId(), before, ia, "Ödeme");
        assertThat(linkState(r.getId(), ALL)).containsEntry("state", S_HIDDEN).containsEntry("title", null)
                .containsEntry("status", null).containsEntry("occurred_at", null);
        assertThat(sync.catchUp(r.getId())).isZero();
        assertThat(sync.catchUp(fresh(r.getId()))).isZero();
        assertThatThrownBy(() -> sync.refresh(r.getId(), fresh(r.getId()).getVersion(), "issues", ia, "all", 9L, ALL))
                .isInstanceOf(CodedNotFoundException.class)
                .satisfies(e -> assertThat(((CodedNotFoundException) e).getCode()).isEqualTo("INCIDENT_NOT_FOUND"));
        sync.onIncidentChanged(a.getId());
        assertNotWritten(r.getId(), before, ia, "Ödeme");
        assertThat(sync.usage(incidents.findById(a.getId()).orElseThrow(), team -> true, team -> "x"))
                .singleElement().satisfies(m -> assertThat(m).containsEntry("state", S_HIDDEN));

        // 2) Olayın team_id'si sıradan güncellemeyle değişti (aynı istekte başlık da değişti)
        TechErrorReport r2 = service.createReport(channel(1, "Kart"), 2026, 11, 7L);
        IncidentRecord b = simple(1, "Kart");
        String ib = importOne(r2, b);
        TechErrorReport before2 = fresh(r2.getId());
        Map<String, Object> move = new HashMap<>();
        move.put("team_id", 2);
        move.put("title", "Taşındı");
        incidentService.update(b.getId(), move, "olayci");
        assertNotWritten(r2.getId(), before2, ib, "Kart");
        assertThat(linkState(r2.getId(), ALL)).containsEntry("state", S_HIDDEN);

        // 3) Takım taşıması: kanal + rapor başka takıma geçti (TeamAdminService'in yaptığı gibi), olay yerinde kaldı
        TechErrorChannel ch3 = channel(1, "POS");
        TechErrorReport r3 = service.createReport(ch3, 2026, 11, 7L);
        IncidentRecord c = simple(1, "POS");
        String ic = importOne(r3, c);
        sql("UPDATE tech_error_reports SET team_id = ?1 WHERE id = ?2", 3L, r3.getId());
        sql("UPDATE tech_error_channels SET team_id = ?1 WHERE id = ?2", 3L, ch3.getId());
        TechErrorReport before3 = fresh(r3.getId());
        incidentService.update(c.getId(), new HashMap<>(Map.of("title", "A'nın yeni başlığı", "tech_error_count", 5)), "olayci");
        assertNotWritten(r3.getId(), before3, ic, "POS");
        assertThat(linkState(r3.getId(), ALL)).containsEntry("state", S_HIDDEN);
        IncidentRecord cc = incidents.findById(c.getId()).orElseThrow();
        cc.setUpdatedAt("2026-11-14T00:00:00");
        incidents.save(cc);
        assertThat(sync.catchUp(r3.getId())).isZero();
        assertThatThrownBy(() -> sync.refresh(r3.getId(), fresh(r3.getId()).getVersion(), "issues", ic, null, 9L, ALL))
                .isInstanceOf(CodedNotFoundException.class);
        assertNotWritten(r3.getId(), before3, ic, "POS");
    }

    @Test
    @DisplayName("yetişme dondurulmuş raporda (frozen_stale bağ) KİLİT ALMAZ ve yazmaz — satırı tutan işlem varken beklemeden döner")
    void catchUpOnFrozenReportTakesNoLock() throws Exception {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme");
        String iid = importOne(r, a);
        sql("UPDATE tech_error_reports SET last_sent_at = ?1, sent_rev = content_rev WHERE id = ?2",
                Instant.parse("2026-12-04T09:00:00Z"), r.getId());
        IncidentRecord e = incidents.findById(a.getId()).orElseThrow();
        e.setTitle("Değişti");
        e.setUpdatedAt("2026-11-12T00:00:00");
        incidents.save(e);
        assertThat(linkState(r.getId(), ALL)).containsEntry("state", S_FROZEN_STALE);
        TechErrorReport before = fresh(r.getId());
        var tx = new TransactionTemplate(txManager);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var holder = pool.submit(() -> tx.execute(st -> {
                reports.lockRow(r.getId());
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long t0 = System.nanoTime();
            assertThat(sync.catchUp(r.getId())).isZero();
            assertThat(sync.catchUp(fresh(r.getId()))).isZero();
            assertThat(sync.catchUpQuietly(fresh(r.getId()))).isZero();
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(ms).as("donuk raporda yetişme satır kilidini beklemedi").isLessThan(1000L);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(fresh(r.getId()).getVersion()).isEqualTo(before.getVersion());
        assertThat(topic(r.getId(), iid).topic()).isEqualTo("Ödeme");
    }

    @Test
    @DisplayName("onay akışı kaldırılmış kanal (yönetici alıcısı yok): bekleyen onay raporu DONDURMAZ, olay senkronu yazar; alıcı varken donuk")
    void removedApprovalFlowDoesNotFreeze() {
        TechErrorChannel ch = channel(1, "Onaylı");
        TechErrorReport r = service.createReport(ch, 2026, 11, 7L);
        IncidentRecord a = simple(1, "Ödeme");
        String iid = importOne(r, a);
        sql("UPDATE tech_error_reports SET approval_state = 'PENDING', approval_rev = content_rev WHERE id = ?1", r.getId());
        incidentService.update(a.getId(), new HashMap<>(Map.of("title", "Akışsız")), "olayci");
        assertThat(topic(r.getId(), iid).topic()).isEqualTo("Akışsız");          // akış yok → donuk değil → yazıldı

        sql("UPDATE tech_error_reports SET approval_rev = content_rev WHERE id = ?1", r.getId());
        sql("UPDATE tech_error_channels SET executive_emails = ?1 WHERE id = ?2", "yonetici@example.com", ch.getId());
        TechErrorReport before = fresh(r.getId());
        incidentService.update(a.getId(), new HashMap<>(Map.of("title", "Akışlı")), "olayci");
        assertNotWritten(r.getId(), before, iid, "Akışsız");
        IncidentRecord e = incidents.findById(a.getId()).orElseThrow();
        e.setUpdatedAt("2026-11-30T00:00:00");                                     // saniye çözünürlüğünden bağımsız bayat
        incidents.save(e);
        assertThat(linkState(r.getId(), ALL)).containsEntry("state", S_FROZEN_STALE);
        assertThat(sync.catchUp(r.getId())).isZero();
    }

    @Test
    @DisplayName("aynı saniyedeki düzenlemenin olay senkronu KAYBOLDU (updated_at aynı): yetişme kaynak izinden yakalar ve yazar; izi"
            + " olmayan eski bağ yalnız updated_at'e bakar; kapanışta koşmayan senkronlar sayılır")
    void lostSameSecondEditIsCaughtUp() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme");
        String iid = importOne(r, a);
        assertThat(link(r.getId()).getSourceFp())
                .isEqualTo(sourceFp(TechErrorIncidentMapper.Source.of(incidents.findById(a.getId()).orElseThrow())));
        IncidentRecord e = incidents.findById(a.getId()).orElseThrow();
        e.setTechErrorCount(4321L);                                    // updated_at AYNI; olay yayımlanmadı (kayıp senkron)
        incidents.save(e);
        assertThat(linkState(r.getId(), ALL)).containsEntry("state", S_OK);   // ekran ucuz işarete (updated_at) bakar
        assertThat(sync.catchUp(r.getId())).isEqualTo(1);
        assertThat(topic(r.getId(), iid).errors().longValue()).isEqualTo(4321L);
        assertThat(sync.catchUp(r.getId())).isZero();                  // iz güncellendi

        // Eski bağ (source_fp null): yalnız updated_at — önceki davranış
        sql("UPDATE tech_error_report_incidents SET source_fp = NULL WHERE report_id = ?1", r.getId());
        e = incidents.findById(a.getId()).orElseThrow();
        e.setTechErrorCount(5000L);
        incidents.save(e);
        assertThat(sync.catchUp(r.getId())).isZero();
        sync.onIncidentChanged(a.getId());                             // sonraki senkron izi yazar
        assertThat(topic(r.getId(), iid).errors().longValue()).isEqualTo(5000L);
        assertThat(link(r.getId()).getSourceFp()).isNotNull();

        // Kapanış: kapanış başladıktan sonra çıkan iş koşmaz ve SAYILIR; kapanıştan sonraki olay kuyruğa hiç girmez
        TechErrorIncidentSyncService other = new TechErrorIncidentSyncService(service, reports, links, hooks, incidents, txManager);
        List<Runnable> queued = new ArrayList<>();
        other.useExecutor(queued::add);
        other.onIncidentRecordChanged(new com.sitemonitor.service.IncidentRecordChangedEvent(a.getId()));
        other.onContextClosed();
        other.onIncidentRecordChanged(new com.sitemonitor.service.IncidentRecordChangedEvent(a.getId()));
        assertThat(queued).hasSize(1);
        queued.forEach(Runnable::run);
        assertThat(other.stopAndCountDropped()).isEqualTo(1);
        assertThat(other.stopAndCountDropped()).isZero();
    }

    @Test
    @DisplayName("kullanıldığı raporlar: önce okunabilen raporlar süzülür — okunamayanın içeriği (şablon / değer metni) hiç okunmaz; rapor"
            + " varlığı yüklenmez (metinsiz izdüşüm)")
    void usageReadsOnlyReadableReports() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme");
        importOne(r, a);
        IncidentRecord inc = incidents.findById(a.getId()).orElseThrow();
        org.hibernate.stat.Statistics st = statistics();
        try {
            assertThat(sync.usage(inc, team -> false, team -> "x")).isEmpty();
            assertThat(st.getEntityStatistics(TechErrorReport.class.getName()).getLoadCount()).isZero();
            assertThat(Arrays.stream(st.getQueries())).noneMatch(q -> q.contains("valuesJson"));
            st.clear();
            assertThat(sync.usage(inc, team -> true, team -> "Dijital")).singleElement()
                    .satisfies(m -> assertThat(m).containsEntry("topic", "Ödeme").containsEntry("state", S_OK)
                            .containsEntry("status", "draft").containsEntry("channel_name", "Mobil 1"));
            assertThat(st.getEntityStatistics(TechErrorReport.class.getName()).getLoadCount()).isZero();
            assertThat(Arrays.stream(st.getQueries())).anyMatch(q -> q.contains("valuesJson"));
        } finally {
            st.setStatisticsEnabled(false);
        }
    }

    @Test
    @DisplayName("içe almanın bütünlük hatası yalnız bağ tablosunun benzersiz kısıtı ise 'zaten alındı' sayılır; başka bütünlük hatası değil")
    void linkUniqueViolationRecognised() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "A");
        String iid = importOne(r, a);
        assertThatThrownBy(() -> links.saveAndFlush(linkRow(r, "issues", "başka", a.getId())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(TechErrorIncidentImportService.isLinkUniqueViolation(e)).isTrue());
        assertThatThrownBy(() -> links.saveAndFlush(linkRow(r, "issues", iid, a.getId() + 1_000_000)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(TechErrorIncidentImportService.isLinkUniqueViolation(e)).isTrue());
        assertThatThrownBy(() -> links.saveAndFlush(linkRow(r, "x".repeat(41), "k", a.getId() + 2_000_000)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> assertThat(TechErrorIncidentImportService.isLinkUniqueViolation(e)).isFalse());
        assertThat(TechErrorIncidentImportService.isLinkUniqueViolation(new DataIntegrityViolationException("x", new RuntimeException(
                "ERROR: duplicate key value violates unique constraint \"uk_teri_report_incident\"")))).isTrue();
        assertThat(TechErrorIncidentImportService.isLinkUniqueViolation(new DataIntegrityViolationException("x", new RuntimeException(
                "ERROR: duplicate key value violates unique constraint \"uk_tech_error_reports_period\"")))).isFalse();
        assertThat(TechErrorIncidentImportService.isLinkUniqueViolation(new DataIntegrityViolationException("x",
                new org.hibernate.exception.ConstraintViolationException("dup", new java.sql.SQLException("23505"),
                        "UK_TERI_REPORT_TOPIC")))).isTrue();
        assertThat(TechErrorIncidentImportService.isLinkUniqueViolation(
                new DataIntegrityViolationException("value too long for type character varying(40)"))).isFalse();
    }

    @Test
    @DisplayName("görselli içe alma: okunan bayt içe alma başına sınırlı (aşacak görsel okunmadan 'total'); baytlar yönetilen varlık olarak"
            + " yüklenmez (reddedilen görsel kalıcılık bağlamında kalmaz)")
    void imageReadCapAndNoManagedImageEntities() throws Exception {
        TechErrorReport r = report(1);
        IncidentRecord c = simple(1, "Görselli");
        byte[] ok = TechErrorImageValidatorTest.png(16, 16);
        byte[] junk = { 1, 2, 3, 4, 5, 6, 7, 8 };
        IncidentImage bad = incidentImage(c.getId(), "Bozuk", "image/png", junk, junk.length);
        IncidentImage v1 = incidentImage(c.getId(), "Bir", "image/png", ok, ok.length);
        IncidentImage v2 = incidentImage(c.getId(), "İki", "image/png", ok, ok.length);
        c.setRcaSummary("![b](/api/incidents/images/" + bad.getId() + ")\n\n![1](/api/incidents/images/" + v1.getId()
                + ")\n\n![2](/api/incidents/images/" + v2.getId() + ")");
        incidents.save(c);
        long prev = importer.readCapBytes;
        importer.readCapBytes = junk.length + ok.length;                // bozuk (okundu, reddedildi) + bir görsel sığar
        org.hibernate.stat.Statistics st = statistics();
        try {
            var plan = importer.plan(incidents.findById(c.getId()).orElseThrow(), null, 0, true,
                    new TechErrorIncidentImportService.ReadBudget(importer.readCapBytes));
            assertThat(plan.copies()).extracting(TechErrorIncidentImportService.Copy::imageId).containsExactly(v1.getId());
            assertThat(plan.skipped()).extracting(TechErrorIncidentImportService.Skip::reason).containsExactly("format", "total");
            st.clear();
            var out = importer.importIncidents(r.getId(), r.getVersion(), new Request("issues", List.of(c.getId()), null, true),
                    7L, "x", ALL);
            assertThat(out.imagesAdded()).isEqualTo(1);
            assertThat(out.imported().get(0).get("notes")).isEqualTo(List.of(N_IMAGE_SKIPPED));
            assertThat(st.getEntityStatistics(IncidentImage.class.getName()).getLoadCount()).as("görsel varlığı yüklenmedi").isZero();
        } finally {
            importer.readCapBytes = prev;
            st.setStatisticsEnabled(false);
        }
        assertThat(TechErrorIncidentImportService.MAX_IMAGE_READ_BYTES).isEqualTo(2L * TechErrorReportService.MAX_REPORT_IMAGE_BYTES);
    }

    @Test
    @DisplayName("aday ayı sınırı: 16 ve 19 karakterlik zaman TAM sınırda eşlemeyle aynı aya düşer (ay başı içeride, sonraki ay başı dışarıda)")
    void candidateMonthBoundaryBothLengths() {
        TechErrorReport r = report(1);
        IncidentRecord s16 = incident(1, "Ay başı 16", "2026-10-31T21:00", null, 1L, null, null, null, "x");
        IncidentRecord s19 = incident(1, "Ay başı 19", "2026-10-31T21:00:00", null, 1L, null, null, null, "x");
        IncidentRecord e16 = incident(1, "Sonraki ay 16", "2026-11-30T21:00", null, 1L, null, null, null, "x");
        IncidentRecord e19 = incident(1, "Sonraki ay 19", "2026-11-30T21:00:00", null, 1L, null, null, null, "x");
        IncidentRecord p16 = incident(1, "Önceki dakika", "2026-10-31T20:59", null, 1L, null, null, null, "x");
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>)
                importer.candidates(fresh(r.getId()), null, 0, 50, false, ALL).get("items");
        assertThat(items).extracting(m -> m.get("id")).contains(s16.getId(), s19.getId())
                .doesNotContain(e16.getId(), e19.getId(), p16.getId());
        @SuppressWarnings("unchecked") List<Map<String, Object>> overlap = (List<Map<String, Object>>)
                importer.candidates(fresh(r.getId()), null, 0, 50, true, ALL).get("items");
        assertThat(overlap).extracting(m -> m.get("id")).contains(s16.getId(), s19.getId(), p16.getId())
                .doesNotContain(e16.getId(), e19.getId());
        Template t = template(fresh(r.getId()));
        java.time.YearMonth nov = java.time.YearMonth.of(2026, 11);
        for (IncidentRecord in : List.of(s16, s19)) {
            assertThat(TechErrorIncidentMapper.map(TechErrorIncidentMapper.Source.of(in), t.section("issues"), t, nov, null, "i-x")
                    .issue().date()).as(in.getOccurredAt()).isEqualTo("2026-11-01");
        }
        for (IncidentRecord in : List.of(e16, e19)) {
            assertThat(TechErrorIncidentMapper.map(TechErrorIncidentMapper.Source.of(in), t.section("issues"), t, nov, null, "i-x")
                    .dateOutsideMonth()).as(in.getOccurredAt()).isTrue();
        }
    }

    @Test
    @DisplayName("kendiliğinden senkron 'son düzenleyen'e dokunmaz (updated_at / updated_by_id kişinin son düzenlemesi); içerik sayacı ve"
            + " content_changed_at ilerler; açık yenileme yapan kişiyi yazar")
    void autoSyncKeepsHumanLastEditor() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme");
        String iid = importOne(r, a);                                  // yapan 7, saat 2026-12-05T09:00Z
        TechErrorReport imported = fresh(r.getId());
        Instant later = Instant.parse("2026-12-06T10:00:00Z");
        service.useClock(Clock.fixed(later, ZoneOffset.UTC));
        incidentService.update(a.getId(), new HashMap<>(Map.of("title", "Yeni")), "olayci");
        TechErrorReport after = fresh(r.getId());
        assertThat(topic(r.getId(), iid).topic()).isEqualTo("Yeni");
        assertThat(after.getUpdatedAt()).isEqualTo(imported.getUpdatedAt());
        assertThat(after.getUpdatedById()).isEqualTo(7L);
        assertThat(after.getContentChangedAt()).isEqualTo(later);
        assertThat(after.getContentRev()).isGreaterThan(imported.getContentRev());

        userEdits(r.getId(), iid, "errors", 99);                       // kişi 8 düzenledi (sabitlendi)
        Instant evenLater = Instant.parse("2026-12-07T10:00:00Z");
        service.useClock(Clock.fixed(evenLater, ZoneOffset.UTC));
        sync.refresh(r.getId(), fresh(r.getId()).getVersion(), "issues", iid, List.of(G_ERRORS), 9L, ALL);
        TechErrorReport refreshed = fresh(r.getId());
        assertThat(refreshed.getUpdatedById()).isEqualTo(9L);
        assertThat(refreshed.getUpdatedAt()).isEqualTo(evenLater);
    }

    @Test
    @DisplayName("senkron rapor içeriği sınırını (256 KB) aşacaksa önce açıklama geri alınır (value_refused), diğer gruplar yazılır")
    @SuppressWarnings("unchecked")
    void tooLargeSyncRevertsDescription() {
        TechErrorReport r = report(1);
        IncidentRecord a = simple(1, "Ödeme");
        String iid = importOne(r, a);
        String originalDescription = topic(r.getId(), iid).description();
        int max = TechErrorValues.MAX_JSON_CHARS;
        int base = fresh(r.getId()).getValuesJson().length();
        int fillers = (max - 12_000 - base) / 9_200;
        fill(r.getId(), fillers, 9_000, 0);
        int now = fresh(r.getId()).getValuesJson().length();
        int last = Math.min(9_500, max - 3_000 - now - 300);
        if (last > 0) fill(r.getId(), 1, last, fillers);
        int size = fresh(r.getId()).getValuesJson().length();
        assertThat(size).as("sınıra yakın ama altında").isBetween(max - 9_000, max - 1_000);

        Map<String, Object> body = new HashMap<>();
        body.put("rca_summary", "kelime ".repeat(3_000));
        body.put("tech_error_count", 4242);
        incidentService.update(a.getId(), body, "olayci");
        Issue i = topic(r.getId(), iid);
        assertThat(i.errors().longValue()).isEqualTo(4242L);           // küçük grup yazıldı
        assertThat(i.description()).isEqualTo(originalDescription);    // açıklama geri alındı
        assertThat(fresh(r.getId()).getValuesJson().length()).isLessThanOrEqualTo(max);
        assertThat(linkState(r.getId(), ALL)).satisfies(m -> assertThat(notesOf(m)).contains(N_VALUE_REFUSED));
        assertThat(link(r.getId()).getPinned()).isNull();              // reddedilen grup sabitlenmez
    }

    @SuppressWarnings("unchecked")
    private void fill(Long reportId, int count, int descriptionChars, int startIndex) {
        TechErrorReport x = fresh(reportId);
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorValues.toMap(values(x)));
        Map<String, Object> issues = new LinkedHashMap<>((Map<String, Object>) raw.get("issues"));
        List<Object> list = new ArrayList<>((List<Object>) issues.get("issues"));
        for (int k = 0; k < count; k++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "f" + (startIndex + k));
            m.put("topic", "Doldurma " + (startIndex + k));
            m.put("title_auto", true);
            m.put("description", "a".repeat(descriptionChars));
            list.add(m);
        }
        issues.put("issues", list);
        raw.put("issues", issues);
        service.saveValues(reportId, x.getVersion(), raw, 8L);
    }

    @Test
    @DisplayName("şablon yenilemede kancasız (ör. eski pod) değişmiş grup SABİTLENİR, sonraki senkron onu yazmaz; dokunulmamış grup izlenir")
    @SuppressWarnings("unchecked")
    void templateRefreshPinsGroupChangedWithoutHooks() {
        TechErrorChannel ch = channel(1, "Mobil");
        TechErrorReport r = service.createReport(ch, 2026, 11, 7L);
        IncidentRecord a = simple(1, "Ödeme");
        String iid = importOne(r, a);
        // Kullanıcının değişikliği kancasız yazıldı: hata adedi 77 (sabitleme tespiti çalışmadı)
        Map<String, Object> raw = new LinkedHashMap<>(TechErrorValues.toMap(values(fresh(r.getId()))));
        Map<String, Object> issues = new LinkedHashMap<>((Map<String, Object>) raw.get("issues"));
        List<Object> list = new ArrayList<>();
        for (Object o : (List<Object>) issues.get("issues")) {
            Map<String, Object> m = new LinkedHashMap<>((Map<String, Object>) o);
            if (iid.equals(m.get("id"))) m.put("errors", 77);
            list.add(m);
        }
        issues.put("issues", list);
        raw.put("issues", issues);
        sql("UPDATE tech_error_reports SET values_json = ?1 WHERE id = ?2", TechErrorTemplate.JSON.writeValueAsString(raw), r.getId());
        assertThat(link(r.getId()).getPinned()).isNull();

        Map<String, Object> rawT = TechErrorTemplate.toMap(TechErrorTemplate.defaults("tr"));
        List<Map<String, Object>> secs = new ArrayList<>((List<Map<String, Object>>) rawT.get("sections"));
        secs.replaceAll(x -> {
            if (!"issues".equals(x.get("kind"))) return x;
            Map<String, Object> copy = new LinkedHashMap<>(x);
            copy.put("title", "Anormal artışlar (yeni ad)");
            return copy;
        });
        rawT.put("sections", secs);
        service.saveTemplate(ch.getId(), 1, TechErrorTemplate.normalize(rawT), 7L);
        service.refreshTemplate(r.getId(), fresh(r.getId()).getVersion(), 7L);
        assertThat(link(r.getId()).getPinned()).isEqualTo(G_ERRORS);
        assertThat(topic(r.getId(), iid).errors().longValue()).isEqualTo(77L);

        Map<String, Object> body = new HashMap<>();
        body.put("title", "Yeni başlık");
        body.put("tech_error_count", 5555);
        incidentService.update(a.getId(), body, "olayci");
        Issue i = topic(r.getId(), iid);
        assertThat(i.errors().longValue()).isEqualTo(77L);
        assertThat(i.topic()).isEqualTo("Yeni başlık");
    }
}
