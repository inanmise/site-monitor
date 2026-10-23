package com.sitemonitor.service.metrics;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.DatabaseHealthService;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.service.UserPushService;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import com.sitemonitor.service.watchdog.WatchdogService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Aktarıcı turu (2026-10-18): kaynaklar okunur, gauge'lar kayıt defterine yazılır; okunamayan kaynak ailesini DÜŞÜRÜR ve
 * {@code source.up=0} yazar (sahte 0 / up yok), aktarıcı kapalıyken yalnız {@code enabled=0}; kaybolan izlemenin serisi bir
 * sonraki turda silinir; Genel Bakış alarmları okuyamadıysa izleme durumu yayılmaz; tarama liderliği salt okunur.
 */
class MonitoringMetricsExporterTest {

    SimpleMeterRegistry registry;
    AppSettingsService settings;
    MonitoringMetricsExporter exporter;
    MonitoringOverviewService overview;
    AlertEventRepository alerts;
    CertificateInventoryRepository inventory;
    CertificateService certs;
    DomainCheckRepository domains;
    AlertStormRepository storms;
    TeamRepository teams;
    JdbcTemplate jdbc;
    WatchdogService watchdog;
    ClusterScheduleService cluster;
    DatabaseHealthService database;
    SystemMaintenanceService maintenance;
    UserPushService push;
    Map<String, Object> ov;

    static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = new SimpleMeterRegistry();
        settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        // İzleme başına seriler varsayılan KAPALI (2026-10-18) — bu testler açık hâli de sınar
        when(settings.getBoolean(eq(MetricsExportSettings.KEY_PER_MONITOR), anyBoolean())).thenReturn(true);
        exporter = new MonitoringMetricsExporter(registry, settings);
        exporter.setClock(() -> NOW);
        overview = mock(MonitoringOverviewService.class);
        alerts = mock(AlertEventRepository.class);
        inventory = mock(CertificateInventoryRepository.class);
        certs = mock(CertificateService.class);
        domains = mock(DomainCheckRepository.class);
        storms = mock(AlertStormRepository.class);
        teams = mock(TeamRepository.class);
        jdbc = mock(JdbcTemplate.class);
        watchdog = mock(WatchdogService.class);
        cluster = mock(ClusterScheduleService.class);
        database = mock(DatabaseHealthService.class);
        maintenance = mock(SystemMaintenanceService.class);
        push = mock(UserPushService.class);
        exporter.setOverview(overview);
        exporter.setAlertRepo(alerts);
        exporter.setInventoryRepo(inventory);
        exporter.setCertificateService(certs);
        exporter.setDomainCheckRepo(domains);
        exporter.setStormRepo(storms);
        exporter.setTeamRepo(teams);
        exporter.setJdbc(jdbc);
        exporter.setWatchdog(watchdog);
        exporter.setCluster(cluster);
        exporter.setDatabase(database);
        exporter.setMaintenance(maintenance);
        exporter.setPush(push);

