package com.sitemonitor.service.metrics;

import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tarama liderliği geçiş sayacı (2026-10-23, çok pod R2b) — {@code sitemonitor_sweep_leader_transitions{reason}} POD
 * ölçümü. {@code SchedulerService} her geçişte {@link SweepLeadershipChangedEvent}'i TAM BİR KEZ, liderlik kilidi
 * tutulurken eşzamanlı yayınlar; dinleyici yalnız sayaç artırır (hızlı, istisnasız). Sayaç bu pod'un açılıştan beri
 * gördüğü geçişlerdir — sağlıklı kümede nadiren artar; sık LOST / GAINED dalgalanması kira yenilemesinin aksadığını,
 * veritabanı gecikmesini ya da saat kaymasını gösterir.
 */
@Component
public class SweepLeadershipCounter {

    /** Sabit etiket kümesi (her biri 0'dan başlar — seri hiç olmamış gibi "veri yok" görünmez). */
    public static final List<String> REASONS = List.of(SweepLeadershipChangedEvent.GAINED, SweepLeadershipChangedEvent.LOST,
            SweepLeadershipChangedEvent.RELEASED);

    private final Map<String, AtomicLong> counts = new LinkedHashMap<>();

    public SweepLeadershipCounter() {
        for (String r : REASONS) counts.put(r, new AtomicLong());
    }

    @EventListener
    public void onLeadershipChanged(SweepLeadershipChangedEvent ev) {
        if (ev == null || ev.reason() == null) return;
        AtomicLong c = counts.get(ev.reason());
        if (c != null) c.incrementAndGet();
    }

    /** Geçiş sayıları (neden → sayı), sabit sırada. */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counts.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}
