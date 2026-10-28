package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.service.evalstate.EvalStateReadSwitch;
import com.sitemonitor.service.evalstate.EvalStateSink;
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
        a.scheduler().drainManualEvalInbox();   // lider boşaltıcı kirasını yazar (her 5 sn)
    }

    /** Saat ilerler; lider bu arada boşaltma turlarını koşmuştur (kira taze). */
    void tick(long ms) {
        mp.advance(ms);
        a.scheduler().drainManualEvalInbox();
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
        tick(60_000);
        b.scheduler().evaluateHttpNow(x2, raw(true));
        a.scheduler().drainManualEvalInbox();                            // yine 1/2 (eskiden takipçide 2/2 → kapanırdı)
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        tick(60_000);
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

    // ── İnceleme 2026-10-22 (CORE-3 / F9 / R-4): yuvarlanan dağıtımda ESKİ sürüm lider gelen kutusunu boşaltmaz ─────────

    long drainerLeaseRows() {
        Integer n = mp.jdbc().queryForObject("SELECT count(*) FROM monitor_check_schedule WHERE monitor_key = ?",
                Integer.class, "lease:" + SchedulerService.EVAL_INBOX_DRAINER);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("Eski sürüm lider (boşaltıcı kirası yok): takipçinin 'Şimdi kontrol et' sonucu 20.164.0 gibi YEREL değerlendirilir")
    void oldLeaderWithoutHeartbeat_followerEvaluatesLocally() {
        mp.jdbc().update("DELETE FROM monitor_check_schedule WHERE monitor_key = ?", "lease:" + SchedulerService.EVAL_INBOX_DRAINER);
        ReflectionTestUtils.setField(a.scheduler(), "manualEvalInbox", null);   // 20.164.0: boşaltma yok, kira da yok
        openAlarmByX();

        a.scheduler().drainManualEvalInbox();                            // eski lider: hiçbir şey yazmaz
        assertThat(drainerLeaseRows()).isZero();
        b.scheduler().evaluateHttpNow(x, raw(true));

        assertThat(inboxRows()).as("gelen kutusuna yazılmadı (eski lider hiç boşaltmayacaktı)").isZero();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);   // takipçide yerel kapanış (eski davranış)
    }

    @Test
    @DisplayName("B1: paylaşılan zamanlayıcı havuzu meşgul — lider 40 sn boşaltma turu koşmadı ama hâlâ lider → takipçi YİNE iletir")
    void busyPool_noDrainFor40s_sameLeader_stillForwarded() {
        openAlarmByX();
        mp.advance(40_000);                                               // boşaltma turu yok (havuz dolu); lider aynı
        b.scheduler().evaluateHttpNow(x, raw(true));
        assertThat(inboxRows()).as("yetenek kararı: ilanın sahibi = tarama lideri → iletilir").isEqualTo(1);
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        a.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);   // iletilen satır TAM BİR kez işlendi
    }

    @Test
    @DisplayName("B1 güvenlik ağı: lider 10 dk'dan uzun HİÇ boşaltma turu koşmadıysa (lider kalsa da) takipçi yerel değerlendirir")
    void drainerSilentBeyondSafetyNet_followerEvaluatesLocally() {
        openAlarmByX();
        for (int i = 0; i < 11; i++) {
            mp.advance(60_000);
            a.scheduler().renewSweepLeadership();                        // tarama kirası yenileniyor, boşaltma yok
        }
        assertThat(a.scheduler().isSweepLeaderNow()).isTrue();
        b.scheduler().evaluateHttpNow(x, raw(true));
        assertThat(inboxRows()).isZero();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);
    }

    @Test
    @DisplayName("B1: lider ölür, yeni lider (yeni sürüm) devralır — ilk boşaltma turuna dek ilan ölü liderin (yerel); "
            + "ilk turundan sonra iletilir")
    void deadLeader_newLeaderForwardedAfterFirstTick() {
        MonitoringMultiPodKit.PodWithMos c = MonitoringMultiPodKit.podWithMos(mp, 3);
        ReflectionTestUtils.setField(c.scheduler(), "manualEvalInbox", inbox());
        openAlarmByX();
        mp.advance(MonitoringMultiPodKit.TTL + 1_000);                    // A öldü (ilanını bırakmadı), kirası doldu
        assertThat(b.scheduler().isSweepLeaderNow()).isTrue();
        assertThat(c.scheduler().isSweepLeaderNow()).isFalse();
        assertThat(mp.lease(SchedulerService.EVAL_INBOX_DRAINER).owner()).isEqualTo(MultiPodTestSupport.owner(1));

        c.scheduler().evaluateHttpNow(x, raw(true));                     // ilan A'nın, lider B → yerel
        assertThat(inboxRows()).isZero();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);

        b.scheduler().drainManualEvalInbox();                            // B'nin ilk turu: ilanı koşulsuz devralır
        assertThat(mp.lease(SchedulerService.EVAL_INBOX_DRAINER).owner()).isEqualTo(MultiPodTestSupport.owner(2));
        c.scheduler().evaluateHttpNow(x, raw(true));
        assertThat(inboxRows()).isEqualTo(1);
        b.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
        verify(escalation, times(2)).resolveMonitoringAlertsForDomain(URL, TYPE);
    }

    @Test
    @DisplayName("Liderliği kaybeden pod ilk boşaltma turunda kirasını bırakır; yeni lider hemen devralır ve iletim sürer")
    void exLeaderRetiresHeartbeat_newLeaderTakesOver() {
        assertThat(mp.lease(SchedulerService.EVAL_INBOX_DRAINER).owner()).isEqualTo(MultiPodTestSupport.owner(1));
        mp.advance(MonitoringMultiPodKit.TTL + 1_000);                    // A'nın tarama kirası doldu
        assertThat(b.scheduler().isSweepLeaderNow()).isTrue();
        a.scheduler().drainManualEvalInbox();                            // A artık takipçi → boşaltıcı kirasını bırakır
        assertThat(drainerLeaseRows()).isZero();
        b.scheduler().drainManualEvalInbox();                            // B yeni lider → kira B'de
        assertThat(mp.lease(SchedulerService.EVAL_INBOX_DRAINER).owner()).isEqualTo(MultiPodTestSupport.owner(2));

        openAlarmByX();
        a.scheduler().evaluateHttpNow(x, raw(true));                     // A takipçi → B'ye iletir
        assertThat(inboxRows()).isEqualTo(1);
        b.scheduler().drainManualEvalInbox();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);
    }

    // ── Çok pod Sürüm 3-2 inceleme (2026-10-28, MOS M-1 yol 1): okuma ayarı AÇIKKEN takipçi devir aralığında da iletir ──────

    /** Devirde okuma ayarını kümede açar (ortak ayar sahtesi — her pod aynı değeri görür). */
    void readSettingOn() {
        org.mockito.Mockito.doReturn(true).when(mp.mock(AppSettingsService.class)).getBoolean(EvalStateReadSwitch.KEY, false);
    }

    @Test
    @DisplayName("J-1 (ayar kapalı): lider kirayı bıraktı, yeni lider henüz yok — takipçinin 'Şimdi kontrol et' sonucu bugünkü gibi YEREL")
    void readOff_leaderlessGap_followerEvaluatesLocally() {
        openAlarmByX();
        a.scheduler().releaseSweepLeadership();                          // RELEASED: kira boş, ilan hâlâ A'nın
        assertThat(mp.leaseOwner("sweep-leader")).isNull();
        b.scheduler().evaluateHttpNow(x, raw(true));                     // B'nin "takipçi" kararı önbellekte (≤ 5 sn)
        assertThat(inboxRows()).isZero();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);
    }

    @Test
    @DisplayName("M-1 yol 1 (ayar açık): kira boşken takipçi de, kapanan eski lider de sonucu gelen kutusuna yazar (yerel DEĞİL); "
            + "yeni lider devralıp boşaltınca TAM BİR kez değerlendirir")
    void readOn_leaderlessGap_followerAndDyingLeaderForward() {
        readSettingOn();
        openAlarmByX();
        a.scheduler().releaseSweepLeadership();
        assertThat(mp.leaseOwner("sweep-leader")).isNull();

        b.scheduler().evaluateHttpNow(x, raw(false));                    // devir aralığında DOWN — kaybolmamalı
        a.scheduler().evaluateHttpNow(x, raw(true));                     // bırakan pod (artık lider değil) da iletir
        assertThat(inboxRows()).as("iki sonuç da gelen kutusunda").isEqualTo(2);
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        mp.advance(SchedulerService.SWEEP_FOLLOWER_RECHECK_MS + 1);
        assertThat(b.scheduler().isSweepLeaderNow()).as("B devraldı").isTrue();
        b.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
        b.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
    }

    @Test
    @DisplayName("M-1 yol 1 (ayar açık): yeni lider ilk boşaltma turunu koşmadan (ilan ölü liderin) takipçi YİNE iletir")
    void readOn_newLeaderBeforeFirstTick_followerForwards() {
        readSettingOn();
        MonitoringMultiPodKit.PodWithMos c = MonitoringMultiPodKit.podWithMos(mp, 3);
        ReflectionTestUtils.setField(c.scheduler(), "manualEvalInbox", inbox());
        openAlarmByX();
        mp.advance(MonitoringMultiPodKit.TTL + 1_000);                    // A öldü (ilanını bırakmadı)
        assertThat(b.scheduler().isSweepLeaderNow()).isTrue();
        assertThat(c.scheduler().isSweepLeaderNow()).isFalse();
        assertThat(mp.lease(SchedulerService.EVAL_INBOX_DRAINER).owner()).isEqualTo(MultiPodTestSupport.owner(1));

        c.scheduler().evaluateHttpNow(x, raw(true));
        assertThat(inboxRows()).as("ilan eski liderin ama ayar açık → iletildi").isEqualTo(1);
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        b.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);
    }

    @Test
    @DisplayName("M-1 yol 1: devirde yükleme sürerken (değerlendirme kapısı LOADING) lider gelen kutusundan satır ALMAZ, ilanı yine "
            + "yazar; yükleme bitince ilk turda satır değerlendirilir")
    void drainSkipsWhileEvalLoadPending() {
        readSettingOn();
        b.mos().setEvalReadEnabled(() -> true);
        b.mos().setEvalStateSink(org.mockito.Mockito.mock(EvalStateSink.class));   // engel yok → GAINED kapıyı kurar
        openAlarmByX();
        mp.advance(MonitoringMultiPodKit.TTL + 1_000);                    // A öldü
        assertThat(b.scheduler().isSweepLeaderNow()).as("B devraldı (GAINED)").isTrue();
        long token = b.mos().evalLoadToken();
        assertThat(token).as("yükleme bekleniyor").isPositive();
        assertThat(b.scheduler().evalLoadPending()).isTrue();

        assertThat(a.scheduler().isSweepLeaderNow()).isFalse();
        a.scheduler().evaluateHttpNow(x, raw(true));                     // A artık takipçi → iletir
        assertThat(inboxRows()).isEqualTo(1);

        b.scheduler().drainManualEvalInbox();                            // yükleme sürüyor → satır alınmaz
        assertThat(inboxRows()).as("yükleyici kutuyu dolu görsün").isEqualTo(1);
        assertThat(mp.lease(SchedulerService.EVAL_INBOX_DRAINER).owner()).as("ilan yine yazıldı")
                .isEqualTo(MultiPodTestSupport.owner(2));
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        b.mos().evalLoadSkipped(token, null);                            // yükleme bitti (yüklenmedi)
        assertThat(b.mos().evalLoadToken()).isZero();
        b.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);
    }
}
