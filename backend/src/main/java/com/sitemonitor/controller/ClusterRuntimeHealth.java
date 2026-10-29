package com.sitemonitor.controller;

import com.sitemonitor.service.evalstate.EvalLoadReport;
import com.sitemonitor.service.evalstate.EvalStateSink;
import com.sitemonitor.service.evalstate.EvalVerifyReport;
import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.schedule.SideJobRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sistem Sağlığı Küme kartının çok pod Sürüm 3-1 alanları (2026-10-23, paket B; tasarım R3-1 §4 B) — {@code SystemController}
 * yanıtına eklenen iki alanın biçimi, saf (veritabanı yalnız küme gözlemini okumak için, tek PK okuması):
 *
 * <ul>
 *   <li>{@code eval_state}: {@code this_pod} = BU POD'un kalıcı değerlendirme durumu kaydedicisi (kip, yazıyor mu, kuşak,
 *       bekleyen, yazma hatası, devralınan sayılar, son doğrulama turu) — kaydedici yok / durum yok → {@code null}
 *       (ekran "Bilinmiyor"); {@code verify} = KÜMENİN son {@code eval-state-verify} gözlemi (tarama lideri yayımlar,
 *       {@code observed_by} ile; satır yok → {@code null}); okunamadı / bozuk → {@code verify_error} (ekran "Bilinmiyor" —
 *       asla "0 uyuşmazlık" ya da yeşil değil).</li>
 *   <li>{@code side_jobs}: BU POD'un yan iş koşucusu ({@code SideJobRunner.status()}) — iş başına durum, ertelenen / bırakılan
 *       sayaçlar, lider erteleme kuralı ve "başka canlı pod" gözlemi ({@code null} = şu an önbellekte yok) +
 *       {@code other_live_pod_seen} = bu pod'da son görülen dolu gözlem ve zamanı (yalnız bellek; hiç görülmediyse yok).</li>
 * </ul>
 * Zamanlar UTC {@code yyyy-MM-ddTHH:mm:ss} (ekranın diğer alanlarıyla aynı). Kişi verisi yok; doğrulama anahtarları
 * yalnız kısa özet (gözlemdeki gibi).
 */
final class ClusterRuntimeHealth {

    private ClusterRuntimeHealth() {}

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    /** Doğrulama gözlemi bu kadar doğrulama aralığından eskiyse "eski" sayılır (ekran yeşil göstermez). */
    static final int VERIFY_STALE_ROUNDS = 3;
    /** Gösterilen iş sayısı üst sınırı (koşucu bugün ~15 iş taşır). */
    static final int MAX_JOBS = 50;

    // ── Kalıcı değerlendirme durumu ───────────────────────────────────────────────────────────────────────────

    /**
     * {@code eval_state} alanı. {@code sink == null} ve {@code store == null} ise çağıran alanı hiç eklemez.
     *
     * @param verifyMs doğrulama aralığı ({@code site.monitor.eval-state.verify-ms}) — bayatlık eşiği bunun
     *                 {@value #VERIFY_STALE_ROUNDS} katı
     */
    static Map<String, Object> evalState(EvalStateSink sink, ClusterHealthStore store, long nowMs, long verifyMs) {
        return evalState(sink, store, nowMs, verifyMs, null);
    }

    /**
     * {@link #evalState(EvalStateSink, ClusterHealthStore, long, long)} + bu pod'un tarama lideri ipucu (veritabanısız;
     * {@code SchedulerService.sweepLeaderHint()}). {@code fenced} yalnız bu pod liderken "yazmıyor" anlamı taşır: çit bayrağı
     * liderlik bitince de kalır (yalnız GAINED / kuşak sahibi sessizleşince kalkar) — lider olmayan pod'da uyarı olmasın.
     * {@code leaderHint == null} (zamanlayıcı yok — testler) → bayrak olduğu gibi.
     */
    static Map<String, Object> evalState(EvalStateSink sink, ClusterHealthStore store, long nowMs, long verifyMs,
                                         Boolean leaderHint) {
        return evalState(sink, store, nowMs, verifyMs, leaderHint, null);
    }

