package com.certmonitor.service;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.PasswordHistory;
import com.certmonitor.model.Team;
import com.certmonitor.repository.AppUserRepository;
import com.certmonitor.repository.CertificateInventoryRepository;
import com.certmonitor.repository.EscalationContactRepository;
import com.certmonitor.repository.PasswordHistoryRepository;
import com.certmonitor.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final AppUserRepository userRepo;
    private final TeamRepository teamRepo;
    private final CertificateInventoryRepository inventoryRepo;
    private final EscalationContactRepository contactRepo;
    private final PasswordHistoryRepository passwordHistoryRepo;

    /** Admin "Sonlandır" sonrası activeSessionId'ye yazılan sentinel öneki — gerçek oturum ID'sine
     *  asla eşleşmez; AppUserRepository.findAllWithActiveSession bunu hariç tutar. */
    public static final String SESSION_TERMINATED_PREFIX = "TERMINATED:";

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Lockout durations (seconds) per offense level — injected from config. */
    @Value("${cert.monitor.lockout.durations-seconds:30,120,600,1800}")
    private List<Long> lockoutDurationsSecs;

    /** Fresh failures required to trigger each lockout level — injected from config. */
    @Value("${cert.monitor.lockout.failures-needed:5,3,2,1}")
    private List<Integer> lockoutFailuresNeeded;

    @Value("${cert.monitor.password.min-length:6}")
    private int passwordMinLength;

    @Value("${cert.monitor.password.max-length:10}")
    private int passwordMaxLength;

    @Value("${cert.monitor.password.history-count:3}")
    private int passwordHistoryCount;

    /** "Aktif oturum" tazelik penceresi (sn): son ping bu süre içindeyse oturum canlı sayılır.
     *  Frontend ping aralığı ~15 sn; arka-plan sekmesi kısıtlamasına (≈1/dk) tolerans için 120 sn. */
    @Value("${cert.monitor.session.active-window-seconds:120}")
    private long activeWindowSeconds;

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
                // LDAP users have no stored password (hash is null) → never match local auth.
                .filter(u -> u.getPasswordHash() != null
                        && PASSWORD_ENCODER.matches(rawPassword, u.getPasswordHash()));
    }

    /** True when an admin-issued temp password's 24-hour window has elapsed.
     *  Returns false (= still valid / no temp) when the field is null. */
    public boolean isTempPasswordExpired(AppUser user) {
        String exp = user.getTempPasswordExpiresAt();
        if (exp == null) return false;
        try {
            Instant expiresAt = LocalDateTime.parse(exp).toInstant(ZoneOffset.UTC);
            return expiresAt.isBefore(Instant.now());
        } catch (Exception e) {
            log.warn("Could not parse temp_password_expires_at '{}' for user {}", exp, user.getUsername());
            return false;  // fail-open: don't lock the user out on a parse bug
        }
    }

    public Optional<AppUser> findByUsername(String username) {
        return userRepo.findByUsername(username);
    }

    // ── Tek aktif oturum (single active session per user) — store-agnostik ──────
    /** Username normalizasyonu: trim + BÜYÜK harf (Locale.ROOT — Türkçe locale'de 'i'→'İ' OLMASIN; ASCII).
     *  Tek kaynak: tüm oluşturma noktalari (bootstrap / LDAP provision / admin create) bunu kullanir →
     *  app_users.username HER ZAMAN büyük harf. Aramalar AppUserRepository'de case-insensitive (DB UPPER). */
    public static String normalizeUsername(String username) {
        return username == null ? null : username.strip().toUpperCase(java.util.Locale.ROOT);
    }

    /** Kullanıcının en güncel oturum ID'sini kaydeder (yeni login / remember-me reauth → newest wins). */
    @Transactional
    public void recordActiveSession(String username, String sessionId) {
        if (username == null || sessionId == null) return;
        userRepo.findByUsername(username).ifPresent(u -> {
            u.setActiveSessionId(sessionId);
            u.setLastSeenAt(ISO.format(Instant.now()));   // login = taze etkinlik
            userRepo.save(u);
        });
    }

    /** Oturum ping'i (frontend ~15 sn): kullanıcının güncel oturumunun lastSeenAt'ini tazeler.
     *  Süpersede oturum (sid eşleşmez) güncellenmez — interceptor zaten 401 verir. */
    @Transactional
    public void touchActiveSession(String username, String sessionId) {
        if (username == null || sessionId == null) return;
        userRepo.touchLastSeen(username, sessionId, ISO.format(Instant.now()));
    }

    /** Kullanıcının CANLI bir aktif oturumu var mı? activeSessionId set (TERMINATED sentinel değil) VE
     *  lastSeenAt tazelik penceresi içinde (son ping). Aktif sayım + login-onayı bunu kullanır;
     *  böylece logout'suz kapatılan/ölen oturumlar otomatik düşer. */
    public boolean hasLiveSession(AppUser u) {
        if (u == null) return false;
        String sid = u.getActiveSessionId();
        if (sid == null || sid.isBlank() || sid.startsWith(SESSION_TERMINATED_PREFIX)) return false;
        String last = u.getLastSeenAt();
        if (last == null || last.isBlank()) return false;
        String threshold = ISO.format(Instant.now().minusSeconds(activeWindowSeconds));
        return last.compareTo(threshold) >= 0;
    }

    /** Bu oturum, kullanıcının kayıtlı (daha yeni) oturumu tarafından geçersiz kılındı mı?
     *  Yalnız kayıtlı activeSessionId VARSA ve verilenden FARKLIYSA true → başka yerden login olmuş, bu eski
     *  oturum kapatılmalı. Kayıt yoksa (null, deploy öncesi eski oturumlar) veya eşleşiyorsa false → zorlama yok. */
    public boolean isSessionSuperseded(String username, String sessionId) {
        if (username == null || sessionId == null) return false;
        String active = userRepo.findByUsername(username).map(AppUser::getActiveSessionId).orElse(null);
        return active != null && !active.equals(sessionId);
    }

    /** Çıkışta aktif oturum kaydını temizler — yalnız eşleşiyorsa (yarıştaki yeni oturumu silmesin). */
    @Transactional
    public void clearActiveSession(String username, String sessionId) {
        if (username == null || sessionId == null) return;
        userRepo.findByUsername(username).ifPresent(u -> {
            if (sessionId.equals(u.getActiveSessionId())) {
                u.setActiveSessionId(null);
                userRepo.save(u);
            }
        });
    }

    /** Açılışta: tüm stale activeSessionId kayıtlarını temizler (in-memory oturumlar restart'ı yaşamaz).
     *  Temizlenen satır sayısını döner. */
    @Transactional
    public int clearAllActiveSessions() {
        return userRepo.clearAllActiveSessions();
    }

    /** Admin tarafından uzaktan oturum sonlandırma (kick). activeSessionId'yi kullanıcının GERÇEK
     *  oturumuna asla eşleşmeyecek bir sentinel'e çeker → kullanıcının sonraki isteğinde
     *  isSessionSuperseded=true olur, oturum kapanır. (null yapmak grandfather yüzünden ATMAZ.)
     *  remember-me iptali çağıran tarafça yapılır (sessiz reauth olmasın). */
    @Transactional
    public void terminateActiveSession(String username) {
        if (username == null) return;
        userRepo.findByUsername(username).ifPresent(u -> {
            u.setActiveSessionId(SESSION_TERMINATED_PREFIX + UUID.randomUUID());
            userRepo.save(u);
        });
    }

    public Optional<Team> findTeamById(Long id) {
        return teamRepo.findById(id);
    }

    // ── Team scoping (Faz 3b) ───────────────────────────────────────────────
    // null  = unrestricted (global admin / AUDIT read)
    // list  = restricted to those team ids (empty = nothing)

    /** Read scope: which teams' objects this user may SEE. */
    public List<Long> computeViewTeamIds(AppUser u) {
        if (u == null) return java.util.List.of();
        String role = u.getSystemRole();
        if ("AUDIT".equals(role)) return null;                  // system-wide read
        boolean ldap = "LDAP".equalsIgnoreCase(u.getAuthSource());
        if ("ADMIN".equals(role)) {
            // Local/bootstrap ADMIN → global; AD ADMIN (müdür) → only subordinates' teams.
            return ldap ? subordinateTeamIds(u.getId()) : null;
        }
        if ("TEAM_ADMIN".equals(role)) return ledPlusOwnTeamIds(u);
        // USER — üye olduğu TÜM takımlar (birincil dahil)
        return ownTeamIds(u);
    }

    /** Kullanıcının üye olduğu tüm takımlar (birincil {@code teamId} dahil), sıralı + tekil. */
    private List<Long> ownTeamIds(AppUser u) {
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
        if (u.getTeamIds() != null) for (Long t : u.getTeamIds()) if (t != null) ids.add(t);
        if (u.getTeamId() != null) ids.add(u.getTeamId());
        return new java.util.ArrayList<>(ids);
    }

    /** Write scope: which teams' objects this user may MANAGE. */
    public List<Long> computeManageTeamIds(AppUser u) {
        if (u == null) return java.util.List.of();
        String role = u.getSystemRole();
        boolean ldap = "LDAP".equalsIgnoreCase(u.getAuthSource());
        if ("ADMIN".equals(role) && !ldap) return null;         // global manage
        if ("TEAM_ADMIN".equals(role)) return ledPlusOwnTeamIds(u);
        // müdür (AD admin), USER, AUDIT → no team management
        return java.util.List.of();
    }

    private List<Long> subordinateTeamIds(Long managerId) {
        if (managerId == null) return new java.util.ArrayList<>();
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
        for (AppUser sub : userRepo.findByManagerId(managerId)) {
            if (sub.getTeamIds() != null) for (Long t : sub.getTeamIds()) if (t != null) ids.add(t);
            if (sub.getTeamId() != null) ids.add(sub.getTeamId());
        }
        return new java.util.ArrayList<>(ids);
    }

    private List<Long> ledPlusOwnTeamIds(AppUser u) {
        java.util.LinkedHashSet<Long> ids = new java.util.LinkedHashSet<>();
        for (Team t : teamRepo.findByLeaderId(u.getId())) ids.add(t.getId());
        if (u.getTeamIds() != null) for (Long t : u.getTeamIds()) if (t != null) ids.add(t);
        if (u.getTeamId() != null) ids.add(u.getTeamId());
        return new java.util.ArrayList<>(ids);
    }

    /** Verilen takım id'lerini ada çevirir (sıra korunur, bulunamayanlar atlanır) — /me için. */
    public List<String> teamNamesFor(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return java.util.List.of();
        java.util.Map<Long, String> byId = new java.util.HashMap<>();
        teamRepo.findAllById(ids).forEach(t -> byId.put(t.getId(), t.getName()));
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Long id : ids) { String n = byId.get(id); if (n != null) names.add(n); }
        return names;
    }

    /**
     * Finds an existing user by username, or provisions a new LDAP-authenticated one.
     * AD users get a sentinel password hash (random UUID → never matches local auth),
     * systemRole=USER, no team (team/attribute mapping is a later phase),
     * authSource="LDAP". On re-login, refreshes displayName/email from AD.
     */
    @Transactional
    public AppUser provisionLdapUser(String username, String displayName, String email) {
        String uname = normalizeUsername(username);
        String now = now();
        Optional<AppUser> existing = userRepo.findByUsername(uname);
        if (existing.isPresent()) {
            AppUser u = existing.get();
            boolean changed = false;
            if (displayName != null && !displayName.isBlank() && !displayName.equals(u.getDisplayName())) {
                u.setDisplayName(displayName);
                changed = true;
            }
            if (email != null && !email.isBlank() && !email.equals(u.getEmail())) {
                u.setEmail(email);
                changed = true;
            }
            if (changed) {
                u.setUpdatedAt(now);
                userRepo.save(u);
            }
            return u;
        }
        AppUser u = new AppUser();
        u.setUsername(uname);
        // LDAP users authenticate against AD — no password is stored in the app (null hash).
        u.setPasswordHash(null);
        u.setDisplayName((displayName != null && !displayName.isBlank()) ? displayName : uname);
        u.setEmail(email);
        u.setSystemRole("USER");
        u.setTeamId(null);
        u.setAuthSource("LDAP");
        u.setActive(true);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        AppUser saved = userRepo.save(u);
        log.info("Provisioned LDAP user '{}' (USER, no team)", uname);
        return saved;
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
        admin.setUsername(normalizeUsername(adminUsername));
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
        // Sıralamayı DB'ye bırak (LOWER(name)) — Java tarafında findAll()+sort yerine.
        return teamRepo.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.asc("name").ignoreCase()));
    }

    @Transactional
    public Team createTeam(String name, String email, String description, Long leaderId) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Team name cannot be blank");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Team email is required");
        if (teamRepo.existsByName(name.trim())) throw new IllegalArgumentException("Team already exists: " + name);
        // Leader (PO) is OPTIONAL — a team may be created before its PO has logged in.
        if (leaderId != null && !userRepo.existsById(leaderId))
            throw new IllegalArgumentException("Leader user not found: " + leaderId);
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
        if (leaderId != null) {   // optional; only validated when provided
            if (!userRepo.existsById(leaderId)) throw new IllegalArgumentException("Leader user not found: " + leaderId);
            team.setLeaderId(leaderId);
        }
        team.setUpdatedAt(now());
        return teamRepo.save(team);
    }

    @Transactional
    public void deleteTeam(Long id) {
        if (inventoryRepo.existsByTeamIdAndActiveTrueAndDeletedAtIsNull(id))
            throw new IllegalStateException("Bu takım aktif sertifikalara atanmış — önce sertifikaları başka bir takıma taşıyın.");
        if (userRepo.existsByTeamId(id) || userRepo.existsByMembershipTeamId(id))
            throw new IllegalStateException("Bu takımda hâlâ kullanıcılar var — önce kullanıcıları başka bir takıma taşıyın.");
        if (contactRepo.existsByTeamIdAndActiveTrue(id))
            throw new IllegalStateException("Bu takıma atanmış aktif escalation contact'lar var — önce onları kaldırın veya devre dışı bırakın.");
        teamRepo.deleteById(id);
    }

    // ── User CRUD ─────────────────────────────────────────────────────────────

    public List<AppUser> listUsers() {
        return userRepo.findAllByOrderByUsernameAsc();
    }

    @Transactional
    public AppUser createUser(String username, String rawPassword, String displayName,
                               String email, String employeeId, String systemRole,
                               java.util.Collection<Long> teamIds, String orgRole) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Username cannot be blank");
        if (rawPassword == null || rawPassword.length() < passwordMinLength) throw new IllegalArgumentException("Password too short (min " + passwordMinLength + " chars)");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Email is required");
        java.util.LinkedHashSet<Long> teams = normalizeTeams(teamIds);
        // Takım, ADMIN dışındaki roller için zorunlu (global admin bir takıma bağlı olmak zorunda değil).
        if (teams.isEmpty() && !"ADMIN".equals(systemRole)) throw new IllegalArgumentException("Team is required");
        String uname = normalizeUsername(username);
        if (userRepo.existsByUsername(uname)) throw new IllegalArgumentException("Username already exists: " + uname);

        String now = now();
        AppUser user = new AppUser();
        user.setUsername(uname);
        user.setPasswordHash(PASSWORD_ENCODER.encode(rawPassword));
        user.setDisplayName(displayName);
        user.setEmail(email.trim());
        user.setEmployeeId(employeeId);
        user.setSystemRole(systemRole != null ? systemRole : "USER");
        user.setRoleLocked(true);       // admin tarafından oluşturuldu → rol manuel; LDAP ezmesin
        user.setOrgRole(orgRole != null && !orgRole.isBlank() ? orgRole : null);
        user.setOrgRoleLocked(true);    // admin oluşturdu → org rol manuel; LDAP provisyonu ezmesin
        applyTeams(user, teams);
        user.setActive(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        AppUser saved = userRepo.save(user);
        syncPoLeadership(saved);
        return saved;
    }

    /** Çoklu takımı kullanıcıya uygular: üyelik kümesini yazar, birincil = ilk eleman. */
    private void applyTeams(AppUser user, java.util.LinkedHashSet<Long> teams) {
        user.setTeamIds(teams);
        user.setTeamId(teams.isEmpty() ? null : teams.iterator().next());
    }

    /** Gelen id koleksiyonunu sırayı koruyarak tekilleştirir (null'ları atar). */
    private java.util.LinkedHashSet<Long> normalizeTeams(java.util.Collection<Long> teamIds) {
        java.util.LinkedHashSet<Long> set = new java.util.LinkedHashSet<>();
        if (teamIds != null) for (Long t : teamIds) if (t != null) set.add(t);
        return set;
    }

    /**
     * A PO (orgRole=PO) assigned to a team automatically becomes that team's leader,
     * but ONLY when the team has no (valid) leader yet — an existing, real leader is never
     * overwritten. A dangling leaderId (silinmiş kullanıcıya işaret eden) lidersiz sayılır,
     * böylece silinen PO yerine yeni eklenen PO otomatik lider atanır.
     * No-op when the user isn't a PO or has no team.
     */
    private void syncPoLeadership(AppUser user) {
        if (user == null || !"PO".equals(user.getOrgRole()) || user.getTeamId() == null) return;
        teamRepo.findById(user.getTeamId()).ifPresent(team -> {
            Long leaderId = team.getLeaderId();
            boolean leaderless = leaderId == null || !userRepo.existsById(leaderId);
            if (leaderless) {                        // gerçek (mevcut) lideri ezme
                team.setLeaderId(user.getId());
                team.setUpdatedAt(now());
                teamRepo.save(team);
            }
        });
    }

    @Transactional
    public AppUser updateUser(Long id, String displayName, String email, String employeeId,
                               String systemRole, java.util.Collection<Long> teamIds, Boolean active, String orgRole) {
        AppUser user = userRepo.findById(id).orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        if (displayName != null) user.setDisplayName(displayName);
        if (email != null && !email.isBlank()) user.setEmail(email.trim());
        if (employeeId != null) user.setEmployeeId(employeeId);
        if (systemRole != null && !systemRole.equals(user.getSystemRole())) {
            user.setSystemRole(systemRole);
            user.setRoleLocked(true);   // admin manuel değiştirdi → LDAP provisyonu bu rolü ezmesin
        }
        // teamIds == null → takımlara dokunma (kısmi güncelleme); verilirse (boş dahil) üyeliği set et.
        if (teamIds != null) applyTeams(user, normalizeTeams(teamIds));
        if (active != null) user.setActive(active);
        String newOrg = (orgRole != null && !orgRole.isBlank()) ? orgRole : null;
        if (!java.util.Objects.equals(newOrg, user.getOrgRole())) {
            user.setOrgRole(newOrg);
            user.setOrgRoleLocked(true);   // admin manuel değiştirdi → LDAP org rolünü ezmesin
        }
        user.setUpdatedAt(now());
        AppUser saved = userRepo.save(user);
        String syncName = saved.getDisplayName() != null && !saved.getDisplayName().isBlank()
                ? saved.getDisplayName() : saved.getUsername();
        contactRepo.findByUserId(id).forEach(c -> {
            c.setName(syncName);
            c.setEmail(saved.getEmail());
            contactRepo.save(c);
        });
        syncPoLeadership(saved);
        return saved;
    }

    /** Admin kullanıcının rol-kilidini kaldırır → systemRole tekrar AD (LDAP) yönetimine döner
     *  (sonraki LDAP girişinde rol AD'den yeniden türetilir). */
    @Transactional
    public void unlockRole(Long id) {
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        user.setRoleLocked(false);
        user.setUpdatedAt(now());
        userRepo.save(user);
    }

    /** Admin kullanıcının org-rol kilidini kaldırır → org_role tekrar AD (LDAP) yönetimine döner
     *  (sonraki LDAP girişinde seviyeden yeniden türetilir). */
    @Transactional
    public void unlockOrgRole(Long id) {
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));
        user.setOrgRoleLocked(false);
        user.setUpdatedAt(now());
        userRepo.save(user);
    }

    /**
     * Applies the AD-mirrored profile fields (ad/soyad/ünvan/telefon/departman/seviye/
     * müdürlük/müdür sicili) from an admin create/update request body onto {@code u}.
     * Only keys actually present in the body are touched; blank values clear the field.
     * For LDAP users these are refreshed from AD on next login (same as displayName/email).
     */
    public void applyProfileFields(AppUser u, Map<String, Object> body) {
        if (body == null) return;
        if (body.containsKey("first_name"))    u.setFirstName(bodyStr(body.get("first_name")));
        if (body.containsKey("last_name"))     u.setLastName(bodyStr(body.get("last_name")));
        if (body.containsKey("title"))         u.setTitle(bodyStr(body.get("title")));
        if (body.containsKey("phone"))         u.setPhone(bodyStr(body.get("phone")));
        if (body.containsKey("department"))    u.setDepartment(bodyStr(body.get("department")));
        if (body.containsKey("company_level")) u.setCompanyLevel(bodyStr(body.get("company_level")));
        if (body.containsKey("mudurluk_name")) u.setMudurlukName(bodyStr(body.get("mudurluk_name")));
        if (body.containsKey("manager_sicil")) u.setManagerSicil(bodyStr(body.get("manager_sicil")));
    }

    private static String bodyStr(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    @Transactional
    public void changePassword(Long id, String rawPassword) {
        if (rawPassword == null || rawPassword.length() < passwordMinLength) {
            throw new IllegalArgumentException("Password too short (min " + passwordMinLength + " chars)");
        }
        if (rawPassword.length() > passwordMaxLength) {
            throw new IllegalArgumentException("Password too long (max " + passwordMaxLength + " chars)");
        }
        AppUser user = userRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + id));

        // History check: the new raw password must not collide with the current
        // hash or any of the most recent (historyCount - 1) archived hashes.
        List<String> recentHashes = new ArrayList<>();
        if (user.getPasswordHash() != null) recentHashes.add(user.getPasswordHash());
        passwordHistoryRepo.findByUserIdOrderByCreatedAtDesc(id).stream()
                .limit(Math.max(0, passwordHistoryCount - 1))
                .map(PasswordHistory::getPasswordHash)
                .forEach(recentHashes::add);
        for (String oldHash : recentHashes) {
            if (oldHash != null && PASSWORD_ENCODER.matches(rawPassword, oldHash)) {
                throw new IllegalArgumentException("Password recently used");
            }
        }

        // Archive the outgoing hash before overwriting it so the next change
        // can compare against it.
        if (user.getPasswordHash() != null) {
            PasswordHistory hist = new PasswordHistory();
            hist.setUserId(id);
            hist.setPasswordHash(user.getPasswordHash());
            passwordHistoryRepo.save(hist);
        }

        user.setPasswordHash(PASSWORD_ENCODER.encode(rawPassword));
        user.setUpdatedAt(now());
        if (Boolean.TRUE.equals(user.getMustChangePassword())) {
            user.setMustChangePassword(false);   // any successful change clears the forced flag
        }
        if (user.getTempPasswordExpiresAt() != null) {
            user.setTempPasswordExpiresAt(null); // the new password is permanent
        }
        userRepo.save(user);

        // Prune anything older than (historyCount - 1) since the current
        // password on the AppUser row already counts as the Nth entry.
        int keep = Math.max(0, passwordHistoryCount - 1);
        List<PasswordHistory> all = passwordHistoryRepo.findByUserIdOrderByCreatedAtDesc(id);
        if (all.size() > keep) {
            passwordHistoryRepo.deleteAll(all.subList(keep, all.size()));
        }
    }

    /**
     * Step-up password change: requires the calling admin to re-prove they know
     * their own password before the target user's password is rewritten.
     * Prevents a stolen admin session from being weaponised into account
     * takeover of other accounts.
     */
    @Transactional
    public void changePassword(Long targetUserId, String newPassword,
                               String adminUsername, String adminCurrentPassword) {
        if (adminUsername == null || adminCurrentPassword == null || adminCurrentPassword.isBlank()) {
            throw new SecurityException("Invalid admin password");
        }
        boolean adminVerified = userRepo.findByUsernameAndActiveTrue(adminUsername)
                .filter(u -> PASSWORD_ENCODER.matches(adminCurrentPassword, u.getPasswordHash()))
                .isPresent();
        if (!adminVerified) {
            throw new SecurityException("Invalid admin password");
        }
        changePassword(targetUserId, newPassword);
    }

    /**
     * Admin auto-reset: verifies the admin's own password, generates a
     * temporary password for the target user, sets mustChangePassword=true,
     * and returns the plaintext temp password so the controller can email
     * it. The plaintext is NEVER persisted or logged here.
     */
    @Transactional
    public String adminAutoResetPassword(Long targetUserId, String adminUsername, String adminCurrentPassword) {
        if (adminUsername == null || adminCurrentPassword == null || adminCurrentPassword.isBlank()) {
            throw new SecurityException("Invalid admin password");
        }
        boolean adminVerified = userRepo.findByUsernameAndActiveTrue(adminUsername)
                .filter(u -> PASSWORD_ENCODER.matches(adminCurrentPassword, u.getPasswordHash()))
                .isPresent();
        if (!adminVerified) {
            throw new SecurityException("Invalid admin password");
        }

        AppUser target = userRepo.findById(targetUserId)
                .orElseThrow(() -> new NoSuchElementException("User not found: " + targetUserId));
        if (target.getEmail() == null || target.getEmail().isBlank()) {
            throw new IllegalArgumentException("User has no email address");
        }

        // Archive the outgoing hash so the user cannot pick it again later.
        if (target.getPasswordHash() != null) {
            PasswordHistory hist = new PasswordHistory();
            hist.setUserId(targetUserId);
            hist.setPasswordHash(target.getPasswordHash());
            passwordHistoryRepo.save(hist);
        }

        String tempPwd = com.certmonitor.util.PasswordGenerator.generate();
        target.setPasswordHash(PASSWORD_ENCODER.encode(tempPwd));
        target.setMustChangePassword(true);
        target.setTempPasswordExpiresAt(ISO.format(Instant.now().plus(Duration.ofHours(24))));
        target.setUpdatedAt(now());
        userRepo.save(target);

        // Prune archived hashes beyond the policy keep window.
        int keep = Math.max(0, passwordHistoryCount - 1);
        List<PasswordHistory> all = passwordHistoryRepo.findByUserIdOrderByCreatedAtDesc(targetUserId);
        if (all.size() > keep) {
            passwordHistoryRepo.deleteAll(all.subList(keep, all.size()));
        }

        return tempPwd;
    }

    @Transactional
    public void deleteUser(Long id) {
        // Bu kullanıcı bir takımın lideriyse, silmeden önce o takım(lar)ın liderliğini boşalt.
        // Aksi halde leaderId dangling (silinmiş id) kalır → UI "lider atanmadı" gösterir ama
        // yeni eklenen PO otomatik lider atanmaz (syncPoLeadership null kontrolü geçemez).
        for (Team t : teamRepo.findByLeaderId(id)) {
            t.setLeaderId(null);
            t.setUpdatedAt(now());
            teamRepo.save(t);
        }
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
