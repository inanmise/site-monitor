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
    @DisplayName("R3-1 entegrasyon: çit bayrağı yalnız bu pod LİDERKEN 'fenced' — liderliği kaybeden pod'un kalan bayrağı uyarı "
            + "yapmaz; ipucu bilinmiyorsa (zamanlayıcı yok) bayrak olduğu gibi; kanca hataları sayısı geçer")
    @SuppressWarnings("unchecked")
    void fencedOnlyWhileLeader() {
        EvalStateSink sink = mock(EvalStateSink.class);
        when(sink.status()).thenReturn(new EvalStateSink.Status("verify", false, "sitemonitor-a-0000000a", null, 0, 0, 40, 0,
                NOW - 600_000, null, null, false, null, null, true, 7L));
        Map<String, Object> leader = (Map<String, Object>) ClusterRuntimeHealth.evalState(sink, null, NOW, 300_000, true).get("this_pod");
        assertThat(leader).containsEntry("fenced", true).containsEntry("hook_failures", 7L);
        Map<String, Object> follower = (Map<String, Object>) ClusterRuntimeHealth.evalState(sink, null, NOW, 300_000, false).get("this_pod");
        assertThat(follower).containsEntry("fenced", false).containsEntry("hook_failures", 7L);
        Map<String, Object> unknown = (Map<String, Object>) ClusterRuntimeHealth.evalState(sink, null, NOW, 300_000).get("this_pod");
        assertThat(unknown).containsEntry("fenced", true);
        // Çitlenmemiş pod liderken de false
        when(sink.status()).thenReturn(new EvalStateSink.Status("verify", true, "sitemonitor-a-0000000a", 4L, 0, 0, 40, 0,
                NOW - 1_000, null, null, false, null, null));
        Map<String, Object> ok = (Map<String, Object>) ClusterRuntimeHealth.evalState(sink, null, NOW, 300_000, true).get("this_pod");
        assertThat(ok).containsEntry("fenced", false).containsEntry("hook_failures", 0L);
    }

    // ── Çok pod Sürüm 3-2 (2026-10-28, paket C; tasarım R3-2 §8.3): devirde yükleme alanları — hepsi EKLENTİ ────────────

    /** 3-1'in {@code this_pod} alan adları (sırasıyla) — 3-2 yalnız SONA ekler. */
    static final List<String> THIS_POD_31 = List.of("mode", "active", "writer", "writer_pod", "generation", "pending", "dropped",
            "flushes", "write_failures", "last_flush_at", "last_failure_at", "last_failure", "table_missing", "fenced",
            "hook_failures", "failing", "inherited", "last_verify");

    static com.sitemonitor.service.evalstate.EvalLoadReport fullLoad() {
        com.sitemonitor.service.evalstate.EvalLoad load = new com.sitemonitor.service.evalstate.EvalLoad(7L,
                com.sitemonitor.service.evalstate.EvalLoad.TIER_FULL,
                Map.of("PORT_DOWN:a.example.com", new com.sitemonitor.service.evalstate.EvalLoad.Recovery(2, NOW - 10_000)),
                Map.of(), java.util.Set.of("PORT_DOWN"), java.util.Set.of("PORT_DOWN", "HTTP_DOWN"),
                Map.of("PORT_DOWN", Map.of("a.example.com", new com.sitemonitor.service.evalstate.EvalLoad.SeedMark(false, NOW - 5_000))),
                Map.of("PORT_DOWN", 60_000L),
                new com.sitemonitor.service.evalstate.EvalLoad.Meta(11L, POD_B + "-0000000b", 12_000L, 3_000L, null, 85L));
        return com.sitemonitor.service.evalstate.EvalLoadReport.applied(NOW - 30_000, "sitemonitor-a-0000000a", load)
                .withWaits(140L, 2);
    }

    static ClusterHealthStore storeWithLoad(String verifyPayload, String loadPayload) {
        ClusterHealthStore store = mock(ClusterHealthStore.class);
        when(store.read(ClusterHealthStore.OBS_EVAL_STATE_VERIFY)).thenReturn(verifyPayload == null ? Optional.empty()
                : Optional.of(new ClusterHealthStore.Observation(POD_B + "-0000000b", NOW - 60_000, verifyPayload, "x")));
        when(store.read(com.sitemonitor.service.evalstate.EvalLoadReport.OBSERVATION)).thenReturn(loadPayload == null ? Optional.empty()
                : Optional.of(new ClusterHealthStore.Observation("sitemonitor-a-0000000a", NOW - 30_000, loadPayload, "x")));
        return store;
    }

    @Test
    @DisplayName("3-2 J-1: ayar kapalı → yalnız EKLENTİ alanlar (read_enabled=false, load, this_pod.handover_ready=false / last_load / "
            + "last_handover); 3-1 alanları ve sırası aynı; küme deposuna yalnız iki PK okuması, yazma yok")
    @SuppressWarnings("unchecked")
    void readOff_additiveFieldsOnly() {
        EvalStateSink sink = mock(EvalStateSink.class);
        when(sink.status()).thenReturn(new EvalStateSink.Status("verify", true, "sitemonitor-a-0000000a", 5L, 4, 1, 300, 2,
                NOW - 1_000, NOW - 5_000, "X", false, null, null, false, 0L));
        ClusterHealthStore store = storeWithLoad(snakePayload("ok", 0), null);
        Map<String, Object> es = ClusterRuntimeHealth.evalState(sink, store, NOW, 300_000, true, false);
        assertThat(es.keySet()).containsExactly("this_pod", "verify", "read_enabled", "load");
        assertThat(es).containsEntry("read_enabled", false).containsEntry("load", null).doesNotContainKey("load_error");
        Map<String, Object> p = (Map<String, Object>) es.get("this_pod");
        List<String> expected = new java.util.ArrayList<>(THIS_POD_31);
        expected.addAll(List.of("handover_ready", "last_load", "last_handover", "marker_columns_missing", "closing"));
        assertThat(new java.util.ArrayList<>(p.keySet())).isEqualTo(expected);
        assertThat(p).containsEntry("handover_ready", false).containsEntry("last_load", null).containsEntry("last_handover", null)
                .containsEntry("marker_columns_missing", null).containsEntry("closing", false);
        // 3-1 alanları 3-1 çağrı biçimiyle aynı değerler
        Map<String, Object> old = ClusterRuntimeHealth.evalState(sink, store, NOW, 300_000, true);
        for (String k : THIS_POD_31) assertThat(((Map<String, Object>) old.get("this_pod")).get(k)).as(k).isEqualTo(p.get(k));
        assertThat(old.get("verify")).isEqualTo(es.get("verify"));
        // okunacak değer yoksa kaydedici durumundan (16 bileşen = false)
        assertThat(old).containsEntry("read_enabled", false);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.times(2)).read(ClusterHealthStore.OBS_EVAL_STATE_VERIFY);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.times(2)).read(com.sitemonitor.service.evalstate.EvalLoadReport.OBSERVATION);
        org.mockito.Mockito.verifyNoMoreInteractions(store);
    }

    @Test
    @DisplayName("3-2: küme yükleme gözlemi — sonuç, sayılar, beklemeler, gözlemleyen pod, kaynak pod adı; bu pod'un last_load aynı alan adları")
    @SuppressWarnings("unchecked")
    void loadObservation() {
        com.sitemonitor.service.evalstate.EvalLoadReport r = fullLoad();
        EvalStateSink sink = mock(EvalStateSink.class);
        when(sink.status()).thenReturn(new EvalStateSink.Status("verify", true, "sitemonitor-a-0000000a", 12L, 0, 0, 3, 0,
                NOW - 1_000, null, null, false, null, null, false, 0L, true, true, r,
                com.sitemonitor.service.evalstate.HandoverResult.NOT_QUIESCENT));
        Map<String, Object> es = ClusterRuntimeHealth.evalState(sink, storeWithLoad(null, r.payload()), NOW, 300_000, true, true);
        assertThat(es).containsEntry("read_enabled", true).doesNotContainKey("load_error");
        Map<String, Object> l = (Map<String, Object>) es.get("load");
        assertThat(l).containsEntry("outcome", "full").containsEntry("reason", null).containsEntry("attempted_tier", "full")
                .containsEntry("from_generation", 11L).containsEntry("from_pod", POD_B + "-0000000b")
                .containsEntry("from_pod_name", POD_B).containsEntry("pod_name", "sitemonitor-a")
                .containsEntry("recovery_counters", 1).containsEntry("suppressions", 1).containsEntry("round_seen", 2)
                .containsEntry("window_types", 1).containsEntry("window_domains", 1).containsEntry("max_wait_ms", 140L)
                .containsEntry("waiters", 2).containsEntry("handover_age_ms", 12_000L)
                .containsEntry("observed_by", "sitemonitor-a-0000000a").containsEntry("observed_by_pod", "sitemonitor-a")
                .containsEntry("age_seconds", 30L);
        assertThat((String) l.get("observed_at")).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d");
        assertThat(l.get("at")).isEqualTo(ClusterRuntimeHealth.iso(NOW - 30_000));
        Map<String, Object> p = (Map<String, Object>) es.get("this_pod");
        assertThat(p).containsEntry("handover_ready", true).containsEntry("last_handover", "not_quiescent");
        Map<String, Object> last = (Map<String, Object>) p.get("last_load");
        assertThat(last.keySet()).as("bu pod'un son yüklemesi = küme gözlemiyle aynı alan adları (gözlem alanları hariç)")
                .containsExactlyElementsOf(l.keySet().stream()
                        .filter(k -> !List.of("observed_by", "observed_by_pod", "observed_at", "age_seconds").contains(k)).toList());
        assertThat(last.keySet()).containsAll(com.sitemonitor.service.evalstate.EvalLoadReport.FIELDS);
        assertThat(last).containsEntry("outcome", "full");
        // camelCase / ek alanlı yük de okunur; bilinmeyen alanlar yok sayılır
        String extra = r.payload().replace("{\"at_ms\"", "{\"future_field\":1,\"at_ms\"");
        assertThat((Map<String, Object>) ClusterRuntimeHealth.evalState(null, storeWithLoad(null, extra), NOW, 300_000, null, true)
                .get("load")).containsEntry("outcome", "full");
    }

    @Test
    @DisplayName("3-2: yükleme gözlemi okunamadı / bozuk / sonuçsuz / depo yok → load_error (bilinmiyor, asla 'yüklenmedi'); "
            + "doğrulama okuması düşse de yükleme okunur")
    void loadError() {
        ClusterHealthStore broken = mock(ClusterHealthStore.class);
        when(broken.read(ClusterHealthStore.OBS_EVAL_STATE_VERIFY)).thenReturn(Optional.empty());
        when(broken.read(com.sitemonitor.service.evalstate.EvalLoadReport.OBSERVATION)).thenThrow(
                new org.springframework.jdbc.BadSqlGrammarException("x", "y", new java.sql.SQLException("no table")));
        assertThat(ClusterRuntimeHealth.evalState(null, broken, NOW, 300_000, null, true)).containsEntry("load", null)
                .containsEntry("load_error", "BadSqlGrammarException").containsEntry("verify", null);

        assertThat(ClusterRuntimeHealth.evalState(null, storeWithLoad(null, "{\"outcome\":"), NOW, 300_000, null, true))
                .containsEntry("load", null).containsEntry("load_error", "unreadable_payload");
        assertThat(ClusterRuntimeHealth.evalState(null, storeWithLoad(null, "{\"at_ms\":1}"), NOW, 300_000, null, true))
                .as("sonuç alanı yok").containsEntry("load_error", "unreadable_payload");
        assertThat(ClusterRuntimeHealth.evalState(null, storeWithLoad(null, "[1]"), NOW, 300_000, null, true))
                .containsEntry("load_error", "unreadable_payload");
        assertThat(ClusterRuntimeHealth.evalState(null, null, NOW, 300_000, null, false))
                .containsEntry("load", null).containsEntry("load_error", "unavailable").containsEntry("read_enabled", false);
        // gözlem yok → hata değil
        assertThat(ClusterRuntimeHealth.evalState(null, storeWithLoad(null, null), NOW, 300_000, null, true))
                .containsEntry("load", null).doesNotContainKey("load_error");
        // doğrulama okuması fırlasa da yükleme okunur
        ClusterHealthStore verifyBroken = storeWithLoad(null, fullLoad().payload());
        when(verifyBroken.read(ClusterHealthStore.OBS_EVAL_STATE_VERIFY)).thenThrow(new IllegalStateException("db"));
        Map<String, Object> es = ClusterRuntimeHealth.evalState(null, verifyBroken, NOW, 300_000, null, true);
        assertThat(es).containsEntry("verify_error", "IllegalStateException");
        assertThat(es.get("load")).isNotNull();
    }

    @Test
    @DisplayName("3-2 inceleme L-3: devir hazırlığının gerçek nedeni — işaret sütunları eksik (kaydedici), kaydedici kapanıyor; "
            + "kaydedici değilse sütun bilgisi bilinmiyor (null); okunamazsa null")
    @SuppressWarnings("unchecked")
    void readinessDetail_columnsMissingAndClosing() {
        EvalStateSink.Status st = new EvalStateSink.Status("verify", true, "sitemonitor-a-0000000a", 5L, 0, 0, 3, 0,
                NOW - 1_000, null, null, false, null, null, false, 0L, true, false, null, null);
        com.sitemonitor.service.evalstate.EvalStateRecorder rec = mock(com.sitemonitor.service.evalstate.EvalStateRecorder.class);
        when(rec.status()).thenReturn(st);
        when(rec.markerColumnsMissingNow()).thenReturn(true);
        when(rec.loadBlocker()).thenReturn(null);
        Map<String, Object> p = (Map<String, Object>) ClusterRuntimeHealth.evalState(rec, null, NOW, 300_000, true, true)
                .get("this_pod");
        assertThat(p).containsEntry("handover_ready", false).containsEntry("marker_columns_missing", true)
                .containsEntry("closing", false);

        when(rec.markerColumnsMissingNow()).thenReturn(false);
        when(rec.loadBlocker()).thenReturn(com.sitemonitor.service.evalstate.EvalLoadReport.REASON_RECORDER_CLOSING);
        p = (Map<String, Object>) ClusterRuntimeHealth.evalState(rec, null, NOW, 300_000, true, true).get("this_pod");
        assertThat(p).containsEntry("marker_columns_missing", false).containsEntry("closing", true);

        when(rec.loadBlocker()).thenReturn(com.sitemonitor.service.evalstate.EvalLoadReport.REASON_MODE_OFF);
        when(rec.markerColumnsMissingNow()).thenThrow(new IllegalStateException("x"));
        p = (Map<String, Object>) ClusterRuntimeHealth.evalState(rec, null, NOW, 300_000, true, true).get("this_pod");
        assertThat(p).containsEntry("marker_columns_missing", null).containsEntry("closing", false);

        EvalStateSink other = mock(EvalStateSink.class);
        when(other.status()).thenReturn(st);
        when(other.loadBlocker()).thenThrow(new IllegalStateException("y"));
        p = (Map<String, Object>) ClusterRuntimeHealth.evalState(other, null, NOW, 300_000, true, true).get("this_pod");
        assertThat(p).as("kaydedici değil → sütun bilgisi yok; kapanma okunamadı → null")
                .containsEntry("marker_columns_missing", null).containsEntry("closing", null);
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
