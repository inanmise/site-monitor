package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
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
 * Ürün güvencesi (2026-10-01): "Adım tanımlanmadıkça kimseye yeni bildirim gitmez." Hiçbir kişide gecikme yokken
 * (null ya da 0) anlık yolların — ilk bildirim, seviye artışı, günlük hatırlatma, çözüm, elle yeniden gönderim
 * önizlemesi — alıcıları ve {@code notification_logs} satırları BUGÜNKÜYLE aynıdır ve adım tablosu HİÇ okunmaz. Gecikmeli
 * kişi ise adımı gidene dek bu yolların hiçbirine girmez; adım gittikten sonra o alarmın normal alıcısıdır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationStepRegressionTest {

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
    @Mock NotificationGroupService notificationGroups;
    @Mock AppSettingsService appSettings;
    @Mock AlertEscalationStepRepository stepRepo;

    static final long TEAM = 42L;
    static final String DOMAIN = "shop.example.com";
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    EscalationService service;
    final List<NotificationLog> rows = new ArrayList<>();
    final Map<Long, AlertEvent> events = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "escalationStepRepo", stepRepo);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(smtpSettings.getOrDefaults()).thenReturn(new com.sitemonitor.model.SmtpSettings());
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());   // genel aralık 24 saat
        Team team = new Team();
        team.setId(TEAM);
        team.setName("Ödeme");
        team.setEmail("team@x.com");
        when(teamRepo.findById(TEAM)).thenReturn(Optional.of(team));
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(DOMAIN);
        inv.setTeamId(TEAM);
        when(inventoryRepo.findByDomain(DOMAIN)).thenReturn(Optional.of(inv));
        when(alertEventRepo.save(any())).thenAnswer(i -> {
            AlertEvent e = i.getArgument(0);
            if (e.getId() == null) e.setId(100L + events.size());
            events.put(e.getId(), e);
            return e;
        });
        when(alertEventRepo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(events.get((Long) i.getArgument(0))));
        when(alertEventRepo.markResolvedIfOpen(any(), any(), any())).thenReturn(1);
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn("SENT");
        when(emailService.buildAlertEmailHtml(anyString(), anyString(), any(), any(), any(), any(), any())).thenReturn("<html/>");
        when(emailService.sendResolutionAlert(any(String[].class), anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn("SENT");
        when(notificationLogRepo.save(any())).thenAnswer(i -> { rows.add(i.getArgument(0)); return i.getArgument(0); });
    }

    static EscalationContact contact(long id, String email, Integer delay, boolean webhook) {
        EscalationContact c = new EscalationContact();
        c.setId(id);
        c.setTeamId(TEAM);
        c.setName("Kişi " + id);
        c.setEmail(email);
        c.setRole("MANAGER");
        c.setMinAlertLevel("WARNING");
        c.setActive(true);
        c.setDelayMinutes(delay);
        if (webhook) { c.setWebhookUrl("https://hooks.example.com/" + id); c.setWebhookType("TEAMS"); }
        return c;
    }

    private void givenContacts(EscalationContact... cs) {
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(TEAM)).thenReturn(List.of(cs));
        when(contactRepo.findByTeamIdAndMinAlertLevelInAndActiveTrue(eq(TEAM), anyList())).thenReturn(List.of(cs));
        when(contactRepo.findByTeamIdAndMinAlertLevelAndActiveTrue(TEAM, "WARNING")).thenReturn(List.of(cs));
    }

    private AlertEvent openEvent(String level, int lastAlertHoursAgo) {
        AlertEvent e = new AlertEvent();
        e.setId(7L);
        e.setDomain(DOMAIN);
        e.setAlertType("ACCESSIBILITY");
        e.setAlertLevel(level);
        e.setTeamId(TEAM);
        e.setMessage(level + ": " + DOMAIN + " erişilemiyor");
        e.setCreatedAt(ISO.format(Instant.now().minus(3, ChronoUnit.DAYS)));
        e.setLastReAlertAt(ISO.format(Instant.now().minus(lastAlertHoursAgo, ChronoUnit.HOURS)));
        e.setAcknowledged(false);
        e.setResolved(false);
        events.put(e.getId(), e);
        when(alertEventRepo.findOpenAlert(DOMAIN, "ACCESSIBILITY")).thenReturn(Optional.of(e));
        return e;
    }

    private static Map<String, Object> ctx() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("detail", "HTTP 503");
        return m;
    }

    /** Satır parmak izi — gönderim anı hariç her şey. */
    private static String fp(NotificationLog l) {
        return l.getTrigger() + "|" + l.getRecipientRole() + "|" + l.getRecipientName() + "|" + l.getRecipientEmail()
                + "|" + l.getSubject() + "|" + l.getEmailStatus() + "|" + l.getWebhookStatus();
    }

    private List<String[]> mailRecipients() {
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService, atLeast(0)).sendAlert(to.capture(), anyString(), anyString(), any(), any(), any(), any(), any());
        return to.getAllValues();
    }

    // ── gecikme YOK: bugünkü davranış birebir ───────────────────────────────────────────────────

    @Test
    @DisplayName("Gecikmesiz liste süzgeçten AYNI NESNE olarak döner ve adım tablosu hiç okunmaz")
    void noDelays_filterIsIdentity_noStepQuery() {
        List<EscalationContact> list = List.of(contact(1, "a@x.com", null, false), contact(2, "b@x.com", 0, true));
        assertThat(service.dueNow(list, 7L)).isSameAs(list);
        assertThat(EscalationDelay.immediateOnly(list)).isSameAs(list);
        verifyNoInteractions(stepRepo);
    }

    @Test
    @DisplayName("Gecikme yok: İLK bildirim — alıcılar, notifiedContacts ve günlük satırları bugünkü gibi; adım tablosuna dokunulmaz")
    void noDelays_initial_recipientsAndRowsUnchanged() {
        givenContacts(contact(1, "a@x.com", null, true), contact(2, "b@x.com", null, false));
        when(alertEventRepo.findOpenAlert(DOMAIN, "ACCESSIBILITY")).thenReturn(Optional.empty());

        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "CRITICAL", ctx());

        assertThat(mailRecipients()).singleElement()
                .satisfies(to -> assertThat(to).containsExactly("team@x.com", "a@x.com", "b@x.com"));
        verify(webhookService).send(eq("TEAMS"), eq("https://hooks.example.com/1"), anyString(), anyString(), eq("CRITICAL"));
        assertThat(rows).extracting(EscalationStepRegressionTest::fp).containsExactly(
                "INITIAL|COMBINED|Ödeme|team@x.com, a@x.com, b@x.com|" + rows.get(0).getSubject() + "|SENT|SKIPPED",
                "INITIAL|MANAGER|Kişi 1|a@x.com|" + rows.get(0).getSubject() + "|SKIPPED|SENT");
        AlertEvent saved = events.values().iterator().next();
        assertThat(saved.getNotifiedContacts()).contains("a@x.com", "b@x.com");
        verifyNoInteractions(stepRepo);
    }

    @Test
    @DisplayName("Gecikme 0 ≡ gecikme yok: ilk bildirim, seviye artışı, hatırlatma ve çözümde satırlar null-gecikmeyle BİREBİR aynı")
    void zeroDelay_isIdenticalToNoDelay_onEveryPath() {
        List<String> withNull = runAllPaths(null);
        rows.clear(); events.clear();
        reset(emailService, webhookService);
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn("SENT");
        when(emailService.buildAlertEmailHtml(anyString(), anyString(), any(), any(), any(), any(), any())).thenReturn("<html/>");
        when(emailService.sendResolutionAlert(any(String[].class), anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn("SENT");
        List<String> withZero = runAllPaths(0);

        assertThat(withNull).isNotEmpty();
        assertThat(withZero).containsExactlyElementsOf(withNull);
        assertThat(withNull).anyMatch(s -> s.startsWith("INITIAL|COMBINED|"))
                .anyMatch(s -> s.startsWith("ESCALATION|COMBINED|"))
                .anyMatch(s -> s.startsWith("DAILY_REALERT|COMBINED|"))
                .anyMatch(s -> s.startsWith("RESOLUTION|COMBINED|"));
        verifyNoInteractions(stepRepo);
    }

    /** İlk bildirim → seviye artışı → günlük hatırlatma → çözüm; satır parmak izleri (konu tarih içermez). */
    private List<String> runAllPaths(Integer delay) {
        givenContacts(contact(1, "a@x.com", delay, true), contact(2, "b@x.com", delay, false));
        when(alertEventRepo.findOpenAlert(DOMAIN, "ACCESSIBILITY")).thenReturn(Optional.empty());
        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "HIGH", ctx());          // INITIAL
        AlertEvent e = openEvent("HIGH", 1);
        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "CRITICAL", ctx());      // ESCALATION
        e.setLastReAlertAt(ISO.format(Instant.now().minus(25, ChronoUnit.HOURS)));
        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "CRITICAL", ctx());      // DAILY_REALERT
        e.setResolved(true);
        e.setResolvedAt(ISO.format(Instant.now()));
        service.sendResolutionNotificationAsync(e, "auto", "RESOLUTION");               // RESOLUTION
        List<String> out = new ArrayList<>();
        for (NotificationLog l : rows) out.add(fp(l));
        return out;
    }

    @Test
    @DisplayName("Gecikme yok: elle yeniden gönderim önizlemesi takım + tüm kişiler (bugünkü liste)")
    void noDelays_reNotifyPreview_unchanged() {
        givenContacts(contact(1, "a@x.com", null, false), contact(2, "b@x.com", null, false));
        openEvent("CRITICAL", 1);

        assertThat(service.previewReNotify(7L)).extracting(EscalationService.ReNotifyRecipient::email)
                .containsExactly("team@x.com", "a@x.com", "b@x.com");
        verifyNoInteractions(stepRepo);
    }

    // ── gecikmeli kişi: anlık yollardan düşer, adımdan sonra döngüdedir ─────────────────────────

    @Test
    @DisplayName("Gecikmeli kişi İLK bildirime girmez (e-posta, webhook, notifiedContacts) — yeni olayda adım tablosu da okunmaz")
    void delayedContact_excludedFromInitial() {
        givenContacts(contact(1, "a@x.com", null, false), contact(2, "mgr@x.com", 30, true));
        when(alertEventRepo.findOpenAlert(DOMAIN, "ACCESSIBILITY")).thenReturn(Optional.empty());

        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "CRITICAL", ctx());

        assertThat(mailRecipients()).singleElement()
                .satisfies(to -> assertThat(to).containsExactly("team@x.com", "a@x.com"));
        verifyNoInteractions(webhookService);
        assertThat(events.values().iterator().next().getNotifiedContacts()).contains("a@x.com").doesNotContain("mgr@x.com");
        assertThat(rows).noneMatch(l -> "mgr@x.com".equals(l.getRecipientEmail()));
        verifyNoInteractions(stepRepo);
    }

    @Test
    @DisplayName("Günlük hatırlatma: adımı gitmemiş gecikmeli kişi YOK; adımı gitmiş (döngüdeki) kişi VAR")
    void realert_pendingExcluded_steppedIncluded() {
        givenContacts(contact(1, "a@x.com", null, false), contact(2, "mgr@x.com", 30, false));
        AlertEvent e = openEvent("CRITICAL", 25);
        when(stepRepo.findNotifiedContactIds(7L)).thenReturn(List.of());

        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "CRITICAL", ctx());
        assertThat(mailRecipients()).singleElement()
                .satisfies(to -> assertThat(to).containsExactly("team@x.com", "a@x.com"));

        when(stepRepo.findNotifiedContactIds(7L)).thenReturn(List.of(2L));
        e.setLastReAlertAt(ISO.format(Instant.now().minus(25, ChronoUnit.HOURS)));
        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "CRITICAL", ctx());
        assertThat(mailRecipients()).hasSize(2).last()
                .satisfies(to -> assertThat(to).containsExactly("team@x.com", "a@x.com", "mgr@x.com"));
    }

    @Test
    @DisplayName("Çözüm: alarmı hiç duymamış gecikmeli kişiye 'çözüldü' gitmez; adımı almış kişiye gider")
    void resolution_onlyToSteppedDelayedContact() {
        givenContacts(contact(1, "a@x.com", null, false), contact(2, "mgr@x.com", 30, true));
        AlertEvent e = openEvent("CRITICAL", 1);
        e.setResolved(true);
        e.setResolvedAt(ISO.format(Instant.now()));
        when(stepRepo.findNotifiedContactIds(7L)).thenReturn(List.of());

        service.sendResolutionNotificationAsync(e, "auto", "RESOLUTION");
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendResolutionAlert(to.capture(), anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any());
        assertThat(to.getValue()).containsExactly("team@x.com", "a@x.com");
        verifyNoInteractions(webhookService);

        when(stepRepo.findNotifiedContactIds(7L)).thenReturn(List.of(2L));
        service.sendResolutionNotificationAsync(e, "auto", "RESOLUTION");
        verify(emailService, times(2)).sendResolutionAlert(to.capture(), anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        assertThat(to.getValue()).containsExactly("team@x.com", "a@x.com", "mgr@x.com");
        verify(webhookService).send(eq("TEAMS"), eq("https://hooks.example.com/2"), anyString(), anyString(), eq("INFO"));
    }

    @Test
    @DisplayName("Elle yeniden gönderim önizlemesi gerçek gönderimle aynı: adımı gitmemiş gecikmeli kişi listede yok")
    void reNotifyPreview_excludesPendingDelayed() {
        givenContacts(contact(1, "a@x.com", null, false), contact(2, "mgr@x.com", 45, false));
        openEvent("CRITICAL", 1);
        when(stepRepo.findNotifiedContactIds(7L)).thenReturn(List.of());

        assertThat(service.previewReNotify(7L)).extracting(EscalationService.ReNotifyRecipient::email)
                .containsExactly("team@x.com", "a@x.com");
    }

    @Test
    @DisplayName("Simülatör: gecikmeli kişi 'delay_minutes' ile işaretlenir ve ilk e-posta toplamına girmez; gecikmesiz çıktı aynı")
    void simulator_marksDelayedContacts() {
        givenContacts(contact(1, "a@x.com", null, false), contact(2, "mgr@x.com", 30, true));

        Map<String, Object> out = service.simulateRecipients(TEAM, "CRITICAL", false, null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rowsOut = (List<Map<String, Object>>) out.get("contacts");
        assertThat(rowsOut.get(0)).doesNotContainKey("delay_minutes");
        assertThat(rowsOut.get(1)).containsEntry("delay_minutes", 30);
        assertThat(out.get("email_total")).isEqualTo(2L);   // takım + a; gecikmeli müdür sayılmaz
    }
}
