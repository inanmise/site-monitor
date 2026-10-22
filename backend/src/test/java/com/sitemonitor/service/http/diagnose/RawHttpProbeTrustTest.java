package com.sitemonitor.service.http.diagnose;

import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.TrustEvaluator;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CHECKS-1 tanılama ayağı (2026-10-22): HTTP / keyword / sayfa tanılamasının güven kararı, ana değerlendirme güvenmezse
 * host'un PİNLİ CA'sını da dener. O deneme anahtar algoritmasını ("EC") JDK authType'ı olarak veriyordu → iki sertifikalı
 * EC zincirinde "Unknown authType: EC" → pinli CA'sı olan her ECDSA host'u tanılamada "güvenilmiyor" görünürdü (izlemenin
 * kendisi güvenirken). Gerçek iki sertifikalı zincirle sınanır (tek, kendinden imzalı sertifikada denetim hiç koşmaz).
 */
class RawHttpProbeTrustTest {

    private static final AtomicLong SERIAL = new AtomicLong(500);
    private static KeyPair caKeys;
    private static X509Certificate ca;

    @BeforeAll
    static void setUp() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        caKeys = ec();
        X500Name name = new X500Name("CN=Pinned EC Root, O=SiteMonitor Test");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(SERIAL.incrementAndGet()),
                Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(30, ChronoUnit.DAYS)), name, caKeys.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        ca = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(b.build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(caKeys.getPrivate())));
    }

    private static RawHttpProbe probe(TrustEvaluator evaluator, CaAutoPinService pin) {
        return new RawHttpProbe(null, null, evaluator, pin, null, null);
    }

    private static TrustEvaluator untrustingEvaluator() {
        TrustEvaluator ev = mock(TrustEvaluator.class);
        when(ev.evaluate(any())).thenReturn(new TrustEvaluator.TrustResult(false, "PKIX path building failed"));
        return ev;
    }

    @Test
    @DisplayName("EC yaprak + pinli EC kök: ana değerlendirme güvenmese de pinli CA zinciri doğrular → güvenilir")
    void ecChain_pinnedCa_trusted() throws Exception {
        X509Certificate leaf = leaf("CN=ec.example.test", ec().getPublic(), caKeys.getPrivate(), ca);
        CaAutoPinService pin = mock(CaAutoPinService.class);
        when(pin.trustManagerForHost("ec.example.test", 443)).thenReturn(tmTrusting(ca));

        String[] r = probe(untrustingEvaluator(), pin).trust(new X509Certificate[]{ leaf, ca }, "ec.example.test", 443);

        assertThat(r[0]).as("güvenilir = null neden").isNull();
    }

    @Test
    @DisplayName("Pinli CA başka bir kök → ilk neden (ana değerlendirmenin) korunur")
    void unrelatedPin_keepsFirstReason() throws Exception {
        X509Certificate leaf = leaf("CN=ec2.example.test", ec().getPublic(), caKeys.getPrivate(), ca);
        KeyPair otherKeys = ec();
        X500Name otherName = new X500Name("CN=Other Root");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(otherName, BigInteger.valueOf(SERIAL.incrementAndGet()),
                Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(30, ChronoUnit.DAYS)), otherName, otherKeys.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        X509Certificate other = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(b.build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(otherKeys.getPrivate())));
        CaAutoPinService pin = mock(CaAutoPinService.class);
        when(pin.trustManagerForHost("ec2.example.test", 443)).thenReturn(tmTrusting(other));

        String[] r = probe(untrustingEvaluator(), pin).trust(new X509Certificate[]{ leaf, ca }, "ec2.example.test", 443);

        assertThat(r[0]).isEqualTo("PKIX path building failed");
    }

    @Test
    @DisplayName("Boş zincir → neden; pin yok → ana değerlendirmenin nedeni")
    void guards() throws Exception {
        assertThat(probe(untrustingEvaluator(), null).trust(new X509Certificate[0], "h", 443)[0]).isNotNull();
        X509Certificate leaf = leaf("CN=ec3.example.test", ec().getPublic(), caKeys.getPrivate(), ca);
        assertThat(probe(untrustingEvaluator(), null).trust(new X509Certificate[]{ leaf, ca }, "ec3.example.test", 443)[0])
                .isEqualTo("PKIX path building failed");
    }

    // ── yardımcılar ───────────────────────────────────────────────────────────────────────────────

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static X509Certificate leaf(String dn, PublicKey key, PrivateKey issuerKey, X509Certificate issuer) throws Exception {
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                X500Name.getInstance(issuer.getSubjectX500Principal().getEncoded()), BigInteger.valueOf(SERIAL.incrementAndGet()),
                Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(10, ChronoUnit.DAYS)), new X500Name(dn), key);
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(b.build(new JcaContentSignerBuilder("SHA256withECDSA").setProvider("BC").build(issuerKey)));
    }

    private static X509TrustManager tmTrusting(X509Certificate root) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("ca", root);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return (X509TrustManager) tmf.getTrustManagers()[0];
    }
}
