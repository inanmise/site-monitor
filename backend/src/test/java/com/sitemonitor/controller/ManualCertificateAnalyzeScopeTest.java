package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.ManualCertificateVersion;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.ManualCertificateVersionRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.MonitorHistoryService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.manualcert.ManualCertificateAnalyzer;
import com.sitemonitor.service.manualcert.ManualCertificateEvaluationService;
import com.sitemonitor.service.manualcert.ManualCertificateService;
import com.sitemonitor.service.manualcert.TestCerts;
import com.sitemonitor.service.manualcert.TestCerts.Chain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.sitemonitor.service.manualcert.TestCerts.days;
import static com.sitemonitor.service.manualcert.TestCerts.pem;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UIM-24 / K6 (2026-10-22): {@code /api/manual-certs/analyze} eşleşmeleri ({@code already_tracked}, {@code same_subject},
 * {@code network_monitored}) yalnız ÇAĞIRANIN görebildiği kayıtlar + kayıt başına {@code can_write}. Eskiden B takımının
 * takip adları ve alan adları A takımı kullanıcısına dönüyor, sihirbaz "yenile"yi o kayda kuruyor, kaydetme 403 alıyordu.
 * Oluşturma reddi (ALREADY_TRACKED) aynen sürer ama göremediğiniz kaydın adını söylemez.
 */
@WebMvcTest(ManualCertificateController.class)
@Import({ManualCertificateAnalyzer.class, ManualCertificateService.class})
class ManualCertificateAnalyzeScopeTest {

    @Autowired MockMvc mvc;

    @MockitoBean ManualCertificateEvaluationService evaluation;
    @MockitoBean ManualCertificateVersionRepository versionRepo;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean LatestCheckRepository latestRepo;
    @MockitoBean MonitorHistoryService monitorHistory;
    @MockitoBean AdminController adminController;
    @MockitoBean CertificateService certService;
    @MockitoBean PlatformTransactionManager txManager;
    @MockitoBean TrustEvaluator trustEvaluator;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private static Chain chain;
    private static String leafFp;

    @BeforeAll
    static void certs() {
        chain = TestCerts.chain("api.example.test", days(200));
        leafFp = ManualCertificateAnalyzer.fingerprint(chain.leaf());
    }

    private static CertificateInventory row(long id, String key, long team, boolean manual) {
        CertificateInventory i = new CertificateInventory();
        i.setId(id);
        i.setDomain(key);
        i.setTeamId(team);
        i.setActive(true);
        i.setPort(443);
        if (manual) i.setCertSource(CertificateInventory.SOURCE_MANUAL);
        return i;
    }

    private static ManualCertificateVersion version(long invId, String fp) {
        ManualCertificateVersion mv = new ManualCertificateVersion();
        mv.setId(500L + invId);
        mv.setInventoryId(invId);
        mv.setVersion(1);
        mv.setCurrent(true);
        mv.setFingerprint(fp);
        mv.setNotAfter("2026-01-01T00:00:00");
        mv.setSubjectDn(chain.leaf().getSubjectX500Principal().getName());
        return mv;
    }

