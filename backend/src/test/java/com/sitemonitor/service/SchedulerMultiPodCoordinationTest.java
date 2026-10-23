package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.it.MultiPodTestSupport.Pod;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod koordinasyon çekirdeği (2026-10-21, çok pod R1-A) — iki / üç {@link SchedulerService} AYNI H2 veritabanını ve
 * ortak saati paylaşır ({@link MultiPodTestSupport}); her pod'un kendi kilit sahibi ({@code ClusterLockService}), kendi kira
 * kaydı ve kendi belleği var:
 * <ul>
 *   <li>MP-02: uzun sertifika taraması kilidi yeniler → başka pod'un bayat taraması eşzamanlı koşmaz; kilit kaybedilince
 *       sonuç işlenmez; sahibi ölünce TTL sonra devralınır; kapanışta iş kilitleri bırakılır.</li>
 *   <li>MP-11: geçici kira hatası ikinci lider yaratmaz (lider yalnız güvenli pencerede sürer); tur sahiplenmesi hatası
 *       turu atlatır (haftalık rapor kümede bir kez).</li>
 *   <li>MP-15: iki aşamalı tur yarıda kalırsa koşu kirası dolunca yeniden koşar; saatlik tur önce kilidi alır.</li>
 *   <li>MP-08: {@code ContextClosedEvent} kirayı hemen bırakır; MP-24: tıkanmış lider kirayı bırakır, canlılık BROKEN.</li>
 *   <li>MP-03: açılış catch-up'ı kümede bir kez.</li>
 * </ul>
 */
class SchedulerMultiPodCoordinationTest {

    private static final long SEC = 1_000L;
    private static final long MIN = 60 * SEC;

    final MultiPodTestSupport mp = MultiPodTestSupport.create();

    @AfterEach
    void close() {
        mp.close();
    }

    /** Pod: üretimdeki kilit süreleri (10 dk → yenilenen 3 dk), bekleme / yeniden deneme beklemeleri kısaltılmış. */
    Pod pod(int n) {
        return mp.pod(n, SchedulerMultiPodCoordinationTest::tune);
    }

    static void tune(SchedulerService s) {
        ReflectionTestUtils.setField(s, "lockTtlMinutes", 10);
        ReflectionTestUtils.setField(s, "sweepLockTtlMinutes", 2);
        ReflectionTestUtils.setField(s, "hourlyLockWaitMs", 0L);
        ReflectionTestUtils.setField(s, "roundClaimRetryDelaysMs", new long[] {1L, 1L});
        ReflectionTestUtils.setField(s, "cronRoundRetryDelaysMs", new long[] {1L, 1L, 1L, 1L, 1L});
        ReflectionTestUtils.setField(s, "startupCheckDelayMs", 0L);
        ReflectionTestUtils.setField(s, "networkMinErrors", 3);        // @Value varsayılanları (manuel kurulumda 0 kalır)
        ReflectionTestUtils.setField(s, "networkErrorRateThreshold", 0.5);
    }

