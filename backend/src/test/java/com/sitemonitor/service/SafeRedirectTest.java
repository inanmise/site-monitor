package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Yönlendirme politikasının kapısı.
 *
 * <p>Bu kurallar üç ayrı checker tarafından paylaşılıyor (keyword / HTTP / HSTS) + sayfa çekme
 * çekirdeği. Politika tek yerde yaşasın diye ayrıldı; burada kırılan bir kural dördünde birden
 * kırılıyor demektir.
 */
class SafeRedirectTest {

    private static final URI BASE = URI.create("https://example.com/a/b");

    @Test
    @DisplayName("nextHop: göreli Location mevcut URL'e göre çözülür")
    void nextHop_relativeResolved() {
        assertThat(SafeRedirect.nextHop(BASE, "/x")).hasToString("https://example.com/x");
        assertThat(SafeRedirect.nextHop(BASE, "c")).hasToString("https://example.com/a/c");
        assertThat(SafeRedirect.nextHop(BASE, "https://other.example.com/y"))
                .hasToString("https://other.example.com/y");
    }

    @Test
    @DisplayName("nextHop: http/https DIŞI şemalar takip EDİLMEZ (file/gopher/ftp/jar)")
    void nextHop_rejectsNonHttpSchemes() {
        // file: yerel dosya okumaya açılırdı; host'suz olduğu için SsrfGuard da devreye giremezdi.
        assertThat(SafeRedirect.nextHop(BASE, "file:///etc/passwd")).isNull();
        assertThat(SafeRedirect.nextHop(BASE, "gopher://example.com/1")).isNull();
        assertThat(SafeRedirect.nextHop(BASE, "ftp://example.com/f")).isNull();
        assertThat(SafeRedirect.nextHop(BASE, "jar:file:///a.jar!/b")).isNull();
    }

    @Test
    @DisplayName("nextHop: host'suz / boş / bozuk Location takip EDİLMEZ")
    void nextHop_rejectsHostlessAndBlank() {
        assertThat(SafeRedirect.nextHop(BASE, null)).isNull();
        assertThat(SafeRedirect.nextHop(BASE, "")).isNull();
        assertThat(SafeRedirect.nextHop(BASE, "   ")).isNull();
        assertThat(SafeRedirect.nextHop(BASE, "http:///onlypath")).isNull();   // host yok
    }

    @Test
    @DisplayName("nextHop: metadata/iç adresler POLİTİKA reddi DEĞİL — burada geçer, SsrfGuard durdurur")
    void nextHop_leavesAddressPolicyToSsrfGuard() {
        // Sorumluluk ayrımı: SafeRedirect "bu URL takip edilebilir mi", SsrfGuard "bu adrese
        // çıkılabilir mi" sorusunu yanıtlar. İkisini karıştırmak adres politikasını iki yere kopyalardı.
        assertThat(SafeRedirect.nextHop(BASE, "http://169.254.169.254/latest/meta-data/"))
                .hasToString("http://169.254.169.254/latest/meta-data/");
    }

    @Test
    @DisplayName("isDowngrade: https → düz http düşürmesi işaretlenir (Redirect.NORMAL davranışı)")
    void isDowngrade_flagsHttpsToHttp() {
        assertThat(SafeRedirect.isDowngrade(BASE, URI.create("http://example.com/x"))).isTrue();
        assertThat(SafeRedirect.isDowngrade(BASE, URI.create("https://example.com/x"))).isFalse();
        assertThat(SafeRedirect.isDowngrade(URI.create("http://example.com/"),
                URI.create("https://example.com/"))).isFalse();   // yükseltme sorun değil
    }

    @Test
    @DisplayName("nextMethod: 303 → GET; diğer 3xx metodu KORUR")
    void nextMethod_303BecomesGet() {
        assertThat(SafeRedirect.nextMethod(303, "HEAD")).isEqualTo("GET");
        assertThat(SafeRedirect.nextMethod(303, "POST")).isEqualTo("GET");
        assertThat(SafeRedirect.nextMethod(302, "HEAD")).isEqualTo("HEAD");
        assertThat(SafeRedirect.nextMethod(301, "POST")).isEqualTo("POST");
        assertThat(SafeRedirect.nextMethod(302, null)).isEqualTo("GET");
    }

