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
        // Tanı (2026-09-22): TLS evresi, güven hatası türü, hedef IP/port ve istisna zinciri JSON olarak taşınır
        assertThat((String) r.get("error_detail")).contains("\"kind\":\"TLS_CERT_UNTRUSTED\"").contains("\"phase\":\"TLS\"")
                .contains("\"target_ip\":\"127.0.0.1\"").contains("\"cause_chain\"");
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
        assertThat(HttpCheckerService.matchName("callcenterfacechat.example.com", "callcenterfacechat.example.com")).isTrue();
        assertThat(HttpCheckerService.matchName("*.example.com", "callcenterfacechat.example.com")).isTrue();
        assertThat(HttpCheckerService.matchName("*.example.com", "example.com")).isFalse();        // wildcard bir etiket ister
        assertThat(HttpCheckerService.matchName("*.example.com", "a.b.example.com")).isFalse();    // yalnız en soldaki tek etiket
        assertThat(HttpCheckerService.matchName("*.example.org", "callcenterfacechat.example.com")).isFalse();   // BASKA alan adi
        assertThat(HttpCheckerService.matchName("www.example.com", "example.com")).isFalse();
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

    // -- Yonlendirme guvenligi (her hop SsrfGuard'dan gecer) ------------------

    /** Verilen hedefe 302 ile yonlendiren duz HTTP sunucusu. */
    private static com.sun.net.httpserver.HttpServer plainRedirect(String location) throws Exception {
        com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location", location);
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        s.start();
        return s;
    }

    private static com.sun.net.httpserver.HttpServer plainOk() throws Exception {
        com.sun.net.httpserver.HttpServer s = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> { ex.sendResponseHeaders(200, -1); ex.close(); });
        s.start();
        return s;
    }

    @Test
    @DisplayName("Yonlendirme takip edilir - zincirin sonundaki 200 raporlanir (davranis korunur)")
    void followRedirects_chainStillFollowed() throws Exception {
        com.sun.net.httpserver.HttpServer target = plainOk();
        com.sun.net.httpserver.HttpServer entry =
                plainRedirect("http://127.0.0.1:" + target.getAddress().getPort() + "/");
        try {
            Fixture f = fixture("", false);
            Map<String, Object> r = f.service().check(
                    "http://127.0.0.1:" + entry.getAddress().getPort() + "/",
                    "GET", "200-399", 3000, false, true);
            assertThat(r.get("ok")).isEqualTo(true);
            assertThat(r.get("http_status")).isEqualTo(200);
        } finally {
            entry.stop(0);
            target.stop(0);
        }
    }

    @Test
    @DisplayName("SSRF: cloud-metadata ucuna YONLENDIRME engellenir (Redirect.NORMAL bunu kacirirdi)")
    void followRedirects_toMetadata_blocked() throws Exception {
        com.sun.net.httpserver.HttpServer entry = plainRedirect("http://169.254.169.254/latest/meta-data/");
        try {
            Fixture f = fixture("", false);
            Map<String, Object> r = f.service().check(
                    "http://127.0.0.1:" + entry.getAddress().getPort() + "/",
                    "GET", "200-399", 3000, false, true);
            assertThat(r.get("ok")).isEqualTo(false);
            assertThat((String) r.get("error")).contains("cloud-metadata");
            assertThat((String) r.get("error_detail")).contains("\"kind\":\"SSRF_BLOCKED\"").contains("\"phase\":\"POLICY\"")
                    .contains("\"redirects\":[\"http://169.254.169.254/latest/meta-data/\"]");
        } finally {
            entry.stop(0);
        }
    }

    @Test
    @DisplayName("followRedirects=false ise hic hop yapilmaz - 302 oldugu gibi raporlanir")
    void followRedirectsOff_reportsRedirectStatus() throws Exception {
        com.sun.net.httpserver.HttpServer entry = plainRedirect("http://169.254.169.254/");
        try {
            Fixture f = fixture("", false);
            Map<String, Object> r = f.service().check(
                    "http://127.0.0.1:" + entry.getAddress().getPort() + "/",
                    "GET", "200-399", 3000, false, false);
            assertThat(r.get("http_status")).isEqualTo(302);
            assertThat(r.get("ok")).isEqualTo(true);   // 200-399 kaliba uyuyor
        } finally {
            entry.stop(0);
        }
    }

    @Test
    @DisplayName("Tanı: çözülemeyen host → DNS evresi, DNS_UNRESOLVED; başarılı kontrolde error_detail YOK")
    void diagnostics_dnsAndSuccess() throws Exception {
        Fixture f = fixture("", false);
        Map<String, Object> r = f.service().check("https://" + TestHosts.UNRESOLVABLE + "/", "GET", "200-399", 3000, false, false);
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat((String) r.get("error_detail")).contains("\"kind\":\"DNS_UNRESOLVED\"").contains("\"phase\":\"DNS\"")
                .contains("\"host\":\"" + TestHosts.UNRESOLVABLE + "\"").contains("\"port\":443").contains("\"timeout_ms\":3000");
        Map<String, Object> ok = check(f, false);
        assertThat(ok.get("ok")).isEqualTo(true);
        assertThat(ok).doesNotContainKey("error_detail");
    }
}
