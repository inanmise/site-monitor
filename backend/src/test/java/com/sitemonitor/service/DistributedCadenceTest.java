package com.sitemonitor.service;

import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.checks.CheckResultRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-3 paket B — KLASİK TUR KADANSI (B-MOS; tasarım R3-3 §1.3, risk R-8). Dağıtık kipte zamanlanmış sonuçlar
 * boşaltıcı dalgalarıyla (~2 sn) gelir; değerlendirici her çağrıdan önce türün klasik {@code @Scheduled} tıkını MOS'a bildirir ve
 * ölçülen tur aralığı {@code max(boşluk, tık)} olur: pencere ve kaydedilen tur aralığı klasik taramanınkiyle AYNI (DNS 300 sn tık →
 * 900 sn pencere). İpucu yoksa (anahtar kapalı) aritmetik birebir eski; ipucu ölçülmüş bir boşluğu asla kısaltmaz.
 */
class DistributedCadenceTest {

    private static long interval(MonitoringOutageService mos, String alertType) {
        Long iv = mos.typeValues(alertType).roundIntervalMs();
        return iv == null ? 0L : iv;
    }

    private static List<CheckResultRow> wave(R33SweepHarness h, String type, List<Long> ids) {
        List<CheckResultRow> out = new ArrayList<>();
        for (Long id : ids) out.add(h.workerRow(type, id));
        return out;
    }

    // ── uçtan uca: klasik çalıştırma = dağıtık çalıştırma ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DNS: klasik (300 sn tık) ve dağıtık (2 sn arayla dalgalar) aynı pencere 900 sn ve aynı tur aralığı 300 sn")
    void dnsWindowEqualsClassic() throws Exception {
        R33SweepHarness classic = new R33SweepHarness(true);
        classic.dnsFixture();
        ReflectionTestUtils.invokeMethod(classic.scheduler, "runDnsChecksLocked");
        classic.clock.addAndGet(300_000L);
        ReflectionTestUtils.invokeMethod(classic.scheduler, "runDnsChecksLocked");

        R33SweepHarness dist = new R33SweepHarness(true);
        List<DnsMonitor> all = dist.dnsFixture();
        List<Long> ids = all.stream().map(DnsMonitor::getId).toList();
        dist.scheduler.applyCheckResults(wave(dist, CheckJobTypes.DNS, ids.subList(0, 4)));
        for (int i = 0; i < 3; i++) {
            dist.clock.addAndGet(2_000L);
            dist.scheduler.applyCheckResults(wave(dist, CheckJobTypes.DNS, ids.subList(4, ids.size())));
        }

        for (String t : List.of(EscalationService.TYPE_DNS_FAILURE, EscalationService.TYPE_DNS_SLOW,
                EscalationService.TYPE_DNS_UNEXPECTED)) {
            assertThat(classic.outage.windowLengthMs(t)).as(t + " klasik pencere").isEqualTo(900_000L);
            assertThat(dist.outage.windowLengthMs(t)).as(t + " dağıtık pencere = klasik").isEqualTo(900_000L);
            assertThat(interval(dist.outage, t)).as(t + " dağıtık tur aralığı = klasik").isEqualTo(interval(classic.outage, t))
                    .isEqualTo(300_000L);
        }
    }

    @Test
    @DisplayName("port: klasik (30 sn tık, her tıkta izleme) ve dağıtık (dalgalar) aynı tur aralığı 30 sn ve aynı pencere (taban 300 sn)")
    void portWindowEqualsClassic() throws Exception {
        R33SweepHarness classic = new R33SweepHarness(true);
        for (PortMonitor m : classic.portFixture()) m.setIntervalSeconds(30);
        for (int i = 0; i < 3; i++) {
            ReflectionTestUtils.invokeMethod(classic.scheduler, "runPortChecksLocked");
            classic.clock.addAndGet(30_000L);
        }
        R33SweepHarness dist = new R33SweepHarness(true);
        List<PortMonitor> all = dist.portFixture();
        List<Long> ids = all.stream().map(PortMonitor::getId).toList();
        for (int i = 0; i < 6; i++) {
            dist.scheduler.applyCheckResults(wave(dist, CheckJobTypes.PORT, ids.subList(i % 2, i % 2 + 3)));
            dist.clock.addAndGet(2_000L);
        }
        for (String t : List.of(EscalationService.TYPE_PORT_DOWN, EscalationService.TYPE_PORT_SLOW)) {
            assertThat(interval(classic.outage, t)).as(t + " klasik").isEqualTo(30_000L);
            assertThat(interval(dist.outage, t)).as(t + " dağıtık = klasik").isEqualTo(30_000L);
            assertThat(dist.outage.windowLengthMs(t)).isEqualTo(classic.outage.windowLengthMs(t)).isEqualTo(300_000L);
        }
    }

