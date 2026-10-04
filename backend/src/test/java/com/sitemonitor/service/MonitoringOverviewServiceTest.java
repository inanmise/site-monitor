package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.HttpCheck;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.ScriptedCheck;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * İzleme Panosu özeti (2026-09-30): tür başına sayılar, durum türetimi (düşük / eski / duraklatılmış / bilinmiyor /
 * silinmiş), pencere koşum sayıları, açık alarmların hedef anahtarıyla izlemeye bağlanması ve görüş kapsamı.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringOverviewServiceTest {

    @Mock HttpMonitorRepository httpMonitorRepo; @Mock PingMonitorRepository pingMonitorRepo; @Mock PortMonitorRepository portMonitorRepo;
    @Mock DnsMonitorRepository dnsMonitorRepo; @Mock KeywordMonitorRepository keywordMonitorRepo; @Mock PageMonitorRepository pageMonitorRepo;
    @Mock PageSpeedMonitorRepository pageSpeedMonitorRepo; @Mock ScriptedMonitorRepository scriptedMonitorRepo; @Mock DomainMonitorRepository domainMonitorRepo;
    @Mock HttpCheckRepository httpCheckRepo; @Mock PingCheckRepository pingCheckRepo; @Mock PortCheckRepository portCheckRepo;
    @Mock DnsRecordRepository dnsRecordRepo; @Mock KeywordResultRepository keywordResultRepo; @Mock PageCheckRepository pageCheckRepo;
    @Mock PageSpeedCheckRepository pageSpeedCheckRepo; @Mock ScriptedCheckRepository scriptedCheckRepo; @Mock DomainCheckRepository domainCheckRepo;
    @Mock AlertEventRepository alertEventRepo; @Mock TeamRepository teamRepo;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static String ago(long minutes) { return ISO.format(Instant.now().minus(minutes, ChronoUnit.MINUTES)); }

    private MonitoringOverviewService svc;

    @BeforeEach
    void setUp() {
        svc = new MonitoringOverviewService(httpMonitorRepo, pingMonitorRepo, portMonitorRepo, dnsMonitorRepo, keywordMonitorRepo,
                pageMonitorRepo, pageSpeedMonitorRepo, scriptedMonitorRepo, domainMonitorRepo,
                httpCheckRepo, pingCheckRepo, portCheckRepo, dnsRecordRepo, keywordResultRepo, pageCheckRepo, pageSpeedCheckRepo,
                scriptedCheckRepo, domainCheckRepo, alertEventRepo, teamRepo);
        Team t = new Team(); t.setId(14L); t.setName("SY");
        when(teamRepo.findAll()).thenReturn(List.of(t));
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of());
        when(alertEventRepo.countRecoveredSinceByTypeAndTeam(anyString())).thenReturn(List.of());
    }

    private static HttpMonitor http(long id, String url, Long team, boolean active, int interval) {
        HttpMonitor m = new HttpMonitor(); m.setId(id); m.setName("h" + id); m.setUrl(url); m.setTeamId(team); m.setActive(active); m.setIntervalSeconds(interval); return m;
    }
    private static HttpCheck httpCheck(long monitorId, boolean ok, String at) {
        HttpCheck c = new HttpCheck(); c.setMonitorId(monitorId); c.setOk(ok); c.setCheckedAt(at); c.setResponseMs(120L); return c;
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("HTTP: aktif/duraklatılmış/düşük/eski/bilinmiyor türetilir; pencere sayımı ve açık alarm hedefe bağlanır; kapsam dışı takım görünmez")
    void httpSummaryAndRows() {
        when(httpMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(
                http(1, "https://up.example.com", 14L, true, 300),        // up, taze
                http(2, "https://down.example.com", 14L, true, 300),      // son kontrol başarısız + açık alarm
                http(3, "https://stale.example.com", 14L, true, 300),     // son kontrol 2 saat önce (3×300 sn aşıldı)
                http(4, "https://paused.example.com", 14L, false, 300),   // duraklatılmış
                http(5, "https://new.example.com", 14L, true, 300),       // hiç kontrol yok
                http(6, "https://other.example.com", 99L, true, 300)));   // kapsam dışı takım
        when(httpCheckRepo.findLatestPerMonitor()).thenReturn(List.of(
                httpCheck(1, true, ago(2)), httpCheck(2, false, ago(2)), httpCheck(3, true, ago(120)), httpCheck(4, true, ago(2)), httpCheck(6, true, ago(1))));
        when(httpCheckRepo.weeklyStatsByMonitor(anyCollection(), anyString(), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 10L, 10L, 100.0, 10L}, new Object[]{2L, 10L, 4L, 100.0, 10L}));
        AlertEvent open = new AlertEvent(); open.setDomain("https://down.example.com"); open.setAlertType("HTTP_DOWN"); open.setAlertLevel("CRITICAL"); open.setResolved(false);
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(open));
        // Pencerede kurtarılan alarmlar — gruplu sayım (tip, damgalı takım, adet): kapsam içi takım sayılır; kapsam dışı
        // takım, takımsız satır ve katalog dışı tip sayılmaz (eski tam-entity döngüsüyle aynı kural).
        when(alertEventRepo.countRecoveredSinceByTypeAndTeam(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"HTTP_DOWN", 14L, 1L}, new Object[]{"HTTP_DOWN", 99L, 4L},
                new Object[]{"HTTP_DOWN", null, 2L}, new Object[]{"BOGUS", 14L, 3L}));

        Map<String, Object> out = svc.build(team -> Long.valueOf(14L).equals(team), false, 24);

        List<Map<String, Object>> types = (List<Map<String, Object>>) out.get("types");
        Map<String, Object> http = types.stream().filter(x -> "http".equals(x.get("type"))).findFirst().orElseThrow();
        assertThat(http).containsEntry("total", 5L).containsEntry("active", 4L).containsEntry("paused", 1L)
                .containsEntry("down", 1L).containsEntry("stale", 1L).containsEntry("unknown", 1L)
                .containsEntry("checks_window", 20L).containsEntry("failed_window", 6L).containsEntry("success_rate_window", 70.0)
                .containsEntry("open_alerts", 1L).containsEntry("open_critical", 1L).containsEntry("resolved_window", 1L);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("monitors");
        assertThat(rows).extracting(r -> r.get("target")).doesNotContain("https://other.example.com");
        Map<String, Object> down = rows.stream().filter(r -> "https://down.example.com".equals(r.get("target"))).findFirst().orElseThrow();
        assertThat(down).containsEntry("status", "down").containsEntry("open_alert_level", "CRITICAL").containsEntry("team_name", "SY").containsEntry("failed_window", 6L);
        assertThat(rows.stream().filter(r -> "https://stale.example.com".equals(r.get("target"))).findFirst().orElseThrow()).containsEntry("status", "stale");
        assertThat(rows.stream().filter(r -> "https://paused.example.com".equals(r.get("target"))).findFirst().orElseThrow()).containsEntry("status", "paused");
        assertThat(rows.stream().filter(r -> "https://new.example.com".equals(r.get("target"))).findFirst().orElseThrow()).containsEntry("status", "unknown");
        Map<String, Object> totals = (Map<String, Object>) out.get("totals");
        assertThat(totals).containsEntry("total", 5L).containsEntry("down", 1L).containsEntry("open_alerts", 1L);
        assertThat(types).extracting(x -> x.get("type")).containsExactly("http", "ping", "port", "dns", "domain", "keyword", "page", "pagespeed", "scripted");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Envanteri PASİF host'un envanter türevi Port/DNS izlemesi 'duraklatılmış' + inventory_inactive (tarama atlar); standalone ve aktif envanterli satır gecikmiş kalır")
    void inventoryInactivePortIsPausedNotStale() {
        com.sitemonitor.repository.CertificateInventoryRepository inventoryRepo =
                org.mockito.Mockito.mock(com.sitemonitor.repository.CertificateInventoryRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "inventoryRepo", inventoryRepo);
        // Tek sütunluk projeksiyon (2026-10-01): ham alan adı — tarama gibi BİREBİR karşılaştırılır (normalizasyon yok;
        // harf büyüklüğü farkı ayrı testte: inventoryInactive_exactMatchLikeSweep).
        when(inventoryRepo.findActiveDomains()).thenReturn(List.of("live.example.com"));
        PortMonitor gone = new PortMonitor(); gone.setId(1L); gone.setName("p-gone"); gone.setHost("gone.example.com"); gone.setPort(443); gone.setTeamId(14L); gone.setActive(true); gone.setStandalone(false); gone.setIntervalSeconds(300);
        PortMonitor alone = new PortMonitor(); alone.setId(2L); alone.setName("p-alone"); alone.setHost("gone.example.com"); alone.setPort(443); alone.setTeamId(14L); alone.setActive(true); alone.setStandalone(true); alone.setIntervalSeconds(300);
        PortMonitor live = new PortMonitor(); live.setId(3L); live.setName("p-live"); live.setHost("live.example.com"); live.setPort(443); live.setTeamId(14L); live.setActive(true); live.setStandalone(false); live.setIntervalSeconds(300);
        when(portMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(gone, alone, live));
        List<com.sitemonitor.model.PortCheck> checks = new java.util.ArrayList<>();
        for (long id : new long[]{1, 2, 3}) { com.sitemonitor.model.PortCheck c = new com.sitemonitor.model.PortCheck(); c.setMonitorId(id); c.setOpen(true); c.setCheckedAt(ago(600)); checks.add(c); }
        when(portCheckRepo.findLatestPerMonitor()).thenReturn(checks);
        com.sitemonitor.model.DnsMonitor dgone = new com.sitemonitor.model.DnsMonitor(); dgone.setId(5L); dgone.setName("d-gone"); dgone.setDomain("gone.example.com"); dgone.setTeamId(14L); dgone.setActive(true); dgone.setStandalone(false); dgone.setIntervalSeconds(300);
        when(dnsMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(dgone));

        Map<String, Object> out = svc.build(team -> true, true, 24);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("monitors");
        java.util.function.Function<String, Map<String, Object>> row = n -> rows.stream().filter(r -> n.equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(row.apply("p-gone")).containsEntry("status", "paused").containsEntry("inventory_inactive", true).containsEntry("active", false);
        assertThat(row.apply("p-alone")).containsEntry("status", "stale").containsEntry("inventory_inactive", false);
        assertThat(row.apply("p-live")).containsEntry("status", "stale").containsEntry("inventory_inactive", false);
        assertThat(row.apply("d-gone")).containsEntry("status", "paused").containsEntry("inventory_inactive", true);
        List<Map<String, Object>> types = (List<Map<String, Object>>) out.get("types");
        Map<String, Object> port = types.stream().filter(x -> "port".equals(x.get("type"))).findFirst().orElseThrow();
        assertThat(port).containsEntry("total", 3L).containsEntry("active", 2L).containsEntry("paused", 1L).containsEntry("stale", 2L);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Envanter eşleşmesi taramayla BİREBİR (2026-10-01 prod): envanterde yalnız harf büyüklüğü farklı host'un envanter-türevi satırı tarama gibi atlanır → duraklatılmış + inventory_inactive, gecikmiş DEĞİL")
    void inventoryInactive_exactMatchLikeSweep() {
        com.sitemonitor.repository.CertificateInventoryRepository inventoryRepo =
                org.mockito.Mockito.mock(com.sitemonitor.repository.CertificateInventoryRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "inventoryRepo", inventoryRepo);
        // SchedulerService: activeDomains.contains(m.getDomain()) — "OutboundIVR.example.com" ≠ "outboundivr.example.com"
        when(inventoryRepo.findActiveDomains()).thenReturn(List.of("OutboundIVR.example.com", "exact.example.com"));
        com.sitemonitor.model.DnsMonitor caseOnly = new com.sitemonitor.model.DnsMonitor(); caseOnly.setId(1L); caseOnly.setName("d-case"); caseOnly.setDomain("outboundivr.example.com");
        caseOnly.setTeamId(14L); caseOnly.setActive(true); caseOnly.setStandalone(false); caseOnly.setIntervalSeconds(300);
        com.sitemonitor.model.DnsMonitor exact = new com.sitemonitor.model.DnsMonitor(); exact.setId(2L); exact.setName("d-exact"); exact.setDomain("exact.example.com");
        exact.setTeamId(14L); exact.setActive(true); exact.setStandalone(false); exact.setIntervalSeconds(300);
        com.sitemonitor.model.DnsMonitor nullHost = new com.sitemonitor.model.DnsMonitor(); nullHost.setId(3L); nullHost.setName("d-null");
        nullHost.setTeamId(14L); nullHost.setActive(true); nullHost.setStandalone(false); nullHost.setIntervalSeconds(300);
        when(dnsMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(caseOnly, exact, nullHost));
        List<com.sitemonitor.model.DnsRecord> recs = new java.util.ArrayList<>();
        for (long id : new long[]{1, 2}) { com.sitemonitor.model.DnsRecord r = new com.sitemonitor.model.DnsRecord(); r.setMonitorId(id); r.setValue("1.2.3.4"); r.setCheckedAt(ago(id == 1 ? 70 * 24 * 60 : 2)); recs.add(r); }
        when(dnsRecordRepo.findLatestPerMonitor()).thenReturn(recs);

        Map<String, Object> out = svc.build(team -> true, true, 24);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("monitors");
        java.util.function.Function<String, Map<String, Object>> row = n -> rows.stream().filter(r -> n.equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(row.apply("d-case")).containsEntry("status", "paused").containsEntry("inventory_inactive", true).containsEntry("active", false);
        assertThat(row.apply("d-exact")).containsEntry("status", "up").containsEntry("inventory_inactive", false);
        assertThat(row.apply("d-null")).containsEntry("status", "paused").containsEntry("inventory_inactive", true);   // contains(null) → atlanır
        Map<String, Object> dns = ((List<Map<String, Object>>) out.get("types")).stream().filter(x -> "dns".equals(x.get("type"))).findFirst().orElseThrow();
        // Kart "aktif" = taramanın gerçekten kontrol ettiği; gecikmiş SAYILMAZ; envanter-dışı paused'un alt kümesi
        assertThat(dns).containsEntry("total", 3L).containsEntry("active", 1L).containsEntry("stale", 0L)
                .containsEntry("paused", 2L).containsEntry("inventory_inactive", 2L);
        assertThat((Map<String, Object>) out.get("totals")).containsEntry("stale", 0L).containsEntry("inventory_inactive", 2L);
        assertThat(MonitoringOverviewService.inventoryInactive(java.util.Set.of("A.example.com"), false, "a.example.com")).isTrue();
        assertThat(MonitoringOverviewService.inventoryInactive(java.util.Set.of("a.example.com"), false, "a.example.com")).isFalse();
        assertThat(MonitoringOverviewService.inventoryInactive(java.util.Set.of(), true, "a.example.com")).isFalse();    // standalone baypas
        assertThat(MonitoringOverviewService.inventoryInactive(null, false, "a.example.com")).isFalse();                 // depo yok → bilinmiyor
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Envanterden çıkmış eski Port satırının son kontrolü BAŞARISIZ olsa da 'sorunlu' değil duraklatılmış; açık alarm eski satıra bağlanmaz; aynı host'un bağımsız izlemesi sağlıklı ve standalone_twin olarak bağlı (outboundivrtahprod prod vakası)")
    void orphanFailedPort_isPausedNotDown_andLinksStandaloneTwin() {
        com.sitemonitor.repository.CertificateInventoryRepository inventoryRepo =
                org.mockito.Mockito.mock(com.sitemonitor.repository.CertificateInventoryRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "inventoryRepo", inventoryRepo);
        when(inventoryRepo.findActiveDomains()).thenReturn(List.of("other.example.com"));
        PortMonitor orphan = new PortMonitor(); orphan.setId(1L); orphan.setName("outboundivrtahprod.example.com"); orphan.setHost("outboundivrtahprod.example.com");
        orphan.setPort(443); orphan.setTeamId(14L); orphan.setActive(true); orphan.setStandalone(false); orphan.setIntervalSeconds(300);
        PortMonitor twin = new PortMonitor(); twin.setId(2L); twin.setName("IVR tah prod"); twin.setHost("OUTBOUNDIVRTAHPROD.example.com");
        twin.setPort(443); twin.setTeamId(14L); twin.setActive(true); twin.setStandalone(true); twin.setIntervalSeconds(300);
        when(portMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(orphan, twin));
        com.sitemonitor.model.PortCheck old = new com.sitemonitor.model.PortCheck(); old.setMonitorId(1L); old.setOpen(false); old.setError("Connect timed out"); old.setCheckedAt(ago(50L * 24 * 60));
        com.sitemonitor.model.PortCheck fresh = new com.sitemonitor.model.PortCheck(); fresh.setMonitorId(2L); fresh.setOpen(true); fresh.setCheckedAt(ago(2)); fresh.setResponseMs(40L);
        when(portCheckRepo.findLatestPerMonitor()).thenReturn(List.of(old, fresh));
        when(portCheckRepo.weeklyStatsByMonitor(anyCollection(), anyString(), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{2L, 288L, 288L}));
        // Host'un açık alarmı (hedef anahtarı küçük harf): YALNIZ taranan bağımsız satıra bağlanır, iki kez sayılmaz
        AlertEvent open = new AlertEvent(); open.setDomain("outboundivrtahprod.example.com"); open.setAlertType("PORT_DOWN"); open.setAlertLevel("HIGH"); open.setResolved(false);
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(open));

        Map<String, Object> out = svc.build(team -> true, true, 24);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("monitors");
        Map<String, Object> o = rows.stream().filter(r -> Long.valueOf(1L).equals(r.get("id"))).findFirst().orElseThrow();
        Map<String, Object> s = rows.stream().filter(r -> Long.valueOf(2L).equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(o).containsEntry("status", "paused").containsEntry("inventory_inactive", true).containsEntry("standalone", false)
                .containsEntry("open_alerts", 0).containsEntry("checks_window", 0L).containsEntry("success_rate_window", null);
        assertThat((Map<String, Object>) o.get("standalone_twin")).containsEntry("id", 2L).containsEntry("name", "IVR tah prod")
                .containsEntry("status", "down");   // alarmı açık → bağımsız satır sorunlu; eski satır değil
        assertThat(s).containsEntry("standalone", true).containsEntry("inventory_inactive", false).containsEntry("open_alerts", 1)
                .containsEntry("success_rate_window", 100.0).doesNotContainKey("standalone_twin");
        Map<String, Object> port = ((List<Map<String, Object>>) out.get("types")).stream().filter(x -> "port".equals(x.get("type"))).findFirst().orElseThrow();
        assertThat(port).containsEntry("down", 1L).containsEntry("paused", 1L).containsEntry("inventory_inactive", 1L)
                .containsEntry("open_alerts", 1L).containsEntry("success_rate_window", 100.0);
        assertThat((Map<String, Object>) out.get("totals")).containsEntry("down", 1L).containsEntry("open_alerts", 1L);

        // Alarm kapalıyken: bağımsız izleme sağlıklı, eski satır yine duraklatılmış — "sorunlu" toplamı 0
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of());
        Map<String, Object> out2 = svc.build(team -> true, true, 24);
        List<Map<String, Object>> rows2 = (List<Map<String, Object>>) out2.get("monitors");
        Map<String, Object> o2 = rows2.stream().filter(r -> Long.valueOf(1L).equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(o2).containsEntry("status", "paused");
        assertThat((Map<String, Object>) o2.get("standalone_twin")).containsEntry("status", "up");
        Map<String, Object> port2 = ((List<Map<String, Object>>) out2.get("types")).stream().filter(x -> "port".equals(x.get("type"))).findFirst().orElseThrow();
        assertThat(port2).containsEntry("down", 0L).containsEntry("active", 1L).containsEntry("paused", 1L);
        assertThat((Map<String, Object>) out2.get("totals")).containsEntry("down", 0L).containsEntry("stale", 0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Zenginleştirme (2026-10-01): satır başarı oranı + pencere ort. yanıt (ek sorgu yok), açık alarm başlangıcı + sahiplenme, tür ağırlıklı ort. yanıt, filo başarı oranı; DNS'in 4. sütunu (değişim sayısı) ort. yanıt SAYILMAZ")
    void enrichmentFields() {
        when(httpMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(http(1, "https://a.example.com", 14L, true, 300), http(2, "https://b.example.com", 14L, true, 300)));
        when(httpCheckRepo.findLatestPerMonitor()).thenReturn(List.of(httpCheck(1, true, ago(1)), httpCheck(2, true, ago(1))));
        // [id, toplam, başarılı, AVG(responseMs), ölçümlü sayısı]
        when(httpCheckRepo.weeklyStatsByMonitor(anyCollection(), anyString(), anyString())).thenReturn(List.<Object[]>of(
                new Object[]{1L, 10L, 9L, 100.0, 9L}, new Object[]{2L, 10L, 10L, 400.4, 3L}));
        AlertEvent older = new AlertEvent(); older.setDomain("https://a.example.com"); older.setAlertType("HTTP_DOWN"); older.setAlertLevel("WARNING");
        older.setResolved(false); older.setCreatedAt("2026-09-30T08:00:00"); older.setAcknowledged(true);
        AlertEvent newer = new AlertEvent(); newer.setDomain("https://a.example.com"); newer.setAlertType("HTTP_DOWN"); newer.setAlertLevel("CRITICAL");
        newer.setResolved(false); newer.setCreatedAt("2026-09-30T09:00:00"); newer.setAcknowledged(true);
        AlertEvent unacked = new AlertEvent(); unacked.setDomain("https://b.example.com"); unacked.setAlertType("HTTP_DOWN"); unacked.setAlertLevel("HIGH");
        unacked.setResolved(false); unacked.setCreatedAt("2026-09-30T10:00:00");
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(newer, unacked, older));
        com.sitemonitor.model.DnsMonitor d = new com.sitemonitor.model.DnsMonitor(); d.setId(5L); d.setName("d"); d.setDomain("d.example.com");
        d.setTeamId(14L); d.setActive(true); d.setStandalone(true); d.setIntervalSeconds(300);
        when(dnsMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(d));
        when(dnsRecordRepo.weeklyStatsByMonitor(anyCollection(), anyString(), anyString())).thenReturn(List.<Object[]>of(new Object[]{5L, 4L, 4L, 3L}));

        Map<String, Object> out = svc.build(team -> true, true, 24);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("monitors");
        java.util.function.Function<Long, Map<String, Object>> row = id -> rows.stream().filter(r -> id.equals(r.get("id")) && !"dns".equals(r.get("type"))).findFirst().orElseThrow();
        assertThat(row.apply(1L)).containsEntry("success_rate_window", 90.0).containsEntry("avg_response_ms_window", 100L)
                .containsEntry("open_since", "2026-09-30T08:00:00").containsEntry("open_acknowledged", true).containsEntry("open_alert_level", "CRITICAL");
        assertThat(row.apply(2L)).containsEntry("success_rate_window", 100.0).containsEntry("avg_response_ms_window", 400L)
                .containsEntry("open_acknowledged", false);
        Map<String, Object> dnsRow = rows.stream().filter(r -> "dns".equals(r.get("type"))).findFirst().orElseThrow();
        assertThat(dnsRow).containsEntry("avg_response_ms_window", null).containsEntry("checks_window", 4L).containsEntry("standalone", true);
        List<Map<String, Object>> types = (List<Map<String, Object>>) out.get("types");
        Map<String, Object> http = types.stream().filter(x -> "http".equals(x.get("type"))).findFirst().orElseThrow();
        // Ağırlıklı: (100×9 + 400×3) / 12 = 175
        assertThat(http).containsEntry("avg_response_ms_window", 175L).containsEntry("success_rate_window", 95.0);
        assertThat(types.stream().filter(x -> "dns".equals(x.get("type"))).findFirst().orElseThrow()).containsEntry("avg_response_ms_window", null);
        // Filo: 24 koşum, 1 başarısız → 95,8
        assertThat((Map<String, Object>) out.get("totals")).containsEntry("checks_window", 24L).containsEntry("failed_window", 1L)
                .containsEntry("success_rate_window", 95.8);
        assertThat(rows.stream().filter(r -> "http".equals(r.get("type"))).findFirst().orElseThrow()).containsEntry("standalone", null);
    }

    @Test
    @DisplayName("fresh=true (Yenile düğmesi): bellek kaydı 5 sn'den gençse o döner; eskiyse yeniden hesaplanır ve bellek tazelenir — sonraki yoklama taze kaydı okur")
    void fresh_bypassesMemoAtMostEveryFiveSeconds() {
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "cacheMs", 30_000L);
        long[] clock = {1_000_000L};
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "memo",
                new com.sitemonitor.util.TtlMemo<Map<String, Object>>(MonitoringOverviewService.MEMO_MAX_KEYS, () -> clock[0]));
        Map<String, Object> a = svc.build("ALL", team -> true, true, 24, false);
        clock[0] += 1_000;
        Map<String, Object> b = svc.build("ALL", team -> true, true, 24, true);     // 1 sn → sel koruması: bellekten
        assertThat(b).isSameAs(a);
        verify(teamRepo, times(1)).findAll();
        clock[0] += 6_000;
        Map<String, Object> c = svc.build("ALL", team -> true, true, 24, true);     // 7 sn → yeniden hesap
        assertThat(c).isNotSameAs(a);
        verify(teamRepo, times(2)).findAll();
        clock[0] += 1_000;
        Map<String, Object> d = svc.build("ALL", team -> true, true, 24);           // düz yoklama → taze kayıt
        assertThat(d).isSameAs(c);
        verify(teamRepo, times(2)).findAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Silinmiş (soft-delete) Port izlemesi 'deleted' sayılır, pencere sayımına girmez; Sentetik alarm ada bağlanır")
    void deletedPortAndScriptedKey() {
        PortMonitor gone = new PortMonitor(); gone.setId(7L); gone.setName("p7"); gone.setHost("h"); gone.setPort(443); gone.setTeamId(14L); gone.setActive(false); gone.setDeletedAt(ago(60));
        when(portMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(gone));
        ScriptedMonitor s = new ScriptedMonitor(); s.setId(9L); s.setName("OCPA - Response Time Anomalisi"); s.setTeamId(14L); s.setActive(true); s.setIntervalSeconds(300);
        when(scriptedMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(s));
        ScriptedCheck sc = new ScriptedCheck(); sc.setMonitorId(9L); sc.setOk(false); sc.setCheckedAt(ago(1)); sc.setDurationMs(900L);
        when(scriptedCheckRepo.findLatestPerMonitor()).thenReturn(List.of(sc));
        AlertEvent open = new AlertEvent(); open.setDomain("OCPA - Response Time Anomalisi"); open.setAlertType("SCRIPTED_FAIL"); open.setAlertLevel("WARNING"); open.setResolved(false);
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(open));

        Map<String, Object> out = svc.build(team -> true, true, 24);

        List<Map<String, Object>> types = (List<Map<String, Object>>) out.get("types");
        Map<String, Object> port = types.stream().filter(x -> "port".equals(x.get("type"))).findFirst().orElseThrow();
        assertThat(port).containsEntry("total", 1L).containsEntry("deleted", 1L).containsEntry("active", 0L).containsEntry("paused", 0L);
        verify(portCheckRepo, never()).weeklyStatsByMonitor(anyCollection(), anyString(), anyString());
        Map<String, Object> scripted = types.stream().filter(x -> "scripted".equals(x.get("type"))).findFirst().orElseThrow();
        assertThat(scripted).containsEntry("down", 1L).containsEntry("open_alerts", 1L);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("monitors");
        assertThat(rows.stream().filter(r -> "scripted".equals(r.get("type"))).findFirst().orElseThrow())
                .containsEntry("status", "down").containsEntry("open_alert_level", "WARNING").containsEntry("response_ms", 900L);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Kurtarılan sayımı global görüntüleyicide takımdan bağımsız: kapsam dışı + takımsız satırlar da sayılır (katalog dışı tip yine hayır)")
    void resolvedWindow_globalViewerCountsAllTeams() {
        when(alertEventRepo.countRecoveredSinceByTypeAndTeam(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"HTTP_DOWN", 14L, 1L}, new Object[]{"ACCESSIBILITY", 99L, 4L},
                new Object[]{"PING_DOWN", null, 2L}, new Object[]{"BOGUS", 14L, 3L}));
        Map<String, Object> out = svc.build(team -> false, true, 24);
        List<Map<String, Object>> types = (List<Map<String, Object>>) out.get("types");
        java.util.function.Function<String, Object> resolved = ty -> types.stream().filter(x -> ty.equals(x.get("type"))).findFirst().orElseThrow().get("resolved_window");
        assertThat(resolved.apply("http")).isEqualTo(5L);
        assertThat(resolved.apply("ping")).isEqualTo(2L);
        assertThat((Map<String, Object>) out.get("totals")).containsEntry("resolved_window", 7L);
    }

    @Test
    @DisplayName("Performans (2026-10-01): tam alarm/envanter entity'si yüklenmez — gruplu sayım + tek sütunluk envanter projeksiyonu")
    void noFullEntityLoads() {
        com.sitemonitor.repository.CertificateInventoryRepository inventoryRepo =
                org.mockito.Mockito.mock(com.sitemonitor.repository.CertificateInventoryRepository.class);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "inventoryRepo", inventoryRepo);
        when(inventoryRepo.findActiveDomains()).thenReturn(List.of());
        svc.build(team -> true, true, 24);
        verify(alertEventRepo).countRecoveredSinceByTypeAndTeam(anyString());
        verify(alertEventRepo, never()).findByResolvedAtGreaterThanEqual(anyString());
        verify(inventoryRepo).findActiveDomains();
        verify(inventoryRepo, never()).findByActiveTrueOrderByDomainAsc();
    }

    @Test
    @DisplayName("Bellek (2026-10-01): aynı (kapsam, pencere, global) anahtarı cacheMs içinde tek hesaplama; farklı kapsam/pencere ya da null anahtar yeniden hesaplar; cacheMs=0 kapalı")
    void memo_perScopeAndWindow() {
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "cacheMs", 60_000L);
        Map<String, Object> a = svc.build("T:14", team -> Long.valueOf(14L).equals(team), false, 24);
        Map<String, Object> b = svc.build("T:14", team -> Long.valueOf(14L).equals(team), false, 24);
        assertThat(b).isSameAs(a);
        verify(teamRepo, times(1)).findAll();

        svc.build("T:14", team -> Long.valueOf(14L).equals(team), false, 48);   // farklı pencere
        svc.build("T:3,14", team -> true, false, 24);                           // farklı kapsam
        svc.build("T:14", team -> true, true, 24);                              // farklı global bayrağı
        verify(teamRepo, times(4)).findAll();
        svc.build(null, team -> true, true, 24);                                // anahtarsız → bellek yok
        svc.build(null, team -> true, true, 24);
        verify(teamRepo, times(6)).findAll();
        // Pencere kırpılır: 0 saat → 1 saat; aynı kırpılmış anahtar bellekten gelir
        Map<String, Object> c1 = svc.build("ALL", team -> true, true, 0);
        Map<String, Object> c2 = svc.build("ALL", team -> true, true, -5);
        assertThat(c2).isSameAs(c1).containsEntry("window_hours", 1);

        org.springframework.test.util.ReflectionTestUtils.setField(svc, "cacheMs", 0L);
        Map<String, Object> d1 = svc.build("T:14", team -> true, false, 24);
        Map<String, Object> d2 = svc.build("T:14", team -> true, false, 24);
        assertThat(d2).isNotSameAs(d1);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("orgSummary (2026-10-04, giriş sayfası): global görüntüleyicinin pano toplamlarıyla AYNI; sağlıklı = aktif − düşük − gecikmiş − bilinmiyor = 'up' satır sayısı; kapsamsız (her takım + takımsız); bellek anahtarı panoyla ortak")
    void orgSummary_equalsGlobalViewerTotals() {
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "cacheMs", 60_000L);
        when(httpMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(
                http(1, "https://up.example.com", 14L, true, 300),        // up
                http(2, "https://down.example.com", 14L, true, 300),      // düşük
                http(3, "https://stale.example.com", 14L, true, 300),     // gecikmiş
                http(4, "https://paused.example.com", 14L, false, 300),   // duraklatılmış (aktif değil)
                http(5, "https://new.example.com", 14L, true, 300),       // hiç kontrol yok
                http(6, "https://other.example.com", 99L, true, 300),     // başka takım — up
                http(7, "https://teamless.example.com", null, true, 300)));  // takımsız — up
        when(httpCheckRepo.findLatestPerMonitor()).thenReturn(List.of(
                httpCheck(1, true, ago(2)), httpCheck(2, false, ago(2)), httpCheck(3, true, ago(120)),
                httpCheck(4, true, ago(2)), httpCheck(6, true, ago(1)), httpCheck(7, true, ago(1))));
        when(httpCheckRepo.weeklyStatsByMonitor(anyCollection(), anyString(), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{1L, 10L, 10L, 100.0, 10L}, new Object[]{2L, 8L, 3L, 100.0, 8L}));

        // İzleme Panosu, global görüntüleyici: denetleyicinin kurduğu anahtar + yüklem + pencere.
        Map<String, Object> page = svc.build(com.sitemonitor.util.TtlMemo.scopeKey(true, null), team -> true, true,
                MonitoringOverviewService.ORG_SUMMARY_WINDOW_HOURS);
        Map<String, Object> totals = (Map<String, Object>) page.get("totals");
        Map<String, Object> org = svc.orgSummary();

        long active = ((Number) totals.get("active")).longValue();
        long expectedHealthy = active - ((Number) totals.get("down")).longValue()
                - ((Number) totals.get("stale")).longValue() - ((Number) totals.get("unknown")).longValue();
        long upRows = ((List<Map<String, Object>>) page.get("monitors")).stream().filter(r -> "up".equals(r.get("status"))).count();
        assertThat(org).containsEntry("total", totals.get("total")).containsEntry("active", totals.get("active"))
                .containsEntry("down", totals.get("down")).containsEntry("stale", totals.get("stale"))
                .containsEntry("unknown", totals.get("unknown")).containsEntry("paused", totals.get("paused"))
                .containsEntry("checks_window", totals.get("checks_window")).containsEntry("failed_window", totals.get("failed_window"))
                .containsEntry("healthy", expectedHealthy).containsEntry("window_hours", 24);
        assertThat(expectedHealthy).isEqualTo(upRows).isEqualTo(3L);     // 1, 6 (başka takım), 7 (takımsız)
        assertThat(org).containsEntry("total", 7L).containsEntry("active", 6L).containsEntry("checks_window", 18L)
                .containsEntry("failed_window", 5L);
        // Kuruluş geneli özet YALNIZ sayı taşır — satır, ad, hedef, takım adı yok.
        assertThat(org).doesNotContainKeys("monitors", "types", "totals");
        assertThat(org.values()).noneMatch(v -> v instanceof String s && s.contains("example.com"));
        // Ortak bellek: pano (global) + giriş sayfası tek hesap.
        verify(httpMonitorRepo, times(1)).findAllByOrderByNameAsc();
    }

    @Test
    @DisplayName("healthyOf: aktif − düşük − gecikmiş − bilinmiyor, tabanı 0")
    void healthyOf_formula() {
        assertThat(MonitoringOverviewService.healthyOf(702, 9, 3, 2)).isEqualTo(688L);
        assertThat(MonitoringOverviewService.healthyOf(0, 0, 0, 0)).isZero();
        assertThat(MonitoringOverviewService.healthyOf(2, 3, 0, 0)).isZero();
    }

    @Test
    @DisplayName("orgSummary: toplamlar yoksa null (giriş sayfası '—' gösterir)")
    void orgSummary_nullWhenNoTotals() {
        MonitoringOverviewService spy = spy(svc);
        doReturn(Map.of("generated_at", "x")).when(spy).build(anyString(), any(), anyBoolean(), anyInt());
        assertThat(spy.orgSummary()).isNull();
    }

    @Test
    @DisplayName("isStale: aralığın 3 katı (en az 10 dk) geçtiyse eski; damga yoksa değil; aralıksız izlemede 3 saat")
    void staleRule() {
        Instant now = Instant.now();
        assertThat(MonitoringOverviewService.isStale(ago(2), 300, now)).isFalse();
        assertThat(MonitoringOverviewService.isStale(ago(16), 300, now)).isTrue();
        assertThat(MonitoringOverviewService.isStale(ago(8), 60, now)).isFalse();   // taban 10 dk
        assertThat(MonitoringOverviewService.isStale(null, 300, now)).isFalse();
        assertThat(MonitoringOverviewService.isStale(ago(170), null, now)).isFalse();
        assertThat(MonitoringOverviewService.isStale(ago(190), null, now)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Durum Sayfası (2026-10-01): satır izleme grubunu taşır (group_name, kırpılmış; boş → null) — dokuz türün hepsinde, ek sorgu yok")
    void rowsCarryGroupName() {
        HttpMonitor grouped = http(1, "https://a.example.com", 14L, true, 300); grouped.setGroupName("  Ödeme  ");
        HttpMonitor blank = http(2, "https://b.example.com", 14L, true, 300); blank.setGroupName("   ");
        when(httpMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(grouped, blank));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) svc.build(team -> true, true, 24).get("monitors");
        assertThat(rows).extracting(r -> r.get("group_name")).containsExactly("Ödeme", null);

        com.sitemonitor.model.PingMonitor ping = new com.sitemonitor.model.PingMonitor(); ping.setGroupName("g1");
        PortMonitor port = new PortMonitor(); port.setGroupName("g2");
        com.sitemonitor.model.DnsMonitor dns = new com.sitemonitor.model.DnsMonitor(); dns.setGroupName("g3");
        com.sitemonitor.model.DomainMonitor domain = new com.sitemonitor.model.DomainMonitor(); domain.setGroupName("g4");
        com.sitemonitor.model.KeywordMonitor keyword = new com.sitemonitor.model.KeywordMonitor(); keyword.setGroupName("g5");
        com.sitemonitor.model.PageMonitor page = new com.sitemonitor.model.PageMonitor(); page.setGroupName("g6");
        com.sitemonitor.model.PageSpeedMonitor speed = new com.sitemonitor.model.PageSpeedMonitor(); speed.setGroupName("g7");
        ScriptedMonitor scripted = new ScriptedMonitor(); scripted.setGroupName("g8");
        assertThat(List.<Object>of(ping, port, dns, domain, keyword, page, speed, scripted)).extracting(m -> MonitoringOverviewService.groupNameOf(m))
                .containsExactly("g1", "g2", "g3", "g4", "g5", "g6", "g7", "g8");
        assertThat(MonitoringOverviewService.groupNameOf(null)).isNull();
        assertThat(MonitoringOverviewService.groupNameOf("başka nesne")).isNull();
    }
}
