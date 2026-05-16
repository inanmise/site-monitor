package com.certmonitor.controller;

import com.certmonitor.service.RememberMeService;
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
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    @Value("${cert.monitor.username:user}")
    private String validUsername;

    @Value("${cert.monitor.password:password}")
    private String validPassword;

    private final RememberMeService rememberMeService;

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> body,
            HttpSession session,
            HttpServletResponse response) {

        String username = body.getOrDefault("username", "").strip();
        String password = body.getOrDefault("password", "").strip();
        boolean rememberMe = Boolean.parseBoolean(body.getOrDefault("remember_me", "false"));

        if (validUsername.equals(username) && validPassword.equals(password)) {
            session.setAttribute("authenticated", true);
            session.setAttribute("username", username);
            log.info("Kullanıcı giriş yaptı: {} (rememberMe={})", username, rememberMe);

            if (rememberMe) {
                String token = rememberMeService.generateToken(username);
                Cookie cookie = new Cookie(RememberMeService.COOKIE_NAME, token);
                cookie.setMaxAge(7 * 24 * 3600); // 7 gün
                cookie.setHttpOnly(true);
                cookie.setPath("/");
                response.addCookie(cookie);
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Başarıyla giriş yapıldı",
                    "username", username));
        }

        log.warn("Başarısız giriş denemesi: {}", username);
        return ResponseEntity.status(401)
                .body(Map.of("success", false, "error", "Hatalı kullanıcı adı veya şifre"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session) {

        // Remember-me token'ını iptal et ve cookie'yi sil
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            Arrays.stream(cookies)
                    .filter(c -> RememberMeService.COOKIE_NAME.equals(c.getName()))
                    .findFirst()
                    .ifPresent(c -> {
                        rememberMeService.invalidate(c.getValue());
                        Cookie del = new Cookie(RememberMeService.COOKIE_NAME, "");
                        del.setMaxAge(0);
                        del.setPath("/");
                        response.addCookie(del);
                    });
        }

        session.invalidate();
        return ResponseEntity.ok(Map.of("success", true, "message", "Çıkış yapıldı"));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) username = "user";
        return ResponseEntity.ok(Map.of("success", true, "username", username));
    }
}
