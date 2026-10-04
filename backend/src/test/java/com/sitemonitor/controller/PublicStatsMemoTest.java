package com.sitemonitor.controller;

import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Giriş sayfası istatistikleri belleği (2026-10-04): {@code cache-ms} içinde tek hesap; ayar kapalıyken bellekteki
 * tam kayıt süzülerek kullanılır; kapalı kayıttan sonra şerit açılınca YENİDEN hesaplanır; soğuk bellekte eşzamanlı
 * istekler tek hesap paylaşır (oturumsuz uç — pano hesabı ağır).
 */
class PublicStatsMemoTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MonitoringOverviewService overview = mock(MonitoringOverviewService.class);
    private final AlertEventRepository alerts = mock(AlertEventRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final AuditLogRepository audit = mock(AuditLogRepository.class);
    private final PresenceService presence = mock(PresenceService.class);
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private PublicStatsController ctrl;

    @BeforeEach
    void setUp() {
        ctrl = new PublicStatsController(jdbc, overview, alerts, teams, users, audit, presence, settings);
        ctrl.cacheMs = 60_000;
        when(settings.getBoolean(eq(PublicStatsController.USAGE_KEY), anyBoolean())).thenReturn(true);
        when(overview.orgSummary()).thenReturn(Map.of("active", 10L, "healthy", 8L, "total", 12L,
                "checks_window", 100L, "failed_window", 2L));
        when(jdbc.queryForMap(anyString(), anyString())).thenReturn(Map.of("ups", 99L, "total", 100L));
        when(alerts.countCreatedSince(anyString())).thenReturn(3L);
        when(teams.countByActiveTrue()).thenReturn(4L);
        when(users.countByActiveTrue()).thenReturn(7L);
        when(audit.countDistinctLoginActorsSince(anyString())).thenReturn(5L);
        when(presence.online()).thenReturn(Map.of("total", 6L));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data() {
        return (Map<String, Object>) ctrl.stats().getBody().get("data");
    }

    @Test
    @DisplayName("cache-ms içinde ikinci istek hesaplamaz; varsayılan pencere 60 sn")
    void memoHonoured() {
        assertThat(new PublicStatsController(jdbc, overview, alerts, teams, users, audit, presence, settings).cacheMs)
                .as("alan varsayılanı = @Value varsayılanı (60 sn)").isEqualTo(60_000L);
        Map<String, Object> a = data();
        Map<String, Object> b = data();
        assertThat(b).isEqualTo(a).containsEntry("healthy_monitors", 8L).containsEntry("online_users", 6L)
                .containsEntry("active_users", 7L);
        verify(overview, times(1)).orgSummary();
        verify(presence, times(1)).online();
        verify(users, times(1)).countByActiveTrue();
    }

    @Test
    @DisplayName("cache-ms=0 → her istek hesaplar")
    void memoDisabled() {
        ctrl.cacheMs = 0;
        data();
        data();
        verify(overview, times(2)).orgSummary();
    }

    @Test
    @DisplayName("Ayar kapanınca bellekteki tam kayıt SÜZÜLÜR (yeniden hesap yok); kapalı kayıttan sonra açılınca yeniden hesaplanır")
    void flagToggle_reusesOrRecomputes() {
        data();                                                       // tam kayıt (kullanım açık)
        when(settings.getBoolean(eq(PublicStatsController.USAGE_KEY), anyBoolean())).thenReturn(false);
        assertThat(data().keySet()).containsExactly("monitored_targets", "availability_pct");
        verify(overview, times(1)).orgSummary();

        // Soğuk bellek + kapalı ayar → yalnız eski alanlar hesaplanır; açılınca kullanım alanları için yeniden hesap.
        ctrl = new PublicStatsController(jdbc, overview, alerts, teams, users, audit, presence, settings);
        ctrl.cacheMs = 60_000;
        data();
        verify(presence, times(1)).online();                          // ilk denetleyicinin hesabı; ikincisinde yok
        when(settings.getBoolean(eq(PublicStatsController.USAGE_KEY), anyBoolean())).thenReturn(true);
        assertThat(data()).containsEntry("online_users", 6L).containsEntry("teams", 4L);
        verify(presence, times(2)).online();
    }

    @Test
    @DisplayName("Ayar okunamazsa şerit AÇIK kabul edilir (varsayılan)")
    void flagReadFailure_defaultsOn() {
        when(settings.getBoolean(eq(PublicStatsController.USAGE_KEY), anyBoolean())).thenThrow(new IllegalStateException("x"));
        assertThat(data()).containsKey("healthy_monitors");
    }

    @Test
    @DisplayName("Soğuk bellekte eşzamanlı istekler TEK hesap paylaşır (oturumsuz uçta açılış seli DB'ye N kez binmez)")
    void coldCache_singleFlight() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(overview.orgSummary()).thenAnswer(inv -> {
            inside.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return Map.of("active", 10L, "healthy", 8L);
        });
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            futures.add(pool.submit(this::data));
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();   // ilk istek hesabın içinde, kilit onda
            for (int i = 0; i < 4; i++) futures.add(pool.submit(this::data));
            ReentrantLock lock = (ReentrantLock) ReflectionTestUtils.getField(ctrl, "computeLock");
            long deadline = System.currentTimeMillis() + 5_000;
            while (lock.getQueueLength() < 4 && System.currentTimeMillis() < deadline) Thread.onSpinWait();
            assertThat(lock.getQueueLength()).as("bekleyen istekler kilitte sıraya girdi").isEqualTo(4);
            release.countDown();
            for (Future<Map<String, Object>> f : futures) {
                assertThat(f.get(5, TimeUnit.SECONDS)).containsEntry("healthy_monitors", 8L);
            }
        } finally {
            pool.shutdownNow();
        }
        verify(overview, times(1)).orgSummary();
    }
}
