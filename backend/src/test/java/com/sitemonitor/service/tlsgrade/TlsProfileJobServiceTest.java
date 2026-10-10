package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TlsProfileRepository;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.SchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TLS profili işi (2026-10-10): lider kapısı + kendi kilidi, vade seçimi (hiç yoklanmamış önce, sınırlı tur), elle
 * yüklenen kayıtların dışlanması, havuzun eşzamanlılık sınırı ve tur bütçesi, not karşılaştırması + önbellek boşaltma.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TlsProfileJobServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private CertificateInventoryRepository inventoryRepo;
    private TlsProfileRepository profileRepo;
    private LatestCheckRepository latestRepo;
    private TlsProfileProbeService probe;
    private TlsGradeService grades;
    private CertificateService certs;
    private SchedulerService scheduler;
    private TlsProfileJobService job;
    private final List<CertificateInventory> inventory = new ArrayList<>();
    private final Map<String, TlsProfile> profiles = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        inventoryRepo = mock(CertificateInventoryRepository.class);
        profileRepo = mock(TlsProfileRepository.class);
        latestRepo = mock(LatestCheckRepository.class);
        probe = mock(TlsProfileProbeService.class);
        grades = mock(TlsGradeService.class);
        certs = mock(CertificateService.class);
        scheduler = mock(SchedulerService.class);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenAnswer(i -> inventory);
        when(profileRepo.findByDomainIn(anyCollection())).thenAnswer(i -> new ArrayList<>(profiles.values()));
        when(profileRepo.save(any())).thenAnswer(i -> { TlsProfile p = i.getArgument(0); profiles.put(p.getDomain(), p); return p; });
        when(probe.probe(anyString(), anyInt(), anyBoolean(), anyString(), any())).thenAnswer(i -> profile(i.getArgument(0), "OK"));
        when(grades.reconcile(any())).thenReturn(new TlsGradeService.ReconcileResult(0, 0, 0, 0));
        when(certs.getAllLatest()).thenReturn(List.of());
        when(latestRepo.findById(anyString())).thenReturn(Optional.empty());
        job = new TlsProfileJobService(inventoryRepo, profileRepo, latestRepo, probe, grades);
        job.setCertificateService(certs);
        job.setSchedulerService(scheduler);
    }

    private static TlsProfile profile(String domain, String status) {
        TlsProfile p = new TlsProfile();
        p.setDomain(domain);
        p.setPort(443);
        p.setStatus(status);
        p.setProbedAt(ISO.format(Instant.now()));
        return p;
    }

    private CertificateInventory add(String domain) {
        CertificateInventory inv = new CertificateInventory();
        inv.setId((long) (inventory.size() + 1));
        inv.setDomain(domain);
        inv.setActive(true);
        inv.setPort(443);
        inventory.add(inv);
        return inv;
    }

    @Test
    @DisplayName("Lider değilse tur koşmaz; liderse kendi scheduler_lock anahtarıyla koşar")
    void leaderGateAndLock() {
        add("a.example.com");
        when(scheduler.isSweepLeaderNow()).thenReturn(false);
        job.scheduledTick();
        verify(scheduler, never()).runWithSchedulerLock(anyString(), any());

        when(scheduler.isSweepLeaderNow()).thenReturn(true);
        doAnswer(i -> { ((Runnable) i.getArgument(1)).run(); return null; })
                .when(scheduler).runWithSchedulerLock(eq(TlsProfileJobService.LOCK_NAME), any());
        job.scheduledTick();
        verify(scheduler).runWithSchedulerLock(eq("tls-profile"), any());
        verify(probe).probe(eq("a.example.com"), eq(443), eq(false), eq(TlsProfile.TRIGGER_SCHEDULED), any());
    }

    @Test
    @DisplayName("Kapalıysa hiçbir şey yapılmaz")
    void disabled() {
        add("a.example.com");
        job.enabled = false;
        job.scheduledTick();
        verify(scheduler, never()).isSweepLeaderNow();
        verify(probe, never()).probe(anyString(), anyInt(), anyBoolean(), anyString(), any());
    }

    @Test
    @DisplayName("Vade: hiç yoklanmamış önce, sonra en eski; tur boyutu sınırı; taze profil atlanır; elle yüklenen dışarıda")
    void dueSelection() {
        Instant now = Instant.now();
        add("fresh.example.com");
        add("old.example.com");
        add("never.example.com");
        add("failed.example.com");
        CertificateInventory manual = add("upload-key");
        manual.setCertSource(CertificateInventory.SOURCE_MANUAL);
        CertificateInventory moved = add("moved.example.com");
        moved.setPort(8443);
        TlsProfile fresh = profile("fresh.example.com", "OK");
        fresh.setProbedAt(ISO.format(now.minus(2, ChronoUnit.HOURS)));
        TlsProfile old = profile("old.example.com", "OK");
        old.setProbedAt(ISO.format(now.minus(30, ChronoUnit.HOURS)));
        TlsProfile failed = profile("failed.example.com", TlsProfile.STATUS_FAILED);
        failed.setProbedAt(ISO.format(now.minus(7, ChronoUnit.HOURS)));
        TlsProfile movedP = profile("moved.example.com", "OK");
        movedP.setProbedAt(ISO.format(now.minus(1, ChronoUnit.HOURS)));
        Map<String, TlsProfile> map = Map.of(fresh.getDomain(), fresh, old.getDomain(), old, failed.getDomain(), failed,
                movedP.getDomain(), movedP);

        List<CertificateInventory> net = job.networkInventory();
        assertThat(net).extracting(CertificateInventory::getDomain).doesNotContain("upload-key");
        List<CertificateInventory> due = job.dueEndpoints(net, map, now);
        assertThat(due).extracting(CertificateInventory::getDomain)
                .containsExactly("never.example.com", "old.example.com", "failed.example.com", "moved.example.com");

        job.batchSize = 2;
        assertThat(job.dueEndpoints(net, map, now)).extracting(CertificateInventory::getDomain)
                .containsExactly("never.example.com", "old.example.com");
    }

    @Test
    @DisplayName("Havuz eşzamanlılığı sınırlı; sonuçlar kaydedilir; profil değişince önbellek boşaltılır")
    void boundedConcurrency() {
        for (int i = 0; i < 9; i++) add("h" + i + ".example.com");
        AtomicInteger inFlight = new AtomicInteger(), peak = new AtomicInteger();
        when(probe.probe(anyString(), anyInt(), anyBoolean(), anyString(), any())).thenAnswer(i -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            Thread.sleep(80);
            inFlight.decrementAndGet();
            return profile(i.getArgument(0), "OK");
        });
        job.concurrency = 3;
        TlsProfileJobService.TickResult r = job.runTick(Instant.now());
        assertThat(r.probed()).isEqualTo(9);
        assertThat(peak.get()).isLessThanOrEqualTo(3);
        assertThat(profiles).hasSize(9);
        verify(certs).evictAllCaches();
        job.shutdown();
    }

    @Test
    @DisplayName("Tur bütçesi dolunca bitmeyen yoklamalar iptal edilir — tur sınırlı sürede döner")
    void tickBudget() {
        for (int i = 0; i < 4; i++) add("slow" + i + ".example.com");
        when(probe.probe(anyString(), anyInt(), anyBoolean(), anyString(), any())).thenAnswer(i -> {
            Thread.sleep(30_000);
            return profile(i.getArgument(0), "OK");
        });
        job.concurrency = 2;
        job.tickBudgetMs = 10_000;   // en küçük bütçe (alt sınır)
        long t0 = System.currentTimeMillis();
        TlsProfileJobService.TickResult r = job.runTick(Instant.now());
        assertThat(System.currentTimeMillis() - t0).isLessThan(20_000);
        assertThat(r.probed()).isZero();
        job.shutdown();
    }

    @Test
    @DisplayName("Not karşılaştırması: notlu satırlar envanter kaydıyla eşlenip servise verilir; değişim varsa önbellek boşaltılır")
    void reconcileFeedsGradeService() {
        CertificateInventory a = add("a.example.com");
        profiles.put("a.example.com", profile("a.example.com", "OK"));   // taze → yoklama yok
        CertificateDto row = new CertificateDto();
        row.setDomain("a.example.com");
        row.setTlsGrade("B");
        row.setTlsGradeReasons(List.of("TLS10_ENABLED"));
        CertificateDto ungraded = new CertificateDto();
        ungraded.setDomain("a.example.com");
        when(certs.getAllLatest()).thenReturn(List.of(row, ungraded));
        when(grades.reconcile(any())).thenReturn(new TlsGradeService.ReconcileResult(0, 0, 1, 0));
        TlsProfileJobService.TickResult r = job.runTick(Instant.now());
        assertThat(r.probed()).isZero();
        verify(grades).reconcile(org.mockito.ArgumentMatchers.argThat(list -> list.size() == 1
                && list.get(0).inv() == a && "B".equals(list.get(0).grade())));
        verify(certs, times(1)).evictAllCaches();
    }

    @Test
    @DisplayName("Elle yeniden tarama: MANUAL tetikle yoklar, kaydeder, önbelleği boşaltır")
    void rescan() {
        CertificateInventory a = add("a.example.com");
        a.setUseProxy(true);
        TlsProfile p = job.rescan(a, "ayse");
        verify(probe).probe("a.example.com", 443, true, TlsProfile.TRIGGER_MANUAL, "ayse");
        assertThat(profiles).containsKey("a.example.com");
        assertThat(p.getDomain()).isEqualTo("a.example.com");
        verify(certs).evictAllCaches();
    }
}
