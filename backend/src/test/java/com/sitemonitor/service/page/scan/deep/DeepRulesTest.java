package com.sitemonitor.service.page.scan.deep;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Algoritma 3 kuralları — küçültme / kaynak haritası, çerez, güvenlik başlıkları, güvenlik puanı, ağ profili. */
class DeepRulesTest {

    static String repeat(String s, int n) {
        return s.repeat(n);
    }

    @Test
    @DisplayName("küçültme: uzun satır / az boşluk → true; kısa girintili satırlar → false; küçük ya da arada → null")
    void minified() {
        String minified = "var a=1;" + repeat("function f(a,b){return a+b}", 200);
        assertThat(AssetSample.minified(minified)).isTrue();
        String pretty = repeat("function add(a, b) {\n    // toplar\n    return a + b;\n}\n\n", 120);
        assertThat(AssetSample.minified(pretty)).isFalse();
        assertThat(AssetSample.minified("var a = 1;\n")).as("çok küçük").isNull();
        String mid = repeat("abcdefghij klmnopqrstu vwxyz abcdefghij klmnopq rstuvwxyz abcdefghij klmnop qrstuv wxyz abcdefghijk\n", 60);
        assertThat(AssetSample.minified(mid)).as("satır ~100, boşluk ~%9 → belirsiz").isNull();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', nullValues = "-", value = {
            "x();\\n//# sourceMappingURL=app.js.map|app.js.map",
            "x();\\n//@ sourceMappingURL=old.map\\n|old.map",
            "a{b:c}\\n/*# sourceMappingURL=site.css.map */|site.css.map",
            "x();\\n//# sourceMappingURL=data:application/json;base64,AAA|-",
            "var s = 'sourceMappingURL=fake.map';|-",
            "x();|-"})
    void sourceMapping(String tail, String expected) {
        assertThat(AssetSample.sourceMapping(tail.replace("\\n", "\n"))).isEqualTo(expected);
    }

    @Test
    @DisplayName("örnek: betikte kütüphane başlıktan, stilde kütüphane aranmaz; örnek yoksa hepsi bilinmiyor")
    void inspect() {
        AssetSample.Result r = AssetSample.inspect("https://a.com/vendor.js", "/*! jQuery v1.12.4 | (c) jQuery Foundation */\n"
                + repeat("a=1;", 1000), "\n//# sourceMappingURL=vendor.js.map", true);
        assertThat(r.library()).isEqualTo(new JsLibraryCatalog.Library("jquery", "1.12.4"));
        assertThat(r.sourceMap()).isEqualTo("vendor.js.map");
        assertThat(r.minified()).isTrue();
        assertThat(AssetSample.inspect("https://a.com/jquery-1.12.4.css", "a{}", null, false).library()).isNull();
        assertThat(AssetSample.inspect("https://a.com/x.js", null, null, true)).isEqualTo(AssetSample.Result.NONE);
    }

    @Test
    @DisplayName("çerez: değer atılır, öznitelikler okunur; sorunlar; oturum benzeri ad")
    void cookies() {
        long now = 1_700_000_000_000L;
        CookieFacts.Cookie c = CookieFacts.parse("sid=; Path=/; HttpOnly", "a.com", now);
        assertThat(c.name()).isEqualTo("sid");
        assertThat(c.httpOnly()).isTrue();
        assertThat(c.secure()).isFalse();
        assertThat(CookieFacts.issues(c, true)).containsExactly("not_secure", "no_same_site");
        assertThat(CookieFacts.insecure(CookieFacts.issues(c, true), c.name())).isTrue();

        CookieFacts.Cookie none = CookieFacts.parse("pref=; SameSite=None; Max-Age=40000000", "a.com", now);
        assertThat(none.sameSite()).isEqualTo("None");
        assertThat(CookieFacts.issues(none, false)).containsExactly("no_http_only", "none_without_secure", "long_lived");

        CookieFacts.Cookie good = CookieFacts.parse("auth_token=; Secure; HttpOnly; SameSite=Lax; Max-Age=3600", "a.com", now);
        assertThat(CookieFacts.issues(good, true)).isEmpty();
        assertThat(CookieFacts.insecure(List.of("no_http_only"), "theme")).as("oturum dışı çerezde HttpOnly yok → güvensiz değil")
                .isFalse();
        assertThat(CookieFacts.insecure(List.of("no_http_only"), "JSESSIONID")).isTrue();
        assertThat(CookieFacts.parse("=x; Secure", "a.com", now)).isNull();
        assertThat(CookieFacts.parse("", "a.com", now)).isNull();
    }

    @ParameterizedTest(name = "{0} → {1} {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', nullValues = "-", value = {
            "default-src 'self'|ok|-",
            "script-src 'self' 'unsafe-inline'|weak|unsafe_inline",
            "script-src 'self' 'unsafe-inline' 'nonce-abc'|ok|-",
            "script-src 'self' 'unsafe-eval'|weak|unsafe_eval",
            "script-src *|weak|wildcard_source",
            "script-src https:|weak|wildcard_source",
            "frame-ancestors 'none'|weak|no_script_policy",
            "img-src 'self'; default-src 'self' 'unsafe-inline' 'unsafe-eval'|weak|unsafe_inline"})
    void csp(String policy, String status, String firstIssue) {
        SecurityHeaders.CspResult r = SecurityHeaders.csp(Map.of("content-security-policy", List.of(policy)), null);
        assertThat(r.status()).isEqualTo(status);
        assertThat(r.issues().isEmpty() ? null : r.issues().get(0)).isEqualTo(firstIssue);
    }

    @Test
    @DisplayName("CSP: yalnız Report-Only → weak; meta → meta_only notu; birden çok politikadan en güçlüsü; frame-ancestors tam metinden")
    void cspSources() {
        assertThat(SecurityHeaders.csp(Map.of("content-security-policy-report-only", List.of("default-src 'self'")), null).issues())
                .containsExactly("report_only");
        SecurityHeaders.CspResult meta = SecurityHeaders.csp(Map.of(), "default-src 'self'");
        assertThat(meta.status()).isEqualTo("ok");
        assertThat(meta.issues()).containsExactly("meta_only");
        assertThat(meta.frameAncestors()).isFalse();
        SecurityHeaders.CspResult two = SecurityHeaders.csp(Map.of("content-security-policy",
                List.of("script-src *", "default-src 'self'; frame-ancestors 'self'")), null);
        assertThat(two.status()).isEqualTo("ok");
        assertThat(two.frameAncestors()).isTrue();
        String longPolicy = "default-src 'self'; " + "img-src https://cdn" + "x".repeat(400) + ".com; frame-ancestors 'none'";
        assertThat(SecurityHeaders.csp(Map.of("content-security-policy", List.of(longPolicy)), null).frameAncestors()).isTrue();
    }

    @ParameterizedTest(name = "{0} {1} → {2} {3}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "max-age=31536000; includeSubDomains|true|ok|-",
            "max-age=86400|true|weak|short_max_age",
            "max-age=0|true|weak|disabled",
            "includeSubDomains|true|weak|invalid",
            "-|true|missing|-",
            "max-age=31536000|false|missing|not_https"})
    void hsts(String value, boolean https, String status, String issue) {
        SecurityHeaders.Header h = SecurityHeaders.hsts(value, https);
        assertThat(h.status()).isEqualTo(status);
        assertThat(h.issue()).isEqualTo(issue);
    }

    @Test
    @DisplayName("diğer başlıklar: nosniff, çerçeve (XFO / frame-ancestors), referrer, permissions / feature, COOP, CORP")
    void otherHeaders() {
        List<SecurityHeaders.Header> all = SecurityHeaders.evaluate(Map.of(
                "x-content-type-options", List.of("nosniff"),
                "x-frame-options", List.of("ALLOW-FROM https://x"),
                "referrer-policy", List.of("no-referrer, unsafe-url"),
                "feature-policy", List.of("camera 'none'"),
                "cross-origin-opener-policy", List.of("unsafe-none"),
                "cross-origin-resource-policy", List.of("same-site")), null, true);
        assertThat(all).extracting(SecurityHeaders.Header::key).containsExactlyElementsOf(SecurityHeaders.KEYS);
        Map<String, SecurityHeaders.Header> by = new java.util.HashMap<>();
        for (SecurityHeaders.Header h : all) by.put(h.key(), h);
        assertThat(by.get("csp").status()).isEqualTo("missing");
        assertThat(by.get("hsts").status()).isEqualTo("missing");
        assertThat(by.get("x_content_type_options").status()).isEqualTo("ok");
        assertThat(by.get("frame_protection").issue()).isEqualTo("allow_from_deprecated");
        assertThat(by.get("referrer_policy").issue()).as("tarayıcı tanıdığı SONUNCUYU kullanır").isEqualTo("unsafe_url");
        assertThat(by.get("permissions_policy").issue()).isEqualTo("legacy_feature_policy");
        assertThat(by.get("coop").issue()).isEqualTo("unsafe_none");
        assertThat(by.get("corp").status()).isEqualTo("ok");
        SecurityHeaders.Header viaCsp = SecurityHeaders.evaluate(Map.of("content-security-policy",
                List.of("default-src 'self'; frame-ancestors 'self'")), null, true).get(3);
        assertThat(viaCsp.status()).isEqualTo("ok");
        assertThat(SecurityHeaders.referrer("strict-origin-when-cross-origin").status()).isEqualTo("ok");
        assertThat(SecurityHeaders.referrer("bogus").issue()).isEqualTo("invalid");
    }

    @Test
    @DisplayName("güvenlik puanı: başlık düşüleri, http sayfası, çerez / SRI / açık / karışık içerik tavanları")
    void securityScore() {
        List<SecurityHeaders.Header> allOk = SecurityHeaders.KEYS.stream()
                .map(k -> new SecurityHeaders.Header(k, "ok", "v", null)).toList();
        assertThat(SecurityScore.score(allOk, 0, 0, List.of(), 0, 0)).isEqualTo(100);
        List<SecurityHeaders.Header> allMissing = SecurityHeaders.KEYS.stream()
                .map(k -> new SecurityHeaders.Header(k, "missing", null, null)).toList();
        assertThat(SecurityScore.score(allMissing, 0, 0, List.of(), 0, 0)).isEqualTo(100 - (15 + 15 + 8 + 10 + 4 + 3 + 2 + 1));
        List<SecurityHeaders.Header> http = allOk.stream()
                .map(h -> "hsts".equals(h.key()) ? new SecurityHeaders.Header("hsts", "missing", null, "not_https") : h).toList();
        assertThat(SecurityScore.score(http, 0, 0, List.of(), 0, 0)).isEqualTo(70);
        assertThat(SecurityScore.score(allOk, 10, 0, List.of(), 0, 0)).as("çerez tavanı 15").isEqualTo(85);
        assertThat(SecurityScore.score(allOk, 0, 10, List.of(), 0, 0)).as("SRI tavanı 12").isEqualTo(88);
        assertThat(SecurityScore.score(allOk, 0, 0, List.of("critical", "medium"), 0, 0)).isEqualTo(100 - 23);
        assertThat(SecurityScore.score(allOk, 0, 0, List.of("high", "high", "high"), 0, 0)).as("açık tavanı 30").isEqualTo(70);
        assertThat(SecurityScore.score(allOk, 0, 0, List.of(), 1, 2)).as("karışık tavanı 20").isEqualTo(80);
        assertThat(SecurityScore.score(allMissing, 10, 10, List.of("high", "high", "high"), 5, 5)).isEqualTo(0);
    }

    @Test
    @DisplayName("ağ profili modeli: HTML kurulum + RTT + bayt/bant; yeni host bir kurulum turu; 6'lık turlar; ilk boyama yalnız engelleyenler")
    void networkProfiles() {
        NetworkProfiles.Profile p4g = NetworkProfiles.PROFILES.get(1);
        assertThat(p4g.key()).isEqualTo("4g");
        List<NetworkProfiles.Obj> objs = List.of(
                new NetworkProfiles.Obj("a.com", true, 112_500, true, 0),          // 100 ms @ 9 Mbit/s
                new NetworkProfiles.Obj("cdn.com", true, 0, false, 0));
        long[] r = NetworkProfiles.estimate(p4g, "a.com", true, 112_500, objs);
        // HTML: 3×70 + 70 + 100 = 380; yükleme: + 3×70 (yeni host) + 1×70 + 100 = 760; ilk boyama: + 0 + 70 + 100 = 550
        assertThat(r[0]).isEqualTo(760);
        assertThat(r[1]).isEqualTo(550);
        long[] none = NetworkProfiles.estimate(p4g, "a.com", true, 112_500, List.of());
        assertThat(none[0]).isEqualTo(380).isEqualTo(none[1]);
        assertThat(NetworkProfiles.estimate("a.com", true, 1000, objs)).extracting(m -> m.get("key"))
                .containsExactly("fiber", "4g", "3g", "slow3g");
    }
}
