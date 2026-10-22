package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeeklyReport;
import com.sitemonitor.model.WeeklyReportMail;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeeklyReportImageRepository;
import com.sitemonitor.repository.WeeklyReportRepository;
import com.sitemonitor.service.WeeklyReportService.Actor;
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

import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-10-22 (WR-1, WR-2): PO onay belirteci gönderim geçmişine yazılmaz ve geçmiş okunurken (eski satırlar) maskelenir;
 * giden posta belirteci TAŞIR. Global yönetici olmayan aktörün başka takım için açtığı rapor sessizce kendi takımına
 * çevrilmez — 400 {@code VALIDATION_FAILED} alan {@code team_id}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyReportTokenRedactionTest {

    @Mock WeeklyReportRepository reportRepo;
    @Mock WeeklyReportImageRepository imageRepo;
    @Mock com.sitemonitor.repository.WeeklyReportMailRepository mailRepo;
    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock EmailNotificationService emailService;
    @Mock AppSettingsService appSettings;
    @Mock PermissionService permissionService;
    @Mock WeeklyReportKpiService kpiService;
    @Mock MonitoringWeeklyStatsService monitoringStatsService;

    private WeeklyReportService service;

    private static final Actor USER_T2 = new Actor(10L, "user2", "Kullanıcı İki", 2L, "USER");
    private static final Actor SCOPED_ADMIN_T2 = new Actor(12L, "mudur", "Müdür", 2L, "ADMIN", false);
    private static final Actor GLOBAL_ADMIN = new Actor(1L, "admin", "Admin", null, "ADMIN");

    @BeforeEach
    void setUp() {
        service = new WeeklyReportService(reportRepo, imageRepo, mailRepo, teamRepo, userRepo,
                contactRepo, emailService, new ObjectMapper(), appSettings, permissionService, kpiService, monitoringStatsService,
                org.mockito.Mockito.mock(com.sitemonitor.repository.DomainMonitorRepository.class),
                org.mockito.Mockito.mock(com.sitemonitor.repository.DomainCheckRepository.class));
        ReflectionTestUtils.setField(service, "imageMaxBytes", 2L * 1024 * 1024);
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString("site.monitor.app.base-url", "http://localhost:5173")).thenReturn("https://sm.test");
        ReflectionTestUtils.setField(service, "appBaseUrl", "http://localhost:5173");
        when(reportRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        WeeklyReportClaimStubs.allWin(reportRepo);
        when(mailRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(emailService.fromAddress()).thenReturn("sitemonitor@test");
        Team t = new Team();
        t.setId(2L); t.setName("TakimA"); t.setEmail("takim@test.com"); t.setWeeklyReportsEnabled(true);
        when(teamRepo.findById(2L)).thenReturn(Optional.of(t));
        Team b = new Team();
        b.setId(7L); b.setName("TakimB"); b.setWeeklyReportsEnabled(true);
        when(teamRepo.findById(7L)).thenReturn(Optional.of(b));
        when(emailService.sendHtml(any(), any(), anyString(), anyString(), any())).thenReturn("SENT");
        // PO postası: gerçek şablon gibi onay bağlantısını (10. argüman) gövdeye yazar.
        when(emailService.buildWeeklyReportHtml(any(), any(), any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> "<p>Rapor</p><a class=\"btn-full\" href=\"" + inv.getArgument(9) + "\">Onayla</a>"
                        + "<img src=\"cid:img5\">");
        when(imageRepo.findByReportIdOrderByIdAsc(anyLong())).thenReturn(List.of());
        EscalationContact po = new EscalationContact();
        po.setRole("PO"); po.setName("PO İki"); po.setEmail("po@test.com"); po.setActive(true);
        when(contactRepo.findByTeamIdAndRoleAndActiveTrue(2L, "PO")).thenReturn(List.of(po));
    }

    private static WeeklyReport draft() {
        WeeklyReport r = new WeeklyReport();
        r.setId(5L); r.setTeamId(2L);
        var today = WeeklyReportService.today();
        r.setReportYear(today.get(WeekFields.ISO.weekBasedYear()));
        r.setWeekNo(today.get(WeekFields.ISO.weekOfWeekBasedYear()));
        r.setWeekLabel("2026-W43");
        r.setStatus("DRAFT");
        r.setVersion(1);
        r.setContentJson(WeeklyReportService.DEFAULT_TEMPLATE_JSON);
        return r;
    }

    @Test
    @DisplayName("submit: PO'ya giden posta belirteci TAŞIR; SUBMIT_PO kaydının gövdesinde belirteç YOK (maskeli)")
    void submit_storesRedactedBody_sendsRealToken() {
        WeeklyReport r = draft();
        when(reportRepo.findById(5L)).thenReturn(Optional.of(r));

        service.submit(5L, USER_T2);

        String token = r.getApprovalToken();
        assertThat(token).isNotBlank();
        ArgumentCaptor<String> sentHtml = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtml(any(), any(), anyString(), sentHtml.capture(), any());
        assertThat(sentHtml.getValue()).contains("/api/weekly-reports/approve-link?token=" + token);

        ArgumentCaptor<WeeklyReportMail> stored = ArgumentCaptor.forClass(WeeklyReportMail.class);
        verify(mailRepo).save(stored.capture());
        assertThat(stored.getValue().getBodyHtml())
                .doesNotContain(token)
                .contains("/api/weekly-reports/approve-link?token=" + WeeklyReportService.REDACTED_TOKEN);
    }

    @Test
    @DisplayName("mails(): düzeltmeden ÖNCE yazılmış satırdaki belirteç okunurken maskelenir; cid görseli /api'ye; kayıt nesnesi değişmez")
    void mails_redactsLegacyRows_andNeverMutatesEntity() {
        when(reportRepo.findById(5L)).thenReturn(Optional.of(draft()));
        WeeklyReportMail legacy = new WeeklyReportMail();
        legacy.setId(31L); legacy.setReportId(5L); legacy.setMailType("SUBMIT_PO"); legacy.setStatus("SENT");
        legacy.setToAddresses("po@test.com"); legacy.setSubject("onay");
        String raw = "<a href=\"https://sm.test/api/weekly-reports/approve-link?token=Ab-9_xyzSECRETtoken12\">Onayla</a>"
                + "<form action=\"/api/weekly-reports/approve-link/confirm?token=Zz_SECRET2\"></form><img src=\"cid:img5\">";
        legacy.setBodyHtml(raw);
        when(mailRepo.findByReportIdOrderByIdDesc(5L)).thenReturn(List.of(legacy));

        List<WeeklyReportMail> out = service.mails(5L, USER_T2);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getBodyHtml())
                .doesNotContain("SECRET")
                .contains("approve-link?token=" + WeeklyReportService.REDACTED_TOKEN)
                .contains("approve-link/confirm?token=" + WeeklyReportService.REDACTED_TOKEN)
                .contains("/api/weekly-reports/images/5");
        assertThat(out.get(0).getId()).isEqualTo(31L);
        assertThat(out.get(0).getStatus()).isEqualTo("SENT");
        assertThat(legacy.getBodyHtml()).as("depodan okunan nesne değişmez").isEqualTo(raw);
        verify(mailRepo, never()).save(any());
    }

    @Test
    @DisplayName("redactApprovalTokens: belirteçsiz gövde aynen; null güvenli; uzun gövde doğrusal")
    void redactHelper() {
        assertThat(WeeklyReportService.redactApprovalTokens(null)).isNull();
        assertThat(WeeklyReportService.redactApprovalTokens("<p>approve-link</p>")).isEqualTo("<p>approve-link</p>");
        String big = "x".repeat(2_000_000) + "approve-link?token=abc";
        assertThat(WeeklyReportService.redactApprovalTokens(big)).endsWith("approve-link?token=" + WeeklyReportService.REDACTED_TOKEN);
    }

    @Test
    @DisplayName("WR-2 / K8: kapsamlı müdür ya da kullanıcı BAŞKA takım için rapor açamaz → 400 VALIDATION_FAILED alan team_id; kendi takımı ve global yönetici açar")
    void create_foreignTeam_rejectedWithFieldError() {
        assertThatThrownBy(() -> service.create(7L, 2026, 40, SCOPED_ADMIN_T2))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class)
                .satisfies(e -> assertThat(((GlobalExceptionHandler.FieldValidationException) e).getFields()).containsKey("team_id"));
        assertThatThrownBy(() -> service.create(7L, 2026, 40, USER_T2))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        verify(reportRepo, never()).save(any());

        when(reportRepo.findByTeamIdAndReportYearAndWeekNo(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Optional.empty());
        assertThat(service.create(2L, 2026, 40, SCOPED_ADMIN_T2).getTeamId()).isEqualTo(2L);
        assertThat(service.create(null, 2026, 41, USER_T2).getTeamId()).isEqualTo(2L);
        assertThat(service.create(7L, 2026, 40, GLOBAL_ADMIN).getTeamId()).isEqualTo(7L);
    }
}
