package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.service.monitoring.ManualEvalInbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod R1-B (2026-10-21, MP-14 / SWEEPS-4) — takipçi pod'a düşen "Şimdi kontrol et" sonucu LİDERDE değerlendirilir.
 *
 * <p>Alarm durumu (kurtarma sayacı, kardeş DOWN gözlemi) yalnız liderin belleğinde. Takipçi kendi boş belleğinde
 * değerlendirince kardeş kuralı atlanıyor (alarm kapanıp yeniden açılıyor), liderin DOWN'u takipçinin sayacını
 * sıfırlamıyordu. Takipçi ham sonucu {@code monitor_eval_inbox}'a yazar; lider BUGÜNKÜ elle kontrol kuralıyla
 * (yalnız kapanış uzlaştırması, alarm AÇMAZ) değerlendirir. Gerçek iki pod (H2, ortak saat).
 */
class ManualEvalForwardingTest {

    static final String URL = "https://a.example.com";
    static final String TYPE = EscalationService.TYPE_HTTP_DOWN;

    MultiPodTestSupport mp;
    AlertEventRepository alerts;
    EscalationService escalation;
    HttpMonitor x;
    MonitoringMultiPodKit.PodWithMos a;
    MonitoringMultiPodKit.PodWithMos b;
    ManualEvalInbox inboxA;
    ManualEvalInbox inboxB;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        mp.jdbc().execute(ManualEvalInbox.DDL);
        alerts = mp.mock(AlertEventRepository.class);
        escalation = mp.mock(EscalationService.class);
        x = http(1L, 1);
        when(mp.mock(HttpMonitorRepository.class).findById(1L)).thenReturn(Optional.of(x));
        a = MonitoringMultiPodKit.podWithMos(mp, 1);
        b = MonitoringMultiPodKit.podWithMos(mp, 2);
        inboxA = inbox();
        inboxB = inbox();
        ReflectionTestUtils.setField(a.scheduler(), "manualEvalInbox", inboxA);
        ReflectionTestUtils.setField(b.scheduler(), "manualEvalInbox", inboxB);
        assertThat(a.scheduler().isSweepLeaderNow()).as("A lider").isTrue();
        assertThat(b.scheduler().isSweepLeaderNow()).as("B takipçi").isFalse();
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    ManualEvalInbox inbox() {
        ManualEvalInbox i = new ManualEvalInbox(mp.jdbc());
        i.setClock(mp.clockSupplier());
        return i;
    }

    static HttpMonitor http(long id, int recoveryChecks) {
        HttpMonitor m = new HttpMonitor();
        m.setId(id);
        m.setName("HTTP " + id);
        m.setUrl(URL);
        m.setTeamId(7L);
        m.setActive(true);
        m.setRecoveryChecks(recoveryChecks);
        m.setRecoveryIntervalSeconds(null);   // pasif kurtarma: karar sayaçta
        return m;
    }

