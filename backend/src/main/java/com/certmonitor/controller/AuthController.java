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

    private final AuditService auditService;
    private final RememberMeService rememberMeService;
    private final UserService userService;

    // Brute-force protection: IP → failed-attempt count (reset every minute)
    private final ConcurrentHashMap<String, AtomicInteger> loginAttempts = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong lastAttemptReset =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> body,
            HttpServletRequest request,
            HttpServletResponse response) {

        String clientIp = resolveClientIp(request);
        if (isRateLimited(clientIp)) {
            log.warn("Login rate limit exceeded: IP={}", clientIp);
            return ResponseEntity.status(429)
                    .body(Map.of("success", false, "error", "Too many login attempts. Please wait."));
        }

        String username = body.getOrDefault("username", "").strip();
        String password = body.getOrDefault("password", "").strip();
        boolean rememberMe = Boolean.parseBoolean(body.getOrDefault("remember_me", "false"));

        Optional<AppUser> userOpt = userService.authenticate(username, password);

        if (userOpt.isPresent()) {
            AppUser user = userOpt.get();
            loginAttempts.remove(clientIp);

            // Session fixation prevention
            HttpSession oldSession = request.getSession(false);
            if (oldSession != null) oldSession.invalidate();

            HttpSession newSession = request.getSession(true);
            populateSession(newSession, user);

            log.info("User logged in: {} (role={}, teamId={}, rememberMe={}, IP={})",
                    username, user.getSystemRole(), user.getTeamId(), rememberMe, clientIp);
            auditService.recordLogin(username, user.getId(), user.getTeamId(),
                    user.getSystemRole(), clientIp,
                    request.getHeader("User-Agent"), newSession.getId(),
                    true, null);

            if (rememberMe) {
                String token = rememberMeService.generateToken(username);
                Cookie cookie = new Cookie(RememberMeService.COOKIE_NAME, token);
                cookie.setMaxAge(7 * 24 * 3600);
                cookie.setHttpOnly(true);
                cookie.setSecure(cookieSecure);
                cookie.setPath("/");
                response.addCookie(cookie);
            }

            return ResponseEntity.ok(buildMeResponse(user));
        }

        recordFailedAttempt(clientIp);
        log.warn("Failed login attempt: IP={}, username={}", clientIp, username);
        auditService.recordLogin(username, null, null, null, clientIp,
                request.getHeader("User-Agent"), null,
                false, "Invalid credentials");
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
        return resp;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return AuditService.normalizeIp(ip);
    }

    private boolean isRateLimited(String ip) {
        resetIfNeeded();
        return loginAttempts.computeIfAbsent(ip, k -> new AtomicInteger(0)).get() >= maxLoginAttempts;
    }

    private void recordFailedAttempt(String ip) {
        resetIfNeeded();
        loginAttempts.computeIfAbsent(ip, k -> new AtomicInteger(0)).incrementAndGet();
    }

    private void resetIfNeeded() {
        long now = System.currentTimeMillis();
        long prev = lastAttemptReset.get();
        if (now - prev > 60_000L && lastAttemptReset.compareAndSet(prev, now)) {
            loginAttempts.clear();
        }
    }
}
