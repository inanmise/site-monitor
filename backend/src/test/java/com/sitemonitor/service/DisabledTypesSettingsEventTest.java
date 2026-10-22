package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MP-44 (2026-10-23, çok pod R2a · D2): başka pod'un kaydını DB tazelemesinde gören pod ({@code source="refresh"})
 * uzlaştırmayı YİNELEMEZ; ekrandan kayıt ({@code "save"}) kuyruğa alır. İçe aktarmayla pasifleştirme koşullu kapanışı
 * kullanır (bütün varlık kaydı yapan {@code closeAlertsOnDeactivate} değil).
 */
class DisabledTypesSettingsEventTest {

    private MonitoringOutageService mos(List<Runnable> queued) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        MonitoringOutageService s = new MonitoringOutageService(mock(AlertEventRepository.class), mock(EscalationService.class),
                mock(JdbcTemplate.class), mock(DnsRecordRepository.class), mock(DnsMonitorRepository.class), settings,
                mock(NetworkOutageEventRepository.class));
        ReflectionTestUtils.setField(s, "settingsReconcileExecutor", (java.util.concurrent.Executor) queued::add);
        return s;
    }

    @Test
    @DisplayName("refresh olayı (başka pod'un kaydı) uzlaştırma KUYRUĞA ALMAZ; save olayı alır")
    void refreshEventIgnored_saveQueued() {
        List<Runnable> queued = new ArrayList<>();
        MonitoringOutageService s = mos(queued);
        s.onSettingsChanged(new AppSettingsChangedEvent(Set.of("site.monitor.scripted.alert-enabled"), "refresh"));
        s.onSettingsChanged(new AppSettingsChangedEvent(Set.of(), "refresh"));
        assertThat(queued).isEmpty();
        s.onSettingsChanged(new AppSettingsChangedEvent(Set.of("site.monitor.scripted.alert-enabled"), "save"));
        assertThat(queued).hasSize(1);
    }

    @Test
    @DisplayName("İçe aktarma pasifleştirmesi koşullu kapanışı kullanır; kapatıcı yoksa eski yol")
    void importDeactivate_usesConditionalCloser() {
        CertificateInventoryRepository inventoryRepo = mock(CertificateInventoryRepository.class);
        TeamRepository teamRepo = mock(TeamRepository.class);
        Team t = new Team();
        t.setId(5L);
        t.setName("Takım A");
        when(teamRepo.findAll()).thenReturn(List.of(t));
        CertificateInventory ex = new CertificateInventory();
        ex.setId(7L);
        ex.setDomain("off.example.com");
        ex.setTeamId(5L);
        ex.setPort(443);
        ex.setActive(true);
        when(inventoryRepo.findByDomain(anyString())).thenReturn(Optional.empty());
        when(inventoryRepo.findByDomain("off.example.com")).thenReturn(Optional.of(ex));
        when(inventoryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        InventoryImportService svc = new InventoryImportService(inventoryRepo, teamRepo, mock(MonitorHistoryService.class),
                mock(MonitoringGroupService.class), mock(PlatformService.class), mock(SchedulerService.class));
        EscalationService esc = mock(EscalationService.class);
        SilentAlarmCloser closer = mock(SilentAlarmCloser.class);
        svc.setEscalationService(esc);
        svc.setSilentAlarmCloser(closer);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("domain", "off.example.com");
        row.put("active", "hayır");
        var r = svc.commit(List.of(row), id -> true, "admin", null);

        assertThat(r.updated()).isEqualTo(1);
        verify(closer).closeOnInventoryDeactivate("off.example.com");
        verify(esc, never()).closeAlertsOnDeactivate(any());
    }
}
