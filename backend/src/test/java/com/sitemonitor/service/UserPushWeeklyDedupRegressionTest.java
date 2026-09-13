package com.sitemonitor.service;

import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Regression: ISSUE-001 — aynı kişi (PO = müdür e-postası) bir onay için İKİ push aldı (takım + müdür satırı)
// Found by /qa on 2026-09-13
// Report: .gstack/qa-reports/qa-report-localhost-2026-09-13.md
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPushWeeklyDedupRegressionTest {

    @Mock AppSettingsService appSettings;
    @Mock UserPushDeliveryRepository deliveryRepo;
    @Mock UserPushScopeRepository scopeRepo;
    @Mock UserPushRecipientResolver resolver;
    @Mock AlertEventRepository alertEventRepo;
    @Mock SecretCipher secretCipher;
    @Mock TrustEvaluator trustEvaluator;
    @Mock CaAutoPinService caAutoPinService;

    private UserPushService service;

    @BeforeEach
    void setUp() {
        service = new UserPushService(appSettings, deliveryRepo, scopeRepo, resolver,
                alertEventRepo, secretCipher, trustEvaluator, caAutoPinService);
        when(appSettings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(inv -> inv.getArgument(1));
        when(appSettings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(true);
        when(appSettings.getString(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(appSettings.getInt(anyString(), any(Integer.class))).thenAnswer(inv -> inv.getArgument(1));
        when(appSettings.getCsv(anyString(), anyString())).thenReturn(List.of("1"));
        when(scopeRepo.findByScopeTypeAndScopeKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(deliveryRepo.countRecentForUser(anyString(), anyString())).thenReturn(0L);
        when(deliveryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // outbox worker'ı beslemeyelim: PENDING listesi boş dönsün (HTTP tarafı bu testin konusu değil)
        when(deliveryRepo.findTop50ByStatusOrderByIdAsc(anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("ISSUE-001: müdür push'u önce gider, dönen usernames takım bildiriminden düşülür → aynı kişiye tek satır")
    void managerAlreadyNotified_isExcludedFromTeamNotice() {
        // Takım grubu: PO (N64954) + uzman (N00002); müdür de N64954 (kontak e-postası eşleşti)
        when(resolver.resolve(anyLong(), anyString())).thenReturn(List.of(
                new UserPushRecipientResolver.Recipient("N64954", "PO Müdür", null),
                new UserPushRecipientResolver.Recipient("N00002", "Uzman", null)));

        Map<String, Object> mgr = service.enqueueDirect(
                List.of(new UserPushService.DirectRecipient("n64954", "PO Müdür", false)),
                5L, "WEEKLY_REPORT", "WARNING", "WEEKLY_REPORT", "Takım A 2026-W36", "müdüre", "WR_APPROVED:11:2:MGR");
        assertThat(mgr.get("queued")).isEqualTo(1);
        @SuppressWarnings("unchecked") List<String> notified = (List<String>) mgr.get("usernames");
        assertThat(notified).containsExactly("n64954");

        Map<String, Object> team = service.enqueueTeamNotice(5L, "WEEKLY_REPORT", "WARNING", "WEEKLY_REPORT",
                "Takım A 2026-W36", "takıma", "WR_APPROVED:11:2", Set.copyOf(notified));
        assertThat(team.get("queued")).isEqualTo(1);     // yalnız uzman
        assertThat(team.get("skipped")).isEqualTo(1);    // PO=müdür düşüldü (harf duyarsız: n64954 ≡ N64954)

        ArgumentCaptor<UserPushDelivery> cap = ArgumentCaptor.forClass(UserPushDelivery.class);
        verify(deliveryRepo, org.mockito.Mockito.times(2)).save(cap.capture());
        List<String> rows = cap.getAllValues().stream().map(d -> d.getUsername() + "|" + d.getDedupeKey()).toList();
        assertThat(rows).containsExactly("n64954|WR_APPROVED:11:2:MGR", "N00002|WR_APPROVED:11:2");
    }

    @Test
    @DisplayName("boş / null dışlama kümesi → davranış değişmez (7-arg aşırı yükleme aynı sonucu verir)")
    void emptyExclusion_isNoop() {
        when(resolver.resolve(anyLong(), anyString())).thenReturn(List.of(
                new UserPushRecipientResolver.Recipient("N00001", "A", null)));
        assertThat(service.enqueueTeamNotice(5L, "WEEKLY_REPORT", "WARNING", "WEEKLY_REPORT", "x", "m", "k1", null).get("queued")).isEqualTo(1);
        assertThat(service.enqueueTeamNotice(5L, "WEEKLY_REPORT", "WARNING", "WEEKLY_REPORT", "x", "m", "k2").get("queued")).isEqualTo(1);
    }
}
