package com.sitemonitor.service;

import com.sitemonitor.service.http.HttpRequestOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GERİYE UYUM KİLİDİ (2026-10-01, onaylı öneri 9): HTTP izlemesinin istek kurucusu gelişmiş alanlar BOŞKEN bugünkü
 * isteğin BİREBİR aynısını üretir — yöntem, başlıklar (yalnız User-Agent), POST gövdesiz. Alanlar doluyken başlıklar,
 * Basic auth ve gövde yalnız izin verilen yerde (ilk host, POST) uygulanır.
 */
class HttpRequestBuildTest {

    private static final URI U = URI.create("https://api.example.com/health");
    private static final String UA = "SiteMonitor-HttpMonitor/1.0";

    private static HttpRequest build(String method, HttpRequestOptions o, boolean origin) {
        return HttpCheckerService.buildRequest(U, method, 5000, null, o, origin);
    }

    private static long bodyLength(HttpRequest r) {
        return r.bodyPublisher().map(HttpRequest.BodyPublisher::contentLength).orElse(-1L);
    }

    @Test
    @DisplayName("NONE: GET/HEAD/POST bugünkü gibi — yalnız User-Agent, POST gövdesiz (contentLength 0)")
    void none_isLegacy() {
        for (boolean origin : new boolean[]{true, false}) {
            HttpRequest get = build("GET", HttpRequestOptions.NONE, origin);
            assertThat(get.method()).isEqualTo("GET");
            assertThat(get.headers().map()).isEqualTo(Map.of("User-Agent", List.of(UA)));
            assertThat(get.bodyPublisher()).isEmpty();

            HttpRequest head = build("HEAD", HttpRequestOptions.NONE, origin);
            assertThat(head.method()).isEqualTo("HEAD");
            assertThat(head.headers().map()).isEqualTo(Map.of("User-Agent", List.of(UA)));
            assertThat(bodyLength(head)).isZero();

            HttpRequest post = build("POST", HttpRequestOptions.NONE, origin);
            assertThat(post.method()).isEqualTo("POST");
            assertThat(post.headers().map()).isEqualTo(Map.of("User-Agent", List.of(UA)));
            assertThat(bodyLength(post)).as("POST bugün de gövdesiz").isZero();
            assertThat(post.timeout()).contains(java.time.Duration.ofMillis(5000));
        }
    }

    @Test
    @DisplayName("tüm alanları BOŞ/boşluk bir seçenek de NONE gibi davranır (fark yaratmaz)")
    void blankOptions_behaveAsNone() {
        HttpRequestOptions blank = new HttpRequestOptions("  ", "", null, "   ", "text/plain", "", "ok");
        assertThat(blank.isEmpty()).isTrue();
        for (String method : new String[]{"GET", "HEAD", "POST"}) {
            HttpRequest legacy = build(method, HttpRequestOptions.NONE, true);
            HttpRequest r = build(method, blank, true);
            assertThat(r.method()).isEqualTo(legacy.method());
            assertThat(r.headers().map()).isEqualTo(legacy.headers().map());
            assertThat(bodyLength(r)).isEqualTo(bodyLength(legacy));
        }
    }

    @Test
    @DisplayName("başlıklar + Basic auth ilk host'ta uygulanır; kullanıcı başlığı aynı adı bilinçli ezer")
    void headersAndBasicAuth_applied() {
        HttpRequestOptions o = new HttpRequestOptions("X-Api-Key: k-123\nCache-Control: no-cache", "izleme", "p@ss",
                null, null, null, null);
        HttpRequest r = build("GET", o, true);
        assertThat(r.headers().firstValue("X-Api-Key")).contains("k-123");
        assertThat(r.headers().firstValue("Cache-Control")).contains("no-cache");
        assertThat(r.headers().firstValue("Authorization")).contains(
                "Basic " + Base64.getEncoder().encodeToString("izleme:p@ss".getBytes(StandardCharsets.UTF_8)));
        assertThat(r.headers().allValues("User-Agent")).containsExactly(UA);
        assertThat(r.bodyPublisher()).as("GET gövdesiz kalır").isEmpty();

        HttpRequest overridden = build("GET",
                new HttpRequestOptions("Authorization: Token abc", "izleme", "p@ss", null, null, null, null), true);
        assertThat(overridden.headers().allValues("Authorization")).containsExactly("Token abc");
    }

    @Test
    @DisplayName("başka host'a giden hop'ta (origin=false) başlık, kimlik ve gövde GÖNDERİLMEZ")
    void otherHost_noSecretsNoBody() {
        HttpRequestOptions o = new HttpRequestOptions("X-Api-Key: k-123", "izleme", "p@ss", "{\"a\":1}", null, null, null);
        HttpRequest r = build("POST", o, false);
        assertThat(r.headers().map()).isEqualTo(Map.of("User-Agent", List.of(UA)));
        assertThat(bodyLength(r)).isZero();
    }

    @Test
    @DisplayName("gövde YALNIZ POST'ta gönderilir; içerik türü boşsa application/json, doluysa o")
    void body_onlyForPost() {
        String json = "{\"probe\":true,\"ad\":\"şık\"}";
        HttpRequestOptions o = new HttpRequestOptions(null, null, null, json, null, null, null);

        HttpRequest post = build("POST", o, true);
        assertThat(bodyLength(post)).isEqualTo(json.getBytes(StandardCharsets.UTF_8).length);
        assertThat(post.headers().firstValue("Content-Type")).contains("application/json");

        HttpRequest get = build("GET", o, true);
        assertThat(get.bodyPublisher()).isEmpty();
        assertThat(get.headers().firstValue("Content-Type")).isEmpty();

        HttpRequest head = build("HEAD", o, true);
        assertThat(bodyLength(head)).isZero();
        assertThat(head.headers().firstValue("Content-Type")).isEmpty();

        HttpRequest form = build("POST", new HttpRequestOptions(null, null, null, "a=1", "application/x-www-form-urlencoded",
                null, null), true);
        assertThat(form.headers().firstValue("Content-Type")).contains("application/x-www-form-urlencoded");
    }

    @Test
    @DisplayName("kısıtlı başlık (Host/Content-Length) çalışma anında da sessizce atlanır — istek patlamaz")
    void restrictedHeaders_skippedAtRuntime() {
        HttpRequestOptions o = new HttpRequestOptions("Host: evil.example.com\nContent-Length: 1\nX-Ok: 1",
                null, null, null, null, null, null);
        HttpRequest r = build("GET", o, true);
        assertThat(r.headers().firstValue("Host")).isEmpty();
        assertThat(r.headers().firstValue("Content-Length")).isEmpty();
        assertThat(r.headers().firstValue("X-Ok")).contains("1");
    }
}
