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
}
