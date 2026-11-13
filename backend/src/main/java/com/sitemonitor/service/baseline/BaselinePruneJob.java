package com.sitemonitor.service.baseline;

import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.schedule.SideJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dinamik taban çizgisi örneklerinin GÜNLÜK budaması (hata taraması 2026-11-13 #2 — {@code BaselineService.prune} hiç
 * çağrılmıyordu, tablo sınırsız büyüyordu). Her gün {@value #CRON} (İstanbul) tetiği; gövde pod'un yan iş koşucusunda
 * ({@code SideJob}, lider ertelemesi dâhil), {@code scheduler_lock} ({@value #NAME}) altında ve küme cron turu
 * ({@code round:}{@value #NAME}) alındıysa koşar — iki pod aynı tetikte budamaz. Budama izleme başına en yeni
 * {@link BaselineSampleStore#KEEP_PER_MONITOR} satırı tutar; idempotenttir (ikinci koşu 0 siler), bu yüzden tur kaydı geçici
 * bir hatayla okunamazsa yine koşar (öğe korumalı cron turu). Ana anahtar kapalıyken de budar (örnekler saklı kalıyor).
 * Pod kapanırken ya da {@value #BUDGET_MS} ms bütçe dolunca yarıda bırakır; kalan ertesi gün budanır. Adlar KALICI.
 */
@Slf4j
@Component
public class BaselinePruneJob {

    /** Kilit, cron turu ve yan iş adı — KALICI. */
    public static final String NAME = "baseline-sample-prune";
    /** Günlük tetik (İstanbul) — gece temizliğinden ayrı, yoğun saat dışı. Sabit (yanlış değer açılışı düşürmesin). */
    static final String CRON = "0 45 4 * * *";
    static final String ZONE = "Europe/Istanbul";
    /** Tek koşunun zaman bütçesi (ms). */
    static final long BUDGET_MS = 10 * 60_000L;

    private final BaselineService baselineService;
    private final SchedulerService scheduler;
    private final boolean enabled;

    public BaselinePruneJob(BaselineService baselineService, SchedulerService scheduler,
                            @Value("${site.monitor.baseline.prune-enabled:true}") boolean enabled) {
        this.baselineService = baselineService;
        this.scheduler = scheduler;
        this.enabled = enabled;
    }

    @Scheduled(cron = CRON, zone = ZONE)
    public void tick() {
        if (!enabled) return;
        Runnable body = this::runLocked;
        SideJob job = SideJob.cron(NAME, true, NAME, CRON, ZONE, null, body);
        if (!scheduler.offloadSideJob(job)) body.run();
    }

    /** Kilit + cron turu altında tek budama (gövde; testler doğrudan çağırır). */
    void runLocked() {
        scheduler.runWithSchedulerLock(NAME, () -> {
            if (!scheduler.claimItemProtectedCronRound(NAME, CRON, ZONE)) return;
            long deadline = System.currentTimeMillis() + BUDGET_MS;
            BaselineSampleStore.PruneResult r = baselineService.prune(
                    () -> scheduler.sideJobsStopping() || System.currentTimeMillis() > deadline);
            if (r.deleted() > 0 || r.partial())
                log.info("Taban çizgisi örnekleri budandı: {} satır, {} izleme{}", r.deleted(), r.monitors(),
                        r.partial() ? " (yarıda kaldı — kalan ertesi gün)" : "");
        });
    }
}
