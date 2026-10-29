package com.sitemonitor.service;

import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.service.checks.CheckJobRow;
import com.sitemonitor.service.checks.CheckJobRunner;
import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.checks.CheckResultRow;
import com.sitemonitor.service.checks.DistributedChecks;
import com.sitemonitor.service.checks.SchedulerCheckJobs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-3 paket B — GÖNDERİM ve DEĞERLENDİRME KAPILARI (tasarım R3-3 §3.5 / §3.6 / §9.2 B): gönderici klasik taramanın
 * kapılarını AYNEN uygular (envanter türevi Port / DNS, tür anahtarları, k6 yoklaması, sayfa hızı aralık kırpması, {@code checkDue}
 * dönemi), anahtar kapalıyken ya da lider değilken HİÇ gönderim yok; değerlendirici pasif / hedefi değişmiş / tür anahtarı kapalı /
 * envanter dışı sonucu düşürür; tembel tık yalnız anahtar açık görüldüğünde kurulur.
 */
class CheckDispatchTest {

    R33SweepHarness h;
    R33SweepHarness.FakeStore store;
    R33SweepHarness.FakeRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        h = new R33SweepHarness();
        store = new R33SweepHarness.FakeStore();
        registry = new R33SweepHarness.FakeRegistry();
        h.scheduler.setCheckJobInfrastructure(store, registry);
        for (String t : CheckJobTypes.ALL) registry.live.put(t, 1);
        when(h.scriptedChecker.ensureProbed()).thenReturn(true);
    }

    private Set<Long> dispatchedIds(String type) {
        return new TreeSet<>(store.byType().getOrDefault(type, List.of()));
    }

    // ── K-1: anahtar kapalı ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("anahtar KAPALI: gönderim tıkı hiçbir şeye dokunmaz (kuyruk, işçi kaydı, depolar, JDBC, liderlik) — K-1")
    void offSwitchTouchesNothing() {
        h.bindDistributed(false, null);
        h.portFixture();
        h.dnsFixture();
        Mockito.clearInvocations(h.portRepo, h.dnsRepo, h.inventoryRepo);
        h.scheduler.dispatchCheckJobs();
        assertThat(store.calls).isZero();
        assertThat(registry.reads.get()).isZero();
        verify(h.portRepo, never()).findCheckDispatchRows();
        verify(h.dnsRepo, never()).findCheckDispatchRows();
        verifyNoInteractions(h.jdbc);
        verifyNoInteractions(h.inventoryRepo);
        @SuppressWarnings("unchecked")
        Map<String, Object> status = h.scheduler.distributedCheckStatus();
        assertThat(status.get("dispatch_active")).isEqualTo(false);
        assertThat(new java.util.ArrayList<Object>(((Map<?, ?>) status.get("mode")).values())).containsOnly("classic");
    }

    @Test
    @DisplayName("anahtar KAPALI: klasik gövde bugünkü gibi koşar (denetleyici çağrılır, kuyruk / kayıt hiç sorulmaz)")
    void offSwitchRunsClassic() {
        h.bindDistributed(false, null);
        h.portFixture();
        ReflectionTestUtils.invokeMethod(h.scheduler, "runPortChecksLocked");
        verify(h.portChecker, times(6)).check(any(PortMonitor.class));   // 7 izleme − 1 envanter dışı türev
        assertThat(store.calls).isZero();
        assertThat(registry.reads.get()).isZero();
    }

    @Test
    @DisplayName("bean yok (elle kurulum / testler): kip daima klasik, gönderim no-op")
    void noBeansIsClassic() throws Exception {
        R33SweepHarness bare = new R33SweepHarness();
        bare.scheduler.dispatchCheckJobs();
        assertThat((boolean) ReflectionTestUtils.invokeMethod(bare.scheduler, "distributedMode", CheckJobTypes.PORT)).isFalse();
    }

    // ── liderlik ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("lider DEĞİL: gönderim yok, işçi kaydı sorulmaz")
    void notLeaderNoDispatch() {
        h.bindDistributed(true, null);
        h.portFixture();
        ReflectionTestUtils.setField(h.scheduler, "clusterSchedule",
                mock(com.sitemonitor.service.schedule.ClusterScheduleService.class));
        ReflectionTestUtils.setField(h.scheduler, "sweepLeaderStopped", true);
        h.scheduler.dispatchCheckJobs();
        assertThat(store.dispatched).isEmpty();
        assertThat(registry.reads.get()).isZero();
    }

    @Test
    @DisplayName("kapanış başladı: gönderim yok")
    void shuttingDownNoDispatch() {
        h.bindDistributed(true, null);
        h.portFixture();
        ReflectionTestUtils.setField(h.scheduler, "shutdownStarted", true);
        h.scheduler.dispatchCheckJobs();
        assertThat(store.calls).isZero();
    }

    // ── kapılar = klasik ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("port: gönderilen izlemeler = klasik taramanın kontrol ettikleri (envanter dışı türev atlanır, bağımsız ve envanterdeki geçer)")
    void portGatingEqualsClassic() throws Exception {
        h.bindDistributed(true, null);
        h.portFixture();
        h.scheduler.dispatchCheckJobs();
        R33SweepHarness classic = new R33SweepHarness();
        List<PortMonitor> all = classic.portFixture();
        ReflectionTestUtils.invokeMethod(classic.scheduler, "runPortChecksLocked");
        Set<Long> checked = new TreeSet<>();
        for (var inv : Mockito.mockingDetails(classic.portChecker).getInvocations()) {
            checked.add(((PortMonitor) inv.getArgument(0)).getId());
        }
        assertThat(checked).hasSize(all.size() - 1).doesNotContain(5L);
        assertThat(dispatchedIds(CheckJobTypes.PORT)).isEqualTo(checked);
        verify(h.portChecker, never()).check(any(PortMonitor.class));   // gönderici kontrol YAPMAZ
    }

    @Test
    @DisplayName("dns: gönderilen izlemeler = klasik taramanın sorguladıkları (envanter dışı türev atlanır)")
    void dnsGatingEqualsClassic() throws Exception {
        h.bindDistributed(true, null);
        h.dnsFixture();
        h.scheduler.dispatchCheckJobs();
        R33SweepHarness classic = new R33SweepHarness();
        List<DnsMonitor> all = classic.dnsFixture();
        ReflectionTestUtils.invokeMethod(classic.scheduler, "runDnsChecksLocked");
        Set<Long> checked = new TreeSet<>();
        for (var inv : Mockito.mockingDetails(classic.dnsChecker).getInvocations()) {
            if (!"check".equals(inv.getMethod().getName())) continue;
            String domain = inv.getArgument(0);
            all.stream().filter(m -> m.getDomain().equals(domain)).forEach(m -> checked.add(m.getId()));
        }
        assertThat(checked).doesNotContain(4L).hasSize(all.size() - 1);
        assertThat(dispatchedIds(CheckJobTypes.DNS)).isEqualTo(checked);
    }

    @Test
    @DisplayName("her tür kendi checkDue anahtarı ve kirasıyla, gönderen = bu pod'un örnek kimliği")
    void leaseAndDispatcherPerType() {
        h.bindDistributed(true, null);
        h.portFixture();
        h.pingFixture();
        h.httpFixture();
        h.keywordFixture();
        h.pageFixture();
        h.pageSpeedFixture();
        h.dnsFixture();
        h.scriptedFixture();
        h.scheduler.dispatchCheckJobs();
        assertThat(store.byType().keySet()).containsExactlyElementsOf(CheckJobTypes.ALL);
        for (int i = 0; i < store.dispatched.size(); i++) {
            String type = store.dispatched.get(i).getKey();
            assertThat(store.leases.get(i)).isEqualTo(CheckJobTypes.leaseMs(type));
            assertThat(store.dispatchers.get(i)).isEqualTo(SchedulerService.instanceId());
        }
        @SuppressWarnings("unchecked")
        Map<String, Long> due = (Map<String, Long>) ReflectionTestUtils.getField(h.scheduler, "lastMonitorCheckAt");
        assertThat(due).containsKeys("port:1", "ping:1", "http:1", "keyword:1", "page:1", "pagespeed:1", "dns:1", "scripted:1");
    }

    @Test
    @DisplayName("aynı dönemde ikinci tık göndermez; aralık dolunca yeniden gönderir (klasik checkDue ızgarası)")
    void oncePerPeriod() {
        h.bindDistributed(true, CheckJobTypes.HTTP);
        List<HttpMonitor> all = h.httpFixture();
        h.scheduler.dispatchCheckJobs();
        assertThat(dispatchedIds(CheckJobTypes.HTTP)).hasSize(all.size());
        store.dispatched.clear();
        h.clock.addAndGet(30_000L);
        h.scheduler.dispatchCheckJobs();
        assertThat(store.dispatched).isEmpty();
        h.clock.addAndGet(31_000L);
        h.scheduler.dispatchCheckJobs();
        assertThat(dispatchedIds(CheckJobTypes.HTTP)).hasSize(all.size());
    }

    @Test
    @DisplayName("sayfa hızı: aralık klasikteki gibi türün alt sınırına kırpılır (60 sn ayarlı izleme alt sınırdan önce yeniden gönderilmez)")
    void pageSpeedClamp() {
        h.bindDistributed(true, CheckJobTypes.PAGESPEED);
        List<PageSpeedMonitor> all = h.pageSpeedFixture();
        for (PageSpeedMonitor m : all) m.setIntervalSeconds(60);
        List<Object[]> rows = new ArrayList<>();
        for (PageSpeedMonitor m : all) rows.add(new Object[]{m.getId(), 60});
        when(h.pageSpeedRepo.findCheckDispatchRows()).thenReturn(rows);
        h.scheduler.dispatchCheckJobs();
        assertThat(dispatchedIds(CheckJobTypes.PAGESPEED)).hasSize(all.size());
        store.dispatched.clear();
        h.clock.addAndGet(61_000L);
        h.scheduler.dispatchCheckJobs();
        assertThat(store.dispatched).as("60 sn ham aralık, kırpılmış taban dolmadan gönderilmemeli").isEmpty();
        int clamped = SchedulerService.pageSpeedDueInterval(60);
        assertThat(clamped).isGreaterThan(60);
        h.clock.addAndGet(clamped * 1000L);
        h.scheduler.dispatchCheckJobs();
        assertThat(dispatchedIds(CheckJobTypes.PAGESPEED)).hasSize(all.size());
    }

    @Test
    @DisplayName("tür anahtarı kapalı → o tür gönderilmez, diğerleri gönderilir; tür listede yoksa gönderilmez")
    void togglesAndTypeList() {
        h.bindDistributed(true, "port,http");
        h.portFixture();
        h.httpFixture();
        h.pingFixture();
        h.settings.put("site.monitor.port.alert-enabled", false);
        h.scheduler.dispatchCheckJobs();
        assertThat(store.byType().keySet()).containsExactly(CheckJobTypes.HTTP);
        verify(h.pingRepo, never()).findCheckDispatchRows();
        verify(h.portRepo, never()).findCheckDispatchRows();
    }

    @Test
    @DisplayName("k6: lider pod'da k6 yoksa senaryo işi gönderilmez (klasik giriş de koşmaz; yeniden kontroller liderde)")
    void scriptedNeedsK6() {
        h.bindDistributed(true, CheckJobTypes.SCRIPTED);
        h.scriptedFixture();
        when(h.scriptedChecker.ensureProbed()).thenReturn(false);
        h.scheduler.dispatchCheckJobs();
        assertThat(store.dispatched).isEmpty();
        when(h.scriptedChecker.ensureProbed()).thenReturn(true);
        h.scheduler.dispatchCheckJobs();
        assertThat(dispatchedIds(CheckJobTypes.SCRIPTED)).hasSize(7);
    }

    @Test
    @DisplayName("gönderim deyimi düşerse: sayaç + WARN, diğer türler sürer, istisna dışarı sızmaz")
    void dispatchFailureCounted() {
        h.bindDistributed(true, "port,http");
        h.portFixture();
        h.httpFixture();
        store.dispatchFailure = new DataAccessResourceFailureException("bağlantı yok");
        h.scheduler.dispatchCheckJobs();
        assertThat(h.scheduler.distributedCheckStatus().get("dispatch_failures")).isEqualTo(2L);
    }

    @Test
    @DisplayName("biçme: iki kez sahiplenilip tamamlanmamış işler 'lost' sayacına girer")
    void reaperCountsLost() {
        h.bindDistributed(true, CheckJobTypes.HTTP);
        h.httpFixture();
        store.toReap.add(new CheckJobRow(41L, CheckJobTypes.HTTP, 3L, 0L, 2, 150_000L, null, 0L, 0L));
        store.toReap.add(new CheckJobRow(42L, CheckJobTypes.HTTP, 4L, 0L, 2, 150_000L, null, 0L, 0L));
        h.scheduler.dispatchCheckJobs();
        assertThat(h.scheduler.distributedCheckStatus().get("lost")).isEqualTo(2L);
    }

    // ── iş gövdesi + hedef parmak izi ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("iş gövdesi: hedef parmak izi eklenir; izleme yok / pasif / tür anahtarı kapalı → inactive (kontrol YOK); istisna → failed (yalnız sınıf adı)")
    void jobBodyMarkers() {
        List<PortMonitor> all = h.portFixture();
        Map<String, Object> ok = h.scheduler.runCheckJob(CheckJobTypes.PORT, 1L);
        assertThat(ok.get(CheckJobRunner.KEY_TARGET)).isEqualTo("a.example.com|443|TCP");
        assertThat(ok.get("status")).isEqualTo("up");
        Map<String, Object> boom = h.scheduler.runCheckJob(CheckJobTypes.PORT, 7L);
        assertThat(boom).containsEntry(CheckJobRunner.KEY_FAILED, "IllegalStateException").hasSize(1);
        assertThat(h.scheduler.runCheckJob(CheckJobTypes.PORT, 999L)).isEqualTo(CheckJobRunner.inactive());
        all.get(1).setActive(false);
        Mockito.clearInvocations(h.portChecker);
        assertThat(h.scheduler.runCheckJob(CheckJobTypes.PORT, 2L)).isEqualTo(CheckJobRunner.inactive());
        h.settings.put("site.monitor.port.alert-enabled", false);
        assertThat(h.scheduler.runCheckJob(CheckJobTypes.PORT, 1L)).isEqualTo(CheckJobRunner.inactive());
        verify(h.portChecker, never()).check(any(PortMonitor.class));
        assertThat(h.scheduler.runCheckJob("nope", 1L)).containsKey(CheckJobRunner.KEY_FAILED);
    }

    @Test
    @DisplayName("hedef parmak izleri tür başına (port host|port|protokol, http url|yöntem, keyword url|sözcük, ping host|sürüm, dns alan|tür, sayfa url, k6 ad|sürüm)")
    void targetFingerprints() {
        h.portFixture();
        h.httpFixture();
        h.keywordFixture();
        h.pingFixture();
        h.dnsFixture();
        h.pageFixture();
        h.pageSpeedFixture();
        h.scriptedFixture();
        assertThat(h.scheduler.runCheckJob(CheckJobTypes.PORT, 6L).get("target")).isEqualTo("f.example.com|25|null");
        assertThat(h.scheduler.runCheckJob(CheckJobTypes.HTTP, 2L).get("target")).isEqualTo("https://b.example.com/health|HEAD");
        assertThat(h.scheduler.runCheckJob(CheckJobTypes.KEYWORD, 1L).get("target")).isEqualTo("https://a.example.com/|Hoş geldiniz");
        assertThat(h.scheduler.runCheckJob(CheckJobTypes.PING, 5L).get("target")).isEqualTo("10.0.0.5|v6");
        assertThat(h.scheduler.runCheckJob(CheckJobTypes.DNS, 3L).get("target")).isEqualTo("inv-host.example.com|MX");
        assertThat(h.scheduler.runCheckJob(CheckJobTypes.PAGE, 1L).get("target")).isEqualTo("https://a.example.com/");
        assertThat(h.scheduler.runCheckJob(CheckJobTypes.PAGESPEED, 1L).get("target")).isEqualTo("https://a.example.com/");
        assertThat(h.scheduler.runCheckJob(CheckJobTypes.SCRIPTED, 1L).get("target")).isEqualTo("Giriş akışı|v1");
    }

    @Test
    @DisplayName("değerlendirici: hedef değişti / izleme pasifleşti / tür anahtarı kapandı / türev hedef envanterden çıktı / sonuç yok → düşer, MOS'a kalem gitmez")
    void applierDrops() {
        List<HttpMonitor> http = h.httpFixture();
        List<PortMonitor> ports = h.portFixture();
        CheckResultRow renamed = h.workerRow(CheckJobTypes.HTTP, 1L);
        CheckResultRow paused = h.workerRow(CheckJobTypes.HTTP, 2L);
        CheckResultRow derived = h.workerRow(CheckJobTypes.PORT, 4L);   // inv-host envanterde → klasikte kontrol edilir
        http.get(0).setUrl("https://a.example.com/v2/health");
        http.get(1).setActive(false);
        when(h.inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());   // türev hedef envanterden çıktı
        CheckResultRow failed = h.row(CheckJobTypes.HTTP, 3L, CheckJobRunner.failed(new IllegalStateException()));
        CheckResultRow timeout = h.row(CheckJobTypes.HTTP, 4L, CheckJobRunner.workerTimeout());
        h.clearMos();
        h.scheduler.applyCheckResults(List.of(renamed, paused, derived, failed, timeout));
        assertThat(h.mosCalls()).isEmpty();
        Map<String, Object> s = h.scheduler.distributedCheckStatus();
        assertThat(s.get("dropped_target")).isEqualTo(1L);
        assertThat(s.get("dropped_inactive")).isEqualTo(1L);
        assertThat(s.get("dropped_inventory")).isEqualTo(1L);
        assertThat(s.get("no_result")).isEqualTo(2L);
        assertThat(ports).isNotEmpty();

        CheckResultRow good = h.workerRow(CheckJobTypes.HTTP, 5L);
        h.settings.put("site.monitor.http.alert-enabled", false);
        h.scheduler.applyCheckResults(List.of(good));
        assertThat(h.mosCalls()).isEmpty();
        assertThat(h.scheduler.distributedCheckStatus().get("dropped_toggle")).isEqualTo(1L);
        h.settings.put("site.monitor.http.alert-enabled", true);
        h.scheduler.applyCheckResults(List.of(good));
        assertThat(h.mosCalls()).hasSize(1).first().asString().startsWith("HTTP_DOWN");
        assertThat(h.scheduler.distributedCheckStatus().get("applied")).isEqualTo(1L);
    }

    @Test
    @DisplayName("değerlendirici bir türde düşerse diğer türler yine değerlendirilir, istisna dışarı sızmaz")
    void applierIsolatesTypes() {
        h.httpFixture();
        h.pingFixture();
        CheckResultRow http = h.workerRow(CheckJobTypes.HTTP, 1L);
        CheckResultRow ping = h.workerRow(CheckJobTypes.PING, 1L);
        Mockito.doThrow(new IllegalStateException("db")).when(h.httpRepo).findAllById(org.mockito.ArgumentMatchers.anyIterable());
        h.clearMos();
        h.scheduler.applyCheckResults(List.of(http, ping));
        assertThat(h.mosCalls()).extracting(c -> c.substring(0, c.indexOf(' '))).containsExactly("PING_DOWN", "PING_SLOW");
        h.scheduler.applyCheckResults(null);
        h.scheduler.applyCheckResults(List.of());
    }

    // ── köprü bileşeni ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SchedulerCheckJobs: CheckJobRunner / CheckResultApplier çağrıyı SchedulerService'e iletir; servis yoksa failed / no-op")
    void bridgeComponent() {
        h.httpFixture();
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        bf.addBean("scheduler", h.scheduler);
        SchedulerCheckJobs jobs = new SchedulerCheckJobs(bf.getBeanProvider(SchedulerService.class));
        assertThat(jobs.runCheckJob(CheckJobTypes.HTTP, 1L).get("target")).isEqualTo("https://a.example.com/health|GET");
        h.clearMos();
        jobs.apply(List.of(h.workerRow(CheckJobTypes.HTTP, 1L)));
        assertThat(h.mosCalls()).isNotEmpty();
        SchedulerCheckJobs none = new SchedulerCheckJobs(new StaticListableBeanFactory().getBeanProvider(SchedulerService.class));
        assertThat(none.runCheckJob(CheckJobTypes.HTTP, 1L)).containsKey(CheckJobRunner.KEY_FAILED);
        none.apply(List.of(h.workerRow(CheckJobTypes.HTTP, 1L)));
        none.apply(null);
    }

    // ── tembel kurulum ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("gönderim tıkı yalnız anahtar açık görüldüğünde BİR KEZ kurulur (kapalıyken görev yok — K-1)")
    void lazyTickRegistration() {
        TaskScheduler ts = mock(TaskScheduler.class);
        Mockito.doReturn(mock(java.util.concurrent.ScheduledFuture.class)).when(ts)
                .scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        StaticListableBeanFactory bf = new StaticListableBeanFactory();
        bf.addBean("taskScheduler", ts);
        ReflectionTestUtils.setField(h.scheduler, "taskSchedulerProvider", bf.getBeanProvider(TaskScheduler.class));
        DistributedChecks dc = h.bindDistributed(false, null);
        dc.onReady(null);
        dc.onSettingsChanged(new AppSettingsChangedEvent(Set.of("site.monitor.unrelated"), "save"));
        verify(ts, never()).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
        assertThat(h.scheduler.distributedCheckStatus().get("dispatch_active")).isEqualTo(false);

        h.settings.put(DistributedChecks.KEY_ENABLED, true);
        dc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(DistributedChecks.KEY_ENABLED), "save"));
        verify(ts).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class),
                org.mockito.ArgumentMatchers.eq(Duration.ofMillis(DistributedChecks.Tuning.DEFAULTS.dispatchMs())));
        dc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(DistributedChecks.KEY_ENABLED), "save"));
        ReflectionTestUtils.invokeMethod(h.scheduler, "startCheckDispatchTick");
        verify(ts, times(1)).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), any(Duration.class));
    }

    @Test
    @DisplayName("zamanlayıcı yoksa gönderim tıkı kendi cin iş parçacığında (\"check-dispatch\") kurulur")
    void fallbackDispatchThread() throws Exception {
        DistributedChecks dc = h.bindDistributed(true, CheckJobTypes.HTTP);
        h.httpFixture();
        dc.onReady(null);
        assertThat(h.scheduler.distributedCheckStatus().get("dispatch_active")).isEqualTo(true);
        Object task = ReflectionTestUtils.getField(h.scheduler, "checkDispatchTask");
        assertThat(task).isNotNull();
        ((java.util.concurrent.ScheduledFuture<?>) task).cancel(true);
    }
}
