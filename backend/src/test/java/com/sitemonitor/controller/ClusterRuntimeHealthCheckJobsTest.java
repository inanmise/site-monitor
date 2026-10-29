package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.checks.CheckJobStore;
import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.checks.CheckResultDrainer;
import com.sitemonitor.service.checks.CheckWorker;
import com.sitemonitor.service.checks.CheckWorkerRegistry;
import com.sitemonitor.service.checks.CheckWorkerRegistryImpl;
import com.sitemonitor.service.checks.DbClock;
import com.sitemonitor.service.checks.DistributedChecks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sistem Sağlığı Küme kartının çok pod Sürüm 3-3 alanı {@code check_jobs} (2026-10-29, paket D1; tasarım R3-3 §7.1): anahtar
 * kapalıyken YALNIZ {@code {enabled: false}} (bellek — kuyruk / kayıt / işçi / saat / zamanlayıcı hiç çağrılmaz, K-1); açıkken
 * kipler, kuyruk (tek gruplu deyim), işçiler (önbellek), bu pod; okunamayan parça "bilinmiyor" — asla sahte 0.
 */
class ClusterRuntimeHealthCheckJobsTest {

    static final long NOW = 1_793_260_800_000L;   // 2026-10-29
    static final String SELF = SchedulerService.instanceId();

