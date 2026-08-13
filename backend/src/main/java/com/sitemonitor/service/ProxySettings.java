package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Kurumsal çıkış vekilinin (outbound proxy) tek yerden okunan ayarları.
 *
 * <p>Neden var: Java tarafındaki outbound (sertifika/zincir/HSTS/RDAP/TR-whois) {@code
 * site.monitor.proxy.*} ayarlarını kullanıyor ve vekil zorunlu ağlarda dışarı ÇIKABİLİYOR; k6 alt
 * süreci ise doğrudan çıkıyordu. Vekil zorunlu bir ağda doğrudan çıkış güvenlik cihazınca TCP'de
 * kabul edilip yutulduğu için her sentetik koşum {@code request timeout} ile düşüyordu
 * (2026-08'de sahada: 288 koşumun 288'i; aynı pod sertifikayı sorunsuz çekerken).
 *
 * <p>Bu bileşen YALNIZ sentetik (k6) yolundan kullanılır. Aynı {@code @Value} alanlarını tekrarlayan
 * dört sınıf ({@code CertificateCheckerService}, {@code ConnectionDiagnosticsService},
 * {@code RdapDomainClient}, {@code TrWebWhoisClient}) bilinçli olarak DEĞİŞTİRİLMEDİ — onları da
 * buraya taşımak ayrı ve riskli bir iş; burada yalnız eksik olan yol kapatılıyor.
 */
@Slf4j
@Component
public class ProxySettings {

    @Value("${site.monitor.proxy.host:}")     private String host;
    @Value("${site.monitor.proxy.port:0}")    private int    port;
    @Value("${site.monitor.proxy.user:}")     private String user;
    @Value("${site.monitor.proxy.pass:}")     private String pass;
    @Value("${site.monitor.proxy.no-proxy:}") private String noProxy;

    /** Vekil yapılandırılmış mı? ({@code CertificateCheckerService.proxyEnabled} ile aynı kural.) */
    public boolean enabled() {
        return host != null && !host.isBlank() && port > 0;
    }

    public String host() { return host; }
    public int port() { return port; }

    /** {@code NO_PROXY} ham listesi (virgülle ayrık); tanımsızsa boş string. */
    public String noProxyList() {
        return noProxy == null ? "" : noProxy.trim();
    }

    /**
     * Go/k6'nın {@code HTTPS_PROXY} olarak anlayacağı URL.
     *
     * <p>Kimlik varsa {@code http://user:pass@host:port}. Kullanıcı adı/parola URL-encode edilir:
     * kurumsal parolalarda {@code @} ve {@code :} sık geçer ve encode edilmezse URL ayrıştırması
     * sessizce yanlış host'a işaret eder.
     *
     * @return vekil URL'i; vekil yapılandırılmamışsa {@code null}
     */
    public String proxyUrl() {
        if (!enabled()) return null;
        StringBuilder sb = new StringBuilder("http://");
        if (user != null && !user.isBlank()) {
            sb.append(enc(user));
            if (pass != null && !pass.isEmpty()) sb.append(':').append(enc(pass));
            sb.append('@');
        }
        return sb.append(host).append(':').append(port).toString();
    }

    /** Maskelenmesi gereken değer: vekil parolası (varsa). Çıktıya sızmamalı. */
    public String secretValue() {
        return (pass == null || pass.isEmpty()) ? null : pass;
    }

    /** Log/gösterim için kimliksiz kısa gösterim — parola ASLA yazılmaz. */
    public String displayTarget() {
        return enabled() ? host + ":" + port : "";
    }

    /**
     * Vekil host'unun IP adresleri — SSRF kara listesinden muaf tutmak için.
     *
     * <p>k6'nın {@code --blacklist-ip} kuralı dialer düzeyinde çalışır: vekil kullanılırken k6
     * VEKİLİN adresine bağlanır. Vekil iç ağdaysa ve {@code allow-internal-targets} kapalıysa,
     * kara liste vekile giden bağlantıyı da keser — yani vekili açmak tek başına yetmez.
     *
     * @return çözülen adresler; çözülemezse boş dizi (çağıran muafiyet uygulamaz)
     */
    public InetAddress[] resolveProxyAddresses() {
        if (!enabled()) return new InetAddress[0];
        try {
            return InetAddress.getAllByName(host);
        } catch (Exception e) {
            log.warn("Vekil host'u çözülemedi ({}): {} — SSRF kara listesi muafiyeti uygulanmayacak",
                    host, e.toString());
            return new InetAddress[0];
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
