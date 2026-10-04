package com.sitemonitor.service.keyword;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.keyword.KeywordBodyAnalyzer.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keyword hata teşhisi (2026-10-04) — "neden bulunamadı" çözümleyicisi: görünür metin, HTML varlıkları, karakter kümesi,
 * her ipucu kendi kurgulanmış gövdesinden, normal sayfalarda YANLIŞ ipucu yok, alıntının maskelenmesi ve tavanları.
 */
class KeywordBodyAnalyzerTest {

    private static final String ORIGIN = "https://shop.example.com/";

    private static Analysis run(String body, String contentType, String kw, boolean cs, int count, String op, int n,
                                int status, String finalUrl, int redirects, List<String> secrets) {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        return analyze(new Input(b, contentType, kw, cs, count, op, n, status, ORIGIN,
                finalUrl == null ? URI.create(ORIGIN) : URI.create(finalUrl), redirects, secrets));
    }

    private static Analysis notFound(String body, String kw) {
        return run(body, "text/html; charset=utf-8", kw, false, count(body, kw, false), "GTE", 1, 200, null, 0, List.of());
    }

    // ── Görünür metin ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("görünür metin")
    class Visible {
        @Test
        @DisplayName("betik/stil/şablon/yorum atlanır, etiketler ayıklanır, varlıklar çözülür, boşluk indirgenir")
        void strips() {
            String html = "<!doctype html><html><head><title>Mağaza &amp; Ürünler</title>"
                    + "<style>.a{color:red}</style><script>var secret='x<y';</script></head>"
                    + "<body><!-- gizli yorum --><div>Merhaba<br>d&uuml;nya&nbsp;&nbsp;  !</div>"
                    + "<template><p>şablon</p></template><p>a &lt; b &#252; &#xFC;</p></body></html>";
            String v = visibleText(html, SCAN_CAP, 10_000);
            assertThat(v).isEqualTo("Mağaza & Ürünler Merhaba dünya ! a < b ü ü");
            assertThat(v).doesNotContain("secret").doesNotContain("color").doesNotContain("yorum").doesNotContain("şablon");
        }

        @Test
        @DisplayName("noscript metni KALIR (JavaScript'siz okuyucunun gördüğü), tırnak içindeki '>' etiketi bölmez")
        void noscriptAndQuotes() {
            String html = "<a title=\"a > b\" href=\"/x\">bağlantı</a><noscript>JavaScript gerekli</noscript>";
            assertThat(visibleText(html, SCAN_CAP, 1000)).isEqualTo("bağlantı JavaScript gerekli");
        }

        @Test
        @DisplayName("kapanmamış betik sonuna kadar atlanır; düz metin/JSON olduğu gibi; tavanlar uygulanır")
        void edgesAndCaps() {
            assertThat(visibleText("<p>önce</p><script>for(;;){}", SCAN_CAP, 1000)).isEqualTo("önce");
            assertThat(visibleText("{\"status\":\"UP\"}", SCAN_CAP, 1000)).isEqualTo("{\"status\":\"UP\"}");
            assertThat(visibleText("<p>" + "a".repeat(5000) + "</p>", SCAN_CAP, 100)).hasSize(100);
            assertThat(visibleText("x".repeat(10) + "y".repeat(10), 10, 1000)).isEqualTo("x".repeat(10));
        }

        @Test
        @DisplayName("başlık ayrıştırma: varlıklar çözülür; başlık yoksa null")
        void title() {
            assertThat(KeywordBodyAnalyzer.title("<html><head><TITLE>Erişim &amp; Giriş</TITLE></head>")).isEqualTo("Erişim & Giriş");
            assertThat(KeywordBodyAnalyzer.title("<html><body>yok</body>")).isNull();
            assertThat(KeywordBodyAnalyzer.title("<titlebar>x</titlebar>")).isNull();
        }

        @Test
        @DisplayName("varlık çözümü: adlı (Türkçe dâhil), ondalık, onaltılık; bilinmeyen olduğu gibi")
        void entities() {
            assertThat(decodeEntities("&Ccedil;i&ccedil;ek &scedil;&#304;&#x131; &bogus; &")).isEqualTo("Çiçek şİı &bogus; &");
            assertThat(collapseWs("  a  b\t\nc  ")).isEqualTo("a b c");
        }
    }

