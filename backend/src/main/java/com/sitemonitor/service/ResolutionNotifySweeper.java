package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Kaybolan "ÇÖZÜLDÜ" postalarını BİR kez gönderir (2026-10-21, çok pod R1 / MP-07).
 *
 * <p>Alarm kapanırken çözüm bildirimi eşzamansız bir işe verilir; iş pod durdurulurken (yayılım, küçülme, tahliye) ya da
 * havuz kapanmışken düşerse posta sonsuza dek gitmiyordu — açılışın aksine hiçbir yol onu yeniden algılamaz. Kapanış artık
 * kalıcı bir işaret yazar ({@code alert_events.resolution_notify = PENDING}, çözüm kapısıyla aynı koşullu UPDATE'te); bu iş
 * her {@code site.monitor.notify.resolution-sweep-ms} (60 sn) bir, kümede TEK pod'da ({@code scheduler_lock} {@value #LOCK}),
 * 2 dk'dan eski PENDING satırları sahiplenip gönderir ({@link EscalationService#deliverPendingResolutions}). Sahiplenme
 * koşullu UPDATE'tir: eşzamansız iş ile süpürücü ya da iki süpürücü aynı satırı iki kez göndermez. Yarıda kalmış gönderim
 * (SENDING 10 dk) UNKNOWN olur ve yeniden GÖNDERİLMEZ; 24 saatte gönderilemeyen EXPIRED olur — ikisi de bildirim günlüğünde.
 */
@Slf4j
@Component
public class ResolutionNotifySweeper {

    /** Kilit adı — KALICI (yayından sonra değiştirilmez). */
    public static final String LOCK = "resolution-notify-sweep";

    private final EscalationService escalationService;

    @Autowired(required = false)
    private SchedulerService scheduler;

    public ResolutionNotifySweeper(EscalationService escalationService) {
        this.escalationService = escalationService;
    }

    /** Test dikişi. */
    void setScheduler(SchedulerService scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${site.monitor.notify.resolution-sweep-ms:60000}",
               initialDelayString = "${site.monitor.notify.resolution-sweep-initial-ms:150000}")
    public void scheduledSweep() {
        try {
            if (scheduler == null) {
                sweep(Instant.now());
            } else {
                scheduler.callWithSchedulerLock(LOCK, () -> sweep(Instant.now()));
            }
        } catch (Exception e) {
            log.warn("Çözüm bildirimi süpürmesi atlandı (sonraki turda yeniden denenir): {}", e.getMessage());
        }
    }

    /** Bir tur — gönderilen çözüm bildirimi sayısı. */
    int sweep(Instant now) {
        int n = escalationService.deliverPendingResolutions(now);
        if (n > 0) log.info("Çözüm bildirimi süpürmesi: {} bekleyen çözüm postası gönderildi", n);
        return n;
    }
}
