package com.sitemonitor.service.report;

import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * DİNAMİK cron'lu raporların (aylık yönetici özeti, aylık envanter raporu) "şu an gerçekten tetik anı mı" denetimi (çok pod
 * R2a, MP-42, 2026-10-22).
 *
 * <p>Neden: {@code SchedulingConfigurer} tetikleyicisi bir sonraki çalışmayı ÖNCEKİ çalışma bitince o anki ayardan hesaplar.
 * Ayardaki cron sonradan değişirse (ör. 30'unda "ayın 1'i 09:00" → "ayın 2'si 09:00") zaten planlanmış eski tetik yine
 * gelir ve rapor eski saatte gider. Tetik anında CANLI ifade bu dakikada (son {@link #LOOKBACK}) tetiklenmiyorsa iş koşmaz;
 * tetikleyici bir sonraki çalışmayı canlı ifadeden yeniden hesaplar.
 */
public final class LiveCron {

    /** Geç gelen tetik payı (dolu zamanlayıcı havuzu, saat kayması). */
    static final Duration LOOKBACK = Duration.ofSeconds(61);
    /** Tetik duvar saatinden birkaç ms ERKEN koşabilir. */
    static final Duration EARLY = Duration.ofSeconds(2);

    private LiveCron() { }

    /**
     * Canlı ifade {@code now}'ın son {@link #LOOKBACK}'inde (ya da {@link #EARLY} içinde) tetikleniyor mu. Çözülemeyen ifade
     * yerine {@code fallback} (tetikleyicinin kendi kuralı); o da çözülemezse {@code true} (eski davranış — iş koşar).
     */
    public static boolean firesNow(String cron, String fallback, ZoneId zone, Instant now) {
        CronExpression expr = parse(cron);
        if (expr == null) expr = parse(fallback);
        if (expr == null) return true;
        ZonedDateTime next = expr.next(now.minus(LOOKBACK).atZone(zone));
        return next != null && !next.toInstant().isAfter(now.plus(EARLY));
    }

    /** Geçerliyse çözülmüş ifade, değilse {@code cron} ya da {@code fallback} (cron turunun anahtarı için). */
    public static String effective(String cron, String fallback) {
        return parse(cron) != null ? cron : fallback;
    }

    private static CronExpression parse(String cron) {
        if (cron == null || cron.isBlank()) return null;
        try {
            return CronExpression.parse(cron);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
