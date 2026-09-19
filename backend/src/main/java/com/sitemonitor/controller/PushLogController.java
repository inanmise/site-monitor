package com.sitemonitor.controller;

import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.PushLogQueryService;
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
 * Webhook Push Gönderim Logu (2026-09-19) — Sistem Sağlığı → Webhook Push kartının tam sayfa alt görünümü
 * ({@code view=push}). Okuma {@code system_health.read} (takım kapsamı satır bazında; kullanıcı kendi satırlarını
 * her zaman görür), yeniden kuyruğa alma yalnız global admin + {@code system_health.actions}
 * (denetim kaydı USER_PUSH_REQUEUE).
 */
@RestController
@RequestMapping("/api/admin/push-log")
@RequiredArgsConstructor
public class PushLogController {

    static final int EXPORT_CAP = 20_000;

    private final PushLogQueryService pushLogQueryService;
    private final PermissionService permissionService;
    private final AuditService auditService;

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam Map<String, String> params,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "25") int size,
                                                      HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        Map<String, Object> data = pushLogQueryService.search(filter(params), scope(session), page, size, Instant.now());
        Map<String, Object> body = new LinkedHashMap<>();   // `to` açık uçlu pencerede NULL → Map.of kullanılmaz
        body.put("data", data.get("items")); body.put("total", data.get("total")); body.put("page", data.get("page")); body.put("size", data.get("size"));
        body.put("from", data.get("from")); body.put("to", data.get("to"));
        return ok(body);
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(@RequestParam Map<String, String> params, HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        return ok(Map.of("data", pushLogQueryService.summary(filter(params), scope(session), Instant.now())));
    }

    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(@RequestParam Map<String, String> params, HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        List<Map<String, Object>> rows = pushLogQueryService.exportRows(filter(params), scope(session), EXPORT_CAP, Instant.now());
        return ok(Map.of("data", rows, "count", rows.size(), "capped", rows.size() >= EXPORT_CAP));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable long id, HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        Map<String, Object> d = pushLogQueryService.detail(id, scope(session));
        if (d == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "error", "Kayıt bulunamadı"));
        return ok(Map.of("data", d));
    }

    @PostMapping("/{id}/requeue")
    public ResponseEntity<Map<String, Object>> requeue(@PathVariable long id, HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) throw new SecurityException("Admin access required");
        permissionService.require(session, "system_health.actions", "execute");
        String actor = String.valueOf(session.getAttribute("username"));
        PushLogQueryService.RequeueResult r = pushLogQueryService.requeue(id, scope(session), actor);
        auditService.recordAction("USER_PUSH_REQUEUE", session, "USER_PUSH", String.valueOf(id),
                r.ok() ? "yeniden kuyruğa alındı" : "alınamadı: " + r.reason(), null);
        if (!r.ok()) {
            HttpStatus st = "NOT_FOUND".equals(r.reason()) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            return ResponseEntity.status(st).body(Map.of("success", false, "error", r.reason()));
        }
        return ResponseEntity.ok(Map.of("success", true, "queued", true));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private static PushLogQueryService.Filter filter(Map<String, String> p) {
        Long tid = null;
        try { String t = p.get("teamId"); if (t != null && !t.isBlank()) tid = Long.parseLong(t.trim()); } catch (NumberFormatException ignored) { }
        return new PushLogQueryService.Filter(p.get("from"), p.get("to"), p.get("status"), p.get("trigger"), tid,
                p.get("username"), p.get("monitorType"), p.get("level"), p.get("errorClass"), p.get("q"), p.get("sort"));
    }

    /** Global görücü her şeyi; kapsamlı kullanıcı görüş takımları + kendi satırları. */
    private static PushLogQueryService.Scope scope(HttpSession session) {
        String me = session == null ? null : String.valueOf(session.getAttribute("username"));
        if (SessionScope.isGlobalViewer(session)) return PushLogQueryService.Scope.all();
        List<Long> teams = SessionScope.viewTeamIds(session);
        Set<Long> ids = teams == null ? Set.of() : new HashSet<>(teams);
        return new PushLogQueryService.Scope(false, ids, me);
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> response = new LinkedHashMap<>(body);
        response.put("success", true);
        response.put("timestamp", Instant.now().toString());
        return ResponseEntity.ok(response);
    }
}
