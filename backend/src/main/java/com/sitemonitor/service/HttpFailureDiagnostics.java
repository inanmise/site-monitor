package com.sitemonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP/Website kontrolü başarısız olduğunda "ne oldu, nerede, kimden kime, ne kadar bekledi"
 * sorularını cevaplayan yapısal tanı (2026-09-22, kullanıcı isteği: "timeout varsa hangi IP'den
 * hangi IP'ye hangi porta gitmeye çalışırken ne kadar bekledin").
 *
 * <p>Saf ve durumsuz: {@link Trace} çağrı sırasında toplanan bağlamı taşır (çözümlenen IP'ler,
 * pinlenen hedef, yönlendirme zinciri), {@link #forException}/{@link #forStatusMismatch} bunu
 * istisnanın sınıfına göre <b>evre</b> (DNS → TCP → TLS → istek → yanıt) ve <b>tür</b>e
 * indirger. Çıktı snake_case bir harita; {@code http_checks.error_detail} kolonunda JSON olarak
 * saklanır ve arayüz {@code kind} üzerinden açıklama/öneri metnini kendisi seçer (i18n).
 *
 * <p>Yalnız BAŞARISIZ kontrollerde üretilir: başarılı satıra tanı yazılmaz (tek pod, dakikada
 * bir kontrol × yüzlerce izleme — satır başına ~400 bayt gereksiz yük olurdu).
 *
 * <p>Kaynak IP'yi JDK HttpClient dışarı vermez; {@code DatagramSocket.connect} ile çekirdeğin
 * o hedef için seçeceği çıkış arayüzü sorulur (paket gönderilmez, yalnız yönlendirme kararı).
 */
public final class HttpFailureDiagnostics {

    private HttpFailureDiagnostics() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    /** İstisna zincirinde en fazla bu kadar halka yazılır (sonsuz/çok derin nedenlere karşı). */
    static final int MAX_CAUSES = 6;

    /** Kontrolün düştüğü evre. Sıra arayüzdeki şeridin sırasıdır. */
    public enum Phase { POLICY, DNS, CONNECT, TLS, REQUEST, RESPONSE, REDIRECT }

    /** Hata türü — arayüz açıklama/öneriyi bu anahtardan seçer. */
    public enum Kind {
        SSRF_BLOCKED(Phase.POLICY),
        CONFIG_ERROR(Phase.POLICY),
        DNS_UNRESOLVED(Phase.DNS),
        CONNECT_TIMEOUT(Phase.CONNECT),
        CONNECT_REFUSED(Phase.CONNECT),
        HOST_UNREACHABLE(Phase.CONNECT),
        PROXY_CONNECT(Phase.CONNECT),
        PROXY_AUTH(Phase.CONNECT),
        TLS_CERT_UNTRUSTED(Phase.TLS),
        TLS_HOSTNAME_MISMATCH(Phase.TLS),
        TLS_HANDSHAKE(Phase.TLS),
        RESPONSE_TIMEOUT(Phase.RESPONSE),
        CONNECTION_RESET(Phase.RESPONSE),
        CONNECTION_CLOSED(Phase.RESPONSE),
        PROTOCOL_ERROR(Phase.RESPONSE),
        TOO_MANY_REDIRECTS(Phase.REDIRECT),
        STATUS_MISMATCH(Phase.RESPONSE),
        INTERRUPTED(Phase.REQUEST),
        UNKNOWN(Phase.REQUEST);

        public final Phase phase;
        Kind(Phase p) { this.phase = p; }
    }

    /**
     * Kontrol boyunca toplanan bağlam. Çağıran ({@code HttpCheckerService}) çözümleme/pin/yönlendirme
     * anlarında doldurur; hata anında tanıya çevrilir. Alanlar null kalabilir (o noktaya gelinmedi).
     */
    public static final class Trace {
        public String url, method, scheme, host;
        public int port;
        public int timeoutMs;
        public boolean verifySsl, followRedirects;
        /** "direct" | "proxy" — ve vekil ise hedef gösterimi (host:port). */
        public String via, proxyTarget;
        public List<String> resolvedIps = new ArrayList<>();
        public Long dnsMs;
        /** Gerçekten bağlanılan (pinlenen) IP; çok-A pin yoksa ilk çözümlenen. */
        public String targetIp;
        public List<String> redirects = new ArrayList<>();
        public String expectedStatus;
        public Integer httpStatus;
        public long elapsedMs;

        public Trace start(String url, String method, int timeoutMs, boolean verifySsl, boolean followRedirects, String via, String proxyTarget) {
            this.url = url; this.method = method; this.timeoutMs = timeoutMs; this.verifySsl = verifySsl;
            this.followRedirects = followRedirects; this.via = via; this.proxyTarget = proxyTarget;
            try {
                URI u = URI.create(url.trim());
                this.scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
                this.host = u.getHost();
                boolean https = "https".equals(this.scheme);
                this.port = u.getPort() != -1 ? u.getPort() : (https ? 443 : 80);
            } catch (Exception ignore) { /* bozuk URL — tanı yine üretilir, host/port boş kalır */ }
            return this;
        }

        public void resolved(List<InetAddress> addrs, long ms) {
            this.dnsMs = ms;
            if (addrs != null) for (InetAddress a : addrs) resolvedIps.add(a.getHostAddress());
            if (targetIp == null && !resolvedIps.isEmpty()) targetIp = resolvedIps.get(0);
        }

        public void pinned(InetAddress ip) { if (ip != null) targetIp = ip.getHostAddress(); }

        public void hop(URI next) { if (next != null && redirects.size() <= SafeRedirect.MAX_HOPS + 1) redirects.add(next.toString()); }
    }

    // ── Sınıflandırma ────────────────────────────────────────────────────────────────────────

    /** İstisna zincirini (cause'lar dâhil) yürüyerek türü belirler. Bilinmeyen → UNKNOWN. */
    public static Kind classify(Throwable t) {
        if (t == null) return Kind.UNKNOWN;
        Throwable cur = t;
        int guard = 0;
        // Önce en DIŞTAKİ belirleyici sınıflar (HttpClient kendi zaman aşımlarını sarmaz)
        while (cur != null && guard++ < MAX_CAUSES + 2) {
            String cls = cur.getClass().getName();
            String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(Locale.ROOT);
            // SsrfGuard hedefi istekten ÖNCE çözer: çözülemeyen host da BlockedException olarak gelir — bu bir DNS hatasıdır, politika değil
            if (cur instanceof SsrfGuard.BlockedException)
                return cur.getMessage() != null && cur.getMessage().startsWith(SsrfGuard.UNRESOLVABLE_PREFIX) ? Kind.DNS_UNRESOLVED : Kind.SSRF_BLOCKED;
            if (cls.equals("java.net.http.HttpConnectTimeoutException")) return proxyAware(cur, Kind.CONNECT_TIMEOUT);
            if (cls.equals("java.net.http.HttpTimeoutException")) return Kind.RESPONSE_TIMEOUT;
            if (cur instanceof java.net.UnknownHostException) return Kind.DNS_UNRESOLVED;
            if (cur instanceof javax.net.ssl.SSLHandshakeException || cur instanceof javax.net.ssl.SSLException) {
                // Ad uyuşmazlığı ÖNCE: JDK bunu da "(certificate_unknown) No name matching …" diye sarar
                if (msg.contains("hostname") || msg.contains("subject alternative") || msg.contains("no name matching")) return Kind.TLS_HOSTNAME_MISMATCH;
                if (msg.contains("pkix") || msg.contains("unable to find valid certification path")
                        || msg.contains("certificate_unknown") || msg.contains("self signed") || msg.contains("self-signed")
                        || cur.getCause() instanceof java.security.cert.CertificateException) return Kind.TLS_CERT_UNTRUSTED;
                return Kind.TLS_HANDSHAKE;
            }
            if (cur instanceof java.security.cert.CertificateException) return Kind.TLS_CERT_UNTRUSTED;
            if (cur instanceof java.net.ConnectException) {
                if (msg.contains("refused")) return proxyAware(cur, Kind.CONNECT_REFUSED);
                if (msg.contains("timed out") || msg.contains("timeout")) return proxyAware(cur, Kind.CONNECT_TIMEOUT);
                if (msg.contains("unreachable") || msg.contains("no route")) return Kind.HOST_UNREACHABLE;
                return proxyAware(cur, Kind.CONNECT_REFUSED);
            }
            if (cur instanceof java.net.NoRouteToHostException) return Kind.HOST_UNREACHABLE;
            if (cur instanceof java.net.SocketTimeoutException) return msg.contains("connect") ? Kind.CONNECT_TIMEOUT : Kind.RESPONSE_TIMEOUT;
            if (cur instanceof java.nio.channels.ClosedChannelException) return Kind.CONNECTION_CLOSED;
            if (cur instanceof InterruptedException || cur instanceof java.nio.channels.ClosedByInterruptException) return Kind.INTERRUPTED;
            if (cur instanceof java.io.EOFException) return Kind.CONNECTION_CLOSED;
            if (cur instanceof java.net.SocketException) {
                if (msg.contains("reset")) return Kind.CONNECTION_RESET;
                if (msg.contains("broken pipe") || msg.contains("closed")) return Kind.CONNECTION_CLOSED;
                if (msg.contains("unreachable")) return Kind.HOST_UNREACHABLE;
            }
            if (cur instanceof java.io.IOException) {
                if (msg.contains("407") || msg.contains("proxy authentication")) return Kind.PROXY_AUTH;
                if (msg.contains("tunnel failed") || msg.contains("proxy")) return Kind.PROXY_CONNECT;
                if (msg.contains("çok fazla yönlendirme") || msg.contains("too many redirects")) return Kind.TOO_MANY_REDIRECTS;
                if (msg.contains("connection reset")) return Kind.CONNECTION_RESET;
                if (msg.contains("received no bytes") || msg.contains("connection closed") || msg.contains("eof")) return Kind.CONNECTION_CLOSED;
                if (msg.contains("protocol") || msg.contains("invalid") || msg.contains("malformed") || msg.contains("header parser")) return Kind.PROTOCOL_ERROR;
            }
            if (cur instanceof IllegalArgumentException && (msg.contains("uri") || msg.contains("url") || msg.contains("scheme"))) return Kind.CONFIG_ERROR;
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return Kind.UNKNOWN;
    }

    /** Vekil yolunda bağlantı hatası vekile aittir, hedefe değil — mesajda vekil izi varsa yeniden etiketle. */
    private static Kind proxyAware(Throwable t, Kind fallback) {
        String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
        return msg.contains("proxy") || msg.contains("tunnel") ? Kind.PROXY_CONNECT : fallback;
    }

    /** "Sınıf: mesaj" halkaları (en fazla {@link #MAX_CAUSES}); iç mesajlar arayüzde ham izdir. */
    public static List<String> causeChain(Throwable t) {
        List<String> out = new ArrayList<>();
        Throwable cur = t;
        while (cur != null && out.size() < MAX_CAUSES) {
            String m = cur.getMessage();
            out.add(cur.getClass().getName() + (m == null || m.isBlank() ? "" : ": " + m));
            cur = cur.getCause() == cur ? null : cur.getCause();
        }
        return out;
    }

    // ── Üretim ───────────────────────────────────────────────────────────────────────────────

    /** İstisnayla düşen kontrol için tanı haritası. */
    public static Map<String, Object> forException(Trace tr, Throwable t) {
        Kind k = classify(t);
        Map<String, Object> m = base(tr, k);
        m.put("exception", t == null ? null : t.getClass().getName());
        m.put("message", t == null ? null : t.getMessage());
        m.put("cause_chain", causeChain(t));
        return m;
    }

    /** Yanıt geldi ama beklenen durum koduyla eşleşmedi. */
    public static Map<String, Object> forStatusMismatch(Trace tr) {
        return base(tr, Kind.STATUS_MISMATCH);
    }

    private static Map<String, Object> base(Trace tr, Kind k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", k.name());
        m.put("phase", k.phase.name());
        m.put("url", tr.url);
        m.put("method", tr.method);
        m.put("scheme", tr.scheme);
        m.put("host", tr.host);
        m.put("port", tr.port);
        m.put("via", tr.via);
        m.put("proxy", tr.proxyTarget);
        m.put("resolved_ips", tr.resolvedIps);
        m.put("dns_ms", tr.dnsMs);
        // Vekil yolunda TCP hedefi vekildir: kaynak IP de ona göre seçilir
        String target = "proxy".equals(tr.via) && tr.proxyTarget != null ? hostOf(tr.proxyTarget) : tr.targetIp;
        int targetPort = "proxy".equals(tr.via) && tr.proxyTarget != null ? portOf(tr.proxyTarget, tr.port) : tr.port;
        m.put("target_ip", tr.targetIp);
        m.put("local_ip", localIpFor(target, targetPort));
        m.put("timeout_ms", tr.timeoutMs);
        m.put("elapsed_ms", tr.elapsedMs);
        m.put("verify_ssl", tr.verifySsl);
        m.put("follow_redirects", tr.followRedirects);
        m.put("redirects", tr.redirects);
        m.put("expected_status", tr.expectedStatus);
        m.put("http_status", tr.httpStatus);
        return m;
    }

    private static String hostOf(String hostPort) {
        int i = hostPort.lastIndexOf(':');
        return i > 0 ? hostPort.substring(0, i) : hostPort;
    }

    private static int portOf(String hostPort, int def) {
        int i = hostPort.lastIndexOf(':');
        try { return i > 0 ? Integer.parseInt(hostPort.substring(i + 1)) : def; } catch (NumberFormatException e) { return def; }
    }

    /**
     * Çekirdeğin bu hedef için seçeceği çıkış arayüzünün adresi. Paket GÖNDERİLMEZ (UDP connect yalnız
     * yönlendirme tablosuna bakar). Hedef çözümlenemiyorsa/izin yoksa null — tanı yine üretilir.
     */
    static String localIpFor(String targetHostOrIp, int port) {
        if (targetHostOrIp == null || targetHostOrIp.isBlank()) return null;
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(new InetSocketAddress(InetAddress.getByName(targetHostOrIp), port > 0 ? port : 80));
            InetAddress a = s.getLocalAddress();
            return a == null || a.isAnyLocalAddress() ? null : a.getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    /** Haritayı JSON'a çevirir; başarısızlıkta null (kayıt yine yazılır, yalnız tanı düşer). */
    public static String toJson(Map<String, Object> detail) {
        if (detail == null) return null;
        try { return JSON.writeValueAsString(detail); } catch (Exception e) { return null; }
    }
}
