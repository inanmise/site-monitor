package com.sitemonitor.service;

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
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Envanter domain_expiry cache'ini RDAP/WHOIS'ten tazeleyen servis — bayat/taze/başarısız + registrable eşleşme.
 *  2026-11-06 (performans PF3 #4): envanter TUR BAŞINA bir kez, tek kolon projeksiyonla okunur; yazım hedefli
 *  {@code updateDomainExpiry} (tam varlık kaydı yok). Aktif / silinmemiş / elle yüklenen hariç süzgeci SQL'de
 *  ({@code PerfScheduledProjectionQueriesTest}). */
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

    private final AtomicLong ids = new AtomicLong();

    /** findDomainExpiryRows satırı: [id, domain, domainExpiry, domainExpiryCheckedAt]. */
    private Object[] row(String domain, String expiry, String checkedAt) {
        return new Object[]{ids.incrementAndGet(), domain, expiry, checkedAt};
    }

    @SuppressWarnings("unchecked")
    private java.util.Collection<Long> capturedIds() {
        org.mockito.ArgumentCaptor<java.util.Collection<Long>> cap = org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(inventoryRepo).updateDomainExpiry(cap.capture(), anyString(), any(), anyString());
        return cap.getValue();
    }

    @Test
    @DisplayName("refreshAll: bayat distinct registrable RDAP'tan tazelenir; taze atlanır; eşleşen satırlara TEK hedefli UPDATE")
    void refreshAll_refreshesStaleSkipsFresh() {
        Object[] a = row("www.kartfree.com", null, null);                     // bayat (hiç kontrol edilmemiş)
        Object[] b = row("kartfree.com", null, null);                         // aynı registrable
        Object[] fresh = row("fresh.com", null, ISO.format(Instant.now()));   // taze → atla
        when(inventoryRepo.findDomainExpiryRows()).thenReturn(List.of(a, b, fresh));
        when(publicSuffixService.registrableDomain("www.kartfree.com")).thenReturn("kartfree.com");
        when(publicSuffixService.registrableDomain("kartfree.com")).thenReturn("kartfree.com");
        when(publicSuffixService.registrableDomain("fresh.com")).thenReturn("fresh.com");
        when(diag.diagnose("kartfree.com")).thenReturn(Map.of(
                "source", "RDAP", "expiry_date", "2027-08-06", "registrable", "kartfree.com", "registrar", "GoDaddy"));
        when(inventoryRepo.updateDomainExpiry(anyCollection(), anyString(), any(), anyString())).thenReturn(2);

        int n = service.refreshAll();

        assertThat(n).isEqualTo(1);
        verify(diag).diagnose("kartfree.com");
        verify(diag, never()).diagnose("fresh.com");                 // taze → sorgulanmaz
        verify(inventoryRepo).updateDomainExpiry(anyCollection(), eq("2027-08-06"), eq("GoDaddy"), anyString());
        assertThat(capturedIds()).containsExactlyInAnyOrder((Long) a[0], (Long) b[0]);   // eşleşen iki satır; fresh yok
        verify(inventoryRepo, times(1)).findDomainExpiryRows();     // envanter TUR BAŞINA bir kez (PF3 #4)
        verify(inventoryRepo, never()).findByActiveTrueOrderByDomainAsc();
        verify(inventoryRepo, never()).save(any());                 // tam varlık kaydı YOK
    }

    @Test
    @DisplayName("refreshAll: birden çok registrable yazılsa da envanter bir kez okunur (eskiden yazım başına tüm envanter)")
    void refreshAll_manyRegistrables_singleInventoryRead() {
        when(inventoryRepo.findDomainExpiryRows()).thenReturn(List.of(
                row("a.com", null, null), row("b.com", null, null), row("c.com", null, null)));
        for (String d : List.of("a.com", "b.com", "c.com")) {
            when(publicSuffixService.registrableDomain(d)).thenReturn(d);
            when(diag.diagnose(d)).thenReturn(Map.of("source", "RDAP", "expiry_date", "2027-01-01", "registrable", d));
        }
        when(inventoryRepo.updateDomainExpiry(anyCollection(), anyString(), any(), anyString())).thenReturn(1);

        assertThat(service.refreshAll()).isEqualTo(3);
        verify(inventoryRepo, times(1)).findDomainExpiryRows();
        verify(inventoryRepo, times(3)).updateDomainExpiry(anyCollection(), eq("2027-01-01"), any(), anyString());
    }

    @Test
    @DisplayName("refreshAll: diagnose FAILED → yazma yok, eski değer korunur")
    void refreshAll_failed_keepsOld() {
        when(inventoryRepo.findDomainExpiryRows()).thenReturn(List.<Object[]>of(row("dead.com", "2020-01-01", null)));
        when(publicSuffixService.registrableDomain("dead.com")).thenReturn("dead.com");
        when(diag.diagnose("dead.com")).thenReturn(Map.of("source", "FAILED"));

        int n = service.refreshAll();

        assertThat(n).isZero();
        verify(inventoryRepo, never()).updateDomainExpiry(anyCollection(), anyString(), any(), anyString());
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("refreshAll: canlı monitör taze değeri TAZE envanteri bile bayat cache'i düzeltir (diagnose çağrılmaz)")
    void refreshAll_reconcilesFromLiveMonitor() {
        // Envanter TAZE (checkedAt şimdi) ama değer bayat (2026, yenileme öncesi) → diagnose normalde atlardı.
        Object[] a = row("www.kartfree.com", "2026-08-06", ISO.format(Instant.now()));
        when(inventoryRepo.findDomainExpiryRows()).thenReturn(List.<Object[]>of(a));
        when(publicSuffixService.registrableDomain("www.kartfree.com")).thenReturn("kartfree.com");
        when(publicSuffixService.registrableDomain("kartfree.com")).thenReturn("kartfree.com");
        when(inventoryRepo.updateDomainExpiry(anyCollection(), anyString(), any(), anyString())).thenReturn(1);

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
        verify(inventoryRepo).updateDomainExpiry(anyCollection(), eq("2027-08-06T12:37:46Z"), eq("GoDaddy"), anyString());
        assertThat(capturedIds()).containsExactly((Long) a[0]);              // monitörden düzeltildi
        verify(diag, never()).diagnose(any());                               // canlı monitör → RDAP diagnose YOK
    }

    @Test
    @DisplayName("refreshAll: monitör değeri cache ile aynı gün → yazma yok (churn yok)")
    void refreshAll_monitorSameDay_noWrite() {
        when(inventoryRepo.findDomainExpiryRows()).thenReturn(List.<Object[]>of(
                row("www.kartfree.com", "2027-08-06", ISO.format(Instant.now()))));   // zaten güncel
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
        verify(inventoryRepo, never()).updateDomainExpiry(anyCollection(), anyString(), any(), anyString());   // gün aynı → yazma yok
    }

    @Test
    @DisplayName("persistToInventory: yalnız registrable eşleşen (büyük/küçük harf duyarsız) satırlara hedefli UPDATE")
    void persistToInventory_matchesRegistrable() {
        Object[] a = row("www.kartfree.com", null, null);
        Object[] other = row("baska.com", null, null);
        when(inventoryRepo.findDomainExpiryRows()).thenReturn(List.of(a, other));
        when(publicSuffixService.registrableDomain("www.kartfree.com")).thenReturn("KartFree.com");   // equalsIgnoreCase kuralı
        when(publicSuffixService.registrableDomain("baska.com")).thenReturn("baska.com");
        when(inventoryRepo.updateDomainExpiry(anyCollection(), anyString(), any(), anyString())).thenReturn(1);

        int updated = service.persistToInventory("kartfree.com", "2027-08-06", "GoDaddy");

        assertThat(updated).isEqualTo(1);
        org.mockito.ArgumentCaptor<String> at = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(inventoryRepo).updateDomainExpiry(anyCollection(), eq("2027-08-06"), eq("GoDaddy"), at.capture());
        assertThat(at.getValue()).isNotBlank();                            // checkedAt damgası
        assertThat(capturedIds()).containsExactly((Long) a[0]);
        verify(inventoryRepo, never()).save(any());
    }

    @Test
    @DisplayName("persistToInventory: eşleşen satır yok → UPDATE yok; okuma hatası yutulur (0)")
    void persistToInventory_noMatchOrError_zero() {
        when(inventoryRepo.findDomainExpiryRows()).thenReturn(List.<Object[]>of(row("baska.com", null, null)));
        when(publicSuffixService.registrableDomain("baska.com")).thenReturn("baska.com");
        assertThat(service.persistToInventory("kartfree.com", "2027-08-06", "GoDaddy")).isZero();
        verify(inventoryRepo, never()).updateDomainExpiry(anyCollection(), anyString(), any(), anyString());

        when(inventoryRepo.findDomainExpiryRows()).thenThrow(new RuntimeException("db yok"));
        assertThat(service.persistToInventory("kartfree.com", "2027-08-06", "GoDaddy")).isZero();
    }
}
