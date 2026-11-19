package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Süreli alarm onayının süre bitimi (2026-11-19, kullanıcı kararı: "süre dolunca alarm hâlâ açıksa onay kalkar ve hemen
 * hatırlatma gider"). Her {@code site.monitor.alerts.ack-expiry-tick-ms} (60 sn) bir, kümede TEK pod'da
 * ({@code scheduler_lock} {@value #LOCK}, yenilenen 2 dk'lık kilit) süresi dolan onayları düşürür
 * ({@link EscalationService#expireDueAcknowledgements}). Satır başına koşullu sahiplenme iki pod'un aynı onayı iki kez
 * düşürmesini / hatırlatmasını engeller; kilit yalnız boşa iş yapmamak içindir. Kapanış başladıktan sonra kilit alınmaz
 * ({@code SchedulerService}). Bir tur en çok {@link EscalationService#ACK_EXPIRY_BATCH} satır işler; kalan sonraki turda.
 */
@Slf4j
@Component
public class AckExpirySweeper {

    /** Kilit adı — KALICI (yayından sonra değiştirilmez). */
    public static final String LOCK = "ack-expiry";

    private final EscalationService escalationService;

    @Autowired(required = false)
    private SchedulerService scheduler;

    public AckExpirySweeper(EscalationService escalationService) {
        this.escalationService = escalationService;
    }

    /** Test dikişi. */
    void setScheduler(SchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${site.monitor.alerts.ack-expiry-tick-ms:60000}",
               initialDelayString = "${site.monitor.alerts.ack-expiry-initial-ms:90000}")
    public void scheduledSweep() {
        try {
            if (scheduler == null) {
                sweep(Instant.now());
            } else {
                scheduler.callWithSchedulerLock(LOCK, Duration.ofMinutes(2), () -> sweep(Instant.now()));
            }
        } catch (Exception e) {
            log.warn("Süreli onay süpürmesi atlandı (sonraki turda yeniden denenir): {}", e.getMessage());
        }
    }

    /** Bir tur — düşürülen onay sayısı. */
    int sweep(Instant now) {
        return escalationService.expireDueAcknowledgements(now);
    }
}
