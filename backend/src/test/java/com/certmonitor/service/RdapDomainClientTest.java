package com.certmonitor.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** RDAP yanıt ayrıştırması (in-process HTTP sunucusu — gerçek ağ yok). */
class RdapDomainClientTest {

    private static final String RDAP_JSON = """
        { "objectClassName":"domain","ldhName":"EXAMPLE.COM",
          "events":[{"eventAction":"registration","eventDate":"1995-08-14T04:00:00Z"},
                    {"eventAction":"expiration","eventDate":"2026-08-13T04:00:00Z"}],
          "status":["client transfer prohibited","redemption period"],
          "entities":[{"roles":["registrar"],"vcardArray":["vcard",[["version",{},"text","4.0"],["fn",{},"text","MarkMonitor Inc."]]]}],
          "nameservers":[{"ldhName":"a.iana-servers.net"},{"ldhName":"b.iana-servers.net"}] }
        """;

    private HttpServer server;
    private RdapDomainClient client;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = RDAP_JSON.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/rdap+json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        AppSettingsService appSettings = mock(AppSettingsService.class);
        // Hem bootstrap hem fallback aynı sunucuya → bootstrap "services" içermez (boş map) → fallback kullanılır.
        when(appSettings.getString(anyString(), anyString())).thenReturn(base);
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        client = new RdapDomainClient(appSettings, psl, new TrustEvaluator(appSettings));   // @Value proxy alanları null/0 → direct client
        client.init();
    }

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    @DisplayName("lookup RDAP JSON'u ayrıştırır: expiry/registrar/EPP/nameserver")
    @SuppressWarnings("unchecked")
    void lookupParses() {
        Map<String, Object> r = client.lookup("example.com");
        assertThat(r.get("source")).isEqualTo("RDAP");
        assertThat(r.get("expiry_date")).isEqualTo("2026-08-13T04:00:00Z");
        assertThat(r.get("registration_date")).isEqualTo("1995-08-14T04:00:00Z");
        assertThat(r.get("registrar")).isEqualTo("MarkMonitor Inc.");
        assertThat((List<String>) r.get("status_codes")).contains("redemption period", "client transfer prohibited");
        assertThat((List<String>) r.get("nameservers")).contains("a.iana-servers.net", "b.iana-servers.net");
    }
}
