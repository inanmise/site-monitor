package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** PSL kayıtlı-domain (eTLD+1) çıkarımı + IDN punycode. */
class PublicSuffixServiceTest {

    static PublicSuffixService psl;

    @BeforeAll
    static void load() {
        psl = new PublicSuffixService();
        psl.load();
    }

    @Test
    @DisplayName("subdomain → kayıtlı domain'e indirger")
    void subdomain() {
        assertThat(psl.registrableDomain("www.akbank.com")).isEqualTo("akbank.com");
        assertThat(psl.registrableDomain("https://a.b.example.com/path?q=1")).isEqualTo("example.com");
        assertThat(psl.registrableDomain("example.com")).isEqualTo("example.com");
    }

    @Test
    @DisplayName(".tr ikinci-seviye ekleri (com.tr) doğru")
    void trSecondLevel() {
        assertThat(psl.registrableDomain("www.akbank.com.tr")).isEqualTo("akbank.com.tr");
        assertThat(psl.registrableDomain("portal.dev.firma.org.tr")).isEqualTo("firma.org.tr");
        // Kullanıcı tam URL yapıştırırsa da domain kısmı çıkarılmalı (şema + www + trailing slash).
        assertThat(psl.registrableDomain("https://www.wingscard.com.tr/")).isEqualTo("wingscard.com.tr");
        assertThat(psl.tldOf("https://www.wingscard.com.tr/")).isEqualTo("tr");
    }

    @Test
    @DisplayName("co.uk çok-parçalı ek")
    void coUk() {
        assertThat(psl.registrableDomain("shop.example.co.uk")).isEqualTo("example.co.uk");
    }

    @Test
    @DisplayName("listede olmayan TLD → son iki etikete düşer (varsayılan '*')")
    void unknownTldFallback() {
        assertThat(psl.registrableDomain("foo.bar.zzznew")).isEqualTo("bar.zzznew");
    }

    @Test
    @DisplayName("IDN → punycode")
    void idnPunycode() {
        assertThat(psl.registrableDomain("münchen.de")).isEqualTo("xn--mnchen-3ya.de");
    }

    @Test
    @DisplayName("geçersiz giriş → null")
    void invalid() {
        assertThat(psl.registrableDomain(null)).isNull();
        assertThat(psl.registrableDomain("")).isNull();
    }

    @Test
    @DisplayName("tldOf son etiketi verir")
    void tldOf() {
        assertThat(psl.tldOf("www.akbank.com.tr")).isEqualTo("tr");
        assertThat(psl.tldOf("example.com")).isEqualTo("com");
    }
}
