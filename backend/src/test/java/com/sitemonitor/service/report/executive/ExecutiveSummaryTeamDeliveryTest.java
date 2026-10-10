package com.sitemonitor.service.report.executive;

import com.sitemonitor.model.ExecutiveSummaryReport;
import com.sitemonitor.model.ExecutiveSummaryTeamReport;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.ExecutiveSummaryReportRepository;
import com.sitemonitor.repository.ExecutiveSummaryTeamReportRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import com.sitemonitor.service.InactiveRecipientGuard;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sitemonitor.service.report.executive.ExecTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Takım özeti gönderimi (2026-10-10): kurum özetinden bağımsız açılır; açık her aktif takım kendi kaydıyla tam bir kez;
 * bir takımın hatası diğerlerini durdurmaz; telafi yalnız yeniden denenebilir takımı alır; konu/ek/iz takım adını taşır;
 * eski takım kayıtları budanır. Posta servisi SAHTE.
 */
class ExecutiveSummaryTeamDeliveryTest {

    private final ExecutiveSummaryService summaryService = mock(ExecutiveSummaryService.class);
    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final ExecutiveSummaryReportRepository repo = mock(ExecutiveSummaryReportRepository.class);
    private final ExecutiveSummaryTeamReportRepository teamRepo = mock(ExecutiveSummaryTeamReportRepository.class);
    private final ExecutiveSummaryTeamService teamService = mock(ExecutiveSummaryTeamService.class);
    private final EmailNotificationService email = mock(EmailNotificationService.class);
    private final NotificationLogRepository logs = mock(NotificationLogRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final UserService userService = mock(UserService.class);
    private ExecutiveSummaryDeliveryService svc;

    @BeforeEach
    void setUp() {
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        ExecutiveSummarySettings settings = new ExecutiveSummarySettings(appSettings);   // kurum özeti KAPALI (varsayılan)
        svc = new ExecutiveSummaryDeliveryService(summaryService, settings, repo, email, logs, users, userService, appSettings);
        svc.setInactiveGuard(mock(InactiveRecipientGuard.class));
        svc.setTeamParts(teamService, teamRepo);
        when(summaryService.now()).thenReturn(NOW);
        when(summaryService.currentMonth()).thenReturn(YearMonth.of(2026, 10));
        when(summaryService.defaultMonth()).thenReturn(YearMonth.of(2026, 9));
        when(summaryService.newRunShared()).thenAnswer(i -> new java.util.concurrent.ConcurrentHashMap<String, Object>());
        when(summaryService.teamNames()).thenReturn(Map.of(5L, "Ödeme Sistemleri", 6L, "Ağ"));
        when(summaryService.compute(any(), anyLong(), any())).thenAnswer(i -> sample(i.getArgument(1)));
        when(repo.findByReportYearAndReportMonth(anyInt(), anyInt())).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(teamRepo.findByTeamIdAndReportYearAndReportMonth(anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());
        when(teamRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(email.sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList())).thenReturn("SENT");
        when(users.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of());
        when(teamService.enabledTeamIds()).thenReturn(List.of(5L, 6L));
        when(teamService.anyEnabled()).thenReturn(true);
        Map<Long, ExecutiveSummaryTeamService.Recipients> rc = new LinkedHashMap<>();
        rc.put(5L, rc("mudur@example.com", "ayse@example.com"));
        rc.put(6L, rc("ag@example.com"));
        when(teamService.recipientsFor(any())).thenReturn(rc);
    }

    private static ExecutiveSummaryTeamService.Recipients rc(String... emails) {
        return new ExecutiveSummaryTeamService.Recipients(List.of(emails), 1, 0, emails.length - 1, 0, 0, List.of());
    }

    static ExecutiveSummary sample(Long teamId) {
        SectionResult sec = SectionResult.builder("availability", 10, "Erişilebilirlik hedefi uyumu")
                .status(SectionResult.OK).verdict("V", SectionResult.T_OK, "hüküm").build();
        String name = teamId != null && teamId == 5L ? "Ödeme Sistemleri" : "Ağ";
        return new ExecutiveSummary("2026-09", "Eylül 2026", "2026-08-31T21:00:00", "2026-09-30T21:00:00", true,
                "2026-10-10T09:00:00", ExecutiveSummary.SOURCE_LIVE, SectionResult.OK, sec.verdicts(), List.of(),
                List.of(sec), Map.of("availability_target", 99.9),
                teamId == null ? ExecutiveSummary.Scope.ORG : ExecutiveSummary.Scope.team(teamId, name));
    }

    @Test
    @DisplayName("kurum özeti KAPALI iken açık takımlar yine gider: her takım kendi alıcısına, kendi kaydıyla; konu/ek/iz takım adlı")
    void teamsIndependentOfOrg() {
        ExecutiveSummaryDeliveryService.Result r = svc.runScheduled();
        assertThat(r.status()).isEqualTo("DISABLED");                       // kurum sonucu (değişmedi)

        ArgumentCaptor<String[]> bcc = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailNotificationService.MailAttachment>> att = ArgumentCaptor.forClass(List.class);
        verify(email, times(2)).sendHtmlBccWithAttachments(bcc.capture(), subject.capture(), anyString(), anyString(), att.capture());
        assertThat(bcc.getAllValues().get(0)).containsExactly("mudur@example.com", "ayse@example.com");
        assertThat(bcc.getAllValues().get(1)).containsExactly("ag@example.com");
        assertThat(subject.getAllValues().get(0)).startsWith("[Site Monitor] Aylık Yönetici Özeti · Ödeme Sistemleri · Eylül 2026");
        assertThat(att.getAllValues().get(0).get(0).fileName()).isEqualTo("site-monitor-yonetici-ozeti-odeme-sistemleri-2026-09.pdf");

        ArgumentCaptor<ExecutiveSummaryTeamReport> rows = ArgumentCaptor.forClass(ExecutiveSummaryTeamReport.class);
        verify(teamRepo, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(ExecutiveSummaryTeamReport::getTeamId).containsExactly(5L, 6L);
        assertThat(rows.getAllValues()).allSatisfy(x -> {
            assertThat(x.getStatus()).isEqualTo(ExecutiveSummaryTeamReport.SENT);
            assertThat(x.getReportMonth()).isEqualTo(9);
            assertThat(x.getSummaryJson()).contains("\"kind\":\"team\"");
        });
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs, times(2)).save(log.capture());
        assertThat(log.getAllValues().get(0).getRecipientName()).isEqualTo("Yönetici Özeti · Ödeme Sistemleri");
        // kurum kaydı yalnız SKIPPED_DISABLED izi; kurum özeti hesaplanmadı
        verify(summaryService, never()).compute(any(YearMonth.class));
        // koşu boyu paylaşım: iki takım AYNI haritayı aldı
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> shared = ArgumentCaptor.forClass(Map.class);
        verify(summaryService, times(2)).compute(eq(YearMonth.of(2026, 9)), anyLong(), shared.capture());
        assertThat(shared.getAllValues().get(0)).isSameAs(shared.getAllValues().get(1));
        // eski takım kayıtları budandı: 2026-10'dan 24 ay önce = 2024-10 → 2024*12+10
        verify(teamRepo).deleteOlderThan(2024 * 12 + 10);
    }

    @Test
    @DisplayName("tam bir kez (takım): gönderilmiş takım ayı tekrar gitmez; INSERT yarışını kaybeden takım posta göndermez, diğeri gider")
    void exactlyOncePerTeam() {
        ExecutiveSummaryTeamReport sent = new ExecutiveSummaryTeamReport();
        sent.setId(1L);
        sent.setTeamId(5L);
        sent.setStatus(ExecutiveSummaryTeamReport.SENT);
        sent.setAttempts(1);
        when(teamRepo.findByTeamIdAndReportYearAndReportMonth(5L, 2026, 9)).thenReturn(Optional.of(sent));
        when(teamRepo.saveAndFlush(argThat(r -> r != null && Long.valueOf(6L).equals(r.getTeamId()) && r.getId() == null)))
                .thenThrow(new DataIntegrityViolationException("uk_estr_team_year_month"));
        svc.runScheduled();
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("bir takımın hesabı patlarsa kaydı FAILED olur, sıradaki takım yine gider")
    void oneTeamFailureDoesNotStopOthers() {
        when(summaryService.compute(any(), eq(5L), any())).thenThrow(new IllegalStateException("patladı"));
        svc.runScheduled();
        ArgumentCaptor<String[]> bcc = ArgumentCaptor.forClass(String[].class);
        verify(email, times(1)).sendHtmlBccWithAttachments(bcc.capture(), anyString(), anyString(), anyString(), anyList());
        assertThat(bcc.getValue()).containsExactly("ag@example.com");
        ArgumentCaptor<ExecutiveSummaryTeamReport> rows = ArgumentCaptor.forClass(ExecutiveSummaryTeamReport.class);
        verify(teamRepo, times(2)).save(rows.capture());
        assertThat(rows.getAllValues()).extracting(ExecutiveSummaryTeamReport::getStatus)
                .containsExactly(ExecutiveSummaryTeamReport.FAILED, ExecutiveSummaryTeamReport.SENT);
    }

    @Test
    @DisplayName("alıcısı olmayan takım NO_RECIPIENT kaydı + 'SKIPPED: alıcı yok' izi; silinmiş/pasif takım atlanır")
    void noRecipientAndInactiveTeam() {
        Map<Long, ExecutiveSummaryTeamService.Recipients> rc = new LinkedHashMap<>();
        rc.put(5L, new ExecutiveSummaryTeamService.Recipients(List.of(), 0, 0, 0, 0, 0, List.of("NO_MANAGER")));
        when(teamService.recipientsFor(any())).thenReturn(rc);                 // 6 yok → pasif / silinmiş
        svc.runScheduled();
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());
        ArgumentCaptor<ExecutiveSummaryTeamReport> rows = ArgumentCaptor.forClass(ExecutiveSummaryTeamReport.class);
        verify(teamRepo, times(1)).save(rows.capture());
        assertThat(rows.getValue().getStatus()).isEqualTo(ExecutiveSummaryTeamReport.NO_RECIPIENT);
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).startsWith("SKIPPED: alıcı yok");
    }

    @Test
    @DisplayName("telafi: kurum kapalı + takım açık → yalnız kaydı olmayan/yeniden denenebilir takım gider; pencere dışı hiçbir şey")
    void catchUpTeamsOnly() {
        when(summaryService.now()).thenReturn(Instant.parse("2026-10-01T08:00:00Z"));   // planlı 06:00 UTC'den 2 saat sonra
        ExecutiveSummaryTeamReport failed = new ExecutiveSummaryTeamReport();
        failed.setId(2L);
        failed.setTeamId(6L);
        failed.setStatus(ExecutiveSummaryTeamReport.SENT);
        failed.setAttempts(1);
        when(teamRepo.findByTeamIdAndReportYearAndReportMonth(6L, 2026, 9)).thenReturn(Optional.of(failed));
        assertThat(svc.catchUp().status()).isEqualTo("DISABLED");              // kurum sonucu
        ArgumentCaptor<String[]> bcc = ArgumentCaptor.forClass(String[].class);
        verify(email, times(1)).sendHtmlBccWithAttachments(bcc.capture(), anyString(), anyString(), anyString(), anyList());
        assertThat(bcc.getValue()).containsExactly("mudur@example.com", "ayse@example.com");
        verify(repo, never()).saveAndFlush(any());                            // kurum kaydı yazılmadı

        when(summaryService.now()).thenReturn(Instant.parse("2026-10-05T08:00:00Z"));   // 72 saat geçti
        assertThat(svc.catchUp().status()).isEqualTo("WINDOW_CLOSED");
        when(teamService.anyEnabled()).thenReturn(false);
        assertThat(svc.catchUp().status()).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("takımın elle gönderimi gönderilmiş ayı yeniden gönderir (force); pasif takım TEAM_INACTIVE")
    void sendTeamNow() {
        ExecutiveSummaryTeamReport sent = new ExecutiveSummaryTeamReport();
        sent.setId(3L);
        sent.setTeamId(5L);
        sent.setStatus(ExecutiveSummaryTeamReport.SENT);
        sent.setAttempts(1);
        when(teamRepo.findByTeamIdAndReportYearAndReportMonth(5L, 2026, 9)).thenReturn(Optional.of(sent));
        when(teamRepo.reclaim(eq(3L), eq("SENT"), eq(1), eq(2), anyString(), eq("MANUAL"), eq("yonetici"))).thenReturn(1);
        when(teamService.teamName(5L)).thenReturn("Ödeme Sistemleri");
        ExecutiveSummaryDeliveryService.Result r = svc.sendTeamNow(5L, YearMonth.of(2026, 9), "yonetici");
        assertThat(r.status()).isEqualTo(ExecutiveSummaryTeamReport.SENT);
        assertThat(r.recipients()).isEqualTo(2);

        when(teamService.recipientsFor(any())).thenReturn(Map.of());
        assertThat(svc.sendTeamNow(5L, YearMonth.of(2026, 9), "yonetici").status()).isEqualTo("TEAM_INACTIVE");
    }

    @Test
    @DisplayName("takım yolu kurulmamışsa (bean yok) kurum yolu eskisi gibi; takım çağrısı yapılmaz")
    void withoutTeamParts() {
        ExecutiveSummaryDeliveryService plain = new ExecutiveSummaryDeliveryService(summaryService,
                new ExecutiveSummarySettings(appSettings), repo, email, logs, users, userService, appSettings);
        assertThat(plain.teamsAvailable()).isFalse();
        assertThat(plain.runScheduled().status()).isEqualTo("DISABLED");
        verifyNoInteractions(teamService);
        verify(repo, times(1)).saveAndFlush(any(ExecutiveSummaryReport.class));
    }
}
