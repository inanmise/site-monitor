package com.sitemonitor.controller;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.SecretCipher;
import com.sitemonitor.service.UserPushService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Şablon yer-tutucu doğrulaması — bilinmeyen yer tutucu kaydetmede REDDEDİLİR.
 *
 * <p>Sessiz bozulmanın kendisi: {@code {sevye}} gibi bir yazım hatası kaydedilseydi çalışma
 * anında mesajda aynen "{sevye}" basılır, kimse fark etmezdi. Doğrulama kaydetme anında.
 */
class UserPushControllerUnitTest {

    @Test
    @DisplayName("Bilinen yer tutucular geçer, bilinmeyenin ADI döner")
    void unknownPlaceholderDetection() {
        assertThat(UserPushController.unknownPlaceholder(
                "{seviye} > {ad}: {hedef} yanıt vermiyor — {neden}. {saat}")).isNull();
        assertThat(UserPushController.unknownPlaceholder("düz metin, yer tutucu yok")).isNull();
        assertThat(UserPushController.unknownPlaceholder("{seviye} {sevye}")).isEqualTo("sevye");
        assertThat(UserPushController.unknownPlaceholder("{unknown_thing}")).isEqualTo("unknown_thing");
    }

    // ── Yonetim uclari: yetki kapisi + MASKELEME ──────────────────────────────
    //
    // Bu controller 200 satir ve SIR isliyor (webhook Authorization basliklari sifreli
    // saklaniyor) ama tek bir testi vardi. Iki sozlesme test ediliyor:
    //
    // 1. getSettings sirlari MASKELI dondurur — sifreli deger sunucudan DUZ CIKMAZ. Maskeleme
    //    bozulursa panel acan herkes token'i goruer ve bunu hicbir sey yakalamaz.
    // 2. Yonetim uclari GLOBAL ADMIN ister. requireAdmin dusesse USER rolu push yapilandirmasini
    //    (URL, basliklar, kapsamlar) degistirebilirdi.

    private static final String STORED_HEADERS =
            "[{\"name\":\"Authorization\",\"value\":\"ENC(gercek-token)\",\"secret\":true},"
          + " {\"name\":\"X-Env\",\"value\":\"prod\",\"secret\":false}]";

    private static UserPushController controllerWith(AppSettingsService settings) {
        return new UserPushController(
                settings,
                mock(UserPushService.class),
                mock(com.sitemonitor.repository.UserPushDeliveryRepository.class),
                mock(com.sitemonitor.repository.UserPushScopeRepository.class),
                mock(SecretCipher.class),
                mock(AuditService.class),
                mock(com.sitemonitor.service.UserPushRecipientResolver.class),
                mock(com.sitemonitor.repository.AppUserRepository.class),
                mock(com.sitemonitor.repository.TeamRepository.class));
    }

    private static MockHttpSession session(String role, boolean scoped) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        // viewTeamIds DOLU ise global admin DEGILDIR (mudur deseni) — SessionScope kurali.
        if (scoped) s.setAttribute("viewTeamIds", List.of(5L));
        return s;
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("SIZINTI KAPISI: getSettings sir basliklarini MASKELI dondurur")
    void getSettings_masksSecretHeaders() {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getString(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);
        when(settings.getString("site.monitor.userpush.headers", ""))
                .thenReturn(STORED_HEADERS);
        when(settings.getString(org.mockito.ArgumentMatchers.eq("site.monitor.userpush.headers"),
                org.mockito.ArgumentMatchers.any())).thenReturn(STORED_HEADERS);

        // Yanit sarmalayicisi: { success, data: { settings, scopes, defaults, health } }
        var body = controllerWith(settings).getSettings(session("ADMIN", false)).getBody();
        var data = (Map<String, Object>) body.get("data");
        var map = (Map<String, Object>) data.get("settings");
        var headers = (List<Map<String, Object>>) map.get("site.monitor.userpush.headers");

        var auth = headers.stream().filter(h -> "Authorization".equals(h.get("name"))).findFirst().orElseThrow();
        assertThat(String.valueOf(auth.get("value")))
                .as("sifreli deger DUZ donuyor — panel acan herkes token'i gorur")
                .doesNotContain("gercek-token")
                .doesNotContain("ENC(");
        assertThat(auth).containsEntry("secret", true);

        // Sir OLMAYAN baslik aynen doner (maskeleme fazla genis olmamali).
        var env = headers.stream().filter(h -> "X-Env".equals(h.get("name"))).findFirst().orElseThrow();
        assertThat(env).containsEntry("value", "prod");
    }

