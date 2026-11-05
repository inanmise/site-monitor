package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.*;
import com.sitemonitor.service.forward.AlertForwardService;
import com.sitemonitor.service.forward.ForwardTarget;
import com.sitemonitor.service.noc.NocMonitorDirectory;
import com.sitemonitor.service.noc.NocType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Alarm iletimi UÇTAN UCA (2026-11-05, entegrasyon): GERÇEK {@link AlertForwardService} (H2'de gerçek iletim / takım / kullanıcı /
 * envanter satırları, gerçek anlık görüntü) + GERÇEK {@link EscalationService} gönderim kodu. Ajan testleri iki tarafı ayrı ayrı
 * sahte karşı tarafla sınadı; bu test sözleşmenin iki ucunun gerçekten birleştiğini kanıtlar: tür kapsamı (cert), belirli izleme
 * (cert alan adıyla eşleşme), seçilen üyeler, eşleşmeyen tür, duraklatılmış iletim, ayrı posta + bant + FORWARD satırı + damga
 * + push çağrısı. Gönderim dışı bağımlılıklar taklit.
 */
@DataJpaTest
@Import(AlertForwardService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlertForwardEndToEndTest {

    @Autowired AlertForwardService forwards;
    @Autowired AlertForwardRepository forwardRepo;
    @Autowired TeamRepository realTeams;
    @Autowired AppUserRepository realUsers;
    @Autowired CertificateInventoryRepository realInventory;

    // EscalationService'in gönderim dışı bağımlılıkları (taklit)
    AlertEventRepository alertEventRepo = mock(AlertEventRepository.class);
    AlertThresholdRepository thresholdRepo = mock(AlertThresholdRepository.class);
    EscalationContactRepository contactRepo = mock(EscalationContactRepository.class);
    CertificateInventoryRepository inventoryRepo = mock(CertificateInventoryRepository.class);
    EmailNotificationService emailService = mock(EmailNotificationService.class);
    NotificationLogRepository notificationLogRepo = mock(NotificationLogRepository.class);
    TeamRepository teamRepo = mock(TeamRepository.class);
    SmtpSettingsService smtpSettings = mock(SmtpSettingsService.class);
    AppSettingsService appSettings = mock(AppSettingsService.class);

    static final String DOMAIN = "vitrin.example.com";
    static final String NOC_NAME = "Ağ Operasyon Merkezi — 7/24 İzleme ve Olay Yönetimi Takımı";

    EscalationService escalation;
    ForwardFakes.Push push;
    AlertForwardService raw;
    long owner, noc, appTeam, httpOnly, paused;
    long ayse;
    long certId;

    Team team(String name, String email) {
        Team t = new Team();
        t.setName(name);
        t.setEmail(email);
        t.setActive(true);
        return realTeams.saveAndFlush(t);
    }

    Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @BeforeEach
    void setUp() {
        forwardRepo.deleteAll();
        realUsers.deleteAll();
        realInventory.deleteAll();
        realTeams.deleteAll();
        owner = team("Sipariş Sistemleri (Dijital Kanallar Uygulama Geliştirme Müdürlüğü)", "siparis@example.com").getId();
        noc = team(NOC_NAME, "noc@example.com").getId();
        appTeam = team("Uygulama Destek", "destek@example.com").getId();
        httpOnly = team("Yalnız HTTP", "http@example.com").getId();
        paused = team("Duraklatılmış Alıcı", "durdu@example.com").getId();
        AppUser u = new AppUser();
        u.setUsername("ayse");
        u.setDisplayName("Ayşe Yılmaz (Uygulama Destek / Kıdemli Uzman)");
        u.setEmail("ayse@example.com");
        u.setSystemRole("USER");
        u.setTeamId(appTeam);
        u.setActive(true);
        ayse = realUsers.saveAndFlush(u).getId();
        CertificateInventory c = new CertificateInventory();
        c.setDomain(DOMAIN);
        c.setTeamId(owner);
        c.setActive(true);
        certId = realInventory.saveAndFlush(c).getId();

        raw = AopTestUtils.getTargetObject(forwards);
        NocMonitorDirectory directory = mock(NocMonitorDirectory.class);
        when(directory.ofType(any())).thenReturn(List.of());
        when(directory.ofType(NocType.SSL)).thenReturn(List.of(
                new NocMonitorDirectory.Row(NocType.SSL, certId, DOMAIN, DOMAIN, owner, null, true, false, null, false)));
        when(directory.find(eq(NocType.SSL), eq(certId))).thenReturn(
                new NocMonitorDirectory.Row(NocType.SSL, certId, DOMAIN, DOMAIN, owner, null, true, false, null, false));
        ReflectionTestUtils.invokeMethod(raw, "setDirectory", directory);

        // Gönderim tarafı — EscalationForwardTest kurulumuyla aynı
        escalation = new EscalationService(alertEventRepo, thresholdRepo, contactRepo, inventoryRepo, emailService,
                mock(WeeklyAvailabilityReportService.class), mock(WebhookService.class), new ObjectMapper(), notificationLogRepo,
                mock(LatestCheckRepository.class), teamRepo, smtpSettings, mock(MaintenanceService.class), mock(StormService.class),
                mock(UserPushService.class), mock(DomainMonitorRepository.class), mock(DomainCheckRepository.class),
                mock(DnsRecordRepository.class), mock(PageCheckRepository.class), mock(NotificationGroupService.class), appSettings);
        ReflectionTestUtils.setField(escalation, "self", escalation);
        push = new ForwardFakes.Push();
        escalation.setForwarding(forwards, push);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(smtpSettings.getOrDefaults()).thenReturn(new com.sitemonitor.model.SmtpSettings());
        AlertThreshold t = new AlertThreshold();
        t.setWarningDays(30); t.setHighDays(15); t.setCriticalDays(7); t.setReAlertIntervalHours(24);
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.of(t));
        when(alertEventRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        when(alertEventRepo.setForwardEmailsIfNull(any(), any())).thenReturn(1);
        when(alertEventRepo.save(any())).thenAnswer(i -> {
            AlertEvent e = i.getArgument(0);
            if (e.getId() == null) e.setId(100L);
            return e;
        });
        when(emailService.sendAlert(any(String[].class), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn("SENT");
        Team ownerTeam = realTeams.findById(owner).orElseThrow();
        when(teamRepo.findById(owner)).thenReturn(Optional.of(ownerTeam));
        when(contactRepo.findByTeamIdAndActiveTrueOrderByRoleAsc(any())).thenReturn(List.of());
        when(contactRepo.findByTeamIdAndMinAlertLevelInAndActiveTrue(any(), anyList())).thenReturn(List.of());
        when(contactRepo.findByTeamIdAndMinAlertLevelAndActiveTrue(any(), anyString())).thenReturn(List.of());
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(DOMAIN);
        inv.setTeamId(owner);
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of(inv));
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.of(inv));
    }

    /** Ana bilgisayar adı uyuşmazlığı — sertifika (cert) türünde alarm açan sonuç. */
    private Map<String, Object> mismatch() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", DOMAIN); r.put("status", "valid"); r.put("warning", false); r.put("days_remaining", 300);
        r.put("revocation_status", "VALID"); r.put("chain_status", "VALID"); r.put("deployment_status", "OK");
        r.put("san", List.of("baska.example.com"));
        r.put("trust_status", "TRUSTED");
        return r;
    }

    private void rebuildSnapshot() {
        ReflectionTestUtils.invokeMethod(raw, "invalidateLocal");
        ReflectionTestUtils.invokeMethod(raw, "current");
    }

    private List<String[]> sends() {
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        verify(emailService, atLeastOnce()).sendAlert(to.capture(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any(), any());
        return to.getAllValues();
    }

    private List<NotificationLog> forwardLogs() {
        ArgumentCaptor<NotificationLog> c = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepo, atLeast(0)).save(c.capture());
        return c.getAllValues().stream().filter(l -> EscalationService.ROLE_FORWARD.equals(l.getRecipientRole())).toList();
    }

    @Test
    @DisplayName("iletim tanımlı değilken gerçek yönlendirme hiçbir şey eklemez — yalnız sahip postası")
    void noForwardNothingAdded() {
        rebuildSnapshot();
        escalation.processResults(List.of(mismatch()));
        assertThat(sends()).hasSize(1);
        assertThat(sends().get(0)).containsExactly("siparis@example.com");
        assertThat(forwardLogs()).isEmpty();
        assertThat(push.calls).isEmpty();
        verify(alertEventRepo, never()).setForwardEmailsIfNull(any(), any());
    }

    @Test
    @DisplayName("tür (cert) + belirli izleme (alan adı) + seçilen üye eşleşir; yalnız HTTP ve duraklatılmış iletim gitmez")
    void realRoutingDrivesRealSend() {
        forwards.create(body("owner_team_id", owner, "scope_kind", "TYPES", "scope_types", List.of("cert"),
                "recipient_kind", "TEAM", "recipient_team_id", noc), null, "yonetici");
        forwards.create(body("owner_team_id", owner, "scope_kind", "MONITORS",
                "scope_monitors", List.of(Map.of("type", "cert", "id", certId)),
                "recipient_kind", "MEMBERS", "recipient_team_id", appTeam), List.of(ayse), "yonetici");
        forwards.create(body("owner_team_id", owner, "scope_kind", "TYPES", "scope_types", List.of("http"),
                "recipient_kind", "TEAM", "recipient_team_id", httpOnly), null, "yonetici");
        AlertForwardService.Saved p = forwards.create(body("owner_team_id", owner, "scope_kind", "ALL",
                "recipient_kind", "TEAM", "recipient_team_id", paused), null, "yonetici");
        forwards.update(p.forward().getId(), body("enabled", false), null, "yonetici");
        rebuildSnapshot();

        List<ForwardTarget> targets = forwards.targetsFor(new com.sitemonitor.service.forward.AlertForwardRouting.AlertRef(
                List.of(owner), "HOSTNAME_MISMATCH", DOMAIN, null));
        assertThat(targets).extracting(ForwardTarget::recipientTeamId).containsExactly(noc, appTeam);

        escalation.processResults(List.of(mismatch()));

        List<String[]> to = sends();
        assertThat(to).hasSize(3);
        assertThat(to.get(0)).containsExactly("siparis@example.com");          // sahip postası değişmedi
        Set<String> forwarded = new HashSet<>();
        for (String[] a : to.subList(1, 3)) forwarded.addAll(List.of(a));
        assertThat(forwarded).containsExactlyInAnyOrder("noc@example.com", "ayse@example.com");
        assertThat(forwardLogs()).extracting(NotificationLog::getRecipientName)
                .containsExactlyInAnyOrder(NOC_NAME, "Uygulama Destek");
        assertThat(forwardLogs()).allSatisfy(l -> assertThat(l.getEmailStatus()).isEqualTo("SENT"));
        verify(alertEventRepo, atLeastOnce()).setForwardEmailsIfNull(eq(100L), anyString());
        assertThat(push.calls).singleElement().satisfies(c ->
                assertThat(c.targets()).extracting(ForwardTarget::recipientTeamId).containsExactly(noc, appTeam));
    }

    @Test
    @DisplayName("iletim silinince sonraki alarm iletilmez (gerçek görüntü yeniden kurulur)")
    void deletedForwardStops() {
        AlertForwardService.Saved s = forwards.create(body("owner_team_id", owner, "scope_kind", "ALL",
                "recipient_kind", "TEAM", "recipient_team_id", noc), null, "yonetici");
        rebuildSnapshot();
        assertThat(forwards.anyFor(List.of(owner))).isTrue();
        forwards.delete(s.forward().getId());
        rebuildSnapshot();
        assertThat(forwards.anyFor(List.of(owner))).isFalse();
        escalation.processResults(List.of(mismatch()));
        assertThat(sends()).hasSize(1);
        assertThat(forwardLogs()).isEmpty();
    }
}
