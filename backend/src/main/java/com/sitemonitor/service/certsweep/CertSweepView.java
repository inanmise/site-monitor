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
 *       {@code cert_sweep_runs}'ta varsa {@link #MODE_CHUNKED} (ABANDONED + FAILED = klasiğe dönmüş → {@link #MODE_CLASSIC});
 *       yoksa ve okunan koşu satırları o turu kapsıyorsa (saklama penceresi içinde) {@link #MODE_CLASSIC}; kapsamıyorsa (okuma
 *       sınırı / budanmış eski tur) ya da okunamadıysa {@link #MODE_UNKNOWN} — asla tahmin yok.</li>
 *   <li>{@link #coordinatorLive} / {@link #orphanSuspected} — açık koşunun koordinatörü {@code cert-check} kilidini hâlâ tutuyor
 *       mu; tutmuyorsa koşu sahipsizdir (sonraki tur kapatır) — ilerleme / bekleyen uyarısı / parça serisi gösterilmez.</li>
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
            if (r != null && runId.equals(r.runId())) {
                // İnceleme OB-L2 (a): koordinatörün iç hatası koşuyu ABANDONED + FAILED kapatır ve tur KLASİK toplamayla sürer
                // (aynı tur kimliğiyle scan_runs yazılır) → klasik. Diğer her eşleşme (DECIDING / DONE / FAILED, ve sahipsiz
                // kapatılan ama karar veren yavaş koordinatör) parçalı ölçüldü.
                return fellBackToClassic(r) ? MODE_CLASSIC : MODE_CHUNKED;
            }
        }
        Long end = parseIso(lastRunAt);
        long start = end == null ? Long.MIN_VALUE : end - Math.max(0L, durationMs == null ? 0L : durationMs);
        // Okunan satırlar tüm koşular (sınıra ulaşılmadı) → bu tur parçalı olsaydı listede olurdu — ANCAK yalnız koşu satırlarının
        // saklama penceresi içinde başlamışsa (inceleme OB-L2 (b): 7 günden eski kapanmış satırlar budanır; budanmış parçalı bir
        // "full" turu klasik sanılmasın — eski tur bilinmiyor)
        if (runs.size() < Math.max(1, requested)) {
            if (end == null) return MODE_UNKNOWN;
            return start >= status.nowMs() - CertChunking.RUN_RETENTION_MS + CLOCK_SLACK_MS ? MODE_CLASSIC : MODE_UNKNOWN;
        }
        long oldest = Long.MAX_VALUE;
        for (CertSweepStatus.Run r : runs) if (r != null) oldest = Math.min(oldest, r.createdMs());
        if (end == null || oldest == Long.MAX_VALUE) return MODE_UNKNOWN;
        // Tur, okunan en eski koşu satırından AÇIKÇA sonra başladıysa parçalı olsaydı listede olurdu → klasik
        return start >= oldest + CLOCK_SLACK_MS ? MODE_CLASSIC : MODE_UNKNOWN;
    }

    /**
     * Koşu satırı "parçalı başladı ama klasiğe döndü" mü: ABANDONED + {@code OUTCOME_FAILED} — koordinatörün iç hatası, tur
     * klasik toplamayla tamamlandı (inceleme OB-L2 / OB-L3). Bu koşu bir hata DEĞİLDİR (sonuçlar kaydedildi, karar verildi).
     */
    public static boolean fellBackToClassic(CertSweepStatus.Run r) {
        return r != null && CertSweepStore.RUN_ABANDONED.equals(r.state()) && CertSweepStore.OUTCOME_FAILED.equals(r.outcome());
    }

    // ── İnceleme OB-L1: sahipsiz açık koşu (koordinatörü artık cert-check kilidini tutmuyor) ─────────────────────────────

    /** Sahipsiz sayılmak için açık koşunun en az yaşı (ms) — kilit okuması ile koşu okuması arasındaki yarışı örter. */
    public static final long ORPHAN_MIN_AGE_MS = 60_000L;
    /** {@code cert-check} kilit satırı (PK okuması; yalnız anahtar AÇIK ve açık koşu varken). */
    public static final String SQL_CERT_LOCK = "SELECT locked_by, locked_until FROM scheduler_lock WHERE name = ?";
    /** Sertifika taramasının kilit adı (kalıcı). */
    public static final String CERT_LOCK_NAME = "cert-check";

    /**
     * Okuyanın gördüğü {@code cert-check} kilidi.
     *
     * @param known  kilit satırı okundu (var ya da yok) — false = okunamadı (bilinmiyor)
     * @param holder canlı (süresi geçmemiş) satırın sahibi; canlı satır yoksa null
     */
    public record CertLock(boolean known, String holder) {
        /** Okunamadı. */
        public static final CertLock UNKNOWN = new CertLock(false, null);
    }

    /**
     * {@code scheduler_lock} satırından kilit: süresi geçmemiş (ya da biçimi okunamayan — {@code SchedulerService.lockRowLive}
     * kuralı: bilinmeyen serbest sayılmaz) satır canlıdır.
     */
    public static CertLock lockOf(boolean rowExists, String lockedBy, String lockedUntil, long nowMs) {
        if (!rowExists) return new CertLock(true, null);
        Long until = parseIso(lockedUntil);
        boolean live = until == null || until >= nowMs;
        return new CertLock(true, live ? lockedBy : null);
    }

    /**
     * {@code cert-check} kilit satırını okur (tek PK okuması). Asla fırlatmaz — okunamadı → {@link CertLock#UNKNOWN}.
     * Çağıran YALNIZ anahtar açıkken ve açık koşu varken çağırır (L-1).
     */
    public static CertLock readCertLock(org.springframework.jdbc.core.JdbcTemplate jdbc, long nowMs) {
        if (jdbc == null) return CertLock.UNKNOWN;
        try {
            List<String[]> rows = jdbc.query(SQL_CERT_LOCK, (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, CERT_LOCK_NAME);
            if (rows == null) return CertLock.UNKNOWN;
            if (rows.isEmpty()) return lockOf(false, null, null, nowMs);
            String[] r = rows.get(0);
            return lockOf(true, r[0], r[1], nowMs);
        } catch (RuntimeException e) {
            return CertLock.UNKNOWN;
        }
    }

    /**
     * Açık koşunun koordinatörü canlı mı (kilide göre): TRUE = {@code cert-check}'in canlı sahibi koordinatör (ya da bu pod koşuyu
     * ŞU AN yürütüyor — {@code selfRunId}); FALSE = kilidi başka pod tutuyor ya da canlı kilit yok (koordinatör her turda kilidi
     * tur boyunca tutar, bıraktığında koşuyu kapatmıştır); null = kilit okunamadı / koşu yok (karar verilmez).
     */
    public static Boolean coordinatorLive(CertSweepStatus.Run run, CertLock lock, String selfRunId) {
        if (run == null) return null;
        if (selfRunId != null && selfRunId.equals(run.runId())) return Boolean.TRUE;
        if (lock == null || !lock.known()) return null;
        if (lock.holder() == null) return Boolean.FALSE;
        return lock.holder().equals(run.coordinator());
    }

    /**
     * Açık koşu sahipsiz mi (gösterim): koordinatör kilidi tutmuyor ({@link #coordinatorLive} FALSE) VE koşu en az
     * {@link #ORPHAN_MIN_AGE_MS} yaşında. Sahipsiz koşu ilerleme / bekleyen uyarısı / parça serisi olarak gösterilmez; sonraki
     * tur (anahtar açıkken her tur başında) onu ORPHANED kapatır. Bilinmeyen kilit → false (bugünkü gösterim).
     */
    public static boolean orphanSuspected(CertSweepStatus.Run run, long nowMs, Boolean coordinatorLive) {
        return run != null && run.open() && Boolean.FALSE.equals(coordinatorLive) && run.ageMs(nowMs) >= ORPHAN_MIN_AGE_MS;
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
