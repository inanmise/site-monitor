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
 *
 * <p><b>Çok pod R2a (MP-42, 2026-10-22):</b> kilit iş bitince bırakılır; geç tetiklenen ikinci pod (dolu zamanlayıcı havuzu)
 * aynı tetiği yeniden koşturup ayın deneme hakkını boşa harcıyordu. Planlı tetik artık küme genelinde BİR KEZ
 * ({@link SchedulerService#claimCronRound} — tur {@value #SCHEDULED_ROUND}) ve yalnız CANLI cron bu dakikada tetikleniyorsa
 * ({@link com.sitemonitor.service.report.LiveCron}: ayardan sonra değişen zamanlamada eski planlı tetik göndermez); telafi
 * saatte bir kez ({@link SchedulerService#claimRound} — tur {@value #CATCH_UP_ROUND}, iki aşamalı). Tur adları KALICI.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ExecutiveSummaryScheduling implements SchedulingConfigurer {

    static final String LOCK = "executive-summary";
    static final String CATCH_UP_CRON = "0 17 * * * *";
    /** Planlı tetiğin küme turu (R2a, MP-42) — KALICI ad. */
    static final String SCHEDULED_ROUND = "executive-summary";
    /** Saatlik telafinin küme turu (R2a, MP-42) — KALICI ad; aralık bir saatten kısa ki her saatin tetiği vadeli olsun. */
    static final String CATCH_UP_ROUND = "executive-summary-catchup";
    static final long CATCH_UP_ROUND_MS = 55L * 60_000L;

    private final ExecutiveSummaryDeliveryService delivery;
    private final ExecutiveSummarySettings settings;
    private final SchedulerService schedulerService;
    private final ExecutiveSummaryTeamService teamService;

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
                () -> schedulerService.runWithSchedulerLock(LOCK, () -> scheduledTick(Instant.now())),
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
                        schedulerService.runWithSchedulerLock(LOCK, this::catchUpTick);
                    }
                },
                new CronTrigger(CATCH_UP_CRON, ExecutiveSummaryContext.IST));
    }

    /**
     * Planlı tetik (kilit altında): CANLI cron bu dakikada tetiklenmiyorsa (zamanlama tetik planlandıktan sonra değişti) iş
     * koşmaz; tetik küme genelinde bir kez ({@value #SCHEDULED_ROUND} cron turu).
     */
    void scheduledTick(Instant now) {
        String cron = com.sitemonitor.service.report.LiveCron.effective(settings.cron(), ExecutiveSummarySettings.DEFAULT_CRON);
        if (!com.sitemonitor.service.report.LiveCron.firesNow(cron, ExecutiveSummarySettings.DEFAULT_CRON,
                ExecutiveSummaryContext.IST, now)) {
            log.info("Aylık yönetici özeti: planlı tetik canlı zamanlamaya ({}) uymuyor — zamanlama değişmiş, bu tetikte gönderilmedi",
                    cron);
            return;
        }
        if (!schedulerService.claimCronRound(SCHEDULED_ROUND, cron, ExecutiveSummaryContext.IST.getId())) {
            log.debug("Aylık yönetici özeti: bu planlı tetik başka bir pod'da koştu");
            return;
        }
        delivery.runScheduled();
    }

    /** Saatlik telafi (kilit altında): küme genelinde saatte bir ({@value #CATCH_UP_ROUND}); hatada tur tamamlanmaz. */
    void catchUpTick() {
        if (!schedulerService.claimRound(CATCH_UP_ROUND, CATCH_UP_ROUND_MS)) {
            log.debug("Aylık yönetici özeti telafisi: bu saatin turu başka bir pod'da koştu");
            return;
        }
        delivery.catchUp();
        schedulerService.completeRound(CATCH_UP_ROUND);
    }
}
