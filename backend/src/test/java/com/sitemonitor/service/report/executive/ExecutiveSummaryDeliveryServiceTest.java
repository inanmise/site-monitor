package com.sitemonitor.service.report.executive;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.ExecutiveSummaryReport;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.ExecutiveSummaryReportRepository;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.sitemonitor.service.report.executive.ExecTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Gönderim — opt-in (varsayılan KAPALI), alıcılar (pasif düşer, global yönetici dahil, müdür hariç), BCC 100'lük dilimler,
 * "tam bir kez" talebi (UNIQUE yarışı, koşullu yeniden talep), iz (notification_logs), telafi penceresi, test postası.
 * Posta servisi SAHTE — gerçek posta gönderilmez.
 */
class ExecutiveSummaryDeliveryServiceTest {

    private final ExecutiveSummaryService summaryService = mock(ExecutiveSummaryService.class);
    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final ExecutiveSummaryReportRepository repo = mock(ExecutiveSummaryReportRepository.class);
    private final EmailNotificationService email = mock(EmailNotificationService.class);
    private final NotificationLogRepository logs = mock(NotificationLogRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final UserService userService = mock(UserService.class);
    private final InactiveRecipientGuard guard = mock(InactiveRecipientGuard.class);
    private ExecutiveSummarySettings settings;
    private ExecutiveSummaryDeliveryService svc;

    @BeforeEach
    void setUp() {
        // Ayar servisi varsayılanı geri döner → ExecutiveSummarySettings gerçek varsayılanlarıyla çalışır
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getDouble(anyString(), anyDouble())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        settings = new ExecutiveSummarySettings(appSettings);
        svc = new ExecutiveSummaryDeliveryService(summaryService, settings, repo, email, logs, users, userService, appSettings);
        svc.setInactiveGuard(guard);
        when(summaryService.now()).thenReturn(NOW);
        when(summaryService.currentMonth()).thenReturn(YearMonth.of(2026, 10));
        when(summaryService.defaultMonth()).thenReturn(YearMonth.of(2026, 9));
        when(summaryService.compute(any())).thenReturn(sample());
        when(repo.findByReportYearAndReportMonth(anyInt(), anyInt())).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(email.sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList())).thenReturn("SENT");
        when(users.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of());
    }

    static ExecutiveSummary sample() {
        SectionResult sec = SectionResult.builder("availability", 10, "Erişilebilirlik hedefi uyumu")
                .status(SectionResult.OK).headlineKpi("org_availability")
                .verdict("ORG_MET", SectionResult.T_OK, "Kurum erişilebilirliği %99,95 — hedef %99,9 karşılandı.", 99.95, 99.9)
                .kpi(new SectionResult.Kpi("org_availability", "Kurum erişilebilirliği", 99.95, "pct", SectionResult.T_OK,
                        "Hedef %99,9", List.of(99.9), 0.02, "pp", SectionResult.T_OK))
                .build();
        return new ExecutiveSummary("2026-09", "Eylül 2026", "2026-08-31T21:00:00", "2026-09-30T21:00:00", true,
                "2026-10-10T09:00:00", ExecutiveSummary.SOURCE_LIVE, SectionResult.OK, sec.verdicts(),
                List.of(new ExecutiveSummary.HeadlineKpi("availability", sec.kpis().get(0))), List.of(sec),
                Map.of("availability_target", 99.9, "renewal_target_days", 30));
    }

    private void enable(String recipientsCsv, boolean admins) {
        when(appSettings.getBoolean(eq(ExecutiveSummarySettings.ENABLED_KEY), anyBoolean())).thenReturn(true);
        when(appSettings.getBoolean(eq(ExecutiveSummarySettings.INCLUDE_ADMINS_KEY), anyBoolean())).thenReturn(admins);
        when(appSettings.getString(eq(ExecutiveSummarySettings.RECIPIENTS_KEY), any())).thenReturn(recipientsCsv);
    }

    private static AppUser user(String name, String role, String source, String mail) {
        AppUser u = new AppUser();
        u.setUsername(name);
        u.setSystemRole(role);
        u.setAuthSource(source);
        u.setEmail(mail);
        u.setActive(true);
        return u;
    }

