package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Push kanalında sessiz saat (2026-10-01, onaylı öneri 15):
 * <ul>
 *   <li>Kişisel push sessiz saati global pencerenin kişi eşi: pencerede asgari seviyenin altı {@code SKIPPED_USER_QUIET_HOURS};
 *       KRİTİK ve çözüm (resolvePrior) etkilenmez; alan boşken karar bugünküyle aynı.</li>
 *   <li>Takım sessiz saati: e-posta hunisinin işaretli bağlamı push'u {@code SKIPPED_TEAM_QUIET} karar satırına çevirir;
 *       işaretsiz bağlamda (tanımsız kurulum) davranış aynı.</li>
 * </ul>
 */
class UserPushQuietHoursTest {

    static final Instant NIGHT = QuietHoursTest.ist("2026-10-01T23:00:00");
    static final Instant NOON = QuietHoursTest.ist("2026-10-01T12:00:00");

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("kişisel push sessiz saati (alıcı çözümü)")
    class Personal {
        @Mock AppUserRepository userRepo;
        @Mock AppSettingsService appSettings;
        UserPushRecipientResolver resolver;
        AppUser user;

        @BeforeEach
        void setUp() {
            resolver = new UserPushRecipientResolver(userRepo, appSettings);
            resolver.clock = Clock.fixed(NIGHT, ZoneOffset.UTC);
            when(appSettings.getString(eq("site.monitor.userpush.role-groups"), anyString())).thenReturn(
                    "{\"uzman\":{\"enabled\":true,\"source\":\"orgRole\",\"patterns\":[\"TECH\"],\"minLevel\":\"WARNING\"}}");
            user = new AppUser();
            user.setId(1L);
            user.setUsername("N00001");
            user.setDisplayName("Ayşe");
            user.setOrgRole("TECH");
            user.setActive(true);
            when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(user));
            when(userRepo.findByTeamIdOrderByUsernameAsc(5L)).thenReturn(List.of());
            when(userRepo.findByUsername("N00001")).thenReturn(Optional.of(user));
        }

        private String decision(String level) {
            return resolver.resolve(5L, level).get(0).skipReason();
        }

        @Test
        @DisplayName("Alan boş (varsayılan): gece de karar bugünkü gibi — alıcı")
        void unset_unchanged() {
            assertThat(decision("WARNING")).isNull();
            assertThat(decision("HIGH")).isNull();
            assertThat(resolver.explain(5L, "WARNING")).singleElement()
                    .extracting(UserPushRecipientResolver.Explanation::decision).isEqualTo("RECIPIENT");
        }

        @Test
        @DisplayName("Pencerede UYARI bastırılır (satır kalır); YÜKSEK/KRİTİK geçer; öğlen her şey geçer")
        void window_suppressesBelowMinLevel() {
            user.setPushQuietStart("22:00");
            user.setPushQuietEnd("07:00");
            assertThat(decision("WARNING")).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_QUIET_HOURS);
            assertThat(decision("HIGH")).isNull();
            assertThat(decision("CRITICAL")).isNull();
            assertThat(resolver.explain(5L, "WARNING")).singleElement()
                    .extracting(UserPushRecipientResolver.Explanation::decision)
                    .isEqualTo(UserPushRecipientResolver.SKIPPED_USER_QUIET_HOURS);

