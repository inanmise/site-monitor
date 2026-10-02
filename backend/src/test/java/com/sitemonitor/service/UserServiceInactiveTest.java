package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.PasswordHistoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Pasif hesap (2026-10-02, kullanıcı kararı) — {@link UserService} tarafı: pasifleştirme anında oturum kaydı TERMINATED
 * sentinel'e çekilir ve TÜM remember-me token'ları silinir; pasif kapısı ({@link UserService#isAccountInactive}) tek
 * kolon okur ve kısa TTL ile önbellekler, aktiflik değişimi önbelleği anında boşaltır; "pasif" bilgisi yalnız parola
 * doğrulandığında ({@link UserService#findInactiveWithValidPassword}) açığa çıkar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceInactiveTest {

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock PasswordHistoryRepository passwordHistoryRepo;
    @Mock RememberMeService rememberMeService;
    @Mock InactiveRecipientGuard guard;

    UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepo, teamRepo, inventoryRepo, contactRepo, passwordHistoryRepo);
        service.setRememberMeService(rememberMeService);
        service.setInactiveRecipientGuard(guard);
        when(userRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private AppUser user(long id, String username, boolean active, String sid) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(username);
        u.setActive(active);
        u.setActiveSessionId(sid);
        when(userRepo.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("Pasifleştirme (tekil/toplu düzenleme aynı yoldan): TERMINATED sentinel + kullanıcının TÜM remember-me token'ları silinir + süzgeç önbelleği tazelenir")
    void deactivate_killsSessionsAndTokens() {
        user(1L, "ALICE", true, "LIVE-SID");

        AppUser saved = service.updateUser(1L, null, null, null, null, null, false, null);

        assertThat(saved.getActive()).isFalse();
        assertThat(saved.getActiveSessionId()).startsWith(UserService.SESSION_TERMINATED_PREFIX);
        verify(rememberMeService).invalidateAllForUser("ALICE");
        verify(guard).evict();
    }

    @Test
    @DisplayName("Yeniden aktifleştirme erişimi açar ama token silmez ve oturum kaydına dokunmaz (eski oturum geri gelmez — sentinel kalır)")
    void reactivate_restoresAccessOnly() {
        user(1L, "ALICE", false, UserService.SESSION_TERMINATED_PREFIX + "x");

        AppUser saved = service.updateUser(1L, null, null, null, null, null, true, null);

        assertThat(saved.getActive()).isTrue();
        assertThat(saved.getActiveSessionId()).startsWith(UserService.SESSION_TERMINATED_PREFIX);
        verify(rememberMeService, never()).invalidateAllForUser(anyString());
        verify(guard).evict();
    }

    @Test
    @DisplayName("Aktiflik değişmeyen düzenleme (active=null ya da aynı değer) hiçbir oturuma / token'a dokunmaz")
    void unchangedActive_noSideEffects() {
        user(1L, "ALICE", true, "LIVE-SID");
        service.updateUser(1L, "Alice", null, null, null, null, null, null);
        service.updateUser(1L, null, null, null, null, null, true, null);

        ArgumentCaptor<AppUser> cap = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getActiveSessionId()).isEqualTo("LIVE-SID");
        verifyNoInteractions(rememberMeService, guard);
    }

    @Test
    @DisplayName("isAccountInactive: tek-kolon okuma; pasif → true, aktif / kullanıcı yok → false; TTL içinde DB'ye gitmez; pasifleştirme önbelleği anında boşaltır")
    void isAccountInactive_cachedAndEvictedOnChange() {
        ReflectionTestUtils.setField(service, "inactiveCacheMs", 60_000L);
        when(userRepo.findActiveFlagByUsername("ALICE")).thenReturn(Optional.of(true));
        when(userRepo.findActiveFlagByUsername("GHOST")).thenReturn(Optional.empty());

        assertThat(service.isAccountInactive("ALICE")).isFalse();
        assertThat(service.isAccountInactive("alice")).isFalse();   // normalize: aynı anahtar
        verify(userRepo, times(1)).findActiveFlagByUsername(anyString());
        assertThat(service.isAccountInactive("GHOST")).isFalse();
        assertThat(service.isAccountInactive(null)).isFalse();

        // Pasifleştirme bu pod'da önbelleği boşaltır → sonraki istek hemen görür (TTL beklemez).
        user(1L, "ALICE", true, "LIVE-SID");
        service.updateUser(1L, null, null, null, null, null, false, null);
        when(userRepo.findActiveFlagByUsername("ALICE")).thenReturn(Optional.of(false));
        assertThat(service.isAccountInactive("ALICE")).isTrue();
    }

    @Test
    @DisplayName("findInactiveWithValidPassword: yalnız PASİF + DOĞRU parola; yanlış parola ya da aktif hesap → boş (numaralandırma yok)")
    void findInactiveWithValidPassword_onlyWhenPasswordMatches() {
        AppUser p = new AppUser();
        p.setUsername("PASIF");
        p.setActive(false);
        p.setPasswordHash(new BCryptPasswordEncoder().encode("dogru-parola"));
        when(userRepo.findByUsername("pasif")).thenReturn(Optional.of(p));

        assertThat(service.findInactiveWithValidPassword("pasif", "dogru-parola")).containsSame(p);
        assertThat(service.findInactiveWithValidPassword("pasif", "yanlis")).isEmpty();

        p.setActive(true);
        assertThat(service.findInactiveWithValidPassword("pasif", "dogru-parola")).isEmpty();

        p.setActive(false);
        p.setPasswordHash(null);   // LDAP hesabı: yerel parola yok
        assertThat(service.findInactiveWithValidPassword("pasif", "dogru-parola")).isEmpty();
    }
}
