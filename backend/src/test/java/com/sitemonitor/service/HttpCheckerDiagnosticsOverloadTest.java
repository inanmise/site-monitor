package com.sitemonitor.service;

import com.sitemonitor.service.http.HttpRequestOptions;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HttpCheckerService#checkForDiagnostics} — HTTP uçtan uca tanılamasının "izlemenin gerçek istemcisi" girişi
 * (2026-10-02). Sözleşme: istek/karar normal girişle AYNI yol; ama CA auto-pin YAPMAZ, güven hatasını
 * {@link CaAutoPinService}'e KAYDETMEZ (paralel sweep'in auto-pin'ine karışmasın) ve {@code http_version} döner.
 * Normal giriş aynı kurulumda pinler — test, farkı ölçtüğünü böyle kanıtlar.
 */
class HttpCheckerDiagnosticsOverloadTest {

    private static HttpsServer server;
    private static String url;

    @BeforeAll
    static void start() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X500Name name = new X500Name("CN=localhost, O=SiteMonitor Diag Overload");
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(System.nanoTime()),
                Date.from(now.minus(1, ChronoUnit.HOURS)), Date.from(now.plus(2, ChronoUnit.DAYS)), name, kp.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(b.build(signer));
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("server", kp.getPrivate(), new char[0], new X509Certificate[]{ cert });
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/", ex -> { ex.sendResponseHeaders(200, -1); ex.close(); });
        server.start();
        url = "https://localhost:" + server.getAddress().getPort() + "/";
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop(0);
    }

    private record Fixture(HttpCheckerService svc, CaAutoPinService pin) {}

    private static Fixture fixture() {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getString(eq(TrustEvaluator.CA_BUNDLE_KEY), anyString())).thenReturn("");
        when(settings.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(settings.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        CaAutoPinService pin = mock(CaAutoPinService.class);
        when(pin.isEnabled()).thenReturn(true);
        when(pin.pinFromServer(anyString(), anyInt(), anyString())).thenReturn(false);
        HttpCheckerService svc = new HttpCheckerService(new TrustEvaluator(settings), pin, new SsrfGuard(settings));
        svc.init();
        return new Fixture(svc, pin);
    }

    @Test
    @DisplayName("normal giriş (sweep/elle kontrol): strict PKIX hatasında güven hatası KAYDEDİLİR ve auto-pin DENENİR — davranış değişmedi")
    void normalCheck_stillAutoPins() {
        Fixture f = fixture();
        Map<String, Object> r = f.svc().check(url, "GET", "200-399", 3000, true, false);
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat(r).doesNotContainKey("http_version");
        verify(f.pin(), atLeastOnce()).recordTrustFailure(eq("http-check"), anyString(), anyInt());
        verify(f.pin(), atLeastOnce()).pinFromServer(anyString(), anyInt(), anyString());
    }

    @Test
    @DisplayName("tanılama girişi: aynı strict hata — auto-pin YOK, güven kaydı YOK, http_version anahtarı var")
    void diagnosticCheck_neverPins() {
        Fixture f = fixture();
        Map<String, Object> r = f.svc().checkForDiagnostics(url, "GET", "200-399", 3000, true, false, false, HttpRequestOptions.NONE);
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat((String) r.get("error")).containsIgnoringCase("certif");
        assertThat(r).containsKey("http_version").doesNotContainKey("repinned");
        verify(f.pin(), never()).pinFromServer(anyString(), anyInt(), anyString());
        verify(f.pin(), never()).recordTrustFailure(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("tanılama girişi, verify kapalı: yanıt + müzakere edilen HTTP sürümü")
    void diagnosticCheck_reportsHttpVersion() {
        Fixture f = fixture();
        Map<String, Object> r = f.svc().checkForDiagnostics(url, "GET", "200-399", 3000, false, false, false, null);
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r.get("http_status")).isEqualTo(200);
        assertThat(r.get("http_version")).isEqualTo("HTTP_1_1");
    }

    @Test
    @DisplayName("tanılama girişi: bozuk URL / SSRF engeli normal girişle aynı (istek atılmaz), http_version null")
    void diagnosticCheck_configAndSsrf() {
        Fixture f = fixture();
        Map<String, Object> bad = f.svc().checkForDiagnostics("not a url", "GET", null, 1000, false, true, false, null);
        assertThat(bad).containsEntry("ok", false).containsEntry("config_error", true).containsEntry("http_version", null);
        Map<String, Object> blocked = f.svc().checkForDiagnostics("http://169.254.169.254/", "GET", null, 1000, false, true, false, null);
        assertThat(blocked).containsEntry("ok", false).containsEntry("http_version", null);
        assertThat((String) blocked.get("error")).contains("izin verilmeyen hedef");
    }
}
