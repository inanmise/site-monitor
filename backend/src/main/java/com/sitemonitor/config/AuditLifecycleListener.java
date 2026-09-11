package com.sitemonitor.config;

import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.BuildInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Uygulama yaşam döngüsü SİSTEM olaylarını denetime yazar (başlatma/kapatma). Best-effort — kapanışta
 * DB kapanıyor olabilir; persist hata verirse fallback dosyaya düşer, kapanışı engellemez.
 *
 * <p>2026-09-10: detail düz metin yerine JSON ({@link AuditDetail#of}) — sürüm, commit, ortam, örnek,
 * helm revizyonu ve imaj sürümü ile. Eskiden "site-monitor başlatıldı" satırı hangi sürümün açıldığını
 * söylemiyordu; dağıtım geçmişi (deployment_history) yanında denetim izi de kendi başına okunabilir.
 * {@code BuildInfo} isteğe bağlı enjekte edilir (testler tek-argümanlı yapıcıyı kullanır).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLifecycleListener {

    private final AuditService auditService;

    @Autowired(required = false)
    private BuildInfo buildInfo;

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            auditService.recordSystemEvent("SYSTEM_STARTUP", "SYSTEM", "application", startupDetail());
        } catch (Exception e) {
            log.debug("Startup audit failed: {}", e.toString());
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        try {
            auditService.recordSystemEvent("SYSTEM_SHUTDOWN", "SYSTEM", "application", shutdownDetail());
        } catch (Exception e) {
            log.debug("Shutdown audit failed: {}", e.toString());
        }
    }

    String startupDetail() {
        BuildInfo.Snapshot b = buildInfo != null ? buildInfo.get() : null;
        if (b == null) return AuditDetail.of("message", "site-monitor başlatıldı");
        return AuditDetail.of(
                "message", "site-monitor başlatıldı",
                "version", b.version(),
                "commit", b.commitShort(),
                "environment", b.environment(),
                "instance", b.instanceId(),
                "helmRevision", b.helmRevision() == null ? "" : String.valueOf(b.helmRevision()),
                "imageVersion", b.imageVersion());
    }

    String shutdownDetail() {
        BuildInfo.Snapshot b = buildInfo != null ? buildInfo.get() : null;
        if (b == null) return AuditDetail.of("message", "site-monitor kapatıldı");
        return AuditDetail.of(
                "message", "site-monitor kapatıldı",
                "version", b.version(),
                "environment", b.environment(),
                "instance", b.instanceId(),
                "uptimeSeconds", String.valueOf(b.uptimeSeconds()));
    }
}