    static Map<String, Object> raw(boolean ok) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", ok);
        r.put("http_status", ok ? 200 : 503);
        r.put("response_ms", 120L);
        if (!ok) r.put("error", "HTTP 503");
        return r;
    }

    /** X'in açtığı (bağlamında monitor_id=1) açık alarm. */
    void openAlarmByX() {
        AlertEvent e = new AlertEvent();
        e.setId(77L);
        e.setDomain(URL);
        e.setAlertType(TYPE);
        e.setAlertLevel("WARNING");
        e.setTeamId(7L);
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setContextJson("{\"monitor_id\":1,\"team_id\":7}");
        when(alerts.findOpenByDomainIn(anyCollection())).thenReturn(List.of(e));
    }

    /** Liderin zamanlanmış turu (zamanlayıcının kurduğu kalem biçiminde). */
    static MonitoringOutageService.SweepItem scheduled(long monitorId, boolean up) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_id", monitorId);
        ctx.put("team_id", 7L);
        ctx.put("monitor_recovery_checks", 2);
        return new MonitoringOutageService.SweepItem(TYPE, URL, "GET", up, up ? null : "HTTP 503", ctx,
                () -> Map.of("status", up ? "up" : "down"));
    }

    int inboxRows() {
        Integer n = mp.jdbc().queryForObject("SELECT count(*) FROM monitor_eval_inbox", Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("Kardeş kuralı korunur: lider Y'yi DOWN gördü; takipçideki X 'Şimdi kontrol et' UP → lidere iletilir → alarm AÇIK kalır")
    void followerManualUp_siblingDownOnLeader_alarmStaysOpen() {
        openAlarmByX();
        a.mos().handleSweepResults(TYPE, List.of(scheduled(2L, false)));   // lider: kardeş Y DOWN
        clearInvocations(escalation);

        b.scheduler().evaluateHttpNow(x, raw(true));                     // istek takipçiye düştü
        assertThat(inboxRows()).as("takipçi değerlendirmedi, lidere iletti").isEqualTo(1);
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        a.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
    }

    @Test
    @DisplayName("Kardeş DOWN yokken iletilen UP liderde alarmı KAPATIR (elle kontrol 'kapatır' davranışı çok pod'da da sürer)")
    void forwardedUp_noSiblingDown_closes() {
        openAlarmByX();
        b.scheduler().evaluateHttpNow(x, raw(true));
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
        a.scheduler().drainManualEvalInbox();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);
        assertThat(inboxRows()).isZero();

        a.scheduler().drainManualEvalInbox();                            // satır bir kez işlendi
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);
    }

    @Test
    @DisplayName("Kurtarma sayacı: iki iletilen UP arasında liderin DOWN'u sayacı sıfırlar → ikinci UP kapatmaz")
    void leaderDownBetweenForwardedUps_resetsCounter() {
        HttpMonitor x2 = http(1L, 2);
        when(mp.mock(HttpMonitorRepository.class).findById(1L)).thenReturn(Optional.of(x2));
        openAlarmByX();

        b.scheduler().evaluateHttpNow(x2, raw(true));
        a.scheduler().drainManualEvalInbox();                            // 1/2
        a.mos().handleSweepResults(TYPE, List.of(scheduled(1L, false)));   // lider DOWN → sayaç sıfır
        mp.advance(60_000);
        b.scheduler().evaluateHttpNow(x2, raw(true));
        a.scheduler().drainManualEvalInbox();                            // yine 1/2 (eskiden takipçide 2/2 → kapanırdı)
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        mp.advance(60_000);
        b.scheduler().evaluateHttpNow(x2, raw(true));
        a.scheduler().drainManualEvalInbox();                            // 2/2
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);
    }

    @Test
    @DisplayName("İletilen DOWN liderde de ALARM AÇMAZ (ManualCheckNoAlarm kuralı iletilen yolda da geçerli)")
    void forwardedDown_neverOpens() {
        when(alerts.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        b.scheduler().evaluateHttpNow(x, raw(false));
        a.scheduler().drainManualEvalInbox();
        verify(escalation, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
        assertThat(a.mos().activeConfirmations(null)).isEmpty();
    }

    @Test
    @DisplayName("10 dakikadan eski iletilmiş sonuç değerlendirilmeden silinir")
    void staleRowDropped() {
        openAlarmByX();
        b.scheduler().evaluateHttpNow(x, raw(true));
        mp.advance(ManualEvalInbox.STALE_MS + 60_000);
        assertThat(a.scheduler().isSweepLeaderNow()).isTrue();
        a.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
    }

    @Test
    @DisplayName("Lider kendi aldığı isteği YEREL değerlendirir (gelen kutusu kullanılmaz)")
    void leaderEvaluatesLocally() {
        openAlarmByX();
        a.scheduler().evaluateHttpNow(x, raw(true));
        assertThat(inboxRows()).isZero();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);
    }

    @Test
    @DisplayName("Takipçinin boşaltması hiçbir şey yapmaz; iki pod aynı anda boşaltsa da satır TAM BİR KEZ işlenir")
    void racingDrains_onlyLeaderProcesses_once() {
        openAlarmByX();
        for (int i = 0; i < 5; i++) b.scheduler().evaluateHttpNow(x, raw(true));
        assertThat(inboxRows()).isEqualTo(5);
        mp.race(() -> { b.scheduler().drainManualEvalInbox(); return 0; },
                () -> { a.scheduler().drainManualEvalInbox(); return 0; });
        assertThat(inboxRows()).isZero();
        // İlk UP alarmı kapatır; kalan dört satır aynı alarmı yeniden "kapatmaya" çalışır ama kapatma idempotenttir —
        // önemli olan hiçbir satırın İKİ kez değerlendirilmemesi: 5 satır = en çok 5 değerlendirme.
        verify(escalation, org.mockito.Mockito.atMost(5)).resolveMonitoringAlertsForDomain(URL, TYPE);
        verify(escalation, org.mockito.Mockito.atLeastOnce()).resolveMonitoringAlertsForDomain(URL, TYPE);
    }

    @Test
    @DisplayName("Liderlik boşaltma sırasında el değiştirirse sahiplenilen satır yeni lidere kalır (kira dolunca); kaybolmaz, iki kez işlenmez")
    void claimedRowSurvivesHandover() {
        openAlarmByX();
        b.scheduler().evaluateHttpNow(x, raw(true));
        List<ManualEvalInbox.Row> claimed = inboxA.claim("pod1-dead", 25);   // A sahiplendi ve öldü
        assertThat(claimed).hasSize(1);
        assertThat(inboxB.claim(MultiPodTestSupport.owner(2), 25)).as("kira sürerken başkası alamaz").isEmpty();

        mp.advance(ManualEvalInbox.LEASE_MS + 1_000);
        a.scheduler().drainManualEvalInbox();                            // canlı lider kirası dolmuş satırı alır
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);
        assertThat(inboxA.take(claimed.get(0))).as("ölü sahibin geç gelen işi işlenemez").isFalse();
    }
}
