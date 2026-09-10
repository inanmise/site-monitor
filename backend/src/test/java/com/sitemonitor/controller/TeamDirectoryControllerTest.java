package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Kurum-geneli takım rehberi: oturum açmış herkes okur → projeksiyon beyaz-listeli olmalı
 * (telefon / sicil / sistem rolü / foto SIZMAZ), üyelik yüklemi birincil + çoklu takım,
 * yalnız aktif hesaplar, bilinmeyen takım 404, eskalasyon kişileri takıma göre.
 */
@ExtendWith(MockitoExtension.class)
class TeamDirectoryControllerTest {

    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;
    @Mock EscalationContactRepository contactRepo;

    TeamDirectoryController controller;

    @BeforeEach
    void setUp() { controller = new TeamDirectoryController(teamRepo, userRepo, contactRepo); }

    private static AppUser user(long id, String username, String display, Long teamId, Long... extraTeams) {
        AppUser u = new AppUser();
        u.setId(id); u.setUsername(username); u.setDisplayName(display); u.setTeamId(teamId); u.setActive(true);
        u.setTeamIds(new java.util.LinkedHashSet<>(List.of(extraTeams)));
        u.setEmail(username + "@example.com"); u.setTitle("Uzman"); u.setDepartment("Dijital");
        u.setMudurlukName("Kanallar"); u.setOrgRole("MEMBER"); u.setCompanyLevel("7");
        u.setPhone("+90 555 000 00 00"); u.setEmployeeId("S" + id); u.setSystemRole("USER"); u.setPhotoBase64("AAAA");
        return u;
    }

    private static Team team(long id, String name, Long leaderId) {
        Team t = new Team(); t.setId(id); t.setName(name); t.setLeaderId(leaderId); t.setEmail(name.toLowerCase() + "@example.com"); t.setActive(true);
        return t;
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("members: beyaz-liste alanları döner, telefon/sicil/sistem rolü/foto SIZMAZ; çoklu-takım üyesi dahil, pasif hariç")
    void members_whitelistedProjection() {
        AppUser lead = user(7, "lead", "Lider Kişi", 1L);
        AppUser multi = user(8, "multi", "Çoklu Üye", 9L, 1L);          // birincil 9, ek üyelik 1
        AppUser other = user(9, "other", "Başka Takım", 2L);
        AppUser passive = user(10, "gone", "Ayrılmış", 1L); passive.setActive(false);
        AppUser mgr = user(11, "mgr", "Müdür Kişi", 3L); lead.setManagerId(11L);
        when(teamRepo.findById(1L)).thenReturn(Optional.of(team(1, "Payments", 7L)));
        when(userRepo.findAll()).thenReturn(List.of(lead, multi, other, passive, mgr));
        EscalationContact c = new EscalationContact(); c.setId(3L); c.setName("Nöbetçi"); c.setEmail("oncall@example.com"); c.setRole("TECH"); c.setMinAlertLevel("HIGH");
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(1L)).thenReturn(List.of(c));

        ResponseEntity<Map<String, Object>> resp = controller.members(1L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        List<Map<String, Object>> members = (List<Map<String, Object>>) data.get("members");
        assertThat(members).extracting(m -> m.get("username")).containsExactlyInAnyOrder("lead", "multi");
        Map<String, Object> leadRow = members.stream().filter(m -> "lead".equals(m.get("username"))).findFirst().orElseThrow();
        assertThat(leadRow).containsKeys("display_name", "title", "department", "mudurluk_name", "org_role", "email", "company_level", "manager_id", "manager_display_name");
        assertThat(leadRow.get("manager_display_name")).isEqualTo("Müdür Kişi");
        assertThat(leadRow).doesNotContainKeys("phone", "employee_id", "system_role", "photo_base64", "password_hash");
        Map<String, Object> t = (Map<String, Object>) data.get("team");
        assertThat(t.get("leader_display_name")).isEqualTo("Lider Kişi");
        List<Map<String, Object>> contacts = (List<Map<String, Object>>) data.get("escalation_contacts");
        assertThat(contacts).singleElement().satisfies(m -> {
            assertThat(m.get("email")).isEqualTo("oncall@example.com");
            assertThat(m.get("min_alert_level")).isEqualTo("HIGH");
        });
    }

    @Test
    @DisplayName("members: bilinmeyen takım → 404")
    void members_unknownTeam_404() {
        when(teamRepo.findById(99L)).thenReturn(Optional.empty());
        assertThat(controller.members(99L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("directory: takım listesi id/ad/e-posta/lider adı ile döner")
    void directory_mapsLeader() {
        when(teamRepo.findAll()).thenReturn(List.of(team(1, "Payments", 7L), team(2, "Cards", null)));
        when(userRepo.findAllById(any())).thenReturn(List.of(user(7, "lead", "Lider Kişi", 1L)));

        List<Map<String, Object>> data = (List<Map<String, Object>>) controller.directory().getBody().get("data");

        assertThat(data).hasSize(2);
        assertThat(data.get(0).get("leader_display_name")).isEqualTo("Lider Kişi");
        assertThat(data.get(1).get("leader_display_name")).isNull();
        assertThat(data.get(0)).containsKeys("id", "name", "active", "email", "leader_id");
    }
}
