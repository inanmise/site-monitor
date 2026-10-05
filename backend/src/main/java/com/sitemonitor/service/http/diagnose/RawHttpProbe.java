package com.sitemonitor.service.http.diagnose;

import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.HttpCheckerService;
import com.sitemonitor.service.NetworkResolver;
import com.sitemonitor.service.SafeRedirect;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.http.HttpRequestOptions;
import com.sitemonitor.service.http.HttpRequestRules;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tek bir YOLUN (vekil | doğrudan) ham soket HTTP/1.1 yoklaması — HTTP uçtan uca tanılaması (2026-10-02).
 *
 * <p><b>Neden ham soket.</b> İzlemenin gerçek istemcisi (java.net.http) "request timed out" der ama NEREDE takıldığını
 * söylemez. Prod vakası: düz http, "her zaman vekil" kipindeki bir izleme, pod başka düğüme taşındıktan sonra takılıyordu;
 * aynı pod siteye doğrudan 24 ms'de (HTTP 401) ulaşıyordu. Burada her adım ayrı ölçülür ve kaydedilir: DNS (tüm
 * adresler), TCP (her deneme), vekil (mutlak biçim istek ya da CONNECT tüneli — giden/gelen satırlar), TLS (protokol,
 * şifre, ALPN, SNI, tam zincir, güven, ad eşleşmesi), giden istek satırı + başlıklar (maskeli), yanıt durum satırı +
 * başlıklar + süre + 32 KB metin önizlemesi, her yönlendirme adımı ve takıldığı yer ({@code failed_step}).
 *
 * <p><b>İzlemeyle aynı istek.</b> Yöntem, URL, User-Agent, Host, gelişmiş istek eklentileri ({@link HttpRequestOptions};
 * yalnız İLK host'a — {@code HttpCheckerService.sendFollowing} ile aynı kural), yönlendirme politikası
 * ({@link SafeRedirect}, her hop'ta {@link SsrfGuard}). Farklar bilinçli: {@code Connection: close} (hop başına yeni
 * bağlantı) ve {@code Accept-Encoding} YOK (gövde düz gelsin, önizlenebilsin). ALPN yalnız {@code http/1.1} önerir —
 * yazıcı HTTP/1.1 konuşur; izleme istemcisinin müzakere ettiği sürüm {@code client_check.http_version}'da görünür.
 *
 * <p><b>Süre.</b> Her hop'ta bağlantı + tünel + TLS + istek + yanıt başlıkları {@code timeoutMs} içinde (izlemenin istek
 * zaman aşımı); gövde başlıklardan sonra en çok {@code timeoutMs} daha (izlemenin gövde bütçesi); yolun tamamı sabit
 * {@code hardDeadline}'ı aşamaz. Her okuma kalan süreyle sınırlanır; ayrıca bir bekçi soketi son tarihte kapatır (yazma
 * tıkanması dâhil hiçbir şey süresiz bekleyemez).
 *
 * <p><b>Yan etkisiz.</b> Hiçbir şey yazmaz, CA pinlemez ({@link CaAutoPinService} yalnız OKUNUR: mevcut pinli CA güven
 * hesabına katılır), alarm değerlendirmesine girmez. Sınıf durum taşır — her yol için YENİ örnek.
 */
public final class RawHttpProbe {   // public (2026-10-04): keyword uçtan uca tanılaması da aynı ham ölçümü kullanır

    /** Metin önizlemesi tavanı (sözleşme: ilk 32 KB). */
    static final int PREVIEW_BYTES = 32 * 1024;
    /** Okunacak gövde tavanı — izlemenin JSON doğrulama tavanıyla aynı (2 MB). */
    public static final int BODY_CAP_BYTES = HttpRequestRules.MAX_RESPONSE_BYTES;
    static final int MAX_LINE = 16 * 1024;
    static final int MAX_HEADERS = 200;
    /** İzlemenin gerçek istemcisinin gönderdiği User-Agent ({@code HttpCheckerService.buildRequest}). */
    static final String USER_AGENT = "SiteMonitor-HttpMonitor/1.0";

    private static final Pattern STATUS_LINE = Pattern.compile("^(HTTP/\\d(?:\\.\\d)?)\\s+(\\d{3})(?:\\s+(.*))?$");

    /**
     * Yolun yapılandırması.
     *
     * @param key          {@code monitor} | {@code alternate}
     * @param route        {@code proxy} | {@code direct}
     * @param hardDeadline yolun mutlak son anı (epoch ms) — genel 60 sn tavanından türetilir
     * @param userAgent    gönderilecek User-Agent (izlemenin GERÇEK istemcisiyle aynı olmalı; HTTP: {@value #USER_AGENT})
     * @param captureBody  son yanıtın gövdesi TAM (tavana kadar) tutulsun — keyword tanılaması gövdede arar
     * @param profile      sayfa çekirdeğinin istek profili (Sayfa Bütünlüğü / Sayfa Hızı tanılaması, 2026-10-05); null →
     *                     HTTP/keyword davranışı AYNEN (çıktı değişmez)
     */
    public record Spec(String key, String route, String url, String method, String expectedStatus, int timeoutMs,
                boolean verifySsl, boolean followRedirects, HttpRequestOptions opts,
                String proxyHost, int proxyPort, String proxyAuthHeader, long hardDeadline,
                String userAgent, boolean captureBody, Profile profile) {
        /** HTTP izlemesinin kurucusu (2026-10-02 sözleşmesi) — User-Agent HTTP izlemesininki, gövde yalnız JSON doğrulamasında. */
        public Spec(String key, String route, String url, String method, String expectedStatus, int timeoutMs,
                    boolean verifySsl, boolean followRedirects, HttpRequestOptions opts,
                    String proxyHost, int proxyPort, String proxyAuthHeader, long hardDeadline) {
            this(key, route, url, method, expectedStatus, timeoutMs, verifySsl, followRedirects, opts,
                    proxyHost, proxyPort, proxyAuthHeader, hardDeadline, USER_AGENT, false, null);
        }
        /** Keyword izlemesinin kurucusu (2026-10-04 sözleşmesi) — profil yok. */
        public Spec(String key, String route, String url, String method, String expectedStatus, int timeoutMs,
                    boolean verifySsl, boolean followRedirects, HttpRequestOptions opts,
                    String proxyHost, int proxyPort, String proxyAuthHeader, long hardDeadline,
                    String userAgent, boolean captureBody) {
            this(key, route, url, method, expectedStatus, timeoutMs, verifySsl, followRedirects, opts,
                    proxyHost, proxyPort, proxyAuthHeader, hardDeadline, userAgent, captureBody, null);
        }
        public boolean viaProxy() { return "proxy".equals(route); }
        int maxHops() { return profile != null ? Math.max(0, profile.maxRedirects()) : SafeRedirect.MAX_HOPS; }
    }

