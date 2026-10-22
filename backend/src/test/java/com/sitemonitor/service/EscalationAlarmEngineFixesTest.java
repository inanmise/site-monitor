package com.sitemonitor.service;

import com.sitemonitor.model.*;
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
 * Alarm motoru hata düzeltmeleri (2026-10-22, PKG-2) — EscalationService.
 *
 * <ul>
 *   <li>ALERTS-1: "Tekrar bildir" izleme türlerinde içeriği alarm anı anlık görüntüsünden / olayın iletisinden kurar;
 *       {@code buildMessage} hiçbir izleme türünde sertifika metnine düşmez; "N GÜN KALDI" yalnız bitiş türlerinde.</li>
 *   <li>ALERTS-2: bakımda algılanan değişiklik olayının ertelenmiş INITIAL'ı saklanan zengin iletiyi kullanır; anlık
 *       görüntü eski / yeni değerleri ve "bakımda algılandı" damgasını taşır.</li>
 *   <li>UIM-8 / K13: yalnız e-postalar çıkarılıp push alıcısı kaldıysa elle gönderim KABUL edilir.</li>
 *   <li>L-E4 / L-Q2: sahipsiz ya da e-postası atlanan çözüm bildirimi de günlüğe satır yazar.</li>
 *   <li>L-E5: nöbetçi adresi yalnız posta gerçekten gittiyse (SENT / kuyruk) alarma damgalanır.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationAlarmEngineFixesTest {

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

    static final long TEAM = 7L;
    static final String TEAM_MAIL = "takim-a@example.com";
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    EscalationService service;

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(smtpSettings.getOrDefaults()).thenReturn(new SmtpSettings());
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
        team.setId(TEAM); team.setName("SY-Takım A"); team.setEmail(TEAM_MAIL);
        when(teamRepo.findById(TEAM)).thenReturn(Optional.of(team));
    }

    private static String ago(long minutes) { return ISO.format(Instant.now().minus(minutes, ChronoUnit.MINUTES)); }

    private AlertEvent open(long id, String domain, String type, String level, String contextJson, String message) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setDomain(domain); e.setAlertType(type); e.setAlertLevel(level);
        e.setTeamId(TEAM); e.setResolved(false); e.setAcknowledged(false);
        e.setCreatedAt(ago(60 * 24)); e.setLastReAlertAt(ago(60));
        e.setContextJson(contextJson); e.setMessage(message);
        when(alertEventRepo.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    /** sendAlert'e giden (konu, ileti, gün, bağlam). */
    private record Sent(String subject, String message, Integer days, Map<String, Object> ctx) {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Sent sent() {
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> days = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Map> ctx = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendAlert(any(String[].class), subject.capture(), message.capture(), anyString(), anyString(),
                anyString(), days.capture(), ctx.capture());
        return new Sent(subject.getValue(), message.getValue(), days.getValue(), ctx.getValue());
    }

    // ── ALERTS-1 ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ALERTS-1: KEYWORD elle gönderim — olayın iletisi + anlık görüntü; 'sertifika' yok, gün yok, konu seviye sözcüğüyle")
    void reNotify_keyword_usesSnapshotAndStoredMessage() {
        String stored = "UYARI: https://shop.example.com/ sayfasında \"Sepete ekle\" en az 1 kez bulunmalı; koşul sağlanmıyor.";
        open(501L, "https://shop.example.com/", EscalationService.TYPE_KEYWORD, "WARNING",
                "{\"url\":\"https://shop.example.com/\",\"keyword\":\"Sepete ekle\",\"monitor_id\":9,\"team_id\":7,"
                        + "\"monitor_name\":\"Mağaza vitrini\"}", stored);

        service.reNotify(501L);

        Sent s = sent();
        assertThat(s.message()).isEqualTo(stored).doesNotContainIgnoringCase("sertifika");
        assertThat(s.subject()).startsWith("[RE-ALERT] [Site Monitor] UYARI · Mağaza vitrini").doesNotContain("GÜN KALDI");
        assertThat(s.days()).isNull();
        assertThat(s.ctx()).containsEntry("keyword", "Sepete ekle");
    }

    @Test
    @DisplayName("ALERTS-1: DOMAINMON_BLACKLIST elle gönderim — konu '245 GÜN KALDI' DEĞİL, seviye; ileti kara liste (olayın iletisi)")
    void reNotify_blacklist_noDaysInSubject() {
        String stored = "YÜKSEK: kart.example.com alan adı ya da IP'leri e-posta KARA LİSTESİNDE (1 liste). bl.spamcop.net → 1.2.3.4;";
        open(502L, "kart.example.com", EscalationService.TYPE_DOMAINMON_BLACKLIST, "HIGH", "{\"monitor_id\":55,\"team_id\":7}", stored);
        DomainMonitor mon = new DomainMonitor();
        mon.setId(55L); mon.setDomain("kart.example.com"); mon.setTeamId(TEAM);
        when(domainMonitorRepo.findFirstByDomainOrderByIdAsc("kart.example.com")).thenReturn(Optional.of(mon));
        DomainCheck dc = new DomainCheck();
        dc.setMonitorId(55L); dc.setDaysRemaining(245); dc.setRegistrar("GoDaddy");
        when(domainCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(55L)).thenReturn(Optional.of(dc));

        service.reNotify(502L);

        Sent s = sent();
        assertThat(s.subject()).contains("YÜKSEK").doesNotContain("GÜN KALDI").doesNotContain("245");
        assertThat(s.days()).isNull();
        assertThat(s.message()).contains("KARA LİSTESİNDE").doesNotContainIgnoringCase("sertifika");
        assertThat(s.ctx()).containsEntry("registrar", "GoDaddy");   // taze alan adı kaydı yine bağlamda
    }

    @Test
    @DisplayName("ALERTS-1: DOMAINMON_EXPIRY elle gönderim — süre türü: konu GÜN KALDI, ileti TAZE kalan günle")
    void reNotify_domainExpiry_keepsDays() {
        open(503L, "bitis.example.com", EscalationService.TYPE_DOMAINMON_EXPIRY, "WARNING", "{\"monitor_id\":56,\"team_id\":7}",
                "UYARI: bitis.example.com alan adının kaydı 30 gün içinde doluyor.");
        DomainMonitor mon = new DomainMonitor();
        mon.setId(56L); mon.setDomain("bitis.example.com"); mon.setTeamId(TEAM);
        when(domainMonitorRepo.findFirstByDomainOrderByIdAsc("bitis.example.com")).thenReturn(Optional.of(mon));
        DomainCheck dc = new DomainCheck();
        dc.setMonitorId(56L); dc.setDaysRemaining(12);
        when(domainCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(56L)).thenReturn(Optional.of(dc));

        service.reNotify(503L);

        Sent s = sent();
        assertThat(s.days()).isEqualTo(12);
        assertThat(s.subject()).contains("12 GÜN KALDI");
        assertThat(s.message()).contains("12 gün içinde doluyor");
    }

    @Test
    @DisplayName("ALERTS-1: PING_DOWN eski olay (bağlam + ileti yok) — buildMessage ping metni, sertifika DEĞİL")
    void reNotify_pingWithoutSnapshot_usesTypeText() {
        open(504L, "10.0.0.5", EscalationService.TYPE_PING_DOWN, "CRITICAL", null, null);

        service.reNotify(504L);

        Sent s = sent();
        assertThat(s.message()).contains("ping").doesNotContainIgnoringCase("sertifika");
        assertThat(s.subject()).contains("KRİTİK");
    }

    @Test
    @DisplayName("ALERTS-1: anlık görüntüde e-posta kanalı kapalı (mail_disabled) → elle gönderim takım postası atmaz, iz yazar")
    void reNotify_mailDisabledSnapshot_noTeamMail() {
        open(505L, "https://api.example.com/health", EscalationService.TYPE_HTTP_DOWN, "CRITICAL",
                "{\"url\":\"https://api.example.com/health\",\"mail_disabled\":true,\"team_id\":7}", "KRİTİK: https://api.example.com/health adresine HTTP isteği başarısız.");

        service.reNotify(505L);

        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
        ArgumentCaptor<NotificationLog> logs = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo, atLeastOnce()).save(logs.capture());
        assertThat(logs.getAllValues()).anySatisfy(l -> assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: e-posta kanalı kapalı"));
    }

    @Test
    @DisplayName("KAPI (ALERTS-1): buildMessage HİÇBİR izleme türünde sertifika varsayılan metnine düşmez")
    void buildMessage_neverCertificateTextForMonitoringTypes() {
        for (String type : EscalationService.MONITORING_ALERT_TYPES) {
            for (Integer days : new Integer[]{null, 245}) {
                String msg = ReflectionTestUtils.invokeMethod(service, "buildMessage", "x.example.com", type, "HIGH", days);
                assertThat(msg).as(type + " / " + days)
                        .doesNotContain("adresindeki sertifikanın süresi")
                        .doesNotContain("sertifika bilgileri alınamadı");
            }
        }
    }

    @Test
    @DisplayName("ALERTS-1: isDurationAlert izleme türlerinde YALNIZ kayıt / alan adı bitişi; sertifika kuralı aynı")
    void isDurationAlert_onlyExpiryMonitoringTypes() {
        for (String type : EscalationService.MONITORING_ALERT_TYPES) {
            assertThat(EscalationService.isDurationAlert(type)).as(type)
                    .isEqualTo(EscalationService.EXPIRY_MONITORING_TYPES.contains(type));
        }
        assertThat(EscalationService.isDurationAlert(EscalationService.TYPE_DOMAINMON_STATUS)).isFalse();
        assertThat(EscalationService.isDurationAlert(EscalationService.TYPE_DOMAINMON_TRANSFER_LOCK)).isFalse();
        assertThat(EscalationService.isDurationAlert("EXPIRY")).isTrue();
        assertThat(EscalationService.isDurationAlert(null)).isTrue();
        assertThat(EscalationService.isDurationAlert("REVOKED")).isFalse();
    }

    // ── ALERTS-2 ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ALERTS-2: bakımda açılan DNS_CHANGED olayının anlık görüntüsü kayıt tipi, eski / yeni değerler ve bakım damgasını taşır")
    void deferredChange_snapshotKeepsChangeKeys() {
        when(maintenanceService.isUnderMaintenance("dns.example.com")).thenReturn(true);
        when(alertEventRepo.findOpenAlert("dns.example.com", EscalationService.TYPE_DNS_CHANGED)).thenReturn(Optional.empty());
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("record_type", "A");
        ctx.put("old_values", List.of("1.2.3.4"));
        ctx.put("new_values", List.of("5.6.7.8"));
        ctx.put("team_id", TEAM);
        ctx.put("monitor_id", 31L);

        service.processConfirmedOutage("dns.example.com", EscalationService.TYPE_DNS_CHANGED, "HIGH", ctx);

        ArgumentCaptor<AlertEvent> saved = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo).save(saved.capture());
        String json = saved.getValue().getContextJson();
        assertThat(json).contains("\"record_type\":\"A\"").contains("5.6.7.8").contains("1.2.3.4")
                .contains(EscalationService.CTX_DETECTED_IN_MAINTENANCE);
        assertThat(saved.getValue().getMessage()).contains("Eski değer(ler): 1.2.3.4").contains("Bakım penceresinde algılandı");
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("ALERTS-2: ertelenmiş INITIAL saklanan zengin iletiyle gider ve olayın iletisi EZİLMEZ (eski anlık görüntü dahil)")
    void deferredInitial_keepsStoredRichMessage() {
        String rich = "YÜKSEK: dns.example.com için A kaydı değişti. Eski değer(ler): 1.2.3.4 → Yeni değer(ler): 5.6.7.8. "
                + "Bakım penceresinde algılandı (22.10 01:10); bildirim pencere bitince gönderildi.";
        AlertEvent e = open(601L, "dns.example.com", EscalationService.TYPE_DNS_CHANGED, "HIGH",
                "{\"team_id\":7,\"monitor_id\":31,\"standalone\":true}", rich);   // eski (anahtarsız) anlık görüntü
        e.setLastReAlertAt(null);
        e.setCreatedAt(ago(30));
        when(alertEventRepo.findOpenAlert("dns.example.com", EscalationService.TYPE_DNS_CHANGED)).thenReturn(Optional.of(e));

        service.completeDeferredInitialNotification(e);

        Sent s = sent();
        assertThat(s.message()).isEqualTo(rich);
        assertThat(e.getMessage()).isEqualTo(rich);
    }

    // ── UIM-8 / K13 ──────────────────────────────────────────────────────────────────────────────────────────

    private void pushPreview(String status, String... users) {
        List<UserPushService.PushPreviewRow> rows = new ArrayList<>();
        for (String u : users) rows.add(new UserPushService.PushPreviewRow(u, u, status));
        when(userPushService.preview(anyLong(), any(), anyList())).thenReturn(new UserPushService.PushPreview(true, null, rows));
    }

    @Test
    @DisplayName("UIM-8: tüm e-postalar çıkarıldı ama push alıcısı kaldı → KABUL (kuyruğa alındı), e-posta gitmez, push tetiklenir")
    void reNotify_pushOnly_isAccepted() {
        open(701L, "push.example.com", EscalationService.TYPE_HTTP_DOWN, "CRITICAL", "{\"team_id\":7}", "KRİTİK: push.example.com düştü.");
        pushPreview("PENDING", "ali.veli");

        Map<String, Object> result = service.reNotify(701L, Set.of(TEAM_MAIL), Set.of());

        assertThat(result.get("status")).isEqualTo("queued");
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
        verify(alertEventRepo, atLeastOnce()).save(any());
    }

    @Test
    @DisplayName("UIM-8: e-postalar VE push alıcıları çıkarıldı → 400 metni (Türkçe varsayılan), yazma yok")
    void reNotify_everythingExcluded_rejected() {
        open(702L, "push.example.com", EscalationService.TYPE_HTTP_DOWN, "CRITICAL", "{\"team_id\":7}", "KRİTİK: düştü.");
        pushPreview("PENDING", "ali.veli");

        assertThatThrownBy(() -> service.reNotify(702L, Set.of(TEAM_MAIL), Set.of("ali.veli")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("e-posta ya da push alıcısı");
        verify(alertEventRepo, never()).save(any());
    }

    @Test
    @DisplayName("UIM-8: push önizlemesinde gidecek kimse yoksa (atlanmış satırlar) ret sürer")
    void reNotify_pushRowsNotPending_rejected() {
        open(703L, "push.example.com", EscalationService.TYPE_HTTP_DOWN, "CRITICAL", "{\"team_id\":7}", "KRİTİK: düştü.");
        pushPreview("SKIPPED_USER_LEVEL", "ali.veli");

        assertThatThrownBy(() -> service.reNotify(703L, Set.of(TEAM_MAIL), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── L-E4 / L-Q2 ──────────────────────────────────────────────────────────────────────────────────────────

    private AlertEvent resolvable(String type, Long teamId, String contextJson) {
        AlertEvent e = new AlertEvent();
        e.setId(77L); e.setDomain("izleme.example.com"); e.setAlertType(type); e.setAlertLevel("CRITICAL");
        e.setTeamId(teamId); e.setContextJson(contextJson); e.setCreatedAt(ago(5));
        e.setResolved(true); e.setResolvedAt(ago(0));
        return e;
    }

    private List<NotificationLog> logs() {
        ArgumentCaptor<NotificationLog> cap = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo, atLeastOnce()).save(cap.capture());
        return cap.getAllValues();
    }

    @Test
    @DisplayName("L-E4: sahipsiz alarmın çözümü hiçbir kanaldan gitmez AMA 'SKIPPED: takım yok' satırı yazar")
    void resolution_ownerless_leavesTrace() {
        AlertEvent e = resolvable(EscalationService.TYPE_KEYWORD, null, null);

        service.sendResolutionNotificationAsync(e, "Sistem", "RESOLUTION");

        verify(emailService, never()).sendResolutionAlert(any(String[].class), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(logs()).anySatisfy(l -> {
            assertThat(l.getEmailStatus()).isEqualTo(EscalationService.STATUS_NO_TEAM);
            assertThat(l.getTrigger()).isEqualTo("RESOLUTION");
            assertThat(l.getSubject()).contains("ÇÖZÜLDÜ");
        });
    }

    @Test
    @DisplayName("L-Q2: izlemede e-posta kapalı → çözüm postası atlanır ve 'SKIPPED: e-posta kanalı kapalı' satırı yazar")
    void resolution_mailDisabled_leavesTrace() {
        AlertEvent e = resolvable(EscalationService.TYPE_KEYWORD, TEAM, "{\"mail_disabled\":true,\"team_id\":7}");

        service.sendResolutionNotificationAsync(e, "Sistem", "RESOLUTION");

        assertThat(logs()).anySatisfy(l -> assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: e-posta kanalı kapalı"));
    }

    @Test
    @DisplayName("L-Q2: alıcı yok → 'SKIPPED: alıcı yok' satırı")
    void resolution_noRecipients_leavesTrace() {
        when(teamRepo.findById(TEAM)).thenReturn(Optional.empty());
        AlertEvent e = resolvable(EscalationService.TYPE_KEYWORD, TEAM, "{\"team_id\":7}");

        service.sendResolutionNotificationAsync(e, "Sistem", "RESOLUTION");

        assertThat(logs()).anySatisfy(l -> assertThat(l.getEmailStatus()).isEqualTo("SKIPPED: alıcı yok"));
    }

    // ── L-E5 ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("L-E5: posta BAŞARISIZ → nöbetçi adresi alarma yazılmaz; SENT / kuyruk → yazılır")
    void oncallStampOnlyWhenDelivered() {
        OnCallService onCall = mock(OnCallService.class);
        ReflectionTestUtils.setField(service, "onCallService", onCall);
        when(onCall.notifyTargets(anyCollection())).thenReturn(Map.of(TEAM,
                new OnCallService.OnCallPerson(TEAM, 55L, "N55555", "Nöbetçi", "nobetci@example.com", true, false, null)));
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("team_id", TEAM);
        ctx.put("alert_level", "CRITICAL");
        when(alertEventRepo.findOpenAlert(anyString(), anyString())).thenReturn(Optional.empty());
        when(stormService.evaluate(any(), any())).thenReturn(StormService.StormAction.SEND_INDIVIDUAL);

        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("FAILED: 550 mailbox unavailable");
        service.processConfirmedOutage("https://a.example.com/", EscalationService.TYPE_HTTP_DOWN, "CRITICAL", ctx);
        verify(alertEventRepo, never()).setOncallEmails(anyLong(), anyString());

        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("QUEUED_RETRY: 421");
        service.processConfirmedOutage("https://b.example.com/", EscalationService.TYPE_HTTP_DOWN, "CRITICAL", ctx);
        verify(alertEventRepo).setOncallEmails(anyLong(), eq("nobetci@example.com"));

        assertThat(EscalationService.mailDelivered("SENT")).isTrue();
        assertThat(EscalationService.mailDelivered("QUEUED_RETRY")).isTrue();
        assertThat(EscalationService.mailDelivered("SKIPPED: pasif kullanıcı")).isFalse();
        assertThat(EscalationService.mailDelivered(null)).isFalse();
    }

    // ── Son inceleme 2026-10-22 · C2 / (c) ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C2: alarm anında e-posta kanalı kapalı → önizleme e-posta alıcısı LİSTELEMEZ, sayı 0 — gönderimle aynı (önizleme = gönderim)")
    void reNotify_mailDisabledSnapshot_previewEqualsSend() {
        open(801L, "https://api.example.com/health", EscalationService.TYPE_HTTP_DOWN, "CRITICAL",
                "{\"url\":\"https://api.example.com/health\",\"mail_disabled\":true,\"team_id\":7}", "KRİTİK: düştü.");

        assertThat(service.reNotifyMailDisabled(801L)).isTrue();
        assertThat(service.previewReNotify(801L)).isEmpty();
        Map<String, Object> result = service.reNotify(801L);
        assertThat(result.get("recipients_queued")).isEqualTo(0);
        verify(emailService, never()).sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("C2 regresyon: e-posta kanalı açık → önizleme takım adresini listeler, sayı 1, posta gider")
    void reNotify_mailEnabled_previewListsTeamAndSends() {
        open(802L, "https://api.example.com/health", EscalationService.TYPE_HTTP_DOWN, "CRITICAL",
                "{\"url\":\"https://api.example.com/health\",\"team_id\":7}", "KRİTİK: düştü.");

        assertThat(service.reNotifyMailDisabled(802L)).isFalse();
        assertThat(service.previewReNotify(802L)).extracting(EscalationService.ReNotifyRecipient::email).containsExactly(TEAM_MAIL);
        assertThat(service.reNotify(802L).get("recipients_queued")).isEqualTo(1);
        verify(emailService).sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("C2: sertifika alarmının bağlamı latest_checks'ten gelir — eski anlık görüntüdeki mail_disabled önizlemeyi kapatmaz")
    void reNotify_certAlarm_ignoresSnapshotMailFlag() {
        AlertEvent e = open(803L, "cert.example.com", "EXPIRY", "HIGH", "{\"mail_disabled\":true}", "YÜKSEK: sertifika doluyor.");
        e.setTeamId(null);
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("cert.example.com"); inv.setTeamId(TEAM); inv.setActive(true);
        when(inventoryRepo.findByDomain("cert.example.com")).thenReturn(Optional.of(inv));

        assertThat(service.reNotifyMailDisabled(803L)).isFalse();
        assertThat(service.previewReNotify(803L)).extracting(EscalationService.ReNotifyRecipient::email).contains(TEAM_MAIL);
    }

    @Test
    @DisplayName("(c): yalnız push elle gönderim — e-posta satırı 'alıcı yok' DEĞİL, bilinçli seçim olarak yazılır")
    void reNotify_pushOnly_logsDeselectedNotNoRecipient() {
        open(804L, "push.example.com", EscalationService.TYPE_HTTP_DOWN, "CRITICAL", "{\"team_id\":7}", "KRİTİK: push.example.com düştü.");
        pushPreview("PENDING", "ali.veli");

        service.reNotify(804L, Set.of(TEAM_MAIL), Set.of());

        ArgumentCaptor<NotificationLog> logs = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo, atLeastOnce()).save(logs.capture());
        assertThat(logs.getAllValues()).extracting(NotificationLog::getEmailStatus)
                .contains(EscalationService.STATUS_SKIPPED_EMAIL_DESELECTED)
                .doesNotContain("SKIPPED: alıcı yok");
    }
}