    /**
     * {@link #evalState(EvalStateSink, ClusterHealthStore, long, long, Boolean)} + çok pod Sürüm 3-2 alanları (2026-10-28, paket C;
     * tasarım R3-2 §8.3) — hepsi EKLENTİ (3-1 alanları birebir aynı):
     * <ul>
     *   <li>{@code read_enabled}: bu pod devirde okuma ayarını açık görüyor mu ({@code readEnabled}; null → kaydedici durumundan,
     *       kaydedici yoksa false);</li>
     *   <li>{@code this_pod.handover_ready | last_load | last_handover} ({@link #recorder(EvalStateSink.Status, Boolean)}) ve
     *       {@code this_pod.marker_columns_missing | closing} ({@link #readinessDetail});</li>
     *   <li>{@code load}: KÜMENİN son {@code eval-state-load} gözlemi (yeni lider yayımlar; {@code observed_by} / {@code observed_at}
     *       ile) — satır yok → {@code null}; okunamadı / bozuk / küme deposu yok → {@code load_error} (ekran "bilinmiyor", asla
     *       "yüklenmedi" değil).</li>
     * </ul>
     */
    static Map<String, Object> evalState(EvalStateSink sink, ClusterHealthStore store, long nowMs, long verifyMs,
                                         Boolean leaderHint, Boolean readEnabled) {
        Map<String, Object> out = new LinkedHashMap<>();
        EvalStateSink.Status status = null;
        if (sink == null) {
            out.put("this_pod", null);
        } else {
            try {
                status = sink.status();
                out.put("this_pod", status == null ? null : readinessDetail(recorder(status, leaderHint), sink));
            } catch (RuntimeException e) {
                status = null;
                out.put("this_pod", null);
                out.put("this_pod_error", e.getClass().getSimpleName());
            }
        }
        putVerify(out, store, nowMs, verifyMs);
        // ── çok pod Sürüm 3-2 (eklenti) ──
        out.put("read_enabled", readEnabled != null ? readEnabled : status != null && status.readEnabled());
        putLoad(out, store, nowMs);
        return out;
    }

    /**
     * Devir hazırlığının GERÇEK nedeni için iki bellek bayrağı (çok pod Sürüm 3-2, inceleme 2026-10-28 L-3; ekleme, 3-1 ve
     * önceki 3-2 alanlarından SONRA): {@code marker_columns_missing} = kaydedici devir işareti sütunlarını okuyamıyor (ilk
     * açılış ya da şema yaması başarısız — sonraki açılışta yeniden denenir; kaydedici değilse {@code null} = bilinmiyor) ve
     * {@code closing} = kaydedici kapanıyor ({@code loadBlocker() == recorder_closing}; okunamazsa {@code null}). Ekran
     * "eşitleniyor" yerine bunları söyler. Veritabanı yok.
     */
    static Map<String, Object> readinessDetail(Map<String, Object> pod, EvalStateSink sink) {
        Boolean columnsMissing = null;
        if (sink instanceof com.sitemonitor.service.evalstate.EvalStateRecorder rec) {
            try {
                columnsMissing = rec.markerColumnsMissingNow();
            } catch (RuntimeException e) {
                columnsMissing = null;
            }
        }
        pod.put("marker_columns_missing", columnsMissing);
        Boolean closing;
        try {
            closing = EvalLoadReport.REASON_RECORDER_CLOSING.equals(sink.loadBlocker());
        } catch (RuntimeException e) {
            closing = null;
        }
        pod.put("closing", closing);
        return pod;
    }

    /** Kümenin son doğrulama gözlemi ({@code verify} / {@code verify_error}) — 3-1 biçimi. */
    private static void putVerify(Map<String, Object> out, ClusterHealthStore store, long nowMs, long verifyMs) {
        if (store == null) {
            out.put("verify", null);
            out.put("verify_error", "unavailable");
            return;
        }
        Optional<ClusterHealthStore.Observation> row;
        try {
            row = store.read(ClusterHealthStore.OBS_EVAL_STATE_VERIFY);
        } catch (RuntimeException e) {
            out.put("verify", null);
            out.put("verify_error", e.getClass().getSimpleName());
            return;
        }
        if (row == null || row.isEmpty()) {
            out.put("verify", null);
            return;
        }
        Map<String, Object> v = verify(row.get(), nowMs, verifyMs);
        if (v == null) {
            out.put("verify", null);
            out.put("verify_error", "unreadable_payload");
        } else {
            out.put("verify", v);
        }
    }

    /**
     * Kümenin son devir yüklemesi gözlemi ({@code load} / {@code load_error}; çok pod Sürüm 3-2). Tek PK okuması, yazma yok.
     */
    private static void putLoad(Map<String, Object> out, ClusterHealthStore store, long nowMs) {
        if (store == null) {
            out.put("load", null);
            out.put("load_error", "unavailable");
            return;
        }
        Optional<ClusterHealthStore.Observation> row;
        try {
            row = store.read(EvalLoadReport.OBSERVATION);
        } catch (RuntimeException e) {
            out.put("load", null);
            out.put("load_error", e.getClass().getSimpleName());
            return;
        }
        if (row == null || row.isEmpty()) {
            out.put("load", null);
            return;
        }
        Map<String, Object> l = loadObservation(row.get(), nowMs);
        if (l == null) {
            out.put("load", null);
            out.put("load_error", "unreadable_payload");
        } else {
            out.put("load", l);
        }
    }

