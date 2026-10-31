package com.sitemonitor.controller;

import com.sitemonitor.model.DbQueryTemplate;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.dbquery.DbConnectionProfileService;
import com.sitemonitor.service.dbquery.DbQueryTemplateService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Takım sorgu şablonları (2026-10-31, veritabanı sorgu izlemesi — dbquery-api.md §5). Kapsam bağlantılarla aynı: okuma =
 * {@code monitoring.read/view} + kendi takım kapsamı (paylaşım ve 7/24 açmaz), yazma = {@code monitoring.crud/edit} + takımı
 * kendi kapsamıyla işletebilmek. Sorgu kayıtta güvenlik denetiminden geçer.
 */
@RestController
@RequestMapping("/api/monitoring/dbquery/templates")
@RequiredArgsConstructor
public class DbQueryTemplateController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final DbQueryTemplateService templates;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final CertificateService certificateService;

    @ExceptionHandler(DbQueryApiException.class)
    public ResponseEntity<Map<String, Object>> onApiError(DbQueryApiException e) {
        return e.toResponse();
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(name = "team_id", required = false) Long teamId,
                                                    HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        if (teamId != null && !DbConnectionProfileService.canRead(session, teamId)) {
            throw new SecurityException(Msg.t("Bu takımın şablonlarını görme yetkiniz yok.", "You can't see this team's templates."));
        }
        Map<Long, String> teams = certificateService.teamNamesById();
        List<Map<String, Object>> out = templates.visible(session, teamId).stream()
                .map(t -> DbQueryTemplateService.toApi(t, teams.get(t.getTeamId()),
                        DbConnectionProfileService.canWrite(session, t.getTeamId())))
                .toList();
        return ok(out);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        DbQueryTemplate t = templates.create(nonNull(body), session, actorName(session));
        auditService.recordAction("DBQUERY_TEMPLATE_CREATE", session, "DBQUERY_TEMPLATE", String.valueOf(t.getId()),
                AuditDetail.of("name", t.getName(), "team_id", t.getTeamId(), "db_type", t.getDbType(),
                        "sql_len", t.getSqlText() == null ? 0 : t.getSqlText().length()), null);
        return ok(DbQueryTemplateService.toApi(t, certificateService.teamNamesById().get(t.getTeamId()), true));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                      HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        DbQueryTemplate t = templates.update(id, nonNull(body), session, actorName(session));
        auditService.recordAction("DBQUERY_TEMPLATE_UPDATE", session, "DBQUERY_TEMPLATE", String.valueOf(id),
                AuditDetail.of("name", t.getName(), "team_id", t.getTeamId(), "db_type", t.getDbType(),
                        "sql_len", t.getSqlText() == null ? 0 : t.getSqlText().length()), null);
        return ok(DbQueryTemplateService.toApi(t, certificateService.teamNamesById().get(t.getTeamId()), true));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, HttpSession session) {
        permissionService.require(session, "monitoring.crud", "edit");
        DbQueryTemplate t = templates.delete(id, session);
        auditService.recordAction("DBQUERY_TEMPLATE_DELETE", session, "DBQUERY_TEMPLATE", String.valueOf(id),
                AuditDetail.of("name", t.getName(), "team_id", t.getTeamId()), null);
        return ok(Map.of("deleted", true));
    }

    private static Map<String, Object> nonNull(Map<String, Object> body) {
        return body == null ? new LinkedHashMap<>() : new LinkedHashMap<>(body);
    }

    private static String actorName(HttpSession session) {
        Object d = session != null ? session.getAttribute("displayName") : null;
        if (d != null && !d.toString().isBlank()) return d.toString();
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? u.toString() : "system";
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data, "timestamp", ISO.format(Instant.now())));
    }
}
