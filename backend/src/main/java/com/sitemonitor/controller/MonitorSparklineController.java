package com.sitemonitor.controller;

import com.sitemonitor.service.MonitorSparklineService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * İzleme kartı mini trendi (2026-09-12): {@code GET /api/monitoring/sparklines?type=http&hours=24}.
 * Ayrı controller — MonitoringController 4k satır; yeni yüzey oraya eklenmedi.
 * Kapsam: {@code monitoring.read} + takım görünürlüğü ({@link SessionScope#canView}).
 */
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitorSparklineController {

    private final MonitorSparklineService sparklineService;
    private final PermissionService permissionService;

    @GetMapping("/sparklines")
    public ResponseEntity<Map<String, Object>> sparklines(
            @RequestParam String type,
            @RequestParam(defaultValue = "24") int hours,
            HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        if (!MonitorSparklineService.supports(type)) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Bilinmeyen izleme türü: " + type));
        }
        Set<Long> visible = sparklineService.monitorTeams(type).entrySet().stream()
                .filter(e -> SessionScope.canView(session, e.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        Map<String, Object> data = new LinkedHashMap<>();
        sparklineService.sparklines(type, hours, visible).forEach((id, v) -> data.put(String.valueOf(id), v));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("type", type);
        body.put("hours", Math.max(1, Math.min(MonitorSparklineService.MAX_HOURS, hours)));
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
}
