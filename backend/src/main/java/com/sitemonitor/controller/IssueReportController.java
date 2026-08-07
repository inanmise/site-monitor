package com.sitemonitor.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.LoginIssueReport;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.EmailNotificationService.InlineImage;
import com.sitemonitor.service.LoginIssueMailService;
import com.sitemonitor.service.LoginIssueService;
import com.sitemonitor.service.LoginIssueService.ParsedImage;
import com.sitemonitor.service.LoginIssueService.ReportMeta;
import com.sitemonitor.service.SecretMask;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oturum içi kullanıcı-tetiklemeli "Sorun Bildir" — {@code POST /api/issue-reports} (AUTH'lu;
 * whitelist'te DEĞİL, AuthInterceptor oturum ister). Login-öncesi bildirimler mevcut public
 * {@code /api/login-help} akışını kullanır; bu uç yalnız oturumlu kullanıcı içindir.
 *
 * <p>Kural 1 (kullanıcıya bilineni sorma): kimlik OTURUMDAN, zaman SUNUCUDAN; URL/sekme/sürüm/
 * ekran/tema/dil istemcinin otomatik topladığı bağlamdan gelir ve {@code autoContextJson}'a
 * maskelenerek yazılır. Kullanıcı beyanı kimliği EZEMEZ.
 * <p>Kural 2 (gizlilik): {@link SecretMask#maskUrlQuery} URL'e ve hata metnine uygulanır;
 * son başarısız API çağrıları yalnız yol+durum+zaman taşır (gövde yok).
 * <p>E-posta kuralı: profil e-postası varsa o kullanılır (payload yok sayılır); yoksa payload
 * e-postası zorunlu; {@code saveEmailToProfile=true} ise profile yazılır + audit'lenir.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class IssueReportController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final int MAX_PER_WINDOW = 10;         // oturumlu kullanıcı — login-help'ten geniş
    private static final long WINDOW_MS = 60 * 60_000L;
    private static final int MAX_MESSAGE = 5000;
    private static final int MAX_ERROR_TEXT = 10_000;
    private static final int MAX_EMAIL = 255;
    private static final int MAX_CONTEXT_JSON = 20_000;
    private static final int MAX_IMAGES = 5;
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private static final int MAX_IMAGE_B64_CHARS = MAX_IMAGE_BYTES * 2;
    private static final int MAX_MAP_ENTRIES = 10_000;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern IMAGE_DATA_URL =
            Pattern.compile("^data:image/(png|jpeg);base64,([A-Za-z0-9+/=\\s]+)$");

    private final AppSettingsService appSettings;
    private final LoginIssueService loginIssueService;
    private final LoginIssueMailService loginIssueMailService;
    private final AuditService auditService;
    private final ClientIpResolver clientIpResolver;
    private final AppUserRepository appUserRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, Deque<Long>> rate = new ConcurrentHashMap<>();

    @PostMapping("/api/issue-reports")
    public ResponseEntity<Map<String, Object>> report(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        // Kimlik OTURUMDAN — interceptor auth'u garantiler; yine de savunmacı kontrol.
        Object sessUser = session != null ? session.getAttribute("username") : null;
        if (sessUser == null) return err(HttpStatus.UNAUTHORIZED, "Oturum gerekli");
        String username = sessUser.toString();

        String message = str(body.get("message"));
        if (message.isBlank()) return err(HttpStatus.BAD_REQUEST, "Açıklama boş olamaz");
        if (message.length() > MAX_MESSAGE) return err(HttpStatus.BAD_REQUEST, "Alan uzunluk sınırı aşıldı");

        String category = str(body.get("category"));
        if (!category.isBlank() && !LoginIssueService.CATEGORIES.contains(category)) {
            return err(HttpStatus.BAD_REQUEST, "Geçersiz önem değeri");
        }

        // E-posta kuralı: profil e-postası birincil; yoksa payload zorunlu.
        AppUser user = appUserRepository.findByUsername(username).orElse(null);
        String profileEmail = user != null && user.getEmail() != null && !user.getEmail().isBlank()
                ? user.getEmail().strip() : null;
        String email = profileEmail;
        boolean savedToProfile = false;
        if (email == null) {
            String bodyEmail = str(body.get("email"));
            if (bodyEmail.isBlank()) return err(HttpStatus.BAD_REQUEST, "E-posta adresi zorunludur");
            if (!EMAIL.matcher(bodyEmail).matches() || bodyEmail.length() > MAX_EMAIL)
                return err(HttpStatus.BAD_REQUEST, "Geçerli bir e-posta adresi giriniz");
            email = bodyEmail;
            if (Boolean.TRUE.equals(body.get("saveEmailToProfile")) && user != null) {
                user.setEmail(email);
                appUserRepository.save(user);
                savedToProfile = true;
            }
        }

        // Rate limit KULLANICI bazlı (oturumlu uç; IP paylaşan NAT arkasındaki kullanıcılar birbirini kilitlemesin).
        if (!allow(username)) {
            return err(HttpStatus.TOO_MANY_REQUESTS,
                    "Çok fazla bildirim gönderildi — lütfen daha sonra tekrar deneyin.");
        }

        String ip = clientIpResolver.resolve(request);
        String userAgent = request.getHeader("User-Agent");
        String now = ISO.format(Instant.now());

        // Maskeli otomatik bağlam — kural 2. Hata metni + URL query'leri SecretMask'ten geçer.
        String errorText = SecretMask.maskUrlQuery(str(body.get("errorText")));
        if (errorText.length() > MAX_ERROR_TEXT) errorText = errorText.substring(0, MAX_ERROR_TEXT) + "\n… (kırpıldı)";
        String url = SecretMask.maskUrlQuery(str(body.get("url")));
        String tabKey = str(body.get("tabKey"));
        String appVersion = str(body.get("appVersion"));
        String screenSize = str(body.get("screenSize"));
        String linkedReference = str(body.get("linkedReference"));
        String autoContextJson = buildAutoContext(body, url, ip, userAgent, now);

        // Görseller — login-help ile aynı sınırlar (≤5, png/jpeg, ≤1MB decode).
        List<InlineImage> images = new ArrayList<>();
        List<ParsedImage> parsed = new ArrayList<>();
        Object imagesRaw = body.get("images");
        if (imagesRaw instanceof List<?> list) {
            if (list.size() > MAX_IMAGES)
                return err(HttpStatus.BAD_REQUEST, "En fazla " + MAX_IMAGES + " ekran görüntüsü ekleyebilirsiniz");
            int idx = 0;
            for (Object o : list) {
                String dataUrl = str(o);
                if (dataUrl.isBlank()) continue;
                Matcher m = IMAGE_DATA_URL.matcher(dataUrl);
                if (!m.matches()) return err(HttpStatus.BAD_REQUEST, "Görseller yalnız PNG veya JPEG olabilir");
                String rawB64 = m.group(2);
                if (rawB64.length() > MAX_IMAGE_B64_CHARS)
                    return err(HttpStatus.BAD_REQUEST, "Her görsel en fazla 1MB olabilir");
                byte[] bytes;
                try { bytes = Base64.getMimeDecoder().decode(rawB64); }
                catch (IllegalArgumentException e) { return err(HttpStatus.BAD_REQUEST, "Görsel içeriği çözümlenemedi"); }
                if (bytes.length > MAX_IMAGE_BYTES)
                    return err(HttpStatus.BAD_REQUEST, "Her görsel en fazla 1MB olabilir");
                String mime = "image/" + m.group(1);
                images.add(new InlineImage("shot" + idx++, bytes, mime));
                parsed.add(new ParsedImage(mime, rawB64.replaceAll("\\s", "")));
            }
        }

        ReportMeta meta = new ReportMeta("USER_REPORT", blankToNull(category), blankToNull(appVersion),
                blankToNull(screenSize), blankToNull(tabKey), autoContextJson, blankToNull(linkedReference));
        LoginIssueReport report = loginIssueService.save(
                username, email, blankToNull(errorText), message, parsed, ip, userAgent, now, meta);
        String refCode = LoginIssueService.refCode(report);

        // Mail: digest AÇIKSA tekil admin maili atlanır (günlük özet cron'u gönderir); ACK her durumda gider.
        String adminEmail = appSettings.getString("site.monitor.system-admin.email", "");
        boolean digest = appSettings.getBoolean("site.monitor.issue-reports.daily-digest", false);
        if (adminEmail != null && !adminEmail.isBlank() && !digest) {
            loginIssueMailService.dispatchUserReport(report.getId(), refCode, adminEmail, username, email,
                    blankToNull(category), message, blankToNull(errorText), blankToNull(linkedReference),
                    blankToNull(tabKey), blankToNull(appVersion), images, ip, userAgent, now);
        }
        loginIssueMailService.dispatchAck(report.getId(), refCode, email, username,
                blankToNull(errorText), message, images, now);

        try {
            auditService.recordAction("ISSUE_REPORT", session, request, "UI", String.valueOf(report.getId()),
                    "{\"ref\":\"" + refCode + "\",\"category\":\"" + category + "\",\"images\":" + images.size()
                            + ",\"emailSavedToProfile\":" + savedToProfile + "}");
        } catch (Exception e) {
            log.warn("Sorun bildirimi {} audit kaydı yazılamadı (kayıt saklandı): {}", refCode, e.getMessage());
        }

        log.info("Sorun bildirimi {} kaydedildi: user='{}' kategori={} görsel={} digest={}",
                refCode, username, category.isBlank() ? "—" : category, images.size(), digest);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("reference", refCode);
        out.put("emailSavedToProfile", savedToProfile);
        out.put("timestamp", now);
        return ResponseEntity.ok(out);
    }

    /** Otomatik bağlamı JSON'a derler — istemciden gelen alanlar beyaz-listeli ve maskeli;
     *  sunucu bilinenleri (ip/ua/zaman) eklenir. Başarısız istek listesi yol+durum+zamanla sınırlı. */
    private String buildAutoContext(Map<String, Object> body, String maskedUrl,
                                    String ip, String userAgent, String now) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("url", maskedUrl);
        ctx.put("tab", str(body.get("tabKey")));
        ctx.put("appVersion", str(body.get("appVersion")));
        ctx.put("screenSize", str(body.get("screenSize")));
        ctx.put("theme", str(body.get("theme")));
        ctx.put("lang", str(body.get("lang")));
        ctx.put("serverTime", now);
        ctx.put("ip", ip);
        ctx.put("userAgent", userAgent);
        if (body.get("failedRequests") instanceof List<?> list) {
            List<Map<String, Object>> fr = new ArrayList<>();
            for (Object o : list) {
                if (fr.size() >= 5) break;
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("path", SecretMask.maskUrlQuery(str(m.get("path"))));
                    e.put("status", m.get("status") instanceof Number n ? n.intValue() : null);
                    e.put("at", str(m.get("at")));
                    fr.add(e);
                }
            }
            ctx.put("failedRequests", fr);
        }
        try {
            String json = mapper.writeValueAsString(ctx);
            return json.length() > MAX_CONTEXT_JSON ? json.substring(0, MAX_CONTEXT_JSON) : json;
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String str(Object o) { return o == null ? "" : o.toString().strip(); }
    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }

    /** Kullanıcı başına sliding-window; map boyutu sınırlı. LoginHelpController deseni. */
    private boolean allow(String key) {
        long now = System.currentTimeMillis();
        if (rate.size() > MAX_MAP_ENTRIES) rate.clear();
        Deque<Long> dq = rate.computeIfAbsent(key != null ? key : "?", k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && now - dq.peekFirst() > WINDOW_MS) dq.pollFirst();
            if (dq.size() >= MAX_PER_WINDOW) return false;
            dq.addLast(now);
            return true;
        }
    }

    private ResponseEntity<Map<String, Object>> err(HttpStatus status, String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("error", msg);
        out.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.status(status).body(out);
    }
}
