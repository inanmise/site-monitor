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

    // ── Çok pod R3-1 (2026-10-23, paket B, plan 3e §3.6): kurum geneli ailelerin yerleşimi + değerlendirme durumu ölçümleri ──

    static final String SELF = com.sitemonitor.service.SchedulerService.instanceId();

    com.sitemonitor.service.health.ClusterHealthStore placement(boolean leader, java.util.Optional<com.sitemonitor.service.health.ClusterHealthStore.Observation> obs) {
        com.sitemonitor.service.SchedulerService scheduler = mock(com.sitemonitor.service.SchedulerService.class);
        when(scheduler.sweepLeaderHint()).thenReturn(leader);
        com.sitemonitor.service.health.ClusterHealthStore store = mock(com.sitemonitor.service.health.ClusterHealthStore.class);
        when(store.read(com.sitemonitor.service.health.ClusterHealthStore.OBS_METRICS_ORG)).thenReturn(obs);
        when(store.publish(anyString(), anyString(), anyLong(), anyString(), anyString()))
                .thenReturn(com.sitemonitor.service.health.ClusterHealthStore.Publish.WRITTEN);
        exporter.setScheduler(scheduler);
        exporter.setClusterHealth(store);
        return store;
    }

    static java.util.Optional<com.sitemonitor.service.health.ClusterHealthStore.Observation> observation(String by, long observedMs) {
        return java.util.Optional.of(new com.sitemonitor.service.health.ClusterHealthStore.Observation(by, observedMs, "{}", "x"));
    }

    @Test
    @DisplayName("orgFamiliesPlacement: lider + başka pod'un TAZE gözlemi → yalnız POD aileleri (kurum kaynakları hiç okunmaz, "
            + "KÜME aileleri ve kaynak satırları yok — yok ≠ 0); lider bayrağı / veritabanı / push devre kesicisi var; gözlem yazılmaz")
    void orgFamiliesPlacement_leaderWithFreshOtherExporter_onlyPodFamilies() {
        com.sitemonitor.service.health.ClusterHealthStore store =
                placement(true, observation("sitemonitor-other-0000000b", NOW.toEpochMilli() - 30_000));
        MonitoringMetricsExporter.Status s = exporter.refresh();

        assertThat(s.orgFamilies()).isFalse();
        assertThat(s.entities()).as("bu pod kurum ailesi saymadı — '0 izleme' değil, boş").isEmpty();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_ORG_FAMILIES)).isEqualTo(0);
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) {
            if (d.scope() != MonitoringMetricNames.Scope.POD) {
                assertThat(exists(d.name())).as("POD dışı aile yayılmamalı: " + d.name()).isFalse();
            }
        }
        assertThat(gauge(MonitoringMetricNames.SWEEP_LEADER)).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.DATABASE_STATUS)).isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.PUSH_CIRCUIT)).isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_ENABLED)).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_LAST_REFRESH)).isEqualTo(NOW.getEpochSecond());
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "cluster")).isEqualTo(1);
        // Kurum kaynaklarının source_up satırları YOK (pano guard() başka pod'un okuyamadığını sahte 0 yapmasın); tavan da yok
        for (String src : List.of("overview", "alerts", "certificates", "push", "notifications", "watchdog", "teams", "eval_inbox", "maintenance")) {
            assertThat(exists(MonitoringMetricNames.EXPORTER_SOURCE, "source", src)).as("kaynak satırı: " + src).isFalse();
        }
        assertThat(exists(MonitoringMetricNames.EXPORTER_CAPPED)).isFalse();
        // Kurum kaynakları hiç okunmaz (liderin yükü kalkar)
        verifyNoInteractions(overview, alerts, inventory, certs, domains, storms, teams, jdbc, watchdog, maintenance);
        verify(store, never()).publish(anyString(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("orgFamiliesPlacement: gözlem eski (> 2,5 × aralık) / kendi gözlemi / yok (tek pod, 20.167.0 komşu) / okunamadı → lider de "
            + "kurum ailelerini yayar; lider gözlem YAZMAZ")
    void orgFamiliesPlacement_leaderFallsBackToExporting() {
        long now = NOW.toEpochMilli();
        java.util.List<java.util.Optional<com.sitemonitor.service.health.ClusterHealthStore.Observation>> cases = List.of(
                observation("sitemonitor-other-0000000b", now - 151_000),     // eski: varsayılan 60 sn × 2,5 = 150 sn
                observation(SELF, now - 10_000),                              // kendi (eski takipçi gözlemi)
                java.util.Optional.empty());                                  // tek pod / 20.167.0 komşu
        for (var obs : cases) {
            com.sitemonitor.service.health.ClusterHealthStore store = placement(true, obs);
            MonitoringMetricsExporter.Status s = exporter.refresh();
            assertThat(s.orgFamilies()).as(String.valueOf(obs)).isTrue();
            assertThat(gauge(MonitoringMetricNames.EXPORTER_ORG_FAMILIES)).isEqualTo(1);
            assertThat(gauge(MonitoringMetricNames.MONITOR_STATE, "monitor_id", "2")).isEqualTo(2);
            assertThat(gauge(MonitoringMetricNames.SWEEP_LEASE)).isEqualTo(60);
            assertThat(s.entities()).containsEntry("monitors", 2);
            verify(store, never()).publish(anyString(), anyString(), anyLong(), anyString(), anyString());
        }
        // gözlem okunamadı (tablo yok / DB) → yine yayar (veri boşluğu yerine çift yayın)
        com.sitemonitor.service.health.ClusterHealthStore store = placement(true, java.util.Optional.empty());
        when(store.read(com.sitemonitor.service.health.ClusterHealthStore.OBS_METRICS_ORG)).thenThrow(new RuntimeException("tablo yok"));
        assertThat(exporter.refresh().orgFamilies()).isTrue();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE)).isTrue();
        // 150 sn sınırı kendi aralık ayarını izler: 300 sn aralık → 750 sn içindeki gözlem taze
        placement(true, observation("sitemonitor-other-0000000b", now - 600_000));
        when(settings.getInt(eq(MetricsExportSettings.KEY_REFRESH_SECONDS), anyInt())).thenReturn(300);
        assertThat(exporter.refresh().orgFamilies()).isFalse();
    }

    @Test
    @DisplayName("orgFamiliesPlacement: takipçi her zaman yayar ve tam turdan SONRA gözlem yazar (kendi kimliği, tur anı); bir kaynak "
            + "okunamadıysa yazmaz (lider yaymayı sürdürsün); kapalı aktarıcı gözlem okumaz / yazmaz")
    void orgFamiliesPlacement_followerPublishesAfterCompleteExport() {
        com.sitemonitor.service.health.ClusterHealthStore store =
                placement(false, observation("sitemonitor-other-0000000b", NOW.toEpochMilli()));
        MonitoringMetricsExporter.Status s = exporter.refresh();
        assertThat(s.orgFamilies()).isTrue();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE)).isTrue();
        verify(store, never()).read(anyString());
        verify(store).publish(eq(com.sitemonitor.service.health.ClusterHealthStore.OBS_METRICS_ORG), eq(SELF),
                eq(NOW.toEpochMilli()), contains("\"series\":"), eq("2026-10-18T08:00:00"));

        clearInvocations(store);
        when(certs.getAllLatest()).thenThrow(new RuntimeException("db"));
        assertThat(exporter.refresh().orgFamilies()).isTrue();
        verify(store, never()).publish(anyString(), anyString(), anyLong(), anyString(), anyString());

        clearInvocations(store);
        when(settings.getBoolean(eq(MetricsExportSettings.KEY_ENABLED), anyBoolean())).thenReturn(false);
        exporter.refresh();
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("orgFamiliesPlacement: lider POD-only'ye geçince önceki turun kurum serileri silinir; gözlem eskiyince bir sonraki "
            + "turda geri gelir (boşluk en çok 2,5 × aralık + 1 tur)")
    void orgFamiliesPlacement_switchesBothWays() {
        java.util.concurrent.atomic.AtomicReference<Instant> now = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        exporter.setClock(now::get);
        placement(true, java.util.Optional.empty());
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE)).isTrue();
        placement(true, observation("sitemonitor-other-0000000b", NOW.toEpochMilli()));
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE)).isFalse();
        assertThat(exists(MonitoringMetricNames.ALERTS_OPEN_BY_LEVEL)).isFalse();
        now.set(NOW.plusSeconds(151));
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE)).as("takipçinin gözlemi eskidi → lider yeniden yayar").isTrue();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_ORG_FAMILIES)).isEqualTo(1);
    }

    @Test
    @DisplayName("liderlik ipucu / küme deposu yok (elle kurulum, eski davranış) → her şey yayılır, karar okunmaz")
    void orgFamiliesPlacement_noSchedulerMeansExport() {
        MonitoringMetricsExporter.Status s = exporter.refresh();
        assertThat(s.orgFamilies()).isTrue();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_ORG_FAMILIES)).isEqualTo(1);
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE)).isTrue();
    }

    // ── R3-1 inceleme düzeltmeleri (FX-OBS, 2026-10-23): M1 kapanış / kapanış işareti / canlılık, L3 bitiş anı, L1 ──────────

    static java.util.Optional<com.sitemonitor.service.health.ClusterHealthStore.Observation> observation(String by, long observedMs, String payload) {
        return java.util.Optional.of(new com.sitemonitor.service.health.ClusterHealthStore.Observation(by, observedMs, payload, "x"));
    }

    static org.springframework.context.event.ContextClosedEvent closedEvent() {
        return new org.springframework.context.event.ContextClosedEvent(mock(org.springframework.context.ApplicationContext.class));
    }

    @Test
    @DisplayName("orgFamiliesPlacement (M1): ContextClosedEvent'ten sonra takipçi (liderliği bırakan eski lider dahil) kurum ailelerini "
            + "yaymayı sürdürür ama gözlem YAZMAZ — kalan pod'u yalnız POD ailelerine düşürmesin")
    void orgFamiliesPlacement_noPublishAfterContextClosed() {
        com.sitemonitor.service.health.ClusterHealthStore store = placement(false, java.util.Optional.empty());
        exporter.onContextClosed(closedEvent());
        assertThat(exporter.closing()).isTrue();
        MonitoringMetricsExporter.Status s = exporter.refresh();
        assertThat(s.orgFamilies()).isTrue();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE)).as("pod ölene dek yayar (örtüşme, boşluk değil)").isTrue();
        verify(store, never()).publish(anyString(), anyString(), anyLong(), anyString(), anyString());
        // Başka bağlamın kapanışı (alt bağlam) kapanış sayılmaz
        MonitoringMetricsExporter other = new MonitoringMetricsExporter(new SimpleMeterRegistry(), settings);
        other.setContext(mock(org.springframework.context.ApplicationContext.class));
        other.onContextClosed(closedEvent());
        assertThat(other.closing()).isFalse();
    }

    @Test
    @DisplayName("orgFamiliesPlacement (M1): kapanış işareti yalnız satır BU pod'unsa yazılır (satırdakinden 1 ms yeni, \"stopping\":true); "
            + "başka takipçinin satırı ezilmez; işaret zaten varsa / okunamazsa yazılmaz")
    void orgFamiliesPlacement_tombstoneOnlyOverOwnRow() {
        com.sitemonitor.service.health.ClusterHealthStore store = placement(false, observation(SELF, NOW.toEpochMilli() + 5_000));
        exporter.writeTombstone();
        verify(store).publish(eq(com.sitemonitor.service.health.ClusterHealthStore.OBS_METRICS_ORG), eq(SELF),
                eq(NOW.toEpochMilli() + 5_001), contains("\"stopping\":true"), anyString());

        store = placement(false, observation("sitemonitor-other-0000000b", NOW.toEpochMilli() - 1_000));
        exporter.writeTombstone();
        verify(store, never()).publish(anyString(), anyString(), anyLong(), anyString(), anyString());

        store = placement(false, observation(SELF, NOW.toEpochMilli(), "{\"stopping\":true}"));
        exporter.writeTombstone();
        verify(store, never()).publish(anyString(), anyString(), anyLong(), anyString(), anyString());

        store = placement(false, java.util.Optional.empty());
        when(store.read(anyString())).thenThrow(new RuntimeException("db"));
        exporter.writeTombstone();
        verify(store, never()).publish(anyString(), anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("orgFamiliesPlacement (M1): BAŞKA pod'un taze kapanış işareti → lider kurum ailelerini yayar; POD-only lider bir sonraki "
            + "denetimde (orgPlacementChanged) hemen tur koşar; sıradan taze gözlem → beklemeye devam; lider değilse → hemen tur")
    void orgFamiliesPlacement_tombstoneMakesLeaderExportNextCheck() {
        com.sitemonitor.service.health.ClusterHealthStore store =
                placement(true, observation("sitemonitor-other-0000000b", NOW.toEpochMilli() - 1_000));
        assertThat(exporter.refresh().orgFamilies()).isFalse();
        assertThat(exporter.orgPlacementChanged()).as("takipçi yayıyor — bekle").isFalse();

        when(store.read(com.sitemonitor.service.health.ClusterHealthStore.OBS_METRICS_ORG))
                .thenReturn(observation("sitemonitor-other-0000000b", NOW.toEpochMilli(), "{\"at\":\"x\",\"stopping\":true}"));
        assertThat(exporter.orgPlacementChanged()).as("yayan pod kapanıyor → hemen tur").isTrue();
        MonitoringMetricsExporter.Status s = exporter.refresh();
        assertThat(s.orgFamilies()).isTrue();
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE)).isTrue();

        // Liderliği kaybeden POD-only pod da hemen tur koşar (takipçi olarak kurum ailelerini yaysın)
        placement(false, observation("sitemonitor-other-0000000b", NOW.toEpochMilli() - 1_000));
        assertThat(exporter.orgPlacementChanged()).isTrue();
        // Denetim aralığı ≤ 10 sn (kapanan takipçi → lider en geç ~10 sn + bir tur içinde yayar)
        assertThat(MonitoringMetricsExporter.ORG_RECHECK_MS).isLessThanOrEqualTo(10_000L);
    }

    @Test
    @DisplayName("orgFamiliesPlacement (M1 sertleştirme): taze gözlemi yazan pod canlı DEĞİLSE (heartbeat yok / kapanış işareti) lider "
            + "yayar; canlıysa ya da canlılık okunamadıysa satıra güvenilir (yalnız POD aileleri)")
    void orgFamiliesPlacement_publisherLiveness() {
        com.sitemonitor.service.schedule.PodLiveness liveness = mock(com.sitemonitor.service.schedule.PodLiveness.class);
        exporter.setLivenessForTest(liveness);
        placement(true, observation("sitemonitor-other-0000000b", NOW.toEpochMilli() - 1_000));

        when(liveness.otherLivePods()).thenReturn(java.util.Optional.of(1));
        when(liveness.cachedOtherPods()).thenReturn(List.of("sitemonitor-other-0000000b"));
        assertThat(exporter.refresh().orgFamilies()).as("yayımlayan canlı").isFalse();

        when(liveness.cachedOtherPods()).thenReturn(List.of("sitemonitor-third-0000000c"));
        assertThat(exporter.refresh().orgFamilies()).as("yayımlayan artık canlı değil").isTrue();

        when(liveness.otherLivePods()).thenReturn(java.util.Optional.of(0));
        when(liveness.cachedOtherPods()).thenReturn(List.of());
        assertThat(exporter.refresh().orgFamilies()).as("başka canlı pod yok").isTrue();

        when(liveness.otherLivePods()).thenReturn(java.util.Optional.empty());
        assertThat(exporter.refresh().orgFamilies()).as("canlılık bilinmiyor → satıra güven").isFalse();

        when(liveness.otherLivePods()).thenThrow(new IllegalStateException("x"));
        assertThat(exporter.refresh().orgFamilies()).as("canlılık okunamadı → satıra güven").isFalse();
    }

    @Test
    @DisplayName("orgFamiliesPlacement (L3): gözlem turun BİTİŞ anını ve süresini taşır; lider tazelik penceresine yayımlayanın son tur "
            + "süresini ekler — yavaş takipçi (tur > 45 sn) liderin her döngüde bir tur yaymasına yol açmaz")
    void orgFamiliesPlacement_completionTimeAndSlowFollower() {
        java.util.concurrent.atomic.AtomicLong calls = new java.util.concurrent.atomic.AtomicLong();
        exporter.setClock(() -> NOW.plusSeconds(70 * calls.getAndIncrement()));   // her okuma 70 sn sonra: tur 70 sn sürmüş
        com.sitemonitor.service.health.ClusterHealthStore store = placement(false, java.util.Optional.empty());
        exporter.refresh();
        org.mockito.ArgumentCaptor<Long> at = org.mockito.ArgumentCaptor.forClass(Long.class);
        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(store).publish(eq(com.sitemonitor.service.health.ClusterHealthStore.OBS_METRICS_ORG), eq(SELF), at.capture(),
                payload.capture(), eq("2026-10-18T08:01:10"));
        assertThat(at.getValue()).as("bitiş anı (başlangıç değil)").isEqualTo(NOW.plusSeconds(70).toEpochMilli());
        assertThat(payload.getValue()).contains("\"duration_ms\":");

        // Lider: takipçinin gözlemi 150 sn + 90 sn tur süresi içinde → taze (yalnız POD aileleri); süre yoksa 151 sn eski = bayat
        exporter.setClock(() -> NOW);
        placement(true, observation("sitemonitor-other-0000000b", NOW.toEpochMilli() - 200_000, "{\"duration_ms\":90000}"));
        assertThat(exporter.refresh().orgFamilies()).isFalse();
        placement(true, observation("sitemonitor-other-0000000b", NOW.toEpochMilli() - 151_000, "{\"series\":3}"));
        assertThat(exporter.refresh().orgFamilies()).isTrue();
        // Bozuk / aşırı süre tavanlı (≤ 3 × aralık, en az 3 dk): 10 saatlik "süre" gözlemi sonsuza dek taze yapmaz
        placement(true, observation("sitemonitor-other-0000000b", NOW.toEpochMilli() - 400_000, "{\"duration_ms\":36000000}"));
        assertThat(exporter.refresh().orgFamilies()).isTrue();
        placement(true, observation("sitemonitor-other-0000000b", NOW.toEpochMilli() - 100_000, "bozuk{"));
        assertThat(exporter.refresh().orgFamilies()).as("bozuk yük = sıradan gözlem").isFalse();
    }

    @Test
    @DisplayName("değerlendirme durumu (L1): kip yalnız yazma (write) iken bellekteki son doğrulama raporu YAYILMAZ (eski tur güncel "
            + "görünmesin); kip / bekleyen / yazma hatası yine yayılır")
    void evalStateMeters_writeModeHidesOldVerify() {
        Map<String, com.sitemonitor.service.evalstate.EvalVerifyReport.KindResult> kinds = new LinkedHashMap<>();
        kinds.put("sibling", new com.sitemonitor.service.evalstate.EvalVerifyReport.KindResult(12, 2, 0, null));
        com.sitemonitor.service.evalstate.EvalVerifyReport old = new com.sitemonitor.service.evalstate.EvalVerifyReport(
                NOW.toEpochMilli() - 3_600_000, "pod-a", 3, "mismatch", null, kinds, List.of());
        com.sitemonitor.service.evalstate.EvalStateSink sink = mock(com.sitemonitor.service.evalstate.EvalStateSink.class);
        when(sink.status()).thenReturn(evalStatus("write", true, old));
        attachEvalState(sink);
        exporter.refresh();
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_MODE)).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_PENDING)).isEqualTo(7);
        assertThat(exists(MonitoringMetricNames.EVAL_STATE_VERIFY_AT)).isFalse();
        assertThat(exists(MonitoringMetricNames.EVAL_STATE_VERIFY_MISMATCHES)).isFalse();
        // Doğrulama kipine dönünce yine yayılır
        when(sink.status()).thenReturn(evalStatus("verify", true, old));
        exporter.refresh();
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_VERIFY_MISMATCHES, "kind", "sibling")).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    void attachEvalState(com.sitemonitor.service.evalstate.EvalStateSink sink) {
        org.springframework.beans.factory.ObjectProvider<com.sitemonitor.service.evalstate.EvalStateSink> p =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(sink);
        exporter.setEvalState(p);
    }

    static com.sitemonitor.service.evalstate.EvalStateSink.Status evalStatus(String mode, boolean active,
                                                                              com.sitemonitor.service.evalstate.EvalVerifyReport v) {
        return new com.sitemonitor.service.evalstate.EvalStateSink.Status(mode, active, "pod-a", active ? 3L : null, 7, 0, 120, 2,
                NOW.toEpochMilli(), null, null, false, null, v);
    }

    @Test
    @DisplayName("değerlendirme durumu (R3-1): kip / bekleyen / yazma hatası her pod; doğrulama yalnız YAZAN liderken; bilinmeyen tur "
            + "uyuşmazlık yazmaz (sahte 0 yok); kaydedici yok / durumu null → seri yok; POD-only turda da yayılır")
    void evalStateMeters() {
        Map<String, com.sitemonitor.service.evalstate.EvalVerifyReport.KindResult> kinds = new LinkedHashMap<>();
        kinds.put("recovery", new com.sitemonitor.service.evalstate.EvalVerifyReport.KindResult(40, 0, 1, null));
        kinds.put("sibling", new com.sitemonitor.service.evalstate.EvalVerifyReport.KindResult(12, 2, 0, null));
        kinds.put("window_seed", new com.sitemonitor.service.evalstate.EvalVerifyReport.KindResult(9, 1, 0, 0.12));
        com.sitemonitor.service.evalstate.EvalVerifyReport ok = new com.sitemonitor.service.evalstate.EvalVerifyReport(
                NOW.toEpochMilli() - 60_000, "pod-a", 3, "mismatch", null, kinds, List.of("sibling:abc"));
        com.sitemonitor.service.evalstate.EvalStateSink sink = mock(com.sitemonitor.service.evalstate.EvalStateSink.class);
        when(sink.status()).thenReturn(evalStatus("verify", true, ok));
        attachEvalState(sink);

        exporter.refresh();
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_MODE)).isEqualTo(2);
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_PENDING)).isEqualTo(7);
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_WRITE_FAILURES)).isEqualTo(2);
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_VERIFY_AT)).isEqualTo(NOW.getEpochSecond() - 60);
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_VERIFY_MISMATCHES, "kind", "recovery")).isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_VERIFY_MISMATCHES, "kind", "sibling")).isEqualTo(2);
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_VERIFY_MISMATCHES, "kind", "window_seed")).isEqualTo(1);
        assertThat(exists(MonitoringMetricNames.EVAL_STATE_VERIFY_MISMATCHES, "kind", "confirmation")).as("raporda olmayan tür").isFalse();

        // Bilinmeyen tur: zaman var, uyuşmazlık serisi YOK
        when(sink.status()).thenReturn(evalStatus("verify", true,
                com.sitemonitor.service.evalstate.EvalVerifyReport.unknown(NOW.toEpochMilli(), "pod-a", 3, "flush_lag")));
        exporter.refresh();
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_VERIFY_AT)).isEqualTo(NOW.getEpochSecond());
        assertThat(exists(MonitoringMetricNames.EVAL_STATE_VERIFY_MISMATCHES)).isFalse();

        // Eski lider (yazmıyor): bayat rapor yayılmaz; kip yine yayılır (yalnız yazma = 1)
        when(sink.status()).thenReturn(evalStatus("write", false, ok));
        exporter.refresh();
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_MODE)).isEqualTo(1);
        assertThat(exists(MonitoringMetricNames.EVAL_STATE_VERIFY_AT)).isFalse();
        assertThat(exists(MonitoringMetricNames.EVAL_STATE_VERIFY_MISMATCHES)).isFalse();

        // POD-only turda da (lider kurum ailelerini başkasına bıraktı)
        when(sink.status()).thenReturn(evalStatus("off", true, null));
        placement(true, observation("sitemonitor-other-0000000b", NOW.toEpochMilli()));
        exporter.refresh();
        assertThat(gauge(MonitoringMetricNames.EVAL_STATE_MODE)).isEqualTo(0);
        assertThat(exists(MonitoringMetricNames.MONITOR_STATE)).isFalse();

        // Kaydedici durumu yok (NOOP) / tanınmayan kip / okunamadı → seri yok, tur düşmez
        when(sink.status()).thenReturn(null);
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.EVAL_STATE_MODE)).isFalse();
        assertThat(exists(MonitoringMetricNames.EVAL_STATE_PENDING)).isFalse();
        when(sink.status()).thenReturn(evalStatus("bogus", true, null));
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.EVAL_STATE_MODE)).isFalse();
        assertThat(exists(MonitoringMetricNames.EVAL_STATE_PENDING)).isTrue();
        when(sink.status()).thenThrow(new IllegalStateException("x"));
        assertThat(exporter.refresh().enabled()).isTrue();
        assertThat(exists(MonitoringMetricNames.EVAL_STATE_PENDING)).isFalse();
        assertThat(MonitoringMetricsExporter.evalModeCode(null)).isNull();
    }

    // ── R3-3 D1: dağıtık kontrol ölçümleri (tasarım §7.4) ────────────────────────────────────────────────────────

    com.sitemonitor.service.checks.CheckJobStore cjStore;
    com.sitemonitor.service.checks.CheckWorker cjWorker;
    com.sitemonitor.service.checks.CheckResultDrainer cjDrainer;

    /** Dağıtık kontrol kaynaklarını bağlar; {@code on} = ana anahtar, {@code types} = tür ayarı. */
    void attachCheckJobs(boolean on, String types) {
        when(settings.getBoolean(eq(com.sitemonitor.service.checks.DistributedChecks.KEY_ENABLED), anyBoolean())).thenReturn(on);
        when(settings.getString(eq(com.sitemonitor.service.checks.DistributedChecks.KEY_TYPES), anyString())).thenReturn(types);
        exporter.setDistributedChecks(new com.sitemonitor.service.checks.DistributedChecks(settings,
                com.sitemonitor.service.checks.DistributedChecks.Tuning.DEFAULTS));
        cjStore = mock(com.sitemonitor.service.checks.CheckJobStore.class);
        when(cjStore.stats()).thenReturn(new com.sitemonitor.service.checks.CheckJobStore.QueueStats(NOW.toEpochMilli(), List.of(
                new com.sitemonitor.service.checks.CheckJobStore.QueueStat("PENDING", "port", 7, NOW.toEpochMilli() - 4_000, null),
                new com.sitemonitor.service.checks.CheckJobStore.QueueStat("CLAIMED", "port", 3, NOW.toEpochMilli() - 2_000, null),
                new com.sitemonitor.service.checks.CheckJobStore.QueueStat("DONE", "ping", 2, NOW.toEpochMilli() - 3_000, NOW.toEpochMilli() - 900)),
                4_000L, 900L, 0L));
        cjWorker = mock(com.sitemonitor.service.checks.CheckWorker.class);
        when(cjWorker.status()).thenReturn(new com.sitemonitor.service.checks.CheckWorker.Status(true, true, false, 16, 5, 400, 390, 2,
                1, 3, 0, 0, 0, 0, 410L, 1000));
        cjDrainer = mock(com.sitemonitor.service.checks.CheckResultDrainer.class);
        when(cjDrainer.status()).thenReturn(new com.sitemonitor.service.checks.CheckResultDrainer.Status(true, true, true, false, 380, 120,
                378, 2, 1, 0, 0, 0, 0, NOW.toEpochMilli(), 0L, null, 3L, 2L, 1L, 4L, true, false));
        exporter.setCheckJobStore(cjStore);
        exporter.setCheckWorker(cjWorker);
        exporter.setCheckResultDrainer(cjDrainer);
    }

    com.sitemonitor.service.SchedulerService schedulerWithDispatch(boolean leader) {
        com.sitemonitor.service.SchedulerService scheduler = mock(com.sitemonitor.service.SchedulerService.class);
        when(scheduler.sweepLeaderHint()).thenReturn(leader);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("lost", 4L);
        d.put("dropped_inactive", 1L);
        d.put("dropped_target", 1L);
        d.put("dropped_toggle", 0L);
        d.put("dropped_inventory", 2L);
        when(scheduler.distributedCheckStatus()).thenReturn(d);
        exporter.setScheduler(scheduler);
        return scheduler;
    }

    boolean anyCheckSeries() {
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) {
            if (d.name().startsWith("sitemonitor.check.") && exists(d.name())) return true;
        }
        return false;
    }

    @Test
    @DisplayName("R3-3: ana anahtar KAPALI → hiçbir sitemonitor_check_* serisi ve check_jobs kaynak satırı yok; kuyruk, işçi, boşaltıcı ve "
            + "gönderme sayaçları HİÇ okunmaz (K-1; yok ≠ 0)")
    void checkJobMeters_offNoSeriesNoReads() {
        attachCheckJobs(false, com.sitemonitor.service.checks.CheckJobTypes.DEFAULT_CSV);
        com.sitemonitor.service.SchedulerService scheduler = schedulerWithDispatch(false);
        exporter.refresh();
        assertThat(anyCheckSeries()).isFalse();
        assertThat(exists(MonitoringMetricNames.EXPORTER_SOURCE, "source", "check_jobs")).isFalse();
        verifyNoInteractions(cjStore, cjWorker, cjDrainer);
        verify(scheduler, never()).distributedCheckStatus();
        // diğer aileler etkilenmez
        assertThat(gauge(MonitoringMetricNames.MONITORS, "type", "http", "state", "up")).isEqualTo(1);
    }

    @Test
    @DisplayName("R3-3: anahtar açık → kuyruk (durum × tür, sıfırlar dahil; en eski bekleyen / sonuç sn), yuvalar, birikimli iş sonuçları "
            + "(tamamlanan, geç, kayıp, düşen = zamanlayıcı + boşaltıcı), sahiplenme p95; check_jobs kaynağı 1; adlar _total vb. ile bitmez")
    void checkJobMeters_on() {
        attachCheckJobs(true, "port,ping");
        schedulerWithDispatch(false);
        exporter.refresh();
        assertThat(gauge(MonitoringMetricNames.CHECK_JOBS, "state", "pending", "type", "port")).isEqualTo(7);
        assertThat(gauge(MonitoringMetricNames.CHECK_JOBS, "state", "claimed", "type", "port")).isEqualTo(3);
        assertThat(gauge(MonitoringMetricNames.CHECK_JOBS, "state", "done", "type", "ping")).isEqualTo(2);
        assertThat(gauge(MonitoringMetricNames.CHECK_JOBS, "state", "pending", "type", "scripted")).as("okunmuş kuyrukta 0 gerçektir").isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.CHECK_JOBS_OLDEST, "state", "pending")).isEqualTo(4.0);
        assertThat(gauge(MonitoringMetricNames.CHECK_JOBS_OLDEST, "state", "done")).isEqualTo(0.9);
        assertThat(gauge(MonitoringMetricNames.CHECK_WORKER_SLOTS, "kind", "busy")).isEqualTo(5);
        assertThat(gauge(MonitoringMetricNames.CHECK_WORKER_SLOTS, "kind", "total")).isEqualTo(16);
        assertThat(gauge(MonitoringMetricNames.CHECK_WORKER_JOBS, "outcome", "completed")).isEqualTo(390);
        assertThat(gauge(MonitoringMetricNames.CHECK_WORKER_JOBS, "outcome", "late")).isEqualTo(2);
        assertThat(gauge(MonitoringMetricNames.CHECK_WORKER_JOBS, "outcome", "lost")).isEqualTo(4);
        assertThat(gauge(MonitoringMetricNames.CHECK_WORKER_JOBS, "outcome", "dropped"))
                .as("1 + 1 + 0 + 2 + bayat 2 + sıra dışı 1 + yeniden açma öncesi 3 + klasikten eski 2 + devirde 1").isEqualTo(13);
        assertThat(gauge(MonitoringMetricNames.CHECK_CLAIM_LAG)).isEqualTo(0.41);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "check_jobs")).isEqualTo(1);
        verify(cjStore).stats();
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) {
            if (!d.name().startsWith("sitemonitor.check.")) continue;
            assertThat(d.prom()).doesNotEndWith("_total").doesNotEndWith("_count").doesNotEndWith("_sum").doesNotEndWith("_bucket")
                    .doesNotEndWith("_info").doesNotEndWith("_created");
        }
    }

    @Test
    @DisplayName("R3-3: kuyruk okunamadı → kuyruk aileleri düşer (sahte 0 yok), check_jobs kaynağı 0; POD aileleri sürer. Tür seçili değil → "
            + "kuyruk HİÇ okunmaz; işçi durumu okunamadı → yuva / iş sonucu yok, gönderme sayaçları yine")
    void checkJobMeters_partialFailures() {
        attachCheckJobs(true, "port");
        schedulerWithDispatch(false);
        when(cjStore.stats()).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.CHECK_JOBS)).isFalse();
        assertThat(exists(MonitoringMetricNames.CHECK_JOBS_OLDEST)).isFalse();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "check_jobs")).isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.CHECK_WORKER_SLOTS, "kind", "total")).isEqualTo(16);

        attachCheckJobs(true, "unknown_type_only");
        schedulerWithDispatch(false);
        when(cjWorker.status()).thenThrow(new IllegalStateException("y"));
        exporter.refresh();
        verify(cjStore, never()).stats();
        assertThat(exists(MonitoringMetricNames.CHECK_JOBS)).isFalse();
        assertThat(exists(MonitoringMetricNames.EXPORTER_SOURCE, "source", "check_jobs")).isFalse();
        assertThat(exists(MonitoringMetricNames.CHECK_WORKER_SLOTS)).isFalse();
        assertThat(exists(MonitoringMetricNames.CHECK_WORKER_JOBS, "outcome", "completed")).isFalse();
        assertThat(gauge(MonitoringMetricNames.CHECK_WORKER_JOBS, "outcome", "lost")).isEqualTo(4);
    }

    @Test
    @DisplayName("R3-3: POD-only lider (başka pod kurum ailelerini yayıyor) → kuyruk KÜME aileleri yok ve kuyruk okunmaz; POD aileleri (yuvalar, "
            + "iş sonuçları, sahiplenme p95) bellekten yayılır")
    void checkJobMeters_podOnlyLeader() {
        attachCheckJobs(true, "port,ping");
        placement(true, observation("sitemonitor-other-0000000b", NOW.toEpochMilli() - 30_000));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("lost", 0L);
        d.put("dropped_inactive", 0L);
        d.put("dropped_target", 0L);
        d.put("dropped_toggle", 0L);
        d.put("dropped_inventory", 0L);
        com.sitemonitor.service.SchedulerService scheduler = mock(com.sitemonitor.service.SchedulerService.class);
        when(scheduler.sweepLeaderHint()).thenReturn(true);
        when(scheduler.distributedCheckStatus()).thenReturn(d);
        exporter.setScheduler(scheduler);
        MonitoringMetricsExporter.Status s = exporter.refresh();
        assertThat(s.orgFamilies()).isFalse();
        assertThat(exists(MonitoringMetricNames.CHECK_JOBS)).isFalse();
        assertThat(exists(MonitoringMetricNames.CHECK_JOBS_OLDEST)).isFalse();
        verify(cjStore, never()).stats();
        assertThat(gauge(MonitoringMetricNames.CHECK_WORKER_SLOTS, "kind", "busy")).isEqualTo(5);
        assertThat(gauge(MonitoringMetricNames.CHECK_WORKER_JOBS, "outcome", "completed")).isEqualTo(390);
        assertThat(gauge(MonitoringMetricNames.CHECK_WORKER_JOBS, "outcome", "dropped")).isEqualTo(9);
        assertThat(gauge(MonitoringMetricNames.CHECK_CLAIM_LAG)).isEqualTo(0.41);
    }

    // ── R3-4 C1: parçalı sertifika taraması ölçümleri (tasarım §7.4) ───────────────────────────────────────────────

    com.sitemonitor.service.certsweep.CertSweepStore csStore;
    com.sitemonitor.service.health.ClusterHealthStore csHealth;

    static com.sitemonitor.service.certsweep.CertSweepStatus.Run csRun(String id, String state, long createdMs, Long finishedMs) {
        return new com.sitemonitor.service.certsweep.CertSweepStatus.Run(id, "hourly", "pod-a-0000000a", state, true, 400, 20, 20,
                createdMs, createdMs, finishedMs, finishedMs == null ? null : "DECIDED", null, null, null, null);
    }

    static com.sitemonitor.service.health.ClusterHealthStore.ScanRun scanRun(String kind, String runId, String at, long durationMs) {
        return new com.sitemonitor.service.health.ClusterHealthStore.ScanRun(kind, runId, "pod-a-0000000a", at, durationMs, 400, 0, 0,
                null, null, null, at);
    }

    /** Parçalı tarama kaynaklarını bağlar; {@code on} = anahtar; açık koşu + bir DONE parçalı saatlik tur; stale klasik. */
    com.sitemonitor.service.certsweep.CertChunking attachCertSweep(boolean on) {
        when(settings.getBoolean(eq(com.sitemonitor.service.certsweep.CertChunking.KEY_ENABLED), anyBoolean())).thenReturn(on);
        com.sitemonitor.service.certsweep.CertChunking cc = new com.sitemonitor.service.certsweep.CertChunking(settings, null);
        cc.registerWorkerStatus(() -> new com.sitemonitor.service.certsweep.CertSweepStatus.Worker(true, true, 1, 20, 40, false,
                90, 85, 1, 2, 0, 1700, NOW.toEpochMilli(), false));
        cc.registerCoordinatorStatus(() -> new com.sitemonitor.service.certsweep.CertSweepStatus.Coordinator(12, 3, 4, 5, 7, 1));
        exporter.setCertChunking(cc);
        long now = NOW.toEpochMilli();
        csStore = mock(com.sitemonitor.service.certsweep.CertSweepStore.class);
        com.sitemonitor.service.certsweep.CertSweepStatus.Run open = csRun("r-open", "RUNNING", now - 20_000, null);
        when(csStore.status(MonitoringMetricsExporter.CERT_SWEEP_STATUS_RUNS)).thenReturn(new com.sitemonitor.service.certsweep.CertSweepStatus(now,
                false, List.of(open, csRun("r-hourly", "DONE", now - 3_600_000, now - 3_550_000)), open, List.of(
                new com.sitemonitor.service.certsweep.CertSweepStatus.ChunkGroup("PENDING", null, 5, 100, 0, now - 20_000),
                new com.sitemonitor.service.certsweep.CertSweepStatus.ChunkGroup("CLAIMED", "pod-a-0000000a", 2, 40, 0, now - 20_000),
                new com.sitemonitor.service.certsweep.CertSweepStatus.ChunkGroup("DONE", "pod-a-0000000a", 3, 60, 1, now - 20_000))));
        exporter.setCertSweepStore(csStore);
        csHealth = mock(com.sitemonitor.service.health.ClusterHealthStore.class);
        when(csHealth.readScanRuns()).thenReturn(Map.of(
                "hourly", scanRun("hourly", "r-hourly", "2026-10-18T07:00:50", 50_000L),
                "stale", scanRun("stale", "r-stale", "2026-10-18T07:55:00", 2_000L)));
        exporter.setClusterHealth(csHealth);
        return cc;
    }

    boolean anyCertSweepSeries() {
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) {
            if (d.name().startsWith("sitemonitor.cert.sweep.") && exists(d.name())) return true;
        }
        return false;
    }

    @Test
    @DisplayName("R3-4: anahtar KAPALI → hiçbir sitemonitor_cert_sweep_* serisi ve cert_sweep / scan_runs kaynak satırı yok; koşu deposu ve "
            + "scan_runs HİÇ okunmaz (L-1; yok ≠ 0); diğer aileler etkilenmez")
    void certSweepMeters_offNoSeriesNoReads() {
        attachCertSweep(false);
        exporter.refresh();
        assertThat(anyCertSweepSeries()).isFalse();
        assertThat(exists(MonitoringMetricNames.EXPORTER_SOURCE, "source", "cert_sweep")).isFalse();
        assertThat(exists(MonitoringMetricNames.EXPORTER_SOURCE, "source", "scan_runs")).isFalse();
        verifyNoInteractions(csStore);
        verify(csHealth, never()).readScanRuns();
        assertThat(gauge(MonitoringMetricNames.MONITORS, "type", "http", "state", "up")).isEqualTo(1);
    }

    @Test
    @DisplayName("R3-4: anahtar açık → açık koşunun parçaları durum başına (sıfırlar dahil), tür başına son tur süresi + kipi "
            + "(r-hourly parçalı, r-stale klasik), pod sayaçları (completed / late / released / takeover / second_opinion); kaynaklar 1; "
            + "adlar _total vb. ile bitmez")
    void certSweepMeters_on() {
        attachCertSweep(true);
        exporter.refresh();
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_CHUNKS, "state", "pending")).isEqualTo(5);
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_CHUNKS, "state", "claimed")).isEqualTo(2);
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_CHUNKS, "state", "done")).isEqualTo(3);
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_CHUNKS, "state", "cancelled")).as("okunmuş açık koşuda 0 gerçektir").isEqualTo(0);
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_LAST_RUN, "kind", "hourly", "mode", "chunked")).isEqualTo(50.0);
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_LAST_RUN, "kind", "stale", "mode", "classic")).isEqualTo(2.0);
        assertThat(exists(MonitoringMetricNames.CERT_SWEEP_LAST_RUN, "kind", "full")).as("hiç koşmamış tür yazılmaz").isFalse();
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS, "outcome", "completed")).isEqualTo(85);
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS, "outcome", "late")).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS, "outcome", "released")).isEqualTo(2);
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS, "outcome", "takeover")).isEqualTo(4);
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS, "outcome", "second_opinion")).isEqualTo(7);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "cert_sweep")).isEqualTo(1);
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "scan_runs")).isEqualTo(1);
        verify(csStore).status(MonitoringMetricsExporter.CERT_SWEEP_STATUS_RUNS);
        for (MonitoringMetricNames.Def d : MonitoringMetricNames.ALL) {
            if (!d.name().startsWith("sitemonitor.cert.sweep.")) continue;
            assertThat(d.prom()).doesNotEndWith("_total").doesNotEndWith("_count").doesNotEndWith("_sum").doesNotEndWith("_bucket")
                    .doesNotEndWith("_info").doesNotEndWith("_created");
        }
    }

    @Test
    @DisplayName("R3-4: açık koşu yok → parça serisi YOK (yok ≠ 0), son tur yine; depo okunamadı → parça + son tur aileleri düşer, "
            + "cert_sweep kaynağı 0, scan_runs hiç okunmaz; scan_runs okunamadı → yalnız son tur düşer; işçi / koordinatör kaydı yok → "
            + "o çıkışlar yazılmaz")
    void certSweepMeters_partial() {
        com.sitemonitor.service.certsweep.CertChunking cc = attachCertSweep(true);
        long now = NOW.toEpochMilli();
        when(csStore.status(MonitoringMetricsExporter.CERT_SWEEP_STATUS_RUNS)).thenReturn(new com.sitemonitor.service.certsweep.CertSweepStatus(now,
                false, List.of(csRun("r-hourly", "DONE", now - 3_600_000, now - 3_550_000)), null, List.of()));
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.CERT_SWEEP_CHUNKS)).isFalse();
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_LAST_RUN, "kind", "hourly", "mode", "chunked")).isEqualTo(50.0);

        when(csStore.status(MonitoringMetricsExporter.CERT_SWEEP_STATUS_RUNS)).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));
        org.mockito.Mockito.clearInvocations(csHealth);
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.CERT_SWEEP_CHUNKS)).isFalse();
        assertThat(exists(MonitoringMetricNames.CERT_SWEEP_LAST_RUN)).isFalse();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "cert_sweep")).isEqualTo(0);
        verify(csHealth, never()).readScanRuns();
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS, "outcome", "completed")).as("POD sayaçları sürer").isEqualTo(85);

        org.mockito.Mockito.reset(csStore);
        when(csStore.status(MonitoringMetricsExporter.CERT_SWEEP_STATUS_RUNS)).thenReturn(new com.sitemonitor.service.certsweep.CertSweepStatus(now,
                false, List.of(), null, List.of()));
        when(csHealth.readScanRuns()).thenThrow(new IllegalStateException("tablo yok"));
        cc.registerWorkerStatus(null);
        cc.registerCoordinatorStatus(null);
        exporter.refresh();
        assertThat(exists(MonitoringMetricNames.CERT_SWEEP_LAST_RUN)).isFalse();
        assertThat(gauge(MonitoringMetricNames.EXPORTER_SOURCE, "source", "scan_runs")).isEqualTo(0);
        assertThat(exists(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS)).isFalse();
    }

    @Test
    @DisplayName("R3-4: POD-only lider (başka pod kurum ailelerini yayıyor) → parça / son tur KÜME aileleri yok, depo okunmaz; pod sayaçları "
            + "bellekten yayılır")
    void certSweepMeters_podOnlyLeader() {
        attachCertSweep(true);
        placement(true, observation("sitemonitor-other-0000000b", NOW.toEpochMilli() - 30_000));
        MonitoringMetricsExporter.Status s = exporter.refresh();
        assertThat(s.orgFamilies()).isFalse();
        assertThat(exists(MonitoringMetricNames.CERT_SWEEP_CHUNKS)).isFalse();
        assertThat(exists(MonitoringMetricNames.CERT_SWEEP_LAST_RUN)).isFalse();
        verifyNoInteractions(csStore);
        assertThat(gauge(MonitoringMetricNames.CERT_SWEEP_WORKER_CHUNKS, "outcome", "takeover")).isEqualTo(4);
    }
}
