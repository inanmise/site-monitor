package com.sitemonitor.controller;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.InventoryImportService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.report.InventoryHygieneService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Predicate;

/**
 * Envanter sayfası zenginleştirmeleri (2026-09-12):
 * <ul>
 *   <li>{@code GET /api/admin/inventory/hygiene} — e-posta raporunun hijyen analizi sayfada (#2):
 *       eksik takım/tier/sorumlu, hiç kontrol edilmemiş, kontrol hatası, sertifika sağlığı. Kapsam
 *       {@code listInventory} ile aynı (global görüntüleyici → hepsi, aksi halde görünür takımlar).
 *       Yalnız yönetebilenler (ADMIN / TEAM_ADMIN): {@code inventory.crud} edit izni — USER için gürültü.</li>
 *   <li>{@code POST /api/admin/inventory/import} — CSV içe aktarma (#6): {@code dry_run:true} plan,
 *       {@code false} işleme; kapsam satır başına (bkz. {@link InventoryImportService}).</li>
 * </ul>
 * AdminController'a bağımlılık EKLENMEDİ (37 mock'lu WebMvc testi); ayrı, küçük denetleyici.
 */
@RestController
@RequestMapping("/api/admin/inventory")
@RequiredArgsConstructor
public class InventoryInsightController {

    private static final int MAX_IMPORT_ROWS = 5000;

    private final InventoryHygieneService hygieneService;
    private final InventoryImportService importService;
    private final CertificateInventoryRepository inventoryRepo;
    private final PermissionService permissionService;
    private final AuditService auditService;

    @GetMapping("/hygiene")
    public ResponseEntity<Map<String, Object>> hygiene(HttpSession session) {
        permissionService.require(session, "inventory.list", "view");
        permissionService.require(session, "inventory.crud", "edit");
        List<CertificateInventory> rows;
        if (SessionScope.isGlobalViewer(session)) {
            rows = inventoryRepo.findByDeletedAtIsNullOrderByDomainAsc();
        } else {
            List<Long> scope = SessionScope.viewTeamIds(session);
            rows = (scope == null || scope.isEmpty()) ? List.of()
                    : inventoryRepo.findByTeamIdInAndDeletedAtIsNullOrderByDomainAsc(scope);
        }
        InventoryHygieneService.Result r = hygieneService.analyze(rows, Integer.MAX_VALUE);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (InventoryHygieneService.Group g : r.groups()) {
            List<Map<String, Object>> findings = new ArrayList<>();
            for (InventoryHygieneService.Finding f : g.samples()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("domain", f.domain()); m.put("detail", f.detail()); m.put("codes", f.codes());
                findings.add(m);
            }
            Map<String, Object> gm = new LinkedHashMap<>();
            gm.put("key", g.key()); gm.put("title", g.title()); gm.put("total", g.total()); gm.put("findings", findings);
            groups.add(gm);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", r.totalFindings());
        data.put("scanned", rows.size());
        data.put("groups", groups);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    @CacheEvict(value = {"cert-latest", "cert-warnings", "cert-stats", "renewal-advice"}, allEntries = true)
    @PostMapping("/import")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> importRows(@RequestBody Map<String, Object> body,
                                                          HttpSession session, HttpServletRequest request) {
        permissionService.require(session, "inventory.crud", "edit");
        // AdminController.requireAdminOrTeamAdmin ile AYNI kural: global admin ya da yönettiği takımı olan.
        List<Long> managed = SessionScope.manageTeamIds(session);
        if (!SessionScope.isGlobalAdmin(session) && (managed == null || managed.isEmpty())) {
            throw new SecurityException("Admin or team-admin required");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Object raw = body.get("rows");
        if (raw instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) rows.add((Map<String, Object>) m);
        if (rows.isEmpty()) throw new IllegalArgumentException("rows: empty");
        if (rows.size() > MAX_IMPORT_ROWS) throw new IllegalArgumentException("rows: at most " + MAX_IMPORT_ROWS + " per import");
        boolean dryRun = !Boolean.FALSE.equals(body.get("dry_run"));   // varsayılan KURU koşu — yanlışlıkla yazma yok
        Predicate<Long> canManage = teamId -> SessionScope.canManage(session, teamId);
        String actor = String.valueOf(session.getAttribute("username"));

        InventoryImportService.Result r = dryRun
                ? importService.plan(rows, canManage, actor, session)
                : importService.commit(rows, canManage, actor, session);
        if (!dryRun) {
            List<String> created = r.rows().stream().filter(x -> "create".equals(x.action())).map(InventoryImportService.RowResult::domain).toList();
            List<String> updated = r.rows().stream().filter(x -> "update".equals(x.action())).map(InventoryImportService.RowResult::domain).toList();
            auditService.recordAction("DOMAIN_IMPORT", session, request, "CERTIFICATE", r.created() + "+" + r.updated() + " domain",
                    "{\"created\":" + r.created() + ",\"updated\":" + r.updated() + ",\"skipped\":" + r.skipped()
                            + ",\"errors\":" + r.errors() + ",\"createdDomains\":" + jsonArray(created)
                            + ",\"updatedDomains\":" + jsonArray(updated) + "}");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dry_run", r.dryRun());
        data.put("created", r.created()); data.put("updated", r.updated());
        data.put("skipped", r.skipped()); data.put("errors", r.errors());
        data.put("rows", r.rows());
        data.put("columns", InventoryImportService.COLUMNS);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    private static String jsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }
}
