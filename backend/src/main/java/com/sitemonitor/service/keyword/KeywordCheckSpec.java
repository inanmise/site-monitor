package com.sitemonitor.service.keyword;

import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.service.http.HttpRequestOptions;

/**
 * Bir anahtar kelime KONTROLÜNÜN tanımı (kw sözleşmesi §2 / §13, 2026-11-05) — {@code KeywordCheckerService.check(spec)}'in tek
 * girdisi. Zamanlanmış tur, teyit / kurtarma yeniden kontrolü, elle "Şimdi kontrol et", form "Test", yavaşlık yeniden ölçümü ve
 * uçtan uca tanılama AYNI tanımı kurar ({@link #forMonitor}) — bir alanın bir yolda unutulması imkânsızlaşır.
 *
 * <p>Değişmez; {@link Builder} ile kurulur ({@link #toBuilder()} kopya). Yeni alan eklemek çağrı yerlerini kırmaz (Aşama 2'de
 * kural / boyut / değişim alanları buraya gelir). Sırlar (Basic auth parolası, özel başlıklar) ÇÖZÜLMÜŞ hâldedir — yalnız
 * istek kurulurken yaşar; {@link #toString()} onları basmaz.
 */
public final class KeywordCheckSpec {

    private final String url;
    private final String keyword;
    private final Integer timeoutMs;
    private final String customHeaders;
    private final boolean caseSensitive;
    private final boolean viaProxy;
    private final String operator;
    private final int threshold;
    private final boolean diagnose;
    private final String matchScope;
    private final boolean normalizeWs;
    private final String method;
    private final String requestBody;
    private final String requestContentType;
    private final String basicAuthUser;
    private final String basicAuthPass;
    private final String userAgentMode;
    private final String userAgent;
    private final String expectedStatus;
    private final String expectedFinalUrl;
    private final Boolean followRedirects;
    // ── kw-be2 Aşama 2: kurallar, boyut, içerik değişimi ──
    private final String rulesJson;
    private final String ruleLogic;
    private final Integer minBytes;
    private final Integer maxBytes;
    private final boolean changeDetect;
    private final String changeMask;
    private final String changeSelector;
    private final String monitorLevel;
    private final com.sitemonitor.service.keyword.content.KeywordChangeDetector.BaselineView baseline;
    private final ChangeCapture changeCapture;

    private KeywordCheckSpec(Builder b) {
        this.url = b.url;
        this.keyword = b.keyword;
        this.timeoutMs = b.timeoutMs;
        this.customHeaders = b.customHeaders;
        this.caseSensitive = b.caseSensitive;
        this.viaProxy = b.viaProxy;
        this.operator = b.operator;
        this.threshold = b.threshold;
        this.diagnose = b.diagnose;
        this.matchScope = b.matchScope;
        this.normalizeWs = b.normalizeWs;
        this.method = b.method;
        this.requestBody = b.requestBody;
        this.requestContentType = b.requestContentType;
        this.basicAuthUser = b.basicAuthUser;
        this.basicAuthPass = b.basicAuthPass;
        this.userAgentMode = b.userAgentMode;
        this.userAgent = b.userAgent;
        this.expectedStatus = b.expectedStatus;
        this.expectedFinalUrl = b.expectedFinalUrl;
        this.followRedirects = b.followRedirects;
        this.rulesJson = b.rulesJson;
        this.ruleLogic = b.ruleLogic;
        this.minBytes = b.minBytes;
        this.maxBytes = b.maxBytes;
        this.changeDetect = b.changeDetect;
        this.changeMask = b.changeMask;
        this.changeSelector = b.changeSelector;
        this.monitorLevel = b.monitorLevel;
        this.baseline = b.baseline;
        this.changeCapture = b.changeCapture;
    }

    /**
     * İçerik değişimi ölçümünün çağırana dönüşü (kw sözleşmesi §7.2 / §13): kontrol temel YAZMAZ (saf kalır, işçide de koşar);
     * temel yoksa ölçtüğü anlık görüntüyü buraya bırakır, çağıran (zamanlanmış tur) işçi bırakmadıysa otomatik temeli alır.
     * Sonuç haritasında taşınmaz (JSON'a girmez).
     */
    public static final class ChangeCapture {
        private volatile com.sitemonitor.service.keyword.content.KeywordChangeDetector.Snapshot snapshot;
        private volatile long bytes;
        private volatile String specHash;