    @Test
    @DisplayName("opt-in: varsayılan KAPALI → posta yok, ayın kaydına SKIPPED_DISABLED izi")
    void defaultOff() {
        assertThat(settings.enabled()).isFalse();
        ExecutiveSummaryDeliveryService.Result r = svc.runScheduled();
        assertThat(r.status()).isEqualTo("DISABLED");
        ArgumentCaptor<ExecutiveSummaryReport> row = ArgumentCaptor.forClass(ExecutiveSummaryReport.class);
        verify(repo).saveAndFlush(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(ExecutiveSummaryReport.SKIPPED_DISABLED);
        assertThat(row.getValue().getReportMonth()).isEqualTo(9);
        assertThat(row.getValue().getAttempts()).isZero();
        verifyNoInteractions(email);
        assertThat(svc.catchUp().status()).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("alıcılar: açık liste (tekil, pasif düşer) + aktif global yöneticiler; müdür (LDAP + kapsam) ve USER hariç")
    void recipients() {
        enable("a@x.com, A@X.com; passive@x.com bad-address", true);
        when(guard.isInactiveOnlyEmail("passive@x.com")).thenReturn(true);
        AppUser local = user("admin", "ADMIN", "LOCAL", "g1@x.com");
        AppUser scoped = user("mudur", "ADMIN", "LDAP", "m@x.com");
        AppUser ldapGlobal = user("ldapadmin", "ADMIN", "LDAP", "lg@x.com");
        AppUser plain = user("u", "USER", "LOCAL", "u@x.com");
        AppUser noMail = user("admin2", "ADMIN", "LOCAL", " ");
        AppUser dup = user("admin3", "ADMIN", "LOCAL", "A@x.com");
        when(userService.computeViewTeamIds(scoped)).thenReturn(List.of(1L));
        when(userService.computeViewTeamIds(ldapGlobal)).thenReturn(null);
        when(users.findByActiveTrueOrderByUsernameAsc()).thenReturn(List.of(local, scoped, ldapGlobal, plain, noMail, dup));

        ExecutiveSummaryDeliveryService.Recipients rc = svc.recipients();
        assertThat(rc.emails()).containsExactly("a@x.com", "g1@x.com", "lg@x.com");
        assertThat(rc.explicitCount()).isEqualTo(1);
        assertThat(rc.adminCount()).isEqualTo(2);
        assertThat(rc.droppedInactive()).isEqualTo(1);

        when(appSettings.getBoolean(eq(ExecutiveSummarySettings.INCLUDE_ADMINS_KEY), anyBoolean())).thenReturn(false);
        assertThat(svc.recipients().emails()).containsExactly("a@x.com");
    }

    @Test
    @DisplayName("250 alıcı → BCC 100/100/50, PDF eki, dilim başına iz; kayıt SENT + özet içeriği; konu [Site Monitor]")
    void bccChunksAndTrace() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 250; i++) many.add("u" + i + "@x.com");
        enable(String.join(",", many), false);

        ExecutiveSummaryDeliveryService.Result r = svc.runScheduled();
        assertThat(r.status()).isEqualTo(ExecutiveSummaryReport.SENT);
        assertThat(r.recipients()).isEqualTo(250);
        assertThat(r.chunks()).isEqualTo(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<EmailNotificationService.MailAttachment>> att = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String[]> bcc = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email, times(3)).sendHtmlBccWithAttachments(bcc.capture(), subject.capture(), anyString(), anyString(), att.capture());
        assertThat(bcc.getAllValues()).extracting(a -> a.length).containsExactly(100, 100, 50);
        assertThat(subject.getValue()).startsWith("[Site Monitor] Aylık Yönetici Özeti · Eylül 2026");
        EmailNotificationService.MailAttachment pdf = att.getValue().get(0);
        assertThat(pdf.contentType()).isEqualTo("application/pdf");
        assertThat(pdf.fileName()).isEqualTo("site-monitor-yonetici-ozeti-2026-09.pdf");
        assertThat(new String(pdf.data(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");

        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs, times(3)).save(log.capture());
        assertThat(log.getAllValues()).allSatisfy(n -> {
            assertThat(n.getTrigger()).isEqualTo(ExecutiveSummaryDeliveryService.TRIGGER);
            assertThat(n.getRecipientEmail()).startsWith("BCC×").hasSizeLessThanOrEqualTo(250);
            assertThat(n.getEmailStatus()).isEqualTo("SENT");
        });

        ArgumentCaptor<ExecutiveSummaryReport> row = ArgumentCaptor.forClass(ExecutiveSummaryReport.class);
        verify(repo).save(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(ExecutiveSummaryReport.SENT);
        assertThat(row.getValue().getRecipientCount()).isEqualTo(250);
        assertThat(row.getValue().getChunkCount()).isEqualTo(3);
        assertThat(row.getValue().getSummaryJson()).contains("\"month\":\"2026-09\"");
    }

    @Test
    @DisplayName("tam bir kez: SENT kaydı → ALREADY_SENT; SENDING → IN_PROGRESS; INSERT yarışı kaybedilirse posta yok")
    void exactlyOnce() {
        enable("a@x.com", false);
        ExecutiveSummaryReport sent = new ExecutiveSummaryReport();
        sent.setId(1L);
        sent.setStatus(ExecutiveSummaryReport.SENT);
        sent.setAttempts(1);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(sent));
        assertThat(svc.runScheduled().status()).isEqualTo("ALREADY_SENT");

        sent.setStatus(ExecutiveSummaryReport.SENDING);
        assertThat(svc.runScheduled().status()).isEqualTo("IN_PROGRESS");

        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_esr_year_month"));
        assertThat(svc.runScheduled().status()).isEqualTo("IN_PROGRESS");
        verifyNoInteractions(email);
    }

    @Test
    @DisplayName("FAILED (deneme < 3) koşullu UPDATE ile yeniden talep edilir; UPDATE 0 satır → başka pod aldı; tavan dolunca yalnız elle")
    void reclaim() {
        enable("a@x.com", false);
        ExecutiveSummaryReport failed = new ExecutiveSummaryReport();
        failed.setId(7L);
        failed.setStatus(ExecutiveSummaryReport.FAILED);
        failed.setAttempts(1);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(failed));

        when(repo.reclaim(eq(7L), eq("FAILED"), eq(1), eq(2), anyString(), eq("SCHEDULED"), eq("system"))).thenReturn(0);
        assertThat(svc.runScheduled().status()).isEqualTo("NOT_CLAIMED");
        verifyNoInteractions(email);

        when(repo.reclaim(eq(7L), eq("FAILED"), eq(1), eq(2), anyString(), eq("SCHEDULED"), eq("system"))).thenReturn(1);
        assertThat(svc.runScheduled().status()).isEqualTo(ExecutiveSummaryReport.SENT);
        verify(email, times(1)).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());

        failed.setStatus(ExecutiveSummaryReport.FAILED);
        failed.setAttempts(3);
        assertThat(svc.runScheduled().status()).isEqualTo("NOT_CLAIMED");
        when(repo.reclaim(eq(7L), eq("FAILED"), eq(3), eq(4), anyString(), eq("MANUAL"), eq("yonetici"))).thenReturn(1);
        assertThat(svc.sendNow(YearMonth.of(2026, 9), "yonetici").status()).isEqualTo(ExecutiveSummaryReport.SENT);
    }

