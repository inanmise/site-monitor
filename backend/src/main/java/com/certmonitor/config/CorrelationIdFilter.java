package com.certmonitor.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Her isteğe bir korelasyon kimliği atar (gelen {@code X-Correlation-Id} varsa onu kullanır, yoksa üretir),
 * request attribute'una koyar ve yanıt başlığına yansıtır. Denetim kayıtları bunu {@code correlation_id}'ye
 * yazar → aynı istekten doğan ilişkili olaylar (ör. çoklu kaynak güncellemesi) bağlanabilir.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String ATTR = "correlationId";
    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String cid = req.getHeader(HEADER);
        if (cid == null || cid.isBlank() || cid.length() > 40) {
            cid = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        }
        req.setAttribute(ATTR, cid);
        res.setHeader(HEADER, cid);
        chain.doFilter(req, res);
    }

    /** İstekten korelasyon kimliğini okur (yoksa null). */
    public static String get(HttpServletRequest req) {
        Object v = req != null ? req.getAttribute(ATTR) : null;
        return v != null ? v.toString() : null;
    }
}