        public com.sitemonitor.service.keyword.content.KeywordChangeDetector.Snapshot snapshot() { return snapshot; }
        public long bytes() { return bytes; }
        public String specHash() { return specHash; }

        public void put(com.sitemonitor.service.keyword.content.KeywordChangeDetector.Snapshot s, long b, String h) {
            this.snapshot = s;
            this.bytes = b;
            this.specHash = h;
        }
    }

    public static Builder builder(String url, String keyword) {
        return new Builder().url(url).keyword(keyword);
    }

    /**
     * Kayıtlı izlemeden tanım. {@code headersPlain} / {@code passPlain} çağıranın çözdüğü sırlar ({@code KeywordHeaderSecrets});
     * çözülemeyen sır null gelir — kontrol o kimlik olmadan devam eder (HTTP / Sayfa Hızı ile aynı kural). Teşhis AÇIK (koşul
     * tutmazsa neden + ipuçları + alıntı).
     */
    public static Builder forMonitor(KeywordMonitor m, String headersPlain, String passPlain, boolean viaProxy) {
        return builder(m.getUrl(), m.getKeyword())
                .timeoutMs(m.getTimeoutMs())
                .customHeaders(headersPlain)
                .caseSensitive(Boolean.TRUE.equals(m.getCaseSensitive()))
                .viaProxy(viaProxy)
                .condition(m.getMatchOperator(), m.getMatchCount() != null ? m.getMatchCount() : 1)
                .diagnose(true)
                .matchScope(m.getMatchScope())
                .normalizeWs(Boolean.TRUE.equals(m.getNormalizeWs()))
                .method(m.getMethod())
                .requestBody(m.getRequestBody())
                .requestContentType(m.getRequestContentType())
                .basicAuthUser(m.getBasicAuthUser())
                .basicAuthPass(passPlain)
                .userAgentMode(m.getUserAgentMode())
                .userAgent(m.getUserAgent())
                .expectedStatus(m.getExpectedStatus())
                .expectedFinalUrl(m.getExpectedFinalUrl())
                .followRedirects(m.getFollowRedirects())
                // kw-be2 Aşama 2 (§6 / §7): kurallar, boyut, değişim — temel ÇAĞIRANDAN (baseline), anlık görüntü dönüşü isteğe bağlı
                .rulesJson(m.getRulesJson())
                .ruleLogic(m.getRuleLogic())
                .minBytes(m.getMinBytes())
                .maxBytes(m.getMaxBytes())
                .changeDetect(Boolean.TRUE.equals(m.getChangeDetect()))
                .changeMask(m.getChangeMask())
                .changeSelector(m.getChangeSelector())
                .monitorLevel(com.sitemonitor.model.MonitorAlertPrefs.effectiveLevel(m.getAlertLevel()));
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.url = url; b.keyword = keyword; b.timeoutMs = timeoutMs; b.customHeaders = customHeaders;
        b.caseSensitive = caseSensitive; b.viaProxy = viaProxy; b.operator = operator; b.threshold = threshold;
        b.diagnose = diagnose; b.matchScope = matchScope; b.normalizeWs = normalizeWs; b.method = method;
        b.requestBody = requestBody; b.requestContentType = requestContentType; b.basicAuthUser = basicAuthUser;
        b.basicAuthPass = basicAuthPass; b.userAgentMode = userAgentMode; b.userAgent = userAgent;
        b.expectedStatus = expectedStatus; b.expectedFinalUrl = expectedFinalUrl; b.followRedirects = followRedirects;
        b.rulesJson = rulesJson; b.ruleLogic = ruleLogic; b.minBytes = minBytes; b.maxBytes = maxBytes;
        b.changeDetect = changeDetect; b.changeMask = changeMask; b.changeSelector = changeSelector;
        b.monitorLevel = monitorLevel; b.baseline = baseline; b.changeCapture = changeCapture;
        return b;
    }

    // ── Okuyucular ─────────────────────────────────────────────────────────────────────────────

