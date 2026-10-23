package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.MonitoringOutageService.ConfirmState;
import com.sitemonitor.service.MonitoringOutageService.SweepItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Kancaların çağrılarını sırasıyla kaydeden sahte kaydedici (çok pod Sürüm 3-1 A2 testleri). {@code recovery} /
 * {@code typeState} çağrısında kaynağın O ANKİ değerini de kaydeder (gerçek kaydedici seq ayırdıktan sonra okur) —
 * "kanca mutasyondan SONRA" kuralının kanıtı. İsteğe bağlı: her çağrıda fırlatır ({@link #throwing()}).
 */
public final class RecordingSink implements EvalStateSink {

    /** Kaydedilen tek çağrı. */
    public record Op(String kind, String key, Object value, Object extra) {}

    private final List<Op> ops = Collections.synchronizedList(new ArrayList<>());
    private final boolean throwAlways;
    private volatile EvalStateSource source;
    private volatile int bindCalls;

    private RecordingSink(boolean throwAlways) {
        this.throwAlways = throwAlways;
    }

    public static RecordingSink recording() {
        return new RecordingSink(false);
    }

    /** Her çağrıda (bindSource dâhil) {@link IllegalStateException} fırlatan kaydedici — I-1 kanıtı. */
    public static RecordingSink throwing() {
        return new RecordingSink(true);
    }

    public List<Op> ops() {
        synchronized (ops) {
            return List.copyOf(ops);
        }
    }

    public List<Op> ops(String kind) {
        return ops().stream().filter(o -> o.kind().equals(kind)).toList();
    }

    public void clear() {
        ops.clear();
    }

    public EvalStateSource source() {
        return source;
    }

    public int bindCalls() {
        return bindCalls;
    }

    private void add(Op op) {
        ops.add(op);
        if (throwAlways) throw new IllegalStateException("kaydedici bozuk (test)");
    }

    @Override
    public void recovery(String rkey, String alertType, String domain) {
        EvalStateSource s = source;
        add(new Op("recovery", rkey, s == null ? null : s.recoveryValues(rkey), alertType + "|" + domain));
    }

    @Override
    public void observation(String alertType, SweepItem item, boolean manual, long atMs) {
        add(new Op("observation", alertType + "|" + EvalStateKeys.itemKey(item), item, manual + "@" + atMs));
    }

    @Override
    public void typeState(String alertType) {
        EvalStateSource s = source;
        add(new Op("typeState", alertType, s == null ? null : s.typeValues(alertType), null));
    }

    @Override
    public void chainStart(String confirmKey, long chainId, SweepItem item, ConfirmState state, String firstFailureAt,
                           long delayMs) {
        add(new Op("chainStart", confirmKey, state, chainId + "|" + delayMs + "|" + firstFailureAt + "|" + item.domain()));
    }

    @Override
    public void chainProgress(String confirmKey, long chainId, ConfirmState state, List<Map<String, Object>> attempts) {
        add(new Op("chainProgress", confirmKey, state, chainId + "|" + attempts.size()));
    }

    @Override
    public void chainFinishing(String confirmKey, long chainId, int lockTry) {
        add(new Op("chainFinishing", confirmKey, lockTry, chainId));
    }

    @Override
    public void chainEnd(String confirmKey, long chainId) {
        add(new Op("chainEnd", confirmKey, null, chainId));
    }

    @Override
    public void sweepEnd(String alertType) {
        add(new Op("sweepEnd", alertType, null, null));
    }

    @Override
    public void bindSource(EvalStateSource source) {
        this.source = source;
        bindCalls++;
        if (throwAlways) throw new IllegalStateException("kaydedici bozuk (test)");
    }

    @Override
    public Status status() {
        if (throwAlways) throw new IllegalStateException("kaydedici bozuk (test)");
        return null;
    }
}
