package com.sitemonitor.service.page.diagnose;

import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.PageSpeedCheckerService;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.http.HttpRequestOptions;
import com.sitemonitor.service.http.diagnose.HttpDiagFindings;
import com.sitemonitor.service.http.diagnose.HttpDiagMasker;
import com.sitemonitor.service.http.diagnose.HttpDiagnosticsService;
import com.sitemonitor.service.http.diagnose.RawHttpProbe;
import com.sitemonitor.service.page.HttpPhaseProbe;
import com.sitemonitor.service.page.PageFetchCore;
import com.sitemonitor.service.page.PageSpeedRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Future;

/**
 * Sayfa Hızı UÇTAN UCA TANILAMASI (2026-10-05, kullanıcı isteği: "tanılama ve teşhisi eksik olan … izlemeler için
 * tanılama ekleyelim"). Keyword/HTTP tanılamasının aynası ({@link PageDiagSupport}), üstüne "NEDEN YAVAŞ" çözümlemesi.
 *
 * <p>Her yolda:
 * <ul>
 *   <li>ana sayfanın ham soket ölçümü ({@link RawHttpProbe}) — izlemenin isteğinin AYNISI: GET, izlemenin User-Agent'ı,
 *       DNT, Basic auth ve ŞİFRELİ özel başlıklar (yalnız ilk host'a — çekirdekle aynı kural; çekirdeğin kendi başlıklarını
 *       ezen özel başlık atılır), sayfa çekirdeğinin Accept / Accept-Language / Accept-Encoding başlıkları, 5 yönlendirme,
 *       güven-hepsi TLS. Faz kırılımı (DNS, TCP/vekil, TLS, ilk bayt, indirme) HER yolda ölçülür — izlemenin kendi faz
 *       probu vekil yolunda ölçemez;</li>
 *   <li>izlemenin GERÇEK ölçümü ({@link PageSpeedCheckerService#checkForDiagnostics}, kaydetmeden, aynı eşik kuralı) →
 *       {@code client_check}.</li>
 * </ul>
 * İzlemenin yolu çözümlenir ({@code data.pagespeed}): eşikler ↔ ölçülen (hangi metrik, ne kadar aştı), yol başına faz
 * süreleri ↔ yol gösterici sınırlar, en ağır / en yavaş 5 kaynak, tür kırılımı. Bulgular {@link PageSpeedDiagFindings}.
 */
@Slf4j
@Service
public class PageSpeedDiagnosticsService extends PageDiagSupport {

    /** {@code diagnostic_runs.run_type}. */
    public static final String RUN_TYPE = "PAGESPEED_DIAG";

    // ── Faz sınırları (yol gösterici — eşik değil; izlemenin eşiği yoksa "yavaş" yargısı bunlarla) ──────────────────
    static final long SLOW_DNS_MS = 500;
    static final long SLOW_CONNECT_MS = 500;
    static final long SLOW_PROXY_MS = 1000;
    static final long SLOW_TLS_MS = 1000;
    static final long SLOW_SERVER_MS = 1500;
    static final long SLOW_DOWNLOAD_MS = 2000;
    static final long LARGE_BODY_BYTES = 1024L * 1024L;
    /** "Öteki yol belirgin hızlı": en az bu oran VE bu fark. */
    static final double ROUTE_RATIO = 1.5;
    static final long ROUTE_MIN_DIFF_MS = 500;
    /** En ağır / en yavaş kaynak listesi uzunluğu. */
    static final int TOP_N = 5;

    private final PageSpeedCheckerService pageSpeedChecker;
    private final SecretCipher secretCipher;

    @Autowired
    public PageSpeedDiagnosticsService(SsrfGuard ssrfGuard, TrustEvaluator trustEvaluator, CaAutoPinService caAutoPin,
                                       ProxySettings proxySettings, ProxyPolicyService proxyPolicy,
                                       PageSpeedCheckerService pageSpeedChecker, SecretCipher secretCipher) {
        super(ssrfGuard, trustEvaluator, caAutoPin, proxySettings, proxyPolicy, "pagespeed-diag-watchdog");
        this.pageSpeedChecker = pageSpeedChecker;
        this.secretCipher = secretCipher;
    }

