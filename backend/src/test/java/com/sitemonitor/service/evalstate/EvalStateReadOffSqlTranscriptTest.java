package com.sitemonitor.service.evalstate;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.MonitoringOutageService.SweepItem;
import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-2 A — J-1 SQL DÖKÜMÜ: okuma ayarı KAPALIYKEN kaydedici + doğrulayıcı, gerçek MOS'larla bir kira devri
 * (A → B), boşaltmalar, doğrulama turları, boşta nabız, kapanış yüzü çağrıları boyunca YALNIZ 3-1 ifadelerini koşar —
 * {@code SQL_GEN_PRIOR}, işaretli nabız, devir işareti, kendi zincirini bırakma, ara lider sorgusu, işaret sondası ve yükleyici
 * okumaları hiç görünmez; {@code eval-state-load} gözlemi yayımlanmaz; kaynağın oynatma / uygulama yüzü çağrılmaz. 3-1 ifade
 * metinleri aşağıda v20.168.1'den AYNEN kopyalanmıştır (kâhin) — 3-1 ifadesini değiştiren bir değişiklik bu testi düşürür.
 * Denetim karşıtı: aynı senaryo ayar AÇIKKEN yasaklı ifadeleri gerçekten üretir (test körlüğü yok).
 */
class EvalStateReadOffSqlTranscriptTest {

    static final String PORT = EscalationService.TYPE_PORT_DOWN;
    static final String DB = "db.example.com";
    static final String A = "pod-a-0000000a";
    static final String B = "pod-b-0000000b";

    /** v20.168.1 (65f508e9) kaydedici / SQL katmanı ifadeleri — AYNEN. */
    static final List<String> R31_LITERALS = List.of(
            "SELECT gen FROM monitor_eval_type_state WHERE alert_type = ?",
            "INSERT INTO monitor_eval_type_state (alert_type, suppression_active, suppression_since_ms, "
                    + "last_scheduled_round_ms, round_interval_ms, writer, gen, seq, updated_ms, updated_at) "
                    + "VALUES (?, NULL, NULL, NULL, NULL, ?, ?, 0, ?, ?) ON CONFLICT DO NOTHING",
            "UPDATE monitor_eval_type_state SET gen = ?, writer = ?, seq = 0, updated_ms = ?, updated_at = ? "
                    + "WHERE alert_type = ? AND gen = ?",
            "UPDATE monitor_eval_type_state SET gen = ?, writer = ?, seq = 0, updated_ms = ?, updated_at = ? "
                    + "WHERE alert_type = ? AND gen = ? AND EXISTS (SELECT 1 FROM monitor_check_schedule "
                    + "WHERE monitor_key = ? AND claimed_by = ?)",
            "UPDATE monitor_eval_type_state SET updated_ms = ?, updated_at = ? WHERE alert_type = ? AND gen = ? AND writer = ?",
            "SELECT gen, writer, updated_ms FROM monitor_eval_type_state WHERE alert_type = ?",
            "DELETE FROM monitor_confirmations WHERE key_hash = ? AND chain_id = ? AND writer = ? AND gen <= ?",
            "UPDATE monitor_confirmations SET state = 'ABANDONED', updated_ms = ?, updated_at = ? WHERE gen < ? "
                    + "AND state <> 'ABANDONED'",
            "DELETE FROM monitor_last_status WHERE alert_type = ? AND item_key = ? AND (gen < ? OR (gen = ? AND seq < ?))",
            "DELETE FROM monitor_last_status WHERE observed_ms < ?",
            "DELETE FROM monitor_eval_state WHERE updated_ms < ? AND ((recovery_up_count = 0 AND recovery_chain_gen IS NULL) "
                    + "OR gen < ?)",
            "DELETE FROM monitor_confirmations WHERE state = 'ABANDONED' OR updated_ms < ?",
            "DELETE FROM monitor_eval_type_state WHERE alert_type <> ? AND gen < ? AND updated_ms < ?",
            "SELECT COUNT(*) FROM monitor_eval_state WHERE gen = ? AND recovery_up_count > 0",
            "SELECT COUNT(*) FROM monitor_last_status WHERE gen = ? AND up = FALSE AND monitor_id IS NOT NULL "
                    + "AND observed_ms >= ?",
            "SELECT COUNT(*) FROM monitor_eval_type_state WHERE gen = ? AND alert_type <> ? AND suppression_active = TRUE",
            "SELECT MAX(gen) FROM monitor_eval_state",
            "SELECT MAX(gen) FROM monitor_last_status",
            "SELECT MAX(gen) FROM monitor_eval_type_state WHERE alert_type <> ?",
            "SELECT MAX(gen) FROM monitor_confirmations",
            "SELECT 1 FROM monitor_eval_state WHERE 1 = 0",
            "SELECT 1 FROM monitor_last_status WHERE 1 = 0",
            "SELECT 1 FROM monitor_eval_type_state WHERE 1 = 0",
            "SELECT 1 FROM monitor_confirmations WHERE 1 = 0",
            // doğrulayıcının okumaları
            "SELECT alert_type, domain, recovery_up_count, recovery_last_counted_ms, recovery_chain_gen FROM "
                    + "monitor_eval_state WHERE gen = ? AND writer = ?",
            "SELECT alert_type, suppression_active, last_scheduled_round_ms FROM monitor_eval_type_state "
                    + "WHERE gen = ? AND writer = ? AND alert_type <> ?",
            "SELECT alert_type, item_key, domain, monitor_id, team_id, standalone, up, network_down, observed_ms, gen, "
                    + "writer FROM monitor_last_status WHERE (gen = ? AND writer = ?) OR observed_ms >= ?",
            "SELECT confirm_key, chain_id, alert_type, domain, detail, monitor_type, monitor_id, attempt, state FROM "
                    + "monitor_confirmations WHERE gen = ? AND writer = ?",
            // doğrulama raporunun yayını (eval-state-verify)
            "UPDATE cluster_observations SET instance_id = ?, observed_ms = ?, payload = ?, updated_at = ? "
                    + "WHERE name = ? AND observed_ms < ?",
            "INSERT INTO cluster_observations(name, instance_id, observed_ms, payload, updated_at) VALUES (?, ?, ?, ?, ?)");

