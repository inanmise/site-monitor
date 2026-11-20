package com.sitemonitor.repository;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorReportIncident;
import com.sitemonitor.service.techerror.TechErrorIncidentImportService;
import com.sitemonitor.service.techerror.TechErrorIncidentSyncService;
import com.sitemonitor.service.techerror.TechErrorReportService;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import com.sitemonitor.service.techerror.TechErrorValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Olay kaydı → anomali konusu bağ tablosu (2026-11-20) GERÇEK PostgreSQL'de: iki benzersizlik kısıtı (rapor + konu, rapor +
 * olay), aday sorgusunun mantıksal parametresi ve izdüşümler, ve içe alma ile eşzamanlı değer kaydının satır kilidinde
 * sıralanması (biri yazar, diğeri 409; kilitlenme yok, bağ ile konu tutarlı).
 */
@PostgresIntegration
class TechErrorIncidentImportPostgresTest {

    private static final java.util.function.BiPredicate<Long, Long> ALL = (a, b) -> true;

    private static IncidentRecord incident(IncidentRecordRepository repo, long team, String title) {
        IncidentRecord e = new IncidentRecord();
        e.setTitle(title);
        e.setOccurredAt("2025-06-05T07:00:00");
        e.setResolvedAt("2025-06-05T08:30:00");
        e.setSeverity("HIGH");
        e.setStatus("RESOLVED");
        e.setCategory("OTHER");
        e.setTeamId(team);
        e.setCreatedByTeamId(team);
        e.setTechErrorCount(12L);
        e.setRcaSummary("Kök **neden**");
        e.setCreatedAt("2025-06-10T08:00:00");
        e.setUpdatedAt("2025-06-10T08:00:00");
        return repo.saveAndFlush(e);
    }

