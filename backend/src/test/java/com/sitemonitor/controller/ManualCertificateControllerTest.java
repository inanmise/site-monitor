package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import java.util.Map;
import java.util.Optional;

import static com.sitemonitor.service.manualcert.TestCerts.days;
import static com.sitemonitor.service.manualcert.TestCerts.pem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/manual-certs} (2026-10-06): kapılar, doğrulama (400 {@code errors}), çakışmalar (409 kodları), toplu
 * oluşturmada hep-ya-da-hiç, yenilemede sürümleme (eskisi kalır, tek geçerli), okuma kapsamı, PEM indirme, hız sınırı.
 * Analiz GERÇEK ayrıştırıcıyla koşar (fikstürler testte üretilir).
 */
@WebMvcTest(ManualCertificateController.class)
@Import({ManualCertificateAnalyzer.class, ManualCertificateService.class})
class ManualCertificateControllerTest {

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
    private static final String INVENTORY_JSON =
            "{\"team_id\":1,\"group_name\":\"Grup A\",\"tags\":\"t1\",\"tier\":2,\"port\":8443,\"use_proxy\":true,\"tls_mode\":\"browser\"}";

    @BeforeAll
    static void certs() {
        chain = TestCerts.chain("api.example.test", days(200));
        leafFp = ManualCertificateAnalyzer.fingerprint(chain.leaf());
    }

    @BeforeEach
    void setUp() {
        when(trustEvaluator.evaluate(any())).thenReturn(new TrustEvaluator.TrustResult(false, "x"));
        when(adminController.createInventoryRecord(any(), any())).thenAnswer(a -> {
            CertificateInventory i = a.getArgument(0);
            if (i.getId() == null) i.setId(5L);
            return i;
        });
        when(versionRepo.save(any())).thenAnswer(a -> {
            ManualCertificateVersion v = a.getArgument(0);
            if (v.getId() == null) v.setId(50L);
            return v;
        });
        when(versionRepo.saveAndFlush(any())).thenAnswer(a -> a.getArgument(0));
        when(evaluation.evaluateNow(any(), anyString())).thenReturn(Map.of("domain", "api-takip", "status", "valid"));
        when(userService.listTeams()).thenReturn(List.of());
        when(inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc(anyString())).thenReturn(Optional.empty());
    }

