package com.sitemonitor.service.http;

import com.sitemonitor.model.HttpMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP izlemesinin gelişmiş istek KURALLARI (2026-10-01, onaylı öneri 9): kayıt anı doğrulaması (istek bağlamı yok →
 * {@code Msg.t} Türkçe döner) ve çalışma anı ayrıştırması / seçenek kurulumu.
 */
class HttpRequestRulesTest {

    // ── Özel başlıklar ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("başlıklar: geçerli satırlar, boş satır ve # yorumu kabul")
    void headers_valid() {
        assertThat(HttpRequestRules.validateHeaders(null)).isNull();
        assertThat(HttpRequestRules.validateHeaders("   ")).isNull();
        assertThat(HttpRequestRules.validateHeaders("Authorization: Bearer abc\n\n# yorum\nX-Api-Key: k-1\r\nCache-Control: no-cache"))
                .isNull();
        assertThat(HttpRequestRules.validateHeaders("X-Tab: a\tb")).as("değerde sekme serbest").isNull();
    }

    @Test
    @DisplayName("başlıklar: iki noktasız satır → satır numarasıyla 400 iletisi")
    void headers_badLine() {
        assertThat(HttpRequestRules.validateHeaders("X-Ok: 1\nbozuk satır"))
                .contains("satır 2").contains("Ad: değer");
        assertThat(HttpRequestRules.validateHeaders(": değer")).contains("satır 1");
    }

    @Test
    @DisplayName("başlıklar: satır içi CR / kontrol karakteri reddedilir (başlık enjeksiyonu)")
    void headers_crlfRejected() {
        assertThat(HttpRequestRules.validateHeaders("X-A: a\rInjected: 1")).contains("CR/LF");
        assertThat(HttpRequestRules.validateHeaders("X-A: a\u0000b")).contains("CR/LF");
    }

    @Test
    @DisplayName("başlıklar: Host / Content-Length / Connection / Transfer-Encoding elle ayarlanamaz")
    void headers_restricted() {
        for (String name : new String[]{"Host", "content-length", "Connection", "Transfer-Encoding", "Expect", "Upgrade"}) {
            assertThat(HttpRequestRules.validateHeaders(name + ": x")).as(name).contains("elle ayarlanamaz");
        }
    }