    @Test
    @DisplayName("tık ifadesi @Scheduled ile AYNI ifadeden çözülür: ortam değeri (ör. 600000) kullanılır; ortam yoksa ifadenin varsayılanı")
    void tickResolvedFromTheScheduledExpression() throws Exception {
        R33SweepHarness h = new R33SweepHarness();
        assertThat(h.scheduler.classicSweepTickMs(CheckJobTypes.DNS)).isEqualTo(300_000L);
        assertThat(h.scheduler.classicSweepTickMs(CheckJobTypes.PORT)).isEqualTo(30_000L);
        assertThat(h.scheduler.classicSweepTickMs(CheckJobTypes.PAGE)).isEqualTo(60_000L);
        assertThat(h.scheduler.classicSweepTickMs("nope")).isZero();
        for (String t : CheckJobTypes.ALL) {
            assertThat(SchedulerService.classicSweepTickExpression(t)).startsWith("${site.monitor." + t + ".interval-ms:");
            assertThat(SchedulerService.classicSweepAlertTypes(t)).isNotEmpty();
        }

        R33SweepHarness env = new R33SweepHarness();
        StandardEnvironment e = new StandardEnvironment();
        e.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("t",
                Map.of("site.monitor.dns.interval-ms", "600000", "site.monitor.port.interval-ms", "45s")));
        ReflectionTestUtils.setField(env.scheduler, "environment", e);
        assertThat(env.scheduler.classicSweepTickMs(CheckJobTypes.DNS)).isEqualTo(600_000L);
        assertThat(env.scheduler.classicSweepTickMs(CheckJobTypes.PORT)).isEqualTo(45_000L);
        assertThat(env.scheduler.resolveTickMs("${site.monitor.yok.interval-ms:abc}")).isZero();
    }

    @Test
    @DisplayName("klasik kip / anahtar kapalı: MOS'a hiç ipucu verilmez (klasik gövde noteRoundCadence çağırmaz)")
    void classicNeverHints() throws Exception {
        R33SweepHarness h = new R33SweepHarness();
        h.dnsFixture();
        h.portFixture();
        ReflectionTestUtils.invokeMethod(h.scheduler, "runDnsChecksLocked");
        ReflectionTestUtils.invokeMethod(h.scheduler, "runPortChecksLocked");
        org.mockito.Mockito.verify(h.outage, org.mockito.Mockito.never())
                .noteRoundCadence(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
        h.scheduler.applyCheckResults(List.of(h.workerRow(CheckJobTypes.DNS, 1L)));
        org.mockito.Mockito.verify(h.outage).noteRoundCadence(EscalationService.TYPE_DNS_FAILURE, 300_000L);
        org.mockito.Mockito.verify(h.outage).noteRoundCadence(EscalationService.TYPE_DNS_INCONSISTENT, 300_000L);
    }

    // ── MOS birimi ───────────────────────────────────────────────────────────────────────────────────────────────────

    private static List<MonitoringOutageService.SweepItem> portItems(int n) {
        List<MonitoringOutageService.SweepItem> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new MonitoringOutageService.SweepItem(EscalationService.TYPE_PORT_DOWN, "h" + i + ".example.com",
                    "443/TCP", true, null, Map.of(), null));
        }
        return out;
    }

    @Test
    @DisplayName("ipucu YOK: aritmetik birebir eski (2 sn boşluk → 2 sn aralık, pencere tabanı)")
    void noHintIsUnchanged() throws Exception {
        R33SweepHarness h = new R33SweepHarness(true);
        MonitoringOutageService mos = h.outage;
        mos.handleSweepResults(EscalationService.TYPE_PORT_DOWN, portItems(3));
        h.clock.addAndGet(2_000L);
        mos.handleSweepResults(EscalationService.TYPE_PORT_DOWN, portItems(3));
        assertThat(interval(mos, EscalationService.TYPE_PORT_DOWN)).isEqualTo(2_000L);
        assertThat(mos.windowLengthMs(EscalationService.TYPE_PORT_DOWN)).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("ipucu ölçülmüş boşluğu ASLA kısaltmaz; kısa boşluğu tıka yükseltir; ilk çağrıda (ölçüm yok) uygulanmaz; geçersiz ipucu yok sayılır")
    void hintOnlyRaises() throws Exception {
        R33SweepHarness h = new R33SweepHarness(true);
        MonitoringOutageService mos = h.outage;
        mos.noteRoundCadence(EscalationService.TYPE_PORT_DOWN, 300_000L);
        mos.noteRoundCadence(null, 5L);
        mos.noteRoundCadence(EscalationService.TYPE_PORT_SLOW, 0L);
        mos.noteRoundCadence(EscalationService.TYPE_PORT_SLOW, -1L);
        mos.handleSweepResults(EscalationService.TYPE_PORT_DOWN, portItems(3));
        assertThat(interval(mos, EscalationService.TYPE_PORT_DOWN)).as("ilk çağrı: ölçüm yok").isZero();
        h.clock.addAndGet(2_000L);
        mos.handleSweepResults(EscalationService.TYPE_PORT_DOWN, portItems(3));
        assertThat(interval(mos, EscalationService.TYPE_PORT_DOWN)).isEqualTo(300_000L);
        assertThat(mos.windowLengthMs(EscalationService.TYPE_PORT_DOWN)).isEqualTo(900_000L);
        h.clock.addAndGet(700_000L);
        mos.handleSweepResults(EscalationService.TYPE_PORT_DOWN, portItems(3));
        assertThat(interval(mos, EscalationService.TYPE_PORT_DOWN)).as("uzun boşluk kısaltılmaz").isEqualTo(700_000L);
        // PORT_SLOW için geçersiz ipuçları yok sayıldı → eski aritmetik
        List<MonitoringOutageService.SweepItem> slow = List.of(new MonitoringOutageService.SweepItem(
                EscalationService.TYPE_PORT_SLOW, "h.example.com", "10 ms", true, null, Map.of(), null));
        mos.handleSweepResults(EscalationService.TYPE_PORT_SLOW, slow);
        h.clock.addAndGet(2_000L);
        mos.handleSweepResults(EscalationService.TYPE_PORT_SLOW, slow);
        assertThat(interval(mos, EscalationService.TYPE_PORT_SLOW)).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("liderlik değişimi (resetVolatileState) ipucunu SİLMEZ (yapılandırma); tur ölçümü sıfırlanır, sonraki boşluk yine tıka yükselir")
    void resetKeepsHints() throws Exception {
        R33SweepHarness h = new R33SweepHarness(true);
        MonitoringOutageService mos = h.outage;
        mos.noteRoundCadence(EscalationService.TYPE_PORT_DOWN, 300_000L);
        mos.handleSweepResults(EscalationService.TYPE_PORT_DOWN, portItems(3));
        h.clock.addAndGet(2_000L);
        mos.handleSweepResults(EscalationService.TYPE_PORT_DOWN, portItems(3));
        mos.resetVolatileState("test");
        assertThat(interval(mos, EscalationService.TYPE_PORT_DOWN)).isZero();
        mos.handleSweepResults(EscalationService.TYPE_PORT_DOWN, portItems(3));
        h.clock.addAndGet(2_000L);
        mos.handleSweepResults(EscalationService.TYPE_PORT_DOWN, portItems(3));
        assertThat(interval(mos, EscalationService.TYPE_PORT_DOWN)).isEqualTo(300_000L);
    }
}
