package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.EscalationContact;
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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 2026-09-28 PROD HATASI — "Kontaksız takımın KRİTİK alarmı BAŞKA takımın müdürüne gidiyor".
 *
 * <p>Kök neden: {@code EscalationService.getContactsForLevel} takımın o seviyede kontağı yoksa "global contacts
 * (no team assigned)" diyerek {@code findByActiveTrueOrderByRoleAsc()} / {@code findByMinAlertLevel…ActiveTrue}
 * sorgularına düşüyordu; bu sorgular {@code team_id}'yi hiç süzmediği için TÜM takımların etkin kontaklarını
 * getiriyordu. Ürün kararı (2026-09-28): eskalasyon alıcıları YALNIZ alarmın kendi takımının kontaklarıdır; takımda
 * yoksa kimse eklenmez (yalnız takım alıcıları). Takımsız (team_id NULL) kontak hiçbir yolda alıcı değildir;
 * sahipsiz alarm (SY/UG takımı yok) hiçbir kanaldan bildirim üretmez.
 *
 * <p>Kontak deposu VERİTABANI GİBİ davranan bir sahteyle kurulur ({@link #db}): takım süzgeçsiz sorgu gerçekten
 * herkesi döndürür, takım süzgeçli sorgu gerçekten süzer. Böylece eski kod (yedek yola düşen) bu testlerde B'nin
 * müdürünü ve takımsız kişiyi alıcıya ekler → kırmızı.
 *
 * <p>Senaryo: Takım A (id 1) kontaksız; Takım B (id 2) etkin MANAGER kontağı (webhook'lu); ayrıca takımsız (global)
 * bir kontak var. A'nın KRİTİK HOSTNAME_MISMATCH alarmının HİÇBİR yolunda B'nin müdürü ya da global kişi yok.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationContactLeakTest {

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
    @Mock com.sitemonitor.service.noc.NocNotificationService noc;

    static final long TEAM_A = 1L, TEAM_B = 2L, TEAM_C = 3L;
    static final String A_MAIL = "takim-a@example.com", B_MAIL = "takim-b@example.com", C_MAIL = "takim-c@example.com";
    static final String B_MANAGER = "mudur-b@example.com", GLOBAL = "global@example.com";
    static final String B_HOOK = "https://hooks.example.com/services/T1/B2/takim-b";
    static final String DOMAIN = "a.example.com";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Kontak tablosu — sahte depo cevaplarını buradan süzer (veritabanının yapacağı gibi). */
    final List<EscalationContact> db = new ArrayList<>();
    final Map<String, CertificateInventory> inventory = new HashMap<>();

    EscalationService service;

    @BeforeEach
    void setUp() {
        service = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                weeklyAvailability, webhookService, new ObjectMapper(), notificationLogRepo, latestCheckRepo, teamRepo,
                smtpSettings, maintenanceService, stormService, userPushService, domainMonitorRepo, domainCheckRepo,
                dnsRecordRepo, pageCheckRepo, notificationGroups, appSettings);
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "nocNotifications", noc);

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

        team(TEAM_A, "Takım A", A_MAIL);
        team(TEAM_B, "Takım B", B_MAIL);

        EscalationContact bManager = contact(10L, TEAM_B, B_MANAGER, "MANAGER", "HIGH");
        bManager.setWebhookUrl(B_HOOK); bManager.setWebhookType("TEAMS");
        db.add(bManager);
        db.add(contact(11L, null, GLOBAL, "CLEVEL", "WARNING"));   // takımsız (eski veri)

        // DB'ye sadık sahte depo: süzgeçsiz sorgu HERKESİ döndürür, süzgeçli sorgu gerçekten takıma süzer.
        when(contactRepo.findByActiveTrueOrderByRoleAsc()).thenAnswer(i -> active(c -> true));
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(any()))
                .thenAnswer(i -> active(c -> Objects.equals(c.getTeamId(), i.getArgument(0))));
        when(contactRepo.findByTeamIdAndMinAlertLevelInAndActiveTrue(any(), anyList()))
                .thenAnswer(i -> active(c -> Objects.equals(c.getTeamId(), i.getArgument(0))
                        && ((List<?>) i.getArgument(1)).contains(c.getMinAlertLevel())));
        when(contactRepo.findByTeamIdAndMinAlertLevelAndActiveTrue(any(), anyString()))
                .thenAnswer(i -> active(c -> Objects.equals(c.getTeamId(), i.getArgument(0))
                        && Objects.equals(c.getMinAlertLevel(), i.getArgument(1))));

        when(inventoryRepo.findByDomainIn(anyCollection())).thenAnswer(i -> {
            List<CertificateInventory> out = new ArrayList<>();
            for (Object d : (Collection<?>) i.getArgument(0)) if (inventory.containsKey(d)) out.add(inventory.get(d));
            return out;
        });
        when(inventoryRepo.findByDomain(anyString())).thenAnswer(i -> Optional.ofNullable(inventory.get((String) i.getArgument(0))));
        inventory(DOMAIN, TEAM_A, null);
    }

    // ── A takımı kontaksız: hiçbir yolda B'nin müdürü / takımsız kişi YOK ───────────────────────────────

    @Test
    @DisplayName("İLK bildirim: A'nın KRİTİK HOSTNAME_MISMATCH e-postası yalnız A'ya; B'nin müdürü ve takımsız kişi YOK, webhook yok")
    void initial_teamWithoutContacts_onlyOwnTeam() {
        service.processResults(List.of(hijackedResult(DOMAIN)));

        AlertEvent saved = lastSavedEvent();
        assertThat(saved.getAlertType()).isEqualTo(EscalationService.TYPE_HOSTNAME_MISMATCH);
        assertThat(saved.getAlertLevel()).isEqualTo("CRITICAL");
        assertThat(sentTo()).containsExactly(A_MAIL);
        assertThat(saved.getNotifiedContacts()).isEqualTo("[]");
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
        verify(userPushService).enqueueAlert(any(), eq("INITIAL"), eq(TEAM_A), any(), any());
    }

    @Test
    @DisplayName("Günlük yeniden uyarı (DAILY_REALERT): yalnız A'nın alıcıları")
    void dailyRealert_onlyOwnTeam() {
        AlertEvent open = openEvent(TEAM_A, "CRITICAL");
        open.setLastReAlertAt(ISO.format(Instant.now().minus(25, ChronoUnit.HOURS)));
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(open));

        service.processResults(List.of(hijackedResult(DOMAIN)));

        assertThat(sentTo()).containsExactly(A_MAIL);
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Tekrar bildir: önizleme ve gönderim yalnız A'nın adresi; kontak kuyruğu boş")
    void reNotify_onlyOwnTeam() {
        AlertEvent open = openEvent(TEAM_A, "CRITICAL");
        when(alertEventRepo.findById(open.getId())).thenReturn(Optional.of(open));

        assertThat(service.previewReNotify(open.getId()))
                .extracting(EscalationService.ReNotifyRecipient::email).containsExactly(A_MAIL);
        Map<String, Object> r = service.reNotify(open.getId());
        assertThat(r.get("contacts_queued")).isEqualTo(0);
        assertThat(sentTo()).containsExactly(A_MAIL);
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Çözüm (RESOLVE) e-postası yalnız A'ya; B'nin müdürüne ÇÖZÜLDÜ gitmez, webhook yok")
    void resolution_onlyOwnTeam() {
        AlertEvent open = openEvent(TEAM_A, "CRITICAL");
        when(alertEventRepo.findById(open.getId())).thenReturn(Optional.of(open));

        service.resolve(open.getId(), "Kişi A");

        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendResolutionAlert(to.capture(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(to.getValue()).containsExactly(A_MAIL);
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Kim alır? simülatörü: A için kişi YOK, 'tanımlı değil' bayrağı; global/başka takım gösterilmez")
    @SuppressWarnings("unchecked")
    void simulator_teamWithoutContacts() {
        Map<String, Object> r = service.simulateRecipients(TEAM_A, "CRITICAL", false, null);
        assertThat((List<?>) r.get("contacts")).isEmpty();
        assertThat((List<?>) r.get("webhooks")).isEmpty();
        assertThat(r).doesNotContainKey("contacts_fallback_global");
        assertThat(r.get("team_contacts_missing")).isEqualTo(true);
        assertThat(r.get("team_contacts_defined")).isEqualTo(false);
        List<Map<String, Object>> emails = (List<Map<String, Object>>) r.get("team_emails");
        assertThat(emails).extracting(m -> m.get("email")).containsExactly(A_MAIL);
        assertThat(r.get("email_total")).isEqualTo(1L);
    }

    @Test
    @DisplayName("Seviye uyuşmazlığı: A'nın tek kişisi KRİTİK eşikli, alarm YÜKSEK → kişi yok (B'nin YÜKSEK müdürüne düşmez)")
    void levelMismatch_noFallbackToOtherTeams() {
        db.add(contact(12L, TEAM_A, "kritik-a@example.com", "MANAGER", "CRITICAL"));
        Map<String, Object> high = service.simulateRecipients(TEAM_A, "HIGH", false, null);
        assertThat((List<?>) high.get("contacts")).isEmpty();
        assertThat(high.get("team_contacts_missing")).isEqualTo(true);
        assertThat(high.get("team_contacts_defined")).isEqualTo(true);   // ekran: "bu seviyeye uyan yok"

        service.processConfirmedOutage("port.example.com", EscalationService.TYPE_PORT_SLOW, "HIGH",
                new HashMap<>(Map.of("team_id", TEAM_A, "port", 443, "alert_level", "HIGH")));
        assertThat(sentTo()).containsExactly(A_MAIL);
    }

    // ── Pozitif kontrol: takımın KENDİ kişileri hâlâ eklenir ──────────────────────────────────────────

    @Test
    @DisplayName("Pozitif kontrol: B'nin alarmı B'nin müdürüne gider (e-posta + webhook); takımsız kişi yine yok")
    void ownTeamContacts_stillIncluded() {
        inventory(DOMAIN, TEAM_B, null);

        service.processResults(List.of(hijackedResult(DOMAIN)));

        assertThat(sentTo()).containsExactlyInAnyOrder(B_MAIL, B_MANAGER);
        verify(webhookService).send(eq("TEAMS"), eq(B_HOOK), anyString(), anyString(), eq("CRITICAL"));
    }

    // ── "Her sahip takım kendi kişisi" (2026-09-28): SY ve UG yalnız KENDİ kişilerini getirir ─────────────

    @Test
    @DisplayName("Yalnız UG'li kayıt: UG = B → B'nin adresi + B'nin KENDİ müdürü (+ webhook); UG = A (kontaksız) → yalnız A")
    void ugOnly_ugBringsOwnContacts() {
        inventory(DOMAIN, null, TEAM_B);
        service.processResults(List.of(hijackedResult(DOMAIN)));
        assertThat(sentTo()).containsExactlyInAnyOrder(B_MAIL, B_MANAGER);
        verify(webhookService).send(eq("TEAMS"), eq(B_HOOK), anyString(), anyString(), eq("CRITICAL"));

        clearInvocations(emailService, webhookService);
        inventory("a2.example.com", null, TEAM_A);
        service.processResults(List.of(hijackedResult("a2.example.com")));
        assertThat(sentTo()).containsExactly(A_MAIL);
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("SY = A (kontaksız) + UG = B: A ve B adresleri + YALNIZ B'nin kendi müdürü; SY = A + UG = C (ikisi kontaksız) → B'nin müdürü ASLA")
    void syAndUg_eachTeamBringsOnlyItsOwnContacts() {
        inventory(DOMAIN, TEAM_A, TEAM_B);
        service.processResults(List.of(hijackedResult(DOMAIN)));
        assertThat(sentTo()).containsExactlyInAnyOrder(A_MAIL, B_MAIL, B_MANAGER);
        assertThat(lastSavedEvent().getNotifiedContacts()).contains(B_MANAGER).doesNotContain(GLOBAL);

        clearInvocations(emailService, webhookService);
        team(TEAM_C, "Takım C", C_MAIL);
        inventory("c.example.com", TEAM_A, TEAM_C);
        service.processResults(List.of(hijackedResult("c.example.com")));
        assertThat(sentTo()).containsExactlyInAnyOrder(A_MAIL, C_MAIL);
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Aynı kişi SY ve UG'de kayıtlıysa tek e-posta (webhook korunur); simülatör iki takımı ayrı gösterir (kontaksız takıma 'tanımlı değil')")
    @SuppressWarnings("unchecked")
    void syAndUg_dedupeAndSimulator() {
        db.add(contact(20L, TEAM_A, B_MANAGER, "MANAGER", "HIGH"));   // aynı adres A'da da kişi
        inventory(DOMAIN, TEAM_A, TEAM_B);
        service.processResults(List.of(hijackedResult(DOMAIN)));
        assertThat(sentTo()).containsExactlyInAnyOrder(A_MAIL, B_MAIL, B_MANAGER);   // aynı adrese TEK e-posta
        // UG'deki kaydın webhook'u SY'deki (webhook'suz) kayıt yüzünden düşmez.
        verify(webhookService).send(eq("TEAMS"), eq(B_HOOK), anyString(), anyString(), eq("CRITICAL"));
        db.removeIf(c -> Long.valueOf(20L).equals(c.getId()));

        Map<String, Object> r = service.simulateRecipients(TEAM_A, "CRITICAL", false, null, TEAM_B);
        List<Map<String, Object>> owners = (List<Map<String, Object>>) r.get("owners");
        assertThat(owners).extracting(o -> o.get("role")).containsExactly("SY", "UG");
        assertThat(owners.get(0)).containsEntry("team_id", TEAM_A).containsEntry("contacts_missing", true)
                .containsEntry("contacts_defined", false);
        assertThat(owners.get(1)).containsEntry("team_id", TEAM_B).containsEntry("contacts_missing", false);
        assertThat((List<Map<String, Object>>) r.get("team_emails")).extracting(m -> m.get("email"))
                .containsExactly(A_MAIL, B_MAIL);
        assertThat((List<Map<String, Object>>) r.get("contacts")).extracting(m -> m.get("email")).containsExactly(B_MANAGER);
        // Bağımsız izleme (MONITOR) senaryosunda UG yok sayılır — yalnız A.
        Map<String, Object> mon = service.simulateRecipients(TEAM_A, "CRITICAL", true, null, TEAM_B);
        assertThat((List<?>) mon.get("owners")).hasSize(1);
        assertThat((List<?>) mon.get("contacts")).isEmpty();
    }

    // ── Sahipsiz kayıt: hiçbir kanaldan bildirim yok, olay kaydı durur ────────────────────────────────

    @Test
    @DisplayName("Sahipsiz (SY/UG yok): olay kaydedilir ama e-posta, webhook, push ve 7/24 YOK; tekrar bildir 409 gerekçeli")
    void unowned_noNotificationOnAnyChannel() {
        inventory(DOMAIN, null, null);

        service.processResults(List.of(hijackedResult(DOMAIN)));

        AlertEvent saved = lastSavedEvent();
        assertThat(saved.getAlertType()).isEqualTo(EscalationService.TYPE_HOSTNAME_MISMATCH);   // kayıt durur
        verify(emailService, never()).sendAlert(any(String[].class), any(), any(), any(), any(), any(), any(), any());
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
        verify(userPushService, never()).enqueueAlert(any(), any(), any(), any(), any());
        verify(noc, never()).onAlertDispatched(any(), any(), any(), any(), any(), any(), any());

        AlertEvent open = openEvent(null, "CRITICAL");
        when(alertEventRepo.findById(open.getId())).thenReturn(Optional.of(open));
        assertThatThrownBy(() -> service.reNotify(open.getId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Sahipsiz");
        assertThatThrownBy(() -> service.previewReNotify(open.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Sahipsiz alarmın çözümü de sessiz: ÇÖZÜLDÜ e-postası / push çözümü yok")
    void unowned_resolution_notSent() {
        inventory(DOMAIN, null, null);
        AlertEvent open = openEvent(null, "CRITICAL");
        when(alertEventRepo.findById(open.getId())).thenReturn(Optional.of(open));

        service.resolve(open.getId(), "Kişi A");

        verify(emailService, never()).sendResolutionAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(userPushService, never()).enqueueResolve(any(), any(), any());
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
    }

    // ── Envanter türevli Port / DNS / erişilebilirlik: envanter gibi yönlenir (SY + UG + her takımın kişisi) ──

    @Test
    @DisplayName("Envanter türevli PORT_DOWN (bağlamda team_id YOK): SY A + UG B adresleri + B'nin kendi müdürü; push A'ya")
    void inventoryDerivedPort_routesLikeInventory_withUg() {
        inventory("host.example.com", TEAM_A, TEAM_B);

        service.processConfirmedOutage("host.example.com", EscalationService.TYPE_PORT_DOWN, "CRITICAL",
                new HashMap<>(Map.of("port", 443, "protocol", "TCP")));

        assertThat(sentTo()).containsExactlyInAnyOrder(A_MAIL, B_MAIL, B_MANAGER);
        verify(userPushService).enqueueAlert(any(), eq("INITIAL"), eq(TEAM_A), any(), any());
        assertThat(lastSavedEvent().getTeamId()).isEqualTo(TEAM_A);
    }

    @Test
    @DisplayName("BAĞIMSIZ Port/DNS (bağlamda team_id = A) değişmez: host envanterde SY A + UG B olsa da yalnız A; UG ve B'nin müdürü YOK")
    void standalonePortAndDns_unchanged_noUg() {
        inventory("host.example.com", TEAM_A, TEAM_B);
        service.processConfirmedOutage("host.example.com", EscalationService.TYPE_PORT_DOWN, "CRITICAL",
                new HashMap<>(Map.of("team_id", TEAM_A, "port", 8443, "protocol", "TCP")));
        assertThat(sentTo()).containsExactly(A_MAIL);

        clearInvocations(emailService);
        inventory("dns.example.com", TEAM_A, TEAM_B);
        service.processConfirmedOutage("dns.example.com", EscalationService.TYPE_DNS_FAILURE, "CRITICAL",
                new HashMap<>(Map.of("team_id", TEAM_A, "record_type", "A")));
        assertThat(sentTo()).containsExactly(A_MAIL);
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
    }

    /**
     * 2026-09-28 sızıntı varyantı: bağımsız DNS izlemesinin DNS_CHANGED günlük yeniden uyarısı bağlamı
     * reconstructChangeCtx'ten kurar ve team_id taşımaz → eskiden envanter yoluna düşüp host'un envanterdeki UG'sine
     * (B) ve B'nin müdürüne gidiyordu. Açılış, yeniden uyarı, çözüm ve tekrar bildir YALNIZ izlemenin takımına (A).
     */
    @Test
    @DisplayName("Bağımsız DNS (takım A), host B'nin envanterinde: DNS_CHANGED açılış + günlük yeniden uyarı + çözüm + tekrar bildir YALNIZ A")
    void standaloneDnsChanged_allPathsOnlyOwnTeam() {
        team(TEAM_C, "Takım C", C_MAIL);
        inventory("dns.example.com", TEAM_C, TEAM_B);   // host başka takımların envanterinde (SY C, UG B)
        // Açılış bağlamı ÜRETİCİNİN yazdığı biçimde (D8): SchedulerService → DnsChange → MonitoringOutageService.changeCtx.
        Map<String, Object> openCtx = new HashMap<>(MonitoringOutageService.changeCtx(new MonitoringOutageService.DnsChange(
                "dns.example.com", "A", "1.2.3.4", "5.6.7.8", "2026-09-28T10:00:00", TEAM_A, null, null, null, null, Boolean.TRUE)));
        assertThat(openCtx).containsEntry("team_id", TEAM_A).containsEntry("standalone", true);

        service.processConfirmedOutage("dns.example.com", EscalationService.TYPE_DNS_CHANGED, "HIGH", openCtx);
        assertThat(sentTo()).containsExactly(A_MAIL);
        AlertEvent opened = lastSavedEvent();
        assertThat(opened.getTeamId()).isEqualTo(TEAM_A);
        assertThat(opened.getContextJson()).contains("\"team_id\"");

        // Günlük yeniden uyarı: bağlam team_id TAŞIMAZ (reconstructChangeCtx) — takım + bağımsızlık olaydan.
        clearInvocations(emailService, webhookService);
        opened.setId(8L);
        opened.setLastReAlertAt(ISO.format(Instant.now().minus(25, ChronoUnit.HOURS)));
        when(alertEventRepo.findOpenAlert("dns.example.com", EscalationService.TYPE_DNS_CHANGED)).thenReturn(Optional.of(opened));
        when(alertEventRepo.findById(8L)).thenReturn(Optional.of(opened));
        service.processConfirmedOutage("dns.example.com", EscalationService.TYPE_DNS_CHANGED, "HIGH",
                new HashMap<>(Map.of("record_type", "A", "old_values", List.of("1.2.3.4"), "new_values", List.of("5.6.7.8"))));
        assertThat(sentTo()).containsExactly(A_MAIL);
        verify(webhookService, never()).send(any(), any(), any(), any(), any());

        assertThat(service.previewReNotify(8L)).extracting(EscalationService.ReNotifyRecipient::email).containsExactly(A_MAIL);

        service.resolve(8L, "Kişi A");
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendResolutionAlert(to.capture(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(to.getValue()).containsExactly(A_MAIL);
    }

    @Test
    @DisplayName("O1: takımı BOŞ bağımsız DNS izlemesinin DNS_CHANGED alarmı (üretici bağlamı) host'un envanterindeki takıma GİTMEZ — sahipsiz, bildirim yok")
    void standaloneDnsChangeWithoutTeam_noNotification() {
        inventory("dns.example.com", TEAM_B, TEAM_C);
        Map<String, Object> ctx = new HashMap<>(MonitoringOutageService.changeCtx(new MonitoringOutageService.DnsChange(
                "dns.example.com", "A", "1.2.3.4", "5.6.7.8", "2026-09-28T10:00:00", null, null, null, null, null, Boolean.TRUE)));
        service.processConfirmedOutage("dns.example.com", EscalationService.TYPE_DNS_CHANGED, "HIGH", ctx);
        verify(emailService, never()).sendAlert(any(String[].class), any(), any(), any(), any(), any(), any(), any());
        verify(webhookService, never()).send(any(), any(), any(), any(), any());
        assertThat(lastSavedEvent().getTeamId()).isNull();
    }

    /**
     * Y1 (2026-09-28): "Taşı ve sil" açık alarmın damgasını hedefe (B) taşır; kaynak takım (A) silinmiştir. Sonraki
     * eskalasyon, günlük yeniden uyarı ve çözüm B'ye (adres + B'nin kişileri) gider — silinmiş takıma sessizce değil.
     */
    @Test
    @DisplayName("Y1: taşınıp kaynak takımı silinen açık EXPIRY alarmı — eskalasyon + yeniden uyarı + çözüm YENİ takıma gider")
    void movedOpenAlert_reachesNewTeam() {
        when(teamRepo.findById(TEAM_A)).thenReturn(Optional.empty());   // kaynak takım silindi
        inventory(DOMAIN, TEAM_B, null);
        AlertEvent e = openEvent(TEAM_B, "WARNING");   // moveAll damgayı A → B taşıdı
        e.setAlertType("EXPIRY");
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(e));
        when(alertEventRepo.findById(e.getId())).thenReturn(Optional.of(e));

        service.processResults(List.of(expiring(DOMAIN, 3)));   // 3 gün → KRİTİK: eskalasyon
        assertThat(sentTo()).containsExactlyInAnyOrder(B_MAIL, B_MANAGER);

        clearInvocations(emailService);
        e.setLastReAlertAt(ISO.format(Instant.now().minus(25, ChronoUnit.HOURS)));
        service.processResults(List.of(expiring(DOMAIN, 3)));   // günlük yeniden uyarı
        assertThat(sentTo()).containsExactlyInAnyOrder(B_MAIL, B_MANAGER);

        service.resolve(e.getId(), "Kişi B");
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendResolutionAlert(to.capture(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(to.getValue()).containsExactlyInAnyOrder(B_MAIL, B_MANAGER);
    }

    @Test
    @DisplayName("D3: SY ve UG kişileri AYNI webhook kanalını gösteriyorsa açılışta tek mesaj (çözüm ve fırtına gibi)")
    void syAndUg_sameWebhook_sentOnce() {
        EscalationContact aPo = contact(30L, TEAM_A, "po-a@example.com", "PO", "WARNING");
        aPo.setWebhookUrl(B_HOOK); aPo.setWebhookType("TEAMS");
        db.add(aPo);
        inventory(DOMAIN, TEAM_A, TEAM_B);
        service.processResults(List.of(hijackedResult(DOMAIN)));
        verify(webhookService, times(1)).send(eq("TEAMS"), eq(B_HOOK), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("D4: SY aktarımından sonra (damga eski SY A; envanter SY C + UG B) yeniden uyarı VE çözüm UG B'yi (adres + kendi müdürü) içerir")
    void afterSyTransfer_ugGetsBothRealertAndResolution() {
        team(TEAM_C, "Takım C", C_MAIL);
        inventory(DOMAIN, TEAM_C, TEAM_B);
        AlertEvent e = openEvent(TEAM_A, "CRITICAL");   // açılışta SY A idi; sonra envanter C'ye aktarıldı
        e.setAlertType("EXPIRY");
        e.setLastReAlertAt(ISO.format(Instant.now().minus(25, ChronoUnit.HOURS)));
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(e));
        when(alertEventRepo.findById(e.getId())).thenReturn(Optional.of(e));

        service.processResults(List.of(expiring(DOMAIN, 3)));
        assertThat(sentTo()).containsExactlyInAnyOrder(A_MAIL, B_MAIL, B_MANAGER);

        service.resolve(e.getId(), "Kişi A");
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendResolutionAlert(to.capture(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(to.getValue()).containsExactlyInAnyOrder(A_MAIL, B_MAIL, B_MANAGER);
    }

    @Test
    @DisplayName("Takımı BOŞ bağımsız Port (bağlamda standalone işareti), host B'nin envanterinde: envantere düşmez → sahipsiz, hiç bildirim yok; çözümü de")
    void standaloneWithoutTeam_neverBorrowsInventoryTeam() {
        inventory("host.example.com", TEAM_B, null);
        service.processConfirmedOutage("host.example.com", EscalationService.TYPE_PORT_DOWN, "CRITICAL",
                new HashMap<>(Map.of("standalone", true, "port", 8443, "protocol", "TCP")));
        verify(emailService, never()).sendAlert(any(String[].class), any(), any(), any(), any(), any(), any(), any());
        verify(userPushService, never()).enqueueAlert(any(), any(), any(), any(), any());

        AlertEvent e = openEvent(null, "CRITICAL");
        e.setDomain("host.example.com"); e.setAlertType(EscalationService.TYPE_PORT_DOWN);
        e.setContextJson("{\"standalone\":true,\"port\":8443}");
        when(alertEventRepo.findById(e.getId())).thenReturn(Optional.of(e));
        service.resolve(e.getId(), "Kişi A");
        verify(emailService, never()).sendResolutionAlert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(e.getTeamId()).isNull();   // envanterden B ile geri doldurulmaz
    }

    @Test
    @DisplayName("Envanter türevli DNS_FAILURE (team_id YOK) ve ACCESSIBILITY: SY + UG adresleri + UG'nin kendi müdürü")
    void inventoryDerivedDnsAndAccessibility_reachSyAndUg() {
        inventory("dns.example.com", TEAM_A, TEAM_B);
        service.processConfirmedOutage("dns.example.com", EscalationService.TYPE_DNS_FAILURE, "CRITICAL",
                new HashMap<>(Map.of("record_type", "A")));
        assertThat(sentTo()).containsExactlyInAnyOrder(A_MAIL, B_MAIL, B_MANAGER);

        clearInvocations(emailService);
        inventory("web.example.com", TEAM_A, TEAM_B);
        service.processConfirmedOutage("web.example.com", EscalationService.TYPE_ACCESSIBILITY, "CRITICAL",
                new HashMap<>(Map.of("port", 443)));
        assertThat(sentTo()).containsExactlyInAnyOrder(A_MAIL, B_MAIL, B_MANAGER);
    }

    @Test
    @DisplayName("Türev Port alarmının ÇÖZÜMÜ ve TEKRAR BİLDİR önizlemesi açılışla aynı: SY + UG + UG'nin kendi müdürü")
    void inventoryDerivedPort_resolutionAndReNotify_sameAsOpening() {
        inventory("host.example.com", TEAM_A, TEAM_B);
        AlertEvent e = openEvent(TEAM_A, "CRITICAL");
        e.setDomain("host.example.com"); e.setAlertType(EscalationService.TYPE_PORT_DOWN);
        e.setContextJson("{\"port\":443,\"protocol\":\"TCP\",\"monitor_id\":5}");   // damgasız (türev)
        when(alertEventRepo.findById(e.getId())).thenReturn(Optional.of(e));

        assertThat(service.previewReNotify(e.getId())).extracting(EscalationService.ReNotifyRecipient::email)
                .containsExactlyInAnyOrder(A_MAIL, B_MAIL, B_MANAGER);

        service.resolve(e.getId(), "Kişi A");
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService).sendResolutionAlert(to.capture(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(to.getValue()).containsExactlyInAnyOrder(A_MAIL, B_MAIL, B_MANAGER);
    }

    // ── yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private List<EscalationContact> active(java.util.function.Predicate<EscalationContact> p) {
        return db.stream().filter(c -> Boolean.TRUE.equals(c.getActive())).filter(p).toList();
    }

    private void team(long id, String name, String email) {
        Team t = new Team();
        t.setId(id); t.setName(name); t.setEmail(email);
        when(teamRepo.findById(id)).thenReturn(Optional.of(t));
    }

    private void inventory(String domain, Long sy, Long ug) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setTeamId(sy); i.setUgTeamId(ug);
        inventory.put(domain, i);
    }

    private static EscalationContact contact(long id, Long teamId, String email, String role, String minLevel) {
        EscalationContact c = new EscalationContact();
        c.setId(id); c.setTeamId(teamId); c.setName("Kişi " + id); c.setEmail(email);
        c.setRole(role); c.setMinAlertLevel(minLevel); c.setActive(true);
        return c;
    }

    private AlertEvent openEvent(Long teamId, String level) {
        AlertEvent e = new AlertEvent();
        e.setId(7L); e.setDomain(DOMAIN); e.setAlertType(EscalationService.TYPE_HOSTNAME_MISMATCH);
        e.setAlertLevel(level); e.setTeamId(teamId); e.setResolved(false); e.setAcknowledged(false);
        e.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.DAYS)));
        e.setLastReAlertAt(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        return e;
    }

    private Map<String, Object> hijackedResult(String domain) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", domain); r.put("status", "valid"); r.put("warning", false); r.put("days_remaining", 300);
        r.put("revocation_status", "VALID"); r.put("chain_status", "VALID"); r.put("deployment_status", "OK");
        r.put("san", List.of("baska.example.com"));   // istenen alan adını KAPSAMIYOR → HOSTNAME_MISMATCH (KRİTİK)
        r.put("trust_status", "TRUSTED");
        return r;
    }

    private Map<String, Object> expiring(String domain, int days) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", domain); r.put("status", "warning"); r.put("warning", true); r.put("days_remaining", days);
        r.put("revocation_status", "VALID"); r.put("chain_status", "VALID"); r.put("deployment_status", "OK");
        return r;
    }

    private AlertEvent lastSavedEvent() {
        ArgumentCaptor<AlertEvent> cap = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepo, atLeastOnce()).save(cap.capture());
        return cap.getValue();
    }

    /** Gönderilen TÜM alarm e-postalarının alıcıları (tek çağrı beklenir). */
    private List<String> sentTo() {
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService, atLeastOnce()).sendAlert(to.capture(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        List<String> out = new ArrayList<>();
        for (String[] a : to.getAllValues()) out.addAll(Arrays.asList(a));
        return out;
    }
}