    /**
     * Devir yüklemesi gözlemi → yanıt haritası; yük okunamazsa {@code null}. Alanlar = {@link #loadReport(EvalLoadReport)}
     * (bu pod'un {@code last_load} ile AYNI adlar) + {@code observed_by} / {@code observed_by_pod} / {@code observed_at} /
     * {@code age_seconds}.
     */
    static Map<String, Object> loadObservation(ClusterHealthStore.Observation row, long nowMs) {
        EvalLoadReport r = EvalLoadReport.fromJson(row.payload());
        if (r == null || r.outcome() == null) return null;
        Map<String, Object> m = loadReport(r);
        m.put("observed_by", row.instanceId());
        m.put("observed_by_pod", ClusterHealthStore.podOf(row.instanceId()));
        m.put("observed_at", iso(row.observedMs()));
        m.put("age_seconds", Math.max(0L, nowMs - row.observedMs()) / 1000L);
        return m;
    }

    /**
     * Yükleme raporu → yanıt haritası: {@link EvalLoadReport#asMap()} (SNAKE_CASE, kalıcı adlar) + ekran için türetilmiş
     * {@code at} (UTC ISO), {@code pod_name} / {@code from_pod_name} (örnek kimliğinden pod adı). Kişi verisi yok.
     */
    static Map<String, Object> loadReport(EvalLoadReport r) {
        Map<String, Object> m = new LinkedHashMap<>(r.asMap());
        m.put("at", iso(r.atMs()));
        m.put("pod_name", r.pod() == null ? null : ClusterHealthStore.podOf(r.pod()));
        m.put("from_pod_name", r.fromPod() == null ? null : ClusterHealthStore.podOf(r.fromPod()));
        return m;
    }

    /** Bu pod'un kaydedici durumu → yanıt haritası. */
    static Map<String, Object> recorder(EvalStateSink.Status s) {
        return recorder(s, null);
    }

    /** Bu pod'un kaydedici durumu → yanıt haritası; {@code leaderHint} için bkz. {@link #evalState}. */
    static Map<String, Object> recorder(EvalStateSink.Status s, Boolean leaderHint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", s.mode());
        m.put("active", s.active());
        m.put("writer", s.writer());
        m.put("writer_pod", ClusterHealthStore.podOf(s.writer()));
        m.put("generation", s.generation());
        m.put("pending", s.pending());
        m.put("dropped", s.dropped());
        m.put("flushes", s.flushes());
        m.put("write_failures", s.writeFailures());
        m.put("last_flush_at", iso(s.lastFlushMs()));
        m.put("last_failure_at", iso(s.lastFailureMs()));
        m.put("last_failure", s.lastFailure());
        m.put("table_missing", s.tableMissing());
        // Çitlenmiş lider (R3-1 inceleme durable #1): bu pod lider ama kuşağı başka pod aldı — yazmıyor, kuşak sahibi
        // sessizleşince kendini onarır. Lider olmayan pod'un eski çit bayrağı gösterilmez.
        m.put("fenced", s.fenced() && (leaderHint == null || leaderHint));
        m.put("hook_failures", s.hookFailures());
        // Şu an yazamıyor mu: YAZAN kaydedicinin son hatası son başarılı boşaltmadan yeni (ya da hiç başarılı boşaltma yok).
        // Yazmayan (eski lider, çitlenmiş, kapalı) kaydedici yeniden denemez — eski hatası "şu an başarısız" sayılmaz (R3-1
        // inceleme M2: liderliği DB kesintisinde kaybeden pod günlerce uyarı gösteriyordu).
        m.put("failing", s.active() && s.lastFailureMs() != null
                && (s.lastFlushMs() == null || s.lastFailureMs() > s.lastFlushMs()));
        EvalStateSink.Inherited in = s.inherited();
        if (in == null) {
            m.put("inherited", null);
        } else {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("at", iso(in.atMs()));
            i.put("previous_generation", in.previousGeneration());
            i.put("recovery_counters", in.recoveryCounters());
            i.put("down_siblings", in.downSiblings());
            i.put("active_suppressions", in.activeSuppressions());
            i.put("abandoned_chains", in.abandonedChains());
            m.put("inherited", i);
        }
        EvalVerifyReport v = s.lastVerify();
        if (v == null) {
            m.put("last_verify", null);
        } else {
            Map<String, Object> lv = new LinkedHashMap<>();
            lv.put("at", iso(v.atMs()));
            lv.put("status", v.status());
            lv.put("reason", v.reason());
            lv.put("pass_fail_mismatches", v.unknown() ? null : v.passFailMismatches());
            m.put("last_verify", lv);
        }
        // ── çok pod Sürüm 3-2 (eklenti): devir hazırlığı, son yükleme, son devir boşaltması ──
        m.put("handover_ready", s.handoverReady());
        m.put("last_load", s.lastLoad() == null ? null : loadReport(s.lastLoad()));
        m.put("last_handover", s.lastHandover() == null ? null : s.lastHandover().key());
        return m;
    }

