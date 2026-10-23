package com.sitemonitor.service.report;

import com.sitemonitor.service.SchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Aylık envanter raporunun DİNAMİK zamanlaması.
 *
 * <p>Neden {@code @Scheduled} değil: annotation'daki cron ifadesi uygulama açılışında BİR KEZ
 * çözülür. Zamanlamanın Ayarlar sayfasından değiştirilebilmesi isteniyor; {@code @Scheduled} ile
 * bu ancak yeniden başlatmayla etkili olurdu ve kullanıcı "kaydettim ama değişmedi" derdi.
 * Tetikleyici, sıradaki çalışma zamanını HER SEFERİNDE canlı ayardan hesaplar.
 *
 * <p>Kilit: çok-pod'da tek pod göndersin diye {@link SchedulerService#runWithSchedulerLock}
 * kullanılır — diğer cron işleriyle aynı mekanizma. Ek güvence olarak servis içinde yıl+ay
 * idempotensi var; kilit kaçsa bile ikinci mail gitmez.
 *
 * <p><b>Çok pod R2a (MP-42, 2026-10-22):</b> kilit iş bitince bırakılır ve gönderim işlemi COMMIT olmadan "bugün gönderildi"
 * kapısı görünmez — geç tetiklenen ikinci pod raporu İKİNCİ kez yollayabiliyordu. Tetik artık küme genelinde BİR KEZ
 * ({@link SchedulerService#claimCronRound}, tur {@value #ROUND}) ve yalnız CANLI cron bu dakikada tetikleniyorsa
 * ({@link LiveCron} — ayardan sonra değişen zamanlamada eski planlı tetik göndermez). Tur adı KALICI.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CertInventoryReportScheduling implements SchedulingConfigurer {

    static final String LOCK = "cert-inventory-report";
    /** Planlı tetiğin küme turu (R2a, MP-42) — KALICI ad. */
    static final String ROUND = "cert-inventory-report";
    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");

    private final CertificateInventoryReportService reportService;
    private final SchedulerService schedulerService;

    @Value("${site.monitor.cert-inventory-report.cron:0 0 10 * * FRIL}")
    private String fallbackCron;

    /** Koşan tetiğin PLANLI anı (R2a inceleme REP-3: gecikmeden bağımsız karar). */
    private final LiveCron.PlannedFire plannedFire = new LiveCron.PlannedFire();

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
                this::fire,
                plannedFire.wrap(context -> {
                    String expr = reportService.cron();
                    try {
                        return new CronTrigger(expr, IST).nextExecution(context);
                    } catch (Exception e) {
                        // Geçersiz ifadede null DÖNDÜRME: null "bir daha asla çalışma" demektir ve
                        // rapor sessizce ölürdü. Varsayılana düşüp çalışmaya devam ediyoruz.
                        log.error("Aylık envanter raporu zamanlaması geçersiz ({}) — varsayılana dönülüyor ({}): {}",
                                expr, fallbackCron, e.getMessage());
                        try {
                            return new CronTrigger(fallbackCron, IST).nextExecution(context);
                        } catch (Exception fatal) {
                            log.error("Varsayılan zamanlama da çözümlenemedi: {}", fatal.getMessage());
                            return (Instant) null;
                        }
                    }
                }));
    }

    /**
     * Tetik görevi (R3-1 3e, 2026-10-23): PLANLI an tetik anında yakalanır (tetikleyici sıradaki anı görev DÖNÜNCE hesaplar —
     * ertelenen / koşucuya verilen gövde planlı anı sonradan okuyamaz), gövde kilit altında {@link #scheduledTick}. Tarama
     * lideri başka canlı pod varken tetiği erteler; ertelenen denemede bu tetiğin turu ({@value #ROUND}) başka pod'da
     * alındıysa gövde koşmaz. Gövde pod'un yan iş koşucusunda.
     */
    void fire() {
        Instant planned = plannedFire.last();
        Runnable body = () -> schedulerService.runWithSchedulerLock(LOCK, () -> scheduledTick(planned, Instant.now()));
        com.sitemonitor.service.schedule.SideJob job = com.sitemonitor.service.schedule.SideJob.cron(ROUND, true, null,
                LiveCron.effective(reportService.cron(), fallbackCron), IST.getId(),
                () -> schedulerService.cronRoundTaken(ROUND, LiveCron.effective(reportService.cron(), fallbackCron),
                        IST.getId()),
                body);
        if (!schedulerService.offloadSideJob(job)) body.run();
    }

    /**
     * Planlı tetik (kilit altında): canlı cron tetiğin PLANLI anında ({@code planned}; bilinmiyorsa duvar saati) tetikleniyorsa
     * ve tur bu pod'a düştüyse gönderir — tetik ne kadar geç koşarsa koşsun (R2a inceleme REP-3).
     */
    void scheduledTick(Instant planned, Instant now) {
        String cron = LiveCron.effective(reportService.cron(), fallbackCron);
        if (!LiveCron.due(cron, fallbackCron, IST, planned, now)) {
            log.info("Aylık envanter raporu: planlı tetik ({}) canlı zamanlamaya ({}) uymuyor — zamanlama değişmiş, bu tetikte "
                    + "gönderilmedi; sıradaki tetik yeni zamanlamadan", planned, cron);
            return;
        }
        if (LiveCron.late(planned, now)) {
            log.warn("Aylık envanter raporu: planlı tetik ({}) {} sn geç koştu (dolu zamanlayıcı havuzu) — yine de gönderiliyor",
                    planned, java.time.Duration.between(planned, now).toSeconds());
        }
        if (!schedulerService.claimCronRound(ROUND, cron, IST.getId())) {
            log.debug("Aylık envanter raporu: bu planlı tetik başka bir pod'da koştu");
            return;
        }
        reportService.sendMonthlyReport(false);
    }
}
