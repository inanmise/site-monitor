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

    /** Bekleme sınırı (ms) — test kısaltır. */
    long maxWaitMs = WebConfig.NOTIFY_AWAIT_SECONDS * 1000L;

    public NotifyExecutorDrain(@Qualifier("notifyExecutor") ObjectProvider<ThreadPoolTaskExecutor> notifyExecutor,
                               @Qualifier("loginIssueMailExecutor") ObjectProvider<ThreadPoolTaskExecutor> loginMailExecutor) {
        this.notifyExecutor = notifyExecutor;
        this.loginMailExecutor = loginMailExecutor;
    }

    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        List<ThreadPoolTaskExecutor> pools = new ArrayList<>(2);
        ThreadPoolTaskExecutor n = notifyExecutor.getIfAvailable();
        if (n != null) pools.add(n);
        ThreadPoolTaskExecutor l = loginMailExecutor.getIfAvailable();
        if (l != null) pools.add(l);
        int left = drain(pools, maxWaitMs);
        if (left > 0) {
            log.warn("Kapanış: bildirim havuzlarında {} iş {} ms içinde bitmedi — çözüm postaları kalıcı işaretle başka pod'dan gider",
                    left, maxWaitMs);
        }
    }

    /**
     * Havuzlar boşalana (süren iş = 0 ve kuyruk boş) ya da süre dolana dek bekler; kalan iş sayısını döner. Döngü süreyle
     * sınırlı (100 ms adım) — kesilirse hemen döner.
     */
    static int drain(List<ThreadPoolTaskExecutor> pools, long maxWaitMs) {
        long deadline = System.nanoTime() + Math.max(0, maxWaitMs) * 1_000_000L;
        while (true) {
            int pending = pending(pools);
            if (pending == 0) return 0;
            if (System.nanoTime() >= deadline) return pending;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return pending(pools);
            }
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
