package com.sitemonitor.service;

import com.sitemonitor.repository.AuditLogRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

/**
 * Denetim satırının geo/PTR zenginleştirmesi (hash'e girmeyen kolonlar) — {@link AuditService}'ten AYRI bean.
 *
 * <p><b>Neden ayrı (E2, 2026-09-28):</b> {@code @Async enrichGeoAsync} eskiden AuditService'in İÇİNDEYDİ ve
 * {@code recordLogin} / {@code recordRateLimited} onu {@code this} üzerinden çağırıyordu. Sınıf-içi çağrı Spring
 * proxy'sini atlar → {@code @Async} HİÇ devreye girmiyor, her giriş ve her 429 (genel IP'den) geo API'ye HTTP
 * isteğini ve zaman aşımsız ters-DNS sorgusunu Tomcat iş parçacığında bekliyordu; rate-limit yolu iş parçacığı
 * tüketme vektörüne dönüşüyordu. Çağrı artık bu bean'in proxy'sinden geçer ({@link NewDeviceNotifier} ile aynı desen).
 *
 * <p><b>Ters-DNS tavanlı:</b> {@code InetAddress.getCanonicalHostName()} zaman aşımı almaz (işletim sistemi
 * çözücüsü saniyelerce bekleyebilir). Sorgu küçük, SINIRLI bir havuzda koşar ve en fazla
 * {@link #REVERSE_DNS_TIMEOUT_MS} beklenir; havuz/kuyruk doluysa sorgu hiç yapılmaz (host boş kalır). Havuz
 * doygunluğunda {@code certCheckExecutor} görevi çağıranda koşturduğu için (CallerRuns) bu tavan, en kötü
 * durumda istek iş parçacığının bekleyeceği süreyi de sınırlar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditGeoEnricher {

    /** Ters-DNS (PTR) için en uzun bekleme — aşılırsa host boş bırakılır (denetim satırı yine yazılmıştır). */
    static final long REVERSE_DNS_TIMEOUT_MS = 1500;

    private final AuditLogRepository auditLogRepo;
    private final GeoIpService geoIpService;

    /** PTR sorgusu (testte yavaş/asılı bir sahte ile değiştirilir). İleri doğrulamalı: ad IP'ye geri çözülmezse IP döner. */
    private UnaryOperator<String> ptrLookup = AuditGeoEnricher::canonicalHostName;

    /**
     * Asılı kalan PTR sorguları için SINIRLI havuz: 2 iş parçacığı + 32 kuyruk, taşarsa sorgu reddedilir.
     * Zaman aşımına uğrayan sorgu işletim sistemi bırakana dek bir iş parçacığını tutar; havuz sınırlı olduğundan
     * farklı IP'lerden gelen bir sel en fazla bu kadar iş parçacığı bağlar.
     */
    private final ThreadPoolExecutor ptrPool = newPtrPool();

    private static ThreadPoolExecutor newPtrPool() {
        AtomicInteger seq = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32),
                r -> {
                    Thread t = new Thread(r, "audit-ptr-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @PreDestroy
    void shutdown() {
        ptrPool.shutdownNow();
    }

    @Async("certCheckExecutor")
    public void enrichGeoAsync(Long auditLogId, String ip) {
        try {
            GeoIpService.GeoInfo geo = geoIpService.lookup(ip);
            String host = reverseDns(ip);
            auditLogRepo.updateGeo(auditLogId, geo.country(), geo.city(), geo.org(), host);
        } catch (Exception e) {
            log.debug("Geo enrichment failed for id={}: {}", auditLogId, e.getMessage());
        }
    }

    /** Özel/boş IP'de sorgu yok; sorgu {@link #REVERSE_DNS_TIMEOUT_MS} içinde bitmezse ya da havuz doluysa null. */
    String reverseDns(String ip) {
        if (ip == null || ip.isBlank() || geoIpService.isPrivateIp(ip)) return null;
        Future<String> f;
        try {
            f = ptrPool.submit(() -> ptrLookup.apply(ip));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.debug("Ters-DNS atlandı (havuz dolu): {}", ip);
            return null;
        }
        try {
            String host = f.get(REVERSE_DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return (host != null && !host.equalsIgnoreCase(ip)) ? host : null;
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            log.debug("Ters-DNS zaman aşımı ({} ms): {}", REVERSE_DNS_TIMEOUT_MS, ip);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String canonicalHostName(String ip) {
        try {
            return java.net.InetAddress.getByName(ip).getCanonicalHostName();
        } catch (Exception e) {
            return null;
        }
    }
}
