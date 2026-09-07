package com.sitemonitor.controller;

import com.sitemonitor.model.MonitorGuide;
import com.sitemonitor.model.MonitorNote;
import com.sitemonitor.repository.MonitorGuideRepository;
import com.sitemonitor.repository.MonitorNoteRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
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
import static org.assertj.core.api.Assertions.assertThat;
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
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

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
    @DisplayName("SOZLESME: katalogdaki HER izleme turu Notlar sekmesinde gecerli (cert haric)")
    void everyCatalogType_isAcceptedByNotes() throws Exception {
        // Tip listesi ELLE yaziliydi ve hicbir kapi ona bakmiyordu: yeni bir izleme turu
        // eklendiginde o turun Notlar sekmesi sessizce "Gecersiz izleme tipi" (400) donuyordu.
        // Gercekten yasandi (PAGE, 2026-08-03). Liste artik MonitorTypeCatalog'dan turetiliyor;
        // bu kapi da UCTAN UCA dogruluyor — katalog buyudugunde burasi da buyumus olmali.
        java.util.List<String> rejected = new java.util.ArrayList<>();
        for (String type : com.sitemonitor.service.MonitorTypeCatalog.ORDER) {
            if (MonitorNotesController.NOTE_EXCLUDED_TYPES.contains(type)) continue;
            String upper = type.toUpperCase(java.util.Locale.ROOT);
            when(guideRepo.findByMonitorTypeAndTarget(upper, "hedef")).thenReturn(Optional.empty());
            when(noteRepo.findByMonitorTypeAndTargetAndDeletedAtIsNullOrderByCreatedAtDesc(upper, "hedef"))
                    .thenReturn(List.of());

            int status = mvc.perform(get("/api/monitoring/notes?type=" + upper + "&target=hedef")
                            .session(session("ADMIN")))
                    .andReturn().getResponse().getStatus();
            if (status != 200) rejected.add(upper + " → " + status);
        }
        org.assertj.core.api.Assertions.assertThat(rejected)
                .as("Notlar sekmesinin reddettigi katalog turleri")
                .isEmpty();
    }

    @Test
    @DisplayName("cert BILEREK disarida — sertifikalarin kendi not yuzeyi var")
    void certType_isRejected_deliberately() throws Exception {
        // Muafiyetin KASITLI oldugunu pinler: biri cert'i listeye eklerse bu test kirilir ve
        // karari bilerek vermek zorunda kalir.
        assertThat(MonitorNotesController.NOTE_EXCLUDED_TYPES).containsExactly("cert");
        mvc.perform(get("/api/monitoring/notes?type=CERT&target=x").session(session("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /notes: geçersiz tip → 400")
    void get_invalidType_returns400() throws Exception {
        mvc.perform(get("/api/monitoring/notes?type=FOO&target=x").session(session("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /notes: PAGE tipi geçerli → 200 (Sayfa Bütünlüğü Notlar sekmesi regresyonu)")
    void get_pageType_returns200() throws Exception {
        when(guideRepo.findByMonitorTypeAndTarget("PAGE", "https://x")).thenReturn(Optional.empty());
        when(noteRepo.findByMonitorTypeAndTargetAndDeletedAtIsNullOrderByCreatedAtDesc("PAGE", "https://x"))
                .thenReturn(List.of());
        mvc.perform(get("/api/monitoring/notes?type=PAGE&target=https://x").session(session("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
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

    // ── Takım izolasyonu ─────────────────────────────────────────────
    //
    // Not içeriği operasyoneldir ("Sorun / Yapılan işlem / Kök neden / Bakılacak yerler") — başka
    // bir takımın iç altyapı bilgisi. Kayıt oluşturulurken takım ZATEN damgalanıyordu ama hiçbir
    // okuma/yazma yolunda kullanılmıyordu: okuma tamamen kapsamsızdı, yazma kapısı ise yalnız ROL
    // DİZESİNE bakıyordu ("TEAM_ADMIN".equals(role)) — hangi takım olduğuna değil.

    /** Takım kapsamlı oturum: görüş ve yönetim yalnız verilen takımda. */
    private MockHttpSession teamSession(String role, Long teamId) {
        MockHttpSession s = session(role);
        s.setAttribute("viewTeamIds", List.of(teamId));
        s.setAttribute("manageTeamIds", List.of(teamId));
        s.setAttribute("teamId", teamId);
        return s;
    }

    private MonitorNote note(long id, Long teamId, String author) {
        MonitorNote n = new MonitorNote();
        n.setId(id); n.setMonitorType("PING"); n.setTarget("1.2.3.4");
        n.setProblem("p-" + id); n.setTeamId(teamId); n.setAuthorUsername(author);
        return n;
    }

    @Test
    @DisplayName("GET /notes: başka takımın notu listeden elenir, kendi takımınınki kalır")
    void get_otherTeamNote_filteredOut() throws Exception {
        when(guideRepo.findByMonitorTypeAndTarget("PING", "1.2.3.4")).thenReturn(Optional.empty());
        when(noteRepo.findByMonitorTypeAndTargetAndDeletedAtIsNullOrderByCreatedAtDesc("PING", "1.2.3.4"))
                .thenReturn(List.of(note(1L, 1L, "a"), note(2L, 2L, "b")));

        mvc.perform(get("/api/monitoring/notes?type=PING&target=1.2.3.4")
                        .session(teamSession("USER", 1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes.length()").value(1))
                .andExpect(jsonPath("$.data.notes[0].problem").value("p-1"));
    }

    @Test
    @DisplayName("GET /notes: takımsız (eski) not görünür kalır — mevcut içerik sessizce yok olmaz")
    void get_legacyNoteWithoutTeam_stillVisible() throws Exception {
        when(guideRepo.findByMonitorTypeAndTarget("PING", "1.2.3.4")).thenReturn(Optional.empty());
        when(noteRepo.findByMonitorTypeAndTargetAndDeletedAtIsNullOrderByCreatedAtDesc("PING", "1.2.3.4"))
                .thenReturn(List.of(note(1L, null, "a")));

        mvc.perform(get("/api/monitoring/notes?type=PING&target=1.2.3.4")
                        .session(teamSession("USER", 1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notes.length()").value(1));
    }

    @Test
    @DisplayName("DELETE /notes/{id}: A takımı yöneticisi B takımının notunu SİLEMEZ -> 403")
    void deleteNote_otherTeamAdmin_returns403() throws Exception {
        when(noteRepo.findById(9L)).thenReturn(Optional.of(note(9L, 2L, "b")));

        mvc.perform(delete("/api/monitoring/notes/9").session(teamSession("TEAM_ADMIN", 1L)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /notes/{id}: A takımı yöneticisi B takımının notunu DÜZENLEYEMEZ -> 403")
    void updateNote_otherTeamAdmin_returns403() throws Exception {
        when(noteRepo.findById(9L)).thenReturn(Optional.of(note(9L, 2L, "b")));

        mvc.perform(put("/api/monitoring/notes/9").session(teamSession("TEAM_ADMIN", 1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"ele geçirildi\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /notes/{id}: KENDİ takımının notunu yönetici silebilir -> 200")
    void deleteNote_ownTeamAdmin_returns200() throws Exception {
        when(noteRepo.findById(9L)).thenReturn(Optional.of(note(9L, 1L, "b")));
        when(noteRepo.save(any(MonitorNote.class))).thenAnswer(a -> a.getArgument(0));

        mvc.perform(delete("/api/monitoring/notes/9").session(teamSession("TEAM_ADMIN", 1L)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DELETE /notes/{id}: kendi notunu her kullanıcı silebilir (takım kapsın ya da kapsamasın)")
    void deleteNote_ownNoteAcrossTeams_returns200() throws Exception {
        when(noteRepo.findById(9L)).thenReturn(Optional.of(note(9L, 2L, "u")));   // oturum username = "u"
        when(noteRepo.save(any(MonitorNote.class))).thenAnswer(a -> a.getArgument(0));

        mvc.perform(delete("/api/monitoring/notes/9").session(teamSession("USER", 1L)))
                .andExpect(status().isOk());
    }
}
