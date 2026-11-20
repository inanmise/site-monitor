package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.techerror.TechErrorIncidentImportGate;
import com.sitemonitor.service.techerror.TechErrorIncidentImportService;
import com.sitemonitor.service.techerror.TechErrorIncidentLinks;
import com.sitemonitor.service.techerror.TechErrorIncidentSyncService;
import com.sitemonitor.service.techerror.TechErrorReportService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Olay kaydı → teknik hata raporu anomali konusu: içe alma ve CANLI bağ uçları (2026-11-20). Sözleşme
 * {@code olay-ice-alma-api.md}. Kapılar: raporun İÇERİK yazımı ({@link TechErrorReportController#canWrite} — üye, yönetici,
 * EDIT / DELETE paylaşımı) + olay okuyan her uçta {@code incidents.view/view}; her olay {@link IncidentController#canReadIncident}
 * kuralından geçmeli — bulunamayan ile görülemeyen olay AYNI yanıtı alır. Aday olaylar = kanalın takımının kayıtları.
 */
@RestController
@RequestMapping("/api/tech-error-reports")
public class TechErrorIncidentController {

    static final String READ = "tech_error_reports.read";
    static final String CRUD = "tech_error_reports.crud";
    static final String INCIDENTS = "incidents.view";

    private final TechErrorReportService service;
    private final TechErrorIncidentImportService importer;
    private final TechErrorIncidentSyncService sync;
    private final TechErrorReportController reports;
    private final IncidentRecordRepository incidents;
    private final TeamRepository teams;
    private final PermissionService permissions;
    private final AuditService auditService;

    public TechErrorIncidentController(TechErrorReportService service, TechErrorIncidentImportService importer,
                                       TechErrorIncidentSyncService sync, TechErrorReportController reports,
                                       IncidentRecordRepository incidents, TeamRepository teams, PermissionService permissions,
                                       AuditService auditService) {
        this.service = service;
        this.importer = importer;
        this.sync = sync;
        this.reports = reports;
        this.incidents = incidents;
        this.teams = teams;
        this.permissions = permissions;
        this.auditService = auditService;
    }

    // ── Adaylar / önizleme / içe alma ────────────────────────────────────────────────────────────────

    @GetMapping("/reports/{id}/incident-candidates")
    public ResponseEntity<Map<String, Object>> candidates(@PathVariable Long id,
                                                          @RequestParam(required = false) String q,
                                                          @RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size,
                                                          @RequestParam(required = false) Boolean overlap,
                                                          HttpSession session) {
        requireWrite(session);
        requireIncidents(session);
        TechErrorReport r = writableReport(id, session);
        return ok(importer.candidates(r, q, page, size, Boolean.TRUE.equals(overlap), reports.incidentReader(session)));
    }

    @PostMapping("/reports/{id}/incident-imports/preview")
    public ResponseEntity<Map<String, Object>> previewImport(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                                             HttpSession session) {
        requireWrite(session);
        requireIncidents(session);
        TechErrorReport r = writableReport(id, session);
        TechErrorIncidentImportService.Request req = parseRequest(body);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("proposals", importer.preview(r, req, reports.incidentReader(session)));
        return ok(data);
    }

    /** Görselli içe almaların pod başına yük sınırı (kişi başına 1, pod başına {@link TechErrorIncidentImportGate#PER_POD}). */
    final TechErrorIncidentImportGate imageGate = new TechErrorIncidentImportGate();

    @PostMapping("/reports/{id}/incident-imports")
    public ResponseEntity<Map<String, Object>> importIncidents(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                                               HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        requireIncidents(session);
        writableReport(id, session);
        TechErrorIncidentImportService.Request req = parseRequest(body);
        Long version = parseVersion(body);
        // Görselli içe alma raporun kilidi altında olay görsellerini okur: kişi başına tek, pod başına sınırlı (dolu → 429,
        // beklemeden; reddedilen istek hiçbir şey saymaz). Görselsiz içe alma sınıra girmez.
        TechErrorIncidentImportGate.Slot slot = null;
        if (req.images()) {
            TechErrorIncidentImportGate.Result g = imageGate.tryEnter(gateKey(session));
            if (g.slot() == null) return importBusy(g.refusal());
            slot = g.slot();
        }
        TechErrorIncidentImportService.ImportOutcome out;
        try {
            out = importer.importIncidents(id, version, req, userId(session), displayName(session), reports.incidentReader(session));
        } finally {
            if (slot != null) slot.close();
        }
        auditService.recordAction("TECH_ERROR_REPORT_UPDATE", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                AuditDetail.of("incident_import", req.incidentIds(), "section_id", req.sectionId(),
                        "issues_added", out.issuesAdded(), "images_added", out.imagesAdded()));
        Map<String, Object> data = reports.reportDetail(id, session);
        data.put("imported", out.imported());
        return ok(data);
    }

    // ── Canlı bağ ────────────────────────────────────────────────────────────────────────────────────

    /** Yetişme: dondurulmamış raporun bayat bağları senkronlanır (yazacak bir şey yoksa yazmaz). Rapor ayrıntısı döner. */
    @PostMapping("/reports/{id}/incident-links/sync")
    public ResponseEntity<Map<String, Object>> syncLinks(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        writableReport(id, session);
        int written = sync.catchUp(id);
        if (written > 0) {
            auditService.recordAction("TECH_ERROR_REPORT_UPDATE", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                    AuditDetail.of("incident_sync", written));
        }
        return ok(reports.reportDetail(id, session));
    }

    @PostMapping("/reports/{id}/incident-links/{sectionId}/{issueId}/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@PathVariable Long id, @PathVariable String sectionId, @PathVariable String issueId,
                                                       @RequestBody(required = false) Map<String, Object> body,
                                                       HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        requireIncidents(session);
        writableReport(id, session);
        Map<String, Object> b = body == null ? Map.of() : body;
        Long version = parseVersion(b);
        Object unpin = b.get("unpin");
        TechErrorIncidentSyncService.Outcome o = sync.refresh(id, version, sectionId, issueId, unpin, userId(session),
                reports.incidentReader(session));
        auditService.recordAction("TECH_ERROR_REPORT_UPDATE", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                AuditDetail.of("incident_refresh", sectionId + "/" + issueId, "unpin", unpin, "written", o.written()));
        return ok(reports.reportDetail(id, session));
    }

    @DeleteMapping("/reports/{id}/incident-links/{sectionId}/{issueId}")
    public ResponseEntity<Map<String, Object>> unlink(@PathVariable Long id, @PathVariable String sectionId, @PathVariable String issueId,
                                                      HttpSession session, HttpServletRequest request) {
        requireWrite(session);
        writableReport(id, session);
        Long incidentId = sync.unlink(id, sectionId, issueId, userId(session));
        auditService.recordAction("TECH_ERROR_REPORT_UPDATE", session, request, "TECH_ERROR_REPORT", String.valueOf(id),
                AuditDetail.of("incident_unlink", incidentId, "section_id", sectionId, "issue_id", issueId));
        TechErrorReport r = service.report(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incident_links", sync.linksDto(r, service.now(), reports.incidentReader(session)));
        return ok(data);
    }

    // ── Olayın kullanıldığı raporlar ─────────────────────────────────────────────────────────────────

    @GetMapping("/incident-usage")
    public ResponseEntity<Map<String, Object>> usage(@RequestParam(name = "incident_id", required = false) String incidentId,
                                                     HttpSession session) {
        if (!permissions.allows(session, READ, "view")) throw new SecurityException(READ + " yetkisi gerekli");
        TechErrorReportController.requireModuleVisible(session);
        requireIncidents(session);
        Long iid = parseId(incidentId);
        if (iid == null) {
            throw new FieldValidationException("incident_id", Msg.t("Olay kaydı kimliği okunamadı.",
                    "The incident record id couldn't be read."));
        }
        IncidentRecord inc = incidents.findById(iid).orElse(null);
        if (inc == null || !reports.incidentReader(session).test(inc.getTeamId(), inc.getCreatedByTeamId())) {
            throw new CodedNotFoundException("INCIDENT_NOT_FOUND", Msg.t(
                    "Olay kaydı bulunamadı ya da görme yetkiniz yok. Listeyi yenileyin.",
                    "The incident record wasn't found or you can't see it. Refresh the list."));
        }
        Map<Long, String> names = new HashMap<>();
        List<Map<String, Object>> items = sync.usage(inc, teamId -> TechErrorReportController.canRead(session, teamId),
                teamId -> teamId == null ? null : names.computeIfAbsent(teamId,
                        k -> teams.findById(k).map(Team::getName).orElse(null)));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        return ok(data);
    }

    // ── İstek ayrıştırma ─────────────────────────────────────────────────────────────────────────────

    /** {@code section_id}, {@code incident_ids} (1–10, tekrarlar atılır), {@code parts} (yoksa varsayılan), {@code images}. */
    static TechErrorIncidentImportService.Request parseRequest(Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object sid = b.get("section_id");
        if (!(sid instanceof String s) || s.isBlank() || s.length() > 40) {
            throw new FieldValidationException("section_id", Msg.t("Olayların alınacağı anomali bölümünü seçin.",
                    "Choose the anomaly section to import the incidents into."));
        }
        Object raw = b.get("incident_ids");
        if (!(raw instanceof List<?> list) || list.isEmpty() || list.size() > TechErrorIncidentImportService.MAX_IMPORT * 2) {
            throw badIds();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Object o : list) {
            Long id = o instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) ? Long.valueOf(n.longValue())
                    : o instanceof String str ? parseId(str) : null;
            if (id == null || id <= 0) throw badIds();
            ids.add(id);
        }
        if (ids.size() > TechErrorIncidentImportService.MAX_IMPORT) throw badIds();
        List<String> parts = null;
        Object rp = b.get("parts");
        if (rp != null) {
            if (!(rp instanceof List<?> pl) || pl.size() > 8) throw badParts();
            LinkedHashSet<String> ps = new LinkedHashSet<>();
            for (Object o : pl) {
                if (!(o instanceof String p) || !TechErrorIncidentLinks.PARTS.contains(p)) throw badParts();
                ps.add(p);
            }
            parts = new ArrayList<>(ps);
        }
        Object im = b.get("images");
        if (im != null && !(im instanceof Boolean)) {
            throw new FieldValidationException("images", Msg.t("\"Görselleri de al\" seçeneği okunamadı.",
                    "The \"Import images too\" option couldn't be read."));
        }
        return new TechErrorIncidentImportService.Request(s.strip(), new ArrayList<>(ids), parts, Boolean.TRUE.equals(im));
    }

    private static FieldValidationException badIds() {
        return new FieldValidationException("incident_ids", Msg.t(
                "En az 1, en çok " + TechErrorIncidentImportService.MAX_IMPORT + " olay kaydı seçin.",
                "Choose at least 1 and at most " + TechErrorIncidentImportService.MAX_IMPORT + " incident records."));
    }

    private static FieldValidationException badParts() {
        return new FieldValidationException("parts", Msg.t(
                "Açıklama parçaları okunamadı: rca, impact, resolution, technical değerlerinden seçin.",
                "The description parts couldn't be read: choose from rca, impact, resolution, technical."));
    }

    static Long parseVersion(Map<String, Object> body) {
        Object v = body == null ? null : body.get("version");
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            Long l = parseId(s);
            if (l != null) return l;
        }
        throw new FieldValidationException("version", Msg.t(
                "Raporun okunan sürümü gönderilmedi. Sayfayı yenileyip tekrar deneyin.",
                "The report version you read wasn't sent. Reload the page and try again."));
    }

    // ── Kapılar ──────────────────────────────────────────────────────────────────────────────────────

    private void requireWrite(HttpSession session) {
        if (!permissions.allows(session, CRUD, "edit")) throw new SecurityException(CRUD + " yetkisi gerekli");
        TechErrorReportController.requireModuleVisible(session);
    }

    private void requireIncidents(HttpSession session) {
        if (!permissions.allows(session, INCIDENTS, "view")) {
            throw new SecurityException(Msg.t("Olay kayıtlarını görme yetkiniz yok (incidents.view).",
                    "You don't have permission to view incident records (incidents.view)."));
        }
    }

    private TechErrorReport writableReport(Long id, HttpSession session) {
        TechErrorReport r = service.report(id);
        if (!TechErrorReportController.canRead(session, r.getTeamId())) {
            throw new NoSuchElementException(Msg.t(
                    "Rapor bulunamadı; silinmiş olabilir ya da görme yetkiniz yok. Listeyi yenileyin.",
                    "The report wasn't found; it may have been deleted or you can't see it. Refresh the list."));
        }
        if (!TechErrorReportController.canWrite(session, r.getTeamId())) {
            throw new SecurityException(Msg.t(
                    "Bu raporu yalnız takımın üyeleri ve yöneticileri (ya da düzenleme paylaşımı olanlar) değiştirebilir.",
                    "Only the team's members and managers (or people with an edit share) can change this report."));
        }
        return r;
    }

    /** Görselli içe alma sınırının kişi anahtarı: kullanıcı kimliği, yoksa kullanıcı adı, yoksa oturum. */
    private static String gateKey(HttpSession session) {
        Long uid = userId(session);
        if (uid != null) return "u:" + uid;
        Object u = session.getAttribute("username");
        if (u instanceof String s && !s.isBlank()) return "n:" + s;
        return "s:" + session.getId();
    }

    /** 429 {@code IMPORT_BUSY} + {@code Retry-After} (proje hata sözleşmesi: success / code / error / request_id). */
    static ResponseEntity<Map<String, Object>> importBusy(TechErrorIncidentImportGate.Refusal refusal) {
        long wait = TechErrorIncidentImportGate.RETRY_AFTER_SECONDS;
        String msg = refusal == TechErrorIncidentImportGate.Refusal.USER
                ? Msg.t("Sizin için görselli bir olay içe alması zaten sürüyor; kişi başına aynı anda bir tane yapılır. O bitince (yaklaşık "
                        + wait + " saniye sonra) yeniden deneyin.",
                        "An incident import with images is already running for you; one runs per person at a time. Try again when it has"
                                + " finished (in about " + wait + " seconds).")
                : Msg.t("Şu an sunucuda görselli olay içe almaları dolu. Yaklaşık " + wait
                        + " saniye sonra yeniden deneyin ya da görselleri almadan içe alın.",
                        "The server is busy with other incident imports that include images. Try again in about " + wait
                                + " seconds, or import without images.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "IMPORT_BUSY");
        body.put("error", msg);
        String rid = org.slf4j.MDC.get("requestId");
        if (rid != null) body.put("request_id", rid);
        body.put("retry_after_seconds", wait);
        return ResponseEntity.status(429).header(org.springframework.http.HttpHeaders.RETRY_AFTER, String.valueOf(wait)).body(body);
    }

    private static Long userId(HttpSession session) {
        Object v = session.getAttribute("userId");
        if (v instanceof Number n) return Long.valueOf(n.longValue());
        try { return v == null ? null : Long.valueOf(String.valueOf(v)); } catch (NumberFormatException e) { return null; }
    }

    private static String displayName(HttpSession session) {
        Object d = session.getAttribute("displayName");
        if (d instanceof String s && !s.isBlank()) return s;
        Object u = session.getAttribute("username");
        return u instanceof String s && !s.isBlank() ? s : null;
    }

    private static Long parseId(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty() || t.length() > 18) return null;
        for (int k = 0; k < t.length(); k++) if (t.charAt(k) < '0' || t.charAt(k) > '9') return null;
        return Long.valueOf(t);
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
