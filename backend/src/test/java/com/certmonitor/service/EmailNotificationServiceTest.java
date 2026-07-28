package com.certmonitor.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import com.certmonitor.model.SmtpSettings;
import com.certmonitor.model.NotificationLog;
import com.certmonitor.repository.NotificationLogRepository;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import jakarta.mail.internet.MimeMessage;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailNotificationServiceTest {

    @Mock SmtpSettingsService settingsService;
    @Mock SmtpMailService smtpMailService;
    @Mock JavaMailSenderImpl sender;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock AppSettingsService appSettings;

    private EmailNotificationService service;

    // Logback yakalayıcılar: mail TRACE detayları "com.certmonitor.mail"; operasyonel
    // ERROR/WARN (stack dahil) EmailNotificationService sınıf logger'ında.
    private Logger mailLogger;
    private Logger classLogger;
    private ListAppender<ILoggingEvent> mailAppender;
    private ListAppender<ILoggingEvent> classAppender;
    private Level mailOrig;
    private Level classOrig;

    @BeforeEach
    void setUp() {
        when(appSettings.getString(eq("cert.monitor.app.base-url"), any())).thenAnswer(inv -> inv.getArgument(1));
        EmailTemplateBuilder templateBuilder = new EmailTemplateBuilder(appSettings);
        ReflectionTestUtils.setField(templateBuilder, "appBaseUrl", "http://localhost:5173");
        service = new EmailNotificationService(settingsService, smtpMailService, notificationLogRepo, appSettings, templateBuilder);
        when(settingsService.getOrDefaults()).thenReturn(settings(false));

        mailLogger = (Logger) LoggerFactory.getLogger("com.certmonitor.mail");
        classLogger = (Logger) LoggerFactory.getLogger(EmailNotificationService.class);
        mailOrig = mailLogger.getLevel();
        classOrig = classLogger.getLevel();
        mailAppender = new ListAppender<>();
        classAppender = new ListAppender<>();
        mailAppender.start();
        classAppender.start();
        mailLogger.addAppender(mailAppender);
        classLogger.addAppender(classAppender);
    }

    @AfterEach
    void tearDown() {
        mailLogger.detachAppender(mailAppender);
        classLogger.detachAppender(classAppender);
        mailLogger.setLevel(mailOrig);
        classLogger.setLevel(classOrig);
    }

    /** Tam SMTP ayarlı (host/auth/TLS/timeout) etkin profil — bağlam loglarını anlamlı test eder. */
    private SmtpSettings settingsEnabledFull() {
        SmtpSettings s = settings(true);
        s.setFromAddress("noreply@certmonitor.com");
        s.setHost("smtp.test");
        s.setPort(587);
        s.setAuthEnabled(true);
        s.setUsername("svc@certmonitor.com");
        s.setStartTlsEnable(true);
        s.setStartTlsRequired(true);
        s.setConnectionTimeoutMs(10000);
        s.setReadTimeoutMs(15000);
        s.setWriteTimeoutMs(15000);
        return s;
    }

    /** mailAppender'daki, prefiks ile başlayan son formatlanmış TRACE satırı. */
    private String lastMailLine(String prefix) {
        return mailAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(prefix))
                .reduce((a, b) -> b)
                .orElse("");
    }

    private SmtpSettings settings(boolean enabled) {
        SmtpSettings s = new SmtpSettings();
        s.setEnabled(enabled);
        s.setFromAddress("noreply@certmonitor");
        s.setFromName(null);
        s.setRetryDelayMs(90000);
        return s;
    }

    // ── Email disabled (SKIPPED) ───────────────────────────────────────────────

    @Test
    @DisplayName("sendAlert returns SKIPPED_DISABLED when email is disabled")
    void sendAlert_emailDisabled_returnsSkipped() {
        String result = service.sendAlert("to@test.com", "Test subject", "Test message");

        assertThat(result).isEqualTo("SKIPPED_DISABLED");
        verify(smtpMailService, never()).currentSender();
    }

    @Test
    @DisplayName("sendAlert (rich) returns SKIPPED_DISABLED when email is disabled")
    void sendAlert_rich_emailDisabled_returnsSkipped() {
        String result = service.sendAlert(
                "to@test.com", "[CertMonitor UYARI] test.com — 25 gün kaldı",
                "Test message", "test.com", "WARNING", "EXPIRY", 25, null);

        assertThat(result).isEqualTo("SKIPPED_DISABLED");
        verify(smtpMailService, never()).currentSender();
    }

    @Test
    @DisplayName("sendResolutionAlert returns SKIPPED_DISABLED when email is disabled")
    void sendResolutionAlert_emailDisabled_returnsSkipped() {
        String result = service.sendResolutionAlert(
                "to@test.com",
                "[CertMonitor ✅ ÇÖZÜLDÜ] test.com — Sertifika Süre Bitişi sorunu giderildi",
                "test.com", "EXPIRY", "WARNING",
                25, "john.doe", "2026-05-16T10:00:00", "2026-05-01T08:00:00", null);

        assertThat(result).isEqualTo("SKIPPED_DISABLED");
        verify(smtpMailService, never()).currentSender();
    }

    // ── Başarısız-login anomali admin uyarısı ─────────────────────────────────

    private FailedLoginAnomalyService.AnomalyReport sampleAnomalyReport() {
        var hits = java.util.List.of(
                new FailedLoginAnomalyService.RuleHit("GLOBAL_VOLUME", 47, 20, "47 başarısız login"),
                new FailedLoginAnomalyService.RuleHit("ACCOUNT_TARGETED", 12, 5, "'alice'"));
        var topAccounts = java.util.List.of(
                new FailedLoginAnomalyService.KV("alice", 12), new FailedLoginAnomalyService.KV("bob", 8));
        var topIps = java.util.List.of(new FailedLoginAnomalyService.KV("1.2.3.4", 30));
        var stuffing = java.util.List.of(new FailedLoginAnomalyService.KV("1.2.3.4", 7));
        var reasons = new java.util.LinkedHashMap<String, Long>();
        reasons.put("BAD_PASSWORD", 30L);
        reasons.put("UNKNOWN_USER", 17L);
        return new FailedLoginAnomalyService.AnomalyReport(
                "2026-07-28T10:00:00", "2026-07-28T10:10:00", 10, 47, 3,
                hits, topAccounts, topIps, stuffing, java.util.List.of(), reasons);
    }

    private static String extractText(jakarta.mail.Part part) throws Exception {
        Object c = part.getContent();
        if (c instanceof String s) return s;
        if (c instanceof jakarta.mail.Multipart mp) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) sb.append(extractText(mp.getBodyPart(i)));
            return sb.toString();
        }
        return String.valueOf(c);
    }

    @Test
    @DisplayName("login anomaly alert: email kapalı → SKIPPED_DISABLED")
    void loginAnomalyAlert_disabled_skipped() {
        String r = service.sendSystemAdminLoginAnomalyAlert(new String[]{"ops@x"}, sampleAnomalyReport(), "INITIAL");
        assertThat(r).isEqualTo("SKIPPED_DISABLED");
        verify(smtpMailService, never()).currentSender();
    }

    @Test
    @DisplayName("login anomaly alert: alıcı yok → SKIPPED_NO_RECIPIENT")
    void loginAnomalyAlert_noRecipient_skipped() {
        when(settingsService.getOrDefaults()).thenReturn(settingsEnabledFull());
        String r = service.sendSystemAdminLoginAnomalyAlert(new String[0], sampleAnomalyReport(), "INITIAL");
        assertThat(r).isEqualTo("SKIPPED_NO_RECIPIENT");
    }

    @Test
    @DisplayName("login anomaly alert: etkin → gönderilir; konu+gövde doğru, parola/hassas veri YOK")
    void loginAnomalyAlert_enabled_correctSubjectAndBody_noSensitive() throws Exception {
        when(settingsService.getOrDefaults()).thenReturn(settingsEnabledFull());
        JavaMailSenderImpl spySender = spy(new JavaMailSenderImpl());
        doNothing().when(spySender).send(any(MimeMessage.class));
        when(smtpMailService.currentSender()).thenReturn(spySender);

        String r = service.sendSystemAdminLoginAnomalyAlert(
                new String[]{"ops@x", "sec@x"}, sampleAnomalyReport(), "INITIAL");
        assertThat(r).isEqualTo("SENT");

        ArgumentCaptor<MimeMessage> cap = ArgumentCaptor.forClass(MimeMessage.class);
        verify(spySender).send(cap.capture());
        MimeMessage msg = cap.getValue();
        // Konu: ciddiyet + özet
        assertThat(msg.getSubject()).contains("Anomali").contains("47").contains("2 kural");
        // Gövde: kural etiketleri + top hesap/IP
        String body = extractText(msg);
        assertThat(body).contains("Genel hacim").contains("Hesap odaklı").contains("alice").contains("1.2.3.4");
        // Hassas veri (parola düz metni) ASLA yok
        assertThat(body).doesNotContain("P@ssw0rd").doesNotContainIgnoringCase("parola:");
    }

    // ── Sertifika alarm tipleri (EXPIRY/REVOKED/MISMATCH/CHAIN_BROKEN) ─────────

    private Map<String, Object> certCtx() {
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("issuer", "Test CA");
        c.put("issuer_cn", "Test CA");
        c.put("not_after", "2026-01-01T00:00:00");
        c.put("days_remaining", 25);
        return c;
    }

    @Test
    @DisplayName("EXPIRY alarm HTML (executive): domain + hero gün + sertifika detay satırları + Sertifika İzleme footer")
    void buildAlertEmailHtml_expiry_executive() {
        String html = service.buildAlertEmailHtml(
                "[CertMonitor] ORTA · x.com · Sertifika 25 gün içinde doluyor", "msg",
                "x.com", "WARNING", "EXPIRY", 25, certCtx());
        assertThat(html).contains("x.com");
        assertThat(html).contains(">25<");                       // 56px hero metrik
        assertThat(html).contains("Veren Kurum (CA)").contains("Test CA");
        assertThat(html).contains("Sertifika İzleme");           // footer alt-sistem
        assertThat(html).contains("#0F1B2D");                    // koyu-lacivert üst bant (turuncu banner YOK)
        assertThat(html).doesNotContain("SÜRE BITIŞI TESPİT");   // eski turuncu hero yok
    }

    @Test
    @DisplayName("REVOKED alarm HTML (executive): KRİTİK rozet + domain")
    void buildAlertEmailHtml_revoked_executive() {
        String html = service.buildAlertEmailHtml(
                "[CertMonitor] KRİTİK · x.com · sertifika iptal", "Sertifika iptal edildi",
                "x.com", "CRITICAL", "REVOKED", null, certCtx());
        assertThat(html).contains("x.com").contains("KRİTİK").contains("#C0392B");
    }

    @Test
    @DisplayName("MISMATCH alarm HTML (executive): YÜKSEK rozet")
    void buildAlertEmailHtml_mismatch_executive() {
        String html = service.buildAlertEmailHtml(
                "[CertMonitor] YÜKSEK · x.com · dağıtım", "Dağıtım eksik",
                "x.com", "HIGH", "MISMATCH", null, certCtx());
        assertThat(html).contains("x.com").contains("YÜKSEK").contains("#D68910");
    }

    @Test
    @DisplayName("XSS: domain/registrar user-controlled → HTML escape edilir")
    void buildAlertEmailHtml_escapesUserInput() {
        Map<String, Object> ctx = certCtx();
        ctx.put("registrar", "<script>alert(1)</script>");
        String html = service.buildAlertEmailHtml(
                "s", "m", "evil\"><img src=x>.com", "HIGH", "DOMAINMON_EXPIRY", 10, ctx);
        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).doesNotContain("<img src=x>");
        assertThat(html).contains("&lt;script&gt;");
    }

    // ── Haftalık rapor: onay-bekleyen (CTA) + iade builder'ları ────────────────

    @Test
    @DisplayName("Onay-bekleyen mail: rapor + 'Onayla' CTA + onay linki içerir")
    void buildWeeklyReportSubmittedHtml_containsApproveCta() {
        String url = "https://cm.example.com/api/weekly-reports/approve-link?token=ABC123";
        String html = service.buildWeeklyReportSubmittedHtml("DijitalSY", "2026-W24", "Erdi", url);
        assertThat(html).contains(url);
        assertThat(html).contains("onaylamak için tıklayınız");
    }

    @Test
    @DisplayName("İade maili: düzeltme notu + iade eden içerir")
    void buildWeeklyReportRejectedHtml_containsNoteAndRejecter() {
        String html = service.buildWeeklyReportRejectedHtml(
                "DijitalSY", "2026-W24", "Madde 2 eksik, düzeltiniz", "PO Bey");
        assertThat(html).contains("Madde 2 eksik, düzeltiniz");
        assertThat(html).contains("PO Bey");
    }

    // ── Accessibility (erişim kesintisi) mailleri ──────────────────────────────

    @Test
    @DisplayName("Accessibility alert HTML contains endpoint, outage hero and red accent")
    void buildAlertEmailHtml_accessibility_containsOutageDetails() {
        Map<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("port", 443);
        ctx.put("first_failure_at", "2026-06-11T10:00:00");
        ctx.put("last_error", "Connection timed out");
        ctx.put("confirm_attempt_count", 3);
        ctx.put("confirm_delay_ms", 30000L);
        ctx.put("confirm_attempts", java.util.List.of(
                Map.of("attempt", 1, "checked_at", "2026-06-11T10:00:30", "status", "down", "error", "timeout"),
                Map.of("attempt", 2, "checked_at", "2026-06-11T10:01:00", "status", "down", "error", "timeout"),
                Map.of("attempt", 3, "checked_at", "2026-06-11T10:01:30", "status", "down", "error", "timeout")));

        String html = service.buildAlertEmailHtml(
                "[CertMonitor KRİTİK] down.example.com — Erişim Kesintisi",
                "KRİTİK: down.example.com adresine erişilemiyor.",
                "down.example.com", "CRITICAL", "ACCESSIBILITY", null, ctx);

        assertThat(html).contains("down.example.com:443");
        assertThat(html).contains("SİTE ERİŞİLEMEZ");
        assertThat(html).contains("#dc2626");
        assertThat(html).contains("Erişilebilirlik İzleme");
        assertThat(html).contains("3/3 deneme başarısız");
        assertThat(html).contains("Deneme 1");
    }

    @Test
    @DisplayName("Accessibility alert HTML tolerates null context (Tekrar Bildir path)")
    void buildAlertEmailHtml_accessibility_nullContext_renders() {
        String html = service.buildAlertEmailHtml(
                "subj", "mesaj", "down.example.com", "CRITICAL", "ACCESSIBILITY", null, null);

        assertThat(html).contains("down.example.com");
        assertThat(html).contains("SİTE ERİŞİLEMEZ");
    }

    @Test
    @DisplayName("Accessibility resolved HTML contains outage duration and green accent")
    void buildResolutionEmailHtml_accessibility_containsDuration() {
        String html = service.buildResolutionEmailHtml(
                "down.example.com", "ACCESSIBILITY", "CRITICAL", null,
                "Sistem (otomatik)", "2026-06-11T12:14:00", "2026-06-11T10:00:00", null);

        assertThat(html).contains("#16a34a");
        assertThat(html).contains("Erişim Yeniden Sağlandı");
        assertThat(html).contains("2 saat 14 dakika");
    }

    @Test
    @DisplayName("PORT_DOWN alert HTML contains endpoint, protocol and Port İzleme kicker")
    void buildAlertEmailHtml_portDown_containsPortDetails() {
        Map<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("port", 8443);
        ctx.put("protocol", "TCP");
        ctx.put("first_failure_at", "2026-06-11T10:00:00");
        ctx.put("confirm_attempt_count", 3);
        ctx.put("confirm_delay_ms", 30000L);

        String html = service.buildAlertEmailHtml(
                "[CertMonitor KRİTİK] down.example.com — Port Kesintisi",
                "KRİTİK: port kapalı", "down.example.com", "CRITICAL", "PORT_DOWN", null, ctx);

        assertThat(html).contains("down.example.com:8443");
        assertThat(html).contains("PORT ERİŞİLEMEZ");
        assertThat(html).contains("Port İzleme");
        assertThat(html).contains("TCP");
        assertThat(html).contains("#dc2626");
    }

    @Test
    @DisplayName("DNS_FAILURE alert HTML contains record type and DNS ÇÖZÜLEMİYOR hero")
    void buildAlertEmailHtml_dnsFailure_containsRecordType() {
        Map<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("record_type", "MX");
        ctx.put("first_failure_at", "2026-06-11T10:00:00");

        String html = service.buildAlertEmailHtml(
                "subj", "mesaj", "down.example.com", "CRITICAL", "DNS_FAILURE", null, ctx);

        assertThat(html).contains("MX kaydı");
        assertThat(html).contains("DNS ÇÖZÜLEMİYOR");
        assertThat(html).contains("DNS İzleme");
    }

    @Test
    @DisplayName("DNS_CHANGED HTML: ESKİ/YENİ kolonları, mor aksan, otomatik-kapanış yok, deneme bölümü yok")
    void buildAlertEmailHtml_dnsChanged_oldNewColumns() {
        Map<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("record_type", "A");
        ctx.put("old_values", java.util.List.of("1.2.3.4", "5.6.7.8"));
        ctx.put("new_values", java.util.List.of("9.9.9.9"));
        ctx.put("changed_at", "2026-06-11T10:00:00");

        String html = service.buildAlertEmailHtml(
                "subj", "YÜKSEK: kayıt değişti", "changed.example.com", "HIGH", "DNS_CHANGED", null, ctx);

        assertThat(html).contains("ESKİ DEĞERLER").contains("YENİ DEĞERLER");
        assertThat(html).contains("1.2.3.4").contains("5.6.7.8").contains("9.9.9.9");
        assertThat(html).contains("#9333ea");
        assertThat(html).contains("DNS KAYDI DEĞİŞTİ");
        assertThat(html).doesNotContain("otomatik kapatılır");
        assertThat(html).doesNotContain("Deneme 1");
    }

    @Test
    @DisplayName("DNS_CHANGED HTML tolerates null context (Tekrar Bildir path)")
    void buildAlertEmailHtml_dnsChanged_nullContext_renders() {
        String html = service.buildAlertEmailHtml(
                "subj", "mesaj", "changed.example.com", "HIGH", "DNS_CHANGED", null, null);

        assertThat(html).contains("changed.example.com");
        assertThat(html).contains("DNS KAYDI DEĞİŞTİ");
    }

    @Test
    @DisplayName("Monitoring resolved HTML per type: hero ve süre etiketi tipe göre")
    void buildResolutionEmailHtml_monitoringTypes() {
        String port = service.buildResolutionEmailHtml(
                "d.example.com", "PORT_DOWN", "CRITICAL", null,
                "Sistem (otomatik)", "2026-06-11T12:00:00", "2026-06-11T10:00:00", null);
        assertThat(port).contains("Port Yeniden Açıldı").contains("Toplam Kesinti").contains("#16a34a");

        String dnsChanged = service.buildResolutionEmailHtml(
                "d.example.com", "DNS_CHANGED", "HIGH", null,
                "admin", "2026-06-11T12:00:00", "2026-06-11T10:00:00", null);
        assertThat(dnsChanged).contains("DNS Değişikliği Alarmı Kapatıldı")
                .contains("Alarm Süresi").contains("YÜKSEK");
    }

    // ── Haftalık rapor mailleri ─────────────────────────────────────────────────

    private static final String WR_CONTENT = """
        {"version":1,
         "item1":{"total":12,"urgent":2,"high":3,"medium":4,"low":3,"status_text":"Çalışılıyor","tracking_url":"https://jira/x","notes_md":"| Kayıt | Durum |\\n| --- | --- |\\n| SSL | OK |"},
         "item2":{"open_incidents":1,"problem_records":2,"postmortems":0,"incidents_url":"https://jira/inc","problems_url":"https://jira/prb","postmortems_url":"https://jira/pm","notes_md":""},
         "item3":{"notes_md":"- Çalışma A — tamamlandı\\n- Çalışma B — devam ediyor"},
         "item4":{"channels":[{"id":"c-1","name":"İnternet","notes_md":"![Grafik](/api/weekly-reports/images/5)"}]}}
        """;

    @Test
    @DisplayName("Weekly report HTML: hitap, hafta etiketi, 4 madde, markdown tablo render")
    void buildWeeklyReportHtml_structure() {
        String html = service.buildWeeklyReportHtml("DijitalSY", "2026-W24 (8–12 Haziran 2026)",
                "Ali Müdür", WR_CONTENT, false);

        assertThat(html).contains("Sayın Ali Müdür,");
        assertThat(html).contains("2026-W24 (8–12 Haziran 2026)");
        assertThat(html).contains("1. Proaktif Servis İyileştirme Kayıtları");
        assertThat(html).contains("2. Aşım Yaşanan Olay / Problem ve Açık Postmortem Kayıtları");
        assertThat(html).contains("3. Haftalık Katılım Sağlanan Çalışmalar");
        assertThat(html).contains("4. Domain Bazlı Kritik İşlerin Durumu");
        assertThat(html).contains("Toplam: 12").contains("Acil: 2");
        assertThat(html).contains("<table>");           // markdown tablo render edildi
        assertThat(html).contains("İnternet");

        // Lacivert executive palet + Outlook bgcolor attribute güvencesi; mor kalmadı
        assertThat(html).contains("#1f3864").contains("bgcolor=").doesNotContain("#4f46e5");

        // URL'ler açık yazılmaz — etiket hyperlink'tir (href'te var, görünür metinde yok)
        assertThat(html).contains("href='https://jira/x'").doesNotContain(">https://jira/x<");
        assertThat(html).contains("href='https://jira/inc'").doesNotContain(">https://jira/inc<");
        // Açıklayıcı tıklama metni hyperlink'tir; jenerik "Takip Linki" yazısı kalmadı
        assertThat(html).contains(">Açık olay kayıtları için tıklayınız</a>")
                .contains(">Problem kayıtları için tıklayınız</a>")
                .contains(">Postmortem kayıtları için tıklayınız</a>")
                .contains(">Proaktif İyileştirme kayıtlarına erişmek için tıklayınız</a>")
                .doesNotContain("Takip Linki");

        // Sayı rozeti satırı INLINE width:100% + collapse (Outlook = önizleme) +
        // eşit kolon (Madde 1: 5 kutu → 20%, Madde 2: 3 kutu → 33%); 8-haneli hex ve pill YOK
        assertThat(html).contains("width:100%;border-collapse:collapse;margin:0 0 10px")
                .contains("width='20%'").contains("width='33%'")
                .doesNotContain("#33415514").doesNotContain("#dc262614")
                .doesNotContain("border-radius:999px");
    }

    @Test
    @DisplayName("Weekly report HTML (mail): markdown blokları inline stilli (Outlook=önizleme); footer IST + onay")
    void buildWeeklyReportHtml_emailInlineAndFooter() {
        String html = service.buildWeeklyReportHtml("DijitalSY", "2026-W24 (8–12 Haziran 2026)",
                "Ali Müdür", WR_CONTENT, true, null,
                "Onaylayan Kişi", "2026-06-13T09:00:00", "2026-06-13T09:05:00");

        // Madde 3/4 markdown listeleri Outlook için inline girintili (style bloğuna bağlı değil)
        assertThat(html).contains("<ul style=\"margin:6px 0;padding-left:22px");
        // Madde 1 markdown tablosu inline stilli
        assertThat(html).contains("<table style=\"border-collapse:collapse;width:100%");
        // Footer: onaylayan + UTC→Europe/Istanbul (+3) çevrimi + oluşturma satırı
        assertThat(html).contains("Onaylayan: Onaylayan Kişi");
        assertThat(html).contains("Onay: 13.06.2026 12:00");      // 09:00 UTC → 12:00 IST
        assertThat(html).contains("Gönderim: 13.06.2026 12:05");  // 09:05 UTC → 12:05 IST
        assertThat(html).contains("Oluşturuldu:");
    }

    @Test
    @DisplayName("Weekly report HTML: footer onay bilgisi yoksa (DRAFT önizleme) yalnız Oluşturuldu satırı")
    void buildWeeklyReportHtml_footerNoApprovalInfo() {
        String html = service.buildWeeklyReportHtml("DijitalSY", "2026-W24 (8–12 Haziran 2026)",
                "Ali Müdür", WR_CONTENT, false);
        assertThat(html).contains("Oluşturuldu:")
                .doesNotContain("Onaylayan:").doesNotContain("Gönderim:");
    }

    @Test
    @DisplayName("tint: hex'i beyazla harmanlar (düz açık ton)")
    void tint_blendsTowardWhite() {
        assertThat(EmailNotificationService.tint("#dc2626", 0.12)).isEqualTo("#fbe5e5");
        assertThat(EmailNotificationService.tint("#ffffff", 0.12)).isEqualTo("#ffffff");
        assertThat(EmailNotificationService.tint("#000000", 0.0)).isEqualTo("#ffffff");
    }

    @Test
    @DisplayName("Weekly report HTML: Madde 2 üç sayı da 0 ise otomatik 'kayıt yok' notu; değilse yok")
    void buildWeeklyReportHtml_item2AutoNote() {
        String zero = "{\"version\":1,\"item2\":{\"open_incidents\":0,\"problem_records\":0,\"postmortems\":0}}";
        assertThat(service.buildWeeklyReportHtml("T", "W", "M", zero, false))
                .contains("aşım yaşanan olay, problem veya açık postmortem kaydı bulunmamaktadır");

        String nonZero = "{\"version\":1,\"item2\":{\"open_incidents\":1,\"problem_records\":0,\"postmortems\":0}}";
        assertThat(service.buildWeeklyReportHtml("T", "W", "M", nonZero, false))
                .doesNotContain("kaydı bulunmamaktadır");
    }

    @Test
    @DisplayName("Weekly report HTML: görev listesi checkbox'ları ☑/☐ sembolüne dönüşür (mail uyumu)")
    void buildWeeklyReportHtml_taskListSymbols() {
        String content = "{\"version\":1,\"item3\":{\"notes_md\":\"- [x] yapıldı\\n- [ ] bekleniyor\"}}";
        String html = service.buildWeeklyReportHtml("T", "W", "M", content, false);
        assertThat(html).contains("☑").contains("☐")
                .contains("yapıldı").contains("bekleniyor")
                .doesNotContain("<input");
    }

    @Test
    @DisplayName("Weekly report HTML: eski raporlardaki genel tracking_url hâlâ render edilir")
    void buildWeeklyReportHtml_legacyTrackingUrl() {
        String legacy = "{\"version\":1,\"item2\":{\"open_incidents\":1,\"tracking_url\":\"https://jira/old\"}}";
        String html = service.buildWeeklyReportHtml("T", "W", "M", legacy, false);
        assertThat(html).contains("https://jira/old");
    }

    @Test
    @DisplayName("Weekly report HTML: forEmail=true cid dönüşümü, false /api URL korunur")
    void buildWeeklyReportHtml_cidRewrite() {
        String forMail = service.buildWeeklyReportHtml("T", "W", "M", WR_CONTENT, true);
        assertThat(forMail).contains("cid:img5");
        assertThat(forMail).doesNotContain("/api/weekly-reports/images/5");

        String forUi = service.buildWeeklyReportHtml("T", "W", "M", WR_CONTENT, false);
        assertThat(forUi).contains("/api/weekly-reports/images/5");
        assertThat(forUi).doesNotContain("cid:img5");
    }

    @Test
    @DisplayName("Weekly report HTML: görsel genişliği konuma göre dinamik (madde1→720, kanal→660); Outlook width attr")
    void buildWeeklyReportHtml_imageWidths() {
        // id 7 → madde 1 (tavan 720); id 5 → madde 4 kanal alt-kartı (tavan 660)
        String content = "{\"version\":1,"
                + "\"item1\":{\"notes_md\":\"![A](/api/weekly-reports/images/7)\"},"
                + "\"item4\":{\"channels\":[{\"id\":\"c-1\",\"name\":\"İnternet\","
                + "\"notes_md\":\"![B](/api/weekly-reports/images/5)\"}]}}";

        // Geniş görseller → bulundukları bölümün tavanına kırpılır (dinamik)
        String wide = service.buildWeeklyReportHtml("T", "W", "M", content, true,
                java.util.Map.of(7L, 1600, 5L, 1600));
        assertThat(wide).contains("width=\"720\"").contains("max-width:720px")   // madde 1
                .contains("width=\"660\"").contains("max-width:660px")           // madde 4 kanal
                .contains("display:block").contains("cid:img7").contains("cid:img5");

        // Dar görsel → doğal genişliğinde kalır (upscale yok)
        String narrow = service.buildWeeklyReportHtml("T", "W", "M", content, true,
                java.util.Map.of(7L, 300, 5L, 300));
        assertThat(narrow).contains("width=\"300\"").contains("max-width:300px");

        // Genişlik bilinmiyorsa bölüm tavanı fallback (madde1→720, kanal→660)
        String fallback = service.buildWeeklyReportHtml("T", "W", "M", content, true, null);
        assertThat(fallback).contains("width=\"720\"").contains("width=\"660\"");

        // Önizleme: /api URL korunur + inline max-width:100%, sabit width attr yok
        String preview = service.buildWeeklyReportHtml("T", "W", "M", content, false);
        assertThat(preview).contains("/api/weekly-reports/images/7")
                .contains("max-width:100%").doesNotContain("width=\"720\"");
    }

    @Test
    @DisplayName("Weekly report HTML: markdown içindeki raw HTML escape edilir")
    void buildWeeklyReportHtml_escapesInjectedHtml() {
        String content = "{\"version\":1,\"item3\":{\"notes_md\":\"<script>alert(1)</script>\"}}";
        String html = service.buildWeeklyReportHtml("T", "W", "M", content, false);
        assertThat(html).doesNotContain("<script>alert(1)</script>");
    }

    @Test
    @DisplayName("sendHtml disabled → SKIPPED_DISABLED")
    void sendHtml_disabled_skipped() {
        String result = service.sendHtml(new String[]{"to@test.com"}, new String[]{"cc@test.com"},
                "Konu", "<html/>", null);
        assertThat(result).isEqualTo("SKIPPED_DISABLED");
    }

    @Test
    @DisplayName("sendHtml force=true → mail devre dışıyken bile SKIP etmez, gönderime gider (login-issue mute bypass)")
    void sendHtml_forced_bypassesDisabled() {
        // settingsService varsayılan olarak disabled (setUp'ta settings(false)); force=true isEnabled kısa-devresini atlar.
        JavaMailSenderImpl spySender = spy(new JavaMailSenderImpl());
        doNothing().when(spySender).send(any(MimeMessage.class));
        when(smtpMailService.currentSender()).thenReturn(spySender);

        String result = service.sendHtml(new String[]{"to@test.com"}, null, "Konu", "<html/>", null, true);

        assertThat(result).isEqualTo("SENT");
        verify(spySender).send(any(MimeMessage.class));
    }

    // ── HTML content ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendAlert with enabled flag calls mailSender.send")
    void sendAlert_enabled_callsMailSender() throws Exception {
        when(settingsService.getOrDefaults()).thenReturn(settings(true));
        when(smtpMailService.currentSender()).thenReturn(sender);
        jakarta.mail.internet.MimeMessage mockMsg = mock(jakarta.mail.internet.MimeMessage.class);
        when(sender.createMimeMessage()).thenReturn(mockMsg);
        when(mockMsg.getAllRecipients()).thenReturn(null);
        doNothing().when(sender).send(mockMsg);

        String result = service.sendAlert("to@test.com", "Test subject", "Message body");

        assertThat(result).isEqualTo("SENT");
        verify(sender).send(mockMsg);
    }

    @Test
    @DisplayName("sendResolutionAlert with enabled flag calls mailSender.send")
    void sendResolutionAlert_enabled_callsMailSender() throws Exception {
        when(settingsService.getOrDefaults()).thenReturn(settings(true));
        when(smtpMailService.currentSender()).thenReturn(sender);
        jakarta.mail.internet.MimeMessage mockMsg = mock(jakarta.mail.internet.MimeMessage.class);
        when(sender.createMimeMessage()).thenReturn(mockMsg);
        when(mockMsg.getAllRecipients()).thenReturn(null);
        doNothing().when(sender).send(mockMsg);

        String result = service.sendResolutionAlert(
                "to@test.com", "Subject", "example.com", "EXPIRY", "WARNING",
                25, "john.doe", "2026-05-16T10:00:00", "2026-05-01T08:00:00",
                Map.of("revocation_status", "VALID", "chain_status", "VALID",
                        "deployment_status", "OK", "not_after", "2026-06-01T00:00:00"));

        assertThat(result).isEqualTo("SENT");
        verify(sender).send(mockMsg);
    }

    @Test
    @DisplayName("sendAlert returns FAILED when mailSender throws exception")
    void sendAlert_mailSenderThrows_returnsFailed() {
        when(settingsService.getOrDefaults()).thenReturn(settings(true));
        when(smtpMailService.currentSender()).thenReturn(sender);
        when(sender.createMimeMessage()).thenThrow(new RuntimeException("SMTP error"));

        String result = service.sendAlert("to@test.com", "Subject", "Message");

        assertThat(result).startsWith("FAILED:");
    }

    @Test
    @DisplayName("sendResolutionAlert returns FAILED when mailSender throws exception")
    void sendResolutionAlert_mailSenderThrows_returnsFailed() {
        when(settingsService.getOrDefaults()).thenReturn(settings(true));
        when(smtpMailService.currentSender()).thenReturn(sender);
        when(sender.createMimeMessage()).thenThrow(new RuntimeException("SMTP unavailable"));

        String result = service.sendResolutionAlert(
                "to@test.com", "Subject", "example.com", "EXPIRY", "WARNING",
                null, "admin", null, null, null);

        assertThat(result).startsWith("FAILED:");
    }

    // ── 421 retry sonucunun bildirim loguna geri-yazılması ───────────────────────
    // İlk denemede "QUEUED_RETRY" kaydedilen log, async retry'ların terminal sonucuyla
    // (SENT/FAILED) güncellenir — aksi halde "Alarm gönderilemedi" rozeti yanlış çalışır.

    /** mailRetryExecutor'ı, zamanlanan görevi GECİKMESİZ ve aynı thread'de çalıştıracak
     *  şekilde değiştirir → async retry'lar testte deterministik biter. */
    private void runRetriesInline() {
        ScheduledExecutorService inline = mock(ScheduledExecutorService.class);
        when(inline.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; });
        ReflectionTestUtils.setField(service, "mailRetryExecutor", inline);
    }

    @Test
    @DisplayName("doSend: 421 retry'ları tükenince QUEUED_RETRY logu FAILED'a geri-yazılır")
    void retryExhausted_writesBackFailed() throws Exception {
        String subject = "[CertMonitor YÜKSEK] kartfree.com — DNS";
        when(settingsService.getOrDefaults()).thenReturn(settings(true));
        when(smtpMailService.currentSender()).thenReturn(sender);
        MimeMessage mockMsg = mock(MimeMessage.class);
        when(sender.createMimeMessage()).thenReturn(mockMsg);
        when(mockMsg.getAllRecipients()).thenReturn(null);
        when(mockMsg.getSubject()).thenReturn(subject);
        // her gönderim 421 → tüm denemeler tükenir → terminal FAILED
        doThrow(new MailSendException("421 4.4.2 Try again later")).when(sender).send(mockMsg);
        runRetriesInline();
        NotificationLog stuck = new NotificationLog();
        stuck.setEmailStatus("QUEUED_RETRY: 421 4.4.2 Try again later");
        when(notificationLogRepo.findTopBySubjectAndEmailStatusStartingWithOrderByIdDesc(subject, "QUEUED_RETRY"))
                .thenReturn(Optional.of(stuck));

        String result = service.sendAlert("to@test.com", subject, "msg");

        assertThat(result).startsWith("QUEUED_RETRY");          // caller'a ilk denemenin sonucu döner
        assertThat(stuck.getEmailStatus()).startsWith("FAILED:"); // log terminal duruma geri-yazıldı
        verify(notificationLogRepo).save(stuck);
    }

    @Test
    @DisplayName("doSend: 421 sonrası retry başarılı → QUEUED_RETRY logu SENT'e geri-yazılır")
    void retrySucceeds_writesBackSent() throws Exception {
        String subject = "[CertMonitor YÜKSEK] genesys.akbank.com — DNS";
        when(settingsService.getOrDefaults()).thenReturn(settings(true));
        when(smtpMailService.currentSender()).thenReturn(sender);
        MimeMessage mockMsg = mock(MimeMessage.class);
        when(sender.createMimeMessage()).thenReturn(mockMsg);
        when(mockMsg.getAllRecipients()).thenReturn(null);
        when(mockMsg.getSubject()).thenReturn(subject);
        // ilk gönderim 421, ikinci (retry) başarılı
        doThrow(new MailSendException("421 4.4.2 Try again later")).doNothing().when(sender).send(mockMsg);
        runRetriesInline();
        NotificationLog stuck = new NotificationLog();
        stuck.setEmailStatus("QUEUED_RETRY: 421 4.4.2 Try again later");
        when(notificationLogRepo.findTopBySubjectAndEmailStatusStartingWithOrderByIdDesc(subject, "QUEUED_RETRY"))
                .thenReturn(Optional.of(stuck));

        String result = service.sendAlert("to@test.com", subject, "msg");

        assertThat(result).startsWith("QUEUED_RETRY");
        assertThat(stuck.getEmailStatus()).isEqualTo("SENT");   // retry başarılı → log SENT'e geri-yazıldı
        verify(notificationLogRepo).save(stuck);
    }

    @Test
    @DisplayName("sendAlert (plain) with enabled flag and REVOKED type includes correct HTML")
    void sendAlert_revokedType_emailEnabled_sends() throws Exception {
        when(settingsService.getOrDefaults()).thenReturn(settings(true));
        when(smtpMailService.currentSender()).thenReturn(sender);
        jakarta.mail.internet.MimeMessage mockMsg = mock(jakarta.mail.internet.MimeMessage.class);
        when(sender.createMimeMessage()).thenReturn(mockMsg);
        when(mockMsg.getAllRecipients()).thenReturn(null);
        doNothing().when(sender).send(mockMsg);

        String result = service.sendAlert(
                "to@test.com", "[CertMonitor KRİTİK] revoked.com — İptal Edildi",
                "KRİTİK: sertifika iptal edildi.", "revoked.com", "CRITICAL", "REVOKED", null,
                Map.of("revocation_status", "REVOKED", "chain_status", "VALID",
                        "deployment_status", "OK"));

        assertThat(result).isEqualTo("SENT");
    }

    // ── Olay bildirimi HTML — tarihler her zaman Europe/Istanbul (UTC+3) ──────────

    @Test
    @DisplayName("buildIncidentNotificationHtml: UTC tarihler IST'ye çevrilir (+3) + çözüldü eyebrow")
    void incidentHtml_localTime() {
        Map<String, Object> inc = new HashMap<>();
        inc.put("title", "Ödeme servisi kesintisi");
        inc.put("team_name", "Dijital SY");
        inc.put("severity", "CRITICAL");
        inc.put("status", "RESOLVED");
        inc.put("occurred_at", "2026-06-18T10:00:00");  // 10:00 UTC → 13:00 IST
        inc.put("resolved_at", "2026-06-18T12:30:00");  // 12:30 UTC → 15:30 IST

        String html = service.buildIncidentNotificationHtml(inc, "Müdür Bey", "RESOLVED",
                "https://cm.example.com/?tab=incident-history");

        assertThat(html).contains("18.06.2026 13:00");          // occurred_at IST
        assertThat(html).contains("18.06.2026 15:30");          // resolved_at IST
        assertThat(html).doesNotContain("18.06.2026 10:00");    // ham UTC sızmamalı
        assertThat(html).doesNotContain("18.06.2026 12:30");
        assertThat(html).contains("Olay Çözüldü");              // RESOLVED eyebrow
        assertThat(html).contains("Müdür Bey");
    }

    @Test
    @DisplayName("buildAlertEmailHtml: KEYWORD alarmı kendine özgü şablon — cert alanları YOK")
    void keywordAlert_ownTemplate() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("url", "https://www.akbank.com/");
        ctx.put("keyword", "Melih Ekmekçi");
        ctx.put("operator", "GTE");
        ctx.put("match_count", 3);
        ctx.put("occurrences", 1);
        ctx.put("monitor_id", 42);
        ctx.put("http_status", 200);
        ctx.put("first_failure_at", "2026-06-23T12:00:00");
        String html = service.buildAlertEmailHtml("[CertMonitor KRİTİK] keyword",
                "KRİTİK: kelime bulunamıyor", "https://www.akbank.com/", "CRITICAL", "KEYWORD", null, ctx);
        assertThat(html).contains("İçerik (Keyword) İzleme");
        assertThat(html).contains("Aranan kelime");
        assertThat(html).contains("Melih Ekmekçi");
        assertThat(html).contains("en az 3 kez");                 // opPhrase
        assertThat(html).contains("tab=keyword");                 // CTA deep-link (&amp; ile escape'li)
        assertThat(html).contains("monitor=42");
        // cert/expiry şablonundan hiçbir alan sızmamalı
        assertThat(html).doesNotContain("Son Kullanma");
        assertThat(html).doesNotContain("Veren Kurum");
        assertThat(html).doesNotContain("Geçerlilik Başlangıcı");
    }

    @Test
    @DisplayName("buildAlertEmailHtml: PING_DOWN alarmı kendine özgü şablon — cert alanları YOK")
    void pingAlert_ownTemplate() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("host", "10.0.0.1");
        ctx.put("ip_version", "v4");
        ctx.put("packet_loss", 100);
        ctx.put("first_failure_at", "2026-06-23T12:00:00");
        String html = service.buildAlertEmailHtml("[CertMonitor KRİTİK] ping",
                "KRİTİK: host yanıt vermiyor", "10.0.0.1", "CRITICAL", "PING_DOWN", null, ctx);
        assertThat(html).contains("Ping (ICMP) İzleme");
        assertThat(html).contains("HOST YANIT VERMİYOR");
        assertThat(html).contains("10.0.0.1");
        assertThat(html).doesNotContain("Son Kullanma");
        assertThat(html).doesNotContain("Veren Kurum");
    }

    @Test
    @DisplayName("buildResolutionEmailHtml: KEYWORD/PING çözüldü kendine özgü — cert alanları YOK")
    void keywordPingResolved_ownTemplate() {
        String kw = service.buildResolutionEmailHtml("https://www.akbank.com/", "KEYWORD", "CRITICAL",
                null, "Sistem (otomatik)", "2026-06-23T13:00:00", "2026-06-23T12:00:00", null);
        assertThat(kw).contains("İçerik Doğrulaması Yeniden Başarılı");
        assertThat(kw).doesNotContain("Son Kullanma");
        String pg = service.buildResolutionEmailHtml("10.0.0.1", "PING_DOWN", "CRITICAL",
                null, "Sistem (otomatik)", "2026-06-23T13:00:00", "2026-06-23T12:00:00", null);
        assertThat(pg).contains("Host Yeniden Yanıt Veriyor");
        assertThat(pg).doesNotContain("Son Kullanma");
    }

    @Test
    @DisplayName("buildResolutionEmailHtml (executive): sertifika çözüldü — çözülme tarihi IST insan-okur (+3)")
    void resolutionHtml_localTime() {
        String html = service.buildResolutionEmailHtml(
                "example.com", "EXPIRY", "WARNING", 12,
                "system", "2026-06-18T07:15:00", "2026-06-17T22:00:00", null);

        assertThat(html).contains("example.com").contains("ÇÖZÜLDÜ");
        assertThat(html).contains("18 Haziran 2026 10:15");     // resolvedAt 07:15 UTC → 10:15 IST (+3)
        assertThat(html).doesNotContain("07:15");               // ham UTC sızmamalı
    }

    @Test
    @DisplayName("buildResolutionEmailHtml (domain çözüldü): yenilenen bitiş tarihi + kalan gün + registrar bağlamı gösterilir")
    void resolutionHtml_domainEnriched() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("domain", "kartfree.com");
        ctx.put("expiry_date", "2027-08-06T00:00:00");
        ctx.put("days_remaining", 365);
        ctx.put("registrar", "GoDaddy.com, LLC");
        ctx.put("status_codes", "clientTransferProhibited");
        String html = service.buildResolutionEmailHtml(
                "kartfree.com", "DOMAINMON_EXPIRY", "INFO", 365,
                "Sistem (otomatik)", "2026-07-23T14:58:00", "2026-07-20T09:00:00", ctx);

        assertThat(html).contains("ÇÖZÜLDÜ");
        assertThat(html).contains("Ağustos 2027");         // yenilenen bitiş tarihi (insan-okur)
        assertThat(html).contains("365 gün");              // kalan süre
        assertThat(html).contains("GoDaddy.com, LLC");     // registrar
        assertThat(html).contains("clientTransferProhibited"); // EPP kodu pill

        String text = service.buildResolutionEmailText(
                "kartfree.com", "DOMAINMON_EXPIRY", "Sistem (otomatik)", "2026-07-23T14:58:00",
                "2026-07-20T09:00:00", ctx);
        assertThat(text).contains("Yeni Bitiş Tarihi").contains("Ağustos 2027").contains("365 gün");
    }

    // ── Mail TRACE logging + hata stack izi (com.certmonitor.mail) ────────────────

    @Test
    @DisplayName("Gönderim başarısız: ERROR'da tam stack + TRACE'te SMTP bağlamı/konu — mail GÖVDESİ loglanmaz")
    void doSend_failure_errorHasStack_traceHasContext_noBody() {
        mailLogger.setLevel(Level.TRACE);
        when(settingsService.getOrDefaults()).thenReturn(settingsEnabledFull());
        JavaMailSenderImpl spySender = spy(new JavaMailSenderImpl());
        doThrow(new MailSendException("550 mailbox unavailable")).when(spySender).send(any(MimeMessage.class));
        when(smtpMailService.currentSender()).thenReturn(spySender);

        String result = service.sendAlert("to@test.com", "KONU-X", "GIZLI-GOVDE-12345");

        assertThat(result).startsWith("FAILED:");

        // ERROR satırına tam stack iliştirildi (TRACE açmaya gerek yok)
        ILoggingEvent err = classAppender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("✗ E-posta gönderilemedi"))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(err.getThrowableProxy()).isNotNull();
        assertThat(err.getThrowableProxy().getClassName()).contains("MailSendException");

        // TRACE ayrıntı: SMTP bağlamı + konu var; mail GÖVDESİ asla yok
        String trace = lastMailLine("✗ SMTP hata ayrıntı");
        assertThat(trace).contains("smtp.test:587").contains("konu=KONU-X").contains("timeout(");
        assertThat(trace).doesNotContain("GIZLI-GOVDE-12345");
        // Gönderim-öncesi giriş TRACE'i de basıldı (her gönderimde)
        assertThat(lastMailLine("→ SMTP gönderim")).contains("deneme=1/4").contains("smtp.test:587");
    }

    @Test
    @DisplayName("Gönderim başarılı: TRACE'te '✓ SMTP gönderim OK ... süre=' satırı")
    void doSend_success_tracesOkWithDuration() {
        mailLogger.setLevel(Level.TRACE);
        when(settingsService.getOrDefaults()).thenReturn(settingsEnabledFull());
        JavaMailSenderImpl spySender = spy(new JavaMailSenderImpl());
        doNothing().when(spySender).send(any(MimeMessage.class));
        when(smtpMailService.currentSender()).thenReturn(spySender);

        String result = service.sendAlert("to@test.com", "KONU-X", "mesaj");

        assertThat(result).isEqualTo("SENT");
        assertThat(lastMailLine("✓ SMTP gönderim OK")).contains("süre=").contains("TO=to@test.com");
    }

    @Test
    @DisplayName("TRACE kapalı (INFO): com.certmonitor.mail'e hiç TRACE satırı düşmez")
    void traceOff_noMailTraceLines() {
        mailLogger.setLevel(Level.INFO);
        when(settingsService.getOrDefaults()).thenReturn(settingsEnabledFull());
        JavaMailSenderImpl spySender = spy(new JavaMailSenderImpl());
        doNothing().when(spySender).send(any(MimeMessage.class));
        when(smtpMailService.currentSender()).thenReturn(spySender);

        service.sendAlert("to@test.com", "KONU-X", "mesaj");

        boolean anyTrace = mailAppender.list.stream().anyMatch(e -> e.getLevel() == Level.TRACE);
        assertThat(anyTrace).isFalse();
    }

    @Test
    @DisplayName("421 rate-limit: WARN + async retry kuyruğu; TRACE'te '⏳ SMTP 421 ayrıntı'")
    void error421_warnsAndQueuesRetry_traceDetail() {
        mailLogger.setLevel(Level.TRACE);
        when(settingsService.getOrDefaults()).thenReturn(settingsEnabledFull());
        JavaMailSenderImpl spySender = spy(new JavaMailSenderImpl());
        doThrow(new MailSendException("421 4.7.0 Too many messages")).when(spySender).send(any(MimeMessage.class));
        when(smtpMailService.currentSender()).thenReturn(spySender);

        String result = service.sendAlert("to@test.com", "KONU-X", "mesaj");

        assertThat(result).startsWith("QUEUED_RETRY");
        boolean warned = classAppender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("421 rate limit"));
        assertThat(warned).isTrue();
        assertThat(lastMailLine("⏳ SMTP 421 ayrıntı")).contains("smtp.test:587");
    }

    @Test
    @DisplayName("Hazırlık hatası (createMimeMessage atar): ERROR'da tam stack")
    void prepFailure_errorHasStack() {
        when(settingsService.getOrDefaults()).thenReturn(settingsEnabledFull());
        when(smtpMailService.currentSender()).thenReturn(sender);
        when(sender.createMimeMessage()).thenThrow(new RuntimeException("boom-prep"));

        String result = service.sendAlert("to@test.com", "KONU-X", "mesaj");

        assertThat(result).startsWith("FAILED:");
        ILoggingEvent err = classAppender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("✗ E-posta hazırlanamadı"))
                .reduce((a, b) -> b).orElseThrow();
        assertThat(err.getThrowableProxy()).isNotNull();
        assertThat(err.getThrowableProxy().getMessage()).contains("boom-prep");
    }

    // ── Login sorun bildirimi mailleri (HTML escape + cid + IP/UA görünürlük + alıcı düzeni) ─────────
    // HTML gövde doğrudan buildLoginIssueHtml (private) üzerinden doğrulanır: send() stub'landığında
    // MimeMessage.saveChanges() çalışmaz → getContent() gövdeyi materyalize etmez. Reflection deterministik.

    @Test
    @DisplayName("Sorun bildirimi (admin) HTML'i: referans, user-değeri escape'li, cid görsel, IP/UA görünür")
    void loginIssueReport_htmlEscapesCidAndShowsIp() {
        EmailNotificationService.InlineImage img =
                new EmailNotificationService.InlineImage("shot0", new byte[]{1, 2, 3}, "image/png");
        String html = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "buildLoginIssueHtml",
                "LIR-2026-000042", "N<script>", "reporter@akbank.com", "HTTP 423 <b>x</b>", "mesaj & <tag>",
                java.util.List.of(img), "10.1.2.3", "curl/8", "2026-07-24T09:00:00", false);

        assertThat(html).contains("LIR-2026-000042");
        assertThat(html).contains("N&lt;script&gt;").doesNotContain("N<script>");   // XSS: kullanıcı değeri escape'li
        assertThat(html).contains("cid:shot0");                                      // görsel inline referansı
        assertThat(html).contains("10.1.2.3").contains("curl/8");                    // admin varyantı IP/UA gösterir
        assertThat(html).contains("reporter@akbank.com");                            // admin varyantı bildiren e-postasını gösterir
    }

    @Test
    @DisplayName("Bildiren onay (ACK) HTML'i: referans var; IP/UA satırı GİZLİ (forReporter)")
    void loginIssueAck_htmlHidesIpUa() {
        String html = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "buildLoginIssueHtml",
                "LIR-2026-000042", "N1", "reporter@akbank.com", "HTTP 423", "mesaj",
                java.util.List.of(), null, null, "2026-07-24T09:00:00", true);

        assertThat(html).contains("LIR-2026-000042");
        assertThat(html).doesNotContain("IP Adresi").doesNotContain("Tarayıcı");   // bildirene IP/UA satırı yok
        assertThat(html).doesNotContain("reporter@akbank.com");                    // bildirene kendi e-postası satırı gösterilmez
    }

    @Test
    @DisplayName("Çözüldü maili: bildiren To + admin CC; bildiren yoksa admin To olur")
    void loginIssueResolved_recipientArrangement() throws Exception {
        when(settingsService.getOrDefaults()).thenReturn(settingsEnabledFull());
        JavaMailSenderImpl spySender = spy(new JavaMailSenderImpl());
        doNothing().when(spySender).send(any(MimeMessage.class));
        when(smtpMailService.currentSender()).thenReturn(spySender);

        service.sendLoginIssueResolved("reporter@akbank.com", "admin@akbank.com", "LIR-2026-000009",
                "N9", "HTTP 423", "giriş yok", "2026-07-24T08:00:00",
                "hesap açıldı", "2026-07-24T10:00:00", java.util.List.of(), false);
        org.mockito.ArgumentCaptor<MimeMessage> cap = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(spySender).send(cap.capture());
        MimeMessage msg = cap.getValue();
        assertThat(java.util.Arrays.toString(msg.getRecipients(jakarta.mail.Message.RecipientType.TO)))
                .contains("reporter@akbank.com");
        assertThat(java.util.Arrays.toString(msg.getRecipients(jakarta.mail.Message.RecipientType.CC)))
                .contains("admin@akbank.com");
        assertThat(msg.getSubject()).contains("LIR-2026-000009");

        // Bildiren yok → admin To olur (boş To olmasın).
        reset(spySender);
        doNothing().when(spySender).send(any(MimeMessage.class));
        service.sendLoginIssueResolved(null, "admin@akbank.com", "LIR-2026-000010",
                "N10", null, "msg", "2026-07-24T08:00:00", null, "2026-07-24T10:00:00", java.util.List.of(), false);
        org.mockito.ArgumentCaptor<MimeMessage> cap2 = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(spySender).send(cap2.capture());
        assertThat(java.util.Arrays.toString(cap2.getValue().getRecipients(jakarta.mail.Message.RecipientType.TO)))
                .contains("admin@akbank.com");
    }

    @Test
    @DisplayName("Çözüldü maili ZENGİN: yeşil başlık + bildirim zamanı + hata + açıklama + cid görsel + çözüm notu")
    void loginIssueResolved_enrichedBody() {
        when(settingsService.getOrDefaults()).thenReturn(settingsEnabledFull());
        JavaMailSenderImpl spySender = spy(new JavaMailSenderImpl());
        doNothing().when(spySender).send(any(MimeMessage.class));
        when(smtpMailService.currentSender()).thenReturn(spySender);

        EmailNotificationService.InlineImage img =
                new EmailNotificationService.InlineImage("shot0", new byte[]{1, 2, 3}, "image/png");
        EmailNotificationService.LoginIssueMailResult res = service.sendLoginIssueResolved(
                "reporter@akbank.com", "admin@akbank.com", "LIR-2026-000009",
                "N9", "HTTP 423 <b>x</b>", "giriş yapamıyorum", "2026-07-24T08:00:00",
                "hesap açıldı", "2026-07-24T10:00:00", java.util.List.of(img), false);

        assertThat(res.status()).isEqualTo("SENT");
        String html = res.bodyHtml();
        assertThat(html).contains("✅ Sorun çözümlendi");                  // yeşil başlık korundu
        assertThat(html).contains("Bildirim Zamanı");                      // reportedAt satırı eklendi
        assertThat(html).contains("HTTP 423 &lt;b&gt;x&lt;/b&gt;").doesNotContain("HTTP 423 <b>x</b>");  // hata escape'li
        assertThat(html).contains("giriş yapamıyorum");                    // iletilen açıklama
        assertThat(html).contains("cid:shot0");                            // ekran görüntüsü (CID)
        assertThat(html).contains("hesap açıldı");                         // çözüm notu
    }
}
