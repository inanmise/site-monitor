package com.sitemonitor.service.keyword.diagnose;

import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.KeywordCheckerService;
import com.sitemonitor.service.KeywordHeaderSecrets;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.http.HttpRequestOptions;
import com.sitemonitor.service.http.HttpRequestRules;
import com.sitemonitor.service.http.diagnose.HttpDiagFindings;
import com.sitemonitor.service.http.diagnose.HttpDiagMasker;
import com.sitemonitor.service.http.diagnose.HttpDiagnosticsService;
import com.sitemonitor.service.http.diagnose.RawHttpProbe;
import com.sitemonitor.service.keyword.KeywordBodyAnalyzer;
import com.sitemonitor.service.keyword.KeywordFailureClassifier;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Keyword UÇTAN UCA TANILAMASI (2026-10-04, kullanıcı isteği: "http/website izlemedeki gibi keyword izlemeye de
 * tanılama alanı … kullanıcı hata aldığında sebep sonuç ile detaylıca bilgi sahibi olsun"). HTTP tanılamasının
 * ({@link HttpDiagnosticsService}) aynası, üstüne anahtar kelime çözümlemesi.
 *
 * <p>Her yol (izlemenin KENDİ yolu — vekil kararı {@link ProxyPolicyService}; vekil tanımlıysa ÖTEKİ yol paralel) için:
 * <ul>
 *   <li>ham soket ölçümü ({@link RawHttpProbe}) — izlemenin isteğinin AYNISI: GET, keyword izlemesinin User-Agent'ı,
 *       izlemenin şifreli özel başlıkları (yalnız ilk host'a), yönlendirme takibi, güven-hepsi TLS (doğrulama yok —
 *       izleme de doğrulamaz; güven durumu bilgi olarak raporlanır), gövde tam okunur (2 MB tavan — izlemeninkiyle
 *       aynı);</li>
 *   <li>izlemenin GERÇEK istemcisi ({@link KeywordCheckerService#check} beklentili) → {@code client_check};</li>
 *   <li>gövde üzerinde anahtar kelime çözümlemesi ({@link KeywordBodyAnalyzer}): adet + koşul, en çok 5 eşleşme
 *       bağlamı, harf duyarsız / normalleştirilmiş / karakter kümesi alternatifleri, ipuçları, içerik türü / boyut /
 *       tavan, görünür metin önizlemesi.</li>
 * </ul>
 *
 * <p><b>Mevcudu etkilemez.</b> Hiçbir {@code keyword_results} satırı yazılmaz, sweep / alarm değerlendirmesi / eskalasyon
 * çağrılmaz, CA pinlenmez, izleme değişmez. Kalıcı tek iz tanılama geçmişi ({@code diagnostic_runs}, gövde önizlemesiz)
 * ve denetim kaydı — ikisi denetleyicide. Süre / iş parçacığı kuralları HTTP ile aynı (yol başına 1–30 sn, toplam ≤ 60
 * sn, sanal iş parçacıkları; sweep havuzu kullanılmaz).
 */
@Slf4j
@Service
public class KeywordDiagnosticsService {

    /** {@code diagnostic_runs.run_type}. */
    public static final String RUN_TYPE = "KEYWORD_DIAG";
    /** Keyword izlemesinin gönderdiği User-Agent ({@code KeywordCheckerService.sendFollowingSafely}). */
    public static final String USER_AGENT = "SiteMonitor-KeywordMonitor/1.0";
    /** Eşleşme bağlamı sayısı / yarıçapı. */
    static final int MAX_CONTEXTS = 5;
    static final int CONTEXT_RADIUS = 60;
    /** Görünür metin önizlemesi (yalnız canlı yanıtta; geçmişe YAZILMAZ). */
    static final int VISIBLE_PREVIEW_MAX = 3000;
    static final long PATH_CAP_MS = 55_000L;
    static final long COLLECT_CAP_MS = 58_000L;

    private final SsrfGuard ssrfGuard;
    private final TrustEvaluator trustEvaluator;
    private final KeywordCheckerService keywordChecker;
    private final CaAutoPinService caAutoPin;
    private final ProxySettings proxySettings;
    private final ProxyPolicyService proxyPolicy;
    private final KeywordHeaderSecrets headerSecrets;

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "keyword-diag-watchdog");
        t.setDaemon(true);
        return t;
    });

    /** Ortam değişkeni okuyucu — test kaynak bilgisini sabitleyebilsin. */
    UnaryOperator<String> env = System::getenv;

    @Autowired
    public KeywordDiagnosticsService(SsrfGuard ssrfGuard, TrustEvaluator trustEvaluator, KeywordCheckerService keywordChecker,
                                     CaAutoPinService caAutoPin, ProxySettings proxySettings, ProxyPolicyService proxyPolicy,
                                     KeywordHeaderSecrets headerSecrets) {
        this.ssrfGuard = ssrfGuard;
        this.trustEvaluator = trustEvaluator;
        this.keywordChecker = keywordChecker;
        this.caAutoPin = caAutoPin;
        this.proxySettings = proxySettings;
        this.proxyPolicy = proxyPolicy;
        this.headerSecrets = headerSecrets;
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
        watchdog.shutdownNow();
    }

    private record Plan(String key, String route, Map<String, Object> decision) {}

    /**
     * Tanılamayı koşturur. Dönen harita sözleşmedeki {@code data} nesnesidir ({@code run_id} null — denetleyici kayıttan
     * sonra doldurur). Gövde önizlemeleri ve görünür metin DAHİLDİR; geçmişe yazmadan önce {@link KeywordDiagnosticsHistory}
     * siler.
     */
    public Map<String, Object> diagnose(KeywordMonitor m, boolean compare) {
        long start = System.currentTimeMillis();
        String rawUrl = m.getUrl() == null ? "" : m.getUrl().trim();
        String url = rawUrl.contains("{timestamp}")   // izleme her kontrolde güncel Unix saniyesini koyar — aynısı
                ? rawUrl.replace("{timestamp}", String.valueOf(Instant.now().getEpochSecond())) : rawUrl;
        int timeout = HttpDiagnosticsService.clampTimeout(m.getTimeoutMs());
        int monitorTimeout = m.getTimeoutMs() != null ? m.getTimeoutMs() : 10_000;
        String headersText = headerSecrets == null ? null : headerSecrets.effectiveHeaders(m);
        HttpRequestOptions opts = new HttpRequestOptions(headersText, null, null, null, null, null, null);
        Map<String, String> custom = HttpRequestRules.parseHeaders(headersText);
        boolean caseSensitive = Boolean.TRUE.equals(m.getCaseSensitive());
        String keyword = m.getKeyword() == null ? "" : m.getKeyword();
        KeywordCheckerService.Expectation exp = KeywordCheckerService.Expectation.of(m);

        boolean proxyConfigured = proxySettings != null && proxySettings.enabled();
        String proxyAuth = proxyConfigured ? proxySettings.proxyAuthorizationHeader() : null;
        HttpDiagMasker masker = new HttpDiagMasker(custom.keySet(), secrets(custom, proxyAuth));

        String mode = ProxyPolicyService.normalizeMode(m.getUseProxy());
        ProxyPolicyService.Decision d = proxyPolicy == null
                ? ProxyPolicyService.Decision.direct("none") : proxyPolicy.decide(rawUrl, mode);
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
            // verifySsl=false: keyword izlemesi güven-hepsi TLS ile okur (sertifika ayrı izlenir) — tanılama da aynı
            // kararı verir; zincir/güven/ad eşleşmesi BİLGİ olarak raporlanır.
            RawHttpProbe.Spec spec = new RawHttpProbe.Spec(p.key(), p.route(), url, "GET", null, timeout,
                    false, true, opts,
                    viaProxy ? proxySettings.host() : null, viaProxy ? proxySettings.port() : 0,
                    viaProxy ? proxyAuth : null, hardDeadline, USER_AGENT, true);
            specs.add(spec);
            rawFutures.add(workers.submit(() -> new RawHttpProbe(spec, ssrfGuard, trustEvaluator, caAutoPin, masker, watchdog).run()));
            clientFutures.add(workers.submit(() -> keywordChecker.check(rawUrl, keyword, timeout, headersText,
                    caseSensitive, viaProxy, exp)));
        }

        long collectDeadline = start + COLLECT_CAP_MS;
        List<Map<String, Object>> paths = new ArrayList<>();
        List<Map<String, Object>> pathFindings = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        List<Integer> statuses = new ArrayList<>();
        List<String> failedSteps = new ArrayList<>();
        Map<String, Object> keywordBlock = null;
        boolean slowEnabled = Boolean.TRUE.equals(m.getSlowResponseEnabled());
        Integer slowMs = slowEnabled ? (m.getSlowThresholdMs() != null ? m.getSlowThresholdMs() : 3000) : null;
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            RawHttpProbe.Spec spec = specs.get(i);
            RawHttpProbe probe = await(rawFutures.get(i), collectDeadline);
            if (probe == null) probe = RawHttpProbe.abandoned(spec, masker, System.currentTimeMillis() - start, 60_000L);
            Map<String, Object> client = clientResult(await(clientFutures.get(i), collectDeadline));
            boolean isMonitor = "monitor".equals(plan.key());
            PathEval ev = evaluate(probe, spec, client, m, keyword, caseSensitive, exp, slowMs, masker, isMonitor);
            Map<String, Object> path = pathMap(plan, probe, ev, client);
            paths.add(path);
            pathFindings.addAll(ev.findings);
            outcomes.add(ev.outcome);
            statuses.add(probe.finalStatus());
            failedSteps.add(ev.failedStep);
            if (isMonitor) keywordBlock = ev.keyword;
        }

        // ── Karşılaştırma (HTTP ile aynı kural + keyword sonucu da yol farkı sayılır) ──
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
                // reason=keyword: arayüz HTTP metninin keyword varyantını seçer ("istek tamamlanamadı" değil "koşul sağlanmadı")
                findings.add(HttpDiagFindings.finding(HttpDiagFindings.PATH_DIFFERS, HttpDiagFindings.FAIL, "monitor",
                        RawHttpProbe.params("failing_route", paths.get(0).get("route"),
                                "working_route", paths.get(1).get("route"), "working_status", as, "reason", "keyword")));
            } else if (monitorFails && "fail".equals(ao) && ms == null && as == null) {
                findings.add(HttpDiagFindings.finding(HttpDiagFindings.BOTH_PATHS_FAIL, HttpDiagFindings.FAIL, null,
                        RawHttpProbe.params("failed_step", failedSteps.get(0))));
            }
        }
        comparison.put("differs", differs);
        findings.addAll(pathFindings);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", null);
        data.put("kind", "keyword");
        data.put("started_at", Instant.ofEpochMilli(start).truncatedTo(ChronoUnit.SECONDS).toString());
        data.put("duration_ms", System.currentTimeMillis() - start);
        data.put("monitor", monitorMap(m, timeout, monitorTimeout, mode, custom, slowMs));
        data.put("source", source());
        data.put("proxy", proxyMap(proxyConfigured));
        data.put("verdict", KeywordDiagFindings.verdict(findings, outcomes.get(0), failedSteps.get(0)));
        data.put("findings", findings);
        data.put("paths", paths);
        data.put("comparison", comparison);
        data.put("keyword", keywordBlock);
        masker.scrubDeep(data);   // son savunma hattı: hiçbir alanda sır değeri düz kalmasın
        return data;
    }

    // ── Yol değerlendirmesi ─────────────────────────────────────────────────────────────────────

    static final class PathEval {
        String outcome;
        String failedStep;
        final List<Map<String, Object>> findings = new ArrayList<>();
        Map<String, Object> keyword;
    }

    /**
     * Ham ölçüm + gerçek istemci → yolun sonucu, bulguları ve anahtar kelime çözümlemesi. İzlemenin kararını AYNEN taklit
     * eder: istek tamamlanmadıysa (ağ / TLS / zaman aşımı / gövde süresi) DOWN; yanıt geldiyse durum kodundan bağımsız
     * olarak gövdede aranır ve adet kuralı uygulanır.
     */
    static PathEval evaluate(RawHttpProbe probe, RawHttpProbe.Spec spec, Map<String, Object> client, KeywordMonitor m,
                             String keyword, boolean caseSensitive, KeywordCheckerService.Expectation exp, Integer slowMs,
                             HttpDiagMasker masker, boolean analyze) {
        PathEval ev = new PathEval();
        String key = spec.key();
        RawHttpProbe.Failure failure = probe.failure();
        if (failure != null) {
            ev.outcome = "fail";
            ev.failedStep = failure.step();
            ev.findings.add(HttpDiagFindings.finding(failure.code(), HttpDiagFindings.FAIL, key, failure.params()));
        } else if (probe.finalStatus() == null) {
            ev.outcome = "fail";
            ev.failedStep = "response";
            ev.findings.add(HttpDiagFindings.finding(HttpDiagFindings.RESPONSE_TIMEOUT, HttpDiagFindings.FAIL, key,
                    RawHttpProbe.params("ms", spec.timeoutMs(), "route", spec.route())));
        } else if (probe.bodyTimedOut()) {
            // İzleme gövdeyi süreyle keser ve kontrolü HATA ile bitirir (anahtar kelime aranamaz) — HTTP'den farklı olarak fail.
            ev.outcome = "fail";
            ev.failedStep = "body";
            ev.findings.add(HttpDiagFindings.finding(KeywordDiagFindings.KEYWORD_BODY_UNREAD, HttpDiagFindings.FAIL, key,
                    RawHttpProbe.params("ms", spec.timeoutMs(), "status", probe.finalStatus())));
        } else {
            int status = probe.finalStatus();
            byte[] body = probe.finalBody() == null ? new byte[0] : probe.finalBody();
            Analysis a = analyzeBody(probe, body, keyword, caseSensitive, exp, status, m.getUrl(), masker, analyze);
            boolean met = KeywordCheckerService.evaluate(a.count, exp.operator(), exp.threshold());
            Map<String, Object> p = RawHttpProbe.params("keyword", abbreviate(keyword, 80), "count", a.count,
                    "expected", KeywordCheckerService.opPhrase(exp.operator(), exp.threshold()), "status", status,
                    "bytes", body.length, "cap_kb", RawHttpProbe.BODY_CAP_BYTES / 1024);
            if (met) {
                ev.outcome = "ok";
                if (status >= 400) {
                    ev.findings.add(HttpDiagFindings.finding(KeywordDiagFindings.KEYWORD_HTTP_ERROR, HttpDiagFindings.WARN, key, p));
                }
                if (probe.finalBodyCapped()) {
                    ev.findings.add(HttpDiagFindings.finding(KeywordDiagFindings.KEYWORD_BODY_TRUNCATED, HttpDiagFindings.WARN, key, p));
                }
                if (slowMs != null && probe.totalMs() > slowMs) {
                    ev.findings.add(HttpDiagFindings.finding(KeywordDiagFindings.KEYWORD_SLOW, HttpDiagFindings.WARN, key,
                            RawHttpProbe.params("total_ms", probe.totalMs(), "threshold_ms", slowMs)));
                }
                ev.findings.add(HttpDiagFindings.finding(KeywordDiagFindings.KEYWORD_OK, HttpDiagFindings.INFO, key, p));
            } else {
                ev.outcome = "fail";
                KeywordFailureClassifier.Reason r = KeywordFailureClassifier.forCondition(status, a.count, exp.operator(),
                        exp.threshold(), body.length, probe.finalBodyCapped(), keyword);
                String code = KeywordDiagFindings.fromFailureReason(r.code());
                ev.failedStep = KeywordDiagFindings.KEYWORD_HTTP_ERROR.equals(code)
                        || KeywordDiagFindings.KEYWORD_REDIRECT_BLOCKED.equals(code) ? "response" : "body";
                ev.findings.add(HttpDiagFindings.finding(code, HttpDiagFindings.FAIL, key, p));
                if (a.block != null) a.block.put("failure_reason", r.code());
            }
            if (analyze && a.block != null) {
                for (Object h : (List<?>) a.block.getOrDefault("hints", List.of())) {
                    ev.findings.add(HttpDiagFindings.finding(String.valueOf(h), HttpDiagFindings.WARN, key, p));
                }
            }
            if (a.block != null) {
                a.block.put("condition_met", met);
                ev.keyword = a.block;
            }
        }
        if (ev.keyword == null && analyze) ev.keyword = unanalyzed(keyword, caseSensitive, exp);
        boolean rawOk = "ok".equals(ev.outcome);
        boolean clientOk = Boolean.TRUE.equals(client.get("ok"));
        if (rawOk != clientOk) {
            ev.findings.add(HttpDiagFindings.finding(HttpDiagFindings.CLIENT_MISMATCH, HttpDiagFindings.WARN, key,
                    RawHttpProbe.params("route", spec.route(), "raw_status", probe.finalStatus(),
                            "client_error", client.get("error") != null ? client.get("error") : client.get("failure_reason"))));
        }
        return ev;
    }

    private record Analysis(int count, Map<String, Object> block) {}

    /** Gövdede arama + (izlemenin yolu için) tam çözümleme bloğu. */
    private static Analysis analyzeBody(RawHttpProbe probe, byte[] body, String keyword, boolean cs,
                                        KeywordCheckerService.Expectation exp, int status, String monitorUrl,
                                        HttpDiagMasker masker, boolean full) {
        String text = new String(body, StandardCharsets.UTF_8);
        int count = KeywordBodyAnalyzer.count(text, keyword, cs);
        Map<String, Object> last = lastResponse(probe);
        String contentType = last == null ? null : headerValue(last, "content-type");
        int redirects = Math.max(0, probe.hops().size() - 1);
        URI finalUri = finalUri(probe);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("path", "monitor");
        b.put("keyword", keyword);
        b.put("case_sensitive", cs);
        b.put("operator", exp.operator() == null ? "GTE" : exp.operator());
        b.put("match_count", exp.threshold());
        b.put("absence_rule", KeywordFailureClassifier.isAbsenceRule(exp.operator(), exp.threshold()));
        b.put("analyzed", true);
        b.put("occurrences", count);
        b.put("condition_met", null);
        b.put("failure_reason", null);
        b.put("http_status", status);
        b.put("final_url", KeywordBodyAnalyzer.displayUrl(finalUri));
        b.put("redirect_count", redirects);
        b.put("content_type", contentType);
        b.put("text_like", KeywordBodyAnalyzer.isTextual(contentType, body));
        b.put("body_bytes", body.length);
        b.put("body_truncated", probe.finalBodyCapped());
        b.put("checker_cap_bytes", RawHttpProbe.BODY_CAP_BYTES);
        if (!full) return new Analysis(count, b);
        List<String> secrets = masker.secretValues();
        KeywordBodyAnalyzer.Analysis a = KeywordBodyAnalyzer.analyze(new KeywordBodyAnalyzer.Input(body, contentType, keyword, cs,
                count, exp.operator(), exp.threshold(), status, monitorUrl, finalUri, redirects, secrets));
        b.put("charset", a.declaredCharset());
        b.put("alternatives", a.alternatives().isEmpty()
                ? KeywordBodyAnalyzer.alternatives(text, body, keyword, cs, a.declaredCharset()) : a.alternatives());
        // İpuçları koşul sağlanmasa da sağlansa da hesaplanmaz: analyze() yalnız "eksik" kuralında ve hata sayfasında üretir.
        boolean met = KeywordCheckerService.evaluate(count, exp.operator(), exp.threshold());
        b.put("hints", met ? List.of() : a.hints());
        b.put("title", KeywordBodyAnalyzer.title(text));
        b.put("contexts", KeywordBodyAnalyzer.contexts(text, keyword, cs, MAX_CONTEXTS, CONTEXT_RADIUS, secrets));
        String visible = KeywordBodyAnalyzer.visibleText(text, KeywordBodyAnalyzer.SCAN_CAP, VISIBLE_PREVIEW_MAX + 1);
        boolean cut = visible.length() > VISIBLE_PREVIEW_MAX;
        b.put("visible_text_preview", masker.maskBody(cut ? visible.substring(0, VISIBLE_PREVIEW_MAX) : visible));
        b.put("visible_text_truncated", cut);
        b.put("preview_stored", true);
        return new Analysis(count, b);
    }

    /** Gövde okunamadı/hiç yanıt yok — çözümleme bloğu yine kuralı taşır (arayüz "çözümlenemedi" der). */
    private static Map<String, Object> unanalyzed(String keyword, boolean cs, KeywordCheckerService.Expectation exp) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("path", "monitor");
        b.put("keyword", keyword);
        b.put("case_sensitive", cs);
        b.put("operator", exp.operator() == null ? "GTE" : exp.operator());
        b.put("match_count", exp.threshold());
        b.put("absence_rule", KeywordFailureClassifier.isAbsenceRule(exp.operator(), exp.threshold()));
        b.put("analyzed", false);
        b.put("occurrences", null);
        b.put("condition_met", null);
        b.put("hints", List.of());
        b.put("contexts", List.of());
        return b;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lastResponse(RawHttpProbe probe) {
        List<Map<String, Object>> hops = probe.hops();
        for (int i = hops.size() - 1; i >= 0; i--) {
            if (hops.get(i).get("response") instanceof Map<?, ?> r) return (Map<String, Object>) r;
        }
        return null;
    }

    private static String headerValue(Map<String, Object> response, String name) {
        if (!(response.get("headers") instanceof List<?> hs)) return null;
        for (Object o : hs) {
            if (o instanceof Map<?, ?> h && h.get("name") != null && name.equalsIgnoreCase(String.valueOf(h.get("name")))) {
                Object v = h.get("value");
                return v == null ? null : String.valueOf(v);
            }
        }
        return null;
    }

    private static URI finalUri(RawHttpProbe probe) {
        List<Map<String, Object>> hops = probe.hops();
        if (hops.isEmpty()) return null;
        Object u = hops.get(hops.size() - 1).get("url");
        try {
            return u == null ? null : URI.create(String.valueOf(u));
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> pathMap(Plan plan, RawHttpProbe probe, PathEval ev, Map<String, Object> client) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", plan.key());
        p.put("route", plan.route());
        p.put("decision", plan.decision());
        p.put("outcome", ev.outcome);
        p.put("http_status", probe.finalStatus());
        p.put("total_ms", probe.totalMs());
        p.put("failed_step", ev.failedStep);
        p.put("error", probe.failure() != null ? HttpDiagnosticsService.errorMap(probe.failure().error()) : null);
        p.put("timeline", probe.timeline());
        p.put("hops", probe.hops());
        p.put("transcript", probe.transcript());
        p.put("client_check", client);
        // Yolun anahtar kelime özeti (karşılaştırma kartı için) — tam çözümleme yalnız izlemenin yolunda (data.keyword).
        Map<String, Object> kw = new LinkedHashMap<>();
        kw.put("occurrences", ev.keyword == null ? null : ev.keyword.get("occurrences"));
        kw.put("condition_met", ev.keyword == null ? null : ev.keyword.get("condition_met"));
        kw.put("failure_reason", ev.keyword == null ? null : ev.keyword.get("failure_reason"));
        p.put("keyword", kw);
        return p;
    }

    /** İzlemenin gerçek istemcisinin sonucu (keyword alanlarıyla) — {@code ok} izlemenin kendi koşul kararı. */
    private static Map<String, Object> clientResult(Map<String, Object> r) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (r == null) {
            c.put("ok", false);
            c.put("http_status", null);
            c.put("response_ms", null);
            c.put("http_version", null);
            c.put("error", "tanılama süre sınırında tamamlanmadı");
            c.put("occurrences", null);
            c.put("failure_reason", null);
            return c;
        }
        boolean hadError = r.get("error") != null;
        boolean ok = !hadError && r.get("failure_reason") == null;
        c.put("ok", ok);
        c.put("http_status", r.get("http_status"));
        c.put("response_ms", r.get("response_ms"));
        c.put("http_version", null);
        c.put("error", r.get("error"));
        c.put("occurrences", r.get("count"));
        c.put("failure_reason", r.get("failure_reason"));
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
            log.debug("Keyword tanılama: görev süre sınırında bitmedi/patladı: {}", e.toString());
            return null;
        }
    }

    // ── Bloklar ─────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> decisionMap(String source, boolean wanted, boolean bypassed) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("source", source);
        d.put("wanted", wanted);
        d.put("bypassed", bypassed);
        return d;
    }

    private static Map<String, Object> monitorMap(KeywordMonitor m, int diagTimeout, int monitorTimeout, String mode,
                                                  Map<String, String> custom, Integer slowMs) {
        Map<String, Object> mon = new LinkedHashMap<>();
        mon.put("id", m.getId());
        mon.put("name", m.getName());
        mon.put("url", m.getUrl());
        mon.put("method", "GET");
        mon.put("keyword", m.getKeyword());
        mon.put("operator", m.getMatchOperator() == null ? "GTE" : m.getMatchOperator());
        mon.put("match_count", m.getMatchCount() != null ? m.getMatchCount() : 1);
        mon.put("case_sensitive", Boolean.TRUE.equals(m.getCaseSensitive()));
        mon.put("timeout_ms", monitorTimeout);
        mon.put("diag_timeout_ms", diagTimeout);
        mon.put("verify_ssl", false);
        mon.put("follow_redirects", true);
        mon.put("proxy_mode", mode);
        Map<String, Object> adv = new LinkedHashMap<>();
        adv.put("custom_headers", custom.size());
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

    /** Pod / düğüm / pod IP — HTTP tanılamasıyla aynı kaynak bilgisi. */
    Map<String, Object> source() {
        Map<String, Object> s = new LinkedHashMap<>();
        String pod = blankToNull(env.apply("POD_NAME"));
        if (pod == null) pod = blankToNull(env.apply("HOSTNAME"));
        if (pod == null) {
            try { pod = InetAddress.getLocalHost().getHostName(); } catch (Exception ignore) { pod = null; }
        }
        s.put("pod", pod);
        s.put("node", blankToNull(env.apply("NODE_NAME")));
        s.put("pod_ip", blankToNull(env.apply("POD_IP")));
        return s;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Metinden süzülecek sır DEĞERLERİ: sır olabilecek özel başlık değerleri (+ "Bearer" jetonu), vekil parolası/jetonu. */
    private List<String> secrets(Map<String, String> custom, String proxyAuth) {
        List<String> s = new ArrayList<>();
        for (Map.Entry<String, String> h : custom.entrySet()) {
            if (HttpDiagnosticsService.secretLike(h.getKey(), h.getValue())) {
                s.add(h.getValue());
                int sp = h.getValue().indexOf(' ');
                if (sp > 0 && sp < h.getValue().length() - 1) s.add(h.getValue().substring(sp + 1).trim());
            }
        }
        if (proxySettings != null && proxySettings.secretValue() != null) s.add(proxySettings.secretValue());
        if (proxyAuth != null && proxyAuth.startsWith("Basic ")) s.add(proxyAuth.substring("Basic ".length()));
        return s;
    }
}
