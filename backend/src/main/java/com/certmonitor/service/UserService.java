package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private final CertificateInventoryRepository inventoryRepo;

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Lockout durations (seconds) per offense level — injected from config. */
    @Value("${cert.monitor.lockout.durations-seconds:30,120,600,1800}")
    private List<Long> lockoutDurationsSecs;

    /** Fresh failures required to trigger each lockout level — injected from config. */
    @Value("${cert.monitor.lockout.failures-needed:5,3,2,1}")
    private List<Integer> lockoutFailuresNeeded;

    @Value("${cert.monitor.password.min-length:4}")
    private int passwordMinLength;

    /** Returns how many failures are needed to trigger the next lockout for this account. */
    public int failuresNeededForLevel(Integer failedBlockCount) {
        int level = (failedBlockCount == null ? 0 : failedBlockCount);
        return level < lockoutFailuresNeeded.size() ? lockoutFailuresNeeded.get(level) : 1;
    }

    public record LockoutStatus(boolean permanent, long secondsRemaining) {
        public boolean isBlocked() { return permanent || secondsRemaining > 0; }
    }

    // ── Authentication ────────────────────────────────────────────────────────

    public Optional<AppUser> authenticate(String username, String rawPassword) {
        return userRepo.findByUsernameAndActiveTrue(username)
                .filter(u -> PASSWORD_ENCODER.matches(rawPassword, u.getPasswordHash()));
    }

    public Optional<AppUser> findByUsername(String username) {
        return userRepo.findByUsername(username);
    }

    public Optional<Team> findTeamById(Long id) {
        return teamRepo.findById(id);
    }

    // ── Bootstrap ─────────────────────────────────────────────────────────────

    /**
     * Creates the default "General" team and admin user from config if the
     * users table is empty. Safe to call multiple times (idempotent).
     */
    @Transactional
    public void ensureBootstrapped(String adminUsername, String rawPassword) {
        if (userRepo.count() > 0) return;

        String now = now();

        // Ensure default team exists
        Team team = teamRepo.findByName("General").orElseGet(() -> {
            Team t = new Team();
            t.setName("General");
            t.setDescription("Default team");
            t.setActive(true);
            t.setCreatedAt(now);
            t.setUpdatedAt(now);
            return teamRepo.save(t);
        });

        // Create admin user
        AppUser admin = new AppUser();
        admin.setUsername(adminUsername);
        admin.setPasswordHash(PASSWORD_ENCODER.encode(rawPassword));
        admin.setDisplayName(adminUsername);
        admin.setSystemRole("ADMIN");
        admin.setTeamId(team.getId());
        admin.setActive(true);
        admin.setCreatedAt(now);
        admin.setUpdatedAt(now);
        AppUser savedAdmin = userRepo.save(admin);

        // Set admin as the leader of the General team
        team.setLeaderId(savedAdmin.getId());
        teamRepo.save(team);

        log.info("Bootstrap: created team '{}' (id={}) and admin user '{}'",
                team.getName(), team.getId(), adminUsername);
    }

    // ── Team CRUD ─────────────────────────────────────────────────────────────

    public List<Team> listTeams() {
        return teamRepo.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
    }

    @Transactional
    public Team createTeam(String name, String email, String description, Long leaderId) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Team name cannot be blank");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Team email is required");
        if (leaderId == null) throw new IllegalArgumentException("Team leader is required");
        if (teamRepo.existsByName(name.trim())) throw new IllegalArgumentException("Team already exists: " + name);
        if (!userRepo.existsById(leaderId)) throw new IllegalArgumentException("Leader user not found: " + leaderId);
        String now = now();
        Team team = new Team();
        team.setName(name.trim());
        team.setEmail(email.trim());
        team.setDescription(description);
        team.setLeaderId(leaderId);
        team.setActive(true);
        team.setCreatedAt(now);
        team.setUpdatedAt(now);
        return teamRepo.save(team);
    }

    @Transactional
    public Team updateTeam(Long id, String name, String email, String description, Boolean active, Long leaderId) {
        Team team = teamRepo.findById(id).orElseThrow(() -> new NoSuchElementException("Team not found: " + id));
        if (name != null && !name.isBlank()) team.setName(name.trim());
        if (email != null && !email.isBlank()) team.setEmail(email.trim());
        else if (team.getEmail() == null || team.getEmail().isBlank())
            throw new IllegalArgumentException("Team email is required");
        if (description != null) team.setDescription(description);
        if (active != null) team.setActive(active);
        if (leaderId != null) {
            if (!userRepo.existsById(leaderId)) throw new IllegalArgumentException("Leader user not found: " + leaderId);
            team.setLeaderId(leaderId);
        } else if (team.getLeaderId() == null) {
            throw new IllegalArgumentException("Team leader is required");
        }
        team.setUpdatedAt(now());
        return teamRepo.save(team);
    }

    @Transactional
    public void deleteTeam(Long id) {
        if (inventoryRepo.existsByTeamIdAndActiveTrueAndDeletedAtIsNull(id))
            throw new IllegalStateException("Cannot delete team: it has active certificates assigned to it");
        teamRepo.deleteById(id);
    }

    // ── User CRUD ─────────────────────────────────────────────────────────────

    public List<AppUser> listUsers() {
        return userRepo.findAllByOrderByUsernameAsc();
    }

    @Transactional
    public AppUser createUser(String username, String rawPassword, String displayName,
                               String email, String employeeId, String systemRole, Long teamId, String orgRole) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Username cannot be blank");
        if (rawPassword == null || rawPassword.length() < passwordMinLength) throw new IllegalArgumentException("Password too short (min " + passwordMinLength + " chars)");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Email is required");
        if (teamId == null) throw new IllegalArgumentException("Team is required");
        if (userRepo.existsByUsername(username.trim())) throw new IllegalArgumentException("Username already exists: " + username);

        String now = now();
        AppUser user = new AppUser();
        user.setUsername(username.trim());
        user.setPasswordHash(PASSWORD_ENCODER.encode(rawPassword));
        user.setDisplayName(displayName);
        user.setEmail(email.trim());
        user.setEmployeeId(employeeId);
        user.setSystemRole(systemRole != null ? systemRole : "USER");
        user.setOrgRole(orgRole != null && !orgRole.isBlank() ? orgRole : null);
        user.setTeamId(teamId);
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userRepo.save(user);
    }

    @Transactional
    public AppUser updateUser(Long id, String displayName, String email, String employeeId,
                               String systemRole, Long teamId, Boolean active, String orgRole) {
        AppUser user = userRepo.findById(id).orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        if (displayName != null) user.setDisplayName(displayName);
        if (email != null && !email.isBlank()) user.setEmail(email.trim());
        if (employeeId != null) user.setEmployeeId(employeeId);
        if (systemRole != null) user.setSystemRole(systemRole);
        if (teamId != null) user.setTeamId(teamId);
        if (active != null) user.setActive(active);
        user.setOrgRole(orgRole != null && !orgRole.isBlank() ? orgRole : null);
        user.setUpdatedAt(now());
        return userRepo.save(user);
    }

    @Transactional
    public void changePassword(Long id, String rawPassword) {
        if (rawPassword == null || rawPassword.length() < passwordMinLength) throw new IllegalArgumentException("Password too short (min " + passwordMinLength + " chars)");
        AppUser user = userRepo.findById(id).orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        user.setPasswordHash(PASSWORD_ENCODER.encode(rawPassword));
        user.setUpdatedAt(now());
        userRepo.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        userRepo.deleteById(id);
    }

    // ── Progressive lockout ───────────────────────────────────────────────────

    /** Returns current lockout state without modifying anything. */
    public LockoutStatus checkLockout(String username) {
        return userRepo.findByUsername(username).map(u -> {
            if (Boolean.TRUE.equals(u.getPermanentLock()))
                return new LockoutStatus(true, 0);
            if (u.getLockoutUntil() != null) {
                try {
                    long remaining = Duration.between(
                        Instant.now(), Instant.parse(u.getLockoutUntil() + "Z")).getSeconds();
                    if (remaining > 0) return new LockoutStatus(false, remaining);
                } catch (Exception ignored) {}
                // Lockout expired — clear it
                u.setLockoutUntil(null);
                userRepo.save(u);
            }
            return new LockoutStatus(false, 0);
        }).orElse(new LockoutStatus(false, 0));
    }

    /**
     * Called when BRUTE_FORCE is detected for this username.
     * Escalates the lockout level and persists it.
     * Level 1→30s, 2→2min, 3→10min, 4→30min, 5+→permanent.
     */
    @Transactional
    public LockoutStatus applyProgressiveLockout(String username) {
        return userRepo.findByUsername(username).map(u -> {
            int offense = (u.getFailedBlockCount() == null ? 0 : u.getFailedBlockCount()) + 1;
            u.setFailedBlockCount(offense);
            u.setUpdatedAt(now());
            if (offense > lockoutDurationsSecs.size()) {
                u.setPermanentLock(true);
                u.setLockoutUntil(null);
                u.setLastLockoutAt(now());
                userRepo.save(u);
                log.warn("Account PERMANENTLY locked: user='{}'", username);
                return new LockoutStatus(true, 0);
            }
            long secs = lockoutDurationsSecs.get(offense - 1);
            String lockedAt = ISO.format(Instant.now());
            u.setLastLockoutAt(lockedAt);
            u.setLockoutUntil(ISO.format(Instant.now().plusSeconds(secs)));
            userRepo.save(u);
            log.warn("Account locked level={}/{} for {}s: user='{}'", offense, lockoutDurationsSecs.size(), secs, username);
            return new LockoutStatus(false, secs);
        }).orElse(new LockoutStatus(false, 0));
    }

    /** Admin action: remove all locks and reset escalation counter. */
    @Transactional
    public void unlockUser(Long id) {
        AppUser u = userRepo.findById(id).orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        u.setPermanentLock(false);
        u.setLockoutUntil(null);
        u.setFailedBlockCount(0);
        u.setLastLockoutAt(null);
        u.setUpdatedAt(now());
        userRepo.save(u);
        log.info("Account unlocked by admin: user='{}'", u.getUsername());
    }

    /** Called on successful login to reset the escalation state. */
    @Transactional
    public void clearLockoutOnSuccess(String username) {
        userRepo.findByUsername(username).ifPresent(u -> {
            boolean changed = u.getLockoutUntil() != null
                    || (u.getFailedBlockCount() != null && u.getFailedBlockCount() > 0)
                    || u.getLastLockoutAt() != null;
            u.setLockoutUntil(null);
            u.setFailedBlockCount(0);
            u.setLastLockoutAt(null);
            if (changed) { u.setUpdatedAt(now()); userRepo.save(u); }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String now() {
        return ISO.format(Instant.now());
    }
}
