package com.sitemonitor.service.checks;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Çok pod Sürüm 3-3 paket D2 — {@link CheckWorker}'ın paket-özel test yüzeyine başka paketteki çok pod testlerinden erişim
 * ({@code com.sitemonitor.service.DistributedChecksMultiPodTest} ve kardeşleri {@code MonitoringMultiPodKit}'e muhtaç olduğu için
 * {@code service} paketinde durur). Yalnız delege eder; davranış eklemez.
 */
public final class R33WorkerAccess {

    private R33WorkerAccess() {
    }

    /** Yoklama iş parçacığı olmadan kurar; {@code executor} işlerin koştuğu havuz (satır içi ya da bekletilen). */
    public static void startForTest(CheckWorker w, ExecutorService executor) {
        w.startForTest(executor);
    }

    /** Bir yoklama adımı (zaman aşımı taraması → toplu tamamlama → sahiplenme). */
    public static long tick(CheckWorker w) {
        return w.tick();
    }

    /** Bekleyen tamamlamaları hemen yazar. */
    public static void flush(CheckWorker w) {
        w.flush();
    }

    /** Koşan (sahiplenilmiş, bitmemiş) iş sayısı. */
    public static int running(CheckWorker w) {
        return w.running();
    }

    /** Yazılmayı bekleyen tamamlama sayısı. */
    public static int pendingCompletions(CheckWorker w) {
        return w.pendingCompletions();
    }

    /** Başlamamış işleri "bırakıldı" işaretler ve kimliklerini döner (kapanış yüzü; bellek). */
    public static List<Long> takeNotStarted(CheckWorker w) {
        return w.takeNotStarted();
    }
}
