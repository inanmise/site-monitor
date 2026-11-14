package com.sitemonitor.controller;

import com.sitemonitor.service.AvailabilityWeekService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Kişisel Panom "7 günlük erişilebilirlik" (2026-11-14): {@code GET /api/monitoring/availability-week} — görüş kapsamındaki
 * izlemelerin son 7 günde ölçülen erişilebilirliği ve filo hedefiyle karşılaştırması (tek küçük yanıt; istemci 9 türün kart
 * SLA yanıtını ayrı ayrı çekip toplamaz). Kapı ve kapsam kardeş uçlarla ({@code /sparklines}, {@code /sla}) aynı:
 * {@code monitoring.read/view} + izleme okuma kapsamı ({@link SessionScope#canViewMonitoring}: görüş takımları, erişim
 * paylaşımları, 7/24 operatörü). Okuma hatası → 503 {@code DB_UNAVAILABLE} (asla sıfır / %100).
 */
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class AvailabilityWeekController {

    private final AvailabilityWeekService availabilityWeekService;
    private final PermissionService permissionService;

    @GetMapping("/availability-week")
    public ResponseEntity<Map<String, Object>> availabilityWeek(HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        try {
            return ResponseEntity.ok(Map.of("success", true, "data",
                    availabilityWeekService.build(teamId -> SessionScope.canViewMonitoring(session, teamId))));
        } catch (AvailabilityWeekService.UnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("success", false, "code", "DB_UNAVAILABLE",
                    "error", Msg.t("7 günlük erişilebilirlik şu an okunamıyor; veritabanına geçici olarak ulaşılamıyor olabilir. Birkaç saniye sonra yeniden deneyin.",
                            "The 7-day availability can’t be read right now; the database may be briefly unreachable. Try again in a few seconds.")));
        }
    }
}
