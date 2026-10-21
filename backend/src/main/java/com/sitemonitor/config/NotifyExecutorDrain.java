package com.sitemonitor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bildirim havuzlarını kapanışın EN BAŞINDA boşaltır (2026-10-21, çok pod R1 / MP-07).
 *
 * <p>{@code ContextClosedEvent} kapanışın ilk adımıdır: bütün çekirdekler (depolar, {@code EntityManagerFactory}, SMTP)
 * hâlâ ayaktadır. Yok etme anındaki bekleme ({@code setAwaitTerminationSeconds}) tek başına yetmez — yok etme sırası
 * çekirdek kaydına bağlıdır ve JPA havuzdan ÖNCE kapanırsa bekleyen çözüm postası "EntityManagerFactory is closed" ile
 * düşer. Burada {@code notifyExecutor} ve {@code loginIssueMailExecutor} en çok {@link WebConfig#NOTIFY_AWAIT_SECONDS}
 * saniye, süren iş VE kuyruk bitene kadar beklenir; havuz KAPATILMAZ (bu arada gelen bildirim de kabul edilir, yok etme
 * anındaki bekleme onu bitirir). Süre dolarsa kalan iş sayısı WARN olur — çözüm postası kalıcı işaretten
 * ({@code alert_events.resolution_notify}) başka pod'un süpürücüsüyle gider.
 *
 * <p>Havuz yoksa (dilim testleri, {@code @WebMvcTest}) hiçbir şey yapmaz. Kapı: {@code NotifyExecutorShutdownTest}.
 */
@Slf4j
@Component
public class NotifyExecutorDrain {

    private final ObjectProvider<ThreadPoolTaskExecutor> notifyExecutor;
    private final ObjectProvider<ThreadPoolTaskExecutor> loginMailExecutor;
    private final ObjectProvider<com.sitemonitor.service.UserPushService> userPush;

    /** Bekleme sınırı (ms) — test kısaltır. */
    long maxWaitMs = WebConfig.NOTIFY_AWAIT_SECONDS * 1000L;

    public NotifyExecutorDrain(@Qualifier("notifyExecutor") ObjectProvider<ThreadPoolTaskExecutor> notifyExecutor,
                               @Qualifier("loginIssueMailExecutor") ObjectProvider<ThreadPoolTaskExecutor> loginMailExecutor,
                               ObjectProvider<com.sitemonitor.service.UserPushService> userPush) {
        this.notifyExecutor = notifyExecutor;
        this.loginMailExecutor = loginMailExecutor;
        this.userPush = userPush;
    }

    /**
     * Push worker'ı da AYNI süre içinde boşaltılır (inceleme N3): önce yeni tur durdurulur ({@code beginShutdown}), sonra süren
     * gönderim bildirim havuzlarıyla birlikte beklenir — ek süre yok (aynı {@link WebConfig#NOTIFY_AWAIT_SECONDS} sınırı), ortak
     * yok etme süresine kalmaz (önceki yok etme beklemeleri onu tüketince süren push SENT yazılmadan kesiliyordu).
     */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        List<ThreadPoolTaskExecutor> pools = new ArrayList<>(2);
        ThreadPoolTaskExecutor n = notifyExecutor.getIfAvailable();
        if (n != null) pools.add(n);
        ThreadPoolTaskExecutor l = loginMailExecutor.getIfAvailable();
        if (l != null) pools.add(l);
        com.sitemonitor.service.UserPushService push = userPush.getIfAvailable();
        java.util.function.IntSupplier pushInFlight = () -> 0;
        if (push != null) {
            try {
                push.beginShutdown();
                pushInFlight = push::inFlightBatches;
            } catch (RuntimeException e) {
                log.warn("Kapanış: push worker durdurulamadı: {}", e.getMessage());
            }
        }
        int left = drain(pools, pushInFlight, maxWaitMs);
        if (left > 0) {
            log.warn("Kapanış: bildirim havuzlarında / push worker'ında {} iş {} ms içinde bitmedi — çözüm postaları kalıcı işaretle, "
                    + "push'lar kirası dolunca başka pod'dan gider", left, maxWaitMs);
        }
    }

    static int drain(List<ThreadPoolTaskExecutor> pools, long maxWaitMs) {
        return drain(pools, () -> 0, maxWaitMs);
    }

    /**
     * Havuzlar boşalana (süren iş = 0 ve kuyruk boş; push için yalnız SÜREN tur — kuyrukta periyodik süpürme görevi hep durur)
     * ya da süre dolana dek bekler; kalan iş sayısını döner. Döngü süreyle sınırlı (100 ms adım) — kesilirse hemen döner.
     */
    static int drain(List<ThreadPoolTaskExecutor> pools, java.util.function.IntSupplier extra, long maxWaitMs) {
        long deadline = System.nanoTime() + Math.max(0, maxWaitMs) * 1_000_000L;
        while (true) {
            int pending = pending(pools) + safe(extra);
            if (pending == 0) return 0;
            if (System.nanoTime() >= deadline) return pending;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return pending(pools) + safe(extra);
            }
        }
    }

    private static int safe(java.util.function.IntSupplier s) {
        try {
            return Math.max(0, s.getAsInt());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int pending(List<ThreadPoolTaskExecutor> pools) {
        int n = 0;
        for (ThreadPoolTaskExecutor p : pools) {
            try {
                ThreadPoolExecutor tpe = p.getThreadPoolExecutor();
                n += tpe.getActiveCount() + tpe.getQueue().size();
            } catch (IllegalStateException notInitialized) {
                // başlatılmamış / kapanmış havuz — beklenecek iş yok
            }
        }
        return n;
    }
}
