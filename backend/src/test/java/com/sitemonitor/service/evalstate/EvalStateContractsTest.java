package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.MonitoringOutageService.ConfirmState;
import com.sitemonitor.service.MonitoringOutageService.SweepItem;
import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.schedule.SideJob;
import com.sitemonitor.service.schedule.SideJobRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Çok pod Sürüm 3-1 P0 ortak sözleşmeleri — paketler (A1 kaydedici, A2 MOS + doğrulayıcı, B gözlemlenebilirlik, C yan
 * işler) bu türlere paralel yazar; kalıcı adlar ve anahtar biçimleri burada sabitlenir.
 */
class EvalStateContractsTest {

    @Test
    @DisplayName("kip: kalıcı değerler off/write/verify, varsayılan verify; boş / tanınmayan / büyük harf güvenle çözülür")
    void modeParse() {
        assertThat(EvalStateMode.SETTING_KEY).isEqualTo("site.monitor.eval-state.mode");
        assertThat(EvalStateMode.DEFAULT).isEqualTo(EvalStateMode.VERIFY);
        assertThat(EvalStateMode.parse("off")).isEqualTo(EvalStateMode.OFF);
        assertThat(EvalStateMode.parse(" WRITE ")).isEqualTo(EvalStateMode.WRITE);
        assertThat(EvalStateMode.parse("verify")).isEqualTo(EvalStateMode.VERIFY);
        assertThat(EvalStateMode.parse(null)).isEqualTo(EvalStateMode.VERIFY);
        assertThat(EvalStateMode.parse("")).isEqualTo(EvalStateMode.VERIFY);
        assertThat(EvalStateMode.parse("kapali")).isEqualTo(EvalStateMode.VERIFY);
        assertThat(EvalStateMode.OFF.writes()).isFalse();
        assertThat(EvalStateMode.WRITE.writes()).isTrue();
        assertThat(EvalStateMode.WRITE.verifies()).isFalse();
        assertThat(EvalStateMode.VERIFY.verifies()).isTrue();
    }

    @Test
    @DisplayName("anahtarlar: tablo adları kalıcı; kurtarma = 'tür:alan', kalem = m:<id> ya da d:<alan>|<detay>, özet SHA-256")
    void keys() {
        assertThat(EvalStateKeys.GENERATION_ROW).isEqualTo("*");
        assertThat(List.of(EvalStateKeys.Table.values()).stream().map(EvalStateKeys.Table::tableName))
                .containsExactly("monitor_eval_state", "monitor_last_status", "monitor_eval_type_state",
                        "monitor_confirmations");
        assertThat(EvalStateKeys.recoveryKey("PORT_DOWN", "a.com")).isEqualTo("PORT_DOWN:a.com");

        SweepItem withId = new SweepItem("PORT_DOWN", "a.com", "443", false, "timeout",
                Map.of("monitor_id", 42), null);
        SweepItem noId = new SweepItem("ACCESSIBILITY", "host.example", "443", true, null, Map.of(), null);
        SweepItem nulls = new SweepItem("ACCESSIBILITY", null, null, true, null, null, null);
        assertThat(EvalStateKeys.itemKey(withId)).isEqualTo("m:42");
        assertThat(EvalStateKeys.itemKey(noId)).isEqualTo("d:host.example|443");
        assertThat(EvalStateKeys.itemKey(nulls)).isEqualTo("d:|");
        assertThat(EvalStateKeys.lastStatusKey("PORT_DOWN", "m:42")).isEqualTo("PORT_DOWN|m:42");

        String h = EvalStateKeys.keyHash("PORT_DOWN:a.com:443");
        assertThat(h).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(EvalStateKeys.keyHash("PORT_DOWN:a.com:443"));
        assertThat(h).isNotEqualTo(EvalStateKeys.keyHash("PORT_DOWN:a.com:444"));
        assertThat(EvalStateKeys.shortHash("PORT_DOWN:a.com:443")).isEqualTo(h.substring(0, 12));
    }

