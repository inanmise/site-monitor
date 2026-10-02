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
 * Sistem Bakım Modu — alarm bildirimi susturma (2026-10-02, kullanıcı kararı). GERÇEK {@link EscalationService},
 * bağımlılıklar sahte. "Bildirimler bakım boyunca sussun" açık bakım AKTİFKEN: e-posta, kontak webhook'u, kişi push'u
 * gönderilmez; günlükte {@code SYSTEM_MAINTENANCE / SKIPPED: sistem bakımı} izi, push karar satırı ve bakımın telafi
 * kaydı. Elle gönderim (MANUAL) susmaz. Bakım yokken / anahtar kapalıyken çıktı bayt bayt bugünkü (pozitif kontrol).
 * Telafi: "ilk bildirim" damgası sıfırlanmış açık alarm bir sonraki turda INITIAL'ı BİR kez alır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemMaintenanceNotificationTest {

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
    @Mock SystemMaintenanceService maintenance;

    static final long TEAM_B = 2L;
    static final String B_MAIL = "takim-b@example.com", B_MANAGER = "mudur-b@example.com";
    static final String B_HOOK = "https://hooks.example.com/services/T1/B2/takim-b";
    static final String DOMAIN = "b.example.com";
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    final List<NotificationLog> logRows = new ArrayList<>();
    EscalationService service;

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);

        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(smtpSettings.getOrDefaults()).thenReturn(new com.sitemonitor.model.SmtpSettings());
        AlertThreshold t = new AlertThreshold();
        t.setWarningDays(30); t.setHighDays(15); t.setCriticalDays(7); t.setReAlertIntervalHours(24);
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.of(t));
        when(alertEventRepo.markResolvedIfOpen(any(), any(), any())).thenReturn(1);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(i -> {
            AlertEvent e = i.getArgument(0);
            if (e.getId() == null) e.setId(100L);
            return e;
        });
        when(alertEventRepo.findById(100L)).thenAnswer(i -> Optional.of(event(100L)));
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
        mgr.setWebhookUrl(B_HOOK); mgr.setWebhookType("TEAMS");
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(any())).thenReturn(List.of(mgr));
        when(contactRepo.findByTeamIdAndMinAlertLevelInAndActiveTrue(any(), anyList())).thenReturn(List.of(mgr));
        when(contactRepo.findByTeamIdAndMinAlertLevelAndActiveTrue(any(), anyString())).thenReturn(List.of(mgr));
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(DOMAIN); inv.setTeamId(TEAM_B);
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of(inv));
        when(inventoryRepo.findByDomain(DOMAIN)).thenReturn(Optional.of(inv));
    }

    private void muted(boolean on) {
        service.setSystemMaintenance(maintenance);
        when(maintenance.notificationsMuted()).thenReturn(on);
    }

    @Test
    @DisplayName("SUSTURULMUŞ bakım: açılış e-postası/webhook/push GİTMEZ; günlükte SYSTEM_MAINTENANCE izi + push kararı + telafi kaydı")
    void muted_initialSuppressed_withTrace() {
        muted(true);

        service.processResults(List.of(hijackedResult()));

        verify(emailService, never()).sendAlert(any(String[].class), any(), any(), any(), any(), any(), any(), any());
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
        verify(userPushService, never()).enqueueAlert(any(), any(), any(), any(), any());
        verify(userPushService, never()).enqueueAlert(any(), any(), any(), any());
        assertThat(logRows).hasSize(1);
        NotificationLog row = logRows.get(0);
        assertThat(row.getTrigger()).isEqualTo("SYSTEM_MAINTENANCE");
        assertThat(row.getEmailStatus()).isEqualTo("SKIPPED: sistem bakımı");
        assertThat(row.getAlertEventId()).isEqualTo(100L);
        assertThat(row.getSubject()).contains("INITIAL");
        verify(userPushService).recordSuppressedFor(any(AlertEvent.class), eq("INITIAL"), eq("SKIPPED_SYSTEM_MAINTENANCE"));
        verify(maintenance).noteSuppressed(100L, "INITIAL");
        // Olay kaydı ve kontrol sürer: alarm açıldı, çağıranın damgası aynen.
        ArgumentCaptor<AlertEvent> saved = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getLastReAlertAt()).isNotNull();
    }

    @Test
    @DisplayName("bakım yok / anahtar KAPALI → bayt bayt bugünkü: aynı alıcılar, aynı günlük satırları, susturma kaydı yok")
    void notMuted_identicalToToday() {
        // Referans: bakım servisi hiç yok
        service.processResults(List.of(hijackedResult()));
        List<String> refTo = sentTo();
        List<String> refLog = logSignature();
        clearInvocations(emailService, webhookService, userPushService);
        logRows.clear();

        muted(false);
        service.processResults(List.of(hijackedResult()));

        assertThat(sentTo()).isEqualTo(refTo).containsExactlyInAnyOrder(B_MAIL, B_MANAGER);
        assertThat(logSignature()).isEqualTo(refLog);
        verify(webhookService).send(eq("TEAMS"), eq(B_HOOK), anyString(), anyString(), eq("CRITICAL"));
        verify(maintenance, never()).noteSuppressed(any(), any());
        assertThat(logRows).extracting(NotificationLog::getTrigger).doesNotContain("SYSTEM_MAINTENANCE");
    }

    @Test
    @DisplayName("elle 'Tekrar bildir' (MANUAL) bakımda da GİDER — operatör iradesi")
    void manualResend_notMuted() {
        muted(true);
        EscalationContact mgr = contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(TEAM_B).get(0);
        service.reNotifyAsync(100L, TEAM_B, null, List.of(mgr), DOMAIN, "CRITICAL",
                EscalationService.TYPE_HOSTNAME_MISMATCH, null, Set.of(), Set.of());
        verify(emailService).sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
        verify(maintenance, never()).noteSuppressed(any(), any());
    }

    @Test
    @DisplayName("SUSTURULMUŞ bakımda çözüm: e-posta / webhook gitmez; iz + push RESOLVE kararı (bakımda kapanan alarma sonra da bir şey gitmez)")
    void muted_resolution_suppressed() {
        muted(true);
        AlertEvent open = event(7L);
        when(alertEventRepo.findById(7L)).thenReturn(Optional.of(open));

        service.resolve(7L, "admin");

        verify(emailService, never()).sendResolutionAlert(any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
        assertThat(logRows).extracting(NotificationLog::getTrigger).contains("SYSTEM_MAINTENANCE");
        verify(userPushService).recordSuppressedFor(any(AlertEvent.class), eq("MANUAL_RESOLVE"), eq("SKIPPED_SYSTEM_MAINTENANCE"));
        verify(maintenance).noteSuppressed(7L, "MANUAL_RESOLVE");
    }

    @Test
    @DisplayName("TELAFİ: bakım bitti, 'ilk bildirim' damgası sıfırlanmış açık alarm → sonraki turda INITIAL BİR kez, normal kurallarla")
    void catchUp_initialSentOnceAfterMaintenance() {
        muted(false);
        AlertEvent open = event(7L);
        open.setLastReAlertAt(null);   // SystemMaintenanceJobService.catchUp → clearInitialStampForCatchUp
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(open));
        when(alertEventRepo.findById(7L)).thenReturn(Optional.of(open));

        service.processResults(List.of(hijackedResult()));

        assertThat(sentTo()).containsExactlyInAnyOrder(B_MAIL, B_MANAGER);
        assertThat(logRows).extracting(NotificationLog::getTrigger).contains("INITIAL");
        assertThat(open.getLastReAlertAt()).as("damga yeniden basıldı — ikinci kez gitmez").isNotNull();

        clearInvocations(emailService);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(open));
        service.processResults(List.of(hijackedResult()));
        verify(emailService, never()).sendAlert(any(String[].class), any(), any(), any(), any(), any(), any(), any());
    }

    // ── yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private static AlertEvent event(long id) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setDomain(DOMAIN); e.setAlertType(EscalationService.TYPE_HOSTNAME_MISMATCH);
        e.setAlertLevel("CRITICAL"); e.setTeamId(TEAM_B); e.setResolved(false); e.setAcknowledged(false);
        e.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        e.setLastReAlertAt(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        return e;
    }

    private static Map<String, Object> hijackedResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", DOMAIN); r.put("status", "valid"); r.put("warning", false); r.put("days_remaining", 300);
        r.put("revocation_status", "VALID"); r.put("chain_status", "VALID"); r.put("deployment_status", "OK");
        r.put("san", List.of("baska.example.com"));   // HOSTNAME_MISMATCH (KRİTİK)
        r.put("trust_status", "TRUSTED");
        return r;
    }

    private List<String> sentTo() {
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService, atLeastOnce()).sendAlert(to.capture(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        List<String> out = new ArrayList<>();
        for (String[] a : to.getAllValues()) out.addAll(Arrays.asList(a));
        return out;
    }

    /** Günlük satırlarının zaman-bağımsız imzası (tetik | durum | alıcı | konu). */
    private List<String> logSignature() {
        List<String> out = new ArrayList<>();
        for (NotificationLog n : logRows) {
            out.add(n.getTrigger() + "|" + n.getEmailStatus() + "|" + n.getWebhookStatus() + "|" + n.getRecipientName()
                    + "|" + n.getRecipientEmail() + "|" + n.getSubject() + "|" + n.getMessage());
        }
        return out;
    }
}
