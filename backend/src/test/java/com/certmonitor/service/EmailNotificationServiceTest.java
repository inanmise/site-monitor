package com.certmonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailNotificationServiceTest {

    @Mock
    JavaMailSender mailSender;

    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailNotificationService(mailSender);
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "emailFrom", "noreply@certmonitor");
    }

    // ── Email disabled (SKIPPED) ───────────────────────────────────────────────

    @Test
    @DisplayName("sendAlert returns SKIPPED_DISABLED when email is disabled")
    void sendAlert_emailDisabled_returnsSkipped() {
        String result = service.sendAlert("to@test.com", "Test subject", "Test message");

        assertThat(result).isEqualTo("SKIPPED_DISABLED");
        verify(mailSender, never()).send(any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    @DisplayName("sendAlert (rich) returns SKIPPED_DISABLED when email is disabled")
    void sendAlert_rich_emailDisabled_returnsSkipped() {
        String result = service.sendAlert(
                "to@test.com", "[CertMonitor UYARI] test.com — 25 gün kaldı",
                "Test message", "test.com", "WARNING", "EXPIRY", 25, null);

        assertThat(result).isEqualTo("SKIPPED_DISABLED");
        verify(mailSender, never()).send(any(jakarta.mail.internet.MimeMessage.class));
    }

    @Test
    @DisplayName("sendResolutionAlert returns SKIPPED_DISABLED when email is disabled")
    void sendResolutionAlert_emailDisabled_returnsSkipped() {
        String result = service.sendResolutionAlert(
                "to@test.com",
                "[CertMonitor ✅ ÇÖZÜLDÜ] test.com — Son Kullanma sorunu giderildi",
                "test.com", "EXPIRY", "WARNING",
                25, "john.doe", "2026-05-16T10:00:00", "2026-05-01T08:00:00", null);

        assertThat(result).isEqualTo("SKIPPED_DISABLED");
        verify(mailSender, never()).send(any(jakarta.mail.internet.MimeMessage.class));
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

    // ── HTML content ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sendAlert with enabled flag calls mailSender.send")
    void sendAlert_enabled_callsMailSender() throws Exception {
        ReflectionTestUtils.setField(service, "enabled", true);
        jakarta.mail.internet.MimeMessage mockMsg = mock(jakarta.mail.internet.MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mockMsg);
        when(mockMsg.getAllRecipients()).thenReturn(null);
        doNothing().when(mailSender).send(mockMsg);

        String result = service.sendAlert("to@test.com", "Test subject", "Message body");

        assertThat(result).isEqualTo("SENT");
        verify(mailSender).send(mockMsg);
    }

    @Test
    @DisplayName("sendResolutionAlert with enabled flag calls mailSender.send")
    void sendResolutionAlert_enabled_callsMailSender() throws Exception {
        ReflectionTestUtils.setField(service, "enabled", true);
        jakarta.mail.internet.MimeMessage mockMsg = mock(jakarta.mail.internet.MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mockMsg);
        when(mockMsg.getAllRecipients()).thenReturn(null);
        doNothing().when(mailSender).send(mockMsg);

        String result = service.sendResolutionAlert(
                "to@test.com", "Subject", "example.com", "EXPIRY", "WARNING",
                25, "john.doe", "2026-05-16T10:00:00", "2026-05-01T08:00:00",
                Map.of("revocation_status", "VALID", "chain_status", "VALID",
                        "deployment_status", "OK", "not_after", "2026-06-01T00:00:00"));

        assertThat(result).isEqualTo("SENT");
        verify(mailSender).send(mockMsg);
    }

    @Test
    @DisplayName("sendAlert returns FAILED when mailSender throws exception")
    void sendAlert_mailSenderThrows_returnsFailed() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("SMTP error"));

        String result = service.sendAlert("to@test.com", "Subject", "Message");

        assertThat(result).startsWith("FAILED:");
    }

    @Test
    @DisplayName("sendResolutionAlert returns FAILED when mailSender throws exception")
    void sendResolutionAlert_mailSenderThrows_returnsFailed() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("SMTP unavailable"));

        String result = service.sendResolutionAlert(
                "to@test.com", "Subject", "example.com", "EXPIRY", "WARNING",
                null, "admin", null, null, null);

        assertThat(result).startsWith("FAILED:");
    }

    @Test
    @DisplayName("sendAlert (plain) with enabled flag and REVOKED type includes correct HTML")
    void sendAlert_revokedType_emailEnabled_sends() throws Exception {
        ReflectionTestUtils.setField(service, "enabled", true);
        jakarta.mail.internet.MimeMessage mockMsg = mock(jakarta.mail.internet.MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mockMsg);
        when(mockMsg.getAllRecipients()).thenReturn(null);
        doNothing().when(mailSender).send(mockMsg);

        String result = service.sendAlert(
                "to@test.com", "[CertMonitor KRİTİK] revoked.com — İptal Edildi",
                "KRİTİK: sertifika iptal edildi.", "revoked.com", "CRITICAL", "REVOKED", null,
                Map.of("revocation_status", "REVOKED", "chain_status", "VALID",
                        "deployment_status", "OK"));

        assertThat(result).isEqualTo("SENT");
    }
}
