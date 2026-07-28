package com.certmonitor.service;

import com.certmonitor.model.AuditLog;
import com.certmonitor.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditServiceTest {

    @Mock AuditLogRepository auditLogRepo;
    @Mock GeoIpService geoIpService;

    private AuditService service;

    @BeforeEach
    void setUp() {
        // Gerçek resolver — resolveIp delege testleri davranışı doğrulamaya devam etsin
        ClientIpResolver clientIpResolver = new ClientIpResolver();
        ReflectionTestUtils.setField(clientIpResolver, "headers", new String[]{"X-Forwarded-For"});
        ReflectionTestUtils.setField(clientIpResolver, "index", 0);
        service = new AuditService(auditLogRepo, geoIpService, clientIpResolver);
        // officeStartHour=0, officeEndHour=0 → hour < 0 is never true → isOffHours checks dow>=6
        // Use 0–0 so that on weekdays isOffHours() returns false deterministically
        ReflectionTestUtils.setField(service, "bruteForceWindowSeconds", 600);
        ReflectionTestUtils.setField(service, "geoVelocityWindowSeconds", 3600);
        ReflectionTestUtils.setField(service, "officeStartHour", 0);
        ReflectionTestUtils.setField(service, "officeEndHour", 0);

        when(auditLogRepo.save(any())).thenAnswer(inv -> {
            AuditLog a = inv.getArgument(0);
            a.setId(1L);
            return a;
        });
        when(geoIpService.lookup(any())).thenReturn(new GeoIpService.GeoInfo(null, null, null));
        when(geoIpService.isPrivateIp(any())).thenReturn(false);
        when(auditLogRepo.findById(any())).thenReturn(java.util.Optional.empty());
        when(auditLogRepo.existsSuccessfulLoginFromIp(any(), any())).thenReturn(false);
        when(auditLogRepo.findRecentSuccessfulLogins(any(), any())).thenReturn(Collections.emptyList());
        when(auditLogRepo.countRecentFailedLogins(any(), any())).thenReturn(0L);
    }

    // ── recordLogin ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("successful login → eventType=LOGIN, outcome=SUCCESS")
    void recordLogin_success_setsLoginEventAndSuccessOutcome() {
        AuditLog result = service.recordLogin("alice", 1L, 2L, "USER",
                "1.2.3.4", "Mozilla/5.0", "sess1", true, null, null, 5);

        assertThat(result.getEventType()).isEqualTo("LOGIN");
        assertThat(result.getOutcome()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("failed login → eventType=LOGIN_FAILED, outcome=FAILURE")
    void recordLogin_failure_setsLoginFailedAndFailureOutcome() {
        AuditLog result = service.recordLogin("alice", 1L, 2L, "USER",
                "1.2.3.4", "Mozilla/5.0", "sess1", false, "bad pass", null, 5);

        assertThat(result.getEventType()).isEqualTo("LOGIN_FAILED");
        assertThat(result.getOutcome()).isEqualTo("FAILURE");
    }

    @Test
    @DisplayName("brute force threshold crossed → BRUTE_FORCE flag added")
    void recordLogin_bruteForce_addsBruteForceFlag() {
        when(auditLogRepo.countRecentFailedLogins(eq("alice"), any())).thenReturn(4L);

        AuditLog result = service.recordLogin("alice", 1L, 2L, "USER",
                "1.2.3.4", "Mozilla/5.0", "sess1", false, null, null, 5);

        assertThat(result.getAnomalyFlags()).contains("BRUTE_FORCE");
    }

    @Test
    @DisplayName("failed login → failure_reason makine-kodu önekiyle başlar (BAD_PASSWORD / UNKNOWN_USER)")
    void recordLogin_failure_prefixesMachineCode() {
        AuditLog bad = service.recordLogin("alice", 1L, 2L, "USER",
                "1.2.3.4", "Mozilla/5.0", "sess1", false, "BAD_PASSWORD", null, 5);
        assertThat(bad.getFailureReason()).startsWith("BAD_PASSWORD:");

        AuditLog unknown = service.recordLogin("ghost", null, null, null,
                "1.2.3.4", "Mozilla/5.0", null, false, "UNKNOWN_USER", null, 5);
        assertThat(unknown.getFailureReason()).startsWith("UNKNOWN_USER:");
    }

    @Test
    @DisplayName("unknown IP on successful login → UNUSUAL_IP flag added")
    void recordLogin_unusualIp_addsUnusualIpFlag() {
        when(auditLogRepo.existsSuccessfulLoginFromIp(eq("alice"), any())).thenReturn(false);
        when(geoIpService.isPrivateIp(any())).thenReturn(false);

        AuditLog result = service.recordLogin("alice", 1L, 2L, "USER",
                "8.8.8.8", "Mozilla/5.0", "sess1", true, null, null, 5);

        assertThat(result.getAnomalyFlags()).contains("UNUSUAL_IP");
    }

    @Test
    @DisplayName("known IP on successful login → no UNUSUAL_IP flag")
    void recordLogin_knownIp_noUnusualIpFlag() {
        when(auditLogRepo.existsSuccessfulLoginFromIp(eq("alice"), eq("8.8.8.8"))).thenReturn(true);
        when(geoIpService.isPrivateIp("8.8.8.8")).thenReturn(false);

        AuditLog result = service.recordLogin("alice", 1L, 2L, "USER",
                "8.8.8.8", "Mozilla/5.0", "sess1", true, null, null, 5);

        String flags = result.getAnomalyFlags();
        assertThat(flags == null || !flags.contains("UNUSUAL_IP")).isTrue();
    }

    @Test
    @DisplayName("private IP on successful login → no UNUSUAL_IP flag")
    void recordLogin_privateIp_noUnusualIpFlag() {
        when(geoIpService.isPrivateIp("127.0.0.1")).thenReturn(true);

        AuditLog result = service.recordLogin("alice", 1L, 2L, "USER",
                "127.0.0.1", "Mozilla/5.0", "sess1", true, null, null, 5);

        String flags = result.getAnomalyFlags();
        assertThat(flags == null || !flags.contains("UNUSUAL_IP")).isTrue();
    }

    // ── recordRateLimited ─────────────────────────────────────────────────────

    @Test
    @DisplayName("recordRateLimited → outcome=BLOCKED, RATE_LIMITED flag")
    void recordRateLimited_savesLogWithRateLimitedFlag() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);

        service.recordRateLimited("alice", "1.2.3.4", "Mozilla/5.0");

        verify(auditLogRepo).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getOutcome()).isEqualTo("BLOCKED");
        assertThat(saved.getAnomalyFlags()).contains("RATE_LIMITED");
    }

    // ── recordLogout ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("recordLogout → eventType=LOGOUT, outcome=SUCCESS")
    void recordLogout_savesLogoutEvent() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);

        service.recordLogout("alice", 1L, "1.2.3.4", "sess1");

        verify(auditLogRepo).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("LOGOUT");
        assertThat(saved.getOutcome()).isEqualTo("SUCCESS");
    }

    // ── recordAction ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("recordAction with direct params → saves with SUCCESS outcome")
    void recordAction_directParams_savesWithSuccessOutcome() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);

        service.recordAction("DOMAIN_EDIT", "alice", 1L, 2L, "USER",
                "CERTIFICATE", "domain.com", "{}", "1.2.3.4", "UA", "sess1");

        verify(auditLogRepo).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("DOMAIN_EDIT");
        assertThat(saved.getOutcome()).isEqualTo("SUCCESS");
        assertThat(saved.getResourceType()).isEqualTo("CERTIFICATE");
        assertThat(saved.getResourceId()).isEqualTo("domain.com");
    }

    @Test
    @DisplayName("recordAction from HTTP objects → reads actor from session attributes")
    void recordAction_fromHttpObjects_readsActorFromSession() {
        HttpSession session = mock(HttpSession.class);
        HttpServletRequest request = mock(HttpServletRequest.class);

        when(session.getAttribute("username")).thenReturn("bob");
        when(session.getAttribute("userId")).thenReturn(7L);
        when(session.getAttribute("teamId")).thenReturn(3L);
        when(session.getAttribute("systemRole")).thenReturn("ADMIN");
        when(session.getId()).thenReturn("sess-bob");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(request.getHeader("User-Agent")).thenReturn("TestUA");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        service.recordAction("USER_EDIT", session, request, "USER", "7", null);

        verify(auditLogRepo).save(captor.capture());
        assertThat(captor.getValue().getActor()).isEqualTo("bob");
        assertThat(captor.getValue().getActorId()).isEqualTo(7L);
    }

    // ── resolveIp ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("X-Forwarded-For header → returns first IP")
    void resolveIp_xForwardedFor_returnsFirstIp() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 5.6.7.8");
        assertThat(service.resolveIp(req)).isEqualTo("1.2.3.4");
    }

    @Test
    @DisplayName("no X-Forwarded-For → returns RemoteAddr")
    void resolveIp_noForwardedHeader_returnsRemoteAddr() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("9.9.9.9");
        assertThat(service.resolveIp(req)).isEqualTo("9.9.9.9");
    }

    @Test
    @DisplayName("null request → returns 'unknown'")
    void resolveIp_nullRequest_returnsUnknown() {
        assertThat(service.resolveIp(null)).isEqualTo("unknown");
    }

    // ── resolveUa ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolveUa → extracts User-Agent header")
    void resolveUa_extractsUserAgentHeader() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("User-Agent")).thenReturn("Mozilla/5.0");
        assertThat(service.resolveUa(req)).isEqualTo("Mozilla/5.0");
    }

    // ── persist güvenilirliği (best-effort + fallback) ────────────────────────────

    @Test
    @DisplayName("persist fallback: repo.save patlarsa kayıt fallback dosyaya yazılır, istisna FIRLATILMAZ")
    void persist_dbFailure_writesFallbackFileNoThrow(@TempDir Path tmp) throws IOException {
        Path fb = tmp.resolve("audit-fallback.jsonl");
        ReflectionTestUtils.setField(service, "fallbackFile", fb.toString());
        when(auditLogRepo.findTopByOrderBySeqDesc()).thenReturn(Optional.empty());
        doThrow(new RuntimeException("db down")).when(auditLogRepo).save(any());   // doThrow: mevcut answer'ı tetiklemez

        assertThatCode(() -> service.recordSystemEvent("SYSTEM_STARTUP", "SYSTEM", "app", "boot"))
                .doesNotThrowAnyException();

        assertThat(Files.exists(fb)).isTrue();
        assertThat(Files.readString(fb)).contains("SYSTEM_STARTUP");
    }

    @Test
    @DisplayName("recordSecurityEvent → outcome=BLOCKED, oturumsuzsa actor=anonymous")
    void recordSecurityEvent_blockedAnonymous() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("9.9.9.9");
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);

        service.recordSecurityEvent("ACCESS_DENIED", req, null, "ENDPOINT", "GET /api/admin/audit", "Audit access required");

        verify(auditLogRepo).save(cap.capture());
        AuditLog e = cap.getValue();
        assertThat(e.getEventType()).isEqualTo("ACCESS_DENIED");
        assertThat(e.getOutcome()).isEqualTo("BLOCKED");
        assertThat(e.getActor()).isEqualTo("anonymous");
        assertThat(e.getResourceId()).isEqualTo("GET /api/admin/audit");
        assertThat(e.getRowHash()).isNotBlank();
    }

    @Test
    @DisplayName("recordSystemEvent → actor=SYSTEM, outcome=SUCCESS")
    void recordSystemEvent_systemActor() {
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        service.recordSystemEvent("SCHEMA_PATCH", "SYSTEM", "db", "patch");
        verify(auditLogRepo).save(cap.capture());
        assertThat(cap.getValue().getActor()).isEqualTo("SYSTEM");
        assertThat(cap.getValue().getOutcome()).isEqualTo("SUCCESS");
        assertThat(cap.getValue().getRowHash()).isNotBlank();   // zincir hash set edildi
    }
}
