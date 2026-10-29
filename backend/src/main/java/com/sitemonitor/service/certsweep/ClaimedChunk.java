package com.sitemonitor.service.certsweep;

import java.util.List;
import java.util.Map;

/**
 * İşçinin sahiplendiği parça (2026-10-29, çok pod Sürüm 3-4 P0 sözleşmesi, tasarım R3-4 §4.2 "Claim" / §4.4) —
 * {@link CertSweepStore#claim} döner; {@link CertSweepStore#complete} / {@link CertSweepStore#renew} /
 * {@link CertSweepStore#release} {@link #id} + {@link #claimToken} (ve sahip) ile koşullu yazar. Süreler deponun saatiyle
 * ({@link CertSweepStore#nowMs()}, veritabanı-çapalı) ms'dir.
 *
 * @param id           parça satırı kimliği
 * @param runId        koşu kimliği (= klasik tur kimliği; işçi sonuç haritalarına {@code run_id} olarak koyar)
 * @param chunkNo      tur içindeki parça numarası
 * @param entries      ölçülecek alan adları (koordinatörün sırası; {@link CertChunkSpec.Entry#index()} turdaki sıra)
 * @param attempts     bu sahiplenme DAHİL kaç kez sahiplenildi (1 = ilk; en çok {@value CertChunking#MAX_ATTEMPTS})
 * @param claimToken   bu sahiplenmenin rastgele belirteci (tamamlama CAS'ı; ≤ 40 karakter)
 * @param claimedMs    sahiplenme anı (deponun saati, ms)
 * @param leaseUntilMs kira bitişi (deponun saati, ms) — işçi {@link CertChunking.Tuning#renewEveryMs()}'de bir yeniler
 */
public record ClaimedChunk(long id, String runId, int chunkNo, List<CertChunkSpec.Entry> entries, int attempts,
                           String claimToken, long claimedMs, long leaseUntilMs) {

    public ClaimedChunk {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** Parçadaki alan adı sayısı. */
    public int domainCount() {
        return entries.size();
    }

    /** Klasik satırlar — {@link CertChunkRunner#checkDomains}'e AYNEN verilir. */
    public List<Map<String, Object>> rows() {
        return CertChunkSpec.rowsOf(entries);
    }

    /** Belirteç ve alan adları yazılmaz (günlük satırları kısa kalsın). */
    @Override
    public String toString() {
        return "ClaimedChunk[id=" + id + ", runId=" + runId + ", no=" + chunkNo + ", domains=" + entries.size()
                + ", attempts=" + attempts + "]";
    }
}
