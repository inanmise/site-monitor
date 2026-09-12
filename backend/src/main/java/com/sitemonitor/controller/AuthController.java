package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.Team;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.LdapDirectoryService;
import com.sitemonitor.service.LdapProvisioningService;
import com.sitemonitor.service.LdapSettingsService;
import com.sitemonitor.service.PermissionCatalog;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.WeeklyReportService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    @Value("${server.servlet.session.cookie.secure:false}")
    private boolean cookieSecure;

    /** Konfigüre bootstrap admin kullanıcı adı (site.monitor.username). Settings (SMTP/LDAP/secret/DB)
     *  geçidi bu kullanıcıyı HER ZAMAN geçirir — literal "admin" yerine (kilitlenme-güvenli). */
    @Value("${site.monitor.username:user}")
    private String bootstrapAdminUsername;

    @Value("${site.monitor.login.max-attempts:10}")
    private int maxLoginAttempts;

    /** How long (seconds) an IP stays blocked after hitting max-attempts. */
    @Value("${site.monitor.login.block-seconds:30}")
    private int blockSeconds;

    @Value("${site.monitor.remember.ttl-seconds:604800}")
    private int rememberTtlSeconds;

    private final AuditService auditService;
    private final RememberMeService rememberMeService;
    private final com.sitemonitor.service.DeviceHistoryService deviceHistoryService;
    private final com.sitemonitor.repository.RememberMeTokenRepository rememberMeTokenRepo;
    private final com.sitemonitor.service.LoginIssueService loginIssueService;
    private final com.sitemonitor.service.LoginIssueMailService loginIssueMailService;
    private final com.sitemonitor.service.AppSettingsService appSettings;
    private final UserService userService;
    private final com.sitemonitor.repository.AuditLogRepository auditLogRepo;
    private final ClientIpResolver clientIpResolver;

    @Autowired(required = false)
    private PermissionService permissionService;

    /** Sayfa kullanımı (System Health #1) — opsiyonel: birim testlerinde bean yok. */
    @Autowired(required = false)
    private com.sitemonitor.service.PageUsageService pageUsageService;

    // LDAP/AD — optional so @WebMvcTest contexts without these beans still load.
    @Autowired(required = false)
    private LdapSettingsService ldapSettings;

    @Autowired(required = false)
    private LdapDirectoryService ldapDirectory;

    @Autowired(required = false)
    private LdapProvisioningService ldapProvisioning;

    @Autowired(required = false)
    private WeeklyReportService weeklyReportService;

    // Per-IP attempt counter within a sliding 60-second window
    private final ConcurrentHashMap<String, AtomicInteger> loginAttempts = new ConcurrentHashMap<>();
    // Per-IP: timestamp when the current counting window started
    private final ConcurrentHashMap<String, Long> windowStart = new ConcurrentHashMap<>();
    // Per-IP: absolute timestamp (ms) when the block expires
    private final ConcurrentHashMap<String, Long> blockedUntil = new ConcurrentHashMap<>();

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {

        String clientIp = resolveClientIp(request);
        String username = body.getOrDefault("username", "").strip();

        // 1. IP-based rate limit
        long waitSecs = blockedSecondsRemaining(clientIp);
        if (waitSecs > 0) {
            log.warn("Login rate limit exceeded: IP={} wait={}s", clientIp, waitSecs);
            auditService.recordRateLimited(
                username.isBlank() ? null : username, clientIp, request.getHeader("User-Agent"));
            return ResponseEntity.status(429).body(Map.of(
                "success", false,
                "error", "Too many login attempts. Please wait.",
                "wait_seconds", waitSecs));
        }

        // 2. Per-user progressive lockout (DB-persisted)
        if (!username.isBlank()) {
            UserService.LockoutStatus ls = userService.checkLockout(username);
            if (ls.isBlocked()) {
                auditService.recordRateLimited(username, clientIp, request.getHeader("User-Agent"));
                if (ls.permanent()) {
                    return ResponseEntity.status(423).body(Map.of(
                        "success", false, "locked", true,
                        "error", "Account permanently locked. Contact administrator."));
                }
                return ResponseEntity.status(423).body(Map.of(
                    "success", false, "wait_seconds", ls.secondsRemaining(),
                    "error", "Account temporarily locked."));
            }
        }

        String password  = body.getOrDefault("password", "").strip();
        boolean rememberMe = Boolean.parseBoolean(body.getOrDefault("remember_me", "false"));

        // Route by auth source: LOCAL accounts (incl. bootstrap admin) → BCrypt;
        // non-local / unknown usernames → AD bind when LDAP is enabled.
        Optional<AppUser> existing = userService.findByUsername(username);
        boolean isLocalAccount = existing.isPresent()
                && (existing.get().getAuthSource() == null
                    || "LOCAL".equalsIgnoreCase(existing.get().getAuthSource()));

        Optional<AppUser> userOpt;
        if (isLocalAccount) {
            userOpt = userService.authenticate(username, password);   // local BCrypt
        } else if (ldapEnabled()) {
            userOpt = tryLdapLogin(username, password);               // AD bind + provision (USER)
        } else {
            userOpt = userService.authenticate(username, password);   // LDAP off → local only
        }

        if (userOpt.isPresent()) {
            AppUser user = userOpt.get();

            // Admin-issued temp passwords expire after 24 hours. Treat an
            // expired temp pwd as a separate failure with its own error_code
            // so the UI can show a precise message instead of "wrong password".
            if (userService.isTempPasswordExpired(user)) {
                log.info("Login rejected — temp password expired: user={} IP={}", username, clientIp);
                // Parola doğruydu ama giriş REDDEDİLDİ → kullanıcının güvenlik özetinde başarısız
                // deneme olarak görünmeli (canonical username: yazılan case farklı olabilir).
                userService.recordFailedLogin(user.getUsername(), clientIp, "TEMP_PASSWORD_EXPIRED");
                auditService.recordLogin(username, user.getId(), user.getTeamId(),
                        user.getSystemRole(), clientIp,
                        request.getHeader("User-Agent"), null, false,
                        "TEMP_PASSWORD_EXPIRED", null, 5);
                return ResponseEntity.status(401).body(Map.of(
                        "success", false,
                        "error", "Temporary password expired. Ask your admin to reset again.",
                        "error_code", "TEMP_PASSWORD_EXPIRED"));
            }

            loginAttempts.remove(clientIp);
            windowStart.remove(clientIp);
            blockedUntil.remove(clientIp);
            userService.clearLockoutOnSuccess(username);

            // Tek aktif oturum onayı: kullanıcının başka bir yerde aktif oturumu varsa ve henüz
            // onaylamadıysa, mevcut oturumu DÜŞÜRMEDEN 409 dön → frontend onay modalı gösterir.
            // Onaylarsa force_login=true ile tekrar gelir; o zaman aşağıdaki akış (recordActiveSession)
            // eski oturumu düşürüp girişi tamamlar. (TERMINATED sentinel'i = zaten kapatılmış, sayılmaz.)
            boolean forceLogin = Boolean.parseBoolean(body.getOrDefault("force_login", "false"));
            // Yalnız CANLI (son ping penceresi içinde) bir aktif oturum varsa onay iste; logout'suz
            // kapatılan/ölen oturumlar tazelik penceresi dışına düşer → yanlış onay çıkmaz.
            boolean hasActiveElsewhere = userService.hasLiveSession(user);
            if (hasActiveElsewhere && !forceLogin) {
                log.info("Login needs confirmation — active session exists elsewhere: user={} IP={}", username, clientIp);
                return ResponseEntity.status(409).body(Map.of(
                        "success", false,
                        "error_code", "ACTIVE_SESSION_EXISTS",
                        "error", "An active session already exists for this account elsewhere."));
            }

            HttpSession oldSession = request.getSession(false);
            if (oldSession != null) oldSession.invalidate();
            HttpSession newSession = request.getSession(true);
            populateSession(newSession, user);
            // Tek aktif oturum (store-agnostik): bu oturumu kullanıcının "aktif" oturumu olarak kaydet —
            // AuthInterceptor her istekte karşılaştırır, eşleşmeyen eski oturumu kapatır. Ayrıca eski
            // remember-me token'larını iptal et (eski tarayıcı cookie ile sessizce geri dönüp kicklemesin).
            // CANONICAL username (user.getUsername()) kullan: AD/LDAP girişinde yazılan case (ör. "N12345")
            // DB'deki canonical'dan ("n12345") farklı olabilir; recordActiveSession→findByUsername case-sensitive
            // olduğundan yazılan case'le satır bulunamaz ve aktif-oturum/lastSeenAt set EDİLMEZ → kullanıcı
            // "aktif" sayılmaz. populateSession + sessionPing zaten canonical kullanıyor; burada da hizala.
            // Giriş damgası + aktif oturum kaydı TEK yazmada. Damga BURADA basılır (409 dalından
            // SONRA): oturum kurulmadan basılsaydı, kullanıcı hiç giremediği hâlde "giriş oldu"
            // yazılır ve gösterilecek "önceki girişiniz" değeri boşa harcanırdı.
            UserService.LoginStamp loginStamp = userService.recordSuccessfulLogin(
                    user.getUsername(), newSession.getId(), clientIp, UserService.LoginMethod.PASSWORD);
            // CANONICAL username (yazılan case DEĞİL): app_users ve remember_me_tokens BÜYÜK harfe
            // normalize ediliyor (applySchemaPatches). Yazılan case ile silmek, kullanıcı bir gün
            // "n12345" ertesi gün "N12345" yazdığında eşleşmez ve ÖKSÜZ bir token hayatta kalır —
            // yani "her login eski token'ları iptal eder" güvencesi sessizce delinir.
            rememberMeService.invalidateAllForUser(user.getUsername());

            log.info("User logged in: {} (role={}, teamId={}, rememberMe={}, IP={})",
                    user.getUsername(), user.getSystemRole(), user.getTeamId(), rememberMe, clientIp);
            // Audit actor'ı da CANONICAL: yazılan case ile kaydedilirse aktif-oturum kartı enrichment'i
            // (findTopByActor(canonical, sid)) eşleşmez → login zamanı/IP boş kalır; ayrıca aynı kullanıcı
            // audit'te iki farklı case ("N12345"/"n12345") ile görünür.
            auditService.recordLogin(user.getUsername(), user.getId(), user.getTeamId(),
                    user.getSystemRole(), clientIp,
                    request.getHeader("User-Agent"), newSession.getId(), true, null, null, 5);

            if (rememberMe) {
                String token = rememberMeService.generateToken(
                        user.getUsername(),                       // CANONICAL — iptal yolu da bununla arıyor
                        clientIp, request.getHeader("User-Agent"));
                // SameSite=Strict ZORUNLU — oturum çerezi prod'da zaten Strict ve bu uygulamanın
                // TEK CSRF savunması. Remember-me çerezi jakarta Cookie ile yazılıyordu ve o sınıfın
                // setSameSite'ı yok; bayrak sessizce eksik kalıyordu. AuthInterceptor bu çerezle
                // TAM OTURUMU yeniden kurduğu için, Lax-varsayılanı uygulamayan bir tarayıcıda
                // cross-site POST kimlikli çalışıyor ve oturum çerezindeki Strict etkisiz kalıyordu.
                // (Gövdesiz POST uçları düz HTML formuyla tetiklenebilir — purge-deleted dâhil.)
                ResponseCookie cookie = ResponseCookie.from(RememberMeService.COOKIE_NAME, token)
                        .maxAge(rememberTtlSeconds)
                        .httpOnly(true)
                        .secure(cookieSecure)
                        .path("/")
                        .sameSite("Strict")
                        .build();
                response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
            }
            return ResponseEntity.ok(buildMeResponse(user, newSession, loginStamp));
        }

        // 3. Failed — record attempt, check for BRUTE_FORCE, apply progressive lockout
        recordFailedAttempt(clientIp);
        log.warn("Failed login attempt: IP={}, username={}", clientIp, username);
        // Look up user's lockout context: window start (countSince) and required failures for this level.
        // Ayrıca bilinmeyen-kullanıcı ile yanlış-parolayı denetim kaydı için ayır (yalnız audit'te —
        // istemciye DÖNÜLMEZ, kullanıcı enumeration sızmaz). Boş kullanıcı adı da UNKNOWN_USER sayılır.
        String lastLockoutAt = null;
        int failuresNeeded = 5;
        boolean userExists = false;
        if (!username.isBlank()) {
            var failedUser = userService.findByUsername(username);
            if (failedUser.isPresent()) {
                userExists = true;
                var u = failedUser.get();
                lastLockoutAt = u.getLastLockoutAt();
                failuresNeeded = userService.failuresNeededForLevel(u.getFailedBlockCount());
                // Kullanıcının güvenlik özeti için damga. applyProgressiveLockout'tan (aşağıda)
                // ÖNCE: o metot entity'yi yeniden yükleyip kaydediyor; ters sırada bayat kopya
                // az önce artırılan sayacı ezerdi. UNKNOWN_USER'da güncellenecek satır yok.
                userService.recordFailedLogin(u.getUsername(), clientIp, "BAD_PASSWORD");
            }
        }
        String reasonCode = userExists ? "BAD_PASSWORD" : "UNKNOWN_USER";
        com.sitemonitor.model.AuditLog logged = auditService.recordLogin(
                username, null, null, null, clientIp,
                request.getHeader("User-Agent"), null, false, reasonCode,
                lastLockoutAt, failuresNeeded);

        if (!username.isBlank() && logged.getAnomalyFlags() != null
                && logged.getAnomalyFlags().contains("BRUTE_FORCE")) {
            UserService.LockoutStatus ls = userService.applyProgressiveLockout(username);
            // Hesap kilit GEÇİŞİ — ayrık denetim olayı (altında yatan failed-login zaten kaydedildi).
            auditService.recordAction("ACCOUNT_LOCKED", username, null, null, null, "USER", username,
                    ls.permanent() ? "{\"lock\":\"permanent\"}"
                            : "{\"lock\":\"temporary\",\"seconds\":" + ls.secondsRemaining() + "}",
                    auditService.resolveIp(request), auditService.resolveUa(request), null);
            if (ls.permanent()) {
                return ResponseEntity.status(423).body(Map.of(
                    "success", false, "locked", true,
                    "error", "Account permanently locked. Contact administrator."));
            }
            return ResponseEntity.status(423).body(Map.of(
                "success", false, "wait_seconds", ls.secondsRemaining(),
                "error", "Account temporarily locked."));
        }
        return ResponseEntity.status(401)
                .body(Map.of("success", false, "error", "Invalid username or password"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            HttpServletRequest request,
            HttpServletResponse response) {

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            // Yeni VE eski (rename öncesi) cookie adları — ikisi de geçersizlenir/silinir.
            Arrays.stream(cookies)
                    .filter(c -> RememberMeService.COOKIE_NAME.equals(c.getName())
                            || RememberMeService.LEGACY_COOKIE_NAME.equals(c.getName()))
                    .forEach(c -> {
                        rememberMeService.invalidate(c.getValue());
                        Cookie del = new Cookie(c.getName(), "");
                        del.setMaxAge(0);
                        del.setHttpOnly(true);
                        del.setPath("/");
                        response.addCookie(del);
                    });
        }

        HttpSession session = request.getSession(false);
        if (session != null) {
            String username = (String) session.getAttribute("username");
            Object userId   = session.getAttribute("userId");
            String sid      = session.getId();
            Long uid = userId instanceof Long l ? l : userId != null ? Long.parseLong(userId.toString()) : null;
            // Çıkışta kullanıcının haftalık rapor düzenleme kilitlerini bırak —
            // aksi halde başkaları "X düzenliyor" ipucunu (kilit bayatlayana dek) görür.
            if (weeklyReportService != null && uid != null) {
                try { weeklyReportService.releaseLocksForUser(uid); } catch (Exception ignored) {}
            }
            // Tek aktif oturum: kayıtlı aktif oturum bu ise temizle (başka cihazdaki yeni oturumu silme).
            if (username != null) userService.clearActiveSession(username, sid);
            session.invalidate();
            if (username != null) {
                auditService.recordLogout(username, uid, resolveClientIp(request), sid);
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "message", "Logged out"));
    }

    /** Hafif oturum geçerlilik yoklaması — frontend periyodik çağırır. Oturum başka yerden
     *  süpersede edildiyse AuthInterceptor bu metoda girmeden 401 döner; böylece dropped taraf
     *  boştayken bile kısa sürede /?session=expired'a düşer (tam getMe yükü olmadan). */
    @GetMapping("/session/ping")
    public ResponseEntity<Map<String, Object>> sessionPing(HttpSession session,
                                                           @RequestParam(required = false) String tab) {
        // Oturum canlılığını tazele (aktif sayım + login-onayı bunu kullanır). Süpersede oturum bu
        // metoda girmeden interceptor'da 401 alır; buraya gelen istek geçerli/güncel oturumdur.
        String username = (String) session.getAttribute("username");
        if (username != null) userService.touchActiveSession(username, session.getId());
        // Görünür sekme (yalnız sekme anahtarı; URL parametreleri değil) → sayfa kullanımı sayacı
        if (username != null && tab != null && pageUsageService != null) pageUsageService.record(username, tab);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Hareketsizlik oturum kapatma süresi (dakika). Varsayılan 60.
     *
     * <p>ALT SINIR 1 dakika: 0/negatif bir değer herkesi anında dışarı atardı ve ayarı yanlış
     * giren kişi kendi düzeltemezdi (giriş yapar yapmaz atılırdı). ÜST SINIR sunucu oturum
     * ömrüyle (24 sa) hizalı — ötesini vaat etmek yalan olurdu, sunucu zaten oturumu düşürür.
     */
    private int inactivityMinutes() {
        int v = appSettings.getInt("site.monitor.ui.inactivity-minutes", 60);
        return Math.max(1, Math.min(v, 24 * 60));
    }

    /** Kapatmadan önceki uyarı penceresi (sn). Toplam süreyi AŞAMAZ; aşarsa uyarı hiç görünmezdi. */
    private int inactivityWarnSeconds() {
        int v = appSettings.getInt("site.monitor.ui.inactivity-warn-seconds", 60);
        int max = Math.max(1, inactivityMinutes() * 60 - 1);
        return Math.max(1, Math.min(v, max));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("success", false, "error", "Not authenticated"));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("username", username);
        resp.put("user_id", session.getAttribute("userId"));
        resp.put("team_id", session.getAttribute("teamId"));
        resp.put("team_name", session.getAttribute("teamName"));
        resp.put("system_role", session.getAttribute("systemRole"));
        resp.put("must_change_password",
                Boolean.TRUE.equals(session.getAttribute("mustChangePassword")));
        // Hareketsizlik ayari: arayuz zamanlayicisi bunu okur. Ayri uc/istek yok — zamanlayici
        // zaten yalniz oturum acikken kosuyor, yani /me tam da dogru tasiyici.
        resp.put("inactivity_minutes", inactivityMinutes());
        resp.put("inactivity_warn_seconds", inactivityWarnSeconds());
        // Profile fields (AD-provisioned). Loaded fresh so they reflect the latest sync.
        userService.findByUsername(username).ifPresent(u -> {
            resp.put("display_name", u.getDisplayName());
            resp.put("first_name", u.getFirstName());
            resp.put("last_name", u.getLastName());
            resp.put("email", u.getEmail());
            resp.put("employee_id", u.getEmployeeId());
            resp.put("title", u.getTitle());
            resp.put("phone", u.getPhone());
            resp.put("department", u.getDepartment());
            resp.put("company_level", u.getCompanyLevel());
            resp.put("org_role", u.getOrgRole());
            resp.put("mudurluk_name", u.getMudurlukName());
            // E1: kişi kendi push tercihi — Ayarlar sayfasındaki anahtar bunu okur.
            resp.put("push_opt_out", Boolean.TRUE.equals(u.getPushOptOut()));
            resp.put("manager_sicil", u.getManagerSicil());
            resp.put("has_photo", u.getPhotoBase64() != null && !u.getPhotoBase64().isBlank());
            // Giriş güvenliği özeti — kullanıcı satırından okunur, EK SORGU YOK. Değerler kaydırma
            // sonrası hâldir, yani sayfa yenilendiğinde (F5) giriş yanıtındakiyle birebir aynıdır.
            resp.put("login_info", loginInfo(UserService.stampOf(u)));
            putTeams(resp, u);
        });
        // Faz 3b: scope flags for the UI (hide global-only tabs from scoped müdür-admins).
        resp.put("global_admin", SessionScope.isGlobalAdmin(session));
        resp.put("scoped", session.getAttribute("viewTeamIds") != null);
        return ResponseEntity.ok(resp);
    }

    /** Returns the logged-in user's AD photo (JPEG), or 404 if none. */
    @GetMapping("/me/photo")
    public ResponseEntity<byte[]> mePhoto(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) return ResponseEntity.status(401).build();
        return userService.findByUsername(username)
                .map(u -> photoResponse(u.getPhotoBase64()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Decodes a stored base64 JPEG into an image response. When no (or unusable) photo exists,
     * returns 204 No Content — NOT 404 — so the frontend {@code <img onError>} still falls back to
     * the initials avatar, but the response is not counted as an error by the HTTP metrics
     * (which flag status &gt;= 400). Most users have no AD/LDAP photo, so 404 here inflated the
     * dashboard error rate with benign "missing avatar" misses. A Cache-Control on the empty
     * response also lets the browser stop re-requesting a photo it knows is absent.
     */
    static ResponseEntity<byte[]> photoResponse(String base64) {
        if (base64 == null || base64.isBlank()) {
            return ResponseEntity.noContent().header("Cache-Control", "private, max-age=3600").build();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.trim());
            return ResponseEntity.ok()
                    .header("Content-Type", "image/jpeg")
                    .header("Cache-Control", "private, max-age=3600")
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.noContent().build();
        }
    }

    /**
     * Self-service password change. Any authenticated user can rotate their own
     * password by re-proving knowledge of the current one — no admin role
     * required. Same UserService rules apply (length, history, archive).
     */
    /**
     * E1 kişi opt-out: kullanıcı YALNIZ KENDİ push bayrağını yazar (id/sicil parametresi yok —
     * IDOR yüzeyi hiç açılmaz). Kapatan kişi teslimat günlüğünde SKIPPED_USER_OPT_OUT görünür,
     * yani "neden bana gelmedi" sorusunun cevabı kayıtlıdır.
     */
    @PostMapping("/me/push-opt-out")
    public ResponseEntity<Map<String, Object>> setPushOptOut(
            @RequestBody Map<String, Object> body, HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) throw new SecurityException("Not authenticated");
        var userOpt = userService.findByUsername(username);
        if (userOpt.isEmpty()) throw new SecurityException("Not authenticated");
        boolean optOut = Boolean.TRUE.equals(body.get("opt_out"));
        var u = userOpt.get();
        u.setPushOptOut(optOut);
        userService.savePushOptOut(u);
        auditService.recordAction("USER_PUSH_OPT_OUT", session, "USER", String.valueOf(u.getId()),
                optOut ? "Kişi webhook push bildirimini KAPATTI" : "Kişi webhook push bildirimini AÇTI", null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("push_opt_out", optOut);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/me/change-password")
    public ResponseEntity<Map<String, Object>> changeOwnPassword(
            @RequestBody Map<String, String> body,
            HttpSession session, HttpServletRequest request) {
        String username = (String) session.getAttribute("username");
        Object userIdObj = session.getAttribute("userId");
        if (username == null || userIdObj == null) {
            throw new SecurityException("Not authenticated");
        }
        Long userId = userIdObj instanceof Long ? (Long) userIdObj : Long.valueOf(userIdObj.toString());

        String currentPwd = body.get("current_password");
        String newPwd     = body.get("new_password");
        if (currentPwd == null || currentPwd.isBlank()) {
            throw new IllegalArgumentException("Current password required");
        }

        userService.changePassword(userId, newPwd, username, currentPwd);
        // Parola değişimi TÜM hatırlanan girişleri iptal eder.
        //
        // Bu eksikti: parolasının çalındığını düşünüp parolasını değiştiren kullanıcının eski
        // cihazı, remember-me cookie'siyle 7 gün daha (TTL) sessizce otomatik giriş yapabiliyordu
        // — yani parola değiştirmek saldırganı DIŞARI ATMIYORDU. Bilinen iyi pratik; ekrandaki
        // "parola değiştir" kısayoluyla da tutarlı.
        //
        // CANONICAL username: token satırları büyük harfe normalize; yazılan case ile silmek
        // eşleşmez (bkz. login yolundaki aynı düzeltme).
        userService.findByUsername(username)
                .ifPresent(u -> rememberMeService.invalidateAllForUser(u.getUsername()));
        // Successful self-change clears the forced-change session flag too —
        // AuthInterceptor uses it to gate other endpoints.
        session.setAttribute("mustChangePassword", false);
        auditService.recordAction("SELF_PASSWORD_CHANGE", session, request,
                "USER", userId.toString(), null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Password changed");
        return ResponseEntity.ok(resp);
    }

    /**
     * Self-service audit log. Returns only entries where actor equals the
     * caller's session username (exact, case-insensitive). The actor cannot
     * be overridden via query string — a USER cannot see anyone else's log.
     * /api/admin/audit (admin/audit-only) remains the system-wide view.
     */
    @GetMapping("/me/permissions")
    public ResponseEntity<Map<String, Object>> myPermissions(HttpSession session) {
        if (!Boolean.TRUE.equals(session.getAttribute("authenticated"))) {
            return ResponseEntity.status(401).body(Map.of("success", false, "error", "Not authenticated"));
        }
        String role = (String) session.getAttribute("systemRole");
        Map<String, Map<String, Boolean>> snapshot = permissionService != null
            ? permissionService.snapshotForRole(role)
            : PermissionCatalog.defaultsFor(role);
        return ResponseEntity.ok(Map.of("success", true, "data", snapshot));
    }

    @GetMapping("/me/audit")
    public ResponseEntity<Map<String, Object>> myAudit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(defaultValue = "false") boolean anomalyOnly,
            HttpSession session) {
        Object usernameAttr = session.getAttribute("username");
        if (usernameAttr == null) throw new SecurityException("Not authenticated");
        String username = usernameAttr.toString().toLowerCase();

        int sz = Math.max(1, Math.min(size, 200));
        var result = auditLogRepo.findOwnFiltered(
                username,
                (eventType == null || eventType.isBlank()) ? null : eventType,
                (outcome   == null || outcome.isBlank())   ? null : outcome,
                (since     == null || since.isBlank())     ? null : since,
                (until     == null || until.isBlank())     ? null : until,
                anomalyOnly,
                org.springframework.data.domain.PageRequest.of(Math.max(0, page), sz));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("data", result.getContent());
        resp.put("total", result.getTotalElements());
        resp.put("page", result.getNumber());
        resp.put("total_pages", result.getTotalPages());
        return ResponseEntity.ok(resp);
    }

    // ── Cihaz Geçmişi / Oturum Güvenliği (SELF-SCOPE) ────────────────────────
    //
    // Güvenlik sözleşmesi (/me/audit ile AYNI): kullanıcı YALNIZ kendi verisini görür ve bunu
    // seçen bir PARAMETRE YOKTUR — kimlik oturumdan okunur. Başka kullanıcının verisine giden
    // hiçbir giriş kabul edilmediği için IDOR yüzeyi de yoktur.
    //
    // Yanıtlar oturum kimliği, token değeri ya da token hash'i TAŞIMAZ (bkz. DeviceHistoryService).

    @GetMapping("/me/devices")
    public ResponseEntity<Map<String, Object>> myDevices(HttpSession session, HttpServletRequest request) {
        AppUser user = requireSelf(session);
        // Cookie'deki ham token yalnız HASH'e çevrilip "bu cihaz mı" işaretlemesinde kullanılır.
        String currentHash = findRememberMeCookieValue(request)
                .map(rememberMeService::hashOf).orElse(null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("data", deviceHistoryService.devicesFor(user, currentHash));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/me/devices/logins")
    public ResponseEntity<Map<String, Object>> myDeviceLogins(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "false") boolean failed,
            HttpSession session) {
        AppUser user = requireSelf(session);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("data", deviceHistoryService.loginsFor(user, failed, page, size));
        return ResponseEntity.ok(resp);
    }

    /**
     * Hatırlanan cihazı iptal eder.
     *
     * <p>Sahiplik kontrolü SORGUNUN İÇİNDE: silinen satır sayısı 0 ise satır ya yok ya da
     * başkasının — İKİSİ DE 404 döner. 403 dönmek "bu id var ama senin değil" bilgisini
     * sızdırırdı (varlık sızıntısı).
     */
    @DeleteMapping("/me/devices/remembered/{id}")
    public ResponseEntity<Map<String, Object>> revokeRememberedDevice(
            @PathVariable Long id, HttpSession session,
            HttpServletRequest request, HttpServletResponse response) {
        AppUser user = requireSelf(session);

        int removed = rememberMeTokenRepo.deleteByIdAndUsername(id, user.getUsername());
        if (removed == 0) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Kayıt bulunamadı"));
        }

        // İptal edilen BU tarayıcının token'ıysa cookie de düşürülmeli, yoksa kullanıcı listeden
        // sildiği hâlde bir sonraki ziyarette yine otomatik giriş yapar (silme "işe yaramamış"
        // görünürdü). Hangi satırın silindiğini bilmiyoruz ama cookie'nin hash'i artık DB'de
        // OLMADIĞINDAN, düşürmek her hâlükârda doğru.
        findRememberMeCookieValue(request).ifPresent(raw -> {
            if (rememberMeTokenRepo.findByToken(rememberMeService.hashOf(raw)).isEmpty()) {
                clearRememberMeCookies(response);
            }
        });

        auditService.recordAction("REMEMBER_TOKEN_REVOKE", session, request,
                "remember_me_token", String.valueOf(id), "Hatırlanan cihaz iptal edildi");
        return ResponseEntity.ok(Map.of("success", true));
    }

    /**
     * Tüm hatırlanan girişleri iptal eder.
     *
     * <p>Mesaj DÜRÜST: tek-aktif-oturum modelinde kullanıcının başka CANLI oturumu zaten olamaz,
     * dolayısıyla burada "diğer oturumları kapattık" demek yanlış olurdu. Yaptığımız şey kalıcı
     * girişleri (remember-me) iptal etmektir.
     */
    @PostMapping("/me/devices/logout-others")
    public ResponseEntity<Map<String, Object>> logoutOtherDevices(
            HttpSession session, HttpServletRequest request, HttpServletResponse response) {
        AppUser user = requireSelf(session);

        rememberMeService.invalidateAllForUser(user.getUsername());
        clearRememberMeCookies(response);   // bu tarayıcınınki de iptal edildi
        auditService.recordAction("SESSION_REVOKE_ALL", session, request,
                "remember_me_token", user.getUsername(), "Tüm hatırlanan girişler iptal edildi");

        return ResponseEntity.ok(Map.of("success", true, "remembered_revoked", true));
    }

    /**
     * "Bu girişi ben yapmadım" — şüpheli giriş bildirimi (K7).
     *
     * <p>Self-scope: bildirilen audit satırı KULLANICININ KENDİ satırı olmak zorunda, aksi hâlde
     * 404. Böylece başkasının giriş kaydı hakkında bildirim üretilemez.
     */
    @PostMapping("/me/devices/report-login")
    public ResponseEntity<Map<String, Object>> reportSuspiciousLogin(
            @RequestBody Map<String, Object> body, HttpSession session, HttpServletRequest request) {
        AppUser user = requireSelf(session);
        Long auditId = body.get("auditId") instanceof Number n ? n.longValue() : null;
        if (auditId == null) return ResponseEntity.badRequest().body(Map.of("success", false, "error", "auditId zorunlu"));

        String actor = user.getUsername() == null ? "" : user.getUsername().toLowerCase();
        var row = auditLogRepo.findOwnById(auditId, actor);
        if (row.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("success", false, "error", "Kayıt bulunamadı"));
        }

        var a = row.get();
        String detail = "Kullanıcı bu girişi kendisinin yapmadığını bildirdi — "
                + a.getEventTime() + " · " + (a.getIpAddress() == null ? "IP yok" : a.getIpAddress())
                + " · " + com.sitemonitor.service.UserAgentSummary.labelOf(a.getUserAgent());

        String clientIp = auditService.resolveIp(request);
        String ua = request.getHeader("User-Agent");
        var report = loginIssueService.save(user.getUsername(), user.getEmail(), null, detail,
                java.util.List.of(), clientIp, ua, null,
                new com.sitemonitor.service.LoginIssueService.ReportMeta(
                        "USER_REPORT", "BLOCKER", null, null, "myactivity", null,
                        "audit#" + auditId));
        String refCode = com.sitemonitor.service.LoginIssueService.refCode(report);

        // BILDIRIM GERCEKTEN GIDER. Kayit acip beklemek, bu akisin butun degerini (hizli haber
        // verme) sifirlardi: hesabinin ele gecirildigini dusunen kullanici bildirir, kimse
        // haberdar olmazdi. Kural kardes akislarla AYNI — gunluk ozet acikken tekil admin maili
        // atlanir (ozet cron'u toplar), ACK her durumda gider.
        String adminEmail = appSettings.getString("site.monitor.system-admin.email", "");
        boolean digest = appSettings.getBoolean("site.monitor.issue-reports.daily-digest", false);
        if (adminEmail != null && !adminEmail.isBlank() && !digest) {
            loginIssueMailService.dispatchUserReport(report.getId(), refCode, adminEmail,
                    user.getUsername(), user.getEmail(), "BLOCKER", detail, null,
                    "audit#" + auditId, "myactivity", null, java.util.List.of(),
                    clientIp, ua, report.getReportedAt());
        }
        // ACK: bildiren kisi "gitti mi" diye merakta kalmasin — referans numarasiyla.
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            loginIssueMailService.dispatchAck(report.getId(), refCode, user.getEmail(),
                    user.getUsername(), null, detail, java.util.List.of(), report.getReportedAt());
        }

        auditService.recordAction("LOGIN_DISPUTED", session, request,
                "audit_log", String.valueOf(auditId), "Şüpheli giriş bildirildi (" + refCode + ")");
        // Referans numarasi arayuze doner: kullanici destege basvururken bunu soyleyebilsin.
        return ResponseEntity.ok(Map.of("success", true, "ref", refCode));
    }

    /** Remember-me cookie'lerini (yeni ve eski ad) tarayıcıdan düşürür. */
    private void clearRememberMeCookies(HttpServletResponse response) {
        for (String name : new String[]{ RememberMeService.COOKIE_NAME, RememberMeService.LEGACY_COOKIE_NAME }) {
            Cookie del = new Cookie(name, "");
            del.setMaxAge(0);
            del.setHttpOnly(true);
            del.setPath("/");
            response.addCookie(del);
        }
    }

    /** Oturumdaki kullanıcıyı çözer; yoksa 401. Cihaz uçlarının TEK kimlik kaynağı. */
    private AppUser requireSelf(HttpSession session) {
        Object usernameAttr = session.getAttribute("username");
        if (usernameAttr == null) throw new SecurityException("Not authenticated");
        return userService.findByUsername(usernameAttr.toString())
                .orElseThrow(() -> new SecurityException("Not authenticated"));
    }

    /** Cookie'deki ham remember-me token'ı (yeni ve eski ad) — yalnız hash'lenmek üzere okunur. */
    private java.util.Optional<String> findRememberMeCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return java.util.Optional.empty();
        return Arrays.stream(cookies)
                .filter(c -> RememberMeService.COOKIE_NAME.equals(c.getName())
                        || RememberMeService.LEGACY_COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** True when LDAP auth is configured and enabled. Null-safe (beans optional in tests). */
    private boolean ldapEnabled() {
        if (ldapSettings == null || ldapDirectory == null) return false;
        try {
            var s = ldapSettings.getOrDefaults();
            return s != null && Boolean.TRUE.equals(s.getEnabled());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Authenticates against AD and provisions/loads the local row (USER, no team).
     * Returns empty on wrong password / user-not-found / any LDAP error (caller then
     * records a normal failed attempt). Never throws.
     */
    private Optional<AppUser> tryLdapLogin(String username, String password) {
        try {
            Optional<LdapDirectoryService.LdapUser> ad =
                    ldapDirectory.authenticate(username, password);
            if (ad.isEmpty()) return Optional.empty();
            var u = ad.get();
            String uname = (u.username() != null && !u.username().isBlank()) ? u.username() : username;
            // Map all AD attributes → user/team/manager (Faz 3a).
            return Optional.of(ldapProvisioning.provisionFromAd(uname, u.dn(), u.attributes()));
        } catch (Exception e) {
            log.warn("LDAP login error for '{}': {}", username, e.getMessage());
            return Optional.empty();
        }
    }

    /** Görünen ad: displayName → ad+soyad (AD givenName+sn) → username. AD'de displayName boşsa ad soyad
     *  kullanılır → session displayName + haftalık rapor Oluşturan/Onaylayan/Son düzenleme sicil yerine ad soyad. */
    static String resolveDisplayName(AppUser user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) return user.getDisplayName();
        String full = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
                     + (user.getLastName()  != null ? user.getLastName()  : "")).trim();
        return !full.isEmpty() ? full : user.getUsername();
    }

    /** Write all user context into the session. */
    public void populateSession(HttpSession session, AppUser user) {
        session.setAttribute("authenticated", true);
        session.setAttribute("username", user.getUsername());
        session.setAttribute("displayName", resolveDisplayName(user));   // displayName → ad soyad → username
        session.setAttribute("userId", user.getId());
        session.setAttribute("teamId", user.getTeamId());
        session.setAttribute("systemRole", user.getSystemRole());
        // Settings (SMTP/LDAP/secret/DB) gate'i için: kullanıcı konfigüre bootstrap admin mi?
        session.setAttribute("bootstrapAdmin",
                user.getUsername() != null && user.getUsername().equalsIgnoreCase(bootstrapAdminUsername));
        session.setAttribute("mustChangePassword",
                Boolean.TRUE.equals(user.getMustChangePassword()));
        // Resolve team name
        String teamName = user.getTeamId() != null
                ? userService.findTeamById(user.getTeamId()).map(Team::getName).orElse(null)
                : null;
        session.setAttribute("teamName", teamName);

        // ── Team scope (Faz 3b): null = unrestricted (global admin / AUDIT) → no attribute. ──
        List<Long> view = userService.computeViewTeamIds(user);
        List<Long> manage = userService.computeManageTeamIds(user);
        if (view == null) session.removeAttribute("viewTeamIds");
        else session.setAttribute("viewTeamIds", new ArrayList<>(view));
        if (manage == null) session.removeAttribute("manageTeamIds");
        else session.setAttribute("manageTeamIds", new ArrayList<>(manage));

        // ÜYELİK kapsamı (2026-08-20, şablon kütüphanesi): "bu kullanıcı hangi takımların ÜYESİ?"
        // view/manage'ın ikisi de bu soruyu cevaplamıyor — view müdürde astların takımlarını
        // içeriyor, manage USER'da boş. ASLA null bırakılmaz: yokluğu "kısıtsız" anlamına
        // gelmemeli, boş liste "hiçbir takımın üyesi değil" demeli.
        session.setAttribute("memberTeamIds", new ArrayList<>(userService.computeMemberTeamIds(user)));
    }

    private Map<String, Object> buildMeResponse(AppUser user, HttpSession session,
                                                UserService.LoginStamp stamp) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Login successful");
        resp.put("login_info", loginInfo(stamp));
        resp.put("username", user.getUsername());
        resp.put("user_id", user.getId());
        resp.put("team_id", user.getTeamId());
        resp.put("team_name", user.getTeamId() != null
                ? userService.findTeamById(user.getTeamId()).map(Team::getName).orElse(null) : null);
        putTeams(resp, user);
        resp.put("system_role", user.getSystemRole());
        resp.put("must_change_password", Boolean.TRUE.equals(user.getMustChangePassword()));
        // Giris yanitina da konur: /me yalnizca acilista kosuyor, taze girişten sonra tekrar
        // cagrilmiyor. Konmazsa kullanicinin ayarladigi sure ancak SAYFA YENILENINCE gecerli
        // olurdu ve "ayari degistirdim ama olmadi" diye okunurdu.
        resp.put("inactivity_minutes", inactivityMinutes());
        resp.put("inactivity_warn_seconds", inactivityWarnSeconds());
        // Faz 3b: scope flags so the UI hides global-only tabs from scoped müdür-admins.
        resp.put("global_admin", SessionScope.isGlobalAdmin(session));
        resp.put("scoped", session.getAttribute("viewTeamIds") != null);
        return resp;
    }

    /**
     * Kullanıcının kendi giriş güvenliği özeti — giriş yanıtında ve {@code /api/me}'de AYNI blok.
     *
     * <p>Gösterilen "önceki giriş", içinde bulunulan oturumunki DEĞİLDİR: kullanıcı kendi
     * oturumunun başlangıcını görse "bu ben miydim?" sorusunu cevaplayamaz. IP'ler yalnız
     * kullanıcının KENDİ kaydı için döner (bu uç oturum sahibine bağlıdır).
     *
     * <p>{@code Map.of} kullanılamaz — null değer kabul etmez ve ilk girişte alanların çoğu null.
     */
    private static Map<String, Object> loginInfo(UserService.LoginStamp s) {
        UserService.LoginStamp stamp = s == null ? UserService.LoginStamp.empty() : s;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prev_login_at", stamp.prevLoginAt());
        m.put("prev_login_ip", stamp.prevLoginIp());
        m.put("prev_login_method", stamp.prevLoginMethod());
        m.put("failed_before_login", stamp.failedBeforeLogin());
        m.put("last_failed_at", stamp.lastFailedAt());
        m.put("last_failed_ip", stamp.lastFailedIp());
        m.put("last_failed_reason", stamp.lastFailedReason());
        m.put("current_login_at", stamp.currentLoginAt());
        m.put("current_login_method", stamp.currentLoginMethod());
        // Sunucu hesaplar: frontend "null → ilk giriş mi, veri mi yok" ayrımını tahmin etmesin.
        m.put("first_login", stamp.firstLogin());
        return m;
    }

    /** Birincil takım ilk olacak şekilde kullanıcının TÜM üyeliklerini team_ids + team_names olarak ekler. */
    private void putTeams(Map<String, Object> resp, AppUser user) {
        List<Long> ids = new ArrayList<>();
        if (user.getTeamId() != null) ids.add(user.getTeamId());
        if (user.getTeamIds() != null)
            for (Long t : user.getTeamIds()) if (t != null && !ids.contains(t)) ids.add(t);
        resp.put("team_ids", ids);
        resp.put("team_names", userService.teamNamesFor(ids));
    }

    private String resolveClientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }

    /** Returns seconds remaining in the block, or 0 if not blocked. */
    private long blockedSecondsRemaining(String ip) {
        Long until = blockedUntil.get(ip);
        if (until == null) return 0;
        long remaining = (until - System.currentTimeMillis() + 999) / 1000; // ceil
        if (remaining <= 0) {
            blockedUntil.remove(ip);
            loginAttempts.remove(ip);
            windowStart.remove(ip);
            return 0;
        }
        return remaining;
    }

    private void recordFailedAttempt(String ip) {
        long now = System.currentTimeMillis();
        // Reset counter if the 60-second window has expired for this IP
        long ws = windowStart.computeIfAbsent(ip, k -> now);
        if (now - ws > 60_000L) {
            loginAttempts.put(ip, new AtomicInteger(0));
            windowStart.put(ip, now);
        }
        int count = loginAttempts.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
        if (count >= maxLoginAttempts) {
            blockedUntil.put(ip, now + (long) blockSeconds * 1000);
        }
    }

    /**
     * Periyodik temizlik (saatlik) — IP rate-limit haritalarının sınırsız büyümesini önler.
     * Bir kez deneyip dönmeyen IP'lerin kayıtları aksi halde süresiz birikir (memory leak).
     * Penceresi geçmiş (60sn) ve bloğu sona ermiş IP'ler kaldırılır.
     */
    @Scheduled(fixedDelay = 3_600_000L)
    void pruneRateLimitState() {
        long now = System.currentTimeMillis();
        for (String ip : new ArrayList<>(windowStart.keySet())) {
            Long until = blockedUntil.get(ip);
            boolean blocked = until != null && until > now;
            Long ws = windowStart.get(ip);
            if (!blocked && (ws == null || now - ws > 60_000L)) {
                windowStart.remove(ip);
                loginAttempts.remove(ip);
                blockedUntil.remove(ip);
            }
        }
    }
}
