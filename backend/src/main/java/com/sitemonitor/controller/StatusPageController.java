package com.sitemonitor.controller;

import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.StatusPageService;
import com.sitemonitor.util.TtlMemo;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Kurum içi Durum Sayfası (2026-10-01, onaylı öneri 18): {@code GET /api/status-page[?fresh=1]}.
 *
 * <p><b>Giriş gerektirir, dışarıya açılmaz:</b> {@code AuthInterceptor} {@code /api/**} için oturum ister ve bu yol
 * PUBLIC listesinde YOKTUR (StatusPageControllerTest bunu pinler). Oturum açmış HERKES görür — yeni izin anahtarı yok.
 *
 * <p><b>"Mevcudu bozma" (2026-10-01):</b> ayrıntılar bugünkü ekranların kurallarıyla süzülür — izleme listesi
 * {@link SessionScope#canView}; olay satırı {@code incidents.view} + {@link IncidentController#canReadIncident} (Olaylar
 * ekranının okuma kuralı); bakım satırı {@code maintenance.view} + {@link MaintenanceController#canSeeWindow} (Bakım
 * Pencereleri listesinin kuralı). Kural dışı kalan olay/pencere yalnız sayıya katılır.
 *
 * <p>Bellek anahtarı üç yüklemi TAM belirler: görüş kapsamı ({@code "ALL"} ya da sıralı görüş takımları — İzleme Panosu ile
 * aynı) + iki izin bayrağı. {@code fresh=1|true}: belleği en fazla 5 sn'de bir atlar. Yanıt {@code /api} için genel
 * {@code no-store} başlığını taşır (WebConfig güvenlik süzgeci).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class StatusPageController {

    private final StatusPageService statusPageService;
    private final PermissionService permissionService;

    @GetMapping("/status-page")
    public ResponseEntity<Map<String, Object>> statusPage(@RequestParam(value = "fresh", required = false) String fresh,
                                                          HttpSession session) {
        boolean global = SessionScope.isGlobalViewer(session);
        boolean incidents = permissionService.allows(session, "incidents.view", "view");
        boolean maintenance = permissionService.allows(session, "maintenance.view", "view");
        String memoKey = memoKey(TtlMemo.scopeKey(global, SessionScope.viewTeamIds(session)), incidents, maintenance);
        StatusPageService.Viewer viewer = new StatusPageService.Viewer(
                teamId -> SessionScope.canView(session, teamId),
                (teamId, createdByTeamId) -> incidents && IncidentController.canReadIncident(session, teamId, createdByTeamId),
                (teamId, allMonitors) -> maintenance && MaintenanceController.canSeeWindow(session, teamId, allMonitors));
        Map<String, Object> data = statusPageService.view(memoKey, viewer, MonitoringOverviewController.isFresh(fresh));
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** Görüş kapsamı + izin bayrakları — aynı anahtarı paylaşan iki oturumun üç yüklemi de aynıdır. */
    static String memoKey(String scopeKey, boolean incidents, boolean maintenance) {
        return scopeKey + "|inc=" + (incidents ? 1 : 0) + "|mw=" + (maintenance ? 1 : 0);
    }
}
