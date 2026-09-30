package com.sitemonitor.controller;

import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * İzleme Panosu (2026-09-30): {@code GET /api/monitoring/overview?hours=24} — 9 izleme türünün tek ekranda durumu.
 * Yetki: izleme sayfalarıyla aynı ({@code monitoring.read/view}); kapsam: satırın takımı görüş kapsamında
 * ({@link SessionScope#canView}); alarm sayıları global görüntüleyicide tüm takımlar.
 */
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitoringOverviewController {

    private final MonitoringOverviewService overviewService;
    private final PermissionService permissionService;

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(@RequestParam(defaultValue = "24") int hours, HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        Map<String, Object> data = overviewService.build(teamId -> SessionScope.canView(session, teamId),
                SessionScope.isGlobalViewer(session), hours);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }
}
