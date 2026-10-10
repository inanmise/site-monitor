package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
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
 * <p><b>Küme kuralları.</b> Tur yalnız TARAMA LİDERİ pod'da koşar ({@code SchedulerService.isSweepLeaderNow}; kira
 * okunamazsa yerel koşu — mevcut sözleşme) ve kendi {@code scheduler_lock} anahtarı ({@value #LOCK_NAME}) altında: iki pod
 * aynı anda yoklamaz. Elle "Şimdi kontrol et" bu yoklamayı TETİKLEMEZ; ayrı bir "TLS profilini yeniden tara" ucu vardır
 * ({@link #rescan}).
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
    private final LatestCheckRepository latestRepo;
    private final TlsProfileProbeService probeService;
    private final TlsGradeService gradeService;

    /** Liste önbelleği + not hesaplanmış satırlar — döngüsel bağımlılık olmasın diye tembel. */
    @Autowired
    @Lazy
    private CertificateService certificateService;

    /** Lider kapısı + dağıtık kilit — isteğe bağlı: birim testinde yok (her çağrı yerel koşar). */
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
                                LatestCheckRepository latestRepo, TlsProfileProbeService probeService,
                                TlsGradeService gradeService) {
        this.inventoryRepo = inventoryRepo;
        this.profileRepo = profileRepo;
        this.latestRepo = latestRepo;
        this.probeService = probeService;
        this.gradeService = gradeService;
    }

    void setCertificateService(CertificateService certificateService) {
        this.certificateService = certificateService;
    }

    void setSchedulerService(SchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    /** Tur özeti (log + test). */
    public record TickResult(int due, int probed, int failed, TlsGradeService.ReconcileResult grades) { }

    @Scheduled(fixedDelayString = "${site.monitor.tls-profile.tick-ms:600000}",
               initialDelayString = "${site.monitor.tls-profile.initial-delay-ms:420000}")
    public void scheduledTick() {
        if (!enabled) return;
        if (schedulerService != null && !schedulerService.isSweepLeaderNow()) return;
        if (schedulerService != null) {
            schedulerService.runWithSchedulerLock(LOCK_NAME, this::tickAndLog);
        } else {
            tickAndLog();
        }
    }

    private void tickAndLog() {
        TickResult r = runTick(Instant.now());
        if (r.probed() > 0 || r.grades().changed()) {
            log.info("TLS profil turu: {} vadesi gelen, {} yoklandı ({} başarısız); not: {} düşüş, {} yükseliş, {} bilgi değişimi",
                    r.due(), r.probed(), r.failed(), r.grades().drops(), r.grades().rises(), r.grades().refined());
        }
    }

    /** Tek tur — çağıran kilidi tutar. Paket-özel: test doğrudan çağırır. */
    TickResult runTick(Instant now) {
        List<CertificateInventory> active = networkInventory();
        Map<String, TlsProfile> profiles = new HashMap<>();
        if (!active.isEmpty()) {
            for (TlsProfile p : profileRepo.findByDomainIn(active.stream().map(CertificateInventory::getDomain).toList())) {
                profiles.put(p.getDomain(), p);
            }
        }
        List<CertificateInventory> due = dueEndpoints(active, profiles, now);
        List<TlsProfile> results = probeAll(due);
        int failed = 0;
        for (TlsProfile p : results) {
            try {
                profileRepo.save(p);
            } catch (Exception e) {
                log.warn("TLS profili kaydedilemedi ({}): {}", p.getDomain(), e.toString());
            }
            if (TlsProfile.STATUS_FAILED.equals(p.getStatus()) || TlsProfile.STATUS_BLOCKED.equals(p.getStatus())) failed++;
        }
        if (!results.isEmpty()) evict();
        TlsGradeService.ReconcileResult grades = reconcileAll(active);
        if (grades.changed()) evict();
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
        if (due.isEmpty()) return List.of();
        List<Callable<TlsProfile>> tasks = new ArrayList<>(due.size());
        for (CertificateInventory inv : due) {
            int port = inv.getPort() == null ? 443 : inv.getPort();
            boolean useProxy = Boolean.TRUE.equals(inv.getUseProxy());
            tasks.add(() -> probeService.probe(inv.getDomain(), port, useProxy, TlsProfile.TRIGGER_SCHEDULED, null));
        }
        List<TlsProfile> out = new ArrayList<>();
        try {
            for (Future<TlsProfile> f : executor(due.size()).invokeAll(tasks, Math.max(10_000L, tickBudgetMs), TimeUnit.MILLISECONDS)) {
                try {
                    TlsProfile p = f.get();
                    if (p != null) out.add(p);
                } catch (CancellationException | ExecutionException e) {
                    log.debug("TLS profili yoklaması tamamlanmadı: {}", e.toString());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("TLS profili havuzu dolu — tur atlandı: {}", e.toString());
        }
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
     * Elle yeniden tarama (sertifika penceresi): tek ucu ŞİMDİ yoklar, kaydeder, notu karşılaştırır ve önbellekleri
     * boşaltır. Kapı (izin + takım kapsamı + hız sınırı) çağıranda. Elle yüklenen kayıt için çağrılmaz.
     */
    public TlsProfile rescan(CertificateInventory inv, String actor) {
        int port = inv.getPort() == null ? 443 : inv.getPort();
        TlsProfile p = probeService.probe(inv.getDomain(), port, Boolean.TRUE.equals(inv.getUseProxy()),
                TlsProfile.TRIGGER_MANUAL, actor);
        profileRepo.save(p);
        try {
            LatestCheck lc = latestRepo.findById(inv.getDomain()).orElse(null);
            TlsGradeRules.Grade g = TlsGradeRules.evaluate(lc, p, false);
            if (g.graded() && !Boolean.FALSE.equals(inv.getActive())) {
                gradeService.reconcile(List.of(new TlsGradeService.Evaluated(inv, g.grade(), g.codes())));
            }
        } catch (Exception e) {
            log.debug("Yeniden tarama sonrası not karşılaştırılamadı ({}): {}", inv.getDomain(), e.toString());
        }
        evict();
        return p;
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
