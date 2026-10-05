package com.sitemonitor.service.page.diagnose;

import com.sitemonitor.service.CaAutoPinService;
import com.sitemonitor.service.ProxyPolicyService;
import com.sitemonitor.service.ProxySettings;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.http.HttpRequestRules;
import com.sitemonitor.service.http.diagnose.HttpDiagFindings;
import com.sitemonitor.service.http.diagnose.HttpDiagMasker;
import com.sitemonitor.service.http.diagnose.HttpDiagnosticsService;
import com.sitemonitor.service.http.diagnose.RawHttpProbe;
import com.sitemonitor.service.page.PageFetchCore;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
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
 * Sayfa Bütünlüğü ve Sayfa Hızı uçtan uca tanılamasının ORTAK iskeleti (2026-10-05) — keyword/HTTP tanılamasının aynası:
 * izlemenin KENDİ yolu (vekil kararı {@link ProxyPolicyService}) ve vekil tanımlıysa ÖTEKİ yol PARALEL denenir; her yolda
 * ana sayfanın ham soket ölçümü ({@link RawHttpProbe}, sayfa çekirdeğinin istek profiliyle) + izlemenin GERÇEK kontrolü
 * ({@code client_check}, kaydetmeden) koşar.
 *
 * <p><b>Mevcudu etkilemez.</b> Hiçbir kontrol satırı yazılmaz ({@code page_checks}, {@code page_resource_issues},
 * {@code pagespeed_checks}), sweep / alarm / teyit / anomali bekçisi çağrılmaz, CA pinlenmez (sayfa çekirdeği güven-hepsi
 * TLS kullanır; ham ölçüm güveni yalnız BİLGİ olarak raporlar). Kalıcı tek iz tanılama geçmişi ve denetim kaydı —
 * ikisi denetleyicide. Süre: yol başına izlemenin zaman aşımı 1–30 sn'ye kısılır, toplam ≤ 60 sn; gerçek kontrolün
 * duvar-saati tavanı {@value #CLIENT_CAP_SECONDS} sn (kısmi sonuç döner, tanı "kısmi" der). İş parçacıkları sanal — sweep
 * havuzu kullanılmaz; eşzamanlılık denetleyicinin hız sınırıyla tavanlı.
 */
@Slf4j
abstract class PageDiagSupport {

    static final long PATH_CAP_MS = 55_000L;
    static final long COLLECT_CAP_MS = 58_000L;
    /** Gerçek kontrolün (client_check) duvar-saati tavanı — toplama son anından ÖNCE bitsin, kısmi sonuç dönsün. */
    static final int CLIENT_CAP_SECONDS = 45;
    /** İssue / kaynak URL'lerinin gösterim tavanı (karakter). */
    static final int URL_MAX = 300;

    protected final SsrfGuard ssrfGuard;
    protected final TrustEvaluator trustEvaluator;
    protected final CaAutoPinService caAutoPin;
    protected final ProxySettings proxySettings;
    protected final ProxyPolicyService proxyPolicy;

    protected final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    protected final ScheduledExecutorService watchdog;

    /** Ortam değişkeni okuyucu — test kaynak bilgisini sabitleyebilsin. */
    UnaryOperator<String> env = System::getenv;

    PageDiagSupport(SsrfGuard ssrfGuard, TrustEvaluator trustEvaluator, CaAutoPinService caAutoPin,
                    ProxySettings proxySettings, ProxyPolicyService proxyPolicy, String watchdogName) {
        this.ssrfGuard = ssrfGuard;
        this.trustEvaluator = trustEvaluator;
        this.caAutoPin = caAutoPin;
        this.proxySettings = proxySettings;
        this.proxyPolicy = proxyPolicy;
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, watchdogName);
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
        watchdog.shutdownNow();
    }

    /** Bir yol: anahtar (monitor | alternate), rota (proxy | direct) ve kararın kaynağı. */
    record Plan(String key, String route, Map<String, Object> decision) {
        boolean viaProxy() { return "proxy".equals(route); }
        boolean isMonitor() { return "monitor".equals(key); }
    }

    boolean proxyConfigured() {
        return proxySettings != null && proxySettings.enabled();
    }

    /** İzlemenin yolu + (vekil tanımlı ve karşılaştırma istendiyse) öteki yol. */
    List<Plan> plans(ProxyPolicyService.Decision d, boolean compare) {
        boolean configured = proxyConfigured();
        boolean monitorViaProxy = d.viaProxy() && configured;
        List<Plan> plans = new ArrayList<>();
        plans.add(new Plan("monitor", monitorViaProxy ? "proxy" : "direct", decisionMap(d.source(), d.wanted(), d.bypassed())));
        if (compare && configured) {
            String alt = monitorViaProxy ? "direct" : "proxy";
            plans.add(new Plan("alternate", alt, decisionMap("compare", "proxy".equals(alt), false)));
        }
        return plans;
    }

    /**
     * Sayfa çekirdeğinin istek profili — {@link PageFetchCore#fetch} ile AYNI: her hop'ta Accept / Accept-Language /
     * Accept-Encoding, en çok {@link PageFetchCore#MAX_REDIRECTS} yönlendirme, https→http takip edilir.
     */
    static RawHttpProbe.Profile profile() {
        Map<String, String> base = new LinkedHashMap<>();
        base.put("Accept", PageFetchCore.ACCEPT);
        base.put("Accept-Language", PageFetchCore.ACCEPT_LANGUAGE);
        base.put("Accept-Encoding", PageFetchCore.ACCEPT_ENCODING);
        return new RawHttpProbe.Profile(base, PageFetchCore.MAX_REDIRECTS, true);
    }

    /** Yolun ham ölçüm tanımı (GET, güven-hepsi TLS — çekirdekle aynı; yönlendirme takibi açık). */
    RawHttpProbe.Spec spec(Plan p, String url, int timeoutMs, com.sitemonitor.service.http.HttpRequestOptions opts,
                           String proxyAuth, long hardDeadline, String userAgent) {
        boolean via = p.viaProxy();
        return new RawHttpProbe.Spec(p.key(), p.route(), url, "GET", null, timeoutMs, false, true, opts,
                via ? proxySettings.host() : null, via ? proxySettings.port() : 0, via ? proxyAuth : null, hardDeadline,
                userAgent, false, profile());
    }

    Future<RawHttpProbe> submitProbe(RawHttpProbe.Spec spec, HttpDiagMasker masker) {
        return workers.submit(() -> new RawHttpProbe(spec, ssrfGuard, trustEvaluator, caAutoPin, masker, watchdog).run());
    }

    static Map<String, Object> decisionMap(String source, boolean wanted, boolean bypassed) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("source", source);
        d.put("wanted", wanted);
        d.put("bypassed", bypassed);
        return d;
    }

    Map<String, Object> proxyMap() {
        boolean configured = proxyConfigured();
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

    static <T> T await(Future<T> f, long deadline) {
        try {
            long rem = Math.max(1L, deadline - System.currentTimeMillis());
            return f.get(rem, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            return null;
        } catch (Exception e) {
            f.cancel(true);
            log.debug("Sayfa tanılama: görev süre sınırında bitmedi/patladı: {}", e.toString());
            return null;
        }
    }

    /**
     * Metinden süzülecek sır DEĞERLERİ: Basic auth parolası + jetonu, sır olabilecek özel başlık değerleri (+ "Bearer"
     * jetonu), vekil parolası + jetonu. HTTP tanılamasıyla aynı kural ({@link HttpDiagnosticsService#secretLike}).
     */
    List<String> secrets(Map<String, String> custom, String basicUser, String basicPass, String proxyAuth) {
        List<String> s = new ArrayList<>();
        if (basicPass != null && !basicPass.isBlank()) s.add(basicPass);
        String basic = HttpRequestRules.basicAuthHeader(basicUser, basicPass);
        if (basic != null) s.add(basic.substring("Basic ".length()));
        if (custom != null) {
            for (Map.Entry<String, String> h : custom.entrySet()) {
                if (HttpDiagnosticsService.secretLike(h.getKey(), h.getValue())) {
                    s.add(h.getValue());
                    int sp = h.getValue().indexOf(' ');
                    if (sp > 0 && sp < h.getValue().length() - 1) s.add(h.getValue().substring(sp + 1).trim());
                }
            }
        }
        if (proxySettings != null && proxySettings.secretValue() != null) s.add(proxySettings.secretValue());
        if (proxyAuth != null && proxyAuth.startsWith("Basic ")) s.add(proxyAuth.substring("Basic ".length()));
        return s;
    }

    /**
     * Yolların karşılaştırması (HTTP/keyword ile aynı kural): izlemenin yolu düşüyor, öteki çalışıyorsa {@code PATH_DIFFERS}
     * ({@code reason} = tür — arayüz varyant metnini seçer); ikisi de yanıtsızsa {@code BOTH_PATHS_FAIL}.
     */
    static Map<String, Object> compare(List<Map<String, Object>> paths, List<String> outcomes, List<Integer> statuses,
                                       List<String> failedSteps, boolean available, String reason,
                                       List<Map<String, Object>> findingsOut) {
        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("available", available);
        boolean differs = false;
        if (available && paths.size() > 1) {
            String mo = outcomes.get(0), ao = outcomes.get(1);
            Integer ms = statuses.get(0), as = statuses.get(1);
            differs = !Objects.equals(mo, ao) || !Objects.equals(ms, as);
            boolean monitorFails = "fail".equals(mo);
            boolean altWorks = !"fail".equals(ao) || (ms == null && as != null);
            if (monitorFails && altWorks) {
                findingsOut.add(HttpDiagFindings.finding(HttpDiagFindings.PATH_DIFFERS, HttpDiagFindings.FAIL, "monitor",
                        RawHttpProbe.params("failing_route", paths.get(0).get("route"),
                                "working_route", paths.get(1).get("route"), "working_status", as, "reason", reason)));
            } else if (monitorFails && "fail".equals(ao) && ms == null && as == null) {
                findingsOut.add(HttpDiagFindings.finding(HttpDiagFindings.BOTH_PATHS_FAIL, HttpDiagFindings.FAIL, null,
                        RawHttpProbe.params("failed_step", failedSteps.get(0))));
            }
        }
        comparison.put("differs", differs);
        return comparison;
    }

    /** Yolun ham ölçüm sonucunun ortak kısmı (HTTP sözleşmesiyle aynı alanlar). */
    static Map<String, Object> basePath(Plan plan, RawHttpProbe probe, String outcome, String failedStep,
                                        Map<String, Object> client) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("key", plan.key());
        p.put("route", plan.route());
        p.put("decision", plan.decision());
        p.put("outcome", outcome);
        p.put("http_status", probe.finalStatus());
        p.put("total_ms", probe.totalMs());
        p.put("failed_step", failedStep);
        p.put("error", probe.failure() != null ? HttpDiagnosticsService.errorMap(probe.failure().error()) : null);
        p.put("timeline", probe.timeline());
        p.put("hops", probe.hops());
        p.put("transcript", probe.transcript());
        p.put("client_check", client);
        return p;
    }

    /** Son yanıtın gövde bayt sayısı (teldeki — sıkıştırılmış olabilir); yanıt yoksa null. */
    @SuppressWarnings("unchecked")
    static Long lastBodyBytes(RawHttpProbe probe) {
        List<Map<String, Object>> hops = probe.hops();
        for (int i = hops.size() - 1; i >= 0; i--) {
            if (hops.get(i).get("response") instanceof Map<?, ?> r) {
                if (((Map<String, Object>) r).get("body") instanceof Map<?, ?> b && b.get("bytes") instanceof Number n) {
                    return n.longValue();
                }
                return null;
            }
        }
        return null;
    }

    /** Gösterilecek URL: sorgu sırları maskeli, uzunluk tavanlı. */
    static String displayUrl(String url) {
        if (url == null) return null;
        String u = SecretMask.maskUrlQuery(url);
        return u.length() <= URL_MAX ? u : u.substring(0, URL_MAX - 1) + "…";
    }

    static String abbreviate(String s, int max) {
        if (s == null) return null;
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    /** Ham ölçümün yol sonucu: düşen adım + bulgu; yanıt yoksa {@code RESPONSE_TIMEOUT}. Başarılıysa null döner. */
    static RawVerdict rawVerdict(RawHttpProbe probe, RawHttpProbe.Spec spec, String statusCode, String bodyCode) {
        String key = spec.key();
        RawHttpProbe.Failure failure = probe.failure();
        if (failure != null) {
            return new RawVerdict(failure.step(), HttpDiagFindings.finding(failure.code(), HttpDiagFindings.FAIL, key, failure.params()));
        }
        Integer st = probe.finalStatus();
        if (st == null) {
            return new RawVerdict("response", HttpDiagFindings.finding(HttpDiagFindings.RESPONSE_TIMEOUT, HttpDiagFindings.FAIL, key,
                    RawHttpProbe.params("ms", spec.timeoutMs(), "route", spec.route())));
        }
        // Çekirdek ≥ 400'ü ve takip edilemeyen yönlendirmeyi (gövdesiz 3xx) "sayfa alınamadı" sayar.
        if (st >= 300) {
            return new RawVerdict("response", HttpDiagFindings.finding(statusCode, HttpDiagFindings.FAIL, key,
                    RawHttpProbe.params("status", st, "route", spec.route())));
        }
        // Çekirdek HTML'i süreyle okur; bitmezse sayfa ayrıştırılamaz (izleme DOWN yazar) — HTTP'den farklı olarak fail.
        if (probe.bodyTimedOut()) {
            return new RawVerdict("body", HttpDiagFindings.finding(bodyCode, HttpDiagFindings.FAIL, key,
                    RawHttpProbe.params("ms", spec.timeoutMs(), "status", st)));
        }
        return null;
    }

    /** Ham ölçümün düşüşü: adım + bulgu. */
    record RawVerdict(String failedStep, Map<String, Object> finding) {}
}
