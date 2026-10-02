package com.sitemonitor.service;

import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.StatusPageService.Viewer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Durum Sayfası — Sistem Bakım Modu notu (2026-10-02): EK {@code system_maintenance} alanı ("Planlı bakım: 22:00–23:00");
 * bellekli (paylaşılan) izdüşüme dokunulmaz; bakım servisi yokken yanıt bugünküyle birebir.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatusPageSystemMaintenanceNoteTest {

    @Mock MonitoringOverviewService overview;
    @Mock MaintenanceWindowRepository maintenanceRepo;
    @Mock IncidentRecordRepository incidentRepo;
    @Mock TeamRepository teamRepo;
    @Mock JdbcTemplate jdbc;
    @Mock SystemMaintenanceService maintenance;

    StatusPageService svc;

    @BeforeEach
    void setUp() {
        svc = new StatusPageService(overview, new MaintenanceService(maintenanceRepo), maintenanceRepo, incidentRepo, teamRepo, jdbc);
        when(teamRepo.findAll()).thenReturn(List.of());
        when(maintenanceRepo.findByActiveTrue()).thenReturn(List.of());
        when(incidentRepo.statusPageActive(any(Pageable.class))).thenReturn(List.of());
        when(incidentRepo.statusPageResolvedSince(anyString(), any(Pageable.class))).thenReturn(List.of());
        when(incidentRepo.statusPageActiveCounts()).thenReturn(List.of());
        when(incidentRepo.statusPageResolvedSinceCounts(anyString())).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(overview.build(anyString(), any(), anyBoolean(), anyInt(), anyBoolean())).thenAnswer(inv -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("generated_at", "2026-10-01T09:00:00");
            out.put("monitors", List.of());
            return out;
        });
    }

    @Test
    @DisplayName("bakım servisi yok → system_maintenance alanı YOK (birebir)")
    void noService_unchanged() {
        assertThat(svc.view("ALL", Viewer.all(), false)).doesNotContainKey("system_maintenance");
    }

    @Test
    @DisplayName("planlı bakım duyurusu → EK alan; paylaşılan bellek kaydı DEĞİŞMEZ")
    void note_added_memoUntouched() {
        svc.setSystemMaintenance(maintenance);
        when(maintenance.publicStatus()).thenReturn(Map.of("state", "announced", "start_at", "2026-10-02T19:00:00Z",
                "end_at", "2026-10-02T20:00:00Z"));
        Map<String, Object> v = svc.view("ALL", Viewer.all(), false);
        assertThat(v).containsKey("system_maintenance");
        @SuppressWarnings("unchecked")
        Map<String, Object> note = (Map<String, Object>) v.get("system_maintenance");
        assertThat(note).containsEntry("state", "announced");
        // ikinci çağrı aynı bellek kaydından yeni kopya kurar — bellekteki harita alanı taşımaz
        Map<String, Object> again = svc.view("ALL", Viewer.all(), false);
        assertThat(again).isNotSameAs(v).containsKey("system_maintenance");
    }

    @Test
    @DisplayName("bakım tamamlandı (2026-10-02): public bloğun 'ended' durumu nota aynen taşınır (gerçek bitiş + plan bitişi)")
    void endedNote_passedThrough() {
        svc.setSystemMaintenance(maintenance);
        when(maintenance.publicStatus()).thenReturn(Map.of("state", "ended", "start_at", "2026-10-02T19:00:00Z",
                "end_at", "2026-10-02T19:40:00Z", "planned_end_at", "2026-10-02T20:00:00Z"));
        @SuppressWarnings("unchecked")
        Map<String, Object> note = (Map<String, Object>) svc.view("ALL", Viewer.all(), false).get("system_maintenance");
        assertThat(note).containsEntry("state", "ended").containsEntry("end_at", "2026-10-02T19:40:00Z")
                .containsEntry("planned_end_at", "2026-10-02T20:00:00Z").doesNotContainKey("id");
    }
}
