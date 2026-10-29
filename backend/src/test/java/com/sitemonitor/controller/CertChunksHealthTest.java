package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.certsweep.CertChunking;
import com.sitemonitor.service.certsweep.CertSweepStatus;
import com.sitemonitor.service.certsweep.CertSweepStore;
import com.sitemonitor.service.certsweep.CertSweepView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sistem Sağlığı {@code cert_chunks} + tarama kartı eklentisi (çok pod R3-4, paket C1; tasarım §7.1 / §2.3). Anahtar KAPALI →
 * yalnız {@code {enabled: false}}, depo HİÇ okunmaz, {@code scan} haritasına dokunulmaz (L-1). AÇIK → açık koşu (ilerleme, pod
 * payı, aykırı pod, en eski bekleyen), kapanmış son koşular, bu pod'un sayaçları, {@code scan.chunked} / {@code last_mode} /
 * {@code runs[tür].mode}. Okunamadı → {@code status_error}, kip "unknown" (asla klasik / yeşil uydurulmaz).
 */
class CertChunksHealthTest {

    /** 2026-10-29T09:00:00Z — {@link #data()} tarama koşuları bu güne ait (klasik kip yalnız saklama penceresi içinde, OB-L2 b). */
    static final long NOW = 1_793_264_400_000L;
    static final String A = "pod-a-0000000a";
    static final String B = "site-monitor-production-deployment-7f9c8d6b5a-q8m2zr-0000000b";

    AppSettingsService settings;
    CertChunking chunking;
    CertSweepStore store;
    SchedulerService scheduler;

    @BeforeEach
    void setUp() {
        settings = mock(AppSettingsService.class);
        chunking = new CertChunking(settings, null);
        store = mock(CertSweepStore.class);
        scheduler = mock(SchedulerService.class);
        when(scheduler.getMinNetworkErrors()).thenReturn(3);
        when(scheduler.getErrorRateThreshold()).thenReturn(0.5);
    }

    void switchOn(boolean on) {
        when(settings.getBoolean(eq(CertChunking.KEY_ENABLED), anyBoolean())).thenReturn(on);
    }

    static Map<String, Object> data() {
        Map<String, Object> runs = new LinkedHashMap<>();
        runs.put("hourly", new LinkedHashMap<>(Map.of("kind", "hourly", "run_id", "chunked-run", "last_run", "2026-10-29T08:01:00",
                "duration_ms", 50_000L)));
        runs.put("stale", new LinkedHashMap<>(Map.of("kind", "stale", "run_id", "classic-run", "last_run", "2026-10-29T08:30:00",
                "duration_ms", 2_000L)));
        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("last_run", "2026-10-29T08:30:00");
        scan.put("last_run_kind", "stale");
        scan.put("runs", runs);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("scan", scan);
        return d;
    }

    static CertSweepStatus.Run run(String id, String state, long createdMs, Long finishedMs, String outcome, String workersJson) {
        return new CertSweepStatus.Run(id, "hourly", B, state, true, 400, 20, 20, createdMs, NOW - 2_000, finishedMs, outcome,
                finishedMs == null ? null : 1, finishedMs == null ? null : 0, finishedMs == null ? null : 0, workersJson);
    }