    // ── İpuçları ────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ipuçları")
    class Hints {
        @Test
        @DisplayName("CASE_MISMATCH: harf duyarlı kuralda yalnız farklı harfle var")
        void caseMismatch() {
            String body = "<p>Hoş geldiniz — SİPARİŞ TAMAM</p>";
            Analysis a = run(body, "text/html", "Tamam", true, count(body, "Tamam", true), "GTE", 1, 200, null, 0, List.of());
            assertThat(a.hints()).containsExactly(CASE_MISMATCH);
            assertThat(intOf(a.alternatives().get("case_insensitive"))).isEqualTo(1);
        }

        @Test
        @DisplayName("WHITESPACE_OR_ENTITY: kelime gövdede varlıkla ya da farklı boşlukla yazılmış")
        void entityOrWhitespace() {
            assertThat(notFound("<p>G&uuml;venli &ouml;deme</p>", "Güvenli ödeme").hints()).containsExactly(WHITESPACE_OR_ENTITY);
            assertThat(notFound("<p>Sipariş\n      tamamlandı</p>", "Sipariş tamamlandı").hints()).containsExactly(WHITESPACE_OR_ENTITY);
        }

        @Test
        @DisplayName("CHARSET: sayfa windows-1254 ilan ediyor, kelime UTF-8 okumasında yok ama ilan edilen kümeyle var")
        void charset() {
            byte[] bytes = "<html><body>Şifre sıfırlama başarılı</body></html>".getBytes(Charset.forName("windows-1254"));
            String asUtf8 = new String(bytes, StandardCharsets.UTF_8);
            Analysis a = analyze(new Input(bytes, "text/html; charset=windows-1254", "sıfırlama", false,
                    count(asUtf8, "sıfırlama", false), "GTE", 1, 200, ORIGIN, URI.create(ORIGIN), 0, List.of()));
            assertThat(a.hints()).contains(CHARSET);
            assertThat(a.alternatives()).containsEntry("charset_name", "windows-1254");
            assertThat(a.declaredCharset()).isEqualTo("windows-1254");
        }

        @Test
        @DisplayName("WAF_OR_BLOCK_PAGE: Cloudflare / F5 / Imperva engelleme sayfaları; başlıkta 'Access Denied'")
        void waf() {
            assertThat(notFound("<title>Attention Required! | Cloudflare</title><p>Sorry, you have been blocked</p>", "Ürünler").hints())
                    .contains(WAF_OR_BLOCK_PAGE);
            assertThat(notFound("<html><body>The requested URL was rejected. Please consult with your administrator."
                    + "<br>Your support ID is: 1234567890</body></html>", "Ürünler").hints()).contains(WAF_OR_BLOCK_PAGE);
            assertThat(notFound("<html><body>Request unsuccessful. Incapsula incident ID: 1-2</body></html>", "Ürünler").hints())
                    .contains(WAF_OR_BLOCK_PAGE);
            assertThat(run("<title>Access Denied</title><h1>Access Denied</h1>", "text/html", "Ürünler", false, 0, "GTE", 1, 403,
                    null, 0, List.of()).hints()).contains(WAF_OR_BLOCK_PAGE);
        }

        @Test
        @DisplayName("LOGIN_PAGE: parola alanı ya da yönlendirmeyle varılan /login yolu")
        void login() {
            assertThat(notFound("<form action=\"/session\"><input name=\"u\"><input type=\"password\" name=\"p\"></form>", "Panel").hints())
                    .contains(LOGIN_PAGE);
            assertThat(run("<p>Lütfen bekleyin</p>", "text/html", "Panel", false, 0, "GTE", 1, 200,
                    "https://shop.example.com/sso/login?next=/panel", 2, List.of()).hints()).contains(LOGIN_PAGE);
        }

        @Test
        @DisplayName("JS_RENDERED: boş SPA kökü + betik + noscript, görünür metin yok denecek kadar az")
        void jsRendered() {
            String spa = "<!doctype html><html><head><title>Uygulama</title><script src=\"/assets/app.js\"></script></head>"
                    + "<body><noscript>You need to enable JavaScript to run this app.</noscript><div id=\"root\"></div></body></html>";
            assertThat(notFound(spa, "Sepetim").hints()).contains(JS_RENDERED);
        }

        @Test
        @DisplayName("REDIRECTED_ELSEWHERE: son host başka site (www. farkı aynı site sayılır)")
        void redirectedElsewhere() {
            assertThat(run("<p>başka</p>", "text/html", "Ürün", false, 0, "GTE", 1, 200,
                    "https://portal.example.net/home", 1, List.of()).hints()).contains(REDIRECTED_ELSEWHERE);
            assertThat(run("<p>aynı</p>", "text/html", "Ürün", false, 0, "GTE", 1, 200,
                    "https://www.shop.example.com/", 1, List.of()).hints()).doesNotContain(REDIRECTED_ELSEWHERE);
        }

