package com.sitemonitor.service.watchdog;

import com.sitemonitor.model.WatchdogState;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.checks.CheckJobStore;
import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.checks.CheckWorkerRegistry;
import com.sitemonitor.service.checks.DistributedChecks;
import com.sitemonitor.service.watchdog.WatchdogTransition.Action;
import com.sitemonitor.service.watchdog.WatchdogTransition.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Kendi kendini izleme {@code check_queue} kontrolü (2026-10-29, çok pod Sürüm 3-3, paket D1; tasarım §7.5): ana anahtar
 * kapalıyken NA ve SORGU YOK; açıkken en eski bekleyen / değerlendirilmeyen 2 dk'yı aşarsa ya da kirası dolmuş sahiplenme
 * varsa PROBLEM (orta); {@code confirm-checks} turla onaylanır; okuma hatası UNKNOWN (sayaçlar değişmez).
 */
class WatchdogCheckQueueTest {

    static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    JdbcTemplate jdbc;
    AppSettingsService settings;
    WatchdogProbes probes;
    CheckJobStore store;
    CheckWorkerRegistry registry;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        settings = mock(AppSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        probes = new WatchdogProbes(jdbc, settings);
        store = mock(CheckJobStore.class);
        registry = mock(CheckWorkerRegistry.class);
        probes.setCheckJobStore(store);
        probes.setCheckWorkerRegistry(registry);
    }

    void switchOn(boolean on, String types) {
        when(settings.getBoolean(eq(DistributedChecks.KEY_ENABLED), anyBoolean())).thenReturn(on);
        when(settings.getString(eq(DistributedChecks.KEY_TYPES), anyString())).thenReturn(types);
        probes.setDistributedChecks(new DistributedChecks(settings, DistributedChecks.Tuning.DEFAULTS));
    }

    static CheckJobStore.QueueStats stats(Long oldestPendingMs, Long oldestDoneMs, long expired) {
        return new CheckJobStore.QueueStats(NOW.toEpochMilli(), List.of(
                new CheckJobStore.QueueStat(CheckJobStore.STATE_PENDING, "port", 6, NOW.toEpochMilli() - 1_000, null),
                new CheckJobStore.QueueStat(CheckJobStore.STATE_CLAIMED, "port", 2, NOW.toEpochMilli() - 1_000, null),
                new CheckJobStore.QueueStat(CheckJobStore.STATE_DONE, "ping", 1, NOW.toEpochMilli() - 1_000, NOW.toEpochMilli() - 500)),
                oldestPendingMs, oldestDoneMs, expired);
    }

    @Test
    @DisplayName("anahtar KAPALI (ya da tür yok, ya da bean yok) → NA 'distributed_off'; kuyruk, kayıt ve veritabanı HİÇ çağrılmaz (K-1)")
    void offIsNaWithoutSql() {
        WatchdogProbes.Probe noBean = probes.run(WatchdogCheck.CHECK_QUEUE, NOW);
        assertThat(noBean.result()).isEqualTo(Result.NA);
        assertThat(noBean.facts()).containsEntry("reason", "distributed_off");
        switchOn(false, CheckJobTypes.DEFAULT_CSV);
        WatchdogProbes.Probe off = probes.run(WatchdogCheck.CHECK_QUEUE, NOW);
        assertThat(off.result()).isEqualTo(Result.NA);
        assertThat(off.facts()).containsEntry("reason", "distributed_off");
        switchOn(true, "");
        when(settings.getString(eq(DistributedChecks.KEY_TYPES), anyString())).thenReturn("nope");
        assertThat(probes.run(WatchdogCheck.CHECK_QUEUE, NOW).result()).isEqualTo(Result.NA);
        verifyNoInteractions(store, registry, jdbc);
        // detay cümlesi iki dilde, "uygulanmaz"
        assertThat(WatchdogMessages.detail(WatchdogCheck.CHECK_QUEUE, off.facts(), false)).contains("dağıtık kontroller kapalı");
        assertThat(WatchdogMessages.detail(WatchdogCheck.CHECK_QUEUE, off.facts(), true)).contains("distributed checks are off");
    }