    MultiPodTestSupport mp;
    SqlRecordingDataSource recording;
    JdbcTemplate rec;
    final List<EvalStateRecorder> built = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        new EvalStateSchemaPatch(mp.jdbc()).apply();
        mp.jdbc().execute(ClusterHealthStore.OBS_DDL);
        recording = new SqlRecordingDataSource(mp.dataSource());
        rec = new JdbcTemplate(recording);
    }

    @AfterEach
    void tearDown() {
        built.forEach(EvalStateRecorder::stop);
        mp.close();
    }

    static Set<String> allowed31() {
        Set<String> s = new LinkedHashSet<>(R31_LITERALS);
        for (EvalStateSql.Spec spec : List.of(EvalStateSql.SPEC_EVAL_STATE, EvalStateSql.SPEC_LAST_STATUS,
                EvalStateSql.SPEC_TYPE_STATE, EvalStateSql.SPEC_CONFIRMATIONS)) {
            s.add(spec.update);
            s.add(spec.insert);
            s.add(spec.pgUpsert);
        }
        return s;
    }

    static List<String> forbidden32() {
        return List.of(EvalStateSql.SQL_GEN_PRIOR, EvalStateSql.SQL_HEARTBEAT_MARK, EvalStateSql.SQL_HANDOVER_MARK,
                EvalStateSql.SQL_ABANDON_OWN, EvalStateSql.SQL_INTERMEDIATE, EvalStateSql.SQL_MARKER_PROBE,
                EvalStateSql.SQL_LOAD_RECOVERY, EvalStateSql.SQL_LOAD_SIBLINGS, EvalStateSql.SQL_LOAD_TYPES,
                EvalStateSql.SQL_LOAD_WINDOW);
    }

    /** Gerçek MOS (doğrulayıcı testindeki kurulum); teyit görevleri bekletilir. */
    MonitoringOutageService mos() {
        AlertEventRepository alertRepo = mock(AlertEventRepository.class);
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        MonitoringOutageService m = new MonitoringOutageService(alertRepo, mock(EscalationService.class),
                mock(JdbcTemplate.class), mock(DnsRecordRepository.class), mock(DnsMonitorRepository.class), settings,
                mock(NetworkOutageEventRepository.class));
        for (String f : List.of("uptimeAlertEnabled", "portAlertEnabled", "dnsAlertEnabled", "keywordAlertEnabled",
                "pingAlertEnabled")) {
            ReflectionTestUtils.setField(m, f, true);
        }
        ReflectionTestUtils.setField(m, "confirmAttempts", 3);
        ReflectionTestUtils.setField(m, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(m, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(m, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(m, "confirmExecutor", new ScheduledThreadPoolExecutor(1) {
            @Override
            public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                return null;   // zincir "sürüyor" kalsın
            }
        });
        ReflectionTestUtils.setField(m, "nowMs", mp.clockSupplier());
        ReflectionTestUtils.setField(m, "suppressionClock", mp.clockSupplier());
        AlertEvent open = new AlertEvent();
        open.setId(42L);
        open.setDomain(DB);
        open.setAlertType(PORT);
        open.setAlertLevel("WARNING");
        open.setAcknowledged(false);
        open.setResolved(false);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(open));
        return m;
    }

    /** MOS'a delege eden kaynak — yükleme yüzü çağrılarını sayar, son raporu HER ZAMAN dolu döner (yayın kapısı sınansın). */
    static final class Watching implements EvalStateSource {
        final MonitoringOutageService mos;
        final AtomicInteger replays = new AtomicInteger();
        final AtomicInteger applies = new AtomicInteger();
        final AtomicInteger skips = new AtomicInteger();
        final long reportAt;

        Watching(MonitoringOutageService mos, long reportAt) {
            this.mos = mos;
            this.reportAt = reportAt;
        }

        @Override public RecoveryValues recoveryValues(String rkey) { return mos.recoveryValues(rkey); }
        @Override public TypeValues typeValues(String alertType) { return mos.typeValues(alertType); }
        @Override public String errorClass(String error, String domain) { return mos.errorClass(error, domain); }
        @Override public DownValues downObservation(String alertType, long monitorId) {
            return mos.downObservation(alertType, monitorId);
        }
        @Override public long hookFailures() { return mos.hookFailures(); }
        @Override public long evalLoadToken() { return mos.evalLoadToken(); }
        @Override public boolean applyEvalLoad(EvalLoad load) { applies.incrementAndGet(); return false; }
        @Override public void evalLoadSkipped(long token, EvalLoadReport report) { skips.incrementAndGet(); }
        @Override public EvalLoadReport lastEvalLoad() {
            return new EvalLoadReport(reportAt, "x", EvalLoadReport.OUTCOME_FULL, null, "full", 1L, "y", 1L, 1L,
                    1, 1, 1, 1, 1, 1, 1L, 0L, 0);
        }
        @Override public int replay() { replays.incrementAndGet(); return mos.replayEvalState(); }
    }

    record P(String owner, MonitoringOutageService mos, EvalStateRecorder rec, Watching src, AtomicBoolean leader) {
        void event(String reason) {
            SweepLeadershipChangedEvent e = new SweepLeadershipChangedEvent(SweepLeadershipChangedEvent.GAINED.equals(reason),
                    owner, 0L, reason);
            mos.onSweepLeadershipChanged(e);
            rec.onSweepLeadershipChanged(e);
        }
    }

    P pod(String owner, BooleanSupplier readOn) {
        MonitoringOutageService m = mos();
        EvalStateVerifier v = new EvalStateVerifier(m, rec);
        v.setClusterHealthStore(new ClusterHealthStore(rec));
        AtomicBoolean leader = new AtomicBoolean(false);
        EvalStateRecorder r = EvalStateRecorder.builder(rec).writer(owner).leaderHint(leader::get)
                .mode(() -> EvalStateMode.VERIFY).clock(mp.clockSupplier()).leaseOwner(owner).verifier(() -> v)
                .readEnabled(readOn).observationStore(new ClusterHealthStore(rec)).flushMs(50).verifyMs(300_000)
                .lastStatusRefreshMs(120_000).build();
        m.setEvalStateSink(r);
        Watching w = new Watching(m, mp.now() + 1_000_000L);
        r.bindSource(w);
        built.add(r);
        return new P(owner, m, r, w, leader);
    }

    void lease(String owner) {
        int n = mp.jdbc().update("UPDATE monitor_check_schedule SET claimed_by = ?, claimed_at = ? WHERE monitor_key = ?",
                owner, mp.now(), EvalStateSql.LEASE_KEY);
        if (n == 0) {
            mp.jdbc().update("INSERT INTO monitor_check_schedule(monitor_key, next_due_at, claimed_by, claimed_at) "
                    + "VALUES (?,?,?,?)", EvalStateSql.LEASE_KEY, mp.now() + 180_000, owner, mp.now());
        }
    }

    static SweepItem item(String domain, String detail, boolean up, long monitorId) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_id", monitorId);
        ctx.put("monitor_recovery_checks", 3);
        return new SweepItem(PORT, domain, detail, up, up ? null : "connect timed out", ctx,
                () -> Map.of("status", up ? "up" : "down"));
    }

    /** Lider turları: kurtarma sayacı, DOWN kardeş + süren zincir, tür bayrakları. */
    void rounds(MonitoringOutageService m) {
        m.handleSweepResults(PORT, List.of(item(DB, "5432/TCP", true, 7), item("other.example", "5433/TCP", false, 9)));
        mp.advance(60_000);
        m.handleSweepResults(PORT, List.of(item(DB, "5432/TCP", true, 7)));
    }

    /** Kapalı ayarla tam senaryo; dönüş: koşan ifadeler. */
    List<String> scenario(BooleanSupplier readOn) {
        P a = pod(A, readOn);
        P b = pod(B, readOn);
        lease(A);
        a.leader().set(true);
        a.event(SweepLeadershipChangedEvent.GAINED);
        rounds(a.mos());
        assertThat(a.rec().flushNow()).isTrue();
        a.rec().verifyNow();
        mp.advance(1_000);
        a.rec().flushNow();
        a.rec().verifyNow();
        // Kapanış yüzü (C bunları yalnız ayar açıkken çağırır; yine de SQL üretmemeli)
        a.rec().loadBlocker();
        boolean ready = a.rec().handoverReady();
        if (!readOn.getAsBoolean()) {
            assertThat(ready).isFalse();
            assertThat(a.rec().flushForHandover(EvalStateSink.HANDOVER_FLUSH_MS, true)).isEqualTo(HandoverResult.SKIPPED);
        } else {
            a.rec().openHandover();
            a.leader().set(false);
            a.rec().flushForHandover(EvalStateSink.HANDOVER_FLUSH_MS, true);
        }
        // Kira A → B
        mp.advance(2_000);
        lease(B);
        a.leader().set(false);
        a.event(SweepLeadershipChangedEvent.RELEASED);
        b.leader().set(true);
        b.event(SweepLeadershipChangedEvent.GAINED);
        rounds(b.mos());
        assertThat(b.rec().flushNow()).isTrue();
        b.rec().verifyNow();
        mp.advance(1_000);
        b.rec().flushNow();
        b.rec().verifyNow();
        assertThat(a.rec().flushNow()).as("A lider değil").isFalse();
        mp.advance(61_000);
        b.rec().tick();   // boşta nabız
        if (!readOn.getAsBoolean()) {
            assertThat(a.src().replays).hasValue(0);
            assertThat(b.src().replays).hasValue(0);
            assertThat(a.src().applies.get() + b.src().applies.get() + a.src().skips.get() + b.src().skips.get()).isZero();
        }
        return recording.statements();
    }

    @Test
    @DisplayName("J-1: ayar KAPALI — kira devri + boşaltmalar + doğrulama + boşta nabız boyunca YALNIZ 3-1 ifadeleri; eval-state-load yok")
    void settingOff_onlyR31Statements() {
        List<String> executed = scenario(() -> false);
        Set<String> allowed = allowed31();
        assertThat(executed).as("hiç ifade koşmadıysa test kördür").isNotEmpty();
        assertThat(new LinkedHashSet<>(executed)).as("3-1 dışı ifade").isSubsetOf(allowed);
        for (String f : forbidden32()) {
            assertThat(executed).as("3-2 ifadesi: " + f).noneMatch(q -> q.equals(f) || q.startsWith(f + " LIMIT"));
        }
        assertThat(executed).as("kiraya bağlı 3-1 CAS ve 3-1 nabzı gerçekten koştu")
                .contains(R31_LITERALS.get(3), R31_LITERALS.get(4));
        assertThat(mp.jdbc().queryForList("SELECT name FROM cluster_observations", String.class))
                .as("yalnız doğrulama gözlemi").containsOnly(EvalVerifyReport.OBSERVATION);
        assertThat(executed).as("ayar kapalıyken her ifade bir yazıcı / test iş parçacığında, devir yardımcısı yok")
                .isNotEmpty();
        assertThat(recording.executed).noneMatch(e -> e.thread().equals(EvalStateRecorder.HANDOVER_THREAD_NAME));
    }

    @Test
    @DisplayName("Denetim karşıtı: aynı senaryo ayar AÇIKKEN 3-2 ifadelerini üretir ve eval-state-load yayımlanır (test körlüğü yok)")
    void settingOn_producesR32Statements() {
        List<String> executed = scenario(() -> true);
        assertThat(executed).contains(EvalStateSql.SQL_GEN_PRIOR, EvalStateSql.SQL_HEARTBEAT_MARK,
                EvalStateSql.SQL_HANDOVER_MARK, EvalStateSql.SQL_ABANDON_OWN);
        assertThat(mp.jdbc().queryForList("SELECT name FROM cluster_observations", String.class))
                .contains(EvalLoadReport.OBSERVATION);
    }
}
