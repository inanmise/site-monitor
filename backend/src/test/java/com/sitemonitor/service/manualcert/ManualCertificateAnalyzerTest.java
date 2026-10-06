package com.sitemonitor.service.manualcert;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.ManualCertificateVersion;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.ManualCertificateVersionRepository;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.manualcert.ManualCertificateAnalyzer.Analysis;
import com.sitemonitor.service.manualcert.ManualCertificateAnalyzer.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sitemonitor.service.manualcert.TestCerts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Manuel sertifika ANALİZİ (2026-10-06): zincir kurma, güven hükmü (yalnız zincir tamsa), uyarılar, önerilen takip adı
 * ve eşleşmeler — eşleşme sorguları TOPLU (girdi başına sorgu yok).
 */
class ManualCertificateAnalyzerTest {

    private static Chain chain;

    private TrustEvaluator trust;
    private ManualCertificateVersionRepository versionRepo;
    private CertificateInventoryRepository inventoryRepo;
    private LatestCheckRepository latestRepo;
    private ManualCertificateAnalyzer analyzer;

    @BeforeAll
    static void certs() {
        chain = TestCerts.chain("api.example.test", days(200));
    }

    @BeforeEach
    void setUp() {
        trust = mock(TrustEvaluator.class);
        when(trust.evaluate(any())).thenReturn(new TrustEvaluator.TrustResult(false, "untrusted"));
        versionRepo = mock(ManualCertificateVersionRepository.class);
        inventoryRepo = mock(CertificateInventoryRepository.class);
        latestRepo = mock(LatestCheckRepository.class);
        analyzer = new ManualCertificateAnalyzer(trust, versionRepo, inventoryRepo, latestRepo);
    }

    @AfterEach
    void tearDown() {
        analyzer.shutdown();
    }

    private Analysis analyze(String pemText) {
        return analyzer.analyze(utf8(pemText), "dosya.pem", null, false);
    }

    private static Entry entry(Analysis a, X509Certificate c) {
        return a.find(ManualCertificateAnalyzer.fingerprint(c));
    }

    private static List<String> codes(Entry e) {
        return e.warnings().stream().map(CertificateFileParser.Warning::code).toList();
    }

    private static List<String> fileCodes(Analysis a) {
        return a.warnings.stream().map(CertificateFileParser.Warning::code).toList();
    }

    @Test
    @DisplayName("zincir dosya sırasından bağımsız kurulur (ad + imza); kendinden imzalı köke ulaşan zincir TAM")
    void chain_builtOutOfOrder_complete() {
        Analysis a = analyze(pem(chain.root(), chain.leaf(), chain.inter()));
        Entry leaf = entry(a, chain.leaf());
        assertThat(leaf.chain).containsExactly(chain.inter(), chain.root());
        assertThat(leaf.trust.chainComplete()).isTrue();
        assertThat(a.defaultRef).isEqualTo(leaf.ref);
        assertThat(codes(leaf)).doesNotContain("CHAIN_INCOMPLETE");
        // Güvenilmeyen kök, TAM zincir, sunucu sertifikası → UNTRUSTED (ağ kontrolüyle aynı anlam).
        assertThat(leaf.trust.status()).isEqualTo("UNTRUSTED");
    }

    @Test
    @DisplayName("güven deposu zinciri doğrularsa TRUSTED")
    void trusted_whenEvaluatorTrusts() {
        when(trust.evaluate(any())).thenReturn(new TrustEvaluator.TrustResult(true, null));
        Analysis a = analyze(pem(chain.leaf(), chain.inter()));
        Entry leaf = entry(a, chain.leaf());
        assertThat(leaf.trust.status()).isEqualTo("TRUSTED");
        assertThat(leaf.trust.chainComplete()).isTrue();
    }

