package com.sitemonitor.service.report;

import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DİNAMİK cron'lu raporların (aylık yönetici özeti, aylık envanter raporu) "şu an gerçekten tetik anı mı" denetimi (çok pod
 * R2a, MP-42, 2026-10-22).
 *
 * <p>Neden: {@code SchedulingConfigurer} tetikleyicisi bir sonraki çalışmayı ÖNCEKİ çalışma bitince o anki ayardan hesaplar.
 * Ayardaki cron sonradan değişirse (ör. 30'unda "ayın 1'i 09:00" → "ayın 2'si 09:00") zaten planlanmış eski tetik yine
 * gelir ve rapor eski saatte gider. Tetiğin PLANLI anında ({@link PlannedFire} — tetikleyicinin hesapladığı an) CANLI ifade
 * tetiklenmiyorsa iş koşmaz; tetikleyici bir sonraki çalışmayı canlı ifadeden yeniden hesaplar.
 *
 * <p><b>Duvar saati değil, planlı an (R2a inceleme REP-3, 2026-10-23):</b> ilk sürüm "canlı ifade son 61 sn'de tetiklendi mi"
 * diye DUVAR SAATİNE bakıyordu: 8 iş parçacıklı paylaşılan zamanlayıcı havuzu doluyken (10:00'da saatlik sertifika taraması
 * kilidi ≤ 60 sn bekler, diğer :00 işleri) 61 sn'den geç koşan tetik "zamanlama değişmiş" sanılıp atlanıyor ve telafisi
 * olmayan aylık envanter raporu o ay hiç gitmiyordu. Planlı an gecikmeden bağımsızdır; geç koşan tetik yine gönderir
 * (çift gönderimi küme cron turu önler). Planlı an bilinmiyorsa duvar saatiyle {@link #firesNow} (eski kural).
 */
public final class LiveCron {

    /** Planlı an bilinmezken geç gelen tetik payı (dolu zamanlayıcı havuzu, saat kayması). */
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

    /**
     * Canlı ifade PLANLI anda ({@code planned}, ± {@link #EARLY}) tetikleniyor mu — tetiğin ne kadar geç koştuğundan bağımsız
     * (R2a inceleme REP-3). Çözülemeyen ifade yerine {@code fallback}; o da çözülemezse {@code true} (iş koşar).
     */
    public static boolean firesAt(String cron, String fallback, ZoneId zone, Instant planned) {
        CronExpression expr = parse(cron);
        if (expr == null) expr = parse(fallback);
        if (expr == null) return true;
        ZonedDateTime next = expr.next(planned.minus(EARLY).atZone(zone));
        return next != null && !next.toInstant().isAfter(planned.plus(EARLY));
    }

    /**
     * Planlı tetiği değerlendirir: planlı an biliniyorsa {@link #firesAt}, değilse duvar saatiyle {@link #firesNow}.
     */
    public static boolean due(String cron, String fallback, ZoneId zone, Instant planned, Instant now) {
        return planned != null ? firesAt(cron, fallback, zone, planned) : firesNow(cron, fallback, zone, now);
    }

    /** Tetik planlı anından {@link #LOOKBACK}'ten geç mi koştu (yalnız günlük için). */
    public static boolean late(Instant planned, Instant now) {
        return planned != null && now != null && Duration.between(planned, now).compareTo(LOOKBACK) > 0;
    }

    /**
     * Tetikleyicinin SON hesapladığı planlı anı tutar. Spring'in {@code ReschedulingRunnable}'ı sıradaki çalışmayı görev
     * BİTTİKTEN sonra hesaplar — koşan görev her zaman son hesaplanan anın görevidir.
     */
    public static final class PlannedFire {
        private final AtomicReference<Instant> last = new AtomicReference<>();

        /** Tetikleyiciyi sarar: döndürdüğü her anı kaydeder (null dahil). */
        public Trigger wrap(Trigger trigger) {
            return ctx -> {
                Instant next = trigger.nextExecution(ctx);
                last.set(next);
                return next;
            };
        }

        /** Son hesaplanan planlı an (henüz hesaplanmadıysa {@code null}). */
        public Instant last() {
            return last.get();
        }
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
