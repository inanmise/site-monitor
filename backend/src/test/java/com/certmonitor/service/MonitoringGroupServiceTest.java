package com.certmonitor.service;

import com.certmonitor.model.MonitoringGroup;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AlertEventRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.DnsMonitorRepository;
import com.certmonitor.repository.DomainMonitorRepository;
import com.certmonitor.repository.HttpMonitorRepository;
import com.certmonitor.repository.KeywordMonitorRepository;
import com.certmonitor.repository.MonitoringGroupRepository;
import com.certmonitor.repository.PingMonitorRepository;
import com.certmonitor.repository.PortMonitorRepository;
import com.certmonitor.repository.TeamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MonitoringGroupService — TAKIM + TÜR bazlı get-or-create, tek-tür rename cascade, 403/409, kapsam sızıntısızlığı. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonitoringGroupServiceTest {

    @Mock MonitoringGroupRepository groupRepo;
    @Mock CertificateInventoryRepository certRepo;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock DomainMonitorRepository domainRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock TeamRepository teamRepo;

    @InjectMocks MonitoringGroupService service;

    private static MonitoringGroup grp(long id, long team, String type, String name) {
        MonitoringGroup g = new MonitoringGroup();
        g.setId(id); g.setTeamId(team); g.setType(type); g.setName(name); g.setNameLower(name.toLowerCase());
        return g;
    }
    private static Team team(long id, String name) { Team t = new Team(); t.setId(id); t.setName(name); return t; }
    private static MockHttpSession adminSession() { var s = new MockHttpSession(); s.setAttribute("systemRole", "ADMIN"); return s; }
    private static MockHttpSession otherTeamUser() {
        var s = new MockHttpSession();
        s.setAttribute("systemRole", "USER"); s.setAttribute("teamId", 2L);
        s.setAttribute("viewTeamIds", new java.util.ArrayList<>(List.of(2L)));
        s.setAttribute("manageTeamIds", new java.util.ArrayList<Long>());
        return s;
    }

    @Test
    void getOrCreate_createsCanonical_trimmed() {
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "dns", "deneme")).thenReturn(Optional.empty());
        when(groupRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(service.getOrCreate(1L, "dns", "  Deneme ", "u")).isEqualTo("Deneme");
    }

    @Test
    void getOrCreate_caseInsensitiveDedup_returnsExistingCanonical() {
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "dns", "deneme")).thenReturn(Optional.of(grp(7, 1, "dns", "Deneme")));
        assertThat(service.getOrCreate(1L, "dns", "DENEME", "u")).isEqualTo("Deneme");
        verify(groupRepo, never()).save(any());
    }

    @Test
    void rename_cascadesOnlyToThatTypeAndTypeScopedAlertHistory() {
        when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 1, "dns", "old")));
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "dns", "new")).thenReturn(Optional.empty());
        when(dnsRepo.renameGroupForTeam(1L, "old", "new")).thenReturn(4);

        int affected = service.rename(5L, "new", adminSession());
        assertThat(affected).isEqualTo(4);
        verify(dnsRepo).renameGroupForTeam(1L, "old", "new");
        verify(httpRepo, never()).renameGroupForTeam(anyLong(), anyString(), anyString());   // YALNIZ o tür
        verify(alertEventRepo).renameGroupForTeamAndTypes(eq(1L), eq("old"), eq("new"), any());
    }

    @Test
    void rename_nameConflictInSameTeamAndType_throws409() {
        when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 1, "dns", "old")));
        when(groupRepo.findByTeamIdAndTypeAndNameLower(1L, "dns", "taken")).thenReturn(Optional.of(grp(9, 1, "dns", "taken")));
        assertThatThrownBy(() -> service.rename(5L, "taken", adminSession())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rename_otherTeamUser_throws403() {
        when(groupRepo.findById(5L)).thenReturn(Optional.of(grp(5, 1, "dns", "old")));   // takım 1
        assertThatThrownBy(() -> service.rename(5L, "new", otherTeamUser())).isInstanceOf(SecurityException.class);
    }

    @Test
    void listForScope_user_seesOnlyOwnTeams_noLeak() {
        when(groupRepo.findByTeamIdInOrderByTeamIdAscTypeAscNameAsc(List.of(2L))).thenReturn(List.of(grp(9, 2, "dns", "x")));
        when(teamRepo.findAll()).thenReturn(List.of(team(2, "T2")));
        var out = service.listForScope(List.of(2L), null, null);
        assertThat(out).extracting(MonitoringGroupService.GroupInfo::teamId).containsExactly(2L);
        verify(groupRepo, never()).findAllByOrderByTeamIdAscTypeAscNameAsc();   // admin-only sorgu çağrılmaz
    }
}
