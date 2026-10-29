package com.sitemonitor.service.certsweep;

import com.sitemonitor.service.health.ClusterHealthStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Yazılacak parçalı koşu (2026-10-29, çok pod Sürüm 3-4 P0 sözleşmesi, tasarım R3-4 §4.1 / §4.5 adım 2) — koordinatör (B)
 * kurar, depo (A) {@link CertSweepStore#createRun} ile {@code cert_sweep_runs} + {@code cert_sweep_chunks} satırlarına yazar.
 *
 * <p><b>Kurucu sözleşmesi (L-4 "kayıp alan adı yok"):</b> parçaların girdileri {@code 0…domainsTotal−1} sıra numaralarının
 * HER BİRİNİ TAM BİR KEZ taşır; parça numaraları tekildir ve {@code 0…chunks.size()−1}'dir. Aksi
 * {@code IllegalArgumentException} — koordinatör o turu klasik koşturur.
 *
 * @param runId        klasik tur kimliği ({@code runCheckForDomains}'in 12 haneli onaltılığı; {@code certificate_checks.run_id})
 * @param kind         {@code hourly} / {@code stale} / {@code full} ({@link ClusterHealthStore#KIND_HOURLY} …)
 * @param coordinator  koordinatörün örnek kimliği ({@code SchedulerService.instanceId()}; ≤ 200 karakter)
 * @param evictCaches  tur sonunda önbellek boşaltılacak mı (saatlik / tam: true; bayat: false) — yalnız bilgi
 * @param domainsTotal turdaki alan adı sayısı (= klasik vade listesinin uzunluğu)
 * @param chunkSize    bu turun parça boyutu ({@link CertChunking.Tuning#effectiveChunkSize})
 * @param chunks       parçalar (en az bir)
 */
public record CertRunSpec(String runId, String kind, String coordinator, boolean evictCaches, int domainsTotal,
                          int chunkSize, List<CertChunkSpec> chunks) {

    /** {@code run_id} sütun sınırı. */
    public static final int MAX_RUN_ID_CHARS = 40;
    /** {@code coordinator} sütun sınırı. */
    public static final int MAX_COORDINATOR_CHARS = 200;
    /** Geçerli tür adları (kalıcı — {@code scan_runs.kind} ile aynı). */
    public static final Set<String> KINDS =
            Set.of(ClusterHealthStore.KIND_HOURLY, ClusterHealthStore.KIND_STALE, ClusterHealthStore.KIND_FULL);

    public CertRunSpec {
        if (runId == null || runId.isBlank() || runId.length() > MAX_RUN_ID_CHARS) {
            throw new IllegalArgumentException("runId geçersiz");
        }
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("kind geçersiz: " + kind);
        if (coordinator == null || coordinator.isBlank() || coordinator.length() > MAX_COORDINATOR_CHARS) {
            throw new IllegalArgumentException("coordinator geçersiz");
        }
        if (domainsTotal <= 0) throw new IllegalArgumentException("domainsTotal ≤ 0");
        if (chunkSize <= 0) throw new IllegalArgumentException("chunkSize ≤ 0");
        if (chunks == null || chunks.isEmpty()) throw new IllegalArgumentException("parça yok");
        if (chunks.size() > CertChunking.MAX_CHUNKS_PER_RUN) {
            throw new IllegalArgumentException("parça sayısı > " + CertChunking.MAX_CHUNKS_PER_RUN);
        }
        chunks = List.copyOf(chunks);
        boolean[] seen = new boolean[domainsTotal];
        int covered = 0;
        Set<Integer> numbers = new HashSet<>();
        for (CertChunkSpec c : chunks) {
            if (c.avoidWorker() != null) throw new IllegalArgumentException("ilk parçada avoidWorker olmaz");
            if (c.chunkNo() >= chunks.size() || !numbers.add(c.chunkNo())) {
                throw new IllegalArgumentException("parça numarası geçersiz / yinelenmiş: " + c.chunkNo());
            }
            for (CertChunkSpec.Entry e : c.entries()) {
                if (e.index() >= domainsTotal || seen[e.index()]) {
                    throw new IllegalArgumentException("sıra numarası geçersiz / yinelenmiş: " + e.index());
                }
                seen[e.index()] = true;
                covered++;
            }
        }
        if (covered != domainsTotal) throw new IllegalArgumentException("eksik alan adı: " + covered + " / " + domainsTotal);
    }

    /** Parça sayısı ({@code chunks_total}). */
    public int chunksTotal() {
        return chunks.size();
    }

    @Override
    public String toString() {
        return "CertRunSpec[runId=" + runId + ", kind=" + kind + ", domains=" + domainsTotal + ", chunks=" + chunks.size()
                + ", size=" + chunkSize + "]";
    }
}