            resolver.clock = Clock.fixed(NOON, ZoneOffset.UTC);
            assertThat(decision("WARNING")).isNull();
        }

        @Test
        @DisplayName("Asgari seviye CRITICAL: YÜKSEK de bastırılır, KRİTİK asla; gün süzgeci uygulanır")
        void minLevelCriticalAndDays() {
            user.setPushQuietStart("22:00");
            user.setPushQuietEnd("07:00");
            user.setPushQuietMinLevel("CRITICAL");
            assertThat(decision("HIGH")).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_QUIET_HOURS);
            assertThat(decision("CRITICAL")).isNull();
            user.setPushQuietDays("SAT,SUN");   // 2026-10-01 Perşembe → pencere bu gece yok
            assertThat(decision("HIGH")).isNull();
        }

        @Test
        @DisplayName("Opt-out önceliklidir; çözüm alıcıları (resolvePrior) sessiz saatten etkilenmez")
        void optOutFirst_resolveUnaffected() {
            user.setPushQuietStart("22:00");
            user.setPushQuietEnd("07:00");
            assertThat(resolver.resolvePrior(List.of("N00001"))).singleElement()
                    .extracting(UserPushRecipientResolver.Recipient::skipReason).isNull();
            user.setPushOptOut(true);
            assertThat(decision("WARNING")).isEqualTo("SKIPPED_USER_OPT_OUT");
        }
    }

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    @DisplayName("takım sessiz saati (push karar satırı)")
    class Team {
        @Mock AppSettingsService appSettings;
        @Mock UserPushDeliveryRepository deliveryRepo;
        @Mock UserPushScopeRepository scopeRepo;
        @Mock UserPushRecipientResolver resolver;
        @Mock AlertEventRepository alertEventRepo;
        @Mock SecretCipher secretCipher;
        @Mock TrustEvaluator trustEvaluator;
        @Mock CaAutoPinService caAutoPinService;
        UserPushService service;
        final List<UserPushDelivery> saved = new ArrayList<>();

        @BeforeEach
        void setUp() {
            service = new UserPushService(appSettings, deliveryRepo, scopeRepo, resolver, alertEventRepo,
                    secretCipher, trustEvaluator, caAutoPinService);
            when(appSettings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(i -> i.getArgument(1));
            when(appSettings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(true);
            when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
            when(appSettings.getInt(anyString(), any(Integer.class))).thenAnswer(i -> i.getArgument(1));
            when(scopeRepo.findByScopeTypeAndScopeKey(anyString(), anyString())).thenReturn(Optional.empty());
            when(deliveryRepo.save(any())).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });
            AlertEvent e = new AlertEvent();
            e.setId(1L);
            e.setDomain("svc.example.com");
            e.setAlertType("HTTP_DOWN");
            e.setAlertLevel("WARNING");
            e.setTeamId(5L);
            when(alertEventRepo.findById(1L)).thenReturn(Optional.of(e));
            // kuyruğa yazma yolu çalışmasın diye alıcı yok: işaretsiz bağlamda karar "alıcı yok" olur (bugünkü dal)
            when(resolver.resolve(any(), any())).thenReturn(List.of());
        }

        @Test
        @DisplayName("İşaretli bağlam → SKIPPED_TEAM_QUIET karar satırı (doğru tetik), alıcı çözümüne bile gidilmez")
        void flaggedCtx_teamQuietDecision() {
            service.enqueueAlert(1L, "DAILY_REALERT", 5L, Map.of(EscalationService.CTX_QUIET_DEFERRED, true));

            assertThat(saved).singleElement().satisfies(d -> {
                assertThat(d.getStatus()).isEqualTo(EscalationService.PUSH_SKIPPED_TEAM_QUIET);
                assertThat(d.getTrigger()).isEqualTo("RE_ALERT");
            });
            verify(resolver, never()).resolve(any(), any());
        }

        @Test
        @DisplayName("Takım bildirimi (haftalık rapor vb.) kişisel sessiz saatten etkilenmez — global pencerenin aynası")
        void teamNotice_ignoresPersonalQuiet() {
            List<String> statusesAtSave = new ArrayList<>();
            when(deliveryRepo.save(any())).thenAnswer(i -> {
                UserPushDelivery d = i.getArgument(0);
                statusesAtSave.add(d.getStatus());
                return d;
            });
            when(resolver.resolve(5L, "INFO")).thenReturn(List.of(new UserPushRecipientResolver.Recipient(
                    "N00001", "Ayşe", UserPushRecipientResolver.SKIPPED_USER_QUIET_HOURS)));

            service.enqueueTeamNotice(5L, "WEEKLY_REPORT", "INFO", "WEEKLY_REPORT", "Haftalık rapor", "onaylandı", "WR:1");

            assertThat(statusesAtSave).singleElement().isEqualTo("PENDING");
        }

        @Test
        @DisplayName("İşaretsiz bağlam (tanımsız kurulum) → bugünkü karar")
        void unflaggedCtx_unchanged() {
            service.enqueueAlert(1L, "INITIAL", 5L, Map.of("detail", "x"));

            ArgumentCaptor<UserPushDelivery> c = ArgumentCaptor.forClass(UserPushDelivery.class);
            verify(deliveryRepo).save(c.capture());
            assertThat(c.getValue().getStatus()).isEqualTo("SKIPPED_NO_RECIPIENTS");
            verify(resolver).resolve(5L, "WARNING");
        }
    }
}
