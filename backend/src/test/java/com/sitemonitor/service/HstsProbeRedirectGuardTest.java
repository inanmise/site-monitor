package com.sitemonitor.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Sertifika kontrolündeki HSTS probe'unun yönlendirme kapısı.
 *
 * <p><b>Kapatılan açık.</b> Probe {@code setInstanceFollowRedirects(true)} ile JDK'nın KÖR zincir
 * takibini kullanıyordu; {@code ssrfGuard.validate} yalnız İLK host için çalışıyordu. İzlenen
 * sunucu {@code 302 Location: http://169.254.169.254/…} derse ara hop hiçbir kapıdan geçmiyordu
 * ve isabet kullanıcının gördüğü sonuca yansıyordu — iç servisler için durum-kodu oraklı.
 *
 * <p>Testler yerel bir HTTP sunucusuna karşı uçtan uca koşar; kapı mock'lanır (gerçek guard
 * loopback'i bloklardı, kendisi {@code SsrfGuardTest}'te doğrulanır).
 */
class HstsProbeRedirectGuardTest {

    private HttpServer server;
    private SsrfGuard guard;
    private CertificateCheckerService service;
    private final AtomicInteger nextHits = new AtomicInteger();

    @BeforeEach
    void setUp() {
        guard = mock(SsrfGuard.class);
        service = new CertificateCheckerService(
                mock(ChainValidationService.class), mock(DnsCheckerService.class), new ObjectMapper(),
                mock(TrustEvaluator.class), guard, new ProxySettings());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    /**
     * /start → 302 (Location verilen üreticiden), /next → 200 + Strict-Transport-Security,
     * /loop → kendine 302. Döner: taban URL.
     */
    private String startServer(java.util.function.Function<Integer, String> location) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/start", ex -> {
            ex.getResponseHeaders().add("Location", location.apply(port));
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/next", ex -> {
            nextHits.incrementAndGet();
            ex.getResponseHeaders().add("Strict-Transport-Security", "max-age=31536000");
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/loop", ex -> {
            ex.getResponseHeaders().add("Location", "/loop");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        return "http://localhost:" + port;
    }

    @Test
    @DisplayName("Yönlendirme takip edilir ve HER hop kapıdan geçer")
    void follow_relativeRedirect_guardsEveryHop() throws Exception {
        String base = startServer(p -> "/next");

        HttpURLConnection hc = service.openHstsFollowingSafely(base + "/start", false, null);

        assertThat(hc.getHeaderField("Strict-Transport-Security")).isNotNull();
        verify(guard, times(2)).validate("localhost");   // /start + /next
    }

    @Test
    @DisplayName("Bloklu ARA hop'a hiç bağlanılmaz — sertifika sunucusu iç adrese sıçratamaz")
    void follow_blockedSecondHop_neverConnects() throws Exception {
        // İlk hop localhost (izinli), yönlendirme 127.0.0.1'e (bloklu) gidiyor: kör takipte bu
        // istek atılıyordu, kapıdan geçen ilk host'un izni ikinci hedefe taşınıyordu.
        String base = startServer(p -> "http://127.0.0.1:" + p + "/next");
        doThrow(new SsrfGuard.BlockedException("iç adres")).when(guard).validate("127.0.0.1");

        assertThatThrownBy(() -> service.openHstsFollowingSafely(base + "/start", false, null))
                .isInstanceOf(SsrfGuard.BlockedException.class);

        assertThat(nextHits.get()).as("bloklu hop'a istek gitmemeli").isZero();
    }

    @Test
    @DisplayName("Yönlendirme döngüsü hop tavanında durur (sonsuz zincir yok)")
    void follow_redirectLoop_stopsAtMaxHops() throws Exception {
        String base = startServer(p -> "/loop");

        assertThatThrownBy(() -> service.openHstsFollowingSafely(base + "/loop", false, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("çok fazla yönlendirme");
        verify(guard, times(SafeRedirect.MAX_HOPS)).validate("localhost");
    }

    @Test
    @DisplayName("Tek hop bağlantısında JDK'nın kör takibi KAPALI kurulur")
    void openHstsConnection_followDisabled() throws Exception {
        HttpURLConnection hc = service.openHstsConnection(
                URI.create("http://example.com/"), false, null, "HEAD");
        assertThat(hc.getInstanceFollowRedirects()).isFalse();
        assertThat(hc.getRequestMethod()).isEqualTo("HEAD");
    }

    @Test
    @DisplayName("Host'suz hedef bağlanmadan reddedilir")
    void guardHstsHost_blankHost_blocked() {
        assertThatThrownBy(() -> service.guardHstsHost(null))
                .isInstanceOf(SsrfGuard.BlockedException.class);
        verify(guard, times(0)).validate(anyString());
    }

    @Test
    @DisplayName("Çözülemeyen host DURDURMAZ — vekil arkasında split-DNS meşrudur")
    void guardHstsHost_unresolvable_tolerated() {
        doThrow(new SsrfGuard.UnresolvableHostException("çözülemedi"))
                .when(guard).validate("intranet.example.com");

        service.guardHstsHost("intranet.example.com");   // fırlatmamalı
        verify(guard).validate("intranet.example.com");
    }

    // ── TOPLAM süre (2026-10-08, "zaman aşımı olmayan servis çağrısı" denetimi) ─────────────────────────────────

    @Test
    @DisplayName("KAPI (2026-10-08): başlıkları damlatan sunucu HSTS HEAD'ini süresiz tutamaz — hop bütçesinde kesilir")
    void hstsHead_headerDrip_abortedWithinHopBudget() throws Exception {
        service.hstsHopTotalMs = 1_000L;   // okuma zaman aşımı 4 sn (sabit) — bütçe ondan KISA
        try (com.sitemonitor.util.DripServer drip = com.sitemonitor.util.DripServer.start(
                true, "HTTP/1.1 200 OK\r\nX-Pad: ", 100)) {
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(15),
                    () -> service.openHstsFollowingSafely("http://127.0.0.1:" + drip.port() + "/", false, null)))
                    .isInstanceOf(com.sitemonitor.util.HttpBodies.BodyDeadlineException.class)
                    .hasMessageContaining("HSTS");
            assertThat((System.nanoTime() - t0) / 1_000_000L)
                    .as("bütçe 1 sn; bekçisiz en erken 4 sn (okuma zaman aşımı), damla ulaşırsa ~60 sn").isLessThan(3_500L);
        }
    }

    @Test
    @DisplayName("2026-10-08: normal HEAD bütçeden etkilenmez — durum kodu ve başlıklar aynen okunur")
    void hstsHead_normalResponse_unchanged() throws Exception {
        String base = startServer(p -> "/next");
        HttpURLConnection hc = service.openHstsFollowingSafely(base + "/next", false, null);
        assertThat(hc.getResponseCode()).isEqualTo(200);
        assertThat(hc.getHeaderField("Strict-Transport-Security")).isEqualTo("max-age=31536000");
        assertThat(CertificateCheckerService.HSTS_HOP_TOTAL_MS).isEqualTo(15_000L);
    }
}
