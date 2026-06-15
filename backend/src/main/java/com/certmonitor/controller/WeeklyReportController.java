package com.certmonitor.controller;

import com.certmonitor.model.WeeklyReport;
import com.certmonitor.model.WeeklyReportImage;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.WeeklyReportReminderService;
import com.certmonitor.service.WeeklyReportService;
import com.certmonitor.service.WeeklyReportService.Actor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Haftalık rapor REST API'si. Yetki kontrolleri WeeklyReportService'tedir
 * (USER kendi takımını düzenler; onay/iade PO/TEAM_ADMIN/ADMIN);
 * AuthInterceptor /api/** için oturum zorunluluğunu zaten uygular.
 */
@Slf4j
@RestController
@RequestMapping("/api/weekly-reports")
@RequiredArgsConstructor
public class WeeklyReportController {

    private final WeeklyReportService service;
    private final WeeklyReportReminderService reminderService;
    private final AuditService auditService;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Integer year,
            HttpSession session) {
        List<WeeklyReport> reports = service.list(teamId, year, actor(session));
        // Liste görünümünde content_json taşınmaz (boyut) — özet alanlar yeter
        Map<Long, String> mailStatuses = service.lastMailStatuses(
                reports.stream().map(WeeklyReport::getId).toList());
        List<Map<String, Object>> summaries = reports.stream().map(r -> {
            Map<String, Object> m = summary(r);
            m.put("last_mail_status", mailStatuses.get(r.getId()));
            return m;
        }).toList();
        return ok(Map.of("data", summaries));
    }

    /** Raporun mail gönderim geçmişi — Geçmiş modal'ı. */
    @GetMapping("/{id}/mails")
    public ResponseEntity<Map<String, Object>> mails(@PathVariable Long id, HttpSession session) {
        List<Map<String, Object>> data = service.mails(id, actor(session)).stream().map(m -> {
            Map<String, Object> x = new LinkedHashMap<String, Object>();
            x.put("id", m.getId());
            x.put("mail_type", m.getMailType());
            x.put("from_address", m.getFromAddress());
            x.put("to_addresses", m.getToAddresses());
            x.put("cc_addresses", m.getCcAddresses());
            x.put("subject", m.getSubject());
            x.put("status", m.getStatus());
            x.put("created_by", m.getCreatedBy());
            x.put("created_at", m.getCreatedAt());
            x.put("body_html", m.getBodyHtml());
            return (Map<String, Object>) x;
        }).toList();
        return ok(Map.of("data", data));
    }

    @GetMapping("/years")
    public ResponseEntity<Map<String, Object>> years(
            @RequestParam(required = false) Long teamId, HttpSession session) {
        return ok(Map.of("data", service.years(teamId, actor(session))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id, HttpSession session) {
        Actor a = actor(session);
        WeeklyReport r = service.get(id, a);
        List<Map<String, Object>> images = service.imagesMeta(r.getId()).stream()
                .map(this::imageMeta).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("report", r);
        data.put("images", images);
        data.put("manager_contact_missing", service.managerContactMissing(r.getTeamId()));
        // Bayatlık SUNUCUDA hesaplanır — istemci saatine güvenilmez
        data.put("lock_holder", service.lockHeldByOther(r, a)
                ? Map.of("name", r.getEditingBy(), "heartbeat_at", r.getEditingHeartbeat())
                : null);
        return ok(Map.of("data", data));
    }

    // ── Düzenleme kilidi ──────────────────────────────────────────────────────

    @PostMapping("/{id}/lock")
    public ResponseEntity<Map<String, Object>> lock(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force,
            HttpSession session) {
        return ok(Map.of("data", service.acquireLock(id, force, actor(session))));
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<Map<String, Object>> unlock(@PathVariable Long id, HttpSession session) {
        service.releaseLock(id, actor(session));
        return ok(Map.of("message", "Released"));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        Actor a = actor(session);
        Long teamId = toLong(body.get("team_id"));
        Integer year = toInt(body.get("year"));
        Integer weekNo = toInt(body.get("week_no"));
        if (year == null || weekNo == null) {
            throw new IllegalArgumentException("year ve week_no zorunludur");
        }
        WeeklyReport r = service.create(teamId, year, weekNo, a);
        auditService.recordAction("WEEKLY_REPORT_CREATE", session, request,
                "WEEKLY_REPORT", r.getId().toString(),
                "{\"team_id\":" + r.getTeamId() + ",\"week\":\"" + r.getWeekLabel() + "\"}");
        return ok(Map.of("data", r));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> save(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        String contentJson = body.get("content_json") != null ? body.get("content_json").toString() : null;
        WeeklyReport r = service.saveContent(id, contentJson, toLong(body.get("version")), actor(session));
        auditService.recordAction("WEEKLY_REPORT_SAVE", session, request,
                "WEEKLY_REPORT", id.toString(),
                "{\"team_id\":" + r.getTeamId() + ",\"week\":\"" + r.getWeekLabel() + "\"}");
        return ok(Map.of("data", r));
    }

    @PostMapping("/{id}/submit")
    public ResponseEntity<Map<String, Object>> submit(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        Map<String, Object> result = service.submit(id, actor(session));
        WeeklyReport r = (WeeklyReport) result.get("data");
        auditService.recordAction("WEEKLY_REPORT_SUBMIT", session, request,
                "WEEKLY_REPORT", id.toString(),
                "{\"team_id\":" + r.getTeamId() + ",\"po_mail\":\"" + result.get("po_mail") + "\"}");
        return ok(result);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        Map<String, Object> result = service.approve(id, actor(session));
        WeeklyReport r = (WeeklyReport) result.get("data");
        auditService.recordAction("WEEKLY_REPORT_APPROVE", session, request,
                "WEEKLY_REPORT", id.toString(),
                "{\"team_id\":" + r.getTeamId() + ",\"mail_status\":\"" + result.get("mail_status") + "\"}");
        return ok(result);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable Long id, @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        WeeklyReport r = service.reject(id, body.get("note"), actor(session));
        auditService.recordAction("WEEKLY_REPORT_REJECT", session, request,
                "WEEKLY_REPORT", id.toString(),
                "{\"team_id\":" + r.getTeamId() + "}");
        return ok(Map.of("data", r));
    }

    /** Onaylı raporu yeniden düzenlenebilir hale getirir (APPROVED → DRAFT). */
    @PostMapping("/{id}/reopen")
    public ResponseEntity<Map<String, Object>> reopen(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        WeeklyReport r = service.reopen(id, actor(session));
        auditService.recordAction("WEEKLY_REPORT_REOPEN", session, request,
                "WEEKLY_REPORT", id.toString(),
                "{\"team_id\":" + r.getTeamId() + ",\"week\":\"" + r.getWeekLabel() + "\"}");
        return ok(Map.of("data", r));
    }

    /** Onaylı raporu (yeniden onaysız) müdüre tekrar gönderir. */
    @PostMapping("/{id}/resend")
    public ResponseEntity<Map<String, Object>> resend(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        Map<String, Object> result = service.resend(id, actor(session));
        auditService.recordAction("WEEKLY_REPORT_RESEND", session, request,
                "WEEKLY_REPORT", id.toString(),
                "{\"mail_status\":\"" + result.get("mail_status") + "\"}");
        return ok(result);
    }

    /** Toplu / tekil takım transferi — seçilen raporları başka takıma taşır (yalnız ADMIN).
     *  Body: {"ids":[..], "target_team_id":N}. Yetki kontrolü serviste (actor.isAdmin). */
    @PostMapping("/transfer")
    public ResponseEntity<Map<String, Object>> transfer(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        Long targetTeamId = toLong(body.get("target_team_id"));
        List<Long> ids = toLongList(body.get("ids"));
        Map<String, Object> result = service.transfer(ids, targetTeamId, actor(session));
        auditService.recordAction("WEEKLY_REPORT_TRANSFER", session, request,
                "WEEKLY_REPORT", String.valueOf(ids),
                "{\"target_team_id\":" + targetTeamId + ",\"transferred\":" + result.get("transferred") + "}");
        return ok(result);
    }

    /** Cuma hatırlatma maillerini cron beklemeden ANINDA gönderir — yalnız ADMIN.
     *  Test/operasyon kolaylığı; mantık scheduled cron ile aynı (sendFridayReminders). */
    @PostMapping("/reminders/trigger")
    public ResponseEntity<Map<String, Object>> triggerReminders(
            HttpSession session, HttpServletRequest request) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException("Admin access required");
        }
        WeeklyReportReminderService.ReminderResult r = reminderService.sendFridayReminders();
        auditService.recordAction("WEEKLY_REPORT_REMINDER_TRIGGER", session, request,
                "WEEKLY_REPORT", "-",
                "{\"candidates\":" + r.candidates() + ",\"sent\":" + r.sent() + "}");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("candidates", r.candidates());
        data.put("sent", r.sent());
        data.put("skipped_no_email", r.skippedNoEmail());
        data.put("skipped_done", r.skippedDone());
        return ok(Map.of("data", data));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(
            @PathVariable Long id, HttpSession session, HttpServletRequest request) {
        WeeklyReport r = service.delete(id, actor(session));
        auditService.recordAction("WEEKLY_REPORT_DELETE", session, request,
                "WEEKLY_REPORT", id.toString(),
                "{\"team_id\":" + r.getTeamId() + ",\"week\":\"" + r.getWeekLabel() + "\"}");
        return ok(Map.of("message", "Deleted"));
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable Long id, HttpSession session) {
        return ok(Map.of("html", service.buildPreviewHtml(id, actor(session))));
    }

    // ── Görseller ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/images")
    public ResponseEntity<Map<String, Object>> uploadImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption,
            HttpSession session, HttpServletRequest request) {
        WeeklyReportImage img = service.storeImage(id, caption, file, actor(session));
        auditService.recordAction("WEEKLY_REPORT_IMAGE_ADD", session, request,
                "WEEKLY_REPORT", id.toString(),
                "{\"image_id\":" + img.getId() + ",\"size\":" + img.getSizeBytes() + "}");
        return ok(Map.of("data", imageMeta(img)));
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> serveImage(@PathVariable Long imageId, HttpSession session) {
        WeeklyReportImage img = service.getImage(imageId, actor(session));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.getContentType()))
                .header("Cache-Control", "private, max-age=3600")
                .body(img.getData());
    }

    @DeleteMapping("/images/{imageId}")
    public ResponseEntity<Map<String, Object>> deleteImage(
            @PathVariable Long imageId, HttpSession session, HttpServletRequest request) {
        service.deleteImage(imageId, actor(session));
        auditService.recordAction("WEEKLY_REPORT_IMAGE_DELETE", session, request,
                "WEEKLY_REPORT", imageId.toString(), "{}");
        return ok(Map.of("message", "Deleted"));
    }

    // ── E-posta ile hızlı onay (login'siz, public — AuthInterceptor PUBLIC) ──

    /** PO maildeki linke tıklayınca: rapor özeti + "Onayla" butonu (GET mutasyon yapmaz). */
    @GetMapping(value = "/approve-link", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> approveLinkPage(@RequestParam(required = false) String token) {
        Map<String, Object> st = service.approvalTokenStatus(token);
        String team = esc(String.valueOf(st.getOrDefault("team_name", "—")));
        String week = esc(String.valueOf(st.getOrDefault("week_label", "—")));
        if (!Boolean.TRUE.equals(st.get("valid"))) {
            String reason = String.valueOf(st.get("reason"));
            String msg = switch (reason) {
                case "already_approved" -> "Bu rapor zaten onaylanmış. Ek bir işlem gerekmiyor.";
                case "expired"          -> "Onay bağlantısının süresi dolmuş. Ekipten raporu tekrar onaya göndermesini isteyebilirsiniz.";
                case "not_pending"      -> "Rapor şu an onay bekleme durumunda değil.";
                default                 -> "Onay bağlantısı geçersiz veya daha önce kullanılmış.";
            };
            return htmlPage("Haftalık Rapor Onayı",
                "<div class='wk'>" + team + " · " + week + "</div>"
                + "<p class='msg'>" + esc(msg) + "</p>", "#64748b");
        }
        String body =
            "<div class='wk'>" + team + " · " + week + "</div>"
            + "<p class='msg'>Bu haftalık raporu onaylamak üzeresiniz. Onayladığınızda rapor müdüre otomatik iletilecek.</p>"
            + "<form method='post' action='/api/weekly-reports/approve-link/confirm'>"
            + "<input type='hidden' name='token' value='" + esc(token) + "'>"
            + "<button type='submit' class='btn'>✅ Raporu Onayla</button>"
            + "</form>";
        return htmlPage("Haftalık Rapor Onayı", body, "#15803d");
    }

    /** Onayla butonu POST eder → token ile onay. (GET değil; e-posta ön-yüklemesi onaylamaz.) */
    @PostMapping(value = "/approve-link/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> approveLinkConfirm(@RequestParam(required = false) String token) {
        try {
            Map<String, Object> res = service.approveViaToken(token);
            String mail = String.valueOf(res.get("mail_status"));
            String note = (mail != null && (mail.startsWith("SENT") || mail.equals("QUEUED_RETRY")))
                ? "Rapor müdüre e-posta ile iletildi."
                : "Rapor onaylandı; müdür e-postası gönderilemedi/atlandı (" + esc(mail) + "). Uygulamadan tekrar gönderebilirsiniz.";
            return htmlPage("Onaylandı",
                "<p class='msg'><strong>Rapor onaylandı.</strong><br>" + note + "</p>", "#15803d");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Onay işlemi başarısız.";
            return htmlPage("Onay Başarısız", "<p class='msg'>" + esc(msg) + "</p>", "#dc2626");
        }
    }

    private ResponseEntity<String> htmlPage(String title, String bodyHtml, String accent) {
        String html = "<!DOCTYPE html><html lang='tr'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>" + esc(title) + " — CertMonitor</title>"
            + "<style>body{margin:0;background:#f3f4f6;font-family:'Segoe UI',Arial,sans-serif;color:#1e293b}"
            + ".card{max-width:460px;margin:48px auto;background:#fff;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 6px 28px rgba(0,0,0,.12)}"
            + ".hd{background:" + accent + ";color:#fff;padding:20px 26px}"
            + ".hd .b{font-size:11px;font-weight:700;letter-spacing:.1em;opacity:.85}"
            + ".hd .t{font-size:20px;font-weight:800;margin-top:4px}"
            + ".bd{padding:26px}.wk{font-weight:700;font-size:15px;margin-bottom:6px}"
            + ".msg{font-size:14px;line-height:1.7;color:#334155}"
            + ".btn{display:inline-block;margin-top:18px;background:" + accent + ";color:#fff;border:none;"
            + "border-radius:8px;padding:13px 28px;font-size:15px;font-weight:800;cursor:pointer}"
            + "</style></head><body><div class='card'>"
            + "<div class='hd'><div class='b'>CertMonitor — Haftalık Rapor</div><div class='t'>" + esc(title) + "</div></div>"
            + "<div class='bd'>" + bodyHtml + "</div></div></body></html>";
        return ResponseEntity.ok().contentType(MediaType.valueOf("text/html;charset=UTF-8")).body(html);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Actor actor(HttpSession session) {
        Object userId = session.getAttribute("userId");
        Object teamId = session.getAttribute("teamId");
        return new Actor(
                userId instanceof Long l ? l : (userId != null ? Long.valueOf(userId.toString()) : null),
                (String) session.getAttribute("username"),
                (String) session.getAttribute("displayName"),
                teamId instanceof Long l ? l : (teamId != null ? Long.valueOf(teamId.toString()) : null),
                (String) session.getAttribute("systemRole"));
    }

    private Map<String, Object> summary(WeeklyReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("team_id", r.getTeamId());
        m.put("report_year", r.getReportYear());
        m.put("week_no", r.getWeekNo());
        m.put("week_label", r.getWeekLabel());
        m.put("status", r.getStatus());
        m.put("created_by", r.getCreatedBy());
        m.put("created_at", r.getCreatedAt());
        m.put("submitted_at", r.getSubmittedAt());
        m.put("approved_by", r.getApprovedBy());
        m.put("approved_at", r.getApprovedAt());
        m.put("sent_at", r.getSentAt());
        m.put("updated_by", r.getUpdatedBy());
        m.put("updated_at", r.getUpdatedAt());
        m.put("editing_by", service.lockFresh(r) ? r.getEditingBy() : null); // listede "düzenliyor" ipucu
        return m;
    }

    private Map<String, Object> imageMeta(WeeklyReportImage img) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", img.getId());
        m.put("team_id", img.getTeamId());
        m.put("caption", img.getCaption());
        m.put("content_type", img.getContentType());
        m.put("size_bytes", img.getSizeBytes());
        return m;
    }

    /** Meta projeksiyonundan (byte[] data taşımaz) liste eşlemesi. */
    private Map<String, Object> imageMeta(com.certmonitor.repository.WeeklyReportImageMetaView img) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", img.getId());
        m.put("team_id", img.getTeamId());
        m.put("caption", img.getCaption());
        m.put("content_type", img.getContentType());
        m.put("size_bytes", img.getSizeBytes());
        return m;
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return null; }
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    private List<Long> toLongList(Object v) {
        if (!(v instanceof List<?> list)) return List.of();
        List<Long> out = new java.util.ArrayList<>();
        for (Object o : list) { Long l = toLong(o); if (l != null) out.add(l); }
        return out;
    }
}
