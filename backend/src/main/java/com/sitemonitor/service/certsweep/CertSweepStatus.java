package com.sitemonitor.service.certsweep;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parçalı sertifika taramasının KÜME durumu (2026-10-29, çok pod Sürüm 3-4 P0 sözleşmesi, tasarım R3-4 §7.1) —
 * {@link CertSweepStore#status} döner (iki deyim: son koşular + açık koşunun gruplu parça sayıları); Sistem Sağlığı / Küme
 * kartı / ölçümler (paket C) okur. Yalnız anahtar AÇIKKEN okunur (kapalı: {@code data.cert_chunks = {enabled: false}},
 * sorgu yok). İç içe kayıtlar: {@link Run} (koşu satırı), {@link ChunkGroup} (gruplu parça satırı), {@link WorkerTally}
 * ({@code workers_json} öğesi), {@link Worker} (bu pod'un işçi sayaçları, bellek), {@link Coordinator} (bu pod'un koordinatör
 * sayaçları, bellek).
 *
 * @param nowMs         okuma anı (deponun saati, ms)
 * @param dbClockStale  deponun saati pod saatine düşmüş mü ({@code DbClock.stale()})
 * @param lastRuns      en yeni koşular ({@code created_ms} azalan, en çok istenen sayı)
 * @param openRun       açık (RUNNING / DECIDING) en yeni koşu; yoksa null
 * @param openRunChunks açık koşunun parçaları {@code (state, worker)} grubunda; açık koşu yoksa boş
 */
public record CertSweepStatus(long nowMs, boolean dbClockStale, List<Run> lastRuns, Run openRun,
                              List<ChunkGroup> openRunChunks) {

    public CertSweepStatus {
        lastRuns = lastRuns == null ? List.of() : List.copyOf(lastRuns);
        openRunChunks = openRunChunks == null ? List.of() : List.copyOf(openRunChunks);
    }

    /** Açık koşunun bu durumdaki parça sayısı. */
    public long chunks(String state) {
        long n = 0;
        for (ChunkGroup g : openRunChunks) if (g.state().equals(state)) n += g.chunks();
        return n;
    }

    /** Açık koşunun bu durumdaki alan adı sayısı. */
    public long domains(String state) {
        long n = 0;
        for (ChunkGroup g : openRunChunks) if (g.state().equals(state)) n += g.domains();
        return n;
    }

    /** Açık koşuda ölçülmüş (DONE parçalardaki) alan adı sayısı. */
    public long domainsMeasured() {
        return domains(CertSweepStore.CHUNK_DONE);
    }

    /** Açık koşunun en eski BEKLEYEN parçasının yaşı (ms); bekleyen yoksa null. */
    public Long oldestPendingAgeMs() {
        Long min = null;
        for (ChunkGroup g : openRunChunks) {
            if (!g.state().equals(CertSweepStore.CHUNK_PENDING) || g.minCreatedMs() == null) continue;
            min = min == null ? g.minCreatedMs() : Math.min(min, g.minCreatedMs());
        }
        return min == null ? null : Math.max(0L, nowMs - min);
    }

    /**
     * Açık koşunun pod başına sayımı (DONE + CLAIMED gruplar, işçisi olanlar): parça, alan adı, ağ hatası (yalnız DONE
     * parçaların sayımı). Alan adı sayısına göre azalan, sonra kimlik.
     */
    public List<WorkerTally> perWorker() {
        Map<String, long[]> acc = new LinkedHashMap<>();
        for (ChunkGroup g : openRunChunks) {
            if (g.worker() == null) continue;
            boolean done = g.state().equals(CertSweepStore.CHUNK_DONE);
            if (!done && !g.state().equals(CertSweepStore.CHUNK_CLAIMED)) continue;
            long[] a = acc.computeIfAbsent(g.worker(), k -> new long[3]);
            a[0] += g.chunks();
            a[1] += g.domains();
            if (done) a[2] += g.networkErrors();
        }
        List<WorkerTally> out = new ArrayList<>(acc.size());
        acc.forEach((w, a) -> out.add(new WorkerTally(w, sat(a[0]), sat(a[1]), sat(a[2]))));
        out.sort(WorkerTally.ORDER);
        return out;
    }

    private static int sat(long v) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, v));
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * {@code cert_sweep_runs} satırı.
     *
     * @param runId          koşu kimliği (= klasik tur kimliği)
     * @param kind           hourly / stale / full
     * @param coordinator    koordinatörün örnek kimliği
     * @param state          {@link CertSweepStore#RUN_RUNNING} / DECIDING / DONE / FAILED / ABANDONED
     * @param evictCaches    önbellek boşaltan tur mu
     * @param domainsTotal   alan adı sayısı
     * @param chunksTotal    ilk parça sayısı (ikinci görüş parçaları hariç)
     * @param chunkSize      parça boyutu
     * @param createdMs      yazılma anı
     * @param heartbeatMs    koordinatörün son nabzı
     * @param finishedMs     kapanış anı (açıkken null)
     * @param outcome        {@link CertSweepStore#OUTCOME_DECIDED} … (açıkken null)
     * @param takeovers      koordinatörün kendisi ölçtüğü parça sayısı (DECIDING'de yazılır; önce null)
     * @param reclaims       yeniden sahiplenilen parça sayısı (DECIDING'de; önce null)
     * @param secondOpinions ikinci görüşle yeniden ölçülen alan adı sayısı (DECIDING'de; önce null)
     * @param workersJson    {@link CertSweepStatus#encodeWorkers} biçimi (DECIDING'de; önce null)
     * @param secondOpinionOk     ikinci görüşte yeniden ölçümü kullanılan alan adlarından başka pod'da BAŞARILI olanlar
     *                            (inceleme L-4; DECIDING'de; önce / eski satırda null)
     * @param secondOpinionFailed ikinci görüşte yeniden ölçümü kullanılan alan adlarından YİNE HATALI olanlar (L-4; null aynı)
     */
    public record Run(String runId, String kind, String coordinator, String state, boolean evictCaches, int domainsTotal,
                      int chunksTotal, int chunkSize, long createdMs, long heartbeatMs, Long finishedMs, String outcome,
                      Integer takeovers, Integer reclaims, Integer secondOpinions, String workersJson,
                      Integer secondOpinionOk, Integer secondOpinionFailed) {

        /** İkinci görüş sonucu bilinmeyen satır (eski kurucu; sonuç sütunları null). */
        public Run(String runId, String kind, String coordinator, String state, boolean evictCaches, int domainsTotal,
                   int chunksTotal, int chunkSize, long createdMs, long heartbeatMs, Long finishedMs, String outcome,
                   Integer takeovers, Integer reclaims, Integer secondOpinions, String workersJson) {
            this(runId, kind, coordinator, state, evictCaches, domainsTotal, chunksTotal, chunkSize, createdMs, heartbeatMs,
                    finishedMs, outcome, takeovers, reclaims, secondOpinions, workersJson, null, null);
        }

        /** RUNNING ya da DECIDING. */
        public boolean open() {
            return CertSweepStore.RUN_RUNNING.equals(state) || CertSweepStore.RUN_DECIDING.equals(state);
        }

        /** Süre (ms) — kapanmışsa {@code finished − created}, açıksa null. */
        public Long durationMs() {
            return finishedMs == null ? null : Math.max(0L, finishedMs - createdMs);
        }

        /** Yazılmadan bu yana geçen süre (ms, ≥ 0). */
        public long ageMs(long nowMs) {
            return Math.max(0L, nowMs - createdMs);
        }

        /** {@link #workersJson} çözümü (yoksa / bozuksa boş). */
        public List<WorkerTally> workers() {
            return decodeWorkers(workersJson);
        }
    }

    /**
     * Gruplu parça satırı: {@code SELECT state, COALESCE(worker, claimed_by), COUNT(*), SUM(domain_count),
     * SUM(network_errors), MIN(created_ms) … WHERE run_id = ? GROUP BY …}.
     *
     * @param state         parça durumu
     * @param worker        tamamlayan (DONE) ya da sahiplenen (CLAIMED) örnek kimliği; bekleyen / sahipsiz grupta null
     * @param chunks        parça sayısı
     * @param domains       alan adı sayısı
     * @param networkErrors ağ hatası toplamı (yalnız DONE; diğerlerinde 0)
     * @param minCreatedMs  en eski parçanın yazılma anı
     */
    public record ChunkGroup(String state, String worker, long chunks, long domains, long networkErrors, Long minCreatedMs) {}

    /**
     * Bir işçinin koşudaki payı ({@code workers_json} öğesi; Küme kartının pod tablosu).
     *
     * @param worker        örnek kimliği ({@code ClusterHealthStore.podOf} pod adını verir)
     * @param chunks        parça sayısı
     * @param domains       alan adı sayısı
     * @param networkErrors ağ hatası sayısı
     */
    public record WorkerTally(String worker, int chunks, int domains, int networkErrors) {

        /** Alan adına göre azalan, sonra kimlik. */
        public static final Comparator<WorkerTally> ORDER = Comparator.comparingInt(WorkerTally::domains).reversed()
                .thenComparing(WorkerTally::worker, Comparator.nullsLast(Comparator.naturalOrder()));

        /** Ağ hatası oranı (0–1; alan adı yoksa 0). */
        public double networkErrorRate() {
            return domains <= 0 ? 0.0 : (double) networkErrors / domains;
        }
    }

    /**
     * Bu pod'un işçi sayaçları (bellek; işçi {@link CertChunking#registerWorkerStatus} ile sağlar). Toplamlar pod açıldığından
     * beri birikir.
     *
     * @param workerEnabled   {@link CertChunking.Tuning#workerEnabled()} (false = bu pod parça almaz)
     * @param started         yoklama iş parçacığı başladı mı (anahtar bir kez açık görüldü)
     * @param inflightChunks  şu an ölçülen parça sayısı
     * @param inflightDomains şu an ölçülen alan adı sayısı
     * @param budget          şu anki alan adı bütçesi (lider payı uygulanmış)
     * @param leaderYielding  lider payı uygulanıyor mu (tarama lideri + başka canlı pod)
     * @param claimed         sahiplenilen parça
     * @param completed       tamamlanan parça
     * @param late            geç tamamlanan (0 satır — kira kaybedildi / koşu kapandı) parça
     * @param released        kapanışta bırakılan parça
     * @param discarded       kapanış yüzünden atılan sonuçlu parça
     * @param domainsMeasured ölçülen alan adı
     * @param lastClaimAtMs   son başarılı sahiplenme anı (deponun saati; hiç yoksa null)
     * @param dbClockStale    deponun saati pod saatine düşmüş mü
     */
    public record Worker(boolean workerEnabled, boolean started, int inflightChunks, int inflightDomains, int budget,
                         boolean leaderYielding, long claimed, long completed, long late, long released, long discarded,
                         long domainsMeasured, Long lastClaimAtMs, boolean dbClockStale) {}

    /**
     * Bu pod'un koordinatör sayaçları (bellek; koordinatör {@link CertChunking#registerCoordinatorStatus} ile sağlar). Toplamlar
     * pod açıldığından beri birikir; ölçüm {@code sitemonitor_cert_sweep_worker_chunks{outcome="takeover|second_opinion"}}.
     * Sağlık yükünde {@code classic_by_rule} / {@code classic_fallback} (inceleme OB-L6, 2026-10-29: önceden tek sayaçtı ve
     * sağlıklı kümede her küçük bayat turu "klasiğe dönen" diye sayıyordu).
     *
     * @param chunkedRuns     parçalı koşulan tur
     * @param classicFallback anahtar açıkken parçalı OLACAKKEN klasiğe DÜŞEN tur (koşu yazılamadı / öksüzler kapatılamadı,
     *                        koordinatörün iç hatası) — gerçek bir arıza
     * @param takeovers       koordinatörün kendisi ölçtüğü parça (kayıp parça / ilerlemesiz bekleme / depo hatası)
     * @param remeasured      kullanılamaz sonuç yüzünden kendisi yeniden ölçtüğü alan adı
     * @param secondOpinions  ikinci görüşle yeniden ölçülen alan adı
     * @param abandoned       ABANDONED biten koşu (kapanış / kilit kaybı)
     * @param classicByRule   anahtar açıkken KURAL GEREĞİ klasik koşan tur (başka canlı pod yok, alan adı {@code min-domains}
     *                        altında, depo / tablolar / ölçücü yok, kapanış) — arıza değil
     */
    public record Coordinator(long chunkedRuns, long classicFallback, long takeovers, long remeasured, long secondOpinions,
                              long abandoned, long classicByRule) {

        /** Eski biçim (OB-L6 öncesi): kural gereği klasik turlar ayrı sayılmıyordu → {@code classicByRule = 0}. */
        public Coordinator(long chunkedRuns, long classicFallback, long takeovers, long remeasured, long secondOpinions,
                           long abandoned) {
            this(chunkedRuns, classicFallback, takeovers, remeasured, secondOpinions, abandoned, 0L);
        }
    }

    // ── workers_json kodlaması (B yazar, C okur) ────────────────────────────────────────────────────────────────────

    /** {@code workers_json} sütun sınırı. */
    public static final int MAX_WORKERS_JSON_CHARS = 4_000;

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() { };

    /**
     * {@code {"<örnek kimliği>":[parça, alan adı, ağ hatası], …}} — {@link WorkerTally#ORDER} sırasıyla; null / boş
     * kimlikli öğe atlanır; {@value #MAX_WORKERS_JSON_CHARS} karakteri aşarsa EN AZ alan adlı öğeler düşer (sığan önek).
     * Asla fırlatmaz.
     */
    public static String encodeWorkers(Collection<WorkerTally> tallies) {
        List<WorkerTally> sorted = new ArrayList<>();
        if (tallies != null) for (WorkerTally t : tallies) if (t != null && t.worker() != null && !t.worker().isBlank()) sorted.add(t);
        sorted.sort(WorkerTally.ORDER);
        while (true) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (WorkerTally t : sorted) m.putIfAbsent(t.worker(), List.of(t.chunks(), t.domains(), t.networkErrors()));
            String json;
            try {
                json = JSON.writeValueAsString(m);
            } catch (RuntimeException e) {
                return "{}";
            }
            if (json.length() <= MAX_WORKERS_JSON_CHARS || sorted.isEmpty()) return json;
            sorted.remove(sorted.size() - 1);
        }
    }

    /** {@link #encodeWorkers} çözümü ({@link WorkerTally#ORDER} sırasıyla); null / bozuk → boş; bozuk öğe atlanır. */
    public static List<WorkerTally> decodeWorkers(String json) {
        List<WorkerTally> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        Map<String, Object> m;
        try {
            m = JSON.readValue(json, MAP_TYPE);
        } catch (RuntimeException e) {
            return out;
        }
        if (m == null) return out;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!(e.getValue() instanceof List<?> l) || l.size() < 3) continue;
            if (!(l.get(0) instanceof Number c) || !(l.get(1) instanceof Number d) || !(l.get(2) instanceof Number n)) continue;
            out.add(new WorkerTally(e.getKey(), sat(c.longValue()), sat(d.longValue()), sat(n.longValue())));
        }
        out.sort(WorkerTally.ORDER);
        return out;
    }
}
