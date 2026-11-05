package com.sitemonitor.service;

import com.sitemonitor.model.CertificateCheck;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Sertifika kontrol satırının aktivite bağı (2026-11-05, aktivite zenginleştirmesi A): {@code monitor_id} = ENVANTER kimliği
 * (TLS_GRADE_DROPPED ile tutarlı; eskiden null), {@code check_id} = yazılan certificate_checks satırı, neden kodu ön yüz
 * {@code certCode} aynası; "Şimdi kontrol et" ve "Sağlığı yenile" MANUAL_CHECK, saatlik tur SCHEDULED.
 */
class CertificateServiceActivityTest {

    private CertificateCheckRepository checkRepo;
    private CertificateInventoryRepository inventoryRepo;
    private ActivityLogService activity;
    private CertificateService service;

    @BeforeEach
    void setUp() {
        checkRepo = mock(CertificateCheckRepository.class);
        LatestCheckRepository latestRepo = mock(LatestCheckRepository.class);
        inventoryRepo = mock(CertificateInventoryRepository.class);
        activity = mock(ActivityLogService.class);
        AlertThresholdRepository thresholds = mock(AlertThresholdRepository.class);
        when(thresholds.findFirstByActiveTrue()).thenReturn(Optional.empty());
        CertificateCheckerService checker = mock(CertificateCheckerService.class);
        when(checker.serializeSan(any())).thenReturn("[]");
        service = new CertificateService(checkRepo, latestRepo, checker, mock(MaintenanceService.class), inventoryRepo,
                new ObjectMapper(), mock(TeamRepository.class), thresholds, activity);
        ReflectionTestUtils.setField(service, "self", service);
        when(latestRepo.findById(any())).thenReturn(Optional.empty());
        when(checkRepo.save(any(CertificateCheck.class))).thenAnswer(a -> {
            CertificateCheck c = a.getArgument(0);
            c.setId(4242L);
            return c;
        });
        CertificateInventory inv = new CertificateInventory();
        inv.setId(17L);
        inv.setDomain("x.example.com");
        inv.setTeamId(5L);
        when(inventoryRepo.findByDomain("x.example.com")).thenReturn(Optional.of(inv));
    }

    private static Map<String, Object> result(String runId, boolean failed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", "x.example.com");
        m.put("status", failed ? "error" : "valid");
        m.put("san", List.of());
        m.put("checked_at", "2026-11-05T10:00:00");
        m.put("run_id", runId);
        if (failed) {
            m.put("error", "Connection refused");
            m.put("error_class", "NETWORK");
            m.put("error_stage", "tcp-connect");
        }
        return m;
    }

    @Test
    @DisplayName("Saatlik tur: SCHEDULED, monitor_id = envanter kimliği, check_id = yazılan geçmiş satırı, neden kodu certCode aynası")
    void scheduled_linksInventoryAndCheckRow() {
        service.saveResult(result("run-1", true));
        ArgumentCaptor<ActivityLogService.Refs> refs = ArgumentCaptor.forClass(ActivityLogService.Refs.class);
        verify(activity).recordCheck(eq(ActivityLogService.CERT), eq(17L), eq("x.example.com"), eq("x.example.com"), eq(5L),
                eq(false), eq("scheduler"), any(), refs.capture());
        assertThat(refs.getValue().checkId()).isEqualTo(4242L);
        assertThat(refs.getValue().failureReason()).isEqualTo("CONNECT_REFUSED");
    }

    @Test
    @DisplayName("'Şimdi kontrol et' ve 'Sağlığı yenile' elle (aktör çözümü ActivityLogService'te); başarılı satırda neden yok")
    void manualRuns() {
        service.saveResult(result("manual", false));
        service.saveResult(result("health-refresh", false));
        ArgumentCaptor<ActivityLogService.Refs> refs = ArgumentCaptor.forClass(ActivityLogService.Refs.class);
        verify(activity, times(2)).recordCheck(eq(ActivityLogService.CERT), eq(17L), any(), any(), eq(5L),
                eq(true), isNull(), any(), refs.capture());
        assertThat(refs.getAllValues()).extracting(ActivityLogService.Refs::failureReason).containsOnlyNulls();
    }

    @Test
    @DisplayName("Geçmiş satırı yazılamazsa aktivite satırı yine yazılır (bağsız)")
    void historyWriteFails_activityStillWritten() {
        when(checkRepo.save(any(CertificateCheck.class))).thenThrow(new RuntimeException("db"));
        service.saveResult(result("run-1", false));
        verify(activity).recordCheck(eq(ActivityLogService.CERT), eq(17L), any(), any(), eq(5L), eq(false), eq("scheduler"),
                any(), isNull());
    }
}
