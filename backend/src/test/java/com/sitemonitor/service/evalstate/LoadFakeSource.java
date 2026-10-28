package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.MonitoringOutageService.SweepItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Çok pod Sürüm 3-2 A testleri için sahte {@link EvalStateSource} — MOS'un (paket B) yükleme yüzünü taklit eder: belirteç
 * ({@link #arm}), {@link #applyEvalLoad} / {@link #evalLoadSkipped} yalnız belirteç eşleşirse kabul (sonra belirteç 0),
 * {@link #replay()} bağlı kaydediciye bellekteki her anahtarı yeniden kuyruğa alır (MOS {@code replayEvalState} gibi:
 * kurtarma anahtarları, türler, DOWN kardeşler). Uygulanan yük "belleğe" konur (kurtarma sayaçları) ki ardışık devirler sayacı
 * taşısın.
 */
final class LoadFakeSource implements EvalStateSource {

    final Map<String, RecoveryValues> recovery = new ConcurrentHashMap<>();
    final Map<String, TypeValues> types = new ConcurrentHashMap<>();
    /** "tür|kimlik" → bellekteki DOWN gözlemi (kaydedici okur) + oynatma için kalem. */
    final Map<String, DownValues> downs = new ConcurrentHashMap<>();
    final Map<String, SweepItem> downItems = new ConcurrentHashMap<>();
    final AtomicLong token = new AtomicLong();
    final List<EvalLoad> applied = new CopyOnWriteArrayList<>();
    final List<EvalLoadReport> skipped = new CopyOnWriteArrayList<>();
    final AtomicInteger replays = new AtomicInteger();
    volatile EvalLoadReport last;
    volatile EvalStateSink sink;
    volatile long hookFailures;
    /** Uygulama çağrısında koşar (ör. belirteci düşürmek / gecikme). */
    volatile Runnable onApply = () -> {};
    volatile boolean acceptApply = true;
    volatile boolean throwOnReplay;
    /** Raporların anı (yayın sırası için). */
    volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    long arm(long t) {
        token.set(t);
        return t;
    }

    @Override
    public long evalLoadToken() {
        return token.get();
    }

    @Override
    public boolean applyEvalLoad(EvalLoad load) {
        onApply.run();
        if (load == null || load.token() != token.get() || !acceptApply) {
            last = EvalLoadReport.discarded(clock.getAsLong(), "fake", EvalLoadReport.REASON_LEADERSHIP_CHANGED, load);
            return false;
        }
        applied.add(load);
        load.recovery().forEach((k, r) -> recovery.put(k, new RecoveryValues(r.upCount(), r.lastCountedMs(), null, null)));
        last = EvalLoadReport.applied(clock.getAsLong(), "fake", load);
        token.set(0L);
        return true;
    }

    @Override
    public void evalLoadSkipped(long t, EvalLoadReport report) {
        if (t != token.get()) return;
        skipped.add(report);
        last = report;
        token.set(0L);
    }

    @Override
    public EvalLoadReport lastEvalLoad() {
        return last;
    }

    @Override
    public int replay() {
        replays.incrementAndGet();
        if (throwOnReplay) throw new IllegalStateException("oynatma hatası");
        EvalStateSink s = sink;
        if (s == null) return 0;
        int n = 0;
        for (String k : recovery.keySet()) {
            int c = k.indexOf(':');
            s.recovery(k, k.substring(0, c), k.substring(c + 1));
            n++;
        }
        for (String t : types.keySet()) {
            s.typeState(t);
            n++;
        }
        for (Map.Entry<String, SweepItem> e : downItems.entrySet()) {
            int bar = e.getKey().indexOf('|');
            DownValues dv = downs.get(e.getKey());
            s.observation(e.getKey().substring(0, bar), e.getValue(), false, dv == null ? 0L : dv.atMs());
            n++;
        }
        return n;
    }

    /** Bellekte bir DOWN kardeş (kaydedici {@link #downObservation} ile okur, oynatma yeniden yazar). */
    void down(String type, long monitorId, String domain, String errorClass, long atMs) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("monitor_id", monitorId);
        ctx.put("team_id", 3L);
        ctx.put("standalone", true);
        SweepItem item = new SweepItem(type, domain, "443", false, "Connection refused", ctx, null);
        downs.put(type + "|" + monitorId, new DownValues(true, domain, "443", 3L, true, errorClass, atMs));
        downItems.put(type + "|" + monitorId, item);
    }

    @Override
    public DownValues downObservation(String alertType, long monitorId) {
        return downs.getOrDefault(alertType + "|" + monitorId, DownValues.ABSENT);
    }

    @Override
    public long hookFailures() {
        return hookFailures;
    }

    @Override
    public RecoveryValues recoveryValues(String rkey) {
        return recovery.getOrDefault(rkey, RecoveryValues.NONE);
    }

    @Override
    public TypeValues typeValues(String alertType) {
        return types.getOrDefault(alertType, TypeValues.NONE);
    }

    @Override
    public String errorClass(String error, String domain) {
        if (error == null || error.isBlank()) return null;
        return error.contains("Connection") || error.contains("timed out") ? ERROR_NETWORK : ERROR_OTHER;
    }
}
