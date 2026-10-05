package com.sitemonitor.service.diagnose;

import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.service.HttpCheckerService;
import com.sitemonitor.service.PortCheckerService;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.http.diagnose.HttpDiagMasker;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;

import static com.sitemonitor.service.diagnose.NetDiagFindings.FAIL;
import static com.sitemonitor.service.diagnose.NetDiagFindings.INFO;
import static com.sitemonitor.service.diagnose.NetDiagFindings.WARN;
import static com.sitemonitor.service.diagnose.NetDiagFindings.params;

/**
 * PORT UÇTAN UCA TANILAMASI (2026-10-05). Adımlar: {@code policy} (SSRF — izleme kuralı) → {@code dns} (tüm IP'ler) →
 * yol başına {@code connect} (doğrudan: izlemenin IP sürümüne göre HER IP ayrı ayrı; ilk açılan IP'de devam — izlemenin
 * çok-A kuralı) ya da {@code proxy_tunnel} (vekil: {@code CONNECT} durum satırı, vekilin izin verdiği portlar) → türe
 * göre {@code tls} (protokol, şifre, ALPN, sertifika konusu/yayıncı/SAN/bitiş, güven BİLGİ olarak, ad eşleşmesi) /
 * {@code http} (istek + durum satırı, beklenen kalıp) / {@code banner} (gönder + bekle; alınan baytlar maskeli
 * yazdırılabilir metin + ≤ 256 B onaltılık) / {@code udp} (gönder/al, ICMP port-unreachable).
 *
 * <p>Vekil tanımlıysa ÖTEKİ yol da paralel denenir (UDP hariç — vekilden geçemez); izlemenin yolu düşüp öteki
 * çalışıyorsa {@code PATH_DIFFERS}. {@code client_check} = izlemenin GERÇEK kontrolü ({@link PortCheckerService#check}),
 * kayıt YAZILMADAN. Alarm değerlendirilmez, CA pinlenmez, ayar değişmez.
 */
@Service
public class PortDiagnosticsService {

    /** {@code diagnostic_runs.run_type}. */
    public static final String RUN_TYPE = "PORT_DIAG";
    static final int MAX_IPS = 4;
    static final int BANNER_READ = 1024;
    static final int HEX_PREVIEW = 256;
    static final int TEXT_PREVIEW = 256;
    static final int CERT_SOON_DAYS = 30;

    private final NetDiagNetwork net;
    private final NetDiagWorkers workers;
    private final PortCheckerService portChecker;

    UnaryOperator<String> env = System::getenv;

    public PortDiagnosticsService(NetDiagNetwork net, NetDiagWorkers workers, PortCheckerService portChecker) {
        this.net = net;
        this.workers = workers;
        this.portChecker = portChecker;
    }

    /** Yolun sonucu. */
    static final class PathResult {
        final String key;
        final String route;
        final List<Map<String, Object>> steps = new ArrayList<>();
        final List<Map<String, Object>> findings = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        String ip;
        Long ms;
        /** Kontrolün kendisi (bağlantı / el sıkışması / durum kodu / banner / UDP yanıtı) tamamlandı mı. */
        boolean completed;

        PathResult(String key, String route) {
            this.key = key;
            this.route = route;
        }

        boolean ok() {
            if (!completed) return false;
            for (Map<String, Object> f : findings) if (FAIL.equals(f.get("severity"))) return false;
            return true;
        }

        void find(String code, String severity, Map<String, Object> p) { findings.add(NetDiagFindings.finding(code, severity, key, p)); }
        void info(String s) { lines.add("* " + s); }
        void sent(String s) { lines.add("> " + s); }
        void recv(String s) { lines.add("< " + s); }
    }

    /** İzlemenin tanılanan ayarları (sabit). */
    record Spec(String host, int port, String type, int timeoutMs, String send, String expect, String ipVersion) {}

