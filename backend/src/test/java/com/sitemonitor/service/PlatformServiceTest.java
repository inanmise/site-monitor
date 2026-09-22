package com.sitemonitor.service;

import com.sitemonitor.model.Platform;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.PlatformRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Platform kataloğu (2026-09-22): varsayılan ekim, kod/ad normalizasyonu, kod doğrulama/tekillik, kullanımdaki platform silinmez. */
class PlatformServiceTest {

    private static Platform p(long id, String code, String name) {
        Platform p = new Platform(); p.setId(id); p.setCode(code); p.setName(name); p.setActive(true); p.setSortOrder(10);
        return p;
    }

    @Test
    @DisplayName("boş katalog → 7 varsayılan ekilir; doluysa dokunulmaz")
    void seed() {
        PlatformRepository repo = mock(PlatformRepository.class);
        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        when(repo.count()).thenReturn(0L);
        when(repo.save(any())).thenAnswer(a -> a.getArgument(0));
        new PlatformService(repo, inv).seedDefaults();
        verify(repo, times(PlatformService.DEFAULTS.length)).save(any());
        PlatformRepository full = mock(PlatformRepository.class);
        when(full.count()).thenReturn(3L);
        new PlatformService(full, inv).seedDefaults();
        verify(full, never()).save(any());
    }

    @Test
    @DisplayName("normalize: kod ya da ad (harf duyarsız) → kod; bilinmeyen/boş → null")
    void normalize() {
        PlatformRepository repo = mock(PlatformRepository.class);
        when(repo.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of(p(1, "IIS", "IIS (Windows)"), p(2, "OPENSHIFT", "OpenShift")));
        PlatformService svc = new PlatformService(repo, mock(CertificateInventoryRepository.class));
        assertThat(svc.normalize("iis")).isEqualTo("IIS");
        assertThat(svc.normalize("openshift")).isEqualTo("OPENSHIFT");
        assertThat(svc.normalize(" OpenShift ")).isEqualTo("OPENSHIFT");
        assertThat(svc.normalize("mainframe")).isNull();
        assertThat(svc.normalize("")).isNull();
        assertThat(svc.normalize(null)).isNull();
    }

    @Test
    @DisplayName("create: kod üst-harf slug'a çevrilir, biçim/tekillik/ad doğrulanır; sıra sona eklenir")
    void create() {
        PlatformRepository repo = mock(PlatformRepository.class);
        when(repo.findAllByOrderBySortOrderAscNameAsc()).thenReturn(List.of(p(1, "IIS", "IIS")));
        when(repo.existsByCodeIgnoreCase("IIS")).thenReturn(true);
        when(repo.save(any())).thenAnswer(a -> a.getArgument(0));
        PlatformService svc = new PlatformService(repo, mock(CertificateInventoryRepository.class));
        Platform created = svc.create("k8s prod", "Kubernetes Prod", " ", null, "admin");
        assertThat(created.getCode()).isEqualTo("K8S_PROD");
        assertThat(created.getDescription()).isNull();
        assertThat(created.getSortOrder()).isEqualTo(20);
        assertThat(created.getCreatedBy()).isEqualTo("admin");
        assertThatThrownBy(() -> svc.create("iis", "x", null, null, "a")).hasMessageContaining("zaten var");
        assertThatThrownBy(() -> svc.create("a", "x", null, null, "a")).hasMessageContaining("Kod");
        assertThatThrownBy(() -> svc.create("ABC", " ", null, null, "a")).hasMessageContaining("Ad");
    }

    @Test
    @DisplayName("delete: envanterde kullanılan platform silinmez (false); kullanılmayan silinir; usage kod→sayı")
    void deleteGuard() {
        PlatformRepository repo = mock(PlatformRepository.class);
        CertificateInventoryRepository inv = mock(CertificateInventoryRepository.class);
        List<Object[]> counts = new ArrayList<>();
        counts.add(new Object[]{"IIS", 4L});
        when(inv.platformCounts()).thenReturn(counts);
        when(repo.findById(1L)).thenReturn(java.util.Optional.of(p(1, "IIS", "IIS")));
        when(repo.findById(2L)).thenReturn(java.util.Optional.of(p(2, "LINUX", "Linux")));
        PlatformService svc = new PlatformService(repo, inv);
        assertThat(svc.usage()).containsEntry("IIS", 4L);
        assertThat(svc.delete(1L)).isFalse();
        verify(repo, never()).delete(any());
        assertThat(svc.delete(2L)).isTrue();
        verify(repo).delete(any());
    }
}
