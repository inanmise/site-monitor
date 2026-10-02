package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.QuietDigestItem;
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
 * Ürün güvencesi (2026-10-01, onaylı öneri 15): "Tanımlanmadıkça bildirim zamanı değişmez. Kritik alarmlar sessiz saatte de
 * gider, ertelenen her bildirim kayda geçer."
 *
 * <ul>
 *   <li>Pencere tanımsız (servis yok / ayar yok / pencere dışı): ilk bildirim, seviye artışı, günlük hatırlatma, elle
 *       gönderim ve çözümde alıcılar, push bağlamı ve {@code notification_logs} satırları BİREBİR aynı; özet tablosuna
 *       dokunulmaz.</li>
 *   <li>Pencere içinde UYARI ertelenir: e-posta/webhook gitmez, QUIET_HOURS izi + özet kaydı + push kararı (işaretli ctx).</li>
 *   <li>YÜKSEK/KRİTİK pencerede de hemen gider; elle yeniden gönderim ertelenmez.</li>
 *   <li>Açılışı ertelenmiş alarmın çözümü ayrı posta yerine özete katlanır; açılışı duyulmuş olanınki normal gider.</li>
 *   <li>SY/UG'den yalnız biri pencerede: o takım ertelenir, diğeri bugünkü gibi alır.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuietHoursEscalationTest {

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
    @Mock QuietDigestItemRepository itemRepo;

    static final long TEAM = 42L;
    static final long UG = 77L;
    static final String DOMAIN = "shop.example.com";
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    /** 2026-10-01 23:00 Istanbul (Perşembe) — 22:00–07:00 penceresinin içi. */
    static final Instant NIGHT = QuietHoursTest.ist("2026-10-01T23:00:00");
    /** 2026-10-01 12:00 Istanbul — pencere dışı. */
    static final Instant NOON = QuietHoursTest.ist("2026-10-01T12:00:00");

    EscalationService service;
    TeamQuietHoursService quiet;
    final List<NotificationLog> rows = new ArrayList<>();
    final Map<Long, AlertEvent> events = new HashMap<>();
    final List<QuietDigestItem> items = new ArrayList<>();
    Team team;

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);
        quiet = new TeamQuietHoursService(teamRepo, itemRepo);
        quiet.clock = Clock.fixed(NIGHT, ZoneOffset.UTC);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(smtpSettings.getOrDefaults()).thenReturn(new com.sitemonitor.model.SmtpSettings());
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());
        team = new Team();
        team.setId(TEAM);
        team.setName("Ödeme");
        team.setEmail("team@x.com");
        when(teamRepo.findById(TEAM)).thenReturn(Optional.of(team));
        when(teamRepo.findQuietConfigured()).thenReturn(List.of());
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
        stubMail();
        when(notificationLogRepo.save(any())).thenAnswer(i -> { rows.add(i.getArgument(0)); return i.getArgument(0); });
        when(itemRepo.saveAndFlush(any())).thenAnswer(i -> { items.add(i.getArgument(0)); return i.getArgument(0); });
        when(itemRepo.findPendingByAlertEventId(anyLong())).thenReturn(List.of());
        givenContacts(contact(1, "a@x.com", true), contact(2, "b@x.com", false));
    }

    private void stubMail() {
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn("SENT");
        when(emailService.buildAlertEmailHtml(anyString(), anyString(), any(), any(), any(), any(), any())).thenReturn("<html/>");
        when(emailService.sendResolutionAlert(any(String[].class), anyString(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any())).thenReturn("SENT");
    }

    /** Takıma pencere tanımlar (önbellek düşürülür — kayıt ucunun davranışı). */
    private void quietHours(String start, String end, String days, String minLevel) {
        team.setQuietStart(start);
        team.setQuietEnd(end);
        team.setQuietDays(days);
        team.setQuietMinLevel(minLevel);
        when(teamRepo.findQuietConfigured()).thenReturn(List.of(team));
        quiet.invalidate();
    }

    private void enableQuietService() {
        ReflectionTestUtils.setField(service, "quietHours", quiet);
    }

    static EscalationContact contact(long id, String email, boolean webhook) {
        EscalationContact c = new EscalationContact();
        c.setId(id);
        c.setTeamId(TEAM);
        c.setName("Kişi " + id);
        c.setEmail(email);
        c.setRole("MANAGER");
        c.setMinAlertLevel("WARNING");
        c.setActive(true);
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

    private static String fp(NotificationLog l) {
        return l.getTrigger() + "|" + l.getRecipientRole() + "|" + l.getRecipientName() + "|" + l.getRecipientEmail()
                + "|" + l.getSubject() + "|" + l.getEmailStatus() + "|" + l.getWebhookStatus();
    }

    private List<String[]> mailRecipients() {
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService, atLeast(0)).sendAlert(to.capture(), anyString(), anyString(), any(), any(), any(), any(), any());
        return to.getAllValues();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> pushContexts() {
        ArgumentCaptor<Map<String, Object>> c = ArgumentCaptor.forClass(Map.class);
        verify(userPushService, atLeast(0)).enqueueAlert(any(), anyString(), any(), c.capture(), any());
        return c.getAllValues();
    }

    /** İlk → seviye artışı → hatırlatma → elle gönderim → çözüm; satır + alıcı + push-bağlam parmak izleri. */
    private List<String> runAllPaths(String level) {
        when(alertEventRepo.findOpenAlert(DOMAIN, "ACCESSIBILITY")).thenReturn(Optional.empty());
        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", level, ctx());               // INITIAL
        AlertEvent e = openEvent(level, 1);
        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "CRITICAL", ctx());          // ESCALATION
        e.setLastReAlertAt(ISO.format(Instant.now().minus(25, ChronoUnit.HOURS)));
        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "CRITICAL", ctx());          // DAILY_REALERT
        service.reNotify(7L, Set.of());                                                    // MANUAL
        e.setResolved(true);
        e.setResolvedAt(ISO.format(Instant.now()));
        service.sendResolutionNotificationAsync(e, "auto", "RESOLUTION");                   // RESOLUTION
        List<String> out = new ArrayList<>();
        for (NotificationLog l : rows) out.add(fp(l));
        for (String[] to : mailRecipients()) out.add("TO:" + String.join(",", to));
        // Bağlamın ANAHTARLARI (değerlerde zaman damgası var) — sessiz saat işareti yokluğu böylece kanıtlanır.
        for (Map<String, Object> c : pushContexts()) out.add("PUSHCTX:" + (c == null ? "null" : new TreeSet<>(c.keySet()).toString()));
        return out;
    }

    private void resetRun() {
        rows.clear();
        events.clear();
        items.clear();
        reset(emailService, webhookService, userPushService);
        stubMail();
    }

    // ── tanımsız = bugünkü davranış ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Tanımsız: servis yok ≡ servis var ama ayar yok ≡ ayar var ama pencere dışı — tüm yollarda satır/alıcı/push BİREBİR aynı")
    void noConfig_everyPath_identical() {
        List<String> without = runAllPaths("WARNING");

        resetRun();
        enableQuietService();                                  // servis var, hiçbir takımda ayar yok
        List<String> withServiceNoConfig = runAllPaths("WARNING");
        verifyNoInteractions(itemRepo);                        // ayar yok → özet tablosuna tek sorgu bile yok

        resetRun();
        quietHours("22:00", "07:00", null, "CRITICAL");        // en geniş ayar — ama şu an öğlen
        quiet.clock = Clock.fixed(NOON, ZoneOffset.UTC);
        List<String> outsideWindow = runAllPaths("WARNING");

        assertThat(without).isNotEmpty()
                .anyMatch(s -> s.startsWith("INITIAL|COMBINED|"))
                .anyMatch(s -> s.startsWith("ESCALATION|COMBINED|"))
                .anyMatch(s -> s.startsWith("DAILY_REALERT|COMBINED|"))
                .anyMatch(s -> s.startsWith("MANUAL|COMBINED|"))
                .anyMatch(s -> s.startsWith("RESOLUTION|COMBINED|"))
                .noneMatch(s -> s.contains("QUIET"));
        assertThat(withServiceNoConfig).containsExactlyElementsOf(without);
        assertThat(outsideWindow).containsExactlyElementsOf(without);
        verify(itemRepo, never()).saveAndFlush(any());
        verify(itemRepo, never()).supersede(anyLong(), anyCollection(), anyString());
    }

    @Test
    @DisplayName("Tanımsız: ayar önbelleği dakikada en çok BİR sorgu — alarm başına sorgu yok")
    void noConfig_cacheQueriesOncePerTtl() {
        enableQuietService();
        runAllPaths("WARNING");
        verify(teamRepo, times(1)).findQuietConfigured();
    }

    // ── pencere içinde UYARI ertelenir ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Pencerede UYARI: e-posta ve webhook GİTMEZ; QUIET_HOURS izi + özet kaydı (açılış) + işaretli push bağlamı")
    void warning_inWindow_deferredWithTrace() {
        enableQuietService();
        quietHours("22:00", "07:00", null, null);
        when(alertEventRepo.findOpenAlert(DOMAIN, "ACCESSIBILITY")).thenReturn(Optional.empty());

        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "WARNING", ctx());

        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any());
        verifyNoInteractions(webhookService);
        assertThat(rows).singleElement().satisfies(l -> {
            assertThat(l.getTrigger()).isEqualTo(EscalationService.TRIGGER_QUIET_HOURS);
            assertThat(l.getEmailStatus()).isEqualTo(EscalationService.STATUS_QUIET_DEFERRED);
            assertThat(l.getRecipientName()).isEqualTo("Ödeme");
            assertThat(l.getRecipientEmail()).isEqualTo("team@x.com, a@x.com, b@x.com");
            assertThat(l.getMessage()).contains("22:00–07:00").contains("İlk bildirim");
        });
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.getTeamId()).isEqualTo(TEAM);
            assertThat(i.getWindowKey()).isEqualTo("2026-10-01T22:00");
            assertThat(i.getWindowEnd()).isEqualTo("2026-10-02T04:00:00");   // 07:00 IST = 04:00 UTC
            assertThat(i.getOpeningDeferred()).isTrue();
            assertThat(i.getFirstTrigger()).isEqualTo("INITIAL");
        });
        assertThat(pushContexts()).singleElement()
                .satisfies(c -> assertThat(c).containsEntry(EscalationService.CTX_QUIET_DEFERRED, true));
        // Çağıran damgası sürer: olay "bildirildi" sayılır (yarım ilk bildirim sanılıp tekrar gönderilmez).
        assertThat(events.values().iterator().next().getLastReAlertAt()).isNotNull();
    }

    @Test
    @DisplayName("Pencerede günlük hatırlatma (UYARI) da ertelenir — her erteleme ayrı iz satırı bırakır, özet kaydı pencere başına tek")
    void warning_realert_inWindow_deferred() {
        enableQuietService();
        quietHours("22:00", "07:00", null, null);
        AlertEvent e = openEvent("WARNING", 25);
        // ikinci ekleme UNIQUE kısıta çarpar (aynı alarm, aynı takım, aynı pencere)
        doAnswer(i -> { items.add(i.getArgument(0)); return i.getArgument(0); })
                .doThrow(new org.springframework.dao.DataIntegrityViolationException("dup"))
                .when(itemRepo).saveAndFlush(any());

        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "WARNING", ctx());
        e.setLastReAlertAt(ISO.format(Instant.now().minus(25, ChronoUnit.HOURS)));
        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "WARNING", ctx());

        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), any(), any(), any(), any(), any());
        assertThat(rows).hasSize(2).allSatisfy(l -> assertThat(l.getEmailStatus()).isEqualTo(EscalationService.STATUS_QUIET_DEFERRED));
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.getOpeningDeferred()).isFalse();
            assertThat(i.getFirstTrigger()).isEqualTo("DAILY_REALERT");
        });
    }

    @Test
    @DisplayName("Pencerede YÜKSEK ve KRİTİK hemen gider (bugünkü satırlar); ertelenmez, özet kaydı yok")
    void highAndCritical_inWindow_sentImmediately() {
        enableQuietService();
        quietHours("22:00", "07:00", null, null);
        when(alertEventRepo.findOpenAlert(DOMAIN, "ACCESSIBILITY")).thenReturn(Optional.empty());

        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "HIGH", ctx());
        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "CRITICAL", ctx());   // açık olay yok (stub) → ikinci açılış

        assertThat(mailRecipients()).hasSize(2);
        assertThat(rows).extracting(NotificationLog::getTrigger).containsOnly("INITIAL");
        verify(itemRepo, never()).saveAndFlush(any());
        assertThat(pushContexts()).allSatisfy(c -> assertThat(c).doesNotContainKey(EscalationService.CTX_QUIET_DEFERRED));
    }

    @Test
    @DisplayName("quiet_min_level=CRITICAL: YÜKSEK de ertelenir; KRİTİK yine hemen gider")
    void minLevelCritical_defersHigh_notCritical() {
        enableQuietService();
        quietHours("22:00", "07:00", null, "CRITICAL");
        when(alertEventRepo.findOpenAlert(anyString(), eq("ACCESSIBILITY"))).thenReturn(Optional.empty());

        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "HIGH", ctx());
        assertThat(mailRecipients()).isEmpty();
        assertThat(rows).singleElement().extracting(NotificationLog::getTrigger).isEqualTo(EscalationService.TRIGGER_QUIET_HOURS);

        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "CRITICAL", ctx());
        assertThat(mailRecipients()).hasSize(1);
    }

    @Test
    @DisplayName("Elle yeniden gönderim pencerede de ERTELENMEZ (bilinçli eylem) — ve bekleyen özet kaydını geçersizleştirir")
    void manualResend_notDeferred_supersedes() {
        enableQuietService();
        quietHours("22:00", "07:00", null, null);
        openEvent("WARNING", 1);

        service.reNotify(7L, Set.of());

        assertThat(mailRecipients()).singleElement()
                .satisfies(to -> assertThat(to).containsExactly("team@x.com", "a@x.com", "b@x.com"));
        assertThat(rows).extracting(NotificationLog::getTrigger).contains("MANUAL").doesNotContain(EscalationService.TRIGGER_QUIET_HOURS);
        verify(itemRepo, never()).saveAndFlush(any());
        verify(itemRepo).supersede(eq(7L), eq(List.of(TEAM)), anyString());
    }

    @Test
    @DisplayName("Pencere bitti (07:00) → aynı UYARI hemen gider")
    void windowOver_sendsNormally() {
        enableQuietService();
        quietHours("22:00", "07:00", null, null);
        quiet.clock = Clock.fixed(QuietHoursTest.ist("2026-10-02T07:00:00"), ZoneOffset.UTC);
        when(alertEventRepo.findOpenAlert(DOMAIN, "ACCESSIBILITY")).thenReturn(Optional.empty());

        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "WARNING", ctx());

        assertThat(mailRecipients()).hasSize(1);
        verify(itemRepo, never()).saveAndFlush(any());
    }

    // ── çözüm ─────────────────────────────────────────────────────────────────────────────────────

    private QuietDigestItem pending(long teamId, boolean openingDeferred) {
        QuietDigestItem i = new QuietDigestItem();
        i.setId(500L + teamId);
        i.setAlertEventId(7L);
        i.setTeamId(teamId);
        i.setWindowKey("2026-10-01T22:00");
        i.setOpeningDeferred(openingDeferred);
        return i;
    }

    @Test
    @DisplayName("Açılışı ertelenmiş alarm pencerede çözüldü: ayrı 'çözüldü' postası/webhook GİTMEZ, çözüm özete katlanır (iz satırı)")
    void resolution_openingDeferred_foldedIntoDigest() {
        enableQuietService();
        quietHours("22:00", "07:00", null, null);
        AlertEvent e = openEvent("WARNING", 1);
        e.setResolved(true);
        e.setResolvedAt(ISO.format(Instant.now()));
        when(itemRepo.findPendingByAlertEventId(7L)).thenReturn(List.of(pending(TEAM, true)));

        service.sendResolutionNotificationAsync(e, "auto", "RESOLUTION");

        verify(emailService, never()).sendResolutionAlert(any(String[].class), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        verifyNoInteractions(webhookService);
        assertThat(rows).singleElement().satisfies(l -> {
            assertThat(l.getTrigger()).isEqualTo(EscalationService.TRIGGER_QUIET_HOURS);
            assertThat(l.getEmailStatus()).isEqualTo(EscalationService.STATUS_QUIET_RESOLUTION_FOLDED);
        });
        verify(userPushService).enqueueResolve(eq(e), any(), eq(TEAM));   // push simetrisi kendi kararını yazar
    }

    @Test
    @DisplayName("Açılışı DUYULMUŞ alarmın (yalnız hatırlatması ertelenmiş) çözümü bugünkü gibi gider ve özet kaydı geçersizleşir")
    void resolution_notOpeningDeferred_sentNormally() {
        enableQuietService();
        quietHours("22:00", "07:00", null, null);
        AlertEvent e = openEvent("WARNING", 1);
        e.setResolved(true);
        e.setResolvedAt(ISO.format(Instant.now()));
        when(itemRepo.findPendingByAlertEventId(7L)).thenReturn(List.of(pending(TEAM, false)));

        service.sendResolutionNotificationAsync(e, "auto", "RESOLUTION");

        verify(emailService).sendResolutionAlert(any(String[].class), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any());
        assertThat(rows).extracting(NotificationLog::getTrigger).contains("RESOLUTION")
                .doesNotContain(EscalationService.TRIGGER_QUIET_HOURS);
        verify(itemRepo).supersede(eq(7L), eq(Set.of(TEAM)), anyString());
    }

    // ── SY + UG ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kısmi: yalnız SY pencerede → SY adresleri/kişileri ertelenir (iz + kayıt), UG bugünkü gibi ŞİMDİ alır")
    void partial_onlySyQuiet_ugReceivesNow() {
        enableQuietService();
        quietHours("22:00", "07:00", null, null);
        Team ug = new Team();
        ug.setId(UG);
        ug.setName("Geliştirme");
        ug.setEmail("ug@x.com");
        when(teamRepo.findById(UG)).thenReturn(Optional.of(ug));
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(DOMAIN);
        inv.setTeamId(TEAM);
        inv.setUgTeamId(UG);
        when(inventoryRepo.findByDomain(DOMAIN)).thenReturn(Optional.of(inv));
        when(alertEventRepo.findOpenAlert(DOMAIN, "ACCESSIBILITY")).thenReturn(Optional.empty());

        service.processConfirmedOutage(DOMAIN, "ACCESSIBILITY", "WARNING", ctx());

        assertThat(mailRecipients()).singleElement().satisfies(to -> assertThat(to).containsExactly("ug@x.com"));
        verifyNoInteractions(webhookService);   // SY kişisinin webhook'u da ertelendi
        assertThat(rows).extracting(NotificationLog::getTrigger)
                .containsExactly(EscalationService.TRIGGER_QUIET_HOURS, "INITIAL");
        assertThat(rows.get(1).getRecipientName()).isEqualTo("Geliştirme");
        assertThat(items).singleElement().extracting(QuietDigestItem::getTeamId).isEqualTo(TEAM);
        // push SY üyelerinden çözülür (olayın takımı) → SY penceredeyse push da ertelenir
        assertThat(pushContexts()).singleElement()
                .satisfies(c -> assertThat(c).containsEntry(EscalationService.CTX_QUIET_DEFERRED, true));
    }
}
