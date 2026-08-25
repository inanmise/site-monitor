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
 * süreci ise hiçbir vekil değişkeni almıyordu.
 *
 * <p><b>DÜZELTME (2026-08, ilk teşhis yanlıştı).</b> Bu sınıf "288 koşumun 288'i request timeout"
 * vakasını çözmek için eklendi ve o vakayı ÇÖZMEDİ; gerekçe kayda geçsin:
 * <ul>
 *   <li>Prod {@code NO_PROXY} değeri {@code akbank.com} içeriyor. Go ({@code x/net/http/httpproxy})
 *       bu girdiyi {@code .akbank.com} sonek eşleşmesine çevirir ⇒ {@code www.akbank.com} ve
 *       {@code *.apps.<ic-openshift>.<kurumsal-alan>} dâhil TÜM alt alanlar vekili BAYPAS eder. Yani
 *       {@code HTTPS_PROXY} verilse de Go bu hedefler için onu kullanmaz.</li>
 *   <li>{@code ProcessBuilder.environment()} ebeveyn ortamının kopyasıyla başladığı için k6 zaten
 *       ÖNCEDEN de pod'un {@code NO_PROXY}'sini görüyordu — net davranış değişmedi.
 *       (Ortam artık {@code ProcessProbe} izolasyonuyla temizleniyor; vekil değişkenlerini yalnız
 *       bu sınıf, açıkça verir.)</li>
 *   <li>{@code CertificateCheckerService.shouldBypassProxy} aynı girdide {@code true} döndüğü için
 *       başarıyla sertifika çeken Java kontrolü de DOĞRUDAN çıkıyordu ⇒ "doğrudan çıkış yutuluyor"
 *       açıklaması bu hedefler için geçerli değil. Arıza TCP/TLS kurulumunda değil, isteğin
 *       devamında; hangi fazda olduğu artık koşum faz metrikleriyle ölçülüyor.</li>
 * </ul>
 * Sınıf yine de doğru ve gerekli: vekil gerektiren (NO_PROXY dışı) hedefler için k6'nın tek çıkış yolu.
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
