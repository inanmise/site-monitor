package com.certmonitor.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.CertificatePolicies;
import org.bouncycastle.asn1.x509.PolicyInformation;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.DSAKey;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateCheckerService {

    private final ChainValidationService chainValidator;
    private final DnsCheckerService dnsCheckerService;
    private final ObjectMapper objectMapper;
    private final TrustEvaluator trustEvaluator;

    @Value("${cert.monitor.check-timeout-seconds:6}")
    private int timeoutSeconds;

    @Value("${cert.monitor.warning-days:30}")
    private int warningDays;

    /** Retry transient (NETWORK class) failures once before declaring the cert
     *  unreachable. Eliminates false-positive CRITICAL alarms from WAF resets
     *  and rate-limit hiccups. SSL/DNS/CERT errors are never retried — they
     *  represent real issues that don't self-heal in 1s. */
    @Value("${cert.monitor.check.retry-on-transient:true}")
    private boolean retryOnTransient;

    @Value("${cert.monitor.check.retry-delay-ms:1000}")
    private long retryDelayMs;

    @Value("${cert.monitor.check.max-attempts:2}")
    private int maxAttempts;

    /** Retry with an ALTERNATE combo (flipped TLS mode and/or direct↔proxy
     *  path) instead of repeating identical parameters. Deterministic blocks
     *  (WAF JA3 drop, egress firewall RST) never self-heal on an identical
     *  retry; varying the combo gives the second attempt a real chance. */
    @Value("${cert.monitor.check.retry-fallback:true}")
    private boolean retryFallback;

    /** TLS handshake fingerprint mode.
     *  - "browser" (default): force TLS 1.2 + ALPN [h2, http/1.1] so the
     *    ClientHello looks like Chrome/Firefox. Many WAFs (Akamai/F5/Imperva)
     *    fingerprint and RST connections that look like raw Java SSL.
     *    Observed in akbank prod where 2 WAF-fronted domains kept resetting
     *    despite the same servers accepting browsers + dev-PC handshakes.
     *  - "default": no overrides; whatever Java 21 negotiates (TLS 1.3 by
     *    default). Use this if you suspect the browser-style override is
     *    causing handshake incompatibility with very old peers. */
    @Value("${cert.monitor.check.tls-mode:browser}")
    private String tlsMode;

    private static final String[] BROWSER_TLS_PROTOCOLS = { "TLSv1.2" };
    private static final String[] BROWSER_ALPN          = { "h2", "http/1.1" };

    /**
     * Sertifika ÇEKİMİ için trust-all soket factory'si. Bir izleme aracının, zincir public CA ile
     * doğrulanmasa bile (kurumsal/iç CA, self-signed) sertifikayı OKUYUP süre/zincir/ayrıntıyı
     * raporlayabilmesi gerekir. Güven, okunan zincir üzerinde AYRI bir adımda {@link TrustEvaluator}
     * ile değerlendirilir ({@code trust_status}); handshake artık güveni zorlamaz. (Aynı desen:
     * HstsDiagnosticsService.buildTrustAll, LdapDirectoryService.TRUST_ALL.)
     */
    private static final SSLSocketFactory TRUST_ALL_FACTORY = buildTrustAllFactory();

    private static SSLSocketFactory buildTrustAllFactory() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new java.security.SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    @Value("${cert.monitor.proxy.host:}")     private String proxyHost;
    @Value("${cert.monitor.proxy.port:0}")    private int    proxyPort;
    @Value("${cert.monitor.proxy.user:}")     private String proxyUser;
    @Value("${cert.monitor.proxy.pass:}")     private String proxyPass;
    @Value("${cert.monitor.proxy.no-proxy:}") private String noProxyList;
    /** Direct kontrol TCP düzeyinde başarısız olunca OTOMATİK proxy'ye düşülsün mü? VARSAYILAN KAPALI:
     *  domain'in "Proxy Üzerinden Kontrol Et = Hayır" tercihi kesin onurlanır (aksi halde internal domain'ler
     *  DMZ proxy'sine yönlenip yanlış "Proxy" etiketi + timeout veriyordu). Eski davranış global açılarak geri alınır. */
    @Value("${cert.monitor.proxy.auto-fallback:false}") private boolean autoProxyFallback;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String OID_CERT_POLICIES = "2.5.29.32";
    private static final String EV_OID            = "2.23.140.1.1";

    private static final String[] KEY_USAGE_NAMES = {
        "Digital Signature", "Non-Repudiation", "Key Encipherment", "Data Encipherment",
        "Key Agreement", "Certificate Signing", "CRL Signing", "Encipher Only", "Decipher Only"
    };

    private static final Map<String, String> EKU_NAMES = Map.of(
        "1.3.6.1.5.5.7.3.1", "TLS Web Server",
        "1.3.6.1.5.5.7.3.2", "TLS Web Client",
        "1.3.6.1.5.5.7.3.3", "Code Signing",
        "1.3.6.1.5.5.7.3.4", "Email Protection",
        "1.3.6.1.5.5.7.3.8", "Timestamping"
    );

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String domain, int port) {
        return checkAsync(domain, port, false);
    }

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String domain, int port, boolean forceProxy) {
        return CompletableFuture.completedFuture(check(domain, port, forceProxy));
    }

    @Async("certCheckExecutor")
    public CompletableFuture<Map<String, Object>> checkAsync(String domain, int port, boolean forceProxy, String tlsModeOverride) {
        return CompletableFuture.completedFuture(check(domain, port, forceProxy, tlsModeOverride));
    }

    public Map<String, Object> check(String domain, int port) {
        return check(domain, port, false);
    }

    public Map<String, Object> check(String domain, int port, boolean forceProxy) {
        return check(domain, port, forceProxy, null);
    }

    public Map<String, Object> check(String domain, int port, boolean forceProxy, String tlsModeOverride) {
        CheckOptions opts = resolveOptions(forceProxy, tlsModeOverride, domain);
        Map<String, Object> result = tryCheckOnce(domain, port, opts);

        if (!retryOnTransient || maxAttempts < 2) return result;
        if (!isTransientError(result)) return result;

        CheckOptions retryOpts = retryFallback ? chooseFallback(opts, result, domain) : opts;
        if (!retryOpts.equals(opts)) {
            log.info("Certificate check fallback retry: domain={} from={} to={} prev_stage={} prev_error={}",
                    domain, opts.describe(), retryOpts.describe(),
                    result.get("error_stage"),
                    truncate((String) result.get("error"), 100));
        } else {
            log.info("Certificate check retry: domain={} attempt=2 prev_class={} prev_error={}",
                    domain, result.get("error_class"),
                    truncate((String) result.get("error"), 100));
        }

        try { Thread.sleep(retryDelayMs); }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return result;
        }

        Map<String, Object> retry = tryCheckOnce(domain, port, retryOpts);
        if (!retryOpts.equals(opts)) {
            retry.put("retry_fallback", opts.describe() + "→" + retryOpts.describe());
        }
        if ("error".equals(retry.get("status"))) {
            retry.put("retry_attempted", true);
            log.warn("Certificate check failed after retry: domain={} via={} tlsMode={} final_error={}",
                    domain, retryOpts.viaProxy() ? "proxy" : "direct", retryOpts.tlsMode(),
                    truncate((String) retry.get("error"), 200));
        } else {
            retry.put("retry_recovered", true);
            log.info("Certificate check recovered on retry: domain={} via={} tlsMode={} prev_error={}",
                    domain, retryOpts.viaProxy() ? "proxy" : "direct", retryOpts.tlsMode(),
                    truncate((String) result.get("error"), 80));
        }
        return retry;
    }

    /** Decision table for the alternate-combo retry, evaluated top-down:
     *  1. TLS handshake stalls (no ServerHello: timeout/reset) → flip TLS mode,
     *     same path — JA3 fingerprint suspicion; a different ClientHello shape
     *     may pass the WAF.
     *  2. direct TCP-level block + proxy available → go via proxy.
     *  3. proxy itself unreachable / tunnel failed → go direct.
     *  4. otherwise → identical parameters (legacy behavior). */
    CheckOptions chooseFallback(CheckOptions prev, Map<String, Object> result, String domain) {
        String stage = String.valueOf(result.getOrDefault("error_stage", ""));
        String msg = ((String) result.getOrDefault("error", "")).toLowerCase();

        if ("tls-handshake".equals(stage)) {
            return prev.withTlsMode(
                "browser".equalsIgnoreCase(prev.tlsMode()) ? "default" : "browser");
        }
        // Direct TCP bloğu + proxy yapılandırılmış → proxy'ye düş. YALNIZ auto-fallback global açıksa:
        // varsayılan kapalı, böylece per-domain "Proxy Üzerinden Kontrol Et = Hayır" tercihi EZİLMEZ.
        if (autoProxyFallback && !prev.viaProxy() && "tcp-connect".equals(stage)
                && (msg.contains("reset") || msg.contains("refused")
                    || msg.contains("no route") || msg.contains("timeout"))
                && proxyEnabled() && !shouldBypassProxy(domain)) {
            return prev.withViaProxy(true);
        }
        if (prev.viaProxy() && ("proxy-connect".equals(stage) || "tcp-connect".equals(stage))) {
            return prev.withViaProxy(false);
        }
        return prev;
    }

    /** Returns true iff this failure is a transient NETWORK-class symptom worth
     *  retrying. SSL handshake, DNS, and cert errors are NOT retried. */
    boolean isTransientError(Map<String, Object> result) {
        if (!"error".equals(result.get("status"))) return false;
        if (!"NETWORK".equals(result.get("error_class"))) return false;
        String msg = ((String) result.getOrDefault("error", "")).toLowerCase();
        return msg.contains("connection reset")
            || msg.contains("connection refused")
            || msg.contains("timeout")
            || msg.contains("no route to host")
            || msg.contains("socket closed")
            || msg.contains("broken pipe");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Fully-resolved parameters for one connection attempt. {@code viaProxy}
     *  is the FINAL decision (global proxy config + no-proxy bypass already
     *  applied). {@code lightweight} skips chain/revocation/HSTS/DNS
     *  enrichment — used by connection diagnostics so probe combos don't pay
     *  OCSP/CRL/HEAD round-trips. */
    record CheckOptions(boolean viaProxy, String tlsMode, int timeoutSeconds, boolean lightweight) {

        CheckOptions withViaProxy(boolean v) { return new CheckOptions(v, tlsMode, timeoutSeconds, lightweight); }
        CheckOptions withTlsMode(String m)   { return new CheckOptions(viaProxy, m, timeoutSeconds, lightweight); }

        String describe() { return (viaProxy ? "proxy" : "direct") + "/" + tlsMode; }
    }

    CheckOptions resolveOptions(boolean forceProxy, String tlsModeOverride, String domain) {
        boolean viaProxy = forceProxy && proxyEnabled() && !shouldBypassProxy(domain);
        String mode = (tlsModeOverride != null && !tlsModeOverride.isBlank()) ? tlsModeOverride : tlsMode;
        return new CheckOptions(viaProxy, mode, timeoutSeconds, false);
    }

    Map<String, Object> tryCheckOnce(String domain, int port, boolean forceProxy) {
        return tryCheckOnce(domain, port, resolveOptions(forceProxy, null, domain));
    }

    Map<String, Object> tryCheckOnce(String domain, int port, CheckOptions opts) {
        long startMs = System.currentTimeMillis();
        Map<String, Object> result = doTryCheckOnce(domain, port, opts, startMs);
        result.put("via", opts.viaProxy() ? "proxy" : "direct");
        result.put("tls_mode_used", opts.tlsMode());
        result.put("elapsed_ms", System.currentTimeMillis() - startMs);
        return result;
    }

    private Map<String, Object> doTryCheckOnce(String domain, int port, CheckOptions opts, long startMs) {
        final boolean useProxy = opts.viaProxy();
        final int timeoutSec = opts.timeoutSeconds();
        List<String> resolvedIps = resolveAllIps(domain);
        String stage = "tcp-connect";
        // Actual network route of this attempt (filled once TCP is established,
        // so handshake-stage failures still carry source/peer endpoints).
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("source_ip", null);
        route.put("source_port", null);
        route.put("peer_ip", null);
        route.put("peer_port", null);
        try {
            // Trust-all: sertifikayı her durumda OKU (güven AYRI değerlendirilir → trust_status).
            // İç/kurumsal CA ile imzalı host'lar artık "PKIX path building failed" ile düşmez.
            SSLSocketFactory factory = TRUST_ALL_FACTORY;
            log.debug("Certificate check start: domain={}:{} via={} tlsMode={} resolvedIps={}",
                    domain, port,
                    useProxy ? "proxy(" + proxyHost + ":" + proxyPort + ")" : "direct",
                    opts.tlsMode(), resolvedIps);
            if (useProxy) stage = "proxy-connect";
            SSLSocket socket = useProxy
                    ? openViaProxy(factory, domain, port, timeoutSec)
                    : (SSLSocket) factory.createSocket();
            try (socket) {
                if (!useProxy) {
                    socket.connect(new InetSocketAddress(domain, port), timeoutSec * 1000);
                }
                captureRoute(route, socket);
                socket.setSoTimeout(timeoutSec * 1000);

                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(Collections.singletonList(new SNIHostName(domain)));
                if ("browser".equalsIgnoreCase(opts.tlsMode())) {
                    params.setApplicationProtocols(BROWSER_ALPN);
                    // Protokol kısıtı params üzerinden verilir; aksi halde
                    // socket.setSSLParameters çağrısı default {TLSv1.3, TLSv1.2}
                    // ile geri eziyor (önceki bug). Bkz. fix(cert): TLS protokol kısıtı.
                    params.setProtocols(BROWSER_TLS_PROTOCOLS);
                }
                socket.setSSLParameters(params);

                if (useProxy) {
                    log.info("[cert-proxy] step=tls-handshake-start domain={} sni={} tlsMode={} alpn={} paramsProtocols={} enabledProtocols={} resolvedIps={} timeoutSec={}",
                            domain, domain, opts.tlsMode(),
                            Arrays.toString(params.getApplicationProtocols()),
                            Arrays.toString(params.getProtocols()),
                            Arrays.toString(socket.getEnabledProtocols()),
                            resolvedIps, timeoutSec);
                }
                stage = "tls-handshake";
                long handshakeStart = System.currentTimeMillis();
                socket.startHandshake();
                stage = "cert-ok";
                String tlsVersion = socket.getSession().getProtocol();
                if (useProxy) {
                    log.info("[cert-proxy] step=tls-handshake-done domain={} tlsVersion={} cipher={} peerHost={} peerCerts={} elapsed={}ms",
                            domain, tlsVersion, socket.getSession().getCipherSuite(),
                            socket.getSession().getPeerHost(),
                            socket.getSession().getPeerCertificates().length,
                            System.currentTimeMillis() - handshakeStart);
                }

                Certificate[] peerCerts = socket.getSession().getPeerCertificates();
                if (peerCerts.length == 0) return withRoute(error(domain, "No certificates in chain"), route);

                X509Certificate leaf = (X509Certificate) peerCerts[0];
                Map<String, Object> result = parseLeafCert(leaf, domain);
                result.putAll(route);
                result.put("tls_version", tlsVersion);
                // Tanılama/kök-neden için: anlaşılan cipher + ALPN (JDK TLS yığını farkı görünür olsun)
                result.put("cipher_suite", socket.getSession().getCipherSuite());
                result.put("alpn", socket.getApplicationProtocol());

                String revocation = "UNKNOWN";
                if (opts.lightweight()) {
                    result.put("chain_status", "UNKNOWN");
                    result.put("intermediate_expiry", null);
                    result.put("intermediate_days_remaining", null);
                    result.put("chain", Collections.emptyList());
                    result.put("fingerprint", null);
                    result.put("revocation_status", "UNKNOWN");
                    result.put("trust_status", "UNKNOWN");
                } else {
                    // Full chain analysis
                    Map<String, Object> chainInfo = chainValidator.analyzeChain(peerCerts);
                    result.put("chain_status", chainInfo.get("chain_status"));
                    result.put("intermediate_expiry", chainInfo.get("intermediate_expiry"));
                    result.put("intermediate_days_remaining", chainInfo.get("intermediate_days_remaining"));
                    result.put("chain", chainInfo.get("chain"));

                    // Fingerprint
                    String fingerprint = chainValidator.calculateFingerprint(leaf);
                    result.put("fingerprint", fingerprint);

                    // Revocation (OCSP then CRL)
                    revocation = chainValidator.checkRevocation(peerCerts);
                    result.put("revocation_status", revocation);

                    // If chain is broken due to intermediate expiry, override chain_status
                    if ("BROKEN".equals(chainInfo.get("chain_status")) && !"REVOKED".equals(revocation)) {
                        result.put("chain_status", "BROKEN");
                    } else if ("REVOKED".equals(revocation)) {
                        result.put("chain_status", "REVOKED");
                    }

                    // Güven değerlendirmesi: zincir varsayılan cacerts VEYA admin CA paketiyle
                    // bir güven köküne bağlanıyor mu? (Çekimi engellemez — yalnız raporlar.)
                    try {
                        X509Certificate[] x509Chain = new X509Certificate[peerCerts.length];
                        for (int ci = 0; ci < peerCerts.length; ci++) {
                            x509Chain[ci] = (X509Certificate) peerCerts[ci];
                        }
                        TrustEvaluator.TrustResult tr = trustEvaluator.evaluate(x509Chain);
                        result.put("trust_status", tr.trusted() ? "TRUSTED" : "UNTRUSTED");
                        if (!tr.trusted()) result.put("trust_error", tr.reason());
                    } catch (Exception te) {
                        result.put("trust_status", "UNKNOWN");
                        log.debug("trust evaluate skipped for {}: {}", domain, te.getMessage());
                    }
                }

                // deployment_status is determined by CertificateService (needs inventory lookup)
                result.put("deployment_status", "UNKNOWN");

                if (opts.lightweight()) {
                    result.put("resolved_ip", resolvedIps.isEmpty() ? null : resolvedIps.get(0));
                    result.put("hsts", null);
                    return result;
                }

                // DNS resolution IP — use JNDI-based resolver (same as DNS Record Monitoring)
                try {
                    Map<String, Object> dnsResult = dnsCheckerService.check(domain, "A");
                    List<?> addrs = (List<?>) dnsResult.get("values");
                    result.put("resolved_ip",
                        (addrs != null && !addrs.isEmpty()) ? String.valueOf(addrs.get(0)) : null);
                } catch (Exception ignored) {
                    result.put("resolved_ip", null);
                }

                // HSTS check via HTTP HEAD — reuse the SSLSocketFactory that already succeeded
                HttpURLConnection hc = null;
                try {
                    URL url = new URL("https://" + domain + "/");
                    if (useProxy) {
                        java.net.Proxy p = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                                new InetSocketAddress(proxyHost, proxyPort));
                        hc = (HttpURLConnection) url.openConnection(p);
                        if (proxyUser != null && !proxyUser.isBlank()) {
                            String creds = java.util.Base64.getEncoder().encodeToString(
                                (proxyUser + ":" + proxyPass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            hc.setRequestProperty("Proxy-Authorization", "Basic " + creds);
                        }
                    } else {
                        // Direct = KESİN direct: JVM ProxySelector / sistem proxy env'ini baypas et (Proxy.NO_PROXY),
                        // böylece use_proxy=false iken HSTS HEAD'i de proxy'ye sızmaz.
                        hc = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
                    }
                    if (hc instanceof HttpsURLConnection https) {
                        https.setSSLSocketFactory(factory);
                    }
                    hc.setRequestMethod("HEAD");
                    hc.setConnectTimeout(4000);
                    hc.setReadTimeout(4000);
                    hc.setInstanceFollowRedirects(true);
                    hc.connect();
                    result.put("hsts", hc.getHeaderField("Strict-Transport-Security") != null);
                } catch (Exception e) {
                    log.debug("HSTS check failed for {}: {}", domain, e.getMessage());
                    result.put("hsts", null);
                } finally {
                    if (hc != null) {
                        try { hc.disconnect(); } catch (Exception ignored) { }
                    }
                }

                long elapsed = System.currentTimeMillis() - startMs;
                int days = (Integer) result.getOrDefault("days_remaining", -1);
                boolean warning = Boolean.TRUE.equals(result.get("warning"));
                String issuerCn = (String) result.getOrDefault("issuer_cn", "?");
                String chainStatus = (String) result.getOrDefault("chain_status", "?");

                if (warning) {
                    log.warn("Certificate expiring soon: domain={} days={} issuer={} chain={} elapsed={}ms",
                            domain, days, issuerCn, chainStatus, elapsed);
                } else {
                    log.debug("Certificate OK: domain={} days={} issuer={} chain={} elapsed={}ms",
                            domain, days, issuerCn, chainStatus, elapsed);
                }

                if ("REVOKED".equals(revocation)) {
                    log.warn("Certificate REVOKED: domain={}", domain);
                }
                if ("BROKEN".equals(chainStatus)) {
                    log.warn("Certificate chain BROKEN: domain={}", domain);
                }

                return result;
            }
        } catch (java.net.SocketTimeoutException e) {
            logCheckFailure(useProxy, "timeout", domain, port, startMs, resolvedIps, e);
            return withRoute(errorWithClass(domain,"Connection timeout after " + timeoutSec + "s", "NETWORK", stage, resolvedIps), route);
        } catch (java.net.UnknownHostException e) {
            logCheckFailure(useProxy, "dns-failure", domain, port, startMs, resolvedIps, e);
            return withRoute(errorWithClass(domain,"Domain resolution failed", "DNS", "dns", resolvedIps), route);
        } catch (java.net.ConnectException e) {
            logCheckFailure(useProxy, "connect-refused", domain, port, startMs, resolvedIps, e);
            return withRoute(errorWithClass(domain,"Connection refused/unreachable: " + e.getMessage(), "NETWORK", stage, resolvedIps), route);
        } catch (java.net.NoRouteToHostException e) {
            logCheckFailure(useProxy, "no-route", domain, port, startMs, resolvedIps, e);
            return withRoute(errorWithClass(domain,"No route to host", "NETWORK", stage, resolvedIps), route);
        } catch (java.net.SocketException e) {
            logCheckFailure(useProxy, "socket-error", domain, port, startMs, resolvedIps, e);
            return withRoute(errorWithClass(domain,"Socket error: " + e.getMessage(), "NETWORK", stage, resolvedIps), route);
        } catch (javax.net.ssl.SSLHandshakeException e) {
            logCheckFailure(useProxy, "ssl-handshake", domain, port, startMs, resolvedIps, e);
            return withRoute(errorWithClass(domain,"SSL handshake: " + e.getMessage(), "SSL", stage, resolvedIps), route);
        } catch (javax.net.ssl.SSLException e) {
            logCheckFailure(useProxy, "ssl-error", domain, port, startMs, resolvedIps, e);
            return withRoute(errorWithClass(domain,"SSL Error: " + e.getMessage(), "SSL", stage, resolvedIps), route);
        } catch (IOException e) {
            // openViaProxy wraps tunnel failures (proxy TCP connect / CONNECT
            // response) in plain IOException — NETWORK class so the
            // transient-retry gate and the proxy→direct fallback can apply.
            logCheckFailure(useProxy, "io-error", domain, port, startMs, resolvedIps, e);
            return withRoute(errorWithClass(domain,"I/O error: " + e.getMessage(), "NETWORK", stage, resolvedIps), route);
        } catch (Exception e) {
            // Always log unexpected errors with stack trace, regardless of mode.
            log.error("Certificate check unexpected error: domain={}:{} via={} resolvedIps={} elapsed={}ms",
                    domain, port,
                    useProxy ? "proxy(" + proxyHost + ":" + proxyPort + ")" : "direct",
                    resolvedIps, System.currentTimeMillis() - startMs, e);
            return withRoute(errorWithClass(domain,"Error: " + e.getMessage(), "UNKNOWN", stage, resolvedIps), route);
        }
    }

    /** Records the actual local/peer endpoints of an established socket.
     *  Direct: peer = chosen target IP; proxy: peer = proxy IP (layered
     *  SSLSocket delegates to the underlying raw socket). Network admins use
     *  this to trace "from source_ip:port to peer_ip:port" through firewalls. */
    private void captureRoute(Map<String, Object> route, Socket socket) {
        try {
            if (socket.getLocalAddress() != null && !socket.getLocalAddress().isAnyLocalAddress()) {
                route.put("source_ip", socket.getLocalAddress().getHostAddress());
                route.put("source_port", socket.getLocalPort());
            }
            if (socket.getInetAddress() != null) {
                route.put("peer_ip", socket.getInetAddress().getHostAddress());
                route.put("peer_port", socket.getPort());
            }
        } catch (Exception ignored) { }
    }

    private static Map<String, Object> withRoute(Map<String, Object> r, Map<String, Object> route) {
        r.putAll(route);
        return r;
    }

    /** Resolve all A/AAAA records via the system resolver — the same path the
     *  socket uses. Non-fatal: empty list on failure. For proxy-tunneled
     *  domains this is still the POD's view of the name, which is exactly the
     *  split-DNS signal we want visible in logs and diagnostics. */
    private List<String> resolveAllIps(String domain) {
        List<String> ips = new ArrayList<>();
        try {
            for (java.net.InetAddress a : java.net.InetAddress.getAllByName(domain)) {
                ips.add(a.getHostAddress());
            }
        } catch (Exception ignored) { }
        return ips;
    }

    /** Failure logger that opts into full stack trace + proxy diagnostics when
     *  the check went through a proxy tunnel (per-domain use_proxy flag).
     *  Direct checks keep the original terse one-liner. */
    private void logCheckFailure(boolean useProxy, String stage, String domain, int port, long startMs,
                                 List<String> resolvedIps, Exception e) {
        long elapsed = System.currentTimeMillis() - startMs;
        if (useProxy) {
            log.warn("[cert-proxy] step={} FAILED domain={}:{} proxy={}:{} resolvedIps={} elapsed={}ms errType={} errMsg={}",
                    stage, domain, port, proxyHost, proxyPort, resolvedIps, elapsed,
                    e.getClass().getSimpleName(), e.getMessage());
        } else {
            log.warn("Certificate check {}: domain={}:{} resolvedIps={} elapsed={}ms err={}",
                    stage, domain, port, resolvedIps, elapsed, e.getMessage());
        }
    }

    private boolean proxyEnabled() {
        return proxyHost != null && !proxyHost.isBlank() && proxyPort > 0;
    }

    private boolean shouldBypassProxy(String domain) {
        if (noProxyList == null || noProxyList.isBlank()) return false;
        String d = domain.toLowerCase();
        for (String entry : noProxyList.split(",")) {
            String e = entry.trim().toLowerCase();
            if (e.isEmpty()) continue;
            if (e.startsWith(".")) {
                if (d.endsWith(e) || d.equals(e.substring(1))) return true;
            } else {
                if (d.equals(e) || d.endsWith("." + e)) return true;
            }
        }
        return false;
    }

    /**
     * Tanılama / CA dışa-aktarma: {@code host:port}'a PROXY üzerinden (kurumsal SSL-inspection yolu) TLS
     * el sıkışması yapıp SUNUCUNUN SUNDUĞU zinciri döner. Güven ZORLANMAZ — {@code TRUST_ALL_FACTORY} ile
     * yalnız OKUMA (openssl {@code s_client -showcerts} eşdeğeri, aynı desen sertifika çekiminde kullanılır);
     * dönen zincir yalnız incelenip PEM olarak dışa aktarılır, hiçbir veri akışı için kullanılmaz.
     * Proxy yapılandırılmamışsa IOException. Bu, "kurumsal CA paketini nereden alacağım" sorusunu UI'dan çözer.
     */
    public X509Certificate[] captureProxyChain(String host, int port) throws IOException {
        if (proxyHost == null || proxyHost.isBlank() || proxyPort <= 0) {
            throw new IOException("Proxy yapılandırılmamış (cert.monitor.proxy.host/port boş) — CA zinciri yalnız proxy üzerinden yakalanır");
        }
        int timeoutSec = 8;
        SSLSocket socket = openViaProxy(TRUST_ALL_FACTORY, host, port, timeoutSec);
        try (socket) {
            socket.setSoTimeout(timeoutSec * 1000);
            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(Collections.singletonList(new SNIHostName(host)));
            socket.setSSLParameters(params);
            socket.startHandshake();
            Certificate[] peer = socket.getSession().getPeerCertificates();
            X509Certificate[] out = new X509Certificate[peer.length];
            for (int i = 0; i < peer.length; i++) out[i] = (X509Certificate) peer[i];
            return out;
        }
    }

    /** Open raw TCP to proxy, send HTTP CONNECT, then wrap with SSL.
     *
     *  Emits step-by-step INFO logs under the "[cert-proxy]" prefix so a
     *  failed proxy-tunneled cert check (e.g. www.akbankpos.com) can be
     *  diagnosed end-to-end without re-running with a packet capture.
     *  Errors are wrapped with the failing step name and the raw proxy
     *  response (status line + headers) so the caller's WARN/error log
     *  carries enough context. */
    private SSLSocket openViaProxy(SSLSocketFactory factory, String domain, int port, int timeoutSec) throws IOException {
        boolean authOn = proxyUser != null && !proxyUser.isBlank();
        log.info("[cert-proxy] step=open-tunnel domain={} port={} proxy={}:{} auth={} noProxy='{}' timeoutSec={}",
                domain, port, proxyHost, proxyPort,
                authOn ? "basic(user=" + proxyUser + ")" : "off",
                noProxyList == null ? "" : noProxyList,
                timeoutSec);

        Socket raw = new Socket();
        long tcpStart = System.currentTimeMillis();
        try {
            raw.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutSec * 1000);
        } catch (IOException e) {
            log.warn("[cert-proxy] step=tcp-connect FAILED domain={} proxy={}:{} elapsed={}ms err={}",
                    domain, proxyHost, proxyPort,
                    System.currentTimeMillis() - tcpStart, e.toString());
            try { raw.close(); } catch (Exception ignored) {}
            throw new IOException("Proxy TCP connect failed: " + proxyHost + ":" + proxyPort
                    + " — " + e.getMessage(), e);
        }
        raw.setSoTimeout(timeoutSec * 1000);
        log.info("[cert-proxy] step=tcp-connected domain={} proxy={}:{} localIp={} localPort={} elapsed={}ms",
                domain, proxyHost, proxyPort,
                raw.getLocalAddress() != null ? raw.getLocalAddress().getHostAddress() : "?",
                raw.getLocalPort(),
                System.currentTimeMillis() - tcpStart);

        StringBuilder req = new StringBuilder()
            .append("CONNECT ").append(domain).append(":").append(port).append(" HTTP/1.1\r\n")
            .append("Host: ").append(domain).append(":").append(port).append("\r\n");
        if (authOn) {
            String creds = java.util.Base64.getEncoder().encodeToString(
                (proxyUser + ":" + proxyPass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            req.append("Proxy-Authorization: Basic ").append(creds).append("\r\n");
        }
        req.append("\r\n");
        log.info("[cert-proxy] step=connect-request domain={} requestLine='CONNECT {}:{} HTTP/1.1' hostHdr='{}:{}' authHdr={}",
                domain, domain, port, domain, port,
                authOn ? "'Proxy-Authorization: Basic *******'" : "absent");

        long connectStart = System.currentTimeMillis();
        try {
            raw.getOutputStream().write(req.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            raw.getOutputStream().flush();
        } catch (IOException e) {
            log.warn("[cert-proxy] step=connect-write FAILED domain={} proxy={}:{} err={}",
                    domain, proxyHost, proxyPort, e.toString());
            try { raw.close(); } catch (Exception ignored) {}
            throw new IOException("Proxy CONNECT write failed: " + e.getMessage(), e);
        }

        java.io.BufferedReader in = new java.io.BufferedReader(
            new java.io.InputStreamReader(raw.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
        String status;
        try {
            status = in.readLine();
        } catch (IOException e) {
            log.warn("[cert-proxy] step=connect-read FAILED domain={} proxy={}:{} elapsed={}ms err={}",
                    domain, proxyHost, proxyPort,
                    System.currentTimeMillis() - connectStart, e.toString());
            try { raw.close(); } catch (Exception ignored) {}
            throw new IOException("Proxy CONNECT read failed: " + e.getMessage(), e);
        }
        long connectElapsed = System.currentTimeMillis() - connectStart;

        // Drain + collect response headers for diagnostics
        List<String> respHeaders = new ArrayList<>();
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            if (respHeaders.size() < 32) respHeaders.add(line);
        }

        if (status == null || !(status.startsWith("HTTP/1.1 200") || status.startsWith("HTTP/1.0 200"))) {
            log.warn("[cert-proxy] step=connect-response FAILED domain={} proxy={}:{} status='{}' headers={} elapsed={}ms",
                    domain, proxyHost, proxyPort,
                    status == null ? "<null>" : status,
                    respHeaders, connectElapsed);
            try { raw.close(); } catch (Exception ignored) {}
            throw new IOException("Proxy CONNECT failed: " + status
                    + (respHeaders.isEmpty() ? "" : " headers=" + respHeaders));
        }
        log.info("[cert-proxy] step=connect-response domain={} status='{}' headers={} elapsed={}ms",
                domain, status, respHeaders, connectElapsed);

        log.info("[cert-proxy] step=tunnel-established domain={} proxy={}:{} totalElapsed={}ms",
                domain, proxyHost, proxyPort,
                System.currentTimeMillis() - tcpStart);

        try {
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(raw, domain, port, true);
            log.info("[cert-proxy] step=ssl-wrap domain={} cipherSuitesEnabled={} protocolsEnabled={}",
                    domain, sslSocket.getEnabledCipherSuites().length,
                    Arrays.toString(sslSocket.getEnabledProtocols()));
            return sslSocket;
        } catch (IOException e) {
            log.warn("[cert-proxy] step=ssl-wrap FAILED domain={} err={}", domain, e.toString());
            try { raw.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private Map<String, Object> parseLeafCert(X509Certificate cert, String domain) {
        try {
            Instant notBefore = cert.getNotBefore().toInstant();
            Instant notAfter = cert.getNotAfter().toInstant();
            Instant now = Instant.now();

            long daysRemaining = (notAfter.toEpochMilli() - now.toEpochMilli()) / 86_400_000L;
            boolean warning = daysRemaining <= warningDays;

            String subjectCn = extractCn(cert.getSubjectX500Principal().getName());
            String issuerOrg = extractField(cert.getIssuerX500Principal().getName(), "O");
            String issuerCn = extractCn(cert.getIssuerX500Principal().getName());
            List<String> san = extractSan(cert);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("domain", domain);
            result.put("subject", subjectCn);
            result.put("issuer", issuerOrg);
            result.put("issuer_cn", issuerCn);
            result.put("not_before", ISO.format(notBefore));
            result.put("not_after", ISO.format(notAfter));
            result.put("days_remaining", (int) daysRemaining);
            result.put("warning", warning);
            result.put("status", warning ? "warning" : "valid");
            result.put("san", san);
            result.put("checked_at", ISO.format(now));
            // Extended certificate metadata
            result.put("serial_number", cert.getSerialNumber().toString(16).toUpperCase());
            result.put("signature_algorithm", cert.getSigAlgName());
            result.put("public_key_algorithm", cert.getPublicKey().getAlgorithm());
            result.put("public_key_size", getPublicKeySize(cert.getPublicKey()));
            result.put("subject_dn", cert.getSubjectX500Principal().getName());
            result.put("issuer_dn", cert.getIssuerX500Principal().getName());
            result.put("key_usage", buildKeyUsageList(cert.getKeyUsage()));
            result.put("ext_key_usage", buildExtKeyUsageList(cert));
            result.put("is_ca", cert.getBasicConstraints() >= 0);
            result.put("ocsp_url", chainValidator.extractOcspUrl(cert));
            result.put("crl_url", chainValidator.extractCrlUrl(cert));
            result.put("cert_type", determineCertType(cert, san));
            return result;
        } catch (Exception e) {
            return error(domain, "Parse error: " + e.getMessage());
        }
    }

    private int getPublicKeySize(java.security.PublicKey key) {
        if (key instanceof RSAKey rsa) return rsa.getModulus().bitLength();
        if (key instanceof ECKey ec) return ec.getParams().getOrder().bitLength();
        if (key instanceof DSAKey dsa) return dsa.getParams().getP().bitLength();
        return -1;
    }

    private List<String> buildKeyUsageList(boolean[] ku) {
        if (ku == null) return Collections.emptyList();
        List<String> usages = new ArrayList<>();
        for (int i = 0; i < Math.min(ku.length, KEY_USAGE_NAMES.length); i++) {
            if (ku[i]) usages.add(KEY_USAGE_NAMES[i]);
        }
        return usages;
    }

    private List<String> buildExtKeyUsageList(X509Certificate cert) {
        try {
            List<String> eku = cert.getExtendedKeyUsage();
            if (eku == null) return Collections.emptyList();
            return eku.stream().map(oid -> EKU_NAMES.getOrDefault(oid, oid)).toList();
        } catch (Exception e) { return Collections.emptyList(); }
    }

    private String extractCn(String dn) {
        return Arrays.stream(dn.split(","))
                .map(String::trim)
                .filter(s -> s.startsWith("CN="))
                .map(s -> s.substring(3))
                .findFirst()
                .orElse("Unknown");
    }

    private String extractField(String dn, String field) {
        String prefix = field + "=";
        return Arrays.stream(dn.split(","))
                .map(String::trim)
                .filter(s -> s.startsWith(prefix))
                .map(s -> s.substring(prefix.length()))
                .findFirst()
                .orElse("Unknown");
    }

    private String determineCertType(X509Certificate cert, List<String> san) {
        String org = extractField(cert.getSubjectX500Principal().getName(), "O");
        boolean hasOrg = !"Unknown".equals(org);
        boolean isEv = false;
        try {
            byte[] rawExt = cert.getExtensionValue(OID_CERT_POLICIES);
            if (rawExt != null) {
                byte[] extBytes = ASN1OctetString.getInstance(
                    ASN1Primitive.fromByteArray(rawExt)).getOctets();
                CertificatePolicies policies = CertificatePolicies.getInstance(
                    ASN1Primitive.fromByteArray(extBytes));
                for (PolicyInformation pi : policies.getPolicyInformation()) {
                    if (EV_OID.equals(pi.getPolicyIdentifier().getId())) {
                        isEv = true; break;
                    }
                }
            }
        } catch (Exception ignored) {}
        String validation = isEv && hasOrg ? "Extended Validation (EV)"
                          : hasOrg         ? "Organization Validated (OV)"
                          :                  "Domain Validated (DV)";
        String scope = san.stream().anyMatch(s -> s.startsWith("*.")) ? "Wildcard"
                     : san.size() > 1                                  ? "Multi-Domain (SAN)"
                     :                                                    "Single Domain";
        return validation + " — " + scope;
    }

    private List<String> extractSan(X509Certificate cert) {
        List<String> sans = new ArrayList<>();
        try {
            Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
            if (altNames != null) {
                for (List<?> entry : altNames) {
                    if (Integer.valueOf(2).equals(entry.get(0))) {
                        sans.add((String) entry.get(1));
                    }
                }
            }
        } catch (Exception ignored) {}
        return sans;
    }

    public String serializeSan(List<String> san) {
        try {
            return objectMapper.writeValueAsString(san);
        } catch (Exception e) {
            return "[]";
        }
    }

    public List<String> deserializeSan(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private Map<String, Object> errorWithClass(String domain, String msg, String errorClass) {
        Map<String, Object> r = error(domain, msg);
        r.put("error_class", errorClass);
        return r;
    }

    private Map<String, Object> errorWithClass(String domain, String msg, String errorClass,
                                               String stage, List<String> resolvedIps) {
        Map<String, Object> r = errorWithClass(domain, msg, errorClass);
        r.put("error_stage", stage);
        r.put("resolved_ips", resolvedIps);
        return r;
    }

    private Map<String, Object> error(String domain, String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domain", domain);
        result.put("status", "error");
        result.put("error", msg);
        result.put("warning", true);
        result.put("checked_at", ISO.format(Instant.now()));
        result.put("san", Collections.emptyList());
        result.put("chain_status", "UNKNOWN");
        result.put("revocation_status", "UNKNOWN");
        result.put("deployment_status", "UNKNOWN");
        result.put("fingerprint", null);
        result.put("intermediate_expiry", null);
        result.put("intermediate_days_remaining", null);
        result.put("chain", Collections.emptyList());
        result.put("serial_number", null);
        result.put("signature_algorithm", null);
        result.put("public_key_algorithm", null);
        result.put("public_key_size", null);
        result.put("subject_dn", null);
        result.put("issuer_dn", null);
        result.put("key_usage", Collections.emptyList());
        result.put("ext_key_usage", Collections.emptyList());
        result.put("is_ca", null);
        result.put("ocsp_url", null);
        result.put("crl_url", null);
        result.put("cert_type", null);
        result.put("tls_version", null);
        result.put("hsts", null);
        return result;
    }
}