    public Map<String, Object> diagnose(PortMonitor m) {
        NetDiagRun run = new NetDiagRun();
        HttpDiagMasker masker = NetDiagSupport.masker(net);
        String host = m.getHost() == null ? "" : m.getHost().trim().toLowerCase(Locale.ROOT);
        int port = m.getPort() == null ? 0 : m.getPort();
        String type = m.getProtocol() == null ? "TCP" : m.getProtocol().trim().toUpperCase(Locale.ROOT);
        if (!List.of("TCP", "TLS", "HTTP", "BANNER", "UDP").contains(type)) type = "TCP";
        String ipVersion = NetDiagSupport.ipVersion(m.getIpVersion());
        int timeoutMs = NetDiagRun.clampTimeout(m.getTimeoutMs(), 5000);
        Spec spec = new Spec(host, port, type, timeoutMs, blankToNull(m.getSendData()), blankToNull(m.getExpect()), ipVersion);
        boolean udp = "UDP".equals(type);

        boolean proxyConfigured = net.proxyConfigured();
        ProxyPolicyService.Decision d = null;
        try { d = portChecker.proxyDecision(host, type, m.getUseProxy()); } catch (Exception ignore) { /* karar yoksa doğrudan */ }
        if (d == null) d = ProxyPolicyService.Decision.direct("monitor");
        boolean monitorViaProxy = d.viaProxy() && proxyConfigured && !udp;
        String ownRoute = monitorViaProxy ? "proxy" : "direct";
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("mode", ProxyPolicyService.normalizeModeDefaultOff(m.getUseProxy()));
        decision.put("source", d.source());
        decision.put("wanted", d.wanted());
        decision.put("bypassed", d.bypassed());

        run.info("Port tanılaması: " + host + ":" + port + " (" + type + ", IP sürümü " + ipVersion + ", zaman aşımı " + timeoutMs
                + " ms, izlemenin yolu " + (monitorViaProxy ? "vekil " + net.proxyAddress() : "doğrudan") + ")");
        if (udp && proxyConfigured) {
            run.finding(NetDiagFindings.PROXY_NOT_APPLICABLE, INFO, params("proxy", net.proxyAddress(), "protocol", "UDP"));
            run.info("UDP HTTP vekilinden geçemez — her zaman doğrudan.");
        }

        // ── policy + dns ──
        NetDiagRun.Step policy = run.step("policy").put("host", host);
        NetDiagRun.Step dns = run.step("dns").put("host", host).put("ip_version", ipVersion);
        List<InetAddress> vetted = null;
        List<InetAddress> candidates = List.of();
        try {
            vetted = net.vet(host);
            policy.put("result", "allowed").ok();
        } catch (SsrfGuard.UnresolvableHostException ue) {
            // İzlemenin kontrolü de (vekil yolunda bile) hedefi ÖNCE yerelde doğrular ve burada düşer — tanılama aynı
            // kararı verir; tünel / bağlantı denenmez.
            policy.put("result", "not_evaluated").skip("dns");
            run.info("Ad çözümlenemedi: " + host);
            dns.fail(ue);
            run.finding(NetDiagFindings.DNS_FAILED, FAIL, params("host", host, "error", NetDiagRun.message(ue)));
        } catch (SsrfGuard.BlockedException be) {
            policy.put("result", "blocked").put("reason", NetDiagRun.message(be)).fail(be);
            run.skip("dns", "policy");
            run.info("Hedef politika gereği engellendi: " + NetDiagRun.message(be));
            run.finding(NetDiagFindings.POLICY_BLOCKED, FAIL, params("host", host, "reason", NetDiagRun.message(be)));
        }
        if (vetted != null) {
            candidates = NetDiagSupport.family(vetted, ipVersion);
            if (candidates.size() > MAX_IPS) candidates = candidates.subList(0, MAX_IPS);
            dns.put("addresses", NetDiagSupport.ips(vetted))
                    .put("v4", NetDiagSupport.count(vetted, false))
                    .put("v6", NetDiagSupport.count(vetted, true));
            run.info("Çözümlenen adresler: " + String.join(", ", NetDiagSupport.ips(vetted)));
            if (candidates.isEmpty()) {
                dns.fail("no " + ipVersion + " address");
                if (!monitorViaProxy) {
                    run.finding(NetDiagFindings.NO_ADDRESS_FOR_IP_VERSION, FAIL, params("host", host, "ip_version", ipVersion,
                            "available", String.join(", ", NetDiagSupport.ips(vetted))));
                }
            } else {
                dns.ok();
            }
        }

        boolean policyBlocked = "blocked".equals(policy.detail().get("result"));
        List<Map<String, Object>> paths = null;
        Map<String, Object> clientCheck = PingDiagnosticsService.clientSkipped(policyBlocked ? "policy" : "dns");
        PathResult monitor = null;
        if (vetted != null) {
            // İzlemenin GERÇEK kontrolü, paralel — kayıt YOK.
            Future<Map<String, Object>> clientF = workers.submit(() -> portChecker.check(m));
            List<InetAddress> cands = candidates;
            Future<PathResult> monF = workers.submit(() -> runPath("monitor", ownRoute, spec, cands, run));
            Future<PathResult> altF = null;
            if (proxyConfigured && !udp) {
                String alt = monitorViaProxy ? "direct" : "proxy";
                altF = workers.submit(() -> runPath("alternate", alt, spec, cands, run));
            }
            monitor = NetDiagWorkers.await(monF, run.deadline());
            if (monitor == null) {
                run.markTimeLimited();
                monitor = new PathResult("monitor", ownRoute);   // tamamlanmadı → başarısız; RUN_TIME_LIMIT zarfta eklenir
            }
            PathResult alternate = altF == null ? null : NetDiagWorkers.await(altF, run.deadline());
            if (altF != null && alternate == null) run.markTimeLimited();

            run.blank();
            run.raw("── " + (monitorViaProxy ? "İzlemenin yolu: vekil" : "İzlemenin yolu: doğrudan") + " ──");
            for (String l : monitor.lines) run.raw(l);
            run.steps().addAll(monitor.steps);
            for (Map<String, Object> f : monitor.findings) run.add(f);
            if (alternate != null) {
                run.blank();
                run.raw("── Öteki yol: " + ("proxy".equals(alternate.route) ? "vekil" : "doğrudan") + " ──");
                for (String l : alternate.lines) run.raw(l);
            }

            boolean monOk = monitor.ok();
            if (monOk) {
                run.finding(NetDiagFindings.PORT_OK, INFO, "monitor", params("protocol", type, "ms", monitor.ms,
                        "route", ownRoute, "ip", monitor.ip));
                if (Boolean.TRUE.equals(m.getSlowResponseEnabled()) && m.getSlowThresholdMs() != null
                        && monitor.ms != null && monitor.ms > m.getSlowThresholdMs()) {
                    run.finding(NetDiagFindings.SLOW_RESPONSE, WARN, "monitor", params("ms", monitor.ms, "threshold_ms", m.getSlowThresholdMs()));
                }
            }
            if (alternate != null) {
                if (!monOk && alternate.ok()) {
                    run.finding(NetDiagFindings.PATH_DIFFERS, FAIL, null, params("failing_route", monitor.route,
                            "working_route", alternate.route, "protocol", type));
                }
                paths = new ArrayList<>();
                paths.add(pathMap(monitor));
                paths.add(pathMap(alternate));
            }

            Map<String, Object> c = NetDiagWorkers.await(clientF, run.deadline());
            if (c == null) run.markTimeLimited();
            clientCheck = clientResult(c);
            if (c != null && Boolean.TRUE.equals(clientCheck.get("ok")) != monOk) {
                run.finding(NetDiagFindings.CLIENT_MISMATCH, WARN, params("diag", monOk ? "open" : "closed",
                        "client", Boolean.TRUE.equals(clientCheck.get("ok")) ? "open" : "closed",
                        "client_error", clientCheck.get("error")));
            }
        } else {
            NetDiagRun.skip(run.steps(), udp ? "udp" : monitorViaProxy ? "proxy_tunnel" : "connect", policyBlocked ? "policy" : "dns");
        }

        Map<String, Object> mon = new LinkedHashMap<>();
        mon.put("id", m.getId());
        mon.put("name", m.getName());
        mon.put("host", host);
        mon.put("port", port);
        mon.put("protocol", type);
        mon.put("ip_version", ipVersion);
        mon.put("timeout_ms", m.getTimeoutMs());
        mon.put("expect", spec.expect());
        mon.put("send_data", spec.send() != null);
        mon.put("proxy_mode", decision.get("mode"));
        mon.put("slow_threshold_ms", Boolean.TRUE.equals(m.getSlowResponseEnabled()) ? m.getSlowThresholdMs() : null);
        Map<String, Object> tgt = new LinkedHashMap<>();
        tgt.put("host", host);
        tgt.put("port", port);
        tgt.put("protocol", type);
        tgt.put("ip_version", ipVersion);
        Map<String, Object> extra = new LinkedHashMap<>();
        Map<String, Object> proxy = NetDiagSupport.proxyMap(net);
        proxy.put("connect_ports", safePorts());
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("available", paths != null);
        comparison.put("differs", paths != null && paths.size() > 1
                && !java.util.Objects.equals(paths.get(0).get("outcome"), paths.get(1).get("outcome")));
        extra.put("comparison", comparison);
        return NetDiagSupport.envelope("port", run, mon, tgt, NetDiagSupport.route(ownRoute, proxyConfigured, decision),
                proxy, NetDiagSupport.source(env), NetDiagFindings.PORT_OK, paths, clientCheck, extra, null, masker);
    }

