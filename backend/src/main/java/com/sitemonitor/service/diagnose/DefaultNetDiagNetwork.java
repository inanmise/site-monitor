package com.sitemonitor.service.diagnose;

import com.sitemonitor.service.NetworkResolver;
import com.sitemonitor.service.ProcessProbe;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.xbill.DNS.Message;
import org.xbill.DNS.ResolverConfig;
import org.xbill.DNS.SimpleResolver;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * {@link NetDiagNetwork}'ün GERÇEK uygulaması (2026-10-05). İzleme denetleyicilerinin kullandığı yapı taşlarıyla AYNI
 * davranır: SSRF politikası {@link SsrfGuard}, harici komut {@link ProcessProbe}, vekil tüneli {@link ProxySettings}'in
 * {@code CONNECT} isteğiyle aynı biçim (ama durum satırı ayrıca döner ki "vekile ulaşılamadı" ile "vekil reddetti"
 * ayrılsın), TLS güven-hepsi (izleme de doğrulamaz), DNS önbelleksiz {@link SimpleResolver}. Hiçbir ayarı değiştirmez,
 * CA pinlemez, kayıt yazmaz.
 */
@Component
public class DefaultNetDiagNetwork implements NetDiagNetwork {

    private final SsrfGuard ssrfGuard;
    @Autowired(required = false) private ProxySettings proxySettings;
    @Autowired(required = false) private TrustEvaluator trustEvaluator;

