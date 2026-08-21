package com.sitemonitor.config;

import com.sitemonitor.service.HttpMetricsService;
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

        // Endpoint = method + route şablonu (ör. "GET /api/incidents/{id}") — kardinalitesi sınırlı.
        // Şablon YOKSA ham URI'ye DÜŞÜLMEZ (2026-08-20 bellek denetimi): ham URI, metrik anahtarının
        // kardinalitesini istemci kontrolüne bırakır ve hem currentEndpoints haritasını hem
        // http_metric_minute tablosunu sınırsız büyütebilir. Tek "(unmatched)" kovasına katlanır;
        // hangi yolun istendiği erişim log'unda ve client-errors yüzeyinde zaten duruyor.
        Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = (pattern instanceof String p && !p.isBlank()) ? p : "(unmatched)";
        httpMetricsService.record(req.getMethod() + " " + route, res.getStatus(),
                System.currentTimeMillis() - start);
    }
}
