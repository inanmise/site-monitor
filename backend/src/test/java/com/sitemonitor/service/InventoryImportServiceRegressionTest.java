package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression: ISSUE-004 — içe aktarma önizlemesi yeni satırda yazılacak takımı (ve varsayılan-dışı portu) listelemiyordu.
 * Found by /qa on 2026-09-12
 * Report: .gstack/qa-reports/qa-report-localhost-2026-09-12-r2.md
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryImportServiceRegressionTest {

    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;
    @Mock MonitorHistoryService monitorHistory;
    @Mock MonitoringGroupService monitoringGroupService;
    @Mock SchedulerService schedulerService;
    @InjectMocks InventoryImportService service;

    private static Team team(long id, String name) { Team t = new Team(); t.setId(id); t.setName(name); return t; }
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @BeforeEach
    void setUp() {
        when(teamRepo.findAll()).thenReturn(List.of(team(5L, "Takım A")));
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("yeni satır: değişen alanlar listesi domain, team ve (443 dışıysa) port ile başlar")
    void newRowListsTeamAndNonDefaultPort() {
        var plan = service.plan(List.of(row("domain", "new.example.com", "team", "Takım A", "tier", "2", "port", "8443")), t -> true, "admin", null);
        assertThat(plan.rows().get(0).action()).isEqualTo("create");
        assertThat(plan.rows().get(0).changes()).startsWith("domain", "team", "port").contains("tier");
    }

    @Test
    @DisplayName("yeni satır varsayılan portla: port listelenmez, team listelenir")
    void newRowDefaultPortOmitsPort() {
        var plan = service.plan(List.of(row("domain", "new.example.com", "team", "5", "tier", "1")), t -> true, "admin", null);
        assertThat(plan.rows().get(0).changes()).containsExactly("domain", "team", "tier");
    }

    @Test
    @DisplayName("var olan satır: takım değişmediyse 'team' listelenmez (davranış korunur)")
    void existingRowUnchangedTeamNotListed() {
        CertificateInventory ex = new CertificateInventory(); ex.setId(1L); ex.setDomain("old.example.com"); ex.setTeamId(5L); ex.setPort(443); ex.setTier(1);
        when(inventoryRepo.findByDomain("old.example.com")).thenReturn(Optional.of(ex));
        var plan = service.plan(List.of(row("domain", "old.example.com", "team", "Takım A", "tier", "2")), t -> true, "admin", null);
        assertThat(plan.rows().get(0).action()).isEqualTo("update");
        assertThat(plan.rows().get(0).changes()).containsExactly("tier");
    }
}
