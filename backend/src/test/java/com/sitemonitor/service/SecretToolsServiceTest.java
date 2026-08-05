package com.sitemonitor.service;

import com.sitemonitor.model.LdapSettings;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.LdapSettingsRepository;
import com.sitemonitor.repository.SmtpSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * SecretToolsService — verilen aday anahtarla DB'de şifreli alanların çözümlenmesi.
 * Gerçek SecretCipher (dev default) + mock repolar; enc değerleri cipher.encrypt ile üretilir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecretToolsServiceTest {

    @Mock SmtpSettingsRepository smtpRepo;
    @Mock LdapSettingsRepository ldapRepo;

    SecretCipher cipher;
    SecretToolsService service;

    @BeforeEach
    void setUp() {
        cipher = new SecretCipher();
        cipher.init(); // dev default
        SmtpSettings smtp = new SmtpSettings();
        smtp.setPasswordEnc(cipher.encrypt("smtp-pw"));
        LdapSettings ldap = new LdapSettings();
        ldap.setBindPasswordEnc(cipher.encrypt("ldap-pw"));
        when(smtpRepo.findById(SmtpSettings.SINGLETON_ID)).thenReturn(Optional.of(smtp));
        when(ldapRepo.findById(LdapSettings.SINGLETON_ID)).thenReturn(Optional.of(ldap));
        service = new SecretToolsService(smtpRepo, ldapRepo, cipher);
    }

    @Test
    @DisplayName("doğru anahtar → parolalar çözülür (ok=true + değer)")
    void rightKey_decrypts() {
        List<Map<String, Object>> rows = service.decryptWithKey(cipher.devDefaultKey());
        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> Boolean.TRUE.equals(r.get("ok")));
        assertThat(rows.stream().map(r -> r.get("value")).toList()).contains("smtp-pw", "ldap-pw");
    }

    @Test
    @DisplayName("yanlış anahtar → ok=false ve değer null")
    void wrongKey_fails() {
        List<Map<String, Object>> rows = service.decryptWithKey("yanlis-anahtar");
        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> Boolean.FALSE.equals(r.get("ok")) && r.get("value") == null);
    }

    @Test
    @DisplayName("kayıtlı şifreli satır yoksa boş liste")
    void noRows_empty() {
        when(smtpRepo.findById(SmtpSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(ldapRepo.findById(LdapSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        assertThat(service.decryptWithKey(cipher.devDefaultKey())).isEmpty();
    }

    @Test
    @DisplayName("devDefaultKey + isSecretKeyConfigured passthrough")
    void passthrough() {
        assertThat(service.devDefaultKey()).isNotBlank();
        assertThat(service.isSecretKeyConfigured()).isFalse(); // dev default
    }
}