    private List<Integer> safePorts() {
        try { return portChecker.proxyConnectPorts(); } catch (Exception e) { return List.of(); }
    }

    static Map<String, Object> pathMap(PathResult p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", p.key);
        m.put("route", p.route);
        m.put("outcome", p.ok() ? "ok" : "fail");
        m.put("ms", p.ms);
        m.put("ip", p.ip);
        m.put("steps", p.steps);
        List<Map<String, Object>> ordered = NetDiagFindings.ordered(p.findings);
        Map<String, Object> v = NetDiagFindings.verdict(ordered, NetDiagFindings.PORT_OK);
        if (p.ok() && NetDiagFindings.INCONCLUSIVE.equals(v.get("code"))) {
            v.put("code", NetDiagFindings.PORT_OK);
            v.put("status", "ok");
        }
        m.put("verdict", v);
        m.put("findings", ordered);
        return m;
    }

    // ── Yol ──────────────────────────────────────────────────────────────────────────────────

    PathResult runPath(String key, String route, Spec spec, List<InetAddress> candidates, NetDiagRun run) {
        PathResult p = new PathResult(key, route);
        long t0 = System.nanoTime();
        Socket socket = null;
        try {
            if ("UDP".equals(spec.type())) {
                p.completed = udp(p, spec, candidates, run);
            } else {
                socket = "proxy".equals(route) ? tunnel(p, spec, run) : connect(p, spec, candidates, run);
                if (socket != null) {
                    switch (spec.type()) {
                        case "TLS" -> {
                            Socket s = tls(p, spec, socket, run, "monitor".equals(key));
                            if (s != null) {
                                socket = s;
                                p.completed = true;
                            }
                        }
                        case "HTTP" -> {
                            boolean https = spec.port() == 443 || spec.port() == 8443;
                            Socket s = socket;
                            if (https) s = tls(p, spec, socket, run, "monitor".equals(key));
                            if (s != null) {
                                socket = s;
                                p.completed = http(p, spec, s, run);
                            } else {
                                NetDiagRun.skip(p.steps, "http", "tls");
                            }
                        }
                        case "BANNER" -> p.completed = banner(p, spec, socket, run);
                        default -> p.completed = true;   // TCP: bağlantının kendisi kontrol
                    }
                }
            }
        } finally {
            if (socket != null) try { socket.close(); } catch (Exception ignore) { /* kapatma önemsiz */ }
        }
        p.ms = (System.nanoTime() - t0) / 1_000_000L;
        return p;
    }

