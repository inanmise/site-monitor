package com.sitemonitor.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.service.checks.CheckJobRunner;
import com.sitemonitor.service.checks.CheckJobStore;
import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.checks.CheckResultRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-3 inceleme düzeltmeleri, gönderim / değerlendirme tarafı (FX-EVAL; inceleme {@code r3-3-review-dispatch-apply},
 * {@code r3-3-review-drain-handover}, {@code r3-3-review-queue-worker}). İncelemenin yoklama testlerinin (R33ReviewDispatchProbeTest)
 * KALICI tersleri: her test düzeltme olmadan kırmızıdır.
 */
class CheckPipelineReviewFixTest {

    R33SweepHarness h;
    R33SweepHarness.FakeStore store;
    R33SweepHarness.FakeRegistry registry;
    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeEach
    void setUp() throws Exception {
        h = new R33SweepHarness();
        store = new R33SweepHarness.FakeStore();
        registry = new R33SweepHarness.FakeRegistry();
        h.scheduler.setCheckJobInfrastructure(store, registry);
        for (String t : CheckJobTypes.ALL) registry.live.put(t, 1);
        when(h.scriptedChecker.ensureProbed()).thenReturn(true);
        logger = (Logger) LoggerFactory.getLogger(SchedulerService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        Thread.interrupted();   // kesme testleri bayrağı başka teste sızdırmasın
    }

    private boolean mode(String type) {
        return (boolean) ReflectionTestUtils.invokeMethod(h.scheduler, "distributedMode", type);
    }

    private Set<Long> dispatchedIds(String type) {
        return new TreeSet<>(store.byType().getOrDefault(type, List.of()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> dueKeys() {
        return (Map<String, Long>) ReflectionTestUtils.getField(h.scheduler, "lastMonitorCheckAt");
    }

    private List<String> warns(String prefix) {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(prefix)).toList();
    }

    // ── DA-M1 / DH-M2: boru hattı sağlığı ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DA-M1: gönderim art arda 2 kez düşerse tür klasiğe döner (bir WARN) ve klasik gövde vadesi harcanmış izlemeleri DE kontrol eder; 60 sn sonra yeniden dağıtık")
    void persistentDispatchFailureFallsBackToClassic() {
        h.bindDistributed(true, CheckJobTypes.PORT);
        h.portFixture();
        store.dispatchFailure = new DataAccessResourceFailureException("relation monitor_check_jobs does not exist");
        h.scheduler.dispatchCheckJobs();
        assertThat(mode(CheckJobTypes.PORT)).as("tek düşüş kipi oynatmaz").isTrue();
        h.clock.addAndGet(5_000L);
        h.scheduler.dispatchCheckJobs();
        assertThat(mode(CheckJobTypes.PORT)).as("iki ardışık düşüş → klasik").isFalse();
        assertThat(warns("Kontrol işleri art arda 2 kez gönderilemedi (port)")).hasSize(1);
        assertThat(h.scheduler.distributedCheckStatus().get("dispatch_held")).isEqualTo(List.of(CheckJobTypes.PORT));

        // klasik gövde: vadeler gönderim turunda harcanmıştı (checkDue = false) — bekletilen dönem yine kontrol edilir
        ReflectionTestUtils.invokeMethod(h.scheduler, "runPortChecksLocked");
        verify(h.portChecker, times(6)).check(any(PortMonitor.class));
        assertThat(h.scheduler.distributedCheckStatus().get("retained")).isEqualTo(0L);

        h.clock.addAndGet(SchedulerService.DISTRIBUTED_MODE_HOLD_MS);
        store.dispatchFailure = null;
        assertThat(mode(CheckJobTypes.PORT)).as("bekletme bitti → yeniden dağıtık").isTrue();
    }

    @Test
    @DisplayName("DA-M1: düşen turun vadesi harcanmış izlemeleri sonraki tıkta yeniden sunulur (dönem kaybolmaz)")
    void failedDispatchIdsAreReofferedNextTick() {
        h.bindDistributed(true, CheckJobTypes.PORT);
        h.portFixture();
        store.dispatchFailure = new DataAccessResourceFailureException("bağlantı koptu");
        h.scheduler.dispatchCheckJobs();
        assertThat(store.dispatched).isEmpty();
        store.dispatchFailure = null;
        h.clock.addAndGet(5_000L);   // aralık (60 sn) dolmadı — checkDue hepsi için false
        h.scheduler.dispatchCheckJobs();
        assertThat(dispatchedIds(CheckJobTypes.PORT)).containsExactly(1L, 2L, 3L, 4L, 6L, 7L);
        assertThat(h.scheduler.distributedCheckStatus().get("retained")).isEqualTo(0L);
        store.dispatched.clear();
        h.clock.addAndGet(5_000L);
        h.scheduler.dispatchCheckJobs();
        assertThat(store.dispatched).as("yeniden sunulan dönem bir kez").isEmpty();
    }

    @Test
    @DisplayName("Son inceleme F1: gönderim yarıda düşerse YAZILMIŞ parçalar yeniden sunulmaz (aynı dönemde ikinci kontrol yok); yalnız yazılmayanlar sunulur")
    void partialDispatch_onlyUnwrittenIdsReoffered() {
        h.bindDistributed(true, CheckJobTypes.PORT);
        h.portFixture();
        store.dispatchFailure = new CheckJobStore.PartialDispatchException("kısmen", new DataAccessResourceFailureException("koptu"),
                List.of(1L, 2L, 3L));
        h.scheduler.dispatchCheckJobs();
        assertThat(h.scheduler.distributedCheckStatus().get("retained")).isEqualTo(3L);
        store.dispatchFailure = null;
        h.clock.addAndGet(5_000L);
        h.scheduler.dispatchCheckJobs();
        assertThat(dispatchedIds(CheckJobTypes.PORT)).as("yalnız yazılmayan parça").containsExactly(4L, 6L, 7L);
        assertThat(h.scheduler.distributedCheckStatus().get("retained")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Son inceleme F3: dağıtıkta gönderilen DNS (60 sn aralık, 300 sn tık) klasiğe dönünce hemen yeniden kontrol edilmez — gönderimin vadesine uyulur")
    void dnsClassicAfterDistributed_respectsDispatchDue() {
        // GERÇEK küme vade tablosu (H2): "aralık kısaltıldı" denetimi (ClusterScheduleService.isDue) yalnız bu yolda var
        try (com.sitemonitor.it.MultiPodTestSupport mp = com.sitemonitor.it.MultiPodTestSupport.create()) {
            ReflectionTestUtils.setField(h.scheduler, "clusterSchedule", mp.schedule("pod1-00000001"));
            assertThat(h.scheduler.isSweepLeader()).as("gerçek kira alındı").isTrue();
            dnsClassicAfterDistributedBody();
        }
    }

    private void dnsClassicAfterDistributedBody() {
        h.bindDistributed(true, CheckJobTypes.DNS);
        List<DnsMonitor> all = h.dnsFixture();
        List<Object[]> rows = new ArrayList<>();
        for (DnsMonitor m : all) {
            m.setIntervalSeconds(60);
            rows.add(new Object[] {m.getId(), m.getIntervalSeconds(), m.getStandalone(), m.getDomain()});
        }
        Mockito.doReturn(rows).when(h.dnsRepo).findCheckDispatchRows();
        assertThat(mode(CheckJobTypes.DNS)).isTrue();
        h.scheduler.dispatchCheckJobs();
        assertThat(dispatchedIds(CheckJobTypes.DNS)).isNotEmpty();
        // kayıt okunamadı (UNKNOWN) → tür histerezissiz hemen klasik (gönderim bekletmesi / boşaltamama da aynı)
        registry.live.put(CheckJobTypes.DNS, com.sitemonitor.service.checks.CheckWorkerRegistry.UNKNOWN);
        assertThat(mode(CheckJobTypes.DNS)).isFalse();
        h.clock.addAndGet(30_000L);
        ReflectionTestUtils.invokeMethod(h.scheduler, "runDnsChecksLocked");
        verify(h.dnsChecker, never()).check(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        h.clock.addAndGet(270_000L);   // gönderimin yazdığı vade (dispatch + 300 sn)
        ReflectionTestUtils.invokeMethod(h.scheduler, "runDnsChecksLocked");
        verify(h.dnsChecker, Mockito.atLeastOnce()).check(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Son inceleme F1: bekletilen dönemler liderlik olayında silinir (yeniden lider olan pod ara liderin işlediklerini sunmaz)")
    void retainedDueClearedOnLeadershipEvent() {
        h.bindDistributed(true, CheckJobTypes.PORT);
        h.portFixture();
        store.dispatchFailure = new DataAccessResourceFailureException("koptu");
        h.scheduler.dispatchCheckJobs();
        assertThat(h.scheduler.distributedCheckStatus().get("retained")).isEqualTo(6L);
        ReflectionTestUtils.invokeMethod(h.scheduler, "publishSweepLeadership", false, h.clock.get(),
                com.sitemonitor.service.schedule.SweepLeadershipChangedEvent.LOST);
        assertThat(h.scheduler.distributedCheckStatus().get("retained")).isEqualTo(0L);
    }

    @Test
    @DisplayName("DH-M2: boşaltıcı yok / boşaltamıyor → tür klasik (boşaltıcı yoksa bir WARN); sağlıklı → dağıtık")
    void drainHealthGatesDistributedMode() {
        h.bindDistributed(true, CheckJobTypes.HTTP);
        assertThat(mode(CheckJobTypes.HTTP)).isTrue();
        h.drainer.healthy = false;
        assertThat(mode(CheckJobTypes.HTTP)).as("boşaltamayan lider dağıtmaz").isFalse();
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
                .contains("Dağıtık kontroller: http artık klasik (sonuçlar boşaltılamıyor)");
        h.drainer.healthy = true;
        assertThat(mode(CheckJobTypes.HTTP)).isTrue();

        ReflectionTestUtils.setField(h.scheduler, "checkResultDrainer", null);
        assertThat(mode(CheckJobTypes.HTTP)).isFalse();
        assertThat(mode(CheckJobTypes.PORT)).isFalse();
        assertThat(warns("Dağıtık kontroller açık ama bu pod'da sonuç boşaltıcısı yok")).hasSize(1);
        h.httpFixture();
        h.scheduler.dispatchCheckJobs();
        assertThat(store.dispatched).isEmpty();
    }

    // ── DA-M2: sıklık = klasik ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DA-M2: dağıtık kip klasik tıktan sık göndermez — 60 sn'lik DNS izlemesi 300 sn'lik tıkta 600 sn'de 2 kez (klasik gibi)")
    void dispatchNeverMoreOftenThanTheClassicTick() {
        h.bindDistributed(true, CheckJobTypes.DNS);
        List<DnsMonitor> dm = h.dnsFixture();
        dm.forEach(m -> m.setIntervalSeconds(60));
        List<Object[]> rows = new ArrayList<>();
        for (DnsMonitor m : dm) rows.add(new Object[]{m.getId(), m.getIntervalSeconds(), m.getStandalone(), m.getDomain()});
        when(h.dnsRepo.findCheckDispatchRows()).thenReturn(rows);
        for (int s = 0; s < 600; s += 5) {
            h.scheduler.dispatchCheckJobs();
            h.clock.addAndGet(5_000L);
        }
        long jobs = store.byType().getOrDefault(CheckJobTypes.DNS, List.of()).stream().filter(id -> id == 1L).count();
        assertThat(jobs).isEqualTo(2);
        assertThat(h.scheduler.dispatchIntervalSeconds(CheckJobTypes.DNS, 60)).isEqualTo(300);
        assertThat(h.scheduler.dispatchIntervalSeconds(CheckJobTypes.DNS, 900)).isEqualTo(900);
        assertThat(h.scheduler.dispatchIntervalSeconds(CheckJobTypes.PORT, 60)).isEqualTo(60);
        assertThat(h.scheduler.dispatchIntervalSeconds(CheckJobTypes.PORT, 10)).isEqualTo(30);
        assertThat(h.scheduler.dispatchIntervalSeconds(CheckJobTypes.PING, null)).isEqualTo(60);
    }

    // ── QW-M2: sayfa kirası canlı ayardan ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("QW-M2: sayfa / sayfa hızı kirası canlı max-check-seconds'ı kapsar (170 sn → ≥ 200 sn), varsayılan 150 sn, tavan 600 sn")
    void pageLeaseCoversConfiguredMaxCheckSeconds() {
        h.bindDistributed(true, "page,pagespeed,http");
        h.pageFixture();
        h.pageSpeedFixture();
        h.httpFixture();
        h.settings.put("site.monitor.page.max-check-seconds", 170);
        h.settings.put("site.monitor.pagespeed.max-check-seconds", 5_000);
        h.scheduler.dispatchCheckJobs();
        for (int i = 0; i < store.dispatched.size(); i++) {
            String type = store.dispatched.get(i).getKey();
            long lease = store.leases.get(i);
            switch (type) {
                case CheckJobTypes.PAGE -> assertThat(lease).isEqualTo(200_000L);
                case CheckJobTypes.PAGESPEED -> assertThat(lease).isEqualTo(CheckJobTypes.LEASE_MS_MAX);
                default -> assertThat(lease).isEqualTo(CheckJobTypes.LEASE_MS_DEFAULT);
            }
        }
        assertThat(store.byType().keySet()).contains(CheckJobTypes.PAGE, CheckJobTypes.PAGESPEED);
        assertThat(CheckJobTypes.leaseMs(CheckJobTypes.PAGE, 120)).isEqualTo(CheckJobTypes.LEASE_MS_DEFAULT);
        assertThat(CheckJobTypes.leaseMs(CheckJobTypes.PAGESPEED, 10)).isEqualTo(CheckJobTypes.LEASE_MS_DEFAULT);
        assertThat(CheckJobTypes.leaseMs(CheckJobTypes.SCRIPTED, 500)).isEqualTo(CheckJobTypes.LEASE_MS_SCRIPTED);
        assertThat(CheckJobTypes.leaseMs(CheckJobTypes.PORT, 500)).isEqualTo(CheckJobTypes.LEASE_MS_DEFAULT);
        assertThat(CheckJobTypes.leaseMs(CheckJobTypes.PAGE, -5)).isEqualTo(CheckJobTypes.LEASE_MS_DEFAULT);
    }

    // ── DA-L1 / DA-L2 / DA-L3 / DA-L4 ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DA-L1: kontrolden sonra yayılım denetimi kapatıldıysa kuyruktaki sonuç DNS_INCONSISTENT kalemi üretmez")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void dnsPropagationDisabledAfterCheck_noInconsistentItem() throws Exception {
        h.bindDistributed(true, CheckJobTypes.DNS);
        List<DnsMonitor> all = h.dnsFixture();
        CheckResultRow row = h.workerRow(CheckJobTypes.DNS, 5L);   // e.example.com: yayılım açık, tutarsız
        DnsMonitor e = all.stream().filter(m -> m.getId() == 5L).findFirst().orElseThrow();
        e.setPropagationCheck(false);
        h.clearMos();
        h.scheduler.applyCheckResults(List.of(row));
        ArgumentCaptor<List> inc = ArgumentCaptor.forClass(List.class);
        verify(h.outage).handleDnsSweep(anyList(), anyList(), anyList(), anyList(), inc.capture());
        assertThat((List<?>) inc.getValue()).isEmpty();

        e.setPropagationCheck(true);   // açıkken kalem yine gelir (davranış korunur)
        h.clearMos();
        h.scheduler.applyCheckResults(List.of(h.workerRow(CheckJobTypes.DNS, 5L)));
        verify(h.outage).handleDnsSweep(anyList(), anyList(), anyList(), anyList(), inc.capture());
        assertThat((List<?>) inc.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("DA-L2: envanter okuması düşerse hiçbir izlemenin vadesi harcanmaz (okuma sahiplenmeden ÖNCE)")
    void inventoryReadFailureClaimsNothing() {
        h.bindDistributed(true, CheckJobTypes.PORT);
        h.portFixture();
        when(h.inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenThrow(new DataAccessResourceFailureException("db blip"));
        h.scheduler.dispatchCheckJobs();
        assertThat(store.dispatched).isEmpty();
        assertThat(dueKeys().keySet()).noneMatch(k -> k.startsWith("port:"));
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("inv-host.example.com");
        inv.setActive(true);
        Mockito.doReturn(List.of(inv)).when(h.inventoryRepo).findByActiveTrueOrderByDomainAsc();
        h.clock.addAndGet(5_000L);
        h.scheduler.dispatchCheckJobs();
        assertThat(dispatchedIds(CheckJobTypes.PORT)).containsExactly(1L, 2L, 3L, 4L, 6L, 7L);
    }

    @Test
    @DisplayName("DA-L3: işçi zaman aşımında kesilen DNS / k6 işi geç dönse de geçmiş satırı yazmaz (kesme bayrağı korunur)")
    void interruptedJobBodiesWriteNothing() {
        h.dnsFixture();
        Thread.currentThread().interrupt();
        Map<String, Object> dns = h.scheduler.runCheckJob(CheckJobTypes.DNS, 1L);
        assertThat(Thread.currentThread().isInterrupted()).as("bayrak işçiye kalır").isTrue();
        Thread.interrupted();
        assertThat(dns).containsKey("main_failed").doesNotContainKey("main");
        verify(h.dnsRecordRepo, never()).save(any());

        h.scriptedFixture();
        Thread.currentThread().interrupt();
        Map<String, Object> k6 = h.scheduler.runCheckJob(CheckJobTypes.SCRIPTED, 1L);
        assertThat(Thread.interrupted()).isTrue();
        assertThat(CheckJobRunner.carriesResult(k6)).isFalse();
        verify(h.scriptedCheckRepo, never()).save(any());

        // kesilmemiş iş bugünkü gibi yazar
        assertThat(CheckJobRunner.carriesResult(h.scheduler.runCheckJob(CheckJobTypes.DNS, 1L))).isTrue();
        verify(h.dnsRecordRepo, times(1)).save(any());
        assertThat(CheckJobRunner.carriesResult(h.scheduler.runCheckJob(CheckJobTypes.SCRIPTED, 1L))).isTrue();
        verify(h.scriptedCheckRepo, times(1)).save(any());
    }

    @Test
    @DisplayName("DA-L3 + FX-QUEUE: kesme bayrağı gövdede temizlense de işçi işi bıraktıysa DNS / k6 geçmiş satırı yazılmaz")
    void abandonedJobWithClearedFlagWritesNothing() {
        h.dnsFixture();
        Map<String, Object> dns = com.sitemonitor.service.checks.CheckWorkerTestHooks.runAsAbandonedJob(
                () -> h.scheduler.runCheckJob(CheckJobTypes.DNS, 1L));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        assertThat(dns).containsKey("main_failed").doesNotContainKey("main");
        verify(h.dnsRecordRepo, never()).save(any());

        h.scriptedFixture();
        Map<String, Object> k6 = com.sitemonitor.service.checks.CheckWorkerTestHooks.runAsAbandonedJob(
                () -> h.scheduler.runCheckJob(CheckJobTypes.SCRIPTED, 1L));
        assertThat(CheckJobRunner.carriesResult(k6)).isFalse();
        verify(h.scriptedCheckRepo, never()).save(any());

        // işçi dışında (bırakılmamış) bugünkü gibi yazar
        assertThat(CheckJobRunner.carriesResult(h.scheduler.runCheckJob(CheckJobTypes.DNS, 1L))).isTrue();
        verify(h.dnsRecordRepo, times(1)).save(any());
    }

    @Test
    @DisplayName("Son inceleme F4: işçinin bıraktığı port / keyword / http / ping / sayfa / sayfa hızı işi kesmenin ürettiği sahte başarısızlığı geçmişe yazmaz")
    void abandonedJobsOfEveryTypeWriteNoHistory() {
        h.portFixture();
        h.keywordFixture();
        h.httpFixture();
        h.pingFixture();
        h.pageFixture();
        h.pageSpeedFixture();
        for (String type : List.of(CheckJobTypes.PORT, CheckJobTypes.KEYWORD, CheckJobTypes.HTTP, CheckJobTypes.PING,
                CheckJobTypes.PAGE, CheckJobTypes.PAGESPEED)) {
            Map<String, Object> out = com.sitemonitor.service.checks.CheckWorkerTestHooks.runAsAbandonedJob(
                    () -> h.scheduler.runCheckJob(type, 1L));
            assertThat(CheckJobRunner.carriesResult(out)).as(type).isFalse();
        }
        verify(h.portCheckRepo, never()).save(any());
        verify(h.keywordResultRepo, never()).save(any());
        verify(h.httpCheckRepo, never()).save(any());
        verify(h.pingCheckRepo, never()).save(any());
        verify(h.pageCheckRepo, never()).save(any());
        verify(h.pageSpeedCheckRepo, never()).save(any());

        // bırakılmamış iş bugünkü gibi yazar
        assertThat(CheckJobRunner.carriesResult(h.scheduler.runCheckJob(CheckJobTypes.HTTP, 1L))).isTrue();
        verify(h.httpCheckRepo, times(1)).save(any());
        assertThat(CheckJobRunner.carriesResult(h.scheduler.runCheckJob(CheckJobTypes.PORT, 1L))).isTrue();
        verify(h.portCheckRepo, times(1)).save(any());
    }

    @Test
    @DisplayName("DA-L4: dağıtık kipte port görevleri izlemeleri yalnız 5 dk'lık temizlik vadesinde okur")
    void portDutiesReadMonitorsOnlyWhenCleanupIsDue() {
        h.bindDistributed(true, CheckJobTypes.PORT);
        h.portFixture();
        assertThat(mode(CheckJobTypes.PORT)).isTrue();
        ReflectionTestUtils.invokeMethod(h.scheduler, "runPortChecksLocked");
        h.clock.addAndGet(30_000L);
        ReflectionTestUtils.invokeMethod(h.scheduler, "runPortChecksLocked");
        verify(h.portRepo, times(1)).findByActiveTrue();
        verify(h.portChecker, never()).check(any(PortMonitor.class));
    }

    // ── QW-L7: boşta kilit + biçme yok ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("QW-L7: hiçbir tür dağıtık değil ve son biçme boş → kilit + biçme her 5 sn'de koşmaz; anahtar açıkken en az 60 sn'de bir")
    void idleTickSkipsLockAndReap() {
        h.bindDistributed(true, null);
        for (String t : CheckJobTypes.ALL) registry.live.put(t, 0);   // tek pod: her tür klasik
        h.scheduler.dispatchCheckJobs();
        assertThat(store.calls).as("ilk tık biçer").isEqualTo(1);
        Mockito.clearInvocations(h.jdbc);
        for (int i = 0; i < 11; i++) {
            h.clock.addAndGet(5_000L);
            h.scheduler.dispatchCheckJobs();
        }
        assertThat(store.calls).as("55 sn boyunca biçme yok").isEqualTo(1);
        verifyNoInteractions(h.jdbc);   // kilit deyimi de yok
        h.clock.addAndGet(5_000L);
        h.scheduler.dispatchCheckJobs();
        assertThat(store.calls).as("60 sn → yeniden biçme").isEqualTo(2);

        // bir tür dağıtıkken her tık kilit + biçme (bugünkü gibi)
        registry.live.put(CheckJobTypes.HTTP, 1);
        h.clock.addAndGet(5_000L);
        h.scheduler.dispatchCheckJobs();
        h.clock.addAndGet(5_000L);
        h.scheduler.dispatchCheckJobs();
        assertThat(store.calls).isEqualTo(4);
    }

    // ── DH-M1 b / c: klasik gövdenin damgası ve bekletmesi ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DH-M1 b: klasik gövde başlattığı her zamanlanmış kontrolü boşaltıcıya bildirir (dağıtıktan dönüşte eski sonuç düşer)")
    void classicBodyNotesItsChecks() {
        h.bindDistributed(true, CheckJobTypes.PORT);
        registry.live.put(CheckJobTypes.PORT, 0);
        h.portFixture();
        ReflectionTestUtils.invokeMethod(h.scheduler, "runPortChecksLocked");
        assertThat(h.drainer.classicNotes).containsExactlyInAnyOrder("port:1", "port:2", "port:3", "port:4", "port:6",
                "port:7");
    }

    @Test
    @DisplayName("DH-M1 c: GAINED sonrası birikim boşalmadıysa klasik gövde dönem harcamadan döner; bekleme içinde yetişirse aynı tıkta koşar")
    void classicBodyWaitsForTheDrainCatchUp() throws Exception {
        h.bindDistributed(true, CheckJobTypes.PORT);
        registry.live.put(CheckJobTypes.PORT, 0);   // klasik kip
        h.portFixture();
        h.scheduler.classicCatchUpWaitMs = 0L;
        h.drainer.holdClassic = true;
        ReflectionTestUtils.invokeMethod(h.scheduler, "runPortChecksLocked");
        verify(h.portChecker, never()).check(any(PortMonitor.class));
        assertThat(dueKeys().keySet()).as("dönem harcanmadı").noneMatch(k -> k.startsWith("port:"));

        h.scheduler.classicCatchUpWaitMs = 3_000L;
        Thread release = new Thread(() -> {
            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            h.drainer.holdClassic = false;
        });
        release.start();
        long t0 = System.nanoTime();
        ReflectionTestUtils.invokeMethod(h.scheduler, "runPortChecksLocked");
        release.join(5_000L);
        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(2_500L);
        verify(h.portChecker, times(6)).check(any(PortMonitor.class));
    }

    @Test
    @DisplayName("K-1: anahtar KAPALI → gerçek (kurulmamış) boşaltıcıyla klasik gövde bugünkü gibi: bekletme yok, damga yok, gönderim yok")
    void offSwitchClassicIsUntouched() {
        com.sitemonitor.service.checks.DistributedChecks dc = h.bindDistributed(false, null);
        com.sitemonitor.service.checks.CheckResultDrainer real = new com.sitemonitor.service.checks.CheckResultDrainer(dc,
                () -> h.scheduler, () -> h.outage, () -> store, () -> null, () -> null, () -> null);
        real.registerStarter();   // anahtar kapalı → başlatıcı hiç koşmaz
        ReflectionTestUtils.setField(h.scheduler, "checkResultDrainer", real);
        h.portFixture();
        ReflectionTestUtils.invokeMethod(h.scheduler, "runPortChecksLocked");
        verify(h.portChecker, times(6)).check(any(PortMonitor.class));
        assertThat(real.started()).isFalse();
        assertThat(real.holdClassic()).isFalse();
        assertThat(real.canDrain()).isFalse();
        h.scheduler.dispatchCheckJobs();
        assertThat(store.calls).isZero();
        assertThat(h.scheduler.distributedCheckStatus()).containsEntry("retained", 0L).containsEntry("dispatch_holds", 0L);
        assertThat(real.status().droppedSuperseded()).isZero();
    }

    @Test
    @DisplayName("DH-M1 a: gönderim tıkı her göndermeden önce boşaltıcının tabanını koyar; anahtar kapalı görülünce boşaltıcıya bildirir")
    void dispatchSetsTheFloorBeforeDispatching() {
        h.bindDistributed(true, CheckJobTypes.HTTP);
        h.httpFixture();
        h.scheduler.dispatchCheckJobs();
        assertThat(h.drainer.floorCalls.get()).isEqualTo(1);
        h.settings.put(com.sitemonitor.service.checks.DistributedChecks.KEY_ENABLED, false);
        h.clock.addAndGet(5_000L);
        h.scheduler.dispatchCheckJobs();
        assertThat(h.drainer.switchNotes).containsExactly(false);
        assertThat(h.drainer.floorCalls.get()).isEqualTo(1);
    }
}
