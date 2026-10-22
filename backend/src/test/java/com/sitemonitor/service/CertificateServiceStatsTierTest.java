package com.sitemonitor.service;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * UIM-18 / K12 (2026-10-22): {@code /stats} sayaçları satırın GÖSTERDİĞİ seviyeyi (alert_level, tier eşikleri) izler.
 * Eskiden aday kümesi saklı {@code warning} bayrağıydı (global 30 gün): T1 eşikleri uyarı 60 / yüksek 45 iken 40 günlük
 * T1 sertifikası kartta "Yüksek" görünüp sayaçta "Geçerli" sayılıyordu.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateServiceStatsTierTest {

    @Mock CertificateCheckRepository checkRepo;
    @Mock LatestCheckRepository latestRepo;
    @Mock CertificateCheckerService checkerService;
    @Mock MaintenanceService maintenanceService;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;
    @Mock AlertThresholdRepository alertThresholdRepo;
    @Mock ActivityLogService activityLog;

    private CertificateService service;
    private List<LatestCheck> latest;
    private List<CertificateInventory> inventory;

    @BeforeEach
    void setUp() {
        service = new CertificateService(checkRepo, latestRepo, checkerService, maintenanceService,
                inventoryRepo, new ObjectMapper(), teamRepo, alertThresholdRepo, activityLog);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        when(checkerService.deserializeSan(any())).thenReturn(Collections.emptyList());
        when(alertThresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());   // varsayılan 30/15/7
        AlertThreshold t1 = new AlertThreshold();
        t1.setTier(1);
        t1.setActive(true);
        t1.setWarningDays(60);
        t1.setHighDays(45);
        t1.setCriticalDays(10);
        when(alertThresholdRepo.findByActiveTrueAndTierIsNotNullOrderByIdAsc()).thenReturn(List.of(t1));

        latest = List.of(
                lc("t1-high.example.com", 40, false),      // T1: 10 < 40 ≤ 45 → yüksek (eskiden "geçerli")
                lc("t1-warn.example.com", 50, false),      // T1: 45 < 50 ≤ 60 → uyarı (eskiden "geçerli")
                lc("t1-ok.example.com", 90, false),        // T1: geçerli
                lc("plain.example.com", 40, false),        // tier yok: 30 < 40 → geçerli
                lc("plain-warn.example.com", 20, true));   // tier yok: 15 < 20 ≤ 30 → uyarı
        inventory = List.of(inv("t1-high.example.com", 1), inv("t1-warn.example.com", 1), inv("t1-ok.example.com", 1),
                inv("plain.example.com", null), inv("plain-warn.example.com", null));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(inventory);
        when(latestRepo.findByDomainIn(anyCollection())).thenReturn(latest);
        when(latestRepo.findByWarningTrueOrStatus("error")).thenReturn(
                latest.stream().filter(c -> Boolean.TRUE.equals(c.getWarning())).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("T1 uyarı 60 / yüksek 45: 40 gün → high_count 1, 50 gün → warning_count; valid_count yalnız gerçekten geçerliler")
    @SuppressWarnings("unchecked")
    void statsFollowTierAlertLevel() {
        Map<String, Object> stats = service.getStats();

        assertThat(stats.get("total_certificates")).isEqualTo(5);
        assertThat(stats.get("high_count")).isEqualTo(1L);
        assertThat((List<String>) stats.get("high_domains")).containsExactly("t1-high.example.com");
        assertThat(stats.get("warning_count")).isEqualTo(2L);
        assertThat((List<String>) stats.get("warning_domains"))
                .containsExactlyInAnyOrder("t1-warn.example.com", "plain-warn.example.com");
        assertThat(stats.get("valid_count")).isEqualTo(2L);
        assertThat((List<String>) stats.get("valid_domains"))
                .containsExactlyInAnyOrder("t1-ok.example.com", "plain.example.com");
        assertThat(stats.get("critical_count")).isEqualTo(0L);
        // kova toplamı = toplam (her satır tek kovada)
        long sum = (Long) stats.get("valid_count") + (Long) stats.get("warning_count") + (Long) stats.get("high_count")
                + (Long) stats.get("critical_count") + (Long) stats.get("expired") + (Long) stats.get("error_count");
        assertThat(sum).isEqualTo(5L);
    }

    @Test
    @DisplayName("Kart seviyesiyle aynı: getAllLatest alert_level ile sayaç kovası örtüşür")
    void cardLevelMatchesBucket() {
        Map<String, String> levels = service.getAllLatest().stream()
                .collect(Collectors.toMap(d -> d.getDomain(), d -> d.getAlertLevel()));
        assertThat(levels).containsEntry("t1-high.example.com", "high")
                .containsEntry("t1-warn.example.com", "warning")
                .containsEntry("plain.example.com", "valid");
    }

    private static LatestCheck lc(String domain, int days, boolean warningFlag) {
        LatestCheck c = new LatestCheck();
        c.setDomain(domain);
        c.setStatus(warningFlag ? "warning" : "valid");
        c.setWarning(warningFlag);
        c.setDaysRemaining(days);
        c.setChainStatus("VALID");
        c.setRevocationStatus("VALID");
        c.setDeploymentStatus("OK");
        c.setNotAfter("2027-01-01T00:00:00");
        return c;
    }

    private static CertificateInventory inv(String domain, Integer tier) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain);
        i.setActive(true);
        i.setTier(tier);
        return i;
    }
}
