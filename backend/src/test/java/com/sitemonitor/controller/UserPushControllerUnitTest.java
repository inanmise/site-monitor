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

    // ── Teslimat pencereleri UTC (prod kapısı 2026-09-25, O-6 / eski Y20) ─────────────────────
    // Satırlar UTC yazılıyor (UserPushService.ISO), pencere sınırı İstanbul yereliyle hesaplanıyordu:
    // "since" saklanan değerlerin 3 saat İLERİSİNDEydi — dakikada 3 test tavanı hiç tetiklenmiyordu.

    @Test
    @DisplayName("O-6: test tavanı penceresi UTC 'şimdi − 1 dk' — İstanbul yereliyle 3 saat kayık DEĞİL")
    void testLimitWindow_isUtc() {
        UserPushService push = mock(UserPushService.class);
        when(push.enabled()).thenReturn(true);
        when(push.sendTest(org.mockito.ArgumentMatchers.anyList(), anyString(), anyString())).thenReturn(Map.of());
        com.sitemonitor.repository.UserPushDeliveryRepository repo =
                mock(com.sitemonitor.repository.UserPushDeliveryRepository.class);
        UserPushController c = new UserPushController(mock(AppSettingsService.class), push, repo,
                mock(com.sitemonitor.repository.UserPushScopeRepository.class), mock(SecretCipher.class),
                mock(AuditService.class), mock(com.sitemonitor.service.UserPushRecipientResolver.class),
                mock(com.sitemonitor.repository.AppUserRepository.class), mock(com.sitemonitor.repository.TeamRepository.class));

        c.sendTest(Map.of("usernames", List.of("N00001")), session("ADMIN", false));

        org.mockito.ArgumentCaptor<String> since = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(repo).countByTriggerAndCreatedAtGreaterThanEqual(org.mockito.ArgumentMatchers.eq("TEST"), since.capture());
        java.time.LocalDateTime expected = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1);
        java.time.LocalDateTime actual = java.time.LocalDateTime.parse(since.getValue());
        assertThat(java.time.Duration.between(actual, expected).abs().getSeconds())
                .as("pencere UTC olmalı (satırlar UTC yazılıyor); fark: " + since.getValue() + " ↔ " + expected)
                .isLessThan(60L);
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

    // ── Teslimat günlüğü / CSV / stats TAKIM KAPSAMI (2026-09-28 regresyon taraması) ─────────
    // Kapsamlı müdür requireAdmin'den geçiyordu → her takımın kişi adları, durumları (opt-out dâhil) ve mesaj
    // metinleri liste + CSV'den okunabiliyordu. `/explain` ile aynı kural: yalnız YÖNETTİĞİ takımlar.

    private static MockHttpSession scopedManaging(Long... teams) {
        MockHttpSession s = session("ADMIN", true);
        s.setAttribute("manageTeamIds", List.of(teams));
        return s;
    }

    private static UserPushController controllerWithRepo(com.sitemonitor.repository.UserPushDeliveryRepository repo,
                                                          com.sitemonitor.repository.TeamRepository teamRepo) {
        UserPushService push = mock(UserPushService.class);
        when(push.healthSnapshot()).thenReturn(Map.of());
        return new UserPushController(mock(AppSettingsService.class), push, repo,
                mock(com.sitemonitor.repository.UserPushScopeRepository.class), mock(SecretCipher.class),
                mock(AuditService.class), mock(com.sitemonitor.service.UserPushRecipientResolver.class),
                mock(com.sitemonitor.repository.AppUserRepository.class), teamRepo);
    }

    @Test
    @DisplayName("deliveries: global yönetici tüm satırları arar; kapsamlı müdür YALNIZ yönettiği takımlarda (searchInTeams)")
    void deliveries_scopedAdmin_onlyManagedTeams() {
        var repo = mock(com.sitemonitor.repository.UserPushDeliveryRepository.class);
        when(repo.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(org.springframework.data.domain.Page.empty());
        when(repo.searchInTeams(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(org.springframework.data.domain.Page.empty());
        var c = controllerWithRepo(repo, mock(com.sitemonitor.repository.TeamRepository.class));

        c.deliveries(null, null, null, null, null, null, null, null, null, null, 0, 25, session("ADMIN", false));
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.never()).searchInTeams(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        c.deliveries(null, null, null, null, null, null, null, null, null, null, 0, 25, scopedManaging(5L));
        org.mockito.Mockito.verify(repo).searchInTeams(org.mockito.ArgumentMatchers.eq(List.of(5L)),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        // tek arama global yöneticininki (müdürünki searchInTeams'e gitti)
        org.mockito.Mockito.verify(repo, org.mockito.Mockito.times(1)).search(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("deliveries + CSV: kapsamlı müdür yönetmediği takımı süzerse 403; yönettiğini süzebilir; hiç takımı yoksa boş (sorgu yok)")
    void deliveries_scopedAdmin_foreignTeamRejected() {
        var repo = mock(com.sitemonitor.repository.UserPushDeliveryRepository.class);
        when(repo.search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(org.springframework.data.domain.Page.empty());
        var c = controllerWithRepo(repo, mock(com.sitemonitor.repository.TeamRepository.class));

        assertThatThrownBy(() -> c.deliveries(null, 6L, null, null, null, null, null, null, null, null, 0, 25, scopedManaging(5L)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> c.exportDeliveries(null, 6L, null, null, null, null, null, null, null, null, scopedManaging(5L)))
                .isInstanceOf(SecurityException.class);
        org.assertj.core.api.Assertions.assertThatCode(
                () -> c.deliveries(null, 5L, null, null, null, null, null, null, null, null, 0, 25, scopedManaging(5L)))
                .doesNotThrowAnyException();

        var none = mock(com.sitemonitor.repository.UserPushDeliveryRepository.class);
        var c2 = controllerWithRepo(none, mock(com.sitemonitor.repository.TeamRepository.class));
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) c2.deliveries(null, null, null, null, null, null, null, null, null, null, 0, 25,
                session("ADMIN", true)).getBody().get("data");
        assertThat(data).containsEntry("total", 0L);
        org.mockito.Mockito.verifyNoInteractions(none);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("stats: kapsamlı müdür yalnız yönettiği takımın sayılarını ve takım kırılımını görür (başka takım + takımsız satır yok)")
    void stats_scopedAdmin_onlyManagedTeams() {
        var deliveryRepo = mock(com.sitemonitor.repository.UserPushDeliveryRepository.class);
        var teamRepo = mock(com.sitemonitor.repository.TeamRepository.class);
        com.sitemonitor.model.Team a = new com.sitemonitor.model.Team(); a.setId(5L); a.setName("Takım A");
        com.sitemonitor.model.Team b = new com.sitemonitor.model.Team(); b.setId(6L); b.setName("Takım B");
        when(teamRepo.findAll()).thenReturn(List.of(a, b));
        when(deliveryRepo.countByStatusSince(anyString())).thenReturn(List.<Object[]>of(new Object[]{"SENT", 6L}));
        when(deliveryRepo.countByTeamAndStatusSince(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{6L, "SENT", 1L},
                new Object[]{5L, "SENT", 3L}, new Object[]{5L, "SKIPPED_USER_OPT_OUT", 1L},
                new Object[]{null, "SENT", 2L}));
        var c = controllerWithRepo(deliveryRepo, teamRepo);

        var data = (Map<String, Object>) c.stats(scopedManaging(5L)).getBody().get("data");
        assertThat((Map<String, Object>) data.get("last24h")).containsEntry("SENT", 3L).containsEntry("SKIPPED_USER_OPT_OUT", 1L);
        var w24 = (Map<String, Object>) ((Map<String, Object>) data.get("windows")).get("24h");
        assertThat((Map<String, Object>) w24.get("counts")).containsEntry("SENT", 3L);
        assertThat((List<Map<String, Object>>) w24.get("teams")).extracting(m -> m.get("team_name")).containsExactly("Takım A");
    }

    // ── /scopes + /test TAKIM KAPSAMI (2026-09-28 regresyon taraması) ─────────────────────────
    // Kapsamlı müdür requireAdmin'den geçip herhangi bir takımın push'unu kapatabiliyor (/scopes) ve herhangi bir
    // sicile gerçek test push'u atabiliyordu (/test). Kural: yalnız YÖNETTİĞİ takımlar; TYPE satırı yalnız global.

    private static Map<String, Object> scopeRow(String type, String key, boolean enabled) {
        return Map.of("scopeType", type, "scopeKey", key, "enabled", enabled);
    }

    @Test
    @DisplayName("scopes: kapsamlı müdür yönetmediği takımın ya da TYPE satırını değiştiremez (403, HİÇBİR satır yazılmaz); yönettiği takımı değiştirir")
    void scopes_scopedAdmin_onlyManagedTeamRows() {
        var scopeRepo = mock(com.sitemonitor.repository.UserPushScopeRepository.class);
        var c = new UserPushController(mock(AppSettingsService.class), mock(UserPushService.class),
                mock(com.sitemonitor.repository.UserPushDeliveryRepository.class), scopeRepo, mock(SecretCipher.class),
                mock(AuditService.class), mock(com.sitemonitor.service.UserPushRecipientResolver.class),
                mock(com.sitemonitor.repository.AppUserRepository.class), mock(com.sitemonitor.repository.TeamRepository.class));

        assertThatThrownBy(() -> c.saveScopes(List.of(scopeRow("TEAM", "6", false)), scopedManaging(5L)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> c.saveScopes(List.of(scopeRow("TYPE", "http", false)), scopedManaging(5L)))
                .isInstanceOf(SecurityException.class);
        // Toplu gövdede TEK yabancı satır bütün isteği düşürür — yönettiği satır da yazılmaz (yarım uygulama yok).
        assertThatThrownBy(() -> c.saveScopes(List.of(scopeRow("TEAM", "5", false), scopeRow("TEAM", "6", false)),
                scopedManaging(5L))).isInstanceOf(SecurityException.class);
        // Kanonik olmayan anahtar ("05") yönetilen takımı taklit edemez.
        assertThatThrownBy(() -> c.saveScopes(List.of(scopeRow("TEAM", "05", false)), scopedManaging(5L)))
                .isInstanceOf(SecurityException.class);
        org.mockito.Mockito.verify(scopeRepo, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());

        c.saveScopes(List.of(scopeRow("TEAM", "5", false)), scopedManaging(5L));
        var saved = org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.UserPushScope.class);
        org.mockito.Mockito.verify(scopeRepo).save(saved.capture());
        assertThat(saved.getValue().getScopeKey()).isEqualTo("5");
        assertThat(saved.getValue().getEnabled()).isFalse();
    }

    @Test
    @DisplayName("scopes: global yönetici TYPE satırını ve her takımı değiştirir")
    void scopes_globalAdmin_anyRow() {
        var scopeRepo = mock(com.sitemonitor.repository.UserPushScopeRepository.class);
        var c = new UserPushController(mock(AppSettingsService.class), mock(UserPushService.class),
                mock(com.sitemonitor.repository.UserPushDeliveryRepository.class), scopeRepo, mock(SecretCipher.class),
                mock(AuditService.class), mock(com.sitemonitor.service.UserPushRecipientResolver.class),
                mock(com.sitemonitor.repository.AppUserRepository.class), mock(com.sitemonitor.repository.TeamRepository.class));

        c.saveScopes(List.of(scopeRow("TYPE", "http", false), scopeRow("TEAM", "6", false)), session("ADMIN", false));
        org.mockito.Mockito.verify(scopeRepo, org.mockito.Mockito.times(2)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("test: kapsamlı müdür yalnız YÖNETTİĞİ takımların üyelerine test push'u atar (birincil ya da ek üyelik); yabancı sicil 403 ve gönderim YOK")
    void sendTest_scopedAdmin_onlyMembersOfManagedTeams() {
        UserPushService push = mock(UserPushService.class);
        when(push.enabled()).thenReturn(true);
        when(push.sendTest(org.mockito.ArgumentMatchers.anyList(), anyString(), anyString())).thenReturn(Map.of());
        var userRepo = mock(com.sitemonitor.repository.AppUserRepository.class);
        when(userRepo.findMemberIdentities(List.of(5L))).thenReturn(List.<Object[]>of(new Object[]{1L, "n00001", "USER"}));
        var c = new UserPushController(mock(AppSettingsService.class), push,
                mock(com.sitemonitor.repository.UserPushDeliveryRepository.class),
                mock(com.sitemonitor.repository.UserPushScopeRepository.class), mock(SecretCipher.class),
                mock(AuditService.class), mock(com.sitemonitor.service.UserPushRecipientResolver.class),
                userRepo, mock(com.sitemonitor.repository.TeamRepository.class));

        assertThatThrownBy(() -> c.sendTest(Map.of("usernames", List.of("N00001", "N00002")), scopedManaging(5L)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("N00002");
        // Yönetim kapsamı BOŞ müdür (view dolu, manage boş) kimseye gönderemez.
        assertThatThrownBy(() -> c.sendTest(Map.of("usernames", List.of("N00001")), session("ADMIN", true)))
                .isInstanceOf(SecurityException.class);
        org.mockito.Mockito.verify(push, org.mockito.Mockito.never())
                .sendTest(org.mockito.ArgumentMatchers.anyList(), anyString(), anyString());

        c.sendTest(Map.of("usernames", List.of("N00001")), scopedManaging(5L));   // harf duyarsız eşleşme
        org.mockito.Mockito.verify(push).sendTest(org.mockito.ArgumentMatchers.eq(List.of("N00001")), anyString(), anyString());
    }

    @Test
    @DisplayName("test: global yönetici herhangi bir sicile gönderir — üyelik sorgusu yapılmaz")
    void sendTest_globalAdmin_noMembershipCheck() {
        UserPushService push = mock(UserPushService.class);
        when(push.enabled()).thenReturn(true);
        when(push.sendTest(org.mockito.ArgumentMatchers.anyList(), anyString(), anyString())).thenReturn(Map.of());
        var userRepo = mock(com.sitemonitor.repository.AppUserRepository.class);
        var c = new UserPushController(mock(AppSettingsService.class), push,
                mock(com.sitemonitor.repository.UserPushDeliveryRepository.class),
                mock(com.sitemonitor.repository.UserPushScopeRepository.class), mock(SecretCipher.class),
                mock(AuditService.class), mock(com.sitemonitor.service.UserPushRecipientResolver.class),
                userRepo, mock(com.sitemonitor.repository.TeamRepository.class));

        c.sendTest(Map.of("usernames", List.of("N00009")), session("ADMIN", false));
        org.mockito.Mockito.verify(push).sendTest(org.mockito.ArgumentMatchers.eq(List.of("N00009")), anyString(), anyString());
        org.mockito.Mockito.verifyNoInteractions(userRepo);
    }
}
