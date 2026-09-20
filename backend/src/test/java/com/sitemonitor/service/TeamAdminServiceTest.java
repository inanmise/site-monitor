package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.Team;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Takım sayaçları / etki önizleme / taşıma (2026-09-20). Envanter-türevi DNS/Port (teamId=null) sayıma girmez;
 * taşıma üyelikleri yeniden yazar, varsayılan grup hedefte ikinci varsayılan üretmez.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamAdminServiceTest {

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

    private static Team team(long id, String name) { Team t = new Team(); t.setId(id); t.setName(name); return t; }
    private static CertificateInventory inv(String d, Long team) { CertificateInventory i = new CertificateInventory(); i.setDomain(d); i.setTeamId(team); i.setActive(true); return i; }
    private static AppUser user(long id, String u, Long primary, Long... teams) {
        AppUser a = new AppUser(); a.setId(id); a.setUsername(u); a.setSystemRole("USER"); a.setTeamId(primary);
        a.setTeamIds(new LinkedHashSet<>(List.of(teams))); return a;
    }

    @BeforeEach
    void setUp() {
        when(teamRepo.findAll()).thenReturn(List.of(team(7, "A"), team(9, "B")));
        when(teamRepo.findById(9L)).thenReturn(Optional.of(team(9, "B")));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("a.example.com", 7L), inv("b.example.com", 7L), inv("c.example.com", 9L)));
        when(inventoryRepo.findByTeamIdOrderByDomainAsc(7L)).thenReturn(List.of(inv("a.example.com", 7L), inv("b.example.com", 7L)));
        HttpMonitor h = new HttpMonitor(); h.setId(1L); h.setName("site"); h.setTeamId(7L); h.setActive(true);
        PortMonitor derived = new PortMonitor(); derived.setId(2L); derived.setName("443"); derived.setTeamId(null); derived.setActive(true);
        when(httpRepo.findAll()).thenReturn(List.of(h));
        when(portRepo.findAll()).thenReturn(List.of(derived));
        when(userRepo.findAll()).thenReturn(List.of(user(1, "ali", 7L, 7L), user(2, "veli", 9L, 9L, 7L), user(3, "ayse", 9L, 9L)));
        AlertEvent open = new AlertEvent(); open.setTeamId(7L); open.setResolved(false);
        when(alertEventRepo.findAllOpenOrderBySeverity()).thenReturn(List.of(open));
        EscalationContact c = new EscalationContact(); c.setId(5L); c.setName("PO"); c.setTeamId(7L); c.setActive(true);
        when(contactRepo.findAll()).thenReturn(List.of(c));
        when(contactRepo.findByTeamIdOrderByRoleAsc(7L)).thenReturn(List.of(c));
        NotificationGroup g = new NotificationGroup(); g.setId(3L); g.setName("Ops"); g.setTeamId(7L); g.setIsDefault(true); g.setActive(true);
        when(groupRepo.findAll()).thenReturn(List.of(g));
        when(groupRepo.findByTeamIdOrderByNameAsc(7L)).thenReturn(List.of(g));
    }

    @Test
    @DisplayName("stats: tek geçişte takım başına sayaçlar; teamId=null izleme hiçbir takıma yazılmaz")
    void stats() {
        Map<Long, Map<String, Object>> s = service.stats();
        assertThat(s.get(7L)).containsEntry("members", 2).containsEntry("domains", 2).containsEntry("monitors", 1)
                .containsEntry("open_alerts", 1).containsEntry("contacts", 1).containsEntry("groups", 1);
        assertThat(s.get(9L)).containsEntry("members", 2).containsEntry("domains", 1).containsEntry("monitors", 0);
    }

    @Test
    @DisplayName("impact: bağlı varlıklar adlarıyla, empty=false; boş takımda empty=true")
    @SuppressWarnings("unchecked")
    void impact() {
        Map<String, Object> r = service.impact(7L);
        assertThat(((Map<String, Object>) r.get("domains")).get("count")).isEqualTo(2);
        assertThat((List<String>) ((Map<String, Object>) r.get("monitors")).get("items")).containsExactly("HTTP: site");
        assertThat((List<String>) ((Map<String, Object>) r.get("users")).get("items")).containsExactly("ali", "veli");
        assertThat(r.get("open_alerts")).isEqualTo(1L);
        assertThat(r.get("empty")).isEqualTo(false);
        assertThat(service.impact(99L).get("empty")).isEqualTo(true);
    }

    @Test
    @DisplayName("moveAll: envanter/izleme/üyelik/kişi/grup hedefe geçer; zaten üye olan yalnız kaynaktan düşer; varsayılan bayrağı kalkar")
    void moveAll() {
        Map<String, Integer> moved = service.moveAll(7L, 9L);
        assertThat(moved).containsEntry("domains", 2).containsEntry("monitors", 1).containsEntry("users", 2)
                .containsEntry("contacts", 1).containsEntry("groups", 1);
        verify(portRepo, never()).save(any());   // teamId=null (envanter türevi) taşınmaz
        AppUser ali = userRepo.findAll().get(0), veli = userRepo.findAll().get(1), ayse = userRepo.findAll().get(2);
        assertThat(ali.getTeamId()).isEqualTo(9L);
        assertThat(ali.getTeamIds()).containsExactly(9L);
        assertThat(ali.getTeamLocked()).isTrue();
        assertThat(veli.getTeamIds()).containsExactly(9L);   // 7 düştü, 9 zaten vardı
        assertThat(veli.getTeamId()).isEqualTo(9L);
        verify(userRepo, never()).save(ayse);                // dokunulmadı
        NotificationGroup g = groupRepo.findAll().get(0);
        assertThat(g.getTeamId()).isEqualTo(9L);
        assertThat(g.getIsDefault()).isFalse();
        assertThatThrownBy(() -> service.moveAll(7L, 7L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.moveAll(7L, 123L)).isInstanceOf(IllegalArgumentException.class);
    }
}
