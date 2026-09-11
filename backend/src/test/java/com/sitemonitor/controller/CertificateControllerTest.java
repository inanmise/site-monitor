package com.sitemonitor.controller;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.CertificateCheckerService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.SchedulerService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CertificateController.class)
// Sağlık değerlendirmesi GERÇEK servisle test edilir: mock'lansaydı uç testi mock'u test etmiş
// olurdu ve satır sözleşmesi (ROW_KEYS) hiçbir şeyi korumazdı. Tek bağımlılığı zaten @MockitoBean.
@org.springframework.context.annotation.Import(com.sitemonitor.service.CertificateHealthService.class)
class CertificateControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RememberMeService rememberMeService;

    @MockitoBean
    UserService userService;

    @MockitoBean
    AuthController authController;

    @MockitoBean
    com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean
    CertificateService certService;

    @MockitoBean
    CertificateCheckerService checkerService;

    @MockitoBean
    SchedulerService schedulerService;

    @MockitoBean
    AlertEventRepository alertEventRepo;

    @MockitoBean
    NetworkOutageEventRepository networkOutageRepo;

    @MockitoBean
    com.sitemonitor.repository.CertificateInventoryRepository inventoryRepo;

    @MockitoBean
    com.sitemonitor.service.ExtendedHealthService extendedHealthService;

    @MockitoBean
    com.sitemonitor.service.PermissionService permissionService;

    @MockitoBean
    com.sitemonitor.service.AuditService auditService;

    @MockitoBean
    com.sitemonitor.repository.LatestCheckRepository latestCheckRepo;

    @MockitoBean
    com.sitemonitor.repository.PageMonitorRepository pageMonitorRepo;

    /** Eşikleri okur; sağlık servisinin KENDİSİ mock DEĞİL (aşağıya bakın). */
    @MockitoBean
    com.sitemonitor.repository.AlertThresholdRepository thresholdRepo;

    @MockitoBean
    com.sitemonitor.service.CertificateAppLayerProbe appLayerProbe;

    // ── Auth guard ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/certificates without session returns 401")
    void getCertificates_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/certificates"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/warnings without session returns 401")
    void getWarnings_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/warnings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/stats without session returns 401")
    void getStats_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/stats"))
                .andExpect(status().isUnauthorized());
    }

    // ── Authenticated endpoints ───────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/certificates with auth returns 200 and success:true")
    void getCertificates_authenticated_returns200() throws Exception {
        when(certService.getAllLatestForTeams(null)).thenReturn(List.of());

        mvc.perform(get("/api/certificates").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/certificates returns all certs from service")
    void getCertificates_returnsCertList() throws Exception {
        CertificateDto dto = new CertificateDto();
        dto.setDomain("example.com");
        when(certService.getAllLatestForTeams(null)).thenReturn(List.of(dto));

        mvc.perform(get("/api/certificates").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].domain").value("example.com"));
    }

    @Test
    @DisplayName("GET /api/warnings returns 200 with warning list")
    void getWarnings_authenticated_returns200() throws Exception {
        when(certService.getWarningsForTeams(null)).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/warnings").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("GET /api/stats returns 200 with stats map")
    void getStats_authenticated_returns200() throws Exception {
        when(certService.getStatsForTeams(null)).thenReturn(Map.of("total_certificates", 5));

        mvc.perform(get("/api/stats").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total_certificates").value(5));
    }

    @Test
    @DisplayName("POST /api/scheduler/run returns 200 and starts check")
    void runScheduler_authenticated_returns200() throws Exception {
        mvc.perform(post("/api/scheduler/run").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/scheduler/status returns 200")
    void schedulerStatus_authenticated_returns200() throws Exception {
        when(schedulerService.getStatus()).thenReturn(Map.of("running", false));

        mvc.perform(get("/api/scheduler/status").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/renewal-advice returns 200 with advice list")
    void getRenewalAdvice_authenticated_returns200() throws Exception {
        when(certService.getRenewalAdviceForTeams(null)).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/renewal-advice").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    @DisplayName("GET /api/check/{domain} returns 200 with check result and auto-adds to inventory")
    void checkDomain_authenticated_returns200() throws Exception {
        Map<String, Object> checkResult = Map.of(
                "domain", "example.com",
                "status", "valid",
                "days_remaining", 90
        );
        when(checkerService.check("example.com", 443, false, null, null)).thenReturn(new java.util.LinkedHashMap<>(checkResult));
        when(inventoryRepo.findByDomain("example.com")).thenReturn(java.util.Optional.of(invOf("example.com", null)));

        mvc.perform(get("/api/check/example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.domain").value("example.com"));

        // Envanter kaydi ZATEN var; ensureInInventory cagrilmamali (rastgele domain ekleme yolu kapandi).
        org.mockito.Mockito.verify(certService, org.mockito.Mockito.never())
                .ensureInInventory(anyString(), anyInt(), any());
    }

    @Test
    @DisplayName("GET /api/check/{domain} forwards inventory tls_mode override to checker")
    void checkDomain_inventoryTlsMode_forwardedToChecker() throws Exception {
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
        inv.setDomain("example.com");
        inv.setTlsMode("default");
        when(inventoryRepo.findByDomain("example.com")).thenReturn(java.util.Optional.of(inv));
        when(checkerService.check("example.com", 443, false, "default", null))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of(
                        "domain", "example.com", "status", "valid", "days_remaining", 90)));

        mvc.perform(get("/api/check/example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        org.mockito.Mockito.verify(checkerService).check("example.com", 443, false, "default", null);
    }

    @Test
    @DisplayName("GET /api/history/{domain} returns 200 with history list")
    void getHistory_authenticated_returns200() throws Exception {
        when(inventoryRepo.findByDomain("example.com")).thenReturn(java.util.Optional.of(invOf("example.com", null)));
        when(certService.getHistory("example.com", 30)).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/history/example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.domain").value("example.com"))
                .andExpect(jsonPath("$.data").isArray());
    }

    // NOT: eski /api/activity (yalnız sertifika) testleri kaldırıldı — endpoint yeni birleşik
    // ActivityController'a taşındı (bkz. ActivityControllerTest: izolasyon + sayfalama).

    @Test
    @DisplayName("GET /api/certificates/list returns 200 with paginated data")
    void getCertificatesList_authenticated_returns200() throws Exception {
        when(certService.getPaginated(anyInt(), anyInt(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any()))
                .thenReturn(Map.of(
                        "data", Collections.emptyList(),
                        "pagination", Map.of("total", 0, "page", 1)
                ));

        mvc.perform(get("/api/certificates/list").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /api/history/{domain}/alerts returns 200 with alert list")
    void getDomainAlerts_authenticated_returns200() throws Exception {
        AlertEvent alert = new AlertEvent();
        alert.setId(1L);
        alert.setDomain("example.com");
        alert.setAlertType("EXPIRY");
        alert.setAlertLevel("WARNING");
        when(inventoryRepo.findByDomain("example.com")).thenReturn(java.util.Optional.of(invOf("example.com", null)));
        when(alertEventRepo.findByDomainOrderByCreatedAtDesc("example.com"))
                .thenReturn(List.of(alert));

        mvc.perform(get("/api/history/example.com/alerts").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.domain").value("example.com"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].alert_type").value("EXPIRY"));
    }

    @Test
    @DisplayName("GET /api/history/{domain}/alerts without session returns 401")
    void getDomainAlerts_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/history/example.com/alerts"))
                .andExpect(status().isUnauthorized());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private MockHttpSession authSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "testuser");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    // ── Domain-anahtarlı uçların takım denetimi ─────────────────────────────────
    // Bu üç uç (check / history / history-alerts) eskiden HİÇBİR yetki kontrolü yapmıyordu:
    // her oturumlu kullanıcı başka takımın geçmişini okuyabiliyor, /check ile rastgele bir host
    // için dış bağlantı açtırıp envantere kalıcı kayıt ekletebiliyordu.

    private static com.sitemonitor.model.CertificateInventory invOf(String domain, Long teamId) {
        com.sitemonitor.model.CertificateInventory i = new com.sitemonitor.model.CertificateInventory();
        i.setDomain(domain); i.setPort(443); i.setActive(true); i.setTeamId(teamId);
        return i;
    }

    /** Yalnız takım 5'i gören sıradan kullanıcı. */
    private MockHttpSession scopedSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u5");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(5L)));
        return s;
    }

    @Test
    @DisplayName("GÜVENLİK: /check envanterde OLMAYAN domain'de 404 — kontrol koşmaz, envantere kayıt EKLENMEZ")
    void checkDomain_notInInventory_returns404_andDoesNotProbe() throws Exception {
        when(inventoryRepo.findByDomain("rastgele.example.com")).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/check/rastgele.example.com").session(authSession()))
                .andExpect(status().isNotFound());

        org.mockito.Mockito.verify(checkerService, org.mockito.Mockito.never())
                .check(anyString(), anyInt(), anyBoolean(), any());
        org.mockito.Mockito.verify(certService, org.mockito.Mockito.never())
                .ensureInInventory(anyString(), anyInt(), any());
        org.mockito.Mockito.verify(certService, org.mockito.Mockito.never()).evictAllCaches();
    }

    @Test
    @DisplayName("IDOR: /check başka takımın domain'inde 403 — dış bağlantı açılmaz")
    void checkDomain_foreignTeam_returns403() throws Exception {
        when(inventoryRepo.findByDomain("baska.example.com")).thenReturn(java.util.Optional.of(invOf("baska.example.com", 9L)));

        mvc.perform(get("/api/check/baska.example.com").session(scopedSession()))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(checkerService, org.mockito.Mockito.never())
                .check(anyString(), anyInt(), anyBoolean(), any());
    }

    @Test
    @DisplayName("Kendi takımının domain'inde /check çalışmaya devam eder (dashboard ▶ butonu)")
    void checkDomain_ownTeam_returns200() throws Exception {
        when(inventoryRepo.findByDomain("benim.example.com")).thenReturn(java.util.Optional.of(invOf("benim.example.com", 5L)));
        when(checkerService.check("benim.example.com", 443, false, null))
                .thenReturn(new java.util.LinkedHashMap<>(Map.of("domain", "benim.example.com", "status", "valid")));

        mvc.perform(get("/api/check/benim.example.com").session(scopedSession()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("IDOR: /history ve /history/alerts başka takımın domain'inde 403")
    void history_foreignTeam_returns403() throws Exception {
        when(inventoryRepo.findByDomain("baska.example.com")).thenReturn(java.util.Optional.of(invOf("baska.example.com", 9L)));

        mvc.perform(get("/api/history/baska.example.com").session(scopedSession()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/history/baska.example.com/alerts").session(scopedSession()))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verify(certService, org.mockito.Mockito.never()).getHistory(anyString(), anyInt());
    }

    // ── Sertifika sağlık kontrol listesi ────────────────────────────────────

    // ── 2026-09-11: "planlı yenilemeydi" onayı ─────────────────────────────────────────

    @Test
    @DisplayName("Onay: sabitlenen parmak izi için kim/ne zaman yazılır, denetlenir ve satır YEŞİL döner")
    void confirmRenewal_recordsAckAndTurnsRowGreen() throws Exception {
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(java.util.Optional.of(invOf(5L, 443)));
        com.sitemonitor.model.LatestCheck lc = latestOf();
        lc.setFingerprint("AA:BB");
        lc.setPinnedFingerprint("AA:BB");
        lc.setPreviousFingerprint("00:11");
        lc.setFingerprintChangedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                .minusDays(1).withNano(0).toString());
        when(latestCheckRepo.findById("a.example.com")).thenReturn(java.util.Optional.of(lc));

        mvc.perform(post("/api/certificates/a.example.com/health/confirm-renewal").session(teamSession(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[?(@.key=='pinnedFingerprint')].status").value("OK"))
                .andExpect(jsonPath("$.data.rows[?(@.key=='pinnedFingerprint')].value_key").value("certRenewalConfirmed"))
                .andExpect(jsonPath("$.data.rows[?(@.key=='pinnedFingerprint')].evidence.confirmed_by").value("u"));

        org.assertj.core.api.Assertions.assertThat(lc.getFingerprintAckFingerprint()).isEqualTo("AA:BB");
        org.assertj.core.api.Assertions.assertThat(lc.getFingerprintAckBy()).isEqualTo("u");
        org.assertj.core.api.Assertions.assertThat(lc.getFingerprintAckAt()).isNotBlank();
        verify(latestCheckRepo).save(lc);
        verify(auditService).recordAction(eq("CERT_RENEWAL_CONFIRMED"), any(), eq("CERTIFICATE"),
                eq("a.example.com"), any(), any());
    }

    @Test
    @DisplayName("Onay: onaylanacak değişim yoksa 409 — hiçbir şey yazılmaz")
    void confirmRenewal_nothingToConfirm_isConflict() throws Exception {
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(java.util.Optional.of(invOf(5L, 443)));
        when(latestCheckRepo.findById("a.example.com")).thenReturn(java.util.Optional.of(latestOf()));

        mvc.perform(post("/api/certificates/a.example.com/health/confirm-renewal").session(teamSession(5L)))
                .andExpect(status().isConflict());

        verify(latestCheckRepo, never()).save(any());
        verify(auditService, never()).recordAction(eq("CERT_RENEWAL_CONFIRMED"), any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Onay: sunulan sertifika sabitlenenden FARKLIYSA 409 — araya girme imzası onaylanamaz")
    void confirmRenewal_pinMismatch_isConflict() throws Exception {
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(java.util.Optional.of(invOf(5L, 443)));
        com.sitemonitor.model.LatestCheck lc = latestOf();
        lc.setFingerprint("CC:DD");
        lc.setPinnedFingerprint("AA:BB");
        lc.setFingerprintChangedAt("2026-09-09T19:13:56Z");
        when(latestCheckRepo.findById("a.example.com")).thenReturn(java.util.Optional.of(lc));

        mvc.perform(post("/api/certificates/a.example.com/health/confirm-renewal").session(teamSession(5L)))
                .andExpect(status().isConflict());

        verify(latestCheckRepo, never()).save(any());
    }

    @Test
    @DisplayName("Onay: başka takımın domaini 404 + güvenlik olayı (görüş alanı dışı)")
    void confirmRenewal_otherTeam_isNotFound() throws Exception {
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(java.util.Optional.of(invOf(9L, 443)));

        mvc.perform(post("/api/certificates/a.example.com/health/confirm-renewal").session(teamSession(5L)))
                .andExpect(status().isNotFound());

        verify(latestCheckRepo, never()).save(any());
    }

    private MockHttpSession teamSession(Long teamId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", teamId);
        s.setAttribute("viewTeamIds", java.util.List.of(teamId));
        return s;
    }

    private com.sitemonitor.model.CertificateInventory invOf(Long teamId, Integer port) {
        com.sitemonitor.model.CertificateInventory i = new com.sitemonitor.model.CertificateInventory();
        i.setDomain("a.example.com");
        i.setTeamId(teamId);
        i.setPort(port);
        return i;
    }

    private com.sitemonitor.model.LatestCheck latestOf() {
        com.sitemonitor.model.LatestCheck lc = new com.sitemonitor.model.LatestCheck();
        lc.setDomain("a.example.com");
        lc.setDaysRemaining(120);
        lc.setNotAfter("2027-01-01T00:00:00");
        lc.setRevocationStatus("VALID");
        lc.setChainStatus("VALID");
        lc.setTrustStatus("TRUSTED");
        lc.setDeploymentStatus("COMPLETE");
        lc.setSignatureAlgorithm("SHA256withRSA");
        lc.setPublicKeyAlgorithm("RSA");
        lc.setPublicKeySize(2048);
        lc.setTlsVersion("TLSv1.3");
        lc.setCipherSuite("TLS_AES_256_GCM_SHA384");
        lc.setCheckedAt("2026-08-23T10:00:00");
        return lc;
    }

    @Test
    @DisplayName("Sağlık ucu künye + satırları döner; sonraki kontrol zamanı zamanlayıcıdan gelir")
    void health_returnsRowsAndSchedule() throws Exception {
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(java.util.Optional.of(invOf(5L, 8443)));
        when(latestCheckRepo.findById("a.example.com")).thenReturn(java.util.Optional.of(latestOf()));
        when(pageMonitorRepo.existsByUrlContainingIgnoreCaseAndActiveTrue("a.example.com")).thenReturn(false);
        when(schedulerService.nextCertificateSweepAt()).thenReturn("2026-08-23T11:00:00");

        mvc.perform(get("/api/certificates/a.example.com/health").session(teamSession(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("a.example.com"))
                .andExpect(jsonPath("$.data.port").value(8443))
                .andExpect(jsonPath("$.data.next_check_at").value("2026-08-23T11:00:00"))
                .andExpect(jsonPath("$.data.rows[0].key").value("expiry"))
                .andExpect(jsonPath("$.data.rows[0].status").value("OK"))
                // Backend CÜMLE kurmaz: metin anahtarı taşınır, arayüz i18n'den kurar.
                .andExpect(jsonPath("$.data.rows[0].value_key").value("daysLeft"))
                .andExpect(jsonPath("$.data.rows[0].action_key").value("none"));
    }

    @Test
    @DisplayName("SÖZLEŞME: yanıttaki satır anahtarları çekirdeğin kanonik listesiyle birebir")
    void health_rowKeysMatchCanonicalContract() throws Exception {
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(java.util.Optional.of(invOf(5L, 443)));
        when(latestCheckRepo.findById("a.example.com")).thenReturn(java.util.Optional.of(latestOf()));

        var body = mvc.perform(get("/api/certificates/a.example.com/health").session(teamSession(5L)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Satır eklenip uçta unutulursa (ya da sıra kayarsa) burada yakalanır.
        for (String key : com.sitemonitor.service.CertificateHealthService.ROW_KEYS) {
            org.assertj.core.api.Assertions.assertThat(body)
                    .as("kanonik satır yanıtta yok: %s", key)
                    .contains("\"key\":\"" + key + "\"");
        }
    }

    @Test
    @DisplayName("IDOR: yabancı takımın sertifika sağlığı 404 döner ve güvenlik olayı yazılır")
    void health_foreignTeam_isNotFound() throws Exception {
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(java.util.Optional.of(invOf(9L, 443)));

        mvc.perform(get("/api/certificates/a.example.com/health").session(teamSession(1L)))
                .andExpect(status().isNotFound());

        verify(auditService).recordSecurityEvent(eq("CERT_HEALTH_DENIED"), any(), any(),
                eq("CERTIFICATE"), eq("a.example.com"), anyString());
        verify(latestCheckRepo, never()).findById(anyString());
    }

    @Test
    @DisplayName("Envanterde olmayan domain 404 — güvenlik olayı YAZILMAZ (saldırı değil, yok)")
    void health_unknownDomain_isNotFound() throws Exception {
        when(inventoryRepo.findByDomain("yok.example.com")).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/certificates/yok.example.com/health").session(teamSession(5L)))
                .andExpect(status().isNotFound());

        verify(auditService, never()).recordSecurityEvent(anyString(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Hiç kontrol edilmemiş domain: künye boş ama uç ÇÖKMEZ")
    void health_neverChecked_returnsEmptyMeta() throws Exception {
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(java.util.Optional.of(invOf(5L, 443)));
        when(latestCheckRepo.findById("a.example.com")).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/certificates/a.example.com/health").session(teamSession(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checked_at").doesNotExist())
                .andExpect(jsonPath("$.data.evaluated_count").value(0));
    }

    @Test
    @DisplayName("Tazeleme envanter PORTUYLA canlı kontrol koşar, kaydeder ve denetime yazar")
    void healthRefresh_usesInventoryPort() throws Exception {
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(java.util.Optional.of(invOf(5L, 8443)));
        when(latestCheckRepo.findById("a.example.com")).thenReturn(java.util.Optional.of(latestOf()));
        when(checkerService.check(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of("status", "valid")));

        mvc.perform(post("/api/certificates/a.example.com/health/refresh").session(teamSession(5L)))
                .andExpect(status().isOk());

        verify(checkerService).check(eq("a.example.com"), eq(8443), anyBoolean(), any(), any());
        verify(certService).saveResult(any());
        verify(auditService).recordAction(eq("CERT_HEALTH_REFRESH"), any(), eq("CERTIFICATE"),
                eq("a.example.com"), any(), any());
    }

    /**
     * Sertifika kontrolü ile uygulama-katmanı probu AYNI vekil tercihini almalı.
     *
     * <p>Tercih proba geçmediğinde HSTS tanılaması kararı yalnız global yapılandırmadan
     * türüyordu: SSL sekmesi "HSTS etkin" derken Sağlık sekmesi "Doğrulanamadı" kalıyordu.
     */
    @Test
    @DisplayName("Uygulama katmanı probu, sertifika kontrolüyle AYNI vekil tercihini alır")
    void probeGetsSameProxyPreference() throws Exception {
        com.sitemonitor.model.CertificateInventory inv = invOf(5L, 443);
        inv.setDomain("prox.example.com");
        inv.setUseProxy(false);                                  // "Proxy Üzerinden Kontrol Et = Hayır"
        when(inventoryRepo.findByDomain("prox.example.com")).thenReturn(java.util.Optional.of(inv));
        when(latestCheckRepo.findById("prox.example.com")).thenReturn(java.util.Optional.of(latestOf()));
        when(checkerService.check(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of("status", "valid")));

        mvc.perform(post("/api/certificates/prox.example.com/health/refresh").session(teamSession(5L)))
                .andExpect(status().isOk());

        verify(checkerService).check(eq("prox.example.com"), anyInt(), eq(false), any(), any());
        verify(appLayerProbe).refresh("prox.example.com", 443, false);
    }

    @Test
    @DisplayName("use_proxy AÇIKSA tercih yine AYNEN iner")
    void probeGetsProxyPreferenceWhenEnabled() throws Exception {
        com.sitemonitor.model.CertificateInventory inv = invOf(5L, 443);
        inv.setDomain("prox2.example.com");
        inv.setUseProxy(true);
        when(inventoryRepo.findByDomain("prox2.example.com")).thenReturn(java.util.Optional.of(inv));
        when(latestCheckRepo.findById("prox2.example.com")).thenReturn(java.util.Optional.of(latestOf()));
        when(checkerService.check(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of("status", "valid")));

        mvc.perform(post("/api/certificates/prox2.example.com/health/refresh").session(teamSession(5L)))
                .andExpect(status().isOk());

        verify(appLayerProbe).refresh("prox2.example.com", 443, true);
    }

    @Test
    @DisplayName("Tazeleme SOĞUMA süresine tabi — düğmeye üst üste basmak el sıkışma yağmuru olmaz")
    void healthRefresh_isRateLimited() throws Exception {
        when(inventoryRepo.findByDomain("cool.example.com")).thenReturn(java.util.Optional.of(invOf(5L, 443)));
        when(latestCheckRepo.findById("cool.example.com")).thenReturn(java.util.Optional.empty());
        when(checkerService.check(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of("status", "valid")));

        mvc.perform(post("/api/certificates/cool.example.com/health/refresh").session(teamSession(5L)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/certificates/cool.example.com/health/refresh").session(teamSession(5L)))
                .andExpect(status().is(429));

        verify(checkerService, times(1)).check(eq("cool.example.com"), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("IDOR: yabancı takımda tazeleme de 404 ve canlı kontrol HİÇ koşmaz")
    void healthRefresh_foreignTeam_isBlocked() throws Exception {
        when(inventoryRepo.findByDomain("b.example.com")).thenReturn(java.util.Optional.of(invOf(9L, 443)));

        mvc.perform(post("/api/certificates/b.example.com/health/refresh").session(teamSession(1L)))
                .andExpect(status().isNotFound());

        verify(checkerService, never()).check(eq("b.example.com"), anyInt(), anyBoolean(), any());
    }

    @Test
    @DisplayName("check-preview envanter PORTUNU kullanır (443 sabiti kaldırıldı)")
    void preview_usesInventoryPort() throws Exception {
        when(inventoryRepo.findByDomain("a.example.com")).thenReturn(java.util.Optional.of(invOf(5L, 8443)));
        when(checkerService.check(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of("status", "valid")));

        mvc.perform(get("/api/check-preview/a.example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.port").value(8443));

        verify(checkerService).check(eq("a.example.com"), eq(8443), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("check-preview envanterde OLMAYAN domainde 443'e düşer (SSL Checker aracı çalışsın)")
    void preview_unknownDomainFallsBackTo443() throws Exception {
        when(inventoryRepo.findByDomain("serbest.example.com")).thenReturn(java.util.Optional.empty());
        when(checkerService.check(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of("status", "valid")));

        mvc.perform(get("/api/check-preview/serbest.example.com").session(authSession()))
                .andExpect(status().isOk());

        verify(checkerService).check(eq("serbest.example.com"), eq(443), anyBoolean(), any(), any());
    }

    // ── Ad-hoc sertifika testi (envanter formundaki "Test et") ─────────────────

    @Test
    @DisplayName("POST /api/certificates/test oturumsuz 401 döner")
    void testCertificate_unauthenticated_returns401() throws Exception {
        mvc.perform(post("/api/certificates/test")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"a.example.com\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("test, GÖVDEDEKİ port/TLS/proxy ile koşar — envantere hiç bakmaz")
    void testCertificate_usesBodyValuesNotInventory() throws Exception {
        when(checkerService.check(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of("status", "valid")));

        mvc.perform(post("/api/certificates/test").session(authSession())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"yeni.example.com\",\"port\":8443,"
                                + "\"tlsMode\":\"browser\",\"useProxy\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.port").value(8443));

        // Henüz KAYDEDİLMEMİŞ bir kayıtta envanterden okumak 443'ün sertifikasını gösterirdi:
        // test, kaydedilecek değerlerin AYNISIYLA koşmalı.
        verify(checkerService).check(eq("yeni.example.com"), eq(8443), eq(true), eq("browser"), any());
        verify(inventoryRepo, never()).findByDomain(anyString());
    }

    @Test
    @DisplayName("test, yapıştırılan URL'yi çıplak host'a indirir (form da böyle kaydeder)")
    void testCertificate_stripsUrlToHost() throws Exception {
        when(checkerService.check(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of("status", "valid")));

        mvc.perform(post("/api/certificates/test").session(authSession())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"https://a.example.com/yol\"}"))
                .andExpect(status().isOk());

        verify(checkerService).check(eq("a.example.com"), eq(443), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("test, geçersiz portta 400 döner ve HİÇBİR el sıkışması açmaz")
    void testCertificate_rejectsInvalidPort() throws Exception {
        mvc.perform(post("/api/certificates/test").session(authSession())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"a.example.com\",\"port\":70000}"))
                .andExpect(status().isBadRequest());

        verify(checkerService, never()).check(anyString(), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("test, envanter DÜZENLEME yetkisi ister (oturum yetmez)")
    void testCertificate_requiresInventoryEditPermission() throws Exception {
        org.mockito.Mockito.doThrow(new SecurityException("yetki yok"))
                .when(permissionService).require(any(jakarta.servlet.http.HttpSession.class),
                        eq("inventory.crud"), eq("edit"));

        mvc.perform(post("/api/certificates/test").session(authSession())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"domain\":\"a.example.com\"}"))
                .andExpect(status().isForbidden());

        // Keyfi host:port'a canlı el sıkışması açtırabilen bir uç: kapı KAPALIYKEN ağa çıkılmamalı.
        verify(checkerService, never()).check(anyString(), anyInt(), anyBoolean(), any(), any());
    }

    // ── A2: rozet uclarinin takim kapsami ──────────────────────────────────────
    //
    // `/alerts/silent-domains` ve `/notifications/failure-domains` eskiden `HttpSession`
    // parametresi BILE almiyordu: `monitoring.read` yetkisi olan herhangi bir kullanici
    // sistemdeki TUM takimlarin domain adlarini cekebiliyordu. Ayni sinifin kardes uclari
    // (`/certificates`, `/renewal-advice`, `/history/{domain}`) kapsamli.

    @Test
    @DisplayName("A2: /alerts/silent-domains kapsamli oturumda YABANCI domaini dusurur")
    void silentDomains_scopedSession_dropsForeignDomains() throws Exception {
        when(alertEventRepo.findDomainsWithUnnotifiedOpenAlerts())
                .thenReturn(List.of("benim.example.com", "yabanci.example.com"));
        when(inventoryRepo.findDomainsForTeams(anyList()))
                .thenReturn(List.of("benim.example.com"));

        mvc.perform(get("/api/alerts/silent-domains").session(scopedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0]").value("benim.example.com"));
    }

    @Test
    @DisplayName("A2: /notifications/failure-domains kapsamli oturumda YABANCI domaini dusurur")
    void failureDomains_scopedSession_dropsForeignDomains() throws Exception {
        when(extendedHealthService.findDomainsWithConsecutiveMailFailures(anyInt(), anyInt()))
                .thenReturn(List.of("benim.example.com", "yabanci.example.com"));
        when(inventoryRepo.findDomainsForTeams(anyList()))
                .thenReturn(List.of("benim.example.com"));

        mvc.perform(get("/api/notifications/failure-domains?consecutive=2&days=90").session(scopedSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0]").value("benim.example.com"))
                // `count` suzulmus listeyle tutarli olmali — ham sayiyi raporlamak sizintiyi surdururdu
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("A2: global admin SUZULMEZ — envanter sorgusuna hic gidilmez")
    void silentDomains_globalAdmin_returnsAllWithoutInventoryLookup() throws Exception {
        when(alertEventRepo.findDomainsWithUnnotifiedOpenAlerts())
                .thenReturn(List.of("a.example.com", "b.example.com"));

        mvc.perform(get("/api/alerts/silent-domains").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // Kapsamsiz gorunturleyicide suzme YOK: gereksiz envanter sorgusu da atilmamali
        // (bu uc her pano yuklemesinde cagriliyor).
        verify(inventoryRepo, never()).findDomainsForTeams(anyList());
    }

    @Test
    @DisplayName("A2: gorus kapsami OLMAYAN oturum bos liste alir (depoya gidilmez)")
    void silentDomains_noViewScope_returnsEmpty() throws Exception {
        when(alertEventRepo.findDomainsWithUnnotifiedOpenAlerts())
                .thenReturn(List.of("a.example.com"));

        MockHttpSession noScope = new MockHttpSession();
        noScope.setAttribute("authenticated", Boolean.TRUE);
        noScope.setAttribute("username", "u0");
        noScope.setAttribute("systemRole", "USER");
        noScope.setAttribute("viewTeamIds", new java.util.ArrayList<Long>());   // hicbir takim

        mvc.perform(get("/api/alerts/silent-domains").session(noScope))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // Bos kapsamla sorgu `IN ()` uretirdi — depoya HIC gidilmemeli.
        verify(inventoryRepo, never()).findDomainsForTeams(anyList());
    }

    // ── Kod incelemesi 2026-09-09: check-preview takım kapsamı ────────────────

    @Test
    @DisplayName("GÜVENLİK: check-preview envanterdeki BAŞKA takımın domain'ine 403 (canlı TLS sonucu + port/proxy sızmaz)")
    void preview_otherTeamInventoryDomain_returns403() throws Exception {
        when(inventoryRepo.findByDomain("t9.example.com")).thenReturn(java.util.Optional.of(invOf("t9.example.com", 9L)));

        mvc.perform(get("/api/check-preview/t9.example.com").session(scopedSession()))
                .andExpect(status().isForbidden());

        verify(checkerService, org.mockito.Mockito.never()).check(anyString(), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("check-preview kendi takımının domain'inde ve envanter DIŞI ad-hoc host'ta çalışır")
    void preview_ownTeamAndAdHoc_ok() throws Exception {
        when(inventoryRepo.findByDomain("t5.example.com")).thenReturn(java.util.Optional.of(invOf("t5.example.com", 5L)));
        when(inventoryRepo.findByDomain("adhoc.example.com")).thenReturn(java.util.Optional.empty());
        when(checkerService.check(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(new java.util.LinkedHashMap<>(java.util.Map.of("status", "valid")));

        mvc.perform(get("/api/check-preview/t5.example.com").session(scopedSession())).andExpect(status().isOk());
        mvc.perform(get("/api/check-preview/adhoc.example.com").session(scopedSession())).andExpect(status().isOk());
    }
}