    @Test
    @DisplayName("alıcı yok → posta yok, NO_RECIPIENT + 'SKIPPED: alıcı yok' izi (nedeni söyler)")
    void noRecipient() {
        enable("", false);
        ExecutiveSummaryDeliveryService.Result r = svc.runScheduled();
        assertThat(r.status()).isEqualTo(ExecutiveSummaryReport.NO_RECIPIENT);
        verify(email, never()).sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).startsWith("SKIPPED: alıcı yok");
        ArgumentCaptor<ExecutiveSummaryReport> row = ArgumentCaptor.forClass(ExecutiveSummaryReport.class);
        verify(repo).save(row.capture());
        assertThat(row.getValue().getStatus()).isEqualTo(ExecutiveSummaryReport.NO_RECIPIENT);
    }

    @Test
    @DisplayName("dilim sonuçları → kayıt durumu: hepsi gitti SENT, karışık PARTIAL, posta kapalı SKIPPED_MAIL_OFF, hepsi hata FAILED")
    void finalStatus() {
        assertThat(ExecutiveSummaryDeliveryService.finalStatus(Map.of("SENT", 100, "QUEUED_RETRY", 5))).isEqualTo("SENT");
        assertThat(ExecutiveSummaryDeliveryService.finalStatus(Map.of("SENT", 100, "FAILED", 5))).isEqualTo("PARTIAL");
        assertThat(ExecutiveSummaryDeliveryService.finalStatus(Map.of("SKIPPED_DISABLED", 5))).isEqualTo("SKIPPED_MAIL_OFF");
        assertThat(ExecutiveSummaryDeliveryService.finalStatus(Map.of("FAILED", 5))).isEqualTo("FAILED");
        assertThat(ExecutiveSummaryDeliveryService.finalStatus(Map.of())).isEqualTo("FAILED");
        assertThat(ExecutiveSummaryDeliveryService.statusKey("FAILED: x")).isEqualTo("FAILED");
        assertThat(ExecutiveSummaryDeliveryService.statusKey("QUEUED_RETRY: 421")).isEqualTo("QUEUED_RETRY");
        assertThat(ExecutiveSummaryDeliveryService.statusKey("SKIPPED_DISABLED")).isEqualTo("SKIPPED_DISABLED");
        assertThat(ExecutiveSummaryDeliveryService.statusKey("SENT")).isEqualTo("SENT");

        enable("a@x.com", false);
        when(email.sendHtmlBccWithAttachments(any(), anyString(), anyString(), anyString(), anyList())).thenReturn("SKIPPED_DISABLED");
        assertThat(svc.runScheduled().status()).isEqualTo(ExecutiveSummaryReport.SKIPPED_MAIL_OFF);
    }

    @Test
    @DisplayName("telafi: planlı andan önce NOT_DUE; 72 saat içinde kaçan ayı gönderir; pencere kapanınca WINDOW_CLOSED")
    void catchUp() {
        enable("a@x.com", false);
        assertThat(ExecutiveSummaryDeliveryService.plannedFireThisMonth(YearMonth.of(2026, 10), "0 0 9 1 * *"))
                .isEqualTo(Instant.parse("2026-10-01T06:00:00Z"));
        assertThat(ExecutiveSummaryDeliveryService.plannedFireThisMonth(YearMonth.of(2026, 10), "geçersiz"))
                .isEqualTo(Instant.parse("2026-10-01T06:00:00Z"));

        when(summaryService.now()).thenReturn(Instant.parse("2026-10-01T05:59:00Z"));
        assertThat(svc.catchUp().status()).isEqualTo("NOT_DUE");
        when(summaryService.now()).thenReturn(Instant.parse("2026-10-04T06:01:00Z"));
        assertThat(svc.catchUp().status()).isEqualTo("WINDOW_CLOSED");
        verifyNoInteractions(email);

        when(summaryService.now()).thenReturn(Instant.parse("2026-10-01T07:17:00Z"));
        assertThat(svc.catchUp().status()).isEqualTo(ExecutiveSummaryReport.SENT);

        ExecutiveSummaryReport sent = new ExecutiveSummaryReport();
        sent.setStatus(ExecutiveSummaryReport.SENT);
        sent.setAttempts(1);
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(sent));
        assertThat(svc.catchUp().status()).isEqualTo("NOTHING_TO_DO");
    }

    @Test
    @DisplayName("test postası: yalnız verilen (isteyenin) adrese TO ile; ay kaydı YAZILMAZ; iz EXECUTIVE_SUMMARY_TEST; sınır 3/10 dk")
    void sendTest() {
        when(email.sendHtmlWithAttachments(any(), any(), anyString(), anyString(), any(), anyList())).thenReturn("SENT");
        ExecutiveSummaryDeliveryService.TestResult r = svc.sendTest(YearMonth.of(2026, 9), "ben@x.com");
        assertThat(r.ok()).isTrue();
        ArgumentCaptor<String[]> to = ArgumentCaptor.forClass(String[].class);
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtmlWithAttachments(to.capture(), isNull(), subject.capture(), anyString(), isNull(), anyList());
        assertThat(to.getValue()).containsExactly("ben@x.com");
        assertThat(subject.getValue()).startsWith("[TEST] [Site Monitor]");
        verify(repo, never()).save(any());
        verify(repo, never()).saveAndFlush(any());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getTrigger()).isEqualTo(ExecutiveSummaryDeliveryService.TRIGGER_TEST);

        assertThat(svc.allowTest("admin")).isTrue();
        assertThat(svc.allowTest("admin")).isTrue();
        assertThat(svc.allowTest("admin")).isTrue();
        assertThat(svc.allowTest("admin")).isFalse();
        assertThat(svc.allowTest("baska")).isTrue();
    }
}
