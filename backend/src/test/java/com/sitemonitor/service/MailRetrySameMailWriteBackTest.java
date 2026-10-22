package com.sitemonitor.service;

import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.NotificationLogRepository;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ALERTS-16 (2026-10-22), GERÇEK SQL (H2): fırtına postası 25 üyeli bir takım için 25 günlük satırı yazar (aynı konu, alıcı,
 * rol, tetik, an). 421 yeniden deneme sonucu eskiden yalnız EN YENİ satıra yazılıyordu; 24 satır yetim süpürmesince
 * "FAILED: … gönderen pod kapandı" oluyordu. Tek hedefli UPDATE hepsini yazar (son-20 tavanı yok); başka alıcının, başka
 * tetiğin ve pencere dışındaki aynı konulu postanın satırına dokunmaz. Canlılık damgası da kardeşlere gider.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MailRetrySameMailWriteBackTest {

    @Autowired NotificationLogRepository repo;
    @Autowired JdbcTemplate jdbc;

    private EmailNotificationService service;
    private JavaMailSenderImpl sender;

    private static final String SUBJECT = "[Site Monitor] Alarm fırtınası — Takım A (25 izleme)";
    private static final String TO = "takim-a@example.com, mudur@example.com";

    /** NOT_SUPPORTED commits the rows; remove them so later tests of the cached context start clean. */
    @AfterEach
    void tearDown() {
        repo.deleteAll();
    }

    @BeforeEach
    void setUp() {
        repo.deleteAll();
        SmtpSettingsService settings = mock(SmtpSettingsService.class);
        SmtpSettings s = new SmtpSettings();
        s.setEnabled(true);
        s.setFromAddress("noreply@sitemonitor");
        s.setRetryDelayMs(90000);
        when(settings.getOrDefaults()).thenReturn(s);
        SmtpMailService smtp = mock(SmtpMailService.class);
        sender = mock(JavaMailSenderImpl.class);
        when(smtp.currentSender()).thenReturn(sender);
        AppSettingsService app = mock(AppSettingsService.class);
        when(app.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(inv -> inv.getArgument(1));
        service = new EmailNotificationService(settings, smtp, repo, app, new EmailTemplateBuilder(app));
        service.setJdbc(jdbc);
    }

    private NotificationLog log(long eventId, String to, String trigger, String sentAt, String status) {
        NotificationLog n = new NotificationLog();
        n.setAlertEventId(eventId);
        n.setSentAt(sentAt);
        n.setRecipientName("Takım A");
        n.setRecipientEmail(to);
        n.setRecipientRole("COMBINED");
        n.setSubject(SUBJECT);
        n.setMessage("<html/>");
        n.setEmailStatus(status);
        n.setWebhookStatus("SKIPPED");
        n.setTrigger(trigger);
        return repo.save(n);
    }

    private static MimeMessage message() throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setSubject(SUBJECT, "UTF-8");
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(TO));
        return m;
    }

    @Test
    @DisplayName("25 üyeli fırtına postası: 421 sonucu (SENT) 25 satırın HEPSİNE yazılır; başka alıcı / tetik / gün dokunulmaz")
    void writeBackReachesAllRows() throws Exception {
        // Eski satırlar ÖNCE yazıldı (kimlik sırası = yazım sırası); geri yazım en yeni eşleşen satırı (bu postanın) bulur.
        NotificationLog yesterday = log(102, TO, "STORM_INITIAL", "2026-10-21T06:00:01", "QUEUED_RETRY: 421");
        NotificationLog otherTrigger = log(101, TO, "STORM_REALERT", "2026-10-22T05:59:30", "QUEUED_RETRY: 421");
        for (long i = 1; i <= 25; i++) log(i, TO, "STORM_INITIAL", "2026-10-22T06:00:0" + (i % 3), "QUEUED_RETRY: 421 4.4.2");
        NotificationLog otherTo = log(100, "takim-b@example.com", "STORM_INITIAL", "2026-10-22T06:00:01", "QUEUED_RETRY: 421");

        service.writeBackRetryStatus(message(), "SENT");

        List<NotificationLog> rows = repo.findAll();
        assertThat(rows.stream().filter(n -> n.getAlertEventId() <= 25).map(NotificationLog::getEmailStatus))
                .hasSize(25).containsOnly("SENT");
        assertThat(repo.findById(otherTo.getId()).orElseThrow().getEmailStatus()).startsWith("QUEUED_RETRY");
        assertThat(repo.findById(otherTrigger.getId()).orElseThrow().getEmailStatus()).startsWith("QUEUED_RETRY");
        assertThat(repo.findById(yesterday.getId()).orElseThrow().getEmailStatus()).startsWith("QUEUED_RETRY");
    }

    @Test
    @DisplayName("C1 (son inceleme 2026-10-22): 255 karakteri aşan 'FAILED: <SMTP hatası>' kardeşlere de KIRPILMIŞ yazılır — UPDATE düşmez")
    void longFailedStatusReachesSiblingsFitted() throws Exception {
        for (long i = 1; i <= 3; i++) log(i, TO, "STORM_INITIAL", "2026-10-22T06:00:00", "QUEUED_RETRY: 421");
        String failed = "FAILED: Failed messages: org.eclipse.angus.mail.smtp.SMTPSendFailedException: 421 4.7.0 "
                + "Try again later; message exception details (1) are: " + "x".repeat(400);

        service.writeBackRetryStatus(message(), failed);

        List<String> statuses = repo.findAll().stream().map(NotificationLog::getEmailStatus).toList();
        String fitted = NotificationLog.fit(failed, NotificationLog.VARCHAR_MAX);
        assertThat(fitted.length()).isLessThanOrEqualTo(NotificationLog.VARCHAR_MAX);
        assertThat(statuses).hasSize(3).containsOnly(fitted);
    }

    @Test
    @DisplayName("yetim sayılmış satırların geç gelen gerçek sonucu da hepsine yazılır (önek 'FAILED: sonuç yok' — LIKE kaçışlı)")
    void lateResultCorrectsAllOrphanedRows() throws Exception {
        for (long i = 1; i <= 3; i++) log(i, TO, "QUIET_DIGEST", "2026-10-22T06:00:00", EmailNotificationService.STATUS_RETRY_ORPHANED);
        service.writeBackRetryStatus(message(), "SENT");
        assertThat(repo.findAll()).extracting(NotificationLog::getEmailStatus).containsOnly("SENT");
    }

    @Test
    @DisplayName("canlılık damgası: bekleyen 421 yeniden denemesi aynı postanın TÜM kuyruk satırlarını tazeler (yetim sayılmasınlar)")
    void heartbeatTouchesSiblings() throws Exception {
        for (long i = 1; i <= 4; i++) log(i, TO, "QUIET_DIGEST", "2026-10-22T06:00:00", "QUEUED_RETRY: 421");
        log(50, "takim-b@example.com", "QUIET_DIGEST", "2026-10-22T06:00:00", "QUEUED_RETRY: 421");
        ReflectionTestUtils.setField(service, "mailRetryExecutor", mock(ScheduledExecutorService.class));   // görev bekler, koşmaz
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("421 4.4.2 Try again later")).when(sender).send(any(MimeMessage.class));
        assertThat(service.sendAlert(new String[]{"takim-a@example.com", "mudur@example.com"}, SUBJECT, "msg",
                null, null, null, null, null)).startsWith("QUEUED_RETRY");

        assertThat(service.heartbeatPendingRetries("2026-10-22T06:05:00")).isEqualTo(4);
        List<String> alive = jdbc.queryForList(
                "SELECT retry_alive_at FROM notification_logs WHERE recipient_email = ? ORDER BY id", String.class, TO);
        assertThat(alive).hasSize(4).containsOnly("2026-10-22T06:05:00");
        assertThat(jdbc.queryForObject("SELECT retry_alive_at FROM notification_logs WHERE alert_event_id = 50", String.class)).isNull();
    }
}
