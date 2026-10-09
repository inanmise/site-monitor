package com.sitemonitor.service.noc;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.NocDelivery;
import com.sitemonitor.model.NocNotificationGroup;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.NocDeliveryRepository;
import com.sitemonitor.repository.NocNotificationGroupRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.MaintenanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 7/24 GÖNDERİM KURALLARI (sözleşme §Gönderim): en düşük seviye (DOWN = KRİTİK), bakım, tür anahtarı, izleme
 * anahtarı, tetik türü, alarm başına TEK açılış, yalnız açılış gittiyse ÇÖZÜLDÜ, fırtınada TEK toplu e-posta,
 * global e-posta kapatma ve takımın kendi e-posta kanalından BAĞIMSIZLIK.
 */
class NocNotificationServiceTest {

    private final NocConfigService config = mock(NocConfigService.class);
    private final NocNotificationGroupRepository groupRepo = mock(NocNotificationGroupRepository.class);
    private final NocCallListService callLists = mock(NocCallListService.class);
    private final NocMonitorDirectory directory = mock(NocMonitorDirectory.class);
    private final NocDeliveryRepository deliveries = mock(NocDeliveryRepository.class);
    private final EmailNotificationService email = mock(EmailNotificationService.class);
    private final NotificationLogRepository logs = mock(NotificationLogRepository.class);
    private final MaintenanceService maintenance = mock(MaintenanceService.class);
    private final ActivityLogService activity = mock(ActivityLogService.class);
    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    /** Fırtına değerlendirmesinin TEK anlık görüntüsü (üye başına sorgu yok). */
    private final NocMonitorDirectory.Snapshot snap = mock(NocMonitorDirectory.Snapshot.class);

    private NocNotificationService svc;
    private final Map<String, NocDelivery> store = new LinkedHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private String mailStatus = "SENT";

    private static final NocNotificationGroup ANA = group(1, "NOC Ana", true, true, "noc@example.com, yedek@example.com");
    private static final NocNotificationGroup GECE = group(2, "NOC Gece", true, false, "gece@example.com");

