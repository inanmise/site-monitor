package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.service.checks.AnchoredDbClock;
import com.sitemonitor.service.checks.CheckJobSchemaPatch;
import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.checks.CheckResultDrainer;
import com.sitemonitor.service.checks.CheckWorker;
import com.sitemonitor.service.checks.CheckWorkerRegistryImpl;
import com.sitemonitor.service.checks.DistributedChecks;
import com.sitemonitor.service.checks.JdbcCheckJobStore;
import com.sitemonitor.service.checks.SchedulerCheckJobs;
import com.sitemonitor.service.evalstate.SqlRecordingDataSource;
import com.sitemonitor.service.monitoring.ManualEvalInbox;
import com.sitemonitor.service.schedule.ClusterLockService;
import com.sitemonitor.service.schedule.ClusterScheduleService;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-3 — K-1 İFADE DÖKÜMÜ (tasarım R3-3 §9.1, paket D2): ana anahtar KAPALI ve 3-3'ün TÜM bean'leri kurulu
 * ({@link DistributedChecks}, {@link JdbcCheckJobStore}, {@link CheckWorkerRegistryImpl}, {@link CheckWorker},
 * {@link CheckResultDrainer}, {@link SchedulerCheckJobs}, gönderim tıkının zamanlayıcısı, SS'in boşaltıcı alanı) iken her tarama
 * girişi, kilitli gövde, gönderim tıkı, elle gelen kutusu boşaltması, tembel başlatıcılar, ayar / liderlik / kapanış olayları
 * 3-3 bean'leri HİÇ olmayan bir kurulumla birebir AYNI çağrıları yapar.
 *
 * <ul>
 *   <li><b>A — sekiz tür, bağımlılık dökümü</b> ({@link R33SweepHarness}, altın kayıtların fikstürleri): her sahte bağımlılığın
 *       (MOS, Escalation, etkinlik, denetleyiciler, depolar — JPA ifadelerinin karşılığı —, {@code JdbcTemplate}) çağrı dizisi
 *       sıralı ve normalleştirilmiş olarak eşit; 3-3 parçalarının kendi veritabanında HİÇ ifade yok.</li>
 *   <li><b>B — gerçek veritabanı, SQL çoklu kümesi</b> (ortak H2 üstünde kayıtlı veri kaynağı: kira, iş kilitleri, vade CAS'ı,
 *       elle gelen kutusu, MOS kilitleri, 3-3 kuyruğu): koşan ifade metinlerinin çoklu kümesi eşit.</li>
 * </ul>
 * İzin verilen tek farklar dışarıda bırakılır: açılış şema yaması (iki boş tablo — iki kurulumda da önceden uygulanır) ve
 * {@code DistributedChecks}'in iki ayar anahtarını bellekten okuması (ayar sahtesine giden ek okumalar YALNIZ bu iki anahtar).
 * Denetim karşıtı: aynı senaryo anahtar AÇIKKEN 3-3 ifadelerini gerçekten üretir (test kör değil).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DistributedChecksOffSqlTranscriptTest {

    static final List<String> ENTRIES = List.of("runPortChecks", "runKeywordChecks", "runHttpChecks", "runPingChecks",
            "runDnsChecks", "runPageChecks", "runPageSpeedChecks", "runScriptedChecks");
    static final List<String> BODIES = List.of("runPortChecksLocked", "runKeywordChecksLocked", "runHttpChecksLocked",
            "runPingChecksLocked", "runDnsChecksLocked", "runPageChecksLocked", "runPageSpeedChecksLocked",
            "runScriptedChecksLocked");

    private static final Pattern MOCK_HASH = Pattern.compile("hashCode: -?\\d+");
    private static final Pattern IDENTITY = Pattern.compile("@[0-9a-f]{4,}");
    private static final Pattern LAMBDA = Pattern.compile("\\$\\$Lambda[^\"\\s,\\]}]*");

    // ── A: sekiz tür, sahte bağımlılık dökümü ──────────────────────────────────────────────────────────────────────

    /** 3-3 parçaları (anahtar KAPALI) — kendi veritabanında, kayıtlı veri kaynağıyla. */
    record Beans(MultiPodTestSupport mp, SqlRecordingDataSource ds, DistributedChecks dc, CheckWorker worker,
                 CheckWorkerRegistryImpl registry, CheckResultDrainer drainer) {
        void poke(SchedulerService s) {
            dc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(DistributedChecks.KEY_ENABLED), "save"));
            dc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));
            drainer.onSweepLeadershipChanged(new SweepLeadershipChangedEvent(true, "pod1-00000001", 0L,
                    SweepLeadershipChangedEvent.GAINED));
            assertThat(drainer.holdManual()).isFalse();
            assertThat(drainer.drainOnce()).isFalse();
            assertThat(registry.liveOtherWorkers(CheckJobTypes.PORT)).isEqualTo(-1);
            assertThat(worker.status().started()).isFalse();
            s.distributedCheckStatus();
        }

        void close() {
            worker.onContextClosed(null);
            registry.onContextClosed(null);
            drainer.onContextClosed(null);
            worker.destroy();
            drainer.destroy();
        }
    }

    static Beans bind(R33SweepHarness h) {
        DistributedChecks dc = h.bindDistributed(false, null);
        MultiPodTestSupport mp = MultiPodTestSupport.create();
        new CheckJobSchemaPatch(mp.jdbc()).apply();
        SqlRecordingDataSource ds = new SqlRecordingDataSource(mp.dataSource());
        JdbcTemplate rec = new JdbcTemplate(ds);
        AnchoredDbClock clock = new AnchoredDbClock(rec);
        JdbcCheckJobStore store = new JdbcCheckJobStore(rec, clock);
        SchedulerService s = h.scheduler;
        CheckWorkerRegistryImpl registry = new CheckWorkerRegistryImpl(rec, clock, dc, "pod1-00000001", s::sweepLeaderHint,
                () -> true, () -> true, () -> true, () -> true, () -> true, () -> CheckWorkerRegistryImpl.LocalStats.IDLE,
                () -> "v", System::nanoTime);
        s.setCheckJobInfrastructure(store, registry);
        SchedulerCheckJobs adapter = new SchedulerCheckJobs(R33ClusterRig.provider(SchedulerService.class, s));
        CheckWorker worker = new CheckWorker(store, registry, dc, () -> adapter, s::sweepLeaderHint, s::sideJobsStopping,
                () -> 0, clock, "pod1-00000001", System::nanoTime);
        CheckResultDrainer drainer = new CheckResultDrainer(dc, () -> s, () -> h.outage, () -> store, () -> adapter, () -> null,
                () -> clock);
        registry.register();
        worker.register();
        drainer.registerStarter();
        ReflectionTestUtils.setField(s, "checkResultDrainer", drainer);
        dc.onReady(null);
        return new Beans(mp, ds, dc, worker, registry, drainer);
    }

    /** Harness'in sahte alanları (ayar sahtesi hariç): ad → sahte. */
    static Map<String, Object> mocks(R33SweepHarness h) {
        Map<String, Object> out = new TreeMap<>();
        for (Field f : R33SweepHarness.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            Object v;
            try {
                v = f.get(h);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            if (v == null || !Mockito.mockingDetails(v).isMock() || "appSettings".equals(f.getName())) continue;
            out.put(f.getName(), v);
        }
        return out;
    }

    static String canon(Object arg) {
        String s = ClassicSweepGoldenTest.Json.write(ClassicSweepGoldenTest.norm(arg, 0));
        s = MOCK_HASH.matcher(s).replaceAll("hashCode: #");
        s = LAMBDA.matcher(s).replaceAll("λ");
        return IDENTITY.matcher(s).replaceAll("@#");
    }

    /** Sıralı döküm: {@code alan.yöntem(arg, …)} — Mockito'nun genel sıra numarasıyla. */
    static List<String> transcript(Map<String, Object> mocks) {
        List<Invocation> all = new ArrayList<>();
        Map<Invocation, String> owner = new HashMap<>();
        for (Map.Entry<String, Object> e : mocks.entrySet()) {
            for (Invocation inv : Mockito.mockingDetails(e.getValue()).getInvocations()) {
                all.add(inv);
                owner.put(inv, e.getKey());
            }
        }
        all.sort(Comparator.comparingInt(Invocation::getSequenceNumber));
        List<String> out = new ArrayList<>();
        for (Invocation inv : all) {
            List<String> args = new ArrayList<>();
            for (Object a : inv.getArguments()) args.add(canon(a));
            out.add(owner.get(inv) + "." + inv.getMethod().getName() + "(" + String.join(", ", args) + ")");
        }
        return out;
    }

    static List<String> settingsReads(R33SweepHarness h) {
        List<String> out = new ArrayList<>();
        for (Invocation inv : Mockito.mockingDetails(h.appSettings).getInvocations()) {
            Object[] a = inv.getArguments();
            out.add(inv.getMethod().getName() + "(" + (a.length > 0 ? a[0] : "") + ")");
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    record HarnessRun(List<String> transcript, List<String> settingsReads, List<String> threeThreeSql) {}

    static HarnessRun harnessRun(boolean withBeans) throws Exception {
        R33SweepHarness h = new R33SweepHarness();
        h.portFixture();
        h.keywordFixture();
        h.httpFixture();
        h.pingFixture();
        h.dnsFixture();
        h.pageFixture();
        h.pageSpeedFixture();
        h.scriptedFixture();
        when(h.scriptedChecker.ensureProbed()).thenReturn(true);
        Beans beans = withBeans ? bind(h) : null;
        Map<String, Object> mocks = mocks(h);
        Mockito.clearInvocations(mocks.values().toArray());
        Mockito.clearInvocations(h.appSettings);
        try {
            for (int round = 0; round < 3; round++) {
                for (String entry : ENTRIES) ReflectionTestUtils.invokeMethod(h.scheduler, entry);
                for (String body : BODIES) ReflectionTestUtils.invokeMethod(h.scheduler, body);
                h.scheduler.dispatchCheckJobs();
                h.scheduler.drainManualEvalInbox();
                if (beans != null) beans.poke(h.scheduler);
                h.clock.addAndGet(300_000L);   // her türün aralığı dolsun (DNS 300 sn)
            }
            if (beans != null) beans.close();
            return new HarnessRun(transcript(mocks), settingsReads(h),
                    beans == null ? List.of() : beans.ds().statements());
        } finally {
            if (beans != null) beans.mp().close();
        }
    }

    @Test
    @DisplayName("K-1 A: anahtar KAPALI + tüm 3-3 bean'leri — sekiz türün girişleri, kilitli gövdeler, gönderim tıkı, elle boşaltma "
            + "ve 3-3 olayları 3-3'süz kurulumla birebir AYNI bağımlılık çağrıları; 3-3 kuyruğunda sıfır ifade")
    void eightTypes_offWithBeans_identicalToNoBeans() throws Exception {
        HarnessRun bare = harnessRun(false);
        HarnessRun off = harnessRun(true);
        assertThat(bare.transcript()).as("döküm boş değil (test kör değil)").hasSizeGreaterThan(200);
        assertThat(String.join("\n", bare.transcript())).contains("outage.handleSweepResults", "outage.handleDnsSweep",
                "portCheckRepo.save", "jdbc.");
        assertThat(off.transcript()).as("bağımlılık çağrıları (MOS, Escalation, denetleyiciler, depolar, JdbcTemplate) birebir")
                .containsExactlyElementsOf(bare.transcript());
        assertThat(off.threeThreeSql()).as("anahtar kapalı: 3-3 kuyruğu / işçi kaydı / DB saati hiçbir ifade koşmadı").isEmpty();
        List<String> extra = new ArrayList<>(off.settingsReads());
        for (String r : bare.settingsReads()) extra.remove(r);
        assertThat(extra).as("ek ayar okumaları YALNIZ iki 3-3 anahtarı (bellek)").isNotEmpty()
                .allMatch(r -> r.endsWith("(" + DistributedChecks.KEY_ENABLED + ")")
                        || r.endsWith("(" + DistributedChecks.KEY_TYPES + ")"));
        List<String> missing = new ArrayList<>(bare.settingsReads());
        for (String r : off.settingsReads()) missing.remove(r);
        assertThat(missing).as("3-3'süz kurulumun her ayar okuması kapalı kurulumda da var").isEmpty();
    }

    // ── B: gerçek veritabanı, SQL çoklu kümesi ─────────────────────────────────────────────────────────────────────

    /** Bir pod: SS + MOS + (isteğe bağlı) 3-3 parçaları; SS / MOS / kira / kilit / gelen kutusu kayıtlı veri kaynağından. */
    static List<String> realDbRun(boolean withBeans, boolean switchOn) {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            mp.jdbc().execute(ManualEvalInbox.DDL);
            R33ClusterRig rig = new R33ClusterRig(mp, 60_000L);   // şema yaması iki kurulumda da önceden (izinli fark)
            rig.switchOn.set(switchOn);
            rig.types.set("port,ping");
            for (long id = 1; id <= 6; id++) {
                PortMonitor m = R33ClusterRig.port(id, 60);
                m.setConfirmAttempts(0);
                rig.ports.add(m);
                rig.pings.add(R33ClusterRig.ping(id, 60));
            }
            rig.oracle = (pod, type, id, now) -> !(id == 3 && type.equals(CheckJobTypes.PORT));
            SqlRecordingDataSource ds = new SqlRecordingDataSource(mp.dataSource());
            JdbcTemplate rec = new JdbcTemplate(ds);
            R33ClusterRig.Opts o = withBeans ? new R33ClusterRig.Opts().offBoot().jdbc(rec) : new R33ClusterRig.Opts().legacy();
            R33ClusterRig.Node n = rig.node(1, o);
            SchedulerService s = n.ss();
            ReflectionTestUtils.setField(s, "jdbcTemplate", rec);
            ReflectionTestUtils.setField(s, "clusterSchedule", new ClusterScheduleService(rec, n.owner()));
            ReflectionTestUtils.setField(s, "lockService", new ClusterLockService(rec, n.owner(), mp.clockSupplier(), false));
            ManualEvalInbox inbox = new ManualEvalInbox(rec);
            inbox.setClock(mp.clockSupplier());
            ReflectionTestUtils.setField(s, "manualEvalInbox", inbox);
            ReflectionTestUtils.setField(n.mos(), "jdbcTemplate", rec);
            ManualEvalInbox writer = new ManualEvalInbox(mp.jdbc());   // takipçinin iletimi (kayda girmez)
            writer.setClock(mp.clockSupplier());

            assertThat(s.isSweepLeaderNow()).isTrue();
            for (int round = 0; round < 3; round++) {
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("open", true);
                raw.put("response_ms", 15L);
                writer.enqueue(ManualEvalInbox.TYPE_PORT, 3L, true, raw, "pod2-00000002");
                s.runPortChecks();
                s.runPingChecks();
                s.dispatchCheckJobs();
                s.drainManualEvalInbox();
                if (withBeans) {
                    n.dc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(DistributedChecks.KEY_ENABLED), "save"));
                    n.drainer.holdManual();
                    n.drainer.drainOnce();
                }
                mp.advance(60_000L);
            }
            s.onContextClosed(null);
            if (withBeans) rig.stop(n);
            List<String> out = new ArrayList<>(ds.statements());
            out.sort(Comparator.naturalOrder());
            return out;
        }
    }

    @Test
    @DisplayName("K-1 B: gerçek veritabanı — anahtar KAPALI + 3-3 bean'leri (tembel başlatıcılar kayıtlı) ile 3-3'süz kurulum AYNI "
            + "SQL çoklu kümesini koşar (kira, kilitler, vade CAS'ı, elle gelen kutusu, MOS kilidi, kapanış)")
    void realDatabase_offWithBeans_identicalStatementMultiset() {
        List<String> bare = realDbRun(false, false);
        List<String> off = realDbRun(true, false);
        assertThat(bare).as("ifade koştu (test kör değil)").isNotEmpty()
                .anyMatch(q -> q.contains("monitor_check_schedule")).anyMatch(q -> q.contains("monitor_eval_inbox"));
        assertThat(off).as("SQL çoklu kümesi birebir").containsExactlyElementsOf(bare);
        assertThat(off).noneMatch(q -> q.contains("monitor_check_jobs") || q.contains("monitor_check_workers")
                || q.contains("CURRENT_TIMESTAMP"));
    }

    @Test
    @DisplayName("Denetim karşıtı: aynı gerçek veritabanı senaryosu anahtar AÇIKKEN 3-3 kuyruğu / işçi kaydı ifadelerini üretir")
    void realDatabase_switchOn_producesThreeThreeStatements() {
        List<String> on = realDbRun(true, true);
        assertThat(on).anyMatch(q -> q.contains("monitor_check_workers"));
    }
}
