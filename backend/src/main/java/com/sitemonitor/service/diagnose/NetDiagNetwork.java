package com.sitemonitor.service.diagnose;

import com.sitemonitor.service.ProcessProbe;
import org.xbill.DNS.Message;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Ping / Port / DNS uçtan uca tanılamasının AĞ DİKİŞLERİ (2026-10-05). Tanılama servisleri ağa YALNIZ bu arayüzden çıkar;
 * gerçek uygulama {@link DefaultNetDiagNetwork}, testler sahte bir uygulama verir — testler ağa hiç dokunmaz.
 *
 * <p>Kurallar (gerçek uygulamada):
 * <ul>
 *   <li>{@link #vet} = izleme denetleyicilerinin SSRF politikası ({@code SsrfGuard.validate}); dönen IP'lere bağlanılır
 *       (yeniden çözülmez → DNS-rebind kapalı). Politika reddi {@code SsrfGuard.BlockedException}, çözümlenemeyen ad
 *       {@code SsrfGuard.UnresolvableHostException} fırlatır.</li>
 *   <li>Vekil tüneli izlemenin kullandığı {@code CONNECT host:port} isteğinin AYNISI; kimlik başlığı çıktıya YAZILMAZ.</li>
 *   <li>TLS el sıkışması güven-hepsi (izleme de doğrulamaz); zincir yalnız BİLGİ için döner.</li>
 * </ul>
 */
public interface NetDiagNetwork {

    /** SSRF politikası + çözümleme. @throws com.sitemonitor.service.SsrfGuard.BlockedException politika reddi / çözümlenemeyen ad */
    List<InetAddress> vet(String host);

    /** Harici komut (ping / traceroute) — kabuksuz, süre sınırlı. */
    ProcessProbe.Result exec(List<String> args, int timeoutSec);

    /** Doğrudan TCP bağlantısı (çağıran kapatır). */
    Socket connect(InetAddress ip, int port, int timeoutMs) throws IOException;

    /** Vekil tanımlı mı (host + port). */
    boolean proxyConfigured();

    /** Vekilin kimliksiz gösterimi {@code host:port} (tanımsızsa boş). */
    String proxyAddress();

    /** Vekil kimliği tanımlı mı (yalnız bayrak — değer asla). */
    boolean proxyAuth();

    /** Çıktıdan süzülecek sır değerleri (vekil parolası, Basic jetonu). */
    List<String> secretValues();

    /**
     * Vekilde {@code CONNECT host:port} tüneli.
     *
     * @throws TunnelRefusedException vekil tüneli reddetti (durum satırı içinde)
     * @throws IOException            vekile ulaşılamadı
     */
    Tunnel tunnel(String host, int port, int timeoutMs) throws IOException;

    /** Bağlı soket üzerinde güven-hepsi TLS el sıkışması (SNI = host). */
    TlsSession tls(Socket raw, String host, int port, int timeoutMs) throws IOException;

    /** UDP gönder + tek yanıt bekle. Asla fırlatmaz. */
    UdpResult udp(InetAddress ip, int port, byte[] payload, int timeoutMs);

    /** Tek DNS sorgusu belirli sunucuya (önbelleksiz). Kesilmiş UDP yanıtı OLDUĞU GİBİ döner (TC bayrağı çağırana kalır). */
    Message dns(InetSocketAddress server, Message query, int timeoutMs, boolean tcp) throws IOException;

    /** İşletim sisteminin çözümleyicileri — DNS izlemesinin sorguları bunlara gider (ExtendedResolver sırası). */
    List<InetSocketAddress> systemResolvers();

    /** Zincir güveni (JVM + kurumsal CA paketi) — yalnız BİLGİ. */
    Trust trust(X509Certificate[] chain);

    /** Açılan tünel + vekilin durum satırı. */
    record Tunnel(Socket socket, String statusLine) {}

    /** El sıkışması sonucu; {@code socket} şifreli akış (HTTP/banner bunun üzerinden). */
    record TlsSession(Socket socket, String protocol, String cipher, String alpn, X509Certificate[] chain) {}

    /** {@code outcome}: reply | unreachable | timeout | error. */
    record UdpResult(String outcome, int bytes, String error) {}

    record Trust(Boolean trusted, String reason) {}

    /** Vekil tüneli REDDETTİ (vekile ulaşıldı; hedef porta tünel açmadı). */
    class TunnelRefusedException extends IOException {
        private final String statusLine;

        public TunnelRefusedException(String statusLine) {
            super("vekil tüneli reddetti: " + (statusLine == null ? "(boş yanıt)" : statusLine));
            this.statusLine = statusLine;
        }

        public String statusLine() { return statusLine; }
    }
}
