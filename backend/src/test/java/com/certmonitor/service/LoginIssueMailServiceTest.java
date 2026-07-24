package com.certmonitor.service;

import com.certmonitor.model.LoginIssueMailLog;
import com.certmonitor.repository.LoginIssueMailLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** LoginIssueMailService — async gönderim + login_issue_mail_logs kaydı; force (mute bypass) config'ten. */
class LoginIssueMailServiceTest {

    EmailNotificationService emailService;
    LoginIssueMailLogRepository mailLogRepo;
    AppSettingsService appSettings;
    LoginIssueMailService service;

    @BeforeEach
    void setup() {
        emailService = mock(EmailNotificationService.class);
        mailLogRepo = mock(LoginIssueMailLogRepository.class);
        appSettings = mock(AppSettingsService.class);
        service = new LoginIssueMailService(emailService, mailLogRepo, appSettings);
        when(appSettings.getBoolean(eq("cert.monitor.login-issues.force-email"), anyBoolean())).thenReturn(true);
    }

    @Test
    @DisplayName("dispatchReport: force=true geçilir; SENT durumlu REPORT_ADMIN log satırı yazılır")
    void dispatchReport_forcedAndLogsSent() {
        when(emailService.sendLoginIssueReport(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new EmailNotificationService.LoginIssueMailResult("SENT", "noreply@cm", "Konu R", "<html>r</html>"));

        service.dispatchReport(42L, "LIR-2026-000042", "admin@x.com", "N1", "err", "msg",
                List.of(), "1.2.3.4", "UA", "2026-07-24T09:00:00");

        verify(emailService).sendLoginIssueReport(eq("admin@x.com"), eq("LIR-2026-000042"), eq("N1"),
                eq("err"), eq("msg"), any(), eq("1.2.3.4"), eq("UA"), anyString(), eq(true));

        LoginIssueMailLog m = capturedLog();
        assertThat(m.getReportId()).isEqualTo(42L);
        assertThat(m.getRefCode()).isEqualTo("LIR-2026-000042");
        assertThat(m.getMailType()).isEqualTo(LoginIssueMailService.REPORT_ADMIN);
        assertThat(m.getRecipientTo()).isEqualTo("admin@x.com");
        assertThat(m.getStatus()).isEqualTo("SENT");
        assertThat(m.getEmailFrom()).isEqualTo("noreply@cm");     // kimden
        assertThat(m.getSubject()).isEqualTo("Konu R");           // konu
        assertThat(m.getBodyHtml()).isEqualTo("<html>r</html>");  // içerik
        assertThat(m.isForced()).isTrue();
        assertThat(m.getErrorMessage()).isNull();
        assertThat(m.getSentAt()).isNotBlank();
    }

    @Test
    @DisplayName("dispatchAck: gönderim fırlatırsa status FAILED + errorMessage loglanır (asla dışarı fırlatmaz)")
    void dispatchAck_sendFailure_logsFailed() {
        when(emailService.sendLoginIssueAck(anyString(), anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyBoolean())).thenThrow(new RuntimeException("smtp down"));

        service.dispatchAck(7L, "LIR-2026-000007", "user@x.com", "N1", "err", "msg", List.of(), "2026-07-24T09:00:00");

        LoginIssueMailLog m = capturedLog();
        assertThat(m.getMailType()).isEqualTo(LoginIssueMailService.REPORTER_ACK);
        assertThat(m.getRecipientTo()).isEqualTo("user@x.com");
        assertThat(m.getStatus()).startsWith("FAILED");
        assertThat(m.getErrorMessage()).contains("smtp down");
    }

    @Test
    @DisplayName("dispatchResolved: config force=false → force=false geçilir; RESOLVED log (bildiren To, admin CC)")
    void dispatchResolved_forceFalseFromConfig_recipientArrangement() {
        when(appSettings.getBoolean(eq("cert.monitor.login-issues.force-email"), anyBoolean())).thenReturn(false);
        when(emailService.sendLoginIssueResolved(anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new EmailNotificationService.LoginIssueMailResult("SKIPPED_DISABLED", "noreply@cm", "Konu C", "<html>c</html>"));

        service.dispatchResolved(9L, "LIR-2026-000009", "reporter@x.com", "admin@x.com", "not", "2026-07-24T10:00:00");

        verify(emailService).sendLoginIssueResolved(eq("reporter@x.com"), eq("admin@x.com"),
                eq("LIR-2026-000009"), eq("not"), eq("2026-07-24T10:00:00"), eq(false));

        LoginIssueMailLog m = capturedLog();
        assertThat(m.getMailType()).isEqualTo(LoginIssueMailService.RESOLVED);
        assertThat(m.getRecipientTo()).isEqualTo("reporter@x.com");   // bildiren To
        assertThat(m.getCc()).isEqualTo("admin@x.com");               // admin CC
        assertThat(m.getStatus()).isEqualTo("SKIPPED_DISABLED");
        assertThat(m.isForced()).isFalse();
    }

    @Test
    @DisplayName("dispatchResolved: bildiren yoksa admin To olur (CC boş)")
    void dispatchResolved_noReporter_adminBecomesTo() {
        when(emailService.sendLoginIssueResolved(any(), anyString(), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(new EmailNotificationService.LoginIssueMailResult("SENT", "noreply@cm", "Konu C", "<html>c</html>"));

        service.dispatchResolved(10L, "LIR-2026-000010", null, "admin@x.com", null, "2026-07-24T10:00:00");

        LoginIssueMailLog m = capturedLog();
        assertThat(m.getRecipientTo()).isEqualTo("admin@x.com");
        assertThat(m.getCc()).isNull();
    }

    private LoginIssueMailLog capturedLog() {
        ArgumentCaptor<LoginIssueMailLog> cap = ArgumentCaptor.forClass(LoginIssueMailLog.class);
        verify(mailLogRepo).save(cap.capture());
        return cap.getValue();
    }
}
