package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.service.evalstate.EvalStateMode;
import com.sitemonitor.service.evalstate.EvalStateRecorder;
import com.sitemonitor.service.evalstate.EvalStateSchemaPatch;
import com.sitemonitor.service.evalstate.EvalStateVerifier;
import com.sitemonitor.service.evalstate.EvalVerifyReport;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-1 — kalıcı değerlendirme durumu İKİ POD'DA (tasarım R3-1 §4 A2): her pod'un kendi MOS'u + kendi kaydedicisi,
 * ortak H2. A lider → kuşak 1 satırları; kira A → B → kuşak 2; A'nın geç boşaltması kuşak çitiyle reddedilir; B'nin
 * doğrulaması 0 uyuşmazlık; B'nin devraldığı sayılar A'nın durumu; B belleği yine sıfırlanır (LeadershipChangeResetTest);
 * takipçinin elle değerlendirmesi hiçbir şey yazmaz.
 */
class EvalStateMultiPodTest {

    static final long TTL = MonitoringMultiPodKit.TTL;
    static final String TYPE = EscalationService.TYPE_PORT_DOWN;
    static final String HOST = "db.example.com";

    MultiPodTestSupport mp;
    AlertEventRepository alertRepo;
    EscalationService escalation;
    final List<EvalStateRecorder> recorders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        new EvalStateSchemaPatch(mp.jdbc()).apply();
        alertRepo = mp.mock(AlertEventRepository.class);
        escalation = mp.mock(EscalationService.class);
    }

    @AfterEach
    void tearDown() {
        recorders.forEach(EvalStateRecorder::stop);
        mp.close();
    }

    record PodRec(MonitoringMultiPodKit.PodWithMos pod, EvalStateRecorder rec) {
        MonitoringOutageService mos() {
            return pod.mos();
        }
    }

    PodRec podWithRecorder(int n) {
        MonitoringMultiPodKit.PodWithMos p = MonitoringMultiPodKit.podWithMos(mp, n);
        EvalStateVerifier verifier = new EvalStateVerifier(p.mos(), mp.jdbc());
        EvalStateRecorder rec = EvalStateRecorder.builder(mp.jdbc()).writer(p.pod().owner())
                .leaderHint(p.scheduler()::sweepLeaderHint).mode(() -> EvalStateMode.VERIFY).clock(mp.clockSupplier())
                .leaseOwner(p.pod().schedule().owner())   // üretimdeki gibi: kuşak CAS'ı tarama lideri kirasına bağlı
                .verifier(() -> verifier).flushMs(50).verifyMs(300_000).lastStatusRefreshMs(120_000).build();
        p.pod().events().listen(SweepLeadershipChangedEvent.class, rec::onSweepLeadershipChanged);
        p.mos().setEvalStateSink(rec);
        recorders.add(rec);
        return new PodRec(p, rec);
    }

    static MonitoringOutageService.SweepItem item(boolean up, long monitorId, Supplier<Map<String, Object>> recheck) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_recovery_checks", 3);
        ctx.put("monitor_id", monitorId);
        return new MonitoringOutageService.SweepItem(TYPE, HOST, monitorId + "/TCP", up, up ? null : "connect timed out", ctx,
                recheck);
    }

    static Map<String, Object> status(String s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", s);
        return m;
    }

    void openAlarm() {
        AlertEvent e = new AlertEvent();
        e.setId(42L);
        e.setDomain(HOST);
        e.setAlertType(TYPE);
        e.setAlertLevel("WARNING");
        e.setAcknowledged(false);
        e.setResolved(false);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(e));
    }

    long rows(String table, String where, Object... args) {
        return mp.jdbc().queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Long.class, args);
    }

    @Test
    @DisplayName("A lider kuşak 1 → kira B'ye → kuşak 2; A'nın geç boşaltması reddedilir; B doğrular 0; B devraldığını sayar; B belleği sıfır")
    void leadershipFlip_generationFencing_verifyZero_inherited() {
        PodRec a = podWithRecorder(1);
        PodRec b = podWithRecorder(2);
        openAlarm();
        assertThat(a.pod().scheduler().isSweepLeaderNow()).isTrue();

        // A: kurtarma sayacı 2/3 + kardeş DOWN (5433 aynı sahip, alarm açık kalır).
        a.mos().handleSweepResults(TYPE, List.of(item(true, 5432, () -> status("up"))));
        mp.advance(60_000);
        a.mos().handleSweepResults(TYPE, List.of(item(true, 5432, () -> status("up"))));
        assertThat(a.rec().flushNow()).isTrue();
        assertThat(rows("monitor_eval_state", "gen = 1 AND writer = ?", a.pod().pod().owner())).isEqualTo(1);
        assertThat(a.rec().verifyNow().reason()).isEqualTo(EvalStateVerifier.REASON_RESYNC);
        mp.advance(1_000);
        assertThat(a.rec().flushNow()).isTrue();
        EvalVerifyReport ra = a.rec().verifyNow();
        assertThat(ra.status()).isEqualTo(EvalVerifyReport.STATUS_OK);

        // A bir işlem daha kuyruğa alır ama boşaltmadan kira B'ye geçer (A olayı henüz görmedi).
        mp.advance(60_000);
        a.mos().handleSweepResults(TYPE, List.of(item(false, 5433, () -> status("down"))), true);   // elle: kuyruğa girer
        mp.advance(TTL + 1_000);
        assertThat(b.pod().scheduler().isSweepLeaderNow()).as("B kirayı alır").isTrue();
        b.mos().handleSweepResults(TYPE, List.of(item(true, 5432, () -> status("up"))));
        assertThat(b.rec().flushNow()).isTrue();
        assertThat(rows("monitor_eval_type_state", "alert_type = '*' AND gen = 2 AND writer = ?", b.pod().pod().owner()))
                .as("B yeni kuşak aldı").isEqualTo(1);

        // A'nın geç boşaltması: çit nabzı 0 satır → bekleyenler atılır, yazmaz.
        a.rec().flushNow();
        assertThat(rows("monitor_last_status", "writer = ? AND gen = 1 AND item_key = 'm:5433'", a.pod().pod().owner()))
                .as("eski liderin geç yazımı reddedildi").isZero();

        // B: devralınan = A'nın durumu (sayaç > 0); B belleği sıfırdan (ilk sağlıklı sonuç 1/3 — kapatmaz).
        assertThat(b.rec().status().inherited()).isNotNull();
        assertThat(b.rec().status().inherited().recoveryCounters()).isEqualTo(1);
        assertThat(b.rec().status().inherited().previousGeneration()).isEqualTo(1L);
        assertThat(b.mos().recoveryValues(TYPE + ":" + HOST).upCount()).isEqualTo(1);
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());

        assertThat(b.rec().verifyNow().reason()).isEqualTo(EvalStateVerifier.REASON_RESYNC);
        mp.advance(1_000);
        b.rec().flushNow();
        EvalVerifyReport rb = b.rec().verifyNow();
        assertThat(rb.status()).isEqualTo(EvalVerifyReport.STATUS_OK);
        assertThat(rb.generation()).isEqualTo(2L);
    }

    @Test
    @DisplayName("FX #1: kira B'ye geçti, A henüz fark etmedi (ipucu true, kuşak denemesi gecikti) — A kuşak alamaz, B yazmaya devam eder")
    void staleFormerLeaderCannotFenceLeaseHolder() {
        PodRec a = podWithRecorder(1);
        PodRec b = podWithRecorder(2);
        openAlarm();
        assertThat(a.pod().scheduler().isSweepLeaderNow()).isTrue();   // A lider ama ilk kuşak denemesi henüz koşmadı
        mp.advance(TTL + 1_000);
        assertThat(b.pod().scheduler().isSweepLeaderNow()).as("B kirayı aldı").isTrue();
        b.mos().handleSweepResults(TYPE, List.of(item(true, 5432, () -> status("up"))));
        assertThat(b.rec().flushNow()).isTrue();
        assertThat(b.rec().generation()).isEqualTo(1);

        assertThat(a.pod().scheduler().sweepLeaderHint()).as("A olayı henüz görmedi").isTrue();
        assertThat(a.rec().flushNow()).as("A'nın geç kuşak denemesi kirada takılır").isFalse();
        assertThat(a.rec().generation()).isZero();

        mp.advance(60_000);
        b.mos().handleSweepResults(TYPE, List.of(item(true, 5432, () -> status("up"))));
        assertThat(b.rec().flushNow()).as("B çitlenmedi").isTrue();
        assertThat(b.rec().generation()).isEqualTo(1);
        assertThat(b.rec().status().fenced()).isFalse();
        assertThat(b.rec().status().active()).isTrue();
        assertThat(rows("monitor_eval_type_state", "alert_type = '*' AND gen = 1 AND writer = ?", b.pod().pod().owner()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FX #4 / F6: hem MOS hem kaydedici olayı dinler — MOS önce sıfırlar (dinleyici sırası), kaydedici sonra temizler")
    void leadershipListenerOrder() {
        assertThat(MonitoringOutageService.LEADERSHIP_LISTENER_ORDER)
                .isLessThan(EvalStateRecorder.LEADERSHIP_LISTENER_ORDER);
    }

    @Test
    @DisplayName("Takipçinin elle değerlendirmesi (eski lider gelen kutusunu boşaltmıyor) hiçbir satır yazmaz")
    void followerManualEvaluationWritesNothing() {
        PodRec a = podWithRecorder(1);
        PodRec b = podWithRecorder(2);
        openAlarm();
        assertThat(a.pod().scheduler().isSweepLeaderNow()).isTrue();
        assertThat(b.pod().scheduler().isSweepLeaderNow()).isFalse();
        b.mos().handleSweepResults(TYPE, List.of(item(true, 5432, () -> status("up"))), true);
        b.rec().flushNow();
        assertThat(rows("monitor_last_status", "writer = ?", b.pod().pod().owner())).isZero();
        assertThat(rows("monitor_eval_state", "writer = ?", b.pod().pod().owner())).isZero();
    }
}
