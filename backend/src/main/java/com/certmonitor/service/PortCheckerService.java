package com.certmonitor.service;

import com.certmonitor.model.PortMonitor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Port izleme kontrolleri. Tip ({@code protocol}) baz alınır:
 * <ul>
 *   <li><b>TCP</b> — soket connect (port açık mı).</li>
 *   <li><b>TLS</b> — TLS handshake başarılı mı (sunucu sertifika sunuyor mu).</li>
 *   <li><b>HTTP</b> — HTTP(S) GET; dönen durum kodu beklenen kalıbı tutuyor mu (vars. 2xx/3xx). 443/8443 -> https.</li>
 *   <li><b>BANNER</b> — bağlan, (varsa) veri gönder, sunucu yanıtını oku, beklenen alt-dizgeyi doğrula.</li>
 *   <li><b>UDP</b> — datagram gönder; yanıt/ICMP'ye bakar (bağlantısız olduğundan sonuç güvenilir değildir).</li>
 * </ul>
 * Sonuç: {@code {open, response_ms, error?, detail?}}.
 */
@Slf4j
@Service
public class PortCheckerService {

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String host, int port, int timeoutMs) {
        return CompletableFuture.completedFuture(check(host, port, timeoutMs));
    }

    /** Geriye dönük uyumluluk — düz TCP connect (eski çağrılar/testler). */
    public Map<String, Object> check(String host, int port, int timeoutMs) {
        return check(host, port, timeoutMs, "TCP", null, null);
    }

    /** Monitor tipine göre kontrol (IP sürümü dahil). */
    public Map<String, Object> check(PortMonitor m) {
        return check(m.getHost(), m.getPort(),
                m.getTimeoutMs() != null ? m.getTimeoutMs() : 5000,
                m.getProtocol(), m.getSendData(), m.getExpect(),
                m.getIpVersion());
    }

    /** Geriye uyum: IP sürümü belirtilmeden (auto). */
    public Map<String, Object> check(String host, int port, int timeoutMs, String type, String send, String expect) {
        return check(host, port, timeoutMs, type, send, expect, "auto");
    }

    public Map<String, Object> check(String host, int port, int timeoutMs, String type, String send, String expect, String ipVersion) {
        String t = type != null ? type.trim().toUpperCase() : "TCP";
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            InetAddress addr = resolveFamily(host, ipVersion);   // null → varsayılan çözümleme (auto)
            switch (t) {
                case "TLS"    -> doTls(addr, host, port, timeoutMs, result);
                case "HTTP"   -> doHttp(addr, host, port, timeoutMs, send, expect, result);
                case "BANNER" -> doBanner(addr, host, port, timeoutMs, send, expect, result);
                case "UDP"    -> doUdp(addr, host, port, timeoutMs, send, result);
                default        -> doTcp(addr, host, port, timeoutMs, result);
            }
            if (Boolean.TRUE.equals(result.get("open")) && result.get("response_ms") == null) {
                result.put("response_ms", System.currentTimeMillis() - start);
            }
            if (!result.containsKey("response_ms")) result.put("response_ms", null);
        } catch (Exception e) {
            result.put("open", false);
            result.put("response_ms", null);
            result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.debug("Port check ({}) failed for {}:{}: {}", t, host, port, e.toString());
        }
        return result;
    }

    private void doTcp(InetAddress addr, String host, int port, int timeoutMs, Map<String, Object> result) throws Exception {
        try (Socket s = connectAny(addr, host, port, timeoutMs)) {
            result.put("open", true);
        }
    }

    private void doTls(InetAddress addr, String host, int port, int timeoutMs, Map<String, Object> result) throws Exception {
        try (Socket raw = connectAny(addr, host, port, timeoutMs)) {
            SSLSocketFactory f = trustAllContext().getSocketFactory();
            try (SSLSocket ssl = (SSLSocket) f.createSocket(raw, host, port, true)) {
                ssl.setSoTimeout(timeoutMs);
                try {
                    SSLParameters p = ssl.getSSLParameters();
                    p.setServerNames(List.of(new SNIHostName(host)));
                    ssl.setSSLParameters(p);
                } catch (Exception ignore) { /* SNI opsiyonel */ }
                ssl.startHandshake();
                result.put("open", true);
                result.put("detail", "TLS " + ssl.getSession().getProtocol());
            }
        }
    }

    private void doHttp(InetAddress addr, String host, int port, int timeoutMs, String path, String expect, Map<String, Object> result) throws Exception {
        boolean https = port == 443 || port == 8443;
        String p = (path != null && !path.isBlank()) ? path.trim() : "/";
        if (!p.startsWith("/")) p = "/" + p;
        String connectHost = addr != null ? urlHost(addr) : host;
        URL url = URI.create((https ? "https" : "http") + "://" + connectHost + ":" + port + p).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof HttpsURLConnection hc) {
            hc.setSSLSocketFactory(trustAllContext().getSocketFactory());
            hc.setHostnameVerifier((h, s) -> true);
        }
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "CertMonitor-PortCheck");
        if (addr != null) conn.setRequestProperty("Host", (port == 80 || port == 443) ? host : host + ":" + port);   // IP'ye bağlan, vhost adı doğru kalsın
        int code;
        try {
            code = conn.getResponseCode();
        } finally {
            conn.disconnect();
        }
        boolean ok = httpStatusMatches(code, expect);
        result.put("open", ok);
        result.put("detail", "HTTP " + code);
        if (!ok) result.put("error", "HTTP " + code
                + (expect != null && !expect.isBlank() ? " (beklenen: " + expect.trim() + ")" : ""));
    }

    private void doBanner(InetAddress addr, String host, int port, int timeoutMs, String send, String expect, Map<String, Object> result) throws Exception {
        try (Socket s = connectAny(addr, host, port, timeoutMs)) {
            s.setSoTimeout(timeoutMs);
            if (send != null && !send.isEmpty()) {
                OutputStream os = s.getOutputStream();
                os.write(unescape(send).getBytes(StandardCharsets.ISO_8859_1));
                os.flush();
            }
            byte[] buf = new byte[1024];
            int n = s.getInputStream().read(buf);
            String banner = n > 0 ? new String(buf, 0, n, StandardCharsets.ISO_8859_1).trim() : "";
            boolean ok = (expect != null && !expect.isBlank()) ? banner.contains(expect.trim()) : n > 0;
            String shortB = banner.length() > 80 ? banner.substring(0, 80) + "…" : banner;
            result.put("open", ok);
            result.put("detail", shortB);
            if (!ok) result.put("error", (expect != null && !expect.isBlank())
                    ? "Beklenen yanit yok: '" + expect.trim() + "' (gelen: " + (shortB.isEmpty() ? "bos" : shortB) + ")"
                    : "Banner alinamadi");
        }
    }

    private void doUdp(InetAddress addr, String host, int port, int timeoutMs, String send, Map<String, Object> result) throws Exception {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setSoTimeout(timeoutMs);
            byte[] payload = (send != null && !send.isEmpty())
                    ? unescape(send).getBytes(StandardCharsets.ISO_8859_1) : new byte[]{0};
            InetAddress target = addr != null ? addr : InetAddress.getByName(host);
            ds.send(new DatagramPacket(payload, payload.length, target, port));
            byte[] buf = new byte[2048];
            try {
                ds.receive(new DatagramPacket(buf, buf.length));
                result.put("open", true);
                result.put("detail", "UDP yanit alindi");
            } catch (PortUnreachableException pue) {
                result.put("open", false);
                result.put("error", "UDP port erisilemez (ICMP unreachable)");
            } catch (SocketTimeoutException ste) {
                result.put("open", false);
                result.put("error", "UDP yanit yok (timeout — acik/filtreli olabilir)");
            }
        }
    }

    /** Beklenen kalıp: boş -> 2xx/3xx; "200" tam; "2xx" sınıf; "200-399" aralık; virgül/boşluk ile çoklu. */
    static boolean httpStatusMatches(int code, String expect) {
        if (expect == null || expect.isBlank()) return code >= 200 && code < 400;
        for (String part : expect.split("[,\\s]+")) {
            String e = part.trim();
            if (e.isEmpty()) continue;
            try {
                if (e.contains("-")) {
                    String[] r = e.split("-", 2);
                    if (code >= Integer.parseInt(r[0].trim()) && code <= Integer.parseInt(r[1].trim())) return true;
                } else if (e.length() == 3 && e.toLowerCase().endsWith("xx")) {
                    if (code / 100 == Integer.parseInt(e.substring(0, 1))) return true;
                } else if (code == Integer.parseInt(e)) {
                    return true;
                }
            } catch (NumberFormatException ignore) { /* geçersiz parça atlanır */ }
        }
        return false;
    }

    private static String unescape(String s) {
        return s.replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t");
    }

    /** ipVersion v4/v6 → host'un o aileye ait ilk adresi; auto/null → null (JVM varsayılan çözümlemesi korunur). */
    private static InetAddress resolveFamily(String host, String ipVersion) throws UnknownHostException {
        if (ipVersion == null || ipVersion.isBlank() || "auto".equalsIgnoreCase(ipVersion)) return null;
        boolean wantV6 = "v6".equalsIgnoreCase(ipVersion);
        for (InetAddress a : InetAddress.getAllByName(host)) {
            if (wantV6 ? a instanceof Inet6Address : a instanceof Inet4Address) return a;
        }
        throw new UnknownHostException("No IP" + (wantV6 ? "v6" : "v4") + " address for " + host);
    }

    /**
     * addr set ise onunla (aile-kısıtlı) tek bağlantı; değilse (auto) çok-A: çözümlenen tüm IP'leri
     * sırayla dene, ilk TCP kabul edene bağlan (split-VIP host'ta yanlış IP'ye düşüp refused olmasın).
     */
    private static Socket connectAny(InetAddress addr, String host, int port, int timeoutMs) throws java.io.IOException {
        if (addr != null) {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(addr, port), timeoutMs);
            return s;
        }
        return NetworkResolver.connectFirstReachable(host, port, timeoutMs);
    }

    /** URL için IP literali (v6 köşeli parantez + zone-id kırpma). */
    private static String urlHost(InetAddress addr) {
        String ip = addr.getHostAddress();
        int z = ip.indexOf('%');
        if (z >= 0) ip = ip.substring(0, z);
        return addr instanceof Inet6Address ? "[" + ip + "]" : ip;
    }

    private static SSLContext trustAllContext() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{ new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());
        return ctx;
    }
}
