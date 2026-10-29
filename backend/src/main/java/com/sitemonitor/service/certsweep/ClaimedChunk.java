package com.sitemonitor.service.certsweep;

import java.util.List;
import java.util.Map;

/**
 * İşçinin sahiplendiği parça (2026-10-29, çok pod Sürüm 3-4 P0 sözleşmesi, tasarım R3-4 §4.2 "Claim" / §4.4) —
 * {@link CertSweepStore#claim} döner; {@link CertSweepStore#complete} / {@link CertSweepStore#renew} /
 * {@link CertSweepStore#release} {@link #id} + {@link #claimToken} (ve sahip) ile koşullu yazar. Süreler deponun saatiyle
 * ({@link CertSweepStore#nowMs()}, veritabanı-çapalı) ms'dir.
 *
 * <p><b>Ek alanlar (FX-WORKER, 2026-10-29; SW-M1 / CR-L1 / CR-H-1 b):</b> {@link #coordinator}, {@link #createdMs},
 * {@link #avoidWorker} — işçi aldığı parçanın kendi koşusuna mı ait olduğunu, ikinci görüş mü olduğunu ve sahiplenildiği anda
 * ne kadar beklediğini bilir (tarama liderinin bütçe kuralı). Eski 8 bileşenli kurucu korunur: koordinatör / kaçınılan işçi
 * bilinmez ({@code null}), {@code createdMs = claimedMs} (bekleme 0 — liderin "kimse almıyor" rahatlaması tetiklenmez).
 * FX2-WORKER (L-2): {@link #othersActiveMs} — koşuda BAŞKA işçilerin son etkinliği; eski kurucular
 * {@link #NO_OTHER_ACTIVITY} verir.
 *
 * @param id           parça satırı kimliği
 * @param runId        koşu kimliği (= klasik tur kimliği; işçi sonuç haritalarına {@code run_id} olarak koyar)
 * @param chunkNo      tur içindeki parça numarası
 * @param entries      ölçülecek alan adları (koordinatörün sırası; {@link CertChunkSpec.Entry#index()} turdaki sıra)
 * @param attempts     bu sahiplenme DAHİL kaç kez sahiplenildi (1 = ilk; en çok {@value CertChunking#MAX_ATTEMPTS})
 * @param claimToken   bu sahiplenmenin rastgele belirteci (tamamlama CAS'ı; ≤ 40 karakter)
 * @param claimedMs    sahiplenme anı (deponun saati, ms)
 * @param leaseUntilMs kira bitişi (deponun saati, ms) — işçi {@link CertChunking.Tuning#renewEveryMs()}'de bir yeniler
 * @param coordinator  koşunun koordinatörü ({@code cert_sweep_runs.coordinator}; bilinmiyorsa null)
 * @param createdMs    parçanın yazıldığı an (deponun saati, ms); bekleme = {@code claimedMs − createdMs}
 * @param avoidWorker  ikinci görüş parçasının kaçındığı işçi (ilk görüş parçasında null)
 * @param othersActiveMs aynı koşuda BAŞKA sahiplerin son etkinlik anı (deponun saati, ms; FX2-WORKER L-2, 2026-10-29): başka
 *                     sahibin canlı kirasıyla tuttuğu parça varsa o kiranın bitişi (≥ sahiplenme anı), yoksa başka sahibin son
 *                     sahiplenme anı; hiç yoksa {@link #NO_OTHER_ACTIVITY}. Tarama liderinin "işi kimse almıyor" rahatlaması
 *                     bununla karar verir ({@link #othersQuietMsAtClaim()})
 */
public record ClaimedChunk(long id, String runId, int chunkNo, List<CertChunkSpec.Entry> entries, int attempts,
                           String claimToken, long claimedMs, long leaseUntilMs, String coordinator, long createdMs,
                           String avoidWorker, long othersActiveMs) {

    /**
     * {@link #othersActiveMs}: koşuda başka sahibin parça aldığına / tuttuğuna dair iz yok. Eski kurucular da bunu verir (yalnız
     * test sahteleri) — o zaman rahatlama yalnız bekleme yaşına bakar (FX-WORKER SW-M1 kuralı).
     */
    public static final long NO_OTHER_ACTIVITY = Long.MIN_VALUE;

    public ClaimedChunk {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** Eski kurucu (P0 sözleşmesi): koordinatör / kaçınılan işçi bilinmez, bekleme 0 ({@code createdMs = claimedMs}). */
    public ClaimedChunk(long id, String runId, int chunkNo, List<CertChunkSpec.Entry> entries, int attempts,
                        String claimToken, long claimedMs, long leaseUntilMs) {
        this(id, runId, chunkNo, entries, attempts, claimToken, claimedMs, leaseUntilMs, null, claimedMs, null);
    }

    /** FX-WORKER kurucusu (11 bileşen): başka sahiplerin etkinliği bilinmez ({@link #NO_OTHER_ACTIVITY}). */
    public ClaimedChunk(long id, String runId, int chunkNo, List<CertChunkSpec.Entry> entries, int attempts,
                        String claimToken, long claimedMs, long leaseUntilMs, String coordinator, long createdMs,
                        String avoidWorker) {
        this(id, runId, chunkNo, entries, attempts, claimToken, claimedMs, leaseUntilMs, coordinator, createdMs, avoidWorker,
                NO_OTHER_ACTIVITY);
    }

    /** Parçadaki alan adı sayısı. */
    public int domainCount() {
        return entries.size();
    }

    /** Klasik satırlar — {@link CertChunkRunner#checkDomains}'e AYNEN verilir. */
    public List<Map<String, Object>> rows() {
        return CertChunkSpec.rowsOf(entries);
    }

    /** İkinci görüş parçası mı ({@code avoid_worker} dolu). */
    public boolean secondOpinion() {
        return avoidWorker != null;
    }

    /** Sahiplenme anında ne kadar beklemişti (ms, ≥ 0). */
    public long pendingMsAtClaim() {
        return Math.max(0L, claimedMs - createdMs);
    }

    /**
     * Sahiplenme anında koşuda başka işçilerin ne kadar süredir sessiz olduğu (ms; FX2-WORKER L-2): başka sahip canlı kirayla
     * parça tutuyorsa ≤ 0 (çalışıyor), aksi hâlde son sahiplenmesinden beri geçen süre; hiç iz yoksa {@link Long#MAX_VALUE}.
     */
    public long othersQuietMsAtClaim() {
        if (othersActiveMs == NO_OTHER_ACTIVITY) return Long.MAX_VALUE;
        return claimedMs - othersActiveMs;
    }

    /** Belirteç ve alan adları yazılmaz (günlük satırları kısa kalsın). */
    @Override
    public String toString() {
        return "ClaimedChunk[id=" + id + ", runId=" + runId + ", no=" + chunkNo + ", domains=" + entries.size()
                + ", attempts=" + attempts + "]";
    }
}