    /** Gerçek ölçümün sonucu + harcadığı süre. */
    record ClientRun(PageSpeedCheckerService.Result result, long elapsedMs) {}

    /**
     * Tanılamayı koşturur. Dönen harita sözleşmedeki {@code data} nesnesidir ({@code run_id} null). Hop gövde önizlemeleri
     * DAHİLDİR; geçmişe yazmadan önce {@link PageDiagnosticsHistory} siler.
     */
    public Map<String, Object> diagnose(PageSpeedMonitor m, boolean compare) {
        long start = System.currentTimeMillis();
        String url = m.getUrl() == null ? "" : m.getUrl().trim();
        int monitorTimeout = pageSpeedChecker.effectiveTimeoutMs(m);
        int timeout = HttpDiagnosticsService.clampTimeout(monitorTimeout);
        String userAgent = pageSpeedChecker.effectiveUserAgent(m);

        // İzlemenin ek başlıkları — PageSpeedCheckerService.buildHeaders ile aynı sıra/kural (DNT, Basic auth, özel başlıklar
        // EN SON); çekirdeğin kendi başlıklarını ezmeye çalışan özel başlık çekirdekte de atılır.
        String pass = decrypt(m.getBasicAuthPassEnc());
        Map<String, String> custom = new LinkedHashMap<>();
        for (Map.Entry<String, String> h : PageSpeedRules.parseHeaders(decrypt(m.getCustomHeadersEnc())).entrySet()) {
            if (!PageFetchCore.isReservedHeader(h.getKey())) custom.put(h.getKey(), h.getValue());
        }
        StringBuilder headerText = new StringBuilder();
        if (Boolean.TRUE.equals(m.getSendDnt())) headerText.append("DNT: 1\n");
        for (Map.Entry<String, String> h : custom.entrySet()) headerText.append(h.getKey()).append(": ").append(h.getValue()).append('\n');
        HttpRequestOptions opts = new HttpRequestOptions(headerText.isEmpty() ? null : headerText.toString(),
                m.getBasicAuthUser(), pass, null, null, null, null);
        if (opts.isEmpty()) opts = HttpRequestOptions.NONE;

        String proxyAuth = proxyConfigured() ? proxySettings.proxyAuthorizationHeader() : null;
        HttpDiagMasker masker = new HttpDiagMasker(custom.keySet(), secrets(custom, m.getBasicAuthUser(), pass, proxyAuth));

        String mode = ProxyPolicyService.normalizeModeDefaultOff(m.getUseProxy());
        ProxyPolicyService.Decision d = proxyPolicy == null
                ? ProxyPolicyService.Decision.direct("none") : proxyPolicy.decide(url, mode);
        List<Plan> plans = plans(d, compare);
        boolean altAvailable = plans.size() > 1;

        long hardDeadline = start + PATH_CAP_MS;
        List<RawHttpProbe.Spec> specs = new ArrayList<>();
        List<Future<RawHttpProbe>> rawFutures = new ArrayList<>();
        List<Future<ClientRun>> clientFutures = new ArrayList<>();
        for (Plan p : plans) {
            RawHttpProbe.Spec spec = spec(p, url, timeout, opts, proxyAuth, hardDeadline, userAgent);
            specs.add(spec);
            rawFutures.add(submitProbe(spec, masker));
            boolean via = p.viaProxy();
            clientFutures.add(workers.submit(() -> {
                long c0 = System.currentTimeMillis();
                PageSpeedCheckerService.Result r = pageSpeedChecker.checkForDiagnostics(m, via, CLIENT_CAP_SECONDS);
                return new ClientRun(r, System.currentTimeMillis() - c0);
            }));
        }

        long collectDeadline = start + COLLECT_CAP_MS;
        List<Map<String, Object>> paths = new ArrayList<>();
        List<Map<String, Object>> pathFindings = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        List<String> failedSteps = new ArrayList<>();
        List<RawHttpProbe> probes = new ArrayList<>();
        List<ClientRun> clients = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            RawHttpProbe.Spec spec = specs.get(i);
            RawHttpProbe probe = await(rawFutures.get(i), collectDeadline);
            if (probe == null) probe = RawHttpProbe.abandoned(spec, masker, System.currentTimeMillis() - start, HttpDiagnosticsService.OVERALL_CAP_MS);
            ClientRun client = await(clientFutures.get(i), collectDeadline);
            PathEval ev = evaluate(probe, spec, client, m, plan.isMonitor());
            Map<String, Object> path = basePath(plan, probe, ev.outcome, ev.failedStep, clientMap(client));
            path.put("pagespeed", pathSummary(client));
            paths.add(path);
            pathFindings.addAll(ev.findings);
            outcomes.add(ev.outcome);
            statuses.add(probe.finalStatus());
            failedSteps.add(ev.failedStep);
            probes.add(probe);
            clients.add(client);
        }

