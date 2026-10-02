package com.sitemonitor.service;

import com.sitemonitor.model.AlertEscalationStep;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.NetworkOutageEvent;
import com.sitemonitor.model.NotificationLog;
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
import org.springframework.dao.DataIntegrityViolationException;
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
 * Zamana bağlı eskalasyon adımı işi (2026-10-01) — {@link EscalationStepService} GERÇEK {@link EscalationService} ile
 * (bağımlılıkları sahte): adım yalnız açık + onaysız alarmda, gecikme dolunca, kapsam/seviye kurallarıyla BİR kez gider;
 * onay / çözüm / fırtına / bakım / toplu kesinti / sahipsiz / UYARI-bağımsız / yabancı takım durumlarında gitmez ve atlanan
 * her adım {@code ESCALATION_STEP} satırı bırakır; iş {@code scheduler_lock} altında koşar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationStepServiceTest {

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

    static final long TEAM = 42L, UG = 43L, OTHER = 77L;
    static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    EscalationService escalation;
    EscalationStepService job;
    final List<AlertEscalationStep> stepRows = new ArrayList<>();
    final List<NotificationLog> logRows = new ArrayList<>();
    final List<AlertEvent> open = new ArrayList<>();
    final List<EscalationContact> contacts = new ArrayList<>();

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
        job.clock = java.time.Clock.fixed(NOW, ZoneOffset.UTC);

        when(alertEventRepo.findAllOpenOrderBySeverity()).thenAnswer(i -> new ArrayList<>(open));
        when(alertEventRepo.findById(anyLong())).thenAnswer(i -> open.stream()
                .filter(e -> e.getId().equals(i.getArgument(0))).findFirst());
        when(contactRepo.findByTeamIdInAndActiveTrueOrderByRoleAsc(anyCollection())).thenAnswer(i -> {
            Collection<?> teams = i.getArgument(0);
            return contacts.stream().filter(c -> Boolean.TRUE.equals(c.getActive()) && teams.contains(c.getTeamId())).toList();
        });
        when(contactRepo.existsByActiveTrueAndDelayMinutesGreaterThan(0))
                .thenAnswer(i -> contacts.stream().anyMatch(c -> Boolean.TRUE.equals(c.getActive()) && EscalationDelay.isDelayed(c)));
        // Adım tablosu: bellek-içi, UNIQUE(alarm, kişi, seviye) — ikinci sahiplenme çakışır (gerçek indeks gibi).
        when(stepRepo.saveAndFlush(any())).thenAnswer(i -> {
            AlertEscalationStep s = i.getArgument(0);
            boolean dup = stepRows.stream().anyMatch(r -> r.getAlertEventId().equals(s.getAlertEventId())
                    && r.getContactId().equals(s.getContactId()) && r.getAlertLevel().equals(s.getAlertLevel()));
            if (dup) throw new DataIntegrityViolationException("ux_aes_event_contact_level");
            s.setId((long) stepRows.size() + 1);
            stepRows.add(s);
            return s;
        });
        when(stepRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(stepRepo.findByAlertEventIdIn(anyCollection())).thenAnswer(i -> {
            Collection<?> ids = i.getArgument(0);
            return stepRows.stream().filter(r -> ids.contains(r.getAlertEventId())).toList();
        });
        when(stepRepo.findNotifiedContactIds(anyLong())).thenAnswer(i -> stepRows.stream()
                .filter(r -> r.getAlertEventId().equals(i.getArgument(0)) && !AlertEscalationStep.SKIPPED.equals(r.getOutcome()))
                .map(AlertEscalationStep::getContactId).distinct().toList());
        when(notificationLogRepo.save(any())).thenAnswer(i -> { logRows.add(i.getArgument(0)); return i.getArgument(0); });
        when(notificationLogRepo.latestSentAtByAlertIds(anyCollection(), anyCollection())).thenReturn(List.of());
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn("SENT");
        when(emailService.buildAlertEmailHtml(anyString(), anyString(), any(), any(), any(), any(), any())).thenReturn("<html/>");
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of());
        when(outageRepo.findByStatus("ONGOING")).thenReturn(List.of());
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of());
    }

    // ── fikstür ──────────────────────────────────────────────────────────────────────────────────

    static AlertEvent alarm(long id, String type, String level, Long teamId, int minutesAgo) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain("svc-" + id + ".example.com");
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setTeamId(teamId);
        e.setMessage(level + ": svc-" + id + " erişilemiyor");
        String at = ISO.format(NOW.minus(minutesAgo, ChronoUnit.MINUTES));
        e.setCreatedAt(at);
        e.setLastReAlertAt(at);   // ilk bildirim gitti
        e.setAcknowledged(false);
        e.setResolved(false);
        e.setNotifiedContacts("[]");
        return e;
    }

    static EscalationContact contact(long id, Long team, String minLevel, Integer delay, String email) {
        EscalationContact c = new EscalationContact();
        c.setId(id);
        c.setTeamId(team);
        c.setMinAlertLevel(minLevel);
        c.setDelayMinutes(delay);
        c.setEmail(email);
        c.setName("Kişi " + id);
        c.setRole("MANAGER");
        c.setActive(true);
        return c;
    }

    private List<String[]> sentRecipients() {
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService, atLeast(0)).sendAlert(to.capture(), anyString(), anyString(), any(), any(), any(), any(), any());
        return to.getAllValues();
    }

    /** E-posta GÖNDERİLMEDİ (günlük satırı yazımı gönderen adresini okuyabilir — o gönderim değil). */
    private void assertNoMailSent() {
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any());
        verify(emailService, never()).buildAlertEmailHtml(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    private List<NotificationLog> stepLogs() {
        return logRows.stream().filter(l -> EscalationService.TRIGGER_ESCALATION_STEP.equals(l.getTrigger())).toList();
    }

    // ── kilit + ön kapı ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Gecikme tanımlı kişi YOK: tek ön kapı sorgusu — kilit alınmaz, açık alarm okunmaz, hiçbir şey gönderilmez")
    void noDelayedContact_singleCheap_query_noLock() {
        contacts.add(contact(1, TEAM, "WARNING", null, "po@x.com"));
        contacts.add(contact(2, TEAM, "WARNING", 0, "tech@x.com"));
        open.add(alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 120));

        job.scheduledSweep();

        verify(contactRepo).existsByActiveTrueAndDelayMinutesGreaterThan(0);
        verifyNoInteractions(schedulerService, stepRepo, emailService, webhookService);
        verify(alertEventRepo, never()).findAllOpenOrderBySeverity();
    }

    @Test
    @DisplayName("İş scheduler_lock 'escalation-steps' altında koşar; kilit başka pod'daysa tur hiçbir şey yapmaz")
    void sweep_runsOnlyUnderSchedulerLock() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        open.add(alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 45));

        // Kilit başka pod'da: runWithSchedulerLock görevi koşturmaz.
        job.scheduledSweep();
        verify(schedulerService).runWithSchedulerLock(eq(EscalationStepService.LOCK_NAME), any());
        verify(alertEventRepo, never()).findAllOpenOrderBySeverity();
        assertNoMailSent();

        // Kilit bizde: görev koşar ve adım gider.
        doAnswer(i -> { ((Runnable) i.getArgument(1)).run(); return null; })
                .when(schedulerService).runWithSchedulerLock(eq(EscalationStepService.LOCK_NAME), any());
        job.scheduledSweep();
        assertThat(sentRecipients()).hasSize(1);
    }

    // ── gönderim + tekillik ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Açık + onaysız alarm, gecikme doldu → gecikmeli kişiye TEK adım (e-posta + webhook); ikinci tur göndermez")
    void delayElapsed_stepSentOnce_viaContactChannels() {
        EscalationContact mgr = contact(1, TEAM, "WARNING", 30, "mgr@x.com");
        mgr.setWebhookUrl("https://hooks.example.com/a");
        mgr.setWebhookType("TEAMS");
        contacts.add(mgr);
        contacts.add(contact(2, TEAM, "WARNING", null, "po@x.com"));   // anlık kişi: adım işine HİÇ girmez
        open.add(alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 31));

        EscalationStepService.SweepResult r = job.sweep(NOW);

        assertThat(r.sent()).isEqualTo(1);
        List<String[]> to = sentRecipients();
        assertThat(to).hasSize(1);
        assertThat(to.get(0)).containsExactly("mgr@x.com");   // takım adresi / anlık kişi YOK
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendAlert(any(String[].class), subject.capture(), message.capture(),
                eq("svc-10.example.com"), eq("CRITICAL"), eq("HTTP_DOWN"), any(), any());
        assertThat(subject.getValue()).startsWith("[ESKALASYON · 30 dk onaysız] [Site Monitor] KRİTİK");
        assertThat(message.getValue()).contains("30 dakikadır kimse tarafından onaylanmadı");
        verify(webhookService).send(eq("TEAMS"), eq("https://hooks.example.com/a"), anyString(), anyString(), eq("CRITICAL"));
        verifyNoInteractions(userPushService);   // kişi push'u / NOC adımın parçası değil
        assertThat(stepLogs()).singleElement().satisfies(l -> {
            assertThat(l.getEmailStatus()).isEqualTo("SENT");
            assertThat(l.getWebhookStatus()).isEqualTo("SENT");
            assertThat(l.getRecipientEmail()).isEqualTo("mgr@x.com");
            assertThat(l.getAlertEventId()).isEqualTo(10L);
        });
        assertThat(stepRows).singleElement().satisfies(s -> {
            assertThat(s.getOutcome()).isEqualTo(AlertEscalationStep.SENT);
            assertThat(s.getContactId()).isEqualTo(1L);
            assertThat(s.getAlertLevel()).isEqualTo("CRITICAL");
        });

        // Aynı alarm, sonraki turlar (ya da yeniden başlatma sonrası): adım TEKRAR gitmez.
        job.sweep(NOW.plus(5, ChronoUnit.MINUTES));
        job.sweep(NOW.plus(2, ChronoUnit.HOURS));
        verify(emailService, times(1)).sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any());
        assertThat(stepLogs()).hasSize(1);
    }

    @Test
    @DisplayName("Gecikme dolmadı → adım yok, karar satırı da yazılmaz (sonraki turda değerlendirilir)")
    void delayNotElapsed_nothing() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        open.add(alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 29));

        job.sweep(NOW);

        assertNoMailSent();
        assertThat(stepRows).isEmpty();
        assertThat(logRows).isEmpty();
    }

    @Test
    @DisplayName("Başka pod aynı adımı sahiplendiyse (UNIQUE çakışması) bu pod GÖNDERMEZ")
    void claimRace_lost_noSend() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        open.add(alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 60));
        doThrow(new DataIntegrityViolationException("dup")).when(stepRepo).saveAndFlush(any());

        job.sweep(NOW);

        assertNoMailSent();
        verifyNoInteractions(webhookService);
        assertThat(logRows).isEmpty();
    }

    // ── atlanan adımlar (iz bırakır) ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Onaylanmış (sahiplenilmiş) alarm → adım GİTMEZ, 'SKIPPED: alarm onaylandı' izi bir kez yazılır")
    void acknowledged_skippedWithTrace_once() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        AlertEvent e = alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 60);
        e.setAcknowledged(true);
        e.setAcknowledgedBy("ayse");
        open.add(e);

        job.sweep(NOW);
        job.sweep(NOW.plus(10, ChronoUnit.MINUTES));

        assertNoMailSent();
        verifyNoInteractions(webhookService);
        assertThat(stepLogs()).singleElement().satisfies(l -> {
            assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: alarm onaylandı (ayse)");
            assertThat(l.getWebhookStatus()).isEqualTo("SKIPPED");
            assertThat(l.getRecipientEmail()).isEqualTo("mgr@x.com");
        });
        assertThat(stepRows).singleElement().extracting(AlertEscalationStep::getOutcome).isEqualTo(AlertEscalationStep.SKIPPED);
    }

    @Test
    @DisplayName("Onay yüzünden atlanan adım, alarm seviye atlayıp onay düşünce YENİ seviyede yeniden değerlendirilir")
    void ackSkip_isPerLevel_levelRiseReevaluates() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        AlertEvent e = alarm(10, "EXPIRY", "WARNING", TEAM, 120);
        e.setAcknowledged(true);
        open.add(e);
        job.sweep(NOW);
        assertThat(stepRows).hasSize(1);

        // Seviye artışı: onay düştü, yeni çapa = ESCALATION satırı (40 dk önce) → 30 dk gecikme doldu.
        e.setAlertLevel("CRITICAL");
        e.setAcknowledged(false);
        when(notificationLogRepo.latestSentAtByAlertIds(anyCollection(), anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, ISO.format(NOW.minus(40, ChronoUnit.MINUTES))}));
        job.sweep(NOW);

        assertThat(sentRecipients()).singleElement().satisfies(to -> assertThat(to).containsExactly("mgr@x.com"));
        assertThat(stepRows).extracting(AlertEscalationStep::getAlertLevel).containsExactly("WARNING", "CRITICAL");
    }

    @Test
    @DisplayName("Saat çapası son açılış/seviye-artışı duyurusu: 3 saatlik alarm 10 dk önce eskale olduysa 30 dk'lık adım henüz gitmez")
    void anchor_isLatestAnnouncement_notCreatedAt() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        open.add(alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 180));
        when(notificationLogRepo.latestSentAtByAlertIds(anyCollection(), eq(EscalationStepService.ANCHOR_TRIGGERS)))
                .thenReturn(List.<Object[]>of(new Object[]{10L, ISO.format(NOW.minus(10, ChronoUnit.MINUTES))}));

        job.sweep(NOW);

        assertNoMailSent();
        assertThat(stepRows).isEmpty();
    }

    @Test
    @DisplayName("İlk bildirimi henüz gitmemiş alarm (bakımda ertelenmiş: lastReAlertAt null, duyuru yok) → saat başlamaz")
    void notYetAnnounced_noStep() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        AlertEvent e = alarm(10, "DNS_CHANGED", "HIGH", TEAM, 300);
        e.setLastReAlertAt(null);
        open.add(e);

        job.sweep(NOW);

        assertNoMailSent();
        assertThat(stepRows).isEmpty();
    }

    @Test
    @DisplayName("Tur başındaki listeden sonra çözülen alarm (taze okuma) → adım gitmez, 'alarm çözüldü' izi")
    void resolvedBeforeSend_freshRead_skipped() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        AlertEvent listed = alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 60);
        open.add(listed);
        AlertEvent fresh = alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 60);
        fresh.setResolved(true);
        when(alertEventRepo.findById(10L)).thenReturn(Optional.of(fresh));

        job.sweep(NOW);

        assertNoMailSent();
        assertThat(stepLogs()).singleElement().extracting(NotificationLog::getEmailStatus).isEqualTo("SKIPPED: alarm çözüldü");
        assertThat(stepRows).singleElement().extracting(AlertEscalationStep::getOutcome).isEqualTo(AlertEscalationStep.SKIPPED);
    }

    @Test
    @DisplayName("Çözülmüş alarm açık listede yoksa hiçbir şey yapılmaz")
    void resolvedAlarm_notInOpenList_nothing() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));

        job.sweep(NOW);

        assertNoMailSent();
        verify(contactRepo, never()).findByTeamIdInAndActiveTrueOrderByRoleAsc(anyCollection());
        assertThat(logRows).isEmpty();
    }

    @Test
    @DisplayName("Aktif fırtına üyesi alarm eskale OLMAZ — 'SKIPPED: fırtına #N' izi")
    void stormMember_skipped() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        AlertEvent e = alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 60);
        e.setStormId(5L);
        open.add(e);
        AlertStorm storm = new AlertStorm();
        storm.setId(5L);
        when(stormRepo.findByResolvedFalse()).thenReturn(List.of(storm));

        job.sweep(NOW);

        assertNoMailSent();
        verifyNoInteractions(webhookService);
        assertThat(stepLogs()).singleElement().extracting(NotificationLog::getEmailStatus).asString()
                .startsWith("SKIPPED: fırtına #5");
    }

    @Test
    @DisplayName("Bakım penceresindeki alarm eskale OLMAZ — 'SKIPPED: bakım penceresi' izi")
    void maintenance_skipped() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        open.add(alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 60));
        when(maintenanceService.isUnderMaintenance("svc-10.example.com")).thenReturn(true);

        job.sweep(NOW);

        assertNoMailSent();
        assertThat(stepLogs()).singleElement().extracting(NotificationLog::getEmailStatus).isEqualTo("SKIPPED: bakım penceresi");
    }

    @Test
    @DisplayName("Türün toplu kesinti bastırması sürerken eskalasyon yok; 24 saatten eski (kapanmamış) kayıt bastırma sayılmaz")
    void fleetOutage_skipped_staleOutageIgnored() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        open.add(alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 60));
        NetworkOutageEvent ongoing = new NetworkOutageEvent();
        ongoing.setSource("HTTP_DOWN");
        ongoing.setStatus("ONGOING");
        ongoing.setDetectedAt(ISO.format(NOW.minus(1, ChronoUnit.HOURS)));
        when(outageRepo.findByStatus("ONGOING")).thenReturn(List.of(ongoing));

        job.sweep(NOW);
        assertNoMailSent();
        assertThat(stepLogs()).singleElement().extracting(NotificationLog::getEmailStatus).asString()
                .startsWith("SKIPPED: toplu kesinti bastırması");

        // Bayat ONGOING (2 gün önce, kapanmamış) → ikinci alarmın adımı GİDER.
        stepRows.clear(); logRows.clear(); open.clear();
        open.add(alarm(11, "HTTP_DOWN", "CRITICAL", TEAM, 60));
        ongoing.setDetectedAt(ISO.format(NOW.minus(2, ChronoUnit.DAYS)));
        job.sweep(NOW);
        assertThat(sentRecipients()).hasSize(1);
    }

    // ── kapsam: sahipsiz / UYARI-bağımsız / yabancı takım / seviye eşiği / UG ───────────────────

    @Test
    @DisplayName("Sahipsiz alarm (takım yok) hiçbir adım üretmez — kişi sorgusu bile atılmaz")
    void ownerless_nothing() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        open.add(alarm(10, "HTTP_DOWN", "CRITICAL", null, 60));

        job.sweep(NOW);

        assertNoMailSent();
        verifyNoInteractions(webhookService);
        verify(contactRepo, never()).findByTeamIdInAndActiveTrueOrderByRoleAsc(anyCollection());
        assertThat(stepRows).isEmpty();
        assertThat(logRows).isEmpty();
    }

    @Test
    @DisplayName("UYARI seviyeli bağımsız izleme alarmı yalnız takıma gider — gecikmeli kişiye de adım YOK")
    void warningStandalone_noContacts_noStep() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "mgr@x.com"));
        open.add(alarm(10, "HTTP_SLOW", "WARNING", TEAM, 60));

        job.sweep(NOW);

        assertNoMailSent();
        assertThat(stepRows).isEmpty();
    }

    @Test
    @DisplayName("Başka takımın gecikmeli kişisi asla adım almaz (kişi sorgusu yalnız sahip takımlarla)")
    void otherTeamsContact_neverStepped() {
        contacts.add(contact(9, OTHER, "WARNING", 30, "foreign@x.com"));
        open.add(alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 60));

        job.sweep(NOW);

        assertNoMailSent();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> teams = ArgumentCaptor.forClass(Collection.class);
        verify(contactRepo).findByTeamIdInAndActiveTrueOrderByRoleAsc(teams.capture());
        assertThat(teams.getValue()).containsExactly(TEAM);

        // Depo yanlışlıkla yabancı kişiyi döndürse bile kapsam süzgeci (kişinin KENDİ takımı) eler.
        when(contactRepo.findByTeamIdInAndActiveTrueOrderByRoleAsc(anyCollection())).thenReturn(List.copyOf(contacts));
        job.sweep(NOW);
        assertNoMailSent();
    }

    @Test
    @DisplayName("Seviye eşiği: KRİTİK eşikli gecikmeli kişi YÜKSEK alarmda adım almaz")
    void levelGate_respected() {
        contacts.add(contact(1, TEAM, "CRITICAL", 30, "cto@x.com"));
        open.add(alarm(10, "EXPIRY", "HIGH", TEAM, 60));

        job.sweep(NOW);

        assertNoMailSent();
        assertThat(stepRows).isEmpty();
    }

    @Test
    @DisplayName("Envanter alarmı: UG takımının gecikmeli kişisi de (kendi takımı sahip olduğu için) adım alır")
    void inventoryAlarm_ugTeamContact_stepped() {
        contacts.add(contact(5, UG, "WARNING", 15, "ug-mgr@x.com"));
        open.add(alarm(10, "EXPIRY", "HIGH", TEAM, 60));
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("svc-10.example.com");
        inv.setTeamId(TEAM);
        inv.setUgTeamId(UG);
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of(inv));

        job.sweep(NOW);

        assertThat(sentRecipients()).singleElement().satisfies(to -> assertThat(to).containsExactly("ug-mgr@x.com"));
    }

    @Test
    @DisplayName("Kişi alarmı gecikme tanımlanmadan ÖNCE anlık almıştı → adım gitmez (PRIOR, günlüğe satır yok), döngüye alınır")
    void alreadyNotifiedBeforeDelay_prior_noStep() {
        contacts.add(contact(1, TEAM, "WARNING", 30, "Mgr@X.com"));
        AlertEvent e = alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 600);
        e.setNotifiedContacts("[{\"name\":\"Kişi 1\",\"email\":\"mgr@x.com\",\"role\":\"MANAGER\"}]");
        open.add(e);

        EscalationStepService.SweepResult r = job.sweep(NOW);

        assertThat(r.prior()).isEqualTo(1);
        assertNoMailSent();
        assertThat(logRows).isEmpty();
        assertThat(stepRows).singleElement().extracting(AlertEscalationStep::getOutcome).isEqualTo(AlertEscalationStep.PRIOR);
    }

    @Test
    @DisplayName("İzlemede e-posta kanalı kapalı → adımın e-postası atlanır, kişinin webhook'u yine gider (anlık yolla aynı)")
    void mailDisabled_webhookStillSent() {
        EscalationContact mgr = contact(1, TEAM, "WARNING", 30, "mgr@x.com");
        mgr.setWebhookUrl("https://hooks.example.com/b");
        mgr.setWebhookType("SLACK");
        contacts.add(mgr);
        AlertEvent e = alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 60);
        e.setContextJson("{\"team_id\":42,\"mail_disabled\":true}");
        open.add(e);

        job.sweep(NOW);

        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any());
        verify(webhookService).send(eq("SLACK"), eq("https://hooks.example.com/b"), anyString(), anyString(), eq("CRITICAL"));
        assertThat(stepLogs()).singleElement().satisfies(l -> {
            assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: e-posta kanalı kapalı");
            assertThat(l.getWebhookStatus()).isEqualTo("SENT");
        });
    }

    @Test
    @DisplayName("Sahip takım çözümü elle-gönderim/çözüm yoluyla aynı: bağımsız tür → olay takımı, UG yok; envanter türevi → damga önce, sonra envanter SY + UG")
    void stepOwners_mirrorsResendRule() {
        AlertEvent http = alarm(1, "HTTP_DOWN", "CRITICAL", TEAM, 10);
        CertificateInventory inv = new CertificateInventory();
        inv.setTeamId(OTHER);
        inv.setUgTeamId(UG);
        assertThat(escalation.stepOwners(http, inv)).isEqualTo(new EscalationService.StepOwners(TEAM, null));

        AlertEvent cert = alarm(2, "EXPIRY", "HIGH", null, 10);
        assertThat(escalation.stepOwners(cert, inv)).isEqualTo(new EscalationService.StepOwners(OTHER, UG));
        cert.setTeamId(TEAM);   // damga önce
        assertThat(escalation.stepOwners(cert, inv)).isEqualTo(new EscalationService.StepOwners(TEAM, UG));

        AlertEvent port = alarm(3, "PORT_DOWN", "CRITICAL", TEAM, 10);
        port.setContextJson("{\"team_id\":42,\"standalone\":true}");   // bağımsız Port izlemesi → envanterden takım YOK
        assertThat(escalation.stepOwners(port, inv)).isEqualTo(new EscalationService.StepOwners(TEAM, null));
    }

    // ── Pasif kullanıcı (2026-10-02, kullanıcı kararı) ─────────────────────────────────────────────

    @Test
    @DisplayName("Pasif kullanıcıya bağlı gecikmeli kişi: adım GİTMEZ (e-posta/webhook yok), günlükte ESCALATION_STEP + 'SKIPPED: pasif kullanıcı' — tek kez")
    void passiveContact_stepSkippedWithTrace() {
        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findInactiveIdsAndEmails()).thenReturn(List.<Object[]>of(new Object[]{500L, "mgr@x.com"}));
        job.setInactiveGuard(new InactiveRecipientGuard(users));
        EscalationContact mgr = contact(1, TEAM, "WARNING", 30, "mgr@x.com");
        mgr.setUserId(500L);
        mgr.setWebhookUrl("https://hooks.example.com/a");
        mgr.setWebhookType("TEAMS");
        contacts.add(mgr);
        open.add(alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 31));

        EscalationStepService.SweepResult r = job.sweep(NOW);

        assertThat(r.sent()).isZero();
        assertThat(r.skipped()).isEqualTo(1);
        assertNoMailSent();
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
        assertThat(stepLogs()).singleElement()
                .satisfies(l -> assertThat(l.getEmailStatus()).isEqualTo(InactiveRecipientGuard.STATUS_SKIPPED));
        job.sweep(NOW);   // ikinci tur aynı kararı tekrar yazmaz (alarm × kişi × seviye sahiplenildi)
        assertThat(stepLogs()).hasSize(1);
    }

    @Test
    @DisplayName("Pasif listesi boşken (kullanıcı aktif) adım bugünkü gibi gider")
    void activeContact_stepUnchangedWithGuard() {
        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findInactiveIdsAndEmails()).thenReturn(List.of());
        job.setInactiveGuard(new InactiveRecipientGuard(users));
        EscalationContact mgr = contact(1, TEAM, "WARNING", 30, "mgr@x.com");
        mgr.setUserId(500L);
        contacts.add(mgr);
        open.add(alarm(10, "HTTP_DOWN", "CRITICAL", TEAM, 31));

        assertThat(job.sweep(NOW).sent()).isEqualTo(1);
    }
}
