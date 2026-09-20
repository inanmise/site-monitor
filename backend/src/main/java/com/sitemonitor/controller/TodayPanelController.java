package com.sitemonitor.controller;

import com.sitemonitor.service.TodayPanelService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public ResponseEntity<Map<String, Object>> today(@org.springframework.web.bind.annotation.RequestParam(defaultValue = "false") boolean full,
                                                     HttpSession session) {
        List<Long> own = new ArrayList<>();
        List<Long> view = SessionScope.viewTeamIds(session);
        if (view != null) own.addAll(view);
        else if (session.getAttribute("teamId") instanceof Long tid) own.add(tid);
        // full=true: "Tümünü gör" pop-up'ı — kart başına tavan kalkar (2026-09-18).
        Map<String, Object> data = todayPanelService.build(teamId -> SessionScope.canView(session, teamId), own,
                full ? Integer.MAX_VALUE : com.sitemonitor.service.TodayPanelService.TOP);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** Bildirim kutusu (2026-09-12, #2): {@code GET /api/me/inbox}. Okundu durumu istemcide (anahtar bazlı). */
    @GetMapping("/inbox")
    public ResponseEntity<Map<String, Object>> inbox(HttpSession session,
                                                     @RequestParam(defaultValue = "current") String view,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "25") int size) {
        List<Long> own = new ArrayList<>();
        List<Long> viewIds = SessionScope.viewTeamIds(session);
        if (viewIds != null) own.addAll(viewIds);
        else if (session.getAttribute("teamId") instanceof Long tid) own.add(tid);
        // Geçmiş (2026-09-20): çözülmüş alarmlar 30 gün, sayfalı — güncel liste yalnız son 24 saati gösterir.
        if ("history".equals(view)) {
            var h = inboxService.history(teamId -> SessionScope.canView(session, teamId), page, size);
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("success", true);
            out.put("data", h.items().stream().map(TodayPanelController::inboxItem).toList());
            out.put("total", h.total()); out.put("page", h.page()); out.put("size", h.size()); out.put("total_pages", h.totalPages());
            return ResponseEntity.ok(out);
        }
        List<Map<String, Object>> items = inboxService.build(teamId -> SessionScope.canView(session, teamId), own).stream()
                .map(TodayPanelController::inboxItem).toList();
        return ResponseEntity.ok(Map.of("success", true, "data", items));
    }

    private static Map<String, Object> inboxItem(com.sitemonitor.service.InboxService.Item i) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("key", i.key()); m.put("kind", i.kind()); m.put("level", i.level()); m.put("title", i.title()); m.put("sub", i.sub());
        m.put("at", i.at()); m.put("tab", i.tab()); m.put("params", i.params());
        // 2026-09-20: takım, başlangıç/bitiş (süre arayüzde canlı), izlemeye git
        m.put("team_id", i.teamId()); m.put("team_name", i.teamName());
        m.put("started_at", i.startedAt()); m.put("ended_at", i.endedAt());
        m.put("monitor_tab", i.monitorTab()); m.put("monitor_params", i.monitorParams()); m.put("monitor_name", i.monitorName());
        return m;
    }
}
