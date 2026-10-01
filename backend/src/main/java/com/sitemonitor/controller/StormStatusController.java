package com.sitemonitor.controller;

import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.StormStatusService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Predicate;

/**
 * Takım bazlı alarm fırtınası gözlemi (2026-09-30) — {@code /api/monitoring/storm/status|history|analytics|{id}}.
 *
 * <p>Kapı: {@code alerts.read/view} (Alarm Geçmişi ile aynı). Görüş kapsamı {@code SessionScope.canView} + global
 * görüntüleyici / 7-24 operatörü "hepsini görür" kuralı ({@code TodayPanelController.openAlerts} ile aynı). Ayar
 * uçları ({@code /settings}) {@link StormSettingsController}'da kalır — burada yazan uç yoktur.
 */
@RestController
@RequestMapping("/api/monitoring/storm")
@RequiredArgsConstructor
public class StormStatusController {

    private final StormStatusService statusService;
    private final PermissionService permissionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.noc.NocCallLogService nocCallLog;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpSession session, @RequestParam(defaultValue = "false") boolean fresh) {
        permissionService.require(session, "alerts.read", "view");
        boolean all = seesAll(session);
        return ResponseEntity.ok(Map.of("success", true, "data",
                statusService.status(scopeKey(session, all), canView(session), all, fresh)));
    }

    /** Ezber anahtarı: her şeyi gören "ALL", diğerleri sıralı görüş takımları (aynı kapsam → aynı yanıt). */
    static String scopeKey(HttpSession session, boolean seesAll) {
        if (seesAll) return "ALL";
        java.util.List<Long> v = SessionScope.viewTeamIds(session);
        if (v == null || v.isEmpty()) return "NONE";
        return v.stream().filter(java.util.Objects::nonNull).sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
    }

    @GetMapping("/history")
    public ResponseEntity<Map<String, Object>> history(HttpSession session,
                                                       @RequestParam(required = false) Long teamId,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to,
                                                       @RequestParam(defaultValue = "false") boolean resolvedOnly,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        permissionService.require(session, "alerts.read", "view");
        return ResponseEntity.ok(Map.of("success", true, "data",
                statusService.history(canView(session), seesAll(session), SessionScope.viewTeamIds(session),
                        teamId, from, to, resolvedOnly, page, size)));
    }

    @GetMapping("/analytics")
    public ResponseEntity<Map<String, Object>> analytics(HttpSession session,
                                                         @RequestParam(required = false) Long teamId,
                                                         @RequestParam(defaultValue = "30") int days) {
        permissionService.require(session, "alerts.read", "view");
        return ResponseEntity.ok(Map.of("success", true, "data",
                statusService.analytics(canView(session), seesAll(session), teamId, days)));
    }

    @GetMapping("/{id:\\d+}")
    public ResponseEntity<Map<String, Object>> detail(HttpSession session, @PathVariable Long id) {
        permissionService.require(session, "alerts.read", "view");
        Map<String, Object> d = statusService.detail(id, canView(session), seesAll(session));
        if (d == null) return ResponseEntity.status(404).body(Map.of("success", false, "error",
                com.sitemonitor.util.Msg.t("Fırtına bulunamadı", "Storm not found")));
        return ResponseEntity.ok(Map.of("success", true, "data", d));
    }

    private boolean seesAll(HttpSession session) {
        return SessionScope.isGlobalViewer(session) || (nocCallLog != null && nocCallLog.seesAllAlerts(session));
    }

    /** Her şeyi gören için daima true; diğerleri için {@code SessionScope.canView}. */
    private Predicate<Long> canView(HttpSession session) {
        if (seesAll(session)) return id -> true;
        return id -> id != null && SessionScope.canView(session, id);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> forbidden(SecurityException e) {
        return ResponseEntity.status(403).body(Map.of("success", false, "error", e.getMessage() != null ? e.getMessage() : "forbidden"));
    }
}
