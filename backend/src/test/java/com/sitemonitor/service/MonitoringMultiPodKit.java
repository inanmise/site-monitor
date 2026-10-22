package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.it.MultiPodTestSupport.Pod;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Çok pod R1-B testlerinin ortak düzeneği (2026-10-21): pod başına GERÇEK {@link MonitoringOutageService} (kendi belleği),
 * ortak depolar ({@link MultiPodTestSupport#mock}) ve ortak veritabanı (alarm kilidi {@code scheduler_lock}, değişiklik
 * satırları). Kapı = pod'un liderliği, {@link SweepLeadershipChangedEvent} → sıfırlama — üretimdeki bağlantının aynısı
 * ({@code SchedulerService.startSweepLeaderRenewer} kapıyı, Spring olay dağıtımı dinleyiciyi bağlar).
 */
final class MonitoringMultiPodKit {

    private MonitoringMultiPodKit() {
    }

    /** Kira süresi (varsayılan {@code site.monitor.scheduler.sweep-leader-ttl-ms}). */
    static final long TTL = 180_000L;

    /** Alarm servisi: ortak depolar + ortak veritabanı; teyit 3 deneme; saat = ortak saat. */
    static MonitoringOutageService mos(MultiPodTestSupport mp) {
        MonitoringOutageService s = new MonitoringOutageService(mp.mock(AlertEventRepository.class),
                mp.mock(EscalationService.class), mp.jdbc(), mp.mock(DnsRecordRepository.class),
                mp.mock(DnsMonitorRepository.class), mp.mock(AppSettingsService.class),
                mp.mock(NetworkOutageEventRepository.class));
        for (String f : List.of("uptimeAlertEnabled", "portAlertEnabled", "dnsAlertEnabled", "keywordAlertEnabled",
                "pingAlertEnabled")) {
            ReflectionTestUtils.setField(s, f, true);
        }
        ReflectionTestUtils.setField(s, "confirmAttempts", 3);
        ReflectionTestUtils.setField(s, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(s, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(s, "bulkMinErrors", 3);
        ReflectionTestUtils.setField(s, "nowMs", mp.clockSupplier());
        return s;
    }

    /** Pod + kendi alarm servisi. */
    record PodWithMos(Pod pod, MonitoringOutageService mos) {
        SchedulerService scheduler() {
            return pod.scheduler();
        }
    }

    /** n. pod'u KENDİ alarm servisiyle kurar (servis pod'dan önce paylaşılan bağımlılık olarak verilir). */
    static PodWithMos podWithMos(MultiPodTestSupport mp, int n) {
        MonitoringOutageService m = mos(mp);
        mp.use(MonitoringOutageService.class, m);
        Pod p = mp.pod(n);
        m.setScheduledResultsFence(p.scheduler()::isSweepLeaderNow);
        p.events().listen(SweepLeadershipChangedEvent.class, m::onSweepLeadershipChanged);
        return new PodWithMos(p, m);
    }

    /** schedule() görevini HEMEN aynı iş parçacığında koşturur (teyit zinciri senkron biter). */
    static ScheduledThreadPoolExecutor immediate() {
        return new ScheduledThreadPoolExecutor(1) {
            @Override
            public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
                command.run();
                return null;
            }
        };
    }

    /**
     * schedule() görevlerini KUYRUKTA tutar; test {@link #drain()} / {@link #runRound()} ile elle koşturur. Hiç
     * koşturulmazsa zincir "yarıda öldürülmüş" olur (pod kapandı / devredildi).
     */
    static final class Manual extends ScheduledThreadPoolExecutor {
        private final List<Runnable> queued = new ArrayList<>();
        private final List<Long> delays = new ArrayList<>();

        Manual() {
            super(1);
        }

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            queued.add(command);
            delays.add(unit.toMillis(delay));
            return null;
        }

        synchronized int pending() {
            return queued.size();
        }

        /** Kuyruktakilerin planlanma gecikmeleri (ms) — sıradaki tur için. */
        synchronized List<Long> pendingDelays() {
            return List.copyOf(delays);
        }

        /** Kuyruktaki görevleri BİR tur koşturur (yeni planlananlar sonraki tura kalır); koşturulan sayısını döner. */
        int runRound() {
            List<Runnable> batch;
            synchronized (this) {
                batch = new ArrayList<>(queued);
                queued.clear();
                delays.clear();
            }
            batch.forEach(Runnable::run);
            return batch.size();
        }

        /** Kuyruk boşalana dek koşturur — en çok 50 tur. */
        void drain() {
            for (int i = 0; i < 50; i++) if (runRound() == 0) return;
        }
    }
}
