package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

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
        admin.setDisplayName("Administrator");
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
    public Team createTeam(String name, String description, Long leaderId) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Team name cannot be blank");
        if (teamRepo.existsByName(name.trim())) throw new IllegalArgumentException("Team already exists: " + name);
        if (leaderId == null) throw new IllegalArgumentException("Team leader is required");
        if (!userRepo.existsById(leaderId)) throw new IllegalArgumentException("Leader user not found: " + leaderId);
        String now = now();
        Team team = new Team();
        team.setName(name.trim());
        team.setDescription(description);
        team.setLeaderId(leaderId);
        team.setActive(true);
        team.setCreatedAt(now);
        team.setUpdatedAt(now);
        return teamRepo.save(team);
    }

    @Transactional
    public Team updateTeam(Long id, String name, String description, Boolean active, Long leaderId) {
        Team team = teamRepo.findById(id).orElseThrow(() -> new NoSuchElementException("Team not found: " + id));
        if (name != null && !name.isBlank()) team.setName(name.trim());
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
        teamRepo.deleteById(id);
    }

    // ── User CRUD ─────────────────────────────────────────────────────────────

    public List<AppUser> listUsers() {
        return userRepo.findAllByOrderByUsernameAsc();
    }

    @Transactional
    public AppUser createUser(String username, String rawPassword, String displayName,
                               String email, String systemRole, Long teamId) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Username cannot be blank");
        if (rawPassword == null || rawPassword.length() < 4) throw new IllegalArgumentException("Password too short");
        if (userRepo.existsByUsername(username.trim())) throw new IllegalArgumentException("Username already exists: " + username);

        String now = now();
        AppUser user = new AppUser();
        user.setUsername(username.trim());
        user.setPasswordHash(PASSWORD_ENCODER.encode(rawPassword));
        user.setDisplayName(displayName);
        user.setEmail(email);
        user.setSystemRole(systemRole != null ? systemRole : "USER");
        user.setTeamId(teamId);
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userRepo.save(user);
    }

    @Transactional
    public AppUser updateUser(Long id, String displayName, String email,
                               String systemRole, Long teamId, Boolean active) {
        AppUser user = userRepo.findById(id).orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        if (displayName != null) user.setDisplayName(displayName);
        if (email != null) user.setEmail(email);
        if (systemRole != null) user.setSystemRole(systemRole);
        if (teamId != null) user.setTeamId(teamId);
        if (active != null) user.setActive(active);
        user.setUpdatedAt(now());
        return userRepo.save(user);
    }

    @Transactional
    public void changePassword(Long id, String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 4) throw new IllegalArgumentException("Password too short");
        AppUser user = userRepo.findById(id).orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        user.setPasswordHash(PASSWORD_ENCODER.encode(rawPassword));
        user.setUpdatedAt(now());
        userRepo.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        userRepo.deleteById(id);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String now() {
        return ISO.format(Instant.now());
    }
}
