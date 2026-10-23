package com.sitemonitor.service.schedule;

import com.sitemonitor.service.AppSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R3-1 3e (2026-10-23): lider bırakma kuralı — ayar açık, bu pod lider ve başka canlı pod KESİN var; ucuzdan pahalıya (takipçi /
 * kural kapalıyken heartbeat okunmaz); ayar okunamazsa varsayılan (açık); erteleme süresi cron payına sığacak biçimde sınırlı.
 */
class SideJobPlacementTest {

    @Test
    @DisplayName("Kural: yalnız lider + kural açık + başka canlı pod; takipçide / kural kapalıyken canlılık hiç sorulmaz")
    void leaderShouldYield() {
        PodLiveness live = mock(PodLiveness.class);
        when(live.anotherLivePod()).thenReturn(true);
        assertThat(new SideJobPlacement(() -> true, live, 20_000).leaderShouldYield(true)).isTrue();

        PodLiveness untouched = mock(PodLiveness.class);
        assertThat(new SideJobPlacement(() -> true, untouched, 20_000).leaderShouldYield(false)).as("takipçi").isFalse();
        assertThat(new SideJobPlacement(() -> false, untouched, 20_000).leaderShouldYield(true)).as("kural kapalı").isFalse();
        verifyNoInteractions(untouched);

        PodLiveness alone = mock(PodLiveness.class);
        when(alone.anotherLivePod()).thenReturn(false);
        assertThat(new SideJobPlacement(() -> true, alone, 20_000).leaderShouldYield(true)).as("tek pod").isFalse();
        assertThat(new SideJobPlacement(() -> true, null, 20_000).leaderShouldYield(true)).as("canlılık yok").isFalse();
    }

    @Test
    @DisplayName("Ayar CANLI okunur (AppSettingsService); okunamazsa varsayılan açık")
    void liveSettingWithDefault() {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(SideJob.LEADER_YIELD_KEY, SideJob.LEADER_YIELD_DEFAULT)).thenReturn(false, true);
        SideJobPlacement p = new SideJobPlacement(settings, mock(PodLiveness.class), 20_000);
        assertThat(p.yieldEnabled()).isFalse();
        assertThat(p.yieldEnabled()).as("ayar değişti — yeniden başlatma yok").isTrue();
        assertThat(new SideJobPlacement(() -> { throw new IllegalStateException("x"); }, null, 0).yieldEnabled())
                .isEqualTo(SideJob.LEADER_YIELD_DEFAULT).isTrue();
    }

    @Test
    @DisplayName("Erteleme süresi [0, 25 sn] aralığına sıkıştırılır (cron payı 60 sn − tur kaydı yeniden denemeleri ~30 sn)")
    void deferIsClamped() {
        assertThat(new SideJobPlacement(() -> true, null, 20_000).cronDeferMs()).isEqualTo(20_000);
        assertThat(new SideJobPlacement(() -> true, null, 999_999).cronDeferMs()).isEqualTo(SideJobPlacement.MAX_CRON_DEFER_MS);
        assertThat(new SideJobPlacement(() -> true, null, -5).cronDeferMs()).isZero();
        assertThat(SideJobPlacement.DEFAULT_CRON_DEFER_MS).isLessThanOrEqualTo(SideJobPlacement.MAX_CRON_DEFER_MS);
    }

    @Test
    @DisplayName("Durum satırı için canlılık önbellekten (null = bilinmiyor)")
    void cachedOtherLivePod() {
        PodLiveness live = mock(PodLiveness.class);
        when(live.cachedAnotherLivePod()).thenReturn(Boolean.TRUE);
        assertThat(new SideJobPlacement(() -> true, live, 0).cachedOtherLivePod()).isTrue();
        assertThat(new SideJobPlacement(() -> true, null, 0).cachedOtherLivePod()).isNull();
    }
}
