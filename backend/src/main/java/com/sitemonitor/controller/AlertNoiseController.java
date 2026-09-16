package com.sitemonitor.controller;

import com.sitemonitor.service.AlertNoiseService;
import com.sitemonitor.service.AlertTeamStatsService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Alarm gürültü analizi (2026-09-12, #18): {@code GET /api/admin/alerts/noise?days=7}. Kapsam alerts.read + takım görünürlüğü. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AlertNoiseController {

    private final AlertNoiseService noiseService;
    private final AlertTeamStatsService teamStatsService;
    private final PermissionService permissionService;

    @GetMapping("/alerts/noise")
    public ResponseEntity<Map<String, Object>> noise(@RequestParam(defaultValue = "7") int days, HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        return ResponseEntity.ok(Map.of("success", true, "data", noiseService.build(days, teamId -> SessionScope.canView(session, teamId))));
    }

    /**
     * Takım kırılımı (2026-09-16): takım başına açık / kapalı / son 7 gün / son 30 gün alarm sayısı.
     * Kapsam listeyle AYNI: global görüntüleyici değilse yalnız kendi takımları (takımsız alarmlar da yok).
     */
    @GetMapping("/alerts/team-stats")
    public ResponseEntity<Map<String, Object>> teamStats(HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        boolean global = SessionScope.isGlobalViewer(session);
        return ResponseEntity.ok(Map.of("success", true,
                "data", teamStatsService.build(teamId -> SessionScope.canView(session, teamId), global)));
    }
}
