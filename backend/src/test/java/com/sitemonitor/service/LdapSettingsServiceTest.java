package com.sitemonitor.service;

import com.sitemonitor.model.LdapSettings;
import com.sitemonitor.repository.LdapSettingsRepository;
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
        // repo.save echoes its argument and findById returns the last saved row — the service now applies each save to
        // the CURRENT database row (2026-10-12), so the fake must behave like a table. lenient: defaults-only test never saves.
        org.mockito.Mockito.lenient()
                .when(repo.save(org.mockito.ArgumentMatchers.any(LdapSettings.class)))
                .thenAnswer(inv -> { stored = inv.getArgument(0); return stored; });
        org.mockito.Mockito.lenient()
                .when(repo.findById(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(stored));
    }

    private LdapSettings stored;

    @Test
    @DisplayName("inceleme 4: tazeleme kayıttan ÖNCE okuduğu eski satırı kayıttan SONRA önbelleğe yazamaz (aynı kilit)")
    void refreshCannotOverwriteConcurrentSave() throws Exception {
        LdapSettings old = new LdapSettings();
        old.setId(LdapSettings.SINGLETON_ID);
        old.setHost("eski.example.com");
        old.setUpdatedAt("2026-10-12T08:00:00");
        stored = old;
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
        when(repo.findById(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            if ("ldap-refresh".equals(Thread.currentThread().getName())) {
                LdapSettings snapshot = new LdapSettings();
                snapshot.setId(LdapSettings.SINGLETON_ID);
                snapshot.setHost(stored.getHost());
                snapshot.setUpdatedAt(stored.getUpdatedAt());
                reading.countDown();
                proceed.await(5, java.util.concurrent.TimeUnit.SECONDS);   // okuma bitti, önbelleğe yazmadan önce bekle
                return java.util.Optional.of(snapshot);
            }
            return java.util.Optional.ofNullable(stored);
        });
        Thread refresh = new Thread(service::refreshFromDb, "ldap-refresh");
        refresh.start();
        assertThat(reading.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread save = new Thread(() -> service.save(Map.of("host", "yeni.example.com"), "admin"), "ldap-save");
        save.start();
        save.join(300);                     // kilit yoksa kayıt burada biter ve tazeleme ardından eskisini yazar
        proceed.countDown();
        refresh.join(5_000);
        save.join(5_000);
        assertThat(refresh.isAlive()).isFalse();
        assertThat(save.isAlive()).isFalse();
        assertThat(service.getOrDefaults().getHost()).isEqualTo("yeni.example.com");
    }

    @Test
    @DisplayName("2026-10-12: kayıt DB'deki güncel satıra uygulanır — başka pod'un kaydettiği rol eşlemesi bayat önbellekle geri alınmaz")
    void saveAppliesToCurrentRowNotStaleCache() {
        service.save(Map.of("host", "h", "role_mappings", List.of()), "admin");
        // Başka pod eşleme ekledi (DB satırı değişti; bu pod'un önbelleği ≤ 10 sn bayat)
        LdapSettings other = new LdapSettings();
        other.setId(LdapSettings.SINGLETON_ID);
        other.setHost("h");
        other.setRoleMappingsJson("[{\"group\":\"CN=Admins\",\"role\":\"admin\"}]");
        other.setUpdatedAt("2099-01-01T00:00:00");
        stored = other;
        // Bu pod yalnız değişen alanı (host) gönderir
        service.save(Map.of("host", "h2"), "admin");
        assertThat(stored.getHost()).isEqualTo("h2");
        assertThat(stored.getRoleMappingsJson()).contains("CN=Admins");
        // İlk kayıtta gövdede olmayan alanlar varsayılandan gelir
        stored = null;
        LdapSettings first = service.save(Map.of("host", "yeni"), "admin");
        assertThat(first.getUserAttribute()).isEqualTo("sAMAccountName");
        assertThat(first.getPort()).isEqualTo(636);
    }

    @Test
    @DisplayName("getOrDefaults: unconfigured returns the requested form defaults")
    void defaultsWhenUnconfigured() {
        LdapSettings d = service.getOrDefaults();
        assertThat(d.getEnabled()).isTrue();
        assertThat(d.getUseLdaps()).isTrue();
        assertThat(d.getStartTls()).isFalse();
        // 2026-08-05: güvenli varsayılan — doğrulama AÇIK (eskiden trust-all ile geliyordu)
        assertThat(d.getSkipCertVerification()).isFalse();
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
                "host", "aknwinldaps.example.com",
                "port", 3269,
                "bind_dn", "CN=ocpbind,OU=ServiceAccounts,DC=example,DC=com",
                "bind_password", "topsecret");

        LdapSettings saved = service.save(body, "admin");
        assertThat(saved.getBindPasswordEnc()).startsWith("enc:v1:");
        assertThat(service.isConfigured()).isTrue();
        assertThat(service.decryptedBindPassword()).isEqualTo("topsecret");

        Map<String, Object> client = service.toClientMap(saved);
        assertThat(client).doesNotContainKey("bind_password");
        assertThat(client).doesNotContainKey("bind_password_enc");
        assertThat(client.get("bind_password_set")).isEqualTo(true);
        assertThat(client.get("host")).isEqualTo("aknwinldaps.example.com");
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

    @Test
    @DisplayName("refreshFromDb: başka instance kaydedince (updated_at) cache DB'den tazelenir (çok-pod)")
    void refreshFromDb_picksUpExternalSave() {
        assertThat(service.isConfigured()).isFalse();               // bu pod'da henüz yok
        LdapSettings dbRow = new LdapSettings();
        dbRow.setId(LdapSettings.SINGLETON_ID);
        dbRow.setEnabled(true);
        dbRow.setHost("ldap.example.com");
        dbRow.setUpdatedAt("2026-07-01T10:00:00");
        when(repo.findById(LdapSettings.SINGLETON_ID)).thenReturn(java.util.Optional.of(dbRow));

        service.refreshFromDb();
        assertThat(service.isConfigured()).isTrue();
        assertThat(service.getOrDefaults().getHost()).isEqualTo("ldap.example.com");

        // Başka pod GÜNCELLEDİ (yeni updated_at) → tazelenir
        LdapSettings updated = new LdapSettings();
        updated.setId(LdapSettings.SINGLETON_ID);
        updated.setEnabled(true);
        updated.setHost("ldap2.example.com");
        updated.setUpdatedAt("2026-07-01T11:00:00");
        when(repo.findById(LdapSettings.SINGLETON_ID)).thenReturn(java.util.Optional.of(updated));

        service.refreshFromDb();
        assertThat(service.getOrDefaults().getHost()).isEqualTo("ldap2.example.com");
    }

    // ── Rol eşlemesi ayarları (2026-10-11) ──

    @Test
    @DisplayName("varsayılan: eşleme KAPALI, varsayılan davranış DERIVED; activeRoleMapping null")
    void roleMapping_defaultOff() {
        Map<String, Object> m = service.toClientMap(service.getOrDefaults());
        assertThat(m.get("role_mapping_enabled")).isEqualTo(false);
        assertThat(m.get("role_mapping_fallback")).isEqualTo("DERIVED");
        assertThat(service.activeRoleMapping()).isNull();
    }

    @Test
    @DisplayName("açılınca activeRoleMapping kuralları sırayla + varsayılanı verir")
    void roleMapping_enabled() {
        service.save(new java.util.HashMap<>(Map.of(
                "role_mapping_enabled", true,
                "role_mapping_fallback", "team_admin",
                "role_mappings", List.of(Map.of("group", "SM-Denetim", "role", "audit"), Map.of("group", "Admins", "role", "ADMIN")))), "admin");
        var cfg = service.activeRoleMapping();
        assertThat(cfg).isNotNull();
        assertThat(cfg.fallback()).isEqualTo("TEAM_ADMIN");
        assertThat(cfg.rules()).extracting(com.sitemonitor.service.ldap.LdapRoleMapping.Rule::role).containsExactly("AUDIT", "ADMIN");
    }

    @Test
    @DisplayName("gövdede role_mappings YOKSA kayıtlı kurallar silinmez (eskiden [] yazılıyordu)")
    void roleMappings_keptWhenAbsent() {
        service.save(new java.util.HashMap<>(Map.of("role_mappings", List.of(Map.of("group", "A", "role", "USER")))), "admin");
        service.save(new java.util.HashMap<>(Map.of("host", "ldap.example.com")), "admin");
        assertThat(service.roleMappings()).hasSize(1);
    }

    @Test
    @DisplayName("geçersiz rol / aşırı uzun grup / bilinmeyen varsayılan → alan hatası (400)")
    void roleMapping_validation() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.save(new java.util.HashMap<>(Map.of(
                "role_mappings", List.of(Map.of("group", "A", "role", "ROOT")))), "admin"))
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.save(new java.util.HashMap<>(Map.of(
                "role_mappings", List.of(Map.of("group", "x".repeat(513), "role", "USER")))), "admin"))
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.save(new java.util.HashMap<>(Map.of(
                "role_mapping_fallback", "SUPERUSER")), "admin"))
                .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException.class);
        assertThat(LdapSettingsService.validateFallback("")).isEqualTo("DERIVED");
        assertThat(LdapSettingsService.validateFallback("audit")).isEqualTo("AUDIT");
    }
}