    /** Doğrudan yol: aday IP'lere paralel bağlan; ilk (sıraca) açılanla devam — izlemenin çok-A kuralı. */
    private Socket connect(PathResult p, Spec spec, List<InetAddress> candidates, NetDiagRun run) {
        NetDiagRun.Step step = run.step("connect", p.steps).put("route", "direct").put("port", spec.port());
        if (candidates.isEmpty()) {
            step.skip("dns");
            // Ad çözüldü ama izlemenin IP sürümünde adres yok (çözülemeyen ad yolları hiç çalıştırmaz). İzlemenin yolunda
            // nedeni üst düzey bulgu zaten anlatır; öteki yolun kendi hükmü için yol bulgusu.
            if (!"monitor".equals(p.key)) {
                p.find(NetDiagFindings.NO_ADDRESS_FOR_IP_VERSION, FAIL, params("host", spec.host(), "ip_version", spec.ipVersion(),
                        "available", null));
            }
            return null;
        }
        int timeout = run.budget(spec.timeoutMs());
        boolean single = !"auto".equals(spec.ipVersion());
        List<InetAddress> targets = single ? List.of(candidates.get(0)) : candidates;
        List<Future<Object[]>> futures = new ArrayList<>();
        for (InetAddress ip : targets) {
            futures.add(workers.submit(() -> {
                long t = System.nanoTime();
                try {
                    Socket s = net.connect(ip, spec.port(), timeout);
                    return new Object[]{ ip, s, null, (System.nanoTime() - t) / 1_000_000L };
                } catch (Exception e) {
                    return new Object[]{ ip, null, e, (System.nanoTime() - t) / 1_000_000L };
                }
            }));
        }
        List<Map<String, Object>> tried = new ArrayList<>();
        Socket chosen = null;
        Object[] firstFail = null;
        int down = 0;
        List<String> downIps = new ArrayList<>();
        for (Future<Object[]> f : futures) {
            Object[] r = NetDiagWorkers.await(f, run.deadline());
            if (r == null) { run.markTimeLimited(); continue; }
            InetAddress ip = (InetAddress) r[0];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ip", NetDiagRun.ip(ip));
            row.put("ms", r[3]);
            if (r[1] != null) {
                row.put("result", "open");
                if (chosen == null) {
                    chosen = (Socket) r[1];
                    p.ip = NetDiagRun.ip(ip);
                } else {
                    try { ((Socket) r[1]).close(); } catch (Exception ignore) { /* yalnız ölçüm */ }
                }
                p.info("TCP " + NetDiagRun.ip(ip) + ":" + spec.port() + " → açık (" + r[3] + " ms)");
            } else {
                Throwable e = (Throwable) r[2];
                String outcome = NetDiagRun.connectOutcome(e);
                row.put("result", outcome);
                row.put("error", NetDiagRun.message(e));
                down++;
                downIps.add(NetDiagRun.ip(ip) + " (" + outcome + ")");
                if (firstFail == null) firstFail = r;
                p.info("TCP " + NetDiagRun.ip(ip) + ":" + spec.port() + " → " + outcome + " (" + r[3] + " ms): " + NetDiagRun.message(e));
            }
            tried.add(row);
        }
        step.put("tried", tried).put("timeout_ms", timeout);
        if (chosen != null) {
            step.put("ip", p.ip).ok();
            if (down > 0) {
                p.find(NetDiagFindings.SOME_IPS_DOWN, WARN, params("down", down, "total", tried.size(), "ips", String.join(", ", downIps),
                        "port", spec.port()));
            }
            return chosen;
        }
        if (firstFail == null) {
            step.fail("timeout");
            p.find(NetDiagFindings.CONNECT_TIMEOUT_FILTERED, FAIL, params("host", spec.host(), "port", spec.port(), "ms", timeout));
            return null;
        }
        Throwable e = (Throwable) firstFail[2];
        String outcome = NetDiagRun.connectOutcome(e);
        step.put("ip", NetDiagRun.ip((InetAddress) firstFail[0])).fail(e);
        Map<String, Object> pr = params("host", spec.host(), "port", spec.port(), "ip", NetDiagRun.ip((InetAddress) firstFail[0]),
                "ms", "timeout".equals(outcome) ? timeout : firstFail[3], "error", NetDiagRun.message(e));
        switch (outcome) {
            case "refused" -> p.find(NetDiagFindings.CONNECT_REFUSED, FAIL, pr);
            case "timeout" -> p.find(NetDiagFindings.CONNECT_TIMEOUT_FILTERED, FAIL, pr);
            default -> p.find(NetDiagFindings.NETWORK_UNREACHABLE, FAIL, pr);
        }
        return null;
    }

