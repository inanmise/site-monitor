package com.sitemonitor.controller;

import com.sitemonitor.config.CorrelationIdFilter;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.page.scan.ImageDimensions;
import com.sitemonitor.service.page.scan.PageResourceImageService;
import com.sitemonitor.service.page.scan.PageResourceImageService.ImageResult;
import com.sitemonitor.service.page.scan.PageResourceImageService.Projection;
import com.sitemonitor.service.page.scan.PageResourceImageService.Replacement;
import com.sitemonitor.util.Msg;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kaynak analizi "Görsel aracı" uçları (2026-11-14) — görsel işleme tarayıcıdadır; sunucu yalnız şunları yapar:
 * <ul>
 *   <li>{@code POST /api/monitoring/page/{id}/resource-scans/{scanId}/image} — taramanın bulduğu TEK görselin özgün baytları</li>
 *   <li>{@code POST /api/monitoring/page/{id}/resource-scans/{scanId}/image-projection} — "not ne olurdu?" (yazma / dış istek yok)</li>
 * </ul>
 *
 * <p><b>Yetki.</b> Görsel indirmek sunucunun izlenen siteyi ÇAĞIRMASI demektir → "Şimdi kontrol et" kuralı (analizi başlatmanın
 * kapısı): {@code monitoring.trigger/execute} + izlemenin takımında çalıştırma kapsamı ({@link SessionScope#canExecuteTeam},
 * EXECUTE+ paylaşım dahil). Projeksiyon analizi okuma kapısıdır ({@code monitoring.read/view} +
 * {@link SessionScope#canViewMonitoring}). Göremediği izleme 404 {@code RESOURCE_MONITOR_NOT_FOUND} (varlığı sızmaz).
 *
 * <p><b>Sunucu açık vekil değildir.</b> Yalnız o taramanın saklanan nesne listesindeki, adresi birebir eşleşen GÖRSEL nesnesi
 * çekilir (ayrıntı: {@link PageResourceImageService}). Yanıt asla belge olarak çizilemez: tür sihirli baytlardan,
 * {@code Content-Disposition: attachment}, {@code X-Content-Type-Options: nosniff}, {@code Cache-Control: no-store}.
 * Denetim {@code PAGE_RESOURCE_IMAGE_DOWNLOAD} yalnız BAŞARILI indirmede (adres sorgusuz; gövde asla). Bu uçların gövdeleri
 * günlüğe yazılmaz ({@code RequestLoggingFilter}).
 *
 * <p><b>Yük sınırı yazma boyunca tutulur.</b> Başarılı indirmenin hakkı ({@link ImageResult#lease()}) gövde istemciye YAZILANA
 * ya da istemci kopana dek bırakılmaz: gövde bu yöntemin içinde yanıt akışına yazılır, hak {@code finally}'de bırakılır
 * (yavaş okuyan istemci kişinin / pod'un eşzamanlılık hakkını tutar — bellek ve iş parçacığı sayılır).
 */
@Slf4j
@RestController
@RequestMapping("/api/monitoring/page")
@RequiredArgsConstructor
public class PageResourceImageController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** Dosya adının (uzantısız) en çok uzunluğu. */
    static final int FILE_NAME_MAX = 80;

    private final PageMonitorRepository pageMonitorRepo;
    private final PermissionService permissionService;
    private final PageResourceImageService images;
    private final AuditService auditService;

    // ── 1) Özgün görsel ─────────────────────────────────────────────────────────────────────

    /**
     * Gövde {@code {"url": "<nesnenin taramada dönen adresi>"}}. 200 = ham baytlar ({@code Content-Type} sihirli baytlardan,
     * {@code X-Image-Type}, okunabildiyse {@code X-Image-Width} / {@code X-Image-Height}). Hata kodları:
     * {@code VALIDATION_FAILED} (400, {@code url}), {@code RESOURCE_MONITOR_NOT_FOUND} (404), {@code RESOURCE_SCAN_NOT_FOUND} (404),
     * {@code RESOURCE_SCAN_ITEMS_GONE} (409),
     * {@code RESOURCE_IMAGE_NOT_FOUND} (404), {@code RESOURCE_IMAGE_URL_MASKED} (409), {@code RESOURCE_IMAGE_TOO_LARGE} (413),
     * {@code RATE_LIMITED} / {@code RESOURCE_IMAGE_BUSY} / {@code RESOURCE_IMAGE_HOST_LIMITED} (429), {@code BLOCKED} (400),
     * {@code RESOURCE_IMAGE_FETCH_FAILED} (502; {@code reason} {@code TLS_DOWNGRADE} = https adres http'ye yönlendirdi),
     * {@code RESOURCE_IMAGE_UNSUPPORTED} (415). 200'de son adım https ise {@code X-Image-Tls-Verified: true|false}.
     */
    @PostMapping("/{id}/resource-scans/{scanId}/image")
    public ResponseEntity<?> image(@PathVariable Long id, @PathVariable Long scanId,
                                   @RequestBody(required = false) Map<String, Object> body,
                                   HttpSession session, HttpServletResponse response) {
        permissionService.require(session, "monitoring.trigger", "execute");
        PageMonitor m = pageMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        if (!SessionScope.canExecuteTeam(session, m.getTeamId())) {
            throw new SecurityException(Msg.t("Bu takımın izlemesinde kaynak analizi görseli indiremezsiniz.",
                    "You cannot download a resource analysis image on this team's monitor."));
        }
        String url = urlOf(body == null ? null : body.get("url"), "url");

        ImageResult r = images.image(m.getId(), scanId, url, m.getUseProxy(), userKey(session));
        try {
            return respond(r, m, scanId, url, session, response);
        } finally {
            r.release();   // yük hakkı: gövde yazıldıktan / istemci koptuktan SONRA
        }
    }

    private ResponseEntity<?> respond(ImageResult r, PageMonitor m, Long scanId, String url, HttpSession session,
                                      HttpServletResponse response) {
        return switch (r.outcome()) {
            case OK -> {
                auditService.recordAction("PAGE_RESOURCE_IMAGE_DOWNLOAD", session, "PAGE_MONITOR", String.valueOf(m.getId()),
                        AuditDetail.of("name", m.getName(), "scan_id", scanId, "url", AuditDetail.safeTarget(url),
                                "bytes", r.body().length, "type", r.type()), null);
                writeImage(response, r, url);
                yield null;   // yanıt yazıldı (Spring gövde yazmaz)
            }
            case SCAN_NOT_FOUND -> scanNotFound();
            case ITEMS_GONE -> itemsGone();
            case NOT_FOUND -> error(404, "RESOURCE_IMAGE_NOT_FOUND", Msg.t(
                    "Bu adres, analizin bulduğu görseller arasında yok. Yalnız analizde listelenen görseller indirilebilir; "
                            + "listeyi yenileyip yeniden deneyin.",
                    "This address is not among the images the analysis found. Only images listed in the analysis can be "
                            + "downloaded; refresh the list and try again."));
            case MASKED -> error(409, "RESOURCE_IMAGE_URL_MASKED", Msg.t(
                    "Bu görselin adresindeki gizli değer saklanmadığı için (***** ile maskelendi) sunucu görseli indiremez. "
                            + "Görseli sitenin kendisinden indirip \"Görsel aracı\"na yükleyin.",
                    "The secret value in this image's address was not stored (it is masked with *****), so the server "
                            + "cannot download the image. Download it from the site itself and upload it to the image tool."));
            case TOO_LARGE -> {
                Map<String, Object> b = errorBody("RESOURCE_IMAGE_TOO_LARGE", Msg.t(
                        "Görsel " + mib(PageResourceImageService.MAX_IMAGE_BYTES) + " MiB sınırını aşıyor; sunucudan "
                                + "indirilemez. Görseli sitenin kendisinden indirip küçülterek yükleyin.",
                        "The image is larger than the " + mib(PageResourceImageService.MAX_IMAGE_BYTES) + " MiB limit and "
                                + "cannot be downloaded through the server. Download it from the site itself."));
                b.put("max_bytes", PageResourceImageService.MAX_IMAGE_BYTES);
                if (r.knownBytes() != null) b.put("bytes", r.knownBytes());
                yield json(413).body(b);
            }
            case RATE -> tooMany("RATE_LIMITED", r.retryAfterSeconds(), Msg.t(
                    "Çok sık görsel indirdiniz — kişi başına dakikada en çok " + PageResourceImageService.IMAGES_PER_MINUTE
                            + " görsel. " + r.retryAfterSeconds() + " sn sonra yeniden deneyin.",
                    "You downloaded images too often — at most " + PageResourceImageService.IMAGES_PER_MINUTE
                            + " per minute per person. Try again in " + r.retryAfterSeconds() + " s."));
            case HOST_LIMITED -> tooMany("RESOURCE_IMAGE_HOST_LIMITED", r.retryAfterSeconds(), Msg.t(
                    "Bu sitenin görselleri çok sık indirildi — aynı siteden dakikada en çok "
                            + PageResourceImageService.IMAGES_PER_HOST_MINUTE + " görsel. " + r.retryAfterSeconds()
                            + " sn sonra yeniden deneyin.",
                    "Images from this site were downloaded too often — at most " + PageResourceImageService.IMAGES_PER_HOST_MINUTE
                            + " per minute from the same site. Try again in " + r.retryAfterSeconds() + " s."));
            case BUSY -> tooMany("RESOURCE_IMAGE_BUSY", r.retryAfterSeconds(), Msg.t(
                    "Şu an aynı anda indirilebilen görsel sayısı dolu (kişi başına " + PageResourceImageService.IMAGES_PER_USER
                            + ", sunucuda " + PageResourceImageService.IMAGES_PER_POD + "). Birkaç saniye sonra yeniden deneyin.",
                    "The number of images that can be downloaded at the same time is used up ("
                            + PageResourceImageService.IMAGES_PER_USER + " per person, " + PageResourceImageService.IMAGES_PER_POD
                            + " on the server). Try again in a few seconds."));
            case BLOCKED -> error(400, "BLOCKED", Msg.t(
                    "Görselin adresi (ya da yönlendirdiği adres) güvenlik kuralına takıldı (iç / yasaklı adres); indirilmedi.",
                    "The image address (or an address it redirects to) was blocked by a security rule (internal / forbidden "
                            + "address); it was not downloaded."));
            case FETCH_FAILED -> {
                Map<String, Object> b = errorBody("RESOURCE_IMAGE_FETCH_FAILED",
                        PageResourceImageService.REASON_TLS_DOWNGRADE.equals(r.reason())
                        ? Msg.t("Görselin güvenli (https) adresi şifresiz (http) bir adrese yönlendiriyor; araya girilebilen bu "
                                        + "adımdan alınan bayt özgün sayılamayacağı için görsel indirilmedi. Görseli sitenin "
                                        + "kendisinden indirin.",
                                "The image's secure (https) address redirects to an unencrypted (http) address; bytes taken "
                                        + "from that step could have been altered in transit, so the image was not downloaded. "
                                        + "Download it from the site itself.")
                        : r.httpStatus() != null
                        ? Msg.t("Site görsel için HTTP " + r.httpStatus() + " döndürdü; görsel indirilemedi. Görsel kaldırılmış "
                                        + "ya da erişim kısıtlanmış olabilir — analizi yeniden çalıştırın.",
                                "The site returned HTTP " + r.httpStatus() + " for the image; it could not be downloaded. The image "
                                        + "may have been removed or access may be restricted — run the analysis again.")
                        : Msg.t("Görsele ulaşılamadı (ağ hatası ya da zaman aşımı); indirilemedi. Birazdan yeniden deneyin.",
                                "The image could not be reached (network error or time-out); it was not downloaded. Try again shortly."));
                if (r.reason() != null) b.put("reason", r.reason());
                if (r.httpStatus() != null) b.put("http_status", r.httpStatus());
                yield json(502).body(b);
            }
            case UNSUPPORTED -> error(415, "RESOURCE_IMAGE_UNSUPPORTED", Msg.t(
                    "Adresten gelen içerik desteklenen bir görsel değil (PNG, JPEG, GIF, WebP, BMP, AVIF). SVG ve hata sayfaları "
                            + "indirilmez.",
                    "The content at this address is not a supported image (PNG, JPEG, GIF, WebP, BMP, AVIF). SVG files and "
                            + "error pages are not downloaded."));
        };
    }

    // ── 2) "Not ne olurdu?" projeksiyonu ─────────────────────────────────────────────────────

    /**
     * Gövde {@code {"use_measured": bool, "images": [{"url", "bytes", "width"?, "height"?, "type"?}]}} (ikisi de isteğe bağlı;
     * {@code images} en çok {@value PageResourceImageService#MAX_PROJECTION_IMAGES}). 200 = {@code {algorithm_version, capped,
     * applied, ignored, before, after, reused_components}}. Yazma yok, dış istek yok, denetim yok. Hata kodları:
     * {@code VALIDATION_FAILED} (400), {@code RESOURCE_MONITOR_NOT_FOUND} (404), {@code RESOURCE_SCAN_NOT_FOUND} (404),
     * {@code RESOURCE_SCAN_NOT_DONE} /
     * {@code RESOURCE_SCAN_ITEMS_GONE} / {@code RESOURCE_SCAN_NOT_PROJECTABLE} (409), {@code RATE_LIMITED} /
     * {@code RESOURCE_PROJECTION_BUSY} (429).
     */
    @PostMapping("/{id}/resource-scans/{scanId}/image-projection")
    public ResponseEntity<Map<String, Object>> projection(@PathVariable Long id, @PathVariable Long scanId,
                                                          @RequestBody(required = false) Map<String, Object> body,
                                                          HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        PageMonitor m = pageMonitorRepo.findById(id).orElse(null);
        if (m == null || !SessionScope.canViewMonitoring(session, m.getTeamId())) return notFound();
        boolean useMeasured = useMeasured(body == null ? null : body.get("use_measured"));
        List<Replacement> list = replacements(body == null ? null : body.get("images"));

        Projection p = images.project(m.getId(), scanId, useMeasured, list, userKey(session));
        return switch (p.outcome()) {
            case OK -> ResponseEntity.ok(ok(p.data()));
            case SCAN_NOT_FOUND -> scanNotFound();
            case ITEMS_GONE -> itemsGone();
            case NOT_DONE -> error(409, "RESOURCE_SCAN_NOT_DONE", Msg.t(
                    "Bu kaynak analizi tamamlanmadı (çalışıyor, yarıda kaldı ya da hata verdi); projeksiyon yalnız biten "
                            + "analizde yapılır.",
                    "This resource analysis did not finish (it is running, was cut short or failed); a projection needs a "
                            + "finished analysis."));
            case NOT_PROJECTABLE -> {
                Map<String, Object> b = errorBody("RESOURCE_SCAN_NOT_PROJECTABLE",
                        PageResourceImageService.REASON_ALGORITHM.equals(p.reason())
                                ? Msg.t("Bu analiz eski bir not algoritmasıyla yapılmış; \"not ne olurdu?\" hesabı yapılamaz. "
                                                + "Analizi yeniden çalıştırın.",
                                        "This analysis was made with an older grading algorithm, so the projected grade cannot "
                                                + "be calculated. Run the analysis again.")
                                : Msg.t("Bu analizin notu saklanan nesnelerden birebir yeniden hesaplanamadı; yanıltıcı bir fark "
                                                + "göstermemek için projeksiyon verilmedi. Analizi yeniden çalıştırın.",
                                        "This analysis's grade could not be reproduced exactly from its stored objects, so no "
                                                + "projection is given (to avoid a misleading difference). Run the analysis again."));
                b.put("reason", p.reason());
                yield json(409).body(b);
            }
            case BUSY -> tooMany("RESOURCE_PROJECTION_BUSY", p.retryAfterSeconds(), Msg.t(
                    "Şu an aynı anda yapılabilen \"not ne olurdu?\" hesabı sayısı dolu (kişi başına "
                            + PageResourceImageService.PROJECTIONS_PER_USER + ", sunucuda "
                            + PageResourceImageService.PROJECTIONS_PER_POD + "). Birkaç saniye sonra yeniden deneyin.",
                    "The number of grade projections that can run at the same time is used up ("
                            + PageResourceImageService.PROJECTIONS_PER_USER + " per person, "
                            + PageResourceImageService.PROJECTIONS_PER_POD + " on the server). Try again in a few seconds."));
            case RATE -> tooMany("RATE_LIMITED", p.retryAfterSeconds(), Msg.t(
                    "Çok sık hesaplattınız — kişi başına dakikada en çok " + PageResourceImageService.PROJECTIONS_PER_MINUTE
                            + " projeksiyon. " + p.retryAfterSeconds() + " sn sonra yeniden deneyin.",
                    "You asked too often — at most " + PageResourceImageService.PROJECTIONS_PER_MINUTE
                            + " projections per minute per person. Try again in " + p.retryAfterSeconds() + " s."));
        };
    }

    /**
     * Başarılı indirmenin yanıtı: başlıklar + ham baytlar doğrudan yanıt akışına (çağıranın hakkı yazma boyunca tutulur).
     * İstemci koparsa ({@code ClientAbortException} — bir {@link IOException}) sessizce biter: yanıt zaten başlamıştır, hata
     * gövdesi yazılamaz; hak çağıranın {@code finally}'sinde bırakılır.
     */
    static void writeImage(HttpServletResponse response, ImageResult r, String url) {
        String type = r.type();
        byte[] body = r.body();
        response.setStatus(200);
        response.setContentType(ImageDimensions.mime(type));
        response.setContentLengthLong(body.length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(fileName(url, type)));
        // Genel süzgecin yazdığı iki başlık ÜZERİNE yazılır (yinelenen başlık olmasın)
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Image-Type", type);
        if (r.width() != null && r.height() != null) {
            response.setHeader("X-Image-Width", String.valueOf(r.width()));
            response.setHeader("X-Image-Height", String.valueOf(r.height()));
        }
        if (r.tlsVerified() != null) response.setHeader("X-Image-Tls-Verified", String.valueOf(r.tlsVerified()));
        try {
            ServletOutputStream os = response.getOutputStream();
            os.write(body);
            os.flush();
        } catch (IOException clientGone) {
            log.debug("Kaynak analizi görseli yazılırken istemci koptu: {}", clientGone.getClass().getSimpleName());
        }
    }

    // ── Gövde doğrulaması ───────────────────────────────────────────────────────────────────

    /** Adres: dolu metin, en çok {@value PageResourceImageService#MAX_URL_CHARS} karakter (uzunluk her işlemden ÖNCE). */
    static String urlOf(Object raw, String field) {
        if (!(raw instanceof String s) || s.isBlank()) {
            throw new FieldValidationException(field, Msg.t("Görselin adresi (url) zorunlu ve metin olmalı.",
                    "The image address (url) is required and must be text."));
        }
        if (s.length() > PageResourceImageService.MAX_URL_CHARS) {
            throw new FieldValidationException(field, Msg.t(
                    "Görselin adresi çok uzun (en çok " + PageResourceImageService.MAX_URL_CHARS + " karakter).",
                    "The image address is too long (at most " + PageResourceImageService.MAX_URL_CHARS + " characters)."));
        }
        return s;
    }

    static boolean useMeasured(Object raw) {
        if (raw == null) return false;
        if (raw instanceof Boolean b) return b;
        throw new FieldValidationException("use_measured", Msg.t("use_measured yalnız true ya da false olabilir.",
                "use_measured can only be true or false."));
    }

    /** {@code images}: yoksa boş; liste değilse / çok uzunsa / girdisi bozuksa 400 {@code images} ya da {@code images[N].alan}. */
    static List<Replacement> replacements(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new FieldValidationException("images", Msg.t("images bir liste olmalı.", "images must be a list."));
        }
        if (list.size() > PageResourceImageService.MAX_PROJECTION_IMAGES) {
            throw new FieldValidationException("images", Msg.t(
                    "Bir istekte en çok " + PageResourceImageService.MAX_PROJECTION_IMAGES + " görsel gönderilebilir.",
                    "At most " + PageResourceImageService.MAX_PROJECTION_IMAGES + " images can be sent in one request."));
        }
        List<Replacement> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            String at = "images[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> e)) {
                throw new FieldValidationException(at, Msg.t("Her görsel girdisi bir nesne olmalı (url, bytes).",
                        "Every image entry must be an object (url, bytes)."));
            }
            String url = urlOf(e.get("url"), at + ".url");
            Long bytes = whole(e.get("bytes"));
            if (bytes == null || bytes < 1 || bytes > PageResourceImageService.MAX_PROJECTION_BYTES) {
                throw new FieldValidationException(at + ".bytes", Msg.t(
                        "Görselin yeni boyutu (bytes) 1 ile " + PageResourceImageService.MAX_PROJECTION_BYTES
                                + " arasında bir tam sayı olmalı.",
                        "The image's new size (bytes) must be a whole number between 1 and "
                                + PageResourceImageService.MAX_PROJECTION_BYTES + "."));
            }
            Integer width = side(e.get("width"), at + ".width");
            Integer height = side(e.get("height"), at + ".height");
            String type = null;
            Object rawType = e.get("type");
            if (rawType != null) {
                type = rawType instanceof String s && s.length() <= 10 ? s.trim().toLowerCase(Locale.ROOT) : null;
                if (type == null || ImageDimensions.mime(type) == null) {
                    throw new FieldValidationException(at + ".type", Msg.t(
                            "Görsel türü yalnız png, jpeg, gif, webp, bmp ya da avif olabilir.",
                            "The image type can only be png, jpeg, gif, webp, bmp or avif."));
                }
            }
            out.add(new Replacement(url, bytes, width, height, type));
        }
        return out;
    }

    private static Integer side(Object raw, String field) {
        if (raw == null) return null;
        Long v = whole(raw);
        if (v == null || v < 1 || v > PageResourceImageService.MAX_PROJECTION_SIDE) {
            throw new FieldValidationException(field, Msg.t(
                    "Piksel boyutu 1 ile " + PageResourceImageService.MAX_PROJECTION_SIDE + " arasında bir tam sayı olmalı.",
                    "The pixel size must be a whole number between 1 and " + PageResourceImageService.MAX_PROJECTION_SIDE + "."));
        }
        return v.intValue();
    }

    /** JSON tam sayısı mı (kesirsiz, sonlu); değilse null. Metin kabul edilmez. */
    static Long whole(Object raw) {
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            return Long.valueOf(((Number) raw).longValue());
        }
        if (raw instanceof Double || raw instanceof Float) {
            double d = ((Number) raw).doubleValue();
            boolean wholeNumber = !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d) && Math.abs(d) < 9.0e15;
            return wholeNumber ? Long.valueOf((long) d) : null;
        }
        if (raw instanceof java.math.BigInteger bi) return bi.bitLength() < 63 ? Long.valueOf(bi.longValue()) : null;
        if (raw instanceof java.math.BigDecimal bd) {
            try {
                return Long.valueOf(bd.longValueExact());
            } catch (ArithmeticException e) {
                return null;
            }
        }
        return null;
    }

    // ── Dosya adı ───────────────────────────────────────────────────────────────────────────

    /**
     * İndirilen dosyanın adı: adresin SON yol parçası (yüzde kodlaması çözülmüş), uzantısı atılmış, yalnız harf / rakam /
     * {@code - _ .} bırakılmış (diğer her karakter {@code _}), en çok {@value #FILE_NAME_MAX} karakter; boşsa {@code image}.
     * Uzantı adresten DEĞİL tespit edilen türden gelir. Doğrusal (parça ≤ adres uzunluğu).
     */
    static String fileName(String url, String type) {
        String segment = "";
        try {
            String path = URI.create(url).getPath();
            if (path != null) segment = path.substring(path.lastIndexOf('/') + 1);
        } catch (RuntimeException e) {
            segment = "";
        }
        int dot = segment.lastIndexOf('.');
        if (dot > 0) segment = segment.substring(0, dot);
        StringBuilder sb = new StringBuilder(Math.min(segment.length(), FILE_NAME_MAX));
        for (int i = 0; i < segment.length() && sb.length() < FILE_NAME_MAX; i++) {
            char c = segment.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        String base = sb.toString();
        while (base.startsWith(".")) base = base.substring(1);
        if (base.isBlank() || base.chars().allMatch(ch -> ch == '_' || ch == '.')) base = "image";
        return base + "." + ImageDimensions.extension(type);
    }

    /**
     * {@code attachment; filename="<ASCII>"} (+ ad ASCII değilse {@code filename*=UTF-8''<yüzde kodlu>}). Ad
     * {@link #fileName} çıktısıdır: tırnak, ters bölü, satır sonu taşıyamaz.
     */
    static String contentDisposition(String name) {
        StringBuilder ascii = new StringBuilder(name.length());
        boolean plain = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x80) {
                ascii.append(c);
            } else {
                ascii.append('_');
                plain = false;
            }
        }
        String out = "attachment; filename=\"" + ascii + "\"";
        if (plain) return out;
        StringBuilder enc = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            if ((v >= '0' && v <= '9') || (v >= 'a' && v <= 'z') || (v >= 'A' && v <= 'Z') || v == '-' || v == '_' || v == '.') {
                enc.append((char) v);
            } else {
                enc.append('%').append(Character.toUpperCase(Character.forDigit(v >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(v & 0xF, 16)));
            }
        }
        return out + "; filename*=UTF-8''" + enc;
    }

    private static long mib(long bytes) {
        return bytes / (1024L * 1024L);
    }

    // ── Yardımcılar ─────────────────────────────────────────────────────────────────────────

    private static String userKey(HttpSession session) {
        Object uid = session == null ? null : session.getAttribute("userId");
        if (uid != null) return "u:" + uid;
        Object u = session == null ? null : session.getAttribute("username");
        return u != null ? "n:" + u : "s:" + (session == null ? "anon" : session.getId());
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("timestamp", ISO.format(Instant.now()));
        return body;
    }

    private static Map<String, Object> errorBody(String code, String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", msg);
        String rid = CorrelationIdFilter.current();
        if (rid != null) body.put("request_id", rid);
        return body;
    }

    /** Hata yanıtı HER ZAMAN JSON'dur (istemci {@code Accept: image/*} gönderse de içerik pazarlığına girmez). */
    private static ResponseEntity.BodyBuilder json(int status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String msg) {
        return json(status).body(errorBody(code, msg));
    }

    /**
     * İzleme yok ya da oturum onu göremiyor: KENDİ kodu (arayüz "izleme artık sana görünmüyor" der). Genel {@code NOT_FOUND}
     * değil — o, yeni ucu tanımayan ESKİ pod'un yanıtıdır ({@code SpaNotFoundAdvice}) ve arayüz onu "bu sürümde yok" diye okur.
     * Gövde {@code GlobalExceptionHandler.CodedNotFoundException} biçimiyle aynıdır; istisna atılmaz, çünkü bu uçların hata
     * yanıtı istemci {@code Accept: image/*} gönderse de HER ZAMAN JSON'dur ({@link #json}).
     */
    public static final String MONITOR_NOT_FOUND_CODE = "RESOURCE_MONITOR_NOT_FOUND";

    private static ResponseEntity<Map<String, Object>> notFound() {
        return error(404, MONITOR_NOT_FOUND_CODE, Msg.t("Sayfa Bütünlüğü izlemesi bulunamadı ya da görüntüleme yetkiniz yok.",
                "Page integrity monitor not found, or you are not allowed to view it."));
    }

    private static ResponseEntity<Map<String, Object>> scanNotFound() {
        return error(404, "RESOURCE_SCAN_NOT_FOUND", Msg.t(
                "Kaynak analizi kaydı bulunamadı; silinmiş ya da saklama süresi dolmuş olabilir. Geçmişi yenileyin.",
                "Resource analysis run not found; it may have been deleted or aged out. Refresh the history."));
    }

    private static ResponseEntity<Map<String, Object>> itemsGone() {
        return error(409, "RESOURCE_SCAN_ITEMS_GONE", Msg.t(
                "Bu analizin nesne ayrıntısı artık saklanmıyor (yalnız son analizlerin ayrıntısı tutulur) ya da analiz "
                        + "tamamlanmadı. Analizi yeniden çalıştırın.",
                "The object detail of this analysis is no longer kept (only the latest analyses keep it), or the analysis "
                        + "did not finish. Run the analysis again."));
    }

    private static ResponseEntity<Map<String, Object>> tooMany(String code, long retryAfter, String msg) {
        Map<String, Object> body = errorBody(code, msg);
        body.put("retry_after_seconds", retryAfter);
        return json(429).header("Retry-After", String.valueOf(Math.max(1, retryAfter))).body(body);
    }
}
