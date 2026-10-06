package com.sitemonitor.service;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.ManualCertificateVersion;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.ManualCertificateVersionRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Elle yüklenen sertifika (2026-10-06) — {@link CertificateService} dalları YALNIZ manuel işaretiyle açılır:
 * dağıtım durumu UNKNOWN, TOFU pini güven kapısı sormadan yüklenen sürümü izler, pano satırı kaynak + sürüm taşır
 * (tek toplu sorgu; manuel satır yoksa sorgu YOK). Ağ satırları bugünküyle aynı.
 */
class CertificateServiceManualSourceTest {

    private CertificateCheckRepository checkRepo;
    private LatestCheckRepository latestRepo;
    private CertificateInventoryRepository inventoryRepo;
    private ManualCertificateVersionRepository versionRepo;
    private CertificateService service;

    @BeforeEach
    void setUp() {
        checkRepo = mock(CertificateCheckRepository.class);
        latestRepo = mock(LatestCheckRepository.class);
        inventoryRepo = mock(CertificateInventoryRepository.class);
        versionRepo = mock(ManualCertificateVersionRepository.class);
        AlertThresholdRepository thresholds = mock(AlertThresholdRepository.class);
        when(thresholds.findFirstByActiveTrue()).thenReturn(Optional.empty());
        CertificateCheckerService checker = mock(CertificateCheckerService.class);
        when(checker.serializeSan(any())).thenReturn("[]");
        service = new CertificateService(checkRepo, latestRepo, checker, mock(MaintenanceService.class), inventoryRepo,
                new ObjectMapper(), mock(TeamRepository.class), thresholds, mock(ActivityLogService.class));
        ReflectionTestUtils.setField(service, "self", service);
        ReflectionTestUtils.setField(service, "manualVersionRepo", versionRepo);
        when(latestRepo.findById(any())).thenReturn(Optional.empty());
    }

