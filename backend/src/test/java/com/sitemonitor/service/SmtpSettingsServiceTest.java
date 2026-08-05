package com.sitemonitor.service;

import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.SmtpSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SmtpSettingsServiceTest {

    @Mock SmtpSettingsRepository repo;
    private SmtpSettingsService service;

    @BeforeEach
    void setUp() {
        SecretCipher cipher = new SecretCipher();
        cipher.init();
        service = new SmtpSettingsService(repo, cipher);
        // Seed the live-env @Value fields the way Spring would.
        service.envEnabled = true;
        service.envHost = "smtp.gmail.com";
        service.envPort = 587;
        service.envUsername = "certmonitor01@gmail.com";
        service.envPassword = "app-pass";
        service.envFrom = "certmonitor01@gmail.com";
        service.envAuth = true;
        service.envStartTlsEnable = true;
        service.envStartTlsRequired = true;
        service.envSslTrust = "smtp.gmail.com";
        service.envConnTimeout = 10000;
        service.envReadTimeout = 15000;
        service.envWriteTimeout = 15000;
        service.envRetryDelay = 90000;
        service.envInterContact = 5000;
        service.envInterDomain = 3000;
        lenient().when(repo.save(any(SmtpSettings.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("getOrDefaults: unconfigured reflects the LIVE env mail config")
    void seedsFromEnvWhenUnconfigured() {
        SmtpSettings d = service.getOrDefaults();
        assertThat(d.getEnabled()).isTrue();
        assertThat(d.getHost()).isEqualTo("smtp.gmail.com");
        assertThat(d.getPort()).isEqualTo(587);
        assertThat(d.getUsername()).isEqualTo("certmonitor01@gmail.com");
        assertThat(d.getStartTlsEnable()).isTrue();
        assertThat(d.getStartTlsRequired()).isTrue();
        assertThat(d.getSslTrust()).isEqualTo("smtp.gmail.com");
        assertThat(d.getConnectionTimeoutMs()).isEqualTo(10000);
        assertThat(service.isConfigured()).isFalse();
        // Live env password counts as "set" and is the effective password.
        assertThat(service.isPasswordSet()).isTrue();
        assertThat(service.effectivePassword()).isEqualTo("app-pass");
    }

    @Test
    @DisplayName("save: encrypts password, never echoes it, exposes password_set")
    void savePasswordIsWriteOnly() {
        Map<String, Object> body = Map.of(
                "enabled", true, "host", "mail.corp.local", "port", 25,
                "auth_enabled", false, "start_tls_enable", false, "start_tls_required", false,
                "from_address", "alerts@corp.local", "password", "s3cr3t");
        SmtpSettings saved = service.save(body, "admin");
        assertThat(saved.getPasswordEnc()).startsWith("enc:v1:");
        assertThat(service.isConfigured()).isTrue();
        assertThat(service.effectivePassword()).isEqualTo("s3cr3t");

        Map<String, Object> client = service.toClientMap(saved);
        assertThat(client).doesNotContainKey("password");
        assertThat(client).doesNotContainKey("password_enc");
        assertThat(client.get("password_set")).isEqualTo(true);
        assertThat(client.get("host")).isEqualTo("mail.corp.local");
        assertThat(client.get("port")).isEqualTo(25);
        assertThat(client.get("auth_enabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("save: blank/absent password keeps the previously stored one")
    void savePasswordPreservedWhenBlank() {
        service.save(Map.of("host", "h", "password", "first"), "admin");
        service.save(Map.of("host", "h2"), "admin");
        assertThat(service.effectivePassword()).isEqualTo("first");
        assertThat(service.getOrDefaults().getHost()).isEqualTo("h2");
    }
}
