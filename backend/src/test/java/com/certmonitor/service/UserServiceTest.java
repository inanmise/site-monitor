package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock CertificateInventoryRepository inventoryRepo;

    private UserService service;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new UserService(userRepo, teamRepo, inventoryRepo);
        ReflectionTestUtils.setField(service, "lockoutDurationsSecs", List.of(30L, 120L, 600L, 1800L));
        ReflectionTestUtils.setField(service, "lockoutFailuresNeeded", List.of(5, 3, 2, 1));
        ReflectionTestUtils.setField(service, "passwordMinLength", 4);

        when(userRepo.save(any())).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            if (u.getId() == null) u.setId(99L);
            return u;
        });
        when(teamRepo.save(any())).thenAnswer(inv -> {
            Team t = inv.getArgument(0);
            if (t.getId() == null) t.setId(1L);
            return t;
        });
    }

    // ── failuresNeededForLevel ────────────────────────────────────────────────

    @Test
    @DisplayName("level 0 → 5 failures needed")
    void failuresNeededForLevel_level0_returns5() {
        assertThat(service.failuresNeededForLevel(0)).isEqualTo(5);
    }

    @Test
    @DisplayName("level 1 → 3 failures needed")
    void failuresNeededForLevel_level1_returns3() {
        assertThat(service.failuresNeededForLevel(1)).isEqualTo(3);
    }

    @Test
    @DisplayName("level 2 → 2 failures needed")
    void failuresNeededForLevel_level2_returns2() {
        assertThat(service.failuresNeededForLevel(2)).isEqualTo(2);
    }

    @Test
    @DisplayName("level 3 → 1 failure needed")
    void failuresNeededForLevel_level3_returns1() {
        assertThat(service.failuresNeededForLevel(3)).isEqualTo(1);
    }

    @Test
    @DisplayName("level beyond list → fallback 1")
    void failuresNeededForLevel_level4Plus_returns1() {
        assertThat(service.failuresNeededForLevel(4)).isEqualTo(1);
        assertThat(service.failuresNeededForLevel(10)).isEqualTo(1);
    }

    @Test
    @DisplayName("null input → treated as level 0 → 5")
    void failuresNeededForLevel_nullInput_treatsAsLevel0() {
        assertThat(service.failuresNeededForLevel(null)).isEqualTo(5);
    }

    // ── authenticate ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("correct password returns user")
    void authenticate_correctPassword_returnsUser() {
        String raw = "secret";
        AppUser user = user("alice", ENCODER.encode(raw));
        when(userRepo.findByUsernameAndActiveTrue("alice")).thenReturn(Optional.of(user));

        Optional<AppUser> result = service.authenticate("alice", raw);

        assertThat(result).isPresent();
        assertThat(result.get().getUsername()).isEqualTo("alice");
    }

    @Test
    @DisplayName("wrong password returns empty")
    void authenticate_wrongPassword_returnsEmpty() {
        AppUser user = user("alice", ENCODER.encode("correctpass"));
        when(userRepo.findByUsernameAndActiveTrue("alice")).thenReturn(Optional.of(user));

        assertThat(service.authenticate("alice", "wrongpass")).isEmpty();
    }

    @Test
    @DisplayName("unknown user returns empty")
    void authenticate_unknownUser_returnsEmpty() {
        when(userRepo.findByUsernameAndActiveTrue("nobody")).thenReturn(Optional.empty());
        assertThat(service.authenticate("nobody", "any")).isEmpty();
    }

    // ── checkLockout ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("no lockout record → not blocked")
    void checkLockout_noRecord_returnsUnblocked() {
        when(userRepo.findByUsername("alice")).thenReturn(Optional.empty());
        UserService.LockoutStatus status = service.checkLockout("alice");
        assertThat(status.isBlocked()).isFalse();
    }

    @Test
    @DisplayName("permanentLock=true → permanent block")
    void checkLockout_permanentLock_returnsBlocked() {
        AppUser u = user("alice", "hash");
        u.setPermanentLock(true);
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));

        UserService.LockoutStatus status = service.checkLockout("alice");
        assertThat(status.isBlocked()).isTrue();
        assertThat(status.permanent()).isTrue();
    }

    @Test
    @DisplayName("active temp lockout → blocked with remaining seconds")
    void checkLockout_activeTempLock_returnsBlocked() {
        AppUser u = user("alice", "hash");
        String future = ISO.format(Instant.now().plusSeconds(60));
        u.setLockoutUntil(future);
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));

        UserService.LockoutStatus status = service.checkLockout("alice");
        assertThat(status.isBlocked()).isTrue();
        assertThat(status.secondsRemaining()).isGreaterThan(0);
    }

    @Test
    @DisplayName("expired temp lockout → clears record and returns unblocked")
    void checkLockout_expiredTempLock_clearsAndReturnsUnblocked() {
        AppUser u = user("alice", "hash");
        String past = ISO.format(Instant.now().minusSeconds(5));
        u.setLockoutUntil(past);
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));

        UserService.LockoutStatus status = service.checkLockout("alice");
        assertThat(status.isBlocked()).isFalse();
        verify(userRepo).save(u);
        assertThat(u.getLockoutUntil()).isNull();
    }

    // ── applyProgressiveLockout ───────────────────────────────────────────────

    @Test
    @DisplayName("first offense → sets level 1 lockout ~30s")
    void applyProgressiveLockout_firstOffense_setsLevel1_30s() {
        AppUser u = user("alice", "hash");
        u.setFailedBlockCount(0);
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));

        UserService.LockoutStatus status = service.applyProgressiveLockout("alice");
        assertThat(status.isBlocked()).isTrue();
        assertThat(status.secondsRemaining()).isBetween(25L, 35L);
        assertThat(u.getFailedBlockCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("fourth offense → permanent lock")
    void applyProgressiveLockout_fourthOffense_setsPermanentLock() {
        AppUser u = user("alice", "hash");
        u.setFailedBlockCount(4);
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));

        UserService.LockoutStatus status = service.applyProgressiveLockout("alice");
        assertThat(status.permanent()).isTrue();
        assertThat(u.getPermanentLock()).isTrue();
    }

    @Test
    @DisplayName("unknown user → returns unblocked (no save)")
    void applyProgressiveLockout_unknownUser_returnsUnblocked() {
        when(userRepo.findByUsername("ghost")).thenReturn(Optional.empty());

        UserService.LockoutStatus status = service.applyProgressiveLockout("ghost");
        assertThat(status.isBlocked()).isFalse();
        verify(userRepo, never()).save(any());
    }

    // ── clearLockoutOnSuccess ─────────────────────────────────────────────────

    @Test
    @DisplayName("user with lockout → all fields reset and saved")
    void clearLockoutOnSuccess_userHasLockout_resetsAllFields() {
        AppUser u = user("alice", "hash");
        u.setLockoutUntil(ISO.format(Instant.now().plusSeconds(30)));
        u.setFailedBlockCount(2);
        u.setLastLockoutAt(ISO.format(Instant.now().minusSeconds(60)));
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));

        service.clearLockoutOnSuccess("alice");

        assertThat(u.getLockoutUntil()).isNull();
        assertThat(u.getFailedBlockCount()).isEqualTo(0);
        assertThat(u.getLastLockoutAt()).isNull();
        verify(userRepo).save(u);
    }

    @Test
    @DisplayName("already clean user → save NOT called")
    void clearLockoutOnSuccess_alreadyClean_doesNotSave() {
        AppUser u = user("alice", "hash");
        u.setLockoutUntil(null);
        u.setFailedBlockCount(0);
        u.setLastLockoutAt(null);
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));

        service.clearLockoutOnSuccess("alice");

        verify(userRepo, never()).save(any());
    }

    // ── unlockUser ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unlockUser clears all lockout fields")
    void unlockUser_clearsAllLockoutFields() {
        AppUser u = user("alice", "hash");
        u.setId(1L);
        u.setPermanentLock(true);
        u.setLockoutUntil(ISO.format(Instant.now().plusSeconds(3600)));
        u.setFailedBlockCount(5);
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        service.unlockUser(1L);

        assertThat(u.getPermanentLock()).isFalse();
        assertThat(u.getLockoutUntil()).isNull();
        assertThat(u.getFailedBlockCount()).isEqualTo(0);
        assertThat(u.getLastLockoutAt()).isNull();
        verify(userRepo).save(u);
    }

    @Test
    @DisplayName("unlockUser: user not found → NoSuchElementException")
    void unlockUser_notFound_throwsNoSuchElement() {
        when(userRepo.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.unlockUser(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

    // ── Team CRUD ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createTeam: valid inputs → saves team")
    void createTeam_validInputs_savesTeam() {
        when(teamRepo.existsByName("Alpha")).thenReturn(false);
        when(userRepo.existsById(1L)).thenReturn(true);

        Team result = service.createTeam("Alpha", "alpha@example.com", "Desc", 1L);

        assertThat(result.getName()).isEqualTo("Alpha");
        assertThat(result.getEmail()).isEqualTo("alpha@example.com");
        verify(teamRepo).save(any(Team.class));
    }

    @Test
    @DisplayName("createTeam: blank name → IllegalArgumentException")
    void createTeam_blankName_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.createTeam("  ", "a@b.com", null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("createTeam: blank email → IllegalArgumentException")
    void createTeam_blankEmail_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.createTeam("Alpha", "", null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    @DisplayName("createTeam: null leaderId → IllegalArgumentException")
    void createTeam_nullLeader_throwsIllegalArgument() {
        when(teamRepo.existsByName("Alpha")).thenReturn(false);
        assertThatThrownBy(() -> service.createTeam("Alpha", "a@b.com", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leader");
    }

    @Test
    @DisplayName("createTeam: duplicate name → IllegalArgumentException")
    void createTeam_duplicateName_throwsIllegalArgument() {
        when(teamRepo.existsByName("Alpha")).thenReturn(true);
        assertThatThrownBy(() -> service.createTeam("Alpha", "a@b.com", null, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("createTeam: leader not found → IllegalArgumentException")
    void createTeam_leaderNotFound_throwsIllegalArgument() {
        when(teamRepo.existsByName("Alpha")).thenReturn(false);
        when(userRepo.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> service.createTeam("Alpha", "a@b.com", null, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Leader user not found");
    }

    @Test
    @DisplayName("updateTeam: changes name → saves updated team")
    void updateTeam_changesName_savesUpdatedTeam() {
        Team t = team(2L, "OldName");
        t.setEmail("existing@example.com");
        t.setLeaderId(5L);
        when(teamRepo.findById(2L)).thenReturn(Optional.of(t));

        service.updateTeam(2L, "NewName", null, null, null, null);

        assertThat(t.getName()).isEqualTo("NewName");
        verify(teamRepo).save(t);
    }

    @Test
    @DisplayName("updateTeam: null leaderId keeps previous leader")
    void updateTeam_nullLeaderId_keepsPreviousLeader() {
        Team t = team(2L, "Alpha");
        t.setEmail("e@e.com");
        t.setLeaderId(5L);
        when(teamRepo.findById(2L)).thenReturn(Optional.of(t));

        service.updateTeam(2L, null, null, null, null, null);

        assertThat(t.getLeaderId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("updateTeam: not found → NoSuchElementException")
    void updateTeam_notFound_throwsNoSuchElement() {
        when(teamRepo.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateTeam(999L, "X", "x@x.com", null, null, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("deleteTeam: no certs → deletes successfully")
    void deleteTeam_noCerts_deletesSuccessfully() {
        when(inventoryRepo.existsByTeamIdAndActiveTrueAndDeletedAtIsNull(3L)).thenReturn(false);
        service.deleteTeam(3L);
        verify(teamRepo).deleteById(3L);
    }

    @Test
    @DisplayName("deleteTeam: has active certs → IllegalStateException")
    void deleteTeam_hasCerts_throwsIllegalState() {
        when(inventoryRepo.existsByTeamIdAndActiveTrueAndDeletedAtIsNull(3L)).thenReturn(true);
        assertThatThrownBy(() -> service.deleteTeam(3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active certificates");
    }

    // ── User CRUD ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createUser: valid inputs → BCrypt-hashes password")
    void createUser_validInputs_bcryptHashesPassword() {
        when(userRepo.existsByUsername("alice")).thenReturn(false);
        String raw = "pass1234";

        AppUser result = service.createUser("alice", raw, "Alice", "alice@example.com", null, "USER", 1L, null);

        assertThat(result.getPasswordHash()).isNotEqualTo(raw);
        assertThat(ENCODER.matches(raw, result.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("createUser: blank username → IllegalArgumentException")
    void createUser_blankUsername_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.createUser("  ", "pass1234", "D", "e@e.com", null, "USER", 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username");
    }

    @Test
    @DisplayName("createUser: password shorter than minLength → IllegalArgumentException")
    void createUser_shortPassword_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.createUser("alice", "ab", "D", "e@e.com", null, "USER", 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
    }

    @Test
    @DisplayName("createUser: duplicate username → IllegalArgumentException")
    void createUser_duplicateUsername_throwsIllegalArgument() {
        when(userRepo.existsByUsername("alice")).thenReturn(true);
        assertThatThrownBy(() -> service.createUser("alice", "pass1234", "D", "e@e.com", null, "USER", 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("createUser: valid orgRole → field set on saved user")
    void createUser_withValidOrgRole_setsField() {
        when(userRepo.existsByUsername("bob")).thenReturn(false);
        AppUser result = service.createUser("bob", "pass1234", "Bob", "bob@example.com", null, "USER", 1L, "PO");
        assertThat(result.getOrgRole()).isEqualTo("PO");
    }

    @Test
    @DisplayName("createUser: blank orgRole → stored as null")
    void createUser_blankOrgRole_storesNull() {
        when(userRepo.existsByUsername("carol")).thenReturn(false);
        AppUser result = service.createUser("carol", "pass1234", "Carol", "carol@example.com", null, "USER", 1L, "  ");
        assertThat(result.getOrgRole()).isNull();
    }

    @Test
    @DisplayName("updateUser: changes displayName → saves updated user")
    void updateUser_changesDisplayName_savesUpdated() {
        AppUser u = user("alice", "hash");
        u.setId(1L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        service.updateUser(1L, "Alice Smith", null, null, null, null, null, null);

        assertThat(u.getDisplayName()).isEqualTo("Alice Smith");
        verify(userRepo).save(u);
    }

    @Test
    @DisplayName("updateUser: not found → NoSuchElementException")
    void updateUser_notFound_throwsNoSuchElement() {
        when(userRepo.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateUser(999L, "X", null, null, null, null, null, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("updateUser: valid orgRole → field updated")
    void updateUser_changesOrgRole_savesUpdated() {
        AppUser u = user("alice", "hash");
        u.setId(1L);
        u.setOrgRole("TECH");
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        service.updateUser(1L, null, null, null, null, null, null, "MANAGER");

        assertThat(u.getOrgRole()).isEqualTo("MANAGER");
        verify(userRepo).save(u);
    }

    @Test
    @DisplayName("updateUser: null orgRole → clears field")
    void updateUser_nullOrgRole_clearsField() {
        AppUser u = user("alice", "hash");
        u.setId(1L);
        u.setOrgRole("CLEVEL");
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        service.updateUser(1L, null, null, null, null, null, null, null);

        assertThat(u.getOrgRole()).isNull();
        verify(userRepo).save(u);
    }

    @Test
    @DisplayName("changePassword: too short → IllegalArgumentException")
    void changePassword_tooShort_throwsIllegalArgument() {
        AppUser u = user("alice", "hash");
        u.setId(1L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> service.changePassword(1L, "ab"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ensureBootstrapped: no users → creates team and admin")
    void ensureBootstrapped_noUsers_createsTeamAndAdmin() {
        when(userRepo.count()).thenReturn(0L);
        when(teamRepo.findByName("General")).thenReturn(Optional.empty());

        service.ensureBootstrapped("admin", "adminpass");

        verify(userRepo, atLeastOnce()).save(any(AppUser.class));
    }

    @Test
    @DisplayName("ensureBootstrapped: users exist → does nothing")
    void ensureBootstrapped_usersExist_doesNothing() {
        when(userRepo.count()).thenReturn(1L);

        service.ensureBootstrapped("admin", "adminpass");

        verify(userRepo, never()).save(any(AppUser.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AppUser user(String username, String hash) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(hash);
        u.setActive(true);
        u.setFailedBlockCount(0);
        u.setPermanentLock(false);
        return u;
    }

    private Team team(Long id, String name) {
        Team t = new Team();
        t.setId(id);
        t.setName(name);
        t.setActive(true);
        return t;
    }
}