    @Test
    @DisplayName("NOOP: her çağrı sessiz, durum null (kaydedici yok = 'Bilinmiyor')")
    void noopSink() {
        EvalStateSink s = EvalStateSink.NOOP;
        SweepItem it = new SweepItem("PORT_DOWN", "a.com", "443", false, "x", Map.of(), null);
        ConfirmState cs = new ConfirmState("PORT_DOWN", "a.com", "443", 0, 3, "2026-10-23T06:00:00", 0L);
        assertThatCode(() -> {
            s.recovery("PORT_DOWN:a.com", "PORT_DOWN", "a.com");
            s.observation("PORT_DOWN", it, false, 1L);
            s.typeState("PORT_DOWN");
            s.chainStart("k", 1L, it, cs, "2026-10-23T06:00:00", 30_000L);
            s.chainProgress("k", 1L, cs, List.of());
            s.chainFinishing("k", 1L, 1);
            s.chainEnd("k", 1L);
            s.sweepEnd("PORT_DOWN");
            s.bindSource(null);
        }).doesNotThrowAnyException();
        assertThat(s.status()).isNull();
        assertThat(EvalStateSource.RecoveryValues.NONE.isNone()).isTrue();
        assertThat(new EvalStateSource.RecoveryValues(1, null, null, null).isNone()).isFalse();
    }

    @Test
    @DisplayName("doğrulama raporu: gözlem adı kalıcı; durum yalnız geçti/kaldı türlerinden; ilk anahtarlar en çok 5; değişmez")
    void verifyReport() {
        assertThat(EvalVerifyReport.OBSERVATION).isEqualTo("eval-state-verify")
                .isEqualTo(ClusterHealthStore.OBS_EVAL_STATE_VERIFY);
        assertThat(ClusterHealthStore.OBS_METRICS_ORG).isEqualTo("metrics-org");
        assertThat(EvalVerifyReport.KINDS).containsExactly("recovery", "sibling", "suppression", "round_seen",
                "confirmation", "descriptor", "window_seed");

        Map<String, EvalVerifyReport.KindResult> kinds = new LinkedHashMap<>();
        kinds.put(EvalVerifyReport.KIND_RECOVERY, new EvalVerifyReport.KindResult(10, 0, 1, null));
        kinds.put(EvalVerifyReport.KIND_WINDOW_SEED, new EvalVerifyReport.KindResult(3, 2, 0, 0.4));
        assertThat(EvalVerifyReport.statusOf(kinds)).as("ölçülen tür durumu bozmaz").isEqualTo(EvalVerifyReport.STATUS_OK);
        kinds.put(EvalVerifyReport.KIND_SIBLING, new EvalVerifyReport.KindResult(5, 1, 0, null));
        assertThat(EvalVerifyReport.statusOf(kinds)).isEqualTo(EvalVerifyReport.STATUS_MISMATCH);

        EvalVerifyReport r = new EvalVerifyReport(1L, "pod-a", 3L, EvalVerifyReport.statusOf(kinds), null, kinds,
                List.of("a", "b", "c", "d", "e", "f", "g"));
        assertThat(r.firstMismatches()).hasSize(EvalVerifyReport.MAX_KEYS);
        assertThat(r.passFailMismatches()).isEqualTo(1);
        assertThat(r.unknown()).isFalse();
        assertThat(List.copyOf(r.kinds().keySet())).containsExactly("recovery", "window_seed", "sibling");
        kinds.clear();
        assertThat(r.kinds()).as("kopya, sonradan değişmez").hasSize(3);

        EvalVerifyReport u = EvalVerifyReport.unknown(2L, "pod-a", 3L, EvalVerifyReport.REASON_FLUSH_LAG);
        assertThat(u.unknown()).isTrue();
        assertThat(u.kinds()).isEmpty();
        assertThat(u.firstMismatches()).isEmpty();
    }

