package com.certmonitor.controller;

import com.certmonitor.service.AppSettingsService;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.ClientIpResolver;
import com.certmonitor.service.EmailNotificationService.InlineImage;
import com.certmonitor.service.LoginIssueMailService;
import com.certmonitor.service.LoginIssueService;
import com.certmonitor.service.LoginIssueService.ParsedImage;
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
 * <p>E-postaya EK olarak her bildirim resimleriyle birlikte DB'ye ({@link LoginIssueService}) yazılır;
 * yalnız yetkili adminler /api/admin/login-issues ekranından görüntüleyip çözer. DB kaydı e-posta
 * durumundan bağımsızdır (mail hatası kaydı engellemez). {@code cert.monitor.login-issues.enabled}
 * kapalıysa endpoint 404 döner.
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
    private static final int MAX_EMAIL = 255;
    private static final int MAX_ERROR_TEXT = 2000;
    private static final int MAX_MESSAGE = 5000;

    /** Basit e-posta biçim kontrolü (kimliksiz kullanıcı girişi — kesin RFC değil, makul filtre). */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;   // 1MB (decode) — görsel başına
    /** Base64 metnini DECODE ETMEDEN önce kaba uzunluk sınırı (dev boyutlu string decode → heap spike engeli).
     *  1MB ikili ≈ 1.4M base64 karakter; boşluk payıyla 2×MAX_IMAGE_BYTES fazlasıyla yeterli. */
    private static final int MAX_IMAGE_B64_CHARS = MAX_IMAGE_BYTES * 2;
    private static final int MAX_IMAGES = 5;                  // en fazla 5 ekran görüntüsü
    private static final int MAX_MAP_ENTRIES = 10_000;

    /** Yalnız raster formatlar — SVG bilinçli hariç (kimliksiz kullanıcı içeriği). */
    private static final Pattern IMAGE_DATA_URL =
            Pattern.compile("^data:image/(png|jpeg);base64,([A-Za-z0-9+/=\\s]+)$");

    private final AppSettingsService appSettings;
    private final LoginIssueMailService loginIssueMailService;
    private final AuditService auditService;
    private final ClientIpResolver clientIpResolver;
    private final LoginIssueService loginIssueService;

    private final Map<String, Deque<Long>> rate = new ConcurrentHashMap<>();

    @PostMapping("/api/login-help")
    public ResponseEntity<Map<String, Object>> report(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        // Özellik kapalıysa endpoint yok gibi davran (login link'i değişmez; gönderim 404 alır).
        if (!appSettings.getBoolean("cert.monitor.login-issues.enabled", true)) {
            return err(HttpStatus.NOT_FOUND, "Bu özellik devre dışı");
        }
        String username  = str(body.get("username"));
        String email     = str(body.get("email"));
        String errorText = str(body.get("errorText"));
        String message   = str(body.get("message"));

        if (username.isBlank()) return err(HttpStatus.BAD_REQUEST, "Kullanıcı adı zorunludur");
        if (email.isBlank())    return err(HttpStatus.BAD_REQUEST, "E-posta adresi zorunludur");
        if (!EMAIL.matcher(email).matches() || email.length() > MAX_EMAIL)
            return err(HttpStatus.BAD_REQUEST, "Geçerli bir e-posta adresi giriniz");
        if (message.isBlank())  return err(HttpStatus.BAD_REQUEST, "Açıklama boş olamaz");
        if (username.length() > MAX_USERNAME || errorText.length() > MAX_ERROR_TEXT
                || message.length() > MAX_MESSAGE) {
            return err(HttpStatus.BAD_REQUEST, "Alan uzunluk sınırı aşıldı");
        }

        // Rate-limit'i PAHALI görsel decode'undan ÖNCE uygula: kimliksiz çağıran throttle'a takılmadan
        // ≤5×~1MB base64 decode ettirip heap şişirmesin. Ucuz alan doğrulaması yukarıda kaldı (boş form
        // token harcamaz); ip aşağıda kayıt/mail/audit için de kullanılır.
        String ip = clientIpResolver.resolve(request);
        if (!allow(ip)) {
            return err(HttpStatus.TOO_MANY_REQUESTS,
                    "Çok fazla bildirim gönderildi — lütfen daha sonra tekrar deneyin.");
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

        List<InlineImage> images = new ArrayList<>();          // e-posta CID inline
        List<ParsedImage> parsed = new ArrayList<>();          // DB kalıcılık (base64 TEXT)
        int idx = 0;
        for (String dataUrl : dataUrls) {
            Matcher m = IMAGE_DATA_URL.matcher(dataUrl);
            if (!m.matches()) {
                return err(HttpStatus.BAD_REQUEST, "Görseller yalnız PNG veya JPEG olabilir");
            }
            String rawB64 = m.group(2);
            if (rawB64.length() > MAX_IMAGE_B64_CHARS) {   // DECODE ETMEDEN önce sınırla (heap koruması)
                return err(HttpStatus.BAD_REQUEST, "Her görsel en fazla 1MB olabilir");
            }
            String cleanB64 = rawB64.replaceAll("\\s", "");
            byte[] bytes;
            try {
                bytes = Base64.getMimeDecoder().decode(rawB64);
            } catch (IllegalArgumentException e) {
                return err(HttpStatus.BAD_REQUEST, "Görsel içeriği çözümlenemedi");
            }
            if (bytes.length > MAX_IMAGE_BYTES) {
                return err(HttpStatus.BAD_REQUEST, "Her görsel en fazla 1MB olabilir");
            }
            String mime = "image/" + m.group(1);
            images.add(new InlineImage("shot" + idx++, bytes, mime));
            parsed.add(new ParsedImage(mime, cleanB64));
        }

        String userAgent = request.getHeader("User-Agent");
        String now = ISO.format(Instant.now());

        // DB kalıcılık — BİRİNCİL kayıt (admin ekranı bunun üzerinden çalışır). Mail'den bağımsız.
        com.certmonitor.model.LoginIssueReport report =
                loginIssueService.save(username, email, errorText, message, parsed, ip, userAgent, now);
        String refCode = LoginIssueService.refCode(report);

        // Bilgilendirme mailleri ASYNC (loginIssueMailService) — request thread'ini bloklamaz; her gönderim
        // login_issue_mail_logs'a yazılır (admin ekranındaki mail geçmişi). DB kaydı birincil.
        Long reportId = report.getId();
        String adminEmail = appSettings.getString("cert.monitor.system-admin.email", "");
        if (adminEmail != null && !adminEmail.isBlank()) {
            log.info("Login sorun bildirimi {} kaydedildi: user='{}' ip={} images={} → admin maili kuyruğa alındı ({})",
                    refCode, username, ip, images.size(), adminEmail);
            loginIssueMailService.dispatchReport(reportId, refCode, adminEmail, username, errorText, message, images, ip, userAgent, now);
        } else {
            log.info("Login sorun bildirimi {} kaydedildi; system-admin.email boş — admin maili atlandı (user='{}' ip={})",
                    refCode, username, ip);
        }
        // Bildiren kişiye "alındı" onayı (benzer içerik + referans no) — ASYNC, best-effort + loglanır.
        loginIssueMailService.dispatchAck(reportId, refCode, email, username, errorText, message, images, now);
        // Best-effort audit — kayıt zaten commit'lendi + referans verildi; audit-insert hatası
        // kullanıcıya 500 döndürüp gereksiz resubmit'e yol açmasın (mailler gibi swallow).
        try {
            auditService.recordAction("LOGIN_HELP_REPORT",
                    username, null, null, null,
                    "LOGIN", ip,
                    "{\"ref\":\"" + refCode + "\",\"messageChars\":" + message.length()
                            + ",\"errorChars\":" + errorText.length() + ",\"images\":" + images.size() + "}",
                    ip, userAgent, null);
        } catch (Exception e) {
            log.warn("Login sorun bildirimi {} audit kaydı yazılamadı (kayıt saklandı): {}", refCode, e.getMessage());
        }

        // Referans numarası kullanıcıya döner (durum takibi için); SMTP/admin durumu sızdırılmaz.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("message", "Bildiriminiz alındı");
        out.put("reference", refCode);
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
