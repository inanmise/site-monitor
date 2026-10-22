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
import static org.mockito.Mockito.when;

/**
 * Pano satırının sahip takımları (2026-10-22, hata avı FE-1): {@code owner_team_ids} = envanterin SY + UG takımı. İstemci
 * "Şimdi kontrol et" kapısını {@code /check/{domain}} kuralıyla (SY ya da UG görüş kapsamı / EXECUTE paylaşımı) birebir
 * kurar — UG takımının üyesi, müdürün astlarının takımı, paylaşımla EXECUTE alan kişi düğmeyi kaybetmesin. Alan kaydın kendi
 * verisidir (görüntüleyiciye göre değişmez); pano dışı DTO'larda yazılmaz.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateServiceOwnerTeamsTest {

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
    private final ObjectMapper snake = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

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
        when(inventoryRepo.findActiveDomainNamesByTeamIds(anyCollection())).thenAnswer(i -> {
            Collection<?> want = i.getArgument(0);
            return active.stream().filter(x -> want.contains(x.getTeamId())).map(CertificateInventory::getDomain).toList();
        });
        when(latestRepo.findByDomainIn(anyCollection())).thenAnswer(i -> {
            Collection<?> want = i.getArgument(0);
            return latest.stream().filter(c -> want.contains(c.getDomain())).toList();
        });
        when(teamRepo.findAllById(any())).thenAnswer(i -> {
            List<Team> out = new ArrayList<>();
            for (Long id : (Iterable<Long>) i.getArgument(0)) { Team t = new Team(); t.setId(id); t.setName("Takım " + id); out.add(t); }
            return out;
        });
    }

    private void inv(List<CertificateInventory> into, String domain, Long teamId, Long ugTeamId) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setActive(into == active); i.setTeamId(teamId); i.setUgTeamId(ugTeamId); i.setTier(1); i.setPort(443);
        into.add(i);
        LatestCheck c = new LatestCheck();
        c.setDomain(domain); c.setStatus("valid"); c.setDaysRemaining(90); c.setWarning(false);
        c.setNotAfter("2027-01-01T00:00:00"); c.setCheckedAt("2026-10-20T10:00:00");
        latest.add(c);
    }

    private static CertificateDto row(List<CertificateDto> rows, String domain) {
        return rows.stream().filter(r -> domain.equals(r.getDomain())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("aktif pano satırı SY + UG taşır (tekil, SY önce); takımsız kayıt boş liste; JSON adı owner_team_ids")
    void activeRowsCarryOwners() {
        inv(active, "iki-takim.example.com", 1L, 7L);
        inv(active, "ayni-takim.example.com", 4L, 4L);
        inv(active, "yalniz-ug.example.com", null, 9L);
        inv(active, "takimsiz.example.com", null, null);
        List<CertificateDto> rows = service.getAllLatest();
        assertThat(row(rows, "iki-takim.example.com").getOwnerTeamIds()).containsExactly(1L, 7L);
        assertThat(row(rows, "ayni-takim.example.com").getOwnerTeamIds()).containsExactly(4L);
        assertThat(row(rows, "yalniz-ug.example.com").getOwnerTeamIds()).containsExactly(9L);
        assertThat(row(rows, "takimsiz.example.com").getOwnerTeamIds()).isEmpty();
        assertThat(snake.writeValueAsString(row(rows, "iki-takim.example.com"))).contains("\"owner_team_ids\":[1,7]");
        // Takımsız kayıt "[]" yazar — istemci için "sahip yok" (biliniyor) ≠ alan yok (bilinmiyor)
        assertThat(snake.writeValueAsString(row(rows, "takimsiz.example.com"))).contains("\"owner_team_ids\":[]");
    }

    @Test
    @DisplayName("takım kapsamlı liste ve paylaşım kopyası (withAccess) da alanı taşır")
    void scopedListAndAccessCopyKeepOwners() {
        inv(active, "iki-takim.example.com", 1L, 7L);
        CertificateDto scoped = service.getAllLatestForTeams(List.of(1L)).get(0);
        assertThat(scoped.getOwnerTeamIds()).containsExactly(1L, 7L);
        assertThat(scoped.withAccess(false, Boolean.TRUE, Boolean.FALSE).getOwnerTeamIds()).containsExactly(1L, 7L);
    }

    @Test
    @DisplayName("pasif pano satırı da SY + UG taşır")
    void pausedRowsCarryOwners() {
        inv(paused, "pasif.example.com", 2L, 3L);
        assertThat(service.getPausedForTeams(null).get(0).getOwnerTeamIds()).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("envanterden kurulmayan DTO (ör. geçmiş satırı) alanı YAZMAZ — istemci bugünkü kuralı uygular")
    void otherDtosOmitTheField() {
        CertificateDto plain = new CertificateDto();
        plain.setDomain("x.example.com");
        assertThat(snake.writeValueAsString(plain)).doesNotContain("owner_team_ids");
        assertThat(CertificateDto.ownerTeamIdsOf(null, null)).isEmpty();
        assertThat(CertificateDto.ownerTeamIdsOf(5L, null)).containsExactly(5L);
    }
}
