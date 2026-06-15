package com.certmonitor.controller;

import com.certmonitor.model.PermissionGrant;
import com.certmonitor.repository.PermissionGrantRepository;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.PermissionCatalog;
import com.certmonitor.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;
    private final PermissionGrantRepository repo;
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getMatrix(HttpSession session) {
        requireAdmin(session);

        // Render catalog as plain list-of-maps so frontend doesn't depend on Java types.
        List<Map<String, Object>> catalog = PermissionCatalog.ALL.stream()
            .map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("resource_key", r.key);
                m.put("group", r.group);
                m.put("actions", r.actions);
                m.put("sensitive", r.sensitive);
                return m;
            })
            .toList();

        return ResponseEntity.ok(Map.of(
            "success", true,
            "catalog", catalog,
            "grants",  repo.findAll()
        ));
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> upsertGrant(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        String role = (String) body.get("role");
        String resourceKey = (String) body.get("resource_key");
        String action = (String) body.get("action");
        // allowed boolean ya da "true"/"false" string olabilir — ClassCastException (500) yerine
        // sağlam ayrıştır; geçersizse 400 (kullanıcıya teknik hata gösterme).
        Object allowedRaw = body.get("allowed");
        Boolean allowed = (allowedRaw instanceof Boolean b) ? b
                : (allowedRaw instanceof String s && ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)))
                        ? Boolean.valueOf(s) : null;
        if (role == null || resourceKey == null || action == null || allowed == null) {
            throw new IllegalArgumentException("role, resource_key, action, allowed required");
        }
        String updatedBy = (String) session.getAttribute("username");
        PermissionGrant g = permissionService.upsertGrant(role, resourceKey, action, allowed, updatedBy);
        auditService.recordAction("PERMISSION_UPDATE", session, request,
            "PERMISSION", role + "/" + resourceKey + "/" + action,
            "{\"allowed\":" + allowed + "}");
        return ResponseEntity.ok(Map.of("success", true, "data", g));
    }

    @PostMapping("/reset-to-defaults")
    public ResponseEntity<Map<String, Object>> resetToDefaults(
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        permissionService.seedDefaults();
        auditService.recordAction("PERMISSION_RESET", session, request,
            "PERMISSION", "ALL", null);
        return ResponseEntity.ok(Map.of("success", true, "message", "Permissions reset to defaults"));
    }

    private void requireAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            log.warn("Unauthorized permission-matrix access by user={}",
                session.getAttribute("username"));
            throw new SecurityException("Admin access required");
        }
    }
}