    /**
     * Sayfa çekirdeğinin ({@code PageFetchCore}) istek profili — Sayfa Bütünlüğü ve Sayfa Hızı uçtan uca tanılaması
     * (2026-10-05). İzlemenin GERÇEK istemcisi HTTP izlemesinden farklı davranır; ham ölçüm aynısını göndermezse tanı
     * başka bir isteği anlatır:
     * <ul>
     *   <li>{@code baseHeaders}: HER hop'ta gönderilen tarayıcı-benzeri başlıklar (Accept, Accept-Language, Accept-Encoding)
     *       — izlemenin ek başlıkları bunları EZEMEZ (çağıran ek başlıkları önceden süzer);</li>
     *   <li>{@code maxRedirects}: yönlendirme sınırı (çekirdek: 5);</li>
     *   <li>{@code followDowngrade}: https→http yönlendirmesi takip edilir mi (çekirdek bilinçli olarak takip eder).</li>
     * </ul>
     * {@code Accept-Encoding} gönderildiğinde gövde telde sıkıştırılmış gelir: bayt sayısı TELDEKİ boyuttur (Sayfa Hızı
     * ölçümüyle aynı), metin önizlemesi ise yalnız önizleme için açılır ({@code content_encoding} ayrıca yazılır).
     */
    public record Profile(Map<String, String> baseHeaders, int maxRedirects, boolean followDowngrade) {}

    /** Yolun terminal hatası: bulgu kodu + adlı parametreler + düştüğü adım + istisna. */
    public record Failure(String code, Map<String, Object> params, String step, Throwable error) {}

    private final Spec spec;
    private final SsrfGuard ssrfGuard;
    private final TrustEvaluator trustEvaluator;
    private final CaAutoPinService caAutoPin;   // null olabilir (test); yalnız OKUNUR
    private final HttpDiagMasker masker;
    private final ScheduledExecutorService watchdog;   // null → bekçi yok (yalnız okuma son tarihleri)

    // ── Sonuç ───────────────────────────────────────────────────────────────────────────────────
    final List<Map<String, Object>> hops = new ArrayList<>();
    final List<String> transcript = new ArrayList<>();
    Failure failure;
    Integer finalStatus;
    /** Son yanıtın gövdesi (yalnız JSON doğrulaması için tam, diğerlerinde null). */
    byte[] finalBody;
    boolean finalBodyCapped;
    /** Gövde süre içinde bitmedi (başlıklar geldi) — izleme durum koduyla karar verir. */
    boolean bodyTimedOut;
    long bodyTimeoutMs;
    long totalMs;
    Long dnsMs, connectMs, proxyMs, tlsMs, ttfbMs, downloadMs;
    boolean tunnelApplicable, tlsApplicable;

    public RawHttpProbe(Spec spec, SsrfGuard ssrfGuard, TrustEvaluator trustEvaluator, CaAutoPinService caAutoPin,
                 HttpDiagMasker masker, ScheduledExecutorService watchdog) {
        this.spec = spec;
        this.ssrfGuard = ssrfGuard;
        this.trustEvaluator = trustEvaluator;
        this.caAutoPin = caAutoPin;
        this.masker = masker;
        this.watchdog = watchdog;
    }

    // ── Paket dışı okuma (keyword tanılaması, 2026-10-04) — alanların kendisi paket içi kalır ──────
    public List<Map<String, Object>> hops() { return hops; }
    public List<String> transcript() { return transcript; }
    public Failure failure() { return failure; }
    public Integer finalStatus() { return finalStatus; }
    /** Son yanıtın gövdesi — yalnız {@code captureBody} ya da JSON doğrulamasında dolu (tavanla sınırlı). */
    public byte[] finalBody() { return finalBody; }
    public boolean finalBodyCapped() { return finalBodyCapped; }
    public boolean bodyTimedOut() { return bodyTimedOut; }
    public long totalMs() { return totalMs; }

    /** Zaman çizelgesi — HTTP tanılamasının sözleşmesiyle AYNI biçim (uygulanmayan faz 0, ulaşılmayan null). */
    public Map<String, Object> timeline() {
        Map<String, Object> tl = new LinkedHashMap<>();
        tl.put("dns_ms", dnsMs);
        tl.put("connect_ms", connectMs);
        tl.put("proxy_ms", tunnelApplicable ? proxyMs : Long.valueOf(0L));
        tl.put("tls_ms", tlsApplicable ? tlsMs : Long.valueOf(0L));
        tl.put("ttfb_ms", ttfbMs);
        tl.put("download_ms", downloadMs);
        tl.put("total_ms", totalMs);
        return tl;
    }