    CertificateInventory inv(String domain) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain);
        i.setPort(443);
        i.setActive(true);
        return i;
    }

    void inventory(String... domains) {
        List<CertificateInventory> list = new ArrayList<>();
        for (String d : domains) list.add(inv(d));
        when(mp.mock(CertificateInventoryRepository.class).findByActiveTrueOrderByDomainAsc()).thenReturn(list);
    }

    /** Kontrolleri testin bitirdiği tek bir "yavaş" future'a bağlar; çağrı sayısını döner. */
    AtomicInteger slowChecks(CompletableFuture<Map<String, Object>> slow) {
        AtomicInteger calls = new AtomicInteger();
        when(mp.mock(CertificateCheckerService.class).checkAsync(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenAnswer(i -> {
                    calls.incrementAndGet();
                    return slow.thenApply(r -> Map.<String, Object>of("domain", i.getArgument(0), "status", "valid"));
                });
        return calls;
    }

    Thread startFullRun(Pod p) throws InterruptedException {
        Thread t = new Thread(p.scheduler()::runCheck, "cert-run-" + p.owner());
        t.setDaemon(true);
        t.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!p.owner().equals(mp.schedulerLockHolder("cert-check")) && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertThat(mp.schedulerLockHolder("cert-check")).as("tam tarama kilidi aldı").isEqualTo(p.owner());
        return t;
    }

    static List<String> reasons(Pod p) {
        return p.events().ofType(SweepLeadershipChangedEvent.class).stream().map(SweepLeadershipChangedEvent::reason).toList();
    }

    /** Küme kaydı: istendiğinde (ve yalnız istenen işlemlerde) geçici veritabanı hatası verir. */
    static final class FlakyStore extends ClusterScheduleService {
        final AtomicBoolean leaseDown = new AtomicBoolean();
        final AtomicBoolean claimDown = new AtomicBoolean();
        /** Bu kadar sahiplenme denemesi hata verir, sonra düzelir (cron payı içinde yeniden deneme). */
        final AtomicInteger claimFailuresLeft = new AtomicInteger();
        /** Her başarısız sahiplenme denemesinde çağrılır (ör. yeniden deneme sırasında kapanış başlat). */
        volatile Runnable onClaimFailure;

        FlakyStore(MultiPodTestSupport mp, String owner) {
            super(mp.jdbc(), owner);
        }

        @Override
        public boolean acquireLease(String name, long nowMs, long ttlMs) {
            if (leaseDown.get()) throw new DataAccessResourceFailureException("bağlantı havuzu dolu");
            return super.acquireLease(name, nowMs, ttlMs);
        }

        @Override
        public Lease lease(String name) {
            if (leaseDown.get()) throw new DataAccessResourceFailureException("bağlantı havuzu dolu");
            return super.lease(name);
        }

        @Override
        public Claim claim(String key, long nowMs, long nominalMs, Long knownNextDue, LongUnaryOperator advance) {
            if (claimDown.get() || claimFailuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                Runnable hook = onClaimFailure;
                if (hook != null) hook.run();
                throw new DataAccessResourceFailureException("bağlantı havuzu dolu");
            }
            return super.claim(key, nowMs, nominalMs, knownNextDue, advance);
        }
    }

    FlakyStore flaky(Pod p) {
        FlakyStore s = new FlakyStore(mp, p.owner());
        ReflectionTestUtils.setField(p.scheduler(), "clusterSchedule", s);
        return s;
    }

    // ── MP-02: yenilenen sertifika kilidi ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MP-02: kilit süresinden uzun sertifika taraması yenilenir → başka pod'un bayat taraması 20 dk boyunca koşmaz; sonuçlar tek kez işlenir")
    void longCertSweep_renewed_otherPodsStaleSweepSkips() throws Exception {
        inventory("a.example.com", "b.example.com");
        CompletableFuture<Map<String, Object>> slow = new CompletableFuture<>();
        AtomicInteger checks = slowChecks(slow);
        Pod a = pod(1);
        Pod b = pod(2);

        Thread run = startFullRun(a);
        for (int i = 0; i < 40; i++) {                       // 20 dk (kilit süresi 3 dk) — a her 30 sn yeniler
            mp.advance(30 * SEC);
            a.locks().renewAll();
            b.scheduler().checkStaleInventory();             // HATA ÖNCESİ: 10. dk'da süresi "dolan" kilidi alıp aynı alanları tarardı
        }
        assertThat(checks).as("yalnız a'nın iki kontrolü").hasValue(2);

        slow.complete(Map.of());
        run.join(10_000);
        verify(mp.mock(CertificateService.class), times(2)).saveResult(any());
        verify(mp.mock(EscalationService.class), times(1)).processResults(anyList());
        assertThat(mp.schedulerLockHolder("cert-check")).as("bitince bırakıldı").isNull();
    }

    @Test
    @DisplayName("MP-02: kilit tarama sürerken KAYBEDİLİRSE (sahip yenileyemedi, başka pod aldı) sonuçlar kaydedilmez, alarm işlenmez")
    void lockLostMidRun_resultsNotProcessed() throws Exception {
        inventory("a.example.com");
        CompletableFuture<Map<String, Object>> slow = new CompletableFuture<>();
        slowChecks(slow);
        Pod a = pod(1);
        Pod b = pod(2);

        Thread run = startFullRun(a);
        mp.advance(3 * MIN + 2 * SEC);                       // a'nın yenileyicisi durdu (GC, dolu havuz) → kilit doldu
        assertThat(b.locks().tryAcquire("cert-check")).isPresent();
        assertThat(a.locks().renewAll()).isZero();           // a geri geldi: yenileme 0 satır → LOST

        slow.complete(Map.of());
        run.join(10_000);
        verify(mp.mock(CertificateService.class), never()).saveResult(any());
        verify(mp.mock(EscalationService.class), never()).processResults(anyList());
        verify(mp.mock(EscalationService.class), never()).resolveVerifiedStaleCertAlerts(anyList());
        assertThat(mp.schedulerLockHolder("cert-check")).as("a'nın bitişi b'nin kilidini silmez").isEqualTo(b.owner());
    }

    @Test
    @DisplayName("MP-02: kilit sahibi pod ölürse (yenileme yok) diğer pod kilit süresi DOLUNCA devralır, önce değil")
    void holderDies_otherTakesOverAfterTtl() {
        Pod a = pod(1);
        Pod b = pod(2);
        assertThat(a.scheduler().tryAcquireSchedulerLock("escalation-steps", 10)).isTrue();
        mp.advance(2 * MIN + 50 * SEC);
        assertThat(b.scheduler().tryAcquireSchedulerLock("escalation-steps", 10)).isFalse();
        mp.advance(11 * SEC);
        assertThat(b.scheduler().tryAcquireSchedulerLock("escalation-steps", 10)).isTrue();
        assertThat(mp.schedulerLockHolder("escalation-steps")).isEqualTo(b.owner());
    }

    @Test
    @DisplayName("MP-08: kapanışta pod'un iş kilitleri bırakılır — öbür pod süre beklemeden alır; başkasınınkine dokunulmaz")
    void shutdownReleasesOwnJobLocks() {
        Pod a = pod(1);
        Pod b = pod(2);
        assertThat(a.scheduler().tryAcquireSchedulerLock("watchdog", 2)).isTrue();
        assertThat(a.scheduler().tryAcquireSchedulerLock("quiet-digest", 10)).isTrue();
        assertThat(b.scheduler().tryAcquireSchedulerLock("push-overflow", 10)).isTrue();
        a.scheduler().onShutdown();
        assertThat(mp.schedulerLockHolder("watchdog")).isNull();
        assertThat(mp.schedulerLockHolder("quiet-digest")).isNull();
        assertThat(mp.schedulerLockHolder("push-overflow")).isEqualTo(b.owner());
        assertThat(b.scheduler().tryAcquireSchedulerLock("watchdog", 2)).isTrue();
    }

    // ── MP-11: güvenli taraf ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MP-11: liderin kira sorgusu geçici hata verir → güvenli pencere sonunda durur (LOST), takipçi kira dolunca alır; hiçbir anda iki lider yok")
    void leaderTransientLeaseError_noSecondLeader() {
        Pod a = pod(1);
        Pod b = pod(2);
        FlakyStore storeA = flaky(a);
        assertThat(a.scheduler().isSweepLeader()).isTrue();
        assertThat(b.scheduler().isSweepLeader()).isFalse();
        storeA.leaseDown.set(true);                          // a'nın bağlantı havuzu doldu

        boolean bLed = false;
        for (int t = 10; t <= 300; t += 10) {
            mp.advance(10 * SEC);
            boolean la = a.scheduler().isSweepLeader();
            boolean lb = b.scheduler().isSweepLeader();
            assertThat(la && lb).as("t=%d sn: iki lider", t).isFalse();
            if (t < 150) assertThat(la).as("t=%d sn: lider güvenli pencerede sürer (tek pod'da izleme kesilmez)", t).isTrue();
            if (t >= 160) assertThat(la).as("t=%d sn: pencere doldu", t).isFalse();
            bLed |= lb;
        }
        assertThat(bLed).as("takipçi kira dolunca devraldı").isTrue();
        assertThat(reasons(a)).containsExactly(SweepLeadershipChangedEvent.GAINED, SweepLeadershipChangedEvent.LOST);
        assertThat(reasons(b)).containsExactly(SweepLeadershipChangedEvent.GAINED);
        assertThat(a.scheduler().lastConfirmedSweepLeader()).as("hata son doğrulanmış kararı değiştirmez (izleyici)").isTrue();
    }

    @Test
    @DisplayName("MP-11: takipçinin kira sorgusu hata verir → takipçi kendini ASLA lider saymaz (eskiden ikinci lider oluyordu)")
    void followerTransientLeaseError_neverLeader() {
        Pod a = pod(1);
        Pod b = pod(2);
        assertThat(a.scheduler().isSweepLeader()).isTrue();
        FlakyStore storeB = flaky(b);
        storeB.leaseDown.set(true);
        for (int i = 0; i < 12; i++) {
            mp.advance(30 * SEC);
            a.scheduler().renewSweepLeadership();
            assertThat(b.scheduler().isSweepLeader()).isFalse();
            assertThat(a.scheduler().isSweepLeader()).isTrue();
        }
        assertThat(reasons(b)).isEmpty();
    }

    @Test
    @DisplayName("MP-11 / CORE-2: kilidi kazanan pod'un cron tur kaydı geçici hata verir → takım × hafta sahiplenmesiyle korunan "
            + "Pazartesi raporu YİNE DE koşar (hafta kaybolmaz; çift postayı takım sahiplenmesi önler); System Health sayar")
    @SuppressWarnings("unchecked")
    void cronRoundClaimError_claimProtectedJobRunsAnyway() {
        mp.clock().set(Instant.parse("2026-10-19T07:00:00Z").toEpochMilli());   // Pazartesi 10:00 İstanbul
        Pod a = pod(1);
        Pod b = pod(2);
        FlakyStore storeA = flaky(a);
        storeA.claimDown.set(true);                          // kilidi kazanan pod'un havuzu dolu

        a.scheduler().scheduledWeeklyAvailabilityReport();   // ÖNCE: kayıt hatası → tur atlanır, öbür pod kilidi meşgul bulmuştu → hafta KAYBOLURDU
        verify(mp.mock(WeeklyAvailabilityReportService.class), times(1)).sendWeeklyReports(false);
        Map<String, Object> scheduler = (Map<String, Object>) a.scheduler().getSystemHealth().get("scheduler");
        assertThat(scheduler).containsEntry("round_claim_failures", 1L);
        assertThat((String) scheduler.get("last_round_claim_failure")).contains("round:weekly-availability");
        assertThat((List<Map<String, Object>>) scheduler.get("skipped_rounds")).as("atlanmadı").isEmpty();

        mp.advance(5 * SEC);
        b.scheduler().scheduledWeeklyAvailabilityReport();   // kayıt yazılamamıştı → b de koşar; takım sahiplenmesi tek posta
        verify(mp.mock(WeeklyAvailabilityReportService.class), times(2)).sendWeeklyReports(false);
        mp.advance(5 * SEC);
        storeA.claimDown.set(false);                         // a'nın havuzu düzeldi
        a.scheduler().scheduledWeeklyAvailabilityReport();   // tur artık kayıtlı → üçüncü koşu yok
        verify(mp.mock(WeeklyAvailabilityReportService.class), times(2)).sendWeeklyReports(false);
    }

    @Test
    @DisplayName("CORE-2: korunmayan cron işi (Cuma hatırlatması) — tur kaydı birkaç kez hata verip düzelirse cron payı içinde yeniden denenir ve TEK kez koşar")
    void cronRoundClaimTransientError_retriedWithinSlack() {
        mp.clock().set(Instant.parse("2026-10-16T06:00:00Z").toEpochMilli());   // Cuma 09:00 İstanbul
        Pod a = pod(1);
        Pod b = pod(2);
        FlakyStore storeA = flaky(a);
        storeA.claimFailuresLeft.set(3);

        a.scheduler().scheduledWeeklyReportReminder();
        verify(mp.mock(WeeklyReportReminderService.class), times(1)).sendFridayReminders(true);
        mp.advance(5 * SEC);
        b.scheduler().scheduledWeeklyReportReminder();
        verify(mp.mock(WeeklyReportReminderService.class), times(1)).sendFridayReminders(true);
    }

    @Test
    @DisplayName("R4: tur kaydı yeniden denenirken (cron payı ~30 sn) kapanış başlarsa yeni tur ALINMAZ — korunmayan iş de, "
            + "kayıt hatasında 'yine de koşan' korunan iş de başlamaz")
    void shutdownDuringRoundClaimRetry_noNewJob() {
        mp.clock().set(Instant.parse("2026-10-16T06:00:00Z").toEpochMilli());   // Cuma 09:00 İstanbul
        Pod a = pod(1);
        FlakyStore storeA = flaky(a);
        storeA.claimFailuresLeft.set(1);                     // ilk deneme düşer, ikincisi başarırdı
        storeA.onClaimFailure = () -> a.scheduler().onContextClosed(null);   // SIGTERM tam o anda
        a.scheduler().scheduledWeeklyReportReminder();       // ÖNCE: bekleme sonrası tur alınıp hatırlatma başlıyordu
        verify(mp.mock(WeeklyReportReminderService.class), never()).sendFridayReminders(anyBoolean());

        mp.clock().set(Instant.parse("2026-10-19T07:00:00Z").toEpochMilli());   // Pazartesi 10:00 İstanbul
        Pod b = pod(2);
        FlakyStore storeB = flaky(b);
        storeB.claimDown.set(true);                          // kayıt hiç okunamıyor → korunan iş normalde "yine de" koşar
        storeB.onClaimFailure = () -> b.scheduler().onContextClosed(null);
        b.scheduler().scheduledWeeklyAvailabilityReport();
        verify(mp.mock(WeeklyAvailabilityReportService.class), never()).sendWeeklyReports(anyBoolean());
    }

    @Test
    @DisplayName("CORE-2: korunmayan cron işi — tur kaydı cron payı boyunca okunamazsa atlanır ve İZ bırakır (System Health skipped_rounds)")
    @SuppressWarnings("unchecked")
    void cronRoundClaimPersistentError_skippedWithTrace() {
        mp.clock().set(Instant.parse("2026-10-16T06:00:00Z").toEpochMilli());   // Cuma 09:00 İstanbul
        Pod a = pod(1);
        FlakyStore storeA = flaky(a);
        storeA.claimDown.set(true);

        a.scheduler().scheduledWeeklyReportReminder();
        verify(mp.mock(WeeklyReportReminderService.class), never()).sendFridayReminders(anyBoolean());
        Map<String, Object> scheduler = (Map<String, Object>) a.scheduler().getSystemHealth().get("scheduler");
        assertThat(scheduler).containsEntry("round_claim_failures", 1L);
        assertThat((List<Map<String, Object>>) scheduler.get("skipped_rounds"))
                .extracting(m -> m.get("round")).containsExactly("weekly-report-reminder");
    }

    @Test
    @DisplayName("CORE-2 / F5: haftalık erişilebilirlik yakalaması — yalnız tetik gününde, tetikten 20 dk sonra, saatte kümede bir kez; asıl koşu sürerken atlar")
    void weeklyAvailabilityCatchUp_window_andOncePerHour() {
        Pod a = pod(1);
        Pod b = pod(2);
        SchedulerService s = a.scheduler();
        long mon1010 = Instant.parse("2026-10-19T07:10:00Z").toEpochMilli();   // Pazartesi 10:10 İstanbul
        assertThat(s.weeklyAvailabilityCatchUpWindow(mon1010)).as("tetikten 10 dk sonra (asıl koşu sürüyor olabilir)").isFalse();
        assertThat(s.weeklyAvailabilityCatchUpWindow(mon1010 + 15 * MIN)).as("10:25").isTrue();
        assertThat(s.weeklyAvailabilityCatchUpWindow(Instant.parse("2026-10-19T20:25:00Z").toEpochMilli())).as("23:25").isTrue();
        assertThat(s.weeklyAvailabilityCatchUpWindow(Instant.parse("2026-10-20T07:25:00Z").toEpochMilli())).as("Salı").isFalse();
        assertThat(s.weeklyAvailabilityCatchUpWindow(Instant.parse("2026-10-18T07:25:00Z").toEpochMilli())).as("Pazar").isFalse();
        assertThat(s.weeklyAvailabilityCatchUpWindow(Instant.parse("2026-10-19T06:25:00Z").toEpochMilli())).as("tetikten önce").isFalse();

        WeeklyAvailabilityReportService wa = mp.mock(WeeklyAvailabilityReportService.class);
        mp.clock().set(Instant.parse("2026-10-19T07:25:00Z").toEpochMilli());
        assertThat(b.locks().tryAcquire("weekly-availability")).isPresent();   // asıl koşu b'de sürüyor
        a.scheduler().scheduledWeeklyAvailabilityCatchUp();
        verify(wa, never()).sendMissingWeeklyReports();
        b.locks().release("weekly-availability");

        a.scheduler().scheduledWeeklyAvailabilityCatchUp();
        mp.advance(30 * SEC);
        b.scheduler().scheduledWeeklyAvailabilityCatchUp();
        verify(wa, times(1)).sendMissingWeeklyReports();
        mp.advance(60 * MIN);                                // 11:25
        b.scheduler().scheduledWeeklyAvailabilityCatchUp();
        a.scheduler().scheduledWeeklyAvailabilityCatchUp();
        verify(wa, times(2)).sendMissingWeeklyReports();
        mp.clock().set(Instant.parse("2026-10-20T07:25:00Z").toEpochMilli());   // Salı: yakalama yok
        a.scheduler().scheduledWeeklyAvailabilityCatchUp();
        verify(wa, times(2)).sendMissingWeeklyReports();
    }

    @Test
    @DisplayName("MP-11: izleme vadesi geçici hatada — lider güvenli penceresinde yerel karar (izleme sürer); takipçi yoklamaz")
    void monitorDueTransientError_onlyLeaderWindow() {
        Pod a = pod(1);
        Pod b = pod(2);
        assertThat(a.scheduler().isSweepLeader()).isTrue();
        FlakyStore storeA = flaky(a);
        FlakyStore storeB = flaky(b);
        storeA.claimDown.set(true);
        storeB.claimDown.set(true);
        SchedulerService.DueAdvance grid = (prev, now) -> now + MIN;
        assertThat(a.scheduler().clusterDue("port:1", MIN, grid)).as("lider penceresinde").isTrue();
        assertThat(b.scheduler().clusterDue("port:1", MIN, grid)).as("takipçi").isFalse();
    }

    // ── MP-15: iki aşamalı tur, saatlik tur önce kilit ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MP-15: erişilebilirlik turu yarıda kalırsa (lider devretti) tamamlanmaz → yeni lider koşu kirası (10 dk) dolunca yeniden koşar, 1 saat değil")
    void interruptedRound_dueAgainAfterRunLease() {
        inventory("x.example.com");
        AtomicInteger probes = new AtomicInteger();
        Pod a = pod(1);
        Pod b = pod(2);
        AtomicBoolean handOver = new AtomicBoolean(true);
        when(mp.mock(UptimeHttpCheckerService.class).check(eq("x.example.com"), eq(443), anyInt(), eq(false))).thenAnswer(i -> {
            probes.incrementAndGet();
            if (handOver.getAndSet(false)) a.scheduler().releaseSweepLeadership();   // tur sürerken devir (kapanış)
            return Map.of("status", "up", "response_ms", 5L);
        });

        a.scheduler().runUptimeChecks();
        assertThat(probes).hasValue(1);
        mp.advance(6 * SEC);
        b.scheduler().runUptimeChecks();                     // b lider oldu ama koşu kirası dolmadı
        assertThat(b.scheduler().isSweepLeader()).isTrue();
        assertThat(probes).hasValue(1);
        mp.advance(9 * MIN);
        b.scheduler().runUptimeChecks();
        assertThat(probes).as("kira 10 dk").hasValue(1);
        mp.advance(MIN);
        b.scheduler().runUptimeChecks();                     // HATA ÖNCESİ: tur 1 saat kaybolurdu
        assertThat(probes).hasValue(2);
        mp.advance(MIN);
        b.scheduler().runUptimeChecks();
        assertThat(probes).as("tamamlanan tur bir sonraki saate ilerledi").hasValue(2);
        mp.advance(60 * MIN);
        b.scheduler().runUptimeChecks();
        assertThat(probes).hasValue(3);
    }

    @Test
    @DisplayName("MP-15: saatlik tur önce cert-check kilidini alır — başka pod'un bayat taraması tutarken tur TÜKETİLMEZ, kilit boşalınca koşar")
    void hourly_takesLockBeforeRound() {
        mp.clock().set(Instant.parse("2026-10-21T07:00:00Z").toEpochMilli());
        Pod a = pod(1);
        Pod b = pod(2);
        CertificateInventoryRepository repo = mp.mock(CertificateInventoryRepository.class);
        assertThat(b.locks().tryAcquire("cert-check")).isPresent();   // b'nin bayat taraması sürüyor

        a.scheduler().scheduledHourlyCheck();
        verify(repo, never()).findByActiveTrueOrderByDomainAsc();
        assertThat(mp.jdbc().queryForList("SELECT monitor_key FROM monitor_check_schedule WHERE monitor_key = 'round:cert-hourly'"))
                .as("tur tüketilmedi").isEmpty();

        b.locks().release("cert-check");
        mp.advance(20 * SEC);
        a.scheduler().scheduledHourlyCheck();
        verify(repo, times(1)).findByActiveTrueOrderByDomainAsc();
        b.scheduler().scheduledHourlyCheck();
        verify(repo, times(1)).findByActiveTrueOrderByDomainAsc();
        assertThat(mp.schedulerLockHolder("cert-check")).isNull();
    }

    @Test
    @DisplayName("MP-02: bayat sertifika taraması kümede aralık başına bir kez (her pod 5 dk'da bir tetiklense de)")
    void staleSweep_oncePerIntervalAcrossPods() {
        Pod a = pod(1);
        Pod b = pod(2);
        Pod c = pod(3);
        CertificateInventoryRepository repo = mp.mock(CertificateInventoryRepository.class);
        a.scheduler().checkStaleInventory();
        b.scheduler().checkStaleInventory();
        c.scheduler().checkStaleInventory();
        verify(repo, times(1)).findByActiveTrueOrderByDomainAsc();
        mp.advance(5 * MIN);
        c.scheduler().checkStaleInventory();
        b.scheduler().checkStaleInventory();
        verify(repo, times(2)).findByActiveTrueOrderByDomainAsc();
    }

    // ── MP-03: açılış catch-up'ı ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MP-03: yuvarlanan dağıtımda art arda açılan pod'lar catch-up'ı kümede 15 dk'da bir kez koşar")
    void startupCatchUp_oncePerRound() {
        Pod a = pod(1);
        Pod b = pod(2);
        a.scheduler().runStartupCheck();
        mp.advance(2 * MIN);
        b.scheduler().runStartupCheck();
        verify(mp.mock(EscalationService.class), times(1)).catchUpMissedDailyAlerts();
        mp.advance(15 * MIN);
        mp.pod(3, SchedulerMultiPodCoordinationTest::tune).scheduler().runStartupCheck();
        verify(mp.mock(EscalationService.class), times(2)).catchUpMissedDailyAlerts();
    }

    // ── MP-08: erken kapanış ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MP-08: ContextClosedEvent kirayı HEMEN bırakır (RELEASED) → takipçi bir sonraki yoklamada lider; alt bağlamın olayı yok sayılır")
    void contextClosed_releasesLeaseEarly() {
        Pod a = pod(1);
        Pod b = pod(2);
        ApplicationContext ctx = org.mockito.Mockito.mock(ApplicationContext.class);
        ApplicationContext child = org.mockito.Mockito.mock(ApplicationContext.class);
        ReflectionTestUtils.setField(a.scheduler(), "applicationContext", ctx);
        a.scheduler().renewSweepLeadership();
        a.scheduler().startSweepLeaderRenewer();
        assertThat(b.scheduler().isSweepLeader()).isFalse();

        a.scheduler().onContextClosed(new ContextClosedEvent(child));
        assertThat(a.scheduler().isSweepLeader()).as("alt bağlamın kapanışı liderliği bırakmaz").isTrue();

        a.scheduler().onContextClosed(new ContextClosedEvent(ctx));
        assertThat(mp.lease("sweep-leader")).isNull();
        assertThat(ReflectionTestUtils.getField(a.scheduler(), "sweepLeaderRenewer")).isNull();
        assertThat(reasons(a)).containsExactly(SweepLeadershipChangedEvent.GAINED, SweepLeadershipChangedEvent.RELEASED);
        mp.advance(6 * SEC);
        assertThat(b.scheduler().isSweepLeader()).isTrue();
        a.scheduler().onShutdown();                          // @PreDestroy yedeği idempotent: ikinci olay yok
        assertThat(reasons(a)).hasSize(2);
        assertThat(a.scheduler().isSweepLeader()).isFalse();
    }

    @Test
    @DisplayName("MP-08: ContextClosedEvent'ten sonra (zamanlayıcı ~40 sn daha tıklar) pod YENİ kilit / tur / liderlik almaz; süren işin kilidi yenilenir, kapanışta bırakılır")
    void afterContextClosed_noNewWorkStarts() {
        mp.clock().set(Instant.parse("2026-10-19T07:00:00Z").toEpochMilli());   // Pazartesi 10:00 İstanbul (haftalık rapor tetiği)
        Pod a = pod(1);
        Pod b = pod(2);
        ApplicationContext ctx = org.mockito.Mockito.mock(ApplicationContext.class);
        ReflectionTestUtils.setField(a.scheduler(), "applicationContext", ctx);
        assertThat(a.scheduler().tryAcquireSchedulerLock("escalation-steps", 10)).as("süren iş").isTrue();

        a.scheduler().onContextClosed(new ContextClosedEvent(ctx));

        AtomicInteger ran = new AtomicInteger();
        assertThat(a.scheduler().tryAcquireSchedulerLock("quiet-digest", 10)).isFalse();
        assertThat(a.scheduler().callWithSchedulerLock("secret-reencrypt", () -> ran.incrementAndGet())).isEmpty();
        a.scheduler().runWithSchedulerLock("system-maintenance", ran::incrementAndGet);
        assertThat(ran).as("hiçbir yeni iş koşmadı").hasValue(0);
        assertThat(a.scheduler().claimRound("uptime", 60 * MIN)).isFalse();
        a.scheduler().scheduledWeeklyAvailabilityReport();     // geç tetik: kapanan pod raporu göndermez
        a.scheduler().checkStaleInventory();
        a.scheduler().scheduledHourlyCheck();
        a.scheduler().runPortChecks();
        assertThat(a.scheduler().isSweepLeader()).isFalse();
        verify(mp.mock(WeeklyAvailabilityReportService.class), never()).sendWeeklyReports(anyBoolean());
        verify(mp.mock(CertificateInventoryRepository.class), never()).findByActiveTrueOrderByDomainAsc();
        assertThat(mp.jdbc().queryForList("SELECT monitor_key FROM monitor_check_schedule WHERE monitor_key LIKE 'round:%'"))
                .as("hiçbir tur tüketilmedi").isEmpty();

        mp.advance(4 * MIN);
        assertThat(a.locks().renewAll()).as("süren işin kilidi kapanışa dek yenilenir").isEqualTo(1);
        assertThat(b.scheduler().tryAcquireSchedulerLock("escalation-steps", 10)).isFalse();
        a.scheduler().onShutdown();
        assertThat(mp.schedulerLockHolder("escalation-steps")).isNull();

        b.scheduler().scheduledWeeklyAvailabilityReport();     // canlı pod turu koşar
        verify(mp.mock(WeeklyAvailabilityReportService.class), times(1)).sendWeeklyReports(false);
        assertThat(b.scheduler().tryAcquireSchedulerLock("escalation-steps", 10)).isTrue();
    }

    // ── MP-24: tıkanmış lider ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MP-24: liderde 10 dk hiç tarama tıkı yoksa kirayı bırakır, takipçi devralır; tık gelmeden geri almaz; 30 dk'da canlılık BROKEN, tık dönünce CORRECT")
    void wedgedLeader_yields_thenLivenessBroken() {
        Pod a = pod(1);
        Pod b = pod(2);
        a.scheduler().runPortChecks();                       // a lider ve bir tık attı; sonra zamanlayıcı havuzu kilitlenir
        assertThat(a.scheduler().isSweepLeader()).isTrue();

        for (int m = 1; m <= 9; m++) {
            mp.advance(MIN);
            a.scheduler().renewSweepLeadership();
            b.scheduler().renewSweepLeadership();
            assertThat(a.scheduler().isSweepLeader()).as("%d. dk", m).isTrue();
        }
        mp.advance(MIN);                                     // 10. dk
        a.scheduler().renewSweepLeadership();
        assertThat(reasons(a)).containsExactly(SweepLeadershipChangedEvent.GAINED, SweepLeadershipChangedEvent.LOST);
        b.scheduler().renewSweepLeadership();
        assertThat(b.scheduler().isSweepLeader()).as("takipçi hemen devraldı (kira bırakıldı)").isTrue();

        for (int m = 11; m < 30; m++) {                      // a tıksız: geri almaz, BROKEN yok (henüz)
            mp.advance(MIN);
            b.scheduler().renewSweepLeadership();
            a.scheduler().renewSweepLeadership();
            assertThat(a.scheduler().isSweepLeader()).isFalse();
        }
        assertThat(a.events().ofType(AvailabilityChangeEvent.class)).isEmpty();
        mp.advance(MIN);                                     // son tıktan 30 dk
        a.scheduler().renewSweepLeadership();
        assertThat(a.events().ofType(AvailabilityChangeEvent.class)).extracting(AvailabilityChangeEvent::getState)
                .containsExactly(LivenessState.BROKEN);

        mp.advance(MIN);
        a.scheduler().runPortChecks();                       // tıklar döndü (takipçi olarak)
        a.scheduler().renewSweepLeadership();
        assertThat(a.events().ofType(AvailabilityChangeEvent.class)).extracting(AvailabilityChangeEvent::getState)
                .containsExactly(LivenessState.BROKEN, LivenessState.CORRECT);
        assertThat(b.scheduler().isSweepLeader()).isTrue();
    }

    @Test
    @DisplayName("MP-24: doygun ama ilerleyen lider (2 dk'da bir tık) asla bırakmaz; hiç tık olmamış pod (zamanlayıcı yok) bırakmaz")
    void saturatedLeader_neverYields() {
        Pod a = pod(1);
        Pod b = pod(2);
        a.scheduler().runPortChecks();
        for (int m = 1; m <= 40; m++) {
            mp.advance(MIN);
            if (m % 2 == 0) a.scheduler().runPortChecks();
            a.scheduler().renewSweepLeadership();
            b.scheduler().renewSweepLeadership();
            assertThat(b.scheduler().isSweepLeader()).isFalse();
        }
        assertThat(reasons(a)).containsExactly(SweepLeadershipChangedEvent.GAINED);

        Pod c = mp.newPod("solo-00000003");                  // hiç tarama tıkı olmayan pod (yalnız yenileyici)
        mp.advance(4 * MIN);                                 // a öldü → c devralır
        for (int m = 0; m < 30; m++) {
            c.scheduler().renewSweepLeadership();
            mp.advance(MIN);
        }
        assertThat(c.scheduler().isSweepLeader()).isTrue();
    }

    // ── R3-1 3e: yan iş koşucusu ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R3-1 3e: saatlik sertifika turu yan iş koşucusunda — zamanlayıcı iş parçacığı hemen döner, tur sürerken yeniden tetik "
            + "birleştirilir; başka pod'un bayat taraması cert-check kilidini alamaz (tek tarama), sonuçlar bir kez işlenir")
    void hourlySweepOnSideRunner_coalesced_otherPodSkips() throws Exception {
        mp.clock().set(Instant.parse("2026-10-21T07:00:00Z").toEpochMilli());
        inventory("a.example.com");
        CompletableFuture<Map<String, Object>> slow = new CompletableFuture<>();
        AtomicInteger checks = slowChecks(slow);
        Pod a = pod(1);
        Pod b = pod(2);
        com.sitemonitor.service.schedule.SideJobRunner runner =
                new com.sitemonitor.service.schedule.SideJobRunner(2, mp.clockSupplier());
        a.scheduler().setSideJobRunner(runner);
        try {
            a.scheduler().scheduledHourlyCheck();            // döner; gövde koşucuda
            long deadline = System.currentTimeMillis() + 10_000;
            while (checks.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(5);
            assertThat(checks).hasValue(1);
            assertThat(mp.schedulerLockHolder("cert-check")).isEqualTo(a.owner());

            a.scheduler().scheduledHourlyCheck();            // Spring sürerken yeniden tetikler → birleştirilir
            assertThat(runner.status().jobs()).filteredOn(j -> j.name().equals("cert-hourly"))
                    .singleElement().satisfies(j -> {
                        assertThat(j.skippedCount()).isEqualTo(1);
                        assertThat(j.state()).isEqualTo(com.sitemonitor.service.schedule.SideJobRunner.STATE_RUNNING);
                    });
            b.scheduler().checkStaleInventory();             // b: satır içi (koşucusuz, 20.167.0 gibi) — kilit a'da
            assertThat(checks).as("tek tarama").hasValue(1);

            slow.complete(Map.of());
            assertThat(runner.awaitIdle(10_000)).isTrue();
            verify(mp.mock(CertificateService.class), times(1)).saveResult(any());
            verify(mp.mock(EscalationService.class), times(1)).processResults(anyList());
            assertThat(mp.schedulerLockHolder("cert-check")).as("bitince bırakıldı").isNull();
        } finally {
            runner.stopAndWait();
        }
    }

    @Test
    @DisplayName("R3-1 3e: kapanışta yan iş koşucusu iş kilitlerinden ÖNCE durur — koşan gece temizliği kesilip adımını bırakmadan "
            + "pod'un kilitleri bırakılmaz (kilit başka pod'a geçip iş iki kez koşmasın)")
    void shutdown_stopsSideJobsBeforeReleasingOwnLocks() throws Exception {
        mp.clock().set(Instant.parse("2026-10-21T00:00:00Z").toEpochMilli());   // 03:00 İstanbul
        Pod a = pod(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicBoolean jobStoppedBeforeRelease = new AtomicBoolean();
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        com.sitemonitor.service.schedule.ClusterLockService locks = new com.sitemonitor.service.schedule.ClusterLockService(
                mp.jdbc(), a.owner(), mp.clockSupplier(), false) {
            @Override
            public int releaseAllOwn() {
                jobStoppedBeforeRelease.set(interrupted.get());
                return super.releaseAllOwn();
            }
        };
        ReflectionTestUtils.setField(a.scheduler(), "lockService", locks);
        ReflectionTestUtils.setField(a.scheduler(), "nightlyLockWaitMs", 0L);
        when(mp.mock(com.sitemonitor.service.retention.RetentionService.class).runCleanup()).thenAnswer(i -> {
            started.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.set(true);                       // bayrak yeniden kurulmaz: iş adımını bırakıp kilidini çözer
            }
            return null;
        });
        com.sitemonitor.service.schedule.SideJobRunner runner =
                new com.sitemonitor.service.schedule.SideJobRunner(1, mp.clockSupplier());
        a.scheduler().setSideJobRunner(runner);

        a.scheduler().cleanupOldLogs();
        assertThat(started.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(mp.schedulerLockHolder("nightly-cleanup")).isEqualTo(a.owner());

        a.scheduler().onShutdown();                          // koşucu: ortak süre (testte 5 sn) → kes → adımı bırakmasını bekle → kilitler
        assertThat(interrupted).as("koşan iş kesildi").isTrue();
        assertThat(jobStoppedBeforeRelease).as("iş kilitleri, yan iş durduktan SONRA bırakıldı").isTrue();
        assertThat(mp.schedulerLockHolder("nightly-cleanup")).isNull();
        assertThat(runner.accepting()).isFalse();
    }
}
