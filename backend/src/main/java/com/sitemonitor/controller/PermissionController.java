package com.sitemonitor.controller;

import com.sitemonitor.model.PermissionGrant;
import com.sitemonitor.repository.PermissionGrantRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditDiff;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionCatalog;
import com.sitemonitor.service.PermissionService;
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

    /**
     * İzin matrisi — OKUMA herkese açık (2026-09-25, kullanıcı kararı: "admin hariç diğer kullanıcılar read
     * edebilsin, değişiklik yapamasın; sadece kimin neye yetkisi var görsün"). Oturum zorunluluğunu
     * AuthInterceptor sağlar. Yazma uçları (PUT, reset) yalnız global admin'de kalır; kapsamlı müdür de dahil
     * diğer herkese {@code can_edit=false} döner ve satırlar yalnız (rol, kaynak, eylem, izin) taşır — kimin
     * ne zaman değiştirdiği ({@code updated_by/updated_at}) yönetim bilgisidir, salt okuyana gitmez.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getMatrix(HttpSession session) {
        boolean canEdit = SessionScope.isGlobalAdmin(session);

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

        List<?> grants = canEdit ? repo.findAll() : repo.findAll().stream()
            .map(g -> Map.of("role", g.getRole(), "resource_key", g.getResourceKey(),
                    "action", g.getAction(), "allowed", Boolean.TRUE.equals(g.getAllowed())))
            .toList();

        return ResponseEntity.ok(Map.of(
            "success", true,
            "catalog", catalog,
            "grants",  grants,
            "can_edit", canEdit
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
            AuditDetail.of("role", role, "resource", resourceKey, "action", action, "allowed", allowed));
        return ResponseEntity.ok(Map.of("success", true, "data", g));
    }

    @PostMapping("/reset-to-defaults")
    public ResponseEntity<Map<String, Object>> resetToDefaults(
            HttpSession session, HttpServletRequest request) {
        requireAdmin(session);

        // Bu uç BÜTÜN izin matrisini varsayılana döndürür — sistemdeki en geniş kapsamlı tek
        // yetki işlemi. Denetimde detay `null`du: "izinler sıfırlandı" yazıyor ama ÖNCEKİ matris
        // hiçbir yerde kalmıyordu, dolayısıyla "hangi yetki kaybedildi/kazanıldı" sorusu
        // cevapsızdı. Artık öncesi/sonrası düzleştirilmiş halde diff'leniyor.
        Map<String, Object> before = flattenGrants();
        permissionService.seedDefaults();
        Map<String, Object> after = flattenGrants();

        String changes = AuditDiff.diff(before, after);
        // Çok büyük matriste diff satırı şişirir; sayılar yine de yazılır ve kırpma GÖRÜNÜR olur.
        boolean truncated = changes != null && changes.length() > MAX_RESET_DIFF_CHARS;
        auditService.recordAction("PERMISSION_RESET", session, request, "PERMISSION", "ALL",
            AuditDetail.of("grants_before", before.size(), "grants_after", after.size(),
                    "truncated", truncated),
            truncated ? null : changes);
        return ResponseEntity.ok(Map.of("success", true, "message", "Permissions reset to defaults"));
    }

    /** Diff satırının tavanı — aşarsa `changes` yazılmaz ama kırpıldığı detayda AÇIKÇA belirtilir. */
    private static final int MAX_RESET_DIFF_CHARS = 20_000;

    /** İzin matrisi → düz harita ({@code rol/kaynak/eylem → izinli mi}); diff bunun üzerinde çalışır. */
    private Map<String, Object> flattenGrants() {
        Map<String, Object> flat = new java.util.LinkedHashMap<>();
        for (PermissionGrant g : repo.findAll()) {
            flat.put(g.getRole() + "/" + g.getResourceKey() + "/" + g.getAction(), g.getAllowed());
        }
        return flat;
    }

    private void requireAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            log.warn("Unauthorized permission-matrix access by user={}",
                session.getAttribute("username"));
            throw new SecurityException("Admin access required");
        }
    }
}