    /**
     * Küme gözlemi → yanıt haritası; yük okunamazsa {@code null}. Yük, {@link EvalVerifyReport}'un SNAKE_CASE JSON'udur
     * (paket A2); camelCase adlar da kabul edilir (yayımlayanın eşleyici ayarından bağımsız).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> verify(ClusterHealthStore.Observation row, long nowMs, long verifyMs) {
        Map<String, Object> p;
        try {
            if (row.payload() == null || row.payload().isBlank()) return null;
            Object o = JSON.readValue(row.payload(), Map.class);
            if (!(o instanceof Map<?, ?> mm)) return null;
            p = (Map<String, Object>) mm;
        } catch (RuntimeException e) {
            return null;
        }
        String status = str(pick(p, "status"));
        if (status == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("observed_by", row.instanceId());
        m.put("observed_by_pod", ClusterHealthStore.podOf(row.instanceId()));
        m.put("observed_at", iso(row.observedMs()));
        long ageMs = Math.max(0L, nowMs - row.observedMs());
        long staleAfterMs = Math.max(60_000L, verifyMs) * VERIFY_STALE_ROUNDS;
        m.put("age_seconds", ageMs / 1000L);
        m.put("stale_after_seconds", staleAfterMs / 1000L);
        m.put("stale", ageMs > staleAfterMs);
        Long at = num(pick(p, "at_ms", "atMs"));
        m.put("at", at == null ? null : iso(at));
        m.put("pod", str(pick(p, "pod")));
        m.put("generation", num(pick(p, "generation")));
        m.put("status", status);
        m.put("reason", str(pick(p, "reason")));
        Map<String, Object> kinds = new LinkedHashMap<>();
        int passFail = 0;
        if (pick(p, "kinds") instanceof Map<?, ?> km) {
            // Bilinen türler önce ve sabit sırada; bilinmeyen (yeni sürümün) türleri sonda
            List<String> order = new ArrayList<>(EvalVerifyReport.KINDS);
            for (Object k : km.keySet()) if (k != null && !order.contains(String.valueOf(k))) order.add(String.valueOf(k));
            for (String k : order) {
                if (!(km.get(k) instanceof Map<?, ?> r)) continue;
                Map<String, Object> kr = new LinkedHashMap<>();
                Long mismatches = num(pick((Map<String, Object>) r, "mismatches"));
                kr.put("compared", num(pick((Map<String, Object>) r, "compared")));
                kr.put("mismatches", mismatches);
                kr.put("skipped", num(pick((Map<String, Object>) r, "skipped")));
                kr.put("max_delta", dbl(pick((Map<String, Object>) r, "max_delta", "maxDelta")));
                kr.put("measured", EvalVerifyReport.MEASURED_KINDS.contains(k));
                if (EvalVerifyReport.PASS_FAIL_KINDS.contains(k) && mismatches != null) passFail += (int) Math.max(0, mismatches);
                kinds.put(k, kr);
            }
        }
        m.put("kinds", kinds);
        m.put("pass_fail_mismatches", EvalVerifyReport.STATUS_UNKNOWN.equals(status) ? null : passFail);
        List<String> first = new ArrayList<>();
        if (pick(p, "first_mismatches", "firstMismatches") instanceof List<?> l) {
            for (Object o : l) {
                if (o != null && first.size() < EvalVerifyReport.MAX_KEYS) first.add(String.valueOf(o));
            }
        }
        m.put("first_mismatches", first);
        return m;
    }

    // ── Yan işler ─────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * "Başka canlı pod" gözleminin bu pod'da SON görülen hâli (R3-1 inceleme L4). Koşucunun gözlemi canlılık önbelleğidir ve
     * yalnız liderin yerleşim kararından sonraki ~1 dk dolu döner (yan iş tetikleri, 5 dk'lık tarama tıkı); Sistem Sağlığı
     * istekleri arasında son dolu değer ve zamanı burada tutulur — ekran sürekli "bilinmiyor" yerine "son görülen" der.
     * Yalnız bellek, veritabanı yok.
     */
    static final class OtherPodSeen {
        private volatile Boolean value;
        private volatile long atMs;

        void note(Boolean v, long nowMs) {
            if (v == null) return;
            atMs = nowMs;
            value = v;
        }