        @Test
        @DisplayName("MAINTENANCE_PAGE: başlıkta bakım ya da 503 + bakım metni")
        void maintenance() {
            assertThat(notFound("<title>Planlı Bakım</title><p>Kısa süre içinde geri döneceğiz.</p>", "Ürün").hints())
                    .contains(MAINTENANCE_PAGE);
            assertThat(run("<p>We are down for maintenance</p>", "text/html", "Ürün", false, 0, "GTE", 1, 503, null, 0,
                    List.of()).hints()).contains(MAINTENANCE_PAGE);
        }

        @Test
        @DisplayName("ERROR_PAGE: durum 200 ama başlık '404 / bulunamadı' (yumuşak hata sayfası)")
        void softError() {
            assertThat(notFound("<title>404 - Sayfa bulunamadı</title><p>Aradığınız sayfa yok</p>", "Ürün").hints())
                    .contains(ERROR_PAGE);
            // 4xx/5xx'te zaten HTTP_STATUS nedeni var — ERROR_PAGE ipucu yalnız durum < 400 iken
            assertThat(run("<title>404 Not Found</title>", "text/html", "Ürün", false, 0, "GTE", 1, 404, null, 0, List.of())
                    .hints()).doesNotContain(ERROR_PAGE);
        }

        @Test
        @DisplayName("NON_TEXT_CONTENT: içerik türü metin değil")
        void nonText() {
            Analysis a = run("%PDF-1.7 ...", "application/pdf", "Fatura", false, 0, "GTE", 1, 200, null, 0, List.of());
            assertThat(a.hints()).contains(NON_TEXT_CONTENT);
        }

        @Test
        @DisplayName("'olmamalı' kuralında (yasak kelime bulundu) sayfa türü ipucu ÜRETİLMEZ — alıntı eşleşmenin çevresidir")
        void forbiddenHasNoHints() {
            String body = "<title>Planlı Bakım</title><p>" + "dolgu ".repeat(200) + "Sistem HATA verdi, lütfen bekleyin.</p>";
            Analysis a = run(body, "text/html", "HATA", true, 1, "LTE", 0, 200, null, 0, List.of());
            assertThat(a.hints()).isEmpty();
            assertThat(a.excerpt()).contains("HATA").startsWith("…");
        }
    }

    @Nested
    @DisplayName("normal sayfalarda yanlış ipucu YOK")
    class NoFalsePositives {
        @Test
        @DisplayName("tipik kurumsal sayfa: nav'da giriş bağlantısı, reCAPTCHA betiği, içerikte 'bakım' kelimesi, 'error' sınıfı")
        void typicalPages() {
            String page = "<!doctype html><html><head><title>Ürünler | Örnek Mağaza</title>"
                    + "<script src=\"https://www.google.com/recaptcha/api.js\"></script>"
                    + "<link rel=\"stylesheet\" href=\"/s.css\"></head><body>"
                    + "<nav><a href=\"/login\">Giriş yap</a> <a href=\"/sepet\">Sepet</a></nav>"
                    + "<main><h1>Araç bakım ürünleri</h1>" + "<p>Kaliteli ürünler, hızlı teslimat. </p>".repeat(80)
                    + "<form class=\"error-free\"><input name=\"q\" type=\"search\"></form></main>"
                    + "<footer>© 2026 Örnek</footer></body></html>";
            assertThat(notFound(page, "Kampanya").hints()).isEmpty();
            String json = "{\"status\":\"UP\",\"components\":{\"db\":{\"status\":\"UP\"}}}";
            assertThat(run(json, "application/json", "DOWN", false, 0, "GTE", 1, 200, null, 0, List.of()).hints()).isEmpty();
            String text = "plain text health: all good";
            assertThat(run(text, "text/plain", "OK", true, 0, "GTE", 1, 200, null, 0, List.of()).hints()).isEmpty();
        }

        @Test
        @DisplayName("koşul sağlandıysa (ya da adet fazla) alternatif ipuçları hesaplanmaz")
        void noAltHintsWhenNotMissing() {
            String body = "<p>tamam Tamam</p>";
            assertThat(run(body, "text/html", "Tamam", true, 1, "LTE", 0, 200, null, 0, List.of()).hints()).isEmpty();
        }
    }

