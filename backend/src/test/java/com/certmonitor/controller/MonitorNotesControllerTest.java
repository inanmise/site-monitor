package com.certmonitor.controller;

import com.certmonitor.model.MonitorGuide;
import com.certmonitor.model.MonitorNote;
import com.certmonitor.repository.MonitorGuideRepository;
import com.certmonitor.repository.MonitorNoteRepository;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.PermissionService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MonitorNotesController.class)
class MonitorNotesControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean MonitorGuideRepository guideRepo;
    @MockitoBean MonitorNoteRepository noteRepo;
    @MockitoBean PermissionService permissionService;   // require(...) mock → no-op
    @MockitoBean AuditService auditService;

    // Web-context altyapısı (interceptor/filter) için gerekli mock'lar
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.certmonitor.service.HttpMetricsService httpMetricsService;

    private MockHttpSession session(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        return s;
    }

    @Test
    @DisplayName("GET /notes: ADMIN → 200, guide+notes döner")
    void get_asAdmin_returns200() throws Exception {
        when(guideRepo.findByMonitorTypeAndTarget("PING", "1.2.3.4")).thenReturn(Optional.empty());
        when(noteRepo.findByMonitorTypeAndTargetAndDeletedAtIsNullOrderByCreatedAtDesc("PING", "1.2.3.4"))
                .thenReturn(List.of());
        mvc.perform(get("/api/monitoring/notes?type=PING&target=1.2.3.4").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("GET /notes: geçersiz tip → 400")
    void get_invalidType_returns400() throws Exception {
        mvc.perform(get("/api/monitoring/notes?type=FOO&target=x").session(session("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /notes: geçerli → 200, not kaydedilir")
    void addNote_valid_returns200() throws Exception {
        when(noteRepo.save(any(MonitorNote.class)))
                .thenAnswer(a -> { MonitorNote n = a.getArgument(0); n.setId(7L); return n; });
        mvc.perform(post("/api/monitoring/notes").session(session("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"KEYWORD\",\"target\":\"https://x\",\"problem\":\"500 hata\",\"action_taken\":\"pod restart\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.problem").value("500 hata"));
    }

    @Test
    @DisplayName("POST /notes: problem boş → 400")
    void addNote_blankProblem_returns400() throws Exception {
        mvc.perform(post("/api/monitoring/notes").session(session("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"PING\",\"target\":\"1.2.3.4\",\"problem\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /guide: upsert → 200")
    void saveGuide_returns200() throws Exception {
        when(guideRepo.findByMonitorTypeAndTarget("PING", "1.2.3.4")).thenReturn(Optional.empty());
        when(guideRepo.save(any(MonitorGuide.class)))
                .thenAnswer(a -> { MonitorGuide g = a.getArgument(0); g.setId(3L); return g; });
        mvc.perform(put("/api/monitoring/notes/guide").session(session("ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"PING\",\"target\":\"1.2.3.4\",\"guide\":\"# Rehber\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.guide").value("# Rehber"));
    }

    @Test
    @DisplayName("DELETE /notes/{id}: notu ekleyen (USER) → 200 soft-delete")
    void deleteNote_author_returns200() throws Exception {
        MonitorNote n = new MonitorNote();
        n.setId(9L); n.setMonitorType("PING"); n.setTarget("1.2.3.4"); n.setProblem("x");
        n.setAuthorUsername("u");   // session username = "u"
        when(noteRepo.findById(9L)).thenReturn(Optional.of(n));
        when(noteRepo.save(any(MonitorNote.class))).thenAnswer(a -> a.getArgument(0));
        mvc.perform(delete("/api/monitoring/notes/9").session(session("USER")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /notes/{id}: yazar değil + USER → 403")
    void deleteNote_notAuthorUser_returns403() throws Exception {
        MonitorNote n = new MonitorNote();
        n.setId(9L); n.setAuthorUsername("someone-else");
        when(noteRepo.findById(9L)).thenReturn(Optional.of(n));
        mvc.perform(delete("/api/monitoring/notes/9").session(session("USER")))
                .andExpect(status().isForbidden());
    }
}
