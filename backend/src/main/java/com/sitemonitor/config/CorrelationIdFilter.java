package com.sitemonitor.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Her isteğe bir korelasyon (istek) kimliği atar, request attribute'una koyar ve yanıt başlığına yansıtır. Denetim
 * kayıtları bunu {@code correlation_id}'ye yazar → aynı istekten doğan ilişkili olaylar (ör. çoklu kaynak güncellemesi)
 * bağlanabilir.
 *
 * <p><b>İstek kimliği (2026-10-08, hata iletileri):</b> gelen {@code X-Request-Id} (ön taraftaki proxy/NetScaler) ya da
 * {@code X-Correlation-Id} varsa ve güvenliyse (≤ 40 karakter, yalnız {@code [A-Za-z0-9._:=-]} — log/başlık
 * enjeksiyonu olmasın) o kullanılır, yoksa üretilir. Yanıtta HER İKİ başlık da döner; hata gövdeleri aynı değeri
 * {@code request_id} olarak taşır (GlobalExceptionHandler) ve kullanıcı arayüzü onu "Teknik ayrıntı"da gösterir —
 * kullanıcının ilettiği kimlik, sunucu logundaki hata satırını ({@code [istek=…]}) doğrudan bulur.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String ATTR = "correlationId";
    public static final String HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Kabul edilen dış kimlik: audit kolonu 40 karakter; başlık/log enjeksiyonuna kapalı karakter kümesi. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:=-]{1,40}");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String cid = safe(req.getHeader(REQUEST_ID_HEADER));
        if (cid == null) cid = safe(req.getHeader(HEADER));
        if (cid == null) cid = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        req.setAttribute(ATTR, cid);
        res.setHeader(HEADER, cid);
        res.setHeader(REQUEST_ID_HEADER, cid);
        chain.doFilter(req, res);
    }

    /** Güvenli dış kimlik ya da null. */
    static String safe(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        return SAFE_ID.matcher(v).matches() ? v : null;
    }

    /** İstekten korelasyon kimliğini okur (yoksa null). */
    public static String get(HttpServletRequest req) {
        Object v = req != null ? req.getAttribute(ATTR) : null;
        return v != null ? v.toString() : null;
    }

    /** O anki isteğin kimliği (istek bağlamı yoksa — zamanlayıcı, boot, birim testi — null). */
    public static String current() {
        try {
            RequestAttributes ra = RequestContextHolder.getRequestAttributes();
            if (ra instanceof ServletRequestAttributes sra) return get(sra.getRequest());
        } catch (Exception ignored) {
            // bağlam yok
        }
        return null;
    }
}
