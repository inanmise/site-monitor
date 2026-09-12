package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.LdapSettings;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Yapılandırma sağlığı (2026-09-12, #25): her kontrolün karar tablosu + cron/gün kapsama + bir kontrol düşerse kart yine döner. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfigHealthServiceTest {

    @Mock SmtpSettingsService smtpSettings;
    @Mock LdapSettingsService ldapSettings;
    @Mock UserPushService userPushService;
    @Mock AppSettingsService appSettings;
    @Mock AuditLogRepository auditLogRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock SchedulerService schedulerService;

    ConfigHealthService svc;

    @BeforeEach
    void setUp() {
        svc = new ConfigHealthService(smtpSettings, ldapSettings, userPushService, appSettings, auditLogRepo, inventoryRepo, latestCheckRepo, schedulerService);
        ReflectionTestUtils.setField(svc, "reminderCron", "0 0 9 * * *");
        ReflectionTestUtils.setField(svc, "reminderEnabled", true);
        SmtpSettings smtp = new SmtpSettings(); smtp.setEnabled(true); smtp.setHost("mail.example.com");
        when(smtpSettings.getOrDefaults()).thenReturn(smtp);
        LdapSettings ldap = new LdapSettings(); ldap.setEnabled(false);
        when(ldapSettings.getOrDefaults()).thenReturn(ldap);
        when(userPushService.healthSnapshot()).thenReturn(Map.of("enabled", true, "circuit_open", false, "consecutive_failures", 0));
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(eq("site.monitor.userpush.url"), any())).thenReturn("https://push.example.com/hook");
        when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenReturn("https://monitor.example.com");
        when(appSettings.getString(eq("site.monitor.system-admin.email"), any())).thenReturn("ops@example.com");
        when(auditLogRepo.findTopByEventTypeOrderByEventTimeDesc(anyString())).thenReturn(Optional.empty());
        when(inventoryRepo.countByActiveTrueAndTeamIdIsNull()).thenReturn(0L);
        when(latestCheckRepo.findWeakAlgorithmCandidates()).thenReturn(List.of());
        when(schedulerService.getStatus()).thenReturn(Map.of("last_run", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(20).toString()));
    }

    private Map<String, Object> check(Map<String, Object> body, String key) {
        @SuppressWarnings("unchecked") List<Map<String, Object>> checks = (List<Map<String, Object>>) body.get("checks");
        return checks.stream().filter(c -> key.equals(c.get("key"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("sağlıklı kurulum: SMTP hiç test edilmemiş → warn; LDAP kapalı → off; diğerleri ok; overall warn")
    void healthyWithUntestedSmtp() {
        Map<String, Object> b = svc.build();
        assertThat(check(b, "smtp")).containsEntry("status", "warn").containsEntry("detail", "never_tested").containsEntry("tab", "smtp");
        assertThat(check(b, "ldap")).containsEntry("status", "off");
        assertThat(check(b, "push")).containsEntry("status", "ok");
        assertThat(check(b, "reminder")).containsEntry("status", "ok").containsEntry("detail", "FRI 15:00");
        assertThat(check(b, "base_url")).containsEntry("status", "ok");
        assertThat(check(b, "unowned")).containsEntry("status", "ok");
        assertThat(check(b, "scheduler")).containsEntry("status", "ok");
        assertThat(b.get("overall")).isEqualTo("warn");
        assertThat(b.get("warn")).isEqualTo(1L);
    }

    @Test
    @DisplayName("kırmızılar: SMTP son testi FAIL, push devre kesici açık, cron son giriş gününü kapsamıyor, zamanlayıcı 3 saattir koşmadı, localhost adres, 5 sahipsiz alan")
    void badCases() {
        AuditLog fail = new AuditLog(); fail.setOutcome("FAILURE"); fail.setEventTime("2026-09-12T08:00:00");
        when(auditLogRepo.findTopByEventTypeOrderByEventTimeDesc("SMTP_TEST")).thenReturn(Optional.of(fail));
        when(userPushService.healthSnapshot()).thenReturn(Map.of("enabled", true, "circuit_open", true, "consecutive_failures", 4));
        ReflectionTestUtils.setField(svc, "reminderCron", "0 0 9 ? * FRI");
        when(appSettings.getString(eq(WeeklyReportDeadline.KEY_DAY), any())).thenReturn("THU");
        when(schedulerService.getStatus()).thenReturn(Map.of("last_run", LocalDateTime.now(ZoneOffset.UTC).minusHours(3).toString()));
        when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenReturn("http://localhost:5173");
        when(inventoryRepo.countByActiveTrueAndTeamIdIsNull()).thenReturn(5L);

        Map<String, Object> b = svc.build();
        assertThat(check(b, "smtp")).containsEntry("status", "bad").containsEntry("detail", "tested_fail:2026-09-12T08:00:00");
        assertThat(check(b, "push")).containsEntry("status", "bad").containsEntry("detail", "circuit_open");
        assertThat(check(b, "reminder")).containsEntry("status", "bad");
        assertThat((String) check(b, "reminder").get("detail")).startsWith("cron_misses_deadline:0 0 9 ? * FRI|THU");
        assertThat(check(b, "scheduler")).containsEntry("status", "bad");
        assertThat((String) check(b, "scheduler").get("detail")).startsWith("stale:");
        assertThat(check(b, "base_url")).containsEntry("status", "warn").containsEntry("detail", "localhost");
        assertThat(check(b, "unowned")).containsEntry("status", "warn").containsEntry("detail", "5");
        assertThat(b.get("overall")).isEqualTo("bad");
    }

    @Test
    @DisplayName("bir kontrol istisna fırlatırsa kart yine döner: o satır bad + error:<sınıf>")
    void probeFailureIsIsolated() {
        when(schedulerService.getStatus()).thenThrow(new IllegalStateException("boom"));
        Map<String, Object> b = svc.build();
        assertThat(check(b, "scheduler")).containsEntry("status", "bad").containsEntry("detail", "error:IllegalStateException");
        assertThat(check(b, "push")).containsEntry("status", "ok");
    }

    @Test
    @DisplayName("cronCoversDay: * / ? / FRI / 5 / MON-FRI / 1,3,5 / adım → kapsam; SAT aralığı dışı → kapsamaz")
    void cronCoversDay() {
        assertThat(ConfigHealthService.cronCoversDay("0 0 9 * * *", DayOfWeek.THURSDAY)).isTrue();
        assertThat(ConfigHealthService.cronCoversDay("0 0 9 ? * ?", DayOfWeek.THURSDAY)).isTrue();
        assertThat(ConfigHealthService.cronCoversDay("0 0 9 ? * FRI", DayOfWeek.FRIDAY)).isTrue();
        assertThat(ConfigHealthService.cronCoversDay("0 0 9 ? * FRI", DayOfWeek.THURSDAY)).isFalse();
        assertThat(ConfigHealthService.cronCoversDay("0 0 9 ? * 5", DayOfWeek.FRIDAY)).isTrue();
        assertThat(ConfigHealthService.cronCoversDay("0 0 9 ? * MON-FRI", DayOfWeek.WEDNESDAY)).isTrue();
        assertThat(ConfigHealthService.cronCoversDay("0 0 9 ? * MON-FRI", DayOfWeek.SATURDAY)).isFalse();
        assertThat(ConfigHealthService.cronCoversDay("0 0 9 ? * 1,3,5", DayOfWeek.WEDNESDAY)).isTrue();
        assertThat(ConfigHealthService.cronCoversDay("0 0 9 ? * 0", DayOfWeek.SUNDAY)).isTrue();
        assertThat(ConfigHealthService.cronCoversDay("0 0 9 ? * 7", DayOfWeek.SUNDAY)).isTrue();
        assertThat(ConfigHealthService.cronCoversDay("0 0 9 ? * */2", DayOfWeek.SUNDAY)).isTrue();
        assertThat(ConfigHealthService.cronCoversDay(null, DayOfWeek.SUNDAY)).isTrue();
    }
}
