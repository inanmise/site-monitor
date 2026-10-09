package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpCheckRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.KeywordResultRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.repository.PingCheckRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortCheckRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.UptimeCheckRepository;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod'lu kurulumda izlemeler aralık başına BİR KEZ yoklanır (2026-10-09) — iki (ve daha fazla) {@link SchedulerService}
 * örneği AYNI veritabanını (H2, PostgreSQL kipi — hem {@code scheduler_lock} hem {@code monitor_check_schedule} gerçek
 * SQL) ve ortak bir saati paylaşır, her birinin kendi bellek içi önbelleği vardır (gerçek pod'lar gibi).
 *
 * <p>Hata öncesi: kilit tur bitince bırakılıyor, ikinci pod kendi boş haritasıyla her izlemeyi "vadesi gelmiş" sayıp
 * yeniden yokluyordu (2 pod = aralık başına 2 yoklama; saatlik erişilebilirlik turu saatte 2 kez). Bu sınıf: iki pod →
 * tek yoklama, aralık dolunca yine tek; yeni açılan pod (yuvarlanan dağıtım) yinelemez; tek pod'un zamanlaması eski
 * bellek içi davranışla BİREBİR aynı; DB hatasında eski davranışa düşülür; tur ve cron kayıtları ikinci pod'u durdurur.
 */
@ExtendWith(MockitoExtension.class)
class SchedulerClusterScheduleTest {

    @Mock CertificateCheckerService checkerService;
    @Mock CertificateService certService;
    @Mock EmailNotificationService emailService;
    @Mock EscalationService escalationService;
    @Mock MaintenanceService maintenanceService;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock AlertThresholdRepository thresholdRepo;
    @Mock UserService userService;
    @Mock PermissionService permissionService;
    @Mock MonitorHistoryBackfillService historyBackfill;
    @Mock DataSource dataSource;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PortCheckerService portCheckerService;
    @Mock PortMonitorRepository portMonitorRepo;
    @Mock PortCheckRepository portCheckRepo;
    @Mock DnsCheckerService dnsCheckerService;
    @Mock DnsMonitorRepository dnsMonitorRepo;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock UptimeHttpCheckerService uptimeHttpCheckerService;
    @Mock UptimeCheckRepository uptimeCheckRepo;
    @Mock MonitoringOutageService monitoringOutageService;
    @Mock KeywordCheckerService keywordCheckerService;
    @Mock KeywordMonitorRepository keywordMonitorRepo;
    @Mock KeywordResultRepository keywordResultRepo;
    @Mock PingCheckerService pingCheckerService;
    @Mock PingMonitorRepository pingMonitorRepo;
    @Mock PingCheckRepository pingCheckRepo;
    @Mock HttpCheckerService httpCheckerService;
    @Mock HttpMonitorRepository httpMonitorRepo;
    @Mock HttpCheckRepository httpCheckRepo;
    @Mock RdapDomainExpiryService rdapDomainExpiryService;
    @Mock ActivityLogService activityLog;
    @Mock AuditService auditService;
    @Mock FailedLoginAnomalyIncidentService failedLoginAnomalyIncidentService;
    @Mock DomainMonitorRepository domainMonitorRepo;
    @Mock DomainCheckRepository domainCheckRepo;
    @Mock DomainCheckerService domainCheckerService;
    @Mock NetworkOutageEventRepository networkOutageRepo;
    @Mock WeeklyReportReminderService weeklyReportReminderService;
    @Mock WeeklyAvailabilityReportService weeklyAvailabilityReportService;
    @Mock IncidentService incidentService;
    @Mock AppSettingsService appSettings;
    @Mock ThreadPoolTaskExecutor certCheckExecutor;
    @Mock com.sitemonitor.service.retention.RetentionService retentionService;
    @Mock com.sitemonitor.service.report.CertificateInventoryReportService certificateInventoryReportService;

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long SEC = 1_000L;
    private static final long MIN = 60 * SEC;

    /** Ortak saat — pod'ların vade kararları bunu okur. */
    final AtomicLong clock = new AtomicLong(Instant.parse("2026-10-09T06:00:00Z").toEpochMilli());
    JdbcTemplate db;

    @BeforeEach
    void setUp() {
        db = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:multipod" + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        // scheduler_lock üretimde TEXT kolonludur; H2'de TEXT büyük nesneye eşlenip PK olamayabildiği için VARCHAR —
        // kilidin anlamı (INSERT tekilliği + locked_until kıyası) aynı.
        db.execute("CREATE TABLE scheduler_lock(name VARCHAR(200) NOT NULL PRIMARY KEY, "
                + "locked_by VARCHAR(200) NOT NULL, locked_until VARCHAR(40) NOT NULL)");
        db.execute(ClusterScheduleService.DDL);

        lenient().doAnswer(inv -> { inv.getArgument(0, Runnable.class).run(); return null; })
                .when(certCheckExecutor).execute(any(Runnable.class));
        lenient().when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
    }

    // ── Pod kurulumu ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Gerçek bir pod gibi: kendi bellek içi önbelleği, kendi sahip kimliği, ORTAK veritabanı ve saat. */
    SchedulerService pod(String owner) {
        return pod(new ClusterScheduleService(db, owner));
    }

    SchedulerService pod(ClusterScheduleService store) {
        SchedulerService s = new SchedulerService(
                checkerService, certService, emailService, escalationService, maintenanceService,
                inventoryRepo, latestCheckRepo, thresholdRepo, db,
                userService, permissionService, historyBackfill, dataSource, eventPublisher,
                portCheckerService, portMonitorRepo, portCheckRepo,
                dnsCheckerService, dnsMonitorRepo, dnsRecordRepo,
                uptimeHttpCheckerService, uptimeCheckRepo, monitoringOutageService,
                keywordCheckerService, keywordMonitorRepo, keywordResultRepo,
                pingCheckerService, pingMonitorRepo, pingCheckRepo,
                httpCheckerService, httpMonitorRepo, httpCheckRepo, rdapDomainExpiryService, activityLog, auditService,
                failedLoginAnomalyIncidentService,
                domainMonitorRepo, domainCheckRepo, domainCheckerService,
                networkOutageRepo,
                weeklyReportReminderService, weeklyAvailabilityReportService,
                certificateInventoryReportService, incidentService, appSettings,
                retentionService);
        ReflectionTestUtils.setField(s, "certCheckExecutor", certCheckExecutor);
        ReflectionTestUtils.setField(s, "orphanCleanupIntervalMs", 300_000L);
        ReflectionTestUtils.setField(s, "scheduleClock", (LongSupplier) clock::get);
        if (store != null) ReflectionTestUtils.setField(s, "clusterSchedule", store);
        return s;
    }

    /** Hatadan önceki davranış: küme kaydı yok, yalnız pod belleği. */
    SchedulerService legacyPod() {
        return pod((ClusterScheduleService) null);
    }

    void advance(long ms) {
        clock.addAndGet(ms);
    }

    PortMonitor standalonePort(long id, int intervalSeconds) {
        PortMonitor m = new PortMonitor();
        m.setId(id); m.setName("port-" + id); m.setHost("h" + id + ".example.com"); m.setPort(443);
        m.setProtocol("TCP"); m.setStandalone(true); m.setActive(true); m.setIntervalSeconds(intervalSeconds);
        return m;
    }

    /** Port yoklamalarının (ortak saatle) anlarını kaydeder. */
    List<Long> recordPortProbes() {
        List<Long> at = new ArrayList<>();
        lenient().when(portCheckerService.check(any(PortMonitor.class))).thenAnswer(i -> {
            at.add(clock.get());
            return Map.of("open", true, "response_ms", 3L);
        });
        return at;
    }

    // ── İzleme vadesi ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("iki pod sırayla süpürür: izleme aralık başına TEK kez yoklanır; aralık dolunca yine tek")
    void twoPods_probeOncePerInterval() {
        PortMonitor m = standalonePort(11L, 60);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        List<Long> probes = recordPortProbes();
        SchedulerService a = pod("pod-a");
        SchedulerService b = pod("pod-b");

        a.runPortChecks();
        b.runPortChecks();                         // HATA ÖNCESİ: b'nin boş haritası → ikinci yoklama
        assertThat(probes).hasSize(1);

        advance(30 * SEC);
        b.runPortChecks();
        a.runPortChecks();
        assertThat(probes).hasSize(1);             // aralık dolmadı

        advance(30 * SEC);                          // t = 60 sn
        b.runPortChecks();                          // takipçi (kira a'da) → hiçbir şey yoklamaz
        a.runPortChecks();
        assertThat(probes).hasSize(2);             // vadesi geldi → tarama lideri a yokladı

        advance(60 * SEC);                          // t = 120 sn
        a.runPortChecks();
        b.runPortChecks();
        assertThat(probes).hasSize(3);
        assertThat(db.queryForObject("SELECT claimed_by FROM monitor_check_schedule WHERE monitor_key = 'port:11'",
                String.class)).isEqualTo("pod-a");
    }

    @Test
    @DisplayName("NEGATİF KONTROL — küme kaydı olmayan (hata öncesi) iki pod aynı aralıkta İKİ kez yoklar; uptime turu iki kez koşar")
    void legacyPods_reproduceDoubleProbe() {
        PortMonitor m = standalonePort(10L, 60);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        List<Long> probes = recordPortProbes();
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("z.example.com"); inv.setPort(443); inv.setActive(true);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
        AtomicInteger uptime = new AtomicInteger();
        when(uptimeHttpCheckerService.check(eq("z.example.com"), eq(443), anyInt(), eq(false)))
                .thenAnswer(i -> { uptime.incrementAndGet(); return Map.of("status", "up", "response_ms", 9L); });
        SchedulerService a = legacyPod();
        SchedulerService b = legacyPod();

        a.runPortChecks();
        b.runPortChecks();
        a.runUptimeChecks();
        b.runUptimeChecks();

        assertThat(probes).hasSize(2);   // kilit çakışmayı engeller, tekrarı değil
        assertThat(uptime).hasValue(2);
    }

    @Test
    @DisplayName("yuvarlanan dağıtım: eski pod zarifçe kapanır, yeni pod (boş bellek) lider olur ama aralık dolmadan yinelemez")
    void freshPod_doesNotReprobeWithinInterval() {
        PortMonitor m = standalonePort(12L, 300);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        List<Long> probes = recordPortProbes();

        SchedulerService old = pod("old-pod");
        old.runPortChecks();
        advance(45 * SEC);
        old.releaseSweepLeadership();                // SIGTERM → @PreDestroy: kira bırakılır
        SchedulerService fresh = pod("new-pod");
        fresh.runPortChecks();                       // HATA ÖNCESİ: yeni pod her izlemeyi hemen yokluyordu
        assertThat(fresh.isSweepLeader()).isTrue();
        assertThat(probes).hasSize(1);               // vade kaydı: 300 sn dolmadı

        advance(255 * SEC);                          // t = 300 sn
        fresh.runPortChecks();
        assertThat(probes).hasSize(2);
    }

    @Test
    @DisplayName("tek pod (kira + vade kaydı açık): yoklama anları eski bellek içi ızgarayla BİREBİR aynı (30 sn tur, 90 sn aralık, 12 dk)")
    void singlePod_timingIdenticalToLegacy() {
        PortMonitor m = standalonePort(13L, 90);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        List<Long> probes = recordPortProbes();
        long start = clock.get();

        SchedulerService legacy = legacyPod();
        for (int i = 0; i <= 24; i++) { legacy.runPortChecks(); advance(30 * SEC + 700); }   // tur gecikmesi dahil
        List<Long> legacyTimes = new ArrayList<>(probes);

        probes.clear();
        clock.set(start);
        SchedulerService cluster = pod("solo");
        for (int i = 0; i <= 24; i++) { cluster.runPortChecks(); advance(30 * SEC + 700); }

        assertThat(legacyTimes).hasSizeGreaterThan(5);
        assertThat(probes).isEqualTo(legacyTimes);
    }

    @Test
    @DisplayName("kararlı durumda vade gelmeden DB'ye gidilmez (önbellek hızlı yolu); vade gelince tek sahiplenme")
    void steadyState_noDbRoundTripBeforeDue() {
        PortMonitor m = standalonePort(14L, 60);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        recordPortProbes();
        ClusterScheduleService store = spy(new ClusterScheduleService(db, "pod-a"));
        SchedulerService a = pod(store);

        a.runPortChecks();
        advance(30 * SEC);
        a.runPortChecks();
        advance(20 * SEC);
        a.runPortChecks();
        verify(store, times(1)).claim(eq("port:14"), anyLong(), anyLong(), any(), any());

        advance(10 * SEC);                            // t = 60 sn
        a.runPortChecks();
        verify(store, times(2)).claim(eq("port:14"), anyLong(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("DB hatası (tablo yok): pod eski bellek içi davranışa düşer — izleme durmaz, zamanlama eskisiyle aynı")
    void dbFailure_fallsBackToLegacyInMemory() {
        PortMonitor m = standalonePort(15L, 60);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        List<Long> probes = recordPortProbes();
        long start = clock.get();

        SchedulerService legacy = legacyPod();
        for (int i = 0; i <= 8; i++) { legacy.runPortChecks(); advance(30 * SEC); }
        List<Long> legacyTimes = new ArrayList<>(probes);

        probes.clear();
        clock.set(start);
        JdbcTemplate noTable = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:notable" + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        SchedulerService broken = pod(new ClusterScheduleService(noTable, "pod-broken"));
        for (int i = 0; i <= 8; i++) { broken.runPortChecks(); advance(30 * SEC); }

        assertThat(legacyTimes).hasSize(5);           // 0, 60, 120, 180, 240 sn
        assertThat(probes).isEqualTo(legacyTimes);
    }

    @Test
    @DisplayName("aralık kısaltıldı: saklanan uzak vade beklenmez, sıradaki turda yoklanır (bellekte bunu restart düzeltirdi)")
    void shortenedInterval_probedOnNextSweep() {
        PortMonitor m = standalonePort(16L, 3600);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        List<Long> probes = recordPortProbes();
        SchedulerService a = pod("pod-a");

        a.runPortChecks();
        m.setIntervalSeconds(60);
        advance(30 * SEC);
        a.runPortChecks();
        assertThat(probes).hasSize(2);
        advance(30 * SEC);
        a.runPortChecks();                             // yeni ızgara: 30 + 60 = 90 sn
        assertThat(probes).hasSize(2);
        advance(30 * SEC);
        a.runPortChecks();
        assertThat(probes).hasSize(3);
    }

    @Test
    @DisplayName("alan adı izlemesi (titremeli günlük aralık): iki pod → tek RDAP/WHOIS sorgusu")
    void domainMonitor_twoPods_queriedOnce() {
        DomainMonitor m = new DomainMonitor();
        m.setId(21L); m.setName("ornek"); m.setDomain("ornek.com.tr"); m.setActive(true); m.setIntervalSeconds(86400);
        when(domainMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        when(domainCheckerService.check(m)).thenReturn(Map.of("status", "OK", "days_remaining", 200));

        pod("pod-a").runDomainChecks();
        advance(MIN);
        pod("pod-b").runDomainChecks();
        advance(60 * MIN);
        pod("pod-c").runDomainChecks();

        verify(domainCheckerService, times(1)).check(m);
    }

    // ── Süpürme turları ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("saatlik erişilebilirlik turu: iki pod saatte BİR kez; 59. dakikada yok, 60. dakikada tek")
    void uptimeRound_oncePerHourAcrossPods() {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("x.example.com"); inv.setPort(443); inv.setActive(true);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
        AtomicInteger probes = new AtomicInteger();
        when(uptimeHttpCheckerService.check(eq("x.example.com"), eq(443), anyInt(), eq(false)))
                .thenAnswer(i -> { probes.incrementAndGet(); return Map.of("status", "up", "response_ms", 9L); });
        SchedulerService a = pod("pod-a");
        SchedulerService b = pod("pod-b");

        a.runUptimeChecks();
        advance(MIN);
        b.runUptimeChecks();                           // HATA ÖNCESİ: b kendi fazında ikinci turu koşardı
        assertThat(probes).hasValue(1);

        for (int i = 2; i < 60; i++) { advance(MIN); a.runUptimeChecks(); b.runUptimeChecks(); }   // t = 59 dk
        assertThat(probes).hasValue(1);

        advance(MIN);                                   // t = 60 dk
        b.runUptimeChecks();
        a.runUptimeChecks();
        assertThat(probes).hasValue(2);
    }

    @Test
    @DisplayName("yeni açılan pod saatlik turu yinelemez ama gecikmeli de başlatmaz (tur vadesinde, kendi fazından bağımsız)")
    void uptimeRound_freshPodPicksUpAtDueTime() {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("y.example.com"); inv.setPort(443); inv.setActive(true);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
        AtomicInteger probes = new AtomicInteger();
        when(uptimeHttpCheckerService.check(eq("y.example.com"), eq(443), anyInt(), eq(false)))
                .thenAnswer(i -> { probes.incrementAndGet(); return Map.of("status", "up", "response_ms", 9L); });

        pod("old-pod").runUptimeChecks();              // t = 0; eski pod sonra kapanır
        advance(50 * MIN);
        SchedulerService fresh = pod("new-pod");
        fresh.runUptimeChecks();                       // t = 50 dk → tur vadesi gelmedi
        assertThat(probes).hasValue(1);
        advance(10 * MIN);                              // t = 60 dk → bir dakikalık tıkta yakalar
        fresh.runUptimeChecks();
        assertThat(probes).hasValue(2);
    }

    @Test
    @DisplayName("cron (Cuma 09:00 hatırlatma): kilit bırakıldıktan sonra geç tetiklenen pod İKİNCİ mail atmaz; ertesi hafta yine tek")
    void cronRound_lateSecondPod_doesNotResend() {
        clock.set(Instant.parse("2026-10-09T06:00:00Z").toEpochMilli());   // Cuma 09:00 İstanbul
        SchedulerService a = pod("pod-a");
        SchedulerService b = pod("pod-b");

        a.scheduledWeeklyReportReminder();
        advance(5 * SEC);                                // b'nin zamanlayıcı iş parçacığı meşguldü
        b.scheduledWeeklyReportReminder();
        verify(weeklyReportReminderService, times(1)).sendFridayReminders(true);

        clock.set(Instant.parse("2026-10-16T06:00:00Z").toEpochMilli());  // sonraki Cuma 09:00
        b.scheduledWeeklyReportReminder();
        a.scheduledWeeklyReportReminder();
        verify(weeklyReportReminderService, times(2)).sendFridayReminders(true);
    }

    @Test
    @DisplayName("cron (saatlik sertifika + 16:00 kritik domain): geç kalan ikinci pod aynı turu koşturmaz")
    void cronRounds_hourlyCertAndCriticalDomain_runOnce() {
        // Saatlik sertifika turu: envanter boş → tur gövdesi yalnız envanteri okur; tur kaydında duran pod OKUMAZ.
        clock.set(Instant.parse("2026-10-09T07:00:00Z").toEpochMilli());
        SchedulerService a = pod("pod-a");
        SchedulerService b = pod("pod-b");
        a.scheduledHourlyCheck();
        advance(20 * SEC);
        b.scheduledHourlyCheck();
        verify(inventoryRepo, times(1)).findByActiveTrueOrderByDomainAsc();
        advance(60 * MIN - 20 * SEC);                    // bir sonraki saat başı
        b.scheduledHourlyCheck();
        a.scheduledHourlyCheck();
        verify(inventoryRepo, times(2)).findByActiveTrueOrderByDomainAsc();

        // 16:00 İstanbul kritik domain ikinci kontrolü
        clock.set(Instant.parse("2026-10-09T13:00:00Z").toEpochMilli());
        a.runCriticalDomainChecks();
        advance(3 * SEC);
        b.runCriticalDomainChecks();
        verify(domainCheckRepo, times(1)).findLatestPerMonitor();
    }

    @Test
    @DisplayName("açılış tam taraması: 15 dk içinde açılan ikinci pod yinelemez; sonrakinde koşar")
    void startupCertCheck_dedupedAcrossPods() {
        SchedulerService a = pod("pod-a");
        SchedulerService b = pod("pod-b");

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(a, "roundDue", "cert-startup",
                SchedulerService.STARTUP_CHECK_DEDUPE_MS)).isTrue();
        advance(3 * MIN);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(b, "roundDue", "cert-startup",
                SchedulerService.STARTUP_CHECK_DEDUPE_MS)).isFalse();
        advance(15 * MIN);
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(pod("pod-c"), "roundDue", "cert-startup",
                SchedulerService.STARTUP_CHECK_DEDUPE_MS)).isTrue();
    }

    @Test
    @DisplayName("küme kaydı yokken (manuel kurulum) tur ve cron kapıları eski davranış: her çağrı bir tur")
    void noStore_everyCallIsARound() {
        SchedulerService legacy = legacyPod();
        legacy.scheduledWeeklyReportReminder();
        legacy.scheduledWeeklyReportReminder();
        verify(weeklyReportReminderService, times(2)).sendFridayReminders(true);
    }

    // ── Tarama liderliği (kira) ──────────────────────────────────────────────────────────────────────────────────

    /** Alarm hattını (MonitoringOutageService) besleyen TÜM zamanlanmış taramalar. */
    static void runAllMonitoringSweeps(SchedulerService s) {
        s.runUptimeChecks();
        s.runPortChecks();
        s.runKeywordChecks();
        s.runHttpChecks();
        s.runPageChecks();
        s.runPageCrawls();
        s.runPageSpeedChecks();
        s.runScriptedChecks();
        s.runHttpSslDomainChecks();
        s.runKeywordSslDomainChecks();
        s.runDomainChecks();
        s.runCriticalDomainChecks();
        s.runPingChecks();
        s.runDnsChecks();
    }

    @Test
    @DisplayName("kira: yalnız lider tarar — takipçi pod alarm hattını besleyen 14 taramanın HİÇBİRİNDE depoya/yoklayıcıya/alarm servisine dokunmaz")
    void leader_onlyLeaderRunsMonitoringSweeps() {
        PortMonitor m = standalonePort(31L, 60);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        List<Long> probes = recordPortProbes();
        SchedulerService a = pod("pod-a");
        SchedulerService b = pod("pod-b");

        a.runPortChecks();                                       // a kirayı alır, yoklar
        assertThat(a.isSweepLeader()).isTrue();
        org.mockito.Mockito.clearInvocations(portMonitorRepo, monitoringOutageService, escalationService, inventoryRepo,
                portCheckerService, activityLog);

        runAllMonitoringSweeps(b);                                // takipçi: hepsi kira kapısında döner

        assertThat(b.isSweepLeader()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(portMonitorRepo, pingMonitorRepo, dnsMonitorRepo, keywordMonitorRepo,
                httpMonitorRepo, domainMonitorRepo, domainCheckRepo, inventoryRepo, monitoringOutageService,
                escalationService, portCheckerService, pingCheckerService, dnsCheckerService, keywordCheckerService,
                httpCheckerService, uptimeHttpCheckerService, domainCheckerService, activityLog);
        assertThat(probes).hasSize(1);

        advance(60 * SEC);                                        // lider taramaya devam eder (alarm durumu tek pod'da)
        b.runPortChecks();
        a.runPortChecks();
        a.runPingChecks();
        assertThat(probes).hasSize(2);
        verify(pingMonitorRepo).findByActiveTrue();
        assertThat(db.queryForObject("SELECT claimed_by FROM monitor_check_schedule WHERE monitor_key = 'lease:sweep-leader'",
                String.class)).isEqualTo("pod-a");
    }

    @Test
    @DisplayName("kira: lider yenilemeyi bırakırsa (çöktü) takipçi kira süresi DOLUNCA devralır, önce değil; eski lider dönerse takipçi olur")
    void leaderStopsRenewing_followerTakesOverAfterTtl() {
        PortMonitor m = standalonePort(32L, 30);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        List<Long> probes = recordPortProbes();
        SchedulerService a = pod("pod-a");
        SchedulerService b = pod("pod-b");

        a.runPortChecks();                                       // t = 0: a lider (kira 180 sn), sonra susar
        advance(170 * SEC);
        b.runPortChecks();                                       // izleme vadesi geçmiş ama kira hâlâ a'da
        assertThat(probes).hasSize(1);

        advance(11 * SEC);                                        // t = 181 sn: kira doldu
        b.runPortChecks();
        assertThat(probes).hasSize(2);
        assertThat(b.isSweepLeader()).isTrue();

        advance(9 * SEC);                                         // eski lider geri gelir → yenileyemez, takipçi olur
        a.runPortChecks();
        assertThat(a.isSweepLeader()).isFalse();
        assertThat(probes).hasSize(2);
    }

    @Test
    @DisplayName("KAPI (2026-10-09): tarama iş parçacıkları meşgulken (hiç tarama koşmasa da) yenileyici kirayı tutar — liderlik el değiştirmez")
    void renewer_keepsLeadership_whileSweepThreadsAreBusy() {
        PortMonitor m = standalonePort(35L, 30);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        List<Long> probes = recordPortProbes();
        SchedulerService a = pod("pod-a");
        SchedulerService b = pod("pod-b");

        a.runPortChecks();                                       // t = 0: a lider (kira 180 sn)
        for (int i = 0; i < 5; i++) {                            // 5 dk boyunca a'da HİÇ tarama koşmuyor (8 iş parçacığı dolu)
            advance(60 * SEC);
            a.renewSweepLeadership();                            // yalnız ayrı yenileyici
            b.runPortChecks();                                   // takipçi devralamaz
        }
        assertThat(b.isSweepLeader()).isFalse();
        assertThat(probes).as("takipçi hiç yoklamadı").hasSize(1);
        advance(SEC);
        a.runPortChecks();                                       // lider kesintisiz taramaya devam eder
        assertThat(a.isSweepLeader()).isTrue();
        assertThat(probes).hasSize(2);
    }

    @Test
    @DisplayName("KAPI (2026-10-09): lider gerçekten ölünce takipçinin yenileyicisi (hiç tarama koşmadan) kira dolunca devralır")
    void followerRenewer_takesOverAfterLeaderDies() {
        SchedulerService a = pod("pod-a");
        SchedulerService b = pod("pod-b");
        a.renewSweepLeadership();
        assertThat(a.isSweepLeader()).isTrue();
        advance(170 * SEC);
        b.renewSweepLeadership();
        assertThat(b.isSweepLeader()).as("kira dolmadan devralınmaz").isFalse();
        advance(11 * SEC);                                       // t = 181 sn: a yenilemedi (öldü) → kira doldu
        b.renewSweepLeadership();
        assertThat(b.isSweepLeader()).isTrue();
    }

    @Test
    @DisplayName("KAPI (2026-10-09): alarm hattının kapısı liderliği izler; yenileyici kapanışta durur, kapanan pod kirayı yeniden almaz")
    void renewer_fenceAndShutdown() {
        org.mockito.ArgumentCaptor<java.util.function.BooleanSupplier> fenceA =
                org.mockito.ArgumentCaptor.forClass(java.util.function.BooleanSupplier.class);
        SchedulerService a = pod("pod-a");
        SchedulerService b = pod("pod-b");
        a.renewSweepLeadership();
        a.startSweepLeaderRenewer();
        verify(monitoringOutageService).setScheduledResultsFence(fenceA.capture());
        assertThat(fenceA.getValue().getAsBoolean()).isTrue();
        assertThat(ReflectionTestUtils.getField(a, "sweepLeaderRenewer")).isNotNull();

        b.renewSweepLeadership();
        assertThat(b.isSweepLeader()).isFalse();

        a.onShutdown();                                          // yenileyici durur, kira bırakılır
        assertThat(ReflectionTestUtils.getField(a, "sweepLeaderRenewer")).isNull();
        a.renewSweepLeadership();                                // kapanırken gelen tur kirayı yeniden ALMAZ
        assertThat(fenceA.getValue().getAsBoolean()).as("kapanan pod'un sonuçları alarm hattına yazılmaz").isFalse();
        advance(6 * SEC);
        b.renewSweepLeadership();
        assertThat(b.isSweepLeader()).isTrue();
    }

    @Test
    @DisplayName("Tek pod / küme kaydı yok: yenileyici başlamaz, kapı takılmaz (bugünkü davranış)")
    void noClusterStore_noRenewer() {
        SchedulerService legacy = legacyPod();
        legacy.startSweepLeaderRenewer();
        assertThat(ReflectionTestUtils.getField(legacy, "sweepLeaderRenewer")).isNull();
        org.mockito.Mockito.verify(monitoringOutageService, org.mockito.Mockito.never()).setScheduledResultsFence(any());
        assertThat(legacy.isSweepLeader()).isTrue();
    }

    @Test
    @DisplayName("kira: zarif kapanışta (@PreDestroy) bırakılır → takipçi kira süresini beklemeden bir sonraki tıkta devralır; kapanan pod yeniden almaz")
    void gracefulShutdown_immediateTakeover() {
        PortMonitor m = standalonePort(33L, 30);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        List<Long> probes = recordPortProbes();
        SchedulerService a = pod("pod-a");
        SchedulerService b = pod("pod-b");

        a.runPortChecks();
        b.runPortChecks();
        assertThat(probes).hasSize(1);

        a.onShutdown();                                           // tek @PreDestroy girişi
        advance(30 * SEC);                                        // bir tarama tıkı (kira süresi 180 sn değil)
        a.runPortChecks();                                        // kapanırken koşan tık kirayı yeniden ALMAZ
        b.runPortChecks();
        assertThat(probes).hasSize(2);
        assertThat(b.isSweepLeader()).isTrue();
        assertThat(a.isSweepLeader()).isFalse();
    }

    @Test
    @DisplayName("kira: veritabanı hatası → her pod YEREL tarar (hata öncesi davranış) — izleme durmaz")
    void leaseDbFailure_runsLocally() {
        PortMonitor m = standalonePort(34L, 60);
        when(portMonitorRepo.findByActiveTrue()).thenReturn(List.of(m));
        List<Long> probes = recordPortProbes();
        JdbcTemplate noTable = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:noleasetable" + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        SchedulerService a = pod(new ClusterScheduleService(noTable, "pod-a"));
        SchedulerService b = pod(new ClusterScheduleService(noTable, "pod-b"));

        assertThat(a.isSweepLeader()).isTrue();
        assertThat(b.isSweepLeader()).isTrue();
        a.runPortChecks();
        b.runPortChecks();
        assertThat(probes).hasSize(2);                            // güvenli taraf: çift yoklama, kaçırılan yoklama değil
    }

    @Test
    @DisplayName("kira: açılışta aynı host'taki ÖNCEKİ örneğin (çökmüş) kirası silinir; başka host'un kirasına dokunulmaz")
    void startup_clearsLeaseOfPreviousInstanceOnSameHost() {
        long now = clock.get();
        db.update("INSERT INTO monitor_check_schedule(monitor_key, next_due_at, claimed_by, claimed_at) VALUES (?,?,?,?)",
                "lease:sweep-leader", now + 180_000, "baska-host-1234-abcd", now);
        SchedulerService b = pod("pod-b");

        ReflectionTestUtils.invokeMethod(b, "clearStaleSweepLeaderOfThisHost");
        assertThat(b.isSweepLeader()).isFalse();                  // başka host canlı olabilir → beklenir

        db.update("UPDATE monitor_check_schedule SET claimed_by = ? WHERE monitor_key = 'lease:sweep-leader'",
                SchedulerService.hostname() + "-deadbeef");      // aynı host, önceki (çökmüş) örnek
        ReflectionTestUtils.invokeMethod(b, "clearStaleSweepLeaderOfThisHost");
        advance(6 * SEC);                                         // takipçi yeniden yoklama aralığı
        assertThat(b.isSweepLeader()).isTrue();
    }

    @Test
    @DisplayName("System Health: scheduler.sweep_leader kiranın sahibini ve bu pod'da olup olmadığını gösterir")
    @SuppressWarnings("unchecked")
    void systemHealth_exposesSweepLeader() {
        SchedulerService a = pod("pod-a");
        SchedulerService b = pod("pod-b");
        assertThat(a.isSweepLeader()).isTrue();

        Map<String, Object> la = (Map<String, Object>) ((Map<String, Object>) a.getSystemHealth().get("scheduler")).get("sweep_leader");
        Map<String, Object> lb = (Map<String, Object>) ((Map<String, Object>) b.getSystemHealth().get("scheduler")).get("sweep_leader");

        assertThat(la).containsEntry("owner", "pod-a").containsEntry("held_by_me", true).containsEntry("expired", false);
        assertThat(lb).containsEntry("owner", "pod-a").containsEntry("held_by_me", false);
        assertThat(((Map<String, Object>) legacyPod().getSystemHealth().get("scheduler"))).doesNotContainKey("sweep_leader");
    }
}
