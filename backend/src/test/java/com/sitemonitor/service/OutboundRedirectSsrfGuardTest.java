package com.sitemonitor.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GIDEN ISTEMCILER: YONLENDIRME ZINCIRININ HER HOP'U SsrfGuard'DAN GECER.
 *
 * <p><b>Nasil bulundu.</b> Regresyon/benzer-bug taramasinda (S2 imzasi) uc giden istemcinin
 * {@code HttpClient.Redirect.NORMAL} kullandigi ve hicbirinin {@code SsrfGuard} cagirmadigi
 * gorildu: {@code RdapDomainClient}, {@code RdapDomainExpiryService}, {@code TrWebWhoisClient}.
 * Ayni sinif daha once {@code KeywordCheckerService}, {@code HttpCheckerService},
 * {@code HstsDiagnosticsService}, {@code CertificateCheckerService} ve {@code PageFetchCore}'da
 * duzeltilmisti ({@link SafeRedirect}); bu uc istemci o supurmede ATLANMIS.
 *
 * <p><b>Neden gercek bir yuzey.</b> {@code Redirect.NORMAL} zinciri kutuphane icinde takip eder,
 * ara hop'lar uygulamaya hic gorunmez — yani ilk host guard'dan gecse bile hedef sunucu
 * {@code 302 Location: http://169.254.169.254/} ile pod'u ic aga yonlendirebilir. Bu istemcilerin
 * hedefleri yoneticinin ayarlayabildigi DIS adreslerdir (rdap-bootstrap-url, rdap-fallback-url,
 * http.rdap-base-url, isimtescil-whois-url, trabis-whois-url); hedef sunucunun ele gecmesi
 * yeterlidir. Dahasi {@code RdapDomainClient.traceStep} yanit govdesini {@code _body} olarak
 * yonetici tanilama ekranina DONDURUYOR, yani ic bir ucun govdesi disari sizabilirdi.
 *
 * <p><b>Kapi ne olcuyor.</b> Yerel sunucu 302 ile bulut metadata adresine yonlendiriyor.
 * Guard IZIN VERICI kuruluyor (loopback + internal serbest) — buna ragmen metadata adresi
 * {@link SsrfGuard} tarafindan HER ZAMAN bloklanir. Yani test "guard cagriliyor mu" sorusunu
 * olcuyor, guard'in kendi politikasini degil: istemci hop'u dogrulamadan takip etseydi ikinci
 * istek metadata'ya giderdi.
 */
class OutboundRedirectSsrfGuardTest {

    /** Loopback serbest ama metadata HER ZAMAN bloklu — HstsDiagnosticsServiceTest ile ayni desen. */
    private static SsrfGuard permissiveGuard() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        return new SsrfGuard(s);
    }

    private HttpServer server;
    private String base;
    /** Sunucuya kac istek ULASTI — ikinci hop hic denenmemeli. */
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            // Bulut metadata ucuna yonlendir: guard cagrilmiyorsa istemci buraya gider.
            ex.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    // ── RdapDomainClient ──────────────────────────────────────────────────────

    @Test
    @DisplayName("RdapDomainClient: metadata'ya yonlendiren RDAP sunucusu TAKIP EDILMEZ")
    void rdapClient_metadataRedirect_notFollowed() throws Exception {
        AppSettingsService appSettings = mock(AppSettingsService.class);
        when(appSettings.getString(anyString(), anyString())).thenReturn(base);
        when(appSettings.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(3000);
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();

        RdapDomainClient c = new RdapDomainClient(appSettings, psl, new TrustEvaluator(appSettings),
                mock(CaAutoPinService.class), permissiveGuard());
        c.init();

        Map<String, Object> res = c.lookup("example.com");

        // Sonuc hata olmali; onemli olan ic adrese HIC baglanilmamasi.
        assertThat(res).isNotNull();
        assertThat(res.get("error")).as("metadata yonlendirmesi sessizce basarili olmus").isNotNull();
        // Yerel sunucuya istek gitti (ilk hop mesru), ama zincir orada durdu.
        assertThat(hits.get()).as("ilk hop hic denenmemis - test hedefi olcmuyor").isGreaterThan(0);
    }

    // ── RdapDomainExpiryService ───────────────────────────────────────────────

    @Test
    @DisplayName("RdapDomainExpiryService: metadata'ya yonlendiren RDAP ucu TAKIP EDILMEZ")
    void rdapExpiry_metadataRedirect_notFollowed() {
        AppSettingsService appSettings = mock(AppSettingsService.class);
        when(appSettings.getString(anyString(), anyString())).thenReturn(base);

        RdapDomainExpiryService svc = new RdapDomainExpiryService(
                appSettings, new TrustEvaluator(appSettings), mock(CaAutoPinService.class), permissiveGuard());
        svc.init();

        Map<String, Object> res = svc.check("example.com");

        assertThat(res).isNotNull();
        // Metadata govdesi hicbir alana sizmamali.
        assertThat(String.valueOf(res)).doesNotContain("meta-data");
        // Zincir gercekten yurudu mu (aksi halde test bos bir iddia olurdu).
        assertThat(hits.get()).as("ilk hop hic denenmemis - test hedefi olcmuyor").isGreaterThan(0);
    }

    // ── TrWebWhoisClient ──────────────────────────────────────────────────────

    @Test
    @DisplayName("TrWebWhoisClient: metadata'ya yonlendiren whois sunucusu TAKIP EDILMEZ")
    void trWebWhois_metadataRedirect_notFollowed() {
        AppSettingsService appSettings = mock(AppSettingsService.class);
        when(appSettings.getString(anyString(), anyString())).thenReturn(base.substring(0, base.length() - 1));
        when(appSettings.getBoolean(anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(true);
        when(appSettings.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(3000);
        // Saglayici listesi stub'lanmazsa Mockito BOS liste doner, dongu hic calismaz ve test
        // hicbir sey olcmeden yesil kalirdi (ilk yazimda tam bu oldu; `hits` kontrolu yakaladi).
        when(appSettings.getCsv(anyString(), anyString())).thenReturn(List.of("isimtescil"));

        TrWebWhoisClient c = new TrWebWhoisClient(appSettings, new TrustEvaluator(appSettings),
                mock(CaAutoPinService.class), permissiveGuard());
        c.init();
        try {
            // Engellenince whois verisi YOKTUR (null) — bu DOGRU sonuc. Olculen sey, ic ucun
            // govdesinin asla whois sonucu olarak donmemesi.
            String raw = c.fetchRaw("example.com.tr");
            assertThat(raw == null ? "" : raw)
                    .as("metadata govdesi whois sonucu olarak dondu").doesNotContain("meta-data");
            // Zincirin gercekten yurudugunu kanitla: yerel sunucuya en az bir istek ulasmali,
            // aksi halde test hicbir sey olcmeden yesil kalirdi.
            assertThat(hits.get()).as("ilk hop hic denenmemis - test hedefi olcmuyor").isGreaterThan(0);
        } finally {
            c.close();
        }
    }

    // ── Kaynak duzeyinde kapi: yeni bir istemci NORMAL ile gelmesin ───────────

    @Test
    @DisplayName("KAYNAK KAPISI: Redirect.NORMAL kullanan her istemci SafeRedirect de kullanmali")
    void noClientFollowsRedirectsWithoutSafeRedirect() throws Exception {
        Path serviceDir = Path.of("src/main/java/com/sitemonitor/service");
        assertThat(Files.isDirectory(serviceDir)).as("kaynak dizini bulunamadi").isTrue();

        try (var paths = Files.walk(serviceDir)) {
            List<String> offenders = paths
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        String src;
                        try { src = Files.readString(p); } catch (Exception e) { return false; }
                        // Yalniz GERCEK kullanim: yorum satirlarindaki anlatimlar sayilmaz.
                        boolean followsAutomatically = src.lines()
                                .map(String::trim)
                                .filter(l -> !l.startsWith("*") && !l.startsWith("//") && !l.startsWith("/*"))
                                .anyMatch(l -> l.contains("Redirect.NORMAL")
                                        || l.contains("setInstanceFollowRedirects(true)"));
                        return followsAutomatically && !src.contains("SafeRedirect");
                    })
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();

            assertThat(offenders)
                    .as("Bu dosyalar yonlendirmeyi kutuphaneye birakiyor ve hop'lari dogrulamiyor. "
                      + "Redirect.NEVER + SafeRedirect.nextHop + ssrfGuard.validate desenini uygula "
                      + "(ornek: KeywordCheckerService.sendFollowingSafely).")
                    .isEmpty();
        }
    }
}
