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

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam(defaultValue = "") String q, HttpSession session) {
        List<GlobalSearchService.Hit> hits = searchService.search(q, teamId -> SessionScope.canView(session, teamId));
        List<Map<String, Object>> rows = hits.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", h.kind()); m.put("id", h.id()); m.put("label", h.label()); m.put("sub", h.sub());
            m.put("team_id", h.teamId()); m.put("tab", h.tab()); m.put("params", h.params());
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of("success", true, "data", rows));
    }
}