    private static MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "testuser");
        s.setAttribute("displayName", "Test Kullanıcı");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private static MockHttpSession user(long team) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "uye" + team);
        s.setAttribute("userId", 40L + team);
        s.setAttribute("teamId", team);
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(team)));
        s.setAttribute("memberTeamIds", new ArrayList<>(List.of(team)));
        s.setAttribute("manageTeamIds", new ArrayList<Long>());
        return s;
    }

    private static MockMultipartFile pemFile(String text) {
        return new MockMultipartFile("file", "sunucu.pem", "application/x-pem-file", TestCerts.utf8(text));
    }

    private static CertificateInventory manualRow(long id, String key, long team) {
        CertificateInventory i = new CertificateInventory();
        i.setId(id);
        i.setDomain(key);
        i.setTeamId(team);
        i.setActive(true);
        i.setPort(443);
        i.setCertSource(CertificateInventory.SOURCE_MANUAL);
        return i;
    }

    private static ManualCertificateVersion currentVersion(long invId, int v, String fp, String notAfter) {
        ManualCertificateVersion mv = new ManualCertificateVersion();
        mv.setId(100L + v);
        mv.setInventoryId(invId);
        mv.setVersion(v);
        mv.setCurrent(true);
        mv.setFingerprint(fp);
        mv.setNotAfter(notAfter);
        mv.setPublicKeySha256("ESKIANAHTAR");
        mv.setSubjectDn(chain.leaf().getSubjectX500Principal().getName());
        mv.setSan("[\"api.example.test\"]");
        mv.setChainPem(pem(chain.leaf()));
        return mv;
    }

    // ── Analiz ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("analiz: oturumsuz 401; PEM zinciri → girdiler, önerilen girdi, öneri adı; hiçbir şey yazılmaz")
    void analyze_pem() throws Exception {
        mvc.perform(multipart("/api/manual-certs/analyze").file(pemFile(pem(chain.leaf()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/manual-certs/analyze").file(pemFile(pem(chain.root(), chain.leaf(), chain.inter())))
                        .session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.format").value("PEM"))
                .andExpect(jsonPath("$.data.file_name").value("sunucu.pem"))
                .andExpect(jsonPath("$.data.entries", hasSize(3)))
                .andExpect(jsonPath("$.data.default_ref").value(leafFp))
                .andExpect(jsonPath("$.data.needs_password").value(false));
        verify(adminController, never()).createInventoryRecord(any(), any());
        verify(versionRepo, never()).save(any());
        verify(auditService, never()).recordAction(anyString(), any(HttpSession.class), any(HttpServletRequest.class),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("analiz: dosya da metin de yoksa 400 errors.file")
    void analyze_missingInput() throws Exception {
        mvc.perform(multipart("/api/manual-certs/analyze").session(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").exists());
    }

    @Test
    @DisplayName("analiz: kullanıcı başına dakikada 30 — 31. istek 429")
    void analyze_rateLimit() throws Exception {
        MockHttpSession s = admin();
        s.setAttribute("username", "hiz-siniri-kullanicisi");   // kova kullanıcı başına — diğer testleri etkilemesin
        String text = pem(chain.root());
        for (int i = 0; i < 30; i++) {
            mvc.perform(multipart("/api/manual-certs/analyze").param("text", text).session(s)).andExpect(status().isOk());
        }
        mvc.perform(multipart("/api/manual-certs/analyze").param("text", text).session(s))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    // ── Oluşturma ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("oluşturma: ağ eklemesinin kapılarından geçer; kaynak MANUAL, ağ alanları boş; sürüm 1; denetimde parola/dosya YOK")
    void create_success() throws Exception {
        mvc.perform(multipart("/api/manual-certs").file(pemFile(pem(chain.leaf(), chain.inter())))
                        .param("password", "GIZLI-PAROLA-123")
                        .param("ref", leafFp).param("domain", " Api-Takip ")
                        .param("inventory", INVENTORY_JSON).param("note", "ilk yükleme")
                        .session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.inventory_id").value(5))
                .andExpect(jsonPath("$.data.domain").value("api-takip"))
                .andExpect(jsonPath("$.data.version").value(1));

        ArgumentCaptor<CertificateInventory> item = ArgumentCaptor.forClass(CertificateInventory.class);
        verify(adminController).createInventoryRecord(item.capture(), any());
        CertificateInventory sent = item.getValue();
        assertThat(sent.isManual()).isTrue();
        assertThat(sent.getDomain()).isEqualTo("api-takip");
        assertThat(sent.getPort()).isEqualTo(443);
        assertThat(sent.getUseProxy()).isNull();
        assertThat(sent.getTlsMode()).isNull();
        assertThat(sent.getTier()).isEqualTo(2);

        ArgumentCaptor<ManualCertificateVersion> v = ArgumentCaptor.forClass(ManualCertificateVersion.class);
        verify(versionRepo).save(v.capture());
        assertThat(v.getValue().getVersion()).isEqualTo(1);
        assertThat(v.getValue().getCurrent()).isTrue();
        assertThat(v.getValue().getFingerprint()).isEqualTo(leafFp);
        assertThat(v.getValue().getChainCount()).isEqualTo(2);
        assertThat(v.getValue().getChainPem()).doesNotContain("PRIVATE");
        assertThat(v.getValue().getFileName()).isEqualTo("sunucu.pem");
        assertThat(v.getValue().getUploadedBy()).isEqualTo("testuser");
        assertThat(v.getValue().getNote()).isEqualTo("ilk yükleme");

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("CERT_MANUAL_UPLOAD"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("CERTIFICATE"), eq("api-takip"), detail.capture());
        assertThat(detail.getValue()).contains(leafFp).contains("PEM").doesNotContain("GIZLI-PAROLA-123")
                .doesNotContain("BEGIN");
        verify(adminController).recordInventoryCreateHistory(any(), any());
        verify(evaluation).evaluateNow(any(), eq("manual"));
        verify(certService).evictAllCaches();
    }

    @Test
    @DisplayName("oluşturma: geçersiz takip adı / takım / seçim → 400 errors{alan}; kayıt açılmaz")
    void create_validation() throws Exception {
        mvc.perform(multipart("/api/manual-certs").file(pemFile(pem(chain.leaf())))
                        .param("ref", "YOK").param("domain", "a b")
                        .param("inventory", "{\"group_name\":\"G\",\"tags\":\"t\"}")
                        .session(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.domain").exists())
                .andExpect(jsonPath("$.errors.team_id").exists())
                .andExpect(jsonPath("$.errors.ref").exists());
        verify(adminController, never()).createInventoryRecord(any(), any());
    }

    @Test
    @DisplayName("oluşturma: şifreli PFX parolasız → 400 errors.password")
    void create_pfxNeedsPassword() throws Exception {
        byte[] pfx = TestCerts.pkcs12("a", chain.leafKey().getPrivate(), "p".toCharArray(), chain.leaf());
        mvc.perform(multipart("/api/manual-certs").file(new MockMultipartFile("file", "a.pfx", "application/x-pkcs12", pfx))
                        .param("ref", leafFp).param("domain", "api-takip").param("inventory", INVENTORY_JSON)
                        .session(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    @DisplayName("oluşturma: takip adı envanterde var (harf duyarsız, silinmişler dahil) → 409 KEY_EXISTS")
    void create_keyExists() throws Exception {
        when(inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc("api-takip")).thenReturn(Optional.of(manualRow(9, "API-takip", 3)));
        mvc.perform(multipart("/api/manual-certs").file(pemFile(pem(chain.leaf())))
                        .param("ref", leafFp).param("domain", "api-takip").param("inventory", INVENTORY_JSON)
                        .session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEY_EXISTS"))
                .andExpect(jsonPath("$.domain").value("api-takip"));
        verify(adminController, never()).createInventoryRecord(any(), any());
    }

    @Test
    @DisplayName("oluşturma: aynı parmak izi zaten izleniyor → 409 ALREADY_TRACKED {inventory_id, domain}")
    void create_alreadyTracked() throws Exception {
        ManualCertificateVersion tracked = currentVersion(9L, 1, leafFp, "2027-01-01T00:00:00");
        when(versionRepo.findByCurrentTrueAndFingerprintIn(anyCollection())).thenReturn(List.of(tracked));
        when(inventoryRepo.findAllById(any())).thenReturn(List.of(manualRow(9, "api-eski", 1)));
        mvc.perform(multipart("/api/manual-certs").file(pemFile(pem(chain.leaf())))
                        .param("ref", leafFp).param("domain", "api-yeni").param("inventory", INVENTORY_JSON)
                        .session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_TRACKED"))
                .andExpect(jsonPath("$.inventory_id").value(9))
                .andExpect(jsonPath("$.domain").value("api-eski"));
    }

    // ── Toplu ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("toplu: hep ya da hiç — ikinci kayıt düşerse 400, denetim/geçmiş/değerlendirme HİÇ yok")
    void batch_allOrNothing() throws Exception {
        String rootFp = ManualCertificateAnalyzer.fingerprint(chain.root());
        String interFp = ManualCertificateAnalyzer.fingerprint(chain.inter());
        org.mockito.Mockito.doAnswer(a -> {
            CertificateInventory i = a.getArgument(0);
            if ("ara-ca".equals(i.getDomain())) throw new IllegalArgumentException("Seçilen takım bulunamadı");
            i.setId(11L);
            return i;
        }).when(adminController).createInventoryRecord(any(), any());
        mvc.perform(multipart("/api/manual-certs/batch").file(pemFile(pem(chain.inter(), chain.root())))
                        .param("items", "[{\"ref\":\"" + rootFp + "\",\"domain\":\"kok-ca\"},{\"ref\":\"" + interFp + "\",\"domain\":\"ara-ca\"}]")
                        .param("inventory", INVENTORY_JSON)
                        .session(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Seçilen takım bulunamadı"));
        verify(txManager).rollback(any());
        verify(auditService, never()).recordAction(eq("CERT_MANUAL_UPLOAD"), any(HttpSession.class),
                any(HttpServletRequest.class), anyString(), anyString(), anyString());
        verify(evaluation, never()).evaluateNow(any(), anyString());
    }

    @Test
    @DisplayName("toplu: başarı → {created:[…]}; listede tekrar eden takip adı satır hatası (items[1].domain)")
    void batch_successAndDuplicates() throws Exception {
        String rootFp = ManualCertificateAnalyzer.fingerprint(chain.root());
        String interFp = ManualCertificateAnalyzer.fingerprint(chain.inter());
        mvc.perform(multipart("/api/manual-certs/batch").file(pemFile(pem(chain.inter(), chain.root())))
                        .param("items", "[{\"ref\":\"" + rootFp + "\",\"domain\":\"ayni\"},{\"ref\":\"" + interFp + "\",\"domain\":\"AYNI\"}]")
                        .param("inventory", INVENTORY_JSON).session(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['items[1].domain']").exists());

        mvc.perform(multipart("/api/manual-certs/batch").file(pemFile(pem(chain.inter(), chain.root())))
                        .param("items", "[{\"ref\":\"" + rootFp + "\",\"domain\":\"kok-ca\"},{\"ref\":\"" + interFp + "\",\"domain\":\"ara-ca\"}]")
                        .param("inventory", INVENTORY_JSON).session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created", hasSize(2)))
                .andExpect(jsonPath("$.data.created[1].domain").value("ara-ca"));
        verify(auditService, times(2)).recordAction(eq("CERT_MANUAL_UPLOAD"), any(HttpSession.class),
                any(HttpServletRequest.class), eq("CERTIFICATE"), anyString(), anyString());
    }

    // ── Yenileme ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("yenileme: aynı sertifika 409 SAME_CERTIFICATE; daha eski bitiş onaysız 409 OLDER_THAN_CURRENT")
    void renew_conflicts() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 1)));
        when(versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(5L))
                .thenReturn(Optional.of(currentVersion(5L, 1, leafFp, "2030-01-01T00:00:00")));
        mvc.perform(multipart("/api/manual-certs/5/versions").file(pemFile(pem(chain.leaf()))).param("ref", leafFp).session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_CERTIFICATE"));

        when(versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(5L))
                .thenReturn(Optional.of(currentVersion(5L, 1, "BASKA", "2099-01-01T00:00:00")));
        mvc.perform(multipart("/api/manual-certs/5/versions").file(pemFile(pem(chain.leaf()))).param("ref", leafFp).session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OLDER_THAN_CURRENT"))
                .andExpect(jsonPath("$.current_date").value("2099-01-01T00:00:00"));
        verify(versionRepo, never()).save(any());
        verify(auditService, never()).recordAction(eq("CERT_MANUAL_RENEW"), any(HttpSession.class),
                any(HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("yenileme: eskisi SİLİNMEZ — önce current=false + superseded_* (flush), sonra yeni sürüm v2 geçerli")
    void renew_versioning() throws Exception {
        CertificateInventory row = manualRow(5, "api-takip", 1);
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(row));
        ManualCertificateVersion old = currentVersion(5L, 1, "BASKA", "2026-11-01T00:00:00");
        when(versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(5L)).thenReturn(Optional.of(old));
        when(versionRepo.findMaxVersion(5L)).thenReturn(1);

        mvc.perform(multipart("/api/manual-certs/5/versions").file(pemFile(pem(chain.leaf(), chain.inter())))
                        .param("ref", leafFp).param("note", "yenilendi").session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.previous_version").value(1))
                .andExpect(jsonPath("$.data.warnings[0].code").value("KEY_CHANGED"));

        InOrder order = inOrder(versionRepo);
        ArgumentCaptor<ManualCertificateVersion> flushed = ArgumentCaptor.forClass(ManualCertificateVersion.class);
        order.verify(versionRepo).saveAndFlush(flushed.capture());
        ArgumentCaptor<ManualCertificateVersion> created = ArgumentCaptor.forClass(ManualCertificateVersion.class);
        order.verify(versionRepo).save(created.capture());
        assertThat(flushed.getValue().getCurrent()).isFalse();
        assertThat(flushed.getValue().getSupersededBy()).isEqualTo("testuser");
        assertThat(flushed.getValue().getSupersededAt()).isNotNull();
        assertThat(created.getValue().getVersion()).isEqualTo(2);
        assertThat(created.getValue().getCurrent()).isTrue();
        verify(versionRepo, never()).delete(any());
        verify(versionRepo, never()).deleteByInventoryId(any());
        verify(auditService).recordAction(eq("CERT_MANUAL_RENEW"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("CERTIFICATE"), eq("api-takip"), anyString());
        verify(adminController).recordInventoryUpdateHistory(any(), any(), contains("v2"), any());
        verify(evaluation).evaluateNow(any(), eq("manual"));
    }

    @Test
    @DisplayName("yenileme: başka takımın kaydı (görüş kapsamı dışı) 404 — varlık sızdırılmaz")
    void renew_otherTeam() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 1)));
        mvc.perform(multipart("/api/manual-certs/5/versions").file(pemFile(pem(chain.leaf()))).param("ref", leafFp).session(user(2)))
                .andExpect(status().isNotFound());
    }

    // ── Okuma ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("liste: USER yalnız kendi takımının manuel kayıtlarını görür; sürüm özeti + can_manage")
    void list_scoped() throws Exception {
        when(inventoryRepo.findByCertSourceAndDeletedAtIsNullOrderByDomainAsc("MANUAL"))
                .thenReturn(List.of(manualRow(5, "benim-takip", 2), manualRow(6, "baska-takip", 3)));
        when(versionRepo.findByInventoryIdInAndCurrentTrue(anyCollection()))
                .thenReturn(List.of(currentVersion(5L, 2, "FP5", "2027-01-01T00:00:00")));
        when(versionRepo.countByInventoryIds(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{5L, 2L}));
        mvc.perform(get("/api/manual-certs").session(user(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].domain").value("benim-takip"))
                .andExpect(jsonPath("$.data[0].current_version.version").value(2))
                .andExpect(jsonPath("$.data[0].versions_count").value(2))
                .andExpect(jsonPath("$.data[0].can_manage").value(true));
    }

    @Test
    @DisplayName("detay: sürümler yeniden eskiye + fark; başka takımın kaydı 404")
    void detail() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 2)));
        ManualCertificateVersion v2 = currentVersion(5L, 2, "FP2", "2027-01-01T00:00:00");
        v2.setPublicKeySha256("YENIANAHTAR");
        v2.setSan("[\"api.example.test\",\"www.example.test\"]");
        ManualCertificateVersion v1 = currentVersion(5L, 1, "FP1", "2026-01-01T00:00:00");
        v1.setCurrent(false);
        v1.setSupersededBy("uye2");
        when(versionRepo.findByInventoryIdOrderByVersionDesc(5L)).thenReturn(List.of(v2, v1));
        mvc.perform(get("/api/manual-certs/5").session(user(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions", hasSize(2)))
                .andExpect(jsonPath("$.data.versions[0].current").value(true))
                .andExpect(jsonPath("$.data.versions[0].key_changed").value(true))
                .andExpect(jsonPath("$.data.versions[0].san_added[0]").value("www.example.test"))
                .andExpect(jsonPath("$.data.versions[1].superseded_by").value("uye2"))
                .andExpect(jsonPath("$.data.versions[1].key_changed").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(get("/api/manual-certs/5").session(user(3))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PEM indirme: application/x-pem-file, <takip-adı>-v<sürüm>.pem; başka kaydın sürümü 404")
    void pemDownload() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "*.example.test", 2)));
        ManualCertificateVersion v = currentVersion(5L, 3, "FP", "2027-01-01T00:00:00");
        when(versionRepo.findById(103L)).thenReturn(Optional.of(v));
        mvc.perform(get("/api/manual-certs/5/versions/103/pem").session(user(2)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/x-pem-file")))
                .andExpect(header().string("Content-Disposition", containsString("wildcard.example.test-v3.pem")))
                .andExpect(content().string(containsString("-----BEGIN CERTIFICATE-----")));
        ManualCertificateVersion foreign = currentVersion(6L, 1, "FPX", "2027-01-01T00:00:00");
        when(versionRepo.findById(200L)).thenReturn(Optional.of(foreign));
        mvc.perform(get("/api/manual-certs/5/versions/200/pem").session(user(2))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("değerlendir: ağsız yeniden değerlendirme (yalnız kapanış) + denetim; salt-okur kapsam dışı 403/404")
    void evaluate() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 2)));
        mvc.perform(post("/api/manual-certs/5/evaluate").session(user(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("valid"))
                .andExpect(jsonPath("$.data.port").value(443));
        verify(evaluation).evaluateNow(any(), eq("manual"));
        verify(auditService).recordAction(eq("CERT_HEALTH_REFRESH"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("CERTIFICATE"), eq("api-takip"), anyString());
        mvc.perform(post("/api/manual-certs/5/evaluate").session(user(3))).andExpect(status().isNotFound());
    }

    private static String contains(String s) {
        return org.mockito.ArgumentMatchers.contains(s);
    }
}
