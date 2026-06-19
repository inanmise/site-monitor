package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * IncidentNotificationService.doNotify — takım e-postası + müdür çözümü, konu öneki,
 * alıcı/CTA üretimi. EmailNotificationService + repolar mock; doNotify senkron çağrılır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentNotificationServiceTest {

    @Mock EmailNotificationService emailService;
    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;

    IncidentNotificationService service;

    @BeforeEach
    void setUp() {
        service = new IncidentNotificationService(emailService, teamRepo, userRepo);
        ReflectionTestUtils.setField(service, "appBaseUrl", "https://cm.example.com/");
        when(emailService.buildIncidentNotificationHtml(anyMap(), any(), anyString(), anyString()))
                .thenReturn("<html/>");
        when(emailService.sendHtml(any(), any(), anyString(), anyString(), any())).thenReturn("SENT");
    }

    private Map<String, Object> dto() {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "DB pool tükendi");
        m.put("team_id", 7);
        m.put("status", "OPEN");
        return m;
    }

    private Team team(String email, Long leaderId) {
        Team t = new Team();
        t.setId(7L);
        t.setName("Dijital SY");
        t.setEmail(email);
        t.setLeaderId(leaderId);
        return t;
    }

    @Test
    @DisplayName("NEW: takım + müdür alıcı, konu öneki 'Yeni Olay', CTA incident-history, müdür adı geçer")
    void notify_recipientsAndSubject() {
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team("takim@bank.com", 42L)));
        AppUser mgr = new AppUser();
        mgr.setId(42L);
        mgr.setDisplayName("Müdür Bey");
        mgr.setEmail("mudur@bank.com");
        when(userRepo.findById(42L)).thenReturn(Optional.of(mgr));

        service.doNotify(dto(), "NEW");

        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(to.capture(), isNull(), subject.capture(), anyString(), isNull());
        assertThat(to.getValue()).containsExactlyInAnyOrder("takim@bank.com", "mudur@bank.com");
        assertThat(subject.getValue()).contains("Yeni Olay").contains("DB pool tükendi").contains("Dijital SY");

        ArgumentCaptor<String> mgrName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> cta = ArgumentCaptor.forClass(String.class);
        verify(emailService).buildIncidentNotificationHtml(anyMap(), mgrName.capture(), eq("NEW"), cta.capture());
        assertThat(mgrName.getValue()).isEqualTo("Müdür Bey");
        assertThat(cta.getValue()).isEqualTo("https://cm.example.com/?tab=incident-history"); // trailing / kırpıldı
    }

    @Test
    @DisplayName("RESOLVED: konu öneki 'Olay Çözüldü'")
    void notify_resolvedSubject() {
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team("takim@bank.com", null)));

        service.doNotify(dto(), "RESOLVED");

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(any(), isNull(), subject.capture(), anyString(), isNull());
        assertThat(subject.getValue()).contains("Olay Çözüldü");
    }

    @Test
    @DisplayName("team_id yoksa hiç mail gönderilmez")
    void notify_noTeamId_skips() {
        Map<String, Object> d = dto();
        d.remove("team_id");
        service.doNotify(d, "NEW");
        verify(emailService, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("takım e-postası yok + müdür yok → alıcı boş → mail gönderilmez")
    void notify_noRecipient_skips() {
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team(null, null)));
        service.doNotify(dto(), "NEW");
        verify(emailService, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }
}
