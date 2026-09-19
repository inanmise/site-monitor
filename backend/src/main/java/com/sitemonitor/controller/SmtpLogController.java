package com.sitemonitor.controller;

import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.SmtpLogQueryService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SMTP Gönderim Logu (2026-09-19) — Sistem Sağlığı → SMTP kartının tam sayfa alt görünümü.
 * Okuma {@code system_health.read} (sekmeyi gören herkes; takım kapsamı satır bazında), yeniden
 * gönderim yalnız global admin + {@code system_health.actions} (denetim kaydı SMTP_RESEND).
 */
@RestController
@RequestMapping("/api/admin/smtp-log")
@RequiredArgsConstructor
public class SmtpLogController {

    /** CSV dışa aktarma tavanı — süzgeçle eşleşen tüm satırlar ama sınırsız değil. */
    static final int EXPORT_CAP = 20_000;

    private final SmtpLogQueryService smtpLogQueryService;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final CertificateInventoryRepository inventoryRepo;

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam Map<String, String> params,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "25") int size,
                                                      HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        Map<String, Object> data = smtpLogQueryService.search(filter(params), scope(session), page, size, Instant.now());
        // LinkedHashMap: `to` açık uçlu pencerede NULL (Map.of null kabul etmez → NPE, 2026-09-19 tarayıcıda yakalandı)
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", data.get("items")); body.put("total", data.get("total")); body.put("page", data.get("page")); body.put("size", data.get("size"));
        body.put("from", data.get("from")); body.put("to", data.get("to"));
        return ok(body);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam Map<String, String> params, HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        return ok(Map.of("data", smtpLogQueryService.summary(filter(params), scope(session), Instant.now())));
    }

    /** CSV için satır listesi (istemci dosyayı üretir; formül nötrleme csv.js'te). */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(@RequestParam Map<String, String> params, HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        List<Map<String, Object>> rows = smtpLogQueryService.exportRows(filter(params), scope(session), EXPORT_CAP, Instant.now());
        return ok(Map.of("data", rows, "count", rows.size(), "capped", rows.size() >= EXPORT_CAP));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id, HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        Map<String, Object> d = smtpLogQueryService.detail(id, scope(session));
        if (d == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "error", "Kayıt bulunamadı"));
        return ok(Map.of("data", d));
    }

    @PostMapping("/{id}/resend")
    public ResponseEntity<Map<String, Object>> resend(@PathVariable long id, HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) throw new SecurityException("Admin access required");
        permissionService.require(session, "system_health.actions", "execute");
        String actor = String.valueOf(session.getAttribute("username"));
        SmtpLogQueryService.ResendResult r = smtpLogQueryService.resend(id, scope(session), actor);
        auditService.recordAction("SMTP_RESEND", session, "NOTIFICATION_LOG", String.valueOf(id),
                r.ok() ? "yeniden gönderildi → #" + r.newLogId() : "yeniden gönderilemedi: " + (r.reason() != null ? r.reason() : r.status()), null);
        if (r.reason() != null) {
            HttpStatus st = "NOT_FOUND".equals(r.reason()) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            return ResponseEntity.status(st).body(Map.of("success", false, "error", r.reason()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true); body.put("sent", r.ok()); body.put("status", r.status()); body.put("new_log_id", r.newLogId());
        return ResponseEntity.ok(body);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    private static SmtpLogQueryService.Filter filter(Map<String, String> p) {
        Long tid = null;
        try { String t = p.get("teamId"); if (t != null && !t.isBlank()) tid = Long.parseLong(t.trim()); } catch (NumberFormatException ignored) { }
        return new SmtpLogQueryService.Filter(p.get("from"), p.get("to"), p.get("status"), p.get("trigger"), tid,
                p.get("domain"), p.get("recipient"), p.get("errorClass"), p.get("q"), p.get("sort"));
    }

    /** Global görücü her şeyi; kapsamlı kullanıcı görüş takımları + o takımların envanter alanları (CertificateController ile aynı kural). */
    private SmtpLogQueryService.Scope scope(HttpSession session) {
        if (SessionScope.isGlobalViewer(session)) return SmtpLogQueryService.Scope.all();
        List<Long> teams = SessionScope.viewTeamIds(session);
        if (teams == null || teams.isEmpty()) return new SmtpLogQueryService.Scope(false, Set.of(), Set.of());
        Set<String> domains = new HashSet<>();
        for (String d : inventoryRepo.findDomainsForTeams(teams)) if (d != null) domains.add(d.toLowerCase(java.util.Locale.ROOT));
        return new SmtpLogQueryService.Scope(false, new HashSet<>(teams), domains);
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }
}
