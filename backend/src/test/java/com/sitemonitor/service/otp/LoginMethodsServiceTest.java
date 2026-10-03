package com.sitemonitor.service.otp;

import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.AppSettingsCatalog;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.SmtpSettingsService;
import com.sitemonitor.service.UserPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Giriş yöntemleri ayarları (2026-10-02): varsayılanlar (LDAP açık, kod yöntemleri kapalı, 45 sn, 3 deneme, 30 sn
 * bekleme, 5/20/5 sınırlar, global yönetici kapalı), sunucu kırpması, push'un ağ geçidine bağlılığı, PUBLIC görünümün sabit
 * anahtar kümesi ve anahtarların katalog + GLOBAL_ONLY üyeliği.
 */
class LoginMethodsServiceTest {

    AppSettingsService settings;
    UserPushService push;
    SmtpSettingsService smtp;
    AppUserRepository userRepo;
    LoginMethodsService svc;

    @BeforeEach
    void setUp() {
        settings = mock(AppSettingsService.class);
        push = mock(UserPushService.class);
        smtp = mock(SmtpSettingsService.class);
        userRepo = mock(AppUserRepository.class);
        // Gerçek fallback davranışı: override yoksa varsayılan döner
        when(settings.getBoolean(org.mockito.ArgumentMatchers.anyString(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(settings.getInt(org.mockito.ArgumentMatchers.anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        svc = new LoginMethodsService(settings, push, smtp, userRepo);
    }

    @Test
    @DisplayName("varsayılanlar: LDAP açık, push/e-posta kapalı, 45 sn, 3 deneme, 30 sn bekleme, 5/20/5, global yönetici kapalı")
    void defaults() {
        assertThat(svc.ldapLoginEnabled()).isTrue();
        assertThat(svc.pushEnabled()).isFalse();
        assertThat(svc.emailEnabled()).isFalse();
        assertThat(svc.pushTtlSeconds()).isEqualTo(45);
        assertThat(svc.emailTtlSeconds()).isEqualTo(45);
        assertThat(svc.maxAttempts()).isEqualTo(3);
        assertThat(svc.resendCooldownSeconds()).isEqualTo(30);
        assertThat(svc.maxRequestsPerUser()).isEqualTo(5);
        assertThat(svc.maxRequestsPerIp()).isEqualTo(20);
        assertThat(svc.maxFailedVerifications()).isEqualTo(5);
        assertThat(svc.allowGlobalAdmins()).isFalse();
        // 2026-10-03: kişi bilgisi doğrulaması varsayılan AÇIK, eşleşmeme sınırı 5
        assertThat(svc.pushRequiresPhone()).isTrue();
        assertThat(svc.emailRequiresEmail()).isTrue();
        assertThat(svc.requiresContact(LoginOtpService.Channel.PUSH)).isTrue();
        assertThat(svc.requiresContact(LoginOtpService.Channel.EMAIL)).isTrue();
        assertThat(svc.maxContactMismatches()).isEqualTo(5);
    }

    @Test
    @DisplayName("2026-10-03: kişi bilgisi anahtarları kanal başına; eşleşmeme sınırı 1–20'ye kırpılır")
    void contactSettings() {
        when(settings.getBoolean(eq(LoginMethodsService.KEY_PUSH_REQUIRE_PHONE), anyBoolean())).thenReturn(false);
        assertThat(svc.requiresContact(LoginOtpService.Channel.PUSH)).isFalse();
        assertThat(svc.requiresContact(LoginOtpService.Channel.EMAIL)).isTrue();
        when(settings.getInt(eq(LoginMethodsService.KEY_MAX_CONTACT_MISMATCHES), anyInt())).thenReturn(0);
        assertThat(svc.maxContactMismatches()).isEqualTo(1);
        when(settings.getInt(eq(LoginMethodsService.KEY_MAX_CONTACT_MISMATCHES), anyInt())).thenReturn(500);
        assertThat(svc.maxContactMismatches()).isEqualTo(20);
        assertThat(svc.settingsView()).containsEntry("push_require_phone", false).containsEntry("email_require_email", true)
                .containsEntry("max_contact_mismatches", 20);
        assertThat(LoginMethodsService.limitsView()).containsEntry("max_contact_mismatches", List.of(1, 20));
        assertThat(svc.publicView()).containsEntry("push_requires_phone", false).containsEntry("email_requires_email", true);
    }

    @Test
    @DisplayName("2026-10-03: kapsam TEK toplu sorgudan (aktif / telefonlu / e-postalı); sorgu düşerse sıfırlar, kişi bilgisi yok")
    void contactCoverage() {
        when(userRepo.contactCoverage()).thenReturn(java.util.Collections.singletonList(new Object[] { 120L, 90L, 118L }));
        assertThat(svc.contactCoverage()).containsExactly(
                java.util.Map.entry("active_users", 120L), java.util.Map.entry("with_phone", 90L),
                java.util.Map.entry("with_email", 118L));
        org.mockito.Mockito.verify(userRepo, org.mockito.Mockito.times(1)).contactCoverage();
        when(userRepo.contactCoverage()).thenThrow(new RuntimeException("db down"));
        assertThat(svc.contactCoverage()).containsEntry("active_users", 0L).containsEntry("with_phone", 0L)
                .containsEntry("with_email", 0L);
    }

    @Test
    @DisplayName("sunucu kırpması: API'den gelen aralık dışı değer güvenlik eşiğini gevşetemez (30–300 sn, 1–10 deneme, 10–300 sn)")
    void clamps() {
        when(settings.getInt(eq(LoginMethodsService.KEY_PUSH_TTL), anyInt())).thenReturn(5);
        when(settings.getInt(eq(LoginMethodsService.KEY_EMAIL_TTL), anyInt())).thenReturn(99_999);
        when(settings.getInt(eq(LoginMethodsService.KEY_MAX_ATTEMPTS), anyInt())).thenReturn(1000);
        when(settings.getInt(eq(LoginMethodsService.KEY_COOLDOWN), anyInt())).thenReturn(0);
        assertThat(svc.pushTtlSeconds()).isEqualTo(30);
        assertThat(svc.emailTtlSeconds()).isEqualTo(300);
        assertThat(svc.maxAttempts()).isEqualTo(10);
        assertThat(svc.resendCooldownSeconds()).isEqualTo(10);
    }

    @Test
    @DisplayName("push 'kullanılabilir' YALNIZ ayar açık VE ağ geçidi yapılandırılmışsa; PUBLIC görünüm bunu yansıtır")
    void pushNeedsGateway() {
        when(settings.getBoolean(eq(LoginMethodsService.KEY_PUSH_ENABLED), anyBoolean())).thenReturn(true);
        when(push.gatewayConfigured()).thenReturn(false);
        assertThat(svc.pushAvailable()).isFalse();
        assertThat(svc.available(LoginOtpService.Channel.PUSH)).isFalse();
        assertThat(svc.publicView()).containsEntry("otp_push", false);
        when(push.gatewayConfigured()).thenReturn(true);
        assertThat(svc.pushAvailable()).isTrue();
        assertThat(svc.publicView()).containsEntry("otp_push", true);
    }

    @Test
    @DisplayName("PUBLIC görünüm: sabit anahtar kümesi — yalnız yapılandırma")
    void publicViewKeys() {
        assertThat(svc.publicView().keySet())
                .containsExactly("ldap", "otp_push", "otp_email", "push_ttl", "email_ttl", "resend_cooldown",
                        "push_requires_phone", "email_requires_email");
    }

    @Test
    @DisplayName("SMTP durumu sır taşımaz (yalnız sunucu adı + yapılandırıldı mı + alarm e-postası anahtarı)")
    void smtpStatus_noSecrets() {
        SmtpSettings s = new SmtpSettings();
        s.setHost("smtp.example.com");
        s.setEnabled(false);
        s.setUsername("user");
        when(smtp.getOrDefaults()).thenReturn(s);
        assertThat(svc.smtpStatus()).containsOnlyKeys("host", "configured", "alarm_mail_enabled")
                .containsEntry("host", "smtp.example.com").containsEntry("configured", true)
                .containsEntry("alarm_mail_enabled", false);
    }

    @Test
    @DisplayName("KAPI: sayfanın her anahtarı katalogda ve GLOBAL_ONLY; saklama anahtarı da katalogda")
    void keysAreCataloguedAndGlobalOnly() {
        Set<String> catalog = AppSettingsCatalog.ALL.stream().map(AppSettingsCatalog.Setting::key).collect(Collectors.toSet());
        assertThat(LoginMethodsService.KEYS).hasSize(14);
        for (String k : LoginMethodsService.KEYS) {
            assertThat(catalog).as("katalogda: " + k).contains(k);
            assertThat(AppSettingsCatalog.isGlobalOnly(k)).as("GLOBAL_ONLY: " + k).isTrue();
            assertThat(AppSettingsCatalog.byKey(k).group()).isEqualTo("login-methods");
        }
        assertThat(catalog).contains("site.monitor.login.otp.retention-days");
        assertThat(AppSettingsCatalog.isGlobalOnly("site.monitor.login.otp.retention-days")).isTrue();
    }
}
