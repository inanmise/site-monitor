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
            "/api/client-error-report");

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

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/") || PUBLIC.contains(path)) return true;

        // 1. Valid session check
        HttpSession session = req.getSession(false);
        if (session != null) {
            try {
                if (Boolean.TRUE.equals(session.getAttribute("authenticated"))) {
                    // Tek aktif oturum: bu oturum kullanıcının kayıtlı (daha yeni) oturumu tarafından
                    // geçersiz kılındıysa (başka yerden login ya da admin "Sonlandır"), oturumu kapat ve
                    // temiz 401 dön → frontend otomatik logout (/?session=expired). remember-me cookie'si
                    // de silinir ki kullanıcı sessizce geri dönmesin.
                    String username = (String) session.getAttribute("username");
                    if (userService.isSessionSuperseded(username, session.getId())) {
                        try { session.invalidate(); } catch (IllegalStateException ignored) { /* zaten kapalı */ }
                        clearRememberMeCookie(res);
                        return writeUnauthorized(res, "Session superseded");
                    }
                    return enforceForcedPasswordChange(session, path, res);
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
        Optional<String> usernameOpt = findRememberMeCookie(req).flatMap(rememberMeService::validate);

        if (usernameOpt.isPresent()) {
            String username = usernameOpt.get();
            Optional<AppUser> userOpt = userService.findByUsername(username);
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
                String clientIp = auditService.resolveIp(req);
                userService.recordSuccessfulLogin(user.getUsername(), newSession.getId(),
                        clientIp, UserService.LoginMethod.REMEMBER_ME);
                if (shouldAuditReauth(user.getUsername())) {
                    auditService.recordLogin(user.getUsername(), user.getId(), user.getTeamId(),
                            user.getSystemRole(), clientIp, req.getHeader("User-Agent"),
                            newSession.getId(), true, null, null, 5);
                }
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
