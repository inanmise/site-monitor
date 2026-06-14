package com.certmonitor.service;

import com.certmonitor.model.LdapSettings;
import com.certmonitor.repository.LdapSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LdapSettingsServiceTest {

    @Mock LdapSettingsRepository repo;
    private LdapSettingsService service;

    @BeforeEach
    void setUp() {
        SecretCipher cipher = new SecretCipher();
        cipher.init();
        service = new LdapSettingsService(repo, cipher);
        // repo.save echoes its argument so the in-memory cache mirrors the persisted row.
        // lenient: the defaults-only test never saves.
        org.mockito.Mockito.lenient()
                .when(repo.save(org.mockito.ArgumentMatchers.any(LdapSettings.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("getOrDefaults: unconfigured returns the requested form defaults")
    void defaultsWhenUnconfigured() {
        LdapSettings d = service.getOrDefaults();
        assertThat(d.getEnabled()).isTrue();
        assertThat(d.getUseLdaps()).isTrue();
        assertThat(d.getStartTls()).isFalse();
        assertThat(d.getSkipCertVerification()).isTrue();
        assertThat(d.getPort()).isEqualTo(636);
        assertThat(d.getUserAttribute()).isEqualTo("sAMAccountName");
        assertThat(d.getEmailAttribute()).isEqualTo("mail");
        assertThat(d.getDisplayAttribute()).isEqualTo("displayName");
        assertThat(service.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("save: encrypts bind password, never echoes it, sets bind_password_set")
    void savePasswordIsWriteOnly() {
        Map<String, Object> body = Map.of(
                "enabled", true,
                "host", "aknwinldaps.akbank.com",
                "port", 3269,
                "bind_dn", "CN=ocpbind,OU=ServiceAccounts,DC=aknet,DC=akb",
                "bind_password", "topsecret");

        LdapSettings saved = service.save(body, "admin");
        assertThat(saved.getBindPasswordEnc()).startsWith("enc:v1:");
        assertThat(service.isConfigured()).isTrue();
        assertThat(service.decryptedBindPassword()).isEqualTo("topsecret");

        Map<String, Object> client = service.toClientMap(saved);
        assertThat(client).doesNotContainKey("bind_password");
        assertThat(client).doesNotContainKey("bind_password_enc");
        assertThat(client.get("bind_password_set")).isEqualTo(true);
        assertThat(client.get("host")).isEqualTo("aknwinldaps.akbank.com");
        assertThat(client.get("port")).isEqualTo(3269);
    }

    @Test
    @DisplayName("save: blank/absent bind password keeps the previously stored one")
    void savePasswordPreservedWhenBlank() {
        service.save(Map.of("host", "h", "bind_password", "first"), "admin");
        // Second save without a password — must not wipe the existing one.
        service.save(Map.of("host", "h2"), "admin");
        assertThat(service.decryptedBindPassword()).isEqualTo("first");
        assertThat(service.getOrDefaults().getHost()).isEqualTo("h2");
    }

    @Test
    @DisplayName("role mappings: serialized and parsed back as group/role pairs")
    void roleMappingsRoundTrip() {
        Map<String, Object> body = Map.of(
                "host", "h",
                "role_mappings", List.of(
                        Map.of("group", "CN=CertAdmins", "role", "admin"),
                        Map.of("group", "CN=Auditors", "role", "audit"),
                        Map.of("group", "", "role", "ignored"))); // blank dropped

        service.save(body, "admin");
        List<Map<String, String>> parsed = service.roleMappings();
        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0)).containsEntry("group", "CN=CertAdmins").containsEntry("role", "admin");
        assertThat(parsed.get(1)).containsEntry("group", "CN=Auditors").containsEntry("role", "audit");
    }
}
