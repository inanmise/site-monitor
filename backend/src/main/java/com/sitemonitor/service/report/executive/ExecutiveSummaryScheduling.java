package com.sitemonitor.service.report.executive;

import com.sitemonitor.service.SchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;

/**
 * Aylık yönetici özetinin DİNAMİK zamanlaması — {@code CertInventoryReportScheduling} deseni: cron ifadesi her hesapta
 * CANLI ayardan okunur (Ayarlar'dan değişince yeniden başlatma gerekmez), geçersizse varsayılana düşülür (null "bir daha
 * asla çalışma" demektir). İki görev, ikisi de {@link SchedulerService#runWithSchedulerLock} ile TEK pod'da:
 * <ol>
 *   <li>planlı tetik (varsayılan ayın 1'i 09:00 Europe/Istanbul) → {@link ExecutiveSummaryDeliveryService#runScheduled};</li>
 *   <li>saatlik telafi (her saatin 17. dakikası) → {@link ExecutiveSummaryDeliveryService#catchUp} — pod kapalıyken kaçan
 *       ya da başarısız olan ayı {@value ExecutiveSummaryDeliveryService#CATCH_UP_HOURS} saat içinde tamamlar. Takım
 *       özetleri (2026-10-10) aynı tetiklerle, kurum özetinden sonra gider.</li>
 * </ol>
 * Asıl "tam bir kez" kapısı kilit değil, ay kaydının UNIQUE talebidir (kilit kaçsa bile ikinci posta gitmez).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ExecutiveSummaryScheduling implements SchedulingConfigurer {

    static final String LOCK = "executive-summary";
    static final String CATCH_UP_CRON = "0 17 * * * *";

    private final ExecutiveSummaryDeliveryService delivery;
    private final ExecutiveSummarySettings settings;
    private final SchedulerService schedulerService;
    private final ExecutiveSummaryTeamService teamService;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
                () -> schedulerService.runWithSchedulerLock(LOCK, delivery::runScheduled),
                context -> {
                    String expr = settings.cron();
                    try {
                        return new CronTrigger(expr, ExecutiveSummaryContext.IST).nextExecution(context);
                    } catch (Exception e) {
                        log.error("Aylık yönetici özeti zamanlaması geçersiz ({}) — varsayılana dönülüyor: {}", expr, e.getMessage());
                        try {
                            return new CronTrigger(ExecutiveSummarySettings.DEFAULT_CRON, ExecutiveSummaryContext.IST)
                                    .nextExecution(context);
                        } catch (Exception fatal) {
                            return (Instant) null;
                        }
                    }
                });
        // Kurum özeti ve bütün takım özetleri kapalıyken (varsayılan) telafi kilide bile dokunmaz — saatlik boş DB
        // yazımı olmasın (takım kapısı tek sayım sorgusu).
        registrar.addTriggerTask(
                () -> {
                    if (settings.enabled() || teamService.anyEnabled()) {
                        schedulerService.runWithSchedulerLock(LOCK, delivery::catchUp);
                    }
                },
                new CronTrigger(CATCH_UP_CRON, ExecutiveSummaryContext.IST));
    }
}