    /** Vekil yolu: {@code CONNECT host:port}; ret / ulaşılamazlık ayrı bulgular. */
    private Socket tunnel(PathResult p, Spec spec, NetDiagRun run) {
        List<Integer> allowed = safePorts();
        boolean portAllowed = allowed.contains(spec.port());
        NetDiagRun.Step step = run.step("proxy_tunnel", p.steps)
                .put("route", "proxy")
                .put("proxy", net.proxyAddress())
                .put("request_line", "CONNECT " + spec.host() + ":" + spec.port() + " HTTP/1.1")
                .put("allowed_ports", allowed)
                .put("port_allowed", portAllowed);
        int timeout = run.budget(spec.timeoutMs());
        p.info("Vekil " + net.proxyAddress() + " üzerinden tünel isteniyor");
        p.sent("CONNECT " + spec.host() + ":" + spec.port() + " HTTP/1.1");
        p.sent("Host: " + spec.host() + ":" + spec.port());
        if (net.proxyAuth()) p.sent("Proxy-Authorization: " + HttpDiagMasker.MASK);
        try {
            NetDiagNetwork.Tunnel t = net.tunnel(spec.host(), spec.port(), timeout);
            step.put("status_line", t.statusLine());
            p.recv(t.statusLine());
            step.ok();
            return t.socket();
        } catch (NetDiagNetwork.TunnelRefusedException tr) {
            step.put("status_line", tr.statusLine()).fail(tr);
            p.recv(tr.statusLine() == null ? "(boş yanıt)" : tr.statusLine());
            Map<String, Object> pr = params("port", spec.port(), "allowed", allowed, "status_line", tr.statusLine(),
                    "proxy", net.proxyAddress());
            if (!portAllowed) p.find(NetDiagFindings.PROXY_PORT_NOT_ALLOWED, FAIL, pr);
            else p.find(NetDiagFindings.PROXY_REFUSED, FAIL, pr);
            return null;
        } catch (Exception e) {
            step.fail(e);
            p.info("Vekile ulaşılamadı: " + NetDiagRun.message(e));
            p.find(NetDiagFindings.PROXY_UNREACHABLE, FAIL, params("proxy", net.proxyAddress(), "error", NetDiagRun.message(e),
                    "reason", NetDiagRun.connectOutcome(e)));
            return null;
        }
    }

