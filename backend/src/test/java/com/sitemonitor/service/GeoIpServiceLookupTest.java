package com.sitemonitor.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeoIpService#lookup} — HTTP yolu: 200 parse, cache isabeti, TTL süresi dolumu,
 * 200-dışı durum ve bağlantı hatası. Dış host YOK: yerel {@code HttpSercer} (loopback)
 * canned ip-api yanıtı döner; istek sayacı cache davranışını kanıtlar.
 */
class GeoIpServiceLookupTest {

    private HttpServer server;
    private GeoIpService svc;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = "{\"status\":\"success\",\"country\":\"Turkey\",\"city\":\"Istanbul\",\"org\":\"Akbank\"}";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/json/", ex -> {
            hits.incrementAndGet();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        svc = new GeoIpService();
        ReflectionTestUtils.setField(svc, "apiUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/json/%s");
        ReflectionTestUtils.setField(svc, "timeoutMillis", 2000L);
        ReflectionTestUtils.setField(svc, "cacheTtlMs", 3_600_000L);
        svc.init();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    @Test
    @DisplayName("200 + geçerli JSON → country/city/org parse edilir; ikinci çağrı cache'ten (tek HTTP isteği)")
    void success_parsedAndCached() {
        GeoIpService.GeoInfo g = svc.lookup("8.8.8.8");
        assertThat(g.country()).isEqualTo("Turkey");
        assertThat(g.city()).isEqualTo("Istanbul");
        assertThat(g.org()).isEqualTo("Akbank");
        assertThat(hits.get()).isEqualTo(1);

        GeoIpService.GeoInfo again = svc.lookup("8.8.8.8");
        assertThat(again).isEqualTo(g);
        assertThat(hits.get()).as("cache isabeti — sunucuya ikinci istek gitmemeli").isEqualTo(1);
    }

    @Test
    @DisplayName("TTL=0 → cache süresi anında dolar, her çağrı sunucuya gider")
    void ttlZero_cacheExpiresImmediately() {
        ReflectionTestUtils.setField(svc, "cacheTtlMs", 0L);
        svc.lookup("8.8.4.4");
        svc.lookup("8.8.4.4");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("200-dışı (500) → (null,null,null) döner ve CACHE'e yazılmaz (sonraki çağrı yeniden dener)")
    void non200_nullsAndNotCached() {
        status = 500;
        GeoIpService.GeoInfo g = svc.lookup("1.1.1.1");
        assertThat(g.country()).isNull();
        assertThat(g.city()).isNull();
        assertThat(g.org()).isNull();
        svc.lookup("1.1.1.1");
        assertThat(hits.get()).as("hata cache'lenmez").isEqualTo(2);
    }

    @Test
    @DisplayName("eksik alanlı JSON → eksik alanlar null, mevcutlar parse edilir")
    void partialJson_missingFieldsNull() {
        body = "{\"status\":\"success\",\"country\":\"Germany\"}";
        GeoIpService.GeoInfo g = svc.lookup("9.9.9.9");
        assertThat(g.country()).isEqualTo("Germany");
        assertThat(g.city()).isNull();
        assertThat(g.org()).isNull();
    }

    @Test
    @DisplayName("bağlantı reddi (kapalı port) → istisna yutulur, (null,null,null) döner")
    void connectionRefused_gracefulNulls() throws Exception {
        int deadPort = server.getAddress().getPort();
        server.stop(0);   // portu kapat → refused
        ReflectionTestUtils.setField(svc, "apiUrl", "http://127.0.0.1:" + deadPort + "/json/%s");
        GeoIpService.GeoInfo g = svc.lookup("4.4.4.4");
        assertThat(g).isEqualTo(new GeoIpService.GeoInfo(null, null, null));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); // tearDown için
    }
}