    // ── Alıntı ──────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("alıntı")
    class Excerpt {
        @Test
        @DisplayName("görünür metinden, ≤ 600 karakter, sonu '…'")
        void capped() {
            String body = "<html><body><script>gizli()</script><p>" + "kelime ".repeat(500) + "</p></body></html>";
            String e = notFound(body, "Yok").excerpt();
            assertThat(e).hasSizeLessThanOrEqualTo(EXCERPT_MAX).endsWith("…").doesNotContain("gizli").doesNotContain("<p>");
        }

        @Test
        @DisplayName("sırlar maskeli: JSON parola alanı + izlemenin başlık değeri (yansıtılmış jeton)")
        void masked() {
            String body = "{\"password\":\"hunter2\",\"echo\":\"tok-ABCDEF123456\",\"msg\":\"login required\"}";
            Analysis a = run(body, "application/json", "welcome", false, 0, "GTE", 1, 401, null, 0, List.of("tok-ABCDEF123456"));
            assertThat(a.excerpt()).doesNotContain("hunter2").doesNotContain("tok-ABCDEF123456").contains("login required");
        }

        @Test
        @DisplayName("boş gövde → alıntı yok; görünür metni olmayan SPA → ham gövdenin başı")
        void emptyAndSkeleton() {
            assertThat(notFound("", "x").excerpt()).isNull();
            assertThat(notFound("<script>a()</script>", "x").excerpt()).contains("script");
        }
    }

    // ── Bağlamlar / alternatifler / yardımcılar ─────────────────────────────────────────────────

    @Test
    @DisplayName("bağlamlar: en çok 5, görünür metinden, maskeli; görünür metinde yoksa ham gövdeden (source=raw)")
    void contextsCap() {
        String body = "<p>" + "önce Fiyat sonra. ".repeat(9) + "</p>";
        List<Map<String, Object>> c = contexts(body, "fiyat", false, 5, 20, List.of());
        assertThat(c).hasSize(5);
        assertThat(c.get(0)).containsEntry("match", "Fiyat").containsEntry("source", "visible");
        List<Map<String, Object>> raw = contexts("<div data-x=\"Fiyat\"></div>", "Fiyat", true, 5, 20, List.of());
        assertThat(raw).hasSize(1);
        assertThat(raw.get(0)).containsEntry("source", "raw");
    }

    @Test
    @DisplayName("needsMore: GTE/GT/EQ'da eksik adet; LTE/LT hiçbir zaman 'eksik' değil")
    void needsMoreRule() {
        assertThat(needsMore(0, "GTE", 1)).isTrue();
        assertThat(needsMore(1, "GTE", 1)).isFalse();
        assertThat(needsMore(3, "GT", 3)).isTrue();
        assertThat(needsMore(1, "EQ", 2)).isTrue();
        assertThat(needsMore(3, "EQ", 2)).isFalse();
        assertThat(needsMore(5, "LTE", 0)).isFalse();
        assertThat(needsMore(0, null, 1)).isTrue();
    }

    @Test
    @DisplayName("displayUrl: kullanıcı bilgisi atılır, hassas sorgu değeri maskelenir")
    void displayUrlMasks() {
        String u = displayUrl(URI.create("https://user:p4ss@site.example.com:8443/a/b?token=abc123&page=2"));
        assertThat(u).isEqualTo("https://site.example.com:8443/a/b?token=*****&page=2");
        assertThat(displayUrl(null)).isNull();
    }

    @Test
    @DisplayName("declaredCharset: Content-Type parametresi, yoksa meta; bilinmeyen ad olduğu gibi")
    void declared() {
        assertThat(declaredCharset("text/html; charset=ISO-8859-9", null)).isEqualTo("ISO-8859-9");
        assertThat(declaredCharset("text/html", "<meta charset=\"windows-1254\">".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("windows-1254");
        assertThat(declaredCharset(null, "<p>yok</p>".getBytes(StandardCharsets.US_ASCII))).isNull();
    }

    @Test
    @DisplayName("bozuk girdi asla fırlatmaz")
    void neverThrows() {
        Analysis a = analyze(new Input(null, null, null, false, 0, null, 1, 200, null, null, 0, null));
        assertThat(a.hints()).isNotNull();
    }

    private static int intOf(Object o) { return o instanceof Number n ? n.intValue() : -1; }
}
