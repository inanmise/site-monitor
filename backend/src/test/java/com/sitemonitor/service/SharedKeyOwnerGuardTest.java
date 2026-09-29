package com.sitemonitor.service;

import tools.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Y-1 (BUG_REGRESYON_2026-09-29) — EscalationService tarafı: açık olay BAŞKA sahibin (takım / envanter yönlendirmesi)
 * izlemesine aitse, bu bağlamın arızası o olayın yeniden uyarısı / terfisi / yarım ilk bildirimi OLAMAZ (bildirim yanlış
 * takıma, ileti yabancı izlemenin bağlamından). Olayı AÇAN izleme (takımı sonradan değişse de) ve kimliksiz bağlam
 * (DNS_CHANGED yeniden uyarısı) eski davranışı korur.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SharedKeyOwnerGuardTest {

    @Mock com.sitemonitor.service.NotificationGroupService notificationGroups;
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
    @Mock AppSettingsService appSettings;

    private EscalationService service;
    private AlertEvent open;

    private static final String HOST = "app.example.com";
    private static final String PORT = EscalationService.TYPE_PORT_DOWN;

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo,
                inventoryRepo, emailService, weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo,
                latestCheckRepo, teamRepo, smtpSettings, maintenanceService, stormService, userPushService,
                domainMonitorRepo, domainCheckRepo, dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        ReflectionTestUtils.setField(service, "self", service);
        when(smtpSettings.getOrDefaults()).thenReturn(new com.sitemonitor.model.SmtpSettings());
        when(alertEventRepo.save(any(AlertEvent.class))).thenAnswer(i -> i.getArgument(0));
        when(alertEventRepo.findById(anyLong())).thenReturn(Optional.empty());

        // A takımının (7) izlemesi #1'in açtığı olay — ilk bildirimi "yarım" (lastReAlertAt yok): sahibin bağlamı
        // gelirse hemen INITIAL gönderilir; yabancı bağlam hiçbir şey göndermemeli.
        open = new AlertEvent();
        open.setId(900L);
        open.setDomain(HOST);
        open.setAlertType(PORT);
        open.setAlertLevel("WARNING");
        open.setTeamId(7L);
        open.setContextJson("{\"monitor_id\":1,\"team_id\":7,\"standalone\":true,\"port\":443}");
        open.setAcknowledged(false);
        open.setResolved(false);
        open.setCreatedAt("2026-09-28T22:22:00");
        when(alertEventRepo.findOpenAlerts(HOST, PORT)).thenReturn(List.of(open));
        when(alertEventRepo.findOpenAlert(HOST, PORT)).thenReturn(Optional.of(open));   // varsayılan metot da taklit
    }

    private static Map<String, Object> ctx(Long monitorId, Long teamId) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (monitorId != null) c.put("monitor_id", monitorId);
        if (teamId != null) { c.put("team_id", teamId); c.put("standalone", true); }
        c.put("port", 8443);
        c.put("protocol", "TCP");
        return c;
    }

    @Test
    @DisplayName("Y-1: B takımının izlemesinin (#2) arızası A'nın olayına bildirim üretmez (kayıt/gönderim yok)")
    void foreignTeamContext_doesNotNotifyOwnersEvent() {
        service.processConfirmedOutage(HOST, PORT, "WARNING", ctx(2L, 8L));

        verify(alertEventRepo, never()).save(any());
        verifyNoInteractions(emailService);
        assertThat(open.getLastReAlertAt()).isNull();
        assertThat(open.getMessage()).isNull();
    }

    @Test
    @DisplayName("Y-1: envanter TÜREVİ bağlam (takımsız, #3) bağımsız olayı etkilemez")
    void derivedContext_doesNotNotifyStandaloneEvent() {
        service.processConfirmedOutage(HOST, PORT, "WARNING", ctx(3L, null));
        verify(alertEventRepo, never()).save(any());
    }

    @Test
    @DisplayName("Sahibin kendi bağlamı eski davranışı korur: yarım ilk bildirim tamamlanır")
    void ownerContext_behavesAsBefore() {
        service.processConfirmedOutage(HOST, PORT, "WARNING", ctx(1L, 7L));
        verify(alertEventRepo, atLeastOnce()).save(any());
        assertThat(open.getLastReAlertAt()).isNotNull();
    }

    @Test
    @DisplayName("Olayı AÇAN izleme sonradan başka takıma taşınsa da kendi olayının sahibidir (O5 damgası korunur)")
    void openerMovedTeam_stillOwner() {
        service.processConfirmedOutage(HOST, PORT, "WARNING", ctx(1L, 9L));
        verify(alertEventRepo, atLeastOnce()).save(any());
    }

    @Test
    @DisplayName("Aynı takımın başka izlemesi (#4, ör. 8443) aynı olayı paylaşır (D2 tasarımı)")
    void sameTeamOtherMonitor_sharesEvent() {
        service.processConfirmedOutage(HOST, PORT, "WARNING", ctx(4L, 7L));
        verify(alertEventRepo, atLeastOnce()).save(any());
    }

    @Test
    @DisplayName("İzleme kimliği taşımayan bağlam (DNS_CHANGED yeniden uyarısı gibi) — sahiplik bilinmez, eski davranış")
    void contextWithoutMonitorId_behavesAsBefore() {
        service.processConfirmedOutage(HOST, PORT, "WARNING", ctx(null, null));
        verify(alertEventRepo, atLeastOnce()).save(any());
    }

    // ── O-c2: DNS_CHANGED — başka izlemenin taze değişikliği yutulmaz, SAHİBİNE gider ────────────

    private static final String DNS_CHANGED = EscalationService.TYPE_DNS_CHANGED;
    private static final String RECENT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now().minusSeconds(3600));

    private AlertEvent openDnsChanged() {
        com.sitemonitor.model.Team ta = new com.sitemonitor.model.Team(); ta.setId(7L); ta.setName("Takım A"); ta.setEmail("a@example.com");
        com.sitemonitor.model.Team tb = new com.sitemonitor.model.Team(); tb.setId(8L); tb.setName("Takım B"); tb.setEmail("b@example.com");
        when(teamRepo.findById(7L)).thenReturn(Optional.of(ta));
        when(teamRepo.findById(8L)).thenReturn(Optional.of(tb));
        AlertEvent ev = new AlertEvent();
        ev.setId(950L);
        ev.setDomain(HOST);
        ev.setAlertType(DNS_CHANGED);
        ev.setAlertLevel("HIGH");
        ev.setTeamId(7L);
        ev.setContextJson("{\"monitor_id\":1,\"team_id\":7,\"standalone\":true,\"record_type\":\"A\"}");
        ev.setAcknowledged(false);
        ev.setResolved(false);
        ev.setCreatedAt("2026-09-28T22:22:00");
        ev.setLastReAlertAt(RECENT);   // 1 sa önce — yeniden uyarı vakti GELMEDİ (kayan zaman, sabit tarih değil)
        when(alertEventRepo.findOpenAlerts(HOST, DNS_CHANGED)).thenReturn(List.of(ev));
        when(alertEventRepo.findOpenAlert(HOST, DNS_CHANGED)).thenReturn(Optional.of(ev));
        return ev;
    }

    private static Map<String, Object> changeCtx(long monitorId, Long teamId, String type) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("monitor_id", monitorId);
        if (teamId != null) { c.put("team_id", teamId); c.put("standalone", true); }
        c.put("record_type", type);
        c.put("old_values", List.of("ns1.example.net"));
        c.put("new_values", List.of("ns9.example.org"));
        return c;
    }

    private List<String> mailRecipients() {
        List<String> to = new java.util.ArrayList<>();
        for (var inv : mockingDetails(emailService).getInvocations()) {
            if (inv.getMethod().getName().equals("sendAlert") && inv.getArgument(0) instanceof String[] arr)
                to.addAll(List.of(arr));
        }
        return to;
    }

    @Test
    @DisplayName("O-c2: A takımının İKİNCİ DNS izlemesi (#4) A'nın açık DNS_CHANGED olayı varken NS değişikliği görür → A'ya bildirilir, olaya dokunulmaz")
    void sameTeamOtherMonitorChange_isNotified() {
        AlertEvent ev = openDnsChanged();
        service.processConfirmedOutage(HOST, DNS_CHANGED, "HIGH", changeCtx(4L, 7L, "NS"));
        assertThat(mailRecipients()).containsExactly("a@example.com");
        assertThat(ev.getLastReAlertAt()).as("açık olayın kadansı değişmez").isEqualTo(RECENT);
    }

    @Test
    @DisplayName("O-c2: B takımının izlemesi (#2) aynı alan adında değişiklik görür → B'ye gider, A'ya GİTMEZ")
    void otherTeamChange_goesToItsOwnTeamOnly() {
        openDnsChanged();
        service.processConfirmedOutage(HOST, DNS_CHANGED, "HIGH", changeCtx(2L, 8L, "MX"));
        assertThat(mailRecipients()).containsExactly("b@example.com");
    }

    @Test
    @DisplayName("O-c2 korunur: olayı AÇAN izlemenin kendi bağlamı (#1) olağan yola girer — ek olaysız bildirim yok")
    void openerChange_usesEventPath() {
        openDnsChanged();
        service.processConfirmedOutage(HOST, DNS_CHANGED, "HIGH", changeCtx(1L, 7L, "A"));
        assertThat(mailRecipients()).as("yeniden uyarı vakti gelmedi").isEmpty();
    }

    // ── D-b1: silme / duraklatma yalnız bu izlemenin olayını kapatır ─────────────────────────────

    private void silentClose(Map<String, Object> owner) {
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(HOST), anyCollection())).thenReturn(List.of(open));
        service.resolveOpenAlertsSilently(HOST, java.util.Set.of(PORT), "Sistem (izleme duraklatıldı)", owner);
    }

    @Test
    @DisplayName("D-b1: B takımının izlemesini (#2) DURAKLATMAK/SİLMEK A'nın açık olayını kapatmaz")
    void foreignMonitorPause_doesNotCloseOwnersEvent() {
        silentClose(ctx(2L, 8L));
        assertThat(open.getResolved()).isFalse();
        verify(alertEventRepo, never()).save(any());
    }

    @Test
    @DisplayName("D-b1: aynı takımın KARDEŞ izlemesi (#4) de olayı AÇAN izleme değilse kapatmaz (kardeş kendi kurtarmasıyla kapatır)")
    void siblingMonitorPause_doesNotCloseOpenersEvent() {
        silentClose(ctx(4L, 7L));
        assertThat(open.getResolved()).isFalse();
    }

    @Test
    @DisplayName("D-b1: olayı AÇAN izleme (#1) duraklatılınca/silinince olay sessizce kapanır")
    void openerPause_closesItsEvent() {
        silentClose(ctx(1L, 7L));
        assertThat(open.getResolved()).isTrue();
        assertThat(open.getResolvedSilently()).isTrue();
    }

    @Test
    @DisplayName("D-b1: açanı bilinmeyen ESKİ olay — sahiplik anahtarı aynıysa kapanır, başka takımınsa kapanmaz")
    void legacyEventWithoutOpener_fallsBackToOwnerKey() {
        open.setContextJson("{\"team_id\":7,\"standalone\":true,\"port\":443}");
        silentClose(ctx(2L, 8L));
        assertThat(open.getResolved()).as("başka takım").isFalse();
        silentClose(ctx(4L, 7L));
        assertThat(open.getResolved()).as("aynı takım").isTrue();
    }

    @Test
    @DisplayName("Sahiplik bağlamı verilmeyen eski çağrı (öksüz temizliği, bakım) tüm açık olayları kapatır — davranış korunur")
    void ownerlessCall_closesAll() {
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq(HOST), anyCollection())).thenReturn(List.of(open));
        service.resolveOpenAlertsSilently(HOST, java.util.Set.of(PORT), "Sistem (öksüz alarm)");
        assertThat(open.getResolved()).isTrue();
    }
}
