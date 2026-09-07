package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * HER RDAP ISTEMCISI KURUMSAL VEKILI KULLANMALI.
 *
 * <p><b>Nasil bulundu.</b> Uretim log'unda ayni pod, ayni ag, ayni hedef icin iki farkli sonuc
 * vardi: {@code RdapDomainClient} "proxy uzerinden" diye acilis log'u basip calisiyor,
 * {@code RdapDomainExpiryService} ise her sorguda "HTTP connect timed out" aliyordu. Kaynakta
 * ikincisinin {@code init()}'inde hic {@code ProxySelector} olmadigi gorildu.
 *
 * <p><b>Neden sessiz bir hataydi.</b> {@code SchedulerService.evalHttpDomain} suna dayaniyor:
 * "days null (unknown) → up (alarm yok)". Yani lookup KALICI olarak basarisizken HTTP ve Keyword
 * izlemelerinin alan adi bitis hatirlaticisi sonsuza kadar yesil kalir — kullanici ozelligi acmis
 * olur, ekran temiz gorunur, domain suresi dolarken hicbir uyari gelmez. Sonuc 12 saat
 * cache'lendigi icin gecikme de olusmaz; yalnizca islev olur.
 *
 * <p>Bu kapi yeni bir RDAP istemcisi vekilsiz eklenirse kirilir.
 */
class RdapProxyConfigTest {

    private static final String PROXY_HOST = "proxy.example.com";
    private static final int    PROXY_PORT = 8080;

    /** Verilen alandaki HttpClient bir ProxySelector tasiyor mu? */
    private static boolean hasProxy(Object svc, String field) {
        HttpClient c = (HttpClient) ReflectionTestUtils.getField(svc, field);
        assertThat(c).as("istemci alani kurulmamis: " + field).isNotNull();
        return c.proxy().isPresent();
    }

    private static void withProxySettings(Object svc) {
        ReflectionTestUtils.setField(svc, "proxyHost", PROXY_HOST);
        ReflectionTestUtils.setField(svc, "proxyPort", PROXY_PORT);
    }

    private static RdapDomainExpiryService newExpiryService() {
        AppSettingsService s = mock(AppSettingsService.class);
        return new RdapDomainExpiryService(s, new TrustEvaluator(s), mock(CaAutoPinService.class),
                mock(SsrfGuard.class));
    }

    private static RdapDomainClient newLookupClient() throws Exception {
        AppSettingsService s = mock(AppSettingsService.class);
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        return new RdapDomainClient(s, psl, new TrustEvaluator(s), mock(CaAutoPinService.class),
                mock(SsrfGuard.class));
    }

    @Test
    @DisplayName("RdapDomainExpiryService: proxy tanimliysa vekilli istemci kurulur")
    void expiryService_withProxy_buildsProxiedClient() {
        RdapDomainExpiryService svc = newExpiryService();
        withProxySettings(svc);
        svc.init();

        assertThat(hasProxy(svc, "proxied"))
                .as("alan adi bitis sorgusu vekili atliyor - kurumsal agda sessizce zaman asimina ugrar")
                .isTrue();
    }

    @Test
    @DisplayName("RdapDomainClient: proxy tanimliysa vekilli istemci kurulur (kardes sozlesme)")
    void lookupClient_withProxy_buildsProxiedClient() throws Exception {
        RdapDomainClient c = newLookupClient();
        withProxySettings(c);
        c.init();

        assertThat(hasProxy(c, "proxied")).isTrue();
    }

    @Test
    @DisplayName("Proxy TANIMSIZSA iki istemci de dogrudan cikar (yapilandirma zorlanmaz)")
    void noProxyConfigured_bothFallBackToDirect() throws Exception {
        RdapDomainExpiryService svc = newExpiryService();
        svc.init();
        assertThat(hasProxy(svc, "proxied")).isFalse();

        RdapDomainClient c = newLookupClient();
        c.init();
        assertThat(hasProxy(c, "proxied")).isFalse();
    }

    @Test
    @DisplayName("NO_PROXY eslesen host DOGRUDAN cikar (ic RDAP aynasi vekile gitmesin)")
    void noProxyList_matchingHost_bypassesProxy() {
        RdapDomainExpiryService svc = newExpiryService();
        withProxySettings(svc);
        ReflectionTestUtils.setField(svc, "noProxyList", ".internal.example.com, other.example.org");
        svc.init();

        // clientFor: eslesen host -> dogrudan istemci, digerleri -> vekilli
        HttpClient direct  = (HttpClient) ReflectionTestUtils.getField(svc, "http");
        HttpClient chosenInternal =
                (HttpClient) ReflectionTestUtils.invokeMethod(svc, "clientFor", "rdap.internal.example.com");
        HttpClient chosenExternal =
                (HttpClient) ReflectionTestUtils.invokeMethod(svc, "clientFor", "rdap.example.net");

        assertThat(chosenInternal).as("NO_PROXY eslesmesine ragmen vekil secildi").isSameAs(direct);
        assertThat(chosenExternal).as("dis host dogrudan cikiyor").isNotSameAs(direct);
    }
}