    @Test
    @DisplayName("iki benzersizlik kısıtı, aday sorgusu (overlap parametresi), izdüşümler ve bağ silme sorguları PostgreSQL'de doğru")
    void constraintsAndQueries() {
        var service = PostgresIt.app().bean(TechErrorReportService.class);
        var importer = PostgresIt.app().bean(TechErrorIncidentImportService.class);
        var links = PostgresIt.app().bean(TechErrorReportIncidentRepository.class);
        var incidents = PostgresIt.app().bean(IncidentRecordRepository.class);
        long team = 765_430L + (long) (Math.random() * 1000);
        String tag = "teri-it-" + UUID.randomUUID().toString().substring(0, 8);
        TechErrorChannel ch = service.createChannel(team, tag + " Mobil", null, TechErrorTemplate.defaults("tr"), null);
        List<Long> incIds = new ArrayList<>();
        try {
            TechErrorReport r = service.createReport(ch, 2025, 6, null);
            IncidentRecord a = incident(incidents, team, tag + " A");
            incIds.add(a.getId());
            var out = importer.importIncidents(r.getId(), r.getVersion(),
                    new TechErrorIncidentImportService.Request("issues", List.of(a.getId()), null, false), null, "it", ALL);
            String iid = (String) out.imported().get(0).get("issue_id");

            List<String> idx = PostgresIt.app().jdbc().queryForList(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'tech_error_report_incidents'", String.class);
            assertThat(idx).contains("uk_teri_report_topic", "uk_teri_report_incident", "idx_teri_incident", "idx_teri_channel");

            TechErrorReportIncident sameIncident = row(r, "başka", a.getId());
            assertThatThrownBy(() -> links.saveAndFlush(sameIncident)).isInstanceOf(DataIntegrityViolationException.class);
            TechErrorReportIncident sameTopic = row(r, iid, a.getId() + 1_000_000);
            assertThatThrownBy(() -> links.saveAndFlush(sameTopic)).isInstanceOf(DataIntegrityViolationException.class);

            String from = "2025-05-31T21:00:00", to = "2025-06-30T21:00:00";
            assertThat(incidents.findImportCandidates(team, from, to, false, PageRequest.of(0, 10)))
                    .extracting(IncidentRecordRepository.ImportCandidate::getId).containsExactly(a.getId());
            assertThat(incidents.findImportCandidates(team, from, to, true, PageRequest.of(0, 10))).hasSize(1);
            assertThat(incidents.findLinkStates(List.of(a.getId()))).singleElement()
                    .satisfies(s -> assertThat(s.getUpdatedAt()).isEqualTo("2025-06-10T08:00:00"));
            assertThat(links.findByIncident(a.getId(), PageRequest.of(0, 5))).hasSize(1);
            assertThat(links.deleteForReports(List.of(r.getId()))).isEqualTo(1);
            assertThat(links.findByReportIdOrderByIdAsc(r.getId())).isEmpty();
        } finally {
            service.deleteChannel(ch.getId());
            incidents.deleteAllById(incIds);
        }
    }

    private static TechErrorReportIncident row(TechErrorReport r, String issueId, long incidentId) {
        TechErrorReportIncident l = new TechErrorReportIncident();
        l.setReportId(r.getId());
        l.setChannelId(r.getChannelId());
        l.setSectionId("issues");
        l.setIssueId(issueId);
        l.setIncidentId(incidentId);
        return l;
    }

    @Test
    @DisplayName("içe alma ile eşzamanlı değer kaydı (aynı okunan sürüm): satır kilidinde sıralanır — biri yazar, diğeri 409; bağ"
            + " ve konu tutarlı; olay senkronu ayrı işlemde")
    void importRacesValuesSave() throws Exception {
        var service = PostgresIt.app().bean(TechErrorReportService.class);
        var importer = PostgresIt.app().bean(TechErrorIncidentImportService.class);
        var sync = PostgresIt.app().bean(TechErrorIncidentSyncService.class);
        var links = PostgresIt.app().bean(TechErrorReportIncidentRepository.class);
        var reports = PostgresIt.app().bean(TechErrorReportRepository.class);
        var incidents = PostgresIt.app().bean(IncidentRecordRepository.class);
        long team = 765_430L + (long) (Math.random() * 1000);
        String tag = "teri-it-" + UUID.randomUUID().toString().substring(0, 8);
        TechErrorChannel ch = service.createChannel(team, tag + " POS", null, TechErrorTemplate.defaults("tr"), null);
        List<Long> incIds = new ArrayList<>();
        try {
            for (int round = 0; round < 5; round++) {
                TechErrorReport r = service.createReport(ch, 2025, 1 + round, null);
                IncidentRecord a = incident(incidents, team, tag + " olay " + round);
                incIds.add(a.getId());
                long v = r.getVersion();
                var pool = Executors.newFixedThreadPool(2);
                var start = new CountDownLatch(1);
                try {
                    Future<String> imp = pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            importer.importIncidents(r.getId(), v, new TechErrorIncidentImportService.Request("issues",
                                    List.of(a.getId()), null, false), null, "it", ALL);
                            return "ok";
                        } catch (OptimisticLockingFailureException e) {
                            return "409";
                        }
                    });
                    Future<String> save = pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            service.saveValues(r.getId(), v, Map.of("fields", Map.of("error-count", 5000)), null);
                            return "ok";
                        } catch (OptimisticLockingFailureException e) {
                            return "409";
                        }
                    });
                    start.countDown();
                    List<String> got = List.of(imp.get(60, TimeUnit.SECONDS), save.get(60, TimeUnit.SECONDS));
                    assertThat(got).as("tur " + round).containsExactlyInAnyOrder("ok", "409");
                    TechErrorReport after = reports.findById(r.getId()).orElseThrow();
                    var values = TechErrorValues.fromStored(after.getValuesJson(), TechErrorTemplate.fromStored(after.getTemplateJson()));
                    int topics = values.issues().getOrDefault("issues", List.of()).size();
                    assertThat(links.findByReportIdOrderByIdAsc(r.getId())).hasSize(topics);
                } finally {
                    pool.shutdownNow();
                }
                // Olay değişti → senkron kendi işleminde yazar (bağ varsa)
                IncidentRecord e = incidents.findById(a.getId()).orElseThrow();
                e.setTechErrorCount(99L);
                e.setUpdatedAt("2025-06-11T08:00:00");
                incidents.saveAndFlush(e);
                int written = sync.catchUp(r.getId());
                assertThat(written).isEqualTo(links.findByReportIdOrderByIdAsc(r.getId()).isEmpty() ? 0 : 1);
            }
        } finally {
            service.deleteChannel(ch.getId());
            incidents.deleteAllById(incIds);
        }
    }

    @Test
    @DisplayName("yetişme ve olay senkronu (AUTO) ile eşzamanlı değer kaydı: satır kilidinde sıralanır — kilitlenme yok; kayıt ya yazar ya"
            + " 409, senkron hiç hata vermez ve sonunda konu olayın değerini taşır (inceleme 2026-11-20)")
    void syncRacesValuesSave() throws Exception {
        var service = PostgresIt.app().bean(TechErrorReportService.class);
        var importer = PostgresIt.app().bean(TechErrorIncidentImportService.class);
        var sync = PostgresIt.app().bean(TechErrorIncidentSyncService.class);
        var reports = PostgresIt.app().bean(TechErrorReportRepository.class);
        var incidents = PostgresIt.app().bean(IncidentRecordRepository.class);
        long team = 765_430L + (long) (Math.random() * 1000);
        String tag = "teri-it-" + UUID.randomUUID().toString().substring(0, 8);
        TechErrorChannel ch = service.createChannel(team, tag + " Kart", null, TechErrorTemplate.defaults("tr"), null);
        List<Long> incIds = new ArrayList<>();
        try {
            TechErrorReport r = service.createReport(ch, 2025, 6, null);
            IncidentRecord a = incident(incidents, team, tag + " olay");
            incIds.add(a.getId());
            var out = importer.importIncidents(r.getId(), r.getVersion(),
                    new TechErrorIncidentImportService.Request("issues", List.of(a.getId()), null, false), null, "it", ALL);
            String iid = (String) out.imported().get(0).get("issue_id");
            for (int round = 0; round < 6; round++) {
                IncidentRecord e = incidents.findById(a.getId()).orElseThrow();
                e.setTechErrorCount(100L + round);
                e.setUpdatedAt(String.format("2025-07-%02dT08:00:00", round + 1));
                incidents.saveAndFlush(e);
                TechErrorReport before = reports.findById(r.getId()).orElseThrow();
                long v = before.getVersion();
                // Kayıt DEĞERLERİN TAMAMINI yazar: okunan değerler (konu dahil) + değişen alan — konu silinmesin
                Map<String, Object> body = new LinkedHashMap<>(TechErrorValues.toMap(TechErrorValues.fromStored(
                        before.getValuesJson(), TechErrorTemplate.fromStored(before.getTemplateJson()))));
                Map<String, Object> fields = new LinkedHashMap<>();
                if (body.get("fields") instanceof Map<?, ?> f) f.forEach((k, val) -> fields.put(String.valueOf(k), val));
                fields.put("error-count", 5000 + round);
                body.put("fields", fields);
                boolean auto = round % 2 == 1;
                var pool = Executors.newFixedThreadPool(2);
                var start = new CountDownLatch(1);
                try {
                    Future<Integer> s = pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        if (auto) {
                            sync.onIncidentChanged(a.getId());
                            return -1;
                        }
                        return sync.catchUp(r.getId());
                    });
                    Future<String> save = pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            service.saveValues(r.getId(), v, body, null);
                            return "ok";
                        } catch (OptimisticLockingFailureException ex) {
                            return "409";
                        }
                    });
                    start.countDown();
                    Integer written = s.get(60, TimeUnit.SECONDS);
                    assertThat(written).as("tur " + round).isIn(-1, 0, 1);
                    assertThat(save.get(60, TimeUnit.SECONDS)).as("tur " + round).isIn("ok", "409");
                } finally {
                    pool.shutdownNow();
                }
                TechErrorReport after = reports.findById(r.getId()).orElseThrow();
                var values = TechErrorValues.fromStored(after.getValuesJson(), TechErrorTemplate.fromStored(after.getTemplateJson()));
                var topic = values.issues().get("issues").stream().filter(i -> i.id().equals(iid)).findFirst().orElseThrow();
                assertThat(topic.errors().longValue()).as("tur " + round).isEqualTo(100L + round);
            }
        } finally {
            service.deleteChannel(ch.getId());
            incidents.deleteAllById(incIds);
        }
    }
}
