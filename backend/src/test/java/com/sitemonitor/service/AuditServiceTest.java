package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AuditLogRepository;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditServiceTest {

    @Mock AuditLogRepository auditLogRepo;
    @Mock GeoIpService geoIpService;
    @Mock NewDeviceNotifier newDeviceNotifier;
    @Mock AuditGeoEnricher geoEnricher;

    private AuditService service;
    private ClientIpResolver clientIpResolver;

    @BeforeEach
    void setUp() {
        // Gerçek resolver — resolveIp delege testleri davranışı doğrulamaya devam etsin
        clientIpResolver = new ClientIpResolver();
        ReflectionTestUtils.setField(clientIpResolver, "headers", new String[]{"X-Forwarded-For"});
        ReflectionTestUtils.setField(clientIpResolver, "index", 0);
        service = new AuditService(auditLogRepo, geoIpService, newDeviceNotifier, clientIpResolver, geoEnricher);
        // NOT: buradaki 0/0 ayari mesai penceresini ETKISIZ birakmaz — `hour >= 0` her zaman
        // dogru oldugundan kural DAIMA "mesai disi" der. (Eski yorum bunun tersini soyluyordu.)
        // Bu testler OFF_HOURS'a bakmiyor; kuralin kendisi OffHoursRuleTest'te saf fonksiyon
        // olarak, saat dilimi ve sinirlariyla birlikte pinlenir.
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

    // ── Giriş kanalı ayrıntısı (2026-10-03, Giriş Yöntemleri → İstatistikler) ──────────────────

    @Test
    @DisplayName("2026-10-03: LOGIN / LOGIN_FAILED ayrıntısı giriş kanalını taşır (PASSWORD → LOCAL); yöntemsiz kayıt BİREBİR eski (ayrıntı yok)")
    void loginChannel_detail() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        service.recordLogin("ALICE", 1L, 2L, "USER", "1.2.3.4", "UA", "s1", true, null, null, 5, "LOCAL");
        service.recordLogin("ALICE", 1L, 2L, "USER", "1.2.3.4", "UA", "s2", true, null, null, 5, "REMEMBER_ME");
        service.recordLogin("BOB", null, null, null, "1.2.3.4", "UA", null, false, "BAD_PASSWORD", null, 5, "LDAP");
        service.recordLogin("CAROL", 3L, 2L, "USER", "1.2.3.4", "UA", "s3", true, null, null, 5);
        service.recordInactiveLogin("DAVE", 4L, 2L, "USER", "1.2.3.4", "UA", "PASSWORD");
        service.recordMaintenanceLogin("ERIN", 5L, 2L, "USER", "1.2.3.4", "UA", "REMEMBER_ME");
        service.recordLdapDisabledLogin("FRANK", "1.2.3.4", "UA");
        service.recordLockedLogin("GRACE", 6L, 2L, "USER", "1.2.3.4", "UA", "OTP_EMAIL");
        service.recordRateLimited("HEIDI", "1.2.3.4", "UA", "LDAP");
        service.recordRateLimited("NOBODY", "1.2.3.4", "UA");
        verify(auditLogRepo, org.mockito.Mockito.times(10)).save(captor.capture());
        List<AuditLog> rows = captor.getAllValues();
        assertThat(rows).extracting(AuditLog::getDetail).containsExactly(
                "{\"method\":\"LOCAL\"}", "{\"method\":\"REMEMBER_ME\"}", "{\"method\":\"LDAP\"}", null,
                "{\"method\":\"LOCAL\"}", "{\"method\":\"REMEMBER_ME\"}", "{\"method\":\"LDAP\"}",
                "{\"method\":\"OTP_EMAIL\"}", "{\"method\":\"LDAP\"}", null);
        // Tür / sonuç / neden metni DEĞİŞMEDİ (kaba kuvvet sayacı ve neden ayrıştırıcıları aynen çalışır)
        assertThat(rows.get(2).getEventType()).isEqualTo("LOGIN_FAILED");
        assertThat(rows.get(2).getFailureReason()).startsWith("BAD_PASSWORD: attempt #1/5");
        assertThat(rows.get(4).getFailureReason()).endsWith("(PASSWORD)");
        assertThat(rows.get(4).getOutcome()).isEqualTo("BLOCKED");
        assertThat(rows.get(8).getAnomalyFlags()).isEqualTo("RATE_LIMITED");
        assertThat(AuditService.loginChannel("password")).isEqualTo("LOCAL");
        assertThat(AuditService.loginChannel(" ")).isNull();
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

    @Test
    @DisplayName("recordInactiveLogin (2026-10-02) → LOGIN_FAILED, neden ACCOUNT_INACTIVE, outcome=BLOCKED (kaba kuvvet sayacına GİRMEZ), RATE_LIMITED basılmaz")
    void recordInactiveLogin_blockedWithReason() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);

        service.recordInactiveLogin("PASIF", 55L, 3L, "USER", "1.2.3.4", "Mozilla/5.0", "LDAP");

        verify(auditLogRepo).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getEventType()).isEqualTo("LOGIN_FAILED");
        assertThat(saved.getOutcome()).isEqualTo("BLOCKED");   // countRecentFailedLogins BLOCKED'ı saymaz
        assertThat(saved.getFailureReason()).startsWith("ACCOUNT_INACTIVE:").contains("LDAP");
        assertThat(saved.getActor()).isEqualTo("PASIF");
        assertThat(saved.getActorId()).isEqualTo(55L);
        assertThat(saved.getAnomalyFlags() == null ? "" : saved.getAnomalyFlags()).doesNotContain("RATE_LIMITED")
                .doesNotContain("BRUTE_FORCE");
        // Sayaç sorgusu çağrılmaz: bu satır kilit kararına girmez.
        verify(auditLogRepo, never()).countRecentFailedLogins(any(), any());
    }

    // ── E2: geo zenginleştirmesi giriş iş parçacığında KOŞMAZ ──────────────────

    @Test
    @DisplayName("E2: recordLogin / recordRateLimited geo+PTR'yi AYRI bean'e devreder — kendisi geo sorgusu yapmaz")
    void geoEnrichment_isDelegatedToSeparateBean_notDoneInline() {
        service.recordLogin("alice", 1L, 2L, "USER", "203.0.113.7", "Mozilla/5.0", "sess1", true, null, null, 5);
        service.recordRateLimited("alice", "203.0.113.8", "Mozilla/5.0");

        verify(geoEnricher).enrichGeoAsync(1L, "203.0.113.7");
        verify(geoEnricher).enrichGeoAsync(1L, "203.0.113.8");
        verify(geoIpService, never()).lookup(any());   // eskiden this.enrichGeoAsync → geo HTTP satır içinde
        verify(auditLogRepo, never()).updateGeo(any(), any(), any(), any(), any());
    }

    /**
     * E2 kanıtı GERÇEK Spring proxy'siyle: geo sorgusu bir mandalda ASILI kalırken recordLogin yine de döner ve
     * zenginleştirme ayrı bir iş parçacığında koşar. Sınıf-içi @Async çağrısı (eski hata) proxy'yi atlar →
     * recordLogin mandala takılır ve aşağıdaki {@code get(5 sn)} zaman aşımıyla KIRMIZI olur.
     */
    @Test
    @DisplayName("E2: geo sorgusu asılıyken recordLogin BEKLEMEDEN döner (@Async proxy devrede)")
    void recordLogin_returnsWithoutWaitingForGeoLookup() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> geoThread = new java.util.concurrent.atomic.AtomicReference<>();
        GeoIpService slowGeo = mock(GeoIpService.class);
        when(slowGeo.isPrivateIp(any())).thenReturn(true);   // PTR yok — yalnız geo HTTP'si beklenir
        when(slowGeo.lookup(any())).thenAnswer(inv -> {
            geoThread.set(Thread.currentThread().getName());
            release.await(15, java.util.concurrent.TimeUnit.SECONDS);
            return new GeoIpService.GeoInfo("TR", "Istanbul", "Org");
        });
        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.register(AsyncTestConfig.class);
            // Sahteler hazır singleton olarak (bean son-işlemcileri @Value/@PostConstruct'larına dokunmasın)
            ctx.getBeanFactory().registerSingleton("auditLogRepository", auditLogRepo);
            ctx.getBeanFactory().registerSingleton("geoIpService", slowGeo);
            ctx.registerBean(AuditGeoEnricher.class);
            ctx.refresh();
            AuditService svc = new AuditService(auditLogRepo, slowGeo, newDeviceNotifier, clientIpResolver,
                    ctx.getBean(AuditGeoEnricher.class));
            ReflectionTestUtils.setField(svc, "bruteForceWindowSeconds", 600);
            ReflectionTestUtils.setField(svc, "geoVelocityWindowSeconds", 3600);
            try {
                java.util.concurrent.CompletableFuture<AuditLog> call = java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> svc.recordLogin("alice", 1L, 2L, "USER", "203.0.113.7", "Mozilla/5.0", "sess1",
                                true, null, null, 5));
                AuditLog saved = call.get(5, java.util.concurrent.TimeUnit.SECONDS);   // asılı geo'yu BEKLEMEMELİ
                assertThat(saved.getEventType()).isEqualTo("LOGIN");
                verify(auditLogRepo, never()).updateGeo(any(), any(), any(), any(), any());   // hâlâ mandalda
            } finally {
                release.countDown();
            }
            verify(auditLogRepo, timeout(5000)).updateGeo(1L, "TR", "Istanbul", "Org", null);
            assertThat(geoThread.get()).as("geo sorgusu async havuzunda koştu").startsWith("audit-async-test-");
        }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.scheduling.annotation.EnableAsync
    static class AsyncTestConfig {
        @org.springframework.context.annotation.Bean(name = "certCheckExecutor")
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor certCheckExecutor() {
            var ex = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
            ex.setCorePoolSize(1);
            ex.setThreadNamePrefix("audit-async-test-");
            return ex;
        }
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
    @DisplayName("P3-4: birincil fallback yazılamazsa (salt-okunur kök FS) kayıt GEÇİCİ dizine düşer ve bekleyen sayılır")
    void persist_primaryFallbackUnwritable_usesTempDir(@TempDir Path tmp) throws IOException {
        Path blocker = Files.writeString(tmp.resolve("app"), "salt-okunur kökü taklit eden DOSYA");
        ReflectionTestUtils.setField(service, "fallbackFile", blocker.resolve("logs/audit-fallback.jsonl").toString());
        Path tempDir = Files.createDirectories(tmp.resolve("tmp"));
        ReflectionTestUtils.setField(service, "fallbackSecondaryDir", tempDir.toString());
        when(auditLogRepo.findTopByOrderBySeqDesc()).thenReturn(Optional.empty());
        doThrow(new RuntimeException("db down")).when(auditLogRepo).save(any());

        service.recordSystemEvent("SYSTEM_STARTUP", "SYSTEM", "app", "boot");

        Path second = tempDir.resolve("site-monitor").resolve("audit-fallback.jsonl");
        assertThat(Files.readString(second)).contains("SYSTEM_STARTUP");
        assertThat(service.pendingFallbackAuditCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("P3-4: iki yere de yazılamayan kayıt KAYIP sayılır — bekleyen sayısı 0 (\"temiz\") görünmez")
    void persist_allFallbacksUnwritable_countedAsDropped(@TempDir Path tmp) throws IOException {
        Path blocker = Files.writeString(tmp.resolve("app"), "dosya");
        ReflectionTestUtils.setField(service, "fallbackFile", blocker.resolve("logs/audit-fallback.jsonl").toString());
        ReflectionTestUtils.setField(service, "fallbackSecondaryDir", blocker.toString());   // o da bir DOSYA
        when(auditLogRepo.findTopByOrderBySeqDesc()).thenReturn(Optional.empty());
        doThrow(new RuntimeException("db down")).when(auditLogRepo).save(any());

        assertThatCode(() -> service.recordSystemEvent("SYSTEM_STARTUP", "SYSTEM", "app", "boot"))
                .doesNotThrowAnyException();

        assertThat(service.pendingFallbackAuditCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("P3-4: fallback dosyasının varsayılanı LOG dizinini izler (prod: /var/log/site-monitor, yazılabilir)")
    void fallbackDefault_followsLogDirectory() throws IOException {
        String props = Files.readString(Path.of("src/main/resources/application.properties"));
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^site[.]monitor[.]audit[.]fallback-file=(.*)$").matcher(props);
        assertThat(m.find()).isTrue();
        assertThat(m.group(1).trim())
                .as("göreli 'logs/' varsayılanı prod'da salt-okunur /app altına çözülüyordu")
                .startsWith("${AUDIT_FALLBACK_FILE:${logging.file.path");
        String prod = Files.readString(Path.of("src/main/resources/application-prod.properties"));
        assertThat(prod).contains("logging.file.path=${LOG_DIR:/var/log/site-monitor}");
    }

    @Test
    @DisplayName("Y-2/O-3: 10 KB'lık User-Agent en fazla 512 karakter saklanır; kısa/null değer aynen kalır")
    void recordLogin_capsHugeUserAgent() {
        String huge = "A".repeat(10_000);
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);

        service.recordLogin("alice", 1L, 2L, "USER", "1.2.3.4", huge, "sess1", false, "BAD_PASSWORD", null, 5);

        verify(auditLogRepo, atLeastOnce()).save(cap.capture());
        AuditLog saved = cap.getAllValues().get(0);
        assertThat(saved.getUserAgent()).hasSize(AuditLog.USER_AGENT_MAX).isEqualTo(huge.substring(0, 512));
        // Kısa ve null değer olduğu gibi kalır.
        AuditLog copy = new AuditLog();
        copy.setUserAgent("Mozilla/5.0");
        assertThat(copy.getUserAgent()).isEqualTo("Mozilla/5.0");
        copy.setUserAgent(null);
        assertThat(copy.getUserAgent()).isNull();
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

    // ── Çok pod'lu zincir kilidi (2026-10-09) ─────────────────────────────────────────────────────────

    private org.springframework.jdbc.core.JdbcTemplate chainJdbc(String product) {
        org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        doReturn(product).when(jdbc).execute(any(org.springframework.jdbc.core.ConnectionCallback.class));
        return jdbc;
    }

    private org.springframework.transaction.PlatformTransactionManager txManager() {
        org.springframework.transaction.PlatformTransactionManager tx =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        return tx;
    }

    @Test
    @DisplayName("Çok pod: PostgreSQL'de son satırı okuma + ekleme TEK işlemde, önce pg_advisory_xact_lock alınır")
    void persist_postgres_takesAdvisoryXactLockInSameTransaction() {
        var jdbc = chainJdbc("PostgreSQL");
        var tx = txManager();
        service.setChainLockSupport(jdbc, tx);
        when(auditLogRepo.findTopByOrderBySeqDesc()).thenReturn(Optional.empty());

        service.recordSystemEvent("SYSTEM_STARTUP", "SYSTEM", "app", "boot");
        service.recordSystemEvent("SCHEMA_PATCH", "SYSTEM", "db", "patch-1");

        InOrder order = inOrder(tx, jdbc, auditLogRepo);
        order.verify(tx).getTransaction(any());
        order.verify(jdbc).execute(AuditService.SQL_CHAIN_LOCK);
        order.verify(auditLogRepo).findTopByOrderBySeqDesc();
        order.verify(auditLogRepo).save(any());
        order.verify(tx).commit(any());
        verify(jdbc, times(2)).execute(AuditService.SQL_CHAIN_LOCK);
        // Veritabanı türü bir kez belirlenir.
        verify(jdbc, times(1)).execute(any(org.springframework.jdbc.core.ConnectionCallback.class));
        assertThat(AuditService.SQL_CHAIN_LOCK).isEqualTo("SELECT pg_advisory_xact_lock(" + AuditService.CHAIN_LOCK_KEY + ")");
    }

    @Test
    @DisplayName("Çok pod: H2 / PostgreSQL olmayan veritabanında kilit ve ayrı işlem YOK (bugünkü davranış)")
    void persist_nonPostgres_noAdvisoryLock() {
        var jdbc = chainJdbc("H2");
        var tx = txManager();
        service.setChainLockSupport(jdbc, tx);
        when(auditLogRepo.findTopByOrderBySeqDesc()).thenReturn(Optional.empty());

        AuditLog saved = service.persist(new AuditLog());

        assertThat(saved).isNotNull();
        assertThat(saved.getSeq()).isEqualTo(1L);
        verify(jdbc, never()).execute(anyString());
        verify(tx, never()).getTransaction(any());
        verify(auditLogRepo).save(any());
    }

    @Test
    @DisplayName("Çok pod: açık bir dış işlemin içinde kilit alınmaz (kilit dış COMMIT'e dek tutulmasın) — bugünkü davranış")
    void persist_insideOuterTransaction_noAdvisoryLock() {
        var jdbc = chainJdbc("PostgreSQL");
        var tx = txManager();
        service.setChainLockSupport(jdbc, tx);
        when(auditLogRepo.findTopByOrderBySeqDesc()).thenReturn(Optional.empty());

        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThat(service.persist(new AuditLog())).isNotNull();
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(jdbc, never()).execute(anyString());
        verify(tx, never()).getTransaction(any());
    }

    @Test
    @DisplayName("Çok pod: kilit altında yazım başarısızsa kayıt yine fallback dosyaya düşer, istisna FIRLATILMAZ")
    void persist_postgres_failureStillFallsBack(@TempDir Path tmp) throws IOException {
        Path fb = tmp.resolve("audit-fallback.jsonl");
        ReflectionTestUtils.setField(service, "fallbackFile", fb.toString());
        var jdbc = chainJdbc("PostgreSQL");
        var tx = txManager();
        service.setChainLockSupport(jdbc, tx);
        when(auditLogRepo.findTopByOrderBySeqDesc()).thenReturn(Optional.empty());
        doThrow(new RuntimeException("db down")).when(auditLogRepo).save(any());

        assertThat(service.persist(new AuditLog())).isNull();
        assertThat(Files.exists(fb)).isTrue();
        verify(tx).rollback(any());
    }
}
