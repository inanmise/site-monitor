package com.sitemonitor.controller;

import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.certsweep.CertChunking;
import com.sitemonitor.service.certsweep.CertSweepStatus;
import com.sitemonitor.service.certsweep.CertSweepStore;
import com.sitemonitor.service.certsweep.CertSweepView;
import com.sitemonitor.service.health.ClusterHealthStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sistem Sağlığı'nın çok pod Sürüm 3-4 alanları (2026-10-29, paket C1; tasarım R3-4 §2.3 / §7.1) — {@code SystemController}
 * yanıtına iki EKLENTİ:
 *
 * <ul>
 *   <li>{@code cert_chunks}: parçalı sertifika taraması. Anahtar KAPALI → yalnız {@code {enabled: false}} — tek bellek okuması
 *       ({@link CertChunking#enabled()}), SQL / günlük / iş parçacığı YOK (L-1: 20.170.0'a göre izin verilen tek fark bu alan).
 *       AÇIK → tek {@link CertSweepStore#status} çağrısı (son {@value #LAST_RUNS} koşu + açık koşunun gruplu parçaları): açık
 *       koşu (ilerleme, pod başına pay, aykırı pod, en eski bekleyen parça), son koşular (kapalı olanlar), bu pod'un işçi ve
 *       koordinatör sayaçları (bellek). Okunamadı → {@code status_error} + {@code run: null} (ekran "okunamadı" — asla "tur yok"
 *       ya da yeşil değil).</li>
 *   <li>{@code scan} kartı: yalnız AÇIKKEN, aynı okumadan {@code scan.chunked} (açık parçalı turun ilerlemesi; yoksa null),
 *       {@code scan.last_mode} (en yeni turun kipi — {@link CertSweepView#modeOf}) ve {@code scan.runs[tür].mode}. Kapalıyken
 *       {@code scan} haritasına DOKUNULMAZ.</li>
 * </ul>
 * Kişi verisi yok (pod adları, sayılar); {@code results_json} hiçbir zaman okunmaz. Zamanlar UTC {@code yyyy-MM-ddTHH:mm:ss}.
 */
final class CertChunksHealth {

    private CertChunksHealth() {}

    /** Okunan son koşu sayısı (tasarım §7.1). */
    static final int LAST_RUNS = 5;
    /** Gösterilen pod satırı üst sınırı. */
    static final int MAX_PODS = 50;

    /** Kaynaklar — {@code chunking} null ise çağıran alanı hiç eklemez; diğerleri yok = "bilinmiyor". */
    record Sources(CertChunking chunking, CertSweepStore store, SchedulerService scheduler) {}

    /**
     * Alanları {@code data}'ya ekler. KAPALI → {@code data.cert_chunks = {enabled: false}}, başka hiçbir şey (scan dahil).
     * Asla fırlatmaz — beklenmeyen hata {@code cert_chunks = {error}} olur.
     */
    static void apply(Map<String, Object> data, Sources s) {
        if (data == null || s == null || s.chunking() == null) return;
        Map<String, Object> m = new LinkedHashMap<>();
        boolean enabled = s.chunking().enabled();
        m.put("enabled", enabled);
        if (!enabled) {            // L-1: bellek okuması dışında hiçbir şey
            data.put("cert_chunks", m);
            return;
        }
        CertSweepStatus st = null;
        String error = null;
        if (s.store() == null) {
            error = "unavailable";
        } else {
            try {
                st = s.store().status(LAST_RUNS);
                if (st == null) error = "unavailable";
            } catch (RuntimeException e) {
                error = e.getClass().getSimpleName();
            }
        }
        Boolean tablesReady = null;
        if (s.store() != null) {
            try {
                tablesReady = s.store().tablesReady();
            } catch (RuntimeException e) {
                tablesReady = null;
            }
        }
        m.put("tables_ready", tablesReady);
        int minErrors = 3;
        double threshold = 0.5;
        if (s.scheduler() != null) {
            try {
                minErrors = s.scheduler().getMinNetworkErrors();
                threshold = s.scheduler().getErrorRateThreshold();
            } catch (RuntimeException e) {
                // varsayılanlar (application.properties ile aynı)
            }
        }
        if (st == null) {
            m.put("status_error", error);
            m.put("run", null);
            m.put("last_runs", null);
            m.put("last_mode", CertSweepView.MODE_UNKNOWN);
        } else {
            m.put("at", ClusterRuntimeHealth.iso(st.nowMs()));
            m.put("db_clock_stale", st.dbClockStale());
            m.put("run", st.openRun() == null ? null : openRun(st, minErrors, threshold));
            List<Map<String, Object>> last = new ArrayList<>();
            for (CertSweepStatus.Run r : st.lastRuns()) {
                if (r == null || r.open()) continue;
                last.add(closedRun(r));
            }
            m.put("last_runs", last);
        }
        m.put("min_errors", minErrors);
        m.put("error_rate_threshold", threshold);
        m.put("this_pod", thisPod(s.chunking()));
        decorateScan(data, m, st);
        m.putIfAbsent("last_mode", CertSweepView.MODE_UNKNOWN);
        data.put("cert_chunks", m);
    }

    /** Açık (RUNNING / DECIDING) koşu → yanıt haritası. */
    static Map<String, Object> openRun(CertSweepStatus st, int minErrors, double threshold) {
        CertSweepStatus.Run r = st.openRun();
        long now = st.nowMs();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", r.runId());
        m.put("kind", r.kind());
        m.put("state", r.state());
        m.put("coordinator", r.coordinator());
        m.put("coordinator_pod", ClusterHealthStore.podOf(r.coordinator()));
        m.put("created_at", ClusterRuntimeHealth.iso(r.createdMs()));
        m.put("age_s", r.ageMs(now) / 1000L);
        m.put("heartbeat_age_s", Math.max(0L, now - r.heartbeatMs()) / 1000L);
        m.put("domains_total", r.domainsTotal());
        m.put("chunks_total", r.chunksTotal());
        m.put("chunk_size", r.chunkSize());
        m.put("evict_caches", r.evictCaches());
        Map<String, Object> chunks = new LinkedHashMap<>();
        chunks.put("pending", st.chunks(CertSweepStore.CHUNK_PENDING));
        chunks.put("claimed", st.chunks(CertSweepStore.CHUNK_CLAIMED));
        chunks.put("done", st.chunks(CertSweepStore.CHUNK_DONE));
        chunks.put("cancelled", st.chunks(CertSweepStore.CHUNK_CANCELLED));
        m.put("chunks", chunks);
        m.put("domains_measured", st.domainsMeasured());
        Long oldest = st.oldestPendingAgeMs();
        m.put("oldest_pending_age_s", oldest == null ? null : oldest / 1000L);
        m.put("oldest_pending_age_ms", oldest);
        // Pod başına: parça / alan adı = ölçülen + uçuştaki; ağ hatası ve oran YALNIZ ölçülenden (uçuştaki oranı seyreltmesin)
        List<CertSweepStatus.WorkerTally> done = CertSweepView.doneTallies(st);
        Map<String, CertSweepStatus.WorkerTally> doneBy = new LinkedHashMap<>();
        for (CertSweepStatus.WorkerTally t : done) doneBy.put(t.worker(), t);
        Set<String> outliers = new HashSet<>(CertSweepView.outliers(done, minErrors, threshold));
        List<Map<String, Object>> pods = new ArrayList<>();
        for (CertSweepStatus.WorkerTally w : st.perWorker()) {
            if (pods.size() >= MAX_PODS) break;
            CertSweepStatus.WorkerTally d = doneBy.get(w.worker());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("instance_id", w.worker());
            p.put("pod", ClusterHealthStore.podOf(w.worker()));
            p.put("self", w.worker() != null && w.worker().equals(SchedulerService.instanceId()));
            p.put("chunks", w.chunks());
            p.put("domains", w.domains());
            p.put("done_domains", d == null ? 0 : d.domains());
            p.put("network_errors", d == null ? 0 : d.networkErrors());
            p.put("network_error_rate", d == null || d.domains() <= 0 ? null : round4(d.networkErrorRate()));
            p.put("outlier", outliers.contains(w.worker()));
            pods.add(p);
        }
        m.put("per_pod", pods);
        m.put("outlier_pods", outliers.size());
        m.put("takeovers", r.takeovers());
        m.put("reclaims", r.reclaims());
        m.put("second_opinions", r.secondOpinions());
        return m;
    }

    /** Kapanmış koşu → yanıt haritası ({@code workers_json} pod adlarıyla). */
    static Map<String, Object> closedRun(CertSweepStatus.Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", r.runId());
        m.put("kind", r.kind());
        m.put("state", r.state());
        m.put("outcome", r.outcome());
        m.put("coordinator_pod", ClusterHealthStore.podOf(r.coordinator()));
        m.put("created_at", ClusterRuntimeHealth.iso(r.createdMs()));
        m.put("finished_at", ClusterRuntimeHealth.iso(r.finishedMs()));
        m.put("duration_ms", r.durationMs());
        m.put("domains_total", r.domainsTotal());
        m.put("chunks_total", r.chunksTotal());
        List<CertSweepStatus.WorkerTally> ws = r.workers();
        List<Map<String, Object>> workers = new ArrayList<>();
        for (CertSweepStatus.WorkerTally w : ws) {
            if (workers.size() >= MAX_PODS) break;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("pod", ClusterHealthStore.podOf(w.worker()));
            p.put("chunks", w.chunks());
            p.put("domains", w.domains());
            p.put("network_errors", w.networkErrors());
            workers.add(p);
        }
        m.put("pods", r.workersJson() == null ? null : ws.size());
        m.put("workers", workers);
        m.put("takeovers", r.takeovers());
        m.put("reclaims", r.reclaims());
        m.put("second_opinions", r.secondOpinions());
        return m;
    }

    /** Bu pod'un işçi ve koordinatör sayaçları (bellek; kayıt yok → null). */
    static Map<String, Object> thisPod(CertChunking chunking) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pod", ClusterHealthStore.podOf(SchedulerService.instanceId()));
        CertSweepStatus.Worker w = chunking.workerStatus();
        if (w == null) {
            m.put("worker", null);
        } else {
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("enabled", w.workerEnabled());
            wm.put("started", w.started());
            wm.put("inflight_chunks", w.inflightChunks());
            wm.put("inflight_domains", w.inflightDomains());
            wm.put("budget", w.budget());
            wm.put("leader_yielding", w.leaderYielding());
            wm.put("claimed", w.claimed());
            wm.put("completed", w.completed());
            wm.put("late", w.late());
            wm.put("released", w.released());
            wm.put("discarded", w.discarded());
            wm.put("domains_measured", w.domainsMeasured());
            wm.put("last_claim_at", ClusterRuntimeHealth.iso(w.lastClaimAtMs()));
            wm.put("db_clock_stale", w.dbClockStale());
            m.put("worker", wm);
        }
        CertSweepStatus.Coordinator c = chunking.coordinatorStatus();
        if (c == null) {
            m.put("coordinator", null);
        } else {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("chunked_runs", c.chunkedRuns());
            cm.put("classic_fallback", c.classicFallback());
            cm.put("takeovers", c.takeovers());
            cm.put("remeasured", c.remeasured());
            cm.put("second_opinions", c.secondOpinions());
            cm.put("abandoned", c.abandoned());
            m.put("coordinator", cm);
        }
        return m;
    }

    /**
     * Tarama kartına EKLENTİ (yalnız anahtar açıkken): {@code scan.chunked} (açık parçalı turun ilerlemesi ya da null),
     * {@code scan.last_mode} ve {@code scan.runs[tür].mode}. Durum okunamadıysa {@code chunked: null} + {@code last_mode: unknown}
     * + {@code chunked_error}. {@code scan} harita değilse dokunulmaz. İç haritalar KOPYALANIR (zamanlayıcının haritası
     * değişmez).
     */
    @SuppressWarnings("unchecked")
    static void decorateScan(Map<String, Object> data, Map<String, Object> cc, CertSweepStatus st) {
        if (!(data.get("scan") instanceof Map<?, ?> raw)) return;
        Map<String, Object> scan = new LinkedHashMap<>((Map<String, Object>) raw);
        if (st == null) {
            scan.put("chunked", null);
            scan.put("chunked_error", cc.get("status_error"));
        } else if (st.openRun() == null) {
            scan.put("chunked", null);
        } else {
            Map<String, Object> run = (Map<String, Object>) cc.get("run");
            Map<String, Object> ch = new LinkedHashMap<>();
            ch.put("run_id", run.get("run_id"));
            ch.put("kind", run.get("kind"));
            ch.put("state", run.get("state"));
            ch.put("coordinator_pod", run.get("coordinator_pod"));
            ch.put("age_s", run.get("age_s"));
            ch.put("chunks_done", ((Map<String, Object>) run.get("chunks")).get("done"));
            ch.put("chunks_total", run.get("chunks_total"));
            ch.put("domains_measured", run.get("domains_measured"));
            ch.put("domains_total", run.get("domains_total"));
            ch.put("oldest_pending_age_s", run.get("oldest_pending_age_s"));
            List<Map<String, Object>> pods = new ArrayList<>();
            for (Map<String, Object> p : (List<Map<String, Object>>) run.get("per_pod")) {
                Map<String, Object> pp = new LinkedHashMap<>();
                pp.put("pod", p.get("pod"));
                pp.put("chunks", p.get("chunks"));
                pp.put("domains", p.get("domains"));
                pods.add(pp);
            }
            ch.put("per_pod", pods);
            scan.put("chunked", ch);
        }
        // Kipler: tür başına son tur ve en yeni tur
        String lastMode = CertSweepView.MODE_UNKNOWN;
        if (scan.get("runs") instanceof Map<?, ?> runsRaw) {
            Map<String, Object> runs = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : runsRaw.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> rr)) {
                    runs.put(String.valueOf(e.getKey()), e.getValue());
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>((Map<String, Object>) rr);
                r.put("mode", CertSweepView.modeOf(str(r.get("run_id")), str(r.get("last_run")), num(r.get("duration_ms")), st, LAST_RUNS));
                runs.put(String.valueOf(e.getKey()), r);
            }
            scan.put("runs", runs);
            Object lastKind = scan.get("last_run_kind");
            if (lastKind != null && runs.get(String.valueOf(lastKind)) instanceof Map<?, ?> lr) {
                lastMode = String.valueOf(((Map<String, Object>) lr).get("mode"));
            }
        }
        scan.put("last_mode", lastMode);
        cc.put("last_mode", lastMode);
        data.put("scan", scan);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long num(Object o) {
        return o instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
