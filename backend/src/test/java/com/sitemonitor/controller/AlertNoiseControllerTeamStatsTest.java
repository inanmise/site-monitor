package com.sitemonitor.controller;

import com.sitemonitor.service.AlertNoiseService;
import com.sitemonitor.service.AlertTeamStatsService;
import com.sitemonitor.service.PermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Hata taraması 2026-11-13: {@code GET /api/admin/alerts/team-stats} okuma hatasında 503 {@code DB_UNAVAILABLE} döner — eskiden
 * servis hatayı yutup BOŞ tabloyu {@code success} ile veriyordu; İstatistikler takım karnesi her takıma "0 alarm", Alarm Geçmişi
 * paneli "takım yok" yazıyordu. İstemciler başarısız yanıtı "okunamadı + yeniden dene" diye gösterir.
 */
class AlertNoiseControllerTeamStatsTest {

    private final AlertTeamStatsService teamStats = mock(AlertTeamStatsService.class);
    private final AlertNoiseController controller =
            new AlertNoiseController(mock(AlertNoiseService.class), teamStats, mock(PermissionService.class));

    @Test
    @DisplayName("okuma hatası → 503 DB_UNAVAILABLE, success=false (asla boş başarı)")
    void readFailureIs503() {
        when(teamStats.build(any(), anyBoolean())).thenThrow(new AlertTeamStatsService.UnavailableException(new IllegalStateException("db")));
        ResponseEntity<Map<String, Object>> res = controller.teamStats(new MockHttpSession());
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody()).containsEntry("success", false).containsEntry("code", "DB_UNAVAILABLE").containsKey("error");
        assertThat(res.getBody()).doesNotContainKey("data");
    }

    @Test
    @DisplayName("okununca 200 + veri (değişmedi)")
    void okUnchanged() {
        when(teamStats.build(any(), anyBoolean())).thenReturn(Map.of("teams", java.util.List.of(), "total_open", 0L));
        ResponseEntity<Map<String, Object>> res = controller.teamStats(new MockHttpSession());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("success", true).containsKey("data");
    }
}
