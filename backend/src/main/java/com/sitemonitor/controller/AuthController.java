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
    private final com.sitemonitor.service.TourStateService tourStateService;   // ürün turu (2026-09-13)
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

    /** Sistem Bakım Modu (2026-10-02) — isteğe bağlı: {@code @WebMvcTest} dilimlerinde yok → kapı çalışmaz (bugünkü davranış). */
    @Autowired(required = false)
    private com.sitemonitor.service.SystemMaintenanceService systemMaintenance;

    /** Test kancası. */
    void setSystemMaintenance(com.sitemonitor.service.SystemMaintenanceService s) { this.systemMaintenance = s; }

    /**
     * Giriş yöntemleri ayarları + kodla giriş (2026-10-02, kullanıcı isteği) — İSTEĞE BAĞLI: {@code @WebMvcTest} dilimlerinde
     * yok → LDAP girişi açık sayılır ve kod uçları "yöntem kapalı" döner (bugünkü davranış birebir).
     */
    /**
     * 7/24 izleme ekibi takımı → operatör bayrağı (2026-10-04). İsteğe bağlı: dilimli testte yokken oturumda bayrak hiç
     * yazılmaz (bugünkü davranış). Giriş yanıtı ve {@code /me} {@code noc_operator}/{@code noc_teams} taşır.
     */
    @Autowired(required = false)
    private com.sitemonitor.service.noc.NocOperatorService nocOperators;

    /** {@code noc_can_write} (arama kaydı) kuralının tek kaynağı — isteğe bağlı (dilimli test). */
    @Autowired(required = false)
    private com.sitemonitor.service.noc.NocCallLogService nocCallLogService;

    @Autowired(required = false)
    private com.sitemonitor.service.otp.LoginMethodsService loginMethods;

    @Autowired(required = false)
    private com.sitemonitor.service.otp.LoginOtpService loginOtp;

    /** Test kancaları. */
    void setLoginMethods(com.sitemonitor.service.otp.LoginMethodsService s) { this.loginMethods = s; }
    void setLoginOtp(com.sitemonitor.service.otp.LoginOtpService s) { this.loginOtp = s; }

    /** LDAP hesaplarının parola girişi açık mı (Ayarlar → Giriş Yöntemleri). Okuma hatası girişi KAPATMAZ (fail-open). */
    private boolean ldapLoginEnabled() {
        try {
            return loginMethods == null || loginMethods.ldapLoginEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    /** LDAP girişi kapalıyken TEK yanıt kodu — yerel olmayan her ad (var/yok) ve yerel hesabın yanlış parolası AYNI gövdeyi alır. */
    public static final String LDAP_LOGIN_DISABLED = "LDAP_LOGIN_DISABLED";

    private static ResponseEntity<Map<String, Object>> ldapDisabledResponse() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error_code", LDAP_LOGIN_DISABLED);
        body.put("error", com.sitemonitor.util.Msg.t(
                "Kullanıcı adı veya parola hatalı ya da LDAP ile giriş şu an devre dışı. LDAP hesabıyla giriyorsanız kodla giriş yöntemlerini kullanın.",
                "Invalid username or password, or LDAP sign-in is currently disabled. If you use an LDAP account, sign in with a one-time code."));
        return ResponseEntity.status(401).body(body);
    }

    /** Bakım AKTİF mi — okuma hatası girişi ENGELLEMEZ (fail-open). */
    private boolean maintenanceActive() {
        try {
            return systemMaintenance != null && systemMaintenance.isActive();
        } catch (Exception e) {
            return false;
        }
    }

    /** Bakım bu hesabın girişini engeller mi: bakım aktif + global yönetici değil. */
    private boolean maintenanceBlocks(AppUser user) {
        return maintenanceActive() && !systemMaintenance.isGlobalAdminAccount(user);
    }

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
                username.isBlank() ? null : username, clientIp, request.getHeader("User-Agent"), passwordChannelOf(username));
            return ResponseEntity.status(429).body(Map.of(
                "success", false,
                "error", "Too many login attempts. Please wait.",
                "wait_seconds", waitSecs));
        }

        // 2. Per-user progressive lockout (DB-persisted)
        if (!username.isBlank()) {
            UserService.LockoutStatus ls = userService.checkLockout(username);
            if (ls.isBlocked()) {
                auditService.recordRateLimited(username, clientIp, request.getHeader("User-Agent"), passwordChannelOf(username));
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

        // LDAP İLE GİRİŞ KAPALI (2026-10-02, kullanıcı isteği — Ayarlar → Giriş Yöntemleri): yerel olmayan HER kullanıcı adı
        // (LDAP hesabı da, hiç olmayan ad da) AD'ye gitmeden AYNI yanıtı alır; yerel hesabın yanlış parolası da aynı gövdeyi
        // alır (aşağıda) → "bu ad yerel mi / var mı" ne mesajdan ne süreden okunur (BCrypt maliyeti burada da ödenir).
        // Yerel hesaplar ve kurulumdaki bootstrap admin ETKİLENMEZ (hiçbir ayar break-glass hesabını dışarıda bırakamaz).
        // Denetim LOGIN_FAILED + BLOCKED → kaba kuvvet / ilerleyici kilit sayacına GİRMEZ (parola hiç denenmedi).
        boolean ldapLoginOff = !ldapLoginEnabled();
        if (!isLocalAccount && ldapLoginOff && !username.equalsIgnoreCase(bootstrapAdminUsername)) {
            recordFailedAttempt(clientIp);   // IP oran sınırı (numaralandırma denemesi de yavaşlasın)
            userService.burnPasswordCheck(password);
            auditService.recordLdapDisabledLogin(
                    existing.map(AppUser::getUsername).orElse(username.toUpperCase(java.util.Locale.ROOT)),
                    clientIp, request.getHeader("User-Agent"));
            return ldapDisabledResponse();
        }

        Optional<AppUser> userOpt;
        // PASİF hesap (2026-10-02, kullanıcı kararı): kimlik bilgisi DOĞRULANDIKTAN sonra hesap pasifse giriş reddedilir
        // (403 ACCOUNT_INACTIVE). Yanlış parolada aşağıdaki genel 401 DEĞİŞMEZ — "pasif" bilgisi yalnız parolayı / AD
        // parolasını bilene açılır (kullanıcı adı numaralandırması yok).
        AppUser inactive = null;
        AppUser maintenanceBlocked = null;
        String inactiveMethod = "PASSWORD";
        if (isLocalAccount) {
            userOpt = userService.authenticate(username, password);   // local BCrypt
            if (userOpt.isEmpty()) inactive = userService.findInactiveWithValidPassword(username, password).orElse(null);
        } else if (ldapEnabled() && !ldapLoginOff) {
            // (LDAP girişi kapalıyken buraya yalnız bootstrap adı düşebilir — AD'ye gitmez, yerel doğrulamaya iner.)
            LdapAttempt ldap = tryLdapLogin(username, password);      // AD bind + provision (USER)
            userOpt = Optional.ofNullable(ldap.user());
            inactive = ldap.inactive();
            maintenanceBlocked = ldap.maintenance();
            inactiveMethod = "LDAP";
        } else {
            userOpt = userService.authenticate(username, password);   // LDAP off → local only
            if (userOpt.isEmpty()) inactive = userService.findInactiveWithValidPassword(username, password).orElse(null);
        }

        // Giriş KANALI (2026-10-03, Giriş Yöntemleri → İstatistikler): AD bind yolu LDAP, yerel BCrypt yolu (bootstrap admin ve
        // LDAP kapalıyken yerel doğrulama dahil) LOCAL — LOGIN / LOGIN_FAILED ayrıntısına yazılır; tür / neden / sayaç aynen.
        String channel = "LDAP".equals(inactiveMethod) ? "LDAP" : "LOCAL";
        if (inactive != null) {
            return rejectInactiveLogin(inactive, inactiveMethod, clientIp, request);
        }
        // SİSTEM BAKIMI (2026-10-02, kullanıcı kararı): bakım AKTİFKEN yalnız global yönetici girer. Karar kimlik bilgisi
        // DOĞRULANDIKTAN sonra (pasif hesapla aynı ilke): yanlış parola bugünkü genel 401'i alır — "kim global yönetici"
        // numaralandırılamaz; giriş sayfası bakım kartını zaten public uçtan gösterir. LDAP yolunda provizyon hiç koşmaz.
        if (maintenanceBlocked == null && userOpt.isPresent() && maintenanceBlocks(userOpt.get())) {
            maintenanceBlocked = userOpt.get();
        }
        if (maintenanceBlocked != null) {
            return rejectMaintenanceLogin(maintenanceBlocked, inactiveMethod, clientIp, request);
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
                        "TEMP_PASSWORD_EXPIRED", null, 5, channel);
                return ResponseEntity.status(401).body(Map.of(
                        "success", false,
                        "error", "Temporary password expired. Ask your admin to reset again.",
                        "error_code", "TEMP_PASSWORD_EXPIRED"));
            }

            // Tek aktif oturum onayı: kullanıcının başka bir yerde aktif oturumu varsa ve henüz
            // onaylamadıysa, mevcut oturumu DÜŞÜRMEDEN 409 dön → frontend onay modalı gösterir.
            // Onaylarsa force_login=true ile tekrar gelir; o zaman aşağıdaki akış (recordActiveSession)
            // eski oturumu düşürüp girişi tamamlar. (TERMINATED sentinel'i = zaten kapatılmış, sayılmaz.)
            boolean forceLogin = Boolean.parseBoolean(body.getOrDefault("force_login", "false"));
            return establishSession(user, username, rememberMe, forceLogin, clientIp, request, response,
                    UserService.LoginMethod.PASSWORD, channel);
        }

        // 3. Failed — record attempt, check for BRUTE_FORCE, apply progressive lockout
        return failedLogin(username, clientIp, request, ldapLoginOff, channel);
    }

    /**
     * Hedeflenen hesabın parola giriş kanalı (2026-10-03, oran sınırı / kilit reddinin denetim ayrıntısı için): yerel
     * (ya da kaynağı boş) hesap LOCAL, diğerleri LDAP; ad bilinmiyorsa {@code null} (ayrıntı yazılmaz). Yönlendirme kuralı
     * {@link #login}'deki {@code isLocalAccount} ile aynı.
     */
    private String passwordChannelOf(String username) {
        if (username == null || username.isBlank()) return null;
        try {
            return userService.findByUsername(username)
                    .map(u -> u.getAuthSource() == null || "LOCAL".equalsIgnoreCase(u.getAuthSource()) ? "LOCAL" : "LDAP")
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Başka yerde canlı oturum — onay iste (şifre ve kodla girişte AYNI gövde). */
    private static ResponseEntity<Map<String, Object>> activeSessionConflict() {
        return ResponseEntity.status(409).body(Map.of(
                "success", false,
                "error_code", "ACTIVE_SESSION_EXISTS",
                "error", "An active session already exists for this account elsewhere."));
    }

    /**
     * Kimlik DOĞRULANDIKTAN sonraki ORTAK yol (2026-10-02: şifre girişinden çıkarıldı — kodla giriş de AYNEN buradan geçer):
     * IP sayaçları ve ilerleyici kilit temizliği, tek aktif oturum onayı (409), eski oturumu düşürüp yenisini kurma, giriş
     * damgası + aktif oturum kaydı, eski remember-me token'larının iptali, {@code LOGIN} denetimi (kodla girişte ayrıntıda
     * yöntem), istenirse remember-me çerezi ve /api/me ile aynı yanıt gövdesi. {@code mustChangePassword} oturuma
     * {@link #populateSession} ile yazılır — interceptor kuralı her iki yolda aynı.
     *
     * @param lockoutKey ilerleyici kilit temizliği / log için ad (şifre yolunda yazılan ad — bugünkü davranış)
     */
    private ResponseEntity<Map<String, Object>> establishSession(AppUser user, String lockoutKey, boolean rememberMe,
                                                                 boolean forceLogin, String clientIp,
                                                                 HttpServletRequest request, HttpServletResponse response,
                                                                 UserService.LoginMethod method, String channel) {
        String username = lockoutKey;
        loginAttempts.remove(clientIp);
        windowStart.remove(clientIp);
        blockedUntil.remove(clientIp);
        userService.clearLockoutOnSuccess(username);

        // Yalnız CANLI (son ping penceresi içinde) bir aktif oturum varsa onay iste; logout'suz
        // kapatılan/ölen oturumlar tazelik penceresi dışına düşer → yanlış onay çıkmaz.
        boolean hasActiveElsewhere = userService.hasLiveSession(user);
        if (hasActiveElsewhere && !forceLogin) {
            log.info("Login needs confirmation — active session exists elsewhere: user={} IP={}", username, clientIp);
            return activeSessionConflict();
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
                user.getUsername(), newSession.getId(), clientIp, method);
        // CANONICAL username (yazılan case DEĞİL): app_users ve remember_me_tokens BÜYÜK harfe
        // normalize ediliyor (applySchemaPatches). Yazılan case ile silmek, kullanıcı bir gün
        // "n12345" ertesi gün "N12345" yazdığında eşleşmez ve ÖKSÜZ bir token hayatta kalır —
        // yani "her login eski token'ları iptal eder" güvencesi sessizce delinir.
        rememberMeService.invalidateAllForUser(user.getUsername());

        log.info("User logged in: {} (role={}, teamId={}, rememberMe={}, IP={}, method={})",
                user.getUsername(), user.getSystemRole(), user.getTeamId(), rememberMe, clientIp, method);
        // Audit actor'ı da CANONICAL: yazılan case ile kaydedilirse aktif-oturum kartı enrichment'i
        // (findTopByActor(canonical, sid)) eşleşmez → login zamanı/IP boş kalır; ayrıca aynı kullanıcı
        // audit'te iki farklı case ("N12345"/"n12345") ile görünür.
        // LOGIN + ayrıntıda giriş KANALI: kodla giriş OTP_PUSH / OTP_EMAIL (2026-10-02, kod YOK); parola girişi 2026-10-03'ten
        // beri LOCAL (yerel BCrypt) / LDAP (AD bind) — Giriş Yöntemleri → İstatistikler kanalı buradan okur.
        String auditMethod = channel != null && !channel.isBlank() ? channel : method.name();
        auditService.recordLogin(user.getUsername(), user.getId(), user.getTeamId(),
                user.getSystemRole(), clientIp,
                request.getHeader("User-Agent"), newSession.getId(), true, null, null, 5, auditMethod);

        if (rememberMe) {
            String token = rememberMeService.generateToken(
                    user.getUsername(),                       // CANONICAL — iptal yolu da bununla arıyor
                    clientIp, request.getHeader("User-Agent"));
            // SameSite=Strict ZORUNLU — oturum çerezi prod'da zaten Strict ve bu uygulamanın
            // TEK CSRF savunması. Remember-me çerezi jakarta Cookie ile yazılıyordu ve o sınıfın
            // setSameSite'ı yok; bayrak sessizce eksik kalıyordu. AuthInterceptor bu çerezle
            // TAM OTURUMU yeniden kurduğu için, Lax-varsayılanı uygulamayan bir tarayıcıda
            // cross-site POST kimlikli çalışıyor ve oturum çerezindeki Strict etkisiz kalıyordu.
            // (Gövdesiz POST uçları düz HTML formuyla tetiklenebilir.)
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

    /**
     * Başarısız parola girişi — IP sayacı, kaba kuvvet tespiti, ilerleyici kilit (2026-10-02: login'den çıkarıldı, davranış
     * aynı). {@code ldapLoginOff}: LDAP girişi kapalıyken düz 401 gövdesi yerel olmayan adlarla AYNI olur (numaralandırma yok).
     */
    private ResponseEntity<Map<String, Object>> failedLogin(String username, String clientIp, HttpServletRequest request,
                                                            boolean ldapLoginOff, String channel) {
        recordFailedAttempt(clientIp);
        log.warn("Failed login attempt: IP={}, username={}", clientIp, username);
        // Look up user's lockout context: window start (countSince) and required failures for this level.
        // Ayrıca bilinmeyen-kullanıcı ile yanlış-parolayı denetim kaydı için ayır (yalnız audit'te —
        // istemciye DÖNÜLMEZ, kullanıcı enumeration sızmaz). Boş kullanıcı adı da UNKNOWN_USER sayılır.
        String lastLockoutAt = null;
        int failuresNeeded = 5;
        boolean userExists = false;
        // Sayaç KANONİK adla tutulur (prod kapısı 2026-09-25, O-1): kimlik doğrulama harf duyarsız ama deneme
        // sayacı (`a.actor = :actor`) duyarlıydı — "admin"/"ADMIN"/"Admin" ayrı sayılıp kilit hiç tetiklenmiyordu.
        // Kullanıcı varsa kayıtlı adı, yoksa büyük harf (kanonik saklama biçimi) kullanılır.
        String auditActor = username.toUpperCase(java.util.Locale.ROOT);
        if (!username.isBlank()) {
            var failedUser = userService.findByUsername(username);
            if (failedUser.isPresent()) {
                userExists = true;
                var u = failedUser.get();
                auditActor = u.getUsername();
                lastLockoutAt = u.getLastLockoutAt();
                failuresNeeded = userService.failuresNeededForLevel(u.getFailedBlockCount());
                // Kullanıcının güvenlik özeti için damga. applyProgressiveLockout'tan (aşağıda)
                // ÖNCE: o metot entity'yi yeniden yükleyip kaydediyor; ters sırada bayat kopya
                // az önce artırılan sayacı ezerdi. UNKNOWN_USER'da güncellenecek satır yok.
                userService.recordFailedLogin(u.getUsername(), clientIp, "BAD_PASSWORD");
            }
        }
        String reasonCode = userExists ? "BAD_PASSWORD" : "UNKNOWN_USER";
        // 2026-10-03: ayrıntıda denenen giriş kanalı (LOCAL / LDAP) — neden kodu, sayaç ve kilit kararı AYNEN
        com.sitemonitor.model.AuditLog logged = auditService.recordLogin(
                username.isBlank() ? username : auditActor, null, null, null, clientIp,
                request.getHeader("User-Agent"), null, false, reasonCode,
                lastLockoutAt, failuresNeeded, channel);

        if (!username.isBlank() && logged.getAnomalyFlags() != null
                && logged.getAnomalyFlags().contains("BRUTE_FORCE")) {
            UserService.LockoutStatus ls = userService.applyProgressiveLockout(auditActor);
            // Hesap kilit GEÇİŞİ — ayrık denetim olayı (altında yatan failed-login zaten kaydedildi).
            auditService.recordAction("ACCOUNT_LOCKED", auditActor, null, null, null, "USER", auditActor,
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
        if (ldapLoginOff) return ldapDisabledResponse();
        // 2026-10-08: istek dilinde ve ne yapılacağını söyleyen genel ileti (eskiden yalnız İngilizce). Yapı AYNI —
        // kod alanı yok, pasif / bilinmeyen / yanlış parola aynı gövdeyi alır (numaralandırma yok).
        return ResponseEntity.status(401)
                .body(Map.of("success", false, "error", invalidCredentialsMessage()));
    }

    /** Genel "kullanıcı adı ya da parola hatalı" iletisi — tüm kimlik doğrulama hatalarında AYNI metin. */
    static String invalidCredentialsMessage() {
        return com.sitemonitor.util.Msg.t(
                "Kullanıcı adı ya da parola hatalı. Büyük harf kilidini ve klavye dilini kontrol edip tekrar deneyin; art arda hatalı denemelerden sonra hesap kısa süreliğine kilitlenir.",
                "Incorrect username or password. Check caps lock and your keyboard layout, then try again; after repeated failed attempts the account is locked for a short while.");
    }

    // ── Kodla giriş (push / e-posta tek kullanımlık kod, 2026-10-02, kullanıcı isteği) ─────────────────────────────
    //
    // İki uç da AuthInterceptor PUBLIC listesinde (oturumsuz). İstek ucu uygun / uygunsuz / bilinmeyen kullanıcı için
    // AYNI 200 gövdesini döner (bkz. LoginOtpService); doğrulama başarılıysa oturum ŞİFRE GİRİŞİYLE AYNI yoldan kurulur
    // (establishSession): 409 + forceLogin, beni hatırla, bakım 403, pasif 403 (yalnız DOĞRU koddan sonra),
    // mustChangePassword, LOGIN denetimi (yöntem OTP_PUSH / OTP_EMAIL). Gövdeler RequestLoggingFilter'da HİÇ loglanmaz.

    /**
     * {@code {username, channel: "push"|"email", phone?, email?}} → 200 genel yanıt; yöntem kapalı 400; IP sınırı 429.
     * 2026-10-03: kanalın kişi bilgisi doğrulaması açıksa {@code phone} (push) / {@code email} (e-posta) zorunlu — boşsa
     * 400 {@code PHONE_REQUIRED} / {@code EMAIL_REQUIRED}; eşleşmeme genel 200'dür. Değerler burada loglanmaz.
     */
    @PostMapping("/login/otp/request")
    public ResponseEntity<Map<String, Object>> otpRequest(@RequestBody(required = false) Map<String, Object> body,
                                                          HttpServletRequest request) {
        if (loginOtp == null) return otpUnavailable();
        Map<String, Object> b = body == null ? Map.of() : body;
        var r = loginOtp.request(str(b.get("username")), str(b.get("channel")), str(b.get("phone")), str(b.get("email")),
                resolveClientIp(request), request.getHeader("User-Agent"), com.sitemonitor.util.Msg.isEn());
        return ResponseEntity.status(r.status()).body(r.body());
    }

    /**
     * {@code {challenge_id, code, remember_me, forceLogin}} → başarı: /api/login ile AYNI gövde ve oturum; hata: 401
     * {@code OTP_INVALID} (+ attempts_left) / {@code OTP_EXPIRED} / {@code OTP_LOCKED}; 409 başka yerde oturum (istek
     * tüketilmez, onay süresince canlı kalır); 403 MAINTENANCE / ACCOUNT_INACTIVE ve 423 hesap kilidi yalnız DOĞRU koddan sonra.
     */
    @PostMapping("/login/otp/verify")
    public ResponseEntity<Map<String, Object>> otpVerify(@RequestBody(required = false) Map<String, Object> body,
                                                         HttpServletRequest request, HttpServletResponse response) {
        if (loginOtp == null) return otpUnavailable();
        Map<String, Object> b = body == null ? Map.of() : body;
        String clientIp = resolveClientIp(request);
        boolean rememberMe = bool(b.get("remember_me"));
        boolean forceLogin = bool(b.get("forceLogin")) || bool(b.get("force_login"));
        var out = loginOtp.verify(str(b.get("challenge_id")), str(b.get("code")), clientIp, request.getHeader("User-Agent"));
        if (out.failure() != null) return ResponseEntity.status(out.failure().status()).body(out.failure().body());

        var v = out.verified();
        AppUser user = v.user();
        UserService.LoginMethod method = v.channel().loginMethod();
        // Kod DOĞRU — buradan sonrası şifre girişinin kimlik-doğrulama-sonrası kararlarıyla AYNI sıra.
        if (!Boolean.TRUE.equals(user.getActive())) {
            loginOtp.block(v.challenge());
            return rejectInactiveLogin(user, method.name(), clientIp, request);
        }
        if (maintenanceBlocks(user)) {
            loginOtp.block(v.challenge());
            return rejectMaintenanceLogin(user, method.name(), clientIp, request);
        }
        UserService.LockoutStatus ls = userService.checkLockout(user.getUsername());
        if (ls.isBlocked()) {
            loginOtp.block(v.challenge());
            auditService.recordLockedLogin(user.getUsername(), user.getId(), user.getTeamId(), user.getSystemRole(),
                    clientIp, request.getHeader("User-Agent"), method.name());
            if (ls.permanent()) {
                return ResponseEntity.status(423).body(Map.of(
                        "success", false, "locked", true,
                        "error", "Account permanently locked. Contact administrator."));
            }
            return ResponseEntity.status(423).body(Map.of(
                    "success", false, "wait_seconds", ls.secondsRemaining(),
                    "error", "Account temporarily locked."));
        }
        if (loginOtp.globalAdminBlocked(user)) {
            // Global yöneticiye kodla giriş istek ile doğrulama arasında kapatıldı — genel "süresi doldu" yanıtı.
            loginOtp.block(v.challenge());
            return ResponseEntity.status(401).body(com.sitemonitor.service.otp.LoginOtpService.expiredBody());
        }
        if (userService.hasLiveSession(user) && !forceLogin) {
            // Onay penceresi: istek TÜKETİLMEZ (kullanıcı onaylayınca aynı kod + forceLogin ile gelir).
            loginOtp.holdForConfirmation(v.challenge());
            log.info("Kodla giriş onay bekliyor — başka yerde aktif oturum: user={} IP={}", user.getUsername(), clientIp);
            return activeSessionConflict();
        }
        if (!loginOtp.consume(v.challenge())) {
            return ResponseEntity.status(401).body(com.sitemonitor.service.otp.LoginOtpService.expiredBody());
        }
        return establishSession(user, user.getUsername(), rememberMe, true, clientIp, request, response, method, method.name());
    }

    private static ResponseEntity<Map<String, Object>> otpUnavailable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", "OTP_METHOD_DISABLED");
        body.put("error_code", "OTP_METHOD_DISABLED");
        body.put("error", com.sitemonitor.util.Msg.t("Bu giriş yöntemi şu an kapalı.", "This sign-in method is currently disabled."));
        return ResponseEntity.status(400).body(body);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean x) return x;
        return v != null && Boolean.parseBoolean(v.toString().trim());
    }

    /**
     * Pasif hesabın (kimlik bilgisi doğrulanmış) girişini reddeder — oturum KURULMAZ, LDAP provizyonu / profil / takım
     * eşitlemesi YAPILMAZ, hesap yeniden aktifleşmez. Denetim: {@code LOGIN_FAILED} + neden {@code ACCOUNT_INACTIVE},
     * sonuç BLOCKED → ilerleyici kilit sayacına GİRMEZ (kilit yanlış parolaya karşıdır). IP oran sınırı yine işler
     * (bu IP'den art arda deneme sınırlanır). Kullanıcının güvenlik özetine başarısız deneme damgası düşer.
     */
    private ResponseEntity<Map<String, Object>> rejectInactiveLogin(AppUser user, String method, String clientIp,
                                                                   HttpServletRequest request) {
        recordFailedAttempt(clientIp);   // IP oran sınırı (hesap kilidi DEĞİL)
        userService.recordFailedLogin(user.getUsername(), clientIp, com.sitemonitor.util.AccountInactive.CODE);
        auditService.recordInactiveLogin(user.getUsername(), user.getId(), user.getTeamId(), user.getSystemRole(),
                clientIp, request.getHeader("User-Agent"), method);
        log.warn("Login rejected — account inactive: user={} method={} IP={}", user.getUsername(), method, clientIp);
        return ResponseEntity.status(403).body(com.sitemonitor.util.AccountInactive.body());
    }

    /**
     * Sistem bakımında global yönetici olmayan hesabın (kimlik bilgisi doğrulanmış) girişini reddeder (2026-10-02) — oturum
     * KURULMAZ, LDAP provizyonu YAPILMAZ. Denetim {@code LOGIN_FAILED} + neden {@code MAINTENANCE} (BLOCKED → kilit sayacına
     * girmez); engellenen giriş bakım kaydına sayılır. IP oran sınırına SAYILMAZ: aynı NAT arkasındaki çok sayıda kullanıcı
     * bakımda giriş denerken global yöneticinin IP'si bloklanmasın. Kullanıcının "başarısız deneme" özetine de yazılmaz
     * (bir sonraki girişte yanlış "şüpheli deneme" uyarısı çıkmasın).
     */
    private ResponseEntity<Map<String, Object>> rejectMaintenanceLogin(AppUser user, String method, String clientIp,
                                                                      HttpServletRequest request) {
        auditService.recordMaintenanceLogin(user.getUsername(), user.getId(), user.getTeamId(), user.getSystemRole(),
                clientIp, request.getHeader("User-Agent"), method);
        try { systemMaintenance.recordBlockedLogin(); } catch (Exception ignored) { /* sayaç best-effort */ }
        log.info("Login rejected — system maintenance: user={} method={} IP={}", user.getUsername(), method, clientIp);
        return ResponseEntity.status(403).body(systemMaintenance.signalBody());
    }

    /** Oturum yoklaması / {@code /me} / giriş yanıtına EK bakım bloğu + sunucu saati (servis yoksa hiçbir alan eklenmez). */
    private void putMaintenance(Map<String, Object> resp) {
        if (systemMaintenance == null) return;
        try {
            resp.put("maintenance", systemMaintenance.clientBlock());
            resp.put("server_now", systemMaintenance.serverNow());
        } catch (Exception e) {
            log.debug("Bakım bloğu eklenemedi: {}", e.getMessage());
        }
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
        // Sistem Bakım Modu (2026-10-02): yoklama yanıtına EK blok — duyuru/uyarı şeridi, geri sayım penceresi ve admin
        // şeridi bunu okur; geri sayım İSTEMCİ saatinde değil server_now'a göre hesaplanır. Mevcut alan ("success")
        // değişmez; bakım servisi yoksa yanıt bugünküyle birebir. Okuma pod önbelleğinden (≤ 5 sn) — DB yok.
        if (systemMaintenance == null) return ResponseEntity.ok(Map.of("success", true));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        putMaintenance(resp);
        return ResponseEntity.ok(resp);
    }

    /**
     * "Ayrıldım" sinyali (2026-10-02) — kullanıcının SON açık sekmesi kapanırken istemci {@code navigator.sendBeacon} ile
     * gönderir; kullanıcı çevrimiçi sayımından hemen düşer (bkz. {@link UserService#markLeft}). Gövde okunmaz (beacon
     * text/plain gönderir). Oturum sonlandırılmaz — aynı çerezle dönüş yeniden giriş istemez. Yanıt 204, gövdesiz.
     */
    @PostMapping("/session/leave")
    public ResponseEntity<Void> sessionLeave(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username != null) userService.markLeft(username, session.getId());
        return ResponseEntity.noContent().build();
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
        // Tek DB yüklemesi: hem profil alanları hem takım kapsamı tazelemesi (bayat oturum üyeliği) bundan.
        java.util.Optional<AppUser> fresh = userService.findByUsername(username);
        fresh.ifPresent(u -> refreshTeamScope(session, u));
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
        fresh.ifPresent(u -> {
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
            // Kişisel push sessiz saati (2026-10-01) — null = tanımsız (bugünkü davranış). Etkinliklerim kartı bunu okur.
            resp.put("push_quiet", pushQuietOf(u));
            // Ürün turu durumu (null = hiç görmedi → istemci karşılama kartını gösterir)
            resp.put("tour", com.sitemonitor.service.TourStateService.parse(u.getTourState()));
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
        // Haftalık Raporlar modülü bu kullanıcıya görünür mü (2026-09-16)? Takım bazlı açılır, varsayılan
        // KAPALI — sekme yalnız açık takımlara (ve en az bir takım açıksa yönetici/denetçiye) çizilir.
        resp.put("weekly_reports_visible", weeklyReportsVisible(resp, session));
        putMaintenance(resp);   // Sistem Bakım Modu (2026-10-02): EK blok — açılışta şerit/pencere ilk yoklamayı beklemesin
        putNoc(resp, session);  // 7/24 izleme ekibi (2026-10-04): menü/konsol/arama düğmeleri
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

    /** {@code /me} ve kayıt yanıtındaki kişisel push sessiz saati: tanımsızsa null. */
    static Map<String, Object> pushQuietOf(com.sitemonitor.model.AppUser u) {
        if (u == null || u.getPushQuietStart() == null || u.getPushQuietEnd() == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start", u.getPushQuietStart());
        m.put("end", u.getPushQuietEnd());
        m.put("days", u.getPushQuietDays());
        m.put("min_level", u.getPushQuietMinLevel());
        return m;
    }

    /**
     * Kişisel push sessiz saati (2026-10-01, onaylı öneri 15) — kullanıcı YALNIZ kendi satırını yazar (push-opt-out deseni;
     * id/sicil parametresi yok → IDOR yüzeyi yok). Gövde: {start, end, days[], min_level}; start ve end boş = kaldır.
     * Doğrulama hatası 400 (mesaj arayüz dilinde). Pencerede bastırılan push teslimat günlüğünde
     * {@code SKIPPED_USER_QUIET_HOURS} olarak görünür; KRİTİK ve çözüm push'u etkilenmez.
     */
    @PostMapping("/me/push-quiet-hours")
    public ResponseEntity<Map<String, Object>> setPushQuietHours(
            @RequestBody Map<String, Object> body, HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) throw new SecurityException("Not authenticated");
        var userOpt = userService.findByUsername(username);
        if (userOpt.isEmpty()) throw new SecurityException("Not authenticated");
        Object s = body.get("start"), e = body.get("end"), lvl = body.get("min_level");
        var cfg = com.sitemonitor.service.QuietHours.normalize(s == null ? null : s.toString(),
                e == null ? null : e.toString(), body.get("days"), lvl == null ? null : lvl.toString());
        var u = userService.savePushQuietHours(userOpt.get(), cfg);
        auditService.recordAction("USER_PUSH_QUIET_HOURS", session, "USER", String.valueOf(u.getId()),
                cfg.isSet()
                        ? "Kişisel push sessiz saati: " + cfg.start() + "–" + cfg.end()
                                + (cfg.days() == null ? " (her gün)" : " (" + cfg.days() + ")")
                                + " · hemen giden en düşük seviye " + (cfg.minLevel() == null ? "HIGH" : cfg.minLevel())
                        : "Kişisel push sessiz saati kaldırıldı", null);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("push_quiet", pushQuietOf(u));
        return ResponseEntity.ok(resp);
    }

    /**
     * Ürün turu durumu (2026-09-13) — kullanıcı YALNIZ kendi kaydını yazar (push-opt-out deseni).
     * Gövde: {status?, version?, last_step?, seen_page?, checklist?{k:bool}, checklist_hidden?, reset?}.
     * "completed"/"dismissed" geçişi denetlenir (TOUR_COMPLETED / TOUR_DISMISSED); "reset" durumu siler
     * (kullanıcı "turu yeniden başlat"). Yanıt: güncel {@code tour} (null = sıfırlandı).
     */
    @PostMapping("/me/tour")
    public ResponseEntity<Map<String, Object>> setTourState(
            @RequestBody Map<String, Object> body, HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) throw new SecurityException("Not authenticated");
        var u = userService.findByUsername(username).orElseThrow(() -> new SecurityException("Not authenticated"));
        String before = String.valueOf(java.util.Optional.ofNullable(com.sitemonitor.service.TourStateService.parse(u.getTourState()))
                .map(m -> m.get("status")).orElse(null));
        boolean reset = Boolean.TRUE.equals(body.get("reset"));
        Map<String, Object> next = tourStateService.apply(u, body, reset);
        String after = next == null ? null : String.valueOf(next.get("status"));
        if (after != null && !after.equals(before) && ("completed".equals(after) || "dismissed".equals(after))) {
            auditService.recordAction("completed".equals(after) ? "TOUR_COMPLETED" : "TOUR_DISMISSED", session,
                    "USER", String.valueOf(u.getId()),
                    "completed".equals(after) ? "Kişi ürün turunu tamamladı" : "Kişi ürün turunu kapattı (bir daha gösterme)",
                    "{\"version\":" + next.getOrDefault("version", 0) + ",\"last_step\":\""
                            + String.valueOf(next.getOrDefault("last_step", "")).replace("\"", "") + "\"}");
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("tour", next);
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
        // 7/24 operatörünün takım üyeliğinden gelen okuma izinleri de (2026-10-04) — arayüz menüleri buna göre açar.
        Map<String, Map<String, Boolean>> snapshot = permissionService != null
            ? permissionService.snapshotForSession(session)
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

    /** LDAP giriş denemesinin sonucu: {@code user} = provizyonlanmış aktif kullanıcı; {@code inactive} = AD bind'ı
     *  BAŞARILI ama uygulama hesabı PASİF (provizyon yapılmadı). İkisi de null = başarısız deneme. */
    record LdapAttempt(AppUser user, AppUser inactive, AppUser maintenance) {
        static final LdapAttempt FAILED = new LdapAttempt(null, null, null);
        LdapAttempt(AppUser user, AppUser inactive) { this(user, inactive, null); }
    }

    /**
     * Authenticates against AD and provisions/loads the local row (USER, no team).
     * Returns {@link LdapAttempt#FAILED} on wrong password / user-not-found / any LDAP error (caller then
     * records a normal failed attempt). Never throws.
     *
     * <p><b>Pasif hesap (2026-10-02, kullanıcı kararı — güvenlik açığı kapatıldı):</b> AD bind başarılıysa provizyondan
     * ÖNCE mevcut uygulama hesabına bakılır; pasifse {@code inactive} döner ve provizyon HİÇ çalışmaz — hesap yeniden
     * aktifleşmez, profil/takım/müdür eşitlemesi bu denemeyle değişmez, oturum kurulmaz. Eskiden provizyon aktiflik
     * bakmadığı için AD parolası geçerli pasif LDAP kullanıcısı içeri girebiliyordu.
     */
    private LdapAttempt tryLdapLogin(String username, String password) {
        try {
            Optional<LdapDirectoryService.LdapUser> ad =
                    ldapDirectory.authenticate(username, password);
            if (ad.isEmpty()) return LdapAttempt.FAILED;
            var u = ad.get();
            String uname = (u.username() != null && !u.username().isBlank()) ? u.username() : username;
            Optional<AppUser> existingRow = userService.findByUsername(uname);
            if (existingRow.isPresent() && !Boolean.TRUE.equals(existingRow.get().getActive())) {
                return new LdapAttempt(null, existingRow.get());
            }
            // SİSTEM BAKIMI (2026-10-02): AD bind'ı başarılı ama bakım aktif → provizyon / profil / takım eşitlemesi HİÇ
            // koşmaz (bakımda veri değişmesin), oturum kurulmaz. AD kaynaklı hesap global yönetici olamaz (kapsam AD
            // takımlarından türer); yine de satır global yönetici çıkarsa (ör. elle kaynak değişimi) normal akış sürer.
            if (maintenanceActive() && (existingRow.isEmpty() || !systemMaintenance.isGlobalAdminAccount(existingRow.get()))) {
                AppUser row = existingRow.orElseGet(() -> {
                    AppUser t = new AppUser();
                    t.setUsername(uname);
                    return t;
                });
                return new LdapAttempt(null, null, row);
            }
            // Map all AD attributes → user/team/manager (Faz 3a).
            AppUser provisioned = ldapProvisioning.provisionFromAd(uname, u.dn(), u.attributes());
            // Savunma: provizyon pasif bir satır döndürdüyse (yukarıdaki kapı bunu önler) yine oturum kurulmaz.
            if (provisioned != null && !Boolean.TRUE.equals(provisioned.getActive())) return new LdapAttempt(null, provisioned);
            return new LdapAttempt(provisioned, null);
        } catch (Exception e) {
            log.warn("LDAP login error for '{}': {}", username, e.getMessage());
            return LdapAttempt.FAILED;
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
        session.setAttribute("systemRole", user.getSystemRole());
        // Settings (SMTP/LDAP/secret/DB) gate'i için: kullanıcı konfigüre bootstrap admin mi?
        session.setAttribute("bootstrapAdmin",
                user.getUsername() != null && user.getUsername().equalsIgnoreCase(bootstrapAdminUsername));
        session.setAttribute("mustChangePassword",
                Boolean.TRUE.equals(user.getMustChangePassword()));
        applyTeamScope(session, user, userService.computeViewTeamIds(user), userService.computeManageTeamIds(user),
                userService.computeMemberTeamIds(user));
        // 7/24 izleme ekibi operatörlüğü (2026-10-04): girişte de yazılır — giriş yanıtı noc_operator'ı taşısın. Sonraki
        // isteklerde AuthInterceptor tazeler (takım listeden çıkarsa yeniden giriş beklemeden kalkar).
        if (nocOperators != null) nocOperators.sync(session);
    }

    /**
     * 7/24 izleme ekibi bloğu — {@code /me} ve giriş yanıtı (2026-10-04): {@code noc_operator} (takım üyeliğinden ya da
     * eski AUDIT + {@code noc_calls.write} düzeninden — global yönetici operatör sayılmaz, zaten her şeyi yapar),
     * {@code noc_teams} (üyesi olduğu 7/24 takımları, ad ile) ve {@code noc_can_write} (arama kaydı girebilir mi).
     */
    private void putNoc(Map<String, Object> resp, HttpSession session) {
        boolean byTeam = SessionScope.isNocOperator(session);
        boolean canWrite = nocCallLogService != null && nocCallLogService.canWrite(session);
        resp.put("noc_operator", byTeam || (canWrite && !SessionScope.isGlobalAdmin(session)));
        List<Long> ids = SessionScope.nocTeamIds(session);
        List<Map<String, Object>> teams = new ArrayList<>();
        for (Long id : ids) {   // operatörün 7/24 takımları — pratikte bir-iki takım
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", id);
            t.put("name", userService.findTeamById(id).map(Team::getName).orElse(null));
            teams.add(t);
        }
        resp.put("noc_teams", teams);
        resp.put("noc_can_write", canWrite);
    }

    /** Oturumun TAKIM öznitelikleri (teamId/teamName/viewTeamIds/manageTeamIds/memberTeamIds) — giriş ve tazeleme ortak. */
    private void applyTeamScope(HttpSession session, AppUser user, List<Long> view, List<Long> manage, List<Long> member) {
        session.setAttribute("teamId", user.getTeamId());
        // Resolve team name
        String teamName = user.getTeamId() != null
                ? userService.findTeamById(user.getTeamId()).map(Team::getName).orElse(null)
                : null;
        session.setAttribute("teamName", teamName);

        // ── Team scope (Faz 3b): null = unrestricted (global admin / AUDIT) → no attribute. ──
        if (view == null) session.removeAttribute("viewTeamIds");
        else session.setAttribute("viewTeamIds", new ArrayList<>(view));
        if (manage == null) session.removeAttribute("manageTeamIds");
        else session.setAttribute("manageTeamIds", new ArrayList<>(manage));

        // ÜYELİK kapsamı (2026-08-20, şablon kütüphanesi): "bu kullanıcı hangi takımların ÜYESİ?"
        // view/manage'ın ikisi de bu soruyu cevaplamıyor — view müdürde astların takımlarını
        // içeriyor, manage USER'da boş. ASLA null bırakılmaz: yokluğu "kısıtsız" anlamına
        // gelmemeli, boş liste "hiçbir takımın üyesi değil" demeli.
        session.setAttribute("memberTeamIds", new ArrayList<>(member == null ? List.<Long>of() : member));
    }

    /**
     * Oturumdaki takım kapsamını DB'deki GÜNCEL üyelikle eşitler (2026-09-25, kullanıcı isteği: "USER izleme/alan adı
     * ekleyebilmeli"). Takım öznitelikleri yalnız girişte ({@link #populateSession}) yazılıyordu; kullanıcı sonradan bir
     * takıma eklendiğinde ya da taşındığında oturum eski üyeliği taşıyor, {@code canOperateTeam} /
     * {@code requireInventoryWriter} 403 dönüyor, {@code /me} de taze {@code team_ids} yanında bayat {@code team_id}
     * veriyordu. SPA her sayfa yüklemesinde {@code /me} çağırdığı için tazeleme burada yapılır — zaten yüklenen kullanıcı
     * satırından, yalnız fark varsa yazılır.
     *
     * <p><b>Rol semantiği DEĞİŞMEZ:</b> oturum rolü DB rolünden farklıysa (rol değişikliği) hiçbir şeye dokunulmaz —
     * aksi hâlde eski rolle yeni rolün kapsamı karışırdı (ör. USER oturumunda {@code viewTeamIds} yokluğu "kısıtsız"
     * okunurdu). Rol değişikliği mevcut yoldan (yeniden giriş) işler.
     *
     * @return kapsam güncellendi mi
     */
    boolean refreshTeamScope(HttpSession session, AppUser user) {
        if (user == null || !java.util.Objects.equals(session.getAttribute("systemRole"), user.getSystemRole())) return false;
        List<Long> view = userService.computeViewTeamIds(user);
        List<Long> manage = userService.computeManageTeamIds(user);
        List<Long> member = userService.computeMemberTeamIds(user);
        boolean same = java.util.Objects.equals(session.getAttribute("teamId"), user.getTeamId())
                && java.util.Objects.equals(session.getAttribute("viewTeamIds"), view)
                && java.util.Objects.equals(session.getAttribute("manageTeamIds"), manage)
                && java.util.Objects.equals(session.getAttribute("memberTeamIds"), member == null ? List.of() : member);
        if (same) return false;
        applyTeamScope(session, user, view, manage, member);
        log.info("Oturum takım kapsamı DB'den tazelendi: user={} teamId={} member={}",
                user.getUsername(), user.getTeamId(), member);
        return true;
    }

    private Map<String, Object> buildMeResponse(AppUser user, HttpSession session,
                                                UserService.LoginStamp stamp) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Login successful");
        resp.put("login_info", loginInfo(stamp));
        resp.put("tour", com.sitemonitor.service.TourStateService.parse(user.getTourState()));   // ürün turu (giriş yanıtı da taşır: karşılama kartı F5 beklemesin)
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
        // Giriş yanıtına da konur: /me yalnız açılışta koşuyor — konmazsa sekme ancak F5'ten sonra görünürdü.
        resp.put("weekly_reports_visible", weeklyReportsVisible(resp, session));
        putMaintenance(resp);   // Sistem Bakım Modu (2026-10-02): global yönetici bakımda girince admin şeridi hemen görünsün
        putNoc(resp, session);  // 7/24 izleme ekibi (2026-10-04): giriş yanıtı da taşır — menü F5 beklemesin
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
    /** {@code /me} + giriş yanıtı için modül görünürlüğü; hata olursa KAPALI (sessiz sızma yerine sessiz gizleme). */
    @SuppressWarnings("unchecked")
    private boolean weeklyReportsVisible(Map<String, Object> resp, HttpSession session) {
        try {
            String role = String.valueOf(session.getAttribute("systemRole"));
            boolean adminOrAudit = SessionScope.isGlobalAdmin(session) || "AUDIT".equals(role);
            // ADMIN rolü (global ya da kapsamlı müdür) modül bayrağından bağımsız HER ZAMAN görür (2026-09-25).
            boolean adminRole = "ADMIN".equals(role);
            Object ids = resp.get("team_ids");
            if (weeklyReportService == null) return false;   // @Autowired(required=false): test/kısmi bağlamda kapalı say
            return weeklyReportService.visibleFor(ids instanceof java.util.Collection ? (java.util.Collection<Long>) ids : List.of(), adminOrAudit, adminRole);
        } catch (Exception e) {
            log.debug("weekly_reports_visible hesaplanamadı: {}", e.toString());
            return false;
        }
    }

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
