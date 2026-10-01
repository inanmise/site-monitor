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
 * Performans (2026-10-01): sonuç görüş kapsamı anahtarı + pencere başına kısa süre (varsayılan 30 sn) sunucuda paylaşılır.
 * {@code fresh=1|true} (sayfanın Yenile düğmesi, 2026-10-01 yeniden tasarım): bellek kaydı 5 sn'den eskiyse yeniden
 * hesaplanır — dakikalık yoklama bellekten okumaya devam eder (bkz. {@code MonitoringOverviewService#FRESH_MIN_MS}).
 */
@RestController
@RequestMapping("/api/monitoring")
@RequiredArgsConstructor
public class MonitoringOverviewController {

    private final MonitoringOverviewService overviewService;
    private final PermissionService permissionService;

    @GetMapping("/overview")
    public ResponseEntity<Map<String, Object>> overview(@RequestParam(defaultValue = "24") int hours,
                                                        @RequestParam(value = "fresh", required = false) String fresh,
                                                        HttpSession session) {
        permissionService.require(session, "monitoring.read", "view");
        boolean global = SessionScope.isGlobalViewer(session);
        // Bellek anahtarı canView yüklemini TAM belirler: global → hepsi; aksi halde yalnız görüş takımları.
        String scopeKey = com.sitemonitor.util.TtlMemo.scopeKey(global, SessionScope.viewTeamIds(session));
        java.util.function.Predicate<Long> canView = teamId -> SessionScope.canView(session, teamId);
        Map<String, Object> data = isFresh(fresh)
                ? overviewService.build(scopeKey, canView, global, hours, true)
                : overviewService.build(scopeKey, canView, global, hours);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** Taze istemeyen çağrı (yoklama) — eski imza; testler ve iç çağıranlar için. */
    public ResponseEntity<Map<String, Object>> overview(int hours, HttpSession session) {
        return overview(hours, null, session);
    }

    static boolean isFresh(String v) {
        return v != null && ("1".equals(v.trim()) || "true".equalsIgnoreCase(v.trim()));
    }
}
