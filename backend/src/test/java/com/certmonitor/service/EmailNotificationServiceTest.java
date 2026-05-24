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
