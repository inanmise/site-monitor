package com.sitemonitor.service;

import com.sitemonitor.model.RetentionRun;
import com.sitemonitor.model.SystemHeartbeat;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.RetentionRunRepository;
import com.sitemonitor.repository.SystemHeartbeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sistem Sağlığı kartlarının durum üreticileri — {@link ExtendedHealthServiceTest} heartbeat/SMTP
 * istatistiklerini kapsıyordu; bu sınıf kalan yüzeyleri (alan adı kaynağı, ağ kesintisi, gece
 * temizliği, heartbeat zaman çizelgesi, hata yolları, dağıtım heartbeat kancası) pinler.
 *
 * <p>2026-09-11: CI'da jacoco sınıf tabanı (0,37) 0,36 ile düştü — sınırdaki kapsam ortama göre
 * oynuyordu. Eşik indirilmedi; kapsanmayan bloklar doğrudan test edildi.
 * Zamanlar hep {@code LocalDateTime.now(UTC)}'ye GÖRELİ — runner UTC / yerel Europe/Istanbul farkı
 * sonucu değiştirmez.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExtendedHealthServiceStatusTest {

    @Mock NotificationLogRepository notificationLogRepo;
    @Mock SystemHeartbeatRepository heartbeatRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock RdapDomainClient rdapDomainClient;
    @Mock RetentionRunRepository retentionRunRepo;
    @Mock AppSettingsService appSettings;

    private ExtendedHealthService service;

    @BeforeEach
    void setUp() {
        service = new ExtendedHealthService(notificationLogRepo, heartbeatRepo, alertEventRepo, jdbcTemplate,
                rdapDomainClient, retentionRunRepo, appSettings);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
    }

    // ── Alan adı süresi kaynağı ───────────────────────────────────────────────

    @Test
    @DisplayName("getDomainExpirySourceStatus: RDAP istemcisinin durumu olduğu gibi geçer")
    void domainExpirySource_passThrough() {
        when(rdapDomainClient.getSourceStatus()).thenReturn(Map.of("source", "RDAP", "alarm", false));
        assertThat(service.getDomainExpirySourceStatus()).containsEntry("source", "RDAP").containsEntry("alarm", false);
    }

    @Test
    @DisplayName("getDomainExpirySourceStatus: istemci patlarsa NONE + alarm + gerekçe (kart boş kalmaz)")
    void domainExpirySource_clientThrows_alarm() {
        when(rdapDomainClient.getSourceStatus()).thenThrow(new IllegalStateException("rdap down"));
        Map<String, Object> m = service.getDomainExpirySourceStatus();
        assertThat(m).containsEntry("source", "NONE").containsEntry("alarm", true).containsEntry("reason", "rdap down");
    }

    @Test
    @DisplayName("getDomainExpirySourceStatus: mesajsız istisnada gerekçe sınıf adıdır")
    void domainExpirySource_noMessage_usesClassName() {
        when(rdapDomainClient.getSourceStatus()).thenThrow(new NullPointerException());
        assertThat(service.getDomainExpirySourceStatus()).containsEntry("reason", "NullPointerException");
    }

    // ── Ağ kesintisi ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("getNetworkStatus: SchedulerService yoksa (test/izole bağlam) yalnız alarm=false")
    void networkStatus_noScheduler() {
        Map<String, Object> m = service.getNetworkStatus();
        assertThat(m).containsExactly(Map.entry("alarm", false));
    }

    @Test
    @DisplayName("getNetworkStatus: SchedulerService'in kesinti alanları birebir aktarılır")
    void networkStatus_withScheduler() {
        SchedulerService sched = mock(SchedulerService.class);
        when(sched.isNetworkOutageActive()).thenReturn(true);
        when(sched.getNetworkLastTotal()).thenReturn(40);
        when(sched.getNetworkLastNetworkErrors()).thenReturn(12);
        ReflectionTestUtils.setField(service, "schedulerService", sched);

        Map<String, Object> m = service.getNetworkStatus();
        assertThat(m).containsEntry("alarm", true)
                .containsEntry("last_total", 40)
                .containsEntry("last_network_errors", 12)
                .containsKeys("detected_at", "resolved_at", "last_error_rate", "threshold", "min_errors",
                        "pending_alert_email", "pending_resolved_email");
    }

    // ── Heartbeat kaydı + dağıtım kancası ────────────────────────────────────

    @Test
    @DisplayName("recordHeartbeat: kayıt atılır; dağıtım kaydı touch() ve tablo defteri tick() aynı ritimde")
    void recordHeartbeat_touchesDeploymentAndRegistry() {
        DeploymentHistoryService deployments = mock(DeploymentHistoryService.class);
        SchemaTableRegistryService registry = mock(SchemaTableRegistryService.class);
        ReflectionTestUtils.setField(service, "deploymentHistory", deployments);
        ReflectionTestUtils.setField(service, "schemaRegistry", registry);

        service.recordHeartbeat();

        verify(heartbeatRepo).save(any(SystemHeartbeat.class));
        verify(deployments).touch();
        verify(registry).tick();
    }

    @Test
    @DisplayName("recordHeartbeat: DB yazımı patlarsa yutulur (scheduler ölmez), kancalar çağrılmaz")
    void recordHeartbeat_saveThrows_swallowed() {
        DeploymentHistoryService deployments = mock(DeploymentHistoryService.class);
        ReflectionTestUtils.setField(service, "deploymentHistory", deployments);
        when(heartbeatRepo.save(any(SystemHeartbeat.class))).thenThrow(new RuntimeException("db down"));

        service.recordHeartbeat();   // istisna sızmaz

        verify(deployments, never()).touch();
    }

    @Test
    @DisplayName("getHeartbeatStatus: repo patlarsa alarm + error alanı (kart 'bilinmiyor' yerine alarm gösterir)")
    void heartbeatStatus_repoThrows_alarmWithError() {
        when(heartbeatRepo.findTop5ByOrderByRecordedAtDesc()).thenThrow(new RuntimeException("timeout"));
        Map<String, Object> m = service.getHeartbeatStatus();
        assertThat(m).containsEntry("alarm", true).containsEntry("minutes_since", -1).containsEntry("error", "timeout");
    }

    // ── Gece temizliği ───────────────────────────────────────────────────────

    private static RetentionRun run(LocalDateTime startedAtUtc, int failed) {
        RetentionRun r = new RetentionRun();
        r.setStartedAt(startedAtUtc.toString());
        r.setTotalDeleted(120L);
        r.setFailedCount(failed);
        r.setDurationMs(5000L);
        r.setDryRun(false);
        return r;
    }

    @Test
    @DisplayName("getCleanupStatus: hiç çalışmamış → never_run, alarm YOK (yeni kurulum normal)")
    void cleanup_neverRun_noAlarm() {
        when(retentionRunRepo.findFirstByDryRunFalseOrderByStartedAtDesc()).thenReturn(Optional.empty());
        Map<String, Object> m = service.getCleanupStatus();
        assertThat(m).containsEntry("never_run", true).containsEntry("alarm", false).containsEntry("hours_since", -1);
    }

    @Test
    @DisplayName("getCleanupStatus: 2 saat önce başarılı koşum → alarm yok, alanlar dolu")
    void cleanup_recentSuccess_noAlarm() {
        when(retentionRunRepo.findFirstByDryRunFalseOrderByStartedAtDesc())
                .thenReturn(Optional.of(run(LocalDateTime.now(ZoneOffset.UTC).minusHours(2), 0)));
        Map<String, Object> m = service.getCleanupStatus();
        assertThat(m).containsEntry("alarm", false).containsEntry("never_run", false)
                .containsEntry("total_deleted", 120L).containsEntry("failed_count", 0).containsEntry("duration_ms", 5000L);
        assertThat((Long) m.get("hours_since")).isBetween(1L, 3L);
    }

    @Test
    @DisplayName("getCleanupStatus: 40 saattir koşmamış → alarm (günlük cron + bir gece tolerans aşıldı)")
    void cleanup_stale_alarm() {
        when(retentionRunRepo.findFirstByDryRunFalseOrderByStartedAtDesc())
                .thenReturn(Optional.of(run(LocalDateTime.now(ZoneOffset.UTC).minusHours(40), 0)));
        assertThat(service.getCleanupStatus()).containsEntry("alarm", true);
    }

    @Test
    @DisplayName("getCleanupStatus: son koşumda hata sayısı > 0 → alarm; legal hold açıkken alarm YOK")
    void cleanup_failedItems_alarm_unlessHold() {
        when(retentionRunRepo.findFirstByDryRunFalseOrderByStartedAtDesc())
                .thenReturn(Optional.of(run(LocalDateTime.now(ZoneOffset.UTC).minusHours(1), 2)));
        assertThat(service.getCleanupStatus()).containsEntry("alarm", true).containsEntry("hold_active", false);

        when(appSettings.getBoolean(eq("site.monitor.retention.hold-enabled"), anyBoolean())).thenReturn(true);
        assertThat(service.getCleanupStatus()).containsEntry("alarm", false).containsEntry("hold_active", true);
    }

    @Test
    @DisplayName("getCleanupStatus: repo patlarsa alarm üretmez, error alanı taşır")
    void cleanup_repoThrows_noAlarmWithError() {
        when(retentionRunRepo.findFirstByDryRunFalseOrderByStartedAtDesc()).thenThrow(new RuntimeException("db down"));
        Map<String, Object> m = service.getCleanupStatus();
        assertThat(m).containsEntry("alarm", false).containsEntry("error", "db down").containsEntry("hours_since", -1);
    }

    // ── Heartbeat zaman çizelgesi ─────────────────────────────────────────────

    @Test
    @DisplayName("getHeartbeatTimeline(1): 10 dk kovalar; 3 dk önceki (en yeni) heartbeat son KISMİ kovaya düşer, kaybolmaz")
    void timeline_oneDay_tenMinuteBuckets() {
        SystemHeartbeat hb = new SystemHeartbeat();
        hb.setRecordedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(3));   // EN YENİ heartbeat — son (kısmi) kovaya düşmeli
        when(heartbeatRepo.findByRecordedAtAfterOrderByRecordedAtAsc(any())).thenReturn(List.of(hb));

        Map<String, Object> out = service.getHeartbeatTimeline(1);

        assertThat(out).containsEntry("days", 1).containsEntry("bucket_minutes", 10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");
        assertThat(buckets).hasSizeBetween(144, 145);   // 24 s × 6 kova (+1 kısmi son kova)
        int received = buckets.stream().mapToInt(b -> (Integer) b.get("received")).sum();
        assertThat(received).isEqualTo(1);
        assertThat(buckets.get(0)).containsEntry("expected", 10).containsKey("start");
    }

    @Test
    @DisplayName("getHeartbeatTimeline: son kova KISMİ — expected kalan dakika kadar (10/3 yanlış kaybı üretmez), önceki kovalar tam")
    void timeline_lastBucketIsPartial() {
        when(heartbeatRepo.findByRecordedAtAfterOrderByRecordedAtAsc(any())).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) service.getHeartbeatTimeline(1).get("buckets");
        Map<String, Object> last = buckets.get(buckets.size() - 1);
        int lastExpected = (Integer) last.get("expected");
        assertThat(lastExpected).isBetween(1, 10);
        assertThat(buckets.subList(0, buckets.size() - 1)).allSatisfy(b -> assertThat(b).containsEntry("expected", 10));
        // Son kovanın başlangıcı "şimdi"den en çok 10 dk önce: pencere şimdiye kadar uzanıyor.
        LocalDateTime start = LocalDateTime.parse((String) last.get("start"));
        assertThat(java.time.temporal.ChronoUnit.MINUTES.between(start, LocalDateTime.now(ZoneOffset.UTC))).isBetween(0L, 10L);
    }

    @Test
    @DisplayName("getHeartbeatTimeline: gün sayısı [1,30] aralığına sıkıştırılır, >1 günde 60 dk kova")
    void timeline_clampsDaysAndUsesHourBuckets() {
        when(heartbeatRepo.findByRecordedAtAfterOrderByRecordedAtAsc(any())).thenReturn(List.of());
        assertThat(service.getHeartbeatTimeline(90)).containsEntry("days", 30).containsEntry("bucket_minutes", 60);
        assertThat(service.getHeartbeatTimeline(-4)).containsEntry("days", 1).containsEntry("bucket_minutes", 10);
    }

    // ── SMTP istatistik hata yolu + tablo boyutları ───────────────────────────

    @Test
    @DisplayName("getSmtpStats: günlük sorgusu patlarsa güvenli varsayılanlar (rate 100, alarm yok) + error")
    void smtpStats_repoThrows_safeDefaults() {
        when(notificationLogRepo.countAttemptedSince(anyString())).thenThrow(new RuntimeException("db down"));
        Map<String, Object> m = service.getSmtpStats();
        assertThat(m).containsEntry("total", 0).containsEntry("rate", 100).containsEntry("alarm", false)
                .containsEntry("error", "db down");
    }

    @Test
    @DisplayName("getTableStats: pg_stat_user_tables sorgusu JdbcTemplate'e gider, satırlar olduğu gibi döner")
    void tableStats_delegatesToJdbc() {
        List<Map<String, Object>> rows = List.of(Map.of("table_name", "check_history", "row_count", 42L));
        when(jdbcTemplate.queryForList(anyString())).thenReturn(rows);
        assertThat(service.getTableStats()).isSameAs(rows);
    }
}
