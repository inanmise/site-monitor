package com.sitemonitor.config;

import com.sitemonitor.controller.AuthController;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC = Set.of(
            "/api/login", "/api/logout",
            // Haftalık rapor e-posta onayı — PO login'siz, token ile onaylar/iade eder (token = yetki).
            "/api/weekly-reports/approve-link",
            "/api/weekly-reports/approve-link/confirm",
            "/api/weekly-reports/approve-link/reject",
            // Branding (beyaz etiket) — login sayfası auth ÖNCESİ logo/başlık/renk okur; hassas veri yok.
            "/api/branding",
            // Login hero istatistikleri — yalnız iki toplam sayı (hedef adedi + erişilebilirlik %), detay yok.
            "/api/public-stats",
            // Login "sorun bildir" — kullanıcı giremediği için auth'suz; IP rate-limit + uzunluk sınırı içeride.
            "/api/login-help",
            // ErrorBoundary otomatik çökme bildirimi — çökme login öncesi de olabilir; oturum varsa
            // kullanıcı adı içeride okunur. IP rate-limit + uzunluk sınırı içeride.
            "/api/client-error-report",
            // Sistem Bakım Modu (2026-10-02): giriş sayfası bakım kartını oturumsuz okur — yalnız durum, pencere saatleri,
            // TR/EN mesaj ve iletişim; kimlik/IP/sayaç YOK (SystemMaintenanceService.publicStatus).
            "/api/public/system-maintenance",
            // Giriş Yöntemleri / kodla giriş (2026-10-02): giriş sayfası hangi yöntemlerin açık olduğunu oturumsuz okur
            // (yalnız yapılandırma — kişi bilgisi yok); kod isteği ve doğrulaması oturum KURMADAN önce çağrılır. Oran
            // sınırları ve tek kullanımlık kod kuralları LoginOtpService'te; gövdeler RequestLoggingFilter'da loglanmaz.
            "/api/public/login-methods",
            "/api/login/otp/request",
            "/api/login/otp/verify");

    /** Endpoints a user with mustChangePassword=true is still allowed to call. */
    private static final Set<String> FORCED_CHANGE_WHITELIST = Set.of(
            "/api/me",
            "/api/me/change-password",
            "/api/logout",
            "/api/login"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    private final RememberMeService rememberMeService;
    private final UserService userService;
    private final AuthController authController;
    // IP çözümü AuditService üzerinden yapılır (ClientIpResolver'ı AYRICA enjekte ETME):
    // interceptor bir @Component olduğu için her @WebMvcTest diliminde kuruluyor ve dilimler
    // yalnız UserService/AuthController/AuditService'i mock'luyor — yeni bir bean eklemek
    // ~30 controller testinin bağlamını "No qualifying bean" ile düşürüyordu.
    private final AuditService auditService;

    /** Sessiz reauth için audit ikizlenme kalkanı: username → son yazma anı (ms). */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> reauthAuditAtMs =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long REAUTH_AUDIT_DEBOUNCE_MS = 30_000;
    private static final int REAUTH_MAP_MAX = 10_000;   // sert üst sınır (UserService.SESSION_MAP_MAX deseni)

    /**
     * Pasif hesap "mezar taşı" (2026-10-02, kullanıcı kararı). Pasife alınan kullanıcının canlı oturumu KİMLİKSİZLEŞTİRİLİR:
     * tüm öznitelikleri (authenticated, username, userId, rol, kapsam…) silinir, yerine yalnız bu iki öznitelik konur ve
     * oturum ömrü {@link #INACTIVE_TOMBSTONE_TTL_SECONDS}'e iner. Oturum artık hiçbir işe yetkili değildir.
     *
     * <p>Neden {@code invalidate()} değil: SPA aynı anda birkaç istek atar ve birden çok sekme aynı çerezi paylaşır. Oturum
     * yok edilseydi ilk istek "hesabınız pasif" alır, aynı anda uçuştaki diğer istekler ve öteki sekmeler OTURUMSUZ genel
     * 401'e düşüp "oturum süresi doldu" sayfasına yönlenirdi. Mezar taşı oturum deposunda (prod: JDBC → tüm pod'lar)
     * durduğu için aynı çerezle gelen HER istek aynı sinyali alır. Giriş ({@code /api/login}) ve çıkış eski oturumu yok eder.
     */
    public static final String ATTR_INACTIVE = "accountInactive";
    public static final String ATTR_INACTIVE_USER = "accountInactiveUser";
    static final int INACTIVE_TOMBSTONE_TTL_SECONDS = 15 * 60;

    /** Pasif kesim denetim ikizlenme kalkanı (eşzamanlı istekler aynı oturumu birlikte keser): anahtar → son yazma (ms). */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> inactiveAuditAtMs =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Sistem Bakım Modu "mezar taşı" (2026-10-02, kullanıcı kararı) — pasif hesap deseninin AYNISI: bakım başlayınca global
     * yönetici olmayan kullanıcının canlı oturumu kimliksizleştirilir, yerine yalnız bu iki öznitelik kalır. Aynı çerezle
     * gelen her istek (öteki sekmeler, uçuştaki istekler, öteki pod'lar — JDBC deposu) aynı {@code MAINTENANCE} sinyalini
     * alır; bakım bitince mezar taşı kapatılır ve istek oturumsuz sayılır (yeniden giriş).
     */
    public static final String ATTR_MAINTENANCE = "systemMaintenance";
    public static final String ATTR_MAINTENANCE_USER = "systemMaintenanceUser";

    /**
     * Bakım durumu — İSTEĞE BAĞLI bağımlılık (yapıcıya EKLENMEZ): interceptor her {@code @WebMvcTest} diliminde kuruluyor ve
     * dilimler servisleri yüklemiyor; zorunlu bağımlılık ~30 controller testinin bağlamını düşürürdü (IP çözümü notuyla aynı
     * gerekçe). Yoksa (dilim/birim test) kapı hiç çalışmaz → davranış bugünküyle birebir.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.SystemMaintenanceService systemMaintenance;

    /** Test kancası. */
    void setSystemMaintenance(com.sitemonitor.service.SystemMaintenanceService s) { this.systemMaintenance = s; }

    /**
     * 7/24 izleme ekibi operatör bayrağı (2026-10-04) — İSTEĞE BAĞLI (bakım servisiyle aynı gerekçe: dilim testlerinin
     * bağlamı büyümesin). Her geçerli istekte oturum öznitelikleri ≤ 30 sn'lik önbellekle eşitlenir: takım listeden
     * çıkarılınca ya da kullanıcı takımdan ayrılınca operatörlük yeniden giriş BEKLEMEDEN kalkar. Yoksa hiçbir şey olmaz.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.service.noc.NocOperatorService nocOperators;

    /** Test kancası. */
    void setNocOperators(com.sitemonitor.service.noc.NocOperatorService s) { this.nocOperators = s; }

    private void syncNocOperator(HttpSession session) {
        try {
            if (nocOperators != null) nocOperators.sync(session);
        } catch (Exception e) {
            // Kapalı düşer: bayrak yazılamadıysa önceki değer kalır; okuma hatası bir isteği asla düşürmez.
        }
    }

    private boolean maintenanceActive() {
        try {
            return systemMaintenance != null && systemMaintenance.isActive();
        } catch (Exception e) {
            return false;   // fail-open: bakım durumu okunamıyorsa kimse dışarıda kalmaz
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        // BK1 (2026-09-27): karar HAM getRequestURI() ile DEĞİL, yönlendiricinin gördüğü NORMALİZE yolla
        // verilir. Ham yolda "/api;x/certificates" "/api/" ile başlamıyordu → kapı erken true dönüyordu,
        // DispatcherServlet ise matris içeriğini kırpıp isteği korumalı uca yönlendiriyordu (oturumsuz 200).
        // Yol çözülemezse (null) istek KORUMALI sayılır — PUBLIC'e ya da "API değil" dalına düşmez.
        String path = RequestPathFirewallFilter.lookupPath(req);
        if (path != null && (!path.startsWith("/api/") || PUBLIC.contains(path))) return true;
        if (path == null) path = "";

        // 1. Valid session check
        HttpSession session = req.getSession(false);
        if (session != null) {
            try {
                if (Boolean.TRUE.equals(session.getAttribute("authenticated"))) {
                    String username = (String) session.getAttribute("username");
                    // PASİF HESAP KAPISI (2026-10-02, kullanıcı kararı) — süpersede kontrolünden ÖNCE: pasifleştirme
                    // tek-oturum kaydına TERMINATED sentinel'i de yazar; sıra ters olsaydı istemci "oturum düştü"
                    // (/?session=expired) görürdü, "hesabınız pasif" penceresini değil. Merkezî kapı budur — tek tek
                    // uçlara pasif kontrolü serpiştirilmez. Tek-kolon okuma + kısa TTL önbellek (UserService).
                    if (userService.isAccountInactive(username)) {
                        endInactiveSession(session, username, req, res);
                        return writeAccountInactive(res);
                    }
                    // SİSTEM BAKIM KAPISI (2026-10-02, kullanıcı kararı) — pasif kontrolünden SONRA, süpersede kontrolünden
                    // ÖNCE: bakım AKTİFKEN yalnız global yönetici içeride kalır. Kesim pasif hesapla aynı mezar taşı
                    // desenidir (invalidate değil); sıra ters olsaydı kesilen oturumun öteki sekmesi "oturum düştü"
                    // (/?session=expired) görürdü. Bakım yoksa tek bellek-içi okuma (≤ 5 sn önbellek) — DB yok.
                    if (maintenanceActive() && !com.sitemonitor.controller.SessionScope.isGlobalAdmin(session)) {
                        endMaintenanceSession(session, username, req, res);
                        return writeMaintenance(res);
                    }
                    // Tek aktif oturum: bu oturum kullanıcının kayıtlı (daha yeni) oturumu tarafından
                    // geçersiz kılındıysa (başka yerden login ya da admin "Sonlandır"), oturumu kapat ve
                    // temiz 401 dön → frontend otomatik logout (/?session=expired). remember-me cookie'si
                    // de silinir ki kullanıcı sessizce geri dönmesin.
                    if (userService.isSessionSuperseded(username, session.getId())) {
                        try { session.invalidate(); } catch (IllegalStateException ignored) { /* zaten kapalı */ }
                        clearRememberMeCookie(res);
                        return writeUnauthorized(res, "Session superseded");
                    }
                    syncNocOperator(session);   // 7/24 operatörlüğü (2026-10-04) — oturum geçerli sayıldıktan SONRA
                    return enforceForcedPasswordChange(session, path, res);
                } else if (Boolean.TRUE.equals(session.getAttribute(ATTR_INACTIVE))) {
                    // Pasif hesabın mezar taşı oturumu: hesap hâlâ pasifse aynı sinyal (çok sekme / eşzamanlı istek).
                    // Yeniden aktifleştirildiyse mezar taşı kapatılır ve istek oturumsuz sayılır (yeniden giriş ister).
                    String tombUser = (String) session.getAttribute(ATTR_INACTIVE_USER);
                    if (userService.isAccountInactive(tombUser)) {
                        clearRememberMeCookie(res);
                        return writeAccountInactive(res);
                    }
                    try { session.invalidate(); } catch (IllegalStateException ignored) { /* zaten kapalı */ }
                } else if (Boolean.TRUE.equals(session.getAttribute(ATTR_MAINTENANCE))) {
                    // Bakım mezar taşı: bakım sürüyorsa aynı sinyal (çok sekme / eşzamanlı istek); bittiyse mezar taşı
                    // kapatılır, istek oturumsuz sayılır (giriş yeniden açıldı — kullanıcı yeniden giriş yapar).
                    if (maintenanceActive()) {
                        clearRememberMeCookie(res);
                        return writeMaintenance(res);
                    }
                    try { session.invalidate(); } catch (IllegalStateException ignored) { /* zaten kapalı */ }
                }
            } catch (IllegalStateException alreadyInvalidated) {
                // Oturum, eşzamanlı (paralel) bir istek tarafından zaten geçersiz kılınmış. Tarayıcılar
                // aynı anda birden fazla istek atar; biri süpersede ile oturumu kapatınca diğeri kapalı
                // oturumda getAttribute çağırıp HTTP 500'e yol açabiliyordu. Artık 500 yerine temiz 401
                // (oto-logout) dönüyoruz.
                clearRememberMeCookie(res);
                return writeUnauthorized(res, "Session superseded");
            }
        }

        // 2. Remember-me cookie — if valid, restore full user session.
        //    The cookie must NOT outrank account state: a disabled (active=false)
        //    or locked (temporary/permanent lockout) account is rejected here, same
        //    as the password-login path, otherwise an admin lock could be bypassed
        //    by an outstanding remember-me cookie until its 7-day TTL expires.
        // IP geçirilir: doğrulama başarılıysa token'ın "son kullanım" izi güncellenir (Cihaz
        // Geçmişi ekranı "bu cihaz en son ne zaman otomatik girdi" diye soruyor).
        String rememberIp = auditService.resolveIp(req);
        Optional<String> usernameOpt = findRememberMeCookie(req)
                .flatMap(t -> rememberMeService.validate(t, rememberIp));

        if (usernameOpt.isPresent()) {
            String username = usernameOpt.get();
            Optional<AppUser> userOpt = userService.findByUsername(username);
            if (userOpt.isPresent() && !Boolean.TRUE.equals(userOpt.get().getActive())) {
                // PASİF hesabın geçerli çerezi (2026-10-02): oturum KURULMAZ; kullanıcının tüm token'ları silinir, çerez
                // düşürülür ve istemci "hesabınız pasif" sinyalini alır (açılışta giriş sayfası pasif bildirimiyle açılır).
                AppUser u = userOpt.get();
                rememberMeService.invalidateAllForUser(u.getUsername());
                clearRememberMeCookie(res);
                if (shouldAuditInactive("RM|" + u.getUsername())) {
                    auditService.recordInactiveLogin(u.getUsername(), u.getId(), u.getTeamId(), u.getSystemRole(),
                            rememberIp, req.getHeader("User-Agent"), "REMEMBER_ME");
                }
                return writeAccountInactive(res);
            }
            if (userOpt.isPresent() && Boolean.TRUE.equals(userOpt.get().getActive()) && maintenanceActive()
                    && !systemMaintenance.isGlobalAdminAccount(userOpt.get())) {
                // SİSTEM BAKIMI (2026-10-02): çerez global yönetici olmayan hesaba ait → oturum KURULMAZ; token'lar silinir,
                // çerez düşürülür, istemci bakım sinyalini alır (giriş sayfası bakım kartıyla açılır). Engellenen giriş sayılır.
                AppUser u = userOpt.get();
                rememberMeService.invalidateAllForUser(u.getUsername());
                clearRememberMeCookie(res);
                if (shouldAuditInactive("RMM|" + u.getUsername())) {
                    auditService.recordMaintenanceLogin(u.getUsername(), u.getId(), u.getTeamId(), u.getSystemRole(),
                            rememberIp, req.getHeader("User-Agent"), "REMEMBER_ME");
                    systemMaintenance.recordBlockedLogin();
                }
                return writeMaintenance(res);
            }
            if (userOpt.isPresent()
                    && Boolean.TRUE.equals(userOpt.get().getActive())
                    && !userService.checkLockout(username).isBlocked()) {
                HttpSession newSession = req.getSession(true);
                AppUser user = userOpt.get();
                authController.populateSession(newSession, user);
                // Tek aktif oturum: remember-me ile kurulan oturum da kullanıcının "aktif" oturumu olsun.
                // Bu yol bir GİRİŞTİR: damgası basılır ve — bugüne kadar hiç yazılmayan — denetim
                // kaydı da bırakılır, yoksa çerezle dönen kullanıcı "Etkinliklerim"de kendi girişini
                // göremez. CANONICAL username: yazılan/cookie'deki case DB'dekinden farklı olabilir.
                String clientIp = rememberIp;   // yukarıda bir kez çözüldü
                userService.recordSuccessfulLogin(user.getUsername(), newSession.getId(),
                        clientIp, UserService.LoginMethod.REMEMBER_ME);
                if (shouldAuditReauth(user.getUsername())) {
                    // 2026-10-03: ayrıntıda giriş kanalı REMEMBER_ME (Giriş Yöntemleri → İstatistikler parola girişinden ayırır)
                    auditService.recordLogin(user.getUsername(), user.getId(), user.getTeamId(),
                            user.getSystemRole(), clientIp, req.getHeader("User-Agent"),
                            newSession.getId(), true, null, null, 5, "REMEMBER_ME");
                }
                syncNocOperator(newSession);   // 7/24 operatörlüğü (populateSession de yazar; dilim testinde o mock'tur)
                // Apply the forced-password-change gate to the restored session too,
                // so the cookie path can't sidestep the modal for one request.
                return enforceForcedPasswordChange(newSession, path, res);
            }
        }

        return writeUnauthorized(res, "Unauthorized");
    }

    /**
     * Sessiz reauth dendiğinde denetim kaydı yazılsın mı?
     *
     * <p>Oturum düştükten sonra tarayıcı remember-me çereziyle AYNI ANDA birkaç istek gönderir;
     * her biri buraya girer. Kalkan olmasaydı tek bir dönüş için "Etkinliklerim"de arka arkaya
     * 5-10 LOGIN satırı belirir ve kullanıcının kendi geçmişi okunamaz hâle gelirdi.
     * (Damganın kendi koruması ayrıca {@code UserService.stampDedupeSeconds}'tadır.)
     */
    private boolean shouldAuditReauth(String username) {
        long now = System.currentTimeMillis();
        Long last = reauthAuditAtMs.get(username);
        if (last != null && now - last < REAUTH_AUDIT_DEBOUNCE_MS) return false;
        if (reauthAuditAtMs.size() > REAUTH_MAP_MAX) reauthAuditAtMs.clear();
        reauthAuditAtMs.put(username, now);
        return true;
    }

    /**
     * Pasife alınan kullanıcının canlı oturumunu keser (2026-10-02): oturum kimliksizleştirilir (mezar taşı, bkz.
     * {@link #ATTR_INACTIVE}), kullanıcının TÜM remember-me token'ları silinir, çerez düşürülür ve
     * {@code SESSION_ENDED_INACTIVE} denetim kaydı yazılır (eşzamanlı istekler için 30 sn ikizlenme kalkanı).
     */
    private void endInactiveSession(HttpSession session, String username, HttpServletRequest req, HttpServletResponse res) {
        String sid = session.getId();
        Long userId = longAttr(session.getAttribute("userId"));
        Long teamId = longAttr(session.getAttribute("teamId"));
        Object role = session.getAttribute("systemRole");
        for (String name : java.util.Collections.list(session.getAttributeNames())) session.removeAttribute(name);
        session.setAttribute(ATTR_INACTIVE, Boolean.TRUE);
        session.setAttribute(ATTR_INACTIVE_USER, username);
        session.setMaxInactiveInterval(INACTIVE_TOMBSTONE_TTL_SECONDS);
        rememberMeService.invalidateAllForUser(username);   // oturumdaki ad CANONICAL (populateSession)
        clearRememberMeCookie(res);
        if (shouldAuditInactive("S|" + username + "|" + sid)) {
            auditService.recordAction("SESSION_ENDED_INACTIVE", username, userId, teamId,
                    role == null ? null : role.toString(), "USER", username,
                    "Hesap pasife alındığı için canlı oturum sonlandırıldı (hatırlanan girişler silindi)",
                    auditService.resolveIp(req), req.getHeader("User-Agent"), sid);
        }
    }

    /**
     * Bakım başlayınca global yönetici olmayan kullanıcının canlı oturumunu keser (2026-10-02) — pasif hesap kesiminin
     * aynası: oturum kimliksizleştirilir (mezar taşı, bkz. {@link #ATTR_MAINTENANCE}), kullanıcının remember-me token'ları
     * silinir, çerez düşürülür, tek-oturum kaydı temizlenir (bakım sonrası girişte yanlış "başka yerde oturum" onayı çıkmasın),
     * {@code SESSION_ENDED_MAINTENANCE} denetimi yazılır ve kapatılan oturum bakım kaydına sayılır (eşzamanlı istekler için
     * 30 sn ikizlenme kalkanı — sayaç da kalkanın içinde, aynı oturum iki kez sayılmaz).
     */
    private void endMaintenanceSession(HttpSession session, String username, HttpServletRequest req, HttpServletResponse res) {
        String sid = session.getId();
        Long userId = longAttr(session.getAttribute("userId"));
        Long teamId = longAttr(session.getAttribute("teamId"));
        Object role = session.getAttribute("systemRole");
        for (String name : java.util.Collections.list(session.getAttributeNames())) session.removeAttribute(name);
        session.setAttribute(ATTR_MAINTENANCE, Boolean.TRUE);
        session.setAttribute(ATTR_MAINTENANCE_USER, username);
        session.setMaxInactiveInterval(INACTIVE_TOMBSTONE_TTL_SECONDS);
        if (username != null) {
            rememberMeService.invalidateAllForUser(username);
            try { userService.clearActiveSession(username, sid); } catch (Exception ignored) { /* kayıt best-effort */ }
        }
        clearRememberMeCookie(res);
        if (shouldAuditInactive("M|" + username + "|" + sid)) {
            auditService.recordAction("SESSION_ENDED_MAINTENANCE", username, userId, teamId,
                    role == null ? null : role.toString(), "USER", username,
                    "Sistem bakımı başladığı için canlı oturum sonlandırıldı (hatırlanan girişler silindi)",
                    auditService.resolveIp(req), req.getHeader("User-Agent"), sid);
            try { systemMaintenance.recordSessionEnded(); } catch (Exception ignored) { /* sayaç best-effort */ }
        }
    }

    /** 401 + {@code MAINTENANCE} gövdesi (pencere bilgisiyle) — istemci geri sayım penceresini açar, sonra giriş sayfası. */
    private boolean writeMaintenance(HttpServletResponse res) throws Exception {
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body;
        try {
            body = systemMaintenance != null ? systemMaintenance.signalBody()
                    : com.sitemonitor.util.SystemMaintenanceSignal.body("Maintenance", null);
        } catch (Exception e) {
            body = com.sitemonitor.util.SystemMaintenanceSignal.body("Maintenance", null);
        }
        mapper.writeValue(res.getWriter(), body);
        return false;
    }

    private static Long longAttr(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        try { return v == null ? null : Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    /** Pasif kesim / pasif çerez denetimi yazılsın mı (aynı anahtar için 30 sn'de bir)? */
    private boolean shouldAuditInactive(String key) {
        long now = System.currentTimeMillis();
        Long last = inactiveAuditAtMs.get(key);
        if (last != null && now - last < REAUTH_AUDIT_DEBOUNCE_MS) return false;
        if (inactiveAuditAtMs.size() > REAUTH_MAP_MAX) inactiveAuditAtMs.clear();
        inactiveAuditAtMs.put(key, now);
        return true;
    }

    /** 401 + {@code ACCOUNT_INACTIVE} gövdesi — istemci "Hesabınız pasife alındı" penceresini açar (yönlendirme değil). */
    private boolean writeAccountInactive(HttpServletResponse res) throws Exception {
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(res.getWriter(), com.sitemonitor.util.AccountInactive.body());
        return false;
    }

    /** Temiz 401 JSON yanıtı (frontend bunu yakalayıp otomatik logout eder). */
    private boolean writeUnauthorized(HttpServletResponse res, String error) throws Exception {
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(res.getWriter(), Map.of("success", false, "error", error));
        return false;
    }

    /** Süpersede edilen tarafta remember-me cookie'sini sil — redirect sonrası sessiz reauth olmasın.
     *  Eski (rename öncesi) cookie adı da silinir. */
    private void clearRememberMeCookie(HttpServletResponse res) {
        for (String name : new String[]{RememberMeService.COOKIE_NAME, RememberMeService.LEGACY_COOKIE_NAME}) {
            Cookie del = new Cookie(name, "");
            del.setMaxAge(0);
            del.setHttpOnly(true);
            del.setPath("/");
            res.addCookie(del);
        }
    }

    /**
     * Forced password change: while the flag is set, only the whitelisted
     * endpoints are reachable so the user cannot sidestep the modal by hitting
     * another API directly. Returns true when the request may proceed.
     */
    private boolean enforceForcedPasswordChange(HttpSession session, String path, HttpServletResponse res)
            throws Exception {
        if (Boolean.TRUE.equals(session.getAttribute("mustChangePassword"))
                && !FORCED_CHANGE_WHITELIST.contains(path)) {
            res.setStatus(403);
            res.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(res.getWriter(),
                    Map.of("success", false, "error", "Password change required"));
            return false;
        }
        return true;
    }

    private Optional<String> findRememberMeCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return Optional.empty();
        // Önce yeni ad; yoksa rename öncesi verilmiş eski cookie (geriye-uyum) — token DB'de aynı.
        return Arrays.stream(cookies)
                .filter(c -> RememberMeService.COOKIE_NAME.equals(c.getName())
                        || RememberMeService.LEGACY_COOKIE_NAME.equals(c.getName()))
                .sorted((a, b) -> Boolean.compare(
                        RememberMeService.LEGACY_COOKIE_NAME.equals(a.getName()),
                        RememberMeService.LEGACY_COOKIE_NAME.equals(b.getName())))
                .map(Cookie::getValue)
                .findFirst();
    }
}
