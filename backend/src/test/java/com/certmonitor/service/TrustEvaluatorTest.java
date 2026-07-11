package com.certmonitor.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * TrustEvaluator — sertifika zincirinin güven durumu: JVM cacerts VEYA admin CA paketiyle (PEM)
 * bir güven köküne bağlanıyor mu? Self-signed CA runtime'da üretilir (BouncyCastle), zaman-bombası
 * fixture yok. Çekim (trust-all soket) ayrı; bu test yalnız güven değerlendirme mantığını doğrular.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrustEvaluatorTest {

    @Mock AppSettingsService appSettings;

    private static X509Certificate selfSigned;
    private static String selfSignedPem;

    @BeforeAll
    static void generateSelfSigned() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X500Name dn = new X500Name("CN=Test Internal CA, O=CertMonitor Test");
        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(1, ChronoUnit.HOURS));
        Date notAfter  = Date.from(now.plus(2, ChronoUnit.DAYS));

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(1), notBefore, notAfter, dn, kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(kp.getPrivate());
        selfSigned = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));

        selfSignedPem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(selfSigned.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    @Test
    @DisplayName("CA paketi YOK → self-signed/iç-CA zinciri UNTRUSTED")
    void noBundle_selfSigned_untrusted() {
        when(appSettings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn("");
        TrustEvaluator ev = new TrustEvaluator(appSettings);

        TrustEvaluator.TrustResult r = ev.evaluate(new X509Certificate[]{ selfSigned });

        assertThat(r.trusted()).isFalse();
        assertThat(r.reason()).isNotBlank();
    }

    @Test
    @DisplayName("CA paketine zincirin CA'sı eklenince → TRUSTED")
    void bundleWithCa_selfSigned_trusted() {
        when(appSettings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn(selfSignedPem);
        TrustEvaluator ev = new TrustEvaluator(appSettings);

        TrustEvaluator.TrustResult r = ev.evaluate(new X509Certificate[]{ selfSigned });

        assertThat(r.trusted()).isTrue();
        assertThat(r.reason()).isNull();
    }

    @Test
    @DisplayName("Bozuk PEM → tolere edilir (yalnız varsayılan truststore) → UNTRUSTED, exception yok")
    void badBundle_tolerated_untrusted() {
        when(appSettings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString()))
                .thenReturn("not a valid pem at all");
        TrustEvaluator ev = new TrustEvaluator(appSettings);

        TrustEvaluator.TrustResult r = ev.evaluate(new X509Certificate[]{ selfSigned });

        assertThat(r.trusted()).isFalse();
    }

    @Test
    @DisplayName("Boş zincir → UNTRUSTED")
    void emptyChain_untrusted() {
        when(appSettings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn("");
        TrustEvaluator ev = new TrustEvaluator(appSettings);

        assertThat(ev.evaluate(new X509Certificate[0]).trusted()).isFalse();
        assertThat(ev.evaluate(null).trusted()).isFalse();
    }

    // ── outbound HTTPS TrustManager (RDAP; kurumsal MITM-proxy re-signed cert) ────────────

    @Test
    @DisplayName("outbound: CA paketi YOK → self-signed/iç-CA sunucu zinciri reddedilir (fırlatır)")
    void outbound_noBundle_rejectsSelfSigned() {
        when(appSettings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn("");
        TrustEvaluator ev = new TrustEvaluator(appSettings);

        assertThat(ev.outboundSslContext()).isNotNull();   // SSLContext kurulabilir
        X509TrustManager tm = ev.compositeTrustManager();
        assertThatThrownBy(() -> tm.checkServerTrusted(new X509Certificate[]{ selfSigned }, "RSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    @DisplayName("outbound: admin CA paketi zincirin CA'sını içerince → kabul (fırlatmaz)")
    void outbound_bundleWithCa_acceptsSelfSigned() {
        when(appSettings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn(selfSignedPem);
        TrustEvaluator ev = new TrustEvaluator(appSettings);
        X509TrustManager tm = ev.compositeTrustManager();

        assertThatCode(() -> tm.checkServerTrusted(new X509Certificate[]{ selfSigned }, "RSA"))
                .doesNotThrowAnyException();
    }
}
