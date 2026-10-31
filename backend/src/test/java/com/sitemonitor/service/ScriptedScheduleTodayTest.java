package com.sitemonitor.service;

import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * k6r D2 (2026-10-31): "Sizin için — bugün" bayat kartı — zamanlamalı sentetik izleme pencere dışında beklerken BAYAT DEĞİL
 * (planlı koşum + pay kuralı, veritabanı sorgu izlemesiyle aynı); planlı koşumu kaçmışsa bayat; düz aralık bugünkü kural.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScriptedScheduleTodayTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-11-09T19:00:00Z");

    @Mock ActivityLogRepository activityRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock UserPushDeliveryRepository pushDeliveryRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock WeakAlgorithmExceptionRepository exceptionRepo;
    @Mock CertificateHealthService healthService;
    @Mock DomainCheckRepository domainCheckRepo;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock PageMonitorRepository pageRepo;
    @Mock PageSpeedMonitorRepository pageSpeedRepo;
    @Mock ScriptedMonitorRepository scriptedRepo;
    @Mock DomainMonitorRepository domainRepo;
    @Mock MaintenanceWindowRepository maintenanceRepo;
    @Mock MonitorChangeLogRepository changeLogRepo;
    TodayMonitorInsightsService svc;

    private static String at(long minutesAgo) { return ISO.format(NOW.minus(Duration.ofMinutes(minutesAgo))); }

    @BeforeEach
    void setUp() {
        svc = new TodayMonitorInsightsService(activityRepo, inventoryRepo, notificationLogRepo, pushDeliveryRepo, alertEventRepo,
                latestCheckRepo, exceptionRepo, healthService, domainCheckRepo, httpRepo, portRepo, pingRepo, dnsRepo,
                keywordRepo, pageRepo, pageSpeedRepo, scriptedRepo, domainRepo,
                maintenanceRepo, new MaintenanceService(maintenanceRepo), changeLogRepo);
        for (var r : List.of(portRepo, pingRepo, dnsRepo, keywordRepo, pageRepo, pageSpeedRepo, domainRepo, httpRepo))
            when(r.findAll()).thenReturn(List.of());
        when(activityRepo.monitorsWithFailureSince(anyString())).thenReturn(List.of());
        when(activityRepo.statusSequence(anyString(), anyList(), anyString())).thenReturn(List.of());
        when(activityRepo.avgResponseByMonitor(anyString(), anyString())).thenReturn(List.of());
        when(domainCheckRepo.findLatestPerMonitor()).thenReturn(List.of());
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());
        when(notificationLogRepo.findAllSince(anyString())).thenReturn(List.of());
        when(pushDeliveryRepo.findByStatusAndCreatedAtGreaterThanEqualOrderByIdDesc(anyString(), anyString())).thenReturn(List.of());
        when(latestCheckRepo.findAll()).thenReturn(List.of());
        when(exceptionRepo.findAll()).thenReturn(List.of());
        when(maintenanceRepo.findByActiveTrue()).thenReturn(List.of());
        when(changeLogRepo.lastActiveChange(any())).thenReturn(List.of());
        when(alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        when(alertEventRepo.findByResolvedAtGreaterThanEqual(anyString())).thenReturn(List.of());
        when(healthService.thresholdResolution()).thenReturn(ThresholdResolution.fixed(null));
    }

    private static ScriptedMonitor k6(long id, String name) {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setId(id); m.setName(name); m.setTeamId(1L); m.setActive(true); m.setIntervalSeconds(300);
        m.setCreatedAt(at(10_000));
        return m;
    }

    @Test
    @DisplayName("mesai dışında bekleyen zamanlamalı izleme bayat değil; planlı koşumu kaçmış olan bayat; düz aralık bugünkü kural")
    void scheduledScriptedStaleRule() {
        ScriptedMonitor waiting = k6(1, "mesai");
        waiting.setScheduleMode("INTERVAL");
        waiting.setWindowsJson("[{\"days\":[1,2,3,4,5],\"from\":\"09:00\",\"to\":\"18:00\"}]");
        waiting.setNextRunAt(ISO.format(NOW.plus(Duration.ofHours(11))));   // yarın 09:00
        ScriptedMonitor missed = k6(2, "cron");
        missed.setScheduleMode("CRON"); missed.setCronExpression("0 8 * * *"); missed.setIntervalSeconds(86_400);
        missed.setNextRunAt(at(30));                                          // 30 dk önceydi, koşulmadı
        ScriptedMonitor flat = k6(3, "duz");
        when(scriptedRepo.findAll()).thenReturn(List.of(waiting, missed, flat));
        when(activityRepo.lastCheckByMonitor(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"SCRIPTED", 1L, at(120)}, new Object[]{"SCRIPTED", 2L, at(60 * 25)}, new Object[]{"SCRIPTED", 3L, at(120)}));

        TodayMonitorInsightsService.Snapshot snap = svc.snapshot(NOW);

        assertThat(snap.stale()).extracting(m -> m.get("monitor_id")).containsExactlyInAnyOrder(2L, 3L);
        var cron = snap.stale().stream().filter(m -> m.get("monitor_id").equals(2L)).findFirst().orElseThrow();
        assertThat(cron).containsEntry("expected_min", MonitoringOverviewService.DBQUERY_STALE_GRACE_SECONDS / 60)
                .containsEntry("interval_sec", 86_400).containsEntry("never", false);
    }

    @Test
    @DisplayName("inceleme #3: plan ileri yazılsa da koşmayan zamanlamalı izleme bayat — son kontrol ya da (hiç yoksa) yaratılış anından")
    void skippedOrNeverRunScheduledScriptedIsStale() {
        ScriptedMonitor skipped = k6(4, "atlanan");
        skipped.setScheduleMode("CRON"); skipped.setCronExpression("0 8 * * *"); skipped.setIntervalSeconds(86_400);
        skipped.setNextRunAt(ISO.format(NOW.plus(Duration.ofHours(10))));      // her dönem ileri yazıldı
        ScriptedMonitor never = k6(5, "hic");
        never.setScheduleMode("CRON"); never.setCronExpression("0 8 * * *"); never.setIntervalSeconds(86_400);
        never.setNextRunAt(ISO.format(NOW.plus(Duration.ofHours(10))));
        ScriptedMonitor ok = k6(6, "bugun-kostu");                              // bugünkü 08:00 tetiği koştu
        ok.setScheduleMode("CRON"); ok.setCronExpression("0 8 * * *"); ok.setIntervalSeconds(86_400);
        ok.setNextRunAt(ISO.format(NOW.plus(Duration.ofHours(10))));
        when(scriptedRepo.findAll()).thenReturn(List.of(skipped, never, ok));
        when(activityRepo.lastCheckByMonitor(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"SCRIPTED", 4L, at(60 * 30)}, new Object[]{"SCRIPTED", 6L, at(60 * 10)}));

        TodayMonitorInsightsService.Snapshot snap = svc.snapshot(NOW);

        assertThat(snap.stale()).extracting(m -> m.get("monitor_id")).containsExactlyInAnyOrder(4L, 5L);
        var hic = snap.stale().stream().filter(m -> m.get("monitor_id").equals(5L)).findFirst().orElseThrow();
        assertThat(hic).containsEntry("never", true);
    }

    @Test
    @DisplayName("inceleme #3: pencereden önce yaratılmış ve pencerede kontrolü görünmeyen aylık cron — son kontrol bilinmez, plan ileride → bayat DEĞİL")
    void monthlyCronOutsideActivityWindowIsNotGuessedStale() {
        ScriptedMonitor monthly = k6(7, "aylik");
        monthly.setCreatedAt(at(60L * 24 * 60));                            // 60 gün önce
        monthly.setScheduleMode("CRON"); monthly.setCronExpression("@monthly"); monthly.setIntervalSeconds(30 * 86_400);
        monthly.setNextRunAt(ISO.format(NOW.plus(Duration.ofDays(10))));
        when(scriptedRepo.findAll()).thenReturn(List.of(monthly));
        when(activityRepo.lastCheckByMonitor(anyString())).thenReturn(List.of());

        assertThat(ScriptedSchedule.hasPlan(monthly)).isTrue();
        assertThat(MonitoringOverviewService.isScriptedStale(monthly, null, NOW, java.util.Set.of())).as("doğrudan").isFalse();
        assertThat(svc.snapshot(NOW).stale()).isEmpty();
    }
}
