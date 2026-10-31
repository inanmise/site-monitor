package com.sitemonitor.service;

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
 * k6r D2 (2026-10-31): genel bakışta zamanlamalı sentetik izleme — pencere dışında beklerken "gecikmiş" SAYILMAZ (planlı koşum
 * kuralı: {@code next_run_at} + pay); planlı koşum geçip koşulmadıysa gecikmiş; düz aralıktaki izleme bugünkü aralık kuralıyla.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScriptedScheduleOverviewTest {

    @Mock HttpMonitorRepository httpMonitorRepo; @Mock PingMonitorRepository pingMonitorRepo; @Mock PortMonitorRepository portMonitorRepo;
    @Mock DnsMonitorRepository dnsMonitorRepo; @Mock KeywordMonitorRepository keywordMonitorRepo; @Mock PageMonitorRepository pageMonitorRepo;
    @Mock PageSpeedMonitorRepository pageSpeedMonitorRepo; @Mock ScriptedMonitorRepository scriptedMonitorRepo; @Mock DomainMonitorRepository domainMonitorRepo;
    @Mock HttpCheckRepository httpCheckRepo; @Mock PingCheckRepository pingCheckRepo; @Mock PortCheckRepository portCheckRepo;
    @Mock DnsRecordRepository dnsRecordRepo; @Mock KeywordResultRepository keywordResultRepo; @Mock PageCheckRepository pageCheckRepo;
    @Mock PageSpeedCheckRepository pageSpeedCheckRepo; @Mock ScriptedCheckRepository scriptedCheckRepo; @Mock DomainCheckRepository domainCheckRepo;
    @Mock AlertEventRepository alertEventRepo; @Mock TeamRepository teamRepo;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static String ago(long minutes) { return ISO.format(Instant.now().minus(minutes, ChronoUnit.MINUTES)); }
    private static String inMinutes(long minutes) { return ISO.format(Instant.now().plus(minutes, ChronoUnit.MINUTES)); }

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

    private static ScriptedMonitor k6(long id, String name) {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setId(id); m.setName(name); m.setTeamId(14L); m.setActive(true); m.setIntervalSeconds(300);
        return m;
    }

    private static ScriptedCheck pass(long monitorId, String at) {
        ScriptedCheck c = new ScriptedCheck();
        c.setMonitorId(monitorId); c.setOk(true); c.setStatus("PASS"); c.setCheckedAt(at); c.setDurationMs(500L);
        return c;
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("pencere dışında bekleyen zamanlamalı izleme güncel; planlı koşumu kaçmış olan gecikmiş; düz aralık bugünkü kural")
    void scheduledScriptedStaleRule() {
        ScriptedMonitor waiting = k6(1, "Mesai içi");               // son koşum 3 saat önce, sonraki pencere 2 saat sonra
        waiting.setWindowsJson("[{\"days\":[1,2,3,4,5],\"from\":\"09:00\",\"to\":\"18:00\"}]");
        waiting.setScheduleMode("INTERVAL");
        waiting.setNextRunAt(inMinutes(120));
        ScriptedMonitor overdue = k6(2, "Günlük cron");             // planlı koşum 20 dk önceydi, koşulmadı
        overdue.setScheduleMode("CRON"); overdue.setCronExpression("0 8 * * *"); overdue.setIntervalSeconds(86_400);
        overdue.setNextRunAt(ago(20));
        ScriptedMonitor flat = k6(3, "Düz aralık");                 // düz 300 sn, son koşum 3 saat önce → gecikmiş
        flat.setNextRunAt(inMinutes(120));                          // düz aralıkta saklı plan yok sayılır
        when(scriptedMonitorRepo.findAllByOrderByNameAsc()).thenReturn(List.of(waiting, overdue, flat));
        when(scriptedCheckRepo.findLatestPerMonitor()).thenReturn(List.of(pass(1, ago(180)), pass(2, ago(60 * 25)), pass(3, ago(180))));
        when(scriptedCheckRepo.weeklyStatsByMonitor(anyCollection(), anyString(), anyString())).thenReturn(List.of());

        Map<String, Object> out = svc.build(team -> true, false, 24);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("monitors");
        java.util.function.Function<String, Map<String, Object>> row =
                n -> rows.stream().filter(r -> n.equals(r.get("name"))).findFirst().orElseThrow();
        assertThat(row.apply("Mesai içi")).containsEntry("status", "up");
        assertThat(row.apply("Günlük cron")).containsEntry("status", "stale");
        assertThat(row.apply("Düz aralık")).containsEntry("status", "stale");
    }

    @Test
    @DisplayName("isScriptedStale: plan + 300 sn pay; plan yok = gecikme yok")
    void isScriptedStaleRule() {
        ScriptedMonitor m = k6(9, "x");
        assertThat(MonitoringOverviewService.isScriptedStale(m, Instant.now())).isFalse();
        m.setNextRunAt(ago(4));
        assertThat(MonitoringOverviewService.isScriptedStale(m, Instant.now())).isFalse();
        m.setNextRunAt(ago(6));
        assertThat(MonitoringOverviewService.isScriptedStale(m, Instant.now())).isTrue();
        assertThat(MonitoringOverviewService.isScriptedStale(null, Instant.now())).isFalse();
    }
}
