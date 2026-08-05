package com.sitemonitor.service;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HstsDiagnosticsService#diagnose} — HSTS başlık analizi. Yerel self-signed HTTPS sunucusuna
 * (com.sun HttpsServer) karşı uçtan uca çalışır: servis sertifika doğrulamasını bilerek atladığı için
 * (başlık her koşulda okunmalı) pinleme/güven kurulumu gerekmez. Dış host'a bağımlılık yok.
 */
class HstsDiagnosticsServiceTest {

    private HttpsServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    /** stsHeader null ise başlık gönderilmez; aksi halde verilen Strict-Transport-Security değeriyle 200 döner. */
    private Map<String, Object> diagnoseWithHeader(String stsHeader) throws Exception {
        KeyPair kp = generateKeyPair();
        X509Certificate cert = selfSigned(kp);

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("server", kp.getPrivate(), new char[0], new X509Certificate[]{ cert });
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());

        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/", ex -> {
            if (stsHeader != null) ex.getResponseHeaders().add("Strict-Transport-Security", stsHeader);
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        HstsDiagnosticsService svc = new HstsDiagnosticsService();
        ReflectionTestUtils.setField(svc, "timeoutSeconds", 5);
        return svc.diagnose("localhost", port);
    }

    @Test
    @DisplayName("diagnose: max-age>0 + includeSubDomains + preload → ENFORCED, yönergeler ayrıştırılır")
    void diagnose_fullPolicy_enforced() throws Exception {
        Map<String, Object> r = diagnoseWithHeader("max-age=31536000; includeSubDomains; preload");

        assertThat(r.get("status")).isEqualTo("ok");
        assertThat(r.get("verdict")).isEqualTo("ENFORCED");
        assertThat(r.get("enabled")).isEqualTo(true);
        assertThat(r.get("header_present")).isEqualTo(true);
        assertThat(r.get("max_age")).isEqualTo(31536000L);
        assertThat(r.get("include_subdomains")).isEqualTo(true);
        assertThat(r.get("preload")).isEqualTo(true);
    }

    @Test
    @DisplayName("diagnose: başlık yok → ABSENT (header_present=false, enabled=false)")
    void diagnose_noHeader_absent() throws Exception {
        Map<String, Object> r = diagnoseWithHeader(null);

        assertThat(r.get("status")).isEqualTo("ok");
        assertThat(r.get("verdict")).isEqualTo("ABSENT");
        assertThat(r.get("header_present")).isEqualTo(false);
        assertThat(r.get("enabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("diagnose: max-age=0 → NOT_ENFORCED (politika bilerek temizlenmiş)")
    void diagnose_maxAgeZero_notEnforced() throws Exception {
        Map<String, Object> r = diagnoseWithHeader("max-age=0");

        assertThat(r.get("verdict")).isEqualTo("NOT_ENFORCED");
        assertThat(r.get("max_age")).isEqualTo(0L);
        assertThat(r.get("enabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("diagnose: kapalı porta bağlanılamaz → CONNECT_FAILED + status=error")
    void diagnose_connectFailure_connectFailed() {
        HstsDiagnosticsService svc = new HstsDiagnosticsService();
        ReflectionTestUtils.setField(svc, "timeoutSeconds", 2);

        Map<String, Object> r = svc.diagnose("localhost", 1);   // 1 numaralı port kapalı

        assertThat(r.get("status")).isEqualTo("error");
        assertThat(r.get("verdict")).isEqualTo("CONNECT_FAILED");
        assertThat(r.get("error")).isNotNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair kp) throws Exception {
        X500Name name = new X500Name("CN=localhost, O=SiteMonitor Test");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(System.nanoTime()),
                Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(2, ChronoUnit.DAYS)),
                name, kp.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
