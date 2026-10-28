package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod R1-B (2026-10-21, MP-12 + SWEEPS-12) — liderlik değişimi bellek içi alarm durumunu SIFIRLAR ve zamanlanmış turun
 * zinciri kirayı kaybeden pod'da karar YAZMAZ.
 *
 * <p>Gerçek kira geçişi: iki {@link SchedulerService} pod'u aynı H2 + ortak saat ({@link MultiPodTestSupport}); her pod'un
 * kendi {@link MonitoringOutageService}'i, kapısı ({@code isSweepLeaderNow}) ve olay dinleyicisi bağlı.
 */
class LeadershipChangeResetTest {

    static final long TTL = MonitoringMultiPodKit.TTL;
    static final String TYPE = EscalationService.TYPE_PORT_DOWN;
    static final String HOST = "db.example.com";

    MultiPodTestSupport mp;
    AlertEventRepository alertRepo;
    EscalationService escalation;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        alertRepo = mp.mock(AlertEventRepository.class);
        escalation = mp.mock(EscalationService.class);
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    static MonitoringOutageService.SweepItem item(boolean up, int recoveryChecks, Supplier<Map<String, Object>> recheck) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_recovery_checks", recoveryChecks);
        return new MonitoringOutageService.SweepItem(TYPE, HOST, "5432/TCP", up, up ? null : "connect timed out", ctx, recheck);
    }

    static Map<String, Object> status(String s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", s);
        return m;
    }

    AlertEvent openAlarm() {
        AlertEvent e = new AlertEvent();
        e.setId(42L);
        e.setDomain(HOST);
        e.setAlertType(TYPE);
        e.setAlertLevel("WARNING");
        e.setAcknowledged(false);
        e.setResolved(false);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(e));
        return e;
    }

    /** A kirayı kaybeder (süresi dolar, B alır), sonra B bırakır ve A geri alır. */
    void flipAwayAndBack(MonitoringMultiPodKit.PodWithMos a, MonitoringMultiPodKit.PodWithMos b, Runnable whileBLeads) {
        mp.advance(TTL + 1_000);
        assertThat(b.pod().scheduler().isSweepLeaderNow()).as("B kirayı alır").isTrue();
        a.pod().scheduler().renewSweepLeadership();                 // A kaybı fark eder → LOST → sıfırlama
        assertThat(a.pod().scheduler().isSweepLeaderNow()).isFalse();
        whileBLeads.run();
        b.pod().scheduler().releaseSweepLeadership();
        mp.advance(6_000);                                            // takipçi önbelleği (5 sn) geçsin
        assertThat(a.pod().scheduler().isSweepLeaderNow()).as("A kirayı geri alır").isTrue();
    }

    @Test
    @DisplayName("MP-12: lider → takipçi → lider; arada DOWN başka pod'da — geri dönen liderin İLK sağlıklı sonucu alarmı KAPATMAZ")
    void regainedLeader_firstHealthyResultDoesNotClose() {
        MonitoringMultiPodKit.PodWithMos a = MonitoringMultiPodKit.podWithMos(mp, 1);
        MonitoringMultiPodKit.PodWithMos b = MonitoringMultiPodKit.podWithMos(mp, 2);
        openAlarm();
        assertThat(a.pod().scheduler().isSweepLeaderNow()).isTrue();

        // A 2/3 ardışık sağlıklı sayar (kapanış bekliyor).
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        mp.advance(60_000);
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        // Kira B'ye geçer; B saatlerce DOWN görür (A görmez — takipçi); kira A'ya döner.
        flipAwayAndBack(a, b, () -> {
            b.mos().handleSweepResults(TYPE, List.of(item(false, 3, () -> status("down"))));
            mp.advance(60_000);
            b.mos().handleSweepResults(TYPE, List.of(item(false, 3, () -> status("down"))));
        });
        assertThat(a.pod().events().ofType(SweepLeadershipChangedEvent.class))
                .extracting(SweepLeadershipChangedEvent::reason).containsExactly("GAINED", "LOST", "GAINED");

        // Eski davranış: A'nın bayat 2/3 sayacı + bu sonuç = 3/3 → kapanış (dalgalanma). Şimdi: 1/3.
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        mp.advance(60_000);
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        mp.advance(60_000);
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(HOST, TYPE);
    }

    @Test
    @DisplayName("MP-12: sıfırlama — sayaçlar, kardeş gözlemi, tur ve bastırma bayrakları, teyit zincirleri temizlenir")
    void resetClearsAllVolatileState() {
        MonitoringOutageService s = MonitoringMultiPodKit.mos(mp);
        MonitoringMultiPodKit.Manual ex = new MonitoringMultiPodKit.Manual();
        ReflectionTestUtils.setField(s, "confirmExecutor", ex);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        s.handleSweepResults(TYPE, List.of(item(false, 3, () -> status("down"))));   // teyit zinciri başlar
        assertThat(s.activeConfirmations(null)).hasSize(1);

        s.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "pod1", 0L, SweepLeadershipChangedEvent.LOST));

        assertThat(s.activeConfirmations(null)).as("zincir kaydı temizlendi").isEmpty();
        for (String f : List.of("recoveryUpCount", "recoveryLastCountedAt", "recoveryGeneration", "downObserved",
                "scheduledRoundSeen", "suppressionActive", "outageReconciled", "chainOwner")) {
            Object v = ReflectionTestUtils.getField(s, f);
            assertThat(v instanceof Map<?, ?> m ? m.isEmpty() : ((java.util.Collection<?>) v).isEmpty()).as(f).isTrue();
        }
        // Eski zincirin kuyruktaki adımı koşsa da hiçbir şeye dokunmaz (kayıt yok, alarm yok).
        ex.drain();
        verify(escalation, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
        assertThat(s.activeConfirmations(null)).isEmpty();

        // Aynı anahtarda YENİ zincir başlayabilir ve eski zincirin geç adımı onun kaydını silemez.
        s.handleSweepResults(TYPE, List.of(item(false, 3, () -> status("down"))));
        assertThat(s.activeConfirmations(null)).hasSize(1);
        ex.drain();
        verify(escalation, times(1)).processConfirmedOutage(eq(HOST), eq(TYPE), anyString(), any());
        assertThat(s.activeConfirmations(null)).isEmpty();
    }

    @Test
    @DisplayName("SWEEPS-12: kira yarıda kaybedilirse teyit zinciri alarm AÇMAZ (olay gelmeden de: son adımda kapı sorulur)")
    void confirmationChain_leaseLost_doesNotOpen() {
        MonitoringOutageService s = MonitoringMultiPodKit.mos(mp);
        MonitoringMultiPodKit.Manual ex = new MonitoringMultiPodKit.Manual();
        ReflectionTestUtils.setField(s, "confirmExecutor", ex);
        AtomicBoolean leader = new AtomicBoolean(true);
        s.setScheduledResultsFence(leader::get);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        AtomicInteger rechecks = new AtomicInteger();

        s.handleSweepResults(TYPE, List.of(item(false, 1, () -> { rechecks.incrementAndGet(); return status("down"); })));
        leader.set(false);   // kira başka pod'a geçti (bu pod henüz olayı görmedi)
        ex.drain();

        assertThat(rechecks.get()).as("zincir adımları koştu").isEqualTo(3);
        verify(escalation, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());
        assertThat(s.activeConfirmations(null)).as("kayıt bırakıldı — yeni dönemde zincir yeniden başlayabilir").isEmpty();
    }

    @Test
    @DisplayName("SWEEPS-12: zamanlanmış turun kurtarma zinciri kirayı kaybeden pod'da alarmı KAPATMAZ; elle kontrolün zinciri muaf")
    void recoveryChain_leaseLost_scheduledDoesNotClose_manualExempt() {
        MonitoringOutageService s = MonitoringMultiPodKit.mos(mp);
        MonitoringMultiPodKit.Manual rex = new MonitoringMultiPodKit.Manual();
        ReflectionTestUtils.setField(s, "recoveryExecutor", rex);
        AtomicBoolean leader = new AtomicBoolean(true);
        s.setScheduledResultsFence(leader::get);
        openAlarm();
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_recovery_checks", 3);
        ctx.put("monitor_recovery_interval_ms", 1000L);
        MonitoringOutageService.SweepItem up = new MonitoringOutageService.SweepItem(TYPE, HOST, "5432/TCP", true, null,
                ctx, () -> status("up"));

        // Zamanlanmış tur aktif kurtarma zinciri başlatır; kira kaybedilir; zincirin son adımı kapatmaz.
        s.handleSweepResults(TYPE, List.of(up));
        leader.set(false);
        rex.drain();
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        // Elle kontrolün zinciri (bugünkü kural) kapıdan muaf: aynı durumda kapatır (sayaç 2/3 → aktif zincir → 3/3).
        mp.advance(60_000);
        s.handleSweepResults(TYPE, List.of(up), true);
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
        rex.drain();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(HOST, TYPE);
    }

    @Test
    @DisplayName("MP-12: kardeş DOWN gözlemi bayat kalmaz — liderlik dönünce elle kapanış ilk zamanlanmış tura dek ertelenir (D-6)")
    void staleSiblingObservationDropped_manualCloseWaitsForFirstRound() {
        MonitoringOutageService s = MonitoringMultiPodKit.mos(mp);
        AlertEvent e = openAlarm();
        e.setContextJson("{\"monitor_id\":1,\"team_id\":7,\"standalone\":true}");
        e.setTeamId(7L);
        Map<String, Object> x = new LinkedHashMap<>(Map.of("monitor_id", 1L, "team_id", 7L, "standalone", true));
        Map<String, Object> y = new LinkedHashMap<>(Map.of("monitor_id", 2L, "team_id", 7L, "standalone", true));
        // Lider dönemi: Y DOWN gözlendi.
        s.handleSweepResults(TYPE, List.of(new MonitoringOutageService.SweepItem(TYPE, HOST, "5433/TCP", false,
                "connect timed out", y, () -> status("down"))));
        clearInvocations(escalation);
        // Aynı dönemde: kardeş Y DOWN gözlemi açanın (X) elle sağlıklı sonucunu bekletir (D2).
        s.handleSweepResults(TYPE, List.of(new MonitoringOutageService.SweepItem(TYPE, HOST, "5432/TCP", true, null, x,
                () -> status("up"))), true);
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        s.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "pod1", 0L, SweepLeadershipChangedEvent.LOST));
        s.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(true, "pod1", 0L, SweepLeadershipChangedEvent.GAINED));

        // Yeni dönemde Y'nin durumu bilinmiyor: Y'nin (açan DEĞİL) elle sağlıklı sonucu kapatmaz (D-6 koruması yeniden
        // devrede — bayat "tur görüldü" işareti silindi).
        s.handleSweepResults(TYPE, List.of(new MonitoringOutageService.SweepItem(TYPE, HOST, "5433/TCP", true, null, y,
                () -> status("up"))), true);
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        // Açan izlemenin (X) elle sağlıklı sonucu kapatır; bayat Y DOWN gözlemi artık bekletmez.
        s.handleSweepResults(TYPE, List.of(new MonitoringOutageService.SweepItem(TYPE, HOST, "5432/TCP", true, null, x,
                () -> status("up"))), true);
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(HOST, TYPE);
    }

    // ── Çok pod Sürüm 3-1: kalıcı değerlendirme kaydedicisi bağlıyken anlam AYNI (tasarım R3-1 I-1, §2.5) ─────────────

    @Test
    @DisplayName("3-1: her pod'a FIRLATAN kaydedici bağlı — lider → takipçi → lider; ilk sağlıklı sonuç yine KAPATMAZ, üçüncü kapatır")
    void regainedLeader_withThrowingRecorder_sameSemantics() {
        MonitoringMultiPodKit.PodWithMos a = MonitoringMultiPodKit.podWithMos(mp, 1);
        MonitoringMultiPodKit.PodWithMos b = MonitoringMultiPodKit.podWithMos(mp, 2);
        a.mos().setEvalStateSink(com.sitemonitor.service.evalstate.RecordingSink.throwing());
        b.mos().setEvalStateSink(com.sitemonitor.service.evalstate.RecordingSink.throwing());
        openAlarm();
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        mp.advance(60_000);
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        flipAwayAndBack(a, b, () -> {
            b.mos().handleSweepResults(TYPE, List.of(item(false, 3, () -> status("down"))));
            mp.advance(60_000);
            b.mos().handleSweepResults(TYPE, List.of(item(false, 3, () -> status("down"))));
        });
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
        mp.advance(60_000);
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        mp.advance(60_000);
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(HOST, TYPE);
        assertThat(a.mos().evalStateHookFailures()).isPositive();
    }

    // ── Çok pod Sürüm 3-2: okuma ayarı KAPALIYKEN liderlik geri dönünce bellek boş, kapı kurulmaz (tasarım R3-2 J-1) ──

    @Test
    @DisplayName("3-2 J-1: okuma ayarı kapalı — gerçek kira geçişinde GAINED belleği BOŞ bırakır, kapı kurulmaz, kaydedici sorulmaz, yük reddedilir")
    void readOff_gainedLeavesMemoryEmpty_noArm() {
        MonitoringMultiPodKit.PodWithMos a = MonitoringMultiPodKit.podWithMos(mp, 1);
        MonitoringMultiPodKit.PodWithMos b = MonitoringMultiPodKit.podWithMos(mp, 2);
        AtomicInteger blockerCalls = new AtomicInteger();
        com.sitemonitor.service.evalstate.RecordingSink rec = com.sitemonitor.service.evalstate.RecordingSink.recording();
        a.mos().setEvalStateSink(new com.sitemonitor.service.evalstate.EvalStateSink() {
            @Override public String loadBlocker() { blockerCalls.incrementAndGet(); return null; }   // hazır — ama sorulmamalı
            @Override public void recovery(String k, String t, String d) { rec.recovery(k, t, d); }
            @Override public void observation(String t, MonitoringOutageService.SweepItem i, boolean m, long at) {
                rec.observation(t, i, m, at);
            }
            @Override public void typeState(String t) { rec.typeState(t); }
            @Override public void chainStart(String k, long id, MonitoringOutageService.SweepItem i,
                                             MonitoringOutageService.ConfirmState s, String f, long d) { rec.chainStart(k, id, i, s, f, d); }
            @Override public void chainProgress(String k, long id, MonitoringOutageService.ConfirmState s,
                                                List<Map<String, Object>> at) { rec.chainProgress(k, id, s, at); }
            @Override public void chainFinishing(String k, long id, int t) { rec.chainFinishing(k, id, t); }
            @Override public void chainEnd(String k, long id) { rec.chainEnd(k, id); }
            @Override public void sweepEnd(String t) { rec.sweepEnd(t); }
            @Override public void bindSource(com.sitemonitor.service.evalstate.EvalStateSource s) { rec.bindSource(s); }
            @Override public Status status() { return null; }
        });
        a.mos().setEvalReadEnabled(() -> false);
        a.mos().setEvalLoadPodId("pod-1");
        openAlarm();
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        mp.advance(60_000);
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));

        flipAwayAndBack(a, b, () -> b.mos().handleSweepResults(TYPE, List.of(item(false, 3, () -> status("down")))));
        assertThat(a.pod().events().ofType(SweepLeadershipChangedEvent.class))
                .extracting(SweepLeadershipChangedEvent::reason).containsExactly("GAINED", "LOST", "GAINED");

        com.sitemonitor.service.evalstate.EvalSnapshot snap = a.mos().verifySnapshot();
        assertThat(snap.recoveryUpCount()).isEmpty();
        assertThat(snap.recoveryLastCountedAt()).isEmpty();
        assertThat(snap.downObserved()).isEmpty();
        assertThat(snap.suppressionActive()).isEmpty();
        assertThat(snap.scheduledRoundSeen()).isEmpty();
        assertThat(snap.inFlight()).isEmpty();
        assertThat(a.mos().evalLoadGate().state()).isEqualTo(com.sitemonitor.service.evalstate.EvalLoadGate.State.NONE);
        assertThat(a.mos().evalLoadToken()).isZero();
        assertThat(blockerCalls).as("ayar kapalıyken kaydedici sorulmaz").hasValue(0);
        com.sitemonitor.service.evalstate.EvalLoadReport r = a.mos().lastEvalLoad();
        assertThat(r.reason()).isEqualTo(com.sitemonitor.service.evalstate.EvalLoadReport.REASON_SETTING_OFF);
        assertThat(r.pod()).isEqualTo("pod-1");

        // Yazıcı (paket A) yine de bir yük verse: kapı kurulmadı → uygulanmaz, bellek boş kalır.
        com.sitemonitor.service.evalstate.EvalLoad stray = new com.sitemonitor.service.evalstate.EvalLoad(
                ((java.util.concurrent.atomic.AtomicLong) ReflectionTestUtils.getField(a.mos(), "evalEpoch")).get(),
                com.sitemonitor.service.evalstate.EvalLoad.TIER_FULL, Map.of(TYPE + ":" + HOST,
                new com.sitemonitor.service.evalstate.EvalLoad.Recovery(2, null)), Map.of(), java.util.Set.of(),
                java.util.Set.of(TYPE), Map.of(), Map.of(), null);
        assertThat(a.mos().applyEvalLoad(stray)).isFalse();
        assertThat(a.mos().verifySnapshot().recoveryUpCount()).isEmpty();
        assertThat(a.mos().lastEvalLoad()).as("rapor değişmez").isSameAs(r);

        // Bugünkü anlam: geri dönen liderin ilk sağlıklı sonucu kapatmaz, üçüncüsü kapatır.
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
        mp.advance(60_000);
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        mp.advance(60_000);
        a.mos().handleSweepResults(TYPE, List.of(item(true, 3, () -> status("up"))));
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(HOST, TYPE);
    }

    @Test
    @DisplayName("3-1: kaydedici bağlıyken sıfırlama — kanca ÇAĞIRMAZ (kaydedici aynı olayı dinler), kaynak boş, yeni zincir kayda girer")
    void reset_withRecorderAttached_noHooksSourceEmpty() {
        MonitoringOutageService s = MonitoringMultiPodKit.mos(mp);
        MonitoringMultiPodKit.Manual ex = new MonitoringMultiPodKit.Manual();
        ReflectionTestUtils.setField(s, "confirmExecutor", ex);
        com.sitemonitor.service.evalstate.RecordingSink sink = com.sitemonitor.service.evalstate.RecordingSink.recording();
        s.setEvalStateSink(sink);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        s.handleSweepResults(TYPE, List.of(item(false, 3, () -> status("down"))));
        assertThat(sink.ops("chainStart")).hasSize(1);
        sink.clear();

        s.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(false, "pod1", 0L, SweepLeadershipChangedEvent.LOST));
        assertThat(sink.ops()).as("sıfırlama kanca çağırmaz").isEmpty();
        assertThat(s.verifySnapshot().inFlight()).isEmpty();
        assertThat(s.typeValues(TYPE)).isEqualTo(com.sitemonitor.service.evalstate.EvalStateSource.TypeValues.NONE);

        ex.drain();   // eski zincirin geç adımı: hiçbir kancaya dokunmaz (kayıt yok, dönem eski)
        assertThat(sink.ops()).isEmpty();
        verify(escalation, never()).processConfirmedOutage(anyString(), anyString(), anyString(), any());

        s.handleSweepResults(TYPE, List.of(item(false, 3, () -> status("down"))));
        assertThat(sink.ops("chainStart")).hasSize(1);
        ex.drain();
        verify(escalation, times(1)).processConfirmedOutage(eq(HOST), eq(TYPE), anyString(), any());
        assertThat(sink.ops("chainEnd")).hasSize(1);
    }
}
