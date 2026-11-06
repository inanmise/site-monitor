package com.sitemonitor.service.schema;

import lombok.extern.slf4j.Slf4j;

import java.sql.SQLException;
import java.util.function.LongSupplier;

/**
 * Uzun {@code CONCURRENTLY} DDL'inin (REINDEX / DROP INDEX) BEKÇİSİ (2026-11-06, inceleme ORTA-2). Yeniden kurma / düşürme
 * tablo üzerinde {@code SHARE UPDATE EXCLUSIVE} tutar ve eski işlemleri bekler; o sırada başka bir oturum çakışan bir kilit
 * isterse (ör. gece dağıtılan yeni pod'un ddl-auto'su — {@code ALTER TABLE … ADD COLUMN}, {@code ACCESS EXCLUSIVE}, kilit
 * zaman aşımı yok) bizi bekler ve PostgreSQL kilit kuyruğu yüzünden o tablodaki BÜTÜN sonraki sorgular onun arkasında
 * birikir. {@code lock_timeout} bunu çözmez: o yalnız BİZİM beklememizi sınırlar, bizi bekleyenleri değil.
 *
 * <p>Bekçi AYRI bir bağlantıdan {@link #POLL_MS}'de bir yoklar: bizim oturumumuzu ({@code pid}) bekleyen başka oturum var mı
 * ({@code pid = ANY(pg_blocking_pids(…))}); art arda {@link #STRIKES} yoklamada (≈ 6 sn) varsa deyimimizi iptal eder
 * ({@code pg_cancel_backend}) — çağıran "başka bir oturumu bekletiyordu" diye bırakır ({@link BlockingOthersException}).
 * Yoklama art arda {@link #STRIKES} kez başarısız olursa da iptal edilir (kör bekçiyle uzun DDL koşmaz). İptal isteği
 * gönderilemezse gerekçe yazılmaz, yoklama sürer (sonraki turda yeniden denenir; deyim yine kendi süresiyle sınırlı).
 *
 * <p>Sınırlı: iş parçacığı yalnız DDL sürerken yaşar ({@link #stop()} bekletmeden uyandırır ve en çok {@link #JOIN_MS}
 * birleştirir), en çok {@code maxMs} + {@link #MAX_EXTRA_MS} döner ve bağlantısını kendi kapatır. Cin iş parçacığıdır.
 * {@link #stop()} sonrası iptal gönderilmez (aynı kilit) — biten bir deyimden sonra gelen geç iptal, boşta bekleyen oturumda
 * PostgreSQL tarafından yok sayılır.
 */
@Slf4j
final class DdlBlockWatch {

    static final long POLL_MS = 2_000L;
    static final int STRIKES = 3;
    static final long JOIN_MS = 5_000L;
    /** Bekçinin kendi tavanı: DDL'in deyim süresi + bu pay. */
    static final long MAX_EXTRA_MS = 60_000L;

    /** Bekçinin veritabanı yüzü (gerçeği {@link JdbcIndexCatalog.JdbcProbe}: ayrı, otomatik commit bağlantı). */
    interface Probe extends AutoCloseable {
        /** {@code pid}'i bekleyen (ondan başka) oturum sayısı. */
        int waitingFor(int pid) throws SQLException;

        /** {@code pid}'in süren deyimini iptal eder. */
        void cancel(int pid) throws SQLException;

        @Override
        void close();
    }

    private final Probe probe;
    private final int pid;
    private final long pollMs;
    private final long untilMs;
    private final LongSupplier clock;
    private final Object monitor = new Object();
    private boolean stopped;
    private boolean cancelWarned;
    private volatile String firedReason;
    private volatile boolean finished;
    private Thread thread;

    private DdlBlockWatch(Probe probe, int pid, long maxMs, long pollMs, LongSupplier clock) {
        this.probe = probe;
        this.pid = pid;
        this.pollMs = Math.max(1L, pollMs);
        this.clock = clock;
        this.untilMs = clock.getAsLong() + Math.max(0L, maxMs) + MAX_EXTRA_MS;
    }

    /** Bekçiyi başlatır (cin iş parçacığı {@code index-ddl-watch}). */
    static DdlBlockWatch start(Probe probe, int pid, long maxMs) {
        return start(probe, pid, maxMs, POLL_MS, System::currentTimeMillis);
    }

    static DdlBlockWatch start(Probe probe, int pid, long maxMs, long pollMs, LongSupplier clock) {
        DdlBlockWatch w = new DdlBlockWatch(probe, pid, maxMs, pollMs, clock);
        Thread t = new Thread(w::loop, "index-ddl-watch");
        t.setDaemon(true);
        w.thread = t;
        t.start();
        return w;
    }

    /** Bekçi deyimi iptal etti mi; gerekçe (yoksa null). */
    String firedReason() {
        return firedReason;
    }

    /** Test: iş parçacığı bitti mi (bağlantı kapandı). */
    boolean finished() {
        return finished;
    }

    /** DDL bitti: bekçiyi durdurur (bekleyen yoklamayı uyandırır) ve en çok {@link #JOIN_MS} birleştirir. */
    void stop() {
        synchronized (monitor) {
            stopped = true;
            monitor.notifyAll();
        }
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void loop() {
        int strikes = 0;
        int errors = 0;
        try {
            while (true) {
                synchronized (monitor) {
                    if (stopped) return;
                    monitor.wait(pollMs);
                    if (stopped) return;
                }
                if (clock.getAsLong() >= untilMs) return;
                String reason = null;
                try {
                    int n = probe.waitingFor(pid);
                    errors = 0;
                    strikes = n > 0 ? strikes + 1 : 0;
                    if (strikes >= STRIKES) reason = n + " oturum bu DDL'i bekliyordu";
                } catch (SQLException | RuntimeException e) {
                    strikes = 0;
                    if (++errors >= STRIKES) reason = "bekçi yoklayamadı: " + DeferredIndexBuilder.firstLine(e);
                }
                if (reason != null && fire(reason)) return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                probe.close();
            } finally {
                finished = true;
            }
        }
    }

    /**
     * DDL hâlâ sürüyorsa (durdurulmadıysa) iptal eder; durdurma ile yarışmaz (aynı kilit). Dönüş: bekçinin işi bitti mi
     * (iptal gönderildi ya da DDL zaten bitti). İptal gönderilemezse false — yoklama sürer.
     */
    private boolean fire(String reason) {
        synchronized (monitor) {
            if (stopped) return true;
            try {
                probe.cancel(pid);
                firedReason = reason;
                return true;
            } catch (SQLException | RuntimeException e) {
                if (!cancelWarned) {
                    cancelWarned = true;
                    log.warn("İndeks DDL bekçisi deyimi iptal edemedi ({}) — yeniden denenecek, deyim en geç kendi süresinde"
                            + " biter: {}", reason, DeferredIndexBuilder.firstLine(e));
                }
                return false;
            }
        }
    }
}
