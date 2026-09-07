package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OS-duyarlı komut kurma + özet yardımcıları + TCP kontrolü.
 *
 * <p><b>Ne test EDİLMİYOR ve neden.</b> {@code analyze()} gerçek harici komutlar çalıştırıyor
 * (nslookup, curl, ping, traceroute). Birim testinde koşturmak hem yavaş hem ortama bağlı olurdu:
 * runner'da {@code dig} yoksa test kırmızı olur ama kodda kusur yoktur.
 *
 * <p><b>OS DALLARININ İKİSİ DE denenir (bu dosyanın önceki hâlinde eksikti).</b> Testler
 * {@code isWindows()} ile dallanıyordu, yani geliştirici makinesinde YALNIZ Windows dalı, CI'da
 * YALNIZ Linux dalı doğrulanıyordu — her koşumda argümanların yarısı denenmemiş kalıyordu.
 * Oysa üretim Linux pod'unda, geliştirme Windows'ta çalışıyor: yanlış bir bayrak (ping'de
 * {@code -c} yerine {@code -n}) yalnız bir tarafta bozar ve tanılama sessizce "komut başarısız"
 * der — sorun ağda sanılırken araç bozuktur. Artık {@code os.name} geçici olarak değiştirilip
 * iki dal da AYNI koşumda sabitleniyor.
 */
class NetworkDiagnosticsServiceTest {

    /** os.name'i geçici değiştirir; her iki OS dalı da aynı koşumda denenebilsin. */
    private static <T> T withOs(String osName, java.util.function.Supplier<T> body) {
        String prev = System.getProperty("os.name");
        System.setProperty("os.name", osName);
        try {
            return body.get();
        } finally {
            if (prev != null) System.setProperty("os.name", prev);
            else System.clearProperty("os.name");
        }
    }

    private static final String WIN = "Windows 11";
    private static final String NIX = "Linux";

    // ── ping / traceroute: iki OS dalı da TAM liste ile ───────────────────────

    @Test
    @DisplayName("buildPingArgs: Linux -c 4 -w 8 / Windows -n 4 — IKI dal da")
    void buildPingArgs_bothOsBranches() {
        assertThat(withOs(NIX, () -> NetworkDiagnosticsService.buildPingArgs("example.com")))
                .containsExactly("ping", "-c", "4", "-w", "8", "example.com");
        assertThat(withOs(WIN, () -> NetworkDiagnosticsService.buildPingArgs("example.com")))
                .containsExactly("ping", "-n", "4", "example.com");
    }

    @Test
    @DisplayName("buildTracerouteArgs: Linux traceroute -m / Windows tracert -h — IKI dal da")
    void buildTracerouteArgs_bothOsBranches() {
        assertThat(withOs(NIX, () -> NetworkDiagnosticsService.buildTracerouteArgs("example.com")))
                .containsExactly("traceroute", "-m", "15", "-w", "2", "-q", "1", "example.com");
        assertThat(withOs(WIN, () -> NetworkDiagnosticsService.buildTracerouteArgs("example.com")))
                .containsExactly("tracert", "-h", "15", "-w", "2000", "example.com");
    }

    // ── curl: şema, port eki, null cihaz ──────────────────────────────────────

    @Test
    @DisplayName("buildCurlArgs: sema porta gore, cikti null cihaza — IKI dal da TAM liste")
    void buildCurlArgs_schemeAndNullDevice() {
        assertThat(withOs(NIX, () -> NetworkDiagnosticsService.buildCurlArgs("ex.com", 443)))
                .containsExactly("curl", "-sS", "-v", "--max-time", "8", "-o", "/dev/null", "https://ex.com");
        assertThat(withOs(NIX, () -> NetworkDiagnosticsService.buildCurlArgs("ex.com", 80)))
                .containsExactly("curl", "-sS", "-v", "--max-time", "8", "-o", "/dev/null", "http://ex.com");
        assertThat(withOs(WIN, () -> NetworkDiagnosticsService.buildCurlArgs("ex.com", 443)))
                .containsExactly("curl", "-sS", "-v", "--max-time", "8", "-o", "NUL", "https://ex.com");
    }

