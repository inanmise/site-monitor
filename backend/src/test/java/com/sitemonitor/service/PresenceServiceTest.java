package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çevrimiçi kullanıcı özeti (2026-10-02): birincil takıma göre sayım, takımsız kova, silinmiş takım → takımsız,
 * çoktan aza + ada göre sıra, eşik = hasLiveSession penceresi, kuruluş geneli önbellek.
 */
@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;

    private static Team team(long id, String name) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        return t;
    }

    @Test
    @DisplayName("takım sayıları birincil takıma göre; takımsız + silinmiş takım ayrı kovada; toplam tutar")
    void groupsByPrimaryTeam() {
        when(userRepo.countOnlineByPrimaryTeam(anyString())).thenReturn(List.of(
                new Object[]{1L, 3L}, new Object[]{2L, 5L}, new Object[]{null, 2L}, new Object[]{99L, 1L},
                new Object[]{3L, 3L}));
        when(teamRepo.findAllById(any())).thenReturn(List.of(team(1, "Ödeme"), team(2, "Çekirdek"), team(3, "Ağ")));

        Map<String, Object> out = new PresenceService(userRepo, teamRepo).compute();

        assertThat(out.get("total")).isEqualTo(14L);
        assertThat(out.get("no_team")).isEqualTo(3L);   // 2 takımsız + 1 silinmiş takım
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> teams = (List<Map<String, Object>>) out.get("teams");
        assertThat(teams).extracting(m -> m.get("team_name")).containsExactly("Çekirdek", "Ağ", "Ödeme");
        assertThat(teams).extracting(m -> m.get("count")).containsExactly(5L, 3L, 3L);
        assertThat(teams.get(0)).containsOnlyKeys("team_id", "team_name", "count");   // kişi bilgisi yok
        assertThat(out.get("window_seconds")).isEqualTo(120L);
    }

    @Test
    @DisplayName("kimse çevrimiçi değilse sıfır; takım tablosu sorgulanmaz")
    void empty() {
        when(userRepo.countOnlineByPrimaryTeam(anyString())).thenReturn(List.of());
        Map<String, Object> out = new PresenceService(userRepo, teamRepo).compute();
        assertThat(out.get("total")).isEqualTo(0L);
        assertThat((List<?>) out.get("teams")).isEmpty();
        verify(teamRepo, times(0)).findAllById(any());
    }

    @Test
    @DisplayName("eşik = şimdi − 120 sn (ISO, UTC) — hasLiveSession ile aynı tazelik")
    void thresholdMatchesLiveWindow() {
        when(userRepo.countOnlineByPrimaryTeam(anyString())).thenReturn(List.of());
        new PresenceService(userRepo, teamRepo).compute();
        ArgumentCaptor<String> th = ArgumentCaptor.forClass(String.class);
        verify(userRepo).countOnlineByPrimaryTeam(th.capture());
        Instant t = LocalDateTime.parse(th.getValue()).toInstant(ZoneOffset.UTC);
        long ageSec = Instant.now().getEpochSecond() - t.getEpochSecond();
        assertThat(ageSec).isBetween(119L, 122L);
    }

    @Test
    @DisplayName("online(): kuruluş geneli önbellek — art arda çağrılar tek sorgu")
    void cached() {
        when(userRepo.countOnlineByPrimaryTeam(anyString())).thenReturn(List.of());
        PresenceService s = new PresenceService(userRepo, teamRepo);
        s.online();
        s.online();
        s.online();
        verify(userRepo, times(1)).countOnlineByPrimaryTeam(anyString());
    }
}
