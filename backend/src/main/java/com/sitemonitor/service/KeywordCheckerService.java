package com.sitemonitor.service;

import com.sitemonitor.service.http.HttpRequestOptions;
import com.sitemonitor.service.http.HttpRequestRules;
import com.sitemonitor.service.keyword.KeywordBodyAnalyzer;
import com.sitemonitor.service.keyword.KeywordCheckSpec;
import com.sitemonitor.service.keyword.KeywordFailureClassifier;
import com.sitemonitor.service.keyword.KeywordFold;
import com.sitemonitor.service.keyword.KeywordRequestRules;
import com.sitemonitor.service.keyword.KeywordText;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keyword monitor checker — bir URL'nin HTTP yanıt gövdesini çekip içinde anahtar kelimeyi arar ve kontrolün KARARINI verir
 * (kw sözleşmesi §2, 2026-11-05: {@code ok} artık burada — durum kodu kuralı, son URL kuralı ve adet koşulu birlikte).
 *
 * <p>Akış ({@link #check(KeywordCheckSpec)}):
 * <ul>
 *   <li><b>fetch</b>: yöntem (GET / POST), gövde + içerik türü, Basic auth, User-Agent kipi, özel başlıklar — eklentiler YALNIZ
 *       ilk host'a; yönlendirme elle takip (≤ 5 hop, her hop SSRF) ya da takip kapalı; hop başına izlemenin zaman aşımı (≤ 60 sn),
 *       TÜM kontrol ≤ {@value KeywordRequestRules#TOTAL_BUDGET_MS} ms; gövde ≤ 2 MB.</li>
 *   <li><b>decode</b>: {@link KeywordText#decode} — sayfanın bildirdiği karakter kümesi (eskiden her zaman UTF-8).</li>
 *   <li><b>view</b>: eşleşme kapsamı RAW (ham, isteğe bağlı normalleştirme) ya da VISIBLE (görünür metin).</li>
 *   <li><b>verdict</b>: kabul edilen durum ({@code status_accepted}) · son URL ({@code final_url_ok}) · kural adımı
 *       ({@link #evaluateRules}) — Türkçe harf katlamalı tek kelime sayımı ({@link KeywordFold}).</li>
 *   <li><b>teşhis</b>: {@code ok=false} ve teşhis açıksa neden + ipuçları + maskeli alıntı (ek istek ATILMAZ).</li>
 * </ul>
 * Eski anahtarlar ve anlamları AYNEN (found, count, http_status, response_ms, snippet, error, config_error, via, final_url,
 * redirect_count, content_type, body_bytes, body_truncated, charset, failure_reason, failure_detail, hints, excerpt).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordCheckerService {

    /** Yanıt gövdesi okuma tavanı (OOM koruması) — keyword aramaya fazlasıyla yeter. */
    private static final int MAX_BODY_BYTES = 2_000_000;

    private final SsrfGuard ssrfGuard;
    private HttpClient httpClient;
    // Vekilli eş (2026-09-21) — HttpCheckerService ile aynı desen; alan enjeksiyonu (yapıcı testlerde elle kuruluyor).
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProxySettings proxySettings;
    private HttpClient proxiedClient;

    /** İç-CA / self-signed HTTPS sitelerini de izleyebilmek için trust-all
     *  (içerik kontrolü; sertifika geçerliliği ayrı cert checker'da izlenir). */
    @PostConstruct
    public void init() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Yönlendirmeler MANUEL takip edilir → her hop SsrfGuard'dan geçsin. Redirect.NORMAL
                // zinciri kütüphane içinde takip ediyordu: ilk host doğrulansa bile hedef sunucu bizi
                // 302 ile iç ağa/metadata ucuna yönlendirebiliyordu ve gövdeden alınan snippet
                // KULLANICIYA dönüyordu. Desen PageFetchCore'dan (bkz. SafeRedirect).
                .followRedirects(HttpClient.Redirect.NEVER);
        try {
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            b.sslContext(ssl);
        } catch (Exception e) {
            log.warn("Keyword checker trust-all SSL kurulamadı, varsayılan kullanılacak: {}", e.getMessage());
        }
        httpClient = b.build();
        if (proxySettings != null && proxySettings.enabled()) {
            java.net.Authenticator auth = proxySettings.authenticator(log, "Keyword checker");
            HttpClient.Builder pb = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER).proxy(proxySettings.proxySelector());
            try { pb.sslContext(httpClient.sslContext()); } catch (Exception ignore) { /* varsayılan güven */ }
            if (auth != null) pb.authenticator(auth);
            proxiedClient = pb.build();
        }
    }

    /** viaProxy: vekilli istemci (yapılandırılmamışsa doğrudan). */
    private HttpClient clientFor(boolean viaProxy) {
        return viaProxy && proxiedClient != null ? proxiedClient : httpClient;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Geriye uyum girişleri (test derlemesi + eski çağıranlar) — hepsi {@link #check(KeywordCheckSpec)}'e iner
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /** Geriye uyum: özel header'sız, case-insensitive. */
    public Map<String, Object> check(String url, String keyword, int timeoutMs) {
        return check(url, keyword, timeoutMs, null, false);
    }

    /** Geriye uyum: case-insensitive. */
    public Map<String, Object> check(String url, String keyword, int timeoutMs, String customHeaders) {
        return check(url, keyword, timeoutMs, customHeaders, false);
    }

    /** Geriye uyum: doğrudan yol. */
    public Map<String, Object> check(String url, String keyword, int timeoutMs, String customHeaders, boolean caseSensitive) {
        return check(url, keyword, timeoutMs, customHeaders, caseSensitive, false);
    }

    /** @param viaProxy kurumsal vekil üzerinden (karar {@link ProxyPolicyService}); sonuçta {@code via} proxy|direct. */
    public Map<String, Object> check(String url, String keyword, int timeoutMs, String customHeaders, boolean caseSensitive, boolean viaProxy) {
        return check(url, keyword, timeoutMs, customHeaders, caseSensitive, viaProxy, null);
    }

    /**
     * İzlemenin adet koşulu (operatör + eşik) — verilirse koşul SAĞLANMADIĞINDA nedeni ve "neden bulunamadı" ipuçlarını da
     * üretir (2026-10-04). Verilmezse (eski çağıran) karar varsayılan "en az 1 kez" ile verilir, teşhis üretilmez.
     */
    public record Expectation(String operator, int threshold) {
        public static Expectation of(com.sitemonitor.model.KeywordMonitor m) {
            return new Expectation(m.getMatchOperator(), m.getMatchCount() != null ? m.getMatchCount() : 1);
        }
    }

    /** Eski 7 argümanlı giriş — ince sarmalayıcı (kw sözleşmesi §13). */
    public Map<String, Object> check(String url, String keyword, int timeoutMs, String customHeaders, boolean caseSensitive,
                                     boolean viaProxy, Expectation expectation) {
        KeywordCheckSpec.Builder b = KeywordCheckSpec.builder(url, keyword)
                .timeoutMs(timeoutMs).customHeaders(customHeaders).caseSensitive(caseSensitive).viaProxy(viaProxy)
                .diagnose(expectation != null);
        if (expectation != null) b.condition(expectation.operator(), expectation.threshold());
        return check(b.build());
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  Ana giriş
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Zenginleştirilmiş kontrol (kw sözleşmesi §10.6). Eski anahtarlar AYNEN; EK anahtarlar: {@code ok} (karar), {@code method},
     * {@code decoded_charset} (çözmede kullanılan küme — {@code charset} bildirileni tutar), {@code match_scope}
     * ({@code RAW|VISIBLE}), {@code status_accepted} (kabul edilen durum kuralı: null = kapalı), {@code final_url_ok} (son URL
     * kuralı: null = yok).
     */
    public Map<String, Object> check(KeywordCheckSpec spec) {
        return run(spec, null);
    }

    /** Sonuç haritası + kural / boyut kararı (içerik görünümü dahil; istek tamamlanmadıysa {@code verdict == null}). */
    public record Detailed(Map<String, Object> result, Verdict verdict) {}

    /**
     * {@link #check(KeywordCheckSpec)} + kararın kendisi — canlı temel ("Yeni temel al") ve canlı eşleşme bağlamları (kw sözleşmesi
     * §10.3) aynı ölçümün içerik görünümünü kullanır (ikinci istek YOK).
     */
    public Detailed checkDetailed(KeywordCheckSpec spec) {
        Verdict[] holder = new Verdict[1];
        Map<String, Object> r = run(spec, holder);
        return new Detailed(r, holder[0]);
    }

    private Map<String, Object> run(KeywordCheckSpec spec, Verdict[] verdictOut) {
        long start = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        boolean proxied = spec.viaProxy() && proxiedClient != null;
        String url = spec.url();
        result.put("via", proxied ? "proxy" : "direct");
        result.put("method", spec.method());
        result.put("match_scope", spec.matchScope());
        int timeoutMs = spec.effectiveTimeoutMs();
        // Yapılandırma hatası (şemasız/host'suz URL) kesinti DEĞİL — istek atılmaz, alarm da açılmaz
        // (SchedulerService config_error bayrağını okur). Eskiden bu durum sahte DOWN alarmı üretiyordu.
        if (!com.sitemonitor.util.MonitorUrls.isCheckable(url)) {
            result.put("found", false);
            result.put("count", 0);
            result.put("ok", false);
            result.put("config_error", true);
            result.put("error", com.sitemonitor.util.MonitorUrls.CONFIG_ERROR_MSG);
            result.put("failure_reason", KeywordFailureClassifier.CONFIG_ERROR);
            result.put("failure_detail", "İzlemenin URL'si geçersiz (şema ya da host yok); istek gönderilmedi.");
            return result;
        }
        long deadline = start + KeywordRequestRules.TOTAL_BUDGET_MS;
        Trail trail = new Trail();
        try {
            // SSRF: hedef host HER hop'ta doğrulanır (metadata/loopback/link-local blok; iç ağ ayara bağlı).
            HttpResponse<InputStream> resp = sendFollowingSafely(applyTimestamp(url), spec, timeoutMs, deadline, trail);
            byte[] bytes;
            boolean truncated = false;
            long ms;
            // Bellek koruması (gövde tavanı) + SÜRE koruması (prod kapısı 2026-09-25, N1): readNBytes EOF ya da
            // tavan gelene dek bloklar; kalp atışı gönderen bir SSE ucunda bu günler sürer ve keyword sweep'i
            // donardı. Gövde, izlemenin zaman aşımı kadar (toplam bütçeden kalanla sınırlı) daha beklenir.
            try (InputStream is = com.sitemonitor.util.HttpBodies.withDeadline(
                    resp.body(), remainingFor(timeoutMs, deadline), "Keyword")) {
                bytes = is.readNBytes(MAX_BODY_BYTES);
                ms = System.currentTimeMillis() - start;   // süre ölçümü yoklamadan ÖNCE (yavaşlık kararı değişmesin)
                if (bytes.length >= MAX_BODY_BYTES) {
                    // Tavan doldu: devamı var mı? Tek bayt yoklaması — sonuç (found/count) yine YALNIZ ilk MAX_BODY_BYTES'tan.
                    // Yoklama süre sınırına takılırsa ya da hata verirse "devamı vardı" sayılır; kontrolün kendisi DÜŞMEZ.
                    try { truncated = is.read() != -1; } catch (Exception probe) { truncated = true; }
                }
            }
            String contentType = resp.headers().firstValue("content-type").orElse(null);
            // ── Çözme + görünüm + karar (kw sözleşmesi §4 / §5.2 / §5.3 / §6 / §7) ──
            KeywordText.Decoded decoded = KeywordText.decode(bytes, contentType);
            int status = resp.statusCode();
            Boolean statusAccepted = spec.expectedStatus() == null ? null
                    : HttpCheckerService.matchesStatus(status, spec.expectedStatus());
            Boolean finalUrlOk = spec.expectedFinalUrl() == null ? null
                    : KeywordRequestRules.finalUrlMatches(resp.uri(), spec.expectedFinalUrl(), url);
            Verdict v = evaluateRules(spec, decoded.text(), contentType, bytes.length, truncated, statusAccepted, finalUrlOk);
            if (verdictOut != null) verdictOut[0] = v;
            int count = v.count();
            boolean ok = v.ok();
            // eski tüketiciler (found / count / occurrences / snippet) = kural #1 (§10.6) — eski izlemede bugünküyle AYNI değerler
            result.put("found", count > 0);
            result.put("count", count);
            result.put("http_status", status);
            result.put("response_ms", ms);
            if (v.snippet() != null) result.put("snippet", v.snippet());
            // ── Yanıt meta verisi (2026-10-04) — her yanıtta, ucuz ──
            String finalUrl = KeywordBodyAnalyzer.displayUrl(resp.uri());
            result.put("final_url", finalUrl);
            result.put("redirect_count", trail.redirects);
            result.put("content_type", contentType == null ? null
                    : (contentType.length() > 200 ? contentType.substring(0, 200) : contentType));
            result.put("body_bytes", (long) bytes.length);
            result.put("body_truncated", truncated);
            result.put("charset", KeywordBodyAnalyzer.declaredCharset(contentType, bytes));
            result.put("decoded_charset", decoded.charset());
            result.put("status_accepted", statusAccepted);
            result.put("final_url_ok", finalUrlOk);
            result.put("ok", ok);
            putRuleOutcome(result, spec, v);
            putChange(result, spec, v, status, bytes.length, statusAccepted, finalUrlOk);
            // ── Sağlıksızsa: neden + ipuçları + alıntı (yalnız teşhis açıkken; gövde zaten bellekte) ──
            if (!ok && spec.diagnose()) {
                var reason = KeywordFailureClassifier.forCheck(new KeywordFailureClassifier.Facts(status, count,
                        spec.operator(), spec.threshold(), bytes.length, truncated, spec.keyword(), statusAccepted,
                        spec.expectedStatus(), finalUrlOk, spec.expectedFinalUrl(), finalUrl, spec.followRedirects()),
                        v.ruleFacts());
                result.put("failure_reason", reason.code());
                result.put("failure_detail", reason.detail());
                var analysis = KeywordBodyAnalyzer.analyze(new KeywordBodyAnalyzer.Input(bytes, contentType, spec.keyword(),
                        spec.caseSensitive(), count, spec.operator(), spec.threshold(), status, url, resp.uri(),
                        trail.redirects, secretValues(spec), decoded.text(), decoded.charset(), spec.matchScope(),
                        spec.normalizeWs()));
                if (!analysis.hints().isEmpty()) result.put("hints", analysis.hints());
                if (analysis.excerpt() != null) result.put("excerpt", analysis.excerpt());
            }
        } catch (SsrfGuard.BlockedException be) {
            // Politika reddi — dış istek HİÇ atılmadı. response_ms yazılmaz: ölçülen bir yanıt yok.
            result.put("found", false);
            result.put("count", 0);
            result.put("ok", false);
            result.put("error", be.getMessage());
            putFailure(result, be, trail, timeoutMs, proxied);
            if (spec.changeDetect()) result.put("change_skipped", CHANGE_SKIP_INCOMPLETE);
        } catch (Exception e) {
            result.put("found", false);
            result.put("count", 0);
            result.put("ok", false);
            result.put("response_ms", System.currentTimeMillis() - start);
            result.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            putFailure(result, e, trail, timeoutMs, proxied);
            if (spec.changeDetect() && !result.containsKey("content_changed")) result.put("change_skipped", CHANGE_SKIP_INCOMPLETE);
            log.debug("Keyword check failed for {}: {}", url, e.getMessage());
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════════════════════════
    //  kw-be2 Aşama 2 — kural adımı (çoklu kural / REGEX / kapsam / iki eşik) + boyut + içerik değişimi
    // ═══════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Kural / boyut kararı (kontrol, tanılama ve canlı temel ORTAK — saf).
     *
     * @param content     tek kontrolün içerik görünümü (CSS / JSON en çok bir kez ayrıştırılır; değişim satırları da buradan)
     * @param evaluation  kural değerlendirmesi
     * @param size        boyut kuralı sonucu ({@code sizeOk == null} = kapalı)
     * @param rulesMet    kural birleşimi sağlandı mı
     * @param ok          kontrolün kararı: kurallar ∧ boyut ∧ kabul edilen durum ∧ son URL
     * @param level       sağlıksızsa alarm seviyesi — yalnız kurallar yüzündense kuralların birleşik seviyesi, değilse izlemenin
     *                    seviyesi; sağlıklıysa {@code null}
     * @param breachLevel {@code breach_level}: seviye bir kuralın KENDİ eşiğinden geldiyse (WARNING / CRITICAL), değilse null
     * @param count       kural #1'in sayımı (eski {@code found} / {@code count} / {@code occurrences})
     * @param snippet     kural #1'in ilk eşleşmesinden alıntı (yoksa null)
     */
    public record Verdict(com.sitemonitor.service.keyword.rules.KeywordContent content,
                          com.sitemonitor.service.keyword.rules.KeywordRuleEvaluator.Evaluation evaluation,
                          com.sitemonitor.service.keyword.content.KeywordSizeCheck.Result size,
                          boolean rulesMet, boolean ok, String level, String breachLevel, int count, String snippet) {

        /** Sınıflandırıcıya giden kural / boyut gözlemleri. */
        public KeywordFailureClassifier.RuleFacts ruleFacts() {
            var ev = evaluation;
            return new KeywordFailureClassifier.RuleFacts(rulesMet, ev != null && ev.multi(), ev == null ? null : ev.summary(),
                    ev == null ? null : ev.scopeNote(), size == null ? null : size.sizeOk(),
                    size == null ? null : size.reason(), size == null ? null : size.detail());
        }
    }

    /**
     * Kural adımı (kw sözleşmesi §6.2 / §7.1): etkin kurallar ({@code rules_json} ya da eski tek kelime alanları) içerik
     * görünümünde değerlendirilir, boyut kuralı uygulanır, kontrolün kararı verilir. Eski tek kelime izlemesinde sayım, alıntı ve
     * karar bugünküyle AYNIDIR (aynı görünüm / iğne / katlama — {@link KeywordText#view} / {@link KeywordText#needle}).
     */
    public static Verdict evaluateRules(KeywordCheckSpec spec, String decodedText, String contentType, long bodyBytes,
                                        boolean truncated, Boolean statusAccepted, Boolean finalUrlOk) {
        String scope = spec.matchScope();
        boolean visible = KeywordText.SCOPE_VISIBLE.equals(scope);
        var content = com.sitemonitor.service.keyword.rules.KeywordContent.builder(decodedText == null ? "" : decodedText)
                .contentType(contentType).bytes(bodyBytes).truncated(truncated)
                .visibleScope(visible).normalizeWs(spec.normalizeWs())
                .visibleText(t -> KeywordText.view(t, KeywordText.SCOPE_VISIBLE, false))
                .normalizer(KeywordText::normalize)
                .build();
        java.util.List<com.sitemonitor.service.keyword.rules.KeywordRule> rules = spec.rules();
        com.sitemonitor.service.keyword.rules.KeywordRuleEvaluator.Evaluation ev;
        boolean rulesMet;
        int count;
        String snippet = null;
        if (rules.isEmpty()) {
            // Kelimesiz eski kayıt (olağan dışı): bugünkü gibi sayım 0, adet koşulu uygulanır
            ev = null;
            count = 0;
            rulesMet = evaluate(0, spec.operator(), spec.threshold());
        } else {
            ev = com.sitemonitor.service.keyword.rules.KeywordRuleEvaluator.evaluate(rules, spec.ruleLogic(), content,
                    spec.monitorLevel());
            rulesMet = !ev.breached();
            var p = ev.primary();
            count = p == null ? 0 : p.count();
            if (p != null && p.count() > 0 && p.matchStart() >= 0) {
                String text = content.scope(rules.get(0)).text();
                // kw son inceleme D-1: alıntı izlemenin sırlarıyla (başlık değerleri, parola, base64 kimlik) ve hassas alan
                // adlarıyla maskelenir — liste satırına, kontrol kaydına ve alarm bağlamına maskesiz sayfa metni gitmez
                snippet = snippet(text, p.matchStart(), Math.max(0, p.matchEnd() - p.matchStart()), secretValues(spec));
            }
        }
        var size = com.sitemonitor.service.keyword.content.KeywordSizeCheck.evaluate(spec.minBytes(), spec.maxBytes(), bodyBytes,
                truncated, true);
        boolean sizeOk = !Boolean.FALSE.equals(size.sizeOk());
        boolean ok = rulesMet && sizeOk && !Boolean.FALSE.equals(statusAccepted) && !Boolean.FALSE.equals(finalUrlOk);
        String level = null;
        String breachLevel = null;
        if (!ok) {
            boolean rulesOnly = !rulesMet && sizeOk && !Boolean.FALSE.equals(statusAccepted) && !Boolean.FALSE.equals(finalUrlOk);
            if (rulesOnly && ev != null && ev.level() != null) {
                level = ev.level();
                breachLevel = ev.breachLevel();
            } else {
                level = com.sitemonitor.service.keyword.rules.KeywordRuleEvaluator.normalizeLevel(spec.monitorLevel());
            }
        }
        return new Verdict(content, ev, size, rulesMet, ok, level, breachLevel, count, snippet);
    }

    /** Sonuç anahtarları (§10.6): {@code level}, {@code breach_level}, {@code rule_results}, {@code rules_summary}, {@code size_ok}. */
    private static void putRuleOutcome(Map<String, Object> result, KeywordCheckSpec spec, Verdict v) {
        if (v.level() != null) result.put("level", v.level());
        if (v.breachLevel() != null) result.put("breach_level", v.breachLevel());
        if (v.evaluation() != null) {
            result.put("rule_results", v.evaluation().resultsApi());
            if (v.evaluation().summary() != null && !v.rulesMet()) result.put("rules_summary", v.evaluation().summary());
        }
        if (v.size() != null && v.size().sizeOk() != null) result.put("size_ok", v.size().sizeOk());
    }

    // ── İçerik değişimi notları (kw son inceleme O-3 / D-7) — {@code change_skipped} değerleri, KALICI ──
    /** Değişim ölçülmedi: HTTP durumu ≥ 400 (hata sayfası temel olmaz, değişim sayılmaz). */
    public static final String CHANGE_SKIP_HTTP_ERROR = "HTTP_ERROR";
    /** Değişim ölçülmedi: gövde boş. */
    public static final String CHANGE_SKIP_EMPTY_BODY = "EMPTY_BODY";
    /** Değişim ölçülmedi: istek tamamlanmadı (bağlantı / zaman aşımı / engel). */
    public static final String CHANGE_SKIP_INCOMPLETE = "INCOMPLETE";
    /** Değişim ölçülmedi: kabul edilen durum kodu ya da son URL kuralı reddetti. */
    public static final String CHANGE_SKIP_RULE_REJECTED = "RULE_REJECTED";
    /** Değişim ölçülmedi: değişim seçicisi sayfada metinli bir öğe seçmedi (temel alınmaz, karşılaştırılmaz). */
    public static final String CHANGE_SKIP_SELECTOR_NOT_FOUND = "SELECTOR_NOT_FOUND";
    /** Bütün notlar (sözleşme / ön yüz eşlemesi). */
    public static final java.util.List<String> CHANGE_SKIP_CODES = java.util.List.of(CHANGE_SKIP_HTTP_ERROR,
            CHANGE_SKIP_EMPTY_BODY, CHANGE_SKIP_INCOMPLETE, CHANGE_SKIP_RULE_REJECTED, CHANGE_SKIP_SELECTOR_NOT_FOUND);

    /**
     * İçerik değişimi (kw sözleşmesi §7.2; kw son inceleme O-3) — yalnız değişim açık ∧ istek tamamlandı ∧ HTTP durumu &lt; 400 ∧
     * gövde boş değil ∧ kabul edilen durum / son URL kuralı reddetmedi ∧ (seçici varsa) seçici metinli bir öğe seçti. Aksi hâlde
     * {@code change_skipped} = neden ({@link #CHANGE_SKIP_CODES}): değerlendirme YOK — alarm açılmaz, kapanmaz (açık KEYWORD_CHANGED
     * olduğu gibi kalır), temel alınmaz. Temel yok / başka ayarla alınmış → {@code baseline_missing} (anlık görüntü çağırana
     * {@link KeywordCheckSpec.ChangeCapture}); temel var → {@code content_changed} (+ değişimde {@code change_summary}).
     */
    private static void putChange(Map<String, Object> result, KeywordCheckSpec spec, Verdict v, int status, long bodyBytes,
                                  Boolean statusAccepted, Boolean finalUrlOk) {
        if (!spec.changeDetect()) return;
        String skip = Boolean.FALSE.equals(statusAccepted) || Boolean.FALSE.equals(finalUrlOk) ? CHANGE_SKIP_RULE_REJECTED
                : status >= 400 ? CHANGE_SKIP_HTTP_ERROR
                : bodyBytes <= 0 ? CHANGE_SKIP_EMPTY_BODY : null;
        if (skip != null) {
            result.put("change_skipped", skip);
            return;
        }
        try {
            if (spec.changeSelector() != null && v.content().cssLines(spec.changeSelector()).isEmpty()) {
                result.put("change_skipped", CHANGE_SKIP_SELECTOR_NOT_FOUND);
                return;
            }
            var snap = changeSnapshot(spec, v.content());
            String specHash = com.sitemonitor.service.keyword.content.KeywordChangeDetector.specHash(spec.changeMask(),
                    spec.changeSelector());
            result.put("content_fingerprint", snap.fingerprint());
            var base = spec.baseline();
            var cmp = com.sitemonitor.service.keyword.content.KeywordChangeDetector.compare(base, snap, specHash);
            if (cmp.baselineMissing()) {
                result.put("baseline_missing", true);
                if (spec.changeCapture() != null) spec.changeCapture().put(snap, bodyBytes, specHash);
                return;
            }
            result.put("content_changed", cmp.changed());
            if (base.takenAt() != null) result.put("baseline_taken_at", base.takenAt());
            if (base.source() != null) result.put("baseline_source", base.source());
            if (Boolean.TRUE.equals(cmp.changed()) && cmp.summary() != null) result.put("change_summary", cmp.summary().toMap());
        } catch (RuntimeException e) {
            // Değişim ölçümü kontrolün kararını ASLA bozmaz (değerlendirilmedi = kalem yok)
            log.debug("Keyword içerik değişimi ölçülemedi: {}", e.getMessage());
        }
    }

    /** Değişimin parmak izi alınan anlık görüntüsü: seçici yoksa görünür metin satırları, varsa seçilen öğelerin metni. */
    public static com.sitemonitor.service.keyword.content.KeywordChangeDetector.Snapshot changeSnapshot(
            KeywordCheckSpec spec, com.sitemonitor.service.keyword.rules.KeywordContent content) {
        java.util.List<String> lines;
        String sel = spec.changeSelector();
        if (sel == null) {
            String text = KeywordBodyAnalyzer.visibleLines(content.raw(), KeywordText.VISIBLE_CAP, KeywordText.VISIBLE_CAP);
            lines = text.isEmpty() ? java.util.List.of() : java.util.Arrays.asList(text.split("\n", -1));
        } else {
            lines = content.cssLines(sel);
        }
        return com.sitemonitor.service.keyword.content.KeywordChangeDetector.snapshot(lines,
                com.sitemonitor.service.keyword.content.ContentMasker.parseStored(spec.changeMask()), secretValues(spec));
    }

    /** Hop / gövde okuması için süre: izlemenin zaman aşımı, toplam bütçeden kalanla sınırlı (en az 1 ms). */
    private static int remainingFor(int timeoutMs, long deadline) throws HttpTimeoutException {
        long rem = deadline - System.currentTimeMillis();
        if (rem <= 0) {
            throw new HttpTimeoutException("anahtar kelime kontrolünün toplam süresi ("
                    + (KeywordRequestRules.TOTAL_BUDGET_MS / 1000) + " sn) doldu");
        }
        return (int) Math.max(1, Math.min(timeoutMs, rem));
    }

    /**
     * Eşleşmenin çevresinden (±50 karakter) okunur alıntı — konum aranan metin ({@code text}, kapsamın görünümü) üzerinden.
     * {@link KeywordFold} uzunluğu koruduğu için katlanmış metindeki konum özgün metindeki konumdur (eski U+0130 dönüşümü —
     * CHECKS-3 — artık gerekmez). Boşluk indirgenir, ≤ 200 karakter; alıntı hiçbir koşulda kontrolü düşürmez.
     */
    static String snippet(String text, int idx, int len) {
        return snippet(text, idx, len, java.util.List.of());
    }

    /**
     * Maskeli alıntı (kw son inceleme D-1): pencere kenarındaki sır tam kapsanır, maske birleşik pencereye uygulanır
     * ({@link com.sitemonitor.service.keyword.KeywordSecretWindow}), sonra boşluk indirgenir ve ≤ 200 karakter kesilir.
     * Sır listesi boşken de hassas alan adları ({@code "password":"…"}) maskelenir.
     */
    static String snippet(String text, int idx, int len, java.util.Collection<String> secrets) {
        if (text == null || idx < 0 || idx > text.length()) return "";
        int start = idx;
        int end = Math.min(text.length(), idx + Math.max(0, len));
        int s = Math.max(0, start - 50);
        int e = Math.min(text.length(), Math.max(end, start) + 50);
        if (s > e) s = e;
        int[] w = com.sitemonitor.service.keyword.KeywordSecretWindow.expand(text, s, e, secrets);
        String snip = KeywordBodyAnalyzer.collapseWs(
                com.sitemonitor.service.keyword.KeywordSecretWindow.maskText(text.substring(w[0], w[1]), secrets));
        return snip.length() > 200 ? snip.substring(0, 200) : snip;
    }

    /** İstek tamamlanmadı → neden + ayrıntı + takıldığı URL (yönlendirme ortasında düştüyse o hop'un adresi). */
    private static void putFailure(Map<String, Object> result, Throwable e, Trail trail, int timeoutMs, boolean proxied) {
        try {
            var reason = KeywordFailureClassifier.forException(
                    e, trail.current == null ? null : trail.current.getHost(), Math.max(1000, timeoutMs), proxied);
            result.put("failure_reason", reason.code());
            result.put("failure_detail", reason.detail());
            if (trail.current != null) result.put("final_url", KeywordBodyAnalyzer.displayUrl(trail.current));
            result.put("redirect_count", trail.redirects);
        } catch (RuntimeException ignore) {
            // Teşhis kontrolün kendisini ASLA bozmaz: neden yazılamazsa eski hata metni yine kayıtta.
        }
    }

    /** Yönlendirme izi: şu anki (son denenen) URI + izlenen yönlendirme sayısı. */
    static final class Trail {
        URI current;
        int redirects;
    }

    /** Alıntıdan süzülecek sır değerleri: özel başlıklardan sır OLABİLECEK değerler + Basic auth parolası. */
    public static java.util.List<String> secretValues(KeywordCheckSpec spec) {
        java.util.List<String> out = secretValues(spec.customHeaders());
        if (spec.basicAuthPass() != null && !spec.basicAuthPass().isBlank()) {
            out.add(spec.basicAuthPass());
            // Kimliğin kodlanmış hâli de: Authorization başlığını gövdede yansıtan sayfa alıntıya base64 sızdırmasın.
            String user = spec.basicAuthUser() == null ? "" : spec.basicAuthUser();
            out.add(java.util.Base64.getEncoder().encodeToString(
                    (user + ":" + spec.basicAuthPass()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return out;
    }

    /** Alıntıdan süzülecek sır değerleri: izlemenin özel başlıklarından sır OLABİLECEK değerler (MIME türü vb. hariç). */
    static java.util.List<String> secretValues(String customHeaders) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (customHeaders == null || customHeaders.isBlank()) return out;
        for (String line : customHeaders.split("\\r?\\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String name = line.substring(0, c).trim();
            String value = line.substring(c + 1).trim();
            if (com.sitemonitor.service.http.diagnose.HttpDiagnosticsService.secretLike(name, value)) {
                out.add(value);
                int sp = value.indexOf(' ');   // "Bearer <jeton>" → jetonun kendisi de
                if (sp > 0 && sp < value.length() - 1) out.add(value.substring(sp + 1).trim());
            }
        }
        return out;
    }

    /**
     * İsteği gönderir; yönlendirmeleri MANUEL takip eder ve HER hop'ta {@link SsrfGuard}'ı çalıştırır.
     *
     * <p>Bu uç, eşleşen metnin çevresinden 200 karakterlik bir {@code snippet} DÖNDÜRÜYOR; yani
     * takip edilen son hop'un gövdesi kullanıcıya ulaşıyor. Otomatik takipte (Redirect.NORMAL)
     * yalnız ilk host doğrulanıyordu ve keyword izlemesi sıradan kullanıcı yetkisiyle
     * tanımlanabildiği için bu gerçek bir veri sızıntısı yüzeyiydi.
     *
     * <p>Takip edilemeyen bir {@code Location} (http/https dışı şema, host'suz hedef) hata değildir:
     * 3xx yanıt OLDUĞU GİBİ döner ve gövdesi TÜKETİLMEZ — çağıran okuyacaktır. Takip kapalıysa
     * ({@code follow_redirects=false}) ilk yanıt 3xx olsa da döner.
     */
    private HttpResponse<InputStream> sendFollowingSafely(String url, KeywordCheckSpec spec, int timeoutMs, long deadline,
                                                          Trail trail)
            throws java.io.IOException, InterruptedException {
        URI current = URI.create(url);
        trail.current = current;
        // Özel başlıklar, Basic auth ve POST gövdesi YALNIZ ilk isteğin KAYNAĞINA (şema + host + port) gider. Bunlar kullanıcı
        // girdisi ve pratikte sır taşıyor (Authorization / X-Api-Key); yönlendirme hedefi başka bir kaynağa (başka host YA DA aynı
        // host'un başka portu / şeması — kw son inceleme D-8) çıktığında onu da göndermek anahtarı yabancıya teslim etmek demek.
        // Tarayıcıların cross-origin yönlendirmede Authorization düşürmesiyle aynı kural.
        final URI first = current;
        HttpRequestOptions opts = spec.requestOptions();
        String userAgent = spec.effectiveUserAgent();
        String method = spec.method();
        for (int hop = 0; hop <= SafeRedirect.MAX_HOPS; hop++) {
            String host = current.getHost();
            if (host == null || host.isBlank())
                throw new SsrfGuard.BlockedException("geçersiz hedef URL: " + current);
            ssrfGuard.validate(host);
            int hopTimeout = remainingFor(timeoutMs, deadline);
            HttpRequest req = buildRequest(current, method, hopTimeout, userAgent, opts, KeywordRequestRules.sameOrigin(first, current));
            HttpResponse<InputStream> resp = clientFor(spec.viaProxy()).send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (!SafeRedirect.isRedirect(resp.statusCode())) return resp;
            if (!spec.followRedirects()) return resp;   // takip kapalı → 3xx olduğu gibi değerlendirilir (kw §5.1)
            URI next = SafeRedirect.nextHop(current, resp.headers().firstValue("location").orElse(null));
            // Takip edilemeyen hedef (şema dışı / host'suz / HTTPS→HTTP düşürmesi) → 3xx olduğu gibi
            // döner. HttpCheckerService ile AYNI satır: elle takibe geçen iki çağıran da
            // Redirect.NORMAL'in davranışını korur (SafeRedirect.isDowngrade javadoc'u).
            if (next == null || SafeRedirect.isDowngrade(current, next)) return resp;
            // Yönlendirme gövdesi: bağlantı iadesi için kısa okuma — süre sınırlı (N1), hata yok sayılır.
            int drainMs = remainingFor(timeoutMs, deadline);   // toplam bütçe doldu → kontrol zaman aşımıyla biter
            try (InputStream is = com.sitemonitor.util.HttpBodies.withDeadline(resp.body(), drainMs, "Keyword")) {
                is.readNBytes(4096);
            } catch (Exception ignore) { /* bağlantı iadesi */ }
            method = SafeRedirect.nextMethod(resp.statusCode(), method);   // 303 → GET (gövdesiz)
            current = next;
            trail.current = next;
            trail.redirects++;
        }
        throw new java.io.IOException("çok fazla yönlendirme (" + SafeRedirect.MAX_HOPS + " hop aşıldı)");
    }

    /**
     * İstek kurucusu (kw sözleşmesi §5.1). Sıra: User-Agent (kip) → Authorization (Basic) → Content-Type (POST + gövde) → özel
     * başlıklar {@code setHeader} ile EN SON: aynı adlı özel başlık User-Agent / Authorization'ı bilinçli EZER (eskiden
     * {@code header()} ikinci değer EKLİYORDU — WAF'ı aşma amacı boşa çıkıyordu, inceleme B8). Eklentiler (Basic auth, gövde,
     * özel başlık) yalnız {@code applyExtras} (ilk host) iken. Eklentisiz GET isteği bugünkünün aynısıdır.
     */
    static HttpRequest buildRequest(URI uri, String method, int timeoutMs, String userAgent, HttpRequestOptions opts,
                                    boolean applyExtras) {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                .header("User-Agent", userAgent == null ? KeywordRequestRules.DEFAULT_USER_AGENT : userAgent);
        boolean post = KeywordRequestRules.METHOD_POST.equals(method);
        boolean sendBody = applyExtras && post && opts != null && opts.sendsBody(method);
        if (applyExtras && opts != null) {
            String auth = HttpRequestRules.basicAuthHeader(opts.basicAuthUser(), opts.basicAuthPass());
            if (auth != null) trySetHeader(rb, "Authorization", auth);
            if (sendBody) trySetHeader(rb, "Content-Type", opts.effectiveContentType());
            for (Map.Entry<String, String> h : HttpRequestRules.parseHeaders(opts.headers()).entrySet()) {
                trySetHeader(rb, h.getKey(), h.getValue());
            }
        }
        if (post) {
            rb.POST(sendBody
                    ? HttpRequest.BodyPublishers.ofString(opts.body(), StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.noBody());
        } else {
            rb.GET();
        }
        return rb.build();
    }

    /** Kısıtlı/geçersiz başlığı (HttpClient reddederse) sessizce atlar — kayıt anında zaten doğrulandı. */
    private static void trySetHeader(HttpRequest.Builder rb, String name, String value) {
        try { rb.setHeader(name, value); }
        catch (IllegalArgumentException ignore) { /* kısıtlı/geçersiz — atla */ }
    }

    /** {timestamp} → güncel Unix saniye (her kontrolde benzersiz URL → ara cache bypass). */
    private static String applyTimestamp(String url) {
        if (url == null || !url.contains("{timestamp}")) return url;
        return url.replace("{timestamp}", String.valueOf(java.time.Instant.now().getEpochSecond()));
    }

    /** Adet koşulu değerlendirmesi: SAĞLIKLI = (geçiş adedi) [operatör] (eşik). */
    public static boolean evaluate(int count, String op, int threshold) {
        return switch (op == null ? "GTE" : op) {
            case "LTE" -> count <= threshold;
            case "EQ"  -> count == threshold;
            case "GT"  -> count >  threshold;
            case "LT"  -> count <  threshold;
            default    -> count >= threshold;   // GTE
        };
    }

    /** Operatör + eşik → Türkçe ifade ("en az 3 kez" vb.) — mesaj/şablon/UI için. */
    public static String opPhrase(String op, int n) {
        return switch (op == null ? "GTE" : op) {
            case "LTE" -> "en fazla " + n + " kez";
            case "EQ"  -> "tam olarak " + n + " kez";
            case "GT"  -> n + " kezden fazla";
            case "LT"  -> n + " kezden az";
            default    -> "en az " + n + " kez";   // GTE
        };
    }
}
