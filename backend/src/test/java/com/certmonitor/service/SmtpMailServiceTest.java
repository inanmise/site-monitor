package com.certmonitor.service;

import com.certmonitor.model.SmtpSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * SmtpMailService — guard yolları (host/alıcı eksik) + sender eşlemesi.
 * Gerçek SMTP gönderimi YAPILMAZ; yalnız yapılandırma/guard mantığı doğrulanır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmtpMailServiceTest {

    @Mock SmtpSettingsService settingsService;
    private SmtpMailService service;

    @BeforeEach
    void setUp() {
        service = new SmtpMailService(settingsService);
    }

    private SmtpSettings settings(String host, Integer port) {
        SmtpSettings s = new SmtpSettings();
        s.setHost(host);
        s.setPort(port);
        s.setFromAddress("noreply@certmonitor");
        s.setAuthEnabled(false);
        return s;
    }

    @Test
    @DisplayName("testConnection: host yapılandırılmamışsa success=false (bağlantı denenmez)")
    void testConnection_noHost_fails() {
        when(settingsService.getOrDefaults()).thenReturn(settings(null, null));
        Map<String, Object> r = service.testConnection();
        assertThat(r.get("success")).isEqualTo(false);
        assertThat((String) r.get("error")).contains("host");
    }

    @Test
    @DisplayName("sendTest: boş alıcı reddedilir (ayarlara bakılmaz)")
    void sendTest_blankRecipient_rejected() {
        Map<String, Object> r = service.sendTest("   ");
        assertThat(r.get("success")).isEqualTo(false);
    }

    @Test
    @DisplayName("sendTest: host yoksa success=false")
    void sendTest_noHost_fails() {
        when(settingsService.getOrDefaults()).thenReturn(settings(null, null));
        Map<String, Object> r = service.sendTest("ops@example.com");
        assertThat(r.get("success")).isEqualTo(false);
        assertThat((String) r.get("error")).contains("host");
    }

    @Test
    @DisplayName("currentSender: ayarlardaki host/port JavaMailSender'a eşlenir (gönderim yok)")
    void currentSender_mapsHostPort() {
        when(settingsService.getOrDefaults()).thenReturn(settings("mail.corp.local", 2525));
        JavaMailSenderImpl sender = service.currentSender();
        assertThat(sender.getHost()).isEqualTo("mail.corp.local");
        assertThat(sender.getPort()).isEqualTo(2525);
    }

    @Test
    @DisplayName("currentSender: port boşsa varsayılan 587")
    void currentSender_defaultPort() {
        when(settingsService.getOrDefaults()).thenReturn(settings("mail.corp.local", null));
        assertThat(service.currentSender().getPort()).isEqualTo(587);
    }
}
