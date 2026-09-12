package com.sitemonitor.controller;

import com.sitemonitor.service.TodayPanelService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Sizin için — bugün" (2026-09-12, #3): {@code GET /api/me/today}. Kapsam oturumdan: görüntüleme takımları
 * ({@link SessionScope#canView}); haftalık rapor sorumluluğu = kullanıcının kendi takımları (global
 * görüntüleyicide yalnız oturumdaki takım, yoksa boş — "tüm takımların raporu eksik" gürültüsü yok).
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class TodayPanelController {

    private final TodayPanelService todayPanelService;
    private final com.sitemonitor.service.InboxService inboxService;

    @GetMapping("/today")
    public ResponseEntity<Map<String, Object>> today(HttpSession session) {
        List<Long> own = new ArrayList<>();
        List<Long> view = SessionScope.viewTeamIds(session);
        if (view != null) own.addAll(view);
        else if (session.getAttribute("teamId") instanceof Long tid) own.add(tid);
        Map<String, Object> data = todayPanelService.build(teamId -> SessionScope.canView(session, teamId), own);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** Bildirim kutusu (2026-09-12, #2): {@code GET /api/me/inbox}. Okundu durumu istemcide (anahtar bazlı). */
    @GetMapping("/inbox")
    public ResponseEntity<Map<String, Object>> inbox(HttpSession session) {
        List<Long> own = new ArrayList<>();
        List<Long> view = SessionScope.viewTeamIds(session);
        if (view != null) own.addAll(view);
        else if (session.getAttribute("teamId") instanceof Long tid) own.add(tid);
        List<Map<String, Object>> items = inboxService.build(teamId -> SessionScope.canView(session, teamId), own).stream().map(i -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("key", i.key()); m.put("kind", i.kind()); m.put("level", i.level()); m.put("title", i.title()); m.put("sub", i.sub());
            m.put("at", i.at()); m.put("tab", i.tab()); m.put("params", i.params());
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of("success", true, "data", items));
    }
}
