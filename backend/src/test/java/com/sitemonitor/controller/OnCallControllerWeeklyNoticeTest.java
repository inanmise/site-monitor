package com.sitemonitor.controller;

import com.sitemonitor.model.OnCallSchedule;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.oncall.OnCallService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Haftalık nöbet bildirimi uçları (2026-11-15): yalnız-bildirim gövdesi çizelgeye dokunmaz (hedefli kayıt + denetim farkı),
 * tür hataları alan adıyla 400, çizelge + bildirim tek kayıt, başka takımın yöneticisi 403 / okuma 404, değişiklik yoksa
 * denetim yok.
 */
@WebMvcTest(OnCallController.class)
class OnCallControllerWeeklyNoticeTest {

    @Autowired MockMvc mvc;
    @MockitoBean OnCallService onCall;
    @MockitoBean TeamRepository teams;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    @BeforeEach
    void setUp() {
        when(teams.existsById(any())).thenReturn(true);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("team_id", 5L);
        view.put("weekly_notice", Map.of("enabled", true, "mail", true, "push", true, "day", 1, "time", "08:30"));
        view.put("weekly_notice_next", "2026-11-16T05:30:00");
        when(onCall.view(any(), any())).thenReturn(view);
    }

    /** Takım 5'i yöneten kapsamlı müdür. */
    private static MockHttpSession manager5() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "mudur5");
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(5L)));
        s.setAttribute("manageTeamIds", new ArrayList<>(List.of(5L)));
        return s;
    }

    private static OnCallSchedule saved(boolean noticeOn) {
        OnCallSchedule s = new OnCallSchedule();
        s.setTeamId(5L);
        s.setEnabled(true);
        s.setRotation("WEEKLY");
        s.setNotifyOncall(false);
        s.setWeeklyNoticeEnabled(noticeOn);
        s.setWeeklyNoticeDay(3);
        return s;
    }

    @Test
    @DisplayName("görünüm haftalık bildirim alanlarını taşır")
    void viewCarriesNotice() throws Exception {
        mvc.perform(get("/api/oncall/teams/5").session(manager5()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weekly_notice.time").value("08:30"))
                .andExpect(jsonPath("$.data.weekly_notice_next").value("2026-11-16T05:30:00"));
    }

    @Test
    @DisplayName("yalnız weekly_notice gövdesi: hedefli kayıt, çizelge kaydı YOK, denetim farkı yazılır")
    void noticeOnlyBody() throws Exception {
        when(onCall.weeklyNoticeSnapshot(5L)).thenReturn(Map.of("weeklyNoticeEnabled", false, "weeklyNoticeDay", 1));
        when(onCall.saveWeeklyNotice(eq(5L), any(), eq("mudur5"))).thenReturn(new OnCallService.WeeklyNoticeSave(saved(true), true));
        mvc.perform(put("/api/oncall/teams/5").session(manager5()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekly_notice\":{\"enabled\":true,\"day\":3}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.can_edit").value(true));
        ArgumentCaptor<OnCallService.WeeklyNoticePatch> p = ArgumentCaptor.forClass(OnCallService.WeeklyNoticePatch.class);
        verify(onCall).saveWeeklyNotice(eq(5L), p.capture(), eq("mudur5"));
        assertThat(p.getValue()).isEqualTo(new OnCallService.WeeklyNoticePatch(true, null, null, 3, null));
        verify(onCall, never()).save(any(), anyBoolean(), any(), any(), anyList(), anyBoolean(), any());
        ArgumentCaptor<String> changes = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordAction(eq("ONCALL_SCHEDULE_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("5"), anyString(), changes.capture());
        assertThat(changes.getValue()).contains("weeklyNoticeEnabled").contains("weeklyNoticeDay");
    }

    @Test
    @DisplayName("değişiklik yoksa denetim yazılmaz")
    void noChangeNoAudit() throws Exception {
        when(onCall.saveWeeklyNotice(eq(5L), any(), any())).thenReturn(new OnCallService.WeeklyNoticeSave(saved(true), false));
        mvc.perform(put("/api/oncall/teams/5").session(manager5()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekly_notice\":{\"enabled\":true}}"))
                .andExpect(status().isOk());
        verify(auditService, never()).recordAction(anyString(), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString(), any());
        verify(auditService, never()).recordAction(anyString(), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("tür hataları alan adıyla 400; servis çağrılmaz")
    void typeErrors() throws Exception {
        String[][] cases = {
                {"{\"weekly_notice\":{\"enabled\":\"evet\"}}", "weekly_notice.enabled"},
                {"{\"weekly_notice\":{\"mail\":1}}", "weekly_notice.mail"},
                {"{\"weekly_notice\":{\"day\":\"pazartesi\"}}", "weekly_notice.day"},
                {"{\"weekly_notice\":{\"day\":1.5}}", "weekly_notice.day"},
                {"{\"weekly_notice\":{\"time\":830}}", "weekly_notice.time"},
                {"{\"weekly_notice\":true}", "weekly_notice.enabled"},
        };
        for (String[] c : cases) {
            mvc.perform(put("/api/oncall/teams/5").session(manager5()).contentType(MediaType.APPLICATION_JSON).content(c[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value(c[1]));
        }
        verify(onCall, never()).saveWeeklyNotice(any(), any(), any());
    }

    @Test
    @DisplayName("çizelge + weekly_notice: tek kayıt (8 değişkenli), denetim farkıyla")
    void combinedBody() throws Exception {
        when(onCall.save(any(), anyBoolean(), any(), any(), anyList(), anyBoolean(), any(), any())).thenReturn(saved(true));
        String body = "{\"enabled\":true,\"rotation\":\"WEEKLY\",\"anchor\":\"2026-11-02T08:30\",\"members\":[11],"
                + "\"notify_oncall\":false,\"weekly_notice\":{\"enabled\":true,\"push\":false,\"time\":\"09:15\"}}";
        mvc.perform(put("/api/oncall/teams/5").session(manager5()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(onCall).save(eq(5L), eq(true), eq("WEEKLY"), eq("2026-11-02T08:30"), anyList(), eq(false),
                eq(new OnCallService.WeeklyNoticePatch(true, null, false, null, "09:15")), eq("mudur5"));
        verify(onCall, never()).saveWeeklyNotice(any(), any(), any());
    }

    @Test
    @DisplayName("başka takımın müdürü: yazma 403 (servis çağrılmaz), okuma 404")
    void otherTeamManager() throws Exception {
        mvc.perform(put("/api/oncall/teams/9").session(manager5()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekly_notice\":{\"enabled\":true}}"))
                .andExpect(status().isForbidden());
        verify(onCall, never()).saveWeeklyNotice(any(), any(), any());
        mvc.perform(get("/api/oncall/teams/9").session(manager5())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("takım üyesi (yöneticisi değil) bildirimi açamaz: 403")
    void memberCannotWrite() throws Exception {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u5");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(5L)));
        mvc.perform(put("/api/oncall/teams/5").session(s).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weekly_notice\":{\"enabled\":true}}"))
                .andExpect(status().isForbidden());
        verify(onCall, never()).saveWeeklyNotice(any(), any(), any());
    }
}
