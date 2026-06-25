package com.certmonitor.controller;

import com.certmonitor.service.AuditService;
import com.certmonitor.service.SqlPlaygroundService;
import com.certmonitor.util.SqlSamples;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/sql")
@RequiredArgsConstructor
public class SqlPlaygroundController {

    private final SqlPlaygroundService service;
    private final AuditService auditService;
    private final com.certmonitor.service.PermissionService permissionService;

    @GetMapping("/tables")
    public ResponseEntity<Map<String, Object>> tables(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "sql_playground.execute", "execute");
        return ok(Map.of("data", service.listTables()));
    }

    @GetMapping("/tables/{name}/columns")
    public ResponseEntity<Map<String, Object>> columns(
            @PathVariable String name, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "sql_playground.execute", "execute");
        return ok(Map.of("data", service.listColumns(name)));
    }

    /** Tablo şema detayları: kolon+tip-sınırı, constraint (data integrity), index, trigger. */
    @GetMapping("/tables/{name}/details")
    public ResponseEntity<Map<String, Object>> tableDetails(
            @PathVariable String name, HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "sql_playground.execute", "execute");
        return ok(Map.of("data", service.tableDetails(name)));
    }

    /** Tablolar arası ilişki (hiyerarşi) grafiği — gerçek FK + *_id çıkarımı. */
    @GetMapping("/relationships")
    public ResponseEntity<Map<String, Object>> relationships(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "sql_playground.execute", "execute");
        return ok(Map.of("data", service.relationships()));
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);
        permissionService.require(session, "sql_playground.execute", "execute");
        String sql = body.get("sql");
        String actor = (String) session.getAttribute("username");
        Map<String, Object> result = service.execute(sql, actor != null ? actor : "anonymous");

        String detail = String.format(
            "{\"ok\":%s,\"rows\":%d,\"ms\":%d,\"sqlLen\":%d}",
            result.get("ok"), result.get("rowCount"),
            result.get("durationMs"), sql == null ? 0 : sql.length());
        auditService.recordAction("SQL_EXECUTE", session, request, "QUERY", null, detail);

        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("success", true);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "sql_playground.execute", "execute");
        String actor = (String) session.getAttribute("username");
        return ok(Map.of("data", service.recentHistory(actor != null ? actor : "anonymous")));
    }

    @GetMapping("/samples")
    public ResponseEntity<Map<String, Object>> samples(HttpSession session) {
        requireAdmin(session);
        permissionService.require(session, "sql_playground.execute", "execute");
        return ok(Map.of("data", SqlSamples.list()));
    }

    private boolean isAdmin(HttpSession s) {
        return SessionScope.isGlobalAdmin(s);
    }

    private void requireAdmin(HttpSession s) {
        if (!isAdmin(s)) {
            throw new SecurityException("Admin access required");
        }
    }

    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>(body);
        r.put("success", true);
        return ResponseEntity.ok(r);
    }
}