    /** Süre tavanında bitmeyen yol — boş bir sonuçla "yanıt takıldı" olarak raporlanır (HTTP tanılamasıyla aynı kural). */
    public static RawHttpProbe abandoned(Spec spec, HttpDiagMasker masker, long elapsedMs, long capMs) {
        RawHttpProbe p = new RawHttpProbe(spec, null, null, null, masker, null);
        p.totalMs = elapsedMs;
        p.transcript.add("* Diagnostic time limit reached (" + capMs + " ms) — path abandoned");
        p.failure = new Failure(HttpDiagFindings.RESPONSE_TIMEOUT,
                params("ms", elapsedMs, "route", spec.route()), "response",
                new java.util.concurrent.TimeoutException("tanılama süre sınırı (" + capMs + " ms)"));
        return p;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Yol
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Yolu koşturur; sonuç alanları doldurulur. Asla fırlatmaz. */
    public RawHttpProbe run() {
        long start = System.currentTimeMillis();
        try {
            URI base = URI.create(spec.url().trim());
            String method = spec.method() == null ? "GET" : spec.method().trim().toUpperCase(Locale.ROOT);
            String originHost = base.getHost();
            URI current = base;
            int maxHops = spec.maxHops();
            boolean followDowngrade = spec.profile() != null && spec.profile().followDowngrade();
            for (int hop = 0; hop <= maxHops; hop++) {
                boolean origin = !spec.followRedirects()
                        || (originHost != null && originHost.equalsIgnoreCase(current.getHost()));
                HopResult hr = runHop(hop, current, method, origin);
                if (hr.status != null) finalStatus = hr.status;   // gövde hatasında da durum kodu biliniyor
                if (failure != null || hr.status == null) break;
                if (!spec.followRedirects() || !SafeRedirect.isRedirect(hr.status)) break;
                URI next = SafeRedirect.nextHop(current, hr.location);
                Map<String, Object> redirect = new LinkedHashMap<>();
                redirect.put("status", hr.status);
                redirect.put("location", hr.location == null ? null : SecretMask.maskUrlQuery(hr.location));
                redirect.put("next_url", next == null ? null : SecretMask.maskUrlQuery(next.toString()));
                boolean crossHost = next != null && current.getHost() != null && !current.getHost().equalsIgnoreCase(next.getHost());
                redirect.put("cross_host", crossHost);
                boolean extras = spec.opts() != null && !spec.opts().isEmpty();
                redirect.put("extras_dropped", next != null && extras && originHost != null
                        && !originHost.equalsIgnoreCase(next.getHost()));
                hr.hop.put("redirect", redirect);
                // Takip edilemeyen hedef (şema dışı / host'suz / https→http düşürmesi) → 3xx OLDUĞU GİBİ son yanıttır.
                if (next == null || (SafeRedirect.isDowngrade(current, next) && !followDowngrade)) {
                    transcript.add("* Redirect not followed (" + (next == null ? "unsupported Location" : "https→http downgrade") + ")");
                    break;
                }
                if (hop == maxHops) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("hops", maxHops);
                    failure = new Failure("REDIRECT_LOOP", p, "response",
                            new IOException("çok fazla yönlendirme (" + maxHops + " hop aşıldı)"));
                    transcript.add("* Too many redirects (" + maxHops + " hops)");
                    break;
                }
                transcript.add("* Following redirect → " + SecretMask.maskUrlQuery(next.toString()));
                method = SafeRedirect.nextMethod(hr.status, method);
                current = next;
            }
        } catch (IllegalArgumentException e) {
            failure = new Failure("DNS_FAIL", params("host", null, "error", "geçersiz URL"), "dns", e);
        } catch (RuntimeException e) {
            failure = new Failure("RESPONSE_TIMEOUT", params("ms", spec.timeoutMs(), "route", spec.route(), "reason", "error"),
                    "response", e);
        }
        totalMs = System.currentTimeMillis() - start;
        return this;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Tek hop
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    private static final class HopResult {
        final Map<String, Object> hop;
        Integer status;
        String location;
        HopResult(Map<String, Object> hop) { this.hop = hop; }
    }

    private HopResult runHop(int index, URI uri, String method, boolean origin) {
        long hopStart = System.currentTimeMillis();
        long headerDeadline = Math.min(hopStart + spec.timeoutMs(), spec.hardDeadline());
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        String host = uri.getHost();
        String bareHost = bare(host);
        int port = uri.getPort() != -1 ? uri.getPort() : (https ? 443 : 80);
        boolean viaProxy = spec.viaProxy();
        boolean tunnel = viaProxy && https;
        if (tunnel) tunnelApplicable = true;
        if (https) tlsApplicable = true;
        ttfbMs = null;        // zaman çizelgesinin ttfb/indirme alanları SON hop'u anlatır
        downloadMs = null;

        Map<String, Object> hop = new LinkedHashMap<>();
        HopResult hr = new HopResult(hop);
        Steps steps = new Steps(viaProxy, tunnel, https);
        hop.put("index", index);
        hop.put("url", SecretMask.maskUrlQuery(uri.toString()));
        hop.put("method", method);
        hop.put("steps", steps.list);
        hop.put("dns", null);
        hop.put("tcp", null);
        hop.put("proxy", null);
        hop.put("tls", null);
        hop.put("request", null);
        hop.put("response", null);
        hop.put("redirect", null);
        hops.add(hop);
        if (index > 0) transcript.add("*");

        // ── 1) DNS + SSRF (izlemeyle aynı: her hop çözülür ve politikadan geçer — vekil yolunda da) ──
        Map<String, Object> dns = new LinkedHashMap<>();
        dns.put("host", host);
        dns.put("addresses", new ArrayList<String>());
        dns.put("ms", null);
        dns.put("via_proxy", viaProxy);
        dns.put("error", null);
        hop.put("dns", dns);
        transcript.add("* Resolving " + host + (viaProxy ? " (the proxy resolves it again on its side)" : ""));
        long t0 = System.currentTimeMillis();
        List<InetAddress> addrs;
        try {
            if (host == null || host.isBlank()) throw new SsrfGuard.BlockedException("geçersiz hedef: " + uri);
            addrs = ssrfGuard.validate(bareHost);
            long ms = System.currentTimeMillis() - t0;
            List<String> ips = new ArrayList<>();
            for (InetAddress a : addrs) ips.add(a.getHostAddress());
            dns.put("addresses", ips);
            dns.put("ms", ms);
            dnsMs = add(dnsMs, ms);
            steps.done("dns", "ok", ms);
            transcript.add("* Resolved " + host + " → " + String.join(", ", ips) + " (" + ms + " ms)");
        } catch (SsrfGuard.UnresolvableHostException e) {
            long ms = System.currentTimeMillis() - t0;
            dns.put("ms", ms);
            dns.put("error", e.getMessage());
            steps.done("dns", "fail", ms);
            transcript.add("* Could not resolve host: " + host);
            failure = new Failure("DNS_FAIL", params("host", host, "error", e.getMessage()), "dns", e);
            return hr;
        } catch (SsrfGuard.BlockedException e) {
            long ms = System.currentTimeMillis() - t0;
            dns.put("ms", ms);
            dns.put("error", e.getMessage());
            steps.done("dns", "fail", ms);
            transcript.add("* Blocked by the SSRF policy: " + e.getMessage());
            failure = new Failure("SSRF_BLOCKED", params("host", host), "dns", e);
            return hr;
        }

        // ── 2) TCP (doğrudan: hedef adresler sırayla; vekil: vekil adresi) ──
        Socket raw = null;
        Socket active = null;
        AtomicBoolean watchdogFired = new AtomicBoolean(false);
        ScheduledFuture<?> guard = null;
        try {
            Map<String, Object> tcp = new LinkedHashMap<>();
            List<Map<String, Object>> attempts = new ArrayList<>();
            tcp.put("remote", null);
            tcp.put("local", null);
            tcp.put("ms", null);
            tcp.put("attempts", attempts);
            hop.put("tcp", tcp);
            String connectStep = viaProxy ? "proxy_connect" : "tcp";
            Map<String, Object> proxy = null;
            if (viaProxy) {
                proxy = new LinkedHashMap<>();
                proxy.put("host", spec.proxyHost());
                proxy.put("port", spec.proxyPort());
                proxy.put("mode", tunnel ? "connect" : "absolute");
                proxy.put("connect_request", null);
                proxy.put("connect_response", null);
                proxy.put("ms", tunnel ? null : 0L);
                hop.put("proxy", proxy);
            }
            long c0 = System.currentTimeMillis();
            try {
                List<InetAddress> targets;
                int targetPort;
                if (viaProxy) {
                    transcript.add("* Connecting to proxy " + spec.proxyHost() + ":" + spec.proxyPort());
                    try {
                        targets = List.of(InetAddress.getAllByName(spec.proxyHost()));
                    } catch (IOException e) {
                        throw new ConnectException("vekil adı çözülemedi: " + spec.proxyHost() + " (" + e.getMessage() + ")");
                    }
                    targetPort = spec.proxyPort();
                } else {
                    targets = addrs;
                    targetPort = port;
                }
                raw = connect(targets, targetPort, headerDeadline, attempts);
                long ms = System.currentTimeMillis() - c0;
                tcp.put("remote", fmt(raw.getInetAddress(), raw.getPort()));
                tcp.put("local", fmt(raw.getLocalAddress(), raw.getLocalPort()));
                tcp.put("ms", ms);
                connectMs = add(connectMs, ms);
                steps.done(connectStep, "ok", ms);
                transcript.add("* Connected to " + tcp.get("remote") + " (" + ms + " ms, local " + tcp.get("local") + ")");
            } catch (IOException e) {
                long ms = System.currentTimeMillis() - c0;
                tcp.put("ms", ms);
                steps.done(connectStep, "fail", ms);
                String lastAddr = attempts.isEmpty() ? (viaProxy ? spec.proxyHost() + ":" + spec.proxyPort() : host + ":" + port)
                        : String.valueOf(attempts.get(attempts.size() - 1).get("address"));
                transcript.add("* Failed to connect to " + lastAddr + ": " + msg(e) + " (" + ms + " ms)");
                if (viaProxy) {
                    failure = new Failure("PROXY_CONNECT_FAIL", params("proxy", spec.proxyHost() + ":" + spec.proxyPort()),
                            "proxy_connect", e);
                } else if (isRefused(e)) {
                    failure = new Failure("TCP_REFUSED", params("address", lastAddr), "tcp", e);
                } else {
                    failure = new Failure("TCP_TIMEOUT", params("address", lastAddr, "ms", ms), "tcp", e);
                }
                return hr;
            }
            // Bekçi: hop'un mutlak son anında soketi kapatır (yazma tıkanması, damlatan el sıkışma…).
            long hopHard = Math.min(spec.hardDeadline(), hopStart + 2L * spec.timeoutMs() + 1000L);
            final Socket toClose = raw;
            if (watchdog != null) {
                guard = watchdog.schedule(() -> { watchdogFired.set(true); closeQuietly(toClose); },
                        Math.max(1L, hopHard - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            }
            active = raw;

            // ── 3) CONNECT tüneli (vekil + https) ──
            if (tunnel) {
                long p0 = System.currentTimeMillis();
                String target = host + ":" + port;
                List<String> reqLines = new ArrayList<>();
                reqLines.add("CONNECT " + target + " HTTP/1.1");
                reqLines.add("Host: " + target);
                StringBuilder wire = new StringBuilder();
                wire.append("CONNECT ").append(target).append(" HTTP/1.1\r\n").append("Host: ").append(target).append("\r\n");
                if (spec.proxyAuthHeader() != null) {
                    wire.append("Proxy-Authorization: ").append(spec.proxyAuthHeader()).append("\r\n");
                    reqLines.add(masker.requestHeaderLine("Proxy-Authorization", spec.proxyAuthHeader()));
                }
                wire.append("\r\n");
                proxy.put("connect_request", reqLines);
                for (String l : reqLines) transcript.add("> " + l);
                transcript.add(">");
                try {
                    OutputStream out = raw.getOutputStream();
                    out.write(wire.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Wire w = new Wire(raw, raw.getInputStream(), 1);   // bayt bayt: tünelden sonraki baytlar TLS'e ait
                    w.deadline = headerDeadline;
                    String statusLine = w.readLine(MAX_LINE);
                    if (statusLine == null) throw new EOFException("vekil CONNECT'e yanıt vermeden bağlantıyı kapattı");
                    List<Map<String, Object>> hdrs = new ArrayList<>();
                    transcript.add("< " + statusLine);
                    readHeaders(w, hdrs, null);
                    transcript.add("<");
                    Integer st = parseStatus(statusLine);
                    Map<String, Object> cr = new LinkedHashMap<>();
                    cr.put("status_line", statusLine);
                    cr.put("status", st);
                    cr.put("headers", hdrs);
                    proxy.put("connect_response", cr);
                    long ms = System.currentTimeMillis() - p0;
                    proxy.put("ms", ms);
                    proxyMs = add(proxyMs, ms);
                    if (st == null || st != 200) {
                        steps.done("proxy_tunnel", "fail", ms);
                        String px = spec.proxyHost() + ":" + spec.proxyPort();
                        failure = st != null && st == 407
                                ? new Failure("PROXY_AUTH_REQUIRED", params("proxy", px), "proxy_tunnel",
                                        new IOException("vekil kimlik istedi: " + statusLine))
                                : new Failure("PROXY_TUNNEL_REFUSED", params("proxy", px, "status", st), "proxy_tunnel",
                                        new IOException("vekil tüneli reddetti: " + statusLine));
                        return hr;
                    }
                    steps.done("proxy_tunnel", "ok", ms);
                    transcript.add("* CONNECT tunnel established (" + ms + " ms)");
                } catch (IOException e) {
                    long ms = System.currentTimeMillis() - p0;
                    proxy.put("ms", ms);
                    steps.done("proxy_tunnel", "fail", ms);
                    String px = spec.proxyHost() + ":" + spec.proxyPort();
                    if (isTimeout(e, watchdogFired)) {
                        transcript.add("* No CONNECT response from the proxy within " + spec.timeoutMs() + " ms");
                        failure = new Failure("RESPONSE_TIMEOUT", params("ms", spec.timeoutMs(), "route", spec.route()),
                                "proxy_tunnel", e);
                    } else {
                        transcript.add("* CONNECT failed: " + msg(e));
                        failure = new Failure("PROXY_TUNNEL_REFUSED", params("proxy", px, "status", null), "proxy_tunnel", e);
                    }
                    return hr;
                }
            }

            // ── 4) TLS ──
            if (https) {
                Map<String, Object> tls = new LinkedHashMap<>();
                hop.put("tls", tls);
                long h0 = System.currentTimeMillis();
                CapturingTrustManager ctm = new CapturingTrustManager();
                boolean ipLiteral = NetworkResolver.isIpLiteral(bareHost);
                tls.put("protocol", null);
                tls.put("cipher", null);
                tls.put("alpn", null);
                tls.put("sni", ipLiteral ? null : bareHost);
                tls.put("handshake_ms", null);
                tls.put("trusted", null);
                tls.put("trust_error", null);
                tls.put("hostname_match", null);
                tls.put("chain", new ArrayList<>());
                SSLSocket ssl;
                try {
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, new TrustManager[]{ ctm }, null);
                    ssl = (SSLSocket) ctx.getSocketFactory().createSocket(raw, bareHost, port, true);
                    SSLParameters p = ssl.getSSLParameters();
                    if (!ipLiteral) {
                        try {
                            p.setServerNames(List.of(new SNIHostName(bareHost)));
                        } catch (IllegalArgumentException badSni) {   // ör. alt çizgili ad: SNI'siz dene (JDK istemcisi gibi)
                            tls.put("sni", null);
                        }
                    }
                    p.setApplicationProtocols(new String[]{ "http/1.1" });
                    p.setEndpointIdentificationAlgorithm(null);   // ad eşleşmesi aşağıda AYRI hesaplanır
                    ssl.setSSLParameters(p);
                    ssl.setSoTimeout((int) Math.max(1L, headerDeadline - System.currentTimeMillis()));
                    transcript.add("* TLS handshake (SNI " + (ipLiteral ? "-" : bareHost) + ", ALPN http/1.1)");
                    ssl.startHandshake();
                    active = ssl;
                } catch (Exception e) {
                    long ms = System.currentTimeMillis() - h0;
                    tls.put("handshake_ms", ms);
                    tls.put("chain", chainInfo(ctm.chain));
                    steps.done("tls", "fail", ms);
                    transcript.add("* TLS handshake failed: " + msg(e) + " (" + ms + " ms)");
                    failure = new Failure("TLS_HANDSHAKE_FAIL",
                            params("error", isTimeout(e, watchdogFired) ? "zaman aşımı (" + spec.timeoutMs() + " ms)" : msg(e)),
                            "tls", e);
                    return hr;
                }
                long ms = System.currentTimeMillis() - h0;
                tlsMs = add(tlsMs, ms);
                SSLSession session = ssl.getSession();
                String alpn = ssl.getApplicationProtocol();
                X509Certificate[] chain = ctm.chain;
                if (chain == null) {
                    try {
                        java.security.cert.Certificate[] pc = session.getPeerCertificates();
                        List<X509Certificate> xs = new ArrayList<>();
                        for (java.security.cert.Certificate c : pc) if (c instanceof X509Certificate x) xs.add(x);
                        chain = xs.toArray(new X509Certificate[0]);
                    } catch (Exception ignore) { chain = new X509Certificate[0]; }
                }
                tls.put("protocol", session.getProtocol());
                tls.put("cipher", session.getCipherSuite());
                tls.put("alpn", alpn == null || alpn.isEmpty() ? null : alpn);
                tls.put("handshake_ms", ms);
                String[] trust = trust(chain, bareHost, port);
                boolean trusted = trust[0] == null;
                boolean nameOk = chain.length > 0 && hostnameMatches(chain[0], bareHost);
                tls.put("trusted", trusted);
                tls.put("trust_error", trust[0]);
                tls.put("hostname_match", nameOk);
                tls.put("chain", chainInfo(chain));
                transcript.add("* TLS " + session.getProtocol() + " / " + session.getCipherSuite()
                        + " / ALPN " + (alpn == null || alpn.isEmpty() ? "-" : alpn) + " (" + ms + " ms)");
                if (chain.length > 0) {
                    X509Certificate leaf = chain[0];
                    transcript.add("* Server certificate: " + leaf.getSubjectX500Principal().getName()
                            + " | issuer: " + leaf.getIssuerX500Principal().getName()
                            + " | expires " + leaf.getNotAfter().toInstant().truncatedTo(ChronoUnit.SECONDS)
                            + " (" + daysLeft(leaf) + " days)");
                }
                transcript.add("* Certificate chain " + (trusted ? "trusted" : "NOT trusted: " + trust[0])
                        + "; hostname " + (nameOk ? "matches" : "does NOT match") + " " + bareHost
                        + (spec.verifySsl() ? "" : " (verify_ssl off — the monitor does not enforce trust)"));
                if (spec.verifySsl()) {
                    Long leafDays = chain.length > 0 ? daysLeft(chain[0]) : null;
                    if (leafDays != null && leafDays < 0) {
                        steps.done("tls", "fail", ms);
                        failure = new Failure("TLS_EXPIRED", params("days_left", leafDays), "tls",
                                new CertificateException("sertifikanın süresi dolmuş (" + leafDays + " gün)"));
                        return hr;
                    }
                    if (!trusted) {
                        steps.done("tls", "fail", ms);
                        failure = new Failure("TLS_UNTRUSTED", params("error", trust[0]), "tls",
                                new CertificateException(trust[0]));
                        return hr;
                    }
                    if (!nameOk) {
                        steps.done("tls", "fail", ms);
                        failure = new Failure("TLS_HOSTNAME_MISMATCH", params("host", bareHost), "tls",
                                new CertificateException("sertifika bu adı kapsamıyor: " + bareHost));
                        return hr;
                    }
                }
                steps.done("tls", "ok", ms);
            }

            // ── 5) İstek ──
            HttpRequestOptions opts = spec.opts() == null ? HttpRequestOptions.NONE : spec.opts();
            boolean extras = origin && !opts.isEmpty();
            boolean sendBody = extras && opts.sendsBody(method);
            byte[] body = sendBody ? opts.body().getBytes(StandardCharsets.UTF_8) : null;
            List<String[]> headers = new ArrayList<>();
            putHeader(headers, "Host", hostHeader(uri, https));
            putHeader(headers, "User-Agent", spec.userAgent() == null || spec.userAgent().isBlank() ? USER_AGENT : spec.userAgent());
            if (spec.profile() != null && spec.profile().baseHeaders() != null) {   // sayfa çekirdeği: her hop'ta
                for (Map.Entry<String, String> h : spec.profile().baseHeaders().entrySet()) {
                    if (h.getKey() != null && h.getValue() != null) putHeader(headers, h.getKey(), h.getValue());
                }
            }
            if (extras) {
                String auth = HttpRequestRules.basicAuthHeader(opts.basicAuthUser(), opts.basicAuthPass());
                if (auth != null) putHeader(headers, "Authorization", auth);
                if (sendBody) putHeader(headers, "Content-Type", opts.effectiveContentType());
                // Kullanıcı başlıkları EN SON (izlemeyle aynı): aynı adlı başlık öncekini ezer.
                for (Map.Entry<String, String> h : HttpRequestRules.parseHeaders(opts.headers()).entrySet()) {
                    putHeader(headers, h.getKey(), h.getValue());
                }
            }
            if (viaProxy && !https && spec.proxyAuthHeader() != null) {
                putHeader(headers, "Proxy-Authorization", spec.proxyAuthHeader());
            }
            if ("POST".equals(method)) putHeader(headers, "Content-Length", String.valueOf(body == null ? 0 : body.length));
            putHeader(headers, "Connection", "close");
            String target = (viaProxy && !https) ? absoluteTarget(uri) : originTarget(uri);
            String requestLine = method + " " + target + " HTTP/1.1";
            StringBuilder head = new StringBuilder(requestLine).append("\r\n");
            List<Map<String, Object>> shownHeaders = new ArrayList<>();
            String shownLine = method + " " + SecretMask.maskUrlQuery(target) + " HTTP/1.1";
            transcript.add("> " + shownLine);
            for (String[] h : headers) {
                head.append(h[0]).append(": ").append(h[1]).append("\r\n");
                shownHeaders.add(masker.requestHeader(h[0], h[1]));
                transcript.add("> " + masker.requestHeaderLine(h[0], h[1]));
            }
            head.append("\r\n");
            transcript.add(">");
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("line", shownLine);
            request.put("headers", shownHeaders);
            request.put("body_bytes", body == null ? 0 : body.length);
            request.put("sent_ms", null);
            hop.put("request", request);
            long r0 = System.currentTimeMillis();
            try {
                OutputStream out = active.getOutputStream();
                out.write(head.toString().getBytes(StandardCharsets.UTF_8));
                if (body != null) out.write(body);
                out.flush();
            } catch (IOException e) {
                long ms = System.currentTimeMillis() - r0;
                request.put("sent_ms", ms);
                steps.done("request", "fail", ms);
                transcript.add("* Sending the request failed: " + msg(e));
                failure = new Failure("RESPONSE_TIMEOUT",
                        params("ms", spec.timeoutMs(), "route", spec.route(), "reason", isTimeout(e, watchdogFired) ? "timeout" : "closed"),
                        "request", e);
                return hr;
            }
            long sentMs = System.currentTimeMillis() - r0;
            request.put("sent_ms", sentMs);
            steps.done("request", "ok", sentMs);
            if (body != null) transcript.add("* Request body sent (" + body.length + " bytes)");

            // ── 6) Yanıt başlıkları ──
            long waitStart = System.currentTimeMillis();
            Wire w;
            String statusLine;
            Integer status;
            String version;
            List<Map<String, Object>> respHeaders = new ArrayList<>();
            Map<String, String> firstValues = new LinkedHashMap<>();
            long ttfb;
            try {
                w = new Wire(active, active.getInputStream(), 8192);
                w.deadline = headerDeadline;
                while (true) {
                    statusLine = w.readLine(MAX_LINE);
                    if (statusLine == null) throw new EOFException("sunucu yanıt vermeden bağlantıyı kapattı");
                    Matcher sm = STATUS_LINE.matcher(statusLine);
                    if (!sm.matches()) throw new IOException("geçersiz durum satırı: " + abbreviate(statusLine, 120));
                    version = sm.group(1);
                    status = Integer.parseInt(sm.group(2));
                    ttfb = System.currentTimeMillis() - waitStart;
                    transcript.add("< " + statusLine);
                    respHeaders.clear();
                    firstValues.clear();
                    readHeaders(w, respHeaders, firstValues);
                    transcript.add("<");
                    if (status >= 100 && status < 200 && status != 101) continue;   // 100/103: asıl yanıtı bekle
                    break;
                }
            } catch (IOException e) {
                long ms = System.currentTimeMillis() - waitStart;
                steps.done("response", "fail", ms);
                boolean timeout = isTimeout(e, watchdogFired);
                if (timeout) {
                    transcript.add("* No response within " + spec.timeoutMs() + " ms");
                } else {
                    transcript.add("* Connection closed / failed before a response: " + msg(e) + " (" + ms + " ms)");
                }
                failure = new Failure("RESPONSE_TIMEOUT",
                        timeout ? params("ms", spec.timeoutMs(), "route", spec.route())
                                : params("ms", ms, "route", spec.route(), "reason", e instanceof EOFException ? "closed" : "error"),
                        "response", e);
                return hr;
            }
            long headersAt = System.currentTimeMillis();
            steps.done("response", "ok", headersAt - waitStart);
            hr.status = status;
            hr.location = firstValues.get("location");
            ttfbMs = ttfb;
            String contentType = firstValues.get("content-type");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status_line", statusLine);
            response.put("status", status);
            response.put("http_version", version);
            response.put("headers", respHeaders);
            response.put("ttfb_ms", ttfb);
            response.put("body", null);
            hop.put("response", response);

            // ── 7) Gövde ──
            boolean noBody = "HEAD".equals(method) || status == 204 || status == 304 || status == 101;
            if (noBody) {
                steps.done("body", "skip", null);
                downloadMs = null;
                return hr;
            }
            long bodyDeadline = Math.min(headersAt + spec.timeoutMs(), spec.hardDeadline());
            w.deadline = bodyDeadline;
            boolean wantFull = opts.hasJsonAssertion() || spec.captureBody();
            BodySink sink = new BodySink(wantFull);
            String te = firstValues.get("transfer-encoding");
            String cl = firstValues.get("content-length");
            boolean chunked = te != null && te.toLowerCase(Locale.ROOT).contains("chunked");
            long contentLength = -1;
            if (!chunked && cl != null) {
                try { contentLength = Long.parseLong(cl.trim()); } catch (NumberFormatException ignore) { contentLength = -1; }
            }
            boolean complete = false;
            String bodyError = null;
            boolean timedOut = false;
            Throwable bodyEx = null;
            try {
                if (chunked) complete = readChunked(w, sink);
                else if (contentLength >= 0) complete = readFixed(w, sink, contentLength);
                else complete = readToEof(w, sink);
            } catch (IOException e) {
                bodyEx = e;
                if (isTimeout(e, watchdogFired)) timedOut = true;
                else bodyError = msg(e);
            }
            long dl = System.currentTimeMillis() - headersAt;
            downloadMs = dl;
            boolean capped = sink.capped;
            Map<String, Object> bodyMap = new LinkedHashMap<>();
            bodyMap.put("bytes", sink.total);
            bodyMap.put("complete", complete && !capped && bodyEx == null);
            bodyMap.put("content_type", contentType);
            // Sayfa çekirdeği profili Accept-Encoding gönderir → gövde telde sıkıştırılmış olabilir: sayım teldeki bayt,
            // önizleme açılmış metin. Profil yoksa (HTTP/keyword) bu dal hiç çalışmaz — çıktı aynı.
            String contentEncoding = spec.profile() != null ? firstValues.get("content-encoding") : null;
            byte[] previewBytes = sink.preview.toByteArray();
            if (contentEncoding != null) previewBytes = inflatePreview(previewBytes, contentEncoding);
            boolean text = previewBytes != null && isText(contentType, previewBytes);
            bodyMap.put("text", text);
            bodyMap.put("preview", text ? masker.maskBody(decode(previewBytes, contentType)) : null);
            bodyMap.put("preview_truncated", text && (sink.total > PREVIEW_BYTES
                    || (contentEncoding != null && previewBytes.length >= PREVIEW_BYTES)));
            bodyMap.put("download_ms", dl);
            if (spec.profile() != null) bodyMap.put("content_encoding", contentEncoding);
            response.put("body", bodyMap);
            finalBody = wantFull ? sink.full.toByteArray() : null;
            finalBodyCapped = capped;
            if (timedOut) {
                bodyTimedOut = true;
                bodyTimeoutMs = spec.timeoutMs();
                steps.done("body", "fail", dl);
                transcript.add("* Body not finished within " + spec.timeoutMs() + " ms (" + sink.total + " bytes read)");
            } else if (bodyError != null) {
                steps.done("body", "fail", dl);
                transcript.add("* Body read failed after " + sink.total + " bytes: " + bodyError);
                failure = new Failure("BODY_TIMEOUT", params("ms", dl, "reason", "error"), "body", bodyEx);
            } else {
                steps.done("body", "ok", dl);
                transcript.add("* Body: " + sink.total + " bytes in " + dl + " ms"
                        + (capped ? " (read stopped at the " + (BODY_CAP_BYTES / 1_000_000) + " MB cap)" : complete ? "" : " (incomplete)"));
            }
            return hr;
        } finally {
            if (guard != null) guard.cancel(false);
            closeQuietly(active);
            if (active != raw) closeQuietly(raw);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Yardımcılar
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Adresleri sırayla dener — {@link NetworkResolver#connectFirstReachable} ile aynı kural, deneme başına kayıt. */
    private Socket connect(List<InetAddress> addrs, int port, long deadline, List<Map<String, Object>> attempts) throws IOException {
        if (addrs == null || addrs.isEmpty()) throw new ConnectException("boş adres listesi");
        boolean multi = addrs.size() > 1;
        int limit = multi ? Math.min(addrs.size(), NetworkResolver.MAX_A_ATTEMPTS) : 1;
        IOException last = null;
        for (int i = 0; i < limit; i++) {
            long rem = deadline - System.currentTimeMillis();
            if (rem <= 0) {
                if (last == null) last = new SocketTimeoutException("Connect timed out");
                break;
            }
            int per = (int) (multi ? Math.min(rem, NetworkResolver.CONNECT_CAP_MS) : rem);
            InetSocketAddress target = new InetSocketAddress(addrs.get(i), port);
            String shown = fmt(addrs.get(i), port);
            transcript.add("* Trying " + shown + "...");
            Socket s = new Socket();
            long a0 = System.currentTimeMillis();
            try {
                s.connect(target, Math.max(1, per));
                attempts.add(attempt(shown, System.currentTimeMillis() - a0, null));
                return s;
            } catch (IOException e) {
                attempts.add(attempt(shown, System.currentTimeMillis() - a0, msg(e)));
                closeQuietly(s);
                last = e;
            }
        }
        throw last != null ? last : new ConnectException("bağlanılamadı");
    }

    private static Map<String, Object> attempt(String address, long ms, String error) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("address", address);
        a.put("ms", ms);
        a.put("error", error);
        return a;
    }

    /** Başlık satırlarını okur (katlanmış satırlar bir öncekine eklenir); {@code first} = küçük-harf ad → ilk değer. */
    private void readHeaders(Wire w, List<Map<String, Object>> out, Map<String, String> first) throws IOException {
        List<String[]> raw = new ArrayList<>();
        int count = 0;
        while (true) {
            String line = w.readLine(MAX_LINE);
            if (line == null || line.isEmpty()) break;
            if ((line.startsWith(" ") || line.startsWith("\t")) && !raw.isEmpty()) {
                String[] prev = raw.get(raw.size() - 1);
                prev[1] = prev[1] + " " + line.trim();
                continue;
            }
            if (++count > MAX_HEADERS) throw new IOException("aşırı başlık (" + MAX_HEADERS + "+)");
            int c = line.indexOf(':');
            String name = c > 0 ? line.substring(0, c).trim() : line.trim();
            String value = c > 0 ? line.substring(c + 1).trim() : "";
            raw.add(new String[]{ name, value });
        }
        for (String[] h : raw) {
            out.add(masker.responseHeader(h[0], h[1]));
            transcript.add("< " + masker.responseHeaderLine(h[0], h[1]));
            if (first != null) first.putIfAbsent(h[0].toLowerCase(Locale.ROOT), h[1]);
        }
    }

    private boolean readFixed(Wire w, BodySink sink, long length) throws IOException {
        byte[] buf = new byte[8192];
        long left = length;
        while (left > 0) {
            int n = w.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new EOFException("gövde Content-Length'ten önce bitti (" + (length - left) + "/" + length + " bayt)");
            left -= n;
            if (!sink.accept(buf, n)) return false;
        }
        return true;
    }

    private boolean readToEof(Wire w, BodySink sink) throws IOException {
        byte[] buf = new byte[8192];
        while (true) {
            int n = w.read(buf, 0, buf.length);
            if (n < 0) return true;
            if (!sink.accept(buf, n)) return false;
        }
    }

    private boolean readChunked(Wire w, BodySink sink) throws IOException {
        byte[] buf = new byte[8192];
        while (true) {
            String sizeLine = w.readLine(MAX_LINE);
            if (sizeLine == null) throw new EOFException("parçalı gövde erken bitti");
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            long size;
            try { size = Long.parseLong(hex, 16); } catch (NumberFormatException e) { throw new IOException("geçersiz parça boyu: " + abbreviate(hex, 40)); }
            if (size == 0) {
                while (true) {   // trailer'lar
                    String t = w.readLine(MAX_LINE);
                    if (t == null || t.isEmpty()) break;
                }
                return true;
            }
            long left = size;
            while (left > 0) {
                int n = w.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new EOFException("parçalı gövde erken bitti");
                left -= n;
                if (!sink.accept(buf, n)) return false;
            }
            w.readLine(MAX_LINE);   // parça sonu CRLF
        }
    }

    /** Gövde toplayıcı: ilk 32 KB önizleme, JSON doğrulaması için tam (2 MB tavan), sayım tavanı 2 MB. */
    private static final class BodySink {
        final ByteArrayOutputStream preview = new ByteArrayOutputStream();
        final ByteArrayOutputStream full;
        long total;
        boolean capped;

        BodySink(boolean wantFull) { this.full = wantFull ? new ByteArrayOutputStream() : null; }

        /** @return okumaya devam edilsin mi (tavan dolunca false) */
        boolean accept(byte[] b, int n) {
            int take = (int) Math.min(n, (long) BODY_CAP_BYTES - total);
            if (take <= 0) { capped = true; return false; }
            int p = Math.min(take, PREVIEW_BYTES - preview.size());
            if (p > 0) preview.write(b, 0, p);
            if (full != null) full.write(b, 0, take);
            total += take;
            if (take < n || total >= BODY_CAP_BYTES) {   // tavan doldu — okumayı kes (gövde "tamam" sayılmaz)
                capped = true;
                return false;
            }
            return true;
        }
    }

    /** Soket okuyucu: her doldurmadan önce soket zaman aşımı KALAN süreye ayarlanır (toplam son tarih). */
    static final class Wire {
        private final Socket socket;
        private final InputStream in;
        private final byte[] buf;
        private int pos;
        private int lim;
        long deadline;

        Wire(Socket socket, InputStream in, int bufSize) {
            this.socket = socket;
            this.in = in;
            this.buf = new byte[Math.max(1, bufSize)];
        }

        private int fill() throws IOException {
            long rem = deadline - System.currentTimeMillis();
            if (rem <= 0) throw new SocketTimeoutException("Read timed out (deadline)");
            socket.setSoTimeout((int) Math.max(1L, Math.min(rem, Integer.MAX_VALUE)));
            int n = in.read(buf, 0, buf.length);
            if (n < 0) return -1;
            pos = 0;
            lim = n;
            return n;
        }

        int readByte() throws IOException {
            if (pos >= lim && fill() < 0) return -1;
            return buf[pos++] & 0xff;
        }

        int read(byte[] dst, int off, int len) throws IOException {
            if (pos >= lim && fill() < 0) return -1;
            int n = Math.min(len, lim - pos);
            System.arraycopy(buf, pos, dst, off, n);
            pos += n;
            return n;
        }

        /** CRLF/LF ile biten satır; akış bittiyse ve satır boşsa null. */
        String readLine(int max) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = readByte()) != -1) {
                if (b == '\n') break;
                if (line.size() >= max) throw new IOException("aşırı uzun satır (" + max + "+ bayt)");
                line.write(b);
            }
            if (b == -1 && line.size() == 0) return null;
            String s = line.toString(StandardCharsets.ISO_8859_1);
            return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
        }
    }

    /** El sıkışmayı ASLA reddetmeyen, zinciri yakalayan güven yöneticisi; güven ayrıca hesaplanır ({@link #trust}). */
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

    /**
     * İzlemenin strict yolunun güveni: JVM cacerts → kurumsal CA paketi ({@link TrustEvaluator}) → host'un MEVCUT pinli
     * CA'sı ({@link CaAutoPinService#trustManagerForHost}, salt okuma). {@code [0]} = null → güvenilir; aksi hâlde neden.
     */
    private String[] trust(X509Certificate[] chain, String host, int port) {
        if (chain == null || chain.length == 0) return new String[]{ "sunucu sertifika göndermedi" };
        String reason;
        try {
            TrustEvaluator.TrustResult tr = trustEvaluator.evaluate(chain);
            if (tr.trusted()) return new String[]{ null };
            reason = tr.reason();
        } catch (Exception e) {
            reason = msg(e);
        }
        if (caAutoPin != null) {
            try {
                X509TrustManager pinned = caAutoPin.trustManagerForHost(host, port);
                if (pinned != null) {
                    pinned.checkServerTrusted(chain, chain[0].getPublicKey().getAlgorithm());
                    return new String[]{ null };
                }
            } catch (Exception ignore) { /* pin de güvenmiyor — ilk neden kalır */ }
        }
        return new String[]{ reason == null ? "untrusted" : reason };
    }

    /** Ad eşleşmesi: DNS adı → izlemenin kuralı ({@link HttpCheckerService#hostnameMatches}); IP → SAN iPAddress. */
    static boolean hostnameMatches(X509Certificate cert, String host) {
        if (cert == null || host == null) return false;
        if (NetworkResolver.isIpLiteral(host)) {
            try {
                Collection<List<?>> sans = cert.getSubjectAlternativeNames();
                if (sans == null) return false;
                InetAddress want = InetAddress.getByName(host);
                for (List<?> e : sans) {
                    if (Integer.valueOf(7).equals(e.get(0)) && e.get(1) != null
                            && InetAddress.getByName(String.valueOf(e.get(1))).equals(want)) return true;
                }
            } catch (Exception ignore) { /* eşleşme yok */ }
            return false;
        }
        return HttpCheckerService.hostnameMatches(cert, host);
    }

    static List<Map<String, Object>> chainInfo(X509Certificate[] chain) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (chain == null) return out;
        for (X509Certificate c : chain) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subject", c.getSubjectX500Principal().getName());
            m.put("issuer", c.getIssuerX500Principal().getName());
            m.put("not_before", c.getNotBefore().toInstant().truncatedTo(ChronoUnit.SECONDS).toString());
            m.put("not_after", c.getNotAfter().toInstant().truncatedTo(ChronoUnit.SECONDS).toString());
            m.put("days_left", daysLeft(c));
            m.put("san", sanList(c));
            m.put("sig_alg", c.getSigAlgName());
            m.put("key", keyInfo(c));
            m.put("sha256", fingerprint(c));
            out.add(m);
        }
        return out;
    }