        Team t = new Team(); t.setId(14L); t.setName("Dijital Kanallar"); t.setActive(true);
        when(teams.findAll()).thenReturn(List.of(t));
        when(inventory.findDomainOwners()).thenReturn(List.<Object[]>of(new Object[]{"api.example.com", 14L, null}));
        ov = new LinkedHashMap<>();
        ov.put("types", List.of(new LinkedHashMap<>(Map.of("type", "http"))));
        ov.put("monitors", new ArrayList<>(List.of(monitor(1, "Ana sayfa", "up"), monitor(2, "Ödeme", "down"))));
        when(overview.build(anyString(), any(), anyBoolean(), anyInt())).thenAnswer(i -> ov);
        when(alerts.findOpenMetricRows(any())).thenReturn(List.<Object[]>of(
                new Object[]{5L, "https://pay.example.com", "HTTP_DOWN", "CRITICAL", false, 14L, "2026-10-18T07:00:00", null}));
        CertificateDto c = new CertificateDto(); c.setDomain("api.example.com"); c.setDaysRemaining(20); c.setAlertLevel("warning"); c.setTeamId(14L);
        when(certs.getAllLatest()).thenReturn(List.of(c));
        DomainCheck dc = new DomainCheck(); dc.setMonitorId(9L); dc.setDaysRemaining(40);
        when(domains.findLatestPerMonitor()).thenReturn(List.of(dc));
        AlertStorm s = new AlertStorm(); s.setTeamId(14L); s.setMemberCount(4);
        when(storms.findByResolvedFalse()).thenReturn(List.of(s));
        when(jdbc.query(eq(MonitoringMetricsExporter.SQL_NOTIFY_EMAIL), any(RowMapper.class), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{"email", "INITIAL", "SENT", 3L}));
        when(jdbc.query(eq(MonitoringMetricsExporter.SQL_NOTIFY_WEBHOOK), any(RowMapper.class), anyString())).thenReturn(List.of());
        when(jdbc.query(eq(MonitoringMetricsExporter.SQL_PUSH_24H), any(RowMapper.class), anyString()))
                .thenReturn(List.<Object[]>of(new Object[]{"SENT", 2L}));
        when(jdbc.queryForObject(eq(MonitoringMetricsExporter.SQL_PUSH_PENDING), eq(Long.class))).thenReturn(1L);
        when(jdbc.queryForObject(eq(MonitoringMetricsExporter.SQL_PUSH_STUCK), eq(Long.class), anyString(), anyString())).thenReturn(0L);
        when(jdbc.query(eq(MonitoringMetricsExporter.SQL_LOCKS), any(RowMapper.class)))
                .thenReturn(List.<Object[]>of(new Object[]{"cert-check", "2026-10-18T08:05:00"}));
        when(push.enabled()).thenReturn(true);
        when(push.healthSnapshot()).thenReturn(Map.of("circuit_open", false));
        when(watchdog.publicStatus(any())).thenReturn(Map.of("status", "UP", "evaluated_at", "2026-10-18T07:59:00",
                "checks", List.of(Map.of("key", "sweep_leader", "status", "OK"), Map.of("key", "database", "status", "PROBLEM", "severity", "CRITICAL"))));
        when(cluster.lease("sweep-leader")).thenReturn(new ClusterScheduleService.Lease("pod-a", NOW.toEpochMilli() + 60_000));
        when(cluster.owner()).thenReturn("pod-a");
        when(database.current()).thenReturn(new DatabaseHealthService.Snapshot(Map.of(), "UP", NOW.toEpochMilli()));
        when(maintenance.clientBlock()).thenReturn(Map.of("state", "none"));
    }

    static Map<String, Object> monitor(long id, String name, String status) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "http");
        r.put("id", id);
        r.put("name", name);
        r.put("target", "https://m" + id + ".example.com");
        r.put("team_id", 14L);
        r.put("active", true);
        r.put("deleted", false);
        r.put("status", status);
        r.put("last_checked_at", "2026-10-18T07:58:00");
        r.put("checks_window", 10L);
        r.put("failed_window", 0L);
        r.put("open_alerts", 0);
        return r;
    }

    double gauge(String name, String... tags) {
        Gauge g = registry.find(name).tags(tags).gauge();
        assertThat(g).as("gauge yok: " + name + " " + String.join(",", tags)).isNotNull();
        return g.value();
    }

    boolean exists(String name, String... tags) {
        return registry.find(name).tags(tags).gauge() != null;
    }

    @Test
    @DisplayName("tam tur: izleme, alarm, sertifika, alan adı, fırtına, bildirim, push, kendi sağlığı, lider, kilit, veritabanı, bakım")
    void fullRefresh() {
        MonitoringMetricsExporter.Status s = exporter.refresh();
        assertThat(s.enabled()).isTrue();
        assertThat(gauge(MonitoringMetricNames.MONITOR_STATE, "monitor_id", "2")).isEqualTo(2);
        assertThat(gauge(MonitoringMetricNames.MONITORS, "type", "http", "state", "up")).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.ALERTS_OPEN_BY_LEVEL, "level", "CRITICAL")).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.CERT_DAYS, "domain", "api.example.com")).isEqualTo(20);
        assertThat(gauge(MonitoringMetricNames.STORMS_ACTIVE_ALL)).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.NOTIFICATIONS, "channel", "email", "status", "sent")).isEqualTo(3);
        assertThat(gauge(MonitoringMetricNames.PUSH_PENDING)).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.PUSH_ENABLED)).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.WATCHDOG_STATUS)).isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.WATCHDOG_CHECK, "check", "database")).isEqualTo(3);
        assertThat(gauge(MonitoringMetricNames.WATCHDOG_CHECK, "check", "push_outbox")).as("değerlendirilmemiş").isEqualTo(4);
        assertThat(gauge(MonitoringMetricNames.SWEEP_LEADER)).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.SWEEP_LEASE)).isEqualTo(60);
        assertThat(gauge(MonitoringMetricNames.SCHEDULER_LOCK, "name", "cert-check")).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.DATABASE_STATUS)).isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.SYSTEM_MAINTENANCE)).isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_ENABLED)).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_LAST_REFRESH)).isEqualTo(NOW.getEpochSecond());
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "alerts")).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SERIES, "family", "sitemonitor_monitor_state")).isEqualTo(2);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_CAPPED, "family", "monitors")).isEqualTo(0);
        assertThat(s.entities()).containsEntry("monitors", 2).containsEntry("certificates", 1).containsEntry("alerts", 1);
        assertThat(s.seriesTotal()).isPositive();
        assertThat(exporter.lastStatus()).isSameAs(s);
        // Tarama liderliği SALT OKUNUR: kira alınmaz
        verify(cluster, never()).acquireLease(anyString(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("okunamayan kaynak: ailesi düşer (sahte 0 yok), source.up=0; diğer aileler yayılmaya devam eder")
    void failedSourceDropsFamily() {
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.ALERTS_OPEN_BY_LEVEL, "level", "CRITICAL")).isTrue();
        when(alerts.findOpenMetricRows(any())).thenThrow(new RuntimeException("db down"));
        when(certs.getAllLatest()).thenThrow(new RuntimeException("db down"));
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.ALERTS_OPEN_BY_LEVEL, "level", "CRITICAL")).as("alarm sayısı sahte 0 değil, yok").isFalse();
        assertThat(exists(MonitoringMetricNames.CERTS)).isFalse();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "alerts")).isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "certificates")).isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.MONITOR_STATE, "monitor_id", "1")).isEqualTo(0);
    }

    @Test
    @DisplayName("Genel Bakış alarmları okuyamadıysa izleme durumu yayılmaz (düşük izleme 'up' görünmesin)")
    void overviewWithoutAlertsIsNotExported() {
        ov.put("alerts_unavailable", true);
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE)).isFalse();
        assertThat(exists(MonitoringMetricNames.MONITORS)).isFalse();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "overview")).isEqualTo(0);
    }

    @Test
    @DisplayName("alan adı izlemeleri okunamadıysa alan adı kovaları yazılmaz (sahte '0 alan adı' yok), domains kaynağı 0")
    void unreadableDomainMonitorsDropDomainFamilies() {
        ov.put("types", List.of(new LinkedHashMap<>(Map.of("type", "http")),
                new LinkedHashMap<>(Map.of("type", "domain", "unavailable", true))));
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.DOMAINS_EXPIRY)).isFalse();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "domains")).isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "monitors:domain")).isEqualTo(0);
        assertThat(exists(MonitoringMetricNames.MONITORS, "type", "http")).isTrue();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "monitors:http"))
                .as("okunan tür kaynak satırı 1 yazar (pano 'tür okunamadı' kararını pod'lar arası max ile verir)").isEqualTo(1);
    }

    @Test
    @DisplayName("kaybolan izlemenin serisi sonraki turda silinir")
    @SuppressWarnings("unchecked")
    void removedMonitorDisappears() {
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE, "monitor_id", "2")).isTrue();
        ((List<Object>) ov.get("monitors")).remove(1);
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE, "monitor_id", "2")).isFalse();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE, "monitor_id", "1")).isTrue();
    }

    @Test
    @DisplayName("aktarıcı kapalı: yalnız enabled=0; izleme başına kapalı: izleme serisi yok, toplamlar var")
    void switches() {
        exporter.refresh();
        when(settings.getBoolean(eq(MetricsExportSettings.KEY_PER_MONITOR), anyBoolean())).thenReturn(false);
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE)).isFalse();
        assertThat(exists(MonitoringMetricNames.MONITORS, "state", "up")).isTrue();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_PER_MONITOR)).isEqualTo(0);
        when(settings.getBoolean(eq(MetricsExportSettings.KEY_ENABLED), anyBoolean())).thenReturn(false);
        MonitoringMetricsExporter.Status s = exporter.refresh();
        assertThat(s.enabled()).isFalse();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_ENABLED)).isEqualTo(0);
        long others = registry.getMeters().stream().map(Meter::getId).map(Meter.Id::getName)
                .filter(n -> n.startsWith("sitemonitor.") && !n.equals(MonitoringMetricNames.EXPORTER_ENABLED)
                        && !n.equals(MonitoringMetricNames.EXPORTER_PER_MONITOR)).count();
        assertThat(others).isZero();
    }

    @Test
    @DisplayName("takımlar okunamazsa son başarılı dizin kullanılır (adlar sabit kalır)")
    void teamsFallBackToLastDirectory() {
        exporter.refresh();
        when(teams.findAll()).thenThrow(new RuntimeException("db"));
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE, "team", "Dijital Kanallar")).isTrue();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "teams")).isEqualTo(0);
    }

    @Test
    @DisplayName("lider başka pod'da ya da kira dolmuş: bu pod 0; kira satırı yoksa lider ölçümü yazılmaz")
    void leaderReadOnly() {
        when(cluster.lease("sweep-leader")).thenReturn(new ClusterScheduleService.Lease("pod-b", NOW.toEpochMilli() + 30_000));
        exporter.refresh();
        assertThat(gauge(MonitoringMetricNames.SWEEP_LEADER)).isEqualTo(0);
        when(cluster.lease("sweep-leader")).thenReturn(new ClusterScheduleService.Lease("pod-a", NOW.toEpochMilli() - 90_000));
        exporter.refresh();
        assertThat(gauge(MonitoringMetricNames.SWEEP_LEADER)).isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.SWEEP_LEASE)).isEqualTo(-90);
        when(cluster.lease("sweep-leader")).thenReturn(null);
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.SWEEP_LEADER)).isFalse();
    }

    @Test
    @DisplayName("değerler GC'den sonra da okunur (gauge değer nesnesini zayıf tutar — tutucular aktarıcıda güçlü)")
    void valuesSurviveGarbageCollection() throws InterruptedException {
        exporter.refresh();
        for (int i = 0; i < 5; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertThat(gauge(MonitoringMetricNames.MONITOR_STATE, "monitor_id", "2")).isEqualTo(2);
        assertThat(gauge(MonitoringMetricNames.CERT_DAYS, "domain", "api.example.com")).isEqualTo(20);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_LAST_REFRESH)).isEqualTo(NOW.getEpochSecond());
    }

    @Test
    @DisplayName("var olan seri her turda aynı gauge kalır, yalnız değeri güncellenir (kazıma arası boşluk yok)")
    @SuppressWarnings("unchecked")
    void seriesKeepTheirMeterAcrossRefreshes() {
        exporter.refresh();
        Gauge before = registry.find(MonitoringMetricNames.MONITOR_STATE).tags("monitor_id", "2").gauge();
        ((List<Object>) ov.get("monitors")).set(1, monitor(2, "Ödeme", "up"));
        exporter.refresh();
        Gauge after = registry.find(MonitoringMetricNames.MONITOR_STATE).tags("monitor_id", "2").gauge();
        assertThat(after).isSameAs(before);
        assertThat(after.value()).isEqualTo(0);
    }

    @Test
    @DisplayName("yarım okunan kaynak satır bırakmaz: push sağlık okuması patlarsa push aileleri hiç yazılmaz")
    void partialSourceWritesNothing() {
        when(push.healthSnapshot()).thenThrow(new RuntimeException("push"));
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.PUSH_PENDING)).isFalse();
        assertThat(exists(MonitoringMetricNames.PUSH_DELIVERIES)).isFalse();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "push")).isEqualTo(0);
        when(watchdog.publicStatus(any())).thenThrow(new RuntimeException("wd"));
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.WATCHDOG_STATUS)).isFalse();
        assertThat(exists(MonitoringMetricNames.WATCHDOG_CHECK)).isFalse();
    }

    @Test
    @DisplayName("belirteç yok: aktarıcı hiçbir kaynağı okumaz (veritabanı yükü yok), iş serisi yazmaz; iş parçacığı başlamaz")
    void noTokenNoWork() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        MonitoringMetricsExporter off = new MonitoringMetricsExporter(r, settings, false);
        off.setOverview(overview);
        off.setAlertRepo(alerts);
        off.setJdbc(jdbc);
        assertThat(off.tokenConfigured()).isFalse();
        MonitoringMetricsExporter.Status s = off.refresh();
        assertThat(s.enabled()).isFalse();
        verifyNoInteractions(overview, alerts, jdbc);
        assertThat(r.find(MonitoringMetricNames.MONITOR_STATE).gauge()).isNull();
        assertThat(r.find(MonitoringMetricNames.EXPORTER_ENABLED).gauge().value()).isEqualTo(0);
        off.start();   // belirteç yoksa zamanlayıcı kurulmaz — tekrar çağrı da güvenli
        off.start();
        off.stop();
    }

    @Test
    @DisplayName("tazelik: tur 3 × aralıktan (en az 3 dk) eskiyse yalnız aktarıcının tazelik ölçümleri görünür; hiç tur yoksa da")
    void staleExporterHidesBusinessValues() {
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        exporter.setClock(now::get);
        assertThat(exporter.fresh()).as("hiç tur yok").isFalse();
        assertThat(exporter.visibleNames()).isEqualTo(MonitoringMetricNames.STALE_VISIBLE);
        exporter.refresh();
        assertThat(exporter.fresh()).isTrue();
        assertThat(exporter.visibleNames()).isNull();
        now.set(NOW.plusSeconds(179));
        assertThat(exporter.fresh()).isTrue();
        now.set(NOW.plusSeconds(181));
        assertThat(exporter.fresh()).as("varsayılan 60 sn aralık → eşik 3 dk").isFalse();
        assertThat(exporter.visibleNames()).containsExactlyInAnyOrder("sitemonitor_exporter_enabled", "sitemonitor_exporter_per_monitor",
                "sitemonitor_exporter_last_refresh_timestamp_seconds", "sitemonitor_exporter_refresh_duration_seconds");
        when(settings.getInt(eq(MetricsExportSettings.KEY_REFRESH_SECONDS), anyInt())).thenReturn(300);
        assertThat(exporter.fresh()).as("300 sn aralık → eşik 15 dk").isTrue();
        now.set(NOW.plusSeconds(901));
        assertThat(exporter.fresh()).isFalse();
    }

    @Test
    @DisplayName("çok pod ölçümleri (R2b): kaybedilen kilit, liderlik geçişleri, push kira hataları, saat farkı (pod) + gelen kutusu "
            + "(küme); gelen kutusu okunamazsa seri düşer (sahte 0 yok), saat farkı ölçülmediyse seri yok")
    void multiPodRuntimeMeters() {
        com.sitemonitor.service.schedule.ClusterLockService lock = mock(com.sitemonitor.service.schedule.ClusterLockService.class);
        when(lock.lostCount()).thenReturn(2L);
        SweepLeadershipCounter leadership = new SweepLeadershipCounter();
        leadership.onLeadershipChanged(new com.sitemonitor.service.schedule.SweepLeadershipChangedEvent(true, "pod-a", 1L, "GAINED"));
        leadership.onLeadershipChanged(new com.sitemonitor.service.schedule.SweepLeadershipChangedEvent(false, "pod-a", 2L, "LOST"));
        leadership.onLeadershipChanged(new com.sitemonitor.service.schedule.SweepLeadershipChangedEvent(true, "pod-a", 3L, "GAINED"));
        com.sitemonitor.service.monitoring.ManualEvalInbox inbox = mock(com.sitemonitor.service.monitoring.ManualEvalInbox.class);
        when(inbox.depth()).thenReturn(3L);
        SystemMetricSampler sampler = mock(SystemMetricSampler.class);
        when(sampler.lastClockSkewMs()).thenReturn(-1_500L);
        when(push.leaseRenewFailures()).thenReturn(new LinkedHashMap<>(Map.of("error", 1L, "lost", 0L)));
        exporter.setLockService(lock);
        exporter.setLeadership(leadership);
        exporter.setInbox(inbox);
        exporter.setSampler(sampler);

        exporter.refresh();
        assertThat(gauge(MonitoringMetricNames.CLUSTER_LOCK_LOST)).isEqualTo(2);
        assertThat(gauge(MonitoringMetricNames.SWEEP_LEADER_TRANSITIONS, "reason", "GAINED")).isEqualTo(2);
        assertThat(gauge(MonitoringMetricNames.SWEEP_LEADER_TRANSITIONS, "reason", "LOST")).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.SWEEP_LEADER_TRANSITIONS, "reason", "RELEASED")).as("sabit etiket kümesi").isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.PUSH_LEASE_RENEW_FAILED, "reason", "error")).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.PUSH_LEASE_RENEW_FAILED, "reason", "lost")).isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.CLOCK_SKEW)).isEqualTo(-1.5);
        assertThat(gauge(MonitoringMetricNames.EVAL_INBOX_DEPTH)).isEqualTo(3);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "eval_inbox")).isEqualTo(1);

        when(inbox.depth()).thenThrow(new RuntimeException("tablo yok"));
        when(sampler.lastClockSkewMs()).thenReturn(null);
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.EVAL_INBOX_DEPTH)).as("okunamadı — sahte 0 değil").isFalse();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "eval_inbox")).isEqualTo(0);
        assertThat(exists(MonitoringMetricNames.CLOCK_SKEW)).as("ölçülmedi — seri yok").isFalse();
        assertThat(gauge(MonitoringMetricNames.CLUSTER_LOCK_LOST)).isEqualTo(2);
    }

    @Test
    @DisplayName("açık alarm okuma tavanı aşılırsa alarm aileleri dürüstçe düşer")
    void openAlertCap() {
        List<Object[]> many = new ArrayList<>();
        for (int i = 0; i <= MonitoringMetricsExporter.OPEN_ALERT_READ_CAP; i++) many.add(new Object[]{(long) i, "d", "HTTP_DOWN", "HIGH", false, 14L, "2026-10-18T07:00:00", null});
        when(alerts.findOpenMetricRows(any())).thenReturn(many);
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.ALERTS_OPEN_BY_LEVEL)).isFalse();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "alerts")).isEqualTo(0);
    }
}
