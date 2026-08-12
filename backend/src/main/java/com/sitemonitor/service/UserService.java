package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.PasswordHistory;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.PasswordHistoryRepository;
import com.sitemonitor.repository.TeamRepository;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
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
    @Value("${site.monitor.lockout.durations-seconds:30,120,600,1800}")
    private List<Long> lockoutDurationsSecs;

    /** Fresh failures required to trigger each lockout level — injected from config. */
    @Value("${site.monitor.lockout.failures-needed:5,3,2,1}")
    private List<Integer> lockoutFailuresNeeded;

    @Value("${site.monitor.password.min-length:12}")
    private int passwordMinLength;

    @Value("${site.monitor.password.max-length:128}")
    private int passwordMaxLength;

    @Value("${site.monitor.password.history-count:3}")
    private int passwordHistoryCount;

    /** "Aktif oturum" tazelik penceresi (sn): son ping bu süre içindeyse oturum canlı sayılır.
     *  Frontend ping aralığı ~15 sn; arka-plan sekmesi kısıtlamasına (≈1/dk) tolerans için 120 sn. */
    @Value("${site.monitor.session.active-window-seconds:120}")
    private long activeWindowSeconds;

    /** Supersede kontrolü her /api isteğinde koşar (AuthInterceptor) — istek başına DB SELECT'i
     *  bu kadar kısa TTL'le debounce edilir (F2, CPU denetimi). Tek pod'da force-login/kick anında
     *  evict edildiğinden gecikme yalnız TTL kadardır. */
    @Value("${site.monitor.session.supersede-cache-ms:5000}")
    private long supersedeCacheMs;

    /** Ping başına lastSeenAt UPDATE'i debounce penceresi (F3). 60+15 sn < 120 sn tazelik penceresi →
     *  login-onayı/aktif sayım etkilenmez. */
    @Value("${site.monitor.session.touch-debounce-ms:60000}")
    private long touchDebounceMs;

    /**
     * Giriş damgası "kaydırma" (shift) korumasının penceresi (sn).
     *
     * <p>Oturum düştükten sonra tarayıcı remember-me çereziyle AYNI ANDA birkaç istek gönderir ve
     * her biri sessiz yeniden kimlikleme tetikleyebilir. Kaydırma her seferinde çalışsaydı
     * {@code prevLoginAt} "birkaç saniye önce"ye düşerdi — yani kullanıcıya gösterilecek "önceki
     * girişiniz" değeri sessizce yok olurdu. Bu pencere içinde ikinci bir başarılı giriş kaydı
     * yalnız oturum alanlarını tazeler, kaydırmayı ATLAR.
     */
    @Value("${site.monitor.login.stamp-dedupe-seconds:30}")
    private long stampDedupeSeconds;

    private record ActiveSidEntry(String sid, long atMs) {}
    private static final int SESSION_MAP_MAX = 10_000;   // sert üst sınır (CaAutoPinService cap deseni)
    private final java.util.concurrent.ConcurrentHashMap<String, ActiveSidEntry> activeSessionCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastTouchAtMs =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        return username == null ? null : username.strip().toUpperCase(Locale.ROOT);
    }

    /** Kullanıcının en güncel oturum ID'sini kaydeder (yeni login / remember-me reauth → newest wins). */
    @Transactional
    public void recordActiveSession(String username, String sessionId) {
        recordSuccessfulLogin(username, sessionId, null, LoginMethod.PASSWORD);
    }

    // ── Giriş damgaları ────────────────────────────────────────────────────────

    /** Girişin yapılış biçimi — kullanıcı "beni hatırla ile sessizce dönmüşüm" ile "parola girmişim"i ayırabilsin. */
    public enum LoginMethod { PASSWORD, REMEMBER_ME }

    /**
     * Kaydırma ÖNCESİ değerler: çağıran, ikinci bir DB okuması yapmadan giriş yanıtını kurabilsin.
     * Kullanıcıya gösterilen "önceki girişiniz" tam olarak budur.
     */
    public record LoginStamp(String prevLoginAt, String prevLoginIp, String prevLoginMethod,
                             int failedBeforeLogin,
                             String lastFailedAt, String lastFailedIp, String lastFailedReason,
                             String currentLoginAt, String currentLoginMethod, boolean firstLogin) {

        /** Kullanıcı bulunamadığında / damga yazılamadığında dönen boş kayıt (hep "ilk giriş" gibi görünür). */
        public static LoginStamp empty() {
            return new LoginStamp(null, null, null, 0, null, null, null, null, null, true);
        }
    }

    /**
     * Başarılı giriş: giriş bilgisi kaydırması + tek-aktif-oturum kaydı — <b>TEK</b> {@code save()}.
     *
     * <p>Kaydırma: {@code prev_* ← last_*}, {@code last_* ← şimdi}, sayaç anlık görüntüsü alınıp
     * ({@code failedBeforeLogin}) sıfırlanır. Kullanıcıya gösterilen değer daima {@code prev_*}'dir;
     * içinde bulunduğu oturumun kendi zamanını "son giriş" diye göstermek "bu ben miydim?" sorusunu
     * cevaplamaz.
     *
     * <p>ÇAĞRI YERİ ÖNEMLİ: bu metot yalnız oturum GERÇEKTEN kurulduğunda çağrılmalıdır.
     * {@code AuthController} akışında 409 (başka yerde aktif oturum) dalı oturum kurmadan döner —
     * damga oraya bağlanırsa kullanıcı hiç giremeden "giriş oldu" yazılır.
     *
     * @return kaydırma öncesi değerler (kullanıcıya gösterilecek olan)
     */
    @Transactional
    public LoginStamp recordSuccessfulLogin(String username, String sessionId,
                                            String clientIp, LoginMethod method) {
        if (username == null || sessionId == null) return LoginStamp.empty();
        String methodName = method == null ? null : method.name();
        LoginStamp stamp = userRepo.findByUsername(username).map(u -> {
            String now = ISO.format(Instant.now());

            if (shouldShiftStamp(u.getLastLoginAt(), now)) {
                u.setPrevLoginAt(u.getLastLoginAt());
                u.setPrevLoginIp(u.getLastLoginIp());
                u.setPrevLoginMethod(u.getLastLoginMethod());
                u.setFailedBeforeLogin(u.getFailedSinceLogin() == null ? 0 : u.getFailedSinceLogin());
                u.setFailedSinceLogin(0);
                u.setLastLoginAt(now);
                u.setLastLoginIp(clientIp);
                u.setLastLoginMethod(methodName);
            }
            // Kaydırma atlandıysa (dedupe penceresi) alanlara DOKUNULMAZ: az önceki gerçek girişin
            // damgası korunur. Her iki durumda da okunan değerler kaydırma SONRASI hâldir — yani
            // /api/me'nin döndüreceğiyle birebir aynı. (Kullanıcıya gösterilen "önceki girişiniz",
            // kaydırmadan sonra prev_* alanında duran eski last_* değeridir.)
            u.setActiveSessionId(sessionId);
            u.setLastSeenAt(now);                          // login = taze etkinlik
            userRepo.save(u);                              // ← TEK yazma (oturum + damga birlikte)

            return new LoginStamp(u.getPrevLoginAt(), u.getPrevLoginIp(), u.getPrevLoginMethod(),
                    u.getFailedBeforeLogin() == null ? 0 : u.getFailedBeforeLogin(),
                    u.getLastFailedLoginAt(), u.getLastFailedLoginIp(), u.getLastFailedLoginReason(),
                    u.getLastLoginAt(), u.getLastLoginMethod(), u.getPrevLoginAt() == null);
        }).orElse(LoginStamp.empty());

        activeSessionCache.remove(normalizeUsername(username));   // yeni login anında etkisin (F2 evict)
        return stamp;
    }

    /** {@link LoginStamp}'i mevcut kullanıcı satırından okur (giriş anı DIŞI — {@code /api/me}). */
    public static LoginStamp stampOf(AppUser u) {
        if (u == null) return LoginStamp.empty();
        return new LoginStamp(u.getPrevLoginAt(), u.getPrevLoginIp(), u.getPrevLoginMethod(),
                u.getFailedBeforeLogin() == null ? 0 : u.getFailedBeforeLogin(),
                u.getLastFailedLoginAt(), u.getLastFailedLoginIp(), u.getLastFailedLoginReason(),
                u.getLastLoginAt(), u.getLastLoginMethod(), u.getPrevLoginAt() == null);
    }

    /** Kaydırma yapılmalı mı? İlk giriş → evet; son giriş dedupe penceresinden eskiyse → evet. */
    private boolean shouldShiftStamp(String lastLoginAt, String now) {
        if (lastLoginAt == null || lastLoginAt.isBlank()) return true;
        String threshold = ISO.format(Instant.now().minusSeconds(stampDedupeSeconds));
        return lastLoginAt.compareTo(threshold) < 0;   // ISO-UTC sabit genişlikte → leksikografik karşılaştırma güvenli
    }

    /**
     * Başarısız giriş damgası — yalnız kullanıcı satırı VARSA.
     *
     * <p>Bilinmeyen kullanıcı adında çağrılmaz: güncellenecek satır yoktur ve "yazıldı mı"
     * gözlemlenebilir olsaydı kullanıcı enumeration yüzeyi açardı.
     */
    @Transactional
    public void recordFailedLogin(String username, String clientIp, String reasonCode) {
        if (username == null || username.isBlank()) return;
        userRepo.bumpFailedLogin(username, ISO.format(Instant.now()), clientIp, reasonCode);
    }

    /** Oturum ping'i (frontend ~15 sn): kullanıcının güncel oturumunun lastSeenAt'ini tazeler.
     *  Süpersede oturum (sid eşleşmez) güncellenmez — interceptor zaten 401 verir. */
    @Transactional
    public void touchActiveSession(String username, String sessionId) {
        if (username == null || sessionId == null) return;
        // F3: ping başına UPDATE debounce'u — 60 sn'de en fazla 1 yazma (60+15 < 120 sn tazelik penceresi).
        String key = normalizeUsername(username) + ":" + sessionId;
        long now = System.currentTimeMillis();
        Long last = lastTouchAtMs.get(key);
        if (last != null && now - last < touchDebounceMs) return;
        if (lastTouchAtMs.size() > SESSION_MAP_MAX) lastTouchAtMs.clear();
        lastTouchAtMs.put(key, now);
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
        String key = normalizeUsername(username);
        long now = System.currentTimeMillis();
        ActiveSidEntry e = activeSessionCache.get(key);
        String active;
        if (e != null && now - e.atMs() < supersedeCacheMs) {
            active = e.sid();   // taze cache — DB'ye gitme (istek başına SELECT debounce'u, F2)
        } else {
            // Sıcak yol: tam entity + EAGER teamIds join yerine tek-kolon projeksiyon (bkz. repo).
            active = userRepo.findActiveSessionIdByUsername(username).orElse(null);
            if (activeSessionCache.size() > SESSION_MAP_MAX) activeSessionCache.clear();
            activeSessionCache.put(key, new ActiveSidEntry(active, now));   // null sid de cache'lenir
        }
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
        activeSessionCache.remove(normalizeUsername(username));   // F2 evict
    }

    /** Açılışta: tüm stale activeSessionId kayıtlarını temizler (in-memory oturumlar restart'ı yaşamaz).
     *  Temizlenen satır sayısını döner. */
    @Transactional
    public int clearAllActiveSessions() {
        activeSessionCache.clear();   // F2 evict (açılış temizliği)
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
        activeSessionCache.remove(normalizeUsername(username));   // kick anında etkisin (F2 evict)
    }

    public Optional<Team> findTeamById(Long id) {
        return teamRepo.findById(id);
    }

    // ── Team scoping (Faz 3b) ───────────────────────────────────────────────
    // null  = unrestricted (global admin / AUDIT read)
    // list  = restricted to those team ids (empty = nothing)

    /** Read scope: which teams' objects this user may SEE. */
    public List<Long> computeViewTeamIds(AppUser u) {
        if (u == null) return List.of();
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
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (u.getTeamIds() != null) for (Long t : u.getTeamIds()) if (t != null) ids.add(t);
        if (u.getTeamId() != null) ids.add(u.getTeamId());
        return new ArrayList<>(ids);
    }

    /** Write scope: which teams' objects this user may MANAGE. */
    public List<Long> computeManageTeamIds(AppUser u) {
        if (u == null) return List.of();
        String role = u.getSystemRole();
        boolean ldap = "LDAP".equalsIgnoreCase(u.getAuthSource());
        if ("ADMIN".equals(role) && !ldap) return null;         // global manage
        if ("TEAM_ADMIN".equals(role)) return ledPlusOwnTeamIds(u);
        // müdür (AD admin), USER, AUDIT → no team management
        return List.of();
    }

    private List<Long> subordinateTeamIds(Long managerId) {
        if (managerId == null) return new ArrayList<>();
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (AppUser sub : userRepo.findByManagerId(managerId)) {
            if (sub.getTeamIds() != null) for (Long t : sub.getTeamIds()) if (t != null) ids.add(t);
            if (sub.getTeamId() != null) ids.add(sub.getTeamId());
        }
        return new ArrayList<>(ids);
    }

    private List<Long> ledPlusOwnTeamIds(AppUser u) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (Team t : teamRepo.findByLeaderId(u.getId())) ids.add(t.getId());
        if (u.getTeamIds() != null) for (Long t : u.getTeamIds()) if (t != null) ids.add(t);
        if (u.getTeamId() != null) ids.add(u.getTeamId());
        return new ArrayList<>(ids);
    }

    /** Verilen takım id'lerini ada çevirir (sıra korunur, bulunamayanlar atlanır) — /me için. */
    public List<String> teamNamesFor(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        Map<Long, String> byId = new HashMap<>();
        teamRepo.findAllById(ids).forEach(t -> byId.put(t.getId(), t.getName()));
        List<String> names = new ArrayList<>();
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
                               Collection<Long> teamIds, String orgRole) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Username cannot be blank");
        if (rawPassword == null || rawPassword.length() < passwordMinLength) throw new IllegalArgumentException("Password too short (min " + passwordMinLength + " chars)");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Email is required");
        LinkedHashSet<Long> teams = normalizeTeams(teamIds);
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
    private void applyTeams(AppUser user, LinkedHashSet<Long> teams) {
        user.setTeamIds(teams);
        user.setTeamId(teams.isEmpty() ? null : teams.iterator().next());
    }

    /** Gelen id koleksiyonunu sırayı koruyarak tekilleştirir (null'ları atar). */
    private LinkedHashSet<Long> normalizeTeams(Collection<Long> teamIds) {
        LinkedHashSet<Long> set = new LinkedHashSet<>();
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
                               String systemRole, Collection<Long> teamIds, Boolean active, String orgRole) {
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
        if (!Objects.equals(newOrg, user.getOrgRole())) {
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

        String tempPwd = com.sitemonitor.util.PasswordGenerator.generate();
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