    /**
     * Takım 2'nin kaydı (8) bu sertifikayı ZATEN takip ediyor; takım 2 (9) ve takım 1 (10) aynı konu adlı eski sertifika
     * kayıtları; takım 2'nin ağ kaydı aynı sertifikayı sunuyor.
     */
    @BeforeEach
    void setUp() {
        when(trustEvaluator.evaluate(any())).thenReturn(new TrustEvaluator.TrustResult(false, "x"));
        when(userService.listTeams()).thenReturn(List.of());
        when(inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc(anyString())).thenReturn(Optional.empty());
        when(versionRepo.findByCurrentTrueAndFingerprintIn(anyCollection())).thenReturn(List.of(version(8, leafFp)));
        when(versionRepo.findByCurrentTrueAndSubjectDnIn(anyCollection()))
                .thenReturn(List.of(version(9, "ESKI9"), version(10, "ESKI10")));
        when(inventoryRepo.findAllById(any())).thenReturn(List.of(
                row(8, "b-takip", 2, true), row(9, "b-eski", 2, true), row(10, "a-eski", 1, true)));
        LatestCheck lc = new LatestCheck();
        lc.setDomain("b.network.test");
        lc.setFingerprint(leafFp);
        when(latestRepo.findByFingerprintIn(anyCollection())).thenReturn(List.of(lc));
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of(row(20, "b.network.test", 2, false)));
    }

    private static MockHttpSession user(long team, List<Long> view) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "uye" + team);
        s.setAttribute("userId", 40L + team);
        s.setAttribute("teamId", team);
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(view));
        s.setAttribute("memberTeamIds", new ArrayList<>(List.of(team)));
        s.setAttribute("manageTeamIds", new ArrayList<Long>());
        return s;
    }

    private static MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "testuser");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private static MockMultipartFile leafFile() {
        return new MockMultipartFile("file", "sunucu.pem", "application/x-pem-file", TestCerts.utf8(pem(chain.leaf())));
    }

    @Test
    @DisplayName("Takım 1 üyesi: yalnız kendi takımının aynı konu kaydı (can_write=true); B takımının adları YOK")
    void teamMember_seesOnlyOwnMatches() throws Exception {
        mvc.perform(multipart("/api/manual-certs/analyze").file(leafFile()).session(user(1, List.of(1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[0].matches.already_tracked").doesNotExist())
                .andExpect(jsonPath("$.data.entries[0].matches.same_subject", hasSize(1)))
                .andExpect(jsonPath("$.data.entries[0].matches.same_subject[0].inventory_id").value(10))
                .andExpect(jsonPath("$.data.entries[0].matches.same_subject[0].can_write").value(true))
                .andExpect(jsonPath("$.data.entries[0].matches.network_monitored", hasSize(0)))
                .andExpect(content().string(not(containsString("b-takip"))))
                .andExpect(content().string(not(containsString("b-eski"))))
                .andExpect(content().string(not(containsString("b.network.test"))));
    }

    @Test
    @DisplayName("Takım 2'yi GÖREN ama yazamayan kullanıcı: B kayıtları görünür, can_write=false (sihirbaz yenilemeyi önermez)")
    void viewerWithoutWrite_seesMatchesNotWritable() throws Exception {
        mvc.perform(multipart("/api/manual-certs/analyze").file(leafFile()).session(user(1, List.of(1L, 2L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[0].matches.already_tracked.domain").value("b-takip"))
                .andExpect(jsonPath("$.data.entries[0].matches.already_tracked.can_write").value(false))
                .andExpect(jsonPath("$.data.entries[0].matches.same_subject", hasSize(2)))
                .andExpect(jsonPath("$.data.entries[0].matches.same_subject[?(@.inventory_id == 9)].can_write").value(false))
                .andExpect(jsonPath("$.data.entries[0].matches.same_subject[?(@.inventory_id == 10)].can_write").value(true))
                .andExpect(jsonPath("$.data.entries[0].matches.network_monitored[0].domain").value("b.network.test"));
    }

    @Test
    @DisplayName("Global admin: tüm eşleşmeler + can_write=true")
    void globalAdmin_seesAll() throws Exception {
        mvc.perform(multipart("/api/manual-certs/analyze").file(leafFile()).session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[0].matches.already_tracked.can_write").value(true))
                .andExpect(jsonPath("$.data.entries[0].matches.same_subject", hasSize(2)))
                .andExpect(jsonPath("$.data.entries[0].matches.network_monitored", hasSize(1)));
    }

    @Test
    @DisplayName("Göremediği kaydın takip ettiği sertifikayı oluşturma → 409 ALREADY_TRACKED, ad / kimlik YOK")
    void create_alreadyTrackedByHiddenRecord_namesNothing() throws Exception {
        mvc.perform(multipart("/api/manual-certs").file(leafFile())
                        .param("ref", leafFp).param("domain", "a-yeni")
                        .param("inventory", "{\"team_id\":1,\"group_name\":\"Grup A\",\"tags\":\"t1\",\"tier\":2,\"port\":8443,\"use_proxy\":true,\"tls_mode\":\"browser\"}").session(user(1, List.of(1L))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_TRACKED"))
                .andExpect(jsonPath("$.domain").doesNotExist())
                .andExpect(jsonPath("$.inventory_id").doesNotExist())
                .andExpect(content().string(not(containsString("b-takip"))));
    }
}
