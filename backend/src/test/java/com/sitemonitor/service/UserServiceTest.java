package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.TeamRepository;
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
    @Mock EscalationContactRepository contactRepo;
    @Mock com.sitemonitor.repository.PasswordHistoryRepository passwordHistoryRepo;

    private UserService service;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new UserService(userRepo, teamRepo, inventoryRepo, contactRepo, passwordHistoryRepo);
        when(contactRepo.findByUserId(anyLong())).thenReturn(List.of());
        when(userRepo.existsByTeamId(anyLong())).thenReturn(false);
        when(contactRepo.existsByTeamIdAndActiveTrue(anyLong())).thenReturn(false);
        org.mockito.Mockito.lenient().when(passwordHistoryRepo.findByUserIdOrderByCreatedAtDesc(anyLong()))
                .thenReturn(java.util.Collections.emptyList());
        ReflectionTestUtils.setField(service, "lockoutDurationsSecs", List.of(30L, 120L, 600L, 1800L));
        ReflectionTestUtils.setField(service, "lockoutFailuresNeeded", List.of(5, 3, 2, 1));
        ReflectionTestUtils.setField(service, "passwordMinLength", 6);
        ReflectionTestUtils.setField(service, "passwordMaxLength", 10);
        ReflectionTestUtils.setField(service, "passwordHistoryCount", 3);

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

    @Test
    @DisplayName("authenticate: LDAP user (null password hash) never matches local auth")
    void authenticate_nullHash_returnsEmpty() {
        AppUser ldapUser = user("ldapuser", null);
        when(userRepo.findByUsernameAndActiveTrue("ldapuser")).thenReturn(Optional.of(ldapUser));
        assertThat(service.authenticate("ldapuser", "anything")).isEmpty();
    }

    // ── provisionLdapUser ─────────────────────────────────────────────────────

    @Test
    @DisplayName("provisionLdapUser: new AD user → USER, no team, NULL password, authSource LDAP")
    void provisionLdapUser_new_noPasswordStored() {
        when(userRepo.findByUsername("N34567")).thenReturn(Optional.empty());

        AppUser u = service.provisionLdapUser("n34567", "Erdi İnanmış", "erdi@example.com");

        assertThat(u.getUsername()).isEqualTo("N34567");   // username HER ZAMAN büyük harf
        assertThat(u.getSystemRole()).isEqualTo("USER");
        assertThat(u.getTeamId()).isNull();
        assertThat(u.getAuthSource()).isEqualTo("LDAP");
        assertThat(u.getPasswordHash()).isNull();   // no app password for LDAP users
        assertThat(u.getDisplayName()).isEqualTo("Erdi İnanmış");
        assertThat(u.getEmail()).isEqualTo("erdi@example.com");
    }

    @Test
    @DisplayName("normalizeUsername: trim + ASCII büyük harf (Locale.ROOT — 'i'→'I', Türkçe 'İ' DEĞİL)")
    void normalizeUsername_uppercaseAsciiSafe() {
        assertThat(UserService.normalizeUsername("  n12345 ")).isEqualTo("N12345");
        assertThat(UserService.normalizeUsername("Admin")).isEqualTo("ADMIN");   // 'i' → ASCII 'I', 'İ' DEĞİL
        assertThat(UserService.normalizeUsername(null)).isNull();
    }

    @Test
    @DisplayName("provisionLdapUser: existing user → returned and display/email refreshed, still no password")
    void provisionLdapUser_existing_refreshes() {
        AppUser existing = user("n34567", null);
        existing.setAuthSource("LDAP");
        existing.setSystemRole("USER");
        existing.setDisplayName("Old Name");
        when(userRepo.findByUsername("n34567")).thenReturn(Optional.of(existing));

        AppUser u = service.provisionLdapUser("n34567", "New Name", "new@example.com");

        assertThat(u.getDisplayName()).isEqualTo("New Name");
        assertThat(u.getEmail()).isEqualTo("new@example.com");
        assertThat(u.getPasswordHash()).isNull();
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
        // Haftalık e-postalar opt-in: yeni takım İKİSİ DE KAPALI doğar (mevcut takımlar açılış
        // yamasında TRUE'ya çekilir, bu yüzden varsayılan burada açıkça sabitleniyor).
        assertThat(result.getWeeklyReminderEnabled()).isFalse();
        assertThat(result.getWeeklyAvailabilityEnabled()).isFalse();
        verify(teamRepo).save(any(Team.class));
    }

    @Test
    @DisplayName("updateTeamWeeklyNotifications: yalnız verilen anahtarı çevirir, diğer alanlara dokunmaz")
    void updateTeamWeeklyNotifications_touchesOnlyGivenSwitch() {
        Team existing = new Team();
        existing.setId(2L); existing.setName("Dijital"); existing.setEmail("d@x.com"); existing.setActive(true);
        existing.setWeeklyReminderEnabled(false); existing.setWeeklyAvailabilityEnabled(true);
        when(teamRepo.findById(2L)).thenReturn(Optional.of(existing));   // save stub'ı setUp'ta (entity'yi yankılar)

        Team out = service.updateTeamWeeklyNotifications(2L, true, null);   // null = dokunma

        assertThat(out.getWeeklyReminderEnabled()).isTrue();
        assertThat(out.getWeeklyAvailabilityEnabled()).isTrue();   // null geçildi → değişmedi
        assertThat(out.getName()).isEqualTo("Dijital");            // yönetici alanları korunur
        assertThat(out.getActive()).isTrue();
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
    @DisplayName("createTeam: null leaderId is now allowed (PO optional)")
    void createTeam_nullLeader_isAllowed() {
        when(teamRepo.existsByName("Alpha")).thenReturn(false);
        Team result = service.createTeam("Alpha", "a@b.com", null, null);
        assertThat(result.getName()).isEqualTo("Alpha");
        assertThat(result.getLeaderId()).isNull();
        verify(teamRepo).save(any(Team.class));
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

        service.updateTeam(2L, "NewName", null, null, null, null, null, null);

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

        service.updateTeam(2L, null, null, null, null, null, null, null);

        assertThat(t.getLeaderId()).isEqualTo(5L);
    }

    @Test
    @DisplayName("updateTeam: not found → NoSuchElementException")
    void updateTeam_notFound_throwsNoSuchElement() {
        when(teamRepo.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateTeam(999L, "X", "x@x.com", null, null, null, null, null))
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
                .hasMessageContaining("sertifika");
    }

    @Test
    @DisplayName("createUser: PO + takım → kullanıcı otomatik takım lideri olur")
    void createUser_po_becomesTeamLeader() {
        Team team = team(5L, "Payments");
        when(teamRepo.findById(5L)).thenReturn(Optional.of(team));
        when(userRepo.existsByUsername(anyString())).thenReturn(false);

        AppUser u = service.createUser("po1", "secret1", "PO Bir", "po@x.com", null, "TEAM_ADMIN", List.of(5L), "PO");

        ArgumentCaptor<Team> cap = ArgumentCaptor.forClass(Team.class);
        verify(teamRepo).save(cap.capture());
        assertThat(cap.getValue().getLeaderId()).isEqualTo(u.getId());
    }

    @Test
    @DisplayName("updateUser: PO + takım → kullanıcı otomatik takım lideri olur")
    void updateUser_po_becomesTeamLeader() {
        AppUser existing = user("po2", "hash");
        existing.setId(42L);
        when(userRepo.findById(42L)).thenReturn(Optional.of(existing));
        when(teamRepo.findById(6L)).thenReturn(Optional.of(team(6L, "Cards")));

        service.updateUser(42L, "PO Iki", "po2@x.com", null, "TEAM_ADMIN", List.of(6L), true, "PO");

        ArgumentCaptor<Team> cap = ArgumentCaptor.forClass(Team.class);
        verify(teamRepo).save(cap.capture());
        assertThat(cap.getValue().getLeaderId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("createUser: PO değilse takım lideri atanmaz")
    void createUser_nonPo_noLeaderChange() {
        when(userRepo.existsByUsername(anyString())).thenReturn(false);

        service.createUser("u1", "secret1", "U Bir", "u@x.com", null, "USER", List.of(5L), null);

        verify(teamRepo, never()).save(any());
    }

    @Test
    @DisplayName("createUser: PO ama takımın (gerçek) lideri zaten varsa ezilmez")
    void createUser_po_doesNotOverrideExistingLeader() {
        Team team = team(5L, "Payments");
        team.setLeaderId(77L);   // mevcut, gerçek lider
        when(teamRepo.findById(5L)).thenReturn(Optional.of(team));
        when(userRepo.existsById(77L)).thenReturn(true);   // lider hâlâ var
        when(userRepo.existsByUsername(anyString())).thenReturn(false);

        service.createUser("po1", "secret1", "PO Bir", "po@x.com", null, "TEAM_ADMIN", List.of(5L), "PO");

        verify(teamRepo, never()).save(any());
        assertThat(team.getLeaderId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("createUser: PO, takımın lideri silinmiş (dangling) ise yeni PO lider atanır")
    void createUser_po_replacesDanglingLeader() {
        Team team = team(5L, "Payments");
        team.setLeaderId(77L);   // silinmiş kullanıcıya işaret eden dangling id
        when(teamRepo.findById(5L)).thenReturn(Optional.of(team));
        when(userRepo.existsById(77L)).thenReturn(false);  // lider artık yok
        when(userRepo.existsByUsername(anyString())).thenReturn(false);

        AppUser u = service.createUser("po2", "secret1", "PO İki", "po2@x.com", null, "TEAM_ADMIN", List.of(5L), "PO");

        ArgumentCaptor<Team> cap = ArgumentCaptor.forClass(Team.class);
        verify(teamRepo).save(cap.capture());
        assertThat(cap.getValue().getLeaderId()).isEqualTo(u.getId());
    }

    @Test
    @DisplayName("deleteUser: kullanıcı bir takımın lideriyse o takımın liderliği boşaltılır")
    void deleteUser_clearsTeamLeadership() {
        Team led = team(5L, "Payments");
        led.setLeaderId(42L);
        when(teamRepo.findByLeaderId(42L)).thenReturn(List.of(led));

        service.deleteUser(42L);

        ArgumentCaptor<Team> cap = ArgumentCaptor.forClass(Team.class);
        verify(teamRepo).save(cap.capture());
        assertThat(cap.getValue().getLeaderId()).isNull();
        verify(userRepo).deleteById(42L);
    }

    // ── User CRUD ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createUser: valid inputs → BCrypt-hashes password")
    void createUser_validInputs_bcryptHashesPassword() {
        when(userRepo.existsByUsername("alice")).thenReturn(false);
        String raw = "pass1234";

        AppUser result = service.createUser("alice", raw, "Alice", "alice@example.com", null, "USER", List.of(1L), null);

        assertThat(result.getPasswordHash()).isNotEqualTo(raw);
        assertThat(ENCODER.matches(raw, result.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("createUser: blank username → IllegalArgumentException")
    void createUser_blankUsername_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.createUser("  ", "pass1234", "D", "e@e.com", null, "USER", List.of(1L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username");
    }

    @Test
    @DisplayName("createUser: password shorter than minLength → IllegalArgumentException")
    void createUser_shortPassword_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.createUser("alice", "ab", "D", "e@e.com", null, "USER", List.of(1L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too short");
    }

    @Test
    @DisplayName("createUser: duplicate username → IllegalArgumentException")
    void createUser_duplicateUsername_throwsIllegalArgument() {
        when(userRepo.existsByUsername("ALICE")).thenReturn(true);   // createUser username'i normalize eder (BÜYÜK)
        assertThatThrownBy(() -> service.createUser("alice", "pass1234", "D", "e@e.com", null, "USER", List.of(1L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    @DisplayName("createUser: valid orgRole → field set on saved user")
    void createUser_withValidOrgRole_setsField() {
        when(userRepo.existsByUsername("bob")).thenReturn(false);
        AppUser result = service.createUser("bob", "pass1234", "Bob", "bob@example.com", null, "USER", List.of(1L), "PO");
        assertThat(result.getOrgRole()).isEqualTo("PO");
        assertThat(result.getOrgRoleLocked()).isTrue();   // admin oluşturdu → org rol kilitli
    }

    @Test
    @DisplayName("createUser: blank orgRole → stored as null")
    void createUser_blankOrgRole_storesNull() {
        when(userRepo.existsByUsername("carol")).thenReturn(false);
        AppUser result = service.createUser("carol", "pass1234", "Carol", "carol@example.com", null, "USER", List.of(1L), "  ");
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
        assertThat(u.getOrgRoleLocked()).isTrue();        // TECH→MANAGER değişikliği → kilitle
        verify(userRepo).save(u);
    }

    @Test
    @DisplayName("updateUser: orgRole DEĞİŞMEZSE org rol kilidi konmaz")
    void updateUser_orgRoleUnchanged_doesNotLock() {
        AppUser u = user("alice", "hash");
        u.setId(1L);
        u.setOrgRole("MANAGER");
        u.setOrgRoleLocked(null);
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        service.updateUser(1L, null, null, null, null, null, null, "MANAGER");   // aynı değer

        assertThat(u.getOrgRole()).isEqualTo("MANAGER");
        assertThat(u.getOrgRoleLocked()).isNull();        // değişmedi → kilitlenmedi
    }

    @Test
    @DisplayName("unlockOrgRole: org rol kilidini kaldırır (AD yönetimine döner)")
    void unlockOrgRole_clearsLock() {
        AppUser u = user("alice", "hash");
        u.setId(1L);
        u.setOrgRoleLocked(true);
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        service.unlockOrgRole(1L);

        assertThat(u.getOrgRoleLocked()).isFalse();
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

    @Test
    @DisplayName("changePassword (step-up): wrong admin password → SecurityException, target not modified")
    void changePassword_stepUp_wrongAdminPass_throwsSecurity() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        AppUser admin = user("admin", enc.encode("rightpass"));
        admin.setId(1L);
        when(userRepo.findByUsernameAndActiveTrue("admin")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.changePassword(7L, "newSecret", "admin", "wrongpass"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Invalid admin password");
        verify(userRepo, never()).findById(7L);
    }

    @Test
    @DisplayName("changePassword (step-up): correct admin password → target password updated")
    void changePassword_stepUp_correctAdminPass_updatesTarget() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        AppUser admin = user("admin", enc.encode("rightpass"));
        admin.setId(1L);
        AppUser target = user("bob", "old-hash");
        target.setId(7L);
        when(userRepo.findByUsernameAndActiveTrue("admin")).thenReturn(Optional.of(admin));
        when(userRepo.findById(7L)).thenReturn(Optional.of(target));

        service.changePassword(7L, "newSecret", "admin", "rightpass");

        verify(userRepo).save(target);
        assertThat(target.getPasswordHash()).isNotEqualTo("old-hash");
    }

    @Test
    @DisplayName("changePassword (step-up): missing admin password → SecurityException")
    void changePassword_stepUp_missingAdminPass_throwsSecurity() {
        assertThatThrownBy(() -> service.changePassword(7L, "newSecret", "admin", ""))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> service.changePassword(7L, "newSecret", "admin", null))
                .isInstanceOf(SecurityException.class);
        verify(userRepo, never()).findById(any());
    }

    @Test
    @DisplayName("changePassword: too long (>10) → IllegalArgumentException")
    void changePassword_tooLong_throwsIllegalArgument() {
        AppUser u = user("alice", "hash");
        u.setId(1L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> service.changePassword(1L, "12345678901"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too long");
    }

    @Test
    @DisplayName("changePassword: matches current hash → 'recently used'")
    void changePassword_matchesCurrentHash_throwsReused() {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        AppUser u = user("alice", enc.encode("secret1"));
        u.setId(1L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> service.changePassword(1L, "secret1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recently used");
    }

    @Test
    @DisplayName("changePassword: matches an archived hash → 'recently used'")
    void changePassword_matchesArchivedHash_throwsReused() {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        AppUser u = user("alice", enc.encode("currentPwd"));
        u.setId(1L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        com.sitemonitor.model.PasswordHistory hist = new com.sitemonitor.model.PasswordHistory();
        hist.setUserId(1L);
        hist.setPasswordHash(enc.encode("oldPwd1"));
        when(passwordHistoryRepo.findByUserIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(hist));

        assertThatThrownBy(() -> service.changePassword(1L, "oldPwd1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recently used");
    }

    @Test
    @DisplayName("changePassword: success archives outgoing hash + rotates current")
    void changePassword_success_archivesAndRotates() {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        AppUser u = user("alice", enc.encode("oldPwd"));
        u.setId(1L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        service.changePassword(1L, "newPwd1");

        verify(passwordHistoryRepo).save(any(com.sitemonitor.model.PasswordHistory.class));
        assertThat(u.getPasswordHash()).isNotEqualTo(enc.encode("oldPwd"));
        assertThat(enc.matches("newPwd1", u.getPasswordHash())).isTrue();
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("adminAutoResetPassword: wrong admin password → SecurityException")
    void adminAutoResetPassword_wrongAdminPass_throwsSecurity() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        AppUser admin = user("admin", enc.encode("rightpass"));
        admin.setId(1L);
        when(userRepo.findByUsernameAndActiveTrue("admin")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.adminAutoResetPassword(7L, "admin", "wrongpass"))
                .isInstanceOf(SecurityException.class);
        verify(userRepo, never()).findById(7L);
    }

    @Test
    @DisplayName("adminAutoResetPassword: target without email → IllegalArgumentException")
    void adminAutoResetPassword_targetWithoutEmail_throwsIllegalArgument() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        AppUser admin = user("admin", enc.encode("rightpass"));
        when(userRepo.findByUsernameAndActiveTrue("admin")).thenReturn(Optional.of(admin));
        AppUser target = user("bob", "old-hash");
        target.setId(7L);
        target.setEmail(null);
        when(userRepo.findById(7L)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.adminAutoResetPassword(7L, "admin", "rightpass"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");
    }

    @Test
    @DisplayName("adminAutoResetPassword: success rewrites hash + sets mustChangePassword + returns 10-char temp pwd")
    void adminAutoResetPassword_success_setsForcedChange() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        AppUser admin = user("admin", enc.encode("rightpass"));
        when(userRepo.findByUsernameAndActiveTrue("admin")).thenReturn(Optional.of(admin));
        AppUser target = user("bob", "old-hash");
        target.setId(7L);
        target.setEmail("bob@example.com");
        when(userRepo.findById(7L)).thenReturn(Optional.of(target));

        String temp = service.adminAutoResetPassword(7L, "admin", "rightpass");

        assertThat(temp).hasSize(10);
        assertThat(target.getPasswordHash()).isNotEqualTo("old-hash");
        assertThat(target.getMustChangePassword()).isTrue();
        verify(passwordHistoryRepo).save(any(com.sitemonitor.model.PasswordHistory.class));
    }

    @Test
    @DisplayName("adminAutoResetPassword: sets temp_password_expires_at to roughly now+24h")
    void adminAutoResetPassword_setsTempPasswordExpiry() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        AppUser admin = user("admin", enc.encode("rightpass"));
        when(userRepo.findByUsernameAndActiveTrue("admin")).thenReturn(Optional.of(admin));
        AppUser target = user("bob", "old-hash");
        target.setId(7L);
        target.setEmail("bob@example.com");
        when(userRepo.findById(7L)).thenReturn(Optional.of(target));

        java.time.Instant before = java.time.Instant.now();
        service.adminAutoResetPassword(7L, "admin", "rightpass");
        java.time.Instant after = java.time.Instant.now();

        assertThat(target.getTempPasswordExpiresAt()).isNotNull();
        java.time.Instant exp = java.time.LocalDateTime.parse(target.getTempPasswordExpiresAt())
                .toInstant(java.time.ZoneOffset.UTC);
        assertThat(exp).isAfterOrEqualTo(before.plus(java.time.Duration.ofHours(24)).minusSeconds(2));
        assertThat(exp).isBeforeOrEqualTo(after.plus(java.time.Duration.ofHours(24)).plusSeconds(2));
    }

    @Test
    @DisplayName("changePassword: clears temp_password_expires_at on success")
    void changePassword_clearsTempPasswordExpiry() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        AppUser u = user("alice", enc.encode("oldPwd"));
        u.setId(1L);
        u.setTempPasswordExpiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                .plusHours(12).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        service.changePassword(1L, "newPwd1");

        assertThat(u.getTempPasswordExpiresAt()).isNull();
    }

    @Test
    @DisplayName("isTempPasswordExpired: true when stored timestamp is in the past")
    void isTempPasswordExpired_pastTimestamp_returnsTrue() {
        AppUser u = user("alice", "h");
        u.setTempPasswordExpiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                .minusHours(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
        assertThat(service.isTempPasswordExpired(u)).isTrue();
    }

    @Test
    @DisplayName("isTempPasswordExpired: false when stored timestamp is null or in the future")
    void isTempPasswordExpired_nullOrFuture_returnsFalse() {
        AppUser noTemp = user("alice", "h");
        noTemp.setTempPasswordExpiresAt(null);
        assertThat(service.isTempPasswordExpired(noTemp)).isFalse();

        AppUser futureTemp = user("bob", "h");
        futureTemp.setTempPasswordExpiresAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                .plusHours(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
        assertThat(service.isTempPasswordExpired(futureTemp)).isFalse();
    }

    @Test
    @DisplayName("changePassword: clears mustChangePassword flag on success")
    void changePassword_clearsMustChangeFlag() {
        org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder enc =
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        AppUser u = user("alice", enc.encode("oldPwd"));
        u.setId(1L);
        u.setMustChangePassword(true);
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));

        service.changePassword(1L, "newPwd1");

        assertThat(u.getMustChangePassword()).isFalse();
    }

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

    // ── Faz 3b: computeViewTeamIds / computeManageTeamIds ─────────────────────

    private AppUser scopeUser(Long id, String systemRole, String authSource, Long teamId) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername("u" + id);
        u.setSystemRole(systemRole);
        u.setAuthSource(authSource);
        u.setTeamId(teamId);
        return u;
    }

    @Test
    @DisplayName("computeViewTeamIds: AUDIT → null (system-wide read)")
    void computeViewTeamIds_audit_null() {
        assertThat(service.computeViewTeamIds(scopeUser(1L, "AUDIT", "LOCAL", 5L))).isNull();
    }

    @Test
    @DisplayName("computeViewTeamIds: local/bootstrap ADMIN → null (global)")
    void computeViewTeamIds_localAdmin_null() {
        assertThat(service.computeViewTeamIds(scopeUser(1L, "ADMIN", "LOCAL", 5L))).isNull();
    }

    @Test
    @DisplayName("computeViewTeamIds: AD ADMIN (müdür) → subordinates' distinct teams, not global")
    void computeViewTeamIds_adAdmin_subordinateTeams() {
        AppUser mudur = scopeUser(10L, "ADMIN", "LDAP", 1L);
        when(userRepo.findByManagerId(10L)).thenReturn(List.of(
                scopeUser(20L, "USER", "LDAP", 3L),
                scopeUser(21L, "USER", "LDAP", 3L),  // duplicate team → deduped
                scopeUser(22L, "USER", "LDAP", 7L),
                scopeUser(23L, "USER", "LDAP", null) // no team → ignored
        ));
        assertThat(service.computeViewTeamIds(mudur)).containsExactly(3L, 7L);
    }

    @Test
    @DisplayName("computeViewTeamIds: TEAM_ADMIN (PO) → led teams ∪ own")
    void computeViewTeamIds_teamAdmin_ledPlusOwn() {
        AppUser po = scopeUser(30L, "TEAM_ADMIN", "LDAP", 2L);
        when(teamRepo.findByLeaderId(30L)).thenReturn(List.of(team(4L, "A"), team(5L, "B")));
        assertThat(service.computeViewTeamIds(po)).containsExactly(4L, 5L, 2L);
    }

    @Test
    @DisplayName("computeViewTeamIds: USER → only own team")
    void computeViewTeamIds_user_ownOnly() {
        assertThat(service.computeViewTeamIds(scopeUser(40L, "USER", "LDAP", 9L))).containsExactly(9L);
    }

    @Test
    @DisplayName("computeManageTeamIds: local ADMIN → null (global manage)")
    void computeManageTeamIds_localAdmin_null() {
        assertThat(service.computeManageTeamIds(scopeUser(1L, "ADMIN", "LOCAL", 5L))).isNull();
    }

    @Test
    @DisplayName("computeManageTeamIds: müdür (AD ADMIN) → empty (read-only)")
    void computeManageTeamIds_mudur_empty() {
        assertThat(service.computeManageTeamIds(scopeUser(10L, "ADMIN", "LDAP", 1L))).isEmpty();
    }

    @Test
    @DisplayName("computeManageTeamIds: TEAM_ADMIN (PO) → led teams ∪ own")
    void computeManageTeamIds_teamAdmin_ledPlusOwn() {
        AppUser po = scopeUser(30L, "TEAM_ADMIN", "LDAP", 2L);
        when(teamRepo.findByLeaderId(30L)).thenReturn(List.of(team(4L, "A")));
        assertThat(service.computeManageTeamIds(po)).containsExactly(4L, 2L);
    }

    @Test
    @DisplayName("computeManageTeamIds: USER → empty (no management)")
    void computeManageTeamIds_user_empty() {
        assertThat(service.computeManageTeamIds(scopeUser(40L, "USER", "LDAP", 9L))).isEmpty();
    }

    // ── applyProfileFields (AD-mirrored profil alanları) ──────────────────────

    @Test
    @DisplayName("applyProfileFields: gönderilen alanları set eder, boşları null'lar, eksikleri atlamaz")
    void applyProfileFields_setsPresentKeys() {
        AppUser u = new AppUser();
        u.setTitle("ESKI ÜNVAN");          // body'de title yoksa korunur
        Map<String, Object> body = new HashMap<>();
        body.put("first_name", "Erdi");
        body.put("last_name", "İnanmış");
        body.put("phone", "  +90 532  ");  // trim
        body.put("department", "TEKNOLOJİ");
        body.put("company_level", "Uzman");
        body.put("mudurluk_name", "TEKN.MİM.");
        body.put("manager_sicil", "99999");
        body.put("email", "x@y.com");      // profil alanı değil → yok sayılır

        service.applyProfileFields(u, body);

        assertThat(u.getFirstName()).isEqualTo("Erdi");
        assertThat(u.getLastName()).isEqualTo("İnanmış");
        assertThat(u.getPhone()).isEqualTo("+90 532");
        assertThat(u.getDepartment()).isEqualTo("TEKNOLOJİ");
        assertThat(u.getCompanyLevel()).isEqualTo("Uzman");
        assertThat(u.getMudurlukName()).isEqualTo("TEKN.MİM.");
        assertThat(u.getManagerSicil()).isEqualTo("99999");
        assertThat(u.getTitle()).isEqualTo("ESKI ÜNVAN");   // body'de yok → dokunulmadı
    }

    @Test
    @DisplayName("applyProfileFields: boş string gönderilen alanı null yapar")
    void applyProfileFields_blankClearsField() {
        AppUser u = new AppUser();
        u.setPhone("ESKI");
        Map<String, Object> body = new HashMap<>();
        body.put("phone", "   ");
        service.applyProfileFields(u, body);
        assertThat(u.getPhone()).isNull();
    }
}
