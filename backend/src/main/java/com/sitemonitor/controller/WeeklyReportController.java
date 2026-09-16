package com.sitemonitor.controller;

import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.model.WeeklyReportImage;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.MonitoringWeeklyStatsService;
import com.sitemonitor.service.WeeklyReportKpiService;
import com.sitemonitor.service.WeeklyReportReminderService;
import com.sitemonitor.service.WeeklyReportService;
import com.sitemonitor.service.WeeklyReportService.Actor;
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
    // Matris izinleri weekly_reports.read / .crud katalogda tanımlıydı ama HİÇBİR uç sormuyordu:
    // Yetki Matrisi'nde kapatmak etkisizdi (yalnız .approve serviste okunuyordu). Rol kapısı burada,
    // takım kapsamı (Actor) serviste — diğer controller'larla aynı iki katman.
    private final com.sitemonitor.service.PermissionService permissionService;
    private final AuditService auditService;
    private final WeeklyReportKpiService kpiService;
    private final MonitoringWeeklyStatsService monitoringStatsService;
    private final com.sitemonitor.service.AppSettingsService appSettings;   // son giriş zamanı (2026-09-12)
    private final com.sitemonitor.repository.IncidentRecordRepository incidentRepo;   // öneri: açık olay sayısı (2026-09-13)
    private final com.sitemonitor.repository.TeamRepository teamRepo;                  // takım kanal şablonu (2026-09-13)
    @org.springframework.beans.factory.annotation.Value("${site.monitor.weekly-report.reminder-enabled:true}")
    private boolean reminderEnabled;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Integer year,
            HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        List<WeeklyReport> reports = service.list(teamId, year, actor(session));
        // Liste görünümünde content_json taşınmaz (boyut) — özet alanlar yeter
        Map<Long, String> mailStatuses = service.lastMailStatuses(
                reports.stream().map(WeeklyReport::getId).toList());
        Map<Long, Long> commentCounts = service.commentCounts(reports.stream().map(WeeklyReport::getId).toList());
        List<Map<String, Object>> summaries = reports.stream().map(r -> {
            Map<String, Object> m = summary(r);
            m.put("last_mail_status", mailStatuses.get(r.getId()));
            m.put("comment_count", commentCounts.getOrDefault(r.getId(), 0L));   // yorum rozeti (2026-09-13)
            return m;
        }).toList();
        return ok(Map.of("data", summaries));
    }

    /** Raporun mail gönderim geçmişi — Geçmiş modal'ı. */
    @GetMapping("/{id}/mails")
    public ResponseEntity<Map<String, Object>> mails(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
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

    /** Son giriş zamanı (2026-09-12): sayfa başlığındaki "⏰ Son giriş" canlı ayardan. Okuma yetkisi yeter. */
    @GetMapping("/deadline")
    public ResponseEntity<Map<String, Object>> deadline(HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        com.sitemonitor.service.WeeklyReportDeadline d = com.sitemonitor.service.WeeklyReportDeadline.resolve(appSettings);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("day", d.dayCode());
        m.put("time", d.timeText());
        m.put("day_tr", d.dayNameTr());
        m.put("day_en", d.dayNameEn());
        m.put("valid", d.valid());
        return ok(Map.of("data", m));
    }

    /** Yorum dizisi (2026-09-13): raporu görebilen herkes okur. */
    @GetMapping("/{id}/comments")
    public ResponseEntity<Map<String, Object>> comments(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        return ok(Map.of("data", service.comments(id, actor(session))));
    }

    /** Yorum ekle (COMMENT): AUDIT hariç, raporu görebilen herkes; denetim kaydı düşer. */
    @PostMapping("/{id}/comments")
    public ResponseEntity<Map<String, Object>> addComment(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                          HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "weekly_reports.read", "view");
        var c = service.addComment(id, body.get("text") == null ? null : body.get("text").toString(), actor(session));
        auditService.recordAction("WEEKLY_REPORT_COMMENT", session, request, "WEEKLY_REPORT", id.toString(),
                "{\"comment_id\":" + c.getId() + ",\"chars\":" + (c.getText() == null ? 0 : c.getText().length()) + "}");
        return ok(Map.of("data", c));
    }

    /** Hatırlatma görünürlüğü (2026-09-13): sonraki koşu, alıcı özeti (admin/AUDIT). */
    @GetMapping("/reminders/status")
    public ResponseEntity<Map<String, Object>> reminderStatus(HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        Actor a = actor(session);
        if (!a.isAdmin() && !a.isAudit()) throw new SecurityException("Yalnız yönetici");
        return ok(Map.of("data", service.reminderStatus(reminderEnabled)));
    }

    /** "Bu hafta" şeridi (2026-09-13): kapsamdaki takımların bu haftaki rapor durumu + son giriş anı. */
    @GetMapping("/this-week")
    public ResponseEntity<Map<String, Object>> thisWeek(HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        return ok(Map.of("data", service.thisWeek(actor(session))));
    }

    /**
     * Sistemden öneriler (2026-09-13): madde 1–2 sayıları için canlı değerler — açık olay (RESOLVED dışı,
     * takım kapsamı), haftada açılan alarm, kritik sertifika, penceredeki dolacaklar. YALNIZ-OKUMA; elle
     * girilen değerin üzerine yazmaz (istemci "Uygula" ile alır).
     */
    @GetMapping("/{id}/suggestions")
    public ResponseEntity<Map<String, Object>> suggestions(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        WeeklyReport r = service.get(id, actor(session));
        Map<String, Object> m = new LinkedHashMap<>();
        long open = 0;
        for (Object[] row : incidentRepo.countByStatus(null, null, true, List.of(r.getTeamId()))) {
            String st = row[0] == null ? "" : row[0].toString();
            if (!"RESOLVED".equalsIgnoreCase(st) && !"CLOSED".equalsIgnoreCase(st)) open += ((Number) row[1]).longValue();
        }
        m.put("open_incidents", open);
        try {
            var k = kpiService.compute(r.getTeamId(), r.getReportYear(), r.getWeekNo());
            if (k != null && k.current() != null) {
                m.put("alarms_opened", k.current().alarmsOpened());
                m.put("critical_certs", k.current().criticalCerts());
                m.put("expiring_in_window", k.current().expiringInWindow());
                m.put("uptime_pct", k.current().uptimePct());
            }
        } catch (Exception e) {
            m.put("kpi_error", true);
        }
        return ok(Map.of("data", m));
    }

    /** Önceki hafta raporu (Δ rozetleri + "geçen haftanın notu"): sayılar + notlar; yoksa data=null. */
    @GetMapping("/{id}/previous")
    public ResponseEntity<Map<String, Object>> previous(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        WeeklyReport r = service.get(id, actor(session));
        WeeklyReport p = service.previous(r);
        Map<String, Object> body = new LinkedHashMap<>();
        if (p == null) { body.put("data", null); return ok(body); }
        Map<String, Object> m = summary(p);
        m.put("content_json", p.getContentJson());
        body.put("data", m);
        return ok(body);
    }

    /** Takım tamamlama panosu (2026-09-12, #21): takım × hafta durum matrisi (global admin / AUDIT). */
    @GetMapping("/completion")
    public ResponseEntity<Map<String, Object>> completion(@RequestParam(required = false) Integer year, HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        return ok(Map.of("data", service.completion(year, actor(session))));
    }

    @GetMapping("/years")
    public ResponseEntity<Map<String, Object>> years(
            @RequestParam(required = false) Long teamId, HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        return ok(Map.of("data", service.years(teamId, actor(session))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        Actor a = actor(session);
        WeeklyReport r = service.get(id, a);
        List<Map<String, Object>> images = service.imagesMeta(r.getId()).stream()
                .map(this::imageMeta).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("report", r);
        data.put("images", images);
        data.put("manager_contact_missing", service.managerContactMissing(r.getTeamId()));
        // Takım kanal şablonu (2026-09-13): editördeki "Kanalları şablondan tamamla" düğmesi
        data.put("team_channels", teamRepo.findById(r.getTeamId())
                .map(tm -> com.sitemonitor.service.WeeklyReportService.channelTemplate(tm.getWeeklyChannels())).orElse(List.of()));
        // Bayatlık SUNUCUDA hesaplanır — istemci saatine güvenilmez
        data.put("lock_holder", service.lockHeldByOther(r, a)
                ? Map.of("name", r.getEditingBy(), "heartbeat_at", r.getEditingHeartbeat())
                : null);
        return ok(Map.of("data", data));
    }

    /** Executive KPI şeridi — canlı cert/alarm/uptime (bu hafta + önceki hafta + son 8 hafta trendi).
     *  YALNIZ-OKUMA; durum makinesine/token akışına dokunmaz. Ağır toplama olduğundan {@code get}'e katılmaz,
     *  rapor görünümü tarafından ayrıca lazy çekilir. */
    @GetMapping("/{id}/kpis")
    public ResponseEntity<Map<String, Object>> kpis(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        WeeklyReport r = service.get(id, actor(session));   // yetki + yükleme (get ile aynı)
        return ok(Map.of("data", kpiService.compute(r.getTeamId(), r.getReportYear(), r.getWeekNo())));
    }

    /** İzleme türü bazında haftalık göstergeler — AYRI (lazy) endpoint: ağır 7-tür toplama yalnız akordeon
     *  açılınca çalışsın; eager report-open yolunu (tek pod) hafif tutar. YALNIZ-OKUMA, durum makinesine dokunmaz. */
    @GetMapping("/{id}/monitoring-stats")
    public ResponseEntity<Map<String, Object>> monitoringStats(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        WeeklyReport r = service.get(id, actor(session));
        return ok(Map.of("data", monitoringStatsService.compute(r.getTeamId(), r.getReportYear(), r.getWeekNo())));
    }

    // ── Düzenleme kilidi ──────────────────────────────────────────────────────

    @PostMapping("/{id}/lock")
    public ResponseEntity<Map<String, Object>> lock(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force,
            HttpSession session) {
        permissionService.require(session, "weekly_reports.crud", "edit");
        return ok(Map.of("data", service.acquireLock(id, force, actor(session))));
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<Map<String, Object>> unlock(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "weekly_reports.crud", "edit");
        service.releaseLock(id, actor(session));
        return ok(Map.of("message", "Released"));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "weekly_reports.crud", "edit");
        Actor a = actor(session);
        Long teamId = toLong(body.get("team_id"));
        Integer year = toInt(body.get("year"));
        Integer weekNo = toInt(body.get("week_no"));
        if (year == null || weekNo == null) {
            throw new IllegalArgumentException("year ve week_no zorunludur");
        }
        boolean carry = Boolean.TRUE.equals(body.get("carry_notes"));   // geçen haftadan devam (2026-09-13)
        WeeklyReport r = service.create(teamId, year, weekNo, a, carry);
        auditService.recordAction("WEEKLY_REPORT_CREATE", session, request,
                "WEEKLY_REPORT", r.getId().toString(),
                "{\"team_id\":" + r.getTeamId() + ",\"week\":\"" + r.getWeekLabel() + "\"}");
        return ok(Map.of("data", r));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> save(
            @PathVariable Long id, @RequestBody Map<String, Object> body,
            HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "weekly_reports.crud", "edit");
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
        permissionService.require(session, "weekly_reports.crud", "edit");
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
        permissionService.require(session, "weekly_reports.crud", "edit");
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
        permissionService.require(session, "weekly_reports.crud", "edit");
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

    // ── Modül görünürlüğü (2026-09-16): Ayarlar → Haftalık Raporlar ───────────────────────────

    /** Takım × modül durumu (yalnız global admin): ad, aktiflik, açık mı, mevcut rapor sayısı. */
    @GetMapping("/access/teams")
    public ResponseEntity<Map<String, Object>> accessTeams(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) throw new SecurityException("Admin access required");
        Map<Long, Long> counts = service.reportCountsByTeam();
        List<Map<String, Object>> rows = teamRepo.findAll().stream()
                .sorted(java.util.Comparator.comparing(t -> t.getName() == null ? "" : t.getName().toLowerCase(java.util.Locale.ROOT)))
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("team_id", t.getId());
                    m.put("team_name", t.getName());
                    m.put("active", Boolean.TRUE.equals(t.getActive()));
                    m.put("enabled", Boolean.TRUE.equals(t.getWeeklyReportsEnabled()));
                    m.put("reminder", Boolean.TRUE.equals(t.getWeeklyReminderEnabled()));
                    m.put("report_count", counts.getOrDefault(t.getId(), 0L));
                    return m;
                }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("teams", rows);
        data.put("enabled_count", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("enabled"))).count());
        return ok(Map.of("data", data));
    }

    /** Takımda modülü aç/kapat (yalnız global admin). Kapatmak veriyi SİLMEZ — yalnız görünürlük. */
    @PutMapping("/access/teams/{teamId}")
    public ResponseEntity<Map<String, Object>> setAccess(@PathVariable Long teamId,
                                                        @RequestBody Map<String, Object> body,
                                                        HttpSession session, HttpServletRequest request) {
        if (!SessionScope.isGlobalAdmin(session)) throw new SecurityException("Admin access required");
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("enabled")));
        com.sitemonitor.model.Team team = service.setFeatureEnabled(teamId, enabled);
        auditService.recordAction("WEEKLY_REPORT_ACCESS", session, request, "TEAM", String.valueOf(teamId),
                "{\"team\":\"" + (team.getName() == null ? "" : team.getName().replace("\"", "'")) + "\",\"enabled\":" + enabled + "}");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("team_id", teamId);
        data.put("enabled", enabled);
        return ok(Map.of("data", data, "message", enabled ? "Açıldı" : "Kapatıldı"));
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
        permissionService.require(session, "weekly_reports.crud", "edit");
        WeeklyReport r = service.delete(id, actor(session));
        auditService.recordAction("WEEKLY_REPORT_DELETE", session, request,
                "WEEKLY_REPORT", id.toString(),
                "{\"team_id\":" + r.getTeamId() + ",\"week\":\"" + r.getWeekLabel() + "\"}");
        return ok(Map.of("message", "Deleted"));
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<Map<String, Object>> preview(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        return ok(Map.of("html", service.buildPreviewHtml(id, actor(session))));
    }

    // ── Görseller ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/images")
    public ResponseEntity<Map<String, Object>> uploadImage(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "caption", required = false) String caption,
            HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "weekly_reports.crud", "edit");
        WeeklyReportImage img = service.storeImage(id, caption, file, actor(session));
        auditService.recordAction("WEEKLY_REPORT_IMAGE_ADD", session, request,
                "WEEKLY_REPORT", id.toString(),
                "{\"image_id\":" + img.getId() + ",\"size\":" + img.getSizeBytes() + "}");
        return ok(Map.of("data", imageMeta(img)));
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> serveImage(@PathVariable Long imageId, HttpSession session) {
        permissionService.require(session, "weekly_reports.read", "view");
        WeeklyReportImage img = service.getImage(imageId, actor(session));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.getContentType()))
                .header("Cache-Control", "private, max-age=3600")
                .body(img.getData());
    }

    @DeleteMapping("/images/{imageId}")
    public ResponseEntity<Map<String, Object>> deleteImage(
            @PathVariable Long imageId, HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "weekly_reports.crud", "edit");
        Map<String, Object> deleted = service.deleteImage(imageId, actor(session));
        auditService.recordAction("WEEKLY_REPORT_IMAGE_DELETE", session, request,
                "WEEKLY_REPORT", imageId.toString(), AuditDetail.ofMap(deleted));
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
            + "<p class='msg'>Bu haftalık raporu onaylayabilir ya da düzeltme için ekibe iade edebilirsiniz. "
            + "Onayladığınızda rapor müdüre otomatik iletilir; iade ettiğinizde ekibe (opsiyonel) notunuzla bilgi gider.</p>"
            + "<form method='post' action='/api/weekly-reports/approve-link/confirm'>"
            + "<input type='hidden' name='token' value='" + esc(token) + "'>"
            + "<button type='submit' class='btn'>✅ Raporu Onayla</button>"
            + "</form>"
            + "<div class='sep'>— veya —</div>"
            + "<form method='post' action='/api/weekly-reports/approve-link/reject'>"
            + "<input type='hidden' name='token' value='" + esc(token) + "'>"
            + "<div class='alt'>İade nedeni (opsiyonel)</div>"
            + "<textarea name='reason' rows='3' class='ta' placeholder='Ekibe iletilecek kısa düzeltme notu…'></textarea>"
            + "<button type='submit' class='btn2'>↩ İade Et</button>"
            + "</form>";
        return htmlPage("Haftalık Rapor Onayı", body, "#15803d");
    }

    /** Onayla butonu POST eder → token ile onay. (GET değil; e-posta ön-yüklemesi onaylamaz.) */
    @PostMapping(value = "/approve-link/confirm", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> approveLinkConfirm(@RequestParam(required = false) String token,
                                                     HttpServletRequest request) {
        try {
            Map<String, Object> res = service.approveViaToken(token);
            String mail = String.valueOf(res.get("mail_status"));
            auditTokenDecision("WEEKLY_REPORT_APPROVE", request, res, null);
            String note = (mail != null && (mail.startsWith("SENT") || mail.equals("QUEUED_RETRY")))
                ? "Rapor müdüre e-posta ile iletildi."
                : "Rapor onaylandı; müdür e-postası gönderilemedi/atlandı (" + esc(mail) + "). Uygulamadan tekrar gönderebilirsiniz.";
            return htmlPage("Onaylandı",
                "<p class='msg'><strong>Rapor onaylandı.</strong><br>" + note + "</p>", "#15803d");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Onay işlemi başarısız.";
            // Başarısız token denemesi de iz bırakır: sızmış/süresi dolmuş bir bağlantının
            // kullanılmaya çalışılması, denetimde görülmesi gereken ilk sinyaldir.
            auditService.recordSecurityEvent("WEEKLY_REPORT_APPROVE", request, null,
                    "WEEKLY_REPORT", "email-token", msg);
            return htmlPage("Onay Başarısız", "<p class='msg'>" + esc(msg) + "</p>", "#dc2626");
        }
    }

    /** İade Et butonu POST eder → token ile iade (opsiyonel neden). Rapor DRAFT'a döner, ekibe iade-maili gider. */
    @PostMapping(value = "/approve-link/reject", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> approveLinkReject(@RequestParam(required = false) String token,
                                                    @RequestParam(required = false) String reason,
                                                    HttpServletRequest request) {
        try {
            Map<String, Object> res = service.rejectViaToken(token, reason);
            String mail = String.valueOf(res.get("mail_status"));
            auditTokenDecision("WEEKLY_REPORT_REJECT", request, res, reason);
            String note = (mail != null && (mail.startsWith("SENT") || mail.equals("QUEUED_RETRY")))
                ? "İade bilgisi ve notunuz ekibe e-posta ile iletildi."
                : "Rapor iade edildi; ekip bilgilendirme e-postası gönderilemedi/atlandı (" + esc(mail) + ").";
            return htmlPage("İade Edildi",
                "<p class='msg'><strong>Rapor iade edildi.</strong><br>" + note + "</p>", "#d97706");
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "İade işlemi başarısız.";
            auditService.recordSecurityEvent("WEEKLY_REPORT_REJECT", request, null,
                    "WEEKLY_REPORT", "email-token", msg);
            return htmlPage("İade Başarısız", "<p class='msg'>" + esc(msg) + "</p>", "#dc2626");
        }
    }

    /**
     * E-posta bağlantısıyla verilen onay/iade kararını denetime yazar.
     *
     * <p>Bu iki uç <b>oturumsuzdur</b> ve raporun durumunu değiştirir; uygulama içinden yapılan
     * aynı geçiş yıllardır denetleniyordu ({@code WEEKLY_REPORT_APPROVE}/{@code _REJECT}), token
     * yolundan yapılınca hiçbir iz kalmıyordu. Yeni bir olay TÜRÜ icat edilmez — aynı türler
     * kullanılıp {@code via} alanıyla ayrılır; aksi halde "bu rapor kim tarafından onaylandı"
     * sorusu iki ayrı filtre gerektirirdi.
     *
     * <p>Çözülen kişi adı {@code approver} alanına yazılır, {@code actor} sütununa DEĞİL: belirteç
     * bir posta kutusuna erişimi kanıtlar, o kişinin kimliğini değil.
     */
    private void auditTokenDecision(String eventType, HttpServletRequest request,
                                    Map<String, Object> res, String reason) {
        try {
            Object data = res == null ? null : res.get("data");
            Long reportId = null;
            Long teamId = null;
            String weekLabel = null;
            if (data instanceof com.sitemonitor.model.WeeklyReport r) {
                reportId = r.getId();
                teamId = r.getTeamId();
                weekLabel = r.getWeekLabel();
            }
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("via", "email_token");
            detail.put("week_label", weekLabel);
            detail.put("mail_status", res == null ? null : res.get("mail_status"));
            if (reason != null || "WEEKLY_REPORT_REJECT".equals(eventType)) {
                // İade gerekçesi bugüne kadar HİÇBİR yerde saklanmıyordu — ekibe giden e-postada
                // görünüp kayboluyordu.
                detail.put("reason", reason);
                detail.put("reason_provided", reason != null && !reason.isBlank());
            }
            auditService.recordTokenAction(eventType, request, "email-approval", teamId,
                    "WEEKLY_REPORT", reportId == null ? null : String.valueOf(reportId),
                    AuditDetail.ofMap(detail));
        } catch (Exception e) {
            // Denetim yazımı kullanıcı akışını ASLA bozmaz: onay zaten gerçekleşti, ekran dönmeli.
            log.warn("Token kararı denetime yazılamadı ({}): {}", eventType, e.toString());
        }
    }

    /** Login'siz onay sayfası — executive tasarım (lacivert bant + ENTERPRISE wordmark + durum şeridi + footer).
     *  {@code accent} = duruma göre renk (yeşil/gri/kırmızı): ince şerit + buton. Rota/mantık/token akışı DEĞİŞMEZ. */
    private ResponseEntity<String> htmlPage(String title, String bodyHtml, String accent) {
        String html = "<!DOCTYPE html><html lang='tr'><head><meta charset='UTF-8'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>" + esc(title) + " — Site Monitor</title>"
            + "<style>"
            + "body{margin:0;background:#eef1f4;font-family:'Segoe UI',-apple-system,Arial,sans-serif;color:#1F2937;-webkit-font-smoothing:antialiased}"
            + ".card{max-width:480px;margin:56px auto;background:#fff;border-radius:14px;overflow:hidden;"
            + "box-shadow:0 10px 40px rgba(15,27,45,.14);border:1px solid #E5E8EC}"
            + ".hd{background:#0F1B2D;color:#fff;padding:22px 28px}"
            + ".wm{font-size:16px;font-weight:800;letter-spacing:-.01em}"
            + ".ent{font-size:9px;font-weight:700;letter-spacing:.16em;color:#93a4bd;border:1px solid #33415a;"
            + "border-radius:4px;padding:2px 6px;margin-left:8px;vertical-align:middle}"
            + ".kick{font-size:11px;font-weight:700;letter-spacing:.1em;color:#93a4bd;margin-top:14px;text-transform:uppercase}"
            + ".t{font-size:21px;font-weight:800;margin-top:3px}"
            + ".strip{height:4px;background:" + accent + "}"
            + ".bd{padding:26px 28px}.wk{font-weight:700;font-size:15px;color:#0F1B2D;margin-bottom:8px}"
            + ".msg{font-size:14px;line-height:1.7;color:#475569}"
            + ".btn{display:inline-block;margin-top:20px;background:" + accent + ";color:#fff;border:none;"
            + "border-radius:8px;padding:13px 30px;font-size:15px;font-weight:800;cursor:pointer;box-shadow:0 2px 8px rgba(0,0,0,.12)}"
            + ".sep{margin:22px 0 4px;text-align:center;font-size:12px;font-weight:700;letter-spacing:.06em;color:#94a3b8}"
            + ".alt{margin-top:12px;font-size:12px;font-weight:700;color:#64748b}"
            + ".ta{display:block;width:100%;box-sizing:border-box;margin-top:6px;border:1px solid #E5E8EC;border-radius:8px;"
            + "padding:10px 12px;font-size:14px;font-family:inherit;color:#1F2937;resize:vertical}"
            + ".ta:focus{outline:none;border-color:#d97706}"
            + ".btn2{display:inline-block;margin-top:12px;background:#fff;color:#b45309;border:1px solid #f59e0b;"
            + "border-radius:8px;padding:11px 26px;font-size:14px;font-weight:800;cursor:pointer}"
            + ".ft{padding:14px 28px;border-top:1px solid #E5E8EC;font-size:11px;color:#94a3b8;text-align:center}"
            + "</style></head><body><div class='card'>"
            + "<div class='hd'><span class='wm'>Site Monitor</span><span class='ent'>ENTERPRISE</span>"
            + "<div class='kick'>Haftalık Rapor Onayı</div><div class='t'>" + esc(title) + "</div></div>"
            + "<div class='strip'></div>"
            + "<div class='bd'>" + bodyHtml + "</div>"
            + "<div class='ft'>Site Monitor — Akbank Sertifika &amp; İzleme Platformu</div>"
            + "</div></body></html>";
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
                (String) session.getAttribute("systemRole"),
                SessionScope.isGlobalAdmin(session));   // rol dizesi değil, gerçek kapsam
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
        m.put("score", r.getScore());                     // gönderim anı skoru (2026-09-13)
        m.put("submitted_by", r.getSubmittedBy());
        m.put("reject_note", r.getRejectNote() != null && !r.getRejectNote().isBlank());   // iade notu var mı (rozet)
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
    private Map<String, Object> imageMeta(com.sitemonitor.repository.WeeklyReportImageMetaView img) {
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
