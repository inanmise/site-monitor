package com.sitemonitor.controller;

import com.sitemonitor.model.MonitorNote;
import com.sitemonitor.repository.MonitorGuideRepository;
import com.sitemonitor.repository.MonitorNoteRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * K5 / UIM-13 (2026-10-22): not satırları {@code can_modify} taşır — düzenle / sil kapısıyla AYNI yüklem (yazma izni ∧
 * yazar-ve-takımı-çalıştırabilir ∨ global admin ∨ takım yöneticisi). Ret metinleri arayüz dilinde ({@code Msg.t}).
 */
@WebMvcTest(MonitorNotesController.class)
@org.springframework.context.annotation.Import(com.sitemonitor.service.MonitorTargetTeams.class)
class MonitorNotesModifyFlagTest {

    @Autowired MockMvc mvc;

    @MockitoBean MonitorGuideRepository guideRepo;
    @MockitoBean MonitorNoteRepository noteRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean com.sitemonitor.repository.HttpMonitorRepository httpRepo;
    @MockitoBean com.sitemonitor.repository.KeywordMonitorRepository keywordRepo;
    @MockitoBean com.sitemonitor.repository.PageMonitorRepository pageRepo;
    @MockitoBean com.sitemonitor.repository.PageSpeedMonitorRepository pageSpeedRepo;
    @MockitoBean com.sitemonitor.repository.PingMonitorRepository pingRepo;
    @MockitoBean com.sitemonitor.repository.PortMonitorRepository portRepo;
    @MockitoBean com.sitemonitor.repository.DnsMonitorRepository dnsRepo;
    @MockitoBean com.sitemonitor.repository.DomainMonitorRepository domainRepo;
    @MockitoBean com.sitemonitor.repository.ScriptedMonitorRepository scriptedRepo;
    @MockitoBean com.sitemonitor.repository.CertificateInventoryRepository inventoryRepo;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @BeforeEach
    void setUp() {
        com.sitemonitor.model.PingMonitor m = new com.sitemonitor.model.PingMonitor();
        m.setHost("1.2.3.4");
        m.setTeamId(1L);
        when(pingRepo.findByHost("1.2.3.4")).thenReturn(List.of(m));
        when(guideRepo.findByMonitorTypeAndTarget("PING", "1.2.3.4")).thenReturn(Optional.empty());
        when(noteRepo.findByMonitorTypeAndTargetAndDeletedAtIsNullOrderByCreatedAtDesc("PING", "1.2.3.4"))
                .thenReturn(List.of(note(1L, 1L, "ben"), note(2L, 1L, "meslektas"), note(3L, null, "eski")));
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("monitoring.crud"), eq("edit"))).thenReturn(true);
    }

    private static MonitorNote note(long id, Long teamId, String author) {
        MonitorNote n = new MonitorNote();
        n.setId(id);
        n.setMonitorType("PING");
        n.setTarget("1.2.3.4");
        n.setProblem("p-" + id);
        n.setTeamId(teamId);
        n.setAuthorUsername(author);
        return n;
    }

    private static MockHttpSession member(String role, boolean manager) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "ben");
        s.setAttribute("systemRole", role);
        s.setAttribute("teamId", 1L);
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(1L)));
        s.setAttribute("memberTeamIds", new ArrayList<>(List.of(1L)));
        s.setAttribute("manageTeamIds", manager ? new ArrayList<>(List.of(1L)) : new ArrayList<Long>());
        return s;
    }

    @Test
    @DisplayName("USER üye: kendi notu can_modify=true, meslektaşının notu ve takımsız eski not false; alanlar snake_case kalır")
    void user_ownNoteOnly() throws Exception {
        mvc.perform(get("/api/monitoring/notes?type=PING&target=1.2.3.4").session(member("USER", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes.length()").value(3))
                .andExpect(jsonPath("$.data.notes[0].problem").value("p-1"))
                .andExpect(jsonPath("$.data.notes[0].author_username").value("ben"))
                .andExpect(jsonPath("$.data.notes[0].can_modify").value(true))
                .andExpect(jsonPath("$.data.notes[1].can_modify").value(false))
                .andExpect(jsonPath("$.data.notes[2].can_modify").value(false));
    }

    @Test
    @DisplayName("Takım yöneticisi: takımının tüm notları true; takımsız eski not yalnız yazar / global admin → false")
    void manager_allTeamNotes() throws Exception {
        mvc.perform(get("/api/monitoring/notes?type=PING&target=1.2.3.4").session(member("TEAM_ADMIN", true)))
                .andExpect(jsonPath("$.data.notes[0].can_modify").value(true))
                .andExpect(jsonPath("$.data.notes[1].can_modify").value(true))
                .andExpect(jsonPath("$.data.notes[2].can_modify").value(false));
    }

    @Test
    @DisplayName("Yazma izni yok (AUDIT / salt okuma): hiçbir satır düzenlenemez — kendi notu bile")
    void noWritePermission_allFalse() throws Exception {
        when(permissionService.allows(any(jakarta.servlet.http.HttpSession.class), eq("monitoring.crud"), eq("edit"))).thenReturn(false);
        mvc.perform(get("/api/monitoring/notes?type=PING&target=1.2.3.4").session(member("AUDIT", false)))
                .andExpect(jsonPath("$.data.notes[0].can_modify").value(false))
                .andExpect(jsonPath("$.data.notes[1].can_modify").value(false))
                .andExpect(jsonPath("$.data.can_edit_guide").value(false));
    }

    @Test
    @DisplayName("Global admin: hepsi true (takımsız eski not dahil)")
    void globalAdmin_allTrue() throws Exception {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "root");
        s.setAttribute("systemRole", "ADMIN");
        mvc.perform(get("/api/monitoring/notes?type=PING&target=1.2.3.4").session(s))
                .andExpect(jsonPath("$.data.notes[0].can_modify").value(true))
                .andExpect(jsonPath("$.data.notes[2].can_modify").value(true));
    }

    @Test
    @DisplayName("Ret metni arayüz dilinde: EN isteğe İngilizce 'doesn't belong to your team'")
    void refusalIsLocalized() throws Exception {
        when(noteRepo.findById(2L)).thenReturn(Optional.of(note(2L, 1L, "meslektas")));
        mvc.perform(delete("/api/monitoring/notes/2").header("X-Lang", "en").session(member("USER", false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error", containsString("doesn't belong to your team")));
    }
}
