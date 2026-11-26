package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.BuildInfo;
import com.sitemonitor.service.CertRenewalPlanService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RenewalForecastService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Vade Takvimi ucu (2026-09-12): {@code GET /api/forecast} tek gövde (bkz. {@link RenewalForecastService}),
 * {@code POST/DELETE /api/forecast/{domain}/plan} planlanan yenileme tarihi (#6). Kapsam: görünür takımlar
 * ({@code SessionScope.canView}); plan yazmak için kayıt yönetilebilir olmalı ({@code inventory.crud} edit).
 */
@RestController
@RequestMapping("/api/forecast")
@RequiredArgsConstructor
public class ForecastController {

    private final RenewalForecastService forecastService;
    private final CertificateInventoryRepository inventoryRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final CertRenewalPlanService renewalPlans;
    private final CertificateService certificateService;
    private final BuildInfo buildInfo;

    @GetMapping
    public ResponseEntity<Map<String, Object>> forecast(HttpSession session) {
        permissionService.require(session, "inventory.list", "view");
        // İzleme OKUMA kapsamı (2026-10-04): 7/24 operatörü tüm envanterin yenileme tahminini görür; plan yazma uçları
        // (POST/DELETE /{domain}/plan) canManage'ta kalır.
        List<Long> scope = SessionScope.monitoringViewTeamIds(session);
        Map<String, Object> data = forecastService.build(scope, teamId -> SessionScope.canViewMonitoring(session, teamId));
        data.put("environment", buildInfo.get().environment());   // /system/version ile AYNI kaynak (#4)
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /**
     * Plan koy / güncelle (H paketi 2026-11-26, sertifika B4 / B7): doğrulama {@link CertRenewalPlanService#plan} içinde —
     * gerçek takvim günü değilse 400 alan {@code date}, not 500 karakteri aşarsa 400 alan {@code note} (eskiden sessizce
     * kesiliyordu); yazım HEDEFLİ (yalnız plan kolonları + "son güncelleyen"), varlığın bütünü kaydedilmez.
     */
    @PostMapping("/{domain}/plan")
    public ResponseEntity<Map<String, Object>> plan(@PathVariable String domain, @RequestBody Map<String, Object> body,
                                                    HttpSession session, HttpServletRequest request) {
        CertificateInventory inv = manageable(session, domain);
        renewalPlans.plan(inv, body.get("date"), body.get("note"), session);
        String note = inv.getRenewalPlannedNote();
        auditService.recordAction("CERT_RENEWAL_PLANNED", session, request, "CERTIFICATE", domain,
                AuditDetail.of("domain", domain, "planned_at", inv.getRenewalPlannedAt(), "note", note == null ? "" : note));
        certificateService.evictAllCaches();
        return ResponseEntity.ok(Map.of("success", true, "data", planJson(inv)));
    }

    /**
     * Planı kaldır (B7): KOŞULLU yazım — okunan plan hâlâ yerindeyse. Plan yoksa ya da arada değiştiyse hiçbir şey yazılmaz,
     * denetim kaydı da yazılmaz; yanıt güncel durumu ({@code changed: false}) döndürür.
     */
    @DeleteMapping("/{domain}/plan")
    public ResponseEntity<Map<String, Object>> unplan(@PathVariable String domain, HttpSession session, HttpServletRequest request) {
        CertificateInventory inv = manageable(session, domain);
        String was = inv.getRenewalPlannedAt();
        boolean changed = renewalPlans.unplan(inv, session);
        if (changed) {
            auditService.recordAction("CERT_RENEWAL_PLAN_CLEARED", session, request, "CERTIFICATE", domain,
                    AuditDetail.of("domain", domain, "was_planned_at", was == null ? "" : was));
            certificateService.evictAllCaches();
        } else {
            // Arada değişti ya da zaten plan yok: güncel kaydı oku, yanıt ekranı doğrusuna çeksin.
            inv = inventoryRepo.findByDomain(domain).filter(i -> i.getDeletedAt() == null).orElse(inv);
        }
        Map<String, Object> data = planJson(inv);
        data.put("changed", changed);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    private CertificateInventory manageable(HttpSession session, String domain) {
        permissionService.require(session, "inventory.crud", "edit");
        CertificateInventory inv = inventoryRepo.findByDomain(domain).orElse(null);
        if (inv == null || inv.getDeletedAt() != null) throw new IllegalArgumentException("Domain not found: " + domain);
        if (!SessionScope.canManage(session, inv.getTeamId())) throw new SecurityException("Not allowed for this team");
        return inv;
    }

    private static Map<String, Object> planJson(CertificateInventory i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", i.getDomain()); m.put("renewal_planned_at", i.getRenewalPlannedAt());
        m.put("renewal_planned_by", i.getRenewalPlannedByName()); m.put("renewal_planned_note", i.getRenewalPlannedNote());
        return m;
    }

}
