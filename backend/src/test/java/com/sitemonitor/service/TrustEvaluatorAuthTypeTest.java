package com.sitemonitor.service;

import com.sitemonitor.service.manualcert.ManualCertificateChains;
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
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CHECKS-1 (2026-10-22): güven değerlendirmesi JDK'ya anahtar algoritmasını ("EC" / "RSA") {@code authType} olarak
 * veriyordu. Uç-sertifika denetimi zincir 1'den uzunken koştuğu için GERÇEK iki sertifikalı zincirlerle sınanır (eski
 * test tek, kendinden imzalı bir sertifikaydı: o sertifika kendi güven kökü olduğundan denetim hiç koşmuyordu).
 * Her vakada önce "hata vardı" kanıtı (eski çağrı biçimi fırlatır), sonra düzeltilmiş hüküm.
 */
class TrustEvaluatorAuthTypeTest {

    private static final AtomicLong SERIAL = new AtomicLong(100);

    private static KeyPair ecCaKeys;
    private static X509Certificate ecCa;
    private static KeyPair rsaCaKeys;
    private static X509Certificate rsaCa;
    private static KeyPair otherCaKeys;
    private static X509Certificate otherCa;

    @BeforeAll
    static void setUp() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        ecCaKeys = ec();
        ecCa = ca("CN=Test EC Root, O=SiteMonitor Test", ecCaKeys, "SHA256withECDSA");
        rsaCaKeys = rsa();
        rsaCa = ca("CN=Test RSA Root, O=SiteMonitor Test", rsaCaKeys, "SHA256withRSA");
        otherCaKeys = ec();
        otherCa = ca("CN=Unrelated Root, O=Elsewhere", otherCaKeys, "SHA256withECDSA");
    }

    private static TrustEvaluator evaluatorTrusting(X509Certificate... cas) {
        AppSettingsService settings = mock(AppSettingsService.class);
        StringBuilder pem = new StringBuilder();
        for (X509Certificate c : cas) pem.append(pem(c));
        when(settings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn(pem.toString());
        return new TrustEvaluator(settings);
    }

    // ── Hatanın kendisi: eski çağrı biçimi (anahtar algoritması authType) ─────────────────────────

    @Test
    @DisplayName("Kanıt: JDK 'EC' authType'ını tanımaz — eski evaluate bu yüzden her ECDSA zincirini UNTRUSTED sayıyordu")
    void proof_keyAlgorithmIsNotAnAuthType() throws Exception {
        X509Certificate leaf = leaf("CN=ec.example.test", ec().getPublic(), ecCaKeys.getPrivate(), ecCa,
                "SHA256withECDSA", KeyUsage.digitalSignature, true);
        X509TrustManager tm = tmTrusting(ecCa);
        X509Certificate[] chain = { leaf, ecCa };

        assertThatThrownBy(() -> tm.checkServerTrusted(chain, leaf.getPublicKey().getAlgorithm()))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("Unknown authType");
        // Düzeltilmiş yol: aynı zincir, aynı güven deposu → geçer
        TrustEvaluator.checkServerTrustedAnyAuthType(tm, chain);
    }

    // ── Düzeltilmiş hüküm ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC kök + EC yaprak (digitalSignature, serverAuth), iki sertifikalı zincir → TRUSTED")
    void ecLeaf_digitalSignature_trusted() throws Exception {
        X509Certificate leaf = leaf("CN=ec.example.test", ec().getPublic(), ecCaKeys.getPrivate(), ecCa,
                "SHA256withECDSA", KeyUsage.digitalSignature, true);

        TrustEvaluator.TrustResult r = evaluatorTrusting(ecCa).evaluate(new X509Certificate[]{ leaf, ecCa });

        assertThat(r.trusted()).as("reason=%s", r.reason()).isTrue();
        assertThat(r.reason()).isNull();
    }

    @Test
    @DisplayName("Sunucu kökü göndermese de (yalnız yaprak) EC zincir paketteki köke bağlanır → TRUSTED")
    void ecLeafOnly_chainBuiltFromBundle_trusted() throws Exception {
        X509Certificate leaf = leaf("CN=ec-only.example.test", ec().getPublic(), ecCaKeys.getPrivate(), ecCa,
                "SHA256withECDSA", KeyUsage.digitalSignature, true);

        assertThat(evaluatorTrusting(ecCa).evaluate(new X509Certificate[]{ leaf }).trusted()).isTrue();
    }

    @Test
    @DisplayName("RSA yaprak YALNIZ digitalSignature (keyEncipherment yok — TLS 1.3'te geçerli) → TRUSTED")
    void rsaLeaf_digitalSignatureOnly_trusted() throws Exception {
        X509Certificate leaf = leaf("CN=rsa-ds.example.test", rsa().getPublic(), rsaCaKeys.getPrivate(), rsaCa,
                "SHA256withRSA", KeyUsage.digitalSignature, true);
        X509Certificate[] chain = { leaf, rsaCa };

        // Kanıt: eski çağrı "RSA" geçerek keyEncipherment istiyordu
        assertThatThrownBy(() -> tmTrusting(rsaCa).checkServerTrusted(chain, "RSA"))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("key encipherment");
        assertThat(evaluatorTrusting(rsaCa).evaluate(chain).trusted()).isTrue();
    }

    @Test
    @DisplayName("RSA yaprak YALNIZ keyEncipherment (eski tip) → 'RSA' geri denemesiyle TRUSTED")
    void rsaLeaf_keyEnciphermentOnly_trusted() throws Exception {
        X509Certificate leaf = leaf("CN=rsa-ke.example.test", rsa().getPublic(), rsaCaKeys.getPrivate(), rsaCa,
                "SHA256withRSA", KeyUsage.keyEncipherment, true);
        X509Certificate[] chain = { leaf, rsaCa };

        assertThatThrownBy(() -> tmTrusting(rsaCa).checkServerTrusted(chain, "UNKNOWN"))
                .isInstanceOf(CertificateException.class);
        assertThat(evaluatorTrusting(rsaCa).evaluate(chain).trusted()).isTrue();
    }

    @Test
    @DisplayName("RSA yaprak, KeyUsage uzantısı YOK → TRUSTED")
    void rsaLeaf_noKeyUsage_trusted() throws Exception {
        X509Certificate leaf = leaf("CN=rsa-noku.example.test", rsa().getPublic(), rsaCaKeys.getPrivate(), rsaCa,
                "SHA256withRSA", -1, true);
        assertThat(evaluatorTrusting(rsaCa).evaluate(new X509Certificate[]{ leaf, rsaCa }).trusted()).isTrue();
    }

    @Test
    @DisplayName("EC yaprak YALNIZ keyAgreement (statik ECDH) → 'ECDH_ECDSA' geri denemesiyle TRUSTED")
    void ecLeaf_keyAgreementOnly_trusted() throws Exception {
        X509Certificate leaf = leaf("CN=ecdh.example.test", ec().getPublic(), ecCaKeys.getPrivate(), ecCa,
                "SHA256withECDSA", KeyUsage.keyAgreement, true);
        assertThat(evaluatorTrusting(ecCa).evaluate(new X509Certificate[]{ leaf, ecCa }).trusted()).isTrue();
    }

    @Test
    @DisplayName("Ed25519 yaprak (EC kök imzalı) → UNKNOWN authType ile TRUSTED")
    void ed25519Leaf_trusted() throws Exception {
        KeyPair ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        X509Certificate leaf = leaf("CN=ed.example.test", ed.getPublic(), ecCaKeys.getPrivate(), ecCa,
                "SHA256withECDSA", KeyUsage.digitalSignature, true);
        assertThat(evaluatorTrusting(ecCa).evaluate(new X509Certificate[]{ leaf, ecCa }).trusted()).isTrue();
    }

    @Test
    @DisplayName("İlgisiz bir CA'nın imzaladığı yaprak → UNTRUSTED (geri deneme güveni GENİŞLETMEZ)")
    void leafFromUnrelatedCa_untrusted() throws Exception {
        X509Certificate leaf = leaf("CN=evil.example.test", ec().getPublic(), otherCaKeys.getPrivate(), otherCa,
                "SHA256withECDSA", KeyUsage.digitalSignature, true);

        TrustEvaluator.TrustResult r = evaluatorTrusting(ecCa, rsaCa).evaluate(new X509Certificate[]{ leaf, otherCa });

        assertThat(r.trusted()).isFalse();
        assertThat(r.reason()).isNotBlank().doesNotContain("Unknown authType");
    }

    @Test
    @DisplayName("Yalnız clientAuth EKU'lu yaprak → UNTRUSTED (sunucu kimliği değil)")
    void clientAuthOnlyLeaf_untrusted() throws Exception {
        X509Certificate leaf = leaf("CN=client.example.test", ec().getPublic(), ecCaKeys.getPrivate(), ecCa,
                "SHA256withECDSA", KeyUsage.digitalSignature, false);
        assertThat(evaluatorTrusting(ecCa).evaluate(new X509Certificate[]{ leaf, ecCa }).trusted()).isFalse();
    }

    @Test
    @DisplayName("Ne digitalSignature ne keyAgreement taşıyan EC yaprak → UNTRUSTED, neden 'Unknown authType' DEĞİL")
    void ecLeaf_wrongKeyUsage_untrusted_withRealReason() throws Exception {
        X509Certificate leaf = leaf("CN=ec-bad.example.test", ec().getPublic(), ecCaKeys.getPrivate(), ecCa,
                "SHA256withECDSA", KeyUsage.dataEncipherment, true);
        X509Certificate[] chain = { leaf, ecCa };

        assertThatThrownBy(() -> TrustEvaluator.checkServerTrustedAnyAuthType(tmTrusting(ecCa), chain))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("KeyUsage")
                .hasMessageNotContaining("Unknown authType");
        assertThat(evaluatorTrusting(ecCa).evaluate(chain).trusted()).isFalse();
    }

    @Test
    @DisplayName("checkServerTrustedAnyAuthType: TM yok / boş zincir → CertificateException (NPE değil)")
    void anyAuthType_guards() {
        assertThatThrownBy(() -> TrustEvaluator.checkServerTrustedAnyAuthType(null, new X509Certificate[]{ ecCa }))
                .isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> TrustEvaluator.checkServerTrustedAnyAuthType(tmTrusting(ecCa), new X509Certificate[0]))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    @DisplayName("authType adayları anahtar türüne göre: RSA → UNKNOWN,RSA · EC → UNKNOWN,ECDH_ECDSA · diğer → UNKNOWN")
    void authTypeCandidates() throws Exception {
        assertThat(TrustEvaluator.serverAuthTypes(rsaCa)).containsExactly("UNKNOWN", "RSA");
        assertThat(TrustEvaluator.serverAuthTypes(ecCa)).containsExactly("UNKNOWN", "ECDH_ECDSA");
        KeyPair ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        X509Certificate edLeaf = leaf("CN=ed2.example.test", ed.getPublic(), ecCaKeys.getPrivate(), ecCa,
                "SHA256withECDSA", KeyUsage.digitalSignature, true);
        assertThat(TrustEvaluator.serverAuthTypes(edLeaf)).containsExactly("UNKNOWN");
    }

    @Test
    @DisplayName("Elle yüklenen EC zinciri (yaprak + kök) → ManualCertificateChains.trust TRUSTED")
    void manualUpload_ecChain_trusted() throws Exception {
        X509Certificate leaf = leaf("CN=upload-ec.example.test", ec().getPublic(), ecCaKeys.getPrivate(), ecCa,
                "SHA256withECDSA", KeyUsage.digitalSignature, true);

        ManualCertificateChains.Trust t = ManualCertificateChains.trust(evaluatorTrusting(ecCa), leaf, List.of(ecCa));

        assertThat(t.status()).isEqualTo("TRUSTED");
    }

    // ── yardımcılar ───────────────────────────────────────────────────────────────────────────────

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static X509Certificate ca(String dn, KeyPair keys, String sigAlg) throws Exception {
        X500Name name = new X500Name(dn);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(SERIAL.incrementAndGet()),
                Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(30, ChronoUnit.DAYS)), name, keys.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(b.build(new JcaContentSignerBuilder(sigAlg).setProvider("BC").build(keys.getPrivate())));
    }

    /** @param keyUsage BC KeyUsage bit maskesi; {@code -1} → KeyUsage uzantısı yok. */
    private static X509Certificate leaf(String dn, PublicKey key, PrivateKey issuerKey, X509Certificate issuer,
                                        String sigAlg, int keyUsage, boolean serverAuth) throws Exception {
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                X500Name.getInstance(issuer.getSubjectX500Principal().getEncoded()), BigInteger.valueOf(SERIAL.incrementAndGet()),
                Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(10, ChronoUnit.DAYS)), new X500Name(dn), key);
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        if (keyUsage >= 0) b.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsage));
        b.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(serverAuth ? KeyPurposeId.id_kp_serverAuth : KeyPurposeId.id_kp_clientAuth));
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(b.build(new JcaContentSignerBuilder(sigAlg).setProvider("BC").build(issuerKey)));
    }

    private static X509TrustManager tmTrusting(X509Certificate ca) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("ca", ca);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return (X509TrustManager) tmf.getTrustManagers()[0];
    }

    private static String pem(X509Certificate c) {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(c.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
