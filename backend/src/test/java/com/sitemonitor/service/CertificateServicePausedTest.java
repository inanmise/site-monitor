package com.sitemonitor.service;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.Team;
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
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pano'nun pasif sertifika kartları (2026-10-08, kullanıcı: "aktif olmayan sertifikalar kartlarda gösterilmiyor; kullanıcı
 * aktif ya da pasif kartları görebilmeli, süzebilmeli"). Pasif liste AYRI okunur; aktif liste (İstatistik / Uyarılar da
 * onu okur) değişmez.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateServicePausedTest {

    @Mock CertificateCheckRepository checkRepo;
    @Mock LatestCheckRepository latestRepo;
    @Mock CertificateCheckerService checkerService;
    @Mock MaintenanceService maintenanceService;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;
    @Mock AlertThresholdRepository alertThresholdRepo;
    @Mock ActivityLogService activityLog;

    private CertificateService service;
    private final List<CertificateInventory> active = new ArrayList<>();
    private final List<CertificateInventory> paused = new ArrayList<>();
    private final List<LatestCheck> latest = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new CertificateService(checkRepo, latestRepo, checkerService, maintenanceService,
                inventoryRepo, new ObjectMapper(), teamRepo, alertThresholdRepo, activityLog);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        when(checkerService.deserializeSan(any())).thenReturn(Collections.emptyList());
        when(alertThresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenAnswer(i -> active);
        when(inventoryRepo.findByActiveFalseOrderByDomainAsc()).thenAnswer(i -> paused);
        when(inventoryRepo.findAllActiveDomainNames()).thenAnswer(i -> active.stream().map(CertificateInventory::getDomain).toList());
        when(latestRepo.findByDomainIn(anyCollection())).thenAnswer(i -> {
            Collection<?> want = i.getArgument(0);
            return latest.stream().filter(c -> want.contains(c.getDomain())).toList();
        });
        when(teamRepo.findAllById(any())).thenAnswer(i -> {
            List<Team> out = new ArrayList<>();
            for (Long id : (Iterable<Long>) i.getArgument(0)) { Team t = new Team(); t.setId(id); t.setName("Takım " + id); out.add(t); }
            return out;
        });

        inv(active, "aktif.example.com", 1L, 1);
        check("aktif.example.com", 90);
        inv(paused, "pasif.example.com", 1L, 2).setGroupName("Ödeme");
        check("pasif.example.com", -5);                       // son kontrolde süresi dolmuştu (bayat bilgi)
        inv(paused, "ikiz.example.com", 1L, 1);              // aynı adın AKTİF kaydı da var → pasif ikizi listelenmez
        inv(active, "ikiz.example.com", 1L, 1);
        inv(paused, "hic-kontrol.example.com", 2L, 3);        // hiç kontrol edilmemiş
        inv(paused, "baska-takim.example.com", 3L, 1);
        check("baska-takim.example.com", 40);
    }

    private CertificateInventory inv(List<CertificateInventory> into, String domain, Long teamId, Integer tier) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setActive(into == active); i.setTeamId(teamId); i.setTier(tier); i.setPort(443);
        into.add(i);
        return i;
    }

    private void check(String domain, int days) {
        LatestCheck c = new LatestCheck();
        c.setDomain(domain); c.setStatus("valid"); c.setDaysRemaining(days); c.setWarning(days < 30);
        c.setNotAfter("2027-01-01T00:00:00"); c.setCheckedAt("2026-09-13T10:00:00");
        latest.add(c);
    }

    private static List<String> domains(List<CertificateDto> rows) {
        return rows.stream().map(CertificateDto::getDomain).toList();
    }

    @Test
    @DisplayName("global kapsam: yalnız pasif kayıtlar, aktif ikizi olan düşer; hepsi paused=true")
    void globalScope() {
        List<CertificateDto> rows = service.getPausedForTeams(null);
        assertThat(domains(rows)).containsExactly("pasif.example.com", "hic-kontrol.example.com", "baska-takim.example.com");
        assertThat(rows).allMatch(r -> Boolean.TRUE.equals(r.getPaused()));
    }

    @Test
    @DisplayName("son kontrol bilgisi + envanter alanları (takım, katman, grup) + ton taşınır; hiç kontrol edilmemiş kayıt da döner")
    void carriesLastKnownAndInventoryFields() {
        List<CertificateDto> rows = service.getPausedForTeams(null);
        CertificateDto p = rows.get(0);
        assertThat(p.getDaysRemaining()).isEqualTo(-5);
        assertThat(p.getAlertLevel()).isEqualTo("expired");
        assertThat(p.getTeamName()).isEqualTo("Takım 1");
        assertThat(p.getTier()).isEqualTo(2);
        assertThat(p.getGroupName()).isEqualTo("Ödeme");
        CertificateDto never = rows.get(1);
        assertThat(never.getDomain()).isEqualTo("hic-kontrol.example.com");
        assertThat(never.getDaysRemaining()).isNull();
        assertThat(never.getAlertLevel()).isNull();
        assertThat(never.getTeamName()).isEqualTo("Takım 2");
    }

    @Test
    @DisplayName("takım kapsamı /certificates ile aynı: yalnız sorumlu (SY) takımın kayıtları; boş kapsam → boş, sorgu yok")
    void teamScope() {
        assertThat(domains(service.getPausedForTeams(List.of(1L)))).containsExactly("pasif.example.com");
        assertThat(domains(service.getPausedForTeams(List.of(2L, 3L)))).containsExactly("hic-kontrol.example.com", "baska-takim.example.com");
        assertThat(service.getPausedForTeams(List.of())).isEmpty();
        verify(inventoryRepo, never()).findAllById(any());
    }

    @Test
    @DisplayName("aktif liste DEĞİŞMEZ: pasif kayıtlar getAllLatest'e karışmaz ve aktif satır paused alanını yazmaz")
    void activeListUnchanged() {
        List<CertificateDto> activeRows = service.getAllLatest();
        // Aktif liste son kontrolü olan aktif kayıtlardır (bugünkü kural): pasif hiçbir alan adı karışmaz
        assertThat(domains(activeRows)).containsExactly("aktif.example.com")
                .doesNotContain("pasif.example.com", "hic-kontrol.example.com", "baska-takim.example.com");
        assertThat(activeRows).allMatch(r -> r.getPaused() == null);
        ObjectMapper snake = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
        assertThat(snake.writeValueAsString(activeRows.get(0))).doesNotContain("paused");
        assertThat(snake.writeValueAsString(service.getPausedForTeams(null).get(0))).contains("\"paused\":true");
    }

    @Test
    @DisplayName("hiç pasif kayıt yoksa son kontrol tablosu sorgulanmaz")
    void noPausedNoLatestQuery() {
        paused.clear();
        assertThat(service.getPausedForTeams(null)).isEmpty();
        verify(latestRepo, never()).findByDomainIn(anyCollection());
    }
}
