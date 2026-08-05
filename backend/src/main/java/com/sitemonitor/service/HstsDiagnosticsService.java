package com.sitemonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HSTS (HTTP Strict-Transport-Security) tanılaması. Hedefe HTTPS isteği yapıp
 * Strict-Transport-Security yanıt başlığını okur, yönergeleri (max-age /
 * includeSubDomains / preload) ayrıştırır ve "neyi nasıl kontrol etti, neyi
 * bulamaz" şeklinde AÇIKLAMALI bir rapor üretir — analiz yapan kişi HSTS'nin
 * neden desteklenip desteklenmediğini görebilsin.
 *
 * Sertifika doğrulaması BİLEREK atlanır (başlık her koşulda okunsun); bu test
 * yalnız HSTS politikasını analiz eder, sertifika geçerliliğini DEĞİL.
 */
@Slf4j
@Service
public class HstsDiagnosticsService {

    @Value("${site.monitor.diagnostics.timeout-seconds:5}")
    private int timeoutSeconds;
    @Value("${site.monitor.proxy.host:}") private String proxyHost;
    @Value("${site.monitor.proxy.port:0}") private int proxyPort;
    @Value("${site.monitor.proxy.user:}") private String proxyUser;
    @Value("${site.monitor.proxy.pass:}") private String proxyPass;

    private static final Pattern MAX_AGE = Pattern.compile("max-age\\s*=\\s*\"?(\\d+)\"?");
    private static final SSLSocketFactory TRUST_ALL = buildTrustAll();
    private static final HostnameVerifier ALLOW_ALL = (h, s) -> true;

