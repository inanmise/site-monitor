package com.sitemonitor.controller;

import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.activity.ActivityDetailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@code GET /api/activity/{id}/detail} (2026-11-05, aktivite ayrıntı sayfası): kapı {@code GET /{id}} ile AYNI — görüş
 * kapsamı dışındaki satır ve olmayan satır 404 (varlık sızdırmaz, ayrıntı servisi hiç çağrılmaz); görülebilen satırda servis
 * yanıtı zarfla döner. {@code /{id}} yanıtı değişmedi (yeni alanlar yalnız {@code /detail}'de).
 */
@WebMvcTest(ActivityController.class)
class ActivityControllerDetailTest {

    @Autowired MockMvc mvc;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.AuditService auditService;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;
    @MockitoBean ActivityLogRepository repo;
    @MockitoBean ActivityDetailService detailService;
    // be2 servisleri (liste / zaman çizelgesi / dışa aktarım / kök neden) — denetleyicinin yapıcısı ister
    @MockitoBean com.sitemonitor.service.activity.ActivityQueryService queries;
    @MockitoBean com.sitemonitor.service.activity.ActivityTimelineService timelines;
    @MockitoBean com.sitemonitor.service.activity.ActivityExportService exports;
    @MockitoBean com.sitemonitor.service.activity.ActivityRelatedService related;

    private static MockHttpSession scopedUser(Long... teams) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(teams)));
        return s;
    }

    private static ActivityLog row(Long id, Long teamId) {
        ActivityLog a = new ActivityLog();
        a.setId(id);
        a.setTeamId(teamId);
        a.setMonitorType("PORT");
        a.setAction("SCHEDULED_CHECK");
        a.setActivityTime("2026-11-05T10:00:00");
        a.setCheckId(55L);
        a.setAlertEventId(77L);
        return a;
    }

    @Test
    @DisplayName("Başka takımın satırı → 404 (varlık sızmaz), ayrıntı servisi çağrılmaz; olmayan satır da 404")
    void otherTeam_404_serviceNotCalled() throws Exception {
        when(repo.findById(1L)).thenReturn(Optional.of(row(1L, 2L)));
        mvc.perform(get("/api/activity/1/detail").session(scopedUser(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        when(repo.findById(2L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/activity/2/detail").session(scopedUser(1L))).andExpect(status().isNotFound());
        verifyNoInteractions(detailService);
    }

    @Test
    @DisplayName("Görülebilen satır → servis yanıtı zarfla (success + bölümler + timestamp)")
    void visible_delegatesToService() throws Exception {
        ActivityLog a = row(3L, 1L);
        when(repo.findById(3L)).thenReturn(Optional.of(a));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", Map.of("id", 3, "check_id", 55));
        body.put("check", null);
        body.put("alarm", Map.of("id", 77, "match", "exact"));
        when(detailService.detail(eq(a), any())).thenReturn(body);

        mvc.perform(get("/api/activity/3/detail").session(scopedUser(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.check_id").value(55))
                .andExpect(jsonPath("$.alarm.match").value("exact"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("GET /{id} yanıtı BİREBİR: yeni bağlantı alanları yalnız /detail'de")
    void plainDetail_unchanged() throws Exception {
        when(repo.findById(4L)).thenReturn(Optional.of(row(4L, 1L)));
        mvc.perform(get("/api/activity/4").session(scopedUser(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(4))
                .andExpect(jsonPath("$.data.check_id").doesNotExist())
                .andExpect(jsonPath("$.data.alert_event_id").doesNotExist());
    }
}
