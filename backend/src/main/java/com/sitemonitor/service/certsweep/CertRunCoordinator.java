package com.sitemonitor.service.certsweep;

import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.schedule.PodLiveness;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Parçalı sertifika taramasının KOORDİNATÖRÜ (2026-10-29, çok pod Sürüm 3-4 paket B; tasarım R3-4 §2, §4.5; kullanıcı kararları
 * V-1…V-3). {@code SchedulerService.runCheckForDomains} — bugünkü tetik kodu, {@code cert-check} kilidi ve tur satırları
 * DEĞİŞMEDEN — toplama adımında {@link #gather} çağırır:
 * <ul>
 *   <li>{@code null} → klasik toplama ({@code gatherCertResults}, 20.170.0'ın satırları birebir);</li>
 *   <li>aksi: koşu + parça satırları yazılır, her 3-4 pod'un işçisi (bu pod dahil) parçaları sahiplenip YALNIZ ÖLÇER, bu sınıf
 *       sonuçları KLASİK SIRADA yeniden kurar ve listeyi döner — kayıt, önbellek boşaltma, toplu kesinti kuralı
 *       ({@code onRun}), alarm işleme ve {@code scan_runs} çağıranın DEĞİŞMEMİŞ koduyla, TEK pod'da (L-2), tüm tura
 *       bakarak (V-3) çalışır.</li>
 * </ul>
 *
 * <p><b>Kapalı ≡ v20.170.0 (L-1).</b> {@link #gather}'in İLK deyimi {@link CertChunking#enabled()} (bellek) okumasıdır; kapalıysa
 * {@code null} döner — depo, işçi, {@code PodLiveness}, veritabanı, günlük, iş parçacığı yok. {@link #finish} yalnız parçalı
 * koşulan turda çağrılır.
 *
 * <p><b>Öksüz kapatma her açık turda (inceleme OB-L1).</b> Anahtar açıkken HER turun başında (tur klasik kalsa da — tek pod,
 * az alan adı) depo hazırsa açık (RUNNING / DECIDING) koşular öksüz kapatılır: çağıran {@code cert-check}'i tuttuğu için
 * başka açık koşu olamaz. Böylece ölmüş bir koordinatörün koşusu sonraki tur klasik de olsa "açık" görünmez.
 *
 * <p><b>Kip (§4.5 adım 1).</b> Anahtar açık ∧ depo bean'i var ve tabloları hazır ∧ ölçücü ({@link CertChunkRunner}) var ∧ alan
 * adı sayısı ≥ {@link CertChunking.Tuning#minDomains()} ∧ kapanmıyor ∧ BAŞKA canlı pod var ({@code PodLiveness.anotherLivePod()};
 * bilinmiyor = yok). Biri tutmazsa klasik (sayaç {@code classic_by_rule}); koşu yazılamadıysa ya da koordinatörün kendi hatasıyla
 * klasiğe düşüldüyse sayaç {@code classic_fallback} (inceleme OB-L6). Tek pod'da anahtar açık olsa da tur klasiktir.
 *
 * <p><b>Bekleme döngüsü (§2.4 / §2.5).</b> {@value CertChunking#COORDINATOR_WAIT_POLL_MS} ms'de bir: kapanış → SHUTDOWN, kilit
 * kaybı → LOCK_LOST (sonuç işlenmez — klasik SS:3340 / SS:3348 ile aynı), {@value CertChunking#COORDINATOR_HEARTBEAT_MS} ms'de
 * bir nabız (0 satır → LOCK_LOST), parça durumları, yeni DONE parçaların sonuçları en çok {@value #RESULT_READ_BATCH} parçalık
 * gruplar hâlinde (CR-L2). Kullanılamayan sonuç ({@code failed} / kırpılmış / başka alan adının sonucu) ve KAYIP parça
 * ({@link ChunkState#lost}; ya da kirası {@code lease-ms}'ten uzun süredir dolmuş ve kimsenin yeniden almadığı parça, CR-L3)
 * koordinatörün KENDİ klasik toplamasıyla ölçülür (L-4). Sonucu ya da iptali art arda {@value #STORE_STRIKES_TAKEOVER} kez
 * okunamayan / yazılamayan parça da yerelde ölçülür (CR-L2). İlerleme ({@code sweep-max-wait-seconds}, 900 sn) yoksa,
 * {@value #NO_WORKER_MS} ms içinde hiçbir parça sahiplenilmediyse (hiçbir pod'da işçi yok), depo
 * {@value CertChunking#STORE_FAILURE_LOCAL_MS} ms boyunca okunamadıysa ya da anahtar tur sürerken KAPATILDIYSA ve parçanın
 * durumu {@value #SWITCH_OFF_STALL_MS} ms değişmediyse (SW-L2) kalan parçalar yerelde ölçülür — tur koordinatör yaşadıkça ve
 * kilidi tuttukça HER alan adı için tek sonuçla biter. Yerel ölçüm sürerken koşunun nabzı ayrı bir iş parçacığından
 * ({@value #HEARTBEAT_THREAD}) taze tutulur — işçiler sahiplenmeyi sürdürür, koşu sessiz görünmez (CR-L4).
 *
 * <p><b>Aykırı pod — ikinci görüş (§2.6).</b> Bir pod'un ağ hatası (+ çözülemeyen ad, CR-M-1) oranı eşiğin üstünde, diğerleri
 * temizken o pod'un bu hataları BAŞKA bir pod'dan (kaçınılan işçi = o pod) bir kez yeniden ölçülür; yeni ölçüm eskisinin yerine
 * geçer (L-3'ün tek bilinçli istisnası). Aykırı pod koordinatörün KENDİSİYSE (inceleme H-1): 30 sn içinde hiçbir ikinci görüş
 * parçası sahiplenilmediyse özgün sonuçlar kalır; sahiplenildiyse (tavan: ilerlemesiz {@code sweep-max-wait-seconds}) hepsi
 * bitene ya da biri kaybolana dek beklenir, canlı sahiplenilmiş parça iptal edilmez; ve HEPSİ-YA-DA-HİÇBİRİ: yeniden dağıtılan
 * her alan adı kullanılabilir biçimde yeniden ölçülmediyse özgün sonuçların HEPSİ kalır — kısmi bir değişim turu toplu kesinti
 * eşiğinin altına çekemez. Aykırı pod uyarısı ikinci görüşten SONRA, sonucuyla yazılır; çıkış eşliği (P1) önerisi yalnız yeniden
 * ölçümlerin çoğu başka pod'dan başarılıysa (CR-L5).
 *
 * <p><b>Ölçüm koordinatörün iş parçacığında</b> (tasarım §4.5 adım 3): {@link CertChunkRunner#checkDomains} = klasik toplama, aynı
 * istisna anlamıyla — bir kontrolün istisnası turu klasikteki gibi düşürür (koşu FAILED, istisna AYNEN fırlar). Koordinatörün
 * kendi beklenmedik hatası ise turu düşürmez: koşu kapatılır ve {@code null} döner → klasik toplama.
 */
@Slf4j
@Component
public class CertRunCoordinator {

    /** Bu kadar (ms) içinde hiçbir parça sahiplenilmediyse (kümede hizmet veren işçi yok) koordinatör hepsini kendisi ölçer. */
    public static final long NO_WORKER_MS = CertChunking.COORDINATOR_ALIVE_MS;
    /** Yayın kuruluşu okumasında {@code IN (…)} başına alan adı. */
    static final int ISSUER_BATCH = 500;
    /** Koşu başlatılamadı uyarısının aralığı (ms). */
    static final long CREATE_WARN_EVERY_MS = 60_000L;
    /** Aykırı pod uyarısının aralığı (ms) — pod başına saatte bir. */
    static final long OUTLIER_WARN_EVERY_MS = 3_600_000L;
    /** Biten parçaların sonuçları en çok bu kadar parçalık okumalarla toplanır (inceleme CR-L2 — dev tek okuma yok). */
    static final int RESULT_READ_BATCH = 20;
    /** Sonucu okunamayan / iptali yazılamayan parça art arda bu kadar başarısızlıktan sonra yerelde ölçülür (CR-L2). */
    static final int STORE_STRIKES_TAKEOVER = 3;
    /** Anahtar tur sürerken kapatıldıysa durumu bu kadar (ms) değişmeyen açık parça devralınır (SW-L2). */
    static final long SWITCH_OFF_STALL_MS = 30_000L;
    /** Yerel ölçüm sürerken koşu nabzını atan iş parçacığının adı (kalıcı). */
    public static final String HEARTBEAT_THREAD = "cert-run-heartbeat";
    /** Nabız iş parçacığının durdurulurken en çok beklenmesi (ms). */
    static final long HEARTBEAT_STOP_JOIN_MS = 1_000L;

    /**
     * Turun bağlamı (çağıran {@code SchedulerService} kurar — değerler TEMBEL okunur, anahtar kapalıyken hiçbiri çağrılmaz).
     *
     * @param runId         klasik tur kimliği (= koşu kimliği, {@code certificate_checks.run_id})
     * @param kind          {@code hourly} / {@code stale} / {@code full}
     * @param evictCaches   tur sonunda önbellek boşaltılacak mı (yalnız bilgi — boşaltmayı çağıran yapar)
     * @param lockHeld      {@code cert-check} tutuşu hâlâ bu pod'da mı (bellek, {@code lockStillHeld})
     * @param stopping      kapanış başladı mı (bellek, {@code startupCheckStopped || stopping()})
     * @param maxWaitMs     ilerlemesiz bekleme tavanı (ms, {@code sweep-max-wait-seconds})
     * @param minErrors     toplu kesinti kuralının {@code min-errors} değeri (aykırı pod kuralı için)
     * @param rateThreshold toplu kesinti kuralının oran eşiği (aykırı pod kuralı için)
     */
    public record Request(String runId, String kind, boolean evictCaches, BooleanSupplier lockHeld, BooleanSupplier stopping,
                          long maxWaitMs, IntSupplier minErrors, DoubleSupplier rateThreshold) {}

    /**
     * Parçalı toplamanın sonucu.
     *
     * @param runId        koşu kimliği
     * @param results      KLASİK SIRADA sonuç haritaları (değiştirilebilir; {@code aborted} iken boş)
     * @param abortOutcome null = tamam; {@link CertSweepStore#OUTCOME_SHUTDOWN} / {@link CertSweepStore#OUTCOME_LOCK_LOST} =
     *                     sonuçlar işlenmez, çağıran erken döner
     * @param measured     bırakıldığı anda toplanmış sonuç sayısı (günlük için)
     */
    public record Gathered(String runId, List<Map<String, Object>> results, String abortOutcome, int measured) {
        public boolean aborted() {
            return abortOutcome != null;
        }
    }

    /** Uyku (testler sahte saatle ilerletir). */
    interface Pause {
        void sleep(long ms) throws InterruptedException;
    }

    /**
     * Yerel ölçüm sürerken koşu nabzını atan zamanlayıcı (inceleme CR-L4) — üretimde {@link #THREAD_BEAT}, testler değiştirir.
     * {@code beat} kendi hatasını yutar.
     */
    interface BeatTimer {
        /** {@code beat}'i {@code periodMs}'de bir çalıştırmaya başlar. */
        Beat start(long periodMs, Runnable beat);
    }

    /** Çalışan nabız. */
    interface Beat {
        /**
         * Durdurur (en çok {@value CertRunCoordinator#HEARTBEAT_STOP_JOIN_MS} ms bekler, fırlatmaz).
         *
         * @return beklerken çağıran iş parçacığı kesildi mi (çağıran bayrağı sonunda geri koyar)
         */
        boolean stop();
    }

    /**
     * Üretim nabzı: bir daemon iş parçacığı ({@value #HEARTBEAT_THREAD}) {@code periodMs} uyuyup {@code beat}'i çağırır; durdurma
     * kesme + en çok {@value #HEARTBEAT_STOP_JOIN_MS} ms birleştirme (deyim sürüyorsa iş parçacığı kendi süresiyle biter).
     * Yalnız anahtar açıkken, parçalı koşunun yerel ölçümü sürerken var olur (L-1: kapalıyken hiç doğmaz).
     */
    static final BeatTimer THREAD_BEAT = (periodMs, beat) -> {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(periodMs);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    beat.run();
                } catch (RuntimeException e) {
                    log.debug("Parçalı koşu nabzı atılamadı: {}", e.getClass().getSimpleName());
                }
            }
        }, HEARTBEAT_THREAD);
        t.setDaemon(true);
        t.start();
        return () -> {
            t.interrupt();
            boolean interrupted = false;
            long deadline = System.nanoTime() + HEARTBEAT_STOP_JOIN_MS * 1_000_000L;
            while (t.isAlive()) {
                long left = (deadline - System.nanoTime()) / 1_000_000L;
                if (left <= 0) break;
                try {
                    t.join(left);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            return interrupted;
        };
    };

    private final CertChunking chunking;
    private final Supplier<CertSweepStore> storeSource;
    private final Supplier<CertChunkRunner> runnerSource;
    private final BooleanSupplier anotherLivePod;
    private final Function<List<String>, Map<String, String>> issuerLookup;
    private final Supplier<String> instanceId;
    private final LongSupplier monoMs;
    private final Pause pause;
    private volatile BeatTimer beatTimer = THREAD_BEAT;

    private volatile Runnable localWorkerPoke;
    private final Map<String, Session> open = new ConcurrentHashMap<>();

    private final AtomicLong chunkedRuns = new AtomicLong();
    private final AtomicLong classicByRule = new AtomicLong();
    private final AtomicLong classicFallback = new AtomicLong();
    private final AtomicLong takeovers = new AtomicLong();
    private final AtomicLong remeasured = new AtomicLong();
    private final AtomicLong secondOpinions = new AtomicLong();
    private final AtomicLong abandoned = new AtomicLong();
    private final AtomicLong createWarnAt = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong outlierWarnAt = new AtomicLong(Long.MIN_VALUE);

    private volatile JdbcTemplate issuerJdbc;

    /** Spring kurucusu — depo, ölçücü, canlılık ve veri kaynağı TEMBEL çözülür (bağımlılık döngüsü yok, açılışta iş yok). */
    @Autowired
    public CertRunCoordinator(CertChunking chunking, ObjectProvider<CertSweepStore> store,
                              ObjectProvider<CertChunkRunner> runner, ObjectProvider<PodLiveness> podLiveness,
                              ObjectProvider<DataSource> dataSource) {
        this.chunking = chunking;
        this.storeSource = () -> available(store);
        this.runnerSource = () -> available(runner);
        this.anotherLivePod = () -> {
            PodLiveness p = available(podLiveness);
            return p != null && p.anotherLivePod();
        };
        this.issuerLookup = domains -> springIssuers(dataSource, domains);
        this.instanceId = com.sitemonitor.service.SchedulerService::instanceId;
        this.monoMs = () -> System.nanoTime() / 1_000_000L;
        this.pause = Thread::sleep;
        if (chunking != null) chunking.registerCoordinatorStatus(this::status);
    }

    /** Testler / elle kurulum: her bağımlılık doğrudan (null depo / ölçücü → hep klasik). */
    CertRunCoordinator(CertChunking chunking, CertSweepStore store, CertChunkRunner runner, BooleanSupplier anotherLivePod,
                       Function<List<String>, Map<String, String>> issuerLookup, Supplier<String> instanceId,
                       LongSupplier monoMs, Pause pause) {
        this.chunking = chunking;
        this.storeSource = () -> store;
        this.runnerSource = () -> runner;
        this.anotherLivePod = anotherLivePod == null ? () -> false : anotherLivePod;
        this.issuerLookup = issuerLookup == null ? d -> Map.of() : issuerLookup;
        this.instanceId = instanceId;
        this.monoMs = monoMs;
        this.pause = pause;
        if (chunking != null) chunking.registerCoordinatorStatus(this::status);
    }

    /**
     * Bu pod'un işçisini yeni koşudan haberdar eden kanca (işçi boşta yoklamasını beklemeden sahiplensin — tasarım §4.4).
     * Yoksa işçi bir sonraki yoklamasında ({@code idle-poll-ms}) görür. Hızlı olmalı; istisnası yutulur.
     */
    public void setLocalWorkerPoke(Runnable poke) {
        this.localWorkerPoke = poke;
    }

    /** Testler: yerel ölçüm nabzı (null → üretim iş parçacığı). */
    void setBeatTimer(BeatTimer timer) {
        this.beatTimer = timer == null ? THREAD_BEAT : timer;
    }

    /** Bu pod'un koordinatör sayaçları (bellek; Sistem Sağlığı / ölçümler). */
    public CertSweepStatus.Coordinator status() {
        return new CertSweepStatus.Coordinator(chunkedRuns.get(), classicFallback.get(), takeovers.get(), remeasured.get(),
                secondOpinions.get(), abandoned.get(), classicByRule.get());
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Toplama
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Tur başı öksüz kapatmanın sonucu. */
    private enum Settle { DONE, SKIPPED, FAILED }

    /**
     * Turun toplama adımı — {@code null} = klasik toplamayı koş.
     *
     * @param domains klasik vade listesi (sıra = karar sırası)
     * @param req     turun bağlamı
     * @return null (klasik) ya da klasik sırada sonuçlar / bırakma sonucu
     * @throws RuntimeException bir kontrolün istisnası (klasik toplamanın {@code join()} istisnası, AYNI nesne)
     */
    public Gathered gather(List<Map<String, Object>> domains, Request req) {
        CertChunking cc = chunking;
        if (cc == null || !cc.enabled()) return null;                // İLK deyim: bellek (L-1)
        CertSweepStore store = storeSource.get();
        CertChunkRunner runner = runnerSource.get();
        RuntimeException settleFailure = null;
        Settle settle;
        try {
            settle = settleAtRunStart(store, req);                   // anahtar açıkken HER tur (OB-L1)
        } catch (RuntimeException e) {
            settle = Settle.FAILED;
            settleFailure = e;
            log.debug("Sahipsiz parçalı koşular kapatılamadı: {}", e.getClass().getSimpleName());
        }
        if (!chunkedRunWanted(cc, store, runner, domains, req)) {
            classicByRule.incrementAndGet();
            return null;
        }
        String me = instanceId.get();
        CertRunSpec spec;
        try {
            if (settle == Settle.FAILED) throw settleFailure;        // öksüzler kapatılamadı → bu tur parçalı olamaz
            pruneQuietly(store);
            spec = buildSpec(domains, req, me, cc.tuning());
            store.createRun(spec);
        } catch (RuntimeException e) {
            warnCreate(e);
            classicFallback.incrementAndGet();
            return null;
        }
        chunkedRuns.incrementAndGet();
        log.info("Sertifika taraması parçalı — runId={}, {} alan adı, {} parça (boyut {}) [instance={}]",
                spec.runId(), spec.domainsTotal(), spec.chunksTotal(), spec.chunkSize(), me);
        Runnable poke = localWorkerPoke;
        if (poke != null) {
            try {
                poke.run();
            } catch (RuntimeException e) {
                log.debug("Yerel parça işçisi dürtülemedi: {}", e.getClass().getSimpleName());
            }
        }
        Session s = new Session(spec, req, me, store, runner, cc);
        open.put(spec.runId(), s);
        try {
            return s.run();
        } catch (RunnerFailure rf) {
            // Bir kontrolün istisnası — klasik toplamadaki gibi tur düşer; koşu burada kapanır (çağıranın kancası çağrılmaz)
            open.remove(spec.runId());
            finishQuietly(store, spec.runId(), CertSweepStore.RUN_FAILED, CertSweepStore.OUTCOME_FAILED, false);
            throw rf.rethrowable();
        } catch (RuntimeException e) {
            // Koordinatörün kendi hatası turu düşürmez: koşu kapanır, tur klasik toplamayla sürer (yeniden ölçüm yan etkisiz)
            open.remove(spec.runId());
            log.warn("Parçalı tarama iç hatası — tur klasik toplamaya dönüyor (runId={}): {}", spec.runId(),
                    e.getClass().getSimpleName());
            finishQuietly(store, spec.runId(), CertSweepStore.RUN_ABANDONED, CertSweepStore.OUTCOME_FAILED, false);
            abandoned.incrementAndGet();
            classicFallback.incrementAndGet();
            return null;
        } catch (Error e) {
            open.remove(spec.runId());
            finishQuietly(store, spec.runId(), CertSweepStore.RUN_FAILED, CertSweepStore.OUTCOME_FAILED, false);
            throw e;
        } finally {
            if (s.interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * Tur başı öksüz kapatma (inceleme OB-L1): anahtar açık, depo var ve tabloları hazır, kapanmıyorken HER turun başında —
     * tur klasik kalsa da. Çağıran {@code cert-check}'i tuttuğu için açık (RUNNING / DECIDING) her koşu öksüzdür (koordinatörü
     * kim olursa olsun). Her kapatılan koşu için bir WARN.
     *
     * @throws RuntimeException depo hatası (çağıran: klasik tur sürer; parçalı olacaktıysa klasiğe düşer)
     */
    private Settle settleAtRunStart(CertSweepStore store, Request req) {
        if (store == null || req == null) return Settle.SKIPPED;
        if (!store.tablesReady()) return Settle.SKIPPED;
        if (req.stopping() != null && req.stopping().getAsBoolean()) return Settle.SKIPPED;
        List<CertSweepStatus.Run> orphans = store.settleOrphans();
        if (orphans != null) {
            for (CertSweepStatus.Run orphan : orphans) {
                log.warn("Sahipsiz parçalı tarama kapatıldı (runId={}, koordinatör={}) — sonuçları kaydedilmemişti, "
                        + "bayat tarama telafi eder", orphan.runId(), ClusterHealthStore.podOf(orphan.coordinator()));
            }
        }
        return Settle.DONE;
    }

    /**
     * Turun son durumunu yazar ({@code runCheckForDomains}'in {@code finally}'si — yalnız parçalı koşulan turda): bırakıldıysa
     * ABANDONED (sonucuyla), istisna yakalandıysa FAILED, bittiyse DONE (DECIDED / STORE_FAILED), sonuçlar işlenmeden erken
     * dönüldüyse ABANDONED (kapanış → SHUTDOWN, aksi → LOCK_LOST). Asla fırlatmaz.
     */
    public void finish(String runId, boolean finished, boolean failed) {
        if (runId == null) return;
        Session s = open.remove(runId);
        CertSweepStore store = s != null ? s.store : storeSource.get();
        if (store == null) return;
        String state;
        String outcome;
        if (s != null && s.abortOutcome != null) {
            state = CertSweepStore.RUN_ABANDONED;
            outcome = s.abortOutcome;
        } else if (failed) {
            state = CertSweepStore.RUN_FAILED;
            outcome = CertSweepStore.OUTCOME_FAILED;
        } else if (finished) {
            state = CertSweepStore.RUN_DONE;
            outcome = s != null && s.storeFailed ? CertSweepStore.OUTCOME_STORE_FAILED : CertSweepStore.OUTCOME_DECIDED;
        } else {
            state = CertSweepStore.RUN_ABANDONED;
            outcome = closingNow(s) ? CertSweepStore.OUTCOME_SHUTDOWN : CertSweepStore.OUTCOME_LOCK_LOST;
        }
        if (CertSweepStore.RUN_ABANDONED.equals(state)) abandoned.incrementAndGet();
        finishQuietly(store, runId, state, outcome, CertSweepStore.OUTCOME_SHUTDOWN.equals(outcome));
    }

    private boolean closingNow(Session s) {
        try {
            if (s != null && s.req.stopping() != null && s.req.stopping().getAsBoolean()) return true;
            CertChunkRunner r = s != null ? s.runner : runnerSource.get();
            return r != null && r.closing();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void finishQuietly(CertSweepStore store, String runId, String state, String outcome, boolean atShutdown) {
        try {
            if (!store.finish(runId, state, outcome, atShutdown)) {
                log.debug("Parçalı koşu kapatılamadı (zaten kapalı ya da yazılamadı): runId={}, {}", runId, state);
            }
        } catch (RuntimeException e) {
            log.warn("Parçalı koşu kapanışı yazılamadı (runId={}): {}", runId, e.getClass().getSimpleName());
        }
    }

    private boolean chunkedRunWanted(CertChunking cc, CertSweepStore store, CertChunkRunner runner,
                                     List<Map<String, Object>> domains, Request req) {
        if (store == null || runner == null || domains == null || req == null) return false;
        try {
            if (!store.tablesReady()) return false;
            if (domains.size() < cc.tuning().minDomains()) return false;
            if (req.stopping() != null && req.stopping().getAsBoolean()) return false;
            return anotherLivePod.getAsBoolean();                    // SQL (30 sn önbellekli) — EN SON
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void pruneQuietly(CertSweepStore store) {
        try {
            store.pruneRuns(store.nowMs() - CertChunking.RUN_RETENTION_MS, CertChunking.RUN_PRUNE_LIMIT);
        } catch (RuntimeException e) {
            log.debug("Eski parçalı koşu satırları budanamadı: {}", e.getClass().getSimpleName());
        }
    }

    private void warnCreate(RuntimeException e) {
        long now = monoMs.getAsLong();
        long last = createWarnAt.get();
        if (last != Long.MIN_VALUE && now - last < CREATE_WARN_EVERY_MS) {
            log.debug("Parçalı tarama başlatılamadı — tur klasik koşuyor: {}", e.getClass().getSimpleName());
            return;
        }
        if (createWarnAt.compareAndSet(last, now)) {
            log.warn("Parçalı tarama başlatılamadı — tur klasik koşuyor: {}", e.getClass().getSimpleName());
        }
    }

    // ── Parça kurulumu (§4.5 adım 2) ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Vade listesini yayın kuruluşu yakınlığıyla sıralar (son {@code latest_checks.issuer_dn}; bilinmiyorsa alan adının
     * kendisi; eşitlikte klasik sıra) ve {@link CertChunking.Tuning#effectiveChunkSize} boyutunda parçalara böler. Her girdi
     * KLASİK sıra numarasını taşır — karar listesi bu numarayla yeniden kurulur. Yanlış türlü satır klasik toplamadaki gibi
     * {@code ClassCastException} fırlatır (çağıran klasiğe döner).
     */
    CertRunSpec buildSpec(List<Map<String, Object>> domains, Request req, String me, CertChunking.Tuning tuning) {
        int n = domains.size();
        List<CertChunkSpec.Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) entries.add(CertChunkSpec.Entry.of(i, domains.get(i)));
        Map<String, String> issuers = issuersOf(entries);
        List<CertChunkSpec.Entry> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparing((CertChunkSpec.Entry e) -> {
            String issuer = issuers.get(e.domain());
            return issuer == null || issuer.isBlank() ? e.domain() : issuer;
        }).thenComparingInt(CertChunkSpec.Entry::index));
        int size = tuning.effectiveChunkSize(n);
        List<CertChunkSpec> chunks = new ArrayList<>();
        for (int from = 0, no = 0; from < n; from += size, no++) {
            chunks.add(CertChunkSpec.of(no, ordered.subList(from, Math.min(n, from + size))));
        }
        return new CertRunSpec(req.runId(), req.kind(), me, req.evictCaches(), n, size, chunks);
    }

    private Map<String, String> issuersOf(List<CertChunkSpec.Entry> entries) {
        List<String> names = new ArrayList<>(new LinkedHashSet<>(entries.stream().map(CertChunkSpec.Entry::domain).toList()));
        try {
            Map<String, String> m = issuerLookup.apply(names);
            return m == null ? Map.of() : m;
        } catch (RuntimeException e) {
            log.debug("Yayın kuruluşu sırası okunamadı — klasik sırayla bölünüyor: {}", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Map<String, String> springIssuers(ObjectProvider<DataSource> dataSource, List<String> domains) {
        JdbcTemplate j = issuerJdbc;
        if (j == null) {
            DataSource ds = available(dataSource);
            if (ds == null) return Map.of();
            j = new JdbcTemplate(ds);
            j.setQueryTimeout(CertSweepStore.QUERY_TIMEOUT_SECONDS);
            issuerJdbc = j;
        }
        return lookupIssuers(j, domains);
    }

    /** {@code SELECT domain, issuer_dn FROM latest_checks WHERE domain IN (…)} — {@value #ISSUER_BATCH}'lük gruplar. */
    static Map<String, String> lookupIssuers(JdbcTemplate jdbc, List<String> domains) {
        Map<String, String> out = new HashMap<>();
        if (jdbc == null || domains == null) return out;
        for (int from = 0; from < domains.size(); from += ISSUER_BATCH) {
            List<String> part = domains.subList(from, Math.min(domains.size(), from + ISSUER_BATCH));
            String in = String.join(",", Collections.nCopies(part.size(), "?"));
            jdbc.query("SELECT domain, issuer_dn FROM latest_checks WHERE domain IN (" + in + ")", rs -> {
                String d = rs.getString(1);
                String i = rs.getString(2);
                if (d != null && i != null && !i.isBlank()) out.put(d, i);
            }, part.toArray());
        }
        return out;
    }

    private static <T> T available(ObjectProvider<T> p) {
        if (p == null) return null;
        try {
            return p.getIfAvailable();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Bir koşunun toplaması
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Ölçücünün (klasik toplama) istisnası — koordinatörün kendi hatasından ayrılsın diye sarılır, çağırana AYNEN iletilir. */
    static final class RunnerFailure extends RuntimeException {
        private final Throwable original;

        RunnerFailure(Throwable original) {
            super(null, null, false, false);
            this.original = original;
        }

        RuntimeException rethrowable() {
            if (original instanceof RuntimeException re) return re;
            if (original instanceof Error err) throw err;
            return new IllegalStateException(original);
        }
    }

    /**
     * Bir aykırı pod'un ikinci görüş grubu (§2.6).
     *
     * @param worker  aykırı pod'un örnek kimliği (kaçınılan işçi)
     * @param nw      pod'un sonuç sayısı
     * @param nev     pod'un hatalı sonuç sayısı (ağ + çözülemeyen ad)
     * @param otherN  diğerlerinin sonuç sayısı
     * @param otherNe diğerlerinin hatalı sonuç sayısı
     * @param indices yeniden dağıtılan klasik sıra numaraları
     */
    private record SoGroup(String worker, int nw, int nev, int otherN, int otherNe, List<Integer> indices) {}

    private final class Session {
        final CertRunSpec spec;
        final Request req;
        final String me;
        final CertSweepStore store;
        final CertChunkRunner runner;
        final CertChunking chunking;
        final String runId;
        final int n;
        final long leaseMs;
        final List<Map<String, Object>> results;
        final String[] by;
        final CertChunkSpec.Entry[] entries;
        final Map<Integer, CertChunkSpec> specs = new HashMap<>();
        final Map<Integer, Long> idByNo = new HashMap<>();
        final Map<Long, Integer> noById = new HashMap<>();
        final Map<Integer, ChunkState> lastState = new HashMap<>();
        final Map<Integer, String> fingerprint = new HashMap<>();
        /** Parçanın durum parmak izinin son değiştiği an (tekdüze saat) — anahtar kapatılınca devralma kuralı (SW-L2). */
        final Map<Integer, Long> changedAt = new HashMap<>();
        final Map<Integer, Integer> maxAttempts = new HashMap<>();
        /** Art arda sonuç okuma / iptal başarısızlığı (CR-L2). */
        final Map<Integer, Integer> readStrikes = new HashMap<>();
        final Map<Integer, Integer> cancelStrikes = new HashMap<>();
        final Set<Integer> settled = new HashSet<>();
        final Set<Integer> queued = new HashSet<>();
        final List<CertChunkSpec.Entry> localEntries = new ArrayList<>();
        final Set<Integer> localChunks = new HashSet<>();
        final Map<String, Integer> chunkTally = new HashMap<>();
        // ── ikinci görüş ──
        final List<SoGroup> soGroups = new ArrayList<>();
        final Map<Integer, Map<String, Object>> soOriginal = new HashMap<>();
        /** Kaçınılan işçisi BU pod olan (koordinatör aykırı) ikinci görüş parçaları — hepsi-ya-da-hiçbiri (H-1). */
        final Set<Integer> selfSo = new LinkedHashSet<>();
        final Map<Integer, Map<String, Object>> selfStash = new HashMap<>();
        final Map<Integer, String> selfStashWorker = new HashMap<>();
        final Map<Integer, String> selfChunkWorker = new HashMap<>();
        boolean selfSoClaimSeen;
        boolean selfSoAbandoned;
        boolean selfSoApplied;
        // ──
        int takeoverChunks;
        int secondOpinionDomains;
        boolean storeFailed;
        long storeFailingSince = -1L;
        long lastStoreNow = Long.MIN_VALUE;
        boolean anyClaimSeen;
        boolean noWorkerFired;
        boolean switchOffSeen;
        long createdMono;
        long lastProgress;
        long lastHeartbeat;
        int nextChunkNo;
        volatile String abortOutcome;
        volatile boolean beatLost;
        boolean interrupted;

        Session(CertRunSpec spec, Request req, String me, CertSweepStore store, CertChunkRunner runner, CertChunking chunking) {
            this.spec = spec;
            this.req = req;
            this.me = me;
            this.store = store;
            this.runner = runner;
            this.chunking = chunking;
            this.runId = spec.runId();
            this.n = spec.domainsTotal();
            this.leaseMs = chunking.tuning().leaseMs();
            this.results = new ArrayList<>(Collections.nCopies(n, null));
            this.by = new String[n];
            this.entries = new CertChunkSpec.Entry[n];
            for (CertChunkSpec c : spec.chunks()) {
                register(c);
                for (CertChunkSpec.Entry e : c.entries()) entries[e.index()] = e;
            }
            this.nextChunkNo = spec.chunksTotal();
        }

        private void register(CertChunkSpec c) {
            specs.put(c.chunkNo(), c);
            fingerprint.put(c.chunkNo(), fp(CertSweepStore.CHUNK_PENDING, 0, null, null));
            changedAt.put(c.chunkNo(), monoMs.getAsLong());
            if (c.avoidWorker() != null && c.avoidWorker().equals(me)) selfSo.add(c.chunkNo());
        }

        Gathered run() {
            createdMono = lastProgress = lastHeartbeat = monoMs.getAsLong();
            Set<Integer> initial = new LinkedHashSet<>(specs.keySet());
            String abort = waitFor(initial, false, createdMono);
            if (abort == null) abort = secondOpinion();
            if (abort != null) return aborted(abort);
            List<Map<String, Object>> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                Map<String, Object> m = results.get(i);
                if (m == null) throw new IllegalStateException("eksik sonuç: " + i);   // olmamalı — iç hata → klasik
                list.add(m);
            }
            List<CertSweepStatus.WorkerTally> tallies = tallies();
            int reclaims = 0;
            for (int a : maxAttempts.values()) reclaims += Math.max(0, a - 1);
            if (!storeFailed) {
                try {
                    if (!store.markDeciding(runId, CertSweepStatus.encodeWorkers(tallies), takeoverChunks, reclaims,
                            secondOpinionDomains)) {
                        return aborted(CertSweepStore.OUTCOME_LOCK_LOST);
                    }
                } catch (RuntimeException e) {
                    log.debug("Parçalı koşu DECIDING yazılamadı (runId={}): {}", runId, e.getClass().getSimpleName());
                }
            }
            Map<String, Integer> perPod = new TreeMap<>();
            for (CertSweepStatus.WorkerTally t : tallies) perPod.merge(podOf(t.worker()), t.domains(), Integer::sum);
            log.info("Parçalı tarama toplandı — runId={}, pod başına {}, devralınan {}, ikinci görüş {}, {} sn",
                    runId, perPod, takeoverChunks, secondOpinionDomains,
                    (monoMs.getAsLong() - createdMono) / 1000L);
            return new Gathered(runId, list, null, n);
        }

        private Gathered aborted(String outcome) {
            abortOutcome = outcome;
            return new Gathered(runId, List.of(), outcome, measured());
        }

        int measured() {
            int k = 0;
            for (Map<String, Object> m : results) if (m != null) k++;
            return k;
        }

        // ── Bekleme döngüsü ─────────────────────────────────────────────────────────────────────────────────────────

        /**
         * {@code nos} parçalarının hepsi yerleşene (sonucu toplandı / yerelde ölçüldü / ikinci görüşte eskisi kaldı) kadar bekler.
         *
         * @return null = tamam; aksi bırakma sonucu
         */
        private String waitFor(Set<Integer> nos, boolean secondOpinion, long phaseStart) {
            while (true) {
                if (closing()) return CertSweepStore.OUTCOME_SHUTDOWN;
                if (!req.lockHeld().getAsBoolean()) return CertSweepStore.OUTCOME_LOCK_LOST;
                if (beatLost) return CertSweepStore.OUTCOME_LOCK_LOST;      // yerel ölçüm sırasındaki nabız 0 satır buldu
                if (localEntries.isEmpty() && settled.containsAll(nos)) return null;
                long now = monoMs.getAsLong();
                if (!storeFailed && now - lastHeartbeat >= CertChunking.COORDINATOR_HEARTBEAT_MS) {
                    try {
                        boolean alive = store.heartbeat(runId);
                        storeOk();
                        if (!alive) return CertSweepStore.OUTCOME_LOCK_LOST;
                        lastHeartbeat = now;
                    } catch (RuntimeException e) {
                        storeError(e, now);
                    }
                }
                if (!storeFailed) {
                    try {
                        List<ChunkState> states = store.chunkStates(runId);
                        storeOk();
                        observe(states, nos, now);
                    } catch (RunnerFailure rf) {
                        throw rf;
                    } catch (RuntimeException e) {
                        storeError(e, now);
                    }
                }
                if (storeFailed) {
                    for (int no : nos) takeover(no, null, false, "store");
                } else if (now - lastProgress >= Math.max(1L, req.maxWaitMs())) {
                    log.warn("Parçalı tarama {} sn ilerlemesiz — kalan parçaları koordinatör ölçüyor (runId={})",
                            Math.max(1L, req.maxWaitMs()) / 1000L, runId);
                    for (int no : nos) takeover(no, lastState.get(no), true, "progress");
                    lastProgress = now;
                } else if (!secondOpinion && !anyClaimSeen && !noWorkerFired && now - createdMono >= NO_WORKER_MS) {
                    noWorkerFired = true;
                    log.warn("Parçalı taramada {} sn içinde hiçbir parça sahiplenilmedi (çalışan işçi yok) — koordinatör "
                            + "hepsini ölçüyor (runId={})", NO_WORKER_MS / 1000L, runId);
                    for (int no : nos) takeover(no, lastState.get(no), true, "no_worker");
                } else if (secondOpinion && now - phaseStart >= CertChunking.SECOND_OPINION_WAIT_MS) {
                    secondOpinionDeadline(nos);
                }
                if (!storeFailed && !switchOn()) takeOverStalled(nos, now);
                if (!localEntries.isEmpty()) {
                    String abort = measureLocal();
                    if (abort != null) return abort;
                    lastProgress = monoMs.getAsLong();
                    continue;
                }
                if (settled.containsAll(nos)) return null;
                sleep(CertChunking.COORDINATOR_WAIT_POLL_MS);
            }
        }

        private boolean closing() {
            if (req.stopping() != null && req.stopping().getAsBoolean()) return true;
            try {
                return runner.closing();
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** Anahtar hâlâ açık mı (bellek; okunamazsa açık sayılır — devralma yalnız kesin "kapalı"da). */
        private boolean switchOn() {
            try {
                return chunking.enabled();
            } catch (RuntimeException e) {
                return true;
            }
        }

        /**
         * Anahtar tur sürerken KAPATILDI (SW-L2): işçiler artık boşta kalabilir — durumu {@value #SWITCH_OFF_STALL_MS} ms
         * değişmeyen her açık parça (bırakılmış / kirası dolmuş / hiç alınmamış) koordinatöre alınır. Kirası yenilenen canlı
         * parça değişir, alınmaz.
         */
        private void takeOverStalled(Set<Integer> nos, long now) {
            if (!switchOffSeen) {
                switchOffSeen = true;
                log.info("Parçalı tarama anahtarı tur sürerken kapatıldı — {} sn durumu değişmeyen parçaları koordinatör ölçecek "
                        + "(runId={})", SWITCH_OFF_STALL_MS / 1000L, runId);
            }
            for (int no : nos) {
                if (settled.contains(no) || queued.contains(no)) continue;
                Long changed = changedAt.get(no);
                if (changed != null && now - changed >= SWITCH_OFF_STALL_MS) {
                    ChunkState c = lastState.get(no);
                    takeover(no, c, c != null, "switch_off");
                }
            }
        }

        /**
         * İkinci görüşün 30 sn noktası. Kaçınılan işçisi başka pod olan parçalardan HİÇ alınmamışlar koordinatöre geçer (kendisi
         * ölçer). Kaçınılan işçisi bu pod olanlar (koordinatör aykırı, H-1): o ana dek HİÇBİRİ sahiplenilmediyse özgün sonuçlar
         * kalır; biri sahiplenildiyse beklenir (canlı sahiplenilmiş parça asla iptal edilmez).
         */
        private void secondOpinionDeadline(Set<Integer> nos) {
            for (int no : nos) {
                ChunkState c = lastState.get(no);
                if (selfSo.contains(no)) {
                    if (!selfSoClaimSeen) abandonSelfSecondOpinion("unclaimed");
                } else if (c == null || (c.pending() && c.attempts() == 0)) {
                    takeover(no, c, c != null, "second_opinion");
                }
            }
        }

        private void observe(List<ChunkState> states, Set<Integer> nos, long now) {
            if (states == null) return;
            long storeNow = store.nowMs();
            lastStoreNow = storeNow;
            List<Long> toRead = new ArrayList<>();
            for (ChunkState c : states) {
                if (c == null || !specs.containsKey(c.chunkNo())) continue;
                int no = c.chunkNo();
                idByNo.put(no, c.id());
                noById.put(c.id(), no);
                lastState.put(no, c);
                String f = fp(c.state(), c.attempts(), c.claimedMs(), c.leaseUntilMs());
                if (!f.equals(fingerprint.put(no, f))) {
                    lastProgress = now;
                    changedAt.put(no, now);
                }
                if (c.attempts() > 0 || !c.pending()) anyClaimSeen = true;
                if (selfSo.contains(no) && (c.attempts() > 0 || c.claimed() || c.done())) selfSoClaimSeen = true;
                maxAttempts.merge(no, c.attempts(), Math::max);
                if (!nos.contains(no) || settled.contains(no) || queued.contains(no)) continue;
                if (c.done()) toRead.add(c.id());
                else if (c.cancelled()) takeover(no, c, false, "cancelled");
                else if (c.lost(storeNow)) takeover(no, c, true, "lost");
                else if (leaseLongExpired(c, storeNow)) takeover(no, c, true, "expired");
            }
            if (!toRead.isEmpty()) collect(toRead);
        }

        /**
         * Kirası {@code lease-ms}'ten uzun süredir dolmuş ve kimsenin yeniden sahiplenmediği parça (CR-L3) — deneme sayısı ne
         * olursa olsun kayıp sayılır (tek denemeli parçayı yeniden alacak işçi yoksa 900 sn beklenmez).
         */
        private boolean leaseLongExpired(ChunkState c, long storeNow) {
            return c.claimed() && c.leaseUntilMs() != null && storeNow - c.leaseUntilMs() > leaseMs;
        }

        /**
         * Parçayı koordinatöre alır: gerekirse iptal eder (0 satır = bu arada DONE oldu → sonraki turda toplanır; iptal art arda
         * {@value #STORE_STRIKES_TAKEOVER} kez yazılamazsa yine de alır), girdilerini yerel ölçüm kuyruğuna koyar. Kaçınılan işçisi
         * bu pod olan ikinci görüş parçası yerelde ÖLÇÜLMEZ (koordinatör aykırı) — ikinci görüşün tamamı bırakılır, özgün
         * sonuçlar kalır (H-1).
         */
        private void takeover(int no, ChunkState c, boolean cancel, String reason) {
            if (settled.contains(no) || queued.contains(no)) return;
            CertChunkSpec spec = specs.get(no);
            if (spec == null) return;
            if (c != null && c.done()) return;                       // sonucu toplanacak
            if (selfSo.contains(no)) {
                abandonSelfSecondOpinion(reason);
                return;
            }
            if (cancel) {
                Long id = c != null ? Long.valueOf(c.id()) : idByNo.get(no);
                if (id != null) {
                    try {
                        boolean ok = store.cancel(runId, id);
                        storeOk();
                        cancelStrikes.remove(no);
                        if (!ok) return;                             // DONE oldu (ya da zaten iptal) → toplanır
                    } catch (RuntimeException e) {
                        storeError(e, monoMs.getAsLong());
                        if (!storeFailed) {
                            int k = cancelStrikes.merge(no, 1, Integer::sum);
                            if (k < STORE_STRIKES_TAKEOVER) return;  // sonraki turda yeniden denenir
                            log.info("Parça {} iptal edilemedi ({} deneme) — koordinatör kendisi ölçüyor (runId={})", no, k,
                                    runId);
                        }
                    }
                }
            }
            queued.add(no);
            if ("lost".equals(reason)) {
                log.info("Parça {} kayboldu ({} deneme) — koordinatör kendisi ölçüyor (runId={})", no,
                        c == null ? 0 : c.attempts(), runId);
            } else if ("expired".equals(reason)) {
                log.info("Parça {} kirası doldu, kimse yeniden almadı ({} deneme) — koordinatör kendisi ölçüyor (runId={})", no,
                        c == null ? 0 : c.attempts(), runId);
            }
            takeoverChunks++;
            takeovers.incrementAndGet();
            localEntries.addAll(spec.entries());
            localChunks.add(no);
            chunkTally.merge(me, 1, Integer::sum);
        }

        /**
         * Koordinatörün kendisi aykırıyken ikinci görüşü BIRAKIR (H-1): hiçbir kendi-ikinci-görüş sonucu kullanılmaz, özgün
         * sonuçların HEPSİ kalır (kısmi değişim yok). Henüz yerleşmemiş parçalar yerleşmiş sayılır; yalnız beklemede ya da
         * kirası dolmuş olanlar iptal edilir — canlı sahiplenilmiş parça iptal edilmez (tamamlaması koşu DECIDING'e geçince geç
         * kalır, sonucu kullanılmaz).
         */
        private void abandonSelfSecondOpinion(String reason) {
            if (!selfSoAbandoned) {
                selfSoAbandoned = true;
                log.debug("İkinci görüş (kaçınılan işçi bu pod) bırakıldı: {} (runId={})", reason, runId);
            }
            for (int no : selfSo) {
                if (settled.contains(no)) continue;
                ChunkState c = lastState.get(no);
                boolean idle = c != null && (c.pending() || (lastStoreNow != Long.MIN_VALUE && c.leaseExpired(lastStoreNow)));
                if (idle && !storeFailed) {
                    try {
                        store.cancel(runId, c.id());
                    } catch (RuntimeException e) {
                        log.debug("İkinci görüş parçası iptal edilemedi (runId={}): {}", runId, e.getClass().getSimpleName());
                    }
                }
                queued.add(no);
                settled.add(no);
            }
        }

        /** Yeni DONE parçaların sonuçları — en çok {@value #RESULT_READ_BATCH} parçalık okumalar; önceden okunamayan tek tek. */
        private void collect(List<Long> ids) {
            List<Long> healthy = new ArrayList<>();
            List<Long> suspect = new ArrayList<>();
            for (Long id : ids) {
                Integer no = noById.get(id);
                (no != null && readStrikes.containsKey(no) ? suspect : healthy).add(id);
            }
            for (int from = 0; from < healthy.size(); from += RESULT_READ_BATCH) {
                readBatch(healthy.subList(from, Math.min(healthy.size(), from + RESULT_READ_BATCH)));
            }
            for (Long id : suspect) readBatch(List.of(id));
        }

        private void readBatch(List<Long> ids) {
            Map<Long, String> json;
            try {
                json = store.readResults(runId, ids);
                storeOk();
            } catch (RuntimeException e) {
                storeError(e, monoMs.getAsLong());
                for (Long id : ids) strike(id);
                return;
            }
            if (json == null) json = Map.of();
            for (Long id : ids) {
                Integer no = noById.get(id);
                if (no == null || settled.contains(no) || queued.contains(no)) continue;
                String j = json.get(id);
                if (j == null) {                                     // DONE göründü ama sonucu gelmedi — yeniden denenir
                    strike(id);
                    continue;
                }
                readStrikes.remove(no);
                accept(no, j);
            }
        }

        /** Sonuç okuma başarısızlığı; art arda {@value #STORE_STRIKES_TAKEOVER} kez → parça yerelde ölçülür (CR-L2). */
        private void strike(Long id) {
            Integer no = noById.get(id);
            if (no == null || settled.contains(no) || queued.contains(no)) return;
            int k = readStrikes.merge(no, 1, Integer::sum);
            if (k >= STORE_STRIKES_TAKEOVER) {
                log.info("Parça {} sonucu {} kez okunamadı — koordinatör kendisi ölçüyor (runId={})", no, k, runId);
                takeover(no, null, false, "unreadable");
            }
        }

        private void accept(int no, String json) {
            CertChunkSpec spec = specs.get(no);
            ChunkState state = lastState.get(no);
            String worker = state == null || state.worker() == null || state.worker().isBlank() ? "?" : state.worker();
            boolean secondOpinion = spec.avoidWorker() != null;
            boolean self = selfSo.contains(no);
            Map<Integer, ChunkCompletion.Result> byIndex = new HashMap<>();
            for (ChunkCompletion.Result r : ChunkCompletion.decodeResults(json)) byIndex.putIfAbsent(r.index(), r);
            int used = 0;
            boolean complete = true;
            for (CertChunkSpec.Entry e : spec.entries()) {
                ChunkCompletion.Result r = byIndex.get(e.index());
                boolean ok = r != null && r.usable() && sameDomain(e, r.result());
                if (self) {
                    if (ok) {
                        selfStash.put(e.index(), r.result());
                        selfStashWorker.put(e.index(), worker);
                    } else {
                        complete = false;
                    }
                } else if (ok) {
                    put(e.index(), r.result(), worker);
                    used++;
                } else if (!secondOpinion) {
                    localEntries.add(e);                             // kullanılamaz sonuç → koordinatör yeniden ölçer (L-4)
                    remeasured.incrementAndGet();
                }                                                    // ikinci görüşte kullanılamazsa eski sonuç kalır
            }
            settled.add(no);
            if (self) {
                selfChunkWorker.put(no, worker);
                if (!complete) abandonSelfSecondOpinion("unusable");
                return;
            }
            if (used > 0) chunkTally.merge(worker, 1, Integer::sum);
        }

        /** Sonuç bu girdinin alan adına mı ait (sağlamlık: biçim / işçi hatası sonucu yanlış alan adına yazdırmasın). */
        private boolean sameDomain(CertChunkSpec.Entry e, Map<String, Object> result) {
            return result != null && e.domain() != null && e.domain().equals(result.get("domain"));
        }

        private void put(int index, Map<String, Object> result, String worker) {
            result.putIfAbsent("run_id", runId);
            results.set(index, result);
            by[index] = worker;
        }

        /**
         * Yerel ölçüm kuyruğunu koordinatörün iş parçacığında, klasik toplamayla ölçer. Ölçüm sürerken koşunun nabzı ayrı bir
         * iş parçacığından atılır (CR-L4) — işçilerin sahiplenme kapısı ({@code heartbeat_ms ≥ şimdi − 60 sn}) açık kalır.
         */
        private String measureLocal() {
            Map<Integer, CertChunkSpec.Entry> unique = new TreeMap<>();
            for (CertChunkSpec.Entry e : localEntries) unique.putIfAbsent(e.index(), e);
            localEntries.clear();
            List<CertChunkSpec.Entry> batch = new ArrayList<>(unique.values());
            List<Map<String, Object>> out;
            Beat beat = startBeat();
            try {
                out = runner.checkDomains(CertChunkSpec.rowsOf(batch), runId);
            } catch (RuntimeException | Error e) {
                throw new RunnerFailure(e);
            } finally {
                stopBeat(beat);
            }
            if (out == null || out.size() < batch.size()) return CertSweepStore.OUTCOME_SHUTDOWN;   // kapanışta erken durdu
            for (int k = 0; k < batch.size(); k++) put(batch.get(k).index(), out.get(k), me);
            settled.addAll(localChunks);
            localChunks.clear();
            return null;
        }

        private Beat startBeat() {
            if (storeFailed) return null;                            // depo düşükken nabız denenmez
            try {
                return beatTimer.start(CertChunking.COORDINATOR_HEARTBEAT_MS, () -> {
                    if (closing()) return;
                    try {
                        if (!store.heartbeat(runId)) beatLost = true;
                    } catch (RuntimeException e) {
                        log.debug("Parçalı koşu nabzı atılamadı (runId={}): {}", runId, e.getClass().getSimpleName());
                    }
                });
            } catch (RuntimeException e) {
                log.debug("Yerel ölçüm nabzı başlatılamadı (runId={}): {}", runId, e.getClass().getSimpleName());
                return null;
            }
        }

        private void stopBeat(Beat beat) {
            if (beat == null) return;
            try {
                if (beat.stop()) interrupted = true;
            } catch (RuntimeException e) {
                log.debug("Yerel ölçüm nabzı durdurulamadı (runId={}): {}", runId, e.getClass().getSimpleName());
            }
        }

        // ── Aykırı pod (§2.6) ───────────────────────────────────────────────────────────────────────────────────────

        private String secondOpinion() {
            if (storeFailed) return null;
            int minErrors;
            double threshold;
            try {
                minErrors = req.minErrors().getAsInt();
                threshold = req.rateThreshold().getAsDouble();
            } catch (RuntimeException e) {
                return null;
            }
            Map<String, List<Integer>> byWorker = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) byWorker.computeIfAbsent(by[i], k -> new ArrayList<>()).add(i);
            Map<String, Integer> ne = new HashMap<>();
            int totalNe = 0;
            for (Map.Entry<String, List<Integer>> e : byWorker.entrySet()) {
                int c = 0;
                for (int i : e.getValue()) if (outlierFailure(results.get(i))) c++;
                ne.put(e.getKey(), c);
                totalNe += c;
            }
            List<CertChunkSpec> chunks = new ArrayList<>();
            List<SoGroup> groups = new ArrayList<>();
            int size = spec.chunkSize();
            int no = nextChunkNo;
            for (Map.Entry<String, List<Integer>> e : byWorker.entrySet()) {
                String w = e.getKey();
                if (w == null || "?".equals(w)) continue;
                int nw = e.getValue().size();
                int nev = ne.get(w);
                int otherN = n - nw;
                int otherNe = totalNe - nev;
                if (!outlier(nw, nev, otherN, otherNe, minErrors, threshold)) continue;
                if (w.isBlank() || w.length() > CertChunkSpec.MAX_WORKER_CHARS) continue;
                List<CertChunkSpec.Entry> again = new ArrayList<>();
                List<Integer> idx = new ArrayList<>();
                for (int i : e.getValue()) {
                    if (outlierFailure(results.get(i))) {
                        again.add(entryOf(i));
                        idx.add(i);
                    }
                }
                for (int from = 0; from < again.size(); from += size) {
                    chunks.add(new CertChunkSpec(no++, again.subList(from, Math.min(again.size(), from + size)), w));
                }
                groups.add(new SoGroup(w, nw, nev, otherN, otherNe, idx));
            }
            if (chunks.isEmpty()) return null;
            try {
                if (store.addChunks(runId, chunks) <= 0) return null;   // eklenemedi → eski sonuçlar kalır
                storeOk();
            } catch (RuntimeException e) {
                log.warn("İkinci görüş parçaları yazılamadı — mevcut sonuçlar geçerli (runId={}): {}", runId,
                        e.getClass().getSimpleName());
                return null;
            }
            nextChunkNo = no;
            for (SoGroup g : groups) {
                soGroups.add(g);
                for (int i : g.indices()) soOriginal.put(i, results.get(i));
                secondOpinionDomains += g.indices().size();
                secondOpinions.addAndGet(g.indices().size());
                log.info("İkinci görüş: {} pod'unun parçalarında ağ hatası oranı %{} (diğerleri %{}) — {} alan adı başka pod'dan "
                        + "yeniden ölçülüyor (runId={})", podOf(g.worker()), pct(g.nev(), g.nw()), pct(g.otherNe(), g.otherN()),
                        g.indices().size(), runId);
            }
            Set<Integer> nos = new LinkedHashSet<>();
            for (CertChunkSpec c : chunks) {
                register(c);
                nos.add(c.chunkNo());
            }
            long start = monoMs.getAsLong();
            lastProgress = start;
            String abort = waitFor(nos, true, start);
            if (abort != null) return abort;
            applySelfSecondOpinion();
            logSecondOpinionOutcome();
            return null;
        }

        /**
         * Koordinatör aykırıyken toplanan ikinci görüş sonuçları — HEPSİ-YA-DA-HİÇBİRİ (H-1): yeniden dağıtılan HER alan adının
         * kullanılabilir yeni sonucu varsa hepsi yerine konur, aksi hâlde özgün sonuçların hepsi kalır (kısmi değişim turu toplu
         * kesinti eşiğinin altına çekebilirdi).
         */
        private void applySelfSecondOpinion() {
            if (selfSo.isEmpty()) return;
            boolean complete = !selfSoAbandoned;
            if (complete) {
                for (int no : selfSo) {
                    for (CertChunkSpec.Entry e : specs.get(no).entries()) {
                        if (!selfStash.containsKey(e.index())) {
                            complete = false;
                            break;
                        }
                    }
                    if (!complete) break;
                }
            }
            if (!complete) return;                                   // özgün sonuçlar kalır
            for (Map.Entry<Integer, Map<String, Object>> en : selfStash.entrySet()) {
                put(en.getKey(), en.getValue(), selfStashWorker.getOrDefault(en.getKey(), "?"));
            }
            for (String w : selfChunkWorker.values()) chunkTally.merge(w, 1, Integer::sum);
            selfSoApplied = true;
        }

        /**
         * İkinci görüşün SONUCU (CR-L5): aykırı pod başına yeniden ölçülen / başarılı / yine hatalı. Çıkış eşliği (P1) uyarısı
         * (WARN, pod başına saatte bir) yalnız yeniden ölçümlerin ÇOĞU başka pod'dan başarılıysa; hatalar başka pod'dan da
         * görülüyorsa (gerçek kesinti) ya da ikinci görüş tamamlanamadıysa INFO.
         */
        private void logSecondOpinionOutcome() {
            for (SoGroup g : soGroups) {
                int total = g.indices().size();
                int replaced = 0;
                int ok = 0;
                for (int i : g.indices()) {
                    Map<String, Object> cur = results.get(i);
                    if (cur != soOriginal.get(i)) {
                        replaced++;
                        if (!outlierFailure(cur)) ok++;
                    }
                }
                int failedAgain = replaced - ok;
                String pod = podOf(g.worker());
                String x = pct(g.nev(), g.nw());
                String y = pct(g.otherNe(), g.otherN());
                if (replaced == 0) {
                    int measuredElsewhere = me.equals(g.worker()) ? selfStash.size() : 0;
                    log.info("İkinci görüş tamamlanamadı: {} pod'unun {} alan adından yeniden ölçülen {} — özgün sonuçların "
                            + "hepsi geçerli (runId={})", pod, total, measuredElsewhere, runId);
                } else if (ok * 2 > replaced) {
                    if (outlierWarnDue()) {
                        log.warn("Sertifika taraması: {} pod'unun parçalarında ağ hatası oranı %{} (diğerleri %{}) — {} alan adı "
                                + "başka pod'dan yeniden ölçüldü: {} başarılı, {} yine hatalı; çıkış eşliğini (P1) doğrulayın",
                                pod, x, y, replaced, ok, failedAgain);
                    } else {
                        log.info("İkinci görüş: {} pod'unun {} alan adı başka pod'dan yeniden ölçüldü: {} başarılı, {} yine "
                                + "hatalı — çıkış eşliği şüpheli (runId={})", pod, replaced, ok, failedAgain, runId);
                    }
                } else {
                    log.info("İkinci görüş: {} pod'unun {} alan adı başka pod'dan yeniden ölçüldü: {} başarılı, {} yine hatalı "
                            + "— hatalar başka pod'dan da görülüyor (gerçek kesinti olası), sonuçlar geçerli (runId={})",
                            pod, replaced, ok, failedAgain, runId);
                }
            }
        }

        private boolean outlierWarnDue() {
            long now = monoMs.getAsLong();
            long last = outlierWarnAt.get();
            if (last != Long.MIN_VALUE && now - last < OUTLIER_WARN_EVERY_MS) return false;
            return outlierWarnAt.compareAndSet(last, now);
        }

        private CertChunkSpec.Entry entryOf(int index) {
            CertChunkSpec.Entry e = entries[index];
            if (e == null) throw new IllegalStateException("girdi yok: " + index);
            return e;
        }

        /**
         * Aykırı pod kuralının saydığı hata (CR-M-1): toplu kesinti oranına giren ağ hatası ({@code runner.networkErrorCount} —
         * karar oranı DEĞİŞMEZ) YA DA çözülemeyen ad ({@code status=error ∧ error_class=DNS ∧ unresolvable_host}) — bozuk çözücülü
         * bir pod'un payı da başka pod'dan yeniden ölçülsün.
         */
        private boolean outlierFailure(Map<String, Object> r) {
            if (r == null) return false;
            if (runner.networkErrorCount(List.of(r)) > 0) return true;
            return "error".equals(r.get("status")) && "DNS".equals(r.get("error_class"))
                    && Boolean.TRUE.equals(r.get(UNRESOLVABLE_HOST));
        }

        private boolean networkError(Map<String, Object> r) {
            return r != null && runner.networkErrorCount(List.of(r)) > 0;
        }

        // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────────────────────────

        private List<CertSweepStatus.WorkerTally> tallies() {
            Map<String, int[]> t = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                int[] a = t.computeIfAbsent(by[i] == null ? "?" : by[i], k -> new int[2]);
                a[0]++;
                if (networkError(results.get(i))) a[1]++;
            }
            List<CertSweepStatus.WorkerTally> out = new ArrayList<>();
            for (Map.Entry<String, int[]> e : t.entrySet()) {
                out.add(new CertSweepStatus.WorkerTally(e.getKey(), chunkTally.getOrDefault(e.getKey(), 0), e.getValue()[0],
                        e.getValue()[1]));
            }
            out.sort(CertSweepStatus.WorkerTally.ORDER);
            return out;
        }

        private void storeOk() {
            storeFailingSince = -1L;
        }

        private void storeError(RuntimeException e, long now) {
            if (e instanceof RunnerFailure rf) throw rf;
            if (storeFailingSince < 0) storeFailingSince = now;
            log.debug("Parçalı tarama deposu okunamadı (runId={}): {}", runId, e.getClass().getSimpleName());
            if (!storeFailed && now - storeFailingSince >= CertChunking.STORE_FAILURE_LOCAL_MS) {
                storeFailed = true;
                log.warn("Parçalı tarama deposu {} sn okunamadı — toplanmamış parçaları koordinatör ölçüyor (runId={}): {}",
                        CertChunking.STORE_FAILURE_LOCAL_MS / 1000L, runId, e.getClass().getSimpleName());
            }
        }

        private void sleep(long ms) {
            try {
                pause.sleep(ms);
            } catch (InterruptedException e) {
                interrupted = true;                                   // klasik bekleme gibi: sürer, bayrak sonunda geri konur
            }
        }
    }

    /** {@code CertificateCheckerService.UNRESOLVABLE_HOST} (aynı anahtar; bağımlılık kurmadan). */
    static final String UNRESOLVABLE_HOST = "unresolvable_host";

    /**
     * Aykırı pod kuralı (§2.6): pod'un ağ hatası ≥ {@code minErrors} ∧ oranı ≥ eşik ∧ diğerlerinin birleşik oranı ≤ eşik / 2 ∧
     * diğerlerinin ≥ 2 × {@code minErrors} sonucu var.
     */
    static boolean outlier(int nw, int nev, int otherN, int otherNe, int minErrors, double threshold) {
        if (nw <= 0 || nev <= 0 || nev < minErrors) return false;
        if ((double) nev / nw < threshold) return false;
        if (otherN <= 0 || otherN < 2L * Math.max(0, minErrors)) return false;
        return (double) otherNe / otherN <= threshold / 2.0;
    }

    private static String pct(int part, int whole) {
        return String.format(Locale.ROOT, "%.0f", 100.0 * part / Math.max(1, whole));
    }

    private static String fp(String state, int attempts, Long claimedMs, Long leaseUntilMs) {
        return state + '|' + attempts + '|' + claimedMs + '|' + leaseUntilMs;
    }

    private static String podOf(String worker) {
        String p = ClusterHealthStore.podOf(worker);
        return p == null ? "?" : p;
    }
}
