package com.sitemonitor.controller;

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
        Map<String, Object> out = new LinkedHashMap<>();
        if (sink == null) {
            out.put("this_pod", null);
        } else {
            try {
                EvalStateSink.Status s = sink.status();
                out.put("this_pod", s == null ? null : recorder(s));
            } catch (RuntimeException e) {
                out.put("this_pod", null);
                out.put("this_pod_error", e.getClass().getSimpleName());
            }
        }
        if (store == null) {
            out.put("verify", null);
            out.put("verify_error", "unavailable");
            return out;
        }
        Optional<ClusterHealthStore.Observation> row;
        try {
            row = store.read(ClusterHealthStore.OBS_EVAL_STATE_VERIFY);
        } catch (RuntimeException e) {
            out.put("verify", null);
            out.put("verify_error", e.getClass().getSimpleName());
            return out;
        }
        if (row == null || row.isEmpty()) {
            out.put("verify", null);
            return out;
        }
        Map<String, Object> v = verify(row.get(), nowMs, verifyMs);
        if (v == null) {
            out.put("verify", null);
            out.put("verify_error", "unreadable_payload");
        } else {
            out.put("verify", v);
        }
        return out;
    }

    /** Bu pod'un kaydedici durumu → yanıt haritası. */
    static Map<String, Object> recorder(EvalStateSink.Status s) {
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
