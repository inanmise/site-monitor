package com.sitemonitor.controller;

import com.sitemonitor.model.DeploymentHistory;
import com.sitemonitor.util.Csv;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.DeploymentHistoryService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.Writer;
import java.util.*;

/**
 * Sürüm & Dağıtım geçmişi — yönetim yüzeyi (K1: Sistem Sağlığı "Sürüm & Dağıtım" bölümü).
 *
 * <p>Okuma {@code release_history.read} (ADMIN + AUDIT); yazma {@code release_history.edit} + kapsamlı
 * müdür (AD ADMIN) reddi (K9). Sayfalama/CSV deseni {@link RetentionAdminController#runs} ile aynı:
 * size ≤ 100, sıralama beyaz listesi, CSV BOM + 5000 satır tavanı. Yanıtlar diğer /api uçları gibi no-store.
 */
@RestController
@RequestMapping("/api/admin/deployments")
@RequiredArgsConstructor
public class DeploymentHistoryController {

    private static final Map<String, String> SORTS = Map.of(
            "started_at", "startedAt", "environment", "environment", "version", "version", "source", "source");

    private final DeploymentHistoryService service;
    private final PermissionService permissionService;
    private final AuditService auditService;

    // ── Okuma ────────────────────────────────────────────────────────────────

    /** Sayfalı liste: ortam/kaynak/tarih/metin süzgeci + tür türetimi (UPGRADE/RESTART/…). */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "started_at") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            HttpSession session) {
        requireRead(session);
        int p = Math.max(1, page) - 1;
        int s = Math.max(1, Math.min(size, DeploymentHistoryService.MAX_PAGE));
        Page<DeploymentHistory> pg = service.search(env, normSource(source), since, until, q, PageRequest.of(p, s, sortOf(sort, dir)));
        Map<Long, DeploymentHistoryService.Derived> kinds = service.kindsFor(pg.getContent());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DeploymentHistory d : pg.getContent()) {
            DeploymentHistoryService.Derived k = kinds.get(d.getId());
            rows.add(k != null ? service.toMap(k) : service.toMap(new DeploymentHistoryService.Derived(d, DeploymentHistoryService.Kind.UNKNOWN, null)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", rows);
        body.put("total", pg.getTotalElements());
        body.put("page", pg.getNumber() + 1);
        body.put("size", pg.getSize());
        body.put("total_pages", pg.getTotalPages());
        body.put("environments", service.environments());
        return ResponseEntity.ok(body);
    }

    /** Zaman çizelgesi + özet şeridi (varsayılan: koşan ortam). */
    @GetMapping("/timeline")
    public ResponseEntity<Map<String, Object>> timeline(@RequestParam(required = false) String env, HttpSession session) {
        requireRead(session);
        String e = env == null || env.isBlank() ? service.currentEnvironment() : env;
        Map<String, Object> m = new LinkedHashMap<>(service.timeline(e));
        m.put("summary", service.summary(e));
        m.put("environments", service.environments());
        m.put("backfillCandidates", service.backfillPreview());
        return ResponseEntity.ok(Map.of("success", true, "data", m));
    }

    /** Sürüm × ortam matrisi. */
    @GetMapping("/matrix")
    public ResponseEntity<Map<String, Object>> matrix(@RequestParam(defaultValue = "false") boolean all, HttpSession session) {
        requireRead(session);
        return ResponseEntity.ok(Map.of("success", true, "data", service.matrix(all)));
    }

    /** Ekranla AYNI süzgeçle CSV — en fazla {@value DeploymentHistoryService#CSV_MAX_ROWS} satır. */
    @GetMapping("/export")
    public ResponseEntity<Void> export(
            @RequestParam(required = false) String env,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "started_at") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            HttpSession session, HttpServletRequest request, HttpServletResponse response) throws IOException {
        requireRead(session);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"deployment-history.csv\"");
        Writer w = response.getWriter();
        w.write('﻿');   // Excel UTF-8 BOM
        w.write(Csv.row("id", "started_at", "ready_at", "ended_at", "end_reason", "environment", "version", "kind",
                "previous_version", "image_version", "git_commit", "image_ref", "helm_release", "helm_revision",
                "helm_chart_version", "instance_id", "pod_name", "node_name", "source", "created_by", "note"));
        int rows = 0;
        for (int p = 0; rows < DeploymentHistoryService.CSV_MAX_ROWS; p++) {
            List<DeploymentHistory> chunk = service.search(env, normSource(source), since, until, q,
                    PageRequest.of(p, DeploymentHistoryService.MAX_PAGE, sortOf(sort, dir))).getContent();
            if (chunk.isEmpty()) break;
            Map<Long, DeploymentHistoryService.Derived> kinds = service.kindsFor(chunk);
            for (DeploymentHistory d : chunk) {
                DeploymentHistoryService.Derived k = kinds.get(d.getId());
                w.write(Csv.row(
                        String.valueOf(d.getId()), d.getStartedAt(), d.getReadyAt(), d.getEndedAt(), d.getEndReason(),
                        d.getEnvironment(), d.getVersion(), k == null ? "" : k.kind().name(),
                        k == null ? "" : k.previousVersion(), d.getImageVersion(), d.getGitCommit(), d.getImageRef(),
                        d.getHelmRelease(), d.getHelmRevision() == null ? "" : String.valueOf(d.getHelmRevision()),
                        d.getHelmChartVersion(), d.getInstanceId(), d.getPodName(), d.getNodeName(), d.getSource(),
                        d.getCreatedBy(), d.getNote()));
                rows++;
                if (rows >= DeploymentHistoryService.CSV_MAX_ROWS) break;
            }
            if (chunk.size() < DeploymentHistoryService.MAX_PAGE) break;
        }
        w.flush();
        auditService.recordAction("SYSTEM_DEPLOYMENT_EXPORT", session, request, "SYSTEM", "deployments",
                AuditDetail.of("rows", rows, "env", env, "source", source, "q", q));
        return null;
    }

    // ── Yazma (release_history.edit + kapsamlı müdür reddi) ──────────────────

    /** Elle dağıtım kaydı (K8): env + semver + ISO-UTC tarih + zorunlu not. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createManual(@RequestBody Map<String, Object> body, HttpSession session,
                                                            HttpServletRequest request) {
        requireEdit(session);
        DeploymentHistoryService.ManualRequest req = new DeploymentHistoryService.ManualRequest(
                str(body.get("environment")), str(body.get("version")), str(body.get("started_at")),
                str(body.get("note")), str(body.get("commit")), intOrNull(body.get("helm_revision")));
        DeploymentHistory d = service.createManual(actor(session), req);
        auditService.recordAction("SYSTEM_DEPLOYMENT_MANUAL", session, request, "SYSTEM", String.valueOf(d.getId()),
                AuditDetail.of("environment", d.getEnvironment(), "version", d.getVersion(), "startedAt", d.getStartedAt(), "note", d.getNote()));
        return ResponseEntity.ok(Map.of("success", true, "data",
                service.toMap(new DeploymentHistoryService.Derived(d, DeploymentHistoryService.Kind.UNKNOWN, null))));
    }

    /** Denetimdeki SCHEMA_PATCH satırlarından geri doldurma (idempotent; sürüm NULL). */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpSession session, HttpServletRequest request) {
        requireEdit(session);
        String env = body == null ? null : str(body.get("environment"));
        Map<String, Object> out = service.backfill(actor(session), env);
        auditService.recordAction("SYSTEM_DEPLOYMENT_BACKFILL", session, request, "SYSTEM", "deployments",
                AuditDetail.ofMap(out));
        return ResponseEntity.ok(Map.of("success", true, "data", out));
    }

    /** Yalnız MANUAL satır silinir (K10) — diğerleri 409 (IllegalStateException → GlobalExceptionHandler). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable long id, HttpSession session, HttpServletRequest request) {
        requireEdit(session);
        DeploymentHistory d = service.deleteManual(id);
        auditService.recordAction("SYSTEM_DEPLOYMENT_DELETE", session, request, "SYSTEM", String.valueOf(id),
                AuditDetail.of("environment", d.getEnvironment(), "version", d.getVersion(), "startedAt", d.getStartedAt()));
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    private void requireRead(HttpSession session) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        permissionService.require(session, "release_history.read", "view");
    }

    private void requireEdit(HttpSession session) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        permissionService.require(session, "release_history.edit", "edit");
        SessionScope.requireNotScopedAdmin(session, "release_history.edit");   // kapsamlı müdür (AD ADMIN) yazamaz
    }

    private static String actor(HttpSession session) {
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "anonymous";
    }

    static Sort sortOf(String sort, String dir) {
        String prop = SORTS.getOrDefault(sort == null ? "" : sort.toLowerCase(Locale.ROOT), "startedAt");
        Sort.Direction d = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(d, prop).and(Sort.by(d, "id"));
    }

    /** source: STARTUP|BACKFILL|MANUAL|all (boş/all → süzgeç yok). */
    static String normSource(String s) {
        if (s == null || s.isBlank() || "all".equalsIgnoreCase(s)) return null;
        String u = s.trim().toUpperCase(Locale.ROOT);
        return (u.equals(DeploymentHistory.SOURCE_STARTUP) || u.equals(DeploymentHistory.SOURCE_BACKFILL)
                || u.equals(DeploymentHistory.SOURCE_MANUAL)) ? u : null;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Integer intOrNull(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try { return Integer.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }
}
