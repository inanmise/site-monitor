package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.NocNotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.NocNotificationGroupRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Envanter CSV içe aktarmada 7/24 sütunları ({@code noc_notify}, {@code noc_groups} — grup ADLARI) ve GİDİŞ-DÖNÜŞ:
 * dışa aktarılan satır aynen geri yüklenince değişiklik yok ({@code skip:no_change}).
 */
class InventoryImportNocTest {

    private final CertificateInventoryRepository inventoryRepo = mock(CertificateInventoryRepository.class);
    private final TeamRepository teamRepo = mock(TeamRepository.class);
    private final NocNotificationGroupRepository groupRepo = mock(NocNotificationGroupRepository.class);
    private InventoryImportService service;
    private CertificateInventory existing;

    @BeforeEach
    void setUp() {
        service = new InventoryImportService(inventoryRepo, teamRepo, mock(MonitorHistoryService.class),
                mock(MonitoringGroupService.class), null, mock(SchedulerService.class));
        ReflectionTestUtils.setField(service, "nocGroupRepo", groupRepo);
        Team t = new Team(); t.setId(5L); t.setName("Takım A");
        when(teamRepo.findAll()).thenReturn(List.of(t));
        NocNotificationGroup a = new NocNotificationGroup(); a.setId(3L); a.setName("NOC Ana");
        NocNotificationGroup b = new NocNotificationGroup(); b.setId(7L); b.setName("NOC Gece");
        when(groupRepo.findAll()).thenReturn(List.of(a, b));
        existing = new CertificateInventory();
        existing.setId(1L); existing.setDomain("www.example.com"); existing.setTeamId(5L); existing.setPort(443);
        when(inventoryRepo.findByDomain("www.example.com")).thenReturn(Optional.of(existing));
        when(inventoryRepo.findByDomain(argThat(d -> !"www.example.com".equals(d)))).thenReturn(Optional.empty());
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("sütunlar kabul listesinde (istemci başlık eşlemesi bunu okur)")
    void columnsDeclared() {
        assertThat(InventoryImportService.COLUMNS).contains("noc_notify", "noc_groups");
    }

    @Test
    @DisplayName("noc_notify + grup adları (harf duyarsız) yazılır; aynı satır ikinci kez → no_change (gidiş-dönüş)")
    void roundTrip() {
        var first = service.commit(List.of(row("domain", "www.example.com", "noc_notify", "evet", "noc_groups", "noc gece; NOC Ana")),
                x -> true, "admin", null);
        assertThat(first.updated()).isEqualTo(1);
        assertThat(first.rows().get(0).changes()).contains("noc_notify", "noc_groups");
        assertThat(existing.getNocNotify()).isTrue();
        assertThat(existing.getNocGroupIds()).isEqualTo("7,3");

        var again = service.commit(List.of(row("domain", "www.example.com", "noc_notify", "evet", "noc_groups", "NOC Gece;NOC Ana")),
                x -> true, "admin", null);
        assertThat(again.rows().get(0).reason()).isEqualTo("no_change");
    }

    @Test
    @DisplayName("boş hücre DOKUNMAZ; '-' varsayılana döndürür; bilinmeyen grup adı satırı hata ile düşürür")
    void blankDashAndUnknown() {
        existing.setNocNotify(true);
        existing.setNocGroupIds("3");
        var blank = service.commit(List.of(row("domain", "www.example.com", "noc_notify", "", "noc_groups", "")), x -> true, "admin", null);
        assertThat(blank.rows().get(0).reason()).isEqualTo("no_change");
        assertThat(existing.getNocGroupIds()).isEqualTo("3");

        service.commit(List.of(row("domain", "www.example.com", "noc_groups", "-")), x -> true, "admin", null);
        assertThat(existing.getNocGroupIds()).isNull();

        var unknown = service.commit(List.of(row("domain", "www.example.com", "noc_groups", "Yok Böyle Grup")), x -> true, "admin", null);
        assertThat(unknown.errors()).isEqualTo(1);
        assertThat(unknown.rows().get(0).reason()).isEqualTo("unknown_noc_group");
    }
}
