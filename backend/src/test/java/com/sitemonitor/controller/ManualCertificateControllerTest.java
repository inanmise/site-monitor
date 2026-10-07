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
    @DisplayName("analiz: oturumsuz 401; yaprak + ara + kök → TEK girdi (zincir başı) + çevrim-dışı önizleme; hiçbir şey yazılmaz")
    void analyze_pem() throws Exception {
        when(evaluation.previewChain(any(), any(), any())).thenReturn(Map.of("via", "upload", "manual", true));
        mvc.perform(multipart("/api/manual-certs/analyze").file(pemFile(pem(chain.leaf()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/manual-certs/analyze").file(pemFile(pem(chain.root(), chain.leaf(), chain.inter())))
                        .session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.format").value("PEM"))
                .andExpect(jsonPath("$.data.file_name").value("sunucu.pem"))
                .andExpect(jsonPath("$.data.certificate_count").value(3))
                .andExpect(jsonPath("$.data.entries", hasSize(1)))
                .andExpect(jsonPath("$.data.entries[0].ref").value(leafFp))
                .andExpect(jsonPath("$.data.entries[0].chain", hasSize(2)))
                .andExpect(jsonPath("$.data.entries[0].preview.via").value("upload"))
                .andExpect(jsonPath("$.data.default_ref").value(leafFp))
                .andExpect(jsonPath("$.data.needs_password").value(false));
        // Önizleme zincir başı + onun zinciriyle (ara, kök) kurulur
        ArgumentCaptor<java.security.cert.X509Certificate> head = ArgumentCaptor.forClass(java.security.cert.X509Certificate.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<java.security.cert.X509Certificate>> issuers = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(evaluation).previewChain(eq("api.example.test"), head.capture(), issuers.capture());
        assertThat(head.getValue()).isEqualTo(chain.leaf());
        assertThat(issuers.getValue()).containsExactly(chain.inter(), chain.root());
        verify(evaluation, never()).evaluateNow(any(), anyString());
        verify(adminController, never()).createInventoryRecord(any(), any());
        verify(versionRepo, never()).save(any());
        verify(auditService, never()).recordAction(anyString(), any(HttpSession.class), any(HttpServletRequest.class),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("analiz: ilgisiz iki kök → iki bağımsız girdi (toplu seçilebilir); önizleme düşerse analiz yine 200")
    void analyze_independentRoots() throws Exception {
        when(evaluation.previewChain(any(), any(), any())).thenThrow(new IllegalStateException("önizleme yok"));
        java.security.cert.X509Certificate other = TestCerts.root("Example Other Root CA", TestCerts.freshRsa(), days(-10), days(900));
        mvc.perform(multipart("/api/manual-certs/analyze").file(pemFile(pem(chain.root(), other))).session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(2)))
                .andExpect(jsonPath("$.data.certificate_count").value(2))
                .andExpect(jsonPath("$.data.entries[0].preview").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.default_ref").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("oluşturma: zincire katlanmış ara / kök sertifika ref olamaz → 400 errors.ref (baş adlandırılır); kayıt açılmaz")
    void create_foldedChainMember_rejected() throws Exception {
        String interFp = ManualCertificateAnalyzer.fingerprint(chain.inter());
        mvc.perform(multipart("/api/manual-certs").file(pemFile(pem(chain.leaf(), chain.inter(), chain.root())))
                        .param("ref", interFp).param("domain", "ara-takip").param("inventory", INVENTORY_JSON)
                        .session(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ref", containsString("api.example.test")))
                .andExpect(jsonPath("$.errors.ref", containsString("zincir")));
        mvc.perform(multipart("/api/manual-certs").file(pemFile(pem(chain.leaf(), chain.inter(), chain.root())))
                        .param("ref", interFp).param("domain", "ara-takip").param("inventory", INVENTORY_JSON)
                        .header("X-Lang", "en").session(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ref", containsString("part of the chain of")));
        verify(adminController, never()).createInventoryRecord(any(), any());
        verify(versionRepo, never()).save(any());
    }

    @Test
    @DisplayName("yenileme: zincire katlanmış sertifika ref olamaz → 400 errors.ref; sürüm yazılmaz")
    void renew_foldedChainMember_rejected() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 1)));
        String rootFp = ManualCertificateAnalyzer.fingerprint(chain.root());
        mvc.perform(multipart("/api/manual-certs/5/versions").file(pemFile(pem(chain.leaf(), chain.inter(), chain.root())))
                        .param("ref", rootFp).session(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ref", containsString("api.example.test")));
        verify(versionRepo, never()).save(any());
        verify(versionRepo, never()).saveAndFlush(any());
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

    // ── Özel anahtar tarayıcıdan çıkmaz (2026-10-08, kullanıcı isteği) ────────

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();
    private static final java.util.concurrent.atomic.AtomicInteger EX = new java.util.concurrent.atomic.AtomicInteger();

    /** Aynı yetkili yönetici, her istekte AYRI kullanıcı adı — dakikalık hız sınırı kovası diğer testlerle paylaşılmasın. */
    private static MockHttpSession adminX() {
        MockHttpSession s = admin();
        s.setAttribute("username", "mcert-x-" + EX.incrementAndGet());
        return s;
    }

    private static String b64(java.security.cert.X509Certificate c) {
        return java.util.Base64.getEncoder().encodeToString(TestCerts.der(c));
    }

    /** Tarayıcının gönderdiği `extracted` gövdesi: her sertifika ayrı girdi (PEM gibi), ya da verilen girdiler. */
    private static Map<String, Object> extracted(String format, int privateKeys, List<Map<String, Object>> entries, List<String> csrPem) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("format", format);
        m.put("file_name", "sunucu." + format.toLowerCase());
        m.put("size_bytes", 2048);
        m.put("entries", entries);
        m.put("csr_pem", csrPem);
        m.put("private_keys_removed", privateKeys);
        return m;
    }

    private static Map<String, Object> entry(String alias, boolean keyEntry, java.security.cert.X509Certificate... certs) {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("alias", alias);
        e.put("key_entry", keyEntry);
        e.put("certs", java.util.Arrays.stream(certs).map(ManualCertificateControllerTest::b64).toList());
        return e;
    }

    private static MockMultipartFile extractedPart(Map<String, Object> body) {
        return new MockMultipartFile("extracted", "extracted.json", "application/json", JSON.writeValueAsBytes(body));
    }

    @Test
    @DisplayName("extracted (dosya parçası): yaprak + ara + kök ayrı ayrı gelse de TEK girdi; PRIVATE_KEY_KEPT_LOCAL {count}; biçim istemcinin")
    void analyze_extracted_groupsChain() throws Exception {
        Map<String, Object> body = extracted("PKCS12", 1, List.of(entry("sunucu", true, chain.leaf()),
                entry(null, false, chain.inter()), entry(null, false, chain.root())), List.of());
        mvc.perform(multipart("/api/manual-certs/analyze").file(extractedPart(body)).session(adminX()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.format").value("PKCS12"))
                .andExpect(jsonPath("$.data.file_name").value("sunucu.pkcs12"))
                .andExpect(jsonPath("$.data.size_bytes").value(2048))
                .andExpect(jsonPath("$.data.certificate_count").value(3))
                .andExpect(jsonPath("$.data.entries", hasSize(1)))
                .andExpect(jsonPath("$.data.entries[0].ref").value(leafFp))
                .andExpect(jsonPath("$.data.entries[0].alias").value("sunucu"))
                .andExpect(jsonPath("$.data.entries[0].is_key_entry").value(true))
                .andExpect(jsonPath("$.data.entries[0].chain", hasSize(2)))
                .andExpect(jsonPath("$.data.default_ref").value(leafFp))
                .andExpect(jsonPath("$.data.needs_password").value(false))
                .andExpect(jsonPath("$.data.warnings[?(@.code == 'PRIVATE_KEY_KEPT_LOCAL')].params.count").value(1));
        // Form alanı olarak da kabul edilir (API istemcileri: curl -F 'extracted=<json')
        mvc.perform(multipart("/api/manual-certs/analyze").param("extracted", JSON.writeValueAsString(body)).session(adminX()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(1)));
        verify(versionRepo, never()).save(any());
    }

    @Test
    @DisplayName("extracted: anahtar girdisinin yaprağı varsayılan (güvenilen yaprak önce gelse de); oluştur → sürümde biçim JKS, denetimde parola / anahtar yok")
    void create_extracted_keyEntryPreferred() throws Exception {
        java.security.cert.X509Certificate other = TestCerts.leaf("other.example.test", List.of("other.example.test"),
                TestCerts.freshRsa(), chain.inter(), chain.interKey().getPrivate(), days(-5), days(300));
        Map<String, Object> body = extracted("JKS", 1, List.of(entry("diger", false, other),
                entry("sunucu", true, chain.leaf(), chain.inter(), chain.root())), List.of());
        mvc.perform(multipart("/api/manual-certs/analyze").file(extractedPart(body)).session(adminX()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(2)))
                .andExpect(jsonPath("$.data.default_ref").value(leafFp));
        mvc.perform(multipart("/api/manual-certs").file(extractedPart(body))
                        .param("ref", leafFp).param("domain", "api-takip").param("inventory", INVENTORY_JSON)
                        .session(adminX()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domain").value("api-takip"));
        ArgumentCaptor<ManualCertificateVersion> v = ArgumentCaptor.forClass(ManualCertificateVersion.class);
        verify(versionRepo).save(v.capture());
        assertThat(v.getValue().getFingerprint()).isEqualTo(leafFp);
        assertThat(v.getValue().getFileFormat()).isEqualTo("JKS");
        assertThat(v.getValue().getFileName()).isEqualTo("sunucu.jks");
        assertThat(v.getValue().getSourceAlias()).isEqualTo("sunucu");
        assertThat(v.getValue().getChainPem()).doesNotContain("PRIVATE");
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("CERT_MANUAL_UPLOAD"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("CERTIFICATE"), eq("api-takip"), detail.capture());
        assertThat(detail.getValue()).contains("JKS").doesNotContain("PRIVATE").doesNotContain("password");
    }

    @Test
    @DisplayName("extracted: yalnız CSR PEM → CSR kartı (csr.cn) + CSR_NOT_CERTIFICATE, girdi yok")
    void analyze_extracted_csrCard() throws Exception {
        String csrPem = TestCerts.pemBlock("CERTIFICATE REQUEST",
                TestCerts.csr("csr.example.test", List.of("csr.example.test"), TestCerts.rsa()));
        mvc.perform(multipart("/api/manual-certs/analyze").file(extractedPart(extracted("PEM", 0, List.of(), List.of(csrPem))))
                        .session(adminX()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries", hasSize(0)))
                .andExpect(jsonPath("$.data.csr.cn").value("csr.example.test"))
                .andExpect(jsonPath("$.data.warnings[0].code").value("CSR_NOT_CERTIFICATE"));
    }

    @Test
    @DisplayName("extracted: geçersiz gövde (bozuk Base64 / bilinmeyen alan 'password' / biçim) → 400 EXTRACTED_INVALID + errors.file; hiçbir şey yazılmaz")
    void extracted_invalid_rejected() throws Exception {
        Map<String, Object> bad = extracted("PEM", 0, List.of(Map.of("alias", "x", "key_entry", false, "certs", List.of("!!!"))), List.of());
        mvc.perform(multipart("/api/manual-certs/analyze").file(extractedPart(bad)).session(adminX()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXTRACTED_INVALID"))
                .andExpect(jsonPath("$.errors.file").exists());
        Map<String, Object> withPassword = extracted("PKCS12", 0, List.of(entry(null, false, chain.leaf())), List.of());
        withPassword.put("password", "GIZLI-PAROLA-777");
        mvc.perform(multipart("/api/manual-certs").file(extractedPart(withPassword))
                        .param("ref", leafFp).param("domain", "api-takip").param("inventory", INVENTORY_JSON).session(adminX()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXTRACTED_INVALID"))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("GIZLI-PAROLA-777"))));
        mvc.perform(multipart("/api/manual-certs/analyze").file(extractedPart(extracted("BKS", 0, List.of(), List.of()))).session(adminX()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXTRACTED_INVALID"));
        // extracted + ham dosya birlikte → 400 (belirsiz istek)
        mvc.perform(multipart("/api/manual-certs/analyze").file(extractedPart(extracted("PEM", 0, List.of(entry(null, false, chain.leaf())), List.of())))
                        .file(pemFile(pem(chain.leaf()))).session(adminX()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.file").exists());
        verify(adminController, never()).createInventoryRecord(any(), any());
        verify(versionRepo, never()).save(any());
    }

    @Test
    @DisplayName("extracted ile yenileme: yeni sürüm v2 (biçim istemcinin), denetim CERT_MANUAL_RENEW")
    void renew_extracted() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 1)));
        when(versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(5L))
                .thenReturn(Optional.of(currentVersion(5L, 1, "BASKA", "2026-11-01T00:00:00")));
        when(versionRepo.findMaxVersion(5L)).thenReturn(1);
        Map<String, Object> body = extracted("PKCS12", 1, List.of(entry("sunucu", true, chain.leaf(), chain.inter())), List.of());
        mvc.perform(multipart("/api/manual-certs/5/versions").file(extractedPart(body)).param("ref", leafFp).session(adminX()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        ArgumentCaptor<ManualCertificateVersion> created = ArgumentCaptor.forClass(ManualCertificateVersion.class);
        verify(versionRepo).save(created.capture());
        assertThat(created.getValue().getFileFormat()).isEqualTo("PKCS12");
        verify(auditService).recordAction(eq("CERT_MANUAL_RENEW"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("CERTIFICATE"), eq("api-takip"), anyString());
    }

    @Test
    @DisplayName("ham yol: PFX, JKS, JCEKS, özel anahtarlı PEM, anahtarlı ZIP → 400 PRIVATE_KEY_NOT_ACCEPTED (errors.file); hiçbir şey yazılmaz, parola işe yaramaz")
    void rawPath_rejectsPrivateMaterial() throws Exception {
        char[] pw = "p".toCharArray();
        byte[] pfx = TestCerts.pkcs12("a", chain.leafKey().getPrivate(), pw, chain.leaf());
        byte[] jks = TestCerts.keystore("JKS", "a", chain.leafKey().getPrivate(), pw, Map.of("kok", chain.root()), chain.leaf());
        byte[] trustJceks = TestCerts.keystore("JCEKS", "a", null, pw, Map.of("kok", chain.root()));
        byte[] withKey = TestCerts.utf8(TestCerts.privateKeyPem(TestCerts.rsa()) + pem(chain.leaf()));
        byte[] zip = TestCerts.zip(Map.of("leaf.pem", TestCerts.utf8(pem(chain.leaf())), "leaf.key",
                TestCerts.utf8(TestCerts.privateKeyPem(TestCerts.rsa()))));
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        files.put("a.pfx", pfx);
        files.put("a.jks", jks);
        files.put("trust.jceks", trustJceks);
        files.put("with-key.pem", withKey);
        files.put("bundle.zip", zip);
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            mvc.perform(multipart("/api/manual-certs/analyze").file(new MockMultipartFile("file", f.getKey(), "application/octet-stream", f.getValue()))
                            .param("password", "p").session(adminX()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PRIVATE_KEY_NOT_ACCEPTED"))
                    .andExpect(jsonPath("$.errors.file", containsString("tarayıcınızda")));
        }
        mvc.perform(multipart("/api/manual-certs").file(new MockMultipartFile("file", "a.pfx", "application/x-pkcs12", pfx))
                        .param("password", "p").param("ref", leafFp).param("domain", "api-takip").param("inventory", INVENTORY_JSON)
                        .header("X-Lang", "en").session(adminX()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRIVATE_KEY_NOT_ACCEPTED"))
                .andExpect(jsonPath("$.error", containsString("never leave")));
        mvc.perform(multipart("/api/manual-certs/analyze").param("text", new String(withKey, java.nio.charset.StandardCharsets.UTF_8)).session(adminX()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRIVATE_KEY_NOT_ACCEPTED"));
        verify(adminController, never()).createInventoryRecord(any(), any());
        verify(versionRepo, never()).save(any());
    }

    @Test
    @DisplayName("ham yol: anahtarsız içerik (PEM sertifika, DER, P7B, sertifika ZIP'i) eskisi gibi çalışır")
    void rawPath_certificateOnlyStillWorks() throws Exception {
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        files.put("zincir.pem", TestCerts.utf8(pem(chain.leaf(), chain.inter())));
        files.put("yaprak.der", TestCerts.der(chain.leaf()));
        files.put("zincir.p7b", TestCerts.pkcs7(chain.leaf(), chain.inter(), chain.root()));
        files.put("paket.zip", TestCerts.zip(Map.of("leaf.pem", TestCerts.utf8(pem(chain.leaf())), "chain.p7b", TestCerts.pkcs7(chain.inter()))));
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            mvc.perform(multipart("/api/manual-certs/analyze").file(new MockMultipartFile("file", f.getKey(), "application/octet-stream", f.getValue()))
                            .session(adminX()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries[0].ref").value(leafFp));
        }
    }

    @Test
    @DisplayName("parola hiçbir loga / denetime / yanıta girmez (ham yolda gönderilse bile; extracted gövdesinde gelse bile)")
    void password_neverLoggedOrAudited() throws Exception {
        String secret = "GIZLI-PAROLA-a1b2c3";
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Logger app = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.sitemonitor");
        ch.qos.logback.classic.Level prev = app.getLevel();
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        root.addAppender(appender);
        app.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            byte[] pfx = TestCerts.pkcs12("a", chain.leafKey().getPrivate(), secret.toCharArray(), chain.leaf());
            mvc.perform(multipart("/api/manual-certs/analyze").file(new MockMultipartFile("file", "a.pfx", "application/x-pkcs12", pfx))
                            .param("password", secret).session(adminX()))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString(secret))));
            mvc.perform(multipart("/api/manual-certs").file(pemFile(pem(chain.leaf())))
                            .param("password", secret).param("ref", leafFp).param("domain", "api-takip").param("inventory", INVENTORY_JSON)
                            .session(adminX()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString(secret))));
            Map<String, Object> withPw = extracted("PKCS12", 0, List.of(entry(null, false, chain.leaf())), List.of());
            withPw.put("password", secret);
            mvc.perform(multipart("/api/manual-certs/analyze").file(extractedPart(withPw)).session(adminX()))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString(secret))));
        } finally {
            root.detachAppender(appender);
            app.setLevel(prev);
        }
        List<String> leaks = new ArrayList<>();
        for (ch.qos.logback.classic.spi.ILoggingEvent e : appender.list) {
            String text = e.getFormattedMessage() + (e.getThrowableProxy() != null ? e.getThrowableProxy().getMessage() : "");
            if (text.contains(secret)) leaks.add(e.getLoggerName() + ": " + text);
        }
        assertThat(leaks).as("parola loga sızdı").isEmpty();
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService, org.mockito.Mockito.atLeastOnce()).recordAction(anyString(), any(HttpSession.class),
                any(HttpServletRequest.class), anyString(), anyString(), detail.capture());
        assertThat(detail.getAllValues()).noneMatch(d -> d != null && d.contains(secret));
    }

    @Test
    @DisplayName("oluşturma: takip adı envanterde CANLI bir kayıtta var (harf duyarsız) → 409 KEY_EXISTS")
    void create_keyExists() throws Exception {
        when(inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc("api-takip")).thenReturn(Optional.of(manualRow(9, "API-takip", 3)));
        mvc.perform(multipart("/api/manual-certs").file(pemFile(pem(chain.leaf())))
                        .param("ref", leafFp).param("domain", "api-takip").param("inventory", INVENTORY_JSON)
                        .session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("KEY_EXISTS"))
                .andExpect(jsonPath("$.domain").value("api-takip"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("silinmiş"))));
        verify(adminController, never()).createInventoryRecord(any(), any());
    }

    @Test
    @DisplayName("oluşturma: SİLİNEN kaydın takip adı engel DEĞİL (2026-10-07, silme kalıcı) — kayıt açılır")
    void create_keyOfDeletedRecord_allowed() throws Exception {
        CertificateInventory gone = manualRow(9, "api-takip", 3);
        gone.setDeletedAt("2026-09-01T00:00:00");   // eski sürümden kalmış çöp satırı (createInventoryRecord önce kalıcı siler)
        when(inventoryRepo.findFirstByDomainIgnoreCaseOrderByIdAsc("api-takip")).thenReturn(Optional.of(gone));
        mvc.perform(multipart("/api/manual-certs").file(pemFile(pem(chain.leaf())))
                        .param("ref", leafFp).param("domain", "api-takip").param("inventory", INVENTORY_JSON)
                        .session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").doesNotExist());
        verify(adminController).createInventoryRecord(any(), any());
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

    /** Bağımsız iki zincir başı (2026-10-07: toplu yalnız bunlar arasında) — ilgisiz ikinci bir kök. */
    private static java.security.cert.X509Certificate otherRoot;

    private static java.security.cert.X509Certificate otherRoot() {
        if (otherRoot == null) otherRoot = TestCerts.root("Example Other Root CA", TestCerts.freshRsa(), days(-10), days(900));
        return otherRoot;
    }

    @Test
    @DisplayName("toplu: bir zincirin ara / kök üyesi ayrı kalem olamaz → 400 items[i].ref; kayıt açılmaz")
    void batch_foldedChainMember_rejected() throws Exception {
        String rootFp = ManualCertificateAnalyzer.fingerprint(chain.root());
        String interFp = ManualCertificateAnalyzer.fingerprint(chain.inter());
        mvc.perform(multipart("/api/manual-certs/batch").file(pemFile(pem(chain.inter(), chain.root())))
                        .param("items", "[{\"ref\":\"" + interFp + "\",\"domain\":\"ara-ca\"},{\"ref\":\"" + rootFp + "\",\"domain\":\"kok-ca\"}]")
                        .param("inventory", INVENTORY_JSON).session(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['items[0].ref']").doesNotExist())
                .andExpect(jsonPath("$.errors['items[1].ref']", containsString("Example Test Issuing CA")));
        verify(adminController, never()).createInventoryRecord(any(), any());
    }

    @Test
    @DisplayName("toplu: hep ya da hiç — ikinci kayıt düşerse 400, denetim/geçmiş/değerlendirme HİÇ yok")
    void batch_allOrNothing() throws Exception {
        String rootFp = ManualCertificateAnalyzer.fingerprint(chain.root());
        String interFp = ManualCertificateAnalyzer.fingerprint(otherRoot());
        org.mockito.Mockito.doAnswer(a -> {
            CertificateInventory i = a.getArgument(0);
            if ("ara-ca".equals(i.getDomain())) throw new IllegalArgumentException("Seçilen takım bulunamadı");
            i.setId(11L);
            return i;
        }).when(adminController).createInventoryRecord(any(), any());
        mvc.perform(multipart("/api/manual-certs/batch").file(pemFile(pem(chain.root(), otherRoot())))
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
        String interFp = ManualCertificateAnalyzer.fingerprint(otherRoot());
        mvc.perform(multipart("/api/manual-certs/batch").file(pemFile(pem(otherRoot(), chain.root())))
                        .param("items", "[{\"ref\":\"" + rootFp + "\",\"domain\":\"ayni\"},{\"ref\":\"" + interFp + "\",\"domain\":\"AYNI\"}]")
                        .param("inventory", INVENTORY_JSON).session(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['items[1].domain']").exists());

        mvc.perform(multipart("/api/manual-certs/batch").file(pemFile(pem(otherRoot(), chain.root())))
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

    // ── "Yine de yükle" (allow_same, 2026-10-07) ────────────────────────────

    @Test
    @DisplayName("yenileme allow_same: varsayılan 409 SAME_CERTIFICATE; allow_same → v+1 (aynı sertifika), önceki düşer, denetim same_certificate, değerlendirme")
    void renew_allowSame_savesNewVersion() throws Exception {
        CertificateInventory row = manualRow(5, "api-takip", 1);
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(row));
        String leafNotAfter = com.sitemonitor.service.CertificateFacts.iso(chain.leaf().getNotAfter().toInstant());
        ManualCertificateVersion cur = currentVersion(5L, 2, leafFp, leafNotAfter);   // bitiş AYNI → OLDER sorulmamalı
        when(versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(5L)).thenReturn(Optional.of(cur));
        when(versionRepo.findMaxVersion(5L)).thenReturn(2);

        // Bayrak yoksa davranış aynı: 409, hiçbir şey yazılmaz
        mvc.perform(multipart("/api/manual-certs/5/versions").file(pemFile(pem(chain.leaf(), chain.inter())))
                        .param("ref", leafFp).session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAME_CERTIFICATE"));
        verify(versionRepo, never()).save(any());

        MockMultipartFile der = new MockMultipartFile("file", "sunucu-yeni.der", "application/pkix-cert", TestCerts.der(chain.leaf()));
        mvc.perform(multipart("/api/manual-certs/5/versions").file(der)
                        .param("ref", leafFp).param("allow_same", "true").param("note", "DER biçimi").session(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.previous_version").value(2))
                .andExpect(jsonPath("$.data.same_certificate").value(true));

        InOrder order = inOrder(versionRepo);
        ArgumentCaptor<ManualCertificateVersion> flushed = ArgumentCaptor.forClass(ManualCertificateVersion.class);
        order.verify(versionRepo).saveAndFlush(flushed.capture());
        ArgumentCaptor<ManualCertificateVersion> created = ArgumentCaptor.forClass(ManualCertificateVersion.class);
        order.verify(versionRepo).save(created.capture());
        assertThat(flushed.getValue().getCurrent()).isFalse();
        assertThat(flushed.getValue().getSupersededAt()).isNotNull();
        assertThat(flushed.getValue().getSupersededBy()).isEqualTo("testuser");
        assertThat(created.getValue().getVersion()).isEqualTo(3);
        assertThat(created.getValue().getCurrent()).isTrue();
        assertThat(created.getValue().getFingerprint()).isEqualTo(leafFp);
        assertThat(created.getValue().getFileName()).isEqualTo("sunucu-yeni.der");
        assertThat(created.getValue().getNote()).isEqualTo("DER biçimi");
        verify(versionRepo, never()).delete(any());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("CERT_MANUAL_RENEW"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("CERTIFICATE"), eq("api-takip"), detail.capture());
        assertThat(detail.getValue()).contains("\"same_certificate\":true").contains(leafFp);
        verify(adminController).recordInventoryUpdateHistory(any(), any(), contains("Aynı sertifika"), any());
        verify(evaluation).evaluateNow(any(), eq("manual"));
    }

    @Test
    @DisplayName("yenileme allow_same: FARKLI ve daha eski bitişli sertifikada OLDER_THAN_CURRENT'i ATLATMAZ (confirm şart)")
    void renew_allowSame_doesNotBypassOlder() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 1)));
        when(versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(5L))
                .thenReturn(Optional.of(currentVersion(5L, 1, "BASKA", "2099-01-01T00:00:00")));
        mvc.perform(multipart("/api/manual-certs/5/versions").file(pemFile(pem(chain.leaf())))
                        .param("ref", leafFp).param("allow_same", "true").session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OLDER_THAN_CURRENT"));
        verify(versionRepo, never()).save(any());
        verify(versionRepo, never()).saveAndFlush(any());
    }

    // ── Eski sürümü kalıcı silme (2026-10-07) ────────────────────────────────

    private static ManualCertificateVersion oldVersion(long id, long invId, int v, String fp) {
        ManualCertificateVersion mv = currentVersion(invId, v, fp, "2026-01-01T00:00:00");
        mv.setId(id);
        mv.setCurrent(false);
        mv.setSupersededAt("2026-02-01T00:00:00");
        mv.setSupersededBy("uye1");
        return mv;
    }

    /** Görür (görüş kapsamı) ama yazamaz (üye değil, yönetmiyor). */
    private static MockHttpSession viewer(long team) {
        MockHttpSession s = user(team);
        s.setAttribute("memberTeamIds", new ArrayList<Long>());
        return s;
    }

    @Test
    @DisplayName("sürüm sil: eski sürüm kalıcı silinir (koşullu), kalan sayı döner; denetim + geçmiş; değerlendirme geçmişine dokunulmaz")
    void deleteVersion_success() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 1)));
        when(versionRepo.findById(101L)).thenReturn(Optional.of(oldVersion(101L, 5L, 1, "FP-ESKI")));
        when(versionRepo.deleteByIdAndInventoryIdAndCurrentFalse(101L, 5L)).thenReturn(1L);
        when(versionRepo.countByInventoryIds(anyCollection())).thenReturn(List.<Object[]>of(new Object[]{5L, 2L}));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/manual-certs/5/versions/101")
                        .session(user(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted_version").value(1))
                .andExpect(jsonPath("$.data.versions_count").value(2));
        verify(versionRepo).deleteByIdAndInventoryIdAndCurrentFalse(101L, 5L);
        verify(versionRepo, never()).deleteByInventoryId(any());
        verify(versionRepo, never()).delete(any());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("CERT_MANUAL_VERSION_DELETE"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("CERTIFICATE"), eq("api-takip"), detail.capture());
        assertThat(detail.getValue()).contains("FP-ESKI").contains("\"version\":1").contains("api-takip");
        verify(adminController).recordInventoryUpdateHistory(any(), any(), contains("v1"), any());
        verify(certService, never()).saveResult(any());
        verify(evaluation, never()).evaluateNow(any(), anyString());
    }

    @Test
    @DisplayName("sürüm sil: güncel sürüm 409 CURRENT_VERSION; başka kaydın sürümü / manuel olmayan kayıt 404; silme çağrılmaz")
    void deleteVersion_currentAndForeign() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 1)));
        ManualCertificateVersion cur = currentVersion(5L, 3, "FP-GUNCEL", "2027-01-01T00:00:00");
        cur.setId(103L);
        when(versionRepo.findById(103L)).thenReturn(Optional.of(cur));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/manual-certs/5/versions/103")
                        .session(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CURRENT_VERSION"))
                .andExpect(jsonPath("$.error").isNotEmpty());

        when(versionRepo.findById(201L)).thenReturn(Optional.of(oldVersion(201L, 6L, 1, "FP-BASKA")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/manual-certs/5/versions/201")
                        .session(admin()))
                .andExpect(status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/manual-certs/5/versions/999")
                        .session(admin()))
                .andExpect(status().isNotFound());

        CertificateInventory network = manualRow(7, "ag.example.test", 1);
        network.setCertSource(null);
        when(inventoryRepo.findById(7L)).thenReturn(Optional.of(network));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/manual-certs/7/versions/201")
                        .session(admin()))
                .andExpect(status().isNotFound());
        verify(versionRepo, never()).deleteByIdAndInventoryIdAndCurrentFalse(any(), any());
        verify(auditService, never()).recordAction(eq("CERT_MANUAL_VERSION_DELETE"), any(HttpSession.class),
                any(HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sürüm sil: izin yok 403; görüp yazamayan 403; kapsam dışı takım 404")
    void deleteVersion_gates() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 1)));
        when(versionRepo.findById(101L)).thenReturn(Optional.of(oldVersion(101L, 5L, 1, "FP-ESKI")));
        var del = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/manual-certs/5/versions/101");
        mvc.perform(del.session(viewer(1))).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/manual-certs/5/versions/101")
                .session(user(2))).andExpect(status().isNotFound());
        org.mockito.Mockito.doThrow(new SecurityException("yetki yok"))
                .when(permissionService).require(any(HttpSession.class), eq("inventory.crud"), eq("edit"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/manual-certs/5/versions/101")
                .session(user(1))).andExpect(status().isForbidden());
        verify(versionRepo, never()).deleteByIdAndInventoryIdAndCurrentFalse(any(), any());
    }

    @Test
    @DisplayName("detay: ortadaki sürüm silindiyse fark kalan bir sonraki ESKİ sürümle; aynı parmak izi → same_as_previous")
    void detail_diffSkipsDeletedVersion() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 2)));
        ManualCertificateVersion v3 = currentVersion(5L, 3, "FP-A", "2028-01-01T00:00:00");
        v3.setPublicKeySha256("ANAHTAR-1");
        v3.setSan("[\"a.example.test\",\"c.example.test\"]");
        ManualCertificateVersion v1 = oldVersion(101L, 5L, 1, "FP-A");   // v2 silinmiş; v3 = v1 ile aynı sertifika
        v1.setPublicKeySha256("ANAHTAR-1");
        v1.setSan("[\"a.example.test\",\"b.example.test\"]");
        when(versionRepo.findByInventoryIdOrderByVersionDesc(5L)).thenReturn(List.of(v3, v1));
        mvc.perform(get("/api/manual-certs/5").session(user(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions", hasSize(2)))
                .andExpect(jsonPath("$.data.versions[0].version").value(3))
                .andExpect(jsonPath("$.data.versions[0].key_changed").value(false))
                .andExpect(jsonPath("$.data.versions[0].san_added[0]").value("c.example.test"))
                .andExpect(jsonPath("$.data.versions[0].san_removed[0]").value("b.example.test"))
                .andExpect(jsonPath("$.data.versions[0].same_as_previous").value(true))
                .andExpect(jsonPath("$.data.versions[1].same_as_previous").value(false));
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

    // ── Sürüm hiyerarşisi (2026-10-07: tarayıcı gibi kök → ara → yaprak) ─────

    private ManualCertificateVersion chainVersion(long id, long invId, int v, boolean current, String chainPem) {
        ManualCertificateVersion mv = currentVersion(invId, v, "FP" + v, "2027-01-01T00:00:00");
        mv.setId(id);
        mv.setCurrent(current);
        mv.setChainPem(chainPem);
        return mv;
    }

    @Test
    @DisplayName("hiyerarşi: saklanan zincir (baş ilk) → kök ilk, ara, yaprak son; düğüm başına tek PEM; hiçbir şey yazılmaz")
    void versionChain_rootFirst_readOnly() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 2)));
        when(versionRepo.findById(103L)).thenReturn(Optional.of(chainVersion(103L, 5L, 3, true,
                pem(chain.leaf(), chain.inter(), chain.root()))));
        String body = mvc.perform(get("/api/manual-certs/5/versions/103/chain").session(user(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.inventory_id").value(5))
                .andExpect(jsonPath("$.data.version_id").value(103))
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.current").value(true))
                .andExpect(jsonPath("$.data.certificate_count").value(3))
                .andExpect(jsonPath("$.data.issuer_missing").value(false))
                .andExpect(jsonPath("$.data.nodes", hasSize(3)))
                .andExpect(jsonPath("$.data.nodes[0].role").value("root"))
                .andExpect(jsonPath("$.data.nodes[0].depth").value(0))
                .andExpect(jsonPath("$.data.nodes[0].self_signed").value(true))
                .andExpect(jsonPath("$.data.nodes[1].role").value("intermediate"))
                .andExpect(jsonPath("$.data.nodes[2].role").value("leaf"))
                .andExpect(jsonPath("$.data.nodes[2].head").value(true))
                .andExpect(jsonPath("$.data.nodes[2].subject.cn").value("api.example.test"))
                .andExpect(jsonPath("$.data.nodes[2].issuer.cn").value("Example Test Issuing CA"))
                .andExpect(jsonPath("$.data.nodes[2].san[0]").value("api.example.test"))
                .andExpect(jsonPath("$.data.nodes[2].sha256_fingerprint").value(leafFp))
                .andExpect(jsonPath("$.data.nodes[2].ext_key_usage[0]").value("TLS Web Server"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        List<String> pems = com.jayway.jsonpath.JsonPath.read(body, "$.data.nodes[*].pem");
        List<java.security.cert.X509Certificate> expected = List.of(chain.root(), chain.inter(), chain.leaf());
        assertThat(pems).hasSize(3);
        for (int i = 0; i < pems.size(); i++) {
            assertThat(pems.get(i)).doesNotContain("PRIVATE KEY");
            assertThat(com.sitemonitor.service.manualcert.CertificateFileParser.readPemChain(pems.get(i)))
                    .containsExactly(expected.get(i));
        }
        verify(versionRepo, never()).save(any());
        verify(versionRepo, never()).saveAndFlush(any());
        verify(versionRepo, never()).delete(any());
        verify(versionRepo, never()).deleteByIdAndInventoryIdAndCurrentFalse(any(), any());
        verify(inventoryRepo, never()).save(any());
        verify(certService, never()).saveResult(any());
        verify(certService, never()).evictAllCaches();
        org.mockito.Mockito.verifyNoInteractions(evaluation, auditService, adminController, latestRepo);
    }

    @Test
    @DisplayName("hiyerarşi: yalnız yaprak yüklenmiş (eski sürüm) → tek yaprak düğümü + issuer_missing (kök dosyada yok)")
    void versionChain_leafOnly_issuerMissing() throws Exception {
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 2)));
        when(versionRepo.findById(101L)).thenReturn(Optional.of(chainVersion(101L, 5L, 1, false, pem(chain.leaf()))));
        mvc.perform(get("/api/manual-certs/5/versions/101/chain").session(user(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.current").value(false))
                .andExpect(jsonPath("$.data.issuer_missing").value(true))
                .andExpect(jsonPath("$.data.missing_issuer_dn", containsString("Example Test Issuing CA")))
                .andExpect(jsonPath("$.data.nodes", hasSize(1)))
                .andExpect(jsonPath("$.data.nodes[0].role").value("leaf"))
                .andExpect(jsonPath("$.data.nodes[0].issuer_missing").value(true));
    }

    @Test
    @DisplayName("hiyerarşi kapıları: oturumsuz 401; izin yok 403; kapsam dışı takım / başka kaydın sürümü / bilinmeyen sürüm / ağ kaydı 404; okunamayan zincir 422")
    void versionChain_gates() throws Exception {
        String full = pem(chain.leaf(), chain.inter(), chain.root());
        when(inventoryRepo.findById(5L)).thenReturn(Optional.of(manualRow(5, "api-takip", 2)));
        when(versionRepo.findById(103L)).thenReturn(Optional.of(chainVersion(103L, 5L, 3, true, full)));
        when(versionRepo.findById(201L)).thenReturn(Optional.of(chainVersion(201L, 6L, 1, true, full)));
        when(versionRepo.findById(300L)).thenReturn(Optional.of(chainVersion(300L, 5L, 2, false, null)));
        when(versionRepo.findById(301L)).thenReturn(Optional.of(chainVersion(301L, 5L, 4, false,
                "-----BEGIN CERTIFICATE-----\nYm96dWs=\n-----END CERTIFICATE-----\n")));
        CertificateInventory network = manualRow(7, "ag.example.test", 2);
        network.setCertSource(null);
        when(inventoryRepo.findById(7L)).thenReturn(Optional.of(network));

        mvc.perform(get("/api/manual-certs/5/versions/103/chain")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/manual-certs/5/versions/103/chain").session(user(3))).andExpect(status().isNotFound());
        mvc.perform(get("/api/manual-certs/5/versions/201/chain").session(user(2))).andExpect(status().isNotFound());
        mvc.perform(get("/api/manual-certs/5/versions/999/chain").session(user(2))).andExpect(status().isNotFound());
        mvc.perform(get("/api/manual-certs/7/versions/103/chain").session(admin())).andExpect(status().isNotFound());
        mvc.perform(get("/api/manual-certs/5/versions/300/chain").session(user(2))).andExpect(status().isNotFound());
        mvc.perform(get("/api/manual-certs/5/versions/301/chain").session(user(2)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CHAIN_UNREADABLE"));
        // salt okur (yazamayan) kullanıcı da GÖRÜR — okuma kapısı
        mvc.perform(get("/api/manual-certs/5/versions/103/chain").session(viewer(2))).andExpect(status().isOk());

        org.mockito.Mockito.doThrow(new SecurityException("yetki yok"))
                .when(permissionService).require(any(HttpSession.class), eq("inventory.list"), eq("view"));
        mvc.perform(get("/api/manual-certs/5/versions/103/chain").session(user(2))).andExpect(status().isForbidden());
        verify(versionRepo, never()).save(any());
    }

    private static String contains(String s) {
        return org.mockito.ArgumentMatchers.contains(s);
    }
}
