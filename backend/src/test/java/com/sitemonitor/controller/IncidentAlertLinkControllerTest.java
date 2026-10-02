package com.sitemonitor.controller;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.HttpMetricsService;
import com.sitemonitor.service.IncidentAlertLinkService;
import com.sitemonitor.service.IncidentNotificationService;
import com.sitemonitor.service.IncidentService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.noc.NocCallLogService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Alarmdan olay kaydı açma (2026-10-01) — {@code alert_event_id} yazma kapısı ve alarm detayının bağlı-kayıt ucu.
 * GERÇEK {@link IncidentAlertLinkService} (alarm deposu + 7/24 okuma kapısı taklit): var olmayan alarm 400, görülemeyen
 * alarm 403 (kayıt OLUŞMAZ), görülen alarm 200 + alan kayda geçer; yanıtta {@code alert_event_id} döner.
 */
@WebMvcTest(IncidentController.class)
@Import(IncidentAlertLinkService.class)
class IncidentAlertLinkControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean IncidentService service;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean IncidentNotificationService notificationService;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;
    @MockitoBean AlertEventRepository alertRepo;
    @MockitoBean IncidentRecordRepository incidentRepo;
    @MockitoBean NocCallLogService nocCallLog;

    static final String BODY = "{\"title\":\"Ödeme · İçerik doğrulama\",\"occurred_at\":\"2026-10-01T06:00:00\","
            + "\"severity\":\"CRITICAL\",\"status\":\"OPEN\",\"category\":\"APPLICATION\",\"team_id\":5,"
            + "\"alert_event_id\":%s}";

    @BeforeEach
    void perms() {
        when(permissionService.allows(any(HttpSession.class), eq("incidents.manage"), eq("edit"))).thenReturn(true);
        when(permissionService.allows(any(HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(true);
        when(service.create(any(), any(), any(), any())).thenAnswer(inv -> {
            Map<String, Object> b = inv.getArgument(0);
            IncidentRecord r = record(1L, 5L);
            Object a = b.get("alert_event_id");
            r.setAlertEventId(a == null ? null : Long.valueOf(a.toString()));
            return r;
        });
    }

    @Test
    @DisplayName("create: görülebilen alarm → 200, alert_event_id kayda ve yanıta geçer, denetimde iz")
    void create_visibleAlarm_200() throws Exception {
        AlertEvent ev = alarm(77L);
        when(alertRepo.findById(77L)).thenReturn(Optional.of(ev));
        when(nocCallLog.canRead(any(HttpSession.class), same(ev))).thenReturn(true);

        mvc.perform(post("/api/incidents").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted("77")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alert_event_id").value(77));
        verify(service).create(argThat(b -> Long.valueOf(77).equals(Long.valueOf(String.valueOf(b.get("alert_event_id"))))),
                any(), any(), any());
        verify(auditService).recordAction(eq("INCIDENT_CREATE"), any(HttpSession.class),
                any(jakarta.servlet.http.HttpServletRequest.class), eq("INCIDENT"), eq("1"), contains("\"alert_event_id\":77"));
    }

    @Test
    @DisplayName("create: var olmayan alarm → 400 (TR/EN mesaj), kayıt YOK")
    void create_unknownAlarm_400() throws Exception {
        when(alertRepo.findById(999L)).thenReturn(Optional.empty());
        mvc.perform(post("/api/incidents").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted("999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("#999")));
        mvc.perform(post("/api/incidents").session(user(5L)).header("X-Lang", "en").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted("999")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Alarm not found: #999"));
        verify(service, never()).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName("create: sayı olmayan alarm kimliği → 400, kayıt YOK")
    void create_invalidAlarmId_400() throws Exception {
        mvc.perform(post("/api/incidents").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted("\"abc\"")))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(any(), any(), any(), any());
        verifyNoInteractions(alertRepo);
    }

    @Test
    @DisplayName("create: kullanıcının GÖREMEDİĞİ alarm → 403, kayıt YOK (başka takımın alarmına bağlanamaz)")
    void create_invisibleAlarm_403() throws Exception {
        AlertEvent ev = alarm(78L);
        when(alertRepo.findById(78L)).thenReturn(Optional.of(ev));
        when(nocCallLog.canRead(any(HttpSession.class), same(ev))).thenReturn(false);
        mvc.perform(post("/api/incidents").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted("78")))
                .andExpect(status().isForbidden());
        verify(service, never()).create(any(), any(), any(), any());
    }

    @Test
    @DisplayName("create: alert_event_id YOK / null → doğrulama yok, eski davranış aynen")
    void create_withoutAlarm_unchanged() throws Exception {
        mvc.perform(post("/api/incidents").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted("null")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alert_event_id").doesNotExist());
        verifyNoInteractions(alertRepo, nocCallLog);
    }

    @Test
    @DisplayName("update: DEĞİŞMEYEN bağ yeniden doğrulanmaz (alarm silinmiş/görülemez olsa da düzenleme kırılmaz); değişen bağ doğrulanır")
    void update_unchangedLinkNotRevalidated_changedLinkValidated() throws Exception {
        IncidentRecord cur = record(1L, 5L);
        cur.setAlertEventId(77L);
        when(service.get(1L)).thenReturn(cur);
        when(service.update(eq(1L), any(), any())).thenReturn(cur);

        mvc.perform(put("/api/incidents/1").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"MITIGATED\",\"alert_event_id\":77}"))
                .andExpect(status().isOk());
        verifyNoInteractions(alertRepo);

        AlertEvent other = alarm(80L);
        when(alertRepo.findById(80L)).thenReturn(Optional.of(other));
        when(nocCallLog.canRead(any(HttpSession.class), same(other))).thenReturn(false);
        mvc.perform(put("/api/incidents/1").session(user(5L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"MITIGATED\",\"alert_event_id\":80}"))
                .andExpect(status().isForbidden());
        verify(service, times(1)).update(eq(1L), any(), any());
    }

    @Test
    @DisplayName("GET /by-alert/{id}: tek sorgu, takım kapsamıyla; özet alanlar döner")
    void byAlert_scopedSummary() throws Exception {
        IncidentRecord r = record(12L, 5L);
        r.setAlertEventId(77L);
        when(incidentRepo.findLinkedToAlert(eq(77L), eq(true), eq(List.of(5L)))).thenReturn(List.of(r));

        mvc.perform(get("/api/incidents/by-alert/77").session(user(5L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(12))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"))
                .andExpect(jsonPath("$.data[0].title").value("Ödeme kesintisi"));
        verify(incidentRepo, times(1)).findLinkedToAlert(any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("GET /by-alert/{id}: global görücü kapsamsız sorgular; kapsamsız kullanıcı hiç sorgu atmaz; incidents.view yoksa 403")
    void byAlert_globalEmptyAndForbidden() throws Exception {
        when(incidentRepo.findLinkedToAlert(eq(77L), eq(false), any())).thenReturn(List.of());
        mvc.perform(get("/api/incidents/by-alert/77").session(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        verify(incidentRepo).findLinkedToAlert(eq(77L), eq(false), any());

        clearInvocations(incidentRepo);
        MockHttpSession noScope = user(5L);
        noScope.setAttribute("viewTeamIds", new java.util.ArrayList<Long>());
        mvc.perform(get("/api/incidents/by-alert/77").session(noScope))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        verifyNoInteractions(incidentRepo);

        when(permissionService.allows(any(HttpSession.class), eq("incidents.view"), eq("view"))).thenReturn(false);
        mvc.perform(get("/api/incidents/by-alert/77").session(user(5L))).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /{id}: yanıt alert_event_id taşır (olay detayının alarma geri bağlantısı)")
    void get_returnsAlertEventId() throws Exception {
        IncidentRecord r = record(3L, 5L);
        r.setAlertEventId(77L);
        when(service.get(3L)).thenReturn(r);
        mvc.perform(get("/api/incidents/3").session(user(5L)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.alert_event_id").value(77));
    }

    private static AlertEvent alarm(Long id) {
        AlertEvent ev = new AlertEvent();
        ev.setId(id);
        ev.setDomain("https://kw.example.com/");
        ev.setAlertType("KEYWORD");
        ev.setAlertLevel("CRITICAL");
        ev.setTeamId(5L);
        return ev;
    }

    private static IncidentRecord record(Long id, Long teamId) {
        IncidentRecord e = new IncidentRecord();
        e.setId(id);
        e.setTitle("Ödeme kesintisi");
        e.setOccurredAt("2026-10-01T06:00:00");
        e.setSeverity("CRITICAL");
        e.setStatus("OPEN");
        e.setCategory("APPLICATION");
        e.setTeamId(teamId);
        e.setSlaBreached(false);
        return e;
    }

    private static MockHttpSession user(Long teamId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "sre1");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(teamId)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<Long>());
        s.setAttribute("teamId", teamId);
        return s;
    }

    private static MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }
}
