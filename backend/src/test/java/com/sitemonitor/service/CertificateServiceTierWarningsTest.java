package com.sitemonitor.service;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Uyarılar listesi tier eşiğiyle genişler (H paketi 2026-11-26, sertifika B1): global {@code warning} bayrağı kalkık olmayan
 * ama kendi tier uyarı eşiğinin içindeki satır listeye girer; tier eşiği global eşiği aşmıyorsa ek sorgu YOK.
 */
class CertificateServiceTierWarningsTest {

    private final LatestCheckRepository latestRepo = mock(LatestCheckRepository.class);
    private final CertificateInventoryRepository inventoryRepo = mock(CertificateInventoryRepository.class);
    private final AlertThresholdRepository thresholdRepo = mock(AlertThresholdRepository.class);
    private final CertificateCheckerService checker = mock(CertificateCheckerService.class);
    private CertificateService service;

    @BeforeEach
    void setUp() {
        service = new CertificateService(mock(CertificateCheckRepository.class), latestRepo, checker,
                mock(MaintenanceService.class), inventoryRepo, new ObjectMapper(), mock(TeamRepository.class), thresholdRepo,
                mock(ActivityLogService.class));
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "warningDays", 30);
        when(checker.deserializeSan(any())).thenReturn(List.of());
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.of(threshold(null, 30)));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("t1.example.com", 1),
                inv("plain.example.com", null), inv("global.example.com", null)));
        when(latestRepo.findByWarningTrueOrStatus("error")).thenReturn(List.of(latest("global.example.com", 20, true)));
        when(latestRepo.findTierWarningCandidates(anyInt())).thenReturn(List.of(
                latest("t1.example.com", 40, false), latest("plain.example.com", 40, false),
                latest("gone.example.com", 40, false)));
    }

    private static AlertThreshold threshold(Integer tier, int warn) {
        AlertThreshold t = new AlertThreshold();
        t.setTier(tier);
        t.setWarningDays(warn);
        t.setHighDays(Math.min(warn, 45));
        t.setCriticalDays(7);
        t.setActive(true);
        return t;
    }

    private static CertificateInventory inv(String domain, Integer tier) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain);
        i.setTier(tier);
        i.setActive(true);
        return i;
    }

    private static LatestCheck latest(String domain, int days, boolean warning) {
        LatestCheck l = new LatestCheck();
        l.setDomain(domain);
        l.setDaysRemaining(days);
        l.setWarning(warning);
        l.setStatus("valid");
        return l;
    }

    @Test
    @DisplayName("T1 uyarı 60: 40 günlük T1 satırı listeye girer; tier'sız ve envanter dışı satır girmez; sorgu en geniş eşikle")
    void tierCandidatesJoinTheList() {
        when(thresholdRepo.findByActiveTrueAndTierIsNotNullOrderByIdAsc()).thenReturn(List.of(threshold(1, 60)));
        List<CertificateDto> warnings = service.getWarnings();
        assertThat(warnings).extracting(CertificateDto::getDomain).containsExactly("global.example.com", "t1.example.com");
        verify(latestRepo).findTierWarningCandidates(60);
    }

    @Test
    @DisplayName("hiçbir tier eşiği global eşiği aşmıyorsa tier sorgusu YOK — liste bugünküyle aynı")
    void noTierAboveGlobal_noExtraQuery() {
        when(thresholdRepo.findByActiveTrueAndTierIsNotNullOrderByIdAsc()).thenReturn(List.of(threshold(1, 20)));
        assertThat(service.getWarnings()).extracting(CertificateDto::getDomain).containsExactly("global.example.com");
        verify(latestRepo, never()).findTierWarningCandidates(anyInt());
    }

    @Test
    @DisplayName("tier adayları okunamazsa liste yalnız global eşikle döner (uyarı sayfası düşmez)")
    void tierQueryFailureFallsBack() {
        when(thresholdRepo.findByActiveTrueAndTierIsNotNullOrderByIdAsc()).thenReturn(List.of(threshold(1, 60)));
        when(latestRepo.findTierWarningCandidates(anyInt())).thenThrow(new RuntimeException("db"));
        assertThat(service.getWarnings()).extracting(CertificateDto::getDomain).containsExactly("global.example.com");
    }
}
