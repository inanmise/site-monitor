package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.metrics.SystemMetricsQueryService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Sistem Sağlığı → Metrikler (2026-10-13, kullanıcı isteği): SiteMonitor'ün kendi metriklerinin pod başına kalıcı geçmişi.
 * Kapı Sistem Sağlığı ile aynı ({@code system_health.read}). Yalnız okuma; yanıt sayılardan ibarettir (makine adı dışında
 * altyapı bilgisi yok).
 */
@RestController
@RequestMapping("/api/admin/system/metrics")
@RequiredArgsConstructor
public class SystemMetricsController {

    private final PermissionService permissionService;
    private final SystemMetricsQueryService queryService;

    /** Podlar + her podun son örneği + son 60 dakikanın toplamları. */
    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(HttpSession session) {
        permissionService.require(session, "system_health.read", "view");
        return ResponseEntity.ok(Map.of("success", true, "data", queryService.overview(Instant.now())));
    }

    /** Pod × kova serisi: {@code range} 1h | 6h | 24h | 7d (bilinmeyen → 6h), {@code instance} boş = tüm podlar. */
    @GetMapping("/series")
    public ResponseEntity<Map<String, Object>> series(HttpSession session,
                                                      @RequestParam(defaultValue = "6h") String range,
                                                      @RequestParam(required = false) String instance) {
        permissionService.require(session, "system_health.read", "view");
        String inst = instance == null || instance.isBlank() ? null : instance.trim();
        if (inst != null && inst.length() > 80) {   // örnek kimliği en çok 72 karakter; uzunu "tüm podlar" sayılmaz
            throw new GlobalExceptionHandler.FieldValidationException("instance", Msg.t(
                    "Pod kimliği en çok 80 karakter olabilir. Pod seçimini listeden yapın.",
                    "A pod id can be at most 80 characters. Pick the pod from the list."));
        }
        return ResponseEntity.ok(Map.of("success", true, "data", queryService.series(range, inst, Instant.now())));
    }
}
