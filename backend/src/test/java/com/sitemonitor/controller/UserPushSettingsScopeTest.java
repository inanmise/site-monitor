package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.UserPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpSession;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * K10 / UIA-C3 (2026-10-22): Webhook Push ayar sayfası kapsamlı müdüre (AD ADMIN, viewTeamIds dolu) sunucunun REDDEDECEĞİ
 * denetimleri açıyordu; sayfa her kayıtta başlık listesini ("[]") ve URL'i de yolladığı için müdürün HER kaydı dokunmadığı
 * GLOBAL_ONLY anahtar yüzünden 403 alıyordu. GET artık {@code read_only_keys} / {@code can_edit_types} /
 * {@code manage_team_ids} taşır; değişmemiş GLOBAL_ONLY değeri kayda girmez (değişeni yine AppSettingsService reddeder).
 */
class UserPushSettingsScopeTest {

    private static final String URL = "site.monitor.userpush.url";
    private static final String HEADERS = "site.monitor.userpush.headers";
    private static final String STORED_HEADERS =
            "[{\"name\":\"Authorization\",\"value\":\"ENC(token)\",\"secret\":true},{\"name\":\"X-Env\",\"value\":\"prod\",\"secret\":false}]";

    AppSettingsService settings;
    UserPushController controller;
    final Map<String, String> stored = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        settings = mock(AppSettingsService.class);
        when(settings.getString(anyString(), any())).thenAnswer(i -> stored.getOrDefault(i.getArgument(0), i.getArgument(1)));
        controller = new UserPushController(settings, mock(UserPushService.class),
                mock(com.sitemonitor.repository.UserPushDeliveryRepository.class),
                mock(com.sitemonitor.repository.UserPushScopeRepository.class), mock(SecretCipher.class), mock(AuditService.class),
                mock(com.sitemonitor.service.UserPushRecipientResolver.class),
                mock(com.sitemonitor.repository.AppUserRepository.class), mock(com.sitemonitor.repository.TeamRepository.class));
    }

    private static MockHttpSession session(boolean scoped) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", scoped ? "mudur" : "admin");
        s.setAttribute("systemRole", "ADMIN");
        if (scoped) {
            s.setAttribute("viewTeamIds", List.of(5L, 6L));
            s.setAttribute("manageTeamIds", List.of(5L));
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(MockHttpSession s) {
        return (Map<String, Object>) controller.getSettings(s).getBody().get("data");
    }

    @Test
    @DisplayName("K10: GET — kapsamlı müdürde URL + başlıklar salt-okunur, tür kapsamı kapalı, yönettiği takımlar; global yöneticide hepsi açık")
    void getCarriesEditFlags() {
        Map<String, Object> scoped = data(session(true));
        assertThat((List<String>) scoped.get("read_only_keys")).contains(URL, HEADERS);
        assertThat(scoped.get("can_edit_types")).isEqualTo(false);
        assertThat(scoped.get("manage_team_ids")).isEqualTo(List.of(5L));

        Map<String, Object> global = data(session(false));
        assertThat((List<String>) global.get("read_only_keys")).isEmpty();
        assertThat(global.get("can_edit_types")).isEqualTo(true);
        assertThat(global).doesNotContainKey("manage_team_ids");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> savedMap() {
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(settings).save(cap.capture(), anyString());
        return cap.getValue();
    }

    @Test
    @DisplayName("UIA-C3: müdür şablon kaydederken sayfanın yolladığı DEĞİŞMEMİŞ URL ve boş başlık listesi kayda girmez (403 yok)")
    void scopedSaveDropsUnchangedGlobalOnlyKeys() {
        stored.put(URL, "https://push.kurum.local/api");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("site.monitor.userpush.template.down", "{seviye} {hedef}");
        body.put(URL, " https://push.kurum.local/api ");
        body.put(HEADERS, List.of());   // saklı başlık yok (env ile yapılandırılmış kurulum)

        controller.saveSettings(body, session(true));

        Map<String, Object> saved = savedMap();
        assertThat(saved).containsKey("site.monitor.userpush.template.down").doesNotContainKeys(URL, HEADERS);
    }

    @Test
    @DisplayName("UIA-C3: maskeli değerleriyle geri gelen saklı başlık listesi değişmemiş sayılır; DEĞİŞEN URL / başlık kayda girer (AppSettingsService 403 verir)")
    void scopedSaveKeepsRealChanges() {
        stored.put(HEADERS, STORED_HEADERS);
        stored.put(URL, "https://push.kurum.local/api");
        Map<String, Object> same = new LinkedHashMap<>();
        same.put(HEADERS, List.of(
                Map.of("name", "Authorization", "value", "*****", "secret", true),
                Map.of("name", "X-Env", "value", "prod", "secret", false)));
        controller.saveSettings(same, session(true));
        assertThat(savedMap()).doesNotContainKey(HEADERS);

        org.mockito.Mockito.clearInvocations(settings);
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put(URL, "https://saldirgan.example.com/hook");
        changed.put(HEADERS, List.of(Map.of("name", "X-Env", "value", "test", "secret", false)));
        controller.saveSettings(changed, session(true));
        assertThat(savedMap()).containsKeys(URL, HEADERS);
    }

    @Test
    @DisplayName("global yönetici: başlık listesi her kayıtta eskisi gibi yazılır (davranış aynı)")
    void globalSaveUnchanged() {
        controller.saveSettings(new LinkedHashMap<>(Map.of(HEADERS, List.of())), session(false));
        assertThat(savedMap()).containsEntry(HEADERS, "[]");
        assertThat(UserPushController.sameSettingText(null, " ")).isTrue();
        assertThat(UserPushController.sameSettingText(true, "true")).isTrue();
        assertThat(UserPushController.sameSettingText("a", "b")).isFalse();
    }
}
