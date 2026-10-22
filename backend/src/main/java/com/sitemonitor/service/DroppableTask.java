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

    /**
     * Havuzu kapatır: yeni görev almaz, kuyruktakileri {@code min(capMs, ortak kapanış süresi)} kadar koşturur, sonra kalanları
     * keser; başlamamış her görevin izi yazılır. Dönüş: izi yazılan (başlamamış) görev sayısı.
     */
    static int drain(ExecutorService exec, long capMs) {
        exec.shutdown();
        try {
            if (exec.awaitTermination(ShutdownDeadline.waitMs(capMs), TimeUnit.MILLISECONDS)) return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Runnable> notStarted = exec.shutdownNow();
        int traced = 0;
        for (Runnable r : notStarted) {
            if (r instanceof DroppableTask t) {
                t.dropped();
                traced++;
            }
        }
        if (traced > 0) log.warn("Kapanış: {} bildirim görevi başlayamadı — her biri için iz yazıldı (gönderilmedi)", traced);
        return traced;
    }
}
