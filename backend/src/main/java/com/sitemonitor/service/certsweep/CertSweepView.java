package com.sitemonitor.service.certsweep;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parçalı sertifika taramasının GÖZLEM kuralları (2026-10-29, çok pod Sürüm 3-4, paket C1; tasarım R3-4 §2.3 / §2.6 / §7) —
 * saf, durumsuz; Sistem Sağlığı ({@code controller/CertChunksHealth}), ölçüm aktarıcısı ({@code MonitoringMetricsExporter}) ve
 * kendi kendini izleme ({@code WatchdogProbes.certSweep}) AYNI kuralı kullanır. Hiçbiri karar vermez: koşu kararı, ikinci görüş
 * ve alarm işleme koordinatörün (paket B) işidir; burada yalnız gösterilecek / yayılacak değerler türetilir.
 *
 * <ul>
 *   <li>{@link #modeOf} — bir {@code scan_runs} satırının (tür başına son tur) parçalı mı klasik mi koştuğu: koşu kimliği
 *       {@code cert_sweep_runs}'ta varsa {@link #MODE_CHUNKED}; yoksa ve okunan koşu satırları o turu kapsıyorsa
 *       {@link #MODE_CLASSIC}; kapsamıyorsa (okuma sınırı) ya da okunamadıysa {@link #MODE_UNKNOWN} — asla tahmin yok.</li>
 *   <li>{@link #doneTallies} / {@link #outliers} — açık koşunun pod başına ÖLÇÜLMÜŞ (DONE) payı ve tasarım §2.6'daki aykırı pod
 *       kuralının GÖSTERİM kopyası (ağ hatası oranı ≥ eşik, diğerleri ≤ eşik / 2 ve yeterince örnek): Küme kartının uyarısı.</li>
 *   <li>{@link #abandonedSince} — son koşulardan pencere içindeki terk edilmiş (ABANDONED) koşu sayısı (kendi kendini izleme
 *       olgusu; karar DEĞİŞMEZ).</li>
 * </ul>
 */
public final class CertSweepView {

    private CertSweepView() {}

    /** Tur parçalı koştu ({@code cert_sweep_runs}'ta koşu satırı var). */
    public static final String MODE_CHUNKED = "chunked";
    /** Tur klasik koştu (okunan koşu satırları turu kapsıyor ve kimliği aralarında yok). */
    public static final String MODE_CLASSIC = "classic";
    /** Karar verilemedi (koşu tablosu okunamadı / okuma sınırının dışında / tur kimliği yok). */
    public static final String MODE_UNKNOWN = "unknown";

    /** Pod saati ile veritabanı saati arasındaki kayma payı (ms) — kapsama kararı bunun dışında verilir. */
    static final long CLOCK_SLACK_MS = 60_000L;

    /** {@code scan_runs} zaman biçimi (UTC {@code yyyy-MM-ddTHH:mm:ss}) → epoch ms; çevrilemezse null. */
    public static Long parseIso(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            String s = iso.length() > 19 ? iso.substring(0, 19) : iso;
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Bir tarama turunun kipi.
     *
     * @param runId      {@code scan_runs.run_id} (null / boş → bilinmiyor)
     * @param lastRunAt  turun bitişi (UTC ISO) — kapsama kararı için
     * @param durationMs turun süresi (null = 0)
     * @param status     {@link CertSweepStore#status} sonucu (null = okunamadı → bilinmiyor)
     * @param requested  {@code status} çağrısında istenen en çok koşu sayısı
     */
    public static String modeOf(String runId, String lastRunAt, Long durationMs, CertSweepStatus status, int requested) {
        if (runId == null || runId.isBlank() || status == null) return MODE_UNKNOWN;
        List<CertSweepStatus.Run> runs = status.lastRuns();
        for (CertSweepStatus.Run r : runs) {
            if (r != null && runId.equals(r.runId())) return MODE_CHUNKED;
        }
        // Okunan satırlar tüm koşular (sınıra ulaşılmadı) → bu tur parçalı olsaydı listede olurdu
        if (runs.size() < Math.max(1, requested)) return MODE_CLASSIC;
        long oldest = Long.MAX_VALUE;
        for (CertSweepStatus.Run r : runs) if (r != null) oldest = Math.min(oldest, r.createdMs());
        Long end = parseIso(lastRunAt);
        if (end == null || oldest == Long.MAX_VALUE) return MODE_UNKNOWN;
        long start = end - Math.max(0L, durationMs == null ? 0L : durationMs);
        // Tur, okunan en eski koşu satırından AÇIKÇA sonra başladıysa parçalı olsaydı listede olurdu → klasik
        return start >= oldest + CLOCK_SLACK_MS ? MODE_CLASSIC : MODE_UNKNOWN;
    }

    /**
     * Açık koşunun pod başına ÖLÇÜLMÜŞ payı: yalnız DONE gruplar (parça, alan adı, ağ hatası) — uçuştaki (CLAIMED) alan adları
     * oranı seyreltmesin. Sıra {@link CertSweepStatus.WorkerTally#ORDER}.
     */
    public static List<CertSweepStatus.WorkerTally> doneTallies(CertSweepStatus status) {
        Map<String, long[]> acc = new LinkedHashMap<>();
        if (status != null) {
            for (CertSweepStatus.ChunkGroup g : status.openRunChunks()) {
                if (g == null || g.worker() == null || !CertSweepStore.CHUNK_DONE.equals(g.state())) continue;
                long[] a = acc.computeIfAbsent(g.worker(), k -> new long[3]);
                a[0] += g.chunks();
                a[1] += g.domains();
                a[2] += g.networkErrors();
            }
        }
        List<CertSweepStatus.WorkerTally> out = new ArrayList<>(acc.size());
        acc.forEach((w, a) -> out.add(new CertSweepStatus.WorkerTally(w, sat(a[0]), sat(a[1]), sat(a[2]))));
        out.sort(CertSweepStatus.WorkerTally.ORDER);
        return out;
    }

    /**
     * Tasarım §2.6 aykırı pod kuralı (GÖSTERİM): w aykırı ⇔ {@code ne_w ≥ minErrors} ∧ {@code ne_w / n_w ≥ threshold} ∧ diğerlerinin
     * toplam oranı ≤ {@code threshold / 2} ∧ diğerlerinin örneği ≥ {@code 2 × minErrors}. Koordinatörün kararı bundan bağımsızdır.
     *
     * @return aykırı işçi kimlikleri (girdinin sırasıyla)
     */
    public static List<String> outliers(List<CertSweepStatus.WorkerTally> tallies, int minErrors, double threshold) {
        List<String> out = new ArrayList<>();
        if (tallies == null || tallies.size() < 2) return out;
        long allN = 0, allNe = 0;
        for (CertSweepStatus.WorkerTally t : tallies) {
            if (t == null) continue;
            allN += Math.max(0, t.domains());
            allNe += Math.max(0, t.networkErrors());
        }
        int min = Math.max(1, minErrors);
        for (CertSweepStatus.WorkerTally w : tallies) {
            if (w == null || w.worker() == null || w.domains() <= 0) continue;
            long ne = Math.max(0, w.networkErrors());
            if (ne < min || (double) ne / w.domains() < threshold) continue;
            long otherN = allN - w.domains();
            long otherNe = allNe - ne;
            if (otherN < 2L * min) continue;
            if ((double) otherNe / otherN > threshold / 2.0) continue;
            out.add(w.worker());
        }
        return out;
    }

    /**
     * Son koşulardan {@code sinceMs}'ten sonra yazılmış ve ABANDONED kapanmış olanların sayısı.
     *
     * @return {@code [sayı, kapsandı mı (1/0)]} — kapsanmadı = okunan koşu sınırı dolu ve en eskisi pencerenin içinde (sayı alt sınırdır)
     */
    public static long[] abandonedSince(CertSweepStatus status, long sinceMs, int requested) {
        if (status == null) return new long[]{0, 0};
        long n = 0;
        long oldest = Long.MAX_VALUE;
        for (CertSweepStatus.Run r : status.lastRuns()) {
            if (r == null) continue;
            oldest = Math.min(oldest, r.createdMs());
            if (r.createdMs() >= sinceMs && CertSweepStore.RUN_ABANDONED.equals(r.state())) n++;
        }
        boolean covered = status.lastRuns().size() < Math.max(1, requested) || oldest < sinceMs;
        return new long[]{n, covered ? 1 : 0};
    }

    private static int sat(long v) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, v));
    }
}