    @Test
    @DisplayName("anahtar KAPALI → yalnız {enabled: false}; depo / zamanlayıcı okunmaz; scan haritası AYNI nesne, değişmedi (L-1)")
    void offIsMemoryOnly() {
        switchOn(false);
        Map<String, Object> d = data();
        Object scanBefore = d.get("scan");
        String snapshot = scanBefore.toString();
        CertChunksHealth.apply(d, new CertChunksHealth.Sources(chunking, store, scheduler));
        assertThat(d.get("cert_chunks")).isEqualTo(Map.of("enabled", false));
        assertThat(d.get("scan")).isSameAs(scanBefore);
        assertThat(d.get("scan").toString()).isEqualTo(snapshot);
        verifyNoInteractions(store, scheduler);
        // bean yok → alan yok
        Map<String, Object> d2 = data();
        CertChunksHealth.apply(d2, new CertChunksHealth.Sources(null, store, scheduler));
        assertThat(d2).doesNotContainKey("cert_chunks");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("açık: açık koşu ilerlemesi, pod payı (ağ hatası oranı yalnız ölçülenden), aykırı pod, en eski bekleyen; kapanmış son "
            + "koşular (açık hariç) pod adlarıyla; scan.chunked + last_mode + runs[tür].mode; bu pod'un işçi / koordinatör sayaçları")
    void onFullPayload() {
        switchOn(true);
        CertSweepStatus.Run open = run("open-run", "RUNNING", NOW - 45_000, null, null, null);
        CertSweepStatus.Run done = run("chunked-run", "DONE", NOW - 1_800_000, NOW - 1_750_000, "DECIDED",
                CertSweepStatus.encodeWorkers(List.of(new CertSweepStatus.WorkerTally(A, 10, 200, 1),
                        new CertSweepStatus.WorkerTally(B, 10, 200, 2))));
        CertSweepStatus.Run abandoned = run("ab-run", "ABANDONED", NOW - 3_600_000, NOW - 3_500_000, "SHUTDOWN", null);
        when(store.status(5)).thenReturn(new CertSweepStatus(NOW, false, List.of(open, done, abandoned), open, List.of(
                new CertSweepStatus.ChunkGroup("DONE", A, 4, 80, 40, NOW - 45_000),
                new CertSweepStatus.ChunkGroup("DONE", B, 6, 120, 2, NOW - 45_000),
                new CertSweepStatus.ChunkGroup("CLAIMED", B, 2, 40, 0, NOW - 45_000),
                new CertSweepStatus.ChunkGroup("PENDING", null, 8, 160, 0, NOW - 40_000))));
        when(store.tablesReady()).thenReturn(true);
        chunking.registerWorkerStatus(() -> new CertSweepStatus.Worker(true, true, 2, 40, 40, false, 90, 85, 1, 2, 0, 1700, NOW - 3_000, false));
        chunking.registerCoordinatorStatus(() -> new CertSweepStatus.Coordinator(12, 3, 2, 5, 0, 1));
        Map<String, Object> d = data();
        CertChunksHealth.apply(d, new CertChunksHealth.Sources(chunking, store, scheduler));
        Map<String, Object> cc = (Map<String, Object>) d.get("cert_chunks");
        assertThat(cc).containsEntry("enabled", true).containsEntry("tables_ready", true).containsEntry("db_clock_stale", false)
                .containsEntry("min_errors", 3).containsEntry("error_rate_threshold", 0.5).doesNotContainKey("status_error");
        Map<String, Object> r = (Map<String, Object>) cc.get("run");
        assertThat(r).containsEntry("run_id", "open-run").containsEntry("age_s", 45L).containsEntry("domains_total", 400)
                .containsEntry("domains_measured", 200L).containsEntry("oldest_pending_age_s", 40L)
                .containsEntry("coordinator_pod", "site-monitor-production-deployment-7f9c8d6b5a-q8m2zr").containsEntry("outlier_pods", 1);
        assertThat((Map<String, Object>) r.get("chunks")).containsEntry("pending", 8L).containsEntry("claimed", 2L)
                .containsEntry("done", 10L).containsEntry("cancelled", 0L);
        List<Map<String, Object>> pods = (List<Map<String, Object>>) r.get("per_pod");
        assertThat(pods).hasSize(2);
        Map<String, Object> podB = pods.get(0);
        assertThat(podB).containsEntry("pod", "site-monitor-production-deployment-7f9c8d6b5a-q8m2zr").containsEntry("domains", 160)
                .containsEntry("done_domains", 120).containsEntry("network_errors", 2).containsEntry("outlier", false);
        assertThat(pods.get(1)).containsEntry("pod", "pod-a").containsEntry("network_error_rate", 0.5).containsEntry("outlier", true);
        List<Map<String, Object>> last = (List<Map<String, Object>>) cc.get("last_runs");
        assertThat(last).extracting(m -> m.get("run_id")).containsExactly("chunked-run", "ab-run");
        assertThat(last.get(0)).containsEntry("duration_ms", 50_000L).containsEntry("pods", 2).containsEntry("outcome", "DECIDED");
        assertThat(last.get(1)).containsEntry("pods", null).containsEntry("state", "ABANDONED");
        Map<String, Object> pod = (Map<String, Object>) cc.get("this_pod");
        assertThat((Map<String, Object>) pod.get("worker")).containsEntry("budget", 40).containsEntry("late", 1L)
                .containsEntry("released", 2L);
        assertThat((Map<String, Object>) pod.get("coordinator")).containsEntry("takeovers", 2L).containsEntry("classic_fallback", 3L);

        Map<String, Object> scan = (Map<String, Object>) d.get("scan");
        Map<String, Object> chunked = (Map<String, Object>) scan.get("chunked");
        assertThat(chunked).containsEntry("run_id", "open-run").containsEntry("chunks_done", 10L).containsEntry("chunks_total", 20)
                .containsEntry("domains_measured", 200L);
        assertThat((List<?>) chunked.get("per_pod")).hasSize(2);
        Map<String, Object> runs = (Map<String, Object>) scan.get("runs");
        assertThat((Map<String, Object>) runs.get("hourly")).containsEntry("mode", CertSweepView.MODE_CHUNKED);
        // 3 koşu satırı < 5 istenen → listede olmayan "classic-run" klasik
        assertThat((Map<String, Object>) runs.get("stale")).containsEntry("mode", CertSweepView.MODE_CLASSIC);
        assertThat(scan).containsEntry("last_mode", CertSweepView.MODE_CLASSIC);
        assertThat(cc).containsEntry("last_mode", CertSweepView.MODE_CLASSIC);
        // zamanlayıcının haritası değişmedi (kopya)
        assertThat(((Map<String, Object>) data().get("scan"))).doesNotContainKey("last_mode");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("açık ama depo okunamadı → status_error, run / last_runs null, scan.chunked null + chunked_error, last_mode "
            + "unknown (asla klasik / yeşil); depo bean'i yok → unavailable; açık koşu yok → run null, scan.chunked null")
    void onUnreadableAndIdle() {
        switchOn(true);
        when(store.status(5)).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));
        Map<String, Object> d = data();
        CertChunksHealth.apply(d, new CertChunksHealth.Sources(chunking, store, scheduler));
        Map<String, Object> cc = (Map<String, Object>) d.get("cert_chunks");
        assertThat(cc).containsEntry("status_error", "QueryTimeoutException").containsEntry("run", null)
                .containsEntry("last_runs", null).containsEntry("last_mode", CertSweepView.MODE_UNKNOWN);
        Map<String, Object> scan = (Map<String, Object>) d.get("scan");
        assertThat(scan).containsEntry("chunked", null).containsEntry("chunked_error", "QueryTimeoutException")
                .containsEntry("last_mode", CertSweepView.MODE_UNKNOWN);
        assertThat((Map<String, Object>) ((Map<String, Object>) scan.get("runs")).get("hourly")).containsEntry("mode", CertSweepView.MODE_UNKNOWN);
        assertThat((Map<String, Object>) cc.get("this_pod")).containsEntry("worker", null).containsEntry("coordinator", null);

        Map<String, Object> d2 = data();
        CertChunksHealth.apply(d2, new CertChunksHealth.Sources(chunking, null, null));
        assertThat((Map<String, Object>) d2.get("cert_chunks")).containsEntry("status_error", "unavailable")
                .containsEntry("min_errors", 3);

        CertSweepStore idle = mock(CertSweepStore.class);
        when(idle.status(anyInt())).thenReturn(new CertSweepStatus(NOW, false, List.of(), null, List.of()));
        Map<String, Object> d3 = data();
        CertChunksHealth.apply(d3, new CertChunksHealth.Sources(chunking, idle, scheduler));
        assertThat((Map<String, Object>) d3.get("cert_chunks")).containsEntry("run", null).containsEntry("last_runs", List.of());
        assertThat((Map<String, Object>) d3.get("scan")).containsEntry("chunked", null).containsEntry("last_mode", CertSweepView.MODE_CLASSIC);

        // scan alanı yok (eski / test yanıtı) → yalnız cert_chunks
        Map<String, Object> d4 = new LinkedHashMap<>();
        CertChunksHealth.apply(d4, new CertChunksHealth.Sources(chunking, idle, scheduler));
        assertThat(d4).containsOnlyKeys("cert_chunks");
        assertThat((Map<String, Object>) d4.get("cert_chunks")).containsEntry("last_mode", CertSweepView.MODE_UNKNOWN);
    }

