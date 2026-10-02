package com.sitemonitor.controller;

import com.sitemonitor.model.SystemMaintenanceWindow;
import com.sitemonitor.service.SystemMaintenanceService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sistem Bakım Modu uçları (2026-10-02, kullanıcı kararı) — Ayarlar → Platform → "Sistem Bakımı".
 *
 * <ul>
 *   <li>{@code GET  /api/admin/system-maintenance} → sunucu saati, en yakın/aktif pencere, yaklaşanlar, etki özeti
 *       ("şu an N kullanıcı oturum açık; M'i çıkış yapacak"), seçenekler, e-posta alıcı seçenekleri.</li>
 *   <li>{@code GET  /api/admin/system-maintenance/history?page=&size=} → geçmiş (kim planladı/başlattı/bitirdi,
 *       plan/gerçek zamanlar, kapatılan oturum, engellenen giriş, susturulan bildirim).</li>
 *   <li>{@code GET  /api/admin/system-maintenance/{id}} → ayrıntı (+ telafi dökümü).</li>
 *   <li>{@code POST /api/admin/system-maintenance} → planla; {@code POST …/start-now} → hemen bakıma al (geri sayımlı);
 *       {@code PUT …/{id}} → düzenle (başlamadan önce); {@code POST …/{id}/extend}, {@code …/{id}/end-now},
 *       {@code …/{id}/cancel}.</li>
 *   <li>{@code GET  /api/public/system-maintenance} → giriş sayfası (OTURUMSUZ, {@code AuthInterceptor} PUBLIC): yalnız
 *       durum, pencere saatleri, TR/EN mesaj ve iletişim — kimlik/kişi/IP/sayaç YOK. {@code /api} yanıtları genel
 *       {@code no-store} başlığını taşır (WebConfig) — paylaşımlı önbellek bakım bilgisini bayat tutmaz.</li>
 * </ul>
 *
 * <p><b>Kapı:</b> yönetim uçlarının TAMAMI (okuma dahil) YALNIZ global yönetici ({@link SessionScope#isGlobalAdmin});
 * kapsamlı müdür, TEAM_ADMIN, AUDIT ve USER 403. Doğrulama hatası 400 + {@code field} (arayüz alanın altında gösterir);
 * durum çakışması (ör. süren bakımı iptal) 409.
 */
@RestController
@RequiredArgsConstructor
public class SystemMaintenanceController {

    private final SystemMaintenanceService service;

    @GetMapping("/api/public/system-maintenance")
    public ResponseEntity<Map<String, Object>> publicStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", service.publicStatus());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/admin/system-maintenance")
    public ResponseEntity<Map<String, Object>> overview(HttpSession session) {
        requireGlobalAdmin(session);
        return ok(service.overview());
    }

    @GetMapping("/api/admin/system-maintenance/history")
    public ResponseEntity<Map<String, Object>> history(@RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size,
                                                       HttpSession session) {
        requireGlobalAdmin(session);
        return ok(service.history(page, size));
    }

    @GetMapping("/api/admin/system-maintenance/{id}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id, HttpSession session) {
        requireGlobalAdmin(session);
        return ok(service.detail(id));
    }

    @PostMapping("/api/admin/system-maintenance")
    public ResponseEntity<Map<String, Object>> schedule(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpSession session) {
        requireGlobalAdmin(session);
        return window(service.schedule(body, session));
    }

    @PostMapping("/api/admin/system-maintenance/start-now")
    public ResponseEntity<Map<String, Object>> startNow(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpSession session) {
        requireGlobalAdmin(session);
        return window(service.startNow(body, session));
    }

    @PutMapping("/api/admin/system-maintenance/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpSession session) {
        requireGlobalAdmin(session);
        return window(service.update(id, body, session));
    }

    @PostMapping("/api/admin/system-maintenance/{id}/extend")
    public ResponseEntity<Map<String, Object>> extend(@PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpSession session) {
        requireGlobalAdmin(session);
        return window(service.extend(id, body, session));
    }

    @PostMapping("/api/admin/system-maintenance/{id}/end-now")
    public ResponseEntity<Map<String, Object>> endNow(@PathVariable Long id, HttpSession session) {
        requireGlobalAdmin(session);
        return window(service.endNow(id, session));
    }

    @PostMapping("/api/admin/system-maintenance/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id, HttpSession session) {
        requireGlobalAdmin(session);
        return window(service.cancel(id, session));
    }

    /** Alan-bazlı doğrulama hatası → 400 + {@code field} (genel işleyici alanı taşımaz). */
    @ExceptionHandler(SystemMaintenanceService.FieldException.class)
    public ResponseEntity<Map<String, Object>> fieldError(SystemMaintenanceService.FieldException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", e.getMessage());
        body.put("field", e.field());
        return ResponseEntity.status(400).body(body);
    }

    static void requireGlobalAdmin(HttpSession session) {
        if (!SessionScope.isGlobalAdmin(session)) {
            throw new SecurityException(Msg.t("Sistem bakımı yalnız global yöneticiye açıktır.",
                    "System maintenance is available to global administrators only."));
        }
    }

    private ResponseEntity<Map<String, Object>> window(SystemMaintenanceWindow w) {
        Map<String, Object> data = service.toDto(w);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        body.put("server_now", service.serverNow());
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