    @Test
    @DisplayName("açık: eşik altı → OK (olgular: sayılar, en eski yaşlar sn, canlı işçi, sınır); en eski bekleyen > 2 dk → PROBLEM (orta); "
            + "en eski sonuç > 2 dk → PROBLEM; kirası dolmuş sahiplenme → PROBLEM; tam 2 dk sorun değil")
    void thresholds() {
        switchOn(true, "port,ping");
        when(registry.liveOtherWorkers(CheckJobTypes.PORT)).thenReturn(1);
        when(registry.workers()).thenReturn(List.of(
                new CheckWorkerRegistry.WorkerView("a", "a", Set.of("port"), 16, 1, 1, 0, null, NOW.toEpochMilli(), "v", false, true),
                new CheckWorkerRegistry.WorkerView("b", "b", Set.of("port"), 16, 1, 1, 0, null, NOW.toEpochMilli(), "v", true, true),
                new CheckWorkerRegistry.WorkerView("c", "c", Set.of("port"), 16, 1, 1, 0, null, NOW.toEpochMilli(), "v", false, false)));

        when(store.stats()).thenReturn(stats(4_000L, 900L, 0));
        WatchdogProbes.Probe ok = probes.run(WatchdogCheck.CHECK_QUEUE, NOW);
        assertThat(ok.result()).isEqualTo(Result.OK);
        assertThat(ok.facts()).containsEntry("pending", 6L).containsEntry("claimed", 2L).containsEntry("done", 1L)
                .containsEntry("oldest_pending_seconds", 4L).containsEntry("oldest_done_seconds", 0L).containsEntry("expired_claims", 0L)
                .containsEntry("limit_seconds", 120L).containsEntry("live_workers", 1).containsKey("evaluated_by");

        when(store.stats()).thenReturn(stats(WatchdogProbes.CHECK_QUEUE_LIMIT_MS, WatchdogProbes.CHECK_QUEUE_LIMIT_MS, 0));
        assertThat(probes.run(WatchdogCheck.CHECK_QUEUE, NOW).result()).as("tam sınır sorun değil").isEqualTo(Result.OK);

        when(store.stats()).thenReturn(stats(WatchdogProbes.CHECK_QUEUE_LIMIT_MS + 1, 100L, 0));
        WatchdogProbes.Probe pending = probes.run(WatchdogCheck.CHECK_QUEUE, NOW);
        assertThat(pending.result()).isEqualTo(Result.PROBLEM);
        assertThat(pending.severity()).isEqualTo("MEDIUM");

        when(store.stats()).thenReturn(stats(null, 125_000L, 0));
        WatchdogProbes.Probe done = probes.run(WatchdogCheck.CHECK_QUEUE, NOW);
        assertThat(done.result()).isEqualTo(Result.PROBLEM);
        assertThat(done.facts()).doesNotContainKey("oldest_pending_seconds").containsEntry("oldest_done_seconds", 125L);
        assertThat(WatchdogMessages.detail(WatchdogCheck.CHECK_QUEUE, done.facts(), false))
                .contains("En eski değerlendirilmeyen sonuç: 125 sn (sınır 120 sn)").contains("Canlı işçi: 1");
        assertThat(WatchdogMessages.detail(WatchdogCheck.CHECK_QUEUE, done.facts(), true))
                .contains("Oldest unevaluated result: 125 s (limit 120 s)");

        when(store.stats()).thenReturn(stats(1_000L, 1_000L, 2));
        WatchdogProbes.Probe expired = probes.run(WatchdogCheck.CHECK_QUEUE, NOW);
        assertThat(expired.result()).isEqualTo(Result.PROBLEM);
        assertThat(WatchdogMessages.detail(WatchdogCheck.CHECK_QUEUE, expired.facts(), false)).contains("Kirası dolmuş 2 sahiplenme");
    }

    @Test
    @DisplayName("kuyruk okunamadı → UNKNOWN 'probe_failed' (sayaçlar değişmez); kayıt bilinmiyor → canlı işçi olgusu YOK (sahte 0 değil)")
    void unreadable() {
        switchOn(true, "port");
        when(store.stats()).thenThrow(new org.springframework.dao.QueryTimeoutException("x"));
        WatchdogProbes.Probe p = probes.run(WatchdogCheck.CHECK_QUEUE, NOW);
        assertThat(p.result()).isEqualTo(Result.UNKNOWN);
        assertThat(p.facts()).containsEntry("reason", "probe_failed");

        org.mockito.Mockito.doReturn(stats(1_000L, null, 0)).when(store).stats();
        when(registry.liveOtherWorkers(CheckJobTypes.PORT)).thenReturn(CheckWorkerRegistry.UNKNOWN);
        WatchdogProbes.Probe q = probes.run(WatchdogCheck.CHECK_QUEUE, NOW);
        assertThat(q.result()).isEqualTo(Result.OK);
        assertThat(q.facts()).doesNotContainKey("live_workers").doesNotContainKey("oldest_done_seconds");
    }

