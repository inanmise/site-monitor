package com.sitemonitor.controller;

import com.sitemonitor.model.OnCallOverride;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Nöbet çizelgesi uçları (2026-10-11): okuma kapsamı, yazma = takımı yöneten, kişi kimliği çözümü, denetim. */
@WebMvcTest(OnCallController.class)
class OnCallControllerTest {

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
        when(onCall.view(any(), any())).thenReturn(Map.of("team_id", 5L, "enabled", true));
    }

    /** Takım 5'in üyesi; {@code manage} → 5'i yöneten müdür. */
    private static MockHttpSession member(boolean manage) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u5");
        s.setAttribute("systemRole", manage ? "ADMIN" : "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(5L)));
        if (manage) s.setAttribute("manageTeamIds", new ArrayList<>(List.of(5L)));
        return s;
    }

    private static MockHttpSession global() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    @Test
    @DisplayName("okuma: kendi takımı 200 (+ can_edit=false); başka takım 404")
    void readScope() throws Exception {
        mvc.perform(get("/api/oncall/teams/5").session(member(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.can_edit").value(false));
        mvc.perform(get("/api/oncall/teams/9").session(member(false))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("K11: silinmiş üyenin null kimliği (eski istemci geri yollar) atlanır; bilinmeyen dolu kimlik yine 400")
    void nullMemberIsSkipped() throws Exception {
        OnCallSchedule saved = new OnCallSchedule();
        saved.setEnabled(true); saved.setRotation("WEEKLY"); saved.setNotifyOncall(false);
        when(onCall.save(any(), anyBoolean(), any(), any(), anyList(), anyBoolean(), any())).thenReturn(saved);
        String body = "{\"enabled\":true,\"rotation\":\"WEEKLY\",\"anchor\":\"2026-10-05T09:00\",\"members\":[11,null,12]}";
        mvc.perform(put("/api/oncall/teams/5").session(global()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> members = ArgumentCaptor.forClass(List.class);
        verify(onCall).save(eq(5L), eq(true), eq("WEEKLY"), eq("2026-10-05T09:00"), members.capture(), anyBoolean(), eq("admin"));
        assertThat(members.getValue()).containsExactly(11L, 12L);

        String bad = "{\"enabled\":true,\"rotation\":\"WEEKLY\",\"anchor\":\"2026-10-05T09:00\",\"members\":[11,\"not-a-user\"]}";
        mvc.perform(put("/api/oncall/teams/5").session(global()).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("yazma: üye 403, yönetici 200 + kimlikler çözülür + denetim")
    void writeRequiresManage() throws Exception {
        String body = "{\"enabled\":true,\"rotation\":\"WEEKLY\",\"anchor\":\"2026-10-05T09:00\",\"members\":[11,12],\"notify_oncall\":true}";
        mvc.perform(put("/api/oncall/teams/5").session(member(false)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        verify(onCall, never()).save(any(), anyBoolean(), any(), any(), anyList(), anyBoolean(), any());

        OnCallSchedule saved = new OnCallSchedule();
        saved.setEnabled(true); saved.setRotation("WEEKLY"); saved.setNotifyOncall(true);
        when(onCall.save(any(), anyBoolean(), any(), any(), anyList(), anyBoolean(), any())).thenReturn(saved);
        mvc.perform(put("/api/oncall/teams/5").session(global()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.can_edit").value(true));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> members = ArgumentCaptor.forClass(List.class);
        verify(onCall).save(eq(5L), eq(true), eq("WEEKLY"), eq("2026-10-05T09:00"), members.capture(), eq(true), eq("admin"));
        assertThat(members.getValue()).containsExactly(11L, 12L);
        verify(auditService).recordAction(eq("ONCALL_SCHEDULE_SAVE"), any(jakarta.servlet.http.HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("TEAM"), eq("5"), anyString());
    }

    @Test
    @DisplayName("bilinmeyen takım 404; yer değiştirme ekle / sil yönetim kapısından geçer")
    void overrides() throws Exception {
        when(teams.existsById(99L)).thenReturn(false);
        mvc.perform(get("/api/oncall/teams/99").session(global())).andExpect(status().isNotFound());

        OnCallOverride o = new OnCallOverride();
        o.setId(3L); o.setTeamId(5L); o.setStartsAt("2026-10-10T10:00:00"); o.setEndsAt("2026-10-11T10:00:00");
        when(onCall.addOverride(any(), any(), any(), any(), any(), any())).thenReturn(o);
        mvc.perform(post("/api/oncall/teams/5/overrides").session(global()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\":11,\"starts_at\":\"2026-10-10T10:00:00Z\",\"ends_at\":\"2026-10-11T10:00:00Z\"}"))
                .andExpect(status().isOk());
        verify(onCall).addOverride(eq(5L), eq(11L), eq("2026-10-10T10:00:00Z"), eq("2026-10-11T10:00:00Z"), any(), eq("admin"));

        when(onCall.findOverride(3L)).thenReturn(o);
        mvc.perform(delete("/api/oncall/overrides/3").session(member(false))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/oncall/overrides/3").session(global())).andExpect(status().isOk());
        verify(onCall).deleteOverride(o);
    }

    @Test
    @DisplayName("şu anki nöbetçiler: kapsam dışı takımlar sessizce düşer")
    void current() throws Exception {
        when(onCall.currentFor(anyCollection())).thenReturn(Map.of(5L,
                new OnCallService.OnCallPerson(5L, 11L, "N11", "Kişi 11", "k@example.com", true, false, "2026-10-12T06:00:00")));
        mvc.perform(get("/api/oncall/current?teamIds=5,9,abc").session(member(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].team_id").value(5))
                .andExpect(jsonPath("$.data[0].name").value("Kişi 11"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(onCall).currentFor(ids.capture());
        assertThat(ids.getValue()).containsExactly(5L);
    }
}