    public String url() { return url; }
    public String keyword() { return keyword == null ? "" : keyword; }
    /** Ham (saklı) zaman aşımı — null olabilir. */
    public Integer timeoutMs() { return timeoutMs; }
    /** Çalışma anı zaman aşımı: [1, 60] sn ({@link KeywordRequestRules#effectiveTimeoutMs}). */
    public int effectiveTimeoutMs() { return KeywordRequestRules.effectiveTimeoutMs(timeoutMs); }
    public String customHeaders() { return customHeaders; }
    public boolean caseSensitive() { return caseSensitive; }
    public boolean viaProxy() { return viaProxy; }
    public String operator() { return operator == null ? "GTE" : operator; }
    public int threshold() { return threshold; }
    /** Koşul tutmazsa neden / ipucu / alıntı üretilsin mi (eski beklentisiz çağrılar: hayır). */
    public boolean diagnose() { return diagnose; }
    /** Kanonik kapsam: {@code RAW} | {@code VISIBLE}. */
    public String matchScope() { return KeywordText.scope(matchScope); }
    public boolean normalizeWs() { return normalizeWs; }
    /** Kanonik yöntem: {@code GET} | {@code POST}. */
    public String method() { return KeywordRequestRules.method(method); }
    public String requestBody() { return requestBody; }
    public String requestContentType() { return requestContentType; }
    public String basicAuthUser() { return basicAuthUser; }
    public String basicAuthPass() { return basicAuthPass; }
    public String userAgentMode() { return KeywordRequestRules.userAgentMode(userAgentMode); }
    public String userAgent() { return userAgent; }
    /** Gönderilecek User-Agent (kipe göre). */
    public String effectiveUserAgent() { return KeywordRequestRules.effectiveUserAgent(userAgentMode, userAgent); }
    /** Kabul edilen durum kodları; boş = kural KAPALI (null döner). */
    public String expectedStatus() { return blank(expectedStatus) ? null : expectedStatus.trim(); }
    /** Beklenen son URL; boş = kural KAPALI (null döner). */
    public String expectedFinalUrl() { return blank(expectedFinalUrl) ? null : expectedFinalUrl.trim(); }
    /** Yönlendirmeler takip edilir mi (null = evet, bugünkü). */
    public boolean followRedirects() { return !Boolean.FALSE.equals(followRedirects); }

    /** Saklı kural listesi (JSON); {@code null} = eski tek kelime alanları (kural #1). */
    public String rulesJson() { return rulesJson; }
    /** Etkin kural birleşimi: {@code AND} | {@code OR}. */
    public String ruleLogic() { return com.sitemonitor.service.keyword.rules.KeywordRules.effectiveLogic(ruleLogic); }
    public Integer minBytes() { return minBytes; }
    public Integer maxBytes() { return maxBytes; }
    public boolean changeDetect() { return changeDetect; }
    public String changeMask() { return changeMask; }
    public String changeSelector() { return blank(changeSelector) ? null : changeSelector.trim(); }
    /** İzlemenin etkin alarm seviyesi (kuralın kendi kritik eşiği yoksa ihlalin seviyesi). */
    public String monitorLevel() { return monitorLevel; }
    /** Karşılaştırılacak temel (çağıran yükler; {@code null} = yok). */
    public com.sitemonitor.service.keyword.content.KeywordChangeDetector.BaselineView baseline() { return baseline; }
    /** Temel yokken ölçülen anlık görüntünün dönüşü ({@code null} = istenmiyor). */
    public ChangeCapture changeCapture() { return changeCapture; }
    /** ETKİN kurallar ({@code rules_json} ya da eski tek kelime alanları). */
    public java.util.List<com.sitemonitor.service.keyword.rules.KeywordRule> rules() {
        return com.sitemonitor.service.keyword.rules.KeywordRules.effective(rulesJson, keyword, operator, threshold, caseSensitive);
    }

