package com.sitemonitor.controller;

import com.sitemonitor.model.Platform;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.PlatformService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform kataloğu uçları (2026-09-22). Okuma her oturum açmış kullanıcıya açık (envanter formu seçicisi — "Domain Ekle"
 * her kullanıcı seviyesinde); yazma Ayarlar yetkisi ({@code settings.general} EDIT) ister ve denetim izi yazar.
 */
@RestController
@RequestMapping("/api/admin/platforms")
@RequiredArgsConstructor
public class PlatformController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final PlatformService platforms;
    private final PermissionService permissionService;
    private final AuditService auditService;

    /** {@code ?all=true} → pasifler + kullanım sayısı (Ayarlar); varsayılan yalnız aktifler (form seçicisi). */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean all, HttpSession session) {
        if (session == null || !Boolean.TRUE.equals(session.getAttribute("authenticated")))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(fail("Oturum gerekli"));
        if (all) {
            requireSettings(session);
            return ok(Map.of("data", platforms.listWithUsage()));
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Platform p : platforms.listActive()) out.add(brief(p));
        return ok(Map.of("data", out));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettings(session);
        try {
            Platform p = platforms.create(str(body.get("code")), str(body.get("name")), str(body.get("description")),
                    body.get("sort_order") instanceof Number n ? n.intValue() : null, actor(session));
            auditService.recordAction("PLATFORM_CREATE", session, request, "PLATFORM", p.getCode(),
                    AuditDetail.of("code", p.getCode(), "name", p.getName()));
            return ok(Map.of("data", brief(p)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(fail(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        requireSettings(session);
        try {
            Platform before = platforms.get(id);
            String bName = before.getName(); Boolean bActive = before.getActive();
            Platform p = platforms.update(id, str(body.get("name")), str(body.get("description")),
                    body.get("active") instanceof Boolean b ? b : null,
                    body.get("sort_order") instanceof Number n ? n.intValue() : null);
            auditService.recordAction("PLATFORM_UPDATE", session, request, "PLATFORM", p.getCode(),
                    AuditDetail.of("code", p.getCode(), "name_before", bName, "name", p.getName(),
                            "active_before", bActive, "active", p.getActive()));
            return ok(Map.of("data", brief(p)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(fail(e.getMessage()));
        }
    }

    /** Kullanımdaysa 409 — kullanıcı pasife alsın (envanter kayıtları platformunu korur). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, HttpSession session, HttpServletRequest request) {
        requireSettings(session);
        try {
            Platform p = platforms.get(id);
            if (!platforms.delete(id)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(fail("Bu platform envanterde kullanılıyor — silmek yerine pasife alın"));
            }
            auditService.recordAction("PLATFORM_DELETE", session, request, "PLATFORM", p.getCode(),
                    AuditDetail.of("code", p.getCode(), "name", p.getName()));
            return ok(Map.of("data", Map.of("deleted", true)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(fail(e.getMessage()));
        }
    }

    private void requireSettings(HttpSession session) {
        if (Boolean.TRUE.equals(session != null ? session.getAttribute("bootstrapAdmin") : null)) return;
        permissionService.require(session, "settings.general", "edit");
    }

    private static Map<String, Object> brief(Platform p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId()); m.put("code", p.getCode()); m.put("name", p.getName());
        m.put("description", p.getDescription()); m.put("active", Boolean.TRUE.equals(p.getActive())); m.put("sort_order", p.getSortOrder());
        return m;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String actor(HttpSession session) {
        Object u = session.getAttribute("username");
        return u != null ? u.toString() : "anonymous";
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> r = new LinkedHashMap<>(body);
        r.put("success", true);
        r.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(r);
    }

    private static Map<String, Object> fail(String msg) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false); r.put("error", msg);
        return r;
    }
}
