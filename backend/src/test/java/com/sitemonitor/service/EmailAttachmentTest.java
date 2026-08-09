package com.sitemonitor.service;

import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.NotificationLogRepository;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dosya EKİ (attachment) desteği — aylık envanter raporunun CSV/PDF ekleri.
 *
 * <p>Bu yetenek repoda YOKTU ({@code addAttachment} hiç çağrılmıyordu), bu yüzden MIME yapısı
 * gerçek {@link MimeMessage} üzerinden doğrulanır: ekler ATTACHMENT disposition ile mi geliyor
 * (inline değil), dosya adları ve içerik tipleri doğru mu, ve marka logosu hâlâ INLINE mı.
 * Gerçek SMTP yok — sender mock'lanır, mesaj bellekte gezilir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailAttachmentTest {

    @Mock SmtpSettingsService settingsService;
    @Mock SmtpMailService smtpMailService;
    @Mock JavaMailSenderImpl sender;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock AppSettingsService appSettings;

    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(i -> i.getArgument(1));
        EmailTemplateBuilder tb = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(tb, "appBaseUrl", "http://localhost:5173");
        service = new EmailNotificationService(settingsService, smtpMailService, notificationLogRepo, appSettings, tb);

        SmtpSettings s = new SmtpSettings();
        s.setEnabled(true);
        s.setFromAddress("noreply@sitemonitor");
        s.setRetryDelayMs(90000);
        when(settingsService.getOrDefaults()).thenReturn(s);
        when(smtpMailService.currentSender()).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    /** saveChanges() ŞART: Content-Type başlıkları updateHeaders() sırasında yazılır; öncesinde
     *  MimeBodyPart.getContentType() varsayılan "text/plain" döner (EmailBrandCidTest aynı sebeple çağırır). */
    private MimeMessage sent() throws Exception {
        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(cap.capture());
        MimeMessage msg = cap.getValue();
        msg.saveChanges();
        return msg;
    }

    /** MIME ağacını gezip ATTACHMENT disposition'lı parçaları toplar. */
    private static List<MimeBodyPart> attachments(Part part) throws Exception {
        List<MimeBodyPart> out = new ArrayList<>();
        collect(part, out);
        return out;
    }

    private static void collect(Part part, List<MimeBodyPart> out) throws Exception {
        if (part.getContent() instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) collect(mp.getBodyPart(i), out);
        } else if (part instanceof MimeBodyPart bp && Part.ATTACHMENT.equalsIgnoreCase(bp.getDisposition())) {
            out.add(bp);
        }
    }

    private static final String HTML =
            "<html><body>" + BrandMailAssets.headerLockup() + "<p>rapor</p></body></html>";

    @Test
    @DisplayName("CSV + PDF ekleri ATTACHMENT olarak iliştirilir; ad ve içerik tipi korunur")
    void attachmentsAreAttached() throws Exception {
        byte[] csv = "domain,port\nwww.akbank.com,443\n".getBytes(StandardCharsets.UTF_8);
        byte[] pdf = "%PDF-1.7\n%fake".getBytes(StandardCharsets.ISO_8859_1);

        String status = service.sendHtmlWithAttachments(
                new String[]{ "sertifika@akbank.com" }, null, "[Site Monitor] Envanter", HTML, null,
                List.of(new EmailNotificationService.MailAttachment("envanter.csv", csv, "text/csv"),
                        new EmailNotificationService.MailAttachment("envanter.pdf", pdf, "application/pdf")));

        assertThat(status).doesNotStartWith("FAILED");
        List<MimeBodyPart> parts = attachments(sent());
        assertThat(parts).hasSize(2);
        assertThat(parts.stream().map(p -> {
            try { return p.getFileName(); } catch (Exception e) { return null; }
        })).containsExactlyInAnyOrder("envanter.csv", "envanter.pdf");
        List<String> types = parts.stream().map(p -> {
            try { return p.getContentType(); } catch (Exception e) { return ""; }
        }).toList();
        assertThat(types).as("ek içerik tipleri: %s", types)
                .anyMatch(ct -> ct.contains("text/csv"))
                .anyMatch(ct -> ct.contains("application/pdf"));
    }

    @Test
    @DisplayName("Marka logosu INLINE kalır — ek olarak listelenmez")
    void brandLogoStaysInline() throws Exception {
        service.sendHtmlWithAttachments(new String[]{ "a@b.com" }, null, "konu", HTML, null,
                List.of(new EmailNotificationService.MailAttachment(
                        "x.csv", "a,b\n".getBytes(StandardCharsets.UTF_8), "text/csv")));

        List<MimeBodyPart> parts = attachments(sent());
        assertThat(parts).hasSize(1);                       // yalnız CSV; logo inline tarafta
        assertThat(parts.get(0).getFileName()).isEqualTo("x.csv");
    }

    @Test
    @DisplayName("Boş/null ek atlanır — mail yine gider (rapor tamamen düşmesin)")
    void emptyAttachmentsAreSkipped() throws Exception {
        String status = service.sendHtmlWithAttachments(new String[]{ "a@b.com" }, null, "konu", HTML, null,
                java.util.Arrays.asList(
                        new EmailNotificationService.MailAttachment("bos.pdf", new byte[0], "application/pdf"),
                        null,
                        new EmailNotificationService.MailAttachment("dolu.csv",
                                "x\n".getBytes(StandardCharsets.UTF_8), "text/csv")));

        assertThat(status).doesNotStartWith("FAILED");
        List<MimeBodyPart> parts = attachments(sent());
        assertThat(parts).hasSize(1);
        assertThat(parts.get(0).getFileName()).isEqualTo("dolu.csv");
    }

    @Test
    @DisplayName("Ek verilmezse davranış eskisiyle aynı (regresyon yok)")
    void noAttachmentsBehavesLikeBefore() throws Exception {
        String status = service.sendHtml(new String[]{ "a@b.com" }, null, "konu", HTML, null);

        assertThat(status).doesNotStartWith("FAILED");
        assertThat(attachments(sent())).isEmpty();
    }
}
