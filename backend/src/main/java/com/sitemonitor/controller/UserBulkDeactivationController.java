package com.sitemonitor.controller;

import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.UserBulkDeactivationService;
import com.sitemonitor.service.UserBulkDeactivationService.Criteria;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sistem geneli "Toplu pasife al" (2026-10-02, kullanıcı kararı: "admin sistemdeki kullanıcıları toplu pasife alabilsin;
 * admin kullanıcılar hariç"). Yönetim Paneli → Kullanıcılar → "Toplu pasife al" sihirbazının uçları:
 *
 * <ul>
 *   <li>{@code POST /api/admin/users/bulk-deactivate/preview} {@code {criteria}} → hedef listesi + dışlama sayıları
 *       (DURUM DEĞİŞTİRMEZ).</li>
 *   <li>{@code POST /api/admin/users/bulk-deactivate} {@code {criteria, expected_count, note}} → sunucu hedefi yeniden
 *       hesaplar; sayı değiştiyse 409 {@code code=BULK_LIST_CHANGED}.</li>
 *   <li>{@code POST /api/admin/users/bulk-deactivate/{opId}/undo} → işlemin hâlâ pasif kullanıcılarını açar; ikinci kez 409.</li>
 *   <li>{@code GET  /api/admin/users/bulk-operations} → son 20 işlem.</li>
 * </ul>
 *
 * <p><b>Kapı:</b> YALNIZ global yönetici ({@link SessionScope#isGlobalAdmin}). Kapsamlı müdür (ADMIN rolü + dolu
 * {@code viewTeamIds}), TEAM_ADMIN, USER ve AUDIT 403 alır — seçimli toplu çubuk ({@code POST /users/bulk}) onlar için
 * aynen kalır. Kurallar ve geri alma sözleşmesi {@link UserBulkDeactivationService}'te.
 *
 * <p>AdminController'a eklenmedi: o sınıfın {@code @WebMvcTest} dilimleri her yeni bağımlılıkta onlarca teste
 * {@code @MockitoBean} eklemeyi gerektirirdi; yollar ({@code /users/bulk-deactivate…}, {@code /users/bulk-operations})
 * oradaki {@code /users/{id}} kalıplarıyla çakışmaz (Spring sabit segmenti tercih eder).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class UserBulkDeactivationController {

    private final UserBulkDeactivationService service;
    private final PermissionService permissionService;

    @PostMapping("/bulk-deactivate/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody(required = false) Map<String, Object> body,
                                                       HttpSession session) {
        requireGlobalAdmin(session);
        permissionService.require(session, "users.crud", "edit");
        return ok(service.preview(Criteria.from(body), userId(session)));
    }

    @PostMapping("/bulk-deactivate")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody(required = false) Map<String, Object> body,
                                                     HttpSession session) {
        requireGlobalAdmin(session);
        permissionService.require(session, "users.crud", "edit");
        // Uygulamada ölçüt AÇIKÇA gönderilmeli — eksik gövde "herkes" varsayılanına düşmesin.
        if (body == null || !(body.get("criteria") instanceof Map)) {
            throw new IllegalArgumentException(Msg.t("Ölçüt (criteria) zorunlu.", "Criteria are required."));
        }
        Criteria criteria = Criteria.from(body);
        Object expected = body == null ? null : body.get("expected_count");
        Integer expectedCount = expected instanceof Number n ? Integer.valueOf(n.intValue()) : parseInt(expected);
        Object note = body == null ? null : body.get("note");
        try {
            return ok(service.apply(criteria, expectedCount, note == null ? null : note.toString(), session));
        } catch (UserBulkDeactivationService.ListChangedException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", e.getMessage());
            err.put("code", UserBulkDeactivationService.CODE_LIST_CHANGED);
            err.put("current_count", e.currentCount());
            return ResponseEntity.status(409).body(err);
        }
    }

    @PostMapping("/bulk-deactivate/{opId}/undo")
    public ResponseEntity<Map<String, Object>> undo(@PathVariable Long opId, HttpSession session) {
        requireGlobalAdmin(session);
        permissionService.require(session, "users.crud", "edit");
        return ok(service.undo(opId, session));
    }

    @GetMapping("/bulk-operations")
    public ResponseEntity<Map<String, Object>> history(HttpSession session) {
        requireGlobalAdmin(session);
        permissionService.require(session, "users.list", "view");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operations", service.history());
        return ok(data);
    }

    private static void requireGlobalAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t(
                    "Toplu pasife alma yalnız global yöneticiye açıktır.",
                    "Bulk deactivation is available to global administrators only."));
        }
    }

    private static Long userId(HttpSession session) {
        Object v = session == null ? null : session.getAttribute("userId");
        if (v instanceof Number n) return n.longValue();
        try { return v == null ? null : Long.valueOf(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static Integer parseInt(Object v) {
        if (v == null) return null;
        try { return Integer.valueOf(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
