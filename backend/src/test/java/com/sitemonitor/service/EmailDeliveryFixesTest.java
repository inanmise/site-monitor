package com.sitemonitor.service;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.NotificationLogRepository;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
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

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Teslim düzeltmeleri (2026-10-22):
 * <ul>
 *   <li>ALERTS-14 — SMTP günlüğünden "Yeniden gönder": günlükteki alıcı metni ("a@x, b@y") tek adres sanılıp
 *       {@code setTo}'ya verildiği için birden çok alıcılı HER satır "Illegal address" ile FAILED oluyordu.</li>
 *   <li>ALERTS-16 — tek posta, N günlük satırı (sessiz saat özeti, fırtına): 421 sonucu yalnız EN YENİ satıra yazılıyordu,
 *       öbürleri yetim süpürmesince FAILED oluyordu (JDBC'siz yol; JDBC yolu {@code MailRetrySameMailWriteBackTest}).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailDeliveryFixesTest {

    @Mock SmtpSettingsService settingsService;
    @Mock SmtpMailService smtpMailService;
    @Mock JavaMailSenderImpl sender;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock AppSettingsService appSettings;

    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        when(appSettings.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(inv -> inv.getArgument(1));
        EmailTemplateBuilder templateBuilder = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(templateBuilder, "appBaseUrl", "http://localhost:5173");
        service = new EmailNotificationService(settingsService, smtpMailService, notificationLogRepo, appSettings, templateBuilder);
        SmtpSettings s = new SmtpSettings();
        s.setEnabled(true);
        s.setFromAddress("noreply@sitemonitor");
        s.setRetryDelayMs(90000);
        when(settingsService.getOrDefaults()).thenReturn(s);
        when(smtpMailService.currentSender()).thenReturn(sender);
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private static List<String> addrs(MimeMessage m, Message.RecipientType type) throws Exception {
        jakarta.mail.Address[] a = m.getRecipients(type);
        return a == null ? List.of() : Arrays.stream(a).map(x -> ((InternetAddress) x).getAddress()).toList();
    }

    @Test
    @DisplayName("ALERTS-14: iki alıcılı satırın yeniden gönderimi SENT — iki adres de Kime'de, CC tekrarsız (Kime'deki adres CC'ye yazılmaz)")
    void resendWithSeveralRecipients() throws Exception {
        String status = service.resendStoredHtml("takim@example.com, mudur@example.com", "noc@example.com; TAKIM@example.com",
                "[Site Monitor] KRİTİK · a.example.com", "<html><body>gövde</body></html>", "critical");

        assertThat(status).isEqualTo("SENT");
        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(cap.capture());
        assertThat(addrs(cap.getValue(), Message.RecipientType.TO)).containsExactly("takim@example.com", "mudur@example.com");
        assertThat(addrs(cap.getValue(), Message.RecipientType.CC)).containsExactly("noc@example.com");
        // eski 4 bağımsız değişkenli çağrı da bölünür
        assertThat(service.resendStoredHtml("a@example.com;b@example.com", "s", "<p>x</p>", "ok")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("ALERTS-14: alıcı listesi bölünür (virgül / noktalı virgül / boşluk), boşlar ve tekrarlar atılır; boş liste → FAILED, gönderim yok")
    void splitAddressList() {
        assertThat(EmailNotificationService.splitAddressList(" a@x.io, b@y.io;; A@X.IO  c@z.io ", Set.of()))
                .containsExactly("a@x.io", "b@y.io", "c@z.io");
        assertThat(EmailNotificationService.splitAddressList("a@x.io, b@y.io", Set.of("a@x.io"))).containsExactly("b@y.io");
        assertThat(EmailNotificationService.splitAddressList(null, Set.of())).isEmpty();
        assertThat(service.resendStoredHtml(" , ;", "s", "<p>x</p>", "ok")).startsWith("FAILED");
        verify(sender, never()).send(any(MimeMessage.class));
    }

    private static NotificationLog row(long id, String recipients, String trigger, String sentAt) {
        NotificationLog n = new NotificationLog();
        n.setId(id);
        n.setRecipientEmail(recipients);
        n.setRecipientRole("COMBINED");
        n.setTrigger(trigger);
        n.setSentAt(sentAt);
        n.setEmailStatus("QUEUED_RETRY: 421 4.4.2 Try again later");
        return n;
    }

    private MimeMessage message(String subject, String to) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setSubject(subject, "UTF-8");
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        return m;
    }

    @Test
    @DisplayName("ALERTS-16: özet postasının 3 kuyruk satırı (aynı konu + alıcı + tetik, aynı an) — 421 sonucu ÜÇÜNE de yazılır; başka alıcı, başka tetik ve pencere dışı satıra dokunulmaz")
    void writeBackReachesEveryRowOfOneMail() throws Exception {
        String subject = "[Site Monitor] Sessiz saat özeti — Takım A";
        String to = "takim@example.com, mudur@example.com";
        NotificationLog r1 = row(1L, to, "QUIET_DIGEST", "2026-10-22T06:00:01");
        NotificationLog r2 = row(2L, to, "QUIET_DIGEST", "2026-10-22T06:00:01");
        NotificationLog r3 = row(3L, to, "QUIET_DIGEST", "2026-10-22T06:00:02");
        NotificationLog other = row(4L, "baska@example.com", "QUIET_DIGEST", "2026-10-22T06:00:02");
        NotificationLog otherTrigger = row(5L, to, "INITIAL", "2026-10-22T06:00:02");
        NotificationLog old = row(6L, to, "QUIET_DIGEST", "2026-10-21T06:00:00");
        when(notificationLogRepo.findTop20BySubjectAndEmailStatusStartingWithOrderByIdDesc(subject, "QUEUED_RETRY"))
                .thenReturn(List.of(r3, r2, r1, other, otherTrigger, old));

        service.writeBackRetryStatus(message(subject, "takim@example.com, mudur@example.com"), "SENT");

        assertThat(List.of(r1, r2, r3)).extracting(NotificationLog::getEmailStatus).containsOnly("SENT");
        assertThat(other.getEmailStatus()).startsWith("QUEUED_RETRY");
        assertThat(otherTrigger.getEmailStatus()).startsWith("QUEUED_RETRY");
        assertThat(old.getEmailStatus()).as("dünkü aynı konulu posta").startsWith("QUEUED_RETRY");
        verify(notificationLogRepo).save(r1);
        verify(notificationLogRepo).save(r2);
        verify(notificationLogRepo).save(r3);
        verify(notificationLogRepo, never()).save(other);
    }

    @Test
    @DisplayName("ALERTS-16: 7/24 / teknik hata raporu satırı (başka yerde uzlaştırılır) tek satırdır — kardeş aranmaz")
    void resentElsewhereRowsHaveNoSiblings() throws Exception {
        String subject = "[7/24] KRİTİK · a.example.com";
        NotificationLog noc = row(10L, "7/24 ekibi", "INITIAL", "2026-10-22T06:00:01");
        noc.setRecipientRole(com.sitemonitor.service.noc.NocNotificationService.LOG_ROLE);
        NotificationLog noc2 = row(11L, "7/24 ekibi", "INITIAL", "2026-10-22T06:00:01");
        noc2.setRecipientRole(com.sitemonitor.service.noc.NocNotificationService.LOG_ROLE);
        when(notificationLogRepo.findTop20BySubjectAndEmailStatusStartingWithOrderByIdDesc(subject, "QUEUED_RETRY"))
                .thenReturn(List.of(noc2, noc));
        service.writeBackRetryStatus(message(subject, "noc@example.com"), "SENT");
        assertThat(noc2.getEmailStatus()).isEqualTo("SENT");
        assertThat(noc.getEmailStatus()).startsWith("QUEUED_RETRY");
    }

    @Test
    @DisplayName("ALERTS-16: kardeş kuralı — aynı alıcı / rol / tetik ve ±2 dk; zaman okunamazsa yalnız aynı damga")
    void sameMailRule() {
        NotificationLog a = row(1L, "x@example.com", "STORM_INITIAL", "2026-10-22T06:00:00");
        assertThat(EmailNotificationService.sameMail(a, row(2L, "x@example.com", "STORM_INITIAL", "2026-10-22T06:01:59"))).isTrue();
        assertThat(EmailNotificationService.sameMail(a, row(2L, "x@example.com", "STORM_INITIAL", "2026-10-22T06:02:01"))).isFalse();
        assertThat(EmailNotificationService.sameMail(a, row(2L, "y@example.com", "STORM_INITIAL", "2026-10-22T06:00:00"))).isFalse();
        NotificationLog bad = row(3L, "x@example.com", "STORM_INITIAL", "bozuk");
        assertThat(EmailNotificationService.sameMail(bad, row(4L, "x@example.com", "STORM_INITIAL", "bozuk"))).isTrue();
        assertThat(EmailNotificationService.sameMail(bad, a)).isFalse();
        assertThat(EmailNotificationService.likePrefix("QUEUED_RETRY")).isEqualTo("QUEUED\\_RETRY%");
        assertThat(EmailNotificationService.likePrefix("a%b\\c")).isEqualTo("a\\%b\\\\c%");
    }
}