    @Test
    @DisplayName("onay: confirm-checks (2) ardışık PROBLEM turu → ALERT; tek tur 'izleniyor'; düzelince 2 OK turu → RECOVER; anahtar "
            + "kapatılınca NA açık sorunu kapatır (RECOVER)")
    void confirmRounds() {
        switchOn(true, "port");
        WatchdogState st = new WatchdogState();
        st.setCheckKey(WatchdogCheck.CHECK_QUEUE.key());
        when(store.stats()).thenReturn(stats(300_000L, null, 0));
        WatchdogProbes.Probe bad = probes.run(WatchdogCheck.CHECK_QUEUE, NOW);
        assertThat(WatchdogTransition.apply(st, bad.result(), bad.severity(), null, NOW, 2, 0)).isEqualTo(Action.NONE);
        assertThat(st.getStatus()).isEqualTo("OK");
        assertThat(WatchdogTransition.apply(st, bad.result(), bad.severity(), null, NOW.plusSeconds(60), 2, 0)).isEqualTo(Action.ALERT);
        assertThat(st.getStatus()).isEqualTo("PROBLEM");
        assertThat(st.getSeverity()).isEqualTo("MEDIUM");

        when(store.stats()).thenReturn(stats(1_000L, null, 0));
        WatchdogProbes.Probe good = probes.run(WatchdogCheck.CHECK_QUEUE, NOW);
        assertThat(WatchdogTransition.apply(st, good.result(), good.severity(), null, NOW.plusSeconds(120), 2, 0)).isEqualTo(Action.NONE);
        assertThat(WatchdogTransition.apply(st, good.result(), good.severity(), null, NOW.plusSeconds(180), 2, 0)).isEqualTo(Action.RECOVER);

        // yeniden sorun, sonra anahtar kapatılır → NA sorunu kapatır
        when(store.stats()).thenReturn(stats(300_000L, null, 0));
        WatchdogTransition.apply(st, Result.PROBLEM, "MEDIUM", null, NOW.plusSeconds(240), 2, 0);
        WatchdogTransition.apply(st, Result.PROBLEM, "MEDIUM", null, NOW.plusSeconds(300), 2, 0);
        assertThat(st.getStatus()).isEqualTo("PROBLEM");
        switchOn(false, "port");
        WatchdogProbes.Probe na = probes.run(WatchdogCheck.CHECK_QUEUE, NOW);
        assertThat(WatchdogTransition.apply(st, na.result(), na.severity(), null, NOW.plusSeconds(360), 2, 0)).isEqualTo(Action.RECOVER);
    }

    @Test
    @DisplayName("katalog: anahtar check_queue, varsayılan orta önem, iki dilde ad ve sorun cümlesi; katalogda SON sırada")
    void catalog() {
        assertThat(WatchdogCheck.byKey("check_queue")).isEqualTo(WatchdogCheck.CHECK_QUEUE);
        assertThat(WatchdogCheck.CHECK_QUEUE.severity()).isEqualTo(WatchdogCheck.Severity.MEDIUM);
        assertThat(WatchdogCheck.CHECK_QUEUE.labelTr()).isEqualTo("Dağıtık kontrol kuyruğu");
        assertThat(WatchdogCheck.CHECK_QUEUE.labelEn()).isEqualTo("Distributed check queue");
        assertThat(WatchdogCheck.CHECK_QUEUE.problemTr()).isNotBlank();
        assertThat(WatchdogCheck.CHECK_QUEUE.problemEn()).isNotBlank();
        WatchdogCheck[] all = WatchdogCheck.values();
        assertThat(all[all.length - 1]).isEqualTo(WatchdogCheck.CHECK_QUEUE);
        assertThat(all).hasSize(8);
    }
}
