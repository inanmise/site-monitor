package com.sitemonitor.service;

import tools.jackson.databind.ObjectMapper;
import com.sitemonitor.service.manualcert.TestCerts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.net.InetAddress;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;

import static com.sitemonitor.service.manualcert.TestCerts.days;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * Hiyerarşi görünümünün ham zincir okuması (2026-10-12): {@code capturePresentedChain} gerçek bir yerel TLS sunucusundan
 * sunulan zinciri (yaprak + ara, sırayla) döner; doğrudan yol; iki TLS kipi; SSRF engeli yayılır (bağlanılmaz);
 * otomatik proxy geri düşüşü bayrağı okunur. Sertifikalar testte üretilir.
 */
class CertificateCheckerCaptureChainTest {

    private SSLServerSocket server;
    private Thread acceptor;

    @AfterEach
    void stop() throws Exception {
        if (server != null) server.close();
        if (acceptor != null) acceptor.join(2000);
    }

    private CertificateCheckerService service(SsrfGuard guard) {
        CertificateCheckerService s = new CertificateCheckerService(mock(ChainValidationService.class),
                mock(DnsCheckerService.class), new ObjectMapper(), mock(TrustEvaluator.class), guard, new ProxySettings());
        ReflectionTestUtils.setField(s, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(s, "tlsMode", "default");
        return s;
    }

    /** Yaprak + ara zinciri sunan, gelen her bağlantıda el sıkışmayı tamamlayan yerel sunucu; port döner. */
    private int startServer(TestCerts.Chain chain) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("server", chain.leafKey().getPrivate(), new char[0],
                new X509Certificate[]{ chain.leaf(), chain.inter() });
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        server = (SSLServerSocket) ctx.getServerSocketFactory().createServerSocket(0, 5, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (SSLSocket s = (SSLSocket) server.accept()) {
                    s.setSoTimeout(5000);
                    s.startHandshake();
                } catch (Exception ignored) {
                    // kapanış ya da istemcinin kopması — bir sonraki bağlantıya geç
                }
            }
        }, "capture-chain-test-server");
        acceptor.setDaemon(true);
        acceptor.start();
        return server.getLocalPort();
    }

    @Test
    @DisplayName("sunulan zincir sırasıyla (yaprak, ara) döner; doğrudan yol; default ve browser kipleri")
    void capturesPresentedChain() throws Exception {
        TestCerts.Chain chain = TestCerts.chain("capture.example.test", days(60));
        int port = startServer(chain);
        CertificateCheckerService svc = service(mock(SsrfGuard.class));
        for (String mode : List.of("default", "browser")) {
            CertificateCheckerService.PresentedChain p = assertTimeoutPreemptively(Duration.ofSeconds(20),
                    () -> svc.capturePresentedChain(InetAddress.getLoopbackAddress().getHostAddress(), port, false, mode, 5));
            assertThat(p.viaProxy()).isFalse();
            assertThat(p.certificates()).hasSize(2);
            assertThat(p.certificates()[0]).isEqualTo(chain.leaf());
            assertThat(p.certificates()[1]).isEqualTo(chain.inter());
        }
    }

    @Test
    @DisplayName("SSRF engeli yayılır — bağlantı kurulmaz")
    void ssrfBlockPropagates() {
        SsrfGuard guard = mock(SsrfGuard.class);
        doThrow(new SsrfGuard.BlockedException("engelli")).when(guard).validate(anyString());
        CertificateCheckerService svc = service(guard);
        assertThatThrownBy(() -> svc.capturePresentedChain("blocked.example.test", 443, false, null, 5))
                .isInstanceOf(SsrfGuard.BlockedException.class);
    }

    @Test
    @DisplayName("otomatik proxy geri düşüşü bayrağı okunur (hiyerarşi ucunun yol ipucu kuralı)")
    void autoProxyFallbackFlag() {
        CertificateCheckerService svc = service(mock(SsrfGuard.class));
        ReflectionTestUtils.setField(svc, "autoProxyFallback", false);
        assertThat(svc.autoProxyFallbackEnabled()).isFalse();
        ReflectionTestUtils.setField(svc, "autoProxyFallback", true);
        assertThat(svc.autoProxyFallbackEnabled()).isTrue();
    }
}
