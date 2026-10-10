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

    @Scheduled(cron = "${site.monitor.data-quality.snapshot-cron:0 25 * * * *}", zone = "Europe/Istanbul")
    public void tick() {
        if (!enabled) return;
        Instant now = Instant.now();
        try {
            if (service.snapshotWritten(now)) return;
        } catch (Exception e) {
            log.debug("Veri kalitesi görüntü ön kapısı okunamadı (tablo henüz yok?): {}", e.getMessage());
            return;
        }
        schedulerService.runWithSchedulerLock(LOCK_NAME, () -> {
            if (!service.snapshotWritten(now)) service.writeDailySnapshot(now);
        });
    }
}
