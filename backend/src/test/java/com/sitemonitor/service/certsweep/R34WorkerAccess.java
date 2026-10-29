package com.sitemonitor.service.certsweep;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Çok pod Sürüm 3-4 paket C2 — {@link CertChunkWorker}'ın paket-özel test yüzeyine başka paketteki çok pod testlerinden erişim
 * ({@code com.sitemonitor.service.CertChunksMultiPodTest} ve kardeşleri {@code SchedulerService}'in paket-özel yüzeyine muhtaç
 * olduğu için {@code service} paketinde durur; 3-3'ün {@code R33WorkerAccess} deseni). Yalnız delege eder; davranış eklemez.
 */
public final class R34WorkerAccess {

    private R34WorkerAccess() {
    }

    /** Yoklama iş parçacığı olmadan kurar; {@code executor} parça koşucularının havuzu (satır içi ya da bekletilen). */
    public static void startForTest(CertChunkWorker w, ExecutorService executor) {
        w.startForTest(executor);
    }

    /** Bir yoklama adımı (kira yenileme → sahiplenme); dönüş = önerilen uyku (ms). */
    public static long tick(CertChunkWorker w) {
        return w.tick();
    }

    /** Uçuştaki (sahiplenilmiş, sonucu yazılmamış) parça sayısı. */
    public static int held(CertChunkWorker w) {
        return w.heldCount();
    }

    /** Ölçüm istisnası yüzünden {@code failed} işaretli tamamlanan parça sayısı. */
    public static long failedChunks(CertChunkWorker w) {
        return w.failedChunks();
    }

    /** Tutulan parçaları "bırakıldı" işaretler (kapanış yüzü; bellek). */
    public static List<Long> takeHeldForRelease(CertChunkWorker w) {
        return w.takeHeldForRelease();
    }
}
