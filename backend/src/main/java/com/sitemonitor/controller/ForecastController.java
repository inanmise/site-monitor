package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.BuildInfo;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.MonitorHistoryService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RenewalForecastService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final RenewalForecastService forecastService;
    private final CertificateInventoryRepository inventoryRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final MonitorHistoryService monitorHistory;
    private final CertificateService certificateService;
    private final BuildInfo buildInfo;

    @GetMapping
    public ResponseEntity<Map<String, Object>> forecast(HttpSession session) {
        permissionService.require(session, "inventory.list", "view");
        List<Long> scope = SessionScope.viewTeamIds(session);
        Map<String, Object> data = forecastService.build(scope, teamId -> SessionScope.canView(session, teamId));
        data.put("environment", buildInfo.get().environment());   // /system/version ile AYNI kaynak (#4)
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @PostMapping("/{domain}/plan")
    public ResponseEntity<Map<String, Object>> plan(@PathVariable String domain, @RequestBody Map<String, Object> body,
                                                    HttpSession session, HttpServletRequest request) {
        CertificateInventory inv = manageable(session, domain);
        String date = body.get("date") == null ? "" : String.valueOf(body.get("date")).trim();
        if (!date.matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new IllegalArgumentException("date: YYYY-MM-DD expected");
        String note = body.get("note") == null ? null : String.valueOf(body.get("note")).trim();
        if (note != null && note.length() > 500) note = note.substring(0, 500);
        Map<String, Object> before = snapshot(inv);
        inv.setRenewalPlannedAt(date);
        inv.setRenewalPlannedBy(String.valueOf(session.getAttribute("username")));
        Object dn = session.getAttribute("displayName");
        inv.setRenewalPlannedByName(dn != null ? dn.toString() : String.valueOf(session.getAttribute("username")));
        inv.setRenewalPlannedNote(note == null || note.isBlank() ? null : note);
        inv.setUpdatedAt(ISO.format(Instant.now()));
        inventoryRepo.save(inv);
        monitorHistory.record(MonitorHistoryService.INVENTORY, inv.getId(), inv.getDomain(), inv.getTeamId(),
                MonitorHistoryService.UPDATE, before, snapshot(inv), "renewal-plan", session);
        auditService.recordAction("CERT_RENEWAL_PLANNED", session, request, "CERTIFICATE", domain,
                AuditDetail.of("domain", domain, "planned_at", date, "note", note == null ? "" : note));
        certificateService.evictAllCaches();
        return ResponseEntity.ok(Map.of("success", true, "data", planJson(inv)));
    }

    @DeleteMapping("/{domain}/plan")
    public ResponseEntity<Map<String, Object>> unplan(@PathVariable String domain, HttpSession session, HttpServletRequest request) {
        CertificateInventory inv = manageable(session, domain);
        Map<String, Object> before = snapshot(inv);
        String was = inv.getRenewalPlannedAt();
        inv.setRenewalPlannedAt(null); inv.setRenewalPlannedBy(null); inv.setRenewalPlannedByName(null); inv.setRenewalPlannedNote(null);
        inv.setUpdatedAt(ISO.format(Instant.now()));
        inventoryRepo.save(inv);
        monitorHistory.record(MonitorHistoryService.INVENTORY, inv.getId(), inv.getDomain(), inv.getTeamId(),
                MonitorHistoryService.UPDATE, before, snapshot(inv), "renewal-plan", session);
        auditService.recordAction("CERT_RENEWAL_PLAN_CLEARED", session, request, "CERTIFICATE", domain,
                AuditDetail.of("domain", domain, "was_planned_at", was == null ? "" : was));
        certificateService.evictAllCaches();
        return ResponseEntity.ok(Map.of("success", true, "data", planJson(inv)));
    }

    private CertificateInventory manageable(HttpSession session, String domain) {
        permissionService.require(session, "inventory.crud", "edit");
        CertificateInventory inv = inventoryRepo.findByDomain(domain).orElse(null);
        if (inv == null || inv.getDeletedAt() != null) throw new IllegalArgumentException("Domain not found: " + domain);
        if (!SessionScope.canManage(session, inv.getTeamId())) throw new SecurityException("Not allowed for this team");
        return inv;
    }

    private static Map<String, Object> snapshot(CertificateInventory i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("renewalPlannedAt", i.getRenewalPlannedAt()); m.put("renewalPlannedNote", i.getRenewalPlannedNote());
        return m;
    }
    private static Map<String, Object> planJson(CertificateInventory i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", i.getDomain()); m.put("renewal_planned_at", i.getRenewalPlannedAt());
        m.put("renewal_planned_by", i.getRenewalPlannedByName()); m.put("renewal_planned_note", i.getRenewalPlannedNote());
        return m;
    }

}