    /** TLS el sıkışması (güven-hepsi); güven / ad / süre BİLGİSİ yalnız izlemenin yolunda bulgu olur. */
    private Socket tls(PathResult p, Spec spec, Socket raw, NetDiagRun run, boolean findInfo) {
        NetDiagRun.Step step = run.step("tls", p.steps).put("sni", NetDiagSupportHost.sni(spec.host()));
        int timeout = run.budget(spec.timeoutMs());
        try {
            NetDiagNetwork.TlsSession s = net.tls(raw, spec.host(), spec.port(), timeout);
            step.put("protocol", s.protocol()).put("cipher", s.cipher()).put("alpn", s.alpn());
            p.info("TLS el sıkışması tamam: " + s.protocol() + " · " + s.cipher());
            X509Certificate leaf = s.chain() != null && s.chain().length > 0 ? s.chain()[0] : null;
            Long days = null;
            Boolean nameOk = null;
            if (leaf != null) {
                days = Duration.between(Instant.now(), leaf.getNotAfter().toInstant()).toDays();
                if (leaf.getNotAfter().toInstant().isBefore(Instant.now())) days = Math.min(days, -1L);
                nameOk = NetDiagSupportHost.hostnameMatches(leaf, spec.host());
                step.put("subject", leaf.getSubjectX500Principal().getName())
                        .put("issuer", leaf.getIssuerX500Principal().getName())
                        .put("san", sans(leaf))
                        .put("not_after", leaf.getNotAfter().toInstant().truncatedTo(ChronoUnit.SECONDS).toString())
                        .put("days_left", days)
                        .put("hostname_match", nameOk)
                        .put("chain_length", s.chain().length);
                p.info("Sertifika: " + leaf.getSubjectX500Principal().getName() + " · bitiş "
                        + leaf.getNotAfter().toInstant().truncatedTo(ChronoUnit.SECONDS));
            }
            NetDiagNetwork.Trust trust = net.trust(s.chain());
            step.put("trusted", trust.trusted()).put("trust_reason", trust.reason());
            step.ok();
            if (findInfo) {
                if (Boolean.FALSE.equals(trust.trusted())) {
                    p.find(NetDiagFindings.TLS_UNTRUSTED, WARN, params("error", trust.reason(), "host", spec.host()));
                }
                if (Boolean.FALSE.equals(nameOk)) {
                    p.find(NetDiagFindings.TLS_HOSTNAME_MISMATCH, WARN, params("host", spec.host(),
                            "subject", leaf.getSubjectX500Principal().getName(), "san", String.join(", ", sans(leaf))));
                }
                if (days != null && days < 0) {
                    p.find(NetDiagFindings.CERT_EXPIRED, WARN, params("not_after", step.detail().get("not_after"), "days_left", days));
                } else if (days != null && days <= CERT_SOON_DAYS) {
                    p.find(NetDiagFindings.CERT_EXPIRES_SOON, WARN, params("not_after", step.detail().get("not_after"), "days_left", days));
                }
            }
            return s.socket();
        } catch (Exception e) {
            step.fail(e);
            p.info("TLS el sıkışması başarısız: " + NetDiagRun.message(e));
            p.find(NetDiagFindings.TLS_HANDSHAKE_FAILED, FAIL, params("host", spec.host(), "port", spec.port(),
                    "error", NetDiagRun.message(e)));
            return null;
        }
    }

