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
        return analyzer.analyze(utf8(pemText), "dosya.pem", false);
    }

    /**
     * Tarayıcıda ayıklanmış yükleme (2026-10-08) — anahtar deposunun AÇIK sertifikaları: ilk sertifika anahtar girdisinin
     * yaprağı (key_entry), takma ad korunur. Özel anahtar sunucuya hiç gelmez; yalnız sayısı.
     */
    private Analysis extracted(String format, String alias, List<X509Certificate> keyChain, Map<String, X509Certificate> trusted,
                               int privateKeys) {
        List<CertificateFileParser.ParsedCert> certs = new java.util.ArrayList<>();
        for (int i = 0; i < keyChain.size(); i++) certs.add(new CertificateFileParser.ParsedCert(keyChain.get(i), alias, i == 0, null));
        trusted.forEach((a, c) -> certs.add(new CertificateFileParser.ParsedCert(c, a, false, null)));
        return analyzer.analyzeExtracted(CertificateFileParser.fromExtracted(format, "depo." + format.toLowerCase(), 4096,
                certs, null, privateKeys));
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

    // ── Zincir başına TEK girdi (2026-10-07) ─────────────────────────────────

    @Test
    @DisplayName("yaprak + ara + kök → TEK girdi (yaprak); ara ve kök zincire katlanır, ayrı girdi değil")
    void leafInterRoot_oneEntry() {
        Analysis a = analyze(pem(chain.root(), chain.inter(), chain.leaf()));
        assertThat(a.entries).hasSize(1);
        assertThat(a.certificateCount).isEqualTo(3);
        Entry leaf = a.entries.get(0);
        assertThat(leaf.ref).isEqualTo(ManualCertificateAnalyzer.fingerprint(chain.leaf()));
        assertThat(leaf.chain).containsExactly(chain.inter(), chain.root());
        assertThat(a.defaultRef).isEqualTo(leaf.ref);
        assertThat(entry(a, chain.inter())).isNull();
        assertThat(entry(a, chain.root())).isNull();
        assertThat(a.headOf(ManualCertificateAnalyzer.fingerprint(chain.inter()))).isSameAs(leaf);
        assertThat(a.headOf(ManualCertificateAnalyzer.fingerprint(chain.root()).toLowerCase())).isSameAs(leaf);
        assertThat(a.headOf(leaf.ref)).isNull();   // baş katlanmış değildir
        assertThat(fileCodes(a)).doesNotContain("MULTIPLE_LEAVES");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> json = (List<Map<String, Object>>) a.toJson().get("entries");
        assertThat(json).hasSize(1);
        assertThat((List<?>) json.get(0).get("chain")).hasSize(2);
        assertThat(a.toJson()).containsEntry("certificate_count", 3);
    }

    @Test
    @DisplayName("ara + kök (yaprak yok) → TEK girdi (ara); varsayılan seçim o")
    void interRoot_oneEntry() {
        Analysis a = analyze(pem(chain.root(), chain.inter()));
        assertThat(a.entries).hasSize(1);
        Entry inter = a.entries.get(0);
        assertThat(inter.cert).isEqualTo(chain.inter());
        assertThat(inter.chain).containsExactly(chain.root());
        assertThat(inter.trust.chainComplete()).isTrue();
        assertThat(a.defaultRef).isEqualTo(inter.ref);
        assertThat(a.headOf(ManualCertificateAnalyzer.fingerprint(chain.root()))).isSameAs(inter);
    }

    @Test
    @DisplayName("ortak arayı paylaşan iki yaprak (tarayıcıda ayıklanmış anahtar deposu) → İKİ girdi, ikisi de ortak zinciri taşır; anahtar girdisi tercih; MULTIPLE_LEAVES")
    void twoLeavesSharingIntermediate_twoEntries() {
        X509Certificate other = leaf("other.example.test", List.of("other.example.test"), freshRsa(), chain.inter(),
                chain.interKey().getPrivate(), days(-5), days(300));
        // Güvenilen girdi ÖNCE (dosya sırası): varsayılan seçim yine anahtar girdisinin yaprağı olmalı
        List<CertificateFileParser.ParsedCert> certs = List.of(
                new CertificateFileParser.ParsedCert(other, "diger", false, null),
                new CertificateFileParser.ParsedCert(chain.leaf(), "sunucu", true, null),
                new CertificateFileParser.ParsedCert(chain.inter(), "sunucu", false, null),
                new CertificateFileParser.ParsedCert(chain.root(), "sunucu", false, null));
        Analysis a = analyzer.analyzeExtracted(CertificateFileParser.fromExtracted("JKS", "depo.jks", 4096, certs, null, 1));
        assertThat(a.format()).isEqualTo("JKS");
        assertThat(entry(a, chain.leaf()).alias).isEqualTo("sunucu");
        assertThat(entry(a, chain.leaf()).keyEntry).isTrue();
        assertThat(a.warnings.stream().filter(w -> w.code().equals("PRIVATE_KEY_KEPT_LOCAL")).findFirst().orElseThrow().params())
                .containsEntry("count", 1);
        assertThat(a.entries).hasSize(2);
        assertThat(a.certificateCount).isEqualTo(4);
        assertThat(entry(a, chain.leaf()).chain).containsExactly(chain.inter(), chain.root());
        assertThat(entry(a, other).chain).containsExactly(chain.inter(), chain.root());
        assertThat(entry(a, chain.inter())).isNull();
        assertThat(a.defaultRef).isEqualTo(entry(a, chain.leaf()).ref);   // anahtar girdisi
        assertThat(a.warnings.stream().filter(w -> w.code().equals("MULTIPLE_LEAVES")).findFirst().orElseThrow().params())
                .containsEntry("count", 2);
    }

    @Test
    @DisplayName("ilgisiz iki kendinden imzalı kök → İKİ girdi (bağımsız zincirler); uç yok → MULTIPLE_LEAVES yok, seçim kullanıcıda")
    void twoUnrelatedRoots_twoEntries() {
        X509Certificate other = root("Example Other Root CA", freshRsa(), days(-10), days(1000));
        Analysis a = analyze(pem(chain.root(), other));
        assertThat(a.entries).hasSize(2);
        assertThat(entry(a, chain.root()).chain).isEmpty();
        assertThat(entry(a, other).chain).isEmpty();
        assertThat(fileCodes(a)).doesNotContain("MULTIPLE_LEAVES");
        assertThat(a.defaultRef).isNull();
        assertThat(a.headOf(entry(a, other).ref)).isNull();
    }

    @Test
    @DisplayName("tek sertifika → tek girdi, varsayılan seçim o (CA olsa da)")
    void singleCertificate_oneEntry() {
        Analysis a = analyze(pem(chain.root()));
        assertThat(a.entries).hasSize(1);
        assertThat(a.certificateCount).isEqualTo(1);
        assertThat(a.defaultRef).isEqualTo(ManualCertificateAnalyzer.fingerprint(chain.root()));
        assertThat(codes(a.entries.get(0))).contains("CA_CERTIFICATE", "SELF_SIGNED");
        assertThat(a.entries.get(0).warnings().stream().filter(w -> w.code().equals("SELF_SIGNED")).findFirst()
                .orElseThrow().severity()).isEqualTo("info");
    }

    @Test
    @DisplayName("döngülü (çapraz imzalı) küme baş bırakmasa da sertifika kaybolmaz: dosyadaki ilk sertifika baş olur")
    void crossSignedCycle_neverLosesCertificates() {
        java.security.KeyPair ka = freshRsa();
        java.security.KeyPair kb = freshRsa();
        X509Certificate seedB = root("Cycle B", kb, days(-10), days(1000));
        X509Certificate certA = intermediate("Cycle A", ka, seedB, kb.getPrivate(), days(-10), days(1000));
        X509Certificate certB = intermediate("Cycle B", kb, certA, ka.getPrivate(), days(-10), days(1000));
        Analysis a = analyze(pem(certA, certB));
        assertThat(a.entries).hasSize(1);
        assertThat(a.entries.get(0).cert).isEqualTo(certA);
        assertThat(a.entries.get(0).chain).containsExactly(certB);
        assertThat(a.headOf(ManualCertificateAnalyzer.fingerprint(certB))).isSameAs(a.entries.get(0));
    }

    @Test
    @DisplayName("JSON: preview verilirse girdi başına eklenir (hata → null, analiz düşmez); verilmezse anahtar yok")
    void previewHook() {
        Analysis a = analyze(pem(chain.leaf(), chain.inter(), chain.root()));
        @SuppressWarnings("unchecked")
        Map<String, Object> plain = ((List<Map<String, Object>>) a.toJson().get("entries")).get(0);
        assertThat(plain).doesNotContainKey("preview");
        @SuppressWarnings("unchecked")
        Map<String, Object> withPreview = ((List<Map<String, Object>>) a.toJson(e -> Map.of("chain_len", e.chain.size()))
                .get("entries")).get(0);
        assertThat(withPreview.get("preview")).isEqualTo(Map.of("chain_len", 2));
        @SuppressWarnings("unchecked")
        Map<String, Object> failing = ((List<Map<String, Object>>) a.toJson(e -> { throw new IllegalStateException("x"); })
                .get("entries")).get(0);
        assertThat(failing).containsEntry("preview", null);
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
    @DisplayName("CA girdileri: CA_CERTIFICATE; ara + kök tek girdi (ara); CA güvenilmese de UNKNOWN")
    void caEntries() {
        Analysis a = analyze(pem(chain.inter(), chain.root()));
        assertThat(entry(a, chain.root())).isNull();   // 2026-10-07: kök aranın zincirinde — ayrı girdi değil
        Entry inter = entry(a, chain.inter());
        assertThat(codes(inter)).contains("CA_CERTIFICATE").doesNotContain("SELF_SIGNED");
        assertThat(inter.trust.status()).isEqualTo("UNKNOWN");
        assertThat(a.defaultRef).isEqualTo(inter.ref);   // tek zincir başı → önerilen
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
        assertThat(a.entries).hasSize(1);   // yaprak (ara onun zincirinde)
        assertThat(a.certificateCount).isEqualTo(2);
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
        assertThat(entry(a, chain.inter())).isNull();   // ara iki yaprağın da zincirinde — öneri yalnız başlara
        // CA başının önerisi CN'den: tek başına yüklenen ara
        assertThat(analyze(pem(chain.inter())).entries.get(0).suggestedKey()).isEqualTo("example-test-issuing-ca");
    }

    @Test
    @DisplayName("PKCS#12 (tarayıcıda ayıklanmış): anahtar girdisinin yaprağı varsayılan seçim; is_key_entry + alias JSON'da; parola alanları sabit false")
    void pkcs12_defaultRefIsKeyEntry() {
        Analysis a = extracted("PKCS12", "sunucu", List.of(chain.leaf(), chain.inter(), chain.root()), Map.of(), 1);
        Entry leaf = entry(a, chain.leaf());
        assertThat(a.defaultRef).isEqualTo(leaf.ref);
        assertThat(leaf.keyEntry).isTrue();
        assertThat(leaf.alias).isEqualTo("sunucu");
        Map<String, Object> json = a.toJson();
        assertThat(json).containsKeys("format", "file_name", "size_bytes", "needs_password", "password_error", "warnings",
                "csr", "entries", "default_ref");
        assertThat(json).containsEntry("format", "PKCS12").containsEntry("needs_password", false).containsEntry("password_error", false);
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

    @Test
    @DisplayName("KAPI (2026-10-09): ham yükleme 200'den fazla FARKLI sertifika taşırsa zincir gruplamaya girmeden reddedilir")
    void rawUpload_tooManyDistinctCertificates_rejected() {
        KeyPair kp = TestCerts.rsa();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= ExtractedUpload.MAX_CERTS; i++) {
            sb.append(pem(selfSignedLeaf("bulk" + i + ".example.test", List.of("bulk" + i + ".example.test"), kp,
                    Instant.now().minus(1, ChronoUnit.DAYS), Instant.now().plus(30, ChronoUnit.DAYS))));
        }
        org.junit.jupiter.api.Assertions.assertThrows(ManualCertificateAnalyzer.TooManyCertificatesException.class,
                () -> analyze(sb.toString()));
    }

    @Test
    @DisplayName("Sınır yinelenenleri saymaz: aynı sertifika 300 kez → tek farklı sertifika, analiz edilir")
    void rawUpload_duplicatesDoNotCountTowardLimit() {
        Chain c = TestCerts.chain("dup.example.test", days(100));
        String one = pem(c.leaf());
        Analysis a = analyze(one.repeat(300));
        assertThat(a.entries).hasSize(1);
    }
}
