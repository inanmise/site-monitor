package com.certmonitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    @Value("${cert.monitor.proxy.host:}")     private String proxyHost;
    @Value("${cert.monitor.proxy.port:0}")    private int    proxyPort;
    @Value("${cert.monitor.proxy.user:}")     private String proxyUser;
    @Value("${cert.monitor.proxy.pass:}")     private String proxyPass;
    @Value("${cert.monitor.proxy.no-proxy:}") private String noProxyList;

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

    public Map<String, Object> check(String domain, int port) {
        return check(domain, port, false);
    }

    public Map<String, Object> check(String domain, int port, boolean forceProxy) {
        Map<String, Object> result = tryCheckOnce(domain, port, forceProxy);

        if (!retryOnTransient || maxAttempts < 2) return result;
        if (!isTransientError(result)) return result;

        log.info("Certificate check retry: domain={} attempt=2 prev_class={} prev_error={}",
                domain, result.get("error_class"),
                truncate((String) result.get("error"), 100));

        try { Thread.sleep(retryDelayMs); }
        catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return result;
        }

        Map<String, Object> retry = tryCheckOnce(domain, port, forceProxy);
        if ("error".equals(retry.get("status"))) {
            retry.put("retry_attempted", true);
            log.warn("Certificate check failed after retry: domain={} final_error={}",
                    domain, truncate((String) retry.get("error"), 200));
        } else {
            retry.put("retry_recovered", true);
            log.info("Certificate check recovered on retry: domain={} prev_error={}",
                    domain, truncate((String) result.get("error"), 80));
        }
        return retry;
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

    Map<String, Object> tryCheckOnce(String domain, int port, boolean forceProxy) {
        long startMs = System.currentTimeMillis();
        final boolean useProxy = forceProxy && proxyEnabled() && !shouldBypassProxy(domain);
        try {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            log.debug("Certificate check start: domain={}:{} via={}",
                    domain, port,
                    useProxy ? "proxy(" + proxyHost + ":" + proxyPort + ")" : "direct");
            SSLSocket socket = useProxy
                    ? openViaProxy(factory, domain, port)
                    : (SSLSocket) factory.createSocket();
            try (socket) {
                if (!useProxy) {
                    socket.connect(new InetSocketAddress(domain, port), timeoutSeconds * 1000);
                }
                socket.setSoTimeout(timeoutSeconds * 1000);

                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(Collections.singletonList(new SNIHostName(domain)));
                if ("browser".equalsIgnoreCase(tlsMode)) {
                    params.setApplicationProtocols(BROWSER_ALPN);
                    socket.setEnabledProtocols(BROWSER_TLS_PROTOCOLS);
                }
                socket.setSSLParameters(params);

                if (useProxy) {
                    log.info("[cert-proxy] step=tls-handshake-start domain={} sni={} tlsMode={} alpn={} enabledProtocols={} timeoutSec={}",
                            domain, domain, tlsMode,
                            Arrays.toString(params.getApplicationProtocols()),
                            Arrays.toString(socket.getEnabledProtocols()),
                            timeoutSeconds);
                }
                long handshakeStart = System.currentTimeMillis();
                socket.startHandshake();
                String tlsVersion = socket.getSession().getProtocol();
                if (useProxy) {
                    log.info("[cert-proxy] step=tls-handshake-done domain={} tlsVersion={} cipher={} peerHost={} peerCerts={} elapsed={}ms",
                            domain, tlsVersion, socket.getSession().getCipherSuite(),
                            socket.getSession().getPeerHost(),
                            socket.getSession().getPeerCertificates().length,
                            System.currentTimeMillis() - handshakeStart);
                }

                Certificate[] peerCerts = socket.getSession().getPeerCertificates();
                if (peerCerts.length == 0) return error(domain, "No certificates in chain");

                X509Certificate leaf = (X509Certificate) peerCerts[0];
                Map<String, Object> result = parseLeafCert(leaf, domain);
                result.put("tls_version", tlsVersion);

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
                String revocation = chainValidator.checkRevocation(peerCerts);
                result.put("revocation_status", revocation);

                // If chain is broken due to intermediate expiry, override chain_status
                if ("BROKEN".equals(chainInfo.get("chain_status")) && !"REVOKED".equals(revocation)) {
                    result.put("chain_status", "BROKEN");
                } else if ("REVOKED".equals(revocation)) {
                    result.put("chain_status", "REVOKED");
                }

                // deployment_status is determined by CertificateService (needs inventory lookup)
                result.put("deployment_status", "UNKNOWN");

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
                try {
                    URL url = new URL("https://" + domain + "/");
                    HttpURLConnection hc;
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
                        hc = (HttpURLConnection) url.openConnection();
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
                    hc.disconnect();
                } catch (Exception e) {
                    log.debug("HSTS check failed for {}: {}", domain, e.getMessage());
                    result.put("hsts", null);
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
            logCheckFailure(useProxy, "timeout", domain, port, startMs, e);
            return errorWithClass(domain, "Connection timeout after " + timeoutSeconds + "s", "NETWORK");
        } catch (java.net.UnknownHostException e) {
            logCheckFailure(useProxy, "dns-failure", domain, port, startMs, e);
            return errorWithClass(domain, "Domain resolution failed", "DNS");
        } catch (java.net.ConnectException e) {
            logCheckFailure(useProxy, "connect-refused", domain, port, startMs, e);
            return errorWithClass(domain, "Connection refused/unreachable: " + e.getMessage(), "NETWORK");
        } catch (java.net.NoRouteToHostException e) {
            logCheckFailure(useProxy, "no-route", domain, port, startMs, e);
            return errorWithClass(domain, "No route to host", "NETWORK");
        } catch (java.net.SocketException e) {
            logCheckFailure(useProxy, "socket-error", domain, port, startMs, e);
            return errorWithClass(domain, "Socket error: " + e.getMessage(), "NETWORK");
        } catch (javax.net.ssl.SSLHandshakeException e) {
            logCheckFailure(useProxy, "ssl-handshake", domain, port, startMs, e);
            return errorWithClass(domain, "SSL handshake: " + e.getMessage(), "SSL");
        } catch (javax.net.ssl.SSLException e) {
            logCheckFailure(useProxy, "ssl-error", domain, port, startMs, e);
            return errorWithClass(domain, "SSL Error: " + e.getMessage(), "SSL");
        } catch (Exception e) {
            // Always log unexpected errors with stack trace, regardless of mode.
            log.error("Certificate check unexpected error: domain={}:{} via={} elapsed={}ms",
                    domain, port,
                    useProxy ? "proxy(" + proxyHost + ":" + proxyPort + ")" : "direct",
                    System.currentTimeMillis() - startMs, e);
            return errorWithClass(domain, "Error: " + e.getMessage(), "UNKNOWN");
        }
    }

    /** Failure logger that opts into full stack trace + proxy diagnostics when
     *  the check went through a proxy tunnel (per-domain use_proxy flag).
     *  Direct checks keep the original terse one-liner. */
    private void logCheckFailure(boolean useProxy, String stage, String domain, int port, long startMs, Exception e) {
        long elapsed = System.currentTimeMillis() - startMs;
        if (useProxy) {
            log.warn("[cert-proxy] step={} FAILED domain={}:{} proxy={}:{} elapsed={}ms errType={} errMsg={}",
                    stage, domain, port, proxyHost, proxyPort, elapsed,
                    e.getClass().getSimpleName(), e.getMessage(), e);
        } else {
            log.warn("Certificate check {}: domain={}:{} elapsed={}ms err={}",
                    stage, domain, port, elapsed, e.getMessage());
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

    /** Open raw TCP to proxy, send HTTP CONNECT, then wrap with SSL.
     *
     *  Emits step-by-step INFO logs under the "[cert-proxy]" prefix so a
     *  failed proxy-tunneled cert check (e.g. www.akbankpos.com) can be
     *  diagnosed end-to-end without re-running with a packet capture.
     *  Errors are wrapped with the failing step name and the raw proxy
     *  response (status line + headers) so the caller's WARN/error log
     *  carries enough context. */
    private SSLSocket openViaProxy(SSLSocketFactory factory, String domain, int port) throws IOException {
        boolean authOn = proxyUser != null && !proxyUser.isBlank();
        log.info("[cert-proxy] step=open-tunnel domain={} port={} proxy={}:{} auth={} noProxy='{}' timeoutSec={}",
                domain, port, proxyHost, proxyPort,
                authOn ? "basic(user=" + proxyUser + ")" : "off",
                noProxyList == null ? "" : noProxyList,
                timeoutSeconds);

        Socket raw = new Socket();
        long tcpStart = System.currentTimeMillis();
        try {
            raw.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutSeconds * 1000);
        } catch (IOException e) {
            log.warn("[cert-proxy] step=tcp-connect FAILED domain={} proxy={}:{} elapsed={}ms err={}",
                    domain, proxyHost, proxyPort,
                    System.currentTimeMillis() - tcpStart, e.toString());
            try { raw.close(); } catch (Exception ignored) {}
            throw new IOException("Proxy TCP connect failed: " + proxyHost + ":" + proxyPort
                    + " — " + e.getMessage(), e);
        }
        raw.setSoTimeout(timeoutSeconds * 1000);
        log.info("[cert-proxy] step=tcp-connected domain={} proxy={}:{} localPort={} elapsed={}ms",
                domain, proxyHost, proxyPort, raw.getLocalPort(),
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
