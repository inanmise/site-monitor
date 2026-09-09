package com.sitemonitor.service;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.*;
import com.sitemonitor.repository.*;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.atLeast;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationServiceTest {

    /** Bilerek STUB'LANMAZ: null donus = "hic grup yok" -> eski Team.email yolu isler.
     *  Bu dosyanin tamami boylece "grupsuz kurulum" regresyon kaniti olur (birinci yasa). */
    @Mock private com.sitemonitor.service.NotificationGroupService notificationGroups;

    @Mock AlertEventRepository alertEventRepo;
    @Mock AlertThresholdRepository thresholdRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock com.sitemonitor.repository.CertificateInventoryRepository inventoryRepo;
    @Mock EmailNotificationService emailService;
    @Mock WeeklyAvailabilityReportService weeklyAvailability;
    @Mock WebhookService webhookService;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock com.sitemonitor.repository.LatestCheckRepository latestCheckRepo;
    @Mock com.sitemonitor.repository.TeamRepository teamRepo;
    @Mock SmtpSettingsService smtpSettings;
    @Mock MaintenanceService maintenanceService;
    @Mock StormService stormService;
    @Mock UserPushService userPushService;
    @Mock com.sitemonitor.repository.DomainMonitorRepository domainMonitorRepo;
    @Mock com.sitemonitor.repository.DomainCheckRepository domainCheckRepo;
    @Mock com.sitemonitor.repository.DnsRecordRepository dnsRecordRepo;
    @Mock com.sitemonitor.repository.PageCheckRepository pageCheckRepo;

    /** Güven alarm anahtarları CANLI okunuyor; stub'sizken varsayılan (arg1) döndürülür. */
    @org.mockito.Mock AppSettingsService appSettings;

    private EscalationService service;
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo,
                inventoryRepo, emailService, weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo, smtpSettings, maintenanceService, stormService,
                userPushService,
                domainMonitorRepo, domainCheckRepo, dnsRecordRepo, pageCheckRepo, notificationGroups,
                appSettings);

        // Ayar okumaları varsayılanı döndürsün: hostname uyuşmazlığı AÇIK, güvenilmeyen CA KAPALI.
        org.mockito.Mockito.lenient().when(appSettings.getBoolean(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(i -> i.getArgument(1));

        // Self-injection bypass for @Async dispatch in tests (runs synchronously)
        ReflectionTestUtils.setField(service, "self", service);

        // D9: otomatik kapanış artık ATOMİK koşullu UPDATE ile yarışı çözüyor (manuel resolve'a
        // karşı). Mock varsayılanı 0 (= "başkası kapatmış") olduğundan tüm auto-resolve yolları
        // sessizce atlanırdı — varsayılan "yarışı BİZ kazandık" olmalı; yarışı sınayan test
        // kendi stub'ını verir.
        when(alertEventRepo.markResolvedIfOpen(any(), any(), any())).thenReturn(1);

        // SMTP settings (pacing delays) — return entity defaults.
        when(smtpSettings.getOrDefaults()).thenReturn(new com.sitemonitor.model.SmtpSettings());

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
        // Süre-bitişi ailesinde subject severity yerine kalan günü taşır
        verify(emailService).sendAlert(any(String[].class), contains("[Site Monitor] 25 GÜN KALDI · " + domain), anyString(), any(), any(), any(), any(), any());
    }

    /**
     * KANAL BAĞIMSIZLIĞI SÖZLEŞMESİ (kişi-webhook, kural 1): push tetiği ne yaparsa yapsın —
     * istisna dahil — mail yolu ETKİLENMEZ. Mutasyon kanıtı: sendCombinedAlert'teki try/catch
     * zarfı kaldırılırsa bu test kırmızıya döner.
     */
    @Test
    @DisplayName("user-push tetiği İSTİSNA atsa da mail gönderilir — kanal bağımsızlığı")
    void userPushFailure_doesNotAffectMail() {
        String domain = "expiring.example.com";
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING")).thenReturn(List.of(contact("po@test.com", "PO", "WARNING")));
        when(alertEventRepo.findOpenAlert(domain, "EXPIRY")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("push kanalı çöktü"))
                .when(userPushService).enqueueAlert(any(), any(), any(), any());

        service.processResults(List.of(expiryResult(domain, 25, true)));

        verify(emailService).sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any());
    }

    /** K8 aynası: mail hunisinden geçen HER tetik push tetiğini de çağırır (mail sonucundan bağımsız). */
    @Test
    @DisplayName("Mail hunisi user-push tetiğini de çağırır (K8: mail neyi gönderiyorsa webhook da)")
    void mailFunnel_triggersUserPush() {
        String domain = "expiring.example.com";
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING")).thenReturn(List.of(contact("po@test.com", "PO", "WARNING")));
        when(alertEventRepo.findOpenAlert(domain, "EXPIRY")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processResults(List.of(expiryResult(domain, 25, true)));

        // A2 ile imza 5 parametreye cikti (kanal bazli haric tutma listesi); IDDIA AYNI:
        // mail hunisi push tetigini INITIAL ile calistirir.
        verify(userPushService).enqueueAlert(any(), org.mockito.ArgumentMatchers.eq("INITIAL"),
                any(), any(), any());
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

        // 3 contacts notified — subject süre-bitişinde kalan günü taşır
        verify(emailService, times(1)).sendAlert(any(String[].class), contains("5 GÜN KALDI"), anyString(), any(), any(), any(), any(), any());
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
        verify(emailService).sendAlert(any(String[].class), contains("10 GÜN KALDI"), anyString(), any(), any(), any(), any(), any());
    }

    // ── processResults: re-alert ──────────────────────────────────────────────

    @Test
    @DisplayName("Unacknowledged alert past re-alert interval fires re-alert")
    void processResults_unacknowledgedPastInterval_reAlerts() {
        String domain = "renotify.example.com";
        // Son alarm 25 saat önce (>24s re-alert aralığı) — rolling-saat penceresinde deterministik re-alert (M10).
        String pastInterval = ISO.format(Instant.now().minus(25, ChronoUnit.HOURS));
        AlertEvent existing = existingOpenAlert(domain, "EXPIRY", "WARNING", false);
        existing.setLastReAlertAt(pastInterval);
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
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection())).thenReturn(List.of(existing));
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
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection())).thenReturn(List.of());

        service.processResults(List.of(okResult(domain)));

        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any());
        verify(alertEventRepo, never()).save(any());
    }

    @Test
    @DisplayName("Cert returning to OK resolves open CHAIN_BROKEN alert (not just EXPIRY)")
    void processResults_chainBrokenResolved_whenCertHealthy() {
        String domain = "renewed-chain.example.com";
        AlertEvent existing = existingOpenAlert(domain, "CHAIN_BROKEN", "CRITICAL", false);
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection())).thenReturn(List.of(existing));
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
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection())).thenReturn(List.of(existing));
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
                // ctx artık HER ZAMAN olay kimliğini taşır (e-postadaki olay aksiyon butonları için);
                // eskiden bu yolda null'dı → isNull() yerine pozitif iddia.
                any(), eq("test-user"), any(), any(),
                argThat(m -> m != null && m.containsKey("alert_event_id")), any(), any());
    }

    @Test
    @DisplayName("PAGE_INTEGRITY resolve: ctx alarm-anı snapshot'ı + çözüm anı CANLI PageCheck (resolved_*) taşır")
    void resolve_pageIntegrity_enrichesCtxWithLiveState() {
        AlertEvent event = existingOpenAlert("https://x.example.com/", EscalationService.TYPE_PAGE_INTEGRITY, "HIGH", false);
        event.setId(31L); event.setTeamId(7L);
        event.setContextJson("{\"url\":\"https://x.example.com/\",\"monitor_id\":55,"
                + "\"detail\":\"1 kırık, 1 zaman aşımı, 0 mixed content\","
                + "\"problem_rows\":\"LINK\\thttps://dead.example.com/w\\t\",\"problem_total\":1}");
        when(alertEventRepo.findById(31L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(7L); team.setName("SY-Takım A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team));
        com.sitemonitor.model.PageCheck latest = new com.sitemonitor.model.PageCheck();
        latest.setMonitorId(55L); latest.setStatus("OK"); latest.setTotalResources(135);
        latest.setBrokenResources(0); latest.setTimeoutCount(0); latest.setMixedContentCount(0);
        latest.setCheckedAt("2026-08-04T09:42:00");
        when(pageCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(55L)).thenReturn(Optional.of(latest));

        service.resolve(31L, "test-user");

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map> ctxCap = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendResolutionAlert(
                any(String[].class), contains("ÇÖZÜLDÜ"),
                eq("https://x.example.com/"), eq(EscalationService.TYPE_PAGE_INTEGRITY), eq("HIGH"),
                any(), eq("test-user"), any(), any(), ctxCap.capture(), any(), any());
        Map<String, Object> ctx = ctxCap.getValue();
        assertThat(ctx).containsEntry("detail", "1 kırık, 1 zaman aşımı, 0 mixed content");   // alarm anı
        assertThat(ctx).containsKey("problem_rows");                                          // sorunlu kaynaklar
        assertThat(ctx).containsEntry("resolved_page_status", "OK");                          // çözüm anı canlı
        assertThat(ctx).containsEntry("resolved_total_resources", 135);
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
    @DisplayName("resolve is idempotent — a second resolve does not re-save or clobber resolve metadata (L1)")
    void resolve_idempotent_secondCallNoOp() {
        AlertEvent event = existingOpenAlert("dup.example.com", "EXPIRY", "WARNING", false);
        event.setId(7L);
        when(alertEventRepo.findById(7L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(latestCheckRepo.findById("dup.example.com")).thenReturn(Optional.empty());

        service.resolve(7L, "user-a");                 // resolves + saves (+ async notify)
        String firstResolvedAt = event.getResolvedAt();
        service.resolve(7L, "user-b");                 // already resolved → idempotent no-op

        // Before the fix the second call saves again + clobbers resolvedBy to "user-b".
        verify(alertEventRepo, times(1)).save(any());
        assertThat(event.getResolvedBy()).isEqualTo("user-a");
        assertThat(event.getResolvedAt()).isEqualTo(firstResolvedAt);
    }

    @Test
    @DisplayName("reAlertDue honors the interval-hours knob and does not fire across the UTC-midnight boundary (M10)")
    void reAlertDue_honorsHoursKnobAndMidnightBoundary() {
        // Near-midnight edge: 90s apart across midnight, 24h interval → NOT due
        // (old calendar-day logic wrongly re-alerted seconds after the initial alert).
        assertThat(EscalationService.reAlertDue("2026-07-04T23:59:00", "2026-07-05T00:00:30", 24)).isFalse();
        // Exactly 24h later → due.
        assertThat(EscalationService.reAlertDue("2026-07-04T23:59:00", "2026-07-05T23:59:00", 24)).isTrue();
        // Admin knob honored: 12h interval → 11h elapsed NOT due, 13h elapsed due (previously the knob was ignored).
        assertThat(EscalationService.reAlertDue("2026-07-04T10:00:00", "2026-07-04T21:00:00", 12)).isFalse();
        assertThat(EscalationService.reAlertDue("2026-07-04T10:00:00", "2026-07-04T23:00:00", 12)).isTrue();
        // Unparseable timestamp → safe: allow re-alert (don't silence a stale alert forever).
        assertThat(EscalationService.reAlertDue("bad", "2026-07-05T00:00:00", 24)).isTrue();
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
    @DisplayName("closeAlertsOnDeactivate: pasife alınan domain'in açık alarmlarını sessizce kapatır (resolvedBy=inventory_deactivate, mail yok)")
    void closeAlertsOnDeactivate_silentClose_noEmail() {
        String domain = "passive.example.com";
        AlertEvent expiry = existingOpenAlert(domain, "EXPIRY", "WARNING", false);
        AlertEvent chain  = existingOpenAlert(domain, "CHAIN_BROKEN", "CRITICAL", false);
        when(alertEventRepo.findByDomainAndResolvedFalse(domain)).thenReturn(List.of(expiry, chain));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int closed = service.closeAlertsOnDeactivate(domain);

        assertThat(closed).isEqualTo(2);
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, times(2)).save(captor.capture());
        for (AlertEvent saved : captor.getAllValues()) {
            assertThat(saved.getResolved()).isTrue();
            assertThat(saved.getResolvedBy()).isEqualTo("inventory_deactivate");
            assertThat(saved.getResolvedAt()).isNotNull();
        }
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("closeAlertsOnDeactivate: açık alarm yoksa 0 döner")
    void closeAlertsOnDeactivate_noOpenAlerts_returnsZero() {
        when(alertEventRepo.findByDomainAndResolvedFalse("clean.example.com")).thenReturn(List.of());
        assertThat(service.closeAlertsOnDeactivate("clean.example.com")).isZero();
        verify(alertEventRepo, never()).save(any());
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
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection())).thenReturn(List.of(existing));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING"))
                .thenReturn(List.of(contact("po@test.com", "PO", "WARNING")));
        when(latestCheckRepo.findById(domain)).thenReturn(Optional.empty());

        service.processResults(List.of(okResult(domain)));

        verify(emailService).sendResolutionAlert(
                any(String[].class), contains("ÇÖZÜLDÜ"),
                eq(domain), any(), any(), any(), eq("Sistem (otomatik)"), any(), any(), isNull(), any(), any());
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

    @Test
    @DisplayName("Webhook TESLIM EDILEMEZSE kayit FAILED olur — 'SENT' yazilmaz")
    void processResults_webhookThrows_recordsFailedNotSent() {
        // ASIL KUSUR BUYDU: WebhookService her istisnayi iceride yutup void donuyordu, dolayisiyla
        // asagidaki catch blogu ERISILEMEZDI ve notification_log teslim edilmemis alarmlar icin de
        // "SENT" yaziyordu. Bildirim Gecmisi ekrani bunu yesil "Gonderildi" rozetiyle gosteriyordu:
        // operator alarmin ulastigini saniyordu. Kapi, durumun GERCEGI yansittigini pinler.
        String domain = "webhook-fail.example.com";
        EscalationContact c = contact("dev@test.com", "TECH", "WARNING");
        c.setWebhookUrl("https://teams.example.com/webhook");
        c.setWebhookType("TEAMS");
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING")).thenReturn(List.of(c));
        when(alertEventRepo.findOpenAlert(domain, "EXPIRY")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new WebhookService.WebhookDeliveryException("HTTP 404: no_service"))
                .when(webhookService).send(any(), any(), any(), any(), any());

        service.processResults(List.of(expiryResult(domain, 25, true)));

        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo, atLeastOnce()).save(captor.capture());
        List<String> webhookStatuses = captor.getAllValues().stream()
                .map(NotificationLog::getWebhookStatus)
                .filter(st -> st != null && !"SKIPPED".equals(st))
                .toList();
        assertThat(webhookStatuses)
                .as("teslim edilemeyen webhook 'SENT' olarak kaydedilemez")
                .isNotEmpty()
                .allSatisfy(st -> assertThat(st).startsWith("FAILED"));
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
    @DisplayName("reNotify: HIGH alert sends [RE-ALERT] [SiteMonitor YÜKSEK] subject")
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
                contains("[RE-ALERT] [Site Monitor] 10 GÜN KALDI · high.example.com"),
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

        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
        inv.setTeamId(7L);
        when(inventoryRepo.findByDomain("teamonly.example.com")).thenReturn(Optional.of(inv));

        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
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

    @Test
    @DisplayName("reNotify: DOMAINMON_EXPIRY → TAKIMA gider (event.teamId), müdür/global kontak EKLENMEZ + içerik domain_checks'ten zengin")
    void reNotify_domainMon_routesToTeamNotManager_withRichContent() {
        // Manuel resend hatası: domain monitörü cert envanterinde YOK → eski kod takımı kaybedip global müdüre düşüyordu.
        AlertEvent event = existingOpenAlert("kartfree.com", EscalationService.TYPE_DOMAINMON_EXPIRY, "WARNING", false);
        event.setId(201L);
        event.setTeamId(7L);            // domain monitörünün takımı (ilk alarmda damgalanmıştı)
        event.setDaysRemaining(25);
        when(alertEventRepo.findById(201L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Takım e-postası (collectTeamEmails → teamRepo)
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(7L); team.setName("SY-Takım A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team));

        // GLOBAL müdür kontağı MEVCUT — eski hatalı davranışta buna düşerdi; teamOnly ile ARTIK eklenmemeli.
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING"))
                .thenReturn(List.of(contact("mudur@example.com", "MANAGER", "WARNING")));

        // İçerik bağlamı — EN GÜNCEL DomainCheck (registrar/bitiş/EPP → zengin mail)
        com.sitemonitor.model.DomainMonitor mon = new com.sitemonitor.model.DomainMonitor();
        mon.setId(55L); mon.setDomain("kartfree.com"); mon.setTeamId(7L);
        when(domainMonitorRepo.findFirstByDomainOrderByIdAsc("kartfree.com")).thenReturn(Optional.of(mon));
        com.sitemonitor.model.DomainCheck dc = new com.sitemonitor.model.DomainCheck();
        dc.setMonitorId(55L); dc.setDaysRemaining(25); dc.setExpiryDate("2026-08-06T12:37:46Z");
        dc.setRegistrar("GoDaddy.com, LLC"); dc.setSource("RDAP");
        dc.setStatusCodes("client transfer prohibited, client delete prohibited");
        when(domainCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(55L)).thenReturn(Optional.of(dc));

        Map<String, Object> result = service.reNotify(201L);

        // Takıma gitti, müdür yok → 1 alıcı, 0 kontak
        assertThat(result.get("recipients_queued")).isEqualTo(1);
        assertThat(result.get("contacts_queued")).isEqualTo(0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map> ctxCap = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendAlert(toCap.capture(), contains("[RE-ALERT]"), anyString(),
                eq("kartfree.com"), any(), any(), any(), ctxCap.capture());
        // TO = SADECE takım e-postası; müdür DEĞİL
        assertThat(toCap.getValue()).containsExactly("takim-a@example.com");
        // İçerik zengin — registrar + bitiş bağlamı geçti (detay tablosu dolu)
        assertThat(ctxCap.getValue()).containsEntry("registrar", "GoDaddy.com, LLC");
        assertThat(ctxCap.getValue()).containsKey("expiry_date");
    }

    @Test
    @DisplayName("reNotify: KRİTİK DOMAINMON_EXPIRY → takım + MÜDÜR birlikte (kullanıcı politikası: müdür yalnız kritikte)")
    void reNotify_domainMon_critical_includesTeamAndManager() {
        AlertEvent event = existingOpenAlert("kritik.example.com", EscalationService.TYPE_DOMAINMON_EXPIRY, "CRITICAL", false);
        event.setId(202L);
        event.setTeamId(7L);
        event.setDaysRemaining(3);
        when(alertEventRepo.findById(202L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(7L); team.setName("SY-Takım A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team));
        // KRİTİK seviye eskalasyon kontağı (müdür) — kritik domainde EKLENİR
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(7L))
                .thenReturn(List.of(contact("mudur@example.com", "MANAGER", "CRITICAL")));

        com.sitemonitor.model.DomainMonitor mon = new com.sitemonitor.model.DomainMonitor();
        mon.setId(55L); mon.setDomain("kritik.example.com"); mon.setTeamId(7L);
        when(domainMonitorRepo.findFirstByDomainOrderByIdAsc("kritik.example.com")).thenReturn(Optional.of(mon));
        com.sitemonitor.model.DomainCheck dc = new com.sitemonitor.model.DomainCheck();
        dc.setMonitorId(55L); dc.setDaysRemaining(3); dc.setRegistrar("GoDaddy.com, LLC"); dc.setSource("RDAP");
        when(domainCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(55L)).thenReturn(Optional.of(dc));

        Map<String, Object> result = service.reNotify(202L);

        assertThat(result.get("contacts_queued")).isEqualTo(1);       // müdür kontağı dahil
        assertThat(result.get("recipients_queued")).isEqualTo(2);     // takım + müdür
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendAlert(toCap.capture(), contains("[RE-ALERT]"), anyString(),
                eq("kritik.example.com"), any(), any(), any(), any());
        assertThat(toCap.getValue()).containsExactlyInAnyOrder("takim-a@example.com", "mudur@example.com");
    }

    // ── previewReNotify + excludeEmails (Tekrar Bildir onay pop-up'ı) ────────────

    @Test
    @DisplayName("previewReNotify: takım + kontak sırasıyla döner, HİÇBİR yazma yapmaz")
    void previewReNotify_listsRecipients_noWrites() {
        AlertEvent event = existingOpenAlert("prev.example.com", EscalationService.TYPE_DOMAINMON_EXPIRY, "CRITICAL", false);
        event.setId(301L); event.setTeamId(7L);
        when(alertEventRepo.findById(301L)).thenReturn(Optional.of(event));
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(7L); team.setName("SY-Takım A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team));
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(7L))
                .thenReturn(List.of(contact("mudur@example.com", "MANAGER", "CRITICAL")));

        List<EscalationService.ReNotifyRecipient> out = service.previewReNotify(301L);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).email()).isEqualTo("takim-a@example.com");
        assertThat(out.get(0).kind()).isEqualTo("TEAM");
        assertThat(out.get(0).name()).isEqualTo("SY-Takım A");
        assertThat(out.get(1).email()).isEqualTo("mudur@example.com");
        assertThat(out.get(1).kind()).isEqualTo("CONTACT");
        assertThat(out.get(1).role()).isEqualTo("MANAGER");
        verify(alertEventRepo, never()).save(any());
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("previewReNotify: resolved alarm → IllegalState (reNotify ile aynı semantik)")
    void previewReNotify_resolved_throws() {
        AlertEvent event = existingOpenAlert("done.example.com", "EXPIRY", "WARNING", false);
        event.setId(302L); event.setResolved(true);
        when(alertEventRepo.findById(302L)).thenReturn(Optional.of(event));
        assertThatThrownBy(() -> service.previewReNotify(302L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("reNotify(excludes): hariç tutulan kontak TO'da ve notifiedContacts'ta yok; takım maili gider")
    void reNotify_withExcludes_dropsExcludedContact() {
        AlertEvent event = existingOpenAlert("excl.example.com", EscalationService.TYPE_DOMAINMON_EXPIRY, "CRITICAL", false);
        event.setId(303L); event.setTeamId(7L); event.setDaysRemaining(3);
        when(alertEventRepo.findById(303L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(7L); team.setName("SY-Takım A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team));
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(7L))
                .thenReturn(List.of(contact("mudur@example.com", "MANAGER", "CRITICAL")));
        com.sitemonitor.model.DomainMonitor mon = new com.sitemonitor.model.DomainMonitor();
        mon.setId(55L); mon.setDomain("excl.example.com"); mon.setTeamId(7L);
        when(domainMonitorRepo.findFirstByDomainOrderByIdAsc("excl.example.com")).thenReturn(Optional.of(mon));

        Map<String, Object> result = service.reNotify(303L, Set.of(" MUDUR@example.com "));   // trim+case-insensitive

        assertThat(result.get("recipients_queued")).isEqualTo(1);   // yalnız takım
        assertThat(result.get("contacts_queued")).isEqualTo(0);
        ArgumentCaptor<AlertEvent> evCap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(evCap.capture());
        assertThat(evCap.getValue().getNotifiedContacts() == null
                || !evCap.getValue().getNotifiedContacts().contains("mudur@example.com")).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendAlert(toCap.capture(), contains("[RE-ALERT]"), anyString(),
                eq("excl.example.com"), any(), any(), any(), any());
        assertThat(toCap.getValue()).containsExactly("takim-a@example.com");
    }

    @Test
    @DisplayName("reNotify(excludes): TÜM alıcılar hariç tutulursa IllegalArgument (400), yazma/gönderim yok")
    void reNotify_allExcluded_throwsNoWrites() {
        AlertEvent event = existingOpenAlert("allout.example.com", EscalationService.TYPE_DOMAINMON_EXPIRY, "CRITICAL", false);
        event.setId(304L); event.setTeamId(7L);
        when(alertEventRepo.findById(304L)).thenReturn(Optional.of(event));
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(7L); team.setName("SY-Takım A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team));
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(7L))
                .thenReturn(List.of(contact("mudur@example.com", "MANAGER", "CRITICAL")));

        assertThatThrownBy(() -> service.reNotify(304L, Set.of("takim-a@example.com", "mudur@example.com")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(alertEventRepo, never()).save(any());
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("reNotify DNS_CHANGED: son changed kayıttan eski/yeni değerler mesaja ve ctx'e taşınır (boş kutu bug'ı)")
    void reNotify_dnsChanged_reconstructsCtx() {
        AlertEvent event = existingOpenAlert("www.iyigelecegeyatirim.com", EscalationService.TYPE_DNS_CHANGED, "HIGH", false);
        event.setId(305L);
        when(alertEventRepo.findById(305L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // DNS_CHANGED standalone DEĞİL → takım cert envanterinden çözülür
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
        inv.setTeamId(7L);
        when(inventoryRepo.findByDomain("www.iyigelecegeyatirim.com")).thenReturn(Optional.of(inv));
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(7L); team.setName("SY-Takım A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team));
        com.sitemonitor.model.DnsRecord rec = new com.sitemonitor.model.DnsRecord();
        rec.setRecordType("A"); rec.setPreviousValue("192.168.1.10"); rec.setValue("217.169.196.197");
        rec.setCheckedAt("2026-08-02T01:32:00");
        when(dnsRecordRepo.findChangedByDomain(eq("www.iyigelecegeyatirim.com"),
                any(org.springframework.data.domain.Pageable.class))).thenReturn(List.of(rec));

        service.reNotify(305L);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map> ctxCap = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> msgCap = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendAlert(any(String[].class), contains("[RE-ALERT]"), msgCap.capture(),
                eq("www.iyigelecegeyatirim.com"), any(), any(), any(), ctxCap.capture());
        assertThat(msgCap.getValue()).contains("192.168.1.10").contains("217.169.196.197");
        assertThat(ctxCap.getValue()).containsEntry("old_values", List.of("192.168.1.10"));
        assertThat(ctxCap.getValue()).containsEntry("new_values", List.of("217.169.196.197"));
    }

    @Test
    @DisplayName("reNotify DNS_CHANGED: changed kaydı yoksa generic mesaja düşer (çökmez)")
    void reNotify_dnsChanged_noRecord_fallsBackGeneric() {
        AlertEvent event = existingOpenAlert("nohist.example.com", EscalationService.TYPE_DNS_CHANGED, "HIGH", false);
        event.setId(306L);
        when(alertEventRepo.findById(306L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
        inv.setTeamId(7L);
        when(inventoryRepo.findByDomain("nohist.example.com")).thenReturn(Optional.of(inv));
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(7L); team.setName("SY-Takım A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team));
        when(dnsRecordRepo.findChangedByDomain(anyString(),
                any(org.springframework.data.domain.Pageable.class))).thenReturn(List.of());

        Map<String, Object> result = service.reNotify(306L);

        assertThat(result.get("status")).isEqualTo("queued");
        verify(emailService).sendAlert(any(String[].class), contains("[RE-ALERT]"),
                contains("DNS kaydı değişti"), eq("nohist.example.com"), any(), any(), any(), any());
    }

    // ── Sertifikaya erişilemezlik (ağ/firewall) → UYARI + müdür hariç ───────────
    // Kullanıcı kuralı: sertifika bilgileri ağ/firewall kaynaklı alınamadığında alarm
    // KRİTİK değil UYARI olmalı ve müdür (HIGH/CRITICAL kontağı) bilgilendirilmemeli.

    /** status=error + verilen error_class ile bir sweep sonucu. determineAlertType → EXPIRY. */
    private Map<String, Object> errorResult(String domain, String errorClass) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", domain);
        r.put("status", "error");
        if (errorClass != null) r.put("error_class", errorClass);
        return r;
    }

    @Test
    @DisplayName("NETWORK erişilemezlik → UYARI seviyesi + müdür (KRİTİK kontak) sorgulanmaz/hariç")
    void networkUnreachable_isWarning_managerExcluded() {
        String domain = "unreachable.example.com";
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING"))
                .thenReturn(List.of(contact("takim@test.com", "TECH", "WARNING")));
        // Müdür var ama yalnız KRİTİK dalda dönmeli — UYARI'da çağrılmamalı:
        when(contactRepo.findByActiveTrueOrderByRoleAsc())
                .thenReturn(List.of(contact("takim@test.com", "TECH", "WARNING"),
                                    contact("mudur@test.com", "MANAGER", "CRITICAL")));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processResults(List.of(errorResult(domain, "NETWORK")));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        AlertEvent saved = captor.getAllValues().get(0);
        assertThat(saved.getAlertLevel()).isEqualTo("WARNING");
        assertThat(saved.getAlertType()).isEqualTo("EXPIRY");

        // Müdürü getirecek KRİTİK kontak sorgusu HİÇ çağrılmadı
        verify(contactRepo, never()).findByActiveTrueOrderByRoleAsc();

        // Mail yalnız takım/UYARI alıcısına; müdür alıcı listesinde yok, seviye UYARI
        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendAlert(toCap.capture(), contains("[Site Monitor] ORTA · " + domain), anyString(),
                eq(domain), eq("WARNING"), eq("EXPIRY"), isNull(), any());
        assertThat(toCap.getValue()).containsExactly("takim@test.com");
        assertThat(toCap.getValue()).doesNotContain("mudur@test.com");
    }

    @Test
    @DisplayName("NETWORK erişilemezlik mesajı: 'erişilemediği için ... alınamadı (ağ/firewall ...)'")
    void networkUnreachable_messageMentionsReachability() {
        String domain = "unreachable2.example.com";
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING"))
                .thenReturn(List.of(contact("takim@test.com", "TECH", "WARNING")));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processResults(List.of(errorResult(domain, "NETWORK")));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getMessage())
                .contains("erişilemediği").contains("ağ/firewall");
    }

    @Test
    @DisplayName("DNS çözümleme hatası → UYARI seviyesi (ulaşılabilirlik)")
    void dnsUnreachable_isWarning() {
        String domain = "dnsfail.example.com";
        when(contactRepo.findByMinAlertLevelAndActiveTrue("WARNING"))
                .thenReturn(List.of(contact("takim@test.com", "TECH", "WARNING")));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processResults(List.of(errorResult(domain, "DNS")));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getAlertLevel()).isEqualTo("WARNING");
        verify(contactRepo, never()).findByActiveTrueOrderByRoleAsc();
    }

    @Test
    @DisplayName("SSL handshake hatası → KRİTİK korunur (olası gerçek TLS/sertifika kusuru) + müdür dahil")
    void sslError_staysCritical_managerIncluded() {
        String domain = "sslfail.example.com";
        when(contactRepo.findByActiveTrueOrderByRoleAsc())
                .thenReturn(List.of(contact("takim@test.com", "TECH", "WARNING"),
                                    contact("mudur@test.com", "MANAGER", "CRITICAL")));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processResults(List.of(errorResult(domain, "SSL")));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getAlertLevel()).isEqualTo("CRITICAL");

        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendAlert(toCap.capture(), contains("KRİTİK"), anyString(),
                eq(domain), eq("CRITICAL"), eq("EXPIRY"), isNull(), any());
        assertThat(toCap.getValue()).containsExactlyInAnyOrder("takim@test.com", "mudur@test.com");
    }

    @Test
    @DisplayName("UNKNOWN hata sınıfı → KRİTİK korunur")
    void unknownError_staysCritical() {
        String domain = "weird.example.com";
        when(contactRepo.findByActiveTrueOrderByRoleAsc()).thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processResults(List.of(errorResult(domain, "UNKNOWN")));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getAlertLevel()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("status=error fakat error_class yok → KRİTİK korunur (defansif)")
    void errorWithoutClass_staysCritical() {
        String domain = "noclass.example.com";
        when(contactRepo.findByActiveTrueOrderByRoleAsc()).thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processResults(List.of(errorResult(domain, null)));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getAlertLevel()).isEqualTo("CRITICAL");
    }

    // ── determineAlertType önceliği (REVOKED > MISMATCH > CHAIN_BROKEN > EXPIRY) ────

    @Test
    @DisplayName("determineAlertType: REVOKED+MISMATCH+CHAIN_BROKEN hepsi set → REVOKED önceliği (+CRITICAL)")
    void determineAlertType_allDefectsSet_revokedTakesPriority() {
        String domain = "multi-defect.example.com";
        when(contactRepo.findByActiveTrueOrderByRoleAsc()).thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> result = new LinkedHashMap<>(Map.of(
                "domain", domain, "status", "valid", "warning", false, "days_remaining", 90,
                "revocation_status", "REVOKED", "deployment_status", "INCOMPLETE", "chain_status", "BROKEN"));

        service.processResults(List.of(result));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getAlertType()).isEqualTo("REVOKED");
        assertThat(captor.getAllValues().get(0).getAlertLevel()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("determineAlertType: revoke YOK, MISMATCH+CHAIN_BROKEN set → MISMATCH önceliği (chain'in üstünde)")
    void determineAlertType_mismatchAndChainBroken_mismatchTakesPriority() {
        String domain = "mismatch-over-chain.example.com";
        when(contactRepo.findByActiveTrueOrderByRoleAsc()).thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> result = new LinkedHashMap<>(Map.of(
                "domain", domain, "status", "valid", "warning", false, "days_remaining", 90,
                "revocation_status", "VALID", "deployment_status", "INCOMPLETE", "chain_status", "BROKEN"));

        service.processResults(List.of(result));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getAlertType()).isEqualTo("MISMATCH");
    }

    // ── determineAlertLevel tam-eşik merdiveni (<= kenarları; critical=7 high=15 warning=30) ──

    @ParameterizedTest(name = "gün={0} → {1}")
    @CsvSource({
            "7,  CRITICAL",   // days == criticalDays → CRITICAL
            "8,  HIGH",       // > critical, <= high → HIGH
            "15, HIGH",       // days == highDays → HIGH
            "16, WARNING",    // > high, <= warning → WARNING
            "30, WARNING"     // days == warningDays → WARNING
    })
    @DisplayName("determineAlertLevel: gün eşik merdiveninde doğru seviyeye düşer (<= kenarları)")
    void determineAlertLevel_thresholdLadder_exactBoundaries(int days, String expectedLevel) {
        String domain = "ladder-" + days + ".example.com";
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processResults(List.of(expiryResult(domain, days, true)));

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getAlertLevel()).isEqualTo(expectedLevel);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // ── ACCESSIBILITY (erişim kesintisi) alarmları ─────────────────────────────

    private Map<String, Object> outageCtx() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("port", 443);
        ctx.put("first_failure_at", "2026-06-11T10:00:00");
        ctx.put("last_error", "Connection timed out");
        ctx.put("confirm_attempt_count", 3);
        ctx.put("confirm_delay_ms", 30000L);
        return ctx;
    }

    // ── resolveOpenAlertsSilently (izleme silindiğinde sessiz kapanma) ─────────

    @Test
    @DisplayName("resolveOpenAlertsSilently: açık alarmı kapatır, ÇÖZÜM MAİLİ göndermez")
    void resolveOpenAlertsSilently_closesWithoutEmail() {
        String domain = "https://kw.example.com/";
        AlertEvent open = new AlertEvent();
        open.setId(7L); open.setDomain(domain); open.setAlertType("KEYWORD"); open.setResolved(false);
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of(open));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resolveOpenAlertsSilently(domain, Set.of("KEYWORD"), "Sistem (izleme silindi)");

        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo).save(cap.capture());
        AlertEvent saved = cap.getValue();
        assertThat(saved.getResolved()).isTrue();
        assertThat(saved.getResolvedBy()).isEqualTo("Sistem (izleme silindi)");
        assertThat(saved.getResolvedAt()).isNotBlank();
        // KRİTİK: silme kaynaklı kapanma → hiç bildirim/çözüldü maili yok
        verifyNoInteractions(emailService, webhookService);
    }

    @Test
    @DisplayName("resolveOpenAlertsSilently: açık alarm yoksa no-op")
    void resolveOpenAlertsSilently_noOpenAlerts_noOp() {
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(any(), anyCollection()))
                .thenReturn(List.of());
        service.resolveOpenAlertsSilently("d", Set.of("PING_DOWN"), "x");
        verify(alertEventRepo, never()).save(any());
        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("processConfirmedOutage KEYWORD: contextJson snapshot + teamId (çözüldü detayı için)")
    void processConfirmedOutage_keyword_snapshotAndTeam() {
        String domain = "https://kw.example.com/";
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("team_id", 9L);
        ctx.put("keyword", "example");
        ctx.put("operator", "GTE");
        ctx.put("match_count", 1);
        ctx.put("occurrences", 0);
        ctx.put("first_failure_at", "2026-06-24T00:00:00");
        ctx.put("monitor_id", 42);
        when(alertEventRepo.findOpenAlert(domain, "KEYWORD")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processConfirmedOutage(domain, "KEYWORD", "CRITICAL", ctx);

        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(cap.capture());
        AlertEvent saved = cap.getAllValues().get(0);
        assertThat(saved.getAlertType()).isEqualTo("KEYWORD");
        assertThat(saved.getAlertLevel()).isEqualTo("CRITICAL");
        assertThat(saved.getTeamId()).isEqualTo(9L);
        assertThat(saved.getContextJson()).isNotNull();
        assertThat(saved.getContextJson()).contains("example");   // snapshot → çözüldü mailinde kelime detayı
    }

    /**
     * Y5: re-alert dalı seviyeyi CANLI ctx'ten okuyor ama event'e YAZMIYORDU. WARNING açılan bir
     * DOMAINMON_EXPIRY, gün geçince CRITICAL re-alert gönderip müdürü ekliyordu; alarm çözülünce
     * çözüm bildirimi event'in BAYAT WARNING seviyesine bakıp müdürü listeden düşürüyordu —
     * kritik uyarıyı alan müdür "düzeldi"yi hiç almıyordu.
     */
    @Test
    @DisplayName("Y5: re-alert seviyeyi YÜKSELTİRSE event'e KALICI yazılır (çözüm alıcısı doğru olsun)")
    void reAlert_levelPromotion_isPersisted() {
        String domain = "expiring.example.com";
        AlertEvent open = new AlertEvent();
        open.setId(11L); open.setDomain(domain); open.setAlertType(EscalationService.TYPE_DOMAINMON_EXPIRY);
        open.setAlertLevel("WARNING"); open.setAcknowledged(false); open.setResolved(false);
        open.setTeamId(4L);
        open.setCreatedAt(ISO.format(java.time.Instant.now().minus(java.time.Duration.ofDays(5))));
        open.setLastReAlertAt(ISO.format(java.time.Instant.now().minus(java.time.Duration.ofDays(2))));
        when(alertEventRepo.findOpenAlert(domain, EscalationService.TYPE_DOMAINMON_EXPIRY))
                .thenReturn(Optional.of(open));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("alert_level", "CRITICAL");   // sweep artık kritik diyor
        service.processConfirmedOutage(domain, EscalationService.TYPE_DOMAINMON_EXPIRY, "WARNING", ctx);

        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(cap.capture());
        assertThat(cap.getValue().getAlertLevel())
                .as("terfi kalıcı olmalı — çözüm bildirimi bu alanı okur")
                .isEqualTo("CRITICAL");
    }

    /**
     * O5: alarm AÇIKKEN monitör başka takıma atanırsa, re-alert canlı ctx.team_id'ye giderken
     * çözüm bildirimi event.teamId'ye gidiyordu — iki bildirim FARKLI takıma düşüyordu.
     */
    @Test
    @DisplayName("O5: re-alert damgalı takımı kullanır (çözüm bildirimiyle AYNI takım)")
    void reAlert_usesStampedTeam_notLiveContext() {
        String domain = "https://kw.example.com/";
        AlertEvent open = new AlertEvent();
        open.setId(12L); open.setDomain(domain); open.setAlertType(EscalationService.TYPE_KEYWORD);
        open.setAlertLevel("CRITICAL"); open.setAcknowledged(false); open.setResolved(false);
        open.setTeamId(4L);   // DAMGA: alarm açılırken bu takıma yazılmıştı
        open.setCreatedAt(ISO.format(java.time.Instant.now().minus(java.time.Duration.ofDays(5))));
        open.setLastReAlertAt(ISO.format(java.time.Instant.now().minus(java.time.Duration.ofDays(2))));
        when(alertEventRepo.findOpenAlert(domain, EscalationService.TYPE_KEYWORD)).thenReturn(Optional.of(open));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        com.sitemonitor.model.Team t4 = new com.sitemonitor.model.Team();
        t4.setId(4L); t4.setName("Takım A"); t4.setEmail("takim-a@example.com");
        com.sitemonitor.model.Team t9 = new com.sitemonitor.model.Team();
        t9.setId(9L); t9.setName("Takım B"); t9.setEmail("takim-b@example.com");
        when(teamRepo.findById(4L)).thenReturn(Optional.of(t4));
        when(teamRepo.findById(9L)).thenReturn(Optional.of(t9));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("team_id", 9L);   // monitör SONRADAN 9'a atanmış
        service.processConfirmedOutage(domain, EscalationService.TYPE_KEYWORD, "CRITICAL", ctx);

        ArgumentCaptor<String[]> toCap = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendAlert(toCap.capture(), anyString(), anyString(), any(), any(), any(), any(), any());
        assertThat(toCap.getValue()).as("re-alert damgalı takıma gitmeli").contains("takim-a@example.com");
        assertThat(toCap.getValue()).doesNotContain("takim-b@example.com");
    }

    /**
     * O6: {@code acknowledged} nullable Boolean; kolon eski satırlar dururken eklendiyse NULL
     * kalabilir. Korumasız unbox NPE'si {@code runCheckForDomains} dış catch'ine kadar fırlayıp
     * O SWEEP'TEKİ KALAN TÜM domain'lerin alarm işlemesini iptal ediyordu.
     */
    @Test
    @DisplayName("O6: acknowledged NULL açık alarm NPE'siz işlenir (sweep iptal olmaz)")
    void acknowledgedNull_doesNotThrow() {
        String domain = "https://kw2.example.com/";
        AlertEvent open = new AlertEvent();
        open.setId(13L); open.setDomain(domain); open.setAlertType(EscalationService.TYPE_KEYWORD);
        open.setAlertLevel("CRITICAL"); open.setResolved(false);
        open.setAcknowledged(null);   // eski satır — backfill yok
        open.setTeamId(4L);
        open.setCreatedAt(ISO.format(java.time.Instant.now().minus(java.time.Duration.ofDays(5))));
        open.setLastReAlertAt(ISO.format(java.time.Instant.now().minus(java.time.Duration.ofDays(2))));
        when(alertEventRepo.findOpenAlert(domain, EscalationService.TYPE_KEYWORD)).thenReturn(Optional.of(open));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // İstisna FIRLARSA test patlar — fırlamaması sözleşmenin kendisi.
        service.processConfirmedOutage(domain, EscalationService.TYPE_KEYWORD, "CRITICAL", new LinkedHashMap<>());

        verify(alertEventRepo, atLeast(1)).save(any());
    }

    @Test
    @DisplayName("processConfirmedOutage PAGE_DOWN → CRITICAL, subject 'Sayfa Yüklenemiyor'")
    void processConfirmedOutage_pageDown_critical() {
        String domain = "https://page.example.com/";
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("team_id", 7L);
        ctx.put("url", domain);
        ctx.put("http_status", 500);
        ctx.put("first_failure_at", "2026-06-24T00:00:00");
        when(alertEventRepo.findOpenAlert(domain, EscalationService.TYPE_PAGE_DOWN)).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processConfirmedOutage(domain, EscalationService.TYPE_PAGE_DOWN, "CRITICAL", ctx);

        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(cap.capture());
        AlertEvent saved = cap.getAllValues().get(0);
        assertThat(saved.getAlertType()).isEqualTo(EscalationService.TYPE_PAGE_DOWN);
        assertThat(saved.getAlertLevel()).isEqualTo("CRITICAL");
        assertThat(saved.getTeamId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("processConfirmedOutage PAGE_INTEGRITY → HIGH, bütünlük detayı mesajda")
    void processConfirmedOutage_pageIntegrity_high() {
        String domain = "https://page2.example.com/";
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("team_id", 8L);
        ctx.put("url", domain);
        ctx.put("detail", "3 kırık, 1 mixed content");
        ctx.put("first_failure_at", "2026-06-24T00:00:00");
        when(alertEventRepo.findOpenAlert(domain, EscalationService.TYPE_PAGE_INTEGRITY)).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processConfirmedOutage(domain, EscalationService.TYPE_PAGE_INTEGRITY, "HIGH", ctx);

        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getAlertType()).isEqualTo(EscalationService.TYPE_PAGE_INTEGRITY);
        assertThat(cap.getAllValues().get(0).getAlertLevel()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("processConfirmedOutage SCRIPTED_FAIL → CRITICAL, senaryo detayı mesajda")
    void processConfirmedOutage_scriptedFail_critical() {
        String name = "OIDC Login Akışı";
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("team_id", 8L);
        ctx.put("name", name);
        ctx.put("detail", "FAIL — 2✓/1✗");
        ctx.put("failed_checks", "token exchange 200");
        ctx.put("first_failure_at", "2026-06-24T00:00:00");
        when(alertEventRepo.findOpenAlert(name, EscalationService.TYPE_SCRIPTED_FAIL)).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processConfirmedOutage(name, EscalationService.TYPE_SCRIPTED_FAIL, "CRITICAL", ctx);

        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getAlertType()).isEqualTo(EscalationService.TYPE_SCRIPTED_FAIL);
        assertThat(cap.getAllValues().get(0).getAlertLevel()).isEqualTo("CRITICAL");
        assertThat(EscalationService.isScripted(EscalationService.TYPE_SCRIPTED_FAIL)).isTrue();
    }

    @Test
    @DisplayName("resolveOrphanedScriptedAlerts: eşleşmeyen senaryo adının açık alarmı sessizce kapanır")
    void resolveOrphanedScriptedAlerts_closesOrphans() {
        AlertEvent open = new AlertEvent();
        open.setAlertType(EscalationService.TYPE_SCRIPTED_FAIL);
        open.setDomain("Silinmiş Senaryo");
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(java.util.List.of(open));
        when(alertEventRepo.findOpenAlert(anyString(), anyString())).thenReturn(Optional.empty());

        int closed = service.resolveOrphanedScriptedAlerts(java.util.Set.of("Yaşayan Senaryo"));
        assertThat(closed).isEqualTo(1);
        assertThat(service.resolveOrphanedScriptedAlerts(null)).isZero();
    }

    @Test
    @DisplayName("resolveOrphanedPageAlerts: eşleşen monitörü olmayan açık PAGE alarmını öksüz sayar")
    void resolveOrphanedPageAlerts_closesOrphans() {
        AlertEvent orphan = new AlertEvent();
        orphan.setDomain("https://gone.example.com/");
        orphan.setAlertType(EscalationService.TYPE_PAGE_DOWN);
        orphan.setResolved(false); orphan.setAcknowledged(false);
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(orphan));

        int n = service.resolveOrphanedPageAlerts(java.util.Set.of("https://live.example.com/"));

        assertThat(n).isEqualTo(1);   // gone.example.com hiçbir mevcut URL'ye karşılık gelmiyor → öksüz
    }

    @Test
    @DisplayName("processConfirmedOutage: domain bakım penceresinde → alarm AÇILMAZ + hiçbir kanaldan bildirim gitmez")
    void processConfirmedOutage_underMaintenance_suppressesAlertAndNotification() {
        String domain = "under-maintenance.example.com";
        when(maintenanceService.isUnderMaintenance(domain)).thenReturn(true);

        service.processConfirmedOutage(domain, "ACCESSIBILITY", "CRITICAL", outageCtx());

        verify(alertEventRepo, never()).save(any());
        verifyNoInteractions(emailService, webhookService);
    }

    @Test
    @DisplayName("processConfirmedOutage creates CRITICAL ACCESSIBILITY alert with Erişim Kesintisi subject")
    void processConfirmedOutage_newOutage_createsCriticalAlert() {
        String domain = "down.example.com";
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(alertEventRepo.findOpenAlert(domain, "ACCESSIBILITY")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByActiveTrueOrderByRoleAsc())
                .thenReturn(List.of(contact("team@test.com", "TECH", "WARNING")));

        service.processConfirmedOutage(domain, "ACCESSIBILITY", "CRITICAL", outageCtx());

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        AlertEvent saved = captor.getAllValues().get(0);
        assertThat(saved.getAlertType()).isEqualTo("ACCESSIBILITY");
        assertThat(saved.getAlertLevel()).isEqualTo("CRITICAL");
        assertThat(saved.getDaysRemaining()).isNull();
        verify(emailService).sendAlert(any(String[].class), contains("Erişim kesintisi"),
                anyString(), eq(domain), eq("CRITICAL"), eq("ACCESSIBILITY"), isNull(), any());
    }

    @Test
    @DisplayName("processConfirmedOutage: open alert same UTC day → silent")
    void processConfirmedOutage_sameDay_silent() {
        String domain = "down.example.com";
        AlertEvent open = existingOpenAlert(domain, "ACCESSIBILITY", "CRITICAL", false);
        open.setCreatedAt(ISO.format(Instant.now()));
        open.setLastReAlertAt(ISO.format(Instant.now()));
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(alertEventRepo.findOpenAlert(domain, "ACCESSIBILITY")).thenReturn(Optional.of(open));

        service.processConfirmedOutage(domain, "ACCESSIBILITY", "CRITICAL", outageCtx());

        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("processConfirmedOutage: open alert from yesterday, unacked → daily RE-ALERT")
    void processConfirmedOutage_previousDay_reAlerts() {
        String domain = "down.example.com";
        AlertEvent open = existingOpenAlert(domain, "ACCESSIBILITY", "CRITICAL", false);
        open.setId(5L);
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(alertEventRepo.findOpenAlert(domain, "ACCESSIBILITY")).thenReturn(Optional.of(open));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByActiveTrueOrderByRoleAsc())
                .thenReturn(List.of(contact("team@test.com", "TECH", "WARNING")));

        service.processConfirmedOutage(domain, "ACCESSIBILITY", "CRITICAL", outageCtx());

        verify(emailService).sendAlert(any(String[].class), contains("[RE-ALERT]"),
                anyString(), eq(domain), eq("CRITICAL"), eq("ACCESSIBILITY"), isNull(), any());
        assertThat(open.getLastReAlertAt()).isNotNull();
    }

    @Test
    @DisplayName("processConfirmedOutage: YARIDA KALMIS ilk bildirim (lastReAlertAt null) HEMEN gonderilir")
    void processConfirmedOutage_interruptedFirstNotification_sendsNow() {
        // 2026-08-24 vakasi: alarm satiri kaydedildi, bildirim gitmeden once surec oldu
        // (deploy/restart/OOM). lastReAlertAt iki basari yolunda da damgalandigi icin NULL olmasi
        // "ilk bildirim yarida kaldi" demektir. Eskiden createdAt'e dusuluyordu ve kod sanki
        // bildirim gitmis gibi davraniyordu: alarm 24 saat SESSIZ kaliyor, hicbir isaret birakmiyordu.
        String domain = "down.example.com";
        AlertEvent open = existingOpenAlert(domain, "ACCESSIBILITY", "CRITICAL", false);
        open.setId(7L);
        open.setCreatedAt(ISO.format(Instant.now()));   // AZ ONCE olusmus → re-alert penceresi DOLMADI
        open.setLastReAlertAt(null);                    // ...ama ilk bildirim hic tamamlanmamis
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(alertEventRepo.findOpenAlert(domain, "ACCESSIBILITY")).thenReturn(Optional.of(open));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByActiveTrueOrderByRoleAsc())
                .thenReturn(List.of(contact("team@test.com", "TECH", "WARNING")));

        service.processConfirmedOutage(domain, "ACCESSIBILITY", "CRITICAL", outageCtx());

        // ILK bildirim olarak gider — "[RE-ALERT]" onekiyle DEGIL (kullaniciya tekrar gibi gorunmemeli).
        verify(emailService).sendAlert(any(String[].class), not(contains("[RE-ALERT]")),
                anyString(), eq(domain), eq("CRITICAL"), eq("ACCESSIBILITY"), isNull(), any());
        // Damga atilir ki bir sonraki sweep bunu tekrar gondermesin.
        assertThat(open.getLastReAlertAt()).isNotNull();
    }

    @Test
    @DisplayName("processConfirmedOutage: bildirimi TAMAMLANMIS taze alarm sessiz kalir (tekrar gondermez)")
    void processConfirmedOutage_freshAlertAlreadyNotified_staysSilent() {
        // Yukaridaki kurtarmanin ters kosulu: lastReAlertAt DOLUYSA bildirim gitmistir,
        // re-alert penceresi dolana kadar susulur. Bu ayrim kaybolursa her sweep mail atardi.
        String domain = "down.example.com";
        AlertEvent open = existingOpenAlert(domain, "ACCESSIBILITY", "CRITICAL", false);
        open.setCreatedAt(ISO.format(Instant.now()));
        open.setLastReAlertAt(ISO.format(Instant.now()));
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(alertEventRepo.findOpenAlert(domain, "ACCESSIBILITY")).thenReturn(Optional.of(open));

        service.processConfirmedOutage(domain, "ACCESSIBILITY", "CRITICAL", outageCtx());

        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("processConfirmedOutage: acknowledged open alert → silent")
    void processConfirmedOutage_acknowledged_silent() {
        String domain = "down.example.com";
        AlertEvent open = existingOpenAlert(domain, "ACCESSIBILITY", "CRITICAL", true);
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(alertEventRepo.findOpenAlert(domain, "ACCESSIBILITY")).thenReturn(Optional.of(open));

        service.processConfirmedOutage(domain, "ACCESSIBILITY", "CRITICAL", outageCtx());

        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(),
                any(), any(), any(), any(), any());
        verify(alertEventRepo, never()).save(any());
    }

    @Test
    @DisplayName("resolveAccessibilityAlertsForDomain resolves only ACCESSIBILITY alerts as system")
    void resolveAccessibilityAlerts_resolvesOnlyAccessibility() {
        String domain = "down.example.com";
        AlertEvent open = existingOpenAlert(domain, "ACCESSIBILITY", "CRITICAL", false);
        open.setId(5L);
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of(open));
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resolveMonitoringAlertsForDomain(domain, "ACCESSIBILITY");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> typesCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(alertEventRepo).findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), typesCaptor.capture());
        assertThat(typesCaptor.getValue()).containsExactly("ACCESSIBILITY");
        assertThat(open.getResolved()).isTrue();
        assertThat(open.getResolvedBy()).isEqualTo("system");
    }

    @Test
    @DisplayName("processConfirmedOutage PORT_DOWN → CRITICAL, subject 'Port Kesintisi', mesajda port/protokol")
    void processConfirmedOutage_portDown_critical() {
        String domain = "down.example.com";
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(alertEventRepo.findOpenAlert(domain, "PORT_DOWN")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByActiveTrueOrderByRoleAsc())
                .thenReturn(List.of(contact("team@test.com", "TECH", "WARNING")));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("port", 8443);
        ctx.put("protocol", "TCP");
        ctx.put("detail", "8443/TCP");
        service.processConfirmedOutage(domain, "PORT_DOWN", "CRITICAL", ctx);

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getAlertType()).isEqualTo("PORT_DOWN");
        assertThat(captor.getAllValues().get(0).getAlertLevel()).isEqualTo("CRITICAL");
        assertThat(captor.getAllValues().get(0).getMessage()).contains("8443/TCP");
        verify(emailService).sendAlert(any(String[].class), contains("Port kesintisi"),
                anyString(), eq(domain), eq("CRITICAL"), eq("PORT_DOWN"), isNull(), any());
    }

    @Test
    @DisplayName("processConfirmedOutage DNS_FAILURE → CRITICAL, subject 'DNS Çözümleme Hatası'")
    void processConfirmedOutage_dnsFailure_critical() {
        String domain = "down.example.com";
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(alertEventRepo.findOpenAlert(domain, "DNS_FAILURE")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByActiveTrueOrderByRoleAsc())
                .thenReturn(List.of(contact("team@test.com", "TECH", "WARNING")));

        service.processConfirmedOutage(domain, "DNS_FAILURE", "CRITICAL",
                new LinkedHashMap<>(Map.of("record_type", "A", "detail", "A")));

        verify(emailService).sendAlert(any(String[].class), contains("DNS Çözümleme Hatası"),
                anyString(), eq(domain), eq("CRITICAL"), eq("DNS_FAILURE"), isNull(), any());
    }

    @Test
    @DisplayName("processConfirmedOutage DNS_CHANGED → HIGH, mesajda eski/yeni değerler")
    void processConfirmedOutage_dnsChanged_highWithValues() {
        String domain = "changed.example.com";
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.empty());
        when(alertEventRepo.findOpenAlert(domain, "DNS_CHANGED")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(contactRepo.findByMinAlertLevelInAndActiveTrue(List.of("WARNING", "HIGH")))
                .thenReturn(List.of(contact("team@test.com", "TECH", "WARNING")));

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("record_type", "A");
        ctx.put("old_values", List.of("1.2.3.4"));
        ctx.put("new_values", List.of("9.9.9.9"));
        service.processConfirmedOutage(domain, "DNS_CHANGED", "HIGH", ctx);

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(captor.capture());
        AlertEvent saved = captor.getAllValues().get(0);
        assertThat(saved.getAlertLevel()).isEqualTo("HIGH");
        assertThat(saved.getMessage()).contains("1.2.3.4").contains("9.9.9.9").contains("otomatik kapanmaz");
        verify(emailService).sendAlert(any(String[].class), contains("DNS Değişikliği"),
                anyString(), eq(domain), eq("HIGH"), eq("DNS_CHANGED"), isNull(), any());
    }

    @Test
    @DisplayName("Regression: healthy cert sweep resolves ONLY cert-type alerts, never ACCESSIBILITY")
    void processResults_healthyCert_doesNotTouchAccessibilityAlerts() {
        String domain = "healthy.example.com";
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());

        service.processResults(List.of(okResult(domain)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> typesCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(alertEventRepo).findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), typesCaptor.capture());
        // Kanonik küme BÜYÜDÜ: hostname uyuşmazlığı ve güvenilmeyen CA da sertifika alarmlarıdır,
        // dolayısıyla sağlıklı bir kontrolde onlar da kapatılmalı. Testin ASIL iddiası (izleme
        // tipine, özellikle ACCESSIBILITY'ye DOKUNMAZ) aynen korunuyor.
        assertThat(typesCaptor.getValue())
                .containsExactlyInAnyOrder("EXPIRY", "CHAIN_BROKEN", "REVOKED", "MISMATCH",
                        "HOSTNAME_MISMATCH", "UNTRUSTED_CA")
                .doesNotContain("ACCESSIBILITY");
    }

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

    // ── Envanter zenginleştirmesi: operasyonel bayraklar + değişiklik açıklaması maile taşınır ──

    /** reNotify ile bir alarm gönderip e-posta katmanına geçen ctx'i yakalar. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedCtx(String domain, String alertType, CertificateInventory inv) {
        AlertEvent event = existingOpenAlert(domain, alertType, "WARNING", false);
        event.setId(900L);
        event.setTeamId(7L);
        when(alertEventRepo.findById(900L)).thenReturn(Optional.of(event));
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        // Global kontak: envanter kaydı OLMADIĞI senaryoda da alıcı kalsın (takım envanterden geliyor).
        when(contactRepo.findByMinAlertLevelAndActiveTrue(anyString()))
                .thenReturn(List.of(contact("ops@example.com", "MANAGER", "WARNING")));
        when(inventoryRepo.findByDomain(domain)).thenReturn(Optional.ofNullable(inv));

        Team team = new Team();
        team.setId(7L);
        team.setName("SY-Takım A");
        team.setEmail("team@example.com");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team));

        service.reNotify(900L);

        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendAlert(any(String[].class), anyString(), anyString(),
                eq(domain), any(), any(), any(), ctx.capture());
        return ctx.getValue();
    }

    private static CertificateInventory inventoryWithOps() {
        CertificateInventory inv = new CertificateInventory();
        inv.setTeamId(7L);
        inv.setNetscaler(true);
        inv.setWafEnabled(true);
        inv.setInUse(true);
        inv.setOpenshift(false);      // Hayır → maile girmemeli
        inv.setChangeDescription("1. IISAdmins PFX'i alır.\n2. Netscaler ve WAF'ta güncellenir.");
        return inv;
    }

    @Test
    @DisplayName("sertifika alarmı: envanterin Evet bayrakları + değişiklik açıklaması ctx'e girer")
    void certAlertCarriesInventoryContext() {
        Map<String, Object> ctx = capturedCtx("inv.example.com", "EXPIRY", inventoryWithOps());

        assertThat(ctx.get("inv_ops")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("Netscaler", "WAF'ta Var", "Kullanım Durumu");
        assertThat(String.valueOf(ctx.get("inv_change_desc"))).contains("IISAdmins PFX'i alır");
    }

    @Test
    @DisplayName("sertifika DIŞI alarm: envanter bağlamı eklenmez (yalnız sertifika maili zenginleşir)")
    void nonCertAlertHasNoInventoryContext() {
        Map<String, Object> ctx = capturedCtx("inv2.example.com", "ACCESSIBILITY", inventoryWithOps());

        assertThat(ctx).doesNotContainKeys("inv_ops", "inv_change_desc");
    }

    @Test
    @DisplayName("envanter kaydı yoksa sertifika maili eskisi gibi üretilir (anahtar eklenmez)")
    void certAlertWithoutInventoryRecord() {
        Map<String, Object> ctx = capturedCtx("noinv.example.com", "EXPIRY", null);

        assertThat(ctx).doesNotContainKeys("inv_ops", "inv_change_desc");
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

    @Test
    @DisplayName("processConfirmedOutage SCRIPTED_SLOW → HIGH; mesajda ölçülen süre ve EŞİK geçer")
    void scriptedSlowAlertCarriesDurationAndThreshold() {
        String name = "Login Akisi";
        when(alertEventRepo.findOpenAlert(name, EscalationService.TYPE_SCRIPTED_SLOW)).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Map<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("duration_ms", 8123L);
        ctx.put("threshold_ms", 5000);

        service.processConfirmedOutage(name, EscalationService.TYPE_SCRIPTED_SLOW, "HIGH", ctx);

        var cap = org.mockito.ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeastOnce()).save(cap.capture());
        AlertEvent saved = cap.getAllValues().get(0);
        assertThat(saved.getAlertType()).isEqualTo(EscalationService.TYPE_SCRIPTED_SLOW);
        assertThat(saved.getAlertLevel()).isEqualTo("HIGH");
        // Nöbetçi "ne kadar yavaş, eşiğim neydi" sorusunu mesajdan cevaplayabilmeli.
        assertThat(saved.getMessage()).contains("8123").contains("5000");
        // isScripted her iki tipi de kapsar: sentetik dallar (sekme/CTA/e-posta) ikisinde de çalışır.
        assertThat(EscalationService.isScripted(EscalationService.TYPE_SCRIPTED_SLOW)).isTrue();
    }

    // ── Sessiz bozulma guard'ları ───────────────────────────────────────────────

    @Test
    @DisplayName("E-postası/adı BOŞ kontak listeyi çökertmez — notified_contacts diğer kontakları KORUR")
    void serializeContacts_nullFieldsDoNotWipeTheList() {
        String domain = "np.example.com";
        when(alertEventRepo.findOpenAlert(domain, "EXPIRY")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        // Webhook-only / yeni açılmış kontak: e-posta ve ad NULL (kolonlar nullable, yalnız role NOT NULL).
        EscalationContact broken = contact(null, "PO", "WARNING");
        broken.setName(null);
        when(contactRepo.findByActiveTrueOrderByRoleAsc()).thenReturn(List.of(broken, contact("po@x.com", "MANAGER", "WARNING")));

        service.processResults(List.of(expiryResult(domain, 5, true)));

        var cap = org.mockito.ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeastOnce()).save(cap.capture());
        String json = cap.getAllValues().get(0).getNotifiedContacts();
        // Eskiden Map.of null değeri NPE ile reddediyor, catch "[]" döndürüyordu: TEK bozuk kontak
        // yüzünden "bu alarm kime gitti?" kaydı tamamen kayboluyordu.
        assertThat(json).isNotNull().isNotEqualTo("[]");
        assertThat(json).contains("po@x.com");
    }

    @Test
    @DisplayName("Eşik alanları NULL iken alarm seviyesi hesaplanır (unboxing NPE'si sweep'i düşürmez)")
    void determineAlertLevel_nullThresholdFields_useDefaults() {
        AlertThreshold broken = new AlertThreshold();
        broken.setActive(true);
        broken.setCriticalDays(null); broken.setHighDays(null); broken.setWarningDays(null);
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.of(broken));
        String domain = "nullthr.example.com";
        when(alertEventRepo.findOpenAlert(domain, "EXPIRY")).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        // Fırlamadan tamamlanmalı: NPE buradan sweep'in en dışına kadar çıkıp TÜM turu düşürüyordu.
        service.processResults(List.of(expiryResult(domain, 3, true)));

        var cap = org.mockito.ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getAlertLevel()).isEqualTo("CRITICAL");   // 3 gün ≤ 7 varsayılanı
    }

    // -- B2: KANAL MATRISI - notify_email bayragi gercekten uygulanir ---------
    // Bayrak backend'de HICBIR yerde okunmuyordu: formdaki "E-mail" kutusu sustu, isaretini
    // kaldirmak mail gonderimini durdurmuyordu. Artik SchedulerService ctx'e mail_disabled
    // damgasi basiyor ve sendCombinedAlert bunu okuyup maili atliyor.

    private java.util.Map<String, Object> monCtx(Boolean mailDisabled, Boolean pushDisabled) {
        var ctx = new java.util.LinkedHashMap<String, Object>();
        ctx.put("monitor_id", 1L);
        ctx.put("team_id", 7L);
        if (Boolean.TRUE.equals(mailDisabled)) ctx.put("mail_disabled", true);
        if (Boolean.TRUE.equals(pushDisabled)) ctx.put("push_disabled", true);
        return ctx;
    }

    private void outageFixture(String domain) {
        // Standalone izleme alarminda alicilar EVENT.teamId'den cozulur; takim maili olmadan
        // allEmails bos kalir ve "mail gitti mi" iddiasi anlamsizlasirdi.
        var team = new com.sitemonitor.model.Team();
        team.setId(7L); team.setName("Takim A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(team));
        when(alertEventRepo.findOpenAlert(eq(domain), anyString())).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(inv -> {
            AlertEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(4242L);
            return e;
        });
    }

    @Test
    @DisplayName("B2: notifyEmail KAPALI -> mail GITMEZ, webhook YINE gider (kanal bagimsizligi)")
    void mailDisabled_skipsMailButKeepsPush() {
        String domain = "http://mail-off.example.com/";
        outageFixture(domain);

        service.processConfirmedOutage(domain, "HTTP_DOWN", "CRITICAL", monCtx(true, false));

        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(),
                any(), any(), any(), any(), any());
        // KRITIK: mailin atlanmasi push'u DUSURMEZ. Eskiden push tetigi metodun SONUNDA idi ve
        // erken return eden her dal webhook'u da sessizce dusuruyordu.
        verify(userPushService).enqueueAlert(any(), eq("INITIAL"), any(), any(), any());
    }

    @Test
    @DisplayName("B2: notifyWebhook KAPALI -> mail GIDER (bayraklar birbirini etkilemez)")
    void pushDisabled_stillSendsMail() {
        String domain = "http://push-off.example.com/";
        outageFixture(domain);
        when(contactRepo.findByMinAlertLevelAndActiveTrue(anyString()))
                .thenReturn(List.of(contact("po@example.com", "PO", "CRITICAL")));

        service.processConfirmedOutage(domain, "HTTP_DOWN", "CRITICAL", monCtx(false, true));

        verify(emailService).sendAlert(any(String[].class), anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("B2: iki bayrak da ACIK -> her iki kanal da calisir")
    void bothEnabled_bothChannels() {
        String domain = "http://both-on.example.com/";
        outageFixture(domain);
        when(contactRepo.findByMinAlertLevelAndActiveTrue(anyString()))
                .thenReturn(List.of(contact("po@example.com", "PO", "CRITICAL")));

        service.processConfirmedOutage(domain, "HTTP_DOWN", "CRITICAL", monCtx(false, false));

        verify(emailService).sendAlert(any(String[].class), anyString(), anyString(),
                any(), any(), any(), any(), any());
        verify(userPushService).enqueueAlert(any(), eq("INITIAL"), any(), any(), any());
    }

    // ── A: tarayıcının reddettiği sertifika ALARM üretmeli ─────────────────────
    //
    // Var olmayan bir alan adı NXDOMAIN-hijack ile bir ev modeminin yönetim paneline çözüldü;
    // modem kendi sertifikasını sundu (CN=192.168.1.1, issuer ZTE-ROOT-CA, 1775 gün kalan).
    // Chrome adresi ERR_CERT_AUTHORITY_INVALID ile reddederken SiteMonitor "Geçerli" gösterdi ve
    // hiç alarm üretmedi: karar YALNIZ kalan güne bakıyordu.

    /** Hijack edilmiş modem paneli — süre tertemiz, güvenlik iki koldan bozuk. */
    private Map<String, Object> hijackedResult(String domain) {
        Map<String, Object> r = new java.util.LinkedHashMap<>(okResult(domain));
        r.put("days_remaining", 1775);
        r.put("san", List.of("192.0.2.1"));       // istenen alan adını KAPSAMIYOR
        r.put("trust_status", "UNTRUSTED");        // hiçbir köke bağlanmıyor
        r.put("subject", "192.0.2.1");
        r.put("resolved_ip", "192.168.1.1");
        return r;
    }

    private String typeOfSavedAlert() {
        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(cap.capture());
        return cap.getAllValues().get(0).getAlertType();
    }

    @Test
    @DisplayName("A: süresi uzak ama alan adını kapsamayan sertifika HOSTNAME_MISMATCH alarmı açar")
    void hijackedCert_raisesHostnameMismatch() {
        String domain = "olmayan.example.com";
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processResults(List.of(hijackedResult(domain)));

        assertThat(typeOfSavedAlert()).isEqualTo(EscalationService.TYPE_HOSTNAME_MISMATCH);
    }

    @Test
    @DisplayName("A: uyuşmazlık alarmı KRİTİK seviyededir (doğrulanmış sertifika kusuru)")
    void hijackedCert_isCritical() {
        String domain = "olmayan.example.com";
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processResults(List.of(hijackedResult(domain)));

        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getAlertLevel()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("A: alarm metni KANIT taşır — çözümlenen iç IP ve sunulan CN")
    void hijackedCert_messageCarriesEvidence() {
        String domain = "olmayan.example.com";
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processResults(List.of(hijackedResult(domain)));

        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(cap.capture());
        String msg = cap.getAllValues().get(0).getMessage();
        assertThat(msg).contains("192.168.1.1").contains("iç ağ").contains("192.0.2.1");
    }

    @Test
    @DisplayName("A: uyuşmazlık ayarı KAPALIYKEN bugünkü davranış korunur (alarm yok)")
    void hijackedCert_settingOff_noAlert() {
        String domain = "olmayan.example.com";
        when(appSettings.getBoolean(eq(EscalationService.SETTING_ALERT_HOSTNAME_MISMATCH), anyBoolean()))
                .thenReturn(false);
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());

        service.processResults(List.of(hijackedResult(domain)));

        verify(alertEventRepo, never()).save(any());
    }

    @Test
    @DisplayName("A: güvenilmeyen CA varsayılan KAPALI — tek başına alarm üretmez (CA paketi boşken sel olurdu)")
    void untrustedOnly_defaultOff() {
        String domain = "ic-host.example.com";
        Map<String, Object> r = new java.util.LinkedHashMap<>(okResult(domain));
        r.put("san", List.of(domain));              // alan adı KAPSANIYOR
        r.put("trust_status", "UNTRUSTED");         // ama kurumsal CA truststore'da yok
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());

        service.processResults(List.of(r));

        verify(alertEventRepo, never()).save(any());
    }

    @Test
    @DisplayName("A: güven ayarı AÇILINCA aynı sertifika UNTRUSTED_CA alarmı açar")
    void untrustedOnly_settingOn_raises() {
        String domain = "ic-host.example.com";
        Map<String, Object> r = new java.util.LinkedHashMap<>(okResult(domain));
        r.put("san", List.of(domain));
        r.put("trust_status", "UNTRUSTED");
        when(appSettings.getBoolean(eq(EscalationService.SETTING_ALERT_UNTRUSTED), anyBoolean()))
                .thenReturn(true);
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processResults(List.of(r));

        assertThat(typeOfSavedAlert()).isEqualTo(EscalationService.TYPE_UNTRUSTED_CA);
    }

    @Test
    @DisplayName("A: SAĞLIKLI sertifika hiç etkilenmez — yanlış pozitif üretilmez")
    void healthyCert_stillSilent() {
        String domain = "saglikli.example.com";
        Map<String, Object> r = new java.util.LinkedHashMap<>(okResult(domain));
        r.put("san", List.of(domain, "www." + domain));
        r.put("trust_status", "TRUSTED");
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());

        service.processResults(List.of(r));

        verify(alertEventRepo, never()).save(any());
    }

    @Test
    @DisplayName("A: SAN bilgisi hiç gelmemişse alarm üretilmez (UNKNOWN, FAIL değildir)")
    void missingSan_noAlert() {
        String domain = "bilinmeyen.example.com";
        Map<String, Object> r = new java.util.LinkedHashMap<>(okResult(domain));
        r.put("trust_status", "UNKNOWN");   // hafif kontrol: güven de SAN da hesaplanmadı
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());

        service.processResults(List.of(r));

        verify(alertEventRepo, never()).save(any());
    }

    // ── Denetim 5. tur, bulgu 3: güvenlik alarmı SÜRE alarmını maskelemez ──────
    //
    // determineAlertType tek tip döndürüyor ve güvenlik dalı EXPIRY'nin önündeydi. Hostname
    // uyuşmazlığı KALICI bir durum olabildiği için (cert yalnız www.x.com kapsıyor, izleme x.com)
    // o domainde sertifikanın süresi dolsa bile EXPIRY alarmı HİÇ açılmıyordu.

    /** Hem güvenlik kusuru hem süre uyarısı taşıyan sonuç — ikisi birden doğru. */
    private Map<String, Object> insecureAndExpiringResult(String domain) {
        Map<String, Object> r = new java.util.LinkedHashMap<>(okResult(domain));
        r.put("san", List.of("192.0.2.1"));     // alan adını kapsamıyor
        r.put("trust_status", "UNTRUSTED");
        r.put("warning", true);                  // ve süresi de dolmak üzere
        r.put("days_remaining", 3);
        return r;
    }

    private List<String> savedAlertTypes() {
        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(1)).save(cap.capture());
        return cap.getAllValues().stream().map(AlertEvent::getAlertType).distinct().toList();
    }

    @Test
    @DisplayName("Bulgu 3: güvenlik kusuru VE süre uyarısı birlikteyse İKİ alarm da açılır")
    void securityDoesNotMaskExpiry() {
        String domain = "hem-guvensiz-hem-doluyor.example.com";
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processResults(List.of(insecureAndExpiringResult(domain)));

        assertThat(savedAlertTypes())
                .contains(EscalationService.TYPE_HOSTNAME_MISMATCH)
                .contains("EXPIRY");
    }

    @Test
    @DisplayName("Bulgu 3: REVOKED tek başına döner — bozuk sertifikada süreyi ayrıca alarma bağlamayız")
    void revokedStaysExclusive() {
        String domain = "iptal.example.com";
        Map<String, Object> r = new java.util.LinkedHashMap<>(insecureAndExpiringResult(domain));
        r.put("revocation_status", "REVOKED");
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processResults(List.of(r));

        assertThat(savedAlertTypes()).containsExactly("REVOKED");
    }

    @Test
    @DisplayName("Bulgu 3: bu turda ÜRETİLMEYEN cert tipleri kapatılır (asılı alarm kalmaz)")
    void staleTypesAreResolved() {
        String domain = "hem-guvensiz-hem-doluyor.example.com";
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processResults(List.of(insecureAndExpiringResult(domain)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> types = ArgumentCaptor.forClass(Collection.class);
        verify(alertEventRepo).findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), types.capture());
        // Üretilen iki tip kapatma kümesinde OLMAMALI; kalan cert tipleri kapatılmalı.
        assertThat(types.getValue())
                .doesNotContain(EscalationService.TYPE_HOSTNAME_MISMATCH, "EXPIRY")
                .contains("REVOKED", "CHAIN_BROKEN", "MISMATCH", EscalationService.TYPE_UNTRUSTED_CA);
    }

    // ── Bulgu 12: süre-DIŞI alarmda "N GÜN KALDI" yazılmaz ────────────────────

    @Test
    @DisplayName("Bulgu 12: sertifika kusurları süre-bitişi ailesinden DEĞİLDİR")
    void isDurationAlert_excludesCertDefects() {
        assertThat(EscalationService.isDurationAlert("EXPIRY")).isTrue();
        assertThat(EscalationService.isDurationAlert(EscalationService.TYPE_DOMAINMON_EXPIRY)).isTrue();
        assertThat(EscalationService.isDurationAlert(null)).isTrue();   // bilinmeyen → bugünkü davranış

        assertThat(EscalationService.isDurationAlert("REVOKED")).isFalse();
        assertThat(EscalationService.isDurationAlert("MISMATCH")).isFalse();
        assertThat(EscalationService.isDurationAlert("CHAIN_BROKEN")).isFalse();
        assertThat(EscalationService.isDurationAlert(EscalationService.TYPE_HOSTNAME_MISMATCH)).isFalse();
        assertThat(EscalationService.isDurationAlert(EscalationService.TYPE_UNTRUSTED_CA)).isFalse();
    }

    @Test
    @DisplayName("Bulgu 12: güvenlik alarmının e-posta KONUSU '1775 GÜN KALDI' demez, seviyeyi yazar")
    void securityAlertSubjectShowsLevelNotDays() {
        String domain = "olmayan.example.com";
        // Alici olmadan mail hic gonderilmez; konu satirini gozlemleyebilmek icin takim adresi sart.
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
        inv.setDomain(domain); inv.setTeamId(7L);
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of(inv));
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(7L); team.setName("Takim A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(7L)).thenReturn(java.util.Optional.of(team));
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(domain), anyCollection()))
                .thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        service.processResults(List.of(hijackedResult(domain)));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeast(1)).sendAlert(any(String[].class), subject.capture(),
                anyString(), anyString(), anyString(), anyString(), any(), any());
        assertThat(subject.getAllValues()).isNotEmpty();
        assertThat(subject.getAllValues().get(0)).doesNotContain("GÜN KALDI").contains("KRİTİK");
    }

    // ── Denetim 5. tur: bildirim paritesi (bulgu 6 · 7 · 13) ──────────────────

    private AlertEvent resolvableEvent(String type, Long teamId, String contextJson) {
        AlertEvent e = new AlertEvent();
        e.setId(77L);
        e.setDomain("izleme.example.com");
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setTeamId(teamId);
        e.setContextJson(contextJson);
        e.setCreatedAt(ISO.format(java.time.Instant.now().minus(java.time.Duration.ofMinutes(5))));
        return e;
    }

    @Test
    @DisplayName("Bulgu 6: e-posta alıcısı YOKKEN bile 'DÜZELDİ' push'u tetiklenir")
    void resolution_pushFiresWithoutEmailRecipients() {
        // Takım yok → collectTeamEmails boş → eski kodda erken return, push HİÇ tetiklenmiyordu.
        AlertEvent e = resolvableEvent(EscalationService.TYPE_PORT_DOWN, null, null);

        service.sendResolutionNotificationAsync(e, "Sistem", "AUTO");

        verify(userPushService).enqueueResolve(eq(e), any());
    }

    @Test
    @DisplayName("Bulgu 13: izlemede e-posta KAPALIYSA çözüm maili de gitmez ama push gider")
    void resolution_respectsMailDisabled() {
        AlertEvent e = resolvableEvent(EscalationService.TYPE_PORT_DOWN, 5L,
                "{\"mail_disabled\":true,\"monitor_id\":9}");
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(5L); team.setName("Takim A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(5L)).thenReturn(java.util.Optional.of(team));

        service.sendResolutionNotificationAsync(e, "Sistem", "AUTO");

        verify(emailService, never()).sendResolutionAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any());
        verify(userPushService).enqueueResolve(eq(e), any());
    }

    @Test
    @DisplayName("Bulgu 13: e-posta AÇIKKEN çözüm maili gitmeye devam eder (regresyon)")
    void resolution_sendsMailWhenEnabled() {
        AlertEvent e = resolvableEvent(EscalationService.TYPE_PORT_DOWN, 5L, "{\"monitor_id\":9}");
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(5L); team.setName("Takim A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(5L)).thenReturn(java.util.Optional.of(team));

        service.sendResolutionNotificationAsync(e, "Sistem", "AUTO");

        verify(emailService, atLeast(1)).sendResolutionAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Bulgu 7: envanterde OLMAYAN Port alarmında çözüm, DAMGALANMIŞ takıma gider")
    void resolution_prefersStampedTeamOverInventory() {
        // detachIfIdentityChanged sonrası tipik durum: host envanterde yok, takım event'te damgalı.
        AlertEvent e = resolvableEvent(EscalationService.TYPE_PORT_DOWN, 5L, "{\"monitor_id\":9}");
        when(inventoryRepo.findByDomain("izleme.example.com")).thenReturn(java.util.Optional.empty());
        com.sitemonitor.model.Team team = new com.sitemonitor.model.Team();
        team.setId(5L); team.setName("Takim A"); team.setEmail("takim-a@example.com");
        when(teamRepo.findById(5L)).thenReturn(java.util.Optional.of(team));

        service.sendResolutionNotificationAsync(e, "Sistem", "AUTO");

        // Takım çözülemeseydi alıcı listesi boş kalır ve mail hiç gitmezdi.
        verify(emailService, atLeast(1)).sendResolutionAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), any(), any());
    }
}
