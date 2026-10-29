package com.sitemonitor.service.certsweep;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parçalı sertifika taraması tabloları {@code cert_sweep_runs} + {@code cert_sweep_chunks} (2026-10-29, çok pod Sürüm 3-4 P0
 * sözleşmesi, tasarım R3-4 §4.1 / §4.2; paket A uygular: {@code JdbcCertSweepStore}). Ham DDL, varlık yok, adlar KALICI.
 *
 * <p><b>Yaşam döngüleri.</b> Koşu: RUNNING (koordinatör yazdı) → DECIDING (toplama bitti, karar başlıyor) → DONE / FAILED;
 * kapanış / kilit kaybı / depo hatası / öksüz → ABANDONED. Parça: PENDING → CLAIMED (işçi) → DONE (sonuç yazıldı) ya da
 * CANCELLED (koordinatör devraldı); kapanışta bırakılan CLAIMED → PENDING. Koşu kapanınca parçaları SİLİNİR.
 *
 * <p><b>Zaman.</b> Tüm ms değerleri {@link #nowMs()} saatiyle (3-3'ün veritabanı-çapalı {@code DbClock}'u; kiralar pod
 * saatlerine dayanmaz). Çağıranlar zaman vermez; koordinatör / işçi yaş hesabı için {@link #nowMs()} okur.
 *
 * <p><b>İşlem.</b> Her çağrı kendi kendiliğinden işlemiyle (sınıf düzeyinde {@code @Transactional(propagation =
 * NOT_SUPPORTED)}, 3-3 {@code JdbcCheckJobStore} deseni — çağıranın işlemine katılmaz, onu zehirlemez), kendi
 * {@code JdbcTemplate}'iyle, {@value #QUERY_TIMEOUT_SECONDS} sn deyim zaman aşımıyla (kapanış yazımı
 * {@value #SHUTDOWN_QUERY_TIMEOUT_SECONDS} sn).
 *
 * <p><b>Hatalar.</b> Veritabanı hatası {@code org.springframework.dao.DataAccessException} olarak FIRLAR — aksi belirtilmedikçe.
 * Tablolar yokken (ilk açılışın ilk saniyeleri): {@link #tablesReady()} false; {@link #claim} boş, {@link #renew} /
 * {@link #release} 0, {@link #complete} false döner (gürültü yok); {@link #createRun} / {@link #status} fırlatır (koordinatör
 * klasiğe döner, Küme kartı "okunamadı" der). {@code results_json} hiçbir yöntemde günlüğe yazılmaz.
 *
 * <p><b>Kapı (L-1).</b> Bu arayüzün HİÇBİR yöntemi anahtar açılıştan beri kapalıyken çağrılmaz: koordinatör ve işçi önce
 * {@link CertChunking#enabled()} (bellek) okur; işçi ancak {@link CertChunking#whenActivated} ile başlar.
 */
public interface CertSweepStore {

    // ── Koşu durumları (kalıcı) ─────────────────────────────────────────────────────────────────────────────────────
    String RUN_RUNNING = "RUNNING";
    String RUN_DECIDING = "DECIDING";
    String RUN_DONE = "DONE";
    String RUN_FAILED = "FAILED";
    String RUN_ABANDONED = "ABANDONED";

    // ── Parça durumları (kalıcı) ────────────────────────────────────────────────────────────────────────────────────
    String CHUNK_PENDING = "PENDING";
    String CHUNK_CLAIMED = "CLAIMED";
    String CHUNK_DONE = "DONE";
    String CHUNK_CANCELLED = "CANCELLED";

    // ── Koşu sonuçları ({@code outcome}, kalıcı) ────────────────────────────────────────────────────────────────────
    /** Toplandı, karar verildi (DONE). */
    String OUTCOME_DECIDED = "DECIDED";
    /** Karar aşamasında istisna (FAILED — klasik {@code catch}). */
    String OUTCOME_FAILED = "FAILED";
    /** Kapanış başladı, sonuçlar işlenmedi (ABANDONED). */
    String OUTCOME_SHUTDOWN = "SHUTDOWN";
    /** {@code cert-check} kilidi kaybedildi ya da nabız 0 satır buldu (ABANDONED). */
    String OUTCOME_LOCK_LOST = "LOCK_LOST";
    /** Sonraki koordinatör kapattı — sahibi öldü (ABANDONED). */
    String OUTCOME_ORPHANED = "ORPHANED";
    /**
     * Depo {@value CertChunking#STORE_FAILURE_LOCAL_MS} ms boyunca okunamadı; koordinatör toplanmamış parçaları KENDİSİ ölçtü
     * ve tur klasik gibi karara gitti (§2.5) — durum yazılabildiyse DONE ile bu sonuç (koşunun yazımı da düşerse satır öksüz
     * kalır, sonraki koordinatör ORPHANED kapatır).
     */
    String OUTCOME_STORE_FAILED = "STORE_FAILED";

    /** Deyim zaman aşımı (sn). */
    int QUERY_TIMEOUT_SECONDS = 10;
    /** Kapanış yazımının zaman aşımı (sn) — kapanış bütçesi içinde kalır (§6.6). */
    int SHUTDOWN_QUERY_TIMEOUT_SECONDS = 3;
    /** {@link #chunkStates} en çok bu kadar satır okur ({@value CertChunking#MAX_CHUNKS_PER_RUN} ilk + ikinci görüş). */
    int MAX_CHUNK_ROWS = 1_000;

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Ortak
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Deponun saati (epoch ms, veritabanı-çapalı) — bellek; tüm ms sütunları bununla yazılır. Asla fırlatmaz. */
    long nowMs();

    /** Saat şu an pod saatine mi düşmüş ({@code DbClock.stale()}) — bellek. */
    boolean clockStale();

    /**
     * Tablolar var mı (bellek — şema yaması / son varlık denetimi; sorgu YOK). false iken koordinatör turu klasik koşar ve
     * günlük yazmaz; işçi sahiplenmeyi denemez.
     */
    boolean tablesReady();

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Koordinatör (yalnız {@code cert-check} kilidini tutan pod; L-2)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Öksüzleri kapatır (§2.4 / §4.2 "Settle") — çağıran {@code cert-check}'i tuttuğu için açık (RUNNING / DECIDING) HER koşu
     * öksüzdür: {@code state = ABANDONED, outcome = ORPHANED, finished_ms = şimdi}. Sonra kapalı koşuların ve koşu satırı
     * olmayan parçaların satırlarını siler. Kendi koşusunu YAZMADAN önce çağrılır.
     *
     * @return kapatılan koşular (kapatılmadan önceki hâlleriyle; koordinatör her biri için bir WARN yazar)
     */
    List<CertSweepStatus.Run> settleOrphans();

    /**
     * Budama (§4.1): {@code created_ms < olderThanMs} olan KAPALI koşu satırlarını siler, en çok {@code limit}.
     *
     * @return silinen satır sayısı
     */
    int pruneRuns(long olderThanMs, int limit);

    /**
     * Koşuyu ve parçalarını yazar (çok satırlı INSERT, 200'lük parçalar). ÖNERİ: önce parçalar, EN SON koşu satırı — parça
     * yalnız koşusu RUNNING iken sahiplenilebildiği için yarım yazım işçilere hiç görünmez. Hata olursa yazılan satırlar
     * silinmeye çalışılır (en iyi çaba) ve istisna FIRLAR (koordinatör WARN ≤ 1 / dk + klasik toplama). {@code created_ms} =
     * {@code heartbeat_ms} = {@link #nowMs()}, parçalar {@code attempts = 0}.
     */
    void createRun(CertRunSpec spec);

    /**
     * Açık koşuya ikinci görüş parçaları ekler (§2.6; {@link CertChunkSpec#avoidWorker()} dolu, numaralar mevcutların
     * ardından). Koşu RUNNING değilse yine yazılır ama sahiplenilemez (koordinatör zaten iptal eder).
     *
     * @return eklenen parça sayısı
     */
    int addChunks(String runId, List<CertChunkSpec> chunks);

    /**
     * Nabız: {@code UPDATE … SET heartbeat_ms = şimdi WHERE run_id = ? AND state = 'RUNNING'}.
     *
     * @return false = 0 satır (koşu artık RUNNING değil — başka koordinatör öksüz saydı) → koordinatör LOCK_LOST ile bırakır
     */
    boolean heartbeat(String runId);

    /** Koşunun parça durumları ({@code chunk_no} sırasında; JSON YOK; en çok {@value #MAX_CHUNK_ROWS}). */
    List<ChunkState> chunkStates(String runId);

    /**
     * DONE parçaların sonuç JSON'u ({@link ChunkCompletion#decodeResults} ile çözülür). Yalnız {@code run_id} uyan ve DONE olan
     * kimlikler döner; diğerleri haritada yok.
     *
     * @return kimlik → {@code results_json}
     */
    Map<Long, String> readResults(String runId, Collection<Long> chunkIds);

    /**
     * Devralma (§2.4 / §4.2): {@code UPDATE … SET state = 'CANCELLED' WHERE id = ? AND run_id = ? AND state IN ('PENDING',
     * 'CLAIMED')}. Sonra koordinatör o parçanın alan adlarını kendisi ölçer.
     *
     * @return false = 0 satır (parça bu arada DONE oldu — sonucu toplanır; ya da zaten iptal)
     */
    boolean cancel(String runId, long chunkId);

    /**
     * Karar aşamasına geçiş: {@code UPDATE … SET state = 'DECIDING', workers_json, takeovers, reclaims, second_opinions WHERE
     * run_id = ? AND state = 'RUNNING'} (ikinci görüş sonucu sütunları NULL).
     *
     * @param workersJson {@link CertSweepStatus#encodeWorkers} çıktısı (≤ {@value CertSweepStatus#MAX_WORKERS_JSON_CHARS})
     * @return false = 0 satır → koordinatör "kilit kaybı" gibi bırakır (sonuçları işlemez)
     */
    boolean markDeciding(String runId, String workersJson, int takeovers, int reclaims, int secondOpinions);

    /**
     * {@link #markDeciding(String, String, int, int, int)} + ikinci görüşün SONUCU (inceleme L-4): yeniden ölçümü kullanılan alan
     * adlarından başka pod'da başarılı ({@code second_opinion_ok}) / yine hatalı ({@code second_opinion_failed}) olanlar — Küme
     * kartı çıkış eşliği (P1) önerisini yalnız çoğu başarılıysa yapar. null = bilinmiyor (sütuna NULL). Varsayılan: sonuç yazılmaz
     * (sarmalayan test depoları için; {@link JdbcCertSweepStore} ikisini de yazar).
     */
    default boolean markDeciding(String runId, String workersJson, int takeovers, int reclaims, int secondOpinions,
                                 Integer secondOpinionOk, Integer secondOpinionFailed) {
        return markDeciding(runId, workersJson, takeovers, reclaims, secondOpinions);
    }

    /**
     * Koşuyu kapatır: {@code UPDATE … SET state = ?, outcome = ?, finished_ms = şimdi WHERE run_id = ? AND state IN
     * ('RUNNING', 'DECIDING')}; {@code atShutdown} değilse ardından {@code DELETE FROM cert_sweep_chunks WHERE run_id = ?}.
     * Kapanışta tek UPDATE, {@value #SHUTDOWN_QUERY_TIMEOUT_SECONDS} sn zaman aşımı, parça silinmez (sonraki koordinatörün
     * {@link #settleOrphans} çağrısı siler). FIRLATMAZ (hata → false + çağıranın WARN'ı): {@code runCheckForDomains}'in
     * {@code finally}'sinden çağrılır.
     *
     * @param state      {@link #RUN_DONE} / {@link #RUN_FAILED} / {@link #RUN_ABANDONED}
     * @param outcome    {@link #OUTCOME_DECIDED} …
     * @param atShutdown kapanış yolu mu
     * @return koşu satırı güncellendi mi
     */
    boolean finish(String runId, String state, String outcome, boolean atShutdown);

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // İşçi (her pod; YALNIZ ölçer, yazdığı tek şey parça satırı — L-2)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Bir parça sahiplenir (§4.2 "Claim": PostgreSQL {@code FOR UPDATE OF k SKIP LOCKED … RETURNING}; H2 / bilinmeyen lehçe:
     * aday SELECT + {@code attempts}'e bağlı CAS). Adaylar: PENDING ya da kirası dolmuş CLAIMED, {@code attempts <}
     * {@value CertChunking#MAX_ATTEMPTS}, {@code created_ms < createdBeforeMs}, {@code avoid_worker} bu sahip değil, koşusu
     * RUNNING ve nabzı ≥ şimdi − {@value CertChunking#COORDINATOR_ALIVE_MS} ms; en küçük {@code id}. Rastgele belirteç
     * deponun işi; {@code attempts + 1}; {@code lease_until_ms = şimdi + leaseMs}. Dönen parça koşunun koordinatörünü,
     * parçanın oluşturma anını, kaçınılan işçiyi ve aynı koşuda başka sahiplerin son etkinliğini
     * ({@link ClaimedChunk#othersActiveMs()}, FX2-WORKER L-2) taşır.
     *
     * @param owner           bu pod'un örnek kimliği ({@code SchedulerService.instanceId()})
     * @param leaseMs         kira ({@link CertChunking.Tuning#leaseMs()})
     * @param createdBeforeMs yalnız BAŞKA bir pod'un koordine ettiği koşunun ilk görüş parçaları için: bundan ÖNCE yazılanlar
     *                        ({@link Long#MAX_VALUE} = hepsi; başka canlı pod varken tarama lideri {@code şimdi − steal-after-ms};
     *                        {@link Long#MIN_VALUE} = yalnız muaf parçalar). Sahibin KENDİ koşusunun parçaları
     *                        ({@code coordinator = owner}) ve ikinci görüş parçaları ({@code avoid_worker} dolu) her eşikte
     *                        adaydır (FX-WORKER SW-M1 / CR-L1 / CR-H-1 b).
     * @return sahiplenilen parça; aday yoksa / tablolar yoksa boş
     */
    Optional<ClaimedChunk> claim(String owner, long leaseMs, long createdBeforeMs);

    /**
     * Kira yenileme (tek deyim, bu pod'un tuttuğu tüm parçalar): {@code UPDATE … SET lease_until_ms = şimdi + leaseMs WHERE
     * claimed_by = ? AND state = 'CLAIMED' AND id IN (…)}. Boş küme → 0, deyim yok.
     *
     * @return güncellenen satır sayısı (eksik olanlar kaybedildi — iptal / başka işçi)
     */
    int renew(String owner, Collection<Long> chunkIds, long leaseMs);

    /**
     * Tamamlama (§4.2 "Complete"): belirteç CAS'ı ve koşu hâlâ RUNNING — {@code UPDATE … SET state = 'DONE', results_json,
     * checked, network_errors, errors, warnings, finished_ms = şimdi, worker, lease_until_ms = NULL WHERE id = ? AND state =
     * 'CLAIMED' AND claim_token = ? AND EXISTS (koşu RUNNING)}.
     *
     * @return false = 0 satır (geç: kira kaybedildi, iptal edildi ya da koşu kapandı) — sonuç DÜŞER (sayaç {@code late})
     */
    boolean complete(ChunkCompletion completion);

    /**
     * Kapanışta bırakma (işçinin {@code cert-chunk-release} yardımcısı, beklemesiz): {@code UPDATE … SET state = 'PENDING',
     * claimed_by = NULL, claim_token = NULL, lease_until_ms = NULL, attempts = attempts − 1 WHERE claimed_by = ? AND state =
     * 'CLAIMED' AND id IN (…)} — {@value #SHUTDOWN_QUERY_TIMEOUT_SECONDS} sn zaman aşımı. Boş küme → 0.
     *
     * @return bırakılan satır sayısı
     */
    int release(String owner, Collection<Long> chunkIds);

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Durum (Sistem Sağlığı / Küme kartı / ölçümler — yalnız anahtar açıkken)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Küme durumu: {@code SELECT … FROM cert_sweep_runs ORDER BY created_ms DESC LIMIT ?} + açık koşu varsa onun gruplu parça
     * sayıları ({@link CertSweepStatus.ChunkGroup}). Okunamazsa FIRLAR (çağıran "okunamadı" gösterir — asla "boş" değil).
     *
     * @param lastRuns en çok kaç koşu (1–20; Küme kartı 5)
     */
    CertSweepStatus status(int lastRuns);
}
