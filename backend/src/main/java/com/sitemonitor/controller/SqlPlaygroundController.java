package com.sitemonitor.controller;

import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.SecretMask;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.SqlPlaygroundService;
import com.sitemonitor.util.SqlSamples;
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
    private final PermissionService permissionService;

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

        // resource_id NULL'du: bu satırlar "kaynak geçmişi" sorgusuna HİÇ düşmüyordu, yani
        // "bu sorgu kaç kez, kim tarafından koşturuldu" sorusu cevaplanamıyordu. Normalize
        // edilmiş sorgunun kararlı parmak izi kimlik olur.
        String fingerprint = sqlFingerprint(sql);
        String detail = AuditDetail.of(
            "ok", result.get("ok"),
            "rows", result.get("rowCount"),
            "ms", result.get("durationMs"),
            "sql_len", sql == null ? 0 : sql.length(),
            // Alıntı, "bir admin sorgu koşturdu" ile "bir admin app_users'a sorgu koşturdu"
            // arasındaki farktır; gövde SecretMask'ten geçer.
            "excerpt", SecretMask.maskUrlQuery(excerpt(sql)));
        auditService.recordAction("SQL_EXECUTE", session, request, "QUERY", fingerprint, detail);

        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("success", true);
        return ResponseEntity.ok(response);
    }

    /** Sorgunun kararlı kimliği: boşluk/satır sonu normalize edilir, SHA-256'nın ilk 16 hanesi. */
    private static String sqlFingerprint(String sql) {
        String norm = (sql == null ? "" : sql).trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(norm.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Denetimde saklanan sorgu alıntısı — tam metin DEĞİL (gövde kişisel veri içerebilir). */
    private static String excerpt(String sql) {
        if (sql == null) return null;
        String one = sql.trim().replaceAll("\\s+", " ");
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
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
