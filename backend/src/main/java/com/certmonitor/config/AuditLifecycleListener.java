package com.certmonitor.config;

import com.certmonitor.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Uygulama yaşam döngüsü SİSTEM olaylarını denetime yazar (başlatma/kapatma). Best-effort — kapanışta
 * DB kapanıyor olabilir; persist hata verirse fallback dosyaya düşer, kapanışı engellemez.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLifecycleListener {

    private final AuditService auditService;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            auditService.recordSystemEvent("SYSTEM_STARTUP", "SYSTEM", "application", "cert-monitor başlatıldı");
        } catch (Exception e) {
            log.debug("Startup audit failed: {}", e.toString());
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        try {
            auditService.recordSystemEvent("SYSTEM_SHUTDOWN", "SYSTEM", "application", "cert-monitor kapatıldı");
        } catch (Exception e) {
            log.debug("Shutdown audit failed: {}", e.toString());
        }
    }
}
