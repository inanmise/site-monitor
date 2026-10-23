package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TlsProfileRepository;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.SchedulerService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TLS profili yoklama İŞİ ve not karşılaştırma turu (2026-10-10).
 *
 * <p><b>Saatlik süpürmenin DIŞINDA, düşük sıklıkta.</b> Her aktif ağ envanter ucu {@code site.monitor.tls-profile.interval-hours}
 * (24) saatte bir yoklanır; başarısız yoklama {@code retry-failed-hours} (6) sonra yeniden denenir. Tur
 * {@code site.monitor.tls-profile.tick-ms} (10 dk) aralıkla çalışır ve vadesi gelenlerden en çok {@code batch-size} (40)
 * tanesini — en eskiden başlayarak — yoklar; yük güne yayılır, tek seferde tüm envanter taranmaz. Vade bilgisi
 * {@code tls_profiles.probed_at}'tadır (veritabanı) → yeniden başlatma ya da pod değişimi turu sıfırlamaz.
 *
 * <p><b>Küme kuralları.</b> 2026-10-23'ten (çok pod R3-1 3e) beri tur HERHANGİ bir pod'da koşar — kümede tık aralığı başına
 * TEK tur ({@code round:tls-profile} iki aşamalı tur kaydı) ve kendi {@code scheduler_lock} anahtarı ({@value #LOCK_NAME})
 * altında: iki pod aynı anda yoklamaz, aralık başına bir pod yoklar. Tarama lideri başka canlı pod varken turu bırakır
 * (yük liderden uzaklaşır); eskiden tur yalnız liderde koşuyordu. Elle "Şimdi kontrol et" bu yoklamayı TETİKLEMEZ; ayrı bir
 * "TLS profilini yeniden tara" ucu vardır ({@link #rescan}).
 *
 * <p><b>Sınırlar.</b> Ayrı ve küçük bir iş havuzu ({@code concurrency}, vars. 4 iş parçacığı, kuyruk = tur boyutu); tur
 * {@code tick-budget-ms} (vars. 8 dk) içinde biter — kalan işler iptal edilir ve sonraki tura kalır. Her yoklamanın kendi
 * zaman aşımı vardır ({@link TlsProfileProbeService}).
 *
 * <p>Turun sonunda notlar son bilinenle karşılaştırılır ({@link TlsGradeService#reconcile}); profil ya da not değiştiyse
 * sertifika liste önbellekleri boşaltılır (kart rozetleri güncellenir).
 */
@Slf4j
@Service
public class TlsProfileJobService {

    /** {@code scheduler_lock} anahtarı — diğer işlerden ayrı. */
    public static final String LOCK_NAME = "tls-profile";

    private final CertificateInventoryRepository inventoryRepo;
    private final TlsProfileRepository profileRepo;
    private final TlsProfileProbeService probeService;
    private final TlsGradeService gradeService;

    /** Liste önbelleği + not hesaplanmış satırlar — döngüsel bağımlılık olmasın diye tembel. */
    @Autowired
    @Lazy
    private CertificateService certificateService;

    /** Düşüş → takım bildirimi (2026-10-11) — isteğe bağlı: birim testinde yok. */
    @Autowired(required = false)
    @Lazy
    private TlsGradeDropNotifier dropNotifier;

    void setDropNotifier(TlsGradeDropNotifier n) { this.dropNotifier = n; }

    /** Tur kaydı + dağıtık kilit + yan iş yerleşimi — isteğe bağlı: birim testinde yok (her çağrı yerel koşar). */
    @Autowired(required = false)
    @Lazy
    private SchedulerService schedulerService;

    @Value("${site.monitor.tls-profile.enabled:true}")
    boolean enabled = true;
    @Value("${site.monitor.tls-profile.interval-hours:24}")
    int intervalHours = 24;
    @Value("${site.monitor.tls-profile.retry-failed-hours:6}")
    int retryFailedHours = 6;
    @Value("${site.monitor.tls-profile.batch-size:40}")
    int batchSize = 40;
    @Value("${site.monitor.tls-profile.concurrency:4}")
    int concurrency = 4;
    @Value("${site.monitor.tls-profile.tick-budget-ms:480000}")
    long tickBudgetMs = 480_000L;

    private volatile ThreadPoolExecutor executor;

    public TlsProfileJobService(CertificateInventoryRepository inventoryRepo, TlsProfileRepository profileRepo,
                                TlsProfileProbeService probeService,
                                TlsGradeService gradeService) {
        this.inventoryRepo = inventoryRepo;
        this.profileRepo = profileRepo;
        this.probeService = probeService;
        this.gradeService = gradeService;
    }

    void setCertificateService(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    void setSchedulerService(SchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    /**
     * Tur özeti (log + test). {@code stopped} = pod kapanırken tur yarıda bırakıldı (FX-SIDE #5): biten yoklamalar kaydedildi,
     * not karşılaştırması / düşüş bildirimi yapılmadı, tur tamamlanmaz (koşu kirası dolunca yeniden koşar).
     */
    public record TickResult(int due, int probed, int failed, TlsGradeService.ReconcileResult grades, boolean stopped) {
        public TickResult(int due, int probed, int failed, TlsGradeService.ReconcileResult grades) {
            this(due, probed, failed, grades, false);
        }
    }

    /**
     * Yoklamaları beklerken kapanış / kesme / tur bütçesi bu aralıkla denetlenir (ms) — kapanış başlayınca tur en geç bu kadar
     * sonra döner (FX-SIDE #5).
     */
    static final long STOP_POLL_MS = 200L;

    /**
     * Turun küme kaydı ({@code round:tls-profile}, iki aşamalı; 2026-10-23, çok pod R3-1 3e) — KALICI ad. Aralık = tık
     * aralığı: kümede aralık başına bir tur.
     */
    public static final String ROUND_NAME = "tls-profile";

    /** Tık / tur aralığı (ms) — {@code @Scheduled} ile aynı ayar. */
    @Value("${site.monitor.tls-profile.tick-ms:600000}")
    long tickMs = 600_000L;

    /**
     * Zamanlanmış tık (R3-1 3e, 2026-10-23): artık yalnız tarama liderinde DEĞİL, herhangi bir pod'da koşar — kümede aralık
     * başına TEK tur ({@value #ROUND_NAME} iki aşamalı tur kaydı, kilit {@value #LOCK_NAME} altında). Tarama lideri, turu son
     * 2 aralıkta başka pod aldıysa bırakır ({@code SchedulerService.leaderYieldsRound}); gövde pod'un yan iş koşucusunda.
     * Kesilen (kapanış) ya da hata veren tur tamamlanmaz → koşu kirası dolunca yeniden koşar. Yoklama çıkışı sertifika
     * taramalarıyla aynıdır (onlar da R1'den beri her pod'da koşar).
     */
    @Scheduled(fixedDelayString = "${site.monitor.tls-profile.tick-ms:600000}",
               initialDelayString = "${site.monitor.tls-profile.initial-delay-ms:420000}")
    public void scheduledTick() {
        if (!enabled) return;
        SchedulerService s = schedulerService;
        if (s == null) {
            tickAndLog();
            return;
        }
        if (s.leaderYieldsRound(ROUND_NAME, tickMs)) return;
        Runnable body = () -> s.runWithSchedulerLock(LOCK_NAME, () -> claimedTick(s));
        // FX-SIDE #1: sabit gecikmeli — koşucu gövdeyi bu pod'daki son turun BİTİŞİNDEN en erken bir tık aralığı sonra
        // başlatır (tek pod'da kadans 20.167.0'daki gibi "bitiş + tick-ms"; birikmiş vade varken saatteki tur sayısı artmaz)
        if (!s.offloadSideJob(com.sitemonitor.service.schedule.SideJob.fixedDelay(ROUND_NAME, tickMs, body))) body.run();
    }

    /** Kilit altında: tur bu pod'a düştüyse tık; normal bittiyse (kesilmediyse, kapanışta bırakılmadıysa) tur tamamlanır. */
    private void claimedTick(SchedulerService s) {
        if (!s.claimRound(ROUND_NAME, tickMs)) return;
        boolean done = false;
        try {
            TickResult r = tickAndLog();
            done = !Thread.currentThread().isInterrupted() && !r.stopped();
        } finally {
            s.finishSideRound(ROUND_NAME, done);
        }
    }

    private TickResult tickAndLog() {
        TickResult r = runTick(Instant.now());
        if (r.probed() > 0 || r.grades().changed()) {
            log.info("TLS profil turu: {} vadesi gelen, {} yoklandı ({} başarısız); not: {} düşüş, {} yükseliş, {} bilgi değişimi",
                    r.due(), r.probed(), r.failed(), r.grades().drops(), r.grades().rises(), r.grades().refined());
        }
        if (r.stopped()) log.info("TLS profil turu kapanış başladığı için yarıda bırakıldı — tur tamamlanmadı, sonraki tur sürdürür");
        return r;
    }

    /**
     * Kapanış başladı mı (FX-SIDE #5): {@code SchedulerService.sideJobsStopping()} ({@code ContextClosedEvent} bayrağı) —
     * zamanlayıcı yoksa (birim testi) hiç. Okunamazsa "başlamadı" (tur bugünkü gibi sürer).
     */
    private boolean stopRequested() {
        SchedulerService s = schedulerService;
        if (s == null) return false;
        try {
            return s.sideJobsStopping();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Tek tur — çağıran kilidi tutar. Paket-özel: test doğrudan çağırır. */
    TickResult runTick(Instant now) {
        if (stopRequested()) {
            // FX-SIDE #5: kapanış başladıysa yeni yoklama turu başlamaz (koşucu onShutdown'a vardığında boşta olsun)
            return new TickResult(0, 0, 0, new TlsGradeService.ReconcileResult(0, 0, 0, 0), true);
        }
        List<CertificateInventory> active = networkInventory();
        Map<String, TlsProfile> profiles = new HashMap<>();
        if (!active.isEmpty()) {
            for (TlsProfile p : profileRepo.findByDomainIn(active.stream().map(CertificateInventory::getDomain).toList())) {
                profiles.put(p.getDomain(), p);
            }
        }
        List<CertificateInventory> due = dueEndpoints(active, profiles, now);
        boolean[] stoppedSink = {false};
        List<TlsProfile> results = probeAll(due, stoppedSink);
        int failed = 0;
        for (TlsProfile p : results) {
            try {
                profileRepo.save(p);
            } catch (Exception e) {
                log.warn("TLS profili kaydedilemedi ({}): {}", p.getDomain(), e.toString());
            }
            if (TlsProfile.STATUS_FAILED.equals(p.getStatus()) || TlsProfile.STATUS_BLOCKED.equals(p.getStatus())) failed++;
        }
        if (Thread.currentThread().isInterrupted() || stoppedSink[0] || stopRequested()) {
            // Kapanış (2026-10-12; FX-SIDE #5: ContextClosedEvent bayrağı da): yarım yoklama turuyla not karşılaştırılmaz /
            // bildirim gönderilmez — sonraki tur yapar
            return new TickResult(due.size(), results.size(), failed, new TlsGradeService.ReconcileResult(0, 0, 0, 0), true);
        }
        // Karşılaştırma TAZE listeyle (2026-10-12): profil başka pod'da elle yeniden tarandıysa ya da lider değiştiyse bu
        // pod'un liste önbelleği eskidir — eski notla karşılaştırma sahte düşüş / yükseliş yazardı. Yalnız liste önbelleği
        // boşaltılır (kart ekleri vb. dokunulmaz); ilk okuma onu yeniden kurar.
        if (!results.isEmpty()) evict();
        else evictLatestList();
        TlsGradeService.ReconcileResult grades = reconcileAll(active);
        if (grades.changed()) evict();
        // Bekleyen düşüşler (bu turun karşılaştırmasından) takıma bildirilir — kilit altında, bir kez
        if (dropNotifier != null) dropNotifier.notifyPending();
        return new TickResult(due.size(), results.size(), failed, grades);
    }

    /** Aktif, ağdan kontrol edilen (elle yüklenmemiş) kayıtlar — alan adı başına bir. */
    List<CertificateInventory> networkInventory() {
        Map<String, CertificateInventory> byDomain = new LinkedHashMap<>();
        for (CertificateInventory inv : inventoryRepo.findByActiveTrueOrderByDomainAsc()) {
            if (inv.isManual() || inv.getDomain() == null || inv.getDomain().isBlank()) continue;
            byDomain.putIfAbsent(inv.getDomain(), inv);
        }
        return new ArrayList<>(byDomain.values());
    }

    /** Vadesi gelenler: hiç yoklanmamış önce, sonra en eski; en çok {@code batchSize}. */
    List<CertificateInventory> dueEndpoints(List<CertificateInventory> active, Map<String, TlsProfile> profiles, Instant now) {
        List<CertificateInventory> due = new ArrayList<>();
        for (CertificateInventory inv : active) if (isDue(inv, profiles.get(inv.getDomain()), now)) due.add(inv);
        due.sort(Comparator.comparing((CertificateInventory inv) -> {
            TlsProfile p = profiles.get(inv.getDomain());
            return p == null || p.getProbedAt() == null ? "" : p.getProbedAt();
        }).thenComparing(CertificateInventory::getDomain));
        int max = Math.max(1, batchSize);
        return due.size() > max ? new ArrayList<>(due.subList(0, max)) : due;
    }

    boolean isDue(CertificateInventory inv, TlsProfile p, Instant now) {
        if (p == null) return true;
        int port = inv.getPort() == null ? 443 : inv.getPort();
        if (p.getPort() == null || p.getPort() != port) return true;
        Instant at = parse(p.getProbedAt());
        if (at == null) return true;
        boolean failed = TlsProfile.STATUS_FAILED.equals(p.getStatus()) || TlsProfile.STATUS_BLOCKED.equals(p.getStatus());
        long hours = failed ? Math.max(1, Math.min(retryFailedHours, intervalHours)) : Math.max(1, intervalHours);
        return !at.isAfter(now.minus(hours, ChronoUnit.HOURS));
    }

    /** Sınırlı havuzda paralel yoklama; tur bütçesi dolunca kalanlar iptal (sonraki tura kalır). */
    List<TlsProfile> probeAll(List<CertificateInventory> due) {
        return probeAll(due, null);
    }

    /**
     * Sınırlı havuzda paralel yoklama; tur bütçesi dolunca kalanlar iptal (sonraki tura kalır). Beklerken her
     * {@value #STOP_POLL_MS} ms'de kapanış bayrağına ({@link #stopRequested}) ve kesmeye bakılır (FX-SIDE #5): kapanış başlayınca
     * bitmemiş yoklamalar iptal edilir (kesilir) ve biten sonuçlarla hemen dönülür — yan iş koşucusu {@code onShutdown}'a
     * vardığında boşta olur, ortak yok etme süresi ({@code ShutdownDeadline}) bildirim boşaltmalarına kalır.
     *
     * @param stoppedSink kapanış / kesme yüzünden bırakıldıysa {@code [0] = true} (null olabilir)
     */
    List<TlsProfile> probeAll(List<CertificateInventory> due, boolean[] stoppedSink) {
        if (due.isEmpty()) return List.of();
        List<Callable<TlsProfile>> tasks = new ArrayList<>(due.size());
        for (CertificateInventory inv : due) {
            int port = inv.getPort() == null ? 443 : inv.getPort();
            boolean useProxy = Boolean.TRUE.equals(inv.getUseProxy());
            tasks.add(() -> probeService.probe(inv.getDomain(), port, useProxy, TlsProfile.TRIGGER_SCHEDULED, null));
        }
        List<TlsProfile> out = new ArrayList<>();
        List<Future<TlsProfile>> futures = new ArrayList<>(tasks.size());
        ThreadPoolExecutor ex = executor(due.size());
        try {
            for (Callable<TlsProfile> t : tasks) futures.add(ex.submit(t));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            for (Future<TlsProfile> f : futures) f.cancel(true);
            log.warn("TLS profili havuzu dolu — tur atlandı: {}", e.toString());
            return out;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(10_000L, tickBudgetMs));
        boolean stopped = false;
        wait:
        for (Future<TlsProfile> f : futures) {
            while (!f.isDone()) {
                if (Thread.currentThread().isInterrupted() || stopRequested()) {
                    stopped = true;
                    break wait;
                }
                long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (leftMs <= 0) break wait;                       // tur bütçesi doldu (eski invokeAll zaman aşımı)
                try {
                    f.get(Math.min(STOP_POLL_MS, leftMs), TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    // yeniden denetle
                } catch (CancellationException | ExecutionException e) {
                    break;                                         // bitti (hata) — sonuç aşağıda düşer
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stopped = true;
                    break wait;
                }
            }
        }
        for (Future<TlsProfile> f : futures) {
            if (!f.isDone()) {
                f.cancel(true);
                continue;
            }
            try {
                TlsProfile p = f.get();
                if (p != null) out.add(p);
            } catch (CancellationException | ExecutionException e) {
                log.debug("TLS profili yoklaması tamamlanmadı: {}", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (stopped && stoppedSink != null && stoppedSink.length > 0) stoppedSink[0] = true;
        return out;
    }

    /** Liste satırlarının (önbellekli, not hesaplanmış) notlarını son bilinenle karşılaştırır. */
    TlsGradeService.ReconcileResult reconcileAll(List<CertificateInventory> active) {
        if (certificateService == null || active.isEmpty()) return new TlsGradeService.ReconcileResult(0, 0, 0, 0);
        Map<String, CertificateInventory> invByDomain = new HashMap<>();
        for (CertificateInventory inv : active) invByDomain.put(inv.getDomain(), inv);
        List<TlsGradeService.Evaluated> items = new ArrayList<>();
        try {
            for (CertificateDto row : certificateService.getAllLatest()) {
                CertificateInventory inv = invByDomain.get(row.getDomain());
                if (inv == null || row.getTlsGrade() == null) continue;
                items.add(new TlsGradeService.Evaluated(inv, row.getTlsGrade(),
                        row.getTlsGradeReasons() == null ? List.of() : row.getTlsGradeReasons()));
            }
            return gradeService.reconcile(items);
        } catch (Exception e) {
            log.warn("TLS notu karşılaştırması yapılamadı: {}", e.toString());
            return new TlsGradeService.ReconcileResult(0, 0, 0, 0);
        }
    }

    /**
     * Elle yeniden tarama (sertifika penceresi): tek ucu ŞİMDİ yoklar, kaydeder ve önbellekleri boşaltır (pencere yeni
     * notu hemen gösterir). Kapı (izin + takım kapsamı + hız sınırı) çağıranda. Elle yüklenen kayıt için çağrılmaz.
     *
     * <p>Not KARŞILAŞTIRILMAZ (2026-10-12): kilitsiz ve liste satırından farklı girdiyle (güvenlik bayrakları olmadan)
     * hesaplanan not, turun karşılaştırmasıyla yarışıp sahte düşüş / yükseliş çiftleri yazıyordu. Değişimi bir sonraki tur
     * ({@code tls-profile} kilidi altında, taze listeyle) kaydeder ve düşüşü bildirir.
     */
    public TlsProfile rescan(CertificateInventory inv, String actor) {
        int port = inv.getPort() == null ? 443 : inv.getPort();
        TlsProfile p = probeService.probe(inv.getDomain(), port, Boolean.TRUE.equals(inv.getUseProxy()),
                TlsProfile.TRIGGER_MANUAL, actor);
        profileRepo.save(p);
        evict();
        return p;
    }

    /** Spring önbellek yöneticisi — isteğe bağlı (birim testinde yok → tüm sertifika önbellekleri boşaltılır). */
    @Autowired(required = false)
    private org.springframework.cache.CacheManager cacheManager;

    void setCacheManager(org.springframework.cache.CacheManager m) { this.cacheManager = m; }

    /** Yalnız sertifika liste önbelleği ({@code cert-latest}). */
    private void evictLatestList() {
        try {
            org.springframework.cache.Cache c = cacheManager == null ? null : cacheManager.getCache("cert-latest");
            if (c != null) c.clear();
            else evict();
        } catch (Exception e) {
            log.debug("Sertifika liste önbelleği boşaltılamadı: {}", e.toString());
        }
    }

    private void evict() {
        try {
            if (certificateService != null) certificateService.evictAllCaches();
        } catch (Exception e) {
            log.debug("Sertifika önbellekleri boşaltılamadı: {}", e.toString());
        }
    }

    private ThreadPoolExecutor executor(int queueHint) {
        ThreadPoolExecutor ex = executor;
        if (ex != null && !ex.isShutdown()) return ex;
        synchronized (this) {
            if (executor == null || executor.isShutdown()) {
                int n = Math.max(1, Math.min(concurrency, 16));
                AtomicInteger seq = new AtomicInteger();
                ThreadFactory tf = r -> {
                    Thread t = new Thread(r, "tls-profile-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                };
                ThreadPoolExecutor created = new ThreadPoolExecutor(n, n, 60, TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(Math.max(1, Math.max(batchSize, queueHint))), tf,
                        new ThreadPoolExecutor.AbortPolicy());
                created.allowCoreThreadTimeOut(true);
                executor = created;
            }
            return executor;
        }
    }

    @PreDestroy
    void shutdown() {
        ThreadPoolExecutor ex = executor;
        if (ex != null) ex.shutdownNow();
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso.endsWith("Z") ? iso : iso + "Z");
        } catch (Exception e) {
            return null;
        }
    }
}
