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

    @MockitoBean
    com.sitemonitor.service.ExecutiveStatsService executiveStatsService;   // yönetici özeti (2026-09-12, #20)

    /** Elle yüklenen sertifikanın çevrim-dışı değerlendirmesi (2026-10-06) — yalnız manuel kayıtta çağrılır. */
    @MockitoBean
    com.sitemonitor.service.manualcert.ManualCertificateEvaluationService manualCertEvaluation;

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
    @DisplayName("GET /api/certificates/paused: oturumsuz 401; global kapsamda servisten paused=true satırlar (2026-10-08)")
    void getPausedCertificates() throws Exception {
        mvc.perform(get("/api/certificates/paused")).andExpect(status().isUnauthorized());
        CertificateDto dto = new CertificateDto();
        dto.setDomain("pasif.example.com");
        dto.setPaused(Boolean.TRUE);
        when(certService.getPausedForTeams(null)).thenReturn(List.of(dto));
        mvc.perform(get("/api/certificates/paused").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].domain").value("pasif.example.com"))
                .andExpect(jsonPath("$.data[0].paused").value(true));
    }

    @Test
    @DisplayName("GET /api/certificates/paused: takım kapsamlı oturum kendi görüş kapsamını servise geçirir")
    void getPausedCertificates_teamScoped() throws Exception {
        MockHttpSession s = authSession();
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", List.of(7L));
        when(certService.getPausedForTeams(List.of(7L))).thenReturn(List.of());
        mvc.perform(get("/api/certificates/paused").session(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
        org.mockito.Mockito.verify(certService).getPausedForTeams(List.of(7L));
    }

    @Test
    @DisplayName("GET /api/certificates: aktif satır paused alanını YAZMAZ (yanıt bugünküyle aynı)")
    void getCertificates_activeRowsHaveNoPausedField() throws Exception {
        CertificateDto dto = new CertificateDto();
        dto.setDomain("example.com");
        when(certService.getAllLatestForTeams(null)).thenReturn(List.of(dto));
        mvc.perform(get("/api/certificates").session(authSession()))
                .andExpect(jsonPath("$.data[0].paused").doesNotExist());
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
    @DisplayName("GET /api/warnings: satır 7/24 alanlarını GERÇEK tel biçiminde taşır (noc_notify, noc_group_ids — snake_case)")
    void getWarnings_rowsCarryNocFieldsOnTheWire() throws Exception {
        CertificateDto on = new CertificateDto();
        on.setDomain("on.example.com"); on.setNocNotify(true); on.setNocGroupIds(List.of(3L, 7L));
        CertificateDto off = new CertificateDto();
        off.setDomain("off.example.com"); off.setNocNotify(false); off.setNocGroupIds(List.of());
        when(certService.getWarningsForTeams(null)).thenReturn(List.of(on, off));

        mvc.perform(get("/api/warnings").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].noc_notify").value(true))
                .andExpect(jsonPath("$.data[0].noc_group_ids[0]").value(3))
                .andExpect(jsonPath("$.data[0].noc_group_ids[1]").value(7))
                .andExpect(jsonPath("$.data[1].noc_notify").value(false))
                .andExpect(jsonPath("$.data[1].noc_group_ids").isEmpty())
                .andExpect(jsonPath("$.data[0].nocNotify").doesNotExist());
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

    @Test
    @DisplayName("GET /api/history/{domain}: zarf kaydın GÜNCEL 7/24 alanlarını taşır — yetki kapısının okuduğu envanter satırından, ek sorgu YOK")
    void getHistory_envelopeCarriesNocFromTheGateRead() throws Exception {
        var inv = invOf("example.com", null);
        inv.setNocNotify(true);
        inv.setNocGroupIds("4,9");
        when(inventoryRepo.findByDomain("example.com")).thenReturn(java.util.Optional.of(inv));
        when(certService.getHistory("example.com", 30)).thenReturn(Collections.emptyList());

        mvc.perform(get("/api/history/example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noc_notify").value(true))
                .andExpect(jsonPath("$.noc_group_ids[0]").value(4))
                .andExpect(jsonPath("$.noc_group_ids[1]").value(9));
        verify(inventoryRepo, times(1)).findByDomain("example.com");
        org.mockito.Mockito.verifyNoMoreInteractions(inventoryRepo);

        // Eski satır (iki kolon null): kapalı + varsayılan gruplar — "bilinmiyor" değil, kaydın gerçek durumu
        when(inventoryRepo.findByDomain("old.example.com")).thenReturn(java.util.Optional.of(invOf("old.example.com", null)));
        when(certService.getHistory("old.example.com", 30)).thenReturn(Collections.emptyList());
        mvc.perform(get("/api/history/old.example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.noc_notify").value(false))
                .andExpect(jsonPath("$.noc_group_ids").isEmpty());
    }

    // NOT: eski /api/activity (yalnız sertifika) testleri kaldırıldı — endpoint yeni birleşik
    // ActivityController'a taşındı (bkz. ActivityControllerTest: izolasyon + sayfalama).

    @Test
    @DisplayName("GET /api/certificates/list returns 200 with paginated data")
    void getCertificatesList_authenticated_returns200() throws Exception {
        when(certService.getPaginated(any(com.sitemonitor.dto.CertListQuery.class), any()))
                .thenReturn(Map.of(
                        "data", Collections.emptyList(),
                        "pagination", Map.of("total", 0, "page", 1),
                        "facets", Map.of("all", 0),
                        "shared", Map.of()
                ));

        mvc.perform(get("/api/certificates/list").session(authSession())
                        .param("filter_status", "expired").param("filter_window", "30").param("filter_team", "3")
                        .param("filter_insecure", "true").param("filter_tier", "2").param("filter_port", "nonstd").param("sort_by", "team"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.facets.all").value(0));
        // Yeni süzgeçler kayıt nesnesine EKSİKSİZ taşınır (kanonik zincir boşluğu: parametre eklenip okunmaması)
        org.mockito.ArgumentCaptor<com.sitemonitor.dto.CertListQuery> cap = org.mockito.ArgumentCaptor.forClass(com.sitemonitor.dto.CertListQuery.class);
        verify(certService).getPaginated(cap.capture(), any());
        com.sitemonitor.dto.CertListQuery q = cap.getValue();
        org.assertj.core.api.Assertions.assertThat(q.filterStatus()).isEqualTo("expired");
        org.assertj.core.api.Assertions.assertThat(q.filterWindow()).isEqualTo("30");
        org.assertj.core.api.Assertions.assertThat(q.filterTeam()).isEqualTo("3");
        org.assertj.core.api.Assertions.assertThat(q.filterInsecure()).isTrue();
        org.assertj.core.api.Assertions.assertThat(q.filterTier()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(q.filterPort()).isEqualTo("nonstd");
        org.assertj.core.api.Assertions.assertThat(q.sortBy()).isEqualTo("team");
    }

    @Test
    @DisplayName("GET /api/certificates/export.csv → text/csv, ek dosya adı, CERT_LIST_EXPORT denetim kaydı (satır sayısı)")
    void exportCertificatesCsv_authenticated_returnsCsvAndAudits() throws Exception {
        when(certService.exportCsv(any(com.sitemonitor.dto.CertListQuery.class), any(), any()))
                .thenReturn("domain,status\r\na.example.com,valid\r\nb.example.com,expired\r\n");

        mvc.perform(get("/api/certificates/export.csv").session(authSession())
                        .param("cols", "domain,status").param("filter_status", "expired"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("sertifikalar-")))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("domain,status")));

        org.mockito.ArgumentCaptor<java.util.List<String>> cols = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(certService).exportCsv(any(), any(), cols.capture());
        org.assertj.core.api.Assertions.assertThat(cols.getValue()).containsExactly("domain", "status");
        verify(auditService).recordAction(eq("CERT_LIST_EXPORT"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("CERTIFICATE"), eq("export"),
                org.mockito.ArgumentMatchers.contains("\"rows\":2"));
    }

    @Test
    @DisplayName("GET /api/certificates/export.csv oturumsuz → 401 (dışa aktarma herkese açık değil)")
    void exportCertificatesCsv_unauthenticated_returns401() throws Exception {
        mvc.perform(get("/api/certificates/export.csv")).andExpect(status().isUnauthorized());
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
        // 2026-09-12: sağlık ucu alan başına aşırı yükü çağırır (sıklık boş → genel süpürme)
        when(schedulerService.nextCertificateSweepAt("a.example.com", null)).thenReturn("2026-08-23T11:00:00");

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

    // ── A11 (2026-09-28): YAZAN sağlık uçları görüş kapsamıyla değil İŞLEM kapsamıyla kapılı ─────────
    // Global görücü AUDIT (salt okur) her takımı GÖRDÜĞÜ için her takımın parmak izi değişimini "planlı yenileme"
    // diye onaylayabiliyor (araya girme uyarısı yeşile döner) ve canlı el sıkışması tetikleyebiliyordu.

    private static MockHttpSession auditSession() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "denetci");
        s.setAttribute("systemRole", "AUDIT");   // viewTeamIds null → global görücü, takım üyeliği yok
        return s;
    }

    /** Kapsamlı müdür: rol ADMIN, takım 5'i GÖRÜR (ast takım) ama yalnız takım 2'yi YÖNETİR. */
    private static MockHttpSession scopedAdminViewingOnly5() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "mudur");
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("teamId", 2L);
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(2L, 5L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<>(List.of(2L)));
        return s;
    }

    private com.sitemonitor.model.LatestCheck pendingRenewal(String domain) {
        com.sitemonitor.model.LatestCheck lc = latestOf();
        lc.setDomain(domain);
        lc.setFingerprint("AA:BB");
        lc.setPinnedFingerprint("AA:BB");
        lc.setFingerprintChangedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1).withNano(0).toString());
        return lc;
    }

    @Test
    @DisplayName("A11: AUDIT yenileme ONAYLAYAMAZ ve tazeleme TETİKLEYEMEZ (403) — hiçbir şey yazılmaz/koşmaz")
    void healthWrites_audit_forbidden() throws Exception {
        com.sitemonitor.model.CertificateInventory inv = invOf(5L, 443);
        inv.setDomain("audit.example.com");
        when(inventoryRepo.findByDomain("audit.example.com")).thenReturn(java.util.Optional.of(inv));
        when(latestCheckRepo.findById("audit.example.com")).thenReturn(java.util.Optional.of(pendingRenewal("audit.example.com")));

        mvc.perform(post("/api/certificates/audit.example.com/health/confirm-renewal").session(auditSession()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/certificates/audit.example.com/health/refresh").session(auditSession()))
                .andExpect(status().isForbidden());

        verify(latestCheckRepo, never()).save(any());
        verify(checkerService, never()).check(eq("audit.example.com"), anyInt(), anyBoolean(), any(), any());
        verify(certService, never()).saveResult(any());
    }

    @Test
    @DisplayName("A11: kapsamlı müdür ast takımı GÖRÜR ama İŞLEM yapamaz (403); YÖNETTİĞİ takımda onay 200")
    void healthWrites_scopedAdmin_viewOnlyTeamForbidden_managedTeamOk() throws Exception {
        com.sitemonitor.model.CertificateInventory sub = invOf(5L, 443);
        sub.setDomain("ast.example.com");
        when(inventoryRepo.findByDomain("ast.example.com")).thenReturn(java.util.Optional.of(sub));
        when(latestCheckRepo.findById("ast.example.com")).thenReturn(java.util.Optional.of(pendingRenewal("ast.example.com")));
        mvc.perform(post("/api/certificates/ast.example.com/health/confirm-renewal").session(scopedAdminViewingOnly5()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/certificates/ast.example.com/health/refresh").session(scopedAdminViewingOnly5()))
                .andExpect(status().isForbidden());
        verify(latestCheckRepo, never()).save(any());

        com.sitemonitor.model.CertificateInventory own = invOf(2L, 443);
        own.setDomain("kendi.example.com");
        com.sitemonitor.model.LatestCheck lc = pendingRenewal("kendi.example.com");
        when(inventoryRepo.findByDomain("kendi.example.com")).thenReturn(java.util.Optional.of(own));
        when(latestCheckRepo.findById("kendi.example.com")).thenReturn(java.util.Optional.of(lc));
        mvc.perform(post("/api/certificates/kendi.example.com/health/confirm-renewal").session(scopedAdminViewingOnly5()))
                .andExpect(status().isOk());
        verify(latestCheckRepo).save(lc);
    }

    @Test
    @DisplayName("A11: onay inventory.crud/edit izni ister — izin yoksa 403, kayda bakılmaz bile")
    void confirmRenewal_requiresInventoryEditPermission() throws Exception {
        org.mockito.Mockito.doThrow(new SecurityException("Bu islem icin yetkiniz yok: inventory.crud/edit"))
                .when(permissionService).require(any(jakarta.servlet.http.HttpSession.class), eq("inventory.crud"), eq("edit"));

        mvc.perform(post("/api/certificates/a.example.com/health/confirm-renewal").session(teamSession(5L)))
                .andExpect(status().isForbidden());

        verify(inventoryRepo, never()).findByDomain("a.example.com");
        verify(latestCheckRepo, never()).save(any());
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

    // ── 2026-09-28: SSL Kontrol sekmesi — değerlendirme SUNUCUDAN (CertificateHealthRules, tek kural) ──────────

    /** check-preview için gerçek tel biçiminde başarılı el sıkışma sonucu (CertificateCheckerService anahtarları). */
    private static java.util.Map<String, Object> handshake(String domain, java.util.List<String> san, String tls,
            String cipher, String sigAlg, String keyAlg, int keySize, String trust) {
        java.util.Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("domain", domain);
        r.put("status", "valid");
        r.put("san", san);
        r.put("tls_version", tls);
        r.put("cipher_suite", cipher);
        r.put("signature_algorithm", sigAlg);
        r.put("public_key_algorithm", keyAlg);
        r.put("public_key_size", keySize);
        r.put("trust_status", trust);
        return r;
    }

    @Test
    @DisplayName("check-preview sağlıklı sonuca değerlendirme ekler: her satır OK, güvenlik bayrağı yok")
    void preview_addsAssessment_healthy() throws Exception {
        when(inventoryRepo.findByDomain("www.example.com")).thenReturn(java.util.Optional.empty());
        when(checkerService.check(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(handshake("www.example.com", List.of("example.com", "*.example.com"), "TLSv1.3",
                        "TLS_AES_128_GCM_SHA256", "SHA256withRSA", "RSA", 2048, "TRUSTED"));

        mvc.perform(get("/api/check-preview/www.example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assessment.hostname").value("OK"))
                .andExpect(jsonPath("$.data.assessment.protocol").value("OK"))
                .andExpect(jsonPath("$.data.assessment.protocol_latest").value(true))
                .andExpect(jsonPath("$.data.assessment.cipher").value("OK"))
                .andExpect(jsonPath("$.data.assessment.pfs").value("OK"))
                .andExpect(jsonPath("$.data.assessment.signature").value("OK"))
                .andExpect(jsonPath("$.data.assessment.key_size").value("OK"))
                .andExpect(jsonPath("$.data.security_flags").isEmpty());
    }

    @Test
    @DisplayName("check-preview: joker TEK etiket (RFC 6125) — *.example.com a.b.example.com'u kapsamaz; zayıf ayarlar FAIL")
    void preview_addsAssessment_weakAndMismatch() throws Exception {
        when(inventoryRepo.findByDomain("a.b.example.com")).thenReturn(java.util.Optional.empty());
        when(checkerService.check(anyString(), anyInt(), anyBoolean(), any(), any()))
                .thenReturn(handshake("a.b.example.com", List.of("*.example.com"), "TLSv1",
                        "TLS_RSA_WITH_3DES_EDE_CBC_SHA", "SHA1withRSA", "RSA", 1024, "UNTRUSTED"));

        mvc.perform(get("/api/check-preview/a.b.example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assessment.hostname").value("FAIL"))
                .andExpect(jsonPath("$.data.assessment.protocol").value("FAIL"))
                .andExpect(jsonPath("$.data.assessment.protocol_latest").value(false))
                .andExpect(jsonPath("$.data.assessment.cipher").value("FAIL"))
                .andExpect(jsonPath("$.data.assessment.pfs").value("FAIL"))
                .andExpect(jsonPath("$.data.assessment.signature").value("FAIL"))
                .andExpect(jsonPath("$.data.assessment.key_size").value("FAIL"))
                .andExpect(jsonPath("$.data.security_flags[0]").value("HOSTNAME_MISMATCH"))
                .andExpect(jsonPath("$.data.security_flags[1]").value("UNTRUSTED_CA"));
    }

    @Test
    @DisplayName("check-preview: ölçülmeyen alan UNKNOWN (FAIL değil); hata sonucuna değerlendirme EKLENMEZ")
    void preview_assessment_unknownAndErrorResult() throws Exception {
        when(inventoryRepo.findByDomain(anyString())).thenReturn(java.util.Optional.empty());
        java.util.Map<String, Object> bare = new java.util.LinkedHashMap<>();
        bare.put("domain", "bare.example.com");
        bare.put("status", "valid");
        bare.put("san", List.of());
        bare.put("public_key_size", -1);
        java.util.Map<String, Object> failed = new java.util.LinkedHashMap<>();
        failed.put("domain", "down.example.com");
        failed.put("status", "error");
        failed.put("error", "Connection timeout after 10s");
        failed.put("san", List.of());
        when(checkerService.check(eq("bare.example.com"), anyInt(), anyBoolean(), any(), any())).thenReturn(bare);
        when(checkerService.check(eq("down.example.com"), anyInt(), anyBoolean(), any(), any())).thenReturn(failed);

        mvc.perform(get("/api/check-preview/bare.example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assessment.hostname").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.assessment.protocol").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.assessment.key_size").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.security_flags").isEmpty());
        mvc.perform(get("/api/check-preview/down.example.com").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("error"))
                .andExpect(jsonPath("$.data.assessment").doesNotExist())
                .andExpect(jsonPath("$.data.security_flags").doesNotExist());
    }
    @Test
    @DisplayName("2026-09-12: GET /stats/executive gövdeyi servisten kapsam predicate'iyle döner")
    void executiveStats() throws Exception {
        org.mockito.Mockito.when(executiveStatsService.build(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Map.of("certs", java.util.Map.of("total", 5, "health_pct", 80.0), "teams", java.util.List.of()));
        mvc.perform(get("/api/stats/executive").session(teamSession(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.certs.total").value(5))
                .andExpect(jsonPath("$.data.certs.health_pct").value(80.0));
    }
    // ── 7/24 izleme ekibi operatörü (2026-10-04) ──────────────────────────────

    private MockHttpSession nocOperatorSession() {
        MockHttpSession s = scopedSession();
        s.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        return s;
    }

    @Test
    @DisplayName("7/24 operatörü: sertifika listesi/uyarılar/istatistik TÜM takımlar (kapsam null); başka takımın alarm geçmişini okur")
    void nocOperator_readsAllCertificates() throws Exception {
        when(certService.getAllLatestForTeams(null)).thenReturn(List.of());
        when(certService.getWarningsForTeams(null)).thenReturn(List.of());
        mvc.perform(get("/api/certificates").session(nocOperatorSession())).andExpect(status().isOk());
        mvc.perform(get("/api/warnings").session(nocOperatorSession())).andExpect(status().isOk());
        verify(certService).getAllLatestForTeams(null);
        verify(certService).getWarningsForTeams(null);

        when(inventoryRepo.findByDomain("baska.example.com")).thenReturn(java.util.Optional.of(invOf("baska.example.com", 9L)));
        when(alertEventRepo.findByDomainOrderByCreatedAtDesc("baska.example.com")).thenReturn(List.of());
        mvc.perform(get("/api/history/baska.example.com/alerts").session(nocOperatorSession())).andExpect(status().isOk());
        mvc.perform(get("/api/history/baska.example.com/alerts").session(scopedSession())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("7/24 operatörü: başka takımın alan adında anlık kontrol (/check — dış bağlantı + kayıt) 403 kalır")
    void nocOperator_cannotRunForeignCheck() throws Exception {
        when(inventoryRepo.findByDomain("baska.example.com")).thenReturn(java.util.Optional.of(invOf("baska.example.com", 9L)));
        mvc.perform(get("/api/check/baska.example.com").session(nocOperatorSession()))
                .andExpect(status().isForbidden());
        verify(checkerService, never()).check(anyString(), anyInt(), anyBoolean(), any());
    }

    // ── Elle yüklenen sertifika (2026-10-06) ─────────────────────────────────────────────────────────────────────

    private static com.sitemonitor.model.CertificateInventory manualInv(String key, Long teamId) {
        com.sitemonitor.model.CertificateInventory i = invOf(key, teamId);
        i.setId(31L);
        i.setCertSource(com.sitemonitor.model.CertificateInventory.SOURCE_MANUAL);
        return i;
    }

    @Test
    @DisplayName("manuel: /check ağa ÇIKMAZ — çevrim-dışı değerlendirme (yalnız kapanış), yanıt biçimi aynı")
    void check_manualRow_offline() throws Exception {
        when(inventoryRepo.findByDomain("api-takip")).thenReturn(java.util.Optional.of(manualInv("api-takip", null)));
        when(manualCertEvaluation.evaluateNow(any(), eq("manual"))).thenReturn(new java.util.LinkedHashMap<>(
                Map.of("domain", "api-takip", "status", "valid", "via", "upload")));
        mvc.perform(get("/api/check/api-takip").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.via").value("upload"))
                .andExpect(jsonPath("$.data.port").value(443));
        verify(checkerService, never()).check(anyString(), anyInt(), anyBoolean(), any(), any());
        verify(certService, never()).saveResult(any());   // kayıt değerlendirme servisinin işi
    }

    @Test
    @DisplayName("manuel: check-preview ÇEVRİM-DIŞI önizleme (2026-10-07) — el sıkışma / kayıt / alarm yok; ağa özgü hüküm NA, HOSTNAME_MISMATCH yok")
    void checkPreview_manualRow_offlinePreview() throws Exception {
        when(inventoryRepo.findByDomain("api-takip")).thenReturn(java.util.Optional.of(manualInv("api-takip", null)));
        java.util.Map<String, Object> built = new java.util.LinkedHashMap<>();
        built.put("domain", "api-takip");
        built.put("status", "valid");
        built.put("san", List.of("baska.example.test"));   // takip adı SAN'da yok — yine de HOSTNAME_MISMATCH olmamalı
        built.put("signature_algorithm", "SHA256withRSA");
        built.put("public_key_algorithm", "RSA");
        built.put("public_key_size", 1024);
        built.put("trust_status", "UNTRUSTED");
        built.put("chain", List.of(Map.of("position", 0, "is_leaf", true, "is_root", false)));
        built.put("via", "upload");
        built.put("manual", true);
        when(manualCertEvaluation.previewCurrent(any())).thenReturn(built);
        mvc.perform(get("/api/check-preview/api-takip").session(authSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.via").value("upload"))
                .andExpect(jsonPath("$.data.manual").value(true))
                .andExpect(jsonPath("$.data.chain[0].is_leaf").value(true))
                .andExpect(jsonPath("$.data.assessment.hostname").value("NA"))
                .andExpect(jsonPath("$.data.assessment.protocol").value("NA"))
                .andExpect(jsonPath("$.data.assessment.cipher").value("NA"))
                .andExpect(jsonPath("$.data.assessment.pfs").value("NA"))
                .andExpect(jsonPath("$.data.assessment.signature").value("OK"))
                .andExpect(jsonPath("$.data.assessment.key_size").value("FAIL"))
                .andExpect(jsonPath("$.data.security_flags", org.hamcrest.Matchers.contains("UNTRUSTED_CA")))
                // "Hiyerarşi" görünümü (2026-10-07) sürüm zincirini kayıt kimliğiyle çeker
                .andExpect(jsonPath("$.data.inventory_id").value(31))
                .andExpect(jsonPath("$.data.port").doesNotExist());
        verify(checkerService, never()).check(anyString(), anyInt(), anyBoolean(), any(), any());
        verify(manualCertEvaluation, never()).evaluateNow(any(), anyString());
        verify(certService, never()).saveResult(any());
        verify(certService, never()).evictAllCaches();
    }

    @Test
    @DisplayName("manuel: check-preview geçerli sürüm değerlendirilemezse 409 MANUAL_CERT — canlı el sıkışmaya düşmez")
    void checkPreview_manualRow_noVersion_409() throws Exception {
        when(inventoryRepo.findByDomain("api-takip")).thenReturn(java.util.Optional.of(manualInv("api-takip", null)));
        when(manualCertEvaluation.previewCurrent(any())).thenReturn(null);
        mvc.perform(get("/api/check-preview/api-takip").session(authSession()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("MANUAL_CERT"))
                .andExpect(jsonPath("$.error").isNotEmpty());
        verify(checkerService, never()).check(anyString(), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("manuel: sağlık tazeleme ağsız; uygulama katmanı yoklaması yok; ağa özgü satırlar NA + reason MANUAL")
    void healthRefresh_manualRow() throws Exception {
        // Ayrı ad: tazeleme soğuması (30 sn) denetleyici örneğinde tutulur — diğer testin alan adıyla çakışmasın.
        com.sitemonitor.model.CertificateInventory inv = manualInv("manuel-takip", 5L);
        when(inventoryRepo.findByDomain("manuel-takip")).thenReturn(java.util.Optional.of(inv));
        com.sitemonitor.model.LatestCheck lc = latestOf();
        lc.setDomain("manuel-takip");
        lc.setVia("upload");
        when(latestCheckRepo.findById("manuel-takip")).thenReturn(java.util.Optional.of(lc));
        mvc.perform(post("/api/certificates/manuel-takip/health/refresh").session(teamSession(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[?(@.key=='protocol')].status").value("NA"))
                .andExpect(jsonPath("$.data.rows[?(@.key=='protocol')].reason").value("MANUAL"))
                .andExpect(jsonPath("$.data.rows[?(@.key=='sanMatch')].status").value("NA"))
                .andExpect(jsonPath("$.data.rows[?(@.key=='expiry')].status").value("OK"))
                .andExpect(jsonPath("$.data.rows[?(@.key=='expiry')].reason").isEmpty());
        verify(manualCertEvaluation).evaluateNow(any(), eq("health-refresh"));
        verify(checkerService, never()).check(anyString(), anyInt(), anyBoolean(), any(), any());
        verify(appLayerProbe, never()).refresh(anyString(), anyInt(), anyBoolean());
    }
}