    public Map<String, Object> diagnose(String domain, int port) {
        long start = System.currentTimeMillis();
        boolean useProxy = proxyHost != null && !proxyHost.isBlank() && proxyPort > 0;
        int timeoutMs = Math.max(1, timeoutSeconds) * 1000;
        String url = "https://" + domain + (port == 443 ? "" : ":" + port) + "/";

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", domain);
        r.put("port", port);
        r.put("url", url);
        r.put("proxy_used", useProxy);

        List<Map<String, Object>> checks = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        // 1) HTTPS'e bağlan ve yanıt başlıklarını al (HEAD; 405/501 ise GET fallback)
        String stsRaw = null;
        Integer httpStatus = null;
        String statusLine = null;
        String connectError = null;
        List<Map<String, Object>> responseHeaders = new ArrayList<>();
        HttpURLConnection hc = null;
        try {
            hc = open(url, useProxy, "HEAD", timeoutMs, true);
            hc.connect();
            httpStatus = hc.getResponseCode();
            stsRaw = hc.getHeaderField("Strict-Transport-Security");
            if (stsRaw == null && (httpStatus == 405 || httpStatus == 501)) {
                hc.disconnect();
                hc = open(url, useProxy, "GET", timeoutMs, true);
                hc.connect();
                httpStatus = hc.getResponseCode();
                stsRaw = hc.getHeaderField("Strict-Transport-Security");
            }
            statusLine = hc.getHeaderField(null); // "HTTP/1.1 200 OK"
            responseHeaders = collectHeaders(hc);
        } catch (Exception e) {
            connectError = rootMsg(e);
        } finally {
            if (hc != null) try { hc.disconnect(); } catch (Exception ignored) {}
        }
        r.put("status_line", statusLine);
        r.put("response_headers", responseHeaders);
        r.put("header_count", responseHeaders.size());

        boolean connected = connectError == null;
        addCheck(checks, "tls_connect",
                "https'e HEAD/GET isteği yapıldı (sertifika doğrulaması atlandı — başlık her koşulda okunsun)",
                connected ? "Bağlandı (HTTP " + httpStatus + ")" : "Başarısız: " + connectError,
                connected ? "ok" : "fail");

        if (!connected) {
            r.put("status", "error");
            r.put("verdict", "CONNECT_FAILED");
            r.put("header_present", null);
            r.put("error", connectError);
            notes.add("HTTPS bağlantısı kurulamadığı için HSTS başlığı okunamadı. Önce 'Bağlantı' / 'openssl' tanılamasıyla erişimi ve sertifikayı doğrulayın.");
            return finish(r, checks, notes, start);
        }

        // 2) Strict-Transport-Security başlığı var mı?
        boolean present = stsRaw != null;
        r.put("header_present", present);
        r.put("raw_value", stsRaw);
        int hdrCount = responseHeaders.size();
        addCheck(checks, "sts_header",
                "Sunucunun döndürdüğü " + hdrCount + " yanıt başlığı arasında (büyük/küçük harf duyarsız) "
                        + "'Strict-Transport-Security' arandı",
                present ? "Bulundu → değer: " + stsRaw
                        : "Bulunamadı — gelen " + hdrCount + " başlık arasında 'Strict-Transport-Security' yok "
                          + "(aşağıdaki ham başlık listesine bakın)",
                present ? "ok" : "warn");

        Long maxAge = null;
        boolean includeSub = false, preload = false;
        if (present) {
            String lower = stsRaw.toLowerCase(Locale.ROOT);
            Matcher m = MAX_AGE.matcher(lower);
            if (m.find()) { try { maxAge = Long.parseLong(m.group(1)); } catch (Exception ignored) {} }
            includeSub = lower.contains("includesubdomains");
            preload = lower.contains("preload");

            // 3) Yönerge yorumları (neden uygulanıyor / uygulanmıyor)
            String maxAgeMsg; String maxAgeSev;
            if (maxAge == null) { maxAgeMsg = "max-age yönergesi yok → başlık GEÇERSİZ, tarayıcılar yok sayar"; maxAgeSev = "fail"; }
            else if (maxAge == 0) { maxAgeMsg = "max-age=0 → HSTS bilerek DEVRE DIŞI (mevcut politikayı temizler)"; maxAgeSev = "warn"; }
            else { maxAgeMsg = maxAge + " sn (~" + (maxAge / 86400) + " gün) → politika uygulanıyor"; maxAgeSev = "ok"; }
            addCheck(checks, "max_age",
                    "Başlıktan max-age ayrıştırıldı (politikanın tarayıcıda saklanma süresi)", maxAgeMsg, maxAgeSev);
            addCheck(checks, "include_subdomains",
                    "includeSubDomains yönergesi arandı (alt alan adlarının da kapsanması)",
                    includeSub ? "Var → tüm alt alan adları HSTS kapsamında" : "Yok → yalnız bu host kapsamda",
                    includeSub ? "ok" : "warn");
            addCheck(checks, "preload",
                    "preload yönergesi arandı (tarayıcı preload listesine aday olma şartı)",
                    preload ? "Var (yönerge mevcut)" : "Yok",
                    preload ? "ok" : "warn");
        }
        r.put("max_age", maxAge);
        r.put("include_subdomains", includeSub);
        r.put("preload", preload);

        // 4) HTTP → HTTPS yönlendirmesi (best-effort; yalnız standart 443'te anlamlı)
        if (port == 443) {
            Boolean redirect = checkHttpRedirect(domain, timeoutMs, useProxy);
            r.put("http_redirects_to_https", redirect);
            addCheck(checks, "http_redirect",
                    "http://" + domain + "/ istendi; 3xx + Location'ın https'e gidip gitmediğine bakıldı",
                    redirect == null ? "Belirlenemedi (düz HTTP yanıtı alınamadı)"
                            : redirect ? "Evet → düz HTTP, HTTPS'e yönleniyor"
                                       : "Hayır → düz HTTP HTTPS'e yönlenmiyor (ilk-ziyaret TOFU açığı)",
                    redirect == null ? "na" : redirect ? "ok" : "warn");
        }

        // Karar
        String verdict;
        if (!present) verdict = "ABSENT";
        else if (maxAge == null || maxAge == 0) verdict = "NOT_ENFORCED";
        else verdict = "ENFORCED";
        r.put("status", "ok");
        r.put("verdict", verdict);
        r.put("enabled", "ENFORCED".equals(verdict));

        // Sınırlar — neyi bulamaz
        notes.add("Bu test HSTS POLİTİKASINI analiz eder; sertifikanın geçerliliğini DEĞİL (başlığı okuyabilmek için sertifika doğrulaması atlanır). Sertifika için 'Bağlantı'/'openssl' tanılamasına bakın.");
        notes.add("'preload' yalnızca başlıktaki yönerge olarak raporlanır; alan adının gerçek tarayıcı PRELOAD LİSTESİ üyeliği yerel olarak doğrulanamaz (hstspreload.org gerekir).");
        notes.add("HSTS başlığı tarayıcıca yalnız HTTPS üzerinden alındığında dikkate alınır. Kullanıcı siteye İLK kez düz HTTP ile gelirse, politika henüz yokken araya girilebilir (TOFU). Bu yüzden HTTP→HTTPS yönlendirmesi + preload önemlidir.");
        if (useProxy) notes.add("İstek PROXY üzerinden yapıldı; aradaki proxy/WAF başlığı ekleyip kaldırabilir — sonuç gerçek sunucudan farklı olabilir.");
        notes.add("Ölçüm uygulama sunucusundan (pod) yapılır; CDN/WAF coğrafyaya/uç düğüme göre farklı başlık dönebilir.");

        return finish(r, checks, notes, start);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private HttpURLConnection open(String urlStr, boolean useProxy, String method,
                                   int timeoutMs, boolean followRedirects) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection hc;
        if (useProxy) {
            java.net.Proxy p = new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            hc = (HttpURLConnection) url.openConnection(p);
            if (proxyUser != null && !proxyUser.isBlank()) {
                String creds = Base64.getEncoder().encodeToString(
                        (proxyUser + ":" + proxyPass).getBytes(StandardCharsets.UTF_8));
                hc.setRequestProperty("Proxy-Authorization", "Basic " + creds);
            }
        } else {
            hc = (HttpURLConnection) url.openConnection();
        }
        if (hc instanceof HttpsURLConnection https) {
            https.setSSLSocketFactory(TRUST_ALL);
            https.setHostnameVerifier(ALLOW_ALL);
        }
        hc.setRequestMethod(method);
        hc.setConnectTimeout(timeoutMs);
        hc.setReadTimeout(timeoutMs);
        hc.setInstanceFollowRedirects(followRedirects);
        return hc;
    }

