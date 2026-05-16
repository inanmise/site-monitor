package com.certmonitor.config;

import com.certmonitor.service.RememberMeService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final Set<String> PUBLIC = Set.of("/api/login", "/api/logout");
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private RememberMeService rememberMeService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String path = req.getRequestURI();
        if (!path.startsWith("/api/") || PUBLIC.contains(path)) {
            return true;
        }

        // 1. Geçerli oturum kontrolü
        HttpSession session = req.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute("authenticated"))) {
            return true;
        }

        // 2. Remember-me cookie kontrolü — geçerliyse otomatik oturum aç
        Optional<String> usernameOpt = findRememberMeCookie(req)
                .flatMap(rememberMeService::validate);

        if (usernameOpt.isPresent()) {
            HttpSession newSession = req.getSession(true);
            newSession.setAttribute("authenticated", true);
            newSession.setAttribute("username", usernameOpt.get());
            return true;
        }

        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(res.getWriter(), Map.of("success", false, "error", "Yetkisiz erişim"));
        return false;
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
