package com.certmonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DB'ye erişilemeyen bir açılış: mekanizma açılışı ENGELLEMEMELİ — erişilebilen kaynakları basıp DB-bağımlı
 * bölümlere (SMTP/LDAP/katalog) "okunamadı" yazmalı, istisna fırlatmamalı.
 */
class StartupLoggerConfigResilienceTest {

    @Test
    @DisplayName("DB erişilemez: SMTP/LDAP/katalog 'okunamadı'; renderConfig fırlatmaz; erişilebilen bölümler yine basılır")
    void dbUnavailable_stillRendersWithoutThrowing() {
        ConfigurableEnvironment env = mock(ConfigurableEnvironment.class);
        when(env.getPropertySources()).thenReturn(new MutablePropertySources());
        when(env.getActiveProfiles()).thenReturn(new String[]{"prod"});
        when(env.getProperty(anyString())).thenReturn(null);

        AppSettingsService appSettings = mock(AppSettingsService.class);
        when(appSettings.getCatalogForClient()).thenThrow(new RuntimeException("DB down"));
        SmtpSettingsService smtp = mock(SmtpSettingsService.class);
        when(smtp.getOrDefaults()).thenThrow(new RuntimeException("DB down"));
        LdapSettingsService ldap = mock(LdapSettingsService.class);
        when(ldap.getOrDefaults()).thenThrow(new RuntimeException("DB down"));
        SecretCipher cipher = mock(SecretCipher.class);
        when(cipher.isKeyConfigured()).thenReturn(false);

        StartupLogger logger = new StartupLogger(env, appSettings, smtp, ldap, cipher);

        String out = logger.renderConfig();   // istisna fırlatmamalı

        assertThat(out)
                .contains("ETKİN KONFİGÜRASYON")
                .contains("Uygulama").contains("Sunucu")   // erişilebilen bölümler yine basılır
                .contains("okunamadı");                     // DB-bağımlı bölümler
    }
}
