package com.sitemonitor.service.manualcert;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.ManualCertificateVersion;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.ManualCertificateVersionRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CertificateCheckerService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.ChainValidationService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.TrustEvaluator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.sitemonitor.service.manualcert.TestCerts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çevrim-dışı değerlendirme (2026-10-06): ağ kontrolüyle AYNI sonuç haritası, yalnız-yaprak yüklemede CHAIN_BROKEN yok,
 * iptal sorgusu parmak izi başına önbellekli; elle tetik yalnız kapanış, zamanlanmış adım normal alarm hattı.
 */
class ManualCertificateEvaluationServiceTest {

    private static Chain chain;

    private CertificateService certService;
    private EscalationService escalation;
    private ChainValidationService chainValidator;
    private TrustEvaluator trust;
    private ManualCertificateVersionRepository versionRepo;
    private CertificateInventoryRepository inventoryRepo;
    private AppSettingsService settings;
    private ManualCertificateEvaluationService svc;

    @BeforeAll
    static void certs() {
        chain = TestCerts.chain("api.example.test", days(200));
    }

    @BeforeEach
    void setUp() {
        certService = mock(CertificateService.class);
        escalation = mock(EscalationService.class);
        chainValidator = spy(new ChainValidationService());
        trust = mock(TrustEvaluator.class);
        when(trust.evaluate(any())).thenReturn(new TrustEvaluator.TrustResult(false, "untrusted"));
        versionRepo = mock(ManualCertificateVersionRepository.class);
        inventoryRepo = mock(CertificateInventoryRepository.class);
        settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        svc = new ManualCertificateEvaluationService(certService, escalation, chainValidator, trust, versionRepo,
                inventoryRepo, settings);
    }

    private static CertificateInventory manual(long id, String key) {
        CertificateInventory inv = new CertificateInventory();
        inv.setId(id);
        inv.setDomain(key);
        inv.setActive(true);
        inv.setCertSource(CertificateInventory.SOURCE_MANUAL);
        return inv;
    }

    private static ManualCertificateVersion version(long invId, int v, X509Certificate... chainCerts) {
        ManualCertificateVersion mv = new ManualCertificateVersion();
        mv.setId(100L + v);
        mv.setInventoryId(invId);
        mv.setVersion(v);
        mv.setCurrent(true);
        mv.setChainPem(pem(chainCerts));
        mv.setFingerprint(ManualCertificateAnalyzer.fingerprint(chainCerts[0]));
        return mv;
    }

    @Test
    @DisplayName("sonuç haritası ağ kontrolünün anahtarlarını TAŞIR; ortak yaprak alanları birebir aynı")
    void resultShapeParityWithNetworkChecker() {
        CertificateCheckerService checker = new CertificateCheckerService(chainValidator, null, null, null, null, null);
        ReflectionTestUtils.setField(checker, "warningDays", 30);
        @SuppressWarnings("unchecked")
        Map<String, Object> leafFields = (Map<String, Object>) ReflectionTestUtils.invokeMethod(checker, "parseLeafCert",
                chain.leaf(), "api-takip");
        Map<String, Object> r = svc.buildResult(manual(1, "api-takip"), version(1, 3, chain.leaf(), chain.inter(), chain.root()), false);

        Set<String> networkKeys = new HashSet<>(leafFields.keySet());
        networkKeys.addAll(List.of("source_ip", "source_port", "peer_ip", "peer_port", "tls_version", "cipher_suite",
                "alpn", "chain_status", "intermediate_expiry", "intermediate_days_remaining", "chain", "fingerprint",
                "revocation_status", "trust_status", "deployment_status", "resolved_ip", "hsts", "http_status", "via",
                "tls_mode_used", "elapsed_ms"));
        assertThat(r).containsKeys(networkKeys.toArray(String[]::new));
        for (String k : leafFields.keySet()) {
            if ("checked_at".equals(k)) continue;
            assertThat(r.get(k)).as(k).isEqualTo(leafFields.get(k));
        }
        assertThat(r.get("via")).isEqualTo("upload");
        assertThat(r.get("deployment_status")).isEqualTo("UNKNOWN");
        assertThat(r.get("manual")).isEqualTo(true);
        assertThat(r.get("manual_version")).isEqualTo(3);
        assertThat(r.get("tls_version")).isNull();
        assertThat(r.get("fingerprint")).isEqualTo(ManualCertificateAnalyzer.fingerprint(chain.leaf()));
        assertThat(r.get("chain_status")).isEqualTo("VALID");
        assertThat(r.get("trust_status")).isEqualTo("UNTRUSTED");   // tam zincir, güvenilmeyen kök
    }

    @Test
    @DisplayName("yalnız yaprak: chain_status UNKNOWN (CHAIN_BROKEN üretmez), güven UNKNOWN, iptal UNKNOWN (sorgu yok)")
    void leafOnly_noChainBroken() {
        Map<String, Object> r = svc.buildResult(manual(1, "api-takip"), version(1, 1, chain.leaf()), true);
        assertThat(r.get("chain_status")).isEqualTo("UNKNOWN");
        assertThat(r.get("trust_status")).isEqualTo("UNKNOWN");
        assertThat(r.get("revocation_status")).isEqualTo("UNKNOWN");
        verify(chainValidator, never()).checkRevocation(any());
    }