    @Test
    @DisplayName("yalnız yaprak: zincir eksik → güven UNKNOWN (asla UNTRUSTED) + CHAIN_INCOMPLETE {missing_issuer}")
    void leafOnly_unknownTrust() {
        Analysis a = analyze(pem(chain.leaf()));
        Entry leaf = entry(a, chain.leaf());
        assertThat(leaf.chain).isEmpty();
        assertThat(leaf.trust.chainComplete()).isFalse();
        assertThat(leaf.trust.status()).isEqualTo("UNKNOWN");
        CertificateFileParser.Warning w = leaf.warnings().stream().filter(x -> x.code().equals("CHAIN_INCOMPLETE")).findFirst().orElseThrow();
        assertThat(w.params().get("missing_issuer")).asString().contains("Example Test Issuing CA");
    }

    @Test
    @DisplayName("CA girdileri: CA_CERTIFICATE; kök SELF_SIGNED (info); CA güvenilmese de UNKNOWN")
    void caEntries() {
        Analysis a = analyze(pem(chain.inter(), chain.root()));
        Entry root = entry(a, chain.root());
        Entry inter = entry(a, chain.inter());
        assertThat(codes(root)).contains("CA_CERTIFICATE", "SELF_SIGNED");
        assertThat(root.warnings().stream().filter(w -> w.code().equals("SELF_SIGNED")).findFirst().orElseThrow().severity())
                .isEqualTo("info");
        assertThat(codes(inter)).contains("CA_CERTIFICATE").doesNotContain("SELF_SIGNED");
        assertThat(inter.trust.status()).isEqualTo("UNKNOWN");
        assertThat(a.defaultRef).isNull();   // birden çok CA, uç sertifika yok → kullanıcı seçer
    }

    @Test
    @DisplayName("süre uyarıları: EXPIRED {days}, NOT_YET_VALID {date}, EXPIRES_SOON {days}; durum etiketi")
    void expiryWarnings() {
        KeyPair kp = rsa();
        X509Certificate expired = leaf("old.example.test", List.of("old.example.test"), kp, chain.inter(), chain.interKey().getPrivate(),
                days(-400), Instant.now().minus(5, ChronoUnit.DAYS).minus(2, ChronoUnit.HOURS));
        X509Certificate future = leaf("future.example.test", List.of(), kp, chain.inter(), chain.interKey().getPrivate(),
                days(10), days(400));
        X509Certificate soon = leaf("soon.example.test", List.of(), kp, chain.inter(), chain.interKey().getPrivate(),
                days(-10), Instant.now().plus(10, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS));
        Analysis a = analyze(pem(expired, future, soon, chain.inter()));
        Entry e1 = entry(a, expired);
        assertThat(e1.status(30)).isEqualTo("expired");
        assertThat(e1.warnings().stream().filter(w -> w.code().equals("EXPIRED")).findFirst().orElseThrow().params())
                .containsEntry("days", 6);   // floorDiv: 5 gün 2 saat önce → -6 gün kaldı → 6 gün önce doldu
        Entry e2 = entry(a, future);
        assertThat(e2.status(30)).isEqualTo("not_yet_valid");
        assertThat(codes(e2)).contains("NOT_YET_VALID");
        Entry e3 = entry(a, soon);
        assertThat(e3.status(30)).isEqualTo("warning");
        assertThat(e3.warnings().stream().filter(w -> w.code().equals("EXPIRES_SOON")).findFirst().orElseThrow().params())
                .containsEntry("days", 10);
        assertThat(fileCodes(a)).contains("MULTIPLE_LEAVES");
    }

    @Test
    @DisplayName("süresi dolmuş ara sertifika → CHAIN_EXPIRED_INTERMEDIATE {subject, date}")
    void expiredIntermediate() {
        KeyPair ik = rsa();
        X509Certificate oldInter = intermediate("Old Issuing CA", ik, chain.root(), chain.rootKey().getPrivate(), days(-900), days(-3));
        X509Certificate lf = leaf("x.example.test", List.of("x.example.test"), rsa(), oldInter, ik.getPrivate(), days(-30), days(60));
        Analysis a = analyze(pem(lf, oldInter, chain.root()));
        Entry e = entry(a, lf);
        CertificateFileParser.Warning w = e.warnings().stream().filter(x -> x.code().equals("CHAIN_EXPIRED_INTERMEDIATE"))
                .findFirst().orElseThrow();
        assertThat(w.params()).containsEntry("subject", "Old Issuing CA");
        assertThat(w.params()).containsKey("date");
    }

