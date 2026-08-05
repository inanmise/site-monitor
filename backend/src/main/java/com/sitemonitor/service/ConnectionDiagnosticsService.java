package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Connection diagnostics: probes a domain over every meaningful combo of
 * {direct, proxy} × {browser, default} TLS modes and reports, per combo, the
 * step reached and timing — plus the pod's own DNS resolution.
 *
 * Built to answer "works from my browser, fails from the pod — why?":
 * if only the browser-mode ClientHello stalls at tls-handshake → WAF JA3
 * fingerprint drop; if every combo fails on one path → egress/IP block on
 * that path; if the pod resolves different IPs than expected → split-DNS.
 */
@Slf4j
@Service
public class ConnectionDiagnosticsService {

    private final CertificateCheckerService checker;
    private final ThreadPoolTaskExecutor executor;

    @Value("${site.monitor.diagnostics.timeout-seconds:5}")
    private int diagTimeoutSeconds;

    @Value("${site.monitor.proxy.host:}")
    private String proxyHost;

    @Value("${site.monitor.proxy.port:0}")
    private int proxyPort;

    public ConnectionDiagnosticsService(CertificateCheckerService checker,
                                        @Qualifier("certCheckExecutor") ThreadPoolTaskExecutor executor) {
        this.checker = checker;
        this.executor = executor;
    }

    public Map<String, Object> diagnose(String domain, int port) {
        long start = System.currentTimeMillis();
        boolean proxyConfigured = proxyHost != null && !proxyHost.isBlank() && proxyPort > 0;

        Map<String, Object> source = resolveSource();
        Map<String, Object> dns = resolveDns(domain);

        List<CertificateCheckerService.CheckOptions> combos = new ArrayList<>();
        combos.add(new CertificateCheckerService.CheckOptions(false, "browser", diagTimeoutSeconds, true));
        combos.add(new CertificateCheckerService.CheckOptions(false, "default", diagTimeoutSeconds, true));
        if (proxyConfigured) {
            combos.add(new CertificateCheckerService.CheckOptions(true, "browser", diagTimeoutSeconds, true));
            combos.add(new CertificateCheckerService.CheckOptions(true, "default", diagTimeoutSeconds, true));
        }

        List<CompletableFuture<Map<String, Object>>> futures = combos.stream()
                .map(opts -> CompletableFuture
                        .supplyAsync(() -> checker.tryCheckOnce(domain, port, opts), executor)
                        .exceptionally(ex -> syntheticError(domain, opts, ex)))
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(diagTimeoutSeconds * 2L + 5, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // stragglers fall back to the synthetic timeout entry below
        }

        List<Map<String, Object>> comboResults = new ArrayList<>();
        for (int i = 0; i < combos.size(); i++) {
            CertificateCheckerService.CheckOptions opts = combos.get(i);
            Map<String, Object> raw = futures.get(i).getNow(null);
            if (raw == null) {
                raw = syntheticError(domain, opts, new TimeoutException("probe did not finish in time"));
            }
            comboResults.add(toComboResult(opts, raw));
        }

        long elapsed = System.currentTimeMillis() - start;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", domain);
        out.put("port", port);
        out.put("proxy_configured", proxyConfigured);
        // Proxy üzerinden giden kontrollerde hangi proxy adresinin kullanıldığı bilgisi
        out.put("proxy_address", proxyConfigured ? proxyHost + ":" + proxyPort : null);
        out.put("source", source);
        out.put("dns", dns);
        // Çalışan JDK'nın TLS istemci parmak izi (sunulan protokol/cipher/ALPN).
        // JDK sürümleri arası handshake farkını (ör. akbankpos WAF) kök-neden
        // karşılaştırması için: aynı teşhisi JDK 21 ve JDK 25 instance'larında
        // koşup bu bölümü + combo cipher'larını karşılaştır.
        out.put("tls_client", buildTlsClientInfo());
        out.put("combos", comboResults);
        out.put("elapsed_ms", elapsed);
        log.info("Diagnostics complete: domain={}:{} proxyConfigured={} combos={} elapsed={}ms",
                domain, port, proxyConfigured, comboResults.size(), elapsed);
        return out;
    }

