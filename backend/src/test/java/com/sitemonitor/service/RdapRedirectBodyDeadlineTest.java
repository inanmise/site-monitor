package com.sitemonitor.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * KAPI (2026-10-08, "zaman aşımı olmayan servis çağrısı" denetimi): RDAP istemcileri yönlendirme (3xx) gövdesini
 * çıplak {@code readNBytes(4096)} ile okuyordu. java.net.http zaman aşımı yalnız başlıklara kadar işler: gövdesini
 * bayt bayt damlatan bir 3xx yanıtı lookup'ı (ve sıralı alan adı süpürmesini) süresiz tutuyordu. Artık gövde isteğin
 * kendi zaman aşımı kadar okunup bırakılır ve zincir eskisi gibi sonraki hop'a geçer — sonuç DEĞİŞMEZ.
 *
 * <p>Yerel sunucu: {@code /example.com} → 302 {@code Location: /final} + saniyede 10 bayt damlayan (bitmeyen) gövde;
 * {@code /final} → RDAP JSON. Eski kod 4096 bayt için ~60 sn (damla bitene dek) beklerdi.
 */
class RdapRedirectBodyDeadlineTest {

    private static final String RDAP_JSON = """
        { "objectClassName":"domain", "ldhName":"example.com",
          "events":[{"eventAction":"expiration","eventDate":"2031-08-13T04:00:00Z"},
                    {"eventAction":"registration","eventDate":"1995-08-14T04:00:00Z"}],
          "status":["client transfer prohibited"] }
        """;

    private HttpServer server;
    private ExecutorService pool;
    private String base;
    private AppSettingsService appSettings;

    private static SsrfGuard permissiveGuard() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        return new SsrfGuard(s);
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "rdap-test"); t.setDaemon(true); return t; });
        server.setExecutor(pool);   // damlatan işleyici diğer istekleri bekletmesin
        server.createContext("/", ex -> {          // bootstrap: "services" yok → fallback yolu
            byte[] b = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.createContext("/example.com", ex -> {
            ex.getResponseHeaders().add("Location", "/final");
            ex.sendResponseHeaders(302, 0);         // chunked, gövde hiç bitmez
            try (OutputStream os = ex.getResponseBody()) {
                for (int i = 0; i < 600; i++) { os.write('a'); os.flush(); Thread.sleep(100); }
            } catch (Exception ignored) {
                // istemci akışı bıraktı — beklenen
            }
        });
        server.createContext("/final", ex -> {
            byte[] b = RDAP_JSON.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/rdap+json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        appSettings = mock(AppSettingsService.class);
        when(appSettings.getString(anyString(), anyString())).thenReturn(base);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    @Test
    @DisplayName("RdapDomainClient: damlatan 3xx gövdesi istek bütçesinde bırakılır, zincir sonraki hop'tan sonucu alır")
    void rdapClient_drippingRedirectBody_boundedAndResultUnchanged() {
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        RdapDomainClient client = new RdapDomainClient(appSettings, psl, new TrustEvaluator(appSettings),
                mock(CaAutoPinService.class), permissiveGuard());
        client.init();

        long t0 = System.nanoTime();
        Map<String, Object> r = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> client.lookup("example.com", 1_000));
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertThat(r.get("error")).isNull();
        assertThat(r.get("expiry_date")).isEqualTo("2031-08-13T04:00:00Z");
        assertThat(ms).as("istek zaman aşımı 1 sn → 3xx gövdesi en çok ~1 sn beklenir; eskiden ~60 sn").isLessThan(10_000L);
    }

    @Test
    @DisplayName("RdapDomainExpiryService: damlatan 3xx gövdesi istek bütçesinde (5 sn) bırakılır, bitiş tarihi okunur")
    void rdapExpiry_drippingRedirectBody_boundedAndResultUnchanged() {
        RdapDomainExpiryService svc = new RdapDomainExpiryService(appSettings, new TrustEvaluator(appSettings),
                mock(CaAutoPinService.class), permissiveGuard());
        svc.init();

        long t0 = System.nanoTime();
        Map<String, Object> r = assertTimeoutPreemptively(Duration.ofSeconds(25), () -> svc.check("example.com"));
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertThat(r.get("error")).isNull();
        assertThat(r.get("expiry_date")).isEqualTo("2031-08-13T04:00:00Z");
        assertThat(ms).as("istek zaman aşımı 5 sn; eskiden ~60 sn").isLessThan(15_000L);
    }
}
