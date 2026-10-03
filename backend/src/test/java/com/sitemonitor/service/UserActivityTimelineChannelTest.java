package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sistem Sağlığı → Kullanıcı/Oturum → kullanıcı zaman çizelgesi (2026-10-03): her giriş satırında KANAL (Giriş Yöntemleri
 * istatistikleriyle aynı kural) + kişinin kanal özeti. Mevcut alanlar ve sayımlar değişmez (eklemeli).
 */
class UserActivityTimelineChannelTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private static AuditLog ev(long id, String type, String outcome, String reason, String detail, int minutesAgo) {
        AuditLog a = new AuditLog();
        a.setId(id);
        a.setEventType(type);
        a.setOutcome(outcome);
        a.setFailureReason(reason);
        a.setDetail(detail);
        a.setActor("USER-B");
        a.setEventTime(ISO.format(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)));
        return a;
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("satır başına kanal (yöntem ayrıntısı / eski satırda hesap kaynağı → tahmin) + kanal özeti; eski alanlar aynen")
    void timelineCarriesChannel() {
        AuditLogRepository audit = mock(AuditLogRepository.class);
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser b = new AppUser();
        b.setUsername("USER-B");
        b.setAuthSource("LDAP");
        when(users.findByUsername(anyString())).thenReturn(Optional.of(b));
        when(audit.findLoginEventsSince(any(), anyString())).thenReturn(List.of(
                ev(1, "LOGIN", "SUCCESS", null, "{\"method\":\"LDAP\"}", 50),
                ev(2, "LOGIN", "SUCCESS", null, "{\"method\":\"REMEMBER_ME\"}", 40),
                ev(3, "LOGIN", "SUCCESS", null, null, 30),
                ev(4, "LOGIN_FAILED", "FAILURE", "BAD_PASSWORD: attempt #1/5 for x", "{\"method\":\"LDAP\"}", 20),
                ev(5, "LOGIN", "SUCCESS", null, "{\"method\":\"OTP_EMAIL\"}", 10)));
        UserActivityService svc = new UserActivityService(audit, users, mock(TeamRepository.class), mock(UserService.class),
                mock(PageUsageService.class), mock(JdbcTemplate.class), mock(AppSettingsService.class));

        Map<String, Object> tl = svc.userTimeline("user-b", 20);
        List<Map<String, Object>> events = (List<Map<String, Object>>) tl.get("events");
        assertThat(events).extracting(e -> e.get("channel"))
                .containsExactly("OTP_EMAIL", "LDAP", "LDAP", "REMEMBER_ME", "LDAP");
        assertThat(events).extracting(e -> e.get("channel_estimated")).containsExactly(false, false, true, false, false);
        assertThat(events.get(0)).containsKeys("id", "time", "ip", "outcome", "reason", "flags", "ack");
        Map<String, Object> ch = (Map<String, Object>) tl.get("channels");
        assertThat((Map<String, Object>) ch.get("LDAP")).containsEntry("success", 2L).containsEntry("failed", 1L);
        assertThat((Map<String, Object>) ch.get("REMEMBER_ME")).containsEntry("success", 1L).containsEntry("failed", 0L);
        assertThat((Map<String, Object>) ch.get("OTP_EMAIL")).containsEntry("success", 1L);
        assertThat(tl).containsEntry("logins", 4L).containsEntry("failed", 1L);
    }
}
