package com.sitemonitor.service;

import com.sitemonitor.dto.CertListQuery;
import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.TlsGradeChangeRepository;
import com.sitemonitor.repository.TlsGradeStatusRepository;
import com.sitemonitor.repository.TlsProfileRepository;
import com.sitemonitor.service.tlsgrade.TlsGradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TLS notunun liste yolu (2026-10-10): {@code getAllLatest} notu TEK toplu profil okumasıyla yazar (satır başına sorgu
 * yok), Tüm Sertifikalar tablosu not süzgeci / facet / sıralama sunar, notsuz satırda alanlar yanıta hiç girmez.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateServiceTlsGradeTest {

    @Mock CertificateCheckRepository checkRepo;
    @Mock LatestCheckRepository latestRepo;
    @Mock CertificateCheckerService checkerService;
    @Mock MaintenanceService maintenanceService;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;
    @Mock AlertThresholdRepository alertThresholdRepo;
    @Mock ActivityLogService activityLog;
    @Mock TlsProfileRepository profileRepo;
    @Mock TlsGradeStatusRepository statusRepo;
    @Mock TlsGradeChangeRepository changeRepo;

    private CertificateService service;
    private final List<CertificateInventory> inventory = new ArrayList<>();
    private final List<LatestCheck> latest = new ArrayList<>();
    private final List<TlsProfile> profiles = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new CertificateService(checkRepo, latestRepo, checkerService, maintenanceService,
                inventoryRepo, new ObjectMapper(), teamRepo, alertThresholdRepo, activityLog);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "tlsGradeService",
                new TlsGradeService(profileRepo, statusRepo, changeRepo));
        when(checkerService.deserializeSan(any())).thenAnswer(i -> {
            String json = i.getArgument(0);
            return json == null ? Collections.emptyList() : List.of(json.replaceAll("[\\[\\]\"]", ""));
        });
        when(alertThresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenAnswer(i -> inventory);
        when(latestRepo.findByDomainIn(anyCollection())).thenAnswer(i -> latest);
        when(teamRepo.findAllById(any())).thenReturn(List.of());
        when(profileRepo.findByDomainIn(anyCollection())).thenAnswer(i -> profiles);
        when(statusRepo.findAllById(any())).thenReturn(List.of());

        add("aplus.example.com", p -> { }, false);
        add("tls10.example.com", p -> p.setTls10(TlsProfile.YES), false);
        add("expired.example.com", p -> { }, false);
        latest.get(2).setDaysRemaining(-1);
        add("pending.example.com", null, false);
        add("upload-key", p -> { }, true);
    }

    private void add(String domain, java.util.function.Consumer<TlsProfile> profile, boolean manual) {
        CertificateInventory inv = new CertificateInventory();
        inv.setId((long) (inventory.size() + 1));
        inv.setDomain(domain);
        inv.setActive(true);
        inv.setTeamId(1L);
        if (manual) inv.setCertSource(CertificateInventory.SOURCE_MANUAL);
        inventory.add(inv);
        LatestCheck c = new LatestCheck();
        c.setDomain(domain);
        c.setStatus("valid");
        c.setDaysRemaining(100);
        c.setSan("[\"" + domain + "\"]");
        c.setTrustStatus("TRUSTED");
        c.setChainStatus("VALID");
        c.setRevocationStatus("VALID");
        c.setPublicKeyAlgorithm("EC");
        c.setPublicKeySize(256);
        c.setSignatureAlgorithm("SHA256withECDSA");
        c.setTlsVersion("TLSv1.2");
        c.setCipherSuite("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
        c.setHstsStatus("ENABLED");
        c.setHstsPolicy("{\"max_age\":31536000}");
        c.setCheckedAt("2026-10-10T08:00:00");
        latest.add(c);
        if (profile != null) {
            TlsProfile p = new TlsProfile();
            p.setDomain(domain);
            p.setStatus(TlsProfile.STATUS_OK);
            p.setTls13(TlsProfile.YES);
            p.setTls12(TlsProfile.YES);
            p.setTls11(TlsProfile.NO);
            p.setTls10(TlsProfile.NO);
            p.setOcspStapling(TlsProfile.YES);
            p.setWeakCipher(TlsProfile.NO);
            profile.accept(p);
            profiles.add(p);
        }
    }

    private static String grade(List<CertificateDto> rows, String domain) {
        return rows.stream().filter(r -> r.getDomain().equals(domain)).findFirst().orElseThrow().getTlsGrade();
    }

    @Test
    @DisplayName("getAllLatest notu yazar; profil TEK toplu sorguyla okunur; elle yüklenen satır notsuz")
    void listRowsCarryGrade() {
        List<CertificateDto> rows = service.getAllLatest();
        assertThat(grade(rows, "aplus.example.com")).isEqualTo("A+");
        assertThat(grade(rows, "tls10.example.com")).isEqualTo("B");
        assertThat(grade(rows, "expired.example.com")).isEqualTo("F");
        assertThat(grade(rows, "pending.example.com")).isEqualTo("A");
        assertThat(grade(rows, "upload-key")).isNull();
        CertificateDto tls10 = rows.stream().filter(r -> r.getDomain().equals("tls10.example.com")).findFirst().orElseThrow();
        assertThat(tls10.getTlsGradeReasons()).containsExactly("TLS10_ENABLED");
        verify(profileRepo, times(1)).findByDomainIn(anyCollection());
        verify(statusRepo, times(1)).findAllById(any());
    }

    @Test
    @DisplayName("Not süzgeci + facet (kendi boyutu hariç) + 'none'; sıralama artan = sorunlu önce")
    @SuppressWarnings("unchecked")
    void filterFacetSort() {
        Map<String, Object> res = service.getPaginated(new CertListQuery(1, 50, "domain", "asc", "", "", "", "", "", false,
                null, "", "", "", "B"), null);
        List<CertificateDto> data = (List<CertificateDto>) res.get("data");
        assertThat(data).extracting(CertificateDto::getDomain).containsExactly("tls10.example.com");
        Map<String, Integer> grades = (Map<String, Integer>) ((Map<String, Object>) res.get("facets")).get("grades");
        assertThat(grades).containsEntry("A+", 1).containsEntry("A", 1).containsEntry("B", 1).containsEntry("F", 1)
                .containsEntry("none", 1).containsEntry("C", 0);

        Map<String, Object> none = service.getPaginated(new CertListQuery(1, 50, "domain", "asc", "", "", "", "", "", false,
                null, "", "", "", "none"), null);
        assertThat((List<CertificateDto>) none.get("data")).extracting(CertificateDto::getDomain).containsExactly("upload-key");

        Map<String, Object> bogus = service.getPaginated(new CertListQuery(1, 50, "domain", "asc", "", "", "", "", "", false,
                null, "", "", "", "Z"), null);
        assertThat((List<CertificateDto>) bogus.get("data")).as("tanınmayan değer = süzgeç yok").hasSize(5);

        Map<String, Object> sorted = service.getPaginated(new CertListQuery(1, 50, "tls_grade", "asc", "", "", "", "", "", false,
                null, "", "", "", ""), null);
        assertThat((List<CertificateDto>) sorted.get("data")).extracting(CertificateDto::getDomain)
                .containsExactly("expired.example.com", "tls10.example.com", "pending.example.com", "aplus.example.com", "upload-key");
    }

    @Test
    @DisplayName("Notsuz satırda TLS alanları JSON'a hiç girmez (yanıt bugünküyle aynı)")
    void absentFieldsNotSerialized() throws Exception {
        CertificateDto row = service.getAllLatest().stream().filter(r -> r.getDomain().equals("upload-key")).findFirst().orElseThrow();
        String json = new ObjectMapper().writeValueAsString(row);
        assertThat(json).doesNotContain("tls_grade");
        CertificateDto graded = service.getAllLatest().stream().filter(r -> r.getDomain().equals("aplus.example.com")).findFirst().orElseThrow();
        String gj = new ObjectMapper().writeValueAsString(graded);
        assertThat(gj).contains("\"tls_grade\":\"A+\"").doesNotContain("tls_grade_reasons").doesNotContain("tls_grade_drop");
    }

    @Test
    @DisplayName("CSV: tls_grade sütunu; varsayılan sütun sırası değişmedi (yeni sütun sonda)")
    void csvColumn() {
        String csv = service.exportCsv(new CertListQuery(1, 50, "domain", "asc", "", "", "", "", "", false, null, "", "", "", "B"),
                null, List.of("domain", "tls_grade", "tls_grade_reasons"));
        assertThat(csv).contains("tls10.example.com,B,TLS10_ENABLED");
        String all = service.exportCsv(new CertListQuery(1, 50, "domain", "asc", "", "", "", "", "", false, null, "", "", "", ""),
                null, List.of());
        String header = all.lines().findFirst().orElseThrow();
        assertThat(header).startsWith("domain,issuer,subject").endsWith("error,tls_grade,tls_grade_reasons");
    }
}
