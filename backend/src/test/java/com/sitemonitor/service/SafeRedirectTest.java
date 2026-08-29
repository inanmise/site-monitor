package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;

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

    @Test
    @DisplayName("Dize aşırı yüklemesi URI sürümüyle aynı kararı verir")
    void stringOverload_matchesUriOverload() {
        assertThat(SafeRedirect.nextHop("https://example.com/a/b", "/x")).isEqualTo("https://example.com/x");
        assertThat(SafeRedirect.nextHop("https://example.com/a/b", "file:///etc/passwd")).isNull();
        assertThat(SafeRedirect.nextHop("::bozuk::", "/x")).isNull();
    }
}
