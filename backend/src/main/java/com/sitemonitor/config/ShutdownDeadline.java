package com.sitemonitor.config;

import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Yok etme anındaki havuz beklemelerinin ORTAK süresi (2026-10-21, çok pod R1 inceleme D-1).
 *
 * <p>{@code @PreDestroy} / havuz yok etme beklemeleri SIRAYLA koşar: bildirim havuzu 20 sn + giriş postası havuzu 20 sn + push
 * 10 + kodla giriş 10 + e-posta yeniden deneme 5 + olay postası 5 + elle kontrol havuzu 5 ≈ 75 sn — yükte pod
 * {@code terminationGracePeriodSeconds} içinde bitmiyor, Kubernetes SIGKILL'i sonraki kancalardan (push kirasının bırakılması,
 * 421 FAILED yazımı) önce geliyordu. Artık her bekleme {@code min(kendi tavanı, kalan ortak süre)} kadar bekler
 * ({@link #waitMs}); ortak süre ({@link #DESTROY_BUDGET_MS}) İLK yok etme beklemesiyle başlar, toplam yok etme beklemesi bu
 * süreyi aşmaz. Bildirim havuzlarının asıl boşaltması bundan ÖNCE, {@code ContextClosedEvent}'te ({@link NotifyExecutorDrain},
 * ≤ {@link WebConfig#NOTIFY_AWAIT_SECONDS} sn) yapılır; zamanlanmış görev havuzu ve aşamalar kendi özelliklerindedir —
 * {@code ShutdownBudgetGateTest} hepsini toplar.
 *
 * <p>Yalnız JVM GERÇEKTEN kapanırken etkindir ({@code ContextClosedEvent} sırasında kapanış kancası kaydı reddediliyorsa): testte
 * kapatılan bağlamlar ve elle kurulan nesneler kendi tavanlarıyla bekler — davranış eskisiyle aynı.
 */
@Component
public class ShutdownDeadline {

    /** Yok etme anındaki TÜM havuz beklemelerinin ortak üst sınırı. */
    public static final long DESTROY_BUDGET_MS = 10_000L;

    private static final AtomicReference<ShutdownDeadline> ACTIVE = new AtomicReference<>();

    private final AtomicLong startNanos = new AtomicLong(Long.MIN_VALUE);

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        if (jvmShuttingDown()) ACTIVE.set(this);
    }

    /**
     * Bu beklemenin süresi (ms): kapanış sürerken {@code min(capMs, kalan ortak süre)} (ilk çağrı ortak süreyi başlatır), aksi
     * hâlde {@code capMs}.
     */
    public static long waitMs(long capMs) {
        ShutdownDeadline d = ACTIVE.get();
        return d == null ? Math.max(0, capMs) : d.remainingMs(capMs, System.nanoTime());
    }

    /** Saf hesap (test): ortak süre ilk çağrıda başlar; kalan süre ve tavanın küçüğü, en az 0. */
    public long remainingMs(long capMs, long nowNanos) {
        startNanos.compareAndSet(Long.MIN_VALUE, nowNanos);
        long used = Math.max(0, (nowNanos - startNanos.get()) / 1_000_000L);
        return Math.max(0, Math.min(Math.max(0, capMs), DESTROY_BUDGET_MS - used));
    }

    /** JVM kapanıyor mu (kapanış kancası kaydı reddediliyor mu)? */
    static boolean jvmShuttingDown() {
        Thread probe = new Thread(() -> { });
        try {
            Runtime.getRuntime().addShutdownHook(probe);
            Runtime.getRuntime().removeShutdownHook(probe);
            return false;
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** Test dikişi: etkin örneği ayarlar (null = kapalı). Başka paketlerin testleri de kullanır. */
    public static void activate(ShutdownDeadline d) {
        ACTIVE.set(d);
    }
}
