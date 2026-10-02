package com.sitemonitor.service.http.diagnose;

import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.HttpCheckerService;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.http.HttpRequestOptions;
import com.sitemonitor.service.http.HttpRequestRules;
import com.sitemonitor.service.http.JsonAssertion;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * HTTP uçtan uca tanılaması (2026-10-02, kullanıcı onaylı) — "curl -v'nin gelişmiş hâli", tek tık.
 *
 * <p><b>Neden.</b> Prod'da düz http, "her zaman vekil" kipindeki bir HTTP izlemesi, pod başka düğüme taşındıktan sonra
 * takılmaya başladı; aynı pod siteye DOĞRUDAN 24 ms'de (HTTP 401) ulaşıyordu. İzleme yalnız "request timed out"
 * diyordu — hangi adımda, hangi yolda takıldığını görmek için kubectl exec + curl gerekiyordu. Bu servis aynı soruyu
 * arayüzden cevaplar: izlemenin KENDİ yolu (vekil kararı {@link ProxyPolicyService}) ve vekil tanımlıysa ÖTEKİ yol
 * PARALEL denenir; her yolda ham soket ölçümü ({@link RawHttpProbe}) + izlemenin GERÇEK istemcisi
 * ({@link HttpCheckerService#checkForDiagnostics}) koşar; fark ve bulgular ({@link HttpDiagFindings}) raporlanır.
 *
 * <p><b>Mevcudu etkilemez.</b> Hiçbir {@code http_checks} satırı yazılmaz, {@code MonitoringOutageService} / sweep
 * değerlendirmesi / eskalasyon çağrılmaz, CA auto-pin yapılmaz, izleme kaydı değişmez. Kalıcı tek iz tanılama geçmişi
 * ({@code diagnostic_runs}, gövde önizlemesiz) ve denetim kaydıdır — ikisi de denetleyicide yazılır.
 *
 * <p><b>Süre.</b> Yol başına izlemenin {@code timeout_ms}'i {@value #MIN_TIMEOUT_MS}..{@value #MAX_TIMEOUT_MS} ms'e
 * kısılır; iki yol paralel koşar; isteğin tamamı en çok {@value #OVERALL_CAP_MS} ms sürer. İş parçacıkları sanal
 * (virtual) — sweep havuzu ({@code certCheckExecutor}) hiç kullanılmaz; eşzamanlılık denetleyicinin hız sınırıyla
 * tavanlıdır.
 */
@Slf4j
@Service
public class HttpDiagnosticsService {

    /** {@code diagnostic_runs.run_type}. */
    public static final String RUN_TYPE = "HTTP_DIAG";
    public static final int MIN_TIMEOUT_MS = 1000;
    public static final int MAX_TIMEOUT_MS = 30_000;
    public static final long OVERALL_CAP_MS = 60_000L;
    /** Yolun mutlak tavanı (genel tavanın altında — sonuç toplama payı kalsın). */
    static final long PATH_CAP_MS = 55_000L;
    /** Sonuç toplama son anı (genel tavandan pay düşülmüş). */
    static final long COLLECT_CAP_MS = 58_000L;

    private final SsrfGuard ssrfGuard;
    private final TrustEvaluator trustEvaluator;
    private final HttpCheckerService httpChecker;
    private final CaAutoPinService caAutoPin;
    private final ProxySettings proxySettings;
    private final ProxyPolicyService proxyPolicy;
    private final SecretCipher secretCipher;

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "http-diag-watchdog");
        t.setDaemon(true);
        return t;
    });

    /** Ortam değişkeni okuyucu — test kaynak bilgisini sabitleyebilsin diye alan. */
    UnaryOperator<String> env = System::getenv;

    @Autowired
    public HttpDiagnosticsService(SsrfGuard ssrfGuard, TrustEvaluator trustEvaluator, HttpCheckerService httpChecker,
                                  CaAutoPinService caAutoPin, ProxySettings proxySettings,
                                  ProxyPolicyService proxyPolicy, SecretCipher secretCipher) {
        this.ssrfGuard = ssrfGuard;
        this.trustEvaluator = trustEvaluator;
        this.httpChecker = httpChecker;
        this.caAutoPin = caAutoPin;
        this.proxySettings = proxySettings;
        this.proxyPolicy = proxyPolicy;
        this.secretCipher = secretCipher;
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
        watchdog.shutdownNow();
    }

    /** İzlemenin zaman aşımı → tanılama yol bütçesi (1–30 sn). */
    public static int clampTimeout(Integer timeoutMs) {
        int t = timeoutMs == null ? 10_000 : timeoutMs;
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, t));
    }

    private record Plan(String key, String route, Map<String, Object> decision) {}

    /**
     * Tanılamayı koşturur. Dönen harita sözleşmedeki {@code data} nesnesidir ({@code run_id} null — denetleyici kayıttan
     * sonra doldurur). Gövde önizlemeleri DAHİLDİR; geçmişe yazmadan önce {@code HttpDiagnosticsHistory} siler.
     *
     * @param compare vekil tanımlıysa öteki yolu da dene
     */
    public Map<String, Object> diagnose(HttpMonitor m, boolean compare) {
        long start = System.currentTimeMillis();
        String url = m.getUrl() == null ? "" : m.getUrl().trim();
        String method = normalizeMethod(m.getMethod());
        int timeout = clampTimeout(m.getTimeoutMs());
        boolean verify = Boolean.TRUE.equals(m.getVerifySsl());
        boolean follow = !Boolean.FALSE.equals(m.getFollowRedirects());
        HttpRequestOptions opts = HttpRequestOptions.forMonitor(m, secretCipher == null ? null : secretCipher::decrypt);
        Map<String, String> custom = HttpRequestRules.parseHeaders(opts.headers());

        boolean proxyConfigured = proxySettings != null && proxySettings.enabled();
        String proxyAuth = proxyConfigured ? proxySettings.proxyAuthorizationHeader() : null;
        HttpDiagMasker masker = new HttpDiagMasker(custom.keySet(), secrets(opts, custom, proxyAuth));

        String mode = ProxyPolicyService.normalizeMode(m.getUseProxy());
        ProxyPolicyService.Decision d = proxyPolicy == null
                ? ProxyPolicyService.Decision.direct("none") : proxyPolicy.decide(url, mode);
        boolean monitorViaProxy = d.viaProxy() && proxyConfigured;
        String monitorRoute = monitorViaProxy ? "proxy" : "direct";
        boolean altAvailable = compare && proxyConfigured;

        List<Plan> plans = new ArrayList<>();
        plans.add(new Plan("monitor", monitorRoute, decisionMap(d.source(), d.wanted(), d.bypassed())));
        if (altAvailable) {
            String altRoute = monitorViaProxy ? "direct" : "proxy";
            plans.add(new Plan("alternate", altRoute, decisionMap("compare", "proxy".equals(altRoute), false)));
        }

        long hardDeadline = start + PATH_CAP_MS;
        List<Future<RawHttpProbe>> rawFutures = new ArrayList<>();
        List<Future<Map<String, Object>>> clientFutures = new ArrayList<>();
        List<RawHttpProbe.Spec> specs = new ArrayList<>();
        for (Plan p : plans) {
            boolean viaProxy = "proxy".equals(p.route());
            RawHttpProbe.Spec spec = new RawHttpProbe.Spec(p.key(), p.route(), url, method, m.getExpectedStatus(), timeout,
                    verify, follow, opts,
                    viaProxy ? proxySettings.host() : null, viaProxy ? proxySettings.port() : 0,
                    viaProxy ? proxyAuth : null, hardDeadline);
            specs.add(spec);
            rawFutures.add(workers.submit(() -> new RawHttpProbe(spec, ssrfGuard, trustEvaluator, caAutoPin, masker, watchdog).run()));
            clientFutures.add(workers.submit(() -> httpChecker.checkForDiagnostics(
                    url, method, m.getExpectedStatus(), timeout, verify, follow, viaProxy, opts)));
        }

        long collectDeadline = start + COLLECT_CAP_MS;
        List<Map<String, Object>> paths = new ArrayList<>();
        List<Map<String, Object>> pathFindings = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        List<String> failedSteps = new ArrayList<>();
        boolean slowEnabled = Boolean.TRUE.equals(m.getSlowResponseEnabled());
        Integer slowMs = slowEnabled ? (m.getSlowThresholdMs() != null ? m.getSlowThresholdMs() : HttpRequestRules.DEFAULT_SLOW_MS) : null;
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            RawHttpProbe.Spec spec = specs.get(i);
            RawHttpProbe probe = await(rawFutures.get(i), collectDeadline);
            if (probe == null) probe = abandoned(spec, masker, System.currentTimeMillis() - start);
            Map<String, Object> client = clientResult(await(clientFutures.get(i), collectDeadline));
            PathEval ev = evaluate(probe, spec, client, opts, slowMs);
            Map<String, Object> path = pathMap(plan, probe, ev, client);
            paths.add(path);
            pathFindings.addAll(ev.findings);
            outcomes.add(ev.outcome);
            statuses.add(probe.finalStatus);
            failedSteps.add(ev.failedStep);
        }

        // ── Karşılaştırma ──
        List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("available", altAvailable);
        boolean differs = false;
        if (altAvailable && paths.size() > 1) {
            String mo = outcomes.get(0), ao = outcomes.get(1);
            Integer ms = statuses.get(0), as = statuses.get(1);
            differs = !Objects.equals(mo, ao) || !Objects.equals(ms, as);
            boolean monitorFails = "fail".equals(mo);
            boolean altWorks = "ok".equals(ao) || (ms == null && as != null);
            if (monitorFails && altWorks) {
                findings.add(HttpDiagFindings.finding(HttpDiagFindings.PATH_DIFFERS, HttpDiagFindings.FAIL, "monitor",
                        RawHttpProbe.params("failing_route", paths.get(0).get("route"),
                                "working_route", paths.get(1).get("route"), "working_status", as)));
            } else if (monitorFails && "fail".equals(ao) && ms == null && as == null) {
                findings.add(HttpDiagFindings.finding(HttpDiagFindings.BOTH_PATHS_FAIL, HttpDiagFindings.FAIL, null,
                        RawHttpProbe.params("failed_step", failedSteps.get(0))));
            }
        }
        comparison.put("differs", differs);
        findings.addAll(pathFindings);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", null);
        data.put("started_at", Instant.ofEpochMilli(start).truncatedTo(ChronoUnit.SECONDS).toString());
        data.put("duration_ms", System.currentTimeMillis() - start);
        data.put("monitor", monitorMap(m, method, timeout, verify, follow, mode, opts, custom, slowMs));
        data.put("source", source());
        data.put("proxy", proxyMap(proxyConfigured));
        data.put("verdict", HttpDiagFindings.verdict(findings, outcomes.get(0), failedSteps.get(0)));
        data.put("findings", findings);
        data.put("paths", paths);
        data.put("comparison", comparison);
        masker.scrubDeep(data);   // son savunma hattı: hiçbir alanda sır değeri düz kalmasın
        return data;
    }

    // ── Yol değerlendirmesi ─────────────────────────────────────────────────────────────────────

    static final class PathEval {
        String outcome;
        String failedStep;
        final List<Map<String, Object>> findings = new ArrayList<>();
    }

    /** Ham ölçüm + gerçek istemci → yolun sonucu ve bulguları (izlemenin kararını AYNEN taklit eder). */
    static PathEval evaluate(RawHttpProbe probe, RawHttpProbe.Spec spec, Map<String, Object> client,
                             HttpRequestOptions opts, Integer slowThresholdMs) {
        PathEval ev = new PathEval();
        String key = spec.key();
        String expected = spec.expectedStatus() == null || spec.expectedStatus().isBlank() ? "200-399" : spec.expectedStatus();
        if (probe.failure != null) {
            ev.outcome = "fail";
            ev.failedStep = probe.failure.step();
            ev.findings.add(HttpDiagFindings.finding(probe.failure.code(), HttpDiagFindings.FAIL, key, probe.failure.params()));
        } else if (probe.finalStatus == null) {
            ev.outcome = "fail";
            ev.failedStep = "response";
            ev.findings.add(HttpDiagFindings.finding(HttpDiagFindings.RESPONSE_TIMEOUT, HttpDiagFindings.FAIL, key,
                    RawHttpProbe.params("ms", spec.timeoutMs(), "route", spec.route())));
        } else {
            int st = probe.finalStatus;
            boolean match = HttpCheckerService.matchesStatus(st, spec.expectedStatus());
            boolean auth = st == 401 || st == 403;
            if (!match) {
                ev.outcome = "fail";
                ev.failedStep = "response";
                ev.findings.add(HttpDiagFindings.finding(auth ? HttpDiagFindings.AUTH_REQUIRED : HttpDiagFindings.STATUS_MISMATCH,
                        HttpDiagFindings.FAIL, key, RawHttpProbe.params("status", st, "expected", expected)));
            } else {
                ev.outcome = "ok";
                if (auth) {
                    ev.findings.add(HttpDiagFindings.finding(HttpDiagFindings.AUTH_REQUIRED, HttpDiagFindings.INFO, key,
                            RawHttpProbe.params("status", st, "expected", expected)));
                }
                HttpRequestOptions o = opts == null ? HttpRequestOptions.NONE : opts;
                if (o.hasJsonAssertion()) {
                    String jf = probe.bodyTimedOut
                            ? JsonAssertion.FAIL_PREFIX + "yanıt gövdesi " + spec.timeoutMs() + " ms içinde tamamen okunamadı"
                            : JsonAssertion.evaluate(probe.finalBody, probe.finalBodyCapped, o.jsonPath(), o.jsonExpected());
                    if (jf != null) {
                        ev.outcome = "fail";
                        ev.failedStep = "body";
                        String reason = jf.startsWith(JsonAssertion.FAIL_PREFIX) ? jf.substring(JsonAssertion.FAIL_PREFIX.length()) : jf;
                        ev.findings.add(HttpDiagFindings.finding(HttpDiagFindings.JSON_ASSERTION_FAIL, HttpDiagFindings.FAIL, key,
                                RawHttpProbe.params("path", o.jsonPath(), "reason", reason)));
                    }
                } else if (probe.bodyTimedOut) {
                    // İzleme gövdeyi süreyle keser ve durum koduyla karar verir → yol yine "ok"; uyarı olarak görünür.
                    ev.findings.add(HttpDiagFindings.finding(HttpDiagFindings.BODY_TIMEOUT, HttpDiagFindings.WARN, key,
                            RawHttpProbe.params("ms", probe.bodyTimeoutMs)));
                }
                if ("ok".equals(ev.outcome) && slowThresholdMs != null && probe.totalMs > slowThresholdMs) {
                    ev.findings.add(HttpDiagFindings.finding(HttpDiagFindings.SLOW, HttpDiagFindings.WARN, key,
                            RawHttpProbe.params("total_ms", probe.totalMs, "threshold_ms", slowThresholdMs)));
                }
                if ("ok".equals(ev.outcome)) {
                    ev.findings.add(HttpDiagFindings.finding(HttpDiagFindings.OK, HttpDiagFindings.INFO, key,
                            RawHttpProbe.params("status", st, "total_ms", probe.totalMs)));
                }
            }
        }
        boolean rawOk = "ok".equals(ev.outcome);
        boolean clientOk = Boolean.TRUE.equals(client.get("ok"));
        if (rawOk != clientOk) {
            ev.findings.add(HttpDiagFindings.finding(HttpDiagFindings.CLIENT_MISMATCH, HttpDiagFindings.WARN, key,
                    RawHttpProbe.params("route", spec.route(), "raw_status", probe.finalStatus,
                            "client_error", client.get("error"))));
        }
        return ev;
    }

    private static Map<String, Object> pathMap(Plan plan, RawHttpProbe probe, PathEval ev, Map<String, Object> client) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", plan.key());
        p.put("route", plan.route());
        p.put("decision", plan.decision());
        p.put("outcome", ev.outcome);
        p.put("http_status", probe.finalStatus);
        p.put("total_ms", probe.totalMs);
        p.put("failed_step", ev.failedStep);
        p.put("error", probe.failure != null ? errorMap(probe.failure.error()) : null);
        Map<String, Object> tl = new LinkedHashMap<>();
        tl.put("dns_ms", probe.dnsMs);
        tl.put("connect_ms", probe.connectMs);
        // Uygulanmayan faz 0, ulaşılmayan faz null (sözleşme örneği).
        tl.put("proxy_ms", probe.tunnelApplicable ? probe.proxyMs : Long.valueOf(0L));
        tl.put("tls_ms", probe.tlsApplicable ? probe.tlsMs : Long.valueOf(0L));
        tl.put("ttfb_ms", probe.ttfbMs);
        tl.put("download_ms", probe.downloadMs);
        tl.put("total_ms", probe.totalMs);
        p.put("timeline", tl);
        p.put("hops", probe.hops);
        p.put("transcript", probe.transcript);
        p.put("client_check", client);
        return p;
    }

    /** {@code {class, message, chain[]}} — neden zinciri en çok 8 halka. */
    static Map<String, Object> errorMap(Throwable e) {
        if (e == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("class", e.getClass().getName());
        m.put("message", RawHttpProbe.msg(e));
        List<String> chain = new ArrayList<>();
        Throwable t = e;
        for (int i = 0; t != null && i < 8; i++) {
            chain.add(t.getClass().getName() + ": " + RawHttpProbe.msg(t));
            if (t.getCause() == t) break;
            t = t.getCause();
        }
        m.put("chain", chain);
        return m;
    }

    private static Map<String, Object> clientResult(Map<String, Object> r) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (r == null) {
            c.put("ok", false);
            c.put("http_status", null);
            c.put("response_ms", null);
            c.put("http_version", null);
            c.put("error", "tanılama süre sınırında tamamlanmadı");
            return c;
        }
        c.put("ok", Boolean.TRUE.equals(r.get("ok")));
        c.put("http_status", r.get("http_status"));
        c.put("response_ms", r.get("response_ms"));
        c.put("http_version", r.get("http_version"));
        c.put("error", r.get("error"));
        return c;
    }

    private static <T> T await(Future<T> f, long deadline) {
        try {
            long rem = Math.max(1L, deadline - System.currentTimeMillis());
            return f.get(rem, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            return null;
        } catch (Exception e) {
            f.cancel(true);
            log.debug("HTTP tanılama: görev süre sınırında bitmedi/patladı: {}", e.toString());
            return null;
        }
    }

    /** Görev tavanında bitmeyen yol — yeni (boş) bir sonuçla "yanıt takıldı" olarak raporlanır. */
    private RawHttpProbe abandoned(RawHttpProbe.Spec spec, HttpDiagMasker masker, long elapsed) {
        RawHttpProbe p = new RawHttpProbe(spec, ssrfGuard, trustEvaluator, caAutoPin, masker, null);
        p.totalMs = elapsed;
        p.transcript.add("* Diagnostic time limit reached (" + OVERALL_CAP_MS + " ms) — path abandoned");
        p.failure = new RawHttpProbe.Failure(HttpDiagFindings.RESPONSE_TIMEOUT,
                RawHttpProbe.params("ms", elapsed, "route", spec.route()), "response",
                new java.util.concurrent.TimeoutException("tanılama süre sınırı (" + OVERALL_CAP_MS + " ms)"));
        return p;
    }

    // ── Bloklar ─────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> decisionMap(String source, boolean wanted, boolean bypassed) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("source", source);
        d.put("wanted", wanted);
        d.put("bypassed", bypassed);
        return d;
    }

    private static Map<String, Object> monitorMap(HttpMonitor m, String method, int diagTimeout, boolean verify,
                                                  boolean follow, String mode, HttpRequestOptions opts,
                                                  Map<String, String> custom, Integer slowMs) {
        Map<String, Object> mon = new LinkedHashMap<>();
        mon.put("id", m.getId());
        mon.put("name", m.getName());
        mon.put("url", m.getUrl());
        mon.put("method", method);
        mon.put("expected_status", m.getExpectedStatus());
        mon.put("timeout_ms", m.getTimeoutMs() != null ? m.getTimeoutMs() : 10_000);
        mon.put("diag_timeout_ms", diagTimeout);
        mon.put("verify_ssl", verify);
        mon.put("follow_redirects", follow);
        mon.put("proxy_mode", mode);
        Map<String, Object> adv = new LinkedHashMap<>();
        adv.put("custom_headers", custom.size());
        adv.put("basic_auth", m.getBasicAuthUser() != null && !m.getBasicAuthUser().isBlank());
        adv.put("body_bytes", opts.sendsBody(method) ? opts.body().getBytes(StandardCharsets.UTF_8).length : 0);
        adv.put("json_path", opts.jsonPath());
        adv.put("slow_threshold_ms", slowMs);
        mon.put("advanced", adv);
        return mon;
    }

    private Map<String, Object> proxyMap(boolean configured) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("configured", configured);
        p.put("host", configured ? proxySettings.host() : null);
        p.put("port", configured ? proxySettings.port() : null);
        p.put("auth", configured && proxySettings.hasAuth());
        p.put("no_proxy", proxySettings != null ? proxySettings.noProxyList() : "");
        return p;
    }

    /** Pod / düğüm / pod IP — hedefin gördüğü kaynak IP, OpenShift çıkış (egress) ayarına göre düğüm IP'si olabilir. */
    Map<String, Object> source() {
        Map<String, Object> s = new LinkedHashMap<>();
        String pod = blankToNull(env.apply("POD_NAME"));
        if (pod == null) pod = blankToNull(env.apply("HOSTNAME"));
        if (pod == null) {
            try { pod = InetAddress.getLocalHost().getHostName(); } catch (Exception ignore) { pod = null; }
        }
        String ip = blankToNull(env.apply("POD_IP"));
        if (ip == null) ip = firstLocalAddress();
        s.put("pod", pod);
        s.put("node", blankToNull(env.apply("NODE_NAME")));
        s.put("pod_ip", ip);
        return s;
    }

    private static String firstLocalAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignore) { /* arayüz okunamadı */ }
        return null;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    /**
     * Metinden süzülecek sır DEĞERLERİ: Basic auth parolası + jetonu, vekil parolası + jetonu ve özel başlık değerleri.
     * Özel başlıkların İSTEK satırındaki değeri her durumda gizlenir (ada göre, {@link HttpDiagMasker}); serbest
     * metin süzgecine ise yalnız sır OLABİLECEK değerler girer ({@link #secretLike}) — {@code Accept: application/json}
     * gibi bir değerin yanıttaki her {@code application/json}'u silmesi tanıyı bozardı.
     */
    private List<String> secrets(HttpRequestOptions opts, Map<String, String> custom, String proxyAuth) {
        List<String> s = new ArrayList<>();
        if (opts.basicAuthPass() != null) s.add(opts.basicAuthPass());
        String basic = HttpRequestRules.basicAuthHeader(opts.basicAuthUser(), opts.basicAuthPass());
        if (basic != null) s.add(basic.substring("Basic ".length()));
        for (Map.Entry<String, String> h : custom.entrySet()) {
            if (secretLike(h.getKey(), h.getValue())) {
                s.add(h.getValue());
                int sp = h.getValue().indexOf(' ');   // "Bearer <jeton>" → jetonun kendisi de
                if (sp > 0 && sp < h.getValue().length() - 1) s.add(h.getValue().substring(sp + 1).trim());
            }
        }
        if (proxySettings != null && proxySettings.secretValue() != null) s.add(proxySettings.secretValue());
        if (proxyAuth != null) s.add(proxyAuth.substring("Basic ".length()));
        return s;
    }

    /** Sır olabilecek özel başlık değeri: hassas ad ya da jeton görünümlü değer (rakam içeren, boşluksuz, ≥ 8). MIME türü değil. */
    static boolean secretLike(String name, String value) {
        if (value == null || value.isBlank()) return false;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (HttpDiagMasker.SENSITIVE_REQUEST.contains(n) || com.sitemonitor.service.SecretMask.isSensitive(n)
                || n.contains("auth") || n.contains("session")) return true;
        String v = value.trim();
        if (v.matches("[A-Za-z0-9.+\\-]+/[A-Za-z0-9.+\\-*]+(\\s*;.*)?")) return false;   // MIME türü
        boolean hasDigit = v.chars().anyMatch(Character::isDigit);
        return v.length() >= 8 && hasDigit && v.indexOf(' ') < 0;
    }

    static String normalizeMethod(String m) {
        String v = m == null ? "GET" : m.trim().toUpperCase(Locale.ROOT);
        return v.isEmpty() ? "GET" : v;
    }
}
