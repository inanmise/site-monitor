package com.sitemonitor.service;

import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.PasswordHistoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kullanıcı kapısı önbelleklerinin kuşak koruması (çok pod Sürüm 2a son inceleme, C-3): DB okuması sürerken gelen bir
 * boşaltma (bu pod'da ya da önbellek yolu üzerinden öteki pod'dan) okunan ESKİ değerin önbelleğe yazılmasını engeller —
 * pasif / silinmiş hesap ve zorla kapatılan oturum 5 sn boyunca geri gelmez. Koruma olmadan ilk okuma saklanır ve ikinci
 * çağrı DB'ye inmez (testlerin anlamı).
 */
class UserGateGenerationGuardTest {

    AppUserRepository repo;
    UserService service;

    @BeforeEach
    void setUp() {
        repo = mock(AppUserRepository.class);
        service = new UserService(repo, mock(TeamRepository.class), mock(CertificateInventoryRepository.class),
                mock(EscalationContactRepository.class), mock(PasswordHistoryRepository.class));
        ReflectionTestUtils.setField(service, "inactiveCacheMs", 60_000L);
        ReflectionTestUtils.setField(service, "supersedeCacheMs", 60_000L);
    }

    @Test
    @DisplayName("Pasif kapısı: okuma sırasında boşaltma geldi → okunan değer saklanmaz, sonraki istek DB'yi yeniden okur")
    void inactiveRead_racingEvict_isNotStored() {
        when(repo.findActiveFlagByUsername("ayse")).thenAnswer(inv -> {
            service.forgetUserCaches("ayse");          // eşzamanlı boşaltma (silme / pasifleştirme / öteki pod)
            return Optional.of(Boolean.TRUE);
        });
        assertThat(service.isAccountInactive("ayse")).isFalse();
        assertThat(service.isAccountInactive("ayse")).isFalse();
        verify(repo, times(2)).findActiveFlagByUsername("ayse");
    }

    @Test
    @DisplayName("Pasif kapısı: yarış yoksa bugünkü gibi önbellekten (tek okuma)")
    void inactiveRead_noRace_isCached() {
        when(repo.findActiveFlagByUsername("ayse")).thenReturn(Optional.of(Boolean.TRUE));
        service.isAccountInactive("ayse");
        service.isAccountInactive("ayse");
        verify(repo, times(1)).findActiveFlagByUsername("ayse");
    }

    @Test
    @DisplayName("Süpersede: okuma sırasında zorla kapatma / rol değişikliği boşaltması geldi → eski sid saklanmaz")
    void supersedeRead_racingEvict_isNotStored() {
        when(repo.findActiveSessionIdByUsername("ayse")).thenAnswer(inv -> {
            service.evictActiveSessionCacheFor("ayse");
            return Optional.of("sid-1");
        });
        assertThat(service.isSessionSuperseded("ayse", "sid-1")).isFalse();
        assertThat(service.isSessionSuperseded("ayse", "sid-1")).isFalse();
        verify(repo, times(2)).findActiveSessionIdByUsername("ayse");
    }

    @Test
    @DisplayName("Süpersede: yarış yoksa eşleşen sid önbellekten döner (istek başına SELECT yok)")
    void supersedeRead_noRace_isCached() {
        when(repo.findActiveSessionIdByUsername("ayse")).thenReturn(Optional.of("sid-1"));
        service.isSessionSuperseded("ayse", "sid-1");
        service.isSessionSuperseded("ayse", "sid-1");
        verify(repo, times(1)).findActiveSessionIdByUsername("ayse");
    }
}
