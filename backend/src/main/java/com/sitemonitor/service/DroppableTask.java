package com.sitemonitor.service;

import com.sitemonitor.config.ShutdownDeadline;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Hiç BAŞLAMAYAN bildirim görevinin izi (2026-10-23, çok pod R2a · D2).
 *
 * <p><b>Neden.</b> Olay kaydı postası ve "yeni cihazdan giriş" postası kendi küçük havuzlarında kuyruğa girer. Pod durunca
 * (yuvarlanan dağıtım, ölçek küçültme) havuz {@code shutdownNow()} ile kapanıyor, kuyrukta bekleyen görevler SESSİZCE
 * düşüyordu: posta gitmiyor, {@code notification_logs}'ta neden yazmıyordu. Artık görev bu sarmalayıcıyla verilir; kapanışta
 * havuz ortak kapanış süresi kadar ({@link ShutdownDeadline#waitMs}) boşaltılır, sonra başlamamış her görevin {@code onDropped}
 * kancası (iz satırı) koşar. Kabul edilmeyen görev (havuz kapanıyor / kuyruk dolu) izini HEMEN yazar. Başlamış görev kendi
 * sonucunu yazar; kesilirse (kapanışın kesmesi) sonucu bilinmez — iz yazılmaz (en çok bir kez kuralı, posta gitmiş olabilir).
 *
 * <p>Görev havuza {@link ExecutorService#execute} ile verilir ({@code submit} sarmalardı ve {@code shutdownNow()} listesinden
 * görev tanınmazdı).
 */
@Slf4j
final class DroppableTask implements Runnable {

    private final Runnable work;
    private final Runnable onDropped;

    private DroppableTask(Runnable work, Runnable onDropped) {
        this.work = work;
        this.onDropped = onDropped;
    }

    @Override
    public void run() {
        work.run();
    }

    void dropped() {
        try {
            if (onDropped != null) onDropped.run();
        } catch (Exception e) {
            log.warn("Başlamayan bildirim görevinin izi yazılamadı: {}", e.toString());
        }
    }

    /** Görevi havuza verir. {@code false} = kabul edilmedi (kapanıyor / kuyruk dolu) — iz bu çağrıda yazıldı. */
    static boolean submit(ExecutorService exec, Runnable work, Runnable onDropped) {
        DroppableTask t = new DroppableTask(work, onDropped);
        try {
            exec.execute(t);
            return true;
        } catch (RejectedExecutionException e) {
            t.dropped();
            return false;
        }
    }

    /** Kapanışta görev başına yazılan iz satırı üst sınırı (inceleme 2026-10-23): kalanlar TEK özet satırında. */
    static final int MAX_TRACES = 200;

    /**
     * Havuzu kapatır: yeni görev almaz, kuyruktakileri {@code min(capMs, ortak kapanış süresi)} kadar koşturur, sonra kalanları
     * keser; başlamamış görevlerin ilk {@code maxTraces} tanesinin izi tek tek yazılır, kalanların sayısı {@code onMore}'a
     * verilir (tek özet satırı — kapanış bütçesi tükendikten sonra sınırsız yazım yok). Dönüş: başlamamış görev sayısı.
     */
    static int drain(ExecutorService exec, long capMs, int maxTraces, java.util.function.IntConsumer onMore) {
        exec.shutdown();
        try {
            if (exec.awaitTermination(ShutdownDeadline.waitMs(capMs), TimeUnit.MILLISECONDS)) return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Runnable> notStarted = exec.shutdownNow();
        int total = 0, traced = 0;
        for (Runnable r : notStarted) {
            if (!(r instanceof DroppableTask t)) continue;
            total++;
            if (traced < Math.max(0, maxTraces)) {
                t.dropped();
                traced++;
            }
        }
        int more = total - traced;
        if (more > 0 && onMore != null) {
            try {
                onMore.accept(more);
            } catch (Exception e) {
                log.warn("Başlamayan bildirim görevlerinin özet izi yazılamadı: {}", e.toString());
            }
        }
        if (total > 0) log.warn("Kapanış: {} bildirim görevi başlayamadı — {} iz satırı{} (gönderilmedi)", total, traced,
                more > 0 ? " + " + more + " görev için tek özet satırı" : "");
        return total;
    }
}
