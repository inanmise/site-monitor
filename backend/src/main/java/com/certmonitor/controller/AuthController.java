package com.certmonitor.controller;

import com.certmonitor.model.AppUser;
import com.certmonitor.model.Team;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
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

    @Value("${cert.monitor.login.max-attempts:10}")
    private int maxLoginAttempts;

    /** How long (seconds) an IP stays blocked after hitting max-attempts. */
    @Value("${cert.monitor.login.block-seconds:30}")
    private int blockSeconds;

    @Value("${cert.monitor.remember.ttl-seconds:604800}")
    private int rememberTtlSeconds;

    private final AuditService auditService;
    private final RememberMeService rememberMeService;
    private final UserService userService;

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

        Optional<AppUser> userOpt = userService.authenticate(username, password);

        if (userOpt.isPresent()) {
            AppUser user = userOpt.get();

            // Admin-issued temp passwords expire after 24 hours. Treat an
            // expired temp pwd as a separate failure with its own error_code
            // so the UI can show a precise message instead of "wrong password".
            if (userService.isTempPasswordExpired(user)) {
                log.info("Login rejected — temp password expired: user={} IP={}", username, clientIp);
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

            HttpSession oldSession = request.getSession(false);
            if (oldSession != null) oldSession.invalidate();
            HttpSession newSession = request.getSession(true);
            populateSession(newSession, user);

            log.info("User logged in: {} (role={}, teamId={}, rememberMe={}, IP={})",
                    username, user.getSystemRole(), user.getTeamId(), rememberMe, clientIp);
            auditService.recordLogin(username, user.getId(), user.getTeamId(),
                    user.getSystemRole(), clientIp,
                    request.getHeader("User-Agent"), newSession.getId(), true, null, null, 5);

            if (rememberMe) {
                String token = rememberMeService.generateToken(username);
                Cookie cookie = new Cookie(RememberMeService.COOKIE_NAME, token);
                cookie.setMaxAge(rememberTtlSeconds);
                cookie.setHttpOnly(true);
                cookie.setSecure(cookieSecure);
                cookie.setPath("/");
                response.addCookie(cookie);
            }
            return ResponseEntity.ok(buildMeResponse(user));
        }

        // 3. Failed — record attempt, check for BRUTE_FORCE, apply progressive lockout
        recordFailedAttempt(clientIp);
        log.warn("Failed login attempt: IP={}, username={}", clientIp, username);
        // Look up user's lockout context: window start (countSince) and required failures for this level
        String lastLockoutAt = null;
        int failuresNeeded = 5;
        if (!username.isBlank()) {
            var failedUser = userService.findByUsername(username);
            if (failedUser.isPresent()) {
                var u = failedUser.get();
                lastLockoutAt = u.getLastLockoutAt();
                failuresNeeded = userService.failuresNeededForLevel(u.getFailedBlockCount());
            }
        }
        com.certmonitor.model.AuditLog logged = auditService.recordLogin(
                username, null, null, null, clientIp,
                request.getHeader("User-Agent"), null, false, "Invalid credentials",
                lastLockoutAt, failuresNeeded);

        if (!username.isBlank() && logged.getAnomalyFlags() != null
                && logged.getAnomalyFlags().contains("BRUTE_FORCE")) {
            UserService.LockoutStatus ls = userService.applyProgressiveLockout(username);
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
            Arrays.stream(cookies)
                    .filter(c -> RememberMeService.COOKIE_NAME.equals(c.getName()))
                    .findFirst()
                    .ifPresent(c -> {
                        rememberMeService.invalidate(c.getValue());
                        Cookie del = new Cookie(RememberMeService.COOKIE_NAME, "");
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
            session.invalidate();
            if (username != null) {
                Long uid = userId instanceof Long l ? l : userId != null ? Long.parseLong(userId.toString()) : null;
                auditService.recordLogout(username, uid, resolveClientIp(request), sid);
            }
        }
        return ResponseEntity.ok(Map.of("success", true, "message", "Logged out"));
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
        return ResponseEntity.ok(resp);
    }

    /**
     * Self-service password change. Any authenticated user can rotate their own
     * password by re-proving knowledge of the current one — no admin role
     * required. Same UserService rules apply (length, history, archive).
     */
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Write all user context into the session. */
    public void populateSession(HttpSession session, AppUser user) {
        session.setAttribute("authenticated", true);
        session.setAttribute("username", user.getUsername());
        session.setAttribute("displayName", user.getDisplayName() != null ? user.getDisplayName() : user.getUsername());
        session.setAttribute("userId", user.getId());
        session.setAttribute("teamId", user.getTeamId());
        session.setAttribute("systemRole", user.getSystemRole());
        session.setAttribute("mustChangePassword",
                Boolean.TRUE.equals(user.getMustChangePassword()));
        // Resolve team name
        String teamName = user.getTeamId() != null
                ? userService.findTeamById(user.getTeamId()).map(Team::getName).orElse(null)
                : null;
        session.setAttribute("teamName", teamName);
    }

    private Map<String, Object> buildMeResponse(AppUser user) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Login successful");
        resp.put("username", user.getUsername());
        resp.put("user_id", user.getId());
        resp.put("team_id", user.getTeamId());
        resp.put("team_name", user.getTeamId() != null
                ? userService.findTeamById(user.getTeamId()).map(Team::getName).orElse(null) : null);
        resp.put("system_role", user.getSystemRole());
        resp.put("must_change_password", Boolean.TRUE.equals(user.getMustChangePassword()));
        return resp;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return AuditService.normalizeIp(ip);
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
}
