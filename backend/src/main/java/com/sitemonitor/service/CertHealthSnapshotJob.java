package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Sertifika sağlığının GÜNLÜK görüntüsü — İstatistikler 90 günlük serisi için (2026-11-13). {@code DataQualitySnapshotJob}'un
 * birebir deseni: saatlik tık ({@code site.monitor.cert-health.snapshot-cron}, varsayılan her saatin 35. dakikası, İstanbul);
 * bugünün kurum satırı varsa TEK küçük sorguyla, kilit almadan çıkar. Değilse iş pod'un yan iş koşucusunda,
 * {@code scheduler_lock} ({@value #LOCK_NAME}) altında koşar (çok pod'da tek pod hesaplar); yazım ayrıca
 * {@code ON CONFLICT DO NOTHING} ile gün × kova başına tek satırdır. Bildirim / alarm üretmez.
 */
@Slf4j
@Component
public class CertHealthSnapshotJob {

    public static final String LOCK_NAME = "cert-health-snapshot";
    static final String CRON = "${site.monitor.cert-health.snapshot-cron:0 35 * * * *}";

    private final CertHealthDailyService service;
    private final SchedulerService schedulerService;
    private final boolean enabled;

    public CertHealthSnapshotJob(CertHealthDailyService service, SchedulerService schedulerService,
                                 @Value("${site.monitor.cert-health.snapshot-enabled:true}") boolean enabled) {
        this.service = service;
        this.schedulerService = schedulerService;
        this.enabled = enabled;
    }

    @Scheduled(cron = CRON, zone = "Europe/Istanbul")
    public void tick() {
        if (!enabled) return;
        Instant now = Instant.now();
        try {
            if (service.snapshotWritten(now)) return;
        } catch (Exception e) {
            log.debug("Sertifika sağlığı görüntü ön kapısı okunamadı (tablo henüz yok?): {}", e.getMessage());
            return;
        }
        Runnable body = () -> schedulerService.runWithSchedulerLock(LOCK_NAME, () -> {
            if (!service.snapshotWritten(now)) service.writeDailySnapshot(now);
        });
        com.sitemonitor.service.schedule.SideJob job = com.sitemonitor.service.schedule.SideJob.cron(LOCK_NAME, true, null,
                CRON, "Europe/Istanbul", () -> service.snapshotWritten(now), body);
        if (!schedulerService.offloadSideJob(job)) body.run();
    }
}
