package com.sitemonitor.service.page;

import com.sitemonitor.service.SsrfGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link HttpPhaseProbe} — SSRF kapısı sözleşmesi.
 *
 * <p>Bu sınıfın hiç testi yoktu. Kusur: {@code ssrfGuard.validate(host)} DOĞRULANMIŞ adres
 * listesini döndürüyor, ama prob bu listeyi ATIP host'u {@code InetAddress.getByName} ile
 * YENİDEN çözüyordu. İki çözüm arasında DNS yanıtı değişirse (rebind) prob, muhafızın
 * onayladığından başka bir adrese bağlanırdı — ilk sorguda genel bir IP, ikincisinde bir
 * bulut metadata ucu. Kardeş {@code PortCheckerService} tam bu nedenle döneni kullanıyor.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HttpPhaseProbeTest {

    @Mock SsrfGuard ssrfGuard;

    private HttpPhaseProbe probe() {
        return new HttpPhaseProbe(ssrfGuard);
    }

    @Test
    @DisplayName("A8: muhafızın DÖNDÜĞÜ adrese bağlanır — host YENİDEN çözülmez")
    void connectsToVettedAddress_withoutSecondResolution() throws Exception {
        // Gerçek bir dinleyici aç ve muhafızı ONUN adresini döndürecek şekilde kur. URL'deki
        // host ("rebind.invalid") çözülemez bir addır: prob kendi başına yeniden çözseydi
        // bağlantı kurulamaz ve connectMs null kalırdı. Bağlantının kurulmuş olması,
        // muhafızın döndürdüğü adresin KULLANILDIĞININ kanıtıdır.
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // Kabul edip GERÇEK bir yanıt yaz: prob ilk baytı bekliyor, sessiz kalan bir soket
            // zaman aşımına düşer ve tüm ölçümler null döner (ölçüm değil, hata yolu olurdu).
            Thread responder = new Thread(() -> {
                try (java.net.Socket s = server.accept()) {
                    // İsteği ÖNCE tüket: okumadan kapatmak karşı tarafa RST gönderir ve prob
                    // "Connection reset" ile hata yoluna düşer (bağlantı kurulmuş olsa bile).
                    s.getInputStream().read(new byte[1024]);
                    String crlf = new String(new char[] { 13, 10 });   // ters-bölü kaçışı yok
                    String http = "HTTP/1.1 200 OK" + crlf + "Content-Length: 0" + crlf + crlf;
                    s.getOutputStream().write(http.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    s.getOutputStream().flush();
                    Thread.sleep(200);          // prob yanıtı okuyana kadar soket açık kalsın
                } catch (Exception ignored) { /* test kapanışı */ }
            });
            responder.setDaemon(true);
            responder.start();

            when(ssrfGuard.validate(anyString()))
                    .thenReturn(List.of(InetAddress.getLoopbackAddress()));

            HttpPhaseProbe.Phases p = probe()
                    .measure("http://rebind.invalid:" + server.getLocalPort() + "/", 3000);

            assertThat(p.error())
                    .as("muhafızın döndürdüğü adrese bağlanılmalı — host YENİDEN çözülseydi "
                        + "'rebind.invalid' çözülemez olduğu için burada hata olurdu")
                    .isNull();
            assertThat(p.connectMs()).isNotNull();
            // Tek çözüm: muhafız bir kez çağrılır ve ikinci bir DNS sorgusu yapılmaz.
            verify(ssrfGuard, times(1)).validate(anyString());
            responder.join(2000);
        }
    }

    @Test
    @DisplayName("A8: muhafız hedefi engellerse bağlantı HİÇ açılmaz ve sebep raporlanır")
    void blockedTarget_reportsReasonAndDoesNotConnect() {
        when(ssrfGuard.validate(anyString()))
                .thenThrow(new SsrfGuard.BlockedException("hedef engellendi: link-local"));

        HttpPhaseProbe.Phases p = probe().measure("http://169.254.169.254/latest/meta-data/", 2000);

        assertThat(p.error()).contains("engellendi");
        assertThat(p.connectMs()).isNull();
        assertThat(p.tlsMs()).isNull();
    }

    @Test
    @DisplayName("A8: muhafız BOŞ liste dönerse bağlanılmaz (NPE/IndexOutOfBounds yok)")
    void emptyVettedList_returnsErrorInsteadOfCrashing() {
        when(ssrfGuard.validate(anyString())).thenReturn(List.of());

        HttpPhaseProbe.Phases p = probe().measure("http://example.com/", 2000);

        assertThat(p.error()).isNotNull();
        assertThat(p.connectMs()).isNull();
    }

    @Test
    @DisplayName("geçersiz URL: muhafıza HİÇ gidilmez")
    void blankHost_shortCircuits() {
        HttpPhaseProbe.Phases p = probe().measure("not-a-url", 2000);

        assertThat(p.error()).isNotNull();
        verify(ssrfGuard, times(0)).validate(anyString());
    }

    // ── Zaman aşımı kuralı (2026-11-15) — her adım KESİN son tarihte kesilir ───────────────────────────────

    /** Bağlantıyı kabul edip isteği okuyan, sonra durum satırını {@code dripMs} arayla BAYT BAYT gönderen sunucu. */
    private static Thread dripServer(ServerSocket server, long dripMs) {
        Thread t = new Thread(() -> {
            try (java.net.Socket s = server.accept()) {
                s.getInputStream().read(new byte[2048]);
                java.io.OutputStream out = s.getOutputStream();
                for (int i = 0; i < 100_000; i++) {   // sonlu (test bitince soket kapanır)
                    out.write('H');
                    out.flush();
                    Thread.sleep(dripMs);
                }
            } catch (Exception ignored) { /* bekçi soketi kapattı */ }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Test
    @DisplayName("Damlatan sunucu: her okuma zaman aşımının (5 sn) çok altında kalsa da ölçüm son tarihte kesilir")
    void dripServer_isCutAtTheDeadline() throws Exception {
        // Not: bazı Windows güvenlik yazılımları döngü (loopback) üzerindeki HTTP yanıtını tamamlanana dek tutar; o makinede
        // baytlar hiç gelmez. Okuma zaman aşımı (5 sn) son tarihten (1,5 sn) büyük tutulduğu için iki ortamda da kesen bekçidir.
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            dripServer(server, 200);   // her bayt 200 ms'de — okuma zaman aşımı HİÇ dolmaz; bekçi olmasa 8192 × 200 ms sürerdi
            when(ssrfGuard.validate(anyString())).thenReturn(List.of(InetAddress.getLoopbackAddress()));
            long t0 = System.nanoTime();
            HttpPhaseProbe.Phases p = probe().measure(new HttpPhaseProbe.Target(
                    "http://drip.invalid:" + server.getLocalPort() + "/", 5000, "UA", java.util.Map.of(), false),
                    System.currentTimeMillis() + 1500);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertThat(ms).as("son tarih 1,5 sn").isLessThan(3500);
            assertThat(p.error()).isEqualTo(HttpPhaseProbe.DEADLINE_ERROR);
            assertThat(p.serverMs()).as("süre dolan ölçümde TTFB bilinmiyor").isNull();
        }
    }

    @Test
    @DisplayName("Çağıranın son tarihi (kontrolün bütçesi) 3 × zaman aşımından önce gelirse ona uyulur")
    void callerDeadlineWins() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            dripServer(server, 200);
            when(ssrfGuard.validate(anyString())).thenReturn(List.of(InetAddress.getLoopbackAddress()));
            long t0 = System.nanoTime();
            HttpPhaseProbe.Phases p = probe().measure(new HttpPhaseProbe.Target(
                    "http://drip.invalid:" + server.getLocalPort() + "/", 10_000, "UA", java.util.Map.of(), false),
                    System.currentTimeMillis() + 800);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertThat(ms).as("çağıranın 800 ms son tarihi; 3 × 10 sn değil").isLessThan(2500);
            assertThat(p.error()).isEqualTo(HttpPhaseProbe.DEADLINE_ERROR);
        }
    }

    @Test
    @DisplayName("Yanıt vermeyen vekil (CONNECT yanıtı gelmez): tünel okuması da son tarihte kesilir")
    void silentProxyTunnel_isCutAtTheDeadline() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread t = new Thread(() -> {
                try (java.net.Socket s = proxy.accept()) {
                    java.io.InputStream in = s.getInputStream();
                    while (in.read() != -1) { /* CONNECT isteğini yut, ASLA yanıtlama */ }
                } catch (Exception ignored) { /* bekçi kapattı */ }
            });
            t.setDaemon(true);
            t.start();
            com.sitemonitor.service.ProxySettings ps = org.mockito.Mockito.mock(com.sitemonitor.service.ProxySettings.class);
            when(ps.enabled()).thenReturn(true);
            when(ps.openConnectTunnel(anyString(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
                java.util.function.Consumer<java.net.Socket> cb = inv.getArgument(3);
                java.net.Socket raw = new java.net.Socket();
                cb.accept(raw);
                raw.connect(proxy.getLocalSocketAddress(), 5000);
                raw.setSoTimeout(5000);
                String crlf = new String(new char[] { 13, 10 });
                raw.getOutputStream().write(("CONNECT x:443 HTTP/1.1" + crlf + crlf).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                raw.getInputStream().read();   // vekil yanıtı — gelmez; bekçi soketi kapatınca düşer
                return raw;
            });
            when(ssrfGuard.validate(anyString())).thenReturn(List.of(InetAddress.getLoopbackAddress()));
            HttpPhaseProbe hp = probe();
            hp.setProxySettings(ps);
            long t0 = System.nanoTime();
            HttpPhaseProbe.Phases p = hp.measure(new HttpPhaseProbe.Target(
                    "https://example.invalid/", 5000, "UA", java.util.Map.of(), true), System.currentTimeMillis() + 700);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            assertThat(ms).as("tünel okuması 5 sn zaman aşımını beklemez; son tarihte (700 ms) kesilir").isLessThan(2500);
            assertThat(p.error()).isEqualTo(HttpPhaseProbe.DEADLINE_ERROR);
        }
    }
}
