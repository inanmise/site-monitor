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
    private final TechErrorHistoryService history = mock(TechErrorHistoryService.class);
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
        when(service.history()).thenReturn(history);
        when(settings.getString(anyString(), anyString())).thenReturn("https://sm.example.com/");
        TechErrorPdfInput in = new TechErrorPdfInput(TechErrorTemplate.defaults("tr"), TechErrorValues.Values.empty(), "Mobil",
                "Dijital", 2026, 9, "Ayşe", now, Map.of());
        when(service.report(5L)).thenReturn(report);
        when(service.pdfInput(any(), any(), any())).thenReturn(in);
        pdfReturns(new byte[]{ '%', 'P', 'D', 'F' });
        when(reports.claimSend(eq(5L), eq(now), any())).thenReturn(1);
        when(reports.finishSent(anyLong(), any(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        when(reports.releaseClaim(anyLong(), any(), any())).thenReturn(1);
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
        verifyNoInteractions(history);                       // hiçbir şey denenmedi → geçmişe de yazılmaz
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
        verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("SENT"),
                eq(List.of("dijital@example.com", "ikinci@example.com")), org.mockito.ArgumentMatchers.isNull(), eq(now));
    }

    private static byte[] samplePng(int w, int h) throws java.io.IOException {
        java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = bi.createGraphics();
        g.setColor(java.awt.Color.BLUE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi, "png", bo);
        return bo.toByteArray();
    }

    @Test
    @DisplayName("görseller gövdede (S5): yükleyici PDF izninin içinde ≤ 1000 px kopyaları hazırlar, sendHtml CID görsellerini alır; bütçe 0 → gömme yok, 'PDF ekinde' kutusu")
    void inlineImagesTravelWithTheMail() throws java.io.IOException {
        mailReturns("SENT");
        TechErrorPdfInput withImage = new TechErrorPdfInput(TechErrorTemplate.defaults("tr"), TechErrorValues.Values.empty(), "Mobil",
                "Dijital", 2026, 9, "Ayşe", now, Map.of("annual-summary", new TechErrorPdfInput.Image(samplePng(1600, 400), 1600, 400)));
        when(service.pdfInput(any(), any(), any())).thenReturn(withImage);
        sender.send(report, channel, team, 7L);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EmailNotificationService.InlineImage>> inline = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), anyString(), html.capture(), anyString(), inline.capture(), eq(false), any());
        assertThat(inline.getValue()).hasSize(1);
        assertThat(inline.getValue().get(0).cid()).isEqualTo("te-img-annual-summary");
        assertThat(inline.getValue().get(0).contentType()).isEqualTo("image/png");
        assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(inline.getValue().get(0).data())).getWidth()).isEqualTo(1000);
        assertThat(html.getValue()).contains("cid:te-img-annual-summary").contains("max-width:" + com.sitemonitor.service.mail.MailTokens.WIDTH_XWIDE + "px");
        // Bütçe 0: hiçbir görsel gömülmez, yuva "PDF ekinde" kutusuyla; gönderim yine SENT
        org.mockito.Mockito.reset(email);
        mailReturns("SENT");
        sender.mailInlineMb = 0;
        var res = sender.send(report, channel, team, 7L);
        assertThat(res.state()).isEqualTo("SENT");
        verify(email).sendHtml(any(String[].class), any(), anyString(), html.capture(), anyString(), inline.capture(), eq(false), any());
        assertThat(inline.getValue()).isEmpty();
        assertThat(html.getValue()).doesNotContain("cid:te-img-").contains("data-slot=\"te-image-pdf\"");
    }

    @Test
    @DisplayName("yöneticilere gönderim (S6): talepten sonraki okumada onay yoksa ya da içerik onaydan sonra değiştiyse 409 APPROVAL_STALE — talep bırakılır, posta gitmez, SKIPPED izi + geçmiş sebebi; onaylı ve değişmemiş rapor yöneticilere (To) + takım adresi (CC) gider")
    void executiveDeliveryGuard() {
        channel.setExecutiveEmails("ceo@example.com, Dijital@example.com");
        report.setApprovalState("APPROVED");
        report.setApprovalRev(3L);
        report.setContentRev(4L);                    // onaydan sonra değişti (talepten sonraki okuma bu satır)
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L, TechErrorReportSender.Delivery.EXECUTIVE))
                .isInstanceOf(CodedConflictException.class).extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("APPROVAL_STALE");
        verify(email, never()).sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any());
        verify(reports).releaseClaim(eq(5L), eq(now), any());
        verify(reports, never()).finishSent(anyLong(), any(), anyString(), any(), any(), anyString(), any());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).startsWith("SKIPPED: onay eskidi");
        assertThat(log.getValue().getRecipientRole()).isEqualTo("EXECUTIVE");
        verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("SKIPPED"), org.mockito.ArgumentMatchers.isNull(), eq("APPROVAL_STALE"), any());

        org.mockito.Mockito.reset(email, logs, history);
        mailReturns("SENT");
        report.setContentRev(3L);
        var res = sender.send(report, channel, team, 7L, TechErrorReportSender.Delivery.EXECUTIVE);
        assertThat(res.state()).isEqualTo("SENT");
        assertThat(res.to()).containsExactly("ceo@example.com", "Dijital@example.com");
        ArgumentCaptor<String[]> cc = ArgumentCaptor.forClass(String[].class);
        verify(email).sendHtml(eq(new String[]{ "ceo@example.com", "Dijital@example.com" }), cc.capture(), anyString(), anyString(), anyString(), any(), eq(false), any());
        // Takım adresindeki dijital@example.com yönetici listesinde zaten var (büyük-küçük harf): CC'de yalnız ikinci adres
        assertThat(cc.getValue()).containsExactly("ikinci@example.com");
        ArgumentCaptor<NotificationLog> sentLog = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(sentLog.capture());
        assertThat(sentLog.getValue().getRecipientRole()).isEqualTo("EXECUTIVE");
        assertThat(sentLog.getValue().getCc()).isEqualTo("ikinci@example.com");
        assertThat(sender.maxMailBytes()).isEqualTo(15L * 1024 * 1024);
    }

    @Test
    @DisplayName("posta sınırı PDF + gömülü görsellerin toplamı (inceleme 2026-10-17): sınıra yakın PDF'in yanında görseller gövdeye girmez, yuvalar 'PDF ekinde' der, posta yine SENT")
    void inlineImagesDroppedWhenPdfIsNearTheCap() throws java.io.IOException {
        mailReturns("SENT");
        sender.maxMailMb = 1;
        pdfReturns(new byte[1024 * 1024 - 100]);                 // 1 MB sınırının 100 bayt altında
        TechErrorPdfInput withImage = new TechErrorPdfInput(TechErrorTemplate.defaults("tr"), TechErrorValues.Values.empty(), "Mobil",
                "Dijital", 2026, 9, "Ayşe", now, Map.of("annual-summary", new TechErrorPdfInput.Image(samplePng(900, 300), 900, 300)));
        when(service.pdfInput(any(), any(), any())).thenReturn(withImage);
        var res = sender.send(report, channel, team, 7L);
        assertThat(res.state()).isEqualTo("SENT");
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EmailNotificationService.InlineImage>> inline = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), anyString(), html.capture(), anyString(), inline.capture(), eq(false), any());
        assertThat(inline.getValue()).isEmpty();
        // görseli olan yuva "PDF ekinde" kutusu alır (asla "yüklenmedi"); görselsiz bölümler yine "yüklenmedi" der
        assertThat(html.getValue()).doesNotContain("cid:te-img-").contains("data-slot=\"te-image-pdf\"");
        String section = html.getValue().substring(html.getValue().indexOf("12 Aylık Özetler - Yıllık Göstergeler"),
                html.getValue().indexOf("Eylül 2026 Özetler - Günlük Göstergeler"));
        assertThat(section).contains("data-slot=\"te-image-pdf\"").doesNotContain("Bu bölüme görsel yüklenmedi.");
    }

    @Test
    @DisplayName("görsel hazırlayıcı patlarsa posta durmaz: görseli olan yuvalar 'PDF ekinde' kutusu alır, PDF gider, SENT")
    void preparerFailureFallsBackToPdfBoxes() throws java.io.IOException {
        mailReturns("SENT");
        sender.mailImagePreparer = (in, budget) -> { throw new IllegalStateException("ImageIO yok"); };
        TechErrorPdfInput withImage = new TechErrorPdfInput(TechErrorTemplate.defaults("tr"), TechErrorValues.Values.empty(), "Mobil",
                "Dijital", 2026, 9, "Ayşe", now, Map.of("annual-summary", new TechErrorPdfInput.Image(samplePng(900, 300), 900, 300)));
        when(service.pdfInput(any(), any(), any())).thenReturn(withImage);
        var res = sender.send(report, channel, team, 7L);
        assertThat(res.state()).isEqualTo("SENT");
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EmailNotificationService.InlineImage>> inline = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendHtml(any(String[].class), any(), anyString(), html.capture(), anyString(), inline.capture(), eq(false), any());
        assertThat(inline.getValue()).isEmpty();
        assertThat(html.getValue()).contains("data-slot=\"te-image-pdf\"").doesNotContain("cid:te-img-");
        verify(reports).finishSent(eq(5L), eq(now), eq("SENT"), eq(now), eq(7L), anyString(), anyLong());
    }

    @Test
    @DisplayName("geçmiş (inceleme 2026-10-14): istisnayla biten deneme de 'gönderim denendi' olarak sebebiyle yazılır")
    void exceptionalAttemptsAreInHistory() {
        org.mockito.Mockito.doThrow(new TechErrorReportService.PdfBusyException("meşgul")).when(service).renderPdf(any());
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(TechErrorReportService.PdfBusyException.class);
        verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("SKIPPED"), org.mockito.ArgumentMatchers.isNull(),
                eq("PDF_BUSY"), eq(now));

        org.mockito.Mockito.reset(history);
        sender.maxMailMb = 1;
        pdfReturns(new byte[2 * 1024 * 1024]);
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(CodedConflictException.class);
        verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("FAILED"), org.mockito.ArgumentMatchers.isNull(),
                eq("PDF_TOO_LARGE"), eq(now));

        org.mockito.Mockito.reset(history);
        sender.maxMailMb = 15;
        pdfReturns(new byte[]{ '%', 'P', 'D', 'F' });
        mailReturns("SKIPPED_DISABLED");
        assertThat(sender.send(report, channel, team, 7L).state()).isEqualTo("SKIPPED");
        verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("SKIPPED"), org.mockito.ArgumentMatchers.isNull(),
                eq("MAIL_OFF"), eq(now));
    }

    @Test
    @DisplayName("kuyruktaki gönderimin üzerine yeniden gönderim: ilk gönderimin sonucu 'bilinmiyor' (SUPERSEDED) olarak yazılır")
    void resendOverQueuedRecordsSuperseded() {
        TechErrorReport queued = new TechErrorReport();
        queued.setId(5L);
        queued.setSendState("QUEUED");
        when(reports.findById(5L)).thenReturn(java.util.Optional.of(queued));
        report.setLastSendStatus("QUEUED_RETRY: 421");          // talepten sonraki okuma: kuyruktaki gönderim sonuçlanmamış
        mailReturns("SENT");
        sender.send(report, channel, team, 7L);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(history);
        order.verify(history).recordSend(eq(report), eq("SEND_RESULT"), org.mockito.ArgumentMatchers.isNull(), eq("SUPERSEDED"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), eq(now));
        order.verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("SENT"), any(), org.mockito.ArgumentMatchers.isNull(), eq(now));

        // kuyrukta değilse SUPERSEDED yazılmaz (talepten sonraki okuma belirleyici: son sonuç kuyruk değil)
        org.mockito.Mockito.reset(history);
        queued.setSendState("SENT");
        report.setLastSendStatus("SENT");
        sender.send(report, channel, team, 7L);
        verify(history, never()).recordSend(any(), eq("SEND_RESULT"), any(), any(), any(), any(), any());

        // düzeltme incelemesi: talepten önce başka sekme sonuçlandırdıysa (son durum SENT) yazılmaz
        org.mockito.Mockito.reset(history);
        queued.setSendState("QUEUED");
        report.setLastSendStatus("SENT");
        sender.send(report, channel, team, 7L);
        verify(history, never()).recordSend(any(), eq("SEND_RESULT"), any(), any(), any(), any(), any());

        // PDF sırası dolu: talep bırakılır, kuyruktaki gönderim yaşar → SUPERSEDED yazılmaz (sonucu sonra gelir)
        org.mockito.Mockito.reset(history);
        report.setLastSendStatus("QUEUED_RETRY: 421");
        org.mockito.Mockito.doThrow(new TechErrorReportService.PdfBusyException("meşgul")).when(service).renderPdf(any());
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(TechErrorReportService.PdfBusyException.class);
        verify(history, never()).recordSend(any(), eq("SEND_RESULT"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("düzeltme incelemesi 2026-10-15: önce kuyruk sonuçlandırılır; kuyruktaki gönderimin üzerine deneme posta servisine ulaşmadıysa kuyruk yaşar")
    void queuedSendSurvivesAttemptThatNeverReachedMail() {
        Instant earlier = now.minusSeconds(300);                // kuyruk 5 dk önce: sonucu hâlâ gelebilir
        TechErrorReport queued = new TechErrorReport();
        queued.setId(5L);
        queued.setSendState("QUEUED");
        queued.setSendClaimedAt(earlier);
        when(reports.findById(5L)).thenReturn(java.util.Optional.of(queued));
        report.setChannelId(10L);
        report.setLastSendStatus("QUEUED_RETRY: 421");
        sender.maxMailMb = 1;
        pdfReturns(new byte[2 * 1024 * 1024]);                    // PDF çok büyük → posta servisine hiçbir şey gitmez
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(CodedConflictException.class);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(service, reports);
        order.verify(service).reconcileQueued(List.of(10L));      // talepten ÖNCE
        order.verify(reports).claimSend(eq(5L), eq(now), any());
        verify(reports).releaseClaim(5L, now, earlier);            // kuyruk yaşar, eski talep zamanı geri gelir
        verify(reports, never()).finishNotSent(anyLong(), any(), anyString(), anyString(), any());
        verify(history, never()).recordSend(any(), eq("SEND_RESULT"), any(), any(), any(), any(), any());
        verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("FAILED"), org.mockito.ArgumentMatchers.isNull(),
                eq("PDF_TOO_LARGE"), eq(now));
    }

    @Test
    @DisplayName("kuyruktaki gönderimin üzerine deneme: servis FAILED / SKIPPED dönerse kuyruk yaşar; servis istisna atarsa teslim bilinmez → SUPERSEDED")
    void queuedSendSurvivesWhenNewAttemptDeliveredNothing() {
        Instant earlier = now.minusSeconds(300);                // kuyruk 5 dk önce: sonucu hâlâ gelebilir
        TechErrorReport queued = new TechErrorReport();
        queued.setId(5L);
        queued.setSendState("QUEUED");
        queued.setSendClaimedAt(earlier);
        when(reports.findById(5L)).thenReturn(java.util.Optional.of(queued));
        report.setLastSendStatus("QUEUED_RETRY: 421");
        for (String status : List.of("FAILED: 554 relay denied", "SKIPPED_DISABLED")) {
            org.mockito.Mockito.clearInvocations(reports, history);
            mailReturns(status);
            sender.send(report, channel, team, 7L);
            verify(reports).releaseClaim(5L, now, earlier);
            verify(reports, never()).finishNotSent(anyLong(), any(), anyString(), anyString(), any());
            verify(history, never()).recordSend(any(), eq("SEND_RESULT"), any(), any(), any(), any(), any());
        }
        // Kuyruk 20 dakikadan eski: yeniden deneme kaybolmuş olabilir (pod yeniden başladı) — rapor sonsuza dek "kuyrukta"
        // kalmasın: başarısız deneme kuyruğu bitirir, ilk gönderim "sonucu bilinmiyor"
        org.mockito.Mockito.clearInvocations(reports, history);
        queued.setSendClaimedAt(now.minus(TechErrorReportSender.QUEUED_RESULT_WINDOW).minusSeconds(1));
        mailReturns("FAILED: 554 relay denied");
        sender.send(report, channel, team, 7L);
        verify(reports, never()).releaseClaim(anyLong(), any(), any());
        verify(reports).finishNotSent(eq(5L), eq(now), eq("FAILED"), anyString(), eq(now));
        verify(history).recordSend(eq(report), eq("SEND_RESULT"), org.mockito.ArgumentMatchers.isNull(), eq("SUPERSEDED"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), eq(now));
        queued.setSendClaimedAt(earlier);

        // Yarım kalmış bir talebin devralınması: ön okuma SENDING ama talepten sonraki okuma kuyruk sürüyor diyor → korunur
        org.mockito.Mockito.clearInvocations(reports, history);
        queued.setSendState("SENDING");
        mailReturns("FAILED: 554 relay denied");
        sender.send(report, channel, team, 7L);
        verify(reports).releaseClaim(5L, now, earlier);
        verify(history, never()).recordSend(any(), eq("SEND_RESULT"), any(), any(), any(), any(), any());
        queued.setSendState("QUEUED");

        // Servis istisna attı: teslim BİLİNMİYOR → bitirilir ve ilk gönderim "sonucu bilinmiyor"
        org.mockito.Mockito.clearInvocations(reports, history);
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("smtp koptu"));
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(IllegalStateException.class);
        verify(reports).finishNotSent(eq(5L), eq(now), eq("FAILED"), anyString(), eq(now));
        verify(history).recordSend(eq(report), eq("SEND_RESULT"), org.mockito.ArgumentMatchers.isNull(), eq("SUPERSEDED"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), eq(now));
    }

    @Test
    @DisplayName("gönderilmeme sebebi kodu: posta kapalı / pasif alıcı / PDF sırası / PDF çok büyük / alıcı yok; bilinmeyen null")
    void sendReasonCodes() {
        assertThat(TechErrorHistoryService.sendReason("SKIPPED_DISABLED")).isEqualTo("MAIL_OFF");
        assertThat(TechErrorHistoryService.sendReason("SKIPPED: pasif kullanıcı")).isEqualTo("INACTIVE_RECIPIENTS");
        assertThat(TechErrorHistoryService.sendReason("SKIPPED: PDF sırası dolu, gönderilmedi")).isEqualTo("PDF_BUSY");
        assertThat(TechErrorHistoryService.sendReason("FAILED: PDF e-posta sınırını aşıyor (2 MB > 1 MB)")).isEqualTo("PDF_TOO_LARGE");
        assertThat(TechErrorHistoryService.sendReason("SKIPPED_NO_RECIPIENT")).isEqualTo("NO_RECIPIENT");
        assertThat(TechErrorHistoryService.sendReason("FAILED: 554 relay denied")).isNull();
        assertThat(TechErrorHistoryService.sendReason(null)).isNull();
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
        verify(reports).releaseClaim(eq(5L), eq(now), any());
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
        verify(reports).releaseClaim(eq(5L), eq(now), any());
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