    /** İstek eklentileri HTTP'nin taşıyıcısında (JSON doğrulaması YOK) — {@code HttpRequestRules} ile ortak kurallar. */
    public HttpRequestOptions requestOptions() {
        return new HttpRequestOptions(customHeaders, basicAuthUser, basicAuthPass, requestBody, requestContentType, null, null);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    /** Sırlar ASLA metne dökülmez. */
    @Override
    public String toString() {
        return "KeywordCheckSpec[url=" + url + ", keyword=" + keyword + ", condition=" + operator() + " " + threshold
                + ", caseSensitive=" + caseSensitive + ", timeoutMs=" + effectiveTimeoutMs() + ", viaProxy=" + viaProxy
                + ", method=" + method() + ", scope=" + matchScope()
                + ", headers=" + (blank(customHeaders) ? "none" : "set")
                + ", basicAuth=" + (blank(basicAuthUser) ? "none" : "set")
                + ", body=" + (blank(requestBody) ? "none" : requestBody.length() + " chars")
                + ", expectedStatus=" + expectedStatus() + ", followRedirects=" + followRedirects()
                + ", rules=" + (blank(rulesJson) ? "legacy" : "custom") + ", logic=" + ruleLogic()
                + ", size=" + minBytes + ".." + maxBytes + ", change=" + changeDetect + "]";
    }

    /** Kurucu. */
    public static final class Builder {
        private String url;
        private String keyword;
        private Integer timeoutMs;
        private String customHeaders;
        private boolean caseSensitive;
        private boolean viaProxy;
        private String operator = "GTE";
        private int threshold = 1;
        private boolean diagnose;
        private String matchScope;
        private boolean normalizeWs;
        private String method;
        private String requestBody;
        private String requestContentType;
        private String basicAuthUser;
        private String basicAuthPass;
        private String userAgentMode;
        private String userAgent;
        private String expectedStatus;
        private String expectedFinalUrl;
        private Boolean followRedirects;
        private String rulesJson;
        private String ruleLogic;
        private Integer minBytes;
        private Integer maxBytes;
        private boolean changeDetect;
        private String changeMask;
        private String changeSelector;
        private String monitorLevel;
        private com.sitemonitor.service.keyword.content.KeywordChangeDetector.BaselineView baseline;
        private ChangeCapture changeCapture;

        private Builder() {}

        public Builder url(String v) { this.url = v; return this; }
        public Builder keyword(String v) { this.keyword = v; return this; }
        public Builder timeoutMs(Integer v) { this.timeoutMs = v; return this; }
        public Builder customHeaders(String v) { this.customHeaders = v; return this; }
        public Builder caseSensitive(boolean v) { this.caseSensitive = v; return this; }
        public Builder viaProxy(boolean v) { this.viaProxy = v; return this; }
        /** Adet koşulu (SAĞLIKLI = adet [operatör] eşik). */
        public Builder condition(String op, int n) { this.operator = op; this.threshold = n; return this; }
        public Builder diagnose(boolean v) { this.diagnose = v; return this; }
        public Builder matchScope(String v) { this.matchScope = v; return this; }
        public Builder normalizeWs(boolean v) { this.normalizeWs = v; return this; }
        public Builder method(String v) { this.method = v; return this; }
        public Builder requestBody(String v) { this.requestBody = v; return this; }
        public Builder requestContentType(String v) { this.requestContentType = v; return this; }
        public Builder basicAuthUser(String v) { this.basicAuthUser = v; return this; }
        public Builder basicAuthPass(String v) { this.basicAuthPass = v; return this; }
        public Builder userAgentMode(String v) { this.userAgentMode = v; return this; }
        public Builder userAgent(String v) { this.userAgent = v; return this; }
        public Builder expectedStatus(String v) { this.expectedStatus = v; return this; }
        public Builder expectedFinalUrl(String v) { this.expectedFinalUrl = v; return this; }
        public Builder followRedirects(Boolean v) { this.followRedirects = v; return this; }
        public Builder rulesJson(String v) { this.rulesJson = v; return this; }
        public Builder ruleLogic(String v) { this.ruleLogic = v; return this; }
        public Builder minBytes(Integer v) { this.minBytes = v; return this; }
        public Builder maxBytes(Integer v) { this.maxBytes = v; return this; }
        public Builder changeDetect(boolean v) { this.changeDetect = v; return this; }
        public Builder changeMask(String v) { this.changeMask = v; return this; }
        public Builder changeSelector(String v) { this.changeSelector = v; return this; }
        public Builder monitorLevel(String v) { this.monitorLevel = v; return this; }
        public Builder baseline(com.sitemonitor.service.keyword.content.KeywordChangeDetector.BaselineView v) { this.baseline = v; return this; }
        public Builder changeCapture(ChangeCapture v) { this.changeCapture = v; return this; }

        public KeywordCheckSpec build() { return new KeywordCheckSpec(this); }
    }
}
