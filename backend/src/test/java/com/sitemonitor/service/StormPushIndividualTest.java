package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "Push bildirimlerini alarm fırtınasına devretmeyelim. Alarm fırtınası durumunda push üzerinden teker teker bildirimleri
 * sırasıyla bildirelim." (kullanıcı kararı 2026-10-03, {@code site.monitor.storm.push-individual}, varsayılan AÇIK).
 *
 * <p>GERÇEK {@link UserPushService} (teslimat deposu bellek-içi taklit; outbox boşaltılmaz → hiçbir istek çıkmaz) ile
 * gerçek {@link EscalationService} ve gerçek {@link StormService} uç uca: hangi push satırının hangi sırayla, hangi karar
 * koduyla yazıldığı doğrudan okunur.
 *
 * <ul>
 *   <li>AÇIK — fırtınaya bağlanan alarm: STORM günlük satırı "yalnız e-posta devredildi" der, {@code SKIPPED_STORM} YOK,
 *       bireysel OPEN push; seviye artışı → ESCALATION; fırtınanın günlük tekrarı → üye başına RE_ALERT (açılış sırasıyla,
 *       onaylı/çözülmüş hariç), toplu push YOK; fırtına açılış/çözüm toplu push'u YOK; üyenin kurtuluşu → bireysel RESOLVE.</li>
 *   <li>Bireysel hattın kuralları aynen: takım sessiz saati ({@code SKIPPED_TEAM_QUIET}, özet kaydı YOK), sistem bakımı,
 *       hatırlatma ayarı ({@code SKIPPED_REALERT_OFF}), kişi başına saatlik tavan ({@code RATE_LIMITED}), OPEN tekilleştirme
 *       (mühür/bağ kopması → ikinci OPEN yok).</li>
 *   <li>KAPALI ≡ 2026-10-02: eski STORM metni + {@code SKIPPED_STORM}, toplu fırtına push'u (açılış / tekrar / çözüm).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StormPushIndividualTest {

    // ── EscalationService bağımlılıkları ──
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
    @Mock StormService stormMock;
    @Mock DomainMonitorRepository domainMonitorRepo;
    @Mock DomainCheckRepository domainCheckRepo;
    @Mock DnsRecordRepository dnsRecordRepo;
    @Mock PageCheckRepository pageCheckRepo;
    @Mock NotificationGroupService notificationGroups;
    @Mock AppSettingsService appSettings;
    // ── UserPushService bağımlılıkları ──
    @Mock UserPushDeliveryRepository deliveryRepo;
    @Mock UserPushScopeRepository scopeRepo;
    @Mock UserPushRecipientResolver resolver;
    @Mock SecretCipher secretCipher;
    @Mock TrustEvaluator trustEvaluator;
    @Mock CaAutoPinService caAutoPinService;
    // ── StormService bağımlılıkları ──
    @Mock AlertStormRepository stormRepo;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock QuietDigestItemRepository itemRepo;

    static final long TEAM = 14L;
    static final long STORM = 7L;
    static final String TYPE = EscalationService.TYPE_HTTP_DOWN;
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final List<String> USERS = List.of("N00001", "N00002");

    EscalationService service;
    UserPushService push;
    StormService engine;
    Team team;

    final Map<Long, AlertEvent> events = new LinkedHashMap<>();
    final AtomicLong eventSeq = new AtomicLong();
    final List<UserPushDelivery> store = new ArrayList<>();
    final AtomicLong pushSeq = new AtomicLong();
    final List<NotificationLog> logs = new ArrayList<>();

    boolean pushIndividual = true;
    boolean realertEnabled = true;
    int hourlyCap = 30;

    @BeforeEach
    void setUp() {
        push = new UserPushService(appSettings, deliveryRepo, scopeRepo, resolver, alertEventRepo, secretCipher,
                trustEvaluator, caAutoPinService);
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new tools.jackson.databind.ObjectMapper(), notificationLogRepo,
                latestCheckRepo, teamRepo, smtpSettings, maintenanceService, stormMock, push, domainMonitorRepo,
                domainCheckRepo, dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);
        engine = new StormService(stormRepo, alertEventRepo, appSettings, emailService, webhookService, teamRepo, contactRepo,
                inventoryRepo, jdbcTemplate, httpRepo, portRepo, keywordRepo, pingRepo, dnsRepo, domainMonitorRepo,
                notificationGroups);
        ReflectionTestUtils.setField(engine, "userPushService", push);
        ReflectionTestUtils.setField(engine, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(engine, "notificationLogRepo", notificationLogRepo);

        // Ayarlar: varsayılanlar; push kanalı AÇIK; fırtına push kipi + hatırlatma + saatlik tavan teste göre.
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
            String k = i.getArgument(0);
            if ("site.monitor.userpush.enabled".equals(k)) return true;
            if (StormService.KEY_PUSH_INDIVIDUAL.equals(k)) return pushIndividual;
            if ("site.monitor.userpush.realert-enabled".equals(k)) return realertEnabled;
            return i.getArgument(1);
        });
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i ->
                "site.monitor.userpush.hourly-cap".equals(i.getArgument(0)) ? hourlyCap : i.getArgument(1));
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getCsv(anyString(), anyString())).thenReturn(List.of("1"));

        when(smtpSettings.getOrDefaults()).thenReturn(new com.sitemonitor.model.SmtpSettings());
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());
        team = new Team();
        team.setId(TEAM);
        team.setName("Takım A");
        team.setEmail("takim-a@example.com");
        when(teamRepo.findById(TEAM)).thenReturn(Optional.of(team));
        when(teamRepo.findQuietConfigured()).thenReturn(List.of());
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn("SENT");

        // Alarm deposu (bellek-içi).
        when(alertEventRepo.save(any())).thenAnswer(i -> {
            AlertEvent e = i.getArgument(0);
            if (e.getId() == null) e.setId(eventSeq.incrementAndGet());
            events.put(e.getId(), e);
            return e;
        });
        when(alertEventRepo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(events.get((Long) i.getArgument(0))));
        when(alertEventRepo.findOpenAlert(anyString(), anyString())).thenAnswer(i -> events.values().stream()
                .filter(e -> e.getDomain().equals(i.getArgument(0)) && e.getAlertType().equals(i.getArgument(1))
                        && !Boolean.TRUE.equals(e.getResolved())).findFirst());
        when(alertEventRepo.findByDomainAndAlertTypeInAndResolvedFalse(anyString(), anyCollection())).thenAnswer(i -> {
            Collection<String> types = i.getArgument(1);
            return events.values().stream().filter(e -> e.getDomain().equals(i.getArgument(0))
                    && types.contains(e.getAlertType()) && !Boolean.TRUE.equals(e.getResolved())).toList();
        });
        when(alertEventRepo.markResolvedIfOpen(any(), any(), any())).thenReturn(1);
        when(notificationLogRepo.save(any())).thenAnswer(i -> { logs.add(i.getArgument(0)); return i.getArgument(0); });

        // Fırtına motoru taklidi (EscalationService yolu): alarm fırtına #7'ye bağlanır, fırtına aktif.
        when(stormMock.evaluate(any(), any())).thenAnswer(i -> {
            ((AlertEvent) i.getArgument(0)).setStormId(STORM);
            return StormService.StormAction.SUPPRESSED;
        });
        when(stormMock.isActive(STORM)).thenReturn(true);

        // Push alıcıları: takımın iki kişisi; çözümde önceden alanlar.
        when(resolver.resolve(any(), any())).thenReturn(USERS.stream()
                .map(u -> new UserPushRecipientResolver.Recipient(u, "Kişi " + u, null)).toList());
        when(resolver.resolvePrior(anyList())).thenAnswer(i -> ((List<String>) i.getArgument(0)).stream()
                .map(u -> new UserPushRecipientResolver.Recipient(u, "Kişi " + u, null)).toList());
        when(scopeRepo.findByScopeTypeAndScopeKey(anyString(), anyString())).thenReturn(Optional.empty());

        // Teslimat deposu taklidi — sorguların aynası (JPQL'leri UserPushDeliveryRepositoryTest'te).
        when(deliveryRepo.save(any())).thenAnswer(i -> {
            UserPushDelivery d = i.getArgument(0);
            if (d.getId() == null) { d.setId(pushSeq.incrementAndGet()); store.add(d); }
            return d;
        });
        when(deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(any(), any(), any())).thenAnswer(i -> store.stream()
                .anyMatch(d -> Objects.equals(d.getAlertEventId(), i.getArgument(0)) && Objects.equals(d.getDedupeKey(), i.getArgument(1))
                        && Objects.equals(d.getUsername(), i.getArgument(2))));
        when(deliveryRepo.existsByAlertEventIdAndStatus(any(), any())).thenAnswer(i -> store.stream()
                .anyMatch(d -> Objects.equals(d.getAlertEventId(), i.getArgument(0)) && Objects.equals(d.getStatus(), i.getArgument(1))));
        when(deliveryRepo.countRecentForUser(any(), any())).thenAnswer(i -> store.stream()
                .filter(d -> Objects.equals(d.getUsername(), i.getArgument(0))
                        && Set.of("PENDING", "SENT", "FAILED").contains(d.getStatus())
                        && d.getCreatedAt() != null && d.getCreatedAt().compareTo(i.getArgument(1)) >= 0).count());
        when(deliveryRepo.findByAlertEventIdOrderByIdAsc(any())).thenAnswer(i -> store.stream()
                .filter(d -> Objects.equals(d.getAlertEventId(), i.getArgument(0))).toList());
        when(deliveryRepo.existsByDedupeKeyAndUsername(any(), any())).thenAnswer(i -> store.stream()
                .anyMatch(d -> Objects.equals(d.getDedupeKey(), i.getArgument(0)) && Objects.equals(d.getUsername(), i.getArgument(1))));
        when(deliveryRepo.existsByDedupeKeyAndUsernameAndTeamId(any(), any(), any())).thenAnswer(i -> store.stream()
                .anyMatch(d -> Objects.equals(d.getDedupeKey(), i.getArgument(0)) && Objects.equals(d.getUsername(), i.getArgument(1))
                        && Objects.equals(d.getTeamId(), i.getArgument(2))));
        when(deliveryRepo.findByDedupeKeyStartingWithAndTeamIdAndStatusOrderByIdAsc(any(), any(), any())).thenAnswer(i -> store.stream()
                .filter(d -> d.getDedupeKey() != null && d.getDedupeKey().startsWith(i.getArgument(0))
                        && Objects.equals(d.getTeamId(), i.getArgument(1)) && Objects.equals(d.getStatus(), i.getArgument(2))).toList());
        // Outbox BOŞALTILMAZ (findDuePending boş) → hiçbir push isteği çıkmaz; satırlar PENDING kalır.
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────────────────────────

    static String host(long n) { return "https://h" + n + ".example.com"; }

    static Map<String, Object> ctx(long monitorId, String level) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("team_id", TEAM);
        c.put("monitor_id", monitorId);
        c.put("name", "HTTP izlemesi " + monitorId);
        if (level != null) c.put("alert_level", level);
        return c;
    }

    /** Sweep'in teyitli kesinti çağrısı — alarm yoksa açar (fırtına taklidi bağlar), varsa sürdürür. */
    void down(long n, String level) {
        service.processConfirmedOutage(host(n), TYPE, "WARNING", ctx(n, level));
    }

    AlertEvent event(long n) {
        return events.values().stream().filter(e -> e.getDomain().equals(host(n))).findFirst().orElseThrow();
    }

    List<UserPushDelivery> personRows(String trigger) {
        return store.stream().filter(d -> !UserPushService.SYSTEM_USER.equals(d.getUsername()) && trigger.equals(d.getTrigger())).toList();
    }

    List<UserPushDelivery> decisionRows() {
        return store.stream().filter(d -> UserPushService.SYSTEM_USER.equals(d.getUsername())).toList();
    }

    List<UserPushDelivery> stormNoticeRows() {
        return store.stream().filter(d -> d.getDedupeKey() != null
                && (d.getDedupeKey().startsWith("storm:") || d.getDedupeKey().startsWith("storm-resolved:"))).toList();
    }

    List<NotificationLog> stormLogs() {
        return logs.stream().filter(l -> EscalationService.TRIGGER_STORM_SUPPRESSED.equals(l.getTrigger())).toList();
    }

    void markAllSent() {
        store.forEach(d -> { if ("PENDING".equals(d.getStatus())) d.setStatus("SENT"); });
    }

    AlertStorm teamStorm() {
        AlertStorm s = new AlertStorm();
        s.setId(STORM);
        s.setScopeKey("TEAM:" + TEAM);
        s.setScopeType("TEAM");
        s.setTeamId(TEAM);
        s.setResolved(false);
        s.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        s.setLastMemberAt(ISO.format(Instant.now()));
        return s;
    }

    /** Fırtına üyesi açık alarm (motor yolu testleri için doğrudan depoya). */
    AlertEvent member(long id, String createdAt) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain(host(id));
        e.setAlertType(TYPE);
        e.setAlertLevel("WARNING");
        e.setTeamId(TEAM);
        e.setStormId(STORM);
        e.setResolved(false);
        e.setAcknowledged(false);
        e.setCreatedAt(createdAt);
        e.setMessage("UYARI: " + host(id) + " erişilemiyor");
        e.setContextJson("{\"team_id\":" + TEAM + ",\"monitor_id\":" + id + "}");
        events.put(id, e);
        return e;
    }

    static String minutesAgo(long m) { return ISO.format(Instant.now().minus(m, ChronoUnit.MINUTES)); }

    // ── AÇIK (varsayılan) — açılış ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AÇIK: fırtınaya bağlanan alarm — STORM satırı 'yalnız e-posta devredildi' der, SKIPPED_STORM yok, push bireysel OPEN; e-posta/webhook yok")
    void on_suppressed_writesMailOnlyTrace_andIndividualOpenPush() {
        down(1, null);

        assertThat(stormLogs()).singleElement().satisfies(l -> {
            assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: fırtına #7 — bireysel e-posta yerine toplu fırtına e-postası (push tek tek)");
            assertThat(l.getEmailStatus()).startsWith(EscalationService.STATUS_STORM_PREFIX);
            assertThat(l.getRecipientName()).isEqualTo("Takım A");
        });
        assertThat(store).noneMatch(d -> EscalationService.PUSH_SKIPPED_STORM.equals(d.getStatus()));
        assertThat(personRows("OPEN")).extracting(UserPushDelivery::getUsername).containsExactlyElementsOf(USERS);
        assertThat(personRows("OPEN")).allSatisfy(d -> {
            assertThat(d.getStatus()).isEqualTo("PENDING");
            assertThat(d.getDedupeKey()).isEqualTo("OPEN");
            assertThat(d.getAlertEventId()).isEqualTo(event(1).getId());
            assertThat(d.getTeamId()).isEqualTo(TEAM);
        });
        verify(emailService, never()).sendAlert(any(String[].class), any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(webhookService);
        assertThat(event(1).getLastReAlertAt()).isNotNull();
    }

    @Test
    @DisplayName("AÇIK: fırtına patlamasında N alarm → N alarmın OPEN push satırları alarmların açılış SIRASIYLA kuyruğa girer (eşik öncesi bireyseller dâhil)")
    void on_burst_pushesQueueInAlarmCreationOrder() {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(i -> {
            if (calls.incrementAndGet() <= 2) return StormService.StormAction.SEND_INDIVIDUAL;   // eşik altı → bireysel hat
            ((AlertEvent) i.getArgument(0)).setStormId(STORM);                                  // terfi + bağlanan üyeler
            return StormService.StormAction.SUPPRESSED;
        }).when(stormMock).evaluate(any(), any());

        for (long n = 1; n <= 5; n++) down(n, null);

        List<UserPushDelivery> open = personRows("OPEN");
        assertThat(open).hasSize(5 * USERS.size());
        // Satır kimliği sırası = kuyruk/teslim sırası (outbox kimlik sırasıyla boşalır) → alarm kimliği sırası.
        List<Long> eventOrder = open.stream().sorted(Comparator.comparing(UserPushDelivery::getId))
                .map(UserPushDelivery::getAlertEventId).toList();
        assertThat(eventOrder).isSorted().containsExactly(1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L);
        // E-posta: ilk ikisi bireysel; fırtınaya bağlananların e-postası devredildi (yalnız e-posta).
        verify(emailService, times(2)).sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any());
        assertThat(stormLogs()).extracting(NotificationLog::getAlertEventId).containsExactly(3L, 4L, 5L);
        assertThat(stormLogs()).allSatisfy(l -> assertThat(l.getEmailStatus()).endsWith(EscalationService.STATUS_STORM_MAIL_ONLY_SUFFIX));
        assertThat(store).noneMatch(d -> EscalationService.PUSH_SKIPPED_STORM.equals(d.getStatus()));
    }

    @Test
    @DisplayName("AÇIK: fırtına üyesinin seviye artışı → e-posta yok (fırtınada), push bireysel ESCALATION (seviye başına bir kez)")
    void on_stormMemberLevelRaise_individualEscalationPush() {
        down(1, null);
        down(1, "CRITICAL");
        down(1, "CRITICAL");   // aynı seviye ikinci tur — terfi yok, tekrar push yok

        assertThat(event(1).getAlertLevel()).isEqualTo("CRITICAL");
        assertThat(personRows("ESCALATION")).extracting(UserPushDelivery::getUsername).containsExactlyElementsOf(USERS);
        assertThat(personRows("ESCALATION")).allSatisfy(d -> assertThat(d.getDedupeKey()).isEqualTo("ESC:CRITICAL"));
        verify(emailService, never()).sendAlert(any(String[].class), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AÇIK: fırtına üyesi kurtulunca bireysel çözüm e-postası yok ama push bireysel RESOLVE (açılış push'u gittiği için simetri bulur)")
    void on_memberRecovery_individualResolvePush() {
        down(1, null);
        markAllSent();   // açılış push'u teslim edildi

        service.resolveMonitoringAlertsForDomain(host(1), TYPE);

        assertThat(event(1).getResolved()).isTrue();
        assertThat(personRows("RESOLVE")).extracting(UserPushDelivery::getUsername).containsExactlyElementsOf(USERS);
        assertThat(store).noneMatch(d -> "SKIPPED_NO_PRIOR".equals(d.getStatus()));
        verify(emailService, never()).sendResolutionAlert(any(String[].class), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("AÇIK: mühür/bağ kopması — duyurulmamış üye bireysel İLK e-postasını alır ama OPEN push İKİNCİ kez yazılmaz")
    void on_unlinkAfterSeal_noDuplicateOpenPush() {
        down(1, null);
        assertThat(personRows("OPEN")).hasSize(USERS.size());
        // StormService.resolveStorm → unlinkFromStorm: fırtına bağı ve "bildirildi" damgası silinir.
        AlertEvent e = event(1);
        e.setStormId(null);
        e.setLastReAlertAt(null);

        down(1, null);   // sonraki tur: yarıda kalmış ilk bildirim → INITIAL

        verify(emailService, times(1)).sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any());
        assertThat(personRows("OPEN")).as("OPEN olay+kişi başına bir kez").hasSize(USERS.size());
    }

    // ── AÇIK — bireysel hattın kuralları ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AÇIK: takım sessiz saatinde UYARI üyenin push'u SKIPPED_TEAM_QUIET (özet kaydı / QUIET_HOURS satırı YOK); KRİTİK gider")
    void on_teamQuietHours_skippedTeamQuiet_noDigest() {
        TeamQuietHoursService quiet = new TeamQuietHoursService(teamRepo, itemRepo);
        quiet.clock = Clock.fixed(QuietHoursTest.ist("2026-10-01T23:00:00"), ZoneOffset.UTC);
        team.setQuietStart("22:00");
        team.setQuietEnd("07:00");
        when(teamRepo.findQuietConfigured()).thenReturn(List.of(team));
        ReflectionTestUtils.setField(service, "quietHours", quiet);

        down(1, null);               // UYARI → ertelenir
        down(2, "CRITICAL");         // KRİTİK → asla ertelenmez

        assertThat(decisionRows()).singleElement().satisfies(d -> {
            assertThat(d.getAlertEventId()).isEqualTo(event(1).getId());
            assertThat(d.getStatus()).isEqualTo(EscalationService.PUSH_SKIPPED_TEAM_QUIET);
            assertThat(d.getTrigger()).isEqualTo("OPEN");
        });
        assertThat(personRows("OPEN")).extracting(UserPushDelivery::getAlertEventId).containsOnly(event(2).getId());
        verify(itemRepo, never()).saveAndFlush(any());
        assertThat(logs).noneMatch(l -> EscalationService.TRIGGER_QUIET_HOURS.equals(l.getTrigger()));
    }

    @Test
    @DisplayName("AÇIK: sistem bakımı susturması → bireysel push SKIPPED_SYSTEM_MAINTENANCE karar satırı (mevcut kural)")
    void on_systemMaintenanceMute_existingSkip() {
        SystemMaintenanceService maint = mock(SystemMaintenanceService.class);
        when(maint.notificationsMuted()).thenReturn(true);
        push.setSystemMaintenance(maint);

        down(1, null);

        assertThat(personRows("OPEN")).isEmpty();
        assertThat(decisionRows()).singleElement().satisfies(d -> {
            assertThat(d.getStatus()).isEqualTo(SystemMaintenanceService.PUSH_SKIPPED);
            assertThat(d.getTrigger()).isEqualTo("OPEN");
        });
    }

    @Test
    @DisplayName("AÇIK: kişi başına saatlik push tavanı fırtınada da geçerli — tavanı aşan alarmın push'u RATE_LIMITED")
    void on_hourlyCap_rateLimited() {
        hourlyCap = 2;

        for (long n = 1; n <= 3; n++) down(n, null);

        List<UserPushDelivery> open = personRows("OPEN");
        assertThat(open).hasSize(3 * USERS.size());
        assertThat(open).filteredOn(d -> d.getAlertEventId() <= 2L).allSatisfy(d -> assertThat(d.getStatus()).isEqualTo("PENDING"));
        assertThat(open).filteredOn(d -> d.getAlertEventId() == 3L).allSatisfy(d -> assertThat(d.getStatus()).isEqualTo("RATE_LIMITED"));
    }

    // ── AÇIK — fırtına motoru (toplu tekrar / açılış / çözüm) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("AÇIK: fırtınanın günlük tekrarı → toplu push YOK; açık + onaysız her üyeye RE_ALERT, açılış sırasıyla; aynı gün ikinci tekrar yeni satır açmaz")
    void on_stormDailyRealert_perMemberRealertInOrder_noStormNotice() {
        AlertEvent late = member(31, minutesAgo(10));
        AlertEvent early = member(32, minutesAgo(50));
        AlertEvent mid = member(33, minutesAgo(30));
        AlertEvent acked = member(34, minutesAgo(40));
        acked.setAcknowledged(true);
        AlertEvent recovered = member(35, minutesAgo(45));
        recovered.setResolved(true);
        List<AlertEvent> covered = List.of(late, early, mid, acked, recovered);

        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", teamStorm(), covered, "DAILY_REALERT");
        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", teamStorm(), covered, "DAILY_REALERT");

        assertThat(stormNoticeRows()).as("toplu fırtına push'u yok").isEmpty();
        List<UserPushDelivery> re = personRows("RE_ALERT").stream().sorted(Comparator.comparing(UserPushDelivery::getId)).toList();
        assertThat(re).extracting(UserPushDelivery::getAlertEventId).containsExactly(32L, 32L, 33L, 33L, 31L, 31L);
        assertThat(re).allSatisfy(d -> assertThat(d.getDedupeKey()).startsWith("RE_ALERT:"));
    }

    @Test
    @DisplayName("AÇIK: hatırlatma push'u kapalıysa (userpush.realert-enabled=false) fırtına tekrarı üye başına SKIPPED_REALERT_OFF bırakır")
    void on_stormDailyRealert_realertDisabled_skippedRealertOff() {
        realertEnabled = false;
        List<AlertEvent> covered = List.of(member(41, minutesAgo(20)), member(42, minutesAgo(10)));

        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", teamStorm(), covered, "DAILY_REALERT");

        assertThat(personRows("RE_ALERT")).isEmpty();
        assertThat(decisionRows()).extracting(UserPushDelivery::getAlertEventId).containsExactly(41L, 42L);
        assertThat(decisionRows()).allSatisfy(d -> assertThat(d.getStatus()).isEqualTo("SKIPPED_REALERT_OFF"));
        assertThat(stormNoticeRows()).isEmpty();
    }

    @Test
    @DisplayName("AÇIK: fırtına tekrarı takım sessiz saatinde UYARI üyeler için SKIPPED_TEAM_QUIET (bireysel hatla aynı kural)")
    void on_stormDailyRealert_teamQuiet() {
        TeamQuietHoursService quiet = new TeamQuietHoursService(teamRepo, itemRepo);
        quiet.clock = Clock.fixed(QuietHoursTest.ist("2026-10-01T23:00:00"), ZoneOffset.UTC);
        team.setQuietStart("22:00");
        team.setQuietEnd("07:00");
        when(teamRepo.findQuietConfigured()).thenReturn(List.of(team));
        engine.setQuietHours(quiet);

        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", teamStorm(), List.of(member(51, minutesAgo(5))), "DAILY_REALERT");

        assertThat(personRows("RE_ALERT")).isEmpty();
        assertThat(decisionRows()).singleElement().satisfies(d -> {
            assertThat(d.getStatus()).isEqualTo(EscalationService.PUSH_SKIPPED_TEAM_QUIET);
            assertThat(d.getTrigger()).isEqualTo("RE_ALERT");
        });
        verify(itemRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("AÇIK: fırtına AÇILIŞ ve ÇÖZÜM postaları toplu push üretmez (üyeler push'u kendi hattından alır), karar satırı da yazılmaz")
    void on_stormInitialAndResolve_noAggregatedPush() {
        AlertEvent a = member(61, minutesAgo(3));
        AlertEvent b = member(62, minutesAgo(2));

        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", teamStorm(), List.of(a, b), "INITIAL");
        b.setResolved(true);
        AlertStorm closing = teamStorm();
        closing.setResolved(true);
        closing.setResolvedAt(ISO.format(Instant.now()));
        ReflectionTestUtils.invokeMethod(engine, "sendStormRecovery", closing, List.of(b), List.of(a));

        assertThat(store).as("toplu açılış/çözüm push'u ve karar satırı yok").isEmpty();
        // E-posta tarafı bugünkü gibi: fırtına açılış ve çözüm postaları üye günlüklerine yazıldı.
        assertThat(logs).anyMatch(l -> StormService.TRIGGER_STORM_INITIAL.equals(l.getTrigger()));
        assertThat(logs).anyMatch(l -> StormService.TRIGGER_STORM_RESOLVE.equals(l.getTrigger()));
    }

    @Test
    @DisplayName("AÇIK, ayar fırtına sürerken açıldı: toplu açılış push'u önceden GİTMİŞSE toplu çözüm push'u yine gider (düştü'yü alan düzeldi'yi de alır)")
    void on_switchedMidStorm_resolveNoticeStillSymmetric() {
        // Fırtına ayar KAPALIYKEN açıldı: toplu açılış push'u gönderildi.
        pushIndividual = false;
        AlertEvent a = member(71, minutesAgo(3));
        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", teamStorm(), List.of(a), "INITIAL");
        assertThat(stormNoticeRows()).extracting(UserPushDelivery::getUsername).containsExactlyElementsOf(USERS);
        markAllSent();
        // Ayar AÇILDI; üye kurtuldu, fırtına kapanıyor.
        pushIndividual = true;
        a.setResolved(true);
        AlertStorm closing = teamStorm();
        closing.setResolved(true);
        ReflectionTestUtils.invokeMethod(engine, "sendStormRecovery", closing, List.of(a), List.<AlertEvent>of());

        assertThat(store).filteredOn(d -> ("storm-resolved:" + STORM).equals(d.getDedupeKey()))
                .extracting(UserPushDelivery::getUsername).containsExactlyElementsOf(USERS);
    }

    // ── KAPALI ≡ 2026-10-02 (bayt bayt) ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KAPALI ≡ bugün: fırtınaya bağlanan alarm eski STORM metni + SKIPPED_STORM karar satırı, kişiye push YOK; seviye artışında push YOK")
    void off_suppressed_isByteIdenticalToToday() {
        pushIndividual = false;

        down(1, null);
        down(1, "CRITICAL");

        assertThat(stormLogs()).singleElement().satisfies(l -> {
            assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: fırtına #7 — bireysel bildirim yerine toplu fırtına bildirimi");
            assertThat(l.getWebhookStatus()).isEqualTo("SKIPPED");
            assertThat(l.getRecipientEmail()).isEqualTo("takim-a@example.com");
        });
        assertThat(store).hasSize(1).singleElement().satisfies(d -> {
            assertThat(d.getUsername()).isEqualTo(UserPushService.SYSTEM_USER);
            assertThat(d.getStatus()).isEqualTo(EscalationService.PUSH_SKIPPED_STORM);
            assertThat(d.getTrigger()).isEqualTo("OPEN");
            assertThat(d.getDedupeKey()).isEqualTo("OPEN");
        });
        verify(emailService, never()).sendAlert(any(String[].class), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("KAPALI ≡ bugün: fırtına açılış / günlük tekrar / çözüm TOPLU push üretir (storm:… anahtarları); üye başına push yok")
    void off_stormEngine_aggregatedNoticesAsToday() {
        pushIndividual = false;
        AlertEvent a = member(81, minutesAgo(3));
        AlertEvent b = member(82, minutesAgo(2));

        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", teamStorm(), List.of(a, b), "INITIAL");
        markAllSent();
        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", teamStorm(), List.of(a, b), "DAILY_REALERT");
        b.setResolved(true);
        AlertStorm closing = teamStorm();
        closing.setResolved(true);
        ReflectionTestUtils.invokeMethod(engine, "sendStormRecovery", closing, List.of(b), List.of(a));

        assertThat(store).allSatisfy(d -> assertThat(d.getAlertEventId()).isNull());
        assertThat(store).extracting(UserPushDelivery::getDedupeKey).allSatisfy(k ->
                assertThat(k).matches("storm:7:INITIAL|storm:7:DAILY_REALERT:\\d{4}-\\d{2}-\\d{2}|storm-resolved:7"));
        assertThat(store).filteredOn(d -> "storm:7:INITIAL".equals(d.getDedupeKey())).hasSize(USERS.size());
        assertThat(store).filteredOn(d -> d.getDedupeKey().startsWith("storm:7:DAILY_REALERT:")).hasSize(USERS.size());
        assertThat(store).filteredOn(d -> "storm-resolved:7".equals(d.getDedupeKey())).hasSize(USERS.size());
        assertThat(store).allSatisfy(d -> assertThat(d.getMonitorType()).isEqualTo("STORM"));
        assertThat(personRows("RE_ALERT")).isEmpty();
    }
}
