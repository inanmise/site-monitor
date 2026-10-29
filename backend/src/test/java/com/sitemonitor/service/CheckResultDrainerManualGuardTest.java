package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.service.checks.CheckJobRow;
import com.sitemonitor.service.checks.CheckJobStore;
import com.sitemonitor.service.checks.CheckResultApplier;
import com.sitemonitor.service.checks.CheckResultDrainer;
import com.sitemonitor.service.checks.CheckResultRow;
import com.sitemonitor.service.checks.DbClock;
import com.sitemonitor.service.checks.DistributedChecks;
import com.sitemonitor.service.evalstate.EvalLoad;
import com.sitemonitor.service.evalstate.EvalStateReadSwitch;
import com.sitemonitor.service.evalstate.EvalStateSink;
import com.sitemonitor.service.monitoring.ManualEvalInbox;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-3 paket C — elle kontrol gelen kutusunun sıra kapısı ({@code SchedulerService.drainManualEvalInbox} →
 * {@link CheckResultDrainer#holdManual()}; tasarım R3-3 §4.4).
 *
 * <p>Tehlike (3-2'nin J-4'ü): temiz devirde TAM katman bir kurtarma sayacını taşır (1/2). Devirden ÖNCE ölçülmüş zamanlanmış
 * bir DOWN kuyrukta DONE bekler; devirden SONRA gelen elle sağlıklı sonuç ondan önce değerlendirilirse sayaç 2/2 olur ve alarm
 * bir sağlıklı tur ERKEN kapanır. Kapı: GAINED'den sonra boşaltıcı birikimi boşaltana dek (en çok 20 sn) elle kutu bekler.
 * Gerçek iki pod (H2, ortak saat), gerçek MOS + 3-2 yükleme kapısı; iş kuyruğu ve uygulayıcı sahte (paket A / B).
 */
class CheckResultDrainerManualGuardTest {

    static final String URL = "https://a.example.com";
    static final String TYPE = EscalationService.TYPE_HTTP_DOWN;

    MultiPodTestSupport mp;
    AlertEventRepository alerts;
    EscalationService escalation;
    HttpMonitor x;
    MonitoringMultiPodKit.PodWithMos a;
    MonitoringMultiPodKit.PodWithMos b;
    volatile boolean switchOn = true;
    final QueueStore store = new QueueStore();
    final List<String> applied = new CopyOnWriteArrayList<>();
    CheckResultDrainer drainer;

    /** Sahte kuyruk: DONE satırları sırayla; yalnız {@code take} kullanılır. */
    static final class QueueStore implements CheckJobStore {
        final List<CheckResultRow> done = new ArrayList<>();
        final AtomicInteger takes = new AtomicInteger();

        @Override
        public synchronized List<CheckResultRow> take(int limit, String leaseOwner) {
            takes.incrementAndGet();
            List<CheckResultRow> out = new ArrayList<>(done.subList(0, Math.min(limit, done.size())));
            done.removeAll(out);
            return out;
        }

        @Override
        public int dispatch(String type, Collection<Long> ids, long leaseMs, String dispatcher) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CheckJobRow> claim(Collection<String> types, int limit, String owner, long createdBeforeMs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean[] complete(List<Completion> completions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int release(String owner, Collection<Long> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CheckJobRow> reap(int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public QueueStats stats() {
            throw new UnsupportedOperationException();
        }
    }

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        mp.jdbc().execute(ManualEvalInbox.DDL);
        alerts = mp.mock(AlertEventRepository.class);
        escalation = mp.mock(EscalationService.class);
        x = new HttpMonitor();
        x.setId(1L);
        x.setName("HTTP 1");
        x.setUrl(URL);
        x.setTeamId(7L);
        x.setActive(true);
        x.setRecoveryChecks(2);
        x.setRecoveryIntervalSeconds(null);   // pasif kurtarma: karar sayaçta
        when(mp.mock(HttpMonitorRepository.class).findById(1L)).thenReturn(Optional.of(x));
        // 3-2 okuma ayarı AÇIK: GAINED kapıyı kurar, takipçi her elle sonucu iletir
        org.mockito.Mockito.doReturn(true).when(mp.mock(AppSettingsService.class)).getBoolean(EvalStateReadSwitch.KEY, false);

        a = MonitoringMultiPodKit.podWithMos(mp, 1);
        b = MonitoringMultiPodKit.podWithMos(mp, 2);
        a.mos().setEvalReadEnabled(() -> true);
        a.mos().setEvalStateSink(mock(EvalStateSink.class));   // engel yok → GAINED kapıyı kurar
        ReflectionTestUtils.setField(a.scheduler(), "manualEvalInbox", inbox());
        ReflectionTestUtils.setField(b.scheduler(), "manualEvalInbox", inbox());

        AppSettingsService dcSettings = mock(AppSettingsService.class);
        when(dcSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i ->
                DistributedChecks.KEY_ENABLED.equals(i.getArgument(0)) ? switchOn : i.getArgument(1));
        when(dcSettings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        DistributedChecks dc = new DistributedChecks(dcSettings, null);
        CheckResultApplier applier = wave -> {
            for (CheckResultRow r : wave) {
                applied.add(r.type() + ":" + r.monitorId());
                // B'nin uygulayıcısı: zamanlanmış DOWN sonucu klasik kalemle MOS'a (devirden ÖNCE ölçülmüş)
                a.mos().handleSweepResults(TYPE, List.of(scheduled(1L, false)));
            }
        };
        DbClock clock = new DbClock() {
            @Override
            public long nowMs() {
                return mp.now();
            }

            @Override
            public boolean stale() {
                return false;
            }
        };
        drainer = new CheckResultDrainer(dc, a::scheduler, a::mos, () -> store, () -> applier, a.pod()::schedule,
                () -> clock);
        drainer.setLeaderClock(mp.clockSupplier());
        a.pod().events().listen(SweepLeadershipChangedEvent.class, drainer::onSweepLeadershipChanged);   // MOS'tan SONRA
        ReflectionTestUtils.setField(drainer, "started", true);   // iş parçacığı yerine adımlar elle

        assertThat(a.scheduler().isSweepLeaderNow()).as("A lider (GAINED)").isTrue();
        assertThat(b.scheduler().isSweepLeaderNow()).isFalse();
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

    static Map<String, Object> raw(boolean ok) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", ok);
        r.put("http_status", ok ? 200 : 503);
        r.put("response_ms", 120L);
        if (!ok) r.put("error", "HTTP 503");
        return r;
    }

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

    /**
     * Devir: A TAM katmanla sayacı 1/2 taşıdı (önceki liderin son sayılmış sağlıklı sonucu 60 sn önce); kuyrukta devirden önce
     * ölçülmüş zamanlanmış DOWN (DONE) bekliyor; takipçi B'deki "Şimdi kontrol et" UP gelen kutusunda.
     */
    void handoverWithPendingScheduledDownAndManualUp() {
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

        long token = a.mos().evalLoadToken();
        assertThat(token).as("GAINED kapıyı kurdu").isPositive();
        boolean loaded = a.mos().applyEvalLoad(new EvalLoad(token, EvalLoad.TIER_FULL,
                Map.of(TYPE + ":" + URL, new EvalLoad.Recovery(1, mp.now() - 60_000L)), Map.of(), Set.of(), Set.of(),
                Map.of(), Map.of(), null));
        assertThat(loaded).as("TAM katman uygulandı (sayaç 1/2 taşındı)").isTrue();
        assertThat(a.mos().evalLoadToken()).isZero();

        store.done.add(new CheckResultRow(5L, "http", 1L, mp.now() - 4_000L, mp.now() - 3_900L, mp.now() - 3_000L,
                "pod-b", "{\"ok\":false}", false));
        mp.advance(1_000L);
        b.scheduler().evaluateHttpNow(x, raw(true));   // takipçi: okuma ayarı açık → her zaman iletir
        assertThat(inboxRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("tehlike (kapı yok): elle UP, bekleyen zamanlanmış DOWN'dan ÖNCE değerlendirilir → taşınan 1/2 sayaç 2/2 → alarm ERKEN kapanır")
    void withoutGuard_manualFirst_closesEarly() {
        handoverWithPendingScheduledDownAndManualUp();
        // checkResultDrainer alanı null = 20.169.0 / bean yok
        a.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);
    }

    @Test
    @DisplayName("kapı: GAINED sonrası elle kutu bekler, boşaltıcı zamanlanmış DOWN'u önce uygular (sayaç sıfır) → elle UP 1/2 → kapanmaz")
    void guard_scheduledBacklogFirst_noEarlyClose() {
        ReflectionTestUtils.setField(a.scheduler(), "checkResultDrainer", drainer);
        handoverWithPendingScheduledDownAndManualUp();

        a.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).as("birikim boşalmadı → elle kutu bu turda boşaltılmadı").isEqualTo(1);
        assertThat(mp.lease(SchedulerService.EVAL_INBOX_DRAINER).owner()).as("ilan yine yazıldı")
                .isEqualTo(MultiPodTestSupport.owner(1));

        drainer.drainOnce();   // DONE DOWN uygulanır, alma < 100 → yetişti
        assertThat(applied).containsExactly("http:1");
        assertThat(drainer.holdManual()).isFalse();

        a.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
        verify(escalation, never()).resolveMonitoringAlertsForDomain(anyString(), anyString());
    }

    @Test
    @DisplayName("kapı tavanı: boşaltıcı 20 sn içinde yetişemezse elle kutu yine boşaltılır (sonsuz bekleme yok)")
    void guard_capReleasesTheManualDrain() {
        ReflectionTestUtils.setField(a.scheduler(), "checkResultDrainer", drainer);
        handoverWithPendingScheduledDownAndManualUp();
        a.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isEqualTo(1);
        mp.advance(CheckResultDrainer.HOLD_MANUAL_MAX_MS);
        a.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
    }

    @Test
    @DisplayName("K-1: bean var ama dağıtık kontroller açılıştan beri KAPALI → kapı tek bellek okuması, elle boşaltma bugünkü gibi; kuyruk hiç okunmaz")
    void switchOff_guardChangesNothing() {
        switchOn = false;
        ReflectionTestUtils.setField(drainer, "started", false);   // anahtar hiç açık görülmedi → iş parçacığı yok
        ReflectionTestUtils.setField(a.scheduler(), "checkResultDrainer", drainer);
        handoverWithPendingScheduledDownAndManualUp();
        assertThat(drainer.holdManual()).isFalse();
        a.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
        verify(escalation, times(1)).resolveMonitoringAlertsForDomain(URL, TYPE);   // = kapı yok (20.169.0)
        assertThat(drainer.drainOnce()).isFalse();
        assertThat(store.takes.get()).as("kuyruğa hiç gidilmedi").isZero();
        assertThat(drainer.status().manualHolds()).isZero();
    }

    @Test
    @DisplayName("anahtar açıkken sonradan kapatılırsa (iş parçacığı kurulu) kapı bekletmez — kuyruk okunmuyor, elle kutu bugünkü gibi")
    void switchTurnedOff_guardDoesNotHold() {
        ReflectionTestUtils.setField(a.scheduler(), "checkResultDrainer", drainer);
        handoverWithPendingScheduledDownAndManualUp();
        switchOn = false;
        a.scheduler().drainManualEvalInbox();
        assertThat(inboxRows()).isZero();
        assertThat(store.takes.get()).isZero();
    }
}
