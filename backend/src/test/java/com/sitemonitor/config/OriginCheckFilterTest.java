package com.sitemonitor.config;

import com.sitemonitor.service.AppSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CSRF ikinci katmanı — Origin/Referer doğrulaması (prod kapısı 2026-09-25, O-4).
 *
 * <p>SameSite=Strict tek savunmaydı ve kardeş alt alan adını (aynı SİTE) durdurmuyordu. Bu testler
 * hem reddi (kardeş köken, "null" köken) hem de YANLIŞ RED olmamasını (aynı köken, vekil arkası,
 * tarayıcı dışı istemci, geliştirme kökeni) pinler — yanlış ret tek pod'da tüm yazmaları durdururdu.
 */
class OriginCheckFilterTest {

    private static final String BASE = "https://sitemonitor.example.com";

    private static OriginCheckFilter filter() {
        return new OriginCheckFilter(null, true, BASE, "http://localhost:5173");
    }

    private static MockHttpServletRequest post(String path) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setServerName("app-pod");
        r.addHeader("Host", "app-pod:8080");
        return r;
    }

    /** Filtreden geçti mi (zincire ulaştı mı) — yanıt 403 değil ve zincir çağrıldı. */
    private static boolean passes(OriginCheckFilter f, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
        return chain.getRequest() != null && res.getStatus() != 403;
    }

    @Test
    @DisplayName("Aynı köken: Origin, isteğin Host'uyla eşleşir → geçer")
    void sameOrigin_hostHeader_allowed() throws Exception {
        MockHttpServletRequest r = post("/api/admin/inventory/bulk");
        r.removeHeader("Host");
        r.addHeader("Host", "sitemonitor.example.com");          // TLS'i vekil sonlandırdı, port yok
        r.addHeader("Origin", "https://sitemonitor.example.com");
        assertThat(passes(filter(), r)).isTrue();
    }

    @Test
    @DisplayName("Vekil arkası: Host iç ad, X-Forwarded-Host dış ad → geçer")
    void forwardedHost_allowed() throws Exception {
        MockHttpServletRequest r = post("/api/admin/users/5/unlock");
        r.addHeader("X-Forwarded-Host", "portal.example.com, ic-vekil");
        r.addHeader("Origin", "https://portal.example.com");
        assertThat(passes(filter(), r)).isTrue();
    }

    @Test
    @DisplayName("Yapılandırılmış taban adres (site.monitor.app.base-url) → geçer; Referer yedeği de işler")
    void baseUrl_andRefererFallback_allowed() throws Exception {
        MockHttpServletRequest r = post("/api/scheduler/run");
        r.addHeader("Referer", "https://sitemonitor.example.com/?tab=health");   // Origin yok → Referer
        assertThat(passes(filter(), r)).isTrue();
    }

    @Test
    @DisplayName("Taban adres AppSettings'ten CANLI okunur (Genel Ayarlar değişikliği yeniden başlatmasız)")
    @SuppressWarnings("unchecked")
    void baseUrl_readLiveFromSettings() throws Exception {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getString(eq("site.monitor.app.base-url"), any())).thenReturn("https://yeni.example.com");
        when(s.getCsv(eq("site.monitor.cors.allowed-origins"), any())).thenReturn(List.of());
        ObjectProvider<AppSettingsService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(s);
        OriginCheckFilter f = new OriginCheckFilter(p, true, BASE, "");

        MockHttpServletRequest ok = post("/api/x");
        ok.addHeader("Origin", "https://yeni.example.com");
        assertThat(passes(f, ok)).isTrue();

        MockHttpServletRequest old = post("/api/x");
        old.addHeader("Origin", BASE);   // eski yapılandırma değeri artık geçerli değil
        assertThat(passes(f, old)).isFalse();
    }

    @Test
    @DisplayName("Geliştirme: CORS izinli köken (Vite :5173 → :8080) → geçer")
    void corsOrigin_allowed() throws Exception {
        MockHttpServletRequest r = post("/api/x");
        r.addHeader("Origin", "http://localhost:5173");
        assertThat(passes(filter(), r)).isTrue();
    }

    @Test
    @DisplayName("KAPI: kardeş alt alan adı (aynı SİTE, farklı köken) → 403, zincire ULAŞMAZ")
    void siblingSubdomain_rejected() throws Exception {
        MockHttpServletRequest r = post("/api/admin/permissions/reset-to-defaults");
        r.addHeader("Origin", "https://evil.example.com");
        r.addHeader("Sec-Fetch-Site", "same-site");               // tarayıcı: aynı site ama AYNI KÖKEN DEĞİL
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter().doFilter(r, res, chain);
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).as("uç çalışmamalı").isNull();
        assertThat(res.getContentAsString()).contains("\"success\":false");
        // 2026-10-08: kararlı kod + istek dilinde açıklayıcı metin (ne oldu · ne yapmalı)
        assertThat(res.getContentAsString()).contains("\"code\":\"ORIGIN_MISMATCH\"").contains("güvenlik denetiminden geçemedi");
    }

    @Test
    @DisplayName("ret gövdesi: X-Lang en → İngilizce; istek kimliği varsa request_id; geçerli JSON")
    void rejectionBody_languageAndRequestId() throws Exception {
        MockHttpServletRequest r = post("/api/x");
        r.addHeader("X-Lang", "en");
        r.setAttribute(CorrelationIdFilter.ATTR, "rid-7");
        String body = OriginCheckFilter.rejectionBody(r);
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("code").asText()).isEqualTo("ORIGIN_MISMATCH");
        assertThat(json.get("error").asText()).startsWith("The request failed a security check");
        assertThat(json.get("request_id").asText()).isEqualTo("rid-7");
    }

    @Test
    @DisplayName("KAPI: aynı host, FARKLI port (başka uygulama) → 403; 'null' köken → 403")
    void otherPortAndNullOrigin_rejected() throws Exception {
        MockHttpServletRequest r = post("/api/x");
        r.removeHeader("Host");
        r.addHeader("Host", "sitemonitor.example.com");
        r.addHeader("Origin", "https://sitemonitor.example.com:8443");
        assertThat(passes(filter(), r)).isFalse();

        MockHttpServletRequest n = post("/api/x");
        n.addHeader("Origin", "null");
        assertThat(passes(filter(), n)).isFalse();
    }

    @Test
    @DisplayName("Origin de Referer de YOK (tarayıcı dışı istemci: curl, entegrasyon) → geçer")
    void missingHeaders_allowed() throws Exception {
        assertThat(passes(filter(), post("/api/x"))).isTrue();
    }

    @Test
    @DisplayName("Sec-Fetch-Site: same-origin → Host vekilce yeniden yazılmış olsa da geçer (yanlış ret yok)")
    void secFetchSameOrigin_allowed() throws Exception {
        MockHttpServletRequest r = post("/api/x");
        r.addHeader("Origin", "https://dis-ad.example.com");     // Host/XFH/taban adresle eşleşmiyor
        r.addHeader("Sec-Fetch-Site", "same-origin");
        assertThat(passes(filter(), r)).isTrue();
    }

    @Test
    @DisplayName("Kapsam: GET / API dışı yol / e-posta onay token ucu denetlenmez; anahtar kapalıyken hiçbir şey")
    void scope_andSwitch() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/x");
        get.addHeader("Origin", "https://evil.example.com");
        assertThat(passes(filter(), get)).isTrue();

        MockHttpServletRequest nonApi = new MockHttpServletRequest("POST", "/login-form");
        nonApi.addHeader("Origin", "https://evil.example.com");
        assertThat(passes(filter(), nonApi)).isTrue();

        MockHttpServletRequest token = post("/api/weekly-reports/approve-link/confirm");
        token.addHeader("Origin", "https://evil.example.com");
        assertThat(passes(filter(), token)).isTrue();

        MockHttpServletRequest off = post("/api/x");
        off.addHeader("Origin", "https://evil.example.com");
        assertThat(passes(new OriginCheckFilter(null, false, BASE, ""), off)).isTrue();

        MockHttpServletRequest del = new MockHttpServletRequest("DELETE", "/api/x");
        del.addHeader("Origin", "https://evil.example.com");
        assertThat(passes(filter(), del)).isFalse();
    }

    @Test
    @DisplayName("BK1: matris parametreli / yüzde kodlu API yolu Origin denetimini ATLAYAMAZ (kardeş köken → 403)")
    void pathVariants_cannotSkipOriginCheck() throws Exception {
        for (String p : new String[]{"/api;x/admin/permissions/reset-to-defaults", "/%61pi/admin/users/5/unlock",
                "//api/scheduler/run"}) {
            MockHttpServletRequest r = post(p);
            r.addHeader("Origin", "https://evil.example.com");
            assertThat(passes(filter(), r)).as(p).isFalse();
        }
        // Onay-bağlantısı muafiyeti de normalize yolla eşlenir (token uçları tarayıcı oturumu taşımaz).
        MockHttpServletRequest token = post("/api/weekly-reports/approve-link/confirm;x");
        token.addHeader("Origin", "https://evil.example.com");
        assertThat(passes(filter(), token)).isTrue();
    }

    @Test
    @DisplayName("Host eşlemesi: portsuz Host varsayılan portu kabul eder, IPv6 köşeli ayraç çözülür")
    void hostHeaderParsing() {
        OriginCheckFilter.Endpoint https = OriginCheckFilter.Endpoint.parse("https://a.example.com");
        assertThat(https.matchesHostHeader("a.example.com")).isTrue();
        assertThat(https.matchesHostHeader("A.Example.com:443")).isTrue();
        assertThat(https.matchesHostHeader("a.example.com:80")).isFalse();
        assertThat(https.matchesHostHeader("b.example.com")).isFalse();
        OriginCheckFilter.Endpoint v6 = OriginCheckFilter.Endpoint.parse("http://[::1]:8080");
        assertThat(v6.matchesHostHeader("[::1]:8080")).isTrue();
        assertThat(v6.matchesHostHeader("[::1]:9090")).isFalse();
        assertThat(OriginCheckFilter.Endpoint.parse("javascript:alert(1)")).isNull();
    }
}