    static DistributedChecks checks(boolean on, String types) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(eq(DistributedChecks.KEY_ENABLED), anyBoolean())).thenReturn(on);
        when(settings.getString(eq(DistributedChecks.KEY_TYPES), anyString())).thenReturn(types);
        return new DistributedChecks(settings, DistributedChecks.Tuning.DEFAULTS);
    }

    static CheckJobStore.QueueStats stats(long oldestPendingMs, long oldestDoneMs, long expired) {
        return new CheckJobStore.QueueStats(NOW, List.of(
                new CheckJobStore.QueueStat(CheckJobStore.STATE_PENDING, "port", 7, NOW - oldestPendingMs, null),
                new CheckJobStore.QueueStat(CheckJobStore.STATE_CLAIMED, "port", 3, NOW - 2_000, null),
                new CheckJobStore.QueueStat(CheckJobStore.STATE_DONE, "ping", 2, NOW - 4_000, NOW - oldestDoneMs),
                new CheckJobStore.QueueStat(CheckJobStore.STATE_PENDING, "brand_new", 1, NOW - 500, null)),
                oldestPendingMs, oldestDoneMs, expired);
    }

    static CheckWorkerRegistry.WorkerView worker(String id, String pod, Set<String> caps, boolean live, boolean stopping) {
        return new CheckWorkerRegistry.WorkerView(id, pod, caps, 16, 4, 1200, 2, 820L, NOW - 5_000, "20.170.0", stopping, live);
    }

    static CheckWorker.Status workerStatus(boolean enabled) {
        return new CheckWorker.Status(true, enabled, false, 16, 2, 400, 390, 1, 3, 4, 0, 0, 0, 0, 410L, 1000);
    }

    static CheckResultDrainer.Status drainerStatus() {
        return new CheckResultDrainer.Status(true, true, true, false, 380, 120, 378, 1, 0, 0, 0, 2, 0, NOW - 1_000, 55L, null,
                4L, 3L, 2L, 5L, true, true);
    }

    @Test
    @DisplayName("anahtar kapalı → YALNIZ {enabled: false}; kuyruk, kayıt, işçi, boşaltıcı, saat ve zamanlayıcı HİÇ çağrılmaz (K-1)")
    void offIsMemoryOnly() {
        CheckJobStore store = mock(CheckJobStore.class);
        CheckWorkerRegistry registry = mock(CheckWorkerRegistry.class);
        CheckWorker w = mock(CheckWorker.class);
        CheckResultDrainer d = mock(CheckResultDrainer.class);
        DbClock clock = mock(DbClock.class);
        SchedulerService scheduler = mock(SchedulerService.class);
        Map<String, Object> m = ClusterRuntimeHealth.checkJobs(new ClusterRuntimeHealth.CheckJobsSources(
                checks(false, CheckJobTypes.DEFAULT_CSV), store, registry, w, d, clock, scheduler), NOW);
        assertThat(m).containsExactly(Map.entry("enabled", false));
        verifyNoInteractions(store, registry, w, d, clock, scheduler);
        // ayar servisi yok / kaynak yok → yine kapalı
        assertThat(ClusterRuntimeHealth.checkJobs(null, NOW)).containsExactly(Map.entry("enabled", false));
        assertThat(ClusterRuntimeHealth.checkJobs(new ClusterRuntimeHealth.CheckJobsSources(null, store, null, null, null, null, null), NOW))
                .containsExactly(Map.entry("enabled", false));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("açık: türler, kipler (yanıtlayan pod'un görüşü + lider mi), kuyruk sayıları sıfır dahil + tür kırılımı (yeni tür sonda), "
            + "işçiler (eksik yetenek yalnız SEÇİLİ türlerden, canlı önce, bu pod işaretli), bu pod (işçi, yetenek, saat, gönderme, boşaltıcı)")
    @SuppressWarnings("unchecked")
    void onCarriesEverything() {
        CheckJobStore store = mock(CheckJobStore.class);
        when(store.stats()).thenReturn(stats(4_000, 900, 1));
        CheckWorkerRegistry registry = mock(CheckWorkerRegistry.class);
        when(registry.liveOtherWorkers(CheckJobTypes.PORT)).thenReturn(1);
        when(registry.workers()).thenReturn(List.of(
                worker("sm-z-0000000z", "sm-z", Set.of("port"), false, false),
                worker(SELF, "sm-self", Set.of("port", "ping"), true, false),
                worker("sm-b-0000000b", "sm-b", Set.of("port"), true, false)));
        when(registry.localCapabilities()).thenReturn(Set.of("port", "ping"));
        CheckWorker w = mock(CheckWorker.class);
        when(w.status()).thenReturn(workerStatus(true));
        CheckResultDrainer d = mock(CheckResultDrainer.class);
        when(d.status()).thenReturn(drainerStatus());
        DbClock clock = mock(DbClock.class);
        when(clock.stale()).thenReturn(false);
        SchedulerService scheduler = mock(SchedulerService.class);
        when(scheduler.sweepLeaderHint()).thenReturn(true);
        Map<String, Object> dispatch = new LinkedHashMap<>();
        dispatch.put("mode", Map.of("port", "distributed", "ping", "classic"));
        dispatch.put("dispatch_active", true);
        dispatch.put("dispatched", 900L);
        dispatch.put("lost", 1L);
        when(scheduler.distributedCheckStatus()).thenReturn(dispatch);

        Map<String, Object> m = ClusterRuntimeHealth.checkJobs(new ClusterRuntimeHealth.CheckJobsSources(
                checks(true, "ping, PORT"), store, registry, w, d, clock, scheduler), NOW);

        assertThat(m).containsEntry("enabled", true).containsEntry("types", List.of("port", "ping"))
                .containsEntry("mode_by_leader", true).containsEntry("workers_known", true).doesNotContainKey("queue_error");
        Map<String, Object> mode = (Map<String, Object>) m.get("mode");
        assertThat(mode.keySet()).containsExactlyElementsOf(CheckJobTypes.ALL);
        assertThat(mode).containsEntry("port", "distributed").containsEntry("ping", "classic").containsEntry("http", null);
        Map<String, Object> q = (Map<String, Object>) m.get("queue");
        assertThat(q).containsEntry("pending", 8L).containsEntry("claimed", 3L).containsEntry("done", 2L)
                .containsEntry("oldest_pending_age_ms", 4_000L).containsEntry("oldest_done_age_ms", 900L).containsEntry("expired_claims", 1L);
        Map<String, Map<String, Object>> byType = (Map<String, Map<String, Object>>) q.get("by_type");
        assertThat(byType.keySet()).containsExactly("port", "ping", "brand_new");
        assertThat(byType.get("ping")).containsEntry("pending", 0L).containsEntry("done", 2L);
        List<Map<String, Object>> workers = (List<Map<String, Object>>) m.get("workers");
        assertThat(workers).extracting(x -> x.get("pod")).containsExactly("sm-b", "sm-self", "sm-z");
        assertThat(workers.get(0)).containsEntry("missing_caps", List.of("ping")).containsEntry("live", true)
                .containsEntry("heartbeat_age_ms", 5_000L).containsEntry("self", false).containsEntry("claim_lag_p95_ms", 820L);
        assertThat(workers.get(1)).containsEntry("self", true).containsEntry("missing_caps", List.of());
        assertThat(m).containsEntry("workers_total", 3);
        Map<String, Object> pod = (Map<String, Object>) m.get("this_pod");
        assertThat(pod).containsEntry("leader", true).containsEntry("db_clock_stale", false).containsKey("registry");
        assertThat((List<String>) pod.get("local_caps")).containsExactlyInAnyOrder("port", "ping");
        assertThat((Map<String, Object>) pod.get("worker")).containsEntry("enabled", true).containsEntry("completed", 390L)
                .containsEntry("timeouts", 3L).containsEntry("claim_lag_p95_ms", 410L);
        assertThat((Map<String, Object>) pod.get("dispatch")).containsEntry("dispatched", 900L).containsEntry("lost", 1L).doesNotContainKey("mode");
        assertThat((Map<String, Object>) pod.get("drainer")).containsEntry("taken", 380L).containsEntry("dropped_stale", 1L)
                .containsEntry("draining", true).containsEntry("dropped_obsolete", 4L).containsEntry("dropped_superseded", 3L)
                .containsEntry("dropped_handover", 2L).containsEntry("classic_holds", 5L).containsEntry("can_drain", true)
                .containsEntry("lost_in_handover", true);
        assertThat(((Map<String, Object>) pod.get("drainer")).get("last_take_at")).isNotNull();
        verify(store).stats();
    }

    @Test
    @DisplayName("kuyruk okunamadı → queue null + queue_error (sınıf adı), asla sahte 0; tür seçilmedi → kuyruk HİÇ okunmaz (queue_skipped)")
    void queueUnreadableAndNoTypes() {
        CheckJobStore store = mock(CheckJobStore.class);
        when(store.stats()).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));
        Map<String, Object> m = ClusterRuntimeHealth.checkJobs(new ClusterRuntimeHealth.CheckJobsSources(
                checks(true, "port"), store, null, null, null, null, null), NOW);
        assertThat(m).containsEntry("queue", null).containsEntry("queue_error", "QueryTimeoutException")
                .containsEntry("workers", null).containsEntry("workers_known", false).containsEntry("workers_error", "unavailable");

        CheckJobStore idle = mock(CheckJobStore.class);
        Map<String, Object> n = ClusterRuntimeHealth.checkJobs(new ClusterRuntimeHealth.CheckJobsSources(
                checks(true, "nope"), idle, null, null, null, null, null), NOW);
        assertThat(n).containsEntry("types", List.of()).containsEntry("queue", null).containsEntry("queue_skipped", "no_types");
        verify(idle, never()).stats();

        Map<String, Object> noStore = ClusterRuntimeHealth.checkJobs(new ClusterRuntimeHealth.CheckJobsSources(
                checks(true, "port"), null, null, null, null, null, null), NOW);
        assertThat(noStore).containsEntry("queue", null).containsEntry("queue_error", "unavailable");
    }

    @Test
    @DisplayName("işçi kaydı bilinmiyor: hiç okunmadı / tablo yok / eski → workers_known false + neden; zamanlayıcı ve işçi okunamazsa ayrı hata alanı")
    void workersUnknownAndPartialFailures() {
        CheckWorkerRegistryImpl registry = mock(CheckWorkerRegistryImpl.class);
        when(registry.liveOtherWorkers(CheckJobTypes.PORT)).thenReturn(CheckWorkerRegistry.UNKNOWN);
        when(registry.workers()).thenReturn(List.of());
        when(registry.status()).thenReturn(new CheckWorkerRegistryImpl.Status(true, false, 0, 0, 0, 0, null, 0, 0, false));
        CheckJobStore store = mock(CheckJobStore.class);
        when(store.stats()).thenReturn(stats(1_000, 1_000, 0));
        Map<String, Object> m = ClusterRuntimeHealth.checkJobs(new ClusterRuntimeHealth.CheckJobsSources(
                checks(true, "port"), store, registry, null, null, null, null), NOW);
        assertThat(m).containsEntry("workers_known", false).containsEntry("workers_error", "not_read_yet").containsEntry("workers", List.of());

        when(registry.status()).thenReturn(new CheckWorkerRegistryImpl.Status(true, false, 3, 0, 2, 1, 40_000L, 0, 0, true));
        assertThat(ClusterRuntimeHealth.checkJobs(new ClusterRuntimeHealth.CheckJobsSources(
                checks(true, "port"), store, registry, null, null, null, null), NOW)).containsEntry("workers_error", "table_missing");

        when(registry.status()).thenReturn(new CheckWorkerRegistryImpl.Status(true, false, 3, 0, 2, 0, 40_000L, 1, 0, false));
        when(registry.workers()).thenReturn(List.of(worker("sm-b-0000000b", "sm-b", Set.of("port"), false, false)));
        assertThat(ClusterRuntimeHealth.checkJobs(new ClusterRuntimeHealth.CheckJobsSources(
                checks(true, "port"), store, registry, null, null, null, null), NOW)).containsEntry("workers_error", "stale");

        SchedulerService scheduler = mock(SchedulerService.class);
        when(scheduler.distributedCheckStatus()).thenThrow(new IllegalStateException("x"));
        CheckWorker w = mock(CheckWorker.class);
        when(w.status()).thenThrow(new IllegalStateException("y"));
        @SuppressWarnings("unchecked")
        Map<String, Object> pod = (Map<String, Object>) ClusterRuntimeHealth.checkJobs(new ClusterRuntimeHealth.CheckJobsSources(
                checks(true, "port"), store, registry, w, null, null, scheduler), NOW).get("this_pod");
        assertThat(pod).containsEntry("dispatch", null).containsEntry("dispatch_error", "IllegalStateException")
                .containsEntry("worker", Map.of("error", "IllegalStateException")).containsEntry("drainer", null)
                .containsEntry("db_clock_stale", null);
    }
}
