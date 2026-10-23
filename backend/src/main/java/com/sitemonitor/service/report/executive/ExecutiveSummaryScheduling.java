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

    /** Koşan planlı tetiğin PLANLI anı (R2a inceleme REP-3: gecikmeden bağımsız karar). */
    private final com.sitemonitor.service.report.LiveCron.PlannedFire plannedFire =
            new com.sitemonitor.service.report.LiveCron.PlannedFire();

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
                this::fireScheduled,
                plannedFire.wrap(context -> {
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
                }));
        // Kurum özeti ve bütün takım özetleri kapalıyken (varsayılan) telafi kilide bile dokunmaz — saatlik boş DB
        // yazımı olmasın (takım kapısı tek sayım sorgusu).
        registrar.addTriggerTask(
                () -> {
                    if (settings.enabled() || teamService.anyEnabled()) fireCatchUp();
                },
                new CronTrigger(CATCH_UP_CRON, ExecutiveSummaryContext.IST));
    }

    /**
     * Planlı tetik görevi (R3-1 3e, 2026-10-23): PLANLI an tetik anında yakalanır (tetikleyici sıradaki anı görev DÖNÜNCE
     * hesaplar — ertelenen / koşucuya verilen gövde planlı anı sonradan okuyamaz), gövde kilit altında {@link #scheduledTick}.
     * Tarama lideri başka canlı pod varken tetiği erteler; ertelenen denemede bu tetiğin turu ({@value #SCHEDULED_ROUND}) başka
     * pod'da alındıysa gövde koşmaz. Gövde pod'un yan iş koşucusunda.
     */
    void fireScheduled() {
        Instant planned = plannedFire.last();
        Runnable body = () -> schedulerService.runWithSchedulerLock(LOCK, () -> scheduledTick(planned, Instant.now()));
        String zone = ExecutiveSummaryContext.IST.getId();
        com.sitemonitor.service.schedule.SideJob job = com.sitemonitor.service.schedule.SideJob.cron(SCHEDULED_ROUND, true,
                null, liveCron(), zone, () -> schedulerService.cronRoundTaken(SCHEDULED_ROUND, liveCron(), zone), body);
        if (!schedulerService.offloadSideJob(job)) body.run();
    }

    /**
     * Saatlik telafi görevi (R3-1 3e): tarama lideri, telafi turunu ({@value #CATCH_UP_ROUND}) son 2 periyotta başka pod
     * aldıysa bırakır; gövde (kilit + tur) pod'un yan iş koşucusunda.
     */
    void fireCatchUp() {
        if (schedulerService.leaderYieldsRound(CATCH_UP_ROUND, CATCH_UP_ROUND_MS)) return;
        Runnable body = () -> schedulerService.runWithSchedulerLock(LOCK, this::catchUpTick);
        if (!schedulerService.offloadSideJob(com.sitemonitor.service.schedule.SideJob.heavy(CATCH_UP_ROUND, body))) body.run();
    }

    private String liveCron() {
        return com.sitemonitor.service.report.LiveCron.effective(settings.cron(), ExecutiveSummarySettings.DEFAULT_CRON);
    }

    /**
     * Planlı tetik (kilit altında): CANLI cron tetiğin PLANLI anında ({@code planned}; bilinmiyorsa duvar saati) tetiklenmiyorsa
     * (zamanlama tetik planlandıktan sonra değişti) iş koşmaz; tetik ne kadar geç koşarsa koşsun karar planlı ana göredir
     * (R2a inceleme REP-3); tetik küme genelinde bir kez ({@value #SCHEDULED_ROUND} cron turu).
     */
    void scheduledTick(Instant planned, Instant now) {
        String cron = com.sitemonitor.service.report.LiveCron.effective(settings.cron(), ExecutiveSummarySettings.DEFAULT_CRON);
        if (!com.sitemonitor.service.report.LiveCron.due(cron, ExecutiveSummarySettings.DEFAULT_CRON,
                ExecutiveSummaryContext.IST, planned, now)) {
            log.info("Aylık yönetici özeti: planlı tetik ({}) canlı zamanlamaya ({}) uymuyor — zamanlama değişmiş, bu tetikte "
                    + "gönderilmedi", planned, cron);
            return;
        }
        if (com.sitemonitor.service.report.LiveCron.late(planned, now)) {
            log.warn("Aylık yönetici özeti: planlı tetik ({}) {} sn geç koştu (dolu zamanlayıcı havuzu) — yine de gönderiliyor",
                    planned, java.time.Duration.between(planned, now).toSeconds());
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
