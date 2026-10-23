package com.sitemonitor.service.watchdog;

import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.DatabaseHealthService;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import com.sitemonitor.service.watchdog.WatchdogTransition.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Kendi kendini izleme yoklamaları (2026-10-11): her kontrolün OK / PROBLEM / NA kararı ve hata → UNKNOWN. */
class WatchdogProbesTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    JdbcTemplate jdbc;
    AppSettingsService settings;
    WatchdogProbes probes;
    ClusterScheduleService cluster;
    MonitoringOverviewService overview;
    NotificationLogRepository logs;
    UserPushService push;
    DatabaseHealthService db;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        settings = mock(AppSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        probes = new WatchdogProbes(jdbc, settings);
        cluster = mock(ClusterScheduleService.class);
        overview = mock(MonitoringOverviewService.class);
        logs = mock(NotificationLogRepository.class);
        push = mock(UserPushService.class);
        db = mock(DatabaseHealthService.class);
        probes.setCluster(cluster);
        probes.setOverview(overview);
        probes.setNotificationLogs(logs);
        probes.setPush(push);
        probes.setDatabase(db);
    }

    @Test
    @DisplayName("tarama liderliği: kira yok → NA; taze → OK; bir TTL'den uzun süredir dolu → PROBLEM (kritik)")
    void sweepLeader() {
        when(cluster.lease("sweep-leader")).thenReturn(null);
        assertThat(probes.sweepLeader(NOW).result()).isEqualTo(Result.NA);
        when(cluster.lease("sweep-leader")).thenReturn(new ClusterScheduleService.Lease("pod-a", NOW.toEpochMilli() + 60_000));
        assertThat(probes.sweepLeader(NOW).result()).isEqualTo(Result.OK);
        when(cluster.lease("sweep-leader")).thenReturn(new ClusterScheduleService.Lease("pod-a", NOW.toEpochMilli() - 4 * 60_000));
        WatchdogProbes.Probe p = probes.sweepLeader(NOW);
        assertThat(p.result()).isEqualTo(Result.PROBLEM);
        assertThat(p.severity()).isEqualTo("CRITICAL");
        assertThat(p.facts()).containsEntry("expired_seconds", 240L);
    }

    @Test
    @DisplayName("izleme taramaları: etkin ≥ 2 türde gecikmiş oranı ≥ %50 → PROBLEM; küçük türler sayılmaz")
    void monitorSweep() {
        when(overview.orgTypes()).thenReturn(List.of(
                Map.of("type", "http", "active", 20L, "stale", 2L),
                Map.of("type", "ping", "active", 6L, "stale", 3L),
                Map.of("type", "dns", "active", 1L, "stale", 1L)));
        WatchdogProbes.Probe p = probes.monitorSweep();
        assertThat(p.result()).isEqualTo(Result.PROBLEM);
        assertThat(p.facts()).containsEntry("types_checked", 2);
        assertThat(WatchdogMessages.detail(WatchdogCheck.MONITOR_SWEEP, p.facts(), false)).contains("ping 3/6").doesNotContain("dns");
        when(overview.orgTypes()).thenReturn(List.of(Map.of("type", "http", "active", 20L, "stale", 2L)));
        assertThat(probes.monitorSweep().result()).isEqualTo(Result.OK);
        when(overview.orgTypes()).thenReturn(null);
        assertThat(probes.monitorSweep().result()).isEqualTo(Result.UNKNOWN);
    }

    @Test
    @DisplayName("sertifika taraması: envanter yok → NA; son sonuç sınırın içinde → OK; dışında → PROBLEM")
    void certSweep() {
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("certificate_inventory"), eq(Long.class))).thenReturn(0L);
        assertThat(probes.certSweep(NOW).result()).isEqualTo(Result.NA);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("certificate_inventory"), eq(Long.class))).thenReturn(40L);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("latest_checks"), eq(String.class))).thenReturn("2026-10-11T10:30:00");
        assertThat(probes.certSweep(NOW).result()).isEqualTo(Result.OK);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("latest_checks"), eq(String.class))).thenReturn("2026-10-11T06:00:00");
        WatchdogProbes.Probe p = probes.certSweep(NOW);
        assertThat(p.result()).isEqualTo(Result.PROBLEM);
        assertThat(p.facts()).containsEntry("hours_since", 6L).containsEntry("limit_hours", 4);
    }

    @Test
    @DisplayName("bildirim: 3'ten az deneme → OK; %50 ve üstü başarısız → PROBLEM")
    void notifications() {
        when(logs.countAttemptedSince(anyString())).thenReturn(2L);
        when(logs.countSentSince(anyString())).thenReturn(0L);
        assertThat(probes.notifications(NOW).result()).isEqualTo(Result.OK);
        when(logs.countAttemptedSince(anyString())).thenReturn(10L);
        when(logs.countSentSince(anyString())).thenReturn(4L);
        WatchdogProbes.Probe p = probes.notifications(NOW);
        assertThat(p.result()).isEqualTo(Result.PROBLEM);
        assertThat(p.facts()).containsEntry("failed", 6L);
        when(logs.countSentSince(anyString())).thenReturn(8L);
        assertThat(probes.notifications(NOW).result()).isEqualTo(Result.OK);
    }

    @Test
    @DisplayName("push kuyruğu: kapalı → NA; takılan satır eşiği → PROBLEM; devre kesici YALNIZ bilgi (MP-36, pod'a özgü)")
    void pushOutbox() {
        when(push.enabled()).thenReturn(false);
        assertThat(probes.pushOutbox(NOW).result()).isEqualTo(Result.NA);
        when(push.enabled()).thenReturn(true);
        when(push.healthSnapshot()).thenReturn(Map.of("circuit_open", false));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(3L);
        assertThat(probes.pushOutbox(NOW).result()).isEqualTo(Result.OK);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(12L);
        assertThat(probes.pushOutbox(NOW).result()).isEqualTo(Result.PROBLEM);
        // Yeniden taban (2026-10-23, MP-36): açık devre turu kazanan pod'un belleğidir — tek başına durumu değiştirmez, olgu kalır
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(0L);
        when(push.healthSnapshot()).thenReturn(Map.of("circuit_open", true));
        WatchdogProbes.Probe p = probes.pushOutbox(NOW);
        assertThat(p.result()).isEqualTo(Result.OK);
        assertThat(p.facts()).containsEntry("circuit_open", true).containsKey("evaluated_by");
        // İnceleme W-2: ölü ağ geçidi küme genelindeki sonuç oranından görünür (satırlar takılmadan FAILED olur)
        when(jdbc.query(eq(WatchdogProbes.SQL_PUSH_OUTCOMES), any(org.springframework.jdbc.core.RowMapper.class), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{"SENT", 1L}, new Object[]{"FAILED", 4L}));
        WatchdogProbes.Probe down = probes.pushOutbox(NOW);
        assertThat(down.result()).isEqualTo(Result.PROBLEM);
        assertThat(down.facts()).containsEntry("recent_failed", 4L).containsEntry("recent_sent", 1L);
        when(jdbc.query(eq(WatchdogProbes.SQL_PUSH_OUTCOMES), any(org.springframework.jdbc.core.RowMapper.class), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{"FAILED", 2L}));
        assertThat(probes.pushOutbox(NOW).result()).as("en az 3 sonuç gerekir").isEqualTo(Result.OK);
    }

    @Test
    @DisplayName("veritabanı: UP → OK, DEGRADED → orta, DOWN → kritik; bir yoklamanın hatası UNKNOWN (diğerleri sürer)")
    void databaseAndFailure() {
        when(db.current()).thenReturn(new DatabaseHealthService.Snapshot(Map.of(), DatabaseHealthService.DEGRADED, 0L));
        assertThat(probes.database(NOW).severity()).isEqualTo("MEDIUM");
        when(db.current()).thenReturn(new DatabaseHealthService.Snapshot(Map.of(), DatabaseHealthService.DOWN, 0L));
        assertThat(probes.database(NOW).severity()).isEqualTo("CRITICAL");
        when(db.current()).thenReturn(new DatabaseHealthService.Snapshot(Map.of(), DatabaseHealthService.UP, 0L));
        assertThat(probes.database(NOW).result()).isEqualTo(Result.OK);

        when(cluster.lease(anyString())).thenThrow(new RuntimeException("bağlantı yok"));
        List<WatchdogProbes.Probe> all = probes.runAll(NOW);
        assertThat(all).hasSize(WatchdogCheck.values().length);
        assertThat(all.get(0).result()).isEqualTo(Result.UNKNOWN);
        assertThat(all.get(0).facts()).containsEntry("reason", "probe_failed");
    }
}
