package com.sitemonitor.service;

import com.sitemonitor.model.PortMonitor;
import lombok.RequiredArgsConstructor;
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
 * Sonuç: {@code {open, response_ms, error?, detail?, via}}.
 *
 * <p><b>Vekil (2026-09-24):</b> izleme {@code useProxy} ile kurumsal vekil üzerinden de denetlenebilir — TCP/TLS/BANNER
 * {@link ProxySettings#openConnectTunnel} ile açılan {@code CONNECT host:port} tünelinden, HTTP türü aynı tünelde elle
 * GET ile. UDP HTTP vekilinden GEÇEMEZ → her zaman doğrudan. Kurumsal vekiller CONNECT'i çoğunlukla yalnız belirli
 * portlara açar ({@link #CONNECT_PORTS_KEY}, vars. 443,8443); vekil reddederse sonuç "vekil izin vermedi" diye ayrı
 * yazılır ({@code proxy_refused}) ki "port kapalı" sanılmasın. Vekil yolunda "açık" = hedefe VEKİLİN ağından erişilebilir.
 * Hedef doğrulaması (SSRF) vekil yolunda da hedef adına uygulanır — HTTP izlemesiyle aynı.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortCheckerService {

    private final SsrfGuard ssrfGuard;

    /** Vekil bağımlılıkları İSTEĞE BAĞLI (testler / vekilsiz kurulum): yoksa her kontrol doğrudan. */
    @org.springframework.beans.factory.annotation.Autowired(required = false) private ProxyPolicyService proxyPolicy;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private ProxySettings proxySettings;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private AppSettingsService appSettings;

    /** Vekilin CONNECT tüneline izin verdiği portlar (Ayarlar → İzleme); formda kullanıcıya gösterilir, retçe eklenir. */
    public static final String CONNECT_PORTS_KEY = "site.monitor.proxy.connect-ports";
    static final String DEFAULT_CONNECT_PORTS = "443,8443";

    /** Vekil tanımlı mı (host + port). */
    public boolean proxyConfigured() { return proxySettings != null && proxySettings.enabled(); }

    /** Vekilin tünel açtığı bilinen portlar — ayar bozuksa varsayılan. */
    public List<Integer> proxyConnectPorts() {
        List<String> raw = appSettings != null ? appSettings.getCsv(CONNECT_PORTS_KEY, DEFAULT_CONNECT_PORTS)
                : List.of(DEFAULT_CONNECT_PORTS.split(","));
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        for (String p : raw) {
            try { int v = Integer.parseInt(p.trim()); if (v >= 1 && v <= 65535) out.add(v); } catch (Exception ignored) { /* bozuk parça atlanır */ }
        }
        if (out.isEmpty()) for (String p : DEFAULT_CONNECT_PORTS.split(",")) out.add(Integer.parseInt(p));
        return new java.util.ArrayList<>(out);
    }

    /**
     * Port izlemesinin vekil kararı. null/bilinmeyen kip = OFF (mevcut kayıtlar doğrudan). UDP istense de doğrudan:
     * {@code wanted=true, bypassed=true} döner ki arayüz "UDP vekilden geçemez" diyebilsin.
     */
    public ProxyPolicyService.Decision proxyDecision(String host, String protocol, String mode) {
        String m = ProxyPolicyService.normalizeModeDefaultOff(mode);
        if (proxyPolicy == null || ProxyPolicyService.OFF.equals(m)) return ProxyPolicyService.Decision.direct("monitor");
        ProxyPolicyService.Decision d = proxyPolicy.decideForHost(host == null ? null : host.trim().toLowerCase(java.util.Locale.ROOT), m);
        if (d.viaProxy() && "UDP".equals(normalizeType(protocol))) return new ProxyPolicyService.Decision(false, d.source(), true, true);
        return d;
    }

    static String normalizeType(String type) { return type != null ? type.trim().toUpperCase(java.util.Locale.ROOT) : "TCP"; }

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String host, int port, int timeoutMs) {
        return CompletableFuture.completedFuture(check(host, port, timeoutMs));
    }

    /** Geriye dönük uyumluluk — düz TCP connect (eski çağrılar/testler). */
    public Map<String, Object> check(String host, int port, int timeoutMs) {
        return check(host, port, timeoutMs, "TCP", null, null);
    }

    /** Monitor tipine göre kontrol (IP sürümü + vekil tercihi dahil) — zamanlayıcı ve elle kontrol buradan geçer. */
    public Map<String, Object> check(PortMonitor m) {
        boolean viaProxy = proxyDecision(m.getHost(), m.getProtocol(), m.getUseProxy()).viaProxy();
        return check(m.getHost(), m.getPort(),
                m.getTimeoutMs() != null ? m.getTimeoutMs() : 5000,
                m.getProtocol(), m.getSendData(), m.getExpect(),
                m.getIpVersion(), viaProxy);
    }

    /** Geriye uyum: IP sürümü belirtilmeden (auto). */
    public Map<String, Object> check(String host, int port, int timeoutMs, String type, String send, String expect) {
        return check(host, port, timeoutMs, type, send, expect, "auto");
    }

    public Map<String, Object> check(String host, int port, int timeoutMs, String type, String send, String expect, String ipVersion) {
        return check(host, port, timeoutMs, type, send, expect, ipVersion, false);
    }

    /** @param viaProxy vekil üzerinden (karar {@link #proxyDecision}); UDP'de ve vekil tanımsızken yok sayılır. */
    public Map<String, Object> check(String host, int port, int timeoutMs, String type, String send, String expect, String ipVersion, boolean viaProxy) {
        String t = normalizeType(type);
        boolean proxied = viaProxy && !"UDP".equals(t) && proxyConfigured();
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("via", proxied ? "proxy" : "direct");
        // SSRF: hedefi bağlanmadan ÖNCE doğrula (cloud-metadata/loopback/link-local blok; iç ağ ayara bağlı).
        // Doğrulanan IP'lere bağlanılır → DNS-rebind kapanır.
        List<InetAddress> vetted;
        try {
            vetted = ssrfGuard.validate(host);
        } catch (SsrfGuard.BlockedException be) {
            result.put("open", false);
            result.put("response_ms", null);
            result.put("error", be.getMessage());
            return result;
        }
        if (proxied) {
            try {
                checkViaProxy(t, host, port, timeoutMs, send, expect, result);
                if (Boolean.TRUE.equals(result.get("open")) && result.get("response_ms") == null) {
                    result.put("response_ms", System.currentTimeMillis() - start);
                }
                if (!result.containsKey("response_ms")) result.put("response_ms", null);
            } catch (Exception e) {
                result.put("open", false);
                result.put("response_ms", null);
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (msg.startsWith("vekil tüneli reddetti")) {
                    // "port kapalı" DEĞİL: vekil bu porta tünel açmadı — izinli portlar mesajda
                    result.put("proxy_refused", true);
                    msg = msg + " — vekil bu porta tünel açmıyor olabilir (izinli: "
                            + proxyConnectPorts().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", ")) + ")";
                }
                result.put("error", msg);
                log.debug("Port check via proxy ({}) failed for {}:{}: {}", t, host, port, e.toString());
            }
            return result;
        }
        try {
            InetAddress addr = resolveFamily(vetted, ipVersion);   // vetted'i aileye göre süz; null → auto (çok-A)
            switch (t) {
                case "TLS"    -> doTls(addr, vetted, host, port, timeoutMs, result);
                case "HTTP"   -> doHttp(addr, host, port, timeoutMs, send, expect, result);
                case "BANNER" -> doBanner(addr, vetted, port, timeoutMs, send, expect, result);
                case "UDP"    -> doUdp(addr, vetted, port, timeoutMs, send, result);
                default        -> doTcp(addr, vetted, port, timeoutMs, result);
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

    // ── Vekil yolu (2026-09-24) ─────────────────────────────────────────────────────────────
    private void checkViaProxy(String t, String host, int port, int timeoutMs, String send, String expect, Map<String, Object> result) throws Exception {
        try (Socket tunnel = proxySettings.openConnectTunnel(host, port, timeoutMs)) {
            tunnel.setSoTimeout(timeoutMs);
            switch (t) {
                case "TLS" -> {
                    try (SSLSocket ssl = tlsOver(tunnel, host, port, timeoutMs)) {
                        result.put("open", true);
                        result.put("detail", "TLS " + ssl.getSession().getProtocol() + " · vekil üzerinden");
                    }
                }
                case "HTTP" -> httpOver(tunnel, host, port, timeoutMs, send, expect, result);
                case "BANNER" -> bannerOn(tunnel, send, expect, result);
                default -> {                       // TCP: vekil tüneli açtıysa hedef port (vekilden) erişilebilir
                    result.put("open", true);
                    result.put("detail", "vekil üzerinden bağlandı");
                }
            }
        }
    }

    private static SSLSocket tlsOver(Socket raw, String host, int port, int timeoutMs) throws Exception {
        SSLSocket ssl = (SSLSocket) TRUST_ALL_FACTORY.createSocket(raw, host, port, true);
        ssl.setSoTimeout(timeoutMs);
        try {
            SSLParameters p = ssl.getSSLParameters();
            p.setServerNames(List.of(new SNIHostName(host)));
            ssl.setSSLParameters(p);
        } catch (Exception ignore) { /* SNI opsiyonel */ }
        ssl.startHandshake();
        return ssl;
    }

    /** Tünelde elle HTTP GET — yalnız durum satırı okunur (gövde yok, yönlendirme izlenmez: doğrudan yolla aynı). */
    private static void httpOver(Socket tunnel, String host, int port, int timeoutMs, String path, String expect, Map<String, Object> result) throws Exception {
        boolean https = port == 443 || port == 8443;
        String p = (path != null && !path.isBlank()) ? path.trim() : "/";
        if (!p.startsWith("/")) p = "/" + p;
        Socket s = https ? tlsOver(tunnel, host, port, timeoutMs) : tunnel;
        try {
            String req = "GET " + p + " HTTP/1.1\r\nHost: " + ((port == 80 || port == 443) ? host : host + ":" + port)
                    + "\r\nUser-Agent: SiteMonitor-PortCheck\r\nConnection: close\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            String status = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII)).readLine();
            if (status == null || !status.startsWith("HTTP/") || status.length() < 12) throw new java.io.IOException("geçersiz HTTP yanıtı: " + status);
            int code = Integer.parseInt(status.substring(9, 12));
            boolean ok = httpStatusMatches(code, expect);
            result.put("open", ok);
            result.put("detail", "HTTP " + code + " · vekil üzerinden");
            if (!ok) result.put("error", "HTTP " + code + (expect != null && !expect.isBlank() ? " (beklenen: " + expect.trim() + ")" : ""));
        } finally {
            if (s != tunnel) s.close();
        }
    }

    private void doTcp(InetAddress addr, List<InetAddress> vetted, int port, int timeoutMs, Map<String, Object> result) throws Exception {
        try (Socket s = connectAny(addr, vetted, port, timeoutMs)) {
            result.put("open", true);
        }
    }

    private void doTls(InetAddress addr, List<InetAddress> vetted, String host, int port, int timeoutMs, Map<String, Object> result) throws Exception {
        try (Socket raw = connectAny(addr, vetted, port, timeoutMs)) {
            SSLSocketFactory f = TRUST_ALL_FACTORY;
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
            hc.setSSLSocketFactory(TRUST_ALL_FACTORY);
            hc.setHostnameVerifier((h, s) -> true);
        }
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", "SiteMonitor-PortCheck");
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

    private void doBanner(InetAddress addr, List<InetAddress> vetted, int port, int timeoutMs, String send, String expect, Map<String, Object> result) throws Exception {
        try (Socket s = connectAny(addr, vetted, port, timeoutMs)) {
            s.setSoTimeout(timeoutMs);
            bannerOn(s, send, expect, result);
        }
    }

    /** Bağlı sokette (doğrudan ya da vekil tüneli) gönder-oku-eşleştir. */
    private static void bannerOn(Socket s, String send, String expect, Map<String, Object> result) throws Exception {
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

    private void doUdp(InetAddress addr, List<InetAddress> vetted, int port, int timeoutMs, String send, Map<String, Object> result) throws Exception {
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setSoTimeout(timeoutMs);
            byte[] payload = (send != null && !send.isEmpty())
                    ? unescape(send).getBytes(StandardCharsets.ISO_8859_1) : new byte[]{0};
            InetAddress target = addr != null ? addr : vetted.get(0);   // doğrulanan IP'ye gönder (rebind kapalı)
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

    /** ipVersion v4/v6 → doğrulanan adresler içinden o aileye ait ilki; auto/null → null (çok-A yolu korunur).
     *  SsrfGuard'ın döndürdüğü listeyi süzer (yeniden çözmez → rebind kapalı). */
    private static InetAddress resolveFamily(List<InetAddress> vetted, String ipVersion) throws UnknownHostException {
        if (ipVersion == null || ipVersion.isBlank() || "auto".equalsIgnoreCase(ipVersion)) return null;
        boolean wantV6 = "v6".equalsIgnoreCase(ipVersion);
        for (InetAddress a : vetted) {
            if (wantV6 ? a instanceof Inet6Address : a instanceof Inet4Address) return a;
        }
        throw new UnknownHostException("No IP" + (wantV6 ? "v6" : "v4") + " address");
    }

    /**
     * addr set ise onunla (aile-kısıtlı) tek bağlantı; değilse (auto) çok-A: DOĞRULANAN IP'leri sırayla dene,
     * ilk TCP kabul edene bağlan (split-VIP host'ta yanlış IP'ye düşüp refused olmasın; SsrfGuard sonrası rebind yok).
     */
    private static Socket connectAny(InetAddress addr, List<InetAddress> vetted, int port, int timeoutMs) throws java.io.IOException {
        if (addr != null) {
            return NetworkResolver.connectSingle(new InetSocketAddress(addr, port), timeoutMs);
        }
        return NetworkResolver.connectFirstReachable(vetted, port, timeoutMs);
    }

    /** URL için IP literali (v6 köşeli parantez + zone-id kırpma). */
    private static String urlHost(InetAddress addr) {
        String ip = addr.getHostAddress();
        int z = ip.indexOf('%');
        if (z >= 0) ip = ip.substring(0, z);
        return addr instanceof Inet6Address ? "[" + ip + "]" : ip;
    }

    /** Trust-all SSLSocketFactory'yi BİR KEZ kur (leak analizi #3) — her check'te SSLContext.init (SecureRandom
     *  reseed + provider init) pahalı CPU churn'dü; erişilebilirlik probu için trust-all zaten kasıtlı. */
    private static final SSLSocketFactory TRUST_ALL_FACTORY = buildTrustAllFactory();

    private static SSLSocketFactory buildTrustAllFactory() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }
}
