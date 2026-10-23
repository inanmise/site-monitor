package com.sitemonitor.controller;

import com.sitemonitor.service.evalstate.EvalStateSink;
import com.sitemonitor.service.evalstate.EvalVerifyReport;
import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.schedule.SideJobRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sistem Sağlığı Küme kartının çok pod Sürüm 3-1 alanları (2026-10-23, paket B): bu pod'un kaydedici durumu, kümenin son
 * doğrulama gözlemi (yük SNAKE_CASE ya da camelCase, bozuk → "okunamadı"), bayatlık eşiği ve yan işler. Bilinmeyen asla
 * "0 uyuşmazlık" değildir.
 */
class ClusterRuntimeHealthTest {

    static final long NOW = 1_792_800_000_000L;   // 2026-10-23
    static final String POD_B = "sitemonitor-6b7c9d-bbbbb";

    static String snakePayload(String status, int siblingMismatches) {
        return "{\"at_ms\":" + (NOW - 60_000) + ",\"pod\":\"" + POD_B + "-0000000b\",\"generation\":4,\"status\":\"" + status + "\","
                + "\"reason\":null,\"kinds\":{\"window_seed\":{\"compared\":9,\"mismatches\":1,\"skipped\":0,\"max_delta\":0.12},"
                + "\"recovery\":{\"compared\":40,\"mismatches\":0,\"skipped\":1,\"max_delta\":null},"
                + "\"sibling\":{\"compared\":12,\"mismatches\":" + siblingMismatches + ",\"skipped\":0,\"max_delta\":null},"
                + "\"brand_new_kind\":{\"compared\":1,\"mismatches\":0,\"skipped\":0}},"
                + "\"first_mismatches\":[\"sibling:0123456789ab\",\"a\",\"b\",\"c\",\"d\",\"e\"]}";
    }

    static ClusterHealthStore storeWith(String payload, long observedMs) {
        ClusterHealthStore store = mock(ClusterHealthStore.class);
        when(store.read(ClusterHealthStore.OBS_EVAL_STATE_VERIFY))
                .thenReturn(Optional.of(new ClusterHealthStore.Observation(POD_B + "-0000000b", observedMs, payload, "x")));
        return store;
    }

