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
 * Pasif kullanıcıya hiçbir eskalasyon / alarm bildirimi gitmez (2026-10-02, kullanıcı kararı) — GERÇEK
 * {@link EscalationService} + GERÇEK {@link InactiveRecipientGuard}, bağımlılıklar sahte. Senaryo: Takım B'nin müdür
 * kişisi (e-posta + Teams webhook'u) PASİF bir kullanıcıya ({@code user_id} 500) bağlı. Açılış, günlük yeniden uyarı,
 * çözüm, tekrar bildir önizlemesi ve simülatörde o kişinin ne e-postası ne webhook'u var; yönlendirme kuralları
 * (yalnız sahip takım, seviye eşiği) aynen. Alıcı kalmadıysa günlükte "SKIPPED: pasif kullanıcı" izi. Pasif kullanıcı
 * yokken çıktı bugünküyle aynı (pozitif kontrol).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InactiveRecipientNotificationTest {

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
    @Mock AppUserRepository userRepo;

    static final long TEAM_B = 2L, TEAM_N = 4L, PASSIVE_USER = 500L;
    static final String B_MAIL = "takim-b@example.com", B_MANAGER = "mudur-b@example.com", B_TECH = "tech-b@example.com";
    static final String B_HOOK = "https://hooks.example.com/services/T1/B2/takim-b";
    static final String DOMAIN = "b.example.com";
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    final List<EscalationContact> db = new ArrayList<>();
    final Map<String, CertificateInventory> inventory = new HashMap<>();
    final List<NotificationLog> logRows = new ArrayList<>();
    EscalationService service;
    InactiveRecipientGuard guard;

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);
        guard = new InactiveRecipientGuard(userRepo);
        service.setInactiveGuard(guard);
        when(userRepo.findInactiveIdsAndEmails()).thenReturn(List.<Object[]>of(new Object[]{PASSIVE_USER, B_MANAGER}));
        when(userRepo.findActiveEmailsLowerIn(anyCollection())).thenReturn(List.of());

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
        when(notificationLogRepo.save(any())).thenAnswer(i -> { logRows.add(i.getArgument(0)); return i.getArgument(0); });
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("SENT");

        team(TEAM_B, "Takım B", B_MAIL);
        team(TEAM_N, "Takım N", null);   // takım adresi YOK — tek alıcı adayı pasif kişi

        EscalationContact mgr = contact(10L, TEAM_B, B_MANAGER, "MANAGER", "HIGH");
        mgr.setUserId(PASSIVE_USER);
        mgr.setWebhookUrl(B_HOOK); mgr.setWebhookType("TEAMS");
        db.add(mgr);

        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(any()))
                .thenAnswer(i -> active(c -> Objects.equals(c.getTeamId(), i.getArgument(0))));
        when(contactRepo.findByTeamIdAndMinAlertLevelInAndActiveTrue(any(), anyList()))
                .thenAnswer(i -> active(c -> Objects.equals(c.getTeamId(), i.getArgument(0))
                        && ((List<?>) i.getArgument(1)).contains(c.getMinAlertLevel())));
        when(contactRepo.findByTeamIdAndMinAlertLevelAndActiveTrue(any(), anyString()))
                .thenAnswer(i -> active(c -> Objects.equals(c.getTeamId(), i.getArgument(0))
                        && Objects.equals(c.getMinAlertLevel(), i.getArgument(1))));
        when(inventoryRepo.findByDomainIn(anyCollection())).thenAnswer(i -> {
            List<CertificateInventory> out = new ArrayList<>();
            for (Object d : (Collection<?>) i.getArgument(0)) if (inventory.containsKey(d)) out.add(inventory.get(d));
            return out;
        });
        when(inventoryRepo.findByDomain(anyString())).thenAnswer(i -> Optional.ofNullable(inventory.get((String) i.getArgument(0))));
        inventory(DOMAIN, TEAM_B);
    }

    @Test
    @DisplayName("İLK bildirim: pasif kullanıcıya bağlı müdür kişisi düşer — e-postası YOK, webhook'u YOK; takım adresi + aktif kişi gider")
    void initial_passiveContactExcluded_emailAndWebhook() {
        db.add(contact(11L, TEAM_B, B_TECH, "TECH", "HIGH"));   // aktif kişi (user_id yok) — kalır

        service.processResults(List.of(hijackedResult(DOMAIN)));

        assertThat(sentTo()).containsExactlyInAnyOrder(B_MAIL, B_TECH);
        verify(webhookService, never()).send(any(), eq(B_HOOK), any(), any(), any());
        assertThat(lastSavedEvent().getNotifiedContacts()).contains(B_TECH).doesNotContain(B_MANAGER);
    }

    @Test
    @DisplayName("Günlük yeniden uyarı + çözüm + tekrar bildir önizlemesi + simülatör: pasif kişi hiçbirinde yok")
    void realertResolutionPreviewSimulator_excludePassive() {
        AlertEvent open = openEvent(TEAM_B, "CRITICAL");
        open.setLastReAlertAt(ISO.format(Instant.now().minus(25, ChronoUnit.HOURS)));
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(open));
        when(alertEventRepo.findById(open.getId())).thenReturn(Optional.of(open));

        service.processResults(List.of(hijackedResult(DOMAIN)));   // günlük yeniden uyarı
        assertThat(sentTo()).containsExactly(B_MAIL);

        assertThat(service.previewReNotify(open.getId()))
                .extracting(EscalationService.ReNotifyRecipient::email).containsExactly(B_MAIL);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> simContacts = (List<Map<String, Object>>) service
                .simulateRecipients(TEAM_B, "CRITICAL", false, null).get("contacts");
        assertThat(simContacts).isEmpty();

        service.resolve(open.getId(), "Kişi B");
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendResolutionAlert(to.capture(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(to.getValue()).containsExactly(B_MAIL);
        verify(webhookService, never()).send(any(), eq(B_HOOK), any(), any(), any());
    }

    @Test
    @DisplayName("Tüm alıcılar pasif (takım adresi yok, tek kişi pasif) → e-posta gitmez, günlükte 'SKIPPED: pasif kullanıcı' izi")
    void allRecipientsPassive_skipTrace() {
        EscalationContact nMgr = contact(20L, TEAM_N, "mudur-n@example.com", "MANAGER", "HIGH");
        nMgr.setUserId(PASSIVE_USER);
        db.add(nMgr);
        inventory(DOMAIN, TEAM_N);

        service.processResults(List.of(hijackedResult(DOMAIN)));

        verify(emailService, never()).sendAlert(any(String[].class), any(), any(), any(), any(), any(), any(), any());
        assertThat(logRows).extracting(NotificationLog::getEmailStatus).contains(InactiveRecipientGuard.STATUS_SKIPPED)
                .doesNotContain("SKIPPED: alıcı yok");
    }

    @Test
    @DisplayName("Pozitif kontrol: kişinin kullanıcısı AKTİF (pasif listesi boş) → bugünkü gibi e-posta + webhook")
    void noPassiveUsers_unchanged() {
        when(userRepo.findInactiveIdsAndEmails()).thenReturn(List.of());
        guard.evict();

        service.processResults(List.of(hijackedResult(DOMAIN)));

        assertThat(sentTo()).containsExactlyInAnyOrder(B_MAIL, B_MANAGER);
        verify(webhookService).send(eq("TEAMS"), eq(B_HOOK), anyString(), anyString(), eq("CRITICAL"));
    }

    // ── yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private List<EscalationContact> active(java.util.function.Predicate<EscalationContact> p) {
        return db.stream().filter(c -> Boolean.TRUE.equals(c.getActive())).filter(p).toList();
    }

    private void team(long id, String name, String email) {
        Team t = new Team();
        t.setId(id); t.setName(name); t.setEmail(email);
        when(teamRepo.findById(id)).thenReturn(Optional.of(t));
    }

    private void inventory(String domain, Long sy) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setTeamId(sy);
        inventory.put(domain, i);
    }

    private static EscalationContact contact(long id, Long teamId, String email, String role, String minLevel) {
        EscalationContact c = new EscalationContact();
        c.setId(id); c.setTeamId(teamId); c.setName("Kişi " + id); c.setEmail(email);
        c.setRole(role); c.setMinAlertLevel(minLevel); c.setActive(true);
        return c;
    }

    private AlertEvent openEvent(Long teamId, String level) {
        AlertEvent e = new AlertEvent();
        e.setId(7L); e.setDomain(DOMAIN); e.setAlertType(EscalationService.TYPE_HOSTNAME_MISMATCH);
        e.setAlertLevel(level); e.setTeamId(teamId); e.setResolved(false); e.setAcknowledged(false);
        e.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        e.setLastReAlertAt(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        return e;
    }

    private Map<String, Object> hijackedResult(String domain) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", domain); r.put("status", "valid"); r.put("warning", false); r.put("days_remaining", 300);
        r.put("revocation_status", "VALID"); r.put("chain_status", "VALID"); r.put("deployment_status", "OK");
        r.put("san", List.of("baska.example.com"));   // HOSTNAME_MISMATCH (KRİTİK)
        r.put("trust_status", "TRUSTED");
        return r;
    }

    private AlertEvent lastSavedEvent() {
        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeastOnce()).save(cap.capture());
        return cap.getValue();
    }

    private List<String> sentTo() {
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService, atLeastOnce()).sendAlert(to.capture(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        List<String> out = new ArrayList<>();
        for (String[] a : to.getAllValues()) out.addAll(Arrays.asList(a));
        return out;
    }
}
