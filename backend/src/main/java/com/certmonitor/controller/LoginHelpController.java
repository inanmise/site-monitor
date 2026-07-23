package com.certmonitor.controller;

import com.certmonitor.service.AppSettingsService;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.ClientIpResolver;
import com.certmonitor.service.EmailNotificationService;
import com.certmonitor.service.EmailNotificationService.InlineImage;
import jakarta.servlet.http.HttpServletRequest;
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
 * Login sayfası "sorun bildir" akışı — PUBLIC (auth YOK; kullanıcı zaten giriş yapamıyor).
 * Bildirim, Genel Ayarlar'daki Sistem Yöneticisi E-postası'na
 * ({@code cert.monitor.system-admin.email}, canlı okunur) Outlook-güvenli mail olarak gider;
 * ekran görüntüsü varsa maile CID inline gömülür.
 *
 * <p>Kimliksiz yazma endpoint'i olduğundan kötüye kullanım önlemleri: IP başına saatte 3 bildirim
 * (sliding window, boyut sınırlı map), alan uzunluk sınırları (username zorunlu ≤100, errorText
 * ≤2000, message zorunlu ≤5000), görsel yalnız png/jpeg data-URL + decode ≤1MB (SVG kabul edilmez —
 * kullanıcı içeriği), alıcı sabit, içerik mailde tamamen escape'lenir. Yanıt her durumda jenerik
 * başarı (SMTP durumu/e-posta adresi sızdırılmaz); yalnız rate-limit 429 ve doğrulama 400 döner.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LoginHelpController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final int MAX_PER_WINDOW = 3;
    private static final long WINDOW_MS = 60 * 60_000L;   // 1 saat
    private static final int MAX_USERNAME = 100;
    private static final int MAX_ERROR_TEXT = 2000;
    private static final int MAX_MESSAGE = 5000;
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;   // 1MB (decode) — görsel başına
    private static final int MAX_IMAGES = 5;                  // en fazla 5 ekran görüntüsü
    private static final int MAX_MAP_ENTRIES = 10_000;

    /** Yalnız raster formatlar — SVG bilinçli hariç (kimliksiz kullanıcı içeriği). */
    private static final Pattern IMAGE_DATA_URL =
            Pattern.compile("^data:image/(png|jpeg);base64,([A-Za-z0-9+/=\\s]+)$");

    private final AppSettingsService appSettings;
    private final EmailNotificationService emailService;
    private final AuditService auditService;
    private final ClientIpResolver clientIpResolver;

    private final Map<String, Deque<Long>> rate = new ConcurrentHashMap<>();

    @PostMapping("/api/login-help")
    public ResponseEntity<Map<String, Object>> report(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String username  = str(body.get("username"));
        String errorText = str(body.get("errorText"));
        String message   = str(body.get("message"));

        if (username.isBlank()) return err(HttpStatus.BAD_REQUEST, "Kullanıcı adı zorunludur");
        if (message.isBlank())  return err(HttpStatus.BAD_REQUEST, "Açıklama boş olamaz");
        if (username.length() > MAX_USERNAME || errorText.length() > MAX_ERROR_TEXT
                || message.length() > MAX_MESSAGE) {
            return err(HttpStatus.BAD_REQUEST, "Alan uzunluk sınırı aşıldı");
        }

        // Görseller: yeni istemci `images` dizisi gönderir; tekil `image` alanı geriye dönük desteklenir.
        List<String> dataUrls = new ArrayList<>();
        Object imagesRaw = body.get("images");
        if (imagesRaw instanceof List<?> list) {
            for (Object o : list) { String s = str(o); if (!s.isBlank()) dataUrls.add(s); }
        }
        String single = str(body.get("image"));
        if (!single.isBlank()) dataUrls.add(single);
        if (dataUrls.size() > MAX_IMAGES) {
            return err(HttpStatus.BAD_REQUEST, "En fazla " + MAX_IMAGES + " ekran görüntüsü ekleyebilirsiniz");
        }

        List<InlineImage> images = new ArrayList<>();
        int idx = 0;
        for (String dataUrl : dataUrls) {
            Matcher m = IMAGE_DATA_URL.matcher(dataUrl);
            if (!m.matches()) {
                return err(HttpStatus.BAD_REQUEST, "Görseller yalnız PNG veya JPEG olabilir");
            }
            byte[] bytes;
            try {
                bytes = Base64.getMimeDecoder().decode(m.group(2));
            } catch (IllegalArgumentException e) {
                return err(HttpStatus.BAD_REQUEST, "Görsel içeriği çözümlenemedi");
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                return err(HttpStatus.BAD_REQUEST, "Her görsel en fazla 1MB olabilir");
            }
            images.add(new InlineImage("shot" + idx++, bytes, "image/" + m.group(1)));
        }

        String ip = clientIpResolver.resolve(request);
        if (!allow(ip)) {
            return err(HttpStatus.TOO_MANY_REQUESTS,
                    "Çok fazla bildirim gönderildi — lütfen daha sonra tekrar deneyin.");
        }

        String adminEmail = appSettings.getString("cert.monitor.system-admin.email", "");
        String userAgent = request.getHeader("User-Agent");
        String now = ISO.format(Instant.now());
        if (adminEmail != null && !adminEmail.isBlank()) {
            String status = emailService.sendLoginIssueReport(
                    adminEmail, username, errorText, message, images, ip, userAgent, now);
            log.info("Login sorun bildirimi: user='{}' ip={} images={} → {} ({})",
                    username, ip, images.size(), adminEmail, status);
        } else {
            log.warn("Login sorun bildirimi geldi ama system-admin.email boş — mail gönderilemedi (user='{}' ip={})",
                    username, ip);
        }
        auditService.recordAction("LOGIN_HELP_REPORT",
                username, null, null, null,
                "LOGIN", ip,
                "{\"messageChars\":" + message.length() + ",\"errorChars\":" + errorText.length()
                        + ",\"images\":" + images.size() + "}",
                ip, userAgent, null);

        // Jenerik yanıt — admin e-postasının varlığı/SMTP sonucu dışarı sızdırılmaz.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", "Bildiriminiz alındı");
        out.put("timestamp", now);
        return ResponseEntity.ok(out);
    }

    /** JSON değerini güvenle string'e indirger (null/sayı/bool → boş/strip'li metin). */
    private static String str(Object o) {
        return o == null ? "" : o.toString().strip();
    }

    /** IP başına sliding-window (1 saat / 3 bildirim); map boyutu sınırlı (heap koruması). */
    private boolean allow(String ip) {
        long now = System.currentTimeMillis();
        if (rate.size() > MAX_MAP_ENTRIES) rate.clear();
        Deque<Long> dq = rate.computeIfAbsent(ip != null ? ip : "?", k -> new ArrayDeque<>());
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