    public DefaultNetDiagNetwork(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    @Override
    public List<InetAddress> vet(String host) {
        return ssrfGuard.validate(host);
    }

    @Override
    public ProcessProbe.Result exec(List<String> args, int timeoutSec) {
        return ProcessProbe.run(args, null, timeoutSec);
    }

    @Override
    public Socket connect(InetAddress ip, int port, int timeoutMs) throws IOException {
        return NetworkResolver.connectSingle(new InetSocketAddress(ip, port), timeoutMs);
    }

    @Override
    public boolean proxyConfigured() {
        return proxySettings != null && proxySettings.enabled();
    }

    @Override
    public String proxyAddress() {
        return proxyConfigured() ? proxySettings.displayTarget() : "";
    }

    @Override
    public boolean proxyAuth() {
        return proxyConfigured() && proxySettings.hasAuth();
    }

    @Override
    public List<String> secretValues() {
        List<String> s = new ArrayList<>();
        if (proxySettings == null) return s;
        if (proxySettings.secretValue() != null) s.add(proxySettings.secretValue());
        String auth = proxySettings.proxyAuthorizationHeader();
        if (auth != null && auth.startsWith("Basic ")) s.add(auth.substring("Basic ".length()));
        return s;
    }

    @Override
    public Tunnel tunnel(String host, int port, int timeoutMs) throws IOException {
        if (!proxyConfigured()) throw new IOException("vekil tanımlı değil");
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(proxySettings.host(), proxySettings.port()), timeoutMs);
            raw.setSoTimeout(timeoutMs);
            StringBuilder req = new StringBuilder()
                    .append("CONNECT ").append(host).append(':').append(port).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append(':').append(port).append("\r\n");
            String auth = proxySettings.proxyAuthorizationHeader();
            if (auth != null) req.append("Proxy-Authorization: ").append(auth).append("\r\n");
            req.append("\r\n");
            raw.getOutputStream().write(req.toString().getBytes(StandardCharsets.US_ASCII));
            raw.getOutputStream().flush();
            InputStream in = raw.getInputStream();
            String status = readLine(in);
            if (status == null || !status.startsWith("HTTP/1.") || status.length() < 12 || !status.regionMatches(9, "200", 0, 3)) {
                throw new TunnelRefusedException(status == null ? null : status.trim());
            }
            String line;
            int headers = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (++headers > 100) throw new IOException("vekil tünel yanıtı: aşırı başlık (100+)");
            }
            return new Tunnel(raw, status.trim());
        } catch (IOException e) {
            try { raw.close(); } catch (Exception ignored) { /* kapatma hatası önemsiz */ }
            throw e;
        }
    }

    /** Bayt bayt tek satır (8 KB tavan) — önden okuma yok: hedefin ilk baytları tünel yanıtıyla aynı pakette gelebilir. */
    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (buf.size() >= 8192) throw new IOException("aşırı uzun satır");
            buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        String s = buf.toString(StandardCharsets.US_ASCII);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public TlsSession tls(Socket raw, String host, int port, int timeoutMs) throws IOException {
        CapturingTrustManager tm = new CapturingTrustManager();
        SSLSocket ssl;
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ tm }, new SecureRandom());
            ssl = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("TLS bağlamı kurulamadı: " + e.getMessage(), e);
        }
        ssl.setSoTimeout(timeoutMs);
        try {
            SSLParameters p = ssl.getSSLParameters();
            if (!NetworkResolver.isIpLiteral(host)) p.setServerNames(List.of(new SNIHostName(host)));
            ssl.setSSLParameters(p);
        } catch (Exception ignore) { /* SNI opsiyonel (izlemeyle aynı) */ }
        ssl.startHandshake();
        String alpn = null;
        try { alpn = ssl.getApplicationProtocol(); } catch (Exception ignore) { /* ALPN yok */ }
        return new TlsSession(ssl, ssl.getSession().getProtocol(), ssl.getSession().getCipherSuite(),
                alpn == null || alpn.isEmpty() ? null : alpn, tm.chain);
    }

    @Override
    public UdpResult udp(InetAddress ip, int port, byte[] payload, int timeoutMs) {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setSoTimeout(timeoutMs);
            ds.connect(ip, port);   // bağlı soket: ICMP port-unreachable PortUnreachableException olarak döner
            ds.send(new DatagramPacket(payload, payload.length, ip, port));
            byte[] buf = new byte[2048];
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            try {
                ds.receive(in);
                return new UdpResult("reply", in.getLength(), null);
            } catch (PortUnreachableException pue) {
                return new UdpResult("unreachable", 0, pue.getMessage());
            } catch (SocketTimeoutException ste) {
                return new UdpResult("timeout", 0, null);
            }
        } catch (Exception e) {
            return new UdpResult("error", 0, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    @Override
    public Message dns(InetSocketAddress server, Message query, int timeoutMs, boolean tcp) throws IOException {
        SimpleResolver r = new SimpleResolver(server);
        r.setTimeout(Duration.ofMillis(Math.max(200, timeoutMs)));
        r.setTCP(tcp);
        r.setIgnoreTruncation(true);   // TC bayrağını çağıran görsün (TCP'ye yeniden deneme tanılamanın kendi adımı)
        return r.send(query);
    }

    @Override
    public List<InetSocketAddress> systemResolvers() {
        LinkedHashSet<InetSocketAddress> out = new LinkedHashSet<>();
        try {
            out.addAll(ResolverConfig.getCurrentConfig().servers());
        } catch (Exception ignore) { /* yapılandırma okunamadı — boş liste */ }
        return new ArrayList<>(out);
    }

    @Override
    public Trust trust(X509Certificate[] chain) {
        if (chain == null || chain.length == 0) return new Trust(false, "sunucu sertifika göndermedi");
        if (trustEvaluator == null) return new Trust(null, null);
        try {
            TrustEvaluator.TrustResult r = trustEvaluator.evaluate(chain);
            return new Trust(r.trusted(), r.trusted() ? null : r.reason());
        } catch (Exception e) {
            return new Trust(false, e.getMessage());
        }
    }

    /** Güven-hepsi; sunucunun zincirini yakalar (yalnız giden bağlantı). */
    static final class CapturingTrustManager extends X509ExtendedTrustManager {
        volatile X509Certificate[] chain;

        @Override public void checkServerTrusted(X509Certificate[] c, String a, Socket s) { chain = c; }
        @Override public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) { chain = c; }
        @Override public void checkServerTrusted(X509Certificate[] c, String a) { chain = c; }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, Socket s) throws CertificateException { throw new CertificateException("outbound-only"); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) throws CertificateException { throw new CertificateException("outbound-only"); }
        @Override public void checkClientTrusted(X509Certificate[] c, String a) throws CertificateException { throw new CertificateException("outbound-only"); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
