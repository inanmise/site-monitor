package com.sitemonitor.service.quality;

import com.sitemonitor.service.SchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Veri kalitesi puanının GÜNLÜK görüntüsü — eğilim çizgisi için (2026-10-10).
 *
 * <p>Saatlik tık ({@code site.monitor.data-quality.snapshot-cron}, varsayılan her saatin 25. dakikası, İstanbul):
 * bugünün kurum satırı zaten yazılmışsa TEK küçük sorguyla, kilit almadan çıkar. Değilse iş
 * {@code scheduler_lock} ({@value #LOCK_NAME}) altında koşar — çok pod'lu kurulumda tek pod hesaplar; yazım ayrıca
 * {@code ON CONFLICT DO NOTHING} ile gün × kova başına tek satırdır (kilit süresi dolup ikinci pod koşsa da çift
 * satır olmaz). Yeni kurulumda ilk nokta dağıtımdan sonraki ilk saat içinde oluşur. Bildirim/alarm üretmez;
 * {@code MonitoringOutageService}'i beslemez (tarama lideri kapısı gerekmez).
 */
@Slf4j
@Component
public class DataQualitySnapshotJob {

    public static final String LOCK_NAME = "data-quality-snapshot";

    private final DataQualityService service;
    private final SchedulerService schedulerService;
    private final boolean enabled;

    public DataQualitySnapshotJob(DataQualityService service, SchedulerService schedulerService,
                                  @Value("${site.monitor.data-quality.snapshot-enabled:true}") boolean enabled) {
        this.service = service;
        this.schedulerService = schedulerService;
        this.enabled = enabled;
    }

    /** Zamanlanmış tetiğin cron ifadesi (annotation ve yan iş kaydı aynı sabiti okur). */
    static final String CRON = "${site.monitor.data-quality.snapshot-cron:0 25 * * * *}";

    /**
     * Saatlik tık. R3-1 3e (2026-10-23): yazım gövdesi pod'un yan iş koşucusunda; tarama lideri başka canlı pod varken tetiği
     * erteler ve ertelenen denemede görüntü başka pod'da yazıldıysa ({@code snapshotWritten}) hiçbir şey yapmaz.
     */
    @Scheduled(cron = CRON, zone = "Europe/Istanbul")
    public void tick() {
        if (!enabled) return;
        Instant now = Instant.now();
        try {
            if (service.snapshotWritten(now)) return;
        } catch (Exception e) {
            log.debug("Veri kalitesi görüntü ön kapısı okunamadı (tablo henüz yok?): {}", e.getMessage());
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
