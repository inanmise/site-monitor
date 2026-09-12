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

    @GetMapping("/today")
    public ResponseEntity<Map<String, Object>> today(HttpSession session) {
        List<Long> own = new ArrayList<>();
        List<Long> view = SessionScope.viewTeamIds(session);
        if (view != null) own.addAll(view);
        else if (session.getAttribute("teamId") instanceof Long tid) own.add(tid);
        Map<String, Object> data = todayPanelService.build(teamId -> SessionScope.canView(session, teamId), own);
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }
}
