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
    @DisplayName("R3-4 (paket C1): sertifika taraması olguları — anahtar KAPALI → olgular ve cümle 20.170.0 ile AYNI, koşu deposu hiç "
            + "okunmaz; AÇIK → chunked + açık tur yaşı + en eski bekleyen parça + 24 sa terk edilen tur, KARAR DEĞİŞMEZ; okunamadı → "
            + "chunked_status unreadable")
    void certSweepChunkFacts() {
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("certificate_inventory"), eq(Long.class))).thenReturn(40L);
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.contains("latest_checks"), eq(String.class))).thenReturn("2026-10-11T10:30:00");
        WatchdogProbes.Probe before = probes.certSweep(NOW);
        when(settings.getBoolean(eq(com.sitemonitor.service.certsweep.CertChunking.KEY_ENABLED), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(false);
        com.sitemonitor.service.certsweep.CertSweepStore store = mock(com.sitemonitor.service.certsweep.CertSweepStore.class);
        probes.setCertChunking(new com.sitemonitor.service.certsweep.CertChunking(settings, null));
        probes.setCertSweepStore(store);
        WatchdogProbes.Probe off = probes.certSweep(NOW);
        assertThat(off.facts()).isEqualTo(before.facts());
        assertThat(off.result()).isEqualTo(before.result());
        assertThat(WatchdogMessages.detail(WatchdogCheck.CERT_SWEEP, off.facts(), false))
                .isEqualTo(WatchdogMessages.detail(WatchdogCheck.CERT_SWEEP, before.facts(), false))
                .isEqualTo("Son sertifika sonucu 1 sa önce (sınır 4 sa).");
        org.mockito.Mockito.verifyNoInteractions(store);

        when(settings.getBoolean(eq(com.sitemonitor.service.certsweep.CertChunking.KEY_ENABLED), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(true);
        long now = NOW.toEpochMilli();
        com.sitemonitor.service.certsweep.CertSweepStatus.Run open = new com.sitemonitor.service.certsweep.CertSweepStatus.Run("o", "hourly",
                "pod-a-0000000a", "RUNNING", true, 400, 20, 20, now - 95_000, now - 5_000, null, null, null, null, null, null);
        com.sitemonitor.service.certsweep.CertSweepStatus.Run ab = new com.sitemonitor.service.certsweep.CertSweepStatus.Run("a", "hourly",
                "pod-a-0000000a", "ABANDONED", true, 400, 20, 20, now - 3_600_000, now - 3_600_000, now - 3_500_000, "SHUTDOWN", null, null,
                null, null);
        when(store.status(WatchdogProbes.CERT_CHUNK_STATUS_RUNS)).thenReturn(new com.sitemonitor.service.certsweep.CertSweepStatus(now, false,
                List.of(open, ab), open, List.of(new com.sitemonitor.service.certsweep.CertSweepStatus.ChunkGroup("PENDING", null, 3, 60, 0,
                        now - 42_000))));
        // koordinatör (pod-a) cert-check kilidini tutuyor — canlı açık tur (inceleme OB-L1: kilidi tutmayan koordinatörün turu sahipsizdir)
        stubCertLock("pod-a-0000000a");
        WatchdogProbes.Probe on = probes.certSweep(NOW);
        assertThat(on.result()).as("karar değişmez").isEqualTo(before.result());
        assertThat(on.facts()).containsEntry("chunked", true).containsEntry("chunked_run_age_s", 95L)
                .containsEntry("oldest_pending_chunk_s", 42L).containsEntry("abandoned_runs_24h", 1L)
                .doesNotContainKey("abandoned_runs_24h_lower_bound").doesNotContainKey("reason")
                .containsEntry("hours_since", 1L);
        assertThat(WatchdogMessages.detail(WatchdogCheck.CERT_SWEEP, on.facts(), false))
                .isEqualTo("Son sertifika sonucu 1 sa önce (sınır 4 sa). Parçalı tarama açık: açık tur 95 sn'dir sürüyor, en eski bekleyen "
                        + "parça 42 sn; son 24 saatte terk edilen tur: 1.");
        assertThat(WatchdogMessages.detail(WatchdogCheck.CERT_SWEEP, on.facts(), true))
                .endsWith(" Chunked sweep is on: open run 95 s old, oldest waiting chunk 42 s; abandoned runs in 24 h: 1.");

        // OB-L1: kilidi başka pod tutuyor → koordinatör öldü; açık tur yaşı / en eski bekleyen YOK, sahipsiz tur olgusu + cümlesi
        stubCertLock("pod-b-0000000b");
        WatchdogProbes.Probe orphan = probes.certSweep(NOW);
        assertThat(orphan.result()).as("karar değişmez").isEqualTo(before.result());
        assertThat(orphan.facts()).containsEntry("chunked_run_age_s", null).containsEntry("oldest_pending_chunk_s", null)
                .containsEntry("chunked_orphan_run", true).containsEntry("abandoned_runs_24h", 1L);
        assertThat(WatchdogMessages.detail(WatchdogCheck.CERT_SWEEP, orphan.facts(), false))
                .contains("açık tur yok (tarama kilidini artık tutmayan bir koordinatörün terk edilmiş turu kayıtlı; sonraki tur kapatır)");
        assertThat(WatchdogMessages.detail(WatchdogCheck.CERT_SWEEP, orphan.facts(), true)).contains("no open run (an abandoned run");
        // kilit okunamadı → bugünkü gibi açık tur
        when(jdbc.query(eq(com.sitemonitor.service.certsweep.CertSweepView.SQL_CERT_LOCK), any(org.springframework.jdbc.core.RowMapper.class),
                eq("cert-check"))).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));
        assertThat(probes.certSweep(NOW).facts()).containsEntry("chunked_run_age_s", 95L).doesNotContainKey("chunked_orphan_run");

        org.mockito.Mockito.clearInvocations(jdbc);
        when(store.status(WatchdogProbes.CERT_CHUNK_STATUS_RUNS)).thenReturn(new com.sitemonitor.service.certsweep.CertSweepStatus(now, false,
                List.of(), null, List.of()));
        WatchdogProbes.Probe idle = probes.certSweep(NOW);
        // açık koşu yok → kilit okunmaz
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never()).query(eq(com.sitemonitor.service.certsweep.CertSweepView.SQL_CERT_LOCK),
                any(org.springframework.jdbc.core.RowMapper.class), eq("cert-check"));
        assertThat(idle.facts()).containsEntry("chunked_run_age_s", null).containsEntry("oldest_pending_chunk_s", null)
                .containsEntry("abandoned_runs_24h", 0L);
        assertThat(WatchdogMessages.detail(WatchdogCheck.CERT_SWEEP, idle.facts(), false)).contains("açık tur yok");

        when(store.status(WatchdogProbes.CERT_CHUNK_STATUS_RUNS)).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));
        WatchdogProbes.Probe bad = probes.certSweep(NOW);
        assertThat(bad.result()).isEqualTo(before.result());
        assertThat(bad.facts()).containsEntry("chunked", true).containsEntry("chunked_status", "unreadable");
        assertThat(WatchdogMessages.detail(WatchdogCheck.CERT_SWEEP, bad.facts(), false)).endsWith("Parçalı tarama açık; tur durumu okunamadı.");
    }

    /** {@code cert-check} kilit satırı: verilen sahip, süresi uzak gelecekte. */
    @SuppressWarnings("unchecked")
    void stubCertLock(String holder) {
        when(jdbc.query(eq(com.sitemonitor.service.certsweep.CertSweepView.SQL_CERT_LOCK), any(org.springframework.jdbc.core.RowMapper.class),
                eq("cert-check"))).thenReturn(List.<Object>of((Object) new String[]{holder, "2099-01-01T00:00:00"}));
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