    /** Açık koşu + verilen kilit / zamanlayıcı haritasıyla sağlık yanıtı. */
    @SuppressWarnings("unchecked")
    Map<String, Object> withLock(CertSweepStatus.Run open, Map<String, Object> lock, Map<String, Object> scheduler) {
        when(store.status(5)).thenReturn(new CertSweepStatus(NOW, false, List.of(open), open, List.of(
                new CertSweepStatus.ChunkGroup("PENDING", null, 8, 160, 0, open.createdMs()))));
        Map<String, Object> d = data();
        if (lock != null) d.put("lock", lock);
        if (scheduler != null) d.put("scheduler", scheduler);
        CertChunksHealth.apply(d, new CertChunksHealth.Sources(chunking, store, scheduler()));
        return d;
    }

    SchedulerService scheduler() {
        return scheduler;
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("OB-L1: açık koşunun koordinatörü cert-check kilidini artık tutmuyor (başka pod tutuyor / canlı kilit yok) ve koşu "
            + "≥ 60 sn → orphan_suspected (DECIDING dahil), coordinator_live false, tarama kartı chunked.orphan_suspected; kilit "
            + "koordinatörde → canlı; bu pod yürütüyor → canlı; kilit okunamadı / genç koşu → sahipsiz değil (EK SQL YOK)")
    void orphanRunNotShownAsOpen() {
        switchOn(true);
        CertSweepStatus.Run old = run("open-run", "RUNNING", NOW - 300_000, null, null, null);
        Map<String, Object> d = withLock(old, Map.of("held", true, "locked_by", A), null);
        Map<String, Object> r = (Map<String, Object>) ((Map<String, Object>) d.get("cert_chunks")).get("run");
        assertThat(r).containsEntry("coordinator_live", false).containsEntry("orphan_suspected", true);
        assertThat((Map<String, Object>) ((Map<String, Object>) d.get("scan")).get("chunked")).containsEntry("orphan_suspected", true);

        // canlı kilit yok (satır yok ya da süresi dolmuş: held=false)
        d = withLock(old, Map.of("held", false), null);
        r = (Map<String, Object>) ((Map<String, Object>) d.get("cert_chunks")).get("run");
        assertThat(r).containsEntry("coordinator_live", false).containsEntry("orphan_suspected", true);

        // ölü DECIDING (nabız yazmaz) de tanınır
        CertSweepStatus.Run deadDeciding = run("dec-run", "DECIDING", NOW - 900_000, null, null, null);
        d = withLock(deadDeciding, Map.of("held", false, "expired", true, "locked_by", B), null);
        r = (Map<String, Object>) ((Map<String, Object>) d.get("cert_chunks")).get("run");
        assertThat(r).containsEntry("state", "DECIDING").containsEntry("orphan_suspected", true);

        // kilit koordinatörde (B) → canlı, sahipsiz değil
        d = withLock(old, Map.of("held", true, "locked_by", B), null);
        r = (Map<String, Object>) ((Map<String, Object>) d.get("cert_chunks")).get("run");
        assertThat(r).containsEntry("coordinator_live", true).containsEntry("orphan_suspected", false);
        assertThat((Map<String, Object>) ((Map<String, Object>) d.get("scan")).get("chunked")).containsEntry("orphan_suspected", false);

        // bu pod koşuyu şu an yürütüyor (kilit okunamasa bile)
        d = withLock(old, Map.of("held", false, "error", "x"), Map.of("running_here", true, "current_run_id", "open-run"));
        r = (Map<String, Object>) ((Map<String, Object>) d.get("cert_chunks")).get("run");
        assertThat(r).containsEntry("coordinator_live", true).containsEntry("orphan_suspected", false);

        // kilit okunamadı → bilinmiyor, sahipsiz denmez
        d = withLock(old, Map.of("held", false, "error", "x"), null);
        r = (Map<String, Object>) ((Map<String, Object>) d.get("cert_chunks")).get("run");
        assertThat(r).containsEntry("coordinator_live", null).containsEntry("orphan_suspected", false);
        // tutuluyor ama sahibi yok → bilinmiyor
        d = withLock(old, Map.of("held", true), null);
        r = (Map<String, Object>) ((Map<String, Object>) d.get("cert_chunks")).get("run");
        assertThat(r).containsEntry("coordinator_live", null).containsEntry("orphan_suspected", false);

        // genç koşu (kilit / koşu okuma yarışı) → sahipsiz değil
        CertSweepStatus.Run young = run("young-run", "RUNNING", NOW - 20_000, null, null, null);
        d = withLock(young, Map.of("held", true, "locked_by", A), null);
        r = (Map<String, Object>) ((Map<String, Object>) d.get("cert_chunks")).get("run");
        assertThat(r).containsEntry("coordinator_live", false).containsEntry("orphan_suspected", false);
        // tek durum okuması, başka SQL yok
        org.mockito.Mockito.verify(store, org.mockito.Mockito.atLeastOnce()).status(5);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).chunkStates(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("OB-L6: koordinatörün bölünmüş sayaçları ayrı yazılır — classic_by_rule (kural gereği klasik) ve classic_fallback "
            + "(gerçek düşüş); eski 6 bileşenli kurucu classic_by_rule = 0")
    void classicByRuleSplit() {
        chunking.registerCoordinatorStatus(() -> new CertSweepStatus.Coordinator(12, 3, 2, 5, 0, 1, 280));
        @SuppressWarnings("unchecked")
        Map<String, Object> cm = (Map<String, Object>) CertChunksHealth.thisPod(chunking).get("coordinator");
        assertThat(cm).containsEntry("classic_by_rule", 280L).containsEntry("classic_fallback", 3L)
                .containsEntry("chunked_runs", 12L);

        chunking.registerCoordinatorStatus(() -> new CertSweepStatus.Coordinator(12, 3, 2, 5, 0, 1));
        @SuppressWarnings("unchecked")
        Map<String, Object> old = (Map<String, Object>) CertChunksHealth.thisPod(chunking).get("coordinator");
        assertThat(old).containsEntry("classic_by_rule", 0L).containsEntry("classic_fallback", 3L);
    }
}