    @Test
    @DisplayName("buildCurlArgs: standart DISI port URL'e yazilir (yoksa yanlis uc test edilir)")
    void buildCurlArgs_customPortAppended() {
        assertThat(NetworkDiagnosticsService.buildCurlArgs("ex.com", 8443))
                .contains("https://ex.com:8443");
    }

    @Test
    @DisplayName("nullDevice: Windows NUL / diger /dev/null")
    void nullDevice_osAware() {
        assertThat(withOs(WIN, NetworkDiagnosticsService::nullDevice)).isEqualTo("NUL");
        assertThat(withOs(NIX, NetworkDiagnosticsService::nullDevice)).isEqualTo("/dev/null");
    }

    @Test
    @DisplayName("her komut argumani hedef domaini TASIR (yanlis hedefi tanilamayalim)")
    void everyCommand_carriesTheDomain() {
        for (List<String> args : List.of(
                NetworkDiagnosticsService.buildCurlArgs("hedef.example.com", 8443),
                NetworkDiagnosticsService.buildPingArgs("hedef.example.com"),
                NetworkDiagnosticsService.buildTracerouteArgs("hedef.example.com"))) {
            assertThat(String.join(" ", args)).contains("hedef.example.com");
        }
    }

    // ── özet satırı ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("firstMeaningfulLine: ilk bos olmayan satir, kisaltma, null/bos davranisi")
    void firstMeaningfulLine() {
        assertThat(NetworkDiagnosticsService.firstMeaningfulLine("\n\n  hello \nworld")).isEqualTo("hello");
        assertThat(NetworkDiagnosticsService.firstMeaningfulLine("")).isEqualTo("—");
        assertThat(NetworkDiagnosticsService.firstMeaningfulLine("   \n  ")).isEqualTo("—");
        assertThat(NetworkDiagnosticsService.firstMeaningfulLine(null)).isEqualTo("");
        String longLine = "x".repeat(200);
        assertThat(NetworkDiagnosticsService.firstMeaningfulLine(longLine)).hasSize(121).endsWith("…");
    }

    // ── TCP kontrolü (gerçek soket, hızlı) ────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> tcpCheck(String host, int port) {
        NetworkDiagnosticsService svc = new NetworkDiagnosticsService(null);
        ReflectionTestUtils.setField(svc, "timeoutSeconds", 2);
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(svc, "tcpCheck", host, port);
    }

    @Test
    @DisplayName("TCP: acik port 'ok' doner ve baglanti kaydini tasir")
    void tcpCheck_openPort_isOk() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Map<String, Object> m = tcpCheck("127.0.0.1", server.getLocalPort());
            assertThat(m).containsEntry("status", "ok").containsEntry("available", true);
            assertThat(String.valueOf(m.get("output"))).contains("127.0.0.1");
        }
    }

    @Test
    @DisplayName("TCP: cozulemeyen host DNS hatasi olarak AYIRT EDILIR (zaman asimi degil)")
    void tcpCheck_unknownHost_reportsDnsFailure() {
        // Ayrim onemli: operatore "DNS mi, firewall mi" sorusunun cevabini veren tek sinyal bu.
        Map<String, Object> m = tcpCheck(TestHosts.UNRESOLVABLE, 443);
        assertThat(m).containsEntry("status", "fail");
        assertThat(String.valueOf(m.get("summary"))).contains("DNS");
    }

    @Test
    @DisplayName("TCP: kontrol kaydi her zaman key/label/command tasir (arayuz bunlara dayaniyor)")
    void tcpCheck_alwaysCarriesIdentity() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Map<String, Object> m = tcpCheck("127.0.0.1", server.getLocalPort());
            assertThat(m).containsKeys("key", "label", "command", "status", "summary", "output");
            assertThat(m.get("key")).isEqualTo("tcp");
        }
    }
}
