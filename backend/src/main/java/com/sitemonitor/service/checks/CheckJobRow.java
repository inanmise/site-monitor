package com.sitemonitor.service.checks;

/**
 * İşçinin sahiplendiği bir kontrol işi (2026-10-29, çok pod Sürüm 3-3 P0, tasarım R3-3 §3.1 / §3.2) — {@code monitor_check_jobs}
 * satırının CLAIMED anındaki hâli; {@link CheckJobStore#claim} döner, {@link CheckJobStore#complete} /
 * {@link CheckJobStore#release} {@link #id} + {@link #claimToken} ile koşullu yazar. Süreler VERİTABANI-ÇAPALI saatle
 * ({@link DbClock}) ms'dir; pod saatine dayanmaz.
 *
 * @param id           iş kimliği (gönderme sırası = aynı izlemenin kontrol sırası)
 * @param type         {@link CheckJobTypes} adı
 * @param monitorId    izleme kimliği (türün tablosunda)
 * @param createdMs    gönderme anı (DB saati, ms)
 * @param attempts     bu sahiplenme DAHİL kaç kez sahiplenildi (1 = ilk; en çok {@link CheckJobTypes#MAX_ATTEMPTS})
 * @param leaseMs      gönderen liderin koyduğu kira (ms; {@link CheckJobTypes#leaseMs})
 * @param claimToken   bu sahiplenmenin rastgele belirteci (tamamlama / bırakma CAS'ı; ≤ 40 karakter)
 * @param claimedMs    sahiplenme anı (DB saati, ms)
 * @param leaseUntilMs kira bitişi = {@code claimedMs + leaseMs} (DB saati, ms) — sonrasında başka işçi yeniden alabilir
 */
public record CheckJobRow(long id, String type, long monitorId, long createdMs, int attempts, long leaseMs,
                          String claimToken, long claimedMs, long leaseUntilMs) {

    /** Gönderimden sahiplenmeye geçen süre (ms, ≥ 0) — "sahiplenme gecikmesi" ölçüsü. */
    public long claimLagMs() {
        return Math.max(0L, claimedMs - createdMs);
    }

    /** İşçinin kontrolü keseceği süre (ms) = kira − {@link CheckJobTypes#WORKER_TIMEOUT_MARGIN_MS} (en az 1 sn). */
    public long workerTimeoutMs() {
        return Math.max(1_000L, leaseMs - CheckJobTypes.WORKER_TIMEOUT_MARGIN_MS);
    }
}
