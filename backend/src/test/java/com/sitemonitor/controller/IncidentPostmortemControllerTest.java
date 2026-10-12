package com.sitemonitor.controller;

import com.sitemonitor.model.IncidentActionItem;
import com.sitemonitor.model.IncidentPostmortem;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.IncidentService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.incident.IncidentPostmortemService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Postmortem + aksiyon uçları (2026-10-11): kapılar olay kaydıyla AYNI (görüntüleme izni + okuma kapsamı; yazma =
 * incidents.manage + kendi takım sınırı), aksiyon sahibi yalnız durumunu değiştirebilir, başkası 403; olaylar arası
 * listede kapsam + "mine" + sahip kimliği sunucuda. Adlar sahte.
 */
@WebMvcTest(IncidentPostmortemController.class)
class IncidentPostmortemControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean IncidentService incidents;
    @MockitoBean IncidentPostmortemService pm;
    @MockitoBean PermissionService permissions;
    @MockitoBean AuditService audit;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    IncidentRecord own;
    IncidentRecord foreign;

    @BeforeEach
    void setUp() {
        own = incident(1L, 5L);
        foreign = incident(2L, 9L);
        when(incidents.get(1L)).thenReturn(own);
        when(incidents.get(2L)).thenReturn(foreign);
        when(pm.postmortemView(any())).thenReturn(Map.of("exists", false, "status", "DRAFT"));
        when(pm.actions(anyLong())).thenReturn(List.of());
        when(pm.timeline(any(), anyBoolean(), any())).thenReturn(List.of());
    }

    private static IncidentRecord incident(long id, long team) {
        IncidentRecord r = new IncidentRecord();
        r.setId(id);
        r.setTitle("Olay " + id);
        r.setTeamId(team);
        r.setCreatedByTeamId(team);
        return r;
    }

    private static IncidentActionItem action(long id, long incidentId, Long owner) {
        IncidentActionItem a = new IncidentActionItem();
        a.setId(id);
        a.setIncidentId(incidentId);
        a.setTitle("İş " + id);
        a.setStatus("OPEN");
        a.setKind("PREVENT");
        a.setPriority("HIGH");
        a.setOwnerUserId(owner);
        return a;
    }

    private void grant(String resource, String action) {
        when(permissions.allows(any(HttpSession.class), eq(resource), eq(action))).thenReturn(true);
    }

    private static MockHttpSession session(long userId, Long team) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "kullanici" + userId);
        s.setAttribute("userId", userId);
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", team == null ? new ArrayList<Long>() : new ArrayList<>(List.of(team)));
        s.setAttribute("manageTeamIds", new ArrayList<Long>());
        return s;
    }

    @Test
    @DisplayName("okuma: izin yoksa 403; başka takımın olayı 403; kendi olayında rapor + aksiyon + çizelge + can_edit")
    void readGates() throws Exception {
        mvc.perform(get("/api/incidents/1/postmortem").session(session(3, 5L))).andExpect(status().isForbidden());
        grant("incidents.view", "view");
        mvc.perform(get("/api/incidents/2/postmortem").session(session(3, 5L))).andExpect(status().isForbidden());
        when(pm.actions(1L)).thenReturn(List.of(action(4, 1, 3L), action(5, 1, 8L)));
        mvc.perform(get("/api/incidents/1/postmortem").session(session(3, 5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postmortem.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.can_edit").value(false))   // yalnız okuma izni → düzenleme düğmeleri yok (2026-10-12)
                .andExpect(jsonPath("$.data.actions[0].mine").value(true))
                .andExpect(jsonPath("$.data.actions[1].mine").value(false))
                .andExpect(jsonPath("$.data.me").doesNotExist());
        verify(pm).timeline(eq(own), eq(false), any());   // alerts.read yok → alarm satırları istenmez
        grant("incidents.manage", "edit");
        mvc.perform(get("/api/incidents/1/postmortem").session(session(3, 5L)))
                .andExpect(jsonPath("$.data.can_edit").value(true));
    }

    @Test
    @DisplayName("rapor kaydı: incidents.manage + kendi takımı; başka takım 403, kayıt denetlenir")
    void saveGates() throws Exception {
        String body = "{\"summary\":\"Özet\",\"status\":\"PUBLISHED\"}";
        mvc.perform(put("/api/incidents/1/postmortem").session(session(3, 5L)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        grant("incidents.manage", "edit");
        mvc.perform(put("/api/incidents/2/postmortem").session(session(3, 5L)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        IncidentPostmortem saved = new IncidentPostmortem();
        saved.setStatus("PUBLISHED");
        saved.setVersion(1L);
        when(pm.save(eq(own), anyMap(), eq("kullanici3"))).thenReturn(saved);
        mvc.perform(put("/api/incidents/1/postmortem").session(session(3, 5L)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(audit).recordAction(eq("INCIDENT_POSTMORTEM_SAVE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("INCIDENT"), eq("1"), contains("PUBLISHED"));
    }

    @Test
    @DisplayName("aksiyon ekle: sahip kimliği çözülür (bean yoksa sayısal), başka takımın olayına eklenemez")
    void addAction() throws Exception {
        grant("incidents.manage", "edit");
        when(pm.createAction(eq(own), anyMap(), eq(7L), anyString())).thenReturn(action(9, 1, 7L));
        mvc.perform(post("/api/incidents/1/actions").session(session(3, 5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Alarm eşiği\",\"owner_user_id\":\"7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(9));
        verify(audit).recordAction(eq("INCIDENT_ACTION_ADD"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("INCIDENT"), eq("1"), anyString());
        mvc.perform(post("/api/incidents/2/actions").session(session(3, 5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("aksiyon güncelle: sahip (düzenleme yetkisi yok) yalnız-durum kipinde; ne sahip ne yazar 403; yazar tam")
    void updateAction() throws Exception {
        grant("incidents.view", "view");
        IncidentActionItem foreignAction = action(11, 2, 7L);
        when(pm.getAction(11L)).thenReturn(foreignAction);
        when(pm.updateAction(any(), anyMap(), any(), anyBoolean(), anyBoolean(), anyString())).thenAnswer(i -> i.getArgument(0));

        // Sahip: başka takımın olayı, incidents.manage yok → yalnız durum
        mvc.perform(put("/api/incidents/actions/11").session(session(7, 5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mine").value(true));
        verify(pm).updateAction(eq(foreignAction), anyMap(), isNull(), eq(false), eq(true), eq("kullanici7"));

        // Ne sahip ne yazar
        mvc.perform(put("/api/incidents/actions/11").session(session(8, 5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isForbidden());

        // Yazar (kendi takımı + manage): tam güncelleme, sahip değişimi çözülür
        grant("incidents.manage", "edit");
        IncidentActionItem ownAction = action(12, 1, null);
        when(pm.getAction(12L)).thenReturn(ownAction);
        mvc.perform(put("/api/incidents/actions/12").session(session(8, 5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Yeni\",\"owner_user_id\":\"7\"}"))
                .andExpect(status().isOk());
        verify(pm).updateAction(eq(ownAction), anyMap(), eq(7L), eq(true), eq(false), eq("kullanici8"));
        verify(audit, times(2)).recordAction(eq("INCIDENT_ACTION_UPDATE"), any(HttpSession.class), any(jakarta.servlet.http.HttpServletRequest.class), eq("INCIDENT"), anyString(), anyString());
    }

    @Test
    @DisplayName("aksiyon sil: yazma kapısı; başka takım 403")
    void deleteAction() throws Exception {
        grant("incidents.manage", "edit");
        when(pm.getAction(11L)).thenReturn(action(11, 2, 7L));
        when(pm.getAction(12L)).thenReturn(action(12, 1, null));
        mvc.perform(delete("/api/incidents/actions/11").session(session(7, 5L))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/incidents/actions/12").session(session(7, 5L))).andExpect(status().isOk());
        verify(pm).deleteAction(argThat(a -> a.getId() == 12L));
        verify(pm, never()).deleteAction(argThat(a -> a.getId() == 11L));
    }

    @Test
    @DisplayName("olaylar arası liste: görüntüleme izni; kapsam = görüş takımları, sahip = oturum kullanıcısı")
    void listActions() throws Exception {
        mvc.perform(get("/api/incidents/actions").session(session(7, 5L))).andExpect(status().isForbidden());
        grant("incidents.view", "view");
        when(pm.search(anyBoolean(), any(), any(), anyBoolean(), any(), anyBoolean(), any(), anyInt(), anyInt(), any()))
                .thenReturn(Map.of("items", List.of(), "total", 0));
        mvc.perform(get("/api/incidents/actions?status=open&mine=true&overdue=true&q=ödeme").session(session(7, 5L)))
                .andExpect(status().isOk());
        verify(pm).search(eq(true), eq(List.of(5L)), eq(7L), eq(true), eq("open"), eq(true), eq("ödeme"), eq(0), eq(50), any());
    }
}
