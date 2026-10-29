package com.sitemonitor.service.certsweep;

/**
 * Bir parçanın durumu (2026-10-29, çok pod Sürüm 3-4 P0 sözleşmesi, tasarım R3-4 §4.2 "Coordinator" / §4.5 adım 3) —
 * koordinatörün {@value CertChunking#COORDINATOR_WAIT_POLL_MS} ms'de bir okuduğu satır ({@link CertSweepStore#chunkStates};
 * {@code results_json} OKUNMAZ). Süreler deponun saatiyle ({@link CertSweepStore#nowMs()}) ms'dir.
 *
 * @param id            parça kimliği
 * @param chunkNo       tur içindeki parça numarası
 * @param state         {@link CertSweepStore#CHUNK_PENDING} / {@code CLAIMED} / {@code DONE} / {@code CANCELLED}
 * @param attempts      kaç kez sahiplenildi (kapanışta bırakma bir geri alır)
 * @param domainCount   parçadaki alan adı sayısı
 * @param createdMs     yazılma anı
 * @param claimedBy     son sahiplenen örnek kimliği (PENDING'de null olabilir)
 * @param claimedMs     son sahiplenme anı (null olabilir)
 * @param leaseUntilMs  kira bitişi (yalnız CLAIMED'de dolu)
 * @param worker        tamamlayan örnek kimliği (yalnız DONE)
 * @param finishedMs    tamamlanma anı (yalnız DONE)
 * @param checked       sonuç sayısı (yalnız DONE)
 * @param networkErrors ağ sınıfı hata sayısı (yalnız DONE; karar için değil — ilerleme / aykırı pod kuralı)
 * @param avoidWorker   ikinci görüş parçasında kaçınılan işçi (diğerlerinde null)
 */
public record ChunkState(long id, int chunkNo, String state, int attempts, int domainCount, long createdMs,
                         String claimedBy, Long claimedMs, Long leaseUntilMs, String worker, Long finishedMs,
                         Integer checked, Integer networkErrors, String avoidWorker) {

    public boolean pending() {
        return CertSweepStore.CHUNK_PENDING.equals(state);
    }

    public boolean claimed() {
        return CertSweepStore.CHUNK_CLAIMED.equals(state);
    }

    public boolean done() {
        return CertSweepStore.CHUNK_DONE.equals(state);
    }

    public boolean cancelled() {
        return CertSweepStore.CHUNK_CANCELLED.equals(state);
    }

    /** Henüz sonucu olmayan ve iptal edilmemiş (PENDING ya da CLAIMED). */
    public boolean open() {
        return pending() || claimed();
    }

    /** CLAIMED ve kirası {@code nowMs}'ten önce dolmuş (sahiplenme deyimi böylesini yeniden alabilir). */
    public boolean leaseExpired(long nowMs) {
        return claimed() && leaseUntilMs != null && leaseUntilMs < nowMs;
    }

    /**
     * KAYIP parça (§2.4 / §4.5 adım 3): işçilere artık verilmez — {@code attempts ≥} {@value CertChunking#MAX_ATTEMPTS} VE
     * (PENDING ya da kirası dolmuş CLAIMED). Koordinatör onu iptal eder ({@link CertSweepStore#cancel}) ve kendisi ölçer.
     * Sahiplenme deyiminin {@code attempts < MAX_ATTEMPTS} koşuluyla aynı sınır.
     */
    public boolean lost(long nowMs) {
        return attempts >= CertChunking.MAX_ATTEMPTS && (pending() || leaseExpired(nowMs));
    }

    /** Yazılmadan bu yana geçen süre (ms, ≥ 0). */
    public long ageMs(long nowMs) {
        return Math.max(0L, nowMs - createdMs);
    }
}