    /** Where this probe runs FROM: hostname (the pod name on K8s) plus all
     *  non-loopback, non-link-local interface addresses. Shown even when every
     *  combo fails, so the network admin knows which source to trace. Note the
     *  pod IP may be SNAT'ed at cluster egress — hostname + pod IP is still
     *  what the cluster-side trace needs. Package-private for test stubbing. */
    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    Map<String, Object> resolveSource() {
        Map<String, Object> src = new LinkedHashMap<>();
        String hostname = null;
        try { hostname = InetAddress.getLocalHost().getHostName(); } catch (Exception ignored) { }
        List<String> ips = new ArrayList<>();
        try {
            var ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                var nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                var addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    ips.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        // IPv4 first — that's what firewall rules are usually written against
        ips.sort((x, y) -> Boolean.compare(x.contains(":"), y.contains(":")));
        src.put("hostname", hostname);
        src.put("ips", ips);
        // K8s Downward API (deployment env) — pod'un koştuğu worker node + pod kimliği.
        // Node'a bağlı egress/akbankpos sorununu korele etmek için. K8s dışında null.
        src.put("node_name", emptyToNull(System.getenv("NODE_NAME")));
        src.put("pod_name",  emptyToNull(System.getenv("POD_NAME")));
        src.put("pod_ip",    emptyToNull(System.getenv("POD_IP")));
        return src;
    }

    /** Package-private for deterministic test stubbing (real DNS may hijack NXDOMAIN). */
    Map<String, Object> resolveDns(String domain) {
        Map<String, Object> dns = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        try {
            List<String> ips = new ArrayList<>();
            for (InetAddress a : InetAddress.getAllByName(domain)) {
                ips.add(a.getHostAddress());
            }
            dns.put("ips", ips);
            dns.put("error", null);
        } catch (Exception e) {
            // Non-fatal: proxy combos can still succeed when the pod cannot
            // resolve the name — that asymmetry IS the split-DNS finding.
            dns.put("ips", Collections.emptyList());
            dns.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        dns.put("elapsed_ms", System.currentTimeMillis() - t0);
        return dns;
    }

    private Map<String, Object> toComboResult(CertificateCheckerService.CheckOptions opts, Map<String, Object> raw) {
        String via = opts.viaProxy() ? "proxy" : "direct";
        boolean ok = !"error".equals(raw.get("status"));
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", via + "+" + opts.tlsMode());
        c.put("via", via);
        c.put("tls_mode", opts.tlsMode());
        c.put("status", ok ? "ok" : "error");
        c.put("step_reached", ok ? "cert-ok" : String.valueOf(raw.getOrDefault("error_stage", "unknown")));
        c.put("elapsed_ms", raw.get("elapsed_ms"));
        c.put("source_ip", raw.get("source_ip"));
        c.put("source_port", raw.get("source_port"));
        c.put("peer_ip", raw.get("peer_ip"));
        c.put("peer_port", raw.get("peer_port"));
        if (ok) {
            c.put("subject", raw.get("subject"));
            c.put("days_remaining", raw.get("days_remaining"));
            c.put("tls_version", raw.get("tls_version"));
            c.put("cipher_suite", raw.get("cipher_suite"));
            c.put("alpn", raw.get("alpn"));
            c.put("error", null);
        } else {
            c.put("error_class", raw.get("error_class"));
            c.put("error", raw.get("error"));
        }
        return c;
    }

    /**
     * Çalışan JVM'in TLS istemci parmak izi: JDK sürümü + "browser" (TLS 1.2 +
     * ALPN) ve "default" modlarında sunulan protokoller/cipher suite'ler (+ varsa
     * imza şemaları / named-group'lar). JDK sürümleri arası ClientHello farkını
     * kök-neden olarak görünür kılar — aynı teşhisi iki JDK instance'ında koşup
     * karşılaştır. (Not: imza şeması / named-group JDK VARSAYILANLARI public
     * API'de null döner; bunlar için javax.net.debug=ssl:handshake gerekir.)
     */
    Map<String, Object> buildTlsClientInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("java_version", System.getProperty("java.version"));
        info.put("java_vendor", System.getProperty("java.vendor"));
        info.put("browser", offeredParams(new String[]{"TLSv1.2"}, new String[]{"h2", "http/1.1"}));
        info.put("default", offeredParams(null, null));
        return info;
    }

    /** Bağlanmadan, verilen modda soketin SUNACAĞI TLS parametrelerini okur. */
    private Map<String, Object> offeredParams(String[] protocols, String[] alpn) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            SSLContext ctx = SSLContext.getDefault();
            try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket()) {
                if (protocols != null) s.setEnabledProtocols(protocols);
                SSLParameters p = s.getSSLParameters();
                if (alpn != null) p.setApplicationProtocols(alpn);
                s.setSSLParameters(p);
                SSLParameters eff = s.getSSLParameters();
                m.put("protocols", s.getEnabledProtocols());
                m.put("cipher_suites", s.getEnabledCipherSuites());
                m.put("signature_schemes", eff.getSignatureSchemes()); // null = JDK varsayılanı
                m.put("named_groups", eff.getNamedGroups());           // null = JDK varsayılanı
                m.put("application_protocols", eff.getApplicationProtocols());
            }
        } catch (Exception e) {
            m.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return m;
    }

    private Map<String, Object> syntheticError(String domain, CertificateCheckerService.CheckOptions opts, Throwable ex) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", domain);
        r.put("status", "error");
        r.put("error", "Probe failed: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        r.put("error_class", "UNKNOWN");
        r.put("error_stage", "unknown");
        r.put("via", opts.viaProxy() ? "proxy" : "direct");
        r.put("tls_mode_used", opts.tlsMode());
        r.put("elapsed_ms", null);
        r.put("source_ip", null);
        r.put("source_port", null);
        r.put("peer_ip", null);
        r.put("peer_port", null);
        return r;
    }
}
