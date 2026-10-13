package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EmailNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Teknik hata raporu gönderimi (2026-10-13): alıcı yoksa hiçbir şey yazılmaz; talep en çok bir kez; talepten sonra her
 * yol (başarı, kuyruk, kapalı posta, SMTP hatası, büyük PDF, beklenmeyen istisna) satırı SON durumuna yazar ve bir
 * {@code notification_logs} satırı bırakır. SMTP iletisi kullanıcıya dönmez (uç sınıf döner).
 */
class TechErrorReportSenderTest {

    private final TechErrorReportService service = mock(TechErrorReportService.class);
    private final TechErrorReportRepository reports = mock(TechErrorReportRepository.class);
    private final EmailNotificationService email = mock(EmailNotificationService.class);
    private final NotificationLogRepository logs = mock(NotificationLogRepository.class);
    private final AppSettingsService settings = mock(AppSettingsService.class);
    private TechErrorReportSender sender;

    private final Instant now = Instant.parse("2026-10-13T09:00:00.123Z");
    private TechErrorReport report;
    private TechErrorChannel channel;
    private Team team;

    @BeforeEach
    void setUp() {
        sender = new TechErrorReportSender(service, reports, email, logs, settings);
        report = new TechErrorReport();
        report.setId(5L);
        report.setReportYear(2026);
        report.setReportMonth(9);
        channel = new TechErrorChannel();
        channel.setName("Mobil");
        team = new Team();
        team.setName("Dijital");
        team.setEmail("dijital@example.com; yanlis-adres, dijital@example.com ,ikinci@example.com");
        when(service.now()).thenReturn(now);
        when(settings.getString(anyString(), anyString())).thenReturn("https://sm.example.com/");
        TechErrorPdfInput in = new TechErrorPdfInput(TechErrorTemplate.defaults("tr"), TechErrorValues.Values.empty(), "Mobil",
                "Dijital", 2026, 9, "Ayşe", now, Map.of());
        when(service.report(5L)).thenReturn(report);
        when(service.pdfInput(any(), any(), any())).thenReturn(in);
        pdfReturns(new byte[]{ '%', 'P', 'D', 'F' });
        when(reports.claimSend(eq(5L), eq(now), any())).thenReturn(1);
        when(reports.finishSent(anyLong(), any(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(reports.releaseClaim(anyLong(), any())).thenReturn(1);
        when(reports.finishNotSent(anyLong(), any(), anyString(), anyString(), any())).thenReturn(1);
    }

    /** renderPdf yükleyiciyi gerçekten çağırır (izin ÖNCE, görseller SONRA — sıra testte de korunur). */
    private void pdfReturns(byte[] bytes) {
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Supplier<TechErrorPdfInput> loader = inv.getArgument(0);
            TechErrorPdfInput in = loader.get();
            return new TechErrorReportService.Rendered(in.withoutImages(), bytes);
        }).when(service).renderPdf(any());
    }

    private void mailReturns(String status) {
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any())).thenReturn(status);
    }

    @Test
    @DisplayName("alıcılar: ; ve , ayraçlı, biçimsiz atılır, tekrar tekilleşir; adres yoksa 409 ve HİÇBİR şey yazılmaz")
    void recipients() {
        assertThat(TechErrorReportSender.recipients(team)).containsExactly("dijital@example.com", "ikinci@example.com");
        team.setEmail("  ");
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(CodedConflictException.class)
                .extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("TEAM_EMAIL_MISSING");
        verify(reports, never()).claimSend(anyLong(), any(), any());
        verifyNoInteractions(logs);
    }

    @Test
    @DisplayName("başarı: konu = rapor başlığı, PDF eki, SENT yazılır + günlük satırı (TECH_ERROR_REPORT)")
    void sent() {
        mailReturns("SENT");
        var res = sender.send(report, channel, team, 7L);
        assertThat(res.state()).isEqualTo("SENT");
        assertThat(res.subject()).isEqualTo("Mobil Kanalı Teknik Hata Mesajları - Eylül 2026");
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EmailNotificationService.MailAttachment>> att = ArgumentCaptor.forClass(List.class);
        verify(email).sendHtml(eq(new String[]{ "dijital@example.com", "ikinci@example.com" }), any(), eq(res.subject()),
                anyString(), anyString(), any(), eq(false), att.capture());
        assertThat(att.getValue().get(0).fileName()).isEqualTo("mobil-teknik-hata-2026-09.pdf");
        assertThat(att.getValue().get(0).contentType()).isEqualTo("application/pdf");
        verify(reports).finishSent(eq(5L), eq(now), eq("SENT"), eq(now), eq(7L), eq("dijital@example.com, ikinci@example.com"), eq(0L));
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getTrigger()).isEqualTo("TECH_ERROR_REPORT");
        assertThat(log.getValue().getEmailStatus()).isEqualTo("SENT");
        assertThat(log.getValue().getAlertEventId()).isZero();
    }

    @Test
    @DisplayName("QUEUED_RETRY → QUEUED: 'gönderildi' yazılmaz; günlük satırı ÖNCE yazılır ve kimliği bekleyen alanlarla saklanır")
    void queuedIsNotRecordedAsSent() {
        when(logs.save(any())).thenAnswer(inv -> {
            NotificationLog n = inv.getArgument(0);
            n.setId(77L);
            return n;
        });
        report.setContentRev(3L);
        mailReturns("QUEUED_RETRY: 421");
        assertThat(sender.send(report, channel, team, 7L).state()).isEqualTo("QUEUED");
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(logs, reports);
        order.verify(logs).save(any());
        order.verify(reports).finishQueued(eq(5L), eq(now), eq("QUEUED_RETRY: 421"), eq(now), eq(77L), eq(7L),
                eq("dijital@example.com, ikinci@example.com"), eq(3L));
        verify(reports, never()).finishSent(anyLong(), any(), anyString(), any(), any(), anyString(), any());
        verify(reports, never()).finishNotSent(anyLong(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("gönderim PDF okumasının içerik sayacını saklar")
    void sentRevisionIsTheRenderedRevision() {
        report.setContentRev(4L);
        mailReturns("SENT");
        sender.send(report, channel, team, 7L);
        verify(reports).finishSent(eq(5L), eq(now), eq("SENT"), eq(now), eq(7L), anyString(), eq(4L));
    }

    @Test
    @DisplayName("talep alınamadı ve rapor artık yok → 404 (\"gönderiliyor\" değil)")
    void claimLostBecauseDeleted() {
        when(reports.claimSend(eq(5L), eq(now), any())).thenReturn(0);
        when(reports.existsById(5L)).thenReturn(false);
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    @DisplayName("sınıflar: SKIPPED_DISABLED → SKIPPED, FAILED → FAILED (son gönderim alanlarına dokunmaz)")
    void notSentStates() {
        mailReturns("SKIPPED_DISABLED");
        assertThat(sender.send(report, channel, team, 7L).state()).isEqualTo("SKIPPED");
        mailReturns("FAILED: 554 relay denied");
        assertThat(sender.send(report, channel, team, 7L).state()).isEqualTo("FAILED");
        verify(reports, never()).finishSent(anyLong(), any(), anyString(), any(), any(), anyString(), any());
        verify(reports).finishNotSent(eq(5L), eq(now), eq("SKIPPED"), eq("SKIPPED_DISABLED"), eq(now));
        verify(reports).finishNotSent(eq(5L), eq(now), eq("FAILED"), eq("FAILED: 554 relay denied"), eq(now));
    }

    @Test
    @DisplayName("PDF talepten SONRA taze okunan satırdan üretilir (arada kaydedilen değerler PDF'e girer)")
    void pdfUsesFreshRowAfterClaim() {
        TechErrorReport fresh = new TechErrorReport();
        fresh.setId(5L);
        fresh.setReportYear(2026);
        fresh.setReportMonth(9);
        when(service.report(5L)).thenReturn(fresh);
        mailReturns("SENT");
        sender.send(report, channel, team, 7L);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(reports, service);
        order.verify(reports).claimSend(eq(5L), eq(now), any());
        order.verify(service).report(5L);
        verify(service).pdfInput(org.mockito.ArgumentMatchers.same(fresh), any(), any());
    }

    @Test
    @DisplayName("PDF sırası dolu: hiçbir şey denenmedi — talep önceki durumuna bırakılır, FAILED yazılmaz, SKIPPED izi kalır")
    void pdfBusyReleasesClaim() {
        report.setSendState("SENT");
        org.mockito.Mockito.doThrow(new TechErrorReportService.PdfBusyException("meşgul")).when(service).renderPdf(any());
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(TechErrorReportService.PdfBusyException.class);
        verify(reports).releaseClaim(5L, now);
        verify(reports, never()).finishNotSent(anyLong(), any(), anyString(), anyString(), any());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).startsWith("SKIPPED");
        verify(email, never()).sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("PDF sırası dolu + yarıda kalmış eski talep: talep bırakılır (durum satırın kendi sonucundan — SQL)")
    void pdfBusyAfterStaleClaimReleases() {
        report.setSendState("SENDING");
        org.mockito.Mockito.doThrow(new TechErrorReportService.PdfBusyException("meşgul")).when(service).renderPdf(any());
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(TechErrorReportService.PdfBusyException.class);
        verify(reports).releaseClaim(5L, now);
        verify(reports, never()).finishNotSent(anyLong(), any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("yalnız pasif kullanıcılara ait adres 'kime gitti' kaydına yazılmaz")
    void passiveOnlyAddressNotRecorded() {
        sender.inactiveGuard = mock(com.sitemonitor.service.InactiveRecipientGuard.class);
        when(sender.inactiveGuard.isInactiveOnlyEmail("ikinci@example.com")).thenReturn(true);
        mailReturns("SENT");
        var res = sender.send(report, channel, team, 7L);
        assertThat(res.to()).containsExactly("dijital@example.com");
        verify(reports).finishSent(eq(5L), eq(now), eq("SENT"), eq(now), eq(7L), eq("dijital@example.com"), eq(0L));
    }

    @Test
    @DisplayName("aynı rapor başka yerde gönderiliyorsa 409 SEND_IN_PROGRESS; posta gönderilmez")
    void claimLost() {
        when(reports.claimSend(eq(5L), eq(now), any())).thenReturn(0);
        when(reports.existsById(5L)).thenReturn(true);
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(CodedConflictException.class)
                .extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("SEND_IN_PROGRESS");
        verifyNoInteractions(email, logs);
    }

    @Test
    @DisplayName("PDF e-posta sınırını aşarsa 409 PDF_TOO_LARGE; satır FAILED olur (SENDING'de kalmaz) ve iz bırakır")
    void pdfTooLarge() {
        sender.maxMailMb = 1;
        pdfReturns(new byte[2 * 1024 * 1024]);
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(CodedConflictException.class)
                .extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("PDF_TOO_LARGE");
        verify(reports).finishNotSent(eq(5L), eq(now), eq("FAILED"), org.mockito.ArgumentMatchers.startsWith("FAILED: PDF"), eq(now));
        verify(logs).save(any());
        verify(email, never()).sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("talepten sonra beklenmeyen istisna: satır FAILED'e yazılır, iz bırakılır, istisna yukarı gider")
    void unexpectedFailureFinishes() {
        org.mockito.Mockito.doThrow(new IllegalStateException("bozuk")).when(service).renderPdf(any());
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(IllegalStateException.class);
        verify(reports).finishNotSent(eq(5L), eq(now), eq("FAILED"), eq("FAILED: IllegalStateException"), eq(now));
        verify(logs).save(any());
    }
}
