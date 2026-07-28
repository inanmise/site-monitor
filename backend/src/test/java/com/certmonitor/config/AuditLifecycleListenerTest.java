package com.certmonitor.config;

import com.certmonitor.service.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/** Uygulama başlat/kapat → SYSTEM_STARTUP / SYSTEM_SHUTDOWN denetim olayı üretilir (best-effort). */
class AuditLifecycleListenerTest {

    @Test
    @DisplayName("onStartup → SYSTEM_STARTUP sistem olayı")
    void startup_recordsSystemEvent() {
        AuditService audit = mock(AuditService.class);
        new AuditLifecycleListener(audit).onStartup();
        verify(audit).recordSystemEvent(eq("SYSTEM_STARTUP"), eq("SYSTEM"), eq("application"), any());
    }

    @Test
    @DisplayName("onShutdown → SYSTEM_SHUTDOWN sistem olayı")
    void shutdown_recordsSystemEvent() {
        AuditService audit = mock(AuditService.class);
        new AuditLifecycleListener(audit).onShutdown();
        verify(audit).recordSystemEvent(eq("SYSTEM_SHUTDOWN"), eq("SYSTEM"), eq("application"), any());
    }

    @Test
    @DisplayName("best-effort: audit patlarsa yaşam döngüsü olayı istisna fırlatmaz")
    void startup_auditThrows_swallowed() {
        AuditService audit = mock(AuditService.class);
        doThrow(new RuntimeException("db down")).when(audit).recordSystemEvent(any(), any(), any(), any());
        new AuditLifecycleListener(audit).onStartup();   // istisna fırlatmamalı
    }
}
