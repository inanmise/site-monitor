package com.sitemonitor.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TrWebWhoisClient} saf ayrıştırma yardımcıları (ağsız) + çıkarılan metnin gerçek {@link TrWhoisParser}
 * ile beklenen alanlara çözülmesi. HTML örnekleri iki gerçek düzeni temsil eder: TRABIS {@code <pre>} ve
 * isimtescil {@code <p>...<br/>...</p>}. Süre bitişi her ikisinden de {@code 2029-10-26} olarak gelmelidir.
 */
class TrWebWhoisClientTest {

    // TRABIS (BTK) resmi sayfası — sonuç <pre> bloğu içinde (gerçek çıktı).
    private static final String TRABIS_HTML = """
            <div class="container">
                <pre>** Domain Name: wingscard.com.tr
            Domain Status: Active
            Transfer Status: The domain is LOCKED to transfer.

            ** Registrar:
            NIC Handle\t\t: itt46
            Organization Name\t: İHS KURUMSAL TEKNOLOJİ HİZMETLERİ ANONİM ŞİRKETİ

            ** Domain Servers:
            ns1.akbank.com.tr
            srv.akbank.com.tr

            ** Additional Info:
            Created on..............: 2006-Oct-27.
            Expires on..............: 2029-Oct-26.

            ** Whois Server:
            Last Update Time: 2026-07-25T00:31:44+03:00
            </pre>
            </div>""";

    // isimtescil.net — sonuç <p> içinde <br/> ile satırlanmış (gerçek çıktı).
    private static final String ISIMTESCIL_HTML =
            "<h2 class=\"fs-4\">wingscard.com.tr<span> Whois Sonucu</span></h2>"
            + "<p class=\"fw-normal fs-8 \">"
            + "** Domain Name: wingscard.com.tr<br/>Domain Status: Active<br/>"
            + "Transfer Status: The domain is LOCKED to transfer.<br/><br/>"
            + "** Registrar:<br/>NIC Handle\t\t: itt46<br/>"
            + "Organization Name\t: İHS KURUMSAL TEKNOLOJİ HİZMETLERİ ANONİM ŞİRKETİ<br/><br/>"
            + "** Domain Servers:<br/>ns1.akbank.com.tr<br/>srv.akbank.com.tr<br/><br/>"
            + "** Additional Info:<br/>Created on..............: 2006-Oct-27.<br/>"
            + "Expires on..............: 2029-Oct-26.<br/><br/>"
            + "** Whois Server:<br/>Last Update Time: 2026-07-25T00:47:01+03:00<br/></p>";

    @Test
    void extractToken_readsLaravelCsrfHiddenField() {
        String html = "<form><input type=\"hidden\" name=\"_token\" value=\"RudihOp4PzsbMjDY123\"></form>";
        assertThat(TrWebWhoisClient.extractToken(html)).isEqualTo("RudihOp4PzsbMjDY123");
        assertThat(TrWebWhoisClient.extractToken("<form>no token here</form>")).isNull();
        assertThat(TrWebWhoisClient.extractToken(null)).isNull();
    }

    @Test
    void extractWhois_fromTrabisPreBlock_parsesToExpiry() {
        String raw = TrWebWhoisClient.extractWhois(TRABIS_HTML);
        assertThat(raw).isNotNull();
        assertThat(raw).contains("Expires on..............: 2029-Oct-26.");
        assertThat(raw).doesNotContain("<pre>").doesNotContain("</div>");

        Map<String, Object> info = new TrWhoisParser().parse(raw);
        assertThat(info.get("expiry_date")).isEqualTo("2029-10-26");
        assertThat(info.get("registration_date")).isEqualTo("2006-10-27");
        assertThat((String) info.get("registrar")).contains("İHS KURUMSAL");
        @SuppressWarnings("unchecked")
        List<String> ns = (List<String>) info.get("nameservers");
        assertThat(ns).contains("ns1.akbank.com.tr", "srv.akbank.com.tr");
    }

    @Test
    void extractWhois_fromIsimtescilBrBlock_parsesToExpiry() {
        String raw = TrWebWhoisClient.extractWhois(ISIMTESCIL_HTML);
        assertThat(raw).isNotNull();
        assertThat(raw).doesNotContain("<br").doesNotContain("</p>");
        // <br/> → satır sonu olmalı ki parser satır-bazlı çözebilsin.
        assertThat(raw).contains("\n");

        Map<String, Object> info = new TrWhoisParser().parse(raw);
        assertThat(info.get("expiry_date")).isEqualTo("2029-10-26");
        assertThat((String) info.get("registrar")).contains("İHS KURUMSAL");
        @SuppressWarnings("unchecked")
        List<String> ns = (List<String>) info.get("nameservers");
        assertThat(ns).contains("ns1.akbank.com.tr");
    }

    @Test
    void extractWhois_noWhoisBlock_returnsNull() {
        assertThat(TrWebWhoisClient.extractWhois("<html><body>hiç whois yok</body></html>")).isNull();
        assertThat(TrWebWhoisClient.extractWhois(null)).isNull();
    }

    @Test
    void init_withProxyAuth_authenticatorOnProxiedClientOnly() {
        // Proxy user doluysa yalnız proxied client'a authenticator takılır (auth'lu proxy'de 407 sessiz ölümü önlenir).
        String origProp = System.getProperty(ProxyAuthSupport.TUNNELING_PROP);
        TrWebWhoisClient c = null;
        try {
            AppSettingsService appSettings = org.mockito.Mockito.mock(AppSettingsService.class);
            c = new TrWebWhoisClient(appSettings, new TrustEvaluator(appSettings),
                    org.mockito.Mockito.mock(CaAutoPinService.class));
            org.springframework.test.util.ReflectionTestUtils.setField(c, "proxyHost", "proxy.local");
            org.springframework.test.util.ReflectionTestUtils.setField(c, "proxyPort", 8080);
            org.springframework.test.util.ReflectionTestUtils.setField(c, "proxyUser", "svc-mon");
            org.springframework.test.util.ReflectionTestUtils.setField(c, "proxyPass", "pw");
            c.init();
            var proxied = (java.net.http.HttpClient) org.springframework.test.util.ReflectionTestUtils.getField(c, "proxiedClient");
            var direct  = (java.net.http.HttpClient) org.springframework.test.util.ReflectionTestUtils.getField(c, "directClient");
            assertThat(proxied.authenticator()).isPresent();
            assertThat(direct.authenticator()).isEmpty();   // proxy kimliği doğrudan çıkışa sızmaz
        } finally {
            if (c != null) c.close();
            if (origProp == null) System.clearProperty(ProxyAuthSupport.TUNNELING_PROP);
            else System.setProperty(ProxyAuthSupport.TUNNELING_PROP, origProp);
        }
    }
}
