package com.sitemonitor.service;

import com.sitemonitor.model.AlertEscalationStep;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.QuietDigestItem;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Zamana bağlı eskalasyon adımı × takım sessiz saati (2026-10-01, onaylı öneri 15): vakti gelen adım, kişinin takımı
 * penceredeyse (ertelenebilir seviye) ya da alarmın o takım için bekleyen özeti varsa SAHİPLENMEDEN bekler — ilk beklemede
 * alarm özete yazılır ve TEK iz satırı düşer; KRİTİK adım beklemez; pencere tanımsız/dışıyken adım bugünkü gibi gider.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationStepQuietHoldTest {

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
    @Mock AlertStormRepository stormRepo;
    @Mock NetworkOutageEventRepository outageRepo;
    @Mock SchedulerService schedulerService;
    @Mock QuietDigestItemRepository itemRepo;

    static final long TEAM = 42L;
    /** 2026-10-01 15:00 Istanbul. */
    static final Instant NOW = QuietHoursTest.ist("2026-10-01T15:00:00");
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    EscalationService escalation;
    EscalationStepService job;
    TeamQuietHoursService quiet;
    Team team;
    final List<AlertEscalationStep> stepRows = new ArrayList<>();
    final List<NotificationLog> logRows = new ArrayList<>();
    final List<AlertEvent> open = new ArrayList<>();
    final List<EscalationContact> contacts = new ArrayList<>();
    final List<QuietDigestItem> items = new ArrayList<>();

    @BeforeEach
    void setUp() {
        escalation = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(escalation, "self", escalation);
        ReflectionTestUtils.setField(escalation, "escalationStepRepo", stepRepo);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(smtpSettings.getOrDefaults()).thenReturn(new com.sitemonitor.model.SmtpSettings());
        job = new EscalationStepService(contactRepo, alertEventRepo, stepRepo, inventoryRepo, notificationLogRepo,
                stormRepo, outageRepo, maintenanceService, escalation, schedulerService);
        job.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        quiet = new TeamQuietHoursService(teamRepo, itemRepo);
        quiet.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        team = new Team();
        team.setId(TEAM);
        team.setName("Ödeme");
        team.setEmail("team@x.com");
        when(teamRepo.findById(TEAM)).thenReturn(Optional.of(team));
        when(teamRepo.findQuietConfigured()).thenReturn(List.of());

        when(alertEventRepo.findAllOpenOrderBySeverity()).thenAnswer(i -> new ArrayList<>(open));
        when(alertEventRepo.findById(anyLong())).thenAnswer(i -> open.stream()
                .filter(e -> e.getId().equals(i.getArgument(0))).findFirst());
        when(contactRepo.findByTeamIdInAndActiveTrueOrderByRoleAsc(anyCollection())).thenAnswer(i -> {
            Collection<?> teams = i.getArgument(0);
            return contacts.stream().filter(c -> teams.contains(c.getTeamId())).toList();
        });
        when(stepRepo.saveAndFlush(any())).thenAnswer(i -> { stepRows.add(i.getArgument(0)); return i.getArgument(0); });
        when(stepRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(stepRepo.findByAlertEventIdIn(anyCollection())).thenAnswer(i -> new ArrayList<>(stepRows));
        when(stepRepo.findNotifiedContactIds(anyLong())).thenReturn(List.of());
        when(notificationLogRepo.save(any())).thenAnswer(i -> { logRows.add(i.getArgument(0)); return i.getArgument(0); });
        when(notificationLogRepo.latestSentAtByAlertIds(anyCollection(), anyCollection())).thenReturn(List.of());
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn("SENT");
        when(emailService.buildAlertEmailHtml(anyString(), anyString(), any(), any(), any(), any(), any())).thenReturn("<html/>");
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of());
        when(outageRepo.findByStatus("ONGOING")).thenReturn(List.of());
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of());
        when(itemRepo.saveAndFlush(any())).thenAnswer(i -> { items.add(i.getArgument(0)); return i.getArgument(0); });
        when(itemRepo.findPendingByAlertEventIdIn(anyCollection())).thenAnswer(i -> items.stream()
                .filter(x -> x.getDigestSentAt() == null && x.getSupersededAt() == null).toList());

        AlertEvent e = new AlertEvent();
        e.setId(10L);
        e.setDomain("svc.example.com");
        e.setAlertType("ACCESSIBILITY");
        e.setAlertLevel("WARNING");
        e.setTeamId(TEAM);
        e.setMessage("UYARI: svc erişilemiyor");
        String at = ISO.format(NOW.minus(60, ChronoUnit.MINUTES));
        e.setCreatedAt(at);
        e.setLastReAlertAt(at);
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setNotifiedContacts("[]");
        open.add(e);
        EscalationContact c = new EscalationContact();
        c.setId(1L);
        c.setTeamId(TEAM);
        c.setMinAlertLevel("WARNING");
        c.setDelayMinutes(30);
        c.setEmail("mgr@x.com");
        c.setName("Müdür");
        c.setRole("MANAGER");
        c.setActive(true);
        contacts.add(c);
    }

    private void quietWindow(String start, String end) {
        team.setQuietStart(start);
        team.setQuietEnd(end);
        when(teamRepo.findQuietConfigured()).thenReturn(List.of(team));
        quiet.invalidate();
        job.setQuietHours(quiet);
    }

    private int mailsSent() {
        return mockingDetails(emailService).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendAlert")).toList().size();
    }

    @Test
    @DisplayName("Pencere tanımsız: vakti gelen adım bugünkü gibi gider (özet tablosuna dokunulmaz)")
    void noWindow_stepSentAsToday() {
        job.setQuietHours(quiet);   // servis var, ayar yok

        job.sweep(NOW);

        assertThat(mailsSent()).isEqualTo(1);
        assertThat(stepRows).singleElement().extracting(AlertEscalationStep::getOutcome).isEqualTo(AlertEscalationStep.SENT);
        verifyNoInteractions(itemRepo);
    }

    @Test
    @DisplayName("Pencerede UYARI adımı BEKLER: sahiplenilmez, e-posta yok; alarm özete yazılır + TEK iz satırı (tekrar turda yok)")
    void inWindow_warningStepHeld_onceTraced() {
        quietWindow("14:00", "16:00");

        job.sweep(NOW);
        job.sweep(NOW.plusSeconds(60));

        assertThat(mailsSent()).isZero();
        assertThat(stepRows).isEmpty();   // karar sahiplenilmedi → pencere sonrası yeniden değerlendirilir
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.getFirstTrigger()).isEqualTo(TeamQuietHoursService.STEP_TRIGGER);
            assertThat(i.getOpeningDeferred()).isFalse();
        });
        assertThat(logRows).singleElement().satisfies(l -> {
            assertThat(l.getTrigger()).isEqualTo(EscalationService.TRIGGER_QUIET_HOURS);
            assertThat(l.getEmailStatus()).isEqualTo(EscalationService.STATUS_QUIET_DEFERRED);
            assertThat(l.getRecipientEmail()).isEqualTo("mgr@x.com");
        });
    }

    @Test
    @DisplayName("Pencere bitti ama özet henüz gitmedi: adım hâlâ bekler; özet gidince bugünkü kurala döner")
    void windowOver_pendingDigest_stillHeld() {
        quietWindow("14:00", "16:00");
        job.sweep(NOW);                                   // beklemeye alındı, özet kaydı oluştu
        Instant after = QuietHoursTest.ist("2026-10-01T16:00:30");
        quiet.clock = Clock.fixed(after, ZoneOffset.UTC);

        job.sweep(after);
        assertThat(mailsSent()).isZero();

        items.get(0).setDigestSentAt(ISO.format(after));   // özet gitti
        job.sweep(after.plusSeconds(60));
        assertThat(mailsSent()).isEqualTo(1);
    }

    @Test
    @DisplayName("KRİTİK alarmın adımı pencerede de bekletilmez")
    void critical_neverHeld() {
        quietWindow("14:00", "16:00");
        open.get(0).setAlertLevel("CRITICAL");
        contacts.get(0).setMinAlertLevel("CRITICAL");

        job.sweep(NOW);

        assertThat(mailsSent()).isEqualTo(1);
        assertThat(items).isEmpty();
    }

    @Test
    @DisplayName("Sessiz saat özeti, adım saatinin çapasıdır (ertelenmiş açılışın gerçek duyurusu)")
    void digestIsAnchor() {
        assertThat(EscalationStepService.ANCHOR_TRIGGERS)
                .containsExactly("INITIAL", "ESCALATION", EscalationService.TRIGGER_QUIET_DIGEST);
    }
}
