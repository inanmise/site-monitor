package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.AlertTrendService;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kişisel Panom "Alarm eğilimi" (2026-11-14): {@code GET /api/me/alert-trend?range=24h|7d} — görüş kapsamındaki alarmların
 * kova başına açılan / kapanan sayısı. Kapı ve kapsam {@code /api/me/open-alerts} ile AYNI: Alarm Geçmişi'ni açamayan
 * ({@code alerts.read/view} yok) {@code visible:false} alır (403 değil — kutu dakikada bir yoklar, yetkisi kalkan
 * kullanıcının her yoklaması güvenlik denetimine "erişim reddi" yazmasın); tümünü gören = global görüntüleyici / 7-24
 * operatörü, diğerleri görüş takımları + erişim paylaşımları. Okuma hatası → 503 {@code DB_UNAVAILABLE} (asla sıfır).
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class AlertTrendController {

    private final AlertTrendService alertTrendService;
    private final PermissionService permissionService;
    /** 7/24 operatörü tüm alarmları görür (AdminController.seesAllAlerts ile aynı) — alan enjeksiyonu, null-güvenli. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.noc.NocCallLogService nocCallLog;

    @GetMapping("/alert-trend")
    public ResponseEntity<Map<String, Object>> alertTrend(@RequestParam(defaultValue = AlertTrendService.RANGE_DAY) String range,
                                                          HttpSession session) {
        if (!AlertTrendService.RANGES.contains(range)) {
            throw new FieldValidationException("range", Msg.t("Geçersiz dönem: yalnız 24h ya da 7d seçilebilir.",
                    "Invalid period: only 24h or 7d can be selected."));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (!permissionService.allows(session, "alerts.read", "view")) {
            out.put("visible", false);
            out.put("range", range);
            return ResponseEntity.ok(Map.of("success", true, "data", out));
        }
        boolean seesAll = SessionScope.isGlobalViewer(session) || (nocCallLog != null && nocCallLog.seesAllAlerts(session));
        try {
            out.putAll(alertTrendService.build(range, seesAll, SessionScope.viewTeamIdsWithGrants(session)));
        } catch (AlertTrendService.UnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("success", false, "code", "DB_UNAVAILABLE",
                    "error", Msg.t("Alarm eğilimi şu an okunamıyor; veritabanına geçici olarak ulaşılamıyor olabilir. Birkaç saniye sonra yeniden deneyin.",
                            "The alert trend can’t be read right now; the database may be briefly unreachable. Try again in a few seconds.")));
        }
        out.put("visible", true);
        return ResponseEntity.ok(Map.of("success", true, "data", out));
    }
}
