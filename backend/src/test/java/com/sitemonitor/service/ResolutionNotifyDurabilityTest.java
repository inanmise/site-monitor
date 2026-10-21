package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.PageCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kalıcı çözüm bildirimi işareti (2026-10-21, çok pod R1 / MP-07). Alarm kapanırken {@code alert_events.resolution_notify}
 * = PENDING (kapanış kapısıyla AYNI UPDATE); eşzamansız iş sahiplenip gönderir ve DONE yazar. İş düşerse (SIGTERM — burada
 * {@code self} sahte, iş hiç koşmaz) {@code ResolutionNotifySweeper} 2 dk sonra BİR kez gönderir; iki pod'un süpürücüsü
 * yarışsa da tek posta. Yarıda kalmış gönderim UNKNOWN (yeniden gönderilmez), 24 saati geçen EXPIRED — ikisi de günlükte.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResolutionNotifyDurabilityTest {

    static final Long OWNER = 42L;
    static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

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

    MultiPodTestSupport mp;
    H2AlertEventsFixture store;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        store = new H2AlertEventsFixture(mp.jdbc());
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        SmtpSettings smtp = new SmtpSettings();
        smtp.setInterDomainDelayMs(0);
        when(smtpSettings.getOrDefaults()).thenReturn(smtp);
        when(inventoryRepo.findByDomainIn(anyCollection())).thenAnswer(inv -> owned(inv.getArgument(0)));
        when(inventoryRepo.findByDomain(anyString())).thenAnswer(inv -> {
            String d = inv.getArgument(0);
            return Optional.of(owned(List.of(d)).get(0));
        });
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    static List<CertificateInventory> owned(Collection<?> domains) {
        List<CertificateInventory> out = new ArrayList<>();
        for (Object d : domains) {
            CertificateInventory i = new CertificateInventory();
            i.setDomain(String.valueOf(d));
            i.setTeamId(OWNER);
            out.add(i);
        }
        return out;
    }

    /** {@code asyncRuns=false}: eşzamansız çözüm işi HİÇ koşmaz (pod kapanırken düşmüş gibi). */
    EscalationService pod(int n, boolean asyncRuns) {
        EscalationService s = new EscalationService(store.repo(), thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(s, "self", asyncRuns ? s : mock(EscalationService.class));
        s.setJdbcTemplate(mp.jdbc());
        s.setLockOwner(MultiPodTestSupport.owner(n));
        return s;
    }

    static Map<String, Object> healthy(String domain) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("status", "valid");
        m.put("warning", false);
        m.put("days_remaining", 200);
        m.put("revocation_status", "VALID");
        m.put("chain_status", "VALID");
        m.put("deployment_status", "OK");
        return m;
    }

    AlertEvent openAlarm(String domain) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType("EXPIRY");
        e.setAlertLevel("WARNING");
        e.setMessage("eski");
        e.setTeamId(OWNER);
        e.setAcknowledged(false);
        e.setResolved(false);
        Instant now = Instant.now();
        e.setCreatedAt(ISO.format(now.minus(2, ChronoUnit.DAYS)));
        e.setLastReAlertAt(ISO.format(now.minus(2, ChronoUnit.HOURS)));
        return store.save(e);
    }

    long resolveSends() {
        return org.mockito.Mockito.mockingDetails(userPushService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("enqueueResolve")).count();
    }

    String marker(Long id) {
        return store.byId(id).orElseThrow().getResolutionNotify();
    }

    @Test
    @DisplayName("normal yol: kapanış PENDING yazar, eşzamansız iş sahiplenip gönderir ve DONE yazar; süpürücü sonra bir şey göndermez")
    void normalPath_sentOnceAndDone() {
        AlertEvent e = openAlarm("ok.example.com");
        EscalationService a = pod(1, true);
        a.processResults(List.of(healthy("ok.example.com")));
        assertThat(store.byId(e.getId()).orElseThrow().getResolved()).isTrue();
        assertThat(marker(e.getId())).isEqualTo(EscalationService.RESOLUTION_NOTIFY_DONE);
        assertThat(resolveSends()).isEqualTo(1);

        assertThat(a.deliverPendingResolutions(Instant.now().plus(3, ChronoUnit.MINUTES))).isZero();
        assertThat(resolveSends()).isEqualTo(1);
    }

    @Test
    @DisplayName("SIGTERM benzetimi: eşzamansız iş düştü → işaret PENDING; 2 dk dolmadan süpürücü dokunmaz, sonra iki pod yarışsa da TEK gönderim")
    void lostAsync_sweeperSendsExactlyOnce() {
        AlertEvent e = openAlarm("lost.example.com");
        EscalationService dying = pod(1, false);
        dying.processResults(List.of(healthy("lost.example.com")));
        assertThat(store.byId(e.getId()).orElseThrow().getResolved()).isTrue();
        assertThat(marker(e.getId())).isEqualTo(EscalationService.RESOLUTION_NOTIFY_PENDING);
        assertThat(resolveSends()).isZero();

        EscalationService a = pod(2, true), b = pod(3, true);
        assertThat(a.deliverPendingResolutions(Instant.now())).as("2 dk payı dolmadı").isZero();

        Instant later = Instant.now().plus(3, ChronoUnit.MINUTES);
        List<Integer> sent = mp.race(() -> a.deliverPendingResolutions(later), () -> b.deliverPendingResolutions(later));
        assertThat(sent.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(resolveSends()).isEqualTo(1);
        assertThat(marker(e.getId())).isEqualTo(EscalationService.RESOLUTION_NOTIFY_DONE);

        assertThat(a.deliverPendingResolutions(later.plus(1, ChronoUnit.MINUTES))).isZero();
        assertThat(resolveSends()).isEqualTo(1);
    }

    @Test
    @DisplayName("süpürücü (ResolutionNotifySweeper) kilitsiz kurulumda da tek gönderir; tur sonucu sayıdır")
    void sweeperComponentDelegates() {
        AlertEvent e = openAlarm("sweeper.example.com");
        pod(1, false).processResults(List.of(healthy("sweeper.example.com")));
        mp.jdbc().update("UPDATE alert_events SET resolved_at = ? WHERE id = ?",
                ISO.format(Instant.now().minus(5, ChronoUnit.MINUTES)), e.getId());
        ResolutionNotifySweeper sweeper = new ResolutionNotifySweeper(pod(2, true));
        sweeper.scheduledSweep();
        assertThat(resolveSends()).isEqualTo(1);
        assertThat(marker(e.getId())).isEqualTo(EscalationService.RESOLUTION_NOTIFY_DONE);
    }

    @Test
    @DisplayName("10 dk'dan eski SENDING → UNKNOWN + bildirim günlüğü izi, yeniden GÖNDERİLMEZ (en çok bir kez)")
    void staleSending_becomesUnknownWithoutResend() {
        AlertEvent e = openAlarm("stale.example.com");
        Instant now = Instant.now();
        mp.jdbc().update("UPDATE alert_events SET resolved = TRUE, resolved_at = ?, resolved_by = 'system', "
                + "resolution_notify = 'SENDING', resolution_notify_at = ? WHERE id = ?",
                ISO.format(now.minus(20, ChronoUnit.MINUTES)), ISO.format(now.minus(15, ChronoUnit.MINUTES)), e.getId());
        EscalationService a = pod(1, true);
        assertThat(a.deliverPendingResolutions(now)).isZero();
        assertThat(marker(e.getId())).isEqualTo(EscalationService.RESOLUTION_NOTIFY_UNKNOWN);
        assertThat(resolveSends()).isZero();
        ArgumentCaptor<NotificationLog> cap = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues()).anySatisfy(n -> {
            assertThat(n.getAlertEventId()).isEqualTo(e.getId());
            assertThat(n.getEmailStatus()).isEqualTo(EscalationService.STATUS_RESOLUTION_UNKNOWN);
            assertThat(n.getTrigger()).isEqualTo("RESOLUTION");
        });
        assertThat(a.deliverPendingResolutions(now.plus(1, ChronoUnit.MINUTES))).isZero();
    }

    @Test
    @DisplayName("24 saati geçmiş PENDING → EXPIRED + iz; geç 'çözüldü' gönderilmez")
    void veryOldPending_expires() {
        AlertEvent e = openAlarm("old.example.com");
        Instant now = Instant.now();
        mp.jdbc().update("UPDATE alert_events SET resolved = TRUE, resolved_at = ?, resolved_by = 'system', "
                + "resolution_notify = 'PENDING', resolution_notify_at = ? WHERE id = ?",
                ISO.format(now.minus(26, ChronoUnit.HOURS)), ISO.format(now.minus(26, ChronoUnit.HOURS)), e.getId());
        EscalationService a = pod(1, true);
        assertThat(a.deliverPendingResolutions(now)).isZero();
        assertThat(marker(e.getId())).isEqualTo(EscalationService.RESOLUTION_NOTIFY_EXPIRED);
        assertThat(resolveSends()).isZero();
        ArgumentCaptor<NotificationLog> cap = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getAllValues()).extracting(NotificationLog::getEmailStatus)
                .contains(EscalationService.STATUS_RESOLUTION_EXPIRED);
    }

    @Test
    @DisplayName("işaretsiz satır (eski pod kapattı) eski davranış: eşzamansız iş gönderir; işaret DONE iken GÖNDERMEZ")
    void legacyRowSends_doneRowDoesNot() {
        AlertEvent e = openAlarm("legacy.example.com");
        mp.jdbc().update("UPDATE alert_events SET resolved = TRUE, resolved_at = ?, resolved_by = 'system' WHERE id = ?",
                ISO.format(Instant.now()), e.getId());
        EscalationService a = pod(1, true);
        AlertEvent closed = store.byId(e.getId()).orElseThrow();
        a.sendResolutionNotificationAsync(closed, "Sistem (otomatik)", "RESOLUTION");
        assertThat(resolveSends()).isEqualTo(1);
        assertThat(marker(e.getId())).as("işaretsiz satır işaretsiz kalır").isNull();

        mp.jdbc().update("UPDATE alert_events SET resolution_notify = 'DONE' WHERE id = ?", e.getId());
        a.sendResolutionNotificationAsync(closed, "Sistem (otomatik)", "RESOLUTION");
        assertThat(resolveSends()).as("başka yol göndermiş").isEqualTo(1);
    }

    @Test
    @DisplayName("fırtına üyesinin kapanışı işaret YAZMAZ (bireysel posta yok — toplu çözüm fırtınadan); süpürücü de göndermez")
    void stormMember_noMarker() {
        AlertEvent e = openAlarm("storm.example.com");
        mp.jdbc().update("UPDATE alert_events SET storm_id = 9 WHERE id = ?", e.getId());
        when(stormService.isActive(9L)).thenReturn(true);
        EscalationService a = pod(1, true);
        a.processResults(List.of(healthy("storm.example.com")));
        assertThat(store.byId(e.getId()).orElseThrow().getResolved()).isTrue();
        assertThat(marker(e.getId())).isNull();
        long pushes = resolveSends();   // fırtına dalının push simetrisi (bireysel posta değil)
        assertThat(a.deliverPendingResolutions(Instant.now().plus(3, ChronoUnit.MINUTES))).isZero();
        assertThat(resolveSends()).isEqualTo(pushes);
    }

    @Test
    @DisplayName("elle çözüm de işaret yazar; süpürücü elle çözümü MANUAL_RESOLVE tetiğiyle ve çözen adıyla gönderir")
    void manualResolve_markerAndSweeperTrigger() {
        AlertEvent e = openAlarm("manual.example.com");
        EscalationService dying = pod(1, false);
        dying.resolve(e.getId(), "ayse");
        assertThat(marker(e.getId())).isEqualTo(EscalationService.RESOLUTION_NOTIFY_PENDING);
        assertThat(store.byId(e.getId()).orElseThrow().getResolvedBy()).isEqualTo("ayse");

        EscalationService a = pod(2, true);
        assertThat(a.deliverPendingResolutions(Instant.now().plus(3, ChronoUnit.MINUTES))).isEqualTo(1);
        assertThat(marker(e.getId())).isEqualTo(EscalationService.RESOLUTION_NOTIFY_DONE);
        verify(userPushService).enqueueResolve(any(AlertEvent.class), any(), anyLong());
    }
}
