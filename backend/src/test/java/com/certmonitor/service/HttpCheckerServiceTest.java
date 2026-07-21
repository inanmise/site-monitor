package com.certmonitor.service;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HttpCheckerService — verifySsl=true strict yolunun kurumsal CA paketine
 * ({@code cert.monitor.trust.ca-bundle-pem}, {@link TrustEvaluator}) bağlı olduğunu doğrular:
 * self-signed lokal HTTPS hedef, paket boşken PKIX ile down; sertifika pakete eklenince up.
 * Sertifika runtime'da üretilir (BouncyCastle, SAN=localhost), zaman-bombası fixture yok.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HttpCheckerServiceTest {

    private static HttpsServer server;
    private static String url;
    private static String serverCertPem;

    @BeforeAll
    static void startSelfSignedHttpsServer() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X500Name dn = new X500Name("CN=localhost, O=CertMonitor Test");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, BigInteger.valueOf(1),
                Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(2, ChronoUnit.DAYS)),
                dn, kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
        serverCertPem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("server", kp.getPrivate(), new char[0], new X509Certificate[]{ cert });
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, new SecureRandom());

        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverCtx));
        server.createContext("/", ex -> { ex.sendResponseHeaders(200, -1); ex.close(); });
        server.start();
        url = "https://localhost:" + server.getAddress().getPort() + "/";
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    /** Verilen CA paketi (PEM) ile init edilmiş servis kurar. */
    private static HttpCheckerService serviceWithBundle(String pem) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn(pem);
        HttpCheckerService svc = new HttpCheckerService(new TrustEvaluator(settings));
        svc.init();
        return svc;
    }

    @Test
    @DisplayName("verifySsl=true + CA paketi BOŞ → self-signed hedef down (PKIX/SSL hatası)")
    void strict_noBundle_selfSigned_fails() {
        Map<String, Object> r = serviceWithBundle("").check(url, "GET", "200-399", 5000, true, false);

        assertThat(r.get("ok")).isEqualTo(false);
        assertThat((String) r.get("error")).containsIgnoringCase("certif");
    }

    @Test
    @DisplayName("verifySsl=true + sertifika CA paketinde → up (kurumsal CA bundle strict yola bağlı)")
    void strict_bundleWithCert_succeeds() {
        Map<String, Object> r = serviceWithBundle(serverCertPem).check(url, "GET", "200-399", 5000, true, false);

        assertThat(r.get("error")).isNull();
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r.get("http_status")).isEqualTo(200);
    }

    @Test
    @DisplayName("verifySsl=false → CA paketi olmadan da up (trust-all davranışı korunur)")
    void trustAll_noBundle_succeeds() {
        Map<String, Object> r = serviceWithBundle("").check(url, "GET", "200-399", 5000, false, false);

        assertThat(r.get("error")).isNull();
        assertThat(r.get("ok")).isEqualTo(true);
    }
}
