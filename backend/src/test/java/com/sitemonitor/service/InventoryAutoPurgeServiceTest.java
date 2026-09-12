package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.CertificateNoteRepository;
import com.sitemonitor.repository.CertificateNoteRevisionRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Envanter çöp kutusu otomatik boşaltma (2026-09-12, envanter #10): eşik, kapalı ayar, kilit, denetim. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryAutoPurgeServiceTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mock AppSettingsService appSettings;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock CertificateCheckRepository certificateCheckRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock CertificateNoteRepository noteRepo;
    @Mock CertificateNoteRevisionRepository noteRevisionRepo;
    @Mock AuditService auditService;
    @Mock SchedulerService schedulerService;
    @InjectMocks InventoryAutoPurgeService service;

    private static CertificateInventory deleted(String domain, int daysAgo) {
        CertificateInventory i = new CertificateInventory();
        i.setId((long) domain.hashCode()); i.setDomain(domain); i.setTeamId(5L);
        i.setDeletedAt(ISO.format(Instant.now().minus(daysAgo, ChronoUnit.DAYS)));
        return i;
    }

    @Test
    @DisplayName("eşikten eski silinmişler kontrol geçmişi + latest + notlarla kalıcı silinir; yeni silinmiş kalır; tek sistem denetimi")
    void purgesOnlyOlderThanThreshold() {
        when(inventoryRepo.findByDeletedAtIsNotNullOrderByDomainAsc()).thenReturn(List.of(deleted("old.example.com", 40), deleted("fresh.example.com", 3)));
        when(certificateCheckRepo.deleteByDomain("old.example.com")).thenReturn(12);
        when(latestCheckRepo.findById(anyString())).thenReturn(Optional.empty());
        when(noteRepo.findByDomainOrderByCreatedAtDesc(anyString())).thenReturn(List.of());

        var r = service.purgeOlderThan(30);

        assertThat(r.purged()).isEqualTo(1);
        assertThat(r.checksDeleted()).isEqualTo(12);
        assertThat(r.domains()).containsExactly("old.example.com");
        verify(inventoryRepo).delete(argThat(i -> i.getDomain().equals("old.example.com")));
        verify(inventoryRepo, never()).delete(argThat(i -> i.getDomain().equals("fresh.example.com")));
        verify(certificateCheckRepo, never()).deleteByDomain("fresh.example.com");
        verify(auditService).recordSystemEvent(eq("DOMAIN_AUTO_PURGE"), eq("CERTIFICATE"), eq("1 domain"),
                argThat((String d) -> d.contains("\"days\":30") && d.contains("old.example.com")));
    }

    @Test
    @DisplayName("ayar 0 (kapalı) → zamanlanmış koşu hiçbir şey okumaz; kilit başka pod'da → atlanır")
    void scheduledRespectsSettingAndLock() {
        when(appSettings.getInt(InventoryAutoPurgeService.KEY, 0)).thenReturn(0);
        service.scheduled();
        verify(inventoryRepo, never()).findByDeletedAtIsNotNullOrderByDomainAsc();

        when(appSettings.getInt(InventoryAutoPurgeService.KEY, 0)).thenReturn(30);
        when(schedulerService.tryAcquireSchedulerLock("inventory-auto-purge", 30)).thenReturn(false);
        service.scheduled();
        verify(inventoryRepo, never()).findByDeletedAtIsNotNullOrderByDomainAsc();

        when(schedulerService.tryAcquireSchedulerLock("inventory-auto-purge", 30)).thenReturn(true);
        when(inventoryRepo.findByDeletedAtIsNotNullOrderByDomainAsc()).thenReturn(List.of());
        service.scheduled();
        verify(inventoryRepo).findByDeletedAtIsNotNullOrderByDomainAsc();
        verify(auditService, never()).recordSystemEvent(anyString(), anyString(), anyString(), anyString());   // 0 silme → denetim yok
    }
}
