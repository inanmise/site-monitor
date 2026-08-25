package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vekil (proxy) kararının TEK doğruluk kaynağı — ve iki yolun ayrışmadığının kanıtı.
 *
 * <p>Yaşanan arıza: HSTS tanılaması kararı YALNIZ global yapılandırmadan türetiyordu, izlemenin
 * kendi {@code use_proxy} tercihini hiç görmüyordu. Aynı domain için sertifika kontrolü doğrudan
 * bağlanıp başlığı buluyor (SSL sekmesi "HSTS etkin"), sağlık probu ise vekilden geçmeye çalışıp
 * bağlanamıyor ve satır "Doğrulanamadı" kalıyordu. Kural iki yerde yazılıydı; biri güncellenip
 * diğeri unutulunca sapma sessizce oluştu.
 *
 * <p>Bu yüzden asıl kapı aşağıdaki {@link #noDrift} testidir: kuralın ikinci bir kopyası
 * yeniden yazılırsa aynı girdilerde farklı cevap verdiği anda kırmızıya döner.
 */
class ProxyDecisionTest {

    private static ProxySettings settings(String host, int port, String noProxy) {
        ProxySettings p = new ProxySettings();
        ReflectionTestUtils.setField(p, "host", host);
        ReflectionTestUtils.setField(p, "port", port);
        ReflectionTestUtils.setField(p, "noProxy", noProxy);
        return p;
    }

    private static ProxySettings configured() {
        return settings("proxy.example.com", 8080, "");
    }

    @Test
    @DisplayName("İzleme DOĞRUDAN istiyorsa vekil yapılandırılmış olsa da kullanılmaz")
    void monitorPrefersDirect_wins() {
        // Kullanıcının ekranda gördüğü "Proxy Üzerinden Kontrol Et = Hayır" tercihi budur;
        // yok sayılması arızanın ta kendisiydi.
        assertThat(configured().useFor("a.example.com", false)).isFalse();
    }

    @Test
    @DisplayName("İzleme vekil istese de vekil yapılandırılmamışsa doğrudan çıkılır")
    void proxyNotConfigured_direct() {
        assertThat(settings("", 0, "").useFor("a.example.com", true)).isFalse();
        assertThat(settings("proxy.example.com", 0, "").useFor("a.example.com", true)).isFalse();
    }

    @Test
    @DisplayName("Üçü de uygunsa vekil kullanılır")
    void allConditionsMet_proxy() {
        assertThat(configured().useFor("a.example.com", true)).isTrue();
    }

    @ParameterizedTest(name = "NO_PROXY ''{0}'' → {1} baypas: {2}")
    @CsvSource({
            "example.com,        a.example.com,   true",     // alt alan
            "example.com,        example.com,     true",     // tam eşleşme
            ".example.com,       a.example.com,   true",     // nokta önekli sonek
            ".example.com,       example.com,     true",     // nokta öneki kökü de kapsar
            "example.com,        notexample.com,  false",    // sonek benzerliği eşleşme DEĞİL
            "other.com,          a.example.com,   false",
            "'a.com , example.com', a.example.com, true",     // virgüllü liste, boşluklu
            "'',                 a.example.com,   false",
    })
    void bypassList(String noProxy, String domain, boolean expected) {
        assertThat(settings("proxy.example.com", 8080, noProxy).bypass(domain)).isEqualTo(expected);
    }

    @Test
    @DisplayName("NO_PROXY kapsıyorsa izleme vekil istese bile doğrudan çıkılır")
    void noProxyList_forcesDirect() {
        assertThat(settings("proxy.example.com", 8080, "example.com")
                .useFor("a.example.com", true)).isFalse();
    }

    /**
     * SAPMA KAPISI — asıl kapı.
     *
     * <p>{@code CertificateCheckerService.resolveOptions} ile {@link ProxySettings#useFor} aynı
     * girdilerde AYNI kararı vermek ZORUNDA. Bugün ilki ikincisine devrediyor; biri kopyalanıp
     * ayrışırsa "aynı domain, iki farklı cevap" arızası aynen geri gelir ve bu test onu yakalar.
     */
    @Test
    @DisplayName("Sertifika kontrolü ile paylaşılan kural AYNI kararı verir (sapma yok)")
    void noDrift() {
        for (String noProxy : new String[]{"", "example.com", ".example.com", "other.com"}) {
            for (String host : new String[]{"", "proxy.example.com"}) {
                for (String domain : new String[]{"a.example.com", "example.com", "b.other.com"}) {
                    for (boolean force : new boolean[]{true, false}) {
                        ProxySettings p = settings(host, host.isEmpty() ? 0 : 8080, noProxy);
                        CertificateCheckerService checker = new CertificateCheckerService(
                                null, null, null, null, null, p);
                        ReflectionTestUtils.setField(checker, "proxyHost", host);
                        ReflectionTestUtils.setField(checker, "proxyPort", host.isEmpty() ? 0 : 8080);
                        ReflectionTestUtils.setField(checker, "noProxyList", noProxy);
                        ReflectionTestUtils.setField(checker, "tlsMode", "browser");

                        boolean fromChecker = checker.resolveOptions(force, null, domain).viaProxy();
                        boolean fromSettings = p.useFor(domain, force);

                        assertThat(fromChecker)
                                .as("sapma: no_proxy='%s' host='%s' domain='%s' force=%s",
                                        noProxy, host, domain, force)
                                .isEqualTo(fromSettings);
                    }
                }
            }
        }
    }
}
