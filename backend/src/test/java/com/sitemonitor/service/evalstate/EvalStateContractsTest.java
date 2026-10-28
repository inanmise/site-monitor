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
 * işler) bu türlere paralel yazar; kalıcı adlar ve anahtar biçimleri burada sabitlenir. Sürüm 3-2 P0 (2026-10-28):
 * devirde okuma sözleşmeleri ({@link EvalLoad}, {@link EvalLoadReport}, {@link HandoverResult}, yeni varsayılan yöntemler,
 * {@code Status}'un sona eklenen dört bileşeni) — varsayılanlar bugünkü davranıştır (J-1).
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
        // 3-2 dört bileşeni SONA ekledi (20; ayrıntı r32Status) — 3-1'in iki bileşeni yerinde kalır
        assertThat(components).hasSizeGreaterThanOrEqualTo(16);
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

    // ── Çok pod Sürüm 3-2 P0 (2026-10-28, tasarım R3-2 §3.5) ────────────────────────────────────────────────────────

    private static final tools.jackson.databind.ObjectMapper PLAIN = tools.jackson.databind.json.JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return PLAIN.readValue(json, Map.class);
    }

    @Test
    @DisplayName("3-2 Status: 20 bileşen, dördü SONA eklendi; 14 ve 16 bileşenli kurucular çalışır, yeni alanlar false/false/null/null")
    void r32Status() {
        var components = EvalStateSink.Status.class.getRecordComponents();
        assertThat(components).hasSize(20);
        assertThat(List.of(components).stream().map(java.lang.reflect.RecordComponent::getName).toList())
                .containsExactly("mode", "active", "writer", "generation", "pending", "dropped", "flushes",
                        "writeFailures", "lastFlushMs", "lastFailureMs", "lastFailure", "tableMissing", "inherited",
                        "lastVerify", "fenced", "hookFailures", "readEnabled", "handoverReady", "lastLoad", "lastHandover");
        assertThat(components[18].getType()).isEqualTo(EvalLoadReport.class);
        assertThat(components[19].getType()).isEqualTo(HandoverResult.class);

        EvalStateSink.Status s14 = new EvalStateSink.Status("write", false, "w", null, 0, 0, 0, 0, null, null, null,
                false, null, null);
        EvalStateSink.Status s16 = new EvalStateSink.Status("verify", true, "w", 3L, 1, 2, 3, 4, 5L, 6L, "X", true,
                null, null, true, 9L);
        for (EvalStateSink.Status s : List.of(s14, s16)) {
            assertThat(s.readEnabled()).isFalse();
            assertThat(s.handoverReady()).isFalse();
            assertThat(s.lastLoad()).isNull();
            assertThat(s.lastHandover()).isNull();
        }
        assertThat(s14.fenced()).isFalse();
        assertThat(s14.hookFailures()).isZero();
        assertThat(s16.fenced()).isTrue();
        assertThat(s16.hookFailures()).isEqualTo(9L);
        assertThat(s16.tableMissing()).isTrue();

        EvalLoadReport r = EvalLoadReport.none(1L, "w", EvalLoadReport.REASON_SETTING_OFF);
        EvalStateSink.Status s20 = new EvalStateSink.Status("verify", true, "w", 3L, 0, 0, 0, 0, null, null, null,
                false, null, null, false, 0L, true, true, r, HandoverResult.WRITTEN);
        assertThat(s20.readEnabled()).isTrue();
        assertThat(s20.handoverReady()).isTrue();
        assertThat(s20.lastLoad()).isSameAs(r);
        assertThat(s20.lastHandover()).isEqualTo(HandoverResult.WRITTEN);
        assertThat(new EvalStateSink.Status("verify", true, "w", 3L, 1, 2, 3, 4, 5L, 6L, "X", true, null, null, true, 9L,
                false, false, null, null)).as("16'lı kurucu = 20'li kurucu (yeni alanlar boş)").isEqualTo(s16);
    }

    @Test
    @DisplayName("3-2 varsayılanlar = bugünkü davranış: NOOP kaydedici yüklemeye hazır değil (no_recorder), devir hazır değil, "
            + "boşaltma SKIPPED; çıplak kaynak belirteç 0, uygulamaz, rapor null, oynatma 0; devir bütçe sabitleri")
    void r32Defaults() {
        EvalStateSink noop = EvalStateSink.NOOP;
        assertThat(noop.loadBlocker()).isEqualTo(EvalLoadReport.REASON_NO_RECORDER);
        assertThat(noop.handoverReady()).isFalse();
        assertThatCode(noop::openHandover).doesNotThrowAnyException();
        assertThat(noop.flushForHandover(EvalStateSink.HANDOVER_FLUSH_MS, true)).isEqualTo(HandoverResult.SKIPPED);
        assertThat(noop.flushForHandover(0L, false)).isEqualTo(HandoverResult.SKIPPED);

        assertThat(EvalStateSink.HANDOVER_QUIESCE_MS).isEqualTo(300L);
        assertThat(EvalStateSink.HANDOVER_FLUSH_MS).isEqualTo(700L);
        assertThat(EvalStateSink.HANDOVER_BUDGET_MS).isEqualTo(1_000L)
                .isGreaterThanOrEqualTo(EvalStateSink.HANDOVER_QUIESCE_MS + EvalStateSink.HANDOVER_FLUSH_MS);

        EvalStateSource bare = new EvalStateSource() {
            @Override public RecoveryValues recoveryValues(String rkey) { return RecoveryValues.NONE; }
            @Override public TypeValues typeValues(String alertType) { return TypeValues.NONE; }
            @Override public String errorClass(String error, String domain) { return null; }
        };
        assertThat(bare.evalLoadToken()).isZero();
        EvalLoad load = new EvalLoad(1L, EvalLoad.TIER_FULL, null, null, null, null, null, null, null);
        assertThat(bare.applyEvalLoad(load)).isFalse();
        assertThat(bare.applyEvalLoad(null)).isFalse();
        assertThatCode(() -> bare.evalLoadSkipped(1L, EvalLoadReport.none(1L, "w", EvalLoadReport.REASON_READ_FAILED)))
                .doesNotThrowAnyException();
        assertThat(bare.lastEvalLoad()).isNull();
        assertThat(bare.replay()).isZero();
    }

    @Test
    @DisplayName("3-2 rapor sabitleri: gözlem adı kalıcı ve ≤ 60; sonuçlar; 22 neden tasarım sırasıyla, tekil, küçük harf kod; "
            + "beklenen / işletim / yalnız-gözlem kümeleri ayrık ve birleşimi tam liste")
    void r32ReportConstants() {
        assertThat(EvalLoadReport.OBSERVATION).isEqualTo("eval-state-load").hasSizeLessThanOrEqualTo(60)
                .isNotEqualTo(EvalVerifyReport.OBSERVATION).isNotEqualTo(ClusterHealthStore.OBS_METRICS_ORG);
        assertThat(EvalLoadReport.OUTCOMES).containsExactly("full", "observations", "none");
        assertThat(EvalLoad.TIER_FULL).isEqualTo(EvalLoadReport.OUTCOME_FULL);
        assertThat(EvalLoad.TIER_OBSERVATIONS).isEqualTo(EvalLoadReport.OUTCOME_OBSERVATIONS);

        assertThat(EvalLoadReport.REASONS).containsExactly("setting_off", "mode_off", "no_recorder", "recorder_closing",
                "tables_missing", "columns_missing", "no_previous_generation", "writer_is_self", "previous_unmarked",
                "previous_incomplete", "clock_skew", "previous_stale", "no_handover", "handover_too_old",
                "intermediate_leader", "read_failed", "too_large", "timeout", "evaluation_busy", "evaluated_before_load",
                "leadership_changed", "setting_turned_off");
        assertThat(EvalLoadReport.REASONS).doesNotHaveDuplicates()
                .allSatisfy(r -> assertThat(r).matches("[a-z_]+").hasSizeLessThanOrEqualTo(EvalLoadReport.REASON_MAX));

        // Her REASON_* sabiti listede, listedeki her kod bir sabit (sabit eklenip listeye unutulmasın)
        Set<String> constants = new java.util.TreeSet<>();
        for (java.lang.reflect.Field f : EvalLoadReport.class.getDeclaredFields()) {
            if (f.getName().startsWith("REASON_") && f.getType() == String.class
                    && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                try {
                    constants.add((String) f.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        assertThat(constants).containsExactlyInAnyOrderElementsOf(EvalLoadReport.REASONS);

        Set<String> union = new java.util.HashSet<>();
        union.addAll(EvalLoadReport.EXPECTED_REASONS);
        union.addAll(EvalLoadReport.OPERATIONAL_REASONS);
        union.addAll(List.of(EvalLoadReport.REASON_NO_HANDOVER, EvalLoadReport.REASON_HANDOVER_TOO_OLD,
                EvalLoadReport.REASON_INTERMEDIATE_LEADER));
        assertThat(union).containsExactlyInAnyOrderElementsOf(EvalLoadReport.REASONS);
        assertThat(EvalLoadReport.EXPECTED_REASONS).doesNotContainAnyElementsOf(EvalLoadReport.OPERATIONAL_REASONS)
                .doesNotContain(EvalLoadReport.REASON_NO_HANDOVER, EvalLoadReport.REASON_HANDOVER_TOO_OLD,
                        EvalLoadReport.REASON_INTERMEDIATE_LEADER);
        assertThat(EvalLoadReport.OPERATIONAL_REASONS).doesNotContain(EvalLoadReport.REASON_NO_HANDOVER,
                EvalLoadReport.REASON_HANDOVER_TOO_OLD, EvalLoadReport.REASON_INTERMEDIATE_LEADER);
        assertThat(EvalLoadReport.OBSERVATIONS_REASONS).containsExactly("no_handover", "handover_too_old",
                "intermediate_leader", "clock_skew");
        assertThat(EvalLoadReport.REASONS).containsAll(EvalLoadReport.OBSERVATIONS_REASONS);

        assertThat(EvalLoadReport.operational(EvalLoadReport.REASON_READ_FAILED)).isTrue();
        assertThat(EvalLoadReport.operational(EvalLoadReport.REASON_SETTING_OFF)).isFalse();
        assertThat(EvalLoadReport.operational(EvalLoadReport.REASON_NO_HANDOVER)).isFalse();
        assertThat(EvalLoadReport.operational(null)).isFalse();
        assertThat(EvalLoadReport.operational("bilinmeyen")).isFalse();
    }

    @Test
    @DisplayName("3-2 rapor JSON: SNAKE_CASE alanlar = FIELDS = asMap (aynı sıra / değer); gidiş-dönüş eşit; bozuk / boş → null; "
            + "bilinmeyen alan okumayı bozmaz")
    void r32ReportJson() {
        EvalLoadReport r = new EvalLoadReport(1_730_000_000_000L, "sitemonitor-b-0000000b", "observations", "no_handover",
                "observations", 7L, "sitemonitor-a-0000000a", null, 42_000L, 0, 3, 0, 0, 2, 17, 120L, 800L, 2);
        String json = r.toJson();
        Map<String, Object> parsed = parse(json);
        assertThat(parsed.keySet()).containsExactlyInAnyOrderElementsOf(EvalLoadReport.FIELDS);
        assertThat(List.copyOf(r.asMap().keySet())).containsExactlyElementsOf(EvalLoadReport.FIELDS);
        assertThat(parsed).containsEntry("handover_age_ms", null).containsEntry("from_pod", "sitemonitor-a-0000000a")
                .containsEntry("window_domains", 17).containsEntry("outcome", "observations");
        r.asMap().forEach((k, v) -> assertThat(parsed.get(k) == null ? null : String.valueOf(parsed.get(k)))
                .as(k).isEqualTo(v == null ? null : String.valueOf(v)));

        assertThat(EvalLoadReport.fromJson(json)).isEqualTo(r);
        assertThat(EvalLoadReport.fromJson(r.payload())).isEqualTo(r);
        assertThat(EvalLoadReport.fromJson(null)).isNull();
        assertThat(EvalLoadReport.fromJson("  ")).isNull();
        assertThat(EvalLoadReport.fromJson("{bozuk")).isNull();
        EvalLoadReport newer = EvalLoadReport.fromJson(json.replaceFirst("\\{", "{\"yeni_alan\":5,"));
        assertThat(newer).as("daha yeni bir pod'un ek alanı").isEqualTo(r);
        EvalLoadReport partial = EvalLoadReport.fromJson("{\"outcome\":\"none\",\"reason\":\"timeout\",\"siblings\":null}");
        assertThat(partial).isNotNull();
        assertThat(partial.outcome()).isEqualTo("none");
        assertThat(partial.siblings()).isZero();
        assertThat(partial.fromGeneration()).isNull();
    }

    @Test
    @DisplayName("3-2 rapor yükü: en kötü durumda (uzun / kaçışlı pod kimlikleri, uzun neden, uç sayılar) geçerli JSON ve ≤ 4000; "
            + "metin kırpılır (vekil çifti bölünmez), negatif sayı 0")
    void r32ReportPayloadFits() {
        String control = "\u0001".repeat(5_000);                       // her biri JSON'da \\u0001 = 6 karakter
        String surrogate = "a".repeat(EvalLoadReport.ID_MAX - 1) + "😀" + "b".repeat(10);
        EvalLoadReport worst = new EvalLoadReport(Long.MIN_VALUE, control, control, control, control, Long.MAX_VALUE,
                surrogate, Long.MIN_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE);
        assertThat(worst.pod()).hasSize(EvalLoadReport.ID_MAX);
        assertThat(worst.outcome()).hasSize(EvalLoadReport.CODE_MAX);
        assertThat(worst.reason()).hasSize(EvalLoadReport.REASON_MAX);
        assertThat(worst.fromPod()).as("yüksek vekilde bölünmez").hasSize(EvalLoadReport.ID_MAX - 1)
                .doesNotContain("\uD83D");
        String payload = worst.payload();
        assertThat(payload.length()).isLessThanOrEqualTo(EvalLoadReport.PAYLOAD_MAX);
        assertThat(parse(payload)).containsKeys("outcome", "reason", "pod");
        assertThat(EvalLoadReport.fromJson(payload)).isEqualTo(worst);

        EvalLoadReport negative = new EvalLoadReport(1L, "w", "none", "x", null, null, null, null, null,
                -1, -2, -3, -4, -5, -6, -7L, -8L, -9);
        assertThat(List.of(negative.recoveryCounters(), negative.siblings(), negative.suppressions(), negative.roundSeen(),
                negative.windowTypes(), negative.windowDomains(), negative.waiters())).containsOnly(0);
        assertThat(negative.readMs()).isZero();
        assertThat(negative.maxWaitMs()).isZero();
    }

    @Test
    @DisplayName("3-2 rapor fabrikaları: none (yalnız neden / kaynaklı), discarded (sonuç none, sayılar 0, katman + kaynak yükten), "
            + "applied (katman, FULL'da neden yok, OBSERVATIONS'da kısmi neden, sayılar), withWaits")
    void r32ReportFactories() {
        EvalLoadReport off = EvalLoadReport.none(5L, "pod-b", EvalLoadReport.REASON_SETTING_OFF);
        assertThat(off.outcome()).isEqualTo(EvalLoadReport.OUTCOME_NONE);
        assertThat(off.reason()).isEqualTo("setting_off");
        assertThat(off.loaded()).isFalse();
        assertThat(off.attemptedTier()).isNull();
        assertThat(off.fromGeneration()).isNull();
        assertThat(off.siblings()).isZero();

        EvalLoadReport cont = EvalLoadReport.none(6L, "pod-b", EvalLoadReport.REASON_READ_FAILED, EvalLoad.TIER_FULL, 4L,
                "pod-a", 2_000L, 3_000L, 55L);
        assertThat(cont).extracting(EvalLoadReport::outcome, EvalLoadReport::attemptedTier, EvalLoadReport::fromGeneration,
                        EvalLoadReport::fromPod, EvalLoadReport::handoverAgeMs, EvalLoadReport::durableAgeMs,
                        EvalLoadReport::readMs)
                .containsExactly("none", "full", 4L, "pod-a", 2_000L, 3_000L, 55L);

        EvalLoad full = new EvalLoad(9L, EvalLoad.TIER_FULL,
                Map.of("PORT_DOWN:a.com", new EvalLoad.Recovery(2, 100L), "HTTP_DOWN:b.com", new EvalLoad.Recovery(1, null)),
                Map.of("PORT_DOWN|7", new EvalLoad.Sibling("PORT_DOWN", "a.com", "443", 7L, 3L, true, "NETWORK", 90L)),
                Set.of("PORT_DOWN"), Set.of("PORT_DOWN", "HTTP_DOWN", "DNS_FAILURE"),
                Map.of("PORT_DOWN", Map.of("a.com", new EvalLoad.SeedMark(true, 90L), "b.com", new EvalLoad.SeedMark(false, 80L)),
                        "HTTP_DOWN", Map.of("b.com", new EvalLoad.SeedMark(false, 70L))),
                Map.of("PORT_DOWN", 30_000L),
                new EvalLoad.Meta(4L, "pod-a", 1_500L, 2_500L, null, 33L));
        EvalLoadReport applied = EvalLoadReport.applied(10L, "pod-b", full).withWaits(700L, 3);
        assertThat(applied.outcome()).isEqualTo("full");
        assertThat(applied.reason()).isNull();
        assertThat(applied.loaded()).isTrue();
        assertThat(List.of(applied.recoveryCounters(), applied.siblings(), applied.suppressions(), applied.roundSeen(),
                applied.windowTypes(), applied.windowDomains())).containsExactly(2, 1, 1, 3, 2, 3);
        assertThat(applied).extracting(EvalLoadReport::attemptedTier, EvalLoadReport::fromGeneration,
                        EvalLoadReport::fromPod, EvalLoadReport::handoverAgeMs, EvalLoadReport::durableAgeMs,
                        EvalLoadReport::readMs, EvalLoadReport::maxWaitMs, EvalLoadReport::waiters)
                .containsExactly("full", 4L, "pod-a", 1_500L, 2_500L, 33L, 700L, 3);

        EvalLoad obs = new EvalLoad(9L, EvalLoad.TIER_OBSERVATIONS, null,
                Map.of("PORT_DOWN|7", new EvalLoad.Sibling("PORT_DOWN", "a.com", "443", 7L, null, null, null, 90L)),
                null, null, null, Map.of("PORT_DOWN", 30_000L),
                new EvalLoad.Meta(4L, "pod-a", null, 2_500L, EvalLoadReport.REASON_NO_HANDOVER, 12L));
        EvalLoadReport partial = EvalLoadReport.applied(11L, "pod-b", obs);
        assertThat(partial.outcome()).isEqualTo("observations");
        assertThat(partial.reason()).isEqualTo("no_handover");
        assertThat(partial.siblings()).isEqualTo(1);
        assertThat(partial.recoveryCounters()).isZero();

        EvalLoadReport dropped = EvalLoadReport.discarded(12L, "pod-b", EvalLoadReport.REASON_EVALUATED_BEFORE_LOAD, full);
        assertThat(dropped.outcome()).isEqualTo("none");
        assertThat(dropped.reason()).isEqualTo("evaluated_before_load");
        assertThat(dropped.attemptedTier()).isEqualTo("full");
        assertThat(dropped.fromGeneration()).isEqualTo(4L);
        assertThat(dropped.fromPod()).isEqualTo("pod-a");
        assertThat(dropped.readMs()).isEqualTo(33L);
        assertThat(List.of(dropped.recoveryCounters(), dropped.siblings(), dropped.suppressions(), dropped.roundSeen(),
                dropped.windowTypes(), dropped.windowDomains())).as("yüklenen yok").containsOnly(0);
        EvalLoadReport droppedNull = EvalLoadReport.discarded(13L, "pod-b", EvalLoadReport.REASON_TIMEOUT, null);
        assertThat(droppedNull.attemptedTier()).isNull();
        assertThat(droppedNull.fromGeneration()).as("kuşak 0 = bilinmiyor").isNull();
    }

    @Test
    @DisplayName("3-2 yük: katman doğrulanır; GÖZLEMLER katmanında sayaç / bastırma / tur görüldü BOŞALTILIR (J-4); kopya, null atılır, "
            + "değişmez; kardeş anahtarı ve ince kalem; pencere sayısı; boş meta")
    void r32EvalLoad() {
        assertThatThrownBy(() -> new EvalLoad(1L, "none", null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvalLoad(1L, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, EvalLoad.Recovery> rec = new HashMap<>();
        rec.put("PORT_DOWN:a.com", new EvalLoad.Recovery(2, 100L));
        rec.put("PORT_DOWN:b.com", null);
        Set<String> sup = new java.util.HashSet<>(List.of("PORT_DOWN"));
        Map<String, Map<String, EvalLoad.SeedMark>> win = new HashMap<>();
        Map<String, EvalLoad.SeedMark> marks = new HashMap<>();
        marks.put("a.com", new EvalLoad.SeedMark(true, 5L));
        marks.put(null, new EvalLoad.SeedMark(true, 5L));
        win.put("PORT_DOWN", marks);
        win.put("EMPTY_TYPE", new HashMap<>());

        EvalLoad obs = new EvalLoad(3L, EvalLoad.TIER_OBSERVATIONS, rec, null, sup, Set.of("PORT_DOWN"), win,
                Map.of("PORT_DOWN", 30_000L), null);
        assertThat(obs.full()).isFalse();
        assertThat(obs.recovery()).as("J-4: sayaç yalnız FULL").isEmpty();
        assertThat(obs.suppressionActive()).isEmpty();
        assertThat(obs.roundSeen()).isEmpty();
        assertThat(obs.meta()).isEqualTo(EvalLoad.Meta.EMPTY);
        assertThat(obs.window()).containsOnlyKeys("PORT_DOWN");
        assertThat(obs.window().get("PORT_DOWN")).containsOnlyKeys("a.com");
        assertThat(obs.windowDomains()).isEqualTo(1);

        EvalLoad full = new EvalLoad(3L, EvalLoad.TIER_FULL, rec, null, sup, null, win, null, null);
        assertThat(full.full()).isTrue();
        assertThat(full.recovery()).containsOnlyKeys("PORT_DOWN:a.com");
        rec.put("PORT_DOWN:c.com", new EvalLoad.Recovery(1, null));
        sup.add("HTTP_DOWN");
        marks.put("z.com", new EvalLoad.SeedMark(false, 1L));
        assertThat(full.recovery()).as("kopya").hasSize(1);
        assertThat(full.suppressionActive()).containsExactly("PORT_DOWN");
        assertThat(full.window().get("PORT_DOWN")).hasSize(1);
        assertThatThrownBy(() -> full.recovery().put("x", new EvalLoad.Recovery(1, null)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> full.window().get("PORT_DOWN").put("x", new EvalLoad.SeedMark(true, 1L)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> full.suppressionActive().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(full.siblings()).isEmpty();
        assertThat(full.intervals()).isEmpty();

        EvalLoad.Sibling sib = new EvalLoad.Sibling("PORT_DOWN", "a.com", "443", 7L, 3L, true, "NETWORK", 90L);
        assertThat(sib.key()).isEqualTo("PORT_DOWN|7").isEqualTo(EvalLoad.siblingKey("PORT_DOWN", 7L));
        SweepItem slim = sib.slimItem();
        assertThat(slim.alertType()).isEqualTo("PORT_DOWN");
        assertThat(slim.domain()).isEqualTo("a.com");
        assertThat(slim.detail()).isEqualTo("443");
        assertThat(slim.up()).isFalse();
        assertThat(slim.error()).as("tabloda hata metni yok").isNull();
        assertThat(slim.recheck()).isNull();
        assertThat(slim.ctxExtra()).containsEntry("monitor_id", 7L).containsEntry("team_id", 3L)
                .containsEntry("standalone", true);
        assertThat(EvalStateKeys.itemKey(slim)).isEqualTo("m:7");
        SweepItem bareSlim = new EvalLoad.Sibling("PORT_DOWN", "a.com", null, 8L, null, null, null, 1L).slimItem();
        assertThat(bareSlim.ctxExtra()).containsOnlyKeys("monitor_id");
    }

    @Test
    @DisplayName("3-2 devir sonucu: sekiz değer sırayla, kalıcı küçük harf kodlar; yalnız WRITTEN işaret yazdı")
    void r32HandoverResult() {
        assertThat(List.of(HandoverResult.values())).containsExactly(HandoverResult.WRITTEN, HandoverResult.NOT_QUIESCENT,
                HandoverResult.INCOMPLETE, HandoverResult.NO_GENERATION, HandoverResult.FENCED, HandoverResult.FLUSH_FAILED,
                HandoverResult.BUDGET_EXCEEDED, HandoverResult.SKIPPED);
        assertThat(List.of(HandoverResult.values()).stream().map(HandoverResult::key).toList())
                .containsExactly("written", "not_quiescent", "incomplete", "no_generation", "fenced", "flush_failed",
                        "budget_exceeded", "skipped");
        assertThat(List.of(HandoverResult.values()).stream().filter(HandoverResult::markerWritten).toList())
                .containsExactly(HandoverResult.WRITTEN);
    }
}
