package com.sitemonitor.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Alan Adı Süre Kaynağı" kartının durumu (2026-11-19, prod: kart ara ara "Kesinti — rdap http 404" diyordu ve elle
 * tetiklenen sağlam bir alan adı onu düzeltiyordu). Kök neden: durum kümedeki SON tek sorguya bakıyordu; .tr gibi RDAP'ı
 * olmayan TLD'lerde rdap.org her zaman 404 döner. Kurallar:
 * <ul>
 *   <li>IANA listesinde olmayan TLD için rdap.org'a GİDİLMEZ ve kaynak durumu değişmez;</li>
 *   <li>kayıt sunucusunun 404'ü (alan bulunamadı) kaynak için başarıdır ve yedeğe gidilmez;</li>
 *   <li>kesinti (NONE) yalnız art arda {@link RdapDomainClient#SOURCE_OUTAGE_STREAK} kaynak hatasında; daha azı DEGRADED.</li>
 * </ul>
 * Yerel HTTP sunucusu — gerçek ağ yok.
 */
class RdapSourceStatusTest {

    private static final String RDAP_JSON = """
        { "objectClassName":"domain","ldhName":"OK.COM",
          "events":[{"eventAction":"expiration","eventDate":"2030-08-13T04:00:00Z"}] }
        """;

    private HttpServer server;
    private RdapDomainClient client;
    private final AtomicInteger fallbackHits = new AtomicInteger();
    private final AtomicInteger registryHits = new AtomicInteger();

    private static SsrfGuard permissiveGuard() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        return new SsrfGuard(s);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/rdap+json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        server.createContext("/bootstrap", ex -> reply(ex, 200,
                "{\"services\":[[[\"com\"],[\"" + base + "reg/\"]]]}"));
        server.createContext("/reg/", ex -> {
            registryHits.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/ok.com")) reply(ex, 200, RDAP_JSON);
            else if (path.endsWith("/missing.com")) reply(ex, 404, "{\"errorCode\":404}");
            else reply(ex, 500, "{\"errorCode\":500}");
        });
        server.createContext("/fallback/", ex -> {
            fallbackHits.incrementAndGet();
            reply(ex, 404, "{\"errorCode\":404,\"title\":\"No RDAP service is available for this resource\"}");
        });
        server.start();

        AppSettingsService appSettings = mock(AppSettingsService.class);
        when(appSettings.getString(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(appSettings.getString(eq("site.monitor.domain.rdap-bootstrap-url"), anyString())).thenReturn(base + "bootstrap");
        when(appSettings.getString(eq("site.monitor.domain.rdap-fallback-url"), anyString())).thenReturn(base + "fallback/");
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        client = new RdapDomainClient(appSettings, psl, new TrustEvaluator(appSettings), mock(CaAutoPinService.class), permissiveGuard());
        client.init();
    }

    @AfterEach
    void stop() { if (server != null) server.stop(0); }

    @Test
    @DisplayName("IANA listesinde olmayan TLD (.tr): rdap.org'a gidilmez, sonuç WHOIS'e kalır, kaynak durumu değişmez")
    void unsupportedTld_noRequest_noSourceError() {
        client.lookup("ok.com");                                   // IANA listesi yüklensin + bir başarı
        int fbBefore = fallbackHits.get();
        Map<String, Object> r = client.lookup("ornek.com.tr");
        assertThat(String.valueOf(r.get("error"))).startsWith(RdapDomainClient.NO_PUBLIC_RDAP);
        assertThat(r.get("rdap_registry")).isEqualTo(Boolean.FALSE);
        assertThat(r.get("rdap_unsupported")).isEqualTo(Boolean.TRUE);
        assertThat(fallbackHits.get()).as("rdap.org sorgulanmaz").isEqualTo(fbBefore);
        Map<String, Object> s = client.getSourceStatus();
        assertThat(s.get("source")).isEqualTo("RDAP");
        assertThat(s.get("alarm")).isEqualTo(false);
        assertThat(s.get("consecutive_failures")).isEqualTo(0);
    }

    @Test
    @DisplayName("kayıt sunucusunun 404'ü = alan bulunamadı: kaynak sağlam, yedeğe gidilmez")
    void registryNotFound_isNotASourceFailure() {
        Map<String, Object> r = client.lookup("missing.com");
        assertThat(r.get("error")).isEqualTo("rdap http 404");
        assertThat(fallbackHits.get()).as("404 kesin yanıttır").isZero();
        Map<String, Object> s = client.getSourceStatus();
        assertThat(s.get("source")).isEqualTo("RDAP");
        assertThat(s.get("alarm")).isEqualTo(false);
        assertThat(s.get("reason")).isNull();
        assertThat(s.get("last_success")).isNotNull();
    }

    @Test
    @DisplayName("tek / iki kaynak hatası DEGRADED (uyarı), üçüncüsü NONE (kesinti); başarı sayacı sıfırlar")
    void outageNeedsConsecutiveSourceFailures() {
        client.lookup("broken.com");
        Map<String, Object> s1 = client.getSourceStatus();
        assertThat(s1.get("source")).isEqualTo("DEGRADED");
        assertThat(s1.get("alarm")).isEqualTo(false);
        assertThat(s1.get("consecutive_failures")).isEqualTo(1);
        assertThat(s1.get("outage_after")).isEqualTo(RdapDomainClient.SOURCE_OUTAGE_STREAK);
        assertThat(s1.get("reason")).isEqualTo("rdap http 500");

        client.lookup("broken.com");
        assertThat(client.getSourceStatus().get("source")).isEqualTo("DEGRADED");

        client.lookup("broken.com");
        Map<String, Object> s3 = client.getSourceStatus();
        assertThat(s3.get("source")).isEqualTo("NONE");
        assertThat(s3.get("alarm")).isEqualTo(true);
        assertThat(s3.get("consecutive_failures")).isEqualTo(3);

        client.lookup("ok.com");
        Map<String, Object> ok = client.getSourceStatus();
        assertThat(ok.get("source")).isEqualTo("RDAP");
        assertThat(ok.get("alarm")).isEqualTo(false);
        assertThat(ok.get("consecutive_failures")).isEqualTo(0);

        client.lookup("broken.com");
        assertThat(client.getSourceStatus().get("source")).as("başarıdan sonra tek hata yine uyarı").isEqualTo("DEGRADED");
    }

    @Test
    @DisplayName("araya giren 404 (alan bulunamadı) ardışık hata sayacını sıfırlar — kaynak yanıt verdi")
    void notFoundResetsTheStreak() {
        client.lookup("broken.com");
        client.lookup("broken.com");
        client.lookup("missing.com");
        client.lookup("broken.com");
        Map<String, Object> s = client.getSourceStatus();
        assertThat(s.get("source")).isEqualTo("DEGRADED");
        assertThat(s.get("consecutive_failures")).isEqualTo(1);
    }

    @Test
    @DisplayName("hiç sorgu yokken IDLE; sayaç alanları gönderilir")
    void idleBeforeAnyLookup() {
        Map<String, Object> s = client.getSourceStatus();
        assertThat(s.get("source")).isEqualTo("IDLE");
        assertThat(s.get("alarm")).isEqualTo(false);
        assertThat(s.get("consecutive_failures")).isEqualTo(0);
        assertThat(registryHits.get()).isZero();
    }

    @Test
    @DisplayName("sonuç SiteMonitor'ün sorguladığı RDAP adresini taşır (başarı ve 404) — kart bağlantısı (2026-11-19)")
    void resultCarriesQueryUrl() {
        Map<String, Object> ok = client.lookup("ok.com");
        assertThat(String.valueOf(ok.get(RdapDomainClient.QUERY_URL_KEY))).endsWith("/reg/domain/ok.com");
        Map<String, Object> missing = client.lookup("missing.com");
        assertThat(String.valueOf(missing.get(RdapDomainClient.QUERY_URL_KEY))).endsWith("/reg/domain/missing.com");
        Map<String, Object> tr = client.lookup("ornek.com.tr");
        assertThat(tr).doesNotContainKey(RdapDomainClient.QUERY_URL_KEY);   // sorgu yapılmadı
    }

    @Test
    @DisplayName("safeQueryUrl: yalnız http(s), bilgi (userinfo) içermeyen ve 500 karakteri aşmayan adres")
    void safeQueryUrlRules() {
        assertThat(DomainCheckerService.safeQueryUrl("https://rdap.verisign.com/com/v1/domain/ok.com"))
                .isEqualTo("https://rdap.verisign.com/com/v1/domain/ok.com");
        assertThat(DomainCheckerService.safeQueryUrl(" http://x.example/whois?domainname=a.com.tr ")).isEqualTo("http://x.example/whois?domainname=a.com.tr");
        assertThat(DomainCheckerService.safeQueryUrl("javascript:alert(1)")).isNull();
        assertThat(DomainCheckerService.safeQueryUrl("ftp://x.example/a")).isNull();
        assertThat(DomainCheckerService.safeQueryUrl("https://user:pw@x.example/a")).isNull();
        assertThat(DomainCheckerService.safeQueryUrl("https://x.example/" + "a".repeat(500))).isNull();
        assertThat(DomainCheckerService.safeQueryUrl("ht tp://bozuk")).isNull();
        assertThat(DomainCheckerService.safeQueryUrl(null)).isNull();
        assertThat(DomainCheckerService.safeQueryUrl(42)).isNull();
        // Yapılandırılmış adresteki erişim anahtarı herkese gösterilmez; alan adı parametresi kalır
        assertThat(DomainCheckerService.safeQueryUrl("https://rdap.example/domain/a.com?token=s3cr3t&lang=tr"))
                .isEqualTo("https://rdap.example/domain/a.com?token=" + SecretMask.MASK + "&lang=tr");
        assertThat(DomainCheckerService.safeQueryUrl("https://whois.example/q?domainname=a.com.tr&api_key=k1"))
                .isEqualTo("https://whois.example/q?domainname=a.com.tr&api_key=" + SecretMask.MASK);
    }
}