    /** Düz HTTP'nin HTTPS'e yönlenip yönlenmediği (null = belirlenemedi). */
    private Boolean checkHttpRedirect(String domain, int timeoutMs, boolean useProxy) {
        HttpURLConnection hc = null;
        try {
            hc = open("http://" + domain + "/", useProxy, "HEAD", timeoutMs, false);
            hc.connect();
            int code = hc.getResponseCode();
            String loc = hc.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null) {
                return loc.toLowerCase(Locale.ROOT).startsWith("https://");
            }
            return false;
        } catch (Exception e) {
            return null;
        } finally {
            if (hc != null) try { hc.disconnect(); } catch (Exception ignored) {}
        }
    }

    /** Sunucunun döndürdüğü tüm yanıt başlıkları (STS'in hangi başlıklar arasında arandığı görünsün). */
    private static List<Map<String, Object>> collectHeaders(HttpURLConnection hc) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, List<String>> hf = hc.getHeaderFields();
        if (hf == null) return out;
        for (Map.Entry<String, List<String>> e : hf.entrySet()) {
            if (e.getKey() == null) continue; // null anahtar = durum satırı (ayrıca status_line'da)
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("name", e.getKey());
            h.put("value", e.getValue() == null ? "" : String.join(", ", e.getValue()));
            out.add(h);
        }
        return out;
    }

    private static void addCheck(List<Map<String, Object>> checks, String key, String how, String result, String status) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("key", key);
        c.put("how", how);
        c.put("result", result);
        c.put("status", status); // ok | warn | fail | na
        checks.add(c);
    }

    private static Map<String, Object> finish(Map<String, Object> r, List<Map<String, Object>> checks,
                                              List<String> notes, long start) {
        r.put("checks", checks);
        r.put("notes", notes);
        r.put("elapsed_ms", System.currentTimeMillis() - start);
        return r;
    }

    private static SSLSocketFactory buildTrustAll() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new java.security.SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    private static String rootMsg(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String msg = cur.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : cur.getClass().getSimpleName();
    }
}
