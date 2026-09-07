package com.sitemonitor.service;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.security.auth.x500.X500Principal;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ChainValidationServiceTest {

    private ChainValidationService service;

    @BeforeEach
    void setUp() {
        service = new ChainValidationService();
        // CRL testleri icin: onbellek @PostConstruct'ta kuruluyor ve boyutu @Value'dan geliyor;
        // ciplak new'de 0 kalir (hicbir sey onbelleklenmez). SsrfGuard da enjekte edilmemis olur —
        // izin verici bir mock, indirme yolunun GERCEKTEN denendigi testler icin gerekli
        // (OcspCrlSsrfGuardTest ile ayni desen). Blok kararlari orada olculuyor.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "ssrfGuard",
                org.mockito.Mockito.mock(SsrfGuard.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "crlCacheMaxSize", 100);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "crlCacheTtlHours", 1);
        service.init();
    }

    // ── Fingerprint ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("calculateFingerprint returns 64-char uppercase hex string")
    void calculateFingerprint_validCert_returns64HexChars() throws Exception {
        X509Certificate cert = generateCert("test.example.com", 90);
        String fp = service.calculateFingerprint(cert);
        assertThat(fp).isNotNull().hasSize(64).matches("[0-9A-F]+");
    }

    @Test
    @DisplayName("Same cert always produces the same fingerprint")
    void calculateFingerprint_sameCert_deterministicResult() throws Exception {
        X509Certificate cert = generateCert("stable.example.com", 90);
        String fp1 = service.calculateFingerprint(cert);
        String fp2 = service.calculateFingerprint(cert);
        assertThat(fp1).isEqualTo(fp2);
    }

    @Test
    @DisplayName("Different certs produce different fingerprints")
    void calculateFingerprint_differentCerts_differentResults() throws Exception {
        X509Certificate cert1 = generateCert("a.example.com", 90);
        X509Certificate cert2 = generateCert("b.example.com", 90);
        assertThat(service.calculateFingerprint(cert1))
                .isNotEqualTo(service.calculateFingerprint(cert2));
    }

    // ── Chain Analysis ────────────────────────────────────────────────────────

    @Test
    @DisplayName("analyzeChain with valid chain returns VALID status")
    void analyzeChain_allCertsValid_returnsValid() throws Exception {
        Certificate[] chain = {
            generateCert("leaf.example.com", 90),
            generateCert("intermediate-ca", 300),
            generateCert("root-ca", 3000),
        };
        Map<String, Object> result = service.analyzeChain(chain);
        assertThat(result.get("chain_status")).isEqualTo("VALID");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chainList = (List<Map<String, Object>>) result.get("chain");
        assertThat(chainList).hasSize(3);
        // position 0 is always the leaf regardless of self-signed status in tests
        assertThat(chainList.get(0).get("is_leaf")).isEqualTo(true);
    }

    @Test
    @DisplayName("analyzeChain with expired intermediate returns BROKEN status")
    void analyzeChain_expiredIntermediate_returnsBroken() throws Exception {
        Certificate[] chain = {
            generateCert("leaf.example.com", 90),
            generateExpiredCert("expired-intermediate-ca"),
        };
        Map<String, Object> result = service.analyzeChain(chain);
        assertThat(result.get("chain_status")).isEqualTo("BROKEN");
    }

    @Test
    @DisplayName("analyzeChain tracks earliest non-leaf expiry")
    void analyzeChain_tracksEarliestIntermediateExpiry() throws Exception {
        Certificate[] chain = {
            generateCert("leaf.example.com", 90),
            generateCert("intermediate-ca", 200),
            generateCert("root-ca", 3000),
        };
        Map<String, Object> result = service.analyzeChain(chain);
        Integer intermediateDays = (Integer) result.get("intermediate_days_remaining");
        assertThat(intermediateDays).isNotNull().isGreaterThanOrEqualTo(199).isLessThanOrEqualTo(201);
    }

    @Test
    @DisplayName("analyzeChain with single leaf cert — no intermediate expiry")
    void analyzeChain_singleCert_noIntermediateExpiry() throws Exception {
        Certificate[] chain = { generateCert("leaf.example.com", 90) };
        Map<String, Object> result = service.analyzeChain(chain);
        assertThat(result.get("intermediate_expiry")).isNull();
        assertThat(result.get("intermediate_days_remaining")).isNull();
    }

    @Test
    @DisplayName("analyzeChain chain list contains position, subject, not_after, days_remaining")
    void analyzeChain_chainListHasRequiredFields() throws Exception {
        Certificate[] chain = {
            generateCert("leaf.example.com", 90),
            generateCert("intermediate-ca", 300),
        };
        Map<String, Object> result = service.analyzeChain(chain);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chainList = (List<Map<String, Object>>) result.get("chain");
        Map<String, Object> leaf = chainList.get(0);
        assertThat(leaf).containsKeys("position", "subject", "issuer", "not_after", "days_remaining", "is_leaf", "is_root");
        assertThat(leaf.get("position")).isEqualTo(0);
    }

    // ── Revocation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("checkRevocation with single cert (no issuer) returns UNKNOWN")
    void checkRevocation_singleCert_returnsUnknown() throws Exception {
        Certificate[] chain = { generateCert("leaf.example.com", 90) };
        assertThat(service.checkRevocation(chain)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("checkRevocation with self-signed chain returns UNKNOWN (no OCSP URL)")
    void checkRevocation_noOcspUrl_returnsUnknown() throws Exception {
        Certificate[] chain = {
            generateCert("leaf.example.com", 90),
            generateCert("ca.example.com", 300),
        };
        // Self-signed certs have no AIA extension → OCSP returns UNKNOWN, CRL returns UNKNOWN
        String result = service.checkRevocation(chain);
        // Eskiden isIn("UNKNOWN","VALID") idi — iki zit cevabi birden kabul ettigi icin
        // hicbir sey pinlemiyordu. Danisilan liste yoksa cevap KESIN olarak UNKNOWN.
        assertThat(result).isEqualTo("UNKNOWN");
    }

    // ── CRL iptal durumu: "VALID" yalniz DANISILMIS listeye dayanir ────────────

    /**
     * Bu dort test, uretimde kanitlanmis bir sessiz yanlis-negatifi pinliyor.
     *
     * <p>Eski kod {@code return urls.isEmpty() ? "UNKNOWN" : "VALID"} diyordu: dagitim noktasi
     * TANIMLI ama hicbiri indirilemediginde dongu hicbir sey kontrol etmeden bitiyor ve sonuc
     * "iptal edilmemis" oluyordu ({@code downloadCrl} hata firlatmaz, null doner).
     *
     * <p>Uretim log'unda ic CA ile imzali her sertifikada iki dagitim noktasi da dusuyordu
     * (biri ldap:// = desteklenmeyen sema, digeri erisilemeyen HTTP) ve sertifika yine de
     * temiz raporlaniyordu. Sertifika izleme urununde en pahali hata turu: IPTAL EDILMIS bir
     * sertifika temiz gorunur.
     */
    @Test
    @DisplayName("KAPI: hicbir CRL indirilemezse sonuc UNKNOWN (eski kod VALID donerdi)")
    void checkCrl_noCrlDownloadable_isUnknownNotValid() throws Exception {
        // ldap:// bu istemcinin desteklemedigi semadir → indirme YOK, danisilan liste YOK.
        X509Certificate cert = generateCertWithCrlDp("leaf.example.com",
                "ldap:///CN=Test%20CA,CN=CDP?certificateRevocationList");

        assertThat(service.checkCrl(cert))
                .as("danisilamayan CRL 'iptal edilmemis' sayiliyor")
                .isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("KAPI: dagitim noktalarinin HEPSI duserse yine UNKNOWN")
    void checkCrl_allDistributionPointsFail_isUnknown() throws Exception {
        // Uretimdeki birebir sekil: bir ldap:// + bir erisilemeyen http://
        X509Certificate cert = generateCertWithCrlDp("leaf.example.com",
                "ldap:///CN=Test%20CA?certificateRevocationList",
                "http://" + TestHosts.UNRESOLVABLE + "/CertEnroll/Test%20CA.crl");

        assertThat(service.checkCrl(cert)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("CRL gercekten indirilip sertifika listede DEGILSE VALID")
    void checkCrl_consultedAndNotListed_isValid() throws Exception {
        String url = "http://crl.example.com/test.crl";
        X509Certificate cert = generateCertWithCrlDp("leaf.example.com", url);
        // Onbellege GERCEK bir CRL koyuyoruz → danisilmis sayilir (indirme yolu I/O oldugu icin
        // atlanir; olculen sey "danisildi mi" ayrimi, HTTP tasimasi degil).
        primeCrlCache(url, buildCrl(cert, null));

        assertThat(service.checkCrl(cert)).isEqualTo("VALID");
    }

    @Test
    @DisplayName("CRL indirilip sertifika listedeyse REVOKED")
    void checkCrl_consultedAndListed_isRevoked() throws Exception {
        String url = "http://crl.example.com/test.crl";
        X509Certificate cert = generateCertWithCrlDp("leaf.example.com", url);
        primeCrlCache(url, buildCrl(cert, cert.getSerialNumber()));

        assertThat(service.checkCrl(cert)).isEqualTo("REVOKED");
    }

    // ── Indirme tavani: dev CRL heap'i doldurmasin ────────────────────────────

    /**
     * CRL adresi sertifikanin CRL-DP uzantisindan, yani IZLENEN SUNUCUNUN yazdigi bir dizeden
     * geliyor ve izleme hedefini siradan bir kullanici tanimlayabiliyor. Tavansiz okuma, tek-pod
     * uretimde OOM = kesinti demekti; {@code setReadTimeout} de korumuyor, cunku o okumalar ARASI
     * suredir — surekli akan bir govdede hic tetiklenmez. Ayrica indirilen CRL 200 kayitlik
     * onbellege giriyor, yani tavan iki kez onemli.
     *
     * <p>Tavan asilinca indirme BASARISIZ sayilir → {@code consulted} false kalir → sonuc
     * {@code UNKNOWN}. Yani sessiz bir "temiz" degil, gorunur bir "dogrulanamadi".
     *
     * <p><b>DURUSTLUK NOTU — bu test tavani IZOLE ETMEZ.</b> Tavan kaldirilsa da yesil kalir,
     * cunku o durumda dev govde tamamen okunur ama gecerli bir CRL olmadigi icin ayristirma
     * duser ve sonuc yine UNKNOWN olur. Burada olculen sey uctan uca DAVRANIS: dev bir govde
     * ne cokme uretir ne de "iptal edilmemis" sonucu. Tavanin KENDISI bulundugu yerde
     * pinleniyor: {@code HttpBodiesTest} — tavan kaldirilirsa ORASI kirilir.
     */
    @Test
    @DisplayName("Uctan uca: dev CRL govdesi ne cokertir ne de VALID uretir (sonuc UNKNOWN)")
    void downloadCrl_oversizedBody_rejected() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/big.crl", ex -> {
            byte[] chunk = new byte[64 * 1024];
            ex.sendResponseHeaders(200, 0);   // chunked — Content-Length ile onceden reddedilmesin
            try (java.io.OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < 96; i++) os.write(chunk);   // ~6 MB > 5 MB tavan
            } catch (java.io.IOException ignored) {
                // Tavan asilinca istemci baglantiyi kapatir; sunucu tarafi yazamaz — beklenen.
            }
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/big.crl";
            X509Certificate cert = generateCertWithCrlDp("leaf.example.com", url);

            assertThat(service.checkCrl(cert))
                    .as("tavani asan govde 'iptal edilmemis' sayilmamali")
                    .isEqualTo("UNKNOWN");
        } finally {
            server.stop(0);
        }
    }

    /**
     * Tavanin NORMAL yolu bozmadigini olcer — ve gercekten INDIRIR (onbellek doldurulmaz).
     * Aksi halde test adi "indirilip ayristirilir" derken indirme hic calismaz, yani hicbir sey
     * pinlemez. Govde sunucudan gelecegi icin handler mutable bir tutucudan okuyor: sertifika
     * ancak port belli olduktan SONRA kurulabiliyor, CRL de o sertifikaya gore uretiliyor.
     */
    @Test
    @DisplayName("Tavan NORMAL yolu bozmaz: gercek boyutlu CRL INDIRILIP ayristirilir")
    void downloadCrl_normalSizedBody_isDownloadedAndParsed() throws Exception {
        byte[][] body = new byte[1][];
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok.crl", ex -> {
            ex.sendResponseHeaders(200, body[0].length);
            try (java.io.OutputStream os = ex.getResponseBody()) { os.write(body[0]); }
            ex.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/ok.crl";
            X509Certificate cert = generateCertWithCrlDp("leaf.example.com", url);
            body[0] = buildCrl(cert, cert.getSerialNumber()).getEncoded();
            assertThat(body[0].length).as("uretilen CRL tavanin altinda olmali").isLessThan(MAX_CRL);

            // Onbellek BOS → gercek HTTP indirmesi + tavanli okuma + ayristirma calisir.
            assertThat(service.checkCrl(cert)).isEqualTo("REVOKED");
        } finally {
            server.stop(0);
        }
    }

    /** Uretim sabitiyle ayni tavan (ChainValidationService.MAX_CRL_BYTES). */
    private static final int MAX_CRL = 5 * 1024 * 1024;

    // ── Test Cert Utilities ───────────────────────────────────────────────────

    /** CRL onbellegine hazir bir liste koyar (indirme yolunu atlar). */
    private void primeCrlCache(String url, java.security.cert.X509CRL crl) {
        @SuppressWarnings("unchecked")
        com.github.benmanes.caffeine.cache.Cache<String, java.security.cert.X509CRL> cache =
                (com.github.benmanes.caffeine.cache.Cache<String, java.security.cert.X509CRL>)
                        org.springframework.test.util.ReflectionTestUtils.getField(service, "crlCache");
        cache.put(url, crl);
    }

    /**
     * Imzali bir CRL; {@code revokedSerial} null degilse o seri numarasi listeye girer.
     *
     * <p>CRL, sertifikanin KENDI issuer DN'i adina kurulur: {@code X509CRL.isRevoked(Certificate)}
     * seri numarasinin yani sira issuer eslesmesine de bakar, farkli bir CA adiyla kurulan liste
     * sertifikayi hic gormez (ilk yazimda "Test CA" kullanip testi bosa dusurmustum).
     */
    private static java.security.cert.X509CRL buildCrl(X509Certificate cert, BigInteger revokedSerial)
            throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(1024);
        KeyPair ca = kpg.generateKeyPair();

        // DN'i METINDEN kurmak yetmiyor: BC yeniden kodlarken DER farkli cikabiliyor (PrintableString
        // vs UTF8String) ve X500Principal esitligi bozuluyor -> isRevoked sertifikayi hic gormuyor.
        // Kodlanmis DN'den kurmak birebir ayni DER'i korur.
        org.bouncycastle.cert.X509v2CRLBuilder b = new org.bouncycastle.cert.X509v2CRLBuilder(
                org.bouncycastle.asn1.x500.X500Name.getInstance(
                        cert.getIssuerX500Principal().getEncoded()), new Date());
        b.setNextUpdate(new Date(System.currentTimeMillis() + 86_400_000L));
        if (revokedSerial != null) {
            b.addCRLEntry(revokedSerial, new Date(),
                    org.bouncycastle.asn1.x509.CRLReason.privilegeWithdrawn);
        }
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(ca.getPrivate());
        return new org.bouncycastle.cert.jcajce.JcaX509CRLConverter().getCRL(b.build(signer));
    }

    /** CRL dagitim noktasi (CRL-DP) uzantisi tasiyan self-signed sertifika. */
    private static X509Certificate generateCertWithCrlDp(String cn, String... crlUrls) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(1024);
        KeyPair kp = kpg.generateKeyPair();

        Date notBefore = new Date(System.currentTimeMillis() - 1000);
        Date notAfter = new Date(System.currentTimeMillis() + 90L * 86_400_000L);
        X500Principal subject = new X500Principal("CN=" + cn + ", O=Test, C=TR");

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.nanoTime()), notBefore, notAfter, subject, kp.getPublic());

        org.bouncycastle.asn1.x509.DistributionPoint[] points =
                new org.bouncycastle.asn1.x509.DistributionPoint[crlUrls.length];
        for (int i = 0; i < crlUrls.length; i++) {
            org.bouncycastle.asn1.x509.GeneralName gn = new org.bouncycastle.asn1.x509.GeneralName(
                    org.bouncycastle.asn1.x509.GeneralName.uniformResourceIdentifier, crlUrls[i]);
            points[i] = new org.bouncycastle.asn1.x509.DistributionPoint(
                    new org.bouncycastle.asn1.x509.DistributionPointName(
                            new org.bouncycastle.asn1.x509.GeneralNames(gn)), null, null);
        }
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.cRLDistributionPoints, false,
                new org.bouncycastle.asn1.x509.CRLDistPoint(points));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(kp.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static X509Certificate generateCert(String cn, int daysValid) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(1024);
        KeyPair kp = kpg.generateKeyPair();

        Date notBefore = new Date(System.currentTimeMillis() - 1000);
        Date notAfter = new Date(System.currentTimeMillis() + (long) daysValid * 86_400_000L);

        X500Principal subject = new X500Principal("CN=" + cn + ", O=Test, C=TR");
        BigInteger serial = BigInteger.valueOf(System.nanoTime());

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(kp.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static X509Certificate generateExpiredCert(String cn) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(1024);
        KeyPair kp = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 10 * 86_400_000L);
        Date notAfter = new Date(now - 86_400_000L); // expired 1 day ago

        X500Principal subject = new X500Principal("CN=" + cn + ", O=Test, C=TR");
        BigInteger serial = BigInteger.valueOf(System.nanoTime());

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA").build(kp.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }
}
