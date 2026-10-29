package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.service.R33ClusterRig.Node;
import com.sitemonitor.service.R33ClusterRig.Opts;
import com.sitemonitor.service.checks.CheckResultDrainer;
import com.sitemonitor.service.checks.CheckWorker;
import com.sitemonitor.service.checks.CheckWorkerRegistryImpl;
import com.sitemonitor.service.checks.DistributedChecks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çok pod Sürüm 3-3 — K-1 İŞ PARÇACIKLARI (tasarım R3-3 §9.1, paket D2): ana anahtar açılıştan beri KAPALIYKEN hiçbir
 * {@code check-worker*} (yoklama, yuva havuzu, kayıt, yoklayıcı, bırakma, durma), {@code check-result-drain} ya da
 * {@code check-dispatch} iş parçacığı doğmaz ve gönderim görevi zamanlayıcıya hiç kaydedilmez.
 *
 * <ul>
 *   <li>GERÇEK Spring bağlamı ({@code test} profili, {@code ShutdownLoggerTest} ile aynı yapılandırma — bağlam önbelleği
 *       paylaşılır): bean'ler kurulu, hazır olayı geçti, {@code @Scheduled} taramalar çalışıyor.</li>
 *   <li>Elle kurulmuş iki pod (üretimdeki tembel başlatıcı kayıtlarıyla): giriş tıkları, gönderim, boşaltma, ayar ve liderlik
 *       olayları, kapanış.</li>
 *   <li>Denetim karşıtı: anahtar açılınca aynı düzenek iş parçacıklarını başlatır (ad listesi gerçekten sınanıyor).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DistributedChecksOffThreadsTest {

    @Autowired
    DistributedChecks distributedChecks;
    @Autowired
    CheckWorker checkWorker;
    @Autowired
    CheckWorkerRegistryImpl checkWorkerRegistry;
    @Autowired
    CheckResultDrainer checkResultDrainer;
    @Autowired
    SchedulerService schedulerService;

    /** 3-3'ün iş parçacığı adları: {@code check-worker…} öneki, boşaltıcı ve gönderim yedeği. */
    static boolean threeThreeThread(String name) {
        return name.startsWith("check-worker") || name.equals(CheckResultDrainer.THREAD_NAME)
                || name.equals(SchedulerService.CHECK_DISPATCH_LOCK);
    }

    static Set<String> threeThreeThreads() {
        Set<String> out = new TreeSet<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && threeThreeThread(t.getName())) out.add(t.getName());
        }
        return out;
    }

    static void awaitNoThreeThreeThreads() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!threeThreeThreads().isEmpty() && System.nanoTime() < until) Thread.sleep(20);
    }

    @Test
    @DisplayName("Spring bağlamı: anahtar varsayılan KAPALI → etkinleşme yok, işçi / kayıt / boşaltıcı başlamadı, gönderim görevi yok, "
            + "hiçbir 3-3 iş parçacığı yok")
    void springContext_offSinceBoot_noThreadsNoTask() throws InterruptedException {
        awaitNoThreeThreeThreads();   // başka bir testin anahtar AÇIK karşı sınaması bitmiş olsun
        assertThat(distributedChecks.enabled()).as("varsayılan kapalı").isFalse();
        assertThat(distributedChecks.activated()).isFalse();
        assertThat(checkWorker.status().started()).isFalse();
        assertThat(checkWorkerRegistry.status().started()).isFalse();
        assertThat(checkResultDrainer.started()).isFalse();
        assertThat(schedulerService.distributedCheckStatus().get("dispatch_active")).as("gönderim görevi kaydedilmedi")
                .isEqualTo(false);
        assertThat(threeThreeThreads()).isEmpty();
    }

    @Test
    @DisplayName("Elle kurulmuş iki pod, anahtar KAPALI: giriş tıkları + gönderim + boşaltma + ayar / liderlik olayları + kapanış — "
            + "sıfır 3-3 iş parçacığı, gönderim görevi yok")
    void handBuilt_offSinceBoot_noThreads() throws InterruptedException {
        awaitNoThreeThreeThreads();
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = new R33ClusterRig(mp, 60_000L);
            rig.switchOn.set(false);
            for (long id = 1; id <= 5; id++) {
                rig.ports.add(R33ClusterRig.port(id, 60));
                rig.pings.add(R33ClusterRig.ping(id, 60));
            }
            Node a = rig.node(1, new Opts().offBoot());
            Node b = rig.node(2, new Opts().offBoot());
            assertThat(a.ss().isSweepLeaderNow()).isTrue();
            for (int i = 0; i < 4; i++) {
                rig.entryTicks();
                for (Node n : List.of(a, b)) {
                    n.ss().dispatchCheckJobs();
                    n.ss().drainManualEvalInbox();
                    n.drainer.drainOnce();
                    n.drainer.holdManual();
                    n.dc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(DistributedChecks.KEY_ENABLED), "save"));
                }
                mp.advance(30_000L);
            }
            a.ss().onContextClosed(null);   // temiz devir: RELEASED → B GAINED (olaylar boşaltıcılara da gider)
            mp.advance(6_000L);
            assertThat(b.ss().isSweepLeaderNow()).isTrue();
            rig.entryTicks();
            for (Node n : List.of(a, b)) {
                assertThat(n.dc.activated()).isFalse();
                assertThat(n.ss().distributedCheckStatus().get("dispatch_active")).isEqualTo(false);
                assertThat(n.worker.status().started()).isFalse();
                assertThat(n.registry.status().started()).isFalse();
                assertThat(n.drainer.started()).isFalse();
                rig.stop(n);
            }
            assertThat(threeThreeThreads()).as("anahtar kapalı: hiçbir 3-3 iş parçacığı").isEmpty();
            assertThat(rig.jobRows()).isZero();
            Integer workers = mp.jdbc().queryForObject("SELECT COUNT(*) FROM monitor_check_workers", Integer.class);
            assertThat(workers).as("nabız satırı yok").isZero();
        }
    }

    @Test
    @DisplayName("Denetim karşıtı: aynı düzenekte anahtar açılınca başlatıcılar koşar — gönderim görevi kurulur, check-worker-poll / "
            + "check-worker-registry / check-result-drain doğar; kapanış durdurur")
    void handBuilt_switchTurnedOn_startsThreads() throws InterruptedException {
        awaitNoThreeThreeThreads();
        try (MultiPodTestSupport mp = MultiPodTestSupport.create()) {
            R33ClusterRig rig = new R33ClusterRig(mp, 60_000L);
            rig.switchOn.set(false);
            rig.ports.add(R33ClusterRig.port(1, 60));
            Node a = rig.node(1, new Opts().offBoot());
            assertThat(threeThreeThreads()).isEmpty();
            rig.switchOn.set(true);
            a.dc.onSettingsChanged(new AppSettingsChangedEvent(Set.of(DistributedChecks.KEY_ENABLED), "save"));
            assertThat(a.dc.activated()).isTrue();
            assertThat(a.ss().distributedCheckStatus().get("dispatch_active")).isEqualTo(true);
            assertThat(threeThreeThreads()).contains(CheckWorker.POLL_THREAD, CheckWorkerRegistryImpl.THREAD,
                    CheckResultDrainer.THREAD_NAME);
            rig.stop(a);
            awaitNoThreeThreeThreads();
            assertThat(threeThreeThreads()).as("kapanış iş parçacıklarını durdurdu").isEmpty();
        }
    }
}
