package com.sitemonitor.service.dbgrowth;

import com.sitemonitor.service.SchedulerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * Veritabanı büyümesinin GÜNLÜK görüntüsü (2026-11-13) — {@code DataQualitySnapshotJob} deseni.
 *
 * <p>Saatlik tık ({@value #CRON_DEFAULT}, İstanbul). Yalnız İstanbul saatiyle {@value #EARLIEST_HOUR}:00'dan sonra (03:00 gece
 * temizliği ve 04:40 indeks bakımı bitmiş olsun — görüntü günün "temizlik sonrası" boyutunu tutar). PostgreSQL değilse
 * (H2 yerel geliştirme) hiçbir şey yapmaz; tür okunamadıysa sonraki tık dener. Bugünün satırı yazılmışsa TEK küçük sorguyla,
 * kilit almadan çıkar. Değilse iş {@code scheduler_lock} ({@value #LOCK_NAME}) altında yan iş koşucusunda koşar — çok pod'lu
 * kurulumda tek pod yazar; yazım ayrıca {@code ON CONFLICT DO NOTHING} (gün × tablo tek satır). Bildirim / alarm üretmez.
 */
@Slf4j
@Component
public class DbSizeSnapshotJob {

    public static final String LOCK_NAME = "db-size-snapshot";
    static final String CRON_DEFAULT = "0 35 * * * *";
    static final String CRON = "${site.monitor.db.size-snapshot-cron:" + CRON_DEFAULT + "}";
    static final String ZONE = "Europe/Istanbul";
    static final int EARLIEST_HOUR = 5;

    private final DbGrowthService service;
    private final SchedulerService schedulerService;
    private final boolean enabled;
    private Supplier<Instant> clock = Instant::now;

    public DbSizeSnapshotJob(DbGrowthService service, SchedulerService schedulerService,
                             @Value("${site.monitor.db.size-snapshot-enabled:true}") boolean enabled) {
        this.service = service;
        this.schedulerService = schedulerService;
        this.enabled = enabled;
    }

    /** Test: saat. */
    void setClock(Supplier<Instant> c) { this.clock = c; }

    @Scheduled(cron = CRON, zone = ZONE)
    public void tick() {
        if (!enabled) return;
        Instant now = clock.get();
        if (now.atZone(ZoneId.of(ZONE)).getHour() < EARLIEST_HOUR) return;
        try {
            if (!Boolean.TRUE.equals(service.supported())) return;
            if (service.snapshotWritten(now)) return;
        } catch (Exception e) {
            log.debug("Veritabanı büyümesi görüntü ön kapısı okunamadı (tablo henüz yok?): {}", e.getMessage());
            return;
        }
        Runnable body = () -> schedulerService.runWithSchedulerLock(LOCK_NAME, () -> {
            try {
                // Kapanış başladıysa tablo döngüsü yarıda bırakır (sonraki tık / pod yeniden dener)
                if (!service.snapshotWritten(now)) service.writeSnapshot(now, schedulerService::sideJobsStopping);
            } catch (RuntimeException e) {
                log.warn("Veritabanı büyümesi günlük görüntüsü yazılamadı — sonraki saat yeniden denenecek: {}", e.toString());
            }
        });
        com.sitemonitor.service.schedule.SideJob job = com.sitemonitor.service.schedule.SideJob.cron(LOCK_NAME, true, null,
                CRON, ZONE, () -> service.snapshotWritten(now), body);
        if (!schedulerService.offloadSideJob(job)) body.run();
    }
}
