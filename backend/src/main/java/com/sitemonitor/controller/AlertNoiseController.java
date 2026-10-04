package com.sitemonitor.controller;

import com.sitemonitor.service.AlertNoiseService;
import com.sitemonitor.service.AlertTeamStatsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Alarm gürültü analizi (2026-09-12, #18): {@code GET /api/admin/alerts/noise?days=7[&team=ID]}. Kapsam alerts.read +
 * takım görünürlüğü. {@code team} (2026-10-01): görülebilir olmayan takım → 403; yanıta oturum sahibinin üye olduğu
 * takımlar ({@code my_team_ids}) ve birincil takımı ({@code default_team_id}) eklenir — seçici "Takımlarım"ı öne alır.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AlertNoiseController {

    private final AlertNoiseService noiseService;
    private final AlertTeamStatsService teamStatsService;
    private final PermissionService permissionService;

    @GetMapping("/alerts/noise")
    public ResponseEntity<Map<String, Object>> noise(@RequestParam(defaultValue = "7") int days,
                                                     @RequestParam(required = false) Long team,
                                                     HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        if (team != null && !SessionScope.canViewMonitoring(session, team)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("success", false,
                    "message", Msg.t("Bu takımı görüntüleme yetkiniz yok.", "You are not allowed to view this team.")));
        }
        Map<String, Object> data = noiseService.build(days, team, teamId -> SessionScope.canViewMonitoring(session, teamId));
        data.put("my_team_ids", SessionScope.memberTeamIds(session));
        data.put("default_team_id", SessionScope.primaryTeamId(session));
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /**
     * Isı haritası hücresi ayrıntısı (2026-10-01): {@code GET /api/admin/alerts/noise/slot?days=7&dow=0..6&hour=0..23[&team=ID]}
     * — hücredeki alarmlar (en çok 200, en yeniden). Kapı ve kapsam {@code /alerts/noise} ile aynı; geçersiz gün/saat → 400.
     */
    @GetMapping("/alerts/noise/slot")
    public ResponseEntity<Map<String, Object>> noiseSlot(@RequestParam(defaultValue = "7") int days,
                                                         @RequestParam int dow, @RequestParam int hour,
                                                         @RequestParam(required = false) Long team,
                                                         HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        if (team != null && !SessionScope.canViewMonitoring(session, team)) {
            return ResponseEntity.status(403).body(Map.of("success", false,
                    "message", Msg.t("Bu takımı görüntüleme yetkiniz yok.", "You are not allowed to view this team.")));
        }
        if (dow < 0 || dow > 6 || hour < 0 || hour > 23) {
            return ResponseEntity.badRequest().body(Map.of("success", false,
                    "message", Msg.t("Geçersiz gün ya da saat.", "Invalid day or hour.")));
        }
        return ResponseEntity.ok(Map.of("success", true, "data",
                noiseService.slot(days, team, teamId -> SessionScope.canViewMonitoring(session, teamId), dow, hour)));
    }

    /**
     * Takım kırılımı (2026-09-16): takım başına açık / kapalı / son 7 gün / son 30 gün alarm sayısı.
     * Kapsam listeyle AYNI: global görüntüleyici değilse yalnız kendi takımları (takımsız alarmlar da yok).
     */
    @GetMapping("/alerts/team-stats")
    public ResponseEntity<Map<String, Object>> teamStats(HttpSession session) {
        permissionService.require(session, "alerts.read", "view");
        boolean global = SessionScope.seesAllMonitoring(session);   // + 7/24 operatörü (2026-10-04, salt okuma)
        return ResponseEntity.ok(Map.of("success", true,
                "data", teamStatsService.build(teamId -> SessionScope.canViewMonitoring(session, teamId), global)));
    }
}
