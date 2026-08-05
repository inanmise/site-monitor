package com.sitemonitor.controller;

import com.sitemonitor.model.LoginIssueMailLog;
import com.sitemonitor.model.LoginIssueReport;
import com.sitemonitor.model.LoginIssueReportImage;
import com.sitemonitor.repository.LoginIssueMailLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.EmailNotificationService.InlineImage;
import com.sitemonitor.service.LoginIssueMailService;
import com.sitemonitor.service.LoginIssueService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin-only Login Sorun Bildirimleri yönetimi. Yalnız {@code issues.login-reports} izni olan
 * (veya bootstrap admin) kullanıcılar listeler/görüntüler/durum değiştirir. Kayıtlar public
 * /api/login-help akışından {@link LoginIssueService} ile yazılır. Her durum değişikliği audit'lenir.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/login-issues")
@RequiredArgsConstructor
public class LoginIssueController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String PERM = "issues.login-reports";

    private final LoginIssueService loginIssueService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final LoginIssueMailService loginIssueMailService;
    private final LoginIssueMailLogRepository mailLogRepo;
    private final AppSettingsService appSettings;

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,        // hata mesajı / açıklama / kullanıcı içinde arama
            @RequestParam(required = false) String since,    // bildirim tarihi >= (ISO UTC)
            @RequestParam(required = false) String until,    // bildirim tarihi <= (ISO UTC)
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpSession session) {
        requireAccess(session, "view");
        Page<LoginIssueReport> p = loginIssueService.list(status, q, since, until, page, size);
        List<Map<String, Object>> data = new ArrayList<>();
        for (LoginIssueReport r : p.getContent()) data.add(toListItem(r));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data);
        body.put("total", p.getTotalElements());
        body.put("page", p.getNumber());
        body.put("size", p.getSize());
        body.put("counts", loginIssueService.counts(since, until));   // kartlar liste tarih penceresiyle uyumlu
        return ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id, HttpSession session) {
        requireAccess(session, "view");
        return loginIssueService.get(id)
                .map(r -> ok(Map.of("data", toDetail(r))))
                .orElseGet(this::notFound);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        requireAccess(session, "edit");
        if (loginIssueService.get(id).isEmpty()) return notFound();
        String newStatus = str(body.get("status"));
        String note = str(body.get("resolutionNote"));
        LoginIssueReport updated;
        try {
            updated = loginIssueService.updateStatus(id, newStatus, note, actor(session));
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        // Best-effort audit — durum değişikliği zaten commit'lendi; audit hatası 500'e yol açmasın.
        try {
            auditService.recordAction("LOGIN_ISSUE_STATUS_CHANGE", session, request,
                    "LOGIN_ISSUE", String.valueOf(id),
                    "{\"status\":\"" + updated.getStatus() + "\"}");
        } catch (Exception e) {
            log.warn("Login issue {} durum değişikliği audit kaydı yazılamadı: {}", id, e.getMessage());
        }
        // Çözümlendiğinde "çözüldü" bildirimi HEM bildirene (To) HEM sistem yöneticisine (CC) gider
        // (karşılıklı bilgilendirme). Best-effort; hata akışı kırmaz.
        if (LoginIssueService.RESOLVED.equals(updated.getStatus())) {
            String adminEmail = appSettings.getString("site.monitor.system-admin.email", "");
            // Bildirimin ekran görüntülerini çözümlendi mailine yeniden ekle (CID inline; bozuk görsel atlanır).
            List<InlineImage> images = new ArrayList<>();
            int idx = 0;
            for (LoginIssueReportImage img : loginIssueService.images(updated.getId())) {
                try {
                    byte[] bytes = Base64.getMimeDecoder().decode(img.getDataBase64());
                    images.add(new InlineImage("shot" + idx++, bytes, img.getContentType()));
                } catch (Exception ignore) { /* bozuk görsel → atla */ }
            }
            // ASYNC gönderim + login_issue_mail_logs kaydı (mail geçmişi). Zenginleştirilmiş içerik:
            // bildirim zamanı + orijinal sorun (hata + açıklama) + ekran görüntüleri + çözüm notu. Bildiren To, admin CC.
            loginIssueMailService.dispatchResolved(updated.getId(), LoginIssueService.refCode(updated),
                    updated.getReporterEmail(), adminEmail, updated.getUsername(), updated.getErrorText(),
                    updated.getMessage(), updated.getReportedAt(), updated.getResolutionNote(),
                    updated.getResolvedAt(), images);
        }
        return ok(Map.of("data", toDetail(updated), "message", "Durum güncellendi"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Bootstrap admin (login'de set edilen bayrak) her zaman erişir; aksi halde matris izni. */
    private void requireAccess(HttpSession session, String action) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        permissionService.require(session, PERM, action);
    }

    /** Liste satırı — resimler taşınmaz (hafif); yalnız metadata + özet. */
    private Map<String, Object> toListItem(LoginIssueReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("refCode", LoginIssueService.refCode(r));
        m.put("username", r.getUsername());
        m.put("messageSummary", summarize(r.getMessage()));
        m.put("ipAddress", r.getIpAddress());
        m.put("reportedAt", r.getReportedAt());
        m.put("resolvedAt", r.getResolvedAt());   // ana tabloda "Çözülme Tarihi" kolonu için
        m.put("resolvedBy", r.getResolvedBy());
        m.put("status", r.getStatus());
        m.put("imageCount", r.getImageCount());
        return m;
    }

    /** Detay — tam alanlar + resimler (data-URL). */
    private Map<String, Object> toDetail(LoginIssueReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("refCode", LoginIssueService.refCode(r));
        m.put("username", r.getUsername());
        m.put("reporterEmail", r.getReporterEmail());
        m.put("errorText", r.getErrorText());
        m.put("message", r.getMessage());
        m.put("ipAddress", r.getIpAddress());
        m.put("userAgent", r.getUserAgent());
        m.put("status", r.getStatus());
        m.put("reportedAt", r.getReportedAt());
        m.put("resolvedBy", r.getResolvedBy());
        m.put("resolvedAt", r.getResolvedAt());
        m.put("resolutionNote", r.getResolutionNote());
        m.put("imageCount", r.getImageCount());
        List<String> imgs = new ArrayList<>();
        for (LoginIssueReportImage img : loginIssueService.images(r.getId())) {
            imgs.add("data:" + img.getContentType() + ";base64," + img.getDataBase64());
        }
        m.put("images", imgs);
        // Gönderilen e-postalar (mail geçmişi) — kime/ne zaman/hangi tür + teslim durumu.
        List<Map<String, Object>> mails = new ArrayList<>();
        for (LoginIssueMailLog ml : mailLogRepo.findByReportIdOrderByIdAsc(r.getId())) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("mailType", ml.getMailType());
            mm.put("from", ml.getEmailFrom());
            mm.put("to", ml.getRecipientTo());
            mm.put("cc", ml.getCc());
            mm.put("subject", ml.getSubject());
            mm.put("body", ml.getBodyHtml());
            mm.put("status", ml.getStatus());
            mm.put("error", ml.getErrorMessage());
            mm.put("forced", ml.isForced());
            mm.put("sentAt", ml.getSentAt());
            mails.add(mm);
        }
        m.put("mailHistory", mails);
        return m;
    }

    private static String summarize(String msg) {
        if (msg == null) return "";
        String s = msg.strip().replaceAll("\\s+", " ");
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }

    private static String str(Object o) { return o == null ? "" : o.toString().strip(); }

    private String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "anonymous";
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<Map<String, Object>> notFound() {
        return err(HttpStatus.NOT_FOUND, "Kayıt bulunamadı");
    }

    private ResponseEntity<Map<String, Object>> err(HttpStatus status, String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", false);
        out.put("error", msg);
        out.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.status(status).body(out);
    }
}
