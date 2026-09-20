package com.sitemonitor.service;

import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Özet şeridi (2026-09-20): sayaçlar + uyarılar; kapsamlı kullanıcı yalnız kendi takımlarını sayar.
 * Tarihler ŞİMDİ'ye göre türetilir (sabit fixture = zaman bombası).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminOverviewServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock NotificationGroupRepository groupRepo;
    @Mock AlertThresholdRepository thresholdRepo;
    @InjectMocks AdminOverviewService service;

    private static Team team(long id, Long leader, String email) { Team t = new Team(); t.setId(id); t.setName("T" + id); t.setLeaderId(leader); t.setEmail(email); t.setActive(true); return t; }
    private static AppUser user(long id, String role, Long team, String lastLogin, boolean locked) {
        AppUser u = new AppUser(); u.setId(id); u.setUsername("u" + id); u.setSystemRole(role); u.setActive(true);
        u.setTeamId(team); u.setTeamIds(team == null ? new LinkedHashSet<>() : new LinkedHashSet<>(Set.of(team)));
        u.setLastLoginAt(lastLogin); u.setPermanentLock(locked); return u;
    }

    @BeforeEach
    void setUp() {
        String recent = ISO.format(Instant.now().minus(1, ChronoUnit.DAYS));
        String old = ISO.format(Instant.now().minus(120, ChronoUnit.DAYS));
        when(teamRepo.findAll()).thenReturn(List.of(team(7, 1L, "a@example.com"), team(9, null, "")));
        when(userRepo.findAll()).thenReturn(List.of(
                user(1, "ADMIN", null, recent, false), user(2, "USER", 7L, old, false),
                user(3, "USER", 9L, null, true), user(4, "TEAM_ADMIN", 9L, recent, false)));
        EscalationContact c1 = new EscalationContact(); c1.setId(1L); c1.setTeamId(7L); c1.setActive(true); c1.setWebhookUrl("https://x.example.com/h");
        EscalationContact c2 = new EscalationContact(); c2.setId(2L); c2.setTeamId(9L); c2.setActive(false);
        when(contactRepo.findAll()).thenReturn(List.of(c1, c2));
        NotificationGroup g = new NotificationGroup(); g.setId(1L); g.setTeamId(9L); g.setActive(true); g.setEmails("");
        when(groupRepo.findAll()).thenReturn(List.of(g));
        AlertThreshold d = new AlertThreshold(); d.setActive(true);
        AlertThreshold t1 = new AlertThreshold(); t1.setActive(true); t1.setTier(1);
        when(thresholdRepo.findAll()).thenReturn(List.of(d, t1));
    }

    @Test
    @DisplayName("Global: tüm sayaçlar + uyarı kodları (lidersiz, adressiz, boş grup, pasif kişi, kilitli, hiç girmemiş, uyuyan, tek admin)")
    @SuppressWarnings("unchecked")
    void global() {
        Map<String, Object> r = service.overview(null);
        Map<String, Object> c = (Map<String, Object>) r.get("counts");
        assertThat(c).containsEntry("teams", 2).containsEntry("users", 4).containsEntry("admins", 1)
                .containsEntry("contacts", 2).containsEntry("contacts_webhook", 1).containsEntry("groups", 1).containsEntry("threshold_tiers", 1);
        List<Map<String, Object>> w = (List<Map<String, Object>>) r.get("warnings");
        assertThat(w).extracting(m -> m.get("code")).containsExactly(
                "TEAM_NO_LEADER", "TEAM_NO_EMAIL", "GROUP_NO_EMAILS", "CONTACT_INACTIVE", "USER_LOCKED",
                "USER_NEVER_LOGGED_IN", "USER_DORMANT", "SINGLE_ADMIN");
        assertThat(w.get(6)).containsEntry("count", 1).containsEntry("tab", "users");   // uyuyan: u2 (120 gün)
    }

    @Test
    @DisplayName("Kapsamlı (takım 7): yalnız o takımın satırları; admin uyarıları ve eşik sayaçları yok")
    @SuppressWarnings("unchecked")
    void scoped() {
        Map<String, Object> r = service.overview(List.of(7L));
        Map<String, Object> c = (Map<String, Object>) r.get("counts");
        assertThat(c).containsEntry("teams", 1).containsEntry("users", 1).containsEntry("contacts", 1).containsEntry("groups", 0);
        assertThat(c).doesNotContainKey("threshold_tiers");
        List<Map<String, Object>> w = (List<Map<String, Object>>) r.get("warnings");
        assertThat(w).extracting(m -> m.get("code")).containsExactly("USER_DORMANT");
    }
}
