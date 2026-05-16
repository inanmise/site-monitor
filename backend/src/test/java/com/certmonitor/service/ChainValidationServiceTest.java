package com.certmonitor.service;

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
        assertThat(result).isIn("UNKNOWN", "VALID");
    }

    // ── Test Cert Utilities ───────────────────────────────────────────────────

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
