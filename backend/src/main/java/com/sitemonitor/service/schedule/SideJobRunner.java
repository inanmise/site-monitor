package com.sitemonitor.service.schedule;

import java.util.List;

/**
 * Pod başına yan iş koşucusu (2026-10-23, çok pod Sürüm 3-1; tasarım R3-1 §3.2). <b>P0 iskeleti</b> — gövde (ad başına
 * birleştiren {@code ScheduledThreadPoolExecutor}, {@code submit} / {@code schedule}, {@code ContextClosedEvent}'te kabulü
 * kesme, {@code stopAndWait} paylaşılan kapanış bütçesiyle, durum sayaçları) paket C'nindir. Bu iskelet bean DEĞİLDİR ve
 * hiçbir şey koşturmaz; yalnız {@link Status} sözleşmesini ve {@link #status()} imzasını sabitler — Sistem Sağlığı
 * ({@code data.side_jobs}, paket B) koşucuyu {@code @Autowired(required = false)} alıp yalnız {@link #status()} okur.
 */
public class SideJobRunner {

    /** İş durumları ({@link Status.Job#state()}) — kalıcı (ekran / ölçü etiketi). */
    public static final String STATE_QUEUED = "queued";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_IDLE = "idle";

    /** Son sonuçlar ({@link Status.Job#lastOutcome()}) — kalıcı. */
    public static final String OUTCOME_OK = "ok";
    public static final String OUTCOME_FAILED = "failed";
    /** Ertelenen denemede iş zaten yapılmıştı (tur başka pod'da alındı / {@code alreadyDone}) — gövde koşmadı. */
    public static final String OUTCOME_SKIPPED = "skipped";
    /** Kapanışta kesildi. */
    public static final String OUTCOME_INTERRUPTED = "interrupted";

    /**
     * Bu pod'un yan iş durumu (asla fırlatmaz, veritabanı okumaz — canlı pod gözlemi önbellekten).
     *
     * @param threads            koşucu iş parçacığı sayısı (0 = satır içi koşu: testler / iskelet)
     * @param accepting          yeni iş kabul ediyor mu (kapanışta false)
     * @param leaderYieldEnabled lider erteleme / tur bırakma kuralları açık mı
     *                           ({@code site.monitor.scheduler.leader-yield.enabled})
     * @param leaderHint         bu pod tarama lideri mi ({@code SchedulerService.sweepLeaderHint()})
     * @param otherLivePod       başka canlı pod görüldü mü — null = bilinmiyor (okunamadı; erteleme YAPILMAZ)
     * @param jobs               iş başına satır (ad sırasıyla); hiç koşmamış iş listede olmayabilir
     */
    public record Status(int threads, boolean accepting, boolean leaderYieldEnabled, boolean leaderHint,
                         Boolean otherLivePod, List<Job> jobs) {

        public Status {
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
        }

        /**
         * Bir işin durumu (zamanlar epoch ms; null = hiç).
         *
         * @param name             {@link SideJob#name()}
         * @param state            {@link #STATE_QUEUED} | {@link #STATE_RUNNING} | {@link #STATE_IDLE}
         * @param startedAtMs      süren / son koşunun başlangıcı
         * @param lastFinishedAtMs son koşunun bitişi
         * @param lastOutcome      {@link #OUTCOME_OK} | {@link #OUTCOME_FAILED} | {@link #OUTCOME_SKIPPED} |
         *                         {@link #OUTCOME_INTERRUPTED}; null = hiç bitmedi
         * @param runs             koşturulan gövde sayısı (birikimli, açılıştan beri)
         * @param deferredCount    lider olarak ertelenen tetik sayısı
         * @param yieldedCount     lider olarak başka pod'a bırakılan tur sayısı
         * @param skippedCount     atlanan (zaten yapılmış / birleştirilmiş / kabul edilmemiş) tetik sayısı
         */
        public record Job(String name, String state, Long startedAtMs, Long lastFinishedAtMs, String lastOutcome,
                          long runs, long deferredCount, long yieldedCount, long skippedCount) {}
    }

    /** Bu pod'un yan iş durumu. İskelet: satır içi, kabul yok, kural kapalı, iş yok (paket C gerçek değeri döner). */
    public Status status() {
        return new Status(0, false, false, false, null, List.of());
    }
}
