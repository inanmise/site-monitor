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
 * Port izleme kontrolleri. Tip ({@code protocol}) baz alinir:
 * <ul>
 *   <li><b>TCP</b> — soket connect (port acik mi).</li>
 *   <li><b>TLS</b> — TLS handshake basarili mi (sunucu sertifika sunuyor mu).</li>
 *   <li><b>HTTP</b> — HTTP(S) GET; donen durum kodu beklenen kalibi tutuyor mu (vars. 2xx/3xx). 443/8443 -> https.</li>
 *   <li><b>BANNER</b> — baglan, (varsa) veri gonder, sunucu yanitini oku, beklenen alt-dizgeyi dogrula.</li>
 *   <li><b>UDP</b> — datagram gonder; yanit/ICMP'ye bakar (baglantisiz oldugundan sonuc guvenilir degildir).</li>
 * </ul>
 * Sonuc: {@code {open, response_ms, error?, detail?}}.
 */
@Slf4j
@Service
public class PortCheckerService {

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String host, int port, int timeoutMs) {
        return CompletableFuture.completedFuture(check(host, port, timeoutMs));
    }

    /** Geriye donuk uyumluluk — duz TCP connect (eski cagrilar/testler). */
    public Map<String, Object> check(String host, int port, int timeoutMs) {
        return check(host, port, timeoutMs, "TCP", null, null);
    }

    /** Monitor tipine gore kontrol. */
    public Map<String, Object> check(PortMonitor m) {
        return check(m.getHost(), m.getPort(),
                m.getTimeoutMs() != null ? m.getTimeoutMs() : 5000,
                m.getProtocol(), m.getSendData(), m.getExpect());
    }

    public Map<String, Object> check(String host, int port, int timeoutMs, String type, String send, String expect) {
        String t = type != null ? type.trim().toUpperCase() : "TCP";
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            switch (t) {
                case "TLS"    -> doTls(host, port, timeoutMs, result);
                case "HTTP"   -> doHttp(host, port, timeoutMs, send, expect, result);
                case "BANNER" -> doBanner(host, port, timeoutMs, send, expect, result);
                case "UDP"    -> doUdp(host, port, timeoutMs, send, result);
                default        -> doTcp(host, port, timeoutMs, result);
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

    private void doTcp(String host, int port, int timeoutMs, Map<String, Object> result) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            result.put("open", true);
        }
    }

    private void doTls(String host, int port, int timeoutMs, Map<String, Object> result) throws Exception {
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
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

    private void doHttp(String host, int port, int timeoutMs, String path, String expect, Map<String, Object> result) throws Exception {
        boolean https = port == 443 || port == 8443;
        String p = (path != null && !path.isBlank()) ? path.trim() : "/";
        if (!p.startsWith("/")) p = "/" + p;
        URL url = URI.create((https ? "https" : "http") + "://" + host + ":" + port + p).toURL();
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

    private void doBanner(String host, int port, int timeoutMs, String send, String expect, Map<String, Object> result) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
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

    private void doUdp(String host, int port, int timeoutMs, String send, Map<String, Object> result) throws Exception {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setSoTimeout(timeoutMs);
            byte[] payload = (send != null && !send.isEmpty())
                    ? unescape(send).getBytes(StandardCharsets.ISO_8859_1) : new byte[]{0};
            InetAddress addr = InetAddress.getByName(host);
            ds.send(new DatagramPacket(payload, payload.length, addr, port));
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

    /** Beklenen kalip: bos -> 2xx/3xx; "200" tam; "2xx" sinif; "200-399" aralik; virgul/bosluk ile coklu. */
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
            } catch (NumberFormatException ignore) { /* gecersiz parca atlanir */ }
        }
        return false;
    }

    private static String unescape(String s) {
        return s.replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t");
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
