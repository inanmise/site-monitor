package com.certmonitor.config;

import com.certmonitor.service.HttpMetricsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
@RequiredArgsConstructor
public class HttpMetricsInterceptor implements HandlerInterceptor {

    private static final String START_ATTR = "_t";

    private final HttpMetricsService httpMetricsService;

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        req.setAttribute(START_ATTR, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res,
                                Object handler, Exception ex) {
        Long start = (Long) req.getAttribute(START_ATTR);
        if (start == null) return;

        // Skip the metrics endpoints themselves (+ their sub-paths) to avoid self-referential noise
        String uri = req.getRequestURI();
        if (uri.endsWith("/metrics") || uri.contains("/http-metrics")) return;

        // Endpoint = method + route şablonu (ör. "GET /api/incidents/{id}") — ham URI yerine
        // (kardinalite sınırlı). Şablon yoksa (eşleşmeyen istek) ham URI'ye düş.
        Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = (pattern instanceof String p && !p.isBlank()) ? p : uri;
        httpMetricsService.record(req.getMethod() + " " + route, res.getStatus(),
                System.currentTimeMillis() - start);
    }
}
