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
import com.sitemonitor.service.mail.TechErrorReportMail;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    @DisplayName("2026-10-18: sıfır adet işareti önizlemede de görünür (CID ek gönderimle aynı listede → data: URI); kısaltmalı değer kısaltmasıyla")
    void previewCarriesZeroMarkAndAbbreviation() throws Exception {
        pdfReturns(new byte[1000]);
        var t = TechErrorTemplate.defaults("tr");
        var v = TechErrorValues.normalize(Map.of("fields", Map.of("total-tx", 4_400_000_000L, "quota-count", 0), "units", Map.of("total-tx", 9)), t);
        when(service.pdfInput(any(), any(), any())).thenReturn(new TechErrorPdfInput(t, v, "Mobil", "Dijital", 2026, 9, "Ayşe", now, Map.of()));
        TechErrorReportSender.Preview p = sender.preview(report, channel, team, TechErrorReportSender.Delivery.TEAM);
        assertThat(p.html()).contains("4,4G").doesNotContain("cid:").contains("data-slot=\"te-zero-ok\"")
                .contains("alt=\"sıfır — sorun yok\"");
    }

    @Test
    @DisplayName("önizleme (10. sürüm): gönderimle aynı gövde ve konu, cid görseller data: URI, ek adı; talep / posta / günlük yok; alıcı yoksa problem kodu; EXECUTIVE yolu To / CC")
    void previewBuildsWithoutSending() throws Exception {
        pdfReturns(new byte[1000]);                                  // gönderimle aynı merdiven: PDF üretilir, izin renderPdf'te
        TechErrorPdfInput withImage = new TechErrorPdfInput(TechErrorTemplate.defaults("tr"), TechErrorValues.Values.empty(), "Mobil",
                "Dijital", 2026, 9, "Ayşe", now, Map.of("annual-summary", new TechErrorPdfInput.Image(samplePng(1600, 400), 1600, 400)));
        when(service.pdfInput(any(), any(), any())).thenReturn(withImage);
        TechErrorReportSender.Preview p = sender.preview(report, channel, team, TechErrorReportSender.Delivery.TEAM);
        assertThat(p.subject()).isEqualTo(TechErrorReportMail.subject(withImage));
        assertThat(p.role()).isEqualTo("TEAM");
        assertThat(p.to()).isEqualTo(TechErrorReportSender.recipients(team));
        assertThat(p.cc()).isEmpty();
        assertThat(p.toDropped()).isZero();                           // A3: sınır dışında alıcı yok
        assertThat(p.problem()).isNull();
        assertThat(p.fileName()).endsWith(".pdf");
        assertThat(p.embeddedImages()).isEqualTo(1);
        assertThat(p.html()).contains("data:image/").doesNotContain("cid:te-img-");
        assertThat(p.html()).contains("TEKNİK HATA RAPORU");
        assertThat(p.html()).doesNotContain("cid:");                 // marka logosu da data: URI (duman testi: iframe'de kırık görsel)
        assertThat(TechErrorReportSender.inlineBrandLogo("<img src=\"cid:brand-logo\" alt=\"x\">")).startsWith("<img src=\"data:image/png;base64,").endsWith("\" alt=\"x\">");
        assertThat(TechErrorReportSender.inlineBrandLogo("no logo")).isEqualTo("no logo");
        assertThat(p.provenance()).isNull();                         // pdfInput sahte: kimlik yok → eski "Hazırlayan" satırı
        assertThat(p.html()).contains("Hazırlayan: Ayşe");
        assertThat(p.pdfBytes()).isEqualTo(1000);
        assertThat(p.maxMailMb()).isEqualTo(sender.maxMailMb);
        assertThat(p.html()).contains("takımının adresi olduğu");  // TEAM dipnotu
        verify(email, never()).sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any());
        verify(reports, never()).claimSend(any(), any(), any());
        verify(logs, never()).save(any());
        // Yönetici alıcılı kanal: To yöneticiler, CC takım (yöneticide zaten olan adres tekrar etmez)
        channel.setExecutiveEmails("ceo@example.com, " + TechErrorReportSender.recipients(team).get(0));
        TechErrorReportSender.Preview ex = sender.preview(report, channel, team, TechErrorReportSender.Delivery.EXECUTIVE);
        assertThat(ex.role()).isEqualTo("EXECUTIVE");
        assertThat(ex.to()).hasSize(2).contains("ceo@example.com");
        assertThat(ex.cc()).doesNotContain(TechErrorReportSender.recipients(team).get(0));
        assertThat(ex.html()).contains("yönetici (executive) alıcısı").doesNotContain("takımının adresi olduğu");   // EXECUTIVE dipnotu
        // PDF tek başına sınırı aşıyor: gövde gelir, problem PDF_TOO_LARGE (gönderim 409 verir)
        sender.maxMailMb = 5;
        pdfReturns(new byte[6 * 1024 * 1024]);
        TechErrorReportSender.Preview big = sender.preview(report, channel, team, TechErrorReportSender.Delivery.TEAM);
        assertThat(big.problem()).isEqualTo("PDF_TOO_LARGE");
        assertThat(big.pdfTooLarge()).isTrue();
        assertThat(big.pdfBytes()).isEqualTo(6 * 1024 * 1024);
        assertThat(big.html()).isNotBlank();
        // Alıcı sorunu da varsa `problem` onu söyler, PDF sınırı ayrı bayrakta KALIR (inceleme 2026-10-17: test düğmesi görünüyordu)
        String keptEmail = team.getEmail();
        team.setEmail("");
        TechErrorReportSender.Preview bigNoMail = sender.preview(report, channel, team, TechErrorReportSender.Delivery.TEAM);
        assertThat(bigNoMail.problem()).isEqualTo("TEAM_EMAIL_MISSING");
        assertThat(bigNoMail.pdfTooLarge()).isTrue();
        team.setEmail(keptEmail);
        // PDF + gömülü görsel toplamı sınırı aşıyor: görseller yalnız PDF'te (gönderimdeki kuralın aynısı), problem yok
        pdfReturns(new byte[5 * 1024 * 1024 - 64]);
        TechErrorReportSender.Preview sum = sender.preview(report, channel, team, TechErrorReportSender.Delivery.TEAM);
        assertThat(sum.problem()).isNull();
        assertThat(sum.pdfTooLarge()).isFalse();
        assertThat(sum.embeddedImages()).isZero();
        assertThat(sum.skippedImages()).isEqualTo(1);
        assertThat(sum.html()).contains("te-image-pdf");           // yuva "PDF ekinde" kutusu; logo yine data: URI
        sender.maxMailMb = 15;
        pdfReturns(new byte[1000]);
        // Takım adresi yok: gövde yine gelir, problem kodu söyler
        team.setEmail("");
        TechErrorReportSender.Preview noMail = sender.preview(report, channel, team, TechErrorReportSender.Delivery.TEAM);
        assertThat(noMail.problem()).isEqualTo("TEAM_EMAIL_MISSING");
        assertThat(noMail.to()).isEmpty();
        assertThat(noMail.html()).isNotBlank();
        // Görsel hazırlama hatası postayı durdurmadığı gibi önizlemeyi de durdurmaz
        sender.mailImagePreparer = (in, budget) -> { throw new IllegalStateException("ImageIO yok"); };
        assertThat(sender.preview(report, channel, team, TechErrorReportSender.Delivery.TEAM).skippedImages()).isEqualTo(1);
        assertThat(TechErrorReportSender.inlineCids("<img src=\"cid:a\">", List.of(new EmailNotificationService.InlineImage("a", new byte[] { 1 }, null))))
                .isEqualTo("<img src=\"data:image/png;base64,AQ==\">");
        assertThat(TechErrorReportSender.inlineCids("x", null)).isEqualTo("x");
        // Önek çakışması (inceleme): "a" "a-b"nin öneki — her src kendi görselini alır
        assertThat(TechErrorReportSender.inlineCids("<img src=\"cid:a-b\"><img src=\"cid:a\">",
                List.of(new EmailNotificationService.InlineImage("a", new byte[] { 1 }, null), new EmailNotificationService.InlineImage("a-b", new byte[] { 2 }, null))))
                .isEqualTo("<img src=\"data:image/png;base64,Ag==\"><img src=\"data:image/png;base64,AQ==\">");
    }

    /** renderPdf yükleyiciyi gerçekten çağırır (izin ÖNCE, görseller SONRA — sıra testte de korunur). */
    private void pdfReturns(byte[] bytes) {
        org.mockito.Mockito.doAnswer(inv -> {
            java.util.function.Supplier<TechErrorPdfInput> loader = inv.getArgument(0);
            TechErrorPdfInput in = loader.get();
            return new TechErrorReportService.Rendered(in.withoutImages(), bytes);
        }).when(service).renderPdf(any(), anyLong());
    }

    private void mailReturns(String status) {
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any())).thenReturn(status);
    }

    @Test
    @DisplayName("alıcılar: ; ve , ayraçlı, biçimsiz atılır, tekrar tekilleşir; adres yoksa 409 ve HİÇBİR şey yazılmaz")
    void recipients() {
        assertThat(TechErrorReportSender.recipients(team)).containsExactly("dijital@example.com", "ikinci@example.com");
        assertThat(TechErrorReportSender.parseAddressesDetailed(team.getEmail()).dropped()).isZero();
        // A3: 21 teslim edilebilir adres → 20 + 1 düşen; tekrar (büyük harfli) ve biçimsiz düşen SAYILMAZ; parseAddresses aynı liste
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 21; i++) many.append("u").append(i).append("@example.com; ");
        many.append("U1@example.com, bozuk adres");
        TechErrorReportSender.Addresses d = TechErrorReportSender.parseAddressesDetailed(many.toString());
        assertThat(d.addresses()).hasSize(TechErrorReportSender.MAX_RECIPIENTS).startsWith("u1@example.com").endsWith("u20@example.com");
        assertThat(d.dropped()).isEqualTo(1);
        assertThat(TechErrorReportSender.parseAddresses(many.toString())).isEqualTo(d.addresses());
        assertThat(TechErrorReportSender.parseAddressesDetailed(null).dropped()).isZero();
        assertThat(TechErrorReportSender.parseAddressesDetailed(" ; , ").addresses()).isEmpty();
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

    @Test
    @DisplayName("A3 (inceleme 2026-10-17): takım adresinde 21+ alıcı — önizleme toDropped sayar (Kime 20); EXECUTIVE yolunda Kime kaynağı yönetici listesi ve Bilgi (takım eksi yöneticiler) de en çok 20: fazlası ccDropped; gönderim WARN + geçmiş olayına dropped_recipients / dropped_cc (8 argümanlı kayıt); düşen yoksa eski 7 argümanlı kayıt")
    void droppedRecipientsAreCountedAndRecorded() {
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 22; i++) many.append("u").append(i).append("@example.com, ");
        team.setEmail(many.toString());
        TechErrorReportSender.Preview p = sender.preview(report, channel, team, TechErrorReportSender.Delivery.TEAM);
        assertThat(p.to()).hasSize(20);
        assertThat(p.toDropped()).isEqualTo(2);
        assertThat(p.ccDropped()).isZero();                           // takıma gönderimde Bilgi yok
        channel.setExecutiveEmails("ceo@example.com");
        TechErrorReportSender.Preview ex = sender.preview(report, channel, team, TechErrorReportSender.Delivery.EXECUTIVE);
        assertThat(ex.to()).containsExactly("ceo@example.com");
        assertThat(ex.toDropped()).isZero();                          // yönetici listesi sınır içinde
        // Takımın fazlası Bilgi'yi ilgilendirir: 22 takım adresinden 20'si Bilgi'de, 2'si düşer — sessiz değil
        assertThat(ex.cc()).hasSize(20).doesNotContain("u21@example.com", "u22@example.com");
        assertThat(ex.ccDropped()).isEqualTo(2);
        // Yönetici listesinde zaten olan takım adresi düşmüş sayılmaz ve Bilgi'de yer kaplamaz: 22 − 2 = 20 → hepsi gider
        channel.setExecutiveEmails("U5@example.com, u21@example.com");
        TechErrorReportSender.Preview overlap = sender.preview(report, channel, team, TechErrorReportSender.Delivery.EXECUTIVE);
        assertThat(overlap.cc()).hasSize(20).contains("u22@example.com").doesNotContain("u5@example.com", "u21@example.com");
        assertThat(overlap.ccDropped()).isZero();
        channel.setExecutiveEmails("ceo@example.com");
        mailReturns("SENT");
        var res = sender.send(report, channel, team, 7L);
        assertThat(res.state()).isEqualTo("SENT");
        assertThat(res.to()).hasSize(20).doesNotContain("u21@example.com", "u22@example.com");
        verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("SENT"),
                org.mockito.ArgumentMatchers.<java.util.Collection<String>>argThat(l -> l != null && l.size() == 20),
                org.mockito.ArgumentMatchers.isNull(), eq(now), eq(Map.of("dropped_recipients", 2)));
        verify(history, never()).recordSend(any(), anyString(), any(), anyString(), any(), any(), any());
        // Yöneticilere gönderim: Bilgi 20 adrese gider, fazlası geçmiş olayına `dropped_cc` (Kime fazlası yok → anahtar yok)
        org.mockito.Mockito.reset(email, history);
        when(service.history()).thenReturn(history);
        mailReturns("SENT");
        report.setApprovalState("APPROVED");
        report.setApprovalRev(3L);
        report.setContentRev(3L);
        var exRes = sender.send(report, channel, team, 7L, TechErrorReportSender.Delivery.EXECUTIVE);
        assertThat(exRes.state()).isEqualTo("SENT");
        ArgumentCaptor<String[]> cc = ArgumentCaptor.forClass(String[].class);
        verify(email).sendHtml(eq(new String[]{ "ceo@example.com" }), cc.capture(), anyString(), anyString(), anyString(), any(), eq(false), any());
        assertThat(cc.getValue()).hasSize(20).doesNotContain("u21@example.com", "u22@example.com");
        verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("SENT"),
                org.mockito.ArgumentMatchers.<java.util.Collection<String>>argThat(l -> l != null && l.size() == 1),
                org.mockito.ArgumentMatchers.isNull(), eq(now), eq(Map.of("dropped_cc", 2)));
        verify(history, never()).recordSend(any(), anyString(), any(), anyString(), any(), any(), any());
        // Adres yardımcısı: takım yoksa boş, yönetici yoksa takımın ilk 20'si
        assertThat(TechErrorReportSender.ccAddresses(null, List.of("a@example.com")).addresses()).isEmpty();
        assertThat(TechErrorReportSender.ccAddresses(team, List.of()).dropped()).isEqualTo(2);
    }

    @Test
    @DisplayName("R18 / C13 ek Bilgi (yardımcı): boş = yok; ayraç virgül / noktalı virgül / boşluk; tekrar ve Kime / Bilgi'deki adres eklenmez (büyük-küçük harf); geçersiz adres alan hatası adıyla; 11 farklı adres 400; Bilgi toplamı 20'yi aşarsa 400 (tam 20 geçer); aşırı uzun giriş 400")
    void extraCcRules() {
        assertThat(TechErrorReportSender.extraCc(null, List.of("a@example.com"), List.of())).isEmpty();
        assertThat(TechErrorReportSender.extraCc("  \n ", List.of("a@example.com"), List.of())).isEmpty();
        assertThat(TechErrorReportSender.extraCc("Ek@example.com; ek@example.com, DIJITAL@example.com\nyeni@example.com  iki@example.com, cc@example.com",
                List.of("dijital@example.com"), List.of("cc@example.com")))
                .containsExactly("Ek@example.com", "yeni@example.com", "iki@example.com");
        assertThatThrownBy(() -> TechErrorReportSender.extraCc("ok@example.com, bozuk-adres", List.of("a@example.com"), List.of()))
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class)
                .hasMessageContaining("bozuk-adres")
                .extracting(e -> ((com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException) e).getFields().keySet())
                .isEqualTo(java.util.Set.of("extra_cc"));
        StringBuilder eleven = new StringBuilder();
        for (int i = 1; i <= 11; i++) eleven.append("e").append(i).append("@example.com, ");
        assertThatThrownBy(() -> TechErrorReportSender.extraCc(eleven.toString(), List.of("a@example.com"), List.of()))
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class).hasMessageContaining("11");
        // 10 farklı adres + tekrarı = 10 → geçer
        StringBuilder ten = new StringBuilder();
        for (int i = 1; i <= 10; i++) ten.append("e").append(i).append("@example.com, E").append(i).append("@example.com; ");
        assertThat(TechErrorReportSender.extraCc(ten.toString(), List.of("a@example.com"), List.of())).hasSize(10);
        // Bilgi toplamı: takım Bilgi'si 15 + 6 ek = 21 → 400 (sessiz düşmez); 15 + 5 = 20 geçer
        List<String> baseCc = new java.util.ArrayList<>();
        for (int i = 1; i <= 15; i++) baseCc.add("t" + i + "@example.com");
        assertThatThrownBy(() -> TechErrorReportSender.extraCc("x1@example.com, x2@example.com, x3@example.com, x4@example.com, x5@example.com, x6@example.com",
                List.of("ceo@example.com"), baseCc))
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class).hasMessageContaining("21");
        assertThat(TechErrorReportSender.extraCc("x1@example.com, x2@example.com, x3@example.com, x4@example.com, x5@example.com, t1@example.com",
                List.of("ceo@example.com"), baseCc)).hasSize(5);
        assertThatThrownBy(() -> TechErrorReportSender.extraCc("a".repeat(TechErrorReportSender.MAX_EXTRA_CC_INPUT + 1), List.of(), List.of()))
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class);
    }

    @Test
    @DisplayName("R18 / C13 ek Bilgi (gönderim): takıma gönderimde Bilgi = ek adresler, günlük satırı Bilgi'de taşır, geçmiş olayı extra_cc, sonuç cc; yöneticilere gönderimde takım Bilgi'sinin ARDINA eklenir; geçersiz giriş talepten ÖNCE 400 — talep / posta / günlük / geçmiş yok; günlük satırı kanala + rapora bağlı (E22); test postası ek Bilgi'siz ve bağlı")
    void extraCcSend() {
        report.setChannelId(3L);
        mailReturns("SENT");
        var res = sender.send(report, channel, team, 7L, TechErrorReportSender.Delivery.TEAM, "ek@example.com, Dijital@example.com; iki@example.com");
        assertThat(res.state()).isEqualTo("SENT");
        assertThat(res.cc()).containsExactly("ek@example.com", "iki@example.com");    // Kime'deki takım adresi Bilgi'ye eklenmez
        ArgumentCaptor<String[]> cc = ArgumentCaptor.forClass(String[].class);
        verify(email).sendHtml(eq(new String[]{ "dijital@example.com", "ikinci@example.com" }), cc.capture(), anyString(), anyString(), anyString(),
                any(), eq(false), any());
        assertThat(cc.getValue()).containsExactly("ek@example.com", "iki@example.com");
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getCc()).isEqualTo("ek@example.com, iki@example.com");
        assertThat(log.getValue().getTechErrorChannelId()).isEqualTo(3L);
        assertThat(log.getValue().getTechErrorReportId()).isEqualTo(5L);
        verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("SENT"), any(), org.mockito.ArgumentMatchers.isNull(), eq(now),
                eq(Map.of("extra_cc", List.of("ek@example.com", "iki@example.com"))));
        verify(reports).finishSent(eq(5L), eq(now), eq("SENT"), eq(now), eq(7L), eq("dijital@example.com, ikinci@example.com"), eq(0L));   // last_sent_to = Kime

        // Yöneticilere: Bilgi = takım adresi + ek
        org.mockito.Mockito.reset(email, logs, history);
        when(service.history()).thenReturn(history);
        mailReturns("SENT");
        channel.setExecutiveEmails("ceo@example.com");
        report.setApprovalState("APPROVED");
        report.setApprovalRev(3L);
        report.setContentRev(3L);
        var ex = sender.send(report, channel, team, 7L, TechErrorReportSender.Delivery.EXECUTIVE, "ek@example.com");
        assertThat(ex.cc()).containsExactly("dijital@example.com", "ikinci@example.com", "ek@example.com");
        verify(email).sendHtml(eq(new String[]{ "ceo@example.com" }), cc.capture(), anyString(), anyString(), anyString(), any(), eq(false), any());
        assertThat(cc.getValue()).containsExactly("dijital@example.com", "ikinci@example.com", "ek@example.com");

        // Geçersiz giriş: talepten ÖNCE durur — hiçbir şey yazılmaz
        org.mockito.Mockito.reset(email, logs, history, reports);
        when(service.history()).thenReturn(history);
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L, TechErrorReportSender.Delivery.TEAM, "bozuk"))
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class);
        verify(reports, never()).claimSend(anyLong(), any(), any());
        verifyNoInteractions(logs, history, email);

        // Ek Bilgi'siz eski yol: Bilgi yok (takıma gönderim byte-identical)
        org.mockito.Mockito.reset(reports);
        when(reports.claimSend(eq(5L), eq(now), any())).thenReturn(1);
        when(reports.finishSent(anyLong(), any(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        mailReturns("SENT");
        channel.setExecutiveEmails(null);
        var plain = sender.send(report, channel, team, 7L);
        assertThat(plain.cc()).isEmpty();
        verify(email).sendHtml(any(String[].class), org.mockito.ArgumentMatchers.isNull(), anyString(), anyString(), anyString(), any(), eq(false), any());

        // Test postası: ek Bilgi yok, satır SELF ve kanala bağlı
        org.mockito.Mockito.reset(email, logs);
        mailReturns("SENT");
        sender.sendTest(report, channel, team, TechErrorReportSender.Delivery.TEAM, "ben@example.com", "tr");
        verify(email).sendHtml(eq(new String[]{ "ben@example.com" }), org.mockito.ArgumentMatchers.isNull(), anyString(), anyString(), anyString(), any(), eq(false), any());
        verify(logs).save(log.capture());
        assertThat(log.getValue().getRecipientRole()).isEqualTo("SELF");
        assertThat(log.getValue().getCc()).isNull();
        assertThat(log.getValue().getTechErrorChannelId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("inceleme 2026-10-18: pasif hesabın Bilgi adresi günlük satırına da yazılmaz (Kime ile aynı kural) — günlük, yanıt cc ve geçmiş extra_cc aynı liste; yöneticilere gönderimde takım Bilgi'sindeki pasif adres de")
    void passiveCcNotLogged() {
        report.setChannelId(3L);
        sender.inactiveGuard = mock(com.sitemonitor.service.InactiveRecipientGuard.class);
        when(sender.inactiveGuard.isInactiveOnlyEmail("pasif@example.com")).thenReturn(true);
        mailReturns("SENT");
        var res = sender.send(report, channel, team, 7L, TechErrorReportSender.Delivery.TEAM, "ek@example.com, pasif@example.com");
        assertThat(res.cc()).containsExactly("ek@example.com");
        ArgumentCaptor<String[]> mailCc = ArgumentCaptor.forClass(String[].class);
        verify(email).sendHtml(any(String[].class), mailCc.capture(), anyString(), anyString(), anyString(), any(), eq(false), any());
        assertThat(mailCc.getValue()).containsExactly("ek@example.com", "pasif@example.com");   // posta hunisi kendisi düşürür
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getCc()).isEqualTo("ek@example.com");
        verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("SENT"), any(), org.mockito.ArgumentMatchers.isNull(), eq(now),
                eq(Map.of("extra_cc", List.of("ek@example.com"))));

        // Yöneticilere: takım Bilgi'sindeki pasif adres günlüğe yazılmaz
        org.mockito.Mockito.reset(email, logs);
        mailReturns("SENT");
        when(sender.inactiveGuard.isInactiveOnlyEmail("ikinci@example.com")).thenReturn(true);
        channel.setExecutiveEmails("ceo@example.com");
        report.setApprovalState("APPROVED");
        report.setApprovalRev(3L);
        report.setContentRev(3L);
        var ex = sender.send(report, channel, team, 7L, TechErrorReportSender.Delivery.EXECUTIVE);
        assertThat(ex.cc()).containsExactly("dijital@example.com");
        verify(logs).save(log.capture());
        assertThat(log.getValue().getCc()).isEqualTo("dijital@example.com");

        // Kural tek yerde: servise ulaşmadı → istenen; Bilgi'nin hepsi pasif + Kime etkin → Bilgi yok; hepsi pasif → istenen
        String[] cc = { "a@example.com", "b@example.com" };
        assertThat(TechErrorReportSender.loggedCc(cc, null, List.of("to@example.com"))).isSameAs(cc);
        assertThat(TechErrorReportSender.loggedCc(cc, List.of("a@example.com"), List.of("to@example.com"))).containsExactly("a@example.com");
        assertThat(TechErrorReportSender.loggedCc(cc, List.of(), List.of("to@example.com"))).isNull();
        assertThat(TechErrorReportSender.loggedCc(cc, List.of(), List.of())).isSameAs(cc);
        assertThat(TechErrorReportSender.loggedCc(null, List.of(), List.of())).isNull();
    }

    @Test
    @DisplayName("inceleme 2026-10-18: takım adresinin HEPSİ pasif + etkin ek Bilgi → ek Bilgi postaya eklenmez; gönderim ek Bilgi yokmuş gibi atlanır (SKIPPED pasif → 409 MAIL_DISABLED); rapor 'gönderildi' olmaz, last_sent_to yazılmaz; geçmiş istenen ek adresleri taşır. Yöneticilere gönderimde takım Bilgi'si etkinse ek Bilgi eklenir (tek başına değil)")
    void extraCcAloneNeverSends() {
        report.setChannelId(3L);
        sender.inactiveGuard = mock(com.sitemonitor.service.InactiveRecipientGuard.class);
        when(sender.inactiveGuard.isInactiveOnlyEmail("dijital@example.com")).thenReturn(true);
        when(sender.inactiveGuard.isInactiveOnlyEmail("ikinci@example.com")).thenReturn(true);
        mailReturns(com.sitemonitor.service.InactiveRecipientGuard.STATUS_SKIPPED);   // posta hunisi: Kime'nin hepsi pasif, Bilgi yok
        var res = sender.send(report, channel, team, 7L, TechErrorReportSender.Delivery.TEAM, "ek@example.com");
        assertThat(res.state()).isEqualTo("SKIPPED");
        assertThat(res.cc()).isEmpty();
        verify(email).sendHtml(eq(new String[]{ "dijital@example.com", "ikinci@example.com" }), org.mockito.ArgumentMatchers.isNull(),
                anyString(), anyString(), anyString(), any(), eq(false), any());
        verify(reports, never()).finishSent(anyLong(), any(), anyString(), any(), any(), anyString(), any());
        verify(reports).finishNotSent(eq(5L), eq(now), eq("SKIPPED"), eq(com.sitemonitor.service.InactiveRecipientGuard.STATUS_SKIPPED), eq(now));
        verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("SKIPPED"), org.mockito.ArgumentMatchers.isNull(),
                eq("INACTIVE_RECIPIENTS"), eq(now), eq(Map.of("extra_cc", List.of("ek@example.com"))));
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getCc()).isNull();
        assertThat(log.getValue().getRecipientEmail()).isEqualTo("dijital@example.com, ikinci@example.com");   // "neden gitmedi"

        // Yöneticiler pasif ama takım Bilgi'si etkin: ek Bilgi tek başına değil → eklenir (eski yöneticiler yolu aynen)
        org.mockito.Mockito.reset(email, logs);
        mailReturns("SENT");
        when(sender.inactiveGuard.isInactiveOnlyEmail("dijital@example.com")).thenReturn(false);
        when(sender.inactiveGuard.isInactiveOnlyEmail("ceo@example.com")).thenReturn(true);
        channel.setExecutiveEmails("ceo@example.com");
        report.setApprovalState("APPROVED");
        report.setApprovalRev(3L);
        report.setContentRev(3L);
        sender.send(report, channel, team, 7L, TechErrorReportSender.Delivery.EXECUTIVE, "ek@example.com");
        ArgumentCaptor<String[]> mailCc = ArgumentCaptor.forClass(String[].class);
        verify(email).sendHtml(eq(new String[]{ "ceo@example.com" }), mailCc.capture(), anyString(), anyString(), anyString(), any(), eq(false), any());
        assertThat(mailCc.getValue()).containsExactly("dijital@example.com", "ikinci@example.com", "ek@example.com");
    }

    @Test
    @DisplayName("inceleme 2026-10-18 (2. tur): yöneticilerin HEPSİ pasif, takım Bilgi'si etkin → posta Bilgi'ye gitti: günlük satırı SENT, Kime BOŞ (pasif adresler listelenmez), Bilgi etkin adresler; yanıt to boş, cc dolu. Kural tek yerde (loggedTo = loggedCc'nin aynası)")
    void passiveToNotLoggedWhenCcReceived() {
        report.setChannelId(3L);
        sender.inactiveGuard = mock(com.sitemonitor.service.InactiveRecipientGuard.class);
        when(sender.inactiveGuard.isInactiveOnlyEmail("ceo@example.com")).thenReturn(true);
        mailReturns("SENT");
        channel.setExecutiveEmails("ceo@example.com");
        report.setApprovalState("APPROVED");
        report.setApprovalRev(3L);
        report.setContentRev(3L);
        var res = sender.send(report, channel, team, 7L, TechErrorReportSender.Delivery.EXECUTIVE);
        assertThat(res.state()).isEqualTo("SENT");
        assertThat(res.to()).isEmpty();
        assertThat(res.cc()).containsExactly("dijital@example.com", "ikinci@example.com");
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).isEqualTo("SENT");
        assertThat(log.getValue().getRecipientEmail()).isEmpty();
        assertThat(log.getValue().getCc()).isEqualTo("dijital@example.com, ikinci@example.com");

        // Kural: servise ulaşmadı → istenen; Kime etkin → etkinler; Kime hepsi pasif + Bilgi etkin → boş; hepsi pasif → istenen
        List<String> to = List.of("a@example.com", "b@example.com");
        assertThat(TechErrorReportSender.loggedTo(to, null, null)).isSameAs(to);
        assertThat(TechErrorReportSender.loggedTo(to, List.of("a@example.com"), List.of())).containsExactly("a@example.com");
        assertThat(TechErrorReportSender.loggedTo(to, List.of(), List.of("cc@example.com"))).isEmpty();
        assertThat(TechErrorReportSender.loggedTo(to, List.of(), List.of())).isSameAs(to);
        assertThat(TechErrorReportSender.loggedTo(to, List.of(), null)).isSameAs(to);
        assertThat(TechErrorReportSender.loggedTo(List.of(), null, null)).isEmpty();
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
        sender.maxMailMb = 5;
        pdfReturns(new byte[5 * 1024 * 1024 - 100]);             // 5 MB sınırının 100 bayt altında
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
    @DisplayName("12. sürüm (E21): posta sınırları CANLI — Ayarlar'dan kaydedilen değer yeniden başlatmadan uygulanır (önizleme, gönderim, onay postası); aralık dışı / bozuk override önyükleme değerine düşer")
    void mailLimitsAreReadLive() {
        pdfReturns(new byte[6 * 1024 * 1024]);
        when(settings.getOverride(TechErrorReportSettings.KEY_MAX_MAIL_MB)).thenReturn("5");
        TechErrorReportSender.Preview p = sender.preview(report, channel, team, TechErrorReportSender.Delivery.TEAM);
        assertThat(p.maxMailMb()).isEqualTo(5);
        assertThat(p.problem()).isEqualTo("PDF_TOO_LARGE");
        assertThat(sender.maxMailBytes()).isEqualTo(5L * 1024 * 1024);
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(CodedConflictException.class)
                .satisfies(e -> {
                    assertThat(((CodedConflictException) e).getCode()).isEqualTo("PDF_TOO_LARGE");
                    assertThat(e.getMessage()).contains("5 MB");
                });
        // Aynı gönderen nesnesi, ayar değişti: yeniden başlatma yok
        when(settings.getOverride(TechErrorReportSettings.KEY_MAX_MAIL_MB)).thenReturn("20");
        TechErrorReportSender.Preview p2 = sender.preview(report, channel, team, TechErrorReportSender.Delivery.TEAM);
        assertThat(p2.maxMailMb()).isEqualTo(20);
        assertThat(p2.problem()).isNull();
        when(settings.getOverride(TechErrorReportSettings.KEY_MAX_MAIL_MB)).thenReturn("99");
        assertThat(sender.liveMaxMailMb()).as("aralık dışı → önyükleme değeri").isEqualTo(sender.maxMailMb);
        when(settings.getOverride(TechErrorReportSettings.KEY_MAIL_INLINE_MB)).thenReturn("0");
        assertThat(sender.liveInlineMb()).isZero();
        when(settings.getOverride(TechErrorReportSettings.KEY_MAIL_INLINE_MB)).thenReturn(null);
        assertThat(sender.liveInlineMb()).isEqualTo(sender.mailInlineMb);
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
        org.mockito.Mockito.doThrow(new TechErrorReportService.PdfBusyException("meşgul")).when(service).renderPdf(any(), anyLong());
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(TechErrorReportService.PdfBusyException.class);
        verify(history).recordSend(eq(report), eq("SEND"), eq(7L), eq("SKIPPED"), org.mockito.ArgumentMatchers.isNull(),
                eq("PDF_BUSY"), eq(now));

        org.mockito.Mockito.reset(history);
        sender.maxMailMb = 5;
        pdfReturns(new byte[6 * 1024 * 1024]);
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
        org.mockito.Mockito.doThrow(new TechErrorReportService.PdfBusyException("meşgul")).when(service).renderPdf(any(), anyLong());
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
        sender.maxMailMb = 5;
        pdfReturns(new byte[6 * 1024 * 1024]);                    // PDF çok büyük → posta servisine hiçbir şey gitmez
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
    @DisplayName("son inceleme 2026-10-18: bu gönderim için zaten push alan kişi (onay kararının push'u isteyene 'kuyrukta' dedi) kuyrukla saklanır — teslimde takımın 'gönderildi' bildiriminden düşülür; kimse yoksa eski yazım (kolon temizlenir)")
    void queuedKeepsTheAlreadyNotifiedPerson() {
        when(logs.save(any())).thenAnswer(inv -> {
            NotificationLog n = inv.getArgument(0);
            n.setId(77L);
            return n;
        });
        report.setContentRev(3L);
        mailReturns("QUEUED_RETRY: 421");
        assertThat(sender.send(report, channel, team, 8L, TechErrorReportSender.Delivery.TEAM, null, java.util.Set.of(9L)).state())
                .isEqualTo("QUEUED");
        verify(reports).finishQueuedNotified(eq(5L), eq(now), eq("QUEUED_RETRY: 421"), eq(now), eq(77L), eq(8L),
                eq("dijital@example.com, ikinci@example.com"), eq(3L), eq(9L));
        verify(reports, never()).finishQueued(anyLong(), any(), anyString(), any(), any(), any(), anyString(), any());

        org.mockito.Mockito.clearInvocations(reports);
        sender.send(report, channel, team, 8L, TechErrorReportSender.Delivery.TEAM, null, java.util.Arrays.asList(null, null));
        verify(reports).finishQueued(eq(5L), eq(now), eq("QUEUED_RETRY: 421"), eq(now), eq(77L), eq(8L), anyString(), eq(3L));
        verify(reports, never()).finishQueuedNotified(anyLong(), any(), anyString(), any(), any(), any(), anyString(), any(), any());
        assertThat(TechErrorReportSender.firstId(null)).isNull();
        assertThat(TechErrorReportSender.firstId(java.util.Arrays.asList(null, 4L, 5L))).isEqualTo(4L);
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
        org.mockito.Mockito.doThrow(new TechErrorReportService.PdfBusyException("meşgul")).when(service).renderPdf(any(), anyLong());
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
        org.mockito.Mockito.doThrow(new TechErrorReportService.PdfBusyException("meşgul")).when(service).renderPdf(any(), anyLong());
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
        sender.maxMailMb = 5;
        pdfReturns(new byte[6 * 1024 * 1024]);
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(CodedConflictException.class)
                .extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("PDF_TOO_LARGE");
        verify(reports).finishNotSent(eq(5L), eq(now), eq("FAILED"), org.mockito.ArgumentMatchers.startsWith("FAILED: PDF"), eq(now));
        verify(logs).save(any());
        verify(email, never()).sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("talepten sonra beklenmeyen istisna: satır FAILED'e yazılır, iz bırakılır, istisna yukarı gider")
    void unexpectedFailureFinishes() {
        org.mockito.Mockito.doThrow(new IllegalStateException("bozuk")).when(service).renderPdf(any(), anyLong());
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(IllegalStateException.class);
        verify(reports).finishNotSent(eq(5L), eq(now), eq("FAILED"), eq("FAILED: IllegalStateException"), eq(now));
        verify(logs).save(any());
    }

    // ── Kendine test postası (R13 / B5, 2026-10-17) ───────────────────────────────────────────────

    @Test
    @DisplayName("kendine test: konu [TEST] önekli, banddan sonra test kutusu (okuyanın dilinde), dipnot teslim yolununki; YALNIZ verilen adrese, CC yok, PDF eki; talep / geçmiş / finishSent yok; günlük satırı TECH_ERROR_REPORT_TEST rol SELF")
    void sendTest_sendsOnlyToSelf() throws Exception {
        mailReturns("SENT");
        TechErrorPdfInput withImage = new TechErrorPdfInput(TechErrorTemplate.defaults("tr"), TechErrorValues.Values.empty(), "Mobil",
                "Dijital", 2026, 9, "Ayşe", now, Map.of("annual-summary", new TechErrorPdfInput.Image(samplePng(1600, 400), 1600, 400)));
        when(service.pdfInput(any(), any(), any())).thenReturn(withImage);
        var res = sender.sendTest(report, channel, team, TechErrorReportSender.Delivery.TEAM, "  ayse@example.com ", "tr");
        assertThat(res.state()).isEqualTo("SENT");
        assertThat(res.to()).containsExactly("ayse@example.com");
        assertThat(res.subject()).isEqualTo("[TEST] " + TechErrorReportMail.subject(withImage));
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EmailNotificationService.MailAttachment>> att = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EmailNotificationService.InlineImage>> inline = ArgumentCaptor.forClass(List.class);
        verify(email).sendHtml(eq(new String[]{ "ayse@example.com" }), org.mockito.ArgumentMatchers.isNull(), eq(res.subject()),
                html.capture(), anyString(), inline.capture(), eq(false), att.capture());
        assertThat(html.getValue()).contains("Bu bir TEST gönderimidir").contains("takıma / yöneticilere gönderilmedi");
        assertThat(html.getValue()).contains("takımının adresi olduğu");                 // TEAM dipnotu — gerçek posta gibi okunur
        assertThat(html.getValue()).contains("TEKNİK HATA RAPORU").contains("cid:te-img-annual-summary");  // gövde gönderimle aynı, görsel gömülü
        assertThat(inline.getValue()).hasSize(1);
        assertThat(att.getValue()).hasSize(1);
        assertThat(att.getValue().get(0).fileName()).isEqualTo("mobil-teknik-hata-2026-09.pdf");
        assertThat(att.getValue().get(0).contentType()).isEqualTo("application/pdf");
        // Rapor "gönderildi" OLMAZ: talep, sonuç yazımı, geçmiş yok
        verify(reports, never()).claimSend(anyLong(), any(), any());
        verify(reports, never()).finishSent(anyLong(), any(), anyString(), any(), any(), anyString(), any());
        verify(reports, never()).finishQueued(anyLong(), any(), anyString(), any(), any(), any(), anyString(), any());
        verify(reports, never()).finishNotSent(anyLong(), any(), anyString(), anyString(), any());
        verify(reports, never()).releaseClaim(anyLong(), any(), any());
        verifyNoInteractions(history);
        // Günlük satırı: ayrı tetikleyici, rol SELF, alıcı = kullanıcının adresi, takım adı, alarm kimliği 0
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getTrigger()).isEqualTo("TECH_ERROR_REPORT_TEST");
        assertThat(log.getValue().getRecipientRole()).isEqualTo("SELF");
        assertThat(log.getValue().getRecipientEmail()).isEqualTo("ayse@example.com");
        assertThat(log.getValue().getRecipientName()).isEqualTo("Dijital");
        assertThat(log.getValue().getSubject()).isEqualTo(res.subject());
        assertThat(log.getValue().getEmailStatus()).isEqualTo("SENT");
        assertThat(log.getValue().getAlertEventId()).isZero();
        assertThat(log.getValue().getCc()).isNull();
        // EXECUTIVE yolu: dipnot yönetici cümlesi, test kutusu İngilizce (okuyanın dili), alıcı yine yalnız kendisi
        org.mockito.Mockito.reset(email);
        mailReturns("SENT");
        channel.setExecutiveEmails("ceo@example.com");
        var ex = sender.sendTest(report, channel, team, TechErrorReportSender.Delivery.EXECUTIVE, "ayse@example.com", "en");
        assertThat(ex.to()).containsExactly("ayse@example.com");
        verify(email).sendHtml(eq(new String[]{ "ayse@example.com" }), org.mockito.ArgumentMatchers.isNull(), anyString(),
                html.capture(), anyString(), any(), eq(false), any());
        assertThat(html.getValue()).contains("This is a TEST send").contains("yönetici (executive) alıcısı").doesNotContain("ceo@example.com");
        verify(reports, never()).claimSend(anyLong(), any(), any());
        verifyNoInteractions(history);
    }

    @Test
    @DisplayName("kendine test: posta servisinin cevabı aynen kaydedilir — QUEUED_RETRY yalnız yazılır (finishQueued / sonuçlandırma yok), SKIPPED / FAILED da bir satır")
    void sendTest_recordsMailAnswer() {
        mailReturns("QUEUED_RETRY 421 2/3");
        var q = sender.sendTest(report, channel, team, TechErrorReportSender.Delivery.TEAM, "ayse@example.com", "tr");
        assertThat(q.state()).isEqualTo("QUEUED");
        assertThat(q.status()).isEqualTo("QUEUED_RETRY 421 2/3");
        mailReturns("SKIPPED_DISABLED");
        assertThat(sender.sendTest(report, channel, team, TechErrorReportSender.Delivery.TEAM, "ayse@example.com", "tr").state()).isEqualTo("SKIPPED");
        mailReturns("FAILED: 554");
        assertThat(sender.sendTest(report, channel, team, TechErrorReportSender.Delivery.TEAM, "ayse@example.com", "tr").state()).isEqualTo("FAILED");
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs, org.mockito.Mockito.times(3)).save(log.capture());
        assertThat(log.getAllValues()).extracting(NotificationLog::getEmailStatus).containsExactly("QUEUED_RETRY 421 2/3", "SKIPPED_DISABLED", "FAILED: 554");
        assertThat(log.getAllValues()).extracting(NotificationLog::getTrigger).containsOnly("TECH_ERROR_REPORT_TEST");
        verify(reports, never()).finishQueued(anyLong(), any(), anyString(), any(), any(), any(), anyString(), any());
        verify(reports, never()).claimSend(anyLong(), any(), any());
        verifyNoInteractions(history);
    }

    @Test
    @DisplayName("kendine test: PDF tek başına sınırı aşıyor → 409 PDF_TOO_LARGE (gönderimle aynı metin), posta yok, günlük FAILED satırı; PDF + görseller sınırı aşıyor → görseller yalnız PDF'te, posta gider")
    void sendTest_pdfTooLargeAndSumRule() throws Exception {
        sender.maxMailMb = 5;
        pdfReturns(new byte[6 * 1024 * 1024]);
        assertThatThrownBy(() -> sender.sendTest(report, channel, team, TechErrorReportSender.Delivery.TEAM, "ayse@example.com", "tr"))
                .isInstanceOf(CodedConflictException.class)
                .satisfies(e -> {
                    assertThat(((CodedConflictException) e).getCode()).isEqualTo("PDF_TOO_LARGE");
                    assertThat(e.getMessage()).contains("6.0 MB").contains("5 MB");
                    assertThat(((CodedConflictException) e).getExtra()).containsEntry("max_mb", 5);
                });
        verify(email, never()).sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any());
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getTrigger()).isEqualTo("TECH_ERROR_REPORT_TEST");
        assertThat(log.getValue().getEmailStatus()).startsWith("FAILED: PDF e-posta sınırını aşıyor (6.0 MB > 5 MB)");
        assertThat(log.getValue().getSubject()).startsWith("[TEST] ");
        verify(reports, never()).claimSend(anyLong(), any(), any());
        verifyNoInteractions(history);
        // Gönderimdeki metinle aynı (tek kaynak): send de aynı kodu ve metni verir
        when(reports.claimSend(eq(5L), eq(now), any())).thenReturn(1);
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(CodedConflictException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("6.0 MB").contains("5 MB"));
        // Toplam kuralı: PDF sınıra yakın + gömülü görsel → görseller düşer ("PDF ekinde"), test postası yine gider
        org.mockito.Mockito.reset(logs, email);
        mailReturns("SENT");
        TechErrorPdfInput withImage = new TechErrorPdfInput(TechErrorTemplate.defaults("tr"), TechErrorValues.Values.empty(), "Mobil",
                "Dijital", 2026, 9, "Ayşe", now, Map.of("annual-summary", new TechErrorPdfInput.Image(samplePng(1600, 400), 1600, 400)));
        when(service.pdfInput(any(), any(), any())).thenReturn(withImage);
        pdfReturns(new byte[5 * 1024 * 1024 - 64]);
        var res = sender.sendTest(report, channel, team, TechErrorReportSender.Delivery.TEAM, "ayse@example.com", "tr");
        assertThat(res.state()).isEqualTo("SENT");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<EmailNotificationService.InlineImage>> inline = ArgumentCaptor.forClass(List.class);
        verify(email).sendHtml(any(String[].class), any(), anyString(), html.capture(), anyString(), inline.capture(), eq(false), any());
        assertThat(inline.getValue()).isEmpty();
        assertThat(html.getValue()).contains("te-image-pdf").doesNotContain("cid:te-img-");
    }

    @Test
    @DisplayName("kendine test: PDF sırası dolu → PdfBusyException aynen, günlük SKIPPED satırı; beklenmeyen hata → FAILED sınıf adıyla; adres teslim edilemezse NO_EMAIL ve HİÇBİR şey yazılmaz")
    void sendTest_busyUnexpectedAndNoEmail() {
        org.mockito.Mockito.doThrow(new TechErrorReportService.PdfBusyException("meşgul")).when(service).renderPdf(any(), anyLong());
        assertThatThrownBy(() -> sender.sendTest(report, channel, team, TechErrorReportSender.Delivery.TEAM, "ayse@example.com", "tr"))
                .isInstanceOf(TechErrorReportService.PdfBusyException.class);
        ArgumentCaptor<NotificationLog> log = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).isEqualTo("SKIPPED: PDF sırası dolu, gönderilmedi");
        assertThat(log.getValue().getSubject()).isEqualTo("[TEST] Mobil");              // konu henüz yok → kanal adı
        verify(email, never()).sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any());

        org.mockito.Mockito.reset(logs);
        pdfReturns(new byte[]{ '%', 'P', 'D', 'F' });
        when(email.sendHtml(any(String[].class), any(), anyString(), anyString(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("smtp"));
        assertThatThrownBy(() -> sender.sendTest(report, channel, team, TechErrorReportSender.Delivery.TEAM, "ayse@example.com", "tr"))
                .isInstanceOf(IllegalStateException.class).hasMessage("smtp");
        verify(logs).save(log.capture());
        assertThat(log.getValue().getEmailStatus()).isEqualTo("FAILED: IllegalStateException");

        org.mockito.Mockito.reset(logs, email);
        for (String bad : new String[]{ null, "", "  ", "yanlis adres", "a@b" }) {
            assertThatThrownBy(() -> sender.sendTest(report, channel, team, TechErrorReportSender.Delivery.TEAM, bad, "tr"))
                    .isInstanceOf(CodedConflictException.class)
                    .extracting(e -> ((CodedConflictException) e).getCode()).isEqualTo("NO_EMAIL");
        }
        verifyNoInteractions(logs, email, history);
        verify(reports, never()).claimSend(anyLong(), any(), any());
    }

    @Test
    @DisplayName("inceleme 2026-10-18: posta sınırı gönderim / önizleme / test başına BİR kez okunur ve PDF merdivenine o değer verilir — izin beklenirken ayar değişse de merdiven ile 'sığıyor mu' denetimi aynı sınıra bakar")
    void mailLimitIsReadOncePerRender() {
        pdfReturns(new byte[6 * 1024 * 1024]);
        // İlk okuma 5 MB, sonraki okumalar 20 MB: tek okuma → merdiven ve denetim 5 MB (PDF_TOO_LARGE "5 MB")
        when(settings.getOverride(TechErrorReportSettings.KEY_MAX_MAIL_MB)).thenReturn("5", "20");
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(CodedConflictException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("5 MB"));
        verify(service).renderPdf(any(), eq(5L * 1024 * 1024));
        verify(settings, org.mockito.Mockito.times(1)).getOverride(TechErrorReportSettings.KEY_MAX_MAIL_MB);
        // Önizleme de aynı: tek okuma, aynı değer
        org.mockito.Mockito.clearInvocations(service, settings);
        when(settings.getOverride(TechErrorReportSettings.KEY_MAX_MAIL_MB)).thenReturn("20", "5");
        TechErrorReportSender.Preview p = sender.preview(report, channel, team, TechErrorReportSender.Delivery.TEAM);
        assertThat(p.maxMailMb()).isEqualTo(20);
        assertThat(p.pdfTooLarge()).isFalse();
        verify(service).renderPdf(any(), eq(20L * 1024 * 1024));
        verify(settings, org.mockito.Mockito.times(1)).getOverride(TechErrorReportSettings.KEY_MAX_MAIL_MB);
    }

    // ── Push bildirimleri (2026-10-18) ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("push: SENT → takım bildirimi (gönderen + zaten bildirilen hariç; sayaç + talep anı); FAILED → gönderene; QUEUED, PDF sırası dolu ve test postası push YOK; push hatası sonucu değiştirmez")
    void pushOutcomes() {
        TechErrorPushService pushes = mock(TechErrorPushService.class);
        sender.setPushes(pushes);
        report.setTeamId(3L);
        report.setContentRev(4L);
        when(service.displayName(7L)).thenReturn("Teknik Kişi");
        mailReturns("SENT");
        sender.send(report, channel, team, 7L, TechErrorReportSender.Delivery.TEAM, null, List.of(11L));
        verify(pushes).delivered(argThat(c -> c.reportId() == 5L && c.teamId() == 3L && "Mobil".equals(c.channelName())), eq(false),
                eq(7L), eq("Teknik Kişi"), eq(4L), eq(now), eq(List.of(11L)));
        verify(pushes, never()).sendFailed(any(), anyBoolean(), any(), any(), any(), any());

        org.mockito.Mockito.clearInvocations(pushes);
        mailReturns("FAILED: 550 kutu yok");
        assertThat(sender.send(report, channel, team, 7L).state()).isEqualTo("FAILED");
        verify(pushes).sendFailed(any(), eq(false), eq(7L), eq(TechErrorPushService.Failure.FAILED), org.mockito.ArgumentMatchers.isNull(), eq(now));
        verify(pushes, never()).delivered(any(), anyBoolean(), any(), any(), any(), any(), any());

        // PDF çok büyük: FAILED yazılır → gönderene, sebep kodu PDF_TOO_LARGE
        org.mockito.Mockito.clearInvocations(pushes);
        sender.maxMailMb = 5;
        pdfReturns(new byte[6 * 1024 * 1024]);
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(CodedConflictException.class);
        verify(pushes).sendFailed(any(), eq(false), eq(7L), eq(TechErrorPushService.Failure.FAILED), eq("PDF_TOO_LARGE"), eq(now));
        sender.maxMailMb = 15;
        pdfReturns(new byte[]{ '%', 'P', 'D', 'F' });

        // QUEUED: teslim bilinmiyor → push kuyruk sonuçlanınca (reconcileQueued), burada YOK
        org.mockito.Mockito.clearInvocations(pushes);
        when(reports.finishQueued(anyLong(), any(), anyString(), any(), any(), any(), anyString(), any())).thenReturn(1);
        mailReturns("QUEUED_RETRY: 421");
        sender.send(report, channel, team, 7L);
        org.mockito.Mockito.verifyNoInteractions(pushes);

        // PDF sırası dolu: talep bırakılır → push yok
        org.mockito.Mockito.doThrow(new TechErrorReportService.PdfBusyException("meşgul")).when(service).renderPdf(any(), anyLong());
        assertThatThrownBy(() -> sender.send(report, channel, team, 7L)).isInstanceOf(TechErrorReportService.PdfBusyException.class);
        org.mockito.Mockito.verifyNoInteractions(pushes);
        pdfReturns(new byte[]{ '%', 'P', 'D', 'F' });

        // Kendine test postası: push yok
        sender.sendTest(report, channel, team, TechErrorReportSender.Delivery.TEAM, "ben@example.com", "tr");
        org.mockito.Mockito.verifyNoInteractions(pushes);

        // Sonuç yazılamadı (talep başka gönderime geçti): push yok — iki pod / iki sekme aynı sonucu iki kez bildirmez
        when(reports.finishSent(anyLong(), any(), anyString(), any(), any(), anyString(), any())).thenReturn(0);
        mailReturns("SENT");
        sender.send(report, channel, team, 7L);
        org.mockito.Mockito.verifyNoInteractions(pushes);

        // Push hatası gönderim sonucunu değiştirmez
        when(reports.finishSent(anyLong(), any(), anyString(), any(), any(), anyString(), any())).thenReturn(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("push yok")).when(pushes)
                .delivered(any(), anyBoolean(), any(), any(), any(), any(), any());
        assertThat(sender.send(report, channel, team, 7L).state()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("push: yerine geçilen eski kuyruğun sahibi (başka kişi) — bu deneme de ulaşmadıysa 'sonucu alınamadı'; bu deneme gönderdiyse ya da aynı kişiyse yok")
    void pushSupersededQueueOwner() {
        TechErrorPushService pushes = mock(TechErrorPushService.class);
        sender.setPushes(pushes);
        report.setTeamId(3L);
        Instant old = now.minusSeconds(3600);                  // pencere dışı: sonucu artık gelemez → kuyruk yaşatılmaz
        TechErrorReport queued = new TechErrorReport();
        queued.setId(5L);
        queued.setSendState("QUEUED");
        queued.setSendClaimedAt(old);
        queued.setQueuedById(8L);
        when(reports.findById(5L)).thenReturn(java.util.Optional.of(queued));
        report.setLastSendStatus("QUEUED_RETRY: 421");
        mailReturns("FAILED: 550");
        sender.send(report, channel, team, 7L);
        verify(pushes).sendFailed(any(), eq(false), eq(7L), eq(TechErrorPushService.Failure.FAILED), any(), eq(now));
        verify(pushes).sendFailed(any(), eq(false), eq(8L), eq(TechErrorPushService.Failure.UNKNOWN), org.mockito.ArgumentMatchers.isNull(), eq(old));

        org.mockito.Mockito.clearInvocations(pushes);
        mailReturns("SENT");
        sender.send(report, channel, team, 7L);
        verify(pushes, never()).sendFailed(any(), anyBoolean(), any(), any(), any(), any());
        verify(pushes).delivered(any(), eq(false), eq(7L), any(), any(), eq(now), any());

        org.mockito.Mockito.clearInvocations(pushes);
        queued.setQueuedById(7L);                              // aynı kişi: zaten kendi başarısızlık push'unu alır
        mailReturns("FAILED: 550");
        sender.send(report, channel, team, 7L);
        verify(pushes, times(1)).sendFailed(any(), anyBoolean(), any(), any(), any(), any());
    }
}