        List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, Object> comparison = compare(paths, outcomes, statuses, failedSteps, altAvailable, "pagespeed", findings);
        Map<String, Object> routeFinding = routeFinding(plans, outcomes, probes, clients);
        if (routeFinding != null) findings.add(routeFinding);
        findings.addAll(pathFindings);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", null);
        data.put("kind", "pagespeed");
        data.put("started_at", Instant.ofEpochMilli(start).truncatedTo(ChronoUnit.SECONDS).toString());
        data.put("duration_ms", System.currentTimeMillis() - start);
        data.put("monitor", monitorMap(m, timeout, monitorTimeout, mode, userAgent, custom.size(), opts));
        data.put("source", source());
        data.put("proxy", proxyMap());
        data.put("verdict", PageSpeedDiagFindings.verdict(findings, outcomes.get(0), failedSteps.get(0)));
        data.put("findings", findings);
        data.put("paths", paths);
        data.put("comparison", comparison);
        data.put("pagespeed", block(m, plans, probes, clients));
        masker.scrubDeep(data);   // son savunma hattı: hiçbir alanda sır değeri düz kalmasın
        return data;
    }

    /** Şifreli değeri çöz; çözülemezse o kimlik olmadan devam (ölçümün kendi kuralıyla aynı). */
    private String decrypt(String enc) {
        if (enc == null || enc.isBlank() || secretCipher == null) return null;
        try {
            return secretCipher.decrypt(enc);
        } catch (Exception e) {
            log.debug("Sayfa hızı tanılaması: şifreli alan çözülemedi: {}", e.getMessage());
            return null;
        }
    }

    // ── Yol değerlendirmesi ─────────────────────────────────────────────────────────────────────

    static final class PathEval {
        String outcome;
        String failedStep;
        final List<Map<String, Object>> findings = new ArrayList<>();
    }

    /**
     * Ham ölçüm + gerçek ölçüm → yolun sonucu ({@code ok | slow | fail}) ve bulguları. İzlemenin kararını taklit eder:
     * sayfa alınamadıysa DOWN (fail); alındı ama eşik aşıldıysa SLOW (kesinti DEĞİL — "slow"); yoksa ok.
     */
    static PathEval evaluate(RawHttpProbe probe, RawHttpProbe.Spec spec, ClientRun client, PageSpeedMonitor m, boolean analyze) {
        PathEval ev = new PathEval();
        String key = spec.key();
        RawVerdict raw = rawVerdict(probe, spec, PageSpeedDiagFindings.PAGESPEED_HTTP_STATUS, PageSpeedDiagFindings.PAGESPEED_BODY_UNREAD);
        PageSpeedCheckerService.Result res = client == null ? null : client.result();
        boolean reachable = res != null && res.reachable();

        if (raw != null) {
            ev.outcome = "fail";
            ev.failedStep = raw.failedStep();
            ev.findings.add(raw.finding());
            if (reachable) {
                ev.findings.add(HttpDiagFindings.finding(HttpDiagFindings.CLIENT_MISMATCH, HttpDiagFindings.WARN, key,
                        RawHttpProbe.params("route", spec.route(), "raw_status", probe.finalStatus(),
                                "client_error", "HTTP " + res.statusCode())));
            }
        } else if (!reachable) {
            ev.outcome = "fail";
            ev.failedStep = "response";
            String failure = res == null || res.failure() == null ? null : res.failure().code();
            ev.findings.add(HttpDiagFindings.finding(PageSpeedDiagFindings.PAGESPEED_DOWN, HttpDiagFindings.FAIL, key,
                    RawHttpProbe.params("route", spec.route(), "raw_status", probe.finalStatus(),
                            "status", res == null ? null : res.statusCode(), "failure", failure,
                            "error", res == null ? null : abbreviate(res.error(), 200),
                            "reason", res == null ? "unfinished" : null)));
        } else {
            ev.outcome = res.breached() == null || res.breached().isEmpty() ? "ok" : "slow";
        }

        if (analyze && reachable) {
            for (String metric : res.breached() == null ? List.<String>of() : res.breached()) {
                Map<String, Object> mt = metric(metric, m, res);
                if (mt == null) continue;
                ev.findings.add(HttpDiagFindings.finding(PageSpeedDiagFindings.THRESHOLD_BREACH, HttpDiagFindings.WARN, key,
                        RawHttpProbe.params("reason", metric, "value", mt.get("value"), "limit", mt.get("limit"),
                                "over", mt.get("over"), "unit", mt.get("unit"))));
            }
            phaseFindings(ev, probe, m, key, spec.route());
            resourceFindings(ev, res, key, client.elapsedMs());
            if ("ok".equals(ev.outcome)) {
                ev.findings.add(HttpDiagFindings.finding(PageSpeedDiagFindings.PAGESPEED_OK, HttpDiagFindings.INFO, key,
                        RawHttpProbe.params("total_ms", res.totalMs(), "kb", res.totalBytes() / 1024,
                                "requests", res.requestCount())));
            }
        } else if (!analyze && reachable && "ok".equals(ev.outcome) && raw == null) {
            ev.findings.add(HttpDiagFindings.finding(PageSpeedDiagFindings.PAGESPEED_OK, HttpDiagFindings.INFO, key,
                    RawHttpProbe.params("total_ms", res.totalMs(), "kb", res.totalBytes() / 1024, "requests", res.requestCount())));
        }
        return ev;
    }

    /** Eşik metriği: değer, sınır, fark, birim — TTFB eşiği izlemeyle aynı SUNUCU süresine bakar (faz yoksa tek parça TTFB). */
    static Map<String, Object> metric(String metric, PageSpeedMonitor m, PageSpeedCheckerService.Result res) {
        Long value;
        Integer limit;
        String unit;
        switch (metric) {
            case PageSpeedRules.BREACH_LOAD -> { value = res.totalMs(); limit = m.getMaxLoadMs(); unit = "ms"; }
            case PageSpeedRules.BREACH_TTFB -> {
                Integer server = res.phases() == null ? null : res.phases().serverMs();
                value = server != null ? Long.valueOf(server.longValue()) : Long.valueOf(res.ttfbMs());
                limit = m.getMaxTtfbMs();
                unit = "ms";
            }
            case PageSpeedRules.BREACH_SIZE -> { value = res.totalBytes() / 1024; limit = m.getMaxPageKb(); unit = "KB"; }
            case PageSpeedRules.BREACH_REQUESTS -> { value = (long) res.requestCount(); limit = m.getMaxRequests(); unit = "req"; }
            default -> { return null; }
        }
        boolean active = limit != null && limit > 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", metric);
        out.put("value", value);
        out.put("limit", active ? limit : null);
        out.put("unit", unit);
        out.put("breached", res.breached() != null && res.breached().contains(metric));
        out.put("over", active && value != null && value > limit ? value - limit : null);
        out.put("ratio", active && value != null ? Math.round(value * 100.0 / limit) : null);
        return out;
    }

    /** Ham ölçümün fazları — hangisi yol gösterici sınırı aşıyor (izlemenin TTFB eşiği varsa sunucu fazı ona göre). */
    private static void phaseFindings(PathEval ev, RawHttpProbe probe, PageSpeedMonitor m, String key, String route) {
        Map<String, Object> tl = probe.timeline();
        long total = probe.totalMs();
        Long dns = asLong(tl.get("dns_ms")), connect = asLong(tl.get("connect_ms")), proxy = asLong(tl.get("proxy_ms")),
                tls = asLong(tl.get("tls_ms")), ttfb = asLong(tl.get("ttfb_ms")), download = asLong(tl.get("download_ms"));
        if (dns != null && dns >= SLOW_DNS_MS) {
            ev.findings.add(phase(PageSpeedDiagFindings.SLOW_DNS, key, dns, SLOW_DNS_MS, total, null, route));
        }
        if (connect != null && connect >= SLOW_CONNECT_MS) {
            ev.findings.add(phase(PageSpeedDiagFindings.SLOW_CONNECT, key, connect, SLOW_CONNECT_MS, total,
                    "proxy".equals(route) ? "proxy_connect" : "tcp", route));
        } else if (proxy != null && proxy >= SLOW_PROXY_MS) {
            ev.findings.add(phase(PageSpeedDiagFindings.SLOW_CONNECT, key, proxy, SLOW_PROXY_MS, total, "proxy_tunnel", route));
        }
        if (tls != null && tls >= SLOW_TLS_MS) {
            ev.findings.add(phase(PageSpeedDiagFindings.SLOW_TLS, key, tls, SLOW_TLS_MS, total, null, route));
        }
        long serverLimit = m.getMaxTtfbMs() != null && m.getMaxTtfbMs() > 0 ? m.getMaxTtfbMs() : SLOW_SERVER_MS;
        if (ttfb != null && ttfb > serverLimit) {
            ev.findings.add(phase(PageSpeedDiagFindings.SLOW_SERVER, key, ttfb, serverLimit, total,
                    m.getMaxTtfbMs() != null && m.getMaxTtfbMs() > 0 ? "threshold" : null, route));
        }
        Long bytes = lastBodyBytes(probe);
        if (download != null && download >= SLOW_DOWNLOAD_MS) {
            Map<String, Object> f = phase(PageSpeedDiagFindings.SLOW_DOWNLOAD, key, download, SLOW_DOWNLOAD_MS, total, null, route);
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) f.get("params");
            p.put("kb", bytes == null ? null : bytes / 1024);
            ev.findings.add(f);
        }
        if (bytes != null && bytes >= LARGE_BODY_BYTES) {
            ev.findings.add(HttpDiagFindings.finding(PageSpeedDiagFindings.LARGE_BODY, HttpDiagFindings.WARN, key,
                    RawHttpProbe.params("kb", bytes / 1024, "limit_kb", LARGE_BODY_BYTES / 1024)));
        }
    }

    private static Map<String, Object> phase(String code, String key, long ms, long limit, long total, String reason, String route) {
        return HttpDiagFindings.finding(code, HttpDiagFindings.WARN, key,
                RawHttpProbe.params("ms", ms, "limit", limit, "share", total > 0 ? Math.round(ms * 100.0 / total) : null,
                        "route", route, "reason", reason));
    }

    /** Gerçek ölçümün kaynak bulguları: süreyi/ağırlığı kim harcadı, ölçüm kısmi mi, inmeyen kaynak var mı. */
    private static void resourceFindings(PathEval ev, PageSpeedCheckerService.Result res, String key, long clientMs) {
        List<PageSpeedCheckerService.Measured> rows = res.resources() == null ? List.of() : res.resources();
        List<String> breached = res.breached() == null ? List.of() : res.breached();
        if (breached.contains(PageSpeedRules.BREACH_LOAD) && res.htmlMs() * 2 < res.totalMs() && !rows.isEmpty()) {
            PageSpeedCheckerService.Measured slowest = rows.stream()
                    .max(Comparator.comparingLong(PageSpeedCheckerService.Measured::durationMs)).orElse(null);
            ev.findings.add(HttpDiagFindings.finding(PageSpeedDiagFindings.SLOW_RESOURCES, HttpDiagFindings.WARN, key,
                    RawHttpProbe.params("html_ms", res.htmlMs(), "total_ms", res.totalMs(), "count", res.requestCount() - 1,
                            "top_url", slowest == null ? null : displayUrl(slowest.url()),
                            "top_ms", slowest == null ? null : slowest.durationMs())));
        }
        if (breached.contains(PageSpeedRules.BREACH_SIZE) && !rows.isEmpty() && res.totalBytes() > 0) {
            List<PageSpeedCheckerService.Measured> heavy = rows.stream()
                    .sorted(Comparator.comparingLong(PageSpeedCheckerService.Measured::bytes).reversed()).limit(3).toList();
            long top = heavy.stream().mapToLong(PageSpeedCheckerService.Measured::bytes).sum();
            ev.findings.add(HttpDiagFindings.finding(PageSpeedDiagFindings.HEAVY_RESOURCES, HttpDiagFindings.WARN, key,
                    RawHttpProbe.params("count", heavy.size(), "share", Math.round(top * 100.0 / res.totalBytes()),
                            "top_url", displayUrl(heavy.get(0).url()), "top_kb", heavy.get(0).bytes() / 1024,
                            "kb", res.totalBytes() / 1024)));
        }
        String partial = res.capped() ? "resources" : res.bytesTruncated() ? "bytes"
                : clientMs >= CLIENT_CAP_SECONDS * 1000L - 1000L ? "time" : null;
        if (partial != null) {
            ev.findings.add(HttpDiagFindings.finding(PageSpeedDiagFindings.MEASUREMENT_PARTIAL, HttpDiagFindings.WARN, key,
                    RawHttpProbe.params("reason", partial, "cap", PageFetchCore.MAX_RESOURCES_PER_CHECK,
                            "seconds", CLIENT_CAP_SECONDS)));
        }
        if (res.failedCount() > 0) {
            String sample = rows.stream().filter(PageSpeedCheckerService.Measured::failed).findFirst()
                    .map(r -> displayUrl(r.url())).orElse(null);
            ev.findings.add(HttpDiagFindings.finding(PageSpeedDiagFindings.RESOURCES_FAILED, HttpDiagFindings.INFO, key,
                    RawHttpProbe.params("count", res.failedCount(), "sample", sample)));
        }
    }

    /**
     * Yol hızı karşılaştırması: iki yol da ölçüldüyse ve İZLEMENİN yolu belirgin yavaşsa ({@value #ROUTE_RATIO}× ve ≥
     * {@value #ROUTE_MIN_DIFF_MS} ms) — vekil yavaşsa {@code PROXY_SLOWER}, doğrudan yavaşsa {@code DIRECT_SLOWER}.
     * Gerçek ölçümün toplamı esas; yoksa ham ölçümün toplamı.
     */
    static Map<String, Object> routeFinding(List<Plan> plans, List<String> outcomes, List<RawHttpProbe> probes,
                                            List<ClientRun> clients) {
        if (plans.size() < 2 || "fail".equals(outcomes.get(0)) || "fail".equals(outcomes.get(1))) return null;
        Long own = totalOf(probes.get(0), clients.get(0));
        Long other = totalOf(probes.get(1), clients.get(1));
        if (own == null || other == null || other <= 0) return null;
        if (own < other * ROUTE_RATIO || own - other < ROUTE_MIN_DIFF_MS) return null;
        String ownRoute = plans.get(0).route();
        String code = "proxy".equals(ownRoute) ? PageSpeedDiagFindings.PROXY_SLOWER : PageSpeedDiagFindings.DIRECT_SLOWER;
        double ratio = Math.round(own * 10.0 / other) / 10.0;
        return HttpDiagFindings.finding(code, HttpDiagFindings.WARN, "monitor",
                RawHttpProbe.params("slow_route", ownRoute, "fast_route", plans.get(1).route(), "slow_ms", own,
                        "fast_ms", other, "ratio", ratio));
    }

    private static Long totalOf(RawHttpProbe probe, ClientRun client) {
        if (client != null && client.result() != null && client.result().reachable()) return client.result().totalMs();
        return probe == null ? null : probe.totalMs();
    }

    // ── Bloklar ─────────────────────────────────────────────────────────────────────────────────

    /** İzlemenin yolunun "neden yavaş" çözümlemesi ({@code data.pagespeed}). */
    static Map<String, Object> block(PageSpeedMonitor m, List<Plan> plans, List<RawHttpProbe> probes, List<ClientRun> clients) {
        ClientRun own = clients.get(0);
        PageSpeedCheckerService.Result res = own == null ? null : own.result();
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("path", "monitor");
        b.put("analyzed", res != null && res.reachable());
        Map<String, Object> th = new LinkedHashMap<>();
        th.put("max_load_ms", m.getMaxLoadMs());
        th.put("max_ttfb_ms", m.getMaxTtfbMs());
        th.put("max_page_kb", m.getMaxPageKb());
        th.put("max_requests", m.getMaxRequests());
        b.put("thresholds", th);
        b.put("measured", res == null ? null : measured(res));
        List<Map<String, Object>> metrics = new ArrayList<>();
        if (res != null && res.reachable()) {
            for (String k : PageSpeedDiagFindings.METRICS) {
                Map<String, Object> mt = metric(k, m, res);
                if (mt != null) metrics.add(mt);
            }
        }
        b.put("metrics", metrics);
        List<Map<String, Object>> routes = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) routes.add(routeRow(plans.get(i), probes.get(i), clients.get(i)));
        b.put("routes", routes);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("dns_ms", SLOW_DNS_MS);
        limits.put("connect_ms", SLOW_CONNECT_MS);
        limits.put("proxy_ms", SLOW_PROXY_MS);
        limits.put("tls_ms", SLOW_TLS_MS);
        limits.put("ttfb_ms", m.getMaxTtfbMs() != null && m.getMaxTtfbMs() > 0 ? m.getMaxTtfbMs().longValue() : SLOW_SERVER_MS);
        limits.put("download_ms", SLOW_DOWNLOAD_MS);
        b.put("phase_limits", limits);
        List<PageSpeedCheckerService.Measured> rows = res == null || res.resources() == null ? List.of() : res.resources();
        b.put("heaviest", rows.stream().sorted(Comparator.comparingLong(PageSpeedCheckerService.Measured::bytes).reversed())
                .limit(TOP_N).map(PageSpeedDiagnosticsService::resourceRow).toList());
        b.put("slowest", rows.stream().sorted(Comparator.comparingLong(PageSpeedCheckerService.Measured::durationMs).reversed())
                .limit(TOP_N).map(PageSpeedDiagnosticsService::resourceRow).toList());
        Map<String, long[]> byType = new TreeMap<>();
        for (PageSpeedCheckerService.Measured r : rows) {
            long[] acc = byType.computeIfAbsent(PageSpeedCheckerService.normalizeType(r.type()), k -> new long[2]);
            acc[0]++;
            acc[1] += r.bytes();
        }
        List<Map<String, Object>> types = new ArrayList<>();
        byType.forEach((type, acc) -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("type", type);
            t.put("count", acc[0]);
            t.put("bytes", acc[1]);
            types.add(t);
        });
        types.sort(Comparator.comparingLong((Map<String, Object> t) -> (Long) t.get("bytes")).reversed());
        b.put("by_type", types);
        return b;
    }

    private static Map<String, Object> measured(PageSpeedCheckerService.Result res) {
        Map<String, Object> x = new LinkedHashMap<>();
        x.put("status", res.status());
        x.put("http_status", res.statusCode());
        x.put("ttfb_ms", res.ttfbMs());
        x.put("server_ms", res.phases() == null ? null : res.phases().serverMs());
        x.put("html_ms", res.htmlMs());
        x.put("total_ms", res.totalMs());
        x.put("total_bytes", res.totalBytes());
        x.put("request_count", res.requestCount());
        x.put("failed_count", res.failedCount());
        x.put("capped", res.capped());
        x.put("bytes_truncated", res.bytesTruncated());
        x.put("skipped_lazy", res.skippedLazy());
        x.put("breached", res.breached() == null ? List.of() : res.breached());
        x.put("error", abbreviate(res.error(), 300));
        x.put("failure_reason", res.failure() == null ? null : res.failure().code());
        HttpPhaseProbe.Phases ph = res.phases();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("dns_ms", ph == null ? null : ph.dnsMs());
        p.put("connect_ms", ph == null ? null : ph.connectMs());
        p.put("tls_ms", ph == null ? null : ph.tlsMs());
        p.put("server_ms", ph == null ? null : ph.serverMs());
        p.put("error", ph == null ? null : abbreviate(ph.error(), 200));
        x.put("phases", p);
        return x;
    }

    /** Yolun faz satırı: ham ölçümün zaman çizelgesi + gerçek ölçümün toplamı. */
    private static Map<String, Object> routeRow(Plan plan, RawHttpProbe probe, ClientRun client) {
        Map<String, Object> tl = probe.timeline();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("key", plan.key());
        r.put("route", plan.route());
        r.put("dns_ms", tl.get("dns_ms"));
        r.put("connect_ms", tl.get("connect_ms"));
        r.put("proxy_ms", tl.get("proxy_ms"));
        r.put("tls_ms", tl.get("tls_ms"));
        r.put("ttfb_ms", tl.get("ttfb_ms"));
        r.put("download_ms", tl.get("download_ms"));
        r.put("total_ms", tl.get("total_ms"));
        r.put("body_bytes", lastBodyBytes(probe));
        r.put("http_status", probe.finalStatus());
        PageSpeedCheckerService.Result res = client == null ? null : client.result();
        r.put("measured_total_ms", res != null && res.reachable() ? res.totalMs() : null);
        r.put("measured_bytes", res != null && res.reachable() ? res.totalBytes() : null);
        return r;
    }

    private static Map<String, Object> resourceRow(PageSpeedCheckerService.Measured x) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("url", displayUrl(x.url()));
        r.put("type", PageSpeedCheckerService.normalizeType(x.type()));
        r.put("bytes", x.bytes());
        r.put("ms", x.durationMs());
        r.put("status", x.statusCode());
        r.put("third_party", x.thirdParty());
        r.put("failed", x.failed());
        r.put("truncated", x.truncated());
        return r;
    }

    private static Map<String, Object> pathSummary(ClientRun client) {
        PageSpeedCheckerService.Result res = client == null ? null : client.result();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("status", res == null ? null : res.status());
        s.put("total_ms", res != null && res.reachable() ? res.totalMs() : null);
        s.put("total_bytes", res != null && res.reachable() ? res.totalBytes() : null);
        s.put("requests", res != null && res.reachable() ? res.requestCount() : null);
        s.put("breached", res == null || res.breached() == null ? List.of() : res.breached());
        return s;
    }

    /** İzlemenin gerçek ölçümü — {@code ok} = sayfa ölçüldü (eşik kararı yolun sonucunda: ok | slow). */
    private static Map<String, Object> clientMap(ClientRun run) {
        Map<String, Object> c = new LinkedHashMap<>();
        PageSpeedCheckerService.Result r = run == null ? null : run.result();
        if (r == null) {
            c.put("ok", false);
            c.put("http_status", null);
            c.put("response_ms", null);
            c.put("http_version", null);
            c.put("error", "tanılama süre sınırında tamamlanmadı");
            c.put("status", null);
            c.put("failure_reason", null);
            return c;
        }
        c.put("ok", r.reachable());
        c.put("http_status", r.statusCode());
        c.put("response_ms", r.totalMs());
        c.put("http_version", null);
        c.put("error", abbreviate(r.error(), 300));
        c.put("status", r.status());
        c.put("failure_reason", r.failure() == null ? null : r.failure().code());
        c.put("total_bytes", r.totalBytes());
        c.put("requests", r.requestCount());
        c.put("breached", r.breached() == null ? List.of() : r.breached());
        return c;
    }

    private static Map<String, Object> monitorMap(PageSpeedMonitor m, int diagTimeout, int monitorTimeout, String mode,
                                                  String userAgent, int customHeaders, HttpRequestOptions opts) {
        Map<String, Object> mon = new LinkedHashMap<>();
        mon.put("id", m.getId());
        mon.put("name", m.getName());
        mon.put("url", m.getUrl());
        mon.put("method", "GET");
        mon.put("timeout_ms", monitorTimeout);
        mon.put("diag_timeout_ms", diagTimeout);
        mon.put("verify_ssl", false);
        mon.put("follow_redirects", true);
        mon.put("proxy_mode", mode);
        mon.put("user_agent", userAgent);
        mon.put("check_budget_s", CLIENT_CAP_SECONDS);
        Map<String, Object> adv = new LinkedHashMap<>();
        adv.put("custom_headers", customHeaders);
        adv.put("basic_auth", opts.basicAuthUser() != null && !opts.basicAuthUser().isBlank());
        adv.put("send_dnt", Boolean.TRUE.equals(m.getSendDnt()));
        adv.put("exclude_trackers", Boolean.TRUE.equals(m.getExcludeTrackers()));
        mon.put("advanced", adv);
        Map<String, Object> th = new LinkedHashMap<>();
        th.put("max_load_ms", m.getMaxLoadMs());
        th.put("max_ttfb_ms", m.getMaxTtfbMs());
        th.put("max_page_kb", m.getMaxPageKb());
        th.put("max_requests", m.getMaxRequests());
        mon.put("thresholds", th);
        return mon;
    }

    private static Long asLong(Object o) {
        return o instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }
}