        Boolean value() {
            return value;
        }

        long atMs() {
            return atMs;
        }
    }

    /** {@code side_jobs} alanı; durum okunamazsa {@code {error}}. */
    static Map<String, Object> sideJobs(SideJobRunner runner) {
        return sideJobs(runner, null, System.currentTimeMillis());
    }

    /**
     * {@code side_jobs} alanı. {@code seen} verilirse koşucunun dolu "başka canlı pod" gözlemi kaydedilir ve son görülen
     * değer {@code other_live_pod_seen} = {@code {value, at}} olarak eklenir (hiç görülmediyse alan yok).
     */
    static Map<String, Object> sideJobs(SideJobRunner runner, OtherPodSeen seen, long nowMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        SideJobRunner.Status s;
        try {
            s = runner.status();
        } catch (RuntimeException e) {
            m.put("error", e.getClass().getSimpleName());
            return m;
        }
        if (s == null) {
            m.put("error", "unavailable");
            return m;
        }
        m.put("threads", s.threads());
        m.put("accepting", s.accepting());
        m.put("leader_yield_enabled", s.leaderYieldEnabled());
        m.put("leader_hint", s.leaderHint());
        m.put("other_live_pod", s.otherLivePod());
        if (seen != null) {
            seen.note(s.otherLivePod(), nowMs);
            Boolean last = seen.value();
            if (last != null) {
                Map<String, Object> sv = new LinkedHashMap<>();
                sv.put("value", last);
                sv.put("at", iso(seen.atMs()));
                m.put("other_live_pod_seen", sv);
            }
        }
        List<Map<String, Object>> jobs = new ArrayList<>();
        long deferred = 0, yielded = 0, skipped = 0;
        int running = 0, queued = 0, failed = 0;
        for (SideJobRunner.Status.Job j : s.jobs()) {
            if (j == null) continue;
            deferred += j.deferredCount();
            yielded += j.yieldedCount();
            skipped += j.skippedCount();
            if (SideJobRunner.STATE_RUNNING.equals(j.state())) running++;
            if (SideJobRunner.STATE_QUEUED.equals(j.state())) queued++;
            if (SideJobRunner.OUTCOME_FAILED.equals(j.lastOutcome())) failed++;
            if (jobs.size() >= MAX_JOBS) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", j.name());
            r.put("state", j.state());
            r.put("started_at", iso(j.startedAtMs()));
            r.put("last_finished_at", iso(j.lastFinishedAtMs()));
            r.put("last_outcome", j.lastOutcome());
            r.put("runs", j.runs());
            r.put("deferred_count", j.deferredCount());
            r.put("yielded_count", j.yieldedCount());
            r.put("skipped_count", j.skippedCount());
            jobs.add(r);
        }
        m.put("running", running);
        m.put("queued", queued);
        m.put("failed", failed);
        m.put("deferred_total", deferred);
        m.put("yielded_total", yielded);
        m.put("skipped_total", skipped);
        m.put("jobs", jobs);
        return m;
    }

    // ── R3-3 D1: dağıtık kontroller (çok pod Sürüm 3-3, tasarım R3-3 §7.1) ─────────────────────────────────────────

    /** Gösterilen işçi satırı üst sınırı (kayıt en çok 200 satır okur; Küme kartı için fazlası anlamsız). */
    static final int MAX_WORKERS = 50;

    /**
     * {@code check_jobs} alanının kaynakları — hepsi isteğe bağlı (bean yok = alan "bilinmiyor"); {@code settings} null ise
     * çağıran alanı hiç eklemez.
     */
    record CheckJobsSources(com.sitemonitor.service.checks.DistributedChecks settings,
                            com.sitemonitor.service.checks.CheckJobStore store,
                            com.sitemonitor.service.checks.CheckWorkerRegistry registry,
                            com.sitemonitor.service.checks.CheckWorker worker,
                            com.sitemonitor.service.checks.CheckResultDrainer drainer,
                            com.sitemonitor.service.checks.DbClock clock,
                            com.sitemonitor.service.SchedulerService scheduler) {}

