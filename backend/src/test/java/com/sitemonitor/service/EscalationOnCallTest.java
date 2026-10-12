package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.oncall.OnCallService;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Nöbetçi bildirimi (2026-10-11, kullanıcı kararı: "takım seçerse nöbetçiye de"): takım "nöbetçiye de bildir" seçtiyse
 * YÜKSEK / KRİTİK alarm bildirimi (açılış, elle gönderim önizlemesi + sayımı, çözüm) o anki nöbetçiye de gider (e-posta +
 * push); mevcut alıcılar değişmez. Seçenek kapalı / çizelge yok / servis yok → alıcılar bugünküyle aynı.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationOnCallTest {

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
    @Mock OnCallService onCall;

    static final long TEAM_A = 1L;
    static final String A_MAIL = "takim-a@example.com", ONCALL_MAIL = "nobetci@example.com";
    static final String DOMAIN = "a.example.com";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    EscalationService service;

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "onCallService", onCall);
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
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("SENT");
        Team team = new Team();
        team.setId(TEAM_A); team.setName("Takım A"); team.setEmail(A_MAIL);
        when(teamRepo.findById(TEAM_A)).thenReturn(Optional.of(team));
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(any())).thenReturn(List.of());
        when(contactRepo.findByTeamIdAndMinAlertLevelInAndActiveTrue(any(), anyList())).thenReturn(List.of());
        when(contactRepo.findByTeamIdAndMinAlertLevelAndActiveTrue(any(), anyString())).thenReturn(List.of());
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(DOMAIN); inv.setTeamId(TEAM_A);
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of(inv));
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.of(inv));
        when(onCall.notifyTargets(anyCollection())).thenReturn(Map.of(TEAM_A,
                new OnCallService.OnCallPerson(TEAM_A, 55L, "N55555", "Nöbetçi Kişi", ONCALL_MAIL, true, false, "2026-10-12T06:00:00")));
    }

    private Map<String, Object> hijacked() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", DOMAIN); r.put("status", "valid"); r.put("warning", false); r.put("days_remaining", 300);
        r.put("revocation_status", "VALID"); r.put("chain_status", "VALID"); r.put("deployment_status", "OK");
        r.put("san", List.of("baska.example.com"));
        r.put("trust_status", "TRUSTED");
        return r;
    }

    private Map<String, Object> expiring(int days) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", DOMAIN); r.put("status", "warning"); r.put("warning", true); r.put("days_remaining", days);
        r.put("revocation_status", "VALID"); r.put("chain_status", "VALID"); r.put("deployment_status", "OK");
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

    private AlertEvent openEvent(String level) {
        AlertEvent e = new AlertEvent();
        e.setId(7L); e.setDomain(DOMAIN); e.setAlertType(EscalationService.TYPE_HOSTNAME_MISMATCH);
        e.setAlertLevel(level); e.setTeamId(TEAM_A); e.setResolved(false); e.setAcknowledged(false);
        e.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        e.setLastReAlertAt(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        return e;
    }

    @Test
    @DisplayName("KRİTİK açılış: takım adresi + nöbetçi; nöbetçiye push (takım push'u da aynen); nöbetçi adresi alarma yazılır")
    void criticalAddsOnCall() {
        service.processResults(List.of(hijacked()));
        assertThat(sentTo()).containsExactly(A_MAIL, ONCALL_MAIL);
        verify(userPushService).enqueueAlert(any(), eq("INITIAL"), eq(TEAM_A), any(), any());
        verify(userPushService).enqueueOnCall(any(), eq("INITIAL"), eq(TEAM_A), eq(55L), any());
        // 2026-10-12: çözüm e-postası açılışı NÖBETÇİ olarak alanlara gider — adres alarm satırına hedefli UPDATE ile yazılır
        verify(alertEventRepo).setOncallEmails(eq(100L), eq(ONCALL_MAIL));
    }

    @Test
    @DisplayName("UYARI seviyesi: nöbetçi eklenmez, servis sorulmaz bile")
    void warningDoesNotAddOnCall() {
        service.processResults(List.of(expiring(25)));
        assertThat(sentTo()).containsExactly(A_MAIL);
        verify(onCall, never()).notifyTargets(anyCollection());
        verify(userPushService, never()).enqueueOnCall(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("seçenek kapalı (hedef yok) ya da servis yok → alıcılar bugünküyle aynı")
    void offOrMissingIsUnchanged() {
        when(onCall.notifyTargets(anyCollection())).thenReturn(Map.of());
        service.processResults(List.of(hijacked()));
        assertThat(sentTo()).containsExactly(A_MAIL);
        verify(userPushService, never()).enqueueOnCall(any(), any(), any(), any(), any());

        reset(emailService);
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("SENT");
        ReflectionTestUtils.setField(service, "onCallService", null);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        service.processResults(List.of(hijacked()));
        assertThat(sentTo()).containsExactly(A_MAIL);
    }

    @Test
    @DisplayName("nöbetçi takım adresiyle aynıysa tek kez; servis hata verirse bildirim nöbetçisiz sürer")
    void dedupeAndFailure() {
        when(onCall.notifyTargets(anyCollection())).thenReturn(Map.of(TEAM_A,
                new OnCallService.OnCallPerson(TEAM_A, 55L, "N55555", "Nöbetçi", "TAKIM-A@example.com", true, false, null)));
        service.processResults(List.of(hijacked()));
        assertThat(sentTo()).containsExactly(A_MAIL);

        reset(emailService);
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("SENT");
        when(onCall.notifyTargets(anyCollection())).thenThrow(new RuntimeException("db yok"));
        service.processResults(List.of(hijacked()));
        assertThat(sentTo()).containsExactly(A_MAIL);
    }

    @Test
    @DisplayName("elle gönderim önizlemesi nöbetçiyi ONCALL olarak gösterir; çözüm e-postası açılışı nöbetçi olarak ALANA gider")
    void previewAndResolution() {
        AlertEvent open = openEvent("CRITICAL");
        open.setOncallEmails("eski-nobetci@example.com");   // açılışı alan (devirden önceki) nöbetçi
        when(alertEventRepo.findById(open.getId())).thenReturn(Optional.of(open));
        List<EscalationService.ReNotifyRecipient> preview = service.previewReNotify(open.getId());
        assertThat(preview).extracting(EscalationService.ReNotifyRecipient::email).containsExactly(A_MAIL, ONCALL_MAIL);
        assertThat(preview.get(1).kind()).isEqualTo("ONCALL");
        assertThat(service.reNotifyOnCall(open.getId())).extracting(OnCallService.OnCallPerson::userId).containsExactly(55L);

        service.resolve(open.getId(), "Kişi A");
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendResolutionAlert(to.capture(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        // Şu anki nöbetçi (açılışı görmedi) çözümü ALMAZ; açılışı alan eski nöbetçi kapanışı öğrenir (2026-10-12)
        assertThat(to.getValue()).containsExactly(A_MAIL, "eski-nobetci@example.com");
    }

    @Test
    @DisplayName("2026-10-12: açılışı nöbetçi olarak kimse almadıysa (seçenek sonradan açıldı) çözüm yalnız takıma")
    void resolutionWithoutOnCallOpening() {
        AlertEvent open = openEvent("CRITICAL");
        when(alertEventRepo.findById(open.getId())).thenReturn(Optional.of(open));
        service.resolve(open.getId(), "Kişi A");
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendResolutionAlert(to.capture(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(to.getValue()).containsExactly(A_MAIL);
    }

    @Test
    @DisplayName("nöbetçi adresleri birleşir: tekil (büyük/küçük harf), virgüllü, en çok 20")
    void oncallMailedFor() {
        AlertEvent e = new AlertEvent();
        assertThat(EscalationService.oncallMailedFor(e)).isEmpty();
        e.setOncallEmails(" a@example.com ,b@example.com,, ");
        assertThat(EscalationService.oncallMailedFor(e)).containsExactly("a@example.com", "b@example.com");
        when(alertEventRepo.findOncallEmails(9L)).thenReturn("a@example.com");
        ReflectionTestUtils.invokeMethod(service, "rememberOncallMailed", 9L, List.of("A@example.com", "c@example.com"));
        verify(alertEventRepo).setOncallEmails(9L, "a@example.com,c@example.com");
    }
}
