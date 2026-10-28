package com.sitemonitor.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.service.evalstate.ActiveConfirmationsService;
import com.sitemonitor.service.evalstate.EvalLoadGate;
import com.sitemonitor.service.evalstate.EvalLoadReport;
import com.sitemonitor.service.evalstate.EvalSnapshot;
import com.sitemonitor.service.evalstate.EvalStateMode;
import com.sitemonitor.service.evalstate.EvalStateRecorder;
import com.sitemonitor.service.evalstate.EvalStateSchemaPatch;
import com.sitemonitor.service.evalstate.EvalStateSource;
import com.sitemonitor.service.evalstate.EvalStateVerifier;
import com.sitemonitor.service.evalstate.EvalVerifyReport;
import com.sitemonitor.service.evalstate.HandoverResult;
import com.sitemonitor.service.evalstate.SqlRecordingDataSource;
import com.sitemonitor.service.health.ClusterHealthStore;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-2 — devirde kalıcı değerlendirme durumunu okuma, UÇTAN UCA (tasarım R3-2 §9.3, paket D).
 *
 * <p>Her pod: kendi {@link MonitoringOutageService}'i ({@link MonitoringMultiPodKit#podWithMos}), kendi GERÇEK
 * {@link EvalStateRecorder}'ı (kendi yazan kimliği, lider ipucu = pod'un {@code sweepLeaderHint}, ortak saat, kiraya bağlı
 * kuşak CAS'ı, okuma ayarı tedarikçisi, {@code eval-state-load} gözlem deposu), kendi zamanlayıcısının devir bağlantısı
 * ({@code setEvalStateSinkForHandover}); ortak H2 üzerinde GERÇEK {@link EvalStateSchemaPatch} (üç işaret sütunu dâhil).
 * Liderlik GERÇEK kirayla değişir: temiz devir = {@code SchedulerService.onContextClosed} (devir boşaltması → kira
 * bırakılır), çökme = kiranın süresi dolar. Düzenekte yazıcı iş parçacığı yok: GAINED'den hemen sonra {@code flushNow()}
 * yükü okur ve uygular (üretimde yazıcı turu); unutulursa ilk değerlendirme kapıda son tarihe dek bekler.
 *
 * <p><b>Ortak filo</b> (üç açık alarm, takım 7, bağımsız izlemeler):
 * <ul>
 *   <li>K1 {@code PORT_DOWN / db.example.com} — açan m1, 3 ardışık sağlıklı ister; A'da 2/3 sayıldı (sayaç).</li>
 *   <li>K2 {@code PORT_DOWN / web.example.com} — açan m10; kardeş m11 A'da DOWN gözlendi (DOWN kardeş).</li>
 *   <li>K3 {@code PING_DOWN / ping.example.com} — açan m20 (A'da 1/3); m21 açan DEĞİL, elle sağlıklı sonucu 2 ister
 *       (D-6: tür turu görülmeden elle kapanış ertelenir).</li>
 * </ul>
 * Halef dört adım koşar: b1 elle PING m21 · b2 zamanlanmış PORT [m10, m1] · b3, b4 zamanlanmış PORT [m1]. Beklenen
 * {@link EscalationService} tutanakları: TAM (sayaç + kardeş + D-6 rahatlaması taşındı), GÖZLEMLER (yalnız kardeş), YOK
 * (bugünkü yeniden başlatma semantiği). Her senaryo tutanağı karşılaştırır.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class EvalStateReadMultiPodTest {

    static final long TTL = MonitoringMultiPodKit.TTL;
    static final String PORT = EscalationService.TYPE_PORT_DOWN;
    static final String PING = EscalationService.TYPE_PING_DOWN;
    static final String DB = "db.example.com";
    static final String WEB = "web.example.com";
    static final String PINGH = "ping.example.com";
    static final long TEAM = 7L;
    /** FULL yükün kurtarma sayaçları okuması ({@code EvalStateSql.SQL_LOAD_RECOVERY}) — yükleyicinin İKİNCİ ifadesi. */
    static final String RECOVERY_READ = "recovery_up_count > 0";
    /** {@code ActiveConfirmationsService.MEMO_MS} (paket özel) — takipçi listesinin 2 sn ezberi. */
    static final long CONFIRM_MEMO_MS = 2_000L;

    static final List<String> A_PHASE = List.of("A1: processConfirmedOutage(web.example.com, PORT_DOWN)");
    /** TAM: K3 elle kapanır (D-6 rahat + sayaç 1+1), K1 b2'de kapanır (2+1), K2 kardeş yüzünden açık kalır. */
    static final List<String> FULL_B = List.of(
            "b1: resolveMonitoringAlertsForDomain(ping.example.com, PING_DOWN)",
            "b2: resolveMonitoringAlertsForDomain(db.example.com, PORT_DOWN)");
    /** GÖZLEMLER: K3 ertelenir (D-6), K2 kardeş yüzünden açık kalır, K1 halefin üçüncü sağlıklı turunda kapanır. */
    static final List<String> OBS_B = List.of("b4: resolveMonitoringAlertsForDomain(db.example.com, PORT_DOWN)");
    /** YOK (bugün): K3 ertelenir, K2 b2'de kapanır (kardeş bilinmiyor), K1 b4'te kapanır. */
    static final List<String> NONE_B = List.of(
            "b2: resolveMonitoringAlertsForDomain(web.example.com, PORT_DOWN)",
            "b4: resolveMonitoringAlertsForDomain(db.example.com, PORT_DOWN)");

    static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    // ── Düzenek ─────────────────────────────────────────────────────────────────────────────────────────────────────

    static AlertEvent alarm(long id, String type, String host, long openerMonitorId) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain(host);
        e.setAlertType(type);
        e.setAlertLevel("WARNING");
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setTeamId(TEAM);
        e.setContextJson("{\"monitor_id\":" + openerMonitorId + ",\"team_id\":7,\"standalone\":true}");
        return e;
    }

    static Map<String, Object> status(String s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", s);
        return m;
    }

    static MonitoringOutageService.SweepItem item(String type, String host, long monitorId, boolean up, int recoveryChecks) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("monitor_recovery_checks", recoveryChecks);
        ctx.put("monitor_id", monitorId);
        ctx.put("team_id", TEAM);
        ctx.put("standalone", true);
        return new MonitoringOutageService.SweepItem(type, host, monitorId + "/TCP", up, up ? null : "connect timed out", ctx,
                () -> status(up ? "up" : "down"));
    }

    /** Bir küme: ortak H2 + saat + paylaşılan sahteler; pod başına kendi MOS'u ve kaydedicisi. */
    static final class Cluster implements AutoCloseable {
        final MultiPodTestSupport mp = MultiPodTestSupport.create();
        final AlertEventRepository alertRepo = mp.mock(AlertEventRepository.class);
        final EscalationService escalation = mp.mock(EscalationService.class);
        /** Kümenin okuma ayarı (pod'lar varsayılan olarak bunu görür; karışık görüşler için pod'a özel bayrak). */
        final AtomicBoolean on = new AtomicBoolean(true);
        final List<EvalStateRecorder> recorders = new ArrayList<>();
        final List<String> transcript = new ArrayList<>();
        private int seenInvocations;

        Cluster() {
            new EvalStateSchemaPatch(mp.jdbc()).apply();
            mp.jdbc().execute(ClusterHealthStore.OBS_DDL);
            when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(
                    alarm(41, PORT, DB, 1), alarm(42, PORT, WEB, 10), alarm(43, PING, PINGH, 20)));
        }

        /** Pod kurulumu. */
        final class Spec {
            AtomicBoolean on = Cluster.this.on;
            boolean recorder = true;
            BooleanSupplier recorderRead;   // null = pod'un bayrağı
            long skewMs;
            boolean wrap;
            int maxPending = EvalStateRecorder.MAX_PENDING;
            boolean manualConfirms;

            Spec on(AtomicBoolean v) { this.on = v; return this; }
            Spec noRecorder() { this.recorder = false; return this; }
            Spec recorderRead(BooleanSupplier v) { this.recorderRead = v; return this; }
            Spec skewMs(long v) { this.skewMs = v; return this; }
            Spec wrap() { this.wrap = true; return this; }
            Spec maxPending(int v) { this.maxPending = v; return this; }
            Spec manualConfirms() { this.manualConfirms = true; return this; }

            Node build(int n) {
                MonitoringMultiPodKit.PodWithMos p = MonitoringMultiPodKit.podWithMos(mp, n);
                MonitoringOutageService m = p.mos();
                m.suppressionClock = mp.clockSupplier();
                AtomicBoolean flag = on;
                m.setEvalReadEnabled(flag::get);
                m.setEvalLoadPodId(p.pod().owner());
                MonitoringMultiPodKit.Manual manual = null;
                if (manualConfirms) {
                    manual = new MonitoringMultiPodKit.Manual();
                    ReflectionTestUtils.setField(m, "confirmExecutor", manual);
                }
                SqlRecordingDataSource ds = wrap ? new SqlRecordingDataSource(mp.dataSource()) : null;
                EvalStateRecorder rec = null;
                if (recorder) {
                    JdbcTemplate jdbc = ds == null ? mp.jdbc() : new JdbcTemplate(ds);
                    EvalStateVerifier verifier = new EvalStateVerifier(m, mp.jdbc());
                    long skew = skewMs;
                    BooleanSupplier read = recorderRead != null ? recorderRead : flag::get;
                    rec = EvalStateRecorder.builder(jdbc).writer(p.pod().owner())
                            .leaderHint(p.scheduler()::sweepLeaderHint).mode(() -> EvalStateMode.VERIFY)
                            .clock(() -> mp.now() + skew)
                            .leaseOwner(p.pod().schedule().owner())   // üretimdeki gibi: CAS kiraya bağlı
                            .verifier(() -> verifier).flushMs(50).verifyMs(300_000).lastStatusRefreshMs(120_000)
                            .maxPending(maxPending).readEnabled(read)
                            .observationStore(new ClusterHealthStore(jdbc)).build();
                    EvalStateRecorder r = rec;
                    // MOS dinleyicisi (kit) önce kayıtlı → önce sıfırlar + kapıyı kurar; kaydedici sonra temizler (üretim sırası).
                    p.pod().events().listen(SweepLeadershipChangedEvent.class, r::onSweepLeadershipChanged);
                    m.setEvalStateSink(rec);
                    p.scheduler().setEvalStateSinkForHandover(rec);
                    recorders.add(rec);
                }
                return new Node(p, rec, ds, manual);
            }
        }

        Spec spec() {
            return new Spec();
        }

        Node node(int n) {
            return spec().build(n);
        }

        /** Bir pod: kendi MOS'u, kaydedicisi (null olabilir), isteğe bağlı kayıtlı veri kaynağı ve elle teyit yürütücüsü. */
        final class Node {
            final MonitoringMultiPodKit.PodWithMos p;
            final EvalStateRecorder rec;
            final SqlRecordingDataSource ds;
            final MonitoringMultiPodKit.Manual confirms;

            Node(MonitoringMultiPodKit.PodWithMos p, EvalStateRecorder rec, SqlRecordingDataSource ds,
                 MonitoringMultiPodKit.Manual confirms) {
                this.p = p;
                this.rec = rec;
                this.ds = ds;
                this.confirms = confirms;
            }

            String owner() { return p.pod().owner(); }
            MonitoringOutageService mos() { return p.mos(); }
            SchedulerService scheduler() { return p.scheduler(); }
            MultiPodTestSupport.Pod pod() { return p.pod(); }

            /** GAINED (gerçek kira) + yazıcı turu: kuşak CAS'ı, süreklilik, okuma, uygulama, oynatma, boşaltma. */
            void gain() {
                assertThat(scheduler().isSweepLeaderNow()).as(owner() + " kirayı alır").isTrue();
                if (rec != null) rec.flushNow();
            }

            EvalLoadReport load() {
                return mos().lastEvalLoad();
            }

            int counter(String type, String host) {
                return mos().recoveryValues(type + ":" + host).upCount();
            }
        }

        // ── Tutanak ─────────────────────────────────────────────────────────────────────────────────────────────────

        /** {@link EscalationService} sahtesinin bu adımdan beri aldığı çağrılar: {@code "<adım>: yöntem(alan adı, tür)"}. */
        void step(String label) {
            List<Invocation> inv = new ArrayList<>(Mockito.mockingDetails(escalation).getInvocations());
            inv.sort(Comparator.comparingInt(Invocation::getSequenceNumber));
            for (int i = seenInvocations; i < inv.size(); i++) {
                Invocation x = inv.get(i);
                Object[] a = x.getArguments();
                transcript.add(label + ": " + x.getMethod().getName() + "(" + (a.length > 0 ? a[0] : "") + ", "
                        + (a.length > 1 ? a[1] : "") + ")");
            }
            seenInvocations = inv.size();
        }

        // ── Filo ────────────────────────────────────────────────────────────────────────────────────────────────────

        /** Önceki lider: kirayı alır, iki tur değerlendirir, her şeyi kalıcı yapar (kuşak eksiksiz, işaret taze). */
        void leaderPhase(Node a) {
            a.gain();
            a.mos().handleSweepResults(PORT, List.of(item(PORT, DB, 1, true, 3), item(PORT, WEB, 11, false, 1)));
            a.mos().handleSweepResults(PING, List.of(item(PING, PINGH, 20, true, 3)));
            step("A1");
            mp.advance(60_000);
            a.mos().handleSweepResults(PORT, List.of(item(PORT, DB, 1, true, 3)));
            step("A2");
            assertThat(a.counter(PORT, DB)).isEqualTo(2);
            if (a.rec != null) {
                assertThat(a.rec.flushNow()).isTrue();
                assertThat(a.rec.flushNow()).as("bekleyen yok → işaretli nabız 'kalıcı an = şimdi'").isTrue();
            }
        }

        /** Temiz devir: zarif kapanış (devir boşaltması, sonra kira bırakılır) + takipçi önbelleği (5 sn) geçer. */
        HandoverResult cleanHandover(Node a) {
            a.scheduler().onContextClosed(null);
            mp.advance(6_000);
            return a.rec == null ? null : a.rec.lastHandover();
        }

        /** Çökme: önceki lider hiçbir şey yapmadan susar; kira süresi dolar. */
        void crash() {
            mp.advance(TTL + 1_000);
        }

        /** Halefin dört adımı (tutanakta b1..b4). */
        void successorPhase(Node b) {
            b.mos().handleSweepResults(PING, List.of(item(PING, PINGH, 21, true, 2)), true);   // elle, açan değil
            step("b1");
            mp.advance(60_000);
            b.mos().handleSweepResults(PORT, List.of(item(PORT, WEB, 10, true, 1), item(PORT, DB, 1, true, 3)));
            step("b2");
            mp.advance(60_000);
            b.mos().handleSweepResults(PORT, List.of(item(PORT, DB, 1, true, 3)));
            step("b3");
            mp.advance(60_000);
            b.mos().handleSweepResults(PORT, List.of(item(PORT, DB, 1, true, 3)));
            step("b4");
        }

        Map<String, Object> star() {
            return mp.jdbc().queryForMap("SELECT gen, writer, updated_ms, state_gen, flushed_ms, handover_ms FROM "
                    + "monitor_eval_type_state WHERE alert_type = '*'");
        }

        /** {@code eval-state-load} küme gözlemi (yoksa null). */
        EvalLoadReport observedLoad() {
            return new ClusterHealthStore(mp.jdbc()).read(EvalLoadReport.OBSERVATION)
                    .map(o -> EvalLoadReport.fromJson(o.payload())).orElse(null);
        }

        @Override
        public void close() {
            recorders.forEach(EvalStateRecorder::stop);
            mp.close();
        }
    }

    static void assertMemoryEmpty(Cluster.Node n) {
        EvalSnapshot s = n.mos().verifySnapshot();
        assertThat(s.recoveryUpCount()).as("sayaç yok").isEmpty();
        assertThat(s.recoveryLastCountedAt()).isEmpty();
        assertThat(s.downObserved()).as("kardeş yok").isEmpty();
        assertThat(s.suppressionActive()).isEmpty();
        assertThat(s.scheduledRoundSeen()).as("tur yok (D-6 etkin)").isEmpty();
    }

    static void await(BooleanSupplier cond, String what) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() - until > 0) throw new AssertionError("beklenen olmadı: " + what);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("kesildi: " + what);
            }
        }
    }

    record Worker(Thread thread, AtomicReference<Throwable> failure) {
        void join() throws InterruptedException {
            thread.join(15_000);
            assertThat(thread.isAlive()).as(thread.getName() + " bitti").isFalse();
            assertThat(failure.get()).as(thread.getName() + " hatasız").isNull();
        }
    }

    static Worker start(String name, Runnable r) {
        AtomicReference<Throwable> fail = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable e) {
                fail.set(e);
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return new Worker(t, fail);
    }

    static ListAppender<ILoggingEvent> capture(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> app = new ListAppender<>();
        app.start();
        logger.addAppender(app);
        return app;
    }

    static void release(Class<?> type, ListAppender<ILoggingEvent> app) {
        ((Logger) LoggerFactory.getLogger(type)).detachAppender(app);
    }

    static List<String> messages(ListAppender<ILoggingEvent> app, String contains) {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent e : new ArrayList<>(app.list)) {
            if (e.getFormattedMessage().contains(contains)) out.add(e.getFormattedMessage());
        }
        return out;
    }

    // ── 1–3: temiz devir (TAM), kardeş, çökme (GÖZLEMLER) ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("1: temiz devir → TAM yük; sayaç 2/3 taşındı, halefin İLK sağlıklı sonucu kapatır (ayar kapalı denetim: 3 gerekir)")
    void cleanHandover_counterCarried() {
        ListAppender<ILoggingEvent> mosLog = capture(MonitoringOutageService.class);
        ListAppender<ILoggingEvent> recLog = capture(EvalStateRecorder.class);
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.node(2);
            c.leaderPhase(a);
            assertThat(a.rec.handoverReady()).as("lider eksiksiz kuşak tutuyor").isTrue();
            assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
            Map<String, Object> star = c.star();
            assertThat(star.get("handover_ms")).as("devir işareti = son yazım").isEqualTo(star.get("updated_ms"));
            assertThat(star.get("state_gen")).isEqualTo(star.get("gen"));

            b.gain();
            EvalLoadReport r = b.load();
            assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_FULL);
            assertThat(r.reason()).isNull();
            assertThat(r.fromGeneration()).isEqualTo(1L);
            assertThat(r.fromPod()).isEqualTo(a.owner());
            assertThat(r.recoveryCounters()).as("K1 + K3").isEqualTo(2);
            assertThat(r.siblings()).as("m11").isEqualTo(1);
            assertThat(r.roundSeen()).as("PORT + PING").isEqualTo(2);
            assertThat(r.handoverAgeMs()).isNotNull();
            assertThat(b.counter(PORT, DB)).isEqualTo(2);
            assertThat(c.observedLoad()).as("küme gözlemi eval-state-load").isNotNull()
                    .extracting(EvalLoadReport::outcome, EvalLoadReport::pod)
                    .containsExactly(EvalLoadReport.OUTCOME_FULL, b.owner());
            assertThat(messages(mosLog, "Kalıcı değerlendirme durumu devirde yüklendi (tam)")).hasSize(1);
            assertThat(messages(recLog, "Devir öncesi kalıcı durum boşaltıldı ve işaretlendi")).hasSize(1);

            c.successorPhase(b);
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, FULL_B));
        } finally {
            release(MonitoringOutageService.class, mosLog);
            release(EvalStateRecorder.class, recLog);
        }

        // Denetim: aynı akış ayar KAPALI (bugünkü davranış) → halef sıfırdan, 3 sağlıklı gerekir.
        try (Cluster c = new Cluster()) {
            c.on.set(false);
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.node(2);
            c.leaderPhase(a);
            assertThat(c.cleanHandover(a)).as("ayar kapalı: devir adımı yok").isNull();
            assertThat(c.star().get("state_gen")).as("3-1 nabzı: işaret yok").isNull();
            b.gain();
            assertThat(b.load().reason()).isEqualTo(EvalLoadReport.REASON_SETTING_OFF);
            assertMemoryEmpty(b);
            assertThat(c.observedLoad()).as("ayar kapalıyken gözlem yazılmaz (J-1)").isNull();
            c.successorPhase(b);
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, NONE_B));
        }
    }

    @Test
    @DisplayName("2: temiz devir → DOWN kardeş (m11) yüklü sınıfıyla taşındı; açanın sağlıklı sonucu K2'yi KAPATMAZ (kapalı denetim: kapatır)")
    void cleanHandover_downSiblingSurvives() {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.node(2);
            c.leaderPhase(a);
            assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
            b.gain();
            EvalStateSource.DownValues m11 = b.mos().downObservation(PORT, 11L);
            assertThat(m11.present()).as("kardeş yüklendi").isTrue();
            assertThat(m11.domain()).isEqualTo(WEB);
            assertThat(m11.teamId()).isEqualTo(TEAM);
            assertThat(m11.errorClass()).as("tablodaki hata sınıfı korunur (metin yok)").isEqualTo(EvalStateSource.ERROR_NETWORK);
            c.successorPhase(b);
            assertThat(c.transcript).doesNotContain("b2: resolveMonitoringAlertsForDomain(web.example.com, PORT_DOWN)");
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, FULL_B));
        }
        try (Cluster c = new Cluster()) {
            c.on.set(false);
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.node(2);
            c.leaderPhase(a);
            c.cleanHandover(a);
            b.gain();
            assertThat(b.mos().downObservation(PORT, 11L).present()).isFalse();
            c.successorPhase(b);
            assertThat(c.transcript).contains("b2: resolveMonitoringAlertsForDomain(web.example.com, PORT_DOWN)");
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, NONE_B));
        }
    }

    @Test
    @DisplayName("3: çökme (devir yok, kira süresi doldu) → GÖZLEMLER/no_handover: kardeş tutulur, sayaç TAŞINMAZ (3 gerekir), D-6 etkin")
    void crashTakeover_observationsOnly() {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.node(2);
            c.leaderPhase(a);
            c.crash();
            b.gain();
            EvalLoadReport r = b.load();
            assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_OBSERVATIONS);
            assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_NO_HANDOVER);
            assertThat(r.recoveryCounters()).isZero();
            assertThat(r.roundSeen()).isZero();
            assertThat(r.siblings()).isEqualTo(1);
            EvalSnapshot s = b.mos().verifySnapshot();
            assertThat(s.recoveryUpCount()).as("sayaç taşınmaz (bayat-yüksek olabilir)").isEmpty();
            assertThat(s.scheduledRoundSeen()).as("D-6 koruması etkin").isEmpty();
            assertThat(s.downObserved()).containsOnlyKeys(PORT + "|11");
            c.successorPhase(b);
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, OBS_B));
        }
    }

    // ── 4: süreklilik bozuk → yeniden başlatma semantiği ───────────────────────────────────────────────────────────

    enum Gap {
        /** Önceki liderin son tam yazımı 5 dk'dan eski. */
        STALE(EvalLoadReport.REASON_PREVIOUS_STALE, false),
        /** A → (kaydetmeyen B) → A: son kayıtlı kuşak A'nındı. */
        WRITER_IS_SELF(EvalLoadReport.REASON_WRITER_IS_SELF, false),
        /** Temiz devirden sonra 20.168.x benzeri pod 3-1 CAS'ıyla kuşak aldı (state_gen ≠ gen). */
        PREVIOUS_UNMARKED(EvalLoadReport.REASON_PREVIOUS_UNMARKED, false),
        /** Lider düşüş yaşadı (üst sınır) → kuşak eksik, flushed_ms NULL. */
        PREVIOUS_INCOMPLETE(EvalLoadReport.REASON_PREVIOUS_INCOMPLETE, false),
        /** Önceki liderin saati 60 sn ileride (> 30 sn tolerans). */
        CLOCK_SKEW(EvalLoadReport.REASON_CLOCK_SKEW, false),
        /** Temiz devirden sonra kaydetmeyen üçüncü pod lider oldu ve bir izleme vadesi sahiplendi → yalnız gözlemler. */
        INTERMEDIATE_LEADER(EvalLoadReport.REASON_INTERMEDIATE_LEADER, true),
        /** İlk lider (önceki kuşak yok). */
        NO_PREVIOUS_GENERATION(EvalLoadReport.REASON_NO_PREVIOUS_GENERATION, false);

        final String reason;
        final boolean observations;

        Gap(String reason, boolean observations) {
            this.reason = reason;
            this.observations = observations;
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Gap.class)
    @DisplayName("4: süreklilik denetimi düşerse halef hiçbir karar durumu yüklemez — tutanak ayar kapalı koşuyla aynı")
    void continuityFailure_restartSemantics(Gap gap) {
        try (Cluster c = new Cluster()) {
            Cluster.Node successor;
            List<String> before = A_PHASE;
            switch (gap) {
                case STALE -> {
                    Cluster.Node a = c.node(1);
                    successor = c.node(2);
                    c.leaderPhase(a);
                    assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
                    c.mp.advance(5 * 60_000L + 1_000);
                    successor.gain();
                }
                case WRITER_IS_SELF -> {
                    Cluster.Node a = c.node(1);
                    Cluster.Node silent = c.spec().noRecorder().build(2);
                    c.leaderPhase(a);
                    c.crash();
                    silent.gain();
                    assertThat(silent.load().reason()).isEqualTo(EvalLoadReport.REASON_NO_RECORDER);
                    a.scheduler().renewSweepLeadership();   // A kaybı fark eder (LOST)
                    silent.scheduler().releaseSweepLeadership();
                    c.mp.advance(6_000);
                    successor = a;
                    successor.gain();
                }
                case PREVIOUS_UNMARKED -> {
                    Cluster.Node a = c.node(1);
                    successor = c.node(2);
                    Cluster.Node legacy = c.spec().on(new AtomicBoolean(false)).build(3);
                    c.leaderPhase(a);
                    assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
                    legacy.gain();   // 3-1 CAS: kuşak 2, işaret sütunlarına dokunmaz
                    legacy.scheduler().releaseSweepLeadership();
                    c.mp.advance(6_000);
                    assertThat(c.star().get("state_gen")).as("A'nın işareti kaldı").isEqualTo(1L);
                    assertThat(c.star().get("gen")).isEqualTo(2L);
                    successor.gain();
                }
                case PREVIOUS_INCOMPLETE -> {
                    Cluster.Node a = c.spec().maxPending(12).build(1);
                    successor = c.node(2);
                    c.leaderPhase(a);
                    assertThat(a.rec.generationComplete()).as("önce eksiksiz").isTrue();
                    List<MonitoringOutageService.SweepItem> burst = new ArrayList<>();
                    for (int i = 0; i < 20; i++) burst.add(item(PORT, "burst" + i + ".example.com", 100 + i, true, 3));
                    a.mos().handleSweepResults(PORT, burst);
                    c.step("A3");
                    assertThat(a.rec.status().dropped()).as("üst sınır aşıldı").isPositive();
                    assertThat(a.rec.generationComplete()).isFalse();
                    assertThat(a.rec.flushNow()).isTrue();
                    assertThat(c.star().get("flushed_ms")).as("eksik kuşak: işaretli nabız flushed_ms = NULL").isNull();
                    assertThat(c.cleanHandover(a)).as("hazır değil → devir adımı yok").isNull();
                    successor.gain();
                }
                case CLOCK_SKEW -> {
                    Cluster.Node a = c.spec().skewMs(60_000).build(1);
                    successor = c.node(2);
                    c.leaderPhase(a);
                    assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
                    successor.gain();
                }
                case INTERMEDIATE_LEADER -> {
                    Cluster.Node a = c.node(1);
                    successor = c.node(2);
                    Cluster.Node mid = c.spec().noRecorder().build(3);
                    c.leaderPhase(a);
                    assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
                    mid.gain();
                    mid.pod().schedule().claim("port:99", c.mp.now(), 60_000L, null, x -> x + 60_000L);   // tarama yaptı
                    mid.scheduler().releaseSweepLeadership();
                    c.mp.advance(6_000);
                    successor.gain();
                }
                case NO_PREVIOUS_GENERATION -> {
                    successor = c.node(2);
                    before = List.of();
                    successor.gain();
                }
                default -> throw new IllegalStateException();
            }
            EvalLoadReport r = successor.load();
            assertThat(r.reason()).isEqualTo(gap.reason);
            if (gap.observations) {
                assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_OBSERVATIONS);
                EvalSnapshot s = successor.mos().verifySnapshot();
                assertThat(s.recoveryUpCount()).isEmpty();
                assertThat(s.scheduledRoundSeen()).isEmpty();
                assertThat(s.downObserved()).containsOnlyKeys(PORT + "|11");
            } else {
                assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_NONE);
                assertMemoryEmpty(successor);
            }
            c.successorPhase(successor);
            assertThat(c.transcript).containsExactlyElementsOf(concat(before, gap.observations ? OBS_B : NONE_B));
        }
    }

    // ── 5: okuma hatası ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5: yükleyicinin ikinci okuması düşer → none/read_failed (yarım yük yok) — tutanak ayar kapalıyla aynı")
    void loadReadFailure_restartSemantics() {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.spec().wrap().build(2);
            c.leaderPhase(a);
            assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
            b.ds.failWhen = s -> s.contains(RECOVERY_READ);
            b.gain();
            EvalLoadReport r = b.load();
            assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_NONE);
            assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_READ_FAILED);
            assertThat(r.attemptedTier()).isEqualTo(EvalLoadReport.OUTCOME_FULL);
            assertMemoryEmpty(b);
            assertThat(b.mos().evalLoadGate().state()).as("kapı açıldı").isEqualTo(EvalLoadGate.State.NONE);
            b.ds.failWhen = s -> false;
            c.successorPhase(b);
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, NONE_B));
        }
    }

    // ── 6: yükten önce gelen tarama bekler ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("6: yükleyici CAS'tan sonra bekletilir — zamanlanmış tarama kapıda BEKLER, yük bitince yüklü sayaçla kapatır")
    void sweepBeforeLoad_waits_thenUsesLoadedState() throws Exception {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.spec().wrap().build(2);
            c.leaderPhase(a);
            assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
            b.ds.blockWhen = s -> s.contains(RECOVERY_READ);
            assertThat(b.scheduler().isSweepLeaderNow()).isTrue();   // GAINED → kapı LOADING
            assertThat(b.mos().evalLoadGate().state()).isEqualTo(EvalLoadGate.State.LOADING);
            Worker loader = start("yukleyici", () -> b.rec.flushNow());
            await(() -> b.ds.statements().stream().anyMatch(s -> s.contains(RECOVERY_READ)), "yükleyici bekletildi");
            Worker sweep = start("tarama", () -> b.mos().handleSweepResults(PORT,
                    List.of(item(PORT, WEB, 10, true, 1), item(PORT, DB, 1, true, 3))));
            await(() -> b.mos().evalLoadGate().waiters() >= 1, "tarama kapıda bekliyor");
            assertThat(sweep.thread().isAlive()).isTrue();
            c.step("bekleme");
            assertThat(c.transcript).as("yük bitmeden karar yok").containsExactlyElementsOf(A_PHASE);

            b.ds.release();
            loader.join();
            sweep.join();
            c.step("b2");
            EvalLoadReport r = b.load();
            assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_FULL);
            assertThat(r.waiters()).isEqualTo(1);
            assertThat(c.transcript).containsExactlyElementsOf(
                    concat(A_PHASE, List.of("b2: resolveMonitoringAlertsForDomain(db.example.com, PORT_DOWN)")));
        }
    }

    @Test
    @DisplayName("6b: yükleyici 20 sn'den uzun bekletilir — tarama boş bellekle sürer, geç yük REDDEDİLİR (timeout), bellek taramanınki")
    void sweepBeforeLoad_loadLate_timeout() throws Exception {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.spec().wrap().build(2);
            c.leaderPhase(a);
            assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
            b.ds.blockWhen = s -> s.contains(RECOVERY_READ);
            assertThat(b.scheduler().isSweepLeaderNow()).isTrue();
            Worker loader = start("yukleyici", () -> b.rec.flushNow());
            await(() -> b.ds.statements().stream().anyMatch(s -> s.contains(RECOVERY_READ)), "yükleyici bekletildi");
            Worker sweep = start("tarama", () -> b.mos().handleSweepResults(PORT,
                    List.of(item(PORT, WEB, 10, true, 1), item(PORT, DB, 1, true, 3))));
            await(() -> b.mos().evalLoadGate().waiters() >= 1, "tarama kapıda bekliyor");
            c.mp.advance(EvalLoadGate.LOAD_TIMEOUT_MS + 1_000);   // son tarih (MOS saati) geçti
            sweep.join();
            c.step("b2");
            EvalLoadReport r = b.load();
            assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_NONE);
            assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_TIMEOUT);
            assertThat(r.maxWaitMs()).isGreaterThanOrEqualTo(EvalLoadGate.LOAD_TIMEOUT_MS);

            b.ds.release();
            loader.join();
            assertThat(b.load()).as("geç yük rapor yazmaz").isSameAs(r);
            assertThat(b.counter(PORT, DB)).as("bellek taramanın kurduğu (1), yüklenen 2 birleştirilmedi").isEqualTo(1);
            assertThat(b.mos().downObservation(PORT, 11L).present()).isFalse();
            assertThat(c.transcript).containsExactlyElementsOf(
                    concat(A_PHASE, List.of("b2: resolveMonitoringAlertsForDomain(web.example.com, PORT_DOWN)")));
        }
    }

    // ── 7: kapı kurulmadan giren değerlendirme ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("7: kirayı taramanın kendi çiti alır (GAINED değerlendirmenin İÇİNDE) — bellek dolu → yük atılır (evaluated_before_load)")
    void evaluationSlippedInBeforeArm_discarded() {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.node(2);
            c.leaderPhase(a);
            assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
            // B'ye açıkça sorulmadı: zamanlanmış sonucun çiti kirayı alır → GAINED → sıfırlama + kapı, değerlendirme sürer.
            b.mos().handleSweepResults(PORT, List.of(item(PORT, WEB, 10, true, 1), item(PORT, DB, 1, true, 3)));
            c.step("b2");
            assertThat(b.pod().events().ofType(SweepLeadershipChangedEvent.class))
                    .extracting(SweepLeadershipChangedEvent::reason).containsExactly(SweepLeadershipChangedEvent.GAINED);
            assertThat(b.mos().evalLoadGate().state()).isEqualTo(EvalLoadGate.State.LOADING);
            b.rec.flushNow();
            EvalLoadReport r = b.load();
            assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_NONE);
            assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_EVALUATED_BEFORE_LOAD);
            assertThat(r.attemptedTier()).isEqualTo(EvalLoadReport.OUTCOME_FULL);
            assertThat(b.counter(PORT, DB)).as("birleştirme yok: taramanın 1'i kalır").isEqualTo(1);
            c.mp.advance(60_000);
            b.mos().handleSweepResults(PORT, List.of(item(PORT, DB, 1, true, 3)));
            c.step("b3");
            c.mp.advance(60_000);
            b.mos().handleSweepResults(PORT, List.of(item(PORT, DB, 1, true, 3)));
            c.step("b4");
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, List.of(
                    "b2: resolveMonitoringAlertsForDomain(web.example.com, PORT_DOWN)",
                    "b4: resolveMonitoringAlertsForDomain(db.example.com, PORT_DOWN)")));
        }
    }

    // ── 8: hızlı geçiş, ayar yükleme sırasında kapandı ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("8a: yük okunurken halef kirayı bırakır → leadership_changed, geç yük uygulanmaz; sonraki halef none/previous_unmarked")
    void quickFlip_lateApplyRejected() throws Exception {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.spec().wrap().build(2);
            Cluster.Node d = c.node(3);
            c.leaderPhase(a);
            assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
            b.ds.blockWhen = s -> s.contains(RECOVERY_READ);
            assertThat(b.scheduler().isSweepLeaderNow()).isTrue();
            Worker loader = start("yukleyici", () -> b.rec.flushNow());
            await(() -> b.ds.statements().stream().anyMatch(s -> s.contains(RECOVERY_READ)), "yükleyici bekletildi");
            b.scheduler().releaseSweepLeadership();   // RELEASED (yük sürerken)
            EvalLoadReport r = b.load();
            assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_LEADERSHIP_CHANGED);
            b.ds.release();
            loader.join();
            assertThat(b.load()).as("geç yük ikinci rapor yazmaz").isSameAs(r);
            assertMemoryEmpty(b);

            c.mp.advance(6_000);
            d.gain();   // B'nin CAS'ı A'nın işaretli kuşağının yerini aldı (state_gen ≠ gen)
            assertThat(d.load().reason()).isEqualTo(EvalLoadReport.REASON_PREVIOUS_UNMARKED);
            assertMemoryEmpty(d);
            c.successorPhase(d);
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, NONE_B));
        }
    }

    @Test
    @DisplayName("8b: ayar yük okunurken kapatılır → uygulamada yeniden sorulur, setting_turned_off, bellek boş")
    void settingTurnedOffDuringLoad_discarded() throws Exception {
        try (Cluster c = new Cluster()) {
            AtomicBoolean bOn = new AtomicBoolean(true);
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.spec().on(bOn).wrap().build(2);
            c.leaderPhase(a);
            assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
            b.ds.blockWhen = s -> s.contains(RECOVERY_READ);
            assertThat(b.scheduler().isSweepLeaderNow()).isTrue();
            Worker loader = start("yukleyici", () -> b.rec.flushNow());
            await(() -> b.ds.statements().stream().anyMatch(s -> s.contains(RECOVERY_READ)), "yükleyici bekletildi");
            bOn.set(false);
            b.ds.release();
            loader.join();
            EvalLoadReport r = b.load();
            assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_NONE);
            assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_SETTING_TURNED_OFF);
            assertMemoryEmpty(b);
            c.successorPhase(b);
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, NONE_B));
        }
    }

    // ── 9: eski liderin geç boşaltması ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("9: eski liderin süren boşaltması halefin CAS'ından SONRA iner — halef GÖZLEMLER, eski satırlar hiçbir zaman yüklenmez, A çitli")
    void lateOldLeaderFlush_doesNotBreakObservations() throws Exception {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.spec().wrap().build(1);
            Cluster.Node b = c.node(2);
            Cluster.Node d = c.node(3);
            c.leaderPhase(a);
            c.mp.advance(60_000);
            a.mos().handleSweepResults(PING, List.of(item(PING, PINGH, 20, true, 3)));   // K3 2/3 — henüz kalıcı değil
            c.step("A3");
            a.ds.clear();
            a.ds.blockWhen = s -> s.startsWith("UPDATE monitor_eval_state SET")
                    || s.startsWith("INSERT INTO monitor_eval_state ");
            Worker late = start("gec-bosaltma", () -> a.rec.flushNow());
            await(() -> a.ds.statements().stream().anyMatch(s -> s.startsWith("UPDATE monitor_eval_state SET")),
                    "A'nın kurtarma yazımı bekletildi");

            c.crash();
            b.gain();
            EvalLoadReport r = b.load();
            assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_OBSERVATIONS);
            assertThat(r.reason()).isEqualTo(EvalLoadReport.REASON_NO_HANDOVER);
            assertThat(b.mos().verifySnapshot().recoveryUpCount()).isEmpty();
            a.scheduler().renewSweepLeadership();   // A kaybı fark eder
            a.ds.release();
            late.join();
            List<Integer> lateRow = c.mp.jdbc().queryForList("SELECT recovery_up_count FROM monitor_eval_state WHERE gen = 1 "
                    + "AND alert_type = ? AND domain = ?", Integer.class, PING, PINGH);
            assertThat(lateRow).as("A'nın geç satırı eski kuşağa indi").containsExactly(2);
            long now = c.mp.now();
            assertThat(a.rec.sql().heartbeatMarked(1L, a.owner(), now, now)).as("A'nın nabzı 0 satır").isFalse();
            assertThat(a.rec.sql().handoverMark(1L, a.owner(), now)).as("A'nın işareti 0 satır").isFalse();

            // B'nin doğrulaması yalnız kendi kuşağına bakar — geç satır görünmez.
            assertThat(b.rec.verifyNow().reason()).isEqualTo(EvalStateVerifier.REASON_RESYNC);
            c.mp.advance(1_000);
            b.rec.flushNow();
            EvalVerifyReport v = b.rec.verifyNow();
            assertThat(v.status()).isEqualTo(EvalVerifyReport.STATUS_OK);
            assertThat(v.passFailMismatches()).isZero();

            // B → D temiz devir: D yalnız gen = '*'.gen (2) satırlarını yükler; kuşak 1'in geç K3 = 2 satırı ASLA.
            assertThat(b.rec.flushNow()).isTrue();
            assertThat(c.cleanHandover(b)).isEqualTo(HandoverResult.WRITTEN);
            d.gain();
            EvalLoadReport rd = d.load();
            assertThat(rd.outcome()).isEqualTo(EvalLoadReport.OUTCOME_FULL);
            assertThat(rd.fromGeneration()).isEqualTo(2L);
            assertThat(rd.recoveryCounters()).isZero();
            assertThat(d.mos().verifySnapshot().recoveryUpCount()).as("eski kuşağın satırı yüklenmedi").isEmpty();
            assertThat(d.mos().downObservation(PORT, 11L).present()).as("kardeş B'nin kuşağıyla taşındı").isTrue();
            c.successorPhase(d);
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, OBS_B));
        }
    }

    // ── 10: iki temiz devir ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("10: A → B (TAM) → C (TAM): B hiçbir şeye dokunmadan devreder — oynatma B'nin kuşağına yazdı, sayaç / kardeş / tur taşındı")
    void twoCleanHandovers_replayCarriesState() {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.node(2);
            Cluster.Node d = c.node(3);
            c.leaderPhase(a);
            assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
            b.gain();
            assertThat(b.load().outcome()).isEqualTo(EvalLoadReport.OUTCOME_FULL);
            assertThat(b.rec.generationComplete()).as("oynatma boşaltıldı → eksiksiz").isTrue();
            Integer inB = c.mp.jdbc().queryForObject("SELECT recovery_up_count FROM monitor_eval_state WHERE gen = 2 "
                    + "AND alert_type = ? AND domain = ?", Integer.class, PORT, DB);
            assertThat(inB).as("yüklenen sayaç B'nin kuşağına oynatıldı").isEqualTo(2);
            assertThat(b.rec.flushNow()).isTrue();
            assertThat(c.cleanHandover(b)).isEqualTo(HandoverResult.WRITTEN);
            d.gain();
            EvalLoadReport r = d.load();
            assertThat(r.outcome()).isEqualTo(EvalLoadReport.OUTCOME_FULL);
            assertThat(r.fromGeneration()).isEqualTo(2L);
            assertThat(r.fromPod()).isEqualTo(b.owner());
            assertThat(r.recoveryCounters()).isEqualTo(2);
            assertThat(r.siblings()).isEqualTo(1);
            assertThat(r.roundSeen()).isEqualTo(2);
            c.successorPhase(d);
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, FULL_B));
        }
    }

    // ── 11: yavaş veritabanında devir bütçesi ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("11: devir boşaltması veritabanında takılır — kira ~1 sn içinde yine bırakılır, işaret YOK, halef GÖZLEMLER")
    void handoverBudget_slowDb() throws Exception {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.spec().wrap().build(1);
            Cluster.Node b = c.node(2);
            c.leaderPhase(a);
            a.ds.blockWhen = s -> s.contains("handover_ms = NULL");   // devir yardımcısının ilk ifadesi (işaretli nabız)
            long t0 = System.nanoTime();
            a.scheduler().onContextClosed(null);
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertThat(tookMs).as("devir bütçesi (300 + 700 ms) + pay").isLessThan(1_800);
            assertThat(a.rec.lastHandover()).isEqualTo(HandoverResult.BUDGET_EXCEEDED);
            assertThat(c.mp.leaseOwner("sweep-leader")).as("kira bırakıldı").isNull();
            a.ds.release();
            await(() -> Thread.getAllStackTraces().keySet().stream()
                    .noneMatch(t -> EvalStateRecorder.HANDOVER_THREAD_NAME.equals(t.getName()) && t.isAlive()),
                    "devir yardımcısı bitti");
            Map<String, Object> star = c.star();
            assertThat(star.get("handover_ms")).as("bütçe dolunca işaret yazılmaz").isNull();
            c.mp.advance(6_000);
            b.gain();
            assertThat(b.load().outcome()).isEqualTo(EvalLoadReport.OUTCOME_OBSERVATIONS);
            assertThat(b.load().reason()).isEqualTo(EvalLoadReport.REASON_NO_HANDOVER);
            c.successorPhase(b);
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, OBS_B));
        }
    }

    // ── 12: süren teyit zincirleri listesi her pod'da aynı ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static ActiveConfirmationsService confirmations(Cluster.Node n, BooleanSupplier on, Cluster c) throws Exception {
        Constructor<ActiveConfirmationsService> k = ActiveConfirmationsService.class.getDeclaredConstructor(Function.class,
                BooleanSupplier.class, BooleanSupplier.class, JdbcTemplate.class, LongSupplier.class);
        k.setAccessible(true);
        Function<String, List<Map<String, Object>>> memory = n.mos()::activeConfirmations;
        BooleanSupplier leader = n.scheduler()::sweepLeaderHint;
        return k.newInstance(memory, on, leader, c.mp.jdbc(), c.mp.clockSupplier());
    }

    @Test
    @DisplayName("12: liderde süren teyit zinciri → takipçi (ayar açık) AYNI listeyi tablodan verir; kapalı → []; temiz devirden sonra liste boşalır")
    void confirmationsListSameOnEveryPod() throws Exception {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.spec().manualConfirms().build(1);
            Cluster.Node b = c.node(2);
            a.gain();
            a.mos().handleSweepResults(PORT, List.of(item(PORT, "fresh.example.com", 30, false, 3),
                    item(PORT, "other.example.com", 31, false, 3)));   // açık alarmı yok → iki teyit zinciri
            assertThat(a.confirms.pending()).isEqualTo(2);
            assertThat(a.rec.flushNow()).isTrue();
            ActiveConfirmationsService onB = confirmations(b, () -> true, c);
            ActiveConfirmationsService offB = confirmations(b, () -> false, c);
            List<Map<String, Object>> memoryA = a.mos().activeConfirmations(null);
            assertThat(memoryA).hasSize(2);
            assertThat(onB.list(null)).as("takipçi = liderin belleği").containsExactlyInAnyOrderElementsOf(memoryA);
            assertThat(onB.list("FRESH.example.com")).containsExactlyElementsOf(a.mos().activeConfirmations("fresh.example.com"));
            assertThat(offB.list(null)).as("ayar kapalı: takipçinin belleği (bugün)").isEmpty();

            a.confirms.runRound();   // her zincir 1. denemeyi yapar (DOWN → 2. deneme planlanır)
            assertThat(a.rec.flushNow()).isTrue();
            c.mp.advance(CONFIRM_MEMO_MS + 100);
            List<Map<String, Object>> afterAttempt = a.mos().activeConfirmations(null);
            assertThat(afterAttempt).allSatisfy(m -> assertThat(m.get("attempt")).isEqualTo(1));
            assertThat(onB.list(null)).containsExactlyInAnyOrderElementsOf(afterAttempt);

            assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);   // kendi zincir satırlarını ABANDONED yapar
            c.mp.advance(CONFIRM_MEMO_MS + 100);
            assertThat(onB.list(null)).as("devirden sonra eski liderin zincirleri listelenmez").isEmpty();
            a.confirms.drain();   // eski dönemin adımları hiçbir şeye dokunmaz
            c.step("son");
            assertThat(c.transcript).as("zincir hiç alarm açmadı").isEmpty();
        }
    }

    // ── 13: karışık sürümler ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("13a: 3-1 ifadeleriyle yazan önceki lider (20.168.x gibi) → 3-2 halef none/previous_unmarked, yeniden başlatma semantiği")
    void mixedVersion_legacyHolder() {
        try (Cluster c = new Cluster()) {
            AtomicBoolean legacyView = new AtomicBoolean(false);
            Cluster.Node a = c.spec().on(legacyView).build(1);   // ayar yok / 20.168.x: 3-1 nabzı, devir adımı yok
            Cluster.Node b = c.node(2);
            c.leaderPhase(a);
            assertThat(c.cleanHandover(a)).isNull();
            assertThat(c.star().get("state_gen")).isNull();
            b.gain();
            assertThat(b.load().reason()).isEqualTo(EvalLoadReport.REASON_PREVIOUS_UNMARKED);
            assertMemoryEmpty(b);
            c.successorPhase(b);
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, NONE_B));
        }
    }

    @Test
    @DisplayName("13b: 3-2 lider temiz devretti, halef ayarı KAPALI görüyor → none/setting_off, günlük ve gözlem yok, yeniden başlatma semantiği")
    void mixedVersion_successorSeesSettingOff() {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.node(1);
            AtomicBoolean bView = new AtomicBoolean(false);
            Cluster.Node b = c.spec().on(bView).build(2);
            c.leaderPhase(a);
            assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
            EvalLoadReport before = c.observedLoad();
            assertThat(before).as("A'nın kendi (ilk lider) raporu").isNotNull();
            ListAppender<ILoggingEvent> mosLog = capture(MonitoringOutageService.class);
            try {
                b.gain();
                assertThat(b.load().reason()).isEqualTo(EvalLoadReport.REASON_SETTING_OFF);
                assertThat(b.mos().evalLoadGate().state()).isEqualTo(EvalLoadGate.State.NONE);
                assertThat(messages(mosLog, "devirde yüklen")).as("ayar kapalıyken günlük yok (J-1)").isEmpty();
            } finally {
                release(MonitoringOutageService.class, mosLog);
            }
            assertThat(c.observedLoad()).as("halef gözlem yazmadı").isEqualTo(before);
            assertMemoryEmpty(b);
            c.successorPhase(b);
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, NONE_B));
        }
    }

    // ── 14: TAM yükten sonra doğrulama ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("14: TAM yükten sonra halefin doğrulaması eşitleme turuyla başlar, sonra 0 uyuşmazlık (yük = tablo = bellek)")
    void verifyStaysZeroAfterFullLoad() {
        try (Cluster c = new Cluster()) {
            Cluster.Node a = c.node(1);
            Cluster.Node b = c.node(2);
            c.leaderPhase(a);
            assertThat(c.cleanHandover(a)).isEqualTo(HandoverResult.WRITTEN);
            b.gain();
            assertThat(b.load().outcome()).isEqualTo(EvalLoadReport.OUTCOME_FULL);
            assertThat(b.rec.verifyNow().reason()).isEqualTo(EvalStateVerifier.REASON_RESYNC);
            c.mp.advance(1_000);
            assertThat(b.rec.flushNow()).isTrue();
            EvalVerifyReport v = b.rec.verifyNow();
            assertThat(v.status()).isEqualTo(EvalVerifyReport.STATUS_OK);
            assertThat(v.generation()).isEqualTo(2L);
            assertThat(v.passFailMismatches()).isZero();
            assertThat(v.kinds().get(EvalVerifyReport.KIND_RECOVERY).compared()).as("yüklenen sayaçlar karşılaştırıldı")
                    .isGreaterThanOrEqualTo(2);
            assertThat(v.kinds().get(EvalVerifyReport.KIND_SIBLING).compared()).isGreaterThanOrEqualTo(1);
            assertThat(v.kinds().get(EvalVerifyReport.KIND_ROUND_SEEN).compared()).isGreaterThanOrEqualTo(2);

            c.successorPhase(b);
            c.mp.advance(1_000);
            assertThat(b.rec.flushNow()).isTrue();
            EvalVerifyReport after = b.rec.verifyNow();
            assertThat(after.status()).as("kararlardan sonra da tutarlı").isEqualTo(EvalVerifyReport.STATUS_OK);
            assertThat(after.passFailMismatches()).isZero();
            assertThat(c.transcript).containsExactlyElementsOf(concat(A_PHASE, FULL_B));
        }
    }
}
