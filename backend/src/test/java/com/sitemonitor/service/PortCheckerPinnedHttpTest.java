package com.sitemonitor.service;

import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CHECKS-5 (2026-10-22): Port izleme, tür HTTP + IP sürümü v4/v6 seçiliyken URL'yi IP sabitiyle kurup adı
 * {@code setRequestProperty("Host")} ile geri vermeye çalışıyordu — {@code HttpURLConnection} bu başlığı sessizce yok sayar,
 * HTTPS'te IP sabiti SNI de göndermez → istek varsayılan sanal sunucuya düşer, yanlış PORT_DOWN. Sunucular yalnız DOĞRU
 * {@code Host} / SNI'ye 200 döner; aksi hâlde 404 / 421 (eski davranışta test kırmızı).
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PortCheckerPinnedHttpTest {

    private static PortCheckerService serviceResolving(String host, InetAddress to) {
        SsrfGuard guard = mock(SsrfGuard.class);
        when(guard.validate(anyString())).thenAnswer(inv -> {
            if (!host.equals(inv.getArgument(0))) throw new SsrfGuard.UnresolvableHostException("çözümlenemeyen host: " + inv.getArgument(0));
            return List.of(to);
        });
        return new PortCheckerService(guard);
    }

    @Test
    @DisplayName("HTTP + IPv4: sabit adrese bağlanılır ama Host başlığı host ADIDIR → yalnız vhost'a 200 veren sunucuda open=true")
    void http_ipv4_keepsHostHeader() throws Exception {
        AtomicReference<String> seenHost = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/", ex -> {
            String h = ex.getRequestHeaders().getFirst("Host");
            seenHost.set(h);
            ex.sendResponseHeaders(("vhost.test:" + port).equals(h) ? 200 : 404, -1);
            ex.close();
        });
        server.start();
        try {
            Map<String, Object> r = serviceResolving("vhost.test", InetAddress.getLoopbackAddress())
                    .check("vhost.test", port, 3_000, "HTTP", "/health", null, "v4", false);

            assertThat(seenHost.get()).isEqualTo("vhost.test:" + port);
            assertThat(r.get("open")).as("error=%s", r.get("error")).isEqualTo(true);
            assertThat(r.get("detail")).isEqualTo("HTTP 200");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("HTTP + IPv4: beklenmeyen kod yine kapalı sonuç + neden (davranış korunur)")
    void http_ipv4_statusMismatch_stillDown() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/", ex -> { ex.sendResponseHeaders(503, -1); ex.close(); });
        server.start();
        try {
            Map<String, Object> r = serviceResolving("vhost.test", InetAddress.getLoopbackAddress())
                    .check("vhost.test", port, 3_000, "HTTP", "/", "200", "v4", false);
            assertThat(r.get("open")).isEqualTo(false);
            assertThat((String) r.get("error")).isEqualTo("HTTP 503 (beklenen: 200)");
            assertThat(r.get("response_ms")).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("HTTPS (8443) + IPv4: TLS el sıkışması SNI = host ADIYLA ve Host başlığıyla → open=true")
    void https_ipv4_sendsSniAndHost() throws Exception {
        SSLServerSocket ss = bind8443();
        Assumptions.assumeTrue(ss != null, "8443 yerelde kullanılamıyor — test atlandı");
        AtomicReference<String> sni = new AtomicReference<>();
        AtomicReference<String> host = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try (SSLSocket s = (SSLSocket) ss.accept()) {
                s.setSoTimeout(5_000);
                s.startHandshake();
                for (SNIServerName n : ((ExtendedSSLSession) s.getSession()).getRequestedServerNames()) {
                    if (n instanceof SNIHostName h) sni.set(h.getAsciiName());
                }
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.regionMatches(true, 0, "Host:", 0, 5)) host.set(line.substring(5).trim());
                }
                boolean right = "vhost.test".equals(sni.get()) && "vhost.test:8443".equals(host.get());
                OutputStream out = s.getOutputStream();
                out.write((right ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 421 Misdirected Request\r\n")
                        .concat("Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (Exception ignore) { /* test sonucu iddialarda */ }
        }, "sni-8443");
        t.setDaemon(true);
        t.start();
        try {
            Map<String, Object> r = serviceResolving("vhost.test", InetAddress.getLoopbackAddress())
                    .check("vhost.test", 8443, 5_000, "HTTP", "/", null, "v4", false);
            t.join(10_000);
            assertThat(sni.get()).isEqualTo("vhost.test");
            assertThat(host.get()).isEqualTo("vhost.test:8443");
            assertThat(r.get("open")).as("error=%s", r.get("error")).isEqualTo(true);
        } finally {
            ss.close();
        }
    }

    @Test
    @DisplayName("Sabit adresli yol da TOPLAM süreyle sınırlı — durum satırını damlatan hedef bütçede READ_TIMEOUT")
    void pinned_headerDrip_boundedByTotalBudget() throws Exception {
        SsrfGuard guard = mock(SsrfGuard.class);
        when(guard.validate(anyString())).thenReturn(List.of(InetAddress.getLoopbackAddress()));
        PortCheckerService svc = new PortCheckerService(guard) {
            @Override long httpTotalMs(int timeoutMs) { return 1_000L; }
        };
        // Durum satırı yarım ve hiç bitmiyor; okuma-başı zaman aşımı (5 sn) bütçeden (1 sn) BÜYÜK → yalnız bekçi keser
        try (com.sitemonitor.util.DripServer drip = com.sitemonitor.util.DripServer.start(true, "HTTP/1.1 2", 0)) {
            long t0 = System.nanoTime();
            Map<String, Object> r = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(15),
                    () -> svc.check("vhost.test", drip.port(), 5_000, "HTTP", "/", null, "v4", false));
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertThat(r.get("open")).isEqualTo(false);
            assertThat((String) r.get("error")).contains("Port HTTP");
            assertThat(r.get("failure_reason")).isEqualTo("READ_TIMEOUT");
            assertThat(ms).as("bütçe 1 sn; bekçisiz en erken 5 sn").isLessThan(3_500L);
        }
    }

    @Test
    @DisplayName("hostHeader: varsayılan portta ad, diğerinde ad:port; IPv6 sabiti köşeli parantezli")
    void hostHeaderRules() {
        assertThat(PortCheckerService.hostHeader("a.test", 80)).isEqualTo("a.test");
        assertThat(PortCheckerService.hostHeader("a.test", 443)).isEqualTo("a.test");
        assertThat(PortCheckerService.hostHeader("a.test", 8080)).isEqualTo("a.test:8080");
        assertThat(PortCheckerService.hostHeader("::1", 8080)).isEqualTo("[::1]:8080");
        assertThat(PortCheckerService.hostHeader("[::1]", 80)).isEqualTo("[::1]");
    }

    private static SSLServerSocket bind8443() {
        try {
            Security.addProvider(new BouncyCastleProvider());
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair kp = g.generateKeyPair();
            X500Name dn = new X500Name("CN=vhost.test");
            Instant now = Instant.now();
            X509Certificate cert = new JcaX509CertificateConverter().setProvider("BC").getCertificate(
                    new JcaX509v3CertificateBuilder(dn, BigInteger.ONE, Date.from(now.minus(1, ChronoUnit.HOURS)),
                            Date.from(now.plus(1, ChronoUnit.DAYS)), dn, kp.getPublic())
                            .build(new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC").build(kp.getPrivate())));
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setKeyEntry("s", kp.getPrivate(), new char[0], new X509Certificate[]{ cert });
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, new char[0]);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
            SSLServerSocket ss = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket();
            ss.setReuseAddress(false);
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 8443));
            ss.setSoTimeout(15_000);
            return ss;
        } catch (Exception e) {
            return null;
        }
    }
}