    @Test
    @DisplayName("zayıflık: SHA1 imza → WEAK_SIGNATURE, RSA-1024 → WEAK_KEY; weak alanı")
    void weakAlgorithms() {
        X509Certificate sha1 = leaf("sha1.example.test", List.of(), rsa(), chain.inter(), chain.interKey().getPrivate(),
                days(-1), days(100), "SHA1withRSA");
        X509Certificate small = leaf("small.example.test", List.of(), rsa(1024), chain.inter(), chain.interKey().getPrivate(),
                days(-1), days(100));
        Analysis a = analyze(pem(sha1, small));
        assertThat(codes(entry(a, sha1))).contains("WEAK_SIGNATURE");
        CertificateFileParser.Warning wk = entry(a, small).warnings().stream().filter(w -> w.code().equals("WEAK_KEY")).findFirst().orElseThrow();
        assertThat(wk.params()).containsEntry("algorithm", "RSA").containsEntry("size", 1024);
        @SuppressWarnings("unchecked")
        Map<String, Object> weak = (Map<String, Object>) ((List<Map<String, Object>>) a.toJson().get("entries")).stream()
                .filter(m -> m.get("ref").equals(entry(a, small).ref)).findFirst().orElseThrow().get("weak");
        assertThat(weak).containsEntry("key_size", "WEAK").containsEntry("signature", "OK");
    }

    @Test
    @DisplayName("aynı sertifika iki kez → tek girdi + DUPLICATE_IN_FILE {count}")
    void duplicates() {
        Analysis a = analyze(pem(chain.leaf(), chain.leaf(), chain.inter()));
        assertThat(a.entries).hasSize(2);
        assertThat(a.warnings.stream().filter(w -> w.code().equals("DUPLICATE_IN_FILE")).findFirst().orElseThrow().params())
                .containsEntry("count", 1);
    }

    @Test
    @DisplayName("eşleşmeler TOPLU: already_tracked / same_subject / network_monitored + uyarıları; her sorgu tek kez")
    void matches_batched() {
        String fp = ManualCertificateAnalyzer.fingerprint(chain.leaf());
        ManualCertificateVersion tracked = new ManualCertificateVersion();
        tracked.setInventoryId(7L);
        tracked.setFingerprint(fp);
        tracked.setSubjectDn(chain.leaf().getSubjectX500Principal().getName());
        ManualCertificateVersion older = new ManualCertificateVersion();
        older.setInventoryId(8L);
        older.setFingerprint("AA");
        older.setSubjectDn(chain.leaf().getSubjectX500Principal().getName());
        older.setNotAfter("2026-01-01T00:00:00");
        when(versionRepo.findByCurrentTrueAndFingerprintIn(anyCollection())).thenReturn(List.of(tracked));
        when(versionRepo.findByCurrentTrueAndSubjectDnIn(anyCollection())).thenReturn(List.of(tracked, older));
        when(inventoryRepo.findAllById(any())).thenReturn(List.of(manual(7L, "api-takip"), manual(8L, "api-eski")));
        LatestCheck lc = new LatestCheck();
        lc.setDomain("api.example.test");
        lc.setFingerprint(fp);
        when(latestRepo.findByFingerprintIn(anyCollection())).thenReturn(List.of(lc));
        CertificateInventory net = new CertificateInventory();
        net.setId(9L);
        net.setDomain("api.example.test");
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of(net));

