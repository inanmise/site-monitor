package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserTeamSource;
import com.sitemonitor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Takım varlık taşıması üyelik kaynağını TEAM_MOVE olarak işaretler (2026-09-26) — "bu kişi bu takıma
 * nereden geldi?" ekrandan cevaplansın. Hedefe zaten üye olanın mevcut izi ezilmez.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamAdminMoveSourceTest {

    @Mock TeamRepository teamRepo;
    @Mock AppUserRepository userRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock NotificationGroupRepository groupRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock PageMonitorRepository pageRepo;
    @Mock PageSpeedMonitorRepository pageSpeedRepo;
    @Mock ScriptedMonitorRepository scriptedRepo;
    @Mock DomainMonitorRepository domainRepo;
    @InjectMocks TeamAdminService service;

    private TeamSourceFakes.Store sources;

    private static AppUser user(long id, Long primary, Long... teams) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername("U" + id);
        u.setTeamId(primary);
        u.setTeamIds(new LinkedHashSet<>(List.of(teams)));
        return u;
    }

    @BeforeEach
    void setUp() {
        sources = new TeamSourceFakes.Store();
        service.setTeamSources(sources.service);
        Team target = new Team();
        target.setId(9L);
        when(teamRepo.findById(9L)).thenReturn(Optional.of(target));
        when(userRepo.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("taşınan üyelik TEAM_MOVE (#kaynak); kaynak takımın izi silinir; hedefe zaten üye olanın izi korunur")
    void moveAll_recordsTeamMoveSource() {
        AppUser moved = user(1L, 7L, 7L);
        AppUser alreadyInTarget = user(2L, 9L, 7L, 9L);
        sources.put(1L, 7L, UserTeamSource.LDAP_GROUP, "Eski");
        sources.put(2L, 9L, UserTeamSource.LDAP_GROUP, "Hedef Grup");
        when(userRepo.findAll()).thenReturn(List.of(moved, alreadyInTarget));

        service.moveAll(7L, 9L);

        assertThat(sources.get(1L, 7L)).isNull();
        assertThat(sources.get(1L, 9L).getSource()).isEqualTo(UserTeamSource.TEAM_MOVE);
        assertThat(sources.get(1L, 9L).getDetail()).isEqualTo("#7");
        assertThat(sources.get(2L, 9L).getSource()).isEqualTo(UserTeamSource.LDAP_GROUP);
    }
}