    /** @return durum kodu beklenen kalıba uydu mu */
    private boolean http(PathResult p, Spec spec, Socket s, NetDiagRun run) {
        String path = spec.send() != null ? spec.send().trim() : "/";
        if (path.isEmpty()) path = "/";
        if (!path.startsWith("/")) path = "/" + path;
        String hostHeader = (spec.port() == 80 || spec.port() == 443) ? spec.host() : spec.host() + ":" + spec.port();
        String requestLine = "GET " + path + " HTTP/1.1";
        String expected = spec.expect() == null ? "2xx-3xx" : spec.expect();
        NetDiagRun.Step step = run.step("http", p.steps).put("request_line", requestLine).put("expected", expected);
        int timeout = run.budget(spec.timeoutMs());
        try {
            s.setSoTimeout(timeout);
            String req = requestLine + "\r\nHost: " + hostHeader + "\r\nUser-Agent: SiteMonitor-PortCheck\r\nConnection: close\r\n\r\n";
            p.sent(requestLine);
            p.sent("Host: " + hostHeader);
            p.sent("User-Agent: SiteMonitor-PortCheck");
            OutputStream out = s.getOutputStream();
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = readStatusLine(s.getInputStream(), System.nanoTime() + Math.max(1, timeout) * 1_000_000L);
            step.put("status_line", status);
            if (status != null) p.recv(status);
            if (status == null || !status.startsWith("HTTP/") || status.length() < 12) {
                step.fail("invalid HTTP response");
                p.find(NetDiagFindings.HTTP_BAD_RESPONSE, FAIL, params("error", status == null ? "(boş yanıt)" : status, "reason", "invalid"));
                return false;
            }
            int code = Integer.parseInt(status.substring(9, 12));
            boolean ok = PortCheckerService.httpStatusMatches(code, spec.expect());
            step.put("status", code).put("matched", ok);
            if (ok) {
                step.ok();
            } else {
                step.fail("HTTP " + code);
                p.find(NetDiagFindings.HTTP_STATUS_MISMATCH, FAIL, params("status", code, "expected", expected));
            }
            return ok;
        } catch (SocketTimeoutException te) {
            step.fail(te);
            p.find(NetDiagFindings.HTTP_BAD_RESPONSE, FAIL, params("error", NetDiagRun.message(te), "reason", "timeout", "ms", timeout));
        } catch (Exception e) {
            step.fail(e);
            p.find(NetDiagFindings.HTTP_BAD_RESPONSE, FAIL, params("error", NetDiagRun.message(e), "reason", "error"));
        }
        return false;
    }

    /** @return beklenen yanıt geldi mi */
    private boolean banner(PathResult p, Spec spec, Socket s, NetDiagRun run) {
        NetDiagRun.Step step = run.step("banner", p.steps).put("expected", spec.expect());
        int timeout = run.budget(spec.timeoutMs());
        try {
            s.setSoTimeout(timeout);
            if (spec.send() != null) {
                byte[] payload = unescape(spec.send()).getBytes(StandardCharsets.ISO_8859_1);
                step.put("sent_bytes", payload.length).put("sent_preview", NetDiagRun.printable(payload, payload.length, 120));
                p.sent("(" + payload.length + " bayt) " + NetDiagRun.printable(payload, payload.length, 120));
                s.getOutputStream().write(payload);
                s.getOutputStream().flush();
            } else {
                step.put("sent_bytes", 0);
            }
            byte[] buf = new byte[BANNER_READ];
            int n;
            try {
                n = s.getInputStream().read(buf);
            } catch (SocketTimeoutException te) {
                n = 0;
                step.put("read_timeout", true);
            }
            int got = Math.max(0, n);
            String text = new String(buf, 0, got, StandardCharsets.ISO_8859_1).trim();
            String preview = NetDiagRun.printable(buf, got, TEXT_PREVIEW);
            step.put("received_bytes", got)
                    .put("text_preview", preview)
                    .put("hex_preview", NetDiagRun.hex(buf, got, HEX_PREVIEW))
                    .put("timeout_ms", timeout);
            p.recv(got > 0 ? "(" + got + " bayt) " + (preview.length() > 80 ? preview.substring(0, 80) + "…" : preview) : "(yanıt yok)");
            boolean matched = spec.expect() != null ? text.contains(spec.expect().trim()) : got > 0;
            step.put("matched", matched);
            if (matched) {
                step.ok();
                return true;
            } else if (got == 0) {
                step.fail("no data");
                p.find(NetDiagFindings.BANNER_EMPTY, FAIL, params("expected", spec.expect(), "ms", timeout));
            } else {
                step.fail("expected text not found");
                String shortB = text.length() > 80 ? text.substring(0, 80) + "…" : text;
                p.find(NetDiagFindings.BANNER_MISMATCH, FAIL, params("expected", spec.expect(), "got", NetDiagRun.printable(
                        shortB.getBytes(StandardCharsets.ISO_8859_1), shortB.length(), 120), "bytes", got));
            }
        } catch (Exception e) {
            step.fail(e);
            p.find(NetDiagFindings.BANNER_EMPTY, FAIL, params("expected", spec.expect(), "error", NetDiagRun.message(e)));
        }
        return false;
    }

