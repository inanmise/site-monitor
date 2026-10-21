package com.sitemonitor.service.loginpolicy;

import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.AppSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kullanıcı adı ön eki politikası (2026-10-21): canlı ayardan kural, okuma hatasında fail-open, PUBLIC ipucu yalnız kural
 * uygulanırken (muaf liste ASLA), önizleme tek projeksiyon sorgusuyla aktif kullanıcıları sayar.
 */
class UsernamePrefixPolicyTest {

    AppSettingsService settings;
    AppUserRepository userRepo;
    UsernamePrefixPolicy policy;

    @BeforeEach
    void setUp() {
        settings = mock(AppSettingsService.class);
        userRepo = mock(AppUserRepository.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getString(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        policy = new UsernamePrefixPolicy(settings, userRepo, "admin");
    }

    private void configure(boolean enabled, String allowed, String exempt) {
        when(settings.getBoolean(eq(UsernamePrefixRule.KEY_ENABLED), anyBoolean())).thenReturn(enabled);
        when(settings.getString(eq(UsernamePrefixRule.KEY_ALLOWED), anyString())).thenReturn(allowed);
        when(settings.getString(eq(UsernamePrefixRule.KEY_EXEMPT), anyString())).thenReturn(exempt);
    }

    @Test
    @DisplayName("varsayılan KAPALI: herkes girer, ipucu yok (giriş sayfası yanıtı birebir)")
    void defaultsOff() {
        assertThat(policy.active()).isFalse();
        assertThat(policy.allows("MP1")).isTrue();
        assertThat(policy.publicHint()).isNull();
    }

    @Test
    @DisplayName("açık + N: MP reddedilir; ayar değişince (başka pod'un kaydı) yeni kural hemen uygulanır")
    void liveSettings() {
        configure(true, "N", "");
        assertThat(policy.allows("MP1")).isFalse();
        assertThat(policy.allows("N1")).isTrue();
        configure(true, "N,MP", "");
        assertThat(policy.allows("MP1")).isTrue();
        configure(false, "N", "");
        assertThat(policy.allows("MP1")).isTrue();
    }

    @Test
    @DisplayName("ayar okunamazsa fail-open (herkes girer)")
    void failOpen() {
        when(settings.getBoolean(eq(UsernamePrefixRule.KEY_ENABLED), anyBoolean())).thenThrow(new RuntimeException("db"));
        assertThat(policy.allows("MP1")).isTrue();
        assertThat(policy.active()).isFalse();
    }

    @Test
    @DisplayName("PUBLIC ipucu yalnız izinli ön ekleri taşır — muaf liste ve bootstrap adı ASLA")
    void publicHintHasNoExemptNames() {
        configure(true, "N", "GIZLI.HESAP");
        Map<String, Object> hint = policy.publicHint();
        assertThat(hint).containsOnlyKeys("allowed").containsEntry("allowed", List.of("N"));
        assertThat(hint.toString()).doesNotContain("GIZLI").doesNotContain("admin");
    }

    @Test
    @DisplayName("ret gövdesi kural ön eklerini adlandırır")
    void deniedBody() {
        configure(true, "N,T2", "");
        assertThat(policy.deniedBody()).containsEntry("allowed_prefixes", List.of("N", "T2"));
    }

    @Test
    @DisplayName("ayar görünümü: açık / uygulanıyor / ön ekler / muaflar / bootstrap / sınırlar")
    void settingsView() {
        configure(true, "n", "ops");
        Map<String, Object> v = policy.settingsView();
        assertThat(v).containsEntry("enabled", true).containsEntry("active", true)
                .containsEntry("allowed", List.of("N")).containsEntry("exempt", List.of("OPS"))
                .containsEntry("bootstrap_admin", "admin").containsKey("limits");
    }

    @Test
    @DisplayName("önizleme: yalnız AKTİF kullanıcılar; muaf + bootstrap sayılmaz; örnek ada göre sıralı ve 50 ile sınırlı; kendi hesap bayrağı")
    void preview() {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"N1", "Nur Bir", 1L, "LDAP", true});
        rows.add(new Object[]{"MP2", "Mehmet İki", 1L, "LDAP", true});
        rows.add(new Object[]{"MP1", "Mehmet Bir", 1L, "LDAP", true});
        rows.add(new Object[]{"MP3", "Pasif", 1L, "LDAP", false});
        rows.add(new Object[]{"ADMIN", "Yönetici", null, "LOCAL", true});
        rows.add(new Object[]{"SERVIS", "Servis", null, "LOCAL", true});
        for (int i = 0; i < 60; i++) rows.add(new Object[]{"X" + String.format("%02d", i), null, null, "LDAP", true});
        when(userRepo.findLoginStatDirectory()).thenReturn(rows);

        Map<String, Object> p = policy.preview("N", "SERVIS", "MP1");
        assertThat(p).containsEntry("active_users", 65L).containsEntry("exempt_users", 2L)
                .containsEntry("refused", 62L).containsEntry("allowed_users", 3L)
                .containsEntry("truncated", true).containsEntry("self_refused", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sample = (List<Map<String, Object>>) p.get("sample");
        assertThat(sample).hasSize(50);
        assertThat(sample.get(0)).containsEntry("username", "MP1").containsEntry("display_name", "Mehmet Bir")
                .doesNotContainKey("id");
        assertThat(sample.get(1)).containsEntry("username", "MP2");
        // kaydedilmiş ayar önizlemede KULLANILMAZ (yalnız verilen değerler)
        verify(settings, never()).getString(eq(UsernamePrefixRule.KEY_ALLOWED), anyString());
    }
}
