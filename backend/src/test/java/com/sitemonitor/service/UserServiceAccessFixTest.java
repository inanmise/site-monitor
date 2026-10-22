package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Hata avı 2026-10-22, PKG-1 (UserService): silinmiş hesap kapısı (AUTH-1), rol değişikliğinin canlı oturumu bitirmesi
 * (AUTH-4), kenar boşluklu parolanın reddi (AUTH-7), takım lideri temizleme (ADMIN-11), harf duyarsız takım adı (ADMIN-12).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceAccessFixTest {

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock PasswordHistoryRepository passwordHistoryRepo;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService(userRepo, teamRepo, inventoryRepo, contactRepo, passwordHistoryRepo);
        ReflectionTestUtils.setField(service, "passwordMinLength", 6);
        ReflectionTestUtils.setField(service, "passwordMaxLength", 64);
        ReflectionTestUtils.setField(service, "passwordHistoryCount", 3);
        ReflectionTestUtils.setField(service, "inactiveCacheMs", 60_000L);
        when(contactRepo.findByUserId(anyLong())).thenReturn(List.of());
        when(passwordHistoryRepo.findByUserIdOrderByCreatedAtDesc(anyLong())).thenReturn(List.of());
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(teamRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AppUser user(Long id, String name, String role, boolean active, String sid) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(name);
        u.setSystemRole(role);
        u.setActive(active);
        u.setActiveSessionId(sid);
        when(userRepo.findById(id)).thenReturn(Optional.of(u));
        when(userRepo.findByUsername(name)).thenReturn(Optional.of(u));
        return u;
    }

    // ── AUTH-1: silinmiş hesap ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AUTH-1: satır YOK → isAccountGone true (pasif sayılmaz); aynı önbellek kaydı — ikinci soru DB'ye gitmez")
    void gone_whenRowMissing_cached() {
        when(userRepo.findActiveFlagByUsername("GHOST")).thenReturn(Optional.empty());
        when(userRepo.existsByUsername("GHOST")).thenReturn(false);

        assertThat(service.isAccountInactive("GHOST")).isFalse();
        assertThat(service.isAccountGone("GHOST")).isTrue();
        assertThat(service.isAccountGone("ghost")).isTrue();   // normalize: aynı anahtar

        verify(userRepo, times(1)).findActiveFlagByUsername(anyString());
        verify(userRepo, times(1)).existsByUsername(anyString());
    }

    @Test
    @DisplayName("AUTH-1: aktif satır → silinmiş değil, varlık sorgusu HİÇ koşmaz (sıcak yol tek okuma)")
    void notGone_activeRow_singleQuery() {
        when(userRepo.findActiveFlagByUsername("ALICE")).thenReturn(Optional.of(true));
        assertThat(service.isAccountGone("ALICE")).isFalse();
        verify(userRepo, never()).existsByUsername(anyString());
    }

    @Test
    @DisplayName("AUTH-1: aktiflik kolonu NULL olan eski satır (boş projeksiyon) → silinmiş SAYILMAZ")
    void notGone_nullActiveColumn() {
        when(userRepo.findActiveFlagByUsername("LEGACY")).thenReturn(Optional.empty());
        when(userRepo.existsByUsername("LEGACY")).thenReturn(true);
        assertThat(service.isAccountGone("LEGACY")).isFalse();
    }

    @Test
    @DisplayName("AUTH-1: okuma hatası → silinmiş değil (açık düşer, DB kesintisi herkesi atmaz); boş ad → false")
    void gone_readFailure_failsOpen() {
        when(userRepo.findActiveFlagByUsername("ALICE")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        assertThat(service.isAccountGone("ALICE")).isFalse();
        assertThat(service.isAccountGone(null)).isFalse();
        assertThat(service.isAccountGone(" ")).isFalse();
    }

    @Test
    @DisplayName("AUTH-1: forgetUserCaches → bu pod'un pasif/silinmiş önbelleği düşer, sonraki soru DB'yi okur")
    void forgetUserCaches_evicts() {
        when(userRepo.findActiveFlagByUsername("ALICE")).thenReturn(Optional.of(true));
        assertThat(service.isAccountGone("ALICE")).isFalse();
        // Kullanıcı silindi:
        when(userRepo.findActiveFlagByUsername("ALICE")).thenReturn(Optional.empty());
        when(userRepo.existsByUsername("ALICE")).thenReturn(false);
        assertThat(service.isAccountGone("ALICE")).as("TTL içinde önbellek").isFalse();
        service.forgetUserCaches("alice");
        assertThat(service.isAccountGone("ALICE")).isTrue();
    }

    // ── AUTH-4: rol değişikliği ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AUTH-4: GERÇEK sistem rolü değişikliği → TERMINATED nöbetçisi (canlı oturum yeni rolle yeniden girer)")
    void roleChange_writesSentinel() {
        user(1L, "BOSS", "ADMIN", true, "LIVE-SID");
        service.updateUser(1L, null, null, null, "USER", null, null, null);

        ArgumentCaptor<AppUser> cap = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo, atLeastOnce()).save(cap.capture());
        AppUser saved = cap.getValue();
        assertThat(saved.getSystemRole()).isEqualTo("USER");
        assertThat(saved.getActiveSessionId()).startsWith(UserService.SESSION_TERMINATED_PREFIX);
        when(userRepo.findActiveSessionIdByUsername("BOSS")).thenReturn(Optional.of(saved.getActiveSessionId()));
        assertThat(service.isSessionSuperseded("BOSS", "LIVE-SID")).isTrue();
    }

    @Test
    @DisplayName("AUTH-4: aynı rol yeniden gönderilir ya da yalnız takım değişir → oturuma DOKUNULMAZ")
    void sameRoleOrTeamChange_keepsSession() {
        AppUser u = user(1L, "ALICE", "USER", true, "LIVE-SID");
        u.setTeamId(1L);
        service.updateUser(1L, "Alice", null, null, "USER", List.of(1L, 2L), null, null);
        assertThat(u.getActiveSessionId()).isEqualTo("LIVE-SID");
    }

    @Test
    @DisplayName("AUTH-4: PASİF hesabın rolü değişince nöbetçi yazılmaz (pasif kapısı zaten kesti; toplu geri alma işareti korunur)")
    void roleChangeOnPassive_keepsBulkMarker() {
        AppUser u = user(1L, "PASIF", "TEAM_ADMIN", false, "TERMINATED:bulk-op-7");
        service.updateUser(1L, null, null, null, "USER", null, null, null);
        assertThat(u.getActiveSessionId()).isEqualTo("TERMINATED:bulk-op-7");
    }

    // ── AUTH-7: kenar boşluklu parola ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AUTH-7: parola değişimi başında / sonunda boşlukla → 400 alan hatası 'password'; ortadaki boşluk serbest")
    void changePassword_edgeWhitespace_rejected() {
        AppUser u = user(1L, "ALICE", "USER", true, null);
        assertThatThrownBy(() -> service.changePassword(1L, "secret1 "))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class)
                .hasMessageContaining("boşluk");
        assertThatThrownBy(() -> service.changePassword(1L, " secret1"))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        assertThat(((GlobalExceptionHandler.FieldValidationException) catchFieldError(() -> service.changePassword(1L, "\tsecret1")))
                .getFields()).containsKey("password");
        service.changePassword(1L, "sec ret1");
        assertThat(u.getPasswordHash()).isNotNull();
    }

    @Test
    @DisplayName("AUTH-7: kullanıcı oluşturma da kenar boşluklu parolayı reddeder (giriş parolayı kırpar → hiç giremezdi)")
    void createUser_edgeWhitespace_rejected() {
        assertThatThrownBy(() -> service.createUser("NEWBIE", "secret1 ", null, "n@x.com", null, "USER", List.of(1L), null))
                .isInstanceOf(GlobalExceptionHandler.FieldValidationException.class);
        verify(userRepo, never()).save(any());
    }

    private static RuntimeException catchFieldError(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("hata bekleniyordu");
    }

    // ── ADMIN-11 / ADMIN-12: takım ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ADMIN-11: clearTeamLeader lideri kaldırır; lidersiz takımda yazma yok")
    void clearTeamLeader() {
        Team t = new Team(); t.setId(5L); t.setName("Ops"); t.setLeaderId(42L);
        when(teamRepo.findById(5L)).thenReturn(Optional.of(t));
        assertThat(service.clearTeamLeader(5L).getLeaderId()).isNull();
        verify(teamRepo, times(1)).save(t);
        service.clearTeamLeader(5L);
        verify(teamRepo, times(1)).save(t);   // ikinci çağrı yazmaz
    }

    @Test
    @DisplayName("ADMIN-12: takım oluşturma ad denetimi HARF DUYARSIZ — 'ops' varken 'Ops' 400")
    void createTeam_caseInsensitiveDuplicate() {
        when(teamRepo.existsByNameIgnoreCase("Ops")).thenReturn(true);
        assertThatThrownBy(() -> service.createTeam(" Ops ", "ops@x.com", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
        verify(teamRepo, never()).save(any());
        verify(teamRepo, never()).existsByName(anyString());
    }
}