    @Test
    @DisplayName("R3-1 FX-STATE sözleşmeleri: yeni neden kodları kalıcı; Status sona eklenen bileşenler + eski 14'lü kurucu; varsayılanlar")
    void fixStateContracts() {
        assertThat(EvalVerifyReport.REASON_LEADERSHIP_CHANGED).isEqualTo("leadership_changed");
        assertThat(EvalVerifyReport.REASON_NOTHING_COMPARED).isEqualTo("nothing_compared");
        var components = EvalStateSink.Status.class.getRecordComponents();
        assertThat(components).hasSize(16);
        assertThat(components[14].getName()).isEqualTo("fenced");
        assertThat(components[15].getName()).isEqualTo("hookFailures");
        EvalStateSink.Status legacy = new EvalStateSink.Status("write", false, "w", null, 0, 0, 0, 0, null, null, null,
                false, null, null);
        assertThat(legacy.fenced()).isFalse();
        assertThat(legacy.hookFailures()).isZero();

        assertThat(EvalStateMode.unrecognized("kapali")).isTrue();
        assertThat(EvalStateMode.unrecognized(" Verify ")).isFalse();
        assertThat(EvalStateMode.unrecognized(null)).isFalse();
        assertThat(EvalStateMode.unrecognized("  ")).isFalse();

        EvalStateSource bare = new EvalStateSource() {
            @Override public RecoveryValues recoveryValues(String rkey) { return RecoveryValues.NONE; }
            @Override public TypeValues typeValues(String alertType) { return TypeValues.NONE; }
            @Override public String errorClass(String error, String domain) { return null; }
        };
        assertThat(bare.downObservation("PORT_DOWN", 1L)).as("desteklenmiyor → çağıranın değerleri").isNull();
        assertThat(bare.hookFailures()).isZero();
        assertThat(EvalStateSource.DownValues.ABSENT.present()).isFalse();
        assertThatCode(() -> EvalStateSink.NOOP.observationPruned("PORT_DOWN",
                new SweepItem("PORT_DOWN", "a.com", "443", false, "x", Map.of("monitor_id", 1L), null)))
                .doesNotThrowAnyException();
        EvalStateVerification.Context fixed = new EvalStateVerification.Context() {
            @Override public String writer() { return "w"; }
            @Override public long generation() { return 1; }
            @Override public long generationStartMs() { return 0; }
            @Override public long nowMs() { return 0; }
            @Override public long seq() { return 0; }
            @Override public boolean awaitFlushed(long seq, long timeoutMs) { return true; }
            @Override public boolean changedSince(EvalStateKeys.Table table, String key, long seq) { return false; }
            @Override public long lastStatusRefreshMs() { return 0; }
        };
        assertThat(fixed.stillCurrent()).as("sabit test bağlamı").isTrue();
    }

    @Test
    @DisplayName("anlık görüntü: kopya alır, null anahtar/değer atar, değiştirilemez; ince kalem stillMonitored için")
    void snapshotIsImmutableCopy() {
        Map<String, Integer> up = new HashMap<>();
        up.put("PORT_DOWN:a.com", 2);
        up.put("PORT_DOWN:b.com", null);
        EvalSnapshot s = new EvalSnapshot(5L, up, null, null, null,
                Map.of("PORT_DOWN|7", new EvalSnapshot.Down("PORT_DOWN", "a.com", "443", 7L, 3L, true,
                        EvalStateSource.ERROR_NETWORK, 4L)),
                null, Set.of("PORT_DOWN"), null, null,
                Map.of("PORT_DOWN", new EvalSnapshot.Window(1, 4, 300_000L)));
        up.put("PORT_DOWN:c.com", 1);
        assertThat(s.recoveryUpCount()).containsExactly(Map.entry("PORT_DOWN:a.com", 2));
        assertThat(s.inFlight()).isEmpty();
        assertThatThrownBy(() -> s.recoveryUpCount().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(s.windows().get("PORT_DOWN").rate()).isEqualTo(0.25);
        assertThat(EvalSnapshot.EMPTY.downObserved()).isEmpty();

        SweepItem slim = s.downObserved().get("PORT_DOWN|7").slimItem();
        assertThat(slim.up()).isFalse();
        assertThat(slim.recheck()).isNull();
        assertThat(slim.ctxExtra()).containsEntry("monitor_id", 7L).containsEntry("team_id", 3L)
                .containsEntry("standalone", true);
        assertThat(EvalStateKeys.itemKey(slim)).isEqualTo("m:7");
    }

    @Test
    @DisplayName("yan iş: ad ve gövde zorunlu; cron tetikli mi; lider erteleme anahtarı kalıcı; iskelet koşucu durumu boş")
    void sideJobContracts() {
        Runnable body = () -> {};
        assertThat(SideJob.LEADER_YIELD_KEY).isEqualTo("site.monitor.scheduler.leader-yield.enabled");
        assertThat(SideJob.LEADER_YIELD_DEFAULT).isTrue();
        assertThat(SideJob.heavy("cert-stale", body).cronTriggered()).isFalse();
        assertThat(SideJob.heavy("cert-stale", body).heavy()).isTrue();
        SideJob c = SideJob.cron("nightly-cleanup", true, "nightly-cleanup", "0 0 3 * * *", "Europe/Istanbul", null, body);
        assertThat(c.cronTriggered()).isTrue();
        assertThatThrownBy(() -> SideJob.heavy(" ", body)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SideJob.heavy("x", null)).isInstanceOf(IllegalArgumentException.class);

        SideJobRunner.Status st = new SideJobRunner().status();
        assertThat(st.threads()).isZero();
        assertThat(st.otherLivePod()).as("bilinmiyor ≠ yok").isNull();
        assertThat(st.jobs()).isEmpty();
    }
}
