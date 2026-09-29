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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * K1'in sertifika eşleniği (prod 2026-09-29): sertifika sweep'i ağ kesintisi şüphesinde (ya da sertifika alarmları
 * kapalıyken) processResults'u tümüyle atlıyordu — DÜZELEN alan adının açık sertifika alarmı da kapanmıyordu.
 * {@link EscalationService#resolveVerifiedStaleCertAlerts} yalnız kapanış kuralını uygular: doğrulanmış sonuç artık
 * üretmiyorsa açık sertifika türü kapanır; status=error hiçbir şeyi doğrulamadığından dokunulmaz; açılış YOK.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertAlertOutageReconcileTest {

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

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo,
                inventoryRepo, emailService, weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo,
                latestCheckRepo, teamRepo, smtpSettings, maintenanceService, stormService, userPushService,
                domainMonitorRepo, domainCheckRepo, dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        lenient().when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        ReflectionTestUtils.setField(service, "self", service);
        when(alertEventRepo.markResolvedIfOpen(any(), any(), any())).thenReturn(1);
        when(alertEventRepo.save(any(AlertEvent.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static AlertEvent open(long id, String domain, String type) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setResolved(false);
        return e;
    }

    private static Map<String, Object> result(String domain, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("status", status);
        m.put("warning", false);
        return m;
    }

    @Test
    @DisplayName("Doğrulanmış sağlıklı sonuç → açık CHAIN_BROKEN kapanır; hata veren alan adına DOKUNULMAZ; alarm açılmaz")
    void verifiedHealthyResult_closesStaleCertAlarm_errorResultUntouched() {
        AlertEvent fixed = open(1L, "fixed.example.com", "CHAIN_BROKEN");
        AlertEvent unverified = open(2L, "down.example.com", "REVOKED");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(fixed, unverified));
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq("fixed.example.com"), anyCollection()))
                .thenReturn(List.of(fixed));

        int n = service.resolveVerifiedStaleCertAlerts(List.of(
                result("fixed.example.com", "valid"),
                result("down.example.com", "error")));

        assertThat(n).isEqualTo(1);
        assertThat(fixed.getResolved()).isTrue();
        verify(alertEventRepo, never()).findByDomainAndAlertTypeInAndResolvedFalse(eq("down.example.com"), anyCollection());
        assertThat(unverified.getResolved()).isFalse();
        verify(stormService, never()).evaluate(any(), any());   // açılış hattına HİÇ girilmez
    }

    @Test
    @DisplayName("D-1: sertifika alarm bildirimleri KAPALIYKEN kapanış SESSİZ (çözüm e-postası yolu yok, resolvedBy açıklamalı)")
    void alertsDisabled_closureIsSilent() {
        when(appSettings.getBoolean(eq("site.monitor.expiry.alert-enabled"), anyBoolean())).thenReturn(false);
        AlertEvent fixed = open(5L, "quiet.example.com", "CHAIN_BROKEN");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(fixed));
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq("quiet.example.com"), anyCollection()))
                .thenReturn(List.of(fixed));

        service.resolveVerifiedStaleCertAlerts(List.of(result("quiet.example.com", "valid")));

        assertThat(fixed.getResolved()).isTrue();
        assertThat(fixed.getResolvedBy()).contains("bildirimleri kapalı");
        verify(alertEventRepo, never()).markResolvedIfOpen(any(), any(), any());   // normal çözüm hattına girilmedi
        verify(notificationLogRepo, never()).save(any());
    }

    @Test
    @DisplayName("D-7: HOSTNAME_MISMATCH bayrağı DURUYOR ama alarm türü ayardan kapatıldı → açık alarm SESSİZCE kapanır ('sorun giderildi' değil)")
    void settingDisabledSecurityType_closesSilently() {
        when(appSettings.getBoolean(eq(EscalationService.SETTING_ALERT_HOSTNAME_MISMATCH), anyBoolean())).thenReturn(false);
        AlertEvent hm = open(7L, "mismatch.example.com", EscalationService.TYPE_HOSTNAME_MISMATCH);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(hm));
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq("mismatch.example.com"), anyCollection()))
                .thenAnswer(i -> ((java.util.Collection<?>) i.getArgument(1)).contains(EscalationService.TYPE_HOSTNAME_MISMATCH)
                        ? List.of(hm) : List.of());
        Map<String, Object> r = result("mismatch.example.com", "valid");
        r.put("san", List.of("baska.example.com"));   // sertifika bu alan adını HÂLÂ kapsamıyor

        service.resolveVerifiedStaleCertAlerts(List.of(r));

        assertThat(hm.getResolved()).isTrue();
        assertThat(hm.getResolvedBy()).contains("ayardan kapatıldı");
        verify(alertEventRepo, never()).markResolvedIfOpen(any(), any(), any());   // normal "ÇÖZÜLDÜ" hattı yok
    }

    @Test
    @DisplayName("D-7 korunur: ayar AÇIK ve sertifika artık alan adını KAPSIYOR → normal çözüm hattı")
    void fixedSecurityIssue_closesNormally() {
        AlertEvent hm = open(8L, "fixed-san.example.com", EscalationService.TYPE_HOSTNAME_MISMATCH);
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(hm));
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq("fixed-san.example.com"), anyCollection()))
                .thenAnswer(i -> ((java.util.Collection<?>) i.getArgument(1)).contains(EscalationService.TYPE_HOSTNAME_MISMATCH)
                        ? List.of(hm) : List.of());
        Map<String, Object> r = result("fixed-san.example.com", "valid");
        r.put("san", List.of("fixed-san.example.com"));

        service.resolveVerifiedStaleCertAlerts(List.of(r));

        assertThat(hm.getResolved()).isTrue();
        verify(alertEventRepo).markResolvedIfOpen(eq(8L), any(), eq("system"));
    }

    @Test
    @DisplayName("Sonuç HÂLÂ sorunu gösteriyorsa (REVOKED) açık alarm kapanmaz")
    void resultStillShowsProblem_alarmStaysOpen() {
        AlertEvent revoked = open(3L, "revoked.example.com", "REVOKED");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(revoked));
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(eq("revoked.example.com"), anyCollection()))
                .thenAnswer(i -> ((java.util.Collection<?>) i.getArgument(1)).contains("REVOKED") ? List.of(revoked) : List.of());
        Map<String, Object> r = result("revoked.example.com", "valid");
        r.put("revocation_status", "REVOKED");

        service.resolveVerifiedStaleCertAlerts(List.of(r));

        assertThat(revoked.getResolved()).isFalse();
    }
}