    /** @return UDP yanıtı alındı mı */
    private boolean udp(PathResult p, Spec spec, List<InetAddress> candidates, NetDiagRun run) {
        NetDiagRun.Step step = run.step("udp", p.steps).put("port", spec.port());
        if (candidates.isEmpty()) {
            step.skip("dns");
            return false;
        }
        InetAddress ip = candidates.get(0);
        p.ip = NetDiagRun.ip(ip);
        byte[] payload = spec.send() != null ? unescape(spec.send()).getBytes(StandardCharsets.ISO_8859_1) : new byte[]{0};
        int timeout = run.budget(spec.timeoutMs());
        step.put("ip", p.ip).put("sent_bytes", payload.length).put("timeout_ms", timeout);
        p.sent("UDP " + p.ip + ":" + spec.port() + " (" + payload.length + " bayt)");
        NetDiagNetwork.UdpResult r = net.udp(ip, spec.port(), payload, timeout);
        step.put("outcome", r.outcome()).put("received_bytes", r.bytes());
        switch (r.outcome()) {
            case "reply" -> {
                p.recv("UDP yanıt (" + r.bytes() + " bayt)");
                step.ok();
                return true;
            }
            case "unreachable" -> {
                p.recv("ICMP port-unreachable");
                step.put("icmp_unreachable", true).fail("port unreachable");
                p.find(NetDiagFindings.UDP_PORT_UNREACHABLE, FAIL, params("port", spec.port(), "ip", p.ip));
            }
            case "timeout" -> {
                p.recv("(yanıt yok — " + timeout + " ms)");
                step.fail("no reply");
                p.find(NetDiagFindings.UDP_NO_REPLY, FAIL, params("port", spec.port(), "ip", p.ip, "ms", timeout));
            }
            default -> {
                p.info("UDP hatası: " + r.error());
                step.fail(r.error());
                p.find(NetDiagFindings.NETWORK_UNREACHABLE, FAIL, params("host", spec.host(), "port", spec.port(),
                        "ip", p.ip, "error", r.error()));
            }
        }
        return false;
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────

    static String readStatusLine(InputStream in, long deadlineNanos) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (buf.size() >= 8192) throw new IOException("HTTP durum satırı çok uzun");
            if (System.nanoTime() - deadlineNanos > 0) throw new SocketTimeoutException("HTTP durum satırı süre tavanını aştı");
            buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        String line = buf.toString(StandardCharsets.US_ASCII);
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    static String unescape(String s) {
        return s.replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t");
    }

    static List<String> sans(X509Certificate c) {
        List<String> out = new ArrayList<>();
        try {
            Collection<List<?>> sans = c.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> e : sans) {
                    if (e.size() > 1 && e.get(1) != null && (Integer.valueOf(2).equals(e.get(0)) || Integer.valueOf(7).equals(e.get(0)))) {
                        out.add(String.valueOf(e.get(1)));
                    }
                    if (out.size() >= 20) break;
                }
            }
        } catch (Exception ignore) { /* SAN yok */ }
        return out;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    static Map<String, Object> clientResult(Map<String, Object> r) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (r == null) {
            c.put("ok", false);
            c.put("error", "tanılama süre sınırında tamamlanmadı");
            return c;
        }
        c.put("ok", Boolean.TRUE.equals(r.get("open")));
        c.put("open", Boolean.TRUE.equals(r.get("open")));
        c.put("response_ms", r.get("response_ms"));
        c.put("via", r.get("via"));
        c.put("detail", r.get("detail"));
        c.put("error", r.get("error"));
        c.put("proxy_refused", Boolean.TRUE.equals(r.get("proxy_refused")));
        c.put("failure_reason", r.get("failure_reason"));
        return c;
    }

    /** Ad eşleşmesi + SNI kuralları — HTTP izlemesiyle aynı ({@link HttpCheckerService#hostnameMatches}). */
    static final class NetDiagSupportHost {
        private NetDiagSupportHost() {}

        static String sni(String host) {
            return com.sitemonitor.service.NetworkResolver.isIpLiteral(host) ? null : host;
        }

        static Boolean hostnameMatches(X509Certificate cert, String host) {
            if (cert == null || host == null) return null;
            if (com.sitemonitor.service.NetworkResolver.isIpLiteral(host)) {
                try {
                    InetAddress want = InetAddress.getByName(host);
                    for (String s : sans(cert)) {
                        try {
                            if (com.sitemonitor.service.NetworkResolver.isIpLiteral(s) && InetAddress.getByName(s).equals(want)) return true;
                        } catch (Exception ignore) { /* geçersiz SAN */ }
                    }
                } catch (Exception ignore) { /* eşleşme yok */ }
                return false;
            }
            return HttpCheckerService.hostnameMatches(cert, host);
        }
    }
}