    @BeforeEach
    void setUp() {
        svc = new NocNotificationService(config, new NocGroupService(groupRepo, null), callLists, directory, deliveries,
                email, logs, maintenance, activity, appSettings);
        config("CRITICAL", true, Set.of());
        when(groupRepo.findAllByOrderByNameAsc()).thenReturn(List.of(ANA, GECE));
        when(callLists.forMail(any())).thenReturn(new NocMailComposer.TeamBlock("Takım A",
                List.of(new NocMailComposer.Person("Kişi A", "Uzman", "+90 555 000 00 00")), true, null, List.of()));
        when(appSettings.getString(anyString(), any())).thenReturn("https://sitemonitor.example.com");
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), anyString(), any(), anyBoolean(), any()))
                .thenAnswer(i -> mailStatus);
        when(email.getEmailFrom()).thenReturn("noreply@example.com");
        // Teslim izi deposu bellekte — tekil anahtar davranışı (saveAndFlush çakışması) gerçekçi.
        when(deliveries.findByDedupeKey(anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.<String>getArgument(0))));
        when(deliveries.findByDedupeKeyIn(anyCollection())).thenAnswer(i -> {
            List<NocDelivery> out = new ArrayList<>();
            for (Object k : i.<Collection<?>>getArgument(0)) if (store.containsKey(k)) out.add(store.get(k));
            return out;
        });
        when(deliveries.saveAndFlush(any())).thenAnswer(i -> put(i.getArgument(0)));
        when(deliveries.save(any())).thenAnswer(i -> put(i.getArgument(0)));
        when(deliveries.findTopByStormIdAndPhaseOrderByIdDesc(any(), anyString())).thenAnswer(i -> store.values().stream()
                .filter(d -> Objects.equals(d.getStormId(), i.getArgument(0)) && Objects.equals(d.getPhase(), i.getArgument(1)))
                .max(Comparator.comparing(NocDelivery::getId)));
        when(directory.snapshot()).thenReturn(snap);
    }

    private NocDelivery put(NocDelivery d) {
        if (d.getId() == null) d.setId(seq.incrementAndGet());
        store.put(d.getDedupeKey(), d);
        return d;
    }

    private void config(String min, boolean sendResolve, Set<NocType> disabled) {
        when(config.get()).thenReturn(new NocConfigService.Config(disabled, min, sendResolve, "Sırayla arayın.", null, null));
    }

    private static NocNotificationGroup group(long id, String name, boolean active, boolean def, String emails) {
        NocNotificationGroup g = new NocNotificationGroup();
        g.setId(id); g.setName(name); g.setActive(active); g.setIsDefault(def); g.setEmails(emails);
        return g;
    }

    private static AlertEvent alert(long id, String type, String level, String domain) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setAlertType(type); e.setAlertLevel(level); e.setDomain(domain); e.setTeamId(10L);
        e.setCreatedAt("2026-01-10T21:05:00");
        e.setContextJson("{\"monitor_id\":" + id + "}");
        return e;
    }

    private NocMonitorDirectory.Row monitor(NocType t, long id, boolean noc, boolean active, String groups) {
        NocMonitorDirectory.Row r = new NocMonitorDirectory.Row(t, id, "izleme-" + id, "h" + id + ".example.com", 10L, null,
                active, noc, groups, false);
        when(directory.forAlert(eq(t), any(), eq((Object) id))).thenReturn(r);
        when(directory.forAlert(eq(t), any(), eq((Object) Long.valueOf(id)))).thenReturn(r);
        when(directory.find(t, id)).thenReturn(r);
        when(snap.forAlert(eq(t), any(), eq((Object) Long.valueOf(id)))).thenReturn(r);
        return r;
    }

    private void dispatch(AlertEvent e, String trigger) {
        svc.onAlertDispatched(e, 10L, e.getDomain(), e.getAlertLevel(), e.getAlertType(), trigger, Map.of("monitor_id", e.getId()));
    }

    private int mailsSent() {
        return mockingDetails(email).getInvocations().stream().filter(i -> i.getMethod().getName().equals("sendHtml")).toList().size();
    }

    private String[] lastRecipients() {
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(email, atLeastOnce()).sendHtml(to.capture(), any(), anyString(), anyString(), anyString(), any(), anyBoolean(), any());
        return to.getValue();
    }

    // ── Açılış ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("KRİTİK kesinti: varsayılan gruba tek e-posta; posta günlüğü NOC; etkinlik kaydı; teslim izi SENT")
    void opensToDefaultGroup() {
        AlertEvent e = alert(1, "PING_DOWN", "WARNING", "h1.example.com");
        monitor(NocType.PING, 1, true, true, null);
        dispatch(e, "INITIAL");

        assertThat(mailsSent()).isEqualTo(1);
        assertThat(lastRecipients()).containsExactly("noc@example.com", "yedek@example.com");
        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), subj.capture(), anyString(), anyString(), any(), eq(false), any());
        assertThat(subj.getValue()).isEqualTo("[Site Monitor] [7/24] KRİTİK — h1.example.com — Takım A");
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getRecipientRole()).isEqualTo("NOC");
        assertThat(log.getValue().getTrigger()).isEqualTo("NOC_OPEN");
        assertThat(log.getValue().getAlertEventId()).isEqualTo(1L);
        verify(activity).recordLifecycle(eq("PING"), eq(1L), any(), any(), eq(10L), eq("NOC_NOTIFIED"), eq("system"), anyString());
        assertThat(store.get("alert:1:OPEN").getStatus()).isEqualTo("SENT");
        assertThat(store.get("alert:1:OPEN").getGroupIds()).isEqualTo("1");
    }

    @Test
    @DisplayName("izlemenin seçtiği grup(lar)a gider — varsayılana değil")
    void opensToMonitorGroups() {
        AlertEvent e = alert(2, "HTTP_DOWN", "CRITICAL", "h2.example.com");
        monitor(NocType.HTTP, 2, true, true, "2");
        dispatch(e, "INITIAL");
        assertThat(lastRecipients()).containsExactly("gece@example.com");
    }

    @Test
    @DisplayName("en düşük seviye: KRİTİK eşiğinde YÜKSEK SSL alarmı gitmez; DOWN ailesi izleme seviyesi UYARI olsa da KRİTİK sayılır")
    void minLevel() {
        monitor(NocType.HTTP, 3, true, true, null);
        dispatch(alert(3, "HTTP_SSL", "HIGH", "h3.example.com"), "INITIAL");
        assertThat(mailsSent()).isZero();

        monitor(NocType.PORT, 4, true, true, null);
        dispatch(alert(4, "PORT_DOWN", "WARNING", "h4.example.com"), "INITIAL");
        assertThat(mailsSent()).isEqualTo(1);

        config("HIGH", true, Set.of());
        dispatch(alert(3, "HTTP_SSL", "HIGH", "h3.example.com"), "ESCALATION");
        assertThat(mailsSent()).isEqualTo(2);
    }

    @Test
    @DisplayName("bakım penceresi, kapalı tür, izlemede kapalı 7/24, duraklatılmış izleme, elle yeniden bildirim → GİTMEZ")
    void suppressions() {
        monitor(NocType.PING, 5, true, true, null);
        when(maintenance.isUnderMaintenance("h5.example.com")).thenReturn(true);
        dispatch(alert(5, "PING_DOWN", "CRITICAL", "h5.example.com"), "INITIAL");

        monitor(NocType.DNS, 6, true, true, null);
        config("CRITICAL", true, Set.of(NocType.DNS));
        dispatch(alert(6, "DNS_FAILURE", "CRITICAL", "h6.example.com"), "INITIAL");
        config("CRITICAL", true, Set.of());

        monitor(NocType.PING, 7, false, true, null);
        dispatch(alert(7, "PING_DOWN", "CRITICAL", "h7.example.com"), "INITIAL");

        monitor(NocType.PING, 8, true, false, null);
        dispatch(alert(8, "PING_DOWN", "CRITICAL", "h8.example.com"), "INITIAL");

        monitor(NocType.PING, 9, true, true, null);
        dispatch(alert(9, "PING_DOWN", "CRITICAL", "h9.example.com"), "MANUAL");

        assertThat(mailsSent()).isZero();
        assertThat(store).isEmpty();
    }

    @Test
    @DisplayName("hiç aktif 7/24 grubu yoksa gönderim yok, teslim izi de yazılmaz")
    void noActiveGroup() {
        when(groupRepo.findAllByOrderByNameAsc()).thenReturn(List.of(group(1, "Pasif", false, true, "p@example.com")));
        monitor(NocType.PING, 10, true, true, null);
        dispatch(alert(10, "PING_DOWN", "CRITICAL", "h10.example.com"), "INITIAL");
        assertThat(mailsSent()).isZero();
        assertThat(store).isEmpty();
    }

    @Test
    @DisplayName("alarm başına TEK açılış: eskalasyon ve günlük tekrar ikinci NOC e-postası üretmez")
    void dedupePerAlert() {
        AlertEvent e = alert(11, "PING_DOWN", "CRITICAL", "h11.example.com");
        monitor(NocType.PING, 11, true, true, null);
        dispatch(e, "INITIAL");
        dispatch(e, "ESCALATION");
        dispatch(e, "DAILY_REALERT");
        assertThat(mailsSent()).isEqualTo(1);
    }

    @Test
    @DisplayName("başarısız ya da global e-posta KAPALIYKEN atlanan açılış sonraki tetikte yeniden denenir; çözüm ise gitmez")
    void failedOrDisabledIsRetryable() {
        AlertEvent e = alert(12, "PING_DOWN", "CRITICAL", "h12.example.com");
        monitor(NocType.PING, 12, true, true, null);
        mailStatus = "SKIPPED_DISABLED";
        dispatch(e, "INITIAL");
        assertThat(store.get("alert:12:OPEN").getStatus()).isEqualTo("SKIPPED_DISABLED");
        e.setResolvedAt("2026-01-10T22:00:00");
        svc.onAlertResolved(e);
        assertThat(mailsSent()).isEqualTo(1);          // yalnız atlanan açılış denemesi
        mailStatus = "SENT";
        dispatch(e, "DAILY_REALERT");
        assertThat(mailsSent()).isEqualTo(2);
        assertThat(store.get("alert:12:OPEN").getStatus()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("takımın e-posta kanalı kapalı / kişisel susturma bağlamı NOC'u ETKİLEMEZ (7/24)")
    void independentOfTeamMailChannel() {
        AlertEvent e = alert(13, "PING_DOWN", "CRITICAL", "h13.example.com");
        monitor(NocType.PING, 13, true, true, null);
        svc.onAlertDispatched(e, 10L, e.getDomain(), "CRITICAL", "PING_DOWN", "INITIAL",
                Map.of("monitor_id", 13L, "mail_disabled", true, "push_disabled", true));
        assertThat(mailsSent()).isEqualTo(1);
    }

    // ── Çözüm ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ÇÖZÜLDÜ yalnız açılış NOC'a gittiyse ve send_resolve açıksa; aynı gruplara; bir kez")
    void resolveOnlyIfOpenSent() {
        AlertEvent never = alert(20, "PING_DOWN", "CRITICAL", "h20.example.com");
        svc.onAlertResolved(never);
        assertThat(mailsSent()).isZero();

        AlertEvent e = alert(21, "HTTP_DOWN", "CRITICAL", "h21.example.com");
        monitor(NocType.HTTP, 21, true, true, "2");
        dispatch(e, "INITIAL");
        e.setResolvedAt("2026-01-10T22:00:00");
        e.setResolvedBy("Sistem (otomatik)");
        svc.onAlertResolved(e);
        svc.onAlertResolved(e);
        assertThat(mailsSent()).isEqualTo(2);
        assertThat(lastRecipients()).containsExactly("gece@example.com");
        assertThat(store.get("alert:21:RESOLVE").getStatus()).isEqualTo("SENT");
        verify(activity).recordLifecycle(eq("HTTP"), eq(21L), any(), any(), eq(10L), eq("NOC_RESOLVED"), eq("system"), anyString());
    }

    @Test
    @DisplayName("send_resolve KAPALI → açılış gitmiş olsa da ÇÖZÜLDÜ gitmez")
    void resolveDisabled() {
        AlertEvent e = alert(22, "PING_DOWN", "CRITICAL", "h22.example.com");
        monitor(NocType.PING, 22, true, true, null);
        dispatch(e, "INITIAL");
        config("CRITICAL", false, Set.of());
        svc.onAlertResolved(e);
        assertThat(mailsSent()).isEqualTo(1);
    }

    // ── Fırtına ──────────────────────────────────────────────────────────────

    private AlertStorm storm(long id) {
        AlertStorm s = new AlertStorm();
        s.setId(id);
        s.setCreatedAt("2026-01-10T21:05:00");
        s.setResolvedAt("2026-01-10T21:50:00");
        return s;
    }

    @Test
    @DisplayName("fırtına: kapsanan üyeler için TEK toplu e-posta; üyeler 'açılışı gitti' işaretlenir → tek başına açılış gitmez, çözümü gider")
    void stormAggregation() {
        AlertEvent a = alert(31, "PORT_DOWN", "CRITICAL", "h31.example.com");
        AlertEvent b = alert(32, "PORT_DOWN", "CRITICAL", "h32.example.com");
        AlertEvent c = alert(33, "PORT_DOWN", "CRITICAL", "h33.example.com");
        monitor(NocType.PORT, 31, true, true, null);
        monitor(NocType.PORT, 32, true, true, "2");
        monitor(NocType.PORT, 33, false, true, null);   // 7/24 kapalı → e-postada yok

        svc.onStormDispatched(storm(5), List.of(a, b, c), "Tüm izlemeler", "Port Kesintisi");
        svc.onStormDispatched(storm(5), List.of(a, b, c), "Tüm izlemeler", "Port Kesintisi");   // günlük toplu tekrar
        assertThat(mailsSent()).isEqualTo(1);
        assertThat(lastRecipients()).containsExactlyInAnyOrder("noc@example.com", "yedek@example.com", "gece@example.com");
        assertThat(store.get("alert:31:OPEN").getStatus()).isEqualTo(NocNotificationService.VIA_STORM);
        assertThat(store.get("alert:32:OPEN").getStatus()).isEqualTo(NocNotificationService.VIA_STORM);
        assertThat(store).doesNotContainKey("alert:33:OPEN");

        dispatch(a, "INITIAL");                          // fırtına sonrası tek başına dağıtım: tekrar YOK
        assertThat(mailsSent()).isEqualTo(1);
        a.setResolvedAt("2026-01-10T22:10:00");
        svc.onAlertResolved(a);                          // açılışı fırtınayla gitti → kapanışı gider
        assertThat(mailsSent()).isEqualTo(2);
    }

    @Test
    @DisplayName("2026-09-28: fırtınada SAHİPSİZ üye (olay + izleme takımsız, UG yok) 7/24 postasına girmez; UG'li ve sahipli üye girer")
    void storm_unownedMemberExcluded() {
        AlertEvent owned = alert(71, "PORT_DOWN", "CRITICAL", "h71.example.com");
        monitor(NocType.PORT, 71, true, true, null);
        AlertEvent orphan = alert(72, "PORT_DOWN", "CRITICAL", "h72.example.com");
        orphan.setTeamId(null);
        when(snap.forAlert(eq(NocType.PORT), any(), eq((Object) Long.valueOf(72)))).thenReturn(
                new NocMonitorDirectory.Row(NocType.PORT, 72, "izleme-72", "h72.example.com", null, null, true, true, null, false));
        AlertEvent ugOnly = alert(73, "PORT_DOWN", "CRITICAL", "h73.example.com");
        ugOnly.setTeamId(null);
        when(snap.forAlert(eq(NocType.PORT), any(), eq((Object) Long.valueOf(73)))).thenReturn(
                new NocMonitorDirectory.Row(NocType.PORT, 73, "izleme-73", "h73.example.com", null, 20L, true, true, null, false));

        var eligible = svc.eligibleStormMembers(List.of(owned, orphan, ugOnly), config.get());
        assertThat(eligible).extracting(s -> s.event().getId()).containsExactly(71L, 73L);
    }

    @Test
    @DisplayName("fırtına çözümü: açılışı NOC'a gitmiş kurtulanlar için TEK ÇÖZÜLDÜ; sonra tek başına ikinci çözüm yok")
    void stormRecovery() {
        AlertEvent a = alert(41, "PING_DOWN", "CRITICAL", "h41.example.com");
        AlertEvent b = alert(42, "PING_DOWN", "CRITICAL", "h42.example.com");
        monitor(NocType.PING, 41, true, true, null);
        monitor(NocType.PING, 42, true, true, null);
        svc.onStormDispatched(storm(6), List.of(a, b), "Tüm izlemeler", "Ping Yanıtsız");
        assertThat(mailsSent()).isEqualTo(1);

        a.setResolvedAt("2026-01-10T21:40:00");
        svc.onStormRecovered(storm(6), List.of(a), List.of(b));
        svc.onStormRecovered(storm(6), List.of(a), List.of(b));
        assertThat(mailsSent()).isEqualTo(2);
        assertThat(store.get("alert:41:RESOLVE").getStatus()).isEqualTo(NocNotificationService.VIA_STORM);
        svc.onAlertResolved(a);
        assertThat(mailsSent()).isEqualTo(2);
    }

    @Test
    @DisplayName("fırtınada kapsanan üye yoksa NOC e-postası yok; kurtulanların açılışı gitmemişse ÇÖZÜLDÜ de yok")
    void stormWithoutEligibleMembers() {
        AlertEvent a = alert(51, "PING_DOWN", "CRITICAL", "h51.example.com");
        monitor(NocType.PING, 51, false, true, null);
        svc.onStormDispatched(storm(7), List.of(a), "Tüm izlemeler", "Ping Yanıtsız");
        svc.onStormRecovered(storm(7), List.of(a), List.of());
        assertThat(mailsSent()).isZero();
        assertThat(store).isEmpty();
    }

    @Test
    @DisplayName("bağlam JSON'undan monitor_id okunur (fırtına üyesi eşlemesi)")
    void monitorIdFromContext() {
        assertThat(NocNotificationService.monitorIdOf("{\"a\":1,\"monitor_id\": 42}")).isEqualTo(42L);
        assertThat(NocNotificationService.monitorIdOf("{\"monitor_id\":\"7\"}")).isEqualTo(7L);
        assertThat(NocNotificationService.monitorIdOf("{}")).isNull();
        assertThat(NocNotificationService.monitorIdOf(null)).isNull();
    }

    // ── Yayın öncesi inceleme (2026-09-27) ──────────────────────────────────

    private NotificationLog lastLog() {
        ArgumentCaptor<NotificationLog> c = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs, atLeastOnce()).save(c.capture());
        return c.getValue();
    }

    private String lastSubject() {
        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        verify(email, atLeastOnce()).sendHtml(any(String[].class), any(), subj.capture(), anyString(), anyString(), any(), anyBoolean(), any());
        return subj.getValue();
    }

    @Test
    @DisplayName("posta günlüğü MASKELİ: gövdede tel: yok, tam numara yok (yalnız son iki hane); alıcıda adres yok, grup adı + sayı")
    void logRowIsRedacted() {
        monitor(NocType.PING, 60, true, true, null);
        dispatch(alert(60, "PING_DOWN", "CRITICAL", "h60.example.com"), "INITIAL");
        ArgumentCaptor<String> sentHtml = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), anyString(), sentHtml.capture(), anyString(), any(), anyBoolean(), any());
        assertThat(sentHtml.getValue()).contains("tel:+905550000000");   // 7/24 ekibinin e-postası TAM
        NotificationLog l = lastLog();
        assertThat(l.getMessage()).doesNotContain("tel:").doesNotContain("5550000000").doesNotContain("555 000 00 00")
                .contains(NocLogRedaction.MASK + "00").contains("Kişi A");
        assertThat(l.getRecipientEmail()).isEqualTo("NOC Ana (2 adres)").doesNotContain("@");
        assertThat(l.getRecipientRole()).isEqualTo("NOC");
    }

    @Test
    @DisplayName("fırtına günlük satırı da MASKELİ (on takıma kadar arama listesi taşır)")
    void stormLogRowIsRedacted() {
        monitor(NocType.PORT, 61, true, true, null);
        svc.onStormDispatched(storm(20), List.of(alert(61, "PORT_DOWN", "CRITICAL", "h61.example.com")), "Tüm izlemeler", "Port Kesintisi");
        NotificationLog l = lastLog();
        assertThat(l.getTrigger()).isEqualTo("NOC_STORM");
        assertThat(l.getMessage()).doesNotContain("tel:").doesNotContain("5550000000");
        assertThat(l.getRecipientEmail()).doesNotContain("@");
    }

    @Test
    @DisplayName("bayat 'gönderiliyor' (pod ölümü) 10 dk sonra yeniden denenir; taze olan engeller")
    void staleSendingIsRetryable() {
        java.time.Instant t0 = java.time.Instant.parse("2026-01-10T21:00:00Z");
        svc.clock = java.time.Clock.fixed(t0, java.time.ZoneOffset.UTC);
        monitor(NocType.PING, 62, true, true, null);
        NocDelivery stuck = new NocDelivery();
        stuck.setDedupeKey("alert:62:OPEN"); stuck.setPhase("OPEN"); stuck.setStatus("SENDING");
        stuck.setCreatedAt("2026-01-10T20:55:00"); stuck.setUpdatedAt("2026-01-10T20:55:00");   // 5 dk önce — taze
        put(stuck);
        dispatch(alert(62, "PING_DOWN", "CRITICAL", "h62.example.com"), "INITIAL");
        assertThat(mailsSent()).isZero();
        svc.clock = java.time.Clock.fixed(t0.plus(java.time.Duration.ofMinutes(6)), java.time.ZoneOffset.UTC);   // artık 11 dk
        dispatch(alert(62, "PING_DOWN", "CRITICAL", "h62.example.com"), "DAILY_REALERT");
        assertThat(mailsSent()).isEqualTo(1);
        assertThat(store.get("alert:62:OPEN").getStatus()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("fırtınaya SONRADAN katılan kapsanan izleme: TEK toplu güncelleme; tik tekrarında spam yok; 5 dk aralık; çözümü gider")
    void stormJoinersGetBatchedUpdate() {
        // 21:04 → +2 dk = 21:06 YENİ 5-dk dilimine düşer: tekil anahtar engellemez, yalnız "son güncelleme" aralığı engeller
        java.time.Instant t0 = java.time.Instant.parse("2026-01-10T21:04:00Z");
        svc.clock = java.time.Clock.fixed(t0, java.time.ZoneOffset.UTC);
        AlertEvent a = alert(71, "PORT_DOWN", "CRITICAL", "h71.example.com");
        monitor(NocType.PORT, 71, true, true, null);
        svc.onStormDispatched(storm(30), List.of(a), "Tüm izlemeler", "Port Kesintisi");
        assertThat(mailsSent()).isEqualTo(1);

        AlertEvent c = alert(72, "PORT_DOWN", "CRITICAL", "h72.example.com");   // açılış e-postasından SONRA katıldı
        monitor(NocType.PORT, 72, true, true, "2");
        AlertEvent off = alert(73, "PORT_DOWN", "CRITICAL", "h73.example.com");
        monitor(NocType.PORT, 73, false, true, null);                           // 7/24 kapalı → yok
        svc.onStormTick(storm(30), List.of(a, c, off), "Tüm izlemeler", "Port Kesintisi");
        assertThat(mailsSent()).isEqualTo(2);
        assertThat(lastSubject()).isEqualTo(NocMailComposer.stormUpdateSubject(1));
        assertThat(lastRecipients()).containsExactly("gece@example.com");
        assertThat(store.get("alert:72:OPEN").getStatus()).isEqualTo(NocNotificationService.VIA_STORM);
        assertThat(store).doesNotContainKey("alert:73:OPEN");
        assertThat(lastLog().getTrigger()).isEqualTo("NOC_STORM_UPDATE");

        svc.onStormTick(storm(30), List.of(a, c, off), "Tüm izlemeler", "Port Kesintisi");   // aynı tik tekrarı
        AlertEvent d = alert(74, "PORT_DOWN", "CRITICAL", "h74.example.com");
        monitor(NocType.PORT, 74, true, true, null);
        svc.clock = java.time.Clock.fixed(t0.plus(java.time.Duration.ofMinutes(2)), java.time.ZoneOffset.UTC);
        svc.onStormTick(storm(30), List.of(a, c, d), "Tüm izlemeler", "Port Kesintisi");      // yeni dilim ama 5 dk dolmadı
        assertThat(mailsSent()).isEqualTo(2);

        svc.clock = java.time.Clock.fixed(t0.plus(java.time.Duration.ofMinutes(6)), java.time.ZoneOffset.UTC);
        svc.onStormTick(storm(30), List.of(a, c, d), "Tüm izlemeler", "Port Kesintisi");      // yalnız d
        assertThat(mailsSent()).isEqualTo(3);
        assertThat(lastSubject()).isEqualTo(NocMailComposer.stormUpdateSubject(1));
        svc.onStormTick(storm(30), List.of(a, c, d), "Tüm izlemeler", "Port Kesintisi");
        assertThat(mailsSent()).isEqualTo(3);

        c.setResolvedAt("2026-01-10T22:00:00");
        svc.onAlertResolved(c);                                                               // güncellemeyle açıldı → çözüm gider
        assertThat(mailsSent()).isEqualTo(4);
        assertThat(lastRecipients()).containsExactly("gece@example.com");
    }

    @Test
    @DisplayName("fırtına açılışında kapsanan üye yoktu; ilk kapsanan katılımcı tik'te fırtına AÇILIŞINI gönderir")
    void firstCoveredJoinerOpensStorm() {
        AlertEvent a = alert(81, "PING_DOWN", "CRITICAL", "h81.example.com");
        monitor(NocType.PING, 81, false, true, null);
        svc.onStormDispatched(storm(31), List.of(a), "Tüm izlemeler", "Ping Yanıtsız");
        assertThat(mailsSent()).isZero();
        AlertEvent b = alert(82, "PING_DOWN", "CRITICAL", "h82.example.com");
        monitor(NocType.PING, 82, true, true, null);
        svc.onStormTick(storm(31), List.of(a, b), "Tüm izlemeler", "Ping Yanıtsız");
        assertThat(mailsSent()).isEqualTo(1);
        assertThat(lastSubject()).isEqualTo(NocMailComposer.stormSubject(2));
        assertThat(store.get("alert:82:OPEN").getStatus()).isEqualTo(NocNotificationService.VIA_STORM);
    }

    @Test
    @DisplayName("O-3 (D-b6): eski fırtınadan SESSİZCE taşınan takım fırtınası 7/24'e YENİ açılış postası atmaz (tik + günlük tekrar); sonradan katılan güncellemeyle gider")
    void legacyMovedStorm_noNewOpeningPost() {
        svc.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-01-10T21:04:00Z"), java.time.ZoneOffset.UTC);
        AlertEvent a = alert(91, "PORT_DOWN", "CRITICAL", "h91.example.com");
        AlertEvent b = alert(92, "PORT_DOWN", "CRITICAL", "h92.example.com");
        monitor(NocType.PORT, 91, true, true, null);
        monitor(NocType.PORT, 92, true, true, null);
        svc.onStormDispatched(storm(40), List.of(a, b), "Tüm izlemeler", "Port Kesintisi");   // ESKİ (kuruluş geneli) fırtına
        assertThat(mailsSent()).isEqualTo(1);

        AlertStorm team = storm(41);
        team.setLegacyStormId(40L);                                                            // üyeler sessizce taşındı
        svc.onStormTick(team, List.of(a, b), "Takım A", "Port Kesintisi");
        svc.onStormDispatched(team, List.of(a, b), "Takım A", "Port Kesintisi");               // günlük toplu tekrar
        assertThat(mailsSent()).as("taşınan üyeler 7/24'e eski fırtınayla zaten bildirildi").isEqualTo(1);

        AlertEvent c = alert(93, "PORT_DOWN", "CRITICAL", "h93.example.com");                  // taşımadan SONRA katıldı
        monitor(NocType.PORT, 93, true, true, null);
        svc.onStormTick(team, List.of(a, b, c), "Takım A", "Port Kesintisi");
        assertThat(mailsSent()).isEqualTo(2);
        assertThat(lastSubject()).isEqualTo(NocMailComposer.stormUpdateSubject(1));
    }

    @Test
    @DisplayName("fırtına değerlendirmesi başına TEK anlık görüntü: üye başına dizin sorgusu yok")
    void singleSnapshotPerStormEvaluation() {
        List<AlertEvent> members = new ArrayList<>();
        for (long id = 91; id <= 95; id++) {
            monitor(NocType.PORT, id, true, true, null);
            members.add(alert(id, "PORT_DOWN", "CRITICAL", "h" + id + ".example.com"));
        }
        svc.onStormDispatched(storm(32), members, "Tüm izlemeler", "Port Kesintisi");
        verify(directory, times(1)).snapshot();
        verify(directory, never()).forAlert(any(), any(), any());
        verify(snap, times(5)).forAlert(any(), any(), any());
        assertThat(mailsSent()).isEqualTo(1);
    }

    @Test
    @DisplayName("7/24 e-postası: SSL bağlantısı sertifika penceresini açar (open=cert); erişilebilirlik ve diğer türler açmaz")
    void monitorUrl_sslOpensCertificateWindow() {
        var ssl = new NocMonitorDirectory.Row(NocType.SSL, 7, "www.example.com", "www.example.com", 10L, null,
                true, true, null, false);
        assertThat(NocNotificationService.monitorUrl("http://cm.local", NocType.SSL, ssl, "CHAIN_BROKEN"))
                .isEqualTo("http://cm.local/?tab=dashboard&domain=www.example.com&open=cert");
        assertThat(NocNotificationService.monitorUrl("http://cm.local", NocType.SSL, ssl,
                com.sitemonitor.service.EscalationService.TYPE_ACCESSIBILITY))
                .isEqualTo("http://cm.local/?tab=status&domain=www.example.com");
        var http = new NocMonitorDirectory.Row(NocType.HTTP, 9, "api", "https://api.example.com", 10L, null,
                true, true, null, false);
        assertThat(NocNotificationService.monitorUrl("http://cm.local", NocType.HTTP, http, "HTTP_DOWN"))
                .isEqualTo("http://cm.local/?tab=http&monitor=9");
    }

    // ── 2026-10-09: fırtına tik'i gitmemiş açılışı SONSUZA kadar yeniden göndermez ─────────────────────────────

    /** Posta günlüğüne yazılan, verilen tetikli satırlar (sırayla). */
    private List<NotificationLog> logRows(String trigger) {
        List<NotificationLog> out = new ArrayList<>();
        for (var inv : mockingDetails(logs).getInvocations()) {
            if (!inv.getMethod().getName().equals("save")) continue;
            NotificationLog n = (NotificationLog) inv.getArgument(0);
            if (trigger.equals(n.getTrigger())) out.add(n);
        }
        return out;
    }

    /** 30 sn arayla N tik (yaşam döngüsü kadansı) — saat her tikte ilerler. */
    private void ticks(AlertStorm st, List<AlertEvent> members, java.time.Instant from, int n) {
        for (int i = 0; i < n; i++) {
            svc.clock = java.time.Clock.fixed(from.plusSeconds(30L * i), java.time.ZoneOffset.UTC);
            svc.onStormTick(st, members, "Takım A", "Port Kesintisi");
        }
    }

    @Test
    @DisplayName("2026-10-09 döngü kapısı: global e-posta KAPALIYKEN (SKIPPED_DISABLED) fırtına açılışı tik'te yeniden GÖNDERİLMEZ — 2 saatlik 240 tikte tek deneme, tek günlük satırı")
    void stormTick_skippedOpening_isFinal() {
        java.time.Instant t0 = java.time.Instant.parse("2026-01-10T21:05:00Z");
        svc.clock = java.time.Clock.fixed(t0, java.time.ZoneOffset.UTC);
        AlertEvent a = alert(101, "PORT_DOWN", "CRITICAL", "h101.example.com");
        monitor(NocType.PORT, 101, true, true, null);
        mailStatus = "SKIPPED_DISABLED";
        svc.onStormDispatched(storm(50), List.of(a), "Takım A", "Port Kesintisi");   // fırtınanın açılışı
        assertThat(mailsSent()).isEqualTo(1);

        ticks(storm(50), List.of(a), t0.plusSeconds(30), 240);

        assertThat(mailsSent()).as("SKIPPED kesin karar — tik yeniden denemez").isEqualTo(1);
        assertThat(logRows(NocNotificationService.TRIGGER_STORM)).hasSize(1);
        assertThat(store.get("storm:50:OPEN").getStatus()).isEqualTo("SKIPPED_DISABLED");
        assertThat(store.get("storm:50:OPEN").getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("2026-10-09 döngü kapısı: FAILED fırtına açılışı tik'te geri çekilmeli (≥5 dk) ve tavanlı (3 deneme) — sonra durur; son deneme 'vazgeçildi' izini taşır")
    void stormTick_failedOpening_backoffAndCap() {
        java.time.Instant t0 = java.time.Instant.parse("2026-01-10T21:05:00Z");
        svc.clock = java.time.Clock.fixed(t0, java.time.ZoneOffset.UTC);
        AlertEvent a = alert(102, "PORT_DOWN", "CRITICAL", "h102.example.com");
        monitor(NocType.PORT, 102, true, true, null);
        // SMTP DATA sonrası istemci zaman aşımı: posta belki gitti ama durum FAILED — eskiden 30 sn'de bir yeniden gidiyordu.
        mailStatus = "FAILED: Read timed out";
        svc.onStormDispatched(storm(51), List.of(a), "Takım A", "Port Kesintisi");
        assertThat(mailsSent()).isEqualTo(1);

        ticks(storm(51), List.of(a), t0.plusSeconds(30), 9);   // ilk 4,5 dk: geri çekilme
        assertThat(mailsSent()).as("5 dk dolmadan yeniden deneme yok").isEqualTo(1);

        ticks(storm(51), List.of(a), t0.plus(java.time.Duration.ofMinutes(5)), 240);   // sonraki 2 saat

        assertThat(mailsSent()).as("toplam en çok " + NocNotificationService.STORM_OPEN_MAX_ATTEMPTS + " deneme")
                .isEqualTo(NocNotificationService.STORM_OPEN_MAX_ATTEMPTS);
        List<NotificationLog> rows = logRows(NocNotificationService.TRIGGER_STORM);
        assertThat(rows).hasSize(NocNotificationService.STORM_OPEN_MAX_ATTEMPTS);
        assertThat(rows.get(0).getEmailStatus()).isEqualTo("FAILED: Read timed out");
        assertThat(rows.get(rows.size() - 1).getEmailStatus())
                .startsWith("FAILED: Read timed out")
                .contains("vazgeçildi");
        NocDelivery open = store.get("storm:51:OPEN");
        assertThat(open.getAttempts()).isEqualTo(NocNotificationService.STORM_OPEN_MAX_ATTEMPTS);
        assertThat(open.getStatus()).startsWith("FAILED").contains("vazgeçildi");
    }

    @Test
    @DisplayName("2026-10-09: tavanlı tik yeniden denemesi geçici arızayı yine kurtarır — 5 dk sonra SMTP düzelince açılış gider, sonra tekrar yok")
    void stormTick_failedOpening_recoversWithinCap() {
        java.time.Instant t0 = java.time.Instant.parse("2026-01-10T21:05:00Z");
        svc.clock = java.time.Clock.fixed(t0, java.time.ZoneOffset.UTC);
        AlertEvent a = alert(103, "PORT_DOWN", "CRITICAL", "h103.example.com");
        monitor(NocType.PORT, 103, true, true, null);
        mailStatus = "FAILED: Connection refused";
        svc.onStormDispatched(storm(52), List.of(a), "Takım A", "Port Kesintisi");
        mailStatus = "SENT";
        ticks(storm(52), List.of(a), t0.plus(java.time.Duration.ofMinutes(5)), 20);

        assertThat(mailsSent()).isEqualTo(2);
        assertThat(store.get("storm:52:OPEN").getStatus()).isEqualTo("SENT");
        assertThat(store.get("alert:103:OPEN").getStatus()).isEqualTo(NocNotificationService.VIA_STORM);
    }

    @Test
    @DisplayName("2026-10-09: fırtınanın KENDİ tetiği (günlük toplu tekrar) tik tavanı dolduktan sonra da bir kez dener — alarm hunisindeki gibi (davranış aynı)")
    void stormDailyRealert_stillRetriesAfterTickCap() {
        java.time.Instant t0 = java.time.Instant.parse("2026-01-10T21:05:00Z");
        svc.clock = java.time.Clock.fixed(t0, java.time.ZoneOffset.UTC);
        AlertEvent a = alert(104, "PORT_DOWN", "CRITICAL", "h104.example.com");
        monitor(NocType.PORT, 104, true, true, null);
        mailStatus = "FAILED: Connection refused";
        svc.onStormDispatched(storm(53), List.of(a), "Takım A", "Port Kesintisi");
        ticks(storm(53), List.of(a), t0.plus(java.time.Duration.ofMinutes(5)), 60);
        assertThat(mailsSent()).isEqualTo(NocNotificationService.STORM_OPEN_MAX_ATTEMPTS);

        mailStatus = "SENT";
        svc.clock = java.time.Clock.fixed(t0.plus(java.time.Duration.ofHours(24)), java.time.ZoneOffset.UTC);
        svc.onStormDispatched(storm(53), List.of(a), "Takım A", "Port Kesintisi");   // StormService günlük tekrarı
        assertThat(mailsSent()).isEqualTo(NocNotificationService.STORM_OPEN_MAX_ATTEMPTS + 1);
        assertThat(store.get("storm:53:OPEN").getStatus()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("2026-10-09: vazgeçme notu 255 karakterlik günlük kolonuna sığar; gitmiş / atlanmış / tavan altı durum değişmez")
    void giveUpNote_fitsLogColumn() {
        String longErr = "FAILED: " + "x".repeat(400);
        String noted = NocNotificationService.withGiveUpNote(longErr, NocNotificationService.STORM_OPEN_MAX_ATTEMPTS);
        assertThat(noted).hasSizeLessThanOrEqualTo(255).startsWith("FAILED: ").contains("vazgeçildi");
        assertThat(NocNotificationService.withGiveUpNote("SENT", 5)).isEqualTo("SENT");
        assertThat(NocNotificationService.withGiveUpNote("SKIPPED_DISABLED", 5)).isEqualTo("SKIPPED_DISABLED");
        assertThat(NocNotificationService.withGiveUpNote("FAILED: x", 1)).isEqualTo("FAILED: x");
    }
}