    @Test
    @DisplayName("yüklenen zincirde süresi dolmuş ara sertifika → BROKEN (ağ yoluyla aynı)")
    void expiredIntermediate_broken() {
        KeyPair ik = rsa();
        X509Certificate oldInter = intermediate("Old Issuing CA", ik, chain.root(), chain.rootKey().getPrivate(), days(-900), days(-2));
        X509Certificate lf = leaf("x.example.test", List.of("x.example.test"), rsa(), oldInter, ik.getPrivate(), days(-10), days(90));
        Map<String, Object> r = svc.buildResult(manual(1, "x"), version(1, 1, lf, oldInter, chain.root()), false);
        assertThat(r.get("chain_status")).isEqualTo("BROKEN");
    }

    @Test
    @DisplayName("iptal sorgusu parmak izi başına önbellekli (6 sa); hata → UNKNOWN; elle tetik CA'yı BEKLEMEZ")
    void revocationCached() {
        doReturn("REVOKED").when(chainValidator).checkRevocation(any());
        CertificateInventory inv = manual(1, "api-takip");
        ManualCertificateVersion v = version(1, 1, chain.leaf(), chain.inter());

        Map<String, Object> manualTrigger = svc.buildResult(inv, v, false);   // önbellek boş, ısıtma yürütücüsü yok
        assertThat(manualTrigger.get("revocation_status")).isEqualTo("UNKNOWN");
        verify(chainValidator, never()).checkRevocation(any());

        Map<String, Object> first = svc.buildResult(inv, v, true);
        Map<String, Object> second = svc.buildResult(inv, v, true);
        Map<String, Object> third = svc.buildResult(inv, v, false);
        assertThat(first.get("revocation_status")).isEqualTo("REVOKED");
        assertThat(first.get("chain_status")).isEqualTo("REVOKED");
        assertThat(second.get("revocation_status")).isEqualTo("REVOKED");
        assertThat(third.get("revocation_status")).isEqualTo("REVOKED");
        verify(chainValidator, times(1)).checkRevocation(any());

        ManualCertificateVersion other = version(2, 1, chain.inter(), chain.root());
        doThrow(new RuntimeException("ocsp down")).when(chainValidator).checkRevocation(any());
        assertThat(svc.buildResult(manual(2, "ara"), other, true).get("revocation_status")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("elle tetik: kaydeder + önbellek + YALNIZ kapanış uzlaştırması — processResults hiç çağrılmaz")
    void evaluateNow_recoveryOnly() {
        CertificateInventory inv = manual(5, "api-takip");
        when(versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(5L))
                .thenReturn(Optional.of(version(5, 2, chain.leaf(), chain.inter())));
        Map<String, Object> r = svc.evaluateNow(inv, "manual");
        assertThat(r).isNotNull();
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> saved = (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
        verify(certService).saveResult(saved.capture());
        assertThat(saved.getValue()).containsEntry("run_id", "manual").containsEntry("manual", true).containsEntry("domain", "api-takip");
        verify(certService).evictAllCaches();
        verify(escalation).resolveVerifiedStaleCertAlerts(anyList());
        verify(escalation, never()).processResults(anyList());
    }

    @Test
    @DisplayName("elle tetik: geçerli sürüm yoksa ya da kayıt manuel değilse hiçbir şey yazılmaz")
    void evaluateNow_noVersion() {
        when(versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(5L)).thenReturn(Optional.empty());
        assertThat(svc.evaluateNow(manual(5, "x"), "manual")).isNull();
        CertificateInventory net = manual(6, "net.example.test");
        net.setCertSource(null);
        assertThat(svc.evaluateNow(net, "manual")).isNull();
        verify(certService, never()).saveResult(any());
    }

    @Test
    @DisplayName("zamanlanmış adım: normal alarm hattı (processResults) — manuel işaretli sonuçlarla; sürümsüz satır atlanır")
    void evaluateScheduled_alertsEnabled() {
        CertificateInventory a = manual(1, "a-takip");
        CertificateInventory b = manual(2, "b-takip");   // sürümü yok → atlanır
        when(versionRepo.findByInventoryIdInAndCurrentTrue(anyCollection())).thenReturn(List.of(version(1, 1, chain.leaf(), chain.inter())));
        int n = svc.evaluateScheduled(List.of(a, b), "upload-run");
        assertThat(n).isEqualTo(1);
        verify(certService, times(1)).saveResult(any());
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<Map<String, Object>>> cap = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(escalation).processResults(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().get(0)).containsEntry("manual", true).containsEntry("domain", "a-takip")
                .containsEntry("run_id", "upload-run");
        verify(escalation, never()).resolveVerifiedStaleCertAlerts(anyList());
    }

    @Test
    @DisplayName("zamanlanmış adım: sertifika alarmları kapalıysa yalnız kapanış (ağ süpürmesiyle aynı ayar)")
    void evaluateScheduled_alertsDisabled() {
        when(settings.getBoolean(eq("site.monitor.expiry.alert-enabled"), anyBoolean())).thenReturn(false);
        when(versionRepo.findByInventoryIdInAndCurrentTrue(anyCollection())).thenReturn(List.of(version(1, 1, chain.leaf())));
        svc.evaluateScheduled(List.of(manual(1, "a")), "r");
        verify(escalation, never()).processResults(anyList());
        verify(escalation).resolveVerifiedStaleCertAlerts(anyList());
    }

    @Test
    @DisplayName("aktif manuel satırlar: yalnız kaynak MANUAL + silinmemiş")
    void activeManualRows() {
        CertificateInventory deleted = manual(3, "silinmis");
        deleted.setDeletedAt("2026-10-01T00:00:00");
        when(inventoryRepo.findByCertSourceAndActiveTrueOrderByDomainAsc("MANUAL")).thenReturn(List.of(manual(1, "a"), deleted));
        assertThat(svc.activeManualRows()).extracting(CertificateInventory::getDomain).containsExactly("a");
    }

    // ── Önizleme (2026-10-07) ────────────────────────────────────────────────

    @Test
    @DisplayName("previewCurrent: geçerli sürümden aynı sonuç haritası — kayıt / önbellek boşaltma / alarm hattı YOK")
    void previewCurrent_sideEffectFree() {
        when(versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(5L))
                .thenReturn(Optional.of(version(5, 4, chain.leaf(), chain.inter(), chain.root())));
        Map<String, Object> r = svc.previewCurrent(manual(5, "api-takip"));
        assertThat(r).containsEntry("domain", "api-takip").containsEntry("via", "upload").containsEntry("manual", true)
                .containsEntry("manual_version", 4).containsEntry("chain_status", "VALID")
                .containsEntry("manual_version_id", 104L);   // yalnız önizlemede — "Hiyerarşi" görünümü (2026-10-07)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ch = (List<Map<String, Object>>) r.get("chain");
        assertThat(ch).hasSize(3);
        assertThat(ch.get(0)).containsEntry("is_leaf", true);
        assertThat(ch.get(2)).containsEntry("is_root", true);
        verify(certService, never()).saveResult(any());
        verify(certService, never()).evictAllCaches();
        verify(escalation, never()).resolveVerifiedStaleCertAlerts(anyList());
        verify(escalation, never()).processResults(anyList());
        verify(chainValidator, never()).checkRevocation(any());

        when(versionRepo.findFirstByInventoryIdAndCurrentTrueOrderByVersionDesc(6L)).thenReturn(Optional.empty());
        assertThat(svc.previewCurrent(manual(6, "surumsuz"))).isNull();
        CertificateInventory net = manual(7, "net.example.test");
        net.setCertSource(null);
        assertThat(svc.previewCurrent(net)).isNull();
    }

    @Test
    @DisplayName("previewChain (analiz): ağa HİÇ çıkmaz — iptal yalnız önbellekten, ısıtma da yok; baş + zincir sırası korunur")
    void previewChain_noNetwork() {
        java.util.List<Runnable> warmed = new java.util.ArrayList<>();
        ReflectionTestUtils.setField(svc, "certCheckExecutor", (java.util.concurrent.Executor) warmed::add);
        Map<String, Object> r = svc.previewChain("api.example.test", chain.leaf(), List.of(chain.inter(), chain.root()));
        assertThat(r).containsEntry("domain", "api.example.test").containsEntry("via", "upload")
                .containsEntry("revocation_status", "UNKNOWN").doesNotContainKey("manual_version");
        assertThat(r.get("fingerprint")).isEqualTo(ManualCertificateAnalyzer.fingerprint(chain.leaf()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ch = (List<Map<String, Object>>) r.get("chain");
        assertThat(ch).extracting(m -> m.get("position")).containsExactly(0, 1, 2);
        assertThat(warmed).as("analiz önizlemesi iptal sorgusunu ısıtmaz").isEmpty();
        verify(chainValidator, never()).checkRevocation(any());

        // Karşılaştırma: kayıtlı satırın elle tetiği (CACHED_WARM) ısıtır
        svc.buildResult(manual(1, "api-takip"), version(1, 1, chain.leaf(), chain.inter()), false);
        assertThat(warmed).hasSize(1);

        // Tek sertifika (kök) da önizlenir; zincir 1 halka
        Map<String, Object> root = svc.previewChain("kok", chain.root(), List.of());
        assertThat((List<?>) root.get("chain")).hasSize(1);
        assertThat(svc.previewChain("x", null, List.of())).isNull();
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> map() { return new LinkedHashMap<>(); }

    @SuppressWarnings("unused")
    private static Certificate[] arr(X509Certificate... c) { return c; }
}
