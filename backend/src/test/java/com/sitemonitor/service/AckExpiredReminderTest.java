package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
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
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Süreli onayın hatırlatması (2026-11-19, kullanıcı kararı: "süre dolunca alarm hâlâ açıksa onay kalkar ve hemen hatırlatma
 * gider"). GERÇEK {@link EscalationService}, bağımlılıklar sahte: hatırlatma alarmın NORMAL alıcılarına {@code ACK_EXPIRED}
 * tetiğiyle ve {@code [ONAY SÜRESİ DOLDU]} konu önekiyle gider; push aynı tetikle; aktif fırtına üyesi / bakım penceresi /
 * kapanmış / yeniden onaylanmış alarmda gönderim yok (ilk ikisinde karar satırı); susturulmuş sistem bakımı bunu da susturur
 * (elle gönderim değil); hatırlatma bir hata atarsa sessiz değil — FAILED satırı.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AckExpiredReminderTest {

    @Mock NotificationGroupService notificationGroups;
    @Mock AlertEventRepository alertEventRepo;
    @Mock AlertThresholdRepository thresholdRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock EmailNotificationService emailService;
    @Mock WeeklyAvailabilityReportService weeklyAvailability;
    @Mock WebhookService webhookService;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock TeamRepository teamRepo;
    @Mock SmtpSettingsService smtpSettings;
    @Mock MaintenanceService maintenanceService;
    @Mock StormService stormService;
    @Mock UserPushService userPushService;
    @Mock DomainMonitorRepository domainMonitorRepo;
    @Mock DomainCheckRepository domainCheckRepo;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock PageCheckRepository pageCheckRepo;
    @Mock AppSettingsService appSettings;
    @Mock SystemMaintenanceService systemMaintenance;
    @Mock org.springframework.jdbc.core.JdbcTemplate jdbc;

    static final long TEAM_B = 2L;
    static final String B_MAIL = "takim-b@example.com", B_MANAGER = "mudur-b@example.com";
    static final String DOMAIN = "b.example.com";
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Sahiplenmenin yazdığı damga ve önceki damga (gitmeyen hatırlatmada koşullu geri alınır). */
    static final String CLAIM = "2026-11-19T10:00:00", PREV = "2026-11-18T09:00:00";
    static final String RESTORE_SQL = "UPDATE alert_events SET last_re_alert_at = ? WHERE id = ? AND last_re_alert_at = ?";

    final List<NotificationLog> logRows = new ArrayList<>();
    EscalationService service;
    AlertEvent row;

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "jdbcTemplate", jdbc);

        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(smtpSettings.getOrDefaults()).thenReturn(new com.sitemonitor.model.SmtpSettings());
        AlertThreshold t = new AlertThreshold();
        t.setWarningDays(30); t.setHighDays(15); t.setCriticalDays(7); t.setReAlertIntervalHours(24);
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.of(t));
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        row = event();
        when(alertEventRepo.findById(100L)).thenAnswer(i -> Optional.of(row));
        when(notificationLogRepo.save(any())).thenAnswer(i -> { logRows.add(i.getArgument(0)); return i.getArgument(0); });
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("SENT");
        when(emailService.buildAlertEmailHtml(any(), any(), any(), any(), any(), any(), any())).thenReturn("<html>alarm</html>");

        Team team = new Team();
        team.setId(TEAM_B); team.setName("Takım B"); team.setEmail(B_MAIL);
        when(teamRepo.findById(TEAM_B)).thenReturn(Optional.of(team));
        EscalationContact mgr = new EscalationContact();
        mgr.setId(10L); mgr.setTeamId(TEAM_B); mgr.setName("Müdür B"); mgr.setEmail(B_MANAGER);
        mgr.setRole("MANAGER"); mgr.setMinAlertLevel("HIGH"); mgr.setActive(true);
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(any())).thenReturn(List.of(mgr));
        when(contactRepo.findByTeamIdAndMinAlertLevelInAndActiveTrue(any(), anyList())).thenReturn(List.of(mgr));
        when(contactRepo.findByTeamIdAndMinAlertLevelAndActiveTrue(any(), anyString())).thenReturn(List.of(mgr));
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(DOMAIN); inv.setTeamId(TEAM_B);
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of(inv));
        when(inventoryRepo.findByDomain(DOMAIN)).thenReturn(Optional.of(inv));
    }

    private static AlertEvent event() {
        AlertEvent e = new AlertEvent();
        e.setId(100L); e.setDomain(DOMAIN); e.setAlertType(EscalationService.TYPE_HOSTNAME_MISMATCH);
        e.setAlertLevel("CRITICAL"); e.setTeamId(TEAM_B); e.setResolved(false); e.setAcknowledged(false);
        e.setMessage("Sertifika ana makine adıyla eşleşmiyor");
        e.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        e.setLastReAlertAt(ISO.format(Instant.now()));
        return e;
    }

    private List<String> sentTo() {
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService, atLeastOnce()).sendAlert(to.capture(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        List<String> out = new ArrayList<>();
        for (String[] a : to.getAllValues()) out.addAll(Arrays.asList(a));
        return out;
    }

    private void noMail() {
        verify(emailService, never()).sendAlert(any(String[].class), any(), any(), any(), any(), any(), any(), any());
        verify(userPushService, never()).enqueueAlert(any(), any(), any(), any(), any());
        verify(userPushService, never()).enqueueAlert(any(), any(), any(), any());
    }

    @Test
    @DisplayName("açık + onayı düşmüş alarm: takım adresi + seviyesine uyan eskalasyon kişisi, ACK_EXPIRED tetiği ve konu öneki")
    void reminderGoesToNormalRecipients() {
        service.ackExpiredReminder(100L, CLAIM, PREV);

        assertThat(sentTo()).containsExactlyInAnyOrder(B_MAIL, B_MANAGER);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendAlert(any(String[].class), subject.capture(), anyString(), anyString(),
                anyString(), anyString(), any(), any());
        assertThat(subject.getAllValues()).allMatch(s -> s.startsWith(EscalationService.ACK_EXPIRED_SUBJECT_PREFIX));
        assertThat(logRows).isNotEmpty();
        assertThat(logRows).extracting(NotificationLog::getTrigger).containsOnly(EscalationService.TRIGGER_ACK_EXPIRED);
        assertThat(logRows).extracting(NotificationLog::getEmailStatus).contains("SENT");
        verify(jdbc, never()).update(eq(RESTORE_SQL), any(), any(), any());   // gitti — damga kalır
    }

    @Test
    @DisplayName("bu arada kapanmış ya da yeniden onaylanmış alarm: hiçbir şey gitmez, satır yazılmaz")
    void closedOrReAcknowledged_nothing() {
        row.setResolved(true);
        service.ackExpiredReminder(100L, CLAIM, PREV);
        row.setResolved(false);
        row.setAcknowledged(true);
        service.ackExpiredReminder(100L, CLAIM, PREV);
        when(alertEventRepo.findById(100L)).thenReturn(Optional.empty());
        service.ackExpiredReminder(100L, CLAIM, PREV);

        noMail();
        assertThat(logRows).isEmpty();
    }

    @Test
    @DisplayName("aktif fırtına üyesi: gönderim yok, fırtınaya devredildi satırı")
    void stormMember_skippedWithTrace() {
        row.setStormId(55L);
        when(stormService.isActive(55L)).thenReturn(true);

        service.ackExpiredReminder(100L, CLAIM, PREV);

        noMail();
        assertThat(logRows).hasSize(1);
        assertThat(logRows.get(0).getEmailStatus()).isEqualTo(EscalationService.STATUS_ACK_EXPIRED_STORM);
        assertThat(logRows.get(0).getTrigger()).isEqualTo(EscalationService.TRIGGER_ACK_EXPIRED);
        verify(jdbc).update(RESTORE_SQL, PREV, 100L, CLAIM);   // gitmedi — günlük hatırlatma bir aralık kaymaz
    }

    @Test
    @DisplayName("bakım penceresindeki alarm: gönderim yok, bakım satırı")
    void maintenanceWindow_skippedWithTrace() {
        when(maintenanceService.isUnderMaintenance(DOMAIN)).thenReturn(true);

        service.ackExpiredReminder(100L, CLAIM, PREV);

        noMail();
        assertThat(logRows).hasSize(1);
        assertThat(logRows.get(0).getEmailStatus()).isEqualTo(EscalationService.STATUS_ACK_EXPIRED_MAINTENANCE);
        verify(jdbc).update(RESTORE_SQL, PREV, 100L, CLAIM);
    }

    @Test
    @DisplayName("susturulmuş sistem bakımı: otomatik hatırlatma da susar (elle gönderim değil) — iz kalır")
    void mutedSystemMaintenance_suppressed() {
        service.setSystemMaintenance(systemMaintenance);
        when(systemMaintenance.notificationsMuted()).thenReturn(true);

        service.ackExpiredReminder(100L, CLAIM, PREV);

        verify(emailService, never()).sendAlert(any(String[].class), any(), any(), any(), any(), any(), any(), any());
        assertThat(logRows).extracting(NotificationLog::getTrigger).contains("SYSTEM_MAINTENANCE");
        verify(systemMaintenance).noteSuppressed(100L, EscalationService.TRIGGER_ACK_EXPIRED);
        verify(jdbc).update(RESTORE_SQL, PREV, 100L, CLAIM);
    }

    @Test
    @DisplayName("önceki damga yoksa geri alınmaz (NULL 'ilk bildirim eksik' demektir — INITIAL'ı yeniden tetiklerdi)")
    void noPreviousStamp_notRestored() {
        when(maintenanceService.isUnderMaintenance(DOMAIN)).thenReturn(true);

        service.ackExpiredReminder(100L, CLAIM, null);

        noMail();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).update(eq(RESTORE_SQL), any(), any(), any());
        verify(jdbc, never()).update(eq("UPDATE alert_events SET last_re_alert_at = NULL WHERE id = ? AND last_re_alert_at = ?"),
                any(), any());
    }

    @Test
    @DisplayName("hatırlatma bir hata atarsa sessiz değil: FAILED satırı, istisna dışarı kaçmaz")
    void failure_leavesFailedRow() {
        when(maintenanceService.isUnderMaintenance(DOMAIN)).thenThrow(new IllegalStateException("db"));

        service.ackExpiredReminder(100L, CLAIM, PREV);

        noMail();
        assertThat(logRows).hasSize(1);
        assertThat(logRows.get(0).getTrigger()).isEqualTo(EscalationService.TRIGGER_ACK_EXPIRED);
        assertThat(logRows.get(0).getEmailStatus()).startsWith("FAILED: onay süresi hatırlatması gönderilemedi");
    }

    @Test
    @DisplayName("ackExpiry: süresiz / 0 / negatif → null; aksi şimdi + dakika (UTC ISO)")
    void ackExpiryRule() {
        Instant now = Instant.parse("2026-11-19T10:00:00Z");
        assertThat(EscalationService.ackExpiry(null, now)).isNull();
        assertThat(EscalationService.ackExpiry(0, now)).isNull();
        assertThat(EscalationService.ackExpiry(-5, now)).isNull();
        assertThat(EscalationService.ackExpiry(60, now)).isEqualTo("2026-11-19T11:00:00");
        assertThat(EscalationService.ackExpiry(4320, now)).isEqualTo("2026-11-22T10:00:00");
    }
}
