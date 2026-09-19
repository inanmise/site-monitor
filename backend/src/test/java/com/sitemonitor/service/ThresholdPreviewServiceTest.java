package com.sitemonitor.service;

import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Eşik etki önizlemesi (2026-09-20): kapsam satırın kapsamıdır, sayım kalan güne göre; kalıcı yazma yok.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ThresholdPreviewServiceTest {

    @Mock AlertThresholdRepository thresholdRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @InjectMocks ThresholdPreviewService service;

    private static AlertThreshold row(Integer tier, int warn, int high, int crit) {
        AlertThreshold t = new AlertThreshold();
        t.setTier(tier); t.setWarningDays(warn); t.setHighDays(high); t.setCriticalDays(crit); t.setActive(true);
        return t;
    }
    private static CertificateInventory inv(String d, Integer tier) {
        CertificateInventory i = new CertificateInventory(); i.setDomain(d); i.setTier(tier); i.setActive(true); return i;
    }
    private static LatestCheck lc(String d, Integer days) {
        LatestCheck l = new LatestCheck(); l.setDomain(d); l.setDaysRemaining(days); return l;
    }

    @BeforeEach
    void setUp() {
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.of(row(null, 30, 15, 7)));
        when(thresholdRepo.findByActiveTrueAndTierIsNotNullOrderByIdAsc()).thenReturn(List.of(row(1, 60, 30, 14)));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(
                inv("t1-a.example.com", 1), inv("t1-b.example.com", 1),
                inv("t2.example.com", 2), inv("none.example.com", null), inv("unchecked.example.com", null)));
        List<LatestCheck> checks = List.of(
                lc("t1-a.example.com", 12), lc("t1-b.example.com", 50),
                lc("t2.example.com", 12), lc("none.example.com", 25));
        when(latestCheckRepo.findByDomainIn(anyCollection())).thenAnswer(i -> {
            Collection<?> ds = i.getArgument(0);
            List<LatestCheck> out = new ArrayList<>();
            for (LatestCheck c : checks) if (ds.contains(c.getDomain())) out.add(c);
            return out;
        });
    }

    @Test
    @DisplayName("Tier satırı önizlemesi YALNIZ o tier'daki alanları sayar; mevcut ve önerilen yan yana")
    @SuppressWarnings("unchecked")
    void tierScope() {
        Map<String, Object> r = service.preview(1, 40, 20, 10);
        assertThat(r.get("scope_total")).isEqualTo(2);
        assertThat(r.get("unchecked")).isEqualTo(0);
        Map<String, Object> cur = (Map<String, Object>) r.get("current");
        Map<String, Object> next = (Map<String, Object>) r.get("proposed");
        // mevcut tier-1 eşiği (60/30/14): 12g → KRİTİK, 50g → UYARI
        assertThat(cur.get("critical")).isEqualTo(1);
        assertThat(cur.get("warning")).isEqualTo(1);
        // önerilen (40/20/10): 12g → YÜKSEK, 50g → eşik dışı
        assertThat(next.get("high")).isEqualTo(1);
        assertThat(next.get("ok")).isEqualTo(1);
        Map<String, List<String>> samples = (Map<String, List<String>>) r.get("samples");
        assertThat(samples.get("HIGH")).containsExactly("t1-a.example.com (12g)");
    }

    @Test
    @DisplayName("Varsayılan satır önizlemesi: tier'sız alanlar + KENDİ satırı olmayan tier'lar (tier 1 dışarıda)")
    @SuppressWarnings("unchecked")
    void defaultScopeExcludesOverriddenTiers() {
        Map<String, Object> r = service.preview(null, 30, 15, 7);
        assertThat(r.get("scope_total")).isEqualTo(3);           // t2 + none + unchecked
        assertThat(r.get("unchecked")).isEqualTo(1);             // son kontrolü yok → sayıma girmez
        Map<String, Object> next = (Map<String, Object>) r.get("proposed");
        assertThat(next.get("high")).isEqualTo(1);               // t2 12g
        assertThat(next.get("warning")).isEqualTo(1);            // none 25g
        assertThat(next.get("critical")).isEqualTo(0);
    }

    @Test
    @DisplayName("Boş kapsamda sayaçlar sıfır, sorgu atılmaz")
    void emptyScope() {
        Map<String, Object> r = service.preview(4, 30, 15, 7);
        assertThat(r.get("scope_total")).isEqualTo(0);
        assertThat(((Map<?, ?>) r.get("proposed")).get("critical")).isEqualTo(0);
    }
}