    /**
     * {@code check_jobs} alanı (eklenti). Ana anahtar KAPALI → yalnız {@code {enabled: false}} — tek bellek okuması
     * ({@code DistributedChecks.enabled()}), SQL / iş parçacığı / günlük YOK (K-1). AÇIK:
     * <ul>
     *   <li>{@code types} (ayar), {@code mode{tür: distributed | classic}} — YANITLAYAN pod'un görüşü
     *       ({@code SchedulerService.distributedCheckStatus()}, bellek; yalnız liderde anlamlı → {@code mode_by_pod},
     *       {@code mode_by_leader});</li>
     *   <li>{@code queue} — tek gruplu deyim ({@code CheckJobStore.stats()}, yalnız {@code anyDistributed()} iken): durum ×
     *       tür sayıları, en eski bekleyen / değerlendirilmeyen yaşı, kirası dolmuş sahiplenme; okunamadı → {@code queue: null}
     *       + {@code queue_error} (ekran "bilinmiyor", asla sahte 0 / yeşil değil); tür seçilmedi → {@code queue_skipped};</li>
     *   <li>{@code workers[]} — kayıt önbelleği ({@code monitor_check_workers}, bellek): pod, yetenekler, eksik yetenek
     *       (ayardaki türlerden), yuva / meşgul, tamamlanan / geç, sahiplenme gecikmesi p95, nabız yaşı, durum; önbellek hiç
     *       okunmadı / okunamadı / 30 sn'den eski → {@code workers_known: false} + {@code workers_error};</li>
     *   <li>{@code this_pod} — bu pod'un işçisi, yerel yetenekleri, kaydı, DB saati, gönderme sayaçları ve (liderde anlamlı)
     *       sonuç boşaltıcısı; hepsi bellek.</li>
     * </ul>
     * Kişi verisi yok; {@code result_json} hiçbir zaman okunmaz.
     */
    static Map<String, Object> checkJobs(CheckJobsSources s, long nowMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean enabled = s != null && s.settings() != null && s.settings().enabled();
        m.put("enabled", enabled);
        if (!enabled) return m;   // K-1: anahtar kapalı → bellek okuması dışında hiçbir şey
        java.util.Set<String> types = s.settings().types();
        m.put("types", new ArrayList<>(types));
        String selfId = com.sitemonitor.service.SchedulerService.instanceId();
        Boolean leader = null;
        Map<String, Object> dispatch = null;
        String dispatchError = null;
        if (s.scheduler() != null) {
            try {
                leader = s.scheduler().sweepLeaderHint();
            } catch (RuntimeException e) {
                leader = null;
            }
            try {
                dispatch = s.scheduler().distributedCheckStatus();
            } catch (RuntimeException e) {
                dispatchError = e.getClass().getSimpleName();
            }
        }
        Map<String, Object> mode = new LinkedHashMap<>();
        Object rawMode = dispatch == null ? null : dispatch.get("mode");
        for (String t : com.sitemonitor.service.checks.CheckJobTypes.ALL) {
            Object v = rawMode instanceof Map<?, ?> mm ? mm.get(t) : null;
            mode.put(t, v == null ? null : String.valueOf(v));
        }
        m.put("mode", mode);
        m.put("mode_by", selfId);
        m.put("mode_by_pod", ClusterHealthStore.podOf(selfId));
        m.put("mode_by_leader", leader);

        // Kuyruk — tek gruplu deyim, yalnız tür seçiliyken (CheckJobStore sözleşmesi: anyDistributed() false iken çağrılmaz)
        com.sitemonitor.service.checks.CheckJobStore.QueueStats stats = null;
        if (types.isEmpty()) {
            m.put("queue", null);
            m.put("queue_skipped", "no_types");
        } else if (s.store() == null) {
            m.put("queue", null);
            m.put("queue_error", "unavailable");
        } else {
            try {
                stats = s.store().stats();
                m.put("queue", stats == null ? null : queue(stats, types));
                if (stats == null) m.put("queue_error", "unavailable");
            } catch (RuntimeException e) {
                m.put("queue", null);
                m.put("queue_error", e.getClass().getSimpleName());
            }
        }

        // İşçiler — kayıt önbelleği (bellek); DB saatini kuyruk okumasından al (ek sorgu yok), yoksa DB-çapalı saat
        long dbNow = stats != null ? stats.nowMs() : s.clock() != null ? safeNow(s.clock(), nowMs) : nowMs;
        putWorkers(m, s.registry(), types, selfId, dbNow);

        Map<String, Object> pod = new LinkedHashMap<>();
        pod.put("pod", ClusterHealthStore.podOf(selfId));
        pod.put("leader", leader);
        pod.put("worker", s.worker() == null ? null : worker(s.worker()));
        java.util.Set<String> caps = null;
        if (s.registry() != null) {
            try {
                caps = s.registry().localCapabilities();
            } catch (RuntimeException e) {
                caps = null;
            }
        }
        pod.put("local_caps", caps == null ? null : new ArrayList<>(caps));
        pod.put("registry", s.registry() instanceof com.sitemonitor.service.checks.CheckWorkerRegistryImpl impl ? registry(impl) : null);
        Boolean clockStale = null;
        if (s.clock() != null) {
            try {
                clockStale = s.clock().stale();
            } catch (RuntimeException e) {
                clockStale = null;
            }
        }
        pod.put("db_clock_stale", clockStale);
        if (dispatch != null) {
            Map<String, Object> d = new LinkedHashMap<>(dispatch);
            d.remove("mode");
            pod.put("dispatch", d);
        } else {
            pod.put("dispatch", null);
            if (dispatchError != null) pod.put("dispatch_error", dispatchError);
        }
        pod.put("drainer", s.drainer() == null ? null : drainer(s.drainer()));
        m.put("this_pod", pod);
        return m;
    }

