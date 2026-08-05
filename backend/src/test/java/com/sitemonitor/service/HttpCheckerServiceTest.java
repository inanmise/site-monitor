package com.sitemonitor.service;

import com.sitemonitor.model.PinnedCa;
import com.sitemonitor.repository.PinnedCaRepository;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HttpCheckerService — verifySsl=true strict yolu: kurumsal CA paketi
 * ({@code site.monitor.trust.ca-bundle-pem}) VE CA otomatik sabitleme (auto-pin / TOFU,
 * {@link CaAutoPinService}). Self-signed lokal HTTPS hedefe karşı: paket boş + auto-pin kapalı →
 * down; sertifika pakete eklenince → up; auto-pin açıkken ilk kontrol pinleyip retry ile up
 * ({@code repinned=true}); rotasyonda eski pin yenisiyle değiştirilir. Sertifikalar runtime'da
 * üretilir (BouncyCastle, SAN=localhost), zaman-bombası fixture yok.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HttpCheckerServiceTest {

    private static HttpsServer server;
    private static String url;
    private static X509Certificate serverCert;
    private static String serverCertPem;
    /** Rotasyon senaryosu için: sunucununkinden FARKLI, eski-pin rolündeki sertifika. */
    private static X509Certificate staleCert;
    private static String staleCertPem;

    @BeforeAll
    static void startSelfSignedHttpsServer() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPair kp = generateKeyPair();
        serverCert = selfSigned(kp, "CN=localhost, O=SiteMonitor Test");
        serverCertPem = toPem(serverCert);
        KeyPair staleKp = generateKeyPair();
        staleCert = selfSigned(staleKp, "CN=localhost, O=SiteMonitor Test Old");
        staleCertPem = toPem(staleCert);

        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("server", kp.getPrivate(), new char[0], new X509Certificate[]{ serverCert });
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

    /** Test başına izole kurulum: gerçek TrustEvaluator + gerçek CaAutoPinService, mock kenarlar. */
    private record Fixture(HttpCheckerService service, PinnedCaRepository repo,
                           AuditService audit, Map<String, PinnedCa> store) {}

    private static Fixture fixture(String bundlePem, boolean autoPinEnabled) throws Exception {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn(bundlePem);
        when(settings.getBoolean(eq(CaAutoPinService.ENABLED_KEY), anyBoolean())).thenReturn(autoPinEnabled);

        CertificateCheckerService certChecker = mock(CertificateCheckerService.class);
        when(certChecker.captureDirectChain(anyString(), anyInt(), anyInt()))
                .thenReturn(new X509Certificate[]{ serverCert });

        Map<String, PinnedCa> store = new HashMap<>();
        PinnedCaRepository repo = mock(PinnedCaRepository.class);
        when(repo.findByHostAndPort(anyString(), anyInt())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + ":" + inv.getArgument(1))));
        when(repo.save(any(PinnedCa.class))).thenAnswer(inv -> {
            PinnedCa p = inv.getArgument(0);
            store.put(p.getHost() + ":" + p.getPort(), p);
            return p;
        });

        AuditService audit = mock(AuditService.class);
        CaAutoPinService pinService = new CaAutoPinService(certChecker, repo, settings, audit);
        // Test 127.0.0.1'e bağlanır → SsrfGuard'ı izin verici kur (loopback + iç ağ). Metadata/link-local yine bloklu.
        when(settings.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(settings.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        HttpCheckerService svc = new HttpCheckerService(new TrustEvaluator(settings), pinService, new SsrfGuard(settings));
        svc.init();
        return new Fixture(svc, repo, audit, store);
    }

    private static Map<String, Object> check(Fixture f, boolean verifySsl) {
        return f.service().check(url, "GET", "200-399", 5000, verifySsl, false);
    }

    @Test
    @DisplayName("verifySsl=true + CA paketi BOŞ + auto-pin KAPALI → self-signed hedef down (PKIX)")
    void strict_noBundle_autoPinOff_fails() throws Exception {
        Fixture f = fixture("", false);
        Map<String, Object> r = check(f, true);

        assertThat(r.get("ok")).isEqualTo(false);
        assertThat((String) r.get("error")).containsIgnoringCase("certif");
        assertThat(r).doesNotContainKey("repinned");
        verify(f.repo(), never()).save(any());
    }

    @Test
    @DisplayName("verifySsl=true + sertifika CA paketinde → up (kurumsal bundle strict yola bağlı, pin gerekmez)")
    void strict_bundleWithCert_succeeds() throws Exception {
        Fixture f = fixture(serverCertPem, true);
        Map<String, Object> r = check(f, true);

        assertThat(r.get("error")).isNull();
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r.get("http_status")).isEqualTo(200);
        assertThat(r).doesNotContainKey("repinned");
        verify(f.repo(), never()).save(any());
    }

    @Test
    @DisplayName("verifySsl=false → CA paketi olmadan da up (trust-all davranışı korunur)")
    void trustAll_noBundle_succeeds() throws Exception {
        Fixture f = fixture("", false);
        Map<String, Object> r = check(f, false);

        assertThat(r.get("error")).isNull();
        assertThat(r.get("ok")).isEqualTo(true);
    }

    @Test
    @DisplayName("auto-pin AÇIK + paket boş → ilk kontrol CA'yı pinler, retry ile up (repinned=true, CA_PINNED)")
    void autoPin_firstCheck_pinsAndSucceeds() throws Exception {
        Fixture f = fixture("", true);
        Map<String, Object> r = check(f, true);

        assertThat(r.get("error")).isNull();
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r.get("repinned")).isEqualTo(true);
        assertThat(f.store()).hasSize(1);
        PinnedCa pin = f.store().values().iterator().next();
        assertThat(pin.getHost()).isEqualTo("localhost");
        assertThat(pin.getPem()).contains("BEGIN CERTIFICATE");
        verify(f.audit()).recordAction(eq("CA_PINNED"), eq("system"), any(), any(), any(),
                eq("pinned_ca"), anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("rotasyon: eski sertifika pinliyken sunucu yenisini sunar → otomatik re-pin ile up (CA_ROTATED)")
    void autoPin_rotation_repinsAndSucceeds() throws Exception {
        Fixture f = fixture("", true);
        PinnedCa old = new PinnedCa();
        old.setHost("localhost");
        old.setPort(server.getAddress().getPort());
        old.setPem(staleCertPem);
        old.setFingerprintSha256("stale-fingerprint");
        old.setNotAfter("2020-01-01T00:00:00");
        old.setPinnedAt("2020-01-01T00:00:00");
        f.store().put("localhost:" + server.getAddress().getPort(), old);

        Map<String, Object> r = check(f, true);

        assertThat(r.get("error")).isNull();
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r.get("repinned")).isEqualTo(true);
        assertThat(f.store().values().iterator().next().getFingerprintSha256())
                .isNotEqualTo("stale-fingerprint");
        verify(f.audit()).recordAction(eq("CA_ROTATED"), eq("system"), any(), any(), any(),
                eq("pinned_ca"), anyString(), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("hostname mismatch (SAN=localhost, hedef 127.0.0.1) → pin edilse bile DOWN kalır (hostname doğrulaması korunur)")
    void hostnameMismatch_staysDownEvenAfterPin() throws Exception {
        Fixture f = fixture("", true);
        String ipUrl = "https://127.0.0.1:" + server.getAddress().getPort() + "/";
        Map<String, Object> r = f.service().check(ipUrl, "GET", "200-399", 5000, true, false);

        // Zincir PKIX'te düştüğü için pin denenir; ama retry'da pin zinciri güvense de hostname
        // doğrulaması (SAN=localhost ≠ 127.0.0.1) el sıkışmayı reddeder — JSSE tuzağı guard'ı.
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat((String) r.get("error")).isNotBlank();
    }

    @Test
    @DisplayName("matchName: tam eşleşme + tek-etiket wildcard (çok-A pin sonrası hostname doğrulaması)")
    void matchName_exactAndWildcard() {
        assertThat(HttpCheckerService.matchName("callcenterfacechat.akbank.com", "callcenterfacechat.akbank.com")).isTrue();
        assertThat(HttpCheckerService.matchName("*.akbank.com", "callcenterfacechat.akbank.com")).isTrue();
        assertThat(HttpCheckerService.matchName("*.akbank.com", "akbank.com")).isFalse();        // wildcard bir etiket ister
        assertThat(HttpCheckerService.matchName("*.akbank.com", "a.b.akbank.com")).isFalse();    // yalnız en soldaki tek etiket
        assertThat(HttpCheckerService.matchName("*.example.com", "callcenterfacechat.akbank.com")).isFalse();
        assertThat(HttpCheckerService.matchName("www.akbank.com", "akbank.com")).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair kp, String dn) throws Exception {
        X500Name name = new X500Name(dn);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(System.nanoTime()),
                Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(2, ChronoUnit.DAYS)),
                name, kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(kp.getPrivate());
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
    }

    private static String toPem(X509Certificate cert) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }
}
