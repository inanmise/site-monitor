package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.service.R33ClusterRig.Node;
import com.sitemonitor.service.R33ClusterRig.Opts;
import com.sitemonitor.service.checks.CheckJobTypes;
import com.sitemonitor.service.checks.R33WorkerAccess;
import com.sitemonitor.service.evalstate.EvalLoadReport;
import com.sitemonitor.service.evalstate.HandoverResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-3 — DAĞITIK KONTROLLER, UÇTAN UCA (tasarım R3-3 §9.3, paket D2).
 *
 * <p>Her pod GERÇEK gönderici + iş gövdeleri + değerlendirici ({@code SchedulerService}, paket B), GERÇEK iş kuyruğu / işçi
 * kaydı / işçi (paket A), GERÇEK sonuç boşaltıcısı (paket C) ve kendi {@code MonitoringOutageService}'i ile kurulur
 * ({@link R33ClusterRig}); ortak H2, ortak saat, gerçek tarama liderliği kirası. Denetleyiciler pod başına sahtedir ve sonuç
 * yalnız (izleme, dönem)'e bağlıdır. 3 / 4. senaryolar 3-2 kümesini ({@link EvalStateReadMultiPodTest.Cluster}: gerçek
 * kaydedici, gerçek devir) kullanır. Her senaryonun mekanizması geçici olarak kaldırıldığında senaryo DÜŞER (delta-R3-3-D2.md
 * "Mutasyon denetimi").
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class DistributedChecksMultiPodTest {

    static final long PERIOD_MS = 60_000L;
    static final String PORT = EscalationService.TYPE_PORT_DOWN;
    static final String DB = "db.example.com";

    // ── 1. 200 izleme × 20 dönem ────────────────────────────────────────────────────────────────────────────────────

    /** Dönem kâhini: uzun kesinti, tek dönemlik çökme ve kurtarmayı bölen ikinci DOWN — pod'dan bağımsız. */
    static boolean s1Up(long id, long p) {
        if (id % 20 == 0 && p >= 3 && p <= 7) return false;
        if (id % 50 == 7 && p == 10) return false;
        return !(id % 33 == 1 && (p == 13 || p == 15));
    }

    static PortMonitor s1Monitor(long id) {
        PortMonitor m = R33ClusterRig.port(id, 60);
        m.setRecoveryChecks(2);
        boolean confirm = id % 40 == 0;
        m.setConfirmAttempts(confirm ? 2 : 0);   // 0 = hemen aç; 2 = değerlendiricide 2 yeniden kontrollü teyit zinciri
        m.setConfirmIntervalSeconds(confirm ? 1 : null);
        m.setRecoveryIntervalSeconds(null);       // pasif kurtarma: karar ardışık turlarda
        return m;
    }

    static R33ClusterRig s1Rig(MultiPodTestSupport mp, int monitors) {
        R33ClusterRig rig = new R33ClusterRig(mp, PERIOD_MS);
        rig.alarms = new R33ClusterRig.AlarmStore(mp, rig::period);
        rig.types.set("port");
        for (long id = 1; id <= monitors; id++) rig.ports.add(s1Monitor(id));
        rig.oracle = (pod, type, id, now) -> s1Up(id, rig.period(now));
        return rig;
    }

    @Test
    @DisplayName("1: 200 izleme × 20 dönem — her izleme dönem başına TAM BİR kez denetlenir, iş ≥ 2 pod'a yayılır, her sonuç bir kez "
            + "değerlendirilir; karar tutanağı tek pod klasik koşuyla aynı")
    void s1_everyMonitorOncePerPeriod_spread_evaluatedOnce_sameDecisionsAsClassic() {
        Map<String, List<String>> distributed;
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = s1Rig(mp, 200);
            Node a = rig.node(1);
            Node b = rig.node(2);
            Node c = rig.node(3);
            rig.workerOrder.addAll(List.of(b, c, a));
            assertThat(a.ss().isSweepLeaderNow()).as("pod1 kirayı alır").isTrue();
            rig.tickRegistries();
            rig.tickRegistries();
            for (int p = 0; p < 20; p++) rig.untilNextPeriod();

            Map<String, List<Integer>> checks = rig.periodChecks();
            assertThat(checks).as("200 izleme × 20 dönem").hasSize(200 * 20);
            assertThat(checks).allSatisfy((key, pods) -> assertThat(pods).as("dönem kontrolü " + key).hasSize(1));
            Map<Integer, Long> perPod = new TreeMap<>();
            for (List<Integer> pods : checks.values()) perPod.merge(pods.get(0), 1L, Long::sum);
            assertThat(perPod.keySet()).as("iş pod'lara yayıldı: " + perPod).hasSizeGreaterThanOrEqualTo(2);
            assertThat(perPod.getOrDefault(2, 0L) + perPod.getOrDefault(3, 0L)).as("takipçiler işin çoğunu aldı (U-2)")
                    .isGreaterThan(perPod.getOrDefault(1, 0L));
            assertThat(rig.runs).as("her iş tek kez koştu").hasSize(4000);
            assertThat(rig.evaluations()).as("her sonuç bir kez değerlendirildi").hasSize(4000)
                    .allSatisfy((key, n) -> assertThat(n).as(key).isEqualTo(1));
            assertThat(rig.evals).as("yalnız lider değerlendirir (K-2)").allSatisfy(e -> assertThat(e.pod()).isEqualTo(1));
            for (Node f : List.of(b, c)) {
                assertThat(f.mos().verifySnapshot().scheduledRoundSeen()).as(f + ": takipçi MOS'u hiç zamanlanmış tur görmedi")
                        .isEmpty();
                assertThat(f.mos().verifySnapshot().recoveryUpCount()).isEmpty();
            }
            assertThat(rig.checks.stream().filter(R33ClusterRig.Check::recheck).toList())
                    .as("teyit yeniden kontrolleri değerlendiricide (lider) koştu").isNotEmpty()
                    .allSatisfy(ch -> assertThat(ch.pod()).isEqualTo(1));
            assertThat(rig.jobRows()).as("kuyruk boşaldı").isZero();
            assertThat(a.ss().distributedCheckStatus().get("mode")).asString().contains("port=distributed");
            distributed = rig.alarms.byPeriod();
        }

        Map<String, List<String>> classic;
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = s1Rig(mp, 200);
            Node solo = rig.node(1, new Opts().legacy());
            assertThat(solo.ss().isSweepLeaderNow()).isTrue();
            for (int p = 0; p < 20; p++) rig.untilNextPeriod();
            assertThat(rig.periodChecks()).hasSize(200 * 20)
                    .allSatisfy((key, pods) -> assertThat(pods).as(key).hasSize(1));
            classic = rig.alarms.byPeriod();
        }
        assertThat(classic).as("klasik koşu karar üretti (senaryo kör değil)").isNotEmpty();
        assertThat(String.join("\n", classic.values().stream().flatMap(List::stream).toList()))
                .contains(" open ", " realert ", " resolve ");
        assertThat(distributed).as("dağıtık karar tutanağı = tek pod klasik koşu (K-3)").isEqualTo(classic);
    }

    // ── 2. İşçi ölümü / takılma ─────────────────────────────────────────────────────────────────────────────────────

    static R33ClusterRig portRig(MultiPodTestSupport mp, int monitors) {
        R33ClusterRig rig = new R33ClusterRig(mp, PERIOD_MS);
        rig.types.set("port");
        for (long id = 1; id <= monitors; id++) rig.ports.add(R33ClusterRig.port(id, 60));
        return rig;
    }

    List<Long> jobIds(R33ClusterRig rig) {
        return rig.mp.jdbc().queryForList("SELECT id FROM monitor_check_jobs ORDER BY id", Long.class);
    }

    List<Map<String, Object>> jobs(R33ClusterRig rig) {
        return rig.mp.jdbc().queryForList("SELECT id, monitor_id, state, claimed_by, claimed_ms, attempts FROM monitor_check_jobs "
                + "ORDER BY id");
    }

    @Test
    @DisplayName("2a: işçi kontrol ortasında ölür → işler kira dolunca BAŞKA pod'da bir kez yeniden koşar, tek tamamlama, tek "
            + "değerlendirme; arada dönemler açık işe katılır (çift kontrol yok)")
    void s2a_workerKilledMidCheck_reRunOnceElsewhereAfterLease() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = portRig(mp, 5);
            Node a = rig.node(1);
            Node b = rig.node(2, new Opts().held());
            Node c = rig.node(3);
            rig.workerOrder.addAll(List.of(b, c, a));
            assertThat(a.ss().isSweepLeaderNow()).isTrue();
            rig.tickRegistries();
            rig.tickRegistries();

            rig.step();   // gönderim: 5 iş; pod2 hepsini sahiplenir (bekletilen havuz — kontrol "sürüyor")
            List<Map<String, Object>> claimed = jobs(rig);
            assertThat(claimed).hasSize(5).allSatisfy(j -> {
                assertThat(j.get("state")).isEqualTo("CLAIMED");
                assertThat(j.get("claimed_by")).isEqualTo(b.owner());
            });
            Set<Long> original = claimed.stream().map(j -> ((Number) j.get("id")).longValue()).collect(Collectors.toSet());
            long claimedAt = ((Number) claimed.get(0).get("claimed_ms")).longValue();
            long killedAt = mp.now();

            b.dead = true;   // pod2 kontrol ortasında öldü: kuyruğundaki işler hiç bitmez
            assertThat(b.held.drop()).isEqualTo(5);

            long lease = CheckJobTypes.leaseMs(CheckJobTypes.PORT);
            while (mp.now() <= claimedAt + lease) {
                rig.step();
                assertThat(rig.evals).as("kira dolmadan yeniden koşu yok").isEmpty();
            }
            rig.steps(2);

            List<R33ClusterRig.Eval> reRun = rig.evals.stream().filter(e -> original.contains(e.jobId())).toList();
            assertThat(reRun).as("her özgün iş TAM BİR kez değerlendirildi").hasSize(5)
                    .extracting(R33ClusterRig.Eval::jobId).doesNotHaveDuplicates();
            assertThat(reRun).allSatisfy(e -> assertThat(e.worker()).isNotEqualTo(b.owner()));
            List<R33ClusterRig.Run> after = rig.runs.stream().filter(r -> r.atMs() >= killedAt).toList();
            assertThat(after).as("yeniden koşu başka pod'da, kira dolduktan sonra").isNotEmpty()
                    .allSatisfy(r -> assertThat(r.pod()).isIn(1, 3));
            Map<Long, Long> firstAfterKill = new TreeMap<>();
            for (R33ClusterRig.Run r : after) firstAfterKill.merge(r.monitorId(), r.atMs(), Math::min);
            assertThat(firstAfterKill).hasSize(5).allSatisfy((id, at) -> assertThat(at).isGreaterThan(claimedAt + lease));
            assertThat(rig.runs.stream().filter(r -> r.pod() == 2)).as("ölen pod hiçbir işi bitirmedi").isEmpty();
            assertThat(b.worker.status().completed()).isZero();
            assertThat(a.worker.status().late() + c.worker.status().late()).as("çift tamamlama yok").isZero();

            // Sonra normal dönemler: her izleme dönem başına bir kez.
            long resume = rig.period() + 1;
            rig.untilNextPeriod();
            rig.untilNextPeriod();
            rig.untilNextPeriod();
            Map<String, List<Integer>> checks = rig.periodChecks();
            for (long id = 1; id <= 5; id++) {
                for (long p = resume; p < resume + 2; p++) {
                    assertThat(checks.get("port:" + id + ":" + p)).as("port:" + id + " dönem " + p).hasSize(1);
                }
            }
            assertThat(checks).allSatisfy((k, pods) -> assertThat(pods).as(k).hasSizeLessThanOrEqualTo(1));
        }
    }

    @Test
    @DisplayName("2b: takılan işçi kira dolduktan sonra sonuç yazar → belirteç uyuşmaz, sonuç REDDEDİLİR (geç); iş yeni sahibinin "
            + "sonucuyla bir kez değerlendirilir")
    void s2b_hungWorkerPastLease_lateCompletionRejected() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = portRig(mp, 1);
            Node a = rig.node(1);
            Node b = rig.node(2, new Opts().held());
            Node c = rig.node(3, new Opts().held());
            rig.workerOrder.addAll(List.of(b, c, a));
            assertThat(a.ss().isSweepLeaderNow()).isTrue();
            rig.tickRegistries();
            rig.tickRegistries();

            rig.step();
            long jobId = jobIds(rig).get(0);
            long claimedAt = ((Number) jobs(rig).get(0).get("claimed_ms")).longValue();
            assertThat(jobs(rig).get(0).get("claimed_by")).isEqualTo(b.owner());

            b.stalled = true;   // pod2 takıldı (GC duraklaması / CPU açlığı): ne yoklar ne yazar
            long lease = CheckJobTypes.leaseMs(CheckJobTypes.PORT);
            while (mp.now() <= claimedAt + lease) rig.step();
            rig.step();
            Map<String, Object> row = jobs(rig).get(0);
            assertThat(row.get("claimed_by")).as("kira doldu, pod3 yeniden sahiplendi").isEqualTo(c.owner());
            assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(2);

            b.stalled = false;   // pod2 uyanır: takılan gövde döner, sonucu ESKİ belirteçle yazmaya çalışır
            assertThat(b.held.runAll()).isEqualTo(1);
            R33WorkerAccess.flush(b.worker);
            assertThat(b.worker.status().late()).as("geç sonuç reddedildi").isEqualTo(1);
            assertThat(jobs(rig).get(0).get("state")).as("satır hâlâ pod3'ün").isEqualTo("CLAIMED");

            assertThat(c.held.runAll()).isEqualTo(1);
            R33WorkerAccess.flush(c.worker);
            rig.drainAll();
            List<R33ClusterRig.Eval> ev = rig.evals.stream().filter(e -> e.jobId() == jobId).toList();
            assertThat(ev).as("iş bir kez, yeni sahibinin sonucuyla değerlendirildi").hasSize(1);
            assertThat(ev.get(0).worker()).isEqualTo(c.owner());
            assertThat(c.worker.status().completed()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("2c: yeniden koşan işçi de ölürse iş üçüncü kez koşmaz — biçilir (lost), sonraki dönem yeni iş gönderilir")
    void s2c_secondDeath_jobReaped_noThirdRun() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = portRig(mp, 3);
            Node a = rig.node(1);
            Node b = rig.node(2, new Opts().held());
            Node c = rig.node(3, new Opts().held());
            rig.workerOrder.addAll(List.of(b, c, a));
            assertThat(a.ss().isSweepLeaderNow()).isTrue();
            rig.tickRegistries();
            rig.tickRegistries();
            rig.step();
            Set<Long> original = new HashSet<>(jobIds(rig));
            long lease = CheckJobTypes.leaseMs(CheckJobTypes.PORT);
            long first = ((Number) jobs(rig).get(0).get("claimed_ms")).longValue();
            b.dead = true;
            b.held.drop();
            while (mp.now() <= first + lease) rig.step();
            rig.step();
            assertThat(jobs(rig)).as("pod3 yeniden sahiplendi (2. deneme)").filteredOn(j -> original.contains(
                    ((Number) j.get("id")).longValue())).allSatisfy(j -> assertThat(j.get("claimed_by")).isEqualTo(c.owner()));
            long second = mp.now();
            c.dead = true;
            c.held.drop();
            while (mp.now() <= second + lease) rig.step();
            rig.steps(2);
            assertThat(jobIds(rig)).as("iki ölümden sonra özgün işler biçildi").doesNotContainAnyElementsOf(original);
            assertThat(((Number) a.ss().distributedCheckStatus().get("lost")).longValue()).isEqualTo(3);
            assertThat(rig.evals.stream().filter(e -> original.contains(e.jobId()))).as("biçilen iş değerlendirilmez").isEmpty();
            long next = rig.period() + 1;
            rig.untilNextPeriod();
            rig.untilNextPeriod();
            rig.untilNextPeriod();
            Map<String, List<Integer>> checks = rig.periodChecks();
            for (long id = 1; id <= 3; id++) {
                for (long p = next; p < next + 2; p++) {
                    assertThat(checks.get("port:" + id + ":" + p)).as("lider yalnız kaldı (klasik / kendi işçisi): port:" + id
                            + " dönem " + p).containsExactly(1);
                }
            }
        }
    }

    // ── 3 / 4. Devir (3-2 okuma ayarı AÇIK, gerçek kaydedici) ────────────────────────────────────────────────────────

    /** 3-2 kümesine 3-3 parçaları: A (ilk lider), B (halef), W (yalnız işçi). DB alarmı #41 açık (PORT_DOWN db, açan izleme 1). */
    record Handover(EvalStateReadMultiPodTest.Cluster c, R33ClusterRig rig, EvalStateReadMultiPodTest.Cluster.Node a,
                    EvalStateReadMultiPodTest.Cluster.Node b, EvalStateReadMultiPodTest.Cluster.Node w, Node ra, Node rb,
                    Node rw, PortMonitor m) {}

    static Handover handoverCluster(int recoveryChecks, java.util.function.LongPredicate upInPeriod) {
        EvalStateReadMultiPodTest.Cluster c = new EvalStateReadMultiPodTest.Cluster();
        R33ClusterRig rig = new R33ClusterRig(c.mp, PERIOD_MS);
        rig.types.set("port");
        PortMonitor m = R33ClusterRig.port(1, 60);
        m.setHost(DB);
        m.setPort(5432);
        m.setRecoveryChecks(recoveryChecks);
        m.setConfirmAttempts(0);
        m.setRecoveryIntervalSeconds(null);
        rig.ports.add(m);
        rig.oracle = (pod, type, id, now) -> upInPeriod.test(rig.period(now));
        rig.installCheckers(1);
        EvalStateReadMultiPodTest.Cluster.Node a = c.node(1);
        Node ra = rig.attach(1, a.p, new Opts());
        rig.installCheckers(2);
        EvalStateReadMultiPodTest.Cluster.Node b = c.node(2);
        Node rb = rig.attach(2, b.p, new Opts());
        rig.installCheckers(3);
        EvalStateReadMultiPodTest.Cluster.Node w = c.node(3);
        Node rw = rig.attach(3, w.p, new Opts());
        rig.workerOrder.add(rw);   // kontrolleri yalnız W koşturur
        a.gain();
        rig.tickRegistries();
        rig.tickRegistries();
        return new Handover(c, rig, a, b, w, ra, rb, rw, m);
    }

    /** A iki dönem lider: W'nin sağlıklı sonuçları A'da değerlendirilir (sayaç 2); 3. dönemin sonucu DONE bekler. */
    static void leaderPhaseWithQueuedResult(Handover h) {
        h.rig().untilNextPeriod();
        h.rig().untilNextPeriod();
        assertThat(h.a().counter(PORT, DB)).as("A: iki sağlıklı dönem").isEqualTo(2);
        h.ra().drainPaused = true;
        h.rig().step();   // 3. dönem: gönderim + W koşturur ve yazar; A henüz boşaltmadı
        assertThat(h.rig().jobRows("DONE")).as("devirden ÖNCE ölçülmüş sonuç kuyrukta").isEqualTo(1);
        assertThat(h.a().rec.flushNow()).isTrue();
        assertThat(h.a().rec.flushNow()).isTrue();
        h.c().step("A");
    }

    static Map<String, Object> rawUp() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("open", true);
        r.put("response_ms", 20L);
        return r;
    }

    @Test
    @DisplayName("3: temiz devir, kuyrukta DONE sonuç + 3-2 okuma AÇIK → halef TAM yükler, birikimi elle gelen kutusundan ÖNCE "
            + "boşaltır; devirden sonra gelen elle sağlıklı sonuç, önce ölçülmüş DOWN'dan önce alarmı KAPATMAZ (kapısız denetim: kapatır)")
    void s3_cleanHandover_fullLoad_backlogBeforeManualInbox() {
        List<String> guarded = s3(true);
        List<String> unguarded = s3(false);
        assertThat(guarded).as("kapıyla: devirden ÖNCEKİ DOWN önce, alarm açık kalır")
                .containsExactly("b-drain: processConfirmedOutage(db.example.com, PORT_DOWN)");
        assertThat(unguarded).as("denetim (kapı yok): elle UP taşınan 2/3 sayacı 3/3 yapar → alarm bir sağlıklı tur ERKEN kapanır")
                .contains("b-manual1: resolveMonitoringAlertsForDomain(db.example.com, PORT_DOWN)");
    }

    /** 3. senaryonun halef adımları; dönüş: B'nin tutanağı. {@code guard=false} = C'nin elle kutu kapısı yok (20.169.0 davranışı). */
    List<String> s3(boolean guard) {
        Handover h = handoverCluster(3, p -> p != 2);
        try (EvalStateReadMultiPodTest.Cluster c = h.c()) {
            leaderPhaseWithQueuedResult(h);
            assertThat(c.cleanHandover(h.a())).isEqualTo(HandoverResult.WRITTEN);
            h.b().gain();
            EvalLoadReport load = h.b().load();
            assertThat(load.outcome()).as("halef TAM yükledi (kuyruktaki DONE 3-2 yükleyicisini etkilemez)")
                    .isEqualTo(EvalLoadReport.OUTCOME_FULL);
            assertThat(h.b().counter(PORT, DB)).as("sayaç 2/3 taşındı").isEqualTo(2);
            if (!guard) ReflectionTestUtils.setField(h.b().scheduler(), "checkResultDrainer", null);

            h.w().scheduler().evaluatePortNow(h.m(), rawUp());   // takipçi "Şimdi kontrol et": okuma açık → gelen kutusuna
            assertThat(c.inboxRows()).isEqualTo(1);
            int before = c.transcript.size();
            h.b().scheduler().drainManualEvalInbox();
            c.step("b-manual1");
            if (guard) assertThat(c.inboxRows()).as("birikim boşalmadan elle kutu bekler").isEqualTo(1);
            assertThat(h.rb().drainer.drainOnce()).isFalse();
            c.step("b-drain");
            assertThat(h.rig().jobRows()).as("birikim boşaldı").isZero();
            h.b().scheduler().drainManualEvalInbox();
            c.step("b-manual2");
            assertThat(c.inboxRows()).isZero();
            if (guard) assertThat(h.b().counter(PORT, DB)).as("DOWN sıfırladı, elle UP 1/3").isEqualTo(1);
            return new ArrayList<>(c.transcript.subList(before, c.transcript.size()));
        }
    }

    @Test
    @Disabled("needs fix/r3-3-eval DH-M1 (c): GAINED sonrası boşaltıcı birikimi boşaltana dek 8 klasik kilitli gövde checkDue'dan "
            + "ÖNCE dönmeli. Bu tabanda klasik kipteki halefin ilk taraması, devirden ÖNCE ölçülmüş DONE DOWN'dan önce değerlendirilir; "
            + "TAM yüklenen 2/3 sayaç 3/3 olur ve alarm bir sağlıklı tur ERKEN kapanır. Birleştirmeden sonra etkinleştirilecek.")
    @DisplayName("3b (DH-M1 c): klasik kipte halef + TAM yük + kuyrukta devirden önceki DOWN → klasik sağlıklı tur alarmı KAPATMAZ")
    void s3b_successorInClassicMode_pendingDownBeforeClassicUp_noEarlyClose() {
        Handover h = handoverCluster(3, p -> p != 2);
        try (EvalStateReadMultiPodTest.Cluster c = h.c()) {
            R33ClusterRig rig = h.rig();
            rig.untilNextPeriod();
            rig.untilNextPeriod();
            assertThat(h.a().counter(PORT, DB)).isEqualTo(2);
            h.ra().drainPaused = true;
            rig.step();                  // dönem 2: W DOWN ölçer → DONE, A boşaltmaz
            assertThat(rig.jobRows("DONE")).isEqualTo(1);
            h.rw().dead = true;          // W ayrıldı (düğüm boşaltma); A kapanıyor: işçisi ve nabzı durdu
            h.ra().stalled = true;
            while (c.mp.now() < rig.t0 + 2 * PERIOD_MS + 50_000) rig.step();
            assertThat(h.a().rec.flushNow()).isTrue();
            assertThat(h.a().rec.flushNow()).isTrue();
            assertThat(c.cleanHandover(h.a())).isEqualTo(HandoverResult.WRITTEN);
            while (c.mp.now() < rig.t0 + 3 * PERIOD_MS + 1_000) c.mp.advance(1_000);
            h.b().gain();
            assertThat(h.b().load().outcome()).isEqualTo(EvalLoadReport.OUTCOME_FULL);
            assertThat(h.b().counter(PORT, DB)).isEqualTo(2);
            h.rb().registry.tick();
            assertThat(h.rb().registry.liveOtherWorkers(CheckJobTypes.PORT)).as("halefin yanında canlı işçi yok → klasik")
                    .isZero();
            c.step("A");
            int before = c.transcript.size();
            h.b().scheduler().runPortChecks();   // klasik tık yükleme biter bitmez gelir (boşaltıcı ≤ 2 sn sonra)
            c.step("b-classic1");
            h.rb().drainer.drainOnce();          // devirden önce ölçülmüş DOWN
            c.step("b-drain");
            c.mp.advance(30_000);
            h.b().scheduler().runPortChecks();
            c.step("b-classic2");
            List<String> b = new ArrayList<>(c.transcript.subList(before, c.transcript.size()));
            assertThat(b).as("DOWN önce, sağlıklı klasik tur ondan sonra: alarm açık kalır")
                    .noneMatch(t -> t.contains("resolveMonitoringAlertsForDomain(db.example.com"));
            assertThat(h.b().counter(PORT, DB)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("4: temiz devir sırasında boşaltma partisi koşuyor → NOT_QUIESCENT → halef yalnız GÖZLEMLERİ yükler, sayaç "
            + "taşınmaz (denetim: parti bitince WRITTEN → TAM, sayaç taşınır)")
    void s4_handoverWhileDrainBatchRuns_notQuiescent_observations() throws Exception {
        Handover h = handoverCluster(4, p -> true);
        try (EvalStateReadMultiPodTest.Cluster c = h.c()) {
            leaderPhaseWithQueuedResult(h);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            h.ra().beforeApply = () -> {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            h.ra().drainPaused = false;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread drain = new Thread(() -> {
                try {
                    h.ra().drainer.drainOnce();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "r33-drain-batch");
            drain.setDaemon(true);
            drain.start();
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("parti biletin içinde, uygulamada").isTrue();

            HandoverResult result = h.a().cleanHandoverNoAdvance();
            release.countDown();
            drain.join(10_000);
            assertThat(drain.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(result).as("süren parti → durulma yok").isEqualTo(HandoverResult.NOT_QUIESCENT);
            c.mp.advance(6_000);
            h.b().gain();
            EvalLoadReport load = h.b().load();
            assertThat(load.outcome()).isEqualTo(EvalLoadReport.OUTCOME_OBSERVATIONS);
            assertThat(h.b().counter(PORT, DB)).as("sayaç taşınmadı (yeniden başlatma semantiği)").isZero();
        }

        // Denetim: aynı akış, parti devirden ÖNCE biter → durgun → WRITTEN → TAM, sayaç (3/4) taşınır.
        Handover h2 = handoverCluster(4, p -> true);
        try (EvalStateReadMultiPodTest.Cluster c = h2.c()) {
            leaderPhaseWithQueuedResult(h2);
            h2.ra().drainPaused = false;
            h2.ra().drainer.drainOnce();
            assertThat(h2.a().counter(PORT, DB)).isEqualTo(3);
            assertThat(h2.a().rec.flushNow()).isTrue();
            assertThat(h2.a().rec.flushNow()).isTrue();
            assertThat(c.cleanHandover(h2.a())).isEqualTo(HandoverResult.WRITTEN);
            h2.b().gain();
            assertThat(h2.b().load().outcome()).isEqualTo(EvalLoadReport.OUTCOME_FULL);
            assertThat(h2.b().counter(PORT, DB)).isEqualTo(3);
        }
    }

    // ── 5. Karışık sürümler (K-5) ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("5a: 20.169.0 lider + 3-3 takipçiler (anahtar AÇIK) → hiç iş gönderilmez, lider klasik tarar, işçiler boşta yoklar")
    void s5a_legacyLeader_noJobs() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = portRig(mp, 20);
            Node old = rig.node(1, new Opts().legacy());
            Node b = rig.node(2);
            Node c = rig.node(3);
            assertThat(old.ss().isSweepLeaderNow()).as("eski sürüm pod lider").isTrue();
            for (int p = 0; p < 4; p++) {
                rig.untilNextPeriod();
                // Takipçilerin gönderim tıkı dönem sınırında eski liderin klasik giriş tıkından ÖNCE (üretimde iki zamanlayıcı
                // bağımsız; düzenekte sıra sabit) — vadesi gelmiş izlemeler varken bile takipçi göndermez.
                b.ss().dispatchCheckJobs();
                c.ss().dispatchCheckJobs();
            }
            assertThat(rig.maxJobIdSeen()).as("yalnız 3-3 lideri gönderir").isZero();
            assertThat(rig.runs).isEmpty();
            Map<String, List<Integer>> checks = rig.periodChecks();
            assertThat(checks).hasSize(20 * 4).allSatisfy((k, pods) -> assertThat(pods).as(k).containsExactly(1));
            Integer workers = mp.jdbc().queryForObject("SELECT COUNT(*) FROM monitor_check_workers", Integer.class);
            assertThat(workers).as("3-3 işçileri nabız yazar (eski lider okumaz)").isEqualTo(2);
            assertThat(b.worker.status().claimed() + c.worker.status().claimed()).isZero();
        }
    }

    record MixedRun(Map<String, List<String>> decisions, Map<String, List<Integer>> checks, long maxJobId, Object mode) {}

    MixedRun s5bRun(boolean switchOn) {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = s1Rig(mp, 40);
            rig.switchOn.set(switchOn);
            Node a = rig.node(1);
            rig.node(2, new Opts().legacy());
            rig.node(3, new Opts().legacy());
            assertThat(a.ss().isSweepLeaderNow()).isTrue();
            for (int p = 0; p < 16; p++) rig.untilNextPeriod();
            return new MixedRun(rig.alarms.byPeriod(), rig.periodChecks(), rig.maxJobIdSeen(),
                    a.ss().distributedCheckStatus().get("mode"));
        }
    }

    @Test
    @DisplayName("5b: 3-3 lider + yalnız 20.169.0 takipçiler, anahtar AÇIK → canlı işçi yok → klasik kip; kararlar ve kontroller "
            + "anahtar KAPALI koşuyla birebir")
    void s5b_threeThreeLeaderLegacyFollowers_classicIdenticalToOff() {
        MixedRun on = s5bRun(true);
        MixedRun off = s5bRun(false);
        assertThat(on.maxJobId()).as("iş yok").isZero();
        assertThat(off.maxJobId()).isZero();
        assertThat(String.valueOf(on.mode())).doesNotContain("distributed");
        assertThat(on.checks()).hasSize(40 * 16).allSatisfy((k, pods) -> assertThat(pods).as(k).containsExactly(1));
        assertThat(on.checks()).isEqualTo(off.checks());
        assertThat(off.decisions()).isNotEmpty();
        assertThat(on.decisions()).isEqualTo(off.decisions());
    }

    @Test
    @DisplayName("5c: geri alma — 3-3 lider ölür, 20.169.0 pod devralır: kuyruktaki işler 3-3 işçide biter ama DEĞERLENDİRİLMEZ; "
            + "aynı dönemde ikinci kontrol yok (ortak vade CAS'ı)")
    void s5c_rollbackToLegacyLeader_noDuplicateCheck() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = portRig(mp, 10);
            Node a = rig.node(1);
            Node b = rig.node(2, new Opts().held());
            Node old = rig.node(3, new Opts().legacy());
            rig.workerOrder.addAll(List.of(b, a));
            assertThat(a.ss().isSweepLeaderNow()).isTrue();
            rig.tickRegistries();
            rig.tickRegistries();
            rig.untilNextPeriod();   // dönem 0: dağıtık
            rig.step();              // dönem 1: gönderim, pod2 sahiplenir (bekletilen)
            a.dead = true;           // 3-3 lider çöktü
            b.held.runAll();         // pod2 işleri bitirir ve yazar — kimse değerlendirmez
            R33WorkerAccess.flush(b.worker);
            int evaluatedBefore = rig.evals.size();
            mp.advance(MonitoringMultiPodKit.TTL);   // kira süresi doldu; eski sürüm pod önce sorar ve devralır
            assertThat(old.ss().isSweepLeaderNow()).isTrue();
            for (int p = 0; p < 4; p++) rig.untilNextPeriod();
            assertThat(mp.leaseOwner("sweep-leader")).as("eski sürüm pod devraldı").isEqualTo(old.owner());
            assertThat(rig.evals).as("eski lider kuyruğu bilmez: değerlendirme yok").hasSize(evaluatedBefore);
            assertThat(rig.jobRows("DONE")).as("sonuçlar kuyrukta kalır (≤ 1 kayıp değerlendirme / izleme)").isEqualTo(10);
            assertThat(rig.periodChecks()).as("hiçbir izleme bir dönemde iki kez denetlenmedi")
                    .allSatisfy((k, pods) -> assertThat(pods).as(k).hasSize(1));
            assertThat(rig.periodChecks().entrySet().stream().filter(e -> e.getValue().contains(3)))
                    .as("eski lider sonraki ızgara noktalarını klasik tarar").isNotEmpty();
        }
    }

    @Test
    @DisplayName("5d: temiz devir dönem ortasında 20.169.0 pod'a — 3-3 liderin bu dönem gönderdiği izlemeler eski lideri aynı "
            + "dönemde ikinci kez denetletmez (gönderim klasikle AYNI port:<id> vade anahtarını tüketir)")
    void s5d_cleanHandoverToLegacyMidPeriod_sharedDueKeys() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = portRig(mp, 10);
            Node a = rig.node(1);
            Node b = rig.node(2);
            Node old = rig.node(3, new Opts().legacy());
            rig.workerOrder.addAll(List.of(b, a));
            assertThat(a.ss().isSweepLeaderNow()).isTrue();
            rig.tickRegistries();
            rig.tickRegistries();
            rig.untilNextPeriod();
            rig.untilNextPeriod();   // dönem 2'nin başı (giriş tıkı henüz koşmadı)
            long p = rig.period();
            rig.step();              // gönderim: 10 iş pod2'de biter, lider boşaltır
            assertThat(rig.periodChecks().keySet().stream().filter(k -> k.endsWith(":" + p)))
                    .as("dönem " + p + " dağıtık denetlendi").hasSize(10);
            a.ss().onContextClosed(null);   // temiz devir: kira bırakıldı (RELEASED)
            a.dead = true;
            assertThat(old.ss().isSweepLeaderNow()).as("eski sürüm pod kirayı hemen alır").isTrue();
            int guard = 0;
            while (rig.period() == p && guard++ < 100) rig.step();   // dönemin kalanı: eski liderin klasik giriş tık(lar)ı
            rig.untilNextPeriod();
            Map<String, List<Integer>> checks = rig.periodChecks();
            for (long id = 1; id <= 10; id++) {
                assertThat(checks.get("port:" + id + ":" + p)).as("port:" + id + " dönem " + p + " — tek kontrol (3-3 işçisi)")
                        .containsExactly(2);
                assertThat(checks.get("port:" + id + ":" + (p + 1))).as("port:" + id + " sonraki dönem — eski lider klasik")
                        .containsExactly(3);
            }
            assertThat(mp.leaseOwner("sweep-leader")).isEqualTo(old.owner());
        }
    }

    // ── 6. Anahtar AÇIK → KAPALI → AÇIK ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("6: anahtar AÇIK → KAPALI → AÇIK dönem ortasında — çift dönem yok, klasik bir tık içinde döner, kuyruktaki işler "
            + "anahtar yeniden açılınca boşaltılır")
    void s6_switchOnOffOn_noDuplicatePeriod_classicWithinOneTick_queuedDrained() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = portRig(mp, 20);
            Node a = rig.node(1);
            Node b = rig.node(2, new Opts().held());
            Node c = rig.node(3);
            b.held.holding.set(false);   // dönem 0 / 1: pod2'nin işleri satır içi biter
            rig.workerOrder.addAll(List.of(b, c, a));
            assertThat(a.ss().isSweepLeaderNow()).isTrue();
            rig.tickRegistries();
            rig.tickRegistries();
            rig.untilNextPeriod();
            rig.untilNextPeriod();

            // Dönem 2, ilk adım: 20 iş; pod2 16'sını sahiplenir (sürüyor), pod3 4'ünü koşturur.
            b.held.holding.set(true);
            rig.step();
            assertThat(R33WorkerAccess.running(b.worker)).isEqualTo(16);
            long offAt = mp.now();
            long jobsAtOff = rig.maxJobIdSeen();
            rig.switchOn.set(false);
            rig.settingsChanged();
            b.held.runAll();   // anahtar kapandıktan sonra biten kontroller yine yazılır (kirasıyla beklemesin)
            R33WorkerAccess.flush(b.worker);
            assertThat(rig.jobRows("DONE")).isEqualTo(16);

            // "Bir tık içinde klasik": sonraki giriş tıkı klasik gövdeyi koşar.
            while (rig.steps % (R33ClusterRig.ENTRY_TICK_MS / R33ClusterRig.STEP_MS) != 0) rig.step();
            rig.step();
            assertThat(a.ss().distributedCheckStatus().get("mode")).asString().contains("port=classic");
            assertThat(rig.jobRows("DONE")).as("anahtar kapalı: boşaltıcı kuyruğa dokunmaz").isEqualTo(16);
            rig.untilNextPeriod();       // dönem 2 sonu
            rig.step();                  // dönem 3: klasik tarar
            rig.step();
            assertThat(rig.maxJobIdSeen()).as("anahtar kapalıyken yeni iş yok").isEqualTo(jobsAtOff);
            rig.switchOn.set(true);
            rig.settingsChanged();
            b.held.holding.set(false);   // bundan sonra pod2'nin işleri satır içi biter
            rig.untilNextPeriod();       // dönem 3 sonu: dağıtık, ama dönem 3 zaten klasikte sahiplenildi
            rig.untilNextPeriod();       // dönem 4: dağıtık

            Map<String, List<Integer>> checks = rig.periodChecks();
            for (long id = 1; id <= 20; id++) {
                for (long p = 0; p <= 4; p++) {
                    assertThat(checks.get("port:" + id + ":" + p)).as("port:" + id + " dönem " + p + " TAM bir kez").hasSize(1);
                }
            }
            assertThat(checks.entrySet().stream().filter(e -> e.getKey().endsWith(":3")))
                    .as("dönem 3 klasik: liderde").allSatisfy(e -> assertThat(e.getValue()).containsExactly(1));
            assertThat(rig.evals.stream().filter(e -> e.period() == 2 && e.createdMs() < offAt && !e.worker().equals(b.owner())))
                    .as("kapanmadan önce biten 4 iş anahtar kapanmadan değerlendirildi").hasSize(4);
            assertThat(rig.evals.stream().filter(e -> e.period() == 4)).as("dönem 4 dağıtık: 20 sonuç bir kez").hasSize(20);
            // Kapalıyken kuyrukta kalan 16 DONE satırı yeniden açılınca kuyruktan alınır: bu tabanda değerlendirilir, DH-M1 (a)
            // düzeltmesinden sonra açılıştan önce bittikleri için DÜŞÜRÜLÜR — iki durumda da kuyruk boşalır (6b düzeltmeyi sınar).
            assertThat(rig.jobRows()).isZero();
        }
    }

    @Test
    @Disabled("needs fix/r3-3-eval DH-M1 (a): anahtar KAPALI → AÇIK geçişinden ÖNCE biten DONE satırları düşürülmeli (dropped_obsolete). "
            + "Bu tabanda boşaltıcı onları klasik turların daha yeni sonuçlarından SONRA uygular — bayat UP açık alarmı bir sağlıklı "
            + "tur ERKEN kapatır (dönem 3). Birleştirmeden sonra etkinleştirilecek.")
    @DisplayName("6b (DH-M1 a): kapalıyken kuyrukta kalan bayat UP yeniden açılışta değerlendirilmez — alarm doğru turda (dönem 4) kapanır")
    void s6b_staleQueuedResultAfterReEnable_isDropped_noEarlyClose() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = new R33ClusterRig(mp, PERIOD_MS);
            rig.alarms = new R33ClusterRig.AlarmStore(mp, rig::period);
            rig.types.set("port");
            PortMonitor m = R33ClusterRig.port(1, 60);
            m.setRecoveryChecks(2);
            m.setConfirmAttempts(0);
            m.setRecoveryIntervalSeconds(null);
            rig.ports.add(m);
            // dönem 0 DOWN (alarm açılır), 1 UP (anahtar kapanırken kuyrukta kalır), 2 DOWN (klasik), 3+ UP
            rig.oracle = (pod, type, id, now) -> {
                long p = rig.period(now);
                return p == 1 || p >= 3;
            };
            Node a = rig.node(1);
            Node b = rig.node(2, new Opts().held());
            rig.workerOrder.addAll(List.of(b, a));
            assertThat(a.ss().isSweepLeaderNow()).isTrue();
            rig.tickRegistries();
            rig.tickRegistries();
            rig.step();
            b.held.runAll();
            R33WorkerAccess.flush(b.worker);
            rig.drainAll();
            assertThat(rig.alarms.open).as("dönem 0: alarm açık").hasSize(1);
            while (rig.period() < 1) rig.step();
            rig.step();                  // dönem 1: pod2 UP'u ölçer (bekletilen)
            rig.switchOn.set(false);
            rig.settingsChanged();
            b.held.runAll();             // anahtar kapandıktan sonra biter → DONE, boşaltılmaz
            R33WorkerAccess.flush(b.worker);
            assertThat(rig.jobRows("DONE")).isEqualTo(1);
            rig.untilNextPeriod();
            rig.step();                  // dönem 2: klasik DOWN (yeniden uyarı, sayaç 0)
            rig.step();
            rig.switchOn.set(true);
            rig.settingsChanged();
            b.held.holding.set(false);
            rig.step();                  // yeniden açılış: bayat dönem-1 UP satırı kuyruktan alınır
            assertThat(rig.jobRows()).isZero();
            assertThat(rig.evals.stream().filter(e -> e.period() == 1))
                    .as("açılıştan ÖNCE biten satır değerlendirilmez (dropped_obsolete)").isEmpty();
            rig.untilNextPeriod();
            rig.step();                  // dönem 3 UP: doğru sayaç 1/2 → kapanmaz
            assertThat(rig.alarms.decisions).as("dönem 3'te ERKEN kapanış yok").noneMatch(d -> d.startsWith("p3 resolve"));
            rig.untilNextPeriod();
            rig.step();                  // dönem 4 UP: 2/2 → kapanır
            assertThat(rig.alarms.decisions).anyMatch(d -> d.startsWith("p4 resolve"));
        }
    }

    // ── 7. Yetenek: ICMP / k6 ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("7: ICMP'siz pod hiç ping işi almaz; k6 izni dolu pod hiç senaryo işi almaz; her izleme dönem başına bir kez")
    void s7_capabilities_gateClaiming() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = new R33ClusterRig(mp, PERIOD_MS);
            rig.types.set("ping,scripted");
            for (long id = 1; id <= 30; id++) rig.pings.add(R33ClusterRig.ping(id, 60));
            for (long id = 1; id <= 10; id++) rig.scripteds.add(R33ClusterRig.scripted(id, 60));
            Node a = rig.node(1, new Opts().scripted());
            Node b = rig.node(2, new Opts().scripted().noIcmp());
            Node c = rig.node(3, new Opts().scripted().k6Permits(0));
            rig.workerOrder.addAll(List.of(b, c, a));
            assertThat(a.ss().isSweepLeaderNow()).isTrue();
            rig.tickRegistries();
            rig.tickRegistries();
            for (int p = 0; p < 3; p++) rig.untilNextPeriod();

            assertThat(rig.runs).as("ICMP'siz pod2 hiç ping koşturmadı")
                    .noneMatch(r -> r.pod() == 2 && r.type().equals(CheckJobTypes.PING));
            assertThat(rig.runs).as("k6 izni olmayan pod3 hiç senaryo koşturmadı")
                    .noneMatch(r -> r.pod() == 3 && r.type().equals(CheckJobTypes.SCRIPTED));
            // Düzenekte yuvalar satır içi koşar (sahiplenme turu içinde biter): bir adımdaki (aynı an) senaryo koşuları = o turda
            // sahiplenilenler. Senaryo sahiplenmesi boş k6 izniyle sınırlı — izin kapısı kalkarsa pod2 dönemin on işini birden alır.
            assertThat(rig.runs.stream().filter(r -> r.pod() == 2 && r.type().equals(CheckJobTypes.SCRIPTED))
                    .collect(java.util.stream.Collectors.groupingBy(R33ClusterRig.Run::atMs, java.util.stream.Collectors.counting()))
                    .values())
                    .as("pod2 bir sahiplenme turunda en çok boş k6 izni (2) kadar senaryo işi alır")
                    .isNotEmpty().allSatisfy(n -> assertThat(n).isLessThanOrEqualTo(2L));
            assertThat(rig.runs).as("pod2 senaryo, pod3 ping aldı (yetenekleri olan türler)")
                    .anyMatch(r -> r.pod() == 2 && r.type().equals(CheckJobTypes.SCRIPTED))
                    .anyMatch(r -> r.pod() == 3 && r.type().equals(CheckJobTypes.PING));
            Map<String, List<Integer>> checks = rig.periodChecks();
            assertThat(checks).hasSize((30 + 10) * 3).allSatisfy((k, pods) -> assertThat(pods).as(k).hasSize(1));
            String caps = mp.jdbc().queryForObject("SELECT caps FROM monitor_check_workers WHERE instance_id = ?", String.class,
                    b.owner());
            assertThat(caps).as("pod2 nabzı ping yeteneğini bildirmez").doesNotContain(CheckJobTypes.PING)
                    .contains(CheckJobTypes.SCRIPTED);
        }
    }

    // ── 8. Tür CSV'den çıkarıldı ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("8: tür listeden çıkarılınca yeni iş yok; kuyruktaki (bekleyen + sürmekte olan) işler biter ve bir kez "
            + "değerlendirilir; tür klasiğe döner, çift dönem yok")
    void s8_typeRemovedFromCsv_noNewJobs_queuedFinish() {
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = new R33ClusterRig(mp, PERIOD_MS);
            rig.types.set("port,ping");
            for (long id = 1; id <= 20; id++) rig.pings.add(R33ClusterRig.ping(id, 60));
            Node a = rig.node(1);
            Node b = rig.node(2, new Opts().held());
            Node c = rig.node(3);
            b.held.holding.set(false);   // dönem 0: pod2'nin işleri satır içi biter
            rig.workerOrder.addAll(List.of(b, c, a));
            assertThat(a.ss().isSweepLeaderNow()).isTrue();
            rig.tickRegistries();
            rig.tickRegistries();
            rig.untilNextPeriod();

            // Dönem 1, ilk adım: 20 ping işi; pod2 16'sını sahiplenir (sürüyor), 4'ü BEKLİYOR (pod3 ve lider bu adımda takılı).
            b.held.holding.set(true);
            c.stalled = true;
            a.stalled = true;
            rig.step();
            c.stalled = false;
            a.stalled = false;
            assertThat(rig.jobRows("CLAIMED")).isEqualTo(16);
            assertThat(rig.jobRows("PENDING")).isEqualTo(4);
            long lastJob = rig.maxJobIdSeen();
            rig.types.set("port");   // ping listeden çıkarıldı
            rig.settingsChanged();

            rig.step();   // bekleyen 4 iş (CSV'den bağımsız) pod3 / liderde biter
            b.held.runAll();
            R33WorkerAccess.flush(b.worker);
            rig.drainAll();
            rig.untilNextPeriod();
            rig.untilNextPeriod();   // dönem 2: ping klasik (liderde)
            rig.untilNextPeriod();   // dönem 3

            assertThat(rig.maxJobIdSeen()).as("çıkarılan tür için yeni iş gönderilmedi").isEqualTo(lastJob);
            assertThat(rig.jobRows()).isZero();
            assertThat(rig.evals.stream().filter(e -> e.period() == 1)).as("kuyruktaki 20 iş bir kez değerlendirildi").hasSize(20)
                    .extracting(R33ClusterRig.Eval::monitorId).doesNotHaveDuplicates();
            Map<String, List<Integer>> checks = rig.periodChecks();
            for (long id = 1; id <= 20; id++) {
                for (long p = 0; p <= 3; p++) {
                    assertThat(checks.get("ping:" + id + ":" + p)).as("ping:" + id + " dönem " + p).hasSize(1);
                }
                assertThat(checks.get("ping:" + id + ":2")).as("dönem 2 klasik (lider)").containsExactly(1);
            }
            assertThat(a.ss().distributedCheckStatus().get("mode")).asString().contains("ping=classic");
        }
    }
}