    @Test
    @DisplayName("isRedirect: yalnız 3xx")
    void isRedirect_only3xx() {
        assertThat(SafeRedirect.isRedirect(299)).isFalse();
        assertThat(SafeRedirect.isRedirect(300)).isTrue();
        assertThat(SafeRedirect.isRedirect(308)).isTrue();
        assertThat(SafeRedirect.isRedirect(399)).isTrue();
        assertThat(SafeRedirect.isRedirect(400)).isFalse();
        assertThat(SafeRedirect.isRedirect(200)).isFalse();
    }

    @Test
    @DisplayName("MAX_HOPS makul bir üst sınır — sonsuz döngü kapısı")
    void maxHops_isBounded() {
        assertThat(SafeRedirect.MAX_HOPS).isBetween(1, 10);
    }

    // ── Location tarayıcı gibi okunur (2026-10-30, akyatirim.com.tr yanlış alarmı) ─────────────

    /** Sunucunun ham UTF-8 baytları — Java'nın HTTP istemcileri bunları ISO-8859-1 okuyup karaktere çevirir. */
    private static String asJavaReadsIt(String utf8) {
        return new String(utf8.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("Ham UTF-8 Location (\"yurt-ıci\") tarayıcı gibi çözülür — %C4%B1, %C3%84%C2%B1 DEĞİL")
    void rawUtf8Location_decodedLikeABrowser() {
        URI cur = URI.create("https://www.akyatirim.com.tr/tr/tradeall/yurt-%C4%B1ci/");
        String loc = asJavaReadsIt("https://www.akyatirim.com.tr/tr/tradeall/yurt-ıci");
        assertThat(loc).isEqualTo("https://www.akyatirim.com.tr/tr/tradeall/yurt-Ä±ci");   // prod'da görülen
        assertThat(SafeRedirect.nextHop(cur, loc))
                .hasToString("https://www.akyatirim.com.tr/tr/tradeall/yurt-%C4%B1ci");
        // göreli Location da aynı
        assertThat(SafeRedirect.nextHop(cur, asJavaReadsIt("/tr/tradeall/yurt-ıci")))
                .hasToString("https://www.akyatirim.com.tr/tr/tradeall/yurt-%C4%B1ci");
        // bütün Türkçe harfler, sorgu ve parça dahil: ş ç ğ ö ü İ ı ? q=Ü # ç
        assertThat(SafeRedirect.nextHop(BASE,
                asJavaReadsIt("/şçğöüİı?q=Ü#ç")))
                .hasToString("https://example.com/%C5%9F%C3%A7%C4%9F%C3%B6%C3%BC%C4%B0%C4%B1?q=%C3%9C#%C3%A7");
        // 4 baytlık karakter (emoji)
        assertThat(SafeRedirect.nextHop(BASE, asJavaReadsIt("/a😀")))
                .hasToString("https://example.com/a%F0%9F%98%80");
    }

    @Test
    @DisplayName("Gerçekten ISO-8859-1 yazan sunucu (tek E9 baytı = é) bugünkü gibi é olarak gider")
    void genuineLatin1Location_keptAsLatin1() {
        // E9 tek başına geçerli UTF-8 değil → Latin-1 okuması kalır; HttpClient de bugün %C3%A9 gönderiyordu.
        assertThat(SafeRedirect.nextHop(BASE, "/café")).hasToString("https://example.com/caf%C3%A9");
        // geçerli ve geçersiz baytlar karışıksa metin bütünüyle Latin-1 kalır (yarım çözüm yok)
        assertThat(SafeRedirect.nextHop(BASE, "/Ä±-é"))
                .hasToString("https://example.com/%C3%84%C2%B1-%C3%A9");
    }

    @Test
    @DisplayName("ASCII / yüzde kodlanmış Location DEĞİŞMEZ; zaten Unicode metin UTF-8 kodlanır")
    void asciiUnchanged_unicodeEncoded() {
        assertThat(SafeRedirect.nextHop(BASE, "/tr/tradeall/yurt-%C4%B1ci?x=%20y"))
                .hasToString("https://example.com/tr/tradeall/yurt-%C4%B1ci?x=%20y");
        assertThat(SafeRedirect.browserLocation("  /x  ")).isEqualTo("/x");
        // 0xFF üstü karakter = bayt değil, metin → yeniden çözülmez, doğrudan UTF-8 kodlanır
        assertThat(SafeRedirect.nextHop(BASE, "/yurt-ıci")).hasToString("https://example.com/yurt-%C4%B1ci");
        assertThat(SafeRedirect.nextHop(BASE, "/a😀")).hasToString("https://example.com/a%F0%9F%98%80");
    }

    @Test
    @DisplayName("Dönen URI her zaman yalnız ASCII; ASCII olmayan HOST bugünkü gibi takip edilmez")
    void resultIsAscii_nonAsciiHostStillRejected() {
        URI n = SafeRedirect.nextHop(BASE, asJavaReadsIt("/ğ/ü?ş=1"));
        assertThat(n.toString()).isEqualTo(n.toASCIIString());
        assertThat(n.getHost()).isEqualTo("example.com");
        // ham host yüzde kodlanınca ayrıştırılamaz → host yok → takip yok (SsrfGuard'sız kalınmaz)
        assertThat(SafeRedirect.nextHop(BASE, asJavaReadsIt("https://bücher.de/"))).isNull();
        assertThat(SafeRedirect.nextHop(BASE, "https://bücher.de/")).isNull();
        // eşleşmeyen vekil karakter çökertmez
        assertThat(SafeRedirect.nextHop(BASE, "/a\uD83D")).hasToString("https://example.com/a%3F");
        // ham NBSP (C2 A0) eskiden URI.create'i patlatıp takibi durduruyordu; artık kodlanıp takip edilir
        assertThat(SafeRedirect.nextHop(BASE, asJavaReadsIt("/a b"))).hasToString("https://example.com/a%C2%A0b");
    }

    @Test
    @DisplayName("Okunur gösterim (tanı ekranı): ham UTF-8 geri çevrilir, yüzde kodlama yapılmaz; ASCII / Latin-1 / null olduğu gibi")
    void readableLocation_forDisplay() {
        assertThat(SafeRedirect.readableLocation(asJavaReadsIt("/tr/tradeall/yurt-ıci"))).isEqualTo("/tr/tradeall/yurt-ıci");
        assertThat(SafeRedirect.readableLocation("  /a?b=%20c  ")).isEqualTo("/a?b=%20c");
        assertThat(SafeRedirect.readableLocation("/café")).as("gerçek Latin-1 kalır").isEqualTo("/café");
        assertThat(SafeRedirect.readableLocation("/yurt-ıci")).as("zaten Unicode").isEqualTo("/yurt-ıci");
        assertThat(SafeRedirect.readableLocation(null)).isNull();
    }

    @Test
    @DisplayName("Dize aşırı yüklemesi URI sürümüyle aynı kararı verir")
    void stringOverload_matchesUriOverload() {
        assertThat(SafeRedirect.nextHop("https://example.com/a/b", "/x")).isEqualTo("https://example.com/x");
        assertThat(SafeRedirect.nextHop("https://example.com/a/b", "file:///etc/passwd")).isNull();
        assertThat(SafeRedirect.nextHop("::bozuk::", "/x")).isNull();
        assertThat(SafeRedirect.nextHop("https://example.com/a/b", "/yurt-Ä±ci"))
                .isEqualTo("https://example.com/yurt-%C4%B1ci");
    }
}
