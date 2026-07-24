package com.certmonitor.service;

import com.certmonitor.model.CertificateInventory;
import com.certmonitor.repository.CertificateInventoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Envanter domain_expiry cache'ini RDAP/WHOIS'ten tazeleyen servis — bayat/taze/başarısız + registrable eşleşme. */
class DomainExpiryRefreshServiceTest {

    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    DomainExpiryDiagnosticsService diag;
    CertificateInventoryRepository inventoryRepo;
    PublicSuffixService publicSuffixService;
    DomainExpiryRefreshService service;

    @BeforeEach
    void setup() {
        diag = mock(DomainExpiryDiagnosticsService.class);
        inventoryRepo = mock(CertificateInventoryRepository.class);
        publicSuffixService = mock(PublicSuffixService.class);
        service = new DomainExpiryRefreshService(diag, inventoryRepo, publicSuffixService);
    }

    private static CertificateInventory ci(String domain, String checkedAt) {
        CertificateInventory c = new CertificateInventory();
        c.setDomain(domain);
        c.setDomainExpiryCheckedAt(checkedAt);
        return c;
    }

    @Test
    @DisplayName("refreshAll: bayat distinct registrable RDAP'tan tazelenir; taze atlanır; eşleşen satırlara yazılır")
    void refreshAll_refreshesStaleSkipsFresh() {
        CertificateInventory a = ci("www.kartfree.com", null);                    // bayat (hiç kontrol edilmemiş)
        CertificateInventory b = ci("kartfree.com", null);                        // aynı registrable
        CertificateInventory fresh = ci("fresh.com", ISO.format(Instant.now()));  // taze → atla
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(a, b, fresh));
        when(publicSuffixService.registrableDomain("www.kartfree.com")).thenReturn("kartfree.com");
        when(publicSuffixService.registrableDomain("kartfree.com")).thenReturn("kartfree.com");
        when(publicSuffixService.registrableDomain("fresh.com")).thenReturn("fresh.com");
        when(diag.diagnose("kartfree.com")).thenReturn(Map.of(
                "source", "RDAP", "expiry_date", "2027-08-06", "registrable", "kartfree.com", "registrar", "GoDaddy"));

        int n = service.refreshAll();

        assertThat(n).isEqualTo(1);
        verify(diag).diagnose("kartfree.com");
        verify(diag, never()).diagnose("fresh.com");                 // taze → sorgulanmaz
        assertThat(a.getDomainExpiry()).isEqualTo("2027-08-06");     // eşleşen iki satır güncellendi
        assertThat(b.getDomainExpiry()).isEqualTo("2027-08-06");
        assertThat(fresh.getDomainExpiry()).isNull();                // eşleşmez → yazılmaz
        verify(inventoryRepo, times(2)).save(any());
    }

    @Test
    @DisplayName("refreshAll: diagnose FAILED → yazma yok, eski değer korunur")
    void refreshAll_failed_keepsOld() {
        CertificateInventory a = ci("dead.com", null);
        a.setDomainExpiry("2020-01-01");   // eski değer
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(a));
        when(publicSuffixService.registrableDomain("dead.com")).thenReturn("dead.com");
        when(diag.diagnose("dead.com")).thenReturn(Map.of("source", "FAILED"));

        int n = service.refreshAll();

        assertThat(n).isZero();
        assertThat(a.getDomainExpiry()).isEqualTo("2020-01-01");     // korundu
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("persistToInventory: yalnız registrable eşleşen aktif satırlara yazar (checkedAt damgası)")
    void persistToInventory_matchesRegistrable() {
        CertificateInventory a = ci("www.kartfree.com", null);
        CertificateInventory other = ci("baska.com", null);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(a, other));
        when(publicSuffixService.registrableDomain("www.kartfree.com")).thenReturn("kartfree.com");
        when(publicSuffixService.registrableDomain("baska.com")).thenReturn("baska.com");

        int updated = service.persistToInventory("kartfree.com", "2027-08-06", "GoDaddy");

        assertThat(updated).isEqualTo(1);
        assertThat(a.getDomainExpiry()).isEqualTo("2027-08-06");
        assertThat(a.getDomainRegistrar()).isEqualTo("GoDaddy");
        assertThat(a.getDomainExpiryCheckedAt()).isNotBlank();
        assertThat(other.getDomainExpiry()).isNull();
    }
}
