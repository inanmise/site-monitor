package com.sitemonitor.config;

import com.sitemonitor.service.AuditService;
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

    // ── 2026-09-10: detail JSON — sürüm/commit/ortam ile (Sürüm & Dağıtım Geçmişi) ──────────

    @Test
    @DisplayName("BuildInfo enjekte edilmişse başlangıç detayı JSON'dur: version + environment anahtarları")
    void startup_detailIsJsonWithVersionAndEnvironment() {
        AuditService audit = mock(AuditService.class);
        AuditLifecycleListener listener = new AuditLifecycleListener(audit);
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("site.monitor.version", "20.54.0")
                .withProperty("site.monitor.environment", "prod")
                .withProperty("site.monitor.build.commit", "0123456789abcdef0123456789abcdef01234567")
                .withProperty("site.monitor.helm.revision", "42");
        org.springframework.test.util.ReflectionTestUtils.setField(listener, "buildInfo", new com.sitemonitor.service.BuildInfo(env));

        listener.onStartup();

        org.mockito.ArgumentCaptor<String> detail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(audit).recordSystemEvent(eq("SYSTEM_STARTUP"), eq("SYSTEM"), eq("application"), detail.capture());
        String d = detail.getValue();
        org.assertj.core.api.Assertions.assertThat(d).startsWith("{").endsWith("}")
                .contains("\"version\":\"20.54.0\"")
                .contains("\"environment\":\"prod\"")
                .contains("\"commit\":\"01234567\"")
                .contains("\"helmRevision\":\"42\"")
                .contains("\"message\"");
        // Kapanış detayı da aynı kimliği taşır
        org.assertj.core.api.Assertions.assertThat(listener.shutdownDetail())
                .contains("\"version\":\"20.54.0\"").contains("\"environment\":\"prod\"").contains("\"uptimeSeconds\"");
    }

    @Test
    @DisplayName("BuildInfo yoksa (tek-argümanlı yapıcı) detay yine geçerli JSON — yalnız message")
    void startup_detailWithoutBuildInfo_isStillJson() {
        AuditService audit = mock(AuditService.class);
        AuditLifecycleListener listener = new AuditLifecycleListener(audit);
        org.assertj.core.api.Assertions.assertThat(listener.startupDetail())
                .startsWith("{").endsWith("}").contains("\"message\"").doesNotContain("\"version\"");
    }

    @Test
    @DisplayName("best-effort: audit patlarsa yaşam döngüsü olayı istisna fırlatmaz")
    void startup_auditThrows_swallowed() {
        AuditService audit = mock(AuditService.class);
        doThrow(new RuntimeException("db down")).when(audit).recordSystemEvent(any(), any(), any(), any());
        new AuditLifecycleListener(audit).onStartup();   // istisna fırlatmamalı
    }
}
