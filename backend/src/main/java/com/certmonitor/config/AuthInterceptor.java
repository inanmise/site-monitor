package com.certmonitor.config;

import com.certmonitor.controller.AuthController;
import com.certmonitor.model.AppUser;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC = Set.of(
            "/api/login", "/api/logout",
            // Haftalık rapor e-posta onayı — PO login'siz, token ile onaylar (token = yetki).
            "/api/weekly-reports/approve-link",
            "/api/weekly-reports/approve-link/confirm");

    /** Endpoints a user with mustChangePassword=true is still allowed to call. */
    private static final Set<String> FORCED_CHANGE_WHITELIST = Set.of(
            "/api/me",
            "/api/me/change-password",
            "/api/logout",
            "/api/login"
    );

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private RememberMeService rememberMeService;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthController authController;

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
                authController.populateSession(newSession, userOpt.get());
                // Tek aktif oturum: remember-me ile kurulan oturum da kullanıcının "aktif" oturumu olsun.
                userService.recordActiveSession(username, newSession.getId());
                // Apply the forced-password-change gate to the restored session too,
                // so the cookie path can't sidestep the modal for one request.
                return enforceForcedPasswordChange(newSession, path, res);
            }
        }

        return writeUnauthorized(res, "Unauthorized");
    }

    /** Temiz 401 JSON yanıtı (frontend bunu yakalayıp otomatik logout eder). */
    private boolean writeUnauthorized(HttpServletResponse res, String error) throws Exception {
        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(res.getWriter(), Map.of("success", false, "error", error));
        return false;
    }

    /** Süpersede edilen tarafta remember-me cookie'sini sil — redirect sonrası sessiz reauth olmasın. */
    private void clearRememberMeCookie(HttpServletResponse res) {
        Cookie del = new Cookie(RememberMeService.COOKIE_NAME, "");
        del.setMaxAge(0);
        del.setHttpOnly(true);
        del.setPath("/");
        res.addCookie(del);
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
        return Arrays.stream(cookies)
                .filter(c -> RememberMeService.COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
