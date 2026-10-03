package com.sitemonitor.service;

import com.sitemonitor.model.QuietDigestItem;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.QuietDigestItemRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Takım sessiz saati ayar önbelleği (2026-10-01, onaylı öneri 15): TTL içinde TEK sorgu; kayıt sonrası {@code invalidate}
 * hemen tazeler; okuma hatası / bozuk ayar = pencere YOK (bildirim asla engellenmez); erteleme kaydı pencere başına tek.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamQuietHoursServiceTest {

    @Mock TeamRepository teamRepo;
    @Mock QuietDigestItemRepository itemRepo;
    TeamQuietHoursService svc;
    static final Instant NIGHT = QuietHoursTest.ist("2026-10-01T23:00:00");

    @BeforeEach
    void setUp() {
        svc = new TeamQuietHoursService(teamRepo, itemRepo);
        svc.clock = Clock.fixed(NIGHT, ZoneOffset.UTC);
    }

    static Team team(long id, String start, String end) {
        Team t = new Team();
        t.setId(id);
        t.setQuietStart(start);
        t.setQuietEnd(end);
        return t;
    }

    @Test
    @DisplayName("TTL içinde tek sorgu; invalidate sonrası yeniden okunur; TTL dolunca yeniden okunur")
    void cacheTtlAndInvalidate() {
        when(teamRepo.findQuietConfigured()).thenReturn(List.of(team(1, "22:00", "07:00")));
        for (int i = 0; i < 50; i++) assertThat(svc.isConfigured(1L)).isTrue();
        verify(teamRepo, times(1)).findQuietConfigured();

        when(teamRepo.findQuietConfigured()).thenReturn(List.of());
        svc.invalidate();
        assertThat(svc.isConfigured(1L)).isFalse();
        verify(teamRepo, times(2)).findQuietConfigured();

        svc.clock = Clock.fixed(NIGHT.plus(TeamQuietHoursService.CACHE_TTL), ZoneOffset.UTC);
        svc.isConfigured(1L);
        verify(teamRepo, times(3)).findQuietConfigured();
    }

    @Test
    @DisplayName("defersPush (2026-10-03, fırtınanın bireysel push'u): yalnız ertelenebilir tetik + pencere içi + ertelenebilir seviye; özet kaydı YAZMAZ")
    void defersPush_mirrorsDeferralRule_withoutDigest() {
        when(teamRepo.findQuietConfigured()).thenReturn(List.of(team(1, "22:00", "07:00")));

        assertThat(svc.defersPush(1L, "WARNING", "INITIAL")).isTrue();
        assertThat(svc.defersPush(1L, "WARNING", "ESCALATION")).isTrue();
        assertThat(svc.defersPush(1L, "WARNING", "DAILY_REALERT")).isTrue();
        assertThat(svc.defersPush(1L, "CRITICAL", "INITIAL")).as("KRİTİK asla ertelenmez").isFalse();
        assertThat(svc.defersPush(1L, "WARNING", "MANUAL")).as("elle gönderim ertelenmez").isFalse();
        assertThat(svc.defersPush(2L, "WARNING", "INITIAL")).as("pencere tanımsız takım").isFalse();
        assertThat(svc.defersPush(null, "WARNING", "INITIAL")).isFalse();
        svc.clock = Clock.fixed(QuietHoursTest.ist("2026-10-01T12:00:00"), ZoneOffset.UTC);
        assertThat(svc.defersPush(1L, "WARNING", "INITIAL")).as("pencere dışı").isFalse();
        verifyNoInteractions(itemRepo);
    }

    @Test
    @DisplayName("Okuma hatası ve bozuk ayar = pencere yok (erteleme yok)")
    void failuresMeanNoWindow() {
        when(teamRepo.findQuietConfigured()).thenThrow(new RuntimeException("column quiet_start does not exist"));
        assertThat(svc.deferral(1L, "WARNING", NIGHT)).isNull();
        svc.invalidate();
        reset(teamRepo);
        when(teamRepo.findQuietConfigured()).thenReturn(List.of(team(1, "22:00", "22:00"), team(2, "bozuk", "07:00")));
        assertThat(svc.anyConfigured()).isFalse();
    }

    @Test
    @DisplayName("Erteleme kararı: pencere + seviye; KRİTİK ve pencere dışı null")
    void deferralDecision() {
        when(teamRepo.findQuietConfigured()).thenReturn(List.of(team(1, "22:00", "07:00")));
        assertThat(svc.deferral(1L, "WARNING", NIGHT)).isNotNull();
        assertThat(svc.deferral(1L, "CRITICAL", NIGHT)).isNull();
        assertThat(svc.deferral(1L, "HIGH", NIGHT)).isNull();
        assertThat(svc.deferral(1L, "WARNING", QuietHoursTest.ist("2026-10-01T12:00:00"))).isNull();
        assertThat(svc.deferral(2L, "WARNING", NIGHT)).isNull();   // ayarsız takım
    }

    @Test
    @DisplayName("Erteleme kaydı pencere başına tek: UNIQUE çakışması false döner (istisna yayılmaz)")
    void recordDeferral_uniquePerWindow() {
        when(teamRepo.findQuietConfigured()).thenReturn(List.of(team(1, "22:00", "07:00")));
        when(itemRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0))
                .thenThrow(new DataIntegrityViolationException("ux_qdi_event_team_window"));
        QuietHours.Occurrence occ = svc.deferral(1L, "WARNING", NIGHT);

        assertThat(svc.recordDeferral(7L, 1L, occ, "WARNING", "INITIAL", NIGHT)).isTrue();
        assertThat(svc.recordDeferral(7L, 1L, occ, "WARNING", "DAILY_REALERT", NIGHT)).isFalse();
        verify(itemRepo, times(2)).saveAndFlush(any(QuietDigestItem.class));
    }
}
