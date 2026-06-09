package com.certmonitor.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Logs all incoming HTTP requests at DEBUG level: timing, method, URI, query,
 * client IP, headers (sensitive redacted), request body, response status,
 * response body, total duration.
 *
 * Skipped paths: /health, static assets — too noisy.
 * Sensitive headers redacted: Authorization, Cookie, etc.
 * Sensitive body fields masked: password, token, etc.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie",
            "x-csrf-token", "x-auth-token", "x-api-key", "proxy-authorization"
    );

    private static final Pattern SENSITIVE_BODY_FIELDS = Pattern.compile(
            "(?i)(\"(?:password|old_password|new_password|current_password|" +
                    "smtp_pass|smtpPass|token|apiKey|secret)\"\\s*:\\s*)\"[^\"]*\""
    );

    private static final int MAX_BODY_LOG = 2000;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {

        if (!log.isDebugEnabled() || shouldSkip(req)) {
            chain.doFilter(req, resp);
            return;
        }

        ContentCachingRequestWrapper wReq = new ContentCachingRequestWrapper(req);
        ContentCachingResponseWrapper wResp = new ContentCachingResponseWrapper(resp);
        long start = System.currentTimeMillis();
        String query = req.getQueryString();
        String fullUri = req.getRequestURI() + (query != null ? "?" + query : "");

        log.debug(">>> {} {} from {} | headers={}",
                req.getMethod(), fullUri, clientIp(req), sanitizedHeaders(req));

        try {
            chain.doFilter(wReq, wResp);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            String reqBody  = redact(bodyOf(wReq.getContentAsByteArray()));
            String respBody = redact(bodyOf(wResp.getContentAsByteArray()));
            log.debug("<<< {} {} -> {} ({} ms) | reqBody={} | respBody={}",
                    req.getMethod(), fullUri, wResp.getStatus(), durationMs,
                    truncate(reqBody), truncate(respBody));
            wResp.copyBodyToResponse();
        }
    }

    private boolean shouldSkip(HttpServletRequest req) {
        String uri = req.getRequestURI();
        if (uri == null) return false;
        return uri.equals("/health") || uri.equals("/favicon.ico")
                || uri.startsWith("/assets/") || uri.startsWith("/static/")
                || uri.endsWith(".js") || uri.endsWith(".css") || uri.endsWith(".map")
                || uri.endsWith(".png") || uri.endsWith(".svg") || uri.endsWith(".woff2")
                || uri.endsWith(".ico");
    }

    private String sanitizedHeaders(HttpServletRequest req) {
        Map<String, String> map = new LinkedHashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        if (names == null) return "{}";
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String value = SENSITIVE_HEADERS.contains(name.toLowerCase())
                    ? "***REDACTED***"
                    : req.getHeader(name);
            map.put(name, value);
        }
        return map.toString();
    }

    private String bodyOf(byte[] buf) {
        if (buf == null || buf.length == 0) return "";
        return new String(buf, StandardCharsets.UTF_8);
    }

    private String redact(String body) {
        if (body == null || body.isEmpty()) return body;
        return SENSITIVE_BODY_FIELDS.matcher(body).replaceAll("$1\"***\"");
    }

    private String truncate(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= MAX_BODY_LOG ? s : s.substring(0, MAX_BODY_LOG) + "...[truncated]";
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