    @Test
    @DisplayName("YETKI: kapsamli mudur-admin (viewTeamIds dolu) yonetim ucuna GIRER (2026-09-10 karari; url/headers GLOBAL_ONLY ile serviste kapali)")
    void getSettings_scopedAdmin_allowed() {
        // 2026-09-10: mudur operasyonel ayarlari yonetir; userpush.url/headers'i AppSettingsService.save
        // GLOBAL_ONLY ile reddeder (AppSettingsServiceTest.scopedAdmin_globalOnlyRejected_operationalAllowed).
        org.assertj.core.api.Assertions.assertThatCode(() -> controllerWith(mock(AppSettingsService.class))
                .getSettings(session("ADMIN", true)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("YETKI: USER rolu yonetim ucuna GIREMEZ")
    void getSettings_user_rejected() {
        assertThatThrownBy(() -> controllerWith(mock(AppSettingsService.class))
                .getSettings(session("USER", false)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("YETKI: oturumsuz istek yonetim ucuna GIREMEZ")
    void getSettings_noSession_rejected() {
        assertThatThrownBy(() -> controllerWith(mock(AppSettingsService.class))
                .getSettings((HttpSession) null))
                .isInstanceOf(SecurityException.class);
    }

    // ── /stats: beş pencere + takım kırılımı (2026-09-12) ─────────────────────

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("stats: 24h/7d/15d/30d/60d pencereleri; her pencerede durum sayaçları + takım kırılımı (toplam azalan, takımsız sonda); last24h/last7d geriye uyumlu")
    void stats_fiveWindowsWithTeamBreakdown() {
        var deliveryRepo = mock(com.sitemonitor.repository.UserPushDeliveryRepository.class);
        var teamRepo = mock(com.sitemonitor.repository.TeamRepository.class);
        var pushService = mock(UserPushService.class);
        com.sitemonitor.model.Team a = new com.sitemonitor.model.Team(); a.setId(5L); a.setName("Takım A");
        com.sitemonitor.model.Team b = new com.sitemonitor.model.Team(); b.setId(6L); b.setName("Takım B");
        when(teamRepo.findAll()).thenReturn(List.of(a, b));
        when(deliveryRepo.countByStatusSince(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"SENT", 4L}, new Object[]{"FAILED", 1L}));
        when(deliveryRepo.countByTeamAndStatusSince(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{6L, "SENT", 1L},
                new Object[]{5L, "SENT", 3L}, new Object[]{5L, "FAILED", 1L},
                new Object[]{null, "SENT", 2L}));
        when(pushService.healthSnapshot()).thenReturn(java.util.Map.of());
        var c = new UserPushController(mock(AppSettingsService.class), pushService, deliveryRepo,
                mock(com.sitemonitor.repository.UserPushScopeRepository.class), mock(SecretCipher.class),
                mock(AuditService.class), mock(com.sitemonitor.service.UserPushRecipientResolver.class),
                mock(com.sitemonitor.repository.AppUserRepository.class), teamRepo);

        var body = (java.util.Map<String, Object>) c.stats(session("ADMIN", false)).getBody();
        var data = (java.util.Map<String, Object>) body.get("data");
        var windows = (java.util.Map<String, Object>) data.get("windows");
        assertThat(windows.keySet()).containsExactly("24h", "7d", "15d", "30d", "60d");
        assertThat((java.util.Map<String, Object>) data.get("last24h")).containsEntry("SENT", 4L);
        var w24 = (java.util.Map<String, Object>) windows.get("24h");
        assertThat((java.util.Map<String, Object>) w24.get("counts")).containsEntry("FAILED", 1L);
        var teams = (List<java.util.Map<String, Object>>) w24.get("teams");
        assertThat(teams).extracting(m -> m.get("team_name")).containsExactly("Takım A", "Takım B", null);
        assertThat(teams.get(0)).containsEntry("SENT", 3L).containsEntry("FAILED", 1L).containsEntry("total", 4L);
        assertThat(teams.get(2)).containsEntry("team_id", null).containsEntry("total", 2L);
    }
}