    static long daysLeft(X509Certificate c) {
        return Math.floorDiv(Duration.between(Instant.now(), c.getNotAfter().toInstant()).toSeconds(), 86_400L);
    }

    private static List<String> sanList(X509Certificate c) {
        List<String> out = new ArrayList<>();
        try {
            Collection<List<?>> sans = c.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> e : sans) {
                    Object type = e.get(0);
                    if ((Integer.valueOf(2).equals(type) || Integer.valueOf(7).equals(type)) && e.get(1) != null) {
                        out.add(String.valueOf(e.get(1)));
                    }
                }
            }
        } catch (Exception ignore) { /* SAN yok */ }
        return out;
    }

    private static String keyInfo(X509Certificate c) {
        java.security.PublicKey k = c.getPublicKey();
        if (k instanceof RSAPublicKey r) return "RSA " + r.getModulus().bitLength();
        if (k instanceof ECPublicKey e) return "EC " + e.getParams().getCurve().getField().getFieldSize();
        return k.getAlgorithm();
    }

    private static String fingerprint(X509Certificate c) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Aynı adlı başlığı (büyük/küçük harf duyarsız) YERİNDE ezer — {@code HttpRequest.Builder.setHeader} anlamı. */
    private static void putHeader(List<String[]> headers, String name, String value) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) { h[1] = value; return; }
        }
        headers.add(new String[]{ name, value });
    }

    private static String hostHeader(URI uri, boolean https) {
        int port = uri.getPort();
        boolean dflt = port == -1 || (https && port == 443) || (!https && port == 80);
        return uri.getHost() + (dflt ? "" : ":" + port);
    }

    private static String originTarget(URI uri) {
        String path = uri.getRawPath();
        String t = path == null || path.isEmpty() ? "/" : path;
        return uri.getRawQuery() != null ? t + "?" + uri.getRawQuery() : t;
    }

    private static String absoluteTarget(URI uri) {
        int port = uri.getPort();
        return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost() + (port == -1 ? "" : ":" + port) + originTarget(uri);
    }

    static boolean isText(String contentType, byte[] sample) {
        if (contentType != null && !contentType.isBlank()) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            return ct.startsWith("text/") || ct.contains("json") || ct.contains("xml") || ct.contains("javascript")
                    || ct.contains("html") || ct.contains("x-www-form-urlencoded") || ct.contains("ecmascript")
                    || ct.contains("yaml") || ct.contains("csv");
        }
        if (sample == null || sample.length == 0) return false;
        // İçerik türü yoksa kaba koklama: NUL ve (sekme/satır sonu dışı) kontrol baytı yoksa metin say.
        int n = Math.min(sample.length, 1024);
        for (int i = 0; i < n; i++) {
            int b = sample[i] & 0xff;
            if (b == 0 || (b < 0x20 && b != '\t' && b != '\n' && b != '\r' && b != 0x0c)) return false;
        }
        return true;
    }

    /**
     * Sıkıştırılmış gövde önizlemesini YALNIZ gösterim için açar (sayfa çekirdeği profili, 2026-10-05). Önizleme ilk
     * 32 KB'ta kesildiği için akış yarım olabilir — açılabilen kısım yeter. Açılan boyut {@link #PREVIEW_BYTES}'la sınırlı
     * (sıkıştırma bombası önizlemeyi şişiremez). {@code identity}/boş → olduğu gibi; gzip/deflate dışı kodlama (br …)
     * ya da hiç açılamayan akış → {@code null} (metin değil sayılır, anlamsız bayt ekrana basılmaz).
     */
    static byte[] inflatePreview(byte[] raw, String encoding) {
        if (raw == null || raw.length == 0 || encoding == null) return raw;
        String enc = encoding.trim().toLowerCase(Locale.ROOT);
        if (enc.isEmpty() || "identity".equals(enc)) return raw;
        boolean gzip = enc.contains("gzip");
        if (!gzip && !enc.contains("deflate")) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        java.util.zip.Inflater inflater = gzip ? null : new java.util.zip.Inflater(true);   // çekirdekle aynı (nowrap)
        try (InputStream in = gzip
                ? new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(raw))
                : new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(raw), inflater)) {
            byte[] buf = new byte[4096];
            while (out.size() < PREVIEW_BYTES) {
                int n = in.read(buf, 0, Math.min(buf.length, PREVIEW_BYTES - out.size()));
                if (n <= 0) break;
                out.write(buf, 0, n);
            }
        } catch (IOException | RuntimeException e) {
            // yarım akış (önizleme kesildi) ya da bozuk kodlama — açılabilen kısım kalır
        } finally {
            if (inflater != null) inflater.end();
        }
        return out.size() > 0 ? out.toByteArray() : null;
    }

    static String decode(byte[] bytes, String contentType) {
        Charset cs = StandardCharsets.UTF_8;
        if (contentType != null) {
            Matcher m = Pattern.compile("(?i)charset\\s*=\\s*\"?([A-Za-z0-9._\\-]+)").matcher(contentType);
            if (m.find()) {
                try { cs = Charset.forName(m.group(1)); } catch (Exception ignore) { cs = StandardCharsets.UTF_8; }
            }
        }
        return new String(bytes, cs);
    }

    private static Integer parseStatus(String statusLine) {
        Matcher m = STATUS_LINE.matcher(statusLine == null ? "" : statusLine);
        return m.matches() ? Integer.valueOf(m.group(2)) : null;
    }

    private static boolean isRefused(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return e instanceof ConnectException && m.contains("refused");
    }

    private static boolean isTimeout(Throwable e, AtomicBoolean watchdogFired) {
        if (watchdogFired != null && watchdogFired.get()) return true;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    public static String msg(Throwable e) {
        if (e == null) return null;
        String m = e.getMessage();
        return m != null && !m.isBlank() ? m : e.getClass().getSimpleName();
    }

    private static String abbreviate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
    }

    static String bare(String host) {
        if (host != null && host.startsWith("[") && host.endsWith("]")) return host.substring(1, host.length() - 1);
        return host;
    }

    private static String fmt(InetAddress a, int port) {
        if (a == null) return null;
        String h = a.getHostAddress();
        int z = h.indexOf('%');
        if (z >= 0) h = h.substring(0, z);
        return (a instanceof java.net.Inet6Address ? "[" + h + "]" : h) + ":" + port;
    }

    private static Long add(Long acc, long v) { return acc == null ? v : acc + v; }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignore) { /* zaten kapalı */ }
    }

    /** Adlı parametre haritası (null değer korunur — ön yüz yer tutucuyu boş gösterir). */
    public static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /**
     * Hop'un adım listesi — sözleşmedeki SABİT sıra: dns, proxy_connect | tcp, proxy_tunnel, tls, request, response,
     * body. Uygulanabilir adımlar baştan {@code skip} olarak durur; ulaşılan adım {@code ok|fail} + süreyle güncellenir.
     */
    private static final class Steps {
        final List<Map<String, Object>> list = new ArrayList<>();
        private final Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();

        Steps(boolean viaProxy, boolean tunnel, boolean https) {
            add("dns");
            add(viaProxy ? "proxy_connect" : "tcp");
            if (tunnel) add("proxy_tunnel");
            if (https) add("tls");
            add("request");
            add("response");
            add("body");
        }

        private void add(String key) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("key", key);
            s.put("status", "skip");
            s.put("ms", null);
            list.add(s);
            byKey.put(key, s);
        }

        void done(String key, String status, Long ms) {
            Map<String, Object> s = byKey.get(key);
            if (s == null) return;
            s.put("status", status);
            s.put("ms", ms);
        }
    }
}
