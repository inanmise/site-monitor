package com.sitemonitor.service;

import com.sitemonitor.model.*;
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
 * H paketi (2026-11-26):
 * <ul>
 *   <li>izleme 1 — bakım penceresi sertifika KUSUR alarmlarını (ve hata sonucunun "erişilemedi" alarmını) açmaz / tırmandırmaz /
 *       hatırlatmaz; karar notification_logs SKIPPED satırı bırakır (alan adı × tür başına sınırlı); süre (EXPIRY, hatasız
 *       sonuç) bastırılmaz; bakım okuması düşerse bugünkü davranış.</li>
 *   <li>sertifika B1 — EXPIRY eşiği max(global bayrak, tier uyarı günü): tier uyarısı 60 olan T1 sertifikası 40. günde HIGH
 *       ile açılır; kapanış uzlaştırması aynı kuralla tier penceresindeki EXPIRY'yi kapatmaz.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationCertMaintenanceTierTest {

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

    private static final Long OWNER = 42L;
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private EscalationService service;
    private final Map<String, Integer> tiers = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo,
                inventoryRepo, emailService, weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo,
                latestCheckRepo, teamRepo, smtpSettings, maintenanceService, stormService, userPushService,
                domainMonitorRepo, domainCheckRepo, dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(alertEventRepo.markResolvedIfOpen(any(), any(), any())).thenReturn(1);
        when(smtpSettings.getOrDefaults()).thenReturn(new SmtpSettings());
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.of(threshold(null, 30, 15, 7)));
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        when(alertEventRepo.findOpenAlert(anyString(), anyString())).thenReturn(Optional.empty());
        when(alertEventRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(inventoryRepo.findByDomainIn(anyCollection())).thenAnswer(inv -> {
            List<CertificateInventory> out = new ArrayList<>();
            for (Object d : (Collection<?>) inv.getArgument(0)) {
                CertificateInventory i = new CertificateInventory();
                i.setDomain(String.valueOf(d));
                i.setTeamId(OWNER);
                i.setTier(tiers.get(String.valueOf(d)));
                out.add(i);
            }
            return out;
        });
        when(contactRepo.findByTeamIdAndMinAlertLevelAndActiveTrue(any(), anyString())).thenReturn(List.of());
    }

    private static AlertThreshold threshold(Integer tier, int warn, int high, int crit) {
        AlertThreshold t = new AlertThreshold();
        t.setId(tier == null ? 1L : 10L + tier);
        t.setTier(tier);
        t.setWarningDays(warn);
        t.setHighDays(high);
        t.setCriticalDays(crit);
        t.setReAlertIntervalHours(24);
        t.setActive(true);
        return t;
    }

    private static Map<String, Object> result(String domain, String status, boolean warning, Integer days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("status", status);
        m.put("warning", warning);
        if (days != null) m.put("days_remaining", days);
        m.put("revocation_status", "VALID");
        m.put("chain_status", "VALID");
        m.put("deployment_status", "OK");
        return m;
    }

    private static Map<String, Object> chainBroken(String domain) {
        Map<String, Object> m = result(domain, "valid", false, 200);
        m.put("chain_status", "BROKEN");
        return m;
    }

    /** Gönderim yok: e-posta servisine yalnız günlük satırının gönderen adresi okuması (saveLog) dokunabilir. */
    private void noMailSent() {
        verify(emailService, atLeast(0)).getEmailFrom();
        verifyNoMoreInteractions(emailService);
    }

    private List<NotificationLog> savedLogs() {
        ArgumentCaptor<NotificationLog> cap = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo, atLeast(0)).save(cap.capture());
        return cap.getAllValues();
    }

    private static AlertEvent open(long id, String domain, String type, String level) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setResolved(false);
        e.setAcknowledged(false);
        e.setTeamId(OWNER);
        e.setCreatedAt(ISO.format(Instant.now().minus(3, ChronoUnit.DAYS)));
        e.setLastReAlertAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        return e;
    }

    // ── izleme 1: bakım penceresi ─────────────────────────────────────────────

    @Test
    @DisplayName("bakımda CHAIN_BROKEN açılmaz; SKIPPED 'bakım penceresi' izi BİR kez (ikinci tur yeni satır yazmaz)")
    void defectNotOpenedDuringMaintenance_tracedOnce() {
        when(maintenanceService.isUnderMaintenance("m.example.com")).thenReturn(true);
        service.processResults(List.of(chainBroken("m.example.com")));
        service.processResults(List.of(chainBroken("m.example.com")));
        verify(alertEventRepo, never()).save(any());
        noMailSent();
        List<NotificationLog> logs = savedLogs().stream()
                .filter(n -> EscalationService.STATUS_CERT_MAINTENANCE_OPEN.equals(n.getEmailStatus())).toList();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getAlertEventId()).isEqualTo(0L);
        assertThat(logs.get(0).getWebhookStatus()).isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("bakımda hata sonucunun 'erişilemedi' (EXPIRY türü) alarmı da açılmaz")
    void unreachableNotOpenedDuringMaintenance() {
        when(maintenanceService.isUnderMaintenance("e.example.com")).thenReturn(true);
        Map<String, Object> err = result("e.example.com", "error", false, null);
        err.put("error_class", "NETWORK");
        service.processResults(List.of(err));
        verify(alertEventRepo, never()).save(any());
        noMailSent();
    }

    @Test
    @DisplayName("bakımda SÜRE alarmı (hatasız EXPIRY) BASTIRILMAZ — bitişe yaklaşan sertifika gizlenmez")
    void expiryNotMutedDuringMaintenance() {
        when(maintenanceService.isUnderMaintenance("x.example.com")).thenReturn(true);
        service.processResults(List.of(result("x.example.com", "warning", true, 5)));
        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getAlertType()).isEqualTo("EXPIRY");
        assertThat(cap.getAllValues().get(0).getAlertLevel()).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("bakımda açık kusur alarmı TIRMANMAZ / hatırlatılmaz; iz açık olayın kimliğiyle yazılır")
    void openDefectNotEscalatedDuringMaintenance() {
        AlertEvent ev = open(77L, "o.example.com", "CHAIN_BROKEN", "WARNING");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(ev));
        when(maintenanceService.isUnderMaintenance("o.example.com")).thenReturn(true);
        service.processResults(List.of(chainBroken("o.example.com")));
        noMailSent();
        assertThat(ev.getAlertLevel()).as("terfi yazılmadı").isEqualTo("WARNING");
        assertThat(savedLogs()).anySatisfy(n -> {
            assertThat(n.getEmailStatus()).isEqualTo(EscalationService.STATUS_CERT_MAINTENANCE_OPEN_ALERT);
            assertThat(n.getAlertEventId()).isEqualTo(77L);
        });
    }

    @Test
    @DisplayName("bakımda açık kusur alarmının bildirimi SIRASI GELMEMİŞSE iz yazılmaz (aynı seviye, hatırlatma 1 sa önce); "
            + "aralık dolunca yazılır")
    void openDefectTracedOnlyWhenNotificationDue() {
        AlertEvent ev = open(79L, "q.example.com", "CHAIN_BROKEN", "CRITICAL");
        ev.setLastReAlertAt(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(ev));
        when(maintenanceService.isUnderMaintenance("q.example.com")).thenReturn(true);
        service.processResults(List.of(chainBroken("q.example.com")));
        noMailSent();
        assertThat(savedLogs()).as("sırası gelmemiş bildirim için 'gönderilmedi' izi yok").noneMatch(n ->
                EscalationService.STATUS_CERT_MAINTENANCE_OPEN_ALERT.equals(n.getEmailStatus()));

        ev.setLastReAlertAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        service.processResults(List.of(chainBroken("q.example.com")));
        noMailSent();
        assertThat(savedLogs()).anySatisfy(n -> {
            assertThat(n.getEmailStatus()).isEqualTo(EscalationService.STATUS_CERT_MAINTENANCE_OPEN_ALERT);
            assertThat(n.getAlertEventId()).isEqualTo(79L);
        });
    }

    /** Kapanış yolunun ({@code resolveOpenAlertsForDomain}) okuduğu tür kümeleri — hangi türler "kapatılmak üzere" arandı. */
    @SuppressWarnings("unchecked")
    private List<Collection<String>> closeLookups() {
        ArgumentCaptor<Collection<String>> cap = ArgumentCaptor.forClass(Collection.class);
        verify(alertEventRepo, atLeast(0)).findByDomainAndAlertTypeInAndResolvedFalse(anyString(), cap.capture());
        return cap.getAllValues();
    }

    private static Map<String, Object> exclusiveDefectInWindow(String domain, String which) {
        Map<String, Object> m = result(domain, "warning", true, 20);
        switch (which) {
            case "CHAIN_BROKEN" -> m.put("chain_status", "BROKEN");
            case "REVOKED" -> m.put("revocation_status", "REVOKED");
            default -> m.put("deployment_status", "INCOMPLETE");
        }
        return m;
    }

    @Test
    @DisplayName("inceleme: bakımda dışlayıcı kusur (CHAIN_BROKEN / REVOKED / MISMATCH) bastırılırken süre penceresindeki açık "
            + "EXPIRY kapatılmaz — yalancı 'çözüldü' postası yok")
    void openExpiryNotStaleClosedWhileExclusiveDefectMuted() {
        for (String which : List.of("CHAIN_BROKEN", "REVOKED", "MISMATCH")) {
            reset(alertEventRepo, notificationLogRepo, emailService);
            when(alertEventRepo.markResolvedIfOpen(any(), any(), any())).thenReturn(1);
            when(alertEventRepo.findOpenAlert(anyString(), anyString())).thenReturn(Optional.empty());
            String d = which.toLowerCase(Locale.ROOT) + ".example.com";
            AlertEvent exp = open(90L, d, "EXPIRY", "WARNING");
            exp.setDaysRemaining(20);
            when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(exp));
            when(maintenanceService.isUnderMaintenance(d)).thenReturn(true);
            service.processResults(List.of(exclusiveDefectInWindow(d, which)));
            assertThat(closeLookups()).as(which).noneMatch(types -> types.contains("EXPIRY"));
            noMailSent();
        }
    }

    @Test
    @DisplayName("inceleme: bakım DIŞINDA dışlayıcı kusurda açık EXPIRY bugünkü gibi kapanır; bakımda pencere DIŞINDAKİ "
            + "(yenilenmiş) sertifikanın EXPIRY'si de kapanır")
    void exclusiveDefectClosesExpiryOutsideMaintenanceOrWindow() {
        AlertEvent exp = open(91L, "n2.example.com", "EXPIRY", "WARNING");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(exp));
        service.processResults(List.of(exclusiveDefectInWindow("n2.example.com", "CHAIN_BROKEN")));
        assertThat(closeLookups()).anyMatch(types -> types.contains("EXPIRY"));

        reset(alertEventRepo);
        when(alertEventRepo.markResolvedIfOpen(any(), any(), any())).thenReturn(1);
        when(alertEventRepo.findOpenAlert(anyString(), anyString())).thenReturn(Optional.empty());
        AlertEvent exp2 = open(92L, "w2.example.com", "EXPIRY", "WARNING");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(exp2));
        when(maintenanceService.isUnderMaintenance("w2.example.com")).thenReturn(true);
        service.processResults(List.of(chainBroken("w2.example.com")));   // 200 gün: süre penceresinde değil
        assertThat(closeLookups()).anyMatch(types -> types.contains("EXPIRY"));
    }

    @Test
    @DisplayName("inceleme: pencereden ÖNCE açılmış 'erişilemedi' EXPIRY alarmı bakımda da hatırlatılır; yalnız YENİ açılış susar")
    void openExpiryKeepsReAlertingDuringMaintenance() {
        AlertEvent ev = open(93L, "r.example.com", "EXPIRY", "WARNING");
        String before = ev.getLastReAlertAt();
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(ev));
        when(alertEventRepo.findById(93L)).thenReturn(Optional.of(ev));
        when(maintenanceService.isUnderMaintenance("r.example.com")).thenReturn(true);
        Map<String, Object> err = result("r.example.com", "error", false, null);
        err.put("error_class", "NETWORK");
        service.processResults(List.of(err));
        assertThat(ev.getLastReAlertAt()).as("hatırlatma gitti (damga ilerledi)").isNotEqualTo(before);
        assertThat(savedLogs()).noneMatch(n -> EscalationService.STATUS_CERT_MAINTENANCE_OPEN_ALERT.equals(n.getEmailStatus()));
        assertThat(EscalationService.openExpiryKeepsNotifying("EXPIRY", ev)).isTrue();
        assertThat(EscalationService.openExpiryKeepsNotifying("EXPIRY", null)).isFalse();
        assertThat(EscalationService.openExpiryKeepsNotifying("CHAIN_BROKEN", ev)).isFalse();
    }

    @Test
    @DisplayName("inceleme: açılış telafisi bakımda açık EXPIRY'yi (son kontrol hata) susturmaz — bakım izi yazılmaz")
    void catchUpKeepsOpenExpiryDuringMaintenance() {
        AlertEvent ev = open(94L, "cx.example.com", "EXPIRY", "WARNING");
        when(alertEventRepo.findByResolvedFalseAndAcknowledgedFalseOrderByCreatedAtDesc()).thenReturn(List.of(ev));
        when(alertEventRepo.findById(94L)).thenReturn(Optional.of(ev));
        LatestCheck lc = new LatestCheck();
        lc.setDomain("cx.example.com");
        lc.setStatus("error");
        when(latestCheckRepo.findByDomainIn(anyCollection())).thenReturn(List.of(lc));
        when(maintenanceService.isUnderMaintenance("cx.example.com")).thenReturn(true);
        service.catchUpMissedDailyAlerts();
        assertThat(savedLogs()).noneMatch(n -> EscalationService.STATUS_CERT_MAINTENANCE_OPEN_ALERT.equals(n.getEmailStatus()));
    }

    @Test
    @DisplayName("bakım dışında kusur alarmı bugünkü gibi açılır; bakım okuması düşerse de açılır (bilinmeyen gizlemez)")
    void outsideMaintenanceOrUnreadableOpensAsToday() {
        service.processResults(List.of(chainBroken("n.example.com")));
        // Gerçek MaintenanceService okuma hatasını kendi yutar (freshen); savunma katmanı yine de sınanır: kapanış yolunun
        // (bugünkü kod) okuması geçer, bastırma kontrolünün okuması düşer → alarm bugünkü gibi açılır.
        when(maintenanceService.isUnderMaintenance("u.example.com")).thenReturn(false).thenThrow(new RuntimeException("db"));
        service.processResults(List.of(chainBroken("u.example.com")));
        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeast(2)).save(cap.capture());
        assertThat(cap.getAllValues()).extracting(AlertEvent::getDomain).contains("n.example.com", "u.example.com");
    }

    @Test
    @DisplayName("açılış telafisi (catch-up) bakımdaki alan adının kusur alarmını hatırlatmaz")
    void catchUpSkipsDefectDuringMaintenance() {
        AlertEvent ev = open(88L, "c.example.com", "CHAIN_BROKEN", "CRITICAL");
        when(alertEventRepo.findByResolvedFalseAndAcknowledgedFalseOrderByCreatedAtDesc()).thenReturn(List.of(ev));
        when(alertEventRepo.findById(88L)).thenReturn(Optional.of(ev));
        when(latestCheckRepo.findByDomainIn(anyCollection())).thenReturn(List.of());
        when(maintenanceService.isUnderMaintenance("c.example.com")).thenReturn(true);
        service.catchUpMissedDailyAlerts();
        noMailSent();
        assertThat(savedLogs()).anySatisfy(n ->
                assertThat(n.getEmailStatus()).isEqualTo(EscalationService.STATUS_CERT_MAINTENANCE_OPEN_ALERT));
    }

    @Test
    @DisplayName("certMaintenanceMutes doğruluk tablosu")
    void mutesTruthTable() {
        Map<String, Object> ok = Map.of("status", "valid");
        Map<String, Object> err = Map.of("status", "error");
        assertThat(EscalationService.certMaintenanceMutes("CHAIN_BROKEN", ok)).isTrue();
        assertThat(EscalationService.certMaintenanceMutes("REVOKED", ok)).isTrue();
        assertThat(EscalationService.certMaintenanceMutes("MISMATCH", ok)).isTrue();
        assertThat(EscalationService.certMaintenanceMutes(EscalationService.TYPE_HOSTNAME_MISMATCH, ok)).isTrue();
        assertThat(EscalationService.certMaintenanceMutes(EscalationService.TYPE_UNTRUSTED_CA, ok)).isTrue();
        assertThat(EscalationService.certMaintenanceMutes("EXPIRY", ok)).isFalse();
        assertThat(EscalationService.certMaintenanceMutes("EXPIRY", err)).isTrue();
        assertThat(EscalationService.certMaintenanceMutes("EXPIRY", null)).isFalse();
        assertThat(EscalationService.certMaintenanceMutes(EscalationService.TYPE_PING_DOWN, ok)).isFalse();
        assertThat(EscalationService.certMaintenanceMutes(null, ok)).isFalse();
    }

    // ── sertifika B1: tier eşiği ──────────────────────────────────────────────

    @Test
    @DisplayName("B1: T1 uyarı 60 / yüksek 45 — 40 gün kalan, global bayrak KAPALI sertifika HIGH ile açılır; tier'sız kayıt açılmaz")
    void tierWarningAboveGlobalOpensExpiry() {
        when(thresholdRepo.findByActiveTrueAndTierIsNotNullOrderByIdAsc()).thenReturn(List.of(threshold(1, 60, 45, 7)));
        tiers.put("t1.example.com", 1);
        service.processResults(List.of(result("t1.example.com", "valid", false, 40),
                result("plain.example.com", "valid", false, 40)));
        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues()).extracting(AlertEvent::getDomain).containsOnly("t1.example.com");
        assertThat(cap.getAllValues().get(0).getAlertType()).isEqualTo("EXPIRY");
        assertThat(cap.getAllValues().get(0).getAlertLevel()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("B1: tier eşiği global eşiğin ALTINDAysa (uyarı 20) 25. gündeki EXPIRY bugünkü gibi seviye yok → açılmaz")
    void tierWarningBelowGlobalUnchanged() {
        when(thresholdRepo.findByActiveTrueAndTierIsNotNullOrderByIdAsc()).thenReturn(List.of(threshold(2, 20, 10, 5)));
        tiers.put("t2.example.com", 2);
        service.processResults(List.of(result("t2.example.com", "warning", true, 25)));
        verify(alertEventRepo, never()).save(any());
    }

    @Test
    @DisplayName("B1: kapanış uzlaştırması tier penceresindeki açık EXPIRY'yi KAPATMAZ; pencere dışında kapatır")
    void staleCloseRespectsTierWindow() {
        when(thresholdRepo.findByActiveTrueAndTierIsNotNullOrderByIdAsc()).thenReturn(List.of(threshold(1, 60, 45, 7)));
        tiers.put("t1.example.com", 1);
        AlertEvent ev = open(5L, "t1.example.com", "EXPIRY", "HIGH");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(ev));
        service.resolveVerifiedStaleCertAlerts(List.of(result("t1.example.com", "valid", false, 40)));
        verify(alertEventRepo, never()).findByDomainAndAlertTypeInAndResolvedFalse(eq("t1.example.com"),
                argThat((Collection<String> c) -> c.contains("EXPIRY")));

        service.resolveVerifiedStaleCertAlerts(List.of(result("t1.example.com", "valid", false, 90)));
        verify(alertEventRepo, atLeastOnce()).findByDomainAndAlertTypeInAndResolvedFalse(eq("t1.example.com"),
                argThat((Collection<String> c) -> c.contains("EXPIRY")));
    }

    @Test
    @DisplayName("B1: kapanışta tier okunamazsa EXPIRY kapatılmaz (bilinmeyen karar vermez)")
    void staleCloseTierUnreadableKeepsExpiry() {
        AlertEvent ev = open(6L, "t1.example.com", "EXPIRY", "HIGH");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(ev));
        when(inventoryRepo.findByDomainIn(anyCollection())).thenThrow(new RuntimeException("db"));
        service.resolveVerifiedStaleCertAlerts(List.of(result("t1.example.com", "valid", false, 90)));
        verify(alertEventRepo, never()).findByDomainAndAlertTypeInAndResolvedFalse(eq("t1.example.com"),
                argThat((Collection<String> c) -> c.contains("EXPIRY")));
    }
}
