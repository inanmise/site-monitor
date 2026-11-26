package com.sitemonitor.controller;

import com.sitemonitor.service.GlobalSearchService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Komut paleti (2026-09-12, #1): {@code GET /api/search?q=…}. Oturumu olan herkes; kapsam
 * {@link SessionScope#canView} (global görüntüleyici hepsini, takım kullanıcısı yalnız kendi takımlarını görür).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GlobalSearchController {

    private final GlobalSearchService searchService;

    /** Olay kaydı araması (2026-11-26) {@code incidents.view} ister — dilim testlerinde bean yoksa olay araması yapılmaz. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.PermissionService permissionService;

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam(defaultValue = "") String q, HttpSession session) {
        List<GlobalSearchService.Hit> hits = searchService.search(q, teamId -> SessionScope.canViewMonitoring(session, teamId),   // izleme araması: + 7/24 operatörü (2026-10-04)
                incidentScope(session));
        return ResponseEntity.ok(Map.of("success", true, "data", rowsOf(hits)));
    }

    /**
     * Olay kaydı arama kapsamı — {@code incidents.view} yoksa null (olay araması yok); varsa olay listesinin kapsamı
     * ({@code IncidentController.incidentViewScope}): global görüntüleyici / 7/24 operatörü tümü, diğerleri görüş + paylaşım
     * kapsamındaki takımlar.
     */
    GlobalSearchService.IncidentScope incidentScope(HttpSession session) {
        if (permissionService == null || !permissionService.allows(session, "incidents.view", "view")) return null;
        return new GlobalSearchService.IncidentScope(
                SessionScope.seesAllMonitoring(session) ? null : SessionScope.viewTeamIdsWithGrants(session));
    }

    private static List<Map<String, Object>> rowsOf(List<GlobalSearchService.Hit> hits) {
        List<Map<String, Object>> rows = hits.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", h.kind()); m.put("id", h.id()); m.put("label", h.label()); m.put("sub", h.sub());
            m.put("team_id", h.teamId()); m.put("tab", h.tab()); m.put("params", h.params());
            // 2026-09-20: takım adı / grup / etiket / tier — palet satırında görünür
            m.put("team_name", h.teamName()); m.put("group_name", h.groupName()); m.put("tags", h.tags()); m.put("tier", h.tier());
            return m;
        }).toList();
        return rows;
    }
}