    private static Map<String, Object> result(String domain, String fp, boolean manual) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("status", "valid");
        m.put("warning", false);
        m.put("days_remaining", 90);
        m.put("san", List.of("api.example.test"));
        m.put("fingerprint", fp);
        m.put("trust_status", "UNTRUSTED");
        m.put("checked_at", "2026-10-06T08:00:00");
        if (manual) {
            m.put("manual", true);
            m.put("via", "upload");
        }
        return m;
    }

    private LatestCheck savedLatest() {
        ArgumentCaptor<LatestCheck> cap = ArgumentCaptor.forClass(LatestCheck.class);
        verify(latestRepo).save(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("manuel sonuç: dağıtım durumu UNKNOWN (beklenen parmak izi sorulmaz → MISMATCH yok)")
    void manual_deploymentUnknown() {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("api-takip");
        inv.setExpectedFingerprint("BAMBASKA");
        when(inventoryRepo.findByDomain("api-takip")).thenReturn(Optional.of(inv));
        Map<String, Object> r = result("api-takip", "AB12", true);
        service.saveResult(r);
        assertThat(r.get("deployment_status")).isEqualTo("UNKNOWN");
        assertThat(savedLatest().getDeploymentStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("ağ sonucu: AYNI veride dağıtım hükmü bugünkü gibi INCOMPLETE")
    void network_deploymentUnchanged() {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("net.example.test");
        inv.setExpectedFingerprint("BAMBASKA");
        when(inventoryRepo.findByDomain("net.example.test")).thenReturn(Optional.of(inv));
        Map<String, Object> r = result("net.example.test", "AB12", false);
        service.saveResult(r);
        assertThat(r.get("deployment_status")).isEqualTo("INCOMPLETE");
    }

    @Test
    @DisplayName("TOFU: manuel sürüm, ad/güven kapısına takılmadan sabitlenir; ağ sonucunda kapı aynen işler")
    void autoPin_manualSkipsGate() {
        LatestCheck manual = new LatestCheck();
        manual.setDomain("api-takip");
        manual.setTrustStatus("UNTRUSTED");
        manual.setPinnedFingerprint("ESKI");
        CertificateService.applyAutoPin(manual, "YENI", "2026-10-06T08:00:00", List.of("api.example.test"), true);
        assertThat(manual.getPinnedFingerprint()).isEqualTo("YENI");
        assertThat(manual.getPreviousFingerprint()).isEqualTo("ESKI");

        LatestCheck network = new LatestCheck();
        network.setDomain("net.example.test");
        network.setTrustStatus("UNTRUSTED");
        network.setPinnedFingerprint("ESKI");
        CertificateService.applyAutoPin(network, "YENI", "2026-10-06T08:00:00", List.of("api.example.test"));
        assertThat(network.getPinnedFingerprint()).isEqualTo("ESKI");   // kapı korur
    }

    @Test
    @DisplayName("pano: manuel satır cert_source + sürüm taşır (TEK toplu sorgu); ağ satırı alan taşımaz")
    void getAllLatest_manualFields() {
        CertificateInventory net = new CertificateInventory();
        net.setId(1L);
        net.setDomain("net.example.test");
        net.setActive(true);
        CertificateInventory man = new CertificateInventory();
        man.setId(2L);
        man.setDomain("api-takip");
        man.setActive(true);
        man.setCertSource(CertificateInventory.SOURCE_MANUAL);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(net, man));
        LatestCheck l1 = new LatestCheck();
        l1.setDomain("net.example.test");
        l1.setVia("direct");
        LatestCheck l2 = new LatestCheck();
        l2.setDomain("api-takip");
        l2.setVia("upload");
        l2.setSan("[\"api.example.test\"]");
        when(latestRepo.findByDomainIn(anyCollection())).thenReturn(List.of(l1, l2));
        ManualCertificateVersion v = new ManualCertificateVersion();
        v.setInventoryId(2L);
        v.setVersion(2);
        v.setUploadedAt("2026-10-05T10:00:00");
        when(versionRepo.findByInventoryIdInAndCurrentTrue(anyCollection())).thenReturn(List.of(v));

        List<CertificateDto> rows = service.getAllLatest();
        CertificateDto manualDto = rows.stream().filter(d -> d.getDomain().equals("api-takip")).findFirst().orElseThrow();
        CertificateDto netDto = rows.stream().filter(d -> d.getDomain().equals("net.example.test")).findFirst().orElseThrow();
        assertThat(manualDto.getCertSource()).isEqualTo("MANUAL");
        assertThat(manualDto.getManualVersion()).isEqualTo(2);
        assertThat(manualDto.getManualUploadedAt()).isEqualTo("2026-10-05T10:00:00");
        assertThat(netDto.getCertSource()).isNull();
        assertThat(netDto.getManualVersion()).isNull();
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<java.util.Collection<Long>> ids = (ArgumentCaptor) ArgumentCaptor.forClass(java.util.Collection.class);
        verify(versionRepo, times(1)).findByInventoryIdInAndCurrentTrue(ids.capture());
        assertThat(ids.getValue()).containsExactly(2L);
    }

    @Test
    @DisplayName("pano: manuel satır yoksa sürüm deposu HİÇ sorgulanmaz")
    void getAllLatest_noManualRows_noQuery() {
        CertificateInventory net = new CertificateInventory();
        net.setId(1L);
        net.setDomain("net.example.test");
        net.setActive(true);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(net));
        when(latestRepo.findByDomainIn(anyCollection())).thenReturn(List.of());
        service.getAllLatest();
        verify(versionRepo, never()).findByInventoryIdInAndCurrentTrue(anyCollection());
    }

    @Test
    @DisplayName("DTO: via=upload satırında 'Güvensiz' rozeti ad kapsamasından ÜRETİLMEZ; ağ satırında üretilir")
    void dto_securityFlags_manualSkipsHostname() {
        LatestCheck lc = new LatestCheck();
        lc.setDomain("api-takip");
        lc.setTrustStatus("TRUSTED");
        lc.setVia("upload");
        CertificateDto manual = CertificateDto.from(lc, List.of("api.example.test"), List.of(), List.of());
        assertThat(manual.getSecurityFlags()).isEmpty();
        assertThat(manual.getSecure()).isTrue();
        lc.setVia("direct");
        CertificateDto network = CertificateDto.from(lc, List.of("api.example.test"), List.of(), List.of());
        assertThat(network.getSecurityFlags()).containsExactly(CertificateHealthRules.FLAG_HOSTNAME_MISMATCH);
    }
}
