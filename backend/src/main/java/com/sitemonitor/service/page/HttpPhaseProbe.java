package com.sitemonitor.service.page;

import com.sitemonitor.service.SsrfGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Bir HTTP(S) isteğinin FAZLARINI ayrı ayrı ölçer: DNS → TCP → TLS → sunucu bekleme.
 *
 * <p><b>Neden gerekli:</b> Sayfa Hızı'nın TTFB değeri tek bir rakamdı ve bu rakam DNS + TCP +
 * TLS + istek + ilk baytın TAMAMINI, üstelik yönlendirme zincirinin tümünü içeriyordu. Ölçüm
 * {@code HttpClient} üzerinden yapıldığı için bağlantı havuzu SICAKKEN 41 ms, yeni bağlantı
 * kurulunca ~3 sn okunuyordu: aynı sunucu, aynı sayfa, iki kat farklı sonuç. Kullanıcı bunu
 * "TTFB ara ara sıçrıyor" diye bildirdi ve haklıydı — sıçrayan sunucu değil, bizim bağlantı
 * kurma maliyetimizdi. Eşik o rakama bakınca sunucu hiç yavaşlamamışken alarm üretiyordu.
 *
 * <p><b>Neden AYRI ve TAZE bir bağlantı:</b> fazlar ancak bağlantıyı kendimiz kurarsak
 * ölçülebilir; {@code HttpClient} bunları dışarı vermez. Taze bağlantı ayrıca ölçümü
 * KARŞILAŞTIRILABİLİR yapar — havuzun o anki durumuna göre değişmez. Bedeli, yüzlerce istek
 * yapan bir kontrolde tek bir el sıkışmadır.
 *
 * <p><b>Dürüst sınır:</b> kalıcı bağlantı kullanan gerçek bir tarayıcı oturumu bu fazların
 * bir kısmını ödemez; buradaki rakam "soğuk" durumdur. Bunu gizlemek yerine arayüz fazları
 * ayrı ayrı gösterir, kullanıcı hangisinin büyüdüğünü görür.
 *
 * <p>Sertifika doğrulaması BİLEREK atlanır ({@link PageFetchCore} ile aynı sözleşme): bu prob
 * sertifika geçerliliğini değil ZAMANLAMAYI ölçer; sertifika işi Sertifika izlemesinindir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HttpPhaseProbe {

    /** Hiçbiri ölçülemedi (bağlantı kurulamadı vb.) — tüm alanlar {@code null}. */
    public static final Phases NONE = new Phases(null, null, null, null, null);

    /**
     * Faz kırılımı. Her alan {@code null} olabilir: ölçülemeyen fazı sıfır saymak, hiç
     * olmayan bir hızı iddia etmek olurdu.
     *
     * @param dnsMs     ad çözümleme
     * @param connectMs TCP el sıkışması
     * @param tlsMs     TLS el sıkışması (düz HTTP'de {@code null})
     * @param serverMs  istek gönderildikten sonra İLK YANIT BAYTINA kadar geçen süre —
     *                  "sunucu ne kadar düşündü". EŞİK BUNA bakar.
     * @param error     ölçüm neden yapılamadı (varsa)
     */
    public record Phases(Integer dnsMs, Integer connectMs, Integer tlsMs, Integer serverMs, String error) {

        /** Fazların toplamı — ölçülemeyenler atlanır; hiçbiri yoksa {@code null}. */
        public Integer totalMs() {
            int sum = 0;
            boolean any = false;
            for (Integer v : new Integer[]{ dnsMs, connectMs, tlsMs, serverMs }) {
                if (v != null) { sum += v; any = true; }
            }
            return any ? sum : null;
        }
    }

    private final SsrfGuard ssrfGuard;

    private static final SSLSocketFactory TRUST_ALL = buildTrustAll();

    /**
     * Fazları ölçer. Asla istisna fırlatmaz — ölçüm başarısızsa {@link Phases#error} dolu döner
     * ve ASIL kontrol (sayfa çekimi) etkilenmez: faz kırılımı bir teşhis zenginleştirmesidir,
     * ölçümün ön şartı değildir.
     */
    public Phases measure(String url, int timeoutMs) {
        int to = Math.max(1000, timeoutMs);
        Socket plain = null;
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            if (host == null || host.isBlank()) return new Phases(null, null, null, null, "geçersiz URL");
            boolean https = !"http".equalsIgnoreCase(u.getScheme());
            int port = u.getPort() > 0 ? u.getPort() : (https ? 443 : 80);

            // SSRF: sayfa çekimiyle AYNI kapı. Prob ayrı bir bağlantı açtığı için burada da
            // doğrulanmalı, aksi halde muhafızın kapattığı bir hedefe prob sızardı.
            //
            // DOĞRULANAN ADRESE bağlanılır — `validate` zaten çözülmüş IP listesini DÖNDÜRÜYOR ve
            // eskiden bu liste ATILIP host `InetAddress.getByName` ile YENİDEN çözülüyordu. İki
            // çözüm arasında DNS yanıtı değişirse (rebind) prob, muhafızın onayladığından BAŞKA
            // bir adrese bağlanırdı: ilk sorguda genel bir IP, ikincisinde 169.254.169.254 gibi
            // bir bulut metadata ucu. PortCheckerService:66-69 tam bu nedenle döneni kullanıyor.
            // PageFetchCore:188-191'deki *kabul edilmiş* TOCTOU gerekçesi (HttpClient + SNI)
            // burada geçerli DEĞİL: prob zaten ham soketle, InetAddress üzerinden bağlanıyor.
            long t0 = System.nanoTime();
            java.util.List<java.net.InetAddress> vetted = ssrfGuard.validate(host);
            int dnsMs = msSince(t0);
            if (vetted.isEmpty()) return new Phases(dnsMs, null, null, null, "adres çözülemedi");
            InetAddress addr = vetted.get(0);

            long t1 = System.nanoTime();
            plain = new Socket();
            plain.connect(new InetSocketAddress(addr, port), to);
            plain.setSoTimeout(to);
            int connectMs = msSince(t1);

            Socket sock = plain;
            Integer tlsMs = null;
            if (https) {
                long t2 = System.nanoTime();
                SSLSocket ssl = (SSLSocket) TRUST_ALL.createSocket(plain, host, port, true);
                ssl.setSoTimeout(to);
                // SNI: sanal barındırılan/WAF arkasındaki hedefler SNI olmadan farklı (ya da hiç)
                // sertifika sunar ve el sıkışma süresi gerçeği yansıtmaz.
                ssl.setUseClientMode(true);
                ssl.startHandshake();
                tlsMs = msSince(t2);
                sock = ssl;
            }

            String path = (u.getRawPath() == null || u.getRawPath().isBlank()) ? "/" : u.getRawPath();
            if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + (u.getPort() > 0 ? ":" + u.getPort() : "") + "\r\n"
                    + "User-Agent: SiteMonitor-PhaseProbe\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n";

            long t3 = System.nanoTime();
            OutputStream out = sock.getOutputStream();
            out.write(req.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = sock.getInputStream();
            int first = in.read();                       // İLK BAYT — ölçülmek istenen an tam burası
            int serverMs = msSince(t3);
            if (first < 0) {
                return new Phases(dnsMs, connectMs, tlsMs, null, "sunucu yanıt vermeden bağlantıyı kapattı");
            }
            // Gövde OKUNMAZ: bu prob ağırlık ölçmez, yalnız zamanlama ölçer. Connection: close
            // gönderildiği için soket kapatıldığında sunucu da bırakır.
            return new Phases(dnsMs, connectMs, tlsMs, serverMs, null);

        } catch (SsrfGuard.BlockedException be) {
            return new Phases(null, null, null, null, be.getMessage());
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.debug("Faz ölçümü yapılamadı {}: {}", url, msg);
            return new Phases(null, null, null, null, msg);
        } finally {
            if (plain != null) try { plain.close(); } catch (Exception ignored) { /* kapanış */ }
        }
    }

    private static int msSince(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }

    private static SSLSocketFactory buildTrustAll() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            log.warn("Faz probu trust-all SSL kuramadı, varsayılan kullanılacak: {}", e.getMessage());
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }
}
