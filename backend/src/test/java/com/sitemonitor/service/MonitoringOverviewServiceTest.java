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
        when(alertEventRepo.findByResolvedAtGreaterThanEqual(anyString())).thenReturn(List.of());
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
        AlertEvent resolved = new AlertEvent(); resolved.setDomain("https://up.example.com"); resolved.setAlertType("HTTP_DOWN"); resolved.setResolved(true); resolved.setTeamId(14L); resolved.setResolvedAt(ago(30));
        when(alertEventRepo.findByResolvedAtGreaterThanEqual(anyString())).thenReturn(List.of(resolved));

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
}
