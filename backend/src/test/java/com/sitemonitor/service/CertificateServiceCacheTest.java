package com.sitemonitor.service;

import com.sitemonitor.config.CacheConfig;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CACHE BEKÇİSİ — gerçek Spring proxy'siyle.
 *
 * @EnableCaching PROXY modunda çalışır: aynı bean içinden yapılan `this.getAllLatest()` çağrısı
 * proxy'yi ATLAR ve @Cacheable hiç devreye girmez. Dashboard'un tüm sıcak uçları (*ForTeams)
 * tam olarak bu iç çağrıları kullanıyordu → cert-latest/cert-stats/cert-warnings/renewal-advice
 * cache'lerinin hit oranı fiilen %0'dı ve 100 kullanıcı × 5 dk poll her istekte envanter +
 * latest_checks tam taraması demekti (tek pod, CPU/heap sert tavan).
 *
 * Bu test mock'lu repo'larla gerçek CacheManager'ı ayağa kaldırır ve İKİNCİ çağrının veritabanına
 * inmediğini doğrular. Birim testleri (CertificateServiceTest) proxy'siz koştuğu için bunu
 * kanıtlayamaz — self alanını kendileri doldurur.
 */
@SpringJUnitConfig(classes = { CacheConfig.class, CertificateService.class,
                               CertificateServiceCacheTest.TestBeans.class })
class CertificateServiceCacheTest {

    @Configuration
    static class TestBeans {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();   // @Value("${...:default}") çözülsün
        }
    }

    @MockitoBean CertificateCheckRepository checkRepo;
    @MockitoBean LatestCheckRepository latestRepo;
    @MockitoBean CertificateCheckerService checkerService;
    @MockitoBean MaintenanceService maintenanceService;
    @MockitoBean CertificateInventoryRepository inventoryRepo;
    @MockitoBean TeamRepository teamRepo;
    @MockitoBean AlertThresholdRepository alertThresholdRepo;
    @MockitoBean ActivityLogService activityLog;

    @Autowired CertificateService service;

    /** Spring bağlamı test metotları arasında PAYLAŞILIR: mock'lar sıfırlanır ama Caffeine cache
     *  sıcak kalır → sonraki test veriyi cache'ten alır ve "sıfır repo etkileşimi" görür. Her testi
     *  soğuk başlat. */
    @org.junit.jupiter.api.BeforeEach
    void coldCache() { service.evictAllCaches(); }

    private void stubOneDomain() {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("a.example.com"); inv.setActive(true); inv.setTeamId(5L);
        LatestCheck lc = new LatestCheck();
        lc.setDomain("a.example.com"); lc.setStatus("valid"); lc.setDaysRemaining(90);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
        when(inventoryRepo.findByDomainIn(anyCollection())).thenReturn(List.of(inv));
        when(latestRepo.findByDomainIn(anyCollection())).thenReturn(List.of(lc));
        when(latestRepo.findAllByOrderByDomainAsc()).thenReturn(List.of(lc));
        when(teamRepo.findAll()).thenReturn(List.of());
        when(alertThresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());
        when(checkerService.deserializeSan(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("getAllLatestForTeams(null): ikinci çağrı CACHE'ten gelir — envanter/latest_checks bir kez okunur")
    void allLatestForTeams_isCached() {
        stubOneDomain();

        var first  = service.getAllLatestForTeams(null);   // global admin yolu (teamIds == null)
        var second = service.getAllLatestForTeams(null);

        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        // Kritik iddia: iki çağrı → TEK veritabanı turu. Self-injection kaldırılırsa bu 2 olur.
        verify(inventoryRepo, times(1)).findByActiveTrueOrderByDomainAsc();
        verify(latestRepo, times(1)).findByDomainIn(anyCollection());
    }

    @Test
    @DisplayName("Takım kapsamlı çağrı da aynı cache'i kullanır (iki farklı kapsam → tek tarama)")
    void scopedCalls_shareTheSameCache() {
        stubOneDomain();

        service.getAllLatestForTeams(List.of(5L));
        service.getAllLatestForTeams(List.of(5L, 9L));

        // *ForTeams cache'siz ama içerideki getAllLatest() cache'li: filtreleme bellekte yapılır.
        verify(inventoryRepo, times(1)).findByActiveTrueOrderByDomainAsc();
    }

    @Test
    @DisplayName("evictAllCaches sonrası veri YENİDEN okunur (bayat veri asılı kalmaz)")
    void evict_forcesReload() {
        stubOneDomain();

        service.getAllLatestForTeams(null);
        service.evictAllCaches();
        service.getAllLatestForTeams(null);

        verify(inventoryRepo, times(2)).findByActiveTrueOrderByDomainAsc();
    }
}