    @Test
    @DisplayName("küme doğrulaması: gözlemleyen pod, tür sırası (bilinen önce, yeni tür sonda), ölçüm türleri işaretli, ilk anahtarlar ≤ 5, "
            + "geçti/kaldı toplamı ölçüm türlerini saymaz")
    @SuppressWarnings("unchecked")
    void verifyObservation() {
        Map<String, Object> es = ClusterRuntimeHealth.evalState(null, storeWith(snakePayload("mismatch", 2), NOW - 90_000), NOW, 300_000);
        assertThat(es).containsEntry("this_pod", null).doesNotContainKey("verify_error");
        Map<String, Object> v = (Map<String, Object>) es.get("verify");
        assertThat(v).containsEntry("observed_by_pod", POD_B).containsEntry("status", "mismatch").containsEntry("generation", 4L)
                .containsEntry("stale", false).containsEntry("age_seconds", 90L).containsEntry("stale_after_seconds", 900L)
                .containsEntry("pass_fail_mismatches", 2);
        Map<String, Map<String, Object>> kinds = (Map<String, Map<String, Object>>) v.get("kinds");
        assertThat(kinds.keySet()).containsExactly("recovery", "sibling", "window_seed", "brand_new_kind");
        assertThat(kinds.get("window_seed")).containsEntry("measured", true).containsEntry("max_delta", 0.12).containsEntry("mismatches", 1L);
        assertThat(kinds.get("recovery")).containsEntry("measured", false).containsEntry("skipped", 1L);
        assertThat((List<String>) v.get("first_mismatches")).hasSize(5).first().isEqualTo("sibling:0123456789ab");
        assertThat((String) v.get("observed_at")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d");
    }

    @Test
    @DisplayName("bayat gözlem (3 × doğrulama aralığından eski) işaretlenir; bilinmeyen tur toplamı null (0 değil); camelCase yük de okunur")
    @SuppressWarnings("unchecked")
    void staleUnknownAndCamelCase() {
        Map<String, Object> v = (Map<String, Object>) ClusterRuntimeHealth.evalState(null,
                storeWith(snakePayload("ok", 0), NOW - 901_000), NOW, 300_000).get("verify");
        assertThat(v).containsEntry("stale", true).containsEntry("pass_fail_mismatches", 0);
        String unknown = "{\"atMs\":" + NOW + ",\"pod\":\"p\",\"generation\":0,\"status\":\"unknown\",\"reason\":\"flush_lag\","
                + "\"kinds\":{},\"firstMismatches\":[]}";
        Map<String, Object> u = (Map<String, Object>) ClusterRuntimeHealth.evalState(null, storeWith(unknown, NOW), NOW, 300_000).get("verify");
        assertThat(u).containsEntry("status", "unknown").containsEntry("reason", "flush_lag").containsEntry("pass_fail_mismatches", null);
        assertThat(u.get("at")).isNotNull();
    }

    @Test
    @DisplayName("gözlem yok → verify null (hata değil); okunamadı / bozuk yük / küme deposu yok → verify_error (bilinmiyor, asla 0)")
    void verifyMissingOrUnreadable() {
        ClusterHealthStore none = mock(ClusterHealthStore.class);
        when(none.read(ClusterHealthStore.OBS_EVAL_STATE_VERIFY)).thenReturn(Optional.empty());
        assertThat(ClusterRuntimeHealth.evalState(null, none, NOW, 300_000)).containsEntry("verify", null).doesNotContainKey("verify_error");

        ClusterHealthStore broken = mock(ClusterHealthStore.class);
        when(broken.read(ClusterHealthStore.OBS_EVAL_STATE_VERIFY)).thenThrow(new org.springframework.jdbc.BadSqlGrammarException("x", "y",
                new java.sql.SQLException("no table")));
        assertThat(ClusterRuntimeHealth.evalState(null, broken, NOW, 300_000)).containsEntry("verify", null)
                .containsEntry("verify_error", "BadSqlGrammarException");

        assertThat(ClusterRuntimeHealth.evalState(null, storeWith("{\"status\":", NOW), NOW, 300_000))
                .containsEntry("verify_error", "unreadable_payload");
        assertThat(ClusterRuntimeHealth.evalState(null, storeWith("{\"kinds\":{}}", NOW), NOW, 300_000))
                .as("durum alanı yok").containsEntry("verify_error", "unreadable_payload");
        assertThat(ClusterRuntimeHealth.evalState(null, storeWith("[1,2]", NOW), NOW, 300_000))
                .containsEntry("verify_error", "unreadable_payload");
        assertThat(ClusterRuntimeHealth.evalState(null, null, NOW, 300_000)).containsEntry("verify_error", "unavailable");
    }

    @Test
    @DisplayName("bu pod'un kaydedicisi: alanlar, pod adı, şu an yazamıyor mu (son hata son boşaltmadan yeni), devralınan, son tur; "
            + "NOOP (durum null) → this_pod null; durum okunamazsa this_pod_error")
    @SuppressWarnings("unchecked")
    void recorderStatus() {
        EvalStateSink sink = mock(EvalStateSink.class);
        EvalVerifyReport report = new EvalVerifyReport(NOW - 30_000, "sitemonitor-a-0000000a", 5, "ok", null,
                Map.of("recovery", new EvalVerifyReport.KindResult(3, 0, 0, null)), List.of());
        when(sink.status()).thenReturn(new EvalStateSink.Status("verify", true, "sitemonitor-a-0000000a", 5L, 4, 1, 300, 2,
                NOW - 1_000, NOW - 5_000, "CannotGetJdbcConnectionException", false,
                new EvalStateSink.Inherited(NOW - 600_000, 4L, 3, 1, 0, 2), report));
        Map<String, Object> p = (Map<String, Object>) ClusterRuntimeHealth.evalState(sink, null, NOW, 300_000).get("this_pod");
        assertThat(p).containsEntry("mode", "verify").containsEntry("active", true).containsEntry("writer_pod", "sitemonitor-a")
                .containsEntry("generation", 5L).containsEntry("pending", 4L).containsEntry("write_failures", 2L)
                .containsEntry("failing", false).containsEntry("table_missing", false);
        assertThat((Map<String, Object>) p.get("inherited")).containsEntry("recovery_counters", 3).containsEntry("abandoned_chains", 2)
                .containsEntry("previous_generation", 4L);
        assertThat((Map<String, Object>) p.get("last_verify")).containsEntry("status", "ok").containsEntry("pass_fail_mismatches", 0);

        when(sink.status()).thenReturn(new EvalStateSink.Status("write", true, "w", 5L, 0, 0, 0, 1,
                null, NOW - 5_000, "X", false, null, EvalVerifyReport.unknown(NOW, "w", 5, "flush_lag")));
        p = (Map<String, Object>) ClusterRuntimeHealth.evalState(sink, null, NOW, 300_000).get("this_pod");
        assertThat(p).containsEntry("failing", true).containsEntry("last_flush_at", null).containsEntry("inherited", null);
        assertThat((Map<String, Object>) p.get("last_verify")).containsEntry("status", "unknown").containsEntry("pass_fail_mismatches", null);

        when(sink.status()).thenReturn(null);
        assertThat(ClusterRuntimeHealth.evalState(sink, null, NOW, 300_000)).containsEntry("this_pod", null).doesNotContainKey("this_pod_error");
        when(sink.status()).thenThrow(new IllegalStateException("x"));
        assertThat(ClusterRuntimeHealth.evalState(sink, null, NOW, 300_000)).containsEntry("this_pod", null)
                .containsEntry("this_pod_error", "IllegalStateException");
    }

    @Test
    @DisplayName("R3-1 inceleme M2: YAZMAYAN kaydedicinin (eski lider / çitlenmiş) eski hatası 'şu an başarısız' sayılmaz — yalnız yazan "
            + "kaydedicinin son hatası son boşaltmadan yeniyse failing")
    @SuppressWarnings("unchecked")
    void failingOnlyWhileActive() {
        EvalStateSink sink = mock(EvalStateSink.class);
        // Eski lider: DB kesintisinde son yazımı başarısız oldu, liderliği kaybetti → artık yazmıyor, yeniden denemiyor
        when(sink.status()).thenReturn(new EvalStateSink.Status("verify", false, "sitemonitor-a-0000000a", null, 0, 0, 40, 3,
                NOW - 600_000, NOW - 300_000, "CannotGetJdbcConnectionException", false, null, null));
        Map<String, Object> p = (Map<String, Object>) ClusterRuntimeHealth.evalState(sink, null, NOW, 300_000).get("this_pod");
        assertThat(p).containsEntry("active", false).containsEntry("failing", false)
                .containsEntry("last_failure", "CannotGetJdbcConnectionException").containsEntry("write_failures", 3L);
        // Aynı geçmiş, yazan lider → failing
        when(sink.status()).thenReturn(new EvalStateSink.Status("verify", true, "sitemonitor-a-0000000a", 4L, 0, 0, 40, 3,
                NOW - 600_000, NOW - 300_000, "CannotGetJdbcConnectionException", false, null, null));
        p = (Map<String, Object>) ClusterRuntimeHealth.evalState(sink, null, NOW, 300_000).get("this_pod");
        assertThat(p).containsEntry("failing", true);
    }

    @Test
    @DisplayName("R3-1 inceleme L4: koşucunun 'başka canlı pod' gözlemi yalnız lider kararından sonra ~1 dk dolu; son dolu değer ve zamanı "
            + "bellekte tutulur (other_live_pod_seen) — ekran sürekli 'bilinmiyor' demez; hiç görülmediyse alan yok")
    @SuppressWarnings("unchecked")
    void sideJobsRemembersLastSeenOtherPod() {
        SideJobRunner runner = mock(SideJobRunner.class);
        ClusterRuntimeHealth.OtherPodSeen seen = new ClusterRuntimeHealth.OtherPodSeen();
        when(runner.status()).thenReturn(new SideJobRunner.Status(3, true, true, true, null, List.of()));
        assertThat(ClusterRuntimeHealth.sideJobs(runner, seen, NOW)).containsEntry("other_live_pod", null)
                .doesNotContainKey("other_live_pod_seen");

        when(runner.status()).thenReturn(new SideJobRunner.Status(3, true, true, true, true, List.of()));
        assertThat((Map<String, Object>) ClusterRuntimeHealth.sideJobs(runner, seen, NOW - 120_000).get("other_live_pod_seen"))
                .containsEntry("value", true).containsEntry("at", ClusterRuntimeHealth.iso(NOW - 120_000));

        // Önbellek boşaldı (karar 1 dk'dan eski): son görülen kalır
        when(runner.status()).thenReturn(new SideJobRunner.Status(3, true, true, true, null, List.of()));
        Map<String, Object> m = ClusterRuntimeHealth.sideJobs(runner, seen, NOW);
        assertThat(m).containsEntry("other_live_pod", null);
        assertThat((Map<String, Object>) m.get("other_live_pod_seen")).containsEntry("value", true)
                .containsEntry("at", ClusterRuntimeHealth.iso(NOW - 120_000));

        // Yeni gözlem eskisinin yerine geçer
        when(runner.status()).thenReturn(new SideJobRunner.Status(3, true, true, true, false, List.of()));
        assertThat((Map<String, Object>) ClusterRuntimeHealth.sideJobs(runner, seen, NOW).get("other_live_pod_seen"))
                .containsEntry("value", false).containsEntry("at", ClusterRuntimeHealth.iso(NOW));
        // Bellek parametresiz eski çağrı biçimi alanı eklemez
        assertThat(ClusterRuntimeHealth.sideJobs(runner)).doesNotContainKey("other_live_pod_seen");
    }

    @Test
    @DisplayName("yan işler: iş satırları + özet sayaçlar (koşan, kuyrukta, son koşusu hatalı, ertelenen, bırakılan); başka canlı pod "
            + "bilinmiyorsa null; durum okunamazsa error")
    @SuppressWarnings("unchecked")
    void sideJobs() {
        SideJobRunner runner = mock(SideJobRunner.class);
        when(runner.status()).thenReturn(new SideJobRunner.Status(3, true, true, true, null, List.of(
                new SideJobRunner.Status.Job("cert-hourly", SideJobRunner.STATE_RUNNING, NOW - 20_000, NOW - 3_600_000, SideJobRunner.OUTCOME_OK, 5, 4, 0, 1),
                new SideJobRunner.Status.Job("tls-profile", SideJobRunner.STATE_IDLE, null, NOW - 60_000, SideJobRunner.OUTCOME_FAILED, 2, 0, 3, 0),
                new SideJobRunner.Status.Job("nightly-cleanup", SideJobRunner.STATE_QUEUED, null, null, null, 0, 1, 0, 0))));
        Map<String, Object> m = ClusterRuntimeHealth.sideJobs(runner);
        assertThat(m).containsEntry("threads", 3).containsEntry("other_live_pod", null).containsEntry("running", 1)
                .containsEntry("queued", 1).containsEntry("failed", 1).containsEntry("deferred_total", 5L).containsEntry("yielded_total", 3L)
                .containsEntry("skipped_total", 1L).containsEntry("leader_yield_enabled", true);
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) m.get("jobs");
        assertThat(jobs).extracting(j -> j.get("name")).containsExactly("cert-hourly", "tls-profile", "nightly-cleanup");
        assertThat(jobs.get(0).get("started_at")).isNotNull();
        assertThat(jobs.get(2)).containsEntry("started_at", null).containsEntry("last_outcome", null);

        when(runner.status()).thenThrow(new IllegalStateException("x"));
        assertThat(ClusterRuntimeHealth.sideJobs(runner)).containsEntry("error", "IllegalStateException");
        SideJobRunner nul = mock(SideJobRunner.class);
        assertThat(ClusterRuntimeHealth.sideJobs(nul)).containsEntry("error", "unavailable");
        when(nul.status()).thenReturn(new SideJobRunner.Status(0, false, false, false, false, null));
        assertThat(ClusterRuntimeHealth.sideJobs(nul)).containsEntry("threads", 0).containsEntry("other_live_pod", false)
                .containsEntry("jobs", List.of()).containsEntry("running", 0);
    }
}
