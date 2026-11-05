package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertForward;
import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.forward.ForwardTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Alarm iletimi — e-posta yolları (2026-11-05, kullanıcı kararları 3 / 6 / 7): sahip takımın iletimleri alarm sahibin aldığı her
 * bildirimi (açılış, elle gönderim, çözüm) alıcı takıma AYRI posta (bant) + push olarak götürür; sahip postası değişmez,
 * adresler tekilleşir, her karar günlükte FORWARD satırı, teslim edilen adres alarmın iletim damgasına yazılır, çözüm damgadan.
 * Yönlendirme yokken / takımın iletimi yokken hiçbir şey değişmez.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationForwardTest {

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
    @Mock TeamQuietHoursService quietHours;
    @Mock AlertForwardRepository forwardRepo;

    static final long TEAM_A = 1L, TEAM_UG = 2L, TEAM_B = 3L, TEAM_C = 4L;
    static final String A_MAIL = "takim-a@example.com", UG_MAIL = "takim-ug@example.com",
            B_MAIL = "takim-b@example.com", C_MAIL = "takim-c@example.com";
    static final String B_NAME = "Ağ Operasyon Merkezi — 7/24 İzleme ve Olay Yönetimi Takımı";
    static final String DOMAIN = "a.example.com";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    EscalationService service;
    ForwardFakes.Push push;
    CertificateInventory inv;

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);
        push = new ForwardFakes.Push();
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(smtpSettings.getOrDefaults()).thenReturn(new com.sitemonitor.model.SmtpSettings());
        AlertThreshold t = new AlertThreshold();
        t.setWarningDays(30); t.setHighDays(15); t.setCriticalDays(7); t.setReAlertIntervalHours(24);
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.of(t));
        when(alertEventRepo.markResolvedIfOpen(any(), any(), any())).thenReturn(1);
        when(alertEventRepo.setForwardEmailsIfNull(any(), any())).thenReturn(1);
        when(alertEventRepo.setForwardEmailsIfUnchanged(any(), any(), any())).thenReturn(1);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        when(alertEventRepo.save(any())).thenAnswer(i -> {
            AlertEvent e = i.getArgument(0);
            if (e.getId() == null) e.setId(100L);
            return e;
        });
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("SENT");
        when(emailService.sendResolutionAlert(any(String[].class), anyString(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenReturn("SENT");
        team(TEAM_A, "Takım A", A_MAIL);
        team(TEAM_UG, "Takım UG", UG_MAIL);
        team(TEAM_B, B_NAME, B_MAIL);
        team(TEAM_C, "Takım C", C_MAIL);
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(any())).thenReturn(List.of());
        when(contactRepo.findByTeamIdAndMinAlertLevelInAndActiveTrue(any(), anyList())).thenReturn(List.of());
        when(contactRepo.findByTeamIdAndMinAlertLevelAndActiveTrue(any(), anyString())).thenReturn(List.of());
        inv = new CertificateInventory();
        inv.setDomain(DOMAIN); inv.setTeamId(TEAM_A);
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of(inv));
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.of(inv));
    }

    private void team(long id, String name, String email) {
        Team team = new Team();
        team.setId(id); team.setName(name); team.setEmail(email);
        when(teamRepo.findById(id)).thenReturn(Optional.of(team));
    }

    private ForwardFakes.Routing routing(Map<Long, List<ForwardTarget>> byOwner) {
        ForwardFakes.Routing r = new ForwardFakes.Routing(byOwner);
        service.setForwarding(r, push);
        return r;
    }

    private static ForwardTarget toB(String... emails) {
        return ForwardFakes.target(TEAM_B, B_NAME, List.of("Takım A"), List.of(emails));
    }

    private Map<String, Object> hijacked() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", DOMAIN); r.put("status", "valid"); r.put("warning", false); r.put("days_remaining", 300);
        r.put("revocation_status", "VALID"); r.put("chain_status", "VALID"); r.put("deployment_status", "OK");
        r.put("san", List.of("baska.example.com"));
        r.put("trust_status", "TRUSTED");
        return r;
    }

    private List<String[]> sends() {
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService, atLeastOnce()).sendAlert(to.capture(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        return to.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sentCtx() {
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(emailService, atLeastOnce()).sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), ctx.capture());
        return ctx.getAllValues();
    }

    private List<NotificationLog> logs() {
        ArgumentCaptor<NotificationLog> c = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo, atLeast(0)).save(c.capture());
        return c.getAllValues();
    }

    private List<NotificationLog> forwardLogs() {
        return logs().stream().filter(l -> EscalationService.ROLE_FORWARD.equals(l.getRecipientRole())).toList();
    }

    // ── açılış ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("yönlendirme yok / sahibin iletimi yok → sahip postası bugünkü gibi, iletim satırı / damga / push yok")
    void noForwardingIsUnchanged() {
        service.processResults(List.of(hijacked()));
        assertThat(sends()).hasSize(1);
        assertThat(sends().get(0)).containsExactly(A_MAIL);
        assertThat(forwardLogs()).isEmpty();
        verify(alertEventRepo, never()).setForwardEmailsIfNull(any(), any());

        reset(emailService, notificationLogRepo);
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("SENT");
        ForwardFakes.Routing r = routing(Map.of(TEAM_C, List.of(toB(B_MAIL))));   // başka takımın iletimi
        service.processResults(List.of(hijacked()));
        assertThat(sends()).hasSize(1);
        assertThat(r.anyCalls).containsExactly(List.of(TEAM_A));
        assertThat(r.refs).isEmpty();
        assertThat(forwardLogs()).isEmpty();
        assertThat(push.calls).isEmpty();
        verify(alertEventRepo, never()).findForwardEmails(any());
    }

    @Test
    @DisplayName("açılış: sahip postası aynen + alıcı takıma AYRI posta (bant bağlamı), FORWARD satırı, damga, push")
    void openingForwardedSeparately() {
        routing(Map.of(TEAM_A, List.of(toB(B_MAIL))));
        service.processResults(List.of(hijacked()));

        List<String[]> to = sends();
        assertThat(to).hasSize(2);
        assertThat(to.get(0)).containsExactly(A_MAIL);   // sahip postası — iletim adresi YOK
        assertThat(to.get(1)).containsExactly(B_MAIL);
        List<Map<String, Object>> ctx = sentCtx();
        assertThat(ctx.get(0)).doesNotContainKey(EmailTemplateBuilder.CTX_FORWARD_NOTE);
        @SuppressWarnings("unchecked")
        Map<String, Object> note = (Map<String, Object>) ctx.get(1).get(EmailTemplateBuilder.CTX_FORWARD_NOTE);
        assertThat(note).containsEntry("recipient_team", B_NAME);
        assertThat(note.get("owner_teams")).isEqualTo(List.of("Takım A"));

        assertThat(forwardLogs()).singleElement().satisfies(l -> {
            assertThat(l.getRecipientName()).isEqualTo(B_NAME);
            assertThat(l.getRecipientEmail()).isEqualTo(B_MAIL);
            assertThat(l.getEmailStatus()).isEqualTo("SENT");
            assertThat(l.getTrigger()).isEqualTo("INITIAL");
            assertThat(l.getAlertEventId()).isEqualTo(100L);
        });
        verify(alertEventRepo).setForwardEmailsIfNull(100L, "{\"3\":[\"" + B_MAIL + "\"]}");
        assertThat(push.calls).singleElement().satisfies(c -> {
            assertThat(c.alertEventId()).isEqualTo(100L);
            assertThat(c.trigger()).isEqualTo("INITIAL");
            assertThat(c.targets()).extracting(ForwardTarget::recipientTeamId).containsExactly(TEAM_B);
        });
    }

    @Test
    @DisplayName("tekilleştirme: sahip postasındaki adres iletimde yok; iki hedef arası tekrar yok; hepsi tekrarsa posta yok, karar satırı")
    void addressDedupe() {
        routing(Map.of(TEAM_A, List.of(
                toB(A_MAIL.toUpperCase(), B_MAIL),
                ForwardFakes.target(TEAM_C, "Takım C", List.of("Takım A"), List.of(B_MAIL, C_MAIL)),
                ForwardFakes.target(5L, "Takım D", List.of("Takım A"), List.of(" " + C_MAIL + " ")))));
        service.processResults(List.of(hijacked()));
        List<String[]> to = sends();
        assertThat(to).hasSize(3);
        assertThat(to.get(1)).containsExactly(B_MAIL);
        assertThat(to.get(2)).containsExactly(C_MAIL);
        List<NotificationLog> rows = forwardLogs();
        assertThat(rows).extracting(NotificationLog::getRecipientName).containsExactly(B_NAME, "Takım C", "Takım D");
        assertThat(rows.get(2).getEmailStatus()).isEqualTo(EscalationService.STATUS_FORWARD_ALREADY_MAILED);
        verify(alertEventRepo).setForwardEmailsIfNull(100L, "{\"3\":[\"" + B_MAIL + "\"],\"4\":[\"" + C_MAIL + "\"]}");
    }

    @Test
    @DisplayName("adresi olmayan hedef (yalnız push) → 'alıcı yok' satırı, push yine; başarısız posta damgalanmaz")
    void noAddressAndFailedSend() {
        routing(Map.of(TEAM_A, List.of(toB(), ForwardFakes.target(TEAM_C, "Takım C", List.of("Takım A"), List.of(C_MAIL)))));
        when(emailService.sendAlert(ArgumentMatchers.<String[]>argThat(a -> a != null && Arrays.asList(a).contains(C_MAIL)), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), any())).thenReturn("FAILED: 550");
        service.processResults(List.of(hijacked()));
        List<NotificationLog> rows = forwardLogs();
        assertThat(rows).extracting(NotificationLog::getEmailStatus).containsExactly("SKIPPED: alıcı yok", "FAILED: 550");
        verify(alertEventRepo, never()).setForwardEmailsIfNull(any(), any());
        assertThat(push.calls).singleElement().satisfies(c ->
                assertThat(c.targets()).extracting(ForwardTarget::recipientTeamId).containsExactly(TEAM_B, TEAM_C));
    }

    @Test
    @DisplayName("sahip takımlardan biri olan alıcı atlanır (SY / UG — ertelenen sahip dahil)")
    void ownerRecipientSkipped() {
        ForwardFakes.Routing r = new ForwardFakes.Routing(Map.of(TEAM_A, List.of(
                ForwardFakes.target(TEAM_UG, "Takım UG", List.of("Takım A"), List.of(UG_MAIL)), toB(B_MAIL))));
        assertThat(EscalationService.forwardTargets(r, List.of(TEAM_A), TEAM_A, TEAM_UG, "EXPIRY", DOMAIN, null))
                .extracting(ForwardTarget::recipientTeamId).containsExactly(TEAM_B);
        assertThat(EscalationService.forwardTargets(null, List.of(TEAM_A), TEAM_A, null, "EXPIRY", DOMAIN, null)).isEmpty();
    }

    // ── sessiz saat ──────────────────────────────────────────────────────────────────────────────────

    private void quiet(long... deferred) {
        service.setForwarding(null, push);
        ReflectionTestUtils.setField(service, "quietHours", quietHours);
        when(quietHours.isConfigured(any())).thenReturn(true);
        when(quietHours.now()).thenReturn(Instant.now());
        ZonedDateTime s = ZonedDateTime.now(QuietHours.ZONE);
        for (long d : deferred) {
            when(quietHours.deferral(eq(d), any(), any())).thenReturn(new QuietHours.Occurrence(s, s.plusHours(8)));
        }
    }

    @Test
    @DisplayName("sessiz saat: sahip tamamen ertelendiyse iletim ŞİMDİ gitmez (özete girer); kısmide yalnız ertelenmeyen sahibin iletimleri")
    void quietHoursFollowOwner() {
        quiet(TEAM_A);
        ForwardFakes.Routing r = routing(Map.of(TEAM_A, List.of(toB(B_MAIL))));
        service.processResults(List.of(expiring(25)));
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        assertThat(r.anyCalls).isEmpty();
        assertThat(push.calls).isEmpty();

        // kısmi: SY penceresinde, UG değil → yalnız UG'nin iletimleri (SY'ninki SY özetine)
        reset(emailService, notificationLogRepo);
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("SENT");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        inv.setUgTeamId(TEAM_UG);
        ForwardFakes.Routing r2 = routing(Map.of(TEAM_A, List.of(toB(B_MAIL)),
                TEAM_UG, List.of(ForwardFakes.target(TEAM_C, "Takım C", List.of("Takım UG"), List.of(C_MAIL)))));
        service.processResults(List.of(expiring(25)));
        assertThat(r2.refs).singleElement().satisfies(ref -> assertThat(ref.ownerTeamIds()).containsExactly(TEAM_UG));
        List<String[]> to = sends();
        assertThat(to).extracting(a -> List.of(a)).containsExactly(List.of(UG_MAIL), List.of(C_MAIL));
    }

    private Map<String, Object> expiring(int days) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", DOMAIN); r.put("status", "warning"); r.put("warning", true); r.put("days_remaining", days);
        r.put("revocation_status", "VALID"); r.put("chain_status", "VALID"); r.put("deployment_status", "OK");
        return r;
    }

    // ── elle gönderim ───────────────────────────────────────────────────────────────────────────────

    private AlertEvent openMonitor(String ctxJson) {
        AlertEvent e = new AlertEvent();
        e.setId(7L); e.setDomain("https://portal.example.com/odeme"); e.setAlertType(EscalationService.TYPE_HTTP_DOWN);
        e.setAlertLevel("HIGH"); e.setTeamId(TEAM_A); e.setResolved(false); e.setAcknowledged(false);
        e.setMessage("YÜKSEK: portal erişilemez");
        e.setContextJson(ctxJson);
        e.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        e.setLastReAlertAt(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        when(alertEventRepo.findById(7L)).thenReturn(Optional.of(e));
        return e;
    }

    @Test
    @DisplayName("elle gönderim: önizleme FORWARD satırı (alıcı takım), sayım; hariç tutulan iletim adresi gitmez; izleme kimliği yönlendirmeye")
    void manualPreviewAndSend() {
        openMonitor("{\"monitor_id\":44,\"team_id\":1}");
        ForwardFakes.Routing r = routing(Map.of(TEAM_A, List.of(toB(B_MAIL, A_MAIL),
                ForwardFakes.target(TEAM_C, "Takım C", List.of("Takım A"), List.of(C_MAIL)))));
        List<EscalationService.ReNotifyRecipient> preview = service.previewReNotify(7L);
        assertThat(preview).extracting(EscalationService.ReNotifyRecipient::email).containsExactly(A_MAIL, B_MAIL, C_MAIL);
        assertThat(preview.get(1).kind()).isEqualTo("FORWARD");
        assertThat(preview.get(1).teamName()).isEqualTo(B_NAME);
        assertThat(preview.get(1).role()).contains(B_NAME);
        assertThat(preview.get(0).teamName()).isNull();
        assertThat(r.refs.get(0).monitorId()).isEqualTo(44L);
        assertThat(r.refs.get(0).alertType()).isEqualTo(EscalationService.TYPE_HTTP_DOWN);

        Map<String, Object> res = service.reNotify(7L, Set.of(C_MAIL.toUpperCase()), Set.of("u1"));
        assertThat(res.get("recipients_queued")).isEqualTo(2);   // A + B (C hariç)
        List<String[]> to = sends();
        assertThat(to).extracting(a -> List.of(a)).containsExactly(List.of(A_MAIL), List.of(B_MAIL));
        List<NotificationLog> rows = forwardLogs();
        assertThat(rows).extracting(NotificationLog::getEmailStatus)
                .containsExactly("SENT", EscalationService.STATUS_SKIPPED_EMAIL_DESELECTED);
        assertThat(rows).extracting(NotificationLog::getTrigger).containsOnly("MANUAL");
        assertThat(push.calls).singleElement().satisfies(c -> {
            assertThat(c.trigger()).isEqualTo("MANUAL");
            assertThat(c.excludeUsernames()).containsExactly("u1");
        });
    }

    @Test
    @DisplayName("elle gönderim: tüm e-postalar çıkarıldı ama iletim push'u (önizlemede) kaldı → kabul (yalnız push)")
    void manualPushOnlyForwardAccepted() {
        openMonitor("{\"monitor_id\":44}");
        routing(Map.of(TEAM_A, List.of(toB(B_MAIL))));
        when(userPushService.preview(eq(7L), any(), any())).thenReturn(new UserPushService.PushPreview(true, null, List.of(
                new UserPushService.PushPreviewRow("u.noc", "Ayşe Yılmaz (Ağ Operasyon / Uzman)", "PENDING", null,
                        UserPushService.PREVIEW_KIND_FORWARD, B_NAME))));
        assertThatCode(() -> service.reNotify(7L, Set.of(A_MAIL, B_MAIL), Set.of())).doesNotThrowAnyException();
        // iletim yokken aynı istek bugünkü gibi reddedilir
        service.setForwarding(null, null);
        when(userPushService.preview(eq(7L), any(), any())).thenReturn(null);
        assertThatThrownBy(() -> service.reNotify(7L, Set.of(A_MAIL), Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("elle gönderim: tüm e-postalar + tüm push alıcıları (iletim dahil) çıkarıldı → 400, son bildirim damgası yazılmaz")
    void manualEverythingExcludedRejected() {
        AlertEvent e = openMonitor("{\"monitor_id\":44}");
        String stampBefore = e.getLastReAlertAt();
        routing(Map.of(TEAM_A, List.of(toB(B_MAIL))));   // iletim hedefinin push alıcısı VAR (tüm takım)
        when(userPushService.preview(eq(7L), any(), any())).thenReturn(new UserPushService.PushPreview(true, null, List.of(
                new UserPushService.PushPreviewRow("u.sahip", "Sahip Kişi", "PENDING"),
                new UserPushService.PushPreviewRow("u.noc", "Ayşe Yılmaz (Ağ Operasyon / Uzman)", "PENDING", null,
                        UserPushService.PREVIEW_KIND_FORWARD, B_NAME))));
        assertThatThrownBy(() -> service.reNotify(7L, Set.of(A_MAIL, B_MAIL), Set.of("u.sahip", "u.noc")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(e.getLastReAlertAt()).as("reddedilen istek damga yazmaz").isEqualTo(stampBefore);
        verify(alertEventRepo, never()).save(e);
        assertThat(push.calls).isEmpty();
        assertThat(forwardLogs()).isEmpty();
    }

    @Test
    @DisplayName("izlemede e-posta kapalı: sahip ve iletim satırı 'kanal kapalı', posta yok, damga yok, push yine")
    void mailDisabledFollowsOwner() {
        openMonitor("{\"monitor_id\":44,\"mail_disabled\":true}");
        routing(Map.of(TEAM_A, List.of(toB(B_MAIL))));
        assertThat(service.previewReNotify(7L)).isEmpty();
        service.reNotify(7L, Set.of(), Set.of());
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        assertThat(forwardLogs()).singleElement().satisfies(l ->
                assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: e-posta kanalı kapalı"));
        verify(alertEventRepo, never()).setForwardEmailsIfNull(any(), any());
        assertThat(push.calls).hasSize(1);
    }

    // ── çözüm ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("çözüm: damgadaki her alıcı takıma ayrı çözüm postası (bant), sahip adresi tekrar etmez; damga yoksa hiçbir şey")
    void resolutionFromStamp() {
        AlertEvent e = openMonitor("{\"monitor_id\":44}");
        e.setForwardEmails("{\"3\":[\"" + B_MAIL + "\",\"" + A_MAIL + "\"],\"4\":[\"" + C_MAIL + "\"]}");
        routing(Map.of());   // canlı iletim YOK — çözüm damgadan
        service.resolve(7L, "Kişi A");
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> ctx = ArgumentCaptor.forClass(Map.class);
        verify(emailService, times(3)).sendResolutionAlert(to.capture(), anyString(), any(), any(), any(), any(), any(), any(),
                any(), ctx.capture(), any(), any());
        assertThat(to.getAllValues()).extracting(a -> List.of(a))
                .containsExactly(List.of(A_MAIL), List.of(B_MAIL), List.of(C_MAIL));
        assertThat(ctx.getAllValues().get(0)).doesNotContainKey(EmailTemplateBuilder.CTX_FORWARD_NOTE);
        assertThat(ctx.getAllValues().get(1)).containsKey(EmailTemplateBuilder.CTX_FORWARD_NOTE);
        assertThat(forwardLogs()).extracting(NotificationLog::getRecipientName).containsExactly(B_NAME, "Takım C");
        assertThat(forwardLogs()).extracting(NotificationLog::getTrigger).containsOnly("MANUAL_RESOLVE");

        // damgasız alarm: yalnız sahip
        reset(emailService, notificationLogRepo);
        AlertEvent plain = openMonitor("{\"monitor_id\":44}");
        plain.setForwardEmails(null);
        service.resolve(7L, "Kişi A");
        verify(emailService, times(1)).sendResolutionAlert(any(String[].class), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        assertThat(forwardLogs()).isEmpty();
    }

    @Test
    @DisplayName("çözüm: izlemede e-posta kapalıysa iletim satırı 'kanal kapalı', posta yok")
    void resolutionMailDisabled() {
        AlertEvent e = openMonitor("{\"monitor_id\":44,\"mail_disabled\":true}");
        e.setForwardEmails("{\"3\":[\"" + B_MAIL + "\"]}");
        routing(Map.of());
        service.resolve(7L, "Kişi A");
        verify(emailService, never()).sendResolutionAlert(any(String[].class), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        assertThat(forwardLogs()).singleElement().satisfies(l -> {
            assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: e-posta kanalı kapalı");
            assertThat(l.getRecipientName()).isEqualTo(B_NAME);
        });
    }

    // ── simülatör ───────────────────────────────────────────────────────────────────────────────────

    private static AlertForward fwd(long id, String scope, String types, String monitors, String kind, String users) {
        AlertForward f = new AlertForward();
        f.setId(id); f.setOwnerTeamId(TEAM_A); f.setScopeKind(scope); f.setScopeTypes(types); f.setScopeMonitors(monitors);
        f.setRecipientKind(kind); f.setRecipientTeamId(TEAM_B); f.setRecipientUserIds(users); f.setEnabled(true);
        return f;
    }

    @Test
    @DisplayName("'Kim bilgilendirilir?': yönlendirme yoksa alan yok; ALL / TYPES (cert) / MONITORS satırları, duraklatılmış / süresi dolmuş yok")
    void simulatorForwards() {
        assertThat(service.simulateRecipients(TEAM_A, "HIGH", false, null)).doesNotContainKey("forwards");

        routing(Map.of(TEAM_A, List.of(toB(B_MAIL))));
        service.setForwardRepo(forwardRepo);
        AlertForward paused = fwd(4, "ALL", null, null, "TEAM", null);
        paused.setEnabled(false);
        AlertForward expired = fwd(5, "ALL", null, null, "TEAM", null);
        expired.setExpiresAt("2020-01-01T20:59:59");
        when(forwardRepo.findByOwnerTeamIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                fwd(1, "ALL", null, null, "TEAM", null),
                fwd(2, "TYPES", "http,ping", null, "MEMBERS", "11,12"),
                fwd(3, "MONITORS", null, "http:44", "TEAM", null), paused, expired));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cert = (List<Map<String, Object>>) service.simulateRecipients(TEAM_A, "HIGH", false, null).get("forwards");
        assertThat(cert).extracting(m -> m.get("forward_id")).containsExactly(1L, 3L);   // TYPES cert içermiyor
        assertThat(cert.get(0)).containsEntry("applies", "all").containsEntry("recipient_team_name", B_NAME)
                .containsEntry("emails", List.of(B_MAIL)).containsEntry("member_count", 0);
        assertThat(cert.get(1)).containsEntry("applies", "monitors_only").containsEntry("scope_kind", "MONITORS");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mon = (List<Map<String, Object>>) service.simulateRecipients(TEAM_A, "HIGH", true, null).get("forwards");
        assertThat(mon).extracting(m -> m.get("applies")).containsExactly("all", "types_only", "monitors_only");
        assertThat(mon.get(1)).containsEntry("member_count", 2).containsEntry("scope_types", List.of("http", "ping"));
    }

    @Test
    @DisplayName("'Kim bilgilendirilir?' MONITORS: öğelerin HEPSİ denenir — ilk öğe silinmiş izlemeyse satır kaybolmaz; cert öğesi tek toplu okuma")
    void simulatorMonitorsTriesEveryItem() {
        ForwardFakes.Routing r = routing(Map.of(TEAM_A, List.of(toB(B_MAIL))));
        // yalnız 44 numaralı izleme ve shop.example.com alan adı eşleşir (999 silinmiş, 70 envanteri silinmiş)
        r.filter = (ref, t) -> Long.valueOf(44L).equals(ref.monitorId()) || "shop.example.com".equals(ref.domain());
        service.setForwardRepo(forwardRepo);
        CertificateInventory shop = new CertificateInventory();
        shop.setId(71L);
        shop.setDomain("shop.example.com");
        when(inventoryRepo.findAllById(anyIterable())).thenReturn(List.of(shop));
        when(forwardRepo.findByOwnerTeamIdInOrderByIdAsc(anyCollection())).thenReturn(List.of(
                fwd(1, "MONITORS", null, "http:999,http:44", "TEAM", null),
                fwd(2, "MONITORS", null, "cert:70,cert:71", "TEAM", null),
                fwd(3, "MONITORS", null, "http:998", "TEAM", null)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> mon = (List<Map<String, Object>>) service.simulateRecipients(TEAM_A, "HIGH", true, null).get("forwards");
        assertThat(mon).extracting(m -> m.get("forward_id")).containsExactly(1L, 2L);
        assertThat(mon).extracting(m -> m.get("applies")).containsOnly("monitors_only");
        verify(inventoryRepo, never()).findById(anyLong());
    }
}
