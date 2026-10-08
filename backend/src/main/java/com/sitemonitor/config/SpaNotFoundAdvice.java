package com.sitemonitor.config;

import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bilinmeyen adresler için 404 yanıtı (2026-10-08, kullanıcı isteği: "özelleştirilmiş 404 sayfası").
 *
 * <p>Uygulamada eşleşmeyen her yol statik kaynak işleyicisine ({@code /**}) düşer ve
 * {@link NoResourceFoundException} fırlatır. Bu tavsiye onu üç biçimden birine çevirir — hepsi HTTP 404 +
 * {@code Cache-Control: no-store} (bir 404'ün paylaşımlı önbellekte saklanması, yuvarlanan dağıtımda olmayan bir
 * {@code /assets/*.js} için yıllarca beyaz ekran demekti):
 * <ul>
 *   <li><b>{@code /api/**}</b> → JSON {@code {success:false, code:"NOT_FOUND", error}} (istek dilinde, {@link Msg}).
 *       Tarayıcı {@code text/html} istese bile JSON — API istemcisi HTML ayrıştırmaz.</li>
 *   <li><b>Statik varlık</b> ({@code /assets/}, {@code /brand/}, {@code /fonts/} ya da bilinen bir dosya uzantısı) →
 *       düz metin 404. Asla HTML değil: bir modül {@code <script>} yüklemesi HTML alırsa MIME hatasıyla çöker ve
 *       asıl neden (eksik dosya) gizlenir.</li>
 *   <li><b>Sayfa gezintisi</b> (GET/HEAD + {@code Accept: text/html}) → SPA kabuğu ({@code index.html}) ile 404.
 *       Ön yüz {@code main.jsx} bilinmeyen yolu görüp markalı 404 sayfasını çizer; durum kodu doğru kalır
 *       (izleyiciler, tarayıcı geçmişi ve arama motorları sayfayı "var" saymaz).</li>
 * </ul>
 * Diğer her şey (ör. {@code Accept: *}{@code /*} ile uzantısız bir yol) düz metin 404 alır.
 *
 * <p>{@code /}, {@code /index.html}, {@code /health}, {@code /metrics}, {@code /api/**} uçları ve e-posta onay
 * sayfaları ({@code /api/weekly-reports/approve-link}) bu yola HİÇ uğramaz — yalnız eşleşmeyen yollar gelir.
 *
 * <p>{@link GlobalExceptionHandler}'dan ÖNCE çalışır ({@code HIGHEST_PRECEDENCE}); oradaki
 * {@code NoResourceFoundException} işleyicisi artık bu tavsiyenin gölgesindedir. Yanıt doğrudan yazılır (içerik
 * pazarlığı yok) — {@code Accept: text/html} ile gelen bir API isteği JSON'u "kabul edilemez" bulup Whitelabel
 * sayfasına düşmesin.
 */
@Slf4j
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SpaNotFoundAdvice {

    public static final String CODE = "NOT_FOUND";

    /** Yalnız dosya sunan önekler — altındaki her eksik yol düz metin 404'tür. */
    static final List<String> ASSET_PREFIXES = List.of("/assets/", "/brand/", "/fonts/", "/static/");

    /** Statik dosya uzantıları (küçük harf). {@code .html} bilinçli olarak YOK: eski bir sayfa adresi markalı 404 görür. */
    static final Set<String> ASSET_EXTENSIONS = Set.of(
            "js", "mjs", "cjs", "css", "map", "json", "webmanifest", "txt", "xml",
            "png", "jpg", "jpeg", "gif", "svg", "ico", "webp", "avif", "bmp",
            "woff", "woff2", "ttf", "otf", "eot",
            "pdf", "wasm", "mp3", "mp4", "webm", "zip", "gz");

    /** Yanıtta yankılanan yolun üst sınırı (JSON iletisi). */
    private static final int MAX_ECHO = 200;

    private static final UrlPathHelper PATHS = UrlPathHelper.defaultInstance;

    private final ResourceLoader resourceLoader;
    private final List<String> indexLocations;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Son okunan kabuk — dosya değişmedikçe (lastModified) yeniden okunmaz. */
    private volatile CachedIndex cached;

    private record CachedIndex(String uri, long lastModified, byte[] bytes) { }

    /** Sayfa sınıflandırması — yalnız test görünürlüğü için paket düzeyinde. */
    enum Kind { API, ASSET, PAGE, OTHER }

    public SpaNotFoundAdvice(ResourceLoader resourceLoader,
                             @Value("${spring.web.resources.static-locations:classpath:/static/}") String staticLocations) {
        this.resourceLoader = resourceLoader;
        this.indexLocations = parseLocations(staticLocations);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public void handleNoResource(NoResourceFoundException e, HttpServletRequest req, HttpServletResponse res)
            throws IOException {
        String path = PATHS.getPathWithinApplication(req);
        Kind kind = classify(req.getMethod(), path, req.getHeader("Accept"));
        log.debug("404 ({}): {} {}", kind, req.getMethod(), path);
        res.setStatus(HttpServletResponse.SC_NOT_FOUND);
        // Filtrenin /assets/** için yazdığı "immutable, 1 yıl" bir 404'e ASLA uygulanmaz (setHeader eskisini değiştirir).
        res.setHeader("Cache-Control", "no-store, must-revalidate");
        switch (kind) {
            case API -> writeJson(req, res, path);
            case PAGE -> {
                byte[] index = indexHtml();
                if (index != null) writeBytes(res, "text/html;charset=UTF-8", index);
                else writePlain(res);   // arayüz paketlenmemiş (yalnız backend geliştirme) → düz 404
            }
            default -> writePlain(res);
        }
    }

    /** İstek türü: API mi, statik varlık mı, HTML sayfa gezintisi mi. */
    static Kind classify(String method, String path, String accept) {
        String p = path == null || path.isEmpty() ? "/" : path;
        if (p.equals("/api") || p.startsWith("/api/")) return Kind.API;
        if (isAsset(p)) return Kind.ASSET;
        boolean read = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
        if (read && acceptsHtml(accept)) return Kind.PAGE;
        return Kind.OTHER;
    }

    static boolean isAsset(String path) {
        for (String prefix : ASSET_PREFIXES) {
            if (path.startsWith(prefix)) return true;
        }
        String last = path.substring(path.lastIndexOf('/') + 1);
        int dot = last.lastIndexOf('.');
        if (dot <= 0 || dot == last.length() - 1) return false;   // ".env" gibi gizli adlar ve sonda nokta uzantı değil
        return ASSET_EXTENSIONS.contains(last.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static boolean acceptsHtml(String accept) {
        if (accept == null) return false;
        String a = accept.toLowerCase(Locale.ROOT);
        return a.contains("text/html") || a.contains("application/xhtml+xml");
    }

    private void writeJson(HttpServletRequest req, HttpServletResponse res, String path) throws IOException {
        String shown = path.length() > MAX_ECHO ? path.substring(0, MAX_ECHO) + "…" : path;
        String method = req.getMethod() == null ? "GET" : req.getMethod();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", CODE);
        body.put("error", Msg.t(
                "İstenen API adresi bulunamadı (" + method + " " + shown + "). Adres yanlış yazılmış ya da bu sürümde "
                        + "kaldırılmış olabilir; sayfayı yenileyip yeniden deneyin.",
                "The requested API address was not found (" + method + " " + shown + "). It may be mistyped or no "
                        + "longer available in this version; reload the page and try again."));
        // Hata sözleşmesi (GlobalExceptionHandler ile aynı): istek kimliği gövdede de — kullanıcı bildirirken alıntılar.
        String requestId = CorrelationIdFilter.get(req);
        if (requestId != null) body.put("request_id", requestId);
        res.setHeader("Pragma", "no-cache");
        writeBytes(res, "application/json;charset=UTF-8", mapper.writeValueAsBytes(body));
    }

    private static void writePlain(HttpServletResponse res) throws IOException {
        writeBytes(res, "text/plain;charset=UTF-8", "404 Not Found".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(HttpServletResponse res, String contentType, byte[] bytes) throws IOException {
        res.setContentType(contentType);
        res.setContentLength(bytes.length);
        res.getOutputStream().write(bytes);
    }

    /**
     * SPA kabuğu — statik konumların sırasıyla İLK bulunan {@code index.html}'i (Spring'in kendi çözüm sırası:
     * önce {@code file:./frontend/dist/}, sonra jar içi {@code classpath:/static/}). Yeni bir derleme dosyayı
     * değiştirirse (lastModified) yeniden okunur; dosya yoksa {@code null}.
     */
    byte[] indexHtml() {
        for (String loc : indexLocations) {
            try {
                Resource r = resourceLoader.getResource(loc + "index.html");
                if (!r.exists() || !r.isReadable()) continue;
                String uri = r.getURI().toString();
                long lm = lastModified(r);
                CachedIndex c = cached;
                if (c != null && c.uri().equals(uri) && c.lastModified() == lm && lm > 0) return c.bytes();
                byte[] bytes;
                try (InputStream in = r.getInputStream()) {
                    bytes = StreamUtils.copyToByteArray(in);
                }
                cached = new CachedIndex(uri, lm, bytes);
                return bytes;
            } catch (IOException | RuntimeException ex) {
                log.debug("index.html okunamadı ({}): {}", loc, ex.toString());
            }
        }
        return null;
    }

    private static long lastModified(Resource r) {
        try {
            return r.lastModified();
        } catch (IOException e) {
            return -1L;
        }
    }

    /** "file:./frontend/dist/,classpath:/static/" → her biri '/' ile biten temiz liste. */
    static List<String> parseLocations(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            out.add(s.endsWith("/") ? s : s + "/");
        }
        return out;
    }
}
