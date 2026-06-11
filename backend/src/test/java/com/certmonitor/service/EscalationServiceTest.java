package com.certmonitor.service;

import com.certmonitor.model.*;
import com.certmonitor.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeast;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationServiceTest {

    @Mock AlertEventRepository alertEventRepo;
    @Mock AlertThresholdRepository thresholdRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock com.certmonitor.repository.CertificateInventoryRepository inventoryRepo;
    @Mock EmailNotificationService emailService;
    @Mock WebhookService webhookService;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock com.certmonitor.repository.LatestCheckRepository latestCheckRepo;
    @Mock com.certmonitor.repository.TeamRepository teamRepo;

    private EscalationService service;
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo,
                inventoryRepo, emailService, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo);

        // Self-injection bypass for @Async dispatch in tests (runs synchronously)
        ReflectionTestUtils.setField(service, "self", service);

        // Default active threshold
        AlertThreshold t = defaultThreshold();
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.of(t));

        // processResults() artık batch ön yükleme yapıyor — default mock'lar
        // boş döner ki test bazlı override'lar (findOpenAlert.thenReturn) hâlâ
        // çalışsın. Bu testlerin "yok" senaryosu = boş list bekleniyor.
        when(alertEventRepo.findOpenByDomainIn(anyCollection()))
                .thenReturn(java.util.List.of());
        when(inventoryRepo.findByDomainIn(anyCollection()))
                .thenReturn(java.util.List.of());
    }

    // ── processResults: new alert ─────────────────────────────────────────────

    @Test
    @DisplayName("New EXPIRY WARNING alert creates event and sends email")
    void processResults_newExpiryWarning_createsEventAndSends() {
        String domain = "expiring.example.com";
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING")).thenReturn(List.of(contact("po@test.com", "PO", "WARNING")));
        when(alertEventRepo.findOpenAlert(domain, "EXPIRY")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processResults(List.of(expiryResult(domain, 25, true)));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        AlertEvent saved = captor.getAllValues().get(0);
        assertThat(saved.getDomain()).isEqualTo(domain);
        assertThat(saved.getAlertLevel()).isEqualTo("WARNING");
        assertThat(saved.getAlertType()).isEqualTo("EXPIRY");
        assertThat(saved.getAcknowledged()).isFalse();
        assertThat(saved.getResolved()).isFalse();
        verify(emailService).sendAlert(any(String[].class), contains("UYARI"), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("New CRITICAL alert (7 days) notifies all contacts")
    void processResults_criticalExpiry_notifiesAllContacts() {
        String domain = "urgent.example.com";
        List<EscalationContact> allContacts = List.of(
                contact("po@test.com", "PO", "WARNING"),
                contact("manager@test.com", "MANAGER", "HIGH"),
                contact("ceo@test.com", "CLEVEL", "CRITICAL")
        );
        when(contactRepo.findByActiveTrueOrderByRoleAsc()).thenReturn(allContacts);
        when(alertEventRepo.findOpenAlert(domain, "EXPIRY")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processResults(List.of(expiryResult(domain, 5, true)));

        // 3 contacts notified
        verify(emailService, times(1)).sendAlert(any(String[].class), contains("KRİTİK"), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("REVOKED cert always fires CRITICAL alert")
    void processResults_revokedCert_criticalAlert() {
        String domain = "revoked.example.com";
        when(contactRepo.findByActiveTrueOrderByRoleAsc()).thenReturn(List.of(contact("sec@test.com", "TECH", "WARNING")));
        when(alertEventRepo.findOpenAlert(domain, "REVOKED")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = Map.of(
                "domain", domain, "status", "valid", "warning", false,
                "days_remaining", 60,
                "revocation_status", "REVOKED",
                "chain_status", "VALID",
                "deployment_status", "OK"
        );
        service.processResults(List.of(result));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getAlertLevel()).isEqualTo("CRITICAL");
        assertThat(captor.getAllValues().get(0).getAlertType()).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("DEPLOYMENT_INCOMPLETE fires CRITICAL MISMATCH alert")
    void processResults_deploymentMismatch_criticalMismatchAlert() {
        String domain = "mismatch.example.com";
        when(contactRepo.findByActiveTrueOrderByRoleAsc()).thenReturn(List.of(contact("dev@test.com", "TECH", "WARNING")));
        when(alertEventRepo.findOpenAlert(domain, "MISMATCH")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = Map.of(
                "domain", domain, "status", "valid", "warning", false,
                "days_remaining", 90, "revocation_status", "VALID",
                "chain_status", "VALID", "deployment_status", "INCOMPLETE"
        );
        service.processResults(List.of(result));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getAlertType()).isEqualTo("MISMATCH");
        assertThat(captor.getAllValues().get(0).getAlertLevel()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("CHAIN_BROKEN fires CRITICAL alert")
    void processResults_chainBroken_criticalAlert() {
        String domain = "chain-broken.example.com";
        when(contactRepo.findByActiveTrueOrderByRoleAsc()).thenReturn(List.of());
        when(alertEventRepo.findOpenAlert(domain, "CHAIN_BROKEN")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = Map.of(
                "domain", domain, "status", "valid", "warning", false,
                "days_remaining", 90, "revocation_status", "VALID",
                "chain_status", "BROKEN", "deployment_status", "OK"
        );
        service.processResults(List.of(result));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getAlertType()).isEqualTo("CHAIN_BROKEN");
    }

    // ── processResults: escalation ────────────────────────────────────────────

    @Test
    @DisplayName("Level escalation from WARNING to HIGH resets ACK and re-notifies")
    void processResults_levelEscalation_resetsAckAndNotifies() {
        String domain = "escalate.example.com";
        AlertEvent existing = existingOpenAlert(domain, "EXPIRY", "WARNING", false);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(existing));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByMinAlertLevelInAndActiveTrue(List.of("WARNING", "HIGH")))
                .thenReturn(List.of(contact("mgr@test.com", "MANAGER", "HIGH")));

        // Now only 10 days left → HIGH
        service.processResults(List.of(expiryResult(domain, 10, true)));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo).save(captor.capture());
        AlertEvent saved = captor.getValue();
        assertThat(saved.getAlertLevel()).isEqualTo("HIGH");
        assertThat(saved.getAcknowledged()).isFalse();
        verify(emailService).sendAlert(any(String[].class), contains("YÜKSEK"), anyString(), any(), any(), any(), any(), any());
    }

    // ── processResults: re-alert ──────────────────────────────────────────────

    @Test
    @DisplayName("Unacknowledged alert past re-alert interval fires re-alert")
    void processResults_unacknowledgedPastInterval_reAlerts() {
        String domain = "renotify.example.com";
        // Use yesterday's noon UTC — always a different calendar day regardless of when the test runs
        String yesterdayNoon = ISO.format(Instant.now().minus(1, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS));
        AlertEvent existing = existingOpenAlert(domain, "EXPIRY", "WARNING", false);
        existing.setLastReAlertAt(yesterdayNoon);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(existing));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING"))
                .thenReturn(List.of(contact("po@test.com", "PO", "WARNING")));

        service.processResults(List.of(expiryResult(domain, 25, true)));

        verify(emailService).sendAlert(any(String[].class), contains("[RE-ALERT]"), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Unacknowledged alert within re-alert interval is not re-sent")
    void processResults_unacknowledgedWithinInterval_noReAlert() {
        String domain = "quiet.example.com";
        // Use today's noon UTC — always on the same calendar day regardless of when the test runs
        String todayNoon = ISO.format(Instant.now().truncatedTo(ChronoUnit.DAYS).plus(12, ChronoUnit.HOURS));
        AlertEvent existing = existingOpenAlert(domain, "EXPIRY", "WARNING", false);
        existing.setLastReAlertAt(todayNoon);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(existing));

        service.processResults(List.of(expiryResult(domain, 25, true)));

        verify(alertEventRepo, never()).save(any());
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Acknowledged alert is not re-sent even after interval")
    void processResults_acknowledgedAlert_noReAlert() {
        String domain = "acked.example.com";
        AlertEvent existing = existingOpenAlert(domain, "EXPIRY", "WARNING", true); // acked
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(existing));

        service.processResults(List.of(expiryResult(domain, 25, true)));

        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    // ── processResults: resolution ────────────────────────────────────────────

    @Test
    @DisplayName("Cert returning to OK resolves open EXPIRY alert")
    void processResults_certOk_resolvesOpenAlert() {
        String domain = "recovered.example.com";
        AlertEvent existing = existingOpenAlert(domain, "EXPIRY", "WARNING", false);
        when(alertEventRepo.findByDomainAndResolvedFalse(domain)).thenReturn(List.of(existing));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Now cert is fine
        service.processResults(List.of(okResult(domain)));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo).save(captor.capture());
        assertThat(captor.getValue().getResolved()).isTrue();
        assertThat(captor.getValue().getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("Cert with no alert type does not fire any notification")
    void processResults_certOkNoExistingAlert_noAction() {
        String domain = "healthy.example.com";
        when(alertEventRepo.findByDomainAndResolvedFalse(domain)).thenReturn(List.of());

        service.processResults(List.of(okResult(domain)));

        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any());
        verify(alertEventRepo, never()).save(any());
    }

    @Test
    @DisplayName("Cert returning to OK resolves open CHAIN_BROKEN alert (not just EXPIRY)")
    void processResults_chainBrokenResolved_whenCertHealthy() {
        String domain = "renewed-chain.example.com";
        AlertEvent existing = existingOpenAlert(domain, "CHAIN_BROKEN", "CRITICAL", false);
        when(alertEventRepo.findByDomainAndResolvedFalse(domain)).thenReturn(List.of(existing));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(latestCheckRepo.findById(domain)).thenReturn(Optional.empty());

        service.processResults(List.of(okResult(domain)));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo).save(captor.capture());
        assertThat(captor.getValue().getResolved()).isTrue();
        assertThat(captor.getValue().getAlertType()).isEqualTo("CHAIN_BROKEN");
        assertThat(captor.getValue().getResolvedBy()).isEqualTo("system");
    }

    @Test
    @DisplayName("Cert returning to OK resolves open REVOKED alert (not just EXPIRY)")
    void processResults_revokedAlertResolved_whenCertHealthy() {
        String domain = "renewed-revoked.example.com";
        AlertEvent existing = existingOpenAlert(domain, "REVOKED", "CRITICAL", false);
        when(alertEventRepo.findByDomainAndResolvedFalse(domain)).thenReturn(List.of(existing));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(latestCheckRepo.findById(domain)).thenReturn(Optional.empty());

        service.processResults(List.of(okResult(domain)));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo).save(captor.capture());
        assertThat(captor.getValue().getResolved()).isTrue();
        assertThat(captor.getValue().getAlertType()).isEqualTo("REVOKED");
        assertThat(captor.getValue().getResolvedBy()).isEqualTo("system");
    }

    // ── acknowledge / resolve ─────────────────────────────────────────────────

    @Test
    @DisplayName("acknowledge sets acknowledged flag and acknowledgedBy")
    void acknowledge_setsFields() {
        AlertEvent event = existingOpenAlert("d.example.com", "EXPIRY", "WARNING", false);
        event.setId(1L);
        when(alertEventRepo.findById(1L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AlertEvent result = service.acknowledge(1L, "john.doe");

        assertThat(result.getAcknowledged()).isTrue();
        assertThat(result.getAcknowledgedBy()).isEqualTo("john.doe");
        assertThat(result.getAcknowledgedAt()).isNotNull();
    }

    @Test
    @DisplayName("resolve sets resolved flag and resolvedAt timestamp")
    void resolve_setsFields() {
        AlertEvent event = existingOpenAlert("d.example.com", "EXPIRY", "WARNING", false);
        event.setId(2L);
        when(alertEventRepo.findById(2L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AlertEvent result = service.resolve(2L, "test-user");

        assertThat(result.getResolved()).isTrue();
        assertThat(result.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("acknowledge with unknown ID throws NoSuchElementException")
    void acknowledge_unknownId_throws() {
        when(alertEventRepo.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.acknowledge(999L, "anyone"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("resolve sends resolution notification email to contacts")
    void resolve_sendsResolutionNotification() {
        AlertEvent event = existingOpenAlert("notify.example.com", "EXPIRY", "WARNING", false);
        event.setId(3L);
        when(alertEventRepo.findById(3L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING"))
                .thenReturn(List.of(contact("po@test.com", "PO", "WARNING")));
        when(latestCheckRepo.findById("notify.example.com")).thenReturn(Optional.empty());

        service.resolve(3L, "test-user");

        verify(emailService).sendResolutionAlert(
                any(String[].class), contains("ÇÖZÜLDÜ"),
                eq("notify.example.com"), eq("EXPIRY"), eq("WARNING"),
                any(), eq("test-user"), any(), any(), isNull());
    }

    @Test
    @DisplayName("resolve with blank resolvedBy defaults to 'admin'")
    void resolve_blankResolvedBy_defaultsToAdmin() {
        AlertEvent event = existingOpenAlert("d.example.com", "EXPIRY", "WARNING", false);
        event.setId(4L);
        when(alertEventRepo.findById(4L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AlertEvent result = service.resolve(4L, "   "); // blank

        assertThat(result.getResolvedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("closeAlertsOnInventoryDelete: silently resolves open alerts without sending email")
    void closeAlertsOnInventoryDelete_silentClose_noEmail() {
        String domain = "decommissioned.example.com";
        AlertEvent chainAlert  = existingOpenAlert(domain, "CHAIN_BROKEN", "CRITICAL", false);
        AlertEvent expiryAlert = existingOpenAlert(domain, "EXPIRY", "WARNING", false);
        when(alertEventRepo.findByDomainAndResolvedFalse(domain))
                .thenReturn(List.of(chainAlert, expiryAlert));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int closed = service.closeAlertsOnInventoryDelete(domain);

        assertThat(closed).isEqualTo(2);
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, times(2)).save(captor.capture());
        for (AlertEvent saved : captor.getAllValues()) {
            assertThat(saved.getResolved()).isTrue();
            assertThat(saved.getResolvedBy()).isEqualTo("inventory_delete");
            assertThat(saved.getResolvedAt()).isNotNull();
        }
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("closeAlertsOnInventoryDelete: returns 0 when no open alerts")
    void closeAlertsOnInventoryDelete_noOpenAlerts_returnsZero() {
        when(alertEventRepo.findByDomainAndResolvedFalse("clean.example.com"))
                .thenReturn(List.of());

        int closed = service.closeAlertsOnInventoryDelete("clean.example.com");

        assertThat(closed).isZero();
        verify(alertEventRepo, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("catchUpAlertsOnDeletedDomains: silently closes stuck alerts on soft-deleted domains")
    void catchUpAlertsOnDeletedDomains_closesStuckAlerts_silently() {
        AlertEvent stuck1 = existingOpenAlert("legacy1.example.com", "EXPIRY", "HIGH", false);
        AlertEvent stuck2 = existingOpenAlert("legacy2.example.com", "CHAIN_BROKEN", "CRITICAL", false);
        when(alertEventRepo.findOpenAlertsOnSoftDeletedDomains())
                .thenReturn(List.of(stuck1, stuck2));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int closed = service.catchUpAlertsOnDeletedDomains();

        assertThat(closed).isEqualTo(2);
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, times(2)).save(captor.capture());
        for (AlertEvent saved : captor.getAllValues()) {
            assertThat(saved.getResolved()).isTrue();
            assertThat(saved.getResolvedBy()).isEqualTo("inventory_delete");
            assertThat(saved.getResolvedAt()).isNotNull();
        }
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("catchUpAlertsOnDeletedDomains: returns 0 when nothing stuck (no-op)")
    void catchUpAlertsOnDeletedDomains_noStuck_noOp() {
        when(alertEventRepo.findOpenAlertsOnSoftDeletedDomains()).thenReturn(List.of());

        int closed = service.catchUpAlertsOnDeletedDomains();

        assertThat(closed).isZero();
        verify(alertEventRepo, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("processResults: cert returning to OK sends auto-resolution email")
    void processResults_autoResolve_sendsResolutionEmail() {
        String domain = "recovered2.example.com";
        AlertEvent existing = existingOpenAlert(domain, "EXPIRY", "WARNING", false);
        when(alertEventRepo.findByDomainAndResolvedFalse(domain)).thenReturn(List.of(existing));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING"))
                .thenReturn(List.of(contact("po@test.com", "PO", "WARNING")));
        when(latestCheckRepo.findById(domain)).thenReturn(Optional.empty());

        service.processResults(List.of(okResult(domain)));

        verify(emailService).sendResolutionAlert(
                any(String[].class), contains("ÇÖZÜLDÜ"),
                eq(domain), any(), any(), any(), eq("Sistem (otomatik)"), any(), any(), isNull());
    }

    // ── Webhook ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Contact with webhookUrl triggers webhook send")
    void processResults_contactWithWebhook_triggersWebhook() {
        String domain = "webhook.example.com";
        EscalationContact c = contact("dev@test.com", "TECH", "WARNING");
        c.setWebhookUrl("https://teams.example.com/webhook");
        c.setWebhookType("TEAMS");
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING")).thenReturn(List.of(c));
        when(alertEventRepo.findOpenAlert(domain, "EXPIRY")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processResults(List.of(expiryResult(domain, 25, true)));

        verify(webhookService).send(eq("TEAMS"), eq("https://teams.example.com/webhook"),
                anyString(), anyString(), eq("WARNING"));
    }

    // ── reNotify (async) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("reNotify: open alert returns queued status + dispatches async email")
    void reNotify_openAlert_returnsQueuedAndDispatches() {
        AlertEvent event = existingOpenAlert("queued.example.com", "EXPIRY", "WARNING", false);
        event.setId(101L);
        when(alertEventRepo.findById(101L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING"))
                .thenReturn(List.of(contact("po@test.com", "PO", "WARNING")));
        when(latestCheckRepo.findById("queued.example.com")).thenReturn(Optional.empty());

        Map<String, Object> result = service.reNotify(101L);

        // Returns immediately with queued status
        assertThat(result.get("alert_id")).isEqualTo(101L);
        assertThat(result.get("status")).isEqualTo("queued");
        assertThat(result.get("contacts_queued")).isEqualTo(1);
        assertThat(result.get("recipients_queued")).isEqualTo(1);
        // No "notifications" or "alert" entity in response (lightweight payload)
        assertThat(result).doesNotContainKey("notifications");
        assertThat(result).doesNotContainKey("alert");

        // DB write committed before dispatch (notifiedContacts + lastReAlertAt persisted)
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        AlertEvent saved = captor.getValue();
        assertThat(saved.getLastReAlertAt()).isNotNull();
        assertThat(saved.getNotifiedContacts()).contains("po@test.com");

        // Async path executed sync (self-injection bypass) — email was sent with [RE-ALERT] subject
        verify(emailService).sendAlert(any(String[].class), contains("[RE-ALERT]"),
                anyString(), eq("queued.example.com"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reNotify: resolved alert throws IllegalStateException, no email sent")
    void reNotify_resolvedAlert_throws() {
        AlertEvent event = existingOpenAlert("resolved.example.com", "EXPIRY", "WARNING", false);
        event.setId(102L);
        event.setResolved(true);
        when(alertEventRepo.findById(102L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.reNotify(102L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already resolved");

        verify(alertEventRepo, never()).save(any());
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reNotify: unknown alert ID throws NoSuchElementException")
    void reNotify_unknownAlert_throws() {
        when(alertEventRepo.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reNotify(999L))
                .isInstanceOf(NoSuchElementException.class);

        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reNotify: HIGH alert sends [RE-ALERT] [CertMonitor YÜKSEK] subject")
    void reNotify_highAlert_subjectFormat() {
        AlertEvent event = existingOpenAlert("high.example.com", "EXPIRY", "HIGH", false);
        event.setId(103L);
        event.setDaysRemaining(10);
        when(alertEventRepo.findById(103L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByMinAlertLevelInAndActiveTrue(List.of("WARNING", "HIGH")))
                .thenReturn(List.of(contact("mgr@test.com", "MANAGER", "HIGH")));

        Map<String, Object> result = service.reNotify(103L);

        assertThat(result.get("status")).isEqualTo("queued");
        verify(emailService).sendAlert(any(String[].class),
                contains("[RE-ALERT] [CertMonitor YÜKSEK] high.example.com"),
                anyString(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reNotify: no contacts available — still updates DB, no email")
    void reNotify_noContacts_dbStillUpdatedNoEmail() {
        AlertEvent event = existingOpenAlert("orphan.example.com", "EXPIRY", "WARNING", false);
        event.setId(104L);
        when(alertEventRepo.findById(104L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING")).thenReturn(List.of());

        Map<String, Object> result = service.reNotify(104L);

        assertThat(result.get("contacts_queued")).isEqualTo(0);
        assertThat(result.get("recipients_queued")).isEqualTo(0);
        assertThat(result.get("status")).isEqualTo("queued");
        verify(alertEventRepo, atLeast(1)).save(any());
        // No contacts AND no team emails — sendCombinedAlert returns empty without calling emailService
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reNotify: no contacts but team email present — recipients_queued reflects team emails")
    void reNotify_noContactsButTeamEmail_countsRecipients() {
        AlertEvent event = existingOpenAlert("teamonly.example.com", "EXPIRY", "WARNING", false);
        event.setId(105L);
        when(alertEventRepo.findById(105L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING")).thenReturn(List.of());

        com.certmonitor.model.CertificateInventory inv = new com.certmonitor.model.CertificateInventory();
        inv.setTeamId(7L);
        when(inventoryRepo.findByDomain("teamonly.example.com")).thenReturn(Optional.of(inv));

        com.certmonitor.model.Team team = new com.certmonitor.model.Team();
        team.setId(7L);
        team.setName("Platform");
        team.setEmail("team@example.com");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team));

        Map<String, Object> result = service.reNotify(105L);

        assertThat(result.get("recipients_queued")).isEqualTo(1);
        assertThat(result.get("contacts_queued")).isEqualTo(0);
        assertThat(result.get("status")).isEqualTo("queued");
        // Mail actually sent (combined alert with team email as TO)
        verify(emailService).sendAlert(any(String[].class), anyString(), anyString(),
                eq("teamonly.example.com"), any(), any(), any(), any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AlertThreshold defaultThreshold() {
        AlertThreshold t = new AlertThreshold();
        t.setId(1L);
        t.setWarningDays(30);
        t.setHighDays(15);
        t.setCriticalDays(7);
        t.setReAlertIntervalHours(24);
        t.setActive(true);
        return t;
    }

    private EscalationContact contact(String email, String role, String minLevel) {
        EscalationContact c = new EscalationContact();
        c.setName("Test " + role);
        c.setEmail(email);
        c.setRole(role);
        c.setMinAlertLevel(minLevel);
        c.setActive(true);
        return c;
    }

    private Map<String, Object> expiryResult(String domain, int days, boolean warning) {
        return new java.util.LinkedHashMap<>(Map.of(
                "domain", domain,
                "status", warning ? "warning" : "valid",
                "warning", warning,
                "days_remaining", days,
                "revocation_status", "VALID",
                "chain_status", "VALID",
                "deployment_status", "OK"
        ));
    }

    private Map<String, Object> okResult(String domain) {
        return new java.util.LinkedHashMap<>(Map.of(
                "domain", domain,
                "status", "valid",
                "warning", false,
                "days_remaining", 120,
                "revocation_status", "VALID",
                "chain_status", "VALID",
                "deployment_status", "OK"
        ));
    }

    private AlertEvent existingOpenAlert(String domain, String type, String level, boolean acked) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setAcknowledged(acked);
        e.setResolved(false);
        e.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        e.setLastReAlertAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        return e;
    }
}
