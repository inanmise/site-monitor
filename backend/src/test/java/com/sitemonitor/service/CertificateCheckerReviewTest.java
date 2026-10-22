package com.sitemonitor.service;

import com.sitemonitor.model.AlertThreshold;
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
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Sertifika kontrolü inceleme bulguları (2026-10-22):
 * <ul>
 *   <li>CHECKS-2: DNS'te çözülmeyen alan adı {@code BLOCKED} değil {@code DNS} sınıfıdır → alarm seviyesi UYARI (müdüre
 *       KRİTİK değil) ve toplu ağ kesintisi tespiti onu sayar. Gerçek {@link SsrfGuard} (eski test onu mock'luyordu).</li>
 *   <li>CHECKS-10: 443 dışı portta kontrol edilen sertifikanın {@code http_status}'ü O portun yanıtıdır.</li>
 * </ul>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CertificateCheckerReviewTest {

    private static CertificateCheckerService checker(SsrfGuard guard) {
        ChainValidationService chain = mock(ChainValidationService.class);
        Map<String, Object> chainInfo = new LinkedHashMap<>();
        chainInfo.put("chain_status", "VALID");
        chainInfo.put("chain", List.of());
        when(chain.analyzeChain(any())).thenReturn(chainInfo);
        CertificateCheckerService s = new CertificateCheckerService(chain, mock(DnsCheckerService.class), new ObjectMapper(),
                mock(TrustEvaluator.class), guard, new ProxySettings());
        ReflectionTestUtils.setField(s, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(s, "tlsMode", "default");
        ReflectionTestUtils.setField(s, "warningDays", 30);
        return s;
    }

    private static SsrfGuard realGuard(boolean allowLoopback) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(eq("site.monitor.monitoring.allow-internal-targets"), anyBoolean())).thenReturn(true);
        when(settings.getBoolean(eq("site.monitor.monitoring.allow-loopback-targets"), anyBoolean())).thenReturn(allowLoopback);
        return new SsrfGuard(settings);
    }

    // ── CHECKS-2 ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CHECKS-2: çözülmeyen alan adı (gerçek SsrfGuard, .invalid) → error_class DNS, aşama dns — BLOCKED DEĞİL")
    void unresolvableDomain_isDnsClass() {
        Map<String, Object> r = checker(realGuard(false)).check("bf4-no-such-host-" + System.nanoTime() + ".invalid", 443);

        assertThat(r.get("status")).isEqualTo("error");
        assertThat(r.get("error_class")).isEqualTo("DNS");
        assertThat(r.get("error_stage")).isEqualTo("dns");
        assertThat(r.get("error")).isEqualTo("Domain resolution failed");
    }

    @Test
    @DisplayName("CHECKS-2: gerçek politika reddi (loopback kapalı) BLOCKED kalır")
    void policyBlock_staysBlocked() {
        Map<String, Object> r = checker(realGuard(false)).check("127.0.0.1", 443);
        assertThat(r.get("error_class")).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("CHECKS-2: DNS sınıflı hata sonucu → alarm seviyesi UYARI (BLOCKED → KRİTİK idi)")
    void dnsErrorResult_alertLevelIsWarning() {
        EscalationService esc = mock(EscalationService.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        AlertThreshold t = ThresholdResolution.defaults();
        Map<String, Object> dns = new LinkedHashMap<>(Map.of("status", "error", "error_class", "DNS"));
        Map<String, Object> blocked = new LinkedHashMap<>(Map.of("status", "error", "error_class", "BLOCKED"));

        assertThat((String) ReflectionTestUtils.invokeMethod(esc, "determineAlertLevel", dns, "EXPIRY", t)).isEqualTo("WARNING");
        assertThat((String) ReflectionTestUtils.invokeMethod(esc, "determineAlertLevel", blocked, "EXPIRY", t)).isEqualTo("CRITICAL");
    }

    // ── CHECKS-10 ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CHECKS-10: 8443 gibi 443 dışı portta http_status O PORTUN HEAD yanıtıdır (443'ün değil)")
    void nonDefaultPort_httpStatusFromThatPort() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        X509Certificate cert = selfSigned(kp);
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("s", kp.getPrivate(), new char[0], new X509Certificate[]{ cert });
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        AtomicInteger heads = new AtomicInteger();
        server.createContext("/", ex -> {
            if ("HEAD".equals(ex.getRequestMethod())) heads.incrementAndGet();
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            SsrfGuard guard = mock(SsrfGuard.class);   // localhost; politika SsrfGuardTest'te
            when(guard.validate(anyString())).thenReturn(List.of());
            Map<String, Object> r = checker(guard).check("localhost", port);

            assertThat(r.get("status")).as("error=%s", r.get("error")).isNotEqualTo("error");
            assertThat(r.get("http_status")).isEqualTo(204);
            assertThat(heads.get()).isGreaterThanOrEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("isDefaultHttpsPort: 443 ve tanımsız (≤ 0) varsayılan; diğerleri değil")
    void defaultPortRule() {
        assertThat(CertificateCheckerService.isDefaultHttpsPort(443)).isTrue();
        assertThat(CertificateCheckerService.isDefaultHttpsPort(0)).isTrue();
        assertThat(CertificateCheckerService.isDefaultHttpsPort(8443)).isFalse();
    }

    private static X509Certificate selfSigned(KeyPair kp) throws Exception {
        X500Name name = new X500Name("CN=localhost");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(System.nanoTime()),
                Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(40, ChronoUnit.DAYS)), name, kp.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(b.build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.getPrivate())));
    }
}