    @Test
    @DisplayName("başlıklar: geçersiz ad, çok uzun değer ve 20 başlık tavanı")
    void headers_nameValueCount() {
        assertThat(HttpRequestRules.validateHeaders("Kötü Ad: x")).contains("geçerli bir başlık adı değil");
        assertThat(HttpRequestRules.validateHeaders("X-Big: " + "a".repeat(HttpRequestRules.MAX_HEADER_VALUE + 1)))
                .contains("en fazla");
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= HttpRequestRules.MAX_HEADERS; i++) many.append("X-H").append(i).append(": v\n");
        assertThat(HttpRequestRules.validateHeaders(many.toString())).contains("En fazla 20");
    }

    @Test
    @DisplayName("parseHeaders: çalışma anında bozuk/kısıtlı satır sessizce atlanır, sıra korunur")
    void parseHeaders_defensive() {
        Map<String, String> h = HttpRequestRules.parseHeaders("X-B: 2\nbozuk\nHost: evil\nX-A: 1\n# not\nX-C: a\rb");
        assertThat(h).containsExactly(Map.entry("X-B", "2"), Map.entry("X-A", "1"));
    }

    // ── Gövde / içerik türü / Basic auth ─────────────────────────────────────────────────────

    @Test
    @DisplayName("gövde: 64 KB (UTF-8 bayt) tavanı")
    void body_sizeLimit() {
        assertThat(HttpRequestRules.validateBody("a".repeat(HttpRequestRules.MAX_BODY_BYTES))).isNull();
        assertThat(HttpRequestRules.validateBody("a".repeat(HttpRequestRules.MAX_BODY_BYTES + 1))).contains("64 KB");
        // çok baytlı karakter: karakter sayısı tavanın altında ama bayt sayısı üstünde
        assertThat(HttpRequestRules.validateBody("ş".repeat(HttpRequestRules.MAX_BODY_BYTES / 2 + 1))).contains("64 KB");
    }

    @Test
    @DisplayName("içerik türü: tür/alt-tür biçimi; boş serbest")
    void contentType() {
        assertThat(HttpRequestRules.validateContentType(null)).isNull();
        assertThat(HttpRequestRules.validateContentType("application/json")).isNull();
        assertThat(HttpRequestRules.validateContentType("application/x-www-form-urlencoded; charset=UTF-8")).isNull();
        assertThat(HttpRequestRules.validateContentType("json")).isNotNull();
        assertThat(HttpRequestRules.validateContentType("text/plain\r\nX: 1")).isNotNull();
    }

    @Test
    @DisplayName("Basic auth: kullanıcı adında iki nokta / kontrol karakteri yok; parolada satır sonu yok")
    void basicAuth() {
        assertThat(HttpRequestRules.validateBasicAuthUser("izleme")).isNull();
        assertThat(HttpRequestRules.validateBasicAuthUser("a:b")).isNotNull();
        assertThat(HttpRequestRules.validateBasicAuthPass("p@ss: w0rd")).isNull();
        assertThat(HttpRequestRules.validateBasicAuthPass("a\nb")).isNotNull();
        String hdr = HttpRequestRules.basicAuthHeader("izleme", "p@ss");
        assertThat(hdr).isEqualTo("Basic " + Base64.getEncoder().encodeToString("izleme:p@ss".getBytes(StandardCharsets.UTF_8)));
        assertThat(HttpRequestRules.basicAuthHeader(" ", "x")).as("kullanıcı yoksa başlık yok").isNull();
    }

    // ── JSON / yavaşlık ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("JSON yolu: sözdizimi hatası konumlu iletiyle; HEAD yönteminde JSON doğrulaması reddedilir")
    void jsonPathAndMethod() {
        assertThat(HttpRequestRules.validateJsonPath("$.status")).isNull();
        assertThat(HttpRequestRules.validateJsonPath("$.items[x]")).contains("Geçersiz JSON yolu").contains("9. karakter");
        assertThat(HttpRequestRules.validateJsonPath("$." + "a".repeat(HttpRequestRules.MAX_JSON_PATH))).contains("en fazla");
        assertThat(HttpRequestRules.validateJsonExpected("x".repeat(HttpRequestRules.MAX_JSON_EXPECTED + 1))).isNotNull();
        assertThat(HttpRequestRules.validateJsonWithMethod("HEAD", "$.a")).contains("HEAD");
        assertThat(HttpRequestRules.validateJsonWithMethod("HEAD", null)).isNull();
        assertThat(HttpRequestRules.validateJsonWithMethod("GET", "$.a")).isNull();
    }

    @Test
    @DisplayName("yavaş yanıt eşiği: 100–300000 ms")
    void slowThreshold() {
        assertThat(HttpRequestRules.validateSlowThreshold(3000)).isNull();
        assertThat(HttpRequestRules.validateSlowThreshold(99)).isNotNull();
        assertThat(HttpRequestRules.validateSlowThreshold(300_001)).isNotNull();
    }

    // ── Seçenek kurulumu ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("forMonitor: alanları boş izleme TAM OLARAK NONE örneğini alır (çağıran eski girişe gider)")
    void forMonitor_plainIsNone() {
        HttpMonitor m = new HttpMonitor();
        m.setUrl("https://www.example.com/");
        assertThat(HttpRequestOptions.forMonitor(m, s -> "x")).isSameAs(HttpRequestOptions.NONE);
        // yalnız içerik türü / beklenen değer / parola (kullanıcısız) isteği değiştirmez → yine NONE
        m.setRequestContentType("text/plain");
        m.setJsonExpected("ok");
        m.setBasicAuthPassEnc("enc");
        m.setSlowResponseEnabled(true);
        assertThat(HttpRequestOptions.forMonitor(m, s -> "x")).isSameAs(HttpRequestOptions.NONE);
    }

    @Test
    @DisplayName("forMonitor: sırlar çözülür; çözülemeyen sır o kimlik olmadan devam eder; toString sır basmaz")
    void forMonitor_decrypts() {
        HttpMonitor m = new HttpMonitor();
        m.setCustomHeadersEnc("ENC-H");
        m.setBasicAuthUser("izleme");
        m.setBasicAuthPassEnc("ENC-P");
        m.setJsonPath("$.status");
        HttpRequestOptions o = HttpRequestOptions.forMonitor(m, s -> s.equals("ENC-H") ? "X-Api-Key: gizli-123" : "parola-456");
        assertThat(o.headers()).isEqualTo("X-Api-Key: gizli-123");
        assertThat(o.basicAuthPass()).isEqualTo("parola-456");
        assertThat(o.hasJsonAssertion()).isTrue();
        assertThat(o.toString()).doesNotContain("gizli-123").doesNotContain("parola-456");

        HttpRequestOptions broken = HttpRequestOptions.forMonitor(m, s -> { throw new IllegalStateException("anahtar yok"); });
        assertThat(broken.headers()).isNull();
        assertThat(broken.basicAuthPass()).isNull();
        assertThat(broken.basicAuthUser()).isEqualTo("izleme");

        assertThat(o.withoutJsonAssertion().hasJsonAssertion()).isFalse();
        assertThat(o.withoutJsonAssertion().headers()).isEqualTo("X-Api-Key: gizli-123");
    }

    @Test
    @DisplayName("sendsBody: YALNIZ POST + dolu gövde; içerik türü boşsa application/json")
    void sendsBody() {
        HttpRequestOptions o = new HttpRequestOptions(null, null, null, "{\"a\":1}", null, null, null);
        assertThat(o.sendsBody("POST")).isTrue();
        assertThat(o.sendsBody("GET")).isFalse();
        assertThat(o.sendsBody("HEAD")).isFalse();
        assertThat(o.effectiveContentType()).isEqualTo("application/json");
        assertThat(new HttpRequestOptions(null, null, null, "  ", null, null, null).sendsBody("POST")).isFalse();
    }
}
