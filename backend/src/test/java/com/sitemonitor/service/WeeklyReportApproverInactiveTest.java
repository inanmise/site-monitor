package com.sitemonitor.service;

import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeeklyReportImageRepository;
import com.sitemonitor.repository.WeeklyReportMailRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Haftalık rapor e-posta onay bağlantısı — pasif hesap kapısı (2026-10-02, kullanıcı kararı: pasif hesap hiçbir yoldan
 * işlem yapamaz). Belirteç rapora bağlıdır; "sahibi" raporun son {@code SUBMIT_PO} postasının alıcılarıdır. Alıcıların
 * HEPSİ pasifse (yalnız pasife ait adres ya da pasif kullanıcıya bağlı PO kişisi) onay/iade reddedilir; aktif bir alıcı
 * ya da kullanıcıya ait olmayan adres bağlantıyı açık tutar; aktif onaylayanın akışı DEĞİŞMEZ.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyReportApproverInactiveTest {

    @Mock WeeklyReportRepository reportRepo;
    @Mock WeeklyReportImageRepository imageRepo;
    @Mock WeeklyReportMailRepository mailRepo;
    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock EmailNotificationService emailService;
    @Mock AppSettingsService appSettings;
    @Mock PermissionService permissionService;
    @Mock WeeklyReportKpiService kpiService;
    @Mock MonitoringWeeklyStatsService monitoringStatsService;
    @Mock InactiveRecipientGuard guard;

    private WeeklyReportService service;
    private WeeklyReport report;

    @BeforeEach
    void setUp() {
        service = new WeeklyReportService(reportRepo, imageRepo, mailRepo, teamRepo, userRepo,
                contactRepo, emailService, new ObjectMapper(), appSettings, permissionService, kpiService, monitoringStatsService,
                mock(com.sitemonitor.repository.DomainMonitorRepository.class),
                mock(com.sitemonitor.repository.DomainCheckRepository.class));
        service.setInactiveGuard(guard);
        when(guard.withoutInactive(any(), any())).thenAnswer(i -> i.getArgument(0));   // kişi süzgeci: bu süitte dokunmaz
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(mailRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(emailService.fromAddress()).thenReturn("sitemonitor@test");
        when(emailService.sendHtml(any(), any(), anyString(), anyString(), any())).thenReturn("SENT");
        when(emailService.buildWeeklyReportRejectedHtml(any(), any(), any(), any())).thenReturn("<html/>");
        when(emailService.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenReturn("<html/>");
        when(imageRepo.findByReportIdOrderByIdAsc(anyLong())).thenReturn(List.of());
        Team t = new Team();
        t.setId(2L); t.setName("TakimA"); t.setEmail("takim@test.com"); t.setWeeklyReportsEnabled(true);
        when(teamRepo.findById(2L)).thenReturn(Optional.of(t));
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "MANAGER"))
                .thenReturn(List.of(contact(90L, "MANAGER", "mudur@test.com", null)));
        when(userRepo.findActiveEmailsLowerIn(any())).thenReturn(List.of());

        report = new WeeklyReport();
        report.setId(5L); report.setTeamId(2L); report.setStatus("PENDING_APPROVAL");
        report.setWeekLabel("2026-W40"); report.setVersion(1);
        report.setContentJson(WeeklyReportService.DEFAULT_TEMPLATE_JSON);
        report.setApprovalToken("T1");
        report.setApprovalTokenExpiresAt(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(ZoneOffset.UTC).format(Instant.now().plusSeconds(86_400)));
        when(reportRepo.findByApprovalToken("T1")).thenReturn(Optional.of(report));
    }

    private static EscalationContact contact(Long id, String role, String email, Long userId) {
        EscalationContact c = new EscalationContact();
        c.setId(id); c.setRole(role); c.setName(role + " kişi"); c.setEmail(email); c.setActive(true); c.setTeamId(2L);
        c.setUserId(userId);
        return c;
    }

    private void linkSentTo(String toAddresses) {
        when(mailRepo.findToAddressesByReportIdAndType(5L, "SUBMIT_PO")).thenReturn(List.of(toAddresses));
    }

    @Test
    @DisplayName("Tek alıcı YALNIZ pasif kullanıcıya ait → onay reddedilir (ApproverInactiveException), rapor değişmez, müdüre posta gitmez")
    void passiveApprover_approveRefused() {
        linkSentTo("po@test.com");
        when(guard.isInactiveOnlyEmail("po@test.com")).thenReturn(true);

        assertThatThrownBy(() -> service.approveViaToken("T1"))
                .isInstanceOf(WeeklyReportService.ApproverInactiveException.class)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pasif");
        assertThat(report.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(report.getApprovalToken()).isEqualTo("T1");
        verify(emailService, never()).sendHtml(any(), any(), anyString(), anyString(), any());
        verify(reportRepo, never()).save(any());
    }

    @Test
    @DisplayName("Pasif sahibin bağlantısıyla İADE de reddedilir")
    void passiveApprover_rejectRefused() {
        linkSentTo("po@test.com");
        when(guard.isInactiveOnlyEmail("po@test.com")).thenReturn(true);

        assertThatThrownBy(() -> service.rejectViaToken("T1", "not"))
                .isInstanceOf(WeeklyReportService.ApproverInactiveException.class);
        assertThat(report.getStatus()).isEqualTo("PENDING_APPROVAL");
        verify(emailService, never()).sendHtml(any(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("GET sayfası durumu: valid=false, reason=approver_inactive, report_id taşınır (denetim için)")
    void passiveApprover_statusPage() {
        linkSentTo("po@test.com");
        when(guard.isInactiveOnlyEmail("po@test.com")).thenReturn(true);

        Map<String, Object> st = service.approvalTokenStatus("T1");

        assertThat(st.get("valid")).isEqualTo(false);
        assertThat(st.get("reason")).isEqualTo("approver_inactive");
        assertThat(st.get("report_id")).isEqualTo(5L);
    }

    @Test
    @DisplayName("Kişi düzeyi (user_id): adres aktif bir kullanıcıya ait değil ama PO kişisi pasif kullanıcıya bağlı → reddedilir")
    void passiveLinkedContact_refused() {
        linkSentTo("po-kisi@test.com");
        EscalationContact po = contact(70L, "PO", "PO-Kisi@test.com", 44L);
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "PO")).thenReturn(List.of(po));
        when(guard.isInactiveOnlyEmail("po-kisi@test.com")).thenReturn(false);
        when(guard.isInactiveContact(po)).thenReturn(true);

        assertThatThrownBy(() -> service.approveViaToken("T1"))
                .isInstanceOf(WeeklyReportService.ApproverInactiveException.class);
    }

    @Test
    @DisplayName("Pasif kişiye bağlı adresi AKTİF bir kullanıcı da kullanıyorsa bağlantı açık kalır")
    void passiveLinkedContact_butActiveOwner_allowed() {
        linkSentTo("ortak@test.com");
        EscalationContact po = contact(70L, "PO", "ortak@test.com", 44L);
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "PO")).thenReturn(List.of(po));
        when(guard.isInactiveContact(po)).thenReturn(true);
        when(userRepo.findActiveEmailsLowerIn(List.of("ortak@test.com"))).thenReturn(List.of("ortak@test.com"));

        service.approveViaToken("T1");

        assertThat(report.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("Aktif onaylayan: akış DEĞİŞMEZ — rapor onaylanır, token temizlenir, sayfa geçerli")
    void activeApprover_unchanged() {
        linkSentTo("po@test.com");
        when(guard.isInactiveOnlyEmail("po@test.com")).thenReturn(false);
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "PO"))
                .thenReturn(List.of(contact(70L, "PO", "po@test.com", 13L)));
        when(guard.isInactiveContact(any())).thenReturn(false);

        assertThat(service.approvalTokenStatus("T1").get("valid")).isEqualTo(true);
        Map<String, Object> out = service.approveViaToken("T1");

        assertThat(report.getStatus()).isEqualTo("APPROVED");
        assertThat(report.getApprovalToken()).isNull();
        assertThat(out.get("mail_status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("Alıcılardan biri pasif, biri aktif → kimin tıkladığı bilinemez, bağlantı açık kalır")
    void mixedRecipients_allowed() {
        linkSentTo("po@test.com, po2@test.com");
        when(guard.isInactiveOnlyEmail("po@test.com")).thenReturn(true);
        when(guard.isInactiveOnlyEmail("po2@test.com")).thenReturn(false);

        service.approveViaToken("T1");

        assertThat(report.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("Alıcı kaydı yok (eski rapor) ya da süzgeç yok → davranış bugünküyle aynı")
    void noRecord_orNoGuard_unchanged() {
        when(mailRepo.findToAddressesByReportIdAndType(5L, "SUBMIT_PO")).thenReturn(List.of());
        assertThat(service.approvalTokenStatus("T1").get("valid")).isEqualTo(true);

        linkSentTo("po@test.com");
        when(guard.isInactiveOnlyEmail("po@test.com")).thenReturn(true);
        service.setInactiveGuard(null);
        assertThat(service.approvalTokenStatus("T1").get("valid")).isEqualTo(true);
    }

    @Test
    @DisplayName("Okuma hatası bağlantıyı KİLİTLEMEZ (fail-open)")
    void readError_failOpen() {
        when(mailRepo.findToAddressesByReportIdAndType(eq(5L), eq("SUBMIT_PO"))).thenThrow(new RuntimeException("db"));
        assertThat(service.approvalTokenStatus("T1").get("valid")).isEqualTo(true);
    }
}
