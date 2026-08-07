package com.sitemonitor.controller;

import com.sitemonitor.model.LoginIssueReport;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.LoginIssueMailService;
import com.sitemonitor.service.LoginIssueService;
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
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ErrorBoundary otomatik çökme bildirimi — frontend'te bir ekran çöktüğünde ("Bir şey ters gitti")
 * hata + component stack buraya POST edilir; kayıt login sorun-bildirimleri ekranına
 * ({@code /api/admin/login-issues}) düşer ve Sistem Yöneticisi E-postası'na async mail gider.
 *
 * <p>PUBLIC endpoint (AuthInterceptor whitelist): çökme login sayfasında da olabilir. Oturum varsa
 * kullanıcı adı oturumdan okunur — istemci beyanına güvenilmez. Kimliksiz yazma yüzeyi olduğundan
 * kötüye kullanım önlemleri LoginHelpController ile aynı desendir: IP başına saatte 5 bildirim
 * (sliding window, boyut sınırlı map), alan uzunluk sınırları, alıcı sabit, içerik mailde escape'lenir.
 * {@code site.monitor.client-errors.enabled} kapalıysa 404 döner.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ClientErrorController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final int MAX_PER_WINDOW = 5;          // çökme tekrar edebilir; login-help'ten biraz geniş
    private static final long WINDOW_MS = 60 * 60_000L;   // 1 saat
    private static final int MAX_ERROR_TEXT = 10_000;     // stack + component stack
    private static final int MAX_URL = 500;
    private static final int MAX_MAP_ENTRIES = 10_000;

    private final AppSettingsService appSettings;
    private final LoginIssueService loginIssueService;
    private final LoginIssueMailService loginIssueMailService;
    private final AuditService auditService;
    private final ClientIpResolver clientIpResolver;

    private final Map<String, Deque<Long>> rate = new ConcurrentHashMap<>();

    @PostMapping("/api/client-error-report")
    public ResponseEntity<Map<String, Object>> report(
            @RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (!appSettings.getBoolean("site.monitor.client-errors.enabled", true)) {
            return err(HttpStatus.NOT_FOUND, "Bu özellik devre dışı");
        }
        // Gizlilik (kural 2): URL query'sindeki ve stack içindeki hassas parametre değerleri maskelenir.
        String errorText = com.sitemonitor.service.SecretMask.maskUrlQuery(str(body.get("errorText")));
        String url       = com.sitemonitor.service.SecretMask.maskUrlQuery(str(body.get("url")));
        if (errorText.isBlank()) return err(HttpStatus.BAD_REQUEST, "Hata metni boş olamaz");
        // Uzunluk aşımında reddetme — kırp: otomatik bildirimde kullanıcıdan düzeltme istenemez.
        if (errorText.length() > MAX_ERROR_TEXT) errorText = errorText.substring(0, MAX_ERROR_TEXT) + "\n… (kırpıldı)";
        if (url.length() > MAX_URL) url = url.substring(0, MAX_URL);

        String ip = clientIpResolver.resolve(request);
        if (!allow(ip)) {
            return err(HttpStatus.TOO_MANY_REQUESTS,
                    "Çok fazla bildirim gönderildi — lütfen daha sonra tekrar deneyin.");
        }

        // Kullanıcı adı YALNIZ sunucu tarafındaki oturumdan — istemci beyanı kabul edilmez (spoof önlemi).
        HttpSession session = request.getSession(false);
        Object sessUser = session != null ? session.getAttribute("username") : null;
        String username = sessUser != null ? sessUser.toString() : "(oturumsuz)";

        String userAgent = request.getHeader("User-Agent");
        String now = ISO.format(Instant.now());
        String message = "Otomatik istemci hatası bildirimi (ErrorBoundary — uygulama içi ekran çökmesi)."
                + (url.isBlank() ? "" : "\nSayfa: " + url);

        // DB kalıcılık — BİRİNCİL kayıt; sorun-bildirimleri ekranına düşer. Mail'den bağımsız.
        // tab anahtarı URL'den (?tab=...) çıkarılır; sürüm/ekran istemci beyanı (kimlik DEĞİL — güvenle alınır).
        String tabKey = extractTab(url);
        LoginIssueService.ReportMeta meta = new LoginIssueService.ReportMeta(
                "CLIENT_ERROR", null, str(body.get("appVersion")), str(body.get("screenSize")),
                tabKey, null, null);
        LoginIssueReport report = loginIssueService.save(
                username, "", errorText, message, List.of(), ip, userAgent, now, meta);
        String refCode = LoginIssueService.refCode(report);

        String adminEmail = appSettings.getString("site.monitor.system-admin.email", "");
        if (adminEmail != null && !adminEmail.isBlank()) {
            log.info("Uygulama hatası bildirimi {} kaydedildi: user='{}' ip={} → admin maili kuyruğa alındı ({})",
                    refCode, username, ip, adminEmail);
            loginIssueMailService.dispatchClientError(report.getId(), refCode, adminEmail,
                    username, errorText, message, ip, userAgent, now);
        } else {
            log.info("Uygulama hatası bildirimi {} kaydedildi; system-admin.email boş — admin maili atlandı (user='{}' ip={})",
                    refCode, username, ip);
        }

        // Best-effort audit — kayıt zaten commit'lendi; audit hatası 500'e yol açmasın.
        try {
            auditService.recordAction("CLIENT_ERROR_REPORT",
                    username, null, null, null,
                    "UI", ip,
                    "{\"ref\":\"" + refCode + "\",\"errorChars\":" + errorText.length() + "}",
                    ip, userAgent, null);
        } catch (Exception e) {
            log.warn("Uygulama hatası bildirimi {} audit kaydı yazılamadı (kayıt saklandı): {}", refCode, e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("reference", refCode);
        out.put("timestamp", now);
        return ResponseEntity.ok(out);
    }

    private static String str(Object o) { return o == null ? "" : o.toString().strip(); }

    /** URL'den aktif sekme anahtarını çıkarır (?tab=xyz) — yoksa boş. */
    static String extractTab(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]tab=([^&#\\s]+)").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    /** IP başına sliding-window; map boyutu sınırlı (heap koruması). LoginHelpController deseni. */
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
