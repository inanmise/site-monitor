package com.sitemonitor.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.checks.CheckWorkerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Çok pod Sürüm 3-3 paket B — TÜR KİPİ (tasarım R3-3 §5.3, §9.2 B): {@code distributedMode(T)} = ayar ∧ tür listede ∧ tarama lideri
 * ∧ BAŞKA ≥ 1 canlı yetenekli işçi; histerezis (ilk sighting → dağıtık, 60 sn sighting yok → klasik), işçi kaydı okunamazsa HEMEN
 * klasik, tek pod (başka işçi yok) → klasik; geçişler bir kez INFO. Dağıtık kipte klasik giriş yalnız lider görevlerini yapar.
 */
class DistributedModeTest {

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
        logger = (Logger) LoggerFactory.getLogger(SchedulerService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    private boolean mode(String type) {
        return (boolean) ReflectionTestUtils.invokeMethod(h.scheduler, "distributedMode", type);
    }

    private List<String> modeLogs() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("Dağıtık kontroller: ")).toList();
    }

    @Test
    @DisplayName("ilk sighting'de dağıtık (bir INFO), sonraki çağrılar günlük yazmaz")
    void firstSightingSwitchesOnce() {
        h.bindDistributed(true, null);
        registry.live.put(CheckJobTypes.PORT, 2);
        assertThat(mode(CheckJobTypes.PORT)).isTrue();
        assertThat(mode(CheckJobTypes.PORT)).isTrue();
        assertThat(modeLogs()).containsExactly("Dağıtık kontroller: port artık dağıtık (canlı işçi: 2)");
    }

    @Test
    @DisplayName("histerezis: işçi kaybolunca 60 sn boyunca dağıtık kalır, sonra klasiğe döner (bir INFO)")
    void hysteresis() {
        h.bindDistributed(true, null);
        registry.live.put(CheckJobTypes.HTTP, 1);
        assertThat(mode(CheckJobTypes.HTTP)).isTrue();
        registry.live.put(CheckJobTypes.HTTP, 0);
        h.clock.addAndGet(59_000L);
        assertThat(mode(CheckJobTypes.HTTP)).isTrue();
        h.clock.addAndGet(1_000L);
        assertThat(mode(CheckJobTypes.HTTP)).isFalse();
        assertThat(mode(CheckJobTypes.HTTP)).isFalse();
        assertThat(modeLogs()).containsExactly("Dağıtık kontroller: http artık dağıtık (canlı işçi: 1)",
                "Dağıtık kontroller: http artık klasik (canlı işçi: 0)");
        registry.live.put(CheckJobTypes.HTTP, 1);
        assertThat(mode(CheckJobTypes.HTTP)).isTrue();   // yeniden sighting → hemen dağıtık
    }

    @Test
    @DisplayName("işçi kaydı okunamadı (UNKNOWN) ya da fırlattı → HEMEN klasik (asla 'bilinmeyen işçilerle dağıtık')")
    void unknownIsClassicAtOnce() {
        h.bindDistributed(true, null);
        registry.live.put(CheckJobTypes.PING, 1);
        assertThat(mode(CheckJobTypes.PING)).isTrue();
        registry.live.put(CheckJobTypes.PING, CheckWorkerRegistry.UNKNOWN);
        assertThat(mode(CheckJobTypes.PING)).isFalse();
        assertThat(modeLogs()).last().isEqualTo("Dağıtık kontroller: ping artık klasik (canlı işçi: bilinmiyor)");
        CheckWorkerRegistry throwing = new R33SweepHarness.FakeRegistry() {
            @Override
            public int liveOtherWorkers(String type) {
                throw new IllegalStateException("tablo yok");
            }
        };
        h.scheduler.setCheckJobInfrastructure(store, throwing);
        assertThat(mode(CheckJobTypes.PING)).isFalse();
        h.scheduler.setCheckJobInfrastructure(store, null);
        assertThat(mode(CheckJobTypes.PING)).isFalse();
    }

    @Test
    @DisplayName("tek pod / başka işçi yok → anahtar açıkken de klasik; durum kaydı açılmaz, günlük yok")
    void aloneIsClassicSilently() {
        h.bindDistributed(true, null);
        for (String t : CheckJobTypes.ALL) assertThat(mode(t)).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, ?> states = (Map<String, ?>) ReflectionTestUtils.getField(h.scheduler, "checkModes");
        assertThat(states).isEmpty();
        assertThat(modeLogs()).isEmpty();
    }

    @Test
    @DisplayName("ayar: anahtar kapalı / tür listede yok → klasik; dağıtıkken anahtar kapanırsa bir INFO ile klasik")
    void settingDecides() {
        h.bindDistributed(true, "port");
        registry.live.put(CheckJobTypes.PORT, 1);
        registry.live.put(CheckJobTypes.DNS, 1);
        assertThat(mode(CheckJobTypes.DNS)).isFalse();
        assertThat(mode(CheckJobTypes.PORT)).isTrue();
        long reads = registry.reads.get();
        h.settings.put(com.sitemonitor.service.checks.DistributedChecks.KEY_ENABLED, false);
        assertThat(mode(CheckJobTypes.PORT)).isFalse();
        assertThat(mode(CheckJobTypes.PORT)).isFalse();
        assertThat(registry.reads.get()).as("anahtar kapalıyken işçi kaydı sorulmaz").isEqualTo(reads);
        assertThat(modeLogs()).containsExactly("Dağıtık kontroller: port artık dağıtık (canlı işçi: 1)",
                "Dağıtık kontroller: port artık klasik (ayar kapalı ya da bu pod lider değil)");
    }

    @Test
    @DisplayName("lider değil → klasik (işçi kaydı sorulmaz)")
    void notLeaderIsClassic() {
        h.bindDistributed(true, null);
        registry.live.put(CheckJobTypes.PORT, 1);
        ReflectionTestUtils.setField(h.scheduler, "clusterSchedule",
                org.mockito.Mockito.mock(com.sitemonitor.service.schedule.ClusterScheduleService.class));
        ReflectionTestUtils.setField(h.scheduler, "sweepLeaderStopped", true);
        assertThat(mode(CheckJobTypes.PORT)).isFalse();
        assertThat(registry.reads.get()).isZero();
    }

    @Test
    @DisplayName("liderlik İPUCU (bellek) false → klasik; kira hiç sorulmaz (kapanan / kirayı bırakan pod asla göndermez)")
    void leaderHintFalseIsClassicWithoutLeaseRead() {
        h.bindDistributed(true, null);
        registry.live.put(CheckJobTypes.PORT, 1);
        var cs = org.mockito.Mockito.mock(com.sitemonitor.service.schedule.ClusterScheduleService.class);
        ReflectionTestUtils.setField(h.scheduler, "clusterSchedule", cs);
        ReflectionTestUtils.setField(h.scheduler, "sweepLeaderHeld", false);
        assertThat(h.scheduler.sweepLeaderHint()).isFalse();
        assertThat(mode(CheckJobTypes.PORT)).isFalse();
        h.scheduler.dispatchCheckJobs();
        org.mockito.Mockito.verifyNoInteractions(cs);
        assertThat(registry.reads.get()).isZero();
        assertThat(store.dispatched).isEmpty();
    }

    // ── dağıtık kipte klasik giriş = yalnız lider görevleri ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("port dağıtıkken klasik gövde KONTROL YAPMAZ; öksüz temizliği + envanter dışı türev sessiz kapanışı yapar (5 dk'da bir)")
    void portDutiesOnly() {
        h.bindDistributed(true, null);
        registry.live.put(CheckJobTypes.PORT, 1);
        List<PortMonitor> all = h.portFixture();
        ReflectionTestUtils.invokeMethod(h.scheduler, "runPortChecksLocked");
        verify(h.portChecker, never()).check(any(PortMonitor.class));
        verify(h.escalation).resolveOrphanedPortAlerts(any());
        verify(h.escalation).resolveOpenAlertsSilently(eq("gone-host.example.com"),
                eq(Set.of(EscalationService.TYPE_PORT_DOWN, EscalationService.TYPE_PORT_SLOW)), anyString());
        verify(h.outage, never()).handleSweepResults(anyString(), any());
        ReflectionTestUtils.invokeMethod(h.scheduler, "runPortChecksLocked");   // 5 dk dolmadı → temizlik yok
        verify(h.escalation, times(1)).resolveOrphanedPortAlerts(any());
        assertThat(all).isNotEmpty();
    }

    @Test
    @DisplayName("dns dağıtıkken klasik gövde sorgu YAPMAZ; bekleyen değişiklik yeniden kuyruğu + envanter dışı sessiz kapanış yapar")
    void dnsDutiesOnly() {
        h.bindDistributed(true, null);
        registry.live.put(CheckJobTypes.DNS, 1);
        List<DnsMonitor> all = h.dnsFixture();
        ReflectionTestUtils.invokeMethod(h.scheduler, "runDnsChecksLocked");
        verify(h.dnsChecker, never()).check(anyString(), anyString());
        verify(h.jdbc, org.mockito.Mockito.atLeastOnce()).queryForList(org.mockito.ArgumentMatchers.contains("AND checked_at >= ?"),
                org.mockito.ArgumentMatchers.<Object>any());   // requeuePendingDnsChanges
        verify(h.escalation).resolveOpenAlertsSilently(eq("gone.example.com"), any(), anyString());
        verify(h.outage, never()).handleDnsSweep(any(), any(), any(), any(), any());
        assertThat(all).isNotEmpty();
    }

    @Test
    @DisplayName("diğer türler dağıtıkken klasik gövde yalnız öksüz temizliği yapar (keyword / http / ping / sayfa / sayfa hızı / k6)")
    void otherDutiesOnly() {
        h.bindDistributed(true, null);
        for (String t : CheckJobTypes.ALL) registry.live.put(t, 1);
        List<KeywordMonitor> kw = h.keywordFixture();
        h.httpFixture();
        h.pingFixture();
        h.pageFixture();
        h.pageSpeedFixture();
        h.scriptedFixture();
        for (String body : List.of("runKeywordChecksLocked", "runHttpChecksLocked", "runPingChecksLocked",
                "runPageChecksLocked", "runPageSpeedChecksLocked", "runScriptedChecksLocked")) {
            ReflectionTestUtils.invokeMethod(h.scheduler, body);
        }
        verify(h.escalation).resolveOrphanedKeywordAlerts(any());
        verify(h.escalation).resolveOrphanedHttpAlerts(any());
        verify(h.escalation).resolveOrphanedPingAlerts(any());
        verify(h.escalation).resolveOrphanedPageAlerts(any());
        verify(h.escalation).resolveOrphanedPageSpeedAlerts(any());
        verify(h.escalation).resolveOrphanedScriptedAlerts(any());
        verify(h.outage, never()).handleSweepResults(anyString(), any());
        verify(h.keywordChecker, never()).check(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt(), any(),
                org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(h.scriptedChecker, never()).submit(any());
        verify(h.pingChecker, never()).check(anyString(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
        assertThat(kw).isNotEmpty();
    }

    @Test
    @DisplayName("Sistem Sağlığı görünümü: tür kipleri + sayaçlar (yalnız bellek)")
    void statusView() {
        h.bindDistributed(true, null);
        registry.live.put(CheckJobTypes.PORT, 1);
        assertThat(mode(CheckJobTypes.PORT)).isTrue();
        Map<String, Object> s = h.scheduler.distributedCheckStatus();
        @SuppressWarnings("unchecked")
        Map<String, String> modes = (Map<String, String>) s.get("mode");
        assertThat(modes).containsEntry("port", "distributed").containsEntry("http", "classic").hasSize(8);
        assertThat(s).containsKeys("dispatched", "lost", "applied", "dropped_target", "dropped_inactive",
                "dropped_toggle", "dropped_inventory", "no_result", "dispatch_failures", "dispatch_active");
    }
}
