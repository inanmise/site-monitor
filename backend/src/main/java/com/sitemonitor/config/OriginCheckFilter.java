package com.sitemonitor.config;

import com.sitemonitor.service.AppSettingsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CSRF savunmasının ikinci katmanı: durum DEĞİŞTİREN API isteğinde kaynak doğrulaması
 * (prod kapısı 2026-09-25, O-4).
 *
 * <p><b>Neden.</b> Tek savunma {@code SameSite=Strict} oturum çereziydi. Strict yalnız SİTE DIŞI isteği keser;
 * kurumun kayıtlı alan adı altındaki her kardeş alt alan adı "aynı site" sayılır. Oradaki bir XSS ya da
 * kontrol edilen bir sayfa, oturumu açık yöneticinin tarayıcısından çerezleri taşıyan düz bir form POST'u
 * gönderebiliyordu — gövdesiz uçlar (izin varsayılanlarına dön, silinenleri kalıcı temizle, kilit aç …) buna
 * açıktı. Kodda Origin / Referer / Sec-Fetch-Site denetimi hiç yoktu.
 *
 * <p><b>Kural</b> (yalnız {@code /api/**} altında POST/PUT/PATCH/DELETE):
 * <ol>
 *   <li>{@code Sec-Fetch-Site: same-origin} (ya da kullanıcı tetiklemeli {@code none}) → geç. Başlığı tarayıcı
 *       hesaplar, sayfa betiği taklit edemez ({@code Sec-} önekli yasak başlık). Vekil Host'u yeniden
 *       yazsa bile modern tarayıcıda yanlış ret olmaz.</li>
 *   <li>{@code Origin} (yoksa {@code Referer}) YOKSA → geç: tarayıcı dışı istemci (curl, entegrasyon) CSRF
 *       taşıyıcısı değildir.</li>
 *   <li>Kaynak şunlardan biriyle eşleşiyorsa → geç: isteğin {@code Host}'u, vekilin ilettiği
 *       {@code X-Forwarded-Host}, yapılandırılmış {@code site.monitor.app.base-url}, CORS izinli kökenler
 *       (geliştirmede Vite :5173 → :8080).</li>
 *   <li>Aksi hâlde → 403. {@code "null"} Origin (sandbox iframe, file://) de reddedilir.</li>
 * </ol>
 *
 * <p>Token'ın kendisi yetki olan kimliksiz e-posta onay uçları kapsam dışıdır (tarayıcı oturumu taşımazlar).
 * Kapatma anahtarı: {@code site.monitor.security.origin-check.enabled} (env {@code ORIGIN_CHECK_ENABLED});
 * AppSettings kataloğuna bilerek eklenmedi — yanlış bir yapılandırmada kilitlenen yönetici arayüzden
 * açamayacağı bir ayarı değil, ortam değişkenini kullanır.
 */
@Slf4j
public class OriginCheckFilter extends OncePerRequestFilter {

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    /** Token = yetki olan kimliksiz uçlar (AuthInterceptor.PUBLIC ile aynı onay-bağlantısı uçları). */
    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/api/weekly-reports/approve-link",
            "/api/weekly-reports/approve-link/confirm",
            "/api/weekly-reports/approve-link/reject");

    private final ObjectProvider<AppSettingsService> settings;
    private final boolean enabled;
    private final String baseUrlDefault;
    private final String corsDefault;

    public OriginCheckFilter(ObjectProvider<AppSettingsService> settings, boolean enabled,
                             String baseUrlDefault, String corsDefault) {
        this.settings = settings;
        this.enabled = enabled;
        this.baseUrlDefault = baseUrlDefault;
        this.corsDefault = corsDefault;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        if (!enabled || !UNSAFE_METHODS.contains(req.getMethod().toUpperCase(Locale.ROOT))) return true;
        // BK1 (2026-09-27): HAM getRequestURI() değil NORMALİZE yol — "/api;x/..." ham hâliyle "/api/" ile
        // başlamadığı için POST/PUT/DELETE bu denetimi atlıyordu (yönlendirici yine API ucuna götürüyordu).
        // Yol çözülemezse (null) DENETLE: fail-closed.
        String path = RequestPathFirewallFilter.lookupPath(req);
        if (path == null) return false;
        return !path.startsWith("/api/") || EXEMPT_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (allowed(req)) {
            chain.doFilter(req, res);
            return;
        }
        log.warn("Kaynak doğrulaması reddetti (CSRF koruması): {} {} origin={} referer={} host={} xfh={}",
                req.getMethod(), req.getRequestURI(), clip(req.getHeader("Origin")), clip(req.getHeader("Referer")),
                clip(req.getHeader("Host")), clip(req.getHeader("X-Forwarded-Host")));
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"success\":false,\"error\":\"İstek kaynağı doğrulanamadı (Origin uyuşmuyor)\"}");
    }

    /** Karar — paket görünür: testler doğrudan sınar. */
    boolean allowed(HttpServletRequest req) {
        String fetchSite = req.getHeader("Sec-Fetch-Site");
        if ("same-origin".equalsIgnoreCase(fetchSite) || "none".equalsIgnoreCase(fetchSite)) return true;

        String source = req.getHeader("Origin");
        if (source == null || source.isBlank()) source = req.getHeader("Referer");
        if (source == null || source.isBlank()) return true;   // tarayıcı dışı istemci

        Endpoint src = Endpoint.parse(source);
        if (src == null) return false;                          // "null" Origin / bozuk değer

        if (src.matchesHostHeader(req.getHeader("Host"))) return true;
        String xfh = req.getHeader("X-Forwarded-Host");
        if (xfh != null) {
            for (String h : xfh.split(",")) if (src.matchesHostHeader(h.trim())) return true;
        }
        Endpoint base = Endpoint.parse(baseUrl());
        if (src.equals(base)) return true;
        for (String o : corsOrigins()) {
            if (src.equals(Endpoint.parse(o))) return true;
        }
        return false;
    }

    private String baseUrl() {
        try {
            AppSettingsService s = settings == null ? null : settings.getIfAvailable();
            return s != null ? s.getString("site.monitor.app.base-url", baseUrlDefault) : baseUrlDefault;
        } catch (RuntimeException e) {
            return baseUrlDefault;   // ayar okunamazsa yapılandırma değeri — filtre isteği asla düşürmez
        }
    }

    private List<String> corsOrigins() {
        try {
            AppSettingsService s = settings == null ? null : settings.getIfAvailable();
            if (s != null) return s.getCsv("site.monitor.cors.allowed-origins", corsDefault);
        } catch (RuntimeException ignore) { /* yapılandırma değerine düş */ }
        return corsDefault == null || corsDefault.isBlank() ? List.of()
                : java.util.Arrays.stream(corsDefault.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static String clip(String v) {
        if (v == null) return "-";
        String s = v.replaceAll("[\\r\\n\\t]", " ");
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    /** Karşılaştırma birimi: şema + küçük harf host + etkin port (varsayılan port açık yazılmış sayılır). */
    record Endpoint(String scheme, String host, int port) {

        static Endpoint parse(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                URI u = URI.create(value.trim());
                String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
                String host = u.getHost();
                if (scheme == null || host == null || !(scheme.equals("http") || scheme.equals("https"))) return null;
                int port = u.getPort() != -1 ? u.getPort() : defaultPort(scheme);
                return new Endpoint(scheme, stripBrackets(host).toLowerCase(Locale.ROOT), port);
            } catch (RuntimeException e) {
                return null;
            }
        }

        static int defaultPort(String scheme) { return "https".equals(scheme) ? 443 : 80; }

        private static String stripBrackets(String h) {
            return h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
        }

        /**
         * {@code Host} biçimindeki değer ({@code host}, {@code host:port}, {@code [v6]:port}) bu kaynağı mı
         * gösteriyor? Port yazılmamışsa kaynağın şemasının varsayılan portu kabul edilir (TLS'i vekil
         * sonlandırıyorsa Host genelde portsuz gelir).
         */
        boolean matchesHostHeader(String hostHeader) {
            if (hostHeader == null || hostHeader.isBlank()) return false;
            String h = hostHeader.trim();
            String name;
            Integer p = null;
            if (h.startsWith("[")) {
                int end = h.indexOf(']');
                if (end < 0) return false;
                name = h.substring(1, end);
                if (h.length() > end + 2 && h.charAt(end + 1) == ':') p = parsePort(h.substring(end + 2));
            } else {
                int c = h.lastIndexOf(':');
                if (c > 0 && h.indexOf(':') == c) {
                    name = h.substring(0, c);
                    p = parsePort(h.substring(c + 1));
                } else {
                    name = h;
                }
            }
            if (!host.equalsIgnoreCase(name)) return false;
            return (p == null ? defaultPort(scheme) : p) == port;
        }

        private static Integer parsePort(String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
        }
    }
}
