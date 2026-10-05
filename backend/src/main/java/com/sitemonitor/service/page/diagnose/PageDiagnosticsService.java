package com.sitemonitor.service.page.diagnose;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.PageCheckerService;
import com.sitemonitor.service.PageCheckerService.PageCheckResult;
import com.sitemonitor.service.PageCheckerService.ResourceIssue;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.http.HttpRequestOptions;
import com.sitemonitor.service.http.diagnose.HttpDiagFindings;
import com.sitemonitor.service.http.diagnose.HttpDiagMasker;
import com.sitemonitor.service.http.diagnose.HttpDiagnosticsService;
import com.sitemonitor.service.http.diagnose.RawHttpProbe;
import com.sitemonitor.service.page.PageFetchCore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * Sayfa Bütünlüğü UÇTAN UCA TANILAMASI (2026-10-05, kullanıcı isteği: "tanılama ve teşhisi eksik olan … izlemeler için
 * tanılama ekleyelim … hata alındığında detaylıca ne hatası aldığını görelim"). Keyword/HTTP tanılamasının aynası
 * ({@link PageDiagSupport}), üstüne KAYNAK çözümlemesi.
 *
 * <p>Her yolda:
 * <ul>
 *   <li>ana sayfanın ham soket ölçümü ({@link RawHttpProbe}) — izlemenin isteğinin AYNISI: GET, Sayfa Bütünlüğü'nün
 *       User-Agent'ı ({@code site.monitor.page.user-agent}), sayfa çekirdeğinin Accept / Accept-Language / Accept-Encoding
 *       başlıkları, 5 yönlendirme, güven-hepsi TLS;</li>
 *   <li>izlemenin GERÇEK kontrolü ({@link PageCheckerService#check}, tek sayfa, kaydetmeden) → {@code client_check} +
 *       izlemenin kararı (alarm aç/kapa ayarlarıyla — sweep'in {@code issueAlarmWorthy} kuralı, TEK kopya).</li>
 * </ul>
 * İzlemenin yolu ayrıca çözümlenir ({@code data.page}): kayda geçecek durum, kaynak toplamları (kırık / zaman aşımı / mixed /
 * belirsiz / yavaş, birinci ↔ üçüncü taraf, alarma sayılan), en çok {@value #MAX_ISSUES} sorun (URL maskeli, tür, HTTP, süre,
 * yol, alarm), sınırlar. SITE_CRAWL izlemesinde tanılama YALNIZ başlangıç sayfasını dener (günlük tarama dakikalar sürebilir)
 * ve bunu bulgu olarak söyler.
 */
@Slf4j
@Service
public class PageDiagnosticsService extends PageDiagSupport {

    /** {@code diagnostic_runs.run_type}. */
    public static final String RUN_TYPE = "PAGE_DIAG";
    /** Çözümleme bloğundaki sorun satırı tavanı. */
    static final int MAX_ISSUES = 20;

    private final PageCheckerService pageChecker;
    private final AppSettingsService appSettings;

    @Autowired
    public PageDiagnosticsService(SsrfGuard ssrfGuard, TrustEvaluator trustEvaluator, CaAutoPinService caAutoPin,
                                  ProxySettings proxySettings, ProxyPolicyService proxyPolicy,
                                  PageCheckerService pageChecker, AppSettingsService appSettings) {
        super(ssrfGuard, trustEvaluator, caAutoPin, proxySettings, proxyPolicy, "page-diag-watchdog");
        this.pageChecker = pageChecker;
        this.appSettings = appSettings;
    }

    /** Gerçek kontrolün sorun sıralaması: alarma sayılan önce, sonra tür. */
    private static final List<String> KIND_ORDER = List.of("BROKEN", "TIMEOUT", "MIXED_CONTENT", "BLOCKED", "SLOW");

    /**
     * Tanılamayı koşturur. Dönen harita sözleşmedeki {@code data} nesnesidir ({@code run_id} null — denetleyici kayıttan
     * sonra doldurur). Hop gövde önizlemeleri DAHİLDİR; geçmişe yazmadan önce {@link PageDiagnosticsHistory} siler.
     */
    public Map<String, Object> diagnose(PageMonitor m, boolean compare) {
        long start = System.currentTimeMillis();
        String url = m.getUrl() == null ? "" : m.getUrl().trim();
        int monitorTimeout = m.getTimeoutMs() != null ? m.getTimeoutMs() : 4000;
        int timeout = HttpDiagnosticsService.clampTimeout(monitorTimeout);
        int slowMs = m.getSlowResourceMs() != null ? m.getSlowResourceMs() : 2000;
        int concurrency = m.getResourceConcurrency() != null ? m.getResourceConcurrency() : 5;
        int capSeconds = clientCapSeconds();
        String userAgent = pageChecker.effectiveUserAgent();

        String proxyAuth = proxyConfigured() ? proxySettings.proxyAuthorizationHeader() : null;
        HttpDiagMasker masker = new HttpDiagMasker(List.of(), secrets(Map.of(), null, null, proxyAuth));

        String mode = ProxyPolicyService.normalizeMode(m.getUseProxy());
        ProxyPolicyService.Decision d = proxyPolicy == null
                ? ProxyPolicyService.Decision.direct("none") : proxyPolicy.decide(url, mode);
        List<Plan> plans = plans(d, compare);
        boolean altAvailable = plans.size() > 1;

        long hardDeadline = start + PATH_CAP_MS;
        List<RawHttpProbe.Spec> specs = new ArrayList<>();
        List<Future<RawHttpProbe>> rawFutures = new ArrayList<>();
        List<Future<ClientRun>> clientFutures = new ArrayList<>();
        for (Plan p : plans) {
            RawHttpProbe.Spec spec = spec(p, url, timeout, HttpRequestOptions.NONE, proxyAuth, hardDeadline, userAgent);
            specs.add(spec);
            rawFutures.add(submitProbe(spec, masker));
            boolean via = p.viaProxy();
            clientFutures.add(workers.submit(() -> {
                long c0 = System.currentTimeMillis();
                PageCheckResult r = pageChecker.check(url, "SINGLE_PAGE", timeout, slowMs, concurrency,
                        m.getExcludePatterns(), 0, 1, capSeconds, via);
                return new ClientRun(r, System.currentTimeMillis() - c0);
            }));
        }

        long collectDeadline = start + COLLECT_CAP_MS;
        List<Map<String, Object>> paths = new ArrayList<>();
        List<Map<String, Object>> pathFindings = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        List<String> failedSteps = new ArrayList<>();
        Map<String, Object> pageBlock = null;
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            RawHttpProbe.Spec spec = specs.get(i);
            RawHttpProbe probe = await(rawFutures.get(i), collectDeadline);
            if (probe == null) probe = RawHttpProbe.abandoned(spec, masker, System.currentTimeMillis() - start, HttpDiagnosticsService.OVERALL_CAP_MS);
            ClientRun client = await(clientFutures.get(i), collectDeadline);
            PathEval ev = evaluate(probe, spec, client, m, url, capSeconds, plan.isMonitor());
            Map<String, Object> path = basePath(plan, probe, ev.outcome, ev.failedStep, clientMap(client, m));
            path.put("page", pathSummary(ev));
            paths.add(path);
            pathFindings.addAll(ev.findings);
            outcomes.add(ev.outcome);
            statuses.add(probe.finalStatus());
            failedSteps.add(ev.failedStep);
            if (plan.isMonitor()) pageBlock = ev.block;
        }

        List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, Object> comparison = compare(paths, outcomes, statuses, failedSteps, altAvailable, "page", findings);
        findings.addAll(pathFindings);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", null);
        data.put("kind", "page");
        data.put("started_at", Instant.ofEpochMilli(start).truncatedTo(ChronoUnit.SECONDS).toString());
        data.put("duration_ms", System.currentTimeMillis() - start);
        data.put("monitor", monitorMap(m, timeout, monitorTimeout, mode, slowMs, capSeconds, userAgent));
        data.put("source", source());
        data.put("proxy", proxyMap());
        data.put("verdict", PageDiagFindings.verdict(findings, outcomes.get(0), failedSteps.get(0)));
        data.put("findings", findings);
        data.put("paths", paths);
        data.put("comparison", comparison);
        data.put("page", pageBlock);
        masker.scrubDeep(data);   // son savunma hattı: hiçbir alanda sır değeri düz kalmasın
        return data;
    }

    /** Gerçek kontrolün duvar-saati tavanı: izlemenin ayarı ({@code site.monitor.page.max-check-seconds}) ile tanılama bütçesinin küçüğü. */
    int clientCapSeconds() {
        int configured = appSettings == null ? 120 : appSettings.getInt("site.monitor.page.max-check-seconds", 120);
        return Math.max(5, Math.min(configured, CLIENT_CAP_SECONDS));
    }

    /** Gerçek kontrolün sonucu + harcadığı süre (süre bütçesine takılıp takılmadığını anlamak için). */
    record ClientRun(PageCheckResult result, long elapsedMs) {}

    // ── Yol değerlendirmesi ─────────────────────────────────────────────────────────────────────

    static final class PathEval {
        String outcome;
        String failedStep;
        final List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, Object> block;
        Counts counts;
        String recordedStatus;
        boolean monitorOk;
    }

    /** Sorun sayaçları (kayda geçenlerle aynı sınıflar). */
    static final class Counts {
        int total, broken, timeouts, mixed, blocked, slow, firstPartyProblems, thirdPartyProblems, thirdPartyAlarm, alarm,
                alarmBroken, alarmTimeouts;
        final Set<String> thirdPartyHosts = new LinkedHashSet<>();
        String firstProblemFirstParty, firstProblemThirdParty, firstBroken, firstMixed, firstBlocked, slowestUrl;
        Long slowestMs;
    }

    /**
     * Ham ölçüm + gerçek kontrol → yolun sonucu, bulguları ve (izlemenin yolu için) kaynak çözümlemesi. İzlemenin kararını
     * AYNEN taklit eder: ana sayfa alınamadıysa DOWN; alındıysa alarma sayılan kaynak sorunu varsa izleme alarm verir
     * (sonuç fail, düşen adım {@code resources}); yoksa ok.
     */
    static PathEval evaluate(RawHttpProbe probe, RawHttpProbe.Spec spec, ClientRun client, PageMonitor m, String url,
                             int capSeconds, boolean analyze) {
        PathEval ev = new PathEval();
        String key = spec.key();
        RawVerdict raw = rawVerdict(probe, spec, PageDiagFindings.PAGE_HTTP_STATUS, PageDiagFindings.PAGE_BODY_UNREAD);
        PageCheckResult res = client == null ? null : client.result();
        boolean mainUp = res != null && res.mainReachable();

        if (raw != null) {
            ev.outcome = "fail";
            ev.failedStep = raw.failedStep();
            ev.findings.add(raw.finding());
            if (mainUp) {
                ev.findings.add(HttpDiagFindings.finding(HttpDiagFindings.CLIENT_MISMATCH, HttpDiagFindings.WARN, key,
                        RawHttpProbe.params("route", spec.route(), "raw_status", probe.finalStatus(),
                                "client_error", "HTTP " + res.httpStatus())));
            }
        } else if (res == null || !mainUp) {
            ev.outcome = "fail";
            ev.failedStep = "response";
            String failure = res == null ? null : res.failure() != null ? res.failure().code() : null;
            // reason=unfinished: izlemenin kontrolü tanılamanın süre bütçesinde bitmedi (arayüz varyant metnini seçer)
            ev.findings.add(HttpDiagFindings.finding(PageDiagFindings.PAGE_DOWN, HttpDiagFindings.FAIL, key,
                    RawHttpProbe.params("route", spec.route(), "raw_status", probe.finalStatus(),
                            "status", res == null ? null : res.httpStatus(), "failure", failure,
                            "error", res == null ? null : abbreviate(res.error(), 200),
                            "reason", res == null ? "unfinished" : null)));
        }

        if (res != null && mainUp) {
            Counts c = count(res, m);
            ev.counts = c;
            boolean alertTimeout = !Boolean.FALSE.equals(m.getAlertTimeout());
            String recorded = res.status();
            if (!alertTimeout && c.timeouts > 0 && c.broken == 0 && c.mixed == 0) recorded = "OK";   // sweep'le aynı kural
            ev.recordedStatus = recorded;
            ev.monitorOk = c.alarm == 0;
            if (raw == null) {
                ev.outcome = c.alarm > 0 ? "fail" : "ok";
                if (c.alarm > 0) ev.failedStep = "resources";
            }
            if (analyze) resourceFindings(ev, c, res, m, key, client.elapsedMs(), capSeconds);
            if (raw == null && "ok".equals(ev.outcome)) {
                ev.findings.add(HttpDiagFindings.finding(PageDiagFindings.PAGE_OK, HttpDiagFindings.INFO, key,
                        RawHttpProbe.params("status", res.httpStatus(), "resources", res.totalResources(),
                                "ms", res.responseMs())));
            }
        } else if (res != null) {
            ev.recordedStatus = res.status();
        }
        if (analyze) ev.block = block(ev, res, m, url, spec.route(), capSeconds, client);
        return ev;
    }

    /** Sorunları sayar (izlemenin alarm kuralıyla). */
    static Counts count(PageCheckResult res, PageMonitor m) {
        Counts c = new Counts();
        c.total = res.totalResources();
        for (ResourceIssue i : res.issues()) {
            String type = i.issueType() == null ? "" : i.issueType();
            boolean alarm = PageCheckerService.issueAlarmWorthy(m, i);
            if (alarm) c.alarm++;
            switch (type) {
                case "BROKEN" -> { c.broken++; if (alarm) c.alarmBroken++; if (c.firstBroken == null) c.firstBroken = i.resourceUrl(); }
                case "TIMEOUT" -> { c.timeouts++; if (alarm) c.alarmTimeouts++; }
                case "MIXED_CONTENT" -> { c.mixed++; if (c.firstMixed == null) c.firstMixed = i.resourceUrl(); }
                case "BLOCKED" -> { c.blocked++; if (c.firstBlocked == null) c.firstBlocked = i.resourceUrl(); }
                case "SLOW" -> {
                    c.slow++;
                    if (i.durationMs() != null && (c.slowestMs == null || i.durationMs() > c.slowestMs)) {
                        c.slowestMs = i.durationMs();
                        c.slowestUrl = i.resourceUrl();
                    }
                }
                default -> { /* bilinmeyen tür — sayılmaz */ }
            }
            if ("BROKEN".equals(type) || "TIMEOUT".equals(type)) {
                if (i.firstParty()) {
                    c.firstPartyProblems++;
                    if (c.firstProblemFirstParty == null) c.firstProblemFirstParty = i.resourceUrl();
                } else {
                    c.thirdPartyProblems++;
                    if (alarm) c.thirdPartyAlarm++;
                    if (c.firstProblemThirdParty == null) c.firstProblemThirdParty = i.resourceUrl();
                    String h = PageFetchCore.hostOf(i.resourceUrl());
                    if (h != null && c.thirdPartyHosts.size() < 5) c.thirdPartyHosts.add(h);
                }
            }
        }
        return c;
    }

    /** İzlemenin yolunun kaynak bulguları — önem sırasıyla. */
    private static void resourceFindings(PathEval ev, Counts c, PageCheckResult res, PageMonitor m, String key,
                                         long clientMs, int capSeconds) {
        boolean alertMixed = !Boolean.FALSE.equals(m.getAlertMixedContent());
        if (c.broken > 0) {
            ev.findings.add(HttpDiagFindings.finding(PageDiagFindings.RESOURCES_BROKEN,
                    c.alarmBroken > 0 ? HttpDiagFindings.FAIL : HttpDiagFindings.WARN, key,
                    RawHttpProbe.params("count", c.broken, "alarm", c.alarmBroken, "total", c.total,
                            "sample", displayUrl(c.firstBroken))));
        }
        if (c.timeouts > 0) {
            ev.findings.add(HttpDiagFindings.finding(PageDiagFindings.RESOURCES_TIMEOUT,
                    c.alarmTimeouts > 0 ? HttpDiagFindings.FAIL : HttpDiagFindings.WARN, key,
                    RawHttpProbe.params("count", c.timeouts, "alarm", c.alarmTimeouts,
                            "ms", m.getTimeoutMs() != null ? m.getTimeoutMs() : 4000)));
        }
        if (c.mixed > 0) {
            ev.findings.add(HttpDiagFindings.finding(PageDiagFindings.MIXED_CONTENT,
                    alertMixed ? HttpDiagFindings.FAIL : HttpDiagFindings.WARN, key,
                    RawHttpProbe.params("count", c.mixed, "sample", displayUrl(c.firstMixed))));
        }
        String rootHost = PageFetchCore.hostOf(m.getUrl());
        if (c.firstPartyProblems > 0) {
            ev.findings.add(HttpDiagFindings.finding(PageDiagFindings.SAME_HOST_BROKEN, HttpDiagFindings.WARN, key,
                    RawHttpProbe.params("count", c.firstPartyProblems, "host", rootHost,
                            "sample", displayUrl(c.firstProblemFirstParty))));
        } else if (c.thirdPartyProblems > 0) {
            // reason: bu dış sorunlardan biri izlemenin alarmına sayılıyor mu (3. taraf alarmı açık / dış kaynak zaman aşımı)
            boolean alarms = c.thirdPartyAlarm > 0;
            ev.findings.add(HttpDiagFindings.finding(PageDiagFindings.THIRD_PARTY_ONLY,
                    alarms ? HttpDiagFindings.WARN : HttpDiagFindings.INFO, key,
                    RawHttpProbe.params("count", c.thirdPartyProblems, "hosts", String.join(", ", c.thirdPartyHosts),
                            "reason", alarms ? "alarm" : "quiet")));
        }
        if (c.blocked > 0) {
            ev.findings.add(HttpDiagFindings.finding(PageDiagFindings.RESOURCES_BLOCKED, HttpDiagFindings.INFO, key,
                    RawHttpProbe.params("count", c.blocked, "sample", displayUrl(c.firstBlocked))));
        }
        if (c.slow > 0) {
            ev.findings.add(HttpDiagFindings.finding(PageDiagFindings.RESOURCES_SLOW, HttpDiagFindings.INFO, key,
                    RawHttpProbe.params("count", c.slow, "ms", m.getSlowResourceMs() != null ? m.getSlowResourceMs() : 2000,
                            "slowest_ms", c.slowestMs, "sample", displayUrl(c.slowestUrl))));
        }
        if (c.total >= PageFetchCore.MAX_RESOURCES_PER_CHECK) {
            ev.findings.add(HttpDiagFindings.finding(PageDiagFindings.CRAWL_LIMIT, HttpDiagFindings.WARN, key,
                    RawHttpProbe.params("reason", "resources", "cap", PageFetchCore.MAX_RESOURCES_PER_CHECK, "total", c.total)));
        } else if (clientMs >= capSeconds * 1000L - 1000L) {
            ev.findings.add(HttpDiagFindings.finding(PageDiagFindings.CRAWL_LIMIT, HttpDiagFindings.WARN, key,
                    RawHttpProbe.params("reason", "time", "seconds", capSeconds, "total", c.total)));
        }
        if ("SITE_CRAWL".equalsIgnoreCase(m.getMode())) {
            ev.findings.add(HttpDiagFindings.finding(PageDiagFindings.CRAWL_LIMIT, HttpDiagFindings.INFO, key,
                    RawHttpProbe.params("reason", "single_page",
                            "pages", m.getCrawlMaxPages() != null ? m.getCrawlMaxPages() : 50)));
        }
    }

    /** İzlemenin yolunun kaynak çözümlemesi ({@code data.page}). */
    static Map<String, Object> block(PathEval ev, PageCheckResult res, PageMonitor m, String url, String route,
                                     int capSeconds, ClientRun client) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("path", "monitor");
        b.put("analyzed", res != null && res.mainReachable());
        b.put("mode", "SINGLE_PAGE");
        b.put("monitor_mode", m.getMode() == null ? "SINGLE_PAGE" : m.getMode().toUpperCase(Locale.ROOT));
        b.put("crawl_max_pages", m.getCrawlMaxPages());
        b.put("recorded_status", ev.recordedStatus);
        b.put("monitor_ok", res != null && res.mainReachable() && ev.monitorOk);
        b.put("main_reachable", res != null && res.mainReachable());
        b.put("http_status", res == null ? null : res.httpStatus());
        b.put("response_ms", res == null ? null : res.responseMs());
        b.put("body_bytes", res == null ? null : res.bodyBytes());
        b.put("failure_reason", res == null || res.failure() == null ? null : res.failure().code());
        b.put("error", res == null ? null : abbreviate(res.error(), 300));
        Counts c = ev.counts;
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("resources", c == null ? null : c.total);
        totals.put("broken", c == null ? null : c.broken);
        totals.put("timeouts", c == null ? null : c.timeouts);
        totals.put("mixed", c == null ? null : c.mixed);
        totals.put("blocked", c == null ? null : c.blocked);
        totals.put("slow", c == null ? null : c.slow);
        totals.put("first_party", c == null ? null : c.firstPartyProblems);
        totals.put("third_party", c == null ? null : c.thirdPartyProblems);
        totals.put("alarm", c == null ? null : c.alarm);
        b.put("totals", totals);
        List<Map<String, Object>> rows = new ArrayList<>();
        int issuesTotal = 0;
        if (res != null) {
            List<ResourceIssue> sorted = new ArrayList<>(res.issues());
            issuesTotal = sorted.size();
            sorted.sort(Comparator
                    .comparing((ResourceIssue i) -> !PageCheckerService.issueAlarmWorthy(m, i))
                    .thenComparingInt(i -> {
                        int k = KIND_ORDER.indexOf(i.issueType());
                        return k < 0 ? KIND_ORDER.size() : k;
                    }));
            for (ResourceIssue i : sorted) {
                if (rows.size() >= MAX_ISSUES) break;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("url", displayUrl(i.resourceUrl()));
                r.put("resource_type", i.resourceType());
                r.put("kind", i.issueType());
                r.put("status", i.httpStatus());
                r.put("ms", i.durationMs());
                r.put("first_party", i.firstParty());
                r.put("alarm", PageCheckerService.issueAlarmWorthy(m, i));
                r.put("via", route);
                r.put("source_page", sameAsUrl(i.sourcePage(), url) ? null : displayUrl(i.sourcePage()));
                rows.add(r);
            }
        }
        b.put("issues", rows);
        b.put("issues_total", issuesTotal);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("resource_cap", PageFetchCore.MAX_RESOURCES_PER_CHECK);
        limits.put("resource_cap_hit", c != null && c.total >= PageFetchCore.MAX_RESOURCES_PER_CHECK);
        limits.put("time_budget_s", capSeconds);
        limits.put("time_budget_hit", client != null && client.elapsedMs() >= capSeconds * 1000L - 1000L);
        b.put("limits", limits);
        Map<String, Object> toggles = new LinkedHashMap<>();
        toggles.put("alert_third_party", Boolean.TRUE.equals(m.getAlertThirdParty()));
        toggles.put("alert_mixed_content", !Boolean.FALSE.equals(m.getAlertMixedContent()));
        toggles.put("alert_timeout", !Boolean.FALSE.equals(m.getAlertTimeout()));
        toggles.put("slow_resource_ms", m.getSlowResourceMs() != null ? m.getSlowResourceMs() : 2000);
        b.put("toggles", toggles);
        return b;
    }

    private static boolean sameAsUrl(String a, String b) {
        return a == null || (b != null && PageFetchCore.stripFragment(a).equalsIgnoreCase(PageFetchCore.stripFragment(b)));
    }

    /** Yol kartının özeti (karşılaştırma için) — tam çözümleme yalnız izlemenin yolunda ({@code data.page}). */
    private static Map<String, Object> pathSummary(PathEval ev) {
        Map<String, Object> s = new LinkedHashMap<>();
        Counts c = ev.counts;
        s.put("recorded_status", ev.recordedStatus);
        s.put("resources", c == null ? null : c.total);
        s.put("broken", c == null ? null : c.broken);
        s.put("timeouts", c == null ? null : c.timeouts);
        s.put("mixed", c == null ? null : c.mixed);
        s.put("alarm", c == null ? null : c.alarm);
        return s;
    }

    /**
     * İzlemenin gerçek kontrolünün sonucu — {@code ok} = izlemenin KENDİ kararı (ana sayfa alındı VE alarma sayılan kaynak
     * sorunu yok; yolun sonucuyla aynı dil), {@code main_reachable} ayrıca.
     */
    private static Map<String, Object> clientMap(ClientRun run, PageMonitor m) {
        Map<String, Object> c = new LinkedHashMap<>();
        PageCheckResult r = run == null ? null : run.result();
        if (r == null) {
            c.put("ok", false);
            c.put("main_reachable", false);
            c.put("http_status", null);
            c.put("response_ms", null);
            c.put("http_version", null);
            c.put("error", "tanılama süre sınırında tamamlanmadı");
            c.put("status", null);
            c.put("failure_reason", null);
            return c;
        }
        c.put("ok", r.mainReachable() && count(r, m).alarm == 0);
        c.put("main_reachable", r.mainReachable());
        c.put("http_status", r.httpStatus());
        c.put("response_ms", r.responseMs());
        c.put("http_version", null);
        c.put("error", abbreviate(r.error(), 300));
        c.put("status", r.status());
        c.put("failure_reason", r.failure() == null ? null : r.failure().code());
        c.put("resources", r.totalResources());
        c.put("broken", r.brokenResources());
        c.put("timeouts", r.timeoutResources());
        c.put("mixed", r.mixedContentCount());
        return c;
    }

    private static Map<String, Object> monitorMap(PageMonitor m, int diagTimeout, int monitorTimeout, String mode,
                                                  int slowMs, int capSeconds, String userAgent) {
        Map<String, Object> mon = new LinkedHashMap<>();
        mon.put("id", m.getId());
        mon.put("name", m.getName());
        mon.put("url", m.getUrl());
        mon.put("method", "GET");
        mon.put("mode", m.getMode() == null ? "SINGLE_PAGE" : m.getMode().toUpperCase(Locale.ROOT));
        mon.put("timeout_ms", monitorTimeout);
        mon.put("diag_timeout_ms", diagTimeout);
        mon.put("verify_ssl", false);
        mon.put("follow_redirects", true);
        mon.put("proxy_mode", mode);
        mon.put("user_agent", userAgent);
        mon.put("slow_resource_ms", slowMs);
        mon.put("check_budget_s", capSeconds);
        return mon;
    }
}
