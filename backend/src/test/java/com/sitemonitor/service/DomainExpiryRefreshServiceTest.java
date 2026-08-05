package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    DomainMonitorRepository domainMonitorRepo;
    DomainCheckRepository checkRepo;
    DomainExpiryRefreshService service;

    @BeforeEach
    void setup() {
        diag = mock(DomainExpiryDiagnosticsService.class);
        inventoryRepo = mock(CertificateInventoryRepository.class);
        publicSuffixService = mock(PublicSuffixService.class);
        domainMonitorRepo = mock(DomainMonitorRepository.class);
        checkRepo = mock(DomainCheckRepository.class);
        when(domainMonitorRepo.findByActiveTrue()).thenReturn(List.of());   // varsayılan: monitör yok → diagnose yolu
        service = new DomainExpiryRefreshService(diag, inventoryRepo, publicSuffixService, domainMonitorRepo, checkRepo);
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
    @DisplayName("refreshAll: canlı monitör taze değeri TAZE envanteri bile bayat cache'i düzeltir (diagnose çağrılmaz)")
    void refreshAll_reconcilesFromLiveMonitor() {
        // Envanter TAZE (checkedAt şimdi) ama değer bayat (2026, yenileme öncesi) → diagnose normalde atlardı.
        CertificateInventory a = ci("www.kartfree.com", ISO.format(Instant.now()));
        a.setDomainExpiry("2026-08-06");
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(a));
        when(publicSuffixService.registrableDomain("www.kartfree.com")).thenReturn("kartfree.com");
        when(publicSuffixService.registrableDomain("kartfree.com")).thenReturn("kartfree.com");

        DomainMonitor mon = mock(DomainMonitor.class);
        when(mon.getId()).thenReturn(7L);
        when(mon.getDomain()).thenReturn("kartfree.com");
        when(domainMonitorRepo.findByActiveTrue()).thenReturn(List.of(mon));
        DomainCheck chk = new DomainCheck();
        chk.setSource("RDAP");
        chk.setExpiryDate("2027-08-06T12:37:46Z");   // canlı RDAP (yenilenmiş)
        chk.setRegistrar("GoDaddy");
        chk.setCheckedAt(ISO.format(Instant.now()));
        when(checkRepo.findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(7L, "NONE")).thenReturn(Optional.of(chk));

        int n = service.refreshAll();

        assertThat(n).isEqualTo(1);
        assertThat(a.getDomainExpiry()).isEqualTo("2027-08-06T12:37:46Z");   // monitörden düzeltildi
        assertThat(a.getDomainRegistrar()).isEqualTo("GoDaddy");
        verify(diag, never()).diagnose(any());                               // canlı monitör → RDAP diagnose YOK
        verify(inventoryRepo).save(any());
    }

    @Test
    @DisplayName("refreshAll: monitör değeri cache ile aynı gün → yazma yok (churn yok)")
    void refreshAll_monitorSameDay_noWrite() {
        CertificateInventory a = ci("www.kartfree.com", ISO.format(Instant.now()));
        a.setDomainExpiry("2027-08-06");   // zaten güncel
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(a));
        when(publicSuffixService.registrableDomain("www.kartfree.com")).thenReturn("kartfree.com");
        when(publicSuffixService.registrableDomain("kartfree.com")).thenReturn("kartfree.com");

        DomainMonitor mon = mock(DomainMonitor.class);
        when(mon.getId()).thenReturn(7L);
        when(mon.getDomain()).thenReturn("kartfree.com");
        when(domainMonitorRepo.findByActiveTrue()).thenReturn(List.of(mon));
        DomainCheck chk = new DomainCheck();
        chk.setSource("RDAP");
        chk.setExpiryDate("2027-08-06T00:00:00Z");   // aynı gün (farklı format)
        chk.setCheckedAt(ISO.format(Instant.now()));
        when(checkRepo.findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(7L, "NONE")).thenReturn(Optional.of(chk));

        int n = service.refreshAll();

        assertThat(n).isZero();
        verify(diag, never()).diagnose(any());
        verify(inventoryRepo, never()).save(any());   // gün aynı → yazma yok
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
