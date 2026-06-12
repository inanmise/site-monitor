package com.certmonitor.controller;

import com.certmonitor.model.WeeklyReport;
import com.certmonitor.model.WeeklyReportImage;
import com.certmonitor.service.AuditService;
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
        List<Map<String, Object>> summaries = reports.stream().map(this::summary).toList();
        return ok(Map.of("data", summaries));
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
        m.put("submitted_at", r.getSubmittedAt());
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
}