    /** Kuyruk özeti → yanıt haritası (sayılar sıfır dahil; tür kırılımı = ayardaki türler + satırı olan türler). */
    static Map<String, Object> queue(com.sitemonitor.service.checks.CheckJobStore.QueueStats st, java.util.Set<String> types) {
        String pending = com.sitemonitor.service.checks.CheckJobStore.STATE_PENDING;
        String claimed = com.sitemonitor.service.checks.CheckJobStore.STATE_CLAIMED;
        String done = com.sitemonitor.service.checks.CheckJobStore.STATE_DONE;
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("at", iso(st.nowMs()));
        q.put("pending", st.count(pending));
        q.put("claimed", st.count(claimed));
        q.put("done", st.count(done));
        q.put("oldest_pending_age_ms", st.oldestPendingAgeMs());
        q.put("oldest_done_age_ms", st.oldestDoneAgeMs());
        q.put("expired_claims", st.expiredClaims());
        List<String> order = new ArrayList<>();
        java.util.Set<String> withRows = st.types();
        for (String t : com.sitemonitor.service.checks.CheckJobTypes.ALL) {
            if (types.contains(t) || withRows.contains(t)) order.add(t);
        }
        for (String t : withRows) if (!order.contains(t)) order.add(t);   // yeni sürümün türü (bilinmeyen) sonda
        Map<String, Object> byType = new LinkedHashMap<>();
        for (String t : order) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("pending", st.count(pending, t));
            r.put("claimed", st.count(claimed, t));
            r.put("done", st.count(done, t));
            byType.put(t, r);
        }
        q.put("by_type", byType);
        return q;
    }

    private static void putWorkers(Map<String, Object> m, com.sitemonitor.service.checks.CheckWorkerRegistry registry,
                                   java.util.Set<String> types, String selfId, long dbNow) {
        if (registry == null) {
            m.put("workers", null);
            m.put("workers_known", false);
            m.put("workers_error", "unavailable");
            return;
        }
        List<com.sitemonitor.service.checks.CheckWorkerRegistry.WorkerView> views;
        boolean known;
        try {
            views = registry.workers();
            // Önbellek hiç okunmadı / son okuma hatalı / 30 sn'den eski → liveOtherWorkers UNKNOWN (kip kararı da klasik)
            known = registry.liveOtherWorkers(com.sitemonitor.service.checks.CheckJobTypes.PORT)
                    != com.sitemonitor.service.checks.CheckWorkerRegistry.UNKNOWN;
        } catch (RuntimeException e) {
            m.put("workers", null);
            m.put("workers_known", false);
            m.put("workers_error", e.getClass().getSimpleName());
            return;
        }
        if (views == null) views = List.of();
        m.put("workers_known", known);
        if (!known) {
            String why = "unreadable";
            if (registry instanceof com.sitemonitor.service.checks.CheckWorkerRegistryImpl impl) {
                try {
                    var st = impl.status();
                    if (st.tableMissing()) why = "table_missing";
                    else if (st.reads() == 0 && st.readFailures() == 0) why = "not_read_yet";
                    else if (!views.isEmpty()) why = "stale";
                } catch (RuntimeException e) {
                    why = "unreadable";
                }
            } else if (!views.isEmpty()) {
                why = "stale";
            }
            m.put("workers_error", why);
        }
        List<com.sitemonitor.service.checks.CheckWorkerRegistry.WorkerView> sorted = new ArrayList<>(views);
        sorted.removeIf(java.util.Objects::isNull);
        sorted.sort(java.util.Comparator
                .comparing((com.sitemonitor.service.checks.CheckWorkerRegistry.WorkerView v) -> !v.live())
                .thenComparing(v -> v.pod() == null ? String.valueOf(v.instanceId()) : v.pod())
                .thenComparing(v -> String.valueOf(v.instanceId())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var v : sorted) {
            if (out.size() >= MAX_WORKERS) break;
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("instance_id", v.instanceId());
            w.put("pod", v.pod() != null && !v.pod().isBlank() ? v.pod() : ClusterHealthStore.podOf(v.instanceId()));
            w.put("self", selfId != null && selfId.equals(v.instanceId()));
            w.put("caps", new ArrayList<>(v.caps()));
            List<String> missing = new ArrayList<>();
            for (String t : types) if (!v.caps().contains(t)) missing.add(t);
            w.put("missing_caps", missing);
            w.put("slots", v.slots());
            w.put("busy", v.busy());
            w.put("completed", v.completed());
            w.put("late", v.late());
            w.put("claim_lag_p95_ms", v.claimLagP95Ms());
            w.put("heartbeat_at", iso(v.heartbeatMs()));
            w.put("heartbeat_age_ms", v.heartbeatMs() > 0 ? Math.max(0L, dbNow - v.heartbeatMs()) : null);
            w.put("version", v.version());
            w.put("stopping", v.stopping());
            w.put("live", v.live());
            out.add(w);
        }
        m.put("workers", out);
        m.put("workers_total", sorted.size());
    }

    private static Map<String, Object> worker(com.sitemonitor.service.checks.CheckWorker worker) {
        com.sitemonitor.service.checks.CheckWorker.Status st;
        try {
            st = worker.status();
        } catch (RuntimeException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            return err;
        }
        if (st == null) return null;
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("started", st.started());
        w.put("enabled", st.workerEnabled());
        w.put("closing", st.closing());
        w.put("slots", st.slots());
        w.put("busy", st.busy());
        w.put("claimed", st.claimed());
        w.put("completed", st.completed());
        w.put("late", st.late());
        w.put("timeouts", st.timeouts());
        w.put("failed", st.failed());
        w.put("released", st.released());
        w.put("claim_errors", st.claimErrors());
        w.put("complete_errors", st.completeErrors());
        w.put("dropped_at_shutdown", st.droppedAtShutdown());
        w.put("claim_lag_p95_ms", st.claimLagP95Ms());
        w.put("poll_delay_ms", st.pollDelayMs());
        return w;
    }

    private static Map<String, Object> registry(com.sitemonitor.service.checks.CheckWorkerRegistryImpl impl) {
        com.sitemonitor.service.checks.CheckWorkerRegistryImpl.Status st;
        try {
            st = impl.status();
        } catch (RuntimeException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            return err;
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("started", st.started());
        r.put("closing", st.closing());
        r.put("heartbeats", st.heartbeats());
        r.put("heartbeat_failures", st.heartbeatFailures());
        r.put("reads", st.reads());
        r.put("read_failures", st.readFailures());
        r.put("last_read_age_ms", st.lastReadAgeMs());
        r.put("known_workers", st.knownWorkers());
        r.put("table_missing", st.tableMissing());
        return r;
    }

    private static Map<String, Object> drainer(com.sitemonitor.service.checks.CheckResultDrainer drainer) {
        com.sitemonitor.service.checks.CheckResultDrainer.Status st;
        try {
            st = drainer.status();
        } catch (RuntimeException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getClass().getSimpleName());
            return err;
        }
        if (st == null) return null;
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("started", st.started());
        d.put("draining", st.draining());
        d.put("caught_up", st.caughtUp());
        d.put("holding_manual", st.holdingManual());
        d.put("taken", st.taken());
        d.put("waves", st.waves());
        d.put("applied", st.applied());
        d.put("dropped_stale", st.droppedStale());
        d.put("out_of_order", st.outOfOrder());
        d.put("apply_failures", st.applyFailures());
        d.put("take_failures", st.takeFailures());
        d.put("load_waits", st.loadWaits());
        d.put("manual_holds", st.manualHolds());
        d.put("last_take_at", st.lastTakeAtMs() > 0 ? iso(st.lastTakeAtMs()) : null);
        d.put("last_failure", st.lastFailure());
        // FX-EVAL düşürmeleri (eklemeli): yeniden açma öncesi / klasik sonrası / devirde değerlendirilmeyen
        d.put("dropped_obsolete", st.droppedObsolete());
        d.put("dropped_superseded", st.droppedSuperseded());
        d.put("dropped_handover", st.droppedHandover());
        d.put("classic_holds", st.classicHolds());
        d.put("can_drain", st.canDrain());
        d.put("lost_in_handover", st.lostInHandover());
        return d;
    }

    private static long safeNow(com.sitemonitor.service.checks.DbClock clock, long fallback) {
        try {
            return clock.nowMs();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ── /R3-3 D1 ──────────────────────────────────────────────────────────────────────────────────────────────

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────────────────────

    static String iso(Long ms) {
        return ms == null || ms <= 0 ? null : ISO.format(Instant.ofEpochMilli(ms));
    }

    private static Object pick(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.containsKey(k)) return m.get(k);
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long num(Object o) {
        return o instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    private static Double dbl(Object o) {
        return o instanceof Number n ? Double.valueOf(n.doubleValue()) : null;
    }
}
