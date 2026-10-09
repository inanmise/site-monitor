package com.sitemonitor.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Genel Bakış önbelleklerini ARKA PLANDA ısıtır (2026-10-09, kullanıcı bildirimi: "uygulama ilk ayağa kalktığında
 * sertifikalar ilk yüklenirken çok fazla zaman alıyor").
 *
 * <p><b>Neden.</b> {@code cert-latest} / {@code cert-stats} / {@code card-extras} açılışta boştur ve her tarama / elle kontrol
 * sonrası {@link CertificateService#evictAllCaches()} ile boşalır; boş önbelleği ilk açan kullanıcı tüm hesaplamayı
 * (envanter + son kontroller, istatistikler, 24 saatlik erişilebilirlik toplamı, alarmlar) kendi isteğinde bekliyordu.
 * Isıtıcı açılışta ve her boşaltmadan {@link #DEBOUNCE_MS} sonra aynı hesaplamayı TEK arka plan iş parçacığında yapar —
 * kullanıcı ısınmış önbelleğe düşer.
 *
 * <p><b>Sınırlı.</b> Arka arkaya gelen boşaltmalar tek ısıtmada birleşir (bekleyen varken yenisi kurulmaz); döngü yok,
 * zamanlanmış yineleme yok — yalnız açılış ve boşaltma tetikler. Her adım ayrı yakalanır; hata yalnız DEBUG'dır (ısıtma
 * olmasa da ilk istek hesaplar — bugünkü davranış). {@code site.monitor.cache.warm-enabled=false} kapatır.
 */
@Slf4j
@Component
public class CertificateCacheWarmer {

    /** Boşaltmadan sonra ısıtmaya kadar bekleme: toplu kontrollerin ardışık boşaltmaları tek ısıtmada birleşsin. */
    static final long DEBOUNCE_MS = 2_000L;

    private final ObjectProvider<CertificateService> certService;
    private final ObjectProvider<CertificateCardExtrasService> cardExtras;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cert-cache-warmer");
        t.setDaemon(true);
        return t;
    });

    @Value("${site.monitor.cache.warm-enabled:true}")
    private boolean enabled = true;

    /** Test dikişi: birleştirme gecikmesi (üretimde {@link #DEBOUNCE_MS}). */
    long debounceMs = DEBOUNCE_MS;

    public CertificateCacheWarmer(ObjectProvider<CertificateService> certService,
                                  ObjectProvider<CertificateCardExtrasService> cardExtras) {
        this.certService = certService;
        this.cardExtras = cardExtras;
    }

    /** Açılış: ilk kullanıcı soğuk önbelleğe düşmesin. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        request();
    }

    /** Tarama / elle kontrol / envanter değişikliği önbellekleri boşalttı. */
    @EventListener
    public void onEvicted(CertificateService.CachesEvictedEvent event) {
        request();
    }

    /** Isıtma ister; bekleyen bir ısıtma varsa yenisi kurulmaz (birleştirme). @return yeni ısıtma kuruldu mu */
    boolean request() {
        if (!enabled || exec.isShutdown()) return false;
        if (!pending.compareAndSet(false, true)) return false;
        try {
            exec.schedule(this::warm, debounceMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (RuntimeException e) {
            pending.set(false);
            return false;
        }
    }

    /** Tek ısıtma turu: önce bayrak bırakılır — tur sırasında gelen boşaltma bir SONRAKİ turu kurar (taze veri). */
    void warm() {
        pending.set(false);
        long t0 = System.currentTimeMillis();
        CertificateService certs = certService.getIfAvailable();
        if (certs != null) {
            step("sertifika listesi", certs::getAllLatest);
            step("istatistikler", certs::getStats);
        }
        CertificateCardExtrasService extras = cardExtras.getIfAvailable();
        if (extras != null) step("kart ekleri", extras::all);
        log.debug("Genel Bakış önbellekleri ısıtıldı ({} ms)", System.currentTimeMillis() - t0);
    }

    private static void step(String what, Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.debug("Önbellek ısıtma adımı atlandı ({}): {}", what, e.toString());
        }
    }

    @PreDestroy
    void shutdown() {
        exec.shutdownNow();
    }
}
