package com.sitemonitor.service.otp;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.LoginOtpChallenge;
import com.sitemonitor.repository.LoginOtpChallengeRepository;
import com.sitemonitor.service.AuditDetail;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Kodla giriş (push / e-posta ile 6 haneli tek kullanımlık kod) — 2026-10-02, kullanıcı isteği: "LDAP sorununda
 * kullanıcılar kodla girsin". İSTEK ve DOĞRULAMA burada; başarılı doğrulamadan sonraki oturum kurma ŞİFRE GİRİŞİYLE AYNI
 * yoldan ({@code AuthController.establishSession}) geçer.
 *
 * <h3>İstek ({@link #request})</h3>
 * <ul>
 *   <li>Yöntem kapalı → 400 (yapılandırma bilgisi, kişi bilgisi değil). IP başına istek sınırı (15 dk) → açıkça 429.</li>
 *   <li>Bunun dışında yanıt HER ZAMAN aynı: 200 {@code {success, challenge_id, channel, expires_in, resend_in}} —
 *       kullanıcı yok / pasif / kilitli / global yönetici izni yok / kanal hedefi yok / kişi başı sınır / bekleme /
 *       askı ayırt EDİLEMEZ. Uygun olmayan istek de bir TUZAK satırı doğurur (kod gönderilmez); onun doğrulaması gerçek
 *       yanlış kodla birebir aynı yanıtları verir.</li>
 *   <li>Süre de ayırt ettirmez: her istekte AYNI sorgular koşar (kısa devre yok) ve gönderim isteği izleyen eşzamansız
 *       işte yapılır ({@link LoginOtpDeliveryService}) — SMTP / ağ geçidi süresi yanıta binmez.</li>
 * </ul>
 *
 * <h3>Doğrulama ({@link #verify})</h3>
 * Kod yalnız onu ALAN tarayıcının challenge kimliğiyle geçerli (HMAC kimliği de kapsar); sabit-zamanlı karşılaştırma;
 * challenge başına en çok N yanlış deneme (vars. 3) → kilit; süre dolunca geçersiz; tek kullanım (koşullu UPDATE —
 * iki pod aynı anda tüketemez). Kullanıcı başına başarısız doğrulama (15 dk'da vars. 5) aşılınca kodla giriş o
 * kullanıcı için ASKIYA alınır (OTP_LOCKED) — şifre / LDAP girişi ve ilerleyici hesap kilidi ETKİLENMEZ (başkası kod
 * deneyerek hesabı kilitleyemez; denetim türleri {@code LOGIN_FAILED} değildir).
 *
 * <p><b>Kod hiçbir log'a, DB alanına, denetim ayrıntısına ya da hata metnine yazılmaz.</b>
 */
@Slf4j
@Service
public class LoginOtpService {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Pattern CODE_RE = Pattern.compile("\\d{6}");
    private static final Pattern ID_RE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Doğru kod + "başka yerde oturum var" onayı için isteğin canlı kalacağı süre (sn). */
    static final int CONFIRM_GRACE_SECONDS = 120;
    private static final long AUDIT_DEBOUNCE_MS = 30_000;
    private static final int DEBOUNCE_MAP_MAX = 10_000;

    public static final String OTP_INVALID = "OTP_INVALID";
    public static final String OTP_EXPIRED = "OTP_EXPIRED";
    public static final String OTP_LOCKED = "OTP_LOCKED";
    public static final String OTP_METHOD_DISABLED = "OTP_METHOD_DISABLED";
    public static final String OTP_RATE_LIMITED = "OTP_RATE_LIMITED";
    public static final String OTP_CHANNEL_INVALID = "OTP_CHANNEL_INVALID";
    public static final String USERNAME_REQUIRED = "USERNAME_REQUIRED";

    /** Teslim kanalı. Telde küçük harf ({@code push} / {@code email}); satırda büyük harf. */
    public enum Channel {
        PUSH, EMAIL;

        public String wire() { return name().toLowerCase(Locale.ROOT); }

        public UserService.LoginMethod loginMethod() {
            return this == PUSH ? UserService.LoginMethod.OTP_PUSH : UserService.LoginMethod.OTP_EMAIL;
        }

        public static Channel parse(String s) {
            if (s == null) return null;
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "push" -> PUSH;
                case "email", "e-mail", "mail" -> EMAIL;
                default -> null;
            };
        }
    }

    /** HTTP yanıtı (durum + gövde) — denetleyici aynen döner. */
    public record Result(int status, Map<String, Object> body) { }

    /** Doğru kodla doğrulanmış, henüz TÜKETİLMEMİŞ istek (oturum kararı denetleyicide). */
    public record Verified(LoginOtpChallenge challenge, Channel channel, AppUser user) { }

    /** Doğrulama sonucu: ya hata yanıtı ({@code failure}) ya doğrulanmış istek ({@code verified}). */
    public record VerifyOutcome(Result failure, Verified verified) {
        static VerifyOutcome fail(Result r) { return new VerifyOutcome(r, null); }
    }

    private final LoginOtpChallengeRepository repo;
    private final LoginMethodsService methods;
    private final UserService userService;
    private final AuditService auditService;
    private final OtpCodes codes;
    private final LoginOtpDeliveryService delivery;

    private Clock clock = Clock.systemUTC();

    /** Denetim ikizlenme kalkanı (IP sınırı / askı): anahtar → son yazma (ms). */
    private final ConcurrentHashMap<String, Long> auditAtMs = new ConcurrentHashMap<>();

    public LoginOtpService(LoginOtpChallengeRepository repo, LoginMethodsService methods, UserService userService,
                           AuditService auditService, OtpCodes codes, LoginOtpDeliveryService delivery) {
        this.repo = repo;
        this.methods = methods;
        this.userService = userService;
        this.auditService = auditService;
        this.codes = codes;
        this.delivery = delivery;
    }

    /** Test kancası. */
    void setClock(Clock clock) { this.clock = clock; }

    // ── İstek ──────────────────────────────────────────────────────────────────

    /**
     * Kod isteği. {@code english}: giriş sayfasının dili (push metni bu dilde; e-posta iki dillidir).
     */
    public Result request(String rawUsername, String rawChannel, String ip, String userAgent, boolean english) {
        Channel ch = Channel.parse(rawChannel);
        if (ch == null) {
            return error(400, OTP_CHANNEL_INVALID, Msg.t("Geçersiz kanal.", "Invalid channel."), null);
        }
        if (!methods.available(ch)) {
            return error(400, OTP_METHOD_DISABLED, Msg.t("Bu giriş yöntemi şu an kapalı.", "This sign-in method is currently disabled."), null);
        }
        String typed = rawUsername == null ? "" : rawUsername.strip();
        if (typed.isEmpty() || typed.length() > 120) {
            return error(400, USERNAME_REQUIRED, Msg.t("Kullanıcı adınızı girin.", "Enter your username."), null);
        }
        String ua = clip(userAgent, 255);
        Instant now = clock.instant();
        String nowIso = ISO.format(now);
        String since = ISO.format(now.minusSeconds(LoginMethodsService.WINDOW_SECONDS));

        // 1) IP başına istek sınırı — açıkça 429 (IP bilgisi hesap varlığını sızdırmaz).
        if (ip != null && repo.countByIpAndCreatedAtGreaterThanEqual(ip, since) >= methods.maxRequestsPerIp()) {
            if (shouldAudit("IP|" + ip)) {
                auditService.recordOtp("LOGIN_OTP_REQUESTED", UserService.normalizeUsername(typed), null, null, null,
                        "BLOCKED", "IP_RATE_LIMITED: IP başına kod isteği sınırı aşıldı",
                        AuditDetail.of("channel", ch.name(), "result", "REJECTED", "reason", "IP_RATE_LIMITED"), ip, ua);
            }
            log.warn("Kodla giriş isteği IP sınırına takıldı: IP={}", ip);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("retry_after", LoginMethodsService.WINDOW_SECONDS);
            return error(429, OTP_RATE_LIMITED, Msg.t("Bu cihazdan çok fazla kod istendi. Lütfen birkaç dakika sonra yeniden deneyin.",
                    "Too many codes were requested from this device. Please try again in a few minutes."), extra);
        }

        // 2) Uygunluk + kişi başı sınırlar — HER istekte AYNI sorgular (kısa devre yok: yanıt süresi ayırt ettirmesin).
        AppUser user = userService.findByUsername(typed).orElse(null);
        String key = user != null && user.getUsername() != null ? user.getUsername() : UserService.normalizeUsername(typed);
        boolean locked = isLocked(key);
        boolean suspended = repo.sumFailuresSince(key, since) >= methods.maxFailedVerifications();
        boolean overUser = repo.countByUsernameAndUserIdIsNotNullAndCreatedAtGreaterThanEqual(key, since) >= methods.maxRequestsPerUser();
        int cooldownSec = methods.resendCooldownSeconds();
        boolean cooling = repo.findTopByUsernameAndChannelAndUserIdIsNotNullOrderByCreatedAtDesc(key, ch.name())
                .map(last -> parse(last.getCreatedAt()).plusSeconds(cooldownSec).isAfter(now)).orElse(false);
        String reason = ineligibility(user, ch, locked);
        if (reason == null && suspended) reason = "SUSPENDED";
        if (reason == null && overUser) reason = "USER_RATE_LIMITED";
        if (reason == null && cooling) reason = "COOLDOWN";

        int ttl = methods.ttlSeconds(ch);
        String id = UUID.randomUUID().toString();
        String code = codes.newCode();
        LoginOtpChallenge c = new LoginOtpChallenge();
        c.setId(id);
        c.setUsername(key);
        c.setUserId(reason == null ? user.getId() : null);
        c.setChannel(ch.name());
        c.setCodeHmac(codes.hmac(id, code));
        c.setCreatedAt(nowIso);
        c.setExpiresAt(ISO.format(now.plusSeconds(ttl)));
        c.setAttempts(0);
        c.setMaxAttempts(methods.maxAttempts());
        c.setStatus(LoginOtpChallenge.STATUS_PENDING);
        c.setDeliveryStatus(reason == null ? LoginOtpChallenge.DELIVERY_QUEUED : LoginOtpChallenge.DELIVERY_SUPPRESSED_PREFIX + reason);
        c.setIp(clip(ip, 64));
        c.setUserAgent(ua);
        repo.save(c);

        auditService.recordOtp("LOGIN_OTP_REQUESTED", key,
                user == null ? null : user.getId(), user == null ? null : user.getTeamId(), user == null ? null : user.getSystemRole(),
                reason == null ? "SUCCESS" : "BLOCKED", reason == null ? null : "SUPPRESSED: " + reason,
                AuditDetail.of("channel", ch.name(), "result", reason == null ? "SENT" : "SUPPRESSED", "reason", reason,
                        "challenge", shortId(id)),
                ip, ua);

        if (reason == null) {
            delivery.dispatch(new LoginOtpDeliveryService.Job(id, ch, user.getUsername(), user.getId(), user.getTeamId(),
                    user.getSystemRole(), displayName(user), user.getEmail(), ttl, now, ip, ua, english), code);
            log.info("Kodla giriş kodu istendi: user={} kanal={} istek={}", key, ch, shortId(id));
        } else {
            log.info("Kodla giriş isteği sessizce bastırıldı: user={} kanal={} neden={} istek={}", key, ch, reason, shortId(id));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("challenge_id", id);
        body.put("channel", ch.wire());
        body.put("expires_in", ttl);
        body.put("resend_in", cooldownSec);
        return new Result(200, body);
    }

    /**
     * Kod gönderilmez mi — neden kodu (iç; yalnız denetim / ayar ekranı görür). null = uygun.
     * Uygun: kullanıcı var, aktif, kilitli değil, global yöneticiyse ayar izin veriyor, kanal hedefi var
     * (push: ağ geçidinin kişiyi hedeflediği kimlik = kullanıcı adı/sicil — alarm push'uyla AYNI alan; e-posta: adres).
     */
    String ineligibility(AppUser u, Channel ch, boolean locked) {
        if (u == null) return "UNKNOWN_USER";
        if (!Boolean.TRUE.equals(u.getActive())) return "INACTIVE";
        if (locked || Boolean.TRUE.equals(u.getPermanentLock())) return "LOCKED";
        if (isGlobalAdmin(u) && !methods.allowGlobalAdmins()) return "GLOBAL_ADMIN_NOT_ALLOWED";
        // Zorunlu parola değişikliği bekleyen hesap (yönetici sıfırladı, geçici parola gönderildi) kodla GİREMEZ
        // (2026-10-03): kodla kurulan oturumda parola değiştirme ucu mevcut parolayı ister ve kullanıcı takılırdı.
        // Geçici parolayla girmesi gereken akış budur; dışarıya yine aynı genel yanıt (tuzak satır).
        if (Boolean.TRUE.equals(u.getMustChangePassword())) return "MUST_CHANGE_PASSWORD";
        if (ch == Channel.EMAIL && !validEmail(u.getEmail())) return "NO_TARGET";
        if (ch == Channel.PUSH && (u.getUsername() == null || u.getUsername().isBlank())) return "NO_TARGET";
        return null;
    }

    /** Doğrulama anında: global yöneticiye kodla giriş (istek sonrası) kapatıldı mı. */
    public boolean globalAdminBlocked(AppUser u) {
        return isGlobalAdmin(u) && !methods.allowGlobalAdmins();
    }

    /** Genel "süresi doldu / artık geçerli değil" gövdesi (denetleyici de kullanır). */
    public static Map<String, Object> expiredBody() {
        return expired().body();
    }

    /** Global yönetici: ADMIN rolü + takım kapsamı yok (SystemMaintenanceService.isGlobalAdminAccount ile aynı kural). */
    boolean isGlobalAdmin(AppUser u) {
        if (u == null || !"ADMIN".equals(u.getSystemRole())) return false;
        try {
            return userService.computeViewTeamIds(u) == null;
        } catch (Exception e) {
            return true;   // belirsizse temkinli: global yönetici say (ayar kapalıyken kod gitmez)
        }
    }

    private boolean isLocked(String username) {
        try {
            UserService.LockoutStatus ls = userService.checkLockout(username);
            return ls != null && ls.isBlocked();
        } catch (Exception e) {
            return false;
        }
    }

    // ── Doğrulama ──────────────────────────────────────────────────────────────

    /**
     * Kodu doğrular. Başarı = doğru kod + PENDING + süresi dolmamış + deneme hakkı var — istek henüz TÜKETİLMEZ
     * (oturum kararı — pasif / bakım / kilit / başka yerde oturum — denetleyicide; ardından {@link #consume} ya da
     * {@link #block} / {@link #holdForConfirmation}).
     */
    public VerifyOutcome verify(String challengeId, String code, String ip, String userAgent) {
        String ua = clip(userAgent, 255);
        String id = challengeId == null ? "" : challengeId.trim();
        if (!ID_RE.matcher(id).matches()) return VerifyOutcome.fail(expired());
        Optional<LoginOtpChallenge> opt = repo.findById(id);
        if (opt.isEmpty()) return VerifyOutcome.fail(expired());   // hiç verilmemiş kimlik — denetlenecek kişi yok
        LoginOtpChallenge c = opt.get();
        Channel ch = Channel.parse(c.getChannel());
        if (ch == null) return VerifyOutcome.fail(expired());
        if (!methods.available(ch)) {
            return VerifyOutcome.fail(error(400, OTP_METHOD_DISABLED,
                    Msg.t("Bu giriş yöntemi şu an kapalı.", "This sign-in method is currently disabled."), null));
        }
        Instant now = clock.instant();
        String nowIso = ISO.format(now);
        String status = c.getStatus();
        if (LoginOtpChallenge.STATUS_LOCKED.equals(status)) return VerifyOutcome.fail(locked());
        if (!LoginOtpChallenge.STATUS_PENDING.equals(status)) {
            // Tüketilmiş / reddedilmiş / süresi dolmuş istek yeniden denendi — tek kullanım.
            audit("LOGIN_OTP_EXPIRED", c, "FAILURE", "OTP_EXPIRED: kullanılmış ya da kapanmış istek",
                    AuditDetail.of("channel", ch.name(), "challenge", shortId(id), "reason", "NOT_PENDING:" + status), ip, ua);
            return VerifyOutcome.fail(expired());
        }
        if (parse(c.getExpiresAt()).isBefore(now)) {
            if (repo.closePending(id, LoginOtpChallenge.STATUS_EXPIRED, nowIso) > 0) {
                audit("LOGIN_OTP_EXPIRED", c, "FAILURE", "OTP_EXPIRED: süre doldu",
                        AuditDetail.of("channel", ch.name(), "challenge", shortId(id), "reason", "TTL"), ip, ua);
            }
            return VerifyOutcome.fail(expired());
        }
        String since = ISO.format(now.minusSeconds(LoginMethodsService.WINDOW_SECONDS));
        if (repo.sumFailuresSince(c.getUsername(), since) >= methods.maxFailedVerifications()) {
            if (shouldAudit("SUSP|" + c.getUsername())) {
                audit("LOGIN_OTP_LOCKED", c, "BLOCKED", "OTP_LOCKED: kullanıcı başına başarısız doğrulama sınırı (kodla giriş askıda)",
                        AuditDetail.of("channel", ch.name(), "challenge", shortId(id), "reason", "USER_SUSPENDED"), ip, ua);
            }
            return VerifyOutcome.fail(locked());
        }

        String given = code == null ? "" : code.trim();
        boolean wellFormed = CODE_RE.matcher(given).matches();
        boolean match = codes.matches(id, given, c.getCodeHmac());   // biçim bozuk olsa da HER ZAMAN hesaplanır
        if (!wellFormed || !match || c.decoy()) {
            repo.recordFailure(id, nowIso);
            int max = c.getMaxAttempts() == null ? methods.maxAttempts() : c.getMaxAttempts();
            int attempts = repo.findById(id).map(LoginOtpChallenge::getAttempts).filter(Objects::nonNull)
                    .orElse(max);
            int left = Math.max(0, max - attempts);
            if (left == 0) {
                audit("LOGIN_OTP_LOCKED", c, "BLOCKED", "OTP_LOCKED: deneme hakkı bitti",
                        AuditDetail.of("channel", ch.name(), "challenge", shortId(id), "reason", "ATTEMPTS", "attempts", attempts), ip, ua);
                return VerifyOutcome.fail(locked());
            }
            audit("LOGIN_OTP_VERIFY_FAILED", c, "FAILURE", "OTP_INVALID: yanlış kod",
                    AuditDetail.of("channel", ch.name(), "challenge", shortId(id), "attempts_left", left), ip, ua);
            return VerifyOutcome.fail(invalid(left));
        }
        AppUser user = userService.findByUsername(c.getUsername()).orElse(null);
        if (user == null || !Objects.equals(user.getId(), c.getUserId())) {
            // Kullanıcı silinmiş / ad başka kayda geçmiş — kod doğru olsa da giriş yok (genel yanıt).
            block(c);
            return VerifyOutcome.fail(expired());
        }
        return new VerifyOutcome(null, new Verified(c, ch, user));
    }

    /** Tek kullanım: doğrulanmış isteği tüketir. true = bu çağrı kazandı (eşzamanlı ikinci istek false alır). */
    public boolean consume(LoginOtpChallenge c) {
        return repo.consume(c.getId(), ISO.format(clock.instant())) == 1;
    }

    /** Doğru koddan SONRA reddedilen giriş (pasif / bakım / kilit): kod yeniden kullanılamaz. */
    public void block(LoginOtpChallenge c) {
        try {
            repo.closePending(c.getId(), LoginOtpChallenge.STATUS_BLOCKED, ISO.format(clock.instant()));
        } catch (Exception e) {
            log.debug("Kodla giriş isteği kapatılamadı: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Doğru kod ama başka yerde canlı oturum (409): istek TÜKETİLMEZ, onay penceresi süresince canlı kalır
     * ({@value #CONFIRM_GRACE_SECONDS} sn) — kullanıcı onaylayınca aynı kodla {@code forceLogin} gönderir.
     */
    public void holdForConfirmation(LoginOtpChallenge c) {
        try {
            repo.extendExpiry(c.getId(), ISO.format(clock.instant().plusSeconds(CONFIRM_GRACE_SECONDS)));
        } catch (Exception e) {
            log.debug("Kodla giriş isteği onay için uzatılamadı: {}", e.getClass().getSimpleName());
        }
    }

    // ── Yanıt gövdeleri ─────────────────────────────────────────────────────────

    static Result error(int status, String code, String message, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error_code", code);
        body.put("error", message);
        if (extra != null) body.putAll(extra);
        return new Result(status, body);
    }

    static Result invalid(int attemptsLeft) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("attempts_left", attemptsLeft);
        return error(401, OTP_INVALID, Msg.t("Kod hatalı.", "The code is incorrect."), extra);
    }

    static Result expired() {
        return error(401, OTP_EXPIRED, Msg.t("Kodun süresi doldu ya da kod artık geçerli değil. Yeni bir kod isteyin.",
                "The code has expired or is no longer valid. Request a new code."), null);
    }

    static Result locked() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("attempts_left", 0);
        return error(401, OTP_LOCKED, Msg.t("Çok fazla hatalı deneme. Yeni bir kod isteyin ya da şifrenizle giriş yapın.",
                "Too many incorrect attempts. Request a new code or sign in with your password."), extra);
    }

    // ── Yardımcılar ────────────────────────────────────────────────────────────

    private void audit(String type, LoginOtpChallenge c, String outcome, String reason, String detail, String ip, String ua) {
        try {
            auditService.recordOtp(type, c.getUsername(), c.getUserId(), null, null, outcome, reason, detail, ip, ua);
        } catch (Exception e) {
            log.debug("Kodla giriş denetimi yazılamadı: {}", e.getClass().getSimpleName());
        }
    }

    private boolean shouldAudit(String key) {
        long now = System.currentTimeMillis();
        Long last = auditAtMs.get(key);
        if (last != null && now - last < AUDIT_DEBOUNCE_MS) return false;
        if (auditAtMs.size() > DEBOUNCE_MAP_MAX) auditAtMs.clear();
        auditAtMs.put(key, now);
        return true;
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return Instant.EPOCH;
        try {
            return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    static boolean validEmail(String e) {
        if (e == null) return false;
        String s = e.trim();
        int at = s.indexOf('@');
        return at > 0 && at < s.length() - 1 && s.indexOf('@', at + 1) < 0 && !s.contains(" ");
    }

    private static String displayName(AppUser u) {
        if (u.getDisplayName() != null && !u.getDisplayName().isBlank()) return u.getDisplayName();
        String full = ((u.getFirstName() == null ? "" : u.getFirstName()) + " " + (u.getLastName() == null ? "" : u.getLastName())).trim();
        return full.isEmpty() ? u.getUsername() : full;
    }

    static String shortId(String id) {
        return id == null ? null : id.substring(0, Math.min(8, id.length()));
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
