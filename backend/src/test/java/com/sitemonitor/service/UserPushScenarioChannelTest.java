package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.UserPushScope;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "Kim bilgilendirilir?" push KANAL durumu ({@link UserPushService#scenarioChannel}, 2026-09-28) — herkesi aynı anda
 * etkileyen kapılar (global anahtar, servis adresi, tür/takım katmanı, sessiz saat) gerçek gönderimin koduyla
 * AYNI kararı vermeli: tür/takım için {@link UserPushService#preview} ile, sessiz saat için
 * {@link UserPushService#quietHoursBlock} ile karşılaştırılır.
 */
class UserPushScenarioChannelTest {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private AppSettingsService settings;
    private UserPushScopeRepository scopeRepo;
    private AlertEventRepository alertRepo;
    private UserPushService service;

    @BeforeEach
    void setUp() {
        settings = mock(AppSettingsService.class);
        scopeRepo = mock(UserPushScopeRepository.class);
        alertRepo = mock(AlertEventRepository.class);
        when(settings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(true);
        when(settings.getString(eq("site.monitor.userpush.url"), any())).thenReturn("https://push.example.com/api/v1/send");
        when(scopeRepo.findByScopeTypeAndScopeKey(anyString(), anyString())).thenReturn(Optional.empty());
        UserPushRecipientResolver resolver = mock(UserPushRecipientResolver.class);
        when(resolver.resolve(any(), any())).thenReturn(List.of(new UserPushRecipientResolver.Recipient("N00001", "Kişi A", null)));
        service = new UserPushService(settings, mock(UserPushDeliveryRepository.class), scopeRepo, resolver, alertRepo,
                mock(SecretCipher.class), mock(TrustEvaluator.class), mock(CaAutoPinService.class));
    }

    @AfterEach
    void tearDown() { service.shutdown(); }

    private void scopeOff(String type, String key) {
        UserPushScope s = new UserPushScope();
        s.setScopeType(type); s.setScopeKey(key); s.setEnabled(false);
        when(scopeRepo.findByScopeTypeAndScopeKey(type, key)).thenReturn(Optional.of(s));
    }

    private void quiet(LocalTime start, LocalTime end, String min) {
        when(settings.getString(eq("site.monitor.userpush.quiet-start"), any())).thenReturn(start.format(HM));
        when(settings.getString(eq("site.monitor.userpush.quiet-end"), any())).thenReturn(end.format(HM));
        when(settings.getString(eq("site.monitor.userpush.quiet-min-level"), any())).thenReturn(min);
    }

    @Test
    @DisplayName("Açık + adresli + kapsam içi → engel yok; ayar DEĞERİ (adres) yanıtta YOK, yalnız var/yok")
    void open_noBlock_noUrlValue() {
        Map<String, Object> out = service.scenarioChannel(5L, "HIGH", false);
        assertThat(out).containsEntry("enabled", true).containsEntry("configured", true).containsEntry("team_enabled", true)
                .containsEntry("types", List.of("cert")).containsEntry("disabled_types", List.of())
                .containsEntry("quiet_active", false).containsEntry("block_reason", null);
        assertThat(out.toString()).doesNotContain("push.example.com").doesNotContain("https");
    }

    @Test
    @DisplayName("Global anahtar kapalı → CHANNEL_DISABLED; adres boş → NOT_CONFIGURED (gönderim 'URL ayarlanmamış' ile düşer)")
    void disabledAndNotConfigured() {
        when(settings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(false);
        assertThat(service.scenarioChannel(5L, "HIGH", false)).containsEntry("enabled", false)
                .containsEntry("block_reason", "CHANNEL_DISABLED");

        when(settings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(true);
        when(settings.getString(eq("site.monitor.userpush.url"), any())).thenReturn("  ");
        assertThat(service.scenarioChannel(5L, "HIGH", false)).containsEntry("configured", false)
                .containsEntry("block_reason", "NOT_CONFIGURED");
    }

    @Test
    @DisplayName("Takım/tür katmanı: senaryo kararı gerçek gönderimin önizlemesiyle (preview) AYNI kodu verir")
    void scopeDecisions_matchRealSendPreview() {
        AlertEvent ev = new AlertEvent();
        ev.setId(11L); ev.setTeamId(5L); ev.setAlertType("EXPIRY"); ev.setAlertLevel("HIGH");
        when(alertRepo.findById(11L)).thenReturn(Optional.of(ev));

        scopeOff("TEAM", "5");
        assertThat(service.scenarioChannel(5L, "HIGH", false)).containsEntry("team_enabled", false)
                .containsEntry("block_reason", "SKIPPED_TEAM_OFF");
        assertThat(service.preview(11L, 5L).blockReason()).isEqualTo("SKIPPED_TEAM_OFF");

        scopeOff("TYPE", "cert");
        assertThat(service.scenarioChannel(5L, "HIGH", false)).containsEntry("block_reason", "SKIPPED_TYPE_OFF")
                .containsEntry("disabled_types", List.of("cert"));
        assertThat(service.preview(11L, 5L).blockReason()).isEqualTo("SKIPPED_TYPE_OFF");   // gönderim de önce türe bakar
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("İzleme senaryosu dokuz türü sorar: bir tür kapalı → kısmi (engel yok), hepsi kapalı → SKIPPED_TYPE_OFF")
    void monitorKind_partialAndFullTypeOff() {
        scopeOff("TYPE", "ping");
        Map<String, Object> partial = service.scenarioChannel(5L, "HIGH", true);
        assertThat((List<String>) partial.get("types")).hasSize(9).doesNotContain("cert");
        assertThat(partial).containsEntry("disabled_types", List.of("ping")).containsEntry("block_reason", null);

        for (String f : MonitorTypeCatalog.ORDER) scopeOff("TYPE", f);
        assertThat(service.scenarioChannel(5L, "HIGH", true)).containsEntry("block_reason", "SKIPPED_TYPE_OFF");
    }

    @Test
    @DisplayName("Sessiz saat ŞU AN açıksa asgari seviyenin altı engellenir — quietHoursBlock ile birebir aynı karar")
    void quietHours_matchSendDecision() {
        LocalTime now = LocalTime.now(IST);
        quiet(now.minusHours(1), now.plusHours(1), "CRITICAL");   // gece yarısını aşan pencere de contains() ile doğru
        Map<String, Object> high = service.scenarioChannel(5L, "HIGH", false);
        assertThat(high).containsEntry("quiet_active", true).containsEntry("quiet_blocks_level", true)
                .containsEntry("quiet_min_level", "CRITICAL").containsEntry("block_reason", "SKIPPED_QUIET_HOURS");
        assertThat(service.quietHoursBlock("HIGH")).isTrue();

        Map<String, Object> critical = service.scenarioChannel(5L, "CRITICAL", false);
        assertThat(critical).containsEntry("quiet_blocks_level", false).containsEntry("block_reason", null);
        assertThat(service.quietHoursBlock("CRITICAL")).isFalse();

        // Pencere şimdi DEĞİL: engel yok ama ekran "bu seviye o saatlerde gitmez" diyebilsin
        quiet(now.plusHours(2), now.plusHours(3), "CRITICAL");
        Map<String, Object> later = service.scenarioChannel(5L, "HIGH", false);
        assertThat(later).containsEntry("quiet_active", false).containsEntry("quiet_blocks_level", true)
                .containsEntry("block_reason", null).containsEntry("quiet_start", now.plusHours(2).format(HM));
        assertThat(service.quietHoursBlock("HIGH")).isFalse();

        // Sıfır uzunluklu pencere = pencere yok (Y20) — iki yol da susturmaz
        quiet(now, now, "CRITICAL");
        assertThat(service.scenarioChannel(5L, "HIGH", false)).containsEntry("quiet_start", null).containsEntry("block_reason", null);
        assertThat(service.quietHoursBlock("HIGH")).isFalse();
    }
}