        Analysis a = analyze(pem(chain.leaf(), chain.inter(), chain.root()));
        Entry e = entry(a, chain.leaf());
        assertThat(e.alreadyTracked()).containsEntry("inventory_id", 7L).containsEntry("domain", "api-takip");
        assertThat(codes(e)).contains("ALREADY_TRACKED", "SAME_SUBJECT_TRACKED", "NETWORK_MONITORED");
        @SuppressWarnings("unchecked")
        Map<String, Object> matches = (Map<String, Object>) ((List<Map<String, Object>>) a.toJson().get("entries")).stream()
                .filter(m -> m.get("ref").equals(e.ref)).findFirst().orElseThrow().get("matches");
        assertThat((List<?>) matches.get("same_subject")).hasSize(1);
        assertThat(matches.get("network_monitored").toString()).contains("api.example.test");

        verify(versionRepo, times(1)).findByCurrentTrueAndFingerprintIn(anyCollection());
        verify(versionRepo, times(1)).findByCurrentTrueAndSubjectDnIn(anyCollection());
        verify(latestRepo, times(1)).findByFingerprintIn(anyCollection());
        verify(inventoryRepo, times(1)).findAllById(any());
        verify(inventoryRepo, times(1)).findByDomainIn(anyCollection());
        verify(inventoryRepo, times(1)).findExistingDomainsLower(anyCollection());
    }

    @Test
    @DisplayName("önerilen takip adı: CN küçük harf; çakışırsa -manuel, sonra -manuel-2; aynı dosyada tekrar etmez")
    void suggestedKeys() {
        when(inventoryRepo.findExistingDomainsLower(anyCollection()))
                .thenReturn(List.of("api.example.test", "api.example.test-manuel"));
        KeyPair kp = rsa();
        X509Certificate twin = leaf("API.Example.Test", List.of(), kp, chain.inter(), chain.interKey().getPrivate(), days(-1), days(90));
        Analysis a = analyze(pem(chain.leaf(), twin, chain.inter()));
        assertThat(entry(a, chain.leaf()).suggestedKey()).isEqualTo("api.example.test-manuel-2");
        assertThat(entry(a, twin).suggestedKey()).isEqualTo("api.example.test-manuel-3");
        assertThat(entry(a, chain.inter()).suggestedKey()).isEqualTo("example-test-issuing-ca");
    }

    @Test
    @DisplayName("PKCS#12: anahtar girdisinin yaprağı varsayılan seçim; is_key_entry + alias JSON'da")
    void pkcs12_defaultRefIsKeyEntry() {
        char[] pw = "p".toCharArray();
        byte[] pfx = pkcs12("sunucu", chain.leafKey().getPrivate(), pw, chain.leaf(), chain.inter(), chain.root());
        Analysis a = analyzer.analyze(pfx, "x.pfx", "p".toCharArray(), false);
        Entry leaf = entry(a, chain.leaf());
        assertThat(a.defaultRef).isEqualTo(leaf.ref);
        assertThat(leaf.keyEntry).isTrue();
        Map<String, Object> json = a.toJson();
        assertThat(json).containsKeys("format", "file_name", "size_bytes", "needs_password", "password_error", "warnings",
                "csr", "entries", "default_ref");
        @SuppressWarnings("unchecked")
        Map<String, Object> ej = ((List<Map<String, Object>>) json.get("entries")).get(0);
        assertThat(ej).containsKeys("ref", "alias", "is_key_entry", "is_ca", "self_signed", "subject", "subject_dn", "cn",
                "issuer", "issuer_dn", "serial_number", "not_before", "not_after", "days_remaining", "status", "san",
                "key_alg", "key_size", "signature_algorithm", "key_usage", "ext_key_usage", "cert_type", "chain",
                "chain_complete", "trust_status", "weak", "suggested_key", "warnings", "matches");
        assertThat(json.toString()).doesNotContain("-----BEGIN");   // analiz yanıtı PEM/anahtar malzemesi taşımaz
    }

    private static CertificateInventory manual(Long id, String domain) {
        CertificateInventory inv = new CertificateInventory();
        inv.setId(id);
        inv.setDomain(domain);
        inv.setCertSource(CertificateInventory.SOURCE_MANUAL);
        return inv;
    }

    @SuppressWarnings("unused")
    private static Optional<CertificateInventory> none() { return Optional.empty(); }
}
